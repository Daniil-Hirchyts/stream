package tv.daniil.stream;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import tv.daniil.stream.addon.AddonRepository;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.addon.MediaSource;
import tv.daniil.stream.addon.Title;
import tv.daniil.stream.data.InstalledAddon;
import tv.daniil.stream.data.TvStore;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Item.Action;
import tv.daniil.stream.ui.Item.Tile;
import tv.daniil.stream.ui.Page;
import tv.daniil.stream.ui.Sounds;

/** Title details (with seasons and episodes) and explicit source selection. */
final class TitleScreens {
    private final Navigator nav;
    private final TvApplication.Services services;

    TitleScreens(Navigator nav, TvApplication.Services services) { this.nav = nav; this.services = services; }

    Page details(Route route, Cancellation scope) throws Exception { return details(route, scope, ignored -> {}); }
    Page overview(Route route) throws Exception { return detailContent(route, ""); }
    Page details(Route route, Cancellation scope, java.util.function.Consumer<Page> progress) throws Exception {
        Page initial = detailContent(route, ""); progress.accept(initial);
        if (route.title.provider.equals("local")) return initial;
        PlaybackPreparation.Entry entry = services.preparation.prepare(route.title, route.video);
        route.title = entry.awaitMetadata(scope);
        scope.check();
        Page enriched = detailContent(route, "");
        scope.check(); services.preparation.prepare(route.title, route.video);
        return enriched;
    }
    private Page detailContent(Route route, String note) throws Exception {
        Title title = route.title;
        boolean local = title.provider.equals("local");
        route.title = title;
        Title chosen = title;
        boolean favourite = false;
        for (TvStore.LibraryEntry e : services.store.library(true)) if (e.title.key().equals(title.key())) favourite = true;
        // The selected video: explicit, else the last one played, else the first episode.
        String video = route.video.isEmpty() ? title.id : route.video;
        if (route.video.isEmpty()) for (TvStore.Progress p : services.store.history(true)) if (p.titleKey.equals(title.key())) { video = p.video; break; }
        if (title.type.equals("series") && video.equals(title.id) && !title.episodes.isEmpty()) video = title.episodes.get(0).id;
        route.video = video;
        String selected = video;
        TvStore.Progress progress = services.store.progress(title.key(), video);
        boolean resume = progress != null && !progress.watched && progress.positionMs > 0;

        Page page = detailsPage(title, note);
        // Primary actions retain labels; compact toggles show their state and keep accessible action names.
        List<Action> actions = new ArrayList<>();
        boolean series = title.type.equals("series") && !title.episodes.isEmpty();
        if (!title.type.equals("series") || !video.equals(title.id)) actions.add(new Action("play", Action.Style.PRIMARY, resume ? "Resume" : "Play", () -> nav.play(chosen, selected, "")).icon(R.drawable.ic_tv_play)
                .detail(series ? shortEpisode(title, video) : ""));
        if (!local && (!title.type.equals("series") || !video.equals(title.id))) actions.add(new Action("sources", Action.Style.ICON, "Sources", () -> nav.sources(chosen, selected)).icon(R.drawable.ic_tv_list));
        boolean isFavourite = favourite, watched = progress != null && progress.watched;
        actions.add(new Action("favourite", Action.Style.ICON, isFavourite ? "Remove favourite" : "Add favourite",
                () -> { Sounds.play(isFavourite ? Sounds.Sound.FAVOURITE_OFF : Sounds.Sound.FAVOURITE_ON); nav.mutate(() -> { services.store.favourite(chosen, !isFavourite); return null; }); })
                .icon(isFavourite ? R.drawable.ic_tv_heart : R.drawable.ic_tv_heart_outline).selected(isFavourite));
        actions.add(new Action("watched", Action.Style.ICON, watched ? "Mark unwatched" : "Mark watched",
                () -> { Sounds.play(watched ? Sounds.Sound.WATCHED_OFF : Sounds.Sound.WATCHED_ON); nav.mutate(() -> { services.store.watched(chosen, selected, !watched); return null; }); })
                .icon(R.drawable.ic_tv_check).selected(watched));
        if (local) {
            actions.add(new Action("rename", Action.Style.SECONDARY, "Rename import", () -> nav.edit("Rename import", "Title", chosen.name, name -> nav.mutate(() -> {
                services.imports.rename(chosen.id, name);
                route.title = Title.parse("local", new JSONObject().put("id", chosen.id).put("type", chosen.type).put("name", name));
                return null;
            }))).icon(R.drawable.ic_tv_edit));
            actions.add(new Action("remove-import", Action.Style.SECONDARY, "Remove import", () -> nav.then(() -> { services.imports.remove(chosen.id); return null; }, nav::back))
                    .icon(R.drawable.ic_tv_delete));
        } else {
            actions.add(new Action("more", Action.Style.ICON, "More", () -> nav.choose(chosen.name, new String[]{"Remove from library"}, which -> {
                nav.then(() -> { services.store.removeLibrary(chosen.key()); return null; }, () -> nav.root("Library"));
            })).icon(R.drawable.ic_tv_more));
        }
        page.add(Item.actions("title-actions", actions));
        page.initialFocus = "play";
        if (!local && noSources(title, video)) {
            page.add(Item.notice(R.drawable.ic_tv_info, Availability.NO_PROVIDER, "",
                    new Action("add-source", Action.Style.SECONDARY, "Get add-ons", () -> nav.push(StoreScreens.route("Sources"))).icon(R.drawable.ic_tv_extension)));
        }
        if (title.type.equals("series")) episodes(page, route, title);
        if (!title.cast.isEmpty()) page.add(Item.text("Starring", String.join(", ", title.cast.subList(0, Math.min(4, title.cast.size())))));
        return page;
    }

