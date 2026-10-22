package tv.daniil.stream;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
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

/** Title details (with seasons and episodes) and explicit source selection. */
final class TitleScreens {
    private final Navigator nav;
    private final TvApplication.Services services;

    TitleScreens(Navigator nav, TvApplication.Services services) { this.nav = nav; this.services = services; }

    Page details(Route route, Cancellation scope) throws Exception {
        Title original = route.title, title = original;
        String note = "";
        boolean local = original.provider.equals("local");
        if (!local) {
            AddonRepository.Result<Title> found = services.addons.metadata(original.provider, original.type, original.id, scope);
            if (!found.items.isEmpty()) title = found.items.get(0);
            if (!found.failures.isEmpty()) note = "Some details are unavailable right now; saved information is shown.";
        }
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

        Page page = new Page(); page.backdrop = title;
        page.add(Item.details(title, note));
        List<Action> actions = new ArrayList<>();
        actions.add(new Action("play", Action.Style.PRIMARY, resume ? "Resume" : "Play", () -> nav.play(chosen, selected, "")).icon(R.drawable.ic_tv_play));
        if (!local) actions.add(new Action("sources", Action.Style.SECONDARY, "Sources", () -> nav.sources(chosen, selected)).icon(R.drawable.ic_tv_list));
        boolean isFavourite = favourite, watched = progress != null && progress.watched;
        actions.add(new Action("favourite", Action.Style.ICON, isFavourite ? "Remove favourite" : "Add favourite",
                () -> nav.mutate(() -> { services.store.favourite(chosen, !isFavourite); return null; })).icon(isFavourite ? R.drawable.ic_tv_heart : R.drawable.ic_tv_heart_outline));
        actions.add(new Action("watched", Action.Style.ICON, watched ? "Mark unwatched" : "Mark watched",
                () -> nav.mutate(() -> { services.store.watched(chosen, selected, !watched); return null; })).icon(watched ? R.drawable.ic_tv_watched : R.drawable.ic_tv_unwatched));
        if (local) {
            actions.add(new Action("rename", Action.Style.SECONDARY, "Rename import", () -> nav.edit("Rename import", "Title", chosen.name, name -> nav.mutate(() -> {
                services.imports.rename(chosen.id, name);
                route.title = Title.parse("local", new JSONObject().put("id", chosen.id).put("type", chosen.type).put("name", name));
                return null;
            }))).icon(R.drawable.ic_tv_edit));
            actions.add(new Action("remove-import", Action.Style.SECONDARY, "Remove import", () -> nav.then(() -> { services.imports.remove(chosen.id); return null; }, nav::back))
                    .icon(R.drawable.ic_tv_delete));
        } else {
            actions.add(new Action("refresh-details", Action.Style.ICON, "Refresh details", nav::refresh).icon(R.drawable.ic_tv_refresh));
            actions.add(new Action("remove-library", Action.Style.ICON, "Remove from library and history",
                    () -> nav.then(() -> { services.store.removeLibrary(chosen.key()); return null; }, () -> nav.root("Library"))).icon(R.drawable.ic_tv_delete));
        }
        page.add(Item.actions("title-actions", actions));
        page.initialFocus = "play";
        if (title.type.equals("series") && !title.episodes.isEmpty()) {
            page.add(Item.text("", (resume ? "Resume " : "Play ") + episodeName(title, video) + (resume ? " at " + BrowseScreens.minutes(progress.positionMs) : "")));
        }
        if (!local && noSources(title, video)) {
            page.add(Item.notice(R.drawable.ic_tv_info, Availability.NO_PROVIDER, Availability.NO_PROVIDER_DETAIL,
                    new Action("add-source", Action.Style.SECONDARY, "Find a source add-on", () -> nav.push(StoreScreens.route("Sources"))).icon(R.drawable.ic_tv_extension)));
        }
        if (title.type.equals("series")) episodes(page, route, title);
        if (!title.cast.isEmpty()) page.add(Item.text("Starring", String.join(", ", title.cast.subList(0, Math.min(8, title.cast.size())))));
        return page;
    }

