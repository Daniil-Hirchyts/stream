package tv.stream.app;

/** Main-thread useful-data clock. PlaybackCoordinator owns the overall deadline. */
final class StartupWatchdog {
    private long progressed, downloaded, pausedAt = -1;
    void start(long now) { progressed = now; downloaded = 0; pausedAt = -1; }
    void setPaused(boolean paused, long now) {
        if (paused && pausedAt < 0) pausedAt = now;
        else if (!paused && pausedAt >= 0) {
            long duration = now - pausedAt; progressed += duration; pausedAt = -1;
        }
    }
    void observe(long bytes, long now) {
        if (bytes > downloaded) { downloaded = bytes; if (pausedAt < 0) progressed = now; }
    }
    long quietMs(long now) { return pausedAt >= 0 ? 0 : now - progressed; }
    static boolean bufferingStalled(boolean buffering, long began, long quiet, long now, long threshold) {
        return buffering && began > 0 && now - began >= threshold && quiet >= threshold;
    }
}