    private void episodes(Page page, Route route, Title title) {
        if (title.episodes.isEmpty()) {
            page.add(Item.notice(R.drawable.ic_tv_info, "No episodes listed", "Try Refresh details."));
            return;
        }
        SortedSet<Integer> seasons = new TreeSet<>();
        for (Title.Episode e : title.episodes) seasons.add(e.season);
        if (route.season == -1 || !seasons.contains(route.season)) {
            route.season = seasons.first();
            for (Title.Episode e : title.episodes) if (e.id.equals(route.video)) route.season = e.season;
        }
        List<Action> tabs = new ArrayList<>();
        Map<String, List<Item>> panes = new java.util.LinkedHashMap<>();
        Map<String, TvStore.Progress> saved = new java.util.HashMap<>();
        for (TvStore.Progress progress : services.store.history(false))
            if (progress.titleKey.equals(title.key())) saved.put(progress.video, progress);
        for (int season : seasons) {
            String key = "season:" + season;
            tabs.add(new Action(key, Action.Style.CHIP, season == 0 ? "Specials" : "Season " + season, () -> {})
                    .selected(season == route.season));
            List<Tile> tiles = new ArrayList<>();
            for (Title.Episode e : title.episodes) if (e.season == season) {
                TvStore.Progress p = saved.get(e.id);
                boolean watched = p != null && p.watched;
                boolean resume = p != null && !watched && p.positionMs > 0;
                Tile tile = new Tile("episode:" + e.id, Tile.Style.LANDSCAPE, e.name, "",
                        e.thumbnail != null ? e.thumbnail : title.background, null, () -> nav.choose(e.name,
                        new String[]{resume ? "Resume" : "Play", "Choose source", watched ? "Mark unwatched" : "Mark watched"}, which -> {
                            if (!route.video.equals(e.id)) route.cache = null;
                            route.video = e.id;
                            if (which == 0) nav.play(title, e.id, "");
                            else if (which == 1) nav.sources(title, e.id);
                            else { Sounds.play(watched ? Sounds.Sound.WATCHED_OFF : Sounds.Sound.WATCHED_ON); nav.mutate(() -> { services.store.watched(title, e.id, !watched); return null; }); }
                        })).episode(e.number, e.overview).released(episodeDate(e.released)).description("Episode " + e.number + ". " + e.name
                                + (watched ? ". Watched" : ""));
                if (watched) tile.badge(R.drawable.ic_tv_check);
                else if (p != null && p.durationMs > 0) tile.progress(p.positionMs / (float) p.durationMs);
                tiles.add(tile);
            }
            panes.put(key, java.util.Collections.singletonList(Item.shelf("episodes:" + season, "", "", Tile.Style.LANDSCAPE, tiles)));
        }
        page.add(Item.episodes(tabs, panes, "season:" + route.season,
                key -> route.season = Integer.parseInt(key.substring("season:".length()))));
    }

    /** Keep the provider's broadcast day; converting a midnight UTC stamp can move it to yesterday. */
    static String episodeDate(String released) {
        if (released == null || released.length() < 10) return "";
        try {
            return java.time.LocalDate.parse(released.substring(0, 10))
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", Locale.getDefault()));
        } catch (java.time.DateTimeException invalid) { return ""; }
    }

    /** True when no enabled add-on could supply streams and the metadata carries none itself. */
    private boolean noSources(Title title, String video) {
        if (!services.addons.providers("stream", title.type, video).isEmpty()) return false;
        for (Title.Episode e : title.episodes) if (e.id.equals(video) && !e.streams.isEmpty()) return false;
        return true;
    }

