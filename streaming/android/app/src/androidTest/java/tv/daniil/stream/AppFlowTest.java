package tv.daniil.stream;

import static org.junit.Assert.*;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AppFlowTest extends AppHarness {
    @Test public void starterReviewRequiresAcceptanceAndKeepsSuccessfulInstallsWhenOneFails() throws Exception {
        context.getSharedPreferences("instrumentation", android.content.Context.MODE_PRIVATE).edit()
                .putString("starterAddons", origin + "/addon/good/starter/manifest.json " + origin + "/missing-addon/manifest.json").commit();
        app.resetServices(); services = app.services();
        launch(); tap("Settings", true); tap("Add-on store", false); tap("Starter add-ons", true);
        waitUntil("starter review", 10_000, () -> visible("Install"));
        assertTrue("Opening the review does not install providers", services.store.addons().isEmpty());
        tap("Install", false);
        waitUntil("partial starter install is explained", 15_000, () -> visible("Some add-ons weren't added"));
        assertEquals(1, services.store.addons().size());
        assertEquals(origin + "/addon/good/starter/manifest.json", services.store.addons().get(0).address.manifest);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("installed catalogue appears on Home", 10_000, () -> visible("Generated Film"));
    }

    @Test public void catalogueFavouritesPlaybackControlsAndEvictedRewindWork() throws Exception {
        playFilm();
        waitUntil("native statistics", 5000, () -> diagnostics().has("cache"));
        JSONObject initial = diagnostics(); assertTrue(initial.toString(), initial.getLong("totalDownload") < 68_000_000);
        // Stream's player has no transport row: a pointer pauses with a timeline tap, the remote skips.
        control(tv.daniil.playback.R.id.player_view, false); control(tv.daniil.playback.R.id.progress, true);
        waitUntil("paused by mouse", 5000, () -> state() != null && !state().playWhenReady);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        waitUntil("first remote key seeks after mouse input", 20_000, () -> state().positionMs >= 10_000);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY);
        WatchActivity activity = watch();
        instrumentation.runOnMainSync(() -> { tv.daniil.playback.PlaybackSession s = session(activity); s.seekTo(36_000); });
        waitUntil("late playback", 30_000, () -> state().playing && state().positionMs >= 37_000);
        instrumentation.runOnMainSync(() -> session(activity).seekTo(1000));
        waitUntil("evicted rewind", 30_000, () -> state().playing && state().positionMs >= 2500 && state().positionMs < 15_000);
        JSONObject cache = diagnostics().getJSONObject("cache"); assertTrue(cache.getLong("evicted") > 0); assertTrue(cache.getLong("allocatedBytes") <= (16L << 20));
        // Play opens the player straight from Details, so closing it returns there.
        instrumentation.runOnMainSync(activity::finish);
        tap("Add favourite", false);
        waitUntil("favourite persisted", 5000, () -> services.store.library(true).size() == 1);
    }
    @Test public void seriesResolvesItsOriginalVideoIdAndNextEpisodeIndependently() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10_000, () -> visible("Generated Film"));
        // The series shelf starts below the fold, under a "Generated series" heading; the remote brings its card up.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        tap("Generated Series", true); tap("Play", false); chooseFirstSource();
        waitUntil("first episode", 60_000, () -> state() != null && state().playing && state().renderedFirstFrame);
        WatchActivity activity = watch(); instrumentation.runOnMainSync(() -> assertTrue(activity.onNextEpisode()));
        waitUntil("second episode", 60_000, () -> state() != null && state().playing && state().positionMs > 1000);
        instrumentation.runOnMainSync(activity::finish);
        waitUntil("episode history", 10_000, () -> services.store.history(false).size() >= 2);
        assertTrue(services.store.history(false).stream().anyMatch(p -> p.video.equals("fixture:episode/custom?part=2")));
    }
    @Test public void addonStoreReviewsInstallsAndFiltersListedAddons() throws Exception {
        launch();
        tap("Settings", true); tap("Add-on store", false); waitUntil("store lists", 15_000, () -> visible("Setup fixture") || visible("Local fixtures"));
        assertTrue(visible("Internet Archive"));
        assertFalse(visible("Torrentio")); // Local-only instrumentation must never offer public scraper endpoints.
        int loadedLists = storeRequests();
        tap("Torrent/P2P", false);
        waitUntil("torrent filter", 10_000, () -> visible("Local fixtures") && !visible("Internet Archive") && !visible("Community fixture"));
        assertEquals("Changing a filter must reuse the directory results", loadedLists, storeRequests());
        tap("Local fixtures", false); waitUntil("review", 10_000, () -> visible("Install"));
        assertTrue(services.store.addons().isEmpty());
        tap("Install", true); waitUntil("installed from the store", 10_000, () -> services.store.addons().size() == 1);
        waitUntil("installed state", 10_000, () -> visible("Manage add-on"));
        assertEquals(origin + "/addon/good/store/manifest.json", services.store.addons().get(0).address.manifest);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        tap("Subtitles", false); tap("Community fixture", true);
        waitUntil("subtitle provider review", 10_000, () -> visible("Install") && visible("Community fixture"));
        assertTrue("Installing a provider must refresh directory discovery", storeRequests() > loadedLists);
        assertEquals(1, services.store.addons().size());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        tap("Setup fixture", true); waitUntil("needs setup", 10_000, () -> visible("Enter configured address"));
        assertFalse(visible("Install"));
        tap("Enter configured address", false);
        String configured = origin + "/addon/good/configured-setup%2Fprivate/manifest.json?token=fixture-only";
        enter(configured); tap("Continue", true);
        waitUntil("configured manifest review", 10_000, () -> visible("Install"));
        assertEquals(1, services.store.addons().size()); // Entering an address isn't acceptance.
        tap("Install", false);
        waitUntil("configured install", 10_000, () -> services.store.addons().size() == 2 && visible("Manage add-on"));
        assertTrue(services.store.addons().stream().anyMatch(a -> a.address.manifest.equals(configured)));
    }
    private int storeRequests() throws Exception {
        try (tv.daniil.stream.addon.AddonClient client = new tv.daniil.stream.addon.AddonClient();
             tv.daniil.stream.addon.Cancellation scope = new tv.daniil.stream.addon.Cancellation()) {
            return client.json(origin + "/addon/stats.json", scope, tv.daniil.stream.addon.AddonClient.deadline()).getInt("storeRequests");
        }
    }
    static tv.daniil.playback.PlaybackSession session(WatchActivity activity) {
        try { java.lang.reflect.Field f = WatchActivity.class.getDeclaredField("session"); f.setAccessible(true); return (tv.daniil.playback.PlaybackSession) f.get(activity); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
