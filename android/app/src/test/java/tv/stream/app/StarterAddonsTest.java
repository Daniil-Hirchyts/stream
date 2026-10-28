package tv.stream.app;

import static org.junit.Assert.*;
import org.junit.Test;
import tv.stream.app.addon.AddonUrl;
import tv.stream.app.addon.BuiltinAddons;

public final class StarterAddonsTest {
    @Test public void internetArchiveIsNotInstalledByDefault() {
        assertFalse(StarterAddons.ALL.contains(BuiltinAddons.INTERNET_ARCHIVE));
        assertTrue(StarterAddons.ALL.contains(AddonUrl.CINEMETA));
        assertTrue(StarterAddons.ALL.contains(StarterAddons.OPENSUBTITLES));
    }
}
