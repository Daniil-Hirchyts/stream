package tv.stream.app;

import static org.junit.Assert.*;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import tv.stream.app.addon.Title;

/** New episodes on Home, Library and Details, from an isolated library holding a series the viewer has caught up on. */
public final class NewEpisodesScreenTest extends AppHarness {
    @Test public void aCaughtUpSeriesAnnouncesItsNewEpisodeUntilHidden() throws Exception {
        long now = System.currentTimeMillis(), week = 7 * NewEpisodes.DAY_MS;
        JSONArray videos = new JSONArray();
        // The third episode came out this morning; the viewer finished the second just now.
        for (int n = 1; n <= 3; n++) videos.put(new JSONObject().put("id", "followed:1:" + n).put("season", 1).put("episode", n)
                .put("title", "Part " + n).put("released", Instant.ofEpochMilli(n == 3 ? now - 3_600_000 : now - (3 - n) * week).toString()));
        // No installed add-on knows this provider, so Details shows the saved metadata without a network request.
        Title show = Title.parse("unavailable-provider", new JSONObject().put("id", "followed").put("type", "series")
                .put("name", "Followed Series").put("videos", videos));
        for (int n = 1; n <= 2; n++) services.store.progress(show, "followed:1:" + n, 44 * 60_000, 45 * 60_000, true);
        services.store.favourite(show, true);
        String card = "new:" + show.key() + ":followed:1:3";

        launch();
        waitUntil("Home leads with the new episode", 10000, () -> visible("New Episodes") && focused(card));
        assertEquals("Followed Series. New episode, season 1 episode 3, Part 3", description(card));
        assertTrue("Spotlight says what is new", visible("Followed Series. New episode · S1 E3"));
        assertEquals("Favourite poster says New", "New", flag("favourite:" + show.key()));

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("Details open on the new episode", 10000, () -> focused("play") && visible("S1 E3"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("episodes", 5000, () -> focused("season:1"));
        waitUntil("the new episode card is marked", 5000, () -> "New".equals(flag("episode:followed:1:3")));
        assertNull("watched episodes aren't", flag("episode:followed:1:2"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("overview", 5000, () -> focused("play"));

        tap("More", false);
        waitUntil("menu", 5000, () -> visible("Hide new episode") && visible("Turn off new episode alerts"));
        tap("Hide new episode", false);
        waitUntil("hidden", 10000, () -> !visible("Hide new episode") && focused("more"));
        browse.onActivity(activity -> activity.root("Home"));
        waitUntil("Home without the notice", 10000, () -> shown("favourite:" + show.key()) && !visible("New Episodes"));
        assertNull(flag("favourite:" + show.key()));

        // Opening the series without choosing an episode still starts where the viewer is, now unmarked.
        browse.onActivity(activity -> activity.openTitle(show, ""));
        waitUntil("Play continues the series", 10000, () -> focused("play") && visible("S1 E3"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("episodes", 5000, () -> focused("season:1"));
        waitUntil("no longer marked", 5000, () -> shown("episode:followed:1:3") && flag("episode:followed:1:3") == null);
    }

    @Test public void libraryMarksSeriesWithNewEpisodes() throws Exception {
        long now = System.currentTimeMillis();
        JSONArray videos = new JSONArray()
                .put(new JSONObject().put("id", "library:1:1").put("season", 1).put("episode", 1).put("title", "Pilot").put("released", Instant.ofEpochMilli(now - 30 * NewEpisodes.DAY_MS).toString()))
                .put(new JSONObject().put("id", "library:2:1").put("season", 2).put("episode", 1).put("title", "Return").put("released", Instant.ofEpochMilli(now - 3_600_000).toString()));
        Title show = Title.parse("unavailable-provider", new JSONObject().put("id", "library-series").put("type", "series").put("name", "Returning Series").put("videos", videos));
        services.store.progress(show, "library:1:1", 50 * 60_000, 50 * 60_000, true);
        launch();
        waitUntil("Home", 10000, () -> visible("New Episodes"));
        assertTrue(visible("Returning Series. New season, season 2 episode 1, Return"));
        browse.onActivity(activity -> activity.root("Library"));
        waitUntil("Library poster", 10000, () -> "New".equals(flag("library:" + show.key())));
        assertEquals("Returning Series. New", description("library:" + show.key()));
    }

    private boolean focused(String key) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        browse.onActivity(activity -> result.set(activity.getCurrentFocus() != null && key.equals(activity.getCurrentFocus().getTag())));
        return result.get();
    }
    private boolean shown(String key) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        browse.onActivity(activity -> { View view = activity.findViewById(R.id.page_rows).findViewWithTag(key); result.set(view != null && view.isShown()); });
        return result.get();
    }
    private String description(String key) {
        AtomicReference<String> result = new AtomicReference<>();
        browse.onActivity(activity -> {
            View view = activity.findViewById(R.id.page_rows).findViewWithTag(key);
            result.set(view == null || view.getContentDescription() == null ? null : view.getContentDescription().toString());
        });
        return result.get();
    }
    /** The word over a tile's artwork, or null when it has none. */
    private String flag(String key) {
        AtomicReference<String> result = new AtomicReference<>();
        browse.onActivity(activity -> {
            View tile = activity.findViewById(R.id.page_rows).findViewWithTag(key);
            TextView flag = tile == null ? null : tile.findViewWithTag("tile-flag");
            result.set(flag == null || !flag.isShown() ? null : flag.getText().toString());
        });
        return result.get();
    }
}
