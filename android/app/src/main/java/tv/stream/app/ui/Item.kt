package tv.stream.app.ui

import androidx.annotation.DrawableRes
import java.util.Collections
import java.util.function.Consumer
import tv.stream.app.addon.Title

/** One block of a page. Screens build these on workers; [PageAdapter] turns them into views. */
class Item private constructor(@JvmField val kind: Kind, @JvmField val key: String) {
    enum class Kind { SPOTLIGHT, DETAILS, INTRO, TITLE, HEADER, SHELF, GRID, ACTIONS, CHIPS, LIST, SOURCE, FIELD, KEYBOARD, SPLIT, SKELETON, MESSAGE, NOTICE, TEXT, LOADING, SUBTITLE_PREVIEW, CATALOGUE_HEADER, PAGINATION, EPISODES, SERIES_HEADER }

    /** A poster, landscape thumbnail or catalogue card. */
    class Tile(
        @JvmField val key: String, @JvmField val style: Style, @JvmField val title: String, @JvmField val subtitle: String,
        @JvmField val image: String?,
        /** Shown in the Home spotlight and backdrop while this tile has focus. */
        @JvmField val media: Title?,
        @JvmField val action: Runnable
    ) {
        enum class Style { POSTER, LANDSCAPE }
        /** 0–1 playback progress, or below zero for none. */
        @JvmField var progress: Float = -1f
        @JvmField var episodeNumber: Int = -1
        @JvmField var synopsis: String = ""
        @JvmField var releaseDate: String = ""
        @DrawableRes @JvmField var badge: Int = 0
        /** A short word over the artwork's corner, such as "New". */
        @JvmField var flag: String = ""
        /** What the Home spotlight leads its facts with while this tile is featured ("New season · S2 E1"). */
        @JvmField var headline: String = ""
        /** The accessible label when it should differ from "title. subtitle" (a catalogue card is named by its catalogue). */
        @JvmField var description: String? = null

        fun progress(value: Float): Tile { progress = value; return this }
        fun badge(@DrawableRes icon: Int): Tile { badge = icon; return this }
        fun flag(text: String): Tile { flag = text; return this }
        fun headline(text: String): Tile { headline = text; return this }
        fun description(value: String?): Tile { description = value; return this }
        fun episode(number: Int, overview: String): Tile { episodeNumber = number; synopsis = overview; return this }
        fun released(date: String): Tile { releaseDate = date; return this }
        /** A plain block with its title inside and no artwork or caption (a Browse category). */
        @JvmField var block: Boolean = false
        @JvmField var colorIndex: Int = 0
        fun block(): Tile { block = true; return this }
    }

    /** A focusable button. Icon-only buttons are described by their label. */
    class Action(@JvmField val key: String, @JvmField val style: Style, @JvmField val label: String, @JvmField val action: Runnable) {
        enum class Style { PRIMARY, SECONDARY, ICON, CHIP }
        @DrawableRes @JvmField var icon: Int = 0
        @JvmField var detail: String = ""
        /** The visible label when it differs from the accessible one ("Favourite" for "Add favourite"). */
        @JvmField var text: String? = null
        @JvmField var selected: Boolean = false

        fun icon(@DrawableRes value: Int): Action { icon = value; return this }
        fun detail(value: String): Action { detail = value; return this }
        fun text(value: String?): Action { text = value; return this }
        fun shown(): String { return if (text != null) text!! else label }
        fun selected(value: Boolean): Action { selected = value; return this }
    }

    @JvmField var title: String = ""
    @JvmField var detail: String = ""
    @JvmField var value: String = ""
    @JvmField var meta: String = ""
    @JvmField var media: Title? = null
    @JvmField var action: Runnable? = null
    @DrawableRes @JvmField var icon: Int = 0
    /** Marks unusable sources and other rows that stay selectable but are not the expected choice. */
    @JvmField var muted: Boolean = false
    /** A source row's status icon: 0 for the default (a check, or info when muted), -1 for none. */
    @JvmField var statusIcon: Int = 0
    /** Centres a row of actions, as on onboarding. */
    @JvmField var centered: Boolean = false
    @JvmField var style: Tile.Style = Tile.Style.POSTER
    @JvmField var tiles: List<Tile> = Collections.emptyList()
    @JvmField var actions: List<Action> = Collections.emptyList()
    @JvmField var keyboardInput: Consumer<String>? = null
    @JvmField var keyboardLanguages: List<String> = Collections.singletonList("en")
    @JvmField var keyboardLanguage: String = "en"
    @JvmField var keyboardVisible: Boolean = true
    @JvmField var keyboardLanguageChanged: Consumer<String>? = null
    @JvmField var loading: Boolean = false

    fun centered(): Item { centered = true; return this }
    fun status(icon: Int): Item { statusIcon = icon; return this }

    @JvmField var children: List<Item>? = null
    @JvmField var panes: Map<String, List<Item>>? = null
    @JvmField var playerPreferences: android.content.SharedPreferences? = null

