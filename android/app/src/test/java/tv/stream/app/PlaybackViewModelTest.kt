package tv.stream.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.MediaSource
import tv.stream.playback.PlaybackErrors
import java.util.concurrent.FutureTask

class PlaybackViewModelTest {
    @Test fun cancellationInvalidatesCallbacksAndClosesSocketsAndQueuedWork() {
        val model = PlaybackViewModel()
        val work = model.attach()
        val request = Cancellation()
        var socketsClosed = 0
        request.add(Runnable { socketsClosed++ })
        work.scope = request
        val task = FutureTask { "pending" }
        work.task = task
        val generation = work.generation

        work.cancel()

        assertTrue(work.generation > generation)
        assertTrue(request.isCancelled())
        assertEquals(1, socketsClosed)
        assertTrue(task.isCancelled)
        assertNull(work.scope)
        assertNull(work.task)
    }

    @Test fun aRecreatedScreenClosesItsPreviousRequestOwner() {
        val model = PlaybackViewModel()
        val previous = model.attach()
        val oldRequest = Cancellation()
        previous.scope = oldRequest
        val next = model.attach()
        val nextGeneration = next.generation
        assertNotEquals(previous.generation, nextGeneration)
        previous.cancel()

        assertTrue(previous.isClosed)
        assertTrue(oldRequest.isCancelled())
        assertFalse(next.isClosed)
        assertEquals(nextGeneration, next.generation)
    }

    @Test fun retriesPreferAnotherProviderAndKeepEarlierSnapshotsStable() {
        val model = PlaybackViewModel()
        val first = source("one", "first")
        val sameProvider = source("one", "second")
        val anotherProvider = source("two", "third")
        val input = mutableListOf(first, sameProvider, anotherProvider)
        model.candidates = input
        val initial = model.selection.value
        input.clear()
        assertEquals(3, initial.candidates.size)

        model.attempt(first)
        assertEquals(1, model.attempts)
        assertEquals(2, model.nextCandidate())
        assertTrue(initial.attemptedSources.isEmpty())
        assertEquals(0, initial.attempts)
        model.attempt(anotherProvider)
        assertEquals(1, model.nextCandidate())
        model.resetAttempts()
        assertEquals(0, model.attempts)
        assertEquals(0, model.nextCandidate())
    }

    @Test fun presentationTransitionsKeepTheOriginalFailureDescription() {
        val model = PlaybackViewModel()
        model.loading("peers", "Finding peers", "Source", false)
        assertEquals(PlaybackPresentation.Loading("peers", "Finding peers", "Source", false), model.presentation.value)
        model.ready()
        assertSame(PlaybackPresentation.Ready, model.presentation.value)
        val error = PlaybackErrors.Description("opening", "Could not play", "No peers", "", 0, true)
        model.failed(error)
        assertSame(error, (model.presentation.value as PlaybackPresentation.Failed).error)
        model.released()
        assertSame(PlaybackPresentation.Released, model.presentation.value)
    }

    private fun source(provider: String, id: String): MediaSource = MediaSource.list(provider,
        JSONObject().put("streams", org.json.JSONArray().put(JSONObject().put("url", "https://example.invalid/$id.mp4")))).single()
}
