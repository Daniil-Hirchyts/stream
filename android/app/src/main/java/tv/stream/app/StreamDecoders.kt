package tv.stream.app

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.ArrayList
import java.util.Locale
import java.util.function.Predicate
import tv.stream.playback.PlaybackDecoders
import tv.stream.playback.SubtitlePlayback

/** Hardware first, bundled FFmpeg when unavailable, and one runtime fallback per track type/media load. */
@OptIn(markerClass = [UnstableApi::class])
class StreamDecoders : PlaybackDecoders {
    private val manager = DecoderManager()
    private var player: ExoPlayer? = null
    private var factory: NextRenderersFactory? = null
    private var retriedStereo = false
    private var retriedVideo = false
    private var retriedAudio = false
    private var videoFormat: String? = null
    private var audioFormat: String? = null

    companion object {
        fun supports(mime: String?): Boolean {
            return mime != null && FfmpegLibrary.supportsFormat(mime)
        }
    }

    override fun renderers(context: Context): DefaultRenderersFactory {
        DecoderProfile.load(context)
        val created = object : NextRenderersFactory(context) {
            override fun buildTextRenderers(context: Context, output: TextOutput, looper: Looper,
                    mode: Int, out: ArrayList<Renderer>) {
                out.add(SubtitlePlayback.textRenderer(output, looper))
            }
        }.setDecoderManager(manager).setVideoFormatPredicate(Predicate { format -> SourcePolicy.withinDeviceProfile(format) && DecoderProfile.hdrSupported(format) })
        factory = created
        return created
    }

    override fun attach(player: ExoPlayer) {
        this.player = player; manager.attach(player)
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioInputFormatChanged(time: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, format: Format, reuse: androidx.media3.exoplayer.DecoderReuseEvaluation?) { inputFormat(format, false) }
            override fun onVideoInputFormatChanged(time: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, format: Format, reuse: androidx.media3.exoplayer.DecoderReuseEvaluation?) { inputFormat(format, true) }
        })
    }
    private fun inputFormat(format: Format, video: Boolean) {
        val key = format.sampleMimeType + ":" + format.codecs + ":" + format.cryptoType
        val previous = if (video) videoFormat else audioFormat
        if (video) videoFormat = key else audioFormat = key
        // Bitrate, resolution and language changes within a codec keep the working renderer.
        if (previous == null || previous == key) return
        if (video) { retriedVideo = false; manager.selectVideoDecoder(DecoderMode.AUTO) }
        else { retriedStereo = false; retriedAudio = retriedStereo; factory!!.setForceStereoAudio(false); manager.selectAudioDecoder(DecoderMode.AUTO) }
    }

    override fun reset() {
        retriedStereo = false; retriedAudio = retriedStereo; retriedVideo = retriedAudio; audioFormat = null; videoFormat = audioFormat
        if (factory != null) factory!!.setForceStereoAudio(false)
        manager.selectVideoDecoder(DecoderMode.AUTO)
        manager.selectAudioDecoder(DecoderMode.AUTO)
    }

    override fun recover(error: PlaybackException): Boolean {
        if (error !is ExoPlaybackException || player == null) return false
        val failure = error
        if (failure.type != ExoPlaybackException.TYPE_RENDERER) return false
        val code = error.errorCode
        val decoder = code >= 4000 && code < 5000
        val audioOutput = code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
        if (!decoder && !audioOutput) return false
        val format = failure.rendererFormat
        val ffmpeg = failure.rendererName != null && failure.rendererName!!.lowercase(Locale.ROOT).contains("ffmpeg")
        if (audioOutput && ffmpeg && !retriedStereo && format != null && format.cryptoType == C.CRYPTO_TYPE_NONE) {
            retriedStereo = true; factory!!.setForceStereoAudio(true); manager.selectAudioDecoder(DecoderMode.FFMPEG); player!!.prepare(); return true
        }
        // FFmpeg cannot decrypt DRM. Never retry failures from FFmpeg itself, or loop on corrupt input.
        if (format == null || format.cryptoType != C.CRYPTO_TYPE_NONE || !supports(format.sampleMimeType)
                || ffmpeg) return false
        if (MimeTypes.isVideo(format.sampleMimeType)) {
            if (DecoderProfile.hdr(format) || audioOutput || retriedVideo || manager.activeVideoMode == DecoderMode.FFMPEG) return false
            retriedVideo = true
            manager.selectVideoDecoder(DecoderMode.FFMPEG)
        } else if (MimeTypes.isAudio(format.sampleMimeType)) {
            if (retriedAudio || manager.activeAudioMode == DecoderMode.FFMPEG) return false
            retriedAudio = true
            manager.selectAudioDecoder(DecoderMode.FFMPEG)
        } else return false
        // DecoderManager retains the playlist, track overrides, position, speed and playWhenReady.
        player!!.prepare()
        return true
    }

    override fun release() { manager.detach(); player = null }
}
