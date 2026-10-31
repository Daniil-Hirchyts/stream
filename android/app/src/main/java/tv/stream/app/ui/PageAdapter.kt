package tv.stream.app.ui

import android.content.Context
import android.graphics.Rect
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import tv.stream.app.R
import tv.stream.app.addon.Title

/** Publishes page blocks to stable Compose holders and retains route scroll/focus across navigation. */
class PageAdapter(private val context: Context, private val artwork: Artwork, private val listener: Listener) : RecyclerView.Adapter<PageAdapter.Holder>() {
    interface Listener {
        fun focused(key: String)
        /** The Home spotlight changed to this title (null when the page has no artwork). */
        fun featured(title: Title?)
        fun searchKeyboardChanged(visible: Boolean) {}
    }
    class Holder(view: View) : RecyclerView.ViewHolder(view)

    private var items: List<Item> = Collections.emptyList()
    private var snapshots: MutableList<List<Any?>> = Collections.emptyList()
    private var restore = ""
    private var gutterStart = 0
    private var gutterEnd = 0
    private val spotlightState = androidx.compose.runtime.mutableStateOf<TileBlocks.Featured?>(null)
    private var featured: Item.Tile? = null
    private var recycler: RecyclerView? = null
    private var generation = 0
    private var replacingFocus = false
    private val childScroll: MutableMap<String, android.os.Parcelable?> = java.util.HashMap()
    fun scrollPositions(): MutableMap<String, android.os.Parcelable?> = java.util.HashMap(childScroll)
    fun restoreScrollPositions(positions: Map<String, android.os.Parcelable?>) {
        childScroll.clear(); childScroll.putAll(positions)
    }
    /** Child scroll for declarative columns (Settings' split view), saved with the route like RecyclerView states. */
    private val scrollMemory = object : PageRows.ScrollMemory {
        override fun load(key: String): android.os.Parcelable? = childScroll[key]
        override fun save(key: String, state: android.os.Parcelable?) { childScroll[key] = state }
    }
    init {
        setHasStableIds(true); gutter(48, 48)
    }

