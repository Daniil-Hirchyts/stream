package tv.stream.app

import org.junit.Assert.*
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.json.JSONArray
import org.junit.Test

/** Page motion and remote focus: cached pages animate in, rows are reached one at a time, focus is always drawn. */
class PageNavigationTest : AppHarness() {
    @Test @Throws(Exception::class) fun tabsPushBackSearchAndCachedHomeAnimateAfterTheirContentIsLaidOut() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }; SystemClock.sleep(350)
        motion(Consumer { activity -> activity.root("Settings") }, 0)
        motion(Consumer { activity -> activity.push(Route("Keyboard languages")) }, 1)
        motion(Consumer { activity -> activity.back() }, -1)
        motion(Consumer { activity -> activity.root("Home") }, 0)
        motion(Consumer { activity -> activity.search() }, 0)
        motion(Consumer { activity -> activity.back() }, 0)
    }
    /**
     * Up walks back through every shelf the remote came down through, one per press, even after the rows above have
     * scrolled away; only the first shelf hands focus to the top corner's Refresh.
     */
    @Test @Throws(Exception::class) fun upMovesOneHomeShelfAtATimeBeforeTheToolbar() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            server.catalogCount = 7
            services.addons.newRequest().use { scope ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope))
            }
            launch()
            AppHarness.waitUntil("Home shelves", 10000) { visible("Film 00") && focusedRow() >= 0 }
            walkRowsDownThenUp(R.id.home_refresh, 7)
        }
    }

    /** The same on a catalogue grid: Up reaches each row in turn, then its filters, and only then Back. */
    @Test @Throws(Exception::class) fun upMovesOneCatalogueRowAtATimeBeforeBack() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { scope ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope))
            }
            launch(); AppHarness.waitUntil("Home", 10000) { focusedRow() >= 0 }
            val addon = services.store.addons()[0].id
            browse!!.onActivity { a -> a.push(Route("Catalogue").also { it.value = JSONArray().put(addon).put("movie").put("browse").toString() }) }
            AppHarness.waitUntil("Catalogue grid", 10000) { visible("Film 00") && focusedRow() >= 0 }
            walkRowsDownThenUp(R.id.page_back, 4)
        }
    }

    /**
     * Settings always opens with a lit category: by remote from the rail, again after visiting Home the same way, and
     * directly. Lit means drawn focused (the platter grows), not only focused in the accessibility tree.
     */
    @Test @Throws(Exception::class) fun settingsOpensWithItsFirstCategoryFocused() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        openFromRail("nav:Settings")
        val lit = waitFor(5000) { focusTag() == "settings:addons" && drawnFocused("settings:addons") }
        assertTrue("Settings opens on its first category, drawn focused: ${describe("settings:addons")}", lit)
        key(KeyEvent.KEYCODE_DPAD_DOWN); AppHarness.waitUntil("Down moves through the categories", 5000) { focusTag() == "settings:torrents" && drawnFocused("settings:torrents") }
        openFromRail("nav:Home"); AppHarness.waitUntil("Home again", 10000) { visible("Generated Film") && focusedRow() >= 0 && focusTag()?.startsWith("settings:") != true }
        openFromRail("nav:Settings")
        assertTrue("Returning to Settings lights a category: ${describe(focusTag() ?: "")}", waitFor(5000) {
            val tag = focusTag() ?: ""; tag.startsWith("settings:") && drawnFocused(tag) })
        browse!!.onActivity { a -> a.root("Library") }; AppHarness.waitUntil("Library", 10000) { focusTag()?.startsWith("settings:") != true }
        browse!!.onActivity { a -> a.root("Settings") }
        assertTrue("Settings opened directly lights a category: ${describe(focusTag() ?: "")}", waitFor(5000) {
            val tag = focusTag() ?: ""; tag.startsWith("settings:") && drawnFocused(tag) })
    }
    /**
     * Every page opened shows a lit control without a key press: each tab reached by remote from the rail (Search's
     * keyboard was left with focus on the bare page list), Search opened directly, and a title's details.
     */
    @Test @Throws(Exception::class) fun everyOpenedPageFocusesAControl() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") && focusedRow() >= 0 }
        for (tab in listOf("nav:search", "nav:Browse", "nav:Library", "nav:Settings", "nav:Home", "nav:search")) {
            openFromRail(tab)
            assertControlFocused(tab)
            if (tab == "nav:search") {
                assertTrue("Search starts on its keyboard: ${focusTag()}", focusTag()?.startsWith("keyboard:") == true)
                // Left stays in the keyboard (Search never reveals the rail); Back leaves it, and that page is lit too.
                key(KeyEvent.KEYCODE_BACK); AppHarness.waitUntil("Search closed", 5000) { focusTag()?.startsWith("keyboard:") != true }
                assertControlFocused("The page Back returns to from Search")
            }
        }
        browse!!.onActivity { a -> a.root("Home") }; AppHarness.waitUntil("Home again", 10000) { visible("Generated Film") }
        browse!!.onActivity { a -> a.search() }; assertControlFocused("Search opened directly")
        browse!!.onActivity { a -> a.root("Home") }; AppHarness.waitUntil("Home again", 10000) { visible("Generated Film") }
        tap("Generated Film", false); AppHarness.waitUntil("Details", 10000) { visible("Play") || visible("Resume") }
        assertControlFocused("Details")
    }
    /** Focus is in the page (not the rail, not the bare list), and a composition's control reports itself focused. */
    private fun assertControlFocused(page: String) {
        var detail = ""
        val lit = waitFor(5000) {
            val tag = focusTag(); val id = focusId()
            detail = "focus $tag id $id"
            if (tag == null || tag.startsWith("nav:") || id == R.id.page_rows) return@waitFor false
            val node = semanticNode(tag) ?: return@waitFor true
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false }
        }
        assertTrue("$page opens with a focused control ($detail)", lit)
    }
    /**
     * Quick remote moves across and between shelves leave exactly the focused poster grown, its artwork included, with
     * its caption riding below the grown artwork; every other poster settles back to its size.
     */
    @Test @Throws(Exception::class) fun focusedPosterArtworkGrowsWithItsCaption() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            server.catalogCount = 3
            services.addons.newRequest().use { scope ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope))
            }
            launch(); AppHarness.waitUntil("Home shelves", 10000) { visible("Film 00") && focusedRow() >= 0 }
            val moves = intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            for ((index, code) in moves.withIndex()) {
                instrumentation.sendKeyDownUpSync(code); SystemClock.sleep(if (index % 2 == 0) 40L else 140L)
                if (index % 4 == 3) { instrumentation.waitForIdleSync(); assertPosterGrown() }
            }
            instrumentation.waitForIdleSync(); assertPosterGrown()
        }
    }
    private fun assertPosterGrown() {
        var detail = ""
        val settled = waitFor(3000) {
            val focus = focusTag() ?: return@waitFor false
            val art = semanticNode("$focus:art"); val title = semanticNode("$focus:title")
            if (art == null || title == null) { detail = "no artwork for $focus"; return@waitFor false }
            val ratio = scale(art)
            val stale = semanticNodes { node ->
                val tag = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }
                tag.endsWith(":art") && tag != "$focus:art" && grown(node)
            }.map { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } }
            detail = "focus $focus, scale $ratio, caption top ${title.boundsInRoot.top} artwork bottom ${art.boundsInRoot.bottom}, still grown $stale"
            Math.abs(ratio - tv.stream.app.ui.Tv.LIFT_TILE) < .01f && title.boundsInRoot.top >= art.boundsInRoot.bottom - 1f && stale.isEmpty()
        }
        assertTrue("Only the focused poster's artwork is grown, its caption below it ($detail)", settled)
    }

    /** Left into the rail, then Up or Down to `tab` and OK, as a viewer does. */
    private fun openFromRail(tab: String) {
        key(KeyEvent.KEYCODE_DPAD_LEFT); AppHarness.waitUntil("Rail", 5000) { focusTag()?.startsWith("nav:") == true }
        val order = listOf("nav:search", "nav:Home", "nav:Browse", "nav:Library", "nav:Settings")
        for (i in 0 until 6) {
            val at = order.indexOf(focusTag()); val want = order.indexOf(tab)
            if (at == want) break
            key(if (at < want) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
        assertEquals(tab, focusTag())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    /** A control is drawn focused when its focus growth applies: its layer-transformed bounds exceed its size. */
    private fun drawnFocused(tag: String): Boolean {
        val node = semanticNode(tag) ?: return false
        return node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false } && grown(node)
    }
    private fun describe(tag: String): String {
        val nodes = semanticNodes { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } == tag }
        return "focus ${focusTag()}; " + nodes.joinToString { n -> "focused=" + n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false } +
                " bounds=" + n.boundsInRoot + " size=" + n.size }
    }
    /** Focus growth from layer-transformed bounds; an ancestor's clip may trim one axis (a column's width), not both. */
    private fun scale(node: androidx.compose.ui.semantics.SemanticsNode): Float =
            Math.max(node.boundsInRoot.width / node.size.width, node.boundsInRoot.height / node.size.height)
    private fun grown(node: androidx.compose.ui.semantics.SemanticsNode): Boolean = scale(node) > 1.004f

    /** From the page's first control, Down to the last row, then Up through exactly the same rows to `top`. */
    private fun walkRowsDownThenUp(top: Int, atLeast: Int) {
        val first = focusedRow()
        // Start at the top row of controls.
        for (i in 0 until 12) { if (focusedRow() <= firstFocusableRow()) break; key(KeyEvent.KEYCODE_DPAD_UP) }
        val rows = ArrayList<Int>(); rows.add(focusedRow())
        assertTrue("Starts in the page ($first)", rows[0] >= 0)
        while (rows.size < 40) {
            val before = focusedRow(); key(KeyEvent.KEYCODE_DPAD_DOWN)
            val moved = waitFor(1500) { focusedRow() != before }
            if (!moved) break
            assertTrue("Down moves to a lower row", focusedRow() > before)
            rows.add(focusedRow())
        }
        assertTrue("The page has at least $atLeast rows of controls: $rows", rows.size >= atLeast)
        assertTrue("The page scrolled", scrolled())
        for (i in rows.size - 2 downTo 0) {
            val before = focusedRow(); key(KeyEvent.KEYCODE_DPAD_UP)
            waitFor(1500) { focusedRow() != before }
            assertEquals("Up from row $before reaches the row above it (rows $rows, focus ${focusTag()} id ${focusId()})", rows[i], focusedRow())
        }
        // Held or quick presses arrive while the page is still scrolling to the row before.
        for (i in 1 until rows.size) key(KeyEvent.KEYCODE_DPAD_DOWN)
        AppHarness.waitUntil("Back at the last row", 5000) { focusedRow() == rows.last() }
        for (i in 1 until rows.size) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); SystemClock.sleep(90) }
        instrumentation.waitForIdleSync()
        assertEquals("Quick Up presses still stop at every row (focus ${focusTag()})", rows[0], focusedRow())
        key(KeyEvent.KEYCODE_DPAD_UP)
        val reached = waitFor(3000) { focusId() == top }
        assertTrue("Only Up from the first row reaches the top corner (rows $rows, focus ${focusTag()} in row ${focusedRow()})", reached)
    }

    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync() }
    private fun waitFor(timeout: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return true; SystemClock.sleep(30) }
        return condition()
    }
    private fun focusTag(): String? { val tag = AtomicReference<String?>(); browse!!.onActivity { a -> tag.set(a.currentFocus?.tag as? String) }; return tag.get() }
    private fun focusId(): Int { val id = AtomicReference(0); browse!!.onActivity { a -> id.set(a.currentFocus?.id ?: 0) }; return id.get() }
    private fun scrolled(): Boolean { val yes = AtomicBoolean(); browse!!.onActivity { a -> yes.set(a.findViewById<RecyclerView>(R.id.page_rows).canScrollVertically(-1)) }; return yes.get() }
    /** The adapter position of the page block holding focus, or -1 outside the page. */
    private fun focusedRow(): Int {
        val row = AtomicReference(-1)
        browse!!.onActivity { a ->
            val rows = a.findViewById<RecyclerView>(R.id.page_rows); val focus = a.currentFocus
            val item = if (focus == null) null else rows.findContainingItemView(focus)
            row.set(if (item == null) -1 else rows.getChildAdapterPosition(item))
        }
        return row.get()
    }
    private fun firstFocusableRow(): Int {
        val row = AtomicReference(-1)
        browse!!.onActivity { a ->
            val rows = a.findViewById<RecyclerView>(R.id.page_rows)
            for (i in 0 until rows.childCount) {
                val child = rows.getChildAt(i); val position = rows.getChildAdapterPosition(child)
                val offered = ArrayList<View>().also { child.addFocusables(it, View.FOCUS_DOWN) }
                if (offered.isNotEmpty() && (row.get() < 0 || position < row.get())) row.set(position)
            }
        }
        return row.get()
    }

    private fun motion(navigate: Consumer<MainActivity>, direction: Int) {
        val movingContent = AtomicBoolean()
        val observer = AtomicReference<ViewTreeObserver.OnDrawListener>()
        browse!!.onActivity { activity ->
            val rows = activity.findViewById<RecyclerView>(R.id.page_rows)
            observer.set(ViewTreeObserver.OnDrawListener {
                val distance = if (direction == 0) rows.translationY else direction * rows.translationX
                if (rows.childCount > 0 && rows.alpha > 0.01f && rows.alpha < .99f && distance > 1f) movingContent.set(true)
            })
            rows.viewTreeObserver.addOnDrawListener(observer.get()); navigate.accept(activity)
        }
        AppHarness.waitUntil("visible page slides and fades in direction $direction", 5000) { movingContent.get() }
        AppHarness.waitUntil("page transition settles", 5000) {
            val settled = AtomicBoolean()
            browse!!.onActivity { activity ->
                val rows = activity.findViewById<View>(R.id.page_rows)
                settled.set(rows.alpha == 1f && rows.translationX == 0f && rows.translationY == 0f)
            }
            settled.get()
        }
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).viewTreeObserver.removeOnDrawListener(observer.get()) }
    }
}
