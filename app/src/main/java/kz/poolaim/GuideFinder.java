package kz.poolaim;

/**
 * Находит направление белой линии прицела игры, выходящей из битка (без Android API).
 * Для каждого из 720 направлений идём лучом от края битка наружу и считаем, какая доля точек — «белая линия».
 * Линия прицела даёт почти 100% на своём направлении; кольца, текст, кий (коричневый) — мало.
 * Наши собственные линии рисуются цветными (не белыми), поэтому не мешают.
 * Координаты: центр пикселя (i,j) — это точка (i,j).
 */
final class GuideFinder {

    static final int BINS = 720;

    /**
     * @param rgb   картинка w×h (0xRRGGBB)
     * @param cx    центр битка (в пикселях картинки)
     * @param r     радиус шара (в пикселях картинки)
     * @param r0    откуда начинать луч (чуть дальше нашего кольца вокруг битка)
     * @param balls остальные шары x0,y0,x1,y1,... (луч обрывается у шара — там кончается линия игры)
     * @param feltY яркость сукна (0..255) или -1
     * @return угол в радианах (0 — вправо, по часовой — вниз) или NaN
     */
    static float find(int[] rgb, int w, int h, float cx, float cy, float r, float r0, float[] balls, int feltY) {
        // Цвет сукна — медиана по картинке.
        int[] hy = new int[256], hs = new int[256];
        int cnt0 = 0;
        for (int i = 0; i < rgb.length; i += 5) {
            int v = rgb[i];
            int rr = (v >> 16) & 255, gg = (v >> 8) & 255, bb = v & 255;
            hy[(299 * rr + 587 * gg + 114 * bb) / 1000]++;
            hs[Math.max(rr, Math.max(gg, bb)) - Math.min(rr, Math.min(gg, bb))]++;
            cnt0++;
        }
        int fy = feltY >= 0 ? feltY : median(hy, cnt0);
        int fs = median(hs, cnt0);
        // «Белая линия»: заметно ярче сукна и менее насыщенная (белое, наложенное на сукно).
        final int yMin = fy + Math.max(25, (int) (0.3f * (255 - fy)));
        final int sMax = Math.max(40, (int) (fs * 0.75f));

        float rMax = 6f * r;
        float[] run = new float[BINS];
        float[] lims = new float[BINS];
        for (int b = 0; b < BINS; b++) {
            double a = 2 * Math.PI * b / BINS;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            float lim = rMax;
            if (balls != null) {
                for (int j = 0; j + 1 < balls.length; j += 2) {
                    float t = rayCircle(cx, cy, dx, dy, balls[j], balls[j + 1], 2f * r);
                    if (t > 0 && t < lim) lim = t;
                }
            }
            lims[b] = lim;
            // Непрерывная белая полоса от края битка (допускаем разрывы до 2 px).
            float last = -1;
            int gap = 0;
            for (float rho = r0; rho <= lim; rho += 1f) {
                float x = cx + dx * rho, y = cy + dy * rho;
                if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) break;
                boolean wht = false;
                for (int o = -1; o <= 1 && !wht; o++) {
                    int xi = Math.round(x - dy * o), yi = Math.round(y + dx * o);
                    wht = white(rgb[yi * w + xi], yMin, sMax);
                }
                if (wht) {
                    last = rho;
                    gap = 0;
                } else if (last < 0 && rho - r0 > 3) {
                    break; // у самого битка линии нет
                } else if (++gap > 2) {
                    break;
                }
            }
            run[b] = last < 0 ? 0 : last - r0;
        }
        int best = 0;
        for (int b = 1; b < BINS; b++) if (run[b] > run[best]) best = b;
        if (run[best] < 1.2f * r) return Float.NaN;
        for (int b = 0; b < BINS; b++) {
            int d = Math.abs(b - best);
            d = Math.min(d, BINS - d);
            if (d > 12 && run[b] > 0.85f * run[best]) return Float.NaN;
        }
        // Центр пика: середина соседних направлений с почти такой же длиной полосы.
        int lo = best, hi = best;
        while (hi - best < 6 && run[(hi + 1) % BINS] >= 0.9f * run[best]) hi++;
        while (best - lo < 6 && run[(lo - 1 + BINS) % BINS] >= 0.9f * run[best]) lo--;
        best = ((lo + hi) / 2 + BINS) % BINS;
        // Уточнение: ±1.5° шагом 0.05°, максимум суммарной «белизны» вдоль луча (билинейно).
        double a0 = 2 * Math.PI * best / BINS;
        int steps = 61;
        double[] sc = new double[steps];
        // Конец линии не берём: там кольцо-прицел игры.
        float lim = Math.min(lims[best], r0 + Math.max(0.7f * run[best], run[best] - 0.6f * r));
        for (int k = 0; k < steps; k++) {
            double a = a0 + Math.toRadians((k - 30) * 0.05);
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            double sum = 0;
            for (float rho = r0; rho <= lim; rho += 0.5f) {
                float x = cx + dx * rho, y = cy + dy * rho;
                if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) break;
                sum += bright(rgb, w, x, y, fy, sMax);
            }
            sc[k] = sum;
        }
        int bk = 0;
        for (int k = 1; k < steps; k++) if (sc[k] > sc[bk]) bk = k;
        double off = 0;
        if (bk > 0 && bk < steps - 1) {
            double den = sc[bk - 1] - 2 * sc[bk] + sc[bk + 1];
            if (Math.abs(den) > 1e-9) off = 0.5 * (sc[bk - 1] - sc[bk + 1]) / den;
        }
        return (float) (a0 + Math.toRadians((bk - 30 + off) * 0.05));
    }

    /** Насколько пиксель ярче сукна (билинейно), только «бело-серые» оттенки. */
    private static float bright(int[] rgb, int w, float x, float y, int fy, int sMax) {
        int x0 = (int) x, y0 = (int) y;
        float fx = x - x0, fyy = y - y0;
        float v = 0;
        for (int k = 0; k < 4; k++) {
            int xi = x0 + (k & 1), yi = y0 + (k >> 1);
            float wgt = ((k & 1) == 1 ? fx : 1 - fx) * ((k >> 1) == 1 ? fyy : 1 - fyy);
            int c = rgb[yi * w + xi];
            int rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
            int yy = (299 * rr + 587 * gg + 114 * bb) / 1000;
            int sat = Math.max(rr, Math.max(gg, bb)) - Math.min(rr, Math.min(gg, bb));
            float e = sat > sMax * 1.2f ? 0f : Math.max(0, yy - fy);
            v += wgt * Math.max(0, e);
        }
        return v;
    }

    private static boolean white(int c, int yMin, int sMax) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int y = (299 * r + 587 * g + 114 * b) / 1000;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return y >= yMin && mx - mn <= sMax;
    }

    private static int median(int[] hist, int total) {
        int half = total / 2, acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc > half) return i;
        }
        return 128;
    }

    /** Расстояние вдоль луча до входа в круг или -1. */
    static float rayCircle(float x, float y, float dx, float dy, float cx, float cy, float rad) {
        float fx = x - cx, fy = y - cy;
        float b = fx * dx + fy * dy;
        float c = fx * fx + fy * fy - rad * rad;
        float disc = b * b - c;
        if (disc < 0) return -1f;
        float t = (float) (-b - Math.sqrt(disc));
        return t;
    }
}
