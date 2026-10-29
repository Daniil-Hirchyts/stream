package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.media3.common.text.Cue
import androidx.media3.ui.StreamSubtitleOutput
import androidx.media3.ui.SubtitleView
import java.util.Collections
import org.junit.Test
import tv.stream.playback.SubtitleStyle

/** Render real Media3 output on Android, including wrapping, style changes and bitmap cues. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SubtitleBoxTest : AppHarness() {
    @Test fun multilineBoxIsOneRoundedContourFollowingTheLineWidths() {
        instrumentation.runOnMainSync {
            val view = SubtitleView(context); StreamSubtitleOutput.install(view)
            SubtitleStyle.apply(view, 1, 0, 0, 2, 1)
            view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32f)
            view.setBottomPaddingFraction(0.1f)
            view.setCues(Collections.singletonList(Cue.Builder()
                    .setText("A longer first subtitle line\nShort line\nAnd a medium final line").build()))
            var image = draw(view); var bounds = pixels(image, false)
            assertFalse(bounds.isEmpty)
            val middle = rowBounds(image, bounds.centerY())
            assertTrue("The shape follows the short middle line instead of filling a rectangle",
                    rowBounds(image, bounds.top + bounds.height() / 6).width() > middle.width() + 100)
            for (y in bounds.top + 8 until bounds.bottom - 8) {
                assertTrue("Adjacent rows join without notches cutting into the common body at $y",
                        Color.alpha(image.getPixel(middle.left + 2, y)) >= 170)
                for (x in bounds.left until bounds.right) {
                    val pixel = image.getPixel(x, y)
                    if (Color.red(pixel) == 0 && Color.alpha(pixel) > 20)
                        assertTrue("The unified translucent fill has no darker overlapping seams", Color.alpha(pixel) <= 180)
                }
            }
            val evidence = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            val scene = Canvas(evidence); scene.drawColor(0xFF5B616B.toInt()); view.draw(scene)
            try {
                java.io.FileOutputStream(java.io.File(context.filesDir, "validation-connected-subtitles.png")).use { out ->
                    evidence.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: java.io.IOException) { throw AssertionError(e) }
            evidence.recycle(); image.recycle()

            val clipped = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            val partial = Canvas(clipped); partial.clipRect(0, 0, 640, bounds.centerY()); view.draw(partial)
            assertTrue("Clipping the final rows must not remove the visible background", hasBox(clipped))
            clipped.recycle()

            view.setCues(Collections.singletonList(Cue.Builder().setText("First subtitle row\n\nLast row").build()))
            image = draw(view); bounds = pixels(image, false)
            assertEquals("Empty rows stay inside the connected background", 179, Color.alpha(image.getPixel(bounds.centerX(), bounds.centerY())).toLong())
            image.recycle()
        }
    }

    @Test fun boxHasPaddedSidesRoundedCornersAndKeepsOtherCueTypes() {
        instrumentation.runOnMainSync {
            val view = SubtitleView(context); StreamSubtitleOutput.install(view)
            SubtitleStyle.apply(view, 1, 0, 0, 2, 1)
            view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32f)
            view.setBottomPaddingFraction(0.1f)
            view.setCues(Collections.singletonList(Cue.Builder().setText("Subtitle box").build()))
            var image = draw(view); val box = pixels(image, false); val letters = pixels(image, true)
            assertFalse("Text renders", letters.isEmpty); assertFalse(box.isEmpty)
            assertTrue("Extra space before the text", letters.left - box.left >= 7)
            assertTrue("Extra space after the text", box.right - letters.right >= 7)
            assertEquals("The corner is rounded", 0, Color.alpha(image.getPixel(box.left, box.top)).toLong())
            assertTrue("The top edge stays flat between corners", Color.alpha(image.getPixel(box.centerX(), box.top + 1)) > 100)
            assertTrue("The side remains flat, rather than a pill", Color.alpha(image.getPixel(box.left + 1, box.centerY())) > 100)
            image.recycle()

            view.setCues(Collections.singletonList(Cue.Builder().setText("First line\nSecond line wraps naturally across the screen with a longer subtitle sentence.").build()))
            image = draw(view); val multiline = pixels(image, false)
            assertTrue("Line wrapping still works", multiline.height() > box.height() * 2)
            assertTrue("Padded backgrounds stay within the viewport", multiline.left >= 0 && multiline.right <= image.width)
            image.recycle()

            SubtitleStyle.apply(view, 1, 0, 0, 0, 1)
            view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32f)
            view.setCues(Collections.singletonList(Cue.Builder().setText("Subtitle box").build()))
            image = draw(view)
            assertTrue("Outline switches off the padded box", pixels(image, false).width() < box.width()); image.recycle()

            val cueImage = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888); cueImage.eraseColor(Color.GREEN)
            SubtitleStyle.apply(view, 1, 0, 0, 2, 1)
            view.setCues(Collections.singletonList(Cue.Builder().setBitmap(cueImage).setPosition(0.5f)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE).setLine(0.5f, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE).setSize(0.25f).build()))
            image = draw(view); assertEquals("Bitmap subtitles pass through", Color.GREEN.toLong(), image.getPixel(320, 180).toLong()); image.recycle()
            view.setCues(Collections.emptyList()); image = draw(view)
            assertTrue("Clearing a cue clears its box too", pixels(image, false).isEmpty); image.recycle(); cueImage.recycle()
        }
    }

    companion object {
        private fun draw(view: SubtitleView): Bitmap {
            view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 640, 360)
            val image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888); view.draw(Canvas(image)); return image
        }
        private fun pixels(image: Bitmap, white: Boolean): Rect {
            val out = Rect()
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val color = image.getPixel(x, y)
                if (Color.alpha(color) > 20 && (!white || Color.red(color) > 220)) out.union(x, y, x + 1, y + 1)
            }
            return out
        }
        private fun rowBounds(image: Bitmap, y: Int): Rect {
            val row = Rect()
            for (x in 0 until image.width)
                if (Color.alpha(image.getPixel(x, y)) > 20) row.union(x, y, x + 1, y + 1)
            return row
        }
        private fun hasBox(image: Bitmap): Boolean {
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                if (Color.red(pixel) == 0 && Color.alpha(pixel) >= 170) return true
            }
            return false
        }
    }
}
