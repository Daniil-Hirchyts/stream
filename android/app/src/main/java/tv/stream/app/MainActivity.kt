package tv.stream.app

import tv.stream.playback.SubtitleStyle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.function.Consumer
import java.util.function.DoubleSupplier
import tv.stream.app.addon.AddonException
import tv.stream.app.addon.AddonUrl
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title
import tv.stream.app.ui.Artwork
import tv.stream.app.ui.Backdrop
import tv.stream.app.ui.Failures
import tv.stream.app.ui.IntroView
import tv.stream.app.ui.NavigationRail
import tv.stream.app.ui.PageViewport
import tv.stream.app.ui.Item
import tv.stream.app.ui.Page
import tv.stream.app.ui.PageAdapter
import tv.stream.app.ui.Sounds
import tv.stream.app.ui.Tv

/**
 * TV navigation shell: tabs, back stack, artwork and dialogs. Screens build pages on application workers; each
 * route render owns a cancellable provider scope and a generation, so late results never reach a newer screen.
 */
class MainActivity : ComponentActivity(), Navigator, PageAdapter.Listener {
    private val screenModel by viewModels<BrowseViewModel>()
    private val routes by viewModels<NavigationViewModel>()
    private lateinit var app: TvApplication
    private var services: TvApplication.Services? = null
    private lateinit var list: RecyclerView
    private lateinit var adapter: PageAdapter
    private lateinit var artwork: Artwork
    private lateinit var backdrop: Backdrop
    private lateinit var navigation: LinearLayout
    private lateinit var topBar: NavigationRail
    private lateinit var searchButton: View
    private lateinit var scrollFade: View
    private lateinit var pageBack: View
    private lateinit var pageTools: View
    private lateinit var pageLayout: PageAdapter.Layout
    private var shownRoute: Route? = null
    private var shownPage: Page? = null
    private val dirtyPages: MutableSet<Route> = java.util.HashSet()
    private val retainedPages: MutableMap<Route, Page> = java.util.IdentityHashMap()
    private val tabRoots get() = routes.tabRoots
    private var renderedConfiguration: Long = -1
    private lateinit var clock: android.widget.TextClock
    private var clockFormat = "off"
    private var pageLoading = true
    private var browseClockReady = false
    private var seriesTransition = 0
    private val main = Handler(Looper.getMainLooper())
    private lateinit var stack: MutableList<Route>
    private lateinit var prompts: BrowseDialogs
    private var browse: BrowseScreens? = null
    private var titles: TitleScreens? = null
    private var setup: SetupScreens? = null
    private var store: StoreScreens? = null
    private val scope get() = screenModel.request
    private val generation get() = screenModel.generation
    private var stopped = false
    private var contentReady = false
    private var incomingHandled = false
    private var searchExpanded: Boolean
        get() = routes.searchExpanded
        set(value) { routes.searchExpanded = value }
    private var searchReturnTab: String
        get() = routes.searchReturnTab
        set(value) { routes.searchReturnTab = value }
    private var searchDebounce: Runnable? = null
    /** A tab was chosen: keep focus in the rail until Right enters its content. */
    private var waitingForContentFocus = false
    private var leftPressed = false
    /** Saved series changed while their page was loading; it rebuilds when that load finishes. */
    private var libraryChanged = false
    private var delayedLoading: Runnable? = null
    private var loadStarted: Long = 0
    private var resetLoadingContent = false
    private var keepLoadingContent = false
    private var incomingFile: Uri? = null
    /** The start screen, over the browse shell until Home and its first artwork are ready (owner launches only). */
    private var intro: IntroView? = null
    private var introStarted: Long = 0
    private var introHome = false
    private var introArtwork = false
    /** Where the next page enters from: 1 after opening a screen, -1 after Back, 0 for a tab (it rises instead). */
    private var enterDirection = 0
    private var pageEntering = false
    private var pageEntrance: ViewTreeObserver.OnPreDrawListener? = null

