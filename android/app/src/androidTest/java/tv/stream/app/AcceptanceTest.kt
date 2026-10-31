package tv.stream.app

import org.junit.Assert.*
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayList
import tv.stream.app.addon.*
import tv.stream.app.data.InstalledAddon

/** Full application paths beyond the small, fast simulator smoke suite. */
@RunWith(AndroidJUnit4::class)
class AcceptanceTest : AppHarness() {
    @Test @Throws(Exception::class) fun directMp4MkvHlsAndDashRenderAndAdvance() {
        for (format in arrayOf("mp4", "mkv", "hls", "dash")) {
            for (addon: InstalledAddon in services.store.addons()) services.addons.remove(addon.id)
            readyLibrary("http-$format"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource()
            waitUntil("$format first frame", 40_000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs > 1000 }
            val activity = watch()
            if (format == "mkv") {
                assertEquals(2, state()!!.audioTracks.size.toLong()); assertFalse(state()!!.textTracks.isEmpty())
                instrumentation.runOnMainSync { AppFlowTest.session(activity).selectAudio(stateOnMain(activity!!)!!.audioTracks[1]); AppFlowTest.session(activity).selectSubtitles(stateOnMain(activity)!!.textTracks[0]) }
                waitUntil("Russian audio selected", 5000) { state()!!.audio != null && ("rus" == state()!!.audio!!.language || "ru" == state()!!.audio!!.language) }
                waitUntil("audio choice remembered by language", 5000) { services.store.preference("audioLanguage", "").startsWith("ru") }
            }
            if (format == "dash") {
                assertTrue("Adaptive qualities", state()!!.qualities.size >= 2)
                instrumentation.runOnMainSync { AppFlowTest.session(activity).selectQuality(stateOnMain(activity!!)!!.qualities[0]) }
                assertNotNull(state()!!.quality)
            }
            instrumentation.runOnMainSync { AppFlowTest.session(activity).pause(); AppFlowTest.session(activity).seekTo(8000); AppFlowTest.session(activity).setSpeed(1.25f) }
            waitUntil("paused seek", 10_000) { state()!!.positionMs >= 8000 && !state()!!.playWhenReady }
            assertEquals(1.25f, state()!!.speed, 0.001f)
            instrumentation.runOnMainSync { AppFlowTest.session(activity).play() }
            waitUntil("resumes after seek", 15_000) { state()!!.playing && state()!!.positionMs > 9000 }
            instrumentation.runOnMainSync(activity!!::finish); instrumentation.waitForIdleSync()
            browse!!.close(); browse = null
        }
    }

    @Test @Throws(Exception::class) fun automaticFailoverIsBoundedAndManualSourcesDoNotSilentlyChange() {
        readyLibrary("failover"); launch(); tap("Generated Film", true); startAutomaticFixturePlayback()
        waitUntil("second source renders", 40_000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
        val activity = watch()
        assertEquals(2, field(activity, "attempts"))
        instrumentation.runOnMainSync(activity!!::finish); instrumentation.waitForIdleSync(); browse!!.close(); browse = null
        launch(); tap("Generated Film", false); tap("Sources", true); tap("Unavailable source", false)
        waitUntil("manual source failure", 20_000) {
            val snapshot = state()
            snapshot != null && snapshot.error != null && snapshot.error!!.code == "opening"
        }
        assertEquals(1, field(watch(), "attempts")); assertFalse(state()!!.renderedFirstFrame)
        instrumentation.runOnMainSync(watch()!!::finish); instrumentation.waitForIdleSync(); browse!!.close(); browse = null
        for (addon: InstalledAddon in services.store.addons()) services.addons.remove(addon.id)
        readyLibrary("failover-all"); launch(); tap("Generated Film", true); startAutomaticFixturePlayback()
        waitUntil("bounded failure", 40_000) {
            val snapshot = state()
            snapshot != null && snapshot.error != null && snapshot.error!!.code == "opening"
                    && Integer.valueOf(3) == field(watch(), "attempts")
        }
        assertEquals(3, field(watch(), "attempts")); assertFalse(state()!!.renderedFirstFrame)
        waitUntil("source choice is accessible after failure", 5000) { visible("Choose source") }
    }
    @Test @Throws(Exception::class) fun pressuredPayloadResetKeepsTheChosenEpisodeAndPosition() {
        readyLibrary("multiple-files"); launch(); tap("Generated Film", true); tap("Play", true)
        waitUntil("episode file choice", 30000) { visible("Choose a video file") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("chosen episode renders", 30000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
        val activity = watch(); val coordinator = field(activity, "coordinator") as PlaybackCoordinator
        val selected = coordinator.fileIndex
        instrumentation.runOnMainSync { AppFlowTest.session(activity).seekTo(6000) }
        waitUntil("position before pressure", 10000) { state()!!.playing && state()!!.positionMs >= 6000 }
        val before = state()!!.positionMs
        // The native stalled-peer test supplies real cache pressure. Here inject its reported condition
        // to exercise the UI handoff, real session/storage drain, and selected-file restoration.
        instrumentation.runOnMainSync {
            try {
                val stats = WatchActivity::class.java.getDeclaredField("nativeStats"); stats.isAccessible = true
                stats.set(activity, org.json.JSONObject().put("urgentReservationFailures", 1).put("obsoletePartials", 1).put("verifiedReadBytes", 0))
                (field(activity, "startupWatchdog") as StartupWatchdog).start(android.os.SystemClock.elapsedRealtime() - 7000)
                val tick = field(activity, "tick") as Runnable; (field(activity, "main") as android.os.Handler).removeCallbacks(tick); tick.run()
            } catch (error: Exception) { throw AssertionError(error) }
        }
        waitUntil("same episode restarts after storage drain", 30000) { state()!!.error == null && state()!!.renderedFirstFrame && state()!!.playing
                && state()!!.positionMs >= before && coordinator.fileIndex == selected && !visible("Choose a video file") }
        assertEquals(1, diagnostics().getInt("sourceAttempts").toLong())
        assertEquals(0, diagnostics().getJSONArray("sourceFailures").length().toLong())
    }

    @Test @Throws(Exception::class) fun decoderAndNetworkFailuresAfterFramesResumeOnDifferentSources() {
        readyLibrary("recover-after-frame"); launch(); tap("Generated Film", true); startAutomaticFixturePlayback()
        waitUntil("initial video", 30_000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
        val activity = watch()
        instrumentation.runOnMainSync { AppFlowTest.session(activity).setSpeed(1.5f); AppFlowTest.session(activity).seekTo(8000) }
        waitUntil("playing beyond initial frame", 10_000) { state()!!.playing && state()!!.positionMs >= 8500 }
        val position = state()!!.positionMs
        instrumentation.runOnMainSync { AppFlowTest.session(activity).fail(tv.stream.playback.PlaybackErrors.describe(context,
                androidx.media3.common.PlaybackException("Injected real decoder failure path", null,
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED))) }
        waitUntil("decoder failure recovers after first frame", 30_000) { Integer.valueOf(2) == field(activity, "attempts")
                && state() != null && state()!!.error == null && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs >= position }
        assertEquals("Recovery keeps the chosen speed", 1.5f, state()!!.speed, 0.001f)
        val resumed = state()!!.positionMs
        instrumentation.runOnMainSync { AppFlowTest.session(activity).fail(tv.stream.playback.PlaybackErrors.Description(
                "unsupported", "Injected error code collision", "Cannot play this source", "test", 0, false)) }
        waitUntil("shared error vocabulary still recovers", 30_000) { Integer.valueOf(3) == field(activity, "attempts")
                && state() != null && state()!!.error == null && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs >= resumed }
        instrumentation.runOnMainSync { AppFlowTest.session(activity).fail(tv.stream.playback.PlaybackErrors.describe(context,
                androidx.media3.common.PlaybackException("Injected network failure path", null,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))) }
        waitUntil("recovery budget is bounded after frames too", 5000) { state() != null && state()!!.error != null
                && "opening" == state()!!.error!!.code }
        assertEquals(3, field(activity, "attempts"))
        assertEquals(3, diagnostics().getJSONArray("sourceFailures").length().toLong())
        waitUntil("source choice is accessible after failure", 5000) { visible("Choose source") }
    }

    @Test @Throws(Exception::class) fun activityRecreationRetainsPauseSpeedPositionAndNativeTelemetry() {
        playFilm(); val original = watch()
        instrumentation.runOnMainSync { AppFlowTest.session(original).setSpeed(1.5f); AppFlowTest.session(original).pause(); AppFlowTest.session(original).seekTo(6000) }
        waitUntil("paused before recreation", 10000) { !state()!!.buffering && state()!!.positionMs >= 6000 && !state()!!.playWhenReady }
        instrumentation.runOnMainSync(original!!::recreate)
        waitUntil("restored paused video", 30000) { watch() != null && watch() !== original && state() != null && state()!!.error == null && state()!!.renderedFirstFrame && !state()!!.buffering }
        assertFalse(state()!!.playWhenReady); assertEquals(1.5f, state()!!.speed, .001f); assertTrue(state()!!.positionMs >= 6000)
        waitUntil("native telemetry resumes", 10000) { diagnostics().optLong("verifiedReadBytes") > 0 }
        val restored = watch(); instrumentation.runOnMainSync { AppFlowTest.session(restored).play() }
        waitUntil("restored playback advances", 10000) { state()!!.playing && state()!!.positionMs > 6500 }
    }
    @Test @Throws(Exception::class) fun manuallyChangingSourcesRetainsTheCurrentPausedPositionAndSpeed() {
        readyLibrary("recover-after-frame"); launch(); tap("Generated Film", false); tap("Play", true)
        waitUntil("video before source change", 30000) { state() != null && state()!!.playing && state()!!.renderedFirstFrame }
        val activity = watch()
        instrumentation.runOnMainSync { AppFlowTest.session(activity).setSpeed(1.5f); AppFlowTest.session(activity).pause(); AppFlowTest.session(activity).seekTo(6000) }
        waitUntil("paused before source change", 10000) { !state()!!.buffering && state()!!.positionMs >= 6000 }
        instrumentation.runOnMainSync(activity!!::onChooseSource)
        waitUntil("manual source dialog", 5000) { visible("Choose source") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("replacement source ready", 30000) { state()!!.error == null && state()!!.renderedFirstFrame && !state()!!.buffering }
        assertFalse(state()!!.playWhenReady); assertEquals(1.5f, state()!!.speed, .001f); assertTrue(state()!!.positionMs >= 6000)
        assertFalse(diagnostics().getBoolean("automatic"))
    }

    private fun field(activity: WatchActivity?, name: String): Any? {
        if (name == "attempts") {
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            instrumentation.runOnMainSync { attempts.set(activity!!.diagnosticSnapshot().getInt("sourceAttempts")) }
            return attempts.get()
        }
        try { val field = WatchActivity::class.java.getDeclaredField(name); field.isAccessible = true; return field.get(activity) }
        catch (e: Exception) { throw AssertionError(e) }
    }

    @Test @Throws(Exception::class) fun contentFileAndMagnetImportsUseRealMetadataAndPlayback() {
        val file = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("content://tv.stream.app.test.files/series.torrent"), context, MainActivity::class.java)
                .setType("application/x-bittorrent").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // setType clears data; Android requires the pair to be set together.
        file.setDataAndType(android.net.Uri.parse("content://tv.stream.app.test.files/series.torrent"), "application/x-bittorrent")
        browse = androidx.test.core.app.ActivityScenario.launch<MainActivity>(file)
        waitUntil("file import dialog", 10_000) { visible("Name this import") }; enter("Content file"); tap("Continue", false)
        waitUntil("private copy saved", 10_000) { services.store.importIds().size == 1 }
        assertEquals("file", services.store.importDescriptor(services.store.importIds()[0])!!.optString("kind"))
        tap("Content file", true); tap("Play", false); waitUntil("file metadata chooser", 30_000) { visible("Choose a video file") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("copied file plays", 30_000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
        instrumentation.runOnMainSync(watch()!!::finish)
        waitUntil("import details regain window focus", 10_000) {
            val focused = java.util.concurrent.atomic.AtomicBoolean()
            browse!!.onActivity { activity -> focused.set(activity.hasWindowFocus()) }
            watch() == null && focused.get() && visible("Rename import")
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) // Imported title details return to the Imports list.
        tap("Add magnet or torrent URL", false)
        var hash: String? = null
        AddonClient().use { client ->
            Cancellation().use { request ->
                val bytes = client.fetch("$origin/index.json", 2 shl 20, request, AddonClient.deadline())
                val index = org.json.JSONArray(String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                for (i in 0 until index.length()) if (index.getJSONObject(i).getString("name") == "film-hybrid") hash = index.getJSONObject(i).getString("v1")
            }
        }
        assertNotNull(hash); enter("magnet:?xt=urn:btih:$hash"); tap("Continue", true); enter("Magnet import"); tap("Continue", false)
        waitUntil("magnet saved", 5000) { services.store.importIds().size == 2 }; tap("Magnet import", true); tap("Play", false)
        waitUntil("magnet metadata and playback", 40_000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
    }

    @Test @Throws(Exception::class) fun importsChooseAFileRenameAndRemoveWithoutStartingDuringBrowse() {
        launch(); tap("Settings", true); tap("Torrents", false); tap("Imports", true); tap("Add magnet or torrent URL", false)
        enter("$origin/torrents/series.torrent"); tap("Continue", true); enter("Three episodes"); tap("Continue", false)
        waitUntil("descriptor saved", 5000) { services.store.importIds().size == 1 }
        val payload = java.io.File(context.cacheDir, "payload").listFiles(); assertTrue(payload == null || payload.size == 0)
        tap("Three episodes", true); tap("Play", false)
        waitUntil("file chooser", 30_000) { visible("Choose a video file") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("chosen episode plays", 30_000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
        assertTrue(services.store.importDescriptor(services.store.importIds()[0])!!.has("fileIdx"))
        instrumentation.runOnMainSync(watch()!!::finish); tap("Rename import", true); enter("Renamed episodes"); tap("Continue", false)
        waitUntil("renamed", 5000) { "Renamed episodes" == services.store.importDescriptor(services.store.importIds()[0])!!.optString("name") }
        tap("Remove import", true); waitUntil("removed", 5000) { services.store.importIds().isEmpty() }
    }

    @Test @Throws(Exception::class) fun addonSettingsAndPlaybackPreferencesWorkThroughRealRows() {
        launch(); tap("Settings", true); tap("Add add-on", false)
        enter("$origin/addon/good/settings/manifest.json"); tap("Continue", true); tap("Install", false)
        waitUntil("addon installed", 5000) { services.store.addons().size == 1 }
        tap("Local fixtures", true); tap("Disable add-on", false)
        waitUntil("disabled", 5000) { !services.store.addons()[0].enabled }; tap("Enable add-on", true)
        waitUntil("enabled", 5000) { services.store.addons()[0].enabled }
        tap("Move earlier", false); tap("Move later", true); tap("Generated films", false)
        waitUntil("catalogue hidden", 5000) { val a = services.store.addons()[0]; !services.store.catalogVisible(a.id, "movie", "films/list") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); tap("Settings", true)
        tap("Languages", false); tap("Audio language", false); tap("Russian", true); tap("Subtitle language", false); tap("English", true)
        tap("Subtitles", true)
        // {preference, option, row label}: the edge style's row is labelled Background.
        for (setting in arrayOf(arrayOf("size", "large", "size"), arrayOf("font", "condensed", "font"), arrayOf("color", "yellow", "color"), arrayOf("edge", "outline", "background"), arrayOf("position", "raised", "position"))) {
            tap("Subtitle " + setting[2], false); tap(setting[1], true)
            waitUntil("style saved", 5000) { setting[1] == services.playerPreferences.getString("subtitle_" + setting[0], "") }
        }
        tap("Playback", false); tap("Playback cache", false); tap("128 MiB", true); waitUntil("cache preference", 5000) { services.cacheBytes() == (128L shl 20) }
        tap("Play next episode", false); waitUntil("autoplay preference", 5000) { services.store.preference("autoplay", "true") == "false" }
        tap("Clear app playback and metadata cache", true); tap("About", false); tap("Developer mode", false); tap("Developer", false); tap("Open source notices", true); tap("Streaming engine (Rust crates)", false)
        waitUntil("license text", 5000) { visible("Open source notices") }; instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        tap("Settings", false); tap("Add-ons", false); tap("Local fixtures", true); tap("Remove add-on", false)
        waitUntil("addon removed", 5000) { services.store.addons().isEmpty() }
        assertEquals("rus", services.store.preference("audioLanguage", "")); assertEquals("eng", services.store.preference("subtitleLanguage", ""))
    }

    @Test @Throws(Exception::class) fun searchPaginationWatchedStateAndFocusSurviveNavigation() {
        readyLibrary(); launch(); tap("Generated Film", true); tap("Mark watched", false)
        waitUntil("watched persisted", 5000) { services.store.history(false).stream().anyMatch { p -> p.watched } }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("Card focus restored", 10_000) { val focused = java.util.concurrent.atomic.AtomicBoolean(); browse!!.onActivity { activity -> focused.set(activity.currentFocus != null && (activity.currentFocus!!.tag as Any?).toString().contains("fixture:film")) }; focused.get() }
        tap("Search", true); waitUntil("search keyboard", 5000) { visible("Type g") }
        for (c in "generated".toCharArray()) tap("Type $c", c.code % 2 == 0)
        waitUntil("search results", 10_000) { visible("Generated Film") }; captureUi("search"); tap("Back", false)
        tap("Browse", true); captureUi("categories"); tap("Generated films", false)
        tap("Generated", true); captureUi("catalogue-header")
        for (step in 0 until 3) {
            if (focusedKey().startsWith("next-page:")) break
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); android.os.SystemClock.sleep(250)
        }
        waitUntil("remote reaches footer next page", 5000) { focusedKey().startsWith("next-page:") }
        waitUntil("pagination footer is fully visible", 5000) {
            val atBottom = java.util.concurrent.atomic.AtomicBoolean()
            browse!!.onActivity { activity -> atBottom.set(!activity.findViewById<android.view.View>(R.id.page_rows).canScrollVertically(1)) }
            atBottom.get()
        }
        captureUi("catalogue-pagination"); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("next page", 10_000) { visible("Films · Page 2") }; captureUi("catalogue")
        tap("Previous page", false)
        waitUntil("first page", 10_000) { visible("Films · Page 1") }
        tap("Back", true)
        waitUntil("back to categories", 5000) { visible("Browse") && visible("Generated films") }
    }
    @Test @Throws(Exception::class) fun searchGridKeepsFocusedCardsInsideVisibleViewport() {
        launch(); tap("Search", true); waitUntil("keyboard ready", 5000) { visible("Type a") }
        // Exercise the production search renderer with enough local cards to cross both viewport edges.
        browse!!.onActivity { activity ->
            val item = tv.stream.app.ui.Item.keyboard("keyboard", "") { ignored -> }
            item.tiles = ArrayList()
            for (i in 0 until 25) (item.tiles as MutableList<tv.stream.app.ui.Item.Tile>).add(tv.stream.app.ui.Item.Tile("edge-card:$i",
                    tv.stream.app.ui.Item.Tile.Style.POSTER, "Generated $i", "2026", null, null, Runnable {}))
            try {
                val field = MainActivity::class.java.getDeclaredField("adapter"); field.isAccessible = true
                (field.get(activity) as tv.stream.app.ui.PageAdapter).set(java.util.Collections.singletonList(item), "edge-card:0")
            } catch (error: ReflectiveOperationException) { throw AssertionError(error) }
        }
        instrumentation.waitForIdleSync()
        // The keyboard keeps focus while typing; Right from its last column moves into the results.
        browse!!.onActivity { activity -> assertNotNull(focusKey(activity, "keyboard:f")) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitUntil("Right moves into the results", 5000) { focusedKey().startsWith("edge-card:") }
        for (i in 0 until 3) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); android.os.SystemClock.sleep(300) }
        captureUi("search-scroll")
        val key = focusedKey()
        assertTrue("Focus stayed in search results: $key", key.startsWith("edge-card:"))
        assertTrue("Down moved through the results: $key", key.removePrefix("edge-card:").toInt() >= 12)
        // Compose clips window bounds by every ancestor but not a node's size, so equal heights mean nothing is cut off.
        waitUntil("Focused card and caption are fully visible", 5000) {
            val card = semanticNode(key) ?: return@waitUntil false
            Math.abs(card.boundsInWindow.height - card.size.height) < 1f
        }
    }
    /** The key of the control holding focus: declarative controls report theirs through the page's focus owner. */
    private fun focusedKey(): String {
        val key = java.util.concurrent.atomic.AtomicReference("")
        browse!!.onActivity { activity -> key.set((activity.currentFocus?.tag as Any?)?.toString() ?: "") }
        return key.get()
    }
    @Throws(Exception::class)
    private fun captureUi(name: String) {
        android.os.SystemClock.sleep(300)
        val bitmap: android.graphics.Bitmap? = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("UI evidence $name", bitmap)
        try {
            java.io.FileOutputStream(java.io.File(context.filesDir, "ui-$name.png")).use { out ->
                bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally { bitmap!!.recycle() }
    }

    companion object {
        @JvmStatic private fun stateOnMain(activity: WatchActivity): tv.stream.playback.PlaybackSnapshot? { return activity.playbackSnapshot() }
    }
}