    /** Page margins; a side panel already provides the left one. */
    fun gutter(start: Int, end: Int) { gutterStart = dp(start); gutterEnd = dp(end) }
    /** Shows `value`; true when only Search's results changed in place, so focus is already where it belongs. */
    fun set(value: List<Item>, restoreKey: String?): Boolean {
        // A provider answer replaces render values in the active composition. The keyboard and its focus survive.
        val search = searchState
        if (value.size == 1 && items.size == 1 && value[0].kind == Item.Kind.KEYBOARD && items[0].kind == Item.Kind.KEYBOARD
                && value[0].key == items[0].key && search != null) {
            restore = restoreKey ?: ""
            items = ArrayList(value); snapshots = Collections.singletonList(snapshot(value[0]))
            search.update(value[0])
            return true
        }
        if (value.size != 1 || value[0].kind != Item.Kind.KEYBOARD) searchState = null
        val previous = items
        val before = snapshots; val after: MutableList<List<Any?>> = ArrayList()
        for (item in value) after.add(snapshot(item))
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int { return previous.size }
            override fun getNewListSize(): Int { return value.size }
            override fun areItemsTheSame(old: Int, next: Int): Boolean {
                return previous.get(old).kind == value.get(next).kind && previous.get(old).key.equals(value.get(next).key)
            }
            override fun areContentsTheSame(old: Int, next: Int): Boolean { return before.get(old).equals(after.get(next)) }
        }, false)
        if (value.none { it.kind == Item.Kind.SPOTLIGHT }) { featured = null; spotlightState.value = null }
        items = ArrayList(value); snapshots = after
        restore = if (restoreKey == null) "" else restoreKey
        diff.dispatchUpdatesTo(this)
        // Restoration is a one-off: a shelf scrolled back into view later must not take focus again.
        val mine = ++generation
        if (recycler != null) recycler!!.postDelayed({ if (mine == generation) restore = "" }, 1500)
        return false
    }
    override fun onAttachedToRecyclerView(view: RecyclerView) { recycler = view }
    override fun onDetachedFromRecyclerView(view: RecyclerView) { recycler = null }
    /** A focus restore must wait for Compose updates queued while RecyclerView was laying out its holders. */
    fun hasPendingBindings(): Boolean {
        fun pending(view: View): Boolean {
            if (!view.isAttachedToWindow) return false
            if (view is ComposePageBlock && view.hasPendingBinding) return true
            if (view is ViewGroup) for (i in 0 until view.childCount) if (pending(view.getChildAt(i))) return true
            return false
        }
        return recycler?.let(::pending) == true
    }
    private fun focused(key: String) {
        if (replacingFocus) return
        restore = "" // Any deliberate control focus supersedes a queued restore.
        listener.focused(key)
        if ((key.startsWith("next-page:") || key.startsWith("previous-page:")) && recycler != null) {
            val page: RecyclerView = recycler!!
            page.post {
                val focused: View? = page.findFocus()
                if (!page.isAttachedToWindow || focused == null || !key.equals(focused.tag)) return@post
                val footer: View = page.layoutManager!!.findViewByPosition(position(key)) ?: return@post
                val below = page.layoutManager!!.getDecoratedBottom(footer) - (page.height - page.paddingBottom)
                if (below > 0) page.smoothScrollBy(0, below)
            }
        }
        searchState?.focused(key, arrowing)
    }
    /** Index of the block containing `key`, or -1. */
    fun position(key: String): Int {
        for (i in 0 until items.size) if (contains(items.get(i), key)) return i
        return -1
    }
    /** Lazy shelves may attach their previous card a frame later; a Left there must not enter the rail. */
    fun leadingEdge(key: String): Boolean {
        for (item in items) {
            if (item.kind == Item.Kind.SHELF || item.kind == Item.Kind.GRID) {
                val index = item.tiles.indexOfFirst { it.key == key }
                if (index >= 0) return index == 0
            }
            if (item.kind == Item.Kind.EPISODES) {
                val shelf = item.panes?.get(item.value)?.firstOrNull()
                val index = shelf?.tiles?.indexOfFirst { it.key == key } ?: -1
                if (index >= 0) return index == 0
            }
        }
        return true
    }
    /** Positions of the blocks with controls beyond `position`, nearest first, in `step`'s direction (-1 up, 1 down). */
    fun controlRows(position: Int, step: Int): List<Int> = controlRows(items, position, step)
    /** The first Home shelf belongs with the spotlight above it. */
    fun firstSpotlightShelf(key: String): Boolean {
        if (items.isEmpty() || items.get(0).kind != Item.Kind.SPOTLIGHT) return false
        for (i in 1 until items.size)
            if (items.get(i).kind == Item.Kind.SHELF) return contains(items.get(i), key)
        return false
    }
    override fun getItemCount(): Int { return items.size }
    override fun getItemViewType(position: Int): Int { return items.get(position).kind.ordinal }
    override fun getItemId(position: Int): Long {
        var hash = 0xcbf29ce484222325uL.toLong()
        for (c in items.get(position).key.toCharArray()) hash = (hash xor c.code.toLong()) * 0x100000001b3L
        return hash
    }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
        val box = FrameLayout(context)
        box.clipChildren = false; box.clipToPadding = false
        box.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return Holder(box)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val box = holder.itemView as FrameLayout
        val item = items.get(position)
        val composition = box.getChildAt(0) as? ComposePageBlock
        if (composition != null && composition.bind(item)) return
        val focused: View? = box.findFocus()
        val focusedKey: String? = if (focused != null && focused.tag is String) focused.tag as String else null
        val policy = if (recycler == null) 0 else recycler!!.descendantFocusability
        // A structural rebuild still restores atomically, before drawing or letting Android choose another row.
        if (focusedKey != null && recycler != null) {
            if (item.kind == Item.Kind.EPISODES && focusedKey.startsWith("episode:")) restore = focusedKey
            replacingFocus = true
            recycler!!.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; recycler!!.requestFocus()
        }
        try {
            // Removing a block is a structural replacement, not a cached holder leaving the viewport.
            (box.getChildAt(0) as? ComposePageBlock)?.disposeComposition()
            box.removeAllViews()
            val fillViewport = item.kind == Item.Kind.KEYBOARD || item.kind == Item.Kind.SPLIT || item.kind == Item.Kind.LOADING
            box.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    if (fillViewport) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
            val content = ComposePageBlock(context, item, this::declarative)
            // A block with a fixed height (the Home spotlight) keeps it; everything else wraps its content.
            val own: ViewGroup.LayoutParams? = content.layoutParams
            val height = if (fillViewport) ViewGroup.LayoutParams.MATCH_PARENT else if (own != null && own.height > 0) own.height else ViewGroup.LayoutParams.WRAP_CONTENT
            box.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        } finally {
            if (focusedKey != null && recycler != null) {
                recycler!!.descendantFocusability = policy; replacingFocus = false
                val target: View? = box.findViewWithTag(focusedKey)
                if (target != null) target.requestFocus()
            }
        }
    }

    /** Every page block owns its layout in Compose. The subtitle preview reuses the player's renderer. */
    private fun declarative(item: Item): @androidx.compose.runtime.Composable () -> Unit {
        val layout = TextBlocks.Layout(Tv.px(context, gutterStart), Tv.px(context, gutterEnd), ROW_INSET.dp)
        return when (item.kind) {
            Item.Kind.HEADER -> {
                val section = listFollows(item)
                val (title, detail) = item.title to item.detail
                ({ TextBlocks.Header(title, detail, section, if (section) 22 else 20, layout) })
            }
            Item.Kind.TEXT -> {
                val inList = inList(item)
                val (title, detail) = item.title to item.detail
                ({ TextBlocks.Paragraph(title, detail, inList, layout) })
            }
            Item.Kind.LIST, Item.Kind.SOURCE, Item.Kind.FIELD -> {
                val page = PageRows.Page(this::focused, restore)
                val start = layout.start; val end = layout.end
                ({ PageRows.Block(item, start, end, page) })
            }
            Item.Kind.SPLIT -> {
                val page = PageRows.Page(this::focused, restore)
                val start = layout.start; val end = layout.end
                ({ PageRows.Split(item, start, end, page, scrollMemory, this::subtitlePreview) })
            }
            Item.Kind.MESSAGE -> if (item.icon == 0 && item.actions.isEmpty()) {
                val (title, detail) = item.title to item.detail
                ({ TextBlocks.QuietMessage(title, detail, layout) })
            } else {
                val page = PageRows.Page(this::focused, restore)
                ({ PageControls.Message(item, page, layout.start, layout.end) })
            }
            Item.Kind.NOTICE -> {
                val page = PageRows.Page(this::focused, restore)
                ({ PageControls.Notice(item, page, layout.start, layout.end) })
            }
            Item.Kind.ACTIONS -> {
                val page = PageRows.Page(this::focused, restore)
                ({ PageControls.Actions(item.actions, page, layout.start, layout.end, item.centered) })
            }
            Item.Kind.CHIPS -> {
                val page = PageRows.Page(this::focused, restore)
                ({ PageControls.Chips(item, page, layout) })
            }
            Item.Kind.PAGINATION -> {
                val page = PageRows.Page(this::focused, restore)
                ({ PageControls.Pagination(item, page, layout.start, layout.end) })
            }
            Item.Kind.DETAILS -> ({ TitleBlocks.Details(item, layout.start, layout.end, artwork) })
            Item.Kind.SERIES_HEADER -> ({ TitleBlocks.SeriesHeader(item, layout.start, layout.end, artwork) })
            Item.Kind.INTRO -> ({ TitleBlocks.Intro(item, layout.start, layout.end) })
            Item.Kind.TITLE -> ({ TitleBlocks.PageTitle(item, layout.start, layout.end) })
            Item.Kind.CATALOGUE_HEADER -> ({ TitleBlocks.CatalogueHeader(item, layout.start, layout.end) })
            Item.Kind.SPOTLIGHT -> {
                val first = featured ?: items.firstOrNull { it.tiles.isNotEmpty() }?.tiles?.firstOrNull()
                if (first != null && spotlightState.value == null) feature(first)
                ({ TileBlocks.Spotlight(spotlightState.value, SPOTLIGHT_HEIGHT.dp, layout.start, layout.end, artwork, this::focused) })
            }
            Item.Kind.SHELF -> {
                val cards = TileBlocks.Cards(PageRows.Page(this::focused, restore), artwork, this::feature, scrollMemory)
                ({ TileBlocks.Shelf(item, cards, layout) })
            }
            Item.Kind.GRID -> {
                val cards = TileBlocks.Cards(PageRows.Page(this::focused, restore), artwork, this::feature, scrollMemory)
                ({ TileBlocks.GridRow(item, cards, layout.start, layout.end) })
            }
            Item.Kind.EPISODES -> {
                val cards = TileBlocks.Cards(PageRows.Page(this::focused, restore), artwork, this::feature, scrollMemory)
                ({ TileBlocks.Episodes(item, cards, layout, this::focused, { replacingFocus || restore.startsWith("episode:") }) { current ->
                    val index = position(current.key)
                    if (index >= 0) snapshots[index] = snapshot(current)
                } })
            }
            Item.Kind.KEYBOARD -> {
                val state = searchState ?: SearchBlocks.State(item, (scrollMemory.load("keyboard-page") as? android.os.Bundle)?.getInt("page") ?: 0, listener::searchKeyboardChanged).also {
                    searchState = it; listener.searchKeyboardChanged(it.visible)
                }
                val cards = TileBlocks.Cards(PageRows.Page(this::focused, restore), artwork, this::feature, scrollMemory)
                ({ SearchBlocks.Content(state, cards, layout.start, layout.end) })
            }
            Item.Kind.SKELETON -> ({ SkeletonBlocks.Block(item.value, layout.start, layout.end) })
            Item.Kind.LOADING -> ({ SkeletonBlocks.Loading() })
            Item.Kind.SUBTITLE_PREVIEW -> ({
                androidx.compose.ui.viewinterop.AndroidView(factory = { subtitlePreview(item) })
            })
        }
    }

    /** Whether a heading introduces list rows (a grey section header) rather than artwork (a shelf header). */
    private fun listFollows(header: Item): Boolean {
        val index = items.indexOf(header)
        if (index < 0 || index + 1 >= items.size) return false
        val next = items.get(index + 1).kind
        return next == Item.Kind.LIST || next == Item.Kind.SOURCE || next == Item.Kind.TEXT || next == Item.Kind.NOTICE || next == Item.Kind.ACTIONS
    }
    /** Text set among list rows starts where the rows' labels do; elsewhere it aligns with the page edge. */
    private fun inList(text: Item): Boolean {
        var i = items.indexOf(text) - 1
        while (i >= 0) {
            val before = items.get(i)
            if (before.kind == Item.Kind.TEXT) { i--; continue }
            return before.kind == Item.Kind.LIST || before.kind == Item.Kind.SOURCE || (before.kind == Item.Kind.HEADER && listFollows(before))
        }
        return false
    }
    private fun dp(value: Float): Int = Tv.dp(context, value)
    private fun dp(value: Int): Int = Tv.dp(context, value.toFloat())

    private fun feature(tile: Item.Tile) {
        if (tile.media == null || items.none { it.kind == Item.Kind.SPOTLIGHT }) return
        featured = tile
        spotlightState.value = TileBlocks.Featured(tile.media, tile.key, tile.action, tile.headline)
        listener.featured(tile.media)
    }
    private fun subtitlePreview(item: Item): View {
        val box = FrameLayout(context)
        box.clipChildren = false
        box.clipToPadding = false
        box.setPadding(gutterStart, dp(4), gutterEnd, dp(14))

        val preview = SubtitlePreviewView(context)
        val prefs = item.playerPreferences
        if (prefs != null) {
            preview.updateFrom(prefs)
        }
        preview.tag = item.key
        box.addView(preview, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92)))
        box.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return box
    }

    internal var searchState: SearchBlocks.State? = null
        private set
    /** True only while the activity dispatches a remote arrow; pointer focus keeps the keyboard visible. */
    private var arrowing = false
    fun arrowing(value: Boolean) { arrowing = value }
    fun searchKey(code: Int): Boolean = searchState?.key(code) == true
    fun persistentFocus(key: String): String = searchState?.persistent(key) ?: key
    fun toggleSearchKeyboard() { searchState?.toggle() }

    /** Scrolls so a focused child keeps some room around it, rather than stopping at the very edge. */
    class Layout(context: Context, orientation: Int, marginDp: Int) : LinearLayoutManager(context, orientation, false) {
        private val margin: Int = Tv.dp(context, marginDp.toFloat())
        private var owner: RecyclerView? = null
        override fun onAttachedToWindow(view: RecyclerView) { super.onAttachedToWindow(view); owner = view }
        override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
            super.onDetachedFromWindow(view, recycler); owner = null
        }
        /** Each block's height as last measured, so the distance to the top is known after the top has scrolled away. */
        private val heights = android.util.SparseIntArray()
        override fun measureChildWithMargins(child: View, widthUsed: Int, heightUsed: Int) {
            super.measureChildWithMargins(child, widthUsed, heightUsed)
            val params = child.layoutParams as RecyclerView.LayoutParams
            heights.put(getPosition(child), getDecoratedMeasuredHeight(child) + params.topMargin + params.bottomMargin)
        }
        override fun onItemsChanged(view: RecyclerView) { heights.clear() }
        override fun onItemsAdded(view: RecyclerView, start: Int, count: Int) { heights.clear() }
        override fun onItemsRemoved(view: RecyclerView, start: Int, count: Int) { heights.clear() }
        override fun onItemsUpdated(view: RecyclerView, start: Int, count: Int) { heights.clear() }
        override fun onItemsMoved(view: RecyclerView, from: Int, to: Int, count: Int) { heights.clear() }
        /** How far the page is scrolled from its top, or -1 when a block above the screen was never measured. */
        private fun scrolled(): Int {
            var top: View? = null; var topPosition = Int.MAX_VALUE
            for (i in 0 until childCount) { val c = getChildAt(i)!!; val p = getPosition(c); if (p < topPosition) { topPosition = p; top = c } }
            if (top == null) return -1
            var offset = paddingTop - getDecoratedTop(top)
            for (p in 0 until topPosition) { val h = heights.get(p, -1); if (h < 0) return -1; offset += h }
            return offset
        }
        override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
            // A block that fills the page (Search's keyboard and results, Settings) scrolls inside itself, never the page.
            if (orientation == LinearLayoutManager.VERTICAL && child.height >= height - paddingTop - paddingBottom) return false
            // Pointer/remote focus must not move a control which is already fully visible. In particular,
            // applying the extra focus margin at the pagination footer caused a jump on press/hover.
            if (orientation == LinearLayoutManager.VERTICAL && child.top + rect.top >= paddingTop &&
                    child.top + rect.bottom <= height) return false
            // While the focused view fits in the first screenful, return the page to its very top (the Home spotlight
            // shows again), even when the top blocks have scrolled away and been recycled.
            val offset = if (orientation == LinearLayoutManager.VERTICAL) scrolled() else -1
            if (offset >= 0) {
                if (child.top + rect.bottom + offset <= height) {
                    if (offset == 0) return false
                    if (immediate) parent.scrollBy(0, -offset) else parent.smoothScrollBy(0, -offset)
                    return true
                }
            }
            // Focus zoom is allowed into the shelf's unclipped gutters. Use the stable item bounds, not the
            // animated artwork rectangle, and keep an already visible card still for both remote and pointer input.
            if (orientation == LinearLayoutManager.HORIZONTAL && getDecoratedLeft(child) >= paddingLeft &&
                    getDecoratedRight(child) <= width - paddingRight) return false
            val grown = Rect(rect)
            if (orientation == LinearLayoutManager.VERTICAL) { grown.top -= margin; grown.bottom += margin } else { grown.left -= margin; grown.right += margin }
            return super.requestChildRectangleOnScreen(parent, child, grown, immediate, focusedChildVisible)
        }
        /**
         * Shelves keep their ends stable. The activity sends Left from a true content edge to the rail;
         * Right and Up never jump sideways into an unrelated navigation action.
         */
        override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
            if ((direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) && orientation == LinearLayoutManager.VERTICAL) {
                val step = if (direction == View.FOCUS_UP) -1 else 1
                val row = adjacentRow(focused, step)
                if (row != null) return row
                val next: View? = FocusFinder.getInstance().findNextFocus(focused.rootView as ViewGroup, focused, direction)
                // Up from the first row of controls goes to the top corner even when nothing lies geometrically above.
                if (direction == View.FOCUS_UP && next == null && findContainingItemView(focused) != null)
                    return topCorner(focused).takeIf { it !== focused }
                if (next != null && findContainingItemView(next) == null) {
                    if (direction != View.FOCUS_UP) return focused
                    return topCorner(focused)
                }
                return null
            }
            if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return null
            val item: View = findContainingItemView(focused) ?: return null
            if (orientation == LinearLayoutManager.HORIZONTAL) {
                val forward = (direction == View.FOCUS_RIGHT) != (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                val position = getPosition(item)
                return if ((forward && position == itemCount - 1) || (!forward && position == 0)) focused else null
            }
            val next: View? = FocusFinder.getInstance().findNextFocus(focused.rootView as ViewGroup, focused, direction)
            return if (next != null && findContainingItemView(next) == null) focused else null
        }

        private fun topCorner(focused: View): View {
            val back: View? = focused.rootView.findViewById(R.id.page_back)
            if (back != null && back.isShown) return back
            val refresh: View? = focused.rootView.findViewById(R.id.home_refresh)
            return if (refresh != null && refresh.isShown) refresh else focused
        }

        /**
         * Up and Down move exactly one row of controls. The neighbouring block is found by adapter position, not by
         * geometry: a row that has scrolled away (or is still arriving during a smooth scroll) is laid out first, so
         * a farther control on screen, such as the top corner's Back or Refresh, never wins over it. Null when the
         * focused view is not in a block or no block beyond it has controls.
         */
        private fun adjacentRow(focused: View, step: Int): View? {
            val page = owner ?: return null
            val adapter = page.adapter as? PageAdapter ?: return null
            val item = findContainingItemView(focused) ?: return null
            val position = getPosition(item)
            if (position == RecyclerView.NO_POSITION) return null
            // Only stops the row holds itself (a native control beside a composition) stay with the row's own search.
            val direction = if (step < 0) View.FOCUS_UP else View.FOCUS_DOWN
            for (target in adapter.controlRows(position, step).take(3)) {
                val block = reveal(page, target, step) ?: continue
                val into = landing(page, focused, block, direction)
                if (into != null) return into
                // A row just scrolled into layout composes its controls on the next frame: enter it then rather than
                // skipping it for the row beyond.
                if (composing(block)) {
                    page.postOnAnimation {
                        if (!focused.isFocused || !block.isAttachedToWindow) return@postOnAnimation
                        landing(page, focused, block, direction)?.requestFocus()
                    }
                    return focused
                }
            }
            return null
        }

        private fun composing(view: View): Boolean {
            if (view is ComposePageBlock && view.hasPendingBinding) return true
            if (view is ViewGroup) for (i in 0 until view.childCount) if (composing(view.getChildAt(i))) return true
            return false
        }

        /** The laid-out view of block `target`, scrolling just far enough to lay it out when it is not. */
        private fun reveal(page: RecyclerView, target: Int, step: Int): View? {
            var view = findViewByPosition(target)
            var tries = 0
            while (view == null && tries++ < 8 && !page.isComputingLayout && page.canScrollVertically(step)) {
                page.scrollBy(0, step * Math.max(1, height / 3))
                view = findViewByPosition(target)
            }
            return view
        }

        /** The control in `block` that a move from `focused` lands on: the nearest in the direction of travel. */
        private fun landing(page: RecyclerView, focused: View, block: View, direction: Int): View? {
            if (block !is ViewGroup) return if (block.isFocusable) block else null
            val rect = Rect()
            focused.getFocusedRect(rect)
            page.offsetDescendantRectToMyCoords(focused, rect)
            rect.offset(-block.left, -block.top)
            // Enter from the block's far edge, keeping the column: the source lies beyond it in the travel direction.
            val height = rect.height()
            if (direction == View.FOCUS_UP) { rect.top = block.height; rect.bottom = rect.top + height }
            else { rect.bottom = 0; rect.top = -height }
            return FocusFinder.getInstance().findNextFocusFromRect(block, rect, direction) ?: PageFocus.first(block)?.let { first ->
                ArrayList<View>().also { first.addFocusables(it, direction) }.firstOrNull()
            }
        }

    }

    companion object {
        /** The Home spotlight keeps a stable artwork area above the shelves. */
        const val SPOTLIGHT_HEIGHT = 248
        private const val ROW_INSET = PageRows.ROW_INSET

        /** Capture values now: preferences and page blocks can be mutated before the next render. */
        private fun snapshot(item: Item): List<Any?> {
            val state: MutableList<Any?> = ArrayList(java.util.Arrays.asList<Any?>(item.kind, item.key, item.title, item.detail, item.value,
                    item.meta, item.icon, item.statusIcon, item.muted, item.centered, item.style, item.loading,
                    if (item.media == null) null else item.media!!.cacheJson(), item.keyboardLanguages, item.keyboardLanguage))
            for (a in item.actions) state.add(java.util.Arrays.asList<Any?>(a.key, a.label, a.text, a.detail, a.icon, a.style, a.selected))
            for (t in item.tiles) state.add(java.util.Arrays.asList<Any?>(t.key, t.title, t.subtitle, t.image, t.style, t.episodeNumber, t.synopsis, t.releaseDate,
                    t.progress, t.badge, t.flag, t.headline, t.description, t.block, t.colorIndex, if (t.media == null) null else t.media!!.cacheJson()))
            val children = item.children
            if (children != null) for (child in children) state.add(snapshot(child))
            val panes = item.panes
            if (panes is LazyPanes) state.add(panes.signature)
            else if (panes != null) for (pane in panes.entries) {
                state.add(pane.key); for (row in pane.value) state.add(snapshot(row))
            }
            val prefs = item.playerPreferences
            if (prefs != null) state.add(java.util.HashMap(prefs.all))
            return state
        }
        /** Whether a block draws controls the remote can stop on. Headings, text, artwork and skeletons do not. */
        internal fun interactive(item: Item): Boolean = when (item.kind) {
            Item.Kind.KEYBOARD, Item.Kind.SPLIT, Item.Kind.EPISODES -> true
            Item.Kind.LIST, Item.Kind.SOURCE, Item.Kind.FIELD -> item.action != null
            Item.Kind.SHELF, Item.Kind.GRID -> item.tiles.any { it.action != null }
            Item.Kind.SKELETON, Item.Kind.LOADING, Item.Kind.SPOTLIGHT, Item.Kind.TEXT, Item.Kind.HEADER -> false
            else -> item.actions.isNotEmpty()
        }
        /** Positions of the interactive blocks beyond `position`, nearest first, in `step`'s direction (-1 up, 1 down). */
        internal fun controlRows(items: List<Item>, position: Int, step: Int): List<Int> {
            val rows = ArrayList<Int>()
            var i = position + step
            while (i >= 0 && i < items.size) { if (interactive(items[i])) rows.add(i); i += step }
            return rows
        }
        private fun contains(item: Item, key: String): Boolean {
            if (item.key.equals(key)) return true
            // Settings' categories are controls of its split view too, so restoring one finds its block.
            if (item.kind == Item.Kind.SPLIT && item.children?.any { it.key == key } == true) return true
            if (item.kind == Item.Kind.KEYBOARD && key.startsWith(item.key + ":")) return true
            for (t in item.tiles) if (t.key.equals(key)) return true
            for (a in item.actions) if (a.key.equals(key)) return true
            val panes = item.panes
            if (panes is LazyPanes) return panes.rowKeys.contains(key)
            if (panes != null) for (pane in panes.values)
                for (child in pane) if (contains(child, key)) return true
            return false
        }

        fun kind(title: Title): String {
            when (title.type) {
                "movie" -> return "Film"
                "series" -> return "Series"
                "channel" -> return "Channel"
                "tv" -> return "Live TV"
                else -> return if (title.type.isEmpty()) "" else Character.toUpperCase(title.type[0]).toString() + title.type.substring(1)
            }
        }
        fun metaLine(title: Title): String {
            return metaLine(title, true)
        }
        fun metaLine(title: Title, year: Boolean): String {
            val parts: MutableList<String> = ArrayList()
            if (year && !title.releaseInfo.isEmpty()) parts.add(title.releaseInfo)
            if (!title.genres.isEmpty()) parts.add(title.genres.subList(0, Math.min(3, title.genres.size)).joinToString(", "))
            if (!title.rating.isEmpty()) parts.add("★ " + title.rating)
            return parts.joinToString("  ·  ")
        }
    }
}
