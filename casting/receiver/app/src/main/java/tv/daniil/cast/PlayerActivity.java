package tv.daniil.cast;

import android.app.Activity;
import android.content.SharedPreferences;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

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
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plays what a paired Mac sends, and lets the remote change episode, audio, subtitles, quality and speed. */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity implements CastHub.Screen, MenuPanel.Listener, Pairing.Listener {
    private static final long SEEK_STEP_MS = 10_000;
    private static final long SEEK_SETTLE_MS = 1200;
    private static final long PREVIEW_MS = 4000;
    private static final long NOTICE_MS = 4000;
    /** How long a format this TV can't decode waits for the Mac to offer a converted version. */
    private static final long CONVERSION_WAIT_MS = 20_000;
    private static final int UP_NEXT_SECONDS = 10;
    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final String PREF_SUBTITLES_ON = "subtitles_on", PREF_LANGUAGE = "subtitle_language";
    // What the loading screen says while there is no picture yet.
    private static final String STEP_OPENING = "opening", STEP_CONVERTING = "converting", STEP_PREPARING = "preparing";
    // Back is routed through onBackPressed/the system dispatcher on every Android version.
    private static final Set<Integer> REMOTE_KEYS = new HashSet<>(Arrays.asList(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS));

    private ExoPlayer player;
    private PlayerView playerView;
    private SubtitleView subtitles;
    private List<Cue> sample;
    private View idleView, idleContent, loadingView, errorView, errorRetry, bufferingView, pairingView, upNextView;
    private TextView loadingStep, loadingTitle, loadingMessage, errorTitle, errorMessage, pairingCode, idleDevice;
    private TextView notice, upNextTitle, upNextDetail;
    private ControlsOverlay controls;
    private MenuPanel menu;
    private SubtitleStyle style;
    private SharedPreferences prefs;
    private Pairing pairing;
    private MediaSession session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private String version = "";
    private boolean visible, screenKeptOn;

    // The current video.
    private String title = "";
    /** The command that loaded it, kept so "Try again" can repeat it. */
    @Nullable private JSONObject source;
    @Nullable private Catalog catalog;
    @Nullable private Catalog.Episode episode;
    @Nullable private PlaybackErrors.Description failure;
    /** Set while there is no picture yet: opening, converting on the Mac, or waiting for a converted version. */
    @Nullable private String step;
    /** The format being converted, or that this TV can't decode, such as "AV1". */
    @Nullable private String stepFormat, unsupportedFormat;
    @Nullable private String convertedFrom;
    private boolean started;
    // Set once a video frame is on screen; the extension pauses Chrome only then, not on audio alone.
    private boolean renderedVideo;
    private int generation;
    private float speed = 1f;

    // Tracks, and what Chrome asked for once they are known.
    private List<SubtitleTracks.Track> textTracks = SubtitleTracks.none();
    private Map<String, androidx.media3.extractor.text.Subtitle> pausedSubtitles = Collections.emptyMap();
    @Nullable private SubtitleTracks.Request subtitleRequest;
    private List<AudioTracks.Track> audioTracks = new ArrayList<>();
    // Voice-over names by the page's index, and the one chosen in Chrome (-1: the stream's default).
    private List<String> audioNames = new ArrayList<>();
    private int audioRequest = -1;
    /** The voice-over the viewer chose, by name, kept from one episode to the next. */
    @Nullable private String voice;
    private List<VideoQualities.Option> qualities = new ArrayList<>();
    @Nullable private VideoQualities.Option quality;

    private long previewUntil;
    private long seekTarget = C.TIME_UNSET, seekDelta;
    @Nullable private Catalog.Episode upNext;
    private int upNextSeconds;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            publishStatus();
            renderPairing();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable endSeek = () -> { seekTarget = C.TIME_UNSET; seekDelta = 0; };
    private final Runnable showCues = this::showCues;
    private final Runnable hideNotice = () -> Ui.fade(notice, false);
    private final Runnable countdown = this::countDown;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.player_view);
        idleView = findViewById(R.id.idle);
        idleContent = findViewById(R.id.idle_content);
        idleDevice = findViewById(R.id.idle_device);
        loadingView = findViewById(R.id.loading);
        loadingStep = findViewById(R.id.loading_step);
        loadingTitle = findViewById(R.id.loading_title);
        loadingMessage = findViewById(R.id.loading_message);
        bufferingView = findViewById(R.id.buffering);
        errorView = findViewById(R.id.error);
        errorTitle = findViewById(R.id.error_title);
        errorMessage = findViewById(R.id.error_message);
        errorRetry = findViewById(R.id.error_retry);
        pairingView = findViewById(R.id.pairing);
        pairingCode = findViewById(R.id.pairing_code);
        notice = findViewById(R.id.notice);
        upNextView = findViewById(R.id.up_next);
        upNextTitle = findViewById(R.id.up_next_title);
        upNextDetail = findViewById(R.id.up_next_detail);
        subtitles = findViewById(R.id.subtitles);
        playerView.getSubtitleView().setVisibility(View.GONE);
        // The gap below subtitles is a fraction of the view's height, known only after layout.
        subtitles.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) layoutSubtitles();
        });
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { /* Our own package is always there. */ }

        prefs = getSharedPreferences("player", MODE_PRIVATE);
        style = new SubtitleStyle(prefs);
        style.applyTo(subtitles);
        sample = Collections.singletonList(new Cue.Builder().setText(getString(R.string.subtitle_sample)).build());

        Capabilities.load(this);
        // Decoders hidden for testing stay hidden from playback too.
        player = new ExoPlayer.Builder(this, new SubtitlePlayback(this)
                .setMediaCodecSelector(Capabilities.selector()).setEnableDecoderFallback(true)).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        playerView.setPlayer(player);
        controls = new ControlsOverlay(this, player, handler, this::layoutSubtitles);
        menu = new MenuPanel(this, this);
        bindPointerControls();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !started) {
                    started = true;
                    if (unsupportedFormat == null) step = null;
                }
                if (state == Player.STATE_ENDED) offerNextEpisode();
                // The up-next card replaces the overlay at the end of an episode.
                if (started && upNext == null) controls.onPlaybackChanged();
                render();
                publishStatus();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                render();
                showCues();
                publishStatus();
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition, int reason) { showCues(); }
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (started && upNext == null) controls.onPlaybackChanged();
            }
            @Override public void onPlayerError(PlaybackException exception) {
                if (exception.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // A live stream moved on while paused: rejoin it rather than stop.
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                fail(PlaybackErrors.describe(PlayerActivity.this, exception));
            }
            @Override public void onRenderedFirstFrame() {
                renderedVideo = true;
                publishStatus();
            }
            @Override public void onTracksChanged(Tracks tracks) {
                checkSupport(tracks);
                updateTracks(tracks);
            }
            @Override public void onCues(CueGroup cues) { showCues(); }
        });
        session = new MediaSession(this, "Daniil Cast");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { player.play(); }
            @Override public void onPause() { player.pause(); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long pos) { player.seekTo(Math.max(0, pos)); }
            @Override public void onFastForward() { seekBy(SEEK_STEP_MS); }
            @Override public void onRewind() { seekBy(-SEEK_STEP_MS); }
            @Override public void onSkipToNext() { playAdjacent(true); }
            @Override public void onSkipToPrevious() { playAdjacent(false); }
        });
        session.setActive(true);

        pairing = Pairing.get(this);
        pairing.addListener(this);
        CastService.start(this);
        render();
        CastHub.attach(this);
        handler.post(tick);
    }

    @Override public void onStart() {
        super.onStart();
        visible = true;
        renderPairing();
    }

    @Override public boolean isVisible() {
        return visible;
    }

    // Commands from the paired Mac

    @Override public JSONObject command(JSONObject command) throws Exception {
        String action = command.optString("action");
        switch (action) {
            case "load": load(command); break;
            case "prepare": prepare(command); break;
            case "fail": failFromMac(command); break;
            case "play":
                if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
                player.play();
                break;
            case "pause": player.pause(); break;
            case "seek":
                player.seekTo(Math.max(0, command.getLong("position")));
                if (started) controls.show();
                break;
            case "stop": stopPlayback(); break;
            case "retry": retry(); break;
            case "audio": {
                AudioTracks.Track track = AudioTracks.find(audioTracks, command.optString("track"));
                if (track == null) throw new CastServer.Failure(404, "no-track", "That audio track isn't available");
                chooseAudio(track);
                break;
            }
            case "subtitles": applySubtitleCommand(command); break;
            case "quality": {
                String id = command.optString("id");
                VideoQualities.Option option = VideoQualities.find(qualities, id);
                if (option == null && !VideoQualities.AUTO.equals(id)) {
                    throw new CastServer.Failure(404, "no-quality", "That quality isn't available");
                }
                chooseQuality(option);
                break;
            }
            case "speed": setSpeed((float) command.getDouble("value")); break;
            case "episode": {
                Catalog.Episode chosen = catalog == null ? null : catalog.find(command.optString("id"));
                if (chosen == null) throw new CastServer.Failure(404, "no-episode", "That episode isn't in this series");
                playEpisode(chosen);
                break;
            }
            case "next":
                if (!playAdjacent(true)) throw new CastServer.Failure(404, "no-episode", "This is the last episode");
                break;
            case "previous":
                if (!playAdjacent(false)) throw new CastServer.Failure(404, "no-episode", "This is the first episode");
                break;
            default: throw new CastServer.Failure(400, "unknown-command", "Unknown command " + action);
        }
        render();
        publishStatus();
        return new JSONObject().put("ok", true);
    }

    private void load(JSONObject command) throws Exception {
        String url = command.optString("url");
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new CastServer.Failure(400, "bad-url", getString(R.string.error_url));
        }
        int load = ++generation;
        pausedSubtitles = Collections.emptyMap();
        String supplied = command.optString("title").trim();
        title = supplied.isEmpty() ? getString(R.string.default_title) : supplied;
        Map<String, String> headers = requestHeaders(command.optJSONObject("headers"));
        // A video converted on the Mac drops the page's headers; its subtitle files still need them.
        JSONObject pageHeaders = command.optJSONObject("subtitleHeaders");
        Map<String, String> subtitleHeaders = pageHeaders == null ? headers : requestHeaders(pageHeaders);
        JSONArray files = command.optJSONArray("subtitles");
        List<SubtitleDownloader.Source> sources = SubtitleDownloader.parse(files == null ? null : files.toString());
        MediaItem.Builder media = new MediaItem.Builder().setUri(url)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
        String mime = command.optString("mime");
        if (!mime.isEmpty()) media.setMimeType(mime);
        long position = Math.max(0, command.optLong("position"));
        audioNames = names(command.optJSONArray("audioNames"));
        audioRequest = command.optInt("audio", -1);
        subtitleRequest = new SubtitleTracks.Request(command.has("subtitle") ? command.optString("subtitle") : null,
                command.optString("textLanguage", null), command.optString("textLabel", null));
        boolean sameSeries = command.optBoolean("sameSeries");
        if (command.has("catalog")) catalog = Catalog.parse(command.optJSONObject("catalog"));
        else if (!sameSeries) catalog = null;
        episode = catalog == null ? null : catalog.find(command.optString("episode"));
        if (!sameSeries) {
            voice = null;
            speed = 1f;
        }
        JSONObject converted = command.optJSONObject("converted");
        convertedFrom = converted == null ? null : converted.optString("from", "video");

        source = command;
        failure = null;
        started = false;
        renderedVideo = false;
        unsupportedFormat = null;
        step = convertedFrom != null ? STEP_CONVERTING : STEP_OPENING;
        stepFormat = convertedFrom;
        quality = null;
        qualities = new ArrayList<>();
        cancelUpNext();
        player.stop();
        player.clearMediaItems();
        SubtitleTracks.reset(player);
        AudioTracks.reset(player);
        VideoQualities.reset(player);
        player.setPlaybackSpeed(speed);
        controls.hide();
        controls.setTitle(title);
        if (sources.isEmpty()) {
            play(media, headers, position);
            return;
        }
        File directory = new File(getCacheDir(), "subtitles");
        background.execute(() -> {
            List<MediaItem.SubtitleConfiguration> downloaded =
                    SubtitleDownloader.download(directory, sources, subtitleHeaders);
            Map<String, androidx.media3.extractor.text.Subtitle> paused = PausedSubtitleCues.read(downloaded);
            handler.post(() -> {
                if (load != generation || player == null) return; // A newer cast replaced this one.
                pausedSubtitles = paused;
                media.setSubtitleConfigurations(downloaded);
                play(media, headers, position);
            });
        });
    }

    private void play(MediaItem.Builder media, Map<String, String> headers, long position) {
        // Media3 applies its own User-Agent after the default properties, so Chrome's must be set here.
        // Some CDNs answer HTTP 410 to an unfamiliar one.
        Map<String, String> properties = new HashMap<>(headers);
        String userAgent = properties.remove("User-Agent");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent == null ? "DaniilCast/" + version : userAgent)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
                .setDefaultRequestProperties(properties);
        // DefaultDataSource adds file:// for the downloaded subtitles.
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, http);
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(data);
        player.setMediaSource(SubtitlePlayback.create(sources, data, media.build()));
        player.seekTo(position);
        player.prepare();
        player.play();
        render();
        publishStatus();
    }

    /** The Mac is converting this video; show that, not the previous one. */
    private void prepare(JSONObject command) {
        generation++;
        pausedSubtitles = Collections.emptyMap();
        String supplied = command.optString("title").trim();
        if (!supplied.isEmpty()) title = supplied;
        player.stop();
        player.clearMediaItems();
        cancelUpNext();
        failure = null;
        started = false;
        renderedVideo = false;
        unsupportedFormat = null;
        step = STEP_CONVERTING;
        stepFormat = command.optString("format", "");
        controls.hide();
        controls.setTitle(title);
    }

    /** A problem found on the Mac, such as a conversion that couldn't start. */
    private void failFromMac(JSONObject command) {
        generation++;
        player.stop();
        fail(new PlaybackErrors.Description(command.optString("code", "mac"),
                command.optString("title", getString(R.string.error_generic_title)),
                command.optString("message", getString(R.string.error_generic)),
                command.optString("detail"), 0, command.optBoolean("retry")));
    }

    private void stopPlayback() {
        // Stop accepting network loads immediately, before Android's asynchronous
        // task removal. A new cast must open a fresh player, not this dying one.
        visible = false;
        CastHub.detach(this);
        pausedSubtitles = Collections.emptyMap();
        player.stop();
        player.clearMediaItems();
        finishAndRemoveTask();
    }

    private void retry() {
        if (source == null) return;
        try {
            long position = player.getCurrentPosition();
            JSONObject again = new JSONObject(source.toString());
            if (started && position > 0) again.put("position", position);
            again.put("sameSeries", true);
            load(again);
        } catch (Exception exception) {
            fail(PlaybackErrors.of(this, "open", R.string.error_open_title, R.string.error_generic,
                    exception.getClass().getSimpleName(), 0, false));
        }
        render();
    }

    /** Referer, Origin and User-Agent only, under their canonical names. */
    private static Map<String, String> requestHeaders(@Nullable JSONObject supplied) throws Exception {
        Map<String, String> headers = new HashMap<>();
        if (supplied == null) return headers;
        for (Iterator<String> names = supplied.keys(); names.hasNext();) {
            String name = names.next();
            String value = supplied.getString(name);
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) continue;
            for (String allowed : new String[] {"Referer", "Origin", "User-Agent"}) {
                if (name.equalsIgnoreCase(allowed)) headers.put(allowed, value);
            }
        }
        return headers;
    }

    private void fail(PlaybackErrors.Description description) {
        failure = description;
        step = null;
        cancelUpNext();
        errorTitle.setText(description.title);
        errorMessage.setText(description.message);
        errorRetry.setVisibility(description.retry && source != null ? View.VISIBLE : View.GONE);
        render();
        publishStatus();
    }

    /**
     * A format this TV can't decode, such as AV1, would play as sound over a black screen, or as a silent picture.
     * Wait instead: the Mac sees this in the status and sends a converted version. Only without one is it an error.
     */
    private void checkSupport(Tracks tracks) {
        if (unsupportedFormat != null || failure != null) return;
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
        if (convertedFrom != null) {
            fail(PlaybackErrors.of(this, "decoder", R.string.error_decoder_title, R.string.error_decoder,
                    "UNSUPPORTED_FORMAT · " + format, 0, false));
            return;
        }
        unsupportedFormat = format;
        step = STEP_PREPARING;
        player.pause();
        int waiting = generation;
        handler.postDelayed(() -> {
            if (waiting == generation && unsupportedFormat != null && failure == null) {
                player.stop();
                fail(PlaybackErrors.needsMac(this, format));
            }
        }, CONVERSION_WAIT_MS);
        render();
        publishStatus();
    }

    /** Shows exactly one of idle, preparing, error or the video itself. */
    private void render() {
        boolean failed = failure != null;
        boolean preparing = !failed && step != null;
        boolean loaded = player.getMediaItemCount() > 0;
        Ui.fade(idleView, !failed && !preparing && !loaded);
        Ui.fade(loadingView, preparing);
        Ui.fade(errorView, failed);
        bufferingView.setVisibility(!failed && !preparing && started
                && player.getPlaybackState() == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        if (failed || preparing || !started) controls.hide();
        if (preparing) {
            loadingTitle.setText(title);
            String format = stepFormat == null ? "" : stepFormat;
            if (STEP_CONVERTING.equals(step)) {
                loadingStep.setText(R.string.step_converting);
                loadingMessage.setText(getString(R.string.step_converting_body, format.isEmpty() ? "this video" : format));
            } else if (STEP_PREPARING.equals(step)) {
                loadingStep.setText(R.string.step_preparing);
                loadingMessage.setText(R.string.step_preparing_body);
            } else {
                loadingStep.setText(R.string.step_opening);
                loadingMessage.setText("");
            }
            loadingMessage.setVisibility(loadingMessage.getText().length() > 0 ? View.VISIBLE : View.GONE);
        }
        // The screensaver may start while idle or paused, never during a video or while it opens.
        keepScreenOn(preparing || player.isPlaying());
        renderPairing();
    }

    private void keepScreenOn(boolean on) {
        if (on == screenKeptOn) return;
        screenKeptOn = on;
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // Pairing

    /** The code stays on the idle screen until a Mac pairs; afterwards it shows only when a Mac asks. */
    private void renderPairing() {
        boolean idle = failure == null && step == null && player.getMediaItemCount() == 0;
        String code = pairing.activeCode();
        if (code == null && idle && visible && pairing.clientCount() == 0) code = pairing.showCode();
        if (code != null) pairingCode.setText(code.substring(0, 3) + " " + code.substring(3));
        Ui.fade(pairingView, code != null && !menu.isOpen() && failure == null);
        idleDevice.setText(getString(R.string.idle_device, CastService.deviceName(this), CastService.pairingAddress()));
    }

    @Override public void onPairingChanged(@Nullable String pairedClient) {
        if (pairedClient != null) notice(getString(R.string.paired_with, pairedClient));
        renderPairing();
        menu.refresh();
    }

    private void notice(String text) {
        notice.setText(text);
        Ui.fade(notice, true);
        handler.removeCallbacks(hideNotice);
        handler.postDelayed(hideNotice, NOTICE_MS);
    }

    // Episodes

    private void playEpisode(Catalog.Episode next) {
        if (catalog == null) return;
        try {
            Catalog.Version version = Catalog.pick(next, source == null ? null : source.optString("kind"));
            JSONObject command = new JSONObject().put("action", "load").put("url", version.url)
                    .put("mime", version.mime).put("kind", version.kind).put("codec", version.codec)
                    .put("title", catalog.title.isEmpty() ? title : catalog.title)
                    .put("headers", catalog.headers).put("subtitles", next.subtitles)
                    .put("audioNames", new JSONArray(next.audioNames))
                    .put("episode", next.id).put("sameSeries", true);
            // Keep the viewer's voice-over and subtitles from the last episode.
            int number = voice == null ? -1 : next.audioNames.indexOf(voice);
            if (number >= 0) command.put("audio", number);
            SubtitleTracks.Track shown = SubtitleTracks.selected(textTracks);
            if (shown == null && !textTracks.isEmpty()) {
                command.put("subtitle", "off");
            } else if (shown != null) {
                for (int i = 0; i < next.subtitles.length(); i++) {
                    JSONObject file = next.subtitles.optJSONObject(i);
                    if (file != null && shown.label.equals(file.optString("label"))) command.put("subtitle", String.valueOf(i));
                }
                command.put("textLabel", shown.label);
                if (shown.language != null) command.put("textLanguage", shown.language);
            }
            load(command);
        } catch (Exception exception) {
            fail(PlaybackErrors.of(this, "open", R.string.error_open_title, R.string.error_generic,
                    exception.getClass().getSimpleName(), 0, false));
        }
        render();
        publishStatus();
    }

    private boolean playAdjacent(boolean forward) {
        if (catalog == null || episode == null) return false;
        Catalog.Episode next = forward ? catalog.next(episode) : catalog.previous(episode);
        if (next == null) return false;
        playEpisode(next);
        return true;
    }

    private void offerNextEpisode() {
        if (catalog == null || episode == null || upNext != null) return;
        Catalog.Episode next = catalog.next(episode);
        if (next == null) return;
        upNext = next;
        upNextSeconds = UP_NEXT_SECONDS;
        controls.hide();
        upNextTitle.setText(getString(R.string.episode_label, next.number));
        Ui.fade(upNextView, true);
        countDown();
    }

    private void countDown() {
        if (upNext == null) return;
        if (upNextSeconds <= 0) {
            Catalog.Episode next = upNext;
            cancelUpNext();
            playEpisode(next);
            return;
        }
        upNextDetail.setText(getString(R.string.season_label, upNext.season.number) + "  ·  "
                + getString(R.string.up_next_countdown, upNextSeconds));
        upNextSeconds--;
        handler.postDelayed(countdown, 1000);
    }

    private void cancelUpNext() {
        handler.removeCallbacks(countdown);
        upNext = null;
        Ui.fade(upNextView, false);
    }

    private String episodeShort(Catalog.Episode item) {
        return getString(R.string.episode_short, item.season.number, item.number);
    }

    // Audio, subtitles, quality and speed

    private static List<String> names(@Nullable JSONArray items) {
        List<String> names = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) names.add(items.optString(i));
        return names;
    }

    /** Applies what Chrome asked for once the tracks are known, then mirrors them in the menu and overlay. */
    private void updateTracks(Tracks tracks) {
        audioTracks = AudioTracks.from(this, tracks, audioNames);
        if (audioRequest >= 0 && !audioTracks.isEmpty()) {
            AudioTracks.Track wanted = AudioTracks.numbered(audioTracks, audioRequest);
            audioRequest = -1;
            if (wanted != null) {
                voice = wanted.label;
                if (!wanted.selected) AudioTracks.select(player, wanted);
            }
        }
        textTracks = SubtitleTracks.from(this, tracks);
        if (subtitleRequest != null && !textTracks.isEmpty()) {
            SubtitleTracks.Request request = subtitleRequest;
            subtitleRequest = null;
            SubtitleTracks.select(player, SubtitleTracks.initial(textTracks, request,
                    prefs.getBoolean(PREF_SUBTITLES_ON, true), prefs.getString(PREF_LANGUAGE, null)));
        }
        qualities = VideoQualities.from(tracks);
        if (quality != null) quality = VideoQualities.find(qualities, quality.id);
        List<String> details = new ArrayList<>();
        if (episode != null) details.add(episodeShort(episode));
        AudioTracks.Track playing = AudioTracks.selected(audioTracks);
        if (audioTracks.size() > 1 && playing != null) details.add(playing.label);
        if (!textTracks.isEmpty()) details.add(getString(R.string.details_subtitles, subtitleSummary()));
        controls.setDetails(TextUtils.join("  ·  ", details));
        showCues();
        menu.refresh();
        publishStatus();
    }

    private void chooseAudio(AudioTracks.Track track) {
        voice = track.label;
        AudioTracks.select(player, track);
    }

    private void chooseSubtitles(@Nullable SubtitleTracks.Track track) {
        SubtitleTracks.select(player, track);
        // Remembered for the next video: whether subtitles are wanted, and in which language.
        SharedPreferences.Editor editor = prefs.edit().putBoolean(PREF_SUBTITLES_ON, track != null);
        if (track != null && track.language != null) editor.putString(PREF_LANGUAGE, track.language);
        editor.apply();
    }

    private void chooseQuality(@Nullable VideoQualities.Option option) {
        quality = option;
        VideoQualities.select(player, option);
    }

    private void setSpeed(float value) {
        speed = Math.max(0.25f, Math.min(4f, value));
        player.setPlaybackSpeed(speed);
    }

    private String subtitleSummary() {
        SubtitleTracks.Track shown = SubtitleTracks.selected(textTracks);
        return shown == null ? getString(R.string.track_off) : shown.label;
    }

    private String qualitySummary() {
        if (quality != null) return quality.label;
        int lines = playingLines();
        return lines > 0 ? getString(R.string.quality_auto_current, VideoQualities.label(lines)) : getString(R.string.quality_auto);
    }

    private int playingLines() {
        Format format = player.getVideoFormat();
        return format == null ? 0 : VideoQualities.lines(format.width, format.height);
    }

    private String speedLabel(float value) {
        if (value == 1f) return getString(R.string.speed_normal);
        String number = value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
        return getString(R.string.speed_value, number);
    }

    private String styleSummary() {
        return getString(SubtitleStyle.Setting.SIZE.labels[style.get(SubtitleStyle.Setting.SIZE)]) + " · "
                + getString(SubtitleStyle.Setting.COLOR.labels[style.get(SubtitleStyle.Setting.COLOR)]);
    }

    /** Track and style changes sent from the Chrome extension. */
    private void applySubtitleCommand(JSONObject command) {
        String track = command.optString("track", null);
        if ("off".equals(track)) {
            chooseSubtitles(null);
        } else if (track != null) {
            SubtitleTracks.Track chosen = SubtitleTracks.find(textTracks, track);
            if (chosen != null) chooseSubtitles(chosen);
        }
        boolean styled = false;
        for (SubtitleStyle.Setting setting : SubtitleStyle.Setting.values()) {
            String value = command.optString(setting.key, null);
            if (value != null) styled |= style.set(setting, value);
        }
        if (!styled) return;
        onSubtitleStyleChanged();
        menu.refresh();
        // Show the sample line for a moment, so the change is visible on the TV between cues.
        previewUntil = SystemClock.uptimeMillis() + PREVIEW_MS;
        handler.removeCallbacks(showCues);
        handler.postDelayed(showCues, PREVIEW_MS);
        showCues();
    }

    private void onSubtitleStyleChanged() {
        style.applyTo(subtitles);
        layoutSubtitles();
        publishStatus();
    }

    /** The current cues, or the sample line while the style is being changed and nothing else is shown. */
    private void showCues() {
        List<Cue> cues = player.getCurrentCues().cues;
        if (!player.getPlayWhenReady()) {
            androidx.media3.extractor.text.Subtitle timeline =
                    PausedSubtitleCues.find(pausedSubtitles, SubtitleTracks.selected(textTracks));
            if (timeline != null) cues = timeline.getCues(player.getCurrentPosition() * 1000);
        }
        boolean preview = menu.previewsSubtitles() || SystemClock.uptimeMillis() < previewUntil;
        subtitles.setCues(cues.isEmpty() && preview ? sample : cues);
    }

    /** Places subtitles at the chosen height, clear of the menu and the overlay's progress bar. */
    private void layoutSubtitles() {
        int right = menu.isOpen() ? getResources().getDimensionPixelSize(R.dimen.panel_width) : 0;
        int gap = Math.round(subtitles.getHeight() * style.bottomGap());
        int overlay = controls.isShown() ? getResources().getDimensionPixelSize(R.dimen.controls_height) : 0;
        subtitles.setPadding(0, 0, right, Math.max(gap, overlay));
    }

    // Menu pages

    private interface Items {
        List<MenuPanel.Item> get();
    }

    private static MenuPanel.Page page(String title, boolean preview, Items items) {
        return new MenuPanel.Page() {
            @Override public String title() { return title; }
            @Override public List<MenuPanel.Item> items() { return items.get(); }
            @Override public boolean previewsSubtitles() { return preview; }
        };
    }

    private MenuPanel.Page rootPage() {
        boolean loaded = player.getMediaItemCount() > 0;
        return page(getString(loaded ? R.string.menu_options : R.string.menu_settings), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            if (catalog != null && episode != null) {
                Catalog catalog = this.catalog;
                items.add(MenuPanel.Item.page(getString(R.string.menu_episode), episodeShort(episode), false,
                        () -> menu.push(catalog.seasons.size() > 1 ? seasonsPage() : episodesPage(episode.season))));
                Catalog.Episode next = catalog.next(episode);
                if (next != null) {
                    items.add(MenuPanel.Item.action(getString(R.string.menu_next_episode), episodeShort(next), () -> {
                        menu.close();
                        playEpisode(next);
                    }));
                }
            }
            if (audioTracks.size() > 1) {
                AudioTracks.Track playing = AudioTracks.selected(audioTracks);
                items.add(MenuPanel.Item.page(getString(R.string.menu_audio), playing == null ? null : playing.label,
                        false, () -> menu.push(audioPage())));
            }
            if (!textTracks.isEmpty()) {
                items.add(MenuPanel.Item.page(getString(R.string.menu_subtitles), subtitleSummary(), false,
                        () -> menu.push(subtitlesPage())));
            }
            if (!qualities.isEmpty()) {
                items.add(MenuPanel.Item.page(getString(R.string.menu_quality), qualitySummary(), false,
                        () -> menu.push(qualityPage())));
            }
            if (loaded) {
                items.add(MenuPanel.Item.page(getString(R.string.menu_speed), speedLabel(speed), false,
                        () -> menu.push(speedPage())));
            }
            items.add(MenuPanel.Item.page(getString(R.string.menu_style), styleSummary(), false,
                    () -> menu.push(stylePage())));
            if (!loaded) {
                items.add(MenuPanel.Item.action(getString(R.string.menu_pair), null, () -> {
                    menu.close();
                    pairing.showCode();
                }));
                int count = pairing.clientCount();
                items.add(MenuPanel.Item.page(getString(R.string.menu_paired),
                        count == 0 ? getString(R.string.none) : getString(R.string.count, count), false,
                        () -> menu.push(pairedPage())));
            }
            return items;
        });
    }

    private MenuPanel.Page seasonsPage() {
        return page(getString(R.string.menu_season), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            if (catalog == null) return items;
            for (Catalog.Season season : catalog.seasons) {
                items.add(MenuPanel.Item.page(getString(R.string.season_label, season.number),
                        getString(R.string.episodes_count, season.episodes.size()),
                        episode != null && episode.season == season, () -> menu.push(episodesPage(season))));
            }
            return items;
        });
    }

    private MenuPanel.Page episodesPage(Catalog.Season season) {
        boolean seasons = catalog != null && catalog.seasons.size() > 1;
        String heading = seasons ? getString(R.string.season_label, season.number) : getString(R.string.menu_episode);
        return page(heading, false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (Catalog.Episode item : season.episodes) {
                boolean current = item == episode;
                String value = current ? getString(R.string.playing_now)
                        : item.durationMs > 0 ? getString(R.string.minutes, Math.round(item.durationMs / 60_000f)) : null;
                items.add(MenuPanel.Item.choice(getString(R.string.episode_label, item.number), value, current, () -> {
                    menu.close();
                    if (item != episode) playEpisode(item);
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page audioPage() {
        return page(getString(R.string.menu_audio), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (AudioTracks.Track track : audioTracks) {
                items.add(MenuPanel.Item.choice(track.label, null, track.selected, () -> {
                    chooseAudio(track);
                    menu.pop();
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page subtitlesPage() {
        return page(getString(R.string.menu_subtitles), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            SubtitleTracks.Track shown = SubtitleTracks.selected(textTracks);
            items.add(MenuPanel.Item.choice(getString(R.string.track_off), null, shown == null, () -> {
                chooseSubtitles(null);
                menu.pop();
            }));
            for (SubtitleTracks.Track track : textTracks) {
                items.add(MenuPanel.Item.choice(track.label, null, track == shown, () -> {
                    chooseSubtitles(track);
                    menu.pop();
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page qualityPage() {
        return page(getString(R.string.menu_quality), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            int lines = playingLines();
            items.add(MenuPanel.Item.choice(getString(R.string.quality_auto),
                    quality == null && lines > 0 ? VideoQualities.label(lines) : null, quality == null, () -> {
                        chooseQuality(null);
                        menu.pop();
                    }));
            for (VideoQualities.Option option : qualities) {
                items.add(MenuPanel.Item.choice(option.label, null, quality != null && option.id.equals(quality.id), () -> {
                    chooseQuality(option);
                    menu.pop();
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page speedPage() {
        return page(getString(R.string.menu_speed), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (float value : SPEEDS) {
                items.add(MenuPanel.Item.choice(speedLabel(value), null, value == speed, () -> {
                    setSpeed(value);
                    menu.pop();
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page stylePage() {
        return page(getString(R.string.menu_style), true, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (SubtitleStyle.Setting setting : SubtitleStyle.Setting.values()) {
                items.add(MenuPanel.Item.page(getString(setting.title), getString(setting.labels[style.get(setting)]),
                        false, () -> menu.push(styleSettingPage(setting))));
            }
            return items;
        });
    }

    private MenuPanel.Page styleSettingPage(SubtitleStyle.Setting setting) {
        return page(getString(setting.title), true, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (int i = 0; i < setting.values.length; i++) {
                int index = i;
                items.add(MenuPanel.Item.choice(getString(setting.labels[i]), null, style.get(setting) == i, () -> {
                    if (style.set(setting, index)) onSubtitleStyleChanged();
                    menu.pop();
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page pairedPage() {
        return page(getString(R.string.menu_paired), false, () -> Collections.singletonList(
                MenuPanel.Item.action(getString(R.string.menu_forget), null, () -> {
                    pairing.forgetAll();
                    notice(getString(R.string.menu_forget_done));
                    menu.pop();
                })));
    }

    @Override public void onMenuToggled(boolean open) {
        if (open) controls.hide();
        // On the idle screen the left side becomes a clear stage for the sample line.
        Ui.fade(idleContent, !open);
        renderPairing();
        layoutSubtitles();
        showCues();
    }

    @Override public void onMenuPageChanged() {
        showCues();
    }

    /** Pointer actions use the same playback and menu paths as remote and Chrome commands. */
    private void bindPointerControls() {
        Ui.action(findViewById(R.id.idle_settings), () -> menu.open(rootPage()));
        Ui.action(findViewById(R.id.control_options), () -> menu.open(rootPage()));
        Ui.action(findViewById(R.id.center_icon), this::togglePlay);
        Ui.action(findViewById(R.id.control_play), this::togglePlay);
        Ui.action(findViewById(R.id.control_rewind), () -> seekBy(-SEEK_STEP_MS));
        Ui.action(findViewById(R.id.control_forward), () -> seekBy(SEEK_STEP_MS));
        Ui.action(findViewById(R.id.control_stop), this::stopPlayback);
        Ui.action(errorRetry, this::retry);
        Ui.action(findViewById(R.id.error_close), this::stopPlayback);
        Ui.action(findViewById(R.id.up_next_play), () -> {
            Catalog.Episode next = upNext;
            cancelUpNext();
            if (next != null) playEpisode(next);
        });
        Ui.action(findViewById(R.id.up_next_cancel), () -> {
            cancelUpNext();
            controls.show();
        });
        View.OnClickListener surface = view -> {
            if (menu.isOpen()) menu.close();
            else if (started && failure == null && step == null) {
                if (controls.isShown() && player.getPlayWhenReady()) controls.hide();
                else controls.show();
            }
        };
        findViewById(R.id.player_root).setOnClickListener(surface);
        findViewById(R.id.controls).setOnClickListener(surface);
        playerView.setOnClickListener(surface);
    }

    // Remote shortcuts

    @Override public void onBackPressed() {
        // Android 13+ may deliver Back through its dispatcher rather than as a
        // KeyEvent. Both paths must pop the menu before closing the player.
        if (!onRemoteKey(KeyEvent.KEYCODE_BACK)) finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && onRemoteKey(code)) return true;
        if (event.getAction() == KeyEvent.ACTION_UP && REMOTE_KEYS.contains(code)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean onRemoteKey(int code) {
        if (menu.isOpen() && menu.onKeyDown(code)) return true;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (upNext != null && code != KeyEvent.KEYCODE_MENU && code != KeyEvent.KEYCODE_CAPTIONS) {
            if (ok || code == KeyEvent.KEYCODE_BACK) {
                Catalog.Episode next = upNext;
                cancelUpNext();
                if (ok) playEpisode(next);
                else controls.show();
            }
            return true;
        }
        // A code asked for over a video goes away with Back; the idle screen keeps it until a Mac pairs.
        if (code == KeyEvent.KEYCODE_BACK && pairing.activeCode() != null && pairing.clientCount() > 0) {
            pairing.hideCode();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CAPTIONS: menu.open(rootPage()); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: player.play(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: player.pause(); return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT: playAdjacent(true); return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: playAdjacent(false); return true;
            default: break;
        }
        if (failure != null) {
            if (ok && failure.retry && source != null) {
                retry();
                return true;
            }
            return false;
        }
        // While idle or preparing, Back closes the app as usual.
        if (!started || step != null) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: togglePlay(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND: seekBy(-SEEK_STEP_MS); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekBy(SEEK_STEP_MS); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: controls.show(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (!controls.isShown() || !player.getPlayWhenReady()) return false;
                controls.hide();
                return true;
            default: return false;
        }
    }

    private void togglePlay() {
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekTo(0);
            player.play();
        } else if (player.getPlayWhenReady()) {
            player.pause();
        } else {
            player.play();
        }
        controls.show();
    }

    /** Repeated presses add up, so holding the button scrubs; the overlay shows the total jump. */
    private void seekBy(long delta) {
        if (!started || failure != null) return;
        long base = seekTarget != C.TIME_UNSET ? seekTarget : player.getCurrentPosition();
        long target = Math.max(0, base + delta);
        long duration = player.getDuration();
        if (duration != C.TIME_UNSET) target = Math.min(target, Math.max(0, duration - 1000));
        seekDelta += target - base;
        seekTarget = target;
        player.seekTo(target);
        controls.showSeek(seekDelta);
        handler.removeCallbacks(endSeek);
        handler.postDelayed(endSeek, SEEK_SETTLE_MS);
    }

    // Status for the paired Mac

    private String phase() {
        if (failure != null) return "error";
        if (step != null) return "preparing";
        if (player.getMediaItemCount() == 0) return "idle";
        int state = player.getPlaybackState();
        if (state == Player.STATE_ENDED) return "ended";
        if (state == Player.STATE_BUFFERING || !started) return "buffering";
        return player.getPlayWhenReady() ? "playing" : "paused";
    }

    private void publishStatus() {
        if (player == null) return;
        try {
            long duration = player.getDuration();
            JSONArray texts = new JSONArray();
            for (SubtitleTracks.Track track : textTracks) {
                texts.put(new JSONObject().put("id", track.id).put("label", track.label)
                        .put("language", track.language == null ? "" : track.language));
            }
            SubtitleTracks.Track shown = SubtitleTracks.selected(textTracks);
            JSONArray voices = new JSONArray();
            for (AudioTracks.Track track : audioTracks) voices.put(new JSONObject().put("id", track.id).put("label", track.label));
            AudioTracks.Track playing = AudioTracks.selected(audioTracks);
            JSONArray qualityList = new JSONArray();
            for (VideoQualities.Option option : qualities) {
                qualityList.put(new JSONObject().put("id", option.id).put("label", option.label));
            }
            Object current = episode == null ? JSONObject.NULL : new JSONObject().put("id", episode.id)
                    .put("season", episode.season.number).put("number", episode.number).put("label", episodeShort(episode));
            Object playedFrom = source == null ? JSONObject.NULL : new JSONObject()
                    .put("kind", source.optString("kind")).put("codec", source.optString("codec"))
                    .put("convertedFrom", convertedFrom == null ? "" : convertedFrom);
            JSONObject status = new JSONObject()
                    .put("app", "Daniil Cast").put("version", version).put("phase", phase()).put("title", title)
                    .put("step", step == null ? "" : step).put("stepFormat", stepFormat == null ? "" : stepFormat)
                    .put("needsConversion", unsupportedFormat != null)
                    .put("unsupportedFormat", unsupportedFormat == null ? "" : unsupportedFormat)
                    .put("error", failure == null ? JSONObject.NULL : failure.toJson())
                    .put("renderedVideo", renderedVideo).put("source", playedFrom)
                    .put("videoFormat", formatStatus(player.getVideoFormat()))
                    .put("audioFormat", formatStatus(player.getAudioFormat()))
                    .put("tracks", trackStatus())
                    .put("playing", player.isPlaying()).put("playWhenReady", player.getPlayWhenReady())
                    .put("position", player.getCurrentPosition()).put("duration", duration == C.TIME_UNSET ? 0 : duration)
                    .put("buffered", player.getBufferedPosition())
                    .put("textTracks", texts).put("textTrack", shown == null ? "off" : shown.id)
                    .put("audioTracks", voices).put("audioTrack", playing == null ? "" : playing.id)
                    .put("qualities", qualityList).put("quality", quality == null ? VideoQualities.AUTO : quality.id)
                    .put("speed", (double) speed).put("episode", current)
                    .put("catalog", catalog == null ? JSONObject.NULL : catalog.summary(episode))
                    .put("subtitleStyle", style.toJson())
                    .put("updatedAt", System.currentTimeMillis());
            CastHub.publish(this, status);
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(player.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            player.getCurrentPosition(), player.isPlaying() ? speed : 0).build());
        } catch (Exception ignored) { /* A status read must never interrupt playback. */ }
    }

    private static JSONObject formatStatus(@Nullable Format format) throws Exception {
        JSONObject json = new JSONObject();
        if (format == null) return json;
        return json.put("id", format.id).put("mime", format.sampleMimeType).put("codecs", format.codecs)
                .put("width", format.width).put("height", format.height).put("channels", format.channelCount);
    }

    /** Every track with its decoder support, to diagnose a video the TV can't play. */
    private JSONArray trackStatus() throws Exception {
        JSONArray tracks = new JSONArray();
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            for (int i = 0; i < group.length; i++) {
                tracks.put(formatStatus(group.getTrackFormat(i)).put("type", group.getType())
                        .put("supported", group.isTrackSupported(i)).put("selected", group.isTrackSelected(i)));
            }
        }
        return tracks;
    }

    @Override public void onStop() {
        super.onStop();
        visible = false;
        if (player != null) { player.pause(); publishStatus(); }
    }

    @Override public void onDestroy() {
        CastHub.detach(this);
        pairing.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        background.shutdownNow();
        if (player != null) { player.stop(); playerView.setPlayer(null); player.release(); player = null; }
        if (session != null) session.release();
        super.onDestroy();
    }
}
