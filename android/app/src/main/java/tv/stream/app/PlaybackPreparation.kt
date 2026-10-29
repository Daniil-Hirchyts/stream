package tv.stream.app

import android.os.SystemClock
import java.util.ArrayList
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.function.Consumer
import tv.stream.app.addon.*

/** Process-local metadata/source handoff. Never opens a torrent, downloads payload, or saves an address. */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
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
        val cached: Title? = if (current != null && current!!.metadataReady && current!!.configuration == addons.configuration()
                && current!!.titleKey.equals(title.key()) && now - current!!.created < 60_000) current!!.title else null
        discard()
        val entry = Entry(title, video, addons.configuration(), now, addons.newRequest(), addons.newRequest())
        current = entry
        if (cached != null) { entry.title = cached; entry.metadataReady = true; entry.metadataScope.close() }
        else entry.metadataTask = workers.submit(Runnable {
            try {
                addons.metadata(title.provider, title.type, title.id, entry.metadataScope, Consumer { result ->
                    if (!result.items.isEmpty()) synchronized(entry) {
                        entry.title = result.items.get(0).withProvider(title.provider); entry.metadataReady = true; entry.version++; (entry as Object).notifyAll()
                    }
                })
            } catch (ignored: AddonException) { /* cached details remain useful */ }
            finally { synchronized(entry) { entry.metadataReady = true; entry.version++; (entry as Object).notifyAll() }; entry.metadataScope.close() }
        })
        if (title.type.equals("series") && video.equals(title.id)) { entry.complete = true; return entry }
        entry.streamTask = workers.submit(Runnable {
            try {
                addons.streams(title.type, video, entry.scope, Consumer { result ->
                    synchronized(entry) { entry.result = result; entry.version++; (entry as Object).notifyAll() }
                })
            } catch (ignored: AddonException) { /* cancellation or an unavailable provider */ }
            finally { synchronized(entry) { entry.complete = true; entry.version++; (entry as Object).notifyAll() } }
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
    @Synchronized fun invalidate(title: Title, video: String?) {
        if (current != null && current!!.titleKey.equals(title.key()) && (video == null || current!!.video.equals(video))) discard()
    }
    @Synchronized fun leave() { discard() }
    private fun discard() { if (current != null) { current!!.close(); current = null }; workers.purge() }
    @Synchronized override fun close() {
        discard(); for (entry in pending.values) entry.close(); pending.clear(); workers.shutdownNow()
    }

    class Entry(title: Title, @JvmField val video: String, @JvmField val configuration: Long, @JvmField val created: Long, @JvmField val scope: Cancellation, @JvmField val metadataScope: Cancellation) : AutoCloseable {
        @JvmField val token: String = UUID.randomUUID().toString()
        @JvmField val titleKey: String
        @JvmField @Volatile var title: Title
        @JvmField @Volatile var complete = false
        @JvmField @Volatile var metadataReady = false
        @JvmField @Volatile var claimed = false
        // Private in Java; Kotlin outer classes cannot reach a nested class's private members.
        @JvmField var result: AddonRepository.Result<MediaSource>? = null
        @JvmField var version = 0
        @JvmField var metadataTask: java.util.concurrent.Future<*>? = null
        @JvmField var streamTask: java.util.concurrent.Future<*>? = null
        init {
            this.title = title; titleKey = title.key()
            scope.priority(2); metadataScope.priority(2)
        }
        fun matches(title: Title, video: String?, configuration: Long, now: Long): Boolean {
            return !scope.isCancelled() && this.configuration == configuration && titleKey.equals(title.key()) && this.video.equals(video) && now - created < 60_000
        }
        @Synchronized fun sources(): MutableList<MediaSource> {
            val sources: MutableList<MediaSource> = ArrayList(if (result == null) Collections.emptyList() else result!!.items)
            for (episode in title.episodes) if (episode.id.equals(video)) sources.addAll(episode.streams)
            return sources
        }
        @Synchronized fun version(): Int = version
        @Synchronized fun result(): AddonRepository.Result<MediaSource> {
            val base = if (result == null) AddonRepository.Result<MediaSource>(Collections.emptyList(), Collections.emptyMap(), false) else result!!
            return base.withItems(sources())
        }
        @Throws(Exception::class)
        fun awaitMetadata(caller: Cancellation): Title {
            synchronized(this) { while (!metadataReady) { caller.check(); scope.check(); (this as Object).wait(100) }; return title }
        }
        @Throws(Exception::class)
        fun awaitCandidate(caller: Cancellation, explicit: String?): List<MediaSource> {
            var first: Long = 0; var rankedVersion = -1; var candidates: List<MediaSource> = Collections.emptyList()
            synchronized(this) {
                while (true) {
                    caller.check(); scope.check()
                    if (rankedVersion != version) { candidates = SourcePolicy.ranked(sources()); rankedVersion = version }
                    val ready = if (explicit == null || explicit.isEmpty()) !candidates.isEmpty()
                            else candidates.stream().anyMatch { source -> SourcePolicy.key(source).equals(explicit) }
                    val now = SystemClock.elapsedRealtime()
                    if (ready && first == 0L) first = now
                    if (complete && (metadataReady || ready) || ready && (now - first >= 750 || explicit != null && !explicit.isEmpty())) return candidates
                    (this as Object).wait(50)
                }
            }
        }
        @Throws(Exception::class)
        fun awaitAlternative(caller: Cancellation, attempted: Set<String>, providers: Set<String>, deadline: Long): List<MediaSource> {
            var rankedVersion = -1; var candidates: List<MediaSource> = Collections.emptyList()
            synchronized(this) {
                while (true) {
                    caller.check(); scope.check()
                    if (rankedVersion != version) { candidates = SourcePolicy.ranked(sources()); rankedVersion = version }
                    if (SourcePolicy.nextCandidate(candidates, attempted, providers) >= 0 || complete && metadataReady || SystemClock.elapsedRealtime() >= deadline) return candidates
                    (this as Object).wait(50)
                }
            }
        }
        @Throws(Exception::class)
        fun awaitComplete(caller: Cancellation, progress: Consumer<AddonRepository.Result<MediaSource>>) {
            var seen = -1
            while (true) {
                var snapshot: AddonRepository.Result<MediaSource>? = null; var done = false
                var waited = false
                synchronized(this) {
                    caller.check(); scope.check()
                    if (seen == version && !(complete && metadataReady)) { (this as Object).wait(100); waited = true }
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
            synchronized(this) { (this as Object).notifyAll() }
        }
    }
}
