package tv.stream.playback

import android.net.Uri

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

import java.util.Collections
import java.util.HashMap

/** One media item and everything the player needs before it starts. Immutable; see [Builder]. */
@OptIn(markerClass = [UnstableApi::class])
class PlaybackRequest private constructor(
    /** The host's name for this media, such as a provider's video id; the player doesn't read it. */
    @JvmField val id: String,
    @JvmField val mediaIdentity: String,
    @JvmField val title: String,
    /** A line under the title, such as "S1 E2"; empty when there is none. */
    @JvmField val detail: String,
    @JvmField val uri: Uri,
    @JvmField val mimeType: String?,
    /** Sent with media requests over HTTP; a User-Agent replaces the session's own. */
    @JvmField val headers: Map<String, String>,
    /** Sent with external subtitle requests, which may need the page's headers when the media doesn't. */
    @JvmField val subtitleHeaders: Map<String, String>,
    @JvmField val subtitles: List<SubtitleDownloader.Source>,
    @JvmField val subtitleRequest: SubtitleTracks.Request,
    /** Audio track names by the source's own numbering, which pages use for voice-overs. */
    @JvmField val audioNames: List<String>,
    /** The audio track to start with, by that numbering; -1 lets [audioLanguage] or the stream decide. */
    @JvmField val audioIndex: Int,
    @JvmField val audioLanguage: String?,
    @JvmField val positionMs: Long,
    /** Keeps the previous media's speed and chosen voice-over, as for the next episode of a series. */
    @JvmField val continuing: Boolean,
    @JvmField val startPaused: Boolean,
    /** Reads the media itself, such as from a torrent; null fetches [uri] over HTTP. */
    @JvmField val dataSource: DataSource.Factory?,
) {
    class Builder(private val uri: Uri, private val title: String) {
        private var id: String = ""
        private var detail: String = ""
        private var mediaIdentity: String? = null
        private var mimeType: String? = null
        private var audioLanguage: String? = null
        private var headers: Map<String, String> = Collections.emptyMap()
        private var subtitleHeaders: Map<String, String>? = null
        private var subtitles: List<SubtitleDownloader.Source> = Collections.emptyList()
        private var subtitleRequest: SubtitleTracks.Request = SubtitleTracks.Request(null, null, null)
        private var audioNames: List<String> = Collections.emptyList()
        private var audioIndex = -1
        private var positionMs: Long = 0
        private var continuing = false
        private var startPaused = false
        private var dataSource: DataSource.Factory? = null

        fun mediaIdentity(value: String?): Builder { mediaIdentity = value; return this }
        fun id(value: String): Builder { id = value; return this }
        fun detail(value: String): Builder { detail = value; return this }
        fun mimeType(value: String?): Builder { mimeType = value; return this }
        fun headers(value: Map<String, String>): Builder { headers = value; return this }
        fun subtitleHeaders(value: Map<String, String>?): Builder { subtitleHeaders = value; return this }
        fun subtitles(value: List<SubtitleDownloader.Source>): Builder { subtitles = value; return this }
        fun subtitleRequest(value: SubtitleTracks.Request): Builder { subtitleRequest = value; return this }
        fun audioNames(value: List<String>): Builder { audioNames = value; return this }
        fun audioIndex(value: Int): Builder { audioIndex = value; return this }
        fun audioLanguage(value: String?): Builder { audioLanguage = value; return this }
        fun position(value: Long): Builder { positionMs = Math.max(0, value); return this }
        fun startPaused(value: Boolean): Builder { startPaused = value; return this }
        fun continuing(value: Boolean): Builder { continuing = value; return this }
        fun dataSource(value: DataSource.Factory?): Builder { dataSource = value; return this }

        fun build(): PlaybackRequest {
            // The Java original's PlaybackRequest(Builder) constructor, evaluated in the same order.
            return PlaybackRequest(
                    id,
                    if (mediaIdentity == null) uri.toString() else mediaIdentity!!,
                    title,
                    detail,
                    uri,
                    mimeType,
                    Collections.unmodifiableMap(HashMap(headers)),
                    Collections.unmodifiableMap(HashMap(
                            if (subtitleHeaders == null) headers else subtitleHeaders!!)),
                    Collections.unmodifiableList(ArrayList(subtitles)),
                    subtitleRequest,
                    Collections.unmodifiableList(ArrayList(audioNames)),
                    audioIndex,
                    audioLanguage,
                    positionMs,
                    continuing, startPaused,
                    dataSource)
        }
    }
}
