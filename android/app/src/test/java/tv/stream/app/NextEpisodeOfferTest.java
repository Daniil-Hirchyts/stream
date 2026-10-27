package tv.stream.app;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NextEpisodeOfferTest {
    @Test public void offersOnlyNearAnActualKnownEnding() {
        assertFalse(NextEpisodeOffer.nearEnd(0, 0));
        assertFalse(NextEpisodeOffer.nearEnd(50_000, 0));
        assertFalse(NextEpisodeOffer.nearEnd(1_200_000, 2_400_000));
        assertFalse(NextEpisodeOffer.nearEnd(2_300_000, 2_400_000));
        assertTrue(NextEpisodeOffer.nearEnd(2_310_000, 2_400_000));
        assertTrue(NextEpisodeOffer.nearEnd(54_000, 60_000));
        assertFalse(NextEpisodeOffer.nearEnd(60_000, 60_000));
    }
}
