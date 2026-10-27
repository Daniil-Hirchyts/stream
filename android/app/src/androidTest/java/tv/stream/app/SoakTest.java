package tv.stream.app;

import static org.junit.Assert.*;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Explicit `simulator.sh soak`: thirty real minutes of playback/rewinds, never time-compressed. */
@RunWith(AndroidJUnit4.class)
public final class SoakTest extends AppHarness {
    @Test public void playbackAndSeekSoak() throws Exception {
        int seconds = Integer.parseInt(InstrumentationRegistry.getArguments().getString("durationSeconds", "1800"));
        assertTrue("The acceptance soak must last at least thirty minutes", seconds >= 1800);
        playFilm(); WatchActivity activity = watch();
        long started = SystemClock.elapsedRealtime(), deadline = started + seconds * 1000L, maxPss = 0, maxCache = 0;
        JSONArray samples = new JSONArray(); int cycles = 0;
        File evidence = new File(context.getFilesDir(), "validation-soak.json");
        while (SystemClock.elapsedRealtime() < deadline) {
            long target = cycles % 3 == 0 ? 35_000 : cycles % 3 == 1 ? 1000 : 15_000;
            long seekStart = SystemClock.elapsedRealtime();
            instrumentation.runOnMainSync(() -> AppFlowTest.session(activity).seekTo(target));
            waitUntil("soak seek " + cycles, 30_000, () -> state() != null && state().playing && state().positionMs >= target + 1000 && state().positionMs < target + 10_000);
            long recovered = SystemClock.elapsedRealtime() - seekStart;
            JSONObject sample = diagnostics();
            Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
            maxPss = Math.max(maxPss, memory.getTotalPss() * 1024L);
            if (sample.has("cache")) {
                long allocated = sample.getJSONObject("cache").getLong("allocatedBytes"); maxCache = Math.max(maxCache, allocated);
                assertTrue(sample.toString(), allocated <= (16L << 20));
            }
            sample.put("elapsedMs", SystemClock.elapsedRealtime() - started).put("seekMs", recovered).put("pssBytes", memory.getTotalPss() * 1024L);
            samples.put(sample); cycles++;
            JSONObject report = new JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started).put("cycles", cycles)
                    .put("maxPssBytes", maxPss).put("maxAllocatedCacheBytes", maxCache).put("samples", samples);
            Files.write(evidence.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
            SystemClock.sleep(Math.min(8000, Math.max(0, deadline - SystemClock.elapsedRealtime())));
        }
        assertTrue(cycles > 50);
        assertTrue(diagnostics().getJSONObject("cache").getLong("evicted") >= 3 * diagnostics().getJSONObject("cache").getInt("slots"));
        JSONObject complete = new JSONObject().put("complete", true).put("elapsedMs", SystemClock.elapsedRealtime() - started).put("cycles", cycles)
                .put("maxPssBytes", maxPss).put("maxAllocatedCacheBytes", maxCache).put("samples", samples);
        Files.write(evidence.toPath(), complete.toString().getBytes(StandardCharsets.UTF_8));
    }
}
