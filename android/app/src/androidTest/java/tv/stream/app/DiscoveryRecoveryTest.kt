package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.app.addon.Cancellation

/** Real provider failures, retries and remote/pointer recovery in Home and both Search modes. */
class DiscoveryRecoveryTest : AppHarness() {
    @Test @Throws(Exception::class) fun returningHomeAfterSearchRetriesAFailedSnapshotImmediately() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            install(server); server.unavailable = true
            launch(); waitUntil("Home retry limit", 12000) { visible("Unable to load Home") && visible("Reload") }
            assertEquals(3, server.homeRequests.get().toLong())
            server.unavailable = false
            browse!!.onActivity { activity -> activity.search() }; browse!!.onActivity { activity -> activity.searchInput("Film") }
            waitUntil("Search works again", 5000) { visible("Film 00") }
            browse!!.onActivity { activity -> activity.root("Home") }
            waitUntil("Home recovers without a manual refresh or a five-minute wait", 5000
            ) { server.homeRequests.get() == 4 && visible("Film 00") && !visible("Reload") }
        }
    }
    @Test @Throws(Exception::class) fun homeAndSearchRetryOnlyFailedResourcesAndKeepHealthyResults() {
        BrowseRecoveryTest.CatalogServer().use { good ->
            BrowseRecoveryTest.CatalogServer().use { flaky ->
                install(good); install(flaky); flaky.unavailable = true
                launch(); waitUntil("Home retry limit", 12000) { text("Attempt 3 of 3") && visible("Reload") }
                assertTrue("Healthy shelf survives failures", visible("Film 00"))
                assertEquals(1, good.homeRequests.get().toLong()); assertEquals(3, flaky.homeRequests.get().toLong())
                screenshot("validation-home-recovery.png")
                flaky.unavailable = false; tap("Reload", true)
                waitUntil("Home reload recovers", 5000) { flaky.homeRequests.get() == 4 && !visible("Reload") }
                assertEquals(2, good.homeRequests.get().toLong())
                flaky.unavailable = true
                browse!!.onActivity { activity -> activity.search() }; browse!!.onActivity { activity -> activity.searchInput("Film") }
                waitUntil("Search retry limit", 12000) { text("Attempt 3 of 3") && visible("Reload") }
                assertTrue("Healthy search results stay usable", visible("Film 00")); assertFalse(visible("No results"))
                assertEquals("Healthy search provider is not retried", 1, good.searchRequests.get().toLong()); assertEquals(3, flaky.searchRequests.get().toLong())
                val total = flaky.searchRequests.get(); SystemClock.sleep(1300); assertEquals("Retries stop", total.toLong(), flaky.searchRequests.get().toLong())
            }
        }
    }
    @Test @Throws(Exception::class) fun failedSearchHasRemoteReloadAndAnEmptySuccessIsDifferent() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            install(server); launch(); waitUntil("Home", 10000) { visible("Film 00") }; server.unavailable = true
            tap("Search", true); browse!!.onActivity { activity -> activity.searchInput("Film") }
            waitUntil("failed search explained", 12000) { visible("Search unavailable") && text("Attempt 3 of 3") }
            assertFalse("A failed provider is not an empty success", visible("No results"))
            assertEquals(3, server.searchRequests.get().toLong()); screenshot("validation-search-recovery.png")
            browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:f").requestFocus() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitUntil("remote reaches recovery", 5000) { focus() == "reload-search" }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            waitUntil("Left returns to keyboard", 5000) { focus().startsWith("keyboard:") }
            browse!!.onActivity { activity -> ((activity.findViewById<View>(R.id.page_rows) as androidx.recyclerview.widget.RecyclerView).adapter as tv.stream.app.ui.PageAdapter).toggleSearchKeyboard() }
            waitUntil("full search can reach recovery", 5000) { focus() == "reload-search" }
            server.unavailable = false; server.emptySearch = true
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("empty success", 5000) { visible("No results") && !visible("Reload") }
            assertFalse(visible("Search unavailable")); assertEquals(4, server.searchRequests.get().toLong())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            waitUntil("empty full search returns to keyboard", 5000) { focus().startsWith("keyboard:") }
        }
    }
    @Test @Throws(Exception::class) fun leavingSearchCancelsBackoffAndPermanentErrorsAreNotRetried() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            install(server); launch(); waitUntil("Home", 10000) { visible("Film 00") }; server.unavailable = true
            tap("Search", true); browse!!.onActivity { activity -> activity.searchInput("Film") }
            waitUntil("backoff begins", 5000) { server.searchRequests.get() >= 1 }
            assertFalse(text("Retrying search")); assertFalse(visible("Reload"))
            tap("Back", false); waitUntil("Home restored", 5000) { visible("Film 00") }
            val count = server.searchRequests.get(); SystemClock.sleep(3300); assertEquals("Abandoned search never retries", count.toLong(), server.searchRequests.get().toLong())
            server.failureStatus = 401
            tap("Search", true); browse!!.onActivity { activity -> activity.searchInput("Other") }
            waitUntil("permanent failure", 5000) { visible("Search unavailable") && text("Attempt 1 of 3") }
            val denied = server.searchRequests.get(); SystemClock.sleep(3300); assertEquals(denied.toLong(), server.searchRequests.get().toLong())
        }
    }
    @Throws(Exception::class)
    private fun install(server: BrowseRecoveryTest.CatalogServer) {
        services.addons.newRequest().use { request -> services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request)) }
    }
    private fun focus(): String { val key = AtomicReference(""); browse!!.onActivity { activity -> if (activity.currentFocus != null) key.set((activity.currentFocus!!.tag as Any?).toString()) }; return key.get() }
    private fun text(part: String): Boolean { return instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(part).stream().anyMatch { node -> node.isVisibleToUser } }
    @Throws(Exception::class)
    private fun screenshot(name: String) { val image: Bitmap? = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(image); FileOutputStream(File(context.filesDir, name)).use { out -> image!!.compress(Bitmap.CompressFormat.PNG, 100, out) }; image!!.recycle() }
}
