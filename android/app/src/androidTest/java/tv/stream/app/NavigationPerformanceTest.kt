package tv.stream.app

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.app.addon.Title

/** Local-fixture navigation and frame timing. Run alone; results describe this emulator, not a TV. */
@RunWith(AndroidJUnit4::class)
class NavigationPerformanceTest : AppHarness() {
    @Test fun detailsNavigationAndFrameTimes() {
        readyLibrary()
        val title = requireNotNull(Title.parse(services.store.addons()[0].id, JSONObject()
            .put("id", "fixture:film").put("type", "movie").put("name", "Generated Film")))
        launch()
        waitUntil("home ready", 10_000) { visible("Generated Film") }
        val frames = mutableListOf<Long>()
        var droppedReports = 0
        val metricsThread = HandlerThread("navigation-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            synchronized(frames) { if (duration > 0) frames.add(duration); droppedReports += dropped }
        }
        browse!!.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper)) }
        val elapsed = mutableListOf<Long>()
        val pss = mutableListOf<Int>()
        try {
            repeat(10) {
                val start = SystemClock.elapsedRealtime()
                browse!!.onActivity { it.openTitle(title, title.id) }
                val deadline = SystemClock.elapsedRealtime() + 10_000
                val ready = AtomicBoolean(false)
                while (!ready.get() && SystemClock.elapsedRealtime() < deadline) {
                    browse!!.onActivity { activity ->
                        ready.set(tv.stream.app.ui.PageFocus.has(activity.findViewById(R.id.page_rows), "play") && activity.hasWindowFocus())
                    }
                    if (!ready.get()) SystemClock.sleep(10)
                }
                assertTrue("Details controls did not arrive", ready.get())
                elapsed.add(SystemClock.elapsedRealtime() - start)
                val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory); pss.add(memory.totalPss)
                browse!!.onActivity { it.back() }
                waitUntil("home restored", 10_000) { visible("Generated Film") }
            }
        } finally {
            browse!!.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            metricsThread.quitSafely(); metricsThread.join()
        }
        val measured = synchronized(frames) { frames.toList() }
        assertTrue("FrameMetrics delivered no frames", measured.isNotEmpty())
        val sorted = measured.sorted()
        val p95 = sorted[(sorted.size * 95 + 99) / 100 - 1]
        val report = JSONObject().put("model", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS[0]).put("journey", "home-details-back")
            .put("detailsMs", JSONArray(elapsed)).put("pssKiB", JSONArray(pss))
            .put("frameCount", sorted.size).put("frameP95Ms", p95 / 1_000_000.0)
            .put("framesOver16_7ms", measured.count { it > 16_666_667L }).put("droppedMetricReports", droppedReports)
        merge(report)
    }

    /** Settings by remote: category moves (each re-renders the pane) and their frame times. */
    @Test fun settingsCategoryNavigationAndFrameTimes() {
        launch()
        waitUntil("home ready", 10_000) { visible("Make yourself at home") }
        browse!!.onActivity { it.root("Settings") }
        waitUntil("Settings category", 10_000) { focusedTag() == "settings:addons" }
        val frames = mutableListOf<Long>()
        val metricsThread = HandlerThread("settings-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            synchronized(frames) { if (duration > 0) frames.add(duration) }
        }
        browse!!.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper)) }
        val moves = mutableListOf<Long>()
        val pss = mutableListOf<Int>()
        try {
            repeat(3) {
                for (code in IntArray(6) { android.view.KeyEvent.KEYCODE_DPAD_DOWN } + IntArray(6) { android.view.KeyEvent.KEYCODE_DPAD_UP }) {
                    val before = focusedTag()
                    val start = SystemClock.elapsedRealtime()
                    instrumentation.sendKeyDownUpSync(code)
                    waitUntil("focus moved", 5_000) { focusedTag() != before }
                    instrumentation.waitForIdleSync()
                    moves.add(SystemClock.elapsedRealtime() - start)
                }
                val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory); pss.add(memory.totalPss)
            }
        } finally {
            browse!!.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            metricsThread.quitSafely(); metricsThread.join()
        }
        val measured = synchronized(frames) { frames.toList() }.sorted()
        assertTrue("FrameMetrics delivered no frames", measured.isNotEmpty())
        val movesSorted = moves.sorted()
        merge(JSONObject().put("settings", JSONObject().put("journey", "settings-categories-down-up-x3")
            .put("moveMs", JSONArray(moves)).put("moveP50Ms", movesSorted[movesSorted.size / 2])
            .put("moveP95Ms", movesSorted[(movesSorted.size * 95 + 99) / 100 - 1]).put("pssKiB", JSONArray(pss))
            .put("frameCount", measured.size).put("frameP95Ms", measured[(measured.size * 95 + 99) / 100 - 1] / 1_000_000.0)
            .put("framesOver16_7ms", measured.count { it > 16_666_667L })))
    }

    private fun focusedTag(): String? {
        val tag = java.util.concurrent.atomic.AtomicReference<String?>()
        browse!!.onActivity { tag.set(it.currentFocus?.tag as? String) }
        return tag.get()
    }

    /** Both journeys share one report, whichever runs first. */
    private fun merge(part: JSONObject) {
        val file = File(context.filesDir, "validation-navigation-performance.json")
        val report = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        for (key in part.keys()) report.put(key, part.get(key))
        report.put("measuredAtMs", System.currentTimeMillis())
        file.writeText(report.toString(2))
    }
}
