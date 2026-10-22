package tv.daniil.stream;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tv.daniil.stream.addon.AddonManifest;
import tv.daniil.stream.addon.AddonRepository;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.addon.Title;
import tv.daniil.stream.data.InstalledAddon;
import tv.daniil.stream.data.TvStore;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Item.Tile;
import tv.daniil.stream.ui.Page;

/** Home, Search, Library, History and catalogue browsing. Builders run on workers and block on providers. */
final class BrowseScreens {
    private static final int POSTER_COLUMNS = 6, LANDSCAPE_COLUMNS = 3;
    private final Navigator nav;
    private final TvApplication.Services services;

    BrowseScreens(Navigator nav, TvApplication.Services services) { this.nav = nav; this.services = services; }

    Page home(Cancellation scope) throws Exception {
        Page page = new Page();
        List<Item> shelves = new ArrayList<>();
        Map<String, Title> library = new LinkedHashMap<>();
        List<Tile> favourites = new ArrayList<>();
        for (TvStore.LibraryEntry e : services.store.library(false)) {
            library.put(e.title.key(), e.title);
            if (e.favourite) favourites.add(poster("favourite", e.title));
        }
        List<Tile> continuing = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (TvStore.Progress p : services.store.history(true)) {
            Title title = library.get(p.titleKey);
            if (title == null || seen.contains(p.titleKey)) continue;
            seen.add(p.titleKey); continuing.add(progressTile("continue", title, p));
        }
        if (!continuing.isEmpty()) shelves.add(Item.shelf("continue-watching", "Continue Watching", "", Tile.Style.LANDSCAPE, continuing));
        if (!favourites.isEmpty()) shelves.add(Item.shelf("favourites", "Favourites", "", Tile.Style.POSTER, favourites));
        List<AddonRepository.CatalogQuery> catalogues = services.addons.homeCatalogs();
        boolean failed = false, stale = false;
        if (!catalogues.isEmpty()) {
            AddonRepository.Result<Title> found = services.addons.catalogs(catalogues, scope);
            for (AddonRepository.CatalogQuery query : catalogues) {
                String key = query.addon.id + ":" + query.catalog.type + ":" + query.catalog.id;
                List<Tile> tiles = new ArrayList<>();
                for (Title t : found.byResource.getOrDefault(key, Collections.emptyList())) tiles.add(poster(key, t));
                if (!tiles.isEmpty()) shelves.add(Item.shelf("catalogue:" + key, shelfTitle(query.catalog), query.addon.manifest.name, Tile.Style.POSTER, tiles));
            }
            failed = !found.failures.isEmpty(); stale = found.stale;
        }
        if (shelves.isEmpty()) {
            page.add(Item.message(R.drawable.ic_tv_tv, "Make yourself at home",
                    catalogues.isEmpty() ? "Add a catalogue add-on such as Cinemeta from Add-ons, or import a torrent to start your library."
                            : "Your catalogue add-ons returned nothing right now. Refresh, or import a torrent.",
                    new Item.Action("home-add-addon", Item.Action.Style.PRIMARY, "Browse add-ons", () -> nav.root("Add-ons")).icon(R.drawable.ic_tv_extension),
                    new Item.Action("home-import", Item.Action.Style.SECONDARY, "Import a torrent", () -> nav.root("Imports")).icon(R.drawable.ic_tv_download)));
        } else {
            page.add(Item.spotlight(null));
            page.addAll(shelves);
        }
        if (failed) page.add(Item.notice(R.drawable.ic_tv_offline, stale ? "Showing saved catalogues" : "Some providers are unavailable",
                "Other rows are still available. Refresh to try again."));
        page.add(Item.actions("home-actions",
                new Item.Action("browse", Item.Action.Style.SECONDARY, "Browse catalogues", () -> nav.push(new Route("Catalogues"))).icon(R.drawable.ic_tv_list),
                new Item.Action("refresh", Item.Action.Style.ICON, "Refresh", nav::refresh).icon(R.drawable.ic_tv_refresh)));
        return page;
    }

    Page search(Route route, Cancellation scope) throws Exception {
        Page page = new Page();
        page.add(Item.field("query", "Search", route.value.isEmpty() ? "Films, series and local imports" : route.value,
                () -> nav.edit("Search", "Title", route.value, text -> { route.value = text; route.scroll = null; nav.refresh(); })));
        if (route.value.isEmpty()) {
            page.add(Item.message(R.drawable.ic_tv_search, "Find something to watch", "Search every catalogue add-on that supports it, plus your imports."));
            return page;
        }
        AddonRepository.Result<Title> found = services.addons.search(route.value, scope);
        List<Tile> tiles = new ArrayList<>();
        for (Title t : found.items) tiles.add(poster("search", t));
        if (!tiles.isEmpty()) {
            page.add(Item.header("Results", tiles.size() + (tiles.size() == 1 ? " title" : " titles")));
            page.addAll(Item.grid("search-results", Tile.Style.POSTER, POSTER_COLUMNS, tiles));
        } else page.add(Item.message(R.drawable.ic_tv_search, "No results", "Try another search, or add a catalogue add-on that supports search."));
        if (!found.failures.isEmpty()) page.add(Item.notice(R.drawable.ic_tv_offline, "Some providers could not answer", "Results from the other providers are shown."));
        page.add(Item.actions("search-actions", new Item.Action("retry-search", Item.Action.Style.SECONDARY, "Refresh search", nav::refresh).icon(R.drawable.ic_tv_refresh)));
        return page;
    }

