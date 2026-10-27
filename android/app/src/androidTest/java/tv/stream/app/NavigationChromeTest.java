package tv.stream.app;

import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NavigationChromeTest extends AppHarness {
    private final Rect backPosition = new Rect(), refreshPosition = new Rect();

    private void assertIcon(View view) {
        assertTrue(view.isShown());
        assertEquals("Utility controls stay circular", view.getHeight(), view.getWidth());
        assertEquals("No text is added to Back or Refresh", 1, ((ViewGroup) view).getChildCount());
        assertTrue(((ViewGroup) view).getChildAt(0) instanceof ImageView);
        assertNull("No hover text", view.getTooltipText());
    }
    private void fixedIcon(View view, Rect position) {
        assertIcon(view);
        Rect bounds = new Rect(); ((ViewGroup) view).getChildAt(0).getGlobalVisibleRect(bounds);
        // Compare centres, independent of focus growth and Refresh's rotating glyph.
        Rect center = new Rect(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY());
        if (position.left == 0) position.set(center);
        else { assertEquals(position.left, center.left, 1); assertEquals(position.top, center.top, 1); }
    }
    private void assertBack() { browse.onActivity(a -> fixedIcon(a.findViewById(R.id.page_back), backPosition)); }
    private void assertRefresh(String label) {
        browse.onActivity(a -> {
            View refresh = a.findViewById(R.id.home_refresh); fixedIcon(refresh, refreshPosition);
            assertEquals(label, refresh.getContentDescription());
            Rect button = new Rect(), clock = new Rect(); refresh.getGlobalVisibleRect(button); a.findViewById(R.id.clock).getGlobalVisibleRect(clock);
            assertTrue("Refresh sits left of the top-right clock", button.right < clock.left);
            assertTrue(button.centerX() > a.getResources().getDisplayMetrics().widthPixels * .7);
        });
    }
    @Test public void browseIsAPrimaryDestinationAndBackRemainsIconOnlyOnEverySubpage() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        tap("Browse", true); waitUntil("Browse page", 5000, () -> visible("Generated films"));
        browse.onActivity(a -> {
            assertFalse("Primary Browse has no Back button", a.findViewById(R.id.page_back).isShown());
            assertTrue(a.findViewById(R.id.navigation).findViewWithTag("nav:Browse").isActivated());
        });
        tap("Generated films", false); waitUntil("Catalogue", 10000, () -> visible("Generated Film")); assertBack();
        browse.onActivity(a -> a.findViewById(R.id.page_rows).findViewWithTag("filter:genre:").requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        browse.onActivity(a -> assertEquals("Up reaches the separate Back control", R.id.page_back, a.getCurrentFocus().getId()));
        tap("Back", false); waitUntil("Browse restored", 5000, () -> visible("Generated films"));
        browse.onActivity(a -> a.root("Home")); tap("Generated Film", true); waitUntil("Details", 5000, () -> visible("Play")); assertBack();
        tap("Sources", false); waitUntil("Sources", 10000, () -> visible("Size")); assertBack();
        tap("Settings", true); tap("Add-on store", true); waitUntil("Store", 10000, () -> visible("All")); assertBack();
        browse.onActivity(MainActivity::openImports); waitUntil("Imports", 5000, () -> visible("Add magnet or torrent URL")); assertBack();
    }
    @Test public void topRightRefreshKeepsItsPositionAndRemoteFocusAcrossPages() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film")); assertRefresh("Refresh");
        tap("Browse", false); tap("Generated films", false); waitUntil("Catalogue", 10000, () -> visible("Generated Film")); assertRefresh("Refresh");
        browse.onActivity(a -> a.findViewById(R.id.home_refresh).requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("Refreshed catalogue", 10000, () -> visible("Generated Film"));
        browse.onActivity(a -> assertEquals(R.id.home_refresh, a.getCurrentFocus().getId()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        browse.onActivity(a -> assertNotEquals("Down restores content", R.id.home_refresh, a.getCurrentFocus().getId()));
        browse.onActivity(a -> a.root("Home")); tap("Generated Film", true); waitUntil("Details", 5000, () -> visible("Play")); assertRefresh("Refresh details");
        tap("Sources", false); waitUntil("Sources", 10000, () -> visible("Size")); assertRefresh("Refresh sources");
        browse.onActivity(a -> a.root("Settings")); tap("Add-on store", true); waitUntil("Store", 10000, () -> visible("All")); assertRefresh("Refresh add-on lists");
        tap("Back", false); waitUntil("Settings", 5000, () -> visible("Add-ons"));
        browse.onActivity(a -> assertFalse(a.findViewById(R.id.home_tools).isShown()));
    }
    @Test public void toolbarLeftAndDownReturnToNavigationAndRememberedContent() throws Exception {
        readyLibrary(); launch(); waitUntil("Home", 10000, () -> visible("Generated Film"));
        browse.onActivity(a -> a.findViewById(R.id.home_refresh).requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        browse.onActivity(a -> assertEquals("nav:Home", a.getCurrentFocus().getTag()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        browse.onActivity(a -> assertFalse(a.findViewById(R.id.top_bar).hasFocus()));
        tap("Generated Film", false); waitUntil("Details", 5000, () -> visible("Play"));
        browse.onActivity(a -> a.findViewById(R.id.page_back).requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        browse.onActivity(a -> assertEquals(R.id.home_refresh, a.getCurrentFocus().getId()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        browse.onActivity(a -> assertEquals(R.id.page_back, a.getCurrentFocus().getId()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        browse.onActivity(a -> assertEquals("play", a.getCurrentFocus().getTag()));
    }
    @Test public void sourcesWaitForAnExplicitChoice() throws Exception {
        readyLibrary(); launch(); tap("Generated Film", false); tap("Sources", true);
        waitUntil("Source selection", 10000, () -> visible("Size") && visible("Sources"));
        assertNull(watch()); chooseFirstSource();
        waitUntil("Chosen source renders", 60000, () -> state() != null && state().renderedFirstFrame && state().positionMs > 1000);
        assertFalse(watch().getIntent().getStringExtra("source").isEmpty());
        instrumentation.runOnMainSync(watch()::finish);
    }
}
