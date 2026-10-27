package tv.stream.app;

import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import tv.stream.app.addon.AddonException;
import tv.stream.app.addon.AddonRepository;
import tv.stream.app.addon.Cancellation;
import tv.stream.app.addon.Title;
import tv.stream.app.data.TvStore;

/**
 * New episodes of the series the viewer follows.
 *
 * <p>A series is followed while it is in the library and the viewer has finished one of its episodes (in the last two
 * years) or made it a favourite, unless they turned its alerts off. Its next episode is the one after the furthest
 * episode they finished, specials aside; for a favourite they never played, the first. That episode is new when it has
 * aired since they finished the one before it (or favourited the show) and they haven't started it. A viewer who is
 * behind already knows the following episodes exist, and one who started it finds it in Continue Watching.
 *
 * <p>A notice never stays for good: it goes once the episode is played, when the viewer hides it, after Home has shown
 * it on {@link #SHOWN_DAYS} different days, or {@link #LIFETIME_MS} after it first appeared. One that ends unplayed is
 * not shown again, so ignoring it quietens the show until the viewer watches it again; once caught up, the next
 * episode is new again. The library's saved episode lists are refreshed in the background by {@link #check}.
 */
final class NewEpisodes {
    private NewEpisodes() {}
    static final long DAY_MS = 86_400_000L;
    /** How often one followed series' episode list is fetched again. */
    static final long CHECK_MS = 12 * 3_600_000L;
    /** Series checked per run, the most recently watched first. */
    static final int CHECKS_PER_RUN = 24;
    static final int SHOWN_DAYS = 7;
    static final long LIFETIME_MS = 30 * DAY_MS;
    /** A series nobody has watched for this long is no longer followed, unless it is a favourite. */
    static final long FOLLOW_MS = 730 * DAY_MS;
    /** Per series (by title key): when it was checked, whether alerts are off, and the current notice's history. */
    private static final String STATE = "newEpisodes";
    private static final AtomicBoolean checking = new AtomicBoolean();

    static final class Notice {
        final Title title;
        /** The first new episode: the one to watch next. */
        final Title.Episode episode;
        /** The new episodes, from {@link #episode} on. */
        final Set<String> episodes;
        Notice(Title title, Title.Episode episode, Set<String> episodes) { this.title = title; this.episode = episode; this.episodes = episodes; }
        String label() {
            if (episode.number == 1 && episode.season > 1) return "New season";
            return episodes.size() > 1 ? episodes.size() + " new episodes" : "New episode";
        }
    }

    /** The series' episodes in viewing order, without specials. */
    static List<Title.Episode> order(Title title) {
        List<Title.Episode> out = new ArrayList<>();
        boolean numbered = true;
        for (Title.Episode e : title.episodes) if (e.season != 0) { out.add(e); numbered &= e.season > 0 && e.number >= 0; }
        if (numbered) out.sort(Comparator.comparingInt((Title.Episode e) -> e.season).thenComparingInt(e -> e.number));
        return out;
    }
    static boolean finished(@Nullable TvStore.Progress p) {
        return p != null && (p.watched || WatchCompletion.nearlyFinished(p.positionMs, p.durationMs));
    }
    private static int furthest(List<Title.Episode> order, Map<String, TvStore.Progress> progress) {
        for (int i = order.size() - 1; i >= 0; i--) if (finished(progress.get(order.get(i).id))) return i;
        return -1;
    }
    /** The episode after the furthest one finished (the first when none is) that has aired; null when there is none yet. */
    @Nullable static Title.Episode nextUp(Title title, Map<String, TvStore.Progress> progress, long now) {
        List<Title.Episode> order = order(title);
        int next = furthest(order, progress) + 1;
        if (next >= order.size()) return null;
        long aired = aired(order.get(next).released);
        return aired > now ? null : order.get(next);
    }

    /**
     * When an episode aired, from the provider's ISO date or time; -1 when it doesn't say. A date alone is midnight UTC,
     * the provider's broadcast day.
     */
    static long aired(@Nullable String released) {
        if (released == null || released.length() < 10) return -1;
        try { return Instant.parse(released).toEpochMilli(); }
        catch (DateTimeException notAnInstant) {
            try { return LocalDate.parse(released.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); }
            catch (DateTimeException invalid) { return -1; }
        }
    }

