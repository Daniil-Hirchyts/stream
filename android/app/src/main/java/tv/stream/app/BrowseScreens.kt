package tv.stream.app

import org.json.JSONArray
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.Locale
import java.util.function.Consumer
import java.util.function.ToIntFunction
import tv.stream.app.addon.AddonManifest
import tv.stream.app.addon.AddonRepository
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title
import tv.stream.app.data.InstalledAddon
import tv.stream.app.data.TvStore
import tv.stream.app.ui.Item
import tv.stream.app.ui.Item.Tile
import tv.stream.app.ui.Page

/** Home, Search, Library, History and catalogue browsing. Builders run on workers and block on providers. */
class BrowseScreens(private val nav: Navigator, private val services: TvApplication.Services) {
    private class SavedResults(val result: AddonRepository.CatalogPage, val configuration: Long) {
        val saved: Long = android.os.SystemClock.elapsedRealtime()
        fun fresh(revision: Long, restoring: Boolean): Boolean {
            // Returning after a long film updates local progress without replacing the gallery underneath focus.
            // Failed snapshots can be shown while reloading, but must never suppress the recovery request.
            return !result.loading && result.result.failures.isEmpty() && configuration == revision
                    && (restoring || android.os.SystemClock.elapsedRealtime() - saved < FRESH_MS)
        }
    }
    private var homeResults: SavedResults? = null
    private val searches: MutableMap<String, SavedResults> = LinkedHashMap(8, .75f, true)

    @Throws(Exception::class)
    fun home(scope: Cancellation, progress: Consumer<Page>, refresh: Boolean, restoring: Boolean): Page {
        val catalogues: List<AddonRepository.CatalogQuery> = services.addons.homeCatalogs()
        val revision = services.addons.configuration()
        // The viewer's own shelves are read once; each partial catalogue result only adds provider shelves.
        val own = ownShelves()
        synchronized(this) {
            if (!refresh && homeResults != null && homeResults!!.fresh(revision, restoring)) return homeContent(catalogues, own, homeResults!!.result)
        }
        progress.accept(homeContent(catalogues, own, null))
        val found = services.addons.catalogsPage(catalogues, scope, Consumer { partial -> progress.accept(homeContent(catalogues, own, partial)) })
        scope.check()
        synchronized(this) { homeResults = SavedResults(found, revision) }
        return homeContent(catalogues, own, found)
    }
    /** New Episodes, Continue Watching and Favourites, from local data. Showing them counts toward a notice's end. */
    private fun ownShelves(): List<Item> {
        val shelves: MutableList<Item> = ArrayList()
        val library: MutableMap<String, Title> = LinkedHashMap()
        val favourites: MutableList<Tile> = ArrayList()
        val saved: List<TvStore.LibraryEntry> = services.store.library(false)
        val news: Map<String, NewEpisodes.Notice> = NewEpisodes.current(services.store, saved, services.store.history(false), System.currentTimeMillis(), true)
        for (e in saved) {
            library[e.title.key()] = e.title
            if (e.favourite) favourites.add(flagged(poster("favourite", e.title), news))
        }
        val fresh: MutableList<Tile> = ArrayList()
        for (notice in news.values) fresh.add(newEpisodeTile(notice))
        if (!fresh.isEmpty()) shelves.add(Item.shelf("new-episodes", "New Episodes", "", Tile.Style.LANDSCAPE, fresh))
        val continuing: MutableList<Tile> = ArrayList()
        val seen: MutableSet<String> = java.util.HashSet()
        for (p in services.store.history(true)) {
            val title = library[p.titleKey]
            if (title != null && seen.add(p.titleKey)) continuing.add(progressTile("continue", title, p))
        }
        if (!continuing.isEmpty()) shelves.add(Item.shelf("continue-watching", "Continue Watching", "", Tile.Style.LANDSCAPE, continuing))
        if (!favourites.isEmpty()) shelves.add(Item.shelf("favourites", "Favourites", "", Tile.Style.POSTER, favourites))
        return shelves
    }
    private fun homeContent(catalogues: List<AddonRepository.CatalogQuery>, own: List<Item>, load: AddonRepository.CatalogPage?): Page {
        val found: AddonRepository.Result<Title>? = if (load == null) null else load.result
        val page = Page()
        val shelves: MutableList<Item> = ArrayList(own)
        var failed = false
        var stale = false
        if (found != null) {
            for (query in catalogues) {
                val key = query.key()
                val tiles: MutableList<Tile> = ArrayList()
                for (t in found.byResource.getOrDefault(key, Collections.emptyList())) tiles.add(poster(key, t))
                if (!tiles.isEmpty()) shelves.add(Item.shelf("catalogue:" + key, shelfTitle(query.catalog), "", Tile.Style.POSTER, tiles))
            }
            failed = !found.failures.isEmpty(); stale = found.stale
        }
        val loading = if (load == null) !catalogues.isEmpty() else load.loading
        if (!shelves.isEmpty()) page.add(Item.spotlight(null))
        if (failed && !loading) {
            val heading = if (shelves.isEmpty()) "Unable to load Home" else if (stale) "Showing saved catalogues" else "Some providers are unavailable"
            page.add(Item.notice(R.drawable.ic_tv_offline, heading, loadDetail(load!!),
                    Item.Action("reload-home", Item.Action.Style.SECONDARY, "Reload", Runnable { nav.refresh() }).icon(R.drawable.ic_tv_refresh)))
            if (shelves.isEmpty()) page.initialFocus = "reload-home"
        }
        if (shelves.isEmpty() && loading) page.add(Item.skeleton("Home results"))
        else if (shelves.isEmpty() && !failed) page.add(Item.message(0, "Make yourself at home",
                "Films and series will appear here. Add catalogues or import a torrent in Settings."))
        else page.addAll(shelves)
        page.reusable = !loading && !failed
        // Browse is a primary destination; Home only exposes its contextual Refresh action.
        if (!catalogues.isEmpty()) page.tools = java.util.Collections.singletonList(
                Item.Action("refresh", Item.Action.Style.ICON, "Refresh", Runnable { nav.refresh() }).icon(R.drawable.ic_tv_refresh))
        return page
    }

