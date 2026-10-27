package tv.stream.app;

import tv.stream.playback.SubtitleStyle;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import tv.stream.app.addon.AddonException;
import tv.stream.app.addon.AddonUrl;
import tv.stream.app.addon.Cancellation;
import tv.stream.app.addon.Title;
import tv.stream.app.ui.Artwork;
import tv.stream.app.ui.Backdrop;
import tv.stream.app.ui.Failures;
import tv.stream.app.ui.IntroView;
import tv.stream.app.ui.NavigationRail;
import tv.stream.app.ui.PageViewport;
import tv.stream.app.ui.Item;
import tv.stream.app.ui.Page;
import tv.stream.app.ui.PageAdapter;
import tv.stream.app.ui.Sounds;
import tv.stream.app.ui.Tv;

/**
 * TV navigation shell: tabs, back stack, artwork and dialogs. Screens build pages on application workers; each
 * route render owns a cancellable provider scope and a generation, so late results never reach a newer screen.
 */
public final class MainActivity extends Activity implements Navigator, PageAdapter.Listener {
    private static final String[] TABS = {"Home", "Browse", "Library", "Settings"};
    /** Width of the centred column that lists and forms use, in dp of the 960 dp canvas. */
    private static final int COLUMN = 620;
    private static final int PICK_TORRENT = 21;
    static final String NAME_HINT = "Choose the name shown in your library.";
    private TvApplication app;
    private TvApplication.Services services;
    private RecyclerView list;
    private PageAdapter adapter;
    private Artwork artwork;
    private Backdrop backdrop;
    private LinearLayout navigation;
    private NavigationRail topBar;
    private View searchButton, scrollFade, pageBack, pageTools;
    private PageAdapter.Layout pageLayout;
    @Nullable private Route shownRoute;
    @Nullable private Page shownPage;
    private final java.util.Set<Route> dirtyPages = new java.util.HashSet<>();
    private final java.util.Map<Route, Page> retainedPages = new java.util.IdentityHashMap<>();
    private final java.util.Map<String, Route> tabRoots = new java.util.HashMap<>();
    private long renderedConfiguration = -1;
    private android.widget.TextClock clock;
    private String clockFormat = "off";
    private boolean pageLoading = true;
    private boolean browseClockReady;
    private int seriesTransition;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Route> stack = new ArrayList<>();
    private final Set<Dialog> dialogs = new HashSet<>();
    private BrowseScreens browse;
    private TitleScreens titles;
    private SetupScreens setup;
    private StoreScreens store;
    private Cancellation scope;
    private Future<?> pending;
    private int generation;
    private boolean stopped, contentReady, incomingHandled;
    private boolean searchExpanded;
    private String searchReturnTab = "Home";
    @Nullable private Runnable searchDebounce;
    /** A tab was chosen: keep focus in the rail until Right enters its content. */
    private boolean waitingForContentFocus;
    private boolean leftPressed;
    /** Saved series changed while their page was loading; it rebuilds when that load finishes. */
    private boolean libraryChanged;
    @Nullable private Runnable delayedLoading;
    private long loadStarted;
    private boolean resetLoadingContent, keepLoadingContent;
    private static final long SKELETON_DELAY_MS = 160;
    @Nullable private Uri incomingFile;
    /** The start screen, over the browse shell until Home and its first artwork are ready (owner launches only). */
    @Nullable private IntroView intro;
    private long introStarted;
    private boolean introHome, introArtwork;
    /** Where the next page enters from: 1 after opening a screen, -1 after Back, 0 for a tab (it rises instead). */
    private int enterDirection;
    private boolean pageEntering;
    @Nullable private android.view.ViewTreeObserver.OnPreDrawListener pageEntrance;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = (TvApplication) getApplication();
        Sounds.get(this);
        incomingHandled = saved != null && saved.getBoolean("incomingHandled");
        incomingFile = incomingHandled ? null : incoming(getIntent());
        setContentView(R.layout.activity_browse);
        // Instrumentation finds controls as soon as they exist, so only the owner's launches show the start screen.
        if (saved == null && RuntimeOptions.installDefaults(this)) {
            intro = new IntroView(this);
            ((FrameLayout) findViewById(R.id.browse_root)).addView(intro, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            introStarted = android.os.SystemClock.uptimeMillis();
            Sounds.play(Sounds.Sound.INTRO);
            // However slow the providers are, the start screen never holds the app for long.
            main.postDelayed(this::finishIntro, 12_000);
        }
        artwork = new Artwork(new java.io.File(getCacheDir(), "artwork"));
        backdrop = new Backdrop(artwork, findViewById(R.id.backdrop), findViewById(R.id.backdrop_back), findViewById(R.id.backdrop_soft), findViewById(R.id.backdrop_scrim));
        list = findViewById(R.id.page_rows);
        pageLayout = new PageAdapter.Layout(this, RecyclerView.VERTICAL, 40);
        list.setLayoutManager(pageLayout);
        list.setItemAnimator(null);
        list.setFocusableInTouchMode(true); list.setDefaultFocusHighlightEnabled(false);
        // Off-screen shelves keep their views: scrolling back never rebuilds a row or reloads its artwork.
        list.setItemViewCacheSize(10);
        adapter = new PageAdapter(this, artwork, this);
        list.setAdapter(adapter);
        navigation = findViewById(R.id.navigation);
        topBar = findViewById(R.id.top_bar);
        scrollFade = findViewById(R.id.scroll_fade);
        searchButton = findViewById(R.id.search_button);
        pageBack = findViewById(R.id.page_back);
        pageTools = findViewById(R.id.home_tools);
        clock = findViewById(R.id.clock);
        topBar.decorate((FrameLayout) searchButton, R.drawable.ic_tv_search, "Search");
        View reveal = findViewById(R.id.navigation_reveal);
        reveal.setOnClickListener(view -> focusNavigation());
        reveal.setFocusable(false);
        View dismiss = findViewById(R.id.navigation_dismiss);
        dismiss.setOnClickListener(view -> dismissNavigation());
        dismiss.setFocusable(false);
        Tv.control(pageBack, "nav:back", this::pageBack, key -> {}, Tv.LIFT_ICON, false);
        wireUtility(pageBack);
        for (View utility : new View[]{pageBack, findViewById(R.id.home_refresh)}) Tv.hover(utility, () -> Tv.LIFT_ICON);
        searchButton.setDefaultFocusHighlightEnabled(false);
        for (String screen : TABS) navigation.addView(tab(screen));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) { updateTopBar(); }
        });
        // Parents finish recording their focused descendant after this callback returns.
        list.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> {
            // The initial focus is assigned before contentReady. Capture it when it leaves the page as well,
            // so the rail and toolbar return to that control rather than the first filter or row.
            if (contentReady && !stack.isEmpty() && shownRoute == route() && insidePage(old) && old.getTag() instanceof String)
                route().focus = adapter.persistentFocus((String) old.getTag());
            main.post(this::updateTopBar);
        });
        Tv.control(searchButton, "nav:search", () -> { search(); enterContent(); }, key -> {}, topBar::lift, false);
        Tv.hover(searchButton, topBar::lift);
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        if (saved != null) {
            searchReturnTab = saved.getString("searchReturnTab", "Home");
            restore(saved.getString("routes", ""));
        }
        if (stack.isEmpty()) stack.add(new Route("Starting"));
        if (stack.get(0).screen.equals("Search")) searchExpanded = true;
        searchReturnTab = primaryTab(searchReturnTab);
        loading();
        initialize();
    }
    private View tab(String screen) {
        FrameLayout tab = new FrameLayout(this);
        int icon = screen.equals("Home") ? R.drawable.ic_tv_home : screen.equals("Browse") ? R.drawable.ic_tv_explore
                : screen.equals("Library") ? R.drawable.ic_tv_library : R.drawable.ic_tv_settings;
        topBar.decorate(tab, icon, screen);
        tab.setId(View.generateViewId()); tab.setBackgroundResource(R.drawable.tv_tab);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 44));
        params.topMargin = Tv.dp(this, 6); tab.setLayoutParams(params);
        Tv.control(tab, "nav:" + screen, () -> { Sounds.play(Sounds.Sound.TAB); root(screen); enterContent(); }, key -> {}, topBar::lift, false);
        Tv.hover(tab, topBar::lift);
        return tab;
    }
    private void initialize() {
        work(() -> { TvApplication.Services s = app.services(); Sounds.get(this).setEnabled(SetupScreens.soundsOn(s)); return s; }, value -> {
            services = value;
            browse = new BrowseScreens(this, services); titles = new TitleScreens(this, services); setup = new SetupScreens(this, this, services); store = new StoreScreens(this, services);
            // No onboarding: the app opens on Home and sets itself up with the starter add-ons in the background.
            checkNewEpisodes();
            if (route().screen.equals("Starting") && intro != null) { introSetup(); offerIncomingFile(); return; }
            if (route().screen.equals("Starting")) root("Home"); else render();
            offerIncomingFile();
            if (RuntimeOptions.installDefaults(this)) app.workers.execute(() -> {
                if (StarterAddons.installDefaults(services) > 0) main.post(() -> { if (!stopped && !isFinishing() && !stack.isEmpty() && route().screen.equals("Home")) render(); });
            });
        });
    }

    /**
     * Fetches followed series' episode lists in the background (each at most twice a day). When one changes, Home and
     * Library rebuild from local data, keeping their provider results; one still loading rebuilds once it has finished.
     */
    private void checkNewEpisodes() {
        TvApplication.Services data = services;
        if (data == null || !RuntimeOptions.checkNewEpisodes(this)) return;
        app.background.execute(() -> {
            if (!NewEpisodes.check(data.store, data.addons, System.currentTimeMillis())) return;
            main.post(() -> {
                if (stopped || isFinishing() || services != data) return;
                for (Route saved : retainedPages.keySet()) if (showsLibrary(saved)) dirtyPages.add(saved);
                if (stack.isEmpty() || !showsLibrary(route())) return;
                if (pageLoading) libraryChanged = true; else { rememberFocus(); render(true); }
            });
        });
    }
    private static boolean showsLibrary(Route route) { return route.screen.equals("Home") || route.screen.equals("Library"); }

    // Start screen.
    /** Starter add-ons install before Home is first built, so a first launch opens on filled shelves. */
    private void introSetup() {
        Runnable home = () -> {
            if (introHome || isFinishing()) return;
            introHome = true;
            root("Home");
        };
        app.workers.execute(() -> {
            int added = StarterAddons.installDefaults(services);
            main.post(() -> {
                if (!introHome) home.run();
                else if (added > 0 && !stopped && !isFinishing() && !stack.isEmpty() && route().screen.equals("Home")) render();
            });
        });
        // Unreachable add-on servers don't hold Home back; a late install refreshes it as before.
        main.postDelayed(home, 6_000);
    }
    /** Warms the artwork Home shows first (the spotlight and the leading posters), feeding the posters to the wall. */
    private void introArtwork(Page page) {
        if (intro == null || introArtwork) return;
        introArtwork = true;
        List<String> posters = new ArrayList<>();
        String spotlight = null;
        for (Item item : page.items) for (Item.Tile tile : item.tiles) {
            if (spotlight == null && tile.media != null) spotlight = Backdrop.art(tile.media);
            if (tile.image != null && posters.size() < 27 && !posters.contains(tile.image)) posters.add(tile.image);
        }
        int total = posters.size() + (spotlight == null ? 0 : 2);
        if (total == 0) { finishIntro(); return; }
        int[] done = {0};
        Runnable arrived = () -> {
            done[0]++;
            if (done[0] == total) finishIntro();
        };
        if (spotlight != null) {
            artwork.fetch(spotlight, Artwork.BACKDROP, false, bitmap -> arrived.run());
            artwork.softened(spotlight, bitmap -> arrived.run());
        }
        for (String url : posters) artwork.fetch(url, Artwork.THUMBNAIL, false, bitmap -> { if (intro != null) intro.poster(bitmap); arrived.run(); });
        // Artwork is optional: failed downloads never answer, so the wait has an end.
        main.postDelayed(this::finishIntro, 4_000);
    }
    /** Leaves once the opening animation has played out, revealing the page already in place beneath. */
    private void finishIntro() {
        if (intro == null || intro.finishing()) return;
        long wait = IntroView.minimumMs() - (android.os.SystemClock.uptimeMillis() - introStarted);
        wait = Math.max(wait, intro.postersMs());
        if (wait > 0) { main.postDelayed(this::finishIntro, wait); return; }
        IntroView leaving = intro;
        Sounds.play(Sounds.Sound.INTRO_EXIT);
        leaving.finish(() -> {
            ((ViewGroup) leaving.getParent()).removeView(leaving);
            if (intro == leaving) {
                intro = null;
                updateClock();
            }
        });
    }

    // Routes and rendering.
    private Route route() { return stack.get(stack.size() - 1); }
    private void rememberFocus() {
        if (stack.isEmpty() || shownRoute != route() || !contentReady) return;
        Route route = route(); route.scroll = list.getLayoutManager().onSaveInstanceState();
        route.childScroll.clear(); route.childScroll.putAll(adapter.scrollPositions());
        View focus = getCurrentFocus();
        if (insidePage(focus) && focus.getTag() instanceof String) route.focus = adapter.persistentFocus((String) focus.getTag());
    }
    private void pageBack() {
        if (stack.size() > 1 || searchExpanded) { onBackPressed(); return; }
        if (!route().screen.equals("Home")) root("Home"); else finish();
    }
    @Override public void root(String screen) {
        if (screen.equals("Catalogues")) screen = "Browse";
        if (screen.equals("Search")) { search(); return; }
        if (services == null) return;
        searchExpanded = false;
        rememberFocus(); stack.clear();
        // The add-on store lives inside Settings rather than in a tab of its own.
        if (screen.equals("Add-ons")) { stack.add(new Route("Settings")); stack.add(StoreScreens.route("All")); }
        else stack.add(tabRoots.computeIfAbsent(screen, Route::new));
        if (isPrimaryTab(stack.get(0).screen)) searchReturnTab = stack.get(0).screen;
        render(true);
    }
    @Override public void search() {
        if (services == null) return;
        if (!stack.isEmpty() && stack.get(0).screen.equals("Search")) {
            searchExpanded = true; chrome(route().screen); return;
        }
        searchReturnTab = stack.isEmpty() ? "Home" : primaryTab(stack.get(0).screen);
        searchExpanded = true;
        rememberFocus(); stack.clear(); stack.add(tabRoots.computeIfAbsent("Search", Route::new)); render(true);
    }
    @Override public void searchInput(String value) {
        resetLoadingContent = true;
        for (Route saved : stack) if (saved.screen.equals("Search")) {
            saved.value = value;
        }
        if (stack.isEmpty() || !route().screen.equals("Search")) return;
        if (searchDebounce != null) main.removeCallbacks(searchDebounce);
        cancelWork();
        searchDebounce = () -> {
            searchDebounce = null;
            if (!stack.isEmpty() && route().screen.equals("Search")) render();
        };
        main.postDelayed(searchDebounce, 280);
    }
    @Override public void openImports() {
        if (services == null) return;
        if (stack.size() == 1 && stack.get(0).screen.equals("Settings")) push(new Route("Imports"));
        else { root("Settings"); push(new Route("Imports")); }
    }
    private void closeSearch() {
        if (!searchExpanded) return;
        searchExpanded = false;
        root(primaryTab(searchReturnTab));
    }
    private boolean isPrimaryTab(String screen) {
        for (String tab : TABS) if (tab.equals(screen)) return true;
        return false;
    }
    private String primaryTab(String screen) {
        if (screen.equals("Catalogues") || screen.equals("Catalogue")) return "Browse";
        if (screen.equals("Imports") || screen.equals("Add-on") || screen.equals("Developer") || screen.equals("Add-ons") || screen.equals("Add-on review")) return "Settings";
        if (screen.equals("Search")) return isPrimaryTab(searchReturnTab) ? searchReturnTab : "Home";
        return isPrimaryTab(screen) ? screen : "Home";
    }
    @Override public void push(Route next) { Sounds.play(Sounds.Sound.OPEN); rememberFocus(); stack.add(next); enterDirection = 1; render(); }
    @Override public void back() { onBackPressed(); }
    @Override public void refresh() { rememberFocus(); render(false, true); }
    @Override public void changed() { resetLoadingContent = true; render(); }
    private void cancelLoading() { if (delayedLoading != null) { main.removeCallbacks(delayedLoading); delayedLoading = null; } }
    private void cancelWork() { generation++; cancelLoading(); if (scope != null) scope.close(); if (pending != null) pending.cancel(true); }
    private void render() { render(false); }
    private void render(boolean restoreOnly) { render(restoreOnly, false); }
    private void render(boolean restoreOnly, boolean refreshData) {
        seriesTransition++;
        if (!pageEntering || shownRoute != route()) {
            list.animate().cancel(); list.setAlpha(1f); list.setTranslationX(0); list.setTranslationY(0);
        }
        if (searchDebounce != null) { main.removeCallbacks(searchDebounce); searchDebounce = null; }
        cancelWork();
        if (stopped || services == null) return;
        scope = services.addons.newRequest();
        refreshClock();
        Route route = route(); Cancellation request = scope;
        loadStarted = android.os.SystemClock.uptimeMillis();
        keepLoadingContent = shownRoute == route && shownPage != null && !resetLoadingContent;
        resetLoadingContent = false;
        tabRoots.put(stack.get(0).screen, stack.get(0));
        if (renderedConfiguration != services.addons.configuration()) {
            dirtyPages.addAll(retainedPages.keySet()); renderedConfiguration = services.addons.configuration();
        }
        if (!route.screen.equals("Details") && !route.screen.equals("Sources")) services.preparation.leave();
        if (shownRoute != route) adapter.restoreScrollPositions(route.childScroll);
        if (shownRoute != route) {
            // Hold focus before removing the clicked card. Skeletons have no controls; letting the framework
            // choose a fallback here briefly highlights Home before the page's Play button arrives.
            waitingForContentFocus = true;
            list.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            list.requestFocus();
        }
        chrome(route.screen);
        java.util.Set<Route> kept = new java.util.HashSet<>(stack); kept.addAll(tabRoots.values());
        retainedPages.keySet().retainAll(kept); dirtyPages.retainAll(kept);
        // Restoring data/focus on a tab or Back is still navigation and needs the same visible entrance.
        if (shownRoute != route) preparePageEntrance(route);
        Page retained = retainedPages.get(route);
        boolean dirty = dirtyPages.contains(route);
        if (shownRoute != route && retained != null) show(retained);
        if (restoreOnly && retained != null && !dirty && retained.reusable) return;
        boolean updating = shownRoute == route && shownPage != null;
        if (!updating) { loading(); updateTopBar(); }
        else if (route.screen.equals("Search") && !(restoreOnly && retained != null)) loading();
        int mine = generation;
        Consumer<Page> progress = partial -> main.post(() -> {
            if (mine == generation && !stopped && !isFinishing()) showPartial(partial);
        });
        work(() -> build(route, request, progress, refreshData, restoreOnly), this::show);
    }
    /** Reads the clock setting off the main thread; a page rebuilt after Settings changes picks up the new one. */
    private void refreshClock() {
        TvApplication.Services data = services;
        app.workers.execute(() -> {
            String format = SetupScreens.clockFormat(data);
            main.post(() -> { if (!isFinishing()) {
                clockFormat = format;
                updateClock();
            } });
        });
    }
    private void updateClock() {
        tv.stream.app.ui.Clock.apply(clock, clockFormat,
                intro == null && (browseClockReady || !pageLoading) && !stack.isEmpty() && !route().screen.equals("Starting"));
    }
    /** Start with the new page's first laid-out frame, not while its worker is still fetching/building it. */
    private void preparePageEntrance(Route expected) {
        cancelPageEntrance();
        int direction = enterDirection; enterDirection = 0;
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        pageEntering = true; list.setAlpha(0f);
        pageEntrance = () -> {
            if (stopped || route() != expected) { cancelPageEntrance(); return true; }
            if (adapter.getItemCount() == 0 || !hasVisiblePageBlock() || list.hasPendingAdapterUpdates()) return true;
            list.getViewTreeObserver().removeOnPreDrawListener(pageEntrance); pageEntrance = null;
            enter(direction); return true;
        };
        list.getViewTreeObserver().addOnPreDrawListener(pageEntrance);
    }
    private boolean hasVisiblePageBlock() {
        for (int i = 0; i < list.getChildCount(); i++) {
            ViewGroup block = (ViewGroup) list.getChildAt(i);
            if (block.getChildCount() > 0 && block.getChildAt(0).getVisibility() == View.VISIBLE && block.getHeight() > 0) return true;
        }
        return false;
    }
    private void cancelPageEntrance() {
        if (pageEntrance != null) { list.getViewTreeObserver().removeOnPreDrawListener(pageEntrance); pageEntrance = null; }
        pageEntering = false;
        list.animate().cancel(); list.setAlpha(1f); list.setTranslationX(0); list.setTranslationY(0);
    }
    /** A new page slides a little way in from the direction of travel and fades up. */
    private void enter(int direction) {
        list.animate().cancel();
        list.setAlpha(0f);
        list.setTranslationX(direction * Tv.dp(this, 36));
        list.setTranslationY(direction == 0 ? Tv.dp(this, 16) : 0);
        list.animate().alpha(1f).translationX(0).translationY(0).setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                .withEndAction(() -> pageEntering = false).start();
    }
    private Page build(Route route, Cancellation request, Consumer<Page> progress, boolean refreshData, boolean restoring) throws Exception {
        switch (route.screen) {
            case "Home": return browse.home(request, progress, refreshData, restoring);
            case "Search": return browse.search(route, request, progress, refreshData, restoring);
            case "Library": return browse.library();
            case "Imports": return setup.imports();
            case "Settings": return setup.settings(route);
            case "Add-on": return setup.addon(route);
            case "Add-ons": return store.store(route, request, progress);
            case "Add-on review": return store.review(route, request);
            case "Browse": case "Catalogues": return browse.catalogues();
            case "Catalogue": return browse.catalogue(route, request, progress, refreshData);
            case "Details": return titles.details(route, request, progress);
            case "Sources": return titles.sources(route, request, progress);
            case "Developer": return setup.developer();
            case "Keyboard languages": return setup.keyboardLanguages();
            default: return browse.home(request, progress, refreshData, restoring);
        }
    }
    /** Browse chrome is persistent; search keeps its editor in the content viewport. */
    private void chrome(String screen) {
        boolean tabs = !screen.equals("Starting");
        String rootScreen = stack.isEmpty() ? "Home" : stack.get(0).screen;
        searchButton.setActivated(rootScreen.equals("Search"));
        for (int i = 0; i < navigation.getChildCount(); i++)
            navigation.getChildAt(i).setActivated(!rootScreen.equals("Search") && TABS[i].equals(primaryTab(rootScreen)));
        boolean canGoBack = tabs && (stack.size() > 1 || searchExpanded
                || !isPrimaryTab(screen));
        pageBack.setVisibility(canGoBack ? View.VISIBLE : View.GONE);
        if (shownRoute != route()) pageTools.setVisibility(View.GONE);
        // Shelves fade out under the rail; with the rail hidden they run cleanly to the screen edge.
        ((PageViewport) findViewById(R.id.browse_content)).fade(hiddenNavigation() ? 0 : contentStart());
        wireRail(); updateTopBar();
    }
    /** Page composition owns visibility; scrolling and opening navigation never change the page's geometry. */
    private boolean hiddenNavigation() {
        if (stack.isEmpty()) return true;
        switch (route().screen) {
            case "Starting": case "Search": case "Details": case "Sources": return true;
            default: return false;
        }
    }
    private int contentInset() { return hiddenNavigation() ? NavigationRail.HIDDEN_CONTENT_START : NavigationRail.CONTENT_START; }
    /** Where the page's content starts, in pixels: clear of the rail, or of the screen edge when the rail is hidden. */
    public int contentStart() { return Tv.dp(this, contentInset()); }
    private void updateTopBar() {
        // A rebound keyboard can restore focus before RecyclerView has attached every holder.
        if (list.isComputingLayout()) { list.post(this::updateTopBar); return; }
        int offset = list.computeVerticalScrollOffset();
        scrollFade.setAlpha(!stack.isEmpty() && route().screen.equals("Search") ? 0f : Math.min(1f, offset / (float) Tv.dp(this, 32)));
        if (!dialogs.isEmpty()) return;
        boolean hidden = hiddenNavigation();
        NavigationRail.State state = hidden ? NavigationRail.State.HIDDEN : topBar.hasFocus()
                ? topBar.state() == NavigationRail.State.EXPANDED ? NavigationRail.State.EXPANDED : NavigationRail.State.WRAPPED
                : NavigationRail.State.WRAPPED;
        topBar.overlaysContent(hidden);
        topBar.state(state);
        if (hidden) topBar.setVisibility(View.INVISIBLE);
        findViewById(R.id.navigation_reveal).setVisibility(View.GONE);
        findViewById(R.id.navigation_dismiss).setVisibility(state == NavigationRail.State.EXPANDED ? View.VISIBLE : View.GONE);
    }
    private void focusNavigation() {
        if (hiddenNavigation()) return;
        rememberFocus();
        // Entering navigation only transfers focus. A separate Left press opens its labels.
        topBar.state(NavigationRail.State.WRAPPED);
        currentTab().requestFocus();
        updateTopBar();
    }
    private void dismissNavigation() { enterContent(); updateTopBar(); }
    /** Only destinations belong to the rail; utilities retain their own top-corner focus paths. */
    private void wireRail() {
        List<View> controls = new ArrayList<>(); controls.add(searchButton);
        for (int i = 0; i < navigation.getChildCount(); i++) controls.add(navigation.getChildAt(i));
        for (int i = 0; i < controls.size(); i++) {
            View control = controls.get(i);
            control.setNextFocusUpId(controls.get(Math.max(0, i - 1)).getId());
            control.setNextFocusDownId(controls.get(Math.min(controls.size() - 1, i + 1)).getId());
            control.setNextFocusLeftId(control.getId());
            control.setOnKeyListener((view, code, event) -> {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        topBar.state(NavigationRail.State.EXPANDED);
                        updateTopBar();
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP && view == searchButton && pageBack.isShown()) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) pageBack.requestFocus();
                    return true;
                }
                if (code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) enterContent();
                return true;
            });
        }
    }
    private void wireUtility(View control) {
        control.setNextFocusUpId(control.getId());
        control.setOnKeyListener((view, code, event) -> {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) enterContent();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (view != pageBack && pageBack.isShown()) pageBack.requestFocus(); else focusNavigation();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && view == pageBack && pageTools.isShown()) findViewById(R.id.home_refresh).requestFocus();
                return true;
            }
            return false;
        });
    }
    private void enterContent() {
        if (stack.isEmpty()) return;
        topBar.state(hiddenNavigation() ? NavigationRail.State.HIDDEN : NavigationRail.State.WRAPPED);
        View target = list.findViewWithTag(route().focus);
        if (target != null && target.isShown() && target.isFocusable()) target.requestFocus();
        else {
            View first = firstFocusable(list);
            if (first != null) first.requestFocus();
            else list.requestFocus(); // Right can collapse the rail even on an empty or still-loading page.
        }
    }
    private void showPartial(Page page) {
        cancelLoading();
        boolean skeleton = page.items.stream().anyMatch(item -> item.kind == Item.Kind.SKELETON);
        if (skeleton && keepLoadingContent) return;
        long delay = SKELETON_DELAY_MS - (android.os.SystemClock.uptimeMillis() - loadStarted);
        if (skeleton && delay > 0) {
            Page shell = page.copy(); shell.items.removeIf(item -> item.kind == Item.Kind.SKELETON);
            show(shell, true);
            int mine = generation;
            delayedLoading = () -> { delayedLoading = null; if (mine == generation && !stopped && pageLoading) show(page, true); };
            main.postDelayed(delayedLoading, delay);
        } else show(page, true);
    }
    private void show(Page page) { show(page, false); }
    /** Refresh is always icon-only, fixed beside the clock at the top right. */
    private void bindTools(Page page) {
        View tool = findViewById(R.id.home_refresh); Item.Action action = null;
        for (Item.Action candidate : page.tools) if (candidate.icon == R.drawable.ic_tv_refresh) { action = candidate; break; }
        pageTools.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action == null) { tool.setTag(null); return; }
        tool.setContentDescription(action.label);
        Runnable run = action.action;
        Tv.control(tool, action.key, () -> { animateRefresh(tool); run.run(); }, key -> {}, Tv.LIFT_ICON, false);
        wireUtility(tool);
    }
    private void animateRefresh(View tool) {
        View icon = ((ViewGroup) tool).getChildAt(0);
        icon.animate().cancel();
        icon.animate().rotationBy(360).setDuration(550)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    private void show(Page page, boolean partial) {
        if (!partial) cancelLoading();
        if (route().screen.equals("Search")) for (Item item : page.items) if (item.kind == Item.Kind.KEYBOARD)
            item.keyboardVisible = !"false".equals(route().extras.get("searchKeyboard"));
        if (route().screen.equals("Details") && episodeBrowser(page) != null) {
            for (Item item : page.items) if (item.kind == Item.Kind.DETAILS) { route().cache = page; break; }
            if (browsingEpisodes()) page = episodePage(page);
        }
        pageLoading = partial;
        if (!partial) browseClockReady = true;
        updateClock();
        boolean updating = shownRoute == route() && shownPage != null;
        Page previousPage = shownPage;
        shownRoute = route(); shownPage = page;
        if (!partial) { retainedPages.put(shownRoute, page); dirtyPages.remove(shownRoute); }
        if (!partial && libraryChanged) {
            libraryChanged = false;
            Route changed = shownRoute;
            if (showsLibrary(changed)) {
                dirtyPages.add(changed);
                main.post(() -> { if (!stopped && !stack.isEmpty() && route() == changed) { rememberFocus(); render(true); } });
            }
        }
        if (!partial && intro != null && shownRoute.screen.equals("Home")) introArtwork(page);
        contentReady = false;
        Route route = route();
        bindTools(page);
        // Titles scroll with their page, as on tvOS, rather than floating over it.
        List<Item> items = page.items;
        if (page.title != null) { items = new ArrayList<>(page.items); items.add(0, Item.pageTitle(page.title)); }
        gutters(page.narrow);
        // Home's spotlight sets the artwork itself; clearing it first would flash the plain canvas.
        boolean spotlight = !page.items.isEmpty() && page.items.get(0).kind == Item.Kind.SPOTLIGHT;
        contentTop(spotlight);
        boolean sameBackdrop = updating && previousPage != null && previousPage.blurBackdrop == page.blurBackdrop
                && java.util.Objects.equals(previousPage.backdrop == null ? null : previousPage.backdrop.cacheJson(),
                        page.backdrop == null ? null : page.backdrop.cacheJson());
        if (!spotlight && !sameBackdrop) backdrop.show(page.backdrop, true, page.blurBackdrop);
        // A partial page is visual context, not a second focus destination on the way to the real controls.
        if (partial && waitingForContentFocus) { adapter.set(items, ""); return; }
        boolean preserveNavigation = !waitingForContentFocus && !list.hasFocus()
                && (pageBack.hasFocus() || topBar.hasFocus() || pageTools.hasFocus());
        // Restore the route's focus; otherwise start on the page's first choice, unless the user is on the tabs.
        String wanted = !route.focus.isEmpty() ? route.focus : page.initialFocus != null ? page.initialFocus : "";
        if (wanted.isEmpty() && page.firstTile() != null) wanted = page.firstTile();
        if (adapter.set(items, preserveNavigation ? "" : wanted)) {
            if (!partial) finishContentFocusWait();
            contentReady = true; return;
        }
        if (partial && updating) { contentReady = true; return; }
        if (!updating || !preserveNavigation) {
            if (route.scroll != null) list.getLayoutManager().onRestoreInstanceState(route.scroll);
            else list.scrollToPosition(0);
        }
        String available = wanted;
        if (!wanted.isEmpty() && adapter.position(wanted) < 0 && pageTools.findViewWithTag(wanted) == null)
            available = page.initialFocus != null ? page.initialFocus : page.firstTile() == null ? "" : page.firstTile();
        String target = available;
        int mine = generation;
        afterPageLayout(() -> {
            if (mine != generation || stopped) return;
            finishContentFocusWait();
            if (preserveNavigation) { contentReady = true; return; }
            View found = target.isEmpty() ? null : pageTools.findViewWithTag(target);
            if (found == null && !target.isEmpty()) found = list.findViewWithTag(target);
            if (found != null) found.requestFocus();
            else if (!target.isEmpty() && adapter.position(target) >= 0) {
                list.scrollToPosition(adapter.position(target));
                afterPageLayout(() -> {
                    if (mine != generation || stopped) return;
                    View restored = list.findViewWithTag(target);
                    if (restored != null) restored.requestFocus(); else focusFirst();
                    contentReady = true;
                });
                return;
            } else focusFirst();
            contentReady = true;
        });
    }
    private void finishContentFocusWait() {
        waitingForContentFocus = false;
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }
    private void afterPageLayout(Runnable action) {
        if (!list.hasPendingAdapterUpdates() && !list.isLayoutRequested()) { list.post(action); return; }
        list.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                list.getViewTreeObserver().removeOnGlobalLayoutListener(this); action.run();
            }
        });
    }
    private void focusFirst() {
        View first = firstFocusable(list);
        if (first != null) first.requestFocus();
        else if (!navigation.hasFocus()) focusNavigation();
    }
    @Nullable private static View firstFocusable(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.isFocusable() && view.getTag() instanceof String) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { View found = firstFocusable(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    private View currentTab() {
        if (!stack.isEmpty() && stack.get(0).screen.equals("Search")) return searchButton;
        String rootScreen = stack.isEmpty() ? "Home" : primaryTab(stack.get(0).screen);
        for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(rootScreen)) return navigation.getChildAt(i);
        return navigation.getChildAt(0);
    }
    private void loading() {
        pageLoading = true;
        updateClock();
        contentReady = false;
        if (!stack.isEmpty()) {
            Route current = route();
            if (current.screen.equals("Sources") && current.title != null) {
                Page shell = titles.sourcesPage(current);
                showPartial(shell.add(Item.skeleton("Source results"))); return;
            }
            if (current.screen.equals("Details") && current.title != null) {
                Page shell = titles.detailsPage(current.title, "");
                TitleScreens.pendingTitle(shell, current.title);
                showPartial(shell); return;
            }
            if (current.screen.equals("Search") && browse != null) {
                Item search = browse.searchLoading(current);
                String focus = current.focus.isEmpty() ? "keyboard:a" : current.focus;
                // Search's own gutter and top before its pane is bound: the previous page's include the rail's inset.
                gutters(false); contentTop(false);
                // Search uses the plain canvas: clear the previous page's artwork alongside the entrance, not after it.
                if (shownRoute != current) backdrop.show(null, true, false);
                // Typing updates the results beside the keyboard; the focused key stays focused and keeps being remembered.
                if (adapter.set(java.util.Collections.singletonList(search), focus)) contentReady = true;
                return;
            }
            if (hasPageSkeleton(current.screen)) {
                // The skeleton takes the place its page will have, so nothing jumps when the page arrives.
                gutters(isNarrow(current.screen));
                contentTop(current.screen.equals("Home"));
                adapter.set(java.util.Collections.emptyList(), "");
                int mine = generation;
                delayedLoading = () -> { delayedLoading = null; if (mine == generation && !stopped && pageLoading)
                    adapter.set(java.util.Collections.singletonList(Item.skeleton(current.screen)), ""); };
                main.postDelayed(delayedLoading, SKELETON_DELAY_MS);
                return;
            }
        }
        // Local lists/forms usually finish within a frame; a small delayed spinner covers unusually slow storage.
        adapter.set(java.util.Collections.emptyList(), "");
        int mine = generation;
        delayedLoading = () -> { delayedLoading = null; if (mine == generation && !stopped && pageLoading)
            adapter.set(java.util.Collections.singletonList(Item.loading()), ""); };
        main.postDelayed(delayedLoading, SKELETON_DELAY_MS);
    }
    /** Screens built as lists and forms (Page.narrow): their skeletons use the same centred column. */
    private static boolean isNarrow(String screen) {
        switch (screen) {
            case "Imports": case "Add-on": case "Developer": case "Add-ons": case "Add-on review": case "Sources": return true;
            default: return false;
        }
    }
    /** Reserve the top toolbar; Home artwork uses the full height. */
    private void contentTop(boolean spotlight) {
        list.setPadding(0, Tv.dp(this, spotlight ? 0 : 76), 0, list.getPaddingBottom());
    }
    private void gutters(boolean narrow) {
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density - contentInset();
        int side = narrow ? Math.max(24, Math.round((widthDp - COLUMN) / 2)) : 24;
        adapter.gutter(contentInset() + side, narrow ? side : 40);
    }
    private static boolean hasPageSkeleton(String screen) {
        switch (screen) {
            case "Home": case "Catalogue": case "Add-ons": case "Add-on review":
                return true;
            default: return false;
        }
    }
    private void failed(Throwable e) {
        int mine = generation;
        app.workers.execute(() -> {
            boolean enabled = false;
            try { enabled = services != null && SetupScreens.developerMode(services); }
            catch (RuntimeException unavailable) { /* No verified preference: keep diagnostics hidden. */ }
            boolean developer = enabled;
            main.post(() -> { if (mine == generation && !stopped && !isFinishing()) showFailure(e, developer); });
        });
    }
    private void showFailure(Throwable e, boolean developer) {
        finishIntro();
        Sounds.play(Sounds.Sound.WARNING);
        Item.Action reportAction = null;
        if (developer) {
            app.lastErrorReport = DiagnosticReport.capture(this, route().screen, "Unable to load", Failures.message(e), "application", e, null);
            reportAction = new Item.Action("error-report", Item.Action.Style.SECONDARY, "Error report", this::errorReport);
        }
        if (shownRoute == route() && shownPage != null) {
            Page retained = shownPage.copy();
            retained.items.removeIf(item -> item.kind == Item.Kind.SKELETON || item.key.equals("notice:Unable to update") || item.key.equals("notice:Loading add-on details"));
            Item.Action retry = new Item.Action("retry-update", Item.Action.Style.SECONDARY, "Retry", this::refresh);
            retained.add(Item.notice(R.drawable.ic_tv_offline, "Unable to update", Failures.message(e),
                    reportAction == null ? new Item.Action[]{retry} : new Item.Action[]{retry, reportAction}));
            retained.reusable = false;
            show(retained); return;
        }
        Item.Action retry = new Item.Action("retry", Item.Action.Style.PRIMARY, "Retry", () -> { if (services == null) { loading(); initialize(); } else render(); }).icon(R.drawable.ic_tv_refresh);
        Page page = new Page().add(Item.message(e instanceof AddonException && ((AddonException) e).kind == AddonException.Kind.CERTIFICATE ? R.drawable.ic_tv_info : R.drawable.ic_tv_offline,
                "Unable to load", Failures.message(e),
                reportAction == null ? new Item.Action[]{retry} : new Item.Action[]{retry, reportAction}));
        page.initialFocus = "retry";
        page.reusable = false;
        show(page);
    }

    // Page listener.
    @Override public void focused(String key) {
        if (!contentReady || stack.isEmpty()) return;
        Route current = route(); current.focus = key;
        if (current.screen.equals("Details") && (key.startsWith("season:") || key.startsWith("episode:"))
                && !browsingEpisodes() && shownPage != null && episodeBrowser(shownPage) != null) {
            current.extras.put("episodes", "true"); current.scroll = null;
            // Focus settles first; the same attached season strip survives the surrounding layout change.
            main.post(() -> { if (!stopped && route() == current && browsingEpisodes())
                transitionSeriesLayout(() -> show(shownPage), true); });
        }
    }
    @Nullable private static Item episodeBrowser(Page page) {
        for (Item item : page.items) if (item.kind == Item.Kind.EPISODES) return item;
        return null;
    }
    private boolean browsingEpisodes() {
        return !stack.isEmpty() && route().screen.equals("Details") && "true".equals(route().extras.get("episodes"));
    }
    private Page episodePage(Page overview) {
        Page page = overview.copy(); page.items.clear(); page.add(Item.seriesHeader(route().title)); page.add(episodeBrowser(overview));
        page.title = null; page.initialFocus = "season:" + route().season;
        return page;
    }
    private boolean returnToSeriesOverview() {
        if (!browsingEpisodes()) return false;
        Route current = route(); current.extras.remove("episodes"); current.focus = "play"; current.scroll = null;
        waitingForContentFocus = true;
        list.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS); list.requestFocus();
        if (current.cache instanceof Page) transitionSeriesLayout(() -> show((Page) current.cache), false);
        else { cancelWork(); work(() -> titles.overview(current), page -> transitionSeriesLayout(() -> show(page), false)); }
        return true;
    }
    private void transitionSeriesLayout(Runnable change, boolean entering) {
        cancelPageEntrance();
        int mine = ++seriesTransition; Route current = route();
        list.animate().cancel();
        list.animate().alpha(0f).translationY(Tv.dp(this, entering ? -8 : 8)).setDuration(100).withEndAction(() -> {
            if (mine != seriesTransition || stopped || route() != current) return;
            change.run();
            afterPageLayout(() -> {
                if (mine != seriesTransition || stopped || route() != current) return;
                list.setTranslationY(Tv.dp(this, entering ? 12 : -12));
                list.animate().alpha(1f).translationY(0).setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            });
        }).start();
    }
    @Override public void featured(@Nullable Title title) { backdrop.show(title, false, false); }
    @Override public void searchKeyboardChanged(boolean visible) {
        if (stack.isEmpty() || !route().screen.equals("Search")) return;
        route().extras.put("searchKeyboard", Boolean.toString(visible));
        if (shownRoute == route() && shownPage != null) for (Item item : shownPage.items)
            if (item.kind == Item.Kind.KEYBOARD) item.keyboardVisible = visible;
        Page retained = retainedPages.get(route());
        if (retained != null) for (Item item : retained.items) if (item.kind == Item.Kind.KEYBOARD) item.keyboardVisible = visible;
    }

    // Workers.
    private <T> void work(Callable<T> task, Consumer<T> done) {
        int mine = generation;
        pending = app.workers.submit(() -> {
            try { T value = task.call(); main.post(() -> { if (mine == generation && !stopped && !isFinishing()) done.accept(value); }); }
            catch (Exception e) { main.post(() -> { if (mine == generation && !stopped && !isFinishing()) failed(e); }); }
        });
    }
    /** Changes the data behind the current page and rebuilds it in place, keeping its scroll position. */
    @Override public void mutate(Callable<?> task) { then(task, () -> { invalidateLocalPages(); rememberFocus(); render(); }); }
    private void invalidateLocalPages() {
        for (Route saved : retainedPages.keySet())
            if (!saved.screen.equals("Catalogue") && !saved.screen.equals("Catalogues") && !saved.screen.equals("Browse")) dirtyPages.add(saved);
    }
    @Override public void then(Callable<?> task, Runnable after) { work(() -> { task.call(); return true; }, ignored -> after.run()); }

    // Navigation used by screens.
    @Override public void openTitle(Title title, String video) { push(Route.of("Details", title, video)); }
    @Override public void sources(Title title, String video) { push(Route.of("Sources", title, video)); }
    @Override public void play(Title title, String video, String sourceKey) {
        long tapped = android.os.SystemClock.elapsedRealtime();
        cancelWork();
        then(() -> { services.store.remember(title); return null; }, () -> {
            PlaybackPreparation.Entry ready = title.provider.equals("local") ? null : services.preparation.claim(title, video);
            rememberFocus(); invalidateLocalPages();
            startActivity(WatchActivity.intent(this, title, video, sourceKey)
                    .putExtra("preparation", ready == null ? "" : ready.token).putExtra("playTapped", tapped));
        });
    }

    // Contextual panels share one navigation model: Back/Left returns a level or dismisses the root.
    private void show(Tv.Alert alert) {
        Dialog dialog = alert.dialog;
        dialogs.add(dialog); dialog.setOnDismissListener(ignored -> { dialogs.remove(dialog); main.post(this::updateTopBar); });
        alert.show();
    }
    /** Long text (licences) scrolls inside the alert, left-aligned for reading. */
    private void showGlassMessage(String title, CharSequence message, boolean scroll, Runnable onClose) {
        Tv.Alert alert = Tv.alert(this, title, scroll ? null : message);
        if (scroll) {
            ScrollView content = new ScrollView(this);
            content.setVerticalScrollBarEnabled(false); content.setFocusable(true);
            TextView body = Tv.text(this, message.toString(), 13.5f, R.color.tv_text_secondary);
            body.setLineSpacing(Tv.dp(this, 3), 1f);
            content.addView(body);
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 300));
            contentParams.topMargin = Tv.dp(this, 16);
            alert.surface.addView(Tv.clipped(content, 0), alert.surface.indexOfChild(alert.buttons), contentParams);
        }
        alert.dialog.setOnCancelListener(ignored -> { if (onClose != null) onClose.run(); });
        show(alert);
    }
    private void showGlassConfirmation(String title, CharSequence message, String action, Runnable onAccept, Runnable onCancel) {
        Tv.Alert alert = Tv.alert(this, title, message);
        alert.button("popup:confirm", action, () -> { alert.dialog.dismiss(); onAccept.run(); });
        alert.dialog.setOnCancelListener(ignored -> { if (onCancel != null) onCancel.run(); });
        show(alert);
    }
    @Override public void edit(String title, String description, String hint, String initial, Consumer<String> accept) {
        Tv.Alert alert = Tv.alert(this, title, description);
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint(hint); input.setText(initial); input.setTextSize(17);
        input.setTextColor(getColor(R.color.tv_text)); input.setHintTextColor(getColor(R.color.tv_text_tertiary));
        input.setSelectAllOnFocus(true); input.setContentDescription(title); input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackgroundResource(R.drawable.tv_dialog_field); input.setPadding(Tv.dp(this, 16), 0, Tv.dp(this, 16), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 50));
        inputParams.topMargin = Tv.dp(this, 20);
        alert.surface.addView(input, alert.surface.indexOfChild(alert.buttons), inputParams);
        TextView error = Tv.text(this, "", 13.5f, R.color.tv_warning);
        error.setVisibility(View.GONE); error.setGravity(Gravity.START); error.setPadding(Tv.dp(this, 12), Tv.dp(this, 8), Tv.dp(this, 12), 0);
        alert.surface.addView(error, alert.surface.indexOfChild(alert.buttons));
        View proceed = alert.button("popup:continue", "Continue", () -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) { error.setText("Enter a value"); error.setVisibility(View.VISIBLE); input.requestFocus(); return; }
            error.setVisibility(View.GONE); alert.dialog.dismiss(); accept.accept(text);
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            proceed.performClick(); return true;
        });
        show(alert);
        input.requestFocus();
    }
    @Override public void options(String title, Consumer<Tv.Alert> contents) {
        Tv.Alert menu = Tv.alert(this, title, (View) null); contents.accept(menu); show(menu);
    }
    @Override public void choose(String title, String[] options, Consumer<Integer> accept) { show(Tv.choices(this, title, options, accept, null)); }
    @Override public void chooseWithPreview(String title, @Nullable View header, String[] options, int selected,
                                           Consumer<Integer> accept, @Nullable Consumer<Integer> onPreview) {
        boolean[] accepted = {false};
        Tv.Alert alert = Tv.choices(this, title, header, options, selected, index -> {
            accepted[0] = true; accept.accept(index);
        }, onPreview, null);
        show(alert);
        alert.dialog.setOnDismissListener(dialog -> {
            dialogs.remove(dialog);
            main.post(this::updateTopBar);
            if (!accepted[0] && header == null) {
                View view = list.findViewWithTag("subtitle-preview");
                if (view instanceof tv.stream.app.ui.SubtitlePreviewView)
                    ((tv.stream.app.ui.SubtitlePreviewView) view).updateFrom(services.playerPreferences);
            }
        });
    }
    @Override public void previewSubtitle(SubtitleStyle.Setting setting, int index) {
        View view = list.findViewWithTag("subtitle-preview");
        if (view instanceof tv.stream.app.ui.SubtitlePreviewView)
            ((tv.stream.app.ui.SubtitlePreviewView) view).preview(setting, index);
    }
    @Override public void alert(String title, String message) { showGlassMessage(title, message, false, null); }
    @Override public void errorReport() {
        work(() -> services != null && SetupScreens.developerMode(services) ? app.lastErrorReport : null,
                report -> { if (report != null) show(ErrorQrPanel.alert(this, report)); });
    }
    @Override public void keyboardLanguage(String language) {
        if (stack.isEmpty()) return;
        route().extras.put("keyboardLanguage", language);
        TvApplication.Services data = services;
        app.persistence.execute(() -> data.store.setPreference("keyboardLanguage", language));
    }
    @Override public void openWebsite(String address) {
        try {
            AddonUrl.web(address);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (android.content.ActivityNotFoundException unavailable) {
            alert("Open on a phone or computer", address + "\n\nCopy the manifest address from that page, then choose Enter configured address on this TV.");
        } catch (AddonException invalid) { alert("Unable to open setup", "This add-on supplied an invalid setup address."); }
    }
    /**
     * The starter page's Install: adds each listed add-on that isn't installed yet, reading its current manifest
     * first. One that can't be reached is skipped and named, and can be added later.
     */
    @Override public void installStarters(List<String> addons) {
        if (shownPage != null) {
            Page partial = shownPage.copy(); partial.items.removeIf(item -> item.key.equals("starter-actions"));
            showPartial(partial.add(Item.notice(R.drawable.ic_tv_refresh, "Installing add-ons", "")));
        }
        work(() -> {
            List<String> missed = new ArrayList<>();
            for (String url : addons) {
                if (StoreScreens.installed(url, "", services.store.addons()) != null) continue;
                // Installing cancels open requests, so each add-on is read with a request of its own.
                try (Cancellation request = services.addons.newRequest()) { services.addons.install(url, services.addons.preview(url, request)); }
                catch (AddonException e) { missed.add(StarterAddons.describe(url)[0]); }
            }
            return missed;
        }, missed -> {
            Sounds.play(missed.isEmpty() ? Sounds.Sound.INSTALLED : Sounds.Sound.WARNING);
            root("Home");
            if (!missed.isEmpty()) alert("Some add-ons weren't added", String.join(", ", missed) + " didn't respond. Try again from the add-on store.");
        });
    }
    @Override public void addAddon() {
        edit("Add add-on", "Connect a catalogue or source using its manifest address.", "https://… or stremio://…/manifest.json", "", this::previewAddon);
    }
    @Override public void previewAddon(String input) {
        String address = AddonUrl.normalize(input);
        if (shownPage != null) showPartial(shownPage.copy().add(Item.notice(R.drawable.ic_tv_info,
                "Loading add-on details", "")));
        int mine = generation;
        Cancellation request = scope;
        work(() -> services.addons.preview(address, request), manifest -> {
            if (shownPage != null) {
                Page restored = shownPage.copy(); restored.items.removeIf(item -> item.key.equals("notice:Loading add-on details"));
                showPartial(restored);
            }
            if (manifest.configurationRequired) {
                Route review = new Route("Add-on review"); review.value = address; push(review); return;
            }
            String supplies = StoreScreens.supplies(manifest);
            boolean torrents = manifest.p2p || tv.stream.app.addon.TorrentAddons.find(address) != null;
            String details = (manifest.description.isEmpty() ? "" : manifest.description + "\n\n") + supplies
                    + (torrents ? "\nTorrent sources play directly on this TV and share data with peers." : "")
                    + "\n\n" + manifest.catalogs.size() + " catalogues. The provider receives your searches and the titles you open.";
            showGlassConfirmation("Install " + manifest.name + "?", details, "Install", () -> then(() -> {
                        services.addons.install(address, manifest); return null;
                    }, () -> { Sounds.play(Sounds.Sound.INSTALLED); rememberFocus(); render(); }), () -> { if (mine == generation) render(); });
        });
    }
    @Override public void importAddress() {
        edit("Import torrent", "Paste a magnet link or a torrent URL.", "magnet:?xt=… or https://…torrent", "", address ->
                edit("Name this import", NAME_HINT, "Title", "Imported torrent", name -> mutate(() -> services.imports.addAddress(name, address))));
    }
    @Override public void importFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, PICK_TORRENT); }
        catch (android.content.ActivityNotFoundException unavailable) {
            alert("Open a torrent file", "No file picker on this TV. Share a .torrent to " + getString(R.string.app_name) + " from a file manager, or add a magnet link.");
        }
    }
    private void importFile(Uri uri) {
        edit("Name this import", NAME_HINT, "Title", "Imported torrent", name -> then(() -> services.imports.addFile(name, uri, scope), () -> {
            if (!stack.isEmpty() && route().screen.equals("Imports")) render(); else openImports();
        }));
    }
    @Override public void notices() {
        String[] files = {"androidx.txt", "libtorrent.txt", "boost.txt", "openssl.txt", "ed25519.txt", "try-signal.txt", "geist.txt", "geist-icons.txt", "zxing.txt", "material-icons.txt", "noto-sans.txt", "atkinson-hyperlegible.txt"};
        choose("Open source notices", new String[]{"AndroidX / Media3", "libtorrent", "Boost", "OpenSSL", "Ed25519", "try_signal", "Geist Sans", "Geist UI icons", "ZXing", "Material Icons", "Noto Sans", "Atkinson Hyperlegible Next"}, which -> work(() -> {
            try (java.io.InputStream input = getAssets().open("notices/" + files[which]); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
                return bytes.toString("UTF-8");
            }
        }, text -> {
            showGlassMessage("Open source notices", text, true, () -> render());
        }));
    }

    // Incoming .torrent files.
    @Nullable private static Uri incoming(Intent intent) {
        try {
            Object item = Intent.ACTION_VIEW.equals(intent.getAction()) ? intent.getData()
                    : Intent.ACTION_SEND.equals(intent.getAction()) ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : null;
            Uri uri = item instanceof Uri ? (Uri) item : null;
            return uri != null && "content".equals(uri.getScheme()) ? uri : null;
        } catch (RuntimeException malformed) { return null; }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); incomingHandled = false; incomingFile = incoming(intent);
        if (services != null) offerIncomingFile();
    }
    private void offerIncomingFile() {
        if (incomingFile == null) return;
        Uri uri = incomingFile; incomingFile = null; incomingHandled = true;
        importFile(uri);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_TORRENT && result == RESULT_OK && data != null && data.getData() != null) importFile(data.getData());
    }

    // Lifecycle and Back.
    /** Back dismisses navigation first, then restores the overview/previous screen, Home or the launcher. */
    @Override public void onBackPressed() {
        if (intro != null) { finish(); return; }
        if (topBar.hasFocus()) { Sounds.play(Sounds.Sound.BACK); dismissNavigation(); return; }
        if (returnToSeriesOverview()) { Sounds.play(Sounds.Sound.BACK); return; }
        if (stack.size() > 1) { Sounds.play(Sounds.Sound.BACK); cancelWork(); stack.remove(stack.size() - 1); enterDirection = -1; render(true); return; }
        if (searchExpanded && !stack.isEmpty() && stack.get(0).screen.equals("Search")) { Sounds.play(Sounds.Sound.BACK); closeSearch(); return; }
        if (services != null && !route().screen.equals("Home")) { Sounds.play(Sounds.Sound.BACK); root("Home"); return; }
        finish();
    }
    /**
     * Arrows are the viewer's navigation, for the focus ticks. One that moves nothing (a row's end, the page's side)
     * bumps, unless focus arrives a moment later (a list that lays out the next row first).
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // Nothing beneath the start screen is visible yet, so only Back reaches the app.
        if (intro != null && event.getKeyCode() != KeyEvent.KEYCODE_BACK) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) leftPressed = true;
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                // Android can consume the first down while leaving pointer mode on an empty viewport.
                // Replay that missing move once, matching the popup navigation's pointer/remote handoff.
                if (!leftPressed && !event.isCanceled()) {
                    boolean handled = dispatchKeyEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_DOWN));
                    leftPressed = false;
                    return handled;
                }
                leftPressed = false;
            }
        }
        boolean move = event.getAction() == KeyEvent.ACTION_DOWN && Sounds.arrow(event.getKeyCode());
        if (move) Sounds.input();
        View before = getCurrentFocus();
        if (move && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && before != null
                && String.valueOf(before.getTag()).startsWith("season:") && returnToSeriesOverview()) return true;
        if (move && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && (before == list || before == null) && services != null && !hiddenNavigation()) {
            focusNavigation();
            if (waitingForContentFocus) finishContentFocusWait();
            return true;
        }
        // Search owns keyboard ↔ results and Settings owns category ↔ pane before an edge enters the rail.
        boolean handled;
        adapter.arrowing(move);
        try {
            if (move && !topBar.hasFocus() && !stack.isEmpty() && route().screen.equals("Search") && adapter.searchKey(event.getKeyCode())) return true;
            handled = super.dispatchKeyEvent(event);
        } finally { adapter.arrowing(false); }
        if (move && !hiddenNavigation() && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && insidePage(before) && leftContentEdge(before)
                && (getCurrentFocus() == before || !insidePage(getCurrentFocus()))) {
            focusNavigation(); handled = true;
        }
        if (move && event.getRepeatCount() == 0 && before != null && getCurrentFocus() == before)
            main.postDelayed(() -> { if (!stopped && getCurrentFocus() == before) Sounds.play(Sounds.Sound.EDGE); }, 90);
        return handled;
    }

    /** Do not steal a shelf's asynchronous scroll to a previous off-screen card. */
    private boolean leftContentEdge(View focused) {
        for (View child = focused; child != null && child != list; ) {
            if (child.getParent() instanceof PageAdapter.GridRow
                    && ((PageAdapter.GridRow) child.getParent()).indexOfChild(child) > 0) return false;
            if (child.getParent() instanceof RecyclerView) {
                RecyclerView row = (RecyclerView) child.getParent();
                RecyclerView.LayoutManager layout = row.getLayoutManager();
                int position = row.getChildAdapterPosition(child);
                if (layout instanceof androidx.recyclerview.widget.GridLayoutManager) {
                    int columns = ((androidx.recyclerview.widget.GridLayoutManager) layout).getSpanCount();
                    if (position >= 0 && position % columns != 0) return false;
                } else if (layout != null && layout.canScrollHorizontally() && position != 0) return false;
            }
            child = child.getParent() instanceof View ? (View) child.getParent() : null;
        }
        return true;
    }

    private boolean insidePage(@Nullable View view) {
        while (view != null) {
            if (view == list) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    @Override protected void onStop() { rememberFocus(); super.onStop(); stopped = true; cancelPageEntrance(); cancelWork(); artwork.cancel(); if (services != null) services.preparation.leave(); }
    @Override protected void onStart() {
        super.onStart();
        Sounds.get(this).refresh(this);
        boolean returning = stopped; stopped = false;
        if (returning) { if (services == null) initialize(); else { render(true); checkNewEpisodes(); } }
    }
    @Override protected void onDestroy() {
        cancelPageEntrance();
        cancelWork();
        for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        backdrop.release(); artwork.close(); main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); rememberFocus();
        out.putBoolean("incomingHandled", incomingHandled);
        out.putString("searchReturnTab", searchReturnTab);
        try {
            JSONArray routes = new JSONArray();
            for (Route r : stack) routes.put(r.save());
            out.putString("routes", routes.toString());
        } catch (Exception ignored) { /* keep normal activity state */ }
    }
    private void restore(String text) {
        try {
            JSONArray saved = new JSONArray(text);
            for (int i = 0; i < saved.length(); i++) stack.add(Route.restore(saved.getJSONObject(i)));
        } catch (Exception ignored) { stack.clear(); }
    }
}
