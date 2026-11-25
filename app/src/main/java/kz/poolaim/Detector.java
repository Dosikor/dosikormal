package kz.poolaim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Поиск шаров на снимке стола (без Android API).
 * Вход — сетка w×h пикселей (0xRRGGBB), в которой радиус шара ≈ ra пикселей,
 * и маска unknown: пиксели, закрытые нашими же линиями/панелью.
 *
 * 1) цвет сукна = медиана цвета; локальная яркость сукна — по блокам;
 * 2) пиксель «шар», если сильно отличается от сукна по цвету или яркости;
 * 3) карта расстояний до ближайшего «не шара»: в центре шара она ≈ ra,
 *    а у тонких линий (кий, линия прицела) — маленькая;
 * 4) локальные максимумы → центры, уточнение центроидом, проверка формы.
 */
final class Detector {

    static final class Blob {
        float x, y;      // центр в координатах сетки (индексы пикселей)
        float white;     // доля белых пикселей
        float dark;      // доля тёмных пикселей
        int type = Geo.SOLID;
        int color;       // основной цвет шара (для линий)
    }

    static List<Blob> detect(int[] rgb, int w, int h, boolean[] unknown, float ra) {
        int n = w * h;
        List<Blob> res = new ArrayList<>();
        if (n == 0 || ra < 2) return res;

        int[] Y = new int[n], Cb = new int[n], Cr = new int[n];
        int[] hy = new int[256], hb = new int[256], hr = new int[256];
        int known = 0;
        for (int i = 0; i < n; i++) {
            int c = rgb[i];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            int y = (299 * r + 587 * g + 114 * b) / 1000;
            int cb = clamp8((-169 * r - 331 * g + 500 * b) / 1000 + 128);
            int cr = clamp8((500 * r - 419 * g - 81 * b) / 1000 + 128);
            Y[i] = y;
            Cb[i] = cb;
            Cr[i] = cr;
            if (unknown == null || !unknown[i]) {
                hy[y]++;
                hb[cb]++;
                hr[cr]++;
                known++;
            }
        }
        if (known < n / 4) return res;
        int fy = median(hy, known), fb = median(hb, known), fr = median(hr, known);

        // Локальный цвет сукна по блокам (к бортам сукно сильно темнеет): «сукноподобные» — тот же оттенок.
        double fHue = Math.atan2(fr - 128, fb - 128);
        float fRatio = (float) Math.hypot(fr - 128, fb - 128) / Math.max(10, fy);
        int bs = Math.max(8, Math.round(ra * 2.5f));
        int bw = (w + bs - 1) / bs, bh = (h + bs - 1) / bs;
        float[] blockY = new float[bw * bh], blockB = new float[bw * bh], blockR = new float[bw * bh];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                long sum = 0, sb = 0, sr = 0;
                int cnt = 0;
                for (int y = by * bs; y < Math.min(h, (by + 1) * bs); y++) {
                    for (int x = bx * bs; x < Math.min(w, (bx + 1) * bs); x++) {
                        int i = y * w + x;
                        if (unknown != null && unknown[i]) continue;
                        if (TableFinder.feltLike(Y[i], Cb[i], Cr[i], fHue, fRatio, fy) && Y[i] > fy * 0.25f) {
                            sum += Y[i];
                            sb += Cb[i];
                            sr += Cr[i];
                            cnt++;
                        }
                    }
                }
                int k = by * bw + bx;
                if (cnt > bs * bs / 6) {
                    blockY[k] = (float) sum / cnt;
                    blockB[k] = (float) sb / cnt;
                    blockR[k] = (float) sr / cnt;
                } else {
                    blockY[k] = fy;
                    blockB[k] = fb;
                    blockR[k] = fr;
                }
            }
        }

        // Классификация пикселей: 0 — сукно, 1 — шар, 2 — неизвестно (наши линии).
        byte[] cls = new byte[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (unknown != null && unknown[i]) {
                    cls[i] = 2;
                    continue;
                }
                int bk = (y / bs) * bw + (x / bs);
                float ly = Math.max(12f, blockY[bk]);
                float lr = Y[i] / ly;
                float db = Cb[i] - blockB[bk], dr = Cr[i] - blockR[bk];
                boolean chroma = db * db + dr * dr > 18 * 18;
                cls[i] = (byte) ((chroma || lr > 1.42f || lr < 0.5f) ? 1 : 0);
            }
        }

        // Карта расстояний (фаска 3-4), края сетки считаются сукном.
        int INF = 1 << 20;
        int[] d = new int[n];
        for (int i = 0; i < n; i++) d[i] = cls[i] != 0 ? INF : 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (d[i] == 0) continue;
                int v = d[i];
                v = Math.min(v, x > 0 ? d[i - 1] + 3 : 3);
                v = Math.min(v, y > 0 ? d[i - w] + 3 : 3);
                v = Math.min(v, (x > 0 && y > 0) ? d[i - w - 1] + 4 : 4);
                v = Math.min(v, (x < w - 1 && y > 0) ? d[i - w + 1] + 4 : 4);
                d[i] = v;
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                if (d[i] == 0) continue;
                int v = d[i];
                v = Math.min(v, x < w - 1 ? d[i + 1] + 3 : 3);
                v = Math.min(v, y < h - 1 ? d[i + w] + 3 : 3);
                v = Math.min(v, (x < w - 1 && y < h - 1) ? d[i + w + 1] + 4 : 4);
                v = Math.min(v, (x > 0 && y < h - 1) ? d[i + w - 1] + 4 : 4);
                d[i] = v;
            }
        }

        // Кандидаты: точки, где до сукна не меньше ~0.6 радиуса.
        int thr = Math.round(0.6f * ra * 3);
        int cnt = 0;
        for (int i = 0; i < n; i++) if (d[i] >= thr) cnt++;
        long[] cand = new long[cnt];
        int k = 0;
        for (int i = 0; i < n; i++) if (d[i] >= thr) cand[k++] = ((long) d[i] << 32) | i;
        Arrays.sort(cand);

        float minSep = 1.5f * ra;
        float[] ax = new float[64], ay = new float[64];
        int na = 0;
        int rr = (int) Math.ceil(ra);
        for (int c = cand.length - 1; c >= 0 && na < 40; c--) {
            int i = (int) (cand[c] & 0xFFFFFFFFL);
            float x = i % w, y = i / w;
            boolean near = false;
            for (int j = 0; j < na; j++) {
                float dx = ax[j] - x, dy = ay[j] - y;
                if (dx * dx + dy * dy < minSep * minSep) {
                    near = true;
                    break;
                }
            }
            if (near) continue;

            // Уточняем центр: центроид пикселей шара в круге 0.95·ra (дважды).
            for (int it = 0; it < 2; it++) {
                float sx = 0, sy = 0;
                int m = 0;
                float rad2 = 0.95f * ra * 0.95f * ra;
                int x0 = Math.round(x), y0 = Math.round(y);
                for (int yy = Math.max(0, y0 - rr); yy <= Math.min(h - 1, y0 + rr); yy++) {
                    for (int xx = Math.max(0, x0 - rr); xx <= Math.min(w - 1, x0 + rr); xx++) {
                        float dx = xx - x, dy = yy - y;
                        if (dx * dx + dy * dy > rad2) continue;
                        if (cls[yy * w + xx] != 0) {
                            sx += xx;
                            sy += yy;
                            m++;
                        }
                    }
                }
                if (m > 0) {
                    x = sx / m;
                    y = sy / m;
                }
            }

            // Проверка: круг почти целиком «шар», немного «неизвестно».
            int tot = 0, ball = 0, unk = 0, white = 0, dark = 0;
            float rad2 = 0.95f * ra * 0.95f * ra;
            int x0 = Math.round(x), y0 = Math.round(y);
            for (int yy = Math.max(0, y0 - rr); yy <= Math.min(h - 1, y0 + rr); yy++) {
                for (int xx = Math.max(0, x0 - rr); xx <= Math.min(w - 1, x0 + rr); xx++) {
                    float dx = xx - x, dy = yy - y;
                    if (dx * dx + dy * dy > rad2) continue;
                    int ii = yy * w + xx;
                    tot++;
                    if (cls[ii] == 2) {
                        unk++;
                        continue;
                    }
                    if (cls[ii] == 1) ball++;
                    int c2 = rgb[ii];
                    int r = (c2 >> 16) & 255, g = (c2 >> 8) & 255, b = c2 & 255;
                    int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                    if (Y[ii] > 125 && mx - mn < 55) white++;
                    if (Y[ii] < 45 && mx - mn < 30) dark++;
                }
            }
            if (tot < 4) continue;
            int kn = tot - unk;
            if (unk > 0.65f * tot) continue;
            if (kn > 0 && ball < 0.75f * kn) continue;

            Blob bl = new Blob();
            bl.x = x;
            bl.y = y;
            bl.white = kn > 0 ? (float) white / kn : 0f;
            bl.dark = kn > 0 ? (float) dark / kn : 0f;
            res.add(bl);
            ax[na] = x;
            ay[na] = y;
            na++;
        }

        for (Blob b : res) b.color = ballColor(rgb, w, h, cls, b.x, b.y, ra);

        // Второй проход: шары, цвет которых почти как у сукна (зелёный на зелёном, полосатый с зелёной полосой),
        // ищем по круглому контуру (преобразование Хафа с известным радиусом).
        int before = res.size();
        houghPass(rgb, w, h, unknown, ra, Y, Cb, Cr, cls, fy, res, ax, ay, na);
        for (int i = before; i < res.size(); i++) {
            Blob b = res.get(i);
            b.color = ballColor(rgb, w, h, null, b.x, b.y, ra);
        }
        return res;
    }

    /** Основной цвет шара: среднее насыщенных пикселей (полоса у полосатых); белый/чёрный — если таких мало. */
    static int ballColor(int[] rgb, int w, int h, byte[] cls, float cx, float cy, float ra) {
        long sr = 0, sg = 0, sb = 0;
        int cnt = 0, white = 0, dark = 0, tot = 0;
        int rr = (int) Math.ceil(ra);
        float rad2 = 0.8f * ra * 0.8f * ra;
        int x0 = Math.round(cx), y0 = Math.round(cy);
        for (int yy = Math.max(0, y0 - rr); yy <= Math.min(h - 1, y0 + rr); yy++) {
            for (int xx = Math.max(0, x0 - rr); xx <= Math.min(w - 1, x0 + rr); xx++) {
                float dx = xx - cx, dy = yy - cy;
                if (dx * dx + dy * dy > rad2) continue;
                int i = yy * w + xx;
                if (cls != null && cls[i] == 2) continue;
                int c = rgb[i];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                int y = (299 * r + 587 * g + 114 * b) / 1000;
                tot++;
                if (y > 125 && mx - mn < 55) {
                    white++;
                } else if (y < 45 && mx - mn < 30) {
                    dark++;
                } else if (mx - mn >= 45) {
                    sr += r;
                    sg += g;
                    sb += b;
                    cnt++;
                }
            }
        }
        if (tot == 0) return 0xFFFFFFFF;
        if (cnt >= Math.max(3, tot / 6)) {
            return 0xFF000000 | ((int) (sr / cnt) << 16) | ((int) (sg / cnt) << 8) | (int) (sb / cnt);
        }
        return dark > white ? 0xFF202020 : 0xFFFFFFFF;
    }

    private static void houghPass(int[] rgb, int w, int h, boolean[] unknown, float ra,
                                  int[] Y, int[] Cb, int[] Cr, byte[] cls, int fy,
                                  List<Blob> res, float[] ax, float[] ay, int na) {
        int n = w * h;
        float[] mag = new float[n], gxA = new float[n], gyA = new float[n];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                boolean skip = false;
                if (unknown != null) {
                    for (int dy = -1; dy <= 1 && !skip; dy++)
                        for (int dx = -1; dx <= 1; dx++)
                            if (unknown[i + dy * w + dx]) {
                                skip = true;
                                break;
                            }
                }
                if (skip) continue;
                float best = 0, bgx = 0, bgy = 0;
                for (int ch = 0; ch < 3; ch++) {
                    int[] a = ch == 0 ? Y : ch == 1 ? Cb : Cr;
                    float k = ch == 0 ? 1f : 1.5f;
                    float sx = (a[i - w + 1] + 2 * a[i + 1] + a[i + w + 1]) - (a[i - w - 1] + 2 * a[i - 1] + a[i + w - 1]);
                    float sy = (a[i + w - 1] + 2 * a[i + w] + a[i + w + 1]) - (a[i - w - 1] + 2 * a[i - w] + a[i - w + 1]);
                    float m = k * (float) Math.hypot(sx, sy);
                    if (m > best) {
                        best = m;
                        bgx = sx;
                        bgy = sy;
                    }
                }
                mag[i] = best;
                gxA[i] = bgx;
                gyA[i] = bgy;
            }
        }
        float gthr = 70f;
        float[] acc = new float[n];
        float[] radii = {0.9f * ra, ra, 1.1f * ra};
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (mag[i] < gthr) continue;
                float gl = (float) Math.hypot(gxA[i], gyA[i]);
                if (gl < 1e-3f) continue;
                float nx = gxA[i] / gl, ny = gyA[i] / gl;
                for (float rad : radii) {
                    for (int sg = -1; sg <= 1; sg += 2) {
                        int cx = Math.round(x + sg * rad * nx), cy = Math.round(y + sg * rad * ny);
                        if (cx >= 0 && cy >= 0 && cx < w && cy < h) acc[cy * w + cx] += 1f;
                    }
                }
            }
        }
        // Сглаживание 3×3.
        float[] sm = new float[n];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float s = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) s += acc[i + dy * w + dx];
                sm[i] = s;
            }
        float vthr = 0.45f * 3f * 2f * (float) Math.PI * ra;
        int cnt = 0;
        for (int i = 0; i < n; i++) if (sm[i] >= vthr) cnt++;
        long[] cand = new long[cnt];
        int k = 0;
        for (int i = 0; i < n; i++) if (sm[i] >= vthr) cand[k++] = ((long) Math.round(sm[i] * 16) << 32) | i;
        Arrays.sort(cand);
        float minSep = 1.5f * ra;
        int rr = (int) Math.ceil(ra * 1.2f);
        for (int c = cand.length - 1; c >= 0 && na < 40; c--) {
            int i = (int) (cand[c] & 0xFFFFFFFFL);
            int x = i % w, y = i / w;
            boolean near = false;
            for (int j = 0; j < na; j++) {
                float dx = ax[j] - x, dy = ay[j] - y;
                if (dx * dx + dy * dy < minSep * minSep) {
                    near = true;
                    break;
                }
            }
            if (near) continue;
            // Отличаем шар от тонкого кольца-прицела игры: внутри шара есть «не сукно» или он темнее сукна.
            int tot = 0, ballPx = 0, unk = 0, white = 0, dark = 0, ringBright = 0, ringTot = 0;
            long sumY = 0;
            for (int yy = Math.max(0, y - rr); yy <= Math.min(h - 1, y + rr); yy++) {
                for (int xx = Math.max(0, x - rr); xx <= Math.min(w - 1, x + rr); xx++) {
                    float dx = xx - x, dy = yy - y;
                    float d2 = dx * dx + dy * dy;
                    int ii = yy * w + xx;
                    if (d2 <= 0.85f * 0.85f * ra * ra) {
                        tot++;
                        if (cls[ii] == 2) {
                            unk++;
                            continue;
                        }
                        if (cls[ii] == 1) ballPx++;
                        sumY += Y[ii];
                        int c2 = rgb[ii];
                        int r = (c2 >> 16) & 255, g = (c2 >> 8) & 255, b = c2 & 255;
                        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                        if (Y[ii] > 125 && mx - mn < 55) white++;
                        if (Y[ii] < 45 && mx - mn < 30) dark++;
                    } else if (d2 <= 1.15f * 1.15f * ra * ra) {
                        ringTot++;
                        if (cls[ii] == 1 && Y[ii] > fy * 1.6f) ringBright++;
                    }
                }
            }
            int kn = tot - unk;
            if (kn < 0.35f * tot || kn < 4) continue;
            float fill = (float) ballPx / kn;
            float meanLr = (float) sumY / kn / Math.max(12, fy);
            boolean ringLike = ringTot > 0 && ringBright > 0.35f * ringTot && fill < 0.35f;
            if (ringLike) continue;
            if (fill < 0.35f && meanLr > 0.65f) continue; // внутри обычное сукно (или тень) — это не шар
            float wsum = 0, wx = 0, wy = 0;
            for (int yy = Math.max(0, y - 2); yy <= Math.min(h - 1, y + 2); yy++)
                for (int xx = Math.max(0, x - 2); xx <= Math.min(w - 1, x + 2); xx++) {
                    float v = acc[yy * w + xx];
                    wsum += v;
                    wx += v * xx;
                    wy += v * yy;
                }
            Blob bl = new Blob();
            bl.x = wsum > 0 ? wx / wsum : x;
            bl.y = wsum > 0 ? wy / wsum : y;
            bl.white = (float) white / kn;
            bl.dark = (float) dark / kn;
            res.add(bl);
            ax[na] = bl.x;
            ay[na] = bl.y;
            na++;
        }
    }

    /**
     * Типы шаров. Биток — самый белый (если похож на прошлый биток по месту — предпочитаем его).
     * Возвращает индекс битка или -1.
     */
    static int classify(List<Blob> blobs, float prevCueX, float prevCueY, float maxJump) {
        int cue = -1;
        float bestD = Float.MAX_VALUE;
        if (prevCueX >= 0) {
            for (int i = 0; i < blobs.size(); i++) {
                Blob b = blobs.get(i);
                if (b.white < 0.6f) continue;
                float dd = (float) Math.hypot(b.x - prevCueX, b.y - prevCueY);
                if (dd < maxJump && dd < bestD) {
                    bestD = dd;
                    cue = i;
                }
            }
        }
        if (cue < 0) {
            float bw = 0.62f;
            for (int i = 0; i < blobs.size(); i++) {
                if (blobs.get(i).white > bw) {
                    bw = blobs.get(i).white;
                    cue = i;
                }
            }
        }
        for (int i = 0; i < blobs.size(); i++) {
            Blob b = blobs.get(i);
            if (i == cue) b.type = Geo.CUE;
            else if (b.dark >= 0.45f) b.type = Geo.EIGHT;
            else if (b.white >= 0.36f) b.type = Geo.STRIPE;
            else b.type = Geo.SOLID;
        }
        return cue;
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static int median(int[] hist, int total) {
        int half = total / 2, acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc > half) return i;
        }
        return 128;
    }
}
