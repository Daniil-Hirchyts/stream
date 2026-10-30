package tv.stream.app.ui

import androidx.annotation.ColorRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tv.stream.app.R

/**
 * Page blocks that are only text, drawn by Compose: section and page headings, explanatory text, and quiet messages.
 * They match the View builders they replace: the same Geist faces, sizes in sp, colours, tracking, line spacing,
 * artwork shadow and accessibility labels.
 */
internal object TextBlocks {
    private val regular = FontFamily(Font(R.font.geist_regular))
    private val medium = FontFamily(Font(R.font.geist_medium))
    private val semibold = FontFamily(Font(R.font.geist_semibold))

    /** What a block needs from its page: the gutters, and whether it sits among list rows. */
    data class Layout(val start: Dp, val end: Dp, val inset: Dp)

    @Composable
    private fun style(font: Tv.Font, sp: Float, @ColorRes color: Int, onArtwork: Boolean, spacing: Float = 1f): TextStyle {
        val density = LocalDensity.current
        val context = LocalContext.current
        // Tv.onArtwork: a 5 dp shadow 1 dp down, 70% black.
        val shadow = if (onArtwork) with(density) { Shadow(Color(0xB3000000), Offset(0f, 1.dp.toPx()), 5.dp.toPx()) } else null
        // TextView's line spacing multiplies the face's own line height (descent - ascent), not the font size.
        val lineHeight = remember(font, sp, spacing, density) {
            if (spacing == 1f) androidx.compose.ui.unit.TextUnit.Unspecified else {
                val paint = android.text.TextPaint().apply { typeface = font.get(context); textSize = with(density) { sp.sp.toPx() } }
                val natural = paint.fontMetrics.let { it.descent - it.ascent }
                with(density) { (natural * spacing).toSp() }
            }
        }
        return TextStyle(
            fontFamily = family(font),
            fontSize = sp.sp,
            color = colorResource(color),
            shadow = shadow,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeight = lineHeight,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        )
    }

    private fun family(font: Tv.Font): FontFamily = when (font) {
        Tv.Font.REGULAR -> regular
        Tv.Font.MEDIUM -> medium
        Tv.Font.SEMIBOLD -> semibold
    }

    /** A heading over a section or list (tvOS Settings style when `section`): one line, detail in smaller grey. */
    @Composable
    fun Header(title: String, detail: String?, section: Boolean, topDp: Int, layout: Layout) {
        val sp = if (section) 14f else 18f
        val color = if (section) R.color.tv_text_secondary else R.color.tv_text
        val text: AnnotatedString = buildAnnotatedString {
            append(title)
            if (!detail.isNullOrEmpty()) {
                withStyle(SpanStyle(fontFamily = regular, fontSize = 13.sp, color = colorResource(R.color.tv_text_tertiary))) {
                    append("   "); append(detail)
                }
            }
        }
        val label = if (detail.isNullOrEmpty()) title else "$title, $detail"
        BasicText(
            text,
            modifier = Modifier.fillMaxWidth()
                .padding(start = layout.start + (if (section) layout.inset else 0.dp), top = topDp.dp, end = layout.end, bottom = 2.dp)
                .semantics { contentDescription = label },
            style = style(if (section) Tv.Font.MEDIUM else Tv.Font.SEMIBOLD, sp, color, onArtwork = true),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    /** Explanatory text, optionally under a small capitalised title. */
    @Composable
    fun Paragraph(title: String, detail: String, inList: Boolean, layout: Layout) {
        Column(Modifier.fillMaxWidth().padding(start = layout.start + (if (inList) layout.inset else 0.dp), top = 14.dp, end = layout.end, bottom = 2.dp)) {
            if (title.isNotEmpty()) {
                BasicText(title.uppercase(), style = style(Tv.Font.MEDIUM, 12f, R.color.tv_text_tertiary, onArtwork = true).copy(letterSpacing = 0.1.em))
            }
            BasicText(detail, modifier = Modifier.padding(top = 5.dp), style = style(Tv.Font.REGULAR, 15f, R.color.tv_text_secondary, onArtwork = true, spacing = 1.22f))
        }
    }

    /** A message with no icon or actions: an empty result, centred and quiet. */
    @Composable
    fun QuietMessage(title: String, detail: String, layout: Layout) {
        Column(
            Modifier.fillMaxWidth().padding(start = layout.start, top = 100.dp, end = layout.end, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(title, style = style(Tv.Font.REGULAR, 20f, R.color.tv_text_secondary, onArtwork = false).copy(textAlign = TextAlign.Center))
            if (detail.isNotEmpty()) {
                BasicText(detail, modifier = Modifier.widthIn(max = 400.dp).padding(vertical = 8.dp),
                        style = style(Tv.Font.REGULAR, 14f, R.color.tv_text_tertiary, onArtwork = false, spacing = 1.22f).copy(textAlign = TextAlign.Center))
            }
        }
    }
}
