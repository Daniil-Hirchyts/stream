package tv.stream.playback;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

/** Optional app-owned decoder extensions. All calls arrive on the player's application thread. */
@OptIn(markerClass = UnstableApi.class)
public interface PlaybackDecoders {
    /** The factory must retain {@link SubtitlePlayback#textRenderer} for external subtitle compatibility. */
    DefaultRenderersFactory renderers(Context context);
    void attach(ExoPlayer player);
    /** Called after stopping/clearing the outgoing media, before loading the next request. */
    void reset();
    /** Return true only after arranging a bounded retry of the same media. */
    boolean recover(PlaybackException error);
    void release();
}
