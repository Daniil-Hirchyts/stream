package tv.stream.app

/** A bounded, advertised startup estimate, not a speed test. Never opens a torrent while ranking. */
object AutoSourceRank {
    fun score(facts: SourceSort.Facts, outcome: Int): Int {
        // With neither quality nor availability, don't invent a comparison from size alone. Preserve provider order.
        if (facts.lines == 0 && facts.seeds < 0) return outcome
        // Prefer the requested HD range; retain unknown/SD sources as fallbacks. Recent failures still lose.
        var quality = if (facts.lines >= 720 && facts.lines <= 1080) 1000 else 0
        if (facts.lines == 1080) quality += 40
        // Each tenfold increase matters, but hundreds of peers cannot justify an enormous remux.
        val availability: Double = if (facts.seeds > 0) 160 * Math.log10((1 + Math.min(100_000, facts.seeds)).toDouble())
                else if (facts.seeds == 0) -240.0 else 0.0
        // Diminishing size penalty avoids always choosing the tiniest (often poor) encode.
        val size: Double = if (facts.bytes > 0) 55 * Math.log1p(facts.bytes / (512L shl 20).toDouble()) / Math.log(2.0) else 90.0
        return quality + Math.round(availability - size).toInt() + outcome
    }
}
