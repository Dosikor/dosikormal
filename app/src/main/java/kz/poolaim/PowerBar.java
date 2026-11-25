package kz.poolaim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Шкала силы. Тап — авто/ручной. Протяжка вверх-вниз — задать силу вручную.
 * В авторежиме показывает рекомендуемую силу для выбранного удара.
 */
public class PowerBar extends View {

    interface Listener {
        void onPower(boolean auto, float manual);
    }

    private final Listener listener;
    private final float dp, slop;
    private final Paint track, fillAuto, fillManual, text, small;
    private final RectF rect = new RectF();

    private float value = 0f;
    private boolean auto = true;
    private float manual = 0.5f;
    private float downY;
    private boolean moved;

    public PowerBar(Context c, Listener l) {
        super(c);
        listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setColor(0xFF3A3A3A);
        fillAuto = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillAuto.setColor(0xFF40C4FF);
        fillManual = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillManual.setColor(0xFFFFAB40);
        text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(12 * dp);
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(2 * dp, 0, 0, 0xFF000000);
        small = new Paint(text);
        small.setTextSize(9 * dp);
    }

    void setShown(float v, boolean isAuto) {
        value = Math.max(0f, Math.min(1f, v));
        auto = isAuto;
        invalidate();
    }

    private float barTop() {
        return 18 * dp;
    }

    private float barBottom() {
        return getHeight() - 14 * dp;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float top = barTop(), bot = barBottom();
        float pad = 6 * dp;
        rect.set(pad, top, w - pad, bot);
        c.drawRoundRect(rect, 6 * dp, 6 * dp, track);
        float fy = bot - (bot - top) * value;
        rect.set(pad, fy, w - pad, bot);
        c.drawRoundRect(rect, 6 * dp, 6 * dp, auto ? fillAuto : fillManual);
        c.drawText(Math.round(value * 100) + "", w / 2f, 13 * dp, text);
        c.drawText(auto ? "АВТО" : "РУЧН", w / 2f, getHeight() - 3 * dp, small);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.abs(e.getY() - downY) > slop) moved = true;
                if (moved) {
                    float top = barTop(), bot = barBottom();
                    manual = Math.max(0.02f, Math.min(1f, (bot - e.getY()) / (bot - top)));
                    auto = false;
                    value = manual;
                    invalidate();
                    listener.onPower(false, manual);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) {
                    performClick();
                    auto = !auto;
                    listener.onPower(auto, manual);
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
}
