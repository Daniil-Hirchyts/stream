package tv.daniil.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tv.daniil.stream.addon.AddonException;
import tv.daniil.stream.addon.AddonListing;
import tv.daniil.stream.addon.AddonManifest;
import tv.daniil.stream.addon.AddonRepository;
import tv.daniil.stream.addon.AddonUrl;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.addon.TorrentAddons;
import tv.daniil.stream.data.InstalledAddon;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Item.Action;
import tv.daniil.stream.ui.Page;

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

    Page store(Route route, Cancellation scope) throws Exception {
        String filter = route.extras.getOrDefault("filter", "All");
        Page page = new Page().panel(R.drawable.ic_tv_extension, "Add-ons",
                "Catalogues such as Cinemeta describe films and series. Sources find the video to play, and subtitle add-ons add captions. "
                        + "Nothing installs until you review it.");
        List<Action> chips = new ArrayList<>();
        for (String f : FILTERS) chips.add(new Action("filter:" + f, Action.Style.CHIP, f, () -> {
            route.extras.put("filter", f); route.scroll = null; route.focus = "filter:" + f; nav.refresh();
        }).selected(f.equals(filter)));
        page.add(Item.chips("store-filters", "", chips));
        page.initialFocus = "filter:" + filter;
        if (filter.equals(TorrentAddons.COLLECTION)) page.add(Item.text("Torrent sources",
                "These add-ons find torrents for films and series. Daniil TV plays torrent results directly on this TV; no debrid account is needed for P2P. "
                        + "Provider settings can also return cloud streams. Review an add-on to install it or change its setup."));

        List<InstalledAddon> installed = services.store.addons();
        List<InstalledAddon> matchingInstalled = new ArrayList<>();
        for (InstalledAddon a : installed) if (matches(a.manifest, a.manifest.p2p || TorrentAddons.find(a.address.manifest) != null, filter)) matchingInstalled.add(a);
        if (!matchingInstalled.isEmpty()) {
            page.add(Item.header("Installed", Integer.toString(matchingInstalled.size())));
            for (InstalledAddon a : matchingInstalled) page.add(Item.row("installed:" + a.id, R.drawable.ic_tv_extension, a.manifest.name,
                    (a.enabled ? "On" : "Off") + " · " + kinds(a.manifest), () -> { Route r = new Route("Add-on"); r.value = a.id; nav.push(r); }));
        }
        page.add(Item.row("store-address", R.drawable.ic_tv_link, "Install from address", "Any compatible manifest.json", nav::addAddon));

        // A filter changes what is displayed, not the directory request. Keep the lists briefly between renders,
        // invalidating them when installed providers change or the user explicitly refreshes.
        List<String> configuration = new ArrayList<>();
        for (InstalledAddon a : installed) configuration.add(a.id + ":" + a.enabled + ":" + a.manifest.json);
        Lists cached = lists;
        AddonRepository.Result<AddonListing> found;
        if (cached != null && cached.configuration.equals(configuration) && android.os.SystemClock.elapsedRealtime() - cached.loadedAt < LIST_CACHE_MS) found = cached.result;
        else {
            found = services.addons.store(scope);
            scope.check(); lists = new Lists(configuration, found);
        }
        int shown = 0;
        for (Map.Entry<String, List<AddonListing>> list : found.byResource.entrySet()) {
            List<Item> rows = new ArrayList<>();
            for (AddonListing l : list.getValue()) if (matches(l, filter)) rows.add(listing(l, installed));
            if (rows.isEmpty()) continue;
            page.add(Item.header(list.getKey(), rows.size() + (rows.size() == 1 ? " add-on" : " add-ons")));
            page.addAll(rows); shown += rows.size();
        }
        if (shown == 0) page.add(Item.text("", "No " + filter.toLowerCase(java.util.Locale.ROOT) + " add-ons are listed right now."));
        if (!found.failures.isEmpty()) page.add(Item.notice(R.drawable.ic_tv_offline, found.stale ? "Showing saved add-on lists" : "Some add-on lists are unavailable",
                "Check the connection and refresh. You can still install an add-on from its address.",
                new Action("refresh-store", Action.Style.SECONDARY, "Refresh", this::refreshLists).icon(R.drawable.ic_tv_refresh)));
        else page.add(Item.row("refresh-store", R.drawable.ic_tv_refresh, "Refresh add-on lists", "Check for updates", this::refreshLists));
        page.add(Item.text("About these lists", "Built-in add-ons are part of this app. The Torrent/P2P section is included with Daniil TV and stays available if the directory is offline. "
                + "The Official and Community lists come from Stremio's public add-on directory. Third-party providers receive the titles you open and your searches."));
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
        return Item.source("store:" + l.url, l.manifest.name, kinds(l.manifest) + (l.torrents() ? " · Torrent/P2P" : "") + (types.isEmpty() ? "" : "  ·  " + types),
                l.manifest.description.replace('\n', ' '), status, true, () -> { Route r = new Route("Add-on review"); r.value = l.url; nav.push(r); });
    }

    /** The add-on's own current manifest, what it supplies and what it receives, then Install. */
    Page review(Route route, Cancellation scope) throws Exception {
        String url = route.value;
        TorrentAddons.Entry curated = TorrentAddons.find(url);
        AddonManifest manifest;
        try { manifest = services.addons.preview(url, scope); }
        catch (AddonException e) {
            if (e.kind == AddonException.Kind.CANCELLED) throw e;
            Page page = new Page().title("Add-on");
            page.add(Item.message(R.drawable.ic_tv_offline, "This add-on isn't responding", tv.daniil.stream.ui.Failures.message(e)
                    + " Its provider may be offline; try again later.",
                    new Action("retry-review", Action.Style.PRIMARY, "Retry", nav::refresh).icon(R.drawable.ic_tv_refresh),
                    new Action("review-back", Action.Style.SECONDARY, "Back", nav::back)));
            if (curated != null) {
                page.add(Item.text("Provider setup", "You can also set up " + curated.listing.manifest.name + " at " + curated.configureUrl + " and enter the manifest address it gives you."));
                page.add(Item.actions("offline-setup", setupActions(curated.configureUrl)));
            }
            page.initialFocus = "retry-review";
            return page;
        }
        List<InstalledAddon> installed = services.store.addons();
        InstalledAddon present = installed(url, manifest.id, installed);
        boolean builtin = url.startsWith(AddonUrl.BUILTIN);
        boolean torrents = manifest.p2p || curated != null;
        Page page = new Page().panel(R.drawable.ic_tv_extension, manifest.name, manifest.description.isEmpty() ? "No description supplied." : manifest.description);
        page.add(Item.text("Supplies", supplies(manifest)));
        List<String> catalogues = new ArrayList<>();
        for (AddonManifest.Catalog c : manifest.catalogs) catalogues.add(BrowseScreens.shelfTitle(c));
        if (!catalogues.isEmpty()) page.add(Item.text("Catalogues", String.join(", ", catalogues)));
        page.add(Item.text("Provider", (builtin ? "Built into Daniil TV; it contacts the service it names." : AddonUrl.redacted(url) + " · run by a third party.")
                + (manifest.version.isEmpty() ? "" : "  Version " + manifest.version + ".")
                + "\nIt receives the titles you open" + (manifest.declares(AddonManifest.CATALOG) ? " and your searches." : ".")
                + (torrents ? "\nTorrent sources play directly on this TV: while you watch, it downloads from and uploads to peers. Provider settings may also return HTTP streams." : "")));
        List<Action> actions = new ArrayList<>();
        if (present != null) {
            page.add(Item.notice(R.drawable.ic_tv_check, "Installed", present.enabled ? "It's on. Manage it to turn it off, reorder or remove it." : "It's installed but turned off."));
            actions.add(new Action("manage", Action.Style.PRIMARY, "Manage add-on", () -> { Route r = new Route("Add-on"); r.value = present.id; nav.push(r); }).icon(R.drawable.ic_tv_settings));
            page.initialFocus = "manage";
        } else if (manifest.configurationRequired) {
            String configure = configurationAddress(url);
            page.add(Item.notice(R.drawable.ic_tv_info, "Needs setup first", "Set it up on a phone or computer at " + configure
                    + ", then enter the manifest address it gives you."));
            actions.addAll(setupActions(configure));
            page.initialFocus = "configured";
        } else {
            actions.add(new Action("install-addon", Action.Style.PRIMARY, "Install", () -> nav.then(() -> {
                services.addons.install(url, manifest); return null;
            }, nav::refresh)).icon(R.drawable.ic_tv_add));
            page.initialFocus = "install-addon";
        }
        actions.add(new Action("review-back", Action.Style.SECONDARY, "Back", nav::back));
        page.add(Item.actions("review-actions", actions));
        if ((manifest.configurable || curated != null) && (present != null || !manifest.configurationRequired) && !builtin) {
            String configure = configurationAddress(url);
            page.add(Item.text("Provider setup", "Choose languages, quality or playback services at " + configure
                    + ". For direct torrent playback, choose the provider's P2P option if it offers one, then enter its configured manifest address. "
                    + "Availability and account requirements depend on the provider."));
            page.add(Item.actions("optional-setup", setupActions(configure)));
        }
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
