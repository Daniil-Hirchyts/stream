package tv.stream.app

import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileWriter
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.app.addon.Title
import org.junit.Assert.*

/** Twenty isolated player/engine lifetimes per transport, using only prepared loopback fixtures. */
@RunWith(AndroidJUnit4::class)
class StartupTimingTest : AppHarness() {
    @Test @Throws(Exception::class) fun preparedHttpAndColdTorrentStartupHaveMeasuredPercentiles() {
        val report = JSONObject()
        val transport = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("transport", "all")
        for (configuration in arrayOf("http-mp4", "ui")) {
            if (transport != "all" && transport != configuration) continue
            for (addon in services.store.addons()) services.addons.remove(addon.id)
            readyLibrary(configuration)
            val title = Title.parse(services.store.addons()[0].id, JSONObject()
                    .put("id", "fixture:film").put("type", "movie").put("name", "Generated Film"))!!
            if (browse != null) { browse!!.close(); browse = null }
            launch(); AppHarness.waitUntil("home ready", 10000) { visible("Generated Film") }
            val times: MutableList<Long> = ArrayList(); val details: MutableList<Long> = ArrayList(); val traces = JSONArray()
            for (run in 0 until 20) {
                services.store.progress(title, title.id, 0, 0, false)
                val opened = SystemClock.elapsedRealtime()
                browse!!.onActivity { activity -> activity.openTitle(title, title.id) }
                waitFast {
                    val shown = AtomicBoolean()
                    browse!!.onActivity { activity -> shown.set(tv.stream.app.ui.PageFocus.has(activity.findViewById(R.id.page_rows), "play")) }
                    shown.get()
                }
                details.add(SystemClock.elapsedRealtime() - opened)
                val entry = services.preparation.prepare(title, title.id)
                services.addons.newRequest().use { scope -> assertFalse(entry.awaitCandidate(scope, "").isEmpty()) }
                val before = File(context.cacheDir, "payload").listFiles()
                assertTrue("Details must not allocate torrent payload", before == null || before.size == 0)
                browse!!.onActivity { activity -> assertTrue("Play takes focus", tv.stream.app.ui.PageFocus.request(activity.findViewById(R.id.page_rows), "play")) }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                try { AppHarness.waitUntil("first video and audio output", 30000) { watch() != null && diagnostics().optLong("playTapToFirstAvMs") > 0 } }
                catch (failure: AssertionError) {
                    val error = diagnostics().put("run", run).put("configuration", configuration)
                    val active = watch()
                    if (active != null) instrumentation.runOnMainSync {
                        try { val field = WatchActivity::class.java.getDeclaredField("failureCause"); field.isAccessible = true
                            val cause = field.get(active) as Throwable?; error.put("failure", android.util.Log.getStackTraceString(cause)) }
                        catch (ignored: Exception) { }
                    }
                    FileWriter(File(context.filesDir, "validation-startup-error.json")).use { out -> out.write(error.toString(2)) }
                    throw failure
                }
                val trace = diagnostics(); times.add(trace.getLong("playTapToFirstAvMs")); traces.put(trace.getJSONObject("startupStagesMs"))
                instrumentation.runOnMainSync(watch()!!::finish)
                AppHarness.waitUntil("browser resumed", 10000) {
                    val resumed = AtomicBoolean(); browse!!.onActivity { activity -> resumed.set(activity.hasWindowFocus()) }
                    watch() == null && resumed.get()
                }
                app.playbackLoader.submit(Runnable { }).get()
                AppHarness.waitUntil("previous engine storage removed", 10000) { val left = File(context.cacheDir, "payload").listFiles(); left == null || left.size == 0 }
                browse!!.onActivity { activity -> activity.back() }
                services.preparation.leave()
            }
            val measurements = JSONObject().put("runs", 20).put("firstAvMs", JSONArray(times))
                    .put("firstAvP50Ms", percentile(times, .5)).put("firstAvP95Ms", percentile(times, .95))
                    .put("detailsMs", JSONArray(details)).put("detailsP95Ms", percentile(details, .95)).put("stages", traces)
            report.put(configuration, measurements)
            FileWriter(File(context.filesDir, "validation-startup-timing.json")).use { out -> out.write(report.toString(2)) }
        }
    }
    private fun waitFast(condition: java.util.function.BooleanSupplier) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < end) { if (condition.asBoolean) return; SystemClock.sleep(10) }
        fail("Details controls did not arrive")
    }
    companion object {
        private fun percentile(values: List<Long>, percentile: Double): Long { val sorted: MutableList<Long> = ArrayList(values); Collections.sort(sorted); return sorted[Math.ceil(sorted.size * percentile).toInt() - 1] }
    }
}
