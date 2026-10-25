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
    private static final int POSTER_COLUMNS = 6, LANDSCAPE_COLUMNS = 3, CATALOGUE_COLUMNS = 3, CATALOGUE_PAGE_SIZE = 24;
    private final Navigator nav;
    private final TvApplication.Services services;

    BrowseScreens(Navigator nav, TvApplication.Services services) { this.nav = nav; this.services = services; }

    Page home(Cancellation scope) throws Exception { return home(scope, ignored -> {}); }
    Page home(Cancellation scope, java.util.function.Consumer<Page> progress) throws Exception {
        List<AddonRepository.CatalogQuery> catalogues = services.addons.homeCatalogs();
        progress.accept(homeContent(catalogues, null));
        AddonRepository.Result<Title> found = services.addons.catalogs(catalogues, scope, partial -> progress.accept(homeContent(catalogues, partial)));
        return homeContent(catalogues, found);
    }
    private Page homeContent(List<AddonRepository.CatalogQuery> catalogues, AddonRepository.Result<Title> found) {
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
        boolean failed = false, stale = false;
        if (found != null) {
            for (AddonRepository.CatalogQuery query : catalogues) {
                String key = query.addon.id + ":" + query.catalog.type + ":" + query.catalog.id;
                List<Tile> tiles = new ArrayList<>();
                for (Title t : found.byResource.getOrDefault(key, Collections.emptyList())) tiles.add(poster(key, t));
                if (!tiles.isEmpty()) shelves.add(Item.shelf("catalogue:" + key, shelfTitle(query.catalog), "", Tile.Style.POSTER, tiles));
            }
            failed = !found.failures.isEmpty(); stale = found.stale;
        }
        if (shelves.isEmpty() && found == null && !catalogues.isEmpty()) page.add(Item.skeleton("Catalogues"));
        else if (shelves.isEmpty()) {
            page.add(Item.message(0, "Make yourself at home",
                    failed ? "Your catalogues are unavailable right now. Manage add-ons in Settings or try refreshing."
                            : "Films and series will appear here. Add catalogues or import a torrent in Settings."));
        } else {
            page.add(Item.spotlight(null));
            page.addAll(shelves);
        }
        if (failed && !shelves.isEmpty()) page.add(Item.notice(R.drawable.ic_tv_offline, stale ? "Showing saved catalogues" : "Some providers are unavailable", ""));
        // Browsing and refreshing need a catalogue; an empty Home shows only the way to add one.
        if (!catalogues.isEmpty()) page.tools = java.util.Arrays.asList(
                new Item.Action("browse", Item.Action.Style.SECONDARY, "Browse catalogues", () -> nav.push(new Route("Catalogues"))).icon(R.drawable.ic_tv_list),
                new Item.Action("refresh", Item.Action.Style.ICON, "Refresh", nav::refresh).icon(R.drawable.ic_tv_refresh));
        return page;
    }

    Page search(Route route, Cancellation scope) throws Exception { return search(route, scope, ignored -> {}); }
    Page search(Route route, Cancellation scope, java.util.function.Consumer<Page> progress) throws Exception {
        progress.accept(searchContent(route, null));
        if (route.value.trim().length() < 2) return searchContent(route, null);
        AddonRepository.Result<Title> found = services.addons.search(route.value, scope, partial -> {
            try { progress.accept(searchContent(route, partial)); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        return searchContent(route, found);
    }
    private Page searchContent(Route route, AddonRepository.Result<Title> found) throws Exception {
        route.extras.put("keyboardLanguages", services.store.preference("keyboardLanguages", "en"));
        if (!route.extras.containsKey("keyboardLanguage")) route.extras.put("keyboardLanguage", services.store.preference("keyboardLanguage", "en"));
        Page page = new Page();
        Item search = searchItem(route);
        page.add(search);
        page.initialFocus = "keyboard:" + tv.daniil.stream.ui.KeyboardLanguages.alphabet(search.keyboardLanguage).charAt(0);
        if (route.value.trim().length() < 2) return page;

        List<Tile> tiles = new ArrayList<>();
        String needle = route.value.trim().toLowerCase(Locale.ROOT);
        for (String id : services.store.importIds()) {
            org.json.JSONObject descriptor = services.store.importDescriptor(id);
            String name = descriptor.optString("name", "Import");
            if (name.toLowerCase(Locale.ROOT).contains(needle)) {
                Title imported = Title.parse("local", new org.json.JSONObject().put("id", id).put("type", "movie").put("name", name));
                tiles.add(poster("search-import", imported));
            }
        }
        if (found != null) for (Title t : found.items) tiles.add(poster("search", t));
        search.tiles = tiles;
        search.loading = found == null;
        search.detail = found != null && tiles.isEmpty() ? "No results" : "";
        if (found != null && !found.failures.isEmpty()) search.meta = "Some providers could not answer";
        return page;
    }

    Item searchLoading(Route route) {
        Item item = searchItem(route);
        item.loading = route.value.trim().length() >= 2;
        return item;
    }

    private Item searchItem(Route route) {
        Item item = Item.keyboard("keyboard", route.value, text -> { route.value = text; route.scroll = null; nav.searchInput(text); });
        item.keyboardLanguages = tv.daniil.stream.ui.KeyboardLanguages.parse(route.extras.getOrDefault("keyboardLanguages", "en"));
        String language = route.extras.getOrDefault("keyboardLanguage", "en");
        item.keyboardLanguage = item.keyboardLanguages.contains(language) ? language : item.keyboardLanguages.get(0);
        item.keyboardLanguageChanged = nav::keyboardLanguage; item.keyboardVoice = nav::voiceInput;
        item.action = () -> nav.push(new Route("Keyboard languages"));
        return item;
    }

    Page library() {
        Page page = new Page();
        List<Tile> favourites = new ArrayList<>(), others = new ArrayList<>();
        Map<String, Title> titles = new LinkedHashMap<>();
        for (TvStore.LibraryEntry e : services.store.library(false)) {
            titles.put(e.title.key(), e.title);
            Tile tile = poster("library", e.title);
            (e.favourite ? favourites : others).add(tile);
        }
        List<Tile> history = new ArrayList<>();
        for (TvStore.Progress progress : services.store.history(false)) {
            Title title = titles.get(progress.titleKey);
            if (title != null) history.add(progressTile("history", title, progress));
        }
        if (favourites.isEmpty() && others.isEmpty() && history.isEmpty()) {
            return page.add(Item.message(0, "Your library is empty",
                    "Films and series you play or favourite will appear here."));
        }
        if (!favourites.isEmpty()) { page.add(Item.header("Favourites")); page.addAll(Item.grid("favourites", Tile.Style.POSTER, POSTER_COLUMNS, favourites)); }
        if (!others.isEmpty()) { page.add(Item.header("Recently played")); page.addAll(Item.grid("played", Tile.Style.POSTER, POSTER_COLUMNS, others)); }
        if (!history.isEmpty()) {
            page.add(Item.header("Watch history"));
            page.addAll(Item.grid("history", Tile.Style.LANDSCAPE, LANDSCAPE_COLUMNS, history));
        }
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
        if (tiles.isEmpty()) return page.add(Item.message(R.drawable.ic_tv_movie, "No watch history yet", "Titles you play appear here."));
        page.add(Item.header("Watch history", "Most recent first"));
        page.addAll(Item.grid("history", Tile.Style.LANDSCAPE, LANDSCAPE_COLUMNS, tiles));
        return page;
    }

    /**
     * Every enabled catalogue as a title-only block, grouped by kind (Films, Series, …) in a fixed order.
     * Nothing is fetched until the viewer opens a block.
     */
    Page catalogues() {
        Page page = new Page().title("Browse");
        Map<String, List<Tile>> byType = new java.util.TreeMap<>(java.util.Comparator.comparingInt(BrowseScreens::typeOrder).thenComparing(t -> t));
        for (InstalledAddon a : services.store.addons()) if (a.enabled) for (AddonManifest.Catalog c : a.manifest.catalogs) {
            if (!browsable(c)) continue;
            String name = c.name.trim().isEmpty() ? typeLabel(c.type) : c.name.trim();
            byType.computeIfAbsent(c.type, type -> new ArrayList<>()).add(new Tile("catalogue:" + a.id + c.type + c.id, Tile.Style.LANDSCAPE,
                    name, "", null, null, () -> {
                Route next = new Route("Catalogue"); next.value = new JSONArray().put(a.id).put(c.type).put(c.id).toString(); nav.push(next);
            }).description(name).block());
        }
        if (byType.isEmpty()) return page.add(Item.message(R.drawable.ic_tv_extension, "No catalogues", "Install a catalogue add-on from Add-ons.",
                new Item.Action("catalogues-add", Item.Action.Style.PRIMARY, "Browse add-ons", () -> nav.push(StoreScreens.route("Catalogues"))).icon(R.drawable.ic_tv_extension)));
        int colorIndex = 0;
        for (Map.Entry<String, List<Tile>> group : byType.entrySet()) {
            for (Tile tile : group.getValue()) tile.colorIndex = colorIndex++;
            page.add(Item.header(typeLabel(group.getKey())));
            page.addAll(Item.grid("catalogues:" + group.getKey(), Tile.Style.LANDSCAPE, CATALOGUE_COLUMNS, group.getValue()));
        }
        return page;
    }
    /** Internal episode-query catalogues need app-supplied ID lists, not a viewer-entered filter. */
    static boolean browsable(AddonManifest.Catalog catalog) {
        for (AddonManifest.Extra extra : catalog.extras)
            if (extra.required && (extra.name.equals("lastVideosIds") || extra.name.equals("calendarVideosIds"))) return false;
        return true;
    }
    /** Films, then Series, then everything else. */
    private static int typeOrder(String type) { return type.equals("movie") ? 0 : type.equals("series") ? 1 : 2; }
    Page catalogue(Route route, Cancellation scope) throws Exception { return catalogue(route, scope, ignored -> {}); }
    Page catalogue(Route route, Cancellation scope, java.util.function.Consumer<Page> progress) throws Exception {
        JSONArray identity = new JSONArray(route.value);
        InstalledAddon addon = null; AddonManifest.Catalog catalog = null;
        for (InstalledAddon a : services.store.addons()) if (a.id.equals(identity.getString(0))) {
            addon = a;
            for (AddonManifest.Catalog c : a.manifest.catalogs) if (c.type.equals(identity.getString(1)) && c.id.equals(identity.getString(2))) catalog = c;
        }
        if (addon == null || catalog == null || !addon.enabled) {
            return new Page().title("Catalogue").add(Item.message(R.drawable.ic_tv_info, "Catalogue unavailable", "The add-on was disabled or removed."));
        }
        Page page = new Page();
        page.tools = Collections.singletonList(new Item.Action("refresh-catalogue", Item.Action.Style.ICON, "Refresh", nav::refresh).icon(R.drawable.ic_tv_refresh));
        page.add(Item.catalogueHeader(shelfTitle(catalog), typeLabel(catalog.type) + (catalog.supports("skip") ? " · Page " + (route.page + 1) : "")));
        // Filters with listed values are rows of chips on the page (no long list in a dialog); free text asks in a dialog.
        boolean complete = true;
        List<Item.Action> typed = new ArrayList<>();
        // A required filter with listed values starts on its first value, so the catalogue loads at once.
        Map<String, String> applied = new LinkedHashMap<>(route.extras);
        for (AddonManifest.Extra e : catalog.extras) if (!e.name.equals("skip")) {
            if (e.required && !applied.containsKey(e.name) && !e.options.isEmpty()) applied.put(e.name, e.options.get(0));
            if (e.required && !applied.containsKey(e.name)) complete = false;
            if (e.name.equals("search") && !e.required) continue;
            String chosen = applied.get(e.name);
            if (e.options.isEmpty()) {
                typed.add(new Item.Action("filter:" + e.name, Item.Action.Style.CHIP, capitalise(e.name), () -> filter(route, e))
                        .detail(chosen != null ? chosen : e.required ? "Choose" : "Any").selected(chosen != null));
                continue;
            }
            List<Item.Action> values = new ArrayList<>();
            if (!e.required) values.add(new Item.Action("filter:" + e.name + ":", Item.Action.Style.CHIP, "Any", () -> choose(route, e.name, ""))
                    .selected(chosen == null));
            for (String option : e.options) values.add(new Item.Action("filter:" + e.name + ":" + option, Item.Action.Style.CHIP, option,
                    () -> choose(route, e.name, option)).selected(option.equals(chosen)));
            // The chips speak for themselves; no "Genre" heading above them.
            page.add(Item.chips("filters:" + e.name, "", values));
        }
        if (!typed.isEmpty()) page.add(Item.chips("filters", "", typed));
        if (!complete) return page.add(Item.message(R.drawable.ic_tv_list, "Choose the required filters", "Then this catalogue will load."));
        Map<String, String> extras = applied;
        if (catalog.supports("skip")) extras.put("skip", Integer.toString(route.page * CATALOGUE_PAGE_SIZE));
        progress.accept(page.copy().add(Item.skeleton("Catalogue results")));
        AddonRepository.Result<Title> found = services.addons.catalogs(Collections.singletonList(new AddonRepository.CatalogQuery(addon, catalog, extras)), scope);
        List<Tile> tiles = new ArrayList<>();
        // Providers often return 100 items per request. Bound the visible page to the same stride as skip,
        // otherwise advancing one page repeats most of the previous page's posters.
        int limit = catalog.supports("skip") ? Math.min(CATALOGUE_PAGE_SIZE, found.items.size()) : found.items.size();
        for (int i = 0; i < limit; i++) tiles.add(poster("catalogue", found.items.get(i)));
        boolean paginated = catalog.supports("skip");
        if (tiles.isEmpty()) page.add(Item.message(R.drawable.ic_tv_movie, "No titles on this page",
                route.page > 0 ? "Go to the previous page or change the filters." : "Try other filters."));
        else page.addAll(Item.grid("catalogue-grid", Tile.Style.POSTER, POSTER_COLUMNS, tiles));
        if (!found.failures.isEmpty()) page.add(Item.notice(R.drawable.ic_tv_offline, "Provider unavailable", found.stale ? "Showing saved entries." : "Try refreshing this catalogue."));
        if (paginated) page.add(pagination(route, "bottom", !tiles.isEmpty()));
        page.initialFocus = tiles.isEmpty() ? "refresh-catalogue" : tiles.get(0).key;
        return page;
    }
    private Item pagination(Route route, String placement, boolean hasNext) {
        List<Item.Action> controls = new ArrayList<>();
        if (route.page > 0) controls.add(new Item.Action("previous-page:" + placement, Item.Action.Style.SECONDARY,
                "Previous page", () -> changePage(route, -1)).text("‹ Previous page"));
        if (hasNext) controls.add(new Item.Action("next-page:" + placement, Item.Action.Style.SECONDARY,
                "Next page", () -> changePage(route, 1)).text("Next page ›"));
        return Item.pagination("paging:" + placement, "Page " + (route.page + 1), controls);
    }
    private void changePage(Route route, int delta) {
        route.page = Math.max(0, route.page + delta); route.scroll = null;
        route.focus = ""; nav.refresh();
    }

    /** A free-text filter (no listed values). */
    private void filter(Route route, AddonManifest.Extra e) {
        nav.edit(capitalise(e.name), "Value", route.extras.getOrDefault(e.name, ""), value -> choose(route, e.name, value));
    }
    private void choose(Route route, String name, String value) {
        if (value.isEmpty()) route.extras.remove(name); else route.extras.put(name, value);
        route.page = 0; route.scroll = null; route.focus = ""; nav.refresh();
    }

    // Shared tile builders.
    Tile poster(String section, Title t) { return poster(section, t, ""); }
    Tile poster(String section, Title t, String subtitle) {
        return new Tile(section + ":" + t.key(), Tile.Style.POSTER, t.name, subtitle, t.poster, t, () -> nav.openTitle(t, ""));
    }
    private Tile progressTile(String section, Title title, TvStore.Progress p) {
        String episode = TitleScreens.episodeName(title, p.video);
        String state = p.watched ? "" : p.durationMs > 0 ? minutes(p.durationMs - p.positionMs) + " left" : "Resume at " + minutes(p.positionMs);
        String image = title.background != null ? title.background : title.poster;
        for (Title.Episode e : title.episodes) if (e.id.equals(p.video) && e.thumbnail != null) image = e.thumbnail;
        Tile tile = new Tile(section + ":" + title.key() + ":" + p.video, Tile.Style.LANDSCAPE, title.name,
                title.type.equals("series") ? episode + (state.isEmpty() ? "" : " · " + state) : state, image, title, () -> nav.openTitle(title, p.video));
        if (p.watched) { if (!section.equals("history")) tile.badge(R.drawable.ic_tv_check); }
        else if (p.durationMs > 0) tile.progress(Math.min(1f, p.positionMs / (float) p.durationMs));
        return tile;
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
