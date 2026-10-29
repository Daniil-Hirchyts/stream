package tv.stream.app

import org.junit.Assert.*
import org.junit.Test
import tv.stream.app.addon.AddonUrl
import tv.stream.app.addon.BuiltinAddons

class StarterAddonsTest {
    @Test fun internetArchiveIsNotInstalledByDefault() {
        assertFalse(StarterAddons.ALL.contains(BuiltinAddons.INTERNET_ARCHIVE))
        assertTrue(StarterAddons.ALL.contains(AddonUrl.CINEMETA))
        assertTrue(StarterAddons.ALL.contains(StarterAddons.OPENSUBTITLES))
    }
}
