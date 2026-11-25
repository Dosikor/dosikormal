package kz.poolaim;

/**
 * Симуляция удара со всеми шарами (без Android API): трение качения, упругие столкновения шаров
 * (одинаковая масса), отскоки от бортов с потерей скорости, падение в лузы.
 * События (касание шаров, борт) считаются точно по времени, поэтому углы разлёта верные.
 *
 * Единицы: пиксели. Торможение a = 1, скорость битка v0 = sqrt(2·dmax)·сила —
 * так при силе p биток без помех прокатится dmax·p² (та же модель силы, что в Geo).
 */
final class Sim {

    static final float BALL_E = 0.94f;    // упругость шар-шар
    static final float CUSH_E = 0.837f;   // скорость после борта (≈ sqrt(Geo.CUSH))

    static final class Result {
        float[][] paths;      // путь каждого шара (x0,y0,x1,y1,...) — только сдвинувшихся, иначе null
        boolean[] potted;
        int[] pocketOf;       // в какую лузу (0..5) или -1
        float[] fx, fy;       // где остановится
        int firstHit = -1;    // какой шар биток заденет первым
        boolean cuePotted;
    }

    private static final class Path {
        float[] p = new float[64];
        int n;

        void add(float x, float y) {
            if (n >= 2 && Math.abs(p[n - 2] - x) < 0.01f && Math.abs(p[n - 1] - y) < 0.01f) return;
            if (n + 2 > p.length) p = java.util.Arrays.copyOf(p, p.length * 2);
            p[n++] = x;
            p[n++] = y;
        }

        float[] get() {
            return java.util.Arrays.copyOf(p, n);
        }
    }

