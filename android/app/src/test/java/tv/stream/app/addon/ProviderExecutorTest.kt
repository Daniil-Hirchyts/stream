package tv.stream.app.addon

import java.util.*
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class ProviderExecutorTest {
    @Test @Throws(Exception::class) fun playbackRunsBeforeQueuedPreparationAndCancelledWorkLeavesTheQueue() {
        val executor = ProviderExecutor()
        val started = CountDownLatch(4); val completed = CountDownLatch(2)
        val release = Semaphore(0)
        val order: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        try {
            for (i in 0 until 4) executor.execute(Runnable {
                started.countDown(); try { release.acquire() } catch (error: InterruptedException) { Thread.currentThread().interrupt() }
            }, Cancellation())
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val preparation = Cancellation(); preparation.priority(2)
            val playback = Cancellation(); playback.priority(0)
            val cancelled = Cancellation()
            executor.execute(Runnable { fail("Cancelled queued request ran") }, cancelled); cancelled.close()
            executor.execute(Runnable { order.add("preparation"); completed.countDown() }, preparation)
            // Keep other workers blocked after one admission, making the ordering deterministic.
            executor.execute(Runnable { order.add("playback"); completed.countDown() }, playback)
            assertEquals(2, executor.getQueue().size)
            release.release(); assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(Arrays.asList("playback", "preparation"), order)
        } finally { release.release(4); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
}
