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
    private String playerClockFormat = "off";
    private boolean playerClockEnabled;
    private final PlaybackCoordinator coordinator = new PlaybackCoordinator();
    private PlaybackPreparation.Entry discovery;
    private Future<?> preparation;
    private Cancellation scope;
    private volatile int generation;
    private boolean visible, releasing, automatic, switching, failureQueued, waitingSources;
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
    private boolean polling, ranking, preferencesInitialized;
    private final StartupWatchdog startupWatchdog = new StartupWatchdog();
    private String audioChoice = "", subtitleChoice = "";
    private Dialog choiceDialog;
    private boolean episodeWatched, exitChoiceOpen;
    /** The media now loaded, used to avoid offering its subtitles for download twice. */
    private PlaybackRequest playing;
    /** Every subtitle the add-ons offer for this source, the preferred language first; the player lists them. */
    private List<SubtitleSource> subtitleOffers = Collections.emptyList();
    private static final int MAX_OFFERS = 40;
    private boolean autoplay = true;
    private int preferredSubtitleRevision;
    private final java.util.Set<String> refreshedSources = new java.util.HashSet<>();
    private boolean developerMode;
    /** Public torrents: trackers answer within seconds, a cold DHT lookup can take most of a minute. */
    private static final long METADATA_MS = 60_000;
    private final java.util.Set<String> attemptedSources = new java.util.HashSet<>();
    private final java.util.Set<String> attemptedProviders = new java.util.HashSet<>();
    private final JSONArray sourceFailures = new JSONArray();
    private PlaybackErrors.Description handledError;
    private boolean keepPausedOnRecovery, continuingPlayback;
    private float retainedSpeed = 1f;
    private Format observedVideo, observedAudio;
    private Throwable failureCause;
    private String diagnosticStage = "Starting playback";
    private JSONObject sourceResolution = new JSONObject();
    private String videoDecoder, audioDecoder;
    private final PlaybackDemand demand = new PlaybackDemand();
    private final VideoFrameWatchdog videoWatchdog = new VideoFrameWatchdog();
    private TorrentEngine.Source cachedTorrentSource, openingTorrentSource;
    private String cachedTorrentKey;
    private final java.util.Set<String> resetPayloads = new java.util.HashSet<>();
    private boolean resettingPayload;
    private long lastDemandUpdate;
    private boolean startedIntent;
    private long recoveryStarted;
    private int discoveryVersion = -1;


    private Dialog show(Tv.Alert alert) { alert.show(); return alert.dialog; }

    public static Intent intent(Context context, Title title, String video, String sourceKey) {
        return new Intent(context, WatchActivity.class).putExtra("provider", title.provider).putExtra("titleId", title.id)
                .putExtra("type", title.type).putExtra("name", title.name).putExtra("video", video).putExtra("source", sourceKey);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); app = (TvApplication) getApplication(); loader = app.playbackLoader; Sounds.get(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        video = getIntent().getStringExtra("video"); explicitKey = getIntent().getStringExtra("source");
        if (state != null) { video = state.getString("video", video); resumePosition = state.getLong("position", 0); startedIntent = true; continuingPlayback = true;
            retainedSpeed = state.getFloat("speed", 1f); keepPausedOnRecovery = state.getBoolean("paused", false); }
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
    }
    @Override protected void onStart() {
        super.onStart(); visible = true; releasing = false; developerMode = false; Sounds.get(this).refresh(this);
        setContentView(R.layout.activity_watch);
        int mine = ++generation;
        preparation = loader.submit(() -> {
            try {
                TvApplication.Services data = app.services();
                boolean diagnosticsEnabled = SetupScreens.developerMode(data);
                main.post(() -> { if (current(mine)) developerMode = diagnosticsEnabled; });
                String clockFormat = SetupScreens.clockFormat(data); boolean clockShown = SetupScreens.playerClock(data);
                Title saved = null;
                String id = getIntent().getStringExtra("titleId"), provider = getIntent().getStringExtra("provider");
                for (TvStore.LibraryEntry e : data.store.library(false)) if (e.title.id.equals(id) && e.title.provider.equals(provider)) saved = e.title;
                if (saved == null) saved = Title.parse(provider, new JSONObject().put("id", id).put("type", getIntent().getStringExtra("type")).put("name", getIntent().getStringExtra("name")));
                Title chosen = saved;
                main.post(() -> {
                    if (!current(mine)) return;
                    services = data; title = chosen; developerMode = diagnosticsEnabled;
                    playerClockFormat = clockFormat; playerClockEnabled = clockShown;
                    session = new PlaybackSession(this, data.playerPreferences, getString(R.string.app_name), "DaniilTV/0.1", this, 24 << 20, new StreamDecoders());
                    session.player().setTrackSelectionParameters(session.player().getTrackSelectionParameters().buildUpon().setMaxVideoSize(1920, 1080).build());
                    session.player().addAnalyticsListener(new AnalyticsListener() {
                        @Override public void onVideoDecoderInitialized(EventTime time, String name, long at, long duration) { videoDecoder = name; }
                        @Override public void onAudioDecoderInitialized(EventTime time, String name, long at, long duration) { audioDecoder = name; }
                        @Override public void onAudioPositionAdvancing(EventTime time, long playoutStartSystemTimeMs) { coordinator.mark("FIRST_AUDIO_OUTPUT"); }
                        @Override public void onRenderedFirstFrame(EventTime time, Object output, long at) { coordinator.mark("FIRST_VIDEO_FRAME"); }
                        @Override public void onDroppedVideoFrames(EventTime time, int count, long elapsedMs) { droppedFrames += count; }
                        @Override public void onPositionDiscontinuity(EventTime time, Player.PositionInfo old, Player.PositionInfo next, int reason) {
                            if (reason == Player.DISCONTINUITY_REASON_SEEK) { seekStarted = lastAdvanceMs = SystemClock.elapsedRealtime(); previousPosition = next.positionMs; demand.reset();
                                androidx.media3.exoplayer.DecoderCounters counters = session.player().getVideoDecoderCounters();
                                videoWatchdog.reset(seekStarted, counters == null ? 0 : counters.renderedOutputBufferCount, counters == null ? 0 : counters.droppedBufferCount); }
                        }
                    });
                    androidx.media3.ui.DaniilSubtitleOutput.install(findViewById(tv.daniil.playback.R.id.subtitles));
                    screen = new PlayerScreen(this, session, this); screen.setTitle(title.name);
                    bindRemoteFocus();
                    resolve(true); main.post(tick);
                });
            } catch (Exception e) { main.post(() -> { if (current(mine)) {
                Tv.Alert alert = Tv.alert(this, "Could not open this title", Failures.message(e));
                if (developerMode) {
                    app.lastErrorReport = DiagnosticReport.capture(this, diagnosticStage, "Could not open this title", Failures.message(e), "initialization", e, diagnosticSnapshot());
                    DiagnosticReport report = app.lastErrorReport;
                    alert.button("error-report", "Error report", () -> {
                        alert.dialog.dismiss(); Tv.Alert qr = ErrorQrPanel.alert(this, report);
                        qr.dialog.setOnDismissListener(d -> finish()); choiceDialog = show(qr);
                    });
                }
                alert.cancelButton("close", "Close", this::finish); alert.dialog.setOnCancelListener(d -> finish()); choiceDialog = show(alert);
            } }); }
        });
    }
    private boolean current(int mine) { return visible && !releasing && mine == generation && !isFinishing(); }
    /** Keep source names and transport steps behind the viewer's diagnostics switch. */
    private void showLoading(String id, String step, String detail, boolean untilReady) {
        diagnosticStage = step;
        screen.setLoading(new PlayerScreen.Loading(id, step, developerMode ? detail : null, untilReady));
    }
    /** Keep the first remote key usable after a pointer action; focus is taken before View handles the click. */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Returning false preserves each view's normal click/drag handling.
    private void bindRemoteFocus() {
        View surface = findViewById(R.id.player_root);
        surface.requestFocus();
        View clock = findViewById(R.id.player_clock);
        clock.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> updatePlayerHeaderInset());
        clock.post(this::updatePlayerHeaderInset);
        // Two buttons replace the shared single Options button: audio and subtitles, and the player's settings.
        tv.daniil.playback.Ui.action(findViewById(R.id.control_options), screen::openTracksMenu);
        tv.daniil.playback.Ui.action(findViewById(R.id.control_settings), screen::openSettingsMenu);
        tv.daniil.playback.Ui.action(findViewById(R.id.control_watched), this::markWatched);
        findViewById(R.id.center_icon).setFocusable(false);
        findViewById(R.id.center_icon).setClickable(false);
        View timeline = findViewById(R.id.progress);
        timeline.setOnClickListener(view -> {
            keepPausedOnRecovery = false;
            session.togglePlay(); screen.showControls();
        });
        timeline.setOnHoverListener((view, event) -> { screen.showControls(); return false; });
        for (int id : LIFTED) {
            View button = findViewById(id);
            button.setOnFocusChangeListener((view, has) -> lift(view, has || view.isHovered()));
            button.setOnHoverListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_HOVER_ENTER) lift(view, true);
                else if (action == android.view.MotionEvent.ACTION_HOVER_EXIT) lift(view, view.isFocused());
                return false;
            });
        }
        for (int id : new int[]{R.id.player_root, tv.daniil.playback.R.id.player_view, R.id.controls,
                R.id.control_watched, R.id.control_options, R.id.control_settings, R.id.control_stop, R.id.progress}) {
            findViewById(id).setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    // A pointer click must activate immediately, rather than only taking keyboard focus.
                    if (view != surface && view.getId() != R.id.progress && view.getId() != R.id.controls
                            && view.getId() != tv.daniil.playback.R.id.player_view) view.setFocusableInTouchMode(false);
                    if (view.getId() == R.id.progress) { keepPausedOnRecovery = false; screen.showControls(); }
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
    /** The player's buttons, which grow while focused or under the pointer, like the app's own controls. */
    static final int[] LIFTED = {R.id.control_stop, R.id.control_options, R.id.control_watched, R.id.control_settings};
    /** Left and right move along the top row in this order. */
    private static final int[] HEADER = {R.id.control_stop, R.id.control_options, R.id.control_watched, R.id.control_settings};
    private final Map<View, android.animation.Animator> lifts = new java.util.HashMap<>();
    /** Scales with its own animator: the overlay's fades cancel a view's property animation on every tick. */
    private void lift(View view, boolean on) {
        android.animation.Animator running = lifts.remove(view);
        if (running != null) running.cancel();
        float scale = on ? Tv.LIFT_ICON : 1f;
        android.animation.Animator animator = android.animation.ObjectAnimator.ofPropertyValuesHolder(view,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, scale), android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, scale));
        animator.setDuration(on ? 200 : 160);
        lifts.put(view, animator); animator.start();
    }
    private static int headerIndex(int id) { for (int i = 0; i < HEADER.length; i++) if (HEADER[i] == id) return i; return -1; }
    private void cancelPreparation() {
        generation++; polling = ranking = false; if (scope != null) scope.close(); if (preparation != null) preparation.cancel(true);
        if (choiceDialog != null) { choiceDialog.dismiss(); choiceDialog = null; }
    }
    private void closeNative() {
        coordinator.close(); openingTorrentSource = null; nativeStats = new JSONObject();
    }
    private void resolve(boolean resetAttempts) {
        if (session == null || !visible) return;
        clearErrorQr();
        save(session.snapshot()); session.stop(); cancelPreparation();
        if (resetAttempts) { coordinator.begin(startedIntent ? SystemClock.elapsedRealtime() : getIntent().getLongExtra("playTapped", 0)); startedIntent = true; recoveryStarted = 0; refreshedSources.clear(); resetPayloads.clear(); cachedTorrentSource = null; if (!continuingPlayback) keepPausedOnRecovery = false; attempts = 0; candidate = 0; attemptedSources.clear(); attemptedProviders.clear();
            while (sourceFailures.length() > 0) sourceFailures.remove(0); sourceResolution = new JSONObject(); }
        switching = waitingSources = true; lastAdvanceMs = SystemClock.elapsedRealtime(); scope = services.addons.newRequest(); scope.priority(0);
        int mine = generation; Cancellation request = scope;
        screen.setTitle(title.name); showLoading("sources", "Finding sources", null, false);
        preparation = loader.submit(() -> {
            try {
                closeNative();
                if (discovery != null) discovery.close();
                Title metadata = title;
                List<MediaSource> choices = Collections.emptyList();
                Availability empty = null;
                JSONObject resolution = new JSONObject();
                if (!title.provider.equals("local")) {
                    discoveryVersion = -1; discovery = services.preparation.take(getIntent().getStringExtra("preparation"), title, video);
                    choices = discovery.awaitCandidate(request, explicitKey);
                    metadata = discovery.title;
                    List<tv.daniil.stream.data.InstalledAddon> providers = services.addons.providers("stream", title.type, video);
                    AddonRepository.Result<MediaSource> found = discovery.result();
                    List<MediaSource> available = discovery.sources();
                    resolution.put("providersAsked", providers.size()).put("advertisedSources", available.size()).put("playableSources", choices.size());
                    if (choices.isEmpty()) empty = Availability.of(providers, available, found == null ? Collections.emptyMap() : found.failures, services.store.addons());
                }
                autoplay = services.store.preference("autoplay", "true").equals("true");
                Sounds.get(this).setEnabled(SetupScreens.soundsOn(services));
                TvStore.Progress progress = services.store.progress(metadata.key(), video);
                long resume = resumePosition > 0 ? resumePosition : progress != null && !progress.watched ? progress.positionMs : 0;
                Map<String, TvStore.SourceMark> history = services.store.sourceMarks(metadata.key(), video);
                request.check(); Title resolved = metadata; List<MediaSource> sources = choices; Availability why = empty;
                main.post(() -> {
                    if (!current(mine)) return;
                    title = resolved; episodeWatched = progress != null && progress.watched; updateWatchedButton(); coordinator.mark("FIRST_SOURCE"); resumePosition = resume; candidates = sources; marks = history; sourceResolution = resolution;
                    automatic = explicitKey == null || explicitKey.isEmpty();
                    if (title.provider.equals("local")) { open(null, -1); return; }
                    if (candidates.isEmpty()) { failure(why.heading, why.detail, "no-sources"); return; }
                    if (!automatic) {
                        for (int i = 0; i < candidates.size(); i++) if (SourcePolicy.key(candidates.get(i)).equals(explicitKey)) { candidate = i; open(candidates.get(i), -1); return; }
                        chooseSources(); return;
                    }
                    candidate = 0; open(candidates.get(0), -1);
                });
            } catch (Exception e) { main.post(() -> { if (current(mine)) { failureCause = e; failure("Could not open this title", Failures.message(e), "provider"); } }); }
        });
    }
    private void open(MediaSource source, int chosenIndex) {
        clearErrorQr();
        save(session.snapshot()); session.stop(); cancelPreparation();
        findViewById(R.id.player_root).requestFocus();
        int mine = generation; scope = services.addons.newRequest(); scope.priority(0); Cancellation request = scope;
        selected = source; waitingSources = false; playing = null; attachedSubtitles.clear(); subtitleOffers = Collections.emptyList();
        boolean payloadReset = resettingPayload; resettingPayload = false;
        if (chosenIndex < 0 && !payloadReset) { attempts++; if (source != null) { attemptedSources.add(SourcePolicy.retryKey(source)); attemptedProviders.add(source.provider); } }
        handledError = null; observedVideo = observedAudio = null; heardPlay = null; heardAt = 0;
        switching = true; failureQueued = false; preferencesInitialized = false; firstFrameMs = 0; droppedFrames = 0;
        startMs = lastAdvanceMs = SystemClock.elapsedRealtime(); previousPosition = -1; startupWatchdog.start(startMs); coordinator.attempt(); demand.reset(); videoWatchdog.reset(startMs, 0, 0); nativeStats = new JSONObject(); lastDemandUpdate = 0;
        showLoading("preparing", "Opening source", sourceLabel(source), false);
        preparation = loader.submit(() -> {
            try {
                // A chosen file reuses the metadata session; a changed source closes the previous one first.
                if (chosenIndex < 0 || payloadReset) coordinator.remove();
                String audio = services.store.preference("audioLanguage", "");
                String subtitles = services.store.preference("subtitleLanguage", "");
                if (subtitles.isEmpty()) subtitles = services.playerPreferences.getBoolean("subtitles_on", false) ? services.playerPreferences.getString("subtitle_language", "") : "off";
                List<SubtitleDownloader.Source> captions = new ArrayList<>();
                boolean subtitlesOff = subtitles.equals("off");
                // Caption providers and downloads are optional work after the video is submitted.
                List<SubtitleSource> offers = Collections.emptyList();
                Uri uri; String mediaMime = null; androidx.media3.datasource.DataSource.Factory dataSource = null;
                if (source != null && source.kind == MediaSource.Kind.HTTP) {
                    HttpMediaResolver resolved = HttpMediaResolver.resolve(source.url, source.requestHeaders, request);
                    request.add(resolved::close); uri = resolved.uri; mediaMime = resolved.mime; dataSource = resolved;
                }
                else {
                    RuntimeOptions options = new RuntimeOptions(this, services.cacheBytes());
                    if (coordinator.engine == null) {
                        File payload = new File(new File(getCacheDir(), "payload"), java.util.UUID.randomUUID().toString());
                        TorrentEngine.Config config = options.localOnly ? TorrentEngine.Config.localOnly(payload.getPath()) : new TorrentEngine.Config(payload.getPath());
                        config.cacheBytes = options.cacheBytes; config.reserveBytes = options.localOnly ? 0 : 512L << 20;
                        config.publicNetworkOnly = options.publicNetworkOnly;
                        File roots = new File(getNoBackupFilesDir(), "native-trust.pem"); TrustRoots.writePem(roots); config.trustRootsFile = roots.getPath();
                        coordinator.engine = TorrentEngine.create(config);
                    }
                    if (coordinator.torrent == 0) {
                        TorrentEngine.Source torrentSource;
                        if (source == null) { try (AddonClient client = new AddonClient()) { torrentSource = services.imports.open(title.id, client, request); } }
                        else torrentSource = source.torrentSource();
                        String identity = source == null ? "import:" + title.id : SourcePolicy.key(source);
                        if (payloadReset && identity.equals(cachedTorrentKey) && cachedTorrentSource != null) torrentSource = cachedTorrentSource;
                        if (options.localOnly) torrentSource = torrentSource.withTrackers().withPeers(options.peer);
                        else torrentSource = torrentSource.withFallbackTrackers(MediaSource.PUBLIC_TRACKERS);
                        openingTorrentSource = torrentSource;
                        coordinator.torrent = coordinator.engine.add(torrentSource);
                        // Real swarms need time to answer through trackers and DHT; loopback fixtures answer at once.
                        main.post(() -> { if (current(mine) && switching) showLoading("peers", "Finding peers", sourceLabel(source), false); });
                    }
                    List<TorrentFile> files = coordinator.engine.awaitMetadata(coordinator.torrent, options.localOnly ? 20_000 : METADATA_MS);
                    main.post(() -> { if (current(mine)) coordinator.mark("TORRENT_METADATA"); });
                    try { cachedTorrentSource = coordinator.engine.snapshotSource(coordinator.torrent,
                            openingTorrentSource);
                        cachedTorrentKey = source == null ? "import:" + title.id : SourcePolicy.key(source);
                    } catch (Exception ignored) { /* the original source remains a safe recovery path */ }
                    int explicit = chosenIndex >= 0 ? chosenIndex : source == null ? services.store.importDescriptor(title.id).optInt("fileIdx", -1) : source.fileIndex;
                    int season = -1, episode = -1; for (Title.Episode e : title.episodes) if (e.id.equals(video)) { season = e.season; episode = e.number; }
                    TorrentFile file = SourcePolicy.select(files, explicit, season, episode);
                    if (file == null) {
                        List<TorrentFile> videos = new ArrayList<>(); for (TorrentFile f : files) if (SourcePolicy.video(f)) videos.add(f);
                        if (videos.isEmpty()) throw new TorrentException(TorrentException.INVALID_FILE, "No video files");
                        request.check(); main.post(() -> { if (current(mine)) chooseFile(source, videos); }); return;
                    }
                    request.check(); coordinator.engine.selectFile(coordinator.torrent, file.index);
                    coordinator.fileBytes = file.size; coordinator.fileIndex = file.index;
                    coordinator.engine.updateDemand(coordinator.torrent, 1_250_000, 5);
                    if (source == null) services.imports.selectedFile(title.id, file.index);
                    uri = TorrentDataSource.uri(coordinator.torrent, file);
                    TorrentEngine activeEngine = coordinator.engine;
                    java.util.concurrent.atomic.AtomicBoolean firstRead = new java.util.concurrent.atomic.AtomicBoolean();
                    dataSource = () -> {
                        TorrentDataSource reader = new TorrentDataSource(activeEngine);
                        reader.addTransferListener(new androidx.media3.datasource.TransferListener() {
                            @Override public void onTransferInitializing(androidx.media3.datasource.DataSource source, androidx.media3.datasource.DataSpec spec, boolean network) {}
                            @Override public void onTransferStart(androidx.media3.datasource.DataSource source, androidx.media3.datasource.DataSpec spec, boolean network) {}
                            @Override public void onTransferEnd(androidx.media3.datasource.DataSource source, androidx.media3.datasource.DataSpec spec, boolean network) {}
                            @Override public void onBytesTransferred(androidx.media3.datasource.DataSource source, androidx.media3.datasource.DataSpec spec, boolean network, int bytes) {
                                if (bytes > 0 && firstRead.compareAndSet(false, true)) {
                                    long at = SystemClock.elapsedRealtime();
                                    main.post(() -> { if (current(mine)) coordinator.markAt("FIRST_VERIFIED_DATA", at); });
                                }
                            }
                        });
                        return reader;
                    };
                }
                PlaybackRequest playback = new PlaybackRequest.Builder(uri, title.name).id(video).mediaIdentity(video + ":" + (source == null ? "import:" + title.id : SourcePolicy.key(source))).detail(episodeLabel())
                        .position(resumePosition).startPaused(keepPausedOnRecovery).continuing(continuingPlayback || payloadReset || attempts > 1).audioLanguage(audio.isEmpty() || audio.equals("off") ? null : audio)
                        .headers(source == null ? Collections.emptyMap() : source.requestHeaders).subtitleHeaders(Collections.emptyMap())
                        .subtitles(captions).subtitleRequest(new SubtitleTracks.Request(subtitles.equals("off") ? "off" : null, subtitles.equals("off") ? null : subtitles, null))
                        .mimeType(mediaMime).dataSource(dataSource).build();
                request.check();
                main.post(() -> {
                    if (!current(mine)) return;
                    playing = playback; subtitleOffers = offers;
                    coordinator.stage(PlaybackCoordinator.State.BUFFERING);
                    switching = false; lastAdvanceMs = SystemClock.elapsedRealtime(); startupWatchdog.start(lastAdvanceMs);
                    if (continuingPlayback) session.setSpeed(retainedSpeed);
                    session.load(playback); preferredSubtitleRevision = session.subtitleSelectionRevision();
                    showLoading("video", "Preparing video", null, true);
                });
                if (source != null) {
                    String wanted = subtitlesOff ? services.playerPreferences.getString("subtitle_language", "") : subtitles;
                    app.workers.execute(() -> {
                        List<SubtitleSource> later = subtitleOffers(source, wanted, request);
                        main.post(() -> { if (current(mine) && !later.isEmpty()) {
                            subtitleOffers = later; screen.refreshMenu();
                            if (!subtitlesOff) for (SubtitleSource offer : later) if (sameLanguage(offer.language, wanted)) { attachSubtitles(offer, false); break; }
                        } });
                    });
                }
            } catch (Exception e) {
                if (!current(mine)) return;
                if (e instanceof TorrentException && ((TorrentException) e).kind == TorrentException.LOW_STORAGE) {
                    String cache = services.store.preference("cacheMiB", "256");
                    main.post(() -> { if (current(mine)) { failureCause = e; lowStorage(cache); } });
                    return;
                }
                main.post(() -> { if (current(mine)) { failureCause = e; attemptFailed(Failures.message(e), "opening"); } });
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
    /** The source's own subtitles and the subtitle add-ons' for this video, without repeats, in {@code wanted} first. */
    private List<SubtitleSource> subtitleOffers(MediaSource source, String wanted, Cancellation request) {
        List<SubtitleSource> advertised = new ArrayList<>(source.subtitles);
        Map<String, String> hints = new LinkedHashMap<>();
        if (source.filename != null) hints.put("filename", source.filename);
        if (source.videoHash != null) hints.put("videoHash", source.videoHash);
        if (source.videoSize > 0) hints.put("videoSize", Long.toString(source.videoSize));
        try { advertised.addAll(services.addons.subtitles(title.type, video, hints, request).items); } catch (Exception ignored) { /* optional subtitles never stop video */ }
        advertised.sort(java.util.Comparator.comparingInt(s -> sameLanguage(s.language, wanted) ? 0 : 1));
        List<SubtitleSource> unique = new ArrayList<>(); java.util.Set<String> urls = new java.util.HashSet<>();
        for (SubtitleSource s : advertised) if (unique.size() < MAX_OFFERS && urls.add(s.url)) unique.add(s);
        return Collections.unmodifiableList(unique);
    }
    private static String numbered(String name, Map<String, Integer> seen) {
        int count = seen.containsKey(name) ? seen.get(name) + 1 : 1; seen.put(name, count);
        return count > 1 ? name + " " + count : name;
    }
    private static String languageName(String code) {
        String name = SetupScreens.languageName(code);
        if (!name.equals(code)) return name;
        try { String display = java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH); if (!display.isEmpty()) return display; } catch (Exception ignored) { /* provider's own code */ }
        return code;
    }
    /** Offers not already loaded as tracks of the current media. */
    private List<SubtitleSource> unloadedOffers() {
        if (playing == null) return Collections.emptyList();
        java.util.Set<String> loaded = new java.util.HashSet<>();
        for (SubtitleDownloader.Source s : playing.subtitles) loaded.add(s.url);
        List<SubtitleSource> result = new ArrayList<>();
        for (SubtitleSource s : subtitleOffers) if (!loaded.contains(s.url) && !attachedSubtitles.contains(s.url) && !session.hasExternalSubtitle(s.url)) result.add(s);
        return result;
    }
    /**
     * The add-ons' subtitles not loaded yet, listed on the player's Subtitles page after the loaded tracks, so every
     * choice stays in one list whether or not a track is showing. Picking one adds it as a track and shows it.
     */
    @Override public List<MenuPanel.Item> subtitleItems() {
        List<MenuPanel.Item> items = new ArrayList<>();
        if (session == null || !session.isLoaded()) return items;
        // Numbered after the tracks already loaded, as they will be named once added.
        Map<String, Integer> seen = new java.util.HashMap<>();
        if (playing != null) for (SubtitleDownloader.Source s : playing.subtitles) numbered(languageName(s.language), seen);
        for (SubtitleSource offer : unloadedOffers()) {
            String name = languageName(offer.language);
            items.add(MenuPanel.Item.choice(numbered(name, seen), "Download", false, () -> addSubtitles(offer, name)));
        }
        return items;
    }
    private final java.util.Set<String> attachedSubtitles = new java.util.HashSet<>();
    private void addSubtitles(SubtitleSource chosen, String name) { attachSubtitles(chosen, true); }
    private void attachSubtitles(SubtitleSource chosen, boolean manual) {
        if (playing == null || session == null || !visible || releasing || !attachedSubtitles.add(chosen.url)) return;
        session.attachExternalSubtitles(Collections.singletonList(new SubtitleDownloader.Source(chosen.url, mime(chosen.url), chosen.language,
                languageName(chosen.language))), Collections.emptyMap(), manual || session.subtitleSelectionRevision() == preferredSubtitleRevision,
                loaded -> attachedSubtitles.remove(chosen.url));
        if (manual) screen.menu().close();
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
        rememberViewerState(session.snapshot());
        save(session.snapshot()); session.stop(); cancelPreparation(); loader.execute(this::closeNative);
        if (candidates.isEmpty()) { failure("No sources available", "Refresh sources on the title page.", "no-sources"); return; }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < names.length; i++) {
            MediaSource c = candidates.get(i); TvStore.SourceMark mark = marks.get(SourcePolicy.key(c));
            String state = mark == null ? "" : mark.state.equals(TvStore.SourceMark.PLAYED) ? "✓ Played  ·  " : "✕ Didn't play  ·  ";
            names[i] = state + c.name.replace('\n', ' ') + "  ·  " + SourcePolicy.assess(c).label;
        }
        choiceDialog = show(Tv.choices(this, "Choose source", names, which -> {
            automatic = false; explicitKey = SourcePolicy.key(candidates.get(which)); candidate = which; attempts = 0; recoveryStarted = 0; coordinator.begin(SystemClock.elapsedRealtime()); open(candidates.get(which), -1);
        }, this::finish));
    }
    private void attemptFailed(String message, String code) {
        if (!visible || releasing) return;
        PlaybackSnapshot state = session == null ? null : session.snapshot();
        if (state != null && state.renderedFirstFrame && (recoveryStarted == 0 || SystemClock.elapsedRealtime() - recoveryStarted >= 60_000)) {
            recoveryStarted = SystemClock.elapsedRealtime(); coordinator.recover(recoveryStarted); attempts = 1; attemptedSources.clear(); attemptedProviders.clear();
            if (selected != null) { attemptedSources.add(SourcePolicy.retryKey(selected)); attemptedProviders.add(selected.provider); }
        }
        PlaybackOutcomes.record(selected, false);
        try {
            JSONObject failed = new JSONObject().put("attempt", attempts).put("code", code)
                    .put("message", message).put("stage", diagnosticStage)
                    .put("renderedFirstFrame", state != null && state.renderedFirstFrame)
                    .put("positionMs", state == null ? 0 : state.positionMs);
            if (selected != null) failed.put("transport", selected.kind.name());
            if (observedVideo != null) failed.put("videoMime", observedVideo.sampleMimeType)
                    .put("width", observedVideo.width).put("height", observedVideo.height).put("codecs", observedVideo.codecs);
            if (observedAudio != null) failed.put("audioMime", observedAudio.sampleMimeType);
            if (state != null && state.error != null) failed.put("playerCode", state.error.code).put("detail", state.error.detail).put("httpStatus", state.error.httpStatus);
            if (developerMode) failed.put("causes", DiagnosticReport.exception(failureCause != null ? failureCause : session.player().getPlayerError()));
            sourceFailures.put(failed);
        } catch (Exception ignored) { /* diagnostic fields never prevent recovery */ }
        if (state == null || !state.renderedFirstFrame) markSource(TvStore.SourceMark.FAILED);
        int httpStatus = state != null && state.error != null ? state.error.httpStatus : 0;
        int causeDepth = 0;
        for (Throwable cause = failureCause; cause != null && causeDepth++ < 12; cause = cause.getCause())
            if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) { httpStatus = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause).responseCode; break; }
        if (selected != null && selected.kind == MediaSource.Kind.HTTP
                && (httpStatus == 401 || httpStatus == 403 || httpStatus == 410)
                && refreshedSources.add(SourcePolicy.key(selected)) && !coordinator.expired(SystemClock.elapsedRealtime())) {
            if (state != null && state.positionMs > 0) resumePosition = state.positionMs; keepPausedOnRecovery = state != null && state.loaded && !state.playWhenReady;
            resolve(false); return;
        }
        if (automatic && attempts < 3 && !coordinator.expired(SystemClock.elapsedRealtime())) {
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
        if (automatic && attempts < 3 && discovery != null && !discovery.scope.isCancelled()
                && !(discovery.complete && discovery.metadataReady) && !coordinator.expired(SystemClock.elapsedRealtime())) {
            if (state != null && state.loaded) { resumePosition = state.positionMs; keepPausedOnRecovery = !state.playWhenReady; }
            awaitAlternative(message, code); return;
        }
        failure(automatic ? "No playable source found" : "Could not play this source", message, code);
    }
    private void awaitAlternative(String message, String code) {
        switching = waitingSources = true;
        session.stop(); cancelPreparation();
        int mine = generation; PlaybackPreparation.Entry entry = discovery;
        scope = services.addons.newRequest(); scope.priority(0); Cancellation request = scope;
        java.util.Set<String> attempted = new java.util.HashSet<>(attemptedSources), providers = new java.util.HashSet<>(attemptedProviders);
        long deadline = SystemClock.elapsedRealtime() + coordinator.remaining(SystemClock.elapsedRealtime());
        showLoading("sources", "Finding another source", null, false);
        preparation = loader.submit(() -> {
            try {
                coordinator.remove();
                List<MediaSource> available = entry.awaitAlternative(request, attempted, providers, deadline);
                request.check();
                main.post(() -> { if (current(mine)) {
                    waitingSources = false; candidates = available;
                    int next = SourcePolicy.nextCandidate(available, attemptedSources, attemptedProviders);
                    if (next >= 0 && !coordinator.expired(SystemClock.elapsedRealtime())) { candidate = next; open(available.get(next), -1); }
                    else failure("No playable source found", message, code);
                } });
            } catch (Exception error) { main.post(() -> { if (current(mine)) failure("No playable source found", Failures.message(error), code); }); }
        });
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
        switching = waitingSources = false;
        if (screen == null || session == null) return;
        // Freeze before fail/stop/closeNative replace the original Media3 error and coordinator.torrent counters.
        if (developerMode) {
            JSONObject state = diagnosticSnapshot();
            try { if (session.error() != null) state.put("playerError", session.error().toJson()); }
            catch (org.json.JSONException ignored) { /* the remaining report is still useful */ }
            app.lastErrorReport = DiagnosticReport.capture(this, diagnosticStage, heading, message, code,
                    failureCause != null ? failureCause : session.player().getPlayerError(), state);
        }
        ErrorQrPanel.attach(findViewById(R.id.error_diagnostics), developerMode, developerMode ? app.lastErrorReport : null);
        handledError = new PlaybackErrors.Description(code, heading, message, "", 0, true);
        session.fail(handledError);
        cancelPreparation(); loader.execute(this::closeNative);
        screen.setLoading(null); screen.render();
    }
    private void clearErrorQr() {
        ErrorQrPanel.attach(findViewById(R.id.error_diagnostics), false, null);
        failureCause = null;
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
        ((tv.daniil.stream.ui.PlayerTimeline) findViewById(R.id.progress)).setPaused(!snapshot.playWhenReady && !snapshot.ended);
        if (keepPausedOnRecovery && snapshot.error == null) {
            if (snapshot.playWhenReady) { session.pause(); return; }
            if (snapshot.started) keepPausedOnRecovery = false;
        }
        if (snapshot.videoFormat != null) observedVideo = snapshot.videoFormat;
        if (snapshot.audioFormat != null) observedAudio = snapshot.audioFormat;
        if (snapshot.renderedFirstFrame && firstFrameMs == 0) { diagnosticStage = "Playing video"; firstFrameMs = SystemClock.elapsedRealtime() - startMs; coordinator.stage(PlaybackCoordinator.State.PLAYING); PlaybackOutcomes.record(selected, true); markSource(TvStore.SourceMark.PLAYED); }
        // The viewer's pause and play, heard as small sounds; never the first state a source starts in.
        if (snapshot.started && snapshot.error == null) {
            retainedSpeed = snapshot.speed;
            if (heardPlay != null && heardPlay != snapshot.playWhenReady) Sounds.play(snapshot.playWhenReady ? Sounds.Sound.RESUME : Sounds.Sound.PAUSE);
            heardPlay = snapshot.playWhenReady;
        }
        if (snapshot.error != null) {
            // Compare the actual host-owned description, not strings shared with Media3's error vocabulary.
            if (snapshot.error != handledError) queueFailure(playbackFailure(snapshot.error), "opening");
            return;
        }
        if (!SourcePolicy.withinDeviceProfile(snapshot.videoFormat)) {
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
    @Override public void onMenuToggled(boolean open) {
        Sounds.play(open ? Sounds.Sound.MENU_OPEN : Sounds.Sound.MENU_CLOSE);
        updatePlayerClock();
    }
    private void save(PlaybackSnapshot snapshot) {
        if (services == null || title == null || snapshot == null || !snapshot.loaded) return;
        if (snapshot.ended) { episodeWatched = true; updateWatchedButton(); }
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
        rememberViewerState(session.snapshot()); save(session.snapshot()); session.stop(); video = next.id; resumePosition = 0; explicitKey = ""; resolve(true);
    }
    @Override public boolean onNextEpisode() { Title.Episode next = neighbour(1); if (next == null) return false; episode(next); return true; }
    @Override public boolean onPreviousEpisode() { Title.Episode previous = neighbour(-1); if (previous == null) return false; episode(previous); return true; }
    @Override public boolean canRetry() { return true; }
    @Override public void onRetry() { if (session != null && session.snapshot().positionMs > 0) resumePosition = session.snapshot().positionMs; resolve(true); }
    @Override public boolean canChooseSource() { return title != null && !title.provider.equals("local"); }
    @Override public void onChooseSource() { chooseSources(); }
    private void updateWatchedButton() {
        View button = findViewById(R.id.control_watched);
        button.setActivated(episodeWatched);
        button.setAlpha(episodeWatched ? 0.6f : 1f);
        button.setContentDescription(episodeWatched ? "Watched" : "Mark watched");
    }
    private void markWatched() {
        if (session == null || services == null || title == null || !session.snapshot().started) return;
        if (episodeWatched) { screen.notice("Watched"); return; }
        PlaybackSnapshot state = session.snapshot();
        Title markedTitle = title; String markedVideo = video;
        episodeWatched = true; updateWatchedButton();
        // Use the same ordered persistence queue as progress; later saves preserve the watched flag.
        app.persistence.execute(() -> services.store.progress(markedTitle, markedVideo, state.positionMs, state.durationMs, true));
        Sounds.play(Sounds.Sound.WATCHED_ON); screen.notice("Marked watched");
    }
    @Override public void onExit() {
        if (exitChoiceOpen) return;
        PlaybackSnapshot state = session == null ? null : session.snapshot();
        if (state == null || !state.started || state.error != null || state.ended || session.player().isCurrentMediaItemLive()
                || !WatchCompletion.offerOnExit(state.positionMs, state.durationMs, episodeWatched)) { finish(); return; }
        exitChoiceOpen = true;
        boolean resume = state.playWhenReady;
        session.pause();
        Tv.Alert alert = Tv.alert(this, "Finished watching?", "Mark this " + (title.type.equals("series") ? "episode" : "film") + " watched, or keep your place for later.");
        // Keeping the place is the default; time remaining alone never marks an episode watched.
        alert.button("keep-progress", "Keep for later", () -> { alert.dialog.dismiss(); finish(); });
        alert.button("mark-watched", "Mark watched", () -> { markWatched(); alert.dialog.dismiss(); finish(); });
        alert.cancelButton("continue-watching", "Continue watching", alert.dialog::cancel);
        alert.dialog.setOnCancelListener(dialog -> {
            exitChoiceOpen = false;
            if (resume && session != null && !releasing) session.play();
        });
        choiceDialog = show(alert);
    }
    private static final int[] ERROR_BUTTONS = {R.id.error_retry, R.id.error_sources, R.id.error_close};
    /** Route the app's remote focus graph before shared transport shortcuts, including error alert actions. */
    @Override public boolean onKey(int code) {
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        View focused = getCurrentFocus();
        if (session == null) return false;
        if (session.error() != null) {
            if (ok && focused != null && (focused.getId() == R.id.error_qr_previous || focused.getId() == R.id.error_qr_next)) {
                focused.performClick(); return true;
            }
            if (ok && focused != null) for (int id : ERROR_BUTTONS)
                if (focused.getId() == id) { focused.performClick(); return true; }
            return false;
        }
        if (switching || !session.started() || screen.isMenuOpen()) return false;
        int focus = focused == null ? View.NO_ID : focused.getId();
        boolean header = headerIndex(focus) >= 0;
        if (code == KeyEvent.KEYCODE_BACK && (header || focus == R.id.progress))
            findViewById(R.id.player_root).requestFocus();
        if ((ok && !header) || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) keepPausedOnRecovery = false;
        if (ok && header) { focused.performClick(); return true; }
        if (header && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            screen.showControls();
            int next = Math.max(0, Math.min(HEADER.length - 1, headerIndex(focus) + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
            View destination = findViewById(HEADER[next]);
            destination.setFocusableInTouchMode(true); destination.requestFocus();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            screen.showControls();
            int target = code == KeyEvent.KEYCODE_DPAD_UP ? R.id.control_options : R.id.progress;
            View destination = findViewById(target);
            if (!destination.isShown()) destination = findViewById(code == KeyEvent.KEYCODE_DPAD_UP ? R.id.control_stop : R.id.progress);
            destination.setFocusableInTouchMode(true);
            destination.requestFocus();
            return true;
        }
        return false;
    }
    /** An error alert opens with its first button focused, so the remote is on it at once. */
    @Override public void onScreenChanged() {
        updatePlayerClock();
        View error = findViewById(R.id.error), focused = getCurrentFocus();
        if (session == null || session.error() == null || error == null || error.getVisibility() != View.VISIBLE) return;
        for (View v = focused; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) if (v == error) return;
        for (int id : ERROR_BUTTONS) { View button = findViewById(id); if (button.getVisibility() == View.VISIBLE) { button.requestFocus(); return; } }
    }
    /** Keep the picture clear during playback and loading; the clock belongs to paused controls and menus. */
    private void updatePlayerClock() {
        View clock = findViewById(R.id.player_clock);
        if (clock == null) return;
        boolean shown = screen != null && session != null && session.started() && screen.loading() == null
                && session.error() == null && (screen.isMenuOpen()
                || !session.player().getPlayWhenReady() && session.player().getPlaybackState() == Player.STATE_READY);
        int previous = clock.getVisibility();
        tv.daniil.stream.ui.Clock.apply((android.widget.TextClock) clock, playerClockFormat, playerClockEnabled && shown);
        if (previous != clock.getVisibility()) clock.post(this::updatePlayerHeaderInset);
    }
    private void updatePlayerHeaderInset() {
        View clock = findViewById(R.id.player_clock), header = findViewById(R.id.player_header);
        android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) header.getLayoutParams();
        int inset = Tv.dp(this, 48) + (clock.isShown() ? clock.getWidth() + Tv.dp(this, 16) : 0);
        if (params.getMarginEnd() != inset) { params.setMarginEnd(inset); header.setLayoutParams(params); }
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
            View focused = getCurrentFocus();
            if (findViewById(R.id.controls).isShown() && focused != null
                    && (headerIndex(focused.getId()) >= 0 || focused.getId() == R.id.progress)) screen.showControls();
            if (state.renderedFirstFrame && state.playing && state.positionMs > 1000 && coordinator.firstAvMs(state.audioFormat != null) > 0) coordinator.mark("STABLE_PLAYBACK");
            if (state.positionMs > previousPosition + 50 && state.playing) { lastAdvanceMs = now; previousPosition = state.positionMs; if (seekStarted > 0) { seekRecoveryMs = now - seekStarted; seekStarted = 0; } }
            if (!state.buffering && !switching && !state.playWhenReady) lastAdvanceMs = now;
            androidx.media3.exoplayer.DecoderCounters videoCounters = session.player().getVideoDecoderCounters();
            if (videoCounters != null) {
                videoCounters.ensureUpdated();
                if (videoWatchdog.unhealthy(now, videoCounters.renderedOutputBufferCount, videoCounters.droppedBufferCount,
                        state.playing && state.bufferedMs - state.positionMs >= 2000 && !switching)) {
                    attemptFailed("This video is too demanding for the TV to play smoothly.", "video-performance");
                    main.postDelayed(this, 1000); return;
                }
            }
            if (!ranking && discovery != null && !discovery.scope.isCancelled() && discoveryVersion != discovery.version()) {
                ranking = true; PlaybackPreparation.Entry entry = discovery; int version = entry.version(), mine = generation;
                app.workers.execute(() -> {
                    List<MediaSource> discovered = SourcePolicy.ranked(entry.sources());
                    main.post(() -> { if (current(mine) && discovery == entry) {
                        ranking = false; discoveryVersion = version; if (!discovered.isEmpty()) candidates = discovered;
                    } });
                });
            }
            coordinator.paused(state.loaded && !switching && !state.playWhenReady, now);
            boolean startup = !state.renderedFirstFrame;
            boolean alternative = automatic && attempts < 3 && SourcePolicy.nextCandidate(candidates, attemptedSources, attemptedProviders) >= 0;
            startupWatchdog.setPaused(state.loaded && !state.playWhenReady, now);
            startupWatchdog.observe(nativeStats.optLong("verifiedReadBytes"), now);
            long quiet = startupWatchdog.quietMs(now), elapsed = coordinator.attemptElapsed(now);
            boolean stalledStage = !waitingSources && alternative && (elapsed >= 25_000
                    || coordinator.torrent != 0 && nativeStats.optInt("peers") == 0 && elapsed >= 8000
                    || "metadata".equals(nativeStats.optString("state")) && elapsed >= 12_000);
            boolean pressure = coordinator.torrent != 0 && nativeStats.optInt("urgentReservationFailures") > 0
                    && nativeStats.optInt("obsoletePartials") > 0 && quiet >= 6000;
            if ((choiceDialog == null || !choiceDialog.isShowing()) && state.error == null) {
                String identity = selected == null ? "import:" + title.id : SourcePolicy.key(selected);
                if (pressure && resetPayloads.add(identity)) {
                    if (state.renderedFirstFrame) coordinator.recover(now);
                    resumePosition = state.positionMs; keepPausedOnRecovery = !state.playWhenReady;
                    int selectedIndex = coordinator.fileIndex;
                    resettingPayload = true; open(selected, selectedIndex);
                } else if (startup && (coordinator.expired(now) || stalledStage)
                        || !switching && StartupWatchdog.bufferingStalled(state.buffering && state.playWhenReady, bufferingStarted, quiet, now, alternative ? 6000 : 30_000)) {
                    String message = coordinator.torrent == 0 ? "The source stopped responding."
                            : nativeStats.optLong("hashFailures") > 0 ? "Downloaded data failed its checks."
                            : nativeStats.optInt("peers") == 0 ? "No peers are sharing this video." : "Video data is arriving too slowly.";
                    attemptFailed(message, "buffering");
                }
            }
            if (!polling && coordinator.engine != null && coordinator.torrent != 0) {
                polling = true; int mine = generation;
                TorrentEngine active = coordinator.engine; long handle = coordinator.torrent;
                long bytesPerSecond = demand.estimate(coordinator.fileBytes, state.durationMs, nativeStats.optLong("verifiedReadBytes"), state.positionMs, now);
                int ahead = PlaybackDemand.aheadSeconds(state.renderedFirstFrame, state.bufferedMs - state.positionMs);
                boolean update = !switching && now - lastDemandUpdate >= 5000; if (update) lastDemandUpdate = now;
                app.playbackTelemetry.execute(() -> {
                    JSONObject stats = null;
                    try { if (handle != 0) {
                        if (update) active.updateDemand(handle, (long) (bytesPerSecond * state.speed), ahead);
                        stats = active.stats(handle);
                    } } catch (Exception ignored) { /* source is closing */ }
                    JSONObject value = stats; main.post(() -> { if (current(mine)) { polling = false; if (value != null) nativeStats = value; } });
                });
            }
            main.postDelayed(this, 1000);
        }
    };
    JSONObject diagnosticSnapshot() {
        JSONObject result = new JSONObject();
        try {
            result.put("playTapToFirstAvMs", coordinator.firstAvMs(observedAudio != null));
            result.put("startupStagesMs", coordinator.diagnostics()).put("playTapToNowMs", coordinator.elapsed(SystemClock.elapsedRealtime()));
            result.put("sourceAttempts", attempts).put("sourceFailures", new JSONArray(sourceFailures.toString())).put("firstFrameMs", firstFrameMs).put("seekRecoveryMs", seekRecoveryMs).put("droppedFrames", droppedFrames)
                    .put("videoDecoder", videoDecoder).put("audioDecoder", audioDecoder)
                    .put("bufferingMs", bufferingMs).put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
                    .put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            result.put("automatic", automatic).put("candidateCount", candidates.size()).put("stage", diagnosticStage);
            result.put("sourceResolution", sourceResolution).put("elapsedSourceMs", startMs == 0 ? 0 : SystemClock.elapsedRealtime() - startMs);
            if (selected != null) result.put("transport", selected.kind.name());
            if (observedVideo != null) result.put("videoMime", observedVideo.sampleMimeType).put("codecs", observedVideo.codecs)
                    .put("width", observedVideo.width).put("height", observedVideo.height);
            if (observedAudio != null) result.put("audioMime", observedAudio.sampleMimeType);
            if (session != null) { PlaybackSnapshot s = session.snapshot(); result.put("positionMs", s.positionMs).put("durationMs", s.durationMs).put("renderedFirstFrame", s.renderedFirstFrame).put("playing", s.playing); }
            for (String key : new String[]{"verifiedReadBytes", "urgentReservationFailures", "obsoletePartials", "peers", "seeds", "totalDownload", "downloadRate", "hashFailures", "evictionsRequested", "cache", "disk"}) if (nativeStats.has(key)) result.put(key, nativeStats.get(key));
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
    @Override public void onBackPressed() { if (screen != null && screen.onRemoteKey(KeyEvent.KEYCODE_BACK)) return; Sounds.play(Sounds.Sound.BACK); onExit(); }
    @Override protected void onStop() {
        visible = false; releasing = true;
        if (session != null) { PlaybackSnapshot state = session.snapshot(); rememberViewerState(state); save(state); }
        cancelPreparation(); if (discovery != null) { discovery.close(); discovery = null; } main.removeCallbacksAndMessages(null);
        for (android.animation.Animator animator : lifts.values()) animator.cancel();
        lifts.clear();
        if (screen != null) screen.release();
        if (session != null) session.release();
        screen = null; session = null;
        loader.execute(this::closeNative);
        super.onStop();
    }
    private void rememberViewerState(PlaybackSnapshot state) {
        if (!state.loaded) return;
        resumePosition = state.positionMs; retainedSpeed = state.speed;
        keepPausedOnRecovery = !state.playWhenReady; continuingPlayback = true;
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("video", video); out.putLong("position", session == null ? resumePosition : session.snapshot().positionMs);
        out.putFloat("speed", session == null ? retainedSpeed : session.snapshot().speed);
        out.putBoolean("paused", session == null ? keepPausedOnRecovery : !session.snapshot().playWhenReady); }
}
