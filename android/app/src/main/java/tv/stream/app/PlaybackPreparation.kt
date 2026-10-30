package tv.stream.app

import android.os.SystemClock
import java.util.ArrayList
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.function.Consumer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import tv.stream.app.addon.*

/** Process-local metadata/source handoff. Never opens a torrent, downloads payload, or saves an address. */
class PlaybackPreparation(private val addons: AddonRepository) : AutoCloseable {
    private val workers: java.util.concurrent.ThreadPoolExecutor = Executors.newFixedThreadPool(2) as java.util.concurrent.ThreadPoolExecutor
    private var current: Entry? = null
    // Claims belong to Play until the activity takes them, independently of the next Details page.
    private val pending: MutableMap<String?, Entry> = java.util.LinkedHashMap()
    @Synchronized fun prepare(title: Title, video: String): Entry {
        val now = SystemClock.elapsedRealtime()
        pending.values.removeIf { entry ->
            if (entry.scope.isCancelled() || now - entry.created >= 60_000) { entry.close(); return@removeIf true }
            false
        }
        if (current != null && current!!.matches(title, video, addons.configuration(), now)) return current!!
        val forceFresh = revalidate.remove(title.key())
        val cached: Title? = if (!forceFresh && current != null && current!!.metadataReady && current!!.configuration == addons.configuration()
                && current!!.titleKey.equals(title.key()) && now - current!!.created < 60_000) current!!.title else null
        discard()
        val entry = Entry(title, video, addons.configuration(), now, addons.newRequest(), addons.newRequest())
        current = entry
        if (cached != null) { entry.title = cached; entry.metadataReady = true; entry.metadataScope.close() }
        val maxAge = if (forceFresh) 0L else AddonRepository.METADATA_FRESH_MS
        if (cached == null) entry.metadataTask = workers.submit(Runnable {
            try {
                addons.metadata(title.provider, title.type, title.id, entry.metadataScope, Consumer { result ->
                    result.items.firstOrNull()?.let { entry.publishMetadata(it.withProvider(title.provider)) }
                }, maxAge)
            } catch (ignored: AddonException) { /* cached details remain useful */ }
            finally { entry.finishMetadata(); entry.metadataScope.close() }
        })
        if (title.type.equals("series") && video.equals(title.id)) { entry.complete = true; return entry }
        entry.streamTask = workers.submit(Runnable {
            try {
                addons.streams(title.type, video, entry.scope, Consumer { result ->
                    entry.publishSources(result)
                })
            } catch (ignored: AddonException) { /* cancellation or an unavailable provider */ }
            finally { entry.finishSources() }
        })
        return entry
    }
    @Synchronized fun claim(title: Title, video: String): Entry {
        val entry = prepare(title, video)
        current = null; entry.claimed = true
        pending[entry.token] = entry
        while (pending.size > 4) pending.remove(pending.keys.iterator().next())!!.close()
        promote(entry)
        return entry
    }
    @Synchronized fun take(token: String?, title: Title, video: String): Entry {
        var entry: Entry? = pending.remove(token)
        if (entry == null || !entry.matches(title, video, addons.configuration(), SystemClock.elapsedRealtime())) {
            if (entry != null) entry.close()
            entry = prepare(title, video); current = null
        }
        entry.claimed = true; promote(entry); return entry
    }
    private fun promote(entry: Entry) {
        entry.metadataScope.priority(0); addons.prioritize(entry.scope)
    }
    /** Titles whose next details load must ask providers again (Refresh details). */
    private val revalidate = HashSet<String>()

    @Synchronized fun invalidate(title: Title, video: String?) {
        if (video == null) revalidate.add(title.key())
        if (current != null && current!!.titleKey.equals(title.key()) && (video == null || current!!.video.equals(video))) discard()
    }
    @Synchronized fun leave() { discard() }
    private fun discard() { if (current != null) { current!!.close(); current = null }; workers.purge() }
    @Synchronized override fun close() {
        discard(); for (entry in pending.values) entry.close(); pending.clear(); workers.shutdownNow()
    }

