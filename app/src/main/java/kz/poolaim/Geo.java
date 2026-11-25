package kz.poolaim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Чистая геометрия и «физика» бильярда (без Android API).
 * Стол задаётся внутренними краями бортов L,T,R,B, r — радиус шара.
 * Центр шара может двигаться только внутри прямоугольника, уменьшенного на r («линии бортов»).
 *
 * Отскок от борта — зеркальный (угол падения = углу отражения). Удары через борт ищутся
 * отражением лузы (или «призрачного шара») относительно линий бортов.
 *
 * Модель силы: путь ∝ скорость². При силе p (0..1) биток без помех прокатился бы D = dmax·p².
 * При ударе прицельному шару достаётся cos²(резки) оставшегося пути, битку — sin².
 * После отскока от борта остаётся CUSH пути.
 */
final class Geo {

    static final float COS_MAX_CUT = 0.0872f; // cos(85°)
    static final float CUSH = 0.7f;

    static final int CUE = 0, SOLID = 1, STRIPE = 2, EIGHT = 3;
    static final int FILTER_ALL = 0, FILTER_SOLIDS = 1, FILTER_STRIPES = 2;

    static final class Ball {
        float x, y;
        int type;
        int color; // цвет шара 0xAARRGGBB (0 — неизвестен)

        Ball(float x, float y, int type) {
            this.x = x;
            this.y = y;
            this.type = type;
        }

        Ball(float x, float y, int type, int color) {
            this(x, y, type);
            this.color = color;
        }
    }

    static final class Shot {
        int obj = -1;      // индекс прицельного шара (-1 — без списка)
        int pocket;        // 0 ЛВ, 1 СВ, 2 ПВ, 3 ЛН, 4 СН, 5 ПН
        int objCush;       // сколько бортов проходит прицельный шар (0..2)
        int cueCush;       // сколько бортов проходит биток до удара (0..1)
        float cx, cy;      // биток
        float ox, oy;      // прицельный шар
        float px, py;      // луза
        float gx, gy;      // «призрачный шар» — где биток в момент удара
        float[] objPath;   // O, точки бортов..., P
        float[] cuePath;   // C, точки бортов..., G
        float cut;         // угол резки, градусы
        float cos2;
        float tx, ty;      // направление битка после удара (0,0 — встанет)
        float pmin;        // минимальная сила, чтобы шар упал
        float score;

        String key() {
            return pocket + ":" + objCush + ":" + cueCush;
        }

        float recPower() {
            return Math.min(1f, Math.max(0.08f, pmin * 1.15f + 0.04f));
        }
    }

    /** Результат прокатки шара. */
    static final class Run {
        float[] pts;
        boolean full;           // дошёл до цели
        boolean scratch;        // упал в лузу
        int scratchPocket = -1;
        boolean hit;            // врезался в шар
        int hitIdx = -1;
        float hitX, hitY;
        boolean shortOfObject;  // биток не доехал до прицельного шара
    }

    /** Что будет, если бить в направлении (dx,dy) — продолжение линии прицела игры. */
    static final class Prediction {
        float[] cuePath;        // биток до удара (или до остановки)
        boolean hitBall;
        int hitIdx = -1;
        float gx, gy;           // где биток в момент удара
        float[] objPath;        // путь задетого шара
        boolean potted;         // задетый шар упадёт в лузу
        int pocket = -1;
        boolean objHitsBall;
        float[] cueAfter;       // путь битка после удара
        boolean scratch;        // биток упадёт в лузу
        float cut;
    }

    private static final class Ctx {
        float L, T, R, B, r, cx, cy, ox, oy, xl, xr, yt, yb, diag, dmax, mx;
        float[] obst;
        int obj;
        boolean banks;
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

    // ------------------------------------------------------------------ подбор ударов

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

    /** Все удары прицельным шаром (ox,oy) битком (cx,cy): прямые, через 1–2 борта, битком через борт. */
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
        k.mx = (L + R) / 2f;
        k.banks = banks;

