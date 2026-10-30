package tv.stream.app.ui

import android.widget.ProgressBar
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import tv.stream.app.R
import tv.stream.app.ui.PageControls.platter

/**
 * Each page's loading state, built with the same sizes and spacing as the page, so it replaces them without anything
 * moving. A bar stands where each line of text will be, centred on the line's capital letters.
 */
internal object SkeletonBlocks {
    @Composable
    fun Block(screen: String, start: Dp, end: Dp) {
        Column(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            when (screen) {
                "Home" -> { Spotlight(start, end); Shelf(start); Shelf(start) }
                "Home results" -> { Shelf(start); Shelf(start) }
                "Add-ons" -> {
                    Title(180.dp, start, end); Buttons(true, listOf(46, 84, 106, 102, 88), start, end)
                    Heading(Tv.Font.MEDIUM, 14f, 22.dp, PageRows.ROW_INSET.dp, 56.dp, start, end)
                    val titles = intArrayOf(132, 104, 150, 118, 140); val metas = intArrayOf(200, 230, 210, 180, 220)
                    for (i in titles.indices) Source(titles[i].dp, metas[i].dp, if (i == 0) 64.dp else 0.dp, start, end)
                }
                "Add-on review" -> {
                    Title(200.dp, start, end); Paragraph(listOf(600, 600, 340), start, end); Paragraph(listOf(230, 150), start, end)
                    Buttons(false, listOf(148), start, end); Paragraph(listOf(330), start, end)
                }
                "Catalogue results" -> { Grid(6, start, end); Grid(6, start, end) }
                "Source results", "Store results" -> repeat(4) { Source(170.dp, 150.dp, 0.dp, start, end) }
                "Catalogue" -> { Title(170.dp, start, end); Grid(6, start, end); Grid(6, start, end) }
                else -> Loading()
            }
        }
    }

