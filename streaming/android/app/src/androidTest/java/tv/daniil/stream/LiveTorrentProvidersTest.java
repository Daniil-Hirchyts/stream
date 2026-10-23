package tv.daniil.stream;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.daniil.playback.PlaybackSnapshot;
import tv.daniil.stream.addon.*;

/** Optional real swarm samples, one supported 1968-film candidate per provider, with native LAN filtering. */
@RunWith(AndroidJUnit4.class)
public final class LiveTorrentProvidersTest extends AppHarness {
    private static final String FILM = "tt0063350";
    // The public-domain directory's published 1080p copy, verified by the live control, when another index offers it.
    private static final String CONTROL = "11ea02584fa6351956f35671962ab46354d99060";
    private final JSONArray results = new JSONArray();

    @Before public void internet() throws Exception {
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().putBoolean("localOnly", false)
                .putBoolean("publicNetworkOnly", true).remove("peer").remove("cacheBytes").remove("addonDirectory").commit();
        app.resetServices(); services = app.services(); save(false);
    }

    @Test public void availablePublicTorrentProvidersRenderThe1968Film() throws Exception {
        Map<String, String> providers = new LinkedHashMap<>();
        for (AddonListing listing : TorrentAddons.listings()) providers.put(listing.manifest.name, listing.url);
        try (Cancellation scope = services.addons.newRequest()) {
            for (AddonListing listing : services.addons.store(scope).items)
                if (listing.manifest.name.equals("Public Domain Movies")) providers.put(listing.manifest.name, listing.url);
        }
        assertTrue("The directory supplies the public-domain control", providers.containsKey("Public Domain Movies"));
        try (java.io.InputStream input = instrumentation.getContext().getAssets().open("nuvio-addon-audit.json")) {
            JSONArray earlier = new JSONArray(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            for (int i = 0; i < earlier.length(); i++) {
                JSONObject entry = earlier.getJSONObject(i); String name = entry.getString("name");
                if (name.equals("TorrentClaw") || name.equals("Watcho") || name.equals("Pipe")) providers.put(name, entry.getString("manifest"));
            }
        }
        String requested = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("providers", "");
        if (!requested.isEmpty() && !requested.equalsIgnoreCase("all")) {
            java.util.Set<String> selectedNames = new java.util.HashSet<>();
            for (String name : requested.split(",")) selectedNames.add(name.replace(" ", "").toLowerCase(java.util.Locale.ROOT));
            providers.keySet().removeIf(name -> !selectedNames.contains(name.replace(" ", "").toLowerCase(java.util.Locale.ROOT)));
        }
        assertFalse("At least one known provider selected", providers.isEmpty());
        launch(); int rendered = 0;
        for (Map.Entry<String, String> entry : providers.entrySet()) {
            JSONObject result = new JSONObject().put("name", entry.getKey()).put("host", AddonUrl.redacted(entry.getValue()));
            results.put(result); save(false);
            Route review = new Route("Add-on review"); review.value = entry.getValue();
            browse.onActivity(activity -> activity.push(review));
            waitUntil("provider review", 20_000, () -> tagged("install-addon") || tagged("configured") || tagged("retry-review"));
            try {
                if (!tagged("install-addon")) {
                    result.put("playback", tagged("retry-review") ? "provider unavailable" : "needs setup");
                    continue;
                }
                tap("Install", true);
                waitUntil("provider accepted", 10_000, () -> services.store.addons().size() == 1);
                waitUntil("installed review", 20_000, () -> tagged("manage") || tagged("retry-review"));
                List<MediaSource> sources;
                try (Cancellation scope = services.addons.newRequest()) { sources = services.addons.streams("movie", FILM, scope).items; }
                MediaSource chosen = null;
                for (MediaSource source : SourcePolicy.ranked(sources)) {
                    String description = source.name + " " + source.description + " " + source.filename;
                    if (source.kind == MediaSource.Kind.TORRENT && (CONTROL.equalsIgnoreCase(source.infoHash)
                            || entry.getKey().equals("Public Domain Movies") || description.contains("1968"))) {
                        if (chosen == null) chosen = source;
                        if (CONTROL.equalsIgnoreCase(source.infoHash)) { chosen = source; break; }
                    }
                }
                if (chosen == null) { result.put("playback", "no supported torrent identified for the 1968 film"); continue; }
                String provider = services.store.addons().get(0).id, key = SourcePolicy.key(chosen);
                result.put("selection", CONTROL.equalsIgnoreCase(chosen.infoHash) ? "public-domain control" : "first supported 1968 candidate");
                Title film = Title.parse(provider, new JSONObject().put("id", FILM).put("type", "movie").put("name", "Night of the Living Dead (1968)"));
                browse.onActivity(activity -> activity.play(film, FILM, key));
                long started = SystemClock.elapsedRealtime(), until = started + 250_000, errorSince = 0;
                String lastError = "";
                result.put("playback", "opening"); long lastSample = 0; boolean fileChosen = false;
                while (SystemClock.elapsedRealtime() < until) {
                    PlaybackSnapshot snapshot = state();
                    if (SystemClock.elapsedRealtime() - lastSample >= 3000) {
                        JSONObject sample = diagnostics(); result.put("diagnostics", sample);
                        if (sample.has("cache")) result.put("lastTransfer", sample);
                        save(false); lastSample = SystemClock.elapsedRealtime();
                    }
                    if (snapshot != null && snapshot.renderedFirstFrame && snapshot.playing && snapshot.positionMs >= 3500) {
                        if (snapshot.durationMs < 60 * 60_000) {
                            result.put("playback", "unexpected short media").put("durationMs", snapshot.durationMs); break;
                        }
                        result.put("playback", "rendered and advancing").put("elapsedMs", SystemClock.elapsedRealtime() - started)
                                .put("diagnostics", diagnostics());
                        if (snapshot.videoFormat != null) result.put("video", new JSONObject().put("mime", snapshot.videoFormat.sampleMimeType)
                                .put("width", snapshot.videoFormat.width).put("height", snapshot.videoFormat.height));
                        capture(results.length() - 1); rendered++; break;
                    }
                    if (snapshot != null && snapshot.error != null) {
                        if (!lastError.equals(snapshot.error.code)) { lastError = snapshot.error.code; errorSince = SystemClock.elapsedRealtime(); }
                        if (SystemClock.elapsedRealtime() - errorSince >= 2000) {
                            result.put("playback", "error").put("error", lastError).put("message", snapshot.error.message); break;
                        }
                    } else { lastError = ""; errorSince = 0; }
                    if (visible("Choose a video file")) {
                        if (fileChosen) { result.put("playback", "repeated file choice"); break; }
                        String label = largestVideoLabel(instrumentation.getUiAutomation().getRootInActiveWindow());
                        assertNotNull("The file chooser lists actual video sizes", label);
                        tap(label, true); fileChosen = true; result.put("fileChoice", true);
                        waitUntil("file accepted", 5000, () -> !visible("Choose a video file"));
                        until = SystemClock.elapsedRealtime() + 250_000;
                    }
                    SystemClock.sleep(200);
                }
                if (result.optString("playback").equals("opening")) result.put("playback", "timed out");
                WatchActivity activity = watch();
                if (activity != null) instrumentation.runOnMainSync(activity::finish);
                waitUntil("return from public playback", 15_000, () -> watch() == null && mainFocused());
                waitUntil("public payload released", 15_000, () -> {
                    File[] files = new File(context.getCacheDir(), "payload").listFiles(); return files == null || files.length == 0;
                });
                app.persistence.submit(() -> {}).get(); services.store.removeLibrary(film.key());
            } catch (AddonException failure) { result.put("playback", "provider request failed").put("error", failure.kind.name()); }
            finally {
                for (tv.daniil.stream.data.InstalledAddon addon : services.store.addons()) services.addons.remove(addon.id);
                browse.onActivity(MainActivity::back);
                waitUntil("return to Home", 10_000, () -> mainFocused() && visible("Make yourself at home"));
                save(false);
            }
        }
        save(true);
        assertTrue("At least one live public torrent must actually render; inspect validation-torrents.json", rendered > 0);
    }

    private boolean mainFocused() {
        AtomicBoolean focused = new AtomicBoolean(); browse.onActivity(activity -> focused.set(activity.hasWindowFocus())); return focused.get();
    }
    private String largestVideoLabel(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return null;
        String best = node.getText() == null ? null : node.getText().toString();
        if (size(best) < 0) best = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            String candidate = largestVideoLabel(node.getChild(i));
            if (size(candidate) > size(best)) best = candidate;
        }
        return best;
    }
    private long size(String label) {
        if (label == null) return -1;
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?s).+ · ([0-9]+) MiB$").matcher(label);
        return match.matches() ? Long.parseLong(match.group(1)) : -1;
    }
    private boolean tagged(String tag) {
        AtomicBoolean found = new AtomicBoolean();
        browse.onActivity(activity -> { android.view.View view = activity.findViewById(R.id.page_rows).findViewWithTag(tag); found.set(view != null && view.isShown()); });
        return found.get();
    }
    private void capture(int index) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "validation-torrent-" + index + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        finally { bitmap.recycle(); }
    }
    private void save(boolean complete) throws Exception {
        JSONObject report = new JSONObject().put("complete", complete).put("film", FILM).put("providers", results);
        Files.write(new File(context.getFilesDir(), "validation-torrents.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
