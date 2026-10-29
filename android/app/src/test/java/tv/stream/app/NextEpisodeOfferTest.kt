package tv.stream.app

import org.junit.Assert.*
import org.junit.Test

class NextEpisodeOfferTest {
    @Test fun offersOnlyNearAnActualKnownEnding() {
        assertFalse(NextEpisodeOffer.nearEnd(0L, 0L))
        assertFalse(NextEpisodeOffer.nearEnd(50_000L, 0L))
        assertFalse(NextEpisodeOffer.nearEnd(1_200_000L, 2_400_000L))
        assertFalse(NextEpisodeOffer.nearEnd(2_300_000L, 2_400_000L))
        assertTrue(NextEpisodeOffer.nearEnd(2_310_000L, 2_400_000L))
        assertTrue(NextEpisodeOffer.nearEnd(54_000L, 60_000L))
        assertFalse(NextEpisodeOffer.nearEnd(60_000L, 60_000L))
    }
}
