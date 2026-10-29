package tv.stream.app

import org.junit.Assert.*

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.playback.PlaybackSnapshot
import tv.stream.app.addon.*

/** Optional real swarm samples, one supported 1968-film candidate per provider, with native LAN filtering. */
@RunWith(AndroidJUnit4::class)
class LiveTorrentProvidersTest : AppHarness() {
    private val results = JSONArray()

    @Before @Throws(Exception::class) fun internet() {
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().putBoolean("localOnly", false)
                .putBoolean("publicNetworkOnly", true).remove("peer").remove("cacheBytes").remove("addonDirectory").commit()
        app.resetServices(); services = app.services(); save(false)
    }

    @Test @Throws(Exception::class) fun availablePublicTorrentProvidersRenderThe1968Film() {
        val providers: MutableMap<String, String> = LinkedHashMap()
        for (listing in TorrentAddons.listings()) providers[listing.manifest.name] = listing.url
        services.addons.newRequest().use { scope ->
            for (listing in services.addons.store(scope).items)
                if (listing.manifest.name == "Public Domain Movies") providers[listing.manifest.name] = listing.url
        }
        assertTrue("The directory supplies the public-domain control", providers.containsKey("Public Domain Movies"))
        instrumentation.context.assets.open("nuvio-addon-audit.json").use { input ->
            val earlier = JSONArray(String(input.readAllBytes(), StandardCharsets.UTF_8))
            for (i in 0 until earlier.length()) {
                val entry = earlier.getJSONObject(i); val name = entry.getString("name")
                if (name == "TorrentClaw" || name == "Watcho" || name == "Pipe") providers[name] = entry.getString("manifest")
            }
        }
        val requested = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("providers", "")
        if (!requested.isEmpty() && !requested.equals("all", ignoreCase = true)) {
            val selectedNames: MutableSet<String> = java.util.HashSet()
            for (name in requested.split(",").dropLastWhile { it.isEmpty() }) selectedNames.add(name.replace(" ", "").lowercase(java.util.Locale.ROOT))
            providers.keys.removeIf { name -> !selectedNames.contains(name.replace(" ", "").lowercase(java.util.Locale.ROOT)) }
        }
        assertFalse("At least one known provider selected", providers.isEmpty())
        launch(); var rendered = 0
        for (entry in providers.entries) {
            val result = JSONObject().put("name", entry.key).put("host", AddonUrl.redacted(entry.value))
            results.put(result); save(false)
            val review = Route("Add-on review"); review.value = entry.value
            browse!!.onActivity { activity -> activity.push(review) }
            waitUntil("provider review", 20_000) { tagged("install-addon") || tagged("configured") || tagged("retry-review") }
            try {
                if (!tagged("install-addon")) {
                    result.put("playback", if (tagged("retry-review")) "provider unavailable" else "needs setup")
                    continue
                }
                tap("Install", true)
                waitUntil("provider accepted", 10_000) { services.store.addons().size == 1 }
                waitUntil("installed review", 20_000) { tagged("manage") || tagged("retry-review") }
                val sources: List<MediaSource> = services.addons.newRequest().use { scope -> services.addons.streams("movie", FILM, scope).items }
                var chosen: MediaSource? = null
                for (source in SourcePolicy.ranked(sources)) {
                    val description = source.name + " " + source.description + " " + source.filename
                    if (source.kind == MediaSource.Kind.TORRENT && (CONTROL.equals(source.infoHash, ignoreCase = true)
                            || entry.key == "Public Domain Movies" || description.contains("1968"))) {
                        if (chosen == null) chosen = source
                        if (CONTROL.equals(source.infoHash, ignoreCase = true)) { chosen = source; break }
                    }
                }
                if (chosen == null) { result.put("playback", "no supported torrent identified for the 1968 film"); continue }
                val provider = services.store.addons()[0].id; val key = SourcePolicy.key(chosen)
                result.put("selection", if (CONTROL.equals(chosen.infoHash, ignoreCase = true)) "public-domain control" else "first supported 1968 candidate")
                val film = Title.parse(provider, JSONObject().put("id", FILM).put("type", "movie").put("name", "Night of the Living Dead (1968)"))!!
                browse!!.onActivity { activity -> activity.play(film, FILM, key) }
                val started = SystemClock.elapsedRealtime(); var until = started + 250_000; var errorSince: Long = 0
                var lastError = ""
                result.put("playback", "opening"); var lastSample: Long = 0; var fileChosen = false
                while (SystemClock.elapsedRealtime() < until) {
                    val snapshot: PlaybackSnapshot? = state()
                    if (SystemClock.elapsedRealtime() - lastSample >= 3000) {
                        val sample = diagnostics(); result.put("diagnostics", sample)
                        if (sample.has("cache")) result.put("lastTransfer", sample)
                        save(false); lastSample = SystemClock.elapsedRealtime()
                    }
                    if (snapshot != null && snapshot.renderedFirstFrame && snapshot.playing && snapshot.positionMs >= 3500) {
                        if (snapshot.durationMs < 60 * 60_000) {
                            result.put("playback", "unexpected short media").put("durationMs", snapshot.durationMs); break
                        }
                        result.put("playback", "rendered and advancing").put("elapsedMs", SystemClock.elapsedRealtime() - started)
                                .put("diagnostics", diagnostics())
                        if (snapshot.videoFormat != null) result.put("video", JSONObject().put("mime", snapshot.videoFormat!!.sampleMimeType)
                                .put("width", snapshot.videoFormat!!.width).put("height", snapshot.videoFormat!!.height))
                        capture(results.length() - 1); rendered++; break
                    }
                    if (snapshot != null && snapshot.error != null) {
                        if (lastError != snapshot.error!!.code) { lastError = snapshot.error!!.code; errorSince = SystemClock.elapsedRealtime() }
                        if (SystemClock.elapsedRealtime() - errorSince >= 2000) {
                            result.put("playback", "error").put("error", lastError).put("message", snapshot.error!!.message); break
                        }
                    } else { lastError = ""; errorSince = 0 }
                    if (visible("Choose a video file")) {
                        if (fileChosen) { result.put("playback", "repeated file choice"); break }
                        val label = largestVideoLabel(instrumentation.uiAutomation.rootInActiveWindow)
                        assertNotNull("The file chooser lists actual video sizes", label)
                        tap(label!!, true); fileChosen = true; result.put("fileChoice", true)
                        waitUntil("file accepted", 5000) { !visible("Choose a video file") }
                        until = SystemClock.elapsedRealtime() + 250_000
                    }
                    SystemClock.sleep(200)
                }
                if (result.optString("playback") == "opening") result.put("playback", "timed out")
                val activity = watch()
                if (activity != null) instrumentation.runOnMainSync { activity.finish() }
                waitUntil("return from public playback", 15_000) { watch() == null && mainFocused() }
                waitUntil("public payload released", 15_000) {
                    val files = File(context.cacheDir, "payload").listFiles(); files == null || files.size == 0
                }
                app.persistence.submit(Runnable {}).get(); services.store.removeLibrary(film.key())
            } catch (failure: AddonException) { result.put("playback", "provider request failed").put("error", failure.kind.name) }
            finally {
                for (addon: tv.stream.app.data.InstalledAddon in services.store.addons()) services.addons.remove(addon.id)
                browse!!.onActivity { activity -> activity.back() }
                waitUntil("return to Home", 10_000) { mainFocused() && visible("Make yourself at home") }
                save(false)
            }
        }
        save(true)
        assertTrue("At least one live public torrent must actually render; inspect validation-torrents.json", rendered > 0)
    }

