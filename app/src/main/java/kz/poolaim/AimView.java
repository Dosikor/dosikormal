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
    }

    private final Host host;
    private final float dp;

    // Стол (внутренние края бортов) и радиус шара, в пикселях этого окна.
    float L, T, R, B, r;
    // Центры битка (c) и прицельного шара (o).
    float cx, cy, ox, oy;
    boolean ready;
    boolean banks, calib, showCue = true;
    // Пробный шар для настройки размера.
    float sx, sy;

    private List<Geo.Shot> shots = new ArrayList<>();
    private int sel = 0;
    private String selKey = null; // null — автоматически лучший удар

    private final Paint pTable, pPocket, pObj, pAim, pGhost, pDot, pCue, pText, pHandle, pCal;
    private final Path path = new Path();

    private int drag = -1;
    private float lastX, lastY;

    public AimView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        pTable = stroke(0x55FFFFFF, 1.2f);
        pPocket = stroke(0x88FFFFFF, 1.2f);
        pObj = stroke(0xFF00E676, 3f);
        pAim = stroke(0xFFFFFFFF, 1.8f);
        pGhost = stroke(0xFFFFFFFF, 2.2f);
        pDot = fill(0xFFFFFFFF);
        pCue = stroke(0xFF40C4FF, 2f);
        pCue.setPathEffect(new DashPathEffect(new float[]{10 * dp, 6 * dp}, 0));
        pText = fill(0xFFFFFFFF);
        pText.setTextSize(14 * dp);
        pText.setFakeBoldText(true);
        pText.setShadowLayer(3 * dp, 0, 0, 0xFF000000);
        pHandle = fill(0xCCFFC107);
        pCal = stroke(0xFFFFC107, 2f);
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
        recompute();
    }

    void setBanks(boolean on) {
        banks = on;
        selKey = null;
        recompute();
    }

    void setCalib(boolean on) {
        calib = on;
        drag = -1;
        recompute();
    }

    void toggleCuePath() {
        showCue = !showCue;
        invalidate();
    }

    void nextShot() {
        if (shots.isEmpty()) return;
        sel = (sel + 1) % shots.size();
        selKey = sel == 0 ? null : shots.get(sel).key();
        invalidate();
    }

    void recompute() {
        if (!ready || R - L < 10 || B - T < 10) {
            shots = new ArrayList<>();
        } else {
            shots = Geo.compute(L, T, R, B, r, cx, cy, ox, oy, banks);
        }
        sel = 0;
        if (selKey != null) {
            int idx = -1;
            for (int i = 0; i < shots.size(); i++) {
                if (shots.get(i).key().equals(selKey)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) sel = idx;
            else selKey = null;
        }
        invalidate();
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
        if (!ready) return;

        if (shots.isEmpty()) {
            pText.setColor(0xFFFF5252);
            label(c, banks ? "нет удара" : "нет прямого удара — нажми Б");
            return;
        }
        Geo.Shot s = shots.get(sel);

        // Куда покатится прицельный шар.
        path.reset();
        path.moveTo(ox, oy);
        if (s.cushion >= 0) path.lineTo(s.kx, s.ky);
        path.lineTo(s.px, s.py);
        c.drawPath(path, pObj);
        c.drawCircle(s.px, s.py, r * 1.1f, pObj);

        // Линия прицела: от битка к «призрачному шару».
        c.drawLine(cx, cy, s.gx, s.gy, pAim);
        c.drawCircle(s.gx, s.gy, r, pGhost);
        c.drawCircle(s.gx, s.gy, 1.8f * dp, pDot);

        // Куда уйдёт биток после удара.
        if (showCue && s.cuePath != null && s.cuePath.length >= 4) {
            path.reset();
            path.moveTo(s.cuePath[0], s.cuePath[1]);
            for (int i = 2; i + 1 < s.cuePath.length; i += 2) path.lineTo(s.cuePath[i], s.cuePath[i + 1]);
            c.drawPath(path, pCue);
        }

        pText.setColor(s.cut < 30 ? 0xFF69F0AE : s.cut < 60 ? 0xFFFFD740 : 0xFFFF5252);
        label(c, Math.round(s.cut) + "°" + (s.cushion >= 0 ? " борт" : "") + "  " + (sel + 1) + "/" + shots.size());
    }

    private void label(Canvas c, String t) {
        float w = pText.measureText(t);
        float x = ox + 1.6f * r;
        if (x + w > getWidth() - 4 * dp) x = ox - 1.6f * r - w;
        float y = oy - 1.6f * r;
        if (y < 20 * dp) y = oy + 1.6f * r + 14 * dp;
        c.drawText(t, x, y, pText);
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

        // Пробный шар + ручка размера.
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