    public override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        stack = routes.stack
        app = application as TvApplication
        prompts = BrowseDialogs(this, { main.post { updateTopBar() } }) {
            val view = list.findViewWithTag<View>("subtitle-preview")
            if (view is tv.stream.app.ui.SubtitlePreviewView) view.updateFrom(services!!.playerPreferences)
        }
        Sounds.get(this)
        incomingHandled = saved != null && saved.getBoolean("incomingHandled")
        incomingFile = if (incomingHandled) null else incoming(intent)
        setContentView(R.layout.activity_browse)
        // Instrumentation finds controls as soon as they exist, so only the owner's launches show the start screen.
        if (saved == null && RuntimeOptions.installDefaults(this)) {
            intro = IntroView(this)
            (findViewById<View>(R.id.browse_root) as FrameLayout).addView(intro, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            introStarted = android.os.SystemClock.uptimeMillis()
            Sounds.play(Sounds.Sound.INTRO)
            // However slow the providers are, the start screen never holds the app for long.
            main.postDelayed({ finishIntro() }, 12_000)
        }
        artwork = Artwork(java.io.File(cacheDir, "artwork"))
        backdrop = Backdrop(artwork, findViewById(R.id.backdrop), findViewById(R.id.backdrop_back), findViewById(R.id.backdrop_soft), findViewById(R.id.backdrop_scrim))
        list = findViewById(R.id.page_rows)
        pageLayout = PageAdapter.Layout(this, RecyclerView.VERTICAL, 40)
        list.layoutManager = pageLayout
        list.itemAnimator = null
        list.isFocusableInTouchMode = true; list.setDefaultFocusHighlightEnabled(false)
        // Off-screen shelves keep their views: scrolling back never rebuilds a row or reloads its artwork.
        list.setItemViewCacheSize(10)
        adapter = PageAdapter(this, artwork, this)
        list.adapter = adapter
        navigation = findViewById(R.id.navigation)
        topBar = findViewById(R.id.top_bar)
        scrollFade = findViewById(R.id.scroll_fade)
        searchButton = findViewById(R.id.search_button)
        pageBack = findViewById(R.id.page_back)
        pageTools = findViewById(R.id.home_tools)
        clock = findViewById(R.id.clock)
        topBar.decorate(searchButton as FrameLayout, R.drawable.ic_tv_search, "Search")
        val reveal = findViewById<View>(R.id.navigation_reveal)
        reveal.setOnClickListener { focusNavigation() }
        reveal.isFocusable = false
        val dismiss = findViewById<View>(R.id.navigation_dismiss)
        dismiss.setOnClickListener { dismissNavigation() }
        dismiss.isFocusable = false
        Tv.control(pageBack, "nav:back", Runnable { pageBack() }, Consumer<String> { }, Tv.LIFT_ICON, false)
        wireUtility(pageBack)
        for (utility in arrayOf(pageBack, findViewById<View>(R.id.home_refresh))) Tv.hover(utility, DoubleSupplier { Tv.LIFT_ICON.toDouble() })
        searchButton.setDefaultFocusHighlightEnabled(false)
        for (screen in TABS) navigation.addView(tab(screen))
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) { updateTopBar() }
        })
        // Parents finish recording their focused descendant after this callback returns.
        list.viewTreeObserver.addOnGlobalFocusChangeListener { old, now ->
            // The initial focus is assigned before contentReady. Capture it when it leaves the page as well,
            // so the rail and toolbar return to that control rather than the first filter or row.
            if (contentReady && !stack.isEmpty() && shownRoute === route() && insidePage(old) && old!!.tag is String)
                route().focus = adapter.persistentFocus(old.tag as String)
            main.post { updateTopBar() }
        }
        Tv.control(searchButton, "nav:search", Runnable { search(); enterContent() }, Consumer<String> { }, DoubleSupplier { topBar.lift() }, false)
        Tv.hover(searchButton, DoubleSupplier { topBar.lift() })
        // ComponentActivity bridges this callback to platform Back on API 33+ and onBackPressed below it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        if (saved != null) {
            routes.restore(saved.getString("routes", ""), saved.getString("searchReturnTab", "Home"))
        }
        if (stack.isEmpty()) stack.add(Route("Starting"))
        if (stack[0].screen == "Search") searchExpanded = true
        searchReturnTab = primaryTab(searchReturnTab)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                screenModel.state.collect { state ->
                    if (!stopped && !isFinishing) when (state) {
                        is PageScreenState.Content -> if (state.generation == generation) {
                            val page = state.content.render()
                            if (state.partial) showPartial(page) else show(page)
                        }
                        is PageScreenState.Failed -> if (state.generation == generation) failed(state.cause)
                        else -> Unit
                    }
                }
            }
        }
        loading()
        initialize()
    }
    private fun tab(screen: String): View {
        val tab = FrameLayout(this)
        val icon = if (screen == "Home") R.drawable.ic_tv_home else if (screen == "Browse") R.drawable.ic_tv_explore
                else if (screen == "Library") R.drawable.ic_tv_library else R.drawable.ic_tv_settings
        topBar.decorate(tab, icon, screen)
        tab.id = View.generateViewId(); tab.setBackgroundResource(R.drawable.tv_tab)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 44f))
        params.topMargin = Tv.dp(this, 6f); tab.layoutParams = params
        Tv.control(tab, "nav:" + screen, Runnable { Sounds.play(Sounds.Sound.TAB); root(screen); enterContent() }, Consumer<String> { }, DoubleSupplier { topBar.lift() }, false)
        Tv.hover(tab, DoubleSupplier { topBar.lift() })
        return tab
    }
    private fun initialize() {
        work(Callable { val s = app.services(); Sounds.get(this).setEnabled(SetupScreens.soundsOn(s)); s }, Consumer { value ->
            services = value
            browse = BrowseScreens(this, services!!); titles = TitleScreens(this, services!!); setup = SetupScreens(this, this, services!!); store = StoreScreens(this, services!!)
            // No onboarding: the app opens on Home and sets itself up with the starter add-ons in the background.
            checkNewEpisodes()
            if (route().screen == "Starting" && intro != null) { introSetup(); offerIncomingFile(); return@Consumer }
            if (route().screen == "Starting") root("Home") else render()
            offerIncomingFile()
            if (RuntimeOptions.installDefaults(this)) app.workers.execute {
                if (StarterAddons.installDefaults(services!!) > 0) main.post { if (!stopped && !isFinishing && !stack.isEmpty() && route().screen == "Home") render() }
            }
        })
    }

    /**
     * Fetches followed series' episode lists in the background (each at most twice a day). When one changes, Home and
     * Library rebuild from local data, keeping their provider results; one still loading rebuilds once it has finished.
     */
    private fun checkNewEpisodes() {
        val data = services
        if (data == null || !RuntimeOptions.checkNewEpisodes(this)) return
        app.background.execute {
            if (!NewEpisodes.check(data.store, data.addons, System.currentTimeMillis())) return@execute
            main.post {
                if (stopped || isFinishing || services !== data) return@post
                for (saved in retainedPages.keys) if (showsLibrary(saved)) dirtyPages.add(saved)
                if (stack.isEmpty() || !showsLibrary(route())) return@post
                if (pageLoading) libraryChanged = true else { rememberFocus(); render(true) }
            }
        }
    }

    // Start screen.
    /** Starter add-ons install before Home is first built, so a first launch opens on filled shelves. */
    private fun introSetup() {
        val home = Runnable {
            if (introHome || isFinishing) return@Runnable
            introHome = true
            root("Home")
        }
        app.workers.execute {
            val added = StarterAddons.installDefaults(services!!)
            main.post {
                if (!introHome) home.run()
                else if (added > 0 && !stopped && !isFinishing && !stack.isEmpty() && route().screen == "Home") render()
            }
        }
        // Unreachable add-on servers don't hold Home back; a late install refreshes it as before.
        main.postDelayed(home, 6_000)
    }
    /** Warms the artwork Home shows first (the spotlight and the leading posters), feeding the posters to the wall. */
    private fun introArtwork(page: Page) {
        if (intro == null || introArtwork) return
        introArtwork = true
        val posters: MutableList<String> = ArrayList()
        var spotlight: String? = null
        for (item in page.items) for (tile in item.tiles) {
            if (spotlight == null && tile.media != null) spotlight = Backdrop.art(tile.media)
            if (tile.image != null && posters.size < 27 && !posters.contains(tile.image)) posters.add(tile.image!!)
        }
        val total = posters.size + (if (spotlight == null) 0 else 2)
        if (total == 0) { finishIntro(); return }
        val done = intArrayOf(0)
        val arrived = Runnable {
            done[0]++
            if (done[0] == total) finishIntro()
        }
        if (spotlight != null) {
            artwork.fetch(spotlight, Artwork.BACKDROP, false, Consumer { bitmap -> arrived.run() })
            artwork.softened(spotlight, Consumer { bitmap -> arrived.run() })
        }
        for (url in posters) artwork.fetch(url, Artwork.THUMBNAIL, false, Consumer { bitmap -> if (intro != null) intro!!.poster(bitmap); arrived.run() })
        // Artwork is optional: failed downloads never answer, so the wait has an end.
        main.postDelayed({ finishIntro() }, 4_000)
    }
    /** Leaves once the opening animation has played out, revealing the page already in place beneath. */
    private fun finishIntro() {
        if (intro == null || intro!!.finishing()) return
        var wait = IntroView.minimumMs() - (android.os.SystemClock.uptimeMillis() - introStarted)
        wait = Math.max(wait, intro!!.postersMs())
        if (wait > 0) { main.postDelayed({ finishIntro() }, wait); return }
        val leaving: IntroView = intro!!
        Sounds.play(Sounds.Sound.INTRO_EXIT)
        leaving.finish(Runnable {
            (leaving.parent as ViewGroup).removeView(leaving)
            if (intro === leaving) {
                intro = null
                updateClock()
            }
        })
    }

    // Routes and rendering.
    private fun route(): Route { return stack[stack.size - 1] }
    private fun rememberFocus() {
        if (stack.isEmpty() || shownRoute !== route() || !contentReady) return
        val route = route(); route.scroll = list.layoutManager!!.onSaveInstanceState()
        route.childScroll.clear(); route.childScroll.putAll(adapter.scrollPositions())
        val focus = currentFocus
        if (insidePage(focus) && focus!!.tag is String) route.focus = adapter.persistentFocus(focus.tag as String)
    }
    private fun pageBack() {
        if (stack.size > 1 || searchExpanded) { onBackPressed(); return }
        if (route().screen != "Home") root("Home") else finish()
    }
    override fun root(screen: String) {
        var screen = screen
        if (screen == "Catalogues") screen = "Browse"
        if (screen == "Search") { search(); return }
        if (services == null) return
        searchExpanded = false
        rememberFocus()
        routes.root(screen)
        render(true)
    }
    override fun search() {
        if (services == null) return
        if (!stack.isEmpty() && stack[0].screen == "Search") {
            searchExpanded = true; chrome(route().screen); return
        }
        searchReturnTab = if (stack.isEmpty()) "Home" else primaryTab(stack[0].screen)
        searchExpanded = true
        rememberFocus(); routes.search(searchReturnTab); render(true)
    }
    override fun searchInput(value: String) {
        resetLoadingContent = true
        for (saved in stack) if (saved.screen == "Search") {
            saved.value = value
        }
        routes.publish()
        if (stack.isEmpty() || route().screen != "Search") return
        if (searchDebounce != null) main.removeCallbacks(searchDebounce!!)
        cancelWork()
        searchDebounce = Runnable {
            searchDebounce = null
            if (!stack.isEmpty() && route().screen == "Search") render()
        }
        main.postDelayed(searchDebounce!!, 280)
    }
    override fun openImports() {
        if (services == null) return
        if (stack.size == 1 && stack[0].screen == "Settings") push(Route("Imports"))
        else { root("Settings"); push(Route("Imports")) }
    }
    private fun closeSearch() {
        if (!searchExpanded) return
        searchExpanded = false
        root(primaryTab(searchReturnTab))
    }
    private fun isPrimaryTab(screen: String): Boolean {
        for (tab in TABS) if (tab == screen) return true
        return false
    }
    private fun primaryTab(screen: String): String {
        if (screen == "Catalogues" || screen == "Catalogue") return "Browse"
        if (screen == "Imports" || screen == "Add-on" || screen == "Developer" || screen == "Add-ons" || screen == "Add-on review") return "Settings"
        if (screen == "Search") return if (isPrimaryTab(searchReturnTab)) searchReturnTab else "Home"
        return if (isPrimaryTab(screen)) screen else "Home"
    }
    override fun push(next: Route) { Sounds.play(Sounds.Sound.OPEN); rememberFocus(); routes.push(next); enterDirection = 1; render() }
    override fun back() { onBackPressed() }
    override fun refresh() { rememberFocus(); render(false, true) }
    override fun changed() { resetLoadingContent = true; render() }
    private fun cancelLoading() { if (delayedLoading != null) { main.removeCallbacks(delayedLoading!!); delayedLoading = null } }
    private fun cancelWork() { cancelLoading(); screenModel.cancel() }
    private fun render() { render(false) }
    private fun render(restoreOnly: Boolean) { render(restoreOnly, false) }
    private fun render(restoreOnly: Boolean, refreshData: Boolean) {
        seriesTransition++
        if (!pageEntering || shownRoute !== route()) {
            list.animate().cancel(); list.alpha = 1f; list.translationX = 0f; list.translationY = 0f
        }
        if (searchDebounce != null) { main.removeCallbacks(searchDebounce!!); searchDebounce = null }
        cancelWork()
        if (stopped || services == null) return
        screenModel.begin(services!!.addons.newRequest())
        refreshClock()
        val route = route()
        loadStarted = android.os.SystemClock.uptimeMillis()
        keepLoadingContent = shownRoute === route && shownPage != null && !resetLoadingContent
        resetLoadingContent = false
        tabRoots[stack[0].screen] = stack[0]
        if (renderedConfiguration != services!!.addons.configuration()) {
            dirtyPages.addAll(retainedPages.keys); renderedConfiguration = services!!.addons.configuration()
        }
        if (route.screen != "Details" && route.screen != "Sources") services!!.preparation.leave()
        if (shownRoute !== route) adapter.restoreScrollPositions(route.childScroll)
        if (shownRoute !== route) {
            // Hold focus before removing the clicked card. Skeletons have no controls; letting the framework
            // choose a fallback here briefly highlights Home before the page's Play button arrives.
            waitingForContentFocus = true
            list.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            list.requestFocus()
        }
        chrome(route.screen)
        val kept: MutableSet<Route> = java.util.HashSet(stack); kept.addAll(tabRoots.values)
        retainedPages.keys.retainAll(kept); dirtyPages.retainAll(kept)
        // Restoring data/focus on a tab or Back is still navigation and needs the same visible entrance.
        if (shownRoute !== route) preparePageEntrance(route)
        val retained = retainedPages[route]
        val dirty = dirtyPages.contains(route)
        if (shownRoute !== route && retained != null) show(retained)
        if (restoreOnly && retained != null && !dirty && retained.reusable) return
        val updating = shownRoute === route && shownPage != null
        if (!updating) { loading(); updateTopBar() }
        else if (route.screen == "Search" && !(restoreOnly && retained != null)) loading()
        routes.publish()
        screenModel.load(app.workers) { request, progress ->
            build(route, request, Consumer(progress), refreshData, restoreOnly)
        }
    }
    /** Reads the clock setting off the main thread; a page rebuilt after Settings changes picks up the new one. */
    private fun refreshClock() {
        val data = services
        app.workers.execute {
            val format = SetupScreens.clockFormat(data!!)
            main.post { if (!isFinishing) {
                clockFormat = format
                updateClock()
            } }
        }
    }
    private fun updateClock() {
        tv.stream.app.ui.Clock.apply(clock, clockFormat,
                intro == null && (browseClockReady || !pageLoading) && !stack.isEmpty() && route().screen != "Starting")
    }
    /** Start with the new page's first laid-out frame, not while its worker is still fetching/building it. */
    private fun preparePageEntrance(expected: Route) {
        cancelPageEntrance()
        val direction = enterDirection; enterDirection = 0
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        pageEntering = true; list.alpha = 0f
        pageEntrance = ViewTreeObserver.OnPreDrawListener {
            if (stopped || route() !== expected) { cancelPageEntrance(); return@OnPreDrawListener true }
            if (adapter.itemCount == 0 || !hasVisiblePageBlock() || list.hasPendingAdapterUpdates()) return@OnPreDrawListener true
            val current = pageEntrance
            if (current != null) list.viewTreeObserver.removeOnPreDrawListener(current)
            pageEntrance = null
            enter(direction); true
        }
        list.viewTreeObserver.addOnPreDrawListener(pageEntrance!!)
    }
    private fun hasVisiblePageBlock(): Boolean {
        for (i in 0 until list.childCount) {
            val block = list.getChildAt(i) as ViewGroup
            if (block.childCount > 0 && block.getChildAt(0).visibility == View.VISIBLE && block.height > 0) return true
        }
        return false
    }
    private fun cancelPageEntrance() {
        if (pageEntrance != null) { list.viewTreeObserver.removeOnPreDrawListener(pageEntrance!!); pageEntrance = null }
        pageEntering = false
        list.animate().cancel(); list.alpha = 1f; list.translationX = 0f; list.translationY = 0f
    }
    /** A new page slides a little way in from the direction of travel and fades up. */
    private fun enter(direction: Int) {
        list.animate().cancel()
        list.alpha = 0f
        list.translationX = (direction * Tv.dp(this, 36f)).toFloat()
        list.translationY = (if (direction == 0) Tv.dp(this, 16f) else 0).toFloat()
        list.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                .withEndAction { pageEntering = false }.start()
    }
    @Throws(Exception::class)
    private fun build(route: Route, request: Cancellation, progress: Consumer<Page>, refreshData: Boolean, restoring: Boolean): Page {
        return when (route.screen) {
            "Home" -> browse!!.home(request, progress, refreshData, restoring)
            "Search" -> browse!!.search(route, request, progress, refreshData, restoring)
            "Library" -> browse!!.library()
            "Imports" -> setup!!.imports()
            "Settings" -> setup!!.settings(route)
            "Add-on" -> setup!!.addon(route)
            "Add-ons" -> store!!.store(route, request, progress)
            "Add-on review" -> store!!.review(route, request)
            "Browse", "Catalogues" -> browse!!.catalogues()
            "Catalogue" -> browse!!.catalogue(route, request, progress, refreshData)
            "Details" -> titles!!.details(route, request, progress)
            "Sources" -> titles!!.sources(route, request, progress)
            "Developer" -> setup!!.developer()
            "Keyboard languages" -> setup!!.keyboardLanguages()
            else -> browse!!.home(request, progress, refreshData, restoring)
        }
    }
    /** Browse chrome is persistent; search keeps its editor in the content viewport. */
    private fun chrome(screen: String) {
        val tabs = screen != "Starting"
        val rootScreen = if (stack.isEmpty()) "Home" else stack[0].screen
        searchButton.isActivated = rootScreen == "Search"
        for (i in 0 until navigation.childCount)
            navigation.getChildAt(i).isActivated = rootScreen != "Search" && TABS[i] == primaryTab(rootScreen)
        val canGoBack = tabs && (stack.size > 1 || searchExpanded
                || !isPrimaryTab(screen))
        pageBack.visibility = if (canGoBack) View.VISIBLE else View.GONE
        if (shownRoute !== route()) pageTools.visibility = View.GONE
        // Shelves fade out under the rail; with the rail hidden they run cleanly to the screen edge.
        (findViewById<View>(R.id.browse_content) as PageViewport).fade(if (hiddenNavigation()) 0 else contentStart())
        wireRail(); updateTopBar()
    }
    /** Page composition owns visibility; scrolling and opening navigation never change the page's geometry. */
    private fun hiddenNavigation(): Boolean {
        if (stack.isEmpty()) return true
        return when (route().screen) {
            "Starting", "Search", "Details", "Sources" -> true
            else -> false
        }
    }
    private fun contentInset(): Int { return if (hiddenNavigation()) NavigationRail.HIDDEN_CONTENT_START else NavigationRail.CONTENT_START }
    /** Where the page's content starts, in pixels: clear of the rail, or of the screen edge when the rail is hidden. */
    fun contentStart(): Int { return Tv.dp(this, contentInset().toFloat()) }
    private fun updateTopBar() {
        // A rebound keyboard can restore focus before RecyclerView has attached every holder.
        if (list.isComputingLayout) { list.post { updateTopBar() }; return }
        val offset = list.computeVerticalScrollOffset()
        scrollFade.alpha = if (!stack.isEmpty() && route().screen == "Search") 0f else Math.min(1f, offset / Tv.dp(this, 32f).toFloat())
        if (prompts.isShowing) return
        val hidden = hiddenNavigation()
        val state = if (hidden) NavigationRail.State.HIDDEN else if (topBar.hasFocus())
                (if (topBar.state() == NavigationRail.State.EXPANDED) NavigationRail.State.EXPANDED else NavigationRail.State.WRAPPED)
                else NavigationRail.State.WRAPPED
        topBar.state(state)
        if (hidden) topBar.visibility = View.INVISIBLE
        findViewById<View>(R.id.navigation_reveal).visibility = View.GONE
        findViewById<View>(R.id.navigation_dismiss).visibility = if (state == NavigationRail.State.EXPANDED) View.VISIBLE else View.GONE
    }
    private fun focusNavigation() {
        if (hiddenNavigation()) return
        rememberFocus()
        // Entering navigation only transfers focus. A separate Left press opens its labels.
        topBar.state(NavigationRail.State.WRAPPED)
        currentTab().requestFocus()
        updateTopBar()
    }
    private fun dismissNavigation() { enterContent(); updateTopBar() }
    /** Only destinations belong to the rail; utilities retain their own top-corner focus paths. */
    private fun wireRail() {
        val controls: MutableList<View> = ArrayList(); controls.add(searchButton)
        for (i in 0 until navigation.childCount) controls.add(navigation.getChildAt(i))
        for (i in controls.indices) {
            val control = controls[i]
            control.nextFocusUpId = controls[Math.max(0, i - 1)].id
            control.nextFocusDownId = controls[Math.min(controls.size - 1, i + 1)].id
            control.nextFocusLeftId = control.id
            control.setOnKeyListener(View.OnKeyListener { view, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        topBar.state(NavigationRail.State.EXPANDED)
                        updateTopBar()
                    }
                    return@OnKeyListener true
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP && view === searchButton && pageBack.isShown) {
                    if (event.action == KeyEvent.ACTION_DOWN) pageBack.requestFocus()
                    return@OnKeyListener true
                }
                if (code != KeyEvent.KEYCODE_DPAD_RIGHT) return@OnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) enterContent()
                true
            })
        }
    }
    private fun wireUtility(control: View) {
        control.nextFocusUpId = control.id
        control.setOnKeyListener(View.OnKeyListener { view, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) enterContent()
                return@OnKeyListener true
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (view !== pageBack && pageBack.isShown) pageBack.requestFocus() else focusNavigation()
                }
                return@OnKeyListener true
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN && view === pageBack && pageTools.isShown) findViewById<View>(R.id.home_refresh).requestFocus()
                return@OnKeyListener true
            }
            false
        })
    }
    private fun enterContent() {
        if (stack.isEmpty()) return
        topBar.state(if (hiddenNavigation()) NavigationRail.State.HIDDEN else NavigationRail.State.WRAPPED)
        val target: View? = list.findViewWithTag(route().focus)
        if (target != null && target.isShown && target.isFocusable) target.requestFocus()
        else {
            val first = firstFocusable(list)
            if (first != null) first.requestFocus()
            else list.requestFocus() // Right can collapse the rail even on an empty or still-loading page.
        }
    }
    private fun showPartial(page: Page) {
        cancelLoading()
        val skeleton = page.items.stream().anyMatch { item -> item.kind == Item.Kind.SKELETON }
        if (skeleton && keepLoadingContent) return
        val delay = SKELETON_DELAY_MS - (android.os.SystemClock.uptimeMillis() - loadStarted)
        if (skeleton && delay > 0) {
            val shell = page.copy(); shell.items.removeIf { item -> item.kind == Item.Kind.SKELETON }
            show(shell, true)
            val mine = generation
            delayedLoading = Runnable { delayedLoading = null; if (mine == generation && !stopped && pageLoading) show(page, true) }
            main.postDelayed(delayedLoading!!, delay)
        } else show(page, true)
    }
    private fun show(page: Page) { show(page, false) }
    /** Refresh is always icon-only, fixed beside the clock at the top right. */
    private fun bindTools(page: Page) {
        val tool = findViewById<View>(R.id.home_refresh)
        var action: Item.Action? = null
        for (candidate in page.tools) if (candidate.icon == R.drawable.ic_tv_refresh) { action = candidate; break }
        pageTools.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action == null) { tool.tag = null; return }
        tool.contentDescription = action.label
        val run = action.action
        Tv.control(tool, action.key, Runnable { animateRefresh(tool); run.run() }, Consumer<String> { }, Tv.LIFT_ICON, false)
        wireUtility(tool)
    }
    private fun animateRefresh(tool: View) {
        val icon = (tool as ViewGroup).getChildAt(0)
        icon.animate().cancel()
        icon.animate().rotationBy(360f).setDuration(550)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }
    private fun show(page: Page, partial: Boolean) {
        var page = page
        if (!partial) cancelLoading()
        if (route().screen == "Search") for (item in page.items) if (item.kind == Item.Kind.KEYBOARD)
            item.keyboardVisible = "false" != route().extras["searchKeyboard"]
        if (route().screen == "Details" && episodeBrowser(page) != null) {
            for (item in page.items) if (item.kind == Item.Kind.DETAILS) { route().cache = page; break }
            if (browsingEpisodes()) page = episodePage(page)
        }
        pageLoading = partial
        if (!partial) browseClockReady = true
        updateClock()
        val updating = shownRoute === route() && shownPage != null
        val previousPage = shownPage
        shownRoute = route(); shownPage = page
        if (!partial) { retainedPages[shownRoute!!] = page; dirtyPages.remove(shownRoute) }
        if (!partial && libraryChanged) {
            libraryChanged = false
            val changed: Route = shownRoute!!
            if (showsLibrary(changed)) {
                dirtyPages.add(changed)
                main.post { if (!stopped && !stack.isEmpty() && route() === changed) { rememberFocus(); render(true) } }
            }
        }
        if (!partial && intro != null && shownRoute!!.screen == "Home") introArtwork(page)
        contentReady = false
        val route = route()
        bindTools(page)
        // Titles scroll with their page, as on tvOS, rather than floating over it.
        var items: List<Item> = page.items
        if (page.title != null) { val titled: MutableList<Item> = ArrayList(page.items); titled.add(0, Item.pageTitle(page.title!!)); items = titled }
        gutters(page.narrow)
        // Home's spotlight sets the artwork itself; clearing it first would flash the plain canvas.
        val spotlight = !page.items.isEmpty() && page.items[0].kind == Item.Kind.SPOTLIGHT
        contentTop(spotlight)
        val sameBackdrop = updating && previousPage != null && previousPage.blurBackdrop == page.blurBackdrop
                && java.util.Objects.equals(if (previousPage.backdrop == null) null else previousPage.backdrop!!.cacheJson(),
                        if (page.backdrop == null) null else page.backdrop!!.cacheJson())
        if (!spotlight && !sameBackdrop) backdrop.show(page.backdrop, true, page.blurBackdrop)
        // A partial page is visual context, not a second focus destination on the way to the real controls.
        if (partial && waitingForContentFocus) { adapter.set(items, ""); return }
        val preserveNavigation = !waitingForContentFocus && !list.hasFocus()
                && (pageBack.hasFocus() || topBar.hasFocus() || pageTools.hasFocus())
        // Restore the route's focus; otherwise start on the page's first choice, unless the user is on the tabs.
        var wanted: String = if (!route.focus.isEmpty()) route.focus else if (page.initialFocus != null) page.initialFocus!! else ""
        if (wanted.isEmpty() && page.firstTile() != null) wanted = page.firstTile()!!
        if (adapter.set(items, if (preserveNavigation) "" else wanted)) {
            if (!partial) finishContentFocusWait()
            contentReady = true; return
        }
        if (partial && updating) { contentReady = true; return }
        if (!updating || !preserveNavigation) {
            if (route.scroll != null) list.layoutManager!!.onRestoreInstanceState(route.scroll)
            else list.scrollToPosition(0)
        }
        var available = wanted
        if (!wanted.isEmpty() && adapter.position(wanted) < 0 && pageTools.findViewWithTag<View>(wanted) == null)
            available = if (page.initialFocus != null) page.initialFocus!! else if (page.firstTile() == null) "" else page.firstTile()!!
        val target = available
        val mine = generation
        afterPageLayout {
            if (mine != generation || stopped) return@afterPageLayout
            finishContentFocusWait()
            if (preserveNavigation) { contentReady = true; return@afterPageLayout }
            var found: View? = if (target.isEmpty()) null else pageTools.findViewWithTag(target)
            if (found == null && !target.isEmpty()) found = list.findViewWithTag(target)
            if (found != null) found.requestFocus()
            else if (!target.isEmpty() && adapter.position(target) >= 0) {
                list.scrollToPosition(adapter.position(target))
                afterPageLayout {
                    if (mine != generation || stopped) return@afterPageLayout
                    val restored: View? = list.findViewWithTag(target)
                    if (restored != null) restored.requestFocus() else focusFirst()
                    contentReady = true
                }
                return@afterPageLayout
            } else focusFirst()
            contentReady = true
        }
    }
    private fun finishContentFocusWait() {
        waitingForContentFocus = false
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
    private fun afterPageLayout(action: Runnable) {
        val afterBindings = Runnable {
            if (!adapter.hasPendingBindings()) { action.run(); return@Runnable }
            list.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (adapter.hasPendingBindings()) return true
                    list.viewTreeObserver.removeOnPreDrawListener(this)
                    action.run()
                    return true
                }
            })
            list.postInvalidateOnAnimation()
        }
        if (!list.hasPendingAdapterUpdates() && !list.isLayoutRequested) { list.post(afterBindings); return }
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this); afterBindings.run()
            }
        })
    }
    private fun focusFirst() {
        val first = firstFocusable(list)
        if (first != null) first.requestFocus()
        else if (!navigation.hasFocus()) focusNavigation()
    }
    private fun currentTab(): View {
        if (!stack.isEmpty() && stack[0].screen == "Search") return searchButton
        val rootScreen = if (stack.isEmpty()) "Home" else primaryTab(stack[0].screen)
        for (i in TABS.indices) if (TABS[i] == rootScreen) return navigation.getChildAt(i)
        return navigation.getChildAt(0)
    }
    private fun loading() {
        pageLoading = true
        updateClock()
        contentReady = false
        if (!stack.isEmpty()) {
            val current = route()
            if (current.screen == "Sources" && current.title != null && titles != null) {
                val shell = titles!!.sourcesPage(current)
                showPartial(shell.add(Item.skeleton("Source results"))); return
            }
            if (current.screen == "Details" && current.title != null && titles != null) {
                val shell = titles!!.detailsPage(current.title!!, "")
                TitleScreens.pendingTitle(shell, current.title!!)
                showPartial(shell); return
            }
            if (current.screen == "Search" && browse != null) {
                val search = browse!!.searchLoading(current)
                val focus = if (current.focus.isEmpty()) "keyboard:a" else current.focus
                // Search's own gutter and top before its pane is bound: the previous page's include the rail's inset.
                gutters(false); contentTop(false)
                // Search uses the plain canvas: clear the previous page's artwork alongside the entrance, not after it.
                if (shownRoute !== current) backdrop.show(null, true, false)
                // Typing updates the results beside the keyboard; the focused key stays focused and keeps being remembered.
                if (adapter.set(java.util.Collections.singletonList(search), focus)) contentReady = true
                return
            }
            if (hasPageSkeleton(current.screen)) {
                // The skeleton takes the place its page will have, so nothing jumps when the page arrives.
                gutters(isNarrow(current.screen))
                contentTop(current.screen == "Home")
                adapter.set(java.util.Collections.emptyList(), "")
                val mine = generation
                delayedLoading = Runnable { delayedLoading = null; if (mine == generation && !stopped && pageLoading)
                    adapter.set(java.util.Collections.singletonList(Item.skeleton(current.screen)), "") }
                main.postDelayed(delayedLoading!!, SKELETON_DELAY_MS)
                return
            }
        }
        // Local lists/forms usually finish within a frame; a small delayed spinner covers unusually slow storage.
        adapter.set(java.util.Collections.emptyList(), "")
        val mine = generation
        delayedLoading = Runnable { delayedLoading = null; if (mine == generation && !stopped && pageLoading)
            adapter.set(java.util.Collections.singletonList(Item.loading()), "") }
        main.postDelayed(delayedLoading!!, SKELETON_DELAY_MS)
    }
    /** Reserve the top toolbar; Home artwork uses the full height. */
    private fun contentTop(spotlight: Boolean) {
        list.setPadding(0, Tv.dp(this, if (spotlight) 0f else 76f), 0, list.paddingBottom)
    }
    private fun gutters(narrow: Boolean) {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - contentInset()
        val side = if (narrow) Math.max(24, Math.round((widthDp - COLUMN) / 2)) else 24
        adapter.gutter(contentInset() + side, if (narrow) side else 40)
    }
    private fun failed(e: Throwable) {
        val mine = generation
        app.workers.execute {
            var enabled = false
            try { enabled = services != null && SetupScreens.developerMode(services!!) }
            catch (unavailable: RuntimeException) { /* No verified preference: keep diagnostics hidden. */ }
            val developer = enabled
            main.post { if (mine == generation && !stopped && !isFinishing) showFailure(e, developer) }
        }
    }
    private fun showFailure(e: Throwable, developer: Boolean) {
        finishIntro()
        Sounds.play(Sounds.Sound.WARNING)
        var reportAction: Item.Action? = null
        if (developer) {
            app.lastErrorReport = DiagnosticReport.capture(this, route().screen, "Unable to load", Failures.message(e), "application", e, null)
            reportAction = Item.Action("error-report", Item.Action.Style.SECONDARY, "Error report", Runnable { errorReport() })
        }
        if (shownRoute === route() && shownPage != null) {
            val retained = shownPage!!.copy()
            retained.items.removeIf { item -> item.kind == Item.Kind.SKELETON || item.key == "notice:Unable to update" || item.key == "notice:Loading add-on details" }
            val retry = Item.Action("retry-update", Item.Action.Style.SECONDARY, "Retry", Runnable { refresh() })
            retained.add(Item.notice(R.drawable.ic_tv_offline, "Unable to update", Failures.message(e),
                    *(if (reportAction == null) arrayOf(retry) else arrayOf(retry, reportAction))))
            retained.reusable = false
            show(retained); return
        }
        val retry = Item.Action("retry", Item.Action.Style.PRIMARY, "Retry", Runnable { if (services == null) { loading(); initialize() } else render() }).icon(R.drawable.ic_tv_refresh)
        val page = Page().add(Item.message(if (e is AddonException && e.kind == AddonException.Kind.CERTIFICATE) R.drawable.ic_tv_info else R.drawable.ic_tv_offline,
                "Unable to load", Failures.message(e),
                *(if (reportAction == null) arrayOf(retry) else arrayOf(retry, reportAction))))
        page.initialFocus = "retry"
        page.reusable = false
        show(page)
    }

    // Page listener.
    private var lastUpNavigation: Long = 0
    override fun focused(key: String) {
        if (!contentReady || stack.isEmpty()) return
        val current = route(); current.focus = key
        if (current.screen == "Home" && lastUpNavigation != 0L
                && android.os.SystemClock.uptimeMillis() - lastUpNavigation < 1000
                && adapter.firstSpotlightShelf(key) && list.canScrollVertically(-1)) {
            lastUpNavigation = 0
            list.post {
                if (stopped || stack.isEmpty() || route() !== current) return@post
                val scroll = object : androidx.recyclerview.widget.LinearSmoothScroller(this) {
                    override fun getVerticalSnapPreference(): Int { return androidx.recyclerview.widget.LinearSmoothScroller.SNAP_TO_START }
                }
                scroll.targetPosition = 0; list.layoutManager!!.startSmoothScroll(scroll)
            }
        }
        if (current.screen == "Details" && (key.startsWith("season:") || key.startsWith("episode:"))
                && !browsingEpisodes() && shownPage != null && episodeBrowser(shownPage!!) != null) {
            current.extras["episodes"] = "true"; current.scroll = null
            // Focus settles first; the same attached season strip survives the surrounding layout change.
            main.post { if (!stopped && route() === current && browsingEpisodes())
                transitionSeriesLayout(Runnable { show(shownPage!!) }, true) }
        }
    }
    private fun browsingEpisodes(): Boolean {
        return !stack.isEmpty() && route().screen == "Details" && "true" == route().extras["episodes"]
    }
    private fun episodePage(overview: Page): Page {
        val page = overview.copy(); page.items.clear(); page.add(Item.seriesHeader(route().title)); page.add(episodeBrowser(overview)!!)
        page.title = null; page.initialFocus = "season:" + route().season
        return page
    }
    private fun returnToSeriesOverview(): Boolean {
        if (!browsingEpisodes()) return false
        val current = route(); current.extras.remove("episodes"); current.focus = "play"; current.scroll = null
        waitingForContentFocus = true
        list.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS; list.requestFocus()
        if (current.cache is Page) transitionSeriesLayout(Runnable { show(current.cache as Page) }, false)
        else { cancelWork(); work(Callable { titles!!.overview(current) }, Consumer { page -> transitionSeriesLayout(Runnable { show(page) }, false) }) }
        return true
    }
    private fun transitionSeriesLayout(change: Runnable, entering: Boolean) {
        cancelPageEntrance()
        val mine = ++seriesTransition; val current = route()
        list.animate().cancel()
        list.animate().alpha(0f).translationY(Tv.dp(this, if (entering) -8f else 8f).toFloat()).setDuration(100).withEndAction {
            if (mine != seriesTransition || stopped || route() !== current) return@withEndAction
            change.run()
            afterPageLayout {
                if (mine != seriesTransition || stopped || route() !== current) return@afterPageLayout
                list.translationY = Tv.dp(this, if (entering) 12f else -12f).toFloat()
                list.animate().alpha(1f).translationY(0f).setDuration(220)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }.start()
    }
    override fun featured(title: Title?) { backdrop.show(title, false, false) }
    override fun searchKeyboardChanged(visible: Boolean) {
        if (stack.isEmpty() || route().screen != "Search") return
        route().extras["searchKeyboard"] = java.lang.Boolean.toString(visible)
        if (shownRoute === route() && shownPage != null) for (item in shownPage!!.items)
            if (item.kind == Item.Kind.KEYBOARD) item.keyboardVisible = visible
        val retained = retainedPages[route()]
        if (retained != null) for (item in retained.items) if (item.kind == Item.Kind.KEYBOARD) item.keyboardVisible = visible
    }

    // Workers.
    private fun <T> work(task: Callable<T>, done: Consumer<T>) {
        screenModel.work(app.workers, task) { value ->
            if (!stopped && !isFinishing) done.accept(value)
        }
    }
    /** Changes the data behind the current page and rebuilds it in place, keeping its scroll position. */
    override fun mutate(task: Callable<*>) { then(task, Runnable { invalidateLocalPages(); rememberFocus(); render() }) }
    override fun mutateEpisode(task: Callable<*>, route: Route, season: Int, episode: String) {
        val target = route
        then(task, Runnable {
            invalidateLocalPages()
            if (stack.isEmpty() || route() !== target) return@Runnable
            rememberFocus(); target.season = season; target.focus = "episode:" + episode
            render()
        })
    }
    private fun invalidateLocalPages() {
        for (saved in retainedPages.keys)
            if (saved.screen != "Catalogue" && saved.screen != "Catalogues" && saved.screen != "Browse") dirtyPages.add(saved)
    }
    override fun then(task: Callable<*>, after: Runnable) { work(Callable { task.call(); true }, Consumer { ignored -> after.run() }) }

    // Navigation used by screens.
    override fun openTitle(title: Title, video: String?) { push(Route.of("Details", title, video)) }
    override fun sources(title: Title, video: String?) { push(Route.of("Sources", title, video)) }
    override fun play(title: Title, video: String, sourceKey: String) {
        val tapped = android.os.SystemClock.elapsedRealtime()
        cancelWork()
        then(Callable { services!!.store.remember(title); null }, Runnable {
            val ready: PlaybackPreparation.Entry? = if (title.provider == "local") null else services!!.preparation.claim(title, video)
            rememberFocus(); invalidateLocalPages()
            startActivity(WatchActivity.intent(this, title, video, sourceKey)
                    .putExtra("preparation", if (ready == null) "" else ready.token).putExtra("playTapped", tapped))
        })
    }

    // Contextual panels preserve the same native drawing and Back/Left behavior.
    private fun show(alert: Tv.Alert) = prompts.show(alert)
    private fun showGlassMessage(title: String, message: CharSequence, scroll: Boolean, onClose: Runnable?) =
        prompts.message(title, message, scroll, onClose)
    private fun showGlassConfirmation(title: String, message: CharSequence, action: String, onAccept: Runnable, onCancel: Runnable?) =
        prompts.confirm(title, message, action, onAccept, onCancel)
    override fun edit(title: String, description: String, hint: String, initial: String?, accept: Consumer<String>) =
        prompts.edit(title, description, hint, initial, accept)
    override fun options(title: String, contents: Consumer<Tv.Alert>) = prompts.options(title, contents)
    override fun choose(title: String, options: Array<String>, accept: Consumer<Int>) = prompts.choose(title, options, accept)
    override fun chooseWithPreview(title: String, header: View?, options: Array<String>, selected: Int,
                                   accept: Consumer<Int>, onPreview: Consumer<Int>?) =
        prompts.chooseWithPreview(title, header, options, selected, accept, onPreview)
    override fun previewSubtitle(setting: SubtitleStyle.Setting, index: Int) {
        val view: View? = list.findViewWithTag("subtitle-preview")
        if (view is tv.stream.app.ui.SubtitlePreviewView)
            view.preview(setting, index)
    }
    override fun alert(title: String, message: String) { showGlassMessage(title, message, false, null) }
    override fun errorReport() {
        work(Callable { if (services != null && SetupScreens.developerMode(services!!)) app.lastErrorReport else null },
                Consumer { report -> if (report != null) show(ErrorQrPanel.alert(this, report)) })
    }
    override fun keyboardLanguage(language: String) {
        if (stack.isEmpty()) return
        route().extras["keyboardLanguage"] = language
        val data = services
        app.persistence.execute { data!!.store.setPreference("keyboardLanguage", language) }
    }
    override fun openWebsite(address: String) {
        try {
            AddonUrl.web(address)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
        } catch (unavailable: android.content.ActivityNotFoundException) {
            alert("Open on a phone or computer", address + "\n\nCopy the manifest address from that page, then choose Enter configured address on this TV.")
        } catch (invalid: AddonException) { alert("Unable to open setup", "This add-on supplied an invalid setup address.") }
    }
    /**
     * The starter page's Install: adds each listed add-on that isn't installed yet, reading its current manifest
     * first. One that can't be reached is skipped and named, and can be added later.
     */
    override fun installStarters(addons: List<String>) = addonFlows.installStarters(addons)
    override fun addAddon() = addonFlows.addAddon()
    override fun previewAddon(address: String) = addonFlows.previewAddon(address)
    override fun importAddress() = addonFlows.importAddress()
    @Suppress("DEPRECATION")
    override fun importFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(intent, PICK_TORRENT) }
        catch (unavailable: android.content.ActivityNotFoundException) {
            alert("Open a torrent file", "No file picker on this TV. Share a .torrent to " + getString(R.string.app_name) + " from a file manager, or add a magnet link.")
        }
    }
    private fun importFile(uri: Uri) = addonFlows.importFile(uri)
    override fun notices() = addonFlows.notices()

    /** The add-on and import journeys, run against this screen. */
    private val addonFlows = AddonFlows(object : AddonFlows.Host {
        override val services: TvApplication.Services get() = this@MainActivity.services!!
        override val generation: Int get() = this@MainActivity.generation
        override val scope: tv.stream.app.addon.Cancellation? get() = this@MainActivity.scope
        override val navigator: Navigator get() = this@MainActivity
        override fun <T> work(task: Callable<T>, done: Consumer<T>) = this@MainActivity.work(task, done)
        override fun shownPage(): Page? = this@MainActivity.shownPage
        override fun showPartial(page: Page) = this@MainActivity.showPartial(page)
        override fun confirm(title: String, message: CharSequence, action: String, accept: Runnable, cancel: Runnable?) =
            showGlassConfirmation(title, message, action, accept, cancel)
        override fun message(title: String, message: CharSequence, scroll: Boolean, close: Runnable?) =
            showGlassMessage(title, message, scroll, close)
        override fun render() = this@MainActivity.render()
        override fun rememberFocus() = this@MainActivity.rememberFocus()
        override fun readAsset(path: String): String = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        override fun isShowing(screen: String): Boolean = !stack.isEmpty() && route().screen == screen
    })

    // Incoming .torrent files.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); incomingHandled = false; incomingFile = incoming(intent)
        if (services != null) offerIncomingFile()
    }
    private fun offerIncomingFile() {
        if (incomingFile == null) return
        val uri: Uri = incomingFile!!; incomingFile = null; incomingHandled = true
        importFile(uri)
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == PICK_TORRENT && result == RESULT_OK && data != null && data.data != null) importFile(data.data!!)
    }

    // Lifecycle and Back.
    /** Back dismisses navigation first, then restores the overview/previous screen, Home or the launcher. */
    private fun navigateBack() {
        if (intro != null) { finish(); return }
        if (topBar.hasFocus()) { Sounds.play(Sounds.Sound.BACK); dismissNavigation(); return }
        if (returnToSeriesOverview()) { Sounds.play(Sounds.Sound.BACK); return }
        if (stack.size > 1) { Sounds.play(Sounds.Sound.BACK); cancelWork(); routes.pop(); enterDirection = -1; render(true); return }
        if (searchExpanded && !stack.isEmpty() && stack[0].screen == "Search") { Sounds.play(Sounds.Sound.BACK); closeSearch(); return }
        if (services != null && route().screen != "Home") { Sounds.play(Sounds.Sound.BACK); root("Home"); return }
        finish()
    }
    /**
     * Arrows are the viewer's navigation, for the focus ticks. One that moves nothing (a row's end, the page's side)
     * bumps, unless focus arrives a moment later (a list that lays out the next row first).
     */
    // AndroidX core restricts its inherited override; TV remote routing still uses the framework dispatch contract.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Nothing beneath the start screen is visible yet, so only Back reaches the app.
        if (intro != null && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_DOWN) leftPressed = true
            else if (event.action == KeyEvent.ACTION_UP) {
                // Android can consume the first down while leaving pointer mode on an empty viewport.
                // Replay that missing move once, matching the popup navigation's pointer/remote handoff.
                if (!leftPressed && !event.isCanceled) {
                    val handled = dispatchKeyEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_DOWN))
                    leftPressed = false
                    return handled
                }
                leftPressed = false
            }
        }
        val move = event.action == KeyEvent.ACTION_DOWN && Sounds.arrow(event.keyCode)
        if (move) lastUpNavigation = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) android.os.SystemClock.uptimeMillis() else 0
        if (move) Sounds.input()
        val before: View? = currentFocus
        if (move && event.keyCode == KeyEvent.KEYCODE_DPAD_UP && before != null
                && before.tag.toString().startsWith("season:") && returnToSeriesOverview()) return true
        if (move && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && (before === list || before == null) && services != null && !hiddenNavigation()) {
            focusNavigation()
            if (waitingForContentFocus) finishContentFocusWait()
            return true
        }
        // Search owns keyboard ↔ results and Settings owns category ↔ pane before an edge enters the rail.
        var handled: Boolean
        adapter.arrowing(move)
        handled = try {
            if (move && !topBar.hasFocus() && !stack.isEmpty() && route().screen == "Search" && adapter.searchKey(event.keyCode)) return true
            super.dispatchKeyEvent(event)
        } finally { adapter.arrowing(false) }
        if (move && !hiddenNavigation() && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && insidePage(before) && leftContentEdge(before)
                && (currentFocus === before || !insidePage(currentFocus))) {
            focusNavigation(); handled = true
        }
        if (move && event.repeatCount == 0 && before != null && currentFocus === before)
            main.postDelayed({ if (!stopped && currentFocus === before) Sounds.play(Sounds.Sound.EDGE) }, 90)
        return handled
    }

    /** Do not steal a shelf's asynchronous scroll to a previous off-screen card. */
    private fun leftContentEdge(focused: View?): Boolean {
        var child: View? = focused
        while (child != null && child !== list) {
            if (child.parent is PageAdapter.GridRow
                    && (child.parent as PageAdapter.GridRow).indexOfChild(child) > 0) return false
            if (child.parent is RecyclerView) {
                val row = child.parent as RecyclerView
                val layout = row.layoutManager
                val position = row.getChildAdapterPosition(child)
                if (layout is androidx.recyclerview.widget.GridLayoutManager) {
                    val columns = layout.spanCount
                    if (position >= 0 && position % columns != 0) return false
                } else if (layout != null && layout.canScrollHorizontally() && position != 0) return false
            }
            child = if (child.parent is View) child.parent as View else null
        }
        return true
    }

    private fun insidePage(view: View?): Boolean {
        var view = view
        while (view != null) {
            if (view === list) return true
            view = if (view.parent is View) view.parent as View else null
        }
        return false
    }

    override fun onStop() { rememberFocus(); super.onStop(); stopped = true; cancelPageEntrance(); cancelWork(); artwork.cancel(); if (services != null) services!!.preparation.leave() }
    override fun onStart() {
        super.onStart()
        Sounds.get(this).refresh(this)
        val returning = stopped; stopped = false
        if (returning) { if (services == null) initialize() else { render(true); checkNewEpisodes() } }
    }
    override fun onDestroy() {
        cancelPageEntrance()
        cancelWork()
        prompts.close()
        routes.detach()
        backdrop.release(); artwork.close(); main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); rememberFocus()
        out.putBoolean("incomingHandled", incomingHandled)
        out.putString("searchReturnTab", searchReturnTab)
        try {
            out.putString("routes", routes.save())
        } catch (ignored: Exception) { /* keep normal activity state */ }
    }

    companion object {
        private val TABS = arrayOf("Home", "Browse", "Library", "Settings")
        /** Width of the centred column that lists and forms use, in dp of the 960 dp canvas. */
        private const val COLUMN = 620
        private const val PICK_TORRENT = 21
        const val NAME_HINT = "Choose the name shown in your library."
        private const val SKELETON_DELAY_MS: Long = 160

        private fun showsLibrary(route: Route): Boolean { return route.screen == "Home" || route.screen == "Library" }
        private fun firstFocusable(view: View): View? {
            if (view.visibility != View.VISIBLE) return null
            if (view.isFocusable && view.tag is String) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) { val found = firstFocusable(view.getChildAt(i)); if (found != null) return found }
            }
            return null
        }
        /** Screens built as lists and forms (Page.narrow): their skeletons use the same centred column. */
        private fun isNarrow(screen: String): Boolean {
            return when (screen) {
                "Imports", "Add-on", "Developer", "Add-ons", "Add-on review", "Sources" -> true
                else -> false
            }
        }
        private fun hasPageSkeleton(screen: String): Boolean {
            return when (screen) {
                "Home", "Catalogue", "Add-ons", "Add-on review" -> true
                else -> false
            }
        }
        private fun episodeBrowser(page: Page): Item? {
            for (item in page.items) if (item.kind == Item.Kind.EPISODES) return item
            return null
        }
        // Incoming .torrent files.
        @Suppress("DEPRECATION")
        private fun incoming(intent: Intent): Uri? {
            try {
                val item: Any? = if (Intent.ACTION_VIEW == intent.action) intent.data
                        else if (Intent.ACTION_SEND == intent.action) intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) else null
                val uri = if (item is Uri) item else null
                return if (uri != null && "content" == uri.scheme) uri else null
            } catch (malformed: RuntimeException) { return null }
        }
    }
}
