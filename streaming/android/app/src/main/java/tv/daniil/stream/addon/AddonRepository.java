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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
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
        Result(List<T> items, Map<String, AddonException.Kind> failures, boolean stale) {
            this(items, failures, stale, Collections.emptyMap());
        }
        Result(List<T> items, Map<String, AddonException.Kind> failures, boolean stale, Map<String, List<T>> byResource) {
            this.items = Collections.unmodifiableList(items); this.failures = Collections.unmodifiableMap(failures); this.stale = stale;
            this.byResource = Collections.unmodifiableMap(byResource);
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
    private static final class Part<T> {
        final String key;
        final List<T> items;
        final AddonException.Kind failure;
        final boolean stale;
        Part(String key, List<T> items, AddonException.Kind failure, boolean stale) { this.key = key; this.items = items; this.failure = failure; this.stale = stale; }
    }
    /** Stremio's add-on directory: Cinemeta publishes the Official and Community lists through addon_catalog. */
    public static final String DIRECTORY = AddonUrl.CINEMETA;
    private static final String[][] DIRECTORY_LISTS = {{"official", "Official"}, {"community", "Community"}};
    private final TvStore store;
    private final List<String> directory;
    private final AddonClient client = new AddonClient();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64));
    private final List<Cancellation> scopes = new ArrayList<>();

    /** No default directory: the store lists built-in add-ons and lists published by installed add-ons. */
    public AddonRepository(TvStore store) { this(store, Collections.emptyList()); }
    /** @param directory manifest addresses whose Official and Community add-on lists the store always shows */
    public AddonRepository(TvStore store, List<String> directory) { this.store = store; this.directory = Collections.unmodifiableList(new ArrayList<>(directory)); }
    public synchronized Cancellation newRequest() {
        scopes.removeIf(Cancellation::isCancelled);
        Cancellation scope = new Cancellation(); scopes.add(scope); return scope;
    }
    /** Called before configuration changes and when leaving all provider screens. */
    public synchronized void cancelRequests() { for (Cancellation scope : scopes) scope.close(); scopes.clear(); }
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
    public Result<Title> search(String text, Cancellation scope) throws AddonException {
        List<CatalogQuery> queries = new ArrayList<>();
        for (InstalledAddon a : store.addons()) if (a.enabled) for (AddonManifest.Catalog c : a.manifest.catalogs) {
            if (c.searchable() && a.manifest.handles("catalog", c.type, null)) queries.add(new CatalogQuery(a, c, Collections.singletonMap("search", text)));
        }
        Result<Title> remote = catalogs(queries, scope);
        LinkedHashMap<String, Title> deduplicated = new LinkedHashMap<>();
        for (Title t : remote.items) deduplicated.putIfAbsent(t.discoveryKey(), t);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (TvStore.LibraryEntry e : store.library(false)) if (e.title.provider.equals("local") && e.title.name.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
            deduplicated.putIfAbsent(e.title.discoveryKey(), e.title);
        }
        return new Result<>(new ArrayList<>(deduplicated.values()), remote.failures, remote.stale);
    }
    public Result<Title> catalogs(List<CatalogQuery> queries, Cancellation scope) throws AddonException {
        long deadline = AddonClient.deadline();
        List<Callable<Part<Title>>> tasks = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (CatalogQuery q : queries) {
            String key = q.addon.id + ":" + q.catalog.type + ":" + q.catalog.id; keys.add(key);
            tasks.add(() -> catalog(q, scope, deadline, key));
        }
        return collect(tasks, keys, scope, deadline);
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
                if (saved != null) try { return new Part<>(key, Title.catalog(q.addon.id, new JSONObject(saved)), e.kind, true); }
                catch (JSONException | AddonException ignored) { /* fall through to the provider error */ }
            }
            return new Part<>(key, Collections.emptyList(), e.kind, false);
        } catch (JSONException e) { return new Part<>(key, Collections.emptyList(), AddonException.Kind.MALFORMED, false); }
    }
    public Result<Title> metadata(String provider, String type, String id, Cancellation scope) throws AddonException {
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
        return collect(tasks, keys, scope, deadline);
    }
    /**
     * The add-on store, by list: built-in add-ons, then each list that installed add-ons publish through
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
        Map<String, List<AddonListing>> groups = new LinkedHashMap<>();
        List<AddonListing> all = new ArrayList<>();
        java.util.Set<String> shown = new java.util.HashSet<>();
        groups.put("Built in", BuiltinAddons.listings());
        for (AddonListing l : BuiltinAddons.listings()) { shown.add(l.manifest.id); all.add(l); }
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
        return resource("stream", type, videoId, Collections.emptyMap(), "streams", MediaSource::list, scope);
    }
    public Result<SubtitleSource> subtitles(String type, String videoId, Map<String, String> hints, Cancellation scope) throws AddonException {
        // Preserve provider episode IDs; forward only subtitle matching hints, never media request headers.
        Map<String, String> extras = new LinkedHashMap<>();
        for (String key : new String[]{"filename", "videoHash", "videoSize"}) if (hints.containsKey(key)) extras.put(key, hints.get(key));
        return resource("subtitles", type, videoId, extras, "subtitles", SubtitleSource::list, scope);
    }
    private interface Parser<T> { List<T> parse(String provider, JSONObject json); }
    private <T> Result<T> resource(String resource, String type, String id, Map<String, String> extras, String field, Parser<T> parser, Cancellation scope) throws AddonException {
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
        // Playable addresses/subtitles are deliberately never cached.
        return collect(tasks, keys, scope, deadline);
    }
    private <T> Result<T> collect(List<Callable<Part<T>>> tasks, List<String> keys, Cancellation scope, long deadline) throws AddonException {
        scope.check();
        ExecutorCompletionService<Part<T>> completion = new ExecutorCompletionService<>(workers);
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
            }
            for (Map.Entry<Future<Part<T>>, Integer> e : pending.entrySet()) finished.put(e.getValue(), new Part<>(keys.get(e.getValue()), Collections.emptyList(), AddonException.Kind.TIMEOUT, false));
            scope.check();
            List<T> items = new ArrayList<>(); Map<String, AddonException.Kind> failures = new LinkedHashMap<>(); boolean stale = false;
            Map<String, List<T>> groups = new LinkedHashMap<>();
            for (Part<T> part : finished.values()) { items.addAll(part.items); groups.put(part.key, Collections.unmodifiableList(part.items)); if (part.failure != null) failures.put(part.key, part.failure); stale |= part.stale; }
            return new Result<>(items, failures, stale, groups);
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AddonException(AddonException.Kind.CANCELLED, "Request cancelled");
        } finally {
            for (Runnable abort : aborts) { scope.remove(abort); abort.run(); }
            workers.purge();
        }
    }
    @Override public synchronized void close() { cancelRequests(); workers.shutdownNow(); client.close(); }
}
