package tv.stream.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tv.stream.app.addon.AddonException;
import tv.stream.app.addon.AddonListing;
import tv.stream.app.addon.AddonManifest;
import tv.stream.app.addon.AddonRepository;
import tv.stream.app.addon.AddonUrl;
import tv.stream.app.addon.Cancellation;
import tv.stream.app.addon.TorrentAddons;
import tv.stream.app.data.InstalledAddon;
import tv.stream.app.ui.Item;
import tv.stream.app.ui.Item.Action;
import tv.stream.app.ui.Page;

/**
 * The add-on store: built-in add-ons, curated torrent scrapers and lists published through {@code addon_catalog} (Stremio's Official and
 * Community lists by default). Every install goes through a review of the add-on's own current manifest.
 */
final class StoreScreens {
    static final String[] FILTERS = {"All", "Sources", TorrentAddons.COLLECTION, "Catalogues", "Subtitles"};
    private static final long LIST_CACHE_MS = 60_000;
    private static final class Lists {
        final List<String> configuration;
        final AddonRepository.Result<AddonListing> result;
        final long loadedAt = android.os.SystemClock.elapsedRealtime();
        Lists(List<String> configuration, AddonRepository.Result<AddonListing> result) { this.configuration = configuration; this.result = result; }
    }
    private final Navigator nav;
    private final TvApplication.Services services;
    private volatile Lists lists;

    StoreScreens(Navigator nav, TvApplication.Services services) { this.nav = nav; this.services = services; }

    /** Opens the store on one kind of add-on, such as Sources from a title that has none. */
    static Route route(String filter) { Route r = new Route("Add-ons"); r.extras.put("filter", filter); return r; }
    /** The starter pack's review page: the store's page for installing Cinemeta, Internet Archive and OpenSubtitles together. */
    static Route starters() { Route r = new Route("Add-on review"); r.value = StarterAddons.PACK; return r; }

    Page store(Route route, Cancellation scope) throws Exception { return store(route, scope, ignored -> {}); }
    Page store(Route route, Cancellation scope, java.util.function.Consumer<Page> progress) throws Exception {
        String filter = route.extras.getOrDefault("filter", "All");
        Page page = new Page().title("Add-on store").narrow();
        page.tools = java.util.Collections.singletonList(new Action("refresh-store", Action.Style.ICON, "Refresh add-on lists", this::refreshLists).icon(R.drawable.ic_tv_refresh));
        List<Action> chips = new ArrayList<>();
        for (String f : FILTERS) chips.add(new Action("filter:" + f, Action.Style.CHIP, f, () -> {
            route.extras.put("filter", f); route.scroll = null; route.focus = "filter:" + f; nav.refresh();
        }).selected(f.equals(filter)));
        page.add(Item.chips("store-filters", "", chips));
        page.initialFocus = "filter:" + filter;
        // Installed add-ons are managed in Settings; here they show as Installed in their list.
        List<InstalledAddon> installed = services.store.addons();
        if (filter.equals("All") && missingStarters(installed) > 0) {
            page.add(Item.header("Start here"));
            page.add(Item.source("store:starters", "Starter add-ons", "Films and series · free classic films · subtitles", "", "", true,
                    () -> nav.push(starters())).status(-1));
        }

        // A filter changes what is displayed, not the directory request. Keep the lists briefly between renders,
        // invalidating them when installed providers change or the user explicitly refreshes.
        List<String> configuration = new ArrayList<>();
        for (InstalledAddon a : installed) configuration.add(a.id + ":" + a.enabled + ":" + a.manifest.json);
        Lists cached = lists;
        AddonRepository.Result<AddonListing> found;
        if (cached != null && cached.configuration.equals(configuration) && android.os.SystemClock.elapsedRealtime() - cached.loadedAt < LIST_CACHE_MS) found = cached.result;
        else {
            progress.accept(page.copy().add(Item.skeleton("Store results")));
            found = services.addons.store(scope);
            scope.check(); lists = new Lists(configuration, found);
        }
        int shown = 0;
        for (Map.Entry<String, List<AddonListing>> list : found.byResource.entrySet()) {
            List<Item> rows = new ArrayList<>();
            for (AddonListing l : list.getValue()) if (matches(l, filter)) rows.add(listing(l, installed));
            if (rows.isEmpty()) continue;
            page.add(Item.header(list.getKey()));
            page.addAll(rows); shown += rows.size();
        }
        if (shown == 0) page.add(Item.message(R.drawable.ic_tv_extension, "Nothing listed", ""));
        if (!found.failures.isEmpty()) page.add(Item.notice(R.drawable.ic_tv_offline, found.stale ? "Showing saved lists" : "Some lists are unavailable", ""));
        return page;
    }
    private void refreshLists() { lists = null; nav.refresh(); }
    private static boolean matches(AddonListing l, String filter) {
        return matches(l.manifest, l.torrents(), filter);
    }
    private static boolean matches(AddonManifest m, boolean torrents, String filter) {
        switch (filter) {
            case "Sources": return m.declares(AddonManifest.STREAM);
            case TorrentAddons.COLLECTION: return m.declares(AddonManifest.STREAM) && torrents;
            case "Catalogues": return m.declares(AddonManifest.CATALOG) && !m.catalogs.isEmpty() || m.declares(AddonManifest.META);
            case "Subtitles": return m.declares(AddonManifest.SUBTITLES);
            default: return true;
        }
    }
    private Item listing(AddonListing l, List<InstalledAddon> installed) {
        boolean present = installed(l.url, l.manifest.id, installed) != null;
        String status = present ? "Installed" : l.manifest.configurationRequired ? "Needs setup" : "Review";
        String types = types(l.manifest.types);
        // One line of what it adds; the description waits for the review page.
        return Item.source("store:" + l.url, l.manifest.name, kinds(l.manifest) + (l.torrents() ? " · Torrent" : "") + (types.isEmpty() ? "" : "  ·  " + types),
                "", present ? status : l.manifest.configurationRequired ? status : "", true, () -> { Route r = new Route("Add-on review"); r.value = l.url; nav.push(r); })
                .status(present ? R.drawable.ic_tv_check : l.manifest.configurationRequired ? R.drawable.ic_tv_info : -1);
    }

