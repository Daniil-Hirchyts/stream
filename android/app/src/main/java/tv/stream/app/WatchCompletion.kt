package tv.stream.app

/** Time can suggest an exit choice, but never decides that the story has finished. */
object WatchCompletion {
    @JvmStatic fun offerOnExit(position: Long, duration: Long, watched: Boolean): Boolean {
        return !watched && position >= 30_000 && position < duration && nearlyFinished(position, duration)
    }
    /** Stopped in the closing minutes: at most the last tenth, capped at three minutes (usually the credits). */
    @JvmStatic fun nearlyFinished(position: Long, duration: Long): Boolean {
        return duration > 0 && duration - position <= Math.min(180_000L, duration / 10)
    }
}
