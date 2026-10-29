package androidx.media3.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LineBackgroundSpan
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import java.text.Bidi
import java.util.ArrayList
import tv.stream.playback.SubtitleStyle
import tv.stream.app.ui.SubtitleFonts

/** Stream's fonts and Box styling; semantic emphasis survives provider-style sanitization. */
@OptIn(markerClass = [UnstableApi::class])
class StreamSubtitleOutput private constructor(view: SubtitleView) : FrameLayout(view.context), SubtitleView.Output {
    private val canvas: CanvasSubtitleOutput
    private var textSize = 0f
    private var textSizeType = 0
    private var boxed = false
    private var fontIndex = -1
    private var inputCues: List<Cue> = java.util.Collections.emptyList()
    private var inputStyle: CaptionStyleCompat? = null
    private var inputBottomGap = 0f

    init {
        canvas = CanvasSubtitleOutput(context)
        addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    companion object {
        @JvmStatic fun install(view: SubtitleView) {
            // SubtitleView is final and exposes no output factory in pinned Media3 1.11.1. Keep this
            // one integration point (also retained by R8) app-local; the receiver keeps its renderer.
            try {
                val replace = SubtitleView::class.java.getDeclaredMethod("setView", View::class.java)
                replace.isAccessible = true
                replace.invoke(view, StreamSubtitleOutput(view))
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("Media3 subtitle output integration changed", e)
            }
        }

        /** The shared player still owns size/colour/position; these font choices belong only to Stream. */
        @JvmStatic fun font(view: SubtitleView, index: Int) {
            if (view.getChildAt(0) !is StreamSubtitleOutput) throw IllegalStateException("Subtitle output is not installed")
            val output = view.getChildAt(0) as StreamSubtitleOutput
            output.fontIndex = if (index >= 0 && index < SubtitleFonts.VALUES.size) index else SubtitleFonts.NOTO
            if (output.inputStyle != null) output.update(output.inputCues, output.inputStyle!!, output.textSize, output.textSizeType, output.inputBottomGap)
        }

        /** Preserve cue italics; update() removes provider font/size/colour overrides before painting. */
        @JvmStatic fun configure(view: SubtitleView) {
            view.setApplyEmbeddedStyles(true); view.setApplyEmbeddedFontSizes(false)
        }
    }

    override fun update(cues: List<Cue>, style: CaptionStyleCompat, size: Float, sizeType: Int, bottomGap: Float) {
        var cues = cues; var style = style
        inputCues = cues; inputStyle = style; inputBottomGap = bottomGap
        var chosen = fontIndex
        if (chosen < 0) {
            chosen = SubtitleFonts.NOTO
            for (i in 1 until SubtitleStyle.fontsCount()) if (SubtitleStyle.typefaceFor(i).equals(style.typeface)) { chosen = i; break }
        }
        style = CaptionStyleCompat(style.foregroundColor, style.backgroundColor, style.windowColor,
                style.edgeType, style.edgeColor, SubtitleFonts.typeface(context, chosen))
        val clean: MutableList<Cue> = ArrayList(cues.size)
        for (cue in cues) clean.add(if (cue.text == null) cue else cue.buildUpon()
                .setText(SubtitleFonts.text(context, chosen, cue.text!!)).clearWindowColor()
                .setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).build())
        cues = clean
        boxed = Color.alpha(style.backgroundColor) > 0 && style.edgeType == CaptionStyleCompat.EDGE_TYPE_NONE
        textSize = size; textSizeType = sizeType; updatePadding()
        if (boxed) {
            val styled: MutableList<Cue> = ArrayList(cues.size)
            for (cue in cues) {
                if (cue.text == null || cue.text!!.length == 0) { styled.add(cue); continue }
                val text = SpannableString(cue.text)
                text.setSpan(Box(style.backgroundColor, cue.textAlignment), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.add(cue.buildUpon().setText(text).build())
            }
            cues = styled
            style = CaptionStyleCompat(style.foregroundColor, Color.TRANSPARENT, style.windowColor,
                    style.edgeType, style.edgeColor, style.typeface)
        }
        canvas.update(cues, style, size, sizeType, bottomGap)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight); updatePadding()
    }

    private fun updatePadding() {
        val pixels = SubtitleViewUtils.resolveTextSize(textSizeType, textSize, height, height)
        val inset = if (boxed) Math.max(0, Math.ceil((pixels * 0.35f).toDouble()).toInt()) else 0
        // Leave room for the box even when a cue wraps across the full viewport.
        if (canvas.paddingLeft != inset) canvas.setPadding(inset, 0, inset, 0)
    }

    private class Box(private val color: Int, private val alignment: Layout.Alignment?) : LineBackgroundSpan {
        private val shape = Path()
        private val row = Path()
        private val clip = Rect()
        private val line = RectF()
        private var previousLine = -1

        override fun drawBackground(target: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int,
                bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int) {
            var end = end
            if (lineNumber <= previousLine) { shape.reset(); line.setEmpty() }
            previousLine = lineNumber
            val last = end == text.length
            while (end > start && (text[end - 1] == '\n' || text[end - 1] == '\r')) end--
            val padding = paint.textSize * 0.3f; val radius = paint.textSize * 0.24f
            if (end > start) {
                val width = Layout.getDesiredWidth(text, start, end, android.text.TextPaint(paint))
                val rtl = !Bidi(text.subSequence(start, end).toString(), Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
                var x = (left + right - width) / 2f
                if (alignment == Layout.Alignment.ALIGN_NORMAL) x = if (rtl) right - width else left.toFloat()
                else if (alignment == Layout.Alignment.ALIGN_OPPOSITE) x = if (rtl) left.toFloat() else right - width
                line.set(x - padding, top.toFloat(), x + width + padding, bottom.toFloat())
            } else if (!line.isEmpty) {
                // An intentional empty row still belongs to the same caption background.
                line.top = top.toFloat(); line.bottom = bottom.toFloat()
            }
            if (!line.isEmpty) {
                row.reset(); row.addRect(line, Path.Direction.CW)
                shape.op(row, Path.Op.UNION)
            }
            // Layout draws every line background before any glyphs. Fill the combined contour once,
            // after collecting its rows: no seams or darker overlaps, and no full-width rectangle.
            target.getClipBounds(clip)
            // Layout may omit the last rows when a long cue extends outside the viewport.
            if ((!last && bottom < clip.bottom) || shape.isEmpty) return
            val savedColor = paint.color; val savedStyle = paint.style
            val savedEffect: PathEffect? = paint.pathEffect
            paint.color = color; paint.style = Paint.Style.FILL
            // Rounds the outside corners and the inward steps where adjacent line widths differ.
            paint.pathEffect = CornerPathEffect(radius)
            target.drawPath(shape, paint)
            paint.color = savedColor; paint.style = savedStyle; paint.pathEffect = savedEffect
            shape.reset(); line.setEmpty(); previousLine = -1
        }
    }
}
