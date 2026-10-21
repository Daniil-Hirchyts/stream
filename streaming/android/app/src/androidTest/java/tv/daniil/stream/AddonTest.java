package tv.daniil.stream;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import tv.daniil.stream.addon.*;
import tv.daniil.stream.data.*;
import tv.daniil.torrent.*;

@RunWith(AndroidJUnit4.class)
public final class AddonTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String database;
    private TvStore store;
    private AddonClient client;
    private AddonRepository repository;
    private String origin;
    @Before public void open() {
        origin = InstrumentationRegistry.getArguments().getString("fixtures", "http://127.0.0.1:58133");
        database = "addon-test-" + UUID.randomUUID() + ".db";
        store = new TvStore(context, database); client = new AddonClient(); repository = new AddonRepository(store);
    }
    @After public void close() { repository.close(); client.close(); store.close(); context.deleteDatabase(database); }
    private String url(String variant) { return origin + "/addon/" + variant + "/" + UUID.randomUUID() + "/manifest.json"; }
    private InstalledAddon install(String variant) throws Exception {
        String url = url(variant);
        try (Cancellation scope = repository.newRequest()) { return repository.install(url, repository.preview(url, scope)); }
    }

    @Test public void configuredPathsAndProviderEpisodeIdsSurviveEncoding() throws Exception {
        AddonUrl address = new AddonUrl(origin + "/addon/good/config%2Fsecret/manifest.json?credential=keep%2Fvalue");
        Map<String, String> extras = new LinkedHashMap<>(); extras.put("search", "a/b & café"); extras.put("skip", "0");
        String resource = address.resource("catalog", "movie", "films/list", extras);
        assertTrue(resource.contains("/config%2Fsecret/catalog/movie/films%2Flist/search=a%2Fb%20%26%20caf%C3%A9&skip=0.json?credential=keep%2Fvalue"));
        JSONObject answer = client.json(resource, new Cancellation(), AddonClient.deadline()).getJSONObject("request");
        assertEquals("films/list", answer.getString("id"));
        assertEquals("a/b & café", answer.getJSONObject("extras").getString("search"));
        String video = "fixture:episode/custom?part=2";
        JSONObject stream = client.json(address.resource("stream", "series", video, Collections.emptyMap()), new Cancellation(), AddonClient.deadline());
        assertEquals(video, stream.getJSONObject("request").getString("id"));
        assertFalse(address.toString().contains("secret")); assertFalse(address.toString().contains("credential"));
        for (String invalid : new String[]{"file:///manifest.json", "https://user:password@example.com/manifest.json", "http://broken host/manifest.json"}) {
            try { new AddonUrl(invalid); fail(invalid); } catch (AddonException e) { assertEquals(AddonException.Kind.INVALID_URL, e.kind); }
        }
    }

    @Test public void manifestFiltersRequiredExtrasAndSourceLessEpisodesAreRetained() throws Exception {
        AddonManifest m = AddonManifest.parse(new JSONObject("{\"id\":\"a\",\"name\":\"A\",\"version\":\"1\",\"types\":[\"series\"],\"idPrefixes\":[\"custom:\"],\"resources\":[\"meta\",{\"name\":\"stream\",\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]}],\"catalogs\":[{\"type\":\"series\",\"id\":\"search\",\"extra\":[{\"name\":\"search\",\"isRequired\":true}]}]}"));
        assertTrue(m.handles("meta", "series", "custom:any")); assertFalse(m.handles("meta", "movie", "custom:any"));
        assertTrue(m.handles("stream", "movie", "tt123")); assertFalse(m.handles("stream", "series", "custom:any"));
        assertFalse(m.catalogs.get(0).listable()); assertTrue(m.catalogs.get(0).searchable());
        InstalledAddon addon = install("good");
        try (Cancellation scope = repository.newRequest()) {
            Title series = repository.metadata(addon.id, "series", "fixture:series", scope).items.get(0);
            assertEquals(2, series.episodes.size()); assertTrue(series.episodes.get(1).streams.isEmpty());
            assertEquals("fixture:episode/custom?part=2", series.episodes.get(1).id);
        }
        Title first = Title.parse("first", new JSONObject("{\"id\":\"custom:1\",\"type\":\"movie\",\"name\":\"One\"}"));
        Title second = Title.parse("second", new JSONObject(first.cacheJson()));
        assertNotEquals(first.discoveryKey(), second.discoveryKey());
        Title imdbA = Title.parse("first", new JSONObject("{\"id\":\"tt123\",\"type\":\"movie\",\"name\":\"One\"}"));
        Title imdbB = Title.parse("second", new JSONObject(imdbA.cacheJson()));
        assertEquals(imdbA.discoveryKey(), imdbB.discoveryKey());
    }

    @Test public void sourceTypesIndexesHeadersAndSubtitleHintsStaySeparate() throws Exception {
        InstalledAddon a = install("good");
        try (Cancellation scope = repository.newRequest()) {
            List<MediaSource> sources = repository.streams("series", "fixture:episode/custom?part=2", scope).items;
            assertEquals(5, sources.size()); assertEquals(MediaSource.Kind.TORRENT, sources.get(0).kind);
            assertEquals(0, sources.get(0).fileIndex); assertEquals(2, sources.get(0).discovery.size());
            assertNotNull(sources.get(0).torrentSource());
            assertEquals(MediaSource.Kind.MAGNET, sources.get(1).kind);
            MediaSource direct = sources.get(2); assertEquals(MediaSource.Kind.HTTP, direct.kind);
            assertEquals("media-only", direct.requestHeaders.get("X-Fixture"));
            assertFalse(sources.get(3).playable()); assertFalse(sources.get(4).playable());
            Map<String, String> hints = new LinkedHashMap<>(); hints.put("filename", direct.filename); hints.put("videoHash", direct.videoHash);
            hints.put("videoSize", Long.toString(direct.videoSize)); hints.put("Authorization", "never-to-subtitles");
            assertEquals(1, repository.subtitles("movie", "fixture:film", hints, scope).items.size());
            JSONObject raw = client.json(a.address.resource("subtitles", "movie", "fixture:film", Collections.singletonMap("filename", direct.filename)), scope, AddonClient.deadline());
            assertEquals("film.mp4", raw.getJSONObject("request").getJSONObject("extras").getString("filename"));
            assertFalse(raw.getJSONObject("request").getJSONObject("headers").has("x-fixture"));
        }
        List<MediaSource> badIndexes = MediaSource.list("p", new JSONObject("{\"streams\":[{\"infoHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"fileIdx\":1.5},{\"infoHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"fileIdx\":4294967296}]}"));
        assertFalse(badIndexes.get(0).playable()); assertFalse(badIndexes.get(1).playable());
        assertTrue(MediaSource.validMagnet("magnet:?xt=urn%3Abtih%3Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertFalse(MediaSource.validMagnet("magnet:?xt=urn:btih:invalid"));
    }

    @Test public void partialSearchPaginationAndOfflineMetadataCacheWork() throws Exception {
        InstalledAddon good = install("good"); install("broken");
        try (Cancellation scope = repository.newRequest()) {
            AddonRepository.Result<Title> search = repository.search("Generated", scope);
            assertFalse(search.items.isEmpty()); assertFalse(search.failures.isEmpty());
            AddonManifest.Catalog catalog = good.manifest.catalogs.get(0);
            Title next = repository.catalogs(Collections.singletonList(new AddonRepository.CatalogQuery(good, catalog, Collections.singletonMap("skip", "1"))), scope).items.get(0);
            assertEquals("fixture:next", next.id);
        }
        InstalledAddon flaky = install("flaky");
        AddonRepository.CatalogQuery q = new AddonRepository.CatalogQuery(flaky, flaky.manifest.catalogs.get(0), Collections.emptyMap());
        try (Cancellation scope = repository.newRequest()) {
            assertFalse(repository.catalogs(Collections.singletonList(q), scope).stale);
            AddonRepository.Result<Title> offline = repository.catalogs(Collections.singletonList(q), scope);
            assertTrue(offline.stale); assertEquals(1, offline.items.size()); assertFalse(offline.failures.isEmpty());
        }
    }

    @Test public void catalogueSourceOpensTheAdvertisedTorrentOverBitTorrent() throws Exception {
        InstalledAddon addon = install("good");
        try (Cancellation scope = repository.newRequest()) {
            Title title = repository.catalogs(Collections.singletonList(new AddonRepository.CatalogQuery(addon,
                    addon.manifest.catalogs.get(0), Collections.emptyMap())), scope).items.get(0);
            MediaSource source = repository.streams(title.type, title.id, scope).items.get(0);
            TorrentEngine.Config config = TorrentEngine.Config.localOnly(new java.io.File(context.getCacheDir(), "addon-torrent-test").getPath());
            config.cacheBytes = 16L << 20; config.reserveBytes = 0;
            try (TorrentEngine engine = TorrentEngine.create(config)) {
                String peer = InstrumentationRegistry.getArguments().getString("seeder", "127.0.0.1:58134");
                long torrent = engine.add(source.torrentSource().withPeers(peer));
                List<TorrentFile> files = engine.awaitMetadata(torrent, 20_000);
                assertFalse(files.isEmpty()); assertEquals(0, source.fileIndex);
                engine.selectFile(torrent, source.fileIndex);
                long reader = engine.openReader(torrent, source.fileIndex);
                byte[] header = new byte[4096];
                assertTrue(engine.read(reader, 0, header, 0, header.length) > 0);
                assertTrue(engine.stats(torrent).getLong("totalDownload") < files.get(0).size);
                engine.closeReader(reader);
            }
        }
    }

    @Test public void malformedCompressedOversizeAndCertificateResponsesAreClassified() throws Exception {
        for (String variant : new String[]{"malformed", "oversized"}) {
            AddonUrl address = new AddonUrl(url(variant));
            try { client.json(address.resource("stream", "movie", "fixture:film", Collections.emptyMap()), new Cancellation(), AddonClient.deadline()); fail(variant); }
            catch (AddonException e) { assertEquals(AddonException.Kind.MALFORMED, e.kind); }
        }
        String certificateFixture = InstrumentationRegistry.getArguments().getString("badCertificate", "https://127.0.0.1:58135/manifest.json");
        try { client.json(certificateFixture, new Cancellation(), AddonClient.deadline()); fail("Untrusted TLS was accepted"); }
        catch (AddonException e) { assertEquals(AddonException.Kind.CERTIFICATE, e.kind); }
    }

    @Test public void obsoleteRequestsCancelAndTransportConcurrencyIsBounded() throws Exception {
        InstalledAddon slow = install("slow");
        Cancellation scope = repository.newRequest(); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread search = new Thread(() -> { try { repository.search("Generated", scope); } catch (Throwable e) { error.set(e); } });
        search.start(); SystemClock.sleep(300);
        long start = SystemClock.elapsedRealtime(); repository.enable(slow.id, false); search.join(3000);
        assertFalse(search.isAlive()); assertTrue(error.get() instanceof AddonException);
        assertEquals(AddonException.Kind.CANCELLED, ((AddonException) error.get()).kind);
        assertTrue(SystemClock.elapsedRealtime() - start < 2500);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        List<Future<JSONObject>> futures = new ArrayList<>();
        AddonUrl address = new AddonUrl(url("concurrency"));
        try {
            for (int i = 0; i < 8; i++) futures.add(callers.submit(() -> client.json(address.resource("stream", "movie", "fixture:film", Collections.emptyMap()), new Cancellation(), AddonClient.deadline())));
            for (Future<JSONObject> f : futures) f.get();
            JSONObject stats = client.json(origin + "/addon/stats.json", new Cancellation(), AddonClient.deadline());
            assertTrue(stats.toString(), stats.getInt("maximum") <= 4); assertTrue(stats.getInt("maximum") >= 2);
        } finally { callers.shutdownNow(); }
    }

    @Test public void perRequestAndAggregateDeadlinesKeepPartialResults() throws Exception {
        install("good");
        // Six slow catalogue queries require two waves: the aggregate must stop before two ten-second requests.
        for (int i = 0; i < 6; i++) install("slow");
        long start = SystemClock.elapsedRealtime();
        try (Cancellation scope = repository.newRequest()) {
            AddonRepository.Result<Title> result = repository.search("Generated", scope);
            assertFalse(result.items.isEmpty()); assertFalse(result.failures.isEmpty());
            assertTrue(SystemClock.elapsedRealtime() - start < 17_000);
            assertTrue(result.failures.containsValue(AddonException.Kind.TIMEOUT));
        }
    }
}
