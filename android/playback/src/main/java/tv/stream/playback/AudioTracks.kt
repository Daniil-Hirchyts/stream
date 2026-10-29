package tv.stream.playback

import android.content.Context

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.regex.Pattern

/**
 * Audio tracks of the current video, usually voice-overs. Streams often call them only "rus0" or "ru2"; the number
 * is the player's own index, so the page's names ("Eng.Original") can be put back on them.
 */
@OptIn(markerClass = [UnstableApi::class])
object AudioTracks {
    private val NUMBERED: Pattern = Pattern.compile("[A-Za-z]{2,3}(\\d{1,2})")
    private val TRAILING_DIGITS = Regex("\\d+$")

    class Track(
        @JvmField val id: String,
        @JvmField val label: String,
        /** The page's index for this voice-over. */
        @JvmField val number: Int,
        format: Format,
        group: Tracks.Group,
        @JvmField val indices: List<Int>,
    ) {
        @JvmField val language: String? = format.language
        @JvmField val selected: Boolean = group.isSelected
        @JvmField val group: TrackGroup = group.mediaTrackGroup
    }

    /** @param pageNames names by the page's index, or empty when the page gave none. */
    @JvmStatic
    fun from(context: Context, tracks: Tracks, pageNames: List<String>): List<Track> {
        val groups: MutableList<Tracks.Group> = ArrayList()
        val positions: MutableList<Int> = ArrayList()
        val all: List<Tracks.Group> = tracks.groups
        for (g in all.indices) {
            if (all[g].type == C.TRACK_TYPE_AUDIO && all[g].isSupported) {
                groups.add(all[g])
                positions.add(g)
            }
        }
        // Numbered names identify the page's voice-over; otherwise tracks follow the page's order.
        var numbered = !groups.isEmpty()
        for (group in groups) numbered = numbered and (number(group.getTrackFormat(0)) >= 0)

        val list: MutableList<Track> = ArrayList()
        val numbers: MutableSet<Int> = HashSet()
        val labels: MutableMap<String, Int> = HashMap()
        for (k in groups.indices) {
            val group = groups[k]
            val format = group.getTrackFormat(0)
            val number = if (numbered) number(format) else k
            if (!numbers.add(number)) continue
            val name = if (number < pageNames.size) pageNames[number].trim { it <= ' ' } else ""
            var label = if (name.isEmpty()) label(context, format, k) else name
            val seen = if (labels.containsKey(label)) labels[label]!! + 1 else 1
            labels[label] = seen
            if (seen > 1) label += " $seen"
            val indices: MutableList<Int> = ArrayList()
            for (t in 0 until group.length) if (group.isTrackSupported(t)) indices.add(t)
            list.add(Track(positions[k].toString(), label, number, format, group, indices))
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

    @JvmStatic
    fun numbered(tracks: List<Track>, number: Int): Track? {
        for (track in tracks) if (track.number == number) return track
        return null
    }

    /** The first track in `language`, ignoring regions and the digits streams append ("rus2"). */
    @JvmStatic
    fun spoken(tracks: List<Track>, language: String?): Track? {
        val wanted = SubtitleTracks.primary(language) ?: return null
        for (track in tracks) {
            val trackLanguage = track.language
            val spoken = if (trackLanguage == null) null else SubtitleTracks.primary(trackLanguage.replace(TRAILING_DIGITS, ""))
            if (wanted == spoken) return track
        }
        return null
    }

    /** Lets the next video start on its own default track. */
    @JvmStatic
    fun reset(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
    }

    @JvmStatic
    fun select(player: Player, track: Track) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(track.group, track.indices)).build()
    }

    private fun number(format: Format): Int {
        for (value in arrayOf(format.label, format.language)) {
            if (value == null) continue
            val matcher = NUMBERED.matcher(value.trim { it <= ' ' })
            if (matcher.matches()) return Integer.parseInt(matcher.group(1)!!)
        }
        return -1
    }

    private fun label(context: Context, format: Format, position: Int): String {
        val label = if (format.label == null) "" else format.label!!.trim { it <= ' ' }
        if (!label.isEmpty() && !NUMBERED.matcher(label).matches()) return label
        val language = if (format.language == null) "" else format.language!!.replace(TRAILING_DIGITS, "")
        if (!language.isEmpty() && C.LANGUAGE_UNDETERMINED != language) {
            val name = Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH)
            if (!name.isEmpty() && !name.equals(language, ignoreCase = true)) {
                return Character.toUpperCase(name[0]).toString() + name.substring(1)
            }
        }
        return context.getString(R.string.audio_numbered, position + 1)
    }
}
