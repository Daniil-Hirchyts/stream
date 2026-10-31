package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import tv.stream.app.addon.AddonClient
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title

/** Real loading, metadata, playback tracks and durable watched changes, using only loopback fixtures. */
class PlayerPresentationTest : AppHarness() {
    private fun rendered(id: Int) = semanticNode(context.resources.getResourceName(id))
    private fun renderedText(id: Int, expected: String) {
        val node = rendered(id); assertNotNull("Rendered text for $id", node)
        assertEquals(expected, node!!.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text })
    }
    private fun renderedDescription(id: Int, expected: String) {
        val node = rendered(id); assertNotNull("Rendered action for $id", node)
        assertEquals(expected, node!!.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(""))
    }
    @Test @Throws(Exception::class) fun textTitlesKeepFilmMetadataOffThePlayerAndWatchedActionsWorking() {
        captureIntro()
        PresentationServer(false, false).use { server ->
            start(server, false)
            AppHarness.waitUntil("text shown during source loading", 10000) { shown(R.id.loading_title) }
            val activity = watch()
            instrumentation.runOnMainSync {
                assertTrue(activity!!.findViewById<View>(R.id.loading).isShown)

            }
            renderedText(R.id.loading_title, "Presentation film")
            assertNull("Films have no episode line while loading", rendered(R.id.loading_episode))
            assertNull(rendered(R.id.loading_step))
            assertNull(rendered(R.id.loading_message))
            screenshot("validation-player-text-loading.png")
            server.streamGate.countDown()
            AppHarness.waitUntil("video renders", 30000) { state() != null && state()!!.renderedFirstFrame && state()!!.positionMs > 1000 }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
            AppHarness.waitUntil("paused controls", 5000) { !state()!!.playWhenReady && shown(R.id.controls_title) }
            instrumentation.runOnMainSync {
                assertTrue(activity!!.findViewById<View>(R.id.player_clock).isShown)
                val watched = activity.playerControlBounds(R.id.control_watched)!!; val audio = activity.playerControlBounds(R.id.control_options)!!; val settings = activity.playerControlBounds(R.id.control_settings)!!
                assertTrue("Watched comes first", watched.left < audio.left)
                assertTrue(audio.left < settings.left)
            }
            renderedText(R.id.controls_title, "Presentation film")
            assertNull(rendered(R.id.watching_info))
            renderedDescription(R.id.control_watched, "Mark watched")
            AppHarness.waitUntil("embedded subtitles available", 5000) { !state()!!.textTracks.isEmpty() }
            control(R.id.control_options, true); tap(state()!!.textTracks[0].label, false)
            AppHarness.waitUntil("subtitle selected", 5000) { state()!!.subtitles != null && !shown(R.id.menu) }
            AppHarness.waitUntil("controls visible after selecting subtitles", 5000) { shown(R.id.controls_title) }
            assertNull(rendered(R.id.controls_details))
            control(R.id.control_watched, true)
            AppHarness.waitUntil("marked watched", 5000) { services.store.progress(server.title.key(), server.video)!!.watched }
            instrumentation.runOnMainSync {
                assertFalse("Marking watched never shows a toast", activity!!.findViewById<View>(R.id.notice).isShown)
            }
            renderedDescription(R.id.control_watched, "Mark unwatched")
            control(R.id.control_watched, false)
            AppHarness.waitUntil("marked unwatched", 5000) { !services.store.progress(server.title.key(), server.video)!!.watched }
            SystemClock.sleep(1200)
            assertFalse("Later progress preserves the viewer's choice", services.store.progress(server.title.key(), server.video)!!.watched)
            instrumentation.runOnMainSync {
                assertFalse("Marking unwatched never shows a toast", activity!!.findViewById<View>(R.id.notice).isShown)
            }
            renderedDescription(R.id.control_watched, "Mark watched")
            assertEquals("Text titles never request logo artwork", 0, server.logoRequests.get().toLong())
            screenshot("validation-player-text-controls.png")
            control(R.id.control_stop, true); AppHarness.waitUntil("player exits", 5000) { watch() == null }
            app.persistence.submit(Runnable { }).get()
            assertFalse("Unwatched survives exit", services.store.progress(server.title.key(), server.video)!!.watched)
        }
    }

    @Test @Throws(Exception::class) fun developerLoadingAndSeriesUseTextEvenWithALogo() {
        PresentationServer(true, true).use { server ->
            services.store.setPreference("playerClock", "false")
            start(server, true)
            AppHarness.waitUntil("developer sees the loading stage", 5000) { shown(R.id.loading_step) }
            val activity = watch()
            instrumentation.runOnMainSync {
                assertTrue(activity!!.playerControlBounds(R.id.loading_title) != null)
            }
            renderedText(R.id.loading_title, "Presentation series")
            renderedText(R.id.loading_episode, "S1 E2 · Pilot")
            assertTrue("Nonempty developer stage", rendered(R.id.loading_step)?.config?.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotEmpty() } == true)
            server.streamGate.countDown()
            AppHarness.waitUntil("video renders without artwork", 30000) { state() != null && state()!!.renderedFirstFrame }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
            AppHarness.waitUntil("readable series title", 5000) { shown(R.id.controls_title) }
            instrumentation.runOnMainSync {
                assertFalse("Player clock preference still applies", activity!!.findViewById<View>(R.id.player_clock).isShown)
            }
            renderedText(R.id.controls_title, "Presentation series")
            renderedText(R.id.watching_info, "S1 E2 · Pilot")
            assertNull(rendered(R.id.controls_details))
            assertEquals(0, server.logoRequests.get().toLong())
        }
    }

    @Throws(Exception::class)
    private fun start(server: PresentationServer, developer: Boolean) {
        services.store.setPreference("developerMode", java.lang.Boolean.toString(developer))
        services.store.setPreference("clock", "24")
        services.addons.newRequest().use { scope -> services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope)) }
        services.store.remember(server.title)
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        browse!!.onActivity { activity -> activity.startActivity(WatchActivity.intent(activity, server.title, server.video, "")) }
    }
    private fun shown(id: Int): Boolean {
        val result = AtomicReference(false)
        val activity = watch(); if (activity != null) instrumentation.runOnMainSync { result.set(activity.findViewById<View>(id)?.isShown ?: (activity.playerControlBounds(id) != null)) }
        return result.get()
    }
    @Throws(Exception::class)
    private fun captureIntro() {
        val intro = arrayOfNulls<tv.stream.app.ui.IntroView>(1)
        instrumentation.runOnMainSync {
            intro[0] = tv.stream.app.ui.IntroView(context)
            intro[0]!!.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            intro[0]!!.layout(0, 0, 1920, 1080)
        }
        SystemClock.sleep(tv.stream.app.ui.IntroView.minimumMs())
        val image = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { intro[0]!!.draw(Canvas(image)) }
        FileOutputStream(File(context.filesDir, "validation-startup-wordmark.png")).use { out ->
            image.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        image.recycle()
    }
    @Throws(Exception::class)
    private fun screenshot(name: String) {
        SystemClock.sleep(400) // Let the activity/window entrance reach the compositor.
        val image = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(image)
        FileOutputStream(File(context.filesDir, name)).use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out) }
        image.recycle()
    }

    private inner class PresentationServer @Throws(Exception::class) constructor(series: Boolean, brokenLogo: Boolean) : AutoCloseable {
        @JvmField val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        @JvmField val clients: java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(4)
        @JvmField val streamGate = CountDownLatch(1)
        @JvmField val logoRequests = AtomicInteger()
        @JvmField val url = "http://127.0.0.1:" + server.localPort
        @JvmField val title: Title
        @JvmField val video: String
        @JvmField val png: ByteArray
        @JvmField val streams: String
        @JvmField val accept: Thread
        init {
            val image = Bitmap.createBitmap(500, 100, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG); paint.color = Color.WHITE; paint.textSize = 68f
            paint.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC)
            Canvas(image).drawText("FRAME", 8f, 76f, paint)
            val bytes = java.io.ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.PNG, 100, bytes); image.recycle(); png = bytes.toByteArray()
            val meta = JSONObject().put("id", "fixture:presentation").put("type", if (series) "series" else "movie")
                    .put("name", if (series) "Presentation series" else "Presentation film").put("logo", url + (if (brokenLogo) "/missing.png" else "/logo.png"))
                    .put("releaseInfo", "1999").put("genres", JSONArray().put("Drama").put("Mystery")).put("imdbRating", "8.7")
            video = if (series) "fixture:pilot" else "fixture:presentation"
            if (series) meta.put("videos", JSONArray().put(JSONObject().put("id", video).put("title", "Pilot").put("season", 1).put("episode", 2)))
            title = Title.parse("fixture.presentation", meta)!!
            streams = AddonClient().use { client -> Cancellation().use { scope ->
                client.json(origin + "/addon/good/http-mkv/stream/movie/fixture%3Afilm.json", scope, AddonClient.deadline()).toString()
            } }
            accept = Thread(Runnable {
                while (!server.isClosed) try { val socket = server.accept(); clients.execute { reply(socket) } }
                catch (e: Exception) { if (server.isClosed) return@Runnable }
            }, "presentation-loopback"); accept.start()
        }
        private fun reply(socket: Socket) {
            try {
                socket.use { connection ->
                    connection.soTimeout = 15000
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))
                    val request = reader.readLine(); val path = request.split(" ")[1]
                    while (true) { val line = reader.readLine(); if (line == null || line.isEmpty()) break }
                    val body: ByteArray; var content = "application/json"; var status = "200 OK"
                    if (path == "/manifest.json") body = ("{\"id\":\"fixture.presentation\",\"version\":\"1\",\"name\":\"Presentation fixture\",\"types\":[\"movie\",\"series\"],\"idPrefixes\":[\"fixture:\"],\"resources\":[\"meta\",\"stream\"],\"catalogs\":[]}").toByteArray(StandardCharsets.UTF_8)
                    else if (path.startsWith("/meta/")) body = ("{\"meta\":" + title.cacheJson() + "}").toByteArray(StandardCharsets.UTF_8)
                    else if (path.startsWith("/stream/")) { streamGate.await(12, TimeUnit.SECONDS); body = streams.toByteArray(StandardCharsets.UTF_8) }
                    else if (path == "/logo.png") { logoRequests.incrementAndGet(); body = png; content = "image/png" }
                    else { status = "404 Not Found"; body = ByteArray(0) }
                    connection.getOutputStream().write(("HTTP/1.1 " + status + "\r\nContent-Type: " + content + "\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                    connection.getOutputStream().write(body)
                }
            } catch (ignored: Exception) { /* cancelled navigation closes outstanding test requests */ }
        }
        @Throws(Exception::class)
        override fun close() { streamGate.countDown(); server.close(); clients.shutdownNow(); accept.join(2000) }
    }
}
