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
}
