package kz.poolaim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Чистая геометрия и «физика» бильярда (без Android API).
 * Стол задаётся внутренними краями бортов L,T,R,B, r — радиус шара.
 * Центр шара может двигаться только внутри прямоугольника, уменьшенного на r.
 *
 * Модель силы: шар катится с постоянным торможением, поэтому пройденный путь ∝ скорость².
 * При силе p (0..1) биток без помех прокатился бы D = dmax·p². При ударе энергия делится:
 * прицельному шару достаётся cos²(резки), битку — sin²(резки). Удар о борт оставляет CUSH пути.
 */
final class Geo {

    static final float COS_MAX_CUT = 0.0872f; // cos(85°)
    static final float CUSH = 0.7f;           // доля оставшегося пути после отскока от борта

    static final int CUE = 0, SOLID = 1, STRIPE = 2, EIGHT = 3;
    static final int FILTER_ALL = 0, FILTER_SOLIDS = 1, FILTER_STRIPES = 2;

    static final class Ball {
        float x, y;
        int type;

        Ball(float x, float y, int type) {
            this.x = x;
            this.y = y;
            this.type = type;
        }
    }

    static final class Shot {
        int obj = -1;    // индекс прицельного шара в списке (-1 — ручной режим)
        int pocket;      // 0 ЛВ, 1 СВ, 2 ПВ, 3 ЛН, 4 СН, 5 ПН
        int cushion;     // -1 прямой, 0 левый, 1 верхний, 2 правый, 3 нижний борт
        float cx, cy;    // биток
        float ox, oy;    // прицельный шар
        float px, py;    // луза
        float kx, ky;    // точка на борте (для удара через борт)
        float gx, gy;    // «призрачный шар»
        float cut;       // угол резки, градусы
        float cos2;      // cos² резки — доля энергии прицельному шару
        float tx, ty;    // направление битка после удара (0,0 — останется на месте)
        float d1;        // путь битка до удара
        float lob;       // путь прицельного шара до лузы (с учётом потерь на борте)
        float pmin;      // минимальная сила, чтобы шар дошёл до лузы (0..1+)
        float score;     // сложность (меньше — проще)

        String key() {
            return pocket + ":" + cushion;
        }

        /** Рекомендуемая сила: с запасом, чтобы шар уверенно упал. */
        float recPower() {
            return Math.min(1f, Math.max(0.08f, pmin * 1.15f + 0.04f));
        }
    }

    /** Результат прокатки шара. */
    static final class Run {
        float[] pts;          // ломаная пути
        boolean full;         // дошёл до цели (лузы)
        boolean scratch;      // упал в лузу (для битка — плохо)
        int scratchPocket = -1;
        boolean hit;          // врезался в другой шар
        float hitX, hitY;     // центр шара, в который врезался
        boolean shortOfObject; // биток даже не докатился до прицельного шара
    }

    private static final class Ctx {
        float L, T, R, B, r, cx, cy, ox, oy, xl, xr, yt, yb, diag, dmax;
        float[] obst; // другие шары: x0,y0,x1,y1,...
        int obj;
    }

    static float[] pockets(float L, float T, float R, float B) {
        float mx = (L + R) / 2f;
        return new float[]{L, T, mx, T, R, T, L, B, mx, B, R, B};
    }

    static boolean typeMatches(int type, int filter) {
        if (type == CUE) return false;
        if (filter == FILTER_SOLIDS) return type == SOLID;
        if (filter == FILTER_STRIPES) return type == STRIPE;
        return true;
    }

    /** Удары по всем найденным шарам (кроме битка), с учётом мешающих шаров. */
    static List<Shot> computeAll(float L, float T, float R, float B, float r, List<Ball> balls, int cueIdx,
                                 int filter, boolean banks, float dmax) {
        List<Shot> out = new ArrayList<>();
        if (cueIdx < 0 || cueIdx >= balls.size()) return out;
        Ball c = balls.get(cueIdx);
        for (int i = 0; i < balls.size(); i++) {
            if (i == cueIdx || !typeMatches(balls.get(i).type, filter)) continue;
            float[] obst = new float[2 * (balls.size() - 2)];
            int n = 0;
            for (int j = 0; j < balls.size(); j++) {
                if (j == i || j == cueIdx) continue;
                obst[n++] = balls.get(j).x;
                obst[n++] = balls.get(j).y;
            }
            Ball o = balls.get(i);
            out.addAll(compute(L, T, R, B, r, c.x, c.y, o.x, o.y, banks, dmax, obst, i));
        }
        Collections.sort(out, (a, b) -> Float.compare(a.score, b.score));
        return out;
    }

    static List<Shot> compute(float L, float T, float R, float B, float r,
                              float cx, float cy, float ox, float oy, boolean banks) {
        return compute(L, T, R, B, r, cx, cy, ox, oy, banks, 6f * (R - L), null, -1);
    }

