package tv.stream.app

/** Main-thread useful-data clock. PlaybackCoordinator owns the overall deadline. */
class StartupWatchdog {
    private var progressed: Long = 0
    private var downloaded: Long = 0
    private var pausedAt: Long = -1
    fun start(now: Long) { progressed = now; downloaded = 0; pausedAt = -1 }
    fun setPaused(paused: Boolean, now: Long) {
        if (paused && pausedAt < 0) pausedAt = now
        else if (!paused && pausedAt >= 0) {
            val duration = now - pausedAt; progressed += duration; pausedAt = -1
        }
    }
    fun observe(bytes: Long, now: Long) {
        if (bytes > downloaded) { downloaded = bytes; if (pausedAt < 0) progressed = now }
    }
    fun quietMs(now: Long): Long = if (pausedAt >= 0) 0 else now - progressed
    companion object {
        fun bufferingStalled(buffering: Boolean, began: Long, quiet: Long, now: Long, threshold: Long): Boolean {
            return buffering && began > 0 && now - began >= threshold && quiet >= threshold
        }
    }
}
