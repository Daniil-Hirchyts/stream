package tv.daniil.playback;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.SingleSampleMediaSource;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
@OptIn(markerClass = UnstableApi.class)
@SuppressWarnings("deprecation")
final class SubtitlePlayback extends DefaultRenderersFactory {
    SubtitlePlayback(Context context) { super(context); }

    @Override protected void buildTextRenderers(Context context, TextOutput output,
            Looper outputLooper, int extensionRendererMode, ArrayList<Renderer> out) {
        TextRenderer renderer = new TextRenderer(output, outputLooper);
        renderer.experimentalSetLegacyDecodingEnabled(true);
        out.add(renderer);
    }

    static MediaSource create(DefaultMediaSourceFactory sources, DataSource.Factory data, MediaItem media) {
        if (media.localConfiguration == null || media.localConfiguration.subtitleConfigurations.isEmpty()) {
            return sources.createMediaSource(media);
        }
        List<MediaItem.SubtitleConfiguration> subtitles = media.localConfiguration.subtitleConfigurations;
        MediaSource[] children = new MediaSource[subtitles.size() + 1];
        children[0] = sources.createMediaSource(media.buildUpon()
                .setSubtitleConfigurations(Collections.emptyList()).build());
        for (int i = 0; i < subtitles.size(); i++) {
            children[i + 1] = new SingleSampleMediaSource.Factory(data)
                    .createMediaSource(subtitles.get(i), C.TIME_UNSET);
        }
        return new MergingMediaSource(children);
    }
}
