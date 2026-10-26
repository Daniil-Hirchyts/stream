package tv.daniil.stream;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.ui.PageAdapter;

public final class SearchModesTest extends AppHarness {
    @Test public void cardsMorphThroughTheReflowWithoutRecreatingTheirContents() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            launch(); waitUntil("Home", 10000, () -> visible("Film 00"));
            tap("Search", true); browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("results", 10000, () -> visible("Film 00") && columns() == 5);
            SystemClock.sleep(600);
            String key = "search:[\"" + services.store.addons().get(0).id + "\",\"movie\",\"browse:5\"]";
            for (boolean show : new boolean[]{false, true}) {
                List<Rect> frames = new ArrayList<>();
                AtomicReference<View> original = new AtomicReference<>();
                AtomicReference<Rect> before = new AtomicReference<>(), after = new AtomicReference<>();
                AtomicBoolean keyboardMoved = new AtomicBoolean();
                AtomicBoolean keyboardOvershot = new AtomicBoolean();
                AtomicReference<android.view.ViewTreeObserver.OnDrawListener> observer = new AtomicReference<>();
                browse.onActivity(activity -> {
                    View rows = activity.findViewById(R.id.page_rows);
                    original.set(rows.findViewWithTag(key)); before.set(cardBounds(original.get()));
                    View keyboard = rows.findViewWithTag("search-keyboard");
                    observer.set(() -> {
                        View card = rows.findViewWithTag(key);
                        if (card != null) frames.add(cardBounds(card));
                        if (Math.abs(keyboard.getTranslationX()) > dp(2)) keyboardMoved.set(true);
                        if (Math.abs(keyboard.getTranslationX()) > keyboard.getWidth() + dp(50)) keyboardOvershot.set(true);
                    });
                    rows.getViewTreeObserver().addOnDrawListener(observer.get());
                    ((PageAdapter) ((RecyclerView) rows).getAdapter()).toggleSearchKeyboard();
                });
                SystemClock.sleep(140); capture("validation-search-morph-" + show + ".png");
                waitUntil("morph finishes", 5000, () -> columns() == (show ? 5 : 6) && keyboardShown() == show
                        && (show ? focus().startsWith("keyboard:") : focus().startsWith("search:[")));
                browse.onActivity(activity -> {
                    View rows = activity.findViewById(R.id.page_rows);
                    rows.getViewTreeObserver().removeOnDrawListener(observer.get());
                    View card = rows.findViewWithTag(key);
                    assertSame("The same film view survives the column change", original.get(), card);
                    after.set(cardBounds(card));
                });
                assertTrue("Posters resize across multiple frames", frames.stream().map(Rect::width).distinct().count() > 3);
                assertTrue("A film moves between rows rather than teleporting", frames.stream().anyMatch(rect ->
                        rect.top > Math.min(before.get().top, after.get().top) + dp(2)
                        && rect.top < Math.max(before.get().top, after.get().top) - dp(2)));
                assertTrue("Keyboard slides with the reflow", keyboardMoved.get());
                assertFalse("Keyboard moves only through its own space", keyboardOvershot.get());
            }
            // Reverse an in-flight gesture; no stale completion may hide the reopened keyboard or take focus.
            browse.onActivity(activity -> ((PageAdapter) ((RecyclerView) activity.findViewById(R.id.page_rows)).getAdapter()).toggleSearchKeyboard());
            SystemClock.sleep(100);
            browse.onActivity(activity -> ((PageAdapter) ((RecyclerView) activity.findViewById(R.id.page_rows)).getAdapter()).toggleSearchKeyboard());
            waitUntil("reversed morph", 5000, () -> keyboardShown() && columns() == 5 && focus().startsWith("keyboard:"));
            assertEquals(1, server.searchRequests.get());
        }
    }
    private Rect cardBounds(View view) {
        assertNotNull(view);
        int[] point = new int[2]; view.getLocationOnScreen(point);
        return new Rect(point[0], point[1], point[0] + view.getWidth(), point[1] + view.getHeight());
    }
    @Test public void emptyFullSearchStillReturnsToTheKeyboardWithLeft() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        tap("Search", true); waitUntil("keyboard", 5000, this::keyboardShown);
        tap("Hide keyboard", false);
        waitUntil("empty results get the full page", 5000, () -> !keyboardShown() && focus().equals("search-results"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("Left returns to typing even without results", 5000, () -> keyboardShown() && focus().startsWith("keyboard:"));
    }
    @Test public void remoteAndPointerSwitchModesAnimateAndPreserveTheQueryAndSelectedFilm() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            launch(); waitUntil("Home", 10000, () -> visible("Film 00"));
            tap("Search", true); browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("split search results", 10000, () -> visible("Film 00") && columns() == 5);
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
            waitUntil("Left reveals keyboard", 5000, () -> keyboardShown() && columns() == 5 && focus().equals("keyboard:f"));
            assertEquals(split.width(), resultsBounds().width(), dp(2));
            assertTrue(visible("Film")); screenshot("validation-search-keyboard.png");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitUntil("Right returns to the same result", 5000, () -> !keyboardShown() && columns() == 6 && focus().equals(target));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            waitUntil("details", 10000, () -> visible("Play")); tap("Back", false);
            waitUntil("Back restores full search and its selected film", 5000, () -> columns() == 6 && !keyboardShown() && focus().equals(target));
            tap("Show keyboard", true);
            waitUntil("pointer reveals keyboard", 5000, () -> columns() == 5 && keyboardShown() && focus().startsWith("keyboard:"));
            tap("Hide keyboard", false);
            waitUntil("touch returns to full results", 5000, () -> columns() == 6 && !keyboardShown() && focus().equals(target));
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