        float[] pk = pockets(L, T, R, B);
        for (int i = 0; i < 6; i++) {
            float px = pk[2 * i], py = pk[2 * i + 1];
            List<float[]> paths = new ArrayList<>();
            paths.add(new float[]{ox, oy, px, py});
            if (banks) {
                for (int c = 0; c < 4; c++) {
                    float[] p1 = bank1(k, px, py, c);
                    if (p1 != null) paths.add(p1);
                    for (int c2 = 0; c2 < 4; c2++) {
                        if (c2 == c) continue;
                        float[] p2 = bank2(k, px, py, c, c2);
                        if (p2 != null) paths.add(p2);
                    }
                }
            }
            for (float[] op : paths) {
                Shot s = make(k, i, op, false);
                if (s != null) {
                    out.add(s);
                } else if (banks) {
                    // Прямой путь битка закрыт — пробуем битком через борт.
                    Shot kk = make(k, i, op, true);
                    if (kk != null) out.add(kk);
                }
            }
        }
        Collections.sort(out, (a, b) -> Float.compare(a.score, b.score));
        return out;
    }

    private static float lineOf(Ctx k, int c) {
        return c == 0 ? k.xl : c == 1 ? k.yt : c == 2 ? k.xr : k.yb;
    }

    private static boolean vertical(int c) {
        return c == 0 || c == 2;
    }

    /** Точка находится по «внутреннюю» сторону линии борта c. */
    private static boolean inside(Ctx k, int c, float x, float y, float margin) {
        switch (c) {
            case 0: return x > k.xl + margin;
            case 1: return y > k.yt + margin;
            case 2: return x < k.xr - margin;
            default: return y < k.yb - margin;
        }
    }

    private static float[] mirror(Ctx k, int c, float x, float y) {
        float v = lineOf(k, c);
        return vertical(c) ? new float[]{2 * v - x, y} : new float[]{x, 2 * v - y};
    }

    /** Пересечение отрезка A→B с линией борта c, параметр t в (0.02,0.98) и точка на настоящем борте. */
    private static float[] hitCushion(Ctx k, int c, float ax, float ay, float bx, float by) {
        float v = lineOf(k, c);
        float t;
        if (vertical(c)) {
            if (Math.abs(bx - ax) < 1e-3f) return null;
            t = (v - ax) / (bx - ax);
        } else {
            if (Math.abs(by - ay) < 1e-3f) return null;
            t = (v - ay) / (by - ay);
        }
        if (t <= 0.02f || t >= 0.98f) return null;
        float x = ax + t * (bx - ax), y = ay + t * (by - ay);
        float r = k.r;
        if (vertical(c)) {
            if (y < k.T + 2.2f * r || y > k.B - 2.2f * r) return null;
        } else {
            if (x < k.L + 2.2f * r || x > k.R - 2.2f * r || Math.abs(x - k.mx) < 2.2f * r) return null;
        }
        return new float[]{x, y};
    }

    private static float[] bank1(Ctx k, float px, float py, int c) {
        if (!inside(k, c, px, py, k.r) || !inside(k, c, k.ox, k.oy, 0.5f * k.r)) return null;
        float[] q = mirror(k, c, px, py);
        float[] h = hitCushion(k, c, k.ox, k.oy, q[0], q[1]);
        if (h == null) return null;
        return new float[]{k.ox, k.oy, h[0], h[1], px, py};
    }

    private static float[] bank2(Ctx k, float px, float py, int c1, int c2) {
        if (!inside(k, c2, px, py, k.r) || !inside(k, c1, k.ox, k.oy, 0.5f * k.r)) return null;
        float[] p2 = mirror(k, c2, px, py);
        float[] p12 = mirror(k, c1, p2[0], p2[1]);
        float[] h1 = hitCushion(k, c1, k.ox, k.oy, p12[0], p12[1]);
        if (h1 == null) return null;
        float[] h2 = hitCushion(k, c2, h1[0], h1[1], p2[0], p2[1]);
        if (h2 == null) return null;
        return new float[]{k.ox, k.oy, h1[0], h1[1], h2[0], h2[1], px, py};
    }

