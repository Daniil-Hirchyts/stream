package tv.stream.app

import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.function.Consumer
import tv.stream.app.addon.AddonException
import tv.stream.app.addon.AddonListing
import tv.stream.app.addon.AddonManifest
import tv.stream.app.addon.AddonRepository
import tv.stream.app.addon.AddonUrl
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.TorrentAddons
import tv.stream.app.data.InstalledAddon
import tv.stream.app.ui.Item
import tv.stream.app.ui.Item.Action
import tv.stream.app.ui.Page

/**
 * The add-on store: built-in add-ons, curated torrent scrapers and lists published through `addon_catalog` (Stremio's Official and
 * Community lists by default). Every install goes through a review of the add-on's own current manifest.
 */
class StoreScreens(private val nav: Navigator, private val services: TvApplication.Services) {
    private class Lists(val configuration: List<String>, val result: AddonRepository.Result<AddonListing>) {
        val loadedAt: Long = android.os.SystemClock.elapsedRealtime()
    }
    @Volatile private var lists: Lists? = null

    @Throws(Exception::class)
    fun store(route: Route, scope: Cancellation): Page { return store(route, scope, Consumer { }) }
    @Throws(Exception::class)
    fun store(route: Route, scope: Cancellation, progress: Consumer<Page>): Page {
        val filter = route.extras.getOrDefault("filter", "All")
        val page = Page().title("Add-on store").narrow()
        page.tools = java.util.Collections.singletonList(Action("refresh-store", Action.Style.ICON, "Refresh add-on lists", Runnable { refreshLists() }).icon(R.drawable.ic_tv_refresh))
        val chips: MutableList<Action> = ArrayList()
        for (f in FILTERS) chips.add(Action("filter:" + f, Action.Style.CHIP, f, Runnable {
            route.extras["filter"] = f; route.scroll = null; route.focus = "filter:" + f; nav.refresh()
        }).selected(f == filter))
        page.add(Item.chips("store-filters", "", chips))
        page.initialFocus = "filter:" + filter
        // Installed add-ons are managed in Settings; here they show as Installed in their list.
        val installed: List<InstalledAddon> = services.store.addons()
        if (filter == "All" && missingStarters(installed) > 0) {
            page.add(Item.header("Start here"))
            page.add(Item.source("store:starters", "Starter add-ons", "Films and series · free classic films · subtitles", "", "", true,
                    Runnable { nav.push(starters()) }).status(-1))
        }

        // A filter changes what is displayed, not the directory request. Keep the lists briefly between renders,
        // invalidating them when installed providers change or the user explicitly refreshes.
        val configuration: MutableList<String> = ArrayList()
        for (a in installed) configuration.add(a.id + ":" + a.enabled + ":" + a.manifest.json)
        val cached = lists
        val found: AddonRepository.Result<AddonListing>
        if (cached != null && cached.configuration == configuration && android.os.SystemClock.elapsedRealtime() - cached.loadedAt < LIST_CACHE_MS) found = cached.result
        else {
            progress.accept(page.copy().add(Item.skeleton("Store results")))
            found = services.addons.store(scope)
            scope.check(); lists = Lists(configuration, found)
        }
        var shown = 0
        for (list in found.byResource.entries) {
            val rows: MutableList<Item> = ArrayList()
            for (l in list.value) if (matches(l, filter)) rows.add(listing(l, installed))
            if (rows.isEmpty()) continue
            page.add(Item.header(list.key))
            page.addAll(rows); shown += rows.size
        }
        if (shown == 0) page.add(Item.message(R.drawable.ic_tv_extension, "Nothing listed", ""))
        if (!found.failures.isEmpty()) page.add(Item.notice(R.drawable.ic_tv_offline, if (found.stale) "Showing saved lists" else "Some lists are unavailable", ""))
        return page
    }
    private fun refreshLists() { lists = null; nav.refresh() }
    private fun listing(l: AddonListing, installed: List<InstalledAddon>): Item {
        val present = installed(l.url, l.manifest.id, installed) != null
        val status = if (present) "Installed" else if (l.manifest.configurationRequired) "Needs setup" else "Review"
        val types = types(l.manifest.types)
        // One line of what it adds; the description waits for the review page.
        return Item.source("store:" + l.url, l.manifest.name, kinds(l.manifest) + (if (l.torrents()) " · Torrent" else "") + (if (types.isEmpty()) "" else "  ·  " + types),
                "", if (present) status else if (l.manifest.configurationRequired) status else "", true, Runnable { val r = Route("Add-on review"); r.value = l.url; nav.push(r) })
                .status(if (present) R.drawable.ic_tv_check else if (l.manifest.configurationRequired) R.drawable.ic_tv_info else -1)
    }

