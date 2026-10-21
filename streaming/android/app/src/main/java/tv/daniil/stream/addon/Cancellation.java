package tv.daniil.stream.addon;

import java.util.ArrayList;
import java.util.List;

/** One navigation/configuration generation. Cancelling closes active sockets as well as queued work. */
public final class Cancellation implements AutoCloseable {
    private final List<Runnable> actions = new ArrayList<>();
    private boolean cancelled;

    public synchronized boolean isCancelled() { return cancelled; }
    public void check() throws AddonException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new AddonException(AddonException.Kind.CANCELLED, "Request cancelled");
        }
    }
    public void add(Runnable action) {
        synchronized (this) { if (!cancelled) { actions.add(action); return; } }
        action.run();
    }
    public synchronized void remove(Runnable action) { actions.remove(action); }
    @Override public void close() {
        List<Runnable> copy;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            copy = new ArrayList<>(actions);
            actions.clear();
        }
        for (Runnable r : copy) r.run();
    }
}