    private static float pathLen(float[] p) {
        float s = 0;
        for (int i = 0; i + 3 < p.length; i += 2) s += (float) Math.hypot(p[i + 2] - p[i], p[i + 3] - p[i + 1]);
        return s;
    }

    /** Какой «запас пути» нужен в начале ломаной, чтобы дойти до конца (с потерями на каждом борте). */
    static float needed(float[] p) {
        float req = 0;
        int legs = p.length / 2 - 1;
        for (int i = legs - 1; i >= 0; i--) {
            req += (float) Math.hypot(p[2 * i + 2] - p[2 * i], p[2 * i + 3] - p[2 * i + 1]);
            if (i > 0) req /= CUSH;
        }
        return req;
    }

    private static boolean blocked(Ctx k, float[] p, float lim) {
        if (k.obst == null) return false;
        for (int i = 0; i + 3 < p.length; i += 2) {
            for (int j = 0; j + 1 < k.obst.length; j += 2) {
                if (segDist(k.obst[j], k.obst[j + 1], p[i], p[i + 1], p[i + 2], p[i + 3]) < lim) return true;
            }
        }
        return false;
    }

    /** op — путь прицельного шара: O, борта..., P. kick=true — биток идёт к удару через борт. */
    private static Shot make(Ctx k, int pocket, float[] op, boolean kick) {
        float r = k.r;
        float ux = op[2] - op[0], uy = op[3] - op[1];
        float len1 = (float) Math.hypot(ux, uy);
        if (len1 < 0.5f * r) return null;
        ux /= len1;
        uy /= len1;

        // Средние лузы не принимают шар под острым углом.
        int n = op.length;
        float fx = op[n - 2] - op[n - 4], fy = op[n - 1] - op[n - 3];
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

        float lim = 1.9f * r;
        if (blocked(k, op, lim)) return null;

        float[] cp;
        if (!kick) {
            cp = new float[]{k.cx, k.cy, gx, gy};
        } else {
            cp = null;
            float bestLen = Float.MAX_VALUE;
            for (int c = 0; c < 4; c++) {
                if (!inside(k, c, gx, gy, 0.3f * r) || !inside(k, c, k.cx, k.cy, 0.3f * r)) continue;
                float[] gm = mirror(k, c, gx, gy);
                float[] h = hitCushion(k, c, k.cx, k.cy, gm[0], gm[1]);
                if (h == null) continue;
                float[] cand = {k.cx, k.cy, h[0], h[1], gx, gy};
                // Биток не должен задеть прицельный шар по дороге к борту.
                if (segDist(k.ox, k.oy, k.cx, k.cy, h[0], h[1]) < 2f * r) continue;
                if (blocked(k, cand, lim)) continue;
                float ln = pathLen(cand);
                // Подход к призрачному шару — тоже с допустимой резкой.
                float ddx = gx - h[0], ddy = gy - h[1], dl = (float) Math.hypot(ddx, ddy);
                if (dl < 0.3f * r || (ddx * ux + ddy * uy) / dl < COS_MAX_CUT) continue;
                if (ln < bestLen) {
                    bestLen = ln;
                    cp = cand;
                }
            }
            if (cp == null) return null;
        }
        if (!kick && blocked(k, cp, lim)) return null;

        int m = cp.length;
        float dx = cp[m - 2] - cp[m - 4], dy = cp[m - 1] - cp[m - 3];
        float dl = (float) Math.hypot(dx, dy);
        if (dl < 0.3f * r) return null;
        dx /= dl;
        dy /= dl;
        float cos = dx * ux + dy * uy;
        if (cos < COS_MAX_CUT) return null;
        float cut = (float) Math.toDegrees(Math.acos(Math.min(1f, cos)));

        Shot s = new Shot();
        s.obj = k.obj;
        s.pocket = pocket;
        s.objCush = n / 2 - 2;
        s.cueCush = m / 2 - 2;
        s.cx = k.cx;
        s.cy = k.cy;
        s.ox = k.ox;
        s.oy = k.oy;
        s.px = op[n - 2];
        s.py = op[n - 1];
        s.gx = gx;
        s.gy = gy;
        s.objPath = op;
        s.cuePath = cp;
        s.cut = cut;
        s.cos2 = cos * cos;

        // Сила: сколько нужно в начале, чтобы шар дошёл до лузы.
        float lob = needed(op) + 0.3f * r;
        float atG = lob / Math.max(s.cos2, 0.01f);
        float req = atG;
        int legs = m / 2 - 1;
        for (int i = legs - 1; i >= 0; i--) {
            req += (float) Math.hypot(cp[2 * i + 2] - cp[2 * i], cp[2 * i + 3] - cp[2 * i + 1]);
            if (i > 0) req /= CUSH;
        }
        s.pmin = (float) Math.sqrt(req / k.dmax);
        if (s.pmin > 1.05f) return null;

        s.score = cut + 20f * pathLen(op) / k.diag + 12f * pathLen(cp) / k.diag
                + 25f * s.objCush + 30f * s.cueCush + sidePenalty + 12f * s.pmin;

        float tx = dx - cos * ux, ty = dy - cos * uy;
        float tl = (float) Math.hypot(tx, ty);
        if (cut >= 2f && tl > 1e-4f) {
            s.tx = tx / tl;
            s.ty = ty / tl;
        }
        return s;
    }

