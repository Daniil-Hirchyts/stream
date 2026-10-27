package tv.stream.app;

import android.os.SystemClock;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.stream.app.addon.Title;
import static org.junit.Assert.*;

/** Twenty isolated player/engine lifetimes per transport, using only prepared loopback fixtures. */
@RunWith(AndroidJUnit4.class)
public final class StartupTimingTest extends AppHarness {
    @Test public void preparedHttpAndColdTorrentStartupHaveMeasuredPercentiles() throws Exception {
        JSONObject report = new JSONObject();
        String transport = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("transport", "all");
        for (String configuration : new String[]{"http-mp4", "ui"}) {
            if (!transport.equals("all") && !transport.equals(configuration)) continue;
            for (tv.stream.app.data.InstalledAddon addon : services.store.addons()) services.addons.remove(addon.id);
            readyLibrary(configuration);
            Title title = Title.parse(services.store.addons().get(0).id, new JSONObject()
                    .put("id", "fixture:film").put("type", "movie").put("name", "Generated Film"));
            if (browse != null) { browse.close(); browse = null; }
            launch(); waitUntil("home ready", 10000, () -> visible("Generated Film"));
            List<Long> times = new ArrayList<>(), details = new ArrayList<>(); JSONArray traces = new JSONArray();
            for (int run = 0; run < 20; run++) {
                services.store.progress(title, title.id, 0, 0, false);
                long opened = SystemClock.elapsedRealtime();
                browse.onActivity(activity -> activity.openTitle(title, title.id));
                waitFast(() -> {
                    AtomicBoolean shown = new AtomicBoolean();
                    browse.onActivity(activity -> { View play = activity.findViewById(R.id.page_rows).findViewWithTag("play"); shown.set(play != null && play.isShown()); });
                    return shown.get();
                });
                details.add(SystemClock.elapsedRealtime() - opened);
                PlaybackPreparation.Entry entry = services.preparation.prepare(title, title.id);
                try (tv.stream.app.addon.Cancellation scope = services.addons.newRequest()) { assertFalse(entry.awaitCandidate(scope, "").isEmpty()); }
                File[] before = new File(context.getCacheDir(), "payload").listFiles();
                assertTrue("Details must not allocate torrent payload", before == null || before.length == 0);
                browse.onActivity(activity -> { View play = activity.findViewById(R.id.page_rows).findViewWithTag("play"); assertNotNull(play); assertTrue(play.performClick()); });
                try { waitUntil("first video and audio output", 30000, () -> watch() != null && diagnostics().optLong("playTapToFirstAvMs") > 0); }
                catch (AssertionError failure) {
                    JSONObject error = diagnostics().put("run", run).put("configuration", configuration);
                    WatchActivity active = watch();
                    if (active != null) instrumentation.runOnMainSync(() -> {
                        try { java.lang.reflect.Field field = WatchActivity.class.getDeclaredField("failureCause"); field.setAccessible(true);
                            Throwable cause = (Throwable) field.get(active); error.put("failure", android.util.Log.getStackTraceString(cause)); }
                        catch (Exception ignored) { }
                    });
                    try (FileWriter out = new FileWriter(new File(context.getFilesDir(), "validation-startup-error.json"))) { out.write(error.toString(2)); }
                    throw failure;
                }
                JSONObject trace = diagnostics(); times.add(trace.getLong("playTapToFirstAvMs")); traces.put(trace.getJSONObject("startupStagesMs"));
                instrumentation.runOnMainSync(watch()::finish);
                waitUntil("browser resumed", 10000, () -> {
                    AtomicBoolean resumed = new AtomicBoolean(); browse.onActivity(activity -> resumed.set(activity.hasWindowFocus()));
                    return watch() == null && resumed.get();
                });
                app.playbackLoader.submit(() -> {}).get();
                waitUntil("previous engine storage removed", 10000, () -> { File[] left = new File(context.getCacheDir(), "payload").listFiles(); return left == null || left.length == 0; });
                browse.onActivity(MainActivity::back);
                services.preparation.leave();
            }
            JSONObject measurements = new JSONObject().put("runs", 20).put("firstAvMs", new JSONArray(times))
                    .put("firstAvP50Ms", percentile(times, .5)).put("firstAvP95Ms", percentile(times, .95))
                    .put("detailsMs", new JSONArray(details)).put("detailsP95Ms", percentile(details, .95)).put("stages", traces);
            report.put(configuration, measurements);
            try (FileWriter out = new FileWriter(new File(context.getFilesDir(), "validation-startup-timing.json"))) { out.write(report.toString(2)); }
        }
    }
    private void waitFast(java.util.function.BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < end) { if (condition.getAsBoolean()) return; SystemClock.sleep(10); }
        fail("Details controls did not arrive");
    }
    private static long percentile(List<Long> values, double percentile) { List<Long> sorted = new ArrayList<>(values); Collections.sort(sorted); return sorted.get((int) Math.ceil(sorted.size() * percentile) - 1); }
}