    private void episodes(Page page, Route route, Title title) {
        if (title.episodes.isEmpty()) {
            page.add(Item.notice(R.drawable.ic_tv_info, "No episodes supplied", "Refresh details or choose another provider."));
            return;
        }
        SortedSet<Integer> seasons = new TreeSet<>();
        for (Title.Episode e : title.episodes) seasons.add(e.season);
        if (route.season == -1 || !seasons.contains(route.season)) {
            route.season = seasons.first();
            for (Title.Episode e : title.episodes) if (e.id.equals(route.video)) route.season = e.season;
        }
        List<Action> tabs = new ArrayList<>();
        for (int season : seasons) {
            tabs.add(new Action("season:" + season, Action.Style.CHIP, season == 0 ? "Specials" : "Season " + season, () -> {
                route.season = season; route.scroll = null; nav.refresh();
            }).selected(season == route.season));
        }
        if (seasons.size() > 1) page.add(Item.chips("seasons", "Episodes", tabs));
        List<Tile> tiles = new ArrayList<>();
        for (Title.Episode e : title.episodes) if (e.season == route.season) {
            TvStore.Progress p = services.store.progress(title.key(), e.id);
            String state = p == null ? e.overview.isEmpty() ? released(e) : e.overview
                    : p.watched ? "Watched" : p.durationMs > 0 ? BrowseScreens.minutes(p.durationMs - p.positionMs) + " left" : "In progress";
            Tile tile = new Tile("episode:" + e.id, Tile.Style.LANDSCAPE, (e.number > 0 ? e.number + ". " : "") + e.name, state,
                    e.thumbnail != null ? e.thumbnail : title.background, null, () -> nav.choose(e.name,
                    new String[]{"Play / Resume", "Choose source", "Mark watched", "Mark unwatched"}, which -> {
                        route.video = e.id;
                        if (which == 0) nav.play(title, e.id, "");
                        else if (which == 1) nav.sources(title, e.id);
                        else nav.mutate(() -> { services.store.watched(title, e.id, which == 2); return null; });
                    }));
            if (p != null && p.watched) tile.badge(R.drawable.ic_tv_check);
            else if (p != null && p.durationMs > 0) tile.progress(p.positionMs / (float) p.durationMs);
            tiles.add(tile);
        }
        page.add(Item.shelf("episodes", seasons.size() > 1 ? "" : (route.season == 0 ? "Specials" : "Season " + route.season), "", Tile.Style.LANDSCAPE, tiles));
    }
    private static String released(Title.Episode e) { return e.released.length() >= 10 ? e.released.substring(0, 10) : e.released; }

    /** True when no enabled add-on could supply streams and the metadata carries none itself. */
    private boolean noSources(Title title, String video) {
        if (!services.addons.providers("stream", title.type, video).isEmpty()) return false;
        for (Title.Episode e : title.episodes) if (e.id.equals(video) && !e.streams.isEmpty()) return false;
        return true;
    }

    Page sources(Route route, Cancellation scope) throws Exception {
        Title title = route.title;
        Page page = new Page().title("Sources"); page.backdrop = title;
        page.add(Item.header(title.name, title.type.equals("series") ? episodeName(title, route.video) : ""));
        List<InstalledAddon> providers = services.addons.providers("stream", title.type, route.video);
        AddonRepository.Result<MediaSource> found = providers.isEmpty() ? null : services.addons.streams(title.type, route.video, scope);
        List<MediaSource> sources = new ArrayList<>(found == null ? new ArrayList<>() : found.items);
        for (Title.Episode e : title.episodes) if (e.id.equals(route.video)) sources.addAll(e.streams);
        List<InstalledAddon> installed = services.store.addons();
        // Compatible first, in ranked order; unsupported sources stay listed with their reason.
        List<MediaSource> ordered = new ArrayList<>(SourcePolicy.ranked(sources));
        for (MediaSource s : sources) if (!ordered.contains(s)) ordered.add(s);
        for (MediaSource source : ordered) {
            SourcePolicy.Assessment assessment = SourcePolicy.assess(source);
            String provider = "Provider";
            for (InstalledAddon a : installed) if (a.id.equals(source.provider)) provider = a.manifest.name;
            String meta = provider + "  ·  " + kind(source) + (source.videoSize > 0 ? "  ·  " + size(source.videoSize) : "");
            page.add(Item.source("source:" + SourcePolicy.key(source), source.name, meta, source.description.replace('\n', ' '), assessment.label, assessment.usable, () -> {
                if (!assessment.usable) nav.alert("Source unavailable", assessment.label);
                else nav.play(title, route.video, SourcePolicy.key(source));
            }));
        }
        if (sources.isEmpty()) {
            Availability why = Availability.of(providers, sources, found == null ? new java.util.HashMap<>() : found.failures, installed);
            page.add(providers.isEmpty()
                    ? Item.message(R.drawable.ic_tv_extension, why.heading, why.detail,
                        new Action("add-source", Action.Style.PRIMARY, "Find a source add-on", () -> nav.push(StoreScreens.route("Sources"))).icon(R.drawable.ic_tv_extension))
                    : Item.message(found.failures.isEmpty() ? R.drawable.ic_tv_search : R.drawable.ic_tv_offline, why.heading, why.detail));
        } else if (found != null && !found.failures.isEmpty()) {
            page.add(Item.notice(R.drawable.ic_tv_offline, "Some providers are unavailable", Availability.failures(found.failures, installed)));
        }
        page.add(Item.actions("source-actions", new Action("refresh-sources", Action.Style.SECONDARY, "Refresh sources", nav::refresh).icon(R.drawable.ic_tv_refresh)));
        return page;
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
    static String episodeName(Title title, String video) {
        for (Title.Episode e : title.episodes) if (e.id.equals(video)) return "S" + e.season + " E" + e.number + " · " + e.name;
        return title.type.equals("series") ? "Episode" : "Film";
    }
}
