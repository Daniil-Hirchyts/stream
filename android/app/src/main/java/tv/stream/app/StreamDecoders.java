package tv.stream.app;

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
import tv.stream.playback.PlaybackDecoders;
import tv.stream.playback.SubtitlePlayback;

/** Hardware first, bundled FFmpeg when unavailable, and one runtime fallback per track type/media load. */
@OptIn(markerClass = UnstableApi.class)
final class StreamDecoders implements PlaybackDecoders {
    private final DecoderManager manager = new DecoderManager();
    private ExoPlayer player;
    private NextRenderersFactory factory;
    private boolean retriedStereo;
    private boolean retriedVideo, retriedAudio;
    private String videoFormat, audioFormat;

    static boolean supports(String mime) {
        return mime != null && FfmpegLibrary.supportsFormat(mime);
    }

    @Override public DefaultRenderersFactory renderers(Context context) {
        DecoderProfile.load(context);
        return factory = new NextRenderersFactory(context) {
            @Override protected void buildTextRenderers(Context context, TextOutput output, Looper looper,
                    int mode, ArrayList<Renderer> out) {
                out.add(SubtitlePlayback.textRenderer(output, looper));
            }
        }.setDecoderManager(manager).setVideoFormatPredicate(format -> SourcePolicy.withinDeviceProfile(format) && DecoderProfile.hdrSupported(format));
    }

    @Override public void attach(ExoPlayer player) {
        this.player = player; manager.attach(player);
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override public void onAudioInputFormatChanged(EventTime time, Format format, androidx.media3.exoplayer.DecoderReuseEvaluation reuse) { inputFormat(format, false); }
            @Override public void onVideoInputFormatChanged(EventTime time, Format format, androidx.media3.exoplayer.DecoderReuseEvaluation reuse) { inputFormat(format, true); }
        });
    }
    private void inputFormat(Format format, boolean video) {
        String key = format.sampleMimeType + ":" + format.codecs + ":" + format.cryptoType;
        String previous = video ? videoFormat : audioFormat;
        if (video) videoFormat = key; else audioFormat = key;
        // Bitrate, resolution and language changes within a codec keep the working renderer.
        if (previous == null || previous.equals(key)) return;
        if (video) { retriedVideo = false; manager.selectVideoDecoder(DecoderMode.AUTO); }
        else { retriedAudio = retriedStereo = false; factory.setForceStereoAudio(false); manager.selectAudioDecoder(DecoderMode.AUTO); }
    }

    @Override public void reset() {
        retriedVideo = retriedAudio = retriedStereo = false; videoFormat = audioFormat = null;
        if (factory != null) factory.setForceStereoAudio(false);
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
        boolean ffmpeg = failure.rendererName != null && failure.rendererName.toLowerCase(Locale.ROOT).contains("ffmpeg");
        if (audioOutput && ffmpeg && !retriedStereo && format != null && format.cryptoType == C.CRYPTO_TYPE_NONE) {
            retriedStereo = true; factory.setForceStereoAudio(true); manager.selectAudioDecoder(DecoderMode.FFMPEG); player.prepare(); return true;
        }
        // FFmpeg cannot decrypt DRM. Never retry failures from FFmpeg itself, or loop on corrupt input.
        if (format == null || format.cryptoType != C.CRYPTO_TYPE_NONE || !supports(format.sampleMimeType)
                || ffmpeg) return false;
        if (MimeTypes.isVideo(format.sampleMimeType)) {
            if (DecoderProfile.hdr(format) || audioOutput || retriedVideo || manager.getActiveVideoMode() == DecoderMode.FFMPEG) return false;
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
