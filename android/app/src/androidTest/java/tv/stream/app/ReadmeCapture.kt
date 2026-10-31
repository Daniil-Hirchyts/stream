package tv.stream.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Before
import org.junit.Test
import tv.stream.app.addon.AddonUrl
import tv.stream.app.addon.BuiltinAddons
import tv.stream.app.addon.Title

/**
 * Explicit capture utility for the README, not a regression gate: `./simulator.sh readme-media` runs it on the verified
 * emulator and turns its output into `docs/media`. It uses real providers over the internet with an isolated database
 * (nothing reaches owner data). Browsing scenes show Cinemeta's current catalogue (titles, posters and artwork as the
 * app displays them); nothing from it is played. The only film that plays on camera is Tears of Steel (2012),
 * © Blender Foundation, CC BY 3.0, from the Blender Foundation’s download server. No torrent add-on is installed.
 */
class ReadmeCapture : AppHarness() {
    private val playbackOnly get() = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("capturePlaybackOnly") == "true"
    private val output by lazy { File(context.filesDir, "readme").also { if (!playbackOnly) it.deleteRecursively(); it.mkdirs() } }

    @Before fun internet() {
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().putBoolean("localOnly", false)
            .putBoolean("publicNetworkOnly", true).remove("peer").remove("cacheBytes").remove("addonDirectory").commit()
        app.resetServices(); services = app.services()
        for (url in listOf(AddonUrl.CINEMETA, BuiltinAddons.INTERNET_ARCHIVE, StarterAddons.OPENSUBTITLES)) {
            services.addons.newRequest().use { request -> services.addons.install(url, services.addons.preview(url, request)) }
        }
        val installed = services.store.addons()
        // Keep browsing on Cinemeta’s current catalogue; playback uses the explicit licensed source below.
        val archive = installed.first { it.manifest.name == "Internet Archive" }
        for (c in archive.manifest.catalogs) services.store.catalogVisible(archive.id, c.type, c.id, false)
    }

