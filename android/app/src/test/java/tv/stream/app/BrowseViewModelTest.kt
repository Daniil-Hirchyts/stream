package tv.stream.app

import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.stream.app.addon.Cancellation
import tv.stream.app.ui.Page

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrowseViewModelTest {
    @Test fun navigationClosesProviderSocketsAndRejectsLateProgressAndCompletion() {
        val worker = Executors.newSingleThreadExecutor()
        val model = BrowseViewModel()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Cancellation()
        var socketClosed = false
        first.add { socketClosed = true }
        try {
            model.begin(first)
            model.load(worker) { _, progress ->
                started.countDown()
                // Simulate a provider that finishes after cancellation despite interruption.
                while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
                progress(Page().title("Old partial"))
                Page().title("Old page")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            model.cancel()
            assertTrue(socketClosed)
            assertTrue(first.isCancelled())
            model.begin(Cancellation())
            model.load(worker) { _, _ -> Page().title("Current page") }
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val current = model.state.value as PageScreenState.Content
            assertEquals("Current page", current.content.render().title)
            assertFalse(current.partial)
            assertEquals(model.generation, current.generation)
        } finally {
            release.countDown(); model.cancel(); worker.shutdownNow()
        }
    }

    @Test fun detachmentDiscardsQueuedActionsAndLeavesApplicationWorkerAvailable() {
        val worker = Executors.newSingleThreadExecutor()
        val model = BrowseViewModel()
        var delivered = false
        try {
            model.work(worker, Callable { "finished" }) { delivered = true }
            worker.submit {}.get(5, TimeUnit.SECONDS)
            model.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(delivered)
            assertEquals(PageScreenState.Idle, model.state.value)
            assertEquals("still available", worker.submit(Callable { "still available" }).get())
        } finally { model.cancel(); worker.shutdownNow() }
    }
}
