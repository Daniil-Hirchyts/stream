package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tv.stream.app.addon.Cancellation;

/** Real provider failures, retries and remote/pointer recovery in Home and both Search modes. */
public final class DiscoveryRecoveryTest extends AppHarness {
    @Test public void returningHomeAfterSearchRetriesAFailedSnapshotImmediately() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            install(server); server.unavailable = true;
            launch(); waitUntil("Home retry limit", 12000, () -> visible("Unable to load Home") && visible("Reload"));
            assertEquals(3, server.homeRequests.get());
            server.unavailable = false;
            browse.onActivity(MainActivity::search); browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("Search works again", 5000, () -> visible("Film 00"));
            browse.onActivity(activity -> activity.root("Home"));
            waitUntil("Home recovers without a manual refresh or a five-minute wait", 5000,
                    () -> server.homeRequests.get() == 4 && visible("Film 00") && !visible("Reload"));
        }
    }
    @Test public void homeAndSearchRetryOnlyFailedResourcesAndKeepHealthyResults() throws Exception {
        try (BrowseRecoveryTest.CatalogServer good = new BrowseRecoveryTest.CatalogServer();
             BrowseRecoveryTest.CatalogServer flaky = new BrowseRecoveryTest.CatalogServer()) {
            install(good); install(flaky); flaky.unavailable = true;
            launch(); waitUntil("Home retry limit", 12000, () -> text("Attempt 3 of 3") && visible("Reload"));
            assertTrue("Healthy shelf survives failures", visible("Film 00"));
            assertEquals(1, good.homeRequests.get()); assertEquals(3, flaky.homeRequests.get());
            screenshot("validation-home-recovery.png");
            flaky.unavailable = false; tap("Reload", true);
            waitUntil("Home reload recovers", 5000, () -> flaky.homeRequests.get() == 4 && !visible("Reload"));
            assertEquals(2, good.homeRequests.get());
            flaky.unavailable = true;
            browse.onActivity(MainActivity::search); browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("Search retry limit", 12000, () -> text("Attempt 3 of 3") && visible("Reload"));
            assertTrue("Healthy search results stay usable", visible("Film 00")); assertFalse(visible("No results"));
            assertEquals("Healthy search provider is not retried", 1, good.searchRequests.get()); assertEquals(3, flaky.searchRequests.get());
            int total = flaky.searchRequests.get(); SystemClock.sleep(1300); assertEquals("Retries stop", total, flaky.searchRequests.get());
        }
    }
    @Test public void failedSearchHasRemoteReloadAndAnEmptySuccessIsDifferent() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            install(server); launch(); waitUntil("Home", 10000, () -> visible("Film 00")); server.unavailable = true;
            tap("Search", true); browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("failed search explained", 12000, () -> visible("Search unavailable") && text("Attempt 3 of 3"));
            assertFalse("A failed provider is not an empty success", visible("No results"));
            assertEquals(3, server.searchRequests.get()); screenshot("validation-search-recovery.png");
            browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("keyboard:f").requestFocus());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitUntil("remote reaches recovery", 5000, () -> focus().equals("reload-search"));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            waitUntil("Left returns to keyboard", 5000, () -> focus().startsWith("keyboard:"));
            tap("Hide keyboard", true);
            waitUntil("full search can reach recovery", 5000, () -> focus().equals("reload-search"));
            server.unavailable = false; server.emptySearch = true;
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            waitUntil("empty success", 5000, () -> visible("No results") && !visible("Reload"));
            assertFalse(visible("Search unavailable")); assertEquals(4, server.searchRequests.get());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            waitUntil("empty full search returns to keyboard", 5000, () -> focus().startsWith("keyboard:"));
        }
    }
    @Test public void leavingSearchCancelsBackoffAndPermanentErrorsAreNotRetried() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            install(server); launch(); waitUntil("Home", 10000, () -> visible("Film 00")); server.unavailable = true;
            tap("Search", true); browse.onActivity(activity -> activity.searchInput("Film"));
            waitUntil("backoff begins", 5000, () -> server.searchRequests.get() >= 1);
            assertFalse(text("Retrying search")); assertFalse(visible("Reload"));
            tap("Back", false); waitUntil("Home restored", 5000, () -> visible("Film 00"));
            int count = server.searchRequests.get(); SystemClock.sleep(3300); assertEquals("Abandoned search never retries", count, server.searchRequests.get());
            server.failureStatus = 401;
            tap("Search", true); browse.onActivity(activity -> activity.searchInput("Other"));
            waitUntil("permanent failure", 5000, () -> visible("Search unavailable") && text("Attempt 1 of 3"));
            int denied = server.searchRequests.get(); SystemClock.sleep(3300); assertEquals(denied, server.searchRequests.get());
        }
    }
    private void install(BrowseRecoveryTest.CatalogServer server) throws Exception {
        try (Cancellation request = services.addons.newRequest()) { services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request)); }
    }
    private String focus() { AtomicReference<String> key = new AtomicReference<>(""); browse.onActivity(activity -> { if (activity.getCurrentFocus() != null) key.set(String.valueOf(activity.getCurrentFocus().getTag())); }); return key.get(); }
    private boolean text(String part) { return instrumentation.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(part).stream().anyMatch(node -> node.isVisibleToUser()); }
    private void screenshot(String name) throws Exception { Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image); try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } image.recycle(); }
}
