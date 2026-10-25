package tv.daniil.stream.addon;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Four sockets at most, with bounded admission and foreground work ahead of preparation. */
final class ProviderExecutor extends ThreadPoolExecutor {
    private final AtomicLong sequence = new AtomicLong();
    ProviderExecutor() { super(4, 4, 0, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>()); }
    synchronized void execute(Runnable command, Cancellation scope) {
        if (getQueue().size() >= 64) throw new RejectedExecutionException("Provider queue full");
        Work work = new Work(command, scope, sequence.getAndIncrement());
        work.abort = () -> remove(work); scope.add(work.abort);
        try { super.execute(work); } catch (RejectedExecutionException failure) { scope.remove(work.abort); throw failure; }
    }
    synchronized void promote() {
        Object[] queued = getQueue().toArray();
        for (Object item : queued) if (getQueue().remove(item)) getQueue().offer((Runnable) item);
    }
    private static final class Work implements Runnable, Comparable<Work> {
        final Runnable command;
        final Cancellation scope;
        final long sequence;
        Runnable abort;
        Work(Runnable command, Cancellation scope, long sequence) { this.command = command; this.scope = scope; this.sequence = sequence; }
        @Override public void run() { try { command.run(); } finally { scope.remove(abort); } }
        @Override public int compareTo(Work other) {
            int order = Integer.compare(scope.priority(), other.scope.priority());
            return order == 0 ? Long.compare(sequence, other.sequence) : order;
        }
    }
}
