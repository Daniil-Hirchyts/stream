package tv.daniil.stream;

import static org.junit.Assert.*;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import tv.daniil.stream.addon.*;
import tv.daniil.stream.data.InstalledAddon;

/** Full application paths beyond the small, fast simulator smoke suite. */
@RunWith(AndroidJUnit4.class)
public final class AcceptanceTest extends AppHarness {
    @Test public void directMp4MkvHlsAndDashRenderAndAdvance() throws Exception {
        for (String format : new String[]{"mp4", "mkv", "hls", "dash"}) {
            for (InstalledAddon addon : services.store.addons()) services.addons.remove(addon.id);
            readyLibrary("http-" + format); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource();
            waitUntil(format + " first frame", 40_000, () -> state() != null && state().renderedFirstFrame && state().playing && state().positionMs > 1000);
            WatchActivity activity = watch();
            if (format.equals("mkv")) {
                assertEquals(2, state().audioTracks.size()); assertFalse(state().textTracks.isEmpty());
                instrumentation.runOnMainSync(() -> { AppFlowTest.session(activity).selectAudio(stateOnMain(activity).audioTracks.get(1)); AppFlowTest.session(activity).selectSubtitles(stateOnMain(activity).textTracks.get(0)); });
                waitUntil("Russian audio selected", 5000, () -> state().audio != null && ("rus".equals(state().audio.language) || "ru".equals(state().audio.language)));
                waitUntil("audio choice remembered by language", 5000, () -> services.store.preference("audioLanguage", "").startsWith("ru"));
            }
            if (format.equals("dash")) {
                assertTrue("Adaptive qualities", state().qualities.size() >= 2);
                instrumentation.runOnMainSync(() -> AppFlowTest.session(activity).selectQuality(stateOnMain(activity).qualities.get(0)));
                assertNotNull(state().quality);
            }
            instrumentation.runOnMainSync(() -> { AppFlowTest.session(activity).pause(); AppFlowTest.session(activity).seekTo(8000); AppFlowTest.session(activity).setSpeed(1.25f); });
            waitUntil("paused seek", 10_000, () -> state().positionMs >= 8000 && !state().playWhenReady);
            assertEquals(1.25f, state().speed, 0.001f);
            instrumentation.runOnMainSync(() -> AppFlowTest.session(activity).play());
            waitUntil("resumes after seek", 15_000, () -> state().playing && state().positionMs > 9000);
            instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync();
            browse.close(); browse = null;
        }
    }
    private static tv.daniil.playback.PlaybackSnapshot stateOnMain(WatchActivity activity) { return activity.playbackSnapshot(); }

