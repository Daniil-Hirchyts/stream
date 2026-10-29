package tv.stream.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** The session's state at one moment. Immutable; times are milliseconds and 0 when unknown. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackSnapshot(session: PlaybackSession, player: ExoPlayer) {
    /** Counts loads and stops; a snapshot from an older generation describes media that is gone. */
    @JvmField val generation: Int
    /** Media is set, whether or not it has started. */
    @JvmField val loaded: Boolean
    /** The current media became ready at least once. */
    @JvmField val started: Boolean
    /** A video frame is on screen, not just sound. */
    @JvmField val renderedFirstFrame: Boolean
    @JvmField val playing: Boolean
    @JvmField val playWhenReady: Boolean
    @JvmField val buffering: Boolean
    @JvmField val ended: Boolean
    @JvmField val positionMs: Long
    @JvmField val durationMs: Long
    @JvmField val bufferedMs: Long
    @JvmField val speed: Float
    @JvmField val audioTracks: List<AudioTracks.Track>
    @JvmField val audio: AudioTracks.Track?
    @JvmField val textTracks: List<SubtitleTracks.Track>
    @JvmField val subtitles: SubtitleTracks.Track?
    @JvmField val qualities: List<VideoQualities.Option>
    /** The fixed quality; null while Media3 adapts. */
    @JvmField val quality: VideoQualities.Option?
    @JvmField val videoFormat: Format?
    @JvmField val audioFormat: Format?
    @JvmField val error: PlaybackErrors.Description?

    init {
        generation = session.generation()
        loaded = player.mediaItemCount > 0
        started = session.started()
        renderedFirstFrame = session.renderedFirstFrame()
        playing = player.isPlaying
        playWhenReady = player.playWhenReady
        val state = player.playbackState
        buffering = state == Player.STATE_BUFFERING
        ended = state == Player.STATE_ENDED
        positionMs = player.currentPosition
        val duration = player.duration
        durationMs = if (duration == C.TIME_UNSET) 0 else duration
        bufferedMs = player.bufferedPosition
        speed = session.speed()
        audioTracks = session.audioTracks()
        audio = AudioTracks.selected(audioTracks)
        textTracks = session.textTracks()
        subtitles = SubtitleTracks.selected(textTracks)
        qualities = session.qualities()
        quality = session.quality()
        videoFormat = player.videoFormat
        audioFormat = player.audioFormat
        error = session.error()
    }
}