    /**
     * The notice a series deserves now, before what the viewer did with earlier ones.
     * @param favouritedMs when a favourite never finished was saved, its starting point; below zero when not a favourite
     */
    @Nullable static Notice find(Title title, Map<String, TvStore.Progress> progress, long favouritedMs, long now) {
        if (!title.type.equals("series")) return null;
        List<Title.Episode> order = order(title);
        int furthest = furthest(order, progress);
        if (furthest < 0 && favouritedMs < 0) return null;
        // Dates are often a broadcast day at midnight UTC, so one airing the day the viewer finished the last still
        // counts; but never one released together with it (a season out at once), which they have already seen listed.
        long finishedAt = furthest >= 0 ? progress.get(order.get(furthest).id).updatedMs : favouritedMs;
        long since = Math.max(finishedAt - DAY_MS, furthest >= 0 ? aired(order.get(furthest).released) : -1);
        Set<String> fresh = new LinkedHashSet<>();
        Title.Episode first = null;
        for (int i = furthest + 1; i < order.size(); i++) {
            Title.Episode e = order.get(i);
            long aired = aired(e.released);
            TvStore.Progress p = progress.get(e.id);
            // Below Continue Watching's threshold, an episode opened for a moment still counts as unstarted.
            boolean unstarted = p == null || p.positionMs < TvStore.RESUMABLE_MS;
            if (i == furthest + 1 && (aired <= since || aired > now || !unstarted)) return null;
            if (aired > since && aired <= now && unstarted) { fresh.add(e.id); if (first == null) first = e; }
        }
        return first == null ? null : new Notice(title, first, Collections.unmodifiableSet(fresh));
    }

    /** Current notices, by title key, in library order. Home passes {@code shown}: each day it shows one counts. */
    static Map<String, Notice> current(TvStore store, List<TvStore.LibraryEntry> library, List<TvStore.Progress> history, long now, boolean shown) {
        Map<String, Map<String, TvStore.Progress>> progress = byTitle(history);
        Map<String, Notice> out = new LinkedHashMap<>();
        String today = shown ? Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString() : null;
        synchronized (store) {
            Ledger ledger = new Ledger(store);
            ledger.prune(library);
            for (TvStore.LibraryEntry entry : library) {
                Notice notice = ledger.evaluate(entry, progress.getOrDefault(entry.title.key(), Collections.emptyMap()), now, today);
                if (notice != null) out.put(entry.title.key(), notice);
            }
            ledger.save(store);
        }
        return out;
    }
    static Map<String, Notice> current(TvStore store, long now) {
        return current(store, store.library(false), store.history(false), now, false);
    }
    /** One saved series' notice, as {@link #current} gives it; showing it elsewhere than Home doesn't count. */
    @Nullable static Notice notice(TvStore store, TvStore.LibraryEntry entry, Map<String, TvStore.Progress> seen, long now) {
        synchronized (store) {
            Ledger ledger = new Ledger(store);
            Notice notice = ledger.evaluate(entry, seen, now, null);
            ledger.save(store);
            return notice;
        }
    }

    /** Hides the series' current notice; a later episode brings a new one. */
    static void hide(TvStore store, String titleKey) {
        update(store, titleKey, show -> { if (show.has("episode")) show.put("gone", true); });
    }
    /** Turns a series' alerts off or back on. Its episode list is no longer checked while they are off. */
    static void mute(TvStore store, String titleKey, boolean muted) { update(store, titleKey, show -> show.put("muted", muted)); }
    static boolean muted(TvStore store, String titleKey) {
        synchronized (store) { return new Ledger(store).show(titleKey).optBoolean("muted"); }
    }

    /**
     * Fetches the episode lists of followed series not checked for {@link #CHECK_MS}, most recently watched first, and
     * saves them over the library's copies. Quiet: one run at a time, ranked below every other provider request (Play
     * closes it), and a series whose provider doesn't answer is asked again next time.
     * @return whether a saved series changed
     */
    static boolean check(TvStore store, AddonRepository addons, long now) {
        if (!checking.compareAndSet(false, true)) return false;
        try (Cancellation scope = addons.newRequest()) {
            scope.priority(3);
            Map<String, Map<String, TvStore.Progress>> progress = byTitle(store.history(false));
            Ledger ledger;
            synchronized (store) { ledger = new Ledger(store); }
            List<TvStore.LibraryEntry> due = new ArrayList<>();
            Map<String, Long> activity = new HashMap<>();
            for (TvStore.LibraryEntry entry : store.library(false)) {
                String key = entry.title.key();
                Map<String, TvStore.Progress> seen = progress.getOrDefault(key, Collections.emptyMap());
                JSONObject show = ledger.show(key);
                if (entry.title.provider.equals("local") || !followed(entry, seen, now) || show.optBoolean("muted")) continue;
                if (now - show.optLong("checked") < CHECK_MS) continue;
                due.add(entry); activity.put(key, lastActivity(entry, seen));
            }
            due.sort(Comparator.comparingLong((TvStore.LibraryEntry e) -> activity.get(e.title.key())).reversed());
            boolean changed = false;
            List<String> checked = new ArrayList<>();
            for (TvStore.LibraryEntry entry : due.subList(0, Math.min(CHECKS_PER_RUN, due.size()))) {
                Title saved = entry.title;
                AddonRepository.Result<Title> found;
                try { found = addons.metadata(saved.provider, saved.type, saved.id, scope); }
                catch (AddonException cancelled) { break; }
                // A saved copy offered while the provider is unavailable is no news: ask again next time.
                if (found.items.isEmpty() || found.stale) continue;
                Title fresh = found.items.get(0).withProvider(saved.provider);
                if (!fresh.cacheJson().equals(saved.cacheJson())) { store.refresh(fresh); changed = true; }
                checked.add(saved.key());
            }
            for (String key : checked) update(store, key, show -> show.put("checked", now));
            return changed;
        } finally { checking.set(false); }
    }

