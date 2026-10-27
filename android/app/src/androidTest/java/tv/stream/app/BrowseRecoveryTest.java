package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import tv.stream.app.addon.Cancellation;
import tv.stream.app.addon.Title;
import tv.stream.app.ui.PageAdapter;

/** Real navigation and HTTP failure paths, served inside the emulator with isolated owner data. */
public final class BrowseRecoveryTest extends AppHarness {
    @Test public void settingsReturnsToTheChosenPaneAndRow() throws Exception {
        launch(); waitUntil("empty Home", 10000, () -> visible("Make yourself at home"));
        tap("Settings", true); tap("Languages", true);
        waitUntil("keyboard language row", 5000, () -> visible("Keyboard languages"));
        browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("keyboard-languages").requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("keyboard language page", 5000, () -> visible("English"));
        tap("Back", true);
        waitUntil("settings restores the actual row", 5000, () -> focus().equals("keyboard-languages"));
        assertTrue("Languages pane is still shown", visible("Subtitle language"));
    }
    @Test public void transientCategoryFailuresKeepOnlySkeletonsUntilTheyRecover() throws Exception {
        try (CatalogServer server = start()) {
            gallery(); tap("Flaky", true);
            waitUntil("third bounded attempt", 10000, () -> server.requests("Flaky", 0) == 3);
            assertFalse(text("Attempt 3 of 3"));
            assertFalse(text("Retrying catalogue")); assertFalse(text("Loading catalogue"));
            assertFalse(visible("Reload"));
            AtomicReference<Boolean> skeleton = new AtomicReference<>(false);
            browse.onActivity(activity -> skeleton.set(((PageAdapter) ((RecyclerView) activity.findViewById(R.id.page_rows)).getAdapter()).position("skeleton:Catalogue results") >= 0));
            assertTrue("The skeleton remains during retry", skeleton.get());
            server.flakyThird.countDown();
            waitUntil("retry recovers posters", 10000, () -> visible("Film 00") && !text("Loading catalogue") && !text("Retrying catalogue"));
            assertEquals(3, server.requests("Flaky", 0));
            assertFalse(text("Unable to load catalogue"));
        }
    }

    @Test public void exhaustedRefreshKeepsPostersAndReloadStartsANewAttempt() throws Exception {
        try (CatalogServer server = start()) {
            gallery(); server.unavailable = true;
            tap("Refresh", true);
            waitUntil("bounded failure with inline reload", 12000, () -> text("Unable to load catalogue") && visible("Reload"));
            assertTrue(text("Attempt 3 of 3")); assertTrue(text("Showing saved entries"));
            assertTrue("Previously fetched posters remain usable", visible("Film 00"));
            assertFalse("A failed request is not an empty catalogue", text("No titles on this page"));
            assertEquals(4, server.requests("", 0));
            SystemClock.sleep(1400); assertEquals("Retries stop at the limit", 4, server.requests("", 0));
            server.unavailable = false; tap("Reload", false);
            waitUntil("manual reload recovers", 10000, () -> server.requests("", 0) == 5 && !text("Unable to load catalogue"));
            assertTrue(visible("Film 00"));
        }
    }

    @Test public void changingCategoryCancelsBackoffAndPermanentFailuresAreNotRetried() throws Exception {
        try (CatalogServer server = start()) {
            gallery(); tap("Offline", true);
            waitUntil("first failure before backoff", 5000, () -> server.requests("Offline", 0) >= 1);
            assertFalse(text("Retrying catalogue"));
            tap("Empty", true);
            waitUntil("empty success", 5000, () -> text("No titles on this page"));
            int cancelled = server.requests("Offline", 0);
            SystemClock.sleep(3300);
            assertEquals("No retry of the abandoned filter", cancelled, server.requests("Offline", 0));
            assertEquals(1, server.requests("Empty", 0)); assertFalse(text("Unable to load catalogue"));
            tap("Bad data", false);
            waitUntil("malformed response explained", 5000, () -> text("invalid catalogue"));
            assertEquals(1, server.requests("Bad data", 0));
            tap("Denied", true);
            waitUntil("HTTP refusal explained", 5000, () -> text("provider could not serve this page"));
            assertEquals(1, server.requests("Denied", 0));
        }
    }

