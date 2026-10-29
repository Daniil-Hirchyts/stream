package tv.stream.app

import org.json.JSONException
import org.json.JSONObject
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import tv.stream.app.addon.AddonException
import tv.stream.app.addon.AddonRepository
import tv.stream.app.addon.Title
import tv.stream.app.data.TvStore

/**
 * New episodes of the series the viewer follows.
 *
 * A series is followed while it is in the library and the viewer has finished one of its episodes (in the last two
 * years) or made it a favourite, unless they turned its alerts off. Its next episode is the one after the furthest
 * episode they finished, specials aside; for a favourite they never played, the first. That episode is new when it has
 * aired since they finished the one before it (or favourited the show) and they haven't started it. A viewer who is
 * behind already knows the following episodes exist, and one who started it finds it in Continue Watching.
 *
 * A notice never stays for good: it goes once the episode is played, when the viewer hides it, after Home has shown
 * it on [SHOWN_DAYS] different days, or [LIFETIME_MS] after it first appeared. One that ends unplayed is
 * not shown again, so ignoring it quietens the show until the viewer watches it again; once caught up, the next
 * episode is new again. The library's saved episode lists are refreshed in the background by [check].
 */
object NewEpisodes {
    const val DAY_MS = 86_400_000L
    /** How often one followed series' episode list is fetched again. */
    const val CHECK_MS = 12 * 3_600_000L
    /** Series checked per run, the most recently watched first. */
    const val CHECKS_PER_RUN = 24
    const val SHOWN_DAYS = 7
    const val LIFETIME_MS = 30 * DAY_MS
    /** A series nobody has watched for this long is no longer followed, unless it is a favourite. */
    const val FOLLOW_MS = 730 * DAY_MS
    /** Per series (by title key): when it was checked, whether alerts are off, and the current notice's history. */
    private const val STATE = "newEpisodes"
    private val checking = AtomicBoolean()

    class Notice(val title: Title,
                 /** The first new episode: the one to watch next. */
                 val episode: Title.Episode,
                 /** The new episodes, from [episode] on. */
                 episodes: Set<String>) {
        val episodes: Set<String> = Collections.unmodifiableSet(LinkedHashSet(episodes))

        fun label(): String {
            if (episode.number == 1 && episode.season > 1) return "New season"
            return if (episodes.size > 1) episodes.size.toString() + " new episodes" else "New episode"
        }
    }

    /** The series' episodes in viewing order, without specials. */
    fun order(title: Title): List<Title.Episode> {
        val out: MutableList<Title.Episode> = ArrayList()
        var numbered = true
        for (e in title.episodes) if (e.season != 0) { out.add(e); numbered = numbered and (e.season > 0 && e.number >= 0) }
        if (numbered) out.sortWith(Comparator.comparingInt { e: Title.Episode -> e.season }.thenComparingInt { e -> e.number })
        return out
    }
    fun finished(p: TvStore.Progress?): Boolean {
        return p != null && (p.watched || WatchCompletion.nearlyFinished(p.positionMs, p.durationMs))
    }
    private fun furthest(order: List<Title.Episode>, progress: Map<String, TvStore.Progress>): Int {
        for (i in order.size - 1 downTo 0) if (finished(progress[order[i].id])) return i
        return -1
    }
    /** The episode after the furthest one finished (the first when none is) that has aired; null when there is none yet. */
    fun nextUp(title: Title, progress: Map<String, TvStore.Progress>, now: Long): Title.Episode? {
        val order = order(title)
        val next = furthest(order, progress) + 1
        if (next >= order.size) return null
        val aired = aired(order[next].released)
        return if (aired > now) null else order[next]
    }

