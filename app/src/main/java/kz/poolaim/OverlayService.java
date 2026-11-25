package kz.poolaim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Держит все окна поверх игры: слой с линиями, два кольца-маркера и панель кнопок. */
public class OverlayService extends Service implements AimView.Host, MarkerView.Host {

    static volatile boolean running;

    private WindowManager wm;
    private SharedPreferences prefs;
    private float dp;

    private AimView aim;
    private WindowManager.LayoutParams aimLp;
    private boolean aimAdded;
    private MarkerView cue, tgt;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView bBank, bCal, bEye;

    private final int[] aimLoc = new int[2];
    private String curKey;
    private boolean calib, hidden;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startFg();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("aim", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;

        try {
            aim = new AimView(this, this);
            aimLp = lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false, false);
            // Android 12+: касания проходят сквозь чужое окно, только если его прозрачность <= 0.8.
            aimLp.alpha = 0.8f;
            wm.addView(aim, aimLp);
            aimAdded = true;

            cue = new MarkerView(this, wm, lp(1, 1, true, true), 0xFFFFFFFF, this);
            tgt = new MarkerView(this, wm, lp(1, 1, true, true), 0xFFFFD600, this);

            buildPanel();
        } catch (Exception e) {
            // Нет разрешения на показ поверх окон.
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (cue != null) remove(cue);
        if (tgt != null) remove(tgt);
        if (aimAdded) {
            try {
                wm.removeView(aim);
            } catch (Exception ignored) {
            }
        }
        if (panel != null && panel.isAttachedToWindow()) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private void startFg() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("aim", "Линейка", NotificationManager.IMPORTANCE_LOW));
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "aim")
                    .setContentTitle("Pool Линейка работает")
                    .setContentText("Нажми, чтобы открыть настройки")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (Throwable ignored) {
            // Без уведомления тоже работает, просто система может выгрузить раньше.
        }
    }

    private WindowManager.LayoutParams lp(int w, int h, boolean touchable, boolean noLimits) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (noLimits) flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    // ---------------------------------------------------------------- стол и маркеры

    @Override
    public void onAimLayout(int w, int h) {
        if (w <= 0 || h <= 0) return;
        aim.getLocationOnScreen(aimLoc);
        String key = w > h ? "land" : "port";
        if (!key.equals(curKey)) {
            curKey = key;
            loadCal(w, h);
            float tw = aim.R - aim.L, th = aim.B - aim.T;
            setMarkers(aim.L + 0.25f * tw, (aim.T + aim.B) / 2f, aim.L + 0.62f * tw, aim.T + 0.35f * th);
        } else {
            syncMarkers();
        }
    }

    private void loadCal(int w, int h) {
        String k = curKey;
        float L = prefs.getFloat(k + "L", -1f);
        float T, R, B, r;
        if (L < 0) {
            float tw = w * 0.66f, th = tw / 2f;
            if (th > h * 0.7f) {
                th = h * 0.7f;
                tw = th * 2f;
            }
            L = (w - tw) / 2f;
            T = (h - th) / 2f + h * 0.05f;
            R = L + tw;
            B = T + th;
            r = tw / 58f;
        } else {
            T = prefs.getFloat(k + "T", 0);
            R = prefs.getFloat(k + "R", 100);
            B = prefs.getFloat(k + "B", 50);
            r = prefs.getFloat(k + "r", 10);
        }
        aim.setTable(L, T, R, B, r);
    }

    private void saveCal() {
        if (curKey == null) return;
        prefs.edit()
                .putFloat(curKey + "L", aim.L)
                .putFloat(curKey + "T", aim.T)
                .putFloat(curKey + "R", aim.R)
                .putFloat(curKey + "B", aim.B)
                .putFloat(curKey + "r", aim.r)
                .apply();
    }

    private void setMarkers(float ccx, float ccy, float tcx, float tcy) {
        cue.setBallRadius(aim.r);
        tgt.setBallRadius(aim.r);
        aim.setBalls(ccx, ccy, tcx, tcy);
        if (!calib && !hidden) {
            place(cue, ccx, ccy);
            place(tgt, tcx, tcy);
        }
    }

    /** Ставит кольцо так, чтобы его центр был в точке (x,y) слоя с линиями. */
    private void place(MarkerView m, float x, float y) {
        m.lp.width = m.size;
        m.lp.height = m.size;
        m.lp.x = Math.round(x + aimLoc[0] - m.corrX - m.size / 2f);
        m.lp.y = Math.round(y + aimLoc[1] - m.corrY - m.size / 2f);
        try {
            if (m.added) {
                wm.updateViewLayout(m, m.lp);
            } else {
                wm.addView(m, m.lp);
                m.added = true;
            }
        } catch (Exception ignored) {
        }
    }

    private void remove(MarkerView m) {
        if (!m.added) return;
        try {
            wm.removeView(m);
        } catch (Exception ignored) {
        }
        m.added = false;
    }

    private void syncMarkers() {
        if (cue == null || tgt == null || !cue.added || !tgt.added) return;
        aim.setBalls(cue.centerX() - aimLoc[0], cue.centerY() - aimLoc[1],
                tgt.centerX() - aimLoc[0], tgt.centerY() - aimLoc[1]);
    }

    @Override
    public void onMarkerMoved(MarkerView m) {
        syncMarkers();
    }

    @Override
    public void onMarkerTap(MarkerView m) {
        if (m == tgt) aim.nextShot();
        else aim.toggleCuePath();
    }

    @Override
    public void onCalibEdited() {
        saveCal();
    }

    // ---------------------------------------------------------------- режимы

    private void toggleCalib() {
        if (hidden) toggleHidden();
        calib = !calib;
        if (calib) {
            remove(cue);
            remove(tgt);
            aimLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            aimLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            saveCal();
        }
        try {
            wm.updateViewLayout(aim, aimLp);
        } catch (Exception ignored) {
        }
        aim.setCalib(calib);
        if (!calib) {
            cue.setBallRadius(aim.r);
            tgt.setBallRadius(aim.r);
            place(cue, aim.cx, aim.cy);
            place(tgt, aim.ox, aim.oy);
        }
        bCal.setText(calib ? "✓" : "⚙");
        bCal.setTextColor(calib ? 0xFF69F0AE : 0xFFFFFFFF);
    }

    private void toggleHidden() {
        if (!hidden && calib) toggleCalib();
        hidden = !hidden;
        aim.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        if (hidden) {
            remove(cue);
            remove(tgt);
        } else {
            place(cue, aim.cx, aim.cy);
            place(tgt, aim.ox, aim.oy);
        }
        bEye.setAlpha(hidden ? 0.4f : 1f);
    }

    // ---------------------------------------------------------------- панель

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC202020);
        bg.setCornerRadius(14 * dp);
        panel.setBackground(bg);
        int pad = (int) (3 * dp);
        panel.setPadding(pad, pad, pad, pad);

        TextView handle = btn("✥", null);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float rx, ry;
            int x0, y0;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        rx = e.getRawX();
                        ry = e.getRawY();
                        x0 = panelLp.x;
                        y0 = panelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = x0 + Math.round(e.getRawX() - rx);
                        panelLp.y = y0 + Math.round(e.getRawY() - ry);
                        try {
                            wm.updateViewLayout(panel, panelLp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        prefs.edit().putInt("panelX", panelLp.x).putInt("panelY", panelLp.y).apply();
                        return true;
                    default:
                        return true;
                }
            }
        });
        btn("⟳", v -> aim.nextShot());
        bBank = btn("Б", v -> {
            aim.setBanks(!aim.banks);
            bBank.setTextColor(aim.banks ? 0xFF69F0AE : 0xFFFFFFFF);
        });
        bCal = btn("⚙", v -> toggleCalib());
        bEye = btn("👁", v -> toggleHidden());
        btn("✕", v -> stopSelf());

        panelLp = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true, false);
        panelLp.x = prefs.getInt("panelX", (int) (8 * dp));
        panelLp.y = prefs.getInt("panelY", (int) (60 * dp));
        wm.addView(panel, panelLp);
    }

    private TextView btn(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tv.setGravity(Gravity.CENTER);
        int s = (int) (40 * dp);
        tv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        if (l != null) tv.setOnClickListener(l);
        panel.addView(tv);
        return tv;
    }
}
