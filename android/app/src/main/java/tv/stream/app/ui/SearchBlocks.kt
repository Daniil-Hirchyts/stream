package tv.stream.app.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tv.stream.app.R
import tv.stream.app.ui.PageControls.platter

/** Search owns its keyboard, grid and reflow. Provider answers replace values without rebuilding the keyboard. */
internal object SearchBlocks {
    data class Frame(val columns: Int, val resultsAlpha: Float, val keyboardAlpha: Float, val keyboardX: Float, val keyboardWidth: Float, val cards: List<android.graphics.Rect>)
    /** Observes the values applied to painted layers, for the device reflow regression. */
    @Volatile var drawn: ((Frame) -> Unit)? = null
    private val Motion = CubicBezierEasing(.32f, 0f, .16f, 1f)
    /** Room above and below results for a focused card's 1.1x lift (about 11 dp on a poster) and its raised shadow. */
    const val RESULT_MARGIN = 28
    /** Share of the space above the results, from the window's top, where cards have faded out (under Back and the clock). */
    private const val FADE_CLEAR = .35f
    /** Room each side of the grid's faded layer, which clips, for a focused card's lift, rim and shadow. */
    private const val RESULT_SIDE = 28

    class State(initial: Item, initialPage: Int = 0, private val modeChanged: (Boolean) -> Unit) {
        var item by mutableStateOf(initial); private set
        var query by mutableStateOf(initial.value); private set
        var visible by mutableStateOf(initial.keyboardVisible); private set
        val keyboardVisible: Boolean get() = visible
        fun scrollToResult(index: Int) { item.tiles.getOrNull(index)?.let { request?.invoke(it.key) } }
        var columns by mutableIntStateOf(if (visible) 4 else 6); internal set
        var page by mutableIntStateOf(initialPage.coerceIn(0, 2))
        var language by mutableStateOf(initial.keyboardLanguage)
        var lastKeyboard = initial.key + ":" + characters()[0]
        var lastResult = ""
        var changing by mutableStateOf(false); internal set
        var frame: Frame? = null; internal set
        var request: ((String) -> Unit)? = null
        var current: (() -> String)? = null
        var focusAfter = false; private set
        private var pendingFocus: String? = null
        fun delivered(key: String) { if (pendingFocus == key) pendingFocus = null }
        var rows: List<List<Key>> = emptyList(); internal set
        fun update(next: Item) { item = next; query = next.value; language = next.keyboardLanguage }
        fun keyboard(show: Boolean, focus: Boolean = true) {
            if (visible == show) { if (focus && !changing) request?.invoke(if (show) lastKeyboard else result()); return }
            pendingFocus = if (focus) { if (show) lastKeyboard else result() } else null
            focusAfter = focus; visible = show; item.keyboardVisible = show; modeChanged(show)
        }
        fun toggle() = keyboard(!visible)
        fun result(): String = lastResult.takeIf { key -> item.tiles.any { it.key == key } } ?: item.tiles.firstOrNull()?.key
                ?: item.actions.firstOrNull()?.key ?: "search-results"
        fun focused(key: String, arrowing: Boolean) {
            if (key.startsWith(item.key + ":")) lastKeyboard = key
            else if (item.tiles.any { it.key == key }) {
                lastResult = key
                if (arrowing && visible && !changing) keyboard(false, false)
            }
        }
        fun persistent(key: String): String = pendingFocus ?: when {
            key == "search-results" -> if (visible) lastKeyboard else result()
            !visible && key.startsWith(item.key + ":") -> result()
            else -> key
        }
        fun key(code: Int): Boolean {
            val key = current?.invoke() ?: return false
            if (visible && key.startsWith(item.key + ":")) {
                val row = rows.firstOrNull { r -> r.any { it.key == key } } ?: return false
                val at = row.indexOfFirst { it.key == key }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { if (at > 0) request?.invoke(row[at - 1].key); return true }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && at == row.lastIndex && item.tiles.isNotEmpty()) {
                    keyboard(false); return true
                }
                // Without results, a recovery action (Reload) beside the keyboard is the next stop.
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && at == row.lastIndex && item.actions.isNotEmpty()) {
                    request?.invoke(item.actions[0].key); return true
                }
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && !visible) {
                val index = item.tiles.indexOfFirst { it.key == key }
                if (key == "search-results" || index >= 0 && index % columns == 0) { keyboard(true); return true }
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && visible &&
                    (key == "search-results" || item.tiles.any { it.key == key } || item.actions.any { it.key == key })) {
                request?.invoke(lastKeyboard); return true
            }
            return false
        }
        fun type(value: String) { query = value.take(120); item.keyboardInput?.accept(query) }
        fun characters(): String = when (page) {
            0 -> KeyboardLanguages.alphabet(language)
            1 -> "1234567890-':&!?.,()#+@/\"_"
            else -> KeyboardLanguages.accents(language)
        }
    }
    /**
     * Scrolls the results so a focused card, with its lift and focus rim, sits a margin inside the results area
     * instead of stopping at its edge; a card already that far inside stays still. The grid itself reaches the
     * window's edges (cards fade out beyond the area), so `top` and `bottom` also cover that extension.
     */
    @OptIn(ExperimentalFoundationApi::class)
    class ResultScroll(private val top: () -> Float, private val bottom: () -> Float) : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                distance(offset, size, containerSize, top(), bottom())
        companion object {
            fun distance(offset: Float, size: Float, containerSize: Float, top: Float, bottom: Float): Float {
                if (offset >= top && offset + size <= containerSize - bottom) return 0f
                // A card taller than the room between the margins keeps its top (artwork) in view.
                if (size + top + bottom > containerSize || offset < top) return offset - top
                return offset + size + bottom - containerSize
            }
        }
    }
    class Key(val key: String, val label: String, val description: String, val cells: Int, val run: () -> Unit)

    private fun keys(state: State): List<List<Key>> {
        val item = state.item
        val rows = ArrayList<MutableList<Key>>()
        fun key(label: String, description: String, cells: Int, run: () -> Unit) = Key(item.key + ":" + if (description == "Change keyboard language") "Language" else label, label, description, cells, run)
        val chars = state.characters()
        for (part in chars.chunked(6)) rows.add(part.map { c -> key(c.toString(), "Type $c", 1) { state.type(state.query + c) } }.toMutableList())
        val used = chars.length % 6
        var remaining = 6 - used
        if (used == 0 || remaining < 3) { rows.add(ArrayList()); remaining = 6 }
        rows.last().add(key("Space", "Insert a space", (remaining + 1) / 2) { state.type(state.query + " ") })
        rows.last().add(key("⌫", "Delete last character", remaining / 2) { state.type(state.query.dropLast(1)) })
        val actions = ArrayList<Key>()
        for (page in 0..2) if (page != state.page) {
            val name = when (page) { 0 -> KeyboardLanguages.alphabet(state.language).take(3); 1 -> "123"; else -> "àéü" }
            actions.add(key(name, "Show $name keys", 2) { state.page = page; state.request?.invoke(item.key + ":" + state.characters()[0]) })
        }
        actions.add(key("Clear", "Clear search", 2) { state.type("") }); rows.add(actions)
        rows.add(mutableListOf(key(KeyboardLanguages.name(state.language), "Change keyboard language", 6) {
            if (item.keyboardLanguages.size == 1) item.action?.run()
            else {
                val next = item.keyboardLanguages[(item.keyboardLanguages.indexOf(state.language) + 1) % item.keyboardLanguages.size]
                item.keyboardLanguage = next; state.language = next; state.page = 0; item.keyboardLanguageChanged?.accept(next)
                state.request?.invoke(item.key + ":Language")
            }
        }))
        return rows
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun Content(state: State, cards: TileBlocks.Cards, start: androidx.compose.ui.unit.Dp, end: androidx.compose.ui.unit.Dp) {
        val item = state.item
        val registry = PageFocus.Local.current
        val owner = LocalView.current
        val scope = rememberCoroutineScope()
        val saved = remember(item.value) { cards.memory.load("search:" + item.value) as? Bundle }
        val restored = item.tiles.indexOfFirst { it.key == cards.page.restore }.coerceAtLeast(0)
        val grid = key(item.value) { rememberLazyGridState(saved?.getInt("index") ?: restored, saved?.getInt("offset") ?: 0) }
        val neutral = remember { FocusRequester() }
        DisposableEffect(registry, neutral) {
            registry?.register("search-results", neutral)
            onDispose { registry?.unregister("search-results", neutral) }
        }
        val keyboard = remember { Animatable(if (state.visible) 0f else 1f) }
        val opacity = remember { Animatable(1f) }
        var split by remember { mutableStateOf(state.visible) }
        val keys = keys(state)
        state.rows = keys
        DisposableEffect(state, registry, grid) {
            var active = true
            var pending: kotlinx.coroutines.Job? = null
            state.current = { owner.findFocus()?.tag as? String ?: "" }
            state.request = { key ->
                pending?.cancel()
                pending = scope.launch {
                val index = state.item.tiles.indexOfFirst { it.key == key }
                if (index >= 0 && registry?.owns(key) != true) grid.scrollToItem(index)
                awaitFrame()
                if (active) {
                    val focused = if (key == "search-results") { neutral.requestFocus(); true } else registry?.request(key) == true
                    if (focused) state.delivered(key)
                }
            } }
            onDispose { active = false; pending?.cancel(); state.current = null; state.request = null; state.frame = null }
        }
        LaunchedEffect(state) {
            snapshotFlow { state.page }.collect { page -> cards.memory.save("keyboard-page", Bundle().apply { putInt("page", page) }) }
        }
        LaunchedEffect(grid, item.value) {
            snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { (i, offset) ->
                if (state.item.tiles.isNotEmpty()) cards.memory.save("search:" + state.item.value, Bundle().apply { putInt("index", i); putInt("offset", offset) })
            }
        }
        // A query answer may add the remembered card beyond the initially composed rows.
        LaunchedEffect(item.tiles, cards.page.restore) {
            val wanted = cards.page.restore
            // A State rebuilt on Back has no last result; neither fallback below may replace the restored card.
            val card = wanted.isNotEmpty() && item.tiles.any { it.key == wanted }
            if (card) state.lastResult = wanted
            if (wanted == "search-results" && !state.visible) state.request?.invoke(wanted)
            else if (card && registry?.owns(wanted) != true) state.request?.invoke(wanted)
            else if (!state.visible && !state.changing && owner.tag == "search-results" && item.tiles.isNotEmpty()) state.request?.invoke(state.result())
        }
        LaunchedEffect(state.visible) {
            val show = state.visible
            if (split == show && keyboard.value == if (show) 0f else 1f) return@LaunchedEffect
            state.changing = true
            if (state.focusAfter && (show || owner.tag?.toString()?.startsWith(item.key + ":") == true)) neutral.requestFocus()
            try {
                coroutineScope {
                    launch { keyboard.animateTo(if (show) 0f else 1f, tween(360, easing = Motion)) }
                    launch {
                        if (split != show) {
                            opacity.animateTo(0f, tween((140 * opacity.value).toInt().coerceAtLeast(1)))
                            val index = grid.firstVisibleItemIndex; val offset = grid.firstVisibleItemScrollOffset
                            split = show; state.columns = if (show) 4 else 6
                            // An empty Search composes no grid; scrolling a never-laid-out lazy grid would never return.
                            if (grid.layoutInfo.totalItemsCount > 0) grid.scrollToItem(index, offset)
                            awaitFrame()
                        }
                        opacity.animateTo(1f, tween(220))
                    }
                }
                if (state.focusAfter) state.request?.invoke(if (show) state.lastKeyboard else state.result())
            } finally { state.changing = false }
        }
        val editorWidth = 263.dp
        val density = LocalDensity.current
        val widthPx = with(density) { editorWidth.toPx() }
        val distance = widthPx + with(density) { start.toPx() }
        val keyboardShown by remember(state, keyboard) { derivedStateOf { keyboard.value < 1f || state.visible } }
        Box(Modifier.fillMaxSize().padding(start = start, top = 8.dp, end = end, bottom = 12.dp)
                .semantics { testTagsAsResourceId = true }.testTag("search-layout")) {
            if (keyboardShown) {
                var editorHeight by remember { mutableIntStateOf(0) }
                Row(Modifier.width(editorWidth).graphicsLayer { translationX = -distance * keyboard.value; alpha = 1f - keyboard.value }
                        .focusProperties { canFocus = !state.changing && state.visible }
                        .testTag("search-keyboard"), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(236.dp).onSizeChanged { editorHeight = it.height }) {
                        BasicText(state.query.ifEmpty { "Search" }, Modifier.padding(start = 6.dp, end = 6.dp, bottom = 12.dp)
                                .width(224.dp).testTag("search-query").padding(horizontal = 2.dp),
                                style = TextBlocks.style(Tv.Font.MEDIUM, 22f, if (state.query.isEmpty()) R.color.tv_text_tertiary else R.color.tv_text, false),
                                maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        val hairline = colorResource(R.color.tv_hairline)
                        Box(Modifier.fillMaxWidth().graphicsLayer { shadowElevation = 6.dp.toPx(); shape = RoundedCornerShape(22.dp) }
                                .platter(R.drawable.tv_dialog).testTag("keyboard-surface").padding(6.dp)) {
                            // The rule is painted behind the gap before commands, without becoming a keyboard row.
                            Column(Modifier.fillMaxWidth().testTag("keyboard-keys").drawBehind {
                                val y = (38 * (keys.lastIndex - 1) + 2).dp.toPx()
                                drawLine(hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            }) {
                                for ((i, row) in keys.withIndex()) {
                                    val commands = i == keys.lastIndex - 1
                                    if (i > 0) Spacer(Modifier.height((if (commands) 12 else 4).dp))
                                    Row(Modifier.fillMaxWidth().height(34.dp).then(if (commands) Modifier.testTag("keyboard-actions") else Modifier),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val fullCells = row.sumOf { it.cells } == 6
                                        for (key in row) key(key.key) {
                                            // Full rows use the six-cell lattice; short four/five-letter tails spread their hit cells.
                                            KeyButton(key, cards.page, if (fullCells) Modifier.width((key.cells * 34 + (key.cells - 1) * 4).dp) else Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Box(Modifier.padding(start = 10.dp, end = 16.dp).width(1.dp).height(with(density) { editorHeight.toDp() })
                            .background(colorResource(R.color.tv_hairline)))
                }
            }
            // No clip on the pane: the lazy grid clips only vertically, at the window's edges, where cards fade out.
            // Its content padding and ResultScroll keep a focused card's lift inside the results area.
            Column(Modifier.fillMaxSize().padding(start = if (split) editorWidth else 0.dp)
                    .graphicsLayer { alpha = opacity.value }.testTag("search-results")
                    .focusRequester(neutral).onFocusChanged { if (it.isFocused) { owner.tag = "search-results"; cards.page.focused("search-results") } }.focusable()
                    .drawWithContent {
                        drawContent()
                        Frame(state.columns, opacity.value, 1f - keyboard.value, -distance * keyboard.value, widthPx,
                            registry?.keys()?.filter { key -> item.tiles.any { it.key == key } }?.mapNotNull { registry.region(it)?.bounds?.invoke() } ?: emptyList()).also { state.frame = it; drawn?.invoke(it) }
                    }) {
                if (item.meta.isNotEmpty()) BasicText(item.meta, Modifier.padding(top = 6.dp, bottom = 4.dp), style = TextBlocks.style(Tv.Font.REGULAR, 13f, R.color.tv_warning, false))
                if (item.actions.isNotEmpty()) PageControls.Actions(item.actions, cards.page, 0.dp, 0.dp)
                if (item.tiles.isNotEmpty() || item.loading) {
                    // The space between the results area and the window's top and bottom: the grid extends over it, so
                    // rows scroll on under the toolbar and off the screen's foot instead of being cut at the area's edge.
                    var above by remember { mutableIntStateOf(0) }
                    var below by remember { mutableIntStateOf(0) }
                    val view = LocalView.current
                    val margin = with(density) { RESULT_MARGIN.dp.toPx() }
                    val side = with(density) { RESULT_SIDE.dp.roundToPx() }
                    val spec = remember(margin) { ResultScroll({ margin + above }, { margin + below }) }
                    Box(Modifier.fillMaxSize().onGloballyPositioned {
                        val top = it.positionInWindow().y.toInt().coerceAtLeast(0)
                        above = top; below = (view.rootView.height - top - it.size.height).coerceAtLeast(0)
                    }) {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(GridCells.Fixed(state.columns), state = grid,
                            modifier = Modifier.fillMaxSize().layout { measurable, constraints ->
                                val height = constraints.maxHeight + above + below; val width = constraints.maxWidth + 2 * side
                                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height))
                                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-side, -above) }
                            }.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                                drawContent()
                                if (size.height <= 0f) return@drawWithContent
                                val h = size.height
                                drawRect(Brush.verticalGradient(0f to Color.Transparent, above * FADE_CLEAR / h to Color.Transparent,
                                        above / h to Color.Black, (h - below) / h to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                            },
                            contentPadding = with(density) { PaddingValues(start = RESULT_SIDE.dp, end = RESULT_SIDE.dp,
                                    top = RESULT_MARGIN.dp + above.toDp(), bottom = RESULT_MARGIN.dp + below.toDp()) },
                            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (item.loading && item.tiles.isEmpty()) items(state.columns * 2) { SkeletonBlocks.Card() }
                        else itemsIndexed(item.tiles, key = { _, tile -> tile.key }) { index, tile ->
                            TileBlocks.Card(tile, Item.Tile.Style.POSTER, cards, onKey = { code -> code == KeyEvent.KEYCODE_DPAD_RIGHT && (index + 1) % state.columns == 0 })
                        }
                    }
                    }
                    }
                } else Box(Modifier.fillMaxSize().padding(bottom = 52.dp), contentAlignment = Alignment.Center) {
                    BasicText(item.detail.ifEmpty { if (item.loading) "Searching…" else "Search films and series" }, style = TextBlocks.style(Tv.Font.REGULAR, 18f, R.color.tv_text_secondary, false))
                }
            }
        }
    }

    @Composable
    private fun KeyButton(key: Key, page: PageRows.Page, modifier: Modifier) {
        val character = key.description.startsWith("Type ")
        val control = PageControls.control(key.key, key.description, Runnable {}, page, 17.dp, click = {
            if (key.description.startsWith("Delete") || key.description.startsWith("Clear")) Sounds.play(Sounds.Sound.DELETE)
            else if (character || key.label == "Space") Sounds.play(Sounds.Sound.KEY)
            key.run()
        })
        val grown = control.focused || control.hovered
        BoxWithConstraints(modifier.fillMaxHeight().then(control.input), contentAlignment = Alignment.Center) {
            val lift = if (character) Tv.LIFT_ICON else 1f + (34.dp / maxWidth) * (Tv.LIFT_ICON - 1f)
            val width = if (character) Modifier.width(34.dp) else Modifier.fillMaxWidth()
            Box(width.height(34.dp).then(PageControls.run { Modifier.lift(grown, lift, false, if (character) CircleShape else RoundedCornerShape(17.dp)) })
                    .then(control.placed).testTag(key.key + ":surface").platter(if (character) R.drawable.tv_keyboard_key else R.drawable.tv_keyboard_action, control.focused, control.pressed, control.hovered), contentAlignment = Alignment.Center) {
                val color = PageControls.stateColor(R.color.tv_on_glass, control.focused, control.pressed)
                if (key.label == "⌫") PageControls.Icon(R.drawable.ic_tv_backspace, 16, color)
                else if (key.description == "Change keyboard language") Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                    PageControls.Icon(R.drawable.ic_tv_language, 14, color)
                    Spacer(Modifier.width(6.dp))
                    BasicText(key.label, Modifier.weight(1f).testTag(key.key + ":label"),
                            style = TextBlocks.style(Tv.Font.MEDIUM, 12f, R.color.tv_text, false).copy(color = color, textAlign = TextAlign.Center), maxLines = 1)
                } else BasicText(key.label, Modifier.testTag(key.key + ":label"), style = TextBlocks.style(Tv.Font.MEDIUM, if (character) 16f else 12f, R.color.tv_text, false)
                        .copy(color = color), maxLines = 1)
            }
        }
    }
}