    @Test public void galleryPaginationBackAndFavouritesKeepFocusWithoutProviderReloads() throws Exception {
        try (CatalogServer server = start()) {
            gallery(); openAndReturn("catalogue", 17, true);
            assertEquals("Back does not request the category again", 1, server.requests("", 0));
            tap("Next page", false);
            waitUntil("second category page", 5000, () -> visible("Film 24"));
            tap("Previous page", true);
            waitUntil("first category page returns", 5000, () -> visible("Film 00"));
            assertEquals("Recently visited page is reused", 1, server.requests("", 0));
            assertEquals(1, server.requests("", 24));
            openAndReturn("catalogue", 19, false);
            assertEquals(1, server.requests("", 0));
        }
    }

    @Test public void searchLibraryAndHomeRestoreTheirOwnFocusAndNestedViewports() throws Exception {
        try (CatalogServer server = start()) {
            for (int i = 0; i < 48; i++) services.store.favourite(title(server, i), true);
            tap("Library", true);
            waitUntil("library grid", 5000, () -> visible("Favourites"));
            openAndReturn("library", 31, false);
            browse.onActivity(activity -> activity.root("Settings"));
            browse.onActivity(activity -> activity.root("Library"));
            waitUntil("library card after tab switch", 5000, () -> focus().equals(tileKey("library", server, 31)));

            browse.onActivity(MainActivity::search);
            browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("search results", 5000, () -> visible("Film 00"));
            ageSnapshots(); openAndReturn("search", 36, true);
            assertEquals("Back does not repeat search", 1, server.searchRequests.get());
            browse.onActivity(activity -> activity.root("Home"));
            waitUntil("Home shelf", 5000, () -> visible("Film 00"));
            String homeKey = services.store.addons().get(0).id + ":movie:browse";
            ageSnapshots(); openAndReturn(homeKey, 14, true);
            assertEquals("Local data changes reuse Home's provider results", 1, server.homeRequests.get());
        }
    }

