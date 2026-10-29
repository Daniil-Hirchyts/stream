package tv.stream.app

/** Compressed-media consumption, never swarm throughput. Seek/discontinuity samples are discarded. */
class PlaybackDemand {
    private var averageKnown = false
    private var position: Long = -1
    private var bytes: Long = 0
    private var estimate: Long = 0
    private var sampledAt: Long = 0
    fun reset() { position = -1; sampledAt = 0; estimate = 0; averageKnown = false }
    fun estimate(fileBytes: Long, durationMs: Long, readBytes: Long, mediaPositionMs: Long, now: Long): Long {
        val average = if (fileBytes > 0 && durationMs > 0) Math.max(16_000L, (fileBytes * 1000.0 / durationMs).toLong()) else 1_250_000L
        if (estimate == 0L || !averageKnown && fileBytes > 0 && durationMs > 0) estimate = average
        averageKnown = averageKnown or (fileBytes > 0 && durationMs > 0)
        val elapsed = now - sampledAt; val advanced = mediaPositionMs - position
        if (position >= 0 && elapsed >= 5000 && advanced > 0 && advanced < elapsed * 4 + 2000 && readBytes > bytes) {
            var consumed = ((readBytes - bytes) * 1000.0 / advanced).toLong()
            consumed = Math.max(average / 2, Math.min(average * 4, consumed))
            estimate = (estimate * 3 + consumed) / 4
        }
        if (position < 0 || elapsed >= 5000) { position = mediaPositionMs; bytes = readBytes; sampledAt = now }
        return estimate
    }
    companion object {
        @JvmStatic fun aheadSeconds(firstFrame: Boolean, bufferedDurationMs: Long): Int {
            return if (firstFrame) Math.max(5L, Math.min(45L, 45L - Math.max(0L, bufferedDurationMs) / 1000)).toInt() else 5
        }
    }
}
