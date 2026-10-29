package tv.stream.app.ui

import tv.stream.app.addon.Title

/** What one route shows: its blocks plus the surrounding chrome (title, column width, artwork, first focus). */
class Page {
    val items: MutableList<Item> = ArrayList()
    /** Quiet fixed upper-right actions, outside the scrolling content. Refresh stays rightmost. */
    var tools: List<Item.Action> = emptyList()
    /** Shown above the content; null for tab roots and pages with their own header. */
    var title: String? = null
    /** Full-screen artwork behind the page. */
    var backdrop: Title? = null
    /** Blur the artwork, as tvOS does behind lists that belong to a title (its sources). */
    var blurBackdrop: Boolean = false
    /** Lists and forms sit in a centred column with open space either side; shelves and grids use the full width. */
    var narrow: Boolean = false
    /** The key that receives focus when no earlier focus is being restored. */
    var initialFocus: String? = null
    /** Failed data remains visible on Back, but should get another bounded load when revisited. */
    var reusable: Boolean = true

    fun add(item: Item): Page = apply { items.add(item) }
    fun addAll(more: Collection<Item>): Page = apply { items.addAll(more) }
    fun title(value: String?): Page = apply { title = value }
    fun narrow(): Page = apply { narrow = true }
    /** A detached snapshot for progress delivery while a worker continues building result blocks. */
    fun copy(): Page {
        val copy = Page(); copy.items.addAll(items.map { it.snapshot() }); copy.title = title; copy.backdrop = backdrop
        copy.tools = tools.map { it.snapshot() }; copy.blurBackdrop = blurBackdrop; copy.narrow = narrow; copy.initialFocus = initialFocus
        copy.reusable = reusable; return copy
    }
    /** Home starts on its first card so the spotlight and artwork describe something. */
    fun firstTile(): String? = items.firstNotNullOfOrNull { it.tiles.firstOrNull()?.key }
}
