package tv.stream.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.stream.app.addon.AddonRepository
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title
import tv.stream.app.data.TvStore

/**
 * Which new episodes Home announces, and when it stops, with the real encrypted store and a loopback metadata add-on.
 * Episodes finished in a test are saved "now" ([T0]); release dates and the clock passed in sit around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewEpisodesTest {
    private var T0: Long = 0
    private lateinit var context: Context
    private lateinit var store: TvStore

    @Before fun open() {
        FakeKeyStore.install()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test-new-episodes.db")
        store = TvStore(context, "test-new-episodes.db")
        T0 = System.currentTimeMillis()
    }
    @After fun close() { store.close(); context.deleteDatabase("test-new-episodes.db") }

    @Test @Throws(Exception::class) fun announcesTheNextEpisodeOnceItAirsAfterTheViewerCaughtUp() {
        val show = series("caught-up", episode(1, 1, T0 - 14 * DAY), episode(1, 2, T0 - 7 * DAY), episode(1, 3, T0 + 2 * DAY))
        finish(show, 1, 1); finish(show, 1, 2)
        assertTrue("Not yet aired", news(T0 + DAY).isEmpty())
        val notice: NewEpisodes.Notice? = news(T0 + 3 * DAY).get(show.key())
        assertNotNull(notice)
        assertEquals("caught-up:1:3", notice!!.episode.id)
        assertEquals("New episode", notice.label())
        assertEquals("caught-up:1:3", NewEpisodes.nextUp(show, progress(show), T0 + 3 * DAY)!!.id)
        assertNull("An episode that hasn't aired isn't offered as next", NewEpisodes.nextUp(show, progress(show), T0))
    }

    @Test @Throws(Exception::class) fun aViewerWhoIsBehindOrHasStartedTheEpisodeIsNotTold() {
        val behind = series("behind", episode(1, 1, T0 - 60 * DAY), episode(1, 2, T0 - 30 * DAY), episode(1, 3, T0 + DAY))
        finish(behind, 1, 1)
        val started = series("started", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY))
        finish(started, 1, 1)
        // Opening it for a moment doesn't count; Continue Watching starts at 30 seconds.
        store.progress(started, "started:1:2", 5_000L, 45 * 60_000L, false)
        assertEquals(Collections.singleton(started.key()), news(T0 + 2 * DAY).keys)
        store.progress(started, "started:1:2", 10 * 60_000L, 45 * 60_000L, false)
        assertTrue(news(T0 + 2 * DAY).isEmpty())
    }

    @Test @Throws(Exception::class) fun aSeasonReleasedAtOnceIsNotNewWhileItIsBeingWatched() {
        val drop = T0 - 3 * HOUR
        val binge = series("binge", episode(1, 1, drop), episode(1, 2, drop), episode(1, 3, drop))
        finish(binge, 1, 1); finish(binge, 1, 2)
        assertTrue(news(T0 + HOUR).isEmpty())
        // A weekly episode dated the morning of the day the viewer finished the last one still counts.
        val weekly = series("weekly", episode(1, 1, T0 - 8 * DAY), episode(1, 2, T0 - 20 * HOUR), episode(1, 3, T0 - 2 * HOUR))
        finish(weekly, 1, 1); finish(weekly, 1, 2)
        assertTrue(news(T0 + HOUR).containsKey(weekly.key()))
    }

    @Test @Throws(Exception::class) fun countsNewEpisodesAndNamesANewSeason() {
        val season = series("season", episode(0, 1, T0 + DAY), episode(1, 1, T0 - 400 * DAY), episode(1, 2, T0 - 393 * DAY),
                episode(2, 1, T0 + DAY), episode(2, 2, T0 + DAY), episode(2, 3, T0 + 30 * DAY))
        finish(season, 1, 1); finish(season, 1, 2)
        val notice: NewEpisodes.Notice? = news(T0 + 2 * DAY).get(season.key())
        assertEquals("Specials are skipped", "season:2:1", notice!!.episode.id)
        assertEquals("New season", notice.label())
        assertEquals(Arrays.asList("season:2:1", "season:2:2"), java.util.ArrayList(notice.episodes))
        val weekly = series("count", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY), episode(1, 3, T0 + 8 * DAY))
        finish(weekly, 1, 1)
        assertEquals("2 new episodes", news(T0 + 9 * DAY).get(weekly.key())!!.label())
    }

    @Test @Throws(Exception::class) fun favouritesAreFollowedBeforeTheyArePlayedAndOtherTitlesAreNot() {
        val upcoming = series("premiere", episode(1, 1, T0 + 5 * DAY))
        store.favourite(upcoming, true)
        val opened = series("opened", episode(1, 1, T0 + 5 * DAY))
        store.remember(opened)
        val found: Map<String, NewEpisodes.Notice> = news(T0 + 6 * DAY)
        assertEquals("premiere:1:1", found.get(upcoming.key())!!.episode.id)
        assertFalse(found.containsKey(opened.key()))
        assertEquals("No episode finished: the first is next", "opened:1:1", NewEpisodes.nextUp(opened, progress(opened), T0 + 6 * DAY)!!.id)
    }

    @Test @Throws(Exception::class) fun aNoticeEndsAfterSevenDaysOnHomeOrAMonthAndStaysGone() {
        val shown = series("shown", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY))
        finish(shown, 1, 1)
        var now = T0 + 2 * DAY
        // 30-hour steps always reach another local day, whatever the time zone does.
        var day = 0
        while (day < NewEpisodes.SHOWN_DAYS) {
            assertTrue("Shown on day " + (day + 1), NewEpisodes.current(store, store.library(false), store.history(false), now, true).containsKey(shown.key()))
            day++; now += 30 * HOUR
        }
        assertTrue("Ignored for a week", NewEpisodes.current(store, store.library(false), store.history(false), now, true).isEmpty())
        assertTrue("Not shown again", news(now + DAY).isEmpty())

        val unseen = series("unseen", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY))
        finish(unseen, 1, 1)
        assertTrue(news(T0 + 2 * DAY).containsKey(unseen.key()))
        assertTrue("Elsewhere than Home, looking doesn't count", news(T0 + 2 * DAY + 20 * DAY).containsKey(unseen.key()))
        assertFalse("A month after it appeared", news(T0 + 2 * DAY + NewEpisodes.LIFETIME_MS).containsKey(unseen.key()))
    }

    @Test @Throws(Exception::class) fun hidingEndsOneNoticeAndTheNextEpisodeBringsAnother() {
        val show = series("hide", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 - 12 * HOUR), episode(1, 3, T0 + 3 * DAY))
        finish(show, 1, 1)
        assertTrue(news(T0).containsKey(show.key()))
        NewEpisodes.hide(store, show.key())
        assertFalse(news(T0).containsKey(show.key()))
        finish(show, 1, 2)
        assertEquals("hide:1:3", news(T0 + 4 * DAY).get(show.key())!!.episode.id)
    }

    @Test @Throws(Exception::class) fun alertsTurnedOffStayOffUntilTurnedOnAndLeaveWithTheLibraryEntry() {
        val show = series("mute", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY))
        finish(show, 1, 1)
        NewEpisodes.mute(store, show.key(), true)
        assertTrue(NewEpisodes.muted(store, show.key()))
        assertTrue(news(T0 + 2 * DAY).isEmpty())
        NewEpisodes.mute(store, show.key(), false)
        assertTrue(news(T0 + 2 * DAY).containsKey(show.key()))
        NewEpisodes.mute(store, show.key(), true)
        store.removeLibrary(show.key())
        assertTrue(news(T0 + 2 * DAY).isEmpty())
        finish(show, 1, 1)
        assertFalse("Removing it forgot the choice", NewEpisodes.muted(store, show.key()))
    }

    @Test @Throws(Exception::class) fun aSeriesLeftForTwoYearsIsNoLongerFollowedUnlessItIsAFavourite() {
        val later = T0 + NewEpisodes.FOLLOW_MS + DAY
        val left = series("left", episode(1, 1, T0 - 7 * DAY), episode(1, 2, later - HOUR))
        finish(left, 1, 1)
        assertTrue(news(later).isEmpty())
        store.favourite(left, true)
        assertTrue(news(later).containsKey(left.key()))
    }

    @Test fun releaseDatesAreReadAsInstantsOrBroadcastDays() {
        assertEquals(Instant.parse("2026-10-20T01:30:00Z").toEpochMilli(), NewEpisodes.aired("2026-10-20T01:30:00.000Z"))
        assertEquals(Instant.parse("2026-10-20T00:00:00Z").toEpochMilli(), NewEpisodes.aired("2026-10-20"))
        assertEquals(-1L, NewEpisodes.aired(""))
        assertEquals(-1L, NewEpisodes.aired("unknown date"))
        assertEquals(-1L, NewEpisodes.aired(null))
    }

    @Test @Throws(Exception::class) fun checksFetchFollowedSeriesTwiceADayAndSaveTheirNewEpisodes() {
        val requests = AtomicInteger()
        val meta = arrayOfNulls<JSONObject>(1)
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val serving = Thread {
            // A minimal add-on: its manifest, and the series' metadata as the test last set it.
            while (!server.isClosed()) try {
                server.accept().use { client: Socket ->
                    val request = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val path = request.readLine().split(" ")[1]
                    while (!request.readLine().isEmpty()) { /* headers */ }
                    val body = if (path.endsWith("/manifest.json")) JSONObject().put("id", "test.meta").put("version", "1.0.0").put("name", "Meta")
                            .put("types", JSONArray().put("series")).put("resources", JSONArray().put("meta")).put("catalogs", JSONArray()).toString()
                    else JSONObject().put("meta", meta[0]).toString()
                    if (path.startsWith("/meta/")) requests.incrementAndGet()
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val out: OutputStream = client.getOutputStream()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                    out.write(bytes); out.flush()
                }
            } catch (closed: Exception) { /* the test finished */ }
        }
        serving.start()
        try {
            AddonRepository(store).use { addons ->
                val url = "http://127.0.0.1:" + server.getLocalPort() + "/manifest.json"
                val provider: String = addons.newRequest().use { scope: Cancellation -> addons.install(url, addons.preview(url, scope)).id }
                val saved = Title.parse(provider, json("checked", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 - DAY)))!!
                finish(saved, 1, 1); finish(saved, 1, 2)
                val ignored = Title.parse(provider, json("muted", episode(1, 1, T0 - 7 * DAY)))!!
                finish(ignored, 1, 1)
                NewEpisodes.mute(store, ignored.key(), true)
                meta[0] = json("checked", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 - DAY), episode(1, 3, T0 + HOUR))

                val placed = entry(saved).updatedMs
                assertTrue(NewEpisodes.check(store, addons, T0 + 2 * HOUR))
                assertEquals("The muted series isn't asked about", 1, requests.get())
                val notice: NewEpisodes.Notice? = news(T0 + 2 * HOUR).get(saved.key())
                assertEquals("checked:1:3", notice!!.episode.id)
                assertEquals("checked:1:3", entry(saved).title.episodes.get(2).id)
                assertEquals("Its place in Recently played is kept", placed, entry(saved).updatedMs)
                assertFalse("Checked recently", NewEpisodes.check(store, addons, T0 + 3 * HOUR))
                assertEquals(1, requests.get())
                assertFalse("Asked again after twelve hours; nothing changed", NewEpisodes.check(store, addons, T0 + 2 * HOUR + NewEpisodes.CHECK_MS))
                assertEquals(2, requests.get())
            }
        } finally { server.close(); serving.join(5000) }
    }

    private fun news(now: Long): Map<String, NewEpisodes.Notice> { return NewEpisodes.current(store, now) }
    private fun entry(show: Title): TvStore.LibraryEntry {
        for (e in store.library(false)) if (e.title.key().equals(show.key())) return e
        throw AssertionError("Not in the library")
    }
    private fun finish(show: Title, season: Int, number: Int) {
        store.progress(show, show.id + ":" + season + ":" + number, 44 * 60_000L, 45 * 60_000L, true)
    }
    private fun progress(show: Title): Map<String, TvStore.Progress> {
        val out: MutableMap<String, TvStore.Progress> = HashMap()
        for (p in store.history(show.key())) out.put(p.video, p)
        return out
    }

    companion object {
        private val HOUR: Long = 3_600_000L
        private val DAY: Long = NewEpisodes.DAY_MS

        @Throws(Exception::class)
        private fun series(id: String, vararg episodes: JSONObject): Title { return Title.parse("provider", json(id, *episodes))!! }
        @Throws(Exception::class)
        private fun json(id: String, vararg episodes: JSONObject): JSONObject {
            val videos = JSONArray()
            for (e in episodes) videos.put(JSONObject(e.toString()).put("id", id + ":" + e.getInt("season") + ":" + e.getInt("episode")))
            return JSONObject().put("id", id).put("type", "series").put("name", "Series " + id).put("videos", videos)
        }
        @Throws(Exception::class)
        private fun episode(season: Int, number: Int, released: Long): JSONObject {
            return JSONObject().put("season", season).put("episode", number).put("title", "Episode " + number)
                    .put("released", Instant.ofEpochMilli(released).toString())
        }
    }
}