    /**
     * Все возможные удары прицельным шаром (ox,oy) битком (cx,cy), отсортированные от простого к сложному.
     */
    static List<Shot> compute(float L, float T, float R, float B, float r,
                              float cx, float cy, float ox, float oy, boolean banks,
                              float dmax, float[] obstacles, int objIdx) {
        List<Shot> out = new ArrayList<>();
        if (r <= 0 || R - L < 6 * r || B - T < 6 * r) return out;

        Ctx k = new Ctx();
        k.L = L; k.T = T; k.R = R; k.B = B; k.r = r;
        k.cx = cx; k.cy = cy; k.ox = ox; k.oy = oy;
        k.xl = L + r; k.xr = R - r; k.yt = T + r; k.yb = B - r;
        k.diag = (float) Math.hypot(R - L, B - T);
        k.dmax = Math.max(dmax, R - L);
        k.obst = obstacles;
        k.obj = objIdx;
        float mx = (L + R) / 2f;

        float[] pk = pockets(L, T, R, B);
        for (int i = 0; i < 6; i++) {
            float px = pk[2 * i], py = pk[2 * i + 1];

            Shot s = make(k, i, -1, px, py, px, py);
            if (s != null) out.add(s);

            if (!banks) continue;
            for (int c = 0; c < 4; c++) {
                // Зеркалим лузу относительно линии, по которой катится центр шара у борта.
                float qx = px, qy = py;
                switch (c) {
                    case 0:
                        if (px <= k.xl + r) continue;
                        qx = 2 * k.xl - px;
                        break;
                    case 1:
                        if (py <= k.yt + r) continue;
                        qy = 2 * k.yt - py;
                        break;
                    case 2:
                        if (px >= k.xr - r) continue;
                        qx = 2 * k.xr - px;
                        break;
                    default:
                        if (py >= k.yb - r) continue;
                        qy = 2 * k.yb - py;
                        break;
                }
                float t;
                if (c == 0 || c == 2) {
                    float lx = (c == 0) ? k.xl : k.xr;
                    if (Math.abs(qx - ox) < 1e-3f) continue;
                    t = (lx - ox) / (qx - ox);
                } else {
                    float ly = (c == 1) ? k.yt : k.yb;
                    if (Math.abs(qy - oy) < 1e-3f) continue;
                    t = (ly - oy) / (qy - oy);
                }
                if (t <= 0.02f || t >= 0.98f) continue;
                float kx = ox + t * (qx - ox), ky = oy + t * (qy - oy);
                if (c == 0 || c == 2) {
                    if (ky < T + 2.2f * r || ky > B - 2.2f * r) continue;
                } else {
                    if (kx < L + 2.2f * r || kx > R - 2.2f * r || Math.abs(kx - mx) < 2.2f * r) continue;
                }
                Shot b = make(k, i, c, px, py, kx, ky);
                if (b != null) out.add(b);
            }
        }
        Collections.sort(out, (a, b) -> Float.compare(a.score, b.score));
        return out;
    }