    /** What the providers answered for one video; reused for ten minutes while the viewer re-sorts. */
    private static final class Fetched {
        final long configuration;
        final List<InstalledAddon> providers; @androidx.annotation.Nullable final AddonRepository.Result<MediaSource> found;
        Fetched(List<InstalledAddon> providers, @androidx.annotation.Nullable AddonRepository.Result<MediaSource> found, long configuration) { this.providers = providers; this.found = found; this.configuration = configuration; }
    }
    private static final long SOURCES_FRESH_MS = 60_000;

    /** The initial and loaded pages share the same fixed toolbar, including during provider requests. */
    Page detailsPage(Title title, String note) {
        Page page = new Page(); page.backdrop = title;
        if (!title.provider.equals("local")) page.tools = java.util.Collections.singletonList(
                new Action("refresh-details", Action.Style.ICON, "Refresh details", () -> { services.preparation.invalidate(title, null); nav.refresh(); }).icon(R.drawable.ic_tv_refresh));
        return page.add(Item.details(title, note));
    }
    Page sourcesPage(Route route) {
        Title title = route.title;
        Page page = new Page().title(title.name).narrow(); page.backdrop = title; page.blurBackdrop = true;
        page.tools = java.util.Collections.singletonList(new Action("refresh-sources", Action.Style.ICON, "Refresh sources", () -> {
            route.cache = null; services.preparation.invalidate(title, route.video); nav.refresh();
        }).icon(R.drawable.ic_tv_refresh));
        return page.add(Item.text("Sources", title.type.equals("series") ? episodeName(title, route.video) : ""));
    }

