package tv.stream.app

import org.junit.Assert.*
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title
import tv.stream.app.ui.PageAdapter

/** Remote movement across recycled film rows and horizontally scrolling Home shelves. */
class FilmFocusNavigationTest : AppHarness() {
    @Test @Throws(Exception::class) fun returningFromDetailsThenMovingUpRestoresHomeSpotlight() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { scope ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope))
            }
            services.store.favourite(Title.parse(services.store.addons()[0].id, server.title(0))!!, true)
            launch(); waitUntil("Home favourites", 10000) { findTagPrefix("favourite:") != null }
            browse!!.onActivity { a -> findPrefix(a.findViewById(R.id.page_rows), "favourite:")!!.requestFocus() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitUntil("Catalogue shelf", 5000) { !focusTag().startsWith("favourite:") }
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitUntil("Film details", 10000) { visible("Play") }
            key(KeyEvent.KEYCODE_BACK); waitUntil("Home restored", 10000) { !visible("Play") && !focusTag().startsWith("nav:") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            waitUntil("First shelf focused", 5000) { focusTag().startsWith("favourite:") }
            waitUntil("Spotlight returns to the top", 5000) {
                val top = AtomicReference(false)
                browse!!.onActivity { a -> top.set(!a.findViewById<View>(R.id.page_rows).canScrollVertically(-1)) }
                top.get()
            }
        }
    }
    @Test @Throws(Exception::class) fun libraryGridAndHomeShelvesKeepFocusInsideTheirRows() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { scope ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope))
            }
            for (i in 0 until 13) services.store.favourite(Title.parse(services.store.addons()[0].id, server.title(i))!!, true)
            launch(); waitUntil("Home favourites", 10000) { visible("Favourites") }
            waitUntil("Home films", 10000) { findTagPrefix("favourite:") != null }
            browse!!.onActivity { a -> findPrefix(a.findViewById(R.id.page_rows), "favourite:")!!.requestFocus() }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT)
            val favourite = focusTag(); assertTrue(favourite.startsWith("favourite:"))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitUntil("Down reaches catalogue shelf", 5000) { focusTag() != favourite && !focusTag().startsWith("nav:") }
            assertFalse("Focus leaves the favourites shelf", focusTag().startsWith("favourite:"))
            key(KeyEvent.KEYCODE_DPAD_UP)
            waitUntil("Up returns to the same favourite", 5000) { focusTag() == favourite }
            val visited: MutableList<String> = java.util.ArrayList(); visited.add(focusTag())
            for (i in 0 until 7) {
                val previous = focusTag(); key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitUntil("Right reaches the next film", 5000) { focusTag() != previous }
                visited.add(focusTag())
            }
            for (i in 6 downTo 0) {
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                assertEquals("A scrolled shelf returns through every film", visited[i], focusTag())
            }
            browse!!.onActivity { a -> a.root("Library") }
            waitUntil("Library grid", 10000) { firstGrid() != null }
            val first = AtomicReference<View>(); val second = AtomicReference<View>(); val third = AtomicReference<View>()
            browse!!.onActivity { a ->
                val row = findGrid(a.findViewById(R.id.page_rows))
                first.set(focusable(row!!.getChildAt(0))); second.set(focusable(row.getChildAt(1))); third.set(focusable(row.getChildAt(2)))
                second.get().requestFocus()
            }
            key(KeyEvent.KEYCODE_DPAD_LEFT); waitUntil("Left reaches the first film", 5000) { focused(first.get()) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); waitUntil("Right returns to the second film", 5000) { focused(second.get()) }
            browse!!.onActivity { a -> third.get().requestFocus() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitUntil("Down reaches the next grid row", 5000) { gridSlot() == 2 && !focused(third.get()) }
            key(KeyEvent.KEYCODE_DPAD_UP); waitUntil("Up returns to the same column", 5000) { focused(third.get()) }
            browse!!.onActivity { a -> first.get().requestFocus() }
            key(KeyEvent.KEYCODE_DPAD_LEFT); waitUntil("Left at the first film reaches navigation", 5000) { focusTag() == "nav:Library" }
        }
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync() }
    private fun focusTag(): String { val result = AtomicReference(""); browse!!.onActivity { a -> val v = a.currentFocus; if (v != null) result.set((v.tag as Any?).toString()) }; return result.get() }
    private fun focused(view: View?): Boolean { val result = AtomicReference(false); browse!!.onActivity { a -> result.set(a.currentFocus === view) }; return result.get() }
    private fun findTagPrefix(prefix: String): View? { val result = AtomicReference<View>(); browse!!.onActivity { a -> result.set(findPrefix(a.findViewById(R.id.page_rows), prefix)) }; return result.get() }
    private fun firstGrid(): PageAdapter.GridRow? { val result = AtomicReference<PageAdapter.GridRow>(); browse!!.onActivity { a -> result.set(findGrid(a.findViewById(R.id.page_rows))) }; return result.get() }
    private fun gridSlot(): Int { val result = AtomicReference(-1); browse!!.onActivity { a ->
        var v: View? = a.currentFocus
        while (v != null && v.parent is View) {
            if (v.parent is PageAdapter.GridRow) { result.set((v.parent as PageAdapter.GridRow).indexOfChild(v)); break }
            v = v.parent as View
        }
    }; return result.get() }

    companion object {
        @JvmStatic private fun findPrefix(view: View, prefix: String): View? {
            if (view.isFocusable && (view.tag as Any?).toString().startsWith(prefix)) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) { val found = findPrefix(view.getChildAt(i), prefix); if (found != null) return found }
            return null
        }
        @JvmStatic private fun findGrid(view: View): PageAdapter.GridRow? {
            if (view is PageAdapter.GridRow) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) { val found = findGrid(view.getChildAt(i)); if (found != null) return found }
            return null
        }
        @JvmStatic private fun focusable(view: View): View? {
            if (view.isFocusable) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) { val found = focusable(view.getChildAt(i)); if (found != null) return found }
            return null
        }
    }
}
