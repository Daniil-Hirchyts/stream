package tv.daniil.playback;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** The current video's subtitle tracks, from its playlist or from external files, with readable names. */
@OptIn(markerClass = UnstableApi.class)
public final class SubtitleTracks {
    /** Format id given to external files, in request order: {@code side-0}, {@code side-1}… */
    static final String SIDELOADED = "side-";
    // Packagers often name tracks "stream_0" or "subs1"; the language reads better.
    private static final Pattern TECHNICAL_LABEL = Pattern.compile("(?i)(stream|track|text|subs?|subtitles?)[ _-]?\\d*");

    public static final class Track {
        public final String id, label;
        @Nullable public final String language;
        public final boolean selected, preferred;
        @Nullable final String formatId;
        @Nullable final TrackGroup group;
        public final boolean external;
        final int index;

        Track(String id, String label, Format format, Tracks.Group group, int index) {
            this.id = id;
            this.external = false;
            this.label = label;
            this.language = format.language;
            this.selected = group.isTrackSelected(index);
            this.preferred = (format.selectionFlags & (C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED)) != 0;
            this.formatId = format.id;
            this.group = group.getMediaTrackGroup();
            this.index = index;
        }
        Track(String id, String label, @Nullable String language, boolean selected) {
            this.id = id; this.label = label; this.language = language; this.selected = selected;
            preferred = false; formatId = id; group = null; index = -1; external = true;
        }
        Track(Track original, boolean selected) {
            id = original.id; label = original.label; language = original.language; this.selected = selected;
            preferred = original.preferred; formatId = original.formatId; group = original.group; index = original.index; external = original.external;
        }

    }

    /**
     * Subtitles wanted for new media: an external file by its index ("0") or "off", else a track's label or
     * language, such as what the browser was showing. All null leaves the choice to the viewer's last language.
     */
    public static final class Request {
        @Nullable public final String choice, language, label;

        public Request(@Nullable String choice, @Nullable String language, @Nullable String label) {
            this.choice = choice;
            this.language = language;
            this.label = label;
        }
    }

    private SubtitleTracks() {}

    static List<Track> from(Context context, Tracks tracks) {
        List<Track> list = new ArrayList<>();
        Map<String, Integer> labels = new HashMap<>();
        List<Tracks.Group> groups = tracks.getGroups();
        for (int g = 0; g < groups.size(); g++) {
            Tracks.Group group = groups.get(g);
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int t = 0; t < group.length; t++) {
                if (!group.isTrackSupported(t)) continue;
                Format format = group.getTrackFormat(t);
                // For HLS that doesn't declare its captions, Media3 assumes a CEA-608 channel. It has no name or
                // language and is almost always empty, including in video converted on the Mac.
                if (isClosedCaption(format) && primary(format.language) == null
                        && (format.label == null || format.label.trim().isEmpty())) continue;
                String label = label(context, format, list.size());
                int seen = labels.containsKey(label) ? labels.get(label) + 1 : 1;
                labels.put(label, seen);
                if (seen > 1) {
                    boolean fromPage = format.id != null && format.id.contains(SIDELOADED);
                    label = fromPage ? context.getString(R.string.track_from_page, label) : label + " " + seen;
                }
                list.add(new Track(g + "." + t, label, format, group, t));
            }
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

    /** Shows one track, or turns subtitles off when {@code track} is null. */
    static void select(Player player, @Nullable Track track) {
        TrackSelectionParameters.Builder parameters = player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null);
        if (track != null && track.group != null) parameters.addOverride(new TrackSelectionOverride(track.group, track.index));
        player.setTrackSelectionParameters(parameters.build());
    }

    /** Clears the previous video's choice before new media loads. */
    static void reset(Player player) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
    }

    /**
     * Picks subtitles for newly loaded media: the page's choice first, then the track the browser was showing,
     * then the viewer's last language. With subtitles switched on, a video that has any shows them.
     */
    @Nullable static Track initial(List<Track> tracks, Request request, boolean enabled, @Nullable String language) {
        if ("off".equals(request.choice)) return null;
        if (request.choice != null) {
            // Merging the video and subtitle sources prefixes each id with its source index: "2:side-1".
            String id = SIDELOADED + request.choice;
            for (Track track : tracks) {
                if (track.formatId != null && (track.formatId.equals(id) || track.formatId.endsWith(":" + id))) {
                    return track;
                }
            }
        }
        if (request.label != null && !request.label.isEmpty()) {
            for (Track track : tracks) if (track.label.equalsIgnoreCase(request.label)) return track;
        }
        Track hinted = matchLanguage(tracks, request.language);
        if (hinted != null) return hinted;
        if (!enabled || tracks.isEmpty()) return null;
        Track remembered = matchLanguage(tracks, language);
        if (remembered != null) return remembered;
        for (Track track : tracks) if (track.preferred) return track;
        return tracks.get(0);
    }

    @Nullable private static Track matchLanguage(List<Track> tracks, @Nullable String language) {
        String wanted = primary(language);
        if (wanted == null) return null;
        for (Track track : tracks) if (wanted.equals(primary(track.language))) return track;
        return null;
    }

    /** The language without its region, such as "en" for "en-US"; null when unknown. */
    @Nullable static String primary(@Nullable String language) {
        String normalized = Util.normalizeLanguageCode(language);
        if (normalized == null || normalized.isEmpty() || C.LANGUAGE_UNDETERMINED.equals(normalized)) return null;
        return normalized.split("-")[0];
    }

    private static String label(Context context, Format format, int position) {
        String label = format.label == null ? "" : format.label.trim();
        String language = primary(format.language) == null ? null : format.language;
        if (!label.isEmpty() && !(language != null && TECHNICAL_LABEL.matcher(label).matches())) return label;
        if (language != null) {
            String name = Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH);
            if (!name.isEmpty()) return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        if (isClosedCaption(format)) return context.getString(R.string.closed_captions);
        if (!label.isEmpty()) return label;
        return context.getString(R.string.track_numbered, position + 1);
    }

    private static boolean isClosedCaption(Format format) {
        String mime = format.sampleMimeType == null ? "" : format.sampleMimeType;
        String codecs = format.codecs == null ? "" : format.codecs;
        return mime.contains("cea-") || codecs.contains("cea-");
    }

    static List<Track> none() {
        return Collections.emptyList();
    }
}