    private CatalogServer start() throws Exception {
        CatalogServer server = new CatalogServer();
        try (Cancellation request = services.addons.newRequest()) {
            services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
        }
        launch(); waitUntil("fixture Home", 10000, () -> visible("Film 00")); return server;
    }
    private void gallery() { tap("Browse", true); tap("Test films", true); waitUntil("category grid", 5000, () -> visible("Film 00")); }
    private Title title(CatalogServer server, int index) throws Exception { return Title.parse(services.store.addons().get(0).id, server.title(index)); }
    private String tileKey(String section, CatalogServer server, int index) {
        try { return section + ":" + title(server, index).key(); } catch (Exception e) { throw new AssertionError(e); }
    }
    private void openAndReturn(String section, int index, boolean favourite) throws Exception {
        String key = section + ":" + new JSONArray().put(services.store.addons().get(0).id).put("movie").put("browse:" + index);
        AtomicReference<Rect> before = new AtomicReference<>();
        browse.onActivity(activity -> {
            RecyclerView outer = activity.findViewById(R.id.page_rows);
            PageAdapter adapter = (PageAdapter) outer.getAdapter();
            outer.scrollToPosition(adapter.position(key));
        });
        if (section.equals("search") || section.contains(":movie:")) {
            waitUntil("nested results row attached", 5000, () -> {
                AtomicReference<Boolean> ready = new AtomicReference<>(false);
                browse.onActivity(activity -> {
                    RecyclerView outer = activity.findViewById(R.id.page_rows);
                    RecyclerView.ViewHolder holder = outer.findViewHolderForAdapterPosition(((PageAdapter) outer.getAdapter()).position(key));
                    if (holder != null) { RecyclerView child = nested(holder.itemView); if (child != null) { child.scrollToPosition(index); ready.set(true); } }
                }); return ready.get();
            });
        }
        waitUntil("selected film laid out", 5000, () -> {
            AtomicReference<Boolean> found = new AtomicReference<>(false);
            browse.onActivity(activity -> found.set(activity.findViewById(R.id.page_rows).findViewWithTag(key) != null)); return found.get();
        });
        browse.onActivity(activity -> { View selected = activity.findViewById(R.id.page_rows).findViewWithTag(key); selected.requestFocus(); });
        SystemClock.sleep(350);
        browse.onActivity(activity -> { Rect bounds = new Rect(); assertTrue(activity.getCurrentFocus().getGlobalVisibleRect(bounds)); before.set(bounds); });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("film details", 10000, () -> visible("Play"));
        if (favourite) {
            int count = services.store.library(true).size();
            tap(visible("Add favourite") ? "Add favourite" : "Remove favourite", true);
            waitUntil("favourite change saved", 5000, () -> services.store.library(true).size() != count);
        }
        tap("Back", false);
        waitUntil("exact film regains focus", 5000, () -> key.equals(focus()));
        SystemClock.sleep(350);
        browse.onActivity(activity -> {
            Rect after = new Rect(); assertTrue(activity.getCurrentFocus().getGlobalVisibleRect(after));
            assertEquals("Same horizontal viewport", before.get().left, after.left, 3);
            assertEquals("Same vertical viewport in " + section, before.get().top, after.top, 3);
        });
    }
    private RecyclerView nested(View view) {
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View child = ((ViewGroup) view).getChildAt(i);
            if (child instanceof RecyclerView) return (RecyclerView) child;
            RecyclerView found = nested(child); if (found != null) return found;
        }
        return null;
    }
    private String focus() {
        AtomicReference<String> result = new AtomicReference<>("");
        browse.onActivity(activity -> { if (activity.getCurrentFocus() != null) result.set(String.valueOf(activity.getCurrentFocus().getTag())); }); return result.get();
    }
    /** Simulate returning after a long film without making a device test wait five real minutes. */
    private void ageSnapshots() {
        browse.onActivity(activity -> {
            try {
                java.lang.reflect.Field screens = MainActivity.class.getDeclaredField("browse"); screens.setAccessible(true);
                Object data = screens.get(activity);
                java.lang.reflect.Field home = BrowseScreens.class.getDeclaredField("homeResults"); home.setAccessible(true);
                java.lang.reflect.Field search = BrowseScreens.class.getDeclaredField("searches"); search.setAccessible(true);
                java.util.List<Object> saved = new java.util.ArrayList<>(((Map<?, ?>) search.get(data)).values());
                if (home.get(data) != null) saved.add(home.get(data));
                for (Object result : saved) { java.lang.reflect.Field time = result.getClass().getDeclaredField("saved"); time.setAccessible(true); time.setLong(result, -300001); }
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
    private boolean text(String fragment) {
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        browse.onActivity(activity -> found.set(contains(activity.findViewById(R.id.page_rows), fragment))); return found.get();
    }
    private boolean contains(View view, String fragment) {
        if (view instanceof TextView && view.isShown() && ((TextView) view).getText().toString().contains(fragment)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (contains(((ViewGroup) view).getChildAt(i), fragment)) return true;
        return false;
    }

    static final class CatalogServer implements AutoCloseable {
        final ServerSocket server;
        final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool();
        final String url;
        final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        final AtomicInteger homeRequests = new AtomicInteger(), searchRequests = new AtomicInteger();
        final CountDownLatch flakyThird = new CountDownLatch(1);
        volatile boolean unavailable, emptySearch;
        volatile int failureStatus = 503;
        volatile int catalogCount = 1;
        volatile long catalogueDelayMs;
        volatile CountDownLatch homeGate;
        volatile String homeGateCatalog = "";
        CatalogServer() throws Exception {
            server = new ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1")); url = "http://127.0.0.1:" + server.getLocalPort();
            workers.execute(() -> { while (!server.isClosed()) try { Socket socket = server.accept(); workers.execute(() -> answer(socket)); } catch (Exception ignored) { break; } });
        }
        int requests(String genre, int skip) { AtomicInteger count = requests.get(genre + ":" + skip); return count == null ? 0 : count.get(); }
        JSONObject title(int index) throws Exception { return new JSONObject().put("id", "browse:" + index).put("type", "movie").put("name", String.format(java.util.Locale.ROOT, "Film %02d", index)).put("description", "Generated navigation fixture."); }
        private void answer(Socket socket) {
            try (Socket connection = socket; BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String request = in.readLine(); if (request == null) return;
                String path = request.split(" ")[1]; String header; while ((header = in.readLine()) != null && !header.isEmpty()) { }
                int status = 200; JSONObject result = new JSONObject();
                if (path.equals("/manifest.json")) {
                    JSONObject genre = new JSONObject().put("name", "genre").put("options", new JSONArray().put("Flaky").put("Offline").put("Empty").put("Bad data").put("Denied"));
                    JSONArray catalogs = new JSONArray();
                    for (int i = 0; i < catalogCount; i++) catalogs.put(new JSONObject().put("type", "movie").put("id", i == 0 ? "browse" : "browse-" + i).put("name", "Test films")
                            .put("extra", new JSONArray().put(genre).put(new JSONObject().put("name", "skip")).put(new JSONObject().put("name", "search"))));
                    result.put("id", "tv.stream.browse-fixture").put("name", "Browse fixture").put("version", "1.0.0")
                            .put("types", new JSONArray().put("movie")).put("resources", new JSONArray().put("catalog").put("meta").put("stream"))
                            .put("catalogs", catalogs);
                } else if (path.startsWith("/catalog/")) {
                    String[] parts = path.substring(0, path.length() - 5).split("/");
                    Map<String, String> extra = new java.util.HashMap<>();
                    if (parts.length > 4) for (String pair : parts[4].split("&")) {
                        String[] value = pair.split("=", 2); extra.put(URLDecoder.decode(value[0], "UTF-8"), value.length == 1 ? "" : URLDecoder.decode(value[1], "UTF-8"));
                    }
                    String genre = extra.getOrDefault("genre", ""); int skip = Integer.parseInt(extra.getOrDefault("skip", "0"));
                    int number = 0;
                    if (extra.containsKey("search")) searchRequests.incrementAndGet();
                    else if (!extra.containsKey("skip")) {
                        homeRequests.incrementAndGet();
                        if (homeGate != null && (homeGateCatalog.isEmpty() || homeGateCatalog.equals(parts[3]))) homeGate.await(15, TimeUnit.SECONDS);
                    }
                    else number = requests.computeIfAbsent(genre + ":" + skip, key -> new AtomicInteger()).incrementAndGet();
                    if (catalogueDelayMs > 0) Thread.sleep(catalogueDelayMs);
                    if (unavailable || genre.equals("Offline") || genre.equals("Flaky") && number < 3) status = failureStatus;
                    if (genre.equals("Denied")) status = 401;
                    if (genre.equals("Flaky") && number == 3) flakyThird.await(15, TimeUnit.SECONDS);
                    JSONArray titles = new JSONArray();
                    int count = extra.containsKey("search") ? 96 : 24;
                    if (!genre.equals("Empty") && !(emptySearch && extra.containsKey("search")) && status == 200) for (int i = skip; i < skip + count; i++) titles.put(title(i));
                    result.put(genre.equals("Bad data") ? "invalid" : "metas", titles);
                } else if (path.startsWith("/meta/")) {
                    String id = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1, path.length() - 5), "UTF-8");
                    result.put("meta", title(Integer.parseInt(id.substring(id.lastIndexOf(':') + 1))));
                } else result.put("streams", new JSONArray());
                byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
                connection.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().write(bytes); connection.getOutputStream().flush();
            } catch (Exception ignored) { /* Cancellation closes the real client socket. */ }
        }
        @Override public void close() throws Exception { flakyThird.countDown(); server.close(); workers.shutdownNow(); }
    }
}
