package tv.stream.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import tv.stream.app.addon.AddonUrl
import tv.stream.app.addon.BuiltinAddons
import tv.stream.app.addon.Title

/**
 * Explicit capture utility for the README, not a regression gate: `./simulator.sh readme-media` runs it on the verified
 * emulator and turns its output into `docs/media`. It uses real providers over the internet with an isolated database
 * (nothing reaches owner data) and shows public-domain films only: Cinemeta supplies their metadata, the built-in
 * Internet Archive add-on their catalogue and streams. Cinemeta's own catalogues are hidden, so every row is
 * public-domain.
 */
class ReadmeCapture : AppHarness() {
    private val films = listOf(
        "tt0063350" to "Night of the Living Dead",
        "tt0013442" to "Nosferatu",
        "tt0017925" to "The General",
        "tt0032599" to "His Girl Friday",
        "tt0056923" to "Charade",
    )
    private lateinit var cinemeta: String
    private val output by lazy { File(context.filesDir, "readme").also { it.deleteRecursively(); it.mkdirs() } }

    @Before fun internet() {
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().putBoolean("localOnly", false)
            .putBoolean("publicNetworkOnly", true).remove("peer").remove("cacheBytes").remove("addonDirectory").commit()
        app.resetServices(); services = app.services()
        for (url in listOf(AddonUrl.CINEMETA, BuiltinAddons.INTERNET_ARCHIVE, StarterAddons.OPENSUBTITLES)) {
            services.addons.newRequest().use { request -> services.addons.install(url, services.addons.preview(url, request)) }
        }
        val installed = services.store.addons()
        val meta = installed.first { it.manifest.name == "Cinemeta" }
        cinemeta = meta.id
        for (c in meta.manifest.catalogs) services.store.catalogVisible(meta.id, c.type, c.id, false)
    }

    @Test @Throws(Exception::class) fun capture() {
        launch()
        waitUntil("Home rows", 60_000) { visible("Public Domain Films") }
        settle(4000)
        // A small library, added with the remote from the public-domain row, so the favourites carry its artwork.
        for (i in 0 until 3) {
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitUntil("details $i", 30_000) { visible("Play") }; settle(1500)
            tap("Add favourite", false); waitUntil("favourite $i", 10_000) { visible("Remove favourite") }
            key(KeyEvent.KEYCODE_BACK); waitUntil("Home again", 15_000) { visible("Public Domain Films") }; settle(1500)
            repeat(i + 1) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(300) }
        }
        root("Library"); pause(500); root("Home")
        waitUntil("Home with favourites", 30_000) { visible("Favourites") && visible("Public Domain Films") }
        settle(4000)

