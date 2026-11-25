package kz.poolaim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Чистая геометрия бильярда (без Android API).
 * Стол задаётся внутренними краями бортов L,T,R,B, r — радиус шара.
 * Центр шара может двигаться только внутри прямоугольника, уменьшенного на r.
 */
final class Geo {

    /** cos(85°): резка круче 85° считается невозможной. */
    static final float COS_MAX_CUT = 0.0872f;

    static final class Shot {
        int pocket;      // 0 ЛВ, 1 СВ, 2 ПВ, 3 ЛН, 4 СН, 5 ПН
        int cushion;     // -1 прямой удар, 0 левый, 1 верхний, 2 правый, 3 нижний борт
        float px, py;    // точка лузы
        float kx, ky;    // точка касания борта (для удара через борт)
        float gx, gy;    // «призрачный шар» — где должен оказаться биток в момент удара
        float cut;       // угол резки, градусы
        float score;     // сложность (меньше — проще)
        float[] cuePath; // путь битка после удара: x0,y0,x1,y1,...

        String key() {
            return pocket + ":" + cushion;
        }
    }

    private static final class Ctx {
        float L, T, R, B, r, cx, cy, ox, oy, xl, xr, yt, yb, diag;
    }

    static float[] pockets(float L, float T, float R, float B) {
        float mx = (L + R) / 2f;
        return new float[]{L, T, mx, T, R, T, L, B, mx, B, R, B};
    }

    /**
     * Все возможные удары прицельным шаром (ox,oy) битком (cx,cy), отсортированные от простого к сложному.
     */
    static List<Shot> compute(float L, float T, float R, float B, float r,
                              float cx, float cy, float ox, float oy, boolean banks) {
        List<Shot> out = new ArrayList<>();
        if (r <= 0 || R - L < 6 * r || B - T < 6 * r) return out;

        Ctx k = new Ctx();
        k.L = L; k.T = T; k.R = R; k.B = B; k.r = r;
        k.cx = cx; k.cy = cy; k.ox = ox; k.oy = oy;
        k.xl = L + r; k.xr = R - r; k.yt = T + r; k.yb = B - r;
        k.diag = (float) Math.hypot(R - L, B - T);
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
                // Точка касания должна быть на борте, а не в лузе.
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

    /** (ax,ay) — куда сначала покатится прицельный шар: луза или точка на борте. */
    private static Shot make(Ctx k, int pocket, int cushion, float px, float py, float ax, float ay) {
        float r = k.r;
        float ux = ax - k.ox, uy = ay - k.oy;
        float len1 = (float) Math.hypot(ux, uy);
        if (len1 < 0.5f * r) return null;
        ux /= len1;
        uy /= len1;

        // Последний отрезок — в лузу.
        float fsx = cushion >= 0 ? ax : k.ox, fsy = cushion >= 0 ? ay : k.oy;
        float fx = px - fsx, fy = py - fsy;
        float len2 = cushion >= 0 ? (float) Math.hypot(fx, fy) : 0f;
        float sidePenalty = 0f;
        if (pocket == 1 || pocket == 4) {
            // Средние лузы не принимают шар под острым углом.
            double ang = Math.toDegrees(Math.atan2(Math.abs(fx), Math.abs(fy)));
            if (ang > 60) return null;
            if (pocket == 1 && fy >= 0) return null;
            if (pocket == 4 && fy <= 0) return null;
            sidePenalty = (float) ang * 0.3f;
        }

        // Призрачный шар: биток должен коснуться прицельного с противоположной от цели стороны.
        float gx = k.ox - 2 * r * ux, gy = k.oy - 2 * r * uy;
        float tol = 0.5f * r;
        if (gx < k.xl - tol || gx > k.xr + tol || gy < k.yt - tol || gy > k.yb + tol) return null;

        float dx = gx - k.cx, dy = gy - k.cy;
        float dl = (float) Math.hypot(dx, dy);
        if (dl < 0.3f * r) return null;
        dx /= dl;
        dy /= dl;
        float cos = dx * ux + dy * uy;
        // При резке < 90° биток гарантированно не заденет прицельный шар раньше точки удара.
        if (cos < COS_MAX_CUT) return null;
        float cut = (float) Math.toDegrees(Math.acos(Math.min(1f, cos)));

        Shot s = new Shot();
        s.pocket = pocket;
        s.cushion = cushion;
        s.px = px;
        s.py = py;
        s.kx = ax;
        s.ky = ay;
        s.gx = gx;
        s.gy = gy;
        s.cut = cut;
        s.score = cut + 20f * (len1 + len2) / k.diag + 12f * dl / k.diag
                + (cushion >= 0 ? 25f : 0f) + sidePenalty;

        // После удара без вращения биток уходит по касательной (правило 90°).
        float tx = dx - cos * ux, ty = dy - cos * uy;
        float tl = (float) Math.hypot(tx, ty);
        if (cut >= 4f && tl > 1e-4f) {
            tx /= tl;
            ty /= tl;
            float len = (k.R - k.L) * (0.35f + 0.65f * (float) Math.sin(Math.toRadians(cut)));
            s.cuePath = reflect(gx, gy, tx, ty, len, k.xl, k.xr, k.yt, k.yb, 4);
        }
        return s;
    }

    /** Луч с отражениями от бортов (угол падения = углу отражения). */
    static float[] reflect(float x, float y, float dx, float dy, float len,
                           float xl, float xr, float yt, float yb, int maxSeg) {
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
            rem -= t;
            if (tx <= ty + 1e-4f) dx = -dx;
            if (ty <= tx + 1e-4f) dy = -dy;
        }
        float[] res = new float[n];
        System.arraycopy(pts, 0, res, 0, n);
        return res;
    }
}
