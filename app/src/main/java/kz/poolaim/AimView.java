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

/** Полноэкранный прозрачный слой: рисует линии, а в режиме настройки — рамку стола. */
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
    // Ручной режим: центры битка (c) и прицельного шара (o) с колец.
    float cx, cy, ox, oy;
    boolean ready;
    boolean banks, calib, showCue = true;
    // Пробный шар для настройки размера.
    float sx, sy;

    // Авторежим: найденные шары.
    boolean auto;
    private List<Geo.Ball> balls = new ArrayList<>();
    private int cueIdx = -1;
    private boolean scannedOnce;
    int filter = Geo.FILTER_ALL;

    // Сила.
    float dmaxTables = 6f;
    boolean powerAuto = true;
    float powerManual = 0.5f;

    private List<Geo.Shot> shots = new ArrayList<>();
    private int sel = 0;
    private String selKey = null; // null — автоматически лучший удар
    private float selTx, selTy;

    private final Paint pTable, pPocket, pObj, pObjFaint, pAim, pGhost, pDot, pCue, pCueBad, pText, pHandle, pCal,
            pAlt, pAltObj, pRing, pRingStripe, pCueRing, pBad, pStop, pHit;
    private final Path path = new Path();

    private int drag = -1;
    private float lastX, lastY;

    public AimView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        pTable = stroke(0x40FFFFFF, 1.2f);
        pPocket = stroke(0x66FFFFFF, 1.2f);
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
        pHandle = fill(0xCCFFC107);
        pCal = stroke(0xFFFFC107, 2f);
        pAlt = stroke(0x77FFFFFF, 1.2f);
        pAltObj = stroke(0x6600E676, 1.5f);
        pRing = stroke(0x99FFFFFF, 1.2f);
        pRingStripe = stroke(0x99FFFFFF, 1.2f);
        pRingStripe.setPathEffect(new DashPathEffect(new float[]{3 * dp, 3 * dp}, 0));
        pCueRing = stroke(0xFFFFFFFF, 2.4f);
        pBad = stroke(0xFFFF5252, 2.5f);
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
        recompute();
    }

    void setBalls(float ccx, float ccy, float tcx, float tcy) {
        cx = ccx;
        cy = ccy;
        ox = tcx;
        oy = tcy;
        ready = true;
        if (!auto) recompute();
    }

    void setAuto(boolean on) {
        auto = on;
        selKey = null;
        if (!on) {
            balls = new ArrayList<>();
            cueIdx = -1;
            scannedOnce = false;
        }
        recompute();
    }

    void setDetected(List<Geo.Ball> list, int cue) {
        balls = list;
        cueIdx = cue;
        scannedOnce = true;
        if (auto) recompute();
    }

    List<Geo.Ball> getBalls() {
        return balls;
    }

    int getCueIdx() {
        return cueIdx;
    }

    /** {биток x,y, цель x,y} — для перехода в ручной режим. */
    float[] currentCueAndTarget() {
        if (auto && cueIdx >= 0) {
            Geo.Ball c = balls.get(cueIdx);
            float tx = c.x + 6 * r, ty = c.y;
            if (!shots.isEmpty()) {
                Geo.Shot s = shots.get(sel);
                tx = s.ox;
                ty = s.oy;
            }
            return new float[]{c.x, c.y, tx, ty};
        }
        return new float[]{cx, cy, ox, oy};
    }

    void setBanks(boolean on) {
        banks = on;
        selKey = null;
        recompute();
    }

    void setFilter(int f) {
        filter = f;
        selKey = null;
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
        if (shots.isEmpty()) return;
        sel = (sel + 1) % shots.size();
        if (sel == 0) {
            selKey = null;
        } else {
            Geo.Shot s = shots.get(sel);
            selKey = s.key();
            selTx = s.ox;
            selTy = s.oy;
        }
        notifyPower();
        invalidate();
    }

    void recompute() {
        if (R - L < 10 || B - T < 10) {
            shots = new ArrayList<>();
        } else if (auto) {
            shots = cueIdx >= 0
                    ? Geo.computeAll(L, T, R, B, r, balls, cueIdx, filter, banks, dmax())
                    : new ArrayList<>();
        } else if (ready) {
            shots = Geo.compute(L, T, R, B, r, cx, cy, ox, oy, banks, dmax(), null, -1);
        } else {
            shots = new ArrayList<>();
        }
        sel = 0;
        if (selKey != null) {
            int idx = -1;
            for (int i = 0; i < shots.size(); i++) {
                Geo.Shot s = shots.get(i);
                if (s.key().equals(selKey) && Math.hypot(s.ox - selTx, s.oy - selTy) < 1.5f * r) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                sel = idx;
                selTx = shots.get(idx).ox;
                selTy = shots.get(idx).oy;
            } else {
                selKey = null;
            }
        }
        notifyPower();
        invalidate();
    }

    private float power() {
        if (shots.isEmpty()) return powerAuto ? 0f : powerManual;
        return powerAuto ? shots.get(sel).recPower() : powerManual;
    }

    private void notifyPower() {
        host.onPowerShown(power(), powerAuto);
    }

    private float[] obstaclesFor(Geo.Shot s) {
        if (!auto) return null;
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
        if (R - L < 10) return;
        if (calib) {
            drawCalib(c);
            return;
        }
        c.drawRect(L, T, R, B, pTable);
        float[] pk = Geo.pockets(L, T, R, B);
        for (int i = 0; i < 6; i++) c.drawCircle(pk[2 * i], pk[2 * i + 1], r * 0.7f, pPocket);

        float ax, ay; // где писать подписи
        if (auto) {
            for (int i = 0; i < balls.size(); i++) {
                Geo.Ball b = balls.get(i);
                if (i == cueIdx) {
                    c.drawCircle(b.x, b.y, r + 2 * dp, pCueRing);
                } else {
                    boolean on = Geo.typeMatches(b.type, filter);
                    Paint p = b.type == Geo.STRIPE ? pRingStripe : pRing;
                    p.setAlpha(on ? 0x99 : 0x33);
                    c.drawCircle(b.x, b.y, r + 1.5f * dp, p);
                }
            }
            if (cueIdx < 0) {
                pText.setColor(0xFFFFD740);
                labelAt(c, scannedOnce ? (balls.isEmpty() ? "шары не найдены — проверь настройку ⚙" : "биток не найден")
                        : "ищу шары…", (L + R) / 2f, T + 3 * r, null);
                return;
            }
            ax = balls.get(cueIdx).x;
            ay = balls.get(cueIdx).y;
        } else {
            if (!ready) return;
            ax = ox;
            ay = oy;
        }

        if (shots.isEmpty()) {
            pText.setColor(0xFFFF5252);
            labelAt(c, banks ? "нет удара" : "нет удара — нажми Б (через борт)", ax, ay, null);
            return;
        }
        Geo.Shot s = shots.get(sel);
        float dmax = dmax();
        float p = power();

        // Другие варианты — бледно, чтобы видеть, куда ещё можно.
        int shown = 0;
        for (int i = 0; i < shots.size() && shown < 3; i++) {
            if (i == sel) continue;
            Geo.Shot a = shots.get(i);
            if (a.obj == s.obj && a.obj >= 0) continue;
            c.drawLine(a.cx, a.cy, a.gx, a.gy, pAlt);
            c.drawCircle(a.gx, a.gy, r, pAlt);
            path.reset();
            path.moveTo(a.ox, a.oy);
            if (a.cushion >= 0) path.lineTo(a.kx, a.ky);
            path.lineTo(a.px, a.py);
            c.drawPath(path, pAltObj);
            shown++;
        }

        Geo.Run obj = Geo.objectRun(s, p, dmax);
        Geo.Run cue = Geo.cueRun(s, p, dmax, L, T, R, B, r, obstaclesFor(s));

        // Куда покатится прицельный шар (пунктир — куда не докатится).
        if (!obj.full) {
            path.reset();
            path.moveTo(s.ox, s.oy);
            if (s.cushion >= 0) path.lineTo(s.kx, s.ky);
            path.lineTo(s.px, s.py);
            c.drawPath(path, pObjFaint);
        }
        drawPoly(c, obj.pts, pObj);
        c.drawCircle(s.px, s.py, r * 1.1f, obj.full ? pObj : pObjFaint);
        if (!obj.full && obj.pts.length >= 2) {
            float ex = obj.pts[obj.pts.length - 2], ey = obj.pts[obj.pts.length - 1];
            cross(c, ex, ey, r * 0.6f, pBad);
        }

        // Линия прицела: от битка к «призрачному шару».
        if (cue.shortOfObject) {
            drawPoly(c, cue.pts, pBad);
            float ex = cue.pts[cue.pts.length - 2], ey = cue.pts[cue.pts.length - 1];
            cross(c, ex, ey, r * 0.6f, pBad);
        } else {
            c.drawLine(s.cx, s.cy, s.gx, s.gy, pAim);
        }
        c.drawCircle(s.gx, s.gy, r, pGhost);
        c.drawCircle(s.gx, s.gy, 1.8f * dp, pDot);

        // Куда уйдёт биток после удара и где остановится.
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
        else if (cue.scratch && showCue) warn = "⚠ биток упадёт в лузу — смени силу/вариант";
        pText.setColor(col);
        labelAt(c, Math.round(s.cut) + "°" + (s.cushion >= 0 ? " борт" : "")
                + "   сила " + pct(p) + "   " + (sel + 1) + "/" + shots.size(), s.ox, s.oy, warn);
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
        if (y < 20 * dp) y = by + 1.6f * r + 14 * dp;
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
                "НАСТРОЙКА",
                "1) Жёлтые углы рамки — на края зелёного сукна (бортики)",
                "2) Белый круг — наложи на любой шар, жёлтой ручкой ● подгони размер",
                "3) Нажми ✓ на панели — готово"
        };
        float y = 28 * dp;
        for (String line : lines) {
            c.drawText(line, (getWidth() - pText.measureText(line)) / 2f, y, pText);
            y += 20 * dp;
        }
    }

    // ---------------------------------------------------------------- настройка пальцем

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