    @Throws(Exception::class)
    fun search(route: Route, scope: Cancellation, progress: Consumer<Page>, refresh: Boolean, restoring: Boolean): Page {
        val query = route.value
        val revision = services.addons.configuration()
        synchronized(this) {
            val saved = searches[query]
            if (!refresh && saved != null && saved.fresh(revision, restoring)) return searchContent(route, saved.result)
        }
        progress.accept(searchContent(route, null))
        if (query.trim { it <= ' ' }.length < 2) return searchContent(route, null)
        val found = services.addons.searchPage(query, scope, Consumer { partial ->
            try { progress.accept(searchContent(route, partial)) }
            catch (error: Exception) { throw IllegalStateException(error) }
        })
        scope.check()
        synchronized(this) {
            searches[query] = SavedResults(found, revision)
            while (searches.size > 6) searches.remove(searches.keys.iterator().next())
        }
        return searchContent(route, found)
    }
    @Throws(Exception::class)
    private fun searchContent(route: Route, load: AddonRepository.CatalogPage?): Page {
        val found: AddonRepository.Result<Title>? = if (load == null) null else load.result
        route.extras["keyboardLanguages"] = services.store.preference("keyboardLanguages", "en")
        if (!route.extras.containsKey("keyboardLanguage")) route.extras["keyboardLanguage"] = services.store.preference("keyboardLanguage", "en")
        val page = Page()
        val search = searchItem(route)
        page.add(search)
        page.initialFocus = "keyboard:" + tv.stream.app.ui.KeyboardLanguages.alphabet(search.keyboardLanguage)[0]
        if (route.value.trim { it <= ' ' }.length < 2) return page

        val tiles: MutableList<Tile> = ArrayList()
        val needle = route.value.trim { it <= ' ' }.lowercase(Locale.ROOT)
        for (id in services.store.importIds()) {
            val descriptor: org.json.JSONObject = services.store.importDescriptor(id)!!
            val name = descriptor.optString("name", "Import")
            if (name.lowercase(Locale.ROOT).contains(needle)) {
                val imported = Title.parse("local", org.json.JSONObject().put("id", id).put("type", "movie").put("name", name))!!
                tiles.add(poster("search-import", imported))
            }
        }
        // Imports above are read afresh; a retained provider snapshot must not reintroduce renamed/removed imports.
        if (found != null) for (t in found.items) if (t.provider != "local") tiles.add(poster("search", t))
        search.tiles = tiles
        search.loading = load == null || load.loading
        val failed = found != null && !found.failures.isEmpty()
        search.detail = if (failed && !search.loading && tiles.isEmpty()) "Search unavailable"
                else if (!search.loading && tiles.isEmpty()) "No results" else ""
        page.reusable = !search.loading && !failed
        if (failed && !search.loading) {
            search.meta = loadDetail(load!!)
            search.actions = Collections.singletonList(Item.Action("reload-search", Item.Action.Style.SECONDARY, "Reload", Runnable { nav.refresh() }).icon(R.drawable.ic_tv_refresh))
        }
        return page
    }