    private int missingStarters(List<InstalledAddon> installed) {
        int missing = 0;
        for (String url : services.starters) if (installed(url, "", installed) == null) missing++;
        return missing;
    }
    /** What the starter pack adds, then one Install for all of it (each add-on's manifest is read when it installs). */
    private Page starterReview() {
        Page page = new Page().title("Starter add-ons").narrow();
        List<InstalledAddon> installed = services.store.addons();
        for (String url : services.starters) {
            String[] about = StarterAddons.describe(url);
            boolean present = installed(url, "", installed) != null;
            page.add(Item.row("starter:" + url, present ? R.drawable.ic_tv_check : R.drawable.ic_tv_extension, about[0], present ? "Installed" : about[1], null));
        }
        if (missingStarters(installed) > 0) {
            page.add(Item.actions("starter-actions", new Action("install-starters", Action.Style.PRIMARY, "Install", () -> nav.installStarters(services.starters))
                    .icon(R.drawable.ic_tv_add)));
            page.initialFocus = "install-starters";
        }
        page.add(Item.text("", "Each provider receives the titles you open and your searches."));
        return page;
    }

    /** The add-on's own current manifest, what it supplies and what it receives, then Install. */
    Page review(Route route, Cancellation scope) throws Exception {
        String url = route.value;
        if (url.equals(StarterAddons.PACK)) return starterReview();
        TorrentAddons.Entry curated = TorrentAddons.find(url);
        AddonManifest manifest;
        try { manifest = services.addons.preview(url, scope); }
        catch (AddonException e) {
            if (e.kind == AddonException.Kind.CANCELLED) throw e;
            Page page = new Page().title("Add-on").narrow();
            page.add(Item.message(R.drawable.ic_tv_offline, "This add-on isn't responding", tv.stream.app.ui.Failures.message(e),
                    new Action("retry-review", Action.Style.PRIMARY, "Retry", nav::refresh).icon(R.drawable.ic_tv_refresh)));
            if (curated != null) page.add(Item.actions("offline-setup", setupActions(curated.configureUrl)));
            page.initialFocus = "retry-review";
            return page;
        }
        List<InstalledAddon> installed = services.store.addons();
        InstalledAddon present = installed(url, manifest.id, installed);
        boolean builtin = url.startsWith(AddonUrl.BUILTIN);
        boolean torrents = manifest.p2p || curated != null;
        // What it is, what it adds and who runs it: three short lines, then the one decision.
        Page page = new Page().title(manifest.name).narrow();
        if (!manifest.description.isEmpty()) page.add(Item.text("", manifest.description));
        page.add(Item.text("", kinds(manifest) + (torrents ? " · Torrent" : "") + (types(manifest.types).isEmpty() ? "" : "  ·  " + types(manifest.types))
                + "\n" + (builtin ? "Built in" : AddonUrl.redacted(url)) + (manifest.version.isEmpty() ? "" : "  ·  Version " + manifest.version)));
        List<Action> actions = new ArrayList<>();
        if (present != null) {
            actions.add(new Action("manage", Action.Style.PRIMARY, "Manage add-on", () -> { Route r = new Route("Add-on"); r.value = present.id; nav.push(r); }).icon(R.drawable.ic_tv_settings)
                    .detail(present.enabled ? "Installed" : "Off"));
            page.initialFocus = "manage";
        } else if (manifest.configurationRequired) {
            String configure = configurationAddress(url);
            page.add(Item.text("Set up first", configure));
            actions.addAll(setupActions(configure));
            page.initialFocus = "configured";
        } else {
            actions.add(new Action("install-addon", Action.Style.PRIMARY, "Install", () -> nav.then(() -> {
                services.addons.install(url, manifest); return null;
            }, () -> { tv.stream.app.ui.Sounds.play(tv.stream.app.ui.Sounds.Sound.INSTALLED); nav.refresh(); })).icon(R.drawable.ic_tv_add));
            page.initialFocus = "install-addon";
        }
        if ((manifest.configurable || curated != null) && (present != null || !manifest.configurationRequired) && !builtin) {
            String configure = configurationAddress(url);
            actions.add(new Action("set-up", Action.Style.SECONDARY, "Set up", () -> nav.choose("Set up " + manifest.name,
                    new String[]{"Open setup page", "Enter configured address"}, which -> {
                        if (which == 0) nav.openWebsite(configure);
                        else nav.edit("Configured add-on address", "https://… or stremio://…/manifest.json", "", nav::previewAddon);
                    })).icon(R.drawable.ic_tv_settings));
        }
        page.add(Item.actions("review-actions", actions));
        // Providers see what is asked of them; say so once, quietly.
        page.add(Item.text("", "The provider receives the titles you open" + (manifest.declares(AddonManifest.CATALOG) ? " and your searches." : ".")));
        return page;
    }

