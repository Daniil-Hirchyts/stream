package tv.daniil.stream;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import tv.daniil.playback.*;
import tv.daniil.stream.addon.*;
import tv.daniil.stream.data.TvStore;
import tv.daniil.stream.ui.Failures;
import tv.daniil.torrent.*;

/** Owns playback only while visible. A single serial loader owns native create/select/destroy operations. */
@OptIn(markerClass = UnstableApi.class)
public final class WatchActivity extends Activity implements PlaybackHost {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService loader;
    private TvApplication app;
    private TvApplication.Services services;
    private PlaybackSession session;
    private PlayerScreen screen;
    private volatile TorrentEngine engine;
    private volatile long torrent;
    private Future<?> preparation;
    private Cancellation scope;
    private volatile int generation;
    private boolean visible, releasing, automatic, switching, failureQueued;
    private Title title;
    private String video, explicitKey;
    private List<MediaSource> candidates = Collections.emptyList();
    private MediaSource selected;
    private int candidate, attempts;
    private long startMs, firstFrameMs, lastAdvanceMs, previousPosition, bufferingStarted, bufferingMs, seekStarted, seekRecoveryMs;
    private long resumePosition;
    private int droppedFrames;
    private JSONObject nativeStats = new JSONObject();
    private boolean polling, preferencesInitialized;
    private String audioChoice = "", subtitleChoice = "";
    private AlertDialog choiceDialog;
    private boolean autoplay = true;

