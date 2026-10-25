package tv.daniil.stream;

import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NavigationChromeTest extends AppHarness {
    private final Rect fixed = new Rect();
    private final Rect refreshPosition = new Rect();
    private void assertFixedRefresh(String label, boolean home) {
        browse.onActivity(activity -> {
            View refresh = activity.findViewById(R.id.home_refresh);
            assertTrue(refresh.isShown()); assertEquals(label, refresh.getContentDescription());
            assertFalse("Refresh is outside scrolling content", descendant(activity.findViewById(R.id.page_rows), refresh));
            Rect bounds = new Rect(); assertTrue(refresh.getGlobalVisibleRect(bounds));
            bounds = layoutBounds(refresh, bounds);
            if (refreshPosition.isEmpty()) refreshPosition.set(bounds);
            else assertEquals("Refresh occupies Home's exact position and size", refreshPosition, bounds);
            assertEquals(home ? View.VISIBLE : View.GONE, activity.findViewById(R.id.home_browse).getVisibility());
        });
    }
    private void assertFixedBack() {
        browse.onActivity(activity -> {
            View back = activity.findViewById(R.id.page_back);
            assertEquals(View.VISIBLE, back.getVisibility());
            assertFalse("Back is outside the scrolling list", descendant(activity.findViewById(R.id.page_rows), back));
            Rect bounds = new Rect(); assertTrue(back.getGlobalVisibleRect(bounds));
            bounds = layoutBounds(back, bounds);
            if (fixed.isEmpty()) fixed.set(bounds); else assertEquals("Same position on every subpage", fixed, bounds);
            assertTrue("Back is in the upper left", bounds.right < activity.getResources().getDisplayMetrics().widthPixels * .2);
        });
    }
    private static Rect layoutBounds(View view, Rect drawn) {
        // Focus grows a button around its centre; compare its resting bounds across pages.
        int x = drawn.centerX(), y = drawn.centerY();
        return new Rect(x - view.getWidth() / 2, y - view.getHeight() / 2,
                x - view.getWidth() / 2 + view.getWidth(), y - view.getHeight() / 2 + view.getHeight());
    }
    private static boolean descendant(ViewGroup group, View view) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == view || child instanceof ViewGroup && descendant((ViewGroup) child, view)) return true;
        }
        return false;
    }
    @Test public void backStaysFixedAcrossSubpagesScrollAndRemoteFocus() throws Exception {
        readyLibrary(); launch(); waitUntil("home", 10000, () -> visible("Generated Film"));
        assertFalse(visible("Back"));
        tap("Browse catalogues", true); waitUntil("browse", 5000, () -> visible("Generated films")); assertFixedBack();
        browse.onActivity(activity -> ((RecyclerView) activity.findViewById(R.id.page_rows)).scrollBy(0, 300));
        android.os.SystemClock.sleep(300); assertFixedBack();
        tap("Back", false); waitUntil("home again", 5000, () -> visible("Browse catalogues"));
        tap("Browse catalogues", false); tap("Generated films", true); waitUntil("catalogue", 5000, () -> visible("Refresh"));
        assertFixedBack(); assertFalse("Old Categories action removed", visible("All categories"));
        android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        if (screenshot != null) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), "ui-fixed-back.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally { screenshot.recycle(); }
        }
        tap("Back", false); tap("Back", true);
        tap("Generated Film", false); waitUntil("details", 5000, () -> visible("Play")); assertFixedBack();
        tap("Sources", false); waitUntil("sources", 5000, () -> visible("Sources") && visible("Back")); assertFixedBack();
        waitUntil("source controls", 10000, () -> visible("Size"));
        browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("sort:BEST").requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        browse.onActivity(activity -> assertEquals("Up enters Back", R.id.page_back, activity.getCurrentFocus().getId()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("media restored", 5000, () -> visible("Play")); assertFixedBack();
        tap("Back", true);
        // The centre tab bar can be sliding away after restoring Home's scroll position.
        browse.onActivity(activity -> activity.findViewById(R.id.navigation).findViewWithTag("nav:Settings").requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("settings", 5000, () -> visible("Add-ons")); assertFalse(visible("Back"));
        tap("Add-on store", false); waitUntil("store", 10000, () -> visible("All")); assertFixedBack();
        tap("Back", false); tap("About", true); tap("Developer mode", false); tap("Developer", false);
        waitUntil("developer", 5000, () -> visible("Open source notices")); assertFixedBack();
        tap("Back", true);
        browse.onActivity(MainActivity::openImports);
        waitUntil("imports", 5000, () -> visible("Add magnet or torrent URL")); assertFixedBack();
    }
    @Test public void upFromRootContentSelectsActiveTabAndHomeToolsAreReachable() throws Exception {
        readyLibrary(); launch(); waitUntil("home tools", 10000, () -> visible("Browse catalogues"));
        browse.onActivity(activity -> activity.findViewById(R.id.navigation).findViewWithTag("nav:Settings").requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        browse.onActivity(activity -> assertEquals(R.id.home_browse, activity.getCurrentFocus().getId()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        browse.onActivity(activity -> assertEquals(R.id.home_refresh, activity.getCurrentFocus().getId()));
        browse.onActivity(activity -> activity.root("Library"));
        waitUntil("empty library", 5000, () -> visible("Your library is empty"));
        browse.onActivity(activity -> assertEquals("Empty Library leaves focus on its active tab", "nav:Library", activity.getCurrentFocus().getTag()));
        browse.onActivity(activity -> activity.root("Settings"));
        waitUntil("settings categories", 5000, () -> visible("Add-ons"));
        browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("settings:addons").requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        browse.onActivity(activity -> assertEquals("Up from settings reaches its active tab", "nav:Settings", activity.getCurrentFocus().getTag()));
    }
    @Test public void playOpensSourcesAndWaitsForAnExplicitChoice() throws Exception {
        readyLibrary(); launch(); tap("Generated Film", false); tap("Play", true);
        waitUntil("source selection", 10000, () -> visible("Size") && visible("Sources"));
        assertNull("Play has not started the player", watch());
        chooseFirstSource();
        waitUntil("chosen source renders", 60000, () -> state() != null && state().renderedFirstFrame && state().positionMs > 1000);
        assertFalse("Player receives an explicit source key", watch().getIntent().getStringExtra("source").isEmpty());
        instrumentation.runOnMainSync(watch()::finish);
    }

    @Test public void refreshUsesHomePositionAcrossCatalogueDetailsSourcesAndStore() throws Exception {
        readyLibrary(); launch(); waitUntil("home tools", 10000, () -> visible("Browse catalogues"));
        assertFixedRefresh("Refresh", true);
        tap("Browse catalogues", false); tap("Generated films", false);
        waitUntil("catalogue results", 10000, () -> visible("Generated Film"));
        assertFixedRefresh("Refresh", false);
        // A remote can reach the single icon without the hidden Browse slot intercepting focus.
        browse.onActivity(activity -> activity.findViewById(R.id.navigation).findViewWithTag("nav:Settings").requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        browse.onActivity(activity -> assertEquals(R.id.home_refresh, activity.getCurrentFocus().getId()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("refreshed catalogue", 10000, () -> visible("Generated Film"));
        instrumentation.waitForIdleSync();
        browse.onActivity(activity -> assertEquals("Refresh keeps remote focus", R.id.home_refresh, activity.getCurrentFocus().getId()));
        browse.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.page_rows);
            list.scrollToPosition(list.getAdapter().getItemCount() - 1);
        });
        instrumentation.waitForIdleSync(); assertFixedRefresh("Refresh", false);
        tap("Back", false); tap("Back", false); tap("Generated Film", false);
        waitUntil("details", 10000, () -> visible("Play")); assertFixedRefresh("Refresh details", false);
        tap("Refresh details", false); waitUntil("refreshed details", 10000, () -> visible("Play"));
        tap("Sources", false); waitUntil("source results", 10000, () -> visible("Size"));
        assertFixedRefresh("Refresh sources", false);
        // Refresh must invalidate the ten-minute source cache, not just repaint the same rows.
        for (tv.daniil.stream.data.InstalledAddon addon : services.store.addons()) services.addons.remove(addon.id);
        tap("Refresh sources", false);
        waitUntil("fresh provider result", 10000, () -> visible("Find a source add-on"));
        assertFixedRefresh("Refresh sources", false);
        browse.onActivity(activity -> activity.root("Settings"));
        tap("Add-on store", false); waitUntil("store results", 10000, () -> visible("All"));
        assertFixedRefresh("Refresh add-on lists", false);
        tap("Refresh add-on lists", false); waitUntil("refreshed store", 10000, () -> visible("All"));
        assertFixedRefresh("Refresh add-on lists", false);
        tap("Back", false); waitUntil("settings restored", 5000, () -> visible("Add-ons"));
        browse.onActivity(activity -> assertFalse("No stale refresh on Settings", activity.findViewById(R.id.home_tools).isShown()));
    }

}
