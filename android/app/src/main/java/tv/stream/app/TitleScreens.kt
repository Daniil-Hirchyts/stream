package tv.stream.app

import org.json.JSONObject
import java.util.ArrayList
import java.util.Locale
import java.util.SortedSet
import java.util.TreeSet
import java.util.concurrent.Callable
import java.util.function.Consumer
import tv.stream.app.addon.AddonRepository
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.MediaSource
import tv.stream.app.addon.Title
import tv.stream.app.data.InstalledAddon
import tv.stream.app.data.TvStore
import tv.stream.app.ui.Item
import tv.stream.app.ui.Item.Action
import tv.stream.app.ui.Item.Tile
import tv.stream.app.ui.LazyPanes
import tv.stream.app.ui.Page
import tv.stream.app.ui.Sounds

/** Title details (with seasons and episodes) and explicit source selection. */
class TitleScreens(private val nav: Navigator, private val services: TvApplication.Services) {

    @Throws(Exception::class)
    fun overview(route: Route): Page { return detailContent(route, "") }
    @Throws(Exception::class)
    fun details(route: Route, scope: Cancellation, progress: Consumer<Page>): Page {
        val initial = detailContent(route, "")
        pendingTitle(initial, route.title!!); progress.accept(initial)
        if (route.title!!.provider == "local") return initial
        val entry = services.preparation.prepare(route.title!!, route.video)
        val before = route.title
        route.title = entry.awaitMetadata(scope)
        scope.check()
        // Fresh metadata also updates a saved series, so its new episodes reach Home before the next background check.
        if (route.title !== before) services.store.refresh(route.title!!)
        val enriched = detailContent(route, "")
        scope.check(); services.preparation.prepare(route.title!!, route.video)
        return enriched
    }
    @Throws(Exception::class)
    private fun detailContent(route: Route, note: String): Page {
        val title = route.title!!
        val local = title.provider == "local"
        val chosen = title
        val entry: TvStore.LibraryEntry? = services.store.entry(title.key())
        val favourite = entry != null && entry.favourite
        val saved = entry != null
        val played: MutableMap<String, TvStore.Progress> = java.util.HashMap()
        var resumable: String? = null
        // Most recent first: the first resumable video is the one being watched.
        for (p in services.store.history(title.key())) {
            played[p.video] = p
            if (resumable == null && p.resumable()) resumable = p.video
        }
        val now = System.currentTimeMillis()
        // The selected video: explicit, else the one being watched, else the next one to watch, else the first episode.
        var video: String = if (!route.video.isEmpty()) route.video else if (resumable != null) resumable else title.id
        if (title.type == "series" && video == title.id && !title.episodes.isEmpty()) {
            val next: Title.Episode? = NewEpisodes.nextUp(title, played, now)
            // Without progress, start at the first numbered episode, not a special listed first.
            video = next?.id ?: (NewEpisodes.order(title).firstOrNull() ?: title.episodes[0]).id
        }
        route.video = video
        val selected = video
        val progress = played[video]
        val series = title.type == "series" && !title.episodes.isEmpty()
        val notice: NewEpisodes.Notice? = if (saved && series) NewEpisodes.notice(services.store, entry, played, now) else null
        val alerts = saved && series && !NewEpisodes.muted(services.store, title.key())
        val resume = progress != null && !progress.watched && progress.positionMs > 0

        val page = detailsPage(title, note)
        // Primary actions retain labels; compact toggles show their state and keep accessible action names.
        val actions: MutableList<Action> = ArrayList()
        if (title.type != "series" || video != title.id) actions.add(Action("play", Action.Style.PRIMARY, if (resume) "Resume" else "Play", Runnable { nav.play(chosen, selected, "") }).icon(R.drawable.ic_tv_play)
                .detail(if (series) shortEpisode(title, video) else ""))
        if (!local && (title.type != "series" || video != title.id)) actions.add(Action("sources", Action.Style.ICON, "Sources", Runnable { nav.sources(chosen, selected) }).icon(R.drawable.ic_tv_list))
        val isFavourite = favourite
        val watched = progress != null && progress.watched
        actions.add(Action("favourite", Action.Style.ICON, if (isFavourite) "Remove favourite" else "Add favourite",
                Runnable { Sounds.play(if (isFavourite) Sounds.Sound.FAVOURITE_OFF else Sounds.Sound.FAVOURITE_ON); nav.mutate(Callable { services.store.favourite(chosen, !isFavourite); null }) })
                .icon(if (isFavourite) R.drawable.ic_tv_heart else R.drawable.ic_tv_heart_outline).selected(isFavourite))
        if (title.type != "series") actions.add(Action("watched", Action.Style.ICON, if (watched) "Mark unwatched" else "Mark watched",
                Runnable { Sounds.play(if (watched) Sounds.Sound.WATCHED_OFF else Sounds.Sound.WATCHED_ON); nav.mutate(Callable { services.store.watched(chosen, selected, !watched); null }) })
                .icon(if (watched) R.drawable.ic_tv_unwatched else R.drawable.ic_tv_check).selected(watched))
        if (local) {
            actions.add(Action("rename", Action.Style.SECONDARY, "Rename import", Runnable { nav.edit("Rename import", MainActivity.NAME_HINT, "Title", chosen.name, Consumer { name -> nav.mutate(Callable {
                services.imports.rename(chosen.id, name)
                route.title = Title.parse("local", JSONObject().put("id", chosen.id).put("type", chosen.type).put("name", name))
                null
            }) }) }).icon(R.drawable.ic_tv_edit))
            actions.add(Action("remove-import", Action.Style.SECONDARY, "Remove import", Runnable { nav.then(Callable { services.imports.remove(chosen.id); null }, Runnable { nav.back() }) })
                    .icon(R.drawable.ic_tv_delete))
        } else if (saved) {
            val following = saved && series
            actions.add(Action("more", Action.Style.ICON, "More", Runnable { nav.options(chosen.name, Consumer { menu ->
                if (notice != null) menu.button("hide-new-episode", "Hide new episode", Runnable {
                    menu.dialog.dismiss(); nav.mutate(Callable { NewEpisodes.hide(services.store, chosen.key()); null })
                })
                if (following) menu.button("episode-alerts", if (alerts) "Turn off new episode alerts" else "Turn on new episode alerts", Runnable {
                    menu.dialog.dismiss(); nav.mutate(Callable { NewEpisodes.mute(services.store, chosen.key(), alerts); null })
                })
                menu.submenu("remove-library", "Remove from library", Consumer { confirmation ->
                    confirmation.button("confirm-remove", "Remove", Runnable {
                        confirmation.dialog.dismiss()
                        nav.then(Callable { services.store.removeLibrary(chosen.key()); null }, Runnable { nav.root("Library") })
                    })
                })
            }) }).icon(R.drawable.ic_tv_more))
        }
        page.add(Item.actions("title-actions", actions))
        page.initialFocus = "play"
        if (!local && noSources(title, video)) {
            page.add(Item.notice(R.drawable.ic_tv_info, Availability.NO_PROVIDER, "",
                    Action("add-source", Action.Style.SECONDARY, "Get add-ons", Runnable { nav.push(StoreScreens.route("Sources")) }).icon(R.drawable.ic_tv_extension)))
        }
        if (title.type == "series") episodes(page, route, title, played, notice)
        if (!title.cast.isEmpty()) page.add(Item.text("Starring", title.cast.subList(0, Math.min(4, title.cast.size)).joinToString(", ")))
        return page
    }

