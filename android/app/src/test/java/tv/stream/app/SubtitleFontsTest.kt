package tv.stream.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.stream.app.ui.SubtitleFonts
import tv.stream.playback.SubtitleStyle

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class SubtitleFontsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    @Test fun legacySavedKeysStayCompatibleAndNewChoicesSurviveRecreation() {
        val prefs: SharedPreferences = context.getSharedPreferences("font-test", Context.MODE_PRIVATE); prefs.edit().clear().commit()
        assertEquals(SubtitleFonts.NOTO, SubtitleFonts.selected(prefs))
        for (i in 0 until SubtitleStyle.Setting.FONT.values.size) {
            assertEquals(SubtitleStyle.Setting.FONT.values[i], SubtitleFonts.VALUES[i])
            prefs.edit().putString("subtitle_font", SubtitleFonts.VALUES[i]).commit()
            assertEquals(i, SubtitleFonts.selected(prefs)); assertEquals(i, SubtitleStyle(prefs).get(SubtitleStyle.Setting.FONT))
            if (i > 0) assertSame(SubtitleStyle.typefaceFor(i), SubtitleFonts.typeface(context, i))
        }
        for (i in intArrayOf(SubtitleFonts.ATKINSON, SubtitleFonts.SYSTEM)) {
            prefs.edit().putString("subtitle_font", SubtitleFonts.VALUES[i]).commit()
            assertEquals(i, SubtitleFonts.selected(context.getSharedPreferences("font-test", Context.MODE_PRIVATE)))
        }
        assertSame(SubtitleStyle.typefaceFor(0), SubtitleFonts.typeface(context, SubtitleFonts.SYSTEM))
        assertEquals(SubtitleFonts.VALUES.size, SubtitleFonts.labels(context).size)
    }
    @Test fun viewerFontColourAndSizeWinWhileCueItalicsRemain() {
        val source = SpannableString("Déjà vu — Уже пора.")
        source.setSpan(StyleSpan(Typeface.ITALIC), 0, source.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(ForegroundColorSpan(Color.RED), 0, source.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(RelativeSizeSpan(3f), 0, source.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val clean: SpannableString = SubtitleFonts.text(context, SubtitleFonts.NOTO, source)
        assertEquals(source.toString(), clean.toString())
        assertEquals(Typeface.ITALIC, clean.getSpans(0, clean.length, StyleSpan::class.java)[0].style)
        assertEquals(0, clean.getSpans(0, clean.length, ForegroundColorSpan::class.java).size)
        assertEquals(0, clean.getSpans(0, clean.length, RelativeSizeSpan::class.java).size)
    }
    @Test fun atkinsonUsesExplicitNotoForCyrillicIncludingCombiningMarksAndItalic() {
        val clean: SpannableString = SubtitleFonts.text(context, SubtitleFonts.ATKINSON, "Latin Уже́ пора")
        val fallback: Array<MetricAffectingSpan> = clean.getSpans(6, 10, MetricAffectingSpan::class.java)
        assertEquals(1, fallback.size); assertEquals(6, clean.getSpanStart(fallback[0])); assertEquals(10, clean.getSpanEnd(fallback[0]))
        val paint = TextPaint(); paint.setTypeface(Typeface.create(SubtitleFonts.typeface(context, SubtitleFonts.ATKINSON), Typeface.ITALIC))
        fallback[0].updateMeasureState(paint)
        assertEquals(Typeface.create(SubtitleFonts.typeface(context, SubtitleFonts.NOTO), Typeface.ITALIC), paint.getTypeface())
        assertEquals(0, clean.getSpans(0, 5, MetricAffectingSpan::class.java).size)
    }
}