    static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay;
        float l2 = vx * vx + vy * vy;
        float t = l2 < 1e-6f ? 0f : ((px - ax) * vx + (py - ay) * vy) / l2;
        t = Math.max(0f, Math.min(1f, t));
        return (float) Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }

    /** (ax,ay) — куда сначала покатится прицельный шар: луза или точка на борте. */
    private static Shot make(Ctx k, int pocket, int cushion, float px, float py, float ax, float ay) {
        float r = k.r;
        float ux = ax - k.ox, uy = ay - k.oy;
        float len1 = (float) Math.hypot(ux, uy);
        if (len1 < 0.5f * r) return null;
        ux /= len1;
        uy /= len1;

        float fsx = cushion >= 0 ? ax : k.ox, fsy = cushion >= 0 ? ay : k.oy;
        float fx = px - fsx, fy = py - fsy;
        float len2 = cushion >= 0 ? (float) Math.hypot(fx, fy) : 0f;
        float sidePenalty = 0f;
        if (pocket == 1 || pocket == 4) {
            double ang = Math.toDegrees(Math.atan2(Math.abs(fx), Math.abs(fy)));
            if (ang > 60) return null;
            if (pocket == 1 && fy >= 0) return null;
            if (pocket == 4 && fy <= 0) return null;
            sidePenalty = (float) ang * 0.3f;
        }

        float gx = k.ox - 2 * r * ux, gy = k.oy - 2 * r * uy;
        float tol = 0.5f * r;
        if (gx < k.xl - tol || gx > k.xr + tol || gy < k.yt - tol || gy > k.yb + tol) return null;

        float dx = gx - k.cx, dy = gy - k.cy;
        float dl = (float) Math.hypot(dx, dy);
        if (dl < 0.3f * r) return null;
        dx /= dl;
        dy /= dl;
        float cos = dx * ux + dy * uy;
        if (cos < COS_MAX_CUT) return null;
        float cut = (float) Math.toDegrees(Math.acos(Math.min(1f, cos)));

        // Мешают ли другие шары: путь битка, путь прицельного шара.
        if (k.obst != null) {
            float lim = 1.9f * r;
            for (int i = 0; i + 1 < k.obst.length; i += 2) {
                float bx = k.obst[i], by = k.obst[i + 1];
                if (segDist(bx, by, k.cx, k.cy, gx, gy) < lim) return null;
                if (segDist(bx, by, k.ox, k.oy, ax, ay) < lim) return null;
                if (cushion >= 0 && segDist(bx, by, ax, ay, px, py) < lim) return null;
            }
        }

        Shot s = new Shot();
        s.obj = k.obj;
        s.pocket = pocket;
        s.cushion = cushion;
        s.cx = k.cx;
        s.cy = k.cy;
        s.ox = k.ox;
        s.oy = k.oy;
        s.px = px;
        s.py = py;
        s.kx = ax;
        s.ky = ay;
        s.gx = gx;
        s.gy = gy;
        s.cut = cut;
        s.cos2 = cos * cos;
        s.d1 = dl;
        s.lob = len1 + (cushion >= 0 ? len2 / CUSH : 0f) + 0.3f * r;
        float need = s.d1 + s.lob / Math.max(s.cos2, 0.01f);
        s.pmin = (float) Math.sqrt(need / k.dmax);
        if (s.pmin > 1.05f) return null; // не добить даже на полной силе

        s.score = cut + 20f * (len1 + len2) / k.diag + 12f * dl / k.diag
                + (cushion >= 0 ? 25f : 0f) + sidePenalty + 12f * s.pmin;

        // После удара без вращения биток уходит по касательной (правило 90°).
        float tx = dx - cos * ux, ty = dy - cos * uy;
        float tl = (float) Math.hypot(tx, ty);
        if (cut >= 2f && tl > 1e-4f) {
            s.tx = tx / tl;
            s.ty = ty / tl;
        }
        return s;
    }

    // ------------------------------------------------------------------ прокатка с учётом силы

    static float budget(float power, float dmax) {
        return dmax * power * power;
    }

    /** Путь прицельного шара при силе power. */
    static Run objectRun(Shot s, float power, float dmax) {
        float e1 = budget(power, dmax) - s.d1;
        Run run = new Run();
        if (e1 <= 0) {
            run.pts = new float[]{s.ox, s.oy};
            return run;
        }
        float[] path = s.cushion >= 0
                ? new float[]{s.ox, s.oy, s.kx, s.ky, s.px, s.py}
                : new float[]{s.ox, s.oy, s.px, s.py};
        float rem = e1 * s.cos2;
        float[] out = new float[path.length];
        out[0] = path[0];
        out[1] = path[1];
        int n = 2;
        run.full = true;
        for (int i = 0; i + 3 < path.length; i += 2) {
            float x0 = path[i], y0 = path[i + 1], x1 = path[i + 2], y1 = path[i + 3];
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            if (rem >= len) {
                out[n++] = x1;
                out[n++] = y1;
                rem -= len;
                if (i + 4 < path.length) rem *= CUSH; // отскок от борта
            } else {
                float t = len < 1e-6f ? 0f : rem / len;
                out[n++] = x0 + (x1 - x0) * t;
                out[n++] = y0 + (y1 - y0) * t;
                run.full = false;
                break;
            }
        }
        float[] res = new float[n];
        System.arraycopy(out, 0, res, 0, n);
        run.pts = res;
        return run;
    }

    /** Путь битка при силе power: до удара, затем по касательной с отскоками; ловим падение в лузу и столкновения. */
    static Run cueRun(Shot s, float power, float dmax, float L, float T, float R, float B, float r, float[] obstacles) {
        Run run = new Run();
        float d = budget(power, dmax);
        if (d < s.d1) {
            float t = s.d1 < 1e-6f ? 0f : d / s.d1;
            run.pts = new float[]{s.cx, s.cy, s.cx + (s.gx - s.cx) * t, s.cy + (s.gy - s.cy) * t};
            run.shortOfObject = true;
            return run;
        }
        float ec = (d - s.d1) * (1f - s.cos2);
        if ((s.tx == 0 && s.ty == 0) || ec < 0.3f * r) {
            run.pts = new float[]{s.gx, s.gy};
            run.scratch = pocketAt(s.gx, s.gy, L, T, R, B, r) >= 0;
            return run;
        }
        float[] p = reflect(s.gx, s.gy, s.tx, s.ty, ec, L + r, R - r, T + r, B - r, 8, CUSH);
        float[] pk = pockets(L, T, R, B);
        // Ищем первое событие вдоль пути: луза или шар.
        float[] out = new float[p.length];
        out[0] = p[0];
        out[1] = p[1];
        int n = 2;
        for (int i = 0; i + 3 < p.length; i += 2) {
            float x0 = p[i], y0 = p[i + 1], x1 = p[i + 2], y1 = p[i + 3];
            float best = 2f;
            int bestPocket = -1;
            float hitX = 0, hitY = 0;
            boolean isHit = false;
            for (int q = 0; q < 6; q++) {
                float cap = (q == 1 || q == 4) ? 1.4f * r : 1.6f * r;
                float t = firstHit(x0, y0, x1, y1, pk[2 * q], pk[2 * q + 1], cap);
                if (t >= 0 && t < best) {
                    best = t;
                    bestPocket = q;
                    isHit = false;
                }
            }
            if (obstacles != null) {
                for (int j = 0; j + 1 < obstacles.length; j += 2) {
                    float t = firstHit(x0, y0, x1, y1, obstacles[j], obstacles[j + 1], 2f * r);
                    if (t > 1e-3f && t < best) {
                        best = t;
                        bestPocket = -1;
                        isHit = true;
                        hitX = obstacles[j];
                        hitY = obstacles[j + 1];
                    }
                }
            }
            if (best <= 1f) {
                out[n++] = x0 + (x1 - x0) * best;
                out[n++] = y0 + (y1 - y0) * best;
                if (isHit) {
                    run.hit = true;
                    run.hitX = hitX;
                    run.hitY = hitY;
                } else {
                    run.scratch = true;
                    run.scratchPocket = bestPocket;
                }
                break;
            }
            out[n++] = x1;
            out[n++] = y1;
        }
        float[] res = new float[n];
        System.arraycopy(out, 0, res, 0, n);
        run.pts = res;
        return run;
    }

    static int pocketAt(float x, float y, float L, float T, float R, float B, float r) {
        float[] pk = pockets(L, T, R, B);
        for (int q = 0; q < 6; q++) {
            float cap = (q == 1 || q == 4) ? 1.4f * r : 1.6f * r;
            if (Math.hypot(x - pk[2 * q], y - pk[2 * q + 1]) < cap) return q;
        }
        return -1;
    }

    /** Параметр t∈[0,1] первого входа отрезка в круг (cx,cy,rad); 0 если начинается внутри; -1 если не входит. */
    static float firstHit(float x0, float y0, float x1, float y1, float cx, float cy, float rad) {
        float dx = x1 - x0, dy = y1 - y0;
        float fx = x0 - cx, fy = y0 - cy;
        float c = fx * fx + fy * fy - rad * rad;
        if (c <= 0) return 0f;
        float a = dx * dx + dy * dy;
        if (a < 1e-9f) return -1f;
        float b = 2 * (fx * dx + fy * dy);
        float disc = b * b - 4 * a * c;
        if (disc < 0) return -1f;
        float t = (float) ((-b - Math.sqrt(disc)) / (2 * a));
        return (t >= 0 && t <= 1) ? t : -1f;
    }

    static float[] reflect(float x, float y, float dx, float dy, float len,
                           float xl, float xr, float yt, float yb, int maxSeg) {
        return reflect(x, y, dx, dy, len, xl, xr, yt, yb, maxSeg, 1f);
    }

    /** Луч с отражениями от бортов (угол падения = углу отражения), после каждого отскока остаётся loss пути. */
    static float[] reflect(float x, float y, float dx, float dy, float len,
                           float xl, float xr, float yt, float yb, int maxSeg, float loss) {
        x = Math.max(xl, Math.min(xr, x));
        y = Math.max(yt, Math.min(yb, y));
        float[] pts = new float[2 * (maxSeg + 1)];
        int n = 0;
        pts[n++] = x;
        pts[n++] = y;
        float rem = len;
        for (int seg = 0; seg < maxSeg && rem > 0; seg++) {
            float tx = dx > 1e-6f ? (xr - x) / dx : dx < -1e-6f ? (xl - x) / dx : Float.MAX_VALUE;
            float ty = dy > 1e-6f ? (yb - y) / dy : dy < -1e-6f ? (yt - y) / dy : Float.MAX_VALUE;
            float t = Math.max(0f, Math.min(tx, ty));
            if (t >= rem) {
                x += dx * rem;
                y += dy * rem;
                pts[n++] = x;
                pts[n++] = y;
                break;
            }
            x += dx * t;
            y += dy * t;
            pts[n++] = x;
            pts[n++] = y;
            rem = (rem - t) * loss;
            if (tx <= ty + 1e-4f) dx = -dx;
            if (ty <= tx + 1e-4f) dy = -dy;
        }
        float[] res = new float[n];
        System.arraycopy(pts, 0, res, 0, n);
        return res;
    }
}