    private fun episodes(page: Page, route: Route, title: Title, saved: Map<String, TvStore.Progress>, notice: NewEpisodes.Notice?) {
        if (title.episodes.isEmpty()) {
            page.add(Item.notice(R.drawable.ic_tv_info, "No episodes listed", "Try Refresh details."))
            return
        }
        val seasons = seasonOrder(title.episodes)
        if (route.season == -1 || !seasons.contains(route.season)) route.season = defaultSeason(title.episodes, route.video, seasons)
        val tabs: MutableList<Action> = ArrayList()
        for (season in seasons) {
            tabs.add(Action("season:" + season, Action.Style.CHIP, if (season == 0) "Specials" else "Season " + season, Runnable {})
                    .selected(season == route.season))
        }
        val panes = seasonPanes(title, seasons, HashMap(saved), notice?.episodes?.toSet() ?: emptySet()) { e, watched, resume ->
            Runnable { nav.choose("Episode " + e.number + " · " + e.name,
                    arrayOf(if (resume) "Resume" else "Play", "Choose source", if (watched) "Mark unwatched" else "Mark watched"), Consumer { which ->
                        if (which == 2) {
                            Sounds.play(if (watched) Sounds.Sound.WATCHED_OFF else Sounds.Sound.WATCHED_ON)
                            nav.mutateEpisode(Callable { services.store.watched(title, e.id, !watched); null }, route, e.season, e.id)
                            return@Consumer
                        }
                        if (route.video != e.id) route.cache = null
                        route.video = e.id
                        if (which == 0) nav.play(title, e.id, "")
                        else nav.sources(title, e.id)
                    }) }
        }
        page.add(Item.episodes(tabs, panes, "season:" + route.season,
                Consumer { key -> route.season = Integer.parseInt(key.substring("season:".length)) }))
    }