    Page library() {
        Page page = new Page();
        List<Tile> favourites = new ArrayList<>(), others = new ArrayList<>();
        for (TvStore.LibraryEntry e : services.store.library(false)) {
            Tile tile = poster("library", e.title, e.favourite ? "Favourite" : subtitle(e.title));
            (e.favourite ? favourites : others).add(e.favourite ? tile.badge(R.drawable.ic_tv_heart) : tile);
        }
        if (favourites.isEmpty() && others.isEmpty()) {
            return page.add(Item.message(R.drawable.ic_tv_heart_outline, "Your library is empty",
                    "Titles you play, favourite or import appear here.",
                    new Item.Action("library-search", Item.Action.Style.PRIMARY, "Find titles", () -> nav.root("Search")).icon(R.drawable.ic_tv_search)));
        }
        if (!favourites.isEmpty()) { page.add(Item.header("Favourites")); page.addAll(Item.grid("favourites", Tile.Style.POSTER, POSTER_COLUMNS, favourites)); }
        if (!others.isEmpty()) { page.add(Item.header("Recently played")); page.addAll(Item.grid("played", Tile.Style.POSTER, POSTER_COLUMNS, others)); }
        return page;
    }

    Page history() {
        Page page = new Page();
        Map<String, Title> library = new LinkedHashMap<>();
        for (TvStore.LibraryEntry e : services.store.library(false)) library.put(e.title.key(), e.title);
        List<Tile> tiles = new ArrayList<>();
        for (TvStore.Progress p : services.store.history(false)) {
            Title title = library.get(p.titleKey);
            if (title != null) tiles.add(progressTile("history", title, p));
        }
        if (tiles.isEmpty()) return page.add(Item.message(R.drawable.ic_tv_movie, "No watch history yet", "Play a title and it appears here, ready to resume."));
        page.add(Item.header("Watch history", "Most recent first"));
        page.addAll(Item.grid("history", Tile.Style.LANDSCAPE, LANDSCAPE_COLUMNS, tiles));
        return page;
    }

    Page catalogues() {
        Page page = new Page().title("Browse");
        for (InstalledAddon a : services.store.addons()) if (a.enabled) for (AddonManifest.Catalog c : a.manifest.catalogs) {
            page.add(Item.row("catalogue:" + a.id + c.type + c.id, R.drawable.ic_tv_movie, shelfTitle(c), a.manifest.name, () -> {
                Route next = new Route("Catalogue"); next.value = new JSONArray().put(a.id).put(c.type).put(c.id).toString(); nav.push(next);
            }));
        }
        if (page.items.isEmpty()) page.add(Item.message(R.drawable.ic_tv_extension, "No catalogues", "Install a catalogue add-on from Add-ons.",
                new Item.Action("catalogues-add", Item.Action.Style.PRIMARY, "Browse add-ons", () -> nav.push(StoreScreens.route("Catalogues"))).icon(R.drawable.ic_tv_extension)));
        return page;
    }

