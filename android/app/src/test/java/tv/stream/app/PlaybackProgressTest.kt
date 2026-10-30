package tv.stream.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.stream.app.addon.Title
import tv.stream.app.data.TvStore

/** Playback records progress, completion, watched marks and language changes in order, with the real store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackProgressTest {
    private lateinit var context: Context
    private lateinit var store: TvStore
    private val show: Title by lazy {
        val videos = JSONArray()
        for (e in 1..3) videos.put(JSONObject().put("id", "s:1:$e").put("title", "E$e").put("season", 1).put("episode", e))
        requireNotNull(Title.parse("fixture", JSONObject().put("id", "s").put("type", "series").put("name", "Show").put("videos", videos)))
    }

    @Before fun open() {
        FakeKeyStore.install()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test-progress.db")
        store = TvStore(context, "test-progress.db")
    }
    @After fun close() { store.close(); context.deleteDatabase("test-progress.db") }

    private fun moment(position: Long, ended: Boolean = false, audio: String = "a1", audioLanguage: String = "en", subtitles: String = "off") =
            PlaybackProgress.Moment(true, true, ended, position, 3_600_000, audio, audioLanguage, subtitles, if (subtitles == "off") "off" else "fr")

    private fun saved(video: String) = store.history(show.key()).firstOrNull { it.video == video }

    @Test fun positionsAndCompletionAreRecordedInOrder() {
        // A direct executor stands in for the application's single persistence thread.
        val progress = PlaybackProgress({ it.run() }) { store }
        assertFalse(progress.save(show, "s:1:1", moment(600_000)))
        assertEquals(600_000, saved("s:1:1")!!.positionMs)
        assertTrue("the end marks it watched once", progress.save(show, "s:1:1", moment(3_600_000, ended = true)))
        assertFalse(progress.save(show, "s:1:1", moment(3_600_000, ended = true)))
        assertTrue(saved("s:1:1")!!.watched)
        assertTrue(progress.watched)
    }

    @Test fun marksAndLanguageChangesAreKept() {
        val progress = PlaybackProgress({ it.run() }) { store }
        assertFalse("the first settled choice is learned, not saved", progress.tracksChanged(moment(1000)))
        assertTrue(progress.tracksChanged(moment(2000, audio = "a2", audioLanguage = "de")))
        progress.save(show, "s:1:2", moment(2000, audio = "a2", audioLanguage = "de", subtitles = "t1"))
        assertEquals("de", store.preference("audioLanguage", ""))
        assertEquals("fr", store.preference("subtitleLanguage", ""))
        assertTrue(progress.mark(show, "s:1:2", moment(2000), true))
        assertFalse("marking again changes nothing", progress.mark(show, "s:1:2", moment(2000), true))
        assertTrue(saved("s:1:2")!!.watched)
        assertTrue(progress.mark(show, "s:1:2", moment(2000), false))
        assertFalse(saved("s:1:2")?.watched ?: false)
    }

    @Test fun subtitleWithoutLanguageDoesNotOverwriteTheSavedPreference() {
        val progress = PlaybackProgress({ it.run() }) { store }
        store.setPreference("subtitleLanguage", "fr")
        progress.tracksChanged(moment(1000))
        progress.save(show, "s:1:1", moment(2000, subtitles = "unknown").copy(subtitleLanguage = null))
        assertEquals("fr", store.preference("subtitleLanguage", ""))
    }

    @Test fun episodeNeighboursAndTheOfferResetPerEpisode() {
        val nav = EpisodeNavigation()
        assertEquals("s:1:2", nav.neighbour(show, "s:1:1", 1)!!.id)
        assertNull(nav.neighbour(show, "s:1:1", -1))
        assertNull(nav.neighbour(show, "s:1:3", 1))
        assertNull(nav.neighbour(show, "missing", 1))
        nav.playing("s:1:1"); nav.dismiss()
        assertTrue(nav.dismissed)
        nav.playing("s:1:1")
        assertTrue("same episode: still dismissed", nav.dismissed)
        nav.playing("s:1:2")
        assertFalse("a new episode may be offered", nav.dismissed)
    }
}
