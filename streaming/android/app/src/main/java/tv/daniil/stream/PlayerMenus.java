package tv.daniil.stream;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.List;
import java.util.Objects;
import tv.daniil.playback.AudioTracks;
import tv.daniil.playback.PlaybackSession;
import tv.daniil.playback.PlayerScreen;
import tv.daniil.playback.SubtitleStyle;
import tv.daniil.playback.SubtitleTracks;
import tv.daniil.playback.VideoQualities;
import tv.daniil.stream.ui.SubtitlePreviewView;
import tv.daniil.stream.ui.Tv;

/** App-owned popup presentation; Media3, cue handling and the shared session remain unchanged. */
final class PlayerMenus {
    static final class SubtitleOffer {
        final String label; final Runnable choose;
        SubtitleOffer(String label, Runnable choose) { this.label = label; this.choose = choose; }
    }
    interface Host {
        boolean hasEpisode(int direction);
        void episode(int direction);
        boolean canChooseSource();
        void sources(Tv.Alert page);
        List<SubtitleOffer> offers();
        void saveStyle(SubtitleStyle.Setting setting, int index);
        void visibilityChanged();
        boolean developerMode();
        void diagnostics(Tv.Alert page);
    }
    private static final float[] SPEEDS = {.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private final WatchActivity activity;
    private final PlaybackSession session;
    private final PlayerScreen screen;
    private final SharedPreferences preferences;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Tv.Alert root;
    private String signature = "";

    PlayerMenus(WatchActivity activity, PlaybackSession session, PlayerScreen screen, SharedPreferences preferences, Host host) {
        this.activity = activity; this.session = session; this.screen = screen; this.preferences = preferences; this.host = host;
    }
    boolean isOpen() { return root != null && root.dialog.isShowing(); }
    void open(boolean tracks, View origin) {
        if (isOpen()) { close(); return; }
        origin.setFocusableInTouchMode(true); origin.requestFocus();
        Tv.Alert menu = Tv.alert(activity, tracks ? "Audio & subtitles" : "Settings", (View) null);
        root = menu;
        menu.onAppear(() -> { menu.reset(); if (tracks) tracks(menu, true); else settings(menu); signature = signature(); });
        // Retain the shared menu's transport/caption guard, but never draw its old presentation.
        // INVISIBLE still measures its rows, avoiding the shared reveal callback retrying an unmeasured GONE view.
        screen.openSettingsMenu();
        activity.findViewById(R.id.menu).setVisibility(View.INVISIBLE);
        menu.onClosing(this::releaseGuard);
        menu.dialog.setOnDismissListener(d -> { if (root == menu) root = null; main.removeCallbacks(refresh); host.visibilityChanged(); });
        menu.show(); host.visibilityChanged();
    }
    private void releaseGuard() {
        if (screen.isMenuOpen()) screen.menu().close();
        if (!activity.isFinishing()) screen.showControls();
    }
    void close() { if (root != null) root.dialog.dismiss(); }
    private void done(Tv.Alert page, boolean top) { if (top) page.dialog.dismiss(); else page.back(); }
    private void settings(Tv.Alert page) {
        if (host.canChooseSource()) page.submenu("player:sources", "Choose source", child -> child.onAppear(() -> {
            child.reset(); host.sources(child);
        }));
        if (host.hasEpisode(-1)) page.button("player:previous", "Previous episode", () -> { close(); host.episode(-1); });
        if (host.hasEpisode(1)) page.button("player:next", "Next episode", () -> { close(); host.episode(1); });
        if (!session.qualities().isEmpty()) page.submenu("player:quality", "Quality", session.quality() == null ? "Auto" : session.quality().label, child -> {
            child.onAppear(() -> {
                child.reset(); child.choice("quality:auto", "Auto", "", session.quality() == null, () -> { session.selectQuality(null); child.back(); });
                for (VideoQualities.Option option : session.qualities()) child.choice("quality:" + option.id, option.label, "",
                        session.quality() != null && option.id.equals(session.quality().id), () -> { session.selectQuality(option); child.back(); });
                child.scrollChoices(276);
            });
        });
        page.submenu("player:speed", "Speed", speed(session.speed()), child -> {
            child.onAppear(() -> {
                child.reset(); for (float value : SPEEDS) child.choice("speed:" + value, speed(value), "", value == session.speed(), () -> { session.setSpeed(value); child.back(); });
                child.scrollChoices(276);
            });
        });
        page.submenu("player:style", "Subtitle style", child -> child.onAppear(() -> style(child)));
        if (host.developerMode()) page.submenu("player:diagnostics", "Developer", host::diagnostics);
        page.scrollChoices(322);
    }
    private void tracks(Tv.Alert page, boolean top) {
        List<SubtitleTracks.Track> tracks = session.textTracks(); SubtitleTracks.Track selected = SubtitleTracks.selected(tracks);
        page.choice("subtitle:off", "Off", "", selected == null, () -> { session.selectSubtitles(null); done(page, top); });
        for (SubtitleTracks.Track track : tracks) page.choice("subtitle:" + track.id, track.label, track.external ? "Downloaded" : "In video", track == selected,
                () -> { session.selectSubtitles(track); done(page, top); });
        int i = 0; for (SubtitleOffer offer : host.offers()) {
            page.button("subtitle:download:" + i++, offer.label + " · Download", () -> { close(); offer.choose.run(); });
        }
        if (session.audioTracks().size() > 1) {
            AudioTracks.Track playing = AudioTracks.selected(session.audioTracks());
            page.submenu("player:audio", "Audio", playing == null ? "" : playing.label, child -> child.onAppear(() -> {
                child.reset(); for (AudioTracks.Track track : session.audioTracks()) child.choice("audio:" + track.id, track.label, "", track.selected,
                        () -> { session.selectAudio(track); child.back(); });
                child.scrollChoices(276);
            }));
        }
        page.scrollChoices(322);
    }
    private SubtitlePreviewView preview(Tv.Alert page) {
        SubtitlePreviewView view = new SubtitlePreviewView(activity); view.setCompact(true); view.updateFrom(preferences); page.body(view); return view;
    }
    private void style(Tv.Alert page) {
        page.reset(); preview(page);
        for (SubtitleStyle.Setting setting : SubtitleStyle.Setting.values()) {
            String label = activity.getString(setting.title), value = activity.getString(setting.labels[screen.style().get(setting)]);
            page.submenu("style:" + setting.key, label, value, child -> child.onAppear(() -> styleChoices(child, setting)));
        }
        page.scrollChoices(230);
    }
    private void styleChoices(Tv.Alert page, SubtitleStyle.Setting setting) {
        page.reset(); SubtitlePreviewView view = preview(page); int selected = screen.style().get(setting);
        for (int i = 0; i < setting.values.length; i++) {
            final int index = i;
            page.choice("style:" + setting.key + ":" + i, activity.getString(setting.labels[i]), "", selected == i, () -> {
                screen.style().set(setting, index); screen.onStyleChanged(false); host.saveStyle(setting, index); page.back();
            }, ignored -> {
                view.preview(setting, index);
            });
        }
        page.scrollChoices(184);
    }
    private String signature() {
        StringBuilder result = new StringBuilder();
        for (SubtitleTracks.Track track : session.textTracks()) result.append(track.id).append(track.selected);
        for (AudioTracks.Track track : session.audioTracks()) result.append(track.id).append(track.selected);
        result.append(session.qualities().size()).append(host.offers().size()); return result.toString();
    }
    void changed() { if (isOpen() && !Objects.equals(signature, signature())) { main.removeCallbacks(refresh); main.postDelayed(refresh, 60); } }
    private final Runnable refresh = () -> { if (isOpen() && !Objects.equals(signature, signature())) { signature = signature(); root.refresh(); } };
    static String speed(float value) { return java.math.BigDecimal.valueOf((double) value).stripTrailingZeros().toPlainString() + "×"; }
}
