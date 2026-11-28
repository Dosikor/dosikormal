package kz.poolaim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
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
    boolean banks = true, calib, showCue = true;
    // Режимы: одна линия от кия (по умолчанию) / физика всех шаров (как в видео) / подсказки ко всем шарам.
    static final int MODE_ONE = 0, MODE_PHYSICS = 1, MODE_HINTS = 2;
    int mode = MODE_ONE;
    boolean showSugg = false;
    float sx, sy; // пробный шар в режиме ручной подстройки

    private List<Geo.Ball> balls = new ArrayList<>();
    private int cueIdx = -1;
    int filter = Geo.FILTER_ALL;
    private String status;

    float dmaxTables = 6f;
    boolean powerAuto = true;
    float powerManual = 0.5f;

    // Продолжение линии прицела игры: цель (от службы) и плавно догоняющий её показанный угол.
    private float guideTarget = Float.NaN, guideDisp = Float.NaN;
    private Sim.Result sim;          // симуляция удара по линии прицела игры
    private float[] simX, simY;      // положения шаров, для которых считали
    private float predAlpha = 0f;

    // Плавность: подсказки бледнеют, пока шары катятся; время прошлого кадра анимации.
    private float suggAlpha = 1f, suggTarget = 1f;
    private long lastAnim;

    // «Липкий» выбор: какой шар выделен и какая луза выбрана у каждого шара (чтобы не прыгало).
    private boolean hlValid;
    private float hlX, hlY;
    private List<float[]> choiceMem = new ArrayList<>(); // x, y, ключ удара

    private List<Geo.Shot> perBall = new ArrayList<>(); // лучший удар для каждого шара, от простого к сложному
    private int sel = 0;
    private boolean selManual;
    private float selX, selY;

    private final Paint pTable, pPocket, pObj, pObjFaint, pAim, pGhost, pDot, pCue, pCueBad, pText, pSmall, pHandle,
            pCal, pAlt, pAltGhost, pAltObj, pRing, pRingStripe, pCueRing, pBad, pNoShot, pStop, pHit,
            pBounce, pPredCue, pPredObj, pPredAfter, pPredGhost, pSim, pSimDot, pSimDotEdge, pSimRing,
            pOne, pOneObj, pOneGhost, pRail, pCueThin;
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
        pAim = stroke(0xFFFFEE58, 2f);
        pGhost = stroke(0xFFFFF59D, 2.2f);
        pDot = fill(0xFFFFF59D);
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
        pAlt = stroke(0x99FFEE58, 1.2f);
        pBounce = fill(0xFFFFF59D);
        pPredCue = stroke(0xFFFFC400, 2.5f);
        pPredObj = stroke(0xFFFF9100, 3f);
        pPredAfter = stroke(0xFFE040FB, 2.2f);
        pPredAfter.setPathEffect(new DashPathEffect(new float[]{9 * dp, 6 * dp}, 0));
        pPredGhost = stroke(0xFFFFC400, 2.2f);
        pSim = stroke(0xFFFFFFFF, 2.6f);
        pSimDot = fill(0xFFFFFFFF);
        pSimDotEdge = stroke(0xCC000000, 1.2f);
        pSimRing = stroke(0xFFFFFFFF, 3f);
        // Одна линия от кия: жёлтая (не белая — чтобы не путать с линией самой игры).
        pOne = stroke(0xFFFFEB3B, 2.6f);
        pOneObj = stroke(0xFF00E676, 3f);
        pOneGhost = stroke(0xFFFFF59D, 2f);
        pRail = stroke(0xCCFFEB3B, 1.3f);
        pCueThin = stroke(0x99FFFFFF, 1.4f);
        pAltGhost = stroke(0x99FFF59D, 1.5f);
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
        computePrediction();
        notifyPower();
        invalidate();
    }

    /** Направление линии прицела игры (радианы) или NaN. Показ догоняет его плавно. */
    void setGuide(float ang) {
        if (Float.isNaN(ang) && Float.isNaN(guideTarget)) return;
        if (!Float.isNaN(ang) && !Float.isNaN(guideTarget) && Math.abs(ang - guideTarget) < 1e-4f) return;
        guideTarget = ang;
        invalidate();
    }

    /** Шары катятся — подсказки плавно бледнеют, пока всё не остановится. */
    void setMoving(boolean m) {
        float t = m ? 0.3f : 1f;
        if (t == suggTarget) return;
        suggTarget = t;
        invalidate();
    }

    float cueX() {
        return cueIdx >= 0 && cueIdx < balls.size() ? balls.get(cueIdx).x : Float.NaN;
    }

    float cueY() {
        return cueIdx >= 0 && cueIdx < balls.size() ? balls.get(cueIdx).y : Float.NaN;
    }

    /** Остальные шары (кроме битка): x0,y0,x1,y1,... */
    float[] otherBalls() {
        int n = 0;
        float[] o = new float[2 * balls.size()];
        for (int i = 0; i < balls.size(); i++) {
            if (i == cueIdx) continue;
            o[n++] = balls.get(i).x;
            o[n++] = balls.get(i).y;
        }
        float[] res = new float[n];
        System.arraycopy(o, 0, res, 0, n);
        return res;
    }

    private void computePrediction() {
        sim = null;
        if (Float.isNaN(guideDisp) || !tableReady || cueIdx < 0 || cueIdx >= balls.size()) return;
        int n = balls.size();
        simX = new float[n];
        simY = new float[n];
        for (int i = 0; i < n; i++) {
            simX[i] = balls.get(i).x;
            simY[i] = balls.get(i).y;
        }
        sim = Sim.run(simX, simY, cueIdx, (float) Math.cos(guideDisp), (float) Math.sin(guideDisp), power(),
                L, T, R, B, r, dmax());
    }

    void setMode(int m) {
        mode = m;
        setShowSugg(m == MODE_HINTS);
    }

    void setShowSugg(boolean on) {
        showSugg = on;
        selManual = false;
        notifyPower();
        computePrediction();
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
        hlValid = true;
        hlX = selX;
        hlY = selY;
        notifyPower();
        invalidate();
    }

    private static final float STICKY = 10f; // насколько новый вариант должен быть лучше, чтобы сменить старый

    void recompute() {
        perBall = new ArrayList<>();
        if (tableReady && cueIdx >= 0 && cueIdx < balls.size()) {
            List<Geo.Shot> all = Geo.computeAll(L, T, R, B, r, balls, cueIdx, filter, banks, dmax());
            Geo.Shot[] best = new Geo.Shot[balls.size()];
            for (Geo.Shot s : all) if (s.obj >= 0 && best[s.obj] == null) best[s.obj] = s;
            List<float[]> mem = new ArrayList<>();
            for (int i = 0; i < best.length; i++) {
                if (best[i] == null) continue;
                Geo.Shot chosen = best[i];
                Geo.Ball b = balls.get(i);
                // Если раньше у этого шара была выбрана другая луза и она почти так же хороша — оставляем её.
                for (float[] m : choiceMem) {
                    if (Math.hypot(m[0] - b.x, m[1] - b.y) > 0.6f * r) continue;
                    for (Geo.Shot s : all) {
                        if (s.obj == i && s.key().hashCode() == (int) m[2] && s.score <= best[i].score + STICKY) {
                            chosen = s;
                            break;
                        }
                    }
                    break;
                }
                perBall.add(chosen);
                mem.add(new float[]{b.x, b.y, chosen.key().hashCode()});
            }
            choiceMem = mem;
            java.util.Collections.sort(perBall, (a, c) -> Float.compare(a.score, c.score));
        }
        computePrediction();
        sel = 0;
        if (!selManual && hlValid) {
            // Выделенный шар меняем, только если другой заметно проще.
            for (int i = 0; i < perBall.size(); i++) {
                Geo.Shot s = perBall.get(i);
                if (Math.hypot(s.ox - hlX, s.oy - hlY) < 0.6f * r) {
                    if (s.score <= perBall.get(0).score + STICKY) sel = i;
                    break;
                }
            }
        }
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
        if (!perBall.isEmpty()) {
            hlValid = true;
            hlX = perBall.get(sel).ox;
            hlY = perBall.get(sel).oy;
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

    /** Сила: ручная — как на шкале; АВТО — 100% на разбое, по подсказке (если включены) или 70%. */
    private float power() {
        if (!powerAuto) return powerManual;
        if (cueIdx >= 0 && isBreak()) return 1f;
        if (perBall.isEmpty()) return 0.7f;
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

    /** Зона вокруг битка, где игра рисует свою белую линию прицела: туда мы ничего не рисуем. */
    private float guideZone() {
        return 6.6f * r;
    }

    /** Шаг плавной анимации: угол линии прицела и прозрачность догоняют цель. true — ещё не догнали. */
    private boolean stepAnimation() {
        long now = SystemClock.uptimeMillis();
        float dt = lastAnim == 0 ? 0.016f : Math.min(0.1f, (now - lastAnim) / 1000f);
        lastAnim = now;
        boolean more = false;
        // Угол линии прицела.
        if (!Float.isNaN(guideTarget) && guideDisp != guideTarget) {
            guideDisp = guideTarget;
            computePrediction();
        }
        // Продолжение линии прицела плавно появляется/гаснет.
        float pt = Float.isNaN(guideTarget) ? 0f : 1f;
        if (Math.abs(pt - predAlpha) > 0.01f) {
            predAlpha += (pt - predAlpha) * (float) (1 - Math.exp(-dt / 0.12));
            more = true;
        } else {
            predAlpha = pt;
            if (pt == 0f && !Float.isNaN(guideDisp)) {
                guideDisp = Float.NaN;
                sim = null;
            }
        }
        // Подсказки бледнеют, пока шары катятся.
        if (Math.abs(suggTarget - suggAlpha) > 0.01f) {
            suggAlpha += (suggTarget - suggAlpha) * (float) (1 - Math.exp(-dt / 0.15));
            more = true;
        } else {
            suggAlpha = suggTarget;
        }
        return more;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (stepAnimation()) postInvalidateOnAnimation();
        else lastAnim = 0;
        if (calib && tableReady) {
            drawCalib(c);
            return;
        }
        if (status != null) {
            pSmall.setColor(0xFFFFFFFF);
            c.drawText(status, (getWidth() - pSmall.measureText(status)) / 2f, 18 * dp, pSmall);
        }
        if (!tableReady) return;

        if (mode == MODE_ONE) {
            drawOneLine(c);
            return;
        }
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
            if (!showSugg) {
                pRing.setAlpha(0x55);
                c.drawCircle(b.x, b.y, r + 1.5f * dp, pRing);
            } else if (on && !hasShot[i] && cueIdx >= 0) {
                c.drawCircle(b.x, b.y, r + 2 * dp, pNoShot);
            } else {
                Paint p = b.type == Geo.STRIPE ? pRingStripe : pRing;
                p.setAlpha(on ? 0x99 : 0x33);
                c.drawCircle(b.x, b.y, r + 1.5f * dp, p);
            }
        }
        if (cueIdx < 0) return;

        Geo.Ball cueBall = balls.get(cueIdx);
        if (!showSugg) {
            drawPrediction(c);
            return;
        }
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
            drawPrediction(c);
            return;
        }

        int layer = suggAlpha < 0.99f
                ? c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * suggAlpha)) : -1;
        float zone = guideZone();

        // Линия для каждого шара: куда целиться и в какую лузу.
        for (int i = 0; i < perBall.size(); i++) {
            if (i == sel) continue;
            Geo.Shot a = perBall.get(i);
            drawPoly(c, trimEnd(trimDist(a.cuePath, zone), r), pAlt);
            bounces(c, a.cuePath);
            c.drawCircle(a.gx, a.gy, r, pAltGhost);
            drawPoly(c, trimStart(a.objPath, r + 3 * dp), pAltObj);
            bounces(c, a.objPath);
        }

        Geo.Shot s = perBall.get(sel);
        float dmax = dmax();
        float p = power();
        Geo.Run obj = Geo.objectRun(s, p, dmax);
        Geo.Run cue = Geo.cueRun(s, p, dmax, L, T, R, B, r, obstaclesFor(s));

        if (!obj.full) drawPoly(c, trimStart(s.objPath, r + 3 * dp), pObjFaint);
        drawPoly(c, trimStart(obj.pts, r + 3 * dp), pObj);
        bounces(c, s.objPath);
        c.drawCircle(s.px, s.py, r * 1.1f, obj.full ? pObj : pObjFaint);
        if (!obj.full && obj.pts.length >= 2) {
            cross(c, obj.pts[obj.pts.length - 2], obj.pts[obj.pts.length - 1], r * 0.6f, pBad);
        }

        if (cue.shortOfObject) {
            drawPoly(c, trimDist(cue.pts, zone), pBad);
            cross(c, cue.pts[cue.pts.length - 2], cue.pts[cue.pts.length - 1], r * 0.6f, pBad);
        } else {
            drawPoly(c, trimEnd(trimDist(s.cuePath, zone), r), pAim);
            bounces(c, s.cuePath);
        }
        c.drawCircle(s.gx, s.gy, r, pGhost);
        c.drawCircle(s.gx, s.gy, 1.8f * dp, pDot);

        if (showCue && !cue.shortOfObject && cue.pts.length >= 4) {
            drawPoly(c, cue.pts, cue.scratch ? pCueBad : pCue);
            bounces(c, cue.pts);
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
        String via = s.objCush == 1 ? " · борт" : s.objCush == 2 ? " · 2 борта" : "";
        if (s.cueCush > 0) via += " · биток через борт";
        labelAt(c, Math.round(s.cut) + "°" + via
                + "   сила " + pct(p) + "   " + (sel + 1) + "/" + perBall.size(), s.ox, s.oy, warn);
        if (layer >= 0) c.restoreToCount(layer);

        drawPrediction(c);
    }

    /** Продолжение линии прицела игры: куда пойдёт биток, какой шар заденет, куда тот покатится. */
    private void drawPrediction(Canvas c) {
        if (sim == null || cueIdx < 0 || predAlpha < 0.02f) return;
        int layer = predAlpha < 0.99f
                ? c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * predAlpha)) : -1;
        drawPredictionInner(c);
        if (layer >= 0) c.restoreToCount(layer);
    }

    // ---------------------------------------------------------------- режим «одна линия от кия»

    /**
     * Одна линия от битка: если игра показывает свою линию прицела — по ней (с отскоками) до шара,
     * и куда этот шар покатится; если нет — один самый простой удар. Больше ничего.
     */
    private void drawOneLine(Canvas c) {
        if (cueIdx < 0 || cueIdx >= balls.size()) return;
        Geo.Ball cb = balls.get(cueIdx);
        c.drawCircle(cb.x, cb.y, r + 2 * dp, pCueThin);
        if (sim != null && predAlpha > 0.02f) {
            int layer = predAlpha < 0.99f
                    ? c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * predAlpha)) : -1;
            drawOneFromSim(c, cb);
            if (layer >= 0) c.restoreToCount(layer);
            if (predAlpha > 0.98f) return;
        }
        if (!perBall.isEmpty()) {
            int layer = predAlpha > 0.02f
                    ? c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * (1 - predAlpha))) : -1;
            drawOneFromShot(c, cb, perBall.get(sel));
            if (layer >= 0) c.restoreToCount(layer);
        } else if (isBreak()) {
            int near = nearestTarget();
            if (near >= 0) {
                Geo.Ball nb = balls.get(near);
                lineFromCue(c, new float[]{cb.x, cb.y, nb.x, nb.y}, pOne, r + 3 * dp);
                pText.setColor(0xFFFFAB40);
                labelAt(c, "РАЗБОЙ: в первый шар, сила 100%", cb.x, cb.y, null);
            }
        }
    }

    private void drawOneFromSim(Canvas c, Geo.Ball cb) {
        Sim.Result sm = sim;
        float[] cp = sm.paths[cueIdx];
        if (cp == null) return;
        int hitN = sm.firstHit >= 0 && sm.cueHitLen >= 4 ? Math.min(sm.cueHitLen, cp.length) : cp.length;
        float[] toHit = java.util.Arrays.copyOf(cp, hitN);
        lineFromCue(c, toHit, pOne, sm.firstHit >= 0 ? r : 0);
        float[] pk = Geo.pockets(L, T, R, B);
        String t;
        int tc;
        if (sm.firstHit >= 0) {
            float hx = toHit[hitN - 2], hy = toHit[hitN - 1];
            c.drawCircle(hx, hy, r, pOneGhost);
            float[] op = sm.paths[sm.firstHit];
            int col = visible(colorOf(sm.firstHit));
            if (op != null) {
                pOneObj.setColor(col);
                drawPoly(c, trimStart(op, r + 2 * dp), pOneObj);
            }
            if (sm.potted[sm.firstHit]) {
                int q = sm.pocketOf[sm.firstHit];
                c.drawCircle(pk[2 * q], pk[2 * q + 1], r * 1.25f, pObj);
                t = "✓ В ЛУЗУ";
                tc = 0xFF69F0AE;
            } else {
                t = "мимо";
                tc = 0xFFFF9100;
            }
        } else {
            t = sm.cuePotted ? "" : "не попадёт в шар";
            tc = 0xFFFF9100;
        }
        if (sm.cuePotted) {
            t = (t.isEmpty() ? "" : t + "   ") + "⚠ биток в лузу";
            tc = 0xFFFF5252;
        }
        pText.setColor(tc);
        float lx = sm.firstHit >= 0 ? simX[sm.firstHit] : cb.x, ly = sm.firstHit >= 0 ? simY[sm.firstHit] : cb.y;
        labelAt(c, t + "   сила " + pct(power()), lx, ly + 3.2f * r, null);
    }

    private void drawOneFromShot(Canvas c, Geo.Ball cb, Geo.Shot s) {
        lineFromCue(c, s.cuePath, pOne, r);
        c.drawCircle(s.gx, s.gy, r, pOneGhost);
        drawPoly(c, trimStart(s.objPath, r + 3 * dp), pObj);
        float[] pk = Geo.pockets(L, T, R, B);
        c.drawCircle(s.px, s.py, r * 1.1f, pObj);
        String via = s.objCush == 1 ? " · борт" : s.objCush == 2 ? " · 2 борта" : "";
        if (s.cueCush > 0) via += " · биток через борт";
        pText.setColor(s.cut < 30 ? 0xFF69F0AE : s.cut < 60 ? 0xFFFFD740 : 0xFFFF5252);
        labelAt(c, Math.round(s.cut) + "°" + via + "   сила " + pct(power()), s.ox, s.oy, null);
    }

    /**
     * Линия от края битка. Возле битка (там игра рисует свою белую линию прицела) — «коридор» из двух тонких
     * линий по бокам, чтобы не закрывать линию игры; дальше — сплошная линия. endTrim — укоротить конец.
     */
    private void lineFromCue(Canvas c, float[] pts, Paint p, float endTrim) {
        if (pts == null || pts.length < 4) return;
        float[] q = endTrim > 0 ? trimEnd(pts, endTrim) : pts;
        if (q == null || q.length < 4) return;
        float cx = pts[0], cy = pts[1], zone = guideZone();
        float dx = q[2] - q[0], dy = q[3] - q[1];
        float l = (float) Math.hypot(dx, dy);
        if (l > 1e-3f) {
            dx /= l;
            dy /= l;
            float a = r + 3 * dp, b = Math.min(zone, l);
            if (b > a) {
                float ox = -dy * 3.5f * dp, oy = dx * 3.5f * dp;
                c.drawLine(cx + dx * a + ox, cy + dy * a + oy, cx + dx * b + ox, cy + dy * b + oy, pRail);
                c.drawLine(cx + dx * a - ox, cy + dy * a - oy, cx + dx * b - ox, cy + dy * b - oy, pRail);
            }
        }
        drawPolyZ(c, q, p, cx, cy, zone);
        bounces(c, q);
    }

    private void drawPredictionInner(Canvas c) {
        Sim.Result sm = sim;
        if (sm == null || simX == null) return;
        float cx = cueX(), cy = cueY(), zone = guideZone();
        float[] pk = Geo.pockets(L, T, R, B);
        int potted = 0;
        // Пути всех шаров — каждый своим цветом (как в видео); у битка — белый.
        for (int i = 0; i < sm.paths.length; i++) {
            float[] p = sm.paths[i];
            if (p == null) continue;
            int col = i == cueIdx ? 0xFFFFFFFF : visible(colorOf(i));
            pSim.setColor(col);
            pSim.setStrokeWidth((i == cueIdx ? 2.2f : 2.6f) * dp);
            drawPolyZ(c, i == cueIdx ? p : trimStart(p, r + 2 * dp), pSim, cx, cy, zone);
            // Где остановится — точка цвета шара; если упадёт — кольцо у лузы.
            if (sm.potted[i]) {
                if (i != cueIdx) potted++;
                pSimRing.setColor(i == cueIdx ? 0xFFFF5252 : col);
                int q = sm.pocketOf[i];
                c.drawCircle(pk[2 * q], pk[2 * q + 1], r * 1.25f, pSimRing);
            } else if (Math.hypot(sm.fx[i] - cx, sm.fy[i] - cy) > zone || i != cueIdx) {
                pSimDot.setColor(col);
                c.drawCircle(sm.fx[i], sm.fy[i], 0.5f * r, pSimDot);
                c.drawCircle(sm.fx[i], sm.fy[i], 0.5f * r, pSimDotEdge);
            }
        }
        // Подпись у первого задетого шара.
        String t = null;
        int tc = 0xFF69F0AE;
        if (sm.cuePotted) {
            t = "⚠ биток в лузу";
            tc = 0xFFFF5252;
        } else if (sm.firstHit >= 0 && sm.potted[sm.firstHit]) {
            t = potted > 1 ? "✓ В ЛУЗУ  (забьёт: " + potted + ")" : "✓ В ЛУЗУ";
        } else if (potted > 0) {
            t = "забьёт: " + potted;
        } else if (sm.firstHit >= 0) {
            t = "мимо";
            tc = 0xFFFF9100;
        }
        if (t != null) {
            pText.setColor(tc);
            float lx = sm.firstHit >= 0 ? simX[sm.firstHit] : cx, ly = sm.firstHit >= 0 ? simY[sm.firstHit] : cy;
            labelAt(c, t + "   сила " + pct(power()), lx, ly + 3.2f * r, null);
        }
    }

    private int colorOf(int i) {
        if (i < 0 || i >= balls.size()) return 0xFFFFFFFF;
        Geo.Ball b = balls.get(i);
        if (b.color != 0) return b.color;
        return b.type == Geo.EIGHT ? 0xFF202020 : b.type == Geo.STRIPE ? 0xFFFFD54F : 0xFFFF7043;
    }

    /** Цвет линии: слишком тёмные чуть осветляем, чтобы были видны на сукне. */
    private static int visible(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int y = (299 * r + 587 * g + 114 * b) / 1000;
        if (y < 50) {
            r = Math.min(255, r + 40);
            g = Math.min(255, g + 40);
            b = Math.min(255, b + 40);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Ломаная без частей внутри круга (cx,cy,R) — там игра рисует свою линию прицела. */
    private void drawPolyZ(Canvas c, float[] pts, Paint p, float cx, float cy, float R) {
        if (pts == null || pts.length < 4) return;
        if (Float.isNaN(cx)) {
            drawPoly(c, pts, p);
            return;
        }
        path.reset();
        boolean open = false;
        for (int i = 0; i + 3 < pts.length; i += 2) {
            float x0 = pts[i], y0 = pts[i + 1], x1 = pts[i + 2], y1 = pts[i + 3];
            float dx = x1 - x0, dy = y1 - y0;
            float fx = x0 - cx, fy = y0 - cy;
            float a = dx * dx + dy * dy, b = 2 * (fx * dx + fy * dy), cc = fx * fx + fy * fy - R * R;
            float t0 = 0, t1 = 1; // внутренняя часть отрезка [ti0, ti1]
            float ti0 = 2, ti1 = -1;
            float disc = b * b - 4 * a * cc;
            if (a > 1e-6f && disc > 0) {
                float sq = (float) Math.sqrt(disc);
                ti0 = (-b - sq) / (2 * a);
                ti1 = (-b + sq) / (2 * a);
            }
            // Видимые куски: [0, ti0] и [ti1, 1].
            float[][] parts = {{t0, Math.min(t1, ti0)}, {Math.max(t0, ti1), t1}};
            if (ti0 > ti1 || ti1 < 0 || ti0 > 1) parts = new float[][]{{0, 1}};
            for (float[] pr : parts) {
                if (pr[1] - pr[0] <= 1e-4f) {
                    open = false;
                    continue;
                }
                float ax = x0 + dx * pr[0], ay = y0 + dy * pr[0], bx = x0 + dx * pr[1], by = y0 + dy * pr[1];
                if (!open || pr[0] > 1e-4f) path.moveTo(ax, ay);
                path.lineTo(bx, by);
                open = pr[1] >= 1 - 1e-4f;
            }
        }
        c.drawPath(path, p);
    }

    /** Точки в местах отскока от бортов (кроме зоны линии прицела игры вокруг битка). */
    private void bounces(Canvas c, float[] pts) {
        if (pts == null) return;
        float cx = cueX(), cy = cueY(), z = guideZone();
        for (int i = 2; i + 3 < pts.length; i += 2) {
            if (!Float.isNaN(cx) && Math.hypot(pts[i] - cx, pts[i + 1] - cy) < z) continue;
            c.drawCircle(pts[i], pts[i + 1], 2.5f * dp, pBounce);
        }
    }

    /** Ломаная без последних d пикселей. */
    private static float[] trimEnd(float[] pts, float d) {
        if (pts == null || pts.length < 4) return pts;
        int n = pts.length;
        float dx = pts[n - 2] - pts[n - 4], dy = pts[n - 1] - pts[n - 3];
        float l = (float) Math.hypot(dx, dy);
        if (l <= d + 1) return n <= 4 ? new float[0] : java.util.Arrays.copyOf(pts, n - 2);
        float[] o = pts.clone();
        o[n - 2] = pts[n - 2] - dx / l * d;
        o[n - 1] = pts[n - 1] - dy / l * d;
        return o;
    }

    /** Ломаная, начиная с расстояния d вдоль неё. */
    private static float[] trimDist(float[] pts, float d) {
        if (pts == null || pts.length < 4) return new float[0];
        float acc = 0;
        for (int i = 0; i + 3 < pts.length; i += 2) {
            float l = (float) Math.hypot(pts[i + 2] - pts[i], pts[i + 3] - pts[i + 1]);
            if (acc + l > d) {
                float t = (d - acc) / l;
                float[] o = new float[pts.length - i];
                o[0] = pts[i] + (pts[i + 2] - pts[i]) * t;
                o[1] = pts[i + 1] + (pts[i + 3] - pts[i + 1]) * t;
                System.arraycopy(pts, i + 2, o, 2, pts.length - i - 2);
                return o;
            }
            acc += l;
        }
        return new float[0];
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