    @Test @Throws(Exception::class) fun capture() {
        launch()
        if (!playbackOnly) {
            waitUntil("Home rows", 60_000) { visible("Popular Films") }
            settle(4000)
            // A small library, added with the remote from the first catalogue row, so the favourites carry its artwork.
            for (i in 0 until 3) {
                key(KeyEvent.KEYCODE_DPAD_CENTER); waitUntil("details $i", 30_000) { visible("Play") }; settle(1500)
                tap("Add favourite", false); waitUntil("favourite $i", 10_000) { visible("Remove favourite") }
                key(KeyEvent.KEYCODE_BACK); waitUntil("Home again", 15_000) { visible("Popular Films") }; settle(1500)
                repeat(i + 1) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(300) }
            }
            root("Library"); pause(500); root("Home")
            waitUntil("Home with favourites", 30_000) { visible("Favourites") && visible("Popular Films") }
            settle(4000)

            // Start at the top of Home, the spotlight over the first favourite.
            focusTop("favourite:" + services.store.library(false).first { it.favourite }.title.key())
            settle(3000)
            record("home", 18) {
                pause(1500)
                repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(900) }
                key(KeyEvent.KEYCODE_DPAD_DOWN); pause(1100)
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(800) }
                key(KeyEvent.KEYCODE_DPAD_DOWN); pause(1100); key(KeyEvent.KEYCODE_DPAD_UP); pause(1100)
                // A film: the capture installs no series stream add-on, and a series would say so on its page.
                key(KeyEvent.KEYCODE_DPAD_CENTER); waitUntil("details", 20_000) { visible("Play") || visible("Resume") }; pause(3500)
            }
            shot("details")
            key(KeyEvent.KEYCODE_BACK); waitUntil("Home again", 10_000) { visible("Popular Films") }; settle(2500)
            focusTop("favourite:" + services.store.library(false).first { it.favourite }.title.key())
            settle(2500)
            shot("home")

            browse!!.onActivity { it.search() }; waitUntil("keyboard", 10_000) { visible("Type a") }; settle(1200)
            // A first pass warms the providers' search, so the recorded one is prompt.
            typeWithRemote("dune")
            try { waitUntil("first results", 60_000) { visible("Dune") } } catch (e: AssertionError) { shot("search-timeout"); throw e }
            tap("Clear", false); waitUntil("cleared", 10_000) { visible("Type a") }; settle(1500)
            record("search", 40) {
                pause(1000)
                typeWithRemote("dune")
                waitUntil("results", 30_000) { visible("Dune") }; pause(2500)
                key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(2500)
                repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(700) }
                key(KeyEvent.KEYCODE_DPAD_DOWN); pause(1200)
                key(KeyEvent.KEYCODE_DPAD_LEFT); pause(700); key(KeyEvent.KEYCODE_DPAD_LEFT); pause(700)
                key(KeyEvent.KEYCODE_DPAD_LEFT); pause(2500)
            }
            shot("search")

            root("Library"); waitUntil("Library", 20_000) { visible("Favourites") || !visible("Your library is empty") }; settle(2000); shot("library")
            root("Settings"); tap("Add-ons", false); waitUntil("installed add-ons", 10_000) { visible("Cinemeta") }; settle(1500); shot("settings-addons")
            tap("Subtitles", false); waitUntil("subtitle settings", 10_000) { visible("Subtitle color") }; settle(1500); shot("settings-subtitles")

        }
        // A capture-only inline source points to the freely licensed film on the Blender Foundation’s download server.
        root("Home"); waitUntil("Home", 30_000) { visible("Popular Films") }; settle(1500)
        openLicensedFilm()
        tap("Sources", true)
        // The Sources page displays the capture-only licensed source and its sort controls.
        try { waitUntil("sources", 150_000) { visible("Seeds") && visible("Played") && !visible("Finding sources") } } catch (e: AssertionError) { shot("sources-timeout"); throw e }
        settle(5000); shot("sources")
        key(KeyEvent.KEYCODE_BACK); waitUntil("details again", 10_000) { visible("Play") }
        tap("Play", true)
        waitUntil("the licensed film plays", 180_000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs >= 3000 }
        // Let the remote film play forward: a large remote seek can stall on a cold range request.
        waitUntil("the opening scene", 90_000) { state()!!.playing && state()!!.positionMs >= 45_000 }
        pause(4000)
        record("player", 18) {
            pause(2000)
            key(KeyEvent.KEYCODE_DPAD_CENTER); pause(2500)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(600) }
            pause(1500)
            control(R.id.control_settings, false); pause(2500)
            key(KeyEvent.KEYCODE_DPAD_DOWN); pause(900); key(KeyEvent.KEYCODE_DPAD_DOWN); pause(1500)
            key(KeyEvent.KEYCODE_BACK); pause(1200)
            // Focus is on a toolbar button now; the remote's Play key resumes from anywhere.
            key(KeyEvent.KEYCODE_MEDIA_PLAY); pause(3000)
        }
        if (!state()!!.playWhenReady) key(KeyEvent.KEYCODE_MEDIA_PLAY)
        waitUntil("playing again", 60_000) { state()!!.playing }
        pause(6000)
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); pause(2000); shot("player-controls")
        control(R.id.control_settings, false); pause(2500); shot("player-menu")
        key(KeyEvent.KEYCODE_BACK); pause(800); key(KeyEvent.KEYCODE_BACK); pause(800)
        if (watch() != null) key(KeyEvent.KEYCODE_BACK)
        waitUntil("player closed", 15_000) { watch() == null }
        root("Home"); waitUntil("Continue watching", 30_000) { visible("Continue watching") || visible("Continue Watching") }; settle(1500)
        // Continue watching tiles are keyed by title and video: a film's video is its own id.
        focusTop("continue:" + services.store.library(false).first { it.title.id == "readme:tears-of-steel" }.title.key() + ":readme:tears-of-steel")
        settle(2500); shot("home-continue")
    }

    private fun openLicensedFilm() {
        val id = "readme:tears-of-steel"
        val source = JSONObject().put("name", "Tears of Steel 720p")
            .put("url", "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov")
        val video = JSONObject().put("id", id).put("streams", JSONArray().put(source))
        val title = Title.parse("readme-capture", JSONObject().put("id", id).put("type", "movie")
            .put("name", "Tears of Steel").put("videos", JSONArray().put(video)))!!
        browse!!.onActivity { it.openTitle(title, "") }
        waitUntil("Tears of Steel details", 30_000) { visible("Play") }
        settle(2500)
    }

    private fun root(screen: String) = browse!!.onActivity { it.root(screen) }

    /** Focuses `key` in one of Home's first rows, scrolling the page back to its top first so the row is laid out. */
    private fun focusTop(key: String) {
        browse!!.onActivity { it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.page_rows).scrollToPosition(0) }
        waitUntil("focus $key", 10_000) {
            val focused = AtomicBoolean()
            browse!!.onActivity { activity -> focused.set(focusKey(activity, key) != null) }
            focused.get()
        }
    }

    /** Types `word` on the on-screen keyboard with D-pad moves, six keys a row, as a remote would. */
    private fun typeWithRemote(word: String) {
        browse!!.onActivity { activity -> org.junit.Assert.assertNotNull(focusKey(activity, "keyboard:a")) }
        var row = 0; var column = 0
        pause(600)
        for (c in word) {
            val i = c - 'a'; val r = i / 6; val col = i % 6
            while (row < r) { key(KeyEvent.KEYCODE_DPAD_DOWN); row++; pause(160) }
            while (row > r) { key(KeyEvent.KEYCODE_DPAD_UP); row--; pause(160) }
            while (column < col) { key(KeyEvent.KEYCODE_DPAD_RIGHT); column++; pause(160) }
            while (column > col) { key(KeyEvent.KEYCODE_DPAD_LEFT); column--; pause(160) }
            pause(250); key(KeyEvent.KEYCODE_DPAD_CENTER); pause(450)
        }
    }

    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code) }
    private fun pause(ms: Long) = SystemClock.sleep(ms)

    /** Lets artwork and animations settle, then waits for the browse window to have focus. */
    private fun settle(ms: Long) {
        pause(ms)
        waitUntil("window focus", 10_000) {
            val focus = AtomicBoolean(true)
            browse?.onActivity { a -> focus.set(a.hasWindowFocus()) }
            focus.get() || watch() != null
        }
    }

    /** Records the screen while `scene` runs, through the shell; screenrecord stops at its limit or when the scene ends. */
    private fun record(name: String, seconds: Int, scene: () -> Unit) {
        val path = "/data/local/tmp/readme-$name.mp4"
        instrumentation.uiAutomation.executeShellCommand("rm -f $path").close()
        val recording = instrumentation.uiAutomation.executeShellCommand("screenrecord --size 1280x720 --time-limit $seconds --bit-rate 4000000 $path")
        val drained = Thread {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(recording).use { it.readBytes() }
        }.apply { start() }
        pause(700)
        val started = SystemClock.elapsedRealtime()
        scene()
        if (SystemClock.elapsedRealtime() - started < seconds * 1000L) instrumentation.uiAutomation.executeShellCommand("pkill -INT screenrecord").close()
        drained.join(10_000)
    }

    private fun shot(name: String) {
        pause(500)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("Screenshot unavailable: $name")
        FileOutputStream(File(output, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
