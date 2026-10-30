package tv.stream.app

import org.junit.Assert.*
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Test
import tv.stream.app.addon.Title
import tv.stream.app.ui.Item
import tv.stream.app.ui.Page
import tv.stream.app.ui.PageAdapter

class InterfacePolishTest : SearchHarness() {
    @Test @Throws(Exception::class) fun keyboardLanguagesKeepTheQueryAndFillEveryRowWithoutVoiceInput() {
        services.store.setPreference("keyboardLanguages", "en,fr,de,es,it,pt,ru,uk")
        launch(); waitUntil("Home", 10000) { visible("Make yourself at home") }
        tap("Search", true); waitUntil("English keys", 5000) { visible("Type a") }
        tap("Type a", false); tap("Type b", true)
        waitUntil("typed query", 5000) { query() == "ab" }
        val queryTop = AtomicInteger(); val keysTop = AtomicInteger()
        val shortest = AtomicInteger(Int.MAX_VALUE); val tallest = AtomicInteger()
        queryTop.set(searchBounds("search-query").top)
        keysTop.set(searchBounds("keyboard-keys").top)
        for (code in tv.stream.app.ui.KeyboardLanguages.CODES) {
            waitUntil("language $code", 5000) { visible(tv.stream.app.ui.KeyboardLanguages.name(code)) }
            assertFalse("Speech input was removed", visible("Voice input"))
            for (page in 0 until 3) {
                SystemClock.sleep(150)
                assertEquals("The query stays anchored at the top", queryTop.get().toLong(), searchBounds("search-query").top.toLong())
                val keyboard = searchBounds("keyboard-keys")
                val surface = searchBounds("keyboard-surface")
                val inset = keyboard.left - surface.left
                assertEquals("Panel padding matches horizontally and vertically", inset.toLong(), (keyboard.top - surface.top).toLong())
                assertEquals("Panel padding matches at the right edge", inset.toLong(), (surface.right - keyboard.right).toLong())
                assertEquals("Panel padding matches at the bottom edge", inset.toLong(), (surface.bottom - keyboard.bottom).toLong())
                assertEquals("Different languages and character pages grow down from the same top", keysTop.get().toLong(), keyboard.top.toLong())
                val safeBottom = AtomicInteger()
                browse!!.onActivity { activity ->
                    val rows = activity.findViewById<View>(R.id.page_rows)
                    safeBottom.set(bounds(rows).bottom - rows.paddingBottom)
                }
                assertTrue("Every key stays inside the bottom safe area", keyboard.bottom <= safeBottom.get())
                shortest.set(Math.min(shortest.get(), keyboard.height()))
                tallest.set(Math.max(tallest.get(), keyboard.height()))
                val rows = keyboardRows(); assertTrue("Keyboard has rows", rows.isNotEmpty())
                for (row in rows) {
                    assertTrue(row.isNotEmpty())
                    assertEquals("Every language/page fills the row", keyboard.right.toFloat(), row.last().boundsInWindow.right, 1f)
                }
                if (code == "de" && page == 2) screenshot("validation-keyboard-popup-short.png")
                if (code == "ru" && page == 0) screenshot("validation-keyboard-popup-long.png")
                if (page == 0) tap("Show 123 keys", false)
                else if (page == 1) tap("Show àéü keys", true)
            }
            tap("Change keyboard language", true)
        }
        assertTrue("The fixed anchor was checked across different keyboard heights", tallest.get() > shortest.get())
        assertEquals("Changing layouts preserves the query", "ab", query())
        tap("Type z", false); waitUntil("still editable", 5000) { query() == "abz" }
        screenshot("keyboard-languages.png")
    }

