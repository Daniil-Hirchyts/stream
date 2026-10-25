package tv.daniil.playback;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.List;

/** The session's state at one moment. Immutable; times are milliseconds and 0 when unknown. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackSnapshot {
    /** Counts loads and stops; a snapshot from an older generation describes media that is gone. */
    public final int generation;
    /** Media is set, whether or not it has started. */
    public final boolean loaded;
    /** The current media became ready at least once. */
    public final boolean started;
    /** A video frame is on screen, not just sound. */
    public final boolean renderedFirstFrame;
    public final boolean playing, playWhenReady, buffering, ended;
    public final long positionMs, durationMs, bufferedMs;
    public final float speed;
    public final List<AudioTracks.Track> audioTracks;
    @Nullable public final AudioTracks.Track audio;
    public final List<SubtitleTracks.Track> textTracks;
    @Nullable public final SubtitleTracks.Track subtitles;
    public final List<VideoQualities.Option> qualities;
    /** The fixed quality; null while Media3 adapts. */
    @Nullable public final VideoQualities.Option quality;
    @Nullable public final Format videoFormat, audioFormat;
    @Nullable public final PlaybackErrors.Description error;

    PlaybackSnapshot(PlaybackSession session, ExoPlayer player) {
        generation = session.generation();
        loaded = player.getMediaItemCount() > 0;
        started = session.started();
        renderedFirstFrame = session.renderedFirstFrame();
        playing = player.isPlaying();
        playWhenReady = player.getPlayWhenReady();
        int state = player.getPlaybackState();
        buffering = state == Player.STATE_BUFFERING;
        ended = state == Player.STATE_ENDED;
        positionMs = player.getCurrentPosition();
        long duration = player.getDuration();
        durationMs = duration == C.TIME_UNSET ? 0 : duration;
        bufferedMs = player.getBufferedPosition();
        speed = session.speed();
        audioTracks = session.audioTracks();
        audio = AudioTracks.selected(audioTracks);
        textTracks = session.textTracks();
        subtitles = SubtitleTracks.selected(textTracks);
        qualities = session.qualities();
        quality = session.quality();
        videoFormat = player.getVideoFormat();
        audioFormat = player.getAudioFormat();
        error = session.error();
    }
}