    private fun missingStarters(installed: List<InstalledAddon>): Int {
        var missing = 0
        for (url in services.starters) if (installed(url, "", installed) == null) missing++
        return missing
    }
    /** What the starter pack adds, then one Install for all of it (each add-on's manifest is read when it installs). */
    private fun starterReview(): Page {
        val page = Page().title("Starter add-ons").narrow()
        val installed: List<InstalledAddon> = services.store.addons()
        for (url in services.starters) {
            val about = StarterAddons.describe(url)
            val present = installed(url, "", installed) != null
            page.add(Item.row("starter:" + url, if (present) R.drawable.ic_tv_check else R.drawable.ic_tv_extension, about[0], if (present) "Installed" else about[1], null))
        }
        if (missingStarters(installed) > 0) {
            page.add(Item.actions("starter-actions", Action("install-starters", Action.Style.PRIMARY, "Install", Runnable { nav.installStarters(services.starters) })
                    .icon(R.drawable.ic_tv_add)))
            page.initialFocus = "install-starters"
        }
        page.add(Item.text("", "Each provider receives the titles you open and your searches."))
        return page
    }

    /** The add-on's own current manifest, what it supplies and what it receives, then Install. */
    @Throws(Exception::class)
    fun review(route: Route, scope: Cancellation): Page {
        val url = route.value
        if (url == StarterAddons.PACK) return starterReview()
        val curated: TorrentAddons.Entry? = TorrentAddons.find(url)
        val manifest: AddonManifest = try { services.addons.preview(url, scope) }
        catch (e: AddonException) {
            if (e.kind == AddonException.Kind.CANCELLED) throw e
            val page = Page().title("Add-on").narrow()
            page.add(Item.message(R.drawable.ic_tv_offline, "This add-on isn't responding", tv.stream.app.ui.Failures.message(e),
                    Action("retry-review", Action.Style.PRIMARY, "Retry", Runnable { nav.refresh() }).icon(R.drawable.ic_tv_refresh)))
            if (curated != null) page.add(Item.actions("offline-setup", setupActions(curated.configureUrl)))
            page.initialFocus = "retry-review"
            return page
        }
        val installed: List<InstalledAddon> = services.store.addons()
        val present = installed(url, manifest.id, installed)
        val builtin = url.startsWith(AddonUrl.BUILTIN)
        val torrents = manifest.p2p || curated != null
        // What it is, what it adds and who runs it: three short lines, then the one decision.
        val page = Page().title(manifest.name).narrow()
        if (!manifest.description.isEmpty()) page.add(Item.text("", manifest.description))
        page.add(Item.text("", kinds(manifest) + (if (torrents) " · Torrent" else "") + (if (types(manifest.types).isEmpty()) "" else "  ·  " + types(manifest.types))
                + "\n" + (if (builtin) "Built in" else AddonUrl.redacted(url)) + (if (manifest.version.isEmpty()) "" else "  ·  Version " + manifest.version)))
        val actions: MutableList<Action> = ArrayList()
        if (present != null) {
            actions.add(Action("manage", Action.Style.PRIMARY, "Manage add-on", Runnable { val r = Route("Add-on"); r.value = present.id; nav.push(r) }).icon(R.drawable.ic_tv_settings)
                    .detail(if (present.enabled) "Installed" else "Off"))
            page.initialFocus = "manage"
        } else if (manifest.configurationRequired) {
            val configure = configurationAddress(url)
            page.add(Item.text("Set up first", configure))
            actions.addAll(setupActions(configure))
            page.initialFocus = "configured"
        } else {
            actions.add(Action("install-addon", Action.Style.PRIMARY, "Install", Runnable { nav.then(Callable {
                services.addons.install(url, manifest); null
            }, Runnable { tv.stream.app.ui.Sounds.play(tv.stream.app.ui.Sounds.Sound.INSTALLED); nav.refresh() }) }).icon(R.drawable.ic_tv_add))
            page.initialFocus = "install-addon"
        }
        if ((manifest.configurable || curated != null) && (present != null || !manifest.configurationRequired) && !builtin) {
            val configure = configurationAddress(url)
            actions.add(Action("set-up", Action.Style.SECONDARY, "Set up", Runnable { nav.choose("Set up " + manifest.name,
                    arrayOf("Open setup page", "Enter configured address"), Consumer { which ->
                        if (which == 0) nav.openWebsite(configure)
                        else nav.edit("Configured add-on address", "https://… or stremio://…/manifest.json", "", Consumer { address -> nav.previewAddon(address) })
                    }) }).icon(R.drawable.ic_tv_settings))
        }
        page.add(Item.actions("review-actions", actions))
        // Providers see what is asked of them; say so once, quietly.
        page.add(Item.text("", "The provider receives the titles you open" + (if (manifest.declares(AddonManifest.CATALOG)) " and your searches." else ".")))
        return page
    }