    /**
     * When an episode aired, from the provider's ISO date or time; -1 when it doesn't say. A date alone is midnight UTC,
     * the provider's broadcast day.
     */
    fun aired(released: String?): Long {
        if (released == null || released.length < 10) return -1
        try { return Instant.parse(released).toEpochMilli() }
        catch (notAnInstant: DateTimeException) {
            try { return LocalDate.parse(released.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
            catch (invalid: DateTimeException) { return -1 }
        }
    }

    /**
     * The notice a series deserves now, before what the viewer did with earlier ones.
     * @param favouritedMs when a favourite never finished was saved, its starting point; below zero when not a favourite
     */
    fun find(title: Title, progress: Map<String, TvStore.Progress>, favouritedMs: Long, now: Long): Notice? {
        if (title.type != "series") return null
        val order = order(title)
        val furthest = furthest(order, progress)
        if (furthest < 0 && favouritedMs < 0) return null
        // Dates are often a broadcast day at midnight UTC, so one airing the day the viewer finished the last still
        // counts; but never one released together with it (a season out at once), which they have already seen listed.
        val finishedAt = if (furthest >= 0) progress[order[furthest].id]!!.updatedMs else favouritedMs
        val since = Math.max(finishedAt - DAY_MS, if (furthest >= 0) aired(order[furthest].released) else -1L)
        val fresh: MutableSet<String> = LinkedHashSet()
        var first: Title.Episode? = null
        for (i in furthest + 1 until order.size) {
            val e = order[i]
            val aired = aired(e.released)
            val p = progress[e.id]
            // Below Continue Watching's threshold, an episode opened for a moment still counts as unstarted.
            val unstarted = p == null || p.positionMs < TvStore.RESUMABLE_MS
            if (i == furthest + 1 && (aired <= since || aired > now || !unstarted)) return null
            if (aired > since && aired <= now && unstarted) { fresh.add(e.id); if (first == null) first = e }
        }
        return if (first == null) null else Notice(title, first, Collections.unmodifiableSet(fresh))
    }

    /** Current notices, by title key, in library order. Home passes `shown`: each day it shows one counts. */
    fun current(store: TvStore, library: List<TvStore.LibraryEntry>, history: List<TvStore.Progress>, now: Long, shown: Boolean): Map<String, Notice> {
        val progress = byTitle(history)
        val out: MutableMap<String, Notice> = LinkedHashMap()
        val today: String? = if (shown) Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString() else null
        synchronized(store) {
            val ledger = Ledger(store)
            ledger.prune(library)
            for (entry in library) {
                val notice = ledger.evaluate(entry, progress.getOrDefault(entry.title.key(), Collections.emptyMap()), now, today)
                if (notice != null) out[entry.title.key()] = notice
            }
            ledger.save(store)
        }
        return out
    }
    fun current(store: TvStore, now: Long): Map<String, Notice> {
        return current(store, store.library(false), store.history(false), now, false)
    }
    /** One saved series' notice, as [current] gives it; showing it elsewhere than Home doesn't count. */
    fun notice(store: TvStore, entry: TvStore.LibraryEntry, seen: Map<String, TvStore.Progress>, now: Long): Notice? {
        synchronized(store) {
            val ledger = Ledger(store)
            val notice = ledger.evaluate(entry, seen, now, null)
            ledger.save(store)
            return notice
        }
    }

    /** Hides the series' current notice; a later episode brings a new one. */
    fun hide(store: TvStore, titleKey: String) {
        update(store, titleKey, Change { show -> if (show.has("episode")) show.put("gone", true) })
    }
    /** Turns a series' alerts off or back on. Its episode list is no longer checked while they are off. */
    fun mute(store: TvStore, titleKey: String, muted: Boolean) { update(store, titleKey, Change { show -> show.put("muted", muted) }) }
    fun muted(store: TvStore, titleKey: String): Boolean {
        synchronized(store) { return Ledger(store).show(titleKey).optBoolean("muted") }
    }

    /**
     * Fetches the episode lists of followed series not checked for [CHECK_MS], most recently watched first, and
     * saves them over the library's copies. Quiet: one run at a time, ranked below every other provider request (Play
     * closes it), and a series whose provider doesn't answer is asked again next time.
     * @return whether a saved series changed
     */
    fun check(store: TvStore, addons: AddonRepository, now: Long): Boolean {
        if (!checking.compareAndSet(false, true)) return false
        try {
            addons.newRequest().use { scope ->
                scope.priority(3)
                val progress = byTitle(store.history(false))
                val ledger: Ledger = synchronized(store) { Ledger(store) }
                val due: MutableList<TvStore.LibraryEntry> = ArrayList()
                val activity: MutableMap<String, Long> = HashMap()
                for (entry in store.library(false)) {
                    val key = entry.title.key()
                    val seen = progress.getOrDefault(key, Collections.emptyMap())
                    val show = ledger.show(key)
                    if (entry.title.provider == "local" || !followed(entry, seen, now) || show.optBoolean("muted")) continue
                    if (now - show.optLong("checked") < CHECK_MS) continue
                    due.add(entry); activity[key] = lastActivity(entry, seen)
                }
                due.sortWith(Comparator.comparingLong { e: TvStore.LibraryEntry -> activity[e.title.key()]!! }.reversed())
                var changed = false
                val checked: MutableList<String> = ArrayList()
                for (entry in due.subList(0, Math.min(CHECKS_PER_RUN, due.size))) {
                    val saved = entry.title
                    val found: AddonRepository.Result<Title> = try { addons.metadata(saved.provider, saved.type, saved.id, scope) }
                    catch (cancelled: AddonException) { break }
                    // A saved copy offered while the provider is unavailable is no news: ask again next time.
                    if (found.items.isEmpty() || found.stale) continue
                    val fresh = found.items[0].withProvider(saved.provider)
                    if (fresh.cacheJson() != saved.cacheJson()) { store.refresh(fresh); changed = true }
                    checked.add(saved.key())
                }
                for (key in checked) update(store, key, Change { show -> show.put("checked", now) })
                return changed
            }
        } finally { checking.set(false) }
    }

    private fun followed(entry: TvStore.LibraryEntry, seen: Map<String, TvStore.Progress>, now: Long): Boolean {
        if (entry.title.type != "series") return false
        if (entry.favourite) return true
        var finished = false
        for (p in seen.values) finished = finished or finished(p)
        return finished && now - lastActivity(entry, seen) < FOLLOW_MS
    }
    private fun lastActivity(entry: TvStore.LibraryEntry, seen: Map<String, TvStore.Progress>): Long {
        var last = entry.updatedMs
        for (p in seen.values) last = Math.max(last, p.updatedMs)
        return last
    }
    private fun byTitle(history: List<TvStore.Progress>): Map<String, Map<String, TvStore.Progress>> {
        val out: MutableMap<String, MutableMap<String, TvStore.Progress>> = HashMap()
        for (p in history) out.computeIfAbsent(p.titleKey) { HashMap() }[p.video] = p
        return out
    }

    private fun interface Change { @Throws(JSONException::class) fun apply(show: JSONObject) }
    private fun update(store: TvStore, titleKey: String, change: Change) {
        synchronized(store) {
            val ledger = Ledger(store)
            try { change.apply(ledger.edit(titleKey)) } catch (e: JSONException) { throw IllegalStateException(e) }
            ledger.save(store)
        }
    }

    /**
     * The saved state of every series, read and changed under the store's lock and saved once if anything changed.
     * Per series: `checked` (last check), `muted`, and the current notice's `episode`, `first`
     * appearance, `days` shown, last `day` shown and whether it is `gone`.
     */
    private class Ledger(store: TvStore) {
        private val state: JSONObject
        private var changed = false
        init {
            var saved: JSONObject
            try { saved = JSONObject(store.preference(STATE, "{}")) }
            catch (unreadable: JSONException) { saved = JSONObject() }
            state = saved
        }
        /** The series' state, empty when there is none; read only. */
        fun show(titleKey: String): JSONObject {
            val show = state.optJSONObject(titleKey)
            return show ?: JSONObject()
        }
        @Throws(JSONException::class)
        fun edit(titleKey: String): JSONObject {
            changed = true
            var show = state.optJSONObject(titleKey)
            if (show == null) { show = JSONObject(); state.put(titleKey, show) }
            return show
        }
        /** Forgets series that left the library. */
        fun prune(library: List<TvStore.LibraryEntry>) {
            val kept: MutableSet<String> = HashSet()
            for (entry in library) kept.add(entry.title.key())
            val gone: MutableList<String> = ArrayList()
            val keys = state.keys()
            while (keys.hasNext()) { val key = keys.next(); if (!kept.contains(key)) gone.add(key) }
            for (key in gone) state.remove(key)
            changed = changed or !gone.isEmpty()
        }
        /**
         * Applies the series' notice history: a new episode starts a notice, which ends for good when it runs out.
         * @param today the local date when Home is showing it, or null
         */
        fun evaluate(entry: TvStore.LibraryEntry, seen: Map<String, TvStore.Progress>, now: Long, today: String?): Notice? {
            val key = entry.title.key()
            if (show(key).optBoolean("muted") || !followed(entry, seen, now)) return null
            val notice = find(entry.title, seen, if (entry.favourite) entry.updatedMs else -1L, now) ?: return null
            try {
                var show = show(key)
                if (notice.episode.id != show.optString("episode"))
                    edit(key).put("episode", notice.episode.id).put("first", now).put("days", 0).put("day", "").put("gone", false)
                show = show(key)
                if (show.optBoolean("gone")) return null
                if (now - show.optLong("first", now) >= LIFETIME_MS || show.optInt("days") >= SHOWN_DAYS) { edit(key).put("gone", true); return null }
                if (today != null && today != show.optString("day")) edit(key).put("day", today).put("days", show.optInt("days") + 1)
                return notice
            } catch (e: JSONException) { throw IllegalStateException(e) }
        }
        fun save(store: TvStore) { if (changed) store.setPreference(STATE, state.toString()); changed = false }
    }
}