    private static boolean followed(TvStore.LibraryEntry entry, Map<String, TvStore.Progress> seen, long now) {
        if (!entry.title.type.equals("series")) return false;
        if (entry.favourite) return true;
        boolean finished = false;
        for (TvStore.Progress p : seen.values()) finished |= finished(p);
        return finished && now - lastActivity(entry, seen) < FOLLOW_MS;
    }
    private static long lastActivity(TvStore.LibraryEntry entry, Map<String, TvStore.Progress> seen) {
        long last = entry.updatedMs;
        for (TvStore.Progress p : seen.values()) last = Math.max(last, p.updatedMs);
        return last;
    }
    private static Map<String, Map<String, TvStore.Progress>> byTitle(List<TvStore.Progress> history) {
        Map<String, Map<String, TvStore.Progress>> out = new HashMap<>();
        for (TvStore.Progress p : history) out.computeIfAbsent(p.titleKey, key -> new HashMap<>()).put(p.video, p);
        return out;
    }

    private interface Change { void apply(JSONObject show) throws JSONException; }
    private static void update(TvStore store, String titleKey, Change change) {
        synchronized (store) {
            Ledger ledger = new Ledger(store);
            try { change.apply(ledger.edit(titleKey)); } catch (JSONException e) { throw new IllegalStateException(e); }
            ledger.save(store);
        }
    }

    /**
     * The saved state of every series, read and changed under the store's lock and saved once if anything changed.
     * Per series: {@code checked} (last check), {@code muted}, and the current notice's {@code episode}, {@code first}
     * appearance, {@code days} shown, last {@code day} shown and whether it is {@code gone}.
     */
    private static final class Ledger {
        private final JSONObject state;
        private boolean changed;
        Ledger(TvStore store) {
            JSONObject saved;
            try { saved = new JSONObject(store.preference(STATE, "{}")); }
            catch (JSONException unreadable) { saved = new JSONObject(); }
            state = saved;
        }
        /** The series' state, empty when there is none; read only. */
        JSONObject show(String titleKey) {
            JSONObject show = state.optJSONObject(titleKey);
            return show == null ? new JSONObject() : show;
        }
        JSONObject edit(String titleKey) throws JSONException {
            changed = true;
            JSONObject show = state.optJSONObject(titleKey);
            if (show == null) state.put(titleKey, show = new JSONObject());
            return show;
        }
        /** Forgets series that left the library. */
        void prune(List<TvStore.LibraryEntry> library) {
            Set<String> kept = new HashSet<>();
            for (TvStore.LibraryEntry entry : library) kept.add(entry.title.key());
            List<String> gone = new ArrayList<>();
            for (Iterator<String> keys = state.keys(); keys.hasNext(); ) { String key = keys.next(); if (!kept.contains(key)) gone.add(key); }
            for (String key : gone) state.remove(key);
            changed |= !gone.isEmpty();
        }
        /**
         * Applies the series' notice history: a new episode starts a notice, which ends for good when it runs out.
         * @param today the local date when Home is showing it, or null
         */
        @Nullable Notice evaluate(TvStore.LibraryEntry entry, Map<String, TvStore.Progress> seen, long now, @Nullable String today) {
            String key = entry.title.key();
            if (show(key).optBoolean("muted") || !followed(entry, seen, now)) return null;
            Notice notice = find(entry.title, seen, entry.favourite ? entry.updatedMs : -1, now);
            if (notice == null) return null;
            try {
                JSONObject show = show(key);
                if (!notice.episode.id.equals(show.optString("episode")))
                    edit(key).put("episode", notice.episode.id).put("first", now).put("days", 0).put("day", "").put("gone", false);
                show = show(key);
                if (show.optBoolean("gone")) return null;
                if (now - show.optLong("first", now) >= LIFETIME_MS || show.optInt("days") >= SHOWN_DAYS) { edit(key).put("gone", true); return null; }
                if (today != null && !today.equals(show.optString("day"))) edit(key).put("day", today).put("days", show.optInt("days") + 1);
                return notice;
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
        void save(TvStore store) { if (changed) store.setPreference(STATE, state.toString()); changed = false; }
    }
}
