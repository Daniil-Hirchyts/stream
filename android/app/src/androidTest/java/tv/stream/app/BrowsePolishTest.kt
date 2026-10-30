package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test

/** Real navigation and keyboard geometry on an isolated fixture-only installation. */
class BrowsePolishTest : SearchHarness() {
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
            waitUntil("$code keyboard", 5000) { visible(tv.stream.app.ui.KeyboardLanguages.name(code)) }
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
    private fun searchText(): String = searchQuery()
    private fun focused(tag: String): Boolean {
        val result = AtomicBoolean()
        browse!!.onActivity { activity -> val focus = activity.currentFocus; result.set(focus != null && tag == focus.tag) }
        return result.get()
    }
    private fun alignedKeys(first: String) {
        instrumentation.waitForIdleSync()
        val firstKey = searchNode("keyboard:$first")
        val rows = keyboardRows()
        val baseline = rows.first { row -> row.any { it.id == firstKey.id } }
        val left = baseline.first().boundsInWindow.left
        val right = baseline.last().boundsInWindow.right
        val height = baseline.first().layoutInfo.height
        for (row in rows) {
            assertEquals("Each row begins in the same column", left, row.first().boundsInWindow.left, 1f)
            assertEquals("Each row ends in the same column", right, row.last().boundsInWindow.right, 1f)
            for ((index, key) in row.withIndex()) {
                val tag = key.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
                val icon = key.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].contains("Delete last character")
                assertTrue("Every cell is an actual key, with no blank spacers", androidx.compose.ui.semantics.SemanticsProperties.Focused in key.config
                        && androidx.compose.ui.semantics.SemanticsActions.OnClick in key.config)
                assertEquals("Different label sizes must not offset key backgrounds", row.first().boundsInWindow.top, key.boundsInWindow.top, 1f)
                assertEquals("Keys share a height", height.toLong(), key.layoutInfo.height.toLong())
                val surface = searchNode(tag + ":surface")
                val scaleX = surface.boundsInWindow.width / surface.layoutInfo.width
                val scaleY = surface.boundsInWindow.height / surface.layoutInfo.height
                assertEquals("Focus growth preserves each key's proportions", scaleX, scaleY, .001f)
                if (!icon) assertCaptionFits(key)
                if (index > 0) assertEquals("Only the designed gap separates keys", tv.stream.app.ui.Tv.dp(context, 4f).toFloat(),
                        key.boundsInWindow.left - row[index - 1].boundsInWindow.right, 1f)
            }
        }
    }
    @Throws(Exception::class)
    private fun keyboardLift() {
        hover(tv.stream.app.ui.Tv.dp(context, 700f).toFloat(), tv.stream.app.ui.Tv.dp(context, 300f).toFloat())
        val before = Rect()
        before.set(searchBounds("keyboard:b"))
        focusSearch("keyboard:a")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitUntil("remote focus grows the key and releases the previous key", 3000) {
            assertEquals("Growth leaves the keyboard grid in place", before, searchBounds("keyboard:b"))
            focused("keyboard:b") && keyScale("keyboard:b") > 1.03f && kotlin.math.abs(keyScale("keyboard:a") - 1f) < .001f
        }
        SystemClock.sleep(250) // Compare completed growth, not different points in the focus easing.
        val letterGrowth = keyGrowth("keyboard:b")
        focusSearch("keyboard:Clear")
        waitUntil("medium capsule focus grows", 3000) { keyScale("keyboard:Clear") > 1.01f }
        SystemClock.sleep(250)
        val actionScale = keyScale("keyboard:Clear")
        assertEquals("Medium keys add the same visible width as letters", letterGrowth,
                keyGrowth("keyboard:Clear"), tv.stream.app.ui.Tv.dp(context, .25f).toFloat())
        val language = Rect()
        focusSearch("keyboard:Language")
        waitUntil("wide capsule focus grows too", 3000) { keyScale("keyboard:Language") > 1.01f }
        screenshot("validation-keyboard-capsule-lift.png")
        assertEquals("The full-width language key adds the same visible width as letters", letterGrowth,
                keyGrowth("keyboard:Language"), tv.stream.app.ui.Tv.dp(context, .25f).toFloat())
        assertTrue("Wider keys use a smaller scale", keyScale("keyboard:Language") < actionScale)
        language.set(searchBounds("keyboard:Language"))
        assertFalse("Language control remains visible", language.isEmpty)
        focusSearch("keyboard:b")
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
        val key = searchNode(tag + ":surface")
        return key.boundsInWindow.width / key.layoutInfo.width
    }
    private fun keyGrowth(tag: String): Float {
        val key = searchNode(tag + ":surface")
        val scaleX = key.boundsInWindow.width / key.layoutInfo.width
        val scaleY = key.boundsInWindow.height / key.layoutInfo.height
        assertEquals("Growth keeps captions and circles in proportion", scaleX, scaleY, .001f)
        return key.boundsInWindow.width - key.layoutInfo.width
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