    fun searchLoading(route: Route): Item {
        val item = searchItem(route)
        item.loading = route.value.trim { it <= ' ' }.length >= 2
        return item
    }

    private fun searchItem(route: Route): Item {
        val item = Item.keyboard("keyboard", route.value, Consumer { text -> route.value = text; route.scroll = null; nav.searchInput(text) })
        item.keyboardLanguages = tv.stream.app.ui.KeyboardLanguages.parse(route.extras.getOrDefault("keyboardLanguages", "en"))
        val language = route.extras.getOrDefault("keyboardLanguage", "en")
        item.keyboardLanguage = if (item.keyboardLanguages.contains(language)) language else item.keyboardLanguages[0]
        item.keyboardVisible = "false" != route.extras["searchKeyboard"]
        item.keyboardLanguageChanged = Consumer { chosen -> nav.keyboardLanguage(chosen) }
        item.action = Runnable { nav.push(Route("Keyboard languages")) }
        return item
    }

    fun library(): Page {
        val page = Page()
        val favourites: MutableList<Tile> = ArrayList()
        val others: MutableList<Tile> = ArrayList()
        val titles: MutableMap<String, Title> = LinkedHashMap()
        val saved: List<TvStore.LibraryEntry> = services.store.library(false)
        val played: List<TvStore.Progress> = services.store.history(false)
        val news: Map<String, NewEpisodes.Notice> = NewEpisodes.current(services.store, saved, played, System.currentTimeMillis(), false)
        for (e in saved) {
            titles[e.title.key()] = e.title
            val tile = flagged(poster("library", e.title), news)
            (if (e.favourite) favourites else others).add(tile)
        }
        val history: MutableList<Tile> = ArrayList()
        for (progress in played) {
            val title = titles[progress.titleKey]
            if (title != null) history.add(progressTile("history", title, progress))
        }
        if (favourites.isEmpty() && others.isEmpty() && history.isEmpty()) {
            // A control to start from, so the empty page still opens with focus inside it rather than on the rail.
            return page.add(Item.message(0, "Your library is empty",
                    "Films and series you play or favourite will appear here.",
                    Item.Action("library-browse", Item.Action.Style.PRIMARY, "Browse", Runnable { nav.root("Browse") }).icon(R.drawable.ic_tv_explore)))
        }
        if (!favourites.isEmpty()) { page.add(Item.header("Favourites")); page.addAll(Item.grid("favourites", Tile.Style.POSTER, POSTER_COLUMNS, favourites)) }
        if (!others.isEmpty()) { page.add(Item.header("Recently played")); page.addAll(Item.grid("played", Tile.Style.POSTER, POSTER_COLUMNS, others)) }
        if (!history.isEmpty()) {
            page.add(Item.header("Watch history"))
            page.addAll(Item.grid("history", Tile.Style.LANDSCAPE, LANDSCAPE_COLUMNS, history))
        }
        return page
    }

