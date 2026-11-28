package kz.poolaim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Автонастройка: находит игровое поле (сукно) и радиус шара на снимке всего экрана (без Android API).
 * 1) цвет сукна — самый частый цвет в центре экрана;
 * 2) заливка от центра по пикселям цвета сукна → область стола;
 * 3) края — по строкам/столбцам, где сукна больше половины (срезает лузы и выступы);
 * 4) уточнение: ищем резкий перепад яркости у краёв (нос борта);
 * 5) радиус шара — по отдельным круглым пятнам «не сукна» внутри поля (медиана).
 */
final class TableFinder {

    static final class Table {
        float L, T, R, B, r;   // в пикселях сетки
        int feltCb, feltCr, feltY;
        int ballsMeasured;
    }

    static Table find(int[] rgb, int w, int h) {
        int n = w * h;
        if (n < 100) return null;
        int[] Y = new int[n], Cb = new int[n], Cr = new int[n];
        for (int i = 0; i < n; i++) {
            int c = rgb[i];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            Y[i] = (299 * r + 587 * g + 114 * b) / 1000;
            Cb[i] = clamp8((-169 * r - 331 * g + 500 * b) / 1000 + 128);
            Cr[i] = clamp8((500 * r - 419 * g - 81 * b) / 1000 + 128);
        }

        // 1) Цвет сукна: мода по (Cb,Cr) в центральной области.
        int[] hist = new int[64 * 64];
        int x0 = w / 4, x1 = 3 * w / 4, y0 = h / 4, y1 = 3 * h / 4;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                int i = y * w + x;
                hist[(Cb[i] >> 2) * 64 + (Cr[i] >> 2)]++;
            }
        int best = 0;
        for (int k = 1; k < hist.length; k++) if (hist[k] > hist[best]) best = k;
        int bCb = best / 64, bCr = best % 64;
        long sCb = 0, sCr = 0;
        int cnt = 0;
        List<Integer> ys = new ArrayList<>();
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                int i = y * w + x;
                if ((Cb[i] >> 2) == bCb && (Cr[i] >> 2) == bCr) {
                    sCb += Cb[i];
                    sCr += Cr[i];
                    cnt++;
                    if ((cnt & 3) == 0) ys.add(Y[i]);
                }
            }
        if (cnt < (x1 - x0) * (y1 - y0) / 6) return null; // в центре нет однотонного сукна
        int fCb = (int) (sCb / cnt), fCr = (int) (sCr / cnt);
        if (ys.isEmpty()) ys.add(128);
        Collections.sort(ys);
        int fY = Math.max(10, ys.get(ys.size() / 2));
        // Слишком серое «сукно» (меню, тёмный фон) — не стол.
        if ((fCb - 128) * (fCb - 128) + (fCr - 128) * (fCr - 128) < 6 * 6) return null;

        // Сукно: тот же оттенок (угол цветности), яркость может сильно падать к бортам (тень/виньетка).
        double fHue = Math.atan2(fCr - 128, fCb - 128);
        float fCh = (float) Math.hypot(fCr - 128, fCb - 128);
        float fRatio = fCh / Math.max(10, fY);
        boolean[] felt = new boolean[n];
        for (int i = 0; i < n; i++) {
            felt[i] = feltLike(Y[i], Cb[i], Cr[i], fHue, fRatio, fY);
        }

        // 2) Заливка от ближайшего к центру пикселя сукна.
        int seed = -1;
        for (int rad = 0; rad < Math.min(w, h) / 4 && seed < 0; rad++) {
            for (int dy = -rad; dy <= rad && seed < 0; dy++)
                for (int dx = -rad; dx <= rad; dx++) {
                    int x = w / 2 + dx, y = h / 2 + dy;
                    if (x >= 0 && y >= 0 && x < w && y < h && felt[y * w + x]) {
                        seed = y * w + x;
                        break;
                    }
                }
        }
        if (seed < 0) return null;
        boolean[] comp = new boolean[n];
        int[] queue = new int[n];
        int qh = 0, qt = 0;
        queue[qt++] = seed;
        comp[seed] = true;
        while (qh < qt) {
            int i = queue[qh++];
            int x = i % w, y = i / w;
            if (x > 0 && felt[i - 1] && !comp[i - 1]) { comp[i - 1] = true; queue[qt++] = i - 1; }
            if (x < w - 1 && felt[i + 1] && !comp[i + 1]) { comp[i + 1] = true; queue[qt++] = i + 1; }
            if (y > 0 && felt[i - w] && !comp[i - w]) { comp[i - w] = true; queue[qt++] = i - w; }
            if (y < h - 1 && felt[i + w] && !comp[i + w]) { comp[i + w] = true; queue[qt++] = i + w; }
        }
        if (qt < n / 10) return null;

        // 3) Края по профилям.
        int[] rowC = new int[h], colC = new int[w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (comp[y * w + x]) {
                    rowC[y]++;
                    colC[x]++;
                }
        int maxRow = 0, maxCol = 0;
        for (int v : rowC) maxRow = Math.max(maxRow, v);
        for (int v : colC) maxCol = Math.max(maxCol, v);
        int T0 = -1, B0 = -1, L0 = -1, R0 = -1;
        for (int y = 0; y < h; y++) if (rowC[y] >= 0.5f * maxRow) { if (T0 < 0) T0 = y; B0 = y; }
        for (int x = 0; x < w; x++) if (colC[x] >= 0.5f * maxCol) { if (L0 < 0) L0 = x; R0 = x; }
        if (T0 < 0 || L0 < 0) return null;
        float L = L0, R = R0 + 1, T = T0, B = B0 + 1;

        // 4) Уточнение: нос борта — резкий перепад яркости сукна недалеко от края.
        int tw = R0 - L0, th = B0 - T0;
        T = refine(Y, comp, w, T0, +1, Math.max(3, th / 14), L0 + tw / 6, R0 - tw / 6, (L0 + R0) / 2, tw / 14, true, fY, T);
        B = refine(Y, comp, w, B0, -1, Math.max(3, th / 14), L0 + tw / 6, R0 - tw / 6, (L0 + R0) / 2, tw / 14, true, fY, B - 1) + 1;
        L = refine(Y, comp, w, L0, +1, Math.max(3, tw / 28), T0 + th / 6, B0 - th / 6, -1, 0, false, fY, L);
        R = refine(Y, comp, w, R0, -1, Math.max(3, tw / 28), T0 + th / 6, B0 - th / 6, -1, 0, false, fY, R - 1) + 1;

        float aspect = (R - L) / Math.max(1f, B - T);
        if (aspect < 1.5f || aspect > 2.6f) return null;
        if (R - L < 0.35f * w) return null;

        // 5) Радиус шара по отдельным круглым пятнам.
        List<Float> radii = new ArrayList<>();
        boolean[] seen = new boolean[n];
        int il = (int) L + 2, ir = (int) R - 2, it = (int) T + 2, ib = (int) B - 2;
        for (int y = it; y < ib; y++) {
            for (int x = il; x < ir; x++) {
                int i = y * w + x;
                if (felt[i] || seen[i]) continue;
                // BFS по «не сукну»
                qh = 0;
                qt = 0;
                queue[qt++] = i;
                seen[i] = true;
                int mnx = x, mxx = x, mny = y, mxy = y;
                boolean touchesEdge = false;
                while (qh < qt && qt < 20000) {
                    int j = queue[qh++];
                    int jx = j % w, jy = j / w;
                    if (jx <= il || jx >= ir - 1 || jy <= it || jy >= ib - 1) touchesEdge = true;
                    mnx = Math.min(mnx, jx);
                    mxx = Math.max(mxx, jx);
                    mny = Math.min(mny, jy);
                    mxy = Math.max(mxy, jy);
                    int[] nb = {j - 1, j + 1, j - w, j + w};
                    for (int q = 0; q < 4; q++) {
                        int k = nb[q];
                        if (q == 0 && jx == 0) continue;
                        if (q == 1 && jx == w - 1) continue;
                        if (k < 0 || k >= n) continue;
                        if (!felt[k] && !seen[k]) {
                            seen[k] = true;
                            queue[qt++] = k;
                        }
                    }
                }
                if (touchesEdge) continue;
                int bw = mxx - mnx + 1, bh = mxy - mny + 1;
                if (bw < 4 || bh < 4) continue;
                float asp = (float) bw / bh;
                float fill = (float) qt / (bw * bh);
                if (asp < 0.75f || asp > 1.33f || fill < 0.6f || fill > 0.93f) continue;
                radii.add((float) Math.sqrt(qt / Math.PI));
            }
        }
        float ballR;
        if (!radii.isEmpty()) {
            Collections.sort(radii);
            ballR = radii.get(radii.size() / 2);
        } else {
            ballR = (R - L) / 56f;
        }
        if (ballR < (R - L) / 120f || ballR > (R - L) / 22f) ballR = (R - L) / 56f;

        Table t = new Table();
        t.L = L;
        t.T = T;
        t.R = R;
        t.B = B;
        t.r = ballR;
        t.feltCb = fCb;
        t.feltCr = fCr;
        t.feltY = fY;
        t.ballsMeasured = radii.size();
        return t;
    }

    /**
     * Ищем в полосе шириной band от края edge (шаг dir внутрь) самый сильный перепад средней яркости сукна.
     * horizontal=true — край верх/низ (усредняем по x в [a,b], исключая середину около mid±midHalf).
     */
    private static float refine(int[] Y, boolean[] comp, int w, int edge, int dir, int band, int a, int b,
                                int mid, int midHalf, boolean horizontal, int fY, float def) {
        // Средняя яркость каждой строки/столбца (все пиксели) от чуть снаружи края до band внутрь.
        int out = Math.max(2, band / 2);
        int len = band + out + 3;
        float[] m = new float[len];
        int h = Y.length / w;
        for (int k = 0; k < len; k++) {
            int pos = edge + dir * (k - out);
            long s = 0;
            int c = 0;
            for (int t = a; t <= b; t++) {
                if (mid >= 0 && Math.abs(t - mid) < midHalf) continue;
                int xx = horizontal ? t : pos, yy = horizontal ? pos : t;
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                s += Y[yy * w + xx];
                c++;
            }
            m[k] = c > 0 ? (float) s / c : -1;
        }
        // 1) Тонкая тёмная линия (тень под носом борта): темнее соседей с обеих сторон.
        int dk = -1;
        float best = Float.MAX_VALUE;
        for (int k = 3; k + 3 < len; k++) {
            if (m[k] < 0 || m[k - 3] < 0 || m[k + 3] < 0) continue;
            float side = Math.min(Math.max(m[k - 3], m[k - 2]), Math.max(m[k + 3], m[k + 2]));
            if (m[k] < 0.6f * side && m[k] < best) {
                best = m[k];
                dk = k;
            }
        }
        if (dk >= 0) {
            // Край сукна — сразу за тёмной линией (со стороны стола).
            int k2 = dk;
            while (k2 + 1 < len && m[k2 + 1] >= 0 && m[k2 + 1] < 0.6f * Math.max(m[Math.min(len - 1, dk + 3)], 1)) k2++;
            return edge + dir * (k2 - out + 1);
        }
        // 2) Иначе — самый резкий перепад яркости.
        float bestD = 0.04f * fY;
        int bestK = -1;
        for (int k = out + 1; k + 1 < len; k++) {
            if (m[k - 1] < 0 || m[k + 1] < 0) continue;
            float d = Math.abs(m[k + 1] - m[k - 1]);
            if (d > bestD) {
                bestD = d;
                bestK = k;
            }
        }
        if (bestK < 0) return def;
        return edge + dir * (bestK - out);
    }

    static boolean feltLike(int y, int cb, int cr, double fHue, float fRatio, int fY) {
        if (y < 15 || y > fY * 1.8f) return false;
        float ch = (float) Math.hypot(cr - 128, cb - 128);
        if (ch < 5) return false;
        double dh = Math.abs(Math.atan2(cr - 128, cb - 128) - fHue);
        if (dh > Math.PI) dh = 2 * Math.PI - dh;
        if (dh > Math.toRadians(15)) return false;
        float ratio = ch / Math.max(10, y);
        return ratio > 0.5f * fRatio && ratio < 2f * fRatio;
    }

    /** Доля пикселей цвета сукна в сетке — виден ли стол сейчас (по оттенку, как при поиске стола). */
    static float feltFraction(int[] rgb, int n, int fCb, int fCr, int fY) {
        double fHue = Math.atan2(fCr - 128, fCb - 128);
        float fRatio = (float) Math.hypot(fCr - 128, fCb - 128) / Math.max(10, fY);
        int c = 0, tot = 0, step = Math.max(1, n / 4000);
        for (int i = 0; i < n; i += step) {
            int v = rgb[i];
            int r = (v >> 16) & 255, g = (v >> 8) & 255, b = v & 255;
            int y = (299 * r + 587 * g + 114 * b) / 1000;
            int cb = clamp8((-169 * r - 331 * g + 500 * b) / 1000 + 128);
            int cr = clamp8((500 * r - 419 * g - 81 * b) / 1000 + 128);
            if (feltLike(y, cb, cr, fHue, fRatio, fY)) c++;
            tot++;
        }
        return tot > 0 ? (float) c / tot : 0f;
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
