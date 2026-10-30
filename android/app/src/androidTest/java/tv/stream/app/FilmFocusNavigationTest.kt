package tv.stream.app

import org.junit.Assert.*
import android.view.KeyEvent
import android.view.View
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.app.addon.Title

/** Remote movement across recycled film rows and horizontally scrolling Home shelves. */
class FilmFocusNavigationTest : AppHarness() {
    @Test @Throws(Exception::class) fun returningFromDetailsThenMovingUpRestoresHomeSpotlight() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { scope ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope))
            }
            services.store.favourite(Title.parse(services.store.addons()[0].id, server.title(0))!!, true)
            val firstFavourite = "favourite:" + services.store.library(false).first { it.favourite }.title.key()
            launch(); waitUntil("Home favourites", 10000) { shownControl(firstFavourite) }
            focus(firstFavourite)
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
            val firstFavourite = "favourite:" + services.store.library(false).first { it.favourite }.title.key()
            waitUntil("Home films", 10000) { shownControl(firstFavourite) }
            focus(firstFavourite)
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
            waitUntil("Library grid", 10000) { libraryRow().size >= 3 }
            val firstRow = libraryRow()
            val first = firstRow[0].key; val second = firstRow[1].key; val third = firstRow[2].key
            focus(second)
            key(KeyEvent.KEYCODE_DPAD_LEFT); waitUntil("Left reaches the first film", 5000) { focusTag() == first }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); waitUntil("Right returns to the second film", 5000) { focusTag() == second }
            focus(third)
            val before = Rect(); assertTrue("Third film is visible", keyed(before) { it == third })
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitUntil("Down reaches the next grid row", 5000) {
                val current = focusTag()
                val row = libraryRow(current)
                row.size > 2 && row[2].key == current && current != third && row[2].bounds.top > before.top
            }
            key(KeyEvent.KEYCODE_DPAD_UP); waitUntil("Up returns to the same column", 5000) { focusTag() == third }
            focus(first)
            key(KeyEvent.KEYCODE_DPAD_LEFT); waitUntil("Left at the first film reaches navigation", 5000) { focusTag() == "nav:Library" }
        }
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync() }
    private fun focusTag(): String { val result = AtomicReference(""); browse!!.onActivity { a -> val v = a.currentFocus; if (v != null) result.set((v.tag as Any?).toString()) }; return result.get() }
    private fun focus(key: String) {
        browse!!.onActivity { activity -> assertNotNull("Focusable film $key", focusKey(activity, key)) }
        waitUntil("Film takes focus", 5000) { focusTag() == key }
    }
    private data class Film(val key: String, val bounds: Rect)

    /** Use the visible controls and their geometry; Compose cards need no platform View per film. */
    private fun libraryRow(containing: String? = null): List<Film> {
        val films = ArrayList<Film>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val key = node.viewIdResourceName
            if (node.isFocusable && key?.startsWith("library:") == true) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) films.add(Film(key, bounds))
            }
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        for (window in windows()) if (window.isFocused) collect(window.root)
        val top = if (containing == null) films.minOfOrNull { it.bounds.top }
                else films.firstOrNull { it.key == containing }?.bounds?.top
        if (top == null) return emptyList()
        return films.filter { kotlin.math.abs(it.bounds.top - top) <= 2 }.sortedBy { it.bounds.left }
    }
}
