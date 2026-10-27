package tv.stream.app;

import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;
import tv.stream.app.addon.MediaSource;

/** Bounded process-local evidence, separate from the viewer's durable Played marks. No stream addresses. */
final class PlaybackOutcomes {
    private static final Map<String, Outcome> outcomes = new LinkedHashMap<>();
    private static final class Outcome { final boolean success; final long at; Outcome(boolean success) { this.success = success; at = SystemClock.elapsedRealtime(); } }
    static synchronized void record(MediaSource source, boolean success) {
        if (source == null) return;
        String key = SourcePolicy.key(source); outcomes.remove(key); outcomes.put(key, new Outcome(success));
        while (outcomes.size() > 256) outcomes.remove(outcomes.keySet().iterator().next());
    }
    static synchronized int rank(MediaSource source) {
        Outcome outcome = outcomes.get(SourcePolicy.key(source));
        if (outcome == null || SystemClock.elapsedRealtime() - outcome.at > (outcome.success ? 600_000 : 300_000)) return 0;
        return outcome.success ? 100 : -3000;
    }
}
