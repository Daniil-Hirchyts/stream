package tv.stream.app.addon

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import tv.stream.app.data.InstalledAddon
import tv.stream.app.data.TvStore

/** Worker-thread use: one repository per app. Navigation owns and closes a Cancellation scope. */
class AddonRepository : AutoCloseable {
    class Result<T>(items: List<T>, failures: Map<String, AddonException.Kind>, val stale: Boolean,
                    byResource: Map<String, List<T>> = emptyMap()) {
        val items: List<T> = Collections.unmodifiableList(ArrayList(items))
        /** Per-provider/catalogue errors, alongside surviving results. No URL/error body is exposed. */
        val failures: Map<String, AddonException.Kind> = Collections.unmodifiableMap(LinkedHashMap(failures))
        /** Catalogue/resource identity to results, preserving separate Home rows after concurrent requests. */
        val byResource: Map<String, List<T>> = Collections.unmodifiableMap(byResource.mapValuesTo(LinkedHashMap()) {
            Collections.unmodifiableList(ArrayList(it.value))
        })

        fun withItems(replacement: List<T>): Result<T> = Result(replacement, failures, stale, byResource)
    }
    class CatalogQuery(val addon: InstalledAddon, val catalog: AddonManifest.Catalog, extras: Map<String, String>) {
        val extras: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(extras))
        /** The catalogue's identity among results ([Result.byResource]), whatever its filters. */
        fun key(): String { return addon.id + ":" + catalog.type + ":" + catalog.id }
    }
    /** One category page load, including visible retry progress. Only transient failures are retried. */
    data class CatalogPage(val result: Result<Title>, val attempt: Int, val loading: Boolean) {
        companion object {
            const val MAX_ATTEMPTS = 3
            const val PAGE_SIZE = 24
        }
    }
    private class Part<T>(val key: String, val items: List<T>, val failure: AddonException.Kind?, val stale: Boolean, val httpStatus: Int) {
        constructor(key: String, items: List<T>, failure: AddonException.Kind?, stale: Boolean) : this(key, items, failure, stale, 0)
    }
    private val store: TvStore
    private val directory: List<String>
    private val client = AddonClient()
    private val workers = ProviderExecutor()
    @Volatile private var configuration: Long = 0
    private class CachedCatalog(val part: Part<Title>) {
        val saved: Long = SystemClock.elapsedRealtime()
    }
    private val catalogPages: MutableMap<String, CachedCatalog> = LinkedHashMap(16, .75f, true)
    /** Details served from a fresh saved copy, and details asked of a provider, for measurements and tests. */
    val freshHits = java.util.concurrent.atomic.AtomicInteger()
    val metadataRequests = java.util.concurrent.atomic.AtomicInteger()
    fun configuration(): Long { return configuration }
    fun prioritize(wanted: Cancellation) {
        wanted.priority(0)
        val open: List<Cancellation>
        synchronized(scopeLock) { open = ArrayList(scopes) }
        for (scope in open) if (scope !== wanted && scope.priority() > 0) scope.close()
        workers.promote()
    }
    /** Guards only [scopes]: screens open requests on the UI thread while workers hold `this` to save. */
    private val scopeLock = Any()
    private val scopes: MutableList<Cancellation> = ArrayList()

    /** No default directory: the store lists built-in add-ons and lists published by installed add-ons. */
    constructor(store: TvStore) : this(store, Collections.emptyList())
    /** @param directory manifest addresses whose Official and Community add-on lists the store always shows */
    constructor(store: TvStore, directory: List<String>) { this.store = store; this.directory = Collections.unmodifiableList(ArrayList(directory)) }
    fun newRequest(): Cancellation {
        synchronized(scopeLock) {
            scopes.removeIf { it.isCancelled() }
            val scope = Cancellation(); scopes.add(scope); return scope
        }
    }
    /** Called before configuration changes and when leaving all provider screens. */
    fun cancelRequests() {
        configuration++
        synchronized(catalogPages) { catalogPages.clear() }
        val open: List<Cancellation>
        synchronized(scopeLock) { open = ArrayList(scopes); scopes.clear() }
        for (scope in open) scope.close()
    }
    @Throws(AddonException::class)
    fun preview(manifestUrl: String, scope: Cancellation): AddonManifest {
        val url = AddonUrl(manifestUrl)
        return AddonManifest.parse(client.json(url.manifest, scope, AddonClient.deadline()))
    }
    /** Only the caller's accepted manifest is installed. Cinemeta and playback providers are never automatic. */
    @Synchronized
    @Throws(AddonException::class)
    fun install(url: String, accepted: AddonManifest): InstalledAddon {
        val address = AddonUrl(url)
        cancelRequests(); return store.install(address, accepted)
    }
    @Synchronized fun enable(id: String, enabled: Boolean) { cancelRequests(); store.enableAddon(id, enabled) }
    @Synchronized fun remove(id: String) { cancelRequests(); store.removeAddon(id) }
    @Synchronized fun reorder(ids: List<String>) { cancelRequests(); store.orderAddons(ids) }
    @Synchronized fun visible(id: String, type: String, catalog: String, visible: Boolean) { cancelRequests(); store.catalogVisible(id, type, catalog, visible) }

    fun homeCatalogs(): List<CatalogQuery> {
        val queries: MutableList<CatalogQuery> = ArrayList()
        for (a in store.addons()) if (a.enabled) for (c in a.manifest.catalogs) {
            if (c.listable() && a.manifest.handles("catalog", c.type, null) && store.catalogVisible(a.id, c.type, c.id)) {
                queries.add(CatalogQuery(a, c, Collections.emptyMap()))
            }
        }
        return queries
    }
    @Throws(AddonException::class)
    fun search(text: String, scope: Cancellation): Result<Title> { return search(text, scope, java.util.function.Consumer { }) }
    /** One attempt, without the retries [searchPage] adds. */
    @Throws(AddonException::class)
    fun search(text: String, scope: Cancellation, progress: java.util.function.Consumer<Result<Title>>): Result<Title> {
        val remote = catalogs(searchQueries(text), scope, java.util.function.Consumer { partial -> progress.accept(searchResults(text, partial)) })
        return searchResults(text, remote)
    }
    private fun searchQueries(text: String): List<CatalogQuery> {
        val queries: MutableList<CatalogQuery> = ArrayList()
        for (a in store.addons()) if (a.enabled) for (c in a.manifest.catalogs)
            if (c.searchable() && a.manifest.handles("catalog", c.type, null)) queries.add(CatalogQuery(a, c, Collections.singletonMap("search", text)))
        return queries
    }
    private fun searchResults(text: String, remote: Result<Title>): Result<Title> {
        val deduplicated = LinkedHashMap<String, Title>()
        for (t in remote.items) deduplicated.putIfAbsent(t.discoveryKey(), t)
        val lower = text.lowercase(java.util.Locale.ROOT)
        for (e in store.library(false)) if (e.title.provider == "local" && e.title.name.lowercase(java.util.Locale.ROOT).contains(lower)) {
            deduplicated.putIfAbsent(e.title.discoveryKey(), e.title)
        }
        return Result(ArrayList(deduplicated.values), remote.failures, remote.stale)
    }
    /** Home/Search retry only failed resources. Successful shelves survive later attempts. */
    @Throws(AddonException::class)
    fun searchPage(text: String, scope: Cancellation, progress: java.util.function.Consumer<CatalogPage>): CatalogPage {
        val page = catalogsPage(searchQueries(text), scope, java.util.function.Consumer { partial -> progress.accept(
                CatalogPage(searchResults(text, partial.result), partial.attempt, partial.loading)) })
        return CatalogPage(searchResults(text, page.result), page.attempt, false)
    }
    @Throws(AddonException::class)
    fun catalogsPage(queries: List<CatalogQuery>, scope: Cancellation,
                     progress: java.util.function.Consumer<CatalogPage>): CatalogPage {
        scope.check()
        val parts: MutableMap<Int, Part<Title>> = java.util.TreeMap()
        val positions: MutableMap<String, Int> = LinkedHashMap()
        var pending: MutableList<Int> = ArrayList()
        for (i in 0 until queries.size) {
            val query = queries[i]
            val key = query.key()
            positions[key] = i; pending.add(i)
            var saved: List<Title> = Collections.emptyList()
            val cached = store.cached(query.addon.address.resource("catalog", query.catalog.type, query.catalog.id, query.extras))
            if (cached != null) try { saved = Title.catalog(query.addon.id, JSONObject(cached)) }
            catch (ignored: JSONException) { /* A damaged cache is replaced by the provider response. */ }
            catch (ignored: AddonException) { /* A damaged cache is replaced by the provider response. */ }
            parts[i] = Part(key, saved, null, !saved.isEmpty())
        }
        if (pending.isEmpty()) return CatalogPage(snapshot(parts), 1, false)
        var attempt = 1
        while (true) {
            scope.check(); val current = attempt
            progress.accept(CatalogPage(snapshot(parts), current, true))
            // Home can have many more resources than Search. Budget the bounded collection for its queue,
            // but start each catalogue's network deadline only when a worker admits it. Keep all workers busy:
            // a fast later shelf should not wait for the slowest request of an earlier group to finish.
            val waves = (pending.size + ProviderExecutor.PARALLELISM.toLong() - 1) / ProviderExecutor.PARALLELISM
            val deadline = SystemClock.elapsedRealtime() + waves * AddonClient.DISCOVERY_MS
            val tasks: MutableList<Callable<Part<Part<Title>>>> = ArrayList(); val keys: MutableList<String> = ArrayList()
            // Keep each resource's HTTP status through collection, so a 401 is never retried like a 503.
            for (index in pending) {
                val query = queries[index]; val key = parts[index]!!.key; keys.add(key)
                tasks.add(Callable { Part(key, Collections.singletonList(catalog(query, scope, AddonClient.deadline(), key)), null, false) })
            }
            val loaded = collect(tasks, keys, scope, deadline, java.util.function.Consumer { partial ->
                mergeCatalogParts(parts, positions, partial)
                val complete = partial.items.size + partial.failures.size == keys.size
                val retrying = current < CatalogPage.MAX_ATTEMPTS && parts.values.stream().anyMatch { retryable(it) }
                progress.accept(CatalogPage(snapshot(parts), current, !complete || retrying))
            })
            mergeCatalogParts(parts, positions, loaded); scope.check()
            val retry: MutableList<Int> = ArrayList()
            for (index in pending) if (retryable(parts[index]!!)) retry.add(index)
            if (retry.isEmpty() || attempt == CatalogPage.MAX_ATTEMPTS) return CatalogPage(snapshot(parts), attempt, false)
            progress.accept(CatalogPage(snapshot(parts), attempt, true))
            backoff(scope, attempt); pending = retry
            attempt++
        }
    }
    @Throws(AddonException::class)
    fun catalogs(queries: List<CatalogQuery>, scope: Cancellation): Result<Title> {
        return catalogs(queries, scope, java.util.function.Consumer { })
    }
    /** Bounded, cancellable category retries; recently visited pages do not ask their provider again. */
    @Throws(AddonException::class)
    fun catalogPage(query: CatalogQuery, scope: Cancellation, refresh: Boolean,
                    progress: java.util.function.Consumer<CatalogPage>): CatalogPage {
        scope.check()
        val revision = configuration
        val address = query.addon.address.resource("catalog", query.catalog.type, query.catalog.id, query.extras)
        val key = query.key()
        val cached: CachedCatalog?
        synchronized(catalogPages) { cached = catalogPages[address] }
        if (!refresh && cached != null && SystemClock.elapsedRealtime() - cached.saved < CATALOG_FRESH_MS)
            return AddonRepository.catalogPage(cached.part, 1, false)
        var previous: Part<Title> = if (cached == null) Part(key, Collections.emptyList(), null, false) else cached.part
        for (attempt in 1..CatalogPage.MAX_ATTEMPTS) {
            scope.check(); progress.accept(AddonRepository.catalogPage(previous, attempt, true))
            var loaded = catalog(query, scope, AddonClient.deadline(), key)
            scope.check()
            if (query.catalog.supports("skip") && loaded.items.size > CatalogPage.PAGE_SIZE)
                loaded = Part(loaded.key, ArrayList(loaded.items.subList(0, CatalogPage.PAGE_SIZE)), loaded.failure, loaded.stale, loaded.httpStatus)
            if (loaded.failure == null) {
                synchronized(catalogPages) {
                    scope.check()
                    if (revision == configuration) {
                        catalogPages[address] = CachedCatalog(loaded)
                        while (catalogPages.size > CATALOG_CACHE_PAGES
                                || catalogPages.values.stream().mapToInt { value -> value.part.items.size }.sum() > CATALOG_CACHE_PAGES * CatalogPage.PAGE_SIZE)
                            catalogPages.remove(catalogPages.keys.iterator().next())
                    }
                }
                return AddonRepository.catalogPage(loaded, attempt, false)
            }
            if (attempt == CatalogPage.MAX_ATTEMPTS || !retryable(loaded)) return AddonRepository.catalogPage(loaded, attempt, false)
            // Keep any saved posters visible during backoff. Cancellation wakes the wait immediately.
            previous = loaded
            progress.accept(AddonRepository.catalogPage(loaded, attempt, true))
            backoff(scope, attempt)
        }
        throw AssertionError("Unreachable catalogue attempt")
    }
    @Throws(AddonException::class)
    fun catalogs(queries: List<CatalogQuery>, scope: Cancellation, progress: java.util.function.Consumer<Result<Title>>): Result<Title> {
        val deadline = AddonClient.deadline()
        val tasks: MutableList<Callable<Part<Title>>> = ArrayList()
        val keys: MutableList<String> = ArrayList()
        for (q in queries) {
            val key = q.key(); keys.add(key)
            tasks.add(Callable { catalog(q, scope, deadline, key) })
        }
        return collect(tasks, keys, scope, deadline, progress)
    }
    private fun catalog(q: CatalogQuery, scope: Cancellation, deadline: Long, key: String): Part<Title> {
        val address = q.addon.address.resource("catalog", q.catalog.type, q.catalog.id, q.extras)
        try {
            if (!q.addon.enabled || !q.addon.manifest.handles("catalog", q.catalog.type, null)) throw AddonException(AddonException.Kind.MALFORMED, "Catalogue is unavailable")
            for (e in q.catalog.extras) if (e.required && !q.extras.containsKey(e.name)) throw AddonException(AddonException.Kind.MALFORMED, "A catalogue filter is required")
            for (extra in q.extras.keys) if (!q.catalog.supports(extra)) throw AddonException(AddonException.Kind.MALFORMED, "Unsupported catalogue filter")
            val titles = Title.catalog(q.addon.id, client.json(address, scope, deadline))
            val metadata = JSONArray(); for (title in titles) metadata.put(JSONObject(title.cacheJson()))
            synchronized(this) { scope.check(); store.cache(address, JSONObject().put("metas", metadata).toString()) }
            return Part(key, titles, null, false)
        } catch (e: AddonException) {
            if (e.kind != AddonException.Kind.CANCELLED) {
                val saved = store.cached(address)
                if (saved != null) try { return Part(key, Title.catalog(q.addon.id, JSONObject(saved)), e.kind, true, e.httpStatus) }
                catch (ignored: JSONException) { /* fall through to the provider error */ }
                catch (ignored: AddonException) { /* fall through to the provider error */ }
            }
            return Part(key, Collections.emptyList(), e.kind, false, e.httpStatus)
        } catch (e: JSONException) { return Part(key, Collections.emptyList(), AddonException.Kind.MALFORMED, false) }
    }
    @Throws(AddonException::class)
    fun metadata(provider: String, type: String, id: String, scope: Cancellation, maxAgeMs: Long = METADATA_FRESH_MS): Result<Title> {
        return metadata(provider, type, id, scope, java.util.function.Consumer { }, maxAgeMs)
    }
    /**
     * A title's details. Freshness rule: a provider's saved response younger than [maxAgeMs] is used without asking it
     * again; older ones are revalidated, and kept as a stale fallback when the provider fails. Pass 0 to always ask
     * (Refresh details, new-episode checks). Saved responses never hold playable addresses.
     */
    @Throws(AddonException::class)
    fun metadata(provider: String, type: String, id: String, scope: Cancellation, progress: java.util.function.Consumer<Result<Title>>,
                 maxAgeMs: Long = METADATA_FRESH_MS): Result<Title> {
        val deadline = AddonClient.deadline()
        val tasks: MutableList<Callable<Part<Title>>> = ArrayList(); val keys: MutableList<String> = ArrayList()
        // Origin first; other matching providers may enrich common identifiers.
        val addons: MutableList<InstalledAddon> = ArrayList(store.addons())
        addons.sortWith(java.util.Comparator.comparingInt { a: InstalledAddon -> if (a.id == provider) 0 else 1 })
        for (a in addons) if (a.enabled && a.manifest.handles("meta", type, id)) {
            keys.add(a.id)
            tasks.add(object : Callable<Part<Title>> {
                override fun call(): Part<Title> {
                    val address = a.address.resource("meta", type, id, Collections.emptyMap())
                    val fresh = try { store.cachedWithin(address, maxAgeMs) } catch (ignored: Exception) { null }
                    if (fresh != null) try {
                        val cached = Title.parse(a.id, JSONObject(fresh))
                        if (cached != null && cached.id == id && cached.type == type) {
                            freshHits.incrementAndGet()
                            return Part(a.id, Collections.singletonList(cached), null, false)
                        }
                    } catch (ignored: JSONException) { /* asked again below */ }
                    try {
                        metadataRequests.incrementAndGet()
                        val t = Title.parse(a.id, client.json(address, scope, deadline).optJSONObject("meta"))
                        if (t == null || t.id != id || t.type != type) throw AddonException(AddonException.Kind.MALFORMED, "Invalid title metadata")
                        synchronized(this@AddonRepository) { scope.check(); store.cache(address, t.cacheJson()) }
                        return Part(a.id, Collections.singletonList(t), null, false)
                    } catch (e: AddonException) {
                        val saved = if (e.kind == AddonException.Kind.CANCELLED) null else store.cached(address)
                        if (saved != null) try {
                            val cached = Title.parse(a.id, JSONObject(saved))
                            if (cached != null) return Part(a.id, Collections.singletonList(cached), e.kind, true)
                        } catch (ignored: JSONException) { /* provider failure */ }
                        return Part(a.id, Collections.emptyList(), e.kind, false)
                    }
                }
            })
        }
        // At most two metadata providers occupy sockets; stream discovery keeps room to run.
        val failures: MutableMap<String, AddonException.Kind> = LinkedHashMap()
        var start = 0
        while (start < tasks.size) {
            val end = Math.min(tasks.size, start + 2)
            val result = collect(tasks.subList(start, end), keys.subList(start, end), scope, deadline, progress, true)
            failures.putAll(result.failures)
            if (!result.items.isEmpty()) return Result(result.items, failures, result.stale, result.byResource)
            start += 2
        }
        return Result(Collections.emptyList(), failures, false)
    }
    /**
     * The add-on store, by list: built-in add-ons, curated torrent scrapers when using the public directory, then each list that installed add-ons publish through
     * `addon_catalog`, then the default directory's. An add-on appears once, in the first list naming it.
     * Lists are cached like catalogues, so a provider failure falls back to the saved list, marked stale.
     */
    @Throws(AddonException::class)
    fun store(scope: Cancellation): Result<AddonListing> {
        val deadline = AddonClient.deadline()
        val lists: MutableMap<String, String> = LinkedHashMap()
        for (a in store.addons()) if (a.enabled && a.manifest.declares(AddonManifest.ADDON_CATALOG)) {
            var combined = false
            for (c in a.manifest.addonCatalogs) combined = combined or (c.type == "all")
            // A list per type repeats the combined one; show the combined list when there is one.
            for (c in a.manifest.addonCatalogs) if (!combined || c.type == "all") {
                lists.putIfAbsent(a.address.resource(AddonManifest.ADDON_CATALOG, c.type, c.id, Collections.emptyMap()), c.name)
            }
        }
        for (manifest in directory) {
            val address = AddonUrl(manifest)
            for (list in DIRECTORY_LISTS) lists.putIfAbsent(address.resource(AddonManifest.ADDON_CATALOG, "all", list[0], Collections.emptyMap()), list[1])
        }
        val tasks: MutableList<Callable<Part<AddonListing>>> = ArrayList(); val keys: MutableList<String> = ArrayList()
        for (list in lists.entries) {
            val address = list.key
            val name = list.value
            val key = if (keys.contains(name)) name + " " + (keys.size + 1) else name
            keys.add(key)
            tasks.add(object : Callable<Part<AddonListing>> {
                override fun call(): Part<AddonListing> {
                    try {
                        val json = client.json(address, scope, deadline)
                        val found = AddonListing.parse(name, address, json)
                        synchronized(this@AddonRepository) { scope.check(); store.cache(address, json.toString()) }
                        return Part(key, found, null, false)
                    } catch (e: AddonException) {
                        val saved = if (e.kind == AddonException.Kind.CANCELLED) null else store.cached(address)
                        if (saved != null) try { return Part(key, AddonListing.parse(name, address, JSONObject(saved)), e.kind, true) }
                        catch (ignored: JSONException) { /* fall through to the provider error */ }
                        catch (ignored: AddonException) { /* fall through to the provider error */ }
                        return Part(key, Collections.emptyList(), e.kind, false)
                    }
                }
            })
        }
        val remote = collect(tasks, keys, scope, deadline)
        return combineListings(remote, if (directory.contains(DIRECTORY)) TorrentAddons.listings() else Collections.emptyList())
    }

    /** Enabled add-ons declaring `resource` for this type and ID. Catalogue-only add-ons such as Cinemeta have no streams. */
    fun providers(resource: String, type: String, id: String?): List<InstalledAddon> {
        val found: MutableList<InstalledAddon> = ArrayList()
        for (a in store.addons()) if (a.enabled && a.manifest.handles(resource, type, id)) found.add(a)
        return found
    }
    @Throws(AddonException::class)
    fun streams(type: String, videoId: String, scope: Cancellation): Result<MediaSource> {
        return streams(type, videoId, scope, java.util.function.Consumer { })
    }
    @Throws(AddonException::class)
    fun streams(type: String, videoId: String, scope: Cancellation, progress: java.util.function.Consumer<Result<MediaSource>>): Result<MediaSource> {
        return resource("stream", type, videoId, Collections.emptyMap(), "streams", Parser { provider, json -> MediaSource.list(provider, json) }, scope, progress)
    }
    @Throws(AddonException::class)
    fun subtitles(type: String, videoId: String, hints: Map<String, String>, scope: Cancellation): Result<SubtitleSource> {
        // Preserve provider episode IDs; forward only subtitle matching hints, never media request headers.
        val extras: MutableMap<String, String> = LinkedHashMap()
        for (key in arrayOf("filename", "videoHash", "videoSize")) if (hints.containsKey(key)) extras[key] = hints[key]!!
        return resource("subtitles", type, videoId, extras, "subtitles", Parser { provider, json -> SubtitleSource.list(provider, json) }, scope, java.util.function.Consumer { })
    }
    private fun interface Parser<T> { fun parse(provider: String, json: JSONObject): List<T> }
    @Throws(AddonException::class)
    private fun <T> resource(resource: String, type: String, id: String, extras: Map<String, String>, field: String, parser: Parser<T>, scope: Cancellation, progress: java.util.function.Consumer<Result<T>>): Result<T> {
        val deadline = AddonClient.deadline()
        val tasks: MutableList<Callable<Part<T>>> = ArrayList(); val keys: MutableList<String> = ArrayList()
        for (a in store.addons()) if (a.enabled && a.manifest.handles(resource, type, id)) {
            keys.add(a.id)
            tasks.add(object : Callable<Part<T>> {
                override fun call(): Part<T> {
                    try {
                        val json = client.json(a.address.resource(resource, type, id, extras), scope, deadline)
                        if (json.optJSONArray(field) == null) throw AddonException(AddonException.Kind.MALFORMED, "Invalid resource response")
                        return Part(a.id, parser.parse(a.id, json), null, false)
                    } catch (e: AddonException) { return Part(a.id, Collections.emptyList(), e.kind, false) }
                }
            })
        }
        // Playable addresses/subtitles are deliberately never persisted.
        return collect(tasks, keys, scope, deadline, progress)
    }
    @Throws(AddonException::class)
    private fun <T> collect(tasks: List<Callable<Part<T>>>, keys: List<String>, scope: Cancellation, deadline: Long): Result<T> {
        return collect(tasks, keys, scope, deadline, java.util.function.Consumer { })
    }
    @Throws(AddonException::class)
    private fun <T> collect(tasks: List<Callable<Part<T>>>, keys: List<String>, scope: Cancellation, deadline: Long, progress: java.util.function.Consumer<Result<T>>): Result<T> {
        return collect(tasks, keys, scope, deadline, progress, false)
    }
    @Throws(AddonException::class)
    private fun <T> collect(tasks: List<Callable<Part<T>>>, keys: List<String>, scope: Cancellation, deadline: Long,
            progress: java.util.function.Consumer<Result<T>>, firstUseful: Boolean): Result<T> {
        scope.check()
        val completion = ExecutorCompletionService<Part<T>>(Executor { command -> workers.execute(command, scope) })
        val pending: MutableMap<Future<Part<T>>, Int> = LinkedHashMap()
        val finished: MutableMap<Int, Part<T>> = java.util.TreeMap()
        val aborts: MutableList<Runnable> = ArrayList()
        try {
            for (i in 0 until tasks.size) {
                scope.check()
                try {
                    val future = completion.submit(tasks[i]); pending[future] = i
                    val abort = Runnable { future.cancel(true) }; aborts.add(abort); scope.add(abort)
                } catch (e: RejectedExecutionException) { finished[i] = Part(keys[i], Collections.emptyList(), AddonException.Kind.TIMEOUT, false) }
            }
            while (!pending.isEmpty()) {
                scope.check()
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                val ready = completion.poll(Math.min(100L, left), TimeUnit.MILLISECONDS) ?: continue
                val index = pending.remove(ready)!!
                try { finished[index] = ready.get() }
                catch (e: ExecutionException) { finished[index] = Part(keys[index], Collections.emptyList(), AddonException.Kind.MALFORMED, false) }
                scope.check(); progress.accept(snapshot(finished))
                if (firstUseful && !finished[index]!!.items.isEmpty()) break
            }
            for (e in pending.entries) if (!firstUseful || finished.values.stream().noneMatch { part -> !part.items.isEmpty() }) finished[e.value] = Part(keys[e.value], Collections.emptyList(), AddonException.Kind.TIMEOUT, false)
            scope.check()
            val result = snapshot(finished); progress.accept(result); return result
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AddonException(AddonException.Kind.CANCELLED, "Request cancelled")
        } catch (e: java.util.concurrent.CancellationException) {
            throw AddonException(AddonException.Kind.CANCELLED, "Request cancelled")
        } finally {
            for (abort in aborts) { scope.remove(abort); abort.run() }
            workers.purge()
        }
    }
    override fun close() { cancelRequests(); workers.shutdownNow(); client.close() }

    companion object {
        /** Stremio's add-on directory: Cinemeta publishes the Official and Community lists through addon_catalog. */
        const val DIRECTORY = AddonUrl.CINEMETA
        private val DIRECTORY_LISTS = arrayOf(arrayOf("official", "Official"), arrayOf("community", "Community"))
        private const val CATALOG_FRESH_MS = 5 * 60_000L
        /** Details change rarely (new episodes, artwork); after this a title's saved details are revalidated. */
        const val METADATA_FRESH_MS = 6 * 60 * 60_000L
        private const val CATALOG_CACHE_PAGES = 12

        private fun mergeCatalogParts(parts: MutableMap<Int, Part<Title>>, positions: Map<String, Int>, update: Result<Part<Title>>) {
            for (part in update.items) parts[positions[part.key]!!] = part
            for (failure in update.failures.entries) {
                val index: Int = positions[failure.key]!!; val previous = parts[index]!!
                parts[index] = Part(previous.key, previous.items, failure.value, !previous.items.isEmpty())
            }
        }
        @Throws(AddonException::class)
        private fun backoff(scope: Cancellation, attempt: Int) {
            val wake = java.util.concurrent.CountDownLatch(1)
            val abort = Runnable { wake.countDown() }; scope.add(abort)
            try { wake.await(attempt * 1000L, TimeUnit.MILLISECONDS) }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw AddonException(AddonException.Kind.CANCELLED, "Request cancelled") }
            finally { scope.remove(abort) }
            scope.check()
        }
        private fun catalogPage(part: Part<Title>, attempt: Int, loading: Boolean): CatalogPage {
            val parts: Map<Int, Part<Title>> = Collections.singletonMap(0, part)
            return CatalogPage(snapshot(parts), attempt, loading)
        }
        private fun retryable(part: Part<*>): Boolean {
            return part.failure == AddonException.Kind.NETWORK || part.failure == AddonException.Kind.TIMEOUT
                    || part.failure == AddonException.Kind.HTTP && (part.httpStatus == 408 || part.httpStatus == 429 || part.httpStatus >= 500)
        }

        /** Merges local descriptors with directory results, including when every remote list has failed. No provider is contacted. */
        fun combineListings(remote: Result<AddonListing>, scrapers: List<AddonListing>): Result<AddonListing> {
            val groups: MutableMap<String, List<AddonListing>> = LinkedHashMap()
            val all: MutableList<AddonListing> = ArrayList()
            val shown: MutableSet<String> = java.util.HashSet()
            groups["Built in"] = BuiltinAddons.listings()
            for (l in BuiltinAddons.listings()) { shown.add(l.manifest.id); all.add(l) }
            // Fixture/custom directories never acquire public providers as a side effect. These descriptors need no
            // network request; the selected add-on is contacted only when its review is opened.
            if (!scrapers.isEmpty()) {
                groups[TorrentAddons.COLLECTION] = scrapers
                for (l in scrapers) { shown.add(l.manifest.id); all.add(l) }
            }
            for (group in remote.byResource.entries) {
                val unique: MutableList<AddonListing> = ArrayList()
                for (l in group.value) if (shown.add(l.manifest.id)) unique.add(l)
                all.addAll(unique)
                if (!unique.isEmpty()) groups[group.key] = Collections.unmodifiableList(unique)
            }
            return Result(all, remote.failures, remote.stale, groups)
        }
        private fun <T> snapshot(finished: Map<Int, Part<T>>): Result<T> {
            val items: MutableList<T> = ArrayList(); val failures: MutableMap<String, AddonException.Kind> = LinkedHashMap()
            val groups: MutableMap<String, List<T>> = LinkedHashMap(); var stale = false
            for (part in finished.values) {
                items.addAll(part.items); groups[part.key] = Collections.unmodifiableList(ArrayList(part.items))
                if (part.failure != null) failures[part.key] = part.failure; stale = stale or part.stale
            }
            return Result(items, failures, stale, groups)
        }
    }
}
