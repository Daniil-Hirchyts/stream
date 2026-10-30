package tv.stream.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.stream.app.addon.AddonRepository
import tv.stream.app.addon.Title
import tv.stream.app.data.TvStore

/** Title details are reused while fresh, revalidated after, and kept as a stale fallback when the provider fails. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MetadataFreshnessTest {
    private lateinit var context: Context
    private lateinit var store: TvStore

    @Before fun open() {
        FakeKeyStore.install()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test-freshness.db")
        store = TvStore(context, "test-freshness.db")
    }
    @After fun close() { store.close(); context.deleteDatabase("test-freshness.db") }

    @Test fun detailsAreReusedWhileFreshAndRevalidatedAfter() {
        val requests = AtomicInteger()
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val serving = Thread {
            while (!server.isClosed) try {
                server.accept().use { client ->
                    val request = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val path = request.readLine().split(" ")[1]
                    while (request.readLine().isNotEmpty()) { /* headers */ }
                    val body = if (path.endsWith("/manifest.json")) JSONObject().put("id", "test.meta").put("version", "1.0.0").put("name", "Meta")
                            .put("types", JSONArray().put("movie")).put("resources", JSONArray().put("meta")).put("catalogs", JSONArray())
                    else {
                        requests.incrementAndGet()
                        JSONObject().put("meta", JSONObject().put("id", "tt7").put("type", "movie").put("name", "Film ${requests.get()}"))
                    }
                    val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes); out.flush()
                }
            } catch (closed: Exception) { /* the test finished */ }
        }
        serving.start()
        AddonRepository(store).use { addons ->
            val url = "http://127.0.0.1:${server.localPort}/manifest.json"
            val provider = addons.newRequest().use { scope -> addons.install(url, addons.preview(url, scope)).id }
            fun load(maxAge: Long) = addons.newRequest().use { scope -> addons.metadata(provider, "movie", "tt7", scope, maxAge) }

            assertEquals("Film 1", load(AddonRepository.METADATA_FRESH_MS).items.single().name)
            assertEquals(1, requests.get())
            // Opening the title again within the window asks nobody.
            repeat(3) { assertEquals("Film 1", load(AddonRepository.METADATA_FRESH_MS).items.single().name) }
            assertEquals(1, requests.get())
            assertEquals(3, addons.freshHits.get())
            // Older than the window (here 1 ms), or an explicit refresh (0), asks the provider again.
            Thread.sleep(5)
            assertEquals("Film 2", load(1).items.single().name)
            assertEquals("Film 3", load(0).items.single().name)
            assertEquals(3, requests.get())
            // The provider gone, the saved details are still shown, marked stale.
            server.close(); serving.join(5000)
            Thread.sleep(5)
            val fallback = load(1)
            assertEquals("Film 3", fallback.items.single().name)
            assertTrue(fallback.stale)
            assertFalse(load(AddonRepository.METADATA_FRESH_MS).stale)
        }
    }

    /** Answers each provider path with [metas], counting requests per provider (`/a/...`, `/b/...`). */
    private inner class Providers(val meta: (String) -> JSONObject) : AutoCloseable {
        val requests = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val serving = Thread {
            while (!server.isClosed) try {
                server.accept().use { client ->
                    val request = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val path = request.readLine().split(" ")[1]
                    while (request.readLine().isNotEmpty()) { /* headers */ }
                    val who = path.split("/")[1]
                    val body = if (path.endsWith("/manifest.json")) JSONObject().put("id", "test.$who").put("version", "1.0.0").put("name", who)
                            .put("types", JSONArray().put("movie").put("series")).put("resources", JSONArray().put("meta").put("catalog"))
                            .put("catalogs", JSONArray().put(JSONObject().put("type", "movie").put("id", "top").put("name", "Top")))
                    else {
                        requests.computeIfAbsent(who) { AtomicInteger() }.incrementAndGet()
                        meta(path)
                    }
                    val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes); out.flush()
                }
            } catch (closed: Exception) { /* the test finished */ }
        }.apply { isDaemon = true; start() }
        fun url(who: String) = "http://127.0.0.1:${server.localPort}/$who/manifest.json"
        fun asked(who: String) = requests[who]?.get() ?: 0
        override fun close() { server.close(); serving.join(5000) }
    }

    private val secret = "secret-stream.example"
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private fun inlineStreams() = JSONArray().put(JSONObject().put("url", "https://$secret/film.mkv").put("name", "Direct"))
            .put(JSONObject().put("infoHash", hash).put("name", "Torrent"))
    private fun seriesWithStreams() = JSONObject().put("id", "tt9").put("type", "series").put("name", "Show")
            .put("streams", inlineStreams()).put("links", inlineStreams())
            .put("videos", JSONArray().put(JSONObject().put("id", "tt9:1:1").put("title", "Pilot").put("season", 1).put("episode", 1).put("streams", inlineStreams())))

    @Test fun aFreshCopyAsksNoProviderAtAll() {
        Providers { JSONObject().put("meta", JSONObject().put("id", "tt7").put("type", "movie").put("name", "Film")) }.use { p ->
            AddonRepository(store).use { addons ->
                val a = addons.newRequest().use { s -> addons.install(p.url("a"), addons.preview(p.url("a"), s)).id }
                val b = addons.newRequest().use { s -> addons.install(p.url("b"), addons.preview(p.url("b"), s)).id }
                // The two providers run together on a miss; let both finish, so both have saved copies.
                addons.newRequest().use { s -> addons.metadata(a, "movie", "tt7", s, 0) }
                Thread.sleep(200)
                val before = p.asked("a") + p.asked("b")
                val progress = ArrayList<AddonRepository.Result<Title>>()
                val again = addons.newRequest().use { s -> addons.metadata(b, "movie", "tt7", s, java.util.function.Consumer { progress.add(it) }) }
                Thread.sleep(100)
                assertEquals("Film", again.items.single().name)
                assertEquals(1, progress.size)
                assertEquals(before, p.asked("a") + p.asked("b"))
            }
        }
    }

    /** Saved copies can't hold a title's inline streams, so reusing one would leave it with no sources. */
    @Test fun aTitleWithInlineStreamsIsNotReusedAsFresh() {
        Providers { JSONObject().put("meta", seriesWithStreams()) }.use { p ->
            AddonRepository(store).use { addons ->
                val a = addons.newRequest().use { s -> addons.install(p.url("a"), addons.preview(p.url("a"), s)).id }
                repeat(2) {
                    val title = addons.newRequest().use { s -> addons.metadata(a, "series", "tt9", s) }.items.single()
                    assertEquals(2, title.episodes.single().streams.size)
                }
                assertEquals(2, p.asked("a"))
                assertEquals(0, addons.freshHits.get())
                p.close()
                // Still a fallback when the provider has gone: descriptive only, marked stale.
                val fallback = addons.newRequest().use { s -> addons.metadata(a, "series", "tt9", s) }
                assertTrue(fallback.stale)
                assertEquals("Show", fallback.items.single().name)
                assertTrue(fallback.items.single().episodes.single().streams.isEmpty())
            }
        }
    }

    @Test fun playableAddressesAreNeverSaved() {
        Providers { path ->
            if (path.contains("/catalog/")) JSONObject().put("metas", JSONArray().put(seriesWithStreams()))
            else JSONObject().put("meta", seriesWithStreams())
        }.use { p ->
            AddonRepository(store).use { addons ->
                val addon = addons.newRequest().use { s -> addons.install(p.url("a"), addons.preview(p.url("a"), s)) }
                val title = addons.newRequest().use { s -> addons.metadata(addon.id, "series", "tt9", s) }.items.single()
                assertTrue("the fixture must really carry them", title.episodes.single().streams.isNotEmpty())
                addons.newRequest().use { s -> addons.catalogs(addons.homeCatalogs(), s) }
                store.remember(title)
                store.progress(title, "tt9:1:1", 60_000, 120_000, false)
                val saved = listOf(
                    store.cached(addon.address.resource("meta", "series", "tt9", emptyMap()))!!,
                    store.cached(addon.address.resource("catalog", "movie", "top", emptyMap()))!!,
                    store.entry(title.key())!!.title.cacheJson(),
                    title.cacheJson(),
                    tv.stream.app.Route.of("Details", title, "tt9:1:1").apply { cache = title }.save().toString(),
                )
                for (text in saved) {
                    assertFalse(text, text.contains(secret))
                    assertFalse(text, text.contains(hash))
                    assertFalse(text, text.contains("streams"))
                }
            }
        }
    }

    @Test fun theCacheStaysWithinItsBounds() {
        fun rows() = store.readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(data)),0) FROM metadata_cache", null).use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }
        for (i in 0 until 300) store.cache("small$i", "{}")
        assertEquals(256L, rows().first)
        store.clearMetadata()
        val large = "x".repeat(1 shl 20)
        for (i in 0 until 12) store.cache("large$i", large)
        assertTrue("${rows()}", rows().second <= 8 shl 20)
        assertTrue("the newest stays", store.cached("large11") != null)
        store.cache("huge", "x".repeat((2 shl 20) + 1))
        assertEquals(null, store.cached("huge"))
    }

    @Test fun freshnessFollowsTheSavedTime() {
        store.cache("key", "{}")
        fun age(ms: Long) = store.writableDatabase.execSQL("UPDATE metadata_cache SET updated=?", arrayOf(System.currentTimeMillis() - ms))
        assertEquals("{}", store.cachedWithin("key", 60_000))
        assertEquals("an explicit refresh never reuses", null, store.cachedWithin("key", 0))
        age(120_000)
        assertEquals(null, store.cachedWithin("key", 60_000))
        assertEquals("{}", store.cachedWithin("key", AddonRepository.METADATA_FRESH_MS))
        assertEquals("an older copy remains as the fallback", "{}", store.cached("key"))
        age(-120_000)
        assertEquals("a clock moved back can't make a copy fresh forever", null, store.cachedWithin("key", 60_000))
        store.cache("key", "{}", false)
        assertEquals(null, store.cachedWithin("key", Long.MAX_VALUE / 2))
        assertEquals("{}", store.cached("key"))
    }
}
