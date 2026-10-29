package tv.stream.app

/** Presentation progress is independent of the audio clock. Ignore buffering, pauses and seek settling. */
class VideoFrameWatchdog {
    private var settledAt: Long = 0
    private var windowAt: Long = 0
    private var frames: Long = 0
    private var drops: Long = 0
    private var lastFrameAt: Long = 0
    private var lastRendered: Long = 0
    private var badWindows = 0
    fun reset(now: Long, frames: Long, drops: Long) { settledAt = now + 2000; lastFrameAt = now; windowAt = lastFrameAt; lastRendered = frames; this.frames = lastRendered; this.drops = drops; badWindows = 0 }
    fun unhealthy(now: Long, rendered: Long, dropped: Long, advancingAndBuffered: Boolean, videoEnded: Boolean): Boolean {
        // Audio and subtitles can outlast video. Only renderer EOS proves there are no frames left to expect.
        if (!advancingAndBuffered || videoEnded) { reset(now, rendered, dropped); return false }
        if (rendered != lastRendered) lastFrameAt = now
        lastRendered = rendered
        if (now < settledAt) { frames = rendered; drops = dropped; windowAt = now; return false }
        if (now - lastFrameAt > 2000) return true
        if (now - windowAt >= 5000) {
            val shown = Math.max(0L, rendered - frames); val missed = Math.max(0L, dropped - drops)
            badWindows = if (shown + missed >= 20 && missed * 5 > shown + missed) badWindows + 1 else 0
            frames = rendered; drops = dropped; windowAt = now
        }
        return badWindows >= 2
    }
}
