package tv.stream.app;

/** Presentation progress is independent of the audio clock. Ignore buffering, pauses and seek settling. */
final class VideoFrameWatchdog {
    private long settledAt, windowAt, frames, drops, lastFrameAt, lastRendered;
    private int badWindows;
    void reset(long now, long frames, long drops) { settledAt = now + 2000; windowAt = lastFrameAt = now; this.frames = lastRendered = frames; this.drops = drops; badWindows = 0; }
    boolean unhealthy(long now, long rendered, long dropped, boolean advancingAndBuffered, boolean videoEnded) {
        // Audio and subtitles can outlast video. Only renderer EOS proves there are no frames left to expect.
        if (!advancingAndBuffered || videoEnded) { reset(now, rendered, dropped); return false; }
        if (rendered != lastRendered) lastFrameAt = now;
        lastRendered = rendered;
        if (now < settledAt) { frames = rendered; drops = dropped; windowAt = now; return false; }
        if (now - lastFrameAt > 2000) return true;
        if (now - windowAt >= 5000) {
            long shown = Math.max(0, rendered - frames), missed = Math.max(0, dropped - drops);
            badWindows = shown + missed >= 20 && missed * 5 > shown + missed ? badWindows + 1 : 0;
            frames = rendered; drops = dropped; windowAt = now;
        }
        return badWindows >= 2;
    }
}
