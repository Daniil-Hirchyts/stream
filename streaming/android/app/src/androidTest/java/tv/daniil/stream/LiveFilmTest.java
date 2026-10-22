package tv.daniil.stream;

import static org.junit.Assert.*;
import android.content.Context;
import org.json.JSONObject;
import tv.daniil.stream.addon.Title;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Real public-domain films over the internet, installed through the store as a viewer would. Not part of the
 * required suites: `./streaming/simulator.sh live-film` runs it on the verified emulator. The torrent case depends
 * on the public swarm having peers at the time.
 */
@RunWith(AndroidJUnit4.class)
public final class LiveFilmTest extends AppHarness {
    @Before public void internet() {
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().putBoolean("localOnly", false)
                .remove("peer").remove("cacheBytes").remove("addonDirectory").commit();
        app.resetServices(); services = app.services();
        services.store.setPreference("onboardingDone", "true");
    }
    /** Installs through the store's real rows and review page, then opens the film's details from that provider. */
    private void installAndOpen(String addon, String imdb, String name) throws Exception {
        tap("Settings", true); tap("Add-on store", false); tap(addon, false); tap("Install", true);
        waitUntil(addon + " installed", 20_000, () -> services.store.addons().stream().anyMatch(a -> a.manifest.name.equals(addon)));
        String provider = services.store.addons().stream().filter(a -> a.manifest.name.equals(addon)).findFirst().get().id;
        Title film = Title.parse(provider, new JSONObject().put("id", imdb).put("type", "movie").put("name", name));
        browse.onActivity(activity -> activity.openTitle(film, ""));
    }

    @Test public void internetArchiveFilmPlaysFromTheStore() throws Exception {
        launch(); installAndOpen("Internet Archive", "tt0063350", "Night of the Living Dead"); tap("Play", true);
        waitUntil("Night of the Living Dead plays from archive.org", 120_000,
                () -> state() != null && state().renderedFirstFrame && state().playing && state().positionMs >= 5000);
        WatchActivity activity = watch();
        instrumentation.runOnMainSync(() -> AppFlowTest.session(activity).seekTo(30 * 60_000));
        waitUntil("seek half an hour in", 90_000, () -> state().playing && state().positionMs >= 30 * 60_000 + 3000);
    }

    @Test public void publicDomainMoviesTorrentPlaysFromTheStore() throws Exception {
        launch(); installAndOpen("Public Domain Movies", "tt0063350", "Night of the Living Dead"); tap("Play", true);
        waitUntil("Night of the Living Dead plays from the public swarm", 300_000,
                () -> state() != null && state().renderedFirstFrame && state().playing && state().positionMs >= 5000);
        assertTrue(diagnostics().toString(), diagnostics().optInt("peers") > 0);
    }
}
