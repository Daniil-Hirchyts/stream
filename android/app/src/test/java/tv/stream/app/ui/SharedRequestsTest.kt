package tv.stream.app.ui

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stream.app.addon.AddonException
import tv.stream.app.addon.Cancellation

class SharedRequestsTest {
    @Test fun cancellingTheScreenSilencesResultsAlreadyQueuedForDelivery() {
        val queued = CopyOnWriteArrayList<Runnable>()
        val results = CopyOnWriteArrayList<String>()
        SharedRequests<String>(8, 1) { queued.add(it) }.use { r ->
            r.request("finished", { "old" }, { results.add(it) }, { results.add("old failed") })
            waitFor { queued.size == 1 }
            r.cancelAll()
            queued.removeAt(0).run()
            assertTrue(results.isEmpty())
            r.request("new", { "new" }, { results.add(it) }, { results.add("new failed") })
            waitFor { queued.size == 1 }
            queued.removeAt(0).run()
            assertEquals(listOf("new"), results.toList())
        }
    }

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

    @Test fun anOutOfMemoryFailsOneRequestAndTheWorkerLivesOn() {
        val results = CopyOnWriteArrayList<String>()
        SharedRequests<String>(8, 1) { it.run() }.use { r ->
            r.request("huge", { throw OutOfMemoryError() }, { results.add(it) }, { results.add("failed") })
            waitFor { results.size == 1 }
            r.request("huge", { "retried" }, { results.add(it) }, { results.add("failed") })
            waitFor { results.size == 2 }
            assertEquals(listOf("failed", "retried"), results.toList())
        }
    }

    /** Runs get() on a thread of its own; the outcome is its value or the name of what it threw. */
    private fun SharedWork<String>.join(key: String, request: Cancellation, outcomes: MutableList<String>, work: (Cancellation) -> String) =
        Thread {
            outcomes.add(try { get(key, request, work) } catch (e: Exception) { (e as? AddonException)?.kind?.name ?: e.javaClass.simpleName })
        }.apply { isDaemon = true; start() }

    @Test fun waitersShareOneRunAndItsResult() {
        val gate = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<String>()
        val work = SharedWork<String>()
        val threads = (0 until 4).map { work.join("image", Cancellation(), outcomes) { gate.await(); "bytes" } }
        waitFor { work.runs == 1 }
        Thread.sleep(50)
        gate.countDown()
        threads.forEach { it.join(5000) }
        assertEquals(List(4) { "bytes" }, outcomes.toList())
        assertEquals(1, work.runs)
    }

    @Test fun theFirstCallersCancellationLeavesTheWorkToTheOthers() {
        val gate = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val first = CopyOnWriteArrayList<String>()
        val second = CopyOnWriteArrayList<String>()
        val work = SharedWork<String>()
        val leader = Cancellation()
        val running = { c: Cancellation -> c.add(Runnable { stopped.countDown() }); gate.await(); "bytes" }
        val a = work.join("image", leader, first, running)
        waitFor { work.runs == 1 }
        val b = work.join("image", Cancellation(), second, running)
        Thread.sleep(50)
        leader.close()
        assertEquals("the work continues for the other waiter", 1L, stopped.count)
        gate.countDown()
        a.join(5000); b.join(5000)
        assertEquals(listOf("CANCELLED"), first.toList())
        assertEquals(listOf("bytes"), second.toList())
        assertEquals(1, work.runs)
    }

    @Test fun aCancelledWaiterWakesAtOnceWhileTheWorkContinues() {
        val gate = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<String>()
        val work = SharedWork<String>()
        val a = work.join("image", Cancellation(), CopyOnWriteArrayList()) { gate.await(); "bytes" }
        waitFor { work.runs == 1 }
        val waiter = Cancellation()
        val b = work.join("image", waiter, outcomes) { "unused" }
        Thread.sleep(50)
        waiter.close()
        b.join(5000)
        assertEquals(listOf("CANCELLED"), outcomes.toList())
        gate.countDown(); a.join(5000)
    }

    @Test fun whenEveryWaiterLeavesTheWorkStopsAndTheNextCallerStartsAgain() {
        val stopped = CountDownLatch(1)
        val work = SharedWork<String>()
        val only = Cancellation()
        val a = work.join("image", only, CopyOnWriteArrayList()) { c ->
            c.add(Runnable { stopped.countDown() }); stopped.await(5, TimeUnit.SECONDS); c.check(); "late"
        }
        waitFor { work.runs == 1 }
        Thread.sleep(50)
        only.close()
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        a.join(5000)
        assertEquals("fresh", work.get("image", Cancellation()) { "fresh" })
        assertEquals(2, work.runs)
    }

    @Test fun aFailureReachesItsWaitersAndIsNotRemembered() {
        val work = SharedWork<String>()
        val outcomes = CopyOnWriteArrayList<String>()
        work.join("image", Cancellation(), outcomes) { throw java.io.IOException("bad") }.join(5000)
        assertEquals(listOf("IOException"), outcomes.toList())
        assertEquals("good", work.get("image", Cancellation()) { "good" })
    }
}
