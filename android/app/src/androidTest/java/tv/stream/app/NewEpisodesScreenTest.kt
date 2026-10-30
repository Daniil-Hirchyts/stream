package tv.stream.app

import org.junit.Assert.*
import android.view.KeyEvent
import android.view.View
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import tv.stream.app.addon.Title

/** New episodes on Home, Library and Details, from an isolated library holding a series the viewer has caught up on. */
class NewEpisodesScreenTest : AppHarness() {
    @Test @Throws(Exception::class) fun aCaughtUpSeriesAnnouncesItsNewEpisodeUntilHidden() {
        val now = System.currentTimeMillis(); val week = 7 * NewEpisodes.DAY_MS
        val videos = JSONArray()
        // The third episode came out this morning; the viewer finished the second just now.
        for (n in 1..3) videos.put(JSONObject().put("id", "followed:1:$n").put("season", 1).put("episode", n)
                .put("title", "Part $n").put("released", Instant.ofEpochMilli(if (n == 3) now - 3_600_000 else now - (3 - n) * week).toString()))
        // No installed add-on knows this provider, so Details shows the saved metadata without a network request.
        val show = Title.parse("unavailable-provider", JSONObject().put("id", "followed").put("type", "series")
                .put("name", "Followed Series").put("videos", videos))!!
        for (n in 1..2) services.store.progress(show, "followed:1:$n", (44 * 60_000).toLong(), (45 * 60_000).toLong(), true)
        services.store.favourite(show, true)
        val card = "new:" + show.key() + ":followed:1:3"

        launch()
        waitUntil("Home leads with the new episode", 10000) { visible("New Episodes") && focused(card) }
        assertEquals("Followed Series. New episode, season 1 episode 3, Part 3", description(card))
        assertTrue("Spotlight says what is new", visible("Followed Series. New episode · S1 E3"))
        assertEquals("Favourite poster says New", "New", flag("favourite:" + show.key()))

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("Details open on the new episode", 10000) { focused("play") && visible("S1 E3") }
        // This fixture has no stream provider, so the "Get add-ons" notice sits between the actions and the seasons.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        waitUntil("the no-provider notice", 5000) { focused("add-source") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        waitUntil("episodes", 5000) { focused("season:1") }
        waitUntil("the new episode card is marked", 5000) { "New" == flag("episode:followed:1:3") }
        assertNull("watched episodes aren't", flag("episode:followed:1:2"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("overview", 5000) { focused("play") }

        tap("More", false)
        waitUntil("menu", 5000) { visible("Hide new episode") && visible("Turn off new episode alerts") }
        tap("Hide new episode", false)
        waitUntil("hidden", 10000) { !visible("Hide new episode") && focused("more") }
        browse!!.onActivity { activity -> activity.root("Home") }
        waitUntil("Home without the notice", 10000) { shown("favourite:" + show.key()) && !visible("New Episodes") }
        assertNull(flag("favourite:" + show.key()))

        // Opening the series without choosing an episode still starts where the viewer is, now unmarked.
        browse!!.onActivity { activity -> activity.openTitle(show, "") }
        waitUntil("Play continues the series", 10000) { focused("play") && visible("S1 E3") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        waitUntil("the no-provider notice", 5000) { focused("add-source") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        waitUntil("episodes", 5000) { focused("season:1") }
        waitUntil("no longer marked", 5000) { shown("episode:followed:1:3") && flag("episode:followed:1:3") == null }
    }

    @Test @Throws(Exception::class) fun libraryMarksSeriesWithNewEpisodes() {
        val now = System.currentTimeMillis()
        val videos = JSONArray()
                .put(JSONObject().put("id", "library:1:1").put("season", 1).put("episode", 1).put("title", "Pilot").put("released", Instant.ofEpochMilli(now - 30 * NewEpisodes.DAY_MS).toString()))
                .put(JSONObject().put("id", "library:2:1").put("season", 2).put("episode", 1).put("title", "Return").put("released", Instant.ofEpochMilli(now - 3_600_000).toString()))
        val show = Title.parse("unavailable-provider", JSONObject().put("id", "library-series").put("type", "series").put("name", "Returning Series").put("videos", videos))!!
        services.store.progress(show, "library:1:1", (50 * 60_000).toLong(), (50 * 60_000).toLong(), true)
        launch()
        waitUntil("Home", 10000) { visible("New Episodes") }
        assertTrue(visible("Returning Series. New season, season 2 episode 1, Return"))
        browse!!.onActivity { activity -> activity.root("Library") }
        waitUntil("Library poster", 10000) { "New" == flag("library:" + show.key()) }
        assertEquals("Returning Series. New", description("library:" + show.key()))
    }

    private fun focused(key: String): Boolean {
        val result = AtomicReference(false)
        browse!!.onActivity { activity -> result.set(activity.currentFocus != null && key == activity.currentFocus!!.tag) }
        return result.get()
    }
    private fun shown(key: String): Boolean = semanticNode(key) != null
    private fun description(key: String): String? =
            semanticNode(key)?.config?.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() }?.joinToString(" ")?.ifEmpty { null }
    /** The word over a tile's artwork, or null when it has none: the painted flag ends the card's description. */
    private fun flag(key: String): String? =
            if (semanticNode("$key:flag") == null) null else description(key)?.substringAfterLast(". ")
}
