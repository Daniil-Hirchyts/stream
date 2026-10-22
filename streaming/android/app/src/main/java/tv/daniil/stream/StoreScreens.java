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
import tv.daniil.stream.data.InstalledAddon;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Item.Action;
import tv.daniil.stream.ui.Page;

/**
 * The add-on store: built-in add-ons and the lists published through {@code addon_catalog} (Stremio's Official and
 * Community lists by default). Every install goes through a review of the add-on's own current manifest.
 */
final class StoreScreens {
    static final String[] FILTERS = {"All", "Sources", "Catalogues", "Subtitles"};
    private final Navigator nav;
    private final TvApplication.Services services;

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

        List<InstalledAddon> installed = services.store.addons();
        if (!installed.isEmpty()) {
            page.add(Item.header("Installed", Integer.toString(installed.size())));
            for (InstalledAddon a : installed) page.add(Item.row("installed:" + a.id, R.drawable.ic_tv_extension, a.manifest.name,
                    (a.enabled ? "On" : "Off") + " · " + kinds(a.manifest), () -> { Route r = new Route("Add-on"); r.value = a.id; nav.push(r); }));
        }
        page.add(Item.row("store-address", R.drawable.ic_tv_link, "Install from address", "Any compatible manifest.json", nav::addAddon));

        AddonRepository.Result<AddonListing> found = services.addons.store(scope);
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
                new Action("refresh-store", Action.Style.SECONDARY, "Refresh", nav::refresh).icon(R.drawable.ic_tv_refresh)));
        page.add(Item.text("About these lists", "Built-in add-ons are part of this app. The Official and Community lists come from Stremio's public add-on "
                + "directory; their add-ons are run by third parties, who receive the titles you open and your searches."));
        return page;
    }
    private static boolean matches(AddonListing l, String filter) {
        switch (filter) {
            case "Sources": return l.sources();
            case "Catalogues": return l.catalogues();
            case "Subtitles": return l.subtitles();
            default: return true;
        }
    }
    private Item listing(AddonListing l, List<InstalledAddon> installed) {
        boolean present = installed(l.url, l.manifest.id, installed) != null;
        String status = present ? "Installed" : l.manifest.configurationRequired ? "Needs setup" : "Review";
        String types = types(l.manifest.types);
        return Item.source("store:" + l.url, l.manifest.name, kinds(l.manifest) + (types.isEmpty() ? "" : "  ·  " + types),
                l.manifest.description.replace('\n', ' '), status, true, () -> { Route r = new Route("Add-on review"); r.value = l.url; nav.push(r); });
    }

    /** The add-on's own current manifest, what it supplies and what it receives, then Install. */
    Page review(Route route, Cancellation scope) throws Exception {
        String url = route.value;
        AddonManifest manifest;
        try { manifest = services.addons.preview(url, scope); }
        catch (AddonException e) {
            if (e.kind == AddonException.Kind.CANCELLED) throw e;
            Page page = new Page().title("Add-on");
            page.add(Item.message(R.drawable.ic_tv_offline, "This add-on isn't responding", tv.daniil.stream.ui.Failures.message(e)
                    + " Its provider may be offline; try again later.",
                    new Action("retry-review", Action.Style.PRIMARY, "Retry", nav::refresh).icon(R.drawable.ic_tv_refresh),
                    new Action("review-back", Action.Style.SECONDARY, "Back", nav::back)));
            page.initialFocus = "retry-review";
            return page;
        }
        List<InstalledAddon> installed = services.store.addons();
        InstalledAddon present = installed(url, manifest.id, installed);
        boolean builtin = url.startsWith(AddonUrl.BUILTIN);
        Page page = new Page().panel(R.drawable.ic_tv_extension, manifest.name, manifest.description.isEmpty() ? "No description supplied." : manifest.description);
        page.add(Item.text("Supplies", supplies(manifest)));
        List<String> catalogues = new ArrayList<>();
        for (AddonManifest.Catalog c : manifest.catalogs) catalogues.add(BrowseScreens.shelfTitle(c));
        if (!catalogues.isEmpty()) page.add(Item.text("Catalogues", String.join(", ", catalogues)));
        page.add(Item.text("Provider", (builtin ? "Built into Daniil TV; it contacts the service it names." : AddonUrl.redacted(url) + " · run by a third party.")
                + (manifest.version.isEmpty() ? "" : "  Version " + manifest.version + ".")
                + "\nIt receives the titles you open" + (manifest.declares(AddonManifest.CATALOG) ? " and your searches." : ".")
                + (manifest.p2p ? "\nIts sources are peer-to-peer torrents: while you watch, this TV downloads from and uploads to other people." : "")));
        List<Action> actions = new ArrayList<>();
        if (present != null) {
            page.add(Item.notice(R.drawable.ic_tv_check, "Installed", present.enabled ? "It's on. Manage it to turn it off, reorder or remove it." : "It's installed but turned off."));
            actions.add(new Action("manage", Action.Style.PRIMARY, "Manage add-on", () -> { Route r = new Route("Add-on"); r.value = present.id; nav.push(r); }).icon(R.drawable.ic_tv_settings));
            page.initialFocus = "manage";
        } else if (manifest.configurationRequired) {
            String configure = url.substring(0, url.length() - "manifest.json".length()) + "configure";
            page.add(Item.notice(R.drawable.ic_tv_info, "Needs setup first", "Set it up on a phone or computer at " + configure
                    + ", then enter the manifest address it gives you."));
            actions.add(new Action("configured", Action.Style.PRIMARY, "Enter configured address", () -> nav.edit("Configured add-on address",
                    "https://…/manifest.json", "", nav::previewAddon)).icon(R.drawable.ic_tv_link));
            page.initialFocus = "configured";
        } else {
            actions.add(new Action("install-addon", Action.Style.PRIMARY, "Install", () -> nav.then(() -> {
                services.addons.install(url, manifest); return null;
            }, nav::refresh)).icon(R.drawable.ic_tv_add));
            page.initialFocus = "install-addon";
        }
        if (manifest.configurable && !manifest.configurationRequired && present == null && !builtin) page.add(Item.text("Options",
                "This add-on has optional settings at " + url.substring(0, url.length() - "manifest.json".length()) + "configure. Install a configured address to use them."));
        actions.add(new Action("review-back", Action.Style.SECONDARY, "Back", nav::back));
        page.add(Item.actions("review-actions", actions));
        return page;
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
