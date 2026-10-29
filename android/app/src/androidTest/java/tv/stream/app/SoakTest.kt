package tv.stream.app

import org.junit.Assert.*
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Explicit `simulator.sh soak`: thirty real minutes of playback/rewinds, never time-compressed. */
@RunWith(AndroidJUnit4::class)
class SoakTest : AppHarness() {
    @Test @Throws(Exception::class) fun playbackAndSeekSoak() {
        val seconds = InstrumentationRegistry.getArguments().getString("durationSeconds", "1800").toInt()
        assertTrue("The acceptance soak must last at least thirty minutes", seconds >= 1800)
        playFilm(); val activity = watch()
        val started = SystemClock.elapsedRealtime(); val deadline = started + seconds * 1000L; var maxPss: Long = 0; var maxCache: Long = 0
        val samples = JSONArray(); var cycles = 0
        val evidence = File(context.filesDir, "validation-soak.json")
        while (SystemClock.elapsedRealtime() < deadline) {
            val target: Long = if (cycles % 3 == 0) 35_000 else if (cycles % 3 == 1) 1000 else 15_000
            val seekStart = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { AppFlowTest.session(activity).seekTo(target) }
            AppHarness.waitUntil("soak seek $cycles", 30_000) { state() != null && state()!!.playing && state()!!.positionMs >= target + 1000 && state()!!.positionMs < target + 10_000 }
            val recovered = SystemClock.elapsedRealtime() - seekStart
            val sample = diagnostics()
            val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
            maxPss = Math.max(maxPss, memory.totalPss * 1024L)
            if (sample.has("cache")) {
                val allocated = sample.getJSONObject("cache").getLong("allocatedBytes"); maxCache = Math.max(maxCache, allocated)
                assertTrue(sample.toString(), allocated <= (16L shl 20))
            }
            sample.put("elapsedMs", SystemClock.elapsedRealtime() - started).put("seekMs", recovered).put("pssBytes", memory.totalPss * 1024L)
            samples.put(sample); cycles++
            val report = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started).put("cycles", cycles)
                    .put("maxPssBytes", maxPss).put("maxAllocatedCacheBytes", maxCache).put("samples", samples)
            Files.write(evidence.toPath(), report.toString().toByteArray(StandardCharsets.UTF_8))
            SystemClock.sleep(Math.min(8000, Math.max(0, deadline - SystemClock.elapsedRealtime())))
        }
        assertTrue(cycles > 50)
        assertTrue(diagnostics().getJSONObject("cache").getLong("evicted") >= 3 * diagnostics().getJSONObject("cache").getInt("slots"))
        val complete = JSONObject().put("complete", true).put("elapsedMs", SystemClock.elapsedRealtime() - started).put("cycles", cycles)
                .put("maxPssBytes", maxPss).put("maxAllocatedCacheBytes", maxCache).put("samples", samples)
        Files.write(evidence.toPath(), complete.toString().toByteArray(StandardCharsets.UTF_8))
    }
}
