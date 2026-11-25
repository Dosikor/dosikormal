package kz.poolaim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Удерживает шары между кадрами, чтобы линии не мигали и не дёргались (без Android API).
 * - Показанная точка шара = среднее последних 8 измерений, и меняется, только если среднее
 *   ушло больше чем на 0.3 r: шум распознавания линии не двигает.
 * - Большой сдвиг (шар покатился) принимается, когда подтверждён на 2 проверках подряд;
 *   пока шар катится, isMoving() = true (подсказки бледнеют), линии не скачут за ним.
 * - Шар исчезает, только если его не видно 5 проверок подряд.
 * - Биток не перескакивает на другой шар из-за одного кадра.
 */
final class Tracker {

    private static final int WIN = 8;

    private static final class Track {
        float x, y;                       // показанная точка
        final float[] wx = new float[WIN], wy = new float[WIN];
        int wn, wi;                       // окно последних измерений
        float pendX, pendY;
        int pendCount;                    // сколько раз подряд шар виден далеко от показанной точки
        int hits, misses;
        int cueStreak, notCueStreak;
        final int[] typeVotes = new int[4];

        void resetWindow(float px, float py) {
            wn = 0;
            wi = 0;
            add(px, py);
        }

        void add(float px, float py) {
            wx[wi] = px;
            wy[wi] = py;
            wi = (wi + 1) % WIN;
            if (wn < WIN) wn++;
        }

        float mx() {
            float s = 0;
            for (int i = 0; i < wn; i++) s += wx[i];
            return s / wn;
        }

        float my() {
            float s = 0;
            for (int i = 0; i < wn; i++) s += wy[i];
            return s / wn;
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private Track cue;
    private boolean moving;

    void clear() {
        tracks.clear();
        cue = null;
        moving = false;
    }

    boolean isMoving() {
        return moving;
    }

    void update(List<Detector.Blob> dets, int cueIdx, float r) {
        moving = false;
        // Сопоставление: все пары «шар—находка» ближе 4r, от ближайших к дальним.
        List<float[]> pairs = new ArrayList<>();
        for (int ti = 0; ti < tracks.size(); ti++) {
            Track t = tracks.get(ti);
            for (int di = 0; di < dets.size(); di++) {
                Detector.Blob d = dets.get(di);
                float dd = (float) Math.hypot(d.x - t.x, d.y - t.y);
                if (dd < 4f * r) pairs.add(new float[]{dd, ti, di});
            }
        }
        Collections.sort(pairs, (a, b) -> Float.compare(a[0], b[0]));
        int[] trackDet = new int[tracks.size()];
        java.util.Arrays.fill(trackDet, -1);
        boolean[] used = new boolean[dets.size()];
        for (float[] p : pairs) {
            int ti = (int) p[1], di = (int) p[2];
            if (trackDet[ti] >= 0 || used[di]) continue;
            trackDet[ti] = di;
            used[di] = true;
        }

        for (int ti = 0; ti < tracks.size(); ti++) {
            Track t = tracks.get(ti);
            int di = trackDet[ti];
            if (di < 0) {
                t.misses++;
                continue;
            }
            Detector.Blob d = dets.get(di);
            float dd = (float) Math.hypot(d.x - t.x, d.y - t.y);
            if (dd <= 0.6f * r) {
                t.pendCount = 0;
                t.add(d.x, d.y);
                float mx = t.mx(), my = t.my();
                if (Math.hypot(mx - t.x, my - t.y) > 0.3f * r) {
                    t.x = mx;
                    t.y = my;
                }
            } else {
                // Далеко от прежнего места: ждём подтверждения на следующей проверке.
                if (t.pendCount > 0 && Math.hypot(d.x - t.pendX, d.y - t.pendY) < 0.6f * r) t.pendCount++;
                else t.pendCount = 1;
                t.pendX = d.x;
                t.pendY = d.y;
                if (t.pendCount >= 2) {
                    t.x = d.x;
                    t.y = d.y;
                    t.resetWindow(d.x, d.y);
                    t.pendCount = 0;
                } else if (t.hits >= 2 && dd > 1.2f * r) {
                    moving = true; // явно покатился (шум так далеко не уводит)
                }
            }
            t.hits = Math.min(t.hits + 1, 1000);
            t.misses = 0;
            if (di == cueIdx) {
                t.cueStreak++;
                t.notCueStreak = 0;
            } else {
                t.notCueStreak++;
                t.cueStreak = 0;
                if (d.type >= 0 && d.type < 4) t.typeVotes[d.type]++;
            }
        }
        for (int i = tracks.size() - 1; i >= 0; i--) if (tracks.get(i).misses >= 5) tracks.remove(i);
        for (int i = 0; i < dets.size(); i++) {
            if (used[i]) continue;
            Detector.Blob d = dets.get(i);
            boolean dup = false;
            for (Track t : tracks) if (Math.hypot(d.x - t.x, d.y - t.y) < 1.5f * r) dup = true;
            if (dup) continue;
            Track t = new Track();
            t.x = d.x;
            t.y = d.y;
            t.resetWindow(d.x, d.y);
            t.hits = 1;
            if (i == cueIdx) t.cueStreak = 1;
            else if (d.type >= 0 && d.type < 4) t.typeVotes[d.type]++;
            tracks.add(t);
        }
        // Биток: держим текущий, пока он не перестал быть битком 4 раза подряд, а другой — уже 3 раза подряд им был.
        if (cue != null && !tracks.contains(cue)) cue = null;
        Track best = null;
        for (Track t : tracks) if (t.cueStreak > 0 && (best == null || t.cueStreak > best.cueStreak)) best = t;
        if (cue == null) {
            cue = best;
        } else if (best != null && best != cue && cue.notCueStreak >= 4 && best.cueStreak >= 3) {
            cue = best;
        }
    }

    /** Подтверждённые шары. Биток — в cueOut[0] (индекс в возвращённом списке) или -1. */
    List<Geo.Ball> visible(int[] cueOut) {
        List<Geo.Ball> out = new ArrayList<>();
        cueOut[0] = -1;
        for (Track t : tracks) {
            if (t.hits < 2 && t != cue) continue;
            int type = Geo.SOLID;
            if (t == cue) {
                type = Geo.CUE;
                cueOut[0] = out.size();
            } else {
                int bv = -1;
                for (int k = 1; k < 4; k++) {
                    if (t.typeVotes[k] > bv) {
                        bv = t.typeVotes[k];
                        type = k;
                    }
                }
            }
            out.add(new Geo.Ball(t.x, t.y, type));
        }
        return out;
    }
}