    /**
     * @param xs,ys положения всех шаров; cue — индекс битка
     * @param dirX,dirY направление удара; power 0..1
     */
    static Result run(float[] xs, float[] ys, int cue, float dirX, float dirY, float power,
                      float L, float T, float R, float B, float r, float dmax) {
        int n = xs.length;
        float[] x = xs.clone(), y = ys.clone(), vx = new float[n], vy = new float[n];
        boolean[] alive = new boolean[n];
        Result res = new Result();
        res.potted = new boolean[n];
        res.pocketOf = new int[n];
        java.util.Arrays.fill(res.pocketOf, -1);
        java.util.Arrays.fill(alive, true);
        float xl = L + r, xr = R - r, yt = T + r, yb = B - r, mx = (L + R) / 2f, mouth = 2.2f * r;

        // Шары не должны перекрываться (распознавание неточное) — раздвигаем.
        for (int it = 0; it < 4; it++) {
            for (int i = 0; i < n; i++) {
                x[i] = Math.max(xl, Math.min(xr, x[i]));
                y[i] = Math.max(yt, Math.min(yb, y[i]));
                for (int j = i + 1; j < n; j++) {
                    float dx = x[j] - x[i], dy = y[j] - y[i];
                    float d = (float) Math.hypot(dx, dy);
                    float need = 2 * r + 0.01f;
                    if (d < need) {
                        if (d < 1e-3f) {
                            dx = 1;
                            dy = 0;
                            d = 1;
                        }
                        float push = (need - d) / 2;
                        x[i] -= dx / d * push;
                        y[i] -= dy / d * push;
                        x[j] += dx / d * push;
                        y[j] += dy / d * push;
                    }
                }
            }
        }

        Path[] paths = new Path[n];
        for (int i = 0; i < n; i++) {
            paths[i] = new Path();
            paths[i].add(x[i], y[i]);
        }
        float dl = (float) Math.hypot(dirX, dirY);
        if (dl < 1e-6f || cue < 0 || cue >= n) return finish(res, paths, x, y, n);
        float v0 = (float) Math.sqrt(2 * dmax) * Math.max(0f, Math.min(1f, power));
        vx[cue] = dirX / dl * v0;
        vy[cue] = dirY / dl * v0;
        boolean[] moved = new boolean[n];
        moved[cue] = true;

        for (int step = 0; step < 6000; step++) {
            float vmax = 0;
            for (int i = 0; i < n; i++) if (alive[i]) vmax = Math.max(vmax, (float) Math.hypot(vx[i], vy[i]));
            if (vmax < 0.05f) break;
            float dt = 0.4f * r / vmax;
            float rem = dt;
            for (int ev = 0; ev < 40 && rem > 1e-7f; ev++) {
                float te = rem;
                int kind = 0, ei = -1, ej = -1; // 1 — шар-шар, 2 — борт по x, 3 — борт по y
                for (int i = 0; i < n; i++) {
                    if (!alive[i]) continue;
                    boolean mi = vx[i] != 0 || vy[i] != 0;
                    // Борта.
                    if (vx[i] > 0) { float t = (xr - x[i]) / vx[i]; if (t < te) { te = Math.max(0, t); kind = 2; ei = i; } }
                    if (vx[i] < 0) { float t = (xl - x[i]) / vx[i]; if (t < te) { te = Math.max(0, t); kind = 2; ei = i; } }
                    if (vy[i] > 0) { float t = (yb - y[i]) / vy[i]; if (t < te) { te = Math.max(0, t); kind = 3; ei = i; } }
                    if (vy[i] < 0) { float t = (yt - y[i]) / vy[i]; if (t < te) { te = Math.max(0, t); kind = 3; ei = i; } }
                    // Шары.
                    for (int j = i + 1; j < n; j++) {
                        if (!alive[j]) continue;
                        boolean mj = vx[j] != 0 || vy[j] != 0;
                        if (!mi && !mj) continue;
                        float dx = x[j] - x[i], dy = y[j] - y[i];
                        float wx = vx[j] - vx[i], wy = vy[j] - vy[i];
                        float b = dx * wx + dy * wy;
                        if (b >= 0) continue; // не сближаются
                        float a = wx * wx + wy * wy;
                        float c = dx * dx + dy * dy - 4 * r * r;
                        float t;
                        if (c <= 0) t = 0;
                        else {
                            float disc = b * b - a * c;
                            if (disc < 0) continue;
                            t = (float) ((-b - Math.sqrt(disc)) / a);
                        }
                        if (t < te) {
                            te = Math.max(0, t);
                            kind = 1;
                            ei = i;
                            ej = j;
                        }
                    }
                }
                // Двигаем всех до события.
                for (int i = 0; i < n; i++) {
                    if (!alive[i]) continue;
                    x[i] += vx[i] * te;
                    y[i] += vy[i] * te;
                }
                rem -= te;
                if (kind == 0) break;
                if (kind == 1) {
                    float dx = x[ej] - x[ei], dy = y[ej] - y[ei];
                    float d = (float) Math.hypot(dx, dy);
                    if (d < 1e-6f) continue;
                    float nx = dx / d, ny = dy / d;
                    float vin = vx[ei] * nx + vy[ei] * ny, vjn = vx[ej] * nx + vy[ej] * ny;
                    float jmp = (1 + BALL_E) / 2f * (vin - vjn);
                    if (jmp <= 0) continue;
                    vx[ei] -= jmp * nx;
                    vy[ei] -= jmp * ny;
                    vx[ej] += jmp * nx;
                    vy[ej] += jmp * ny;
                    paths[ei].add(x[ei], y[ei]);
                    paths[ej].add(x[ej], y[ej]);
                    moved[ei] = moved[ej] = true;
                    if (res.firstHit < 0 && (ei == cue || ej == cue)) res.firstHit = ei == cue ? ej : ei;
                } else {
                    int i = ei;
                    paths[i].add(x[i], y[i]);
                    // Зев лузы — шар падает.
                    int pocket = -1;
                    if (kind == 2) {
                        if (y[i] < T + mouth) pocket = x[i] < mx ? 0 : 2;
                        else if (y[i] > B - mouth) pocket = x[i] < mx ? 3 : 5;
                    } else {
                        boolean top = y[i] < (T + B) / 2f;
                        if (x[i] < L + mouth) pocket = top ? 0 : 3;
                        else if (x[i] > R - mouth) pocket = top ? 2 : 5;
                        else if (Math.abs(x[i] - mx) < mouth) pocket = top ? 1 : 4;
                    }
                    if (pocket >= 0) {
                        float[] pk = Geo.pockets(L, T, R, B);
                        paths[i].add(pk[2 * pocket], pk[2 * pocket + 1]);
                        alive[i] = false;
                        res.potted[i] = true;
                        res.pocketOf[i] = pocket;
                        vx[i] = vy[i] = 0;
                        x[i] = pk[2 * pocket];
                        y[i] = pk[2 * pocket + 1];
                        if (i == cue) res.cuePotted = true;
                    } else if (kind == 2) {
                        vx[i] = -vx[i] * CUSH_E;
                        x[i] = Math.max(xl, Math.min(xr, x[i]));
                    } else {
                        vy[i] = -vy[i] * CUSH_E;
                        y[i] = Math.max(yt, Math.min(yb, y[i]));
                    }
                }
            }
            // Трение: скорость падает равномерно, направление не меняется.
            for (int i = 0; i < n; i++) {
                if (!alive[i]) continue;
                float s = (float) Math.hypot(vx[i], vy[i]);
                if (s == 0) continue;
                float ns = s - dt;
                if (ns <= 0.05f) {
                    vx[i] = vy[i] = 0;
                    paths[i].add(x[i], y[i]);
                } else {
                    vx[i] *= ns / s;
                    vy[i] *= ns / s;
                }
            }
        }
        return finish(res, paths, x, y, n, moved);
    }

    private static Result finish(Result res, Path[] paths, float[] x, float[] y, int n) {
        return finish(res, paths, x, y, n, new boolean[n]);
    }

    private static Result finish(Result res, Path[] paths, float[] x, float[] y, int n, boolean[] moved) {
        res.paths = new float[n][];
        res.fx = new float[n];
        res.fy = new float[n];
        for (int i = 0; i < n; i++) {
            paths[i].add(x[i], y[i]);
            float[] p = paths[i].get();
            res.paths[i] = moved[i] && p.length >= 4 ? p : null;
            res.fx[i] = x[i];
            res.fy[i] = y[i];
        }
        return res;
    }
}