    private List<Action> setupActions(String configure) {
        List<Action> actions = new ArrayList<>();
        actions.add(new Action("configured", Action.Style.PRIMARY, "Enter configured address", () -> nav.edit("Configured add-on address",
                "https://… or stremio://…/manifest.json", "", nav::previewAddon)).icon(R.drawable.ic_tv_link));
        actions.add(new Action("configure-website", Action.Style.SECONDARY, "Open setup page", () -> nav.openWebsite(configure)).icon(R.drawable.ic_tv_link));
        return actions;
    }
    static String configurationAddress(String url) throws AddonException {
        TorrentAddons.Entry curated = TorrentAddons.find(url);
        return curated == null ? new AddonUrl(url).configuration() : curated.configureUrl;
    }

    /** An installed add-on at this address, or another configuration of the same add-on. */
    static InstalledAddon installed(String url, String manifestId, List<InstalledAddon> installed) {
        for (InstalledAddon a : installed) if (a.address.manifest.equals(url)) return a;
        for (InstalledAddon a : installed) if (a.manifest.id.equals(manifestId)) return a;
        return null;
    }
    static String kinds(AddonManifest m) {
        List<String> parts = new ArrayList<>();
        if (m.declares(AddonManifest.STREAM)) parts.add("Sources");
        if (m.declares(AddonManifest.CATALOG) && !m.catalogs.isEmpty() || m.declares(AddonManifest.META)) parts.add("Catalogues");
        if (m.declares(AddonManifest.SUBTITLES)) parts.add("Subtitles");
        return parts.isEmpty() ? "Other" : String.join(" · ", parts);
    }
    static String supplies(AddonManifest m) {
        List<String> parts = new ArrayList<>();
        if (m.declares(AddonManifest.STREAM)) {
            String types = types(typesOf(m, AddonManifest.STREAM)).toLowerCase(java.util.Locale.ROOT);
            parts.add("Video sources" + (m.p2p ? " (torrents)" : "") + " for " + (types.isEmpty() ? "all titles" : types));
        }
        if (m.declares(AddonManifest.CATALOG) && !m.catalogs.isEmpty()) parts.add(m.catalogs.size() + (m.catalogs.size() == 1 ? " catalogue" : " catalogues"));
        if (m.declares(AddonManifest.META)) parts.add("Title details");
        if (m.declares(AddonManifest.SUBTITLES)) parts.add("Subtitles");
        if (m.declares(AddonManifest.ADDON_CATALOG)) parts.add("Lists of other add-ons for this store");
        if (!m.declares(AddonManifest.STREAM)) parts.add("No video: add a source add-on to play titles");
        return String.join("\n", parts);
    }
    private static List<String> typesOf(AddonManifest m, String resource) {
        for (AddonManifest.Resource r : m.resources) if (r.name.equals(resource) && !r.types.isEmpty()) return r.types;
        return m.types;
    }
    static String types(List<String> types) {
        List<String> labels = new ArrayList<>();
        for (String t : types) if (labels.size() < 3) labels.add(BrowseScreens.typeLabel(t));
        return String.join(", ", labels);
    }
}
