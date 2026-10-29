package tv.stream.playback

import java.util.Collections

/**
 * What the app around the player decides. The shared session and screen handle playback, tracks and controls; every
 * call arrives on the main thread. Only leaving the player and formats without a decoder need an answer from
 * every app.
 */
interface PlaybackHost {
    /** Leave the player: Stop, or Close on an error. */
    fun onExit()

    /**
     * Media3 has no decoder for this media's video or audio, such as "AV1". Reported once per media; playback
     * would otherwise continue as sound over a black screen, or as a silent picture.
     */
    fun onUnsupportedFormat(format: String)

    /** Readiness, playing state, first frame, tracks or errors changed. */
    fun onPlaybackChanged(snapshot: PlaybackSnapshot) {}

    /**
     * Each second while playing, and when the media pauses, seeks, ends, or is replaced or stopped: worth saving.
     * The snapshot still describes the outgoing media when another replaces it.
     */
    fun onProgress(snapshot: PlaybackSnapshot) {}

    /** The media played to its end. */
    fun onEnded() {}

    /** Plays the next or previous episode; returns false when there is none. */
    fun onNextEpisode(): Boolean {
        return false
    }

    fun onPreviousEpisode(): Boolean {
        return false
    }

    /** Whether the failed media can be opened again; if so, [onRetry] opens it. */
    fun canRetry(): Boolean {
        return false
    }

    fun onRetry() {}

    /** Whether another source can replace one that failed; if so, [onChooseSource] offers them. */
    fun canChooseSource(): Boolean {
        return false
    }

    fun onChooseSource() {}

    /** The app's rows in the options menu: before the track settings when `leading`, else after them. */
    fun menuItems(leading: Boolean): List<MenuPanel.Item> {
        return Collections.emptyList()
    }

    /**
     * More subtitles the app can add, such as files its providers offer, listed after the loaded tracks on the
     * Subtitles page; choosing one is the app's to handle.
     */
    fun subtitleItems(): List<MenuPanel.Item> {
        return Collections.emptyList()
    }

    /** Handles a remote key before the player does, after the open menu and the up-next card. */
    fun onKey(keyCode: Int): Boolean {
        return false
    }

    /** The player layers were updated; apps update their own, such as an idle screen. */
    fun onScreenChanged() {}

    fun onMenuToggled(open: Boolean) {}
}
