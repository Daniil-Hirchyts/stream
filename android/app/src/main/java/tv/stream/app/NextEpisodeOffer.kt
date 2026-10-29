package tv.stream.app

/** Offer a manual shortcut in the last 10%, capped at 90 seconds; never skip the current ending automatically. */
object NextEpisodeOffer {
    fun nearEnd(position: Long, duration: Long): Boolean {
        return duration > 0 && position >= duration / 2 && position < duration
                && duration - position <= Math.min(90_000L, duration / 10)
    }
}
