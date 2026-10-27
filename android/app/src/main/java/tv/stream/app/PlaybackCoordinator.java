package tv.stream.app;

import android.os.SystemClock;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import tv.stream.torrent.TorrentEngine;

/** App-worker-owned native lifetime and main-thread playback budgets, independent of the screen. */
final class PlaybackCoordinator {
    enum State { RESOLVING, CONNECTING, BUFFERING, PLAYING, RECOVERING, CLOSING, FAILED }
    volatile TorrentEngine engine;
    volatile long torrent, fileBytes;
    volatile int fileIndex = -1;
    private State state = State.RESOLVING;
    private long tapped, budgetStart, budgetPaused, attempt, pausedAt = -1, pausedTotal, attemptPaused;
    private final Map<String, Long> stages = new LinkedHashMap<>();
    void begin(long tapped) {
        this.tapped = tapped > 0 ? tapped : SystemClock.elapsedRealtime();
        attempt = budgetStart = this.tapped; budgetPaused = 0; pausedAt = -1; pausedTotal = attemptPaused = 0; stages.clear(); stage(State.RESOLVING);
    }
    synchronized void mark(String name) { markAt(name, SystemClock.elapsedRealtime()); }
    synchronized void markAt(String name, long at) { stages.putIfAbsent(name, at - tapped - pausedTotal); }
    synchronized void stage(State state) { this.state = state; stages.putIfAbsent(state.name(), SystemClock.elapsedRealtime() - tapped - pausedTotal); }
    void attempt() { attempt = SystemClock.elapsedRealtime(); attemptPaused = pausedTime(attempt); stage(State.CONNECTING); }
    void paused(boolean paused, long now) {
        if (paused && pausedAt < 0) pausedAt = now;
        if (!paused && pausedAt >= 0) { pausedTotal += now - pausedAt; pausedAt = -1; }
    }
    private long pausedTime(long now) { return pausedTotal + (pausedAt < 0 ? 0 : now - pausedAt); }
    long elapsed(long now) { return now - tapped - pausedTime(now); }
    long attemptElapsed(long now) { return now - attempt - pausedTime(now) + attemptPaused; }
    void recover(long now) { budgetStart = now; budgetPaused = pausedTime(now); stage(State.RECOVERING); }
    long remaining(long now) { return Math.max(0, 60_000 - (now - budgetStart - pausedTime(now) + budgetPaused)); }
    boolean expired(long now) { return pausedAt < 0 && state != State.PLAYING && now - budgetStart - pausedTime(now) + budgetPaused >= 60_000; }
    /** Only the application's serial playback worker calls these resource operations. */
    void remove() throws Exception {
        if (engine != null && torrent != 0) {
            try { engine.remove(torrent); } catch (Exception failure) { close(); }
        }
        torrent = 0; fileBytes = 0; fileIndex = -1;
    }
    void close() {
        TorrentEngine old = engine; engine = null; torrent = 0; fileBytes = 0; fileIndex = -1;
        if (old != null) old.close();
    }
    synchronized long firstAvMs(boolean audio) {
        Long video = stages.get("FIRST_VIDEO_FRAME"), sound = stages.get("FIRST_AUDIO_OUTPUT");
        return video == null || audio && sound == null ? 0 : Math.max(video, sound == null ? 0 : sound);
    }
    synchronized JSONObject diagnostics() { return new JSONObject(stages); }
}
