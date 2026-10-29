package tv.stream.app

import org.junit.Assert.*
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title
import tv.stream.app.ui.PageAdapter

/** Real navigation and HTTP failure paths, served inside the emulator with isolated owner data. */
class BrowseRecoveryTest : AppHarness() {
    @Test @Throws(Exception::class) fun settingsReturnsToTheChosenPaneAndRow() {
        launch(); waitUntil("empty Home", 10000) { visible("Make yourself at home") }
        tap("Settings", true); tap("Languages", true)
        waitUntil("keyboard language row", 5000) { visible("Keyboard languages") }
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard-languages").requestFocus() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("keyboard language page", 5000) { visible("English") }
        tap("Back", true)
        waitUntil("settings restores the actual row", 5000) { focus() == "keyboard-languages" }
        assertTrue("Languages pane is still shown", visible("Subtitle language"))
    }
    @Test @Throws(Exception::class) fun transientCategoryFailuresKeepOnlySkeletonsUntilTheyRecover() {
        start().use { server ->
            gallery(); tap("Flaky", true)
            waitUntil("third bounded attempt", 10000) { server.requests("Flaky", 0) == 3 }
            assertFalse(text("Attempt 3 of 3"))
            assertFalse(text("Retrying catalogue")); assertFalse(text("Loading catalogue"))
            assertFalse(visible("Reload"))
            val skeleton = AtomicReference(false)
            browse!!.onActivity { activity -> skeleton.set(((activity.findViewById<View>(R.id.page_rows) as RecyclerView).adapter as PageAdapter).position("skeleton:Catalogue results") >= 0) }
            assertTrue("The skeleton remains during retry", skeleton.get())
            server.flakyThird.countDown()
            waitUntil("retry recovers posters", 10000) { visible("Film 00") && !text("Loading catalogue") && !text("Retrying catalogue") }
            assertEquals(3, server.requests("Flaky", 0).toLong())
            assertFalse(text("Unable to load catalogue"))
        }
    }

    @Test @Throws(Exception::class) fun exhaustedRefreshKeepsPostersAndReloadStartsANewAttempt() {
        start().use { server ->
            gallery(); server.unavailable = true
            tap("Refresh", true)
            waitUntil("bounded failure with inline reload", 12000) { text("Unable to load catalogue") && visible("Reload") }
            assertTrue(text("Attempt 3 of 3")); assertTrue(text("Showing saved entries"))
            assertTrue("Previously fetched posters remain usable", visible("Film 00"))
            assertFalse("A failed request is not an empty catalogue", text("No titles on this page"))
            assertEquals(4, server.requests("", 0).toLong())
            SystemClock.sleep(1400); assertEquals("Retries stop at the limit", 4, server.requests("", 0).toLong())
            server.unavailable = false; tap("Reload", false)
            waitUntil("manual reload recovers", 10000) { server.requests("", 0) == 5 && !text("Unable to load catalogue") }
            assertTrue(visible("Film 00"))
        }
    }

    @Test @Throws(Exception::class) fun changingCategoryCancelsBackoffAndPermanentFailuresAreNotRetried() {
        start().use { server ->
            gallery(); tap("Offline", true)
            waitUntil("first failure before backoff", 5000) { server.requests("Offline", 0) >= 1 }
            assertFalse(text("Retrying catalogue"))
            tap("Empty", true)
            waitUntil("empty success", 5000) { text("No titles on this page") }
            val cancelled = server.requests("Offline", 0)
            SystemClock.sleep(3300)
            assertEquals("No retry of the abandoned filter", cancelled.toLong(), server.requests("Offline", 0).toLong())
            assertEquals(1, server.requests("Empty", 0).toLong()); assertFalse(text("Unable to load catalogue"))
            tap("Bad data", false)
            waitUntil("malformed response explained", 5000) { text("invalid catalogue") }
            assertEquals(1, server.requests("Bad data", 0).toLong())
            tap("Denied", true)
            waitUntil("HTTP refusal explained", 5000) { text("provider could not serve this page") }
            assertEquals(1, server.requests("Denied", 0).toLong())
        }
    }

