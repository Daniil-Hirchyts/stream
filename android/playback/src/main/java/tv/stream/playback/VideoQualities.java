package tv.stream.playback;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The video's qualities, named as viewers know them (1080p, 720p…), with "Auto" letting Media3 adapt. */
@OptIn(markerClass = UnstableApi.class)
public final class VideoQualities {
    public static final String AUTO = "auto";

    public static final class Option {
        public final String id, label;
        public final int lines;
        final TrackGroup group;
        final List<Integer> indices = new ArrayList<>();

        Option(String label, int lines, TrackGroup group) {
            this.id = String.valueOf(lines);
            this.label = label;
            this.lines = lines;
            this.group = group;
        }
    }

    private VideoQualities() {}

    /** Highest first; empty when there is nothing to choose. */
    static List<Option> from(Tracks tracks) {
        Tracks.Group chosen = null;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO || !group.isSupported()) continue;
            if (chosen == null || group.isSelected()) chosen = group;
        }
        if (chosen == null) return new ArrayList<>();
        Map<Integer, Option> options = new LinkedHashMap<>();
        for (int i = 0; i < chosen.length; i++) {
            if (!chosen.isTrackSupported(i)) continue;
            Format format = chosen.getTrackFormat(i);
            int lines = lines(format.width, format.height);
            if (lines <= 0) continue;
            Option option = options.get(lines);
            if (option == null) {
                option = new Option(label(lines), lines, chosen.getMediaTrackGroup());
                options.put(lines, option);
            }
            option.indices.add(i);
        }
        List<Option> list = new ArrayList<>(options.values());
        list.sort((a, b) -> b.lines - a.lines);
        return list.size() > 1 ? list : new ArrayList<>();
    }

    @Nullable public static Option find(List<Option> options, String id) {
        for (Option option : options) if (option.id.equals(id)) return option;
        return null;
    }

    /** Fixes the quality, or lets the player adapt again when {@code option} is null. */
    static void select(Player player, @Nullable Option option) {
        if (option == null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build());
        } else {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setOverrideForType(new TrackSelectionOverride(option.group, option.indices)).build());
        }
    }

    static void reset(Player player) {
        select(player, null);
    }

    /** Films are often cropped (1920 × 800), so the width counts too: 1920 wide is 1080p. */
    static int lines(int width, int height) {
        int lines = Math.max(height, Math.round(width * 9 / 16f));
        if (lines <= 0) return 0;
        if (lines >= 2000) return 2160;
        if (lines >= 1300) return 1440;
        if (lines >= 1000) return 1080;
        if (lines >= 650) return 720;
        if (lines >= 440) return 480;
        if (lines >= 330) return 360;
        return 240;
    }

    static String label(int lines) {
        return lines == 2160 ? "4K" : lines + "p";
    }
}