    private fun mainFocused(): Boolean {
        val focused = AtomicBoolean(); browse!!.onActivity { activity -> focused.set(activity.hasWindowFocus()) }; return focused.get()
    }
    private fun largestVideoLabel(node: android.view.accessibility.AccessibilityNodeInfo?): String? {
        if (node == null) return null
        var best: String? = if (node.text == null) null else node.text.toString()
        if (size(best) < 0) best = null
        for (i in 0 until node.childCount) {
            val candidate = largestVideoLabel(node.getChild(i))
            if (size(candidate) > size(best)) best = candidate
        }
        return best
    }
    private fun size(label: String?): Long {
        if (label == null) return -1
        val match = java.util.regex.Pattern.compile("(?s).+ · ([0-9]+) MiB$").matcher(label)
        return if (match.matches()) java.lang.Long.parseLong(match.group(1)!!) else -1
    }
    private fun tagged(tag: String): Boolean {
        val found = AtomicBoolean()
        browse!!.onActivity { activity -> val view: android.view.View? = activity.findViewById<android.view.View>(R.id.page_rows).findViewWithTag(tag); found.set(view != null && view.isShown) }
        return found.get()
    }
    @Throws(Exception::class)
    private fun capture(index: Int) {
        val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(bitmap)
        try { FileOutputStream(File(context.filesDir, "validation-torrent-$index.png")).use { out -> assertTrue(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)) } }
        finally { bitmap!!.recycle() }
    }
    @Throws(Exception::class)
    private fun save(complete: Boolean) {
        val report = JSONObject().put("complete", complete).put("film", FILM).put("providers", results)
        Files.write(File(context.filesDir, "validation-torrents.json").toPath(), report.toString(2).toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private const val FILM = "tt0063350"
        // The public-domain directory's published 1080p copy, verified by the live control, when another index offers it.
        private const val CONTROL = "11ea02584fa6351956f35671962ab46354d99060"
    }
}
