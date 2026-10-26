package tv.daniil.stream;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import tv.daniil.stream.addon.Title;

/** Focus and pointer navigation through the production series screen, with an isolated local title. */
public final class SeriesBrowseTest extends AppHarness {
    @Test public void seasonsFollowFocusAndKeepShowTitleAboveEpisodes() throws Exception {
        try (LogoServer logo = new LogoServer()) {
        JSONArray episodes = new JSONArray();
        for (int season = 1; season <= 2; season++) for (int number = 1; number <= 8; number++) episodes.put(new JSONObject()
                .put("id", "sample:s" + season + "e" + number).put("season", season).put("episode", number)
                .put("title", (season == 1 ? "First season " : "Second season ") + number)
                .put("released", number == 1 ? "2025-09-12T00:00:00.000Z" : number == 2 ? "" : "unknown")
                .put("overview", "An episode of the selected season. The story continues as old friends return and a new mystery unfolds. Nothing goes as planned and the journey brings an unexpected discovery."));
        Title series = Title.parse("local", new JSONObject().put("id", "test:seasons").put("type", "series")
                .put("name", "Adaptive Series").put("description", "A show overview that gives way to the episodes when browsing seasons.")
                .put("videos", episodes).put("logo", logo.url));
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        browse.onActivity(activity -> activity.openTitle(series, ""));
        waitUntil("overview starts on Play", 10000, () -> focused("play"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("episode browsing replaces overview", 5000, () -> focused("season:1") && !visible("Play"));
        waitUntil("compact show title", 5000, () -> shown("series-header"));
        waitUntil("title logo loads", 5000, () -> {
            AtomicBoolean loaded = new AtomicBoolean();
            browse.onActivity(activity -> {
                android.widget.ImageView image = activity.findViewById(R.id.page_rows).findViewWithTag("series-logo");
                loaded.set(image != null && image.isShown() && image.getDrawable() != null);
            });
            return loaded.get();
        });
        SystemClock.sleep(400);
        assertFalse("No redundant Episodes heading", visible("Episodes"));
        Rect title = bounds("series-header");
        assertTrue("Show title remains near the top", title.top < dp(140));
        assertTrue("Episodes are brought into the main viewport", bounds("episode:sample:s1e1").bottom < context.getResources().getDisplayMetrics().heightPixels);
        final View[] stripButton = new View[1];
        browse.onActivity(activity -> stripButton[0] = activity.findViewById(R.id.page_rows).findViewWithTag("season:2"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        waitUntil("focus alone changes season", 5000, () -> focused("season:2") && shown("episode:sample:s2e1"));
        assertFalse("Previous season is replaced", shown("episode:sample:s1e1"));
        browse.onActivity(activity -> {
            View selected = activity.findViewById(R.id.page_rows).findViewWithTag("season:2");
            assertSame("Season focus survives without rebuilding the strip", stripButton[0], selected);
            assertTrue(selected.isActivated());
        });
        assertEquals("Show title stays in position", title, bounds("series-header"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("Down enters selected season's episodes", 5000, () -> focused("episode:sample:s2e1"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        waitUntil("Up preserves selected season", 5000, () -> focused("season:2"));
        hover(bounds("season:1"));
        waitUntil("mouse hover changes season without clicking", 5000, () -> focused("season:1") && shown("episode:sample:s1e1"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("Down enters episodes", 5000, () -> focused("episode:sample:s1e1"));
        assertEquals("Show title stays visible while browsing episodes", title, bounds("series-header"));
        SystemClock.sleep(350);
        browse.onActivity(activity -> {
            android.view.ViewGroup episode = activity.findViewById(R.id.page_rows).findViewWithTag("episode:sample:s1e1");
            android.widget.TextView number = episode.findViewWithTag("episode-number");
            android.widget.TextView name = episode.findViewWithTag("tile-title");
            android.widget.TextView description = episode.findViewWithTag("episode-description");
            android.widget.TextView date = episode.findViewWithTag("episode-release-date");
            assertEquals("Episode 1", number.getText().toString()); assertEquals("First season 1", name.getText().toString());
            assertTrue(number.getTop() < name.getTop()); assertTrue(name.getTop() < description.getTop());
            assertEquals(3, description.getMaxLines()); assertTrue(description.getLineCount() > 1);
            assertNotNull("Provider release date is shown", date); assertTrue(date.getText().toString().contains("2025"));
            assertTrue("Release date follows the synopsis", date.getTop() > description.getTop());
            Rect complete = new Rect(); assertTrue(episode.getGlobalVisibleRect(complete));
            assertTrue("All episode information fits on screen", complete.height() >= episode.getHeight() - 1);
            View undated = activity.findViewById(R.id.page_rows).findViewWithTag("episode:sample:s1e2");
            assertNotNull(undated); assertNull("Missing dates add no empty caption", undated.findViewWithTag("episode-release-date"));
        });
        screenshot("series-episode-layout.png");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("episode actions", 5000, () -> visible("Play") && visible("Choose source"));
        assertTrue("Completion is an episode menu action", visible("Mark watched"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("episode card receives the window after the menu closes", 5000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            browse.onActivity(activity -> ready.set(activity.hasWindowFocus()));
            return ready.get() && focused("episode:sample:s1e1");
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        tap("Mark watched", true);
        waitUntil("episode marked", 5000, () -> {
            AtomicBoolean marked = new AtomicBoolean();
            browse.onActivity(activity -> {
                View episode = activity.findViewById(R.id.page_rows).findViewWithTag("episode:sample:s1e1");
                marked.set(episode != null && episode.getContentDescription().toString().endsWith("Watched"));
            });
            return marked.get();
        });
        waitUntil("episode card receives input after marking", 5000, () -> {
            AtomicBoolean ready = new AtomicBoolean(); browse.onActivity(activity -> ready.set(activity.hasWindowFocus()));
            return ready.get() && focused("episode:sample:s1e1");
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        waitUntil("Up returns to selected season", 5000, () -> focused("season:1"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        waitUntil("Up restores overview", 5000, () -> focused("play") && visible("Play"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("episode mode again", 5000, () -> focused("season:1") && !visible("Play"));
        tap("Back", true); waitUntil("Back restores overview", 5000, () -> focused("play"));
        tap("Back", false); waitUntil("Home restored", 5000, () -> visible("Make yourself at home"));
        assertFalse("Default Home has no Back", visible("Back"));
        }
    }

    @Test public void episodeMenuUsesItsSavedPlaybackAndWatchedState() throws Exception {
        JSONArray episodes = new JSONArray();
        for (int number = 1; number <= 3; number++) episodes.put(new JSONObject()
                .put("id", "menu:episode:" + number).put("season", 1).put("episode", number).put("title", "Episode menu " + number));
        Title series = Title.parse("local", new JSONObject().put("id", "test:episode-menu").put("type", "series")
                .put("name", "Episode actions").put("videos", episodes));
        services.store.progress(series, "menu:episode:2", 15000, 60000, false);
        services.store.progress(series, "menu:episode:3", 60000, 60000, true);
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        browse.onActivity(activity -> activity.openTitle(series, ""));
        waitUntil("overview", 5000, () -> focused("play"));
        assertFalse("The series overview has no film-style completion toggle", shown("watched"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("seasons", 5000, () -> focused("season:1") && !visible("Resume"));
        tap("Episode menu 2", true); waitUntil("episode playback menu", 5000, () -> visible("Choose source"));
        assertTrue(visible("Resume")); assertFalse(visible("Play"));
        assertTrue("Completion lives in episode options", visible("Mark watched"));
        assertFalse(visible("Mark unwatched"));
        tap("Mark watched", true);
        waitUntil("only this episode marked", 5000, () -> services.store.progress(series.key(), "menu:episode:2").watched);
        assertNull("Episode one was not changed", services.store.progress(series.key(), "menu:episode:1"));
        assertTrue("Episode three stays watched", services.store.progress(series.key(), "menu:episode:3").watched);
        assertFalse("No option button on the episode artwork", visible("Episode 2 options"));
        browse.onActivity(activity -> assertEquals("The episode has no child action buttons", 0,
                childActions(activity.findViewById(R.id.page_rows).findViewWithTag("episode:menu:episode:2"))));
        tap("Episode menu 2", false);
        waitUntil("single state-specific action", 5000, () -> visible("Mark unwatched"));
        assertFalse(visible("Mark watched"));
        SystemClock.sleep(350); // Capture the final panel, after its expansion.
        screenshot("validation-episode-options.png");
        tap("Mark unwatched", false);
        waitUntil("episode unmarked", 5000, () -> !services.store.progress(series.key(), "menu:episode:2").watched);
        tap("Episode menu 2", false); waitUntil("resume preserved", 5000, () -> visible("Resume") && visible("Choose source"));
        assertFalse(visible("Play")); assertTrue(visible("Mark watched"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("episode menu closes", 5000, () -> !visible("Choose source"));
        Title film = Title.parse("local", new JSONObject().put("id", "test:film-toggle").put("type", "movie").put("name", "Film completion"));
        browse.onActivity(activity -> activity.openTitle(film, ""));
        waitUntil("film keeps its own watched control", 5000, () -> shown("watched") && visible("Mark watched"));
        tap("Mark watched", false);
        waitUntil("film marked watched", 5000, () -> services.store.progress(film.key(), film.id) != null && services.store.progress(film.key(), film.id).watched);
    }

    @Test public void seriesArtworkLoadsForTheVisibleSeasonRatherThanTheWholeShow() throws Exception {
        try (LogoServer images = new LogoServer()) {
            JSONArray episodes = new JSONArray();
            for (int season = 1; season <= 3; season++) for (int number = 1; number <= 20; number++) episodes.put(new JSONObject()
                    .put("id", "art:s" + season + "e" + number).put("season", season).put("episode", number)
                    .put("title", "Episode " + number).put("thumbnail", images.url.replace("logo.png", "s" + season + "e" + number + ".png")));
            Title series = Title.parse("local", new JSONObject().put("id", "test:artwork").put("type", "series")
                    .put("name", "Artwork demand review").put("videos", episodes));
            launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
            browse.onActivity(activity -> activity.openTitle(series, ""));
            waitUntil("overview", 5000, () -> focused("play"));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            waitUntil("first season artwork requested", 5000, () -> images.requests.contains("/s1e1.png"));
            SystemClock.sleep(500);
            int first = images.requests.size();
            assertTrue("Only the visible portion of a 20-episode season is fetched", first > 0 && first < 20);
            assertTrue("Hidden seasons have no artwork requests", images.requests.stream().allMatch(path -> path.startsWith("/s1")));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitUntil("new season artwork requested on focus", 5000, () -> images.requests.contains("/s2e1.png"));
            SystemClock.sleep(300);
            assertFalse("Unvisited season stays unloaded", images.requests.stream().anyMatch(path -> path.startsWith("/s3")));
            java.nio.file.Files.write(new File(context.getFilesDir(), "series-artwork-review.txt").toPath(),
                    ("60 episodes across three seasons. Requests after first season: " + first
                            + ". After focusing second season: " + images.requests.size() + ". No third-season requests.\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test public void defaultTabsHideBackButSubpagesKeepIt() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        assertFalse(visible("Back"));
        tap("Library", false); waitUntil("Library", 5000, () -> visible("Your library is empty"));
        assertFalse(visible("Back"));
        tap("Settings", true); waitUntil("Settings", 5000, () -> visible("Add-ons"));
        assertFalse(visible("Back"));
        browse.onActivity(MainActivity::openImports);
        waitUntil("Imports has Back", 5000, () -> visible("Add magnet or torrent URL") && visible("Back"));
        tap("Back", false); waitUntil("Settings restored", 5000, () -> visible("Add-ons") && !visible("Back"));
    }
    private static final class LogoServer implements AutoCloseable {
        final java.net.ServerSocket server;
        final Thread thread;
        final String url;
        final java.util.Set<String> requests = java.util.concurrent.ConcurrentHashMap.newKeySet();
        LogoServer() throws Exception {
            Bitmap bitmap = Bitmap.createBitmap(500, 100, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(android.graphics.Color.WHITE); paint.setTextSize(58);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC));
            canvas.drawText("ADAPTIVE SERIES", 8, 76, paint);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle(); byte[] png = bytes.toByteArray();
            server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
            url = "http://127.0.0.1:" + server.getLocalPort() + "/logo.png";
            thread = new Thread(() -> {
                while (!server.isClosed()) try (java.net.Socket connection = server.accept()) {
                    connection.setSoTimeout(2000);
                    java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream()));
                    String request = input.readLine();
                    if (request != null && request.startsWith("GET ")) requests.add(request.split(" ")[1]);
                    while (true) { String line = input.readLine(); if (line == null || line.isEmpty()) break; }
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " + png.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    connection.getOutputStream().write(png);
                } catch (java.io.IOException ignored) { if (server.isClosed()) return; }
            }, "series-logo-loopback"); thread.start();
        }
        @Override public void close() throws Exception { server.close(); thread.join(3000); }
    }
    private static int childActions(View view) {
        int result = 0;
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
            View child = ((android.view.ViewGroup) view).getChildAt(i);
            if (child.isClickable() || child.isFocusable()) result++;
            result += childActions(child);
        }
        return result;
    }
    private boolean focused(String key) {
        AtomicBoolean result = new AtomicBoolean();
        browse.onActivity(activity -> result.set(activity.getCurrentFocus() != null && key.equals(activity.getCurrentFocus().getTag())));
        return result.get();
    }
    private boolean shown(String key) {
        AtomicBoolean result = new AtomicBoolean();
        browse.onActivity(activity -> { View view = activity.findViewById(R.id.page_rows).findViewWithTag(key); result.set(view != null && view.isShown()); });
        return result.get();
    }
    private Rect bounds(String key) {
        Rect rect = new Rect();
        browse.onActivity(activity -> {
            View view = activity.findViewById(R.id.page_rows).findViewWithTag(key);
            assertNotNull(key, view); assertTrue(key, view.getGlobalVisibleRect(rect));
        });
        return rect;
    }
    private void hover(Rect bounds) {
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, MotionEvent.ACTION_HOVER_MOVE, bounds.centerX(), bounds.centerY(), 0);
        event.setSource(InputDevice.SOURCE_MOUSE);
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle();
        instrumentation.waitForIdleSync();
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
    }
}
