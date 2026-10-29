package tv.stream.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import java.util.Collections

/** One immutable media request. Collections are copied before a playback worker can consume them. */
@OptIn(markerClass = [UnstableApi::class])
class PlaybackRequest(
    val uri: Uri,
    val title: String,
    /** The host's name for this media, such as a provider's video id; the player doesn't read it. */
    val id: String = "",
    mediaIdentity: String? = null,
    /** A line under the title, such as "S1 E2"; empty when there is none. */
    val detail: String = "",
    val mimeType: String? = null,
    /** Sent with media requests over HTTP; a User-Agent replaces the session's own. */
    headers: Map<String, String> = emptyMap(),
    /** Sent with external subtitle requests; omitted headers use the media request's headers. */
    subtitleHeaders: Map<String, String>? = null,
    subtitles: List<SubtitleDownloader.Source> = emptyList(),
    val subtitleRequest: SubtitleTracks.Request = SubtitleTracks.Request(),
    /** Audio track names by the source's own numbering, which pages use for voice-overs. */
    audioNames: List<String> = emptyList(),
    /** The audio track to start with; -1 lets [audioLanguage] or the stream decide. */
    val audioIndex: Int = -1,
    val audioLanguage: String? = null,
    positionMs: Long = 0,
    /** Keeps the previous media's speed and chosen voice-over, as for the next episode of a series. */
    val continuing: Boolean = false,
    val startPaused: Boolean = false,
    /** Reads the media itself, such as from a torrent; null fetches [uri] over HTTP. */
    val dataSource: DataSource.Factory? = null,
) {
    val mediaIdentity: String = mediaIdentity ?: uri.toString()
    val headers: Map<String, String> = Collections.unmodifiableMap(HashMap(headers))
    val subtitleHeaders: Map<String, String> = Collections.unmodifiableMap(HashMap(subtitleHeaders ?: headers))
    val subtitles: List<SubtitleDownloader.Source> = Collections.unmodifiableList(ArrayList(subtitles))
    val audioNames: List<String> = Collections.unmodifiableList(ArrayList(audioNames))
    val positionMs: Long = positionMs.coerceAtLeast(0)

    /** Compatibility for existing integrations; Kotlin callers can use the constructor's named arguments. */
    class Builder(private val uri: Uri, private val title: String) {
        private var id = ""
        private var detail = ""
        private var mediaIdentity: String? = null
        private var mimeType: String? = null
        private var audioLanguage: String? = null
        private var headers: Map<String, String> = emptyMap()
        private var subtitleHeaders: Map<String, String>? = null
        private var subtitles: List<SubtitleDownloader.Source> = emptyList()
        private var subtitleRequest = SubtitleTracks.Request()
        private var audioNames: List<String> = emptyList()
        private var audioIndex = -1
        private var positionMs = 0L
        private var continuing = false
        private var startPaused = false
        private var dataSource: DataSource.Factory? = null

        fun mediaIdentity(value: String?) = apply { mediaIdentity = value }
        fun id(value: String) = apply { id = value }
        fun detail(value: String) = apply { detail = value }
        fun mimeType(value: String?) = apply { mimeType = value }
        fun headers(value: Map<String, String>) = apply { headers = value }
        fun subtitleHeaders(value: Map<String, String>?) = apply { subtitleHeaders = value }
        fun subtitles(value: List<SubtitleDownloader.Source>) = apply { subtitles = value }
        fun subtitleRequest(value: SubtitleTracks.Request) = apply { subtitleRequest = value }
        fun audioNames(value: List<String>) = apply { audioNames = value }
        fun audioIndex(value: Int) = apply { audioIndex = value }
        fun audioLanguage(value: String?) = apply { audioLanguage = value }
        fun position(value: Long) = apply { positionMs = value.coerceAtLeast(0) }
        fun startPaused(value: Boolean) = apply { startPaused = value }
        fun continuing(value: Boolean) = apply { continuing = value }
        fun dataSource(value: DataSource.Factory?) = apply { dataSource = value }

        fun build() = PlaybackRequest(
            uri = uri, title = title, id = id, mediaIdentity = mediaIdentity, detail = detail,
            mimeType = mimeType, headers = headers, subtitleHeaders = subtitleHeaders,
            subtitles = subtitles, subtitleRequest = subtitleRequest, audioNames = audioNames,
            audioIndex = audioIndex, audioLanguage = audioLanguage, positionMs = positionMs,
            continuing = continuing, startPaused = startPaused, dataSource = dataSource,
        )
    }
}
