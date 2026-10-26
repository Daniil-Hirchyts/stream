package tv.daniil.stream;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.DaniilSubtitleOutput;
import androidx.media3.ui.SubtitleView;
import java.util.Collections;
import org.junit.Test;
import tv.daniil.playback.SubtitleStyle;

/** Render real Media3 output on Android, including wrapping, style changes and bitmap cues. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class SubtitleBoxTest extends AppHarness {
    @Test public void multilineBoxIsOneRoundedContourFollowingTheLineWidths() {
        instrumentation.runOnMainSync(() -> {
            SubtitleView view = new SubtitleView(context); DaniilSubtitleOutput.install(view);
            SubtitleStyle.apply(view, 1, 0, 0, 2, 1);
            view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32);
            view.setBottomPaddingFraction(0.1f);
            view.setCues(Collections.singletonList(new Cue.Builder()
                    .setText("A longer first subtitle line\nShort line\nAnd a medium final line").build()));
            Bitmap image = draw(view); Rect bounds = pixels(image, false);
            assertFalse(bounds.isEmpty());
            Rect middle = rowBounds(image, bounds.centerY());
            assertTrue("The shape follows the short middle line instead of filling a rectangle",
                    rowBounds(image, bounds.top + bounds.height() / 6).width() > middle.width() + 100);
            for (int y = bounds.top + 8; y < bounds.bottom - 8; y++) {
                assertTrue("Adjacent rows join without notches cutting into the common body at " + y,
                        Color.alpha(image.getPixel(middle.left + 2, y)) >= 170);
                for (int x = bounds.left; x < bounds.right; x++) {
                    int pixel = image.getPixel(x, y);
                    if (Color.red(pixel) == 0 && Color.alpha(pixel) > 20)
                        assertTrue("The unified translucent fill has no darker overlapping seams", Color.alpha(pixel) <= 180);
                }
            }
            Bitmap evidence = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
            Canvas scene = new Canvas(evidence); scene.drawColor(0xFF5B616B); view.draw(scene);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), "validation-connected-subtitles.png"))) {
                evidence.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (java.io.IOException e) { throw new AssertionError(e); }
            evidence.recycle(); image.recycle();

            Bitmap clipped = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
            Canvas partial = new Canvas(clipped); partial.clipRect(0, 0, 640, bounds.centerY()); view.draw(partial);
            assertTrue("Clipping the final rows must not remove the visible background", hasBox(clipped));
            clipped.recycle();

            view.setCues(Collections.singletonList(new Cue.Builder().setText("First subtitle row\n\nLast row").build()));
            image = draw(view); bounds = pixels(image, false);
            assertEquals("Empty rows stay inside the connected background", 179, Color.alpha(image.getPixel(bounds.centerX(), bounds.centerY())));
            image.recycle();
        });
    }

    @Test public void boxHasPaddedSidesRoundedCornersAndKeepsOtherCueTypes() {
        instrumentation.runOnMainSync(() -> {
            SubtitleView view = new SubtitleView(context); DaniilSubtitleOutput.install(view);
            SubtitleStyle.apply(view, 1, 0, 0, 2, 1);
            view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32);
            view.setBottomPaddingFraction(0.1f);
            view.setCues(Collections.singletonList(new Cue.Builder().setText("Subtitle box").build()));
            Bitmap image = draw(view); Rect box = pixels(image, false), letters = pixels(image, true);
            assertFalse("Text renders", letters.isEmpty()); assertFalse(box.isEmpty());
            assertTrue("Extra space before the text", letters.left - box.left >= 7);
            assertTrue("Extra space after the text", box.right - letters.right >= 7);
            assertEquals("The corner is rounded", 0, Color.alpha(image.getPixel(box.left, box.top)));
            assertTrue("The top edge stays flat between corners", Color.alpha(image.getPixel(box.centerX(), box.top + 1)) > 100);
            assertTrue("The side remains flat, rather than a pill", Color.alpha(image.getPixel(box.left + 1, box.centerY())) > 100);
            image.recycle();

            view.setCues(Collections.singletonList(new Cue.Builder().setText("First line\nSecond line wraps naturally across the screen with a longer subtitle sentence.").build()));
            image = draw(view); Rect multiline = pixels(image, false);
            assertTrue("Line wrapping still works", multiline.height() > box.height() * 2);
            assertTrue("Padded backgrounds stay within the viewport", multiline.left >= 0 && multiline.right <= image.getWidth());
            image.recycle();

            SubtitleStyle.apply(view, 1, 0, 0, 0, 1);
            view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32);
            view.setCues(Collections.singletonList(new Cue.Builder().setText("Subtitle box").build()));
            image = draw(view);
            assertTrue("Outline switches off the padded box", pixels(image, false).width() < box.width()); image.recycle();

            Bitmap cueImage = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888); cueImage.eraseColor(Color.GREEN);
            SubtitleStyle.apply(view, 1, 0, 0, 2, 1);
            view.setCues(Collections.singletonList(new Cue.Builder().setBitmap(cueImage).setPosition(0.5f)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE).setLine(0.5f, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE).setSize(0.25f).build()));
            image = draw(view); assertEquals("Bitmap subtitles pass through", Color.GREEN, image.getPixel(320, 180)); image.recycle();
            view.setCues(Collections.emptyList()); image = draw(view);
            assertTrue("Clearing a cue clears its box too", pixels(image, false).isEmpty()); image.recycle(); cueImage.recycle();
        });
    }

    private static Bitmap draw(SubtitleView view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 640, 360);
        Bitmap image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888); view.draw(new Canvas(image)); return image;
    }
    private static Rect pixels(Bitmap image, boolean white) {
        Rect out = new Rect();
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int color = image.getPixel(x, y);
            if (Color.alpha(color) > 20 && (!white || Color.red(color) > 220)) out.union(x, y, x + 1, y + 1);
        }
        return out;
    }
    private static Rect rowBounds(Bitmap image, int y) {
        Rect row = new Rect();
        for (int x = 0; x < image.getWidth(); x++)
            if (Color.alpha(image.getPixel(x, y)) > 20) row.union(x, y, x + 1, y + 1);
        return row;
    }
    private static boolean hasBox(Bitmap image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int pixel = image.getPixel(x, y);
            if (Color.red(pixel) == 0 && Color.alpha(pixel) >= 170) return true;
        }
        return false;
    }
}
