package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Test
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title

/** Cold, missing and late artwork through the actual details page, using emulator-loopback HTTP only. */
class TitleArtworkTest : AppHarness() {
    @Test @Throws(Exception::class) fun metadataLogoIsTheFirstVisibleTitleAndCachedReopenDoesNotFlashText() {
        LogoServer(false).use { server ->
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            val title = Title.parse(services.store.addons()[0].id, server.title(false))!!
            val flashed = AtomicBoolean()
            browse!!.onActivity { activity ->
                activity.findViewById<View>(R.id.page_rows).viewTreeObserver.addOnDrawListener {
                    val text = titleText(activity.findViewById(R.id.page_rows))
                    if (text != null && text.isShown) flashed.set(true)
                }
                activity.push(Route.of("Details", title, ""))
            }
            assertTrue(server.metaRequested.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(200); assertFalse("No text before metadata", textShown())
            server.metaReady.countDown()
            assertTrue(server.logoRequested.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(200); assertFalse("No text during logo download", textShown())
            server.logoReady.countDown()
            AppHarness.waitUntil("finished logo header", 5000) { logoShown() }
            assertFalse("No frame displayed the text placeholder", flashed.get())
            tap("Back", false)
            browse!!.onActivity { activity -> activity.push(Route.of("Details", title, "")) }
            AppHarness.waitUntil("cached logo", 5000) { logoShown() }
            assertFalse("Reopening also keeps the image title", flashed.get())
        }
    }

    @Test @Throws(Exception::class) fun invalidLogoAndAbsentLogoUseReadableText() {
        LogoServer(true).use { server ->
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
            server.logoReady.countDown()
            val broken = Title.parse("local", server.title(true))!!
            browse!!.onActivity { activity -> activity.push(Route.of("Details", broken, "")) }
            AppHarness.waitUntil("failed image falls back to text", 5000) { textShown() }
            assertFalse(logoShown())
            tap("Back", false)
            val absent = Title.parse("local", server.title(false))!!
            browse!!.onActivity { activity -> activity.push(Route.of("Details", absent, "")) }
            AppHarness.waitUntil("title without artwork", 5000) { textShown() }
        }
    }

    @Test @Throws(Exception::class) fun timedOutLogoCannotReplaceTheTextLater() {
        LogoServer(false).use { server ->
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
            val title = Title.parse("local", server.title(true))!!
            browse!!.onActivity { activity -> activity.push(Route.of("Details", title, "")) }
            assertTrue(server.logoRequested.await(5, TimeUnit.SECONDS))
            AppHarness.waitUntil("bounded text fallback", 5000) { textShown() }
            server.logoReady.countDown()
            assertTrue(server.logoSent.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(500)
            assertTrue("Late artwork cannot flash over the fallback", textShown())
            assertFalse(logoShown())
        }
    }

    private fun textShown(): Boolean {
        val shown = AtomicBoolean()
        browse!!.onActivity { activity -> val text = titleText(activity.findViewById(R.id.page_rows)); shown.set(text != null && text.isShown) }
        return shown.get()
    }
    private fun logoShown(): Boolean {
        val shown = AtomicBoolean()
        browse!!.onActivity { activity ->
            val text = titleText(activity.findViewById(R.id.page_rows)) ?: return@onActivity
            val header = text.parent as ViewGroup
            for (i in 0 until header.childCount) {
                val child = header.getChildAt(i)
                if (child is ImageView && child.isShown && child.drawable != null) shown.set(true)
            }
        }
        return shown.get()
    }

    private class LogoServer @Throws(Exception::class) constructor(invalid: Boolean) : AutoCloseable {
        @JvmField val server: ServerSocket
        @JvmField val workers: ExecutorService = Executors.newCachedThreadPool()
        @JvmField val metaRequested = CountDownLatch(1); @JvmField val metaReady = CountDownLatch(1)
        @JvmField val logoRequested = CountDownLatch(1); @JvmField val logoReady = CountDownLatch(1); @JvmField val logoSent = CountDownLatch(1)
        @JvmField val url: String
        @JvmField val image: ByteArray
        init {
            val bitmap = Bitmap.createBitmap(240, 60, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xFFFFFFFF.toInt())
            val bytes = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle()
            image = if (invalid) byteArrayOf(1, 2, 3) else bytes.toByteArray()
            server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")); url = "http://127.0.0.1:" + server.localPort
            workers.execute { while (!server.isClosed) try { val socket = server.accept(); workers.execute { answer(socket) } } catch (ignored: Exception) { break } }
        }
        @Throws(Exception::class)
        fun title(logo: Boolean): JSONObject {
            val result = JSONObject().put("id", "logo-film").put("type", "movie").put("name", NAME)
            if (logo) result.put("logo", "$url/logo.png"); return result
        }
        fun answer(socket: Socket) {
            try {
                socket.use { connection -> BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)).use { reader ->
                    val first = reader.readLine() ?: return
                    while (true) { val line = reader.readLine(); if (!(line != null && !line.isEmpty())) break }
                    val path = first.split(" ")[1]; val body: ByteArray
                    if (path == "/logo.png") {
                        logoRequested.countDown(); if (!logoReady.await(10, TimeUnit.SECONDS)) return; body = image
                    } else if (path == "/manifest.json") {
                        body = ("{\"id\":\"tv.stream.logo-fixture\",\"name\":\"Logo fixture\",\"version\":\"1.0.0\",\"types\":[\"movie\"],\"resources\":[\"meta\"],\"catalogs\":[]}").toByteArray(StandardCharsets.UTF_8)
                    } else {
                        metaRequested.countDown(); if (!metaReady.await(10, TimeUnit.SECONDS)) return
                        body = JSONObject().put("meta", title(true)).toString().toByteArray(StandardCharsets.UTF_8)
                    }
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                    connection.getOutputStream().write(body); connection.getOutputStream().flush()
                    if (path == "/logo.png") logoSent.countDown()
                } }
            } catch (ignored: Exception) { /* Test shutdown closes in-flight requests. */ }
        }
        @Throws(Exception::class)
        override fun close() { metaReady.countDown(); logoReady.countDown(); server.close(); workers.shutdownNow() }
    }

    companion object {
        private const val NAME = "Logo fixture film"
        private fun titleText(view: View?): TextView? {
            if (view is TextView && NAME.contentEquals(view.text)) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                val found = titleText(view.getChildAt(i)); if (found != null) return found
            }
            return null
        }
    }
}