    /** True when no enabled add-on could supply streams and the metadata carries none itself. */
    private fun noSources(title: Title, video: String): Boolean {
        if (!services.addons.providers("stream", title.type, video).isEmpty()) return false
        for (e in title.episodes) if (e.id == video && !e.streams.isEmpty()) return false
        return true
    }

    /** What the providers answered for one video; reused for a minute while the viewer re-sorts. */
    private class Fetched(val providers: List<InstalledAddon>, val found: AddonRepository.Result<MediaSource>?, val configuration: Long)

    /** The initial and loaded pages share the same fixed toolbar, including during provider requests. */
    fun detailsPage(title: Title, note: String): Page {
        val page = Page(); page.backdrop = title
        if (title.provider != "local") page.tools = java.util.Collections.singletonList(
                Action("refresh-details", Action.Style.ICON, "Refresh details", Runnable { services.preparation.invalidate(title, null); nav.refresh() }).icon(R.drawable.ic_tv_refresh))
        return page.add(Item.details(title, note))
    }
    fun sourcesPage(route: Route): Page {
        val title = route.title!!
        val page = Page().title(title.name).narrow(); page.backdrop = title; page.blurBackdrop = true
        page.tools = java.util.Collections.singletonList(Action("refresh-sources", Action.Style.ICON, "Refresh sources", Runnable {
            route.cache = null; services.preparation.invalidate(title, route.video); nav.refresh()
        }).icon(R.drawable.ic_tv_refresh))
        return page.add(Item.text("Sources", if (title.type == "series") episodeName(title, route.video) else ""))
    }

