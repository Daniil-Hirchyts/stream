package tv.stream.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.stream.app.addon.*;
import tv.stream.app.data.InstalledAddon;
import tv.stream.playback.PlaybackSnapshot;

/** Explicit opt-in title reproduction using only providers the owner already accepted. No configured URLs in evidence. */
@RunWith(AndroidJUnit4.class)
public final class InstalledTitlePlaybackTest extends AppHarness {
    private final List<InstalledAddon> installed = new ArrayList<>();
    private JSONArray results = new JSONArray();
    private final JSONArray titles = new JSONArray();
    private String titleId;
    private String[] requestedTitles;
    private boolean matrix;

    @Override @Before public void configure() throws Exception {
        String requested = InstrumentationRegistry.getArguments().getString("titleIds",
                InstrumentationRegistry.getArguments().getString("titleId", ""));
        org.junit.Assume.assumeTrue("Supply explicitly requested IMDb IDs", requested.matches("tt[0-9]+(?:,tt[0-9]+)*"));
        requestedTitles = requested.split(","); matrix = requestedTitles.length > 1; titleId = requestedTitles[0];
        TvApplication owner = (TvApplication) context.getApplicationContext();
        for (InstalledAddon addon : owner.services().store.addons()) if (addon.enabled) installed.add(addon);
        super.configure();
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit()
                .putBoolean("localOnly", false).putBoolean("publicNetworkOnly", true)
                .remove("peer").remove("cacheBytes").commit();
        app.resetServices(); services = app.services();
    }

    @Test public void installedProvidersPlayTheRequestedTitle() throws Exception {
        assertFalse("Owner has accepted providers", installed.isEmpty()); launch();
        for (String requested : requestedTitles) {
        titleId = requested; results = new JSONArray();
        JSONObject titleResult = new JSONObject().put("titleId", titleId).put("providers", results).put("complete", false);
        titles.put(titleResult);
        for (int index = matrix ? installed.size() : 0; index <= installed.size(); index++) {
            boolean combined = index == installed.size();
            InstalledAddon addon = installed.get(combined ? 0 : index);
            JSONObject result = new JSONObject().put("name", combined ? "All installed (automatic)" : addon.manifest.name);
            results.put(result); save(false);
            // Installing changes provider scopes, so create the request only after acceptance is copied.
            if (combined) for (InstalledAddon accepted : installed) services.addons.install(accepted.address.manifest, accepted.manifest);
            else services.addons.install(addon.address.manifest, addon.manifest);
            try (Cancellation scope = services.addons.newRequest()) {
                AddonRepository.Result<MediaSource> response = services.addons.streams("movie", titleId, scope);
                JSONArray failures = new JSONArray();
                for (AddonException.Kind kind : response.failures.values()) failures.put(kind.name());
                result.put("requestFailures", failures)
                        .put("eligibleProviders", services.addons.providers("stream", "movie", titleId).size());
                List<MediaSource> all = response.items;
                List<MediaSource> ranked = SourcePolicy.ranked(all);
                result.put("sourceCount", all.size()).put("supportedCount", ranked.size());
                if (ranked.isEmpty()) { result.put("result", !response.failures.isEmpty() ? "provider request failed"
                        : all.isEmpty() ? "no sources returned" : "no compatible sources"); continue; }
                result.put("firstCandidateKind", ranked.get(0).kind.name()).put("selection", "automatic; at most three attempts");
                Title title = Title.parse(addon.id, new JSONObject().put("id", titleId).put("type", "movie").put("name", InstrumentationRegistry.getArguments().getString("titleName", "Requested film")));
                browse.onActivity(activity -> activity.play(title, titleId, ""));
                long began = SystemClock.elapsedRealtime(), until = began + 250_000, errorSince = 0;
                result.put("result", "opening");
                while (SystemClock.elapsedRealtime() < until) {
                    PlaybackSnapshot state = state();
                    JSONObject sample = diagnostics();
                    result.put("diagnostics", sample);
                    if (sample.has("peers")) result.put("lastTransfer", sample);
                    if (state != null && state.error != null) {
                        if (errorSince == 0) errorSince = SystemClock.elapsedRealtime();
                        if (SystemClock.elapsedRealtime() - errorSince >= 2000) {
                            result.put("result", "error").put("code", state.error.code).put("message", state.error.message); break;
                        }
                    } else errorSince = 0;
                    if (state != null && state.renderedFirstFrame && state.playing && state.positionMs >= (matrix ? 20_000 : 3500)) {
                        result.put("result", state.durationMs >= 60 * 60_000 ? "rendered and advancing" : "unexpected short media")
                                .put("durationMs", state.durationMs);
                        if (combined && state.durationMs >= 60 * 60_000) {
                            WatchActivity playing = watch();
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP);
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
                            instrumentation.runOnMainSync(() -> assertTrue("Up and OK open real-film options",
                                    playing.findViewById(tv.stream.playback.R.id.menu).isShown()));
                            result.put("remoteOptions", "opened with Up and OK");
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                            instrumentation.runOnMainSync(() -> AppFlowTest.session(playing).seekTo(30 * 60_000));
                            try {
                                waitUntil("requested film plays after seek to 30 minutes", 90_000, () -> state() != null
                                        && state().playing && state().positionMs >= 30 * 60_000 + (matrix ? 10_000 : 1500));
                                result.put("seek30Minutes", "passed").put("afterSeek", diagnostics());
                            } catch (AssertionError failedSeek) { result.put("seek30Minutes", "failed"); }

                        }
                        android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
                        if (image != null) {
                            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(context.getFilesDir(),
                                    "validation-requested-title-" + titleId + "-" + index + ".png"))) {
                                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                            } finally { image.recycle(); }
                        }
                        break;
                    }
                    if (visible("Choose a video file")) { result.put("result", "requires file choice"); break; }
                    save(false); SystemClock.sleep(500);
                }
                if (result.optString("result").equals("opening")) result.put("result", "timed out");
                result.put("elapsedMs", SystemClock.elapsedRealtime() - began);
                WatchActivity player = watch(); if (player != null) instrumentation.runOnMainSync(player::finish);
                waitUntil("player closes", 15_000, () -> watch() == null);
                waitUntil("torrent payload released", 15_000, () -> {
                    File[] files = new File(context.getCacheDir(), "payload").listFiles(); return files == null || files.length == 0;
                });
            } catch (AddonException failure) {
                result.put("result", "provider request failed").put("code", failure.kind.name());
            } finally {
                for (InstalledAddon accepted : services.store.addons()) services.addons.remove(accepted.id);
                save(false);
            }
        }
        titleResult.put("complete", true);
        }
        save(true);
        // This is a diagnostic audit: individual playback failures are recorded, not converted into an overall pass claim.
    }
    @Override @org.junit.After public void cleanup() throws Exception { if (app != null) super.cleanup(); }
    private void save(boolean complete) throws Exception {
        Files.write(new File(context.getFilesDir(), "validation-requested-title.json").toPath(),
                new JSONObject().put("complete", complete).put("titleId", titleId).put("providers", results).put("titles", titles).toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
