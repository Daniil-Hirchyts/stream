package tv.stream.app

import org.junit.Assert.*
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import android.net.Uri
import java.net.*
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.json.*
import org.junit.*
import org.junit.runner.RunWith
import tv.stream.app.addon.*
import tv.stream.app.data.*

/** Deliberately stalled providers and extensionless media, served inside emulator loopback only. */
@RunWith(AndroidJUnit4::class)
class StartupPipelineTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: TvStore
    private lateinit var repository: AddonRepository
    private lateinit var preparation: PlaybackPreparation
    private lateinit var server: Loopback
    private lateinit var database: String
    @Before @Throws(Exception::class) fun open() {
        database = "test-startup-" + UUID.randomUUID() + ".db"; store = TvStore(context, database)
        repository = AddonRepository(store); preparation = PlaybackPreparation(repository); server = Loopback()
    }
    @After @Throws(Exception::class) fun close() {
        preparation.close(); repository.close(); server.close(); store.close(); context.deleteDatabase(database)
    }
    @Throws(Exception::class)
    private fun install(name: String): InstalledAddon {
        val address = server.origin + "/" + name + "/manifest.json"
        repository.newRequest().use { scope -> return repository.install(address, repository.preview(address, scope)) }
    }
    @Test @Throws(Exception::class) fun fastSourcesReachPlaybackBeforeASlowProviderAndAreReused() {
        val fast = install("fast"); install("slow")
        val title = Title.parse(fast.id, JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Film"))!!
        val entry = preparation.prepare(title, title.id)
        repository.newRequest().use { caller ->
            caller.priority(0); val start = SystemClock.elapsedRealtime()
            val sources: List<MediaSource> = entry.awaitCandidate(caller, "")
            assertFalse(sources.isEmpty()); assertFalse("The slow provider is still blocked", entry.complete)
            assertTrue("No all-provider barrier", SystemClock.elapsedRealtime() - start < 3000)
            assertTrue("Fast result consumed within 250 ms plus scheduling tolerance", SystemClock.elapsedRealtime() - server.fastSentAt < 300)
            assertEquals(1, server.fastStreams.get().toLong())
            assertSame(entry, preparation.prepare(title, title.id))
            assertSame(entry, preparation.take(entry.token, title, title.id))
            assertFalse(entry.awaitCandidate(caller, "").isEmpty())
            assertEquals("Playback must reuse the discovered address", 1, server.fastStreams.get().toLong())
            assertTrue(entry.claimed)
        }
        repository.enable(fast.id, false)
        assertTrue("Configuration changes invalidate prepared addresses", entry.scope.isCancelled())
    }
    @Test @Throws(Exception::class) fun claimedPreparationSurvivesDetailsReplacementAndKeepsItsIdentity() {
        install("fast")
        val title = Title.parse("catalogue-owner", JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Film"))!!
        val first = preparation.claim(title, title.id)
        repository.newRequest().use { caller ->
            caller.priority(0); assertFalse(first.awaitCandidate(caller, "").isEmpty())
            assertEquals("Metadata enrichment must retain the library identity", title.key(), first.title.key())
            val other = Title.parse("catalogue-owner", JSONObject().put("id", "fixture:other").put("type", "movie").put("name", "Other"))!!
            preparation.prepare(other, other.id); preparation.leave()
            assertFalse("Details owns no cancellation rights over Play", first.scope.isCancelled())
            assertSame(first, preparation.take(first.token, title, title.id))
            first.close()
        }
    }
    @Test @Throws(Exception::class) fun explicitRefreshMakesANewProviderRequest() {
        val fast = install("fast")
        val title = Title.parse(fast.id, JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Film"))!!
        repository.newRequest().use { caller ->
            val first = preparation.prepare(title, title.id); assertFalse(first.awaitCandidate(caller, "").isEmpty())
            preparation.invalidate(title, title.id)
            val second = preparation.prepare(title, title.id); assertNotSame(first, second)
            assertFalse(second.awaitCandidate(caller, "").isEmpty()); assertEquals(2, server.fastStreams.get().toLong())
        }
    }
    @Test @Throws(Exception::class) fun sourcesWaitForInlineEpisodeStreamsFromLateMetadata() {
        val addon = install("inline")
        val title = Title.parse(addon.id, JSONObject().put("id", "fixture:series").put("type", "series").put("name", "Series"))!!
        val entry = preparation.prepare(title, "fixture:series:one")
        val reader = Executors.newSingleThreadExecutor()
        try {
            repository.newRequest().use { caller ->
                val pending: Future<*> = reader.submit(Runnable { try { entry.awaitComplete(caller) { } } catch (error: Exception) { throw RuntimeException(error) } })
                assertTrue(server.inlineStreams.await(3, TimeUnit.SECONDS))
                val end = SystemClock.elapsedRealtime() + 3000
                while (!entry.complete && SystemClock.elapsedRealtime() < end) SystemClock.sleep(10)
                assertTrue(entry.complete); assertFalse("Metadata still owns possible inline sources", pending.isDone)
                server.inlineMetadata.countDown(); pending.get(3, TimeUnit.SECONDS)
                assertEquals(1, entry.result().items.size.toLong()); assertEquals(1, entry.sources().size.toLong())
            }
        } finally { reader.shutdownNow() }
    }
    @Test @Throws(Exception::class) fun extensionlessProgressiveResponseKeepsItsNormalReadTimeout() {
        Cancellation().use { scope -> HttpMediaResolver.resolve(server.origin + "/slow-body", Collections.emptyMap(), scope).use { resolved ->
            val source: DataSource = resolved.createDataSource()
            try { source.open(DataSpec.Builder().setUri(resolved.uri).build())
                val bytes = ByteArray(8192); var count: Int; var total = 0
                while (source.read(bytes, 0, bytes.size).also { count = it } >= 0) total += count
                assertEquals(8192, total.toLong()); assertEquals(1, server.mediaRequests.get().toLong())
            } finally { source.close() }
        } }
    }
    @Test @Throws(Exception::class) fun probeTimeoutDoesNotBlockFollowingProbesAndKeepsHttpStatus() {
        val start = SystemClock.elapsedRealtime()
        Cancellation().use { scope ->
            try { HttpMediaResolver.resolve(server.origin + "/blocked", Collections.emptyMap(), scope).close(); fail("Unbounded probe") }
            catch (expected: IOException) { assertTrue(SystemClock.elapsedRealtime() - start < 3500) }
            try { HttpMediaResolver.resolve(server.origin + "/expired", Collections.emptyMap(), scope).close(); fail("Expired response accepted") }
            catch (expected: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) { assertEquals(401, expected.responseCode.toLong()) }
            HttpMediaResolver.resolve(server.origin + "/signed-play", Collections.emptyMap(), scope).use { resolved -> assertEquals("application/x-mpegURL", resolved.mime) }
        }
    }
    @Test @Throws(Exception::class) fun redirectedDashAndReopenedRangesUseTheRightContent() {
        Cancellation().use { scope ->
            HttpMediaResolver.resolve(server.origin + "/redirect", Collections.emptyMap(), scope).use { resolved -> assertEquals("application/dash+xml", resolved.mime); assertEquals("/dash-play", resolved.uri.path) }
            HttpMediaResolver.resolve(server.origin + "/binary", Collections.emptyMap(), scope).use { resolved ->
                val source: DataSource = resolved.createDataSource(); val bytes = ByteArray(32)
                source.open(DataSpec.Builder().setUri(resolved.uri).build()); assertEquals(3, source.read(bytes, 0, 3).toLong()); source.close()
                source.open(DataSpec.Builder().setUri(resolved.uri).setPosition(4).setLength(3).build())
                assertEquals(3, source.read(bytes, 0, bytes.size).toLong()); assertEquals("456", String(bytes, 0, 3, StandardCharsets.US_ASCII))
                assertEquals(-1, source.read(bytes, 0, bytes.size).toLong()); source.close()
            }
        }
    }

    @Test @Throws(Exception::class) fun mediaProbeRejectsTheUntrustedTlsFixture() {
        val address = InstrumentationRegistry.getArguments().getString("badCertificate", "https://127.0.0.1:58135/manifest.json")
        Cancellation().use { scope ->
            try { HttpMediaResolver.resolve(address, Collections.emptyMap(), scope).use { ignored -> fail("Untrusted certificate accepted") } }
            catch (expected: javax.net.ssl.SSLException) { /* platform trust and hostname checks remain enabled */ }
        }
    }

    @Test @Throws(Exception::class) fun extensionlessHlsUsesContentAndReusesTheOpenedResponse() {
        Cancellation().use { scope -> HttpMediaResolver.resolve(server.origin + "/signed-play", Collections.emptyMap(), scope).use { resolved ->
            assertEquals("application/x-mpegURL", resolved.mime)
            val source: DataSource = resolved.createDataSource()
            source.open(DataSpec.Builder().setUri(resolved.uri).build())
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(64); var count: Int
            while (source.read(buffer, 0, buffer.size).also { count = it } >= 0) bytes.write(buffer, 0, count)
            source.close()
            assertTrue(bytes.toString(StandardCharsets.UTF_8.name()).startsWith("#EXTM3U"))
            assertEquals("Probe and loader share a request", 1, server.mediaRequests.get().toLong())
        } }
    }
    class Loopback @Throws(IOException::class) constructor() : AutoCloseable {
        @JvmField val socket: ServerSocket
        @JvmField val origin: String
        @JvmField val clients: ExecutorService = Executors.newFixedThreadPool(4)
        @JvmField val unblock = CountDownLatch(1); @JvmField val inlineStreams = CountDownLatch(1); @JvmField val inlineMetadata = CountDownLatch(1)
        @JvmField val fastStreams = AtomicInteger(); @JvmField val mediaRequests = AtomicInteger()
        @JvmField val open: MutableList<Socket> = Collections.synchronizedList(ArrayList())
        @JvmField val accept: Thread
        @Volatile @JvmField var stopped = false
        @Volatile @JvmField var fastSentAt: Long = 0
        @JvmField var mediaRedirect: String? = null
        init {
            socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")); origin = "http://127.0.0.1:" + socket.localPort
            accept = Thread(Runnable {
                while (!stopped) try { val client = socket.accept(); open.add(client); clients.execute { respond(client) } }
                catch (ignored: IOException) { if (stopped) return@Runnable }
                catch (ignored: RejectedExecutionException) { if (stopped) return@Runnable }
            }, "startup-loopback"); accept.start()
        }
        fun respond(client: Socket) {
            try {
                client.use { connection ->
                    val input = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))
                    val request = input.readLine() ?: return; val path = request.split(" ")[1]
                    while (true) { val header = input.readLine(); if (header == null || header.isEmpty()) break }
                    val body: String
                    if (mediaRedirect != null && path == "/movie.mp4") { connection.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)); return }
                    if (mediaRedirect != null && path == "/movie-slow.mp4") { connection.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: " + mediaRedirect + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)); return }
                    if (path == "/redirect") { connection.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /dash-play\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)); return }
                    if (path == "/expired") { connection.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)); return }
                    if (path == "/blocked") { unblock.await(6, TimeUnit.SECONDS); return }
                    if (path == "/slow-body") {
                        mediaRequests.incrementAndGet(); connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 8192\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                        connection.getOutputStream().write(ByteArray(4096)); connection.getOutputStream().flush(); Thread.sleep(2500); connection.getOutputStream().write(ByteArray(4096)); return
                    }
                    if (path.endsWith("manifest.json")) {
                        val id = if (path.contains("/slow/")) "slow" else if (path.contains("/inline/")) "inline" else "fast"
                        body = JSONObject().put("id", id).put("name", id).put("version", "1.0.0")
                                .put("types", JSONArray().put("movie").put("series")).put("resources", JSONArray().put("meta").put("stream")).put("catalogs", JSONArray()).toString()
                    } else if (path == "/binary") { body = "0123456789"
                    } else if (path == "/dash-play") { body = "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>"
                    } else if (path.contains("/inline/stream/")) { inlineStreams.countDown(); body = "{\"streams\":[]}"
                    } else if (path.contains("/inline/meta/")) {
                        inlineMetadata.await(5, TimeUnit.SECONDS)
                        body = JSONObject().put("meta", JSONObject().put("id", "fixture:series").put("type", "series").put("name", "Series")
                                .put("videos", JSONArray().put(JSONObject().put("id", "fixture:series:one").put("name", "One")
                                        .put("streams", JSONArray().put(JSONObject().put("name", "Inline").put("url", origin + "/movie.mp4")))))).toString()
                    } else if (path == "/signed-play") {
                        mediaRequests.incrementAndGet(); body = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nsegment.ts\n#EXT-X-ENDLIST\n"
                    } else {
                        if (path.contains("/slow/")) unblock.await(15, TimeUnit.SECONDS)
                        if (path.contains("/stream/")) {
                            if (path.contains("/fast/")) fastStreams.incrementAndGet()
                            body = JSONObject().put("streams", JSONArray().put(JSONObject().put("name", "Local fixture").put("url", origin + (if (path.contains("/slow/")) "/movie-slow.mp4" else "/movie.mp4")))).toString()
                        } else body = JSONObject().put("meta", JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Prepared film")).toString()
                    }
                    if (path.contains("/fast/stream/")) fastSentAt = SystemClock.elapsedRealtime()
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                    connection.getOutputStream().write(bytes)
                }
            } catch (ignored: Exception) { /* cancelling the client intentionally closes its response */ }
            finally { open.remove(client) }
        }
        @Throws(Exception::class)
        override fun close() {
            stopped = true; unblock.countDown(); inlineMetadata.countDown(); socket.close()
            synchronized(open) { for (client in open) client.close() }
            clients.shutdownNow(); accept.join(2000); assertTrue(clients.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
