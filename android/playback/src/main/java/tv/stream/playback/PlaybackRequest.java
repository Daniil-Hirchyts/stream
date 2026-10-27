package tv.stream.playback;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One media item and everything the player needs before it starts. Immutable; see {@link Builder}. */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackRequest {
    /** The host's name for this media, such as a provider's video id; the player doesn't read it. */
    public final String id;
    public final String mediaIdentity;
    public final String title;
    /** A line under the title, such as "S1 E2"; empty when there is none. */
    public final String detail;
    public final Uri uri;
    @Nullable public final String mimeType;
    /** Sent with media requests over HTTP; a User-Agent replaces the session's own. */
    public final Map<String, String> headers;
    /** Sent with external subtitle requests, which may need the page's headers when the media doesn't. */
    public final Map<String, String> subtitleHeaders;
    public final List<SubtitleDownloader.Source> subtitles;
    public final SubtitleTracks.Request subtitleRequest;
    /** Audio track names by the source's own numbering, which pages use for voice-overs. */
    public final List<String> audioNames;
    /** The audio track to start with, by that numbering; -1 lets {@link #audioLanguage} or the stream decide. */
    public final int audioIndex;
    @Nullable public final String audioLanguage;
    public final long positionMs;
    /** Keeps the previous media's speed and chosen voice-over, as for the next episode of a series. */
    public final boolean continuing;
    public final boolean startPaused;
    /** Reads the media itself, such as from a torrent; null fetches {@link #uri} over HTTP. */
    @Nullable public final DataSource.Factory dataSource;

    private PlaybackRequest(Builder builder) {
        id = builder.id;
        mediaIdentity = builder.mediaIdentity == null ? builder.uri.toString() : builder.mediaIdentity;
        title = builder.title;
        detail = builder.detail;
        uri = builder.uri;
        mimeType = builder.mimeType;
        headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        subtitleHeaders = Collections.unmodifiableMap(new HashMap<>(
                builder.subtitleHeaders == null ? builder.headers : builder.subtitleHeaders));
        subtitles = Collections.unmodifiableList(new ArrayList<>(builder.subtitles));
        subtitleRequest = builder.subtitleRequest;
        audioNames = Collections.unmodifiableList(new ArrayList<>(builder.audioNames));
        audioIndex = builder.audioIndex;
        audioLanguage = builder.audioLanguage;
        positionMs = builder.positionMs;
        continuing = builder.continuing; startPaused = builder.startPaused;
        dataSource = builder.dataSource;
    }

    public static final class Builder {
        private final Uri uri;
        private final String title;
        private String id = "", detail = "";
        private String mediaIdentity;
        @Nullable private String mimeType, audioLanguage;
        private Map<String, String> headers = Collections.emptyMap();
        @Nullable private Map<String, String> subtitleHeaders;
        private List<SubtitleDownloader.Source> subtitles = Collections.emptyList();
        private SubtitleTracks.Request subtitleRequest = new SubtitleTracks.Request(null, null, null);
        private List<String> audioNames = Collections.emptyList();
        private int audioIndex = -1;
        private long positionMs;
        private boolean continuing, startPaused;
        @Nullable private DataSource.Factory dataSource;

        public Builder(Uri uri, String title) {
            this.uri = uri;
            this.title = title;
        }

        public Builder mediaIdentity(String value) { mediaIdentity = value; return this; }
        public Builder id(String value) { id = value; return this; }
        public Builder detail(String value) { detail = value; return this; }
        public Builder mimeType(@Nullable String value) { mimeType = value; return this; }
        public Builder headers(Map<String, String> value) { headers = value; return this; }
        public Builder subtitleHeaders(Map<String, String> value) { subtitleHeaders = value; return this; }
        public Builder subtitles(List<SubtitleDownloader.Source> value) { subtitles = value; return this; }
        public Builder subtitleRequest(SubtitleTracks.Request value) { subtitleRequest = value; return this; }
        public Builder audioNames(List<String> value) { audioNames = value; return this; }
        public Builder audioIndex(int value) { audioIndex = value; return this; }
        public Builder audioLanguage(@Nullable String value) { audioLanguage = value; return this; }
        public Builder position(long value) { positionMs = Math.max(0, value); return this; }
        public Builder startPaused(boolean value) { startPaused = value; return this; }
        public Builder continuing(boolean value) { continuing = value; return this; }
        public Builder dataSource(@Nullable DataSource.Factory value) { dataSource = value; return this; }

        public PlaybackRequest build() {
            return new PlaybackRequest(this);
        }
    }
}
