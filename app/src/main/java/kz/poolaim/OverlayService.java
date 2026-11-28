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
import android.os.SystemClock;
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

/**
 * Всё автоматически: по записи экрана находит стол (края, размер шара), затем каждые ~0.7 с — шары и биток,
 * а слой AimView рисует удар для каждого шара. Вручную ничего ставить не нужно.
 */
public class OverlayService extends Service
        implements AimView.Host, PowerBar.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    static volatile boolean running;
    static final float RA = 7f;       // радиус шара в пикселях сетки анализа шаров
    static final int TABLE_STEP = 2;  // шаг сетки поиска стола, пикселей экрана

    private static final String NEED_CAPTURE = "Нажми 🎯 и разреши запись экрана («Весь экран»)";

    private WindowManager wm;
    private SharedPreferences prefs;
    private float dp;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AimView aim;
    private WindowManager.LayoutParams aimLp;
    private boolean aimAdded;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView bFilter, bBank, bCal, bEye, bSugg;
    private PowerBar powerBar;

    private final int[] aimLoc = new int[2];
    private String curKey;
    private boolean calib, hidden;

    // Запись экрана и анализ.
    private MediaProjection projection;
    private ScreenGrabber grabber;
    private HandlerThread detectThread;
    private Handler detectHandler;
    private boolean scanning;
    private long lastFrame = -1;
    private boolean forceScan;
    private Bitmap maskBmp;
    private boolean[] prevMask;
    private final Paint maskPaint = new Paint();
    private float prevCueX = -1, prevCueY = -1;
    private final Tracker tracker = new Tracker();

    // Линия прицела игры.
    private boolean guideBusy;
    private int guideMiss;
    private float guideAng = Float.NaN;

    private final Runnable guideTick = new Runnable() {
        @Override
        public void run() {
            if (grabber == null) return;
            if (!calib && !hidden && !guideBusy && calReady) startGuideScan();
            main.postDelayed(this, 150);
        }
    };
    private int noTableCount;
    private int calmScans = 2;

    // Стол.
    private boolean calReady, manualCal;
    private int feltCb = -1, feltCr = -1, feltY = -1;
    private float[] cand;
    private int candHits;
    private long lastTableScan;

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (grabber == null) return;
            if (!calib && !scanning) {
                long now = SystemClock.uptimeMillis();
                if (!calReady || (!manualCal && noTableCount >= 6 && now - lastTableScan > 3000)) startTableScan();
                else startScan();
            }
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
            aimLp = lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false);
            // Android 12+: касания проходят сквозь чужое окно, только если его прозрачность <= 0.8.
            aimLp.alpha = 0.8f;
            wm.addView(aim, aimLp);
            aimAdded = true;
            aim.setStatus(NEED_CAPTURE);
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
        main.removeCallbacks(guideTick);
        stopProjection();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
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
        }
    }

    private WindowManager.LayoutParams lp(int w, int h, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
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

    // ---------------------------------------------------------------- запись экрана

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
            grabber = new ScreenGrabber(mp, detectHandler, dm.densityDpi);
            grabber.start(dm.widthPixels, dm.heightPixels);
            aim.setStatus(calReady ? "Ищу шары…" : "Ищу стол…");
            lastTableScan = 0;
            forceScan = true;
            main.removeCallbacks(scanTick);
            main.post(scanTick);
            main.removeCallbacks(guideTick);
            main.postDelayed(guideTick, 300);
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
            aim.setStatus("Запись экрана не включилась (" + t.getClass().getSimpleName() + ") — нажми 🎯");
            toast("Не удалось включить запись экрана: " + t.getClass().getSimpleName());
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
        main.removeCallbacks(scanTick);
        main.removeCallbacks(guideTick);
        aim.setGuide(Float.NaN);
        aim.setDetected(new ArrayList<>(), -1);
        aim.setStatus("Запись экрана остановлена — нажми 🎯");
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

    /** 🎯: заново найти стол и шары (или попросить запись экрана). */
    private void restartAuto() {
        if (grabber == null) {
            requestCapture();
            return;
        }
        manualCal = false;
        calReady = false;
        cand = null;
        if (curKey != null) prefs.edit().remove("c3_" + curKey + "_manual").apply();
        aim.clearTable();
        tracker.clear();
        aim.setStatus("Ищу стол и шары…");
        lastTableScan = 0;
        forceScan = true;
        toast("Ищу стол и шары заново");
    }

    // ---------------------------------------------------------------- поиск стола

    private void startTableScan() {
        final ScreenGrabber g = grabber;
        if (g == null) return;
        scanning = true;
        lastTableScan = SystemClock.uptimeMillis();
        final int offX = aimLoc[0], offY = aimLoc[1];
        detectHandler.post(() -> {
            TableFinder.Table t = null;
            try {
                if (g.grab()) {
                    int gw = g.realW() / TABLE_STEP, gh = g.realH() / TABLE_STEP;
                    int[] rgb = new int[gw * gh];
                    for (int j = 0; j < gh; j++)
                        for (int i = 0; i < gw; i++)
                            rgb[j * gw + i] = g.pixelAtScreen((i + 0.5f) * TABLE_STEP, (j + 0.5f) * TABLE_STEP);
                    t = TableFinder.find(rgb, gw, gh);
                }
            } catch (Throwable ignored) {
                t = null;
            }
            final TableFinder.Table ft = t;
            main.post(() -> onTableFound(ft, offX, offY));
        });
    }

    private static boolean similar(float[] a, float[] b, float tol) {
        float w = Math.max(1f, a[2] - a[0]);
        for (int i = 0; i < 4; i++) if (Math.abs(a[i] - b[i]) > tol * w) return false;
        return true;
    }

    private void onTableFound(TableFinder.Table t, int offX, int offY) {
        scanning = false;
        if (grabber == null) return;
        if (t == null) {
            cand = null;
            if (!calReady) aim.setStatus("Ищу стол… (открой игру — стол должен быть виден)");
            return;
        }
        float[] nt = {t.L * TABLE_STEP - offX, t.T * TABLE_STEP - offY,
                t.R * TABLE_STEP - offX, t.B * TABLE_STEP - offY, t.r * TABLE_STEP};
        if (calReady && similar(new float[]{aim.L, aim.T, aim.R, aim.B, aim.r}, nt, 0.02f)) {
            cand = null; // стол на месте
            if (feltCb < 0 || feltY < 0) {
                feltCb = t.feltCb;
                feltCr = t.feltCr;
                feltY = t.feltY;
                saveCal(manualCal);
            }
            return;
        }
        if (cand != null && similar(cand, nt, 0.015f)) candHits++;
        else {
            cand = nt;
            candHits = 1;
        }
        if (candHits < 2) {
            if (!calReady) aim.setStatus("Нашёл стол, проверяю…");
            forceScan = true;
            lastTableScan = 0; // сразу ещё раз для подтверждения
            return;
        }
        float[] cur = {aim.L, aim.T, aim.R, aim.B, aim.r};
        // Найденный стол не трогаем, пока он виден: меняем только если стол пропадал (другой экран/стол).
        if (!calReady || (noTableCount >= 6 && !similar(cur, nt, 0.02f))) {
            float[] avg = new float[5];
            for (int i = 0; i < 5; i++) avg[i] = (cand[i] + nt[i]) / 2f;
            aim.setTable(avg[0], avg[1], avg[2], avg[3], avg[4]);
            tracker.clear();
            feltCb = t.feltCb;
            feltCr = t.feltCr;
            feltY = t.feltY;
            calReady = true;
            manualCal = false;
            saveCal(false);
            prevMask = null;
            prevCueX = -1;
            aim.setStatus("Стол найден — ищу шары…");
        }
        cand = null;
        forceScan = true;
    }

    // ---------------------------------------------------------------- линия прицела игры

    /** Берём кусок экрана вокруг битка (в половинном разрешении) и ищем белую линию прицела игры. */
    private void startGuideScan() {
        final float cx = aim.cueX(), cy = aim.cueY();
        if (Float.isNaN(cx) || aim.r <= 0) {
            if (!Float.isNaN(guideAng)) {
                guideAng = Float.NaN;
                aim.setGuide(Float.NaN);
            }
            return;
        }
        final float r = aim.r;
        final float step = 2f;
        final int half = (int) Math.ceil(6.6f * r / step) + 4;
        final int size = 2 * half;
        final float sx0 = cx + aimLoc[0] - half * step, sy0 = cy + aimLoc[1] - half * step;
        final float[] others = aim.otherBalls();
        final float pr = r / step, pr0 = (r + 5 * dp) / step;
        final float pcx = (cx + aimLoc[0] - sx0) / step - 0.5f, pcy = (cy + aimLoc[1] - sy0) / step - 0.5f;
        final float[] pb = new float[others.length];
        for (int i = 0; i + 1 < others.length; i += 2) {
            pb[i] = (others[i] + aimLoc[0] - sx0) / step - 0.5f;
            pb[i + 1] = (others[i + 1] + aimLoc[1] - sy0) / step - 0.5f;
        }
        final ScreenGrabber g = grabber;
        guideBusy = true;
        detectHandler.post(() -> {
            float ang = Float.NaN;
            try {
                if (g.grab()) {
                    int[] rgb = new int[size * size];
                    for (int j = 0; j < size; j++) {
                        float sy = sy0 + (j + 0.5f) * step;
                        for (int i = 0; i < size; i++) rgb[j * size + i] = g.pixelAtScreen(sx0 + (i + 0.5f) * step, sy);
                    }
                    ang = GuideFinder.find(rgb, size, size, pcx, pcy, pr, pr0, pb, -1);
                }
            } catch (Throwable ignored) {
                ang = Float.NaN;
            }
            final float fa = ang;
            main.post(() -> onGuide(fa));
        });
    }

    private void onGuide(float ang) {
        guideBusy = false;
        if (grabber == null) return;
        if (Float.isNaN(ang)) {
            // Пропуски не гасят линию сразу — только если линии игры нет ~2 секунды.
            if (++guideMiss >= 13 && !Float.isNaN(guideAng)) {
                guideAng = Float.NaN;
                aim.setGuide(Float.NaN);
            }
            return;
        }
        guideMiss = 0;
        if (!Float.isNaN(guideAng)) {
            double d = Math.atan2(Math.sin(ang - guideAng), Math.cos(ang - guideAng));
            if (Math.abs(d) < Math.toRadians(0.2)) return; // дрожание — не трогаем
            // Сглаживание: большой поворот кия — сразу, мелкий — половиной шага.
            ang = (float) (guideAng + (Math.abs(d) > Math.toRadians(3) ? d : 0.5 * d));
        }
        guideAng = ang;
        aim.setGuide(ang);
    }

    // ---------------------------------------------------------------- поиск шаров

    /** Главный поток: снимаем маску наших же рисунков и отправляем задачу в поток анализа. */
    private void startScan() {
        final float L = aim.L, T = aim.T, r = aim.r;
        if (r <= 0) return;
        final float s = RA / r;
        final int w = (int) Math.ceil((aim.R - L) * s), h = (int) Math.ceil((aim.B - T) * s);
        if (w < 20 || h < 10 || (long) w * h > 600_000) return;
        final int n = w * h;

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
                if (!(cur[i] || (prevMask != null && prevMask[i]))) continue;
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
        final int fCb = feltCb, fCr = feltCr, fY = feltY;
        final ScreenGrabber g = grabber;
        final boolean force = forceScan;
        forceScan = false;
        scanning = true;
        detectHandler.post(() -> {
            List<Detector.Blob> res = null;
            int cueIdx = -1;
            boolean noTable = false;
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
                    if (fCb >= 0 && fY >= 0 && TableFinder.feltFraction(rgb, n, fCb, fCr, fY) < 0.35f) {
                        noTable = true;
                    } else {
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
                }
            } catch (Throwable ignored) {
                res = null;
            }
            final List<Detector.Blob> fr = res;
            final int fc = cueIdx;
            final boolean fn = noTable;
            main.post(() -> onDetected(fr, fc, fn));
        });
    }

    private void onDetected(List<Detector.Blob> found, int cueIdx, boolean noTable) {
        scanning = false;
        if (grabber == null || calib) return;
        if (noTable) {
            // Всплывашки и анимации игры не стирают линии — только если стола нет ~4 секунды.
            if (++noTableCount >= 6) {
                tracker.clear();
                aim.setDetected(new ArrayList<>(), -1);
                aim.setStatus("Стол не виден");
                lastTableScan = 0; // проверим, не сменился ли стол
            }
            return;
        }
        if (found == null) return;
        if (noTableCount >= 6) aim.setStatus(null);
        noTableCount = 0;
        // Трекинг: шар держится, пока его не видно 3 кадра подряд; мелкое дрожание гасится.
        tracker.update(found, cueIdx, aim.r);
        int[] co = new int[1];
        List<Geo.Ball> list = tracker.visible(co);
        cueIdx = co[0];
        // Шары катятся — подсказки бледнеют и спокойно ждут остановки; 2 спокойные проверки — снова ярко.
        if (tracker.isMoving()) calmScans = 0;
        else calmScans++;
        aim.setMoving(calmScans < 2);
        if (cueIdx >= 0) {
            prevCueX = list.get(cueIdx).x;
            prevCueY = list.get(cueIdx).y;
        }
        aim.setDetected(list, cueIdx);
        // Строка сверху — только когда что-то не так (чтобы ничего не мелькало).
        aim.setStatus(cueIdx < 0 ? "Биток не найден" : null);
    }

    // ---------------------------------------------------------------- стол: хранение

    @Override
    public void onAimLayout(int w, int h) {
        if (w <= 0 || h <= 0) return;
        aim.getLocationOnScreen(aimLoc);
        if (grabber != null) {
            DisplayMetrics dm = realMetrics();
            grabber.resize(dm.widthPixels, dm.heightPixels);
            forceScan = true;
        }
        String key = w > h ? "land" : "port";
        if (key.equals(curKey)) return;
        curKey = key;
        cand = null;
        tracker.clear();
        prevCueX = -1;
        prevCueY = -1;
        lastTableScan = 0;
        String k = "c3_" + key + "_";
        if (prefs.contains(k + "L")) {
            aim.setTable(prefs.getFloat(k + "L", 0), prefs.getFloat(k + "T", 0),
                    prefs.getFloat(k + "R", 100), prefs.getFloat(k + "B", 50), prefs.getFloat(k + "r", 10));
            feltCb = prefs.getInt(k + "cb", -1);
            feltCr = prefs.getInt(k + "cr", -1);
            feltY = prefs.getInt(k + "fy", -1);
            manualCal = prefs.getBoolean(k + "manual", false);
            calReady = true;
        } else {
            aim.clearTable();
            calReady = false;
            manualCal = false;
        }
    }

    private void saveCal(boolean manual) {
        if (curKey == null) return;
        String k = "c3_" + curKey + "_";
        prefs.edit()
                .putFloat(k + "L", aim.L)
                .putFloat(k + "T", aim.T)
                .putFloat(k + "R", aim.R)
                .putFloat(k + "B", aim.B)
                .putFloat(k + "r", aim.r)
                .putInt(k + "cb", feltCb)
                .putInt(k + "cr", feltCr)
                .putInt(k + "fy", feltY)
                .putBoolean(k + "manual", manual)
                .apply();
    }

    @Override
    public void onCalibEdited() {
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
        if (!calib && !calReady) {
            toast("Стол ещё не найден — подожди или нажми 🎯");
            return;
        }
        if (hidden) toggleHidden();
        calib = !calib;
        if (calib) {
            aimLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            aimLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            manualCal = true;
            saveCal(true);
            prevMask = null;
            forceScan = true;
        }
        try {
            wm.updateViewLayout(aim, aimLp);
        } catch (Exception ignored) {
        }
        aim.setCalib(calib);
        bCal.setText(calib ? "✓" : "⚙");
        bCal.setTextColor(calib ? 0xFF69F0AE : 0xFFFFFFFF);
    }

    private void toggleHidden() {
        if (!hidden && calib) toggleCalib();
        hidden = !hidden;
        aim.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        if (hidden) {
            guideAng = Float.NaN;
            aim.setGuide(Float.NaN);
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
        btn(c1, "🎯", v -> restartAuto());
        btn(c1, "⟳", v -> aim.nextShot());
        bFilter = btn(c1, "В", v -> cycleFilter());
        bSugg = btn(c2, "1", v -> {
            int m = (aim.mode + 1) % 3;
            aim.setMode(m);
            bSugg.setText(m == AimView.MODE_ONE ? "1" : m == AimView.MODE_PHYSICS ? "∞" : "💡");
            toast(m == AimView.MODE_ONE ? "Одна линия от кия"
                    : m == AimView.MODE_PHYSICS ? "Физика всех шаров (как в видео)" : "Подсказки ко всем шарам");
        });
        bBank = btn(c2, "Б", v -> {
            aim.setBanks(!aim.banks);
            bBank.setTextColor(aim.banks ? 0xFF69F0AE : 0xFFFFFFFF);
        });
        bBank.setTextColor(0xFF69F0AE);
        bCal = btn(c2, "⚙", v -> toggleCalib());
        bEye = btn(c2, "👁", v -> toggleHidden());
        btn(c2, "✕", v -> stopSelf());
        panel.addView(c1);
        panel.addView(c2);

        powerBar = new PowerBar(this, this);
        powerBar.setLayoutParams(new LinearLayout.LayoutParams((int) (34 * dp), LinearLayout.LayoutParams.MATCH_PARENT));
        panel.addView(powerBar);

        panelLp = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
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