    class Entry(title: Title, val video: String, val configuration: Long, val created: Long, val scope: Cancellation, val metadataScope: Cancellation) : AutoCloseable {
        val token: String = UUID.randomUUID().toString()
        val titleKey: String
        @Volatile var title: Title
        @Volatile var complete = false
        @Volatile var metadataReady = false
        @Volatile var claimed = false
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private var result: AddonRepository.Result<MediaSource>? = null
        private var version = 0
        var metadataTask: java.util.concurrent.Future<*>? = null
        var streamTask: java.util.concurrent.Future<*>? = null
        init {
            this.title = title; titleKey = title.key()
            scope.priority(2); metadataScope.priority(2)
        }
        fun matches(title: Title, video: String?, configuration: Long, now: Long): Boolean {
            return !scope.isCancelled() && this.configuration == configuration && titleKey.equals(title.key()) && this.video.equals(video) && now - created < 60_000
        }
        fun publishMetadata(value: Title) = lock.withLock {
            title = value; metadataReady = true; version++; changed.signalAll()
        }
        fun finishMetadata() = lock.withLock {
            metadataReady = true; version++; changed.signalAll()
        }
        fun publishSources(value: AddonRepository.Result<MediaSource>) = lock.withLock {
            result = value; version++; changed.signalAll()
        }
        fun finishSources() = lock.withLock {
            complete = true; version++; changed.signalAll()
        }
        fun sources(): MutableList<MediaSource> = lock.withLock {
            val sources = (result?.items ?: emptyList()).toMutableList()
            title.episodes.filter { it.id == video }.forEach { sources.addAll(it.streams) }
            sources
        }
        fun version(): Int = lock.withLock { version }
        fun result(): AddonRepository.Result<MediaSource> = lock.withLock {
            val base = result ?: AddonRepository.Result(emptyList(), emptyMap(), false)
            base.withItems(sources())
        }
        @Throws(Exception::class)
        fun awaitMetadata(caller: Cancellation): Title {
            lock.withLock {
                while (!metadataReady) { caller.check(); scope.check(); changed.await(100, TimeUnit.MILLISECONDS) }
                return title
            }
        }
        @Throws(Exception::class)
        fun awaitCandidate(caller: Cancellation, explicit: String?): List<MediaSource> {
            var first: Long = 0; var rankedVersion = -1; var candidates: List<MediaSource> = Collections.emptyList()
            lock.withLock {
                while (true) {
                    caller.check(); scope.check()
                    if (rankedVersion != version) { candidates = SourcePolicy.ranked(sources()); rankedVersion = version }
                    val ready = if (explicit == null || explicit.isEmpty()) !candidates.isEmpty()
                            else candidates.any { source -> SourcePolicy.key(source) == explicit }
                    val now = SystemClock.elapsedRealtime()
                    if (ready && first == 0L) first = now
                    if (complete && (metadataReady || ready) || ready && (now - first >= 750 || explicit != null && !explicit.isEmpty())) return candidates
                    changed.await(50, TimeUnit.MILLISECONDS)
                }
            }
        }
        @Throws(Exception::class)
        fun awaitAlternative(caller: Cancellation, attempted: Set<String>, providers: Set<String>, deadline: Long): List<MediaSource> {
            var rankedVersion = -1; var candidates: List<MediaSource> = Collections.emptyList()
            lock.withLock {
                while (true) {
                    caller.check(); scope.check()
                    if (rankedVersion != version) { candidates = SourcePolicy.ranked(sources()); rankedVersion = version }
                    if (SourcePolicy.nextCandidate(candidates, attempted, providers) >= 0 || complete && metadataReady || SystemClock.elapsedRealtime() >= deadline) return candidates
                    changed.await(50, TimeUnit.MILLISECONDS)
                }
            }
        }
        @Throws(Exception::class)
        fun awaitComplete(caller: Cancellation, progress: Consumer<AddonRepository.Result<MediaSource>>) {
            var seen = -1
            while (true) {
                var snapshot: AddonRepository.Result<MediaSource>? = null; var done = false
                var waited = false
                lock.withLock {
                    caller.check(); scope.check()
                    if (seen == version && !(complete && metadataReady)) { changed.await(100, TimeUnit.MILLISECONDS); waited = true }
                    else { seen = version; snapshot = result(); done = complete && metadataReady }
                }
                if (waited) continue
                if (snapshot != null) progress.accept(snapshot!!)
                if (done) return
            }
        }
        override fun close() {
            scope.close(); metadataScope.close()
            if (metadataTask != null) metadataTask!!.cancel(true)
            if (streamTask != null) streamTask!!.cancel(true)
            lock.withLock { changed.signalAll() }
        }
    }
}
