package tv.stream.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

import java.util.LinkedHashMap

/** The video's qualities, named as viewers know them (1080p, 720p…), with "Auto" letting Media3 adapt. */
@OptIn(markerClass = [UnstableApi::class])
object VideoQualities {
    const val AUTO = "auto"

    class Option(@JvmField val label: String, @JvmField val lines: Int, @JvmField val group: TrackGroup) {
        @JvmField val id: String = lines.toString()
        @JvmField val indices: MutableList<Int> = ArrayList()
    }

    /** Highest first; empty when there is nothing to choose. */
    @JvmStatic
    fun from(tracks: Tracks): List<Option> {
        var chosen: Tracks.Group? = null
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO || !group.isSupported) continue
            if (chosen == null || group.isSelected) chosen = group
        }
        if (chosen == null) return ArrayList()
        val options: MutableMap<Int, Option> = LinkedHashMap()
        for (i in 0 until chosen.length) {
            if (!chosen.isTrackSupported(i)) continue
            val format = chosen.getTrackFormat(i)
            val lines = lines(format.width, format.height)
            if (lines <= 0) continue
            var option = options[lines]
            if (option == null) {
                option = Option(label(lines), lines, chosen.mediaTrackGroup)
                options[lines] = option
            }
            option.indices.add(i)
        }
        val list: MutableList<Option> = ArrayList(options.values)
        list.sortWith { a, b -> b.lines - a.lines }
        return if (list.size > 1) list else ArrayList()
    }

    @JvmStatic
    fun find(options: List<Option>, id: String?): Option? {
        for (option in options) if (option.id == id) return option
        return null
    }

    /** Fixes the quality, or lets the player adapt again when `option` is null. */
    @JvmStatic
    fun select(player: Player, option: Option?) {
        if (option == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(option.group, option.indices)).build()
        }
    }

    @JvmStatic
    fun reset(player: Player) {
        select(player, null)
    }

    /** Films are often cropped (1920 × 800), so the width counts too: 1920 wide is 1080p. */
    @JvmStatic
    fun lines(width: Int, height: Int): Int {
        val lines = Math.max(height, Math.round(width * 9 / 16f))
        if (lines <= 0) return 0
        if (lines >= 2000) return 2160
        if (lines >= 1300) return 1440
        if (lines >= 1000) return 1080
        if (lines >= 650) return 720
        if (lines >= 440) return 480
        if (lines >= 330) return 360
        return 240
    }

    @JvmStatic
    fun label(lines: Int): String {
        return if (lines == 2160) "4K" else lines.toString() + "p"
    }
}
