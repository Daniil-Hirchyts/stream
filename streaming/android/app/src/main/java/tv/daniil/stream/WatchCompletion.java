package tv.daniil.stream;

/** Time can suggest an exit choice, but never decides that the story has finished. */
final class WatchCompletion {
    private WatchCompletion() {}

    static boolean offerOnExit(long position, long duration, boolean watched) {
        return !watched && position >= 30_000 && position < duration && nearlyFinished(position, duration);
    }
    /** Stopped in the closing minutes: at most the last tenth, capped at three minutes (usually the credits). */
    static boolean nearlyFinished(long position, long duration) {
        return duration > 0 && duration - position <= Math.min(180_000, duration / 10);
    }
}
