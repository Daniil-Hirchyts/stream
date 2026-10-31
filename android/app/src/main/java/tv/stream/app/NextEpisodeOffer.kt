package tv.stream.app

import tv.stream.app.addon.Title

/** Offer a manual shortcut in the last 10%, capped at 90 seconds; never skip the current ending automatically. */
object NextEpisodeOffer {
    fun nearEnd(position: Long, duration: Long): Boolean {
        return duration > 0 && position >= duration / 2 && position < duration
                && duration - position <= Math.min(90_000L, duration / 10)
    }

    /**
     * Whether `episode` can be offered as the next one now: it has aired by `now`, or its provider gives no date. The
     * same rule as Next Up and new-episode notices ([NewEpisodes.aired]), so the player never offers an announced
     * episode that has no sources yet.
     */
    fun released(episode: Title.Episode?, now: Long): Boolean {
        return episode != null && NewEpisodes.aired(episode.released) <= now
    }

    /** "S2 E7 · Ace of Spades", as the player's toolbar shows it; empty when `video` is not one of `episodes`. */
    fun episodeLabel(episodes: List<Title.Episode>, video: String?): String {
        val e = episodes.firstOrNull { it.id == video } ?: return ""
        return "S" + e.season + " E" + e.number + " · " + e.name
    }

    /** The line under the title while a source opens and on the toolbar: only a series' episode has one. */
    fun watchingLabel(type: String?, episodes: List<Title.Episode>, video: String?): String {
        return if (type == "series") episodeLabel(episodes, video) else ""
    }
}
