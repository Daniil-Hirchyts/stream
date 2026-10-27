package tv.daniil.stream;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tv.daniil.stream.addon.AddonRepository;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.addon.Title;
import tv.daniil.stream.data.TvStore;

/**
 * Which new episodes Home announces, and when it stops, with the real encrypted store and a loopback metadata add-on.
 * Episodes finished in a test are saved "now" ({@link #T0}); release dates and the clock passed in sit around it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class NewEpisodesTest {
    private static final long HOUR = 3_600_000L, DAY = NewEpisodes.DAY_MS;
    private long T0;
    private Context context;
    private TvStore store;

    @Before public void open() {
        FakeKeyStore.install();
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("test-new-episodes.db");
        store = new TvStore(context, "test-new-episodes.db");
        T0 = System.currentTimeMillis();
    }
    @After public void close() { store.close(); context.deleteDatabase("test-new-episodes.db"); }

    @Test public void announcesTheNextEpisodeOnceItAirsAfterTheViewerCaughtUp() throws Exception {
        Title show = series("caught-up", episode(1, 1, T0 - 14 * DAY), episode(1, 2, T0 - 7 * DAY), episode(1, 3, T0 + 2 * DAY));
        finish(show, 1, 1); finish(show, 1, 2);
        assertTrue("Not yet aired", news(T0 + DAY).isEmpty());
        NewEpisodes.Notice notice = news(T0 + 3 * DAY).get(show.key());
        assertNotNull(notice);
        assertEquals("caught-up:1:3", notice.episode.id);
        assertEquals("New episode", notice.label());
        assertEquals("caught-up:1:3", NewEpisodes.nextUp(show, progress(show), T0 + 3 * DAY).id);
        assertNull("An episode that hasn't aired isn't offered as next", NewEpisodes.nextUp(show, progress(show), T0));
    }

    @Test public void aViewerWhoIsBehindOrHasStartedTheEpisodeIsNotTold() throws Exception {
        Title behind = series("behind", episode(1, 1, T0 - 60 * DAY), episode(1, 2, T0 - 30 * DAY), episode(1, 3, T0 + DAY));
        finish(behind, 1, 1);
        Title started = series("started", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY));
        finish(started, 1, 1);
        // Opening it for a moment doesn't count; Continue Watching starts at 30 seconds.
        store.progress(started, "started:1:2", 5_000, 45 * 60_000, false);
        assertEquals(Collections.singleton(started.key()), news(T0 + 2 * DAY).keySet());
        store.progress(started, "started:1:2", 10 * 60_000, 45 * 60_000, false);
        assertTrue(news(T0 + 2 * DAY).isEmpty());
    }

    @Test public void aSeasonReleasedAtOnceIsNotNewWhileItIsBeingWatched() throws Exception {
        long drop = T0 - 3 * HOUR;
        Title binge = series("binge", episode(1, 1, drop), episode(1, 2, drop), episode(1, 3, drop));
        finish(binge, 1, 1); finish(binge, 1, 2);
        assertTrue(news(T0 + HOUR).isEmpty());
        // A weekly episode dated the morning of the day the viewer finished the last one still counts.
        Title weekly = series("weekly", episode(1, 1, T0 - 8 * DAY), episode(1, 2, T0 - 20 * HOUR), episode(1, 3, T0 - 2 * HOUR));
        finish(weekly, 1, 1); finish(weekly, 1, 2);
        assertTrue(news(T0 + HOUR).containsKey(weekly.key()));
    }

    @Test public void countsNewEpisodesAndNamesANewSeason() throws Exception {
        Title season = series("season", episode(0, 1, T0 + DAY), episode(1, 1, T0 - 400 * DAY), episode(1, 2, T0 - 393 * DAY),
                episode(2, 1, T0 + DAY), episode(2, 2, T0 + DAY), episode(2, 3, T0 + 30 * DAY));
        finish(season, 1, 1); finish(season, 1, 2);
        NewEpisodes.Notice notice = news(T0 + 2 * DAY).get(season.key());
        assertEquals("Specials are skipped", "season:2:1", notice.episode.id);
        assertEquals("New season", notice.label());
        assertEquals(Arrays.asList("season:2:1", "season:2:2"), new java.util.ArrayList<>(notice.episodes));
        Title weekly = series("count", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY), episode(1, 3, T0 + 8 * DAY));
        finish(weekly, 1, 1);
        assertEquals("2 new episodes", news(T0 + 9 * DAY).get(weekly.key()).label());
    }

    @Test public void favouritesAreFollowedBeforeTheyArePlayedAndOtherTitlesAreNot() throws Exception {
        Title upcoming = series("premiere", episode(1, 1, T0 + 5 * DAY));
        store.favourite(upcoming, true);
        Title opened = series("opened", episode(1, 1, T0 + 5 * DAY));
        store.remember(opened);
        Map<String, NewEpisodes.Notice> found = news(T0 + 6 * DAY);
        assertEquals("premiere:1:1", found.get(upcoming.key()).episode.id);
        assertFalse(found.containsKey(opened.key()));
        assertEquals("No episode finished: the first is next", "opened:1:1", NewEpisodes.nextUp(opened, progress(opened), T0 + 6 * DAY).id);
    }

    @Test public void aNoticeEndsAfterSevenDaysOnHomeOrAMonthAndStaysGone() throws Exception {
        Title shown = series("shown", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY));
        finish(shown, 1, 1);
        long now = T0 + 2 * DAY;
        // 30-hour steps always reach another local day, whatever the time zone does.
        for (int day = 0; day < NewEpisodes.SHOWN_DAYS; day++, now += 30 * HOUR)
            assertTrue("Shown on day " + (day + 1), NewEpisodes.current(store, store.library(false), store.history(false), now, true).containsKey(shown.key()));
        assertTrue("Ignored for a week", NewEpisodes.current(store, store.library(false), store.history(false), now, true).isEmpty());
        assertTrue("Not shown again", news(now + DAY).isEmpty());

        Title unseen = series("unseen", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY));
        finish(unseen, 1, 1);
        assertTrue(news(T0 + 2 * DAY).containsKey(unseen.key()));
        assertTrue("Elsewhere than Home, looking doesn't count", news(T0 + 2 * DAY + 20 * DAY).containsKey(unseen.key()));
        assertFalse("A month after it appeared", news(T0 + 2 * DAY + NewEpisodes.LIFETIME_MS).containsKey(unseen.key()));
    }

    @Test public void hidingEndsOneNoticeAndTheNextEpisodeBringsAnother() throws Exception {
        Title show = series("hide", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 - 12 * HOUR), episode(1, 3, T0 + 3 * DAY));
        finish(show, 1, 1);
        assertTrue(news(T0).containsKey(show.key()));
        NewEpisodes.hide(store, show.key());
        assertFalse(news(T0).containsKey(show.key()));
        finish(show, 1, 2);
        assertEquals("hide:1:3", news(T0 + 4 * DAY).get(show.key()).episode.id);
    }

    @Test public void alertsTurnedOffStayOffUntilTurnedOnAndLeaveWithTheLibraryEntry() throws Exception {
        Title show = series("mute", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 + DAY));
        finish(show, 1, 1);
        NewEpisodes.mute(store, show.key(), true);
        assertTrue(NewEpisodes.muted(store, show.key()));
        assertTrue(news(T0 + 2 * DAY).isEmpty());
        NewEpisodes.mute(store, show.key(), false);
        assertTrue(news(T0 + 2 * DAY).containsKey(show.key()));
        NewEpisodes.mute(store, show.key(), true);
        store.removeLibrary(show.key());
        assertTrue(news(T0 + 2 * DAY).isEmpty());
        finish(show, 1, 1);
        assertFalse("Removing it forgot the choice", NewEpisodes.muted(store, show.key()));
    }

    @Test public void aSeriesLeftForTwoYearsIsNoLongerFollowedUnlessItIsAFavourite() throws Exception {
        long later = T0 + NewEpisodes.FOLLOW_MS + DAY;
        Title left = series("left", episode(1, 1, T0 - 7 * DAY), episode(1, 2, later - HOUR));
        finish(left, 1, 1);
        assertTrue(news(later).isEmpty());
        store.favourite(left, true);
        assertTrue(news(later).containsKey(left.key()));
    }

    @Test public void releaseDatesAreReadAsInstantsOrBroadcastDays() {
        assertEquals(Instant.parse("2026-10-20T01:30:00Z").toEpochMilli(), NewEpisodes.aired("2026-10-20T01:30:00.000Z"));
        assertEquals(Instant.parse("2026-10-20T00:00:00Z").toEpochMilli(), NewEpisodes.aired("2026-10-20"));
        assertEquals(-1, NewEpisodes.aired(""));
        assertEquals(-1, NewEpisodes.aired("unknown date"));
        assertEquals(-1, NewEpisodes.aired(null));
    }

    @Test public void checksFetchFollowedSeriesTwiceADayAndSaveTheirNewEpisodes() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        JSONObject[] meta = {null};
        ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread serving = new Thread(() -> {
            // A minimal add-on: its manifest, and the series' metadata as the test last set it.
            while (!server.isClosed()) try (Socket client = server.accept()) {
                BufferedReader request = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String path = request.readLine().split(" ")[1];
                while (!request.readLine().isEmpty()) { /* headers */ }
                String body = path.endsWith("/manifest.json") ? new JSONObject().put("id", "test.meta").put("version", "1.0.0").put("name", "Meta")
                        .put("types", new JSONArray().put("series")).put("resources", new JSONArray().put("meta")).put("catalogs", new JSONArray()).toString()
                        : new JSONObject().put("meta", meta[0]).toString();
                if (path.startsWith("/meta/")) requests.incrementAndGet();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                OutputStream out = client.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes); out.flush();
            } catch (Exception closed) { /* the test finished */ }
        });
        serving.start();
        try (AddonRepository addons = new AddonRepository(store)) {
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/manifest.json";
            String provider;
            try (Cancellation scope = addons.newRequest()) { provider = addons.install(url, addons.preview(url, scope)).id; }
            Title saved = Title.parse(provider, json("checked", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 - DAY)));
            finish(saved, 1, 1); finish(saved, 1, 2);
            Title ignored = Title.parse(provider, json("muted", episode(1, 1, T0 - 7 * DAY)));
            finish(ignored, 1, 1);
            NewEpisodes.mute(store, ignored.key(), true);
            meta[0] = json("checked", episode(1, 1, T0 - 7 * DAY), episode(1, 2, T0 - DAY), episode(1, 3, T0 + HOUR));

            long placed = entry(saved).updatedMs;
            assertTrue(NewEpisodes.check(store, addons, T0 + 2 * HOUR));
            assertEquals("The muted series isn't asked about", 1, requests.get());
            NewEpisodes.Notice notice = news(T0 + 2 * HOUR).get(saved.key());
            assertEquals("checked:1:3", notice.episode.id);
            assertEquals("checked:1:3", entry(saved).title.episodes.get(2).id);
            assertEquals("Its place in Recently played is kept", placed, entry(saved).updatedMs);
            assertFalse("Checked recently", NewEpisodes.check(store, addons, T0 + 3 * HOUR));
            assertEquals(1, requests.get());
            assertFalse("Asked again after twelve hours; nothing changed", NewEpisodes.check(store, addons, T0 + 2 * HOUR + NewEpisodes.CHECK_MS));
            assertEquals(2, requests.get());
        } finally { server.close(); serving.join(5000); }
    }

    private Map<String, NewEpisodes.Notice> news(long now) { return NewEpisodes.current(store, now); }
    private TvStore.LibraryEntry entry(Title show) {
        for (TvStore.LibraryEntry e : store.library(false)) if (e.title.key().equals(show.key())) return e;
        throw new AssertionError("Not in the library");
    }
    private void finish(Title show, int season, int number) {
        store.progress(show, show.id + ":" + season + ":" + number, 44 * 60_000, 45 * 60_000, true);
    }
    private Map<String, TvStore.Progress> progress(Title show) {
        Map<String, TvStore.Progress> out = new HashMap<>();
        for (TvStore.Progress p : store.history(show.key())) out.put(p.video, p);
        return out;
    }
    private static Title series(String id, JSONObject... episodes) throws Exception { return Title.parse("provider", json(id, episodes)); }
    private static JSONObject json(String id, JSONObject... episodes) throws Exception {
        JSONArray videos = new JSONArray();
        for (JSONObject e : episodes) videos.put(new JSONObject(e.toString()).put("id", id + ":" + e.getInt("season") + ":" + e.getInt("episode")));
        return new JSONObject().put("id", id).put("type", "series").put("name", "Series " + id).put("videos", videos);
    }
    private static JSONObject episode(int season, int number, long released) throws Exception {
        return new JSONObject().put("season", season).put("episode", number).put("title", "Episode " + number)
                .put("released", Instant.ofEpochMilli(released).toString());
    }
}
