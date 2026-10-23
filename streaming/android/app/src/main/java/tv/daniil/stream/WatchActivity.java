package tv.daniil.stream;

import android.app.Activity;
import android.app.Dialog;
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
import tv.daniil.stream.ui.Sounds;
import tv.daniil.stream.ui.Tv;
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
    /** How each candidate went before, read with them; this activity's own outcomes are added as they happen. */
    private Map<String, TvStore.SourceMark> marks = new java.util.HashMap<>();
    private MediaSource selected;
    /** What the sounds last heard: the play state (only the viewer's change of it sounds), and where the position was and when. */
    private Boolean heardPlay;
    private long heardAt, heardPosition;
    private int candidate, attempts;
    private long startMs, firstFrameMs, lastAdvanceMs, previousPosition, bufferingStarted, bufferingMs, seekStarted, seekRecoveryMs;
    private long resumePosition;
    private int droppedFrames;
    private JSONObject nativeStats = new JSONObject();
    private boolean polling, preferencesInitialized;
    private final StartupWatchdog startupWatchdog = new StartupWatchdog();
    private String audioChoice = "", subtitleChoice = "";
    private Dialog choiceDialog;
    private boolean autoplay = true;
    private boolean developerMode;
    /** Public torrents: trackers answer within seconds, a cold DHT lookup can take most of a minute. */
    private static final long METADATA_MS = 60_000;
    private final java.util.Set<String> attemptedSources = new java.util.HashSet<>();
    private final java.util.Set<String> attemptedProviders = new java.util.HashSet<>();
    private final JSONArray sourceFailures = new JSONArray();
    private PlaybackErrors.Description handledError;
    private boolean keepPausedOnRecovery;
    private Format observedVideo, observedAudio;


    private Dialog show(Tv.Alert alert) { alert.show(); return alert.dialog; }

    public static Intent intent(Context context, Title title, String video, String sourceKey) {
        return new Intent(context, WatchActivity.class).putExtra("provider", title.provider).putExtra("titleId", title.id)
                .putExtra("type", title.type).putExtra("name", title.name).putExtra("video", video).putExtra("source", sourceKey);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); app = (TvApplication) getApplication(); loader = app.playbackLoader; Sounds.get(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        video = getIntent().getStringExtra("video"); explicitKey = getIntent().getStringExtra("source");
        if (state != null) { video = state.getString("video", video); resumePosition = state.getLong("position", 0); }
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
    }
    @Override protected void onStart() {
        super.onStart(); visible = true; releasing = false; Sounds.get(this).refresh(this);
        setContentView(R.layout.activity_watch);
        int mine = ++generation;
        preparation = loader.submit(() -> {
            try {
                TvApplication.Services data = app.services();
                boolean diagnosticsEnabled = SetupScreens.developerMode(data);
                Title saved = null;
                String id = getIntent().getStringExtra("titleId"), provider = getIntent().getStringExtra("provider");
                for (TvStore.LibraryEntry e : data.store.library(false)) if (e.title.id.equals(id) && e.title.provider.equals(provider)) saved = e.title;
                if (saved == null) saved = Title.parse(provider, new JSONObject().put("id", id).put("type", getIntent().getStringExtra("type")).put("name", getIntent().getStringExtra("name")));
                Title chosen = saved;
                main.post(() -> {
                    if (!current(mine)) return;
                    services = data; title = chosen; developerMode = diagnosticsEnabled;
                    session = new PlaybackSession(this, data.playerPreferences, getString(R.string.app_name), "DaniilTV/0.1", this, 24 << 20);
                    session.player().setTrackSelectionParameters(session.player().getTrackSelectionParameters().buildUpon().setMaxVideoSize(1920, 1080).build());
                    session.player().addAnalyticsListener(new AnalyticsListener() {
                        @Override public void onDroppedVideoFrames(EventTime time, int count, long elapsedMs) { droppedFrames += count; }
                        @Override public void onPositionDiscontinuity(EventTime time, Player.PositionInfo old, Player.PositionInfo next, int reason) {
                            if (reason == Player.DISCONTINUITY_REASON_SEEK) { seekStarted = lastAdvanceMs = SystemClock.elapsedRealtime(); previousPosition = next.positionMs; }
                        }
                    });
                    screen = new PlayerScreen(this, session, this); screen.setTitle(title.name);
                    bindRemoteFocus();
                    resolve(true); main.post(tick);
                });
            } catch (Exception e) { main.post(() -> { if (current(mine)) {
                Tv.Alert alert = Tv.alert(this, "Could not open this title", Failures.message(e));
                alert.cancelButton("close", "Close", this::finish); alert.dialog.setOnCancelListener(d -> finish()); choiceDialog = show(alert);
            } }); }
        });
    }
    private boolean current(int mine) { return visible && !releasing && mine == generation && !isFinishing(); }
    /** Keep source names and transport steps behind the viewer's diagnostics switch. */
    private void showLoading(String id, String step, String detail, boolean untilReady) {
        screen.setLoading(new PlayerScreen.Loading(id, developerMode ? step : "Loading", developerMode ? detail : null, untilReady));
    }
    /** Keep the first remote key usable after a pointer action; focus is taken before View handles the click. */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Returning false preserves each view's normal click/drag handling.
    private void bindRemoteFocus() {
        View surface = findViewById(R.id.player_root);
        surface.requestFocus();
        for (int id : new int[]{R.id.player_root, tv.daniil.playback.R.id.player_view, R.id.controls, R.id.center_icon,
                R.id.control_options, R.id.control_stop, R.id.progress}) {
            findViewById(id).setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    // A pointer click must activate immediately, rather than only taking keyboard focus.
                    if (view.getId() == R.id.control_options || view.getId() == R.id.control_stop || view.getId() == R.id.center_icon)
                        view.setFocusableInTouchMode(false);
                    if (view.getId() == R.id.center_icon) keepPausedOnRecovery = false;
                    surface.requestFocus();
                }
                return false;
            });
        }
        for (int id : ERROR_BUTTONS) {
            View button = findViewById(id); button.setFocusableInTouchMode(true);
            button.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) view.requestFocus();
                return false;
            });
        }
    }
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
        if (resetAttempts) { keepPausedOnRecovery = false; attempts = 0; candidate = 0; attemptedSources.clear(); attemptedProviders.clear();
            while (sourceFailures.length() > 0) sourceFailures.remove(0); }
        switching = true; lastAdvanceMs = SystemClock.elapsedRealtime(); scope = services.addons.newRequest();
        int mine = generation; Cancellation request = scope;
        screen.setTitle(title.name); showLoading("sources", "Finding sources", null, false);
        preparation = loader.submit(() -> {
            try {
                closeNative();
                Title metadata = title;
                if (!title.provider.equals("local")) {
                    AddonRepository.Result<Title> found = services.addons.metadata(title.provider, title.type, title.id, request);
                    if (!found.items.isEmpty()) metadata = found.items.get(0);
                }
                autoplay = services.store.preference("autoplay", "true").equals("true");
                Sounds.get(this).setEnabled(SetupScreens.soundsOn(services));
                TvStore.Progress progress = services.store.progress(metadata.key(), video);
                long resume = resumePosition > 0 ? resumePosition : progress != null && !progress.watched ? progress.positionMs : 0;
                List<MediaSource> choices = Collections.emptyList();
                Availability empty = null;
                if (!metadata.provider.equals("local")) {
                    List<tv.daniil.stream.data.InstalledAddon> providers = services.addons.providers("stream", metadata.type, video);
                    AddonRepository.Result<MediaSource> found = providers.isEmpty() ? null : services.addons.streams(metadata.type, video, request);
                    List<MediaSource> available = new ArrayList<>(found == null ? Collections.emptyList() : found.items);
                    for (Title.Episode e : metadata.episodes) if (e.id.equals(video)) available.addAll(e.streams);
                    choices = SourcePolicy.ranked(available);
                    if (choices.isEmpty()) empty = Availability.of(providers, available, found == null ? Collections.emptyMap() : found.failures, services.store.addons());
                }
                Map<String, TvStore.SourceMark> history = services.store.sourceMarks(metadata.key(), video);
                request.check(); Title resolved = metadata; List<MediaSource> sources = choices; Availability why = empty;
                main.post(() -> {
                    if (!current(mine)) return;
                    title = resolved; resumePosition = resume; candidates = sources; marks = history;
                    automatic = explicitKey == null || explicitKey.isEmpty();
                    if (title.provider.equals("local")) { open(null, -1); return; }
                    if (candidates.isEmpty()) { failure(why.heading, why.detail, "no-sources"); return; }
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
        findViewById(R.id.player_root).requestFocus();
        int mine = generation; scope = services.addons.newRequest(); Cancellation request = scope;
        selected = source;
        if (chosenIndex < 0) { attempts++; if (source != null) { attemptedSources.add(SourcePolicy.retryKey(source)); attemptedProviders.add(source.provider); } }
        handledError = null; observedVideo = observedAudio = null; heardPlay = null; heardAt = 0;
        switching = true; failureQueued = false; preferencesInitialized = false; firstFrameMs = 0; droppedFrames = 0;
        startMs = lastAdvanceMs = SystemClock.elapsedRealtime(); previousPosition = -1;
        showLoading("preparing", "Opening source", sourceLabel(source), false);
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
                    RuntimeOptions options = new RuntimeOptions(this, services.cacheBytes());
                    if (engine == null) {
                        File payload = new File(getCacheDir(), "payload");
                        TorrentEngine.Config config = options.localOnly ? TorrentEngine.Config.localOnly(payload.getPath()) : new TorrentEngine.Config(payload.getPath());
                        config.cacheBytes = options.cacheBytes; config.reserveBytes = options.localOnly ? 0 : 512L << 20;
                        config.publicNetworkOnly = options.publicNetworkOnly;
                        File roots = new File(getNoBackupFilesDir(), "native-trust.pem"); TrustRoots.writePem(roots); config.trustRootsFile = roots.getPath();
                        engine = TorrentEngine.create(config);
                        TorrentEngine.Source torrentSource;
                        if (source == null) { try (AddonClient client = new AddonClient()) { torrentSource = services.imports.open(title.id, client, request); } }
                        else torrentSource = source.torrentSource();
                        if (options.localOnly) torrentSource = torrentSource.withTrackers().withPeers(options.peer);
                        else torrentSource = torrentSource.withFallbackTrackers(MediaSource.PUBLIC_TRACKERS);
                        torrent = engine.add(torrentSource);
                        // Real swarms need time to answer through trackers and DHT; loopback fixtures answer at once.
                        main.post(() -> { if (current(mine) && switching) showLoading("peers", "Finding peers", sourceLabel(source), false); });
                    }
                    List<TorrentFile> files = engine.awaitMetadata(torrent, options.localOnly ? 20_000 : METADATA_MS);
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
                        .position(resumePosition).continuing(automatic && attempts > 1).audioLanguage(audio.isEmpty() || audio.equals("off") ? null : audio)
                        .headers(source == null ? Collections.emptyMap() : source.requestHeaders).subtitleHeaders(Collections.emptyMap())
                        .subtitles(captions).subtitleRequest(new SubtitleTracks.Request(subtitles.equals("off") ? "off" : null, subtitles.equals("off") ? null : subtitles, null))
                        .dataSource(dataSource).build();
                request.check();
                main.post(() -> {
                    if (!current(mine)) return;
                    switching = false; lastAdvanceMs = SystemClock.elapsedRealtime(); startupWatchdog.start(lastAdvanceMs); session.load(playback);
                    showLoading("video", "Preparing video", null, true);
                });
            } catch (Exception e) {
                if (!current(mine)) return;
                if (e instanceof TorrentException && ((TorrentException) e).kind == TorrentException.LOW_STORAGE) {
                    String cache = services.store.preference("cacheMiB", "256");
                    main.post(() -> { if (current(mine)) lowStorage(cache); });
                    return;
                }
                main.post(() -> { if (current(mine)) attemptFailed(Failures.message(e), "opening"); });
            }
        });
    }
    /** Offers this app's own cleanup, or a smaller cache, then tries the same source again. */
    private void lowStorage(String size) {
        failure("Not enough storage", Failures.message(new TorrentException(TorrentException.LOW_STORAGE, "")), "storage");
        Tv.Alert alert = Tv.alert(this, "Not enough storage", "Playback needs room for its cache plus 512 MiB free.\nClearing removes only temporary files; your library and history stay.");
        alert.button("clear", "Clear and retry", () -> { alert.dialog.dismiss(); loader.execute(() -> {
            closeNative(); SetupScreens.clearCaches(this, services);
            main.post(() -> { if (visible && !releasing) onRetry(); });
        }); });
        if (!size.equals("128")) alert.button("smaller-cache", "Use a 128 MiB cache", () -> { alert.dialog.dismiss(); app.persistence.execute(() -> {
            services.store.setPreference("cacheMiB", "128");
            main.post(() -> { if (visible && !releasing) onRetry(); });
        }); });
        alert.cancelButton("close", "Close", alert.dialog::dismiss);
        choiceDialog = show(alert);
    }
    private static boolean sameLanguage(String one, String two) {
        if (one == null || two == null || two.isEmpty()) return false;
        try { return new java.util.Locale(one).getISO3Language().equals(new java.util.Locale(two).getISO3Language()); } catch (Exception e) { return one.equalsIgnoreCase(two); }
    }
    private static String mime(String url) { String lower = Uri.parse(url).getPath(); lower = lower == null ? "" : lower.toLowerCase(java.util.Locale.ROOT); return lower.endsWith(".srt") ? "application/x-subrip" : lower.endsWith(".ass") || lower.endsWith(".ssa") ? "text/x-ssa" : "text/vtt"; }
    private String episodeLabel() { for (Title.Episode e : title.episodes) if (e.id.equals(video)) return "S" + e.season + " E" + e.number + " · " + e.name; return ""; }
    /** Viewing context stays separate from the shared audio/subtitle summary. */
    private void updateWatchingInfo(PlaybackSnapshot snapshot) {
        if (title == null) return;
        List<String> info = new ArrayList<>();
        if (!title.releaseInfo.isEmpty()) info.add(title.releaseInfo);
        if (!title.genres.isEmpty()) info.add(String.join(" / ", title.genres.subList(0, Math.min(2, title.genres.size()))));
        if (!title.rating.isEmpty()) info.add("★ " + title.rating);
        if (snapshot.durationMs > 0) info.add(((snapshot.durationMs + 59_999) / 60_000) + " min");
        android.widget.TextView view = findViewById(R.id.watching_info);
        view.setText(String.join("  ·  ", info));
        view.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void chooseFile(MediaSource source, List<TorrentFile> files) {
        String[] names = new String[files.size()]; for (int i = 0; i < names.length; i++) names[i] = files.get(i).name() + " · " + files.get(i).size / (1 << 20) + " MiB";
        choiceDialog = show(Tv.choices(this, "Choose a video file", names, which -> open(source, files.get(which).index), this::finish));
    }
    private void chooseSources() {
        if (session == null) return;
        save(session.snapshot()); session.stop(); cancelPreparation(); loader.execute(this::closeNative);
        if (candidates.isEmpty()) { failure("No sources available", "Refresh sources on the title page.", "no-sources"); return; }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < names.length; i++) {
            MediaSource c = candidates.get(i); TvStore.SourceMark mark = marks.get(SourcePolicy.key(c));
            String state = mark == null ? "" : mark.state.equals(TvStore.SourceMark.PLAYED) ? "✓ Played  ·  " : "✕ Didn't play  ·  ";
            names[i] = state + c.name.replace('\n', ' ') + "  ·  " + SourcePolicy.assess(c).label;
        }
        choiceDialog = show(Tv.choices(this, "Choose source", names, which -> {
            automatic = false; explicitKey = SourcePolicy.key(candidates.get(which)); candidate = which; attempts = 0; open(candidates.get(which), -1);
        }, this::finish));
    }
    private void attemptFailed(String message, String code) {
        if (!visible || releasing) return;
        PlaybackSnapshot state = session == null ? null : session.snapshot();
        try {
            JSONObject failed = new JSONObject().put("attempt", attempts).put("code", code)
                    .put("renderedFirstFrame", state != null && state.renderedFirstFrame)
                    .put("positionMs", state == null ? 0 : state.positionMs);
            if (observedVideo != null) failed.put("videoMime", observedVideo.sampleMimeType)
                    .put("width", observedVideo.width).put("height", observedVideo.height).put("codecs", observedVideo.codecs);
            if (observedAudio != null) failed.put("audioMime", observedAudio.sampleMimeType);
            if (state != null && state.error != null) failed.put("playerCode", state.error.code).put("detail", state.error.detail);
            sourceFailures.put(failed);
        } catch (Exception ignored) { /* diagnostic fields never prevent recovery */ }
        if (state == null || !state.renderedFirstFrame) markSource(TvStore.SourceMark.FAILED);
        if (automatic && attempts < 3) {
            int next = SourcePolicy.nextCandidate(candidates, attemptedSources, attemptedProviders);
            if (next >= 0) {
                if (state != null && state.positionMs > 0) resumePosition = state.positionMs;
                keepPausedOnRecovery = state != null && state.loaded && !state.playWhenReady;
                candidate = next; int mine = generation, chosen = next;
                // Claim recovery before another player callback can queue a second attempt.
                switching = true;
                session.stop();
                findViewById(R.id.player_root).requestFocus();
                showLoading("sources", "Trying another source", sourceLabel(candidates.get(next)), false);
                main.post(() -> { if (current(mine)) open(candidates.get(chosen), -1); }); return;
            }
        }
        failure(automatic ? "No playable source found" : "Could not play this source", message, code);
    }
    /** Remembers how the current source went, for the marks on Sources. Imports have no source list. */
    private void markSource(String state) {
        if (selected == null || title == null || services == null) return;
        String titleKey = title.key(), key = SourcePolicy.key(selected), forVideo = video;
        TvStore.SourceMark before = marks.get(key);
        if (before == null || !before.state.equals(TvStore.SourceMark.PLAYED)) marks.put(key, new TvStore.SourceMark(state, System.currentTimeMillis()));
        app.persistence.execute(() -> services.store.markSource(titleKey, forVideo, key, state));
    }
    /** A source's own name, on one line, under the title while it opens. */
    private static String sourceLabel(MediaSource source) { return source == null ? "Import" : source.name.replace('\n', ' ').trim(); }
    private void failure(String heading, String message, String code) {
        Sounds.play(Sounds.Sound.WARNING);
        switching = false;
        if (screen == null || session == null) return;
        handledError = new PlaybackErrors.Description(code, heading, message, "", 0, true);
        session.fail(handledError);
        cancelPreparation(); loader.execute(this::closeNative);
        screen.setLoading(null); screen.render();
    }
    private void queueFailure(String message, String code) {
        if (failureQueued || releasing || switching) return;
        failureQueued = true; int mine = generation;
        main.post(() -> { if (current(mine)) attemptFailed(message, code); });
    }
    @Override public void onUnsupportedFormat(String format) { queueFailure("This TV can't decode " + format + ".", "unsupported"); }
    @Override public void onPlaybackChanged(PlaybackSnapshot snapshot) {
        if (releasing || session == null || switching) return;
        updateWatchingInfo(snapshot);
        if (keepPausedOnRecovery && snapshot.error == null) {
            if (snapshot.playWhenReady) { session.pause(); return; }
            if (snapshot.started) keepPausedOnRecovery = false;
        }
        if (snapshot.videoFormat != null) observedVideo = snapshot.videoFormat;
        if (snapshot.audioFormat != null) observedAudio = snapshot.audioFormat;
        if (snapshot.renderedFirstFrame && firstFrameMs == 0) { firstFrameMs = SystemClock.elapsedRealtime() - startMs; markSource(TvStore.SourceMark.PLAYED); }
        // The viewer's pause and play, heard as small sounds; never the first state a source starts in.
        if (snapshot.started && snapshot.error == null) {
            if (heardPlay != null && heardPlay != snapshot.playWhenReady) Sounds.play(snapshot.playWhenReady ? Sounds.Sound.RESUME : Sounds.Sound.PAUSE);
            heardPlay = snapshot.playWhenReady;
        }
        if (snapshot.error != null) {
            // Compare the actual host-owned description, not strings shared with Media3's error vocabulary.
            if (snapshot.error != handledError) queueFailure(playbackFailure(snapshot.error), "opening");
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
        if (error.code.equals("decoder")) return "This TV can't decode this source's format.";
        if (error.code.equals("format")) return "No readable video in this source.";
        return error.message;
    }
    @Override public void onProgress(PlaybackSnapshot snapshot) { if (!releasing) { hearSeek(snapshot); save(snapshot); } }
    /** A skip, however it was made (remote, timeline, menu), sounds by its direction: the position jumped from where time had it. */
    private void hearSeek(PlaybackSnapshot snapshot) {
        long now = SystemClock.elapsedRealtime();
        if (!switching && snapshot.started && heardAt != 0) {
            long expected = heardPosition + (snapshot.playing ? (long) ((now - heardAt) * snapshot.speed) : 0);
            long jump = snapshot.positionMs - expected;
            if (jump > 4000) Sounds.play(Sounds.Sound.SEEK_FORWARD); else if (jump < -4000) Sounds.play(Sounds.Sound.SEEK_BACK);
        }
        heardAt = now; heardPosition = snapshot.positionMs;
    }
    @Override public void onMenuToggled(boolean open) { Sounds.play(open ? Sounds.Sound.MENU_OPEN : Sounds.Sound.MENU_CLOSE); }
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
        if (!visible || releasing || screen == null) return;
        Title.Episode next = autoplay ? neighbour(1) : null;
        Sounds.play(next != null ? Sounds.Sound.UP_NEXT : Sounds.Sound.FINISHED);
        if (next != null) screen.offerUpNext(next.name, "Season " + next.season, () -> episode(next));
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
    private static final int[] ERROR_BUTTONS = {R.id.error_retry, R.id.error_sources, R.id.error_close};
    /** Route the app's remote focus graph before shared transport shortcuts, including error alert actions. */
    @Override public boolean onKey(int code) {
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        View focused = getCurrentFocus();
        if (session == null) return false;
        if (session.error() != null) {
            if (ok && focused != null) for (int id : ERROR_BUTTONS)
                if (focused.getId() == id) { focused.performClick(); return true; }
            return false;
        }
        if (switching || !session.started() || screen.isMenuOpen()) return false;
        int focus = focused == null ? View.NO_ID : focused.getId();
        boolean header = focus == R.id.control_options || focus == R.id.control_stop;
        if (code == KeyEvent.KEYCODE_BACK && (header || focus == R.id.center_icon || focus == R.id.progress))
            findViewById(R.id.player_root).requestFocus();
        if ((ok && !header) || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) keepPausedOnRecovery = false;
        if (ok && header) { focused.performClick(); return true; }
        if (header && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            screen.showControls();
            View destination = findViewById(code == KeyEvent.KEYCODE_DPAD_LEFT ? R.id.control_stop : R.id.control_options);
            destination.setFocusableInTouchMode(true); destination.requestFocus();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            screen.showControls();
            int target = code == KeyEvent.KEYCODE_DPAD_UP
                    ? (focus == R.id.progress ? R.id.center_icon : R.id.control_stop)
                    : (header ? R.id.center_icon : R.id.progress);
            View destination = findViewById(target);
            // Seeking temporarily hides the centre glyph; never send focus to that hidden control.
            if (!destination.isShown()) destination = findViewById(code == KeyEvent.KEYCODE_DPAD_UP ? R.id.control_stop : R.id.progress);
            destination.setFocusableInTouchMode(true);
            destination.requestFocus();
            return true;
        }
        return false;
    }
    /** An error alert opens with its first button focused, so the remote is on it at once. */
    @Override public void onScreenChanged() {
        View error = findViewById(R.id.error), focused = getCurrentFocus();
        if (session == null || session.error() == null || error == null || error.getVisibility() != View.VISIBLE) return;
        for (View v = focused; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) if (v == error) return;
        for (int id : ERROR_BUTTONS) { View button = findViewById(id); if (button.getVisibility() == View.VISIBLE) { button.requestFocus(); return; } }
    }
    @Override public List<MenuPanel.Item> menuItems(boolean leading) {
        List<MenuPanel.Item> result = new ArrayList<>();
        if (leading) {
            if (canChooseSource()) result.add(MenuPanel.Item.action("Choose source", null, this::chooseSources));
            if (neighbour(-1) != null) result.add(MenuPanel.Item.action("Previous episode", null, () -> onPreviousEpisode()));
            if (neighbour(1) != null) result.add(MenuPanel.Item.action("Next episode", null, () -> onNextEpisode()));
        } else if (developerMode) result.add(MenuPanel.Item.action("Developer", "Playback diagnostics", this::diagnostics));
        return result;
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible || releasing || session == null) return;
            PlaybackSnapshot state = session.snapshot(); long now = SystemClock.elapsedRealtime();
            if (state.positionMs > previousPosition + 50 && state.playing) { lastAdvanceMs = now; previousPosition = state.positionMs; if (seekStarted > 0) { seekRecoveryMs = now - seekStarted; seekStarted = 0; } }
            if (!state.buffering && !switching && !state.playWhenReady) lastAdvanceMs = now;
            boolean torrentStartup = engine != null && !switching && !state.renderedFirstFrame;
            if (torrentStartup) {
                startupWatchdog.setPaused(!state.playWhenReady, now);
                startupWatchdog.observe(nativeStats.optLong("totalDownload"), now);
            }
            boolean expired = torrentStartup ? startupWatchdog.expired(now) : now - lastAdvanceMs > (switching ? METADATA_MS + 30_000 : 30_000);
            if ((choiceDialog == null || !choiceDialog.isShowing()) && expired
                    && (torrentStartup || state.buffering || switching || !state.started) && state.error == null) {
                String message = engine == null ? "The source stopped responding."
                        : nativeStats.optLong("hashFailures") > 0 ? "Downloaded data failed its checks."
                        : nativeStats.optInt("peers") == 0 ? "No peers are sharing this video."
                        : "Video data is arriving too slowly.";
                attemptFailed(message, "buffering");
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
            result.put("sourceAttempts", attempts).put("sourceFailures", new JSONArray(sourceFailures.toString())).put("firstFrameMs", firstFrameMs).put("seekRecoveryMs", seekRecoveryMs).put("droppedFrames", droppedFrames)
                    .put("bufferingMs", bufferingMs).put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
                    .put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            if (session != null) { PlaybackSnapshot s = session.snapshot(); result.put("positionMs", s.positionMs).put("durationMs", s.durationMs).put("renderedFirstFrame", s.renderedFirstFrame).put("playing", s.playing); }
            for (String key : new String[]{"peers", "seeds", "totalDownload", "downloadRate", "hashFailures", "evictionsRequested", "cache", "disk"}) if (nativeStats.has(key)) result.put(key, nativeStats.get(key));
        } catch (Exception ignored) { /* diagnostics remain best effort */ }
        return result;
    }
    PlaybackSnapshot playbackSnapshot() { return session == null ? null : session.snapshot(); }
    private void diagnostics() {
        Tv.Alert alert = Tv.alert(this, "Playback diagnostics", diagnosticSnapshot().toString().replace(",\"", ", \""));
        alert.cancelButton("close", "Close", alert.dialog::dismiss); choiceDialog = show(alert);
    }
    /** The player's controls and menu belong to the shared player: an arrow that moves its focus ticks here. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        boolean move = event.getAction() == KeyEvent.ACTION_DOWN && Sounds.arrow(event.getKeyCode());
        if (move) Sounds.input();
        View before = getCurrentFocus();
        boolean handled = screen != null && screen.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
        View after = getCurrentFocus();
        if (move && after != null && after != before) Sounds.focus(after);
        return handled;
    }
    @Override public void onBackPressed() { if (screen != null && screen.onRemoteKey(KeyEvent.KEYCODE_BACK)) return; Sounds.play(Sounds.Sound.BACK); finish(); }
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