    Page sources(Route route, Cancellation scope) throws Exception { return sources(route, scope, ignored -> {}); }
    Page sources(Route route, Cancellation scope, java.util.function.Consumer<Page> progress) throws Exception {
        Title title = route.title;
        Page page = sourcesPage(route);
        Fetched fetched = route.cache instanceof Fetched && ((Fetched) route.cache).configuration == services.addons.configuration() && android.os.SystemClock.elapsedRealtime() - route.cachedAt < SOURCES_FRESH_MS ? (Fetched) route.cache : null;
        if (fetched == null) {
            progress.accept(page.copy().add(Item.skeleton("Source results")));
            List<InstalledAddon> providers = services.addons.providers("stream", title.type, route.video);
            PlaybackPreparation.Entry entry = services.preparation.prepare(title, route.video);
            entry.awaitComplete(scope, result -> {
                try { progress.accept(sourceContent(route, new Fetched(providers, result, entry.configuration), entry.complete && entry.metadataReady)); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            fetched = new Fetched(providers, entry.result(), entry.configuration);
            route.cache = fetched; route.cachedAt = android.os.SystemClock.elapsedRealtime();
        }
        return sourceContent(route, fetched);
    }
    private Page sourceContent(Route route, Fetched fetched) throws Exception { return sourceContent(route, fetched, true); }
    private Page sourceContent(Route route, Fetched fetched, boolean complete) throws Exception {
        Title title = route.title;
        Page page = sourcesPage(route);
        List<InstalledAddon> providers = fetched.providers;
        AddonRepository.Result<MediaSource> found = fetched.found;
        List<MediaSource> sources = new ArrayList<>(found == null ? new ArrayList<>() : found.items);
        List<InstalledAddon> installed = services.store.addons();
        // Only what this TV can play. The rest is counted, not listed.
        List<MediaSource> playable = SourcePolicy.ranked(sources);
        if (playable.isEmpty() && !complete) return page.add(Item.skeleton("Finding sources"));
        if (!playable.isEmpty()) {
            Map<String, TvStore.SourceMark> marks = services.store.sourceMarks(title.key(), route.video);
            // "SIZE" or "SIZE:reverse". Choosing the current order again turns it around.
            String saved = services.store.preference("sourceSort", SourceSort.Order.BEST.name());
            SourceSort.Order order = SourceSort.Order.of(saved.replace(":reverse", ""));
            boolean reverse = saved.endsWith(":reverse");
            List<Action> sorts = new ArrayList<>();
            for (SourceSort.Order o : SourceSort.Order.values()) {
                boolean current = o == order;
                String next = o.name() + (current && !reverse ? ":reverse" : "");
                sorts.add(new Action("sort:" + o.name(), Action.Style.CHIP, o.label, () -> {
                    route.scroll = null;
                    nav.mutate(() -> { services.store.setPreference("sourceSort", next); return null; });
                }).text(current ? o.label + (reverse ? "  ↑" : "  ↓") : o.label).selected(current));
            }
            page.add(Item.chips("source-sort", "", sorts));
            String last = ""; long lastAt = 0; int played = 0;
            for (MediaSource source : playable) {
                TvStore.SourceMark mark = marks.get(SourcePolicy.key(source));
                if (mark == null || !mark.state.equals(TvStore.SourceMark.PLAYED)) continue;
                played++;
                if (mark.updatedMs > lastAt) { lastAt = mark.updatedMs; last = SourcePolicy.key(source); }
            }
            page.add(Item.header(playable.size() == 1 ? "1 source" : playable.size() + " sources",
                    (played > 0 ? played + " played  ·  " : "") + "by " + order.label.toLowerCase(Locale.ROOT) + (reverse ? ", reversed" : "")));
            for (MediaSource source : SourceSort.sort(playable, order, reverse, marks)) {
                String key = SourcePolicy.key(source);
                String provider = "Provider";
                for (InstalledAddon a : installed) if (a.id.equals(source.provider)) provider = a.manifest.name;
                SourceSort.Facts facts = SourceSort.facts(source);
                StringBuilder meta = new StringBuilder(provider).append(DOT).append(kind(source));
                if (!facts.quality().isEmpty()) meta.append(DOT).append(facts.quality());
                if (facts.bytes > 0) meta.append(DOT).append(size(facts.bytes));
                if (facts.seeds >= 0) meta.append(DOT).append(facts.seeds == 1 ? "1 seed" : facts.seeds + " seeds");
                TvStore.SourceMark mark = marks.get(key);
                boolean failed = mark != null && mark.state.equals(TvStore.SourceMark.FAILED);
                String status = mark == null ? "" : failed ? "Didn't play" : key.equals(last) ? "Last played" : "Played";
                Item row = Item.source("source:" + key, source.name.replace('\n', ' '), meta.toString(), detail(source), status, !failed,
                        () -> nav.play(title, route.video, key));
                page.add(row.status(mark == null ? -1 : failed ? R.drawable.ic_tv_close : R.drawable.ic_tv_check));
            }
        }
        if (playable.isEmpty() && !sources.isEmpty()) {
            Availability why = Availability.of(providers, sources, found == null ? new java.util.HashMap<>() : found.failures, installed);
            page.add(Item.message(R.drawable.ic_tv_info, why.heading, why.detail));
        } else if (sources.isEmpty()) {
            Availability why = Availability.of(providers, sources, found == null ? new java.util.HashMap<>() : found.failures, installed);
            page.add(providers.isEmpty()
                    ? Item.message(R.drawable.ic_tv_extension, why.heading, why.detail,
                        new Action("add-source", Action.Style.PRIMARY, "Find a source add-on", () -> nav.push(StoreScreens.route("Sources"))).icon(R.drawable.ic_tv_extension))
                    : Item.message(found.failures.isEmpty() ? R.drawable.ic_tv_search : R.drawable.ic_tv_offline, why.heading, why.detail));
        } else if (found != null && !found.failures.isEmpty()) {
            page.add(Item.notice(R.drawable.ic_tv_offline, "Some providers are unavailable", Availability.failures(found.failures, installed)));
        }
        return page;
    }
    private static final String DOT = "  ·  ";
    /** The release name some add-ons put in the description's first line (Torrentio's file name); figures are in meta. */
    private static String detail(MediaSource source) {
        String text = source.description.trim(); int end = text.indexOf('\n'); if (end >= 0) text = text.substring(0, end).trim();
        if (text.isEmpty() && source.filename != null) text = source.filename;
        boolean figures = text.matches("(?s).*(👤|👥|🌱|💾|📦).*");
        return figures || text.equalsIgnoreCase(source.name.replace('\n', ' ').trim()) ? "" : text;
    }

    static String kind(MediaSource source) {
        switch (source.kind) {
            case HTTP: return "Direct";
            case TORRENT: return "Torrent";
            case MAGNET: return "Magnet";
            default: return "Unsupported";
        }
    }
    static String size(long bytes) {
        double gib = bytes / (double) (1L << 30);
        return gib >= 1 ? String.format(java.util.Locale.ROOT, "%.1f GB", gib) : (bytes >> 20) + " MB";
    }
    /** "S1 E4", for a Play button. */
    static String shortEpisode(Title title, String video) {
        for (Title.Episode e : title.episodes) if (e.id.equals(video)) return "S" + e.season + " E" + e.number;
        return "";
    }
    static String episodeName(Title title, String video) {
        for (Title.Episode e : title.episodes) if (e.id.equals(video)) return "S" + e.season + " E" + e.number + " · " + e.name;
        return title.type.equals("series") ? "Episode" : "Film";
    }
}
