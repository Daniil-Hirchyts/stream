package tv.daniil.cast;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import tv.daniil.playback.AudioTracks;
import tv.daniil.playback.MenuPanel;
import tv.daniil.playback.PlaybackErrors;
import tv.daniil.playback.PlaybackHost;
import tv.daniil.playback.PlaybackRequest;
import tv.daniil.playback.PlaybackSession;
import tv.daniil.playback.PlaybackSnapshot;
import tv.daniil.playback.PlayerScreen;
import tv.daniil.playback.SubtitleDownloader;
import tv.daniil.playback.SubtitleStyle;
import tv.daniil.playback.SubtitleTracks;
import tv.daniil.playback.Ui;
import tv.daniil.playback.VideoQualities;

/**
 * Plays what a paired Mac sends, with mouse, touch and optional remote controls. The shared playback library plays
 * and draws the player; this activity translates the Mac's commands, reports status, and adds pairing, the idle
 * screen, series episodes and waiting for the Mac to convert a format the TV can't decode.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity implements CastHub.Screen, PlaybackHost, Pairing.Listener {
    /** How long a format this TV can't decode waits for the Mac to offer a converted version. */
    private static final long CONVERSION_WAIT_MS = 20_000;
    // What the loading screen says while there is no picture yet.
    private static final String STEP_OPENING = "opening", STEP_CONVERTING = "converting", STEP_PREPARING = "preparing";

    private PlaybackSession session;
    private PlayerScreen screen;
    private View idleView, idleContent, pairingView;
    private TextView pairingCode, idleDevice;
    private Pairing pairing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String version = "";
    private boolean visible;

    // The current video.
    private String title = "";
    /** The command that loaded it, kept so "Try again" can repeat it. */
    @Nullable private JSONObject source;
    @Nullable private Catalog catalog;
    @Nullable private Catalog.Episode episode;
    /** The format being converted, or that this TV can't decode, such as "AV1". */
    @Nullable private String stepFormat, unsupportedFormat;
    @Nullable private String convertedFrom;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            publishStatus();
            renderPairing();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_player);
        idleView = findViewById(R.id.idle);
        idleContent = findViewById(R.id.idle_content);
        idleDevice = findViewById(R.id.idle_device);
        pairingView = findViewById(R.id.pairing);
        pairingCode = findViewById(R.id.pairing_code);
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { /* Our own package is always there. */ }

        pairing = Pairing.get(this);
        session = new PlaybackSession(this, getSharedPreferences("player", MODE_PRIVATE), "Daniil Cast",
                "DaniilCast/" + version, this);
        screen = new PlayerScreen(this, session, this);
        Ui.action(findViewById(R.id.idle_settings), screen::openMenu);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
        pairing.addListener(this);
        CastService.start(this);
        screen.render();
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
            case "play": session.play(); break;
            case "pause": session.pause(); break;
            case "seek":
                session.seekTo(command.getLong("position"));
                if (session.started()) screen.showControls();
                break;
            case "stop": stopPlayback(); break;
            case "retry": retry(); break;
            case "audio": {
                AudioTracks.Track track = AudioTracks.find(session.audioTracks(), command.optString("track"));
                if (track == null) throw new CastServer.Failure(404, "no-track", "That audio track isn't available");
                session.selectAudio(track);
                break;
            }
            case "subtitles": applySubtitleCommand(command); break;
            case "quality": {
                String id = command.optString("id");
                VideoQualities.Option option = VideoQualities.find(session.qualities(), id);
                if (option == null && !VideoQualities.AUTO.equals(id)) {
                    throw new CastServer.Failure(404, "no-quality", "That quality isn't available");
                }
                session.selectQuality(option);
                break;
            }
            case "speed": session.setSpeed((float) command.getDouble("value")); break;
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
        screen.render();
        publishStatus();
        return new JSONObject().put("ok", true);
    }

    private void load(JSONObject command) throws Exception {
        String url = command.optString("url");
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new CastServer.Failure(400, "bad-url", getString(R.string.error_url));
        }
        String supplied = command.optString("title").trim();
        title = supplied.isEmpty() ? getString(R.string.default_title) : supplied;
        Map<String, String> headers = requestHeaders(command.optJSONObject("headers"));
        // A video converted on the Mac drops the page's headers; its subtitle files still need them.
        JSONObject pageHeaders = command.optJSONObject("subtitleHeaders");
        JSONArray files = command.optJSONArray("subtitles");
        boolean sameSeries = command.optBoolean("sameSeries");
        if (command.has("catalog")) catalog = Catalog.parse(command.optJSONObject("catalog"));
        else if (!sameSeries) catalog = null;
        episode = catalog == null ? null : catalog.find(command.optString("episode"));
        JSONObject converted = command.optJSONObject("converted");
        convertedFrom = converted == null ? null : converted.optString("from", "video");
        PlaybackRequest request = new PlaybackRequest.Builder(Uri.parse(url), title)
                .detail(episode == null ? "" : episodeShort(episode))
                .mimeType(command.optString("mime"))
                .headers(headers)
                .subtitleHeaders(pageHeaders == null ? headers : requestHeaders(pageHeaders))
                .subtitles(SubtitleDownloader.parse(files == null ? null : files.toString()))
                .subtitleRequest(new SubtitleTracks.Request(command.has("subtitle") ? command.optString("subtitle") : null,
                        command.optString("textLanguage", null), command.optString("textLabel", null)))
                .audioNames(names(command.optJSONArray("audioNames")))
                .audioIndex(command.optInt("audio", -1))
                .position(command.optLong("position"))
                // Another episode keeps the viewer's voice-over and speed.
                .continuing(sameSeries)
                .build();

        source = command;
        unsupportedFormat = null;
        stepFormat = convertedFrom;
        // The step shows before the old video stops, so the idle screen never flashes in between.
        screen.setLoading(step(convertedFrom != null ? STEP_CONVERTING : STEP_OPENING, convertedFrom));
        session.load(request);
    }

    /** The Mac is converting this video; show that, not the previous one. */
    private void prepare(JSONObject command) {
        String supplied = command.optString("title").trim();
        if (!supplied.isEmpty()) title = supplied;
        unsupportedFormat = null;
        stepFormat = command.optString("format", "");
        screen.setLoading(step(STEP_CONVERTING, stepFormat));
        session.stop();
        screen.setTitle(title);
    }

    /** A problem found on the Mac, such as a conversion that couldn't start. */
    private void failFromMac(JSONObject command) {
        session.fail(new PlaybackErrors.Description(command.optString("code", "mac"),
                command.optString("title", getString(tv.daniil.playback.R.string.error_generic_title)),
                command.optString("message", getString(R.string.error_generic)),
                command.optString("detail"), 0, command.optBoolean("retry")));
    }

    private void stopPlayback() {
        // Stop accepting network loads immediately, before Android's asynchronous
        // task removal. A new cast must open a fresh player, not this dying one.
        visible = false;
        CastHub.detach(this);
        session.stop();
        finishAndRemoveTask();
    }

    @Override public void onExit() {
        stopPlayback();
    }

    @Override public boolean canRetry() {
        return source != null;
    }

    @Override public void onRetry() {
        retry();
    }

    private void retry() {
        if (source == null) return;
        try {
            PlaybackSnapshot now = session.snapshot();
            JSONObject again = new JSONObject(source.toString());
            if (now.started && now.positionMs > 0) again.put("position", now.positionMs);
            again.put("sameSeries", true);
            load(again);
        } catch (Exception exception) {
            failToOpen(exception);
        }
        screen.render();
    }

    private void failToOpen(Exception exception) {
        session.fail(PlaybackErrors.of(this, "open", tv.daniil.playback.R.string.error_open_title,
                R.string.error_generic, exception.getClass().getSimpleName(), 0, false));
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

    private static List<String> names(@Nullable JSONArray items) {
        List<String> names = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) names.add(items.optString(i));
        return names;
    }

    private PlayerScreen.Loading step(String id, @Nullable String format) {
        switch (id) {
            case STEP_CONVERTING:
                return new PlayerScreen.Loading(id, getString(R.string.step_converting), getString(
                        R.string.step_converting_body, format == null || format.isEmpty() ? "this video" : format), true);
            case STEP_PREPARING:
                // Stays until the Mac sends a converted version, or the wait ends in an error.
                return new PlayerScreen.Loading(id, getString(R.string.step_preparing),
                        getString(R.string.step_preparing_body), false);
            default:
                return new PlayerScreen.Loading(STEP_OPENING, getString(R.string.step_opening), null, true);
        }
    }

    /**
     * A format this TV can't decode, such as AV1, would play as sound over a black screen, or as a silent picture.
     * Wait instead: the Mac sees this in the status and sends a converted version. Only without one is it an error.
     */
    @Override public void onUnsupportedFormat(String format) {
        if (convertedFrom != null) {
            session.fail(PlaybackErrors.of(this, "decoder", tv.daniil.playback.R.string.error_decoder_title,
                    tv.daniil.playback.R.string.error_decoder, "UNSUPPORTED_FORMAT · " + format, 0, false));
            return;
        }
        unsupportedFormat = format;
        session.pause();
        screen.setLoading(step(STEP_PREPARING, null));
        int waiting = session.generation();
        handler.postDelayed(() -> {
            if (waiting == session.generation() && unsupportedFormat != null && session.error() == null) {
                session.fail(needsMac(format));
            }
        }, CONVERSION_WAIT_MS);
        publishStatus();
    }

    /** No converting Mac answered for a format this TV can't decode. */
    private PlaybackErrors.Description needsMac(String format) {
        return new PlaybackErrors.Description("needs-mac", getString(R.string.error_needs_mac_title, format),
                getString(R.string.error_needs_mac), "UNSUPPORTED_FORMAT · " + format, 0, false);
    }

    // The idle screen and pairing

    @Override public void onScreenChanged() {
        Ui.fade(idleView, screen.isIdle());
        renderPairing();
    }

    /** The code stays on the idle screen until a Mac pairs; afterwards it shows only when a Mac asks. */
    private void renderPairing() {
        String code = pairing.activeCode();
        if (code == null && screen.isIdle() && visible && pairing.clientCount() == 0) code = pairing.showCode();
        if (code != null) pairingCode.setText(code.substring(0, 3) + " " + code.substring(3));
        Ui.fade(pairingView, code != null && !screen.isMenuOpen() && session.error() == null);
        idleDevice.setText(getString(R.string.idle_device, CastService.deviceName(this), CastService.pairingAddress()));
    }

    @Override public void onPairingChanged(@Nullable String pairedClient) {
        if (pairedClient != null) screen.notice(getString(R.string.paired_with, pairedClient));
        renderPairing();
        screen.refreshMenu();
    }

    @Override public void onMenuToggled(boolean open) {
        // On the idle screen the left side becomes a clear stage for the sample line.
        Ui.fade(idleContent, !open);
        renderPairing();
    }

    /** A code asked for over a video goes away with Back; the idle screen keeps it until a Mac pairs. */
    @Override public boolean onKey(int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_BACK || pairing.activeCode() == null || pairing.clientCount() == 0) return false;
        pairing.hideCode();
        return true;
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
            String voice = session.voice();
            int number = voice == null ? -1 : next.audioNames.indexOf(voice);
            if (number >= 0) command.put("audio", number);
            List<SubtitleTracks.Track> textTracks = session.textTracks();
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
            failToOpen(exception);
        }
        screen.render();
        publishStatus();
    }

    private boolean playAdjacent(boolean forward) {
        if (catalog == null || episode == null) return false;
        Catalog.Episode next = forward ? catalog.next(episode) : catalog.previous(episode);
        if (next == null) return false;
        playEpisode(next);
        return true;
    }

    @Override public boolean onNextEpisode() {
        return playAdjacent(true);
    }

    @Override public boolean onPreviousEpisode() {
        return playAdjacent(false);
    }

    @Override public void onEnded() {
        offerNextEpisode();
    }

    private void offerNextEpisode() {
        if (catalog == null || episode == null || screen.hasUpNext()) return;
        Catalog.Episode next = catalog.next(episode);
        if (next == null) return;
        screen.offerUpNext(getString(R.string.episode_label, next.number),
                getString(R.string.season_label, next.season.number), () -> playEpisode(next));
    }

    private String episodeShort(Catalog.Episode item) {
        return getString(R.string.episode_short, item.season.number, item.number);
    }

    // Menu rows: episodes while a series plays, pairing while idle

    @Override public List<MenuPanel.Item> menuItems(boolean leading) {
        List<MenuPanel.Item> items = new ArrayList<>();
        MenuPanel menu = screen.menu();
        if (leading && catalog != null && episode != null) {
            Catalog catalog = this.catalog;
            Catalog.Episode current = episode;
            items.add(MenuPanel.Item.page(getString(R.string.menu_episode), episodeShort(current), false,
                    () -> menu.push(catalog.seasons.size() > 1 ? seasonsPage() : episodesPage(current.season))));
            Catalog.Episode next = catalog.next(current);
            if (next != null) {
                items.add(MenuPanel.Item.action(getString(R.string.menu_next_episode), episodeShort(next), () -> {
                    menu.close();
                    playEpisode(next);
                }));
            }
        }
        if (!leading && !session.isLoaded()) {
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
    }

    private MenuPanel.Page seasonsPage() {
        return MenuPanel.Page.of(getString(R.string.menu_season), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            if (catalog == null) return items;
            for (Catalog.Season season : catalog.seasons) {
                items.add(MenuPanel.Item.page(getString(R.string.season_label, season.number),
                        getString(R.string.episodes_count, season.episodes.size()),
                        episode != null && episode.season == season, () -> screen.menu().push(episodesPage(season))));
            }
            return items;
        });
    }

    private MenuPanel.Page episodesPage(Catalog.Season season) {
        boolean seasons = catalog != null && catalog.seasons.size() > 1;
        String heading = seasons ? getString(R.string.season_label, season.number) : getString(R.string.menu_episode);
        return MenuPanel.Page.of(heading, false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (Catalog.Episode item : season.episodes) {
                boolean current = item == episode;
                String value = current ? getString(R.string.playing_now)
                        : item.durationMs > 0 ? getString(R.string.minutes, Math.round(item.durationMs / 60_000f)) : null;
                items.add(MenuPanel.Item.choice(getString(R.string.episode_label, item.number), value, current, () -> {
                    screen.menu().close();
                    if (item != episode) playEpisode(item);
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page pairedPage() {
        return MenuPanel.Page.of(getString(R.string.menu_paired), false, () -> Collections.singletonList(
                MenuPanel.Item.action(getString(R.string.menu_forget), null, () -> {
                    pairing.forgetAll();
                    screen.notice(getString(R.string.menu_forget_done));
                    screen.menu().pop();
                })));
    }

    /** Track and style changes sent from the Chrome extension. */
    private void applySubtitleCommand(JSONObject command) {
        String track = command.optString("track", null);
        if ("off".equals(track)) {
            session.selectSubtitles(null);
        } else if (track != null) {
            SubtitleTracks.Track chosen = SubtitleTracks.find(session.textTracks(), track);
            if (chosen != null) session.selectSubtitles(chosen);
        }
        boolean styled = false;
        for (SubtitleStyle.Setting setting : SubtitleStyle.Setting.values()) {
            String value = command.optString(setting.key, null);
            if (value != null) styled |= screen.style().set(setting, value);
        }
        // Show the sample line for a moment, so the change is visible on the TV between cues.
        if (styled) screen.onStyleChanged(true);
    }

    // Remote shortcuts

    @Override public void onBackPressed() {
        // Android 13+ may deliver Back through its dispatcher rather than as a
        // KeyEvent. Both paths must pop the menu before closing the player.
        if (!screen.onRemoteKey(KeyEvent.KEYCODE_BACK)) finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return screen.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    // Status for the paired Mac

    private String phase(PlaybackSnapshot now) {
        if (now.error != null) return "error";
        if (screen.loading() != null) return "preparing";
        if (!now.loaded) return "idle";
        if (now.ended) return "ended";
        if (now.buffering || !now.started) return "buffering";
        return now.playWhenReady ? "playing" : "paused";
    }

    @Override public void onPlaybackChanged(PlaybackSnapshot snapshot) {
        publishStatus(snapshot);
    }

    private void publishStatus() {
        if (session != null) publishStatus(session.snapshot());
    }

    private void publishStatus(PlaybackSnapshot now) {
        if (screen == null) return;
        try {
            JSONArray texts = new JSONArray();
            for (SubtitleTracks.Track track : now.textTracks) {
                texts.put(new JSONObject().put("id", track.id).put("label", track.label)
                        .put("language", track.language == null ? "" : track.language));
            }
            JSONArray voices = new JSONArray();
            for (AudioTracks.Track track : now.audioTracks) voices.put(new JSONObject().put("id", track.id).put("label", track.label));
            JSONArray qualityList = new JSONArray();
            for (VideoQualities.Option option : now.qualities) {
                qualityList.put(new JSONObject().put("id", option.id).put("label", option.label));
            }
            Object current = episode == null ? JSONObject.NULL : new JSONObject().put("id", episode.id)
                    .put("season", episode.season.number).put("number", episode.number).put("label", episodeShort(episode));
            Object playedFrom = source == null ? JSONObject.NULL : new JSONObject()
                    .put("kind", source.optString("kind")).put("codec", source.optString("codec"))
                    .put("convertedFrom", convertedFrom == null ? "" : convertedFrom);
            PlayerScreen.Loading step = screen.loading();
            JSONObject status = new JSONObject()
                    .put("app", "Daniil Cast").put("version", version).put("phase", phase(now)).put("title", title)
                    .put("step", step == null ? "" : step.id).put("stepFormat", stepFormat == null ? "" : stepFormat)
                    .put("needsConversion", unsupportedFormat != null)
                    .put("unsupportedFormat", unsupportedFormat == null ? "" : unsupportedFormat)
                    .put("error", now.error == null ? JSONObject.NULL : now.error.toJson())
                    .put("renderedVideo", now.renderedFirstFrame).put("source", playedFrom)
                    .put("videoFormat", formatStatus(now.videoFormat))
                    .put("audioFormat", formatStatus(now.audioFormat))
                    .put("tracks", trackStatus())
                    .put("playing", now.playing).put("playWhenReady", now.playWhenReady)
                    .put("position", now.positionMs).put("duration", now.durationMs)
                    .put("buffered", now.bufferedMs)
                    .put("textTracks", texts).put("textTrack", now.subtitles == null ? "off" : now.subtitles.id)
                    .put("audioTracks", voices).put("audioTrack", now.audio == null ? "" : now.audio.id)
                    .put("qualities", qualityList).put("quality", now.quality == null ? VideoQualities.AUTO : now.quality.id)
                    .put("speed", (double) now.speed).put("episode", current)
                    .put("catalog", catalog == null ? JSONObject.NULL : catalog.summary(episode))
                    .put("subtitleStyle", screen.style().toJson())
                    .put("updatedAt", System.currentTimeMillis());
            CastHub.publish(this, status);
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
        for (Tracks.Group group : session.player().getCurrentTracks().getGroups()) {
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
        session.pause();
        publishStatus();
    }

    @Override public void onDestroy() {
        CastHub.detach(this);
        pairing.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        screen.release();
        session.release();
        super.onDestroy();
    }
}
