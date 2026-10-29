package tv.stream.playback

import android.content.Context
import android.os.Looper

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

import java.util.ArrayList
import java.util.Collections

/**
 * Keeps the full timeline of downloaded subtitle files available to the text renderer.
 * Extraction-time cue samples can be exhausted after a seek or an off/on selection,
 * leaving a long active cue invisible. Single-sample decoding retains its start/end
 * events and evaluates them at the current position, including while paused.
 * Embedded subtitles continue through Media3's normal extraction pipeline.
 *
 * Media3 currently marks this compatibility path deprecated. The playback regression
 * suite must pass before removing it or upgrading past its eventual removal.
 */
@OptIn(markerClass = [UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
@Suppress("DEPRECATION")
class SubtitlePlayback(context: Context) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(context: Context, output: TextOutput,
            outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
        out.add(textRenderer(output, outputLooper))
    }

    companion object {
        /** App decoder factories use the same seek/late-selection compatibility as the default factory. */
        @JvmStatic
        fun textRenderer(output: TextOutput, outputLooper: Looper): TextRenderer {
            val renderer = TextRenderer(output, outputLooper)
            renderer.experimentalSetLegacyDecodingEnabled(true)
            return renderer
        }

        @JvmStatic
        fun create(sources: DefaultMediaSourceFactory, data: DataSource.Factory, media: MediaItem): MediaSource {
            if (media.localConfiguration == null || media.localConfiguration!!.subtitleConfigurations.isEmpty()) {
                return sources.createMediaSource(media)
            }
            val subtitles: List<MediaItem.SubtitleConfiguration> = media.localConfiguration!!.subtitleConfigurations
            val children = arrayOfNulls<MediaSource>(subtitles.size + 1)
            children[0] = sources.createMediaSource(media.buildUpon()
                    .setSubtitleConfigurations(Collections.emptyList()).build())
            for (i in subtitles.indices) {
                children[i + 1] = SingleSampleMediaSource.Factory(data)
                        .createMediaSource(subtitles[i], C.TIME_UNSET)
            }
            @Suppress("UNCHECKED_CAST")
            return MergingMediaSource(*(children as Array<MediaSource>))
        }
    }
}
