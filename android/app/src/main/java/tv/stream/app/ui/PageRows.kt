package tv.stream.app.ui

import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent as PlatformKeyEvent
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.android.awaitFrame
import tv.stream.app.R
import tv.stream.app.ui.PageControls.lifted

/**
 * Settings and add-on management drawn by Compose: list, source and field rows, and the Settings split view. They
 * match the View rows they replace: a platter that is clear until focused and then white with dark content, the same
 * Geist faces and sizes, Settings' growth by a fixed few dp, accessibility labels, sounds, and click by remote, pointer
 * and touch. Each control is keyed in [PageFocus] so pages restore and report focus by key as before.
 */
internal object PageRows {
    const val ROW_INSET = 16
    /** Settings' columns grow their focused row by this much in total, whatever its width. */
    const val ROW_GROWTH = 12
    /** How a row's platter grows on focus: not at all on pages, a fixed few dp in Settings' columns. */
    enum class Growth { NONE, SETTINGS }

    /** Page callbacks a row reports to: focus (with the key) and the one-off restore target. */
    class Page(val focused: (String) -> Unit, val restore: String)

    /** One row block: a LIST, SOURCE or FIELD item, between the page's gutters. */
    @Composable
    fun Block(item: Item, start: Dp, end: Dp, page: Page, growth: Growth = Growth.NONE, selected: Boolean = false,
              modifier: Modifier = Modifier, onKey: ((Int) -> Boolean)? = null) {
        Box(modifier.fillMaxWidth().padding(start = start, top = 2.dp, end = end, bottom = 2.dp)) {
            Platter(item, page, growth, selected, onKey) { state -> Content(item, state) }
        }
    }

    /** What a row's content draws with: platter state and the resulting colours. */
    class State(val focused: Boolean, val pressed: Boolean, val hovered: Boolean) {
        val onFocus: Boolean get() = focused || pressed
        @ColorRes fun primary(): Int = if (onFocus) R.color.tv_on_focus else R.color.tv_text
        @ColorRes fun secondary(): Int = when {
            onFocus -> R.color.tv_on_focus_secondary
            hovered -> R.color.tv_text
            else -> R.color.tv_text_secondary
        }
    }

    @Composable
    private fun Platter(item: Item, page: Page, growth: Growth, selected: Boolean, onKey: ((Int) -> Boolean)?,
                        content: @Composable (State) -> Unit) {
        val radius = when (item.kind) {
            Item.Kind.SOURCE -> 14.dp
            Item.Kind.FIELD -> dimensionResource(R.dimen.tv_radius_control)
            else -> dimensionResource(R.dimen.tv_radius_capsule)
        }
        val control = PageControls.control(item.key, label(item), item.action, page, radius, onKey = onKey)
        val focused = control.focused
        val pressed = control.pressed
        var width by remember { mutableStateOf(0) }
        val density = LocalDensity.current
        val lift = if (growth == Growth.SETTINGS && width > 0) 1f + with(density) { ROW_GROWTH.dp.toPx() } / width else Tv.LIFT_ROW
        val scale by animateFloatAsState(if (focused) lift else 1f, tween(if (focused) 200 else 160, easing = PageControls.Decelerate), label = "lift")
        // The shadow leaves with the white platter: settling under a clear row it would show as a dark pill.
        val raise = PageControls.raised(focused, 10.dp)
        val shape = RoundedCornerShape(radius)
        val fill = when {
            focused -> colorResource(R.color.tv_focus)
            pressed -> colorResource(R.color.tv_focus_pressed)
            selected -> colorResource(if (item.kind == Item.Kind.FIELD) R.color.tv_platter_selected else R.color.tv_platter_low)
            item.kind == Item.Kind.FIELD -> colorResource(R.color.tv_platter)
            else -> Color.Transparent
        }
        val row = Modifier
            .onSizeChanged { width = it.width }
            .lifted(scale, raise, shape, false)
            // Inside the layer, so popups and sounds see the grown platter as native controls' visible rects do.
            .then(control.placed)
            .background(fill, shape)
            .then(control.input)
        Box(row) { content(State(focused, pressed, control.hovered)) }
    }

    fun label(item: Item): String = when (item.kind) {
        Item.Kind.SOURCE -> item.title + ". " + item.meta + ". " + item.value
        Item.Kind.FIELD -> item.title + ". " + item.value
        else -> if (item.value.isEmpty()) item.title else item.title + ". " + item.value
    }

    @Composable
    private fun Content(item: Item, state: State) {
        when (item.kind) {
            Item.Kind.SOURCE -> Source(item, state)
            Item.Kind.FIELD -> Field(item, state)
            else -> ListRow(item, state)
        }
    }

