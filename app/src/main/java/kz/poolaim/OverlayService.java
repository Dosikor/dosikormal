package kz.poolaim;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Держит все окна поверх игры: слой с линиями, кольца для ручного режима, панель; и автопоиск шаров. */
public class OverlayService extends Service
        implements AimView.Host, MarkerView.Host, PowerBar.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    static volatile boolean running;
    static final float RA = 7f; // радиус шара в пикселях сетки анализа

    private WindowManager wm;
    private SharedPreferences prefs;
    private float dp;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AimView aim;
    private WindowManager.LayoutParams aimLp;
    private boolean aimAdded;
    private MarkerView cue, tgt;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView bAuto, bFilter, bBank, bCal, bEye;
    private PowerBar powerBar;

    private final int[] aimLoc = new int[2];
    private String curKey;
    private boolean calib, hidden;

    // Автопоиск.
    private MediaProjection projection;
    private ScreenGrabber grabber;
    private HandlerThread detectThread;
    private Handler detectHandler;
    private boolean autoOn, scanning;
    private long lastFrame = -1;
    private boolean forceScan;
    private Bitmap maskBmp;
    private boolean[] prevMask;
    private final Paint maskPaint = new Paint();
    private float prevCueX = -1, prevCueY = -1;

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (!autoOn) return;
            if (!calib && grabber != null && !scanning && aim.r > 0 && aim.R - aim.L > 10) startScan();
            main.postDelayed(this, 700);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startFg(false);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("aim", MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);
        dp = getResources().getDisplayMetrics().density;
        maskPaint.setColor(0xFFFFFFFF);

        detectThread = new HandlerThread("detect");
        detectThread.start();
        detectHandler = new Handler(detectThread.getLooper());

        try {
            aim = new AimView(this, this);
            aim.dmaxTables = prefs.getFloat("dmax", 6f);
            aimLp = lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false, false);
            // Android 12+: касания проходят сквозь чужое окно, только если его прозрачность <= 0.8.
            aimLp.alpha = 0.8f;
            wm.addView(aim, aimLp);
            aimAdded = true;

            cue = new MarkerView(this, wm, lp(1, 1, true, true), 0xFFFFFFFF, this);
            tgt = new MarkerView(this, wm, lp(1, 1, true, true), 0xFFFFD600, this);

            buildPanel();
        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getIntExtra("code", 0) == Activity.RESULT_OK && aimAdded) {
            Intent data = intent.getParcelableExtra("data");
            if (data != null) startProjection(intent.getIntExtra("code", 0), data);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacks(scanTick);
        stopProjection();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
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
        if (detectThread != null) detectThread.quitSafely();
        super.onDestroy();
    }

    private void startFg(boolean withProjection) {
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
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (withProjection) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                startForeground(1, n, type);
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- захват экрана

    private void startProjection(int code, Intent data) {
        stopProjection();
        startFg(true);
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            final MediaProjection mp = mpm.getMediaProjection(code, data);
            projection = mp;
            mp.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    main.post(() -> onProjectionStopped(mp));
                }
            }, main);
            DisplayMetrics dm = realMetrics();
            grabber = new ScreenGrabber(projection, detectHandler, dm.densityDpi);
            grabber.start(dm.widthPixels, dm.heightPixels);
            setAuto(true);
            toast("Автоприцел включён");
        } catch (Throwable t) {
            grabber = null;
            MediaProjection p0 = projection;
            projection = null;
            if (p0 != null) {
                try {
                    p0.stop();
                } catch (Exception ignored) {
                }
            }
            setAuto(false);
            toast("Автоприцел недоступен — ручной режим");
        }
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private void stopProjection() {
        ScreenGrabber g = grabber;
        grabber = null;
        projection = null;
        if (g != null) g.release();
    }

    private void onProjectionStopped(MediaProjection which) {
        if (which != projection || !running) return;
        stopProjection();
        setAuto(false);
    }

    private void requestCapture() {
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .putExtra("capture", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            toast("Открой «Pool Линейка» и нажми «Запустить»");
        }
    }

    private void setAuto(boolean on) {
        boolean was = autoOn;
        autoOn = on && grabber != null;
        if (was && !autoOn) {
            // В ручной режим — ставим кольца туда, где были найденный биток и цель.
            float[] ct = aim.currentCueAndTarget();
            aim.setAuto(false);
            aim.setBalls(ct[0], ct[1], ct[2], ct[3]);
        } else {
            aim.setAuto(autoOn);
        }
        main.removeCallbacks(scanTick);
        if (autoOn) {
            remove(cue);
            remove(tgt);
            forceScan = true;
            main.post(scanTick);
        } else if (!calib && !hidden) {
            showMarkers();
        }
        if (bAuto != null) bAuto.setTextColor(autoOn ? 0xFF69F0AE : 0xFFFFFFFF);
    }

    /** Главный поток: снимаем маску наших же рисунков и отправляем задачу в поток анализа. */
    private void startScan() {
        final float L = aim.L, T = aim.T, r = aim.r;
        final float s = RA / r;
        final int w = (int) Math.ceil((aim.R - L) * s), h = (int) Math.ceil((aim.B - T) * s);
        if (w < 20 || h < 10 || (long) w * h > 600_000) return;
        int n = w * h;

        if (maskBmp == null || maskBmp.getWidth() != w || maskBmp.getHeight() != h) {
            maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            prevMask = null;
        }
        maskBmp.eraseColor(0);
        if (!hidden) {
            Canvas mc = new Canvas(maskBmp);
            mc.scale(s, s);
            mc.translate(-L, -T);
            aim.draw(mc);
            if (panel != null && panel.isAttachedToWindow()) {
                int[] pl = new int[2];
                panel.getLocationOnScreen(pl);
                float px = pl[0] - aimLoc[0], py = pl[1] - aimLoc[1];
                mc.drawRect(px, py, px + panel.getWidth(), py + panel.getHeight(), maskPaint);
            }
        }
        int[] mp = new int[n];
        maskBmp.getPixels(mp, 0, w, 0, 0, w, h);
        boolean[] cur = new boolean[n];
        for (int i = 0; i < n; i++) cur[i] = (mp[i] >>> 24) > 8;
        final boolean[] unk = new boolean[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                boolean v = cur[i] || (prevMask != null && prevMask[i]);
                if (!v) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx >= 0 && xx < w) unk[yy * w + xx] = true;
                    }
                }
            }
        }
        prevMask = cur;

        final int offX = aimLoc[0], offY = aimLoc[1];
        final float[] pk = Geo.pockets(L, T, aim.R, aim.B);
        final float pcx = prevCueX, pcy = prevCueY;
        final ScreenGrabber g = grabber;
        final boolean force = forceScan;
        forceScan = false;
        scanning = true;
        detectHandler.post(() -> {
            List<Detector.Blob> res = null;
            int cueIdx = -1;
            try {
                if (g.grab() && (force || g.frameCount() != lastFrame)) {
                    lastFrame = g.frameCount();
                    int[] rgb = new int[n];
                    for (int j = 0; j < h; j++) {
                        float sy = T + (j + 0.5f) / s + offY;
                        for (int i = 0; i < w; i++) {
                            rgb[j * w + i] = g.pixelAtScreen(L + (i + 0.5f) / s + offX, sy);
                        }
                    }
                    List<Detector.Blob> found = Detector.detect(rgb, w, h, unk, RA);
                    res = new ArrayList<>();
                    for (Detector.Blob b : found) {
                        b.x = L + (b.x + 0.5f) / s;
                        b.y = T + (b.y + 0.5f) / s;
                        boolean inPocket = false;
                        for (int q = 0; q < 6; q++) {
                            float lim = (q == 1 || q == 4) ? 2.0f * r : 2.5f * r;
                            if (Math.hypot(b.x - pk[2 * q], b.y - pk[2 * q + 1]) < lim) inPocket = true;
                        }
                        if (!inPocket) res.add(b);
                    }
                    cueIdx = Detector.classify(res, pcx, pcy, 4f * r);
                }
            } catch (Throwable ignored) {
                res = null;
            }
            final List<Detector.Blob> fr = res;
            final int fc = cueIdx;
            main.post(() -> onDetected(fr, fc));
        });
    }

    private void onDetected(List<Detector.Blob> found, int cueIdx) {
        scanning = false;
        if (found == null || !autoOn || calib) return;
        // Сглаживание: если шар сдвинулся совсем чуть-чуть — оставляем прежнюю точку, чтобы линии не дрожали.
        List<Geo.Ball> old = aim.getBalls();
        List<Geo.Ball> list = new ArrayList<>();
        float r = aim.r;
        for (Detector.Blob b : found) {
            float x = b.x, y = b.y;
            for (Geo.Ball o : old) {
                if (Math.hypot(o.x - x, o.y - y) < 0.35f * r) {
                    x = o.x;
                    y = o.y;
                    break;
                }
            }
            list.add(new Geo.Ball(x, y, b.type));
        }
        if (cueIdx >= 0) {
            prevCueX = list.get(cueIdx).x;
            prevCueY = list.get(cueIdx).y;
        }
        aim.setDetected(list, cueIdx);
    }

    // ---------------------------------------------------------------- стол и маркеры

    @Override
    public void onAimLayout(int w, int h) {
        if (w <= 0 || h <= 0) return;
        aim.getLocationOnScreen(aimLoc);
        String key = w > h ? "land" : "port";
        if (grabber != null) {
            DisplayMetrics dm = realMetrics();
            grabber.resize(dm.widthPixels, dm.heightPixels);
            forceScan = true;
        }
        if (!key.equals(curKey)) {
            curKey = key;
            loadCal(w, h);
            prevCueX = -1;
            prevCueY = -1;
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
        if (!calib && !hidden && !autoOn) {
            place(cue, ccx, ccy);
            place(tgt, tcx, tcy);
        }
    }

    private void showMarkers() {
        cue.setBallRadius(aim.r);
        tgt.setBallRadius(aim.r);
        place(cue, aim.cx, aim.cy);
        place(tgt, aim.ox, aim.oy);
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

    @Override
    public void onPowerShown(float power, boolean auto) {
        if (powerBar != null) powerBar.setShown(power, auto);
    }

    @Override
    public void onPower(boolean auto, float manual) {
        aim.setPower(auto, manual);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if ("dmax".equals(key) && aim != null) aim.setDmaxTables(sp.getFloat("dmax", 6f));
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
            prevMask = null;
            forceScan = true;
            if (!autoOn) showMarkers();
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
        } else if (!autoOn) {
            showMarkers();
        }
        bEye.setAlpha(hidden ? 0.4f : 1f);
    }

    private void cycleFilter() {
        int f = (aim.filter + 1) % 3;
        aim.setFilter(f);
        bFilter.setText(f == Geo.FILTER_ALL ? "В" : f == Geo.FILTER_SOLIDS ? "●" : "◐");
        toast(f == Geo.FILTER_ALL ? "Цель: все шары" : f == Geo.FILTER_SOLIDS ? "Цель: сплошные" : "Цель: полосатые");
    }

    // ---------------------------------------------------------------- панель

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC202020);
        bg.setCornerRadius(14 * dp);
        panel.setBackground(bg);
        int pad = (int) (3 * dp);
        panel.setPadding(pad, pad, pad, pad);

        LinearLayout c1 = column(), c2 = column();
        TextView handle = btn(c1, "✥", null);
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
        bAuto = btn(c1, "🎯", v -> {
            if (grabber == null) requestCapture();
            else {
                setAuto(!autoOn);
                toast(autoOn ? "Автоприцел включён" : "Ручной режим: двигай кольца сам");
            }
        });
        btn(c1, "⟳", v -> aim.nextShot());
        bFilter = btn(c1, "В", v -> cycleFilter());
        bBank = btn(c2, "Б", v -> {
            aim.setBanks(!aim.banks);
            bBank.setTextColor(aim.banks ? 0xFF69F0AE : 0xFFFFFFFF);
        });
        bCal = btn(c2, "⚙", v -> toggleCalib());
        bEye = btn(c2, "👁", v -> toggleHidden());
        btn(c2, "✕", v -> stopSelf());
        panel.addView(c1);
        panel.addView(c2);

        powerBar = new PowerBar(this, this);
        powerBar.setLayoutParams(new LinearLayout.LayoutParams((int) (34 * dp), LinearLayout.LayoutParams.MATCH_PARENT));
        panel.addView(powerBar);

        panelLp = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true, false);
        panelLp.x = prefs.getInt("panelX", (int) (8 * dp));
        panelLp.y = prefs.getInt("panelY", (int) (60 * dp));
        wm.addView(panel, panelLp);
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private TextView btn(LinearLayout parent, String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tv.setGravity(Gravity.CENTER);
        int s = (int) (40 * dp);
        tv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        if (l != null) tv.setOnClickListener(l);
        parent.addView(tv);
        return tv;
    }
}
