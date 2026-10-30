package tv.stream.app

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.core.view.doOnAttach
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import tv.stream.playback.*
import tv.stream.app.addon.*
import tv.stream.app.data.TvStore
import tv.stream.app.ui.ComposePlayerControls
import tv.stream.app.ui.ComposePlayerLayers
import tv.stream.app.ui.Failures
import tv.stream.app.ui.Sounds
import tv.stream.app.ui.Tv
import tv.stream.torrent.*

/** Retains paused playback across temporary hiding. The serial loader owns native create/select/destroy operations. */
@OptIn(markerClass = [UnstableApi::class])
@Suppress("DEPRECATION")
class WatchActivity : ComponentActivity(), PlaybackHost {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var loader: ExecutorService
    private lateinit var app: TvApplication
    private var services: TvApplication.Services? = null
    private var session: PlaybackSession? = null
    private var screen: PlayerScreen? = null
    private var playerClockFormat: String = "off"
    private var playerClockEnabled = false
    private val coordinator = PlaybackCoordinator()
    private val progress by lazy { PlaybackProgress((application as TvApplication).persistence) { services?.store } }
    private val episodes = EpisodeNavigation()
    private lateinit var playback: PlaybackViewModel
    private lateinit var work: PlaybackWork
    private val generation: Int get() = work.generation
    private var visible = false
    private var releasing = false
    private var switching = false
    private var failureQueued = false
    private var waitingSources = false
    private var retainedWhileStopped = false
    private var title: Title? = null
    private var video: String? = null
    private var explicitKey: String? = null
    /** How each candidate went before, read with them; this activity's own outcomes are added as they happen. */
    private var marks: MutableMap<String, TvStore.SourceMark> = java.util.HashMap()
    /** What the sounds last heard: the play state (only the viewer's change of it sounds), and where the position was and when. */
    private var heardPlay: Boolean? = null
    private var heardAt: Long = 0
    private var heardPosition: Long = 0
    private var startMs: Long = 0
    private var firstFrameMs: Long = 0
    private var lastAdvanceMs: Long = 0
    private var previousPosition: Long = 0
    private var bufferingStarted: Long = 0
    private var bufferingMs: Long = 0
    private var seekStarted: Long = 0
    private var seekRecoveryMs: Long = 0
    private var resumePosition: Long = 0
    private var droppedFrames = 0
    private var nativeStats = JSONObject()
    private var polling = false
    private var ranking = false
    private val startupWatchdog = StartupWatchdog()
    private var choiceDialog: Dialog? = null
    private var playerMenus: PlayerMenus? = null
    private var exitChoiceOpen = false
    /** The media now loaded, used to avoid offering its subtitles for download twice. */
    private var playing: PlaybackRequest? = null
    /** Every subtitle the add-ons offer for this source, the preferred language first; the player lists them. */
    private var subtitleOffers: List<SubtitleSource> = Collections.emptyList()
    private var autoplay = true
    private var preferredSubtitleRevision = 0
    private val refreshedSources: MutableSet<String> = java.util.HashSet()
    private var developerMode = false
    private val sourceFailures = JSONArray()
    private var handledError: PlaybackErrors.Description? = null
    private var keepPausedOnRecovery = false
    private var continuingPlayback = false
    private var retainedSpeed = 1f
    private var observedVideo: Format? = null
    private var observedAudio: Format? = null
    private var failureCause: Throwable? = null
    private var diagnosticStage: String = "Starting playback"
    private var sourceResolution = JSONObject()
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private val demand = PlaybackDemand()
    private val videoWatchdog = VideoFrameWatchdog()
    private var cachedTorrentSource: TorrentEngine.Source? = null
    private var openingTorrentSource: TorrentEngine.Source? = null
    private var cachedTorrentKey: String? = null
    private val resetPayloads: MutableSet<String> = java.util.HashSet()
    private var resettingPayload = false
    private var lastDemandUpdate: Long = 0
    private var playerControls: ComposePlayerControls? = null
    private var playerLayers: ComposePlayerLayers? = null
    private var startedIntent = false
    private var recoveryStarted: Long = 0
    private var discoveryVersion = -1

    companion object {
        /** Public torrents: trackers answer within seconds, a cold DHT lookup can take most of a minute. */
        private const val METADATA_MS: Long = 60_000
        /** The player's buttons, which grow while focused or under the pointer, like the app's own controls. */
        val LIFTED = intArrayOf(R.id.control_stop, R.id.control_watched, R.id.control_options, R.id.control_settings)
        /** Left and right move along the top row in this order. */
        private val HEADER = intArrayOf(R.id.control_stop, R.id.control_watched, R.id.control_options, R.id.control_settings)
        private val ERROR_BUTTONS = intArrayOf(R.id.error_retry, R.id.error_sources, R.id.error_close)

        fun intent(context: Context, title: Title, video: String?, sourceKey: String?): Intent {
            return Intent(context, WatchActivity::class.java).putExtra("provider", title.provider).putExtra("titleId", title.id)
                    .putExtra("type", title.type).putExtra("name", title.name).putExtra("logo", title.logo).putExtra("video", video).putExtra("source", sourceKey)
        }
        private fun headerIndex(id: Int): Int { for (i in 0 until HEADER.size) if (HEADER[i] == id) return i; return -1 }
        /** A source's own name, on one line, under the title while it opens. */
        private fun sourceLabel(source: MediaSource?): String { return if (source == null) "Import" else source.name.replace('\n', ' ').trim { it <= ' ' } }
    }

    internal fun playerTimelineDiagnostics(): String = playerControls?.timelineDiagnostics().orEmpty()
    internal fun playerControlBounds(id: Int): android.graphics.Rect? = playerControls?.bounds(id) ?: playerLayers?.bounds(id)
    internal fun playerControlFocused(id: Int): Boolean = playerControls?.focusedId() == id || playerLayers?.focusedId() == id

