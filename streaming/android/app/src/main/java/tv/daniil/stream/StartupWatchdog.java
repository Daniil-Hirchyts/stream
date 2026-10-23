package tv.daniil.stream;

/** Main-thread startup deadline: allow useful transfer progress, with a finite total wait. */
final class StartupWatchdog {
    private long started, progressed, downloaded, pausedAt = -1;
    void start(long now) { started = progressed = now; downloaded = 0; pausedAt = -1; }
    void setPaused(boolean paused, long now) {
        if (paused && pausedAt < 0) pausedAt = now;
        else if (!paused && pausedAt >= 0) {
            long duration = now - pausedAt; started += duration; progressed += duration; pausedAt = -1;
        }
    }
    void observe(long bytes, long now) {
        if (bytes > downloaded) { downloaded = bytes; if (pausedAt < 0) progressed = now; }
    }
    boolean expired(long now) { return pausedAt < 0 && (now - progressed > 60_000 || now - started > 180_000); }
}
