package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.StreamSubtitleOutput;
import androidx.media3.ui.SubtitleView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import org.junit.Test;
import tv.stream.playback.SubtitleStyle;
import tv.stream.app.ui.SubtitleFonts;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class SubtitleFontRenderingTest extends AppHarness {
    @Test public void settingsExposeBothBundledFontsAndPreviewCancelDoesNotSave() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home")); tap("Settings", true); tap("Subtitles", false);
        assertTrue(visible("Noto Sans"));
        tap("Subtitle font", true);
        tap("Atkinson Hyperlegible Next", false);
        waitUntil("Atkinson saved", 5000, () -> "atkinson".equals(services.playerPreferences.getString("subtitle_font", "")));
        app.persistence.submit(() -> {}).get(); assertEquals("atkinson", services.store.preference("subtitle_font", ""));
        returnedToFontRow();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        fontChoiceFocused(SubtitleFonts.ATKINSON);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); SystemClock.sleep(200);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        returnedToFontRow();
        assertEquals("A preview does not replace the saved font", "atkinson", services.playerPreferences.getString("subtitle_font", ""));
        returnedToFontRow();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        fontChoiceFocused(SubtitleFonts.ATKINSON);
        for (int i = 0; i < SubtitleFonts.ATKINSON; i++) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("Noto saved under the compatible default key", 5000, () -> "standard".equals(services.playerPreferences.getString("subtitle_font", "")));
    }

    private void returnedToFontRow() {
        waitUntil("Settings returns focus after the chooser closes", 5000, () -> {
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            browse.onActivity(activity -> {
                View row = activity.findViewById(R.id.page_rows).findViewWithTag("subtitle-font");
                ready.set(activity.hasWindowFocus() && row != null && row.hasFocus());
            }); return ready.get();
        });
    }

    private void fontChoiceFocused(int index) {
        waitUntil("font chooser focuses its saved choice", 5000, () -> {
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                for (View window : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    View option = window.findViewWithTag("choice:" + index);
                    if (option != null && option.hasWindowFocus() && option.hasFocus()) ready.set(true);
                }
            }); return ready.get();
        });
    }

    @Test public void bundledFacesAndSemanticItalicsRenderThroughMedia3AndProduceAMatchedSizeComparison() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        browse.onActivity(activity -> {
            LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xFF23282E);
            int[] choices = {SubtitleFonts.SYSTEM, SubtitleFonts.NOTO, SubtitleFonts.ATKINSON};
            String[] names = {"Original system sans-serif", "Noto Sans Medium — default", "Atkinson Hyperlegible Next Medium — Noto Sans for Cyrillic"};
            for (int i = 0; i < choices.length; i++) {
                int choice = choices[i];
                TextView label = new TextView(activity); label.setText(names[i]); label.setTextSize(15); label.setTextColor(0xFFA8B4BF); label.setPadding(48, 14, 0, 0);
                root.addView(label, new LinearLayout.LayoutParams(-1, 58));
                SubtitleView subtitles = new SubtitleView(activity); StreamSubtitleOutput.install(subtitles);
                Typeface family = SubtitleFonts.typeface(activity, choice);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTypeface(family); paint.setTextSize(100);
                Rect x = new Rect(); paint.getTextBounds("x", 0, 1, x);
                assertTrue("Typeface has measurable lowercase glyphs", x.height() > 0);
                paint.setTypeface(SubtitleFonts.typeface(activity, SubtitleFonts.NOTO));
                assertTrue("French accents are present", paint.hasGlyph("é")); assertTrue("Cyrillic glyphs are present", paint.hasGlyph("Ж"));
                // Match lowercase height at 25 px, rather than comparing misleading identical point sizes.
                subtitles.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 2500f / x.height());
                subtitles.setStyle(new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, family));
                StreamSubtitleOutput.font(subtitles, choice); StreamSubtitleOutput.configure(subtitles);
                SpannableString text = new SpannableString("We should leave before sunrise.\nDéjà vu : où sont les lumières ?\nНам пора идти — до рассвета.");
                text.setSpan(new StyleSpan(Typeface.ITALIC), 0, 30, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                subtitles.setCues(Collections.singletonList(new Cue.Builder().setText(text).setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLine(.10f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).setSize(.9f).build()));
                root.addView(subtitles, new LinearLayout.LayoutParams(-1, 0, 1));
            }
            activity.setContentView(root);
        });
        SystemClock.sleep(400);
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "validation-subtitle-font-comparison.png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        image.recycle();
    }
}
