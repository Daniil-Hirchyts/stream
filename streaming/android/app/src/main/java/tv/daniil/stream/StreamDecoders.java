package tv.daniil.stream;

import android.content.Context;
import android.os.Looper;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.text.TextOutput;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import java.util.ArrayList;
import java.util.Locale;
import tv.daniil.playback.PlaybackDecoders;
import tv.daniil.playback.SubtitlePlayback;

/** Hardware first, bundled FFmpeg when unavailable, and one runtime fallback per track type/media load. */
@OptIn(markerClass = UnstableApi.class)
final class StreamDecoders implements PlaybackDecoders {
    private final DecoderManager manager = new DecoderManager();
    private ExoPlayer player;
    private boolean retriedVideo, retriedAudio;

    static boolean supports(String mime) {
        return mime != null && FfmpegLibrary.supportsFormat(mime);
    }

    @Override public DefaultRenderersFactory renderers(Context context) {
        return new NextRenderersFactory(context) {
            @Override protected void buildTextRenderers(Context context, TextOutput output, Looper looper,
                    int mode, ArrayList<Renderer> out) {
                out.add(SubtitlePlayback.textRenderer(output, looper));
            }
        }.setDecoderManager(manager);
    }

    @Override public void attach(ExoPlayer player) { this.player = player; manager.attach(player); }

    @Override public void reset() {
        retriedVideo = retriedAudio = false;
        manager.selectVideoDecoder(DecoderMode.AUTO);
        manager.selectAudioDecoder(DecoderMode.AUTO);
    }

    @Override public boolean recover(PlaybackException error) {
        if (!(error instanceof ExoPlaybackException) || player == null) return false;
        ExoPlaybackException failure = (ExoPlaybackException) error;
        if (failure.type != ExoPlaybackException.TYPE_RENDERER) return false;
        int code = error.errorCode;
        boolean decoder = code >= 4000 && code < 5000;
        boolean audioOutput = code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED;
        if (!decoder && !audioOutput) return false;
        Format format = failure.rendererFormat;
        // FFmpeg cannot decrypt DRM. Never retry failures from FFmpeg itself, or loop on corrupt input.
        if (format == null || format.cryptoType != C.CRYPTO_TYPE_NONE || !supports(format.sampleMimeType)
                || (failure.rendererName != null && failure.rendererName.toLowerCase(Locale.ROOT).contains("ffmpeg"))) return false;
        if (MimeTypes.isVideo(format.sampleMimeType)) {
            if (audioOutput || retriedVideo || manager.getActiveVideoMode() == DecoderMode.FFMPEG) return false;
            retriedVideo = true;
            manager.selectVideoDecoder(DecoderMode.FFMPEG);
        } else if (MimeTypes.isAudio(format.sampleMimeType)) {
            if (retriedAudio || manager.getActiveAudioMode() == DecoderMode.FFMPEG) return false;
            retriedAudio = true;
            manager.selectAudioDecoder(DecoderMode.FFMPEG);
        } else return false;
        // DecoderManager retains the playlist, track overrides, position, speed and playWhenReady.
        player.prepare();
        return true;
    }

    @Override public void release() { manager.detach(); player = null; }
}
