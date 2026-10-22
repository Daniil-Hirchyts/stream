package tv.daniil.playback;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.Subtitle;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The text renderer waits for playback to resume before outputting a new legacy
 * cue after a paused seek. Keep an immutable timeline for that still frame too.
 * Parsing stays off the UI thread and uses Media3's parsers, including cue styling,
 * overlaps and end times; no subtitle syntax is reimplemented here.
 */
@OptIn(markerClass = UnstableApi.class)
final class PausedSubtitleCues {
    private PausedSubtitleCues() {}

    static Map<String, Subtitle> read(List<MediaItem.SubtitleConfiguration> files) {
        Map<String, Subtitle> result = new HashMap<>();
        DefaultSubtitleParserFactory parsers = new DefaultSubtitleParserFactory();
        for (MediaItem.SubtitleConfiguration file : files) {
            if (file.id == null || file.uri.getPath() == null || !"file".equals(file.uri.getScheme())) continue;
            try {
                Format format = new Format.Builder().setSampleMimeType(file.mimeType).build();
                if (!parsers.supportsFormat(format)) continue;
                byte[] bytes = Files.readAllBytes(new File(file.uri.getPath()).toPath());
                result.put(file.id, parsers.create(format).parseToLegacySubtitle(bytes, 0, bytes.length));
            } catch (Exception ignored) { /* A bad optional subtitle must not stop the video. */ }
        }
        return Collections.unmodifiableMap(result);
    }

    @Nullable static Subtitle find(Map<String, Subtitle> timelines, @Nullable SubtitleTracks.Track track) {
        if (track == null || track.formatId == null) return null;
        String id = track.formatId;
        return timelines.get(id.substring(id.lastIndexOf(':') + 1));
    }
}