    public static Intent intent(Context context, Title title, String video, String sourceKey) {
        return new Intent(context, WatchActivity.class).putExtra("provider", title.provider).putExtra("titleId", title.id)
                .putExtra("type", title.type).putExtra("name", title.name).putExtra("video", video).putExtra("source", sourceKey);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); app = (TvApplication) getApplication(); loader = app.playbackLoader;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        video = getIntent().getStringExtra("video"); explicitKey = getIntent().getStringExtra("source");
        if (state != null) { video = state.getString("video", video); resumePosition = state.getLong("position", 0); }
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
    }
    @Override protected void onStart() {
        super.onStart(); visible = true; releasing = false;
        setContentView(R.layout.activity_watch);
        int mine = ++generation;
        preparation = loader.submit(() -> {
            try {
                TvApplication.Services data = app.services();
                Title saved = null;
                String id = getIntent().getStringExtra("titleId"), provider = getIntent().getStringExtra("provider");
                for (TvStore.LibraryEntry e : data.store.library(false)) if (e.title.id.equals(id) && e.title.provider.equals(provider)) saved = e.title;
                if (saved == null) saved = Title.parse(provider, new JSONObject().put("id", id).put("type", getIntent().getStringExtra("type")).put("name", getIntent().getStringExtra("name")));
                Title chosen = saved;
                main.post(() -> {
                    if (!current(mine)) return;
                    services = data; title = chosen;
                    session = new PlaybackSession(this, data.playerPreferences, "Daniil TV", "DaniilTV/0.1", this, 24 << 20);
                    session.player().setTrackSelectionParameters(session.player().getTrackSelectionParameters().buildUpon().setMaxVideoSize(1920, 1080).build());
                    session.player().addAnalyticsListener(new AnalyticsListener() {
                        @Override public void onDroppedVideoFrames(EventTime time, int count, long elapsedMs) { droppedFrames += count; }
                        @Override public void onPositionDiscontinuity(EventTime time, Player.PositionInfo old, Player.PositionInfo next, int reason) {
                            if (reason == Player.DISCONTINUITY_REASON_SEEK) { seekStarted = lastAdvanceMs = SystemClock.elapsedRealtime(); previousPosition = next.positionMs; }
                        }
                    });
                    screen = new PlayerScreen(this, session, this); screen.setTitle(title.name);
                    resolve(true); main.post(tick);
                });
            } catch (Exception e) { main.post(() -> { if (current(mine)) { choiceDialog = new AlertDialog.Builder(this).setMessage(Failures.message(e)).setPositiveButton("Close", (d, w) -> finish()).show(); } }); }
        });
    }
    private boolean current(int mine) { return visible && !releasing && mine == generation && !isFinishing(); }
    private void cancelPreparation() {
        generation++; if (scope != null) scope.close(); if (preparation != null) preparation.cancel(true);
        if (choiceDialog != null) { choiceDialog.dismiss(); choiceDialog = null; }
    }
    private void closeNative() {
        TorrentEngine old = engine; engine = null; torrent = 0; nativeStats = new JSONObject();
        if (old != null) old.close();
    }
    private void resolve(boolean resetAttempts) {
        if (session == null || !visible) return;
        save(session.snapshot()); session.stop(); cancelPreparation();
        if (resetAttempts) { attempts = 0; candidate = 0; }
        switching = true; lastAdvanceMs = SystemClock.elapsedRealtime(); scope = services.addons.newRequest();
        int mine = generation; Cancellation request = scope;
        screen.setTitle(title.name); screen.setLoading(new PlayerScreen.Loading("sources", "Finding sources", "You can press Back to cancel.", false)); screen.render();
        preparation = loader.submit(() -> {
            try {
                closeNative();
                Title metadata = title;
                if (!title.provider.equals("local")) {
                    AddonRepository.Result<Title> found = services.addons.metadata(title.provider, title.type, title.id, request);
                    if (!found.items.isEmpty()) metadata = found.items.get(0);
                }
                autoplay = services.store.preference("autoplay", "true").equals("true");
                TvStore.Progress progress = services.store.progress(metadata.key(), video);
                long resume = resumePosition > 0 ? resumePosition : progress != null && !progress.watched ? progress.positionMs : 0;
                List<MediaSource> choices = Collections.emptyList();
                if (!metadata.provider.equals("local")) {
                    AddonRepository.Result<MediaSource> found = services.addons.streams(metadata.type, video, request);
                    List<MediaSource> available = new ArrayList<>(found.items);
                    for (Title.Episode e : metadata.episodes) if (e.id.equals(video)) available.addAll(e.streams);
                    choices = SourcePolicy.ranked(available);
                }
                request.check(); Title resolved = metadata; List<MediaSource> sources = choices;
                main.post(() -> {
                    if (!current(mine)) return;
                    title = resolved; resumePosition = resume; candidates = sources;
                    automatic = explicitKey == null || explicitKey.isEmpty();
                    if (title.provider.equals("local")) { open(null, -1); return; }
                    if (candidates.isEmpty()) { failure("No playable sources", "Enable a compatible source add-on or select another title.", "no-sources"); return; }
                    if (!automatic) {
                        for (int i = 0; i < candidates.size(); i++) if (SourcePolicy.key(candidates.get(i)).equals(explicitKey)) { candidate = i; open(candidates.get(i), -1); return; }
                        chooseSources(); return;
                    }
                    candidate = 0; open(candidates.get(0), -1);
                });
            } catch (Exception e) { main.post(() -> { if (current(mine)) failure("Could not open this title", Failures.message(e), "provider"); }); }
        });
    }
    private void open(MediaSource source, int chosenIndex) {
        save(session.snapshot()); session.stop(); cancelPreparation();
        int mine = generation; scope = services.addons.newRequest(); Cancellation request = scope;
        selected = source; if (chosenIndex < 0) attempts++; switching = true; failureQueued = false; preferencesInitialized = false; firstFrameMs = 0; droppedFrames = 0;
        startMs = lastAdvanceMs = SystemClock.elapsedRealtime(); previousPosition = -1;
        screen.setLoading(new PlayerScreen.Loading("preparing", "Opening " + (source == null ? "import" : source.name), "Preparing video. Press Back to cancel.", false)); screen.render();
        preparation = loader.submit(() -> {
            try {
                // A chosen file reuses the metadata session; a changed source closes the previous one first.
                if (chosenIndex < 0) closeNative();
                String audio = services.store.preference("audioLanguage", "");
                String subtitles = services.store.preference("subtitleLanguage", "");
                if (subtitles.isEmpty()) subtitles = services.playerPreferences.getBoolean("subtitles_on", false) ? services.playerPreferences.getString("subtitle_language", "") : "off";
                List<SubtitleDownloader.Source> captions = new ArrayList<>();
                if (source != null && !subtitles.equals("off")) {
                    List<SubtitleSource> advertised = new ArrayList<>(source.subtitles);
                    Map<String, String> hints = new LinkedHashMap<>();
                    if (source.filename != null) hints.put("filename", source.filename);
                    if (source.videoHash != null) hints.put("videoHash", source.videoHash);
                    if (source.videoSize > 0) hints.put("videoSize", Long.toString(source.videoSize));
                    try { advertised.addAll(services.addons.subtitles(title.type, video, hints, request).items); } catch (AddonException ignored) { /* optional subtitles never stop video */ }
                    String wanted = subtitles;
                    advertised.sort(java.util.Comparator.comparingInt(s -> sameLanguage(s.language, wanted) ? 0 : 1));
                    for (SubtitleSource s : advertised) if (captions.size() < 8) captions.add(new SubtitleDownloader.Source(s.url, mime(s.url), s.language, s.language));
                }
                Uri uri; androidx.media3.datasource.DataSource.Factory dataSource = null;
                if (source != null && source.kind == MediaSource.Kind.HTTP) uri = Uri.parse(source.url);
                else {
                    if (engine == null) {
                        RuntimeOptions options = new RuntimeOptions(this, services.cacheBytes());
                        File payload = new File(getCacheDir(), "payload");
                        TorrentEngine.Config config = options.localOnly ? TorrentEngine.Config.localOnly(payload.getPath()) : new TorrentEngine.Config(payload.getPath());
                        config.cacheBytes = options.cacheBytes; config.reserveBytes = options.localOnly ? 0 : 512L << 20;
                        File roots = new File(getNoBackupFilesDir(), "native-trust.pem"); TrustRoots.writePem(roots); config.trustRootsFile = roots.getPath();
                        engine = TorrentEngine.create(config);
                        TorrentEngine.Source torrentSource;
                        if (source == null) { try (AddonClient client = new AddonClient()) { torrentSource = services.imports.open(title.id, client, request); } }
                        else torrentSource = source.torrentSource();
                        if (options.localOnly) torrentSource = torrentSource.withTrackers().withPeers(options.peer);
                        torrent = engine.add(torrentSource);
                    }
                    List<TorrentFile> files = engine.awaitMetadata(torrent, 20_000);
                    int explicit = chosenIndex >= 0 ? chosenIndex : source == null ? services.store.importDescriptor(title.id).optInt("fileIdx", -1) : source.fileIndex;
                    int season = -1, episode = -1; for (Title.Episode e : title.episodes) if (e.id.equals(video)) { season = e.season; episode = e.number; }
                    TorrentFile file = SourcePolicy.select(files, explicit, season, episode);
                    if (file == null) {
                        List<TorrentFile> videos = new ArrayList<>(); for (TorrentFile f : files) if (SourcePolicy.video(f)) videos.add(f);
                        if (videos.isEmpty()) throw new TorrentException(TorrentException.INVALID_FILE, "No video files");
                        request.check(); main.post(() -> { if (current(mine)) chooseFile(source, videos); }); return;
                    }
                    request.check(); engine.selectFile(torrent, file.index);
                    if (source == null) services.imports.selectedFile(title.id, file.index);
                    uri = TorrentDataSource.uri(torrent, file); dataSource = new TorrentDataSource.Factory(engine);
                }
                PlaybackRequest playback = new PlaybackRequest.Builder(uri, title.name).id(video).detail(episodeLabel())
                        .position(resumePosition).audioLanguage(audio.isEmpty() || audio.equals("off") ? null : audio)
                        .headers(source == null ? Collections.emptyMap() : source.requestHeaders).subtitleHeaders(Collections.emptyMap())
                        .subtitles(captions).subtitleRequest(new SubtitleTracks.Request(subtitles.equals("off") ? "off" : null, subtitles.equals("off") ? null : subtitles, null))
                        .dataSource(dataSource).build();
                request.check();
                main.post(() -> {
                    if (!current(mine)) return;
                    switching = false; session.load(playback);
                    screen.setLoading(new PlayerScreen.Loading("video", "Preparing video", null, true)); screen.render();
                });
            } catch (Exception e) {
                if (!current(mine)) return;
                main.post(() -> { if (current(mine)) attemptFailed(Failures.message(e), "opening"); });
            }
        });
    }
    private static boolean sameLanguage(String one, String two) {
        if (one == null || two == null || two.isEmpty()) return false;
        try { return new java.util.Locale(one).getISO3Language().equals(new java.util.Locale(two).getISO3Language()); } catch (Exception e) { return one.equalsIgnoreCase(two); }
    }
    private static String mime(String url) { String lower = Uri.parse(url).getPath(); lower = lower == null ? "" : lower.toLowerCase(java.util.Locale.ROOT); return lower.endsWith(".srt") ? "application/x-subrip" : lower.endsWith(".ass") || lower.endsWith(".ssa") ? "text/x-ssa" : "text/vtt"; }
    private String episodeLabel() { for (Title.Episode e : title.episodes) if (e.id.equals(video)) return "S" + e.season + " E" + e.number + " · " + e.name; return ""; }
    private void chooseFile(MediaSource source, List<TorrentFile> files) {
        String[] names = new String[files.size()]; for (int i = 0; i < names.length; i++) names[i] = files.get(i).name() + " · " + files.get(i).size / (1 << 20) + " MiB";
        choiceDialog = new AlertDialog.Builder(this).setTitle("Choose a video file").setItems(names, (d, which) -> open(source, files.get(which).index))
                .setNegativeButton("Cancel", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
    }
    private void chooseSources() {
        if (session == null) return;
        save(session.snapshot()); session.stop(); cancelPreparation(); loader.execute(this::closeNative);
        if (candidates.isEmpty()) { failure("No sources available", "Return to the title and refresh its sources.", "no-sources"); return; }
        String[] names = new String[candidates.size()]; for (int i = 0; i < names.length; i++) names[i] = candidates.get(i).name + " · " + SourcePolicy.assess(candidates.get(i)).label;
        choiceDialog = new AlertDialog.Builder(this).setTitle("Choose source").setItems(names, (d, which) -> {
            automatic = false; explicitKey = SourcePolicy.key(candidates.get(which)); candidate = which; attempts = 0; open(candidates.get(which), -1);
        }).setNegativeButton("Cancel", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
    }
    private void attemptFailed(String message, String code) {
        if (!visible || releasing) return;
        boolean rendered = session != null && session.renderedFirstFrame();
        if (automatic && !rendered && attempts < 3 && candidate + 1 < candidates.size()) {
            candidate++; int mine = generation, next = candidate;
            main.post(() -> { if (current(mine)) open(candidates.get(next), -1); }); return;
        }
        failure("Playback could not continue", message, code);
    }
    private void failure(String heading, String message, String code) {
        switching = false;
        if (screen == null || session == null) return;
        session.fail(new PlaybackErrors.Description(code, heading, message, "", 0, true));
        cancelPreparation(); loader.execute(this::closeNative);
        screen.setLoading(null); screen.render();
    }
    private void queueFailure(String message, String code) {
        if (failureQueued || releasing || switching) return;
        failureQueued = true; int mine = generation;
        main.post(() -> { if (current(mine)) attemptFailed(message, code); });
    }
    @Override public void onUnsupportedFormat(String format) { queueFailure("This device cannot decode " + format + ". Choose another source.", "unsupported"); }
    @Override public void onPlaybackChanged(PlaybackSnapshot snapshot) {
        if (releasing || session == null || switching) return;
        if (snapshot.renderedFirstFrame && firstFrameMs == 0) firstFrameMs = SystemClock.elapsedRealtime() - startMs;
        if (snapshot.error != null) {
            // Host-originated errors have already gone through bounded failover.
            if (!snapshot.error.code.equals("unsupported") && !snapshot.error.code.equals("opening") && !snapshot.error.code.equals("buffering") && !snapshot.error.code.equals("provider") && !snapshot.error.code.equals("no-sources"))
                queueFailure(playbackFailure(snapshot.error), "opening");
            return;
        }
        if (!SourcePolicy.actualSupported(this, snapshot.videoFormat) || !SourcePolicy.actualSupported(this, snapshot.audioFormat)) {
            onUnsupportedFormat("the supplied media format"); return;
        }
        if (snapshot.started) {
            String audio = snapshot.audio == null ? "" : snapshot.audio.id;
            String text = snapshot.subtitles == null ? "off" : snapshot.subtitles.id;
            if (!preferencesInitialized) { audioChoice = audio; subtitleChoice = text; preferencesInitialized = true; }
            else if (!audioChoice.equals(audio) || !subtitleChoice.equals(text)) save(snapshot);
        }
        long now = SystemClock.elapsedRealtime();
        if (snapshot.buffering && bufferingStarted == 0) bufferingStarted = now;
        if (!snapshot.buffering && bufferingStarted != 0) { bufferingMs += now - bufferingStarted; bufferingStarted = 0; }
    }
    private String playbackFailure(PlaybackErrors.Description error) {
        Throwable cause = session.player().getPlayerError();
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause())
            if (cause instanceof TorrentException) return Failures.message(cause);
        return error.message;
    }
    @Override public void onProgress(PlaybackSnapshot snapshot) { if (!releasing) save(snapshot); }
    private void save(PlaybackSnapshot snapshot) {
        if (services == null || title == null || snapshot == null || !snapshot.loaded) return;
        Title outgoing = title; String outgoingVideo = video; long position = snapshot.positionMs, duration = snapshot.durationMs; boolean ended = snapshot.ended;
        String audio = snapshot.audio == null ? "" : snapshot.audio.id;
        String text = snapshot.subtitles == null ? "off" : snapshot.subtitles.id;
        String audioLanguage = snapshot.audio == null ? null : snapshot.audio.language;
        String subtitleLanguage = snapshot.subtitles == null ? "off" : snapshot.subtitles.language;
        boolean changedAudio = preferencesInitialized && !audioChoice.equals(audio), changedText = preferencesInitialized && !subtitleChoice.equals(text);
        if (snapshot.started) { audioChoice = audio; subtitleChoice = text; preferencesInitialized = true; }
        app.persistence.execute(() -> {
            services.store.progress(outgoing, outgoingVideo, position, duration, ended);
            if (changedAudio && audioLanguage != null) services.store.setPreference("audioLanguage", audioLanguage);
            if (changedText && subtitleLanguage != null) services.store.setPreference("subtitleLanguage", subtitleLanguage);
        });
    }
    @Override public void onEnded() {
        if (!visible || releasing || screen == null || !autoplay) return;
        Title.Episode next = neighbour(1); if (next != null) screen.offerUpNext(next.name, "Season " + next.season, () -> episode(next));
    }
    private Title.Episode neighbour(int offset) { if (title == null) return null; for (int i = 0; i < title.episodes.size(); i++) if (title.episodes.get(i).id.equals(video)) { int next = i + offset; return next < 0 || next >= title.episodes.size() ? null : title.episodes.get(next); } return null; }
    private void episode(Title.Episode next) {
        save(session.snapshot()); session.stop(); video = next.id; resumePosition = 0; explicitKey = ""; resolve(true);
    }
    @Override public boolean onNextEpisode() { Title.Episode next = neighbour(1); if (next == null) return false; episode(next); return true; }
    @Override public boolean onPreviousEpisode() { Title.Episode previous = neighbour(-1); if (previous == null) return false; episode(previous); return true; }
    @Override public boolean canRetry() { return true; }
    @Override public void onRetry() { if (session != null && session.snapshot().positionMs > 0) resumePosition = session.snapshot().positionMs; resolve(true); }
    @Override public boolean canChooseSource() { return title != null && !title.provider.equals("local"); }
    @Override public void onChooseSource() { chooseSources(); }
    @Override public void onExit() { finish(); }
    @Override public List<MenuPanel.Item> menuItems(boolean leading) {
        List<MenuPanel.Item> result = new ArrayList<>();
        if (leading) {
            if (canChooseSource()) result.add(MenuPanel.Item.action("Choose source", null, this::chooseSources));
            if (neighbour(-1) != null) result.add(MenuPanel.Item.action("Previous episode", null, () -> onPreviousEpisode()));
            if (neighbour(1) != null) result.add(MenuPanel.Item.action("Next episode", null, () -> onNextEpisode()));
        } else result.add(MenuPanel.Item.action("Developer", "Playback diagnostics", this::diagnostics));
        return result;
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible || releasing || session == null) return;
            PlaybackSnapshot state = session.snapshot(); long now = SystemClock.elapsedRealtime();
            if (state.positionMs > previousPosition + 50 && state.playing) { lastAdvanceMs = now; previousPosition = state.positionMs; if (seekStarted > 0) { seekRecoveryMs = now - seekStarted; seekStarted = 0; } }
            if (!state.buffering && !switching && !state.playWhenReady) lastAdvanceMs = now;
            if ((choiceDialog == null || !choiceDialog.isShowing()) && now - lastAdvanceMs > (switching ? 60_000 : 30_000)
                    && (state.buffering || switching || !state.started) && state.error == null) {
                String message = engine == null ? "The source stopped responding. Retry or choose another source."
                        : nativeStats.optLong("hashFailures") > 0 ? "Downloaded pieces failed integrity checks. Retry with a different peer or choose another source."
                        : nativeStats.optInt("peers") == 0 ? "No peers are supplying this video. Retry later or choose another source."
                        : "No verified video data arrived in time. Check peers, retry or choose another source.";
                failure("Waiting for video", message, "buffering");
                cancelPreparation(); loader.execute(WatchActivity.this::closeNative);
            }
            if (!polling && engine != null && !switching) {
                polling = true; int mine = generation;
                loader.execute(() -> {
                    JSONObject stats = null;
                    try { if (engine != null && torrent != 0) stats = engine.stats(torrent); } catch (Exception ignored) { /* source is closing */ }
                    JSONObject value = stats; main.post(() -> { polling = false; if (current(mine) && value != null) nativeStats = value; });
                });
            }
            main.postDelayed(this, 1000);
        }
    };
    JSONObject diagnosticSnapshot() {
        JSONObject result = new JSONObject();
        try {
            result.put("firstFrameMs", firstFrameMs).put("seekRecoveryMs", seekRecoveryMs).put("droppedFrames", droppedFrames)
                    .put("bufferingMs", bufferingMs).put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
                    .put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            if (session != null) { PlaybackSnapshot s = session.snapshot(); result.put("positionMs", s.positionMs).put("durationMs", s.durationMs).put("renderedFirstFrame", s.renderedFirstFrame).put("playing", s.playing); }
            for (String key : new String[]{"peers", "seeds", "totalDownload", "downloadRate", "hashFailures", "evictionsRequested", "cache", "disk"}) if (nativeStats.has(key)) result.put(key, nativeStats.get(key));
        } catch (Exception ignored) { /* diagnostics remain best effort */ }
        return result;
    }
    PlaybackSnapshot playbackSnapshot() { return session == null ? null : session.snapshot(); }
    private void diagnostics() { choiceDialog = new AlertDialog.Builder(this).setTitle("Playback diagnostics").setMessage(diagnosticSnapshot().toString()).setPositiveButton("Close", null).show(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return screen != null && screen.dispatchKeyEvent(event) || super.dispatchKeyEvent(event); }
    @Override public void onBackPressed() { if (screen != null && screen.onRemoteKey(KeyEvent.KEYCODE_BACK)) return; finish(); }
    @Override protected void onStop() {
        visible = false; releasing = true;
        if (session != null) { PlaybackSnapshot state = session.snapshot(); resumePosition = state.positionMs; save(state); }
        cancelPreparation(); main.removeCallbacksAndMessages(null);
        if (screen != null) screen.release();
        if (session != null) session.release();
        screen = null; session = null;
        loader.execute(this::closeNative);
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("video", video); out.putLong("position", session == null ? resumePosition : session.snapshot().positionMs); }
}
