package tv.daniil.playback;

import android.app.Activity;
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
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shared player layers of an activity, driven by a {@link PlaybackSession}: preparing and error states, the
 * playback overlay, subtitles, the up-next card, notices and the options menu. Every action works by mouse, touch
 * and remote. The activity forwards keys and Back, and adds its own layers through {@link PlaybackHost}.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerScreen implements PlaybackSession.Listener, MenuPanel.Listener {
    /** What the screen says while there is no picture yet. */
    public static final class Loading {
        /** The app's name for this step, such as "opening". */
        public final String id;
        public final String overline;
        @Nullable public final String message;
        /** Clears itself once the media is ready; otherwise it stays until replaced. */
        public final boolean untilReady;

        public Loading(String id, String overline, @Nullable String message, boolean untilReady) {
            this.id = id;
            this.overline = overline;
            this.message = message;
            this.untilReady = untilReady;
        }
    }

    private static final long PREVIEW_MS = 4000;
    private static final long NOTICE_MS = 4000;
    private static final int UP_NEXT_SECONDS = 10;
    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    // Back is routed through onBackPressed/the system dispatcher on every Android version.
    private static final Set<Integer> REMOTE_KEYS = new HashSet<>(Arrays.asList(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS));

    private final Activity activity;
    private final PlaybackSession session;
    private final Player player;
    private final PlaybackHost host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlayerView playerView;
    private final SubtitleView subtitles;
    private final View loadingView, errorView, errorRetry, errorSources, bufferingView, upNextView;
    private final TextView loadingStep, loadingTitle, loadingMessage, errorTitle, errorMessage;
    private final TextView notice, upNextTitle, upNextDetail;
    private final ControlsOverlay controls;
    private final MenuPanel menu;
    private final SubtitleStyle style;
    private final List<Cue> sample;

    private String title = "";
    @Nullable private Loading loading;
    @Nullable private PlaybackErrors.Description shownError;
    @Nullable private Runnable upNext;
    private String upNextContext = "";
    private int upNextSeconds;
    private long previewUntil;
    private boolean screenKeptOn;
    // The overlay reacts to changes of these, not to every update.
    private int lastState = Player.STATE_IDLE;
    private boolean lastPlayWhenReady;

    private final Runnable showCues = this::showCues;
    private final Runnable hideNotice = this::hideNotice;
    private final Runnable countdown = this::countDown;

    public PlayerScreen(Activity activity, PlaybackSession session, PlaybackHost host) {
        this.activity = activity;
        this.session = session;
        this.player = session.player();
        this.host = host;
        playerView = activity.findViewById(R.id.player_view);
        loadingView = activity.findViewById(R.id.loading);
        loadingStep = activity.findViewById(R.id.loading_step);
        loadingTitle = activity.findViewById(R.id.loading_title);
        loadingMessage = activity.findViewById(R.id.loading_message);
        bufferingView = activity.findViewById(R.id.buffering);
        errorView = activity.findViewById(R.id.error);
        errorTitle = activity.findViewById(R.id.error_title);
        errorMessage = activity.findViewById(R.id.error_message);
        errorRetry = activity.findViewById(R.id.error_retry);
        errorSources = activity.findViewById(R.id.error_sources);
        notice = activity.findViewById(R.id.notice);
        upNextView = activity.findViewById(R.id.up_next);
        upNextTitle = activity.findViewById(R.id.up_next_title);
        upNextDetail = activity.findViewById(R.id.up_next_detail);
        subtitles = activity.findViewById(R.id.subtitles);
        playerView.getSubtitleView().setVisibility(View.GONE);
        playerView.setPlayer(player);
        // The gap below subtitles is a fraction of the view's height, known only after layout.
        subtitles.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) layoutSubtitles();
        });
        style = new SubtitleStyle(session.preferences());
        style.applyTo(subtitles);
        sample = Collections.singletonList(new Cue.Builder().setText(activity.getString(R.string.subtitle_sample)).build());
        controls = new ControlsOverlay(activity, session, handler, this::layoutSubtitles);
        menu = new MenuPanel(activity, this);
        bindPointerControls();
        session.addListener(this);
    }

    // State shown by the app

    public void setTitle(String text) {
        title = text;
        controls.setTitle(text);
        if (loading != null) loadingTitle.setText(text);
    }

    /** Shows a step before the picture, such as opening or converting; null hides it. */
    public void setLoading(@Nullable Loading step) {
        loading = step;
        render();
    }

    @Nullable public Loading loading() {
        return loading;
    }

    /** Nothing is loaded, failing or being prepared: the app's idle layer shows. */
    public boolean isIdle() {
        return session.error() == null && loading == null && !session.isLoaded();
    }

    public boolean isMenuOpen() {
        return menu.isOpen();
    }

    public MenuPanel menu() {
        return menu;
    }

    public void openMenu() {
        menu.open(rootPage());
    }

    /** Audio and subtitle choices alone, for an app's own Audio & subtitles button. */
    public void openTracksMenu() {
        menu.open(tracksPage());
    }

    /** Everything but the audio and subtitle choices, for an app's own Settings button. */
    public void openSettingsMenu() {
        menu.open(settingsPage());
    }

    public void refreshMenu() {
        menu.refresh();
    }

    public SubtitleStyle style() {
        return style;
    }

    /** Applies a changed style; a {@code preview} shows the sample line for a moment between cues. */
    public void onStyleChanged(boolean preview) {
        style.applyTo(subtitles);
        layoutSubtitles();
        session.notifyChanged();
        if (!preview) return;
        menu.refresh();
        previewUntil = SystemClock.uptimeMillis() + PREVIEW_MS;
        handler.removeCallbacks(showCues);
        handler.postDelayed(showCues, PREVIEW_MS);
        showCues();
    }

    public void notice(String text) {
        notice.setText(text);
        Ui.fade(notice, true);
        handler.removeCallbacks(hideNotice);
        handler.postDelayed(hideNotice, NOTICE_MS);
    }

    private void hideNotice() {
        Ui.fade(notice, false);
    }

    public void showControls() {
        controls.show();
    }

    // Up next

    /** Counts down, then plays the next episode unless the viewer cancels; {@code context} reads "Season 2". */
    public void offerUpNext(String nextTitle, String context, Runnable play) {
        if (upNext != null) return;
        upNext = play;
        upNextContext = context;
        upNextSeconds = UP_NEXT_SECONDS;
        controls.hide();
        upNextTitle.setText(nextTitle);
        Ui.fade(upNextView, true);
        countDown();
    }

    public boolean hasUpNext() {
        return upNext != null;
    }

    public void cancelUpNext() {
        handler.removeCallbacks(countdown);
        upNext = null;
        Ui.fade(upNextView, false);
    }

    private void countDown() {
        if (upNext == null) return;
        if (upNextSeconds <= 0) {
            Runnable next = upNext;
            cancelUpNext();
            next.run();
            return;
        }
        upNextDetail.setText(upNextContext + "  ·  " + activity.getString(R.string.up_next_countdown, upNextSeconds));
        upNextSeconds--;
        handler.postDelayed(countdown, 1000);
    }

    private void playUpNext() {
        Runnable next = upNext;
        cancelUpNext();
        if (next != null) next.run();
    }

    // Rendering

    /** Shows exactly one of the app's idle layer, preparing, error or the video itself. */
    public void render() {
        PlaybackErrors.Description error = session.error();
        if (error != shownError) {
            shownError = error;
            if (error != null) {
                // The error replaces whatever step was showing.
                loading = null;
                cancelUpNext();
                errorTitle.setText(error.title);
                errorMessage.setText(error.message);
                errorRetry.setVisibility(error.retry && host.canRetry() ? View.VISIBLE : View.GONE);
                errorSources.setVisibility(host.canChooseSource() ? View.VISIBLE : View.GONE);
            }
        }
        boolean failed = error != null;
        boolean preparing = !failed && loading != null;
        Ui.fade(loadingView, preparing);
        Ui.fade(errorView, failed);
        bufferingView.setVisibility(!failed && !preparing && session.started()
                && player.getPlaybackState() == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        if (failed || preparing || !session.started() || menu.isOpen()) controls.hide();
        if (preparing) {
            loadingTitle.setText(title);
            loadingStep.setText(loading.overline);
            loadingMessage.setText(loading.message == null ? "" : loading.message);
            loadingMessage.setVisibility(loadingMessage.getText().length() > 0 ? View.VISIBLE : View.GONE);
        }
        // The screensaver may start while idle or paused, never during a video or while it opens.
        keepScreenOn(preparing || player.isPlaying());
        host.onScreenChanged();
    }

    private void keepScreenOn(boolean on) {
        if (on == screenKeptOn) return;
        screenKeptOn = on;
        if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** A load shows its title at once, even while its subtitles download; a stop keeps the last one. */
    @Override public void onMediaChanged() {
        cancelUpNext();
        controls.hide();
        PlaybackRequest request = session.request();
        if (request != null) setTitle(request.title);
    }

    @Override public void onPlaybackChanged() {
        if (session.started() && loading != null && loading.untilReady) loading = null;
        int state = player.getPlaybackState();
        boolean playWhenReady = player.getPlayWhenReady();
        boolean changed = state != lastState || playWhenReady != lastPlayWhenReady;
        lastState = state;
        lastPlayWhenReady = playWhenReady;
        // The up-next card replaces the overlay at the end of an episode.
        if (changed && session.started() && upNext == null && !menu.isOpen()) controls.onPlaybackChanged();
        render();
    }

    @Override public void onCuesChanged() {
        showCues();
    }

    /** Mirrors the tracks in the overlay and the menu once they are known. */
    @Override public void onTracksChanged() {
        List<String> details = new ArrayList<>();
        PlaybackRequest request = session.request();
        if (request != null && !request.detail.isEmpty()) details.add(request.detail);
        AudioTracks.Track playing = AudioTracks.selected(session.audioTracks());
        if (session.audioTracks().size() > 1 && playing != null) details.add(playing.label);
        if (!session.textTracks().isEmpty()) details.add(activity.getString(R.string.details_subtitles, subtitleSummary()));
        controls.setDetails(TextUtils.join("  ·  ", details));
        showCues();
        menu.refresh();
    }

    @Override public void onSeekGathering(long deltaMs) {
        controls.showSeek(deltaMs);
    }

    /** The current cues, or the sample line while the style is being changed and nothing else is shown. */
    private void showCues() {
        List<Cue> cues = session.cues();
        boolean preview = menu.previewsSubtitles() || SystemClock.uptimeMillis() < previewUntil;
        subtitles.setCues(cues.isEmpty() && preview ? sample : bottomAligned(cues));
    }

    /**
     * SubRip and similar parsers place an ordinary bottom cue at a fraction of the height (0.92) rather than leaving
     * it unset. A fraction is measured inside the view's bottom padding, so the cue would float well above the
     * chosen gap and the overlay. Such cues sit on the padding instead; cues placed elsewhere keep their place.
     */
    private static List<Cue> bottomAligned(List<Cue> cues) {
        List<Cue> result = null;
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            boolean low = cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != Cue.DIMEN_UNSET && cue.line >= 0.75f
                    && cue.lineAnchor != Cue.ANCHOR_TYPE_START && cue.verticalType == Cue.TYPE_UNSET;
            if (!low) continue;
            if (result == null) result = new ArrayList<>(cues);
            result.set(i, cue.buildUpon().setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.TYPE_UNSET).build());
        }
        return result == null ? cues : result;
    }

    /** Places subtitles at the chosen height, clear of the menu and the overlay's timeline and buttons. */
    private void layoutSubtitles() {
        int right = menu.isOpen() ? activity.getResources().getDimensionPixelSize(R.dimen.panel_width) : 0;
        // Moving captions above controls changes padding, never their text size.
        if (subtitles.getHeight() > 0) subtitles.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                subtitles.getHeight() * style.sizeFraction());
        int gap = Math.round(subtitles.getHeight() * style.bottomGap());
        int overlay = controls.isShown() ? controls.bottomClearance() : 0;
        subtitles.setPadding(0, 0, right, Math.max(gap, overlay));
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
        playerView.setPlayer(null);
    }

    // Menu pages

    private String subtitleSummary() {
        SubtitleTracks.Track shown = SubtitleTracks.selected(session.textTracks());
        return shown == null ? activity.getString(R.string.track_off) : shown.label;
    }

    private String qualitySummary() {
        VideoQualities.Option quality = session.quality();
        if (quality != null) return quality.label;
        int lines = session.playingLines();
        return lines > 0 ? activity.getString(R.string.quality_auto_current, VideoQualities.label(lines))
                : activity.getString(R.string.quality_auto);
    }

    private String speedLabel(float value) {
        if (value == 1f) return activity.getString(R.string.speed_normal);
        String number = value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
        return activity.getString(R.string.speed_value, number);
    }

    private String styleSummary() {
        return activity.getString(SubtitleStyle.Setting.SIZE.labels[style.get(SubtitleStyle.Setting.SIZE)]) + " · "
                + activity.getString(SubtitleStyle.Setting.COLOR.labels[style.get(SubtitleStyle.Setting.COLOR)]);
    }

    private MenuPanel.Page rootPage() {
        boolean loaded = session.isLoaded();
        return MenuPanel.Page.of(activity.getString(loaded ? R.string.menu_options : R.string.menu_settings), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>(host.menuItems(true));
            List<AudioTracks.Track> audio = session.audioTracks();
            if (audio.size() > 1) {
                AudioTracks.Track playing = AudioTracks.selected(audio);
                items.add(MenuPanel.Item.page(activity.getString(R.string.menu_audio),
                        playing == null ? null : playing.label, false, () -> menu.push(audioPage())));
            }
            if (hasSubtitles()) {
                items.add(MenuPanel.Item.page(activity.getString(R.string.menu_subtitles), subtitleSummary(), false,
                        () -> menu.push(subtitlesPage())));
            }
            addSettings(items, loaded);
            items.addAll(host.menuItems(false));
            return items;
        });
    }

    private boolean hasSubtitles() {
        return !session.textTracks().isEmpty() || !host.subtitleItems().isEmpty();
    }

    private void addSettings(List<MenuPanel.Item> items, boolean loaded) {
        if (!session.qualities().isEmpty()) {
            items.add(MenuPanel.Item.page(activity.getString(R.string.menu_quality), qualitySummary(), false,
                    () -> menu.push(qualityPage())));
        }
        if (loaded) {
            items.add(MenuPanel.Item.page(activity.getString(R.string.menu_speed), speedLabel(session.speed()), false,
                    () -> menu.push(speedPage())));
        }
        items.add(MenuPanel.Item.page(activity.getString(R.string.menu_style), styleSummary(), false,
                () -> menu.push(stylePage())));
    }

    /** Subtitle choices first, then the audio row: what an Audio & subtitles button is pressed for. */
    private MenuPanel.Page tracksPage() {
        return MenuPanel.Page.of(activity.getString(R.string.menu_tracks), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>(subtitleChoices());
            List<AudioTracks.Track> audio = session.audioTracks();
            if (audio.size() > 1) {
                AudioTracks.Track playing = AudioTracks.selected(audio);
                items.add(MenuPanel.Item.page(activity.getString(R.string.menu_audio),
                        playing == null ? null : playing.label, false, () -> menu.push(audioPage())));
            }
            return items;
        });
    }

    private MenuPanel.Page settingsPage() {
        boolean loaded = session.isLoaded();
        return MenuPanel.Page.of(activity.getString(R.string.menu_settings), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>(host.menuItems(true));
            addSettings(items, loaded);
            items.addAll(host.menuItems(false));
            return items;
        });
    }

    private MenuPanel.Page audioPage() {
        return MenuPanel.Page.of(activity.getString(R.string.menu_audio), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (AudioTracks.Track track : session.audioTracks()) {
                items.add(MenuPanel.Item.choice(track.label, null, track.selected, () -> {
                    session.selectAudio(track);
                    menu.pop();
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page subtitlesPage() {
        return MenuPanel.Page.of(activity.getString(R.string.menu_subtitles), false, this::subtitleChoices);
    }

    /** Off, the loaded tracks, then whatever more the app can add. */
    private List<MenuPanel.Item> subtitleChoices() {
        List<MenuPanel.Item> items = new ArrayList<>();
        List<SubtitleTracks.Track> tracks = session.textTracks();
        SubtitleTracks.Track shown = SubtitleTracks.selected(tracks);
        items.add(MenuPanel.Item.choice(activity.getString(R.string.track_off), null, shown == null, () -> {
            if (!tracks.isEmpty()) session.selectSubtitles(null);
            menu.pop();
        }));
        for (SubtitleTracks.Track track : tracks) {
            items.add(MenuPanel.Item.choice(track.label, null, track == shown, () -> {
                session.selectSubtitles(track);
                menu.pop();
            }));
        }
        items.addAll(host.subtitleItems());
        return items;
    }

    private MenuPanel.Page qualityPage() {
        return MenuPanel.Page.of(activity.getString(R.string.menu_quality), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            VideoQualities.Option quality = session.quality();
            int lines = session.playingLines();
            items.add(MenuPanel.Item.choice(activity.getString(R.string.quality_auto),
                    quality == null && lines > 0 ? VideoQualities.label(lines) : null, quality == null, () -> {
                        session.selectQuality(null);
                        menu.pop();
                    }));
            for (VideoQualities.Option option : session.qualities()) {
                items.add(MenuPanel.Item.choice(option.label, null, quality != null && option.id.equals(quality.id), () -> {
                    session.selectQuality(option);
                    menu.pop();
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page speedPage() {
        return MenuPanel.Page.of(activity.getString(R.string.menu_speed), false, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (float value : SPEEDS) {
                items.add(MenuPanel.Item.choice(speedLabel(value), null, value == session.speed(), () -> {
                    session.setSpeed(value);
                    menu.pop();
                }));
            }
            return items;
        });
    }

    private MenuPanel.Page stylePage() {
        return MenuPanel.Page.of(activity.getString(R.string.menu_style), true, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (SubtitleStyle.Setting setting : SubtitleStyle.Setting.values()) {
                items.add(MenuPanel.Item.page(activity.getString(setting.title),
                        activity.getString(setting.labels[style.get(setting)]), false,
                        () -> menu.push(styleSettingPage(setting))));
            }
            return items;
        });
    }

    private MenuPanel.Page styleSettingPage(SubtitleStyle.Setting setting) {
        return MenuPanel.Page.of(activity.getString(setting.title), true, () -> {
            List<MenuPanel.Item> items = new ArrayList<>();
            for (int i = 0; i < setting.values.length; i++) {
                int index = i;
                Runnable apply = () -> {
                    if (style.set(setting, index)) onStyleChanged(true);
                };
                items.add(MenuPanel.Item.choice(activity.getString(setting.labels[i]), null, style.get(setting) == i, apply).onFocus(apply));
            }
            return items;
        });
    }

    @Override public void onMenuToggled(boolean open) {
        if (open) controls.hide();
        host.onMenuToggled(open);
        layoutSubtitles();
        showCues();
    }

    @Override public void onMenuPageChanged() {
        showCues();
    }

    // Pointer and remote input

    /** Pointer actions use the same playback and menu paths as remote keys. */
    private void bindPointerControls() {
        Ui.action(activity.findViewById(R.id.control_options), this::openMenu);
        Ui.action(activity.findViewById(R.id.center_icon), this::togglePlay);
        Ui.action(activity.findViewById(R.id.control_play), this::togglePlay);
        Ui.action(activity.findViewById(R.id.control_rewind), () -> session.seekBy(-PlaybackSession.SEEK_STEP_MS));
        Ui.action(activity.findViewById(R.id.control_forward), () -> session.seekBy(PlaybackSession.SEEK_STEP_MS));
        Ui.action(activity.findViewById(R.id.control_stop), host::onExit);
        Ui.action(errorRetry, host::onRetry);
        Ui.action(errorSources, host::onChooseSource);
        Ui.action(activity.findViewById(R.id.error_close), host::onExit);
        Ui.action(activity.findViewById(R.id.up_next_play), this::playUpNext);
        Ui.action(activity.findViewById(R.id.up_next_cancel), () -> {
            cancelUpNext();
            controls.show();
        });
        View.OnClickListener surface = view -> {
            if (menu.isOpen()) menu.close();
            else if (session.started() && session.error() == null && loading == null) {
                if (controls.isShown() && player.getPlayWhenReady()) controls.hide();
                else controls.show();
            }
        };
        // The activity's root holds the video layer; a click anywhere off the controls toggles them.
        ((View) playerView.getParent()).setOnClickListener(surface);
        activity.findViewById(R.id.controls).setOnClickListener(surface);
        playerView.setOnClickListener(surface);
    }

    /** For the activity's {@code dispatchKeyEvent}; Back goes through {@link #onRemoteKey} from onBackPressed. */
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && onRemoteKey(code)) return true;
        return event.getAction() == KeyEvent.ACTION_UP && REMOTE_KEYS.contains(code);
    }

    /** Handles a remote key; false lets the activity act, such as closing on Back. */
    public boolean onRemoteKey(int code) {
        if (menu.isOpen() && menu.onKeyDown(code)) return true;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (upNext != null && code != KeyEvent.KEYCODE_MENU && code != KeyEvent.KEYCODE_CAPTIONS) {
            if (ok) playUpNext();
            else if (code == KeyEvent.KEYCODE_BACK) {
                cancelUpNext();
                controls.show();
            }
            return true;
        }
        if (host.onKey(code)) return true;
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CAPTIONS: openMenu(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: player.play(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: player.pause(); return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT: host.onNextEpisode(); return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: host.onPreviousEpisode(); return true;
            default: break;
        }
        PlaybackErrors.Description error = session.error();
        if (error != null) {
            if (ok && error.retry && host.canRetry()) {
                host.onRetry();
                return true;
            }
            return false;
        }
        // While idle or preparing, Back closes the app as usual.
        if (!session.started() || loading != null) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: togglePlay(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND: session.seekBy(-PlaybackSession.SEEK_STEP_MS); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: session.seekBy(PlaybackSession.SEEK_STEP_MS); return true;
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
        session.togglePlay();
        controls.show();
    }
}
