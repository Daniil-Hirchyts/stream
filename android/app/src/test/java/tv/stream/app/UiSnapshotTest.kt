package tv.stream.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import tv.stream.app.addon.BuiltinAddons
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title

/**
 * Renders the real browse screens at 1920×1080 (TV, xhdpi) into PNGs for design review: real activity, layouts,
 * focus and artwork, with live Cinemeta and Internet Archive data. Not an assertion suite; run with
 * `./gradlew :app:testDebugUnitTest --tests '*UiSnapshotTest' -PuiSnapshots=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiSnapshotTest {
    private lateinit var out: File
    private lateinit var activity: MainActivity

    @Before fun keystore() {
        FakeKeyStore.install()
        out = File(System.getProperty("dtv.snapshotDir", "build/ui-snapshots"))
        out.mkdirs()
    }

    @Test @Throws(Exception::class) fun browseScreens() {
        val app: TvApplication = ApplicationProvider.getApplicationContext()
        val services: TvApplication.Services = app.services()
        val controller: ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        settle(3000)
        snap("00-home-empty"); reachable("00-home-empty")
        // The real first run: an empty Home leads to the store's starter page, whose Install adds them from live manifests.
        click("Get add-ons"); settle(2000)
        snap("00b-starters"); reachable("00b-starters")
        click("Install")
        waitFor({ services.store.addons().size >= 3 }, 30_000)
        settle(2000)
        val names = StringBuilder()
        for (a: tv.stream.app.data.InstalledAddon in services.store.addons()) names.append(a.manifest.name).append("; ")
        System.out.println("starter add-ons: " + names)

        lateinit var film: Title
        lateinit var series: Title
        services.addons.newRequest().use { scope: Cancellation ->
            val cinemeta = services.store.addons().get(0).id
            film = services.addons.metadata(cinemeta, "movie", "tt0063350", scope).items.get(0)
            series = services.addons.metadata(cinemeta, "series", "tt0052520", scope).items.get(0)
        }
        services.store.progress(film, film.id, 41 * 60_000L, 96 * 60_000L, false)
        services.store.progress(series, series.episodes.get(3).id, 12 * 60_000L, 25 * 60_000L, false)
        services.store.favourite(film, true)

        activity.root("Home"); settle(9000)
        snap("01-home"); reachable("01-home")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(2500)
        snap("02-home-focus-moved")
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN); settle(3000)
        snap("03-home-scrolled")
        for (i in 0 until 6) key(KeyEvent.KEYCODE_DPAD_DOWN)
        settle(3000)
        snap("03b-home-bottom")

        activity.openTitle(film, ""); settle(6000)
        snap("04-details-film"); reachable("04-details-film")
        for (i in 0 until 2) key(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle(1000)
        snap("04b-details-buttons")
        activity.openTitle(series, ""); settle(6000)
        snap("05-details-series"); reachable("05-details-series")
        for (i in 0 until 3) key(KeyEvent.KEYCODE_DPAD_DOWN)
        settle(3000)
        snap("05b-series-episodes")
        activity.sources(film, film.id); settle(3000)
        waitFor({ activity.getWindow().getDecorView().findFocus() != null }, 40_000); settle(1000)
        snap("06-sources"); reachable("06-sources")

        activity.root("Library"); settle(4000)
        snap("07-library"); reachable("07-library")
        for (i in 0 until 3) key(KeyEvent.KEYCODE_DPAD_DOWN)
        settle(2000)
        snap("07b-library-scrolled")
        activity.root("Add-ons"); settle(9000)
        snap("08-store"); reachable("08-store")
        for (i in 0 until 8) key(KeyEvent.KEYCODE_DPAD_DOWN)
        settle(2000)
        snap("08b-store-scrolled")
        val review = Route("Add-on review"); review.value = BuiltinAddons.INTERNET_ARCHIVE
        activity.push(review); settle(3000)
        snap("09-store-review"); reachable("09-store-review")
        activity.root("Settings"); settle(3000)
        snap("10-settings"); reachable("10-settings")
        click("Languages"); settle(800)
        snap("10a-settings-languages")
        click("Audio language"); settle(1500)
        snapDialog("10b-settings-choice")
        ShadowDialog.getLatestDialog().dismiss(); settle(500)
        activity.alert("Playback diagnostics", java.lang.String.join(" ", java.util.Collections.nCopies(90, "A long message scrolls inside the alert.")))
        settle(1000)
        snapDialog("10c-long-alert")
        ShadowDialog.getLatestDialog().dismiss(); settle(500)
        // Left returns to the lit category; Down walks the categories and the pane follows.
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        for (i in 0 until 2) key(KeyEvent.KEYCODE_DPAD_DOWN)
        settle(1200)
        snap("10d-settings-playback"); reachable("10d-settings-playback")
        activity.search(); settle(2000)
        snap("11-search"); reachable("11-search")
        click("Show 123 keys"); settle(800)
        snap("11b-search-symbols")
        click("Show àéü keys"); settle(800)
        snap("11c-search-accents")
        // Typing re-renders the page; the keyboard stays on the page being typed from.
        click("Type é"); settle(1500)
        if (activity.getWindow().getDecorView().findViewWithTag<View>("keyboard:é") == null) throw AssertionError("Keyboard left the accents page")
        click("Clear search"); click("Show abc keys"); settle(800)
        activity.searchInput("night"); settle(7000)
        snap("12-search-results"); reachable("12-search-results")
        for (i in 0 until 7) key(KeyEvent.KEYCODE_DPAD_RIGHT)
        for (i in 0 until 3) key(KeyEvent.KEYCODE_DPAD_DOWN)
        settle(1500)
        snap("12b-search-results-scrolled")
        activity.openImports(); settle(2500)
        snap("14-imports"); reachable("14-imports")
        controller.pause().stop().destroy()
        if (!unreachable.isEmpty()) throw AssertionError("Controls the remote can't reach:\n" + java.lang.String.join("\n", unreachable))
    }

    private val unreachable: MutableList<String> = ArrayList()
    /**
     * Every control on screen must be reachable with the D-pad alone: a breadth-first walk of focus search in the four
     * directions from the current focus must arrive at each visible clickable view, and each one must be focusable.
     */
    private fun reachable(page: String) {
        val decor = activity.getWindow().getDecorView()
        val start: View? = decor.findFocus()
        if (start == null) { unreachable.add(page + ": nothing has focus"); return }
        val seen: MutableSet<View> = java.util.HashSet()
        val queue = java.util.ArrayDeque<View>()
        seen.add(start); queue.add(start)
        val directions = intArrayOf(View.FOCUS_LEFT, View.FOCUS_RIGHT, View.FOCUS_UP, View.FOCUS_DOWN)
        while (!queue.isEmpty() && seen.size < 800) {
            val view = queue.poll()
            for (direction in directions) { val next: View? = view.focusSearch(direction); if (next != null && seen.add(next)) queue.add(next) }
        }
        val before = unreachable.size; val controls = check(page, decor, seen)
        System.out.println("remote " + page + ": " + controls + " controls, " + seen.size + " reached, " + (unreachable.size - before) + " problems")
    }
    private fun check(page: String, view: View, seen: Set<View>): Int {
        if (!view.isShown()) return 0
        var count = 0
        val visible = android.graphics.Rect()
        // The Home spotlight is a touch shortcut to the card that already has focus; everything else must take focus.
        if (view.isClickable() && !"spotlight".equals(view.getTag()) && view.getGlobalVisibleRect(visible) && !visible.isEmpty()) {
            count++
            val name = "" + view.getTag() + " / " + view.getContentDescription()
            if (!view.isFocusable()) unreachable.add(page + ": touch only: " + name)
            else if (!seen.contains(view)) unreachable.add(page + ": unreachable: " + name)
        }
        if (view is android.view.ViewGroup) {
            val group: android.view.ViewGroup = view
            for (i in 0 until group.getChildCount()) count += check(page, group.getChildAt(i), seen)
        }
        return count
    }

    /**
     * The declarative player overlay over a real film still: playing with controls up, then the error alert and options menu.
     */
    @Test @Throws(Exception::class) fun playerOverlay() {
        val host: android.app.Activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().visible().get()
        host.setTheme(R.style.TvTheme)
        val root = android.widget.FrameLayout(host)
        root.setBackgroundColor(0xFF000000.toInt())
        val still = android.widget.ImageView(host)
        still.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
        tv.stream.app.addon.AddonClient().use { client ->
            val image: ByteArray = client.fetch("https://images.metahub.space/background/medium/tt0063350/img", 8 shl 20, Cancellation(),
                    tv.stream.app.addon.AddonClient.deadline())
            still.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size))
        }
        root.addView(still, android.widget.FrameLayout.LayoutParams(-1, -1))
        android.view.LayoutInflater.from(host).inflate(R.layout.playback_layers, root, true)
        host.setContentView(root)
        root.findViewById<View>(R.id.controls).setVisibility(View.VISIBLE)
        val controls = tv.stream.app.ui.ComposePlayerControls.install(root.findViewById(R.id.controls),
                kotlinx.coroutines.flow.MutableStateFlow(PlayerControlsState("Night of the Living Dead")), {})
        controls.render(tv.stream.playback.ControlsOverlay.State(progress = 428, buffered = 520, elapsed = "41:02", remaining = "−55:21", playing = true))
        val layers = tv.stream.app.ui.ComposePlayerLayers(host, false, {}); layers.install()
        settle(1500)
        snap("15-player", host.getWindow())
        root.findViewById<View>(R.id.controls).setVisibility(View.GONE)
        root.findViewById<View>(R.id.error).setVisibility(View.VISIBLE)
        layers.render(tv.stream.playback.PlayerScreen.LayerState(errorTitle = "This source didn't open",
            errorMessage = "The provider didn't answer in time. Try again, or choose another source.", retry = true, sources = true))
        layers.request(R.id.error_retry)
        settle(800)
        snap("15b-player-error", host.getWindow())
        // The options menu, built with the same row layout the player's menu inflates; opening it hides the controls.
        root.findViewById<View>(R.id.error).setVisibility(View.GONE)
        android.view.LayoutInflater.from(host).inflate(tv.stream.playback.R.layout.playback_menu, root, true)
        root.findViewById<View>(tv.stream.playback.R.id.menu).setVisibility(View.VISIBLE)
        root.findViewById<android.widget.TextView>(tv.stream.playback.R.id.menu_title).setText(tv.stream.playback.R.string.menu_options)
        val rows: android.view.ViewGroup = root.findViewById(tv.stream.playback.R.id.menu_rows)
        val items = arrayOf(arrayOf("Audio", "English"), arrayOf("Subtitles", "English"), arrayOf("Quality", "Auto (1080p)"), arrayOf("Speed", "Normal"), arrayOf("Subtitle style", ""))
        for (i in 0 until items.size) {
            val row = android.view.LayoutInflater.from(host).inflate(tv.stream.playback.R.layout.row_menu, rows, false)
            row.findViewById<android.widget.TextView>(tv.stream.playback.R.id.row_label).setText(items[i][0])
            row.findViewById<android.widget.TextView>(tv.stream.playback.R.id.row_value).setText(items[i][1])
            row.findViewById<android.widget.ImageView>(tv.stream.playback.R.id.row_icon).setImageResource(tv.stream.playback.R.drawable.ic_chevron_right)
            row.setSelected(i == 1)
            rows.addView(row)
        }
        settle(800)
        snap("15c-player-menu", host.getWindow())
    }

    /** Each screen's loading skeleton, exactly as MainActivity shows it while the page builds, to compare with the page. */
    @Suppress("UNCHECKED_CAST")
    @Test @Throws(Exception::class) fun skeletons() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle(1500)
        val field = MainActivity::class.java.getDeclaredField("stack"); field.setAccessible(true)
        val loading = MainActivity::class.java.getDeclaredMethod("loading"); loading.setAccessible(true)
        val stack = field.get(activity) as MutableList<Route>
        for (screen in arrayOf("Home", "Add-ons", "Add-on review", "Catalogue")) {
            stack.add(Route(screen))
            activity.runOnUiThread { try { loading.invoke(activity) } catch (e: Exception) { throw AssertionError(e) } }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            snap("sk-" + screen.lowercase(java.util.Locale.ROOT).replace(' ', '-'))
            stack.removeAt(stack.size - 1)
        }
    }

    /** The launcher icon as the system draws its vector layers (masked), and the banner, beside branding/make_brand.py's previews. */
    @Test @Throws(Exception::class) fun brand() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        for (spec in arrayOf(intArrayOf(R.mipmap.ic_launcher, 432, 432), intArrayOf(R.drawable.ic_launcher_monochrome, 432, 432), intArrayOf(R.drawable.tv_banner, 640, 360))) {
            val drawable: android.graphics.drawable.Drawable? = context.getDrawable(spec[0])
            val bitmap = Bitmap.createBitmap(spec[1], spec[2], Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(0xFF1C1C1E.toInt())
            drawable!!.setBounds(0, 0, spec[1], spec[2])
            drawable.draw(canvas)
            val name = "00-" + context.getResources().getResourceEntryName(spec[0])
            FileOutputStream(File(out, name + ".png")).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        }
    }

    /** D-pad: arrows move focus the way the framework's focus search does; other keys go to the focused view. */
    private fun key(code: Int) {
        val focused: View? = activity.getWindow().getDecorView().findFocus()
        val direction = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) View.FOCUS_RIGHT else if (code == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else if (code == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else 0
        if (direction != 0 && focused != null) {
            val next: View? = focused.focusSearch(direction)
            if (next != null) next.requestFocus(direction)
        } else {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }

    private fun click(label: String) {
        val found = ArrayList<View>()
        activity.getWindow().getDecorView().findViewsWithText(found, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION or View.FIND_VIEWS_WITH_TEXT)
        for (view in found) {
            var v: View? = view
            while (v != null) {
                if (v.isClickable()) { v.performClick(); return }
                v = if (v.getParent() is View) v.getParent() as View else null
            }
        }
        throw AssertionError("No control labelled " + label)
    }

    /**
     * Dialogs have their own window; this captures the alert surface without the dimmed page behind it. It draws in
     * software: Robolectric's hardware copy of a dialog window ignores a ScrollView's clip.
     */
    @Throws(Exception::class)
    private fun snapDialog(name: String) {
        val decor = ShadowDialog.getLatestDialog().getWindow()!!.getDecorView()
        val bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(bitmap))
        save(name, bitmap, decor)
    }
    @Throws(Exception::class)
    private fun snap(name: String) { snap(name, activity.getWindow()) }
    @Throws(Exception::class)
    private fun snap(name: String, window: Window) {
        val decor = window.getDecorView()
        val bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888)
        val done = AtomicBoolean()
        PixelCopy.request(window, bitmap, { result -> done.set(true) }, Handler(Looper.getMainLooper()))
        var i = 0
        while (i < 200 && !done.get()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)); i++ }
        save(name, bitmap, decor)
    }
    @Throws(Exception::class)
    private fun save(name: String, bitmap: Bitmap, decor: View) {
        FileOutputStream(File(out, name + ".png")).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        System.out.println("snapshot " + name + " " + decor.getWidth() + "x" + decor.getHeight())
    }

    companion object {
        @BeforeClass @JvmStatic fun enabled() { assumeTrue("UI snapshots run with -PuiSnapshots=true", java.lang.Boolean.getBoolean("dtv.snapshots")) }

        /** Settles until `done` holds or `ms` pass. */
        @Throws(InterruptedException::class)
        private fun waitFor(done: java.util.function.BooleanSupplier, ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (!done.getAsBoolean() && System.currentTimeMillis() < end) settle(100)
        }

        /** Runs the main looper roughly in real time so background work, artwork and animations finish. */
        @Throws(InterruptedException::class)
        private fun settle(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
                Thread.sleep(25)
            }
        }
    }
}
