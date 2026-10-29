package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Measured cached/uncached seeks and actual process memory, with a real rendered screenshot. */
@RunWith(AndroidJUnit4::class)
class ProfileTest : AppHarness() {
    @Test @Throws(Exception::class) fun measureRealPlayback() {
        playFilm(); val activity = watch()
        AppHarness.waitUntil("buffered seek target", 10_000) { state()!!.bufferedMs >= 8000 }
        val first = diagnostics()
        val cached = seek(activity, 5000)
        assertTrue("Uncached seek is outside Media3's retained buffer", state()!!.bufferedMs < 35_000)
        val forward = seek(activity, 36_000); val backward = seek(activity, 1000)
        AppHarness.waitUntil("native measurements", 5000) { diagnostics().has("cache") }
        val measured = diagnostics(); val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
        measured.put("pssBytes", memory.totalPss * 1024L).put("rssBytes", procBytes("VmRSS")).put("peakRssBytes", procBytes("VmHWM"))
                .put("cachedSeekMs", cached).put("uncachedForwardSeekMs", forward).put("uncachedBackwardSeekMs", backward)
                .put("initial", first).put("model", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT).put("abi", android.os.Build.SUPPORTED_ABIS[0])
        assertTrue(measured.getJSONObject("cache").getLong("allocatedBytes") <= (16L shl 20))
        Files.write(File(context.filesDir, "validation-profile.json").toPath(), measured.toString().toByteArray(StandardCharsets.UTF_8))
        val image = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(image)
        FileOutputStream(File(context.filesDir, "validation-profile.png")).use { output -> assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        image.recycle()
    }
    private fun seek(activity: WatchActivity?, position: Long): Long {
        val started = SystemClock.elapsedRealtime(); instrumentation.runOnMainSync { AppFlowTest.session(activity).seekTo(position) }
        AppHarness.waitUntil("measured seek $position", 30_000) { state()!!.playing && state()!!.positionMs >= position + 250 && state()!!.positionMs < position + 5000 }
        return SystemClock.elapsedRealtime() - started
    }
    companion object {
        @Throws(Exception::class)
        private fun procBytes(key: String): Long {
            for (line in Files.readAllLines(File("/proc/self/status").toPath(), StandardCharsets.US_ASCII))
                if (line.startsWith("$key:")) return line.substring(line.indexOf(':') + 1).trim().split(Regex("\\s+"))[0].toLong() * 1024
            return 0
        }
    }
}
