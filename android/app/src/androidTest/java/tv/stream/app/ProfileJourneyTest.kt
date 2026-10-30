package tv.stream.app

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Representative journeys for the app's Baseline and Startup Profiles, on isolated local fixtures. They assert only
 * that each journey reached its screen; `simulator.sh baseline-profile` resets the app's ART profile, runs one
 * journey, asks ART to save what ran (SIGUSR1, as androidx.profileinstaller does) and dumps it. `journey=startup`
 * covers a cold launch to Home; the default covers browsing, Details, Settings, Search and fixture playback.
 */
@RunWith(AndroidJUnit4::class)
class ProfileJourneyTest : AppHarness() {
    @Test fun journey() {
        readyLibrary()
        launch()
        waitUntil("Home", 15_000) { visible("Generated Film") }
        if (InstrumentationRegistry.getArguments().getString("journey") != "startup") browse()
        saveProfile()
    }

    private fun browse() {
        tap("Generated Film", false)
        waitUntil("Details", 10_000) { visible("Play") && visible("Sources") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("Home again", 10_000) { visible("Generated Film") }
        for (screen in arrayOf("Browse", "Library", "Settings")) {
            browse!!.onActivity { it.root(screen) }
            waitUntil(screen, 10_000) { visible(screen) }
        }
        tap("Playback", false); waitUntil("Playback settings", 5_000) { visible("Playback cache") }
        tap("Subtitles", false); waitUntil("Subtitle settings", 5_000) { visible("Subtitle size") }
        repeat(4) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN) }
        browse!!.onActivity { it.root("Home") }
        waitUntil("Home", 10_000) { visible("Generated Film") }
        browse!!.onActivity { it.search() }
        waitUntil("Search", 10_000) { visible("Search") }
        for (key in intArrayOf(KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_N)) instrumentation.sendKeyDownUpSync(key)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        tap("Generated Film", false); tap("Play", true); chooseFirstSource()
        waitUntil("Fixture frames", 60_000) { state()?.let { it.renderedFirstFrame && it.positionMs >= 2_000 } == true }
        // Show the controls and seek once, then leave the player.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(1_500)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        Thread.sleep(1_500)
        repeat(3) { if (watch() != null) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); Thread.sleep(700) } }
        waitUntil("Player closed", 15_000) { watch() == null }
    }

    /** ART writes the methods and classes that ran to the app's current profile on SIGUSR1. */
    private fun saveProfile() {
        android.os.Process.sendSignal(android.os.Process.myPid(), 10)
        Thread.sleep(2_000)
    }
}
