package tv.daniil.stream;

/** Time can suggest an exit choice, but never decides that the story has finished. */
final class WatchCompletion {
    private WatchCompletion() {}

    static boolean offerOnExit(long position, long duration, boolean watched) {
        return !watched && duration > 0 && position >= 30_000 && position < duration
                && duration - position <= Math.min(180_000, duration / 10);
    }
}
