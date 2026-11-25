package kz.poolaim;

import java.util.ArrayList;
import java.util.List;

/**
 * Удерживает шары между кадрами, чтобы линии не мигали (без Android API).
 * Шар появляется после 2 подтверждений и исчезает только если его не видно 3 кадра подряд.
 * Мелкое дрожание координат гасится: если сдвиг < 0.35 r — точка не меняется.
 * Биток выбирается «голосованием» за несколько кадров.
 */
final class Tracker {

    private static final class Track {
        float x, y;
        int hits, misses;
        float cueScore;
        final int[] typeVotes = new int[4];
    }

    private final List<Track> tracks = new ArrayList<>();
    private Track cue;

    void clear() {
        tracks.clear();
        cue = null;
    }

    /**
     * @param dets найденные шары (x,y уже в координатах слоя; type после classify)
     * @param cueIdx индекс битка в dets или -1
     */
    void update(List<Detector.Blob> dets, int cueIdx, float r) {
        boolean[] used = new boolean[dets.size()];
        float match = 1.0f * r;
        // 1) Сопоставляем существующие шары с найденными (жадно, по ближайшему).
        for (Track t : tracks) {
            int best = -1;
            float bd = match;
            for (int i = 0; i < dets.size(); i++) {
                if (used[i]) continue;
                Detector.Blob d = dets.get(i);
                float dd = (float) Math.hypot(d.x - t.x, d.y - t.y);
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best >= 0) {
                used[best] = true;
                Detector.Blob d = dets.get(best);
                if (bd > 0.35f * r) {
                    t.x = d.x;
                    t.y = d.y;
                }
                t.hits = Math.min(t.hits + 1, 100);
                t.misses = 0;
                boolean isCue = best == cueIdx;
                t.cueScore = 0.6f * t.cueScore + (isCue ? 0.4f : 0f);
                if (!isCue && d.type >= 0 && d.type < 4) t.typeVotes[d.type]++;
            } else {
                t.misses++;
                t.cueScore *= 0.8f;
            }
        }
        // 2) Удаляем давно пропавшие.
        for (int i = tracks.size() - 1; i >= 0; i--) if (tracks.get(i).misses >= 3) tracks.remove(i);
        // 3) Новые шары.
        for (int i = 0; i < dets.size(); i++) {
            if (used[i]) continue;
            Detector.Blob d = dets.get(i);
            boolean dup = false;
            for (Track t : tracks) if (Math.hypot(d.x - t.x, d.y - t.y) < 1.5f * r) dup = true;
            if (dup) continue;
            Track t = new Track();
            t.x = d.x;
            t.y = d.y;
            t.hits = 1;
            t.cueScore = i == cueIdx ? 0.4f : 0f;
            if (i != cueIdx && d.type >= 0 && d.type < 4) t.typeVotes[d.type]++;
            tracks.add(t);
        }
        // 4) Биток — шар с наибольшим «голосом» (с запасом, чтобы не перескакивал).
        if (cue != null && !tracks.contains(cue)) cue = null;
        Track best = null;
        for (Track t : tracks) if (best == null || t.cueScore > best.cueScore) best = t;
        if (best != null && best.cueScore > 0.3f && (cue == null || best.cueScore > cue.cueScore + 0.15f)) cue = best;
        if (cue != null && cue.cueScore < 0.1f) cue = null;
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