    @Test public void automaticFailoverIsBoundedAndManualSourcesDoNotSilentlyChange() throws Exception {
        readyLibrary("failover"); launch(); tap("Generated Film", true); startAutomaticFixturePlayback();
        waitUntil("second source renders", 40_000, () -> state() != null && state().renderedFirstFrame && state().playing);
        WatchActivity activity = watch();
        assertEquals(2, field(activity, "attempts"));
        instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync(); browse.close(); browse = null;
        launch(); tap("Generated Film", false); tap("Sources", true); tap("Unavailable source", false);
        waitUntil("manual source failure", 20_000, () -> {
            tv.daniil.playback.PlaybackSnapshot snapshot = state();
            return snapshot != null && snapshot.error != null && snapshot.error.code.equals("opening");
        });
        assertEquals(1, field(watch(), "attempts")); assertFalse(state().renderedFirstFrame);
        instrumentation.runOnMainSync(watch()::finish); instrumentation.waitForIdleSync(); browse.close(); browse = null;
        for (InstalledAddon addon : services.store.addons()) services.addons.remove(addon.id);
        readyLibrary("failover-all"); launch(); tap("Generated Film", true); startAutomaticFixturePlayback();
        waitUntil("bounded failure", 40_000, () -> {
            tv.daniil.playback.PlaybackSnapshot snapshot = state();
            return snapshot != null && snapshot.error != null && snapshot.error.code.equals("opening")
                    && Integer.valueOf(3).equals(field(watch(), "attempts"));
        });
        assertEquals(3, field(watch(), "attempts")); assertFalse(state().renderedFirstFrame);
        waitUntil("source choice is accessible after failure", 5000, () -> visible("Choose source"));
    }
    @Test public void pressuredPayloadResetKeepsTheChosenEpisodeAndPosition() throws Exception {
        readyLibrary("multiple-files"); launch(); tap("Generated Film", true); tap("Play", true);
        waitUntil("episode file choice", 30000, () -> visible("Choose a video file"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("chosen episode renders", 30000, () -> state() != null && state().renderedFirstFrame && state().playing);
        WatchActivity activity = watch(); PlaybackCoordinator coordinator = (PlaybackCoordinator) field(activity, "coordinator");
        int selected = coordinator.fileIndex;
        instrumentation.runOnMainSync(() -> AppFlowTest.session(activity).seekTo(6000));
        waitUntil("position before pressure", 10000, () -> state().playing && state().positionMs >= 6000);
        long before = state().positionMs;
        // The native stalled-peer test supplies real cache pressure. Here inject its reported condition
        // to exercise the UI handoff, real session/storage drain, and selected-file restoration.
        instrumentation.runOnMainSync(() -> {
            try {
                java.lang.reflect.Field stats = WatchActivity.class.getDeclaredField("nativeStats"); stats.setAccessible(true);
                stats.set(activity, new org.json.JSONObject().put("urgentReservationFailures", 1).put("obsoletePartials", 1).put("verifiedReadBytes", 0));
                ((StartupWatchdog) field(activity, "startupWatchdog")).start(android.os.SystemClock.elapsedRealtime() - 7000);
                Runnable tick = (Runnable) field(activity, "tick"); ((android.os.Handler) field(activity, "main")).removeCallbacks(tick); tick.run();
            } catch (Exception error) { throw new AssertionError(error); }
        });
        waitUntil("same episode restarts after storage drain", 30000, () -> state().error == null && state().renderedFirstFrame && state().playing
                && state().positionMs >= before && coordinator.fileIndex == selected && !visible("Choose a video file"));
        assertEquals(1, diagnostics().getInt("sourceAttempts"));
        assertEquals(0, diagnostics().getJSONArray("sourceFailures").length());
    }

    @Test public void decoderAndNetworkFailuresAfterFramesResumeOnDifferentSources() throws Exception {
        readyLibrary("recover-after-frame"); launch(); tap("Generated Film", true); startAutomaticFixturePlayback();
        waitUntil("initial video", 30_000, () -> state() != null && state().renderedFirstFrame && state().playing);
        WatchActivity activity = watch();
        instrumentation.runOnMainSync(() -> { AppFlowTest.session(activity).setSpeed(1.5f); AppFlowTest.session(activity).seekTo(8000); });
        waitUntil("playing beyond initial frame", 10_000, () -> state().playing && state().positionMs >= 8500);
        long position = state().positionMs;
        instrumentation.runOnMainSync(() -> AppFlowTest.session(activity).fail(tv.daniil.playback.PlaybackErrors.describe(context,
                new androidx.media3.common.PlaybackException("Injected real decoder failure path", null,
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED))));
        waitUntil("decoder failure recovers after first frame", 30_000, () -> Integer.valueOf(2).equals(field(activity, "attempts"))
                && state() != null && state().error == null && state().renderedFirstFrame && state().playing && state().positionMs >= position);
        assertEquals("Recovery keeps the chosen speed", 1.5f, state().speed, 0.001f);
        long resumed = state().positionMs;
        instrumentation.runOnMainSync(() -> AppFlowTest.session(activity).fail(new tv.daniil.playback.PlaybackErrors.Description(
                "unsupported", "Injected error code collision", "Cannot play this source", "test", 0, false)));
        waitUntil("shared error vocabulary still recovers", 30_000, () -> Integer.valueOf(3).equals(field(activity, "attempts"))
                && state() != null && state().error == null && state().renderedFirstFrame && state().playing && state().positionMs >= resumed);
        instrumentation.runOnMainSync(() -> AppFlowTest.session(activity).fail(tv.daniil.playback.PlaybackErrors.describe(context,
                new androidx.media3.common.PlaybackException("Injected network failure path", null,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))));
        waitUntil("recovery budget is bounded after frames too", 5000, () -> state() != null && state().error != null
                && "opening".equals(state().error.code));
        assertEquals(3, field(activity, "attempts"));
        assertEquals(3, diagnostics().getJSONArray("sourceFailures").length());
        waitUntil("source choice is accessible after failure", 5000, () -> visible("Choose source"));
    }

    @Test public void activityRecreationRetainsPauseSpeedPositionAndNativeTelemetry() throws Exception {
        playFilm(); WatchActivity original = watch();
        instrumentation.runOnMainSync(() -> { AppFlowTest.session(original).setSpeed(1.5f); AppFlowTest.session(original).pause(); AppFlowTest.session(original).seekTo(6000); });
        waitUntil("paused before recreation", 10000, () -> !state().buffering && state().positionMs >= 6000 && !state().playWhenReady);
        instrumentation.runOnMainSync(original::recreate);
        waitUntil("restored paused video", 30000, () -> watch() != null && watch() != original && state() != null && state().error == null && state().renderedFirstFrame && !state().buffering);
        assertFalse(state().playWhenReady); assertEquals(1.5f, state().speed, .001f); assertTrue(state().positionMs >= 6000);
        waitUntil("native telemetry resumes", 10000, () -> diagnostics().optLong("verifiedReadBytes") > 0);
        WatchActivity restored = watch(); instrumentation.runOnMainSync(() -> AppFlowTest.session(restored).play());
        waitUntil("restored playback advances", 10000, () -> state().playing && state().positionMs > 6500);
    }
    @Test public void manuallyChangingSourcesRetainsTheCurrentPausedPositionAndSpeed() throws Exception {
        readyLibrary("recover-after-frame"); launch(); tap("Generated Film", false); tap("Play", true);
        waitUntil("video before source change", 30000, () -> state() != null && state().playing && state().renderedFirstFrame);
        WatchActivity activity = watch();
        instrumentation.runOnMainSync(() -> { AppFlowTest.session(activity).setSpeed(1.5f); AppFlowTest.session(activity).pause(); AppFlowTest.session(activity).seekTo(6000); });
        waitUntil("paused before source change", 10000, () -> !state().buffering && state().positionMs >= 6000);
        instrumentation.runOnMainSync(activity::onChooseSource);
        waitUntil("manual source dialog", 5000, () -> visible("Choose source"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("replacement source ready", 30000, () -> state().error == null && state().renderedFirstFrame && !state().buffering);
        assertFalse(state().playWhenReady); assertEquals(1.5f, state().speed, .001f); assertTrue(state().positionMs >= 6000);
        assertFalse(diagnostics().getBoolean("automatic"));
    }

    private Object field(WatchActivity activity, String name) {
        try { java.lang.reflect.Field field = WatchActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void contentFileAndMagnetImportsUseRealMetadataAndPlayback() throws Exception {
        android.content.Intent file = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("content://tv.daniil.stream.test.files/series.torrent"), context, MainActivity.class)
                .setType("application/x-bittorrent").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        // setType clears data; Android requires the pair to be set together.
        file.setDataAndType(android.net.Uri.parse("content://tv.daniil.stream.test.files/series.torrent"), "application/x-bittorrent");
        browse = androidx.test.core.app.ActivityScenario.launch(file);
        waitUntil("file import dialog", 10_000, () -> visible("Name this import")); enter("Content file"); tap("Continue", false);
        waitUntil("private copy saved", 10_000, () -> services.store.importIds().size() == 1);
        assertEquals("file", services.store.importDescriptor(services.store.importIds().get(0)).optString("kind"));
        tap("Content file", true); tap("Play", false); waitUntil("file metadata chooser", 30_000, () -> visible("Choose a video file"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("copied file plays", 30_000, () -> state() != null && state().renderedFirstFrame && state().playing);
        instrumentation.runOnMainSync(watch()::finish);
        waitUntil("import details regain window focus", 10_000, () -> {
            java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
            browse.onActivity(activity -> focused.set(activity.hasWindowFocus()));
            return watch() == null && focused.get() && visible("Rename import");
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); // Imported title details return to the Imports list.
        tap("Add magnet or torrent URL", false);
        String hash = null;
        try (AddonClient client = new AddonClient(); Cancellation request = new Cancellation()) {
            byte[] bytes = client.fetch(origin + "/streaming/index.json", 2 << 20, request, AddonClient.deadline());
            org.json.JSONArray index = new org.json.JSONArray(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            for (int i = 0; i < index.length(); i++) if (index.getJSONObject(i).getString("name").equals("film-hybrid")) hash = index.getJSONObject(i).getString("v1");
        }
        assertNotNull(hash); enter("magnet:?xt=urn:btih:" + hash); tap("Continue", true); enter("Magnet import"); tap("Continue", false);
        waitUntil("magnet saved", 5000, () -> services.store.importIds().size() == 2); tap("Magnet import", true); tap("Play", false);
        waitUntil("magnet metadata and playback", 40_000, () -> state() != null && state().renderedFirstFrame && state().playing);
    }

    @Test public void importsChooseAFileRenameAndRemoveWithoutStartingDuringBrowse() throws Exception {
        launch(); tap("Settings", true); tap("Torrents", false); tap("Imports", true); tap("Add magnet or torrent URL", false);
        enter(origin + "/streaming/torrents/series.torrent"); tap("Continue", true); enter("Three episodes"); tap("Continue", false);
        waitUntil("descriptor saved", 5000, () -> services.store.importIds().size() == 1);
        java.io.File[] payload = new java.io.File(context.getCacheDir(), "payload").listFiles(); assertTrue(payload == null || payload.length == 0);
        tap("Three episodes", true); tap("Play", false);
        waitUntil("file chooser", 30_000, () -> visible("Choose a video file"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("chosen episode plays", 30_000, () -> state() != null && state().renderedFirstFrame && state().playing);
        assertTrue(services.store.importDescriptor(services.store.importIds().get(0)).has("fileIdx"));
        instrumentation.runOnMainSync(watch()::finish); tap("Rename import", true); enter("Renamed episodes"); tap("Continue", false);
        waitUntil("renamed", 5000, () -> "Renamed episodes".equals(services.store.importDescriptor(services.store.importIds().get(0)).optString("name")));
        tap("Remove import", true); waitUntil("removed", 5000, () -> services.store.importIds().isEmpty());
    }

    @Test public void addonSettingsAndPlaybackPreferencesWorkThroughRealRows() throws Exception {
        launch(); tap("Settings", true); tap("Add add-on", false);
        enter(origin + "/addon/good/settings/manifest.json"); tap("Continue", true); tap("Install", false);
        waitUntil("addon installed", 5000, () -> services.store.addons().size() == 1);
        tap("Local fixtures", true); tap("Disable add-on", false);
        waitUntil("disabled", 5000, () -> !services.store.addons().get(0).enabled); tap("Enable add-on", true);
        waitUntil("enabled", 5000, () -> services.store.addons().get(0).enabled);
        tap("Move earlier", false); tap("Move later", true); tap("Generated films", false);
        waitUntil("catalogue hidden", 5000, () -> { InstalledAddon a = services.store.addons().get(0); return !services.store.catalogVisible(a.id, "movie", "films/list"); });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); tap("Settings", true);
        tap("Languages", false); tap("Audio language", false); tap("Russian", true); tap("Subtitle language", false); tap("English", true);
        tap("Subtitles", true);
        for (String[] setting : new String[][]{{"size", "large"}, {"font", "condensed"}, {"color", "yellow"}, {"edge", "outline"}, {"position", "raised"}}) {
            tap("Subtitle " + setting[0], false); tap(setting[1], true);
            waitUntil("style saved", 5000, () -> setting[1].equals(services.playerPreferences.getString("subtitle_" + setting[0], "")));
        }
        tap("Playback", false); tap("Playback cache", false); tap("128 MiB", true); waitUntil("cache preference", 5000, () -> services.cacheBytes() == (128L << 20));
        tap("Play next episode", false); waitUntil("autoplay preference", 5000, () -> services.store.preference("autoplay", "true").equals("false"));
        tap("Clear app playback and metadata cache", true); tap("About", false); tap("Developer", false); tap("Open source notices", true); tap("libtorrent", false);
        waitUntil("license text", 5000, () -> visible("Open source notices")); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        tap("Settings", false); tap("Local fixtures", true); tap("Remove add-on", false);
        waitUntil("addon removed", 5000, () -> services.store.addons().isEmpty());
        assertEquals("rus", services.store.preference("audioLanguage", "")); assertEquals("eng", services.store.preference("subtitleLanguage", ""));
    }

    @Test public void searchPaginationWatchedStateAndFocusSurviveNavigation() throws Exception {
        readyLibrary(); launch(); tap("Generated Film", true); tap("Mark watched", false);
        waitUntil("watched persisted", 5000, () -> services.store.history(false).stream().anyMatch(p -> p.watched));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("Card focus restored", 10_000, () -> { java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean(); browse.onActivity(activity -> focused.set(activity.getCurrentFocus() != null && String.valueOf(activity.getCurrentFocus().getTag()).contains("fixture:film"))); return focused.get(); });
        tap("Search", true); waitUntil("search keyboard", 5000, () -> visible("Type g"));
        for (char c : "generated".toCharArray()) tap("Type " + c, c % 2 == 0);
        waitUntil("search results", 10_000, () -> visible("Generated Film")); captureUi("search"); tap("Close search", false);
        tap("Browse catalogues", true); captureUi("categories"); tap("Generated films", false);
        tap("Generated", true); captureUi("catalogue-header");
        for (int step = 0; step < 3; step++) {
            java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();
            browse.onActivity(activity -> reached.set(activity.getCurrentFocus() != null
                    && "Next page".contentEquals(activity.getCurrentFocus().getContentDescription() == null ? "" : activity.getCurrentFocus().getContentDescription())));
            if (reached.get()) break;
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); android.os.SystemClock.sleep(250);
        }
        waitUntil("remote reaches footer next page", 5000, () -> {
            java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
            browse.onActivity(activity -> focused.set(activity.getCurrentFocus() != null
                    && "Next page".contentEquals(activity.getCurrentFocus().getContentDescription() == null ? "" : activity.getCurrentFocus().getContentDescription())));
            return focused.get();
        });
        captureUi("catalogue-pagination"); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("next page", 10_000, () -> visible("Films · Page 2")); captureUi("catalogue");
        tap("Previous page", false);
        waitUntil("first page", 10_000, () -> visible("Films · Page 1"));
        tap("Back", true);
        waitUntil("back to categories", 5000, () -> visible("Browse") && visible("Generated films"));
    }
    @Test public void searchGridKeepsFocusedCardsInsideVisibleViewport() throws Exception {
        launch(); tap("Search", true); waitUntil("keyboard ready", 5000, () -> visible("Type a"));
        // Exercise the production search renderer with enough local cards to cross both viewport edges.
        browse.onActivity(activity -> {
            tv.daniil.stream.ui.Item item = tv.daniil.stream.ui.Item.keyboard("keyboard", "", ignored -> {});
            item.tiles = new ArrayList<>();
            for (int i = 0; i < 25; i++) item.tiles.add(new tv.daniil.stream.ui.Item.Tile("edge-card:" + i,
                    tv.daniil.stream.ui.Item.Tile.Style.POSTER, "Generated " + i, "2026", null, null, () -> {}));
            try {
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("adapter"); field.setAccessible(true);
                ((tv.daniil.stream.ui.PageAdapter) field.get(activity)).set(java.util.Collections.singletonList(item), "edge-card:0");
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        instrumentation.waitForIdleSync();
        for (int i = 0; i < 3; i++) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); android.os.SystemClock.sleep(300); }
        captureUi("search-scroll");
        browse.onActivity(activity -> {
            android.view.View focus = activity.getCurrentFocus(); assertNotNull(focus);
            assertTrue("Focus stayed in search results", String.valueOf(focus.getTag()).startsWith("edge-card:"));
            android.graphics.Rect visible = new android.graphics.Rect(); assertTrue(focus.getGlobalVisibleRect(visible));
            assertEquals("Focused card and caption are fully visible", focus.getHeight(), visible.height());
        });
    }
    private void captureUi(String name) throws Exception {
        android.os.SystemClock.sleep(300);
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("UI evidence " + name, bitmap);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), "ui-" + name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
    }

}
