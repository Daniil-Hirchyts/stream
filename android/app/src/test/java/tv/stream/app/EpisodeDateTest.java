package tv.stream.app;

import static org.junit.Assert.assertEquals;
import java.util.Locale;
import org.junit.Test;

public final class EpisodeDateTest {
    @Test public void releaseDatesKeepTheBroadcastDayAndIgnoreMissingOrInvalidValues() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.UK);
            assertEquals("12 Sept 2025", TitleScreens.episodeDate("2025-09-12T00:00:00.000Z"));
            assertEquals("29 Feb 2024", TitleScreens.episodeDate("2024-02-29"));
            assertEquals("", TitleScreens.episodeDate("2025-02-29"));
            assertEquals("", TitleScreens.episodeDate("unknown"));
            assertEquals("", TitleScreens.episodeDate(""));
            assertEquals("", TitleScreens.episodeDate(null));
        } finally { Locale.setDefault(previous); }
    }
}