        record("home", 16) {
            pause(1500)
            repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(700) }
            key(KeyEvent.KEYCODE_DPAD_DOWN); pause(900)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(700) }
            key(KeyEvent.KEYCODE_DPAD_UP); pause(900)
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitUntil("details", 20_000) { visible("Play") }; pause(3500)
        }
        shot("details")
        key(KeyEvent.KEYCODE_BACK); waitUntil("Home again", 10_000) { visible("Public Domain Films") }; settle(2500)
        shot("home")

        // Search asks every catalogue add-on; Cinemeta's would bring current films, so it sits out this scene.
        services.addons.enable(cinemeta, false)
        browse!!.onActivity { it.search() }; waitUntil("keyboard", 10_000) { visible("Type a") }; settle(1200)
        // The Archive's search can take longer than a recording; a first pass warms it, so the recorded one is prompt.
        typeWithRemote("night"); waitUntil("first results", 90_000) { visible("Night Tide") }
        tap("Clear", false); waitUntil("cleared", 10_000) { visible("Type a") }; settle(1500)
        record("search", 40) {
            pause(1000)
            typeWithRemote("night")
            waitUntil("results", 30_000) { visible("Night Tide") }; pause(2500)
            key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(2500)
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(700) }
            key(KeyEvent.KEYCODE_DPAD_LEFT); pause(700); key(KeyEvent.KEYCODE_DPAD_LEFT); pause(700)
            key(KeyEvent.KEYCODE_DPAD_LEFT); pause(2500)
        }
        shot("search")
        services.addons.enable(cinemeta, true)

        root("Library"); waitUntil("Library", 20_000) { visible("Favourites") || !visible("Your library is empty") }; settle(2000); shot("library")
        root("Settings"); tap("Add-ons", false); waitUntil("installed add-ons", 10_000) { visible("Internet Archive") }; settle(1500); shot("settings-addons")
        tap("Subtitles", false); waitUntil("subtitle settings", 10_000) { visible("Subtitle color") }; settle(1500); shot("settings-subtitles")

        // The first favourite came from the Internet Archive's catalogue row, so the Archive has it: an original print.
        root("Home"); waitUntil("Home", 30_000) { visible("Favourites") }; settle(2500)
        browse!!.onActivity { activity -> org.junit.Assert.assertNotNull(focusKey(activity, firstFavourite())) }
        pause(800); key(KeyEvent.KEYCODE_DPAD_CENTER); waitUntil("details", 30_000) { visible("Play") }; settle(2500)
        tap("Sources", true)
        // Rows read "Internet Archive 1080p" and so on; the sort chips and the count come with them.
        try { waitUntil("sources", 150_000) { visible("Seeds") && visible("Played") && !visible("Finding sources") } } catch (e: AssertionError) { shot("sources-timeout"); throw e }
        settle(5000); shot("sources")
        key(KeyEvent.KEYCODE_BACK); waitUntil("details again", 10_000) { visible("Play") }
        tap("Play", true)
        waitUntil("the film plays from archive.org", 180_000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs >= 3000 }
        val player = watch()
        instrumentation.runOnMainSync { AppFlowTest.session(player).seekTo(9L * 60_000) }
        waitUntil("playing after the seek", 90_000) { state()!!.playing && state()!!.positionMs >= 9L * 60_000 + 3000 }
        pause(4000)
        record("player", 18) {
            pause(2000)
            key(KeyEvent.KEYCODE_DPAD_CENTER); pause(2500)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pause(600) }
            pause(1500)
            key(KeyEvent.KEYCODE_MENU); pause(2500)
            key(KeyEvent.KEYCODE_DPAD_DOWN); pause(900); key(KeyEvent.KEYCODE_DPAD_DOWN); pause(1500)
            key(KeyEvent.KEYCODE_BACK); pause(1200)
            // Focus is on a toolbar button now; the remote's Play key resumes from anywhere.
            key(KeyEvent.KEYCODE_MEDIA_PLAY); pause(3000)
        }
        if (!state()!!.playWhenReady) key(KeyEvent.KEYCODE_MEDIA_PLAY)
        waitUntil("playing again", 60_000) { state()!!.playing }
        pause(6000)
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); pause(2000); shot("player-controls")
        key(KeyEvent.KEYCODE_MENU); pause(2500); shot("player-menu")
        key(KeyEvent.KEYCODE_BACK); pause(800); key(KeyEvent.KEYCODE_BACK); pause(800)
        if (watch() != null) key(KeyEvent.KEYCODE_BACK)
        waitUntil("player closed", 15_000) { watch() == null }
        root("Home"); waitUntil("Continue watching", 30_000) { visible("Continue watching") || visible("Continue Watching") }; settle(2500); shot("home-continue")
    }

    private fun firstFavourite(): String = "favourite:" + services.store.library(false).first { it.favourite }.title.key()

    private fun open(id: String, name: String) {
        val title = Title.parse(cinemeta, JSONObject().put("id", id).put("type", "movie").put("name", name))!!
        browse!!.onActivity { it.openTitle(title, "") }
        waitUntil("$name details", 30_000) { visible("Play") }
        settle(2500)
    }

    private fun root(screen: String) = browse!!.onActivity { it.root(screen) }

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
        val recording = instrumentation.uiAutomation.executeShellCommand("screenrecord --time-limit $seconds --bit-rate 12000000 $path")
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