    @Test @Throws(Exception::class) fun clockStaysVisibleAndIconCaptionsCrossfade() {
        readyLibrary(); launch(); waitUntil("Home clock", 10000) { visible("Generated Film") && clockShown() }
        val hidden = AtomicInteger()
        val frameClock = arrayOfNulls<View>(1)
        val captionCheck = java.util.concurrent.atomic.AtomicBoolean()
        val outgoingFaded = java.util.concurrent.atomic.AtomicBoolean()
        val incomingFaded = java.util.concurrent.atomic.AtomicBoolean()
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (frameClock[0]!!.visibility != View.VISIBLE) hidden.incrementAndGet()
            true
        }
        tv.stream.app.ui.PageControls.captionDrawn = { label, alpha ->
            if (captionCheck.get()) {
                if (label == "Add favourite" && alpha > 0 && alpha < 1) outgoingFaded.set(true)
                if (label == "Mark watched" && alpha > 0 && alpha < 1) incomingFaded.set(true)
            }
        }
        browse!!.onActivity { activity -> frameClock[0] = activity.findViewById(R.id.clock)
            activity.findViewById<View>(R.id.browse_root).viewTreeObserver.addOnPreDrawListener(listener) }
        tap("Refresh", true); waitUntil("refreshed", 10000) { visible("Generated Film") }
        tap("Generated Film", false); waitUntil("details", 10000) { visible("Play") }
        SystemClock.sleep(350)
        focusSearch("favourite")
        SystemClock.sleep(350)
        captionCheck.set(true)
        focusSearch("watched")
        waitUntil("old and new icon captions fade separately", 1500) { outgoingFaded.get() && incomingFaded.get() }
        SystemClock.sleep(250); screenshot("icon-caption-transition.png")
        tap("Back", false); waitUntil("Home restored", 5000) { visible("Browse") }
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.browse_root).viewTreeObserver.removeOnPreDrawListener(listener) }
        assertEquals("No hidden-clock frame during reload or navigation", 0, hidden.get().toLong())
    }

    @Test @Throws(Exception::class) fun galleryCardsOmitYearsAndRedundantCollectionLabels() {
        val film = Title.parse("local", JSONObject().put("id", "test:clean-gallery").put("type", "movie")
                .put("name", "A Quiet Film").put("releaseInfo", "1999"))!!
        services.store.favourite(film, true); services.store.watched(film, film.id, true)
        launch(); waitUntil("favourite on Home", 10000) { visible("A Quiet Film") }
        assertFalse("Home spotlight omits the year", visible("1999"))
        val owner = AtomicReference<MainActivity>(); browse!!.onActivity { activity -> owner.set(activity) }
        val screens = BrowseScreens(owner.get(), services)
        val library: Page = screens.library(); var count = 0
        for (item: Item in library.items) for (tile: Item.Tile in item.tiles) {
            count++; assertEquals("No year, Favourite or Watched caption", "", tile.subtitle)
            assertEquals("Collection context needs no duplicate badge", 0, tile.badge.toLong())
            assertTrue("Completed history needs no full progress bar", tile.progress < 0)
        }
        assertEquals(2, count.toLong())
        assertEquals("", screens.poster("browse", film).subtitle)
        assertTrue("Year stays available in title details", PageAdapter.metaLine(film).contains("1999"))
        tap("Library", true); waitUntil("Watch history", 5000) { visible("Watch history") }
        assertFalse(visible("1999")); assertFalse(visible("Watched")); assertFalse(visible("Favourite"))
        screenshot("library-clean-captions.png")
    }
    @org.junit.After fun clearCaptionObserver() { tv.stream.app.ui.PageControls.captionDrawn = null }
    private fun query(): String = searchQuery()
    private fun bounds(view: View): Rect {
        val point = IntArray(2); view.getLocationOnScreen(point)
        return Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }
    private fun clockShown(): Boolean {
        val shown = AtomicInteger(); browse!!.onActivity { activity -> shown.set(if (activity.findViewById<View>(R.id.clock).isShown) 1 else 0) }; return shown.get() == 1
    }
    @Throws(Exception::class)
    private fun screenshot(name: String) {
        val bitmap: android.graphics.Bitmap? = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(bitmap)
        java.io.FileOutputStream(java.io.File(context.filesDir, name)).use { out -> bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
        bitmap!!.recycle()
    }
}
