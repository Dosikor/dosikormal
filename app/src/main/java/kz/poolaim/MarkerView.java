package kz.poolaim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/** Маленькое отдельное окно-кольцо, которое ставится на шар. Его можно таскать пальцем, а всё остальное
 *  касание идёт в игру. */
public class MarkerView extends View {

    interface Host {
        void onMarkerMoved(MarkerView m);

        void onMarkerTap(MarkerView m);
    }

    final WindowManager wm;
    final WindowManager.LayoutParams lp;
    private final Host host;
    private final float dp;
    private final float slop;
    private final Paint ring, thin, area;

    int size;
    float r;
    float corrX, corrY; // поправка: реальное положение окна на экране минус lp.x/lp.y
    boolean added;

    private float downX, downY;
    private int startX, startY;
    private boolean moved;

    private final Runnable measure = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;
            int[] l = new int[2];
            getLocationOnScreen(l);
            float nx = l[0] - lp.x, ny = l[1] - lp.y;
            if (Math.abs(nx) < 400 * dp && Math.abs(ny) < 400 * dp) {
                corrX = nx;
                corrY = ny;
            }
            host.onMarkerMoved(MarkerView.this);
        }
    };

    public MarkerView(Context c, WindowManager wm, WindowManager.LayoutParams lp, int color, Host host) {
        super(c);
        this.wm = wm;
        this.lp = lp;
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();

        ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.2f * dp);
        ring.setColor(color);

        thin = new Paint(Paint.ANTI_ALIAS_FLAG);
        thin.setStyle(Paint.Style.STROKE);
        thin.setStrokeWidth(1f * dp);
        thin.setColor(color);

        area = new Paint(Paint.ANTI_ALIAS_FLAG);
        area.setStyle(Paint.Style.STROKE);
        area.setStrokeWidth(1f * dp);
        area.setColor((color & 0x00FFFFFF) | 0x66000000);
        area.setPathEffect(new DashPathEffect(new float[]{4 * dp, 4 * dp}, 0));

        setBallRadius(12 * dp);
    }

    void setBallRadius(float rad) {
        r = rad;
        size = (int) Math.max(56 * dp, 2 * rad + 30 * dp);
        lp.width = size;
        lp.height = size;
        invalidate();
    }

    /** Центр кольца в координатах экрана. */
    float centerX() {
        return lp.x + corrX + size / 2f;
    }

    float centerY() {
        return lp.y + corrY + size / 2f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float m = size / 2f;
        c.drawCircle(m, m, m - dp, area);
        c.drawCircle(m, m, r, ring);
        float k = Math.max(3 * dp, r * 0.45f);
        c.drawLine(m - k, m, m + k, m, thin);
        c.drawLine(m, m - k, m, m + k, thin);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                moved = false;
                removeCallbacks(measure);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!moved && dx * dx + dy * dy > slop * slop) moved = true;
                if (moved) {
                    lp.x = startX + Math.round(dx);
                    lp.y = startY + Math.round(dy);
                    try {
                        wm.updateViewLayout(this, lp);
                    } catch (Exception ignored) {
                    }
                    host.onMarkerMoved(this);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) {
                    performClick();
                    host.onMarkerTap(this);
                } else {
                    postDelayed(measure, 250);
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(measure, 300);
    }
}