    private fun setupActions(configure: String): List<Action> {
        val actions: MutableList<Action> = ArrayList()
        actions.add(Action("configured", Action.Style.PRIMARY, "Enter configured address", Runnable { nav.edit("Configured add-on address",
                "https://… or stremio://…/manifest.json", "", Consumer { address -> nav.previewAddon(address) }) }).icon(R.drawable.ic_tv_link))
        actions.add(Action("configure-website", Action.Style.SECONDARY, "Open setup page", Runnable { nav.openWebsite(configure) }).icon(R.drawable.ic_tv_link))
        return actions
    }

    companion object {
        val FILTERS = arrayOf("All", "Sources", TorrentAddons.COLLECTION, "Catalogues", "Subtitles")
        private const val LIST_CACHE_MS: Long = 60_000

        /** Opens the store on one kind of add-on, such as Sources from a title that has none. */
        fun route(filter: String): Route { val r = Route("Add-ons"); r.extras["filter"] = filter; return r }
        /** The starter pack's review page: the store's page for installing Cinemeta, Internet Archive and OpenSubtitles together. */
        fun starters(): Route { val r = Route("Add-on review"); r.value = StarterAddons.PACK; return r }

        private fun matches(l: AddonListing, filter: String): Boolean {
            return matches(l.manifest, l.torrents(), filter)
        }
        private fun matches(m: AddonManifest, torrents: Boolean, filter: String): Boolean {
            return when (filter) {
                "Sources" -> m.declares(AddonManifest.STREAM)
                TorrentAddons.COLLECTION -> m.declares(AddonManifest.STREAM) && torrents
                "Catalogues" -> m.declares(AddonManifest.CATALOG) && !m.catalogs.isEmpty() || m.declares(AddonManifest.META)
                "Subtitles" -> m.declares(AddonManifest.SUBTITLES)
                else -> true
            }
        }

        @Throws(AddonException::class)
        fun configurationAddress(url: String): String {
            val curated: TorrentAddons.Entry? = TorrentAddons.find(url)
            return if (curated == null) AddonUrl(url).configuration() else curated.configureUrl
        }

        /** An installed add-on at this address, or another configuration of the same add-on. */
        fun installed(url: String, manifestId: String, installed: List<InstalledAddon>): InstalledAddon? {
            for (a in installed) if (a.address.manifest == url) return a
            for (a in installed) if (a.manifest.id == manifestId) return a
            return null
        }
        fun kinds(m: AddonManifest): String {
            val parts: MutableList<String> = ArrayList()
            if (m.declares(AddonManifest.STREAM)) parts.add("Sources")
            if (m.declares(AddonManifest.CATALOG) && !m.catalogs.isEmpty() || m.declares(AddonManifest.META)) parts.add("Catalogues")
            if (m.declares(AddonManifest.SUBTITLES)) parts.add("Subtitles")
            return if (parts.isEmpty()) "Other" else parts.joinToString(" · ")
        }
        fun supplies(m: AddonManifest): String {
            val parts: MutableList<String> = ArrayList()
            if (m.declares(AddonManifest.STREAM)) {
                val types = types(typesOf(m, AddonManifest.STREAM)).lowercase(java.util.Locale.ROOT)
                parts.add("Video sources" + (if (m.p2p) " (torrents)" else "") + " for " + (if (types.isEmpty()) "all titles" else types))
            }
            if (m.declares(AddonManifest.CATALOG) && !m.catalogs.isEmpty()) parts.add(m.catalogs.size.toString() + (if (m.catalogs.size == 1) " catalogue" else " catalogues"))
            if (m.declares(AddonManifest.META)) parts.add("Title details")
            if (m.declares(AddonManifest.SUBTITLES)) parts.add("Subtitles")
            if (m.declares(AddonManifest.ADDON_CATALOG)) parts.add("Lists of other add-ons for this store")
            if (!m.declares(AddonManifest.STREAM)) parts.add("No video: add a source add-on to play titles")
            return parts.joinToString("\n")
        }
        private fun typesOf(m: AddonManifest, resource: String): List<String> {
            for (r in m.resources) if (r.name == resource && !r.types.isEmpty()) return r.types
            return m.types
        }
        fun types(types: List<String>): String {
            val labels: MutableList<String> = ArrayList()
            for (t in types) if (labels.size < 3) labels.add(BrowseScreens.typeLabel(t))
            return labels.joinToString(", ")
        }
    }
}
