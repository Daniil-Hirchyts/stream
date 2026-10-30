package tv.stream.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import tv.stream.app.addon.Title

/** Explicit screenshot utility, not a regression gate. Uses the harness's isolated database and generated media. */
class DesignAuditCapture : AppHarness() {
    @Test @Throws(Exception::class) fun before() { capture("before") }
    @Test @Throws(Exception::class) fun after() { capture("after") }
    @Test @Throws(Exception::class) fun browseAfter() { capture("after", browseOnly = true) }
    @Test @Throws(Exception::class) fun otherStates() {
        val phase = "other"
        launch(); waitUntil("empty Home", 10000) { visible("Make yourself at home") }; shot(phase, "22-empty-home")
        browse!!.onActivity { activity -> activity.root("Library") }; waitUntil("empty Library", 5000) { visible("Your library is empty") }; shot(phase, "23-empty-library")
        browse!!.onActivity { activity -> activity.search() }; waitUntil("empty Search", 5000) { visible("Type a") }; shot(phase, "25-empty-search")
        browse!!.onActivity { activity -> activity.root("Settings") }; tap("Playback", false); waitUntil("Playback settings", 5000) { visible("Playback cache") }; shot(phase, "26-playback-settings")
        tap("Subtitles", true); tap("Subtitle background", false); waitUntil("edge choices", 5000) { visible("Dark box") }; shot(phase, "27-settings-edge")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returned()
        readyLibrary("ui"); browse!!.onActivity { activity -> activity.root("Home") }; waitUntil("fixture Home", 10000) { visible("Generated Film") }
        tap("Generated Film", false); tap("Sources", true); waitUntil("sources", 10000) { visible("Local torrent") }; shot(phase, "28-film-sources")
        browse!!.onActivity { activity -> activity.root("Settings") }; tap("Add-ons", true); tap("Add-on store", false); waitUntil("store", 10000) { visible("Add-on store") }; shot(phase, "29-addon-store")
    }
    @Test @Throws(Exception::class) fun providerFailures() {
        val url = "$origin/addon/broken/ui/manifest.json"
        services.addons.newRequest().use { request -> services.addons.install(url, services.addons.preview(url, request)) }
        launch(); waitUntil("failed Home", 10000) { visible("Unable to load Home") }; shot("other", "30-home-failure")
        browse!!.onActivity { activity -> activity.search() }; browse!!.onActivity { activity -> activity.searchInput("Generated") }
        waitUntil("failed search", 10000) { visible("Search unavailable") }; shot("other", "31-search-failure")
        browse!!.onActivity { activity -> activity.push(Route("Catalogues")) }; waitUntil("categories", 10000) { visible("Generated films") }
        tap("Generated films", true); waitUntil("failed catalogue", 30000) { visible("Unable to load catalogue") }; shot("other", "32-catalogue-failure")
    }
    @Throws(Exception::class)
    private fun capture(phase: String, browseOnly: Boolean = false) {
        readyLibrary("http-mkv"); launch(); waitUntil("Home", 10000) { visible("Generated Film") }; shot(phase, "01-home")
        tap("Generated Film", false); waitUntil("film actions", 10000) { visible("Play") }; shot(phase, "02-film")
        tap("Mark watched", true); waitUntil("watched action", 5000) { visible("Mark unwatched") }; shot(phase, "03-film-watched")
        tap("More", false); waitUntil("film menu", 5000) { visible("Remove from library") }; shot(phase, "04-film-menu")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); returned()
        browse!!.onActivity { activity -> activity.root("Library") }; waitUntil("Library", 5000) { visible("Generated Film") }; shot(phase, "05-library")
        browse!!.onActivity { activity -> activity.push(Route("Catalogues")) }; waitUntil("Browse", 5000) { visible("Generated films") }; shot(phase, "07-browse")
        browse!!.onActivity { activity -> activity.search() }; waitUntil("Search", 5000) { visible("Type a") }
        browse!!.onActivity { activity -> activity.searchInput("Generated") }; waitUntil("search results", 10000) { visible("Generated Film") }; shot(phase, "08-search-keyboard")
        browse!!.onActivity { activity -> org.junit.Assert.assertNotNull("Focusable keyboard:f", focusKey(activity, "keyboard:f")) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); waitUntil("full search", 5000) { !visible("Type a") }; shot(phase, "09-search-grid")
        browse!!.onActivity { activity -> activity.root("Settings") }; waitUntil("Settings", 5000) { visible("Languages") }; shot(phase, "10-settings")
        tap("Subtitles", false); waitUntil("subtitle settings", 5000) { visible("Subtitle color") }; shot(phase, "11-subtitle-settings")
        tap("Subtitle color", true); waitUntil("color options", 5000) { visible("White") }; shot(phase, "12-settings-popup")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returned()
        tap("Torrents", false); tap("Imports", true); waitUntil("Imports", 5000) { visible("Add magnet or torrent URL") }; shot(phase, "13-imports")
        tap("Add magnet or torrent URL", false); waitUntil("import form", 5000) { visible("Continue") }; shot(phase, "14-import-popup")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returned()
        val episodes = JSONArray(); for (i in 1..4) episodes.put(JSONObject().put("id", "audit:episode:$i")
                .put("season", 1).put("episode", i).put("title", "Episode $i").put("overview", "A short generated synopsis for reviewing episode layout and navigation."))
        val show = Title.parse("local", JSONObject().put("id", "audit:series").put("type", "series").put("name", "Audit series").put("videos", episodes))!!
        browse!!.onActivity { activity -> activity.openTitle(show, "") }; waitUntil("series", 5000) { visible("Play") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); waitUntil("seasons", 5000) { visible("Season 1") && !visible("Play") }; shot(phase, "15-series")
        tap("Episode 1", true); waitUntil("episode menu", 5000) { visible("Choose source") }; shot(phase, "16-episode-popup")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returned()
        if (browseOnly) return
        browse!!.onActivity { activity -> activity.root("Home") }; waitUntil("Home again", 5000) { visible("Generated Film") }
        tap("Generated Film", false); tap("Play", true); chooseFirstSource(); waitUntil("playing", 30000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE); waitUntil("paused", 5000) { !state()!!.playWhenReady }; shot(phase, "17-player")
        control(R.id.control_options, true); waitUntil("tracks menu", 5000) { visible("Off") }; shot(phase, "18-player-tracks")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); playerReturned()
        control(R.id.control_settings, false); waitUntil("player settings", 5000) { visible("Speed") }; shot(phase, "19-player-settings")
        tap("Subtitle style", true); waitUntil("style menu", 5000) { visible("Font") }; shot(phase, "20-player-style")
        tap("Font", false); waitUntil("font choices", 5000) { visible(context.getString(R.string.subtitle_font_noto)) }; shot(phase, "21-player-font")
        instrumentation.runOnMainSync(watch()!!::finish)
    }
    private fun returned() { waitUntil("browse window returns", 5000) { val focus = AtomicBoolean(); browse!!.onActivity { activity -> focus.set(activity.hasWindowFocus()) }; focus.get() } }
    private fun playerReturned() { waitUntil("player window returns", 5000) { val activity = watch(); if (activity == null) return@waitUntil false; val focus = AtomicBoolean(); instrumentation.runOnMainSync { focus.set(activity.hasWindowFocus()) }; focus.get() && !visible("Off") } }
    @Throws(Exception::class)
    private fun shot(phase: String, name: String) {
        SystemClock.sleep(400); val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
        if (bitmap == null) throw AssertionError("Screenshot unavailable: $name")
        val folder = File(context.filesDir, "design-audit-$phase"); folder.mkdirs()
        FileOutputStream(File(folder, "$name.png")).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }; bitmap.recycle()
    }
}
