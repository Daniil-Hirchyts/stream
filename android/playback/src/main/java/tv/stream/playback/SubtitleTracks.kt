package tv.stream.playback

import android.content.Context

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.regex.Pattern

/** The current video's subtitle tracks, from its playlist or from external files, with readable names. */
@OptIn(markerClass = [UnstableApi::class])
object SubtitleTracks {
    /** Format id given to external files, in request order: `side-0`, `side-1`… */
    const val SIDELOADED = "side-"
    // Packagers often name tracks "stream_0" or "subs1"; the language reads better.
    private val TECHNICAL_LABEL: Pattern = Pattern.compile("(?i)(stream|track|text|subs?|subtitles?)[ _-]?\\d*")
    private val DASH: Pattern = Pattern.compile("-")

    class Track {
        @JvmField val id: String
        @JvmField val label: String
        @JvmField val language: String?
        @JvmField val selected: Boolean
        @JvmField val preferred: Boolean
        @JvmField val formatId: String?
        @JvmField val group: TrackGroup?
        @JvmField val external: Boolean
        @JvmField val index: Int

        constructor(id: String, label: String, format: Format, group: Tracks.Group, index: Int) {
            this.id = id
            this.external = false
            this.label = label
            this.language = format.language
            this.selected = group.isTrackSelected(index)
            this.preferred = (format.selectionFlags and (C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)) != 0
            this.formatId = format.id
            this.group = group.mediaTrackGroup
            this.index = index
        }
        constructor(id: String, label: String, language: String?, selected: Boolean) {
            this.id = id; this.label = label; this.language = language; this.selected = selected
            preferred = false; formatId = id; group = null; index = -1; external = true
        }
        constructor(original: Track, selected: Boolean) {
            id = original.id; label = original.label; language = original.language; this.selected = selected
            preferred = original.preferred; formatId = original.formatId; group = original.group; index = original.index; external = original.external
        }

    }

    /**
     * Subtitles wanted for new media: an external file by its index ("0") or "off", else a track's label or
     * language, such as what the browser was showing. All null leaves the choice to the viewer's last language.
     */
    class Request(@JvmField val choice: String?, @JvmField val language: String?, @JvmField val label: String?)

    @JvmStatic
    fun from(context: Context, tracks: Tracks): List<Track> {
        val list: MutableList<Track> = ArrayList()
        val labels: MutableMap<String, Int> = HashMap()
        val groups: List<Tracks.Group> = tracks.groups
        for (g in groups.indices) {
            val group = groups[g]
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t)) continue
                val format = group.getTrackFormat(t)
                // For HLS that doesn't declare its captions, Media3 assumes a CEA-608 channel. It has no name or
                // language and is almost always empty, including in video converted on the Mac.
                if (isClosedCaption(format) && primary(format.language) == null
                        && (format.label == null || format.label!!.trim { it <= ' ' }.isEmpty())) continue
                var label = label(context, format, list.size)
                val seen = if (labels.containsKey(label)) labels[label]!! + 1 else 1
                labels[label] = seen
                if (seen > 1) {
                    val fromPage = format.id != null && format.id!!.contains(SIDELOADED)
                    label = if (fromPage) context.getString(R.string.track_from_page, label) else "$label $seen"
                }
                list.add(Track("$g.$t", label, format, group, t))
            }
        }
        return list
    }

    @JvmStatic
    fun selected(tracks: List<Track>): Track? {
        for (track in tracks) if (track.selected) return track
        return null
    }

    @JvmStatic
    fun find(tracks: List<Track>, id: String?): Track? {
        for (track in tracks) if (track.id == id) return track
        return null
    }

    /** Shows one track, or turns subtitles off when `track` is null. */
    @JvmStatic
    fun select(player: Player, track: Track?) {
        val parameters: TrackSelectionParameters.Builder = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
        if (track != null && track.group != null) parameters.addOverride(TrackSelectionOverride(track.group!!, track.index))
        player.trackSelectionParameters = parameters.build()
    }

    /** Clears the previous video's choice before new media loads. */
    @JvmStatic
    fun reset(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    }

    /**
     * Picks subtitles for newly loaded media: the page's choice first, then the track the browser was showing,
     * then the viewer's last language. With subtitles switched on, a video that has any shows them.
     */
    @JvmStatic
    fun initial(tracks: List<Track>, request: Request, enabled: Boolean, language: String?): Track? {
        if ("off" == request.choice) return null
        if (request.choice != null) {
            // Merging the video and subtitle sources prefixes each id with its source index: "2:side-1".
            val id = SIDELOADED + request.choice
            for (track in tracks) {
                val formatId = track.formatId
                if (formatId != null && (formatId == id || formatId.endsWith(":$id"))) {
                    return track
                }
            }
        }
        val requestLabel = request.label
        if (requestLabel != null && !requestLabel.isEmpty()) {
            for (track in tracks) if (track.label.equals(requestLabel, ignoreCase = true)) return track
        }
        val hinted = matchLanguage(tracks, request.language)
        if (hinted != null) return hinted
        if (!enabled || tracks.isEmpty()) return null
        val remembered = matchLanguage(tracks, language)
        if (remembered != null) return remembered
        for (track in tracks) if (track.preferred) return track
        return tracks[0]
    }

    private fun matchLanguage(tracks: List<Track>, language: String?): Track? {
        val wanted = primary(language) ?: return null
        for (track in tracks) if (wanted == primary(track.language)) return track
        return null
    }

    /** The language without its region, such as "en" for "en-US"; null when unknown. */
    @JvmStatic
    fun primary(language: String?): String? {
        // normalizeLanguageCode is @PolyNull: a null code normalizes to null.
        val normalized: String? = if (language == null) null else Util.normalizeLanguageCode(language)
        if (normalized == null || normalized.isEmpty() || C.LANGUAGE_UNDETERMINED == normalized) return null
        return DASH.split(normalized)[0]
    }

    private fun label(context: Context, format: Format, position: Int): String {
        val label = if (format.label == null) "" else format.label!!.trim { it <= ' ' }
        val language = if (primary(format.language) == null) null else format.language
        if (!label.isEmpty() && !(language != null && TECHNICAL_LABEL.matcher(label).matches())) return label
        if (language != null) {
            val name = Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH)
            if (!name.isEmpty()) return Character.toUpperCase(name[0]).toString() + name.substring(1)
        }
        if (isClosedCaption(format)) return context.getString(R.string.closed_captions)
        if (!label.isEmpty()) return label
        return context.getString(R.string.track_numbered, position + 1)
    }

    private fun isClosedCaption(format: Format): Boolean {
        val mime = if (format.sampleMimeType == null) "" else format.sampleMimeType!!
        val codecs = if (format.codecs == null) "" else format.codecs!!
        return mime.contains("cea-") || codecs.contains("cea-")
    }

    @JvmStatic
    fun none(): List<Track> {
        return Collections.emptyList()
    }
}