    @Test @Throws(Exception::class) fun galleryPaginationBackAndFavouritesKeepFocusWithoutProviderReloads() {
        start().use { server ->
            gallery(); openAndReturn("catalogue", 17, true)
            assertEquals("Back does not request the category again", 1, server.requests("", 0).toLong())
            tap("Next page", false)
            waitUntil("second category page", 5000) { visible("Film 24") }
            tap("Previous page", true)
            waitUntil("first category page returns", 5000) { visible("Film 00") }
            assertEquals("Recently visited page is reused", 1, server.requests("", 0).toLong())
            assertEquals(1, server.requests("", 24).toLong())
            openAndReturn("catalogue", 19, false)
            assertEquals(1, server.requests("", 0).toLong())
        }
    }

    @Test @Throws(Exception::class) fun searchLibraryAndHomeRestoreTheirOwnFocusAndNestedViewports() {
        start().use { server ->
            for (i in 0 until 48) services.store.favourite(title(server, i), true)
            tap("Library", true)
            waitUntil("library grid", 5000) { visible("Favourites") }
            openAndReturn("library", 31, false)
            browse!!.onActivity { activity -> activity.root("Settings") }
            browse!!.onActivity { activity -> activity.root("Library") }
            waitUntil("library card after tab switch", 5000) { focus() == tileKey("library", server, 31) }

            browse!!.onActivity { activity -> activity.search() }
            browse!!.onActivity { activity -> activity.searchInput("Film") }
            waitUntil("search results", 5000) { visible("Film 00") }
            ageSnapshots(); openAndReturn("search", 36, true)
            assertEquals("Back does not repeat search", 1, server.searchRequests.get().toLong())
            browse!!.onActivity { activity -> activity.root("Home") }
            waitUntil("Home shelf", 5000) { visible("Film 00") }
            val homeKey = services.store.addons()[0].id + ":movie:browse"
            ageSnapshots(); openAndReturn(homeKey, 14, true)
            assertEquals("Local data changes reuse Home's provider results", 1, server.homeRequests.get().toLong())
        }
    }

