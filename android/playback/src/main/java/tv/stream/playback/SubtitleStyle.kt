package tv.stream.playback

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

import org.json.JSONException
import org.json.JSONObject

/**
 * How subtitles look, saved on the TV. Each setting is a short list of tested choices; the keys are also the
 * values the Chrome extension sends, so both sides stay in step. The font is chosen on the TV only.
 */
@OptIn(markerClass = [UnstableApi::class])
class SubtitleStyle(private val prefs: SharedPreferences) {
    enum class Setting(
        @JvmField val key: String,
        @JvmField val title: Int,
        @JvmField val fallback: Int,
        @JvmField val values: Array<String>,
        @JvmField val labels: IntArray,
    ) {
        SIZE("size", R.string.subtitle_size, 1, arrayOf("small", "medium", "large", "xlarge"),
                intArrayOf(R.string.size_small, R.string.size_medium, R.string.size_large, R.string.size_xlarge)),
        FONT("font", R.string.subtitle_font, 0,
                arrayOf("standard", "bold", "condensed", "serif", "monospace", "casual", "cursive", "smallcaps"),
                intArrayOf(R.string.font_standard, R.string.font_bold, R.string.font_condensed, R.string.font_serif,
                        R.string.font_monospace, R.string.font_casual, R.string.font_cursive, R.string.font_smallcaps)),
        COLOR("color", R.string.subtitle_color, 0,
                arrayOf("white", "yellow", "cyan", "green", "magenta", "blue", "amber", "red"),
                intArrayOf(R.string.color_white, R.string.color_yellow, R.string.color_cyan, R.string.color_green,
                        R.string.color_magenta, R.string.color_blue, R.string.color_amber, R.string.color_red)),
        EDGE("edge", R.string.subtitle_edge, 2, arrayOf("outline", "shadow", "box"),
                intArrayOf(R.string.edge_outline, R.string.edge_shadow, R.string.edge_box)),
        POSITION("position", R.string.subtitle_position, 1, arrayOf("low", "standard", "raised"),
                intArrayOf(R.string.position_low, R.string.position_standard, R.string.position_raised));
    }

    private val choice = IntArray(Setting.values().size)

    init {
        for (setting in Setting.values()) {
            val index = indexOf(setting, prefs.getString("subtitle_" + setting.key, null))
            choice[setting.ordinal] = if (index < 0) setting.fallback else index
        }
    }

    fun get(setting: Setting): Int {
        return choice[setting.ordinal]
    }

    /** Returns whether the value changed. */
    fun set(setting: Setting, index: Int): Boolean {
        if (index < 0 || index >= setting.values.size || index == get(setting)) return false
        choice[setting.ordinal] = index
        prefs.edit().putString("subtitle_" + setting.key, setting.values[index]).apply()
        return true
    }

    /** Applies a value sent by the extension, such as `size=large`; unknown values are ignored. */
    fun set(setting: Setting, value: String?): Boolean {
        return set(setting, indexOf(setting, value))
    }

    fun color(): Int {
        return colorFor(get(Setting.COLOR))
    }

    fun typeface(): Typeface {
        return typefaceFor(get(Setting.FONT))
    }

    fun sizeFraction(): Float {
        return sizeFractionFor(get(Setting.SIZE))
    }

    fun edge(): Int {
        return get(Setting.EDGE)
    }

    fun bottomGap(): Float {
        return bottomGapFor(get(Setting.POSITION))
    }

    fun applyTo(view: SubtitleView) {
        apply(view, get(Setting.SIZE), get(Setting.FONT), get(Setting.COLOR), get(Setting.EDGE), get(Setting.POSITION))
    }

    @Throws(JSONException::class)
    fun toJson(): JSONObject {
        val json = JSONObject()
        for (setting in Setting.values()) json.put(setting.key, setting.values[get(setting)])
        return json
    }

    companion object {
        // Text height as a fraction of the video height; Media3's default is 0.0533.
        private val SIZES = floatArrayOf(0.042f, 0.0533f, 0.066f, 0.082f)
        private val COLORS = intArrayOf(
                Color.WHITE, 0xFFFFE15A.toInt(), 0xFF8BE9FF.toInt(), 0xFFA8F387.toInt(), 0xFFFF80DF.toInt(), 0xFF82B1FF.toInt(), 0xFFFFB84D.toInt(), 0xFFFF6E6E.toInt())
        // Gap below the last line, as a fraction of the video height.
        private val BOTTOM_GAP = floatArrayOf(0.04f, 0.08f, 0.16f)
        private const val BOX = 0xB3000000.toInt()
        // System families every Android TV has: sans-serif medium, bold, condensed, serif, monospace, casual, cursive, and small caps.
        private val FONTS = arrayOf(
                Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.create("sans-serif", Typeface.BOLD),
                Typeface.create("sans-serif-condensed", Typeface.BOLD), Typeface.create("serif", Typeface.NORMAL),
                Typeface.create("monospace", Typeface.NORMAL), Typeface.create("casual", Typeface.NORMAL),
                Typeface.create("cursive", Typeface.NORMAL), Typeface.create("sans-serif-smallcaps", Typeface.NORMAL))

        @JvmStatic
        fun colorFor(index: Int): Int {
            return COLORS[Math.max(0, Math.min(index, COLORS.size - 1))]
        }

        @JvmStatic
        fun typefaceFor(index: Int): Typeface {
            return FONTS[Math.max(0, Math.min(index, FONTS.size - 1))]
        }

        @JvmStatic
        fun sizeFractionFor(index: Int): Float {
            return SIZES[Math.max(0, Math.min(index, SIZES.size - 1))]
        }

        @JvmStatic
        fun bottomGapFor(index: Int): Float {
            return BOTTOM_GAP[Math.max(0, Math.min(index, BOTTOM_GAP.size - 1))]
        }

        @JvmStatic
        fun boxColor(): Int {
            return BOX
        }

        @JvmStatic
        fun colorsCount(): Int {
            return COLORS.size
        }

        @JvmStatic
        fun fontsCount(): Int {
            return FONTS.size
        }

        @JvmStatic
        fun apply(view: SubtitleView, sizeIndex: Int, fontIndex: Int, colorIndex: Int, edgeIndex: Int, positionIndex: Int) {
            val color = colorFor(colorIndex)
            val font = typefaceFor(fontIndex)
            val edgeType = if (edgeIndex == 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    else if (edgeIndex == 1) CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW else CaptionStyleCompat.EDGE_TYPE_NONE
            view.setStyle(CaptionStyleCompat(color,
                    if (edgeIndex == 2) BOX else Color.TRANSPARENT, Color.TRANSPARENT,
                    edgeType, Color.BLACK, font))
            // Site-supplied colours and sizes would override the viewer's choice, so only cue positions are kept.
            view.setApplyEmbeddedStyles(false)
            view.setFractionalTextSize(sizeFractionFor(sizeIndex), true)
            // WebVTT cues are anchored to the bottom edge by line number, which ignores Media3's bottom padding
            // fraction. The player pads the view by bottomGap() instead, so every format moves together.
            view.setBottomPaddingFraction(0f)
        }

        private fun indexOf(setting: Setting, value: String?): Int {
            for (i in setting.values.indices) if (setting.values[i] == value) return i
            return -1
        }
    }
}