    @Throws(Exception::class)
    fun sources(route: Route, scope: Cancellation, progress: Consumer<Page>): Page {
        val title = route.title!!
        val page = sourcesPage(route)
        var fetched: Fetched? = if (route.cache is Fetched && (route.cache as Fetched).configuration == services.addons.configuration() && android.os.SystemClock.elapsedRealtime() - route.cachedAt < SOURCES_FRESH_MS) route.cache as Fetched else null
        if (fetched == null) {
            progress.accept(page.copy().add(Item.skeleton("Source results")))
            val providers: List<InstalledAddon> = services.addons.providers("stream", title.type, route.video)
            val entry = services.preparation.prepare(title, route.video)
            entry.awaitComplete(scope, Consumer { result ->
                try { progress.accept(sourceContent(route, Fetched(providers, result, entry.configuration), entry.complete && entry.metadataReady)) }
                catch (error: Exception) { throw IllegalStateException(error) }
            })
            fetched = Fetched(providers, entry.result(), entry.configuration)
            route.cache = fetched; route.cachedAt = android.os.SystemClock.elapsedRealtime()
        }
        return sourceContent(route, fetched)
    }
    @Throws(Exception::class)
    private fun sourceContent(route: Route, fetched: Fetched): Page { return sourceContent(route, fetched, true) }
    @Throws(Exception::class)
    private fun sourceContent(route: Route, fetched: Fetched, complete: Boolean): Page {
        val title = route.title!!
        val page = sourcesPage(route)
        val providers = fetched.providers
        val found = fetched.found
        val sources: MutableList<MediaSource> = ArrayList(if (found == null) ArrayList() else found.items)
        val installed: List<InstalledAddon> = services.store.addons()
        // Only what this TV can play. The rest is counted, not listed.
        val playable: List<MediaSource> = SourcePolicy.ranked(sources)
        if (playable.isEmpty() && !complete) return page.add(Item.skeleton("Source results"))
        if (!playable.isEmpty()) {
            val marks: Map<String, TvStore.SourceMark> = services.store.sourceMarks(title.key(), route.video)
            // "SIZE" or "SIZE:reverse". Choosing the current order again turns it around.
            val saved = services.store.preference("sourceSort", SourceSort.Order.BEST.name)
            val order = SourceSort.Order.of(saved.replace(":reverse", ""))
            val reverse = saved.endsWith(":reverse")
            val sorts: MutableList<Action> = ArrayList()
            for (o in SourceSort.Order.values()) {
                val current = o == order
                val next = o.name + (if (current && !reverse) ":reverse" else "")
                sorts.add(Action("sort:" + o.name, Action.Style.CHIP, o.label, Runnable {
                    route.scroll = null
                    nav.mutate(Callable { services.store.setPreference("sourceSort", next); null })
                }).text(if (current) o.label + (if (reverse) "  ↑" else "  ↓") else o.label).selected(current))
            }
            page.add(Item.chips("source-sort", "", sorts))
            var last = ""
            var lastAt: Long = 0
            var played = 0
            for (source in playable) {
                val mark = marks[SourcePolicy.key(source)]
                if (mark == null || mark.state != TvStore.SourceMark.PLAYED) continue
                played++
                if (mark.updatedMs > lastAt) { lastAt = mark.updatedMs; last = SourcePolicy.key(source) }
            }
            page.add(Item.header(if (playable.size == 1) "1 source" else playable.size.toString() + " sources",
                    (if (played > 0) played.toString() + " played  ·  " else "") + "by " + order.label.lowercase(Locale.ROOT) + (if (reverse) ", reversed" else "")))
            for (source in SourceSort.sort(playable, order, reverse, marks)) {
                val key = SourcePolicy.key(source)
                var provider = "Provider"
                for (a in installed) if (a.id == source.provider) provider = a.manifest.name
                val facts = SourceSort.facts(source)
                val meta = StringBuilder(provider).append(DOT).append(kind(source))
                if (!facts.quality().isEmpty()) meta.append(DOT).append(facts.quality())
                val release = SourceSort.releaseType(source)
                if (!release.isEmpty()) meta.append(DOT).append(release)
                if (facts.bytes > 0) meta.append(DOT).append(size(facts.bytes))
                if (facts.seeds >= 0) meta.append(DOT).append(if (facts.seeds == 1) "1 seed" else facts.seeds.toString() + " seeds")
                val mark = marks[key]
                val failed = mark != null && mark.state == TvStore.SourceMark.FAILED
                val status = if (mark == null) "" else if (failed) "Didn't play" else if (key == last) "Last played" else "Played"
                val row = Item.source("source:" + key, source.name.replace('\n', ' '), meta.toString(), "", status, !failed,
                        Runnable { nav.play(title, route.video, key) })
                page.add(row.status(if (mark == null) -1 else if (failed) R.drawable.ic_tv_close else R.drawable.ic_tv_check))
            }
        }
        if (playable.isEmpty() && !sources.isEmpty()) {
            val why = Availability.of(providers, sources, if (found == null) java.util.HashMap() else found.failures, installed)
            page.add(Item.message(R.drawable.ic_tv_info, why.heading, why.detail))
        } else if (sources.isEmpty()) {
            val why = Availability.of(providers, sources, if (found == null) java.util.HashMap() else found.failures, installed)
            page.add(if (providers.isEmpty())
                    Item.message(R.drawable.ic_tv_extension, why.heading, why.detail,
                        Action("add-source", Action.Style.PRIMARY, "Find a source add-on", Runnable { nav.push(StoreScreens.route("Sources")) }).icon(R.drawable.ic_tv_extension))
                    else Item.message(if (found!!.failures.isEmpty()) R.drawable.ic_tv_search else R.drawable.ic_tv_offline, why.heading, why.detail))
        } else if (found != null && !found.failures.isEmpty()) {
            page.add(Item.notice(R.drawable.ic_tv_offline, "Some providers are unavailable", Availability.failures(found.failures, installed)))
        }
        return page
    }

