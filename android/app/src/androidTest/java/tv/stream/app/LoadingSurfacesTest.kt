package tv.stream.app

import org.junit.Assert.*
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.app.addon.Cancellation
import tv.stream.app.ui.Item

class LoadingSurfacesTest : AppHarness() {
    @Test @Throws(Exception::class) fun onlyThePendingRemoteContentGetsSkeletonsAndLateResultsCannotReplaceLocalPages() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            server.homeGate = CountDownLatch(1)
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); waitUntil("remote Home is still loading", 5000) { skeleton("Home results") }
            assertFalse("Home does not pretend to be category blocks", skeleton("Catalogues"))
            tap("Settings", true); waitUntil("real Settings", 5000) { visible("Add-ons") }
            assertFalse("Local Settings has no ghost form", anySkeleton())
            tap("Library", false); waitUntil("real empty library", 5000) { visible("Your library is empty") }
            assertFalse("Empty Library is not represented by fake posters", anySkeleton())
            server.homeGate!!.countDown(); SystemClock.sleep(250)
            assertTrue("Late catalogue work keeps Library in place", visible("Your library is empty"))
            tap("Home", true); waitUntil("Home has actual posters", 10000) { visible("Film 00") }
            tap("Browse", true); waitUntil("local category index", 5000) { visible("Test films") }
            assertFalse("Category index reads the installed manifest, without a fake grid", anySkeleton())
        }
    }
    private fun skeleton(name: String): Boolean { return items().stream().anyMatch { item -> item.kind == Item.Kind.SKELETON && item.value == name } }
    private fun anySkeleton(): Boolean { return items().stream().anyMatch { item -> item.kind == Item.Kind.SKELETON } }
    @Suppress("UNCHECKED_CAST") private fun items(): List<Item> {
        val result = AtomicReference<List<Item>>(java.util.Collections.emptyList())
        browse!!.onActivity { activity ->
            try {
                val rows = activity.findViewById<RecyclerView>(R.id.page_rows)
                val data = rows.adapter!!.javaClass.getDeclaredField("items"); data.isAccessible = true
                result.set(java.util.ArrayList(data.get(rows.adapter) as List<Item>))
            } catch (e: Exception) { throw AssertionError(e) }
        }; return result.get()
    }
}