    private fun show(alert: Tv.Alert): Dialog { alert.show(); return alert.dialog }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        app = application as TvApplication
        loader = app.playbackLoader
        playback = ViewModelProvider(this)[PlaybackViewModel::class.java]
        work = playback.attach()
        lifecycleScope.launch {
            playback.presentation.collect { renderPresentation(it) }
        }
        Sounds.get(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        video = intent.getStringExtra("video"); explicitKey = intent.getStringExtra("source")
        if (state != null) { video = state.getString("video", video); resumePosition = state.getLong("position", 0); startedIntent = true; continuingPlayback = true
            retainedSpeed = state.getFloat("speed", 1f); keepPausedOnRecovery = state.getBoolean("paused", false) }
        // ComponentActivity bridges this callback to platform Back on API 33+ and onBackPressed below it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exitOrCloseOverlay()
        })
    }
    override fun onStart() {
        super.onStart(); visible = true; releasing = false; Sounds.get(this).refresh(this)
        if (retainedWhileStopped && session != null) {
            retainedWhileStopped = false
            // Keep Media3, its source scope and the native cache together; rebuilding any of them reloads the film.
            session!!.pause()
            val state = session!!.snapshot()
            rememberViewerState(state)
            failureQueued = false; ranking = failureQueued; polling = ranking
            val now = SystemClock.elapsedRealtime()
            heardPlay = false; heardAt = now; previousPosition = state.positionMs; heardPosition = previousPosition
            lastAdvanceMs = now; seekStarted = 0; bufferingStarted = if (state.buffering) now else 0
            val counters = session!!.player().videoDecoderCounters
            videoWatchdog.reset(now, if (counters == null) 0L else counters.renderedOutputBufferCount.toLong(), if (counters == null) 0L else counters.droppedBufferCount.toLong())
            onPlaybackChanged(state)
            screen!!.render(); screen!!.showControls(); updatePlayerClock()
            main.removeCallbacks(tick); main.post(tick)
            return
        }
        if (work.isClosed) work = playback.attach()
        developerMode = false
        setContentView(R.layout.activity_watch)
        progress.startEpisode()
        val mine = work.nextGeneration()
        work.task = loader.submit(Runnable {
            try {
                val setup = playback.sessionSetup(app, PlaybackLaunch(
                        provider = intent.getStringExtra("provider").orEmpty(),
                        titleId = intent.getStringExtra("titleId").orEmpty(),
                        type = intent.getStringExtra("type"), name = intent.getStringExtra("name"),
                        logo = intent.getStringExtra("logo")))
                main.post(Runnable {
                    if (!current(mine)) return@Runnable
                    services = setup.services; title = setup.title; developerMode = setup.developerMode
                    // Declarative loading diagnostics follow the same developer-mode preference.
                    playerClockFormat = setup.clockFormat; playerClockEnabled = setup.clockEnabled
                    session = PlaybackSession(this, setup.services.playerPreferences, getString(R.string.app_name), "Stream/0.1", this, 24 shl 20, StreamDecoders())
                    session!!.player().setTrackSelectionParameters(session!!.player().getTrackSelectionParameters().buildUpon().setMaxVideoSize(1920, 1080).build())
                    session!!.player().addAnalyticsListener(object : AnalyticsListener {
                        override fun onVideoDecoderInitialized(time: AnalyticsListener.EventTime, name: String, at: Long, duration: Long) { videoDecoder = name }
                        override fun onAudioDecoderInitialized(time: AnalyticsListener.EventTime, name: String, at: Long, duration: Long) { audioDecoder = name }
                        override fun onAudioPositionAdvancing(time: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { coordinator.mark("FIRST_AUDIO_OUTPUT") }
                        override fun onRenderedFirstFrame(time: AnalyticsListener.EventTime, output: Any, at: Long) { coordinator.mark("FIRST_VIDEO_FRAME") }
                        override fun onDroppedVideoFrames(time: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) { droppedFrames += count }
                        override fun onPositionDiscontinuity(time: AnalyticsListener.EventTime, old: Player.PositionInfo, next: Player.PositionInfo, reason: Int) {
                            if (reason == Player.DISCONTINUITY_REASON_SEEK) { lastAdvanceMs = SystemClock.elapsedRealtime(); seekStarted = lastAdvanceMs; previousPosition = next.positionMs; demand.reset()
                                val counters = session!!.player().videoDecoderCounters
                                videoWatchdog.reset(seekStarted, if (counters == null) 0L else counters.renderedOutputBufferCount.toLong(), if (counters == null) 0L else counters.droppedBufferCount.toLong()) }
                        }
                    })
                    androidx.media3.ui.StreamSubtitleOutput.install(findViewById(tv.stream.playback.R.id.subtitles))
                    playerLayers = ComposePlayerLayers(this, developerMode) { hideNextEpisode(); onNextEpisode() }.also { it.install() }
                    val controls = findViewById<android.widget.FrameLayout>(R.id.controls)
                    playerControls = ComposePlayerControls.install(controls, playback.controls, { id ->
                        when (id) {
                            R.id.progress -> keepPausedOnRecovery = false
                            R.id.control_stop -> onExit()
                            R.id.control_watched -> setWatched(!progress.watched)
                            R.id.control_options -> playerMenus?.open(true, playerControls!!.origin(id))
                            R.id.control_settings -> playerMenus?.open(false, playerControls!!.origin(id))
                        }
                    }) {
                        if (current(mine)) { resolve(true); main.post(tick) }
                    }
                    screen = PlayerScreen(this, session!!, this, playerControls, playerLayers); screen!!.keepControlsBehindMenu(true); screen!!.setTitle(title!!.name)
                    androidx.media3.ui.StreamSubtitleOutput.font(findViewById(R.id.subtitles), tv.stream.app.ui.SubtitleFonts.selected(services!!.playerPreferences))
                    androidx.media3.ui.StreamSubtitleOutput.configure(findViewById(R.id.subtitles))
                    bindRemoteFocus()
                    updateWatchingInfo()
                })
            } catch (e: Exception) { main.post { if (current(mine)) {
                val alert = Tv.alert(this, "Could not open this title", Failures.message(e))
                if (developerMode) {
                    app.lastErrorReport = DiagnosticReport.capture(this, diagnosticStage, "Could not open this title", Failures.message(e), "initialization", e, diagnosticSnapshot())
                    val report = app.lastErrorReport
                    alert.button("error-report", "Error report") {
                        alert.dialog.dismiss(); val qr = ErrorQrPanel.alert(this, report!!)
                        qr.dialog.setOnDismissListener { d -> finish() }; choiceDialog = show(qr)
                    }
                }
                alert.dialog.setOnCancelListener { d -> finish() }; choiceDialog = show(alert)
            } } }
        })
    }
    /** StateFlow owns the loading/error presentation; Media3 keeps its existing surface and controls. */
    private fun renderPresentation(presentation: PlaybackPresentation) {
        val playerScreen = screen ?: return
        if (releasing) return
        when (presentation) {
            is PlaybackPresentation.Loading -> playerScreen.setLoading(PlayerScreen.Loading(
                    presentation.id, presentation.step, presentation.detail, presentation.untilReady))
            PlaybackPresentation.Ready -> if (playerScreen.loading() != null) playerScreen.setLoading(null)
            is PlaybackPresentation.Failed -> { playerScreen.setLoading(null); playerScreen.render() }
            PlaybackPresentation.Idle, PlaybackPresentation.Released -> Unit
        }
    }
    private fun current(mine: Int): Boolean = visible && !releasing && mine == generation && !isFinishing
    /** Keep source names and transport steps behind the viewer's diagnostics switch. */
    private fun showLoading(id: String, step: String, detail: String?, untilReady: Boolean) {
        diagnosticStage = step
        playback.loading(id, step, if (developerMode) detail else null, untilReady)
    }
    /** Keep the first remote key usable after a pointer action; focus is taken before View handles the click. */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Returning false preserves each view's normal click/drag handling.
    private fun bindRemoteFocus() {
        val surface = findViewById<View>(R.id.player_root)
        surface.requestFocus()
        // Overlay fades finish independently of playback callbacks, including the inactivity timeout.
        surface.viewTreeObserver.addOnGlobalLayoutListener { updatePlayerClock() }
        val clock = findViewById<View>(R.id.player_clock)
        clock.addOnLayoutChangeListener { view, l, t, r, b, ol, ot, or, ob -> updatePlayerHeaderInset() }
        clock.post { updatePlayerHeaderInset() }
        // Two buttons replace the shared single Options button: audio and subtitles, and the player's settings.
        playerMenus = PlayerMenus(this, session!!, screen!!, services!!.playerPreferences, object : PlayerMenus.Host {
            override fun hasEpisode(direction: Int): Boolean = neighbour(direction) != null
            override fun episode(direction: Int) { this@WatchActivity.episode(neighbour(direction)) }
            override fun canChooseSource(): Boolean = this@WatchActivity.canChooseSource()
            override fun sources(page: Tv.Alert) { populateSources(page, true) }
            override fun offers(): List<PlayerMenus.SubtitleOffer> {
                val result: MutableList<PlayerMenus.SubtitleOffer> = ArrayList()
                for (offer in unloadedOffers()) result.add(PlayerMenus.SubtitleOffer(SubtitleOffers.languageName(offer.language), Runnable { attachSubtitles(offer, true) }))
                return result
            }
            override fun saveStyle(setting: SubtitleStyle.Setting, value: String) {
                app.persistence.execute { services!!.store.setPreference("subtitle_" + setting.key, value) }
            }
            override fun visibilityChanged() { updatePlayerClock(); if (session != null) updateNextEpisode(session!!.snapshot()) }
            override fun developerMode(): Boolean = developerMode
            override fun diagnostics(page: Tv.Alert) {
                val body = Tv.text(this@WatchActivity, diagnosticSnapshot().toString(), 12f, R.color.tv_text_secondary)
                body.setPadding(Tv.dp(this@WatchActivity, 12f), 0, Tv.dp(this@WatchActivity, 12f), Tv.dp(this@WatchActivity, 12f))
                body.setLineSpacing(Tv.dp(this@WatchActivity, 3f).toFloat(), 1f)
                page.body(body)
            }
        })
        findViewById<View>(R.id.error).setOnClickListener { onExit() }
        for (id in intArrayOf(R.id.player_root, tv.stream.playback.R.id.player_view, R.id.controls)) {
            findViewById<View>(id).setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) surface.requestFocus()
                false
            }
        }

    }

    private fun cancelPreparation() {
        work.cancel(); ranking = false; polling = false
        if (choiceDialog != null) { choiceDialog!!.dismiss(); choiceDialog = null }
    }
    private fun closeNative() {
        coordinator.close(); openingTorrentSource = null; nativeStats = JSONObject()
    }
    private fun resolve(resetAttempts: Boolean) {
        if (session == null || !visible) return
        clearErrorQr()
        save(session!!.snapshot()); session!!.stop(); cancelPreparation()
        if (resetAttempts) { coordinator.begin(if (startedIntent) SystemClock.elapsedRealtime() else intent.getLongExtra("playTapped", 0)); startedIntent = true; recoveryStarted = 0; refreshedSources.clear(); resetPayloads.clear(); cachedTorrentSource = null; if (!continuingPlayback) keepPausedOnRecovery = false; playback.resetAttempts()
            while (sourceFailures.length() > 0) sourceFailures.remove(0); sourceResolution = JSONObject() }
        waitingSources = true; switching = waitingSources; lastAdvanceMs = SystemClock.elapsedRealtime(); work.scope = services!!.addons.newRequest(); work.scope!!.priority(0)
        val mine = generation; val request = work.scope!!
        screen!!.setTitle(title!!.name); showLoading("sources", "Finding sources", null, false)
        work.task = loader.submit(Runnable {
            try {
                closeNative()
                val resolution = playback.resolveSources(work, services!!, title!!, video!!,
                        explicitKey, intent.getStringExtra("preparation"), resumePosition, request)
                discoveryVersion = -1
                request.check()
                main.post(Runnable {
                    if (!current(mine)) return@Runnable
                    title = resolution.title; screen!!.setTitle(title!!.name)
                    progress.watched = resolution.watched; updateWatchedButton(); coordinator.mark("FIRST_SOURCE")
                    resumePosition = resolution.resumePosition; playback.candidates = resolution.candidates
                    marks = resolution.marks.toMutableMap(); sourceResolution = JSONObject(resolution.diagnostics)
                    autoplay = resolution.autoplay; Sounds.get(this).setEnabled(resolution.soundsEnabled)
                    playback.automatic = explicitKey == null || explicitKey!!.isEmpty()
                    if (title!!.provider.equals("local")) { open(null, -1); return@Runnable }
                    if (playback.candidates.isEmpty()) { failure(resolution.unavailable!!.heading, resolution.unavailable.detail, "no-sources"); return@Runnable }
                    if (!playback.automatic) {
                        for (i in 0 until playback.candidates.size) if (SourcePolicy.key(playback.candidates.get(i)).equals(explicitKey)) { open(playback.candidates.get(i), -1); return@Runnable }
                        chooseSources(); return@Runnable
                    }
                    open(playback.candidates.get(0), -1)
                })
            } catch (e: Exception) { main.post { if (current(mine)) { failureCause = e; failure("Could not open this title", Failures.message(e), "provider") } } }
        })
    }
    private fun open(source: MediaSource?, chosenIndex: Int) {
        clearErrorQr()
        save(session!!.snapshot()); session!!.stop(); cancelPreparation()
        findViewById<View>(R.id.player_root).requestFocus()
        val mine = generation; work.scope = services!!.addons.newRequest(); work.scope!!.priority(0); val request = work.scope!!
        playback.selected = source; waitingSources = false; playing = null; attachedSubtitles.clear(); subtitleOffers = Collections.emptyList()
        val payloadReset = resettingPayload; resettingPayload = false
        if (chosenIndex < 0 && !payloadReset) { playback.attempt(source) }
        handledError = null; observedAudio = null; observedVideo = observedAudio; heardPlay = null; heardAt = 0
        switching = true; failureQueued = false; progress.startSource(); firstFrameMs = 0; droppedFrames = 0
        lastAdvanceMs = SystemClock.elapsedRealtime(); startMs = lastAdvanceMs; previousPosition = -1; startupWatchdog.start(startMs); coordinator.attempt(); demand.reset(); videoWatchdog.reset(startMs, 0, 0); nativeStats = JSONObject(); lastDemandUpdate = 0
        showLoading("preparing", "Opening source", sourceLabel(source), false)
        work.task = loader.submit(Runnable {
            try {
                // A chosen file reuses the metadata session; a changed source closes the previous one first.
                if (chosenIndex < 0 || payloadReset) coordinator.remove()
                val audio = services!!.store.preference("audioLanguage", "")
                var subtitles: String? = services!!.store.preference("subtitleLanguage", "")
                if (subtitles!!.isEmpty()) subtitles = if (services!!.playerPreferences.getBoolean("subtitles_on", false)) services!!.playerPreferences.getString("subtitle_language", "") else "off"
                val captions: MutableList<SubtitleDownloader.Source> = ArrayList()
                val subtitlesOff = subtitles!!.equals("off")
                // Caption providers and downloads are optional work after the video is submitted.
                val offers: List<SubtitleSource> = Collections.emptyList()
                val uri: Uri; var mediaMime: String? = null; var dataSource: androidx.media3.datasource.DataSource.Factory? = null
                if (source != null && source.kind == MediaSource.Kind.HTTP) {
                    val resolved = HttpMediaResolver.resolve(source.url!!, source.requestHeaders, request)
                    request.add(Runnable { resolved.close() }); uri = resolved.uri; mediaMime = resolved.mime; dataSource = resolved
                }
                else {
                    val options = RuntimeOptions(this, services!!.cacheBytes())
                    if (coordinator.engine == null) {
                        val payload = File(File(cacheDir, "payload"), java.util.UUID.randomUUID().toString())
                        val config = if (options.localOnly) TorrentEngine.Config.localOnly(payload.path) else TorrentEngine.Config(payload.path)
                        config.cacheBytes = options.cacheBytes; config.reserveBytes = if (options.localOnly) 0L else 512L shl 20
                        config.publicNetworkOnly = options.publicNetworkOnly
                        if (!options.localOnly && !options.publicNetworkOnly && PortMapping.enabled(services!!.store)) {
                            val gateway = PortMapping.gateway(this)
                            if (gateway != null) { config.natpmp = true; config.natpmpGateway = gateway }
                        }
                        val roots = File(noBackupFilesDir, "native-trust.pem"); TrustRoots.writePem(roots); config.trustRootsFile = roots.path
                        coordinator.engine = TorrentEngine.create(config)
                    }
                    if (coordinator.torrent == 0L) {
                        var torrentSource: TorrentEngine.Source
                        if (source == null) { torrentSource = AddonClient().use { client -> services!!.imports.open(title!!.id, client, request) } }
                        else torrentSource = source.torrentSource()
                        val identity = if (source == null) "import:" + title!!.id else SourcePolicy.key(source)
                        if (payloadReset && identity.equals(cachedTorrentKey) && cachedTorrentSource != null) torrentSource = cachedTorrentSource!!
                        if (options.localOnly) torrentSource = torrentSource.withTrackers().withPeers(options.peer)
                        else torrentSource = torrentSource.withFallbackTrackers(*MediaSource.PUBLIC_TRACKERS)
                        openingTorrentSource = torrentSource
                        coordinator.torrent = coordinator.engine!!.add(torrentSource)
                        // Real swarms need time to answer through trackers and DHT; loopback fixtures answer at once.
                        main.post { if (current(mine) && switching) showLoading("peers", "Finding peers", sourceLabel(source), false) }
                    }
                    val files = coordinator.engine!!.awaitMetadata(coordinator.torrent, if (options.localOnly) 20_000L else METADATA_MS)
                    main.post { if (current(mine)) coordinator.mark("TORRENT_METADATA") }
                    try { cachedTorrentSource = coordinator.engine!!.snapshotSource(coordinator.torrent,
                            openingTorrentSource!!)
                        cachedTorrentKey = if (source == null) "import:" + title!!.id else SourcePolicy.key(source)
                    } catch (ignored: Exception) { /* the original source remains a safe recovery path */ }
                    val explicit = if (chosenIndex >= 0) chosenIndex else if (source == null) services!!.store.importDescriptor(title!!.id)!!.optInt("fileIdx", -1) else source.fileIndex
                    var season = -1; var episode = -1; for (e in title!!.episodes) if (e.id.equals(video)) { season = e.season; episode = e.number }
                    val file = SourcePolicy.select(files, explicit, season, episode)
                    if (file == null) {
                        val videos: MutableList<TorrentFile> = ArrayList(); for (f in files) if (SourcePolicy.video(f)) videos.add(f)
                        if (videos.isEmpty()) throw TorrentException(TorrentException.INVALID_FILE, "No video files")
                        request.check(); main.post { if (current(mine)) chooseFile(source, videos) }; return@Runnable
                    }
                    request.check(); coordinator.engine!!.selectFile(coordinator.torrent, file.index)
                    coordinator.fileBytes = file.size; coordinator.fileIndex = file.index
                    coordinator.engine!!.updateDemand(coordinator.torrent, 1_250_000, 5)
                    if (source == null) services!!.imports.selectedFile(title!!.id, file.index)
                    uri = TorrentDataSource.uri(coordinator.torrent, file)
                    val activeEngine = coordinator.engine
                    val firstRead = java.util.concurrent.atomic.AtomicBoolean()
                    dataSource = androidx.media3.datasource.DataSource.Factory {
                        val reader = TorrentDataSource(activeEngine!!)
                        reader.addTransferListener(object : androidx.media3.datasource.TransferListener {
                            override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean) {}
                            override fun onTransferStart(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean) {}
                            override fun onTransferEnd(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean) {}
                            override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, network: Boolean, bytes: Int) {
                                if (bytes > 0 && firstRead.compareAndSet(false, true)) {
                                    val at = SystemClock.elapsedRealtime()
                                    main.post { if (current(mine)) coordinator.markAt("FIRST_VERIFIED_DATA", at) }
                                }
                            }
                        })
                        reader
                    }
                }
                val playback = PlaybackRequest(
                        uri = uri, title = title!!.name, id = video!!,
                        mediaIdentity = video + ":" + (if (source == null) "import:" + title!!.id else SourcePolicy.key(source)),
                        detail = episodeLabel(), positionMs = resumePosition,
                        startPaused = keepPausedOnRecovery,
                        continuing = continuingPlayback || payloadReset || this.playback.attempts > 1,
                        audioLanguage = audio.takeUnless { it.isEmpty() || it == "off" },
                        headers = source?.requestHeaders ?: emptyMap(), subtitleHeaders = emptyMap(),
                        subtitles = captions,
                        subtitleRequest = SubtitleTracks.Request(
                                if (subtitles == "off") "off" else null,
                                if (subtitles == "off") null else subtitles, null),
                        mimeType = mediaMime, dataSource = dataSource)
                request.check()
                main.post(Runnable {
                    if (!current(mine)) return@Runnable
                    playing = playback; subtitleOffers = offers
                    coordinator.stage(PlaybackCoordinator.State.BUFFERING)
                    switching = false; lastAdvanceMs = SystemClock.elapsedRealtime(); startupWatchdog.start(lastAdvanceMs)
                    if (continuingPlayback) session!!.setSpeed(retainedSpeed)
                    session!!.load(playback); preferredSubtitleRevision = session!!.subtitleSelectionRevision()
                    showLoading("video", "Preparing video", null, true)
                })
                if (source != null) {
                    val wanted = if (subtitlesOff) services!!.playerPreferences.getString("subtitle_language", "") else subtitles
                    app.workers.execute {
                        val later = subtitleOffers(source, wanted, request)
                        main.post { if (current(mine) && !later.isEmpty()) {
                            subtitleOffers = later; screen!!.refreshMenu()
                            if (!subtitlesOff) for (offer in later) if (SubtitleOffers.sameLanguage(offer.language, wanted)) { attachSubtitles(offer, false); break }
                        } }
                    }
                }
            } catch (e: Exception) {
                if (!current(mine)) return@Runnable
                if (e is TorrentException && e.kind == TorrentException.LOW_STORAGE) {
                    val cache = services!!.store.preference("cacheMiB", "256")
                    main.post { if (current(mine)) { failureCause = e; lowStorage(cache) } }
                    return@Runnable
                }
                main.post { if (current(mine)) { failureCause = e; attemptFailed(Failures.message(e), "opening") } }
            }
        })
    }
    /** Offers this app's own cleanup, or a smaller cache, then tries the same source again. */
    private fun lowStorage(size: String) {
        failure("Not enough storage", Failures.message(TorrentException(TorrentException.LOW_STORAGE, "")), "storage")
        val alert = Tv.alert(this, "Not enough storage", "Playback needs room for its cache plus 512 MiB free.\nClearing removes only temporary files; your library and history stay.")
        alert.button("clear", "Clear and retry") { alert.dialog.dismiss(); loader.execute {
            closeNative(); SetupScreens.clearCaches(this, services!!)
            main.post { if (visible && !releasing) onRetry() }
        } }
        if (!size.equals("128")) alert.button("smaller-cache", "Use a 128 MiB cache") { alert.dialog.dismiss(); app.persistence.execute {
            services!!.store.setPreference("cacheMiB", "128")
            main.post { if (visible && !releasing) onRetry() }
        } }
        choiceDialog = show(alert)
    }
    /** The source's own subtitles and the subtitle add-ons' for this video, without repeats, in {@code wanted} first. */
    private fun subtitleOffers(source: MediaSource, wanted: String?, request: Cancellation): List<SubtitleSource> {
        val advertised: MutableList<SubtitleSource> = ArrayList(source.subtitles)
        val hints: MutableMap<String, String> = LinkedHashMap()
        if (source.filename != null) hints["filename"] = source.filename!!
        if (source.videoHash != null) hints["videoHash"] = source.videoHash!!
        if (source.videoSize > 0) hints["videoSize"] = java.lang.Long.toString(source.videoSize)
        try { advertised.addAll(services!!.addons.subtitles(title!!.type, video!!, hints, request).items) } catch (ignored: Exception) { /* optional subtitles never stop video */ }
        return SubtitleOffers.rank(advertised, wanted)
    }
    /** Offers not already loaded as tracks of the current media. */
    private fun unloadedOffers(): List<SubtitleSource> {
        val media = playing ?: return Collections.emptyList()
        return SubtitleOffers.unloaded(subtitleOffers, media.subtitles.map { it.url }, attachedSubtitles) { session!!.hasExternalSubtitle(it) }
    }
    /**
     * The add-ons' subtitles not loaded yet, listed on the player's Subtitles page after the loaded tracks, so every
     * choice stays in one list whether or not a track is showing. Picking one adds it as a track and shows it.
     */
    override fun subtitleItems(): List<MenuPanel.Item> {
        val items: MutableList<MenuPanel.Item> = ArrayList()
        if (session == null || !session!!.isLoaded()) return items
        val offers = unloadedOffers()
        val names = SubtitleOffers.names(playing?.subtitles.orEmpty().map { it.language }, offers)
        for ((i, offer) in offers.withIndex())
            items.add(MenuPanel.Item.choice(names[i], "Download", false, Runnable { attachSubtitles(offer, true) }))
        return items
    }
    private val attachedSubtitles: MutableSet<String> = java.util.HashSet()
    private fun attachSubtitles(chosen: SubtitleSource, manual: Boolean) {
        if (playing == null || session == null || !visible || releasing || !attachedSubtitles.add(chosen.url)) return
        session!!.attachExternalSubtitles(Collections.singletonList(SubtitleDownloader.Source(chosen.url, SubtitleOffers.mime(chosen.url), chosen.language,
                SubtitleOffers.languageName(chosen.language))), Collections.emptyMap(), manual || session!!.subtitleSelectionRevision() == preferredSubtitleRevision,
                java.util.function.Consumer<Boolean> { loaded -> attachedSubtitles.remove(chosen.url) })
        if (manual) { if (playerMenus != null) playerMenus!!.close() else screen!!.menu().close() }
    }
    private fun episodeLabel(): String { for (e in title!!.episodes) if (e.id.equals(video)) return "S" + e.season + " E" + e.number + " · " + e.name; return "" }
    /** Only episode context belongs under the player's title. */
    private fun updateWatchingInfo() {
        val watching = title ?: return
        playback.watching(watching.name, if (watching.type == "series") episodeLabel() else "")
    }
    private fun chooseFile(source: MediaSource?, files: List<TorrentFile>) {
        val names = Array(files.size) { i -> files.get(i).name() + " · " + files.get(i).size / (1 shl 20) + " MiB" }
        choiceDialog = show(Tv.choices(this, "Choose a video file", names, java.util.function.Consumer<Int> { which -> open(source, files.get(which).index) }, Runnable { finish() }))
    }
    private fun populateSources(page: Tv.Alert, remember: Boolean) {
        val offered: List<MediaSource> = ArrayList(playback.candidates)
        for (i in 0 until offered.size) {
            val source = offered.get(i)
            val facts = SourceSort.facts(source)
            var detail = facts.quality()
            val release = SourceSort.releaseType(source)
            if (!release.isEmpty()) detail += (if (detail.isEmpty()) "" else " · ") + release
            if (facts.bytes > 0) detail += (if (detail.isEmpty()) "" else " · ") + TitleScreens.size(facts.bytes)
            if (facts.seeds >= 0) detail += (if (detail.isEmpty()) "" else " · ") + facts.seeds + " peers"
            page.sourceChoice("source:" + i, source.name.replace('\n', ' '), detail, source === playback.selected, Runnable {
                if (remember) rememberViewerState(session!!.snapshot())
                page.dialog.dismiss(); playback.automatic = false; explicitKey = SourcePolicy.key(source)
                playback.resetAttempts(); recoveryStarted = 0; coordinator.begin(SystemClock.elapsedRealtime()); open(source, -1)
            })
        }
        page.scrollChoices(276)
    }
    private fun chooseSources() {
        if (session == null) return
        val previous = session!!.snapshot()
        rememberViewerState(previous); save(previous); session!!.pause()
        if (playback.candidates.isEmpty()) { failure("No sources available", "Refresh sources on the title page.", "no-sources"); return }
        val page = Tv.alert(this, "Choose source", null as View?); populateSources(page, false)
        page.dialog.setOnCancelListener { d ->
            if (session == null || releasing) return@setOnCancelListener
            if (previous.started || previous.error != null) { if (previous.playWhenReady && previous.error == null) session!!.play(); screen!!.showControls() }
            else finish()
        }
        choiceDialog = show(page)
    }
    private fun attemptFailed(message: String, code: String) {
        if (!visible || releasing) return
        val state = if (session == null) null else session!!.snapshot()
        if (state != null && state.renderedFirstFrame && (recoveryStarted == 0L || SystemClock.elapsedRealtime() - recoveryStarted >= 60_000)) {
            recoveryStarted = SystemClock.elapsedRealtime(); coordinator.recover(recoveryStarted); playback.resetAttempts(); playback.attempt(playback.selected)
        }
        PlaybackOutcomes.record(playback.selected, false)
        try {
            val failed = JSONObject().put("attempt", playback.attempts).put("code", code)
                    .put("message", message).put("stage", diagnosticStage)
                    .put("renderedFirstFrame", state != null && state.renderedFirstFrame)
                    .put("positionMs", if (state == null) 0L else state.positionMs)
            if (playback.selected != null) failed.put("transport", playback.selected!!.kind.name)
            if (observedVideo != null) failed.put("videoMime", observedVideo!!.sampleMimeType)
                    .put("width", observedVideo!!.width).put("height", observedVideo!!.height).put("codecs", observedVideo!!.codecs)
            if (observedAudio != null) failed.put("audioMime", observedAudio!!.sampleMimeType)
            if (state != null && state.error != null) failed.put("playerCode", state.error!!.code).put("detail", state.error!!.detail).put("httpStatus", state.error!!.httpStatus)
            if (developerMode) failed.put("causes", DiagnosticReport.exception(if (failureCause != null) failureCause else session!!.player().playerError))
            sourceFailures.put(failed)
        } catch (ignored: Exception) { /* diagnostic fields never prevent recovery */ }
        if (state == null || !state.renderedFirstFrame) markSource(TvStore.SourceMark.FAILED)
        var httpStatus = if (state != null && state.error != null) state.error!!.httpStatus else 0
        var causeDepth = 0
        var cause = failureCause
        while (cause != null && causeDepth++ < 12) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) { httpStatus = cause.responseCode; break }
            cause = cause.cause
        }
        if (playback.selected != null && playback.selected!!.kind == MediaSource.Kind.HTTP
                && (httpStatus == 401 || httpStatus == 403 || httpStatus == 410)
                && refreshedSources.add(SourcePolicy.key(playback.selected!!)) && !coordinator.expired(SystemClock.elapsedRealtime())) {
            if (state != null && state.positionMs > 0) resumePosition = state.positionMs; keepPausedOnRecovery = state != null && state.loaded && !state.playWhenReady
            resolve(false); return
        }
        if (playback.automatic && playback.attempts < 3 && !coordinator.expired(SystemClock.elapsedRealtime())) {
            val next = playback.nextCandidate()
            if (next >= 0) {
                if (state != null && state.positionMs > 0) resumePosition = state.positionMs
                keepPausedOnRecovery = state != null && state.loaded && !state.playWhenReady
                val mine = generation; val chosen = next
                // Claim recovery before another player callback can queue a second attempt.
                switching = true
                session!!.stop()
                findViewById<View>(R.id.player_root).requestFocus()
                showLoading("sources", "Trying another source", sourceLabel(playback.candidates.get(next)), false)
                main.post { if (current(mine)) open(playback.candidates.get(chosen), -1) }; return
            }
        }
        if (playback.automatic && playback.attempts < 3 && work.discovery != null && !work.discovery!!.scope.isCancelled()
                && !(work.discovery!!.complete && work.discovery!!.metadataReady) && !coordinator.expired(SystemClock.elapsedRealtime())) {
            if (state != null && state.loaded) { resumePosition = state.positionMs; keepPausedOnRecovery = !state.playWhenReady }
            awaitAlternative(message, code); return
        }
        failure(if (playback.automatic) "No playable source found" else "Could not play this source", message, code)
    }
    private fun awaitAlternative(message: String, code: String) {
        waitingSources = true; switching = waitingSources
        session!!.stop(); cancelPreparation()
        val mine = generation; val entry = work.discovery
        work.scope = services!!.addons.newRequest(); work.scope!!.priority(0); val request = work.scope!!
        val attempted: MutableSet<String> = java.util.HashSet(playback.attemptedSources); val providers: MutableSet<String> = java.util.HashSet(playback.attemptedProviders)
        val deadline = SystemClock.elapsedRealtime() + coordinator.remaining(SystemClock.elapsedRealtime())
        showLoading("sources", "Finding another source", null, false)
        work.task = loader.submit(Runnable {
            try {
                coordinator.remove()
                val available = entry!!.awaitAlternative(request, attempted, providers, deadline)
                request.check()
                main.post { if (current(mine)) {
                    waitingSources = false; playback.candidates = available
                    val next = SourcePolicy.nextCandidate(available, playback.attemptedSources, playback.attemptedProviders)
                    if (next >= 0 && !coordinator.expired(SystemClock.elapsedRealtime())) { open(available.get(next), -1) }
                    else failure("No playable source found", message, code)
                } }
            } catch (error: Exception) { main.post { if (current(mine)) failure("No playable source found", Failures.message(error), code) } }
        })
    }
    /** Remembers how the current source went, for the marks on Sources. Imports have no source list. */
    private fun markSource(state: String) {
        if (playback.selected == null || title == null || services == null) return
        val titleKey = title!!.key(); val key = SourcePolicy.key(playback.selected!!); val forVideo = video
        val before = marks[key]
        if (before == null || !before.state.equals(TvStore.SourceMark.PLAYED)) marks[key] = TvStore.SourceMark(state, System.currentTimeMillis())
        app.persistence.execute { services!!.store.markSource(titleKey, forVideo!!, key, state) }
    }
    private fun failure(heading: String, message: String, code: String) {
        Sounds.play(Sounds.Sound.WARNING)
        waitingSources = false; switching = waitingSources
        if (screen == null || session == null) return
        // Freeze before fail/stop/closeNative replace the original Media3 error and coordinator.torrent counters.
        if (developerMode) {
            val state = diagnosticSnapshot()
            try { if (session!!.error() != null) state.put("playerError", session!!.error()!!.toJson()) }
            catch (ignored: org.json.JSONException) { /* the remaining report is still useful */ }
            app.lastErrorReport = DiagnosticReport.capture(this, diagnosticStage, heading, message, code,
                    if (failureCause != null) failureCause else session!!.player().playerError, state)
        }
        playerLayers?.diagnostics(if (developerMode) app.lastErrorReport else null)
        handledError = PlaybackErrors.Description(code, heading, message, "", 0, true)
        session!!.fail(handledError)
        cancelPreparation(); loader.execute { closeNative() }
        playback.failed(handledError!!)
    }
    private fun clearErrorQr() {
        playerLayers?.diagnostics(null)
        failureCause = null
    }
    private fun queueFailure(message: String, code: String) {
        if (failureQueued || releasing || switching) return
        failureQueued = true; val mine = generation
        main.post { if (current(mine)) attemptFailed(message, code) }
    }
    override fun onUnsupportedFormat(format: String) { queueFailure("This TV can't decode " + format + ".", "unsupported") }
    override fun onPlaybackChanged(snapshot: PlaybackSnapshot) {
        if (releasing || session == null || switching) return
        if (snapshot.started && snapshot.error == null) playback.ready()
        // Media-session play commands must not start sound behind the screensaver or lock screen.
        if (!visible) { if (snapshot.playWhenReady) session!!.pause(); return }
        updateWatchingInfo()
        if (playerMenus != null) playerMenus!!.changed()
        if (keepPausedOnRecovery && snapshot.error == null) {
            if (snapshot.playWhenReady) { session!!.pause(); return }
            if (snapshot.started) keepPausedOnRecovery = false
        }
        if (snapshot.videoFormat != null) observedVideo = snapshot.videoFormat
        if (snapshot.audioFormat != null) observedAudio = snapshot.audioFormat
        if (snapshot.renderedFirstFrame && firstFrameMs == 0L) { diagnosticStage = "Playing video"; firstFrameMs = SystemClock.elapsedRealtime() - startMs; coordinator.stage(PlaybackCoordinator.State.PLAYING); PlaybackOutcomes.record(playback.selected, true); markSource(TvStore.SourceMark.PLAYED) }
        // The viewer's pause and play, heard as small sounds; never the first state a source starts in.
        if (snapshot.started && snapshot.error == null) {
            retainedSpeed = snapshot.speed
            if (heardPlay != null && heardPlay != snapshot.playWhenReady) Sounds.play(if (snapshot.playWhenReady) Sounds.Sound.RESUME else Sounds.Sound.PAUSE)
            heardPlay = snapshot.playWhenReady
        }
        if (snapshot.error != null) {
            // Compare the actual host-owned description, not strings shared with Media3's error vocabulary.
            if (snapshot.error !== handledError) queueFailure(playbackFailure(snapshot.error!!), "opening")
            return
        }
        if (!SourcePolicy.withinDeviceProfile(snapshot.videoFormat)) {
            onUnsupportedFormat("the supplied media format"); return
        }
        if (progress.tracksChanged(PlaybackProgress.Moment.of(snapshot))) save(snapshot)
        val now = SystemClock.elapsedRealtime()
        if (snapshot.buffering && bufferingStarted == 0L) bufferingStarted = now
        if (!snapshot.buffering && bufferingStarted != 0L) { bufferingMs += now - bufferingStarted; bufferingStarted = 0 }
    }
    private fun playbackFailure(error: PlaybackErrors.Description): String {
        var cause: Throwable? = session!!.player().playerError
        var depth = 0
        while (cause != null && depth < 12) {
            if (cause is TorrentException) return Failures.message(cause)
            depth++; cause = cause.cause
        }
        if (error.code.equals("decoder")) return "This TV can't decode this source's format."
        if (error.code.equals("format")) return "No readable video in this source."
        return error.message
    }
    override fun onProgress(snapshot: PlaybackSnapshot) { if (visible && !releasing) { hearSeek(snapshot); save(snapshot) } }
    /** A skip, however it was made (remote, timeline, menu), sounds by its direction: the position jumped from where time had it. */
    private fun hearSeek(snapshot: PlaybackSnapshot) {
        val now = SystemClock.elapsedRealtime()
        if (!switching && snapshot.started && heardAt != 0L) {
            val expected = heardPosition + (if (snapshot.playing) ((now - heardAt) * snapshot.speed).toLong() else 0L)
            val jump = snapshot.positionMs - expected
            if (jump > 4000) Sounds.play(Sounds.Sound.SEEK_FORWARD) else if (jump < -4000) Sounds.play(Sounds.Sound.SEEK_BACK)
        }
        heardAt = now; heardPosition = snapshot.positionMs
    }
    override fun onMenuToggled(open: Boolean) {
        if (playerMenus == null || !playerMenus!!.isOpen()) Sounds.play(if (open) Sounds.Sound.MENU_OPEN else Sounds.Sound.MENU_CLOSE)
        if (!open && screen != null && session != null && !session!!.player().playWhenReady) screen!!.showControls()
        updatePlayerClock()
        if (session != null) updateNextEpisode(session!!.snapshot())
    }
    private fun save(snapshot: PlaybackSnapshot?) {
        if (services == null || title == null || snapshot == null) return
        if (progress.save(title!!, video!!, PlaybackProgress.Moment.of(snapshot))) updateWatchedButton()
    }
    override fun onEnded() {
        if (!visible || releasing || screen == null) return
        hideNextEpisode()
        val next = if (autoplay && !episodes.dismissed) neighbour(1) else null
        Sounds.play(if (next != null) Sounds.Sound.UP_NEXT else Sounds.Sound.FINISHED)
        if (next != null) {
            screen!!.offerUpNext(next.name, "Season " + next.season, Runnable { episode(next) })
            playerLayers?.request(R.id.up_next_play)
        }
    }
    private fun updateNextEpisode(state: PlaybackSnapshot) {
        episodes.playing(video!!)
        val next = neighbour(1)
        val show = visible && !releasing && !switching && state.started && state.error == null
                && !state.ended && screen!!.loading() == null && !screen!!.isMenuOpen() && (playerMenus == null || !playerMenus!!.isOpen()) && !screen!!.hasUpNext()
                && next != null && !episodes.dismissed && NextEpisodeOffer.nearEnd(state.positionMs, state.durationMs)
        val offer = findViewById<View>(R.id.next_episode_offer)
        if (show == (offer.visibility == View.VISIBLE)) return
        offer.animate().cancel()
        if (show) {
            offer.alpha = 0f; offer.translationY = Tv.dp(this, 12f).toFloat(); offer.visibility = View.VISIBLE
            offer.animate().alpha(1f).translationY(0f).setDuration(240).start()
        } else hideNextEpisode()
    }
    private fun hideNextEpisode() {
        val offer = findViewById<View>(R.id.next_episode_offer)
        if (offer == null) return
        if (offer.hasFocus()) { findViewById<View>(R.id.player_root).requestFocus(); if (screen != null) screen!!.showControls() }
        offer.animate().cancel(); offer.visibility = View.GONE
    }
    private fun neighbour(offset: Int): Title.Episode? = episodes.neighbour(title, video, offset)
    private fun episode(next: Title.Episode?) {
        rememberViewerState(session!!.snapshot()); save(session!!.snapshot()); session!!.stop(); keepPausedOnRecovery = false; video = next!!.id; progress.startEpisode(); resumePosition = 0; explicitKey = ""; resolve(true)
    }
    override fun onNextEpisode(): Boolean { val next = neighbour(1); if (next == null) return false; episode(next); return true }
    override fun onPreviousEpisode(): Boolean { val previous = neighbour(-1); if (previous == null) return false; episode(previous); return true }
    override fun canRetry(): Boolean = true
    override fun onRetry() { if (session != null && session!!.snapshot().positionMs > 0) resumePosition = session!!.snapshot().positionMs; resolve(true) }
    override fun canChooseSource(): Boolean = title != null && !title!!.provider.equals("local")
    override fun onChooseSource() { chooseSources() }
    private fun updateWatchedButton() { playback.watched(progress.watched) }
    private fun setWatched(watched: Boolean) {
        if (session == null || services == null || title == null) return
        if (!progress.mark(title!!, video!!, PlaybackProgress.Moment.of(session!!.snapshot()), watched)) return
        updateWatchedButton()
        Sounds.play(if (watched) Sounds.Sound.WATCHED_ON else Sounds.Sound.WATCHED_OFF)
    }
    override fun onExit() {
        if (exitChoiceOpen) return
        hideNextEpisode()
        val state = if (session == null) null else session!!.snapshot()
        if (state == null || !state.started || state.error != null || state.ended || session!!.player().isCurrentMediaItemLive
                || !WatchCompletion.offerOnExit(state.positionMs, state.durationMs, progress.watched)) { finish(); return }
        exitChoiceOpen = true
        val resume = state.playWhenReady
        session!!.pause()
        val alert = Tv.alert(this, "Finished watching?", "Mark this " + (if (title!!.type.equals("series")) "episode" else "film") + " watched, or keep your place for later.")
        // Keeping the place is the default; time remaining alone never marks an episode watched.
        alert.button("keep-progress", "Keep for later") { alert.dialog.dismiss(); finish() }
        alert.button("mark-watched", "Mark watched") { setWatched(true); alert.dialog.dismiss(); finish() }
        alert.dialog.setOnCancelListener { dialog ->
            exitChoiceOpen = false
            if (resume && session != null && !releasing) session!!.play()
        }
        choiceDialog = show(alert)
    }
    /** Route the app's remote focus graph before shared transport shortcuts, including error alert actions. */
    override fun onKey(code: Int): Boolean {
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        val focused = currentFocus
        if (session == null) return false
        if (session!!.error() != null) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { onExit(); return true }
            if (ok && focused != null && (focused.id == R.id.error_qr_previous || focused.id == R.id.error_qr_next)) {
                focused.performClick(); return true
            }
            val target = playerLayers?.focusedId() ?: View.NO_ID
            if (ok && target in ERROR_BUTTONS) { playerLayers?.activate(target); return true }
            return false
        }
        if (switching || !session!!.started() || screen!!.isMenuOpen()) return false
        val focus = playerControls?.focusedId()?.takeIf { it != View.NO_ID }
            ?: playerLayers?.focusedId()?.takeIf { it != View.NO_ID } ?: focused?.id ?: View.NO_ID
        val header = headerIndex(focus) >= 0
        if (code == KeyEvent.KEYCODE_BACK && (header || focus == R.id.progress))
            findViewById<View>(R.id.player_root).requestFocus()
        if ((ok && !header) || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) keepPausedOnRecovery = false
        if (findViewById<View>(R.id.next_episode_offer).isShown) {
            if (focus == R.id.next_episode_play) {
                if (ok) { playerLayers?.activate(R.id.next_episode_play); return true }
                if (code == KeyEvent.KEYCODE_DPAD_UP) { screen!!.showControls(); playerControls?.request(R.id.progress); return true }
                if (code == KeyEvent.KEYCODE_BACK) { episodes.dismiss(); hideNextEpisode(); return true }
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                playerLayers?.request(R.id.next_episode_play); return true
            }
        }
        if (ok && header) { playerControls?.activate(focus); return true }
        if (header && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            screen!!.showControls()
            val next = Math.max(0, Math.min(HEADER.size - 1, headerIndex(focus) + (if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)))
            playerControls?.request(HEADER[next])
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            screen!!.showControls()
            val target = if (code == KeyEvent.KEYCODE_DPAD_UP) R.id.control_watched else R.id.progress
            playerControls?.request(target)
            return true
        }
        return false
    }
    /** An error alert opens with its first button focused, so the remote is on it at once. */
    override fun onScreenChanged() {
        updatePlayerClock()
        val error = findViewById<View>(R.id.error)
        if (session == null || session!!.error() == null || !error.isShown || error.hasFocus()) return
        playerLayers?.focusError()
    }
    private var clockTargetVisible = false
    /** The clock accompanies active controls as well as paused playback and menus. */
    private fun updatePlayerClock() {
        val clock = findViewById<View>(R.id.player_clock)
        if (clock == null) return
        val shown = screen != null && session != null && session!!.started() && screen!!.loading() == null
                && session!!.error() == null && (screen!!.controlsVisible()
                || screen!!.isMenuOpen() || playerMenus != null && playerMenus!!.isOpen()
                || !session!!.player().playWhenReady && session!!.player().playbackState == Player.STATE_READY)
        val visible = playerClockEnabled && shown && !playerClockFormat.equals("off")
        if (visible) {
            if (clock.visibility != View.VISIBLE) clock.alpha = 0f
            tv.stream.app.ui.Clock.apply(clock as android.widget.TextClock, playerClockFormat, true)
        }
        if (clockTargetVisible == visible) return
        clockTargetVisible = visible
        tv.stream.playback.Ui.fade(clock, visible)
        clock.post { updatePlayerHeaderInset() }
    }
    private fun updatePlayerHeaderInset() {
        val clock = findViewById<View>(R.id.player_clock)
        val inset = Tv.dp(this, 48f) + (if (clock.isShown) clock.width + Tv.dp(this, 16f) else 0)
        playerControls?.headerInset(inset)
    }
    override fun menuItems(leading: Boolean): List<MenuPanel.Item> {
        val result: MutableList<MenuPanel.Item> = ArrayList()
        if (leading) {
            if (canChooseSource()) result.add(MenuPanel.Item.action("Choose source", null, Runnable { chooseSources() }))
            if (neighbour(-1) != null) result.add(MenuPanel.Item.action("Previous episode", null, Runnable { onPreviousEpisode() }))
            if (neighbour(1) != null) result.add(MenuPanel.Item.action("Next episode", null, Runnable { onNextEpisode() }))
        } else if (developerMode) result.add(MenuPanel.Item.action("Developer", "Playback diagnostics", Runnable { diagnostics() }))
        return result
    }
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!visible || releasing || session == null) return
            val state = session!!.snapshot(); val now = SystemClock.elapsedRealtime()
            updateNextEpisode(state)
            if (state.renderedFirstFrame && state.playing && state.positionMs > 1000 && coordinator.firstAvMs(state.audioFormat != null) > 0) coordinator.mark("STABLE_PLAYBACK")
            if (state.positionMs > previousPosition + 50 && state.playing) { lastAdvanceMs = now; previousPosition = state.positionMs; if (seekStarted > 0) { seekRecoveryMs = now - seekStarted; seekStarted = 0 } }
            if (!state.buffering && !switching && !state.playWhenReady) lastAdvanceMs = now
            checkVideoFrames(state, now)
            if (!ranking && work.discovery != null && !work.discovery!!.scope.isCancelled() && discoveryVersion != work.discovery!!.version()) {
                ranking = true; val entry = work.discovery!!; val version = entry.version(); val mine = generation
                app.workers.execute {
                    val discovered = SourcePolicy.ranked(entry.sources())
                    main.post { if (current(mine) && work.discovery === entry) {
                        ranking = false; discoveryVersion = version; if (!discovered.isEmpty()) playback.candidates = discovered
                    } }
                }
            }
            coordinator.paused(state.loaded && !switching && !state.playWhenReady, now)
            val startup = !state.renderedFirstFrame
            val alternative = playback.automatic && playback.attempts < 3 && playback.nextCandidate() >= 0
            startupWatchdog.setPaused(state.loaded && !state.playWhenReady, now)
            startupWatchdog.observe(nativeStats.optLong("verifiedReadBytes"), now)
            val quiet = startupWatchdog.quietMs(now); val elapsed = coordinator.attemptElapsed(now)
            val stalledStage = !waitingSources && alternative && (elapsed >= 25_000
                    || coordinator.torrent != 0L && nativeStats.optInt("peers") == 0 && elapsed >= 8000
                    || "metadata".equals(nativeStats.optString("state")) && elapsed >= 12_000)
            val pressure = coordinator.torrent != 0L && nativeStats.optInt("urgentReservationFailures") > 0
                    && nativeStats.optInt("obsoletePartials") > 0 && quiet >= 6000
            if ((choiceDialog == null || !choiceDialog!!.isShowing) && state.error == null) {
                val identity = if (playback.selected == null) "import:" + title!!.id else SourcePolicy.key(playback.selected!!)
                if (pressure && resetPayloads.add(identity)) {
                    if (state.renderedFirstFrame) coordinator.recover(now)
                    resumePosition = state.positionMs; keepPausedOnRecovery = !state.playWhenReady
                    val selectedIndex = coordinator.fileIndex
                    resettingPayload = true; open(playback.selected, selectedIndex)
                } else if (startup && (coordinator.expired(now) || stalledStage)
                        || !switching && StartupWatchdog.bufferingStalled(state.buffering && state.playWhenReady, bufferingStarted, quiet, now, if (alternative) 6000L else 30_000L)) {
                    val message = if (coordinator.torrent == 0L) "The source stopped responding."
                            else if (nativeStats.optLong("hashFailures") > 0) "Downloaded data failed its checks."
                            else if (nativeStats.optInt("peers") == 0) "No peers are sharing this video." else "Video data is arriving too slowly."
                    attemptFailed(message, "buffering")
                }
            }
            if (!polling && coordinator.engine != null && coordinator.torrent != 0L) {
                polling = true; val mine = generation
                val active = coordinator.engine; val handle = coordinator.torrent
                val bytesPerSecond = demand.estimate(coordinator.fileBytes, state.durationMs, nativeStats.optLong("verifiedReadBytes"), state.positionMs, now)
                val ahead = PlaybackDemand.aheadSeconds(state.renderedFirstFrame, state.bufferedMs - state.positionMs)
                val update = !switching && now - lastDemandUpdate >= 5000; if (update) lastDemandUpdate = now
                app.playbackTelemetry.execute {
                    var stats: JSONObject? = null
                    try { if (handle != 0L) {
                        if (update) active!!.updateDemand(handle, (bytesPerSecond * state.speed).toLong(), ahead)
                        stats = active!!.stats(handle)
                    } } catch (ignored: Exception) { /* source is closing */ }
                    val value = stats; main.post { if (current(mine)) { polling = false; if (value != null) nativeStats = value } }
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    /** Renderer state belongs to the playback thread; report one consistent sample back to the UI. */
    private fun checkVideoFrames(state: PlaybackSnapshot, now: Long) {
        val player = session!!.player()
        val counters = player.videoDecoderCounters
        if (counters == null) return
        val videos: MutableList<androidx.media3.exoplayer.Renderer> = ArrayList()
        for (i in 0 until player.rendererCount) {
            if (player.getRendererType(i) != C.TRACK_TYPE_VIDEO) continue
            videos.add(player.getRenderer(i))
            val secondary = player.getSecondaryRenderer(i)
            if (secondary != null) videos.add(secondary)
        }
        val mine = generation
        val advancing = state.playing && state.bufferedMs - state.positionMs >= 2000 && !switching
        player.createMessage { type, payload ->
            var ended = true
            for (renderer in videos)
                if (renderer.state != androidx.media3.exoplayer.Renderer.STATE_DISABLED && !renderer.isEnded) ended = false
            counters.ensureUpdated()
            val rendered = counters.renderedOutputBufferCount; val dropped = counters.droppedBufferCount
            val videoEnded = ended
            main.post(Runnable {
                if (!current(mine) || session == null || session!!.player() !== player) return@Runnable
                if (videoWatchdog.unhealthy(now, rendered.toLong(), dropped.toLong(), advancing, videoEnded))
                    attemptFailed("This video is too demanding for the TV to play smoothly.", "video-performance")
            })
        }.send()
    }
    fun diagnosticSnapshot(): JSONObject {
        val result = JSONObject()
        try {
            result.put("playTapToFirstAvMs", coordinator.firstAvMs(observedAudio != null))
            result.put("startupStagesMs", coordinator.diagnostics()).put("playTapToNowMs", coordinator.elapsed(SystemClock.elapsedRealtime()))
            result.put("sourceAttempts", playback.attempts).put("sourceFailures", JSONArray(sourceFailures.toString())).put("firstFrameMs", firstFrameMs).put("seekRecoveryMs", seekRecoveryMs).put("droppedFrames", droppedFrames)
                    .put("videoDecoder", videoDecoder).put("audioDecoder", audioDecoder)
                    .put("bufferingMs", bufferingMs).put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
                    .put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            result.put("automatic", playback.automatic).put("candidateCount", playback.candidates.size).put("stage", diagnosticStage)
            result.put("sourceResolution", sourceResolution).put("elapsedSourceMs", if (startMs == 0L) 0L else SystemClock.elapsedRealtime() - startMs)
            if (playback.selected != null) result.put("transport", playback.selected!!.kind.name)
            if (observedVideo != null) result.put("videoMime", observedVideo!!.sampleMimeType).put("codecs", observedVideo!!.codecs)
                    .put("width", observedVideo!!.width).put("height", observedVideo!!.height)
            if (observedAudio != null) result.put("audioMime", observedAudio!!.sampleMimeType)
            if (session != null) { val s = session!!.snapshot(); result.put("positionMs", s.positionMs).put("durationMs", s.durationMs).put("renderedFirstFrame", s.renderedFirstFrame).put("playing", s.playing) }
            for (key in arrayOf("verifiedReadBytes", "urgentReservationFailures", "obsoletePartials", "peers", "seeds", "totalDownload", "downloadRate", "hashFailures", "evictionsRequested", "cache", "disk")) if (nativeStats.has(key)) result.put(key, nativeStats.get(key))
        } catch (ignored: Exception) { /* diagnostics remain best effort */ }
        return result
    }
    fun playbackSnapshot(): PlaybackSnapshot? = if (session == null) null else session!!.snapshot()
    private fun diagnostics() {
        val alert = Tv.alert(this, "Playback diagnostics", diagnosticSnapshot().toString().replace(",\"", ", \""))
        choiceDialog = show(alert)
    }
    /** Actual input extends the shared four-second timeout; retained focus alone must not keep controls up. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        // Back isn't interaction that keeps the controls up: its key-down would re-show controls that the previous Back
        // is still fading out, and its key-up would only hide them again, so a quick second Back could never leave.
        if (dispatchingKey != KeyEvent.KEYCODE_BACK && canShowControls() && findViewById<View>(R.id.controls).isShown) screen!!.showControls()
    }
    /** The key being dispatched, for [onUserInteraction]; zero for touches and pointers. */
    private var dispatchingKey = 0
    private fun canShowControls(): Boolean {
        return screen != null && session != null && session!!.started() && session!!.error() == null
                && screen!!.loading() == null && !screen!!.isMenuOpen() && !screen!!.hasUpNext()
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        // Android delivers the first mouse movement into this window as HOVER_ENTER.
        if ((action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE)
                && canShowControls()) screen!!.showControls()
        return super.dispatchGenericMotionEvent(event)
    }
    /** The player's controls and menu belong to the shared player: an arrow that moves its focus ticks here. */
    // AndroidX core restricts its inherited override; TV remote routing still uses the framework dispatch contract.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        dispatchingKey = event.keyCode
        try { return dispatchKey(event) } finally { dispatchingKey = 0 }
    }
    @android.annotation.SuppressLint("RestrictedApi") // Inherited framework dispatch, as above.
    private fun dispatchKey(event: KeyEvent): Boolean {
        // Shared remote handling consumes keys before Activity can report this interaction itself.
        if (event.action == KeyEvent.ACTION_DOWN) onUserInteraction()
        val move = event.action == KeyEvent.ACTION_DOWN && Sounds.arrow(event.keyCode)
        if (move) Sounds.input()
        if (screen != null && screen!!.hasUpNext()) {
            val code = event.keyCode
            val select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (select || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (select) playerLayers?.activate(if (playerLayers?.focusedId() == R.id.up_next_cancel) R.id.up_next_cancel else R.id.up_next_play)
                    else playerLayers?.request(if (code == KeyEvent.KEYCODE_DPAD_DOWN) R.id.up_next_cancel else R.id.up_next_play)
                }
                return true
            }
        }
        if (playerMenus != null && (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_CAPTIONS)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playerMenus!!.open(false, playerControls!!.origin(R.id.control_settings))
            return true
        }
        val before = currentFocus
        val handled = screen != null && screen!!.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        val after = currentFocus
        if (move && after != null && after !== before) Sounds.focus(after)
        return handled
    }
    private fun exitOrCloseOverlay() { if (screen != null && screen!!.onRemoteKey(KeyEvent.KEYCODE_BACK)) return; Sounds.play(Sounds.Sound.BACK); onExit() }
    override fun onStop() {
        visible = false
        var state = if (session == null) null else session!!.snapshot()
        if (!isFinishing && !isChangingConfigurations && !switching && state != null
                && state.loaded && state.started && state.error == null) {
            retainedWhileStopped = true
            session!!.pause()
            state = session!!.snapshot(); rememberViewerState(state); save(state)
            val now = SystemClock.elapsedRealtime()
            coordinator.paused(true, now); startupWatchdog.setPaused(true, now)
            // A completed episode must not start another episode while the viewer is away.
            hideNextEpisode(); if (playerMenus != null) playerMenus!!.close(); screen!!.cancelUpNext(); screen!!.menu().close()
            if (choiceDialog != null) { choiceDialog!!.dismiss(); choiceDialog = null }
            exitChoiceOpen = false
            main.removeCallbacks(tick)
        } else releasePlayback()
        super.onStop()
    }
    override fun onDestroy() {
        // Also covers a stopped activity being finished or destroyed instead of brought back to the foreground.
        releasePlayback()
        super.onDestroy()
    }
    private fun releasePlayback() {
        if (releasing) return
        releasing = true; retainedWhileStopped = false
        if (session != null) { val state = session!!.snapshot(); rememberViewerState(state); save(state) }
        cancelPreparation(); work.close(); playback.released(); main.removeCallbacksAndMessages(null)
        if (playerMenus != null) { playerMenus!!.close(); playerMenus = null }
        if (screen != null) screen!!.release()
        if (session != null) session!!.release()
        screen = null; session = null
        loader.execute { closeNative() }
    }
    private fun rememberViewerState(state: PlaybackSnapshot) {
        if (!state.loaded) return
        resumePosition = state.positionMs; retainedSpeed = state.speed
        keepPausedOnRecovery = !state.playWhenReady; continuingPlayback = true
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("video", video); out.putLong("position", if (session == null) resumePosition else session!!.snapshot().positionMs)
        out.putFloat("speed", if (session == null) retainedSpeed else session!!.snapshot().speed)
        out.putBoolean("paused", if (session == null) keepPausedOnRecovery else !session!!.snapshot().playWhenReady) }
}
