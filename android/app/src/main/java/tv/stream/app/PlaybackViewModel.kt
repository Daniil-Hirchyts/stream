package tv.stream.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.MediaSource
import tv.stream.app.addon.Title
import tv.stream.app.data.TvStore
import tv.stream.playback.PlaybackErrors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** The player screen's presentation. Native/player objects never enter retained screen state. */
sealed interface PlaybackPresentation {
    data object Idle : PlaybackPresentation
    data class Loading(val id: String, val step: String, val detail: String?, val untilReady: Boolean) : PlaybackPresentation
    data object Ready : PlaybackPresentation
    data class Failed(val error: PlaybackErrors.Description) : PlaybackPresentation
    data object Released : PlaybackPresentation
}

/** In-memory choices only: stream addresses are never part of a saved-state bundle or preference. */
data class PlaybackSelection(
    val candidates: List<MediaSource> = emptyList(),
    val selected: MediaSource? = null,
    val automatic: Boolean = false,
    val attempts: Int = 0,
    val attemptedSources: Set<String> = emptySet(),
    val attemptedProviders: Set<String> = emptySet(),
)

/** The app-owned control labels and watched action; transport/timeline state remains with Media3. */
data class PlayerControlsState(val title: String = "", val detail: String = "", val watched: Boolean = false)

/** Only provider identity and descriptive metadata cross the activity initialization boundary. */
data class PlaybackLaunch(val provider: String, val titleId: String, val type: String?, val name: String?, val logo: String?)

data class PlaybackSessionSetup(
    val services: TvApplication.Services,
    val title: Title,
    val developerMode: Boolean,
    val clockFormat: String,
    val clockEnabled: Boolean,
)

data class PlaybackSourceResolution(
    val title: Title,
    val candidates: List<MediaSource>,
    val unavailable: Availability?,
    val watched: Boolean,
    val resumePosition: Long,
    val marks: Map<String, TvStore.SourceMark>,
    val autoplay: Boolean,
    val soundsEnabled: Boolean,
    val diagnostics: String,
)

/**
 * Owns source selection, provider request lifetime and immutable presentation independently of rendering.
 * Blocking work still runs on application workers. In particular this never creates, selects or closes a native
 * session: the outgoing activity queues that cleanup on the same application playback worker as the next open.
 */
class PlaybackViewModel : ViewModel() {
    private val mutablePresentation = MutableStateFlow<PlaybackPresentation>(PlaybackPresentation.Idle)
    val presentation: StateFlow<PlaybackPresentation> = mutablePresentation.asStateFlow()
    private val mutableSelection = MutableStateFlow(PlaybackSelection())
    val selection: StateFlow<PlaybackSelection> = mutableSelection.asStateFlow()
    private val mutableControls = MutableStateFlow(PlayerControlsState())
    val controls: StateFlow<PlayerControlsState> = mutableControls.asStateFlow()
    private var owner: PlaybackWork? = null

    /** A recreated activity gets an isolated request owner; cancelled closures cannot mutate its generation. */
    fun attach(): PlaybackWork {
        owner?.close()
        mutablePresentation.value = PlaybackPresentation.Idle
        mutableSelection.value = PlaybackSelection()
        mutableControls.value = PlayerControlsState()
        return PlaybackWork().also { owner = it }
    }

    var candidates: List<MediaSource>
        get() = selection.value.candidates
        set(value) { mutableSelection.update { it.copy(candidates = value.toList()) } }
    var selected: MediaSource?
        get() = selection.value.selected
        set(value) { mutableSelection.update { it.copy(selected = value) } }
    var automatic: Boolean
        get() = selection.value.automatic
        set(value) { mutableSelection.update { it.copy(automatic = value) } }
    val attempts: Int get() = selection.value.attempts
    val attemptedSources: Set<String> get() = selection.value.attemptedSources
    val attemptedProviders: Set<String> get() = selection.value.attemptedProviders

    fun watching(title: String, detail: String) { mutableControls.update { it.copy(title = title, detail = detail) } }
    fun watched(watched: Boolean) { mutableControls.update { it.copy(watched = watched) } }

    fun resetAttempts() {
        mutableSelection.update { it.copy(attempts = 0, attemptedSources = emptySet(), attemptedProviders = emptySet()) }
    }