    /**
     * Every enabled catalogue as a title-only block, grouped by kind (Films, Series, …) in a fixed order.
     * Nothing is fetched until the viewer opens a block.
     */
    fun catalogues(): Page {
        val page = Page().title("Browse")
        val byType: MutableMap<String, MutableList<Tile>> = java.util.TreeMap(
                Comparator.comparingInt(ToIntFunction<String> { type -> typeOrder(type) }).thenComparing(Comparator.naturalOrder<String>()))
        for (a in services.store.addons()) if (a.enabled) for (c in a.manifest.catalogs) {
            if (!browsable(c)) continue
            val name = if (c.name.trim { it <= ' ' }.isEmpty()) typeLabel(c.type) else c.name.trim { it <= ' ' }
            byType.computeIfAbsent(c.type) { ArrayList() }.add(Tile("catalogue:" + a.id + c.type + c.id, Tile.Style.LANDSCAPE,
                    name, "", null, null, Runnable {
                val next = Route("Catalogue"); next.value = JSONArray().put(a.id).put(c.type).put(c.id).toString(); nav.push(next)
            }).description(name).block())
        }
        if (byType.isEmpty()) return page.add(Item.message(R.drawable.ic_tv_extension, "No catalogues", "Install a catalogue add-on from Add-ons.",
                Item.Action("catalogues-add", Item.Action.Style.PRIMARY, "Browse add-ons", Runnable { nav.push(StoreScreens.route("Catalogues")) }).icon(R.drawable.ic_tv_extension)))
        var colorIndex = 0
        for (group in byType.entries) {
            for (tile in group.value) tile.colorIndex = colorIndex++
            page.add(Item.header(typeLabel(group.key)))
            page.addAll(Item.grid("catalogues:" + group.key, Tile.Style.LANDSCAPE, CATALOGUE_COLUMNS, group.value))
        }
        return page
    }
    @Throws(Exception::class)
    fun catalogue(route: Route, scope: Cancellation, progress: Consumer<Page>, refresh: Boolean): Page {
        val identity = JSONArray(route.value)
        var addon: InstalledAddon? = null
        var catalog: AddonManifest.Catalog? = null
        for (a in services.store.addons()) if (a.id == identity.getString(0)) {
            addon = a
            for (c in a.manifest.catalogs) if (c.type == identity.getString(1) && c.id == identity.getString(2)) catalog = c
        }
        if (addon == null || catalog == null || !addon.enabled) {
            return Page().title("Catalogue").add(Item.message(R.drawable.ic_tv_info, "Catalogue unavailable", "The add-on was disabled or removed."))
        }
        val page = Page()
        page.tools = Collections.singletonList(Item.Action("refresh-catalogue", Item.Action.Style.ICON, "Refresh", Runnable { nav.refresh() }).icon(R.drawable.ic_tv_refresh))
        page.add(Item.catalogueHeader(shelfTitle(catalog), typeLabel(catalog.type) + (if (catalog.supports("skip")) " · Page " + (route.page + 1) else "")))
        // Filters with listed values are rows of chips on the page (no long list in a dialog); free text asks in a dialog.
        var complete = true
        val typed: MutableList<Item.Action> = ArrayList()
        // A required filter with listed values starts on its first value, so the catalogue loads at once.
        val applied: MutableMap<String, String> = LinkedHashMap(route.extras)
        for (e in catalog.extras) if (e.name != "skip") {
            if (e.required && !applied.containsKey(e.name) && !e.options.isEmpty()) applied[e.name] = e.options[0]
            if (e.required && !applied.containsKey(e.name)) complete = false
            if (e.name == "search" && !e.required) continue
            val chosen = applied[e.name]
            if (e.options.isEmpty()) {
                typed.add(Item.Action("filter:" + e.name, Item.Action.Style.CHIP, capitalise(e.name), Runnable { filter(route, e) })
                        .detail(if (chosen != null) chosen else if (e.required) "Choose" else "Any").selected(chosen != null))
                continue
            }
            val values: MutableList<Item.Action> = ArrayList()
            if (!e.required) values.add(Item.Action("filter:" + e.name + ":", Item.Action.Style.CHIP, "Any", Runnable { choose(route, e.name, "") })
                    .selected(chosen == null))
            for (option in e.options) values.add(Item.Action("filter:" + e.name + ":" + option, Item.Action.Style.CHIP, option,
                    Runnable { choose(route, e.name, option) }).selected(option == chosen))
            // The chips speak for themselves; no "Genre" heading above them.
            page.add(Item.chips("filters:" + e.name, "", values))
        }
        if (!typed.isEmpty()) page.add(Item.chips("filters", "", typed))
        if (!complete) return page.add(Item.message(R.drawable.ic_tv_list, "Choose the required filters", "Then this catalogue will load."))
        val extras: MutableMap<String, String> = applied
        if (catalog.supports("skip")) extras["skip"] = Integer.toString(route.page * CATALOGUE_PAGE_SIZE)
        val query = AddonRepository.CatalogQuery(addon, catalog, extras)
        val paginated = catalog.supports("skip")
        val loaded = services.addons.catalogPage(query, scope, refresh,
                Consumer { partial -> progress.accept(catalogueContent(page, route, paginated, partial)) })
        return catalogueContent(page, route, paginated, loaded)
    }
    private fun catalogueContent(shell: Page, route: Route, paginated: Boolean, load: AddonRepository.CatalogPage): Page {
        val page = shell.copy()
        val found: AddonRepository.Result<Title> = load.result
        val tiles: MutableList<Tile> = ArrayList()
        // Providers often return 100 items per request. Bound the visible page to the same stride as skip,
        // otherwise advancing one page repeats most of the previous page's posters.
        val limit = if (paginated) Math.min(CATALOGUE_PAGE_SIZE, found.items.size) else found.items.size
        for (i in 0 until limit) tiles.add(poster("catalogue", found.items[i]))
        val failed = !found.failures.isEmpty()
        page.reusable = !failed && !load.loading
        if (failed && !load.loading) {
            val detail = ("Attempt " + load.attempt + " of " + AddonRepository.CatalogPage.MAX_ATTEMPTS
                    + ". " + catalogueFailure(found.failures.values.iterator().next()) + (if (tiles.isEmpty()) "" else ". Showing saved entries."))
            page.add(Item.notice(R.drawable.ic_tv_offline, "Unable to load catalogue", detail,
                    Item.Action("reload-catalogue", Item.Action.Style.SECONDARY, "Reload", Runnable { nav.refresh() }).icon(R.drawable.ic_tv_refresh)))
        }
        if (tiles.isEmpty() && load.loading) page.add(Item.skeleton("Catalogue results"))
        else if (tiles.isEmpty() && !failed) page.add(Item.message(R.drawable.ic_tv_movie, "No titles on this page",
                if (route.page > 0) "Go to the previous page or change the filters." else "Try other filters."))
        else page.addAll(Item.grid("catalogue-grid", Tile.Style.POSTER, POSTER_COLUMNS, tiles))
        if (paginated && !load.loading) page.add(pagination(route, "bottom", !tiles.isEmpty()))
        page.initialFocus = if (tiles.isEmpty()) (if (failed && !load.loading) "reload-catalogue" else "refresh-catalogue") else tiles[0].key
        return page
    }
    private fun pagination(route: Route, placement: String, hasNext: Boolean): Item {
        val controls: MutableList<Item.Action> = ArrayList()
        if (route.page > 0) controls.add(Item.Action("previous-page:" + placement, Item.Action.Style.SECONDARY,
                "Previous page", Runnable { changePage(route, -1) }).text("‹ Previous page"))
        if (hasNext) controls.add(Item.Action("next-page:" + placement, Item.Action.Style.SECONDARY,
                "Next page", Runnable { changePage(route, 1) }).text("Next page ›"))
        return Item.pagination("paging:" + placement, "Page " + (route.page + 1), controls)
    }
    private fun changePage(route: Route, delta: Int) {
        route.page = Math.max(0, route.page + delta); route.scroll = null
        route.focus = ""; route.childScroll.clear(); nav.changed()
    }

