package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.app.ui.SubtitlePreviewView

class SubtitlePagePreviewTest : AppHarness() {
    @Test @Throws(Exception::class) fun choicesUseOnePagePreviewAndCancelRestoresItWhileSelectionCommits() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }; tap("Settings", true); tap("Subtitles", false)
        AppHarness.waitUntil("subtitle settings", 5000) { visible("Subtitle color") }
        assertTrue("Effective default value is named", visible("Medium")); assertFalse(visible("Default"))
        assertTrue("Same terminology as the player", visible("Subtitle background")); assertTrue(visible("Dark box"))
        val before = drawPreview()
        try {
            tap("Subtitle color", true); AppHarness.waitUntil("colour choices", 5000) { visible("White") }; SystemClock.sleep(350)
            val screen = instrumentation.uiAutomation.takeScreenshot()
            java.io.FileOutputStream(java.io.File(context.filesDir, "validation-settings-single-preview.png")).use { out -> screen.compress(Bitmap.CompressFormat.PNG, 100, out) }; screen.recycle()
            instrumentation.runOnMainSync {
                val root: View? = WindowInspector.getGlobalWindowViews().stream().map<View> { window -> window.findViewWithTag("popup-root") }.filter { view -> view != null }.findFirst().orElse(null)
                assertNotNull(root); assertEquals("No duplicate preview inside the popup", 0, previews(root!!).toLong())
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); SystemClock.sleep(150)
            val preview = drawPreview(); assertFalse("Focus updates the existing page preview", before.sameAs(preview)); preview.recycle()
            assertEquals("Focus does not save", "", services.playerPreferences.getString("subtitle_color", ""))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            AppHarness.waitUntil("Settings window returns", 5000) { visible("Subtitle color") }; SystemClock.sleep(250)
            val cancelled = drawPreview(); assertTrue("Back restores the saved look", before.sameAs(cancelled)); cancelled.recycle()
            tap("Subtitle color", false); tap("Yellow", true)
            AppHarness.waitUntil("colour saved", 5000) { services.playerPreferences.getString("subtitle_color", "") == "yellow" }
            app.persistence.submit(Runnable { }).get(); assertEquals("yellow", services.store.preference("subtitle_color", ""))
            AppHarness.waitUntil("Settings value updated", 5000) { visible("Subtitle color") && visible("Yellow") }
            val committed = drawPreview(); assertFalse(before.sameAs(committed)); committed.recycle()
        } finally { before.recycle() }
    }
    private fun drawPreview(): Bitmap {
        val image = AtomicReference<Bitmap>(); browse!!.onActivity { activity ->
            val preview = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("subtitle-preview"); assertNotNull(preview)
            val bitmap = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888); preview.draw(Canvas(bitmap)); image.set(bitmap)
        }; return image.get()
    }
    private fun previews(view: View): Int { var count = if (view is SubtitlePreviewView) 1 else 0; if (view is ViewGroup) for (i in 0 until view.childCount) count += previews(view.getChildAt(i)); return count }
}
