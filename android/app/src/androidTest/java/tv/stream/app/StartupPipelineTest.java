package tv.stream.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import android.net.Uri;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import tv.stream.app.addon.*;
import tv.stream.app.data.*;

/** Deliberately stalled providers and extensionless media, served inside emulator loopback only. */
@RunWith(AndroidJUnit4.class)
public final class StartupPipelineTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private TvStore store;
    private AddonRepository repository;
    private PlaybackPreparation preparation;
    private Loopback server;
    private String database;
    @Before public void open() throws Exception {
        database = "test-startup-" + UUID.randomUUID() + ".db"; store = new TvStore(context, database);
        repository = new AddonRepository(store); preparation = new PlaybackPreparation(repository); server = new Loopback();
    }
    @After public void close() throws Exception {
        preparation.close(); repository.close(); server.close(); store.close(); context.deleteDatabase(database);
    }
    private InstalledAddon install(String name) throws Exception {
        String address = server.origin + "/" + name + "/manifest.json";
        try (Cancellation scope = repository.newRequest()) { return repository.install(address, repository.preview(address, scope)); }
    }
    @Test public void fastSourcesReachPlaybackBeforeASlowProviderAndAreReused() throws Exception {
        InstalledAddon fast = install("fast"); install("slow");
        Title title = Title.parse(fast.id, new JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Film"));
        PlaybackPreparation.Entry entry = preparation.prepare(title, title.id);
        try (Cancellation caller = repository.newRequest()) {
            caller.priority(0); long start = SystemClock.elapsedRealtime();
            List<MediaSource> sources = entry.awaitCandidate(caller, "");
            assertFalse(sources.isEmpty()); assertFalse("The slow provider is still blocked", entry.complete);
            assertTrue("No all-provider barrier", SystemClock.elapsedRealtime() - start < 3000);
            assertTrue("Fast result consumed within 250 ms plus scheduling tolerance", SystemClock.elapsedRealtime() - server.fastSentAt < 300);
            assertEquals(1, server.fastStreams.get());
            assertSame(entry, preparation.prepare(title, title.id));
            assertSame(entry, preparation.take(entry.token, title, title.id));
            assertFalse(entry.awaitCandidate(caller, "").isEmpty());
            assertEquals("Playback must reuse the discovered address", 1, server.fastStreams.get());
            assertTrue(entry.claimed);
        }
        repository.enable(fast.id, false);
        assertTrue("Configuration changes invalidate prepared addresses", entry.scope.isCancelled());
    }
    @Test public void claimedPreparationSurvivesDetailsReplacementAndKeepsItsIdentity() throws Exception {
        install("fast");
        Title title = Title.parse("catalogue-owner", new JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Film"));
        PlaybackPreparation.Entry first = preparation.claim(title, title.id);
        try (Cancellation caller = repository.newRequest()) {
            caller.priority(0); assertFalse(first.awaitCandidate(caller, "").isEmpty());
            assertEquals("Metadata enrichment must retain the library identity", title.key(), first.title.key());
            Title other = Title.parse("catalogue-owner", new JSONObject().put("id", "fixture:other").put("type", "movie").put("name", "Other"));
            preparation.prepare(other, other.id); preparation.leave();
            assertFalse("Details owns no cancellation rights over Play", first.scope.isCancelled());
            assertSame(first, preparation.take(first.token, title, title.id));
            first.close();
        }
    }
    @Test public void explicitRefreshMakesANewProviderRequest() throws Exception {
        InstalledAddon fast = install("fast");
        Title title = Title.parse(fast.id, new JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Film"));
        try (Cancellation caller = repository.newRequest()) {
            PlaybackPreparation.Entry first = preparation.prepare(title, title.id); assertFalse(first.awaitCandidate(caller, "").isEmpty());
            preparation.invalidate(title, title.id);
            PlaybackPreparation.Entry second = preparation.prepare(title, title.id); assertNotSame(first, second);
            assertFalse(second.awaitCandidate(caller, "").isEmpty()); assertEquals(2, server.fastStreams.get());
        }
    }
    @Test public void sourcesWaitForInlineEpisodeStreamsFromLateMetadata() throws Exception {
        InstalledAddon addon = install("inline");
        Title title = Title.parse(addon.id, new JSONObject().put("id", "fixture:series").put("type", "series").put("name", "Series"));
        PlaybackPreparation.Entry entry = preparation.prepare(title, "fixture:series:one");
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (Cancellation caller = repository.newRequest()) {
            Future<?> pending = reader.submit(() -> { try { entry.awaitComplete(caller, ignored -> {}); } catch (Exception error) { throw new RuntimeException(error); } });
            assertTrue(server.inlineStreams.await(3, TimeUnit.SECONDS));
            long end = SystemClock.elapsedRealtime() + 3000;
            while (!entry.complete && SystemClock.elapsedRealtime() < end) SystemClock.sleep(10);
            assertTrue(entry.complete); assertFalse("Metadata still owns possible inline sources", pending.isDone());
            server.inlineMetadata.countDown(); pending.get(3, TimeUnit.SECONDS);
            assertEquals(1, entry.result().items.size()); assertEquals(1, entry.sources().size());
        } finally { reader.shutdownNow(); }
    }
    @Test public void extensionlessProgressiveResponseKeepsItsNormalReadTimeout() throws Exception {
        try (Cancellation scope = new Cancellation(); HttpMediaResolver resolved = HttpMediaResolver.resolve(server.origin + "/slow-body", Collections.emptyMap(), scope)) {
            DataSource source = resolved.createDataSource();
            try { source.open(new DataSpec.Builder().setUri(resolved.uri).build());
                byte[] bytes = new byte[8192]; int count, total = 0;
                while ((count = source.read(bytes, 0, bytes.length)) >= 0) total += count;
                assertEquals(8192, total); assertEquals(1, server.mediaRequests.get());
            } finally { source.close(); }
        }
    }
    @Test public void probeTimeoutDoesNotBlockFollowingProbesAndKeepsHttpStatus() throws Exception {
        long start = SystemClock.elapsedRealtime();
        try (Cancellation scope = new Cancellation()) {
            try { HttpMediaResolver.resolve(server.origin + "/blocked", Collections.emptyMap(), scope).close(); fail("Unbounded probe"); }
            catch (IOException expected) { assertTrue(SystemClock.elapsedRealtime() - start < 3500); }
            try { HttpMediaResolver.resolve(server.origin + "/expired", Collections.emptyMap(), scope).close(); fail("Expired response accepted"); }
            catch (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException expected) { assertEquals(401, expected.responseCode); }
            try (HttpMediaResolver resolved = HttpMediaResolver.resolve(server.origin + "/signed-play", Collections.emptyMap(), scope)) { assertEquals("application/x-mpegURL", resolved.mime); }
        }
    }
    @Test public void redirectedDashAndReopenedRangesUseTheRightContent() throws Exception {
        try (Cancellation scope = new Cancellation()) {
            try (HttpMediaResolver resolved = HttpMediaResolver.resolve(server.origin + "/redirect", Collections.emptyMap(), scope)) { assertEquals("application/dash+xml", resolved.mime); assertEquals("/dash-play", resolved.uri.getPath()); }
            try (HttpMediaResolver resolved = HttpMediaResolver.resolve(server.origin + "/binary", Collections.emptyMap(), scope)) {
                DataSource source = resolved.createDataSource(); byte[] bytes = new byte[32];
                source.open(new DataSpec.Builder().setUri(resolved.uri).build()); assertEquals(3, source.read(bytes, 0, 3)); source.close();
                source.open(new DataSpec.Builder().setUri(resolved.uri).setPosition(4).setLength(3).build());
                assertEquals(3, source.read(bytes, 0, bytes.length)); assertEquals("456", new String(bytes, 0, 3, StandardCharsets.US_ASCII));
                assertEquals(-1, source.read(bytes, 0, bytes.length)); source.close();
            }
        }
    }

    @Test public void mediaProbeRejectsTheUntrustedTlsFixture() throws Exception {
        String address = InstrumentationRegistry.getArguments().getString("badCertificate", "https://127.0.0.1:58135/manifest.json");
        try (Cancellation scope = new Cancellation()) {
            try (HttpMediaResolver ignored = HttpMediaResolver.resolve(address, Collections.emptyMap(), scope)) { fail("Untrusted certificate accepted"); }
            catch (javax.net.ssl.SSLException expected) { /* platform trust and hostname checks remain enabled */ }
        }
    }

    @Test public void extensionlessHlsUsesContentAndReusesTheOpenedResponse() throws Exception {
        try (Cancellation scope = new Cancellation(); HttpMediaResolver resolved = HttpMediaResolver.resolve(server.origin + "/signed-play", Collections.emptyMap(), scope)) {
            assertEquals("application/x-mpegURL", resolved.mime);
            DataSource source = resolved.createDataSource();
            source.open(new DataSpec.Builder().setUri(resolved.uri).build());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[64]; int count;
            while ((count = source.read(buffer, 0, buffer.length)) >= 0) bytes.write(buffer, 0, count);
            source.close();
            assertTrue(bytes.toString(StandardCharsets.UTF_8.name()).startsWith("#EXTM3U"));
            assertEquals("Probe and loader share a request", 1, server.mediaRequests.get());
        }
    }
    static final class Loopback implements AutoCloseable {
        final ServerSocket socket;
        final String origin;
        final ExecutorService clients = Executors.newFixedThreadPool(4);
        final CountDownLatch unblock = new CountDownLatch(1), inlineStreams = new CountDownLatch(1), inlineMetadata = new CountDownLatch(1);
        final AtomicInteger fastStreams = new AtomicInteger(), mediaRequests = new AtomicInteger();
        final List<Socket> open = Collections.synchronizedList(new ArrayList<>());
        final Thread accept;
        volatile boolean stopped;
        volatile long fastSentAt;
        String mediaRedirect;
        Loopback() throws IOException {
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")); origin = "http://127.0.0.1:" + socket.getLocalPort();
            accept = new Thread(() -> {
                while (!stopped) try { Socket client = socket.accept(); open.add(client); clients.execute(() -> respond(client)); }
                catch (IOException | RejectedExecutionException ignored) { if (stopped) return; }
            }, "startup-loopback"); accept.start();
        }
        void respond(Socket client) {
            try (Socket connection = client) {
                BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                String request = input.readLine(); if (request == null) return; String path = request.split(" ")[1];
                while (true) { String header = input.readLine(); if (header == null || header.isEmpty()) break; }
                String body;
                if (mediaRedirect != null && path.equals("/movie.mp4")) { connection.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); return; }
                if (mediaRedirect != null && path.equals("/movie-slow.mp4")) { connection.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: " + mediaRedirect + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); return; }
                if (path.equals("/redirect")) { connection.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /dash-play\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); return; }
                if (path.equals("/expired")) { connection.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); return; }
                if (path.equals("/blocked")) { unblock.await(6, TimeUnit.SECONDS); return; }
                if (path.equals("/slow-body")) {
                    mediaRequests.incrementAndGet(); connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 8192\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().write(new byte[4096]); connection.getOutputStream().flush(); Thread.sleep(2500); connection.getOutputStream().write(new byte[4096]); return;
                }
                if (path.endsWith("manifest.json")) {
                    String id = path.contains("/slow/") ? "slow" : path.contains("/inline/") ? "inline" : "fast";
                    body = new JSONObject().put("id", id).put("name", id).put("version", "1.0.0")
                            .put("types", new JSONArray().put("movie").put("series")).put("resources", new JSONArray().put("meta").put("stream")).put("catalogs", new JSONArray()).toString();
                } else if (path.equals("/binary")) { body = "0123456789";
                } else if (path.equals("/dash-play")) { body = "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>";
                } else if (path.contains("/inline/stream/")) { inlineStreams.countDown(); body = "{\"streams\":[]}";
                } else if (path.contains("/inline/meta/")) {
                    inlineMetadata.await(5, TimeUnit.SECONDS);
                    body = new JSONObject().put("meta", new JSONObject().put("id", "fixture:series").put("type", "series").put("name", "Series")
                            .put("videos", new JSONArray().put(new JSONObject().put("id", "fixture:series:one").put("name", "One")
                                    .put("streams", new JSONArray().put(new JSONObject().put("name", "Inline").put("url", origin + "/movie.mp4")))))).toString();
                } else if (path.equals("/signed-play")) {
                    mediaRequests.incrementAndGet(); body = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nsegment.ts\n#EXT-X-ENDLIST\n";
                } else {
                    if (path.contains("/slow/")) unblock.await(15, TimeUnit.SECONDS);
                    if (path.contains("/stream/")) {
                        if (path.contains("/fast/")) fastStreams.incrementAndGet();
                        body = new JSONObject().put("streams", new JSONArray().put(new JSONObject().put("name", "Local fixture").put("url", origin + (path.contains("/slow/") ? "/movie-slow.mp4" : "/movie.mp4")))).toString();
                    } else body = new JSONObject().put("meta", new JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Prepared film")).toString();
                }
                if (path.contains("/fast/stream/")) fastSentAt = SystemClock.elapsedRealtime();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().write(bytes);
            } catch (Exception ignored) { /* cancelling the client intentionally closes its response */ }
            finally { open.remove(client); }
        }
        @Override public void close() throws Exception {
            stopped = true; unblock.countDown(); inlineMetadata.countDown(); socket.close();
            synchronized (open) { for (Socket client : open) client.close(); }
            clients.shutdownNow(); accept.join(2000); assertTrue(clients.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
