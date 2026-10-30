package tv.stream.app.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import tv.stream.app.R
import tv.stream.app.addon.Title

/** Builds a [Page]'s blocks. Every focusable view is tagged with its key so focus can be restored. */
class PageAdapter(private val context: Context, private val artwork: Artwork, private val listener: Listener) : RecyclerView.Adapter<PageAdapter.Holder>() {
    private fun interface Binding { fun update(item: Item): Boolean }
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
    private var spotlight: Spotlight? = null
    private var featured: Item.Tile? = null
    private var recycler: RecyclerView? = null
    private var generation = 0
    private var replacingFocus = false
    private var shownResults = 0
    private val childScroll: MutableMap<String, android.os.Parcelable?> = java.util.HashMap()
    private val scrollKeys: MutableMap<RecyclerView, String> = java.util.WeakHashMap()
    fun scrollPositions(): MutableMap<String, android.os.Parcelable?> {
        if (searchPane != null && searchPane!!.isAttachedToWindow) searchPane!!.complete()
        for (entry in scrollKeys.entries)
            if (entry.key.isAttachedToWindow) childScroll.put(entry.value, entry.key.layoutManager!!.onSaveInstanceState())
        return java.util.HashMap(childScroll)
    }
    fun restoreScrollPositions(positions: Map<String, android.os.Parcelable?>) {
        scrollKeys.clear(); childScroll.clear(); childScroll.putAll(positions)
    }
    private fun retainScroll(list: RecyclerView, key: String) {
        val previous = scrollKeys.put(list, key)
        if (previous != null) childScroll.put(previous, list.layoutManager!!.onSaveInstanceState())
        val state = childScroll.get(key)
        if (state != null) list.layoutManager!!.onRestoreInstanceState(state)
        else if (previous != null) list.scrollToPosition(0)
        if (previous != null) return
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                val active = scrollKeys.get(view)
                if (active != null) childScroll.put(active, view.layoutManager!!.onSaveInstanceState())
            }
        })
    }
    /** Child scroll for declarative columns (Settings' split view), saved with the route like RecyclerView states. */
    private val scrollMemory = object : PageRows.ScrollMemory {
        override fun load(key: String): android.os.Parcelable? = childScroll[key]
        override fun save(key: String, state: android.os.Parcelable?) { childScroll[key] = state }
    }
    /** The live Search results column, if Search is showing. */
    private var searchResults: LinearLayout? = null
    private var keyboardQuery: StringBuilder? = null

    init {
        setHasStableIds(true); gutter(48, 48)
    }

    /** Page margins; a side panel already provides the left one. */
    fun gutter(start: Int, end: Int) { gutterStart = dp(start); gutterEnd = dp(end) }
    /** Shows `value`; true when only Search's results changed in place, so focus is already where it belongs. */
    fun set(value: List<Item>, restoreKey: String?): Boolean {
        // A new search answer replaces only the results: rebinding would rebuild the keyboard and drop its focus.
        if (value.size == 1 && items.size == 1 && value.get(0).kind == Item.Kind.KEYBOARD && items.get(0).kind == Item.Kind.KEYBOARD
                && value.get(0).key.equals(items.get(0).key) && value.get(0).keyboardLanguages.equals(items.get(0).keyboardLanguages)
                && searchResults != null && searchResults!!.isAttachedToWindow) {
            val shown = items.get(0); val next = value.get(0)
            val unchanged = snapshots.get(0).equals(snapshot(next))
            shown.tiles = next.tiles; shown.loading = next.loading; shown.detail = next.detail; shown.meta = next.meta; shown.actions = next.actions
            shown.value = next.value
            if (searchPane != null) searchPane!!.query(next.value)
            if (keyboardQuery != null && !keyboardQuery.toString().equals(next.value)) { keyboardQuery!!.setLength(0); keyboardQuery!!.append(next.value) }
            restore = if (restoreKey == null) "" else restoreKey
            if (!unchanged) results(searchResults!!, shown)
            restoreSearchSelection()
            snapshots = Collections.singletonList(snapshot(shown))
            // A results card may have been replaced. Let the page restore it after the nested grid lays out;
            // typing on the unchanged keyboard can keep its current focus immediately.
            val focused: View? = searchResults!!.findFocus()
            if (unchanged && focused != null && java.util.Objects.equals(restoreKey, focused.tag)) return true
            val pageFocus: View? = if (searchPane == null) null else searchPane!!.findFocus()
            return restoreKey == null || restoreKey.isEmpty() || restoreKey.startsWith(shown.key + ":") &&
                    pageFocus != null && restoreKey.equals(pageFocus.tag)
        }
        if (value.size != 1 || value.get(0).kind != Item.Kind.KEYBOARD) { searchPane = null; searchResults = null; keyboardQuery = null }
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
        if (key.equals(restore)) restore = ""
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
        val pane = searchPane
        if (pane != null && pane.isAttachedToWindow) {
            if (key.startsWith("keyboard:")) pane.lastKeyboard = key
            else if (pane.item.tiles.stream().anyMatch { tile -> tile.key.equals(key) }) {
                pane.lastResult = key
                // Arrowing into results gives them the page. A tap, or focus restored on return, keeps the keyboard:
                // a pointer has no other way to bring it back.
                if (!pane.changing && arrowing) pane.keyboard(false, true, false)
            }
        }
    }
    /** Index of the block containing `key`, or -1. */
    fun position(key: String): Int {
        for (i in 0 until items.size) if (contains(items.get(i), key)) return i
        return -1
    }
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
        if (box.childCount > 0 && box.getChildAt(0).getTag(R.id.page_binding) is Binding
                && (box.getChildAt(0).getTag(R.id.page_binding) as Binding).update(item)) return
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
            val drawn = composeBlock(item.kind) && declarative(item) != null
            val native = if (drawn) null else build(item)
            val content = if (composeBlock(item.kind)) ComposePageBlock(context, item, native, this::build, this::declarative) { view, next ->
                (view.getTag(R.id.page_binding) as? Binding)?.update(next) == true
            } else native!!
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

    /** Settings/add-on controls first, followed by Details and browse blocks that share the same controls. */
    private fun composeBlock(kind: Item.Kind): Boolean = when (kind) {
        Item.Kind.SPLIT, Item.Kind.LIST, Item.Kind.SOURCE, Item.Kind.FIELD, Item.Kind.SUBTITLE_PREVIEW,
        Item.Kind.DETAILS, Item.Kind.SERIES_HEADER, Item.Kind.INTRO, Item.Kind.TITLE,
        Item.Kind.CATALOGUE_HEADER, Item.Kind.HEADER, Item.Kind.PAGINATION,
        Item.Kind.ACTIONS, Item.Kind.CHIPS, Item.Kind.MESSAGE, Item.Kind.NOTICE, Item.Kind.TEXT -> true
        // Artwork shelves, the on-screen keyboard and player controls retain their existing custom parents.
        else -> false
    }

    /**
     * The Compose implementation of a block, where one exists: text-only blocks. Blocks that need a platform drawable
     * (the glass notice's blur, the animated loading indicator) or that take focus still render natively.
     */
    private fun declarative(item: Item): (@androidx.compose.runtime.Composable () -> Unit)? {
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
                ({ PageRows.Split(item, start, end, page, scrollMemory, this::build) })
            }
            Item.Kind.MESSAGE -> if (item.icon == 0 && item.actions.isEmpty()) {
                val (title, detail) = item.title to item.detail
                ({ TextBlocks.QuietMessage(title, detail, layout) })
            } else null
            else -> null
        }
    }

    private fun restoreLater(view: View, key: String) {
        view.post { if (view.isAttachedToWindow && key.equals(restore)) view.requestFocus() }
    }

    private fun build(item: Item): View {
        when (item.kind) {
            Item.Kind.SPOTLIGHT -> return spotlight(item)
            Item.Kind.DETAILS -> return details(item)
            Item.Kind.SERIES_HEADER -> return seriesHeader(item)
            Item.Kind.INTRO -> return intro(item)
            Item.Kind.TITLE -> return pageTitle(item)
            Item.Kind.CATALOGUE_HEADER -> return catalogueHeader(item)
            Item.Kind.PAGINATION -> return pagination(item)
            Item.Kind.HEADER -> return if (listFollows(item)) sectionHeader(item.title, item.detail) else header(item.title, item.detail, 20)
            Item.Kind.SHELF -> return shelf(item)
            Item.Kind.GRID -> return grid(item)
            Item.Kind.ACTIONS -> return actions(item, if (item.centered) Gravity.CENTER_HORIZONTAL else Gravity.START)
            Item.Kind.CHIPS -> return chips(item)
            Item.Kind.EPISODES -> return episodes(item)
            Item.Kind.KEYBOARD -> return keyboard(item)
            Item.Kind.SKELETON -> return skeleton(item)
            Item.Kind.MESSAGE -> return message(item)
            Item.Kind.NOTICE -> return notice(item)
            Item.Kind.TEXT -> return text(item)
            Item.Kind.SUBTITLE_PREVIEW -> return subtitlePreview(item)
            Item.Kind.LOADING -> return loading()
            else -> return loading()
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
    private fun column(): LinearLayout {
        val column = LinearLayout(context); column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false; column.clipToPadding = false
        column.setPadding(gutterStart, 0, gutterEnd, 0)
        return column
    }
    private fun dp(value: Float): Int { return Tv.dp(context, value) }
    private fun dp(value: Int): Int { return Tv.dp(context, value.toFloat()) }
    private fun gone(view: View, gone: Boolean) { view.visibility = if (gone) View.GONE else View.VISIBLE }

    // Spotlight: the focused Home card's title, artwork and description. Clicking opens that card.
    private inner class Spotlight {
        val root: LinearLayout = column()
        val logo = ImageView(context)
        val title: TextView = Tv.onArtwork(Tv.oneLine(Tv.type(context, "", Tv.Type.HERO, R.color.tv_text)))
        val meta: TextView = Tv.onArtwork(Tv.oneLine(Tv.type(context, "", Tv.Type.META, R.color.tv_text_on_artwork)))
        val description: TextView = Tv.onArtwork(Tv.lines(Tv.text(context, "", 15f, R.color.tv_text), 2))
        var key = ""
        var shownHeadline = ""
        var open: Runnable? = null
        var shown: Title? = null

        init {
            root.gravity = Gravity.BOTTOM; root.setPadding(gutterStart, dp(4), gutterEnd, dp(2))
            // A touch shortcut to the focused card. Clickable views take focus by default; this one must not, or Up
            // from the first shelf lands on it invisibly.
            root.tag = "spotlight"; root.isClickable = true; root.isFocusable = false
            root.setOnClickListener { if (open != null) { focused(key); open!!.run() } }
            logo.adjustViewBounds = true; logo.scaleType = ImageView.ScaleType.FIT_START; logo.maxHeight = dp(72); logo.maxWidth = dp(360)
            logo.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // A fixed height keeps the shelves still while the spotlight follows focus.
            root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(SPOTLIGHT_HEIGHT))
            // Wrapped widths: a MATCH_PARENT text view would ignore its maximum width.
            title.maxWidth = dp(600); meta.maxWidth = dp(600); description.maxWidth = dp(520)
            root.addView(logo, wrap()); root.addView(title, wrap())
            root.addView(meta, wrap()); root.addView(description, wrap())
            (title.layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
            (logo.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
            meta.setPadding(0, dp(10), 0, 0); description.setPadding(0, dp(8), 0, 0)
        }
        fun show(media: Title?, tileKey: String, action: Runnable?, headline: String) {
            key = tileKey; open = action
            if (media == null || media === shown && headline.equals(shownHeadline)) return
            shown = media; shownHeadline = headline
            root.contentDescription = if (headline.isEmpty()) media.name else media.name + ". " + headline
            title.text = media.name; gone(title, false)
            val facts = metaLine(media, false); val type = kind(media)
            val lead = if (headline.isEmpty()) type else headline
            val line = android.text.SpannableStringBuilder(if (lead.isEmpty()) facts else if (facts.isEmpty()) lead else lead + "  ·  " + facts)
            // A headline (new episodes) reads first, in white semibold, ahead of the usual facts.
            if (!headline.isEmpty()) {
                line.setSpan(FontSpan(Tv.Font.SEMIBOLD.get(context)), 0, lead.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                line.setSpan(android.text.style.ForegroundColorSpan(context.getColor(R.color.tv_text)), 0, lead.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            meta.text = line; gone(meta, line.length == 0)
            description.text = media.description; gone(description, media.description.isEmpty())
            logo.setImageDrawable(null); gone(logo, true)
            val logoUrl = media.logo
            if (logoUrl != null) {
                val wanted: Title = media
                artwork.fetch(logoUrl, Artwork.LOGO, true) { bitmap ->
                    if (shown !== wanted) return@fetch
                    logo.setImageBitmap(bitmap); gone(logo, false); gone(title, true)
                }
            }
            listener.featured(media)
        }
    }
    private fun spotlight(item: Item): View {
        val created = Spotlight()
        spotlight = created
        var first: Item.Tile? = featured
        if (first == null) for (i in items) if (!i.tiles.isEmpty()) { first = i.tiles.get(0); break }
        if (first != null) created.show(first.media, first.key, first.action, first.headline)
        else if (item.media != null) created.show(item.media, "", null, "")
        return created.root
    }
    private fun feature(tile: Item.Tile) {
        if (spotlight == null || tile.media == null) return
        featured = tile; spotlight!!.show(tile.media, tile.key, tile.action, tile.headline)
    }
    /** A typeface for part of a line; `TypefaceSpan(Typeface)` needs API 28. */
    private class FontSpan(private val typeface: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(paint: android.text.TextPaint) { paint.typeface = typeface }
        override fun updateMeasureState(paint: android.text.TextPaint) { paint.typeface = typeface }
    }

    // Details header: logo or title, facts and description over the title's backdrop.
    private fun details(item: Item): View {
        val media: Title = item.media!!
        val root = column(); root.gravity = Gravity.BOTTOM; root.minimumHeight = dp(220)
        root.setPadding(gutterStart, dp(4), gutterEnd, dp(14))
        val overline = Tv.onArtwork(Tv.oneLine(Tv.type(context, kind(media), Tv.Type.EYEBROW, R.color.tv_text_on_artwork)))
        overline.isAllCaps = true; root.addView(overline)
        val logo = ImageView(context)
        logo.adjustViewBounds = true; logo.scaleType = ImageView.ScaleType.FIT_START; logo.maxHeight = dp(92); logo.maxWidth = dp(400)
        logo.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; logo.visibility = View.GONE
        val logoParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        logoParams.topMargin = dp(10)
        root.addView(logo, logoParams)
        val title = Tv.onArtwork(Tv.lines(Tv.type(context, media.name, Tv.Type.HERO, R.color.tv_text), 2)); title.maxWidth = dp(640); title.setPadding(0, dp(8), 0, 0)
        root.addView(title, wrap())
        // Reveal the finished header together: text must never flash before a cold logo download.
        val logoUrl = media.logo
        if (logoUrl != null) {
            root.visibility = View.INVISIBLE
            artwork.titleLogo(logoUrl, { bitmap ->
                logo.setImageBitmap(bitmap); gone(logo, false); gone(title, true); root.visibility = View.VISIBLE
            }, { root.visibility = View.VISIBLE })
        } else if (item.loading) root.visibility = View.INVISIBLE
        var facts = metaLine(media)
        if (!media.directors.isEmpty()) facts += (if (facts.isEmpty()) "" else "  ·  ") + "Directed by " + media.directors.subList(0, Math.min(2, media.directors.size)).joinToString(", ")
        if (!facts.isEmpty()) {
            val meta = Tv.onArtwork(Tv.oneLine(Tv.type(context, facts, Tv.Type.META, R.color.tv_text_on_artwork))); meta.maxWidth = dp(640); meta.setPadding(0, dp(12), 0, 0)
            root.addView(meta, wrap())
        }
        if (!media.description.isEmpty()) {
            val about = Tv.onArtwork(Tv.lines(Tv.text(context, media.description, 15f, R.color.tv_text), 3)); about.maxWidth = dp(560); about.setPadding(0, dp(8), 0, 0)
            root.addView(about, wrap())
        }
        if (!item.detail.isEmpty()) {
            val note = Tv.onArtwork(Tv.oneLine(Tv.text(context, item.detail, 13.5f, R.color.tv_warning))); note.setPadding(0, dp(8), 0, 0); root.addView(note)
        }
        root.layoutParams = fill()
        return root
    }

    private fun intro(item: Item): View {
        val root = column(); root.gravity = Gravity.CENTER_HORIZONTAL; root.setPadding(gutterStart, dp(28), gutterEnd, dp(18))
        if (!item.meta.isEmpty()) {
            val overline = Tv.heading(context, item.meta, 13f, R.color.tv_text_secondary); overline.letterSpacing = 0.12f; overline.isAllCaps = true
            overline.gravity = Gravity.CENTER; root.addView(overline)
        }
        val title = Tv.bold(context, item.title, 38f); title.gravity = Gravity.CENTER; title.setPadding(0, dp(10), 0, 0); root.addView(title)
        val detail = Tv.text(context, item.detail, 16f, R.color.tv_text_secondary); detail.gravity = Gravity.CENTER
        detail.setLineSpacing(0f, 1.22f); detail.maxWidth = dp(620); detail.setPadding(0, dp(12), 0, 0); root.addView(detail, wrap())
        root.layoutParams = fill()
        return root
    }

    private fun pageTitle(item: Item): View {
        val title = Tv.onArtwork(Tv.oneLine(Tv.type(context, item.title, Tv.Type.TITLE, R.color.tv_text))); title.setPadding(gutterStart, dp(6), gutterEnd, dp(4))
        title.tag = item.key
        title.layoutParams = fill()
        return title
    }
    private fun seriesHeader(item: Item): View {
        val root = FrameLayout(context); root.tag = item.key
        root.setPadding(gutterStart, dp(12), gutterEnd, dp(16))
        root.contentDescription = item.media!!.name
        val fallback = Tv.onArtwork(Tv.oneLine(Tv.type(context, item.media!!.name, Tv.Type.TITLE, R.color.tv_text)))
        root.addView(fallback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL))
        val logo = ImageView(context); logo.tag = "series-logo"
        logo.adjustViewBounds = true; logo.maxWidth = dp(360); logo.maxHeight = dp(52)
        logo.scaleType = ImageView.ScaleType.FIT_START; logo.visibility = View.GONE
        logo.contentDescription = item.media!!.name
        root.addView(logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL))
        val logoUrl = item.media!!.logo
        if (logoUrl != null) artwork.fetch(logoUrl, Artwork.LOGO, true) { bitmap ->
            logo.setImageBitmap(bitmap); logo.visibility = View.VISIBLE; fallback.visibility = View.GONE
            logo.alpha = 0f; logo.animate().alpha(1f).setDuration(160).start()
        }
        root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80))
        return root
    }
    /** A shelf or grid heading: white, above artwork. */
    private fun header(title: String, detail: String?, topDp: Int): View { return header(title, detail, topDp, 18f, R.color.tv_text) }
    /** A list section heading, as in tvOS Settings: smaller and grey, so the rows below lead. */
    private fun sectionHeader(title: String, detail: String?): View { return header(title, detail, 22, 14f, R.color.tv_text_secondary) }
    private fun header(title: String, detail: String?, topDp: Int, sp: Float, color: Int): View {
        val row = LinearLayout(context); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.BOTTOM
        // List headings line up with the labels inside the rows below them, as in tvOS Settings.
        row.setPadding(gutterStart + (if (color == R.color.tv_text) 0 else dp(ROW_INSET)), dp(topDp), gutterEnd, dp(2))
        // One line of text, so a heading such as "Generated series · Local fixtures" is never mistaken for a card's title.
        val text = android.text.SpannableStringBuilder(title)
        if (detail != null && !detail.isEmpty()) {
            val start = text.length
            text.append("   ").append(detail)
            val span = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            text.setSpan(android.text.style.AbsoluteSizeSpan(13, true), start, text.length, span)
            text.setSpan(android.text.style.ForegroundColorSpan(context.getColor(R.color.tv_text_tertiary)), start, text.length, span)
            val regular: Typeface = Tv.Font.REGULAR.get(context)
            text.setSpan(object : android.text.style.MetricAffectingSpan() {
                override fun updateMeasureState(paint: android.text.TextPaint) { paint.typeface = regular }
                override fun updateDrawState(paint: android.text.TextPaint) { paint.typeface = regular }
            }, start, text.length, span)
        }
        val heading = Tv.onArtwork(Tv.oneLine(Tv.heading(context, "", sp, color))); heading.text = text
        if (color == R.color.tv_text) heading.typeface = Tv.Font.SEMIBOLD.get(context)
        heading.contentDescription = if (detail == null || detail.isEmpty()) title else title + ", " + detail
        row.addView(heading)
        row.layoutParams = fill()
        return row
    }

    // Shelves: a heading over a horizontally scrolling row of tiles.
    private fun shelf(item: Item): View { return shelf(item, null) }
    private fun shelf(item: Item, season: View?): View {
        val root = LinearLayout(context); root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false; root.clipToPadding = false
        if (!item.title.isEmpty() || !item.detail.isEmpty()) root.addView(header(item.title, item.detail, 14))
        val list = RecyclerView(context)
        val layout = Layout(context, RecyclerView.HORIZONTAL, 96); layout.navigationTarget(season)
        list.layoutManager = layout
        list.clipToPadding = false; list.clipChildren = false; list.itemAnimator = null
        list.setHasFixedSize(true)
        list.setPadding(gutterStart, dp(if (season == null) 12 else 16), gutterEnd, dp(14))
        list.isHorizontalScrollBarEnabled = false
        val adapter = TileAdapter(item.tiles, item.style, if (item.style == Item.Tile.Style.POSTER) POSTER_WIDTH else LANDSCAPE_WIDTH,
                if (season == null) GAP else GAP + 2)
        list.adapter = adapter
        retainScroll(list, item.key)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var inShelf = -1
        for (i in 0 until item.tiles.size) if (item.tiles.get(i).key.equals(restore)) inShelf = i
        if (inShelf >= 0) {
            if (!childScroll.containsKey(item.key)) list.scrollToPosition(inShelf)
            val key = restore
            list.post { val target: View? = list.findViewWithTag(key); if (target != null && key.equals(restore) && list.isAttachedToWindow) target.requestFocus() }
        }
        root.layoutParams = fill()
        return root
    }

    /**
     * Equal-width tiles: weights rather than a measured width, which may still be the previous page's. Each column is
     * an equal slot that gives up the same share of the gaps, so a short last row lines up with the full rows above.
     */
    private fun grid(item: Item): View {
        val columns = Integer.parseInt(item.value)
        val tiles: MutableList<View> = ArrayList()
        for (tile in item.tiles) tiles.add(this.tile(tile, item.style, 0))
        return gridRow(tiles, columns)
    }
    /** A row of cards whose slots must be traversed before leaving the page. */
    class GridRow(context: Context) : LinearLayout(context)
    private fun gridRow(tiles: List<View>, columns: Int): View {
        val row = GridRow(context); row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false; row.clipToPadding = false; row.weightSum = columns.toFloat()
        row.setPadding(gutterStart, dp(12), gutterEnd, dp(6))
        val gap = dp(GAP)
        for (i in 0 until tiles.size) {
            val slot = FrameLayout(context); slot.clipChildren = false; slot.clipToPadding = false
            slot.setPadding(gap * i / columns, 0, gap - gap * (i + 1) / columns, 0)
            slot.addView(tiles.get(i), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(slot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.layoutParams = fill()
        return row
    }

    private inner class TileAdapter(
        val tiles: List<Item.Tile>, val style: Item.Tile.Style, val widthDp: Int, val gapDp: Int, val matchSpan: Boolean,
        var columns: Int, val verticalGapDp: Int
    ) : RecyclerView.Adapter<Holder>() {
        constructor(tiles: List<Item.Tile>, style: Item.Tile.Style, widthDp: Int) : this(tiles, style, widthDp, GAP, false)
        constructor(tiles: List<Item.Tile>, style: Item.Tile.Style, widthDp: Int, gapDp: Int) : this(tiles, style, widthDp, gapDp, false)
        constructor(tiles: List<Item.Tile>, style: Item.Tile.Style, widthDp: Int, gapDp: Int, matchSpan: Boolean) :
                this(tiles, style, widthDp, gapDp, matchSpan, 0, 0)
        override fun getItemCount(): Int { return tiles.size }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val box = FrameLayout(context); box.clipChildren = false; box.clipToPadding = false
            box.layoutParams = RecyclerView.LayoutParams(if (matchSpan) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            return Holder(box)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val box = holder.itemView as FrameLayout; box.removeAllViews()
            val tile = this@PageAdapter.tile(tiles.get(position), style, dp(widthDp))
            box.transitionName = "tile:" + tiles.get(position).key
            spacing(box, position)
            box.addView(tile)
        }
        private fun spacing(box: View, position: Int) {
            if (columns > 0) {
                // Each column gives up the same share of the gaps, so every tile in a grid has the same width.
                val column = position % columns; val gap = dp(gapDp)
                box.setPadding(gap * column / columns, 0, gap - gap * (column + 1) / columns, dp(verticalGapDp))
            } else box.setPadding(0, 0, if (position == tiles.size - 1) 0 else dp(gapDp), dp(verticalGapDp))
        }
        /** Reflow the same cards without rebinding their artwork or losing the selected result. */
        fun columns(grid: RecyclerView, value: Int) {
            columns = value
            for (i in 0 until grid.childCount) {
                val box = grid.getChildAt(i)
                val position = grid.getChildAdapterPosition(box)
                if (position != RecyclerView.NO_POSITION) spacing(box, position)
            }
        }
    }

    // Skeletons: each page's loading state, built from the same blocks as the page, with the same sizes and spacing.
    // A bar stands where each line of text will be, inside an empty text view of that line's size, so the page
    // replaces it without anything moving.

    /** Search results while they load: the results grid's own columns and gaps. */
    private fun skeletonCards(count: Int, columns: Int): RecyclerView.Adapter<Holder> {
        return object : RecyclerView.Adapter<Holder>() {
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
                val box = FrameLayout(context)
                box.clipChildren = false; box.clipToPadding = false
                box.addView(skeletonTile(Item.Tile.Style.POSTER, 0), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                return Holder(box)
            }
            override fun onBindViewHolder(holder: Holder, position: Int) {
                val column = position % columns; val gap = dp(14)
                holder.itemView.setPadding(gap * column / columns, 0, gap - gap * (column + 1) / columns, dp(18))
            }
            override fun getItemCount(): Int { return count }
        }
    }

    private fun skeleton(item: Item): View {
        val root = LinearLayout(context); root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false; root.clipToPadding = false
        val poster = Item.Tile.Style.POSTER
        when (item.value) {
            "Home" -> {
                root.addView(skeletonSpotlight()); root.addView(skeletonShelf(poster)); root.addView(skeletonShelf(poster)) }
            "Home results" -> {
                root.addView(skeletonShelf(poster)); root.addView(skeletonShelf(poster)) }
            "Add-ons" -> {
                root.addView(skeletonTitle(180)); root.addView(skeletonActions(true, 46, 84, 106, 102, 88))
                root.addView(skeletonHeading(14f, 22, ROW_INSET, 56))
                for (i in 0 until 5) root.addView(skeletonSource(intArrayOf(132, 104, 150, 118, 140)[i], intArrayOf(200, 230, 210, 180, 220)[i], if (i == 0) 64 else 0))
            }
            "Add-on review" -> {
                root.addView(skeletonTitle(200)); root.addView(skeletonText(false, 0, 600, 600, 340)); root.addView(skeletonText(false, 0, 230, 150))
                root.addView(skeletonActions(false, 148)); root.addView(skeletonText(false, 0, 330)) }
            "Catalogue results" -> {
                root.addView(skeletonGrid(poster, 6)); root.addView(skeletonGrid(poster, 6)) }
            "Source results" -> {
                for (i in 0 until 4) root.addView(skeletonSource(170, 150, 0)) }
            "Store results" -> {
                for (i in 0 until 4) root.addView(skeletonSource(170, 150, 0)) }
            "Catalogue" -> {
                root.addView(skeletonTitle(170)); root.addView(skeletonGrid(poster, 6)); root.addView(skeletonGrid(poster, 6)) }
            else -> return loading()
        }
        root.layoutParams = fill()
        return root
    }

    /** A bar for each line of `text`: the view keeps its real size and spacing, its words hidden. */
    private fun skeletonLines(text: TextView, vararg widthsDp: Int): View {
        val box = FrameLayout(context)
        val blank = StringBuilder(" ")
        for (i in 1 until widthsDp.size) blank.append("\n ")
        text.text = blank; text.maxLines = Math.max(1, widthsDp.size)
        text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        box.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Each bar is centred on its line's capital letters: half a cap height above the baseline. The first line's
        // baseline sits below the font's own top padding.
        val metrics = text.paint.fontMetricsInt
        val bar = Math.max(dp(6), Math.round(text.textSize * 0.62f)); val line = text.lineHeight
        val baseline = text.paddingTop - (if (text.includeFontPadding) metrics.top else metrics.ascent)
        for (i in 0 until widthsDp.size) {
            if (widthsDp[i] <= 0) continue
            val p = FrameLayout.LayoutParams(dp(widthsDp[i]), bar)
            p.leftMargin = text.paddingLeft
            p.topMargin = baseline + i * line - Math.round(text.textSize * 0.36f) - bar / 2
            box.addView(skeletonBar(R.drawable.tv_skeleton), p)
        }
        return box
    }

    private fun skeletonBar(background: Int): View {
        val view = View(context); view.setBackgroundResource(background)
        return view
    }

    private fun skeletonTitle(widthDp: Int): View {
        val title = skeletonLines(Tv.type(context, "", Tv.Type.TITLE, R.color.tv_text), widthDp)
        title.setPadding(gutterStart, dp(6), gutterEnd, dp(4)); title.layoutParams = fill()
        return title
    }

    /** A shelf or grid heading (18 sp, white) or a list section heading (14 sp, inset to the rows' labels). */
    private fun skeletonHeading(sp: Float, topDp: Int, insetDp: Int, widthDp: Int): View {
        val label = Tv.heading(context, "", sp, R.color.tv_text)
        if (sp >= 18) label.typeface = Tv.Font.SEMIBOLD.get(context)
        val heading = skeletonLines(label, widthDp)
        heading.setPadding(gutterStart + dp(insetDp), dp(topDp), gutterEnd, dp(2)); heading.layoutParams = fill()
        return heading
    }

    private fun skeletonText(inList: Boolean, overlineDp: Int, vararg widthsDp: Int): View {
        val root = column(); root.setPadding(gutterStart + (if (inList) dp(ROW_INSET) else 0), dp(14), gutterEnd, dp(2))
        if (overlineDp > 0) root.addView(skeletonLines(Tv.heading(context, "", 12f, R.color.tv_text_tertiary), overlineDp))
        val detail = Tv.text(context, "", 15f, R.color.tv_text_secondary); detail.setLineSpacing(0f, 1.22f); detail.setPadding(0, dp(5), 0, 0)
        root.addView(skeletonLines(detail, *widthsDp))
        root.layoutParams = fill()
        return root
    }

    /** A two-line row (a source or a listed add-on), with its status at the end. */
    private fun skeletonSource(titleDp: Int, metaDp: Int, statusDp: Int): View {
        val row = LinearLayout(context); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(ROW_INSET), dp(10), dp(14), dp(10))
        val text = LinearLayout(context); text.orientation = LinearLayout.VERTICAL
        text.addView(skeletonLines(Tv.heading(context, "", 15.5f, R.color.tv_on_glass), titleDp))
        val meta = Tv.text(context, "", 13f, R.color.tv_on_glass_secondary); meta.setPadding(0, dp(5), 0, 0)
        text.addView(skeletonLines(meta, metaDp))
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (statusDp > 0) row.addView(skeletonLines(Tv.text(context, "", 13f, R.color.tv_on_glass_secondary), statusDp))
        val box = FrameLayout(context); box.setPadding(gutterStart, dp(2), gutterEnd, dp(2))
        box.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.layoutParams = fill()
        return box
    }

    /** Buttons (44 dp) or filter chips (36 dp capsules) at their usual widths; -1 is a round icon button. */
    private fun skeletonActions(chips: Boolean, vararg widthsDp: Int): View {
        val row = LinearLayout(context); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(gutterStart, dp(10), gutterEnd, dp(10))
        val height = dp(if (chips) 36 else 44)
        for (i in 0 until widthsDp.size) {
            val round = widthsDp[i] < 0
            val p = LinearLayout.LayoutParams(if (round) height else dp(widthsDp[i]), height)
            if (i > 0) p.leftMargin = dp(12)
            row.addView(skeletonBar(if (round) R.drawable.tv_skeleton_round else if (chips) R.drawable.tv_skeleton_capsule else R.drawable.tv_skeleton), p)
        }
        row.layoutParams = fill()
        return row
    }

    /** Home's spotlight: its fixed height, with the title, facts and description at the bottom. */
    private fun skeletonSpotlight(): View {
        val root = column(); root.gravity = Gravity.BOTTOM; root.setPadding(gutterStart, dp(4), gutterEnd, dp(2))
        val title = Tv.type(context, "", Tv.Type.HERO, R.color.tv_text); title.setPadding(0, dp(4), 0, 0)
        val meta = Tv.type(context, "", Tv.Type.META, R.color.tv_text_on_artwork); meta.setPadding(0, dp(10), 0, 0)
        val description = Tv.text(context, "", 15f, R.color.tv_text); description.setPadding(0, dp(8), 0, 0)
        root.addView(skeletonLines(title, 300)); root.addView(skeletonLines(meta, 250)); root.addView(skeletonLines(description, 500, 380))
        root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(SPOTLIGHT_HEIGHT))
        return root
    }

    private fun skeletonTile(style: Item.Tile.Style, widthDp: Int): View {
        val tile = LinearLayout(context); tile.orientation = LinearLayout.VERTICAL
        val frame = AspectFrame(context, if (style == Item.Tile.Style.POSTER) 1.5f else 0.5625f)
        frame.setBackgroundResource(R.drawable.tv_skeleton)
        tile.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val title = Tv.heading(context, "", 13.5f, R.color.tv_caption); title.setPadding(dp(2), dp(9), dp(2), 0)
        val subtitle = Tv.text(context, "", 12f, R.color.tv_text_secondary); subtitle.setPadding(dp(2), dp(3), dp(2), 0)
        val poster = style == Item.Tile.Style.POSTER
        tile.addView(skeletonLines(title, if (poster) 84 else 132))
        if (!poster) tile.addView(skeletonLines(subtitle, 90))
        tile.layoutParams = LinearLayout.LayoutParams(if (widthDp > 0) dp(widthDp) else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return tile
    }

    /** A shelf: heading, then tiles at the shelf's width and gap, running off the edge as a real shelf does. */
    private fun skeletonShelf(style: Item.Tile.Style): View {
        val root = LinearLayout(context); root.orientation = LinearLayout.VERTICAL
        root.addView(skeletonHeading(18f, 14, 0, 150))
        val tiles = LinearLayout(context); tiles.orientation = LinearLayout.HORIZONTAL
        tiles.setPadding(gutterStart, dp(12), 0, dp(14))
        val width = if (style == Item.Tile.Style.POSTER) POSTER_WIDTH else LANDSCAPE_WIDTH
        for (i in 0 until (if (style == Item.Tile.Style.POSTER) 8 else 5)) {
            val tile = skeletonTile(style, width)
            if (i > 0) (tile.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(GAP)
            tiles.addView(tile)
        }
        val clip = HorizontalScrollView(context); clip.isHorizontalScrollBarEnabled = false; clip.isFocusable = false
        clip.addView(tiles)
        root.addView(clip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.layoutParams = fill()
        return root
    }

    /** One full grid row, as [grid] lays it out. */
    private fun skeletonGrid(style: Item.Tile.Style, columns: Int): View {
        val tiles: MutableList<View> = ArrayList()
        for (i in 0 until columns) tiles.add(skeletonTile(style, 0))
        return gridRow(tiles, columns)
    }

    /** Keeps artwork at its aspect ratio for whatever width the tile receives. */
    private class AspectFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = View.MeasureSpec.getSize(widthSpec)
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Math.round(width * ratio), View.MeasureSpec.EXACTLY))
        }
    }

    /**
     * A rounded image (or a titled placeholder) with a one-line title and subtitle that never clip.
     * `width` is in pixels; zero lets the parent assign it (grid rows).
     */
    private fun tile(tile: Item.Tile, style: Item.Tile.Style, width: Int): View {
        if (tile.block) return block(tile, width)
        val root = LinearLayout(context); root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false; root.clipToPadding = false
        val described = if (tile.description != null) tile.description else if (tile.subtitle.isEmpty()) tile.title else tile.title + ". " + tile.subtitle
        root.contentDescription = if (tile.flag.isEmpty()) described else described + ". " + tile.flag
        val frame: FrameLayout = AspectFrame(context, if (style == Item.Tile.Style.POSTER) 1.5f else 0.5625f)
        root.setTag(R.id.popup_anchor, frame)
        frame.setBackgroundResource(R.drawable.tv_artwork); frame.clipToOutline = true
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.resources.getDimension(R.dimen.tv_radius_control))
            }
        }
        val placeholder = Tv.lines(Tv.bold(context, if (tile.episodeNumber >= 0) "" else tile.title, if (style == Item.Tile.Style.POSTER) 15f else 17f), 4)
        placeholder.gravity = Gravity.CENTER; placeholder.setPadding(dp(10), 0, dp(10), 0)
        placeholder.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        frame.addView(placeholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val image = ImageView(context); image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        artwork.load(image, tile.image)
        if (tile.progress >= 0) {
            // The artwork darkens under the bar so it stays legible on light frames.
            val shade = View(context); shade.setBackgroundResource(R.drawable.tv_tile_shade)
            frame.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), Gravity.BOTTOM))
            val track = LinearLayout(context); track.setBackgroundResource(R.drawable.tv_progress_track)
            val fill = View(context); fill.setBackgroundResource(R.drawable.tv_progress_fill)
            track.addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.02f, Math.min(1f, tile.progress))))
            track.addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0f, 1f - tile.progress)))
            val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM)
            p.setMargins(dp(10), 0, dp(10), dp(10)); frame.addView(track, p)
        }
        if (tile.badge != 0) {
            val badge = Tv.icon(context, tile.badge, 22, R.color.tv_text)
            badge.setBackgroundResource(R.drawable.tv_badge); badge.setPadding(dp(5), dp(5), dp(5), dp(5))
            val p = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END)
            p.setMargins(0, dp(7), dp(7), 0); frame.addView(badge, p)
        }
        if (!tile.flag.isEmpty()) {
            val flag = Tv.oneLine(Tv.heading(context, tile.flag, 11f, R.color.tv_on_focus))
            flag.tag = "tile-flag"; flag.isAllCaps = true; flag.letterSpacing = 0.06f
            flag.setBackgroundResource(R.drawable.tv_flag); flag.setPadding(dp(7), dp(2), dp(7), dp(2))
            flag.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            p.setMargins(dp(7), dp(7), 0, 0); frame.addView(flag, p)
        }
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(frame, LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT))
        val captions: MutableList<View> = ArrayList()
        if (tile.episodeNumber >= 0) {
            val number = Tv.onArtwork(Tv.oneLine(Tv.heading(context, "Episode " + tile.episodeNumber, 12f, R.color.tv_text_secondary)))
            number.tag = "episode-number"
            val episode = LinearLayout(context); episode.gravity = Gravity.CENTER_VERTICAL
            episode.clipChildren = false; episode.clipToPadding = false; episode.setPadding(dp(2), dp(9), dp(2), 0)
            episode.addView(number, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(episode, LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT)); captions.add(episode)
        }
        val title = Tv.onArtwork(Tv.lines(Tv.heading(context, tile.title, if (tile.episodeNumber >= 0) 15f else 13.5f, R.color.tv_caption), if (tile.episodeNumber >= 0) 2 else 1))
        title.tag = "tile-title"; title.isDuplicateParentStateEnabled = true; title.setPadding(dp(2), dp(if (tile.episodeNumber >= 0) 3 else 9), dp(2), 0)
        root.addView(title, LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT))
        captions.add(title)
        val detail = if (tile.episodeNumber >= 0) tile.synopsis else tile.subtitle
        if (!detail.isEmpty()) {
            val subtitle = Tv.onArtwork(Tv.lines(Tv.text(context, detail, 12f, R.color.tv_text_secondary), if (tile.episodeNumber >= 0) 3 else 1))
            subtitle.tag = if (tile.episodeNumber >= 0) "episode-description" else "tile-detail"; subtitle.setPadding(dp(2), dp(4), dp(2), 0)
            root.addView(subtitle, LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT)); captions.add(subtitle)
        }
        if (tile.episodeNumber >= 0 && !tile.releaseDate.isEmpty()) {
            val date = Tv.onArtwork(Tv.oneLine(Tv.heading(context, tile.releaseDate, 12f, R.color.tv_text_tertiary)))
            date.tag = "episode-release-date"; date.contentDescription = "Release date, " + tile.releaseDate
            date.setPadding(dp(2), dp(7), dp(2), 0)
            root.addView(date, LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT)); captions.add(date)
        }
        root.layoutParams = LinearLayout.LayoutParams(if (width > 0) width else fill, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Only the artwork grows, rimmed in white over a deep shadow; the caption keeps its size and moves down with
        // the artwork's lower edge, so nothing around the card shifts or rescales.
        frame.isDuplicateParentStateEnabled = true
        frame.foreground = context.getDrawable(R.drawable.tv_focus_rim)
        Tv.control(root, tile.key, tile.action, { key -> focused(key); feature(tile) }, 1f, false)
        val focus = root.onFocusChangeListener
        root.setOnFocusChangeListener { v, has ->
            focus.onFocusChange(v, has)
            val scale = if (has) Tv.LIFT_TILE else 1f
            frame.animate().cancel()
            frame.animate().scaleX(scale).scaleY(scale).translationZ((if (has) dp(18) else 0).toFloat()).setDuration(if (has) 200L else 160L).start()
            val drop = frame.height * (scale - 1f) / 2f
            for (caption in captions) caption.animate().translationY(drop).setDuration(if (has) 200L else 160L).start()
        }
        if (tile.key.equals(restore)) restoreLater(root, tile.key)
        return root
    }

    /** A category block: a large centred name over a restrained colour wash, with room for focus growth. */
    private fun block(tile: Item.Tile, width: Int): View {
        val frame: FrameLayout = AspectFrame(context, BLOCK_RATIO)
        frame.background = CategoryBackground(tile.colorIndex); frame.clipToOutline = true
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.resources.getDimension(R.dimen.tv_radius_control))
            }
        }
        frame.contentDescription = if (tile.description != null) tile.description else tile.title
        val text = LinearLayout(context); text.orientation = LinearLayout.VERTICAL; text.gravity = Gravity.CENTER
        text.setPadding(dp(12), 0, dp(12), 0); text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val name = Tv.lines(Tv.bold(context, tile.title, 22f), 2); name.gravity = Gravity.CENTER; text.addView(name, wrap())
        if (!tile.subtitle.isEmpty()) {
            val detail = Tv.oneLine(Tv.text(context, tile.subtitle, 12f, R.color.tv_text_secondary)); detail.gravity = Gravity.CENTER
            detail.setPadding(0, dp(4), 0, 0); text.addView(detail, wrap())
        }
        frame.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        frame.foreground = context.getDrawable(R.drawable.tv_focus_rim)
        frame.layoutParams = LinearLayout.LayoutParams(if (width > 0) width else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        Tv.control(frame, tile.key, tile.action, this::focused, 1f, false)
        val focus = frame.onFocusChangeListener
        frame.setOnFocusChangeListener { v, has ->
            focus.onFocusChange(v, has)
            val scale = if (has) Tv.LIFT_TILE else 1f
            v.animate().cancel()
            v.animate().scaleX(scale).scaleY(scale).translationZ((if (has) dp(18) else 0).toFloat()).setDuration(if (has) 200L else 160L).start()
        }
        if (tile.key.equals(restore)) restoreLater(frame, tile.key)
        return frame
    }
    // Buttons, chips and rows.
    private fun catalogueHeader(item: Item): View {
        val root = LinearLayout(context); root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false; root.clipToPadding = false
        root.setPadding(gutterStart, dp(8), gutterEnd, dp(14))
        val line = LinearLayout(context); line.gravity = Gravity.CENTER_VERTICAL
        line.clipChildren = false; line.clipToPadding = false
        val titles = LinearLayout(context); titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(0, 0, dp(20), 0)
        val title = Tv.lines(Tv.type(context, item.title, Tv.Type.TITLE, R.color.tv_text), 2); titles.addView(title, fill())
        val kind = Tv.oneLine(Tv.text(context, item.detail, 13f, R.color.tv_text_secondary))
        kind.setPadding(0, dp(4), 0, 0); titles.addView(kind, fill())
        line.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(line)
        root.layoutParams = fill(); return root
    }

    /** One compact footer; no paging controls between filters and posters. */
    private fun pagination(item: Item): View {
        val root = LinearLayout(context); root.gravity = Gravity.CENTER
        root.clipChildren = false; root.clipToPadding = false
        root.setPadding(gutterStart, dp(18), gutterEnd, dp(20))
        val capsule = LinearLayout(context); capsule.gravity = Gravity.CENTER_VERTICAL
        capsule.setPadding(dp(4), dp(4), dp(4), dp(4))
        capsule.setBackgroundResource(R.drawable.tv_pagination)
        capsule.clipChildren = false; capsule.clipToPadding = false
        var previous: Item.Action? = null; var next: Item.Action? = null
        for (action in item.actions) {
            if (action.key.startsWith("previous-page:")) previous = action else next = action
        }
        for (position in 0 until 3) {
            if (position == 1) {
                val status = Tv.heading(context, item.title, 14f, R.color.tv_text_secondary)
                status.gravity = Gravity.CENTER
                capsule.addView(status, LinearLayout.LayoutParams(dp(92), dp(40)))
            } else {
                capsule.addView(pageButton(if (position == 0) previous else next, position == 0), LinearLayout.LayoutParams(dp(40), dp(40)))
            }
        }
        root.addView(capsule); restoreControls(root, item)
        root.layoutParams = fill(); return root
    }

    /** A round page arrow: a chevron in a circle, dimmed and inert where there is no page to go to. */
    private fun pageButton(action: Item.Action?, previous: Boolean): View {
        val button = FrameLayout(context)
        button.setBackgroundResource(R.drawable.tv_button_round)
        val chevron = Tv.icon(context, R.drawable.ic_tv_chevron, 18, if (action == null) R.color.tv_text_tertiary else R.color.tv_on_glass)
        chevron.isDuplicateParentStateEnabled = true
        if (previous) chevron.rotation = 180f
        button.addView(chevron, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        if (action == null) { button.alpha = 0.5f; button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; return button }
        button.contentDescription = action.label; button.tooltipText = action.label
        Tv.control(button, action.key, action.action, this::focused, Tv.LIFT_ICON, false)
        return button
    }

    private fun restoreControls(root: ViewGroup, item: Item) {
        for (action in item.actions) if (action.key.equals(restore)) {
            val control: View? = root.findViewWithTag(action.key)
            if (control != null) restoreLater(control, action.key)
        }
    }

    private fun actions(item: Item, gravity: Int): View {
        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false; scroll.clipChildren = false; scroll.clipToPadding = false
        scroll.isFillViewport = gravity == Gravity.CENTER_HORIZONTAL
        val row = LinearLayout(context); row.orientation = LinearLayout.HORIZONTAL; row.gravity = gravity or Gravity.CENTER_VERTICAL
        row.clipChildren = false; row.clipToPadding = false
        row.setPadding(gutterStart, dp(10), gutterEnd, dp(10))
        var icons = false
        for (action in item.actions) icons = icons or (action.style == Item.Action.Style.ICON)
        // tvOS names an icon-only button beneath it while it has focus.
        val caption: FocusCaption? = if (icons) FocusCaption(context) else null
        for (i in 0 until item.actions.size) {
            val action = item.actions.get(i)
            val button = Tv.button(context, action, this::focused)
            if (i > 0) (button.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(12)
            row.addView(button)
            bindCaption(button, action, caption, scroll)
            if (action.key.equals(restore)) restoreLater(button, action.key)
        }
        val binding = Binding { next ->
            if (next.kind != item.kind || !next.key.equals(item.key) || next.centered != item.centered || next.actions.size != item.actions.size) return@Binding false
            for (i in 0 until next.actions.size)
                if (!next.actions.get(i).key.equals(item.actions.get(i).key) || next.actions.get(i).style != item.actions.get(i).style) return@Binding false
            for (i in 0 until next.actions.size) {
                val action = next.actions.get(i)
                val button = row.getChildAt(i) as FrameLayout
                Tv.bindButton(button, action, this::focused); bindCaption(button, action, caption, scroll)
                if (caption != null && button.hasFocus() && action.style == Item.Action.Style.ICON) caption.show(action.label, button, scroll)
            }
            true
        }
        scroll.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (caption == null) { scroll.setTag(R.id.page_binding, binding); scroll.layoutParams = fill(); return scroll }
        val column = LinearLayout(context); column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false; column.clipToPadding = false
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(caption)
        column.layoutParams = fill()
        column.setTag(R.id.page_binding, binding)
        return column
    }
    private fun bindCaption(button: View, action: Item.Action, caption: FocusCaption?, scroll: HorizontalScrollView) {
        if (caption == null) return
        val focus = button.onFocusChangeListener
        button.setOnFocusChangeListener { v, has ->
            focus.onFocusChange(v, has)
            if (has && action.style == Item.Action.Style.ICON) caption.show(action.label, v, scroll)
            else caption.hide(action.label)
        }
    }
    private fun chips(item: Item): View {
        val root = LinearLayout(context); root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false; root.clipToPadding = false
        // Chips are buttons, not rows: their heading lines up with the page edge, like a shelf's.
        if (!item.title.isEmpty()) root.addView(header(item.title, "", 14))
        val buttons = actions(item, Gravity.START); root.addView(buttons)
        root.setTag(R.id.page_binding, Binding { next -> next.title.equals(item.title) &&
                (buttons.getTag(R.id.page_binding) as Binding).update(next) })
        root.layoutParams = fill()
        return root
    }

    /** Focus changes just the episode shelf; the season strip stays attached and keeps its focus. */
    private fun episodes(item: Item): View {
        val root = LinearLayout(context); root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false; root.clipToPadding = false
        val seasons = HorizontalScrollView(context)
        seasons.isHorizontalScrollBarEnabled = false; seasons.clipChildren = false; seasons.clipToPadding = false
        val row = LinearLayout(context); row.gravity = Gravity.CENTER_VERTICAL
        row.clipChildren = false; row.clipToPadding = false; row.setPadding(gutterStart, dp(14), gutterEnd, dp(22))
        seasons.addView(row); root.addView(seasons)
        val shelf = SeasonShelf(context)
        root.addView(shelf, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val buttons: MutableMap<String, View> = java.util.LinkedHashMap()
        val selected = arrayOf("")
        val select = java.util.function.Consumer<String> { key ->
            if (key.equals(selected[0])) return@Consumer
            val index = position(item.key)
            val current = if (index >= 0) items.get(index) else item
            val pane: List<Item>? = current.panes!!.get(key)
            if (pane == null || pane.isEmpty()) return@Consumer
            val order: List<String> = ArrayList(current.panes!!.keys)
            val direction = if (order.indexOf(key) >= order.indexOf(selected[0])) 1 else -1
            selected[0] = key; current.value = key
            for (action in current.actions) {
                action.selected = action.key.equals(key)
                val button = buttons.get(action.key); if (button != null) button.isActivated = action.selected
            }
            shelf.show(this.shelf(pane.get(0), buttons.get(key)), direction)
            // Keep the existing strip when the surrounding overview contracts into episode browsing.
            if (index >= 0) snapshots.set(index, snapshot(current))
            val input = current.keyboardInput
            if (input != null) input.accept(key)
        }
        for (action in item.actions) {
            val button = Tv.button(context, action, this::focused)
            if (row.childCount > 0) (button.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(12)
            val focus = button.onFocusChangeListener
            button.setOnFocusChangeListener { view, has ->
                // A temporary focus on the first season during episode restoration must not change the pane.
                if (has && !replacingFocus && !restore.startsWith("episode:")) select.accept(action.key)
                focus.onFocusChange(view, has)
            }
            button.setOnHoverListener { view, event ->
                if ((event.actionMasked == android.view.MotionEvent.ACTION_HOVER_ENTER ||
                                event.actionMasked == android.view.MotionEvent.ACTION_HOVER_MOVE) && !view.hasFocus()) view.requestFocus()
                false
            }
            button.setOnClickListener { view ->
                select.accept(action.key); focused(action.key); Sounds.click()
                val first = firstFocusable(shelf); if (first != null) first.requestFocus()
            }
            button.setOnKeyListener { view, code, event ->
                if (code != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return@setOnKeyListener false
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val first = firstFocusable(shelf); if (first != null) first.requestFocus()
                }
                true
            }
            buttons.put(action.key, button); row.addView(button)
            if (action.key.equals(restore)) restoreLater(button, action.key)
        }
        select.accept(item.value)
        root.layoutParams = fill(); return root
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
        box.layoutParams = fill()
        return box
    }

    // States.
    private fun message(item: Item): View {
        val quiet = item.icon == 0 && item.actions.isEmpty()
        val root = column(); root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(gutterStart, dp(if (quiet) 100 else 52), gutterEnd, dp(12))
        if (item.icon != 0) {
            val disc = FrameLayout(context); disc.setBackgroundResource(R.drawable.tv_icon_disc)
            val icon = Tv.icon(context, item.icon, 26, R.color.tv_text)
            disc.addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            root.addView(disc, LinearLayout.LayoutParams(dp(64), dp(64)))
        }
        val title = if (quiet) Tv.text(context, item.title, 20f, R.color.tv_text_secondary)
                else Tv.heading(context, item.title, 22f, R.color.tv_text)
        title.gravity = Gravity.CENTER; title.setPadding(0, if (quiet) 0 else dp(18), 0, 0)
        root.addView(title, wrap())
        if (!item.detail.isEmpty()) {
            val detail = Tv.text(context, item.detail, if (quiet) 14f else 15f, if (quiet) R.color.tv_text_tertiary else R.color.tv_text_secondary); detail.gravity = Gravity.CENTER
            detail.setLineSpacing(0f, 1.22f); detail.maxWidth = dp(if (quiet) 400 else 520); detail.setPadding(0, dp(8), 0, dp(8)); root.addView(detail, wrap())
        }
        if (!item.actions.isEmpty()) root.addView(actions(item, Gravity.CENTER_HORIZONTAL))
        root.layoutParams = fill()
        return root
    }
    private fun notice(item: Item): View {
        val box = LinearLayout(context); box.orientation = LinearLayout.HORIZONTAL; box.gravity = Gravity.CENTER_VERTICAL
        box.clipChildren = false; box.clipToPadding = false
        box.setPadding(dp(18), dp(12), dp(8), dp(12))
        box.addView(Tv.icon(context, if (item.icon == 0) R.drawable.ic_tv_info else item.icon, 22, R.color.tv_text))
        val text = LinearLayout(context); text.orientation = LinearLayout.VERTICAL; text.setPadding(dp(14), 0, dp(12), 0)
        text.addView(Tv.heading(context, item.title, 15.5f, R.color.tv_text))
        if (!item.detail.isEmpty()) { val detail = Tv.lines(Tv.text(context, item.detail, 13.5f, R.color.tv_text_secondary), 3); detail.setPadding(0, dp(4), 0, 0); text.addView(detail) }
        box.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!item.actions.isEmpty()) {
            val inline = Item.actions(item.key + ":actions", item.actions)
            val buttons = actions(inline, Gravity.END)
            buttons.setPadding(0, 0, 0, 0)
            ((buttons as ViewGroup).getChildAt(0) as ViewGroup).setPadding(0, dp(4), dp(8), dp(4))
            box.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // The notice is glass; its buttons lift outside it, so they sit on the same blur rather than inside its clip.
        val glass = GlassView.surface(context); glass.setBackgroundResource(R.drawable.tv_notice)
        val wrap = FrameLayout(context); wrap.clipChildren = false; wrap.clipToPadding = false
        wrap.setPadding(gutterStart, dp(14), gutterEnd, dp(6))
        wrap.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wrap.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.layoutParams = fill()
        return wrap
    }
    private fun text(item: Item): View {
        val root = column(); root.setPadding(gutterStart + (if (inList(item)) dp(ROW_INSET) else 0), dp(14), gutterEnd, dp(2))
        if (!item.title.isEmpty()) {
            val title = Tv.onArtwork(Tv.heading(context, item.title, 12f, R.color.tv_text_tertiary)); title.letterSpacing = 0.1f; title.isAllCaps = true
            root.addView(title)
        }
        val detail = Tv.onArtwork(Tv.text(context, item.detail, 15f, R.color.tv_text_secondary)); detail.setLineSpacing(0f, 1.22f); detail.setPadding(0, dp(5), 0, 0)
        detail.setTextIsSelectable(false); root.addView(detail)
        root.layoutParams = fill()
        return root
    }
    private var searchPane: SearchPane? = null
    /** Left from the grid's leading column brings the editing column back. */
    private var arrowing = false
    /** Set while the activity dispatches a remote arrow key, so only a remote move into results hides the keyboard. */
    fun arrowing(value: Boolean) { arrowing = value }
    fun searchKey(code: Int): Boolean {
        return searchPane != null && searchPane!!.isAttachedToWindow && searchPane!!.key(code)
    }
    /** A transition's neutral focus holder represents the key/film it will hand focus to when layout settles. */
    fun persistentFocus(key: String): String {
        val pane = searchPane
        if (pane != null && key.equals("search-results")) {
            if (pane.visible) return pane.lastKeyboard
            if (pane.grid != null && !pane.lastResult.isEmpty()) return pane.lastResult
        }
        return key
    }
    private fun restoreSearchSelection() {
        val pane = searchPane
        if (pane == null || pane.grid == null) return
        val grid: RecyclerView = pane.grid!!
        val key = restore
        for (i in 0 until pane.item.tiles.size) if (pane.item.tiles.get(i).key.equals(key)) {
            val position = i
            // A partial answer may have attached the first rows before the final answer restores focus.
            // Wait for the saved viewport to lay out, then scroll only if the selected film is still offscreen.
            grid.post {
                if (grid.isAttachedToWindow && searchPane === pane && pane.grid === grid && key.equals(restore) &&
                        grid.findViewWithTag<View>(key) == null) grid.scrollToPosition(position)
            }
            return
        }
    }
    fun toggleSearchKeyboard() {
        if (searchPane != null && searchPane!!.isAttachedToWindow) searchPane!!.keyboard(!searchPane!!.visible, true, true)
    }
    private fun keyboard(item: Item): View {
        val root = SearchPane(item); searchPane = root
        root.orientation = LinearLayout.HORIZONTAL; root.gravity = Gravity.CENTER_VERTICAL
        root.clipChildren = false; root.clipToPadding = false; root.tag = "search-layout"
        root.setPadding(gutterStart, dp(8), gutterEnd, dp(12))
        val editor = LinearLayout(context); editor.gravity = Gravity.TOP
        editor.clipChildren = false; editor.clipToPadding = false; editor.tag = "search-keyboard"
        val keyboardPanel = LinearLayout(context); keyboardPanel.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        keyboardPanel.orientation = LinearLayout.VERTICAL
        keyboardPanel.clipChildren = false; keyboardPanel.clipToPadding = false
        editor.addView(keyboardPanel, LinearLayout.LayoutParams(keyboardWidth() + dp(12), ViewGroup.LayoutParams.WRAP_CONTENT))
        val queryParams = LinearLayout.LayoutParams(keyboardWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        queryParams.bottomMargin = dp(12)
        keyboardPanel.addView(queryText(root, item), queryParams)
        val surface = FrameLayout(context); surface.tag = "keyboard-surface"
        surface.setBackgroundResource(R.drawable.tv_dialog); surface.elevation = dp(6).toFloat()
        surface.clipChildren = false; surface.clipToPadding = false
        val surfaceInset = dp(6)
        surface.setPadding(surfaceInset, surfaceInset, surfaceInset, surfaceInset)
        val keys = keyboardKeys(item)
        surface.addView(keys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The separator belongs to the surface, keeping every keyboard row a regular set of focusable keys.
        val separator = View(context); separator.setBackgroundColor(context.getColor(R.color.tv_hairline))
        separator.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addView(separator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.TOP))
        keys.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val actions: View? = keys.findViewWithTag("keyboard-actions")
            if (actions != null) separator.translationY = (actions.top - dp(6)).toFloat()
        }
        keyboardPanel.addView(surface, LinearLayout.LayoutParams(keyboardWidth() + dp(12), ViewGroup.LayoutParams.WRAP_CONTENT))

        // The divider spans the query and keyboard: the wrapped editor gives its MATCH_PARENT child their height,
        // so it follows taller alphabets and character pages.
        val divider = View(context); divider.setBackgroundColor(context.getColor(R.color.tv_hairline))
        val dividerParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        dividerParams.setMargins(dp(10), 0, dp(16), 0); editor.addView(divider, dividerParams)
        val editorParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        editorParams.gravity = Gravity.TOP
        root.addView(editor, editorParams)
        root.editor = editor; editor.visibility = if (root.visible) View.VISIBLE else View.GONE

        // The column fades while the keyboard moves. An overlapping view fades through an offscreen layer cut to its
        // own bounds, which would crop the grid running on to the screen edges for the length of the fade.
        val results: LinearLayout = object : LinearLayout(context) {
            override fun hasOverlappingRendering(): Boolean { return false }
        }
        results.orientation = LinearLayout.VERTICAL
        results.clipChildren = false; results.clipToPadding = false
        results.setPadding(0, dp(8), 0, 0)
        // Keep the results viewport inside the visible page, including its bottom safe area.
        val resultsParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        resultsParams.gravity = Gravity.TOP
        root.addView(results, resultsParams)
        root.results = results; results.tag = "search-results"; results.isFocusableInTouchMode = true
        searchResults = results
        results(results, item)
        root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        listener.searchKeyboardChanged(root.visible)
        return root
    }
    /** What has been typed, as plain text heading the keyboard; it leaves with the keyboard. */
    private fun queryText(pane: SearchPane, item: Item): TextView {
        val text = Tv.heading(context, "", 22f, R.color.tv_text)
        text.id = R.id.search_query; text.setSingleLine(true); text.includeFontPadding = false
        // The end of a long query is what was just typed.
        text.ellipsize = android.text.TextUtils.TruncateAt.START
        text.setPadding(dp(2), 0, dp(2), 0)
        pane.queries.add(text); pane.query(item.value)
        return text
    }
    /** The layout changes in place: one query, one results grid and no provider work during a mode switch. */
    private inner class SearchPane(val item: Item) : LinearLayout(context) {
        lateinit var editor: LinearLayout
        lateinit var results: LinearLayout
        val queries: MutableList<TextView> = ArrayList()
        var grid: RecyclerView? = null
        var visible = false
        var changing = false
        var modeAnimation: android.animation.Animator? = null
        var editorAnimation: android.animation.Animator? = null
        var reflow: Runnable? = null
        /** Ends the latest keyboard change, at once if the page is left before its transition does. */
        var finishing: Runnable? = null
        var transition = 0
        var lastKeyboard: String
        var lastResult = ""
        init {
            visible = item.keyboardVisible
            lastKeyboard = item.key + ":" + KeyboardLanguages.alphabet(item.keyboardLanguage)[0]
        }
        fun query(value: String) {
            for (text in queries) {
                text.text = if (value.isEmpty()) "Search" else value
                text.setTextColor(context.getColor(if (value.isEmpty()) R.color.tv_text_tertiary else R.color.tv_text))
            }
        }
        fun key(code: Int): Boolean {
            // Left stays inside its keyboard row, including the leading edge; Search never opens navigation.
            val currentKey: View? = findFocus()
            if (visible && code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && currentKey != null &&
                    currentKey.tag is String && currentKey.tag.toString().startsWith("keyboard:") &&
                    currentKey.parent is ViewGroup) {
                val row = currentKey.parent as ViewGroup
                val index = row.indexOfChild(currentKey)
                if (index > 0) row.getChildAt(index - 1).requestFocus()
                return true
            }
            if (grid == null) {
                if (visible && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && editor.hasFocus()) {
                    val current: View? = findFocus()
                    if (current != null && current.parent is ViewGroup) {
                        val row = current.parent as ViewGroup
                        val retry: View? = results.findViewWithTag("reload-search")
                        if (row.getChildAt(row.childCount - 1) === current && retry != null) { retry.requestFocus(); return true }
                    }
                }
                if (visible && results.hasFocus() && code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { focusKeyboard(); return true }
                if (!visible && hasFocus() && code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { keyboard(true, true, true); return true }
                return false
            }
            val current: View? = findFocus()
            if (visible && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && current != null && current.parent is ViewGroup) {
                val row = current.parent as ViewGroup
                if (current.tag is String && current.tag.toString().startsWith("keyboard:") &&
                        row.getChildAt(row.childCount - 1) === current) {
                    var result: View? = if (lastResult.isEmpty()) null else results.findViewWithTag(lastResult)
                    if (result == null) result = firstFocusable(grid!!)
                    if (result != null && result !== results) { result.requestFocus(); return true }
                }
            }
            if (code != android.view.KeyEvent.KEYCODE_DPAD_LEFT || visible) return false
            if (results.isFocused) { keyboard(true, true, true); return true }
            val focused: View? = findFocus(); val cell: View? = if (focused == null) null else grid!!.findContainingItemView(focused)
            if (cell == null) return false
            val index = grid!!.getChildAdapterPosition(cell); val columns = (grid!!.layoutManager as GridLayoutManager).spanCount
            if (index < 0 || index % columns != 0) return false
            keyboard(true, true, true); return true
        }
        fun keyboard(show: Boolean, animate: Boolean, focusAfter: Boolean) {
            if (visible == show) { if (show && focusAfter && !changing) focusKeyboard(); return }
            // Reversing a gesture starts from its painted position, rather than completing the old direction first.
            cancelMotion(); reflow = null; finishing = null
            visible = show; item.keyboardVisible = show
            listener.searchKeyboardChanged(show)
            val mine = ++transition; changing = true
            // Focus notifications can arrive during RecyclerView layout. Mutate its span count on the next frame.
            post {
                if (mine != transition || searchPane !== this || !isAttachedToWindow) return@post
                val focused: View? = findFocus()
                val target: String = if (focused != null && focused.tag is String && !focused.tag.toString().startsWith("keyboard:") &&
                        !focused.tag.toString().equals("search-results"))
                    focused.tag as String else lastResult
                val layout: GridLayoutManager? = if (grid == null) null else grid!!.layoutManager as GridLayoutManager
                var cell: View? = if (focused == null || grid == null) null else grid!!.findContainingItemView(focused)
                if (cell == null && grid != null && !target.isEmpty()) { val old: View? = grid!!.findViewWithTag(target); if (old != null) cell = grid!!.findContainingItemView(old) }
                val anchor = if (cell == null) (if (layout == null) 0 else Math.max(0, layout.findFirstVisibleItemPosition())) else grid!!.getChildAdapterPosition(cell)
                val offset = if (cell == null) 0 else cell.top - grid!!.paddingTop
                val motion = animate && isLaidOut && android.animation.ValueAnimator.areAnimatorsEnabled()
                val done = Runnable { finish(mine, show, focusAfter, target, anchor) }
                finishing = done
                // A focused result keeps focus while the grid fades, so OK still opens it. Focus waits on the column
                // only when it moves into the pane afterwards; only then does the column clip, as clipping would cut
                // the focused card's lift at the column's edge.
                val keep = !focusAfter && focused != null && grid != null && grid!!.findContainingItemView(focused) != null
                if (!keep && (focusAfter || cell != null)) results.requestFocus()
                editor.measure(View.MeasureSpec.makeMeasureSpec(width - paddingLeft - paddingRight, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(height - paddingTop - paddingBottom, View.MeasureSpec.EXACTLY))
                val editorWidth = editor.measuredWidth
                val hidden = (-editorWidth - paddingLeft).toFloat()
                if (editor.visibility != View.VISIBLE) {
                    // Keep full-width results in place while the keyboard starts arriving above them.
                    editor.translationX = hidden; editor.alpha = 0f; editor.visibility = View.VISIBLE
                    resultMargin(-editorWidth)
                }
                editor.translationZ = dp(8).toFloat()
                editor.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                val margin = if (show) 0 else -editorWidth
                val needsReflow = (results.layoutParams as LinearLayout.LayoutParams).leftMargin != margin ||
                        layout != null && layout.spanCount != (if (show) SEARCH_COLUMNS else 6)
                // Reflow only while the results are invisible. Per-card bounds/slide transitions cross row
                // boundaries and can match recycled holders to a different film, painting them above other rows.
                reflow = Runnable {
                    reflow = null
                    clipColumn(!keep)
                    resultMargin(margin)
                    if (layout != null) {
                        val columns = if (show) SEARCH_COLUMNS else 6
                        layout.spanCount = columns
                        if (grid!!.adapter is TileAdapter) (grid!!.adapter as TileAdapter).columns(grid!!, columns)
                        else grid!!.adapter = skeletonCards(columns * 2, columns)
                        layout.scrollToPositionWithOffset(anchor, offset)
                    }
                }
                if (!motion) { reflow!!.run(); post(done); return@post }
                val ended = booleanArrayOf(false, false)
                val keyboardDone = Runnable { ended[0] = true; if (ended[1]) done.run() }
                val gridDone = Runnable { ended[1] = true; if (ended[0]) done.run() }
                val duration: Long = Math.max(180, Math.round(360 * Math.abs((if (show) 0f else hidden) - editor.translationX) / Math.max(1f, -hidden))).toLong()
                val keyboard = android.animation.AnimatorSet()
                keyboard.playTogether(android.animation.ObjectAnimator.ofFloat(editor, View.TRANSLATION_X, if (show) 0f else hidden),
                        android.animation.ObjectAnimator.ofFloat(editor, View.ALPHA, if (show) 1f else 0f))
                keyboard.duration = duration
                keyboard.interpolator = android.view.animation.PathInterpolator(.32f, 0f, .16f, 1f)
                keyboard.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { keyboardDone.run() }
                })
                editorAnimation = keyboard; keyboard.start()
                if (!needsReflow) {
                    reflow = null
                    fadeResults(1f, duration, gridDone)
                    return@post
                }
                val out = android.animation.ObjectAnimator.ofFloat(results, View.ALPHA, 0f)
                out.duration = Math.max(1, Math.round(140 * results.alpha)).toLong()
                out.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                out.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (mine != transition || !changing || !isAttachedToWindow) return
                        reflow!!.run()
                        val observer = viewTreeObserver
                        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                                if (mine != transition || !changing || !isAttachedToWindow) return true
                                if (!show) reveal(result(target, anchor), true)
                                fadeResults(1f, 220, gridDone)
                                return true
                            }
                        })
                    }
                })
                modeAnimation = out; out.start()
            }
        }
        /**
         * Keeps the moving grid off the keyboard's side only. The grid runs from the top to the bottom of the screen
         * beyond the column, so clipping to the column would cut its rows there for the length of the motion.
         */
        private fun clipColumn(clip: Boolean) {
            results.clipBounds = if (clip) Rect(0, -Int.MAX_VALUE / 4, Int.MAX_VALUE / 4, Int.MAX_VALUE / 4) else null
        }
        private fun resultMargin(value: Int) {
            val params = results.layoutParams as LinearLayout.LayoutParams
            if (params.leftMargin == value) return
            params.leftMargin = value; results.layoutParams = params
        }
        private fun fadeResults(alpha: Float, duration: Long, done: Runnable) {
            val fade = android.animation.ObjectAnimator.ofFloat(results, View.ALPHA, alpha)
            fade.duration = duration; fade.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            fade.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { done.run() }
            })
            modeAnimation = fade; fade.start()
        }
        private fun cancelMotion() {
            if (modeAnimation != null) { modeAnimation!!.removeAllListeners(); modeAnimation!!.cancel(); modeAnimation = null }
            if (editorAnimation != null) { editorAnimation!!.removeAllListeners(); editorAnimation!!.cancel(); editorAnimation = null }
        }
        /** Leaving mid-change: put every card where the change is taking it, so the saved offset is one the grid rests at. */
        fun complete() {
            if (!changing) return
            cancelMotion()
            if (reflow != null) reflow!!.run()
            if (finishing != null) finishing!!.run()
        }
        /** Scrolls the grid as focusing `card` would; `immediate` jumps instead of scrolling smoothly. */
        private fun reveal(card: View?, immediate: Boolean) {
            val cell: View? = if (card == null || grid == null) null else grid!!.findContainingItemView(card)
            if (cell == null) return
            val bounds = Rect(0, 0, card!!.width, card.height)
            if (card !== cell) (cell as ViewGroup).offsetDescendantRectToMyCoords(card, bounds)
            grid!!.layoutManager!!.requestChildRectangleOnScreen(grid!!, cell, bounds, immediate)
        }
        /** The card a hidden keyboard hands focus back to. */
        private fun result(target: String, anchor: Int): View? {
            var card: View? = if (target.isEmpty()) null else results.findViewWithTag(target)
            if (card == null && grid != null) {
                val holder: RecyclerView.ViewHolder? = grid!!.findViewHolderForAdapterPosition(anchor)
                if (holder != null) card = firstFocusable(holder.itemView)
            }
            return card
        }
        fun finish(mine: Int, show: Boolean, focusAfter: Boolean, target: String, anchor: Int) {
            if (mine != transition || searchPane !== this || !isAttachedToWindow) return
            editor.visibility = if (show) View.VISIBLE else View.GONE; resultMargin(0)
            results.alpha = 1f; editor.translationX = 0f; editor.alpha = 1f; editor.translationZ = 0f
            editor.descendantFocusability = if (show) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
            clipColumn(false); changing = false; finishing = null; modeAnimation = null; editorAnimation = null
            if (!hasFocus()) return // Respect a newer move to Back during the animation.
            // A card that kept focus is in view already; one reached during the motion, while the grid could not
            // scroll, is brought into view now.
            if (!show && grid != null && grid!!.findContainingItemView(findFocus()) != null) { reveal(findFocus(), false); return }
            if (show && focusAfter) { focusKeyboard(); return }
            if (show) return
            if (grid == null) {
                val retry: View? = results.findViewWithTag("reload-search"); if (retry != null) retry.requestFocus()
                return
            }
            val restored = result(target, anchor)
            if (restored != null) restored.requestFocus()
        }
        fun focusKeyboard() {
            var key: View? = editor.findViewWithTag(lastKeyboard)
            if (key == null) key = firstFocusable(editor)
            if (key != null) key.requestFocus()
        }
    }
    /** Search's results column, rebuilt alone as the query changes so the keyboard (and its focus) stays as it is. */
    private var resultsGeneration = 0
    private fun results(results: LinearLayout, item: Item) {
        val mine = ++resultsGeneration
        resultsNow(results, item, !item.loading)
        if (item.loading) results.postDelayed({
            if (mine == resultsGeneration && results === searchResults && results.isAttachedToWindow && item.loading)
                resultsNow(results, item, true)
        }, 160)
    }
    /**
     * Lays the faded results viewport out from the top to the bottom of the screen while it keeps its place in the
     * page's layout: the first row still starts below the toolbar and the last one clears the TV's safe area, and
     * rows scroll on beneath both and fade out instead of being cut off inside the page.
     */
    private class ScreenTall(private val viewport: View, private val grid: RecyclerView) : FrameLayout(viewport.context) {
        private val at = IntArray(2)
        private val screen = IntArray(2)
        private var above = 0
        private var below = 0
        // The page list moves its rows after laying them out, so the distances are checked again before each frame.
        private val measure = android.view.ViewTreeObserver.OnPreDrawListener { !reach() }
        init {
            clipChildren = false; clipToPadding = false
            addView(viewport, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        /** Updates the distances to the screen edges; true when they changed and a new layout is on its way. */
        private fun reach(): Boolean {
            val page: View? = rootView.findViewById(R.id.browse_content)
            if (page == null || !isLaidOut) return false
            getLocationInWindow(at); page.getLocationInWindow(screen)
            val nextAbove = Math.max(0, at[1] - screen[1])
            val nextBelow = Math.max(0, screen[1] + page.height - at[1] - height)
            if (nextAbove == above && nextBelow == below) return false
            // The viewport grows upwards by the change; moving its rows down by as much keeps them where they are.
            grid.offsetChildrenVertical(nextAbove - above)
            above = nextAbove; below = nextBelow
            requestLayout()
            return true
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(measure) }
        override fun onDetachedFromWindow() { viewTreeObserver.removeOnPreDrawListener(measure); super.onDetachedFromWindow() }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val top = Tv.dp(context, 18f) + above; val bottom = Tv.dp(context, 36f)
            if (grid.paddingTop != top || grid.paddingBottom != bottom)
                grid.setPadding(grid.paddingLeft, top, grid.paddingRight, bottom)
            val height = this.height + above + below
            viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            viewport.layout(0, -above, width, height - above)
        }
    }
    private fun resultsNow(results: LinearLayout, item: Item, skeletons: Boolean) {
        if (searchPane != null) searchPane!!.complete()
        if (searchPane != null && searchPane!!.grid != null) {
            val previous: RecyclerView = searchPane!!.grid!!
            val key = scrollKeys.get(previous)
            if (key != null) childScroll.put(key, previous.layoutManager!!.onSaveInstanceState())
            searchPane!!.grid = null
        }
        results.removeAllViews()
        // A soft tap when titles appear where there were none, not on every keystroke that keeps them.
        if (!item.loading) {
            if (!item.tiles.isEmpty() && shownResults == 0) Sounds.play(Sounds.Sound.RESULTS)
            shownResults = item.tiles.size
        }
        // No heading: the grid speaks for itself. A provider problem gets one quiet line.
        if (!item.meta.isEmpty()) {
            val warning = Tv.lines(Tv.text(context, item.meta, 13f, R.color.tv_warning), 3)
            warning.setPadding(0, dp(6), 0, dp(4)); results.addView(warning, wrap())
        }
        if (!item.actions.isEmpty()) {
            val actions = LinearLayout(context); actions.gravity = Gravity.START
            for (action in item.actions) actions.addView(Tv.button(context, action, this::focused))
            val spacing = LinearLayout.LayoutParams(-1, -2); spacing.topMargin = dp(8); spacing.bottomMargin = dp(8)
            results.addView(actions, spacing)
        }

        if (item.loading && skeletons || !item.tiles.isEmpty()) {
            val columns = if (searchPane != null && !searchPane!!.visible) 6 else SEARCH_COLUMNS
            val list = RecyclerView(context)
            list.layoutManager = GridLayoutManager(context, columns); list.itemAnimator = null
            if (searchPane != null) searchPane!!.grid = list
            // The bottom padding keeps a focused row inside the TV's safe area while unfocused rows run to the edge.
            list.setPadding(0, dp(18), 0, dp(28))
            list.isVerticalScrollBarEnabled = false; list.isNestedScrollingEnabled = false
            list.isVerticalFadingEdgeEnabled = false
            if (item.loading && item.tiles.isEmpty()) list.adapter = skeletonCards(columns * 2, columns)
            else list.adapter = TileAdapter(item.tiles, Item.Tile.Style.POSTER, 0, 14, true, columns, 18)
            // Placeholder rows have no meaningful viewport and must not replace the selected films' saved offset.
            if (list.adapter is TileAdapter) retainScroll(list, "search:" + item.value)
            var selected = -1
            for (i in 0 until item.tiles.size) if (item.tiles.get(i).key.equals(restore)) selected = i
            // After recreation only the route's selection survives. Attach its row before focus restoration;
            // scrolling the outer page cannot reach an offscreen card in this nested grid.
            if (selected >= 0 && !childScroll.containsKey("search:" + item.value)) list.scrollToPosition(selected)
            // The grid scrolls over the whole screen height and fades out at its edges rather than being cut off
            // inside the page; the edge columns keep room to lift.
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            params.rightMargin = -dp(12)
            params.leftMargin = params.rightMargin
            results.addView(ScreenTall(Tv.faded(list, dp(12), 96, 48), list), params)
            val restoringSelection = selected >= 0
            if (!restoringSelection && !item.loading && searchPane != null && !searchPane!!.visible && results.isFocused)
                list.post {
                    // A newer provider update can replace this grid before its posted focus runs.
                    if (!list.isAttachedToWindow || searchPane == null || searchPane!!.grid !== list || !results.isFocused) return@post
                    val first = firstFocusable(list); if (first != null) first.requestFocus()
                }
        } else {
            // Centred in the visible part of the column (it runs 52 dp past the screen): a quiet prompt before a
            // search, or "No results" after one.
            val empty = LinearLayout(context); empty.orientation = LinearLayout.VERTICAL; empty.gravity = Gravity.CENTER
            empty.setPadding(0, 0, 0, dp(12 + 40))
            val message = Tv.text(context, if (!item.detail.isEmpty()) item.detail else if (item.loading) "Searching…" else "Search films and series", 18f, R.color.tv_text_secondary)
            message.gravity = Gravity.CENTER
            empty.addView(message, wrap())
            results.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /**
     * Settings as a split view: categories on the left; the focused category's rows on the right, changing as focus
     * moves down the categories. Right from a category goes to the first row of its pane; Left from a pane returns
     * to the selected category, which stays lit while its pane is in use.
     */
    /** The keyboard page survives the re-render each keystroke causes, so symbols can be typed one after another. */
    private var keyboardPage = 0

    /**
     * Fill short character rows with Space/Delete when Space can span at least two cells. Otherwise spread
     * the four/five trailing letters evenly and give the commands their own row. Every row fills both edges.
     */
    private fun keyboardKeys(item: Item): LinearLayout {
        val keys = LinearLayout(context); keys.orientation = LinearLayout.VERTICAL; keys.tag = "keyboard-keys"
        keys.clipChildren = false; keys.clipToPadding = false
        val query = StringBuilder(item.value)
        keyboardQuery = query
        val rebuild = arrayOfNulls<Runnable>(1)
        rebuild[0] = Runnable {
            keys.removeAllViews()
            val characters = keyboardCharacters(item, keyboardPage)
            var row: LinearLayout? = null
            for (i in 0 until characters.length) {
                if (i % KEY_COLUMNS == 0) row = keyboardRow(keys)
                val character = characters.substring(i, i + 1)
                addKeyboardButton(row!!, item, character, "Type " + character, 1, Runnable { type(item, query, character) })
            }
            val used = characters.length % KEY_COLUMNS
            if (used >= KEY_COLUMNS - 2) {
                // Four/five letters leave too little room for a readable Space capsule and Delete.
                // Spread their hit cells evenly; the focused letter still gets a centred circle.
                for (i in 0 until row!!.childCount) {
                    val params = row.getChildAt(i).layoutParams as LinearLayout.LayoutParams
                    params.width = 0; params.weight = 1f
                }
            }
            var remaining = KEY_COLUMNS - used
            if (used == 0 || remaining < 3) { row = keyboardRow(keys); remaining = KEY_COLUMNS }
            addKeyboardButton(row!!, item, "Space", "Insert a space", (remaining + 1) / 2, Runnable { type(item, query, " ") })
            addKeyboardButton(row, item, "⌫", "Delete last character", remaining / 2, Runnable {
                if (query.length > 0) query.deleteCharAt(query.length - 1)
                typed(item, query)
            })
            row = keyboardRow(keys)
            row.tag = "keyboard-actions"
            (row.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
            for (other in 0 until KEY_PAGES.size) if (other != keyboardPage) {
                val target = other
                val name = if (other == 0) KeyboardLanguages.alphabet(item.keyboardLanguage).substring(0, 3) else KEY_PAGE_NAMES[other]
                addKeyboardButton(row, item, name, "Show " + name + " keys", 2, Runnable {
                    keyboardPage = target; rebuild[0]!!.run()
                    keys.post {
                        val first: View? = keys.findViewWithTag(item.key + ":" + keyboardCharacters(item, target)[0])
                        if (first != null) first.requestFocus()
                    }
                })
            }
            addKeyboardButton(row, item, "Clear", "Clear search", 2, Runnable {
                query.setLength(0)
                typed(item, query)
            })
            row = keyboardRow(keys)
            addKeyboardButton(row, item, KeyboardLanguages.name(item.keyboardLanguage), "Change keyboard language", 6, Runnable {
                if (item.keyboardLanguages.size == 1) { val action = item.action; if (action != null) action.run(); return@Runnable }
                val next = (item.keyboardLanguages.indexOf(item.keyboardLanguage) + 1) % item.keyboardLanguages.size
                item.keyboardLanguage = item.keyboardLanguages.get(next); keyboardPage = 0
                val changed = item.keyboardLanguageChanged
                if (changed != null) changed.accept(item.keyboardLanguage)
                rebuild[0]!!.run(); keys.post { val target: View? = keys.findViewWithTag(item.key + ":Language"); if (target != null) target.requestFocus() }
            })
        }
        rebuild[0]!!.run()
        return keys
    }
    private fun keyboardWidth(): Int { return KEY_COLUMNS * dp(KEY) + (KEY_COLUMNS - 1) * dp(KEY_GAP) }
    private fun keyboardRow(keys: LinearLayout): LinearLayout {
        val row = LinearLayout(context); row.clipChildren = false; row.clipToPadding = false
        // Align the key backgrounds, not the baselines of the smaller Space/Delete labels.
        row.isBaselineAligned = false
        val params = LinearLayout.LayoutParams(keyboardWidth(), dp(KEY))
        if (keys.childCount > 0) params.topMargin = dp(KEY_GAP)
        keys.addView(row, params)
        return row
    }
    private fun type(item: Item, query: StringBuilder, character: String) {
        if (query.length < 120) query.append(character)
        typed(item, query)
    }
    private fun typed(item: Item, query: StringBuilder) {
        if (searchPane != null) searchPane!!.query(query.toString())
        val input = item.keyboardInput
        if (input != null) input.accept(query.toString())
    }
    private fun addKeyboardButton(row: LinearLayout, item: Item, label: String, description: String, cells: Int, action: Runnable) {
        val key: View
        if (description.equals("Delete last character")) {
            val button = FrameLayout(context)
            button.addView(Tv.icon(context, R.drawable.ic_tv_backspace, 16, R.color.tv_on_glass),
                    FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            key = button
        } else {
            val text = Tv.heading(context, label, if (label.length == 1) 16f else 12f, R.color.tv_on_glass)
            text.gravity = Gravity.CENTER; text.setSingleLine(true); text.includeFontPadding = false
            text.setPadding(0, 0, 0, 0)
            if (description.equals("Change keyboard language")) {
                val glyph = context.getDrawable(R.drawable.ic_tv_language)!!.mutate()
                glyph.setTintList(context.getColorStateList(R.color.tv_on_glass)); glyph.setBounds(0, 0, dp(14), dp(14))
                text.setCompoundDrawables(glyph, null, null, null); text.compoundDrawablePadding = dp(6); text.setPadding(dp(12), 0, dp(10), 0)
            }
            key = text
        }
        key.contentDescription = description
        val character = description.startsWith("Type ")
        key.setBackgroundResource(if (character) R.drawable.tv_keyboard_key else R.drawable.tv_keyboard_action)
        val params = LinearLayout.LayoutParams(cells * dp(KEY) + (cells - 1) * dp(KEY_GAP), dp(KEY))
        if (row.childCount > 0) params.marginStart = dp(KEY_GAP)
        row.addView(key, params)
        // Letters and Space tap; Delete and Clear fall. Page switches use the plain select.
        val erase = description.startsWith("Delete") || description.startsWith("Clear")
        val typed = cells == 1 || label.equals("Space")
        val press: Runnable = if (erase) Runnable { Sounds.play(Sounds.Sound.DELETE); action.run() }
                else if (typed) Runnable { Sounds.at(Sounds.Sound.KEY, key); action.run() } else action
        // Add the same drawn width as a round key; wide capsules need a smaller percentage.
        // A letter's visible platter stays KEY wide even when its tail-row hit cell is wider.
        val lift = 1f + (Tv.LIFT_ICON - 1f) * dp(KEY) / (if (character) dp(KEY) else params.width)
        Tv.control(key, item.key + ":" + (if (description.equals("Change keyboard language")) "Language" else label), press, this::focused, lift, false)
        Tv.hover(key, java.util.function.DoubleSupplier { lift.toDouble() })
        if (key.tag != null && key.tag.equals(restore)) restoreLater(key, key.tag.toString())
    }

    private fun loading(): View {
        val root = LinearLayout(context); root.orientation = LinearLayout.VERTICAL; root.gravity = Gravity.CENTER
        val spinner = ProgressBar(context)
        spinner.indeterminateDrawable = context.getDrawable(R.drawable.tv_loading_indicator)
        spinner.contentDescription = "Loading"
        root.addView(spinner, LinearLayout.LayoutParams(dp(44), dp(44)))
        return root
    }

    /** Scrolls so a focused child keeps some room around it, rather than stopping at the very edge. */
    class Layout(context: Context, orientation: Int, marginDp: Int) : LinearLayoutManager(context, orientation, false) {
        private val margin: Int = Tv.dp(context, marginDp.toFloat())
        private var owner: RecyclerView? = null
        override fun onAttachedToWindow(view: RecyclerView) { super.onAttachedToWindow(view); owner = view }
        override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
            super.onDetachedFromWindow(view, recycler); owner = null
        }
        private var navigationTarget: View? = null
        fun navigationTarget(target: View?) { navigationTarget = target }
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
            if (direction == View.FOCUS_UP && orientation == LinearLayoutManager.HORIZONTAL &&
                    navigationTarget != null && navigationTarget!!.isShown) return navigationTarget
            if ((direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) && orientation == LinearLayoutManager.VERTICAL) {
                var filmRow = false
                var child: View? = focused
                while (child != null && child !== owner && child.parent is View) {
                    if (child.parent is GridRow || child.parent is RecyclerView &&
                            (child.parent as RecyclerView).adapter is TileAdapter) { filmRow = true; break }
                    child = child.parent as View
                }
                if (owner != null && filmRow) {
                    val within: View? = FocusFinder.getInstance().findNextFocus(owner!!, focused, direction)
                    if (within != null) return within
                    if (owner!!.canScrollVertically(if (direction == View.FOCUS_UP) -1 else 1)) return null
                }
                val next: View? = FocusFinder.getInstance().findNextFocus(focused.rootView as ViewGroup, focused, direction)
                if (next != null && findContainingItemView(next) == null) {
                    if (direction != View.FOCUS_UP) return focused
                    val back: View? = focused.rootView.findViewById(R.id.page_back)
                    if (back != null && back.isShown) return back
                    val refresh: View? = focused.rootView.findViewById(R.id.home_refresh)
                    return if (refresh != null && refresh.isShown) refresh else focused
                }
                return null
            }
            if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return null
            var child: View? = focused
            while (child != null && child.parent is View) {
                if (child.parent is GridRow) {
                    val row = child.parent as GridRow
                    val index = row.indexOfChild(child) + (if (direction == View.FOCUS_LEFT) -1 else 1)
                    if (index >= 0 && index < row.childCount) {
                        val target = firstFocusable(row.getChildAt(index))
                        if (target != null) return target
                    }
                    return focused
                }
                child = child.parent as View
            }
            val item: View = findContainingItemView(focused) ?: return null
            if (orientation == LinearLayoutManager.HORIZONTAL) {
                val forward = (direction == View.FOCUS_RIGHT) != (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                val position = getPosition(item)
                return if ((forward && position == itemCount - 1) || (!forward && position == 0)) focused else null
            }
            val next: View? = FocusFinder.getInstance().findNextFocus(focused.rootView as ViewGroup, focused, direction)
            return if (next != null && findContainingItemView(next) == null) focused else null
        }

    }

    companion object {
        /** The Home spotlight keeps a stable artwork area above the shelves. */
        const val SPOTLIGHT_HEIGHT = 248
        private const val POSTER_WIDTH = 120
        private const val LANDSCAPE_WIDTH = 216
        private const val GAP = 20
        private const val SEARCH_COLUMNS = 4
        /** Where a list row's label starts inside its platter. */
        private const val ROW_INSET = PageRows.ROW_INSET
        /** Height to width of a category block. */
        private const val BLOCK_RATIO = 0.5f
        /** Letters, digits/punctuation and language-specific accents, normally six characters to a row. */
        private val KEY_PAGES = arrayOf(
                "abcdefghijklmnopqrstuvwxyz",
                "1234567890-':&!?.,()#+@/\"_",
                "àáâäãåçèéêëíîïñóôöõøùúûüßæ")
        private val KEY_PAGE_NAMES = arrayOf("abc", "123", "àéü")
        private const val KEY_COLUMNS = 6
        private const val KEY = 34
        private const val KEY_GAP = 4

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
        private fun contains(item: Item, key: String): Boolean {
            if (item.key.equals(key)) return true
            if (item.kind == Item.Kind.KEYBOARD && key.startsWith(item.key + ":")) return true
            for (t in item.tiles) if (t.key.equals(key)) return true
            for (a in item.actions) if (a.key.equals(key)) return true
            val panes = item.panes
            if (panes is LazyPanes) return panes.rowKeys.contains(key)
            if (panes != null) for (pane in panes.values)
                for (child in pane) if (contains(child, key)) return true
            return false
        }
        private fun wrap(): LinearLayout.LayoutParams { return LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        private fun fill(): FrameLayout.LayoutParams { return FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }

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
        private fun metaLine(title: Title, year: Boolean): String {
            val parts: MutableList<String> = ArrayList()
            if (year && !title.releaseInfo.isEmpty()) parts.add(title.releaseInfo)
            if (!title.genres.isEmpty()) parts.add(title.genres.subList(0, Math.min(3, title.genres.size)).joinToString(", "))
            if (!title.rating.isEmpty()) parts.add("★ " + title.rating)
            return parts.joinToString("  ·  ")
        }
        private fun firstFocusable(view: View): View? {
            if (view.visibility != View.VISIBLE) return null
            if (view.isFocusable && view.tag is String) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                val found = firstFocusable(view.getChildAt(i))
                if (found != null) return found
            }
            return null
        }
        private fun keyboardCharacters(item: Item, page: Int): String {
            return if (page == 0) KeyboardLanguages.alphabet(item.keyboardLanguage)
                    else if (page == 2) KeyboardLanguages.accents(item.keyboardLanguage) else KEY_PAGES[page]
        }
    }
}
