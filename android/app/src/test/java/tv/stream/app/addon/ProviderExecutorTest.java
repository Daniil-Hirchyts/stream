package tv.stream.app.addon;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;

public final class ProviderExecutorTest {
    @Test public void playbackRunsBeforeQueuedPreparationAndCancelledWorkLeavesTheQueue() throws Exception {
        ProviderExecutor executor = new ProviderExecutor();
        CountDownLatch started = new CountDownLatch(4), completed = new CountDownLatch(2);
        Semaphore release = new Semaphore(0);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < 4; i++) executor.execute(() -> {
                started.countDown(); try { release.acquire(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }, new Cancellation());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Cancellation preparation = new Cancellation(); preparation.priority(2);
            Cancellation playback = new Cancellation(); playback.priority(0);
            Cancellation cancelled = new Cancellation();
            executor.execute(() -> fail("Cancelled queued request ran"), cancelled); cancelled.close();
            executor.execute(() -> { order.add("preparation"); completed.countDown(); }, preparation);
            // Keep other workers blocked after one admission, making the ordering deterministic.
            executor.execute(() -> { order.add("playback"); completed.countDown(); }, playback);
            assertEquals(2, executor.getQueue().size());
            release.release(); assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("playback", "preparation"), order);
        } finally { release.release(4); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }
}
