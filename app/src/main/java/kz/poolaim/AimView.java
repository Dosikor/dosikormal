package kz.poolaim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Полноэкранный прозрачный слой. Всё автоматически: стол и шары находит служба,
 * а здесь для каждого шара рисуется его лучший удар, выбранный — подробно (сила, путь битка).
 */
public class AimView extends View {

    interface Host {
        void onAimLayout(int w, int h);

        void onCalibEdited();

        void onPowerShown(float power, boolean auto);
    }

    private final Host host;
    private final float dp;

    // Стол (внутренние края бортов) и радиус шара, в пикселях этого окна.
    float L, T, R, B, r;
    boolean tableReady;
    boolean banks, calib, showCue = true;
    float sx, sy; // пробный шар в режиме ручной подстройки

    private List<Geo.Ball> balls = new ArrayList<>();
    private int cueIdx = -1;
    int filter = Geo.FILTER_ALL;
    private String status;

    float dmaxTables = 6f;
    boolean powerAuto = true;
    float powerManual = 0.5f;

    private List<Geo.Shot> perBall = new ArrayList<>(); // лучший удар для каждого шара, от простого к сложному
    private int sel = 0;
    private boolean selManual;
    private float selX, selY;

    private final Paint pTable, pPocket, pObj, pObjFaint, pAim, pGhost, pDot, pCue, pCueBad, pText, pSmall, pHandle,
            pCal, pAlt, pAltGhost, pAltObj, pRing, pRingStripe, pCueRing, pBad, pNoShot, pStop, pHit;
    private final Path path = new Path();

    private int drag = -1;
    private float lastX, lastY;

