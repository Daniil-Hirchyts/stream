package tv.daniil.stream;

import static org.junit.Assert.*;
import android.os.SystemClock;
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
            readyLibrary("http-" + format); launch(); tap("Generated Film", false); tap("Play", true);
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
        readyLibrary("failover"); launch(); tap("Generated Film", true); tap("Play", false);
        waitUntil("second source renders", 40_000, () -> state() != null && state().renderedFirstFrame && state().playing);
        WatchActivity activity = watch();
        assertEquals(2, field(activity, "attempts"));
        instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync(); browse.close(); browse = null;
        launch(); tap("Generated Film", false); tap("Sources", true); tap("Unavailable source", false);
        waitUntil("manual source failure", 20_000, () -> state() != null && state().error != null && state().error.code.equals("opening"));
        assertEquals(1, field(watch(), "attempts")); assertFalse(state().renderedFirstFrame);
        instrumentation.runOnMainSync(watch()::finish); instrumentation.waitForIdleSync(); browse.close(); browse = null;
        for (InstalledAddon addon : services.store.addons()) services.addons.remove(addon.id);
        readyLibrary("failover-all"); launch(); tap("Generated Film", true); tap("Play", false);
        waitUntil("bounded failure", 40_000, () -> state() != null && state().error != null && state().error.code.equals("opening"));
        assertEquals(3, field(watch(), "attempts")); assertFalse(state().renderedFirstFrame);
        assertTrue(visible("Choose source"));
    }
    private Object field(WatchActivity activity, String name) {
        try { java.lang.reflect.Field field = WatchActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void contentFileAndMagnetImportsUseRealMetadataAndPlayback() throws Exception {
        services.store.setPreference("onboardingDone", "true");
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
        instrumentation.runOnMainSync(watch()::finish); tap("Imports", true); tap("Add magnet or torrent URL", false);
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
        services.store.setPreference("onboardingDone", "true"); launch(); tap("Imports", true); tap("Add magnet or torrent URL", false);
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
        services.store.setPreference("onboardingDone", "true"); launch(); tap("Settings", true); tap("Add add-on", false);
        enter(origin + "/addon/good/settings/manifest.json"); tap("Continue", true); tap("Install", false);
        waitUntil("addon installed", 5000, () -> services.store.addons().size() == 1);
        tap("Local fixtures", true); tap("Disable add-on", false);
        waitUntil("disabled", 5000, () -> !services.store.addons().get(0).enabled); tap("Enable add-on", true);
        waitUntil("enabled", 5000, () -> services.store.addons().get(0).enabled);
        tap("Move earlier", false); tap("Move later", true); tap("Generated films", false);
        waitUntil("catalogue hidden", 5000, () -> { InstalledAddon a = services.store.addons().get(0); return !services.store.catalogVisible(a.id, "movie", "films/list"); });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); tap("Settings", true);
        tap("Audio language", false); tap("Russian", true); tap("Subtitle language", false); tap("English", true);
        for (String[] setting : new String[][]{{"size", "large"}, {"color", "yellow"}, {"edge", "outline"}, {"position", "raised"}}) {
            tap("Subtitle " + setting[0], false); tap(setting[1], true);
            waitUntil("style saved", 5000, () -> setting[1].equals(services.playerPreferences.getString("subtitle_" + setting[0], "")));
        }
        tap("Playback cache", false); tap("128 MiB", true); waitUntil("cache preference", 5000, () -> services.cacheBytes() == (128L << 20));
        tap("Play next episode", false); waitUntil("autoplay preference", 5000, () -> services.store.preference("autoplay", "true").equals("false"));
        tap("Clear app playback and metadata cache", true); tap("Developer", false); tap("Open source notices", true); tap("libtorrent", false);
        waitUntil("license text", 5000, () -> visible("Open source notices")); tap("Close", true);
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
        waitUntil("search results", 10_000, () -> visible("Generated Film")); tap("Home", false); tap("Browse catalogues", true); tap("Generated films", false);
        tap("genre", true); tap("Generated", false); tap("Next page", true);
        waitUntil("next page", 10_000, () -> visible("Previous page")); tap("Previous page", false);
    }
}