    /** A free-text filter (no listed values). */
    private fun filter(route: Route, e: AddonManifest.Extra) {
        nav.edit(capitalise(e.name), "Value", route.extras.getOrDefault(e.name, ""), Consumer { value -> choose(route, e.name, value) })
    }
    private fun choose(route: Route, name: String, value: String) {
        if (value.isEmpty()) route.extras.remove(name) else route.extras[name] = value
        route.page = 0; route.scroll = null; route.focus = ""; route.childScroll.clear(); nav.changed()
    }

    // Shared tile builders.
    fun poster(section: String, t: Title): Tile { return poster(section, t, "") }
    fun poster(section: String, t: Title, subtitle: String): Tile {
        return Tile(section + ":" + t.key(), Tile.Style.POSTER, t.name, subtitle, t.poster, t, Runnable { nav.openTitle(t, "") })
    }
    /** Opens the series on its first new episode, so Play starts it. */
    private fun newEpisodeTile(notice: NewEpisodes.Notice): Tile {
        val title = notice.title; val episode = notice.episode
        val number = TitleScreens.shortEpisode(title, episode.id); val label = notice.label()
        val image = if (episode.thumbnail != null) episode.thumbnail else if (title.background != null) title.background else title.poster
        return Tile("new:" + title.key() + ":" + episode.id, Tile.Style.LANDSCAPE, title.name, number + " · " + label,
                image, title, Runnable { nav.openTitle(title, episode.id) }).headline(label + " · " + number)
                .description(title.name + ". " + label + ", season " + episode.season + " episode " + episode.number + ", " + episode.name)
    }
    private fun progressTile(section: String, title: Title, p: TvStore.Progress): Tile {
        val episode = TitleScreens.episodeName(title, p.video)
        val state = if (p.watched) "" else if (p.durationMs > 0) minutes(p.durationMs - p.positionMs) + " left" else "Resume at " + minutes(p.positionMs)
        var image = if (title.background != null) title.background else title.poster
        for (e in title.episodes) if (e.id == p.video && e.thumbnail != null) image = e.thumbnail
        val tile = Tile(section + ":" + title.key() + ":" + p.video, Tile.Style.LANDSCAPE, title.name,
                if (title.type == "series") episode + (if (state.isEmpty()) "" else " · " + state) else state, image, title, Runnable { nav.openTitle(title, p.video) })
        if (p.watched) { if (section != "history") tile.badge(R.drawable.ic_tv_check) }
        else if (p.durationMs > 0) tile.progress(Math.min(1f, p.positionMs / p.durationMs.toFloat()))
        return tile
    }

