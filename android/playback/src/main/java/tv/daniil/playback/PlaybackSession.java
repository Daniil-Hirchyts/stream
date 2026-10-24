package tv.daniil.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.text.Subtitle;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns one screen's player: loads requests and their external subtitles, applies track preferences, gathers remote
 * seeks, explains errors, reports formats without a decoder and mirrors state to the system media session. Use it
 * on the main thread; subtitle downloads and parsing run in the background.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackSession {
    /** The player screen's view of the session. Calls arrive on the main thread. */
    interface Listener {
        /** New media was loaded, or the player stopped. */
        void onMediaChanged();
        void onPlaybackChanged();
        /** The visible cues may differ: new cues, a seek, or play and pause. */
        void onCuesChanged();
        void onTracksChanged();
        /** Remote seek presses add up to {@code deltaMs} so far; the seek follows once they pause. */
        void onSeekGathering(long deltaMs);
    }

    public static final long SEEK_STEP_MS = 10_000;
    /** A gathered seek waits this long for another press, so a stream fetches only where the presses stop. */
    private static final long SEEK_COMMIT_MS = 450;
    /** Presses this close together count as one jump. */
    private static final long SEEK_SETTLE_MS = 1200;
    private static final long PROGRESS_MS = 1000;
    private static final String PREF_SUBTITLES_ON = "subtitles_on", PREF_LANGUAGE = "subtitle_language";

    private final Context context;
    private final SharedPreferences prefs;
    private final String userAgent;
    private final PlaybackHost host;
    private final ExoPlayer player;
    @Nullable private final PlaybackDecoders decoders;
    private final MediaSession mediaSession;
    private final File subtitleRoot;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();

    @Nullable private PlaybackRequest request;
    /** Also read by subtitle downloads, which give up once a newer load or a stop replaced theirs. */
    private volatile int generation;
    private boolean started, renderedFirstFrame, reportedUnsupported, released;
    @Nullable private PlaybackErrors.Description error;
    private float speed = 1f;
    /** The voice-over the viewer chose, by name, kept from one episode to the next. */
    @Nullable private String voice;

    // Tracks, and what the request asked for once they are known.
    private List<SubtitleTracks.Track> textTracks = SubtitleTracks.none();
    private Map<String, Subtitle> pausedSubtitles = Collections.emptyMap();
    @Nullable private SubtitleTracks.Request subtitleRequest;
    private List<AudioTracks.Track> audioTracks = Collections.emptyList();
    private int audioRequest = -1;
    @Nullable private String audioLanguage;
    private List<VideoQualities.Option> qualities = Collections.emptyList();
    @Nullable private VideoQualities.Option quality;

    private long seekTarget = C.TIME_UNSET, seekDelta;
    private boolean seekPending;

    private final Runnable commitSeek = this::commitSeek;
    private final Runnable endSeek = () -> {
        seekTarget = C.TIME_UNSET;
        seekDelta = 0;
    };
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (player.getMediaItemCount() > 0) {
                updateMediaSession();
                if (player.isPlaying()) host.onProgress(snapshot());
            }
            handler.postDelayed(this, PROGRESS_MS);
        }
    };

    /**
     * @param prefs where subtitle choices are remembered: {@code subtitles_on}, {@code subtitle_language} and the
     *              {@link SubtitleStyle} keys.
     * @param name the system media session's tag.
     * @param userAgent sent with HTTP requests unless a request supplies its own.
     */
    public PlaybackSession(Context context, SharedPreferences prefs, String name, String userAgent, PlaybackHost host) {
        this(context, prefs, name, userAgent, host, 0);
    }

    /** Hosts with a constrained streaming budget may cap compressed Media3 buffers; zero keeps defaults. */
    public PlaybackSession(Context context, SharedPreferences prefs, String name, String userAgent, PlaybackHost host, int bufferBytes) {
        this(context, prefs, name, userAgent, host, bufferBytes, null);
    }

    /** Apps may bundle additional decoders without changing the default platform-only player. */
    public PlaybackSession(Context context, SharedPreferences prefs, String name, String userAgent, PlaybackHost host,
            int bufferBytes, @Nullable PlaybackDecoders decoders) {
        this.context = context;
        this.prefs = prefs;
        this.userAgent = userAgent;
        this.host = host;
        this.decoders = decoders;
        subtitleRoot = new File(new File(context.getCacheDir(), "subtitles"), java.util.UUID.randomUUID().toString());
        Capabilities.load(context);
        // Decoders hidden for testing stay hidden from playback too.
        ExoPlayer.Builder builder = new ExoPlayer.Builder(context, (decoders == null ? new SubtitlePlayback(context) : decoders.renderers(context))
                .setMediaCodecSelector(Capabilities.selector()).setEnableDecoderFallback(true));
        if (bufferBytes > 0) builder.setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setTargetBufferBytes(bufferBytes).setPrioritizeTimeOverSizeThresholds(false)
                .setBufferDurationsMs(3000, 15_000, 1000, 1000).build());
        player = builder.build();
        if (decoders != null) decoders.attach(player);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) started = true;
                if (state == Player.STATE_ENDED) {
                    host.onEnded();
                    reportProgress();
                }
                notifyChanged();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                notifyChanged();
                for (Listener listener : listeners()) listener.onCuesChanged();
                if (!playing) reportProgress();
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition, int reason) {
                for (Listener listener : listeners()) listener.onCuesChanged();
                if (reason == Player.DISCONTINUITY_REASON_SEEK) reportProgress();
            }
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                notifyChanged();
            }
            @Override public void onPlayerError(PlaybackException exception) {
                if (exception.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // A live stream moved on while paused: rejoin it rather than stop.
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                if (decoders != null && decoders.recover(exception)) return;
                fail(PlaybackErrors.describe(context, exception));
            }
            @Override public void onRenderedFirstFrame() {
                renderedFirstFrame = true;
                notifyChanged();
            }
            @Override public void onTracksChanged(Tracks tracks) {
                checkSupport(tracks);
                updateTracks(tracks);
            }
            @Override public void onCues(CueGroup cues) {
                for (Listener listener : listeners()) listener.onCuesChanged();
            }
        });
        mediaSession = new MediaSession(context, name);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { player.play(); }
            @Override public void onPause() { player.pause(); }
            @Override public void onStop() { host.onExit(); }
            @Override public void onSeekTo(long position) { seekTo(position); }
            @Override public void onFastForward() { seekBy(SEEK_STEP_MS); }
            @Override public void onRewind() { seekBy(-SEEK_STEP_MS); }
            @Override public void onSkipToNext() { host.onNextEpisode(); }
            @Override public void onSkipToPrevious() { host.onPreviousEpisode(); }
        });
        mediaSession.setActive(true);
        handler.post(progress);
    }

    // Loading

    /** Replaces the current media. External subtitles download first, for at most eight seconds. */
    public void load(PlaybackRequest next) {
        reportProgress();
        int load = ++generation;
        request = next;
        pausedSubtitles = Collections.emptyMap();
        if (!next.continuing) {
            voice = null;
            speed = 1f;
        }
        audioRequest = next.audioIndex;
        audioLanguage = next.audioLanguage;
        subtitleRequest = next.subtitleRequest;
        error = null;
        started = false;
        renderedFirstFrame = false;
        reportedUnsupported = false;
        quality = null;
        qualities = Collections.emptyList();
        cancelSeek();
        player.stop();
        player.clearMediaItems();
        if (decoders != null) decoders.reset();
        SubtitleTracks.reset(player);
        AudioTracks.reset(player);
        VideoQualities.reset(player);
        player.setPlaybackSpeed(speed);
        for (Listener listener : listeners()) listener.onMediaChanged();
        MediaItem.Builder media = new MediaItem.Builder().setUri(next.uri)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(next.title).build());
        if (next.mimeType != null && !next.mimeType.isEmpty()) media.setMimeType(next.mimeType);
        if (next.subtitles.isEmpty()) {
            play(media, next);
            return;
        }
        background.execute(() -> {
            if (load != generation) return; // Replaced before it began.
            List<MediaItem.SubtitleConfiguration> downloaded =
                    SubtitleDownloader.download(subtitleFolder(load), next.subtitles, next.subtitleHeaders);
            Map<String, Subtitle> paused = PausedSubtitleCues.read(downloaded);
            handler.post(() -> {
                if (load != generation || released) return; // A newer load replaced this one.
                pausedSubtitles = paused;
                media.setSubtitleConfigurations(downloaded);
                play(media, next);
            });
        });
        notifyChanged();
    }

    private void play(MediaItem.Builder media, PlaybackRequest next) {
        // Media3 applies its own User-Agent after the default properties, so the request's must be set here.
        // Some CDNs answer HTTP 410 to an unfamiliar one.
        Map<String, String> properties = new HashMap<>(next.headers);
        String agent = properties.remove("User-Agent");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(agent == null ? userAgent : agent)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
                .setDefaultRequestProperties(properties);
        // DefaultDataSource adds file:// for the downloaded subtitles.
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(context, http);
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(next.dataSource == null ? data : next.dataSource);
        player.setMediaSource(SubtitlePlayback.create(sources, data, media.build()));
        player.seekTo(next.positionMs);
        player.prepare();
        player.play();
        notifyChanged();
    }

    /**
     * Each load downloads into its own folder and removes older ones, so a slow download for replaced media can
     * never delete the files of the media now playing. Runs on the background thread, one load at a time.
     */
    private File subtitleFolder(int load) {
        String name = String.valueOf(load);
        File[] old = subtitleRoot.listFiles();
        if (old != null) for (File item : old) if (!item.getName().equals(name)) delete(item);
        return new File(subtitleRoot, name);
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    /** Stops and clears the media, for example while the next version is still being prepared elsewhere. */
    public void stop() {
        reportProgress();
        generation++;
        request = null;
        pausedSubtitles = Collections.emptyMap();
        error = null;
        started = false;
        renderedFirstFrame = false;
        reportedUnsupported = false;
        cancelSeek();
        player.stop();
        player.clearMediaItems();
        for (Listener listener : listeners()) listener.onMediaChanged();
        notifyChanged();
    }

    /** Stops loading and shows an error that needs the viewer: a failed stream, or a problem the app found. */
    public void fail(PlaybackErrors.Description description) {
        generation++;
        error = description;
        cancelSeek();
        player.stop();
        notifyChanged();
    }

    public void release() {
        if (released) return;
        reportProgress();
        released = true;
        generation++;
        handler.removeCallbacksAndMessages(null);
        background.shutdownNow();
        Thread cleanup = new Thread(() -> {
            try { if (background.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) delete(subtitleRoot); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "subtitle-cleanup");
        cleanup.setDaemon(true); cleanup.start();
        listeners.clear();
        player.stop();
        if (decoders != null) decoders.release();
        player.release();
        mediaSession.release();
    }

    /**
     * A format Media3 can't decode would play as sound over a black screen, or as a silent picture. The app decides
     * instead: convert it, or offer another source.
     */
    private void checkSupport(Tracks tracks) {
        if (reportedUnsupported || error != null) return;
        boolean hasVideo = false, video = false, hasAudio = false, audio = false;
        String videoMime = null, audioMime = null;
        for (Tracks.Group group : tracks.getGroups()) {
            String mime = group.length > 0 ? group.getTrackFormat(0).sampleMimeType : null;
            if (group.getType() == C.TRACK_TYPE_VIDEO) {
                hasVideo = true;
                video |= group.isSupported();
                if (mime != null) videoMime = mime;
            } else if (group.getType() == C.TRACK_TYPE_AUDIO) {
                hasAudio = true;
                audio |= group.isSupported();
                if (mime != null) audioMime = mime;
            }
        }
        String format = hasVideo && !video ? Capabilities.name(videoMime)
                : hasAudio && !audio ? Capabilities.name(audioMime) : null;
        if (format == null) return;
        reportedUnsupported = true;
        host.onUnsupportedFormat(format);
    }

    /** Applies what the request asked for once the tracks are known. */
    private void updateTracks(Tracks tracks) {
        audioTracks = AudioTracks.from(context, tracks, request == null ? Collections.emptyList() : request.audioNames);
        if (!audioTracks.isEmpty() && (audioRequest >= 0 || audioLanguage != null)) {
            AudioTracks.Track wanted = audioRequest >= 0 ? AudioTracks.numbered(audioTracks, audioRequest) : null;
            if (wanted != null) voice = wanted.label;
            else if (audioLanguage != null) wanted = AudioTracks.spoken(audioTracks, audioLanguage);
            audioRequest = -1;
            audioLanguage = null;
            if (wanted != null && !wanted.selected) AudioTracks.select(player, wanted);
        }
        textTracks = SubtitleTracks.from(context, tracks);
        if (subtitleRequest != null && !textTracks.isEmpty()) {
            SubtitleTracks.Request wanted = subtitleRequest;
            subtitleRequest = null;
            SubtitleTracks.select(player, SubtitleTracks.initial(textTracks, wanted,
                    prefs.getBoolean(PREF_SUBTITLES_ON, true), prefs.getString(PREF_LANGUAGE, null)));
        }
        qualities = VideoQualities.from(tracks);
        if (quality != null) quality = VideoQualities.find(qualities, quality.id);
        for (Listener listener : listeners()) listener.onTracksChanged();
        host.onPlaybackChanged(snapshot());
    }

    // Controls

    public void play() {
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
        player.play();
    }

    public void pause() {
        player.pause();
    }

    public void togglePlay() {
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekTo(0);
            player.play();
        } else if (player.getPlayWhenReady()) {
            player.pause();
        } else {
            player.play();
        }
    }

    public void seekTo(long positionMs) {
        cancelSeek();
        player.seekTo(Math.max(0, positionMs));
    }

    /**
     * Repeated presses add up, so holding a button scrubs. The seek itself waits for the presses to pause, so a
     * stream fetches the final position rather than every step on the way.
     */
    public void seekBy(long deltaMs) {
        if (!started || error != null) return;
        long base = seekTarget != C.TIME_UNSET ? seekTarget : player.getCurrentPosition();
        long target = Math.max(0, base + deltaMs);
        long duration = player.getDuration();
        if (duration != C.TIME_UNSET) target = Math.min(target, Math.max(0, duration - 1000));
        seekDelta += target - base;
        seekTarget = target;
        seekPending = true;
        handler.removeCallbacks(commitSeek);
        handler.postDelayed(commitSeek, SEEK_COMMIT_MS);
        handler.removeCallbacks(endSeek);
        handler.postDelayed(endSeek, SEEK_SETTLE_MS);
        for (Listener listener : listeners()) listener.onSeekGathering(seekDelta);
    }

    private void commitSeek() {
        seekPending = false;
        if (seekTarget != C.TIME_UNSET) player.seekTo(seekTarget);
    }

    private void cancelSeek() {
        handler.removeCallbacks(commitSeek);
        handler.removeCallbacks(endSeek);
        seekPending = false;
        seekTarget = C.TIME_UNSET;
        seekDelta = 0;
    }

    /** Where playback is, or where gathered seek presses will take it. */
    public long position() {
        return seekPending ? seekTarget : player.getCurrentPosition();
    }

    public void selectAudio(AudioTracks.Track track) {
        voice = track.label;
        AudioTracks.select(player, track);
        notifyChanged();
    }

    /** Shows one subtitle track, or none, and remembers for the next media whether and in which language. */
    public void selectSubtitles(@Nullable SubtitleTracks.Track track) {
        SubtitleTracks.select(player, track);
        SharedPreferences.Editor editor = prefs.edit().putBoolean(PREF_SUBTITLES_ON, track != null);
        if (track != null && track.language != null) editor.putString(PREF_LANGUAGE, track.language);
        editor.apply();
        notifyChanged();
    }

    /** Fixes the quality, or lets Media3 adapt again when {@code option} is null. */
    public void selectQuality(@Nullable VideoQualities.Option option) {
        quality = option;
        VideoQualities.select(player, option);
        notifyChanged();
    }

    public void setSpeed(float value) {
        speed = Math.max(0.25f, Math.min(4f, value));
        player.setPlaybackSpeed(speed);
        notifyChanged();
    }

    // State

    public ExoPlayer player() {
        return player;
    }

    public SharedPreferences preferences() {
        return prefs;
    }

    @Nullable public PlaybackRequest request() {
        return request;
    }

    public PlaybackSnapshot snapshot() {
        return new PlaybackSnapshot(this, player);
    }

    public boolean isLoaded() {
        return player.getMediaItemCount() > 0;
    }

    public int generation() {
        return generation;
    }

    public boolean started() {
        return started;
    }

    public boolean renderedFirstFrame() {
        return renderedFirstFrame;
    }

    @Nullable public PlaybackErrors.Description error() {
        return error;
    }

    public float speed() {
        return speed;
    }

    /** The voice-over the viewer chose or the request named, by label; null for the stream's default. */
    @Nullable public String voice() {
        return voice;
    }

    public List<AudioTracks.Track> audioTracks() {
        return audioTracks;
    }

    public List<SubtitleTracks.Track> textTracks() {
        return textTracks;
    }

    public List<VideoQualities.Option> qualities() {
        return qualities;
    }

    @Nullable public VideoQualities.Option quality() {
        return quality;
    }

    /** The lines of the picture now playing, such as 1080, or 0 before there is one. */
    int playingLines() {
        Format format = player.getVideoFormat();
        return format == null ? 0 : VideoQualities.lines(format.width, format.height);
    }

    /**
     * The cues to show now. The text renderer waits for playback to resume before outputting a new legacy cue after
     * a paused seek, so while paused they come from the downloaded file's own timeline.
     */
    List<Cue> cues() {
        List<Cue> cues = player.getCurrentCues().cues;
        if (!player.getPlayWhenReady()) {
            Subtitle timeline = PausedSubtitleCues.find(pausedSubtitles, SubtitleTracks.selected(textTracks));
            if (timeline != null) cues = timeline.getCues(player.getCurrentPosition() * 1000);
        }
        return cues;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    private List<Listener> listeners() {
        return new ArrayList<>(listeners);
    }

    /** Tells the screen and the app that something they show changed. */
    void notifyChanged() {
        if (released) return;
        for (Listener listener : listeners()) listener.onPlaybackChanged();
        updateMediaSession();
        host.onPlaybackChanged(snapshot());
    }

    private void reportProgress() {
        if (!released && started && player.getMediaItemCount() > 0) host.onProgress(snapshot());
    }

    private void updateMediaSession() {
        boolean playing = player.isPlaying();
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        player.getCurrentPosition(), playing ? speed : 0).build());
    }
}