    companion object {
        private const val SOURCES_FRESH_MS: Long = 60_000
        private const val DOT = "  ·  "

        /** Catalogue entries can omit a logo that metadata supplies. Wait before choosing text as the title. */
        fun pendingTitle(page: Page, title: Title) {
            if (title.logo == null && title.provider != "local")
                for (item in page.items) if (item.kind == Item.Kind.DETAILS) item.loading = true
        }

        /** Numbered seasons in ascending order, then Specials (season 0) after the last one. */
        fun seasonOrder(episodes: List<Title.Episode>): List<Int> {
            val seasons: SortedSet<Int> = TreeSet()
            for (e in episodes) seasons.add(e.season)
            val ordered = ArrayList<Int>(seasons.size)
            for (season in seasons) if (season != 0) ordered.add(season)
            if (seasons.contains(0)) ordered.add(0)
            return ordered
        }

        /**
         * The season tab selected first: the selected (resume or next) episode's season, else the first numbered season.
         * Specials are chosen by default only when they hold the selected episode or are the only season.
         */
        fun defaultSeason(episodes: List<Title.Episode>, video: String, ordered: List<Int>): Int {
            for (e in episodes) if (e.id == video) return e.season
            return ordered.firstOrNull { it != 0 } ?: ordered.first()
        }

        /**
         * One pane per season, each built only when shown: a long series' other seasons cost a key and a few values
         * in the signature, not their tiles. The inputs are copies, so the panes never see a worker's later changes.
         */
        fun seasonPanes(title: Title, seasons: Collection<Int>, saved: Map<String, TvStore.Progress>, fresh: Set<String>,
                        options: (Title.Episode, Boolean, Boolean) -> Runnable): LazyPanes {
            val bySeason = title.episodes.groupBy { it.season }
            val builders = LinkedHashMap<String, () -> List<Item>>()
            val signature = ArrayList<Any?>(title.episodes.size * 5 + 2)
            val rows = HashSet<String>()
            signature.add(title.cacheJson())
            for (season in seasons) {
                val episodes = bySeason[season].orEmpty()
                rows.add("episodes:" + season)
                signature.add(season)
                for (e in episodes) {
                    rows.add("episode:" + e.id)
                    val p = saved[e.id]
                    signature.add(e.id); signature.add(p?.watched); signature.add(p?.positionMs); signature.add(p?.durationMs); signature.add(fresh.contains(e.id))
                }
                builders["season:" + season] = {
                    val tiles: MutableList<Tile> = ArrayList(episodes.size)
                    for (e in episodes) {
                        val p = saved[e.id]
                        val watched = p != null && p.watched
                        val resume = p != null && !watched && p.positionMs > 0
                        val tile = Tile("episode:" + e.id, Tile.Style.LANDSCAPE, e.name, "",
                                if (e.thumbnail != null) e.thumbnail else title.background, null, options(e, watched, resume))
                                .episode(e.number, e.overview).released(episodeDate(e.released))
                                .description("Episode " + e.number + ". " + e.name + (if (watched) ". Watched" else ""))
                        if (watched) tile.badge(R.drawable.ic_tv_check)
                        if (!watched && p != null && p.durationMs > 0) tile.progress(p.positionMs / p.durationMs.toFloat())
                        if (fresh.contains(e.id)) tile.flag("New")
                        tiles.add(tile)
                    }
                    java.util.Collections.singletonList(Item.shelf("episodes:" + season, "", "", Tile.Style.LANDSCAPE, tiles))
                }
            }
            return LazyPanes(builders, signature, rows)
        }

        /** Keep the provider's broadcast day; converting a midnight UTC stamp can move it to yesterday. */
        fun episodeDate(released: String?): String {
            if (released == null || released.length < 10) return ""
            try {
                return java.time.LocalDate.parse(released.substring(0, 10))
                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", Locale.getDefault()))
            } catch (invalid: java.time.DateTimeException) { return "" }
        }

        fun kind(source: MediaSource): String {
            return when (source.kind) {
                MediaSource.Kind.HTTP -> "Direct"
                MediaSource.Kind.TORRENT -> "Torrent"
                MediaSource.Kind.MAGNET -> "Magnet"
                else -> "Unsupported"
            }
        }
        fun size(bytes: Long): String {
            val gib = bytes / (1L shl 30).toDouble()
            return if (gib >= 1) String.format(java.util.Locale.ROOT, "%.1f GB", gib) else (bytes shr 20).toString() + " MB"
        }
        /** "S1 E4", for a Play button. */
        fun shortEpisode(title: Title, video: String): String {
            for (e in title.episodes) if (e.id == video) return "S" + e.season + " E" + e.number
            return ""
        }
        fun episodeName(title: Title, video: String): String {
            for (e in title.episodes) if (e.id == video) return "S" + e.season + " E" + e.number + " · " + e.name
            return if (title.type == "series") "Episode" else "Film"
        }
    }
}
