package tv.stream.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.stream.app.addon.AddonException
import tv.stream.app.addon.AddonRepository
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class PlaybackPreparationTest {
    @Test fun metadataPublicationWakesAWaitingPlaybackRequest() {
        val entry = entry()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val waiting = worker.submit<Title> { entry.awaitMetadata(Cancellation()) }
            val updated = title("Fetched title")
            entry.publishMetadata(updated)
            assertSame(updated, waiting.get(2, TimeUnit.SECONDS))
            assertEquals(1, entry.version())
        } finally { entry.close(); worker.shutdownNow() }
    }

    @Test fun cancellingAnEntryWakesWaitersAndClosesBothProviderScopes() {
        val entry = entry()
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val waiting = worker.submit<Title> { entered.countDown(); entry.awaitMetadata(Cancellation()) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            entry.close()
            try { waiting.get(2, TimeUnit.SECONDS); fail("Cancelled metadata must not be returned") }
            catch (failure: ExecutionException) {
                assertEquals(AddonException.Kind.CANCELLED, (failure.cause as AddonException).kind)
            }
            assertTrue(entry.scope.isCancelled())
            assertTrue(entry.metadataScope.isCancelled())
        } finally { entry.close(); worker.shutdownNow() }
    }

    @Test fun completedDiscoveryDeliversOneFinalSnapshotWithoutWaiting() {
        val entry = entry()
        try {
            entry.publishSources(AddonRepository.Result(emptyList(), emptyMap(), false))
            entry.finishSources()
            entry.finishMetadata()
            var delivered = 0
            entry.awaitComplete(Cancellation(), Consumer { delivered++; assertTrue(it.items.isEmpty()) })
            assertEquals(1, delivered)
        } finally { entry.close() }
    }

    private fun entry(): PlaybackPreparation.Entry = PlaybackPreparation.Entry(
        title("Title"), "film", 1L, 0L, Cancellation(), Cancellation())
    private fun title(name: String): Title = requireNotNull(Title.parse("fixture",
        JSONObject().put("id", "film").put("type", "movie").put("name", name)))
}
