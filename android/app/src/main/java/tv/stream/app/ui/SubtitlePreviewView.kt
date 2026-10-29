package tv.stream.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

import java.util.Collections
import tv.stream.playback.SubtitleStyle
import tv.stream.app.R

/**
 * A TV cinema preview frame that renders sample subtitles matching the user's styling choices in real time
 * over a realistic cinematic video background.
 */
@OptIn(markerClass = [UnstableApi::class])
class SubtitlePreviewView(context: Context) : FrameLayout(context) {
    private val subtitleView: SubtitleView
    private var compact = false
    private var sizeIndex = 1
    private var fontIndex = 0
    private var colorIndex = 0
    private var edgeIndex = 2
    private var positionIndex = 1

    init {
        minimumWidth = Tv.dp(context, 280f)
        isFocusable = false
        isFocusableInTouchMode = false

        // Cinema rounded platter
        val radius = Tv.dp(context, 10f)
        val bg = GradientDrawable()
        bg.cornerRadius = radius.toFloat()
        bg.setColor(0xFF101014.toInt())
        background = bg
        clipToOutline = true

        // Realistic cinematic video scene backdrop
        val backdrop = ImageView(context)
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        backdrop.setImageResource(R.drawable.tv_subtitle_backdrop)
        backdrop.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Subtle film scrim + hairline border on top of the backdrop
        val scrim = View(context)
        val overlay = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x33000000))
        overlay.cornerRadius = radius.toFloat()
        overlay.setStroke(Tv.dp(context, 1f), 0x24FFFFFF)
        scrim.background = overlay
        scrim.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        subtitleView = SubtitleView(context)
        androidx.media3.ui.StreamSubtitleOutput.install(subtitleView)
        subtitleView.setCues(Collections.singletonList(
                Cue.Builder().setText(context.getString(tv.stream.playback.R.string.subtitle_sample)).build()))
        addView(subtitleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        apply()
    }

    fun setCompact(compact: Boolean) {
        if (this.compact != compact) {
            this.compact = compact
            apply()
            requestLayout()
        }
    }

    fun isCompact(): Boolean {
        return compact
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var heightMeasureSpec = heightMeasureSpec
        val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
        val targetHeight = if (compact) Tv.dp(context, 58f) else Tv.dp(context, 92f)
        if (heightMode != View.MeasureSpec.EXACTLY) {
            heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun updateFrom(prefs: SharedPreferences) {
        val style = SubtitleStyle(prefs)
        this.sizeIndex = style.get(SubtitleStyle.Setting.SIZE)
        this.fontIndex = SubtitleFonts.selected(prefs)
        this.colorIndex = style.get(SubtitleStyle.Setting.COLOR)
        this.edgeIndex = style.get(SubtitleStyle.Setting.EDGE)
        this.positionIndex = style.get(SubtitleStyle.Setting.POSITION)
        apply()
    }

    fun update(size: Int, font: Int, color: Int, edge: Int, position: Int) {
        this.sizeIndex = size
        this.fontIndex = font
        this.colorIndex = color
        this.edgeIndex = edge
        this.positionIndex = position
        apply()
    }

    fun setSize(size: Int) { this.sizeIndex = size; apply() }
    fun setFont(font: Int) { this.fontIndex = font; apply() }
    fun setColor(color: Int) { this.colorIndex = color; apply() }
    fun setEdge(edge: Int) { this.edgeIndex = edge; apply() }
    fun preview(setting: SubtitleStyle.Setting, index: Int) {
        when (setting) {
            SubtitleStyle.Setting.SIZE -> setSize(index)
            SubtitleStyle.Setting.FONT -> setFont(index)
            SubtitleStyle.Setting.COLOR -> setColor(index)
            SubtitleStyle.Setting.EDGE -> setEdge(index)
            SubtitleStyle.Setting.POSITION -> setPosition(index)
        }
    }
    fun setPosition(position: Int) { this.positionIndex = position; apply() }

    private fun apply() {
        val color = SubtitleStyle.colorFor(colorIndex)
        val font: Typeface? = SubtitleFonts.typeface(context, fontIndex)
        val edgeType = if (edgeIndex == 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE
                else if (edgeIndex == 1) CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW else CaptionStyleCompat.EDGE_TYPE_NONE
        val box = if (edgeIndex == 2) SubtitleStyle.boxColor() else Color.TRANSPARENT
        subtitleView.setStyle(CaptionStyleCompat(color, box, Color.TRANSPARENT, edgeType, Color.BLACK, font))
        androidx.media3.ui.StreamSubtitleOutput.font(subtitleView, fontIndex)
        androidx.media3.ui.StreamSubtitleOutput.configure(subtitleView)

        val sp: Float
        val gapDp: Int
        if (compact) {
            sp = if (sizeIndex == 0) 13f else if (sizeIndex == 1) 15.5f else if (sizeIndex == 2) 18f else 21f
            gapDp = if (positionIndex == 0) 4 else if (positionIndex == 1) 10 else 18
        } else {
            sp = if (sizeIndex == 0) 14f else if (sizeIndex == 1) 17f else if (sizeIndex == 2) 20.5f else 25f
            gapDp = if (positionIndex == 0) 6 else if (positionIndex == 1) 14 else 26
        }

        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        subtitleView.setBottomPaddingFraction(0f)
        subtitleView.setPadding(0, 0, 0, Tv.dp(context, gapDp.toFloat()))
    }
}
