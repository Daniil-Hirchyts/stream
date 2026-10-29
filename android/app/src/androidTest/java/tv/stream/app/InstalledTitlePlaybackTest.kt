package tv.stream.app

import org.junit.Assert.*
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.app.addon.*
import tv.stream.app.data.InstalledAddon
import tv.stream.playback.PlaybackSnapshot

/** Explicit opt-in title reproduction using only providers the owner already accepted. No configured URLs in evidence. */
@RunWith(AndroidJUnit4::class)
class InstalledTitlePlaybackTest : AppHarness() {
    private val installed: MutableList<InstalledAddon> = ArrayList()
    private var results = JSONArray()
    private val titles = JSONArray()
    private var titleId: String? = null
    private lateinit var requestedTitles: Array<String>
    private var matrix = false

    @Before @Throws(Exception::class) override fun configure() {
        val requested = InstrumentationRegistry.getArguments().getString("titleIds",
                InstrumentationRegistry.getArguments().getString("titleId", ""))
        org.junit.Assume.assumeTrue("Supply explicitly requested IMDb IDs", requested.matches(Regex("tt[0-9]+(?:,tt[0-9]+)*")))
        requestedTitles = requested.split(",").toTypedArray(); matrix = requestedTitles.size > 1; titleId = requestedTitles[0]
        val owner = context.applicationContext as TvApplication
        for (addon: InstalledAddon in owner.services().store.addons()) if (addon.enabled) installed.add(addon)
        super.configure()
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit()
                .putBoolean("localOnly", false).putBoolean("publicNetworkOnly", true)
                .remove("peer").remove("cacheBytes").commit()
        app.resetServices(); services = app.services()
    }

    @Test @Throws(Exception::class) fun installedProvidersPlayTheRequestedTitle() {
        assertFalse("Owner has accepted providers", installed.isEmpty()); launch()
        for (requested in requestedTitles) {
        titleId = requested; results = JSONArray()
        val titleResult = JSONObject().put("titleId", titleId).put("providers", results).put("complete", false)
        titles.put(titleResult)
        for (index in (if (matrix) installed.size else 0)..installed.size) {
            val combined = index == installed.size
            val addon = installed[if (combined) 0 else index]
            val result = JSONObject().put("name", if (combined) "All installed (automatic)" else addon.manifest.name)
            results.put(result); save(false)
            // Installing changes provider scopes, so create the request only after acceptance is copied.
            if (combined) for (accepted in installed) services.addons.install(accepted.address.manifest, accepted.manifest)
            else services.addons.install(addon.address.manifest, addon.manifest)
            try {
                val scope = services.addons.newRequest()
                try {
                    val response = services.addons.streams("movie", titleId!!, scope)
                    val failures = JSONArray()
                    for (kind in response.failures.values) failures.put(kind.name)
                    result.put("requestFailures", failures)
                            .put("eligibleProviders", services.addons.providers("stream", "movie", titleId).size)
                    val all = response.items
                    val ranked = SourcePolicy.ranked(all)
                    result.put("sourceCount", all.size).put("supportedCount", ranked.size)
                    if (ranked.isEmpty()) { result.put("result", if (!response.failures.isEmpty()) "provider request failed"
                            else if (all.isEmpty()) "no sources returned" else "no compatible sources"); continue }
                    result.put("firstCandidateKind", ranked[0].kind.name).put("selection", "automatic; at most three attempts")
                    val title = Title.parse(addon.id, JSONObject().put("id", titleId).put("type", "movie").put("name", InstrumentationRegistry.getArguments().getString("titleName", "Requested film")))!!
                    browse!!.onActivity { activity -> activity.play(title, titleId!!, "") }
                    val began = SystemClock.elapsedRealtime(); val until = began + 250_000; var errorSince: Long = 0
                    result.put("result", "opening")
                    while (SystemClock.elapsedRealtime() < until) {
                        val state: PlaybackSnapshot? = state()
                        val sample = diagnostics()
                        result.put("diagnostics", sample)
                        if (sample.has("peers")) result.put("lastTransfer", sample)
                        if (state != null && state.error != null) {
                            if (errorSince == 0L) errorSince = SystemClock.elapsedRealtime()
                            if (SystemClock.elapsedRealtime() - errorSince >= 2000) {
                                result.put("result", "error").put("code", state.error!!.code).put("message", state.error!!.message); break
                            }
                        } else errorSince = 0
                        if (state != null && state.renderedFirstFrame && state.playing && state.positionMs >= (if (matrix) 20_000 else 3500)) {
                            result.put("result", if (state.durationMs >= 60 * 60_000) "rendered and advancing" else "unexpected short media")
                                    .put("durationMs", state.durationMs)
                            if (combined && state.durationMs >= 60 * 60_000) {
                                val playing = watch()
                                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
                                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                                instrumentation.runOnMainSync { assertTrue("Up and OK open real-film options",
                                        playing!!.findViewById<android.view.View>(tv.stream.playback.R.id.menu).isShown) }
                                result.put("remoteOptions", "opened with Up and OK")
                                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                                instrumentation.runOnMainSync { AppFlowTest.session(playing).seekTo((30 * 60_000).toLong()) }
                                try {
                                    waitUntil("requested film plays after seek to 30 minutes", 90_000) { state() != null
                                            && state()!!.playing && state()!!.positionMs >= 30 * 60_000 + (if (matrix) 10_000 else 1500) }
                                    result.put("seek30Minutes", "passed").put("afterSeek", diagnostics())
                                } catch (failedSeek: AssertionError) { result.put("seek30Minutes", "failed") }

                            }
                            val image: android.graphics.Bitmap? = instrumentation.uiAutomation.takeScreenshot()
                            if (image != null) {
                                try {
                                    java.io.FileOutputStream(File(context.filesDir,
                                            "validation-requested-title-$titleId-$index.png")).use { out ->
                                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                } finally { image.recycle() }
                            }
                            break
                        }
                        if (visible("Choose a video file")) { result.put("result", "requires file choice"); break }
                        save(false); SystemClock.sleep(500)
                    }
                    if (result.optString("result") == "opening") result.put("result", "timed out")
                    result.put("elapsedMs", SystemClock.elapsedRealtime() - began)
                    val player = watch(); if (player != null) instrumentation.runOnMainSync { player.finish() }
                    waitUntil("player closes", 15_000) { watch() == null }
                    waitUntil("torrent payload released", 15_000) {
                        val files = File(context.cacheDir, "payload").listFiles(); files == null || files.size == 0
                    }
                } finally { scope.close() }
            } catch (failure: AddonException) {
                result.put("result", "provider request failed").put("code", failure.kind.name)
            } finally {
                for (accepted in services.store.addons()) services.addons.remove(accepted.id)
                save(false)
            }
        }
        titleResult.put("complete", true)
        }
        save(true)
        // This is a diagnostic audit: individual playback failures are recorded, not converted into an overall pass claim.
    }
    @org.junit.After @Throws(Exception::class) override fun cleanup() {
        val initialized = try { app; true } catch (unset: UninitializedPropertyAccessException) { false }
        if (initialized) super.cleanup()
    }
    @Throws(Exception::class)
    private fun save(complete: Boolean) {
        Files.write(File(context.filesDir, "validation-requested-title.json").toPath(),
                JSONObject().put("complete", complete).put("titleId", titleId).put("providers", results).put("titles", titles).toString(2).toByteArray(StandardCharsets.UTF_8))
    }
}
