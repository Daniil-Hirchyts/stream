package tv.stream.app;

/** A bounded, advertised startup estimate, not a speed test. Never opens a torrent while ranking. */
final class AutoSourceRank {
    private AutoSourceRank() {}

    static int score(SourceSort.Facts facts, int outcome) {
        // With neither quality nor availability, don't invent a comparison from size alone. Preserve provider order.
        if (facts.lines == 0 && facts.seeds < 0) return outcome;
        // Prefer the requested HD range; retain unknown/SD sources as fallbacks. Recent failures still lose.
        int quality = facts.lines >= 720 && facts.lines <= 1080 ? 1000 : 0;
        if (facts.lines == 1080) quality += 40;
        // Each tenfold increase matters, but hundreds of peers cannot justify an enormous remux.
        double availability = facts.seeds > 0 ? 160 * Math.log10(1 + Math.min(100_000, facts.seeds))
                : facts.seeds == 0 ? -240 : 0;
        // Diminishing size penalty avoids always choosing the tiniest (often poor) encode.
        double size = facts.bytes > 0 ? 55 * Math.log1p(facts.bytes / (double) (512L << 20)) / Math.log(2) : 90;
        return quality + (int) Math.round(availability - size) + outcome;
    }
}
