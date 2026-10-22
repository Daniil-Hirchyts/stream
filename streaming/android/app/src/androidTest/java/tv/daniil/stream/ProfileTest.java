package tv.daniil.stream;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Measured cached/uncached seeks and actual process memory, with a real rendered screenshot. */
@RunWith(AndroidJUnit4.class)
public final class ProfileTest extends AppHarness {
    @Test public void measureRealPlayback() throws Exception {
        playFilm(); WatchActivity activity = watch();
        waitUntil("buffered seek target", 10_000, () -> state().bufferedMs >= 8000);
        JSONObject first = diagnostics();
        long cached = seek(activity, 5000);
        assertTrue("Uncached seek is outside Media3's retained buffer", state().bufferedMs < 35_000);
        long forward = seek(activity, 36_000), backward = seek(activity, 1000);
        waitUntil("native measurements", 5000, () -> diagnostics().has("cache"));
        JSONObject measured = diagnostics(); Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
        measured.put("pssBytes", memory.getTotalPss() * 1024L).put("rssBytes", procBytes("VmRSS")).put("peakRssBytes", procBytes("VmHWM"))
                .put("cachedSeekMs", cached).put("uncachedForwardSeekMs", forward).put("uncachedBackwardSeekMs", backward)
                .put("initial", first).put("model", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT).put("abi", android.os.Build.SUPPORTED_ABIS[0]);
        assertTrue(measured.getJSONObject("cache").getLong("allocatedBytes") <= (16L << 20));
        Files.write(new File(context.getFilesDir(), "validation-profile.json").toPath(), measured.toString().getBytes(StandardCharsets.UTF_8));
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), "validation-profile.png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        image.recycle();
    }
    private long seek(WatchActivity activity, long position) {
        long started = SystemClock.elapsedRealtime(); instrumentation.runOnMainSync(() -> AppFlowTest.session(activity).seekTo(position));
        waitUntil("measured seek " + position, 30_000, () -> state().playing && state().positionMs >= position + 250 && state().positionMs < position + 5000);
        return SystemClock.elapsedRealtime() - started;
    }
    private static long procBytes(String key) throws Exception {
        for (String line : Files.readAllLines(new File("/proc/self/status").toPath(), StandardCharsets.US_ASCII))
            if (line.startsWith(key + ":")) return Long.parseLong(line.substring(line.indexOf(':') + 1).trim().split("\\s+")[0]) * 1024;
        return 0;
    }
}
