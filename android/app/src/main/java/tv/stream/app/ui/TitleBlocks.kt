package tv.stream.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.function.Consumer
import tv.stream.app.R

/** Title and page headings drawn by Compose: the Details and series headers, intro, page titles and catalogue header. */
internal object TitleBlocks {
    /** Hears each painted frame of Details' text title, so instrumentation can prove a logo's text never flashes. */
    @Volatile var titleDrawn: Runnable? = null

    @Composable
    private fun type(role: Tv.Type, color: Int, spacing: Float = 1f) =
        TextBlocks.style(role.font, role.size, color, onArtwork = true, spacing = spacing).copy(letterSpacing = role.tracking.em)

    /** Details: logo or title, facts and description over the title's backdrop. */
    @Composable
    fun Details(item: Item, start: Dp, end: Dp, artwork: Artwork) {
        val media = item.media!!
        val url = media.logo
        var logo by remember(url) { mutableStateOf<Bitmap?>(null) }
        var settled by remember(url) { mutableStateOf(url == null) }
        // Reveal the finished header together: text must never flash before a cold logo download.
        if (url != null) DisposableEffect(url) {
            val handle = artwork.titleLogo(url, Consumer { logo = it; settled = true }, Runnable { settled = true })
            onDispose { handle.cancel() }
        }
        val hidden = !settled || (url == null && item.loading)
        var column = Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(start = start, top = 4.dp, end = end, bottom = 14.dp)
                .semantics { testTagsAsResourceId = true }
                .graphicsLayer { alpha = if (hidden) 0f else 1f }
        if (hidden) column = column.clearAndSetSemantics {}
        Column(column, verticalArrangement = Arrangement.Bottom) {
            val eyebrow = PageAdapter.kind(media)
            BasicText(eyebrow.uppercase(), style = type(Tv.Type.EYEBROW, R.color.tv_text_on_artwork), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val shown = logo
            if (shown != null) Image(shown.asImageBitmap(), media.name, Modifier.padding(top = 10.dp).sizeIn(maxWidth = 400.dp, maxHeight = 92.dp).testTag("details-logo"),
                    contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
            else BasicText(media.name, Modifier.widthIn(max = 640.dp).padding(top = 8.dp).testTag("details-title")
                    .drawWithContent { drawContent(); if (!hidden) titleDrawn?.run() },
                    style = type(Tv.Type.HERO, R.color.tv_text, spacing = 1.18f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            var facts = PageAdapter.metaLine(media)
            if (media.directors.isNotEmpty()) facts += (if (facts.isEmpty()) "" else "  ·  ") + "Directed by " + media.directors.take(2).joinToString(", ")
            if (facts.isNotEmpty()) BasicText(facts, Modifier.widthIn(max = 640.dp).padding(top = 12.dp), style = type(Tv.Type.META, R.color.tv_text_on_artwork),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (media.description.isNotEmpty()) BasicText(media.description, Modifier.widthIn(max = 560.dp).padding(top = 8.dp),
                    style = TextBlocks.style(Tv.Font.REGULAR, 15f, R.color.tv_text, onArtwork = true, spacing = 1.18f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (item.detail.isNotEmpty()) BasicText(item.detail, Modifier.padding(top = 8.dp),
                    style = TextBlocks.style(Tv.Font.REGULAR, 13.5f, R.color.tv_warning, onArtwork = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    /** The series' logo or name above its seasons and episodes. */
    @Composable
    fun SeriesHeader(item: Item, start: Dp, end: Dp, artwork: Artwork) {
        val media = item.media!!
        val url = media.logo
        var logo by remember(url) { mutableStateOf<Bitmap?>(null) }
        if (url != null) DisposableEffect(url) {
            val handle = artwork.request(url, Artwork.Purpose.LOGO, Consumer { logo = it })
            onDispose { handle.cancel() }
        }
        val fade by animateFloatAsState(if (logo != null) 1f else 0f, tween(160), label = "series-logo")
        Box(Modifier.fillMaxWidth().height(80.dp).padding(start = start, top = 12.dp, end = end, bottom = 16.dp)
                .semantics { contentDescription = media.name; testTagsAsResourceId = true }.testTag(item.key),
                contentAlignment = Alignment.CenterStart) {
            val shown = logo
            if (shown != null) Image(shown.asImageBitmap(), null, Modifier.sizeIn(maxWidth = 360.dp, maxHeight = 52.dp).graphicsLayer { alpha = fade }
                    .testTag("series-logo"), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
            else BasicText(media.name, style = type(Tv.Type.TITLE, R.color.tv_text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    fun Intro(item: Item, start: Dp, end: Dp) {
        Column(Modifier.fillMaxWidth().padding(start = start, top = 28.dp, end = end, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (item.meta.isNotEmpty()) BasicText(item.meta.uppercase(), style = TextBlocks.style(Tv.Font.MEDIUM, 13f, R.color.tv_text_secondary, false)
                    .copy(letterSpacing = .12.em, textAlign = TextAlign.Center))
            BasicText(item.title, Modifier.padding(top = 10.dp), style = TextBlocks.style(Tv.Font.SEMIBOLD, 38f, R.color.tv_text, false)
                    .copy(letterSpacing = (-.015).em, textAlign = TextAlign.Center))
            BasicText(item.detail, Modifier.widthIn(max = 620.dp).padding(top = 12.dp), style = TextBlocks.style(Tv.Font.REGULAR, 16f, R.color.tv_text_secondary, false, spacing = 1.22f)
                    .copy(textAlign = TextAlign.Center))
        }
    }

    /** A pushed page's large title, scrolling with its content. */
    @Composable
    fun PageTitle(item: Item, start: Dp, end: Dp) {
        BasicText(item.title, Modifier.fillMaxWidth().padding(start = start, top = 6.dp, end = end, bottom = 4.dp),
                style = type(Tv.Type.TITLE, R.color.tv_text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    @Composable
    fun CatalogueHeader(item: Item, start: Dp, end: Dp) {
        Column(Modifier.fillMaxWidth().padding(start = start, top = 8.dp, end = end + 20.dp, bottom = 14.dp)) {
            BasicText(item.title, style = TextBlocks.style(Tv.Type.TITLE.font, Tv.Type.TITLE.size, R.color.tv_text, false, spacing = 1.18f).copy(letterSpacing = Tv.Type.TITLE.tracking.em),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(item.detail, Modifier.padding(top = 4.dp), style = TextBlocks.style(Tv.Font.REGULAR, 13f, R.color.tv_text_secondary, false),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
