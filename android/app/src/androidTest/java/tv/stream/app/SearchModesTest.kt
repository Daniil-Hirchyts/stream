package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.app.ui.PageAdapter
import tv.stream.app.ui.SearchBlocks
import androidx.compose.ui.semantics.SemanticsProperties

class SearchModesTest : SearchHarness() {
    @Test @Throws(Exception::class) fun keyboardReflowFadesAsOneGridWithoutCardsCrossingRows() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); browse!!.onActivity { activity -> activity.searchInput("Film") }
            searchWait("results", 10000) { visible("Film 00") && columns() == 4 }
            SystemClock.sleep(600)
            for (show in booleanArrayOf(false, true)) {
                val fadedOut = AtomicBoolean(); val fadedIn = AtomicBoolean(); val crossed = AtomicBoolean()
                val reflowVisible = AtomicBoolean(); val keyboardMoved = AtomicBoolean(); val keyboardOvershot = AtomicBoolean()
                val sharedMotion = AtomicBoolean(); val easedTail = AtomicBoolean()
                val started = SystemClock.uptimeMillis()
                val initialColumns = columns()
                val previousColumns = intArrayOf(initialColumns)
                SearchBlocks.drawn = { frame ->
                    val columns = frame.columns
                    val alpha = frame.resultsAlpha
                    if (alpha > 0 && alpha < .95f) {
                        if (columns == initialColumns) fadedOut.set(true) else fadedIn.set(true)
                    }
                    if (columns != previousColumns[0] && alpha > .01f) reflowVisible.set(true)
                    previousColumns[0] = columns
                    if (alpha > .01f) for (i in frame.cards.indices) {
                        for (j in i + 1 until frame.cards.size)
                            if (Rect.intersects(frame.cards[i], frame.cards[j])) crossed.set(true)
                    }
                    if (kotlin.math.abs(frame.keyboardX) > dp(2)) keyboardMoved.set(true)
                    if (kotlin.math.abs(frame.keyboardX) > frame.keyboardWidth + dp(50)) keyboardOvershot.set(true)
                    if (frame.keyboardAlpha > .02f && frame.keyboardAlpha < .98f) {
                        if (columns == initialColumns && alpha > .1f && alpha < .95f) sharedMotion.set(true)
                        val elapsed = SystemClock.uptimeMillis() - started
                        if (elapsed >= 180 && elapsed <= 340 && kotlin.math.abs(frame.keyboardX) > dp(2)) easedTail.set(true)
                    }
                }
                browse!!.onActivity { activity -> (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).toggleSearchKeyboard() }
                SystemClock.sleep(200); capture("validation-search-reflow-$show.png")
                AppHarness.waitUntil("reflow finishes", 5000) { columns() == (if (show) 4 else 6) && keyboardShown() == show
                        && (if (show) focus().startsWith("keyboard:") else focus().startsWith("search:[")) }
                SearchBlocks.drawn = null
                assertEquals(1f, frame().resultsAlpha, 0f)
                if (show) {
                    assertEquals(0f, frame().keyboardX, 0f)
                    assertEquals(1f, frame().keyboardAlpha, 0f)
                } else {
                    assertEquals(0f, frame().keyboardAlpha, 0f)
                    assertTrue("Hidden keyboard has moved out through its own width", frame().keyboardX <= -frame().keyboardWidth)
                }
                val viewport = searchNode("search-results").boundsInRoot
                val resultNodes = semanticNodes { node ->
                    node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("search:[")
                }
                assertTrue("Results have visible cards", resultNodes.isNotEmpty())
                for (node in resultNodes) {
                    val bounds = node.boundsInRoot
                    // Rows scroll on past the results area to the window's edges (fading out), so only the sides are
                    // bounded, with room for a focused card's lift beyond them (it is no longer clipped there).
                    val lift = dp(28)
                    if (bounds.width > 0 && bounds.height > 0) assertTrue("Results stay inside their scrolling viewport: $bounds in $viewport",
                            bounds.left >= viewport.left - lift && bounds.right <= viewport.right + lift)
                }
                browse!!.onActivity { activity -> assertEquals(0f, activity.findViewById<View>(R.id.scroll_fade).alpha, 0f) }
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
                    val before = (rows.adapter as PageAdapter).searchState!!.frame!!
                    (rows.adapter as PageAdapter).toggleSearchKeyboard()
                    val after = (rows.adapter as PageAdapter).searchState!!.frame!!
                    assertEquals("A reversal keeps the painted keyboard position", before.keyboardX, after.keyboardX, .001f)
                    assertEquals("A reversal keeps the painted keyboard opacity", before.keyboardAlpha, after.keyboardAlpha, .001f)
                }
                AppHarness.waitUntil("reversed reflow", 5000) { keyboardShown() && columns() == 4 && focus().startsWith("keyboard:") }
            }
            assertEquals(1, server.searchRequests.get().toLong())
        }
    }
    @Test @Throws(Exception::class) fun leftStaysInTheKeyboardAndSearchNeverRevealsNavigation() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
        focusSearch("keyboard:q")
        SystemClock.sleep(250); capture("validation-search-popup-keyboard.png")
        for (pair in arrayOf(arrayOf("r", "q"), arrayOf("f", "e"), arrayOf("b", "a"), arrayOf("l", "k"), arrayOf("x", "w"),
                arrayOf("Space", "z"), arrayOf("⌫", "Space"), arrayOf("Clear", "àéü"))) {
            focusSearch("keyboard:" + pair[0])
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            AppHarness.waitUntil("Left reaches " + pair[1], 3000) { focus() == "keyboard:" + pair[1] }
            browse!!.onActivity { activity -> assertEquals(tv.stream.app.ui.NavigationRail.State.HIDDEN,
                    (activity.findViewById<View>(R.id.top_bar) as tv.stream.app.ui.NavigationRail).state()) }
        }
        for (leading in arrayOf("a", "g", "m", "s", "y", "123", "Language")) {
            focusSearch("keyboard:$leading")
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
    @Test @Throws(Exception::class) fun recreatingDuringEitherPhaseRestoresAVisibleGridAndItsSelectedFilm() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); browse!!.onActivity { activity -> activity.searchInput("Film") }
            searchWait("results", 10000) { visible("Film 00") && columns() == 4 }
            val target = "search:[\"" + services.store.addons()[0].id + "\",\"movie\",\"browse:36\"]"
            browse!!.onActivity { activity -> (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).searchState!!.scrollToResult(36) }
            AppHarness.waitUntil("later film", 5000) { has(target) }
            browse!!.onActivity { activity ->
                assertNotNull(focusKey(activity, target))
                assertEquals(target, activity.currentFocus!!.tag)
                (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).toggleSearchKeyboard()
            }
            SystemClock.sleep(60); browse!!.recreate()
            AppHarness.waitUntil("full search restores after the outgoing fade", 10000) { !keyboardShown() && columns() == 6 && focus() == target }
            browse!!.onActivity { activity -> (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).toggleSearchKeyboard() }
            SystemClock.sleep(200); browse!!.recreate()
            AppHarness.waitUntil("typing restores during the incoming slide", 10000) { keyboardShown() && columns() == 4 && focus().startsWith("keyboard:") }
            assertEquals(1f, frame().resultsAlpha, 0f)
        }
    }
    @Test @Throws(Exception::class) fun emptyFullSearchStillReturnsToTheKeyboardWithLeft() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
        browse!!.onActivity { activity -> (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).toggleSearchKeyboard() }
        searchWait("empty results get the full page", 5000) { !keyboardShown() && focus() == "search-results" }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        searchWait("Left returns to typing even without results", 5000) { keyboardShown() && focus().startsWith("keyboard:") }
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
            searchWait("results", 10000) { visible("Film 00") && columns() == 4 }
            val keys = searchBounds("keyboard-keys"); val text = searchBounds("search-query")
            assertEquals("The query lines up with the keys", keys.left.toFloat(), text.left.toFloat(), 1f)
            assertTrue("The query sits just above the keys", text.bottom <= keys.top && keys.top - text.bottom <= dp(24))
            assertFalse("The query is text, not a control", SemanticsProperties.Focused in searchNode("search-query").config)
            screenshot("validation-search-query-keyboard.png")
            focusSearch("keyboard:f")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            AppHarness.waitUntil("results take the page", 5000) { !keyboardShown() && columns() == 6 && focus().startsWith("search:[") }
            assertFalse("The query leaves with the keyboard", shownKey(Rect()) { it == "search-query" })
            assertTrue("Results start at the page's leading edge", resultsBounds().left < dp(120))
            screenshot("validation-search-query-results.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            // The keyboard's edge slides in a frame before the query text enters the window.
            AppHarness.waitUntil("Left brings the keyboard and query back", 5000) { keyboardShown() && semanticNode("search-query") != null && query() == "fi" }
        }
    }
    @Test @Throws(Exception::class) fun scrolledResultsRunOnToTheWindowEdgesWithTheFocusedCardWhole() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
            tap("Type f", false); tap("Type i", false)
            searchWait("results", 10000) { visible("Film 00") && columns() == 4 }
            focusSearch("keyboard:f")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            AppHarness.waitUntil("full results", 5000) { !keyboardShown() && columns() == 6 && focus().startsWith("search:[") }
            val area = resultsBounds()
            for (i in 0 until 3) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); SystemClock.sleep(450) }
            instrumentation.waitForIdleSync(); SystemClock.sleep(400)
            val focused = searchBounds(focus())
            assertTrue("The focused card is whole inside the results area", focused.top >= area.top && focused.bottom <= area.bottom
                    && focused.left >= area.left && focused.right <= area.right)
            // The row above has scrolled past the area's top; it is still composed (drawn fading under the toolbar).
            val above = semanticNodes { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("search:[") }
                    .count { it.boundsInWindow.bottom < focused.top }
            assertTrue("Rows above the focused one stay drawn", above > 0)
            screenshot("validation-search-scrolled.png")
        }
    }
    @Test fun extendingQueryWithReorderedResultsStartsAtTheTop() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
            tap("Type f", false); tap("Type i", false)
            searchWait("first query", 10000) { visible("Film 00") }
            SystemClock.sleep(500)
            fun firstRow(): Pair<String, Float> = semanticNodes {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("search:[")
            }.minBy { it.boundsInWindow.top }.let {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } to it.boundsInWindow.top
            }
            val top = firstRow().second
            server.reverseSearch = true
            tap("Type l", false)
            searchWait("extended query", 10000) { query() == "fil" && server.searchRequests.get() >= 2 }
            SystemClock.sleep(700)
            val first = firstRow()
            assertTrue("The reordered first result is visible: $first", first.first.contains("browse:95"))
            assertEquals("The new query starts at the same top", top, first.second, 2f)
        }
    }
    /** Typing a query again starts its results at the top, not where that query's grid was last scrolled. */
    @Test @Throws(Exception::class) fun retypedQueryStartsAtTheTop() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
            tap("Type f", false); tap("Type i", false)
            searchWait("results", 10000) { visible("Film 00") && columns() == 4 }
            fun firstTop(): Float = semanticNodes { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("search:[") }.minOf { it.boundsInWindow.top }
            val top = firstTop()
            focusSearch("keyboard:f")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            AppHarness.waitUntil("full results", 5000) { !keyboardShown() && focus().startsWith("search:[") }
            for (i in 0 until 3) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); SystemClock.sleep(400) }
            AppHarness.waitUntil("results scrolled", 5000) { firstTop() < top - 50 }
            // Back from a title rebuilds Search with a restoration key. That key must not restore a later query.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            AppHarness.waitUntil("title", 10000) { visible("Play") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            AppHarness.waitUntil("results restored", 10000) { focus().startsWith("search:[") }
            browse!!.recreate()
            AppHarness.waitUntil("restored activity", 10000) { focus().startsWith("search:[") }
            // Left from the leading column brings the keyboard back with the grid still scrolled.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            AppHarness.waitUntil("keyboard again", 5000) { keyboardShown() && focus().startsWith("keyboard:") }
            tap("Clear", false); AppHarness.waitUntil("cleared", 5000) { query() == "Search" }
            tap("Type f", false); tap("Type i", false)
            searchWait("results again", 10000) { visible("Film 00") }
            SystemClock.sleep(600)
            assertEquals("The first row is back at the top of the results", top, firstTop(), 2f)
        }
    }
    /** A slower provider whose answers rank first must not leave the grid scrolled to the results already shown. */
    @Test @Throws(Exception::class) fun lateEarlierRankedAnswersKeepTheResultsAtTheTop() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            server.catalogCount = 2
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            server.lateSearchMs = 2500
            tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
            tap("Type f", false); tap("Type i", false)
            fun firstRow(): Pair<String, Float> = semanticNodes {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("search:[")
            }.minBy { it.boundsInWindow.top }.let { it.config.getOrElse(SemanticsProperties.TestTag) { "" } to it.boundsInWindow.top }
            searchWait("first answers", 10000) { visible("Film 100") }
            SystemClock.sleep(300)
            val top = firstRow().second
            SystemClock.sleep(server.lateSearchMs + 1500)
            val first = firstRow()
            assertTrue("The late provider's first film leads the results: $first", first.first.contains("\"browse:0\""))
            assertEquals("The results still start at the top", top, first.second, 2f)
        }
    }
    private fun query(): String = searchQuery()
    @Test @Throws(Exception::class) fun aPointerSweepLeavesNoKeyHoveredWhileResultsArrive() {
        // The TV's pointer (or the emulator window's) passing over the keyboard while results arrive left keys drawn
        // hovered after it moved on.
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
            focusSearch("keyboard:f")
            for (letter in "film") {
                browse!!.onActivity { activity -> activity.searchInput(searchQuery().let { if (it == "Search") "" else it } + letter) }
                // Sweep across a row of keys while this answer arrives.
                val from = searchBounds("keyboard:g"); val to = searchBounds("keyboard:l")
                for (step in 0..20) {
                    pointerHover(from.centerX() + (to.centerX() - from.centerX()) * step / 20f, from.centerY().toFloat()); SystemClock.sleep(12)
                }
            }
            AppHarness.waitUntil("results", 10000) { visible("Film 00") && query() == "film" }
            // The pointer leaves the keyboard for an empty part of the page.
            pointerHover(960f, 140f)
            SystemClock.sleep(600)
            assertEquals("Only the focused key is drawn highlighted", listOf("keyboard:f"), litKeys())
        }
    }
    private fun pointerHover(x: Float, y: Float) {
        val p = android.view.MotionEvent.PointerProperties(); p.id = 0; p.toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
        val c = android.view.MotionEvent.PointerCoords(); c.x = x; c.y = y
        val time = SystemClock.uptimeMillis()
        val e = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_HOVER_MOVE, 1, arrayOf(p),
                arrayOf(c), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
        assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true)); e.recycle()
    }
    /** Keys whose platter is drawn white: focused, pressed or hovered. */
    private fun litKeys(): List<String> {
        val image = instrumentation.uiAutomation.takeScreenshot()!!
        val lit = ArrayList<String>()
        try {
            for (key in keyboardKeys()) {
                val tag = key.config[SemanticsProperties.TestTag]
                val surface = searchBounds("$tag:surface")
                // A point inside the platter, clear of the centred caption.
                val pixel = image.getPixel(surface.left + surface.width() / 5, surface.centerY())
                if (android.graphics.Color.red(pixel) > 200 && android.graphics.Color.green(pixel) > 200 && android.graphics.Color.blue(pixel) > 200) lit.add(tag)
            }
        } finally { image.recycle() }
        return lit
    }
    @Test @Throws(Exception::class) fun onlyTheFocusedKeyLooksFocusedWhileResultsArrive() {
        // Keys the remote typed on or passed over kept their focused white once results started to arrive.
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); AppHarness.waitUntil("keyboard", 5000) { keyboardShown() }
            focusSearch("keyboard:a")
            var row = 0; var column = 0
            for (c in "film") {
                val i = c - 'a'
                while (row < i / 6) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); row++; SystemClock.sleep(150) }
                while (row > i / 6) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); row--; SystemClock.sleep(150) }
                while (column < i % 6) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); column++; SystemClock.sleep(150) }
                while (column > i % 6) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); column--; SystemClock.sleep(150) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); SystemClock.sleep(400)
            }
            AppHarness.waitUntil("results", 10000) { visible("Film 00") && query() == "film" }
            // From m: up to g, right to h and i, down to o.
            for (code in intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)) {
                instrumentation.sendKeyDownUpSync(code); SystemClock.sleep(200)
            }
            AppHarness.waitUntil("focus on o", 5000) { focus() == "keyboard:o" }
            SystemClock.sleep(600) // Let focus growth and fades finish.
            assertEquals("Only the focused key is drawn focused", listOf("keyboard:o"), litKeys())
        }
    }
    @Test @Throws(Exception::class) fun remoteAndPointerSwitchModesAnimateAndPreserveTheQueryAndSelectedFilm() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Film 00") }
            tap("Search", true); browse!!.onActivity { activity -> activity.searchInput("Film") }
            searchWait("split search results", 10000) { visible("Film 00") && columns() == 4 }
            SystemClock.sleep(350) // All provider callbacks finish before changing the retained view mode.
            val split = resultsBounds()
            focusSearch("keyboard:f")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            AppHarness.waitUntil("results expand after Right", 5000) { columns() == 6 && !keyboardShown() && focus().startsWith("search:[") }
            assertTrue("Grid uses the keyboard's former width", resultsBounds().width() > split.width() + dp(180))
            val target = "search:[\"" + services.store.addons()[0].id + "\",\"movie\",\"browse:36\"]"
            browse!!.onActivity { activity -> (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).searchState!!.scrollToResult(36) }
            AppHarness.waitUntil("later result bound", 5000) { has(target) }
            focusSearch(target)
            screenshot("validation-search-full.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            AppHarness.waitUntil("Left reveals keyboard", 5000) { keyboardShown() && columns() == 4 && focus() == "keyboard:f" }
            assertEquals(split.width().toFloat(), resultsBounds().width().toFloat(), dp(2).toFloat())
            assertTrue(visible("Film")); screenshot("validation-search-keyboard.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            AppHarness.waitUntil("Right returns to the same result", 5000) { !keyboardShown() && columns() == 6 && focus() == target }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            AppHarness.waitUntil("details", 10000) { visible("Play") }; tap("Back", false)
            searchWait("Back restores full search and its selected film", 5000) { columns() == 6 && !keyboardShown() && focus() == target }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            AppHarness.waitUntil("Left brings the keyboard back", 5000) { columns() == 4 && keyboardShown() && focus().startsWith("keyboard:") }
            // A pointer has no way to bring the keyboard back, so tapping a result keeps it while the film opens.
            tap("Film 36", true)
            AppHarness.waitUntil("pointer opens the film", 10000) { visible("Play") }; tap("Back", true)
            AppHarness.waitUntil("the keyboard stayed", 5000) { columns() == 4 && keyboardShown() }
            assertEquals("Mode switches and Back never repeat the search", 1, server.searchRequests.get().toLong())
        }
    }
    private fun searchWait(label: String, timeout: Long, ready: () -> Boolean) {
        try { AppHarness.waitUntil(label, timeout, java.util.function.BooleanSupplier(ready)) }
        catch (failure: AssertionError) {
            val before = searchDiagnostic()
            val tree = semanticNodes { true }.joinToString("\n") { node -> "${node.config} bounds=${node.boundsInWindow}" }
            throw AssertionError(failure.message + "\nSearch state: " + before + "\n" + tree, failure)
        }
    }
    private fun searchDiagnostic(): String {
        val result = AtomicReference("no activity")
        browse!!.onActivity { activity ->
            val state = (activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).searchState
            result.set(if (state == null) "state=null" else "query=${state.query}, tiles=${state.item.tiles.size}, loading=${state.item.loading}, columns=${state.columns}, frame=${state.frame}, focus=${activity.currentFocus?.tag}")
        }
        return result.get() + ", visibleFilm=" + visible("Film 00")
    }
    @org.junit.After fun clearSearchObserver() { SearchBlocks.drawn = null }
    private fun frame(): SearchBlocks.Frame {
        val value = AtomicReference<SearchBlocks.Frame>()
        browse!!.onActivity { activity -> value.set((activity.findViewById<RecyclerView>(R.id.page_rows).adapter as PageAdapter).searchState?.frame) }
        return value.get() ?: throw AssertionError("Search has drawn a frame")
    }
    private fun columns(): Int = runCatching { frame().columns }.getOrDefault(0)
    private fun keyboardShown(): Boolean = shownKey(Rect()) { it == "search-keyboard" }
    private fun has(key: String): Boolean = shownControl(key)
    private fun resultsBounds(): Rect = searchBounds("search-results")
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
