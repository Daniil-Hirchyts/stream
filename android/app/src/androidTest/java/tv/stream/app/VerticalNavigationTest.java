package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import tv.stream.app.addon.Title;
import tv.stream.app.ui.NavigationRail;
import tv.stream.app.ui.Tv;

/** Real input and painted-frame checks: navigation overlays a fixed page; visibility follows page composition. */
public final class VerticalNavigationTest extends AppHarness {
    @Test public void railMorphsThroughHiddenWrappedAndExpandedStates() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        AtomicReference<View> card = new AtomicReference<>();
        browse.onActivity(a -> card.set(a.getCurrentFocus()));
        collapsed("01-home");
        AtomicBoolean paintedMorph = new AtomicBoolean(), movedPage = new AtomicBoolean();
        AtomicReference<android.view.ViewTreeObserver.OnDrawListener> observer = new AtomicReference<>();
        browse.onActivity(a -> {
            View rail = a.findViewById(R.id.top_bar);
            View content = a.findViewById(R.id.browse_content);
            Rect viewport = bounds(content), poster = bounds(card.get());
            observer.set(() -> {
                if (rail.getWidth() > dp(NavigationRail.COLLAPSED) && rail.getWidth() < dp(NavigationRail.EXPANDED)) paintedMorph.set(true);
                if (!viewport.equals(bounds(content)) || !poster.equals(bounds(card.get()))) movedPage.set(true);
            });
            rail.getViewTreeObserver().addOnDrawListener(observer.get());
        });
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        wrappedFocus("nav:Home", "01-home-rail-focused");
        assertFalse("Moving focus onto navigation never starts the width morph", paintedMorph.get());
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Home", "02-expanded");
        assertTrue("The morph paints intermediate widths", paintedMorph.get());
        assertFalse("No painted frame moves or resizes the page or its poster", movedPage.get());
        browse.onActivity(a -> {
            ViewGroup rail = a.findViewById(R.id.top_bar);
            assertTrue("Compact expansion avoids the former 188 dp empty tail", rail.getWidth() < dp(188));
            assertEquals("Navigation is vertically centred", a.findViewById(R.id.browse_root).getHeight() / 2,
                    (a.findViewById(R.id.top_bar_controls).getTop() + a.findViewById(R.id.top_bar_controls).getBottom()) / 2 + rail.getTop(), 2);
            assertCaptions(rail);
            View content = a.findViewById(R.id.browse_content);
            assertEquals("Content keeps its wrapped inset", dp(NavigationRail.CONTENT_START), a.contentStart());
            assertEquals("The page runs under the rail to the screen edge", 0, content.getLeft());
            assertEquals("Shelves fade out under the rail", dp(NavigationRail.CONTENT_START), content.getHorizontalFadingEdgeLength());
            assertTrue(a.findViewById(R.id.navigation_dismiss).isShown());
        });
        // The frame observer only covers this first open/close, not subsequent page navigation.
        key(KeyEvent.KEYCODE_DPAD_RIGHT); collapsed("03-returned");
        browse.onActivity(a -> a.findViewById(R.id.top_bar).getViewTreeObserver().removeOnDrawListener(observer.get()));
        assertFalse("Closing the popup leaves the page still", movedPage.get());
        browse.onActivity(a -> assertSame("Right restores the same attached card", card.get(), a.getCurrentFocus()));
        // Rapid direction changes reverse an in-flight morph cleanly.
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_LEFT);
        expanded("nav:Home", "04-reversed");
        key(KeyEvent.KEYCODE_DPAD_DOWN); assertFocus("nav:Browse");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitUntil("Primary Browse", 5000, () -> visible("Generated films"));
        browse.onActivity(a -> assertFalse("Browse has no Back button", a.findViewById(R.id.page_back).isShown()));
        key(KeyEvent.KEYCODE_DPAD_RIGHT); collapsed("04-browse-wrapped");
        tap("Generated films", false); waitUntil("Catalogue", 5000, () -> visible("Generated Film"));
        waitUntil("Catalogue keeps wrapped navigation", 5000, () -> railState() == NavigationRail.State.WRAPPED);
        shot("04-catalogue-wrapped");
        AtomicReference<View> catalogueFocus = new AtomicReference<>();
        browse.onActivity(a -> catalogueFocus.set(a.getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Browse", "04-catalogue-expanded");
        key(KeyEvent.KEYCODE_BACK); collapsed("04-catalogue-wrapped-returned");
        browse.onActivity(a -> assertSame("Back dismisses navigation and restores the exact catalogue control", catalogueFocus.get(), a.getCurrentFocus()));
        browse.onActivity(a -> a.findViewById(R.id.page_rows).findViewWithTag("filter:genre:").requestFocus());
        key(KeyEvent.KEYCODE_DPAD_UP);
        browse.onActivity(a -> assertEquals("Up reaches Back, independent of rail state", R.id.page_back, a.getCurrentFocus().getId()));

    }

    @Test public void railRowsGrowUnderFocusAndThePointerLikeOtherControls() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        AtomicReference<Object> focus = new AtomicReference<>();
        browse.onActivity(a -> focus.set(a.getCurrentFocus() == null ? null : a.getCurrentFocus().getTag()));
        Rect library = new Rect();
        browse.onActivity(a -> assertTrue(a.findViewById(R.id.top_bar).findViewWithTag("nav:Library").getGlobalVisibleRect(library)));
        hover(library.centerX(), library.centerY());
        waitUntil("the row under the pointer grows like a focused round control", 2000,
                () -> hovered("nav:Library") && Math.abs(rowScale("nav:Library") - Tv.LIFT_ICON) < .01f);
        shot("60-rail-hover");
        browse.onActivity(a -> assertEquals("Hover never moves focus", focus.get(), a.getCurrentFocus() == null ? null : a.getCurrentFocus().getTag()));
        assertEquals("Hover never unwraps the rail", NavigationRail.State.WRAPPED, railState());
        hover(library.centerX(), library.top - dp(6) - library.height() / 2f);
        waitUntil("the highlight follows the pointer", 2000, () -> hovered("nav:Browse") && !hovered("nav:Library")
                && Math.abs(rowScale("nav:Library") - 1f) < .01f);
        hover(dp(700), dp(300));
        waitUntil("the rail settles when the pointer leaves", 2000, () -> !hovered("nav:Browse") && Math.abs(rowScale("nav:Browse") - 1f) < .01f);
        key(KeyEvent.KEYCODE_DPAD_LEFT); wrappedFocus("nav:Home", "61-rail-focus-wrapped");
        waitUntil("focus grows the wrapped circle", 2000, () -> Math.abs(rowScale("nav:Home") - Tv.LIFT_ICON) < .01f);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("focus travels: the next row grows, the last settles", 2000, () -> Math.abs(rowScale("nav:Browse") - Tv.LIFT_ICON) < .01f
                && Math.abs(rowScale("nav:Home") - 1f) < .01f);
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Browse", "62-rail-focus-expanded");
        waitUntil("labelled rows grow like buttons, inside the popup", 2000, () -> Math.abs(rowScale("nav:Browse") - Tv.LIFT_BUTTON) < .01f);
        browse.onActivity(a -> {
            Rect row = new Rect(), popup = new Rect();
            View focused = a.findViewById(R.id.top_bar).findViewWithTag("nav:Browse");
            focused.getGlobalVisibleRect(row); a.findViewById(R.id.top_bar_controls).getGlobalVisibleRect(popup);
            float grownWidth = focused.getWidth() * focused.getScaleX();
            assertTrue("The grown row stays inside the popup", grownWidth <= popup.width());
        });
    }
    private void hover(float x, float y) {
        android.view.MotionEvent.PointerProperties p = new android.view.MotionEvent.PointerProperties(); p.id = 0; p.toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE;
        android.view.MotionEvent.PointerCoords c = new android.view.MotionEvent.PointerCoords(); c.x = x; c.y = y;
        long time = SystemClock.uptimeMillis();
        android.view.MotionEvent e = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_HOVER_MOVE, 1, new android.view.MotionEvent.PointerProperties[]{p},
                new android.view.MotionEvent.PointerCoords[]{c}, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0);
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(e, true)); e.recycle();
        instrumentation.waitForIdleSync();
    }
    private boolean hovered(String tag) {
        AtomicBoolean yes = new AtomicBoolean(); browse.onActivity(a -> yes.set(a.findViewById(R.id.top_bar).findViewWithTag(tag).isHovered())); return yes.get();
    }
    private float rowScale(String tag) {
        AtomicReference<Float> scale = new AtomicReference<>(0f);
        browse.onActivity(a -> scale.set(a.findViewById(R.id.top_bar).findViewWithTag(tag).getScaleX())); return scale.get();
    }
    @Test public void compactNavigationGrowsForLargerLabelsWithoutShiftingThePage() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        AtomicReference<Rect> content = new AtomicReference<>();
        browse.onActivity(a -> {
            content.set(bounds(a.findViewById(R.id.browse_content)));
            ViewGroup rows = a.findViewById(R.id.tab_cluster);
            enlargeLabels(rows);
        });
        key(KeyEvent.KEYCODE_DPAD_LEFT); wrappedFocus("nav:Home", "56-larger-labels-wrapped");
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("Larger text fits expanded rail", 5000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            browse.onActivity(a -> {
                NavigationRail rail = a.findViewById(R.id.top_bar);
                // The width reaches whole pixels a frame before the labels finish fading in.
                ready.set(rail.state() == NavigationRail.State.EXPANDED && rail.getWidth() == dp(rail.expandedWidth()) && labelsShown(rail));
            });
            return ready.get();
        });
        browse.onActivity(a -> {
            NavigationRail rail = a.findViewById(R.id.top_bar);
            assertTrue("Extra width is earned by the larger labels", rail.expandedWidth() > NavigationRail.EXPANDED);
            assertCaptions(rail);
            assertEquals("Larger navigation still overlays the same content", content.get(), bounds(a.findViewById(R.id.browse_content)));
        });
        key(KeyEvent.KEYCODE_DPAD_RIGHT); collapsed("57-larger-labels-returned");
    }

    @Test public void hiddenPagesKeepRemoteAndPointerNavigationInsideContent() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        tap("Generated Film", true); waitUntil("Details", 10000, () -> visible("Play"));
        browse.onActivity(a -> a.findViewById(R.id.page_rows).findViewWithTag("play").requestFocus());
        waitUntil("Details Play focused", 5000, () -> focused("play"));
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("play"); assertNavigationHidden();
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertFocus("sources");
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("play");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("Sources page", 10000, () -> visible("Size"));
        assertNavigationHidden();
        browse.onActivity(a -> a.findViewById(R.id.page_rows).findViewWithTag("sort:BEST").requestFocus());
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("sort:BEST");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertFocus("sort:QUALITY");
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("sort:BEST");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("Down reaches a source", 5000, () -> {
            AtomicBoolean source = new AtomicBoolean();
            browse.onActivity(a -> source.set(a.getCurrentFocus() != null && String.valueOf(a.getCurrentFocus().getTag()).startsWith("source:")));
            return source.get();
        });
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertNavigationHidden();
        hover(dp(2), dp(300)); assertNavigationHidden();
        browse.onActivity(a -> assertNotNull("Sources retains a focused control", a.getCurrentFocus()));
        key(KeyEvent.KEYCODE_BACK); waitUntil("Back returns to Details", 10000, () -> visible("Play"));
        assertNavigationHidden();
        key(KeyEvent.KEYCODE_BACK); waitUntil("Back returns to Home", 10000, () -> railState() == NavigationRail.State.WRAPPED && visible("Generated Film"));
        key(KeyEvent.KEYCODE_DPAD_LEFT); wrappedFocus("nav:Home", "hidden-pages-return-to-home");
    }

    private void assertNavigationHidden() {
        browse.onActivity(a -> {
            assertEquals(NavigationRail.State.HIDDEN, ((NavigationRail) a.findViewById(R.id.top_bar)).state());
            assertFalse(a.findViewById(R.id.top_bar).isShown());
            assertFalse(a.findViewById(R.id.navigation_reveal).isShown());
            assertFalse(a.findViewById(R.id.navigation_dismiss).isShown());
            assertFalse(a.findViewById(R.id.top_bar).hasFocus());
        });
    }

    private void enlargeLabels(View view) {
        if (view instanceof TextView) ((TextView) view).setTextSize(22);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) enlargeLabels(((ViewGroup) view).getChildAt(i));
    }

    @Test public void wrappedFocusAndHeldLeftNeverUnwrapUntilASeparateLeftPress() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        // Focus can arrive from a cursor/pointer as well as from the remote.
        browse.onActivity(a -> a.findViewById(R.id.navigation).findViewWithTag("nav:Home").requestFocus());
        wrappedFocus("nav:Home", "47-wrapped-pointer-focus");
        key(KeyEvent.KEYCODE_DPAD_UP); wrappedFocus("nav:search", "48-wrapped-search-focus");
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN);
        wrappedFocus("nav:Browse", "49-wrapped-browse-focus");
        browse.onActivity(a -> {
            long now = SystemClock.uptimeMillis();
            a.dispatchKeyEvent(new KeyEvent(now - 500, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 2));
            a.dispatchKeyEvent(new KeyEvent(now - 500, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0));
        });
        wrappedFocus("nav:Browse", "50-wrapped-held-left");
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Browse", "51-second-left-expanded");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        waitUntil("Right restores the film", 5000, () -> railState() == NavigationRail.State.WRAPPED && !focused("nav:Browse"));
        key(KeyEvent.KEYCODE_DPAD_LEFT); wrappedFocus("nav:Home", "52-next-entry-wrapped");
        key(KeyEvent.KEYCODE_BACK);
        waitUntil("Back from wrapped navigation restores content", 5000, () -> !focused("nav:Home") && visible("Generated Film"));
        tap("Generated Film", false); waitUntil("Details", 5000, () -> visible("Play"));
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertFocus("play"); assertNavigationHidden();
    }

    @Test public void settingsSearchAndDialogsRespectTheirOwnFocusBeforeEnteringRail() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        tap("Settings", true);
        waitUntil("Settings category", 5000, () -> focused("settings:addons")); collapsed("05-settings");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        browse.onActivity(a -> assertFalse("Right enters Settings pane", "settings:addons".equals(a.getCurrentFocus().getTag())));
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("settings:addons");
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Settings", "06-settings-rail");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertFocus("settings:addons");
        tap("Subtitles", false); tap("Subtitle color", true);
        waitUntil("Subtitle popup", 5000, () -> visible("White")); shot("07-popup");
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("Popup dismisses before rail navigation", 5000, () -> visible("Subtitle color"));
        browse.onActivity(a -> assertFalse(a.findViewById(R.id.top_bar).hasFocus()));
        tap("Search", true); waitUntil("Keyboard", 5000, () -> visible("Type a")); collapsed("08-search");
        browse.onActivity(a -> a.findViewById(R.id.page_rows).findViewWithTag("keyboard:a").requestFocus());
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("keyboard:a");
        browse.onActivity(a -> {
            assertEquals(NavigationRail.State.HIDDEN, ((NavigationRail) a.findViewById(R.id.top_bar)).state());
            assertFalse(a.findViewById(R.id.top_bar).isShown());
            assertFalse(a.findViewById(R.id.navigation_reveal).isShown());
        });
        shot("09-search-no-rail");
        browse.onActivity(a -> a.searchInput("Generated"));
        waitUntil("Search results", 10000, () -> visible("Generated Film")); collapsed("10-search-results");
        browse.onActivity(a -> a.findViewById(R.id.page_rows).findViewWithTag("keyboard:f").requestFocus());
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        browse.onActivity(a -> assertFalse("Right from keyboard reaches results", String.valueOf(a.getCurrentFocus().getTag()).startsWith("keyboard:")));
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        browse.onActivity(a -> assertFalse("Results first return to the keyboard", a.findViewById(R.id.top_bar).hasFocus()));
    }

    @Test public void backDismissesNavigationBeforeLeavingItsPageAndNeverTrapsTheViewer() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        tap("Generated Film", false); waitUntil("Details", 5000, () -> visible("Play"));
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertNavigationHidden();
        key(KeyEvent.KEYCODE_BACK);
        waitUntil("Back restores Home from Details", 5000, () -> visible("Generated Film") && !visible("Play"));
        tap("Settings", false); waitUntil("Settings", 5000, () -> visible("Add-ons"));
        key(KeyEvent.KEYCODE_BACK);
        waitUntil("Root-page Back returns Home", 5000, () -> visible("Generated Film") && !visible("Add-ons"));
        AtomicReference<MainActivity> owner = new AtomicReference<>(); browse.onActivity(owner::set);
        key(KeyEvent.KEYCODE_BACK);
        waitUntil("Back from Home exits", 5000, () -> owner.get().isFinishing() || owner.get().isDestroyed());
    }

    @Test public void scrollingKeepsWrappedNavigationAndDetailsKeepsItHidden() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film")); collapsed("40-home-wrapped");
        browse.onActivity(a -> ((androidx.recyclerview.widget.RecyclerView) a.findViewById(R.id.page_rows)).scrollBy(0, dp(280)));
        waitUntil("Scrolling preserves wrapped navigation", 5000, () -> railState() == NavigationRail.State.WRAPPED);
        SystemClock.sleep(300); shot("41-scrolled-wrapped");
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Home", "42-scrolled-overlay");
        key(KeyEvent.KEYCODE_BACK);
        browse.onActivity(a -> a.root("Home"));
        tap("Generated Film", true); waitUntil("Details", 5000, () -> visible("Play"));
        waitUntil("Details uses hidden navigation", 5000, () -> railState() == NavigationRail.State.HIDDEN);
        hover(dp(2), dp(300)); assertNavigationHidden();
        browse.onActivity(a -> {
            ViewGroup search = a.findViewById(R.id.search_button);
            assertEquals("Search always has its icon and label", 2, search.getChildCount());
            assertEquals("Search label is present", "Search", ((TextView) search.getChildAt(1)).getText().toString());
            ViewGroup back = a.findViewById(R.id.page_back), refresh = a.findViewById(R.id.home_refresh);
            assertEquals(1, back.getChildCount()); assertEquals(1, refresh.getChildCount());
        });
    }

    @Test public void outsideClicksDismissNavigationBeforeActivatingCardsOrActions() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Home", "43-expanded-before-card");
        tap("Close navigation", false);
        waitUntil("Outside tap wraps navigation", 5000, () -> railState() == NavigationRail.State.WRAPPED);
        assertFalse("The outside tap never opens the card beneath", visible("Play"));
        tap("Generated Film", false); waitUntil("Card click opens Details", 5000, () -> visible("Play"));
        tap("Add favourite", false); waitUntil("Saved film actions", 5000, () -> visible("More"));
        AtomicReference<Rect> before = new AtomicReference<>();
        browse.onActivity(a -> before.set(bounds(a.findViewById(R.id.browse_content))));
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertNavigationHidden();
        browse.onActivity(a -> assertEquals("Hidden navigation leaves content unchanged", before.get(), bounds(a.findViewById(R.id.browse_content))));
        assertFalse("More is not opened by Left", visible("Remove from library"));
        tap("More", true); waitUntil("Small action opens its menu", 5000, () -> visible("Remove from library")); shot("45-menu-after-expansion");
        assertEquals("Details navigation stays hidden behind its action menu", NavigationRail.State.HIDDEN, railState());
        key(KeyEvent.KEYCODE_BACK);
        waitUntil("Popup closes", 5000, () -> { AtomicBoolean focus = new AtomicBoolean(); browse.onActivity(a -> focus.set(a.hasWindowFocus())); return focus.get(); });
        waitUntil("Details returns to hidden navigation", 5000, () -> railState() == NavigationRail.State.HIDDEN);
    }

    @Test public void collapsedViewportCoversBrowseSubpagesAndEpisodeMenus() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        tap("Browse", false); waitUntil("Catalogues", 5000, () -> visible("Generated films")); collapsed("12-catalogues");
        tap("Generated films", true); waitUntil("Catalogue", 10000, () -> visible("Generated Film")); collapsed("13-catalogue");
        tap("Generated Film", false); waitUntil("Details", 10000, () -> visible("Play")); collapsed("14-details");
        tap("Add favourite", false); waitUntil("Saved film actions", 5000, () -> visible("More"));
        tap("More", true); waitUntil("Details menu", 5000, () -> visible("Remove from library")); shot("15-details-menu");
        key(KeyEvent.KEYCODE_BACK);
        tap("Sources", true); waitUntil("Sources", 10000, () -> visible("Size")); collapsed("16-sources");
        browse.onActivity(a -> a.root("Library")); waitUntil("Library", 5000, () -> visible("Generated Film")); collapsed("17-library");
        browse.onActivity(a -> a.root("Settings")); tap("Add-on store", false);
        waitUntil("Store", 10000, () -> visible("All")); collapsed("18-store");
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Settings", "18-store-rail");
        browse.onActivity(a -> assertCaptions(a.findViewById(R.id.top_bar)));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        browse.onActivity(MainActivity::openImports); waitUntil("Imports", 5000, () -> visible("Add magnet or torrent URL")); collapsed("19-imports");
        tap("Add magnet or torrent URL", true); waitUntil("Import dialog", 5000, () -> visible("Continue")); shot("20-import-dialog"); key(KeyEvent.KEYCODE_BACK);
        JSONArray episodes = new JSONArray();
        for (int i = 1; i <= 4; i++) episodes.put(new JSONObject().put("id", "rail:episode:" + i).put("season", 1).put("episode", i).put("title", "Episode " + i));
        Title series = Title.parse("local", new JSONObject().put("id", "rail:series").put("type", "series").put("name", "Rail series").put("videos", episodes));
        browse.onActivity(a -> a.openTitle(series, "")); waitUntil("Series", 5000, () -> visible("Play")); collapsed("21-series");
        key(KeyEvent.KEYCODE_DPAD_DOWN); waitUntil("Episodes", 5000, () -> visible("Season 1") && !visible("Play")); collapsed("22-episodes");
        tap("Episode 1", false); waitUntil("Episode menu", 5000, () -> visible("Choose source")); shot("23-episode-menu");
        key(KeyEvent.KEYCODE_BACK);
        waitUntil("Episode popup closed", 5000, () -> { AtomicBoolean focus = new AtomicBoolean(); browse.onActivity(a -> focus.set(a.hasWindowFocus())); return focus.get(); });
        key(KeyEvent.KEYCODE_BACK); waitUntil("Series overview", 5000, () -> visible("Play"));
    }

    @Test public void emptySecondaryAndFailurePagesAndClockFormatsFitTheRail() throws Exception {
        launch(); waitUntil("Empty Home", 10000, () -> visible("Make yourself at home")); collapsed("24-empty-home");
        browse.onActivity(a -> a.root("Library")); waitUntil("Empty Library", 5000, () -> visible("Your library is empty")); collapsed("25-empty-library");
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Library", "26-empty-library-rail");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); collapsed("27-empty-library-returned");
        readyLibrary(); browse.onActivity(a -> a.root("Settings")); waitUntil("Settings", 5000, () -> visible("Local fixtures"));
        tap("Local fixtures", true); waitUntil("Add-on management", 5000, () -> visible("Remove add-on")); collapsed("28-addon-management");
        browse.onActivity(a -> { Route review = new Route("Add-on review"); review.value = origin + "/addon/good/review/manifest.json"; a.push(review); });
        waitUntil("Add-on review", 10000, () -> visible("Manage add-on")); collapsed("29-addon-review");
        services.addons.remove(services.store.addons().get(0).id);
        browse.onActivity(MainActivity::refresh);
        waitUntil("Uninstalled add-on review", 5000, () -> visible("Install")); collapsed("29-uninstalled-review");
        readyLibrary();
        browse.onActivity(a -> a.root("Settings")); tap("Languages", false); tap("Keyboard languages", true);
        waitUntil("Keyboard languages", 5000, () -> visible("English")); collapsed("30-keyboard-languages");
        services.store.setPreference("developerMode", "true");
        browse.onActivity(a -> a.push(new Route("Developer"))); waitUntil("Developer", 5000, () -> visible("Open source notices")); collapsed("31-developer");
        for (String format : new String[]{"12", "24", "", "off"}) {
            services.store.setPreference("clock", format); browse.onActivity(MainActivity::refresh);
            waitUntil("Clock format applied", 5000, () -> {
                AtomicBoolean applied = new AtomicBoolean(); browse.onActivity(a -> {
                    android.widget.TextClock clock = a.findViewById(R.id.clock);
                    applied.set(format.equals("off") ? !clock.isShown() : clock.isShown()
                            && (format.equals("24") ? clock.getFormat24Hour().toString().equals("HH:mm") : clock.getFormat12Hour().toString().equals("h:mm a")));
                }); return applied.get();
            });
            collapsed("32-clock-" + (format.isEmpty() ? "automatic" : format));
        }
        for (tv.stream.app.data.InstalledAddon addon : services.store.addons()) services.addons.remove(addon.id);
        try (tv.stream.app.addon.Cancellation request = services.addons.newRequest()) {
            String url = origin + "/addon/broken/rail/manifest.json"; services.addons.install(url, services.addons.preview(url, request));
        }
        browse.onActivity(a -> a.root("Home")); waitUntil("Home error", 10000, () -> visible("Unable to load Home")); collapsed("33-home-error");
        browse.onActivity(MainActivity::search); browse.onActivity(a -> a.searchInput("Generated"));
        waitUntil("Search error", 10000, () -> visible("Search unavailable")); collapsed("34-search-error");
    }

    private void collapsed(String name) throws Exception {
        // Page actions clicked with a pointer can keep focus; enter the visible content before measuring.
        browse.onActivity(a -> {
            if (a.findViewById(R.id.top_bar).hasFocus()) a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
        });
        waitUntil("Wrapped or hidden rail", 5000, () -> width(NavigationRail.COLLAPSED) && railState() != NavigationRail.State.EXPANDED);
        SystemClock.sleep(350);
        browse.onActivity(a -> {
            View rail = a.findViewById(R.id.top_bar), content = a.findViewById(R.id.browse_content);
            boolean hidden = ((NavigationRail) rail).state() == NavigationRail.State.HIDDEN;
            assertEquals("Page composition sets its fixed inset", dp(hidden ? NavigationRail.HIDDEN_CONTENT_START : NavigationRail.CONTENT_START), a.contentStart());
            assertEquals("Only the rail's band fades", hidden ? 0 : dp(NavigationRail.CONTENT_START), content.getHorizontalFadingEdgeLength());
            View clock = a.findViewById(R.id.clock); Rect bounds = new Rect();
            if (clock.isShown()) {
                assertTrue(clock.getGlobalVisibleRect(bounds)); assertEquals("Clock keeps the top-right safe inset", a.getResources().getDisplayMetrics().widthPixels - dp(40), bounds.right);
                android.text.Layout text = ((TextView) clock).getLayout();
                for (int line = 0; line < text.getLineCount(); line++) assertEquals("Clock never truncates", 0, text.getEllipsisCount(line));
            }
            if (((NavigationRail) rail).state() == NavigationRail.State.WRAPPED) {
                ViewGroup rows = a.findViewById(R.id.tab_cluster);
                View search = a.findViewById(R.id.search_button);
                assertEquals("Wrapped rows are circles", search.getHeight(), search.getWidth());
                ViewGroup nav = a.findViewById(R.id.navigation);
                for (int i = 0; i < nav.getChildCount(); i++) assertEquals("Each destination is circular", nav.getChildAt(i).getHeight(), nav.getChildAt(i).getWidth());
            }
            assertControlsInside(content, hidden ? 0 : rail.getRight());
        });
        shot(name);
    }
    private static Rect bounds(View view) {
        int[] location = new int[2]; view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }
    private void assertControlsInside(View view, int edge) {
        if (!view.isShown()) return;
        // A shelf scrolled along passes its earlier cards under the rail on purpose; its focused card still clears it.
        if (view instanceof androidx.recyclerview.widget.RecyclerView && ((androidx.recyclerview.widget.RecyclerView) view).computeHorizontalScrollOffset() > 0) {
            View focused = view.findFocus();
            if (focused != null) assertControlsInside(focused, edge);
            return;
        }
        if (view.isFocusable() && view.getTag() instanceof String) {
            Rect bounds = new Rect();
            if (view.getGlobalVisibleRect(bounds)) assertTrue("Visible control clears rail: " + view.getTag(), bounds.left > edge);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) assertControlsInside(((ViewGroup) view).getChildAt(i), edge);
    }
    private static boolean labelsShown(View view) {
        if (!view.isShown()) return true;
        if (view instanceof TextView && !(view instanceof android.widget.TextClock)) return view.getAlpha() == 1f;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (!labelsShown(((ViewGroup) view).getChildAt(i))) return false;
        return true;
    }
    private void assertCaptions(View view) {
        if (!view.isShown()) return;
        if (view instanceof TextView && !(view instanceof android.widget.TextClock)) {
            TextView text = (TextView) view;
            assertTrue("Expanded label is visible", text.getAlpha() == 1f);
            assertNotNull(text.getLayout());
            assertEquals("Expanded caption fits: " + text.getText(), 0, text.getLayout().getEllipsisCount(0));
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) assertCaptions(((ViewGroup) view).getChildAt(i));
    }
    private void wrappedFocus(String tag, String name) throws Exception {
        assertFocus(tag);
        waitUntil("Focused rail stays wrapped", 5000, () -> railState() == NavigationRail.State.WRAPPED && width(NavigationRail.COLLAPSED));
        browse.onActivity(a -> assertFalse("Wrapped focus never covers the page with a dismissal target", a.findViewById(R.id.navigation_dismiss).isShown()));
        shot(name);
    }
    private void expanded(String tag, String name) throws Exception {
        assertFocus(tag);
        if (railState() == NavigationRail.State.WRAPPED) key(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("Expanded rail", 5000, () -> {
            AtomicBoolean shown = new AtomicBoolean(); browse.onActivity(a -> shown.set(labelsShown(a.findViewById(R.id.top_bar))));
            return railState() == NavigationRail.State.EXPANDED && width(NavigationRail.EXPANDED) && shown.get();
        });
        shot(name);
    }
    private NavigationRail.State railState() { AtomicReference<NavigationRail.State> state = new AtomicReference<>(); browse.onActivity(a -> state.set(((NavigationRail) a.findViewById(R.id.top_bar)).state())); return state.get(); }
    private boolean width(int dp) { AtomicBoolean yes = new AtomicBoolean(); browse.onActivity(a -> yes.set(a.findViewById(R.id.top_bar).getWidth() == dp(dp))); return yes.get(); }
    private boolean focused(String tag) { AtomicBoolean yes = new AtomicBoolean(); browse.onActivity(a -> yes.set(a.getCurrentFocus() != null && tag.equals(a.getCurrentFocus().getTag()))); return yes.get(); }
    private void assertFocus(String tag) {
        try { waitUntil("Focus " + tag, 5000, () -> focused(tag)); }
        catch (AssertionError failure) {
            AtomicReference<String> actual = new AtomicReference<>();
            browse.onActivity(a -> {
                View focus = a.getCurrentFocus();
                actual.set((focus == null ? "none" : focus.getClass().getSimpleName() + " tag=" + focus.getTag() + " id=" + focus.getId())
                        + " window=" + a.hasWindowFocus() + " touch=" + a.getWindow().getDecorView().isInTouchMode());
            });
            throw new AssertionError("Expected focus " + tag + ", actual " + actual.get(), failure);
        }
    }
    private void key(int code) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync(); }
    private int dp(int value) { return Tv.dp(context, value); }
    private void shot(String name) throws Exception {
        SystemClock.sleep(300); Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        File folder = new File(context.getFilesDir(), "vertical-navigation"); assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { image.recycle(); }
    }
}
