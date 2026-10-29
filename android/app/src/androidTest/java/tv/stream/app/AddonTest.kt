package tv.stream.app

import org.junit.Assert.*
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import tv.stream.app.addon.*
import tv.stream.app.data.*
import tv.stream.torrent.*

@RunWith(AndroidJUnit4::class)
class AddonTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: String
    private lateinit var store: TvStore
    private lateinit var client: AddonClient
    private lateinit var repository: AddonRepository
    private lateinit var origin: String
    @Before fun open() {
        origin = InstrumentationRegistry.getArguments().getString("fixtures", "http://127.0.0.1:58133")
        database = "addon-test-" + UUID.randomUUID() + ".db"
        store = TvStore(context, database); client = AddonClient(); repository = AddonRepository(store)
    }
    @After fun close() { repository.close(); client.close(); store.close(); context.deleteDatabase(database) }
    private fun url(variant: String): String { return origin + "/addon/" + variant + "/" + UUID.randomUUID() + "/manifest.json" }
    @Throws(Exception::class)
    private fun install(variant: String): InstalledAddon {
        val url = url(variant)
        repository.newRequest().use { scope -> return repository.install(url, repository.preview(url, scope)) }
    }

    @Test @Throws(Exception::class) fun storeListsBuiltInsAndDirectoryAddonsOnceThenFallsBackToSavedLists() {
        val flaky = origin + "/addon/flaky/" + UUID.randomUUID() + "/manifest.json"
        AddonRepository(store, Collections.singletonList(flaky)).use { directory -> directory.newRequest().use { scope ->
            val found: AddonRepository.Result<AddonListing> = directory.store(scope)
            assertTrue(found.failures.toString(), found.failures.isEmpty())
            assertEquals(java.util.Arrays.asList("Built in", "Official", "Community"), ArrayList(found.byResource.keys))
            assertEquals(BuiltinAddons.INTERNET_ARCHIVE, found.byResource["Built in"]!![0].url)
            val official: List<AddonListing> = found.byResource["Official"]!!
            assertEquals(2, official.size.toLong())
            assertEquals("$origin/addon/good/store/manifest.json", official[0].url)
            assertTrue(official[1].manifest.configurationRequired)
            // Listed once, in the first list naming it.
            assertEquals(1, found.byResource["Community"]!!.size.toLong())
            assertEquals("Community fixture", found.byResource["Community"]!![0].manifest.name)
            val saved: AddonRepository.Result<AddonListing> = directory.store(scope)
            assertTrue(saved.stale); assertFalse(saved.failures.isEmpty())
            assertEquals(2, saved.byResource["Official"]!!.size.toLong())
            // Built-in add-ons review and install without reaching their service.
            val archive = directory.preview(BuiltinAddons.INTERNET_ARCHIVE, scope)
            assertEquals("Internet Archive", archive.name)
            directory.install(BuiltinAddons.INTERNET_ARCHIVE, archive)
        } }
        assertEquals(BuiltinAddons.INTERNET_ARCHIVE, store.addons()[0].address.manifest)
        assertNotNull(StoreScreens.installed(BuiltinAddons.INTERNET_ARCHIVE, "tv.stream.internet-archive", store.addons()))
        // An installed add-on's own lists name the store's sections; the same address is fetched once.
        val lists: InstalledAddon
        val address = "$origin/addon/good/directory/manifest.json"
        lists = repository.newRequest().use { scope -> repository.install(address, repository.preview(address, scope)) }
        AddonRepository(store, Collections.singletonList(address)).use { directory -> directory.newRequest().use { scope ->
            assertEquals(java.util.Arrays.asList("Built in", "Fixture list", "Community"), ArrayList(directory.store(scope).byResource.keys))
        } }
        assertTrue(lists.manifest.declares(AddonManifest.ADDON_CATALOG))
    }

    @Test @Throws(Exception::class) fun configuredPathsAndProviderEpisodeIdsSurviveEncoding() {
        val address = AddonUrl("$origin/addon/good/config%2Fsecret/manifest.json?credential=keep%2Fvalue")
        val extras: MutableMap<String, String> = LinkedHashMap(); extras["search"] = "a/b & café"; extras["skip"] = "0"
        val resource = address.resource("catalog", "movie", "films/list", extras)
        assertTrue(resource.contains("/config%2Fsecret/catalog/movie/films%2Flist/search=a%2Fb%20%26%20caf%C3%A9&skip=0.json?credential=keep%2Fvalue"))
        val answer = client.json(resource, Cancellation(), AddonClient.deadline()).getJSONObject("request")
        assertEquals("films/list", answer.getString("id"))
        assertEquals("a/b & café", answer.getJSONObject("extras").getString("search"))
        val video = "fixture:episode/custom?part=2"
        val stream = client.json(address.resource("stream", "series", video, Collections.emptyMap()), Cancellation(), AddonClient.deadline())
        assertEquals(video, stream.getJSONObject("request").getString("id"))
        assertFalse(address.toString().contains("secret")); assertFalse(address.toString().contains("credential"))
        for (invalid in arrayOf("file:///manifest.json", "https://user:password@example.com/manifest.json", "http://broken host/manifest.json")) {
            try { AddonUrl(invalid); fail(invalid) } catch (e: AddonException) { assertEquals(AddonException.Kind.INVALID_URL, e.kind) }
        }
    }

    @Test @Throws(Exception::class) fun manifestFiltersRequiredExtrasAndSourceLessEpisodesAreRetained() {
        val m = AddonManifest.parse(JSONObject("{\"id\":\"a\",\"name\":\"A\",\"version\":\"1\",\"types\":[\"series\"],\"idPrefixes\":[\"custom:\"],\"resources\":[\"meta\",{\"name\":\"stream\",\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]}],\"catalogs\":[{\"type\":\"series\",\"id\":\"search\",\"extra\":[{\"name\":\"search\",\"isRequired\":true}]}]}"))
        assertTrue(m.handles("meta", "series", "custom:any")); assertFalse(m.handles("meta", "movie", "custom:any"))
        assertTrue(m.handles("stream", "movie", "tt123")); assertFalse(m.handles("stream", "series", "custom:any"))
        assertFalse(m.catalogs[0].listable()); assertTrue(m.catalogs[0].searchable())
        val addon = install("good")
        repository.newRequest().use { scope ->
            val series = repository.metadata(addon.id, "series", "fixture:series", scope).items[0]
            assertEquals(2, series.episodes.size.toLong()); assertTrue(series.episodes[1].streams.isEmpty())
            assertEquals("fixture:episode/custom?part=2", series.episodes[1].id)
        }
        val first = Title.parse("first", JSONObject("{\"id\":\"custom:1\",\"type\":\"movie\",\"name\":\"One\"}"))!!
        val second = Title.parse("second", JSONObject(first.cacheJson()))!!
        assertNotEquals(first.discoveryKey(), second.discoveryKey())
        val imdbA = Title.parse("first", JSONObject("{\"id\":\"tt123\",\"type\":\"movie\",\"name\":\"One\"}"))!!
        val imdbB = Title.parse("second", JSONObject(imdbA.cacheJson()))!!
        assertEquals(imdbA.discoveryKey(), imdbB.discoveryKey())
    }

    @Suppress("UNCHECKED_CAST")
    @Test @Throws(Exception::class) fun sourceTypesIndexesHeadersAndSubtitleHintsStaySeparate() {
        val a = install("good")
        repository.newRequest().use { scope ->
            val sources: List<MediaSource> = repository.streams("movie", "fixture:film", scope).items
            assertEquals(5, sources.size.toLong()); assertEquals(MediaSource.Kind.TORRENT, sources[0].kind)
            assertEquals(0, sources[0].fileIndex.toLong()); assertEquals(2, sources[0].discovery.size.toLong())
            assertNotNull(sources[0].torrentSource())
            assertEquals(MediaSource.Kind.MAGNET, sources[1].kind)
            val direct = sources[2]; assertEquals(MediaSource.Kind.HTTP, direct.kind)
            assertEquals("media-only", direct.requestHeaders["X-Fixture"])
            assertFalse(sources[3].playable()); assertFalse(sources[4].playable())
            val hints: MutableMap<String, String?> = LinkedHashMap(); hints["filename"] = direct.filename; hints["videoHash"] = direct.videoHash
            hints["videoSize"] = java.lang.Long.toString(direct.videoSize); hints["Authorization"] = "never-to-subtitles"
            assertEquals(1, repository.subtitles("movie", "fixture:film", hints as Map<String, String>, scope).items.size.toLong())
            val raw = client.json(a.address.resource("subtitles", "movie", "fixture:film", Collections.singletonMap("filename", direct.filename) as Map<String, String>), scope, AddonClient.deadline())
            assertEquals("film.mp4", raw.getJSONObject("request").getJSONObject("extras").getString("filename"))
            assertFalse(raw.getJSONObject("request").getJSONObject("headers").has("x-fixture"))
        }
        val badIndexes = MediaSource.list("p", JSONObject("{\"streams\":[{\"infoHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"fileIdx\":1.5},{\"infoHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"fileIdx\":4294967296}]}"))
        assertFalse(badIndexes[0].playable()); assertFalse(badIndexes[1].playable())
        assertTrue(MediaSource.validMagnet("magnet:?xt=urn%3Abtih%3Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertFalse(MediaSource.validMagnet("magnet:?xt=urn:btih:invalid"))
    }

    @Test @Throws(Exception::class) fun partialSearchPaginationAndOfflineMetadataCacheWork() {
        val good = install("good"); install("broken")
        repository.newRequest().use { scope ->
            val search: AddonRepository.Result<Title> = repository.search("Generated", scope)
            assertFalse(search.items.isEmpty()); assertFalse(search.failures.isEmpty())
            val catalog: AddonManifest.Catalog = good.manifest.catalogs[0]
            val next = repository.catalogs(Collections.singletonList(AddonRepository.CatalogQuery(good, catalog, Collections.singletonMap("skip", "1"))), scope).items[0]
            assertEquals("fixture:next", next.id)
        }
        val flaky = install("flaky")
        val q = AddonRepository.CatalogQuery(flaky, flaky.manifest.catalogs[0], Collections.emptyMap())
        repository.newRequest().use { scope ->
            assertFalse(repository.catalogs(Collections.singletonList(q), scope).stale)
            val offline: AddonRepository.Result<Title> = repository.catalogs(Collections.singletonList(q), scope)
            assertTrue(offline.stale); assertEquals(1, offline.items.size.toLong()); assertFalse(offline.failures.isEmpty())
        }
    }

    @Test @Throws(Exception::class) fun catalogueSourceOpensTheAdvertisedTorrentOverBitTorrent() {
        val addon = install("good")
        repository.newRequest().use { scope ->
            val title = repository.catalogs(Collections.singletonList(AddonRepository.CatalogQuery(addon,
                    addon.manifest.catalogs[0], Collections.emptyMap())), scope).items[0]
            val source = repository.streams(title.type, title.id, scope).items[0]
            val config = TorrentEngine.Config.localOnly(java.io.File(context.cacheDir, "addon-torrent-test").path)
            config.cacheBytes = 16L shl 20; config.reserveBytes = 0
            TorrentEngine.create(config).use { engine ->
                val peer = InstrumentationRegistry.getArguments().getString("seeder", "127.0.0.1:58134")
                val torrent = engine.add(source.torrentSource().withPeers(peer))
                val files: List<TorrentFile> = engine.awaitMetadata(torrent, 20_000)
                assertFalse(files.isEmpty()); assertEquals(0, source.fileIndex.toLong())
                engine.selectFile(torrent, source.fileIndex)
                val reader = engine.openReader(torrent, source.fileIndex)
                val header = ByteArray(4096)
                assertTrue(engine.read(reader, 0, header, 0, header.size) > 0)
                assertTrue(engine.stats(torrent).getLong("totalDownload") < files[0].size)
                engine.closeReader(reader)
            }
        }
    }

    @Test @Throws(Exception::class) fun malformedCompressedOversizeAndCertificateResponsesAreClassified() {
        for (variant in arrayOf("malformed", "oversized")) {
            val address = AddonUrl(url(variant))
            try { client.json(address.resource("stream", "movie", "fixture:film", Collections.emptyMap()), Cancellation(), AddonClient.deadline()); fail(variant) }
            catch (e: AddonException) { assertEquals(AddonException.Kind.MALFORMED, e.kind) }
        }
        val certificateFixture = InstrumentationRegistry.getArguments().getString("badCertificate", "https://127.0.0.1:58135/manifest.json")
        try { client.json(certificateFixture, Cancellation(), AddonClient.deadline()); fail("Untrusted TLS was accepted") }
        catch (e: AddonException) { assertEquals(AddonException.Kind.CERTIFICATE, e.kind) }
    }

    @Test @Throws(Exception::class) fun obsoleteRequestsCancelAndTransportConcurrencyIsBounded() {
        val slow = install("slow")
        val scope = repository.newRequest(); val error = AtomicReference<Throwable>()
        val search = Thread { try { repository.search("Generated", scope) } catch (e: Throwable) { error.set(e) } }
        search.start(); SystemClock.sleep(300)
        val start = SystemClock.elapsedRealtime(); repository.enable(slow.id, false); search.join(3000)
        assertFalse(search.isAlive); assertTrue(error.get() is AddonException)
        assertEquals(AddonException.Kind.CANCELLED, (error.get() as AddonException).kind)
        assertTrue(SystemClock.elapsedRealtime() - start < 2500)
        val callers: ExecutorService = Executors.newFixedThreadPool(8)
        val futures: MutableList<Future<JSONObject>> = ArrayList()
        val address = AddonUrl(url("concurrency"))
        try {
            for (i in 0 until 8) futures.add(callers.submit(Callable { client.json(address.resource("stream", "movie", "fixture:film", Collections.emptyMap()), Cancellation(), AddonClient.deadline()) }))
            for (f in futures) f.get()
            val stats = client.json("$origin/addon/stats.json", Cancellation(), AddonClient.deadline())
            assertTrue(stats.toString(), stats.getInt("maximum") <= 4); assertTrue(stats.getInt("maximum") >= 2)
        } finally { callers.shutdownNow() }
    }

    @Test @Throws(Exception::class) fun perRequestAndAggregateDeadlinesKeepPartialResults() {
        install("good")
        // Six slow catalogue queries require two waves: the aggregate must stop before two ten-second requests.
        for (i in 0 until 6) install("slow")
        val start = SystemClock.elapsedRealtime()
        repository.newRequest().use { scope ->
            val result: AddonRepository.Result<Title> = repository.search("Generated", scope)
            assertFalse(result.items.isEmpty()); assertFalse(result.failures.isEmpty())
            val elapsed = SystemClock.elapsedRealtime() - start
            assertTrue("Aggregate discovery took $elapsed ms", elapsed < 17_000)
            assertTrue(result.failures.containsValue(AddonException.Kind.TIMEOUT))
        }
    }
}
