package tv.stream.app

import android.os.SystemClock
import org.json.JSONObject
import java.util.LinkedHashMap
import tv.stream.torrent.TorrentEngine

/** App-worker-owned native lifetime and main-thread playback budgets, independent of the screen. */
class PlaybackCoordinator {
    enum class State { RESOLVING, CONNECTING, BUFFERING, PLAYING, RECOVERING, CLOSING, FAILED }
    @JvmField @Volatile var engine: TorrentEngine? = null
    @JvmField @Volatile var torrent: Long = 0
    @JvmField @Volatile var fileBytes: Long = 0
    @JvmField @Volatile var fileIndex: Int = -1
    private var state: State = State.RESOLVING
    private var tapped: Long = 0
    private var budgetStart: Long = 0
    private var budgetPaused: Long = 0
    private var attempt: Long = 0
    private var pausedAt: Long = -1
    private var pausedTotal: Long = 0
    private var attemptPaused: Long = 0
    private val stages: MutableMap<String, Long> = LinkedHashMap()
    fun begin(tapped: Long) {
        this.tapped = if (tapped > 0) tapped else SystemClock.elapsedRealtime()
        budgetStart = this.tapped; attempt = budgetStart; budgetPaused = 0; pausedAt = -1; attemptPaused = 0; pausedTotal = attemptPaused; stages.clear(); stage(State.RESOLVING)
    }
    @Synchronized fun mark(name: String) { markAt(name, SystemClock.elapsedRealtime()) }
    @Synchronized fun markAt(name: String, at: Long) { stages.putIfAbsent(name, at - tapped - pausedTotal) }
    @Synchronized fun stage(state: State) { this.state = state; stages.putIfAbsent(state.name, SystemClock.elapsedRealtime() - tapped - pausedTotal) }
    fun attempt() { attempt = SystemClock.elapsedRealtime(); attemptPaused = pausedTime(attempt); stage(State.CONNECTING) }
    fun paused(paused: Boolean, now: Long) {
        if (paused && pausedAt < 0) pausedAt = now
        if (!paused && pausedAt >= 0) { pausedTotal += now - pausedAt; pausedAt = -1 }
    }
    private fun pausedTime(now: Long): Long = pausedTotal + (if (pausedAt < 0) 0 else now - pausedAt)
    fun elapsed(now: Long): Long = now - tapped - pausedTime(now)
    fun attemptElapsed(now: Long): Long = now - attempt - pausedTime(now) + attemptPaused
    fun recover(now: Long) { budgetStart = now; budgetPaused = pausedTime(now); stage(State.RECOVERING) }
    fun remaining(now: Long): Long = Math.max(0L, 60_000L - (now - budgetStart - pausedTime(now) + budgetPaused))
    fun expired(now: Long): Boolean = pausedAt < 0 && state != State.PLAYING && now - budgetStart - pausedTime(now) + budgetPaused >= 60_000
    /** Only the application's serial playback worker calls these resource operations. */
    @Throws(Exception::class)
    fun remove() {
        if (engine != null && torrent != 0L) {
            try { engine!!.remove(torrent) } catch (failure: Exception) { close() }
        }
        torrent = 0; fileBytes = 0; fileIndex = -1
    }
    fun close() {
        val old = engine; engine = null; torrent = 0; fileBytes = 0; fileIndex = -1
        old?.close()
    }
    @Synchronized fun firstAvMs(audio: Boolean): Long {
        val video = stages["FIRST_VIDEO_FRAME"]; val sound = stages["FIRST_AUDIO_OUTPUT"]
        return if (video == null || audio && sound == null) 0L else Math.max(video, sound ?: 0L)
    }
    @Synchronized fun diagnostics(): JSONObject = JSONObject(stages as Map<*, *>)
}
