package tv.stream.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.Collections

/** The session's state at one moment. Immutable; times are milliseconds and 0 when unknown. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackSnapshot(session: PlaybackSession, player: ExoPlayer) {
    /** Counts loads and stops; a snapshot from an older generation describes media that is gone. */
    val generation: Int
    /** Media is set, whether or not it has started. */
    val loaded: Boolean
    /** The current media became ready at least once. */
    val started: Boolean
    /** A video frame is on screen, not just sound. */
    val renderedFirstFrame: Boolean
    val playing: Boolean
    val playWhenReady: Boolean
    val buffering: Boolean
    val ended: Boolean
    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long
    val speed: Float
    val audioTracks: List<AudioTracks.Track>
    val audio: AudioTracks.Track?
    val textTracks: List<SubtitleTracks.Track>
    val subtitles: SubtitleTracks.Track?
    val qualities: List<VideoQualities.Option>
    /** The fixed quality; null while Media3 adapts. */
    val quality: VideoQualities.Option?
    val videoFormat: Format?
    val audioFormat: Format?
    val error: PlaybackErrors.Description?

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
        audioTracks = Collections.unmodifiableList(ArrayList(session.audioTracks()))
        audio = AudioTracks.selected(audioTracks)
        textTracks = Collections.unmodifiableList(ArrayList(session.textTracks()))
        subtitles = SubtitleTracks.selected(textTracks)
        qualities = Collections.unmodifiableList(ArrayList(session.qualities()))
        quality = session.quality()
        videoFormat = player.videoFormat
        audioFormat = player.audioFormat
        error = session.error()
    }
}
