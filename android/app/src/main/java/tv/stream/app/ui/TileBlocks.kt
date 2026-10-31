package tv.stream.app.ui

import android.graphics.Bitmap
import android.os.Parcelable
import android.view.KeyEvent as PlatformKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.gestures.detectTapGestures
import java.util.function.Consumer
import kotlinx.coroutines.android.awaitFrame
import tv.stream.app.R
import tv.stream.app.addon.Title
import tv.stream.app.ui.PageControls.overlay
import tv.stream.app.ui.PageControls.platter

/**
 * Artwork drawn by Compose: cards and category blocks, shelves and grids, Home's spotlight and the season/episode
 * browser. Only artwork grows on focus, rimmed in white over a deep shadow; a caption keeps its size and moves with
 * the artwork's lower edge, so nothing around a card shifts or rescales.
 */
internal object TileBlocks {
    /** Optional observer of the painted season layers, used by the device animation regression. */
    @Volatile var seasonDrawn: ((String, Float, Float) -> Unit)? = null
    const val POSTER_WIDTH = 120
    const val LANDSCAPE_WIDTH = 216
    const val GAP = 20
    private const val BLOCK_RATIO = 0.5f
    private val SeasonEase = CubicBezierEasing(.22f, 1f, .36f, 1f)
    /** Horizontal shelf scrolling: no overshoot, about a third of a second to settle. */
    internal val SCROLL_SPEC: AnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 360f, visibilityThreshold = .5f)

    /** What a card needs from its page. [feature] hears the card the Home spotlight should show. */
    class Cards(val page: PageRows.Page, val artwork: Artwork, val feature: (Item.Tile) -> Unit, val memory: PageRows.ScrollMemory)

    @Composable
    fun rememberArtwork(url: String?, artwork: Artwork, purpose: Artwork.Purpose): Bitmap? {
        // A remembered image shows in the card's first frame, so scrolling back never flashes the placeholder.
        var bitmap by remember(url, purpose) { mutableStateOf(artwork.peek(url, purpose)) }
        if (url != null && bitmap == null) DisposableEffect(url, purpose) {
            val handle = artwork.request(url, purpose, Consumer { bitmap = it })
            onDispose { handle.cancel() }
        }
        return bitmap
    }

    fun label(tile: Item.Tile): String {
        val described = tile.description ?: if (tile.subtitle.isEmpty()) tile.title else tile.title + ". " + tile.subtitle
        return if (tile.flag.isEmpty()) described else described + ". " + tile.flag
    }

    /** A rounded image (or a titled placeholder) with a one-line title and subtitle that never clip. */
    @Composable
    fun Card(tile: Item.Tile, style: Item.Tile.Style, cards: Cards, modifier: Modifier = Modifier, onKey: ((Int) -> Boolean)? = null) {
        if (tile.block) { CategoryBlock(tile, cards, modifier, onKey); return }
        val latest by rememberUpdatedState(tile)
        val control = PageControls.control(tile.key, label(tile), tile.action, cards.page, 10.dp, artwork = true,
                onFocus = { has -> if (has) cards.feature(latest) }, onKey = onKey)
        val duration = if (control.focused) 200 else 160
        val scale by animateFloatAsState(if (control.focused) Tv.LIFT_TILE else 1f, tween(duration, easing = PageControls.Decelerate), label = "tile")
        val raise by animateDpAsState(if (control.focused) 18.dp else 0.dp, tween(duration, easing = PageControls.Decelerate), label = "tile-raise")
        val poster = style == Item.Tile.Style.POSTER
        val shape = RoundedCornerShape(10.dp)
        val episode = tile.episodeNumber >= 0
        var frameHeight by remember { mutableStateOf(0) }
        Column(modifier.then(control.input)) {
            Box(Modifier.fillMaxWidth().aspectRatio(if (poster) 1f / 1.5f else 1f / .5625f)
                    .onSizeChanged { frameHeight = it.height }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        shadowElevation = raise.toPx(); this.shape = shape; clip = true
                    }
                    .then(control.placed)
                    .testTag(tile.key + ":art")
                    .platter(R.drawable.tv_artwork)
                    .overlay(R.drawable.tv_focus_rim, control.focused)) {
                val bitmap = rememberArtwork(tile.image, cards.artwork, Artwork.Purpose.THUMBNAIL)
                if (bitmap == null && !episode) BasicText(tile.title, Modifier.align(Alignment.Center).padding(horizontal = 10.dp).clearAndSetSemantics {},
                        style = TextBlocks.style(Tv.Font.SEMIBOLD, if (poster) 15f else 17f, R.color.tv_text, false, spacing = 1.18f)
                                .copy(textAlign = TextAlign.Center, letterSpacing = (-.005).em), maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (tile.progress >= 0) Progress(tile.progress)
                if (tile.badge != 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp).sizeIn(24.dp, 24.dp, 24.dp, 24.dp)
                        .platter(R.drawable.tv_badge).padding(5.dp)) {
                    PageControls.Icon(tile.badge, 14, colorResource(R.color.tv_text))
                }
                if (tile.flag.isNotEmpty()) BasicText(tile.flag.uppercase(), Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 7.dp)
                        .platter(R.drawable.tv_flag).padding(horizontal = 7.dp, vertical = 2.dp)
                        // The card's description already ends with the flag; the tag only shows that it is painted.
                        .clearAndSetSemantics { testTag = tile.key + ":flag" },
                        style = TextBlocks.style(Tv.Font.MEDIUM, 11f, R.color.tv_on_focus, false).copy(letterSpacing = .06.em), maxLines = 1)
            }
            // The caption follows the artwork's grown lower edge in the same layer pass that grows the artwork, so
            // the two always move together; recomposing for it each frame could leave one a frame or more behind.
            Column(Modifier.graphicsLayer { translationY = (scale - 1f) * frameHeight / 2f }) {
                if (episode) BasicText("Episode " + tile.episodeNumber, Modifier.padding(start = 2.dp, top = 9.dp, end = 2.dp).testTag(tile.key + ":number"),
                        style = TextBlocks.style(Tv.Font.MEDIUM, 12f, R.color.tv_text_secondary, true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val caption = PageControls.stateColor(R.color.tv_caption, control.focused)
                BasicText(tile.title, Modifier.padding(start = 2.dp, top = (if (episode) 3 else 9).dp, end = 2.dp).testTag(tile.key + ":title"),
                        style = TextBlocks.style(Tv.Font.MEDIUM, if (episode) 15f else 13.5f, R.color.tv_text, true, spacing = 1.18f).copy(color = caption),
                        maxLines = if (episode) 2 else 1, overflow = TextOverflow.Ellipsis)
                val detail = if (episode) tile.synopsis else tile.subtitle
                if (detail.isNotEmpty()) BasicText(detail, Modifier.padding(start = 2.dp, top = 4.dp, end = 2.dp).testTag(tile.key + ":description"),
                        style = TextBlocks.style(Tv.Font.REGULAR, 12f, R.color.tv_text_secondary, true, spacing = 1.18f),
                        maxLines = if (episode) 3 else 1, overflow = TextOverflow.Ellipsis)
                if (episode && tile.releaseDate.isNotEmpty()) BasicText(tile.releaseDate, Modifier.padding(start = 2.dp, top = 7.dp, end = 2.dp).testTag(tile.key + ":date"),
                        style = TextBlocks.style(Tv.Font.MEDIUM, 12f, R.color.tv_text_tertiary, true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    /** A progress bar over the artwork, which darkens beneath it so it stays legible on light frames. */
    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.Progress(progress: Float) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp).platter(R.drawable.tv_tile_shade))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp).height(4.dp).platter(R.drawable.tv_progress_track)) {
            val done = progress.coerceIn(.02f, 1f)
            Box(Modifier.weight(done).height(4.dp).platter(R.drawable.tv_progress_fill))
            if (done < 1f) Box(Modifier.weight(1f - done))
        }
    }

    /** A category block: a large centred name over a restrained colour wash, with room for focus growth. */
    @Composable
    private fun CategoryBlock(tile: Item.Tile, cards: Cards, modifier: Modifier, onKey: ((Int) -> Boolean)?) {
        val control = PageControls.control(tile.key, tile.description ?: tile.title, tile.action, cards.page, 10.dp, onKey = onKey)
        val duration = if (control.focused) 200 else 160
        val scale by animateFloatAsState(if (control.focused) Tv.LIFT_TILE else 1f, tween(duration, easing = PageControls.Decelerate), label = "block")
        val raise by animateDpAsState(if (control.focused) 18.dp else 0.dp, tween(duration, easing = PageControls.Decelerate), label = "block-raise")
        val shape = RoundedCornerShape(10.dp)
        val wash = remember(tile.colorIndex) { CategoryBackground(tile.colorIndex) }
        Box(modifier.fillMaxWidth().aspectRatio(1f / BLOCK_RATIO)
                .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = raise.toPx(); this.shape = shape; clip = true }
                .then(control.placed)
                .drawBehind {
                    wash.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    drawIntoCanvas { wash.draw(it.nativeCanvas) }
                }
                .overlay(R.drawable.tv_focus_rim, control.focused)
                .then(control.input), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(horizontal = 12.dp).clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(tile.title, style = TextBlocks.style(Tv.Font.SEMIBOLD, 22f, R.color.tv_text, false, spacing = 1.18f)
                        .copy(textAlign = TextAlign.Center, letterSpacing = (-.005).em), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (tile.subtitle.isNotEmpty()) BasicText(tile.subtitle, Modifier.padding(top = 4.dp),
                        style = TextBlocks.style(Tv.Font.REGULAR, 12f, R.color.tv_text_secondary, false).copy(textAlign = TextAlign.Center),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    /**
     * Scrolls so a focused card keeps some room around it, rather than stopping at the very edge; a card already fully
     * inside the shelf's padding stays still, for the remote and the pointer alike.
     */
    @OptIn(ExperimentalFoundationApi::class)
    private class ShelfScroll(private val margin: Float, private val startPadding: Float, private val endPadding: Float) : BringIntoViewSpec {
        /**
         * The shelf glides rather than snaps: a critically damped spring, softer than the default, keeps its velocity
         * when the next press retargets it, so held or repeated presses scroll continuously. A tween can't do that
         * here: bring-into-view re-evaluates its spec from the current value every frame.
         */
        override val scrollAnimationSpec: AnimationSpec<Float> = SCROLL_SPEC
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            if (offset >= startPadding && offset + size <= containerSize - endPadding) return 0f
            val before = offset - margin
            val after = offset + size + margin - containerSize
            return if (before < 0f) before else if (after > 0f) after else 0f
        }
    }

    @Composable
    private fun rememberShelf(memory: PageRows.ScrollMemory, key: String, restoreIndex: Int): LazyListState {
        val saved = remember(key) { memory.load(key) as? android.os.Bundle }
        val state = rememberLazyListState(saved?.getInt("index") ?: restoreIndex.coerceAtLeast(0), saved?.getInt("offset") ?: 0)
        LaunchedEffect(state, key) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { (index, offset) ->
                memory.save(key, android.os.Bundle().apply { putInt("index", index); putInt("offset", offset) } as Parcelable)
            }
        }
        return state
    }

    /** A heading over a horizontally scrolling row of cards. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun Shelf(item: Item, cards: Cards, layout: TextBlocks.Layout, season: FocusRequester? = null, active: Boolean = true) {
        Column(Modifier.fillMaxWidth()) {
            if (item.title.isNotEmpty() || item.detail.isNotEmpty()) TextBlocks.Header(item.title, item.detail, false, 14, layout)
            val tiles = item.tiles
            val state = rememberShelf(cards.memory, item.key, tiles.indexOfFirst { it.key == cards.page.restore })
            val density = LocalDensity.current
            val spec = remember(density, layout) {
                with(density) { ShelfScroll(96.dp.toPx(), layout.start.toPx(), layout.end.toPx()) }
            }
            val poster = item.style == Item.Tile.Style.POSTER
            val width = (if (poster) POSTER_WIDTH else LANDSCAPE_WIDTH).dp
            CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                LazyRow(Modifier.fillMaxWidth(), state = state,
                        contentPadding = PaddingValues(start = layout.start, top = (if (season == null) 12 else 16).dp, end = layout.end, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy((if (season == null) GAP else GAP + 2).dp)) {
                    itemsIndexed(tiles, key = { _, tile -> tile.key }) { index, tile ->
                        // Right holds at the shelf's end; Left at its start goes on to the rail.
                        val atEnd: (Int) -> Boolean = { code -> code == PlatformKeyEvent.KEYCODE_DPAD_RIGHT && index == tiles.size - 1 }
                        Card(tile, item.style, cards, Modifier.width(width).then(
                                Modifier.focusProperties { canFocus = active; if (season != null) up = season }), onKey = atEnd)
                    }
                }
            }
        }
    }

    /** One row of a grid. Every column gives up the same share of the gaps, so a short last row lines up with full ones. */
    @Composable
    fun GridRow(item: Item, cards: Cards, start: Dp, end: Dp) {
        val columns = Integer.parseInt(item.value)
        val tiles = item.tiles
        Row(Modifier.fillMaxWidth().padding(start = start, top = 12.dp, end = end, bottom = 6.dp)) {
            for (i in 0 until columns) {
                val gap = GAP.dp
                Box(Modifier.weight(1f).padding(start = gap * i / columns, end = gap - gap * (i + 1) / columns)) {
                    val tile = tiles.getOrNull(i)
                    if (tile != null) key(tile.key) {
                        val atEnd: (Int) -> Boolean = { code -> code == PlatformKeyEvent.KEYCODE_DPAD_RIGHT && i == tiles.size - 1 }
                        Card(tile, item.style, cards, onKey = atEnd)
                    }
                }
            }
        }
    }

    /** The headline a Home card leads with, and what its spotlight opens. */
    class Featured(val media: Title?, val key: String, val action: Runnable?, val headline: String)

    /** Home's focused card: its title, artwork and description. Clicking opens that card, by touch or pointer only. */
    @Composable
    fun Spotlight(featured: Featured?, height: Dp, start: Dp, end: Dp, artwork: Artwork, focused: (String) -> Unit) {
        val media = featured?.media
        val latest by rememberUpdatedState(featured)
        val open = {
            val now = latest
            if (now?.action != null) { focused(now.key); now.action.run() }
        }
        val label = if (media == null) "" else if (featured.headline.isEmpty()) media.name else media.name + ". " + featured.headline
        // A touch shortcut to the focused card. It never takes focus, or Up from the first shelf lands on it invisibly.
        Column(Modifier.fillMaxWidth().height(height).padding(start = start, top = 4.dp, end = end, bottom = 2.dp)
                .semantics(mergeDescendants = true) {
                    testTagsAsResourceId = true; contentDescription = label
                    onClick { open(); true }
                }.testTag("spotlight")
                .pointerInput(Unit) { detectTapGestures { open() } },
                verticalArrangement = Arrangement.Bottom) {
            if (media == null) return@Column
            val logo = rememberArtwork(media.logo, artwork, Artwork.Purpose.LOGO)
            if (logo != null) Image(logo.asImageBitmap(), null, Modifier.padding(top = 6.dp).sizeIn(maxWidth = 360.dp, maxHeight = 72.dp),
                    contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
            else BasicText(media.name, Modifier.widthIn(max = 600.dp).padding(top = 4.dp), style = TextBlocks.style(Tv.Type.HERO.font, Tv.Type.HERO.size, R.color.tv_text, true)
                    .copy(letterSpacing = Tv.Type.HERO.tracking.em), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = PageAdapter.metaLine(media, false)
            val type = PageAdapter.kind(media)
            val lead = if (featured.headline.isEmpty()) type else featured.headline
            val line = if (lead.isEmpty()) facts else if (facts.isEmpty()) lead else "$lead  ·  $facts"
            if (line.isNotEmpty()) {
                val text = colorResource(R.color.tv_text)
                val semibold = remember { FontFamily(Font(R.font.geist_semibold)) }
                // A headline (new episodes) reads first, in white semibold, ahead of the usual facts.
                val annotated = buildAnnotatedString {
                    if (featured.headline.isNotEmpty()) {
                        withStyle(SpanStyle(fontFamily = semibold, color = text)) { append(lead) }
                        append(line.substring(lead.length))
                    } else append(line)
                }
                BasicText(annotated, Modifier.widthIn(max = 600.dp).padding(top = 10.dp), style = TextBlocks.style(Tv.Type.META.font, Tv.Type.META.size, R.color.tv_text_on_artwork, true),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (media.description.isNotEmpty()) BasicText(media.description, Modifier.widthIn(max = 520.dp).padding(top = 8.dp),
                    style = TextBlocks.style(Tv.Font.REGULAR, 15f, R.color.tv_text, true, spacing = 1.18f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    /** Focus changes just the episode shelf; the season strip stays put and keeps its focus. */
    @Composable
    fun Episodes(item: Item, cards: Cards, layout: TextBlocks.Layout, focused: (String) -> Unit, restoringEpisode: () -> Boolean, changed: (Item) -> Unit) {
        val panes = item.panes!!
        val order = remember(panes) { ArrayList(panes.keys) }
        var selected by remember(item.key) { mutableStateOf(item.value) }
        if (!panes.containsKey(selected)) selected = if (panes.containsKey(item.value)) item.value else order.first()
        var direction by remember { mutableStateOf(1) }
        var entering by remember { mutableStateOf(0) }
        val registry = PageFocus.Local.current
        val select: (String) -> Unit = { key ->
            if (key != selected) {
                val pane = panes[key]
                if (pane != null && pane.isNotEmpty()) {
                    direction = if (order.indexOf(key) >= order.indexOf(selected)) 1 else -1
                    selected = key
                    item.value = key
                    for (action in item.actions) action.selected = action.key == key
                    changed(item)
                    item.keyboardInput?.accept(key)
                }
            }
        }
        val season = remember(selected) { FocusRequester() }
        // Down, OK or a tap go into the shelf at its first card on screen, once it shows this season.
        fun enterShelf(): Boolean {
            val shelf = panes[selected]?.firstOrNull() ?: return false
            val first = shelf.tiles.firstOrNull { registry?.owns(it.key) == true } ?: return false
            return registry?.request(first.key) == true
        }
        LaunchedEffect(entering, selected) {
            if (entering > 0) {
                // Right followed immediately by Down waits for the incoming season, never its outgoing cards.
                for (frame in 0 until 10) { awaitFrame(); if (enterShelf()) break }
                entering = 0
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = layout.start, top = 14.dp, end = layout.end, bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (action in item.actions) key(action.key) {
                    val shownAction = Item.Action(action.key, action.style, action.label, action.action).also {
                        it.icon = action.icon; it.detail = action.detail; it.text = action.text; it.selected = action.key == selected
                    }
                    PageControls.Button(shownAction, cards.page,
                            if (action.key == selected) Modifier.focusRequester(season) else Modifier,
                            // A temporary focus on the first season during episode restoration must not change the pane.
                            onFocus = { has -> if (has && !restoringEpisode()) select(action.key) },
                            onHover = { over -> if (over) registry?.request(action.key) },
                            onKey = { code ->
                                if (code == PlatformKeyEvent.KEYCODE_DPAD_DOWN) { if (!enterShelf()) entering++; true } else false
                            },
                            click = { select(action.key); focused(action.key); entering++ })
                }
            }
            val travel = with(LocalDensity.current) { 24.dp.toPx() }
            val moving = direction
            AnimatedContent(selected, Modifier.fillMaxWidth().testTag("season-shelf"), label = "season", transitionSpec = {
                EnterTransition.None togetherWith ExitTransition.None
            }, contentAlignment = Alignment.TopStart) { key ->
                // These child transitions also retain the outgoing content until its last painted frame.
                val alpha by transition.animateFloat(transitionSpec = { tween(260, easing = SeasonEase) }, label = "season-opacity") {
                    if (it == EnterExitState.Visible) 1f else 0f
                }
                val x by transition.animateFloat(transitionSpec = { tween(260, easing = SeasonEase) }, label = "season-position") {
                    when (it) { EnterExitState.PreEnter -> travel * moving; EnterExitState.Visible -> 0f; EnterExitState.PostExit -> -travel * moving }
                }
                val shelf = panes[key]?.firstOrNull()
                if (shelf != null) Box(Modifier.graphicsLayer { this.alpha = alpha; translationX = x }
                        .drawWithContent { drawContent(); seasonDrawn?.invoke(key, alpha, x) }) {
                    Shelf(shelf, cards, layout, season, active = key == selected)
                }
            }
        }
    }
}