    public AimView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        pTable = stroke(0x33FFFFFF, 1.2f);
        pPocket = stroke(0x55FFFFFF, 1.2f);
        pObj = stroke(0xFF00E676, 3f);
        pObjFaint = stroke(0x8800E676, 2f);
        pObjFaint.setPathEffect(new DashPathEffect(new float[]{3 * dp, 5 * dp}, 0));
        pAim = stroke(0xFFFFFFFF, 1.8f);
        pGhost = stroke(0xFFFFFFFF, 2.2f);
        pDot = fill(0xFFFFFFFF);
        pCue = stroke(0xFF40C4FF, 2f);
        pCue.setPathEffect(new DashPathEffect(new float[]{10 * dp, 6 * dp}, 0));
        pCueBad = stroke(0xFFFF5252, 2.5f);
        pCueBad.setPathEffect(new DashPathEffect(new float[]{10 * dp, 6 * dp}, 0));
        pStop = stroke(0xCC40C4FF, 1.5f);
        pStop.setPathEffect(new DashPathEffect(new float[]{4 * dp, 3 * dp}, 0));
        pHit = stroke(0xFFFFAB40, 2f);
        pText = fill(0xFFFFFFFF);
        pText.setTextSize(14 * dp);
        pText.setFakeBoldText(true);
        pText.setShadowLayer(3 * dp, 0, 0, 0xFF000000);
        pSmall = fill(0xFFFFFFFF);
        pSmall.setTextSize(12 * dp);
        pSmall.setFakeBoldText(true);
        pSmall.setShadowLayer(3 * dp, 0, 0, 0xFF000000);
        pHandle = fill(0xCCFFC107);
        pCal = stroke(0xFFFFC107, 2f);
        pAlt = stroke(0x66FFFFFF, 1.2f);
        pAltGhost = stroke(0x99FFFFFF, 1.5f);
        pAltObj = stroke(0x9900E676, 2f);
        pRing = stroke(0x99FFFFFF, 1.2f);
        pRingStripe = stroke(0x99FFFFFF, 1.2f);
        pRingStripe.setPathEffect(new DashPathEffect(new float[]{3 * dp, 3 * dp}, 0));
        pCueRing = stroke(0xFFFFFFFF, 2.4f);
        pBad = stroke(0xFFFF5252, 2.5f);
        pNoShot = stroke(0x88FF5252, 1.2f);
        pNoShot.setPathEffect(new DashPathEffect(new float[]{2 * dp, 3 * dp}, 0));
    }

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * dp);
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    float dmax() {
        return dmaxTables * (R - L);
    }

    // ---------------------------------------------------------------- состояние

    void setTable(float l, float t, float rr, float b, float rad) {
        L = l;
        T = t;
        R = rr;
        B = b;
        r = rad;
        sx = (l + rr) / 2f;
        sy = (t + b) / 2f;
        tableReady = rr - l > 10 && b - t > 10 && rad > 0;
        recompute();
    }

    void clearTable() {
        tableReady = false;
        balls = new ArrayList<>();
        cueIdx = -1;
        recompute();
    }

    void setDetected(List<Geo.Ball> list, int cue) {
        if (cue == cueIdx && list.size() == balls.size()) {
            boolean same = true;
            for (int i = 0; i < list.size() && same; i++) {
                Geo.Ball a = list.get(i), b = balls.get(i);
                same = a.x == b.x && a.y == b.y && a.type == b.type;
            }
            if (same) return; // ничего не поменялось — не перерисовываем
        }
        balls = list;
        cueIdx = cue;
        recompute();
    }

    List<Geo.Ball> getBalls() {
        return balls;
    }

    void setStatus(String s) {
        if (s == null ? status == null : s.equals(status)) return;
        status = s;
        invalidate();
    }

    void setBanks(boolean on) {
        banks = on;
        selManual = false;
        recompute();
    }

    void setFilter(int f) {
        filter = f;
        selManual = false;
        recompute();
    }

    void setCalib(boolean on) {
        calib = on;
        drag = -1;
        recompute();
    }

    void setDmaxTables(float v) {
        dmaxTables = v;
        recompute();
    }

    void setPower(boolean autoPower, float manual) {
        powerAuto = autoPower;
        powerManual = manual;
        notifyPower();
        invalidate();
    }

    void toggleCuePath() {
        showCue = !showCue;
        invalidate();
    }

    void nextShot() {
        if (perBall.isEmpty()) return;
        sel = (sel + 1) % perBall.size();
        selManual = sel != 0;
        selX = perBall.get(sel).ox;
        selY = perBall.get(sel).oy;
        notifyPower();
        invalidate();
    }

    void recompute() {
        perBall = new ArrayList<>();
        if (tableReady && cueIdx >= 0 && cueIdx < balls.size()) {
            List<Geo.Shot> all = Geo.computeAll(L, T, R, B, r, balls, cueIdx, filter, banks, dmax());
            boolean[] used = new boolean[balls.size()];
            for (Geo.Shot s : all) {
                if (s.obj < 0 || used[s.obj]) continue;
                used[s.obj] = true;
                perBall.add(s);
            }
        }
        sel = 0;
        if (selManual) {
            int idx = -1;
            for (int i = 0; i < perBall.size(); i++) {
                Geo.Shot s = perBall.get(i);
                if (Math.hypot(s.ox - selX, s.oy - selY) < 1.5f * r) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                sel = idx;
                selX = perBall.get(idx).ox;
                selY = perBall.get(idx).oy;
            } else {
                selManual = false;
            }
        }
        notifyPower();
        invalidate();
    }

    private boolean isBreak() {
        return balls.size() - (cueIdx >= 0 ? 1 : 0) >= 12;
    }

    private int nearestTarget() {
        if (cueIdx < 0) return -1;
        Geo.Ball c = balls.get(cueIdx);
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < balls.size(); i++) {
            if (i == cueIdx) continue;
            double d = Math.hypot(balls.get(i).x - c.x, balls.get(i).y - c.y);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private float power() {
        if (perBall.isEmpty()) return powerAuto ? (cueIdx >= 0 && isBreak() ? 1f : 0f) : powerManual;
        return powerAuto ? perBall.get(sel).recPower() : powerManual;
    }

    private void notifyPower() {
        host.onPowerShown(power(), powerAuto);
    }

    private float[] obstaclesFor(Geo.Shot s) {
        int n = 0;
        float[] o = new float[2 * balls.size()];
        for (int i = 0; i < balls.size(); i++) {
            if (i == cueIdx || i == s.obj) continue;
            o[n++] = balls.get(i).x;
            o[n++] = balls.get(i).y;
        }
        float[] res = new float[n];
        System.arraycopy(o, 0, res, 0, n);
        return res;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            final int w = right - left, h = bottom - top;
            post(() -> host.onAimLayout(w, h));
        }
    }

    // ---------------------------------------------------------------- рисование

    @Override
    protected void onDraw(Canvas c) {
        if (calib && tableReady) {
            drawCalib(c);
            return;
        }
        if (status != null) {
            pSmall.setColor(0xFFFFFFFF);
            c.drawText(status, (getWidth() - pSmall.measureText(status)) / 2f, 18 * dp, pSmall);
        }
        if (!tableReady) return;

        c.drawRect(L, T, R, B, pTable);
        float[] pk = Geo.pockets(L, T, R, B);
        for (int i = 0; i < 6; i++) c.drawCircle(pk[2 * i], pk[2 * i + 1], r * 0.7f, pPocket);

        boolean[] hasShot = new boolean[balls.size()];
        for (Geo.Shot s : perBall) if (s.obj >= 0 && s.obj < hasShot.length) hasShot[s.obj] = true;
        for (int i = 0; i < balls.size(); i++) {
            Geo.Ball b = balls.get(i);
            if (i == cueIdx) {
                c.drawCircle(b.x, b.y, r + 2 * dp, pCueRing);
                continue;
            }
            boolean on = Geo.typeMatches(b.type, filter);
            if (on && !hasShot[i] && cueIdx >= 0) {
                c.drawCircle(b.x, b.y, r + 2 * dp, pNoShot);
            } else {
                Paint p = b.type == Geo.STRIPE ? pRingStripe : pRing;
                p.setAlpha(on ? 0x99 : 0x33);
                c.drawCircle(b.x, b.y, r + 1.5f * dp, p);
            }
        }
        if (cueIdx < 0) return;

        Geo.Ball cueBall = balls.get(cueIdx);
        if (perBall.isEmpty()) {
            // Чистого удара нет (например, разбой): показываем ближайший шар.
            int near = nearestTarget();
            if (near >= 0) {
                Geo.Ball nb = balls.get(near);
                line(c, cueBall.x, cueBall.y, nb.x, nb.y, r + 3 * dp, r + 3 * dp, pHit);
                c.drawCircle(nb.x, nb.y, r + 3 * dp, pHit);
            }
            pText.setColor(0xFFFFAB40);
            String msg = isBreak() ? "РАЗБОЙ: бей в первый шар, сила 100%"
                    : (banks ? "чистого удара нет — бей в ближний шар" : "чистого удара нет — нажми Б (через борт)");
            labelAt(c, msg, cueBall.x, cueBall.y, null);
            return;
        }

        // Линия для каждого шара: куда целиться и в какую лузу.
        for (int i = 0; i < perBall.size(); i++) {
            if (i == sel) continue;
            Geo.Shot a = perBall.get(i);
            line(c, a.cx, a.cy, a.gx, a.gy, r + 3 * dp, r, pAlt);
            c.drawCircle(a.gx, a.gy, r, pAltGhost);
            drawPoly(c, trimStart(a.cushion >= 0
                    ? new float[]{a.ox, a.oy, a.kx, a.ky, a.px, a.py}
                    : new float[]{a.ox, a.oy, a.px, a.py}, r + 3 * dp), pAltObj);
        }

        Geo.Shot s = perBall.get(sel);
        float dmax = dmax();
        float p = power();
        Geo.Run obj = Geo.objectRun(s, p, dmax);
        Geo.Run cue = Geo.cueRun(s, p, dmax, L, T, R, B, r, obstaclesFor(s));

        if (!obj.full) {
            drawPoly(c, trimStart(s.cushion >= 0
                    ? new float[]{s.ox, s.oy, s.kx, s.ky, s.px, s.py}
                    : new float[]{s.ox, s.oy, s.px, s.py}, r + 3 * dp), pObjFaint);
        }
        drawPoly(c, trimStart(obj.pts, r + 3 * dp), pObj);
        c.drawCircle(s.px, s.py, r * 1.1f, obj.full ? pObj : pObjFaint);
        if (!obj.full && obj.pts.length >= 2) {
            cross(c, obj.pts[obj.pts.length - 2], obj.pts[obj.pts.length - 1], r * 0.6f, pBad);
        }

        if (cue.shortOfObject) {
            drawPoly(c, trimStart(cue.pts, r + 3 * dp), pBad);
            cross(c, cue.pts[cue.pts.length - 2], cue.pts[cue.pts.length - 1], r * 0.6f, pBad);
        } else {
            line(c, s.cx, s.cy, s.gx, s.gy, r + 3 * dp, r, pAim);
        }
        c.drawCircle(s.gx, s.gy, r, pGhost);
        c.drawCircle(s.gx, s.gy, 1.8f * dp, pDot);

        if (showCue && !cue.shortOfObject && cue.pts.length >= 4) {
            drawPoly(c, cue.pts, cue.scratch ? pCueBad : pCue);
            float ex = cue.pts[cue.pts.length - 2], ey = cue.pts[cue.pts.length - 1];
            c.drawCircle(ex, ey, r, cue.scratch ? pBad : pStop);
            if (cue.hit) c.drawCircle(cue.hitX, cue.hitY, r + 3 * dp, pHit);
        }

        int col = s.cut < 30 ? 0xFF69F0AE : s.cut < 60 ? 0xFFFFD740 : 0xFFFF5252;
        String warn = null;
        if (cue.shortOfObject) warn = "слабо: биток не доедет";
        else if (!obj.full) warn = "слабо: шар не докатится (нужно ≥ " + pct(s.pmin) + ")";
        else if (cue.scratch && showCue) warn = "⚠ биток упадёт в лузу — ⟳ другой шар или сила";
        pText.setColor(col);
        labelAt(c, Math.round(s.cut) + "°" + (s.cushion >= 0 ? " борт" : "")
                + "   сила " + pct(p) + "   " + (sel + 1) + "/" + perBall.size(), s.ox, s.oy, warn);
    }

    private static String pct(float p) {
        return Math.round(Math.min(1f, p) * 100) + "%";
    }

    private void drawPoly(Canvas c, float[] pts, Paint p) {
        if (pts == null || pts.length < 4) return;
        path.reset();
        path.moveTo(pts[0], pts[1]);
        for (int i = 2; i + 1 < pts.length; i += 2) path.lineTo(pts[i], pts[i + 1]);
        c.drawPath(path, p);
    }

    /** Линия, укороченная на a от начала и на b от конца — чтобы не рисовать поверх шаров. */
    private void line(Canvas c, float x0, float y0, float x1, float y1, float a, float b, Paint p) {
        float dx = x1 - x0, dy = y1 - y0;
        float l = (float) Math.hypot(dx, dy);
        if (l <= a + b + 1) return;
        dx /= l;
        dy /= l;
        c.drawLine(x0 + dx * a, y0 + dy * a, x1 - dx * b, y1 - dy * b, p);
    }

    /** Ломаная без первых d пикселей. */
    private static float[] trimStart(float[] pts, float d) {
        if (pts == null || pts.length < 4) return pts;
        float dx = pts[2] - pts[0], dy = pts[3] - pts[1];
        float l = (float) Math.hypot(dx, dy);
        float[] o = pts.clone();
        if (l <= d + 1) {
            if (pts.length <= 4) return new float[0];
            float[] rest = new float[pts.length - 2];
            System.arraycopy(pts, 2, rest, 0, rest.length);
            return rest;
        }
        o[0] = pts[0] + dx / l * d;
        o[1] = pts[1] + dy / l * d;
        return o;
    }

    private void cross(Canvas c, float x, float y, float k, Paint p) {
        c.drawLine(x - k, y - k, x + k, y + k, p);
        c.drawLine(x - k, y + k, x + k, y - k, p);
    }

    private void labelAt(Canvas c, String t, float bx, float by, String warn) {
        float w = pText.measureText(t);
        if (warn != null) w = Math.max(w, pText.measureText(warn));
        float x = bx + 1.6f * r;
        if (x + w > getWidth() - 4 * dp) x = bx - 1.6f * r - w;
        if (x < 4 * dp) x = 4 * dp;
        float y = by - 1.6f * r - (warn != null ? 18 * dp : 0);
        if (y < 36 * dp) y = by + 1.6f * r + 14 * dp;
        c.drawText(t, x, y, pText);
        if (warn != null) {
            pText.setColor(0xFFFF5252);
            c.drawText(warn, x, y + 18 * dp, pText);
        }
    }

    private void drawCalib(Canvas c) {
        c.drawColor(0x55000000);
        c.drawRect(L, T, R, B, pCal);
        float[] pk = Geo.pockets(L, T, R, B);
        for (int i = 0; i < 6; i++) c.drawCircle(pk[2 * i], pk[2 * i + 1], r * 0.7f, pCal);
        float hr = 11 * dp;
        c.drawCircle(L, T, hr, pHandle);
        c.drawCircle(R, T, hr, pHandle);
        c.drawCircle(L, B, hr, pHandle);
        c.drawCircle(R, B, hr, pHandle);

        c.drawCircle(sx, sy, r, pGhost);
        c.drawLine(sx - r * 0.5f, sy, sx + r * 0.5f, sy, pAim);
        c.drawLine(sx, sy - r * 0.5f, sx, sy + r * 0.5f, pAim);
        float hx = sx + r + 18 * dp;
        c.drawLine(sx + r, sy, hx, sy, pCal);
        c.drawCircle(hx, sy, 10 * dp, pHandle);

        pText.setColor(0xFFFFFFFF);
        String[] lines = {
                "ПОДСТРОЙКА (нужна, только если стол найден неточно)",
                "Жёлтые углы рамки — на края сукна. Белый круг — на шар, ручкой ● — размер.",
                "Готово — ✓.  Вернуть автонастройку — 🎯"
        };
        float y = 28 * dp;
        for (String line : lines) {
            c.drawText(line, (getWidth() - pText.measureText(line)) / 2f, y, pText);
            y += 20 * dp;
        }
    }

    // ---------------------------------------------------------------- ручная подстройка пальцем

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!calib) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drag = pick(x, y);
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                applyDrag(dx, dy);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag = -1;
                host.onCalibEdited();
                return true;
            default:
                return true;
        }
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private int pick(float x, float y) {
        float tol = 30 * dp;
        if (dist(x, y, sx + r + 18 * dp, sy) < tol) return 5;
        if (dist(x, y, L, T) < tol) return 0;
        if (dist(x, y, R, T) < tol) return 1;
        if (dist(x, y, L, B) < tol) return 2;
        if (dist(x, y, R, B) < tol) return 3;
        if (dist(x, y, sx, sy) < r + 14 * dp) return 6;
        if (x > L && x < R && y > T && y < B) return 4;
        return -1;
    }

    private void applyDrag(float dx, float dy) {
        float min = 60 * dp;
        switch (drag) {
            case 0:
                L = Math.min(L + dx, R - min);
                T = Math.min(T + dy, B - min);
                break;
            case 1:
                R = Math.max(R + dx, L + min);
                T = Math.min(T + dy, B - min);
                break;
            case 2:
                L = Math.min(L + dx, R - min);
                B = Math.max(B + dy, T + min);
                break;
            case 3:
                R = Math.max(R + dx, L + min);
                B = Math.max(B + dy, T + min);
                break;
            case 4:
                L += dx;
                R += dx;
                T += dy;
                B += dy;
                sx += dx;
                sy += dy;
                break;
            case 5:
                r = Math.max(3 * dp, Math.min(60 * dp, r + dx * 0.5f));
                break;
            case 6:
                sx += dx;
                sy += dy;
                break;
            default:
                break;
        }
    }
}