    companion object {
        @JvmStatic fun spotlight(first: Title?): Item { val i = Item(Kind.SPOTLIGHT, "spotlight"); i.media = first; return i }
        @JvmStatic fun details(title: Title?, note: String): Item { val i = Item(Kind.DETAILS, "details"); i.media = title; i.detail = note; return i }
        @JvmStatic fun seriesHeader(title: Title?): Item { val i = Item(Kind.SERIES_HEADER, "series-header"); i.media = title; return i }
        /** Seasons select on focus; each pane is its own episode shelf, with independent horizontal scroll. */
        @JvmStatic fun episodes(seasons: List<Action>, panes: Map<String, List<Item>>, selected: String, select: Consumer<String>?): Item {
            val i = Item(Kind.EPISODES, "episode-browser"); i.actions = seasons; i.panes = panes
            i.value = selected; i.keyboardInput = select; return i
        }
        @JvmStatic fun intro(overline: String, title: String, detail: String): Item {
            val i = Item(Kind.INTRO, "intro:" + title); i.meta = overline; i.title = title; i.detail = detail; return i
        }
        /** A pushed page's large title, scrolling with its content. */
        @JvmStatic fun pageTitle(title: String): Item { val i = Item(Kind.TITLE, "title:" + title); i.title = title; return i }
        @JvmStatic fun header(title: String): Item { val i = Item(Kind.HEADER, "header:" + title); i.title = title; return i }
        @JvmStatic fun header(title: String, detail: String): Item { val i = header(title); i.detail = detail; return i }
        @JvmStatic fun catalogueHeader(title: String, detail: String): Item {
            val i = Item(Kind.CATALOGUE_HEADER, "catalogue-header"); i.title = title; i.detail = detail
            return i
        }
        @JvmStatic fun pagination(key: String, status: String, actions: List<Action>): Item {
            val i = Item(Kind.PAGINATION, key); i.title = status; i.actions = actions; return i
        }
        @JvmStatic fun shelf(key: String, title: String, detail: String, style: Tile.Style, tiles: List<Tile>): Item {
            val i = Item(Kind.SHELF, key); i.title = title; i.detail = detail; i.style = style; i.tiles = tiles; return i
        }
        /** Splits tiles into rows of `columns`; every row is a separate item so long grids recycle. */
        @JvmStatic fun grid(key: String, style: Tile.Style, columns: Int, tiles: List<Tile>): MutableList<Item> {
            val rows: MutableList<Item> = ArrayList()
            var start = 0
            while (start < tiles.size) {
                val i = Item(Kind.GRID, key + ":" + start); i.style = style; i.value = Integer.toString(columns)
                i.tiles = tiles.subList(start, Math.min(tiles.size, start + columns)); rows.add(i)
                start += columns
            }
            return rows
        }
        @JvmStatic fun actions(key: String, vararg actions: Action): Item { val i = Item(Kind.ACTIONS, key); i.actions = list(actions); return i }
        @JvmStatic fun actions(key: String, actions: List<Action>): Item { val i = Item(Kind.ACTIONS, key); i.actions = actions; return i }
        @JvmStatic fun chips(key: String, title: String, actions: List<Action>): Item { val i = Item(Kind.CHIPS, key); i.title = title; i.actions = actions; return i }
        @JvmStatic fun row(key: String, @DrawableRes icon: Int, title: String, value: String, action: Runnable?): Item {
            val i = Item(Kind.LIST, key); i.icon = icon; i.title = title; i.value = value; i.action = action; return i
        }
        @JvmStatic fun source(key: String, title: String, meta: String, detail: String, status: String, usable: Boolean, action: Runnable?): Item {
            val i = Item(Kind.SOURCE, key); i.title = title; i.meta = meta; i.detail = detail; i.value = status
            i.muted = !usable; i.action = action; return i
        }
        @JvmStatic fun field(key: String, label: String, value: String, action: Runnable?): Item {
            val i = Item(Kind.FIELD, key); i.title = label; i.value = value; i.action = action; return i
        }
        /**
         * Categories on the left, the focused one's rows on the right (Settings). `categories` are rows keyed like
         * `panes`; `selected` is the category shown first, and `select` hears each change.
         */
        @JvmStatic fun split(key: String, categories: List<Item>, panes: Map<String, List<Item>>, selected: String, select: Consumer<String>?): Item {
            val i = Item(Kind.SPLIT, key); i.children = categories; i.panes = panes; i.value = selected; i.keyboardInput = select; return i
        }
        @JvmStatic fun subtitlePreview(key: String, prefs: android.content.SharedPreferences?): Item {
            val i = Item(Kind.SUBTITLE_PREVIEW, key)
            i.playerPreferences = prefs
            return i
        }
        @JvmStatic fun keyboard(key: String, value: String, input: Consumer<String>?): Item {
            val i = Item(Kind.KEYBOARD, key); i.value = value; i.keyboardInput = input; return i
        }
        @JvmStatic fun skeleton(screen: String): Item { val i = Item(Kind.SKELETON, "skeleton:" + screen); i.value = screen; return i }
        /** A centred empty, error or loading state with its next steps. */
        @JvmStatic fun message(@DrawableRes icon: Int, title: String, detail: String, vararg actions: Action): Item {
            val i = Item(Kind.MESSAGE, "message:" + title); i.icon = icon; i.title = title; i.detail = detail; i.actions = list(actions); return i
        }
        /** An inline explanation inside a page that still has other content. */
        @JvmStatic fun notice(@DrawableRes icon: Int, title: String, detail: String, vararg actions: Action): Item {
            val i = Item(Kind.NOTICE, "notice:" + title); i.icon = icon; i.title = title; i.detail = detail; i.actions = list(actions); return i
        }
        @JvmStatic fun text(title: String, detail: String): Item { val i = Item(Kind.TEXT, "text:" + title); i.title = title; i.detail = detail; return i }
        @JvmStatic fun loading(): Item { return Item(Kind.LOADING, "loading") }

        private fun <T> list(values: Array<out T>): MutableList<T> {
            val out: MutableList<T> = ArrayList(); for (v in values) out.add(v); return out
        }
    }
}
