package tv.stream.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import tv.stream.playback.SubtitleStyle;
import tv.stream.app.ui.SubtitleFonts;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36})
public final class SubtitleFontsTest {
    private final Context context = RuntimeEnvironment.getApplication();
    @Test public void legacySavedKeysStayCompatibleAndNewChoicesSurviveRecreation() {
        SharedPreferences prefs = context.getSharedPreferences("font-test", Context.MODE_PRIVATE); prefs.edit().clear().commit();
        assertEquals(SubtitleFonts.NOTO, SubtitleFonts.selected(prefs));
        for (int i = 0; i < SubtitleStyle.Setting.FONT.values.length; i++) {
            assertEquals(SubtitleStyle.Setting.FONT.values[i], SubtitleFonts.VALUES[i]);
            prefs.edit().putString("subtitle_font", SubtitleFonts.VALUES[i]).commit();
            assertEquals(i, SubtitleFonts.selected(prefs)); assertEquals(i, new SubtitleStyle(prefs).get(SubtitleStyle.Setting.FONT));
            if (i > 0) assertSame(SubtitleStyle.typefaceFor(i), SubtitleFonts.typeface(context, i));
        }
        for (int i : new int[]{SubtitleFonts.ATKINSON, SubtitleFonts.SYSTEM}) {
            prefs.edit().putString("subtitle_font", SubtitleFonts.VALUES[i]).commit();
            assertEquals(i, SubtitleFonts.selected(context.getSharedPreferences("font-test", Context.MODE_PRIVATE)));
        }
        assertSame(SubtitleStyle.typefaceFor(0), SubtitleFonts.typeface(context, SubtitleFonts.SYSTEM));
        assertEquals(SubtitleFonts.VALUES.length, SubtitleFonts.labels(context).length);
    }
    @Test public void viewerFontColourAndSizeWinWhileCueItalicsRemain() {
        SpannableString source = new SpannableString("Déjà vu — Уже пора.");
        source.setSpan(new StyleSpan(Typeface.ITALIC), 0, source.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        source.setSpan(new ForegroundColorSpan(Color.RED), 0, source.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        source.setSpan(new RelativeSizeSpan(3f), 0, source.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        SpannableString clean = SubtitleFonts.text(context, SubtitleFonts.NOTO, source);
        assertEquals(source.toString(), clean.toString());
        assertEquals(Typeface.ITALIC, clean.getSpans(0, clean.length(), StyleSpan.class)[0].getStyle());
        assertEquals(0, clean.getSpans(0, clean.length(), ForegroundColorSpan.class).length);
        assertEquals(0, clean.getSpans(0, clean.length(), RelativeSizeSpan.class).length);
    }
    @Test public void atkinsonUsesExplicitNotoForCyrillicIncludingCombiningMarksAndItalic() {
        SpannableString clean = SubtitleFonts.text(context, SubtitleFonts.ATKINSON, "Latin Уже\u0301 пора");
        MetricAffectingSpan[] fallback = clean.getSpans(6, 10, MetricAffectingSpan.class);
        assertEquals(1, fallback.length); assertEquals(6, clean.getSpanStart(fallback[0])); assertEquals(10, clean.getSpanEnd(fallback[0]));
        TextPaint paint = new TextPaint(); paint.setTypeface(Typeface.create(SubtitleFonts.typeface(context, SubtitleFonts.ATKINSON), Typeface.ITALIC));
        fallback[0].updateMeasureState(paint);
        assertEquals(Typeface.create(SubtitleFonts.typeface(context, SubtitleFonts.NOTO), Typeface.ITALIC), paint.getTypeface());
        assertEquals(0, clean.getSpans(0, 5, MetricAffectingSpan.class).length);
    }
}
