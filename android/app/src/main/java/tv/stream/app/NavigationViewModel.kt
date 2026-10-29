package tv.stream.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import tv.stream.app.ui.Page

/** Serializable navigation identity; fetched pages, callbacks and playable addresses never enter saved state. */
data class Destination(val screen: String, val value: String, val video: String)

data class NavigationState(
    val destinations: List<Destination> = emptyList(),
    val searchExpanded: Boolean = false,
    val searchReturnTab: String = "Home",
)

/** The back stack and remembered tab roots are independent of rendering and playback. */
class NavigationViewModel : ViewModel() {
    // Route's mutable fields are view restoration data (focus/scroll/category), not application data.
    val stack = mutableListOf<Route>()
    val tabRoots = mutableMapOf<String, Route>()
    var searchExpanded = false
    var searchReturnTab = "Home"
    private val mutableState = MutableStateFlow(NavigationState())
    val state = mutableState.asStateFlow()

    fun root(screen: String) {
        searchExpanded = false
        stack.clear()
        if (screen == "Add-ons") {
            stack += Route("Settings")
            stack += StoreScreens.route("All")
        } else stack += tabRoots.getOrPut(screen) { Route(screen) }
        if (stack.first().screen in TABS) searchReturnTab = stack.first().screen
        publish()
    }

    fun search(returnTab: String) {
        searchReturnTab = returnTab
        searchExpanded = true
        stack.clear()
        stack += tabRoots.getOrPut("Search") { Route("Search") }
        publish()
    }

    fun push(route: Route) { stack += route; publish() }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex); publish() }

    fun save(): String = JSONArray().also { out -> stack.forEach { out.put(it.save()) } }.toString()

    fun restore(text: String?, returnTab: String) {
        if (stack.isNotEmpty()) return
        searchReturnTab = returnTab
        try {
            val saved = JSONArray(text)
            repeat(saved.length()) { stack += Route.restore(saved.getJSONObject(it)) }
        } catch (_: Exception) { stack.clear() }
        searchExpanded = stack.firstOrNull()?.screen == "Search"
        publish()
    }

    /** Drop activity-bound actions while retaining identity, metadata and focus through recreation. */
    fun detach() {
        // An interrupted provider may still hold its old Route. It must not write an old activity's
        // callbacks into the new activity's retained route after cancellation.
        val copies = java.util.IdentityHashMap<Route, Route>()
        fun detached(route: Route): Route = copies.getOrPut(route) {
            Route(route.screen).also {
                it.focus = route.focus; it.value = route.value; it.video = route.video; it.title = route.title
                it.season = route.season; it.page = route.page; it.scroll = route.scroll
                it.childScroll.putAll(route.childScroll); it.extras.putAll(route.extras)
                it.cache = route.cache.takeUnless { cache -> cache is Page }; it.cachedAt = route.cachedAt
            }
        }
        val retained = stack.map(::detached)
        tabRoots.replaceAll { _, route -> detached(route) }
        stack.clear(); stack.addAll(retained)
        publish()
    }

    fun publish() {
        mutableState.value = NavigationState(
            stack.map { Destination(it.screen, it.value, it.video) }, searchExpanded, searchReturnTab)
    }

    companion object {
        val TABS = listOf("Home", "Browse", "Library", "Settings")
    }
}
