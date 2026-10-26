package tv.daniil.stream.addon;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import tv.daniil.stream.data.InstalledAddon;
import tv.daniil.stream.data.TvStore;

/** Worker-thread use: one repository per app. Navigation owns and closes a Cancellation scope. */
public final class AddonRepository implements AutoCloseable {
    public static final class Result<T> {
        public final List<T> items;
        /** Per-provider/catalogue errors, alongside surviving results. No URL/error body is exposed. */
        public final Map<String, AddonException.Kind> failures;
        public final boolean stale;
        /** Catalogue/resource identity to results, preserving separate Home rows after concurrent requests. */
        public final Map<String, List<T>> byResource;
        public Result(List<T> items, Map<String, AddonException.Kind> failures, boolean stale) {
            this(items, failures, stale, Collections.emptyMap());
        }
        Result(List<T> items, Map<String, AddonException.Kind> failures, boolean stale, Map<String, List<T>> byResource) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items)); this.failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures)); this.stale = stale;
            Map<String, List<T>> groups = new LinkedHashMap<>();
            for (Map.Entry<String, List<T>> group : byResource.entrySet()) groups.put(group.getKey(), Collections.unmodifiableList(new ArrayList<>(group.getValue())));
            this.byResource = Collections.unmodifiableMap(groups);
        }
        public Result<T> withItems(List<T> replacement) {
            return new Result<>(replacement, failures, stale, byResource);
        }
    }
    public static final class CatalogQuery {
        public final InstalledAddon addon;
        public final AddonManifest.Catalog catalog;
        public final Map<String, String> extras;
        public CatalogQuery(InstalledAddon addon, AddonManifest.Catalog catalog, Map<String, String> extras) {
            this.addon = addon; this.catalog = catalog; this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
        }
    }
    /** One category page load, including visible retry progress. Only transient failures are retried. */
    public static final class CatalogPage {
        public static final int MAX_ATTEMPTS = 3;
        public static final int PAGE_SIZE = 24;
        public final Result<Title> result;
        public final int attempt;
        public final boolean loading;
        CatalogPage(Result<Title> result, int attempt, boolean loading) {
            this.result = result; this.attempt = attempt; this.loading = loading;
        }
    }
    private static final class Part<T> {
        final String key;
        final List<T> items;
        final AddonException.Kind failure;
        final boolean stale;
        final int httpStatus;
        Part(String key, List<T> items, AddonException.Kind failure, boolean stale) { this(key, items, failure, stale, 0); }
        Part(String key, List<T> items, AddonException.Kind failure, boolean stale, int httpStatus) {
            this.key = key; this.items = items; this.failure = failure; this.stale = stale; this.httpStatus = httpStatus;
        }
    }
    /** Stremio's add-on directory: Cinemeta publishes the Official and Community lists through addon_catalog. */
    public static final String DIRECTORY = AddonUrl.CINEMETA;
    private static final String[][] DIRECTORY_LISTS = {{"official", "Official"}, {"community", "Community"}};
    private final TvStore store;
    private final List<String> directory;
    private final AddonClient client = new AddonClient();
    private final ProviderExecutor workers = new ProviderExecutor();
    private volatile long configuration;
    private static final long CATALOG_FRESH_MS = 5 * 60_000;
    private static final int CATALOG_CACHE_PAGES = 12;
    private static final class CachedCatalog {
        final Part<Title> part;
        final long saved = SystemClock.elapsedRealtime();
        CachedCatalog(Part<Title> part) { this.part = part; }
    }
    private final Map<String, CachedCatalog> catalogPages = new LinkedHashMap<>(16, .75f, true);
    public long configuration() { return configuration; }
    public void prioritize(Cancellation wanted) {
        wanted.priority(0);
        List<Cancellation> open;
        synchronized (scopeLock) { open = new ArrayList<>(scopes); }
        for (Cancellation scope : open) if (scope != wanted && scope.priority() > 0) scope.close();
        workers.promote();
    }
    /** Guards only {@link #scopes}: screens open requests on the UI thread while workers hold {@code this} to save. */
    private final Object scopeLock = new Object();
    private final List<Cancellation> scopes = new ArrayList<>();

    /** No default directory: the store lists built-in add-ons and lists published by installed add-ons. */
    public AddonRepository(TvStore store) { this(store, Collections.emptyList()); }
    /** @param directory manifest addresses whose Official and Community add-on lists the store always shows */
    public AddonRepository(TvStore store, List<String> directory) { this.store = store; this.directory = Collections.unmodifiableList(new ArrayList<>(directory)); }
    public Cancellation newRequest() {
        synchronized (scopeLock) {
            scopes.removeIf(Cancellation::isCancelled);
            Cancellation scope = new Cancellation(); scopes.add(scope); return scope;
        }
    }
    /** Called before configuration changes and when leaving all provider screens. */
    public void cancelRequests() {
        configuration++;
        synchronized (catalogPages) { catalogPages.clear(); }
        List<Cancellation> open;
        synchronized (scopeLock) { open = new ArrayList<>(scopes); scopes.clear(); }
        for (Cancellation scope : open) scope.close();
    }
    public AddonManifest preview(String manifestUrl, Cancellation scope) throws AddonException {
        AddonUrl url = new AddonUrl(manifestUrl);
        return AddonManifest.parse(client.json(url.manifest, scope, AddonClient.deadline()));
    }
    /** Only the caller's accepted manifest is installed. Cinemeta and playback providers are never automatic. */
    public synchronized InstalledAddon install(String url, AddonManifest accepted) throws AddonException {
        AddonUrl address = new AddonUrl(url);
        cancelRequests(); return store.install(address, accepted);
    }
    public synchronized void enable(String id, boolean enabled) { cancelRequests(); store.enableAddon(id, enabled); }
    public synchronized void remove(String id) { cancelRequests(); store.removeAddon(id); }
    public synchronized void reorder(List<String> ids) { cancelRequests(); store.orderAddons(ids); }
    public synchronized void visible(String id, String type, String catalog, boolean visible) { cancelRequests(); store.catalogVisible(id, type, catalog, visible); }

    public List<CatalogQuery> homeCatalogs() {
        List<CatalogQuery> queries = new ArrayList<>();
        for (InstalledAddon a : store.addons()) if (a.enabled) for (AddonManifest.Catalog c : a.manifest.catalogs) {
            if (c.listable() && a.manifest.handles("catalog", c.type, null) && store.catalogVisible(a.id, c.type, c.id)) {
                queries.add(new CatalogQuery(a, c, Collections.emptyMap()));
            }
        }
        return queries;
    }
    public Result<Title> search(String text, Cancellation scope) throws AddonException { return search(text, scope, ignored -> {}); }
    public Result<Title> search(String text, Cancellation scope, java.util.function.Consumer<Result<Title>> progress) throws AddonException {
        List<CatalogQuery> queries = new ArrayList<>();
        for (InstalledAddon a : store.addons()) if (a.enabled) for (AddonManifest.Catalog c : a.manifest.catalogs) {
            if (c.searchable() && a.manifest.handles("catalog", c.type, null)) queries.add(new CatalogQuery(a, c, Collections.singletonMap("search", text)));
        }
        Result<Title> remote = catalogs(queries, scope, partial -> progress.accept(searchResults(text, partial)));
        return searchResults(text, remote);
    }
    private Result<Title> searchResults(String text, Result<Title> remote) {
        LinkedHashMap<String, Title> deduplicated = new LinkedHashMap<>();
        for (Title t : remote.items) deduplicated.putIfAbsent(t.discoveryKey(), t);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (TvStore.LibraryEntry e : store.library(false)) if (e.title.provider.equals("local") && e.title.name.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
            deduplicated.putIfAbsent(e.title.discoveryKey(), e.title);
        }
        return new Result<>(new ArrayList<>(deduplicated.values()), remote.failures, remote.stale);
    }
    /** Home/Search retry only failed resources. Successful shelves survive later attempts. */
    public CatalogPage searchPage(String text, Cancellation scope, java.util.function.Consumer<CatalogPage> progress) throws AddonException {
        List<CatalogQuery> queries = new ArrayList<>();
        for (InstalledAddon a : store.addons()) if (a.enabled) for (AddonManifest.Catalog c : a.manifest.catalogs)
            if (c.searchable() && a.manifest.handles("catalog", c.type, null))
                queries.add(new CatalogQuery(a, c, Collections.singletonMap("search", text)));
        CatalogPage page = catalogsPage(queries, scope, partial -> progress.accept(
                new CatalogPage(searchResults(text, partial.result), partial.attempt, partial.loading)));
        return new CatalogPage(searchResults(text, page.result), page.attempt, false);
    }
    public CatalogPage catalogsPage(List<CatalogQuery> queries, Cancellation scope,
                                    java.util.function.Consumer<CatalogPage> progress) throws AddonException {
        scope.check();
        Map<Integer, Part<Title>> parts = new java.util.TreeMap<>();
        Map<String, Integer> positions = new LinkedHashMap<>();
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            CatalogQuery query = queries.get(i);
            String key = query.addon.id + ":" + query.catalog.type + ":" + query.catalog.id;
            positions.put(key, i); pending.add(i);
            List<Title> saved = Collections.emptyList();
            String cached = store.cached(query.addon.address.resource("catalog", query.catalog.type, query.catalog.id, query.extras));
            if (cached != null) try { saved = Title.catalog(query.addon.id, new JSONObject(cached)); }
            catch (JSONException | AddonException ignored) { /* A damaged cache is replaced by the provider response. */ }
            parts.put(i, new Part<>(key, saved, null, !saved.isEmpty()));
        }
        if (pending.isEmpty()) return new CatalogPage(snapshot(parts), 1, false);
        for (int attempt = 1; ; attempt++) {
            scope.check(); final int current = attempt;
            progress.accept(new CatalogPage(snapshot(parts), current, true));
            long deadline = AddonClient.deadline();
            List<Callable<Part<Part<Title>>>> tasks = new ArrayList<>(); List<String> keys = new ArrayList<>();
            // Keep each resource's HTTP status through collection, so a 401 is never retried like a 503.
            for (int index : pending) {
                CatalogQuery query = queries.get(index); String key = parts.get(index).key; keys.add(key);
                tasks.add(() -> new Part<>(key, Collections.singletonList(catalog(query, scope, deadline, key)), null, false));
            }
            Result<Part<Title>> loaded = collect(tasks, keys, scope, deadline, partial -> {
                mergeCatalogParts(parts, positions, partial);
                boolean complete = partial.items.size() + partial.failures.size() == keys.size();
                boolean retrying = current < CatalogPage.MAX_ATTEMPTS && parts.values().stream().anyMatch(AddonRepository::retryable);
                progress.accept(new CatalogPage(snapshot(parts), current, !complete || retrying));
            });
            mergeCatalogParts(parts, positions, loaded); scope.check();
            List<Integer> retry = new ArrayList<>();
            for (int index : pending) if (retryable(parts.get(index))) retry.add(index);
            if (retry.isEmpty() || attempt == CatalogPage.MAX_ATTEMPTS) return new CatalogPage(snapshot(parts), attempt, false);
            progress.accept(new CatalogPage(snapshot(parts), attempt, true));
            backoff(scope, attempt); pending = retry;
        }
    }
    private static void mergeCatalogParts(Map<Integer, Part<Title>> parts, Map<String, Integer> positions, Result<Part<Title>> update) {
        for (Part<Title> part : update.items) parts.put(positions.get(part.key), part);
        for (Map.Entry<String, AddonException.Kind> failure : update.failures.entrySet()) {
            int index = positions.get(failure.getKey()); Part<Title> previous = parts.get(index);
            parts.put(index, new Part<>(previous.key, previous.items, failure.getValue(), !previous.items.isEmpty()));
        }
    }
    private static void backoff(Cancellation scope, int attempt) throws AddonException {
        java.util.concurrent.CountDownLatch wake = new java.util.concurrent.CountDownLatch(1);
        Runnable abort = wake::countDown; scope.add(abort);
        try { wake.await(attempt * 1000L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AddonException(AddonException.Kind.CANCELLED, "Request cancelled"); }
        finally { scope.remove(abort); }
        scope.check();
    }
    public Result<Title> catalogs(List<CatalogQuery> queries, Cancellation scope) throws AddonException {
        return catalogs(queries, scope, ignored -> {});
    }
    /** Bounded, cancellable category retries; recently visited pages do not ask their provider again. */
    public CatalogPage catalogPage(CatalogQuery query, Cancellation scope, boolean refresh,
                                   java.util.function.Consumer<CatalogPage> progress) throws AddonException {
        scope.check();
        long revision = configuration;
        String address = query.addon.address.resource("catalog", query.catalog.type, query.catalog.id, query.extras);
        String key = query.addon.id + ":" + query.catalog.type + ":" + query.catalog.id;
        CachedCatalog cached;
        synchronized (catalogPages) { cached = catalogPages.get(address); }
        if (!refresh && cached != null && SystemClock.elapsedRealtime() - cached.saved < CATALOG_FRESH_MS)
            return catalogPage(cached.part, 1, false);
        Part<Title> previous = cached == null ? new Part<>(key, Collections.emptyList(), null, false) : cached.part;
        for (int attempt = 1; attempt <= CatalogPage.MAX_ATTEMPTS; attempt++) {
            scope.check(); progress.accept(catalogPage(previous, attempt, true));
            Part<Title> loaded = catalog(query, scope, AddonClient.deadline(), key);
            scope.check();
            if (query.catalog.supports("skip") && loaded.items.size() > CatalogPage.PAGE_SIZE)
                loaded = new Part<>(loaded.key, new ArrayList<>(loaded.items.subList(0, CatalogPage.PAGE_SIZE)), loaded.failure, loaded.stale, loaded.httpStatus);
            if (loaded.failure == null) {
                synchronized (catalogPages) {
                    scope.check();
                    if (revision == configuration) {
                        catalogPages.put(address, new CachedCatalog(loaded));
                        while (catalogPages.size() > CATALOG_CACHE_PAGES
                                || catalogPages.values().stream().mapToInt(value -> value.part.items.size()).sum() > CATALOG_CACHE_PAGES * CatalogPage.PAGE_SIZE)
                            catalogPages.remove(catalogPages.keySet().iterator().next());
                    }
                }
                return catalogPage(loaded, attempt, false);
            }
            if (attempt == CatalogPage.MAX_ATTEMPTS || !retryable(loaded)) return catalogPage(loaded, attempt, false);
            // Keep any saved posters visible during backoff. Cancellation wakes the wait immediately.
            previous = loaded;
            progress.accept(catalogPage(loaded, attempt, true));
            backoff(scope, attempt);
        }
        throw new AssertionError("Unreachable catalogue attempt");
    }
    private static CatalogPage catalogPage(Part<Title> part, int attempt, boolean loading) {
        Map<Integer, Part<Title>> parts = Collections.singletonMap(0, part);
        return new CatalogPage(snapshot(parts), attempt, loading);
    }
    private static boolean retryable(Part<?> part) {
        return part.failure == AddonException.Kind.NETWORK || part.failure == AddonException.Kind.TIMEOUT
                || part.failure == AddonException.Kind.HTTP && (part.httpStatus == 408 || part.httpStatus == 429 || part.httpStatus >= 500);
    }
    public Result<Title> catalogs(List<CatalogQuery> queries, Cancellation scope, java.util.function.Consumer<Result<Title>> progress) throws AddonException {
        long deadline = AddonClient.deadline();
        List<Callable<Part<Title>>> tasks = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (CatalogQuery q : queries) {
            String key = q.addon.id + ":" + q.catalog.type + ":" + q.catalog.id; keys.add(key);
            tasks.add(() -> catalog(q, scope, deadline, key));
        }
        return collect(tasks, keys, scope, deadline, progress);
    }
    private Part<Title> catalog(CatalogQuery q, Cancellation scope, long deadline, String key) {
        String address = q.addon.address.resource("catalog", q.catalog.type, q.catalog.id, q.extras);
        try {
            if (!q.addon.enabled || !q.addon.manifest.handles("catalog", q.catalog.type, null)) throw new AddonException(AddonException.Kind.MALFORMED, "Catalogue is unavailable");
            for (AddonManifest.Extra e : q.catalog.extras) if (e.required && !q.extras.containsKey(e.name)) throw new AddonException(AddonException.Kind.MALFORMED, "A catalogue filter is required");
            for (String extra : q.extras.keySet()) if (!q.catalog.supports(extra)) throw new AddonException(AddonException.Kind.MALFORMED, "Unsupported catalogue filter");
            List<Title> titles = Title.catalog(q.addon.id, client.json(address, scope, deadline));
            JSONArray metadata = new JSONArray(); for (Title title : titles) metadata.put(new JSONObject(title.cacheJson()));
            synchronized (this) { scope.check(); store.cache(address, new JSONObject().put("metas", metadata).toString()); }
            return new Part<>(key, titles, null, false);
        } catch (AddonException e) {
            if (e.kind != AddonException.Kind.CANCELLED) {
                String saved = store.cached(address);
                if (saved != null) try { return new Part<>(key, Title.catalog(q.addon.id, new JSONObject(saved)), e.kind, true, e.httpStatus); }
                catch (JSONException | AddonException ignored) { /* fall through to the provider error */ }
            }
            return new Part<>(key, Collections.emptyList(), e.kind, false, e.httpStatus);
        } catch (JSONException e) { return new Part<>(key, Collections.emptyList(), AddonException.Kind.MALFORMED, false); }
    }
    public Result<Title> metadata(String provider, String type, String id, Cancellation scope) throws AddonException {
        return metadata(provider, type, id, scope, ignored -> {});
    }
    public Result<Title> metadata(String provider, String type, String id, Cancellation scope, java.util.function.Consumer<Result<Title>> progress) throws AddonException {
        long deadline = AddonClient.deadline();
        List<Callable<Part<Title>>> tasks = new ArrayList<>(); List<String> keys = new ArrayList<>();
        // Origin first; other matching providers may enrich common identifiers.
        List<InstalledAddon> addons = new ArrayList<>(store.addons());
        addons.sort(java.util.Comparator.comparingInt(a -> a.id.equals(provider) ? 0 : 1));
        for (InstalledAddon a : addons) if (a.enabled && a.manifest.handles("meta", type, id)) {
            keys.add(a.id);
            tasks.add(() -> {
                String address = a.address.resource("meta", type, id, Collections.emptyMap());
                try {
                    Title t = Title.parse(a.id, client.json(address, scope, deadline).optJSONObject("meta"));
                    if (t == null || !t.id.equals(id) || !t.type.equals(type)) throw new AddonException(AddonException.Kind.MALFORMED, "Invalid title metadata");
                    synchronized (this) { scope.check(); store.cache(address, t.cacheJson()); }
                    return new Part<>(a.id, Collections.singletonList(t), null, false);
                } catch (AddonException e) {
                    String saved = e.kind == AddonException.Kind.CANCELLED ? null : store.cached(address);
                    if (saved != null) try {
                        Title cached = Title.parse(a.id, new JSONObject(saved));
                        if (cached != null) return new Part<>(a.id, Collections.singletonList(cached), e.kind, true);
                    } catch (JSONException ignored) { /* provider failure */ }
                    return new Part<>(a.id, Collections.emptyList(), e.kind, false);
                }
            });
        }
        // At most two metadata providers occupy sockets; stream discovery keeps room to run.
        Map<String, AddonException.Kind> failures = new LinkedHashMap<>();
        for (int start = 0; start < tasks.size(); start += 2) {
            int end = Math.min(tasks.size(), start + 2);
            Result<Title> result = collect(tasks.subList(start, end), keys.subList(start, end), scope, deadline, progress, true);
            failures.putAll(result.failures);
            if (!result.items.isEmpty()) return new Result<>(result.items, failures, result.stale, result.byResource);
        }
        return new Result<>(Collections.emptyList(), failures, false);
    }
    /**
     * The add-on store, by list: built-in add-ons, curated torrent scrapers when using the public directory, then each list that installed add-ons publish through
     * {@code addon_catalog}, then the default directory's. An add-on appears once, in the first list naming it.
     * Lists are cached like catalogues, so a provider failure falls back to the saved list, marked stale.
     */
    public Result<AddonListing> store(Cancellation scope) throws AddonException {
        long deadline = AddonClient.deadline();
        Map<String, String> lists = new LinkedHashMap<>();
        for (InstalledAddon a : store.addons()) if (a.enabled && a.manifest.declares(AddonManifest.ADDON_CATALOG)) {
            boolean combined = false;
            for (AddonManifest.Catalog c : a.manifest.addonCatalogs) combined |= c.type.equals("all");
            // A list per type repeats the combined one; show the combined list when there is one.
            for (AddonManifest.Catalog c : a.manifest.addonCatalogs) if (!combined || c.type.equals("all")) {
                lists.putIfAbsent(a.address.resource(AddonManifest.ADDON_CATALOG, c.type, c.id, Collections.emptyMap()), c.name);
            }
        }
        for (String manifest : directory) {
            AddonUrl address = new AddonUrl(manifest);
            for (String[] list : DIRECTORY_LISTS) lists.putIfAbsent(address.resource(AddonManifest.ADDON_CATALOG, "all", list[0], Collections.emptyMap()), list[1]);
        }
        List<Callable<Part<AddonListing>>> tasks = new ArrayList<>(); List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> list : lists.entrySet()) {
            String address = list.getKey(), name = list.getValue();
            String key = keys.contains(name) ? name + " " + (keys.size() + 1) : name;
            keys.add(key);
            tasks.add(() -> {
                try {
                    JSONObject json = client.json(address, scope, deadline);
                    List<AddonListing> found = AddonListing.parse(name, address, json);
                    synchronized (this) { scope.check(); store.cache(address, json.toString()); }
                    return new Part<>(key, found, null, false);
                } catch (AddonException e) {
                    String saved = e.kind == AddonException.Kind.CANCELLED ? null : store.cached(address);
                    if (saved != null) try { return new Part<>(key, AddonListing.parse(name, address, new JSONObject(saved)), e.kind, true); }
                    catch (JSONException | AddonException ignored) { /* fall through to the provider error */ }
                    return new Part<>(key, Collections.emptyList(), e.kind, false);
                }
            });
        }
        Result<AddonListing> remote = collect(tasks, keys, scope, deadline);
        return combineListings(remote, directory.contains(DIRECTORY) ? TorrentAddons.listings() : Collections.emptyList());
    }

    /** Merges local descriptors with directory results, including when every remote list has failed. No provider is contacted. */
    static Result<AddonListing> combineListings(Result<AddonListing> remote, List<AddonListing> scrapers) {
        Map<String, List<AddonListing>> groups = new LinkedHashMap<>();
        List<AddonListing> all = new ArrayList<>();
        java.util.Set<String> shown = new java.util.HashSet<>();
        groups.put("Built in", BuiltinAddons.listings());
        for (AddonListing l : BuiltinAddons.listings()) { shown.add(l.manifest.id); all.add(l); }
        // Fixture/custom directories never acquire public providers as a side effect. These descriptors need no
        // network request; the selected add-on is contacted only when its review is opened.
        if (!scrapers.isEmpty()) {
            groups.put(TorrentAddons.COLLECTION, scrapers);
            for (AddonListing l : scrapers) { shown.add(l.manifest.id); all.add(l); }
        }
        for (Map.Entry<String, List<AddonListing>> group : remote.byResource.entrySet()) {
            List<AddonListing> unique = new ArrayList<>();
            for (AddonListing l : group.getValue()) if (shown.add(l.manifest.id)) unique.add(l);
            all.addAll(unique);
            if (!unique.isEmpty()) groups.put(group.getKey(), Collections.unmodifiableList(unique));
        }
        return new Result<>(all, remote.failures, remote.stale, groups);
    }
    /** Enabled add-ons declaring {@code resource} for this type and ID. Catalogue-only add-ons such as Cinemeta have no streams. */
    public List<InstalledAddon> providers(String resource, String type, String id) {
        List<InstalledAddon> found = new ArrayList<>();
        for (InstalledAddon a : store.addons()) if (a.enabled && a.manifest.handles(resource, type, id)) found.add(a);
        return found;
    }
    public Result<MediaSource> streams(String type, String videoId, Cancellation scope) throws AddonException {
        return streams(type, videoId, scope, ignored -> {});
    }
    public Result<MediaSource> streams(String type, String videoId, Cancellation scope, java.util.function.Consumer<Result<MediaSource>> progress) throws AddonException {
        return resource("stream", type, videoId, Collections.emptyMap(), "streams", MediaSource::list, scope, progress);
    }
    public Result<SubtitleSource> subtitles(String type, String videoId, Map<String, String> hints, Cancellation scope) throws AddonException {
        // Preserve provider episode IDs; forward only subtitle matching hints, never media request headers.
        Map<String, String> extras = new LinkedHashMap<>();
        for (String key : new String[]{"filename", "videoHash", "videoSize"}) if (hints.containsKey(key)) extras.put(key, hints.get(key));
        return resource("subtitles", type, videoId, extras, "subtitles", SubtitleSource::list, scope, ignored -> {});
    }
    private interface Parser<T> { List<T> parse(String provider, JSONObject json); }
    private <T> Result<T> resource(String resource, String type, String id, Map<String, String> extras, String field, Parser<T> parser, Cancellation scope, java.util.function.Consumer<Result<T>> progress) throws AddonException {
        long deadline = AddonClient.deadline();
        List<Callable<Part<T>>> tasks = new ArrayList<>(); List<String> keys = new ArrayList<>();
        for (InstalledAddon a : store.addons()) if (a.enabled && a.manifest.handles(resource, type, id)) {
            keys.add(a.id);
            tasks.add(() -> {
                try {
                    JSONObject json = client.json(a.address.resource(resource, type, id, extras), scope, deadline);
                    if (json.optJSONArray(field) == null) throw new AddonException(AddonException.Kind.MALFORMED, "Invalid resource response");
                    return new Part<>(a.id, parser.parse(a.id, json), null, false);
                } catch (AddonException e) { return new Part<>(a.id, Collections.emptyList(), e.kind, false); }
            });
        }
        // Playable addresses/subtitles are deliberately never persisted.
        return collect(tasks, keys, scope, deadline, progress);
    }
    private <T> Result<T> collect(List<Callable<Part<T>>> tasks, List<String> keys, Cancellation scope, long deadline) throws AddonException {
        return collect(tasks, keys, scope, deadline, ignored -> {});
    }
    private <T> Result<T> collect(List<Callable<Part<T>>> tasks, List<String> keys, Cancellation scope, long deadline, java.util.function.Consumer<Result<T>> progress) throws AddonException {
        return collect(tasks, keys, scope, deadline, progress, false);
    }
    private <T> Result<T> collect(List<Callable<Part<T>>> tasks, List<String> keys, Cancellation scope, long deadline,
            java.util.function.Consumer<Result<T>> progress, boolean firstUseful) throws AddonException {
        scope.check();
        ExecutorCompletionService<Part<T>> completion = new ExecutorCompletionService<>(command -> workers.execute(command, scope));
        Map<Future<Part<T>>, Integer> pending = new LinkedHashMap<>();
        Map<Integer, Part<T>> finished = new java.util.TreeMap<>();
        List<Runnable> aborts = new ArrayList<>();
        try {
            for (int i = 0; i < tasks.size(); i++) {
                scope.check();
                try {
                    Future<Part<T>> future = completion.submit(tasks.get(i)); pending.put(future, i);
                    Runnable abort = () -> future.cancel(true); aborts.add(abort); scope.add(abort);
                } catch (RejectedExecutionException e) { finished.put(i, new Part<>(keys.get(i), Collections.emptyList(), AddonException.Kind.TIMEOUT, false)); }
            }
            while (!pending.isEmpty()) {
                scope.check();
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) break;
                Future<Part<T>> ready = completion.poll(Math.min(100, left), TimeUnit.MILLISECONDS);
                if (ready == null) continue;
                Integer index = pending.remove(ready);
                try { finished.put(index, ready.get()); }
                catch (ExecutionException e) { finished.put(index, new Part<>(keys.get(index), Collections.emptyList(), AddonException.Kind.MALFORMED, false)); }
                scope.check(); progress.accept(snapshot(finished));
                if (firstUseful && !finished.get(index).items.isEmpty()) break;
            }
            for (Map.Entry<Future<Part<T>>, Integer> e : pending.entrySet()) if (!firstUseful || finished.values().stream().noneMatch(part -> !part.items.isEmpty())) finished.put(e.getValue(), new Part<>(keys.get(e.getValue()), Collections.emptyList(), AddonException.Kind.TIMEOUT, false));
            scope.check();
            Result<T> result = snapshot(finished); progress.accept(result); return result;
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AddonException(AddonException.Kind.CANCELLED, "Request cancelled");
        } finally {
            for (Runnable abort : aborts) { scope.remove(abort); abort.run(); }
            workers.purge();
        }
    }
    private static <T> Result<T> snapshot(Map<Integer, Part<T>> finished) {
        List<T> items = new ArrayList<>(); Map<String, AddonException.Kind> failures = new LinkedHashMap<>();
        Map<String, List<T>> groups = new LinkedHashMap<>(); boolean stale = false;
        for (Part<T> part : finished.values()) {
            items.addAll(part.items); groups.put(part.key, Collections.unmodifiableList(new ArrayList<>(part.items)));
            if (part.failure != null) failures.put(part.key, part.failure); stale |= part.stale;
        }
        return new Result<>(items, failures, stale, groups);
    }
    @Override public void close() { cancelRequests(); workers.shutdownNow(); client.close(); }
}