    static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay;
        float l2 = vx * vx + vy * vy;
        float t = l2 < 1e-6f ? 0f : ((px - ax) * vx + (py - ay) * vy) / l2;
        t = Math.max(0f, Math.min(1f, t));
        return (float) Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }

    // ------------------------------------------------------------------ прокатка с учётом силы

    static float budget(float power, float dmax) {
        return dmax * power * power;
    }

    /** Проход по ломаной с запасом пути rem (потери на внутренних вершинах). Возвращает [остаток или -1, n точек...]. */
    private static float[] travel(float[] path, float rem, float[] outPts, int[] outN) {
        int n = 0;
        outPts[n++] = path[0];
        outPts[n++] = path[1];
        for (int i = 0; i + 3 < path.length; i += 2) {
            float x0 = path[i], y0 = path[i + 1], x1 = path[i + 2], y1 = path[i + 3];
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            if (rem >= len) {
                outPts[n++] = x1;
                outPts[n++] = y1;
                rem -= len;
                if (i + 4 < path.length) rem *= CUSH;
            } else {
                float t = len < 1e-6f ? 0f : rem / len;
                outPts[n++] = x0 + (x1 - x0) * t;
                outPts[n++] = y0 + (y1 - y0) * t;
                outN[0] = n;
                return new float[]{-1f};
            }
        }
        outN[0] = n;
        return new float[]{rem};
    }

    private static float[] cut(float[] a, int n) {
        float[] res = new float[n];
        System.arraycopy(a, 0, res, 0, n);
        return res;
    }

    /** Сколько «запаса пути» останется у битка в момент удара (или -1, если не доедет). */
    static float atContact(Shot s, float power, float dmax) {
        float[] tmp = new float[s.cuePath.length];
        int[] nn = new int[1];
        return travel(s.cuePath, budget(power, dmax), tmp, nn)[0];
    }

    static Run objectRun(Shot s, float power, float dmax) {
        Run run = new Run();
        float e1 = atContact(s, power, dmax);
        if (e1 <= 0) {
            run.pts = new float[]{s.ox, s.oy};
            return run;
        }
        float[] tmp = new float[s.objPath.length];
        int[] nn = new int[1];
        float rest = travel(s.objPath, e1 * s.cos2, tmp, nn)[0];
        run.pts = cut(tmp, nn[0]);
        run.full = rest >= 0;
        return run;
    }

    static Run cueRun(Shot s, float power, float dmax, float L, float T, float R, float B, float r, float[] obstacles) {
        Run run = new Run();
        float[] tmp = new float[s.cuePath.length];
        int[] nn = new int[1];
        float e1 = travel(s.cuePath, budget(power, dmax), tmp, nn)[0];
        if (e1 < 0) {
            run.pts = cut(tmp, nn[0]);
            run.shortOfObject = true;
            return run;
        }
        float ec = e1 * (1f - s.cos2);
        if ((s.tx == 0 && s.ty == 0) || ec < 0.3f * r) {
            run.pts = new float[]{s.gx, s.gy};
            run.scratch = pocketAt(s.gx, s.gy, L, T, R, B, r) >= 0;
            return run;
        }
        return trace(s.gx, s.gy, s.tx, s.ty, ec, L, T, R, B, r, obstacles, 8, true);
    }

    /**
     * Шар из (x,y) в направлении (dx,dy) с запасом пути len: отскоки от бортов (с потерями, если loss),
     * остановка у лузы или у другого шара.
     */
    static Run trace(float x, float y, float dx, float dy, float len, float L, float T, float R, float B, float r,
                     float[] obstacles, int maxSeg, boolean loss) {
        Run run = new Run();
        float xl = L + r, xr = R - r, yt = T + r, yb = B - r, mx = (L + R) / 2f, mouth = 2.2f * r;
        x = Math.max(xl, Math.min(xr, x));
        y = Math.max(yt, Math.min(yb, y));
        float[] out = new float[2 * (maxSeg + 2)];
        int n = 0;
        out[n++] = x;
        out[n++] = y;
        float rem = len;
        for (int seg = 0; seg < maxSeg && rem > 0; seg++) {
            float tx = dx > 1e-6f ? (xr - x) / dx : dx < -1e-6f ? (xl - x) / dx : Float.MAX_VALUE;
            float ty = dy > 1e-6f ? (yb - y) / dy : dy < -1e-6f ? (yt - y) / dy : Float.MAX_VALUE;
            float tw = Math.max(0f, Math.min(tx, ty));
            float segLen = Math.min(tw, rem);
            float ex = x + dx * segLen, ey = y + dy * segLen;
            // Шар на пути?
            float bestT = 2f;
            int bestBall = -1;
            if (obstacles != null) {
                for (int j = 0; j + 1 < obstacles.length; j += 2) {
                    float t = firstHit(x, y, ex, ey, obstacles[j], obstacles[j + 1], 2f * r);
                    if (t >= 0 && t < bestT && t * segLen > 1e-2f) {
                        bestT = t;
                        bestBall = j / 2;
                    }
                }
            }
            if (bestBall >= 0) {
                out[n++] = x + (ex - x) * bestT;
                out[n++] = y + (ey - y) * bestT;
                run.hit = true;
                run.hitIdx = bestBall;
                run.hitX = obstacles[2 * bestBall];
                run.hitY = obstacles[2 * bestBall + 1];
                break;
            }
            out[n++] = ex;
            out[n++] = ey;
            if (tw >= rem) break; // остановился посреди стола
            // Дошёл до борта: если это зев лузы — падает.
            int pocket = -1;
            if (tx <= ty + 1e-4f) { // левый/правый борт
                if (ey < T + mouth) pocket = ex < mx ? 0 : 2;
                else if (ey > B - mouth) pocket = ex < mx ? 3 : 5;
            }
            if (pocket < 0 && ty <= tx + 1e-4f) { // верхний/нижний борт
                boolean top = ey < (T + B) / 2f;
                if (ex < L + mouth) pocket = top ? 0 : 3;
                else if (ex > R - mouth) pocket = top ? 2 : 5;
                else if (Math.abs(ex - mx) < mouth) pocket = top ? 1 : 4;
            }
            if (pocket >= 0) {
                run.scratch = true;
                run.scratchPocket = pocket;
                break;
            }
            x = ex;
            y = ey;
            rem = (rem - tw) * (loss ? CUSH : 1f);
            if (tx <= ty + 1e-4f) dx = -dx;
            if (ty <= tx + 1e-4f) dy = -dy;
        }
        run.pts = cut(out, n);
        return run;
    }

    /**
     * Продолжение линии прицела: биток из (cx,cy) в направлении (dx,dy).
     * balls — все шары кроме битка (x0,y0,x1,y1,...). power<0 — без учёта силы (показываем геометрию).
     */
    static Prediction predict(float cx, float cy, float dx, float dy, float[] balls,
                              float L, float T, float R, float B, float r, float power, float dmax) {
        Prediction pr = new Prediction();
        float dl = (float) Math.hypot(dx, dy);
        if (dl < 1e-6f) return null;
        dx /= dl;
        dy /= dl;
        float tl = R - L;
        boolean usePower = power >= 0;
        float startBudget = usePower ? budget(power, dmax) : 4f * tl;
        Run cue = trace(cx, cy, dx, dy, startBudget, L, T, R, B, r, balls, 6, usePower);
        pr.cuePath = cue.pts;
        pr.scratch = cue.scratch;
        if (!cue.hit) return pr;

        pr.hitBall = true;
        pr.hitIdx = cue.hitIdx;
        int n = cue.pts.length;
        pr.gx = cue.pts[n - 2];
        pr.gy = cue.pts[n - 1];
        // Направление битка в момент удара — последний отрезок.
        float ix = cue.pts[n - 2] - cue.pts[n - 4], iy = cue.pts[n - 1] - cue.pts[n - 3];
        float il = (float) Math.hypot(ix, iy);
        if (il < 1e-6f) {
            ix = dx;
            iy = dy;
            il = 1f;
        }
        ix /= il;
        iy /= il;
        float ox = cue.hitX, oy = cue.hitY;
        float nx = ox - pr.gx, ny = oy - pr.gy;
        float nl = (float) Math.hypot(nx, ny);
        nx /= nl;
        ny /= nl;
        float cos = Math.max(0f, ix * nx + iy * ny);
        pr.cut = (float) Math.toDegrees(Math.acos(Math.min(1f, cos)));

        // Остаток пути в момент удара.
        float e1;
        if (usePower) {
            float used = 0;
            float rem = startBudget;
            for (int i = 0; i + 3 < n; i += 2) {
                float len = (float) Math.hypot(cue.pts[i + 2] - cue.pts[i], cue.pts[i + 3] - cue.pts[i + 1]);
                rem -= len;
                used += len;
                if (i + 4 < n) rem *= CUSH;
            }
            e1 = Math.max(0f, rem);
        } else {
            e1 = 3f * tl;
        }

        // Остальные шары — помехи для задетого шара (кроме него самого).
        float[] others = new float[Math.max(0, balls.length - 2)];
        int k = 0;
        for (int j = 0; j + 1 < balls.length; j += 2) {
            if (j / 2 == cue.hitIdx) continue;
            others[k++] = balls[j];
            others[k++] = balls[j + 1];
        }
        float[] withCue = new float[others.length + 2];
        System.arraycopy(others, 0, withCue, 0, others.length);
        withCue[others.length] = pr.gx;
        withCue[others.length + 1] = pr.gy;

        float objLen = usePower ? e1 * cos * cos : e1;
        Run obj = trace(ox, oy, nx, ny, objLen, L, T, R, B, r, others, 6, usePower);
        pr.objPath = obj.pts;
        pr.potted = obj.scratch;
        pr.pocket = obj.scratchPocket;
        pr.objHitsBall = obj.hit;

        float tx = ix - cos * nx, ty = iy - cos * ny;
        float tt = (float) Math.hypot(tx, ty);
        if (pr.cut >= 2f && tt > 1e-4f) {
            float cueLen = usePower ? e1 * (1 - cos * cos) : 1.2f * tl;
            Run after = trace(pr.gx, pr.gy, tx / tt, ty / tt, cueLen, L, T, R, B, r, others, 6, usePower);
            pr.cueAfter = after.pts;
            pr.scratch = after.scratch;
        }
        return pr;
    }

    static int pocketAt(float x, float y, float L, float T, float R, float B, float r) {
        float[] pk = pockets(L, T, R, B);
        for (int q = 0; q < 6; q++) {
            float cap = (q == 1 || q == 4) ? 1.4f * r : 1.6f * r;
            if (Math.hypot(x - pk[2 * q], y - pk[2 * q + 1]) < cap) return q;
        }
        return -1;
    }

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
        return cut(pts, n);
    }
}
