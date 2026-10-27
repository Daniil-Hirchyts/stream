package tv.stream.app;

/** Offer a manual shortcut in the last 10%, capped at 90 seconds; never skip the current ending automatically. */
final class NextEpisodeOffer {
    private NextEpisodeOffer() {}
    static boolean nearEnd(long position, long duration) {
        return duration > 0 && position >= duration / 2 && position < duration
                && duration - position <= Math.min(90_000, duration / 10);
    }
}
