package tv.stream.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.Subtitle

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.HashMap

/**
 * The text renderer waits for playback to resume before outputting a new legacy
 * cue after a paused seek. Keep an immutable timeline for that still frame too.
 * Parsing stays off the UI thread and uses Media3's parsers, including cue styling,
 * overlaps and end times; no subtitle syntax is reimplemented here.
 */
@OptIn(markerClass = [UnstableApi::class])
object PausedSubtitleCues {
    @JvmStatic
    fun read(files: List<MediaItem.SubtitleConfiguration>): Map<String, Subtitle> {
        val result: MutableMap<String, Subtitle> = HashMap()
        val parsers = DefaultSubtitleParserFactory()
        for (file in files) {
            if (file.id == null || file.uri.path == null || "file" != file.uri.scheme) continue
            try {
                val format = Format.Builder().setSampleMimeType(file.mimeType).build()
                if (!parsers.supportsFormat(format)) continue
                val bytes = Files.readAllBytes(File(file.uri.path!!).toPath())
                result[file.id!!] = parsers.create(format).parseToLegacySubtitle(bytes, 0, bytes.size)
            } catch (ignored: Exception) { /* A bad optional subtitle must not stop the video. */ }
        }
        return Collections.unmodifiableMap(result)
    }

    @JvmStatic
    fun find(timelines: Map<String, Subtitle>, track: SubtitleTracks.Track?): Subtitle? {
        if (track == null || track.formatId == null) return null
        val id: String = track.formatId!!
        return timelines[id.substring(id.lastIndexOf(':') + 1)]
    }
}
