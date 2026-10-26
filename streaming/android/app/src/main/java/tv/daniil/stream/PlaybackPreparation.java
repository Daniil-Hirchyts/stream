package tv.daniil.stream;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import tv.daniil.stream.addon.*;

/** Process-local metadata/source handoff. Never opens a torrent, downloads payload, or saves an address. */
final class PlaybackPreparation implements AutoCloseable {
    private final AddonRepository addons;
    private final java.util.concurrent.ThreadPoolExecutor workers = (java.util.concurrent.ThreadPoolExecutor) Executors.newFixedThreadPool(2);
    private Entry current;
    // Claims belong to Play until the activity takes them, independently of the next Details page.
    private final java.util.Map<String, Entry> pending = new java.util.LinkedHashMap<>();
    PlaybackPreparation(AddonRepository addons) { this.addons = addons; }
    synchronized Entry prepare(Title title, String video) {
        long now = SystemClock.elapsedRealtime();
        pending.values().removeIf(entry -> {
            if (entry.scope.isCancelled() || now - entry.created >= 60_000) { entry.close(); return true; }
            return false;
        });
        if (current != null && current.matches(title, video, addons.configuration(), now)) return current;
        Title cached = current != null && current.metadataReady && current.configuration == addons.configuration()
                && current.titleKey.equals(title.key()) && now - current.created < 60_000 ? current.title : null;
        discard();
        Entry entry = current = new Entry(title, video, addons.configuration(), now, addons.newRequest(), addons.newRequest());
        if (cached != null) { entry.title = cached; entry.metadataReady = true; entry.metadataScope.close(); }
        else entry.metadataTask = workers.submit(() -> {
            try {
                addons.metadata(title.provider, title.type, title.id, entry.metadataScope, result -> {
                    if (!result.items.isEmpty()) synchronized (entry) {
                        entry.title = result.items.get(0).withProvider(title.provider); entry.metadataReady = true; entry.version++; entry.notifyAll();
                    }
                });
            } catch (AddonException ignored) { /* cached details remain useful */ }
            finally { synchronized (entry) { entry.metadataReady = true; entry.version++; entry.notifyAll(); } entry.metadataScope.close(); }
        });
        if (title.type.equals("series") && video.equals(title.id)) { entry.complete = true; return entry; }
        entry.streamTask = workers.submit(() -> {
            try {
                addons.streams(title.type, video, entry.scope, result -> {
                    synchronized (entry) { entry.result = result; entry.version++; entry.notifyAll(); }
                });
            } catch (AddonException ignored) { /* cancellation or an unavailable provider */ }
            finally { synchronized (entry) { entry.complete = true; entry.version++; entry.notifyAll(); } }
        });
        return entry;
    }
    synchronized Entry claim(Title title, String video) {
        Entry entry = prepare(title, video);
        current = null; entry.claimed = true;
        pending.put(entry.token, entry);
        while (pending.size() > 4) pending.remove(pending.keySet().iterator().next()).close();
        promote(entry);
        return entry;
    }
    synchronized Entry take(String token, Title title, String video) {
        Entry entry = pending.remove(token);
        if (entry == null || !entry.matches(title, video, addons.configuration(), SystemClock.elapsedRealtime())) {
            if (entry != null) entry.close();
            entry = prepare(title, video); current = null;
        }
        entry.claimed = true; promote(entry); return entry;
    }
    private void promote(Entry entry) {
        entry.metadataScope.priority(0); addons.prioritize(entry.scope);
    }
    synchronized void invalidate(Title title, String video) {
        if (current != null && current.titleKey.equals(title.key()) && (video == null || current.video.equals(video))) discard();
    }
    synchronized void leave() { discard(); }
    private void discard() { if (current != null) { current.close(); current = null; } workers.purge(); }
    @Override public synchronized void close() {
        discard(); for (Entry entry : pending.values()) entry.close(); pending.clear(); workers.shutdownNow();
    }