    companion object {
        private const val POSTER_COLUMNS = 6
        private const val LANDSCAPE_COLUMNS = 3
        private const val CATALOGUE_COLUMNS = 3
        private const val CATALOGUE_PAGE_SIZE = AddonRepository.CatalogPage.PAGE_SIZE
        private const val FRESH_MS: Long = 5 * 60_000

        /** Internal episode-query catalogues need app-supplied ID lists, not a viewer-entered filter. */
        fun browsable(catalog: AddonManifest.Catalog): Boolean {
            for (extra in catalog.extras)
                if (extra.required && (extra.name == "lastVideosIds" || extra.name == "calendarVideosIds")) return false
            return true
        }
        /** Films, then Series, then everything else. */
        private fun typeOrder(type: String): Int { return if (type == "movie") 0 else if (type == "series") 1 else 2 }
        private fun loadDetail(load: AddonRepository.CatalogPage): String {
            var detail = "Attempt " + load.attempt + " of " + AddonRepository.CatalogPage.MAX_ATTEMPTS
            if (!load.loading && !load.result.failures.isEmpty()) detail += ". " + catalogueFailure(load.result.failures.values.iterator().next())
            if (load.result.stale && !load.result.items.isEmpty()) detail += ". Showing saved entries."
            return detail
        }
        private fun catalogueFailure(kind: tv.stream.app.addon.AddonException.Kind): String {
            return when (kind) {
                tv.stream.app.addon.AddonException.Kind.CERTIFICATE -> "The provider's secure connection could not be verified."
                tv.stream.app.addon.AddonException.Kind.MALFORMED -> "The provider returned an invalid catalogue."
                tv.stream.app.addon.AddonException.Kind.HTTP -> "The provider could not serve this page."
                else -> "The provider could not be reached. Try again later."
            }
        }
        /** A series poster says "New" while it has a new-episode notice. */
        private fun flagged(poster: Tile, news: Map<String, NewEpisodes.Notice>): Tile {
            return if (poster.media != null && news.containsKey(poster.media!!.key())) poster.flag("New") else poster
        }
        fun minutes(ms: Long): String {
            val minutes = Math.max(1L, Math.round(ms / 60_000.0))
            return if (minutes >= 60) (minutes / 60).toString() + " h " + (minutes % 60) + " min" else minutes.toString() + " min"
        }
        /** "Popular" for films becomes "Popular Films", so a provider's film and series rows are distinguishable. */
        fun shelfTitle(catalog: AddonManifest.Catalog): String {
            val type = typeLabel(catalog.type); val name = catalog.name.trim { it <= ' ' }
            if (name.isEmpty()) return type
            val lower = name.lowercase(Locale.ROOT)
            if (type.isEmpty() || lower.contains(type.lowercase(Locale.ROOT)) || lower.contains(catalog.type.lowercase(Locale.ROOT))
                    || (catalog.type == "movie" && lower.contains("film"))) return name
            return name + " " + type
        }
        fun typeLabel(type: String): String {
            return when (type) {
                "movie" -> "Films"
                "series" -> "Series"
                "channel" -> "Channels"
                "tv" -> "Live TV"
                else -> capitalise(type)
            }
        }
        fun capitalise(value: String): String { return if (value.isEmpty()) value else Character.toUpperCase(value[0]).toString() + value.substring(1) }
    }
}
