package tv.daniil.playback;

import android.content.Context;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audio tracks of the current video, usually voice-overs. Streams often call them only "rus0" or "ru2"; the number
 * is the player's own index, so the page's names ("Eng.Original") can be put back on them.
 */
@OptIn(markerClass = UnstableApi.class)
public final class AudioTracks {
    private static final Pattern NUMBERED = Pattern.compile("[A-Za-z]{2,3}(\\d{1,2})");

    public static final class Track {
        public final String id, label;
        /** The page's index for this voice-over. */
        public final int number;
        @Nullable public final String language;
        public final boolean selected;
        final TrackGroup group;
        final List<Integer> indices;

        Track(String id, String label, int number, Format format, Tracks.Group group, List<Integer> indices) {
            this.id = id;
            this.label = label;
            this.number = number;
            this.language = format.language;
            this.selected = group.isSelected();
            this.group = group.getMediaTrackGroup();
            this.indices = indices;
        }
    }

    private AudioTracks() {}

    /** @param pageNames names by the page's index, or empty when the page gave none. */
    static List<Track> from(Context context, Tracks tracks, List<String> pageNames) {
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        List<Tracks.Group> all = tracks.getGroups();
        for (int g = 0; g < all.size(); g++) {
            if (all.get(g).getType() == C.TRACK_TYPE_AUDIO && all.get(g).isSupported()) {
                groups.add(all.get(g));
                positions.add(g);
            }
        }
        // Numbered names identify the page's voice-over; otherwise tracks follow the page's order.
        boolean numbered = !groups.isEmpty();
        for (Tracks.Group group : groups) numbered &= number(group.getTrackFormat(0)) >= 0;

        List<Track> list = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        Map<String, Integer> labels = new HashMap<>();
        for (int k = 0; k < groups.size(); k++) {
            Tracks.Group group = groups.get(k);
            Format format = group.getTrackFormat(0);
            int number = numbered ? number(format) : k;
            if (!numbers.add(number)) continue;
            String name = number < pageNames.size() ? pageNames.get(number).trim() : "";
            String label = name.isEmpty() ? label(context, format, k) : name;
            int seen = labels.containsKey(label) ? labels.get(label) + 1 : 1;
            labels.put(label, seen);
            if (seen > 1) label += " " + seen;
            List<Integer> indices = new ArrayList<>();
            for (int t = 0; t < group.length; t++) if (group.isTrackSupported(t)) indices.add(t);
            list.add(new Track(String.valueOf(positions.get(k)), label, number, format, group, indices));
        }
        return list;
    }

    @Nullable public static Track selected(List<Track> tracks) {
        for (Track track : tracks) if (track.selected) return track;
        return null;
    }

    @Nullable public static Track find(List<Track> tracks, String id) {
        for (Track track : tracks) if (track.id.equals(id)) return track;
        return null;
    }

    @Nullable static Track numbered(List<Track> tracks, int number) {
        for (Track track : tracks) if (track.number == number) return track;
        return null;
    }

    /** The first track in {@code language}, ignoring regions and the digits streams append ("rus2"). */
    @Nullable static Track spoken(List<Track> tracks, String language) {
        String wanted = SubtitleTracks.primary(language);
        if (wanted == null) return null;
        for (Track track : tracks) {
            String spoken = track.language == null ? null : SubtitleTracks.primary(track.language.replaceAll("\\d+$", ""));
            if (wanted.equals(spoken)) return track;
        }
        return null;
    }

    /** Lets the next video start on its own default track. */
    static void reset(Player player) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
    }

    static void select(Player player, Track track) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(track.group, track.indices)).build());
    }

    private static int number(Format format) {
        for (String value : new String[] {format.label, format.language}) {
            if (value == null) continue;
            Matcher matcher = NUMBERED.matcher(value.trim());
            if (matcher.matches()) return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }

    private static String label(Context context, Format format, int position) {
        String label = format.label == null ? "" : format.label.trim();
        if (!label.isEmpty() && !NUMBERED.matcher(label).matches()) return label;
        String language = format.language == null ? "" : format.language.replaceAll("\\d+$", "");
        if (!language.isEmpty() && !C.LANGUAGE_UNDETERMINED.equals(language)) {
            String name = Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH);
            if (!name.isEmpty() && !name.equalsIgnoreCase(language)) {
                return Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
        }
        return context.getString(R.string.audio_numbered, position + 1);
    }
}