    @Composable
    private fun Icon(@DrawableRes icon: Int, sizeDp: Int, @ColorRes tint: Int, modifier: Modifier = Modifier) {
        Image(painterResource(icon), contentDescription = null, modifier = modifier.size(sizeDp.dp),
                colorFilter = ColorFilter.tint(colorResource(tint)))
    }

    @Composable
    private fun ListRow(item: Item, state: State) {
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = ROW_INSET.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
            if (item.icon != 0) { Icon(item.icon, 18, state.primary()); Spacer(Modifier.width(12.dp)) }
            BasicText(item.title, Modifier.weight(1f), style = TextBlocks.style(Tv.Font.MEDIUM, 15.5f, state.primary(), false),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.value.isNotEmpty()) {
                BasicText(item.value, Modifier.widthIn(max = 280.dp).padding(start = 12.dp, end = 4.dp),
                        style = TextBlocks.style(Tv.Font.REGULAR, Tv.Type.VALUE.size, state.secondary(), false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.action != null) Icon(R.drawable.ic_tv_chevron, 16, state.secondary())
        }
    }

    @Composable
    private fun Source(item: Item, state: State) {
        Row(Modifier.fillMaxWidth().padding(start = ROW_INSET.dp, top = 10.dp, end = 14.dp, bottom = 10.dp)
                .alpha(if (item.muted) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(item.title, style = TextBlocks.style(Tv.Font.MEDIUM, 15.5f, state.primary(), false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(item.meta, Modifier.padding(top = 5.dp), style = TextBlocks.style(Tv.Font.REGULAR, 13f, state.secondary(), false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = if (item.statusIcon != 0) item.statusIcon else if (item.muted) R.drawable.ic_tv_info else R.drawable.ic_tv_check
                if (icon > 0) Icon(icon, 18, if (item.muted) state.secondary() else state.primary())
                BasicText(item.value, Modifier.widthIn(max = 200.dp).padding(start = if (icon > 0) 7.dp else 0.dp),
                        style = TextBlocks.style(Tv.Font.REGULAR, 13f, state.secondary(), false, spacing = 1.18f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun Field(item: Item, state: State) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_tv_search, 18, state.primary()); Spacer(Modifier.width(12.dp))
            BasicText(item.value, style = TextBlocks.style(Tv.Font.MEDIUM, 15f, state.secondary(), false),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    /** Where Settings keeps each column's scroll between visits and recreation (the page's child scroll state). */
    interface ScrollMemory {
        fun load(key: String): Parcelable?
        fun save(key: String, state: Parcelable?)
    }

    @Composable
    private fun rememberList(memory: ScrollMemory, key: String): LazyListState {
        val state = remember(key) {
            val saved = memory.load(key) as? Bundle
            LazyListState(saved?.getInt("index") ?: 0, saved?.getInt("offset") ?: 0)
        }
        LaunchedEffect(state, key) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { (index, offset) ->
                memory.save(key, Bundle().apply { putInt("index", index); putInt("offset", offset) })
            }
        }
        return state
    }

    /**
     * Categories on the left, the focused one's rows on the right (Settings). Focusing a category shows its rows;
     * OK or a tap moves into them. Right from a category enters the pane at its first row; Left from the pane returns
     * to the lit category. `native` draws the subtitle preview, which uses the player's own subtitle renderer.
     */
    @Composable
    fun Split(item: Item, start: Dp, end: Dp, page: Page, memory: ScrollMemory, native: (Item) -> View) {
        val categories = item.children!!
        val panes = item.panes!!
        val latest by rememberUpdatedState(item)
        val restoreTarget = remember { page.restore }
        val initial = remember {
            var chosen = item.value
            for (pane in panes.entries) for (row in pane.value) if (row.key == restoreTarget) chosen = pane.key
            if (panes.containsKey(restoreTarget)) chosen = restoreTarget
            if (!panes.containsKey(chosen)) chosen = categories[0].key
            chosen
        }
        var selected by remember { mutableStateOf(initial) }
        if (!panes.containsKey(selected)) selected = panes.keys.first()
        var restoring by remember { mutableStateOf(restoreTarget.isNotEmpty()) }
        val registry = PageFocus.Local.current
        val categoryList = rememberList(memory, item.key + ":categories")
        val paneList = rememberList(memory, item.key + ":pane:" + selected)
        val rows = panes[selected].orEmpty()
        var entering by remember { mutableStateOf(0) }

        fun firstPaneRow(): String? {
            val visible = paneList.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
            return rows.withIndex().firstOrNull { (i, row) -> i in visible && row.action != null && registry?.owns(row.key) == true }?.value?.key
        }
        fun enterPane(): Boolean { val first = firstPaneRow() ?: return false; return registry?.request(first) == true }
        LaunchedEffect(entering) { if (entering > 0) { awaitFrame(); enterPane() } }
        LaunchedEffect(Unit) {
            if (!restoring) return@LaunchedEffect
            awaitFrame(); awaitFrame()
            registry?.request(restoreTarget)
            restoring = false
        }

        val categoryPage = Page({ key ->
            // Replacing a changed settings pane can give the first category transient focus. Restore the actual row
            // before accepting category changes, or the pane jumps back to Add-ons.
            if (restoring && key != restoreTarget) return@Page
            page.focused(key)
            if (!panes.containsKey(key) || key == selected) return@Page
            selected = key
            latest.keyboardInput?.accept(key)
        }, if (panes.containsKey(restoreTarget)) restoreTarget else "")
        val panePage = Page(page.focused, restoreTarget)
        val toPane: (Int) -> Boolean = { code -> code == PlatformKeyEvent.KEYCODE_DPAD_RIGHT && enterPane() }
        val toCategory: (Int) -> Boolean = { code -> code == PlatformKeyEvent.KEYCODE_DPAD_LEFT && registry?.request(selected) == true }

        Row(Modifier.fillMaxSize().padding(start = start, top = 4.dp, end = end)) {
            LazyColumn(Modifier.width(240.dp).fillMaxHeight(), state = categoryList) {
                itemsIndexed(categories, key = { _, c -> c.key }) { _, category ->
                    // OK or a tap enters the pane, once it shows this category's rows.
                    val entry = Item.row(category.key, category.icon, category.title, category.value, Runnable { entering++ })
                    Block(entry, 0.dp, 0.dp, categoryPage, Growth.SETTINGS, selected = category.key == selected, onKey = toPane)
                }
            }
            // The pane scrolls inside its own clip, bleeding 12 dp each side so grown rows aren't cut; the page stays still.
            Box(Modifier.weight(1f).fillMaxHeight().layout { measurable, constraints ->
                val bleed = 12.dp.roundToPx()
                val wide = constraints.copy(minWidth = constraints.minWidth + 2 * bleed, maxWidth = constraints.maxWidth + 2 * bleed,
                        maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 40.dp.roundToPx() else constraints.maxHeight)
                val placeable = measurable.measure(wide)
                layout(constraints.maxWidth, constraints.maxHeight.coerceAtMost(placeable.height)) { placeable.place(-bleed, 0) }
            }.clipToBounds()) {
                LazyColumn(Modifier.fillMaxSize(), state = paneList, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp)) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        Pane(row, rows.getOrNull(index + 1), rows.subList(0, index), panePage, toCategory, native)
                    }
                }
            }
        }
    }

    /** One row of a Settings pane, laid out as the pane's own page with a 28 dp leading gutter. */
    @Composable
    private fun Pane(row: Item, next: Item?, before: List<Item>, page: Page, onKey: (Int) -> Boolean, native: (Item) -> View) {
        val layout = TextBlocks.Layout(28.dp, 0.dp, ROW_INSET.dp)
        when (row.kind) {
            Item.Kind.LIST, Item.Kind.SOURCE, Item.Kind.FIELD -> Block(row, 28.dp, 0.dp, page, Growth.SETTINGS, onKey = onKey)
            Item.Kind.HEADER -> {
                val section = listFollows(next)
                TextBlocks.Header(row.title, row.detail, section, if (section) 22 else 20, layout)
            }
            Item.Kind.TEXT -> TextBlocks.Paragraph(row.title, row.detail, inList(before), layout)
            Item.Kind.MESSAGE -> TextBlocks.QuietMessage(row.title, row.detail, layout)
            else -> AndroidView(factory = { native(row) }, modifier = Modifier.fillMaxWidth())
        }
    }

    /** Whether a heading introduces list rows (a grey section header) rather than artwork. */
    fun listFollows(next: Item?): Boolean = next != null && (next.kind == Item.Kind.LIST || next.kind == Item.Kind.SOURCE
            || next.kind == Item.Kind.TEXT || next.kind == Item.Kind.NOTICE || next.kind == Item.Kind.ACTIONS)

    /** Text set among list rows starts where the rows' labels do. */
    fun inList(before: List<Item>): Boolean {
        for (i in before.indices.reversed()) {
            val item = before[i]
            if (item.kind == Item.Kind.TEXT) continue
            return item.kind == Item.Kind.LIST || item.kind == Item.Kind.SOURCE
                    || (item.kind == Item.Kind.HEADER && (i + 1 >= before.size || listFollows(before[i + 1])))
        }
        return false
    }
}
