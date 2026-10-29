package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test

/** Real navigation and keyboard geometry on an isolated fixture-only installation. */
class BrowsePolishTest : AppHarness() {
    @Test @Throws(Exception::class) fun keyboardRowsAlignAndEmptyScreensStayNavigable() {
        launch(); waitUntil("empty Home", 10_000) { visible("Make yourself at home") }
        screenshot("validation-empty-home.png")
        tap("Library", true); waitUntil("empty Library", 5000) { visible("Your library is empty") }
        screenshot("validation-empty-library.png")
        tap("Search", true); waitUntil("keyboard", 5000) { visible("Type a") }
        keyboardLift()
        alignedKeys("a"); screenshot("validation-search-keyboard.png")
        tap("Show 123 keys", false); alignedKeys("1")
        tap("Show àéü keys", true); alignedKeys("à")
        tap("Type à", false)
        waitUntil("accent key enters text", 5000) { visible("à") }
        tap("Clear search", true)
        waitUntil("clear keeps keyboard usable", 5000) { visible("Type à") }
    }
    @Test @Throws(Exception::class) fun openingFilmFocusesPlayAndReturningRestoresTheChosenAction() {
        readyLibrary(); launch(); tap("Generated Film", true)
        waitUntil("Play automatically focused", 10_000) { focused("play") }
        SystemClock.sleep(1200)
        assertTrue("Enrichment preserves Play focus", focused("play"))
        screenshot("validation-film-play-focus.png")
        tap("Sources", false)
        waitUntil("source chooser", 10_000) { !focused("play") && visible("Sources") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("return restores Sources instead of stealing focus", 10_000) { focused("sources") }
    }
    @Test @Throws(Exception::class) fun everyKeyboardLanguageFillsItsLetterSymbolAndAccentRows() {
        val codes: Array<String> = tv.stream.app.ui.KeyboardLanguages.CODES
        services.store.setPreference("keyboardLanguages", codes.joinToString(","))
        launch(); waitUntil("Home", 10000) { visible("Make yourself at home") }
        tap("Search", true); waitUntil("keyboard", 5000) { visible("Type a") }
        for (i in codes.indices) {
            val code = codes[i]; val letters = tv.stream.app.ui.KeyboardLanguages.alphabet(code)
            waitUntil("$code keyboard", 5000) {
                val current = AtomicBoolean()
                browse!!.onActivity { activity ->
                    val globe = activity.findViewById<View>(R.id.page_rows).findViewWithTag<android.widget.TextView>("keyboard:Language")
                    current.set(globe != null && globe.text.toString() == tv.stream.app.ui.KeyboardLanguages.name(code))
                }; current.get()
            }
            alignedKeys(letters.substring(0, 1))
            tap("Show 123 keys", false); alignedKeys("1")
            tap("Show àéü keys", true); alignedKeys(tv.stream.app.ui.KeyboardLanguages.accents(code).substring(0, 1))
            tap("Show " + letters.substring(0, 3) + " keys", false)
            if (code == "ru") screenshot("validation-keyboard-russian-filled.png")
            if (i < codes.size - 1) tap("Change keyboard language", true)
        }
        // Editing on the longer alphabet still uses the same query after several layout rebuilds.
        tap("Type ї", false); waitUntil("Ukrainian input", 5000) { searchText() == "ї" }
        tap("Delete last character", true); waitUntil("deletion preserves keyboard", 5000) { visible("Type ї") && searchText() != "ї" }
    }
    private fun searchText(): String {
        val text = java.util.concurrent.atomic.AtomicReference<String>()
        browse!!.onActivity { activity -> text.set((activity.findViewById<View>(R.id.search_query) as android.widget.TextView).text.toString()) }
        return text.get()
    }
    private fun focused(tag: String): Boolean {
        val result = AtomicBoolean()
        browse!!.onActivity { activity -> val focus = activity.currentFocus; result.set(focus != null && tag == focus.tag) }
        return result.get()
    }
    private fun alignedKeys(first: String) {
        instrumentation.waitForIdleSync()
        browse!!.onActivity { activity ->
            val row = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:$first").parent as ViewGroup
            val keys = row.parent as ViewGroup
            val edge = row.getChildAt(row.childCount - 1).right
            for (i in 0 until keys.childCount) {
                val other = keys.getChildAt(i) as ViewGroup
                assertEquals("Each row begins in the same column", row.left.toLong(), other.left.toLong())
                assertEquals("Each row ends in the same column", edge.toLong(), other.getChildAt(other.childCount - 1).right.toLong())
                for (j in 0 until other.childCount) {
                    val key = other.getChildAt(j)
                    // Delete is an icon key; every other key carries its caption.
                    val icon = "Delete last character".contentEquals(key.contentDescription!!)
                    assertTrue("Every cell is an actual key, with no blank spacers", (icon || key is android.widget.TextView) && key.isFocusable)
                    assertEquals("Different label sizes must not offset key backgrounds", other.getChildAt(0).top.toLong(), key.top.toLong())
                    assertEquals("Keys share a height", row.getChildAt(0).height.toLong(), key.height.toLong())
                    assertEquals("Focus growth preserves each key's proportions", key.scaleX, key.scaleY, 0.001f)
                    if (!icon) assertEquals("Key captions fit", 0, (key as android.widget.TextView).layout.getEllipsisCount(0).toLong())
                    if (j > 0) assertEquals("Only the designed gap separates keys", tv.stream.app.ui.Tv.dp(context, 4f).toLong(), (key.left - other.getChildAt(j - 1).right).toLong())
                }
            }
        }
    }
    @Throws(Exception::class)
    private fun keyboardLift() {
        hover(tv.stream.app.ui.Tv.dp(context, 700f).toFloat(), tv.stream.app.ui.Tv.dp(context, 300f).toFloat())
        val before = Rect()
        browse!!.onActivity { activity ->
            val key = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:b")
            before.set(key.left, key.top, key.right, key.bottom)
            activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:a").requestFocus()
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitUntil("remote focus grows the key and releases the previous key", 3000) {
            val ready = AtomicBoolean()
            browse!!.onActivity { activity ->
                val rows = activity.findViewById<View>(R.id.page_rows)
                val key = rows.findViewWithTag<View>("keyboard:b"); val old = rows.findViewWithTag<View>("keyboard:a")
                ready.set(key.isFocused && key.scaleX > 1.03f && Math.abs(old.scaleX - 1f) < .001f)
                assertEquals("Growth leaves the keyboard grid in place", before,
                        Rect(key.left, key.top, key.right, key.bottom))
            }; ready.get()
        }
        SystemClock.sleep(250) // Compare completed growth, not different points in the focus easing.
        val letterGrowth = keyGrowth("keyboard:b")
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:Clear").requestFocus() }
        waitUntil("medium capsule focus grows", 3000) { keyScale("keyboard:Clear") > 1.01f }
        SystemClock.sleep(250)
        val actionScale = keyScale("keyboard:Clear")
        assertEquals("Medium keys add the same visible width as letters", letterGrowth,
                keyGrowth("keyboard:Clear"), tv.stream.app.ui.Tv.dp(context, .25f).toFloat())
        val language = Rect()
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:Language").requestFocus() }
        waitUntil("wide capsule focus grows too", 3000) { keyScale("keyboard:Language") > 1.01f }
        screenshot("validation-keyboard-capsule-lift.png")
        assertEquals("The full-width language key adds the same visible width as letters", letterGrowth,
                keyGrowth("keyboard:Language"), tv.stream.app.ui.Tv.dp(context, .25f).toFloat())
        assertTrue("Wider keys use a smaller scale", keyScale("keyboard:Language") < actionScale)
        browse!!.onActivity { activity ->
            val rows = activity.findViewById<View>(R.id.page_rows)
            val key = rows.findViewWithTag<View>("keyboard:Language")
            assertTrue(key.getGlobalVisibleRect(language))
            rows.findViewWithTag<View>("keyboard:b").requestFocus()
        }
        waitUntil("unfocused capsule returns to rest", 3000) { Math.abs(keyScale("keyboard:Language") - 1f) < .001f }
        hover(language.centerX().toFloat(), language.centerY().toFloat())
        waitUntil("pointer hover grows the capsule", 3000) { keyScale("keyboard:Language") > 1.01f }
        assertTrue("Hover preserves the remote's focused letter", focused("keyboard:b"))
        screenshot("validation-keyboard-hover-lift.png")
        assertEquals("Hover uses the same size-aware growth as remote focus", letterGrowth,
                keyGrowth("keyboard:Language"), tv.stream.app.ui.Tv.dp(context, .25f).toFloat())
        hover(tv.stream.app.ui.Tv.dp(context, 700f).toFloat(), tv.stream.app.ui.Tv.dp(context, 300f).toFloat())
        waitUntil("pointer exit restores the capsule", 3000) { Math.abs(keyScale("keyboard:Language") - 1f) < .001f }
        assertTrue(focused("keyboard:b"))
    }
    private fun keyScale(tag: String): Float {
        val value = java.util.concurrent.atomic.AtomicReference<Float>()
        browse!!.onActivity { activity -> value.set(activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(tag).scaleX) }
        return value.get()
    }
    private fun keyGrowth(tag: String): Float {
        val value = java.util.concurrent.atomic.AtomicReference<Float>()
        browse!!.onActivity { activity ->
            val key = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(tag)
            assertEquals("Growth keeps captions and circles in proportion", key.scaleX, key.scaleY, .001f)
            value.set(key.width * (key.scaleX - 1f))
        }
        return value.get()
    }
    private fun hover(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val pointer = android.view.MotionEvent.PointerProperties()
        pointer.id = 0; pointer.toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
        val point = android.view.MotionEvent.PointerCoords(); point.x = x; point.y = y
        val event = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_HOVER_MOVE,
                1, arrayOf(pointer), arrayOf(point),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
    }
    @Throws(Exception::class)
    private fun screenshot(name: String) {
        SystemClock.sleep(400) // Capture after the page entrance transition.
        val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(bitmap)
        FileOutputStream(File(context.filesDir, name)).use { out -> bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap!!.recycle()
    }
}
