package tv.stream.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.StyleSpan
import tv.stream.app.R
import tv.stream.playback.SubtitleStyle

/** App-owned font choices; eight legacy keys/indices remain valid and app-only choices are appended. */
object SubtitleFonts {
    const val NOTO = 0
    const val ATKINSON = 8
    const val SYSTEM = 9
    @JvmField val VALUES = arrayOf("standard", "bold", "condensed", "serif", "monospace", "casual", "cursive", "smallcaps", "atkinson", "system")
    private val LABELS = intArrayOf(R.string.subtitle_font_noto, tv.stream.playback.R.string.font_bold,
            tv.stream.playback.R.string.font_condensed, tv.stream.playback.R.string.font_serif, tv.stream.playback.R.string.font_monospace,
            tv.stream.playback.R.string.font_casual, tv.stream.playback.R.string.font_cursive, tv.stream.playback.R.string.font_smallcaps,
            R.string.subtitle_font_atkinson, R.string.subtitle_font_system)
    private var noto: Typeface? = null
    private var atkinson: Typeface? = null
    private var cyrillicScale = 0f

    @JvmStatic fun selected(preferences: SharedPreferences): Int {
        val value = preferences.getString("subtitle_font", "standard")
        for (i in VALUES.indices) if (VALUES[i] == value) return i
        return NOTO
    }
    @JvmStatic fun labels(context: Context): Array<String> {
        val labels = arrayOfNulls<String>(LABELS.size)
        for (i in labels.indices) labels[i] = context.getString(LABELS[i])
        @Suppress("UNCHECKED_CAST")
        return labels as Array<String>
    }
    @JvmStatic @Synchronized fun typeface(context: Context, index: Int): Typeface? {
        if (index == ATKINSON) {
            var font = atkinson
            if (font == null) { font = context.resources.getFont(R.font.subtitle_atkinson); atkinson = font }
            return font
        }
        if (index == SYSTEM) return SubtitleStyle.typefaceFor(0)
        if (index > 0 && index < SubtitleStyle.fontsCount()) return SubtitleStyle.typefaceFor(index)
        var font = noto
        if (font == null) { font = context.resources.getFont(R.font.subtitle_noto); noto = font }
        return font
    }

    /** Keep semantic emphasis while provider font, size and colour overrides stay under viewer control. */
    @JvmStatic fun text(context: Context, index: Int, source: CharSequence): SpannableString {
        val text = SpannableString(source.toString())
        if (source is Spanned) {
            val styled: Spanned = source
            for (span in styled.getSpans(0, source.length, StyleSpan::class.java)) {
                val start = styled.getSpanStart(span)
                val end = styled.getSpanEnd(span)
                if (start >= 0 && end > start && end <= text.length)
                    text.setSpan(StyleSpan(span.style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (index == ATKINSON) {
            var start = -1
            var offset = 0
            while (offset <= text.length) {
                val point = if (offset < text.length) Character.codePointAt(text, offset) else -1
                val cyrillic = point >= 0 && Character.UnicodeScript.of(point) == Character.UnicodeScript.CYRILLIC
                val mark = point >= 0 && (Character.getType(point) == Character.NON_SPACING_MARK.toInt() || Character.getType(point) == Character.COMBINING_SPACING_MARK.toInt())
                if (cyrillic && start < 0) start = offset
                if (start >= 0 && !cyrillic && !mark) {
                    text.setSpan(CyrillicFont(typeface(context, NOTO), cyrillicScale(context)), start, offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); start = -1
                }
                if (point < 0) break
                offset += Character.charCount(point)
            }
        }
        return text
    }
    @Synchronized private fun cyrillicScale(context: Context): Float {
        if (cyrillicScale == 0f) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG); paint.textSize = 1000f
            val bounds = Rect()
            paint.typeface = typeface(context, NOTO); paint.getTextBounds("x", 0, 1, bounds); val notoHeight = bounds.height()
            paint.typeface = typeface(context, ATKINSON); paint.getTextBounds("x", 0, 1, bounds)
            cyrillicScale = if (notoHeight > 0 && bounds.height() > 0) bounds.height() / notoHeight.toFloat() else 1f
        }
        return cyrillicScale
    }
    private class CyrillicFont(private val family: Typeface?, private val scale: Float) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) { apply(paint) }
        override fun updateMeasureState(paint: TextPaint) { apply(paint) }
        private fun apply(paint: TextPaint) {
            val current = paint.typeface
            paint.typeface = Typeface.create(family, if (current == null) Typeface.NORMAL else current.style)
            // Noto has a larger lowercase height; match Atkinson instead of enlarging Cyrillic words mid-cue.
            paint.textSize = paint.textSize * scale
        }
    }
}
