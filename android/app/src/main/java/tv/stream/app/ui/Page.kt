package tv.stream.app.ui

import tv.stream.app.addon.Title

/** What one route shows: its blocks plus the surrounding chrome (title, column width, artwork, first focus). */
class Page {
    @JvmField val items: MutableList<Item> = ArrayList()
    /** Quiet fixed upper-right actions, outside the scrolling content. Refresh stays rightmost. */
    @JvmField var tools: List<Item.Action> = java.util.Collections.emptyList()
    /** Shown above the content; null for tab roots and pages with their own header. */
    @JvmField var title: String? = null
    /** Full-screen artwork behind the page. */
    @JvmField var backdrop: Title? = null
    /** Blur the artwork, as tvOS does behind lists that belong to a title (its sources). */
    @JvmField var blurBackdrop: Boolean = false
    /** Lists and forms sit in a centred column with open space either side; shelves and grids use the full width. */
    @JvmField var narrow: Boolean = false
    /** The key that receives focus when no earlier focus is being restored. */
    @JvmField var initialFocus: String? = null
    /** Failed data remains visible on Back, but should get another bounded load when revisited. */
    @JvmField var reusable: Boolean = true

    fun add(item: Item): Page { items.add(item); return this }
    fun addAll(more: Collection<Item>): Page { items.addAll(more); return this }
    fun title(value: String?): Page { title = value; return this }
    fun narrow(): Page { narrow = true; return this }
    /** A snapshot for progress delivery while a worker continues adding result blocks. */
    fun copy(): Page {
        val copy = Page(); copy.items.addAll(items); copy.title = title; copy.backdrop = backdrop
        copy.tools = tools; copy.blurBackdrop = blurBackdrop; copy.narrow = narrow; copy.initialFocus = initialFocus
        copy.reusable = reusable; return copy
    }
    /** Home starts on its first card so the spotlight and artwork describe something. */
    fun firstTile(): String? {
        for (item in items) if (!item.tiles.isEmpty()) return item.tiles.get(0).key
        return null
    }
}
