package tv.daniil.stream;

/** Compressed-media consumption, never swarm throughput. Seek/discontinuity samples are discarded. */
final class PlaybackDemand {
    private boolean averageKnown;
    private long position = -1, bytes, estimate, sampledAt;
    void reset() { position = -1; sampledAt = 0; estimate = 0; averageKnown = false; }
    long estimate(long fileBytes, long durationMs, long readBytes, long mediaPositionMs, long now) {
        long average = fileBytes > 0 && durationMs > 0 ? Math.max(16_000, (long) (fileBytes * 1000.0 / durationMs)) : 1_250_000;
        if (estimate == 0 || !averageKnown && fileBytes > 0 && durationMs > 0) estimate = average;
        averageKnown |= fileBytes > 0 && durationMs > 0;
        long elapsed = now - sampledAt, advanced = mediaPositionMs - position;
        if (position >= 0 && elapsed >= 5000 && advanced > 0 && advanced < elapsed * 4 + 2000 && readBytes > bytes) {
            long consumed = (long) ((readBytes - bytes) * 1000.0 / advanced);
            consumed = Math.max(average / 2, Math.min(average * 4, consumed));
            estimate = (estimate * 3 + consumed) / 4;
        }
        if (position < 0 || elapsed >= 5000) { position = mediaPositionMs; bytes = readBytes; sampledAt = now; }
        return estimate;
    }
    static int aheadSeconds(boolean firstFrame, long bufferedDurationMs) {
        return firstFrame ? (int) Math.max(5, Math.min(45, 45 - Math.max(0, bufferedDurationMs) / 1000)) : 5;
    }
}
