package tv.daniil.playback;

import java.util.Collections;
import java.util.List;

/**
 * What the app around the player decides. The shared session and screen handle playback, tracks and controls; every
 * call arrives on the main thread. Only leaving the player and formats without a decoder need an answer from
 * every app.
 */
public interface PlaybackHost {
    /** Leave the player: Stop, or Close on an error. */
    void onExit();

    /**
     * Media3 has no decoder for this media's video or audio, such as "AV1". Reported once per media; playback
     * would otherwise continue as sound over a black screen, or as a silent picture.
     */
    void onUnsupportedFormat(String format);

    /** Readiness, playing state, first frame, tracks or errors changed. */
    default void onPlaybackChanged(PlaybackSnapshot snapshot) {}

    /**
     * Each second while playing, and when the media pauses, seeks, ends, or is replaced or stopped: worth saving.
     * The snapshot still describes the outgoing media when another replaces it.
     */
    default void onProgress(PlaybackSnapshot snapshot) {}

    /** The media played to its end. */
    default void onEnded() {}

    /** Plays the next or previous episode; returns false when there is none. */
    default boolean onNextEpisode() {
        return false;
    }

    default boolean onPreviousEpisode() {
        return false;
    }

    /** Whether the failed media can be opened again; if so, {@link #onRetry} opens it. */
    default boolean canRetry() {
        return false;
    }

    default void onRetry() {}

    /** Whether another source can replace one that failed; if so, {@link #onChooseSource} offers them. */
    default boolean canChooseSource() {
        return false;
    }

    default void onChooseSource() {}

    /** The app's rows in the options menu: before the track settings when {@code leading}, else after them. */
    default List<MenuPanel.Item> menuItems(boolean leading) {
        return Collections.emptyList();
    }

    /**
     * More subtitles the app can add, such as files its providers offer, listed after the loaded tracks on the
     * Subtitles page; choosing one is the app's to handle.
     */
    default List<MenuPanel.Item> subtitleItems() {
        return Collections.emptyList();
    }

    /** Handles a remote key before the player does, after the open menu and the up-next card. */
    default boolean onKey(int keyCode) {
        return false;
    }

    /** The player layers were updated; apps update their own, such as an idle screen. */
    default void onScreenChanged() {}

    default void onMenuToggled(boolean open) {}
}
