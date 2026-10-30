package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import tv.stream.app.addon.Title

/** Focus and pointer navigation through the production series screen, with an isolated local title. */
class SeriesBrowseTest : AppHarness() {
    @Test @Throws(Exception::class) fun seasonsFollowFocusAndKeepShowTitleAboveEpisodes() {
        LogoServer().use { logo ->
        val episodes = JSONArray()
        for (season in 1..2) for (number in 1..8) episodes.put(JSONObject()
                .put("id", "sample:s" + season + "e" + number).put("season", season).put("episode", number)
                .put("title", (if (season == 1) "First season " else "Second season ") + number)
                .put("released", if (number == 1) "2025-09-12T00:00:00.000Z" else if (number == 2) "" else "unknown")
                .put("overview", "An episode of the selected season. The story continues as old friends return and a new mystery unfolds. Nothing goes as planned and the journey brings an unexpected discovery."))
        val series = Title.parse("local", JSONObject().put("id", "test:seasons").put("type", "series")
                .put("name", "Adaptive Series").put("description", "A show overview that gives way to the episodes when browsing seasons.")
                .put("videos", episodes).put("logo", logo.url))!!
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        browse!!.onActivity { activity -> activity.openTitle(series, "") }
        AppHarness.waitUntil("overview starts on Play", 10000) { focused("play") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        AppHarness.waitUntil("episode browsing replaces overview", 5000) { focused("season:1") && !visible("Play") }
        AppHarness.waitUntil("compact show title", 5000) { shown("series-header") }
        AppHarness.waitUntil("title logo loads", 5000) {
            val loaded = AtomicBoolean()
            browse!!.onActivity { activity ->
                val image = activity.findViewById<View>(R.id.page_rows).findViewWithTag<android.widget.ImageView>("series-logo")
                loaded.set(image != null && image.isShown && image.drawable != null)
            }
            loaded.get()
        }
        SystemClock.sleep(400)
        assertFalse("No redundant Episodes heading", visible("Episodes"))
        val title = bounds("series-header")
        assertTrue("Show title remains near the top", title.top < dp(140))
        assertTrue("Episodes are brought into the main viewport", bounds("episode:sample:s1e1").bottom < context.resources.displayMetrics.heightPixels)
        val stripButton = arrayOfNulls<View>(1)
        val seasonMotion = AtomicBoolean()
        val motionObserver = arrayOfNulls<android.view.ViewTreeObserver.OnDrawListener>(1)
        browse!!.onActivity { activity -> stripButton[0] = activity.findViewById<View>(R.id.page_rows).findViewWithTag("season:2") }
        browse!!.onActivity { activity ->
            val rows = activity.findViewById<View>(R.id.page_rows)
            motionObserver[0] = android.view.ViewTreeObserver.OnDrawListener {
                val shelf = rows.findViewWithTag<android.view.ViewGroup>("season-shelf")
                if (shelf != null && shelf.childCount > 0) {
                    val incoming = shelf.getChildAt(0)
                    if (incoming.alpha > .01f && incoming.alpha < .99f && incoming.translationX > 0f) seasonMotion.set(true)
                }
            }
            rows.viewTreeObserver.addOnDrawListener(motionObserver[0])
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        AppHarness.waitUntil("focus alone changes season", 5000) { focused("season:2") && shown("episode:sample:s2e1") }
        AppHarness.waitUntil("episode shelf slides and crossfades", 5000) { seasonMotion.get() }
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).viewTreeObserver.removeOnDrawListener(motionObserver[0]) }
        assertFalse("Previous season is replaced", shown("episode:sample:s1e1"))
        browse!!.onActivity { activity ->
            val selected = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("season:2")
            assertSame("Season focus survives without rebuilding the strip", stripButton[0], selected)
            assertTrue(selected.isActivated)
        }
        assertEquals("Show title stays in position", title, bounds("series-header"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        AppHarness.waitUntil("Down enters selected season's episodes", 5000) { focused("episode:sample:s2e1") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        AppHarness.waitUntil("episode actions", 5000) { visible("Mark watched") }
        tap("Mark watched", false)
        AppHarness.waitUntil("season two episode stays selected after marking", 10000) { focused("episode:sample:s2e1") && shown("season:2") }
        assertFalse("Marking does not return to season one", shown("episode:sample:s1e1"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        AppHarness.waitUntil("Up preserves selected season", 5000) { focused("season:2") }
        hover(bounds("season:1"))
        AppHarness.waitUntil("mouse hover changes season without clicking", 5000) { focused("season:1") && shown("episode:sample:s1e1") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        AppHarness.waitUntil("Down enters episodes", 5000) { focused("episode:sample:s1e1") }
        assertEquals("Show title stays visible while browsing episodes", title, bounds("series-header"))
        SystemClock.sleep(350)
        browse!!.onActivity { activity ->
            val episode = activity.findViewById<View>(R.id.page_rows).findViewWithTag<android.view.ViewGroup>("episode:sample:s1e1")
            val number = episode.findViewWithTag<android.widget.TextView>("episode-number")
            val name = episode.findViewWithTag<android.widget.TextView>("tile-title")
            val description = episode.findViewWithTag<android.widget.TextView>("episode-description")
            val date = episode.findViewWithTag<android.widget.TextView>("episode-release-date")
            assertEquals("Episode 1", number.text.toString()); assertEquals("First season 1", name.text.toString())
            assertTrue(number.top < name.top); assertTrue(name.top < description.top)
            assertEquals(3, description.maxLines.toLong()); assertTrue(description.lineCount > 1)
            assertNotNull("Provider release date is shown", date); assertTrue(date.text.toString().contains("2025"))
            assertTrue("Release date follows the synopsis", date.top > description.top)
            val complete = Rect(); assertTrue(episode.getGlobalVisibleRect(complete))
            assertTrue("All episode information fits on screen", complete.height() >= episode.height - 1)
            val undated = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("episode:sample:s1e2")
            assertNotNull(undated); assertNull("Missing dates add no empty caption", undated.findViewWithTag<View>("episode-release-date"))
        }
        screenshot("series-episode-layout.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        AppHarness.waitUntil("episode actions", 5000) { visible("Play") && visible("Choose source") }
        assertTrue("Completion is an episode menu action", visible("Mark watched"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("episode card receives the window after the menu closes", 5000) {
            val ready = AtomicBoolean()
            browse!!.onActivity { activity -> ready.set(activity.hasWindowFocus()) }
            ready.get() && focused("episode:sample:s1e1")
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        tap("Mark watched", true)
        AppHarness.waitUntil("episode marked", 5000) {
            val marked = AtomicBoolean()
            browse!!.onActivity { activity ->
                val episode = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("episode:sample:s1e1")
                marked.set(episode != null && episode.contentDescription.toString().endsWith("Watched"))
            }
            marked.get()
        }
        AppHarness.waitUntil("episode card receives input after marking", 5000) {
            val ready = AtomicBoolean(); browse!!.onActivity { activity -> ready.set(activity.hasWindowFocus()) }
            ready.get() && focused("episode:sample:s1e1")
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        AppHarness.waitUntil("Up returns to selected season", 5000) { focused("season:1") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        AppHarness.waitUntil("Up restores overview", 5000) { focused("play") && visible("Play") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        AppHarness.waitUntil("episode mode again", 5000) { focused("season:1") && !visible("Play") }
        tap("Back", true); AppHarness.waitUntil("Back restores overview", 5000) { focused("play") }
        tap("Back", false); AppHarness.waitUntil("Home restored", 5000) { visible("Make yourself at home") }
        assertFalse("Default Home has no Back", visible("Back"))
        }
    }

    @Test @Throws(Exception::class) fun episodeMenuUsesItsSavedPlaybackAndWatchedState() {
        val episodes = JSONArray()
        for (number in 1..3) episodes.put(JSONObject()
                .put("id", "menu:episode:$number").put("season", 1).put("episode", number).put("title", "Episode menu $number"))
        val series = Title.parse("local", JSONObject().put("id", "test:episode-menu").put("type", "series")
                .put("name", "Episode actions").put("videos", episodes))!!
        services.store.progress(series, "menu:episode:2", 15000, 60000, false)
        services.store.progress(series, "menu:episode:3", 60000, 60000, true)
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        browse!!.onActivity { activity -> activity.openTitle(series, "") }
        AppHarness.waitUntil("overview", 5000) { focused("play") }
        assertFalse("The series overview has no film-style completion toggle", shown("watched"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        AppHarness.waitUntil("seasons", 5000) { focused("season:1") && !visible("Resume") }
        tap("Episode menu 2", true); AppHarness.waitUntil("episode playback menu", 5000) { visible("Choose source") }
        assertTrue(visible("Resume")); assertFalse(visible("Play"))
        assertTrue("Completion lives in episode options", visible("Mark watched"))
        assertFalse(visible("Mark unwatched"))
        tap("Mark watched", true)
        AppHarness.waitUntil("only this episode marked", 5000) { services.store.progress(series.key(), "menu:episode:2")!!.watched }
        assertNull("Episode one was not changed", services.store.progress(series.key(), "menu:episode:1"))
        assertTrue("Episode three stays watched", services.store.progress(series.key(), "menu:episode:3")!!.watched)
        assertFalse("No option button on the episode artwork", visible("Episode 2 options"))
        browse!!.onActivity { activity -> assertEquals("The episode has no child action buttons", 0,
                childActions(activity.findViewById<View>(R.id.page_rows).findViewWithTag("episode:menu:episode:2")).toLong()) }
        tap("Episode menu 2", false)
        AppHarness.waitUntil("single state-specific action", 5000) { visible("Mark unwatched") }
        assertFalse(visible("Mark watched"))
        SystemClock.sleep(350) // Capture the final panel, after its expansion.
        screenshot("validation-episode-options.png")
        tap("Mark unwatched", false)
        AppHarness.waitUntil("episode unmarked", 5000) { !services.store.progress(series.key(), "menu:episode:2")!!.watched }
        tap("Episode menu 2", false); AppHarness.waitUntil("resume preserved", 5000) { visible("Resume") && visible("Choose source") }
        assertFalse(visible("Play")); assertTrue(visible("Mark watched"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("episode menu closes", 5000) { !visible("Choose source") }
        val film = Title.parse("local", JSONObject().put("id", "test:film-toggle").put("type", "movie").put("name", "Film completion"))!!
        browse!!.onActivity { activity -> activity.openTitle(film, "") }
        AppHarness.waitUntil("film keeps its own watched control", 5000) { shown("watched") && visible("Mark watched") }
        tap("Mark watched", false)
        AppHarness.waitUntil("film marked watched", 5000) { services.store.progress(film.key(), film.id) != null && services.store.progress(film.key(), film.id)!!.watched }
    }

    @Test @Throws(Exception::class) fun seriesArtworkLoadsForTheVisibleSeasonRatherThanTheWholeShow() {
        LogoServer().use { images ->
            val episodes = JSONArray()
            for (season in 1..3) for (number in 1..20) episodes.put(JSONObject()
                    .put("id", "art:s" + season + "e" + number).put("season", season).put("episode", number)
                    .put("title", "Episode $number").put("thumbnail", images.url.replace("logo.png", "s" + season + "e" + number + ".png")))
            val series = Title.parse("local", JSONObject().put("id", "test:artwork").put("type", "series")
                    .put("name", "Artwork demand review").put("videos", episodes))!!
            launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
            browse!!.onActivity { activity -> activity.openTitle(series, "") }
            AppHarness.waitUntil("overview", 5000) { focused("play") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            AppHarness.waitUntil("first season artwork requested", 5000) { images.requests.contains("/s1e1.png") }
            SystemClock.sleep(500)
            val first = images.requests.size
            assertTrue("Only the visible portion of a 20-episode season is fetched", first > 0 && first < 20)
            assertTrue("Hidden seasons have no artwork requests", images.requests.stream().allMatch { path -> path.startsWith("/s1") })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            AppHarness.waitUntil("new season artwork requested on focus", 5000) { images.requests.contains("/s2e1.png") }
            SystemClock.sleep(300)
            assertFalse("Unvisited season stays unloaded", images.requests.stream().anyMatch { path -> path.startsWith("/s3") })
            java.nio.file.Files.write(File(context.filesDir, "series-artwork-review.txt").toPath(),
                    ("60 episodes across three seasons. Requests after first season: " + first
                            + ". After focusing second season: " + images.requests.size + ". No third-season requests.\n")
                            .toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        }
    }

    @Test @Throws(Exception::class) fun defaultTabsHideBackButSubpagesKeepIt() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        assertFalse(visible("Back"))
        tap("Library", false); AppHarness.waitUntil("Library", 5000) { visible("Your library is empty") }
        assertFalse(visible("Back"))
        tap("Settings", true); AppHarness.waitUntil("Settings", 5000) { visible("Add-ons") }
        assertFalse(visible("Back"))
        browse!!.onActivity { activity -> activity.openImports() }
        AppHarness.waitUntil("Imports has Back", 5000) { visible("Add magnet or torrent URL") && visible("Back") }
        tap("Back", false); AppHarness.waitUntil("Settings restored", 5000) { visible("Add-ons") && !visible("Back") }
    }
    private class LogoServer @Throws(Exception::class) constructor() : AutoCloseable {
        @JvmField val server: java.net.ServerSocket
        @JvmField val thread: Thread
        @JvmField val url: String
        @JvmField val requests: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        init {
            val bitmap = Bitmap.createBitmap(500, 100, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.WHITE; paint.textSize = 58f
            paint.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC)
            canvas.drawText("ADAPTIVE SERIES", 8f, 76f, paint)
            val bytes = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle(); val png = bytes.toByteArray()
            server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
            url = "http://127.0.0.1:" + server.localPort + "/logo.png"
            thread = Thread(Runnable {
                while (!server.isClosed) try {
                    server.accept().use { connection ->
                        connection.soTimeout = 2000
                        val input = java.io.BufferedReader(java.io.InputStreamReader(connection.getInputStream()))
                        val request = input.readLine()
                        if (request != null && request.startsWith("GET ")) requests.add(request.split(" ")[1])
                        while (true) { val line = input.readLine(); if (line == null || line.isEmpty()) break }
                        connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " + png.size + "\r\nConnection: close\r\n\r\n").toByteArray(java.nio.charset.StandardCharsets.US_ASCII))
                        connection.getOutputStream().write(png)
                    }
                } catch (ignored: java.io.IOException) { if (server.isClosed) return@Runnable }
            }, "series-logo-loopback"); thread.start()
        }
        @Throws(Exception::class)
        override fun close() { server.close(); thread.join(3000) }
    }
    private fun focused(key: String): Boolean {
        val result = AtomicBoolean()
        browse!!.onActivity { activity -> result.set(activity.hasWindowFocus() && activity.currentFocus != null && key == activity.currentFocus!!.tag) }
        return result.get()
    }
    private fun shown(key: String): Boolean {
        val result = AtomicBoolean()
        browse!!.onActivity { activity -> val view = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(key); result.set(view != null && view.isShown) }
        return result.get()
    }
    private fun bounds(key: String): Rect {
        val rect = Rect()
        browse!!.onActivity { activity ->
            val view = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(key)
            assertNotNull(key, view); assertTrue(key, view.getGlobalVisibleRect(rect))
        }
        return rect
    }
    private fun hover(bounds: Rect) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, MotionEvent.ACTION_HOVER_MOVE, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        event.source = InputDevice.SOURCE_MOUSE
        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        instrumentation.waitForIdleSync()
    }
    private fun dp(value: Int): Int { return Math.round(value * context.resources.displayMetrics.density) }
    @Throws(Exception::class)
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(bitmap)
        FileOutputStream(File(context.filesDir, name)).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
    }
    companion object {
        private fun childActions(view: View?): Int {
            var result = 0
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.isClickable || child.isFocusable) result++
                result += childActions(child)
            }
            return result
        }
    }
}