    @Throws(Exception::class)
    private fun start(): CatalogServer {
        val server = CatalogServer()
        services.addons.newRequest().use { request ->
            services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
        }
        launch(); waitUntil("fixture Home", 10000) { visible("Film 00") }; return server
    }
    private fun gallery() { tap("Browse", true); tap("Test films", true); waitUntil("category grid", 5000) { visible("Film 00") } }
    @Throws(Exception::class)
    private fun title(server: CatalogServer, index: Int): Title { return Title.parse(services.store.addons()[0].id, server.title(index))!! }
    private fun tileKey(section: String, server: CatalogServer, index: Int): String {
        try { return section + ":" + title(server, index).key() } catch (e: Exception) { throw AssertionError(e) }
    }
    @Throws(Exception::class)
    private fun openAndReturn(section: String, index: Int, favourite: Boolean) {
        val key = section + ":" + JSONArray().put(services.store.addons()[0].id).put("movie").put("browse:$index")
        val before = AtomicReference<Rect>()
        browse!!.onActivity { activity ->
            val outer = activity.findViewById<RecyclerView>(R.id.page_rows)
            val adapter = outer.adapter as PageAdapter
            outer.scrollToPosition(adapter.position(key))
        }
        if (section == "search" || section.contains(":movie:")) {
            waitUntil("nested results row attached", 5000) {
                val ready = AtomicReference(false)
                browse!!.onActivity { activity ->
                    val outer = activity.findViewById<RecyclerView>(R.id.page_rows)
                    val holder = outer.findViewHolderForAdapterPosition((outer.adapter as PageAdapter).position(key))
                    if (holder != null) { val child = nested(holder.itemView); if (child != null) { child.scrollToPosition(index); ready.set(true) } }
                }; ready.get()
            }
        }
        waitUntil("selected film laid out", 5000) {
            val found = AtomicReference(false)
            browse!!.onActivity { activity -> found.set(activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(key) != null) }; found.get()
        }
        browse!!.onActivity { activity -> val selected = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(key); selected.requestFocus() }
        SystemClock.sleep(350)
        browse!!.onActivity { activity -> val bounds = Rect(); assertTrue(activity.currentFocus!!.getGlobalVisibleRect(bounds)); before.set(bounds) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("film details", 10000) { visible("Play") }
        if (favourite) {
            val count = services.store.library(true).size
            tap(if (visible("Add favourite")) "Add favourite" else "Remove favourite", true)
            waitUntil("favourite change saved", 5000) { services.store.library(true).size != count }
        }
        tap("Back", false)
        waitUntil("exact film regains focus", 5000) { key == focus() }
        SystemClock.sleep(350)
        browse!!.onActivity { activity ->
            val after = Rect(); assertTrue(activity.currentFocus!!.getGlobalVisibleRect(after))
            assertEquals("Same horizontal viewport", before.get().left.toFloat(), after.left.toFloat(), 3f)
            assertEquals("Same vertical viewport in $section", before.get().top.toFloat(), after.top.toFloat(), 3f)
        }
    }
    private fun nested(view: View): RecyclerView? {
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is RecyclerView) return child
            val found = nested(child); if (found != null) return found
        }
        return null
    }
    private fun focus(): String {
        val result = AtomicReference("")
        browse!!.onActivity { activity -> if (activity.currentFocus != null) result.set((activity.currentFocus!!.tag as Any?).toString()) }; return result.get()
    }
    /** Simulate returning after a long film without making a device test wait five real minutes. */
    private fun ageSnapshots() {
        browse!!.onActivity { activity ->
            try {
                val screens = MainActivity::class.java.getDeclaredField("browse"); screens.isAccessible = true
                val data = screens.get(activity)
                val home = BrowseScreens::class.java.getDeclaredField("homeResults"); home.isAccessible = true
                val search = BrowseScreens::class.java.getDeclaredField("searches"); search.isAccessible = true
                val saved: MutableList<Any?> = java.util.ArrayList<Any?>((search.get(data) as Map<*, *>).values)
                if (home.get(data) != null) saved.add(home.get(data))
                for (result in saved) { val time = result!!.javaClass.getDeclaredField("saved"); time.isAccessible = true; time.setLong(result, -300001) }
            } catch (e: Exception) { throw AssertionError(e) }
        }
    }
    private fun text(fragment: String): Boolean {
        val found = AtomicReference(false)
        browse!!.onActivity { activity -> found.set(contains(activity.findViewById(R.id.page_rows), fragment)) }; return found.get()
    }
    private fun contains(view: View, fragment: String): Boolean {
        if (view is TextView && view.isShown && view.text.toString().contains(fragment)) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) if (contains(view.getChildAt(i), fragment)) return true
        return false
    }

    class CatalogServer @Throws(Exception::class) constructor() : AutoCloseable {
        @JvmField val server: ServerSocket
        @JvmField val workers: java.util.concurrent.ExecutorService = Executors.newCachedThreadPool()
        @JvmField val url: String
        @JvmField val requests: MutableMap<String, AtomicInteger> = ConcurrentHashMap()
        @JvmField val homeRequests = AtomicInteger(); @JvmField val searchRequests = AtomicInteger()
        @JvmField val flakyThird = CountDownLatch(1)
        @Volatile @JvmField var unavailable = false
        @Volatile @JvmField var emptySearch = false
        @Volatile @JvmField var failureStatus = 503
        @Volatile @JvmField var catalogCount = 1
        @Volatile @JvmField var catalogueDelayMs: Long = 0
        @Volatile @JvmField var homeGate: CountDownLatch? = null
        @Volatile @JvmField var homeGateCatalog = ""
        init {
            server = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1")); url = "http://127.0.0.1:" + server.localPort
            workers.execute { while (!server.isClosed) try { val socket = server.accept(); workers.execute { answer(socket) } } catch (ignored: Exception) { break } }
        }
        fun requests(genre: String, skip: Int): Int { val count = requests["$genre:$skip"]; return if (count == null) 0 else count.get() }
        @Throws(Exception::class)
        fun title(index: Int): JSONObject { return JSONObject().put("id", "browse:$index").put("type", "movie").put("name", String.format(java.util.Locale.ROOT, "Film %02d", index)).put("description", "Generated navigation fixture.") }
        private fun answer(socket: Socket) {
            try {
                socket.use { connection -> BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)).use { input ->
                    val request = input.readLine(); if (request == null) return
                    val path = request.split(" ")[1]; var header: String?; while (true) { header = input.readLine(); if (!(header != null && !header.isEmpty())) break }
                    var status = 200; val result = JSONObject()
                    if (path == "/manifest.json") {
                        val genre = JSONObject().put("name", "genre").put("options", JSONArray().put("Flaky").put("Offline").put("Empty").put("Bad data").put("Denied"))
                        val catalogs = JSONArray()
                        for (i in 0 until catalogCount) catalogs.put(JSONObject().put("type", "movie").put("id", if (i == 0) "browse" else "browse-$i").put("name", "Test films")
                                .put("extra", JSONArray().put(genre).put(JSONObject().put("name", "skip")).put(JSONObject().put("name", "search"))))
                        result.put("id", "tv.stream.browse-fixture").put("name", "Browse fixture").put("version", "1.0.0")
                                .put("types", JSONArray().put("movie")).put("resources", JSONArray().put("catalog").put("meta").put("stream"))
                                .put("catalogs", catalogs)
                    } else if (path.startsWith("/catalog/")) {
                        val parts = path.substring(0, path.length - 5).split("/").toTypedArray()
                        val extra: MutableMap<String, String> = java.util.HashMap()
                        if (parts.size > 4) for (pair in parts[4].split("&")) {
                            val value = pair.split("=", limit = 2); extra[URLDecoder.decode(value[0], "UTF-8")] = if (value.size == 1) "" else URLDecoder.decode(value[1], "UTF-8")
                        }
                        val genre = extra.getOrDefault("genre", ""); val skip = Integer.parseInt(extra.getOrDefault("skip", "0"))
                        var number = 0
                        if (extra.containsKey("search")) searchRequests.incrementAndGet()
                        else if (!extra.containsKey("skip")) {
                            homeRequests.incrementAndGet()
                            if (homeGate != null && (homeGateCatalog.isEmpty() || homeGateCatalog == parts[3])) homeGate!!.await(15, TimeUnit.SECONDS)
                        }
                        else number = requests.computeIfAbsent("$genre:$skip") { key -> AtomicInteger() }.incrementAndGet()
                        if (catalogueDelayMs > 0) Thread.sleep(catalogueDelayMs)
                        if (unavailable || genre == "Offline" || genre == "Flaky" && number < 3) status = failureStatus
                        if (genre == "Denied") status = 401
                        if (genre == "Flaky" && number == 3) flakyThird.await(15, TimeUnit.SECONDS)
                        val titles = JSONArray()
                        val count = if (extra.containsKey("search")) 96 else 24
                        if (genre != "Empty" && !(emptySearch && extra.containsKey("search")) && status == 200) for (i in skip until skip + count) titles.put(title(i))
                        result.put(if (genre == "Bad data") "invalid" else "metas", titles)
                    } else if (path.startsWith("/meta/")) {
                        val id = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1, path.length - 5), "UTF-8")
                        result.put("meta", title(Integer.parseInt(id.substring(id.lastIndexOf(':') + 1))))
                    } else result.put("streams", JSONArray())
                    val bytes = result.toString().toByteArray(StandardCharsets.UTF_8)
                    connection.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\nContent-Type: application/json\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                    connection.getOutputStream().write(bytes); connection.getOutputStream().flush()
                } }
            } catch (ignored: Exception) { /* Cancellation closes the real client socket. */ }
        }
        @Throws(Exception::class)
        override fun close() { flakyThird.countDown(); server.close(); workers.shutdownNow() }
    }
}
