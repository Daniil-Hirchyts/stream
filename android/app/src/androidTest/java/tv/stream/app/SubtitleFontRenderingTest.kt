package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.StreamSubtitleOutput
import androidx.media3.ui.SubtitleView
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import org.junit.Test
import tv.stream.playback.SubtitleStyle
import tv.stream.app.ui.SubtitleFonts

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SubtitleFontRenderingTest : AppHarness() {
    @Test @Throws(Exception::class) fun settingsExposeBothBundledFontsAndPreviewCancelDoesNotSave() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }; tap("Settings", true); tap("Subtitles", false)
        assertTrue(visible("Noto Sans"))
        tap("Subtitle font", true)
        tap("Atkinson Hyperlegible Next", false)
        AppHarness.waitUntil("Atkinson saved", 5000) { "atkinson" == services.playerPreferences.getString("subtitle_font", "") }
        app.persistence.submit(Runnable { }).get(); assertEquals("atkinson", services.store.preference("subtitle_font", ""))
        returnedToFontRow()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        fontChoiceFocused(SubtitleFonts.ATKINSON)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); SystemClock.sleep(200)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        returnedToFontRow()
        assertEquals("A preview does not replace the saved font", "atkinson", services.playerPreferences.getString("subtitle_font", ""))
        returnedToFontRow()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        fontChoiceFocused(SubtitleFonts.ATKINSON)
        for (i in 0 until SubtitleFonts.ATKINSON) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        AppHarness.waitUntil("Noto saved under the compatible default key", 5000) { "standard" == services.playerPreferences.getString("subtitle_font", "") }
    }

    private fun returnedToFontRow() {
        AppHarness.waitUntil("Settings returns focus after the chooser closes", 5000) {
            val ready = java.util.concurrent.atomic.AtomicBoolean()
            browse!!.onActivity { activity ->
                ready.set(activity.hasWindowFocus() && activity.currentFocus?.tag == "subtitle-font")
            }; ready.get()
        }
    }

    private fun fontChoiceFocused(index: Int) {
        AppHarness.waitUntil("font chooser focuses its saved choice", 5000) {
            val ready = java.util.concurrent.atomic.AtomicBoolean()
            instrumentation.runOnMainSync {
                for (window in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    val option = window.findViewWithTag<View>("choice:$index")
                    if (option != null && option.hasWindowFocus() && option.hasFocus()) ready.set(true)
                }
            }; ready.get()
        }
    }

    @Test @Throws(Exception::class) fun bundledFacesAndSemanticItalicsRenderThroughMedia3AndProduceAMatchedSizeComparison() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        browse!!.onActivity { activity ->
            val root = LinearLayout(activity); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(0xFF23282E.toInt())
            val choices = intArrayOf(SubtitleFonts.SYSTEM, SubtitleFonts.NOTO, SubtitleFonts.ATKINSON)
            val names = arrayOf("Original system sans-serif", "Noto Sans Medium — default", "Atkinson Hyperlegible Next Medium — Noto Sans for Cyrillic")
            for (i in choices.indices) {
                val choice = choices[i]
                val label = TextView(activity); label.text = names[i]; label.textSize = 15f; label.setTextColor(0xFFA8B4BF.toInt()); label.setPadding(48, 14, 0, 0)
                root.addView(label, LinearLayout.LayoutParams(-1, 58))
                val subtitles = SubtitleView(activity); StreamSubtitleOutput.install(subtitles)
                val family = SubtitleFonts.typeface(activity, choice)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG); paint.typeface = family; paint.textSize = 100f
                val x = Rect(); paint.getTextBounds("x", 0, 1, x)
                assertTrue("Typeface has measurable lowercase glyphs", x.height() > 0)
                paint.typeface = SubtitleFonts.typeface(activity, SubtitleFonts.NOTO)
                assertTrue("French accents are present", paint.hasGlyph("é")); assertTrue("Cyrillic glyphs are present", paint.hasGlyph("Ж"))
                // Match lowercase height at 25 px, rather than comparing misleading identical point sizes.
                subtitles.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 2500f / x.height())
                subtitles.setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, family))
                StreamSubtitleOutput.font(subtitles, choice); StreamSubtitleOutput.configure(subtitles)
                val text = SpannableString("We should leave before sunrise.\nDéjà vu : où sont les lumières ?\nНам пора идти — до рассвета.")
                text.setSpan(StyleSpan(Typeface.ITALIC), 0, 30, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                subtitles.setCues(Collections.singletonList(Cue.Builder().setText(text).setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLine(.10f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).setSize(.9f).build()))
                root.addView(subtitles, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            activity.setContentView(root)
        }
        SystemClock.sleep(400)
        val image = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(image)
        FileOutputStream(File(context.filesDir, "validation-subtitle-font-comparison.png")).use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out) }
        image.recycle()
    }
}