    Page catalogue(Route route, Cancellation scope) throws Exception {
        JSONArray identity = new JSONArray(route.value);
        InstalledAddon addon = null; AddonManifest.Catalog catalog = null;
        for (InstalledAddon a : services.store.addons()) if (a.id.equals(identity.getString(0))) {
            addon = a;
            for (AddonManifest.Catalog c : a.manifest.catalogs) if (c.type.equals(identity.getString(1)) && c.id.equals(identity.getString(2))) catalog = c;
        }
        if (addon == null || catalog == null || !addon.enabled) {
            return new Page().title("Catalogue").add(Item.message(R.drawable.ic_tv_info, "Catalogue unavailable", "The add-on was disabled or removed."));
        }
        Page page = new Page().title(shelfTitle(catalog));
        List<Item.Action> filters = new ArrayList<>();
        boolean complete = true;
        for (AddonManifest.Extra e : catalog.extras) if (!e.name.equals("skip")) {
            if (e.required && !route.extras.containsKey(e.name)) complete = false;
            if (e.name.equals("search") && !e.required) continue;
            String value = route.extras.getOrDefault(e.name, e.required ? "Choose" : "Any");
            filters.add(new Item.Action("filter:" + e.name, Item.Action.Style.CHIP, capitalise(e.name), () -> filter(route, e)).detail(value)
                    .selected(route.extras.containsKey(e.name)));
        }
        if (!filters.isEmpty()) page.add(Item.chips("filters", "", filters));
        if (!complete) return page.add(Item.message(R.drawable.ic_tv_list, "Choose the required filters", "Then this catalogue will load."));
        Map<String, String> extras = new LinkedHashMap<>(route.extras);
        if (catalog.supports("skip")) extras.put("skip", Integer.toString(route.page * 20));
        AddonRepository.Result<Title> found = services.addons.catalogs(Collections.singletonList(new AddonRepository.CatalogQuery(addon, catalog, extras)), scope);
        List<Tile> tiles = new ArrayList<>();
        for (Title t : found.items) tiles.add(poster("catalogue", t));
        if (tiles.isEmpty()) page.add(Item.message(R.drawable.ic_tv_movie, "No titles on this page", "Change filters or return to the previous page."));
        else page.addAll(Item.grid("catalogue-grid", Tile.Style.POSTER, POSTER_COLUMNS, tiles));
        if (!found.failures.isEmpty()) page.add(Item.notice(R.drawable.ic_tv_offline, "Provider unavailable", found.stale ? "Showing saved entries." : "Try refreshing this catalogue."));
        List<Item.Action> paging = new ArrayList<>();
        if (catalog.supports("skip") && route.page > 0) paging.add(new Item.Action("previous-page", Item.Action.Style.SECONDARY, "Previous page", () -> { route.page--; route.scroll = null; nav.refresh(); }));
        if (catalog.supports("skip") && !tiles.isEmpty()) paging.add(new Item.Action("next-page", Item.Action.Style.SECONDARY, "Next page", () -> { route.page++; route.scroll = null; nav.refresh(); }));
        paging.add(new Item.Action("refresh-catalogue", Item.Action.Style.ICON, "Refresh", nav::refresh).icon(R.drawable.ic_tv_refresh));
        page.add(Item.actions("paging", paging));
        return page;
    }
    private void filter(Route route, AddonManifest.Extra e) {
        java.util.function.Consumer<String> set = value -> {
            if (value.isEmpty()) route.extras.remove(e.name); else route.extras.put(e.name, value);
            route.page = 0; nav.refresh();
        };
        if (e.options.isEmpty()) { nav.edit(capitalise(e.name), "Value", route.extras.getOrDefault(e.name, ""), set); return; }
        List<String> values = new ArrayList<>(e.options);
        if (!e.required) values.add(0, "Any");
        nav.choose(capitalise(e.name), values.toArray(new String[0]), i -> set.accept(!e.required && i == 0 ? "" : values.get(i)));
    }

    // Shared tile builders.
    Tile poster(String section, Title t) { return poster(section, t, subtitle(t)); }
    Tile poster(String section, Title t, String subtitle) {
        return new Tile(section + ":" + t.key(), Tile.Style.POSTER, t.name, subtitle, t.poster, t, () -> nav.openTitle(t, ""));
    }
    private Tile progressTile(String section, Title title, TvStore.Progress p) {
        String episode = TitleScreens.episodeName(title, p.video);
        String state = p.watched ? "Watched" : p.durationMs > 0 ? minutes(p.durationMs - p.positionMs) + " left" : "Resume at " + minutes(p.positionMs);
        String image = title.background != null ? title.background : title.poster;
        for (Title.Episode e : title.episodes) if (e.id.equals(p.video) && e.thumbnail != null) image = e.thumbnail;
        Tile tile = new Tile(section + ":" + title.key() + ":" + p.video, Tile.Style.LANDSCAPE, title.name,
                title.type.equals("series") ? episode + " · " + state : state, image, title, () -> nav.openTitle(title, p.video));
        if (p.watched) tile.badge(R.drawable.ic_tv_check);
        else if (p.durationMs > 0) tile.progress(Math.min(1f, p.positionMs / (float) p.durationMs));
        return tile;
    }
    static String subtitle(Title t) {
        if (!t.releaseInfo.isEmpty()) return t.releaseInfo;
        return t.provider.equals("local") ? "Import" : t.type.equals("series") ? "Series" : t.type.equals("movie") ? "Film" : "";
    }
    static String minutes(long ms) {
        long minutes = Math.max(1, Math.round(ms / 60_000.0));
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }
    /** "Popular" for films becomes "Popular Films", so a provider's film and series rows are distinguishable. */
    static String shelfTitle(AddonManifest.Catalog catalog) {
        String type = typeLabel(catalog.type), name = catalog.name.trim();
        if (name.isEmpty()) return type;
        String lower = name.toLowerCase(Locale.ROOT);
        if (type.isEmpty() || lower.contains(type.toLowerCase(Locale.ROOT)) || lower.contains(catalog.type.toLowerCase(Locale.ROOT))
                || (catalog.type.equals("movie") && lower.contains("film"))) return name;
        return name + " " + type;
    }
    static String typeLabel(String type) {
        switch (type) {
            case "movie": return "Films";
            case "series": return "Series";
            case "channel": return "Channels";
            case "tv": return "Live TV";
            default: return capitalise(type);
        }
    }
    static String capitalise(String value) { return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1); }
}
