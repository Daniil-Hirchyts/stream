package tv.stream.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.text.Subtitle

import java.io.File
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns one screen's player: loads requests and their external subtitles, applies track preferences, gathers remote
 * seeks, explains errors, reports formats without a decoder and mirrors state to the system media session. Use it
 * on the main thread; subtitle downloads and parsing run in the background.
 */
@OptIn(markerClass = [UnstableApi::class])
class PlaybackSession
/** Apps may bundle additional decoders without changing the default platform-only player. */
constructor(
    private val context: Context,
    private val prefs: SharedPreferences,
    name: String,
    private val userAgent: String,
    private val host: PlaybackHost,
    bufferBytes: Int,
    private val decoders: PlaybackDecoders?,
) {
    /** The player screen's view of the session. Calls arrive on the main thread. */
    interface Listener {
        /** New media was loaded, or the player stopped. */
        fun onMediaChanged()
        fun onPlaybackChanged()
        /** The visible cues may differ: new cues, a seek, or play and pause. */
        fun onCuesChanged()
        fun onTracksChanged()
        /** Remote seek presses add up to `deltaMs` so far; the seek follows once they pause. */
        fun onSeekGathering(deltaMs: Long)
    }

    private val player: ExoPlayer
    private val mediaSession: MediaSession
    private val subtitleRoot: File
    private val handler = Handler(Looper.getMainLooper())
    private val background: ExecutorService = Executors.newSingleThreadExecutor()
    private val listeners: MutableList<Listener> = ArrayList()

    private var request: PlaybackRequest? = null
    /** Also read by subtitle downloads, which give up once a newer load or a stop replaced theirs. */
    @Volatile private var generation = 0
    private var started = false
    private var renderedFirstFrame = false
    private var reportedUnsupported = false
    private var released = false
    private var error: PlaybackErrors.Description? = null
    private var speed = 1f
    private var decoderIdentity: String? = null
    private var subtitleRevision = 0
    private var externalChoice: String? = null
    private val externalSubtitles: MutableMap<String, ExternalSubtitle> = java.util.LinkedHashMap()
    private val cueTick = Runnable { externalCuesChanged() }
    private class ExternalSubtitle(@JvmField val track: SubtitleTracks.Track, @JvmField val timeline: Subtitle, @JvmField val address: String)
    /** The voice-over the viewer chose, by name, kept from one episode to the next. */
    private var voice: String? = null

    // Tracks, and what the request asked for once they are known.
    private var textTracks: List<SubtitleTracks.Track> = SubtitleTracks.none()
    private var pausedSubtitles: Map<String, Subtitle> = Collections.emptyMap()
    private var subtitleRequest: SubtitleTracks.Request? = null
    private var audioTracks: List<AudioTracks.Track> = Collections.emptyList()
    private var audioRequest = -1
    private var audioLanguage: String? = null
    private var qualities: List<VideoQualities.Option> = Collections.emptyList()
    private var quality: VideoQualities.Option? = null

    private var seekTarget = C.TIME_UNSET
    private var seekDelta: Long = 0
    private var seekPending = false

    private val commitSeek = Runnable { commitSeek() }
    private val endSeek = Runnable {
        seekTarget = C.TIME_UNSET
        seekDelta = 0
    }
    private val progress: Runnable = object : Runnable {
        override fun run() {
            if (player.mediaItemCount > 0) {
                updateMediaSession()
                if (player.isPlaying) host.onProgress(snapshot())
            }
            handler.postDelayed(this, PROGRESS_MS)
        }
    }

    /**
     * @param prefs where subtitle choices are remembered: `subtitles_on`, `subtitle_language` and the
     *              [SubtitleStyle] keys.
     * @param name the system media session's tag.
     * @param userAgent sent with HTTP requests unless a request supplies its own.
     */
    constructor(context: Context, prefs: SharedPreferences, name: String, userAgent: String, host: PlaybackHost) :
            this(context, prefs, name, userAgent, host, 0)

    /** Hosts with a constrained streaming budget may cap compressed Media3 buffers; zero keeps defaults. */
    constructor(context: Context, prefs: SharedPreferences, name: String, userAgent: String, host: PlaybackHost, bufferBytes: Int) :
            this(context, prefs, name, userAgent, host, bufferBytes, null)

    init {
        subtitleRoot = File(File(context.cacheDir, "subtitles"), java.util.UUID.randomUUID().toString())
        Capabilities.load(context)
        // Decoders hidden for testing stay hidden from playback too.
        val builder = ExoPlayer.Builder(context, (if (decoders == null) SubtitlePlayback(context) else decoders.renderers(context))
                .setMediaCodecSelector(Capabilities.selector()).setEnableDecoderFallback(true))
        if (bufferBytes > 0) builder.setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setTargetBufferBytes(bufferBytes).setPrioritizeTimeOverSizeThresholds(false)
                .setBufferDurationsMs(3000, 15_000, 1000, 1000).build())
        player = builder.build()
        if (decoders != null) decoders.attach(player)
        player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) started = true
                if (state == Player.STATE_ENDED) {
                    host.onEnded()
                    reportProgress()
                }
                notifyChanged()
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                notifyChanged()
                externalCuesChanged()
                if (!playing) reportProgress()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo, reason: Int) {
                externalCuesChanged()
                if (reason == Player.DISCONTINUITY_REASON_SEEK) reportProgress()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                notifyChanged()
            }
            override fun onPlayerError(exception: PlaybackException) {
                if (exception.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // A live stream moved on while paused: rejoin it rather than stop.
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (decoders != null && decoders.recover(exception)) return
                fail(PlaybackErrors.describe(context, exception))
            }
            override fun onRenderedFirstFrame() {
                renderedFirstFrame = true
                notifyChanged()
            }
            override fun onTracksChanged(tracks: Tracks) {
                checkSupport(tracks)
                updateTracks(tracks)
            }
            override fun onCues(cues: CueGroup) {
                for (listener in listeners()) listener.onCuesChanged()
            }
        })
        mediaSession = MediaSession(context, name)
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { player.play() }
            override fun onPause() { player.pause() }
            override fun onStop() { host.onExit() }
            override fun onSeekTo(position: Long) { seekTo(position) }
            override fun onFastForward() { seekBy(SEEK_STEP_MS) }
            override fun onRewind() { seekBy(-SEEK_STEP_MS) }
            override fun onSkipToNext() { host.onNextEpisode() }
            override fun onSkipToPrevious() { host.onPreviousEpisode() }
        })
        mediaSession.isActive = true
        handler.post(progress)
    }

    // Loading

    /** Replaces the current media. External subtitles download first, for at most eight seconds. */
    fun load(next: PlaybackRequest) {
        reportProgress()
        val load = ++generation
        request = next
        pausedSubtitles = Collections.emptyMap()
        externalSubtitles.clear(); externalChoice = null; subtitleRevision++; handler.removeCallbacks(cueTick)
        if (!next.continuing) {
            voice = null
            speed = 1f
        }
        audioRequest = next.audioIndex
        audioLanguage = next.audioLanguage
        subtitleRequest = next.subtitleRequest
        error = null
        started = false
        renderedFirstFrame = false
        reportedUnsupported = false
        quality = null
        qualities = Collections.emptyList()
        cancelSeek()
        player.stop()
        player.clearMediaItems()
        if (decoders != null && next.mediaIdentity != decoderIdentity) decoders.reset()
        decoderIdentity = next.mediaIdentity
        SubtitleTracks.reset(player)
        AudioTracks.reset(player)
        VideoQualities.reset(player)
        player.setPlaybackSpeed(speed)
        for (listener in listeners()) listener.onMediaChanged()
        val media = MediaItem.Builder().setUri(next.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(next.title).build())
        if (next.mimeType != null && !next.mimeType.isEmpty()) media.setMimeType(next.mimeType)
        if (next.subtitles.isEmpty()) {
            play(media, next)
            return
        }
        background.execute {
            if (load != generation) return@execute // Replaced before it began.
            val downloaded: List<MediaItem.SubtitleConfiguration> =
                    SubtitleDownloader.download(subtitleFolder(load), next.subtitles, next.subtitleHeaders)
            val paused: Map<String, Subtitle> = PausedSubtitleCues.read(downloaded)
            handler.post {
                if (load != generation || released) return@post // A newer load replaced this one.
                pausedSubtitles = paused
                media.setSubtitleConfigurations(downloaded)
                play(media, next)
            }
        }
        notifyChanged()
    }

    private fun play(media: MediaItem.Builder, next: PlaybackRequest) {
        // Media3 applies its own User-Agent after the default properties, so the request's must be set here.
        // Some CDNs answer HTTP 410 to an unfamiliar one.
        val properties: MutableMap<String, String> = HashMap(next.headers)
        val agent: String? = properties.remove("User-Agent")
        val http = DefaultHttpDataSource.Factory()
                .setUserAgent(if (agent == null) userAgent else agent)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
                .setDefaultRequestProperties(properties)
        // DefaultDataSource adds file:// for the downloaded subtitles.
        val data = DefaultDataSource.Factory(context, http)
        val sources = DefaultMediaSourceFactory(if (next.dataSource == null) data else next.dataSource as DataSource.Factory)
        player.setMediaSource(SubtitlePlayback.create(sources, data, media.build()))
        player.seekTo(next.positionMs)
        player.prepare()
        player.playWhenReady = !next.startPaused
        notifyChanged()
    }

    /**
     * Each load downloads into its own folder and removes older ones, so a slow download for replaced media can
     * never delete the files of the media now playing. Runs on the background thread, one load at a time.
     */
    private fun subtitleFolder(load: Int): File {
        val name = load.toString()
        val old = subtitleRoot.listFiles()
        if (old != null) for (item in old) if (item.name != name) delete(item)
        return File(subtitleRoot, name)
    }

    /** Stops and clears the media, for example while the next version is still being prepared elsewhere. */
    fun stop() {
        reportProgress()
        generation++
        request = null
        pausedSubtitles = Collections.emptyMap()
        externalSubtitles.clear(); externalChoice = null; subtitleRevision++; handler.removeCallbacks(cueTick)
        error = null
        started = false
        renderedFirstFrame = false
        reportedUnsupported = false
        cancelSeek()
        player.stop()
        player.clearMediaItems()
        for (listener in listeners()) listener.onMediaChanged()
        notifyChanged()
    }

    /** Stops loading and shows an error that needs the viewer: a failed stream, or a problem the app found. */
    fun fail(description: PlaybackErrors.Description?) {
        generation++
        error = description
        cancelSeek()
        player.stop()
        notifyChanged()
    }

    fun release() {
        if (released) return
        reportProgress()
        released = true
        generation++
        handler.removeCallbacksAndMessages(null)
        background.shutdownNow()
        val cleanup = Thread({
            try { if (background.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) delete(subtitleRoot) }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt() }
        }, "subtitle-cleanup")
        cleanup.isDaemon = true; cleanup.start()
        listeners.clear()
        player.stop()
        if (decoders != null) decoders.release()
        player.release()
        mediaSession.release()
    }

    /**
     * A format Media3 can't decode would play as sound over a black screen, or as a silent picture. The app decides
     * instead: convert it, or offer another source.
     */
    private fun checkSupport(tracks: Tracks) {
        if (reportedUnsupported || error != null) return
        var hasVideo = false
        var video = false
        var hasAudio = false
        var audio = false
        var videoMime: String? = null
        var audioMime: String? = null
        for (group in tracks.groups) {
            val mime: String? = if (group.length > 0) group.getTrackFormat(0).sampleMimeType else null
            if (group.type == C.TRACK_TYPE_VIDEO) {
                hasVideo = true
                video = video or group.isSupported
                if (mime != null) videoMime = mime
            } else if (group.type == C.TRACK_TYPE_AUDIO) {
                hasAudio = true
                audio = audio or group.isSupported
                if (mime != null) audioMime = mime
            }
        }
        val format: String? = if (hasVideo && !video) Capabilities.name(videoMime)
                else if (hasAudio && !audio) Capabilities.name(audioMime) else null
        if (format == null) return
        reportedUnsupported = true
        host.onUnsupportedFormat(format)
    }

    /** Applies what the request asked for once the tracks are known. */
    private fun updateTracks(tracks: Tracks) {
        val request = this.request
        audioTracks = AudioTracks.from(context, tracks, if (request == null) Collections.emptyList() else request.audioNames)
        if (!audioTracks.isEmpty() && (audioRequest >= 0 || audioLanguage != null)) {
            var wanted: AudioTracks.Track? = if (audioRequest >= 0) AudioTracks.numbered(audioTracks, audioRequest) else null
            if (wanted != null) voice = wanted.label
            else if (audioLanguage != null) wanted = AudioTracks.spoken(audioTracks, audioLanguage)
            audioRequest = -1
            audioLanguage = null
            if (wanted != null && !wanted.selected) AudioTracks.select(player, wanted)
        }
        textTracks = SubtitleTracks.from(context, tracks)
        val pendingSubtitles = subtitleRequest
        if (pendingSubtitles != null && !textTracks.isEmpty()) {
            val wanted: SubtitleTracks.Request = pendingSubtitles
            subtitleRequest = null
            SubtitleTracks.select(player, SubtitleTracks.initial(textTracks, wanted,
                    prefs.getBoolean(PREF_SUBTITLES_ON, true), prefs.getString(PREF_LANGUAGE, null)))
        }
        mergeExternalTracks()
        qualities = VideoQualities.from(tracks)
        val fixed = quality
        if (fixed != null) quality = VideoQualities.find(qualities, fixed.id)
        for (listener in listeners()) listener.onTracksChanged()
        host.onPlaybackChanged(snapshot())
    }

    // Controls

    fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.playWhenReady) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        cancelSeek()
        player.seekTo(Math.max(0, positionMs))
    }

    /**
     * Repeated presses add up, so holding a button scrubs. The seek itself waits for the presses to pause, so a
     * stream fetches the final position rather than every step on the way.
     */
    fun seekBy(deltaMs: Long) {
        if (!started || error != null) return
        val base = if (seekTarget != C.TIME_UNSET) seekTarget else player.currentPosition
        var target = Math.max(0, base + deltaMs)
        val duration = player.duration
        if (duration != C.TIME_UNSET) target = Math.min(target, Math.max(0, duration - 1000))
        seekDelta += target - base
        seekTarget = target
        seekPending = true
        handler.removeCallbacks(commitSeek)
        handler.postDelayed(commitSeek, SEEK_COMMIT_MS)
        handler.removeCallbacks(endSeek)
        handler.postDelayed(endSeek, SEEK_SETTLE_MS)
        for (listener in listeners()) listener.onSeekGathering(seekDelta)
    }

    private fun commitSeek() {
        seekPending = false
        if (seekTarget != C.TIME_UNSET) player.seekTo(seekTarget)
    }

    private fun cancelSeek() {
        handler.removeCallbacks(commitSeek)
        handler.removeCallbacks(endSeek)
        seekPending = false
        seekTarget = C.TIME_UNSET
        seekDelta = 0
    }

    /** Where playback is, or where gathered seek presses will take it. */
    fun position(): Long {
        return if (seekPending) seekTarget else player.currentPosition
    }

    fun selectAudio(track: AudioTracks.Track) {
        voice = track.label
        AudioTracks.select(player, track)
        notifyChanged()
    }

    /** Shows one subtitle track, or none, and remembers for the next media whether and in which language. */
    fun subtitleSelectionRevision(): Int { return subtitleRevision }
    fun selectSubtitles(track: SubtitleTracks.Track?) {
        subtitleRevision++; subtitleRequest = null
        externalChoice = if (track != null && track.external) track.id else null
        SubtitleTracks.select(player, if (externalChoice == null) track else null)
        textTracks = SubtitleTracks.from(context, player.currentTracks); mergeExternalTracks()
        for (listener in listeners()) listener.onTracksChanged()
        externalCuesChanged()
        val editor = prefs.edit().putBoolean(PREF_SUBTITLES_ON, track != null)
        if (track != null && track.language != null) editor.putString(PREF_LANGUAGE, track.language)
        editor.apply()
        notifyChanged()
    }

    /** Downloads optional captions without replacing the media, renderer, clock or buffering state. */
    fun attachExternalSubtitles(sources: List<SubtitleDownloader.Source>, headers: Map<String, String>, select: Boolean) {
        attachExternalSubtitles(sources, headers, select, java.util.function.Consumer { ignored -> })
    }
    fun hasExternalSubtitle(address: String?): Boolean {
        for (subtitle in externalSubtitles.values) if (subtitle.address == address) return true
        return false
    }
    fun attachExternalSubtitles(sources: List<SubtitleDownloader.Source>, headers: Map<String, String>,
            select: Boolean, completion: java.util.function.Consumer<Boolean>) {
        if (released || request == null || sources.isEmpty()) { completion.accept(false); return }
        val batchSources: List<SubtitleDownloader.Source> = ArrayList(sources.subList(0, Math.min(8, sources.size)))
        val load = generation
        val revision = if (select) ++subtitleRevision else subtitleRevision
        val batch = java.util.UUID.randomUUID().toString()
        background.execute {
            if (load != generation) return@execute
            val directory = File(subtitleRoot, "$load/$batch")
            val files: List<MediaItem.SubtitleConfiguration> = SubtitleDownloader.download(directory, batchSources, headers)
            val timelines: Map<String, Subtitle> = PausedSubtitleCues.read(files)
            delete(directory)
            handler.post {
                if (load != generation || released) return@post
                var first: String? = null
                for (file in files) {
                    // HashMap.get(null) is null in the Java original, so a file without an id is skipped here too.
                    val fileId: String = file.id ?: continue
                    val timeline: Subtitle = timelines[fileId] ?: continue
                    val id = "external:$batch:$fileId"
                    val label: String = if (file.label == null || file.label!!.isEmpty()) (if (file.language == null) "Subtitles" else file.language!!) else file.label!!
                    val index = Integer.parseInt(fileId.substring(SubtitleTracks.SIDELOADED.length))
                    externalSubtitles[id] = ExternalSubtitle(SubtitleTracks.Track(id, label, file.language, false), timeline, batchSources[index].url)
                    if (first == null) first = id
                }
                if (select && revision == subtitleRevision && first != null) {
                    externalChoice = first; subtitleRequest = null; SubtitleTracks.select(player, null)
                }
                while (externalSubtitles.size > 8) {
                    var oldest: String? = null
                    for (candidate in externalSubtitles.keys) if (candidate != externalChoice) { oldest = candidate; break }
                    if (oldest == null) break
                    externalSubtitles.remove(oldest)
                }
                textTracks = SubtitleTracks.from(context, player.currentTracks); mergeExternalTracks()
                for (listener in listeners()) listener.onTracksChanged()
                externalCuesChanged(); notifyChanged(); completion.accept(first != null)
            }
        }
    }
    private fun mergeExternalTracks() {
        val merged: MutableList<SubtitleTracks.Track> = ArrayList()
        for (track in textTracks) if (!track.external) merged.add(if (externalChoice == null) track else SubtitleTracks.Track(track, false))
        for ((key, value) in externalSubtitles) merged.add(SubtitleTracks.Track(value.track, key == externalChoice))
        textTracks = Collections.unmodifiableList(merged)
    }
    private fun externalCuesChanged() {
        handler.removeCallbacks(cueTick)
        for (listener in listeners()) listener.onCuesChanged()
        val external = externalSubtitle()
        if (released || external == null || !player.isPlaying) return
        val positionUs = player.currentPosition * 1000
        val next = external.timeline.getNextEventTimeIndex(positionUs)
        if (next >= 0) handler.postDelayed(cueTick, Math.max(10L, Math.min(1000L,
                ((external.timeline.getEventTime(next) - positionUs) / 1000.0 / speed).toLong())))
    }

    /** `externalSubtitles.get(externalChoice)`; the map holds no null key. */
    private fun externalSubtitle(): ExternalSubtitle? {
        val choice = externalChoice
        return if (choice == null) null else externalSubtitles[choice]
    }

    /** Fixes the quality, or lets Media3 adapt again when `option` is null. */
    fun selectQuality(option: VideoQualities.Option?) {
        quality = option
        VideoQualities.select(player, option)
        notifyChanged()
    }

    fun setSpeed(value: Float) {
        speed = Math.max(0.25f, Math.min(4f, value))
        player.setPlaybackSpeed(speed)
        externalCuesChanged()
        notifyChanged()
    }

    // State

    fun player(): ExoPlayer {
        return player
    }

    fun preferences(): SharedPreferences {
        return prefs
    }

    fun request(): PlaybackRequest? {
        return request
    }

    fun snapshot(): PlaybackSnapshot {
        return PlaybackSnapshot(this, player)
    }

    fun isLoaded(): Boolean {
        return player.mediaItemCount > 0
    }

    fun generation(): Int {
        return generation
    }

    fun started(): Boolean {
        return started
    }

    fun renderedFirstFrame(): Boolean {
        return renderedFirstFrame
    }

    fun error(): PlaybackErrors.Description? {
        return error
    }

    fun speed(): Float {
        return speed
    }

    /** The voice-over the viewer chose or the request named, by label; null for the stream's default. */
    fun voice(): String? {
        return voice
    }

    fun audioTracks(): List<AudioTracks.Track> {
        return audioTracks
    }

    fun textTracks(): List<SubtitleTracks.Track> {
        return textTracks
    }

    fun qualities(): List<VideoQualities.Option> {
        return qualities
    }

    fun quality(): VideoQualities.Option? {
        return quality
    }

    /** The lines of the picture now playing, such as 1080, or 0 before there is one. */
    fun playingLines(): Int {
        val format = player.videoFormat
        return if (format == null) 0 else VideoQualities.lines(format.width, format.height)
    }

    /**
     * The cues to show now. The text renderer waits for playback to resume before outputting a new legacy cue after
     * a paused seek, so while paused they come from the downloaded file's own timeline.
     */
    fun cues(): List<Cue> {
        val external = externalSubtitle()
        if (external != null) return external.timeline.getCues(player.currentPosition * 1000)
        var cues: List<Cue> = player.currentCues.cues
        if (!player.playWhenReady) {
            val timeline = PausedSubtitleCues.find(pausedSubtitles, SubtitleTracks.selected(textTracks))
            if (timeline != null) cues = timeline.getCues(player.currentPosition * 1000)
        }
        return cues
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    private fun listeners(): List<Listener> {
        return ArrayList(listeners)
    }

    /** Tells the screen and the app that something they show changed. */
    fun notifyChanged() {
        if (released) return
        for (listener in listeners()) listener.onPlaybackChanged()
        updateMediaSession()
        host.onPlaybackChanged(snapshot())
    }

    private fun reportProgress() {
        if (!released && started && player.mediaItemCount > 0) host.onProgress(snapshot())
    }

    private fun updateMediaSession() {
        val playing = player.isPlaying
        mediaSession.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
                        or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                        or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
                        or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        player.currentPosition, if (playing) speed else 0f).build())
    }

    companion object {
        const val SEEK_STEP_MS: Long = 10_000
        /** A gathered seek waits this long for another press, so a stream fetches only where the presses stop. */
        private const val SEEK_COMMIT_MS: Long = 450
        /** Presses this close together count as one jump. */
        private const val SEEK_SETTLE_MS: Long = 1200
        private const val PROGRESS_MS: Long = 1000
        private const val PREF_SUBTITLES_ON = "subtitles_on"
        private const val PREF_LANGUAGE = "subtitle_language"

        private fun delete(file: File) {
            val children = file.listFiles()
            if (children != null) for (child in children) delete(child)
            file.delete()
        }
    }
}
