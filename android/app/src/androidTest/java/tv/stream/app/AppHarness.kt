package tv.stream.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import java.util.ArrayList
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import tv.stream.app.addon.*
import tv.stream.playback.PlaybackSnapshot
import org.junit.Assert.*

/** Real activities, real MotionEvents/keys and generated loopback media; an isolated database preserves owner data. */
abstract class AppHarness {
    @JvmField val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    @JvmField val context: Context = instrumentation.targetContext
    lateinit var app: TvApplication
    lateinit var services: TvApplication.Services
    lateinit var database: String
    lateinit var origin: String
    lateinit var oldConfiguration: MutableMap<String, Any?>
    @JvmField var browse: ActivityScenario<MainActivity>? = null
    @Before @Throws(Exception::class) open fun configure() {
        val info: android.accessibilityservice.AccessibilityServiceInfo = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = info
        app = context.applicationContext as TvApplication
        val options = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE)
        // An interrupted run leaves its overrides behind; never keep those as the configuration to restore.
        oldConfiguration = java.util.HashMap<String, Any?>(options.all)
        for (key in arrayOf("database", "localOnly", "cacheBytes", "peer", "addonDirectory")) oldConfiguration.remove(key)
        database = InstrumentationRegistry.getArguments().getString("database", "test-" + UUID.randomUUID() + ".db")
        assertTrue("Isolated test database", database.matches(Regex("test-[a-z0-9-]+\\.db")))
        origin = InstrumentationRegistry.getArguments().getString("fixtures", "http://127.0.0.1:58133")
        // The store's directory is the fixture service's, never Stremio's public one.
        options.edit().putString("database", database).putBoolean("localOnly", true).putLong("cacheBytes", 16L shl 20)
                .putString("peer", InstrumentationRegistry.getArguments().getString("seeder", "127.0.0.1:58134"))
                .putString("addonDirectory", "$origin/addon/good/directory/manifest.json").commit()
        app.resetServices(); services = app.services()
    }
    @After @Throws(Exception::class) open fun cleanup() {
        if (browse != null) browse!!.close()
        instrumentation.runOnMainSync {
            for (stage in arrayOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED))
                for (activity in ArrayList<Activity>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                    if (activity is MainActivity || activity is WatchActivity) activity.finish()
        }
        instrumentation.waitForIdleSync()
        waitUntil("native payload cleanup", 10_000) { val files = java.io.File(context.cacheDir, "payload").listFiles(); files == null || files.size == 0 }
        app.persistence.submit(Runnable {}).get()
        for (id in services.store.importIds()) services.imports.remove(id)
        app.resetServices(); context.deleteDatabase(database); context.deleteSharedPreferences(RuntimeOptions.playerPreferences(database))
        val editor: SharedPreferences.Editor = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().clear()
        for (e in oldConfiguration.entries) {
            val v = e.value; if (v is String) editor.putString(e.key, v) else if (v is Boolean) editor.putBoolean(e.key, v) else if (v is Long) editor.putLong(e.key, v) else if (v is Int) editor.putInt(e.key, v)
        }
        editor.commit()
    }
    open fun launch() { browse = ActivityScenario.launch(MainActivity::class.java) }
    @Throws(Exception::class)
    open fun readyLibrary() {
        readyLibrary("ui")
    }
    @Throws(Exception::class)
    open fun readyLibrary(configuration: String) {
        services.addons.newRequest().use { scope ->
            val url = "$origin/addon/good/$configuration/manifest.json"; services.addons.install(url, services.addons.preview(url, scope))
        }
    }
    open fun watch(): WatchActivity? {
        val result = AtomicReference<WatchActivity>()
        instrumentation.runOnMainSync { for (a in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if (a is WatchActivity) result.set(a) }
        return result.get()
    }
    open fun state(): PlaybackSnapshot? {
        val result = AtomicReference<PlaybackSnapshot>()
        instrumentation.runOnMainSync { for (a in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if (a is WatchActivity) result.set(a.playbackSnapshot()) }
        return result.get()
    }
    open fun diagnostics(): JSONObject {
        val result = AtomicReference<JSONObject>(JSONObject())
        instrumentation.runOnMainSync { for (a in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if (a is WatchActivity) result.set(a.diagnosticSnapshot()) }; return result.get()
    }
    open fun visible(label: String): Boolean { return findWindow(label) != null }
    /**
     * Compose sends semantics changes only when an accessibility service is enabled, which UiAutomation is not, so its
     * node cache can keep a composition's previous content. Read each snapshot afresh.
     */
    fun windows(): List<android.view.accessibility.AccessibilityWindowInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.windows
    }
    private fun findWindow(label: String): AccessibilityNodeInfo? {
        for (window in windows()) {
            if (!window.isFocused) continue
            val result = find(window.root, label); if (result != null) return result
        }
        return null
    }
    open fun find(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isVisibleToUser && (label.equals(if (root.text == null) "" else root.text.toString(), ignoreCase = true) || label.equals(if (root.contentDescription == null) "" else root.contentDescription.toString(), ignoreCase = true))) return root
        for (i in 0 until root.childCount) { val result = find(root.getChild(i), label); if (result != null) return result }
        return null
    }
    open fun enter(text: String) {
        val args = android.os.Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        var field = editable(instrumentation.uiAutomation.rootInActiveWindow)
        if (field == null) for (window in instrumentation.uiAutomation.windows) { field = editable(window.root); if (field != null) break }
        assertNotNull("Editable dialog", field); assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        instrumentation.waitForIdleSync()
    }
    private fun editable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null; if (root.isEditable) return root
        for (i in 0 until root.childCount) { val result = editable(root.getChild(i)); if (result != null) return result }
        return null
    }
    open fun row(key: String, mouse: Boolean) {
        val bounds = Rect()
        if (!keyed(bounds) { it == key }) browse!!.onActivity { activity -> val v = activity.findViewById<android.view.View>(R.id.page_rows).findViewWithTag<android.view.View>(key); assertNotNull("Row $key", v); assertTrue(v.getGlobalVisibleRect(bounds)) }
        pointer(bounds.centerX().toFloat(), bounds.centerY().toFloat(), mouse)
    }
    /** Focuses the page control keyed `key`, native or declarative; the platform view then holding focus, or null. */
    open fun focusKey(activity: MainActivity, key: String): android.view.View? =
            if (tv.stream.app.ui.PageFocus.request(activity.findViewById(R.id.page_rows), key)) activity.currentFocus else null
    /** A visible declarative control whose key (exposed as its accessibility resource name) matches. */
    open fun keyed(bounds: Rect, matches: (String) -> Boolean): Boolean {
        for (window in windows()) {
            if (!window.isFocused) continue
            val node = keyedNode(window.root, matches) ?: continue
            node.getBoundsInScreen(bounds); return !bounds.isEmpty
        }
        return false
    }
    private fun keyedNode(node: AccessibilityNodeInfo?, matches: (String) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val id = node.viewIdResourceName
        if (id != null && node.isFocusable && matches(id)) return node
        for (i in 0 until node.childCount) { val found = keyedNode(node.getChild(i), matches); if (found != null) return found }
        return null
    }
    open fun tap(label: String, mouse: Boolean) {
        revealNavigationFor(label, mouse)
        val end = SystemClock.elapsedRealtime() + 30_000
        while (!visible(label) && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(500)
            if (!visible(label)) {
                if (revealNavigationFor(label, mouse)) continue
                var moved = false
                for (window in instrumentation.uiAutomation.windows) if (window.isFocused) moved = moved or scroll(window.root)
                if (!moved || !visible(label)) swipeBrowse()
            }
        }
        if (!visible(label)) waitUntil("visible $label", 100) { visible(label) }
        waitUntil("target $label", 5000) { targetNode(label) != null }
        var node = targetNode(label)
        if (node != null && node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) {
            SystemClock.sleep(150); instrumentation.runOnMainSync {}
            waitUntil("settled $label", 5000) { targetNode(label) != null }; node = targetNode(label)
        }
        assertNotNull("Clickable $label", node); val bounds = Rect(); node!!.getBoundsInScreen(bounds)
        // IME/dialog transitions move buttons after accessibility first exposes them. Wait for stable geometry.
        var stable = SystemClock.elapsedRealtime(); val endGeometry = stable + 3000
        while (SystemClock.elapsedRealtime() < endGeometry && SystemClock.elapsedRealtime() - stable < 400) {
            SystemClock.sleep(75); val next = targetNode(label)
            if (next == null) { stable = SystemClock.elapsedRealtime(); continue }; val rectangle = Rect(); next.getBoundsInScreen(rectangle)
            if (bounds != rectangle) { bounds.set(rectangle); stable = SystemClock.elapsedRealtime() }
        }
        pointer(bounds.centerX().toFloat(), bounds.centerY().toFloat(), mouse)
    }
    /** Hidden destinations use the same edge entry a pointer/touch viewer uses, before selecting a tab. */
    private fun revealNavigationFor(label: String, mouse: Boolean): Boolean {
        if (!(label == "Search" || label == "Home" || label == "Browse" || label == "Library" || label == "Settings")) return false
        if (targetNode(label) != null || !visible("Open navigation")) return false
        tap("Open navigation", mouse); return true
    }
    private fun targetNode(label: String): AccessibilityNodeInfo? {
        // A shelf heading can have the same text as its card. Only match labels inside an actual click target,
        // carrying the ancestor from this snapshot instead of following potentially recycled parent pointers.
        for (window in windows()) {
            if (!window.isFocused) continue
            val target = clickableLabel(window.root, label, null)
            if (target != null) return target
        }
        return null
    }
    private fun clickableLabel(node: AccessibilityNodeInfo?, label: String, ancestor: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val target = if (node.isClickable) node else ancestor
        val matches = label.equals(if (node.text == null) "" else node.text.toString(), ignoreCase = true)
                || label.equals(if (node.contentDescription == null) "" else node.contentDescription.toString(), ignoreCase = true)
        if (matches && target != null && target.isEnabled) return target
        for (i in 0 until node.childCount) {
            val found = clickableLabel(node.getChild(i), label, target)
            if (found != null) return found
        }
        return null
    }
    private fun swipeBrowse() {
        if (browse == null) return; val bounds = Rect()
        browse!!.onActivity { activity -> if (activity.hasWindowFocus()) activity.findViewById<android.view.View>(R.id.page_rows).getGlobalVisibleRect(bounds) }
        if (bounds.isEmpty) return
        val x = (bounds.right - 30).toFloat(); val from = (bounds.bottom - 60).toFloat(); val to = (bounds.top + 60).toFloat()
        val time = SystemClock.uptimeMillis()
        for (i in 0..10) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), if (i == 0) MotionEvent.ACTION_DOWN else if (i == 10) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 10, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN; assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(25)
        }
    }
    private fun scroll(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
        return false
    }
    open fun pointer(x: Float, y: Float, mouse: Boolean) {
        val time = SystemClock.uptimeMillis()
        val p = MotionEvent.PointerProperties(); p.id = 0; p.toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        val c = MotionEvent.PointerCoords(); c.x = x; c.y = y; c.pressure = 1f; c.size = 1f
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, 1, arrayOf(p), arrayOf(c), 0, if (mouse && action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0, 1f, 1f, 0, 0, if (mouse) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true)); e.recycle()
        }
        // Playback and clocks may keep scheduling frames. Wait for injected input to reach the main
        // thread, then bound accessibility settling; each caller's state assertion decides success.
        instrumentation.runOnMainSync {}
        try { instrumentation.uiAutomation.waitForIdle(100, 2000) } catch (ignored: java.util.concurrent.TimeoutException) { /* state assertions still decide success */ }
    }
    open fun control(id: Int, mouse: Boolean) {
        val activity = watch(); assertNotNull(activity); val bounds = Rect()
        instrumentation.runOnMainSync { assertTrue(activity!!.findViewById<android.view.View>(id).getGlobalVisibleRect(bounds)) }; pointer(bounds.centerX().toFloat(), bounds.centerY().toFloat(), mouse)
    }
    /** Play is automatic; Sources still exercises explicit pointer selection when that page is open. */
    open fun chooseFirstSource() {
        val bounds = Rect()
        waitUntil("playable source row", 30000) {
            if (watch() != null) return@waitUntil true
            bounds.setEmpty()
            if (!keyed(bounds) { it.startsWith("source:") }) browse!!.onActivity { activity -> sourceBounds(activity.findViewById(R.id.page_rows), bounds) }
            !bounds.isEmpty
        }
        if (!bounds.isEmpty) pointer(bounds.centerX().toFloat(), bounds.centerY().toFloat(), true)
    }
    private fun sourceBounds(view: android.view.View, bounds: Rect): Boolean {
        if (view.isClickable && (view.tag as Any?).toString().startsWith("source:") && view.getGlobalVisibleRect(bounds)) return true
        if (view is android.view.ViewGroup) {
            val group: android.view.ViewGroup = view
            for (i in 0 until group.childCount) if (sourceBounds(group.getChildAt(i), bounds)) return true
        }
        return false
    }
    /** Exercise the player's internal automatic-recovery mode directly; normal Play uses this same automatic mode. */
    @Throws(Exception::class)
    open fun startAutomaticFixturePlayback() {
        val title = Title.parse(services.store.addons()[0].id, JSONObject()
                .put("id", "fixture:film").put("type", "movie").put("name", "Generated Film"))!!
        instrumentation.runOnMainSync { context.startActivity(WatchActivity.intent(context, title, title.id, "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    @Throws(Exception::class)
    open fun playFilm() {
        readyLibrary(); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource()
        waitUntil("first frame and advancing playback", 60_000) { state() != null && state()!!.renderedFirstFrame && state()!!.positionMs >= 2000 }
    }

    companion object {
        @JvmStatic fun waitUntil(message: String, timeout: Long, condition: BooleanSupplier) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) { if (condition.asBoolean) return; SystemClock.sleep(100) }
            try {
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                if (image != null) { java.io.FileOutputStream(java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "validation-failure.png")).use { out -> image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }; image.recycle() }
            } catch (ignored: Exception) { /* keep the original assertion */ }
            fail("Timed out: $message")
        }
    }
}