    static final class Entry implements AutoCloseable {
        final String token = UUID.randomUUID().toString(), video, titleKey;
        final long configuration, created;
        final Cancellation scope, metadataScope;
        volatile Title title;
        volatile boolean complete, metadataReady, claimed;
        private AddonRepository.Result<MediaSource> result;
        private int version;
        private java.util.concurrent.Future<?> metadataTask, streamTask;
        Entry(Title title, String video, long configuration, long created, Cancellation scope, Cancellation metadataScope) {
            this.title = title; titleKey = title.key(); this.video = video; this.configuration = configuration; this.created = created;
            this.scope = scope; this.metadataScope = metadataScope; scope.priority(2); metadataScope.priority(2);
        }
        boolean matches(Title title, String video, long configuration, long now) {
            return !scope.isCancelled() && this.configuration == configuration && titleKey.equals(title.key()) && this.video.equals(video) && now - created < 60_000;
        }
        synchronized List<MediaSource> sources() {
            List<MediaSource> sources = new ArrayList<>(result == null ? Collections.emptyList() : result.items);
            for (Title.Episode episode : title.episodes) if (episode.id.equals(video)) sources.addAll(episode.streams);
            return sources;
        }
        synchronized int version() { return version; }
        synchronized AddonRepository.Result<MediaSource> result() {
            AddonRepository.Result<MediaSource> base = result == null ? new AddonRepository.Result<>(Collections.emptyList(), Collections.emptyMap(), false) : result;
            return base.withItems(sources());
        }
        Title awaitMetadata(Cancellation caller) throws Exception {
            synchronized (this) { while (!metadataReady) { caller.check(); scope.check(); wait(100); } return title; }
        }
        List<MediaSource> awaitCandidate(Cancellation caller, String explicit) throws Exception {
            long first = 0; int rankedVersion = -1; List<MediaSource> candidates = Collections.emptyList();
            synchronized (this) {
                for (;;) {
                    caller.check(); scope.check();
                    if (rankedVersion != version) { candidates = SourcePolicy.ranked(sources()); rankedVersion = version; }
                    boolean ready = explicit == null || explicit.isEmpty() ? !candidates.isEmpty()
                            : candidates.stream().anyMatch(source -> SourcePolicy.key(source).equals(explicit));
                    long now = SystemClock.elapsedRealtime();
                    if (ready && first == 0) first = now;
                    if (complete && (metadataReady || ready) || ready && (now - first >= 750 || explicit != null && !explicit.isEmpty())) return candidates;
                    wait(50);
                }
            }
        }
        List<MediaSource> awaitAlternative(Cancellation caller, java.util.Set<String> attempted, java.util.Set<String> providers, long deadline) throws Exception {
            int rankedVersion = -1; List<MediaSource> candidates = Collections.emptyList();
            synchronized (this) {
                for (;;) {
                    caller.check(); scope.check();
                    if (rankedVersion != version) { candidates = SourcePolicy.ranked(sources()); rankedVersion = version; }
                    if (SourcePolicy.nextCandidate(candidates, attempted, providers) >= 0 || complete && metadataReady || SystemClock.elapsedRealtime() >= deadline) return candidates;
                    wait(50);
                }
            }
        }
        void awaitComplete(Cancellation caller, Consumer<AddonRepository.Result<MediaSource>> progress) throws Exception {
            int seen = -1;
            for (;;) {
                AddonRepository.Result<MediaSource> snapshot; boolean done;
                synchronized (this) {
                    caller.check(); scope.check();
                    if (seen == version && !(complete && metadataReady)) { wait(100); continue; }
                    seen = version; snapshot = result(); done = complete && metadataReady;
                }
                if (snapshot != null) progress.accept(snapshot);
                if (done) return;
            }
        }
        @Override public void close() {
            scope.close(); metadataScope.close();
            if (metadataTask != null) metadataTask.cancel(true);
            if (streamTask != null) streamTask.cancel(true);
            synchronized (this) { notifyAll(); }
        }
    }
}
