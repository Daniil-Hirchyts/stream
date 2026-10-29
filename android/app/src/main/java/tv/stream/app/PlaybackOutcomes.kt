package tv.stream.app

import android.os.SystemClock
import java.util.LinkedHashMap
import tv.stream.app.addon.MediaSource

/** Bounded process-local evidence, separate from the viewer's durable Played marks. No stream addresses. */
object PlaybackOutcomes {
    private val outcomes: MutableMap<String, Outcome> = LinkedHashMap()
    private class Outcome(@JvmField val success: Boolean) { @JvmField val at: Long = SystemClock.elapsedRealtime() }
    @JvmStatic @Synchronized fun record(source: MediaSource?, success: Boolean) {
        if (source == null) return
        val key = SourcePolicy.key(source); outcomes.remove(key); outcomes[key] = Outcome(success)
        while (outcomes.size > 256) outcomes.remove(outcomes.keys.iterator().next())
    }
    @JvmStatic @Synchronized fun rank(source: MediaSource?): Int {
        val outcome = outcomes[SourcePolicy.key(source!!)]
        if (outcome == null || SystemClock.elapsedRealtime() - outcome.at > (if (outcome.success) 600_000 else 300_000)) return 0
        return if (outcome.success) 100 else -3000
    }
}