    /** The app's spinner: its animated vector runs on the render thread while the main thread is busy. */
    @Composable
    fun Loading() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { context ->
                ProgressBar(context).apply {
                    indeterminateDrawable = context.getDrawable(R.drawable.tv_loading_indicator)
                    contentDescription = "Loading"
                }
            }, modifier = Modifier.size(44.dp))
        }
    }

    /** One or more lines of `font` at `sp`, each a bar of its given width over its line's real height. */
    @Composable
    private fun Lines(font: Tv.Font, sp: Float, widths: List<Dp>, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val metrics = remember(font, sp, density) {
            android.text.TextPaint().apply { typeface = font.get(context); textSize = with(density) { sp.sp.toPx() } }.fontMetricsInt
        }
        val size = with(density) { sp.sp.toPx() }
        val line = metrics.descent - metrics.ascent
        val bar = maxOf(with(density) { 6.dp.roundToPx() }, Math.round(size * .62f))
        Box(modifier.height(with(density) { (line * widths.size).toDp() })) {
            for (i in widths.indices) {
                if (widths[i] <= 0.dp) continue
                val top = -metrics.ascent + i * line - Math.round(size * .36f) - bar / 2
                Box(Modifier.offset(y = with(density) { top.toDp() }).size(widths[i], with(density) { bar.toDp() }).platter(R.drawable.tv_skeleton))
            }
        }
    }

    @Composable
    private fun Title(width: Dp, start: Dp, end: Dp) {
        Lines(Tv.Type.TITLE.font, Tv.Type.TITLE.size, listOf(width), Modifier.padding(start = start, top = 6.dp, end = end, bottom = 4.dp))
    }

    /** A shelf or grid heading (18 sp, white) or a list section heading (14 sp, inset to the rows' labels). */
    @Composable
    private fun Heading(font: Tv.Font, sp: Float, top: Dp, inset: Dp, width: Dp, start: Dp, end: Dp) {
        Lines(font, sp, listOf(width), Modifier.padding(start = start + inset, top = top, end = end, bottom = 2.dp))
    }

    @Composable
    private fun Paragraph(widths: List<Int>, start: Dp, end: Dp) {
        Lines(Tv.Font.REGULAR, 15f, widths.map { it.dp }, Modifier.padding(start = start, top = 19.dp, end = end, bottom = 2.dp))
    }

    /** A two-line row (a source or a listed add-on), with its status at the end. */
    @Composable
    private fun Source(title: Dp, meta: Dp, status: Dp, start: Dp, end: Dp) {
        Row(Modifier.fillMaxWidth().padding(start = start, top = 2.dp, end = end, bottom = 2.dp)
                .padding(start = PageRows.ROW_INSET.dp, top = 10.dp, end = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Lines(Tv.Font.MEDIUM, 15.5f, listOf(title))
                Lines(Tv.Font.REGULAR, 13f, listOf(meta), Modifier.padding(top = 5.dp))
            }
            if (status > 0.dp) Lines(Tv.Font.REGULAR, 13f, listOf(status))
        }
    }

    /** Buttons (44 dp) or filter chips (36 dp capsules) at their usual widths. */
    @Composable
    private fun Buttons(chips: Boolean, widths: List<Int>, start: Dp, end: Dp) {
        val height = if (chips) 36.dp else 44.dp
        Row(Modifier.fillMaxWidth().padding(start = start, top = 10.dp, end = end, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (width in widths) Bar(if (chips) R.drawable.tv_skeleton_capsule else R.drawable.tv_skeleton, Modifier.size(width.dp, height))
        }
    }

    @Composable
    private fun Bar(@DrawableRes background: Int, modifier: Modifier) { Box(modifier.platter(background)) }

    /** Home's spotlight: its fixed height, with the title, facts and description at the bottom. */
    @Composable
    private fun Spotlight(start: Dp, end: Dp) {
        Column(Modifier.fillMaxWidth().height(PageAdapter.SPOTLIGHT_HEIGHT.dp).padding(start = start, top = 4.dp, end = end, bottom = 2.dp), verticalArrangement = Arrangement.Bottom) {
            Lines(Tv.Type.HERO.font, Tv.Type.HERO.size, listOf(300.dp), Modifier.padding(top = 4.dp))
            Lines(Tv.Type.META.font, Tv.Type.META.size, listOf(250.dp), Modifier.padding(top = 10.dp))
            Lines(Tv.Font.REGULAR, 15f, listOf(500.dp, 380.dp), Modifier.padding(top = 8.dp))
        }
    }

    @Composable
    fun Card(modifier: Modifier = Modifier) {
        Column(modifier) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f / 1.5f).platter(R.drawable.tv_skeleton))
            Lines(Tv.Font.MEDIUM, 13.5f, listOf(84.dp), Modifier.padding(start = 2.dp, top = 9.dp, end = 2.dp))
        }
    }

    /** A shelf: heading, then cards at the shelf's width and gap, running off the edge as a real shelf does. */
    @Composable
    private fun Shelf(start: Dp) {
        Column(Modifier.fillMaxWidth()) {
            Heading(Tv.Font.SEMIBOLD, 18f, 14.dp, 0.dp, 150.dp, start, 0.dp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), enabled = false).padding(start = start, top = 12.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(TileBlocks.GAP.dp)) {
                repeat(8) { Card(Modifier.width(TileBlocks.POSTER_WIDTH.dp)) }
            }
        }
    }

    /** One full grid row, as [TileBlocks.GridRow] lays it out. */
    @Composable
    private fun Grid(columns: Int, start: Dp, end: Dp) {
        Row(Modifier.fillMaxWidth().padding(start = start, top = 12.dp, end = end, bottom = 6.dp)) {
            for (i in 0 until columns) {
                val gap = TileBlocks.GAP.dp
                Card(Modifier.weight(1f).padding(start = gap * i / columns, end = gap - gap * (i + 1) / columns))
            }
        }
    }
}