    fun attempt(source: MediaSource?) {
        mutableSelection.update {
            it.copy(attempts = it.attempts + 1,
                attemptedSources = if (source == null) it.attemptedSources else it.attemptedSources + SourcePolicy.retryKey(source),
                attemptedProviders = if (source == null) it.attemptedProviders else it.attemptedProviders + source.provider)
        }
    }

    fun nextCandidate(): Int = selection.value.let { SourcePolicy.nextCandidate(it.candidates, it.attemptedSources, it.attemptedProviders) }

    fun loading(id: String, step: String, detail: String?, untilReady: Boolean) {
        mutablePresentation.value = PlaybackPresentation.Loading(id, step, detail, untilReady)
    }
    fun ready() { mutablePresentation.value = PlaybackPresentation.Ready }
    fun failed(error: PlaybackErrors.Description) { mutablePresentation.value = PlaybackPresentation.Failed(error) }
    fun released() {
        mutableSelection.value = PlaybackSelection()
        mutablePresentation.value = PlaybackPresentation.Released
    }

    /** Call on the application's playback worker: store APIs block. */
    fun sessionSetup(app: TvApplication, launch: PlaybackLaunch): PlaybackSessionSetup {
        val services = app.services()
        val title = services.store.library(false).lastOrNull {
            it.title.id == launch.titleId && it.title.provider == launch.provider
        }?.title ?: requireNotNull(Title.parse(launch.provider, JSONObject().put("id", launch.titleId)
            .put("type", launch.type).put("name", launch.name).put("logo", launch.logo)))
        return PlaybackSessionSetup(services, title, SetupScreens.developerMode(services),
            SetupScreens.clockFormat(services), SetupScreens.playerClock(services))
    }

    /** Resolve providers and durable progress together on the application's playback worker. */
    fun resolveSources(work: PlaybackWork, services: TvApplication.Services, title: Title, video: String,
                       explicitKey: String?, preparationToken: String?, resumePosition: Long,
                       request: Cancellation): PlaybackSourceResolution {
        work.closeDiscovery()
        var metadata = title
        var candidates = emptyList<MediaSource>()
        var unavailable: Availability? = null
        val diagnostics = JSONObject()
        if (title.provider != "local") {
            val discovery = services.preparation.take(preparationToken, title, video)
            work.discovery(discovery)
            candidates = discovery.awaitCandidate(request, explicitKey)
            metadata = discovery.title
            val providers = services.addons.providers("stream", title.type, video)
            val available = discovery.sources()
            diagnostics.put("providersAsked", providers.size).put("advertisedSources", available.size)
                .put("playableSources", candidates.size)
            if (candidates.isEmpty()) unavailable = Availability.of(providers, available, discovery.result().failures, services.store.addons())
        }
        val progress = services.store.progress(metadata.key(), video)
        val position = if (resumePosition > 0) resumePosition else if (progress != null && !progress.watched) progress.positionMs else 0L
        val result = PlaybackSourceResolution(metadata, candidates.toList(), unavailable, progress?.watched == true,
            position, services.store.sourceMarks(metadata.key(), video).toMap(),
            services.store.preference("autoplay", "true") == "true", SetupScreens.soundsOn(services), diagnostics.toString())
        request.check()
        return result
    }

    override fun onCleared() { owner?.close(); owner = null }
}

/** Cancellable per-activity work; only its provider/task handles survive outside the rendering code. */
class PlaybackWork : AutoCloseable {
    @Volatile var generation: Int = generations.incrementAndGet()
        private set
    var scope: Cancellation? = null
    var task: Future<*>? = null
    @Volatile var discovery: PlaybackPreparation.Entry? = null
        private set
    @Volatile var isClosed = false
        private set

    @Synchronized fun nextGeneration(): Int {
        generation = generations.incrementAndGet()
        return generation
    }

    @Synchronized fun discovery(entry: PlaybackPreparation.Entry) {
        if (isClosed) {
            entry.close()
            throw tv.stream.app.addon.AddonException(tv.stream.app.addon.AddonException.Kind.CANCELLED, "Request cancelled")
        }
        discovery = entry
    }

    fun cancel() {
        nextGeneration()
        scope?.close(); scope = null
        task?.cancel(true); task = null
    }

    @Synchronized fun closeDiscovery() { discovery?.close(); discovery = null }
    @Synchronized override fun close() { isClosed = true; cancel(); closeDiscovery() }

    companion object {
        // The same activity can restart with a new work owner; late callbacks must never reuse its old number.
        private val generations = AtomicInteger()
    }
}
