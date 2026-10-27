package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tv.stream.app.addon.Cancellation;
import tv.stream.app.ui.PageAdapter;

public final class SearchModesTest extends AppHarness {
    @Test public void keyboardReflowFadesAsOneGridWithoutCardsCrossingRows() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            launch(); waitUntil("Home", 10000, () -> visible("Film 00"));
            tap("Search", true); browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("results", 10000, () -> visible("Film 00") && columns() == 4);
            SystemClock.sleep(600);
            for (boolean show : new boolean[]{false, true}) {
                AtomicBoolean fadedOut = new AtomicBoolean(), fadedIn = new AtomicBoolean(), crossed = new AtomicBoolean();
                AtomicBoolean reflowVisible = new AtomicBoolean(), keyboardMoved = new AtomicBoolean(), keyboardOvershot = new AtomicBoolean();
                AtomicBoolean sharedMotion = new AtomicBoolean(), easedTail = new AtomicBoolean();
                long started = SystemClock.uptimeMillis();
                int initialColumns = columns();
                int[] previousColumns = {initialColumns};
                AtomicReference<android.view.ViewTreeObserver.OnDrawListener> observer = new AtomicReference<>();
                browse.onActivity(activity -> {
                    View rows = activity.findViewById(R.id.page_rows);
                    View results = rows.findViewWithTag("search-results"), keyboard = rows.findViewWithTag("search-keyboard");
                    observer.set(() -> {
                        RecyclerView grid = grid(rows);
                        int columns = ((GridLayoutManager) grid.getLayoutManager()).getSpanCount();
                        float alpha = results.getAlpha();
                        if (alpha > 0 && alpha < .95f) {
                            if (columns == initialColumns) fadedOut.set(true); else fadedIn.set(true);
                        }
                        if (columns != previousColumns[0] && alpha > .01f) reflowVisible.set(true);
                        previousColumns[0] = columns;
                        if (alpha > .01f) for (int i = 0; i < grid.getChildCount(); i++) {
                            Rect card = cardBounds(grid.getChildAt(i));
                            for (int j = i + 1; j < grid.getChildCount(); j++)
                                if (Rect.intersects(card, cardBounds(grid.getChildAt(j)))) crossed.set(true);
                        }
                        if (Math.abs(keyboard.getTranslationX()) > dp(2)) keyboardMoved.set(true);
                        if (Math.abs(keyboard.getTranslationX()) > keyboard.getWidth() + dp(50)) keyboardOvershot.set(true);
                        if (keyboard.isShown() && keyboard.getAlpha() > .02f && keyboard.getAlpha() < .98f) {
                            if (columns == initialColumns && alpha > .1f && alpha < .95f) sharedMotion.set(true);
                            long elapsed = SystemClock.uptimeMillis() - started;
                            if (elapsed >= 180 && elapsed <= 340 && Math.abs(keyboard.getTranslationX()) > dp(2)) easedTail.set(true);
                        }
                    });
                    rows.getViewTreeObserver().addOnDrawListener(observer.get());
                    ((PageAdapter) ((RecyclerView) rows).getAdapter()).toggleSearchKeyboard();
                });
                SystemClock.sleep(200); capture("validation-search-reflow-" + show + ".png");
                waitUntil("reflow finishes", 5000, () -> columns() == (show ? 4 : 6) && keyboardShown() == show
                        && (show ? focus().startsWith("keyboard:") : focus().startsWith("search:[")));
                browse.onActivity(activity -> {
                    View rows = activity.findViewById(R.id.page_rows);
                    rows.getViewTreeObserver().removeOnDrawListener(observer.get());
                    assertEquals(1f, rows.findViewWithTag("search-results").getAlpha(), 0f);
                    assertEquals(0f, rows.findViewWithTag("search-keyboard").getTranslationX(), 0f);
                    assertTrue("Results stay inside their scrolling viewport",
                            ((ViewGroup) grid(rows).getParent()).getClipChildren());
                    assertEquals(0f, activity.findViewById(R.id.scroll_fade).getAlpha(), 0f);
                });
                assertSearchNavigationHidden();
                assertTrue("The outgoing grid fades", fadedOut.get());
                assertTrue("The incoming grid fades", fadedIn.get());
                assertFalse("Column changes happen while the grid is invisible", reflowVisible.get());
                assertFalse("Visible films never cross over one another", crossed.get());
                assertTrue("Keyboard slides with the reflow", keyboardMoved.get());
                assertTrue("Keyboard motion starts with the outgoing grid fade", sharedMotion.get());
                assertTrue("Keyboard motion eases through a longer fading tail", easedTail.get());
                assertFalse("Keyboard moves only through its own space", keyboardOvershot.get());
            }
            // Reverse before the layout changes, then reverse during the incoming keyboard slide.
            for (long delay : new long[]{60, 200}) {
                browse.onActivity(activity -> ((PageAdapter) ((RecyclerView) activity.findViewById(R.id.page_rows)).getAdapter()).toggleSearchKeyboard());
                SystemClock.sleep(delay);
                browse.onActivity(activity -> {
                    RecyclerView rows = activity.findViewById(R.id.page_rows);
                    View keyboard = rows.findViewWithTag("search-keyboard");
                    float x = keyboard.getTranslationX(), alpha = keyboard.getAlpha();
                    ((PageAdapter) rows.getAdapter()).toggleSearchKeyboard();
                    assertEquals("A reversal keeps the painted keyboard position", x, keyboard.getTranslationX(), .001f);
                    assertEquals("A reversal keeps the painted keyboard opacity", alpha, keyboard.getAlpha(), .001f);
                });
                waitUntil("reversed reflow", 5000, () -> keyboardShown() && columns() == 4 && focus().startsWith("keyboard:"));
            }
            assertEquals(1, server.searchRequests.get());
        }
    }
    @Test public void leftStaysInTheKeyboardAndSearchNeverRevealsNavigation() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        tap("Search", true); waitUntil("keyboard", 5000, this::keyboardShown);
        browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("keyboard:q").requestFocus());
        SystemClock.sleep(250); capture("validation-search-popup-keyboard.png");
        for (String[] pair : new String[][]{{"r", "q"}, {"f", "e"}, {"b", "a"}, {"l", "k"}, {"x", "w"},
                {"Space", "z"}, {"⌫", "Space"}, {"Clear", "àéü"}}) {
            browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("keyboard:" + pair[0]).requestFocus());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            waitUntil("Left reaches " + pair[1], 3000, () -> focus().equals("keyboard:" + pair[1]));
            browse.onActivity(activity -> assertEquals(tv.stream.app.ui.NavigationRail.State.HIDDEN,
                    ((tv.stream.app.ui.NavigationRail) activity.findViewById(R.id.top_bar)).state()));
        }
        for (String leading : new String[]{"a", "g", "m", "s", "y", "123", "Language"}) {
            browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("keyboard:" + leading).requestFocus());
            for (int repeat = 0; repeat < 2; repeat++) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            assertEquals("Left stops at the leading key", "keyboard:" + leading, focus());
            assertSearchNavigationHidden();
        }
        for (boolean mouse : new boolean[]{true, false}) {
            pointer(dp(8), context.getResources().getDisplayMetrics().heightPixels / 2f, mouse);
            assertSearchNavigationHidden();
        }
        browse.onActivity(activity -> activity.findViewById(R.id.page_back).requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("Left from Back stays on Back", "nav:back", focus());
        assertSearchNavigationHidden();
        capture("validation-search-navigation-hidden.png");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("Back leaves Search", 5000, () -> visible("Make yourself at home") && !keyboardShown());
        browse.onActivity(activity -> assertEquals(tv.stream.app.ui.NavigationRail.State.WRAPPED,
                ((tv.stream.app.ui.NavigationRail) activity.findViewById(R.id.top_bar)).state()));
    }
    private void assertSearchNavigationHidden() {
        browse.onActivity(activity -> {
            tv.stream.app.ui.NavigationRail rail = activity.findViewById(R.id.top_bar);
            assertEquals(tv.stream.app.ui.NavigationRail.State.HIDDEN, rail.state());
            assertFalse("Search never shows navigation", rail.isShown());
            assertFalse("No pointer or touch reveal target", activity.findViewById(R.id.navigation_reveal).isShown());
            assertFalse(activity.findViewById(R.id.navigation_dismiss).isShown());
            assertFalse(rail.hasFocus());
        });
    }
    private Rect cardBounds(View view) {
        assertNotNull(view);
        int[] point = new int[2]; view.getLocationOnScreen(point);
        return new Rect(point[0], point[1], point[0] + view.getWidth(), point[1] + view.getHeight());
    }
    @Test public void recreatingDuringEitherPhaseRestoresAVisibleGridAndItsSelectedFilm() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            launch(); waitUntil("Home", 10000, () -> visible("Film 00"));
            tap("Search", true); browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("results", 10000, () -> visible("Film 00") && columns() == 4);
            String target = "search:[\"" + services.store.addons().get(0).id + "\",\"movie\",\"browse:36\"]";
            browse.onActivity(activity -> grid(activity.findViewById(R.id.page_rows)).scrollToPosition(36));
            waitUntil("later film", 5000, () -> has(target));
            browse.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.page_rows).findViewWithTag(target).requestFocus());
                assertEquals(target, activity.getCurrentFocus().getTag());
                ((PageAdapter) ((RecyclerView) activity.findViewById(R.id.page_rows)).getAdapter()).toggleSearchKeyboard();
            });
            SystemClock.sleep(60); browse.recreate();
            waitUntil("full search restores after the outgoing fade", 10000,
                    () -> !keyboardShown() && columns() == 6 && focus().equals(target));
            browse.onActivity(activity -> ((PageAdapter) ((RecyclerView) activity.findViewById(R.id.page_rows)).getAdapter()).toggleSearchKeyboard());
            SystemClock.sleep(200); browse.recreate();
            waitUntil("typing restores during the incoming slide", 10000,
                    () -> keyboardShown() && columns() == 4 && focus().startsWith("keyboard:"));
            browse.onActivity(activity -> assertEquals(1f,
                    activity.findViewById(R.id.page_rows).findViewWithTag("search-results").getAlpha(), 0f));
        }
    }
    @Test public void emptyFullSearchStillReturnsToTheKeyboardWithLeft() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        tap("Search", true); waitUntil("keyboard", 5000, this::keyboardShown);
        browse.onActivity(activity -> ((PageAdapter) ((RecyclerView) activity.findViewById(R.id.page_rows)).getAdapter()).toggleSearchKeyboard());
        waitUntil("empty results get the full page", 5000, () -> !keyboardShown() && focus().equals("search-results"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("Left returns to typing even without results", 5000, () -> keyboardShown() && focus().startsWith("keyboard:"));
    }
    @Test public void theQueryHeadsTheKeyboardAndFullSearchIsOnlyResults() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            launch(); waitUntil("Home", 10000, () -> visible("Film 00"));
            tap("Search", true); waitUntil("keyboard", 5000, this::keyboardShown);
            assertEquals("Search", query());
            for (String control : new String[]{"Close search", "Hide keyboard", "Show keyboard"}) assertFalse(control, visible(control));
            tap("Type f", false); tap("Type i", false);
            waitUntil("typed text shows before results", 2000, () -> query().equals("fi"));
            waitUntil("results", 10000, () -> visible("Film 00") && columns() == 4);
            browse.onActivity(activity -> {
                View rows = activity.findViewById(R.id.page_rows);
                Rect keys = cardBounds(rows.findViewWithTag("keyboard-keys")), text = cardBounds(activity.findViewById(R.id.search_query));
                assertEquals("The query lines up with the keys", keys.left, text.left, 1);
                assertTrue("The query sits just above the keys", text.bottom <= keys.top && keys.top - text.bottom <= dp(24));
                assertFalse("The query is text, not a control", activity.findViewById(R.id.search_query).isFocusable());
            });
            screenshot("validation-search-query-keyboard.png");
            browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("keyboard:f").requestFocus());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitUntil("results take the page", 5000, () -> !keyboardShown() && columns() == 6 && focus().startsWith("search:["));
            browse.onActivity(activity -> {
                assertFalse("The query leaves with the keyboard", activity.findViewById(R.id.search_query).isShown());
                assertTrue("Results start at the page's leading edge", cardBounds(grid(activity.findViewById(R.id.page_rows))).left < dp(120));
            });
            screenshot("validation-search-query-results.png");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            waitUntil("Left brings the keyboard and query back", 5000, () -> keyboardShown() && query().equals("fi"));
        }
    }
    private String query() {
        AtomicReference<String> result = new AtomicReference<>("");
        browse.onActivity(activity -> result.set(((android.widget.TextView) activity.findViewById(R.id.search_query)).getText().toString()));
        return result.get();
    }
    @Test public void remoteAndPointerSwitchModesAnimateAndPreserveTheQueryAndSelectedFilm() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            launch(); waitUntil("Home", 10000, () -> visible("Film 00"));
            tap("Search", true); browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("split search results", 10000, () -> visible("Film 00") && columns() == 4);
            SystemClock.sleep(350); // All provider callbacks finish before changing the retained view mode.
            Rect split = resultsBounds();
            browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("keyboard:f").requestFocus());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitUntil("results expand after Right", 5000, () -> columns() == 6 && !keyboardShown() && focus().startsWith("search:["));
            assertTrue("Grid uses the keyboard's former width", resultsBounds().width() > split.width() + dp(180));
            String target = "search:[\"" + services.store.addons().get(0).id + "\",\"movie\",\"browse:36\"]";
            browse.onActivity(activity -> grid(activity.findViewById(R.id.page_rows)).scrollToPosition(36));
            waitUntil("later result bound", 5000, () -> has(target));
            browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag(target).requestFocus());
            screenshot("validation-search-full.png");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            waitUntil("Left reveals keyboard", 5000, () -> keyboardShown() && columns() == 4 && focus().equals("keyboard:f"));
            assertEquals(split.width(), resultsBounds().width(), dp(2));
            assertTrue(visible("Film")); screenshot("validation-search-keyboard.png");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitUntil("Right returns to the same result", 5000, () -> !keyboardShown() && columns() == 6 && focus().equals(target));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            waitUntil("details", 10000, () -> visible("Play")); tap("Back", false);
            waitUntil("Back restores full search and its selected film", 5000, () -> columns() == 6 && !keyboardShown() && focus().equals(target));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            waitUntil("Left brings the keyboard back", 5000, () -> columns() == 4 && keyboardShown() && focus().startsWith("keyboard:"));
            // A pointer has no way to bring the keyboard back, so tapping a result keeps it while the film opens.
            tap("Film 36", true);
            waitUntil("pointer opens the film", 10000, () -> visible("Play")); tap("Back", true);
            waitUntil("the keyboard stayed", 5000, () -> columns() == 4 && keyboardShown());
            assertEquals("Mode switches and Back never repeat the search", 1, server.searchRequests.get());
        }
    }
    private int columns() {
        AtomicReference<Integer> result = new AtomicReference<>(0);
        browse.onActivity(activity -> { RecyclerView grid = grid(activity.findViewById(R.id.page_rows)); if (grid != null) result.set(((GridLayoutManager) grid.getLayoutManager()).getSpanCount()); }); return result.get();
    }
    private RecyclerView grid(View view) {
        if (view instanceof RecyclerView && ((RecyclerView) view).getLayoutManager() instanceof GridLayoutManager) return (RecyclerView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { RecyclerView child = grid(((ViewGroup) view).getChildAt(i)); if (child != null) return child; }
        return null;
    }
    private boolean keyboardShown() { return has("search-keyboard") && shown("search-keyboard"); }
    private boolean has(String key) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        browse.onActivity(activity -> result.set(activity.findViewById(R.id.page_rows).findViewWithTag(key) != null)); return result.get();
    }
    private boolean shown(String key) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        browse.onActivity(activity -> { View view = activity.findViewById(R.id.page_rows).findViewWithTag(key); result.set(view != null && view.isShown()); }); return result.get();
    }
    private Rect resultsBounds() {
        Rect result = new Rect(); browse.onActivity(activity -> assertTrue(activity.findViewById(R.id.page_rows).findViewWithTag("search-results").getGlobalVisibleRect(result))); return result;
    }
    private String focus() {
        AtomicReference<String> result = new AtomicReference<>(""); browse.onActivity(activity -> { if (activity.getCurrentFocus() != null) result.set(String.valueOf(activity.getCurrentFocus().getTag())); }); return result.get();
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void screenshot(String name) throws Exception {
        SystemClock.sleep(350); capture(name);
    }
    private void capture(String name) throws Exception {
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } image.recycle();
    }
}
