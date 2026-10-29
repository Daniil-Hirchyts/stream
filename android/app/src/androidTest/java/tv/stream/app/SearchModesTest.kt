package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.app.addon.Cancellation
import tv.stream.app.ui.PageAdapter

class SearchModesTest : AppHarness() {
    @Test @Throws(Exception::class) fun keyboardReflowFadesAsOneGridWithoutCardsCrossingRows() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); browse!!.onActivity { activity -> activity.searchInput("Film") }
            AppHarness.waitUntil("results", 10000) { visible("Film 00") && columns() == 4 }
            SystemClock.sleep(600)
            for (show in booleanArrayOf(false, true)) {
                val fadedOut = AtomicBoolean(); val fadedIn = AtomicBoolean(); val crossed = AtomicBoolean()
                val reflowVisible = AtomicBoolean(); val keyboardMoved = AtomicBoolean(); val keyboardOvershot = AtomicBoolean()
                val sharedMotion = AtomicBoolean(); val easedTail = AtomicBoolean()
                val started = SystemClock.uptimeMillis()
                val initialColumns = columns()
                val previousColumns = intArrayOf(initialColumns)
                val observer = AtomicReference<android.view.ViewTreeObserver.OnDrawListener>()
                browse!!.onActivity { activity ->
                    val rows = activity.findViewById<View>(R.id.page_rows)
                    val results = rows.findViewWithTag<View>("search-results"); val keyboard = rows.findViewWithTag<View>("search-keyboard")
                    observer.set(android.view.ViewTreeObserver.OnDrawListener {
                        val grid = grid(rows)
                        val columns = (grid!!.layoutManager as GridLayoutManager).spanCount
                        val alpha = results.alpha
                        if (alpha > 0 && alpha < .95f) {
                            if (columns == initialColumns) fadedOut.set(true) else fadedIn.set(true)
                        }
                        if (columns != previousColumns[0] && alpha > .01f) reflowVisible.set(true)
                        previousColumns[0] = columns
                        if (alpha > .01f) for (i in 0 until grid.childCount) {
                            val card = cardBounds(grid.getChildAt(i))
                            for (j in i + 1 until grid.childCount)
                                if (Rect.intersects(card, cardBounds(grid.getChildAt(j)))) crossed.set(true)
                        }
                        if (Math.abs(keyboard.translationX) > dp(2)) keyboardMoved.set(true)
                        if (Math.abs(keyboard.translationX) > keyboard.width + dp(50)) keyboardOvershot.set(true)
                        if (keyboard.isShown && keyboard.alpha > .02f && keyboard.alpha < .98f) {
                            if (columns == initialColumns && alpha > .1f && alpha < .95f) sharedMotion.set(true)
                            val elapsed = SystemClock.uptimeMillis() - started
                            if (elapsed >= 180 && elapsed <= 340 && Math.abs(keyboard.translationX) > dp(2)) easedTail.set(true)
                        }
                    })
                    rows.viewTreeObserver.addOnDrawListener(observer.get())
                    ((rows as RecyclerView).adapter as PageAdapter).toggleSearchKeyboard()
                }
                SystemClock.sleep(200); capture("validation-search-reflow-$show.png")
                AppHarness.waitUntil("reflow finishes", 5000) { columns() == (if (show) 4 else 6) && keyboardShown() == show
                        && (if (show) focus().startsWith("keyboard:") else focus().startsWith("search:[")) }
                browse!!.onActivity { activity ->
                    val rows = activity.findViewById<View>(R.id.page_rows)
                    rows.viewTreeObserver.removeOnDrawListener(observer.get())
                    assertEquals(1f, rows.findViewWithTag<View>("search-results").alpha, 0f)
                    assertEquals(0f, rows.findViewWithTag<View>("search-keyboard").translationX, 0f)
                    assertTrue("Results stay inside their scrolling viewport",
                            (grid(rows)!!.parent as ViewGroup).clipChildren)
                    assertEquals(0f, activity.findViewById<View>(R.id.scroll_fade).alpha, 0f)
                }
                assertSearchNavigationHidden()
                assertTrue("The outgoing grid fades", fadedOut.get())
                assertTrue("The incoming grid fades", fadedIn.get())
                assertFalse("Column changes happen while the grid is invisible", reflowVisible.get())
                assertFalse("Visible films never cross over one another", crossed.get())
                assertTrue("Keyboard slides with the reflow", keyboardMoved.get())
                assertTrue("Keyboard motion starts with the outgoing grid fade", sharedMotion.get())
                assertTrue("Keyboard motion eases through a longer fading tail", easedTail.get())
                assertFalse("Keyboard moves only through its own space", keyboardOvershot.get())
            }
            // Reverse before the layout changes, then reverse during the incoming keyboard slide.
            for (delay in longArrayOf(60, 200)) {
                browse!!.onActivity { activity -> (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).toggleSearchKeyboard() }
                SystemClock.sleep(delay)
                browse!!.onActivity { activity ->
                    val rows = activity.findViewById<RecyclerView>(R.id.page_rows)
                    val keyboard = rows.findViewWithTag<View>("search-keyboard")
                    val x = keyboard.translationX; val alpha = keyboard.alpha
                    (rows.adapter as PageAdapter).toggleSearchKeyboard()
                    assertEquals("A reversal keeps the painted keyboard position", x, keyboard.translationX, .001f)
                    assertEquals("A reversal keeps the painted keyboard opacity", alpha, keyboard.alpha, .001f)
                }
                AppHarness.waitUntil("reversed reflow", 5000) { keyboardShown() && columns() == 4 && focus().startsWith("keyboard:") }
            }
            assertEquals(1, server.searchRequests.get().toLong())
        }
    }
    @Test @Throws(Exception::class) fun leftStaysInTheKeyboardAndSearchNeverRevealsNavigation() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:q").requestFocus() }
        SystemClock.sleep(250); capture("validation-search-popup-keyboard.png")
        for (pair in arrayOf(arrayOf("r", "q"), arrayOf("f", "e"), arrayOf("b", "a"), arrayOf("l", "k"), arrayOf("x", "w"),
                arrayOf("Space", "z"), arrayOf("⌫", "Space"), arrayOf("Clear", "àéü"))) {
            browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:" + pair[0]).requestFocus() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            AppHarness.waitUntil("Left reaches " + pair[1], 3000) { focus() == "keyboard:" + pair[1] }
            browse!!.onActivity { activity -> assertEquals(tv.stream.app.ui.NavigationRail.State.HIDDEN,
                    (activity.findViewById<View>(R.id.top_bar) as tv.stream.app.ui.NavigationRail).state()) }
        }
        for (leading in arrayOf("a", "g", "m", "s", "y", "123", "Language")) {
            browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:$leading").requestFocus() }
            for (repeat in 0 until 2) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals("Left stops at the leading key", "keyboard:$leading", focus())
            assertSearchNavigationHidden()
        }
        for (mouse in booleanArrayOf(true, false)) {
            pointer(dp(8).toFloat(), context.resources.displayMetrics.heightPixels / 2f, mouse)
            assertSearchNavigationHidden()
        }
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_back).requestFocus() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Left from Back stays on Back", "nav:back", focus())
        assertSearchNavigationHidden()
        capture("validation-search-navigation-hidden.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Back leaves Search", 5000) { visible("Make yourself at home") && !keyboardShown() }
        browse!!.onActivity { activity -> assertEquals(tv.stream.app.ui.NavigationRail.State.WRAPPED,
                (activity.findViewById<View>(R.id.top_bar) as tv.stream.app.ui.NavigationRail).state()) }
    }
    private fun assertSearchNavigationHidden() {
        browse!!.onActivity { activity ->
            val rail = activity.findViewById<tv.stream.app.ui.NavigationRail>(R.id.top_bar)
            assertEquals(tv.stream.app.ui.NavigationRail.State.HIDDEN, rail.state())
            assertFalse("Search never shows navigation", rail.isShown)
            assertFalse("No pointer or touch reveal target", activity.findViewById<View>(R.id.navigation_reveal).isShown)
            assertFalse(activity.findViewById<View>(R.id.navigation_dismiss).isShown)
            assertFalse(rail.hasFocus())
        }
    }
    private fun cardBounds(view: View?): Rect {
        assertNotNull(view)
        val point = IntArray(2); view!!.getLocationOnScreen(point)
        return Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }
    @Test @Throws(Exception::class) fun recreatingDuringEitherPhaseRestoresAVisibleGridAndItsSelectedFilm() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); browse!!.onActivity { activity -> activity.searchInput("Film") }
            AppHarness.waitUntil("results", 10000) { visible("Film 00") && columns() == 4 }
            val target = "search:[\"" + services.store.addons()[0].id + "\",\"movie\",\"browse:36\"]"
            browse!!.onActivity { activity -> grid(activity.findViewById(R.id.page_rows))!!.scrollToPosition(36) }
            AppHarness.waitUntil("later film", 5000) { has(target) }
            browse!!.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(target).requestFocus())
                assertEquals(target, activity.currentFocus!!.tag)
                (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).toggleSearchKeyboard()
            }
            SystemClock.sleep(60); browse!!.recreate()
            AppHarness.waitUntil("full search restores after the outgoing fade", 10000) { !keyboardShown() && columns() == 6 && focus() == target }
            browse!!.onActivity { activity -> (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).toggleSearchKeyboard() }
            SystemClock.sleep(200); browse!!.recreate()
            AppHarness.waitUntil("typing restores during the incoming slide", 10000) { keyboardShown() && columns() == 4 && focus().startsWith("keyboard:") }
            browse!!.onActivity { activity -> assertEquals(1f,
                    activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("search-results").alpha, 0f) }
        }
    }
    @Test @Throws(Exception::class) fun emptyFullSearchStillReturnsToTheKeyboardWithLeft() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
        browse!!.onActivity { activity -> (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).toggleSearchKeyboard() }
        AppHarness.waitUntil("empty results get the full page", 5000) { !keyboardShown() && focus() == "search-results" }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("Left returns to typing even without results", 5000) { keyboardShown() && focus().startsWith("keyboard:") }
    }
    @Test @Throws(Exception::class) fun theQueryHeadsTheKeyboardAndFullSearchIsOnlyResults() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
            assertEquals("Search", query())
            for (control in arrayOf("Close search", "Hide keyboard", "Show keyboard")) assertFalse(control, visible(control))
            tap("Type f", false); tap("Type i", false)
            AppHarness.waitUntil("typed text shows before results", 2000) { query() == "fi" }
            AppHarness.waitUntil("results", 10000) { visible("Film 00") && columns() == 4 }
            browse!!.onActivity { activity ->
                val rows = activity.findViewById<View>(R.id.page_rows)
                val keys = cardBounds(rows.findViewWithTag("keyboard-keys")); val text = cardBounds(activity.findViewById(R.id.search_query))
                assertEquals("The query lines up with the keys", keys.left.toFloat(), text.left.toFloat(), 1f)
                assertTrue("The query sits just above the keys", text.bottom <= keys.top && keys.top - text.bottom <= dp(24))
                assertFalse("The query is text, not a control", activity.findViewById<View>(R.id.search_query).isFocusable)
            }
            screenshot("validation-search-query-keyboard.png")
            browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:f").requestFocus() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            AppHarness.waitUntil("results take the page", 5000) { !keyboardShown() && columns() == 6 && focus().startsWith("search:[") }
            browse!!.onActivity { activity ->
                assertFalse("The query leaves with the keyboard", activity.findViewById<View>(R.id.search_query).isShown)
                assertTrue("Results start at the page's leading edge", cardBounds(grid(activity.findViewById(R.id.page_rows))).left < dp(120))
            }
            screenshot("validation-search-query-results.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            AppHarness.waitUntil("Left brings the keyboard and query back", 5000) { keyboardShown() && query() == "fi" }
        }
    }
    private fun query(): String {
        val result = AtomicReference("")
        browse!!.onActivity { activity -> result.set((activity.findViewById<View>(R.id.search_query) as android.widget.TextView).text.toString()) }
        return result.get()
    }
    @Test @Throws(Exception::class) fun remoteAndPointerSwitchModesAnimateAndPreserveTheQueryAndSelectedFilm() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); browse!!.onActivity { activity -> activity.searchInput("Film") }
            AppHarness.waitUntil("split search results", 10000) { visible("Film 00") && columns() == 4 }
            SystemClock.sleep(350) // All provider callbacks finish before changing the retained view mode.
            val split = resultsBounds()
            browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:f").requestFocus() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            AppHarness.waitUntil("results expand after Right", 5000) { columns() == 6 && !keyboardShown() && focus().startsWith("search:[") }
            assertTrue("Grid uses the keyboard's former width", resultsBounds().width() > split.width() + dp(180))
            val target = "search:[\"" + services.store.addons()[0].id + "\",\"movie\",\"browse:36\"]"
            browse!!.onActivity { activity -> grid(activity.findViewById(R.id.page_rows))!!.scrollToPosition(36) }
            AppHarness.waitUntil("later result bound", 5000) { has(target) }
            browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(target).requestFocus() }
            screenshot("validation-search-full.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            AppHarness.waitUntil("Left reveals keyboard", 5000) { keyboardShown() && columns() == 4 && focus() == "keyboard:f" }
            assertEquals(split.width().toFloat(), resultsBounds().width().toFloat(), dp(2).toFloat())
            assertTrue(visible("Film")); screenshot("validation-search-keyboard.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            AppHarness.waitUntil("Right returns to the same result", 5000) { !keyboardShown() && columns() == 6 && focus() == target }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            AppHarness.waitUntil("details", 10000) { visible("Play") }; tap("Back", false)
            AppHarness.waitUntil("Back restores full search and its selected film", 5000) { columns() == 6 && !keyboardShown() && focus() == target }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            AppHarness.waitUntil("Left brings the keyboard back", 5000) { columns() == 4 && keyboardShown() && focus().startsWith("keyboard:") }
            // A pointer has no way to bring the keyboard back, so tapping a result keeps it while the film opens.
            tap("Film 36", true)
            AppHarness.waitUntil("pointer opens the film", 10000) { visible("Play") }; tap("Back", true)
            AppHarness.waitUntil("the keyboard stayed", 5000) { columns() == 4 && keyboardShown() }
            assertEquals("Mode switches and Back never repeat the search", 1, server.searchRequests.get().toLong())
        }
    }
    private fun columns(): Int {
        val result = AtomicReference(0)
        browse!!.onActivity { activity -> val grid = grid(activity.findViewById(R.id.page_rows)); if (grid != null) result.set((grid.layoutManager as GridLayoutManager).spanCount) }; return result.get()
    }
    private fun grid(view: View?): RecyclerView? {
        if (view is RecyclerView && view.layoutManager is GridLayoutManager) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) { val child = grid(view.getChildAt(i)); if (child != null) return child }
        return null
    }
    private fun keyboardShown(): Boolean { return has("search-keyboard") && shown("search-keyboard") }
    private fun has(key: String): Boolean {
        val result = AtomicReference(false)
        browse!!.onActivity { activity -> result.set(activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(key) != null) }; return result.get()
    }
    private fun shown(key: String): Boolean {
        val result = AtomicReference(false)
        browse!!.onActivity { activity -> val view = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(key); result.set(view != null && view.isShown) }; return result.get()
    }
    private fun resultsBounds(): Rect {
        val result = Rect(); browse!!.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("search-results").getGlobalVisibleRect(result)) }; return result
    }
    private fun focus(): String {
        val result = AtomicReference(""); browse!!.onActivity { activity -> if (activity.currentFocus != null) result.set(activity.currentFocus!!.tag.toString()) }; return result.get()
    }
    private fun dp(value: Int): Int { return Math.round(value * context.resources.displayMetrics.density) }
    @Throws(Exception::class)
    private fun screenshot(name: String) {
        SystemClock.sleep(350); capture(name)
    }
    @Throws(Exception::class)
    private fun capture(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(image)
        FileOutputStream(File(context.filesDir, name)).use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out) }; image.recycle()
    }
}
