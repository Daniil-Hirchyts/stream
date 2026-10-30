package tv.stream.app.ui

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedRequestsTest {
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue("timed out", System.nanoTime() < deadline)
            Thread.sleep(2)
        }
    }

    @Test fun concurrentRequestsForOneKeyRunOnceAndAllGetTheResult() {
        val gate = CountDownLatch(1)
        val runs = AtomicInteger()
        val results = CopyOnWriteArrayList<String>()
        SharedRequests<String>(8, 1) { it.run() }.use { r ->
            repeat(5) { r.request("poster", { runs.incrementAndGet(); gate.await(); "bitmap" }, { results.add(it) }, {}) }
            gate.countDown()
            waitFor { results.size == 5 }
            assertEquals(1, runs.get())
            assertEquals(4, r.joined)
        }
    }

    @Test fun cancellingOneBindingKeepsWorkOthersStillNeed() {
        val gate = CountDownLatch(1)
        val results = CopyOnWriteArrayList<String>()
        SharedRequests<String>(8, 1) { it.run() }.use { r ->
            val first = r.request("logo", { gate.await(); "logo" }, { results.add("first") }, { results.add("first failed") })
            r.request("logo", { "unused" }, { results.add("second") }, { results.add("second failed") })
            first.cancel()
            gate.countDown()
            waitFor { results.isNotEmpty() }
            Thread.sleep(50)
            assertEquals(listOf("second"), results.toList())
        }
    }

    @Test fun workNobodyWaitsForStopsAndReportsNothing() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val results = CopyOnWriteArrayList<String>()
        SharedRequests<String>(8, 1) { it.run() }.use { r ->
            val ticket = r.request("backdrop", { c ->
                c.add(Runnable { stopped.countDown() })
                started.countDown()
                stopped.await(5, TimeUnit.SECONDS)
                c.check()
                "late"
            }, { results.add(it) }, { results.add("failed") })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            ticket.cancel()
            assertTrue("cancelling the last binding cancels the download", stopped.await(5, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertTrue(results.isEmpty())
        }
    }

    @Test fun overflowDropsTheOldestWaitingKeysAndTellsTheirRequesters() {
        val gate = CountDownLatch(1)
        val failed = CopyOnWriteArrayList<String>()
        val done = CopyOnWriteArrayList<String>()
        SharedRequests<String>(3, 1) { it.run() }.use { r ->
            r.request("busy", { gate.await(); "busy" }, { done.add(it) }, {})
            waitFor { r.started == 1 }
            for (i in 0 until 6) r.request("tile$i", { "tile$i" }, { done.add(it) }, { failed.add("tile$i") })
            // Three may wait; the three oldest were dropped and told so at once.
            assertEquals(listOf("tile0", "tile1", "tile2"), failed.toList())
            gate.countDown()
            waitFor { done.size == 4 }
            // The newest run first.
            assertEquals(listOf("busy", "tile5", "tile4", "tile3"), done.toList())
        }
    }

    @Test fun closingStopsIdleWorkersWithoutAnUncaughtException() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            val r = SharedRequests<String>(8, 2) { it.run() }
            Thread.sleep(50) // the workers are waiting
            r.close()
            Thread.sleep(100)
            assertTrue("$uncaught", uncaught.isEmpty())
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
    }

    @Test fun cancelAllSilencesEveryRequester() {
        val gate = CountDownLatch(1)
        val results = CopyOnWriteArrayList<String>()
        SharedRequests<String>(8, 1) { it.run() }.use { r ->
            r.request("a", { gate.await(); "a" }, { results.add(it) }, { results.add("a failed") })
            r.request("b", { "b" }, { results.add(it) }, { results.add("b failed") })
            r.cancelAll()
            gate.countDown()
            Thread.sleep(100)
            assertTrue(results.isEmpty())
        }
    }
}
