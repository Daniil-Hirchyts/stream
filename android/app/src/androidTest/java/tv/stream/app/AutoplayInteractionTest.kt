package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import tv.stream.app.addon.*
import tv.stream.app.data.TvStore

/** Actual generated torrent playback, with only loopback discovery and no public providers. */
class AutoplayInteractionTest : AppHarness() {
    @Test @Throws(Exception::class) fun playBalancesAvailabilityHdAndSizeAndOffersNextBeforeTheEnding() {
        Sources().use { server ->
            services.addons.newRequest().use { request -> services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request)) }
            services.store.remember(server.title); services.store.favourite(server.title, true)
            launch(); waitUntil("Home", 10000) { visible(server.title.name) }
            tap(server.title.name, false); waitUntil("Play", 10000) { visible("Play") }; tap("Play", true)
            waitUntil("HD source renders", 40000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
            waitUntil("chosen source is recorded", 5000) { services.store.sourceMarks(server.title.key(), server.first).containsKey(SourcePolicy.key(MediaSource.list(services.store.addons()[0].id, server.firstStreams)[2])) }
            assertTrue(diagnostics().optBoolean("automatic")); assertEquals("TORRENT", diagnostics().optString("transport"))
            assertEquals("One attempt picks the balanced source", 1, diagnostics().optInt("sourceAttempts").toLong())
            assertFalse("No offer during the story", shown(R.id.next_episode_offer))
            pauseAndSeek(.93f)
            waitUntil("Next episode appears before completion", 5000) { shown(R.id.next_episode_offer) }
            assertFalse("No early countdown or automatic skip", shown(R.id.up_next))
            assertFalse(state()!!.ended)
            SystemClock.sleep(350)
            val offerActivity = watch()
            instrumentation.runOnMainSync { assertEquals("The final offer is fully readable", 1f, offerActivity!!.findViewById<View>(R.id.next_episode_offer).alpha, .01f) }
            screenshot("validation-next-episode.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            waitUntil("Remote reaches Next episode", 5000) { focused(R.id.next_episode_play) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil("offer dismissed", 5000) { !shown(R.id.next_episode_offer) }
            seek(.98f); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY); waitUntil("episode finishes without autoplay after dismiss", 10000) { state()!!.ended }
            SystemClock.sleep(1200); assertFalse("Dismiss also suppresses end countdown", shown(R.id.up_next))
            // Rewind and explicitly use the existing player option to move to episode two.
            seek(.5f); control(R.id.control_settings, true); tap("Next episode", false)
            waitUntil("next episode actually starts", 40000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing
                    && services.store.sourceMarks(server.title.key(), server.second).values.stream().anyMatch { mark -> mark.state == TvStore.SourceMark.PLAYED } }
            pauseAndSeek(.93f); SystemClock.sleep(1200)
            assertFalse("Last episode has no nonexistent successor", shown(R.id.next_episode_offer))
        }
    }

    @Test @Throws(Exception::class) fun upNextCanBeCancelledWithRemote() {
        Sources().use { server ->
            services.addons.newRequest().use { request -> services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request)) }
            services.store.favourite(server.title, true); launch(); waitUntil("series", 10000) { visible(server.title.name) }
            tap(server.title.name, false); tap("Play", true)
            waitUntil("first episode renders", 40000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
            seek(.98f)
            waitUntil("countdown appears", 15000) { shown(R.id.up_next) }
            waitUntil("play now focused", 3000) { focused(R.id.up_next_play) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            waitUntil("cancel focused", 3000) { focused(R.id.up_next_cancel) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("countdown dismissed", 3000) { !shown(R.id.up_next) }
            assertTrue("cancel keeps the first episode ended", state()!!.ended)
        }
    }

    @Test @Throws(Exception::class) fun manualNextWorksWithAutoplayOffAndUsesTheNextEpisodesSource() {
        Sources().use { server ->
            services.store.setPreference("autoplay", "false")
            services.addons.newRequest().use { request -> services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request)) }
            services.store.favourite(server.title, true); launch(); waitUntil("series", 10000) { visible(server.title.name) }
            tap(server.title.name, false); tap("Play", true)
            waitUntil("first episode renders", 40000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
            pauseAndSeek(.93f); waitUntil("manual next offer even with autoplay disabled", 5000) { shown(R.id.next_episode_offer) }
            control(R.id.next_episode_play, true)
            waitUntil("second episode plays", 40000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing
                    && services.store.sourceMarks(server.title.key(), server.second).values.stream().anyMatch { mark -> mark.state == TvStore.SourceMark.PLAYED } }
        }
    }

    private fun pauseAndSeek(fraction: Float) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
        waitUntil("paused", 5000) { state() != null && !state()!!.playWhenReady }; seek(fraction)
    }
    private fun seek(fraction: Float) {
        val activity = watch(); assertNotNull(activity)
        instrumentation.runOnMainSync {
            try { val field = WatchActivity::class.java.getDeclaredField("session"); field.isAccessible = true
                (field.get(activity) as tv.stream.playback.PlaybackSession).seekTo((stateDuration(activity!!) * fraction).toLong())
            } catch (e: ReflectiveOperationException) { throw AssertionError(e) }
        }
    }
    private fun stateDuration(activity: WatchActivity): Long { return activity.playbackSnapshot()!!.durationMs }
    private fun shown(id: Int): Boolean { val value = AtomicBoolean(); val activity = watch()
        if (activity != null) instrumentation.runOnMainSync { value.set(activity.findViewById<View>(id).isShown) }; return value.get() }
    private fun focused(id: Int): Boolean { val value = AtomicBoolean(); val activity = watch()
        if (activity != null) instrumentation.runOnMainSync { value.set(activity.findViewById<View>(id).isFocused) }; return value.get() }
    @Throws(Exception::class)
    private fun screenshot(name: String) {
        val image: Bitmap? = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(image)
        FileOutputStream(File(context.filesDir, name)).use { out -> image!!.compress(Bitmap.CompressFormat.PNG, 100, out) }; image!!.recycle()
    }

    private inner class Sources @Throws(Exception::class) constructor() : AutoCloseable {
        val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:" + socket.localPort
        val first = "fixture:episode/custom?part=1"; val second = "fixture:episode/custom?part=2"
        val title: Title; val firstStreams: JSONObject; val secondStreams: JSONObject; val worker: Thread
        init {
            val loaded: Array<Any> = AddonClient().use { client ->
                Cancellation().use { scope ->
                    val meta = client.json("$origin/addon/good/ui/meta/series/fixture%3Aseries.json", scope, AddonClient.deadline()).getJSONObject("meta")
                    arrayOf<Any>(Title.parse("fixture.autoplay", meta.put("name", "Autoplay test series"))!!,
                            streams(client.json("$origin/addon/good/ui/stream/series/fixture%3Aepisode%2Fcustom%3Fpart%3D1.json", scope, AddonClient.deadline())),
                            streams(client.json("$origin/addon/good/ui/stream/series/fixture%3Aepisode%2Fcustom%3Fpart%3D2.json", scope, AddonClient.deadline())))
                }
            }
            title = loaded[0] as Title; firstStreams = loaded[1] as JSONObject; secondStreams = loaded[2] as JSONObject
            worker = Thread({
                while (!socket.isClosed) try {
                    val client: Socket = socket.accept()
                    try {
                        client.soTimeout = 3000
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                        val line = reader.readLine(); if (line == null) continue; val path = line.split(" ")[1]
                        while (true) { val header = reader.readLine(); if (header == null || header.isEmpty()) break }
                        val body = if (path == "/manifest.json") "{\"id\":\"fixture.autoplay\",\"version\":\"1\",\"name\":\"Autoplay fixture\",\"types\":[\"series\"],\"resources\":[\"meta\",\"stream\"],\"catalogs\":[]}"
                                else if (path.startsWith("/meta/")) JSONObject().put("meta", JSONObject(title.cacheJson())).toString()
                                else (if (path.lowercase(java.util.Locale.ROOT).contains("part%3d2")) secondStreams else firstStreams).toString()
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                        client.getOutputStream().write(bytes)
                    } finally { client.close() }
                } catch (ignored: Exception) { if (socket.isClosed) return@Thread }
            }, "autoplay-loopback"); worker.start()
        }
        @Throws(Exception::class)
        fun streams(original: JSONObject): JSONObject {
            val torrent = original.getJSONArray("streams").getJSONObject(0)
            val list = JSONArray()
            val names = arrayOf("Heavy 1080p H264 AAC", "Small 720p H264 AAC", "Balanced 1080p H264 AAC")
            val peers = intArrayOf(1000, 30, 200); val size = longArrayOf(20000L shl 20, 800L shl 20, 1000L shl 20)
            for (i in names.indices) list.put(JSONObject(torrent.toString()).put("name", names[i]).put("seeders", peers[i])
                    .put("behaviorHints", JSONObject().put("videoSize", size[i])))
            return JSONObject().put("streams", list)
        }
        @Throws(Exception::class)
        override fun close() { socket.close(); worker.join(2000) }
    }
}
