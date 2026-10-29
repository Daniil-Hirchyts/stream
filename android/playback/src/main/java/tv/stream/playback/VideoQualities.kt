package tv.stream.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

import java.util.Collections

/** The video's qualities, named as viewers know them (1080p, 720p…), with "Auto" letting Media3 adapt. */
@OptIn(markerClass = [UnstableApi::class])
object VideoQualities {
    const val AUTO = "auto"

    class Option(val label: String, val lines: Int, val group: TrackGroup, indices: List<Int> = emptyList()) {
        val id: String = lines.toString()
        val indices: List<Int> = Collections.unmodifiableList(ArrayList(indices))
    }

    /** Highest first; empty when there is nothing to choose. */
    fun from(tracks: Tracks): List<Option> {
        val candidates = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
        // Retain the first supported group unless a later selected group supersedes it.
        val chosen = candidates.lastOrNull { it.isSelected } ?: candidates.firstOrNull() ?: return emptyList()
        val indices = linkedMapOf<Int, MutableList<Int>>()
        for (index in 0 until chosen.length) {
            if (!chosen.isTrackSupported(index)) continue
            val format = chosen.getTrackFormat(index)
            val lines = lines(format.width, format.height)
            if (lines > 0) indices.getOrPut(lines) { mutableListOf() }.add(index)
        }
        if (indices.size <= 1) return emptyList()
        return Collections.unmodifiableList(indices.entries.sortedByDescending { it.key }.map { (lines, entries) ->
            Option(label(lines), lines, chosen.mediaTrackGroup, entries)
        })
    }

    fun find(options: List<Option>, id: String?): Option? {
        for (option in options) if (option.id == id) return option
        return null
    }

    /** Fixes the quality, or lets the player adapt again when `option` is null. */
    fun select(player: Player, option: Option?) {
        if (option == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(option.group, option.indices)).build()
        }
    }

    fun reset(player: Player) {
        select(player, null)
    }

    /** Films are often cropped (1920 × 800), so the width counts too: 1920 wide is 1080p. */
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

    fun label(lines: Int): String {
        return if (lines == 2160) "4K" else lines.toString() + "p"
    }
}
