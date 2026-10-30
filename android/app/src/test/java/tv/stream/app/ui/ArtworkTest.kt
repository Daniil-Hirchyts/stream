package tv.stream.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Artwork against a real loopback HTTP server and real decoding: sharing, cancellation, bounds and failure recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkTest {
    @get:Rule val folder = TemporaryFolder()

    private class Answer(val status: Int, val body: ByteArray, val gate: CountDownLatch? = null)
    private val answers = ConcurrentHashMap<String, Answer>()
    private val requests = ConcurrentHashMap<String, AtomicInteger>()
    private lateinit var server: ServerSocket
    private lateinit var disk: File
    private lateinit var artwork: Artwork

    @Before fun open() {
        server = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) try {
                val client = server.accept()
                Thread {
                    client.use {
                        val head = StringBuilder()
                        val input = client.getInputStream()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                        val path = head.toString().split(" ")[1]
                        requests.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                        val answer = answers[path] ?: Answer(404, ByteArray(0))
                        answer.gate?.await(20, TimeUnit.SECONDS)
                        client.getOutputStream().write(("HTTP/1.1 ${answer.status} X\r\nContent-Length: ${answer.body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        client.getOutputStream().write(answer.body)
                    }
                }.apply { isDaemon = true }.start()
            } catch (closed: Exception) { /* the test finished, or the client went away */ }
        }.apply { isDaemon = true }.start()
        disk = folder.newFolder("artwork")
        artwork = Artwork(disk)
    }
    @After fun close() { artwork.close(); server.close() }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"
    private fun count(path: String) = requests[path]?.get() ?: 0
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
    private fun image(path: String, width: Int = 40, height: Int = 20, gate: CountDownLatch? = null): String {
        answers["/$path"] = Answer(200, png(width, height), gate)
        return url(path)
    }
    private fun pump(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("timed out: $what", System.nanoTime() < deadline)
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun settle() { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
    private fun shown(view: ImageView): Bitmap? = (view.drawable as? BitmapDrawable)?.bitmap
    private fun view() = ImageView(ApplicationProvider.getApplicationContext())
    private fun diskFiles() = disk.listFiles()!!.toList()

    @Test fun everySizeOfAnImageSharesOneDownload() {
        val gate = CountDownLatch(1)
        val poster = image("poster", 800, 400, gate)
        val results = ArrayList<Bitmap>()
        val thumbnails = ArrayList<Bitmap>()
        val done = Consumer<Bitmap> { results.add(it) }
        val thumbnail = Consumer<Bitmap> { thumbnails.add(it); results.add(it) }
        artwork.fetch(poster, Artwork.THUMBNAIL, false, thumbnail)
        artwork.fetch(poster, Artwork.THUMBNAIL, false, thumbnail)
        artwork.fetch(poster, Artwork.BACKDROP, false, done)
        artwork.softened(poster, done)
        artwork.blurred(poster, done)
        pump("first request") { count("/poster") == 1 && artwork.started == 2 }
        settle()
        gate.countDown()
        pump("all results") { results.size == 5 }
        assertEquals(1, count("/poster"))
        assertEquals(1, artwork.joined)
        assertEquals(4, artwork.started) // four sizes and modes decoded, one duplicate joined
        assertEquals(listOf(440, 440), thumbnails.map { it.width })
        assertEquals(800, results.maxOf { it.width })
    }

    @Test fun cancellingOneSizeKeepsTheDownloadAnotherSizeNeeds() {
        val gate = CountDownLatch(1)
        val poster = image("poster", 800, 400, gate)
        val results = ArrayList<String>()
        val first = artwork.fetch(poster, Artwork.THUMBNAIL, false, Consumer { results.add("small") })!!
        pump("download started") { count("/poster") == 1 }
        artwork.fetch(poster, Artwork.BACKDROP, false, Consumer { results.add("large") })
        pump("second size waiting") { artwork.started == 2 }
        settle()
        first.cancel()
        settle()
        gate.countDown()
        pump("the other size") { results.isNotEmpty() }
        settle()
        assertEquals(listOf("large"), results)
        assertEquals("the leader's cancellation must not restart or stop the download", 1, count("/poster"))
    }

    @Test fun cancellingEveryBindingStopsTheDownloadsAndFreesTheWorkers() {
        val never = CountDownLatch(1)
        val a = image("a", gate = never); val b = image("b", gate = never)
        val first = artwork.fetch(a, Artwork.THUMBNAIL, false, Consumer {})!!
        val second = artwork.fetch(b, Artwork.THUMBNAIL, false, Consumer {})!!
        pump("both workers busy") { count("/a") == 1 && count("/b") == 1 }
        first.cancel(); second.cancel()
        val arrived = ArrayList<Bitmap>()
        val start = System.nanoTime()
        artwork.fetch(image("c"), Artwork.THUMBNAIL, false, Consumer { arrived.add(it) })
        pump("next image") { arrived.isNotEmpty() }
        assertTrue("workers must not wait out the 10 s request limit", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5))
        never.countDown()
    }

    @Test fun aLateResultCannotBindToARecycledView() {
        val gate = CountDownLatch(1)
        val old = image("old", 40, 20, gate)
        val tile = view()
        artwork.load(tile, old)
        pump("old request") { count("/old") == 1 }
        artwork.load(tile, image("new", 10, 30))
        pump("new image") { shown(tile) != null }
        gate.countDown()
        settle()
        assertEquals(10, shown(tile)!!.width)

        // Cleared rather than reused: nothing may appear at all.
        val gate2 = CountDownLatch(1)
        val other = view()
        artwork.load(other, image("cleared", 40, 20, gate2))
        pump("cleared request") { count("/cleared") == 1 }
        artwork.load(other, null)
        gate2.countDown()
        settle()
        assertNull(other.drawable)
    }

    @Test fun aFailedBindingDoesNotKeepItsView() {
        val tile = view()
        artwork.load(tile, url("missing"))
        assertEquals(1, artwork.boundViews)
        pump("failure") { count("/missing") == 1 && artwork.boundViews == 0 }
        assertNull(tile.drawable)
    }

    @Test fun downloadsAreCappedAtFourMebibytes() {
        answers["/large"] = Answer(200, ByteArray((4 shl 20) + 1))
        var failed = 0
        artwork.request(url("large"), Artwork.Purpose.THUMBNAIL, Consumer {}, Runnable { failed++ })
        pump("refusal") { failed == 1 }
        assertTrue(diskFiles().isEmpty())
    }

    @Test fun decodesAreBoundedByPurpose() {
        val huge = image("huge", 4000, 2000)
        val sizes = HashMap<Artwork.Purpose, Bitmap>()
        for (purpose in Artwork.Purpose.values()) artwork.request(huge, purpose, Consumer { sizes[purpose] = it })
        pump("every purpose") { sizes.size == Artwork.Purpose.values().size }
        assertEquals(1, count("/huge"))
        assertEquals(440, sizes[Artwork.Purpose.THUMBNAIL]!!.width)
        assertEquals(1280, sizes[Artwork.Purpose.BACKDROP]!!.width)
        assertEquals(96, sizes[Artwork.Purpose.BLURRED]!!.width)
        assertEquals(96, sizes[Artwork.Purpose.SOFTENED]!!.width)
        assertEquals(Bitmap.Config.RGB_565, sizes[Artwork.Purpose.BACKDROP]!!.config)
        assertEquals(Bitmap.Config.ARGB_8888, sizes[Artwork.Purpose.LOGO]!!.config)
    }

    @Test fun decodedMemoryStaysWithinItsLimit() {
        var arrived = 0
        for (i in 0 until 8) artwork.fetch(image("backdrop$i", 1280, 720), Artwork.BACKDROP, false, Consumer { arrived++ })
        pump("backdrops") { arrived == 8 }
        assertTrue("${artwork.memoryBytes}", artwork.memoryBytes in 1..(8 shl 20))
        assertEquals("8 x 1.8 MiB can't all be held", true, 8 * 1280 * 720 * 2 > 8 shl 20)
    }

    @Test fun diskCacheTrimsTheLeastRecentlyShownPastItsLimit() {
        fun filler(name: String, age: Long) = File(disk, name).also {
            java.io.RandomAccessFile(it, "rw").use { f -> f.setLength(15L shl 20) }
            it.setLastModified(System.currentTimeMillis() - age)
        }
        val oldest = filler("oldest", 300_000); val older = filler("older", 200_000); val newer = filler("newer", 100_000)
        var arrived = false
        artwork.fetch(image("poster"), Artwork.THUMBNAIL, false, Consumer { arrived = true })
        pump("poster") { arrived }
        assertFalse(oldest.exists()); assertFalse(older.exists()); assertTrue(newer.exists())
        assertTrue(diskFiles().sumOf { it.length() } <= 30L shl 20)
        assertEquals("the new image and the newest filler", 2, diskFiles().size)
    }

    @Test fun reopeningUsesTheDiskCopyWithoutADownload() {
        val poster = image("poster")
        var arrived = false
        artwork.fetch(poster, Artwork.THUMBNAIL, false, Consumer { arrived = true })
        pump("first") { arrived }
        Artwork(disk).use { again ->
            arrived = false
            again.fetch(poster, Artwork.THUMBNAIL, false, Consumer { arrived = true })
            pump("second") { arrived }
        }
        assertEquals(1, count("/poster"))
    }

    @Test fun anErrorPageIsNeverSavedAsTheImage() {
        answers["/poster"] = Answer(200, "<html>Sorry</html>".toByteArray())
        var failed = 0; var arrived = 0
        val fetch = { artwork.request(url("poster"), Artwork.Purpose.THUMBNAIL, Consumer { arrived++ }, Runnable { failed++ }) }
        fetch()
        pump("failure") { failed == 1 }
        assertTrue(diskFiles().toString(), diskFiles().isEmpty())
        answers["/poster"] = Answer(200, png(40, 20))
        fetch()
        pump("recovery") { arrived == 1 }
        assertEquals(2, count("/poster"))
    }

    @Test fun aDamagedSavedImageIsReplaced() {
        val poster = image("poster")
        var arrived = false
        artwork.fetch(poster, Artwork.THUMBNAIL, false, Consumer { arrived = true })
        pump("first") { arrived }
        val saved = diskFiles().single()
        saved.writeText("damaged")
        Artwork(disk).use { again ->
            arrived = false
            again.fetch(poster, Artwork.THUMBNAIL, false, Consumer { arrived = true })
            pump("second") { arrived }
        }
        assertEquals(2, count("/poster"))
        assertTrue(saved.length() > "damaged".length)
    }

    @Test fun aTransientFailureIsNotRemembered() {
        answers["/poster"] = Answer(503, ByteArray(0))
        var failed = 0; var arrived = 0
        val fetch = { artwork.request(url("poster"), Artwork.Purpose.THUMBNAIL, Consumer { arrived++ }, Runnable { failed++ }) }
        fetch()
        pump("failure") { failed == 1 }
        answers["/poster"] = Answer(200, png(40, 20))
        fetch()
        pump("recovery") { arrived == 1 }
    }

    @Test fun aHandleStopsOnlyItsOwnCallbacks() {
        val gate = CountDownLatch(1)
        val poster = image("poster", gate = gate)
        val results = ArrayList<String>()
        val first = artwork.request(poster, Artwork.Purpose.THUMBNAIL, Consumer { results.add("first") }, Runnable { results.add("first failed") })
        artwork.request(poster, Artwork.Purpose.THUMBNAIL, Consumer { results.add("second") }, Runnable { results.add("second failed") })
        pump("download") { count("/poster") == 1 }
        first.cancel(); first.cancel()
        gate.countDown()
        pump("second") { results.isNotEmpty() }
        settle()
        assertEquals(listOf("second"), results)
    }

    @Test fun aHandleAnswersFromMemoryAtOnceAndPeeks() {
        val poster = image("poster")
        assertNull(artwork.peek(poster, Artwork.Purpose.THUMBNAIL))
        var arrived = false
        artwork.request(poster, Artwork.Purpose.THUMBNAIL, Consumer { arrived = true })
        pump("first") { arrived }
        assertNotNull(artwork.peek(poster, Artwork.Purpose.THUMBNAIL))
        assertNull("another size isn't decoded yet", artwork.peek(poster, Artwork.Purpose.BACKDROP))
        var immediately = false
        val handle = artwork.request(poster, Artwork.Purpose.THUMBNAIL, Consumer { immediately = true })
        assertTrue(immediately)
        handle.cancel()
    }

    @Test fun aHandleWithoutAnAddressFailsLaterUnlessCancelled() {
        var failed = 0
        artwork.request(null, Artwork.Purpose.LOGO, Consumer {}, Runnable { failed++ })
        artwork.request(null, Artwork.Purpose.LOGO, Consumer {}, Runnable { failed += 100 }).cancel()
        assertEquals(0, failed)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failed)
    }

    @Test fun stoppingTheScreenSilencesAMissingAddressAlreadyQueuedForDelivery() {
        var failed = 0
        artwork.request(null, Artwork.Purpose.LOGO, Consumer {}, Runnable { failed++ })
        artwork.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, failed)
    }

    @Test fun stoppingTheScreenSilencesHandlesAndLaterRequestsWork() {
        val gate = CountDownLatch(1)
        val results = ArrayList<String>()
        artwork.request(image("poster", gate = gate), Artwork.Purpose.THUMBNAIL, Consumer { results.add("late") }, Runnable { results.add("failed") })
        pump("download") { count("/poster") == 1 }
        artwork.cancel()
        gate.countDown()
        settle()
        assertTrue(results.isEmpty())
        var arrived = false
        artwork.request(image("next"), Artwork.Purpose.THUMBNAIL, Consumer { arrived = true })
        pump("next") { arrived }
    }

    @Test fun aSlowTitleLogoFallsBackOnceAndStaysFallenBack() {
        val gate = CountDownLatch(1)
        var done = 0; var failed = 0
        artwork.titleLogo(image("logo", gate = gate), Consumer { done++ }, Runnable { failed++ })
        pump("download") { count("/logo") == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
        assertEquals(1, failed)
        gate.countDown()
        settle()
        assertEquals(0, done); assertEquals(1, failed)
    }
}
