package tv.daniil.stream;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
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
import tv.daniil.stream.addon.AddonException;
import tv.daniil.stream.addon.AddonUrl;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.addon.Title;
import tv.daniil.stream.ui.Artwork;
import tv.daniil.stream.ui.Backdrop;
import tv.daniil.stream.ui.Failures;
import tv.daniil.stream.ui.IntroView;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Page;
import tv.daniil.stream.ui.PageAdapter;
import tv.daniil.stream.ui.Sounds;
import tv.daniil.stream.ui.Tv;

/**
 * TV navigation shell: tabs, back stack, artwork and dialogs. Screens build pages on application workers; each
 * route render owns a cancellable provider scope and a generation, so late results never reach a newer screen.
 */
public final class MainActivity extends Activity implements Navigator, PageAdapter.Listener {
    private static final String[] TABS = {"Home", "Library", "Settings"};
    /** Width of the centred column that lists and forms use, in dp of the 960 dp canvas. */
    private static final int COLUMN = 620;
    private static final int PICK_TORRENT = 21;
    private TvApplication app;
    private TvApplication.Services services;
    private RecyclerView list;
    private PageAdapter adapter;
    private Artwork artwork;
    private Backdrop backdrop;
    private LinearLayout navigation;
    private View topBar, topBarControls, searchButton, searchExpandedView, searchClose, scrollFade, pageBack, pageTools;
    private PageAdapter.Layout pageLayout;
    private boolean topBarHidden;
    @Nullable private Route shownRoute;
    @Nullable private Page shownPage;
    private final java.util.Map<Route, Page> retainedPages = new java.util.IdentityHashMap<>();
    private TextView searchQuery;
    private android.widget.TextClock clock;
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
    /** A tab was chosen: leave focus on the tab bar rather than jumping into the new page. */
    private boolean stayOnTabs;
    private boolean waitingForContentFocus;
    @Nullable private Uri incomingFile;
    /** The start screen, over the browse shell until Home and its first artwork are ready (owner launches only). */
    @Nullable private IntroView intro;
    private long introStarted;
    private boolean introHome, introArtwork;
    /** Where the next page enters from: 1 after opening a screen, -1 after Back, 0 for a tab (it rises instead). */
    private int enterDirection;

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
        topBarControls = findViewById(R.id.top_bar_controls);
        searchButton = findViewById(R.id.search_button);
        searchExpandedView = findViewById(R.id.search_expanded);
        searchQuery = findViewById(R.id.search_query);
        searchClose = findViewById(R.id.search_close);
        pageBack = findViewById(R.id.page_back);
        pageTools = findViewById(R.id.home_tools);
        clock = findViewById(R.id.clock);
        pageBack.setTooltipText("Back");
        Tv.control(pageBack, "nav:back", this::back, key -> {}, Tv.LIFT_ICON, false);
        pageBack.setNextFocusLeftId(R.id.page_back); pageBack.setNextFocusUpId(R.id.page_back);
        pageBack.setOnKeyListener((view, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                View target = stack.isEmpty() ? null : list.findViewWithTag(route().focus);
                if (target != null) target.requestFocus(); else focusFirst();
            }
            return true;
        });
        searchButton.setDefaultFocusHighlightEnabled(false);
        searchClose.setDefaultFocusHighlightEnabled(false);
        for (String screen : TABS) navigation.addView(tab(screen));
        // The tab bar's ends hold focus rather than dropping into whatever content lies beyond them.
        View lastTab = navigation.getChildAt(TABS.length - 1);
        lastTab.setNextFocusRightId(lastTab.getId());
        searchButton.setNextFocusLeftId(R.id.search_button);
        // tvOS: the tab bar slides away while content scrolls and returns when focus reaches it or the page is at its top.
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) { updateTopBar(); }
        });
        list.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> updateTopBar());
        Tv.control(searchButton, "nav:search", this::search, key -> {}, Tv.LIFT_ICON, false);
        Tv.control(searchClose, "search:close", this::closeSearch, key -> {}, Tv.LIFT_ICON, false);
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
    private TextView tab(String screen) {
        TextView tab = Tv.heading(this, screen, 15.5f, R.color.tv_tab_text);
        tab.setId(View.generateViewId());
        tab.setGravity(Gravity.CENTER); tab.setBackgroundResource(R.drawable.tv_tab); tab.setContentDescription(screen);
        tab.setPadding(Tv.dp(this, 17), 0, Tv.dp(this, 17), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 38));
        params.setMarginStart(Tv.dp(this, 2)); params.setMarginEnd(Tv.dp(this, 2));
        tab.setLayoutParams(params);
        Tv.control(tab, "nav:" + screen, () -> { Sounds.play(Sounds.Sound.TAB); stayOnTabs = true; root(screen); }, key -> {}, Tv.LIFT_BUTTON, false);
        return tab;
    }
    private void initialize() {
        work(() -> { TvApplication.Services s = app.services(); Sounds.get(this).setEnabled(SetupScreens.soundsOn(s)); return s; }, value -> {
            services = value;
            browse = new BrowseScreens(this, services); titles = new TitleScreens(this, services); setup = new SetupScreens(this, this, services); store = new StoreScreens(this, services);
            // No onboarding: the app opens on Home and sets itself up with the starter add-ons in the background.
            if (route().screen.equals("Starting") && intro != null) { introSetup(); offerIncomingFile(); return; }
            if (route().screen.equals("Starting")) root("Home"); else render();
            offerIncomingFile();
            if (RuntimeOptions.installDefaults(this)) app.workers.execute(() -> {
                if (StarterAddons.installDefaults(services) > 0) main.post(() -> { if (!stopped && !isFinishing() && !stack.isEmpty() && route().screen.equals("Home")) render(); });
            });
        });
    }

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
            if (intro == leaving) intro = null;
        });
    }

    // Routes and rendering.
    private Route route() { return stack.get(stack.size() - 1); }
    private void rememberFocus() { if (!stack.isEmpty()) route().scroll = list.getLayoutManager().onSaveInstanceState(); }
    @Override public void root(String screen) {
        if (screen.equals("Search")) { search(); return; }
        if (services == null) return;
        searchExpanded = false;
        rememberFocus(); stack.clear();
        // The add-on store lives inside Settings rather than in a tab of its own.
        if (screen.equals("Add-ons")) { stack.add(new Route("Settings")); stack.add(StoreScreens.route("All")); }
        else stack.add(new Route(screen));
        if (isPrimaryTab(stack.get(0).screen)) searchReturnTab = stack.get(0).screen;
        render();
    }
    @Override public void search() {
        if (services == null) return;
        if (!stack.isEmpty() && stack.get(0).screen.equals("Search")) {
            searchExpanded = true; syncSearchQuery(); chrome(route().screen); return;
        }
        searchReturnTab = stack.isEmpty() ? "Home" : primaryTab(stack.get(0).screen);
        searchExpanded = true;
        rememberFocus(); stack.clear(); stack.add(new Route("Search")); render();
    }
    @Override public void searchInput(String value) {
        for (Route saved : stack) if (saved.screen.equals("Search")) {
            saved.value = value;
        }
        syncSearchQuery();
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
        if (screen.equals("History")) return "Library";
        if (screen.equals("Imports") || screen.equals("Add-on") || screen.equals("Developer") || screen.equals("Add-ons") || screen.equals("Add-on review")) return "Settings";
        if (screen.equals("Search")) return isPrimaryTab(searchReturnTab) ? searchReturnTab : "Home";
        return isPrimaryTab(screen) ? screen : "Home";
    }
    private void syncSearchQuery() {
        if (searchQuery == null) return;
        String value = "";
        for (Route saved : stack) if (saved.screen.equals("Search")) value = saved.value;
        boolean empty = value.isEmpty();
        searchQuery.setText(empty ? "Search films, series and imports" : value);
        searchQuery.setTextColor(getColor(empty ? R.color.tv_text_secondary : R.color.tv_text));
    }
    @Override public void push(Route next) { Sounds.play(Sounds.Sound.OPEN); rememberFocus(); stack.add(next); enterDirection = 1; render(); }
    @Override public void back() { onBackPressed(); }
    @Override public void refresh() { render(); }
    private void cancelWork() { generation++; if (scope != null) scope.close(); if (pending != null) pending.cancel(true); }
    private void render() {
        if (searchDebounce != null) { main.removeCallbacks(searchDebounce); searchDebounce = null; }
        cancelWork();
        if (stopped || services == null) return;
        scope = services.addons.newRequest();
        refreshClock();
        Route route = route(); Cancellation request = scope;
        if (shownRoute != route && !stayOnTabs) {
            // Hold focus before removing the clicked card. Skeletons have no controls; letting the framework
            // choose a fallback here briefly highlights Home before the page's Play button arrives.
            waitingForContentFocus = true;
            list.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            list.requestFocus();
        }
        chrome(route.screen);
        retainedPages.keySet().retainAll(stack);
        if (shownRoute != route) enter();
        Page retained = retainedPages.get(route);
        if (shownRoute != route && retained != null) show(retained);
        boolean updating = shownRoute == route && shownPage != null;
        if (!updating) { loading(); topBarHidden = true; updateTopBar(); }
        else if (route.screen.equals("Search")) loading();
        int mine = generation;
        Consumer<Page> progress = partial -> main.post(() -> {
            if (mine == generation && !stopped && !isFinishing()) showPartial(partial);
        });
        work(() -> build(route, request, progress), this::show);
    }
    /** Reads the clock setting off the main thread; a page rebuilt after Settings changes picks up the new one. */
    private void refreshClock() {
        TvApplication.Services data = services;
        app.workers.execute(() -> {
            String format = SetupScreens.clockFormat(data);
            main.post(() -> { if (!isFinishing()) tv.daniil.stream.ui.Clock.apply(clock, format, true); });
        });
    }
    /** A new page slides a little way in from the direction of travel and fades up, rather than simply replacing the last. */
    private void enter() {
        int direction = enterDirection; enterDirection = 0;
        list.animate().cancel();
        list.setAlpha(0f);
        list.setTranslationX(direction * Tv.dp(this, 36));
        list.setTranslationY(direction == 0 ? Tv.dp(this, 16) : 0);
        list.animate().alpha(1f).translationX(0).translationY(0).setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
    }
    private Page build(Route route, Cancellation request, Consumer<Page> progress) throws Exception {
        switch (route.screen) {
            case "Home": return browse.home(request);
            case "Search": return browse.search(route, request);
            case "Library": return browse.library();
            case "History": return browse.library();
            case "Imports": return setup.imports();
            case "Settings": return setup.settings(route);
            case "Add-on": return setup.addon(route);
            case "Add-ons": return store.store(route, request, progress);
            case "Add-on review": return store.review(route, request);
            case "Catalogues": return browse.catalogues();
            case "Catalogue": return browse.catalogue(route, request, progress);
            case "Details": return titles.details(route, request);
            case "Sources": return titles.sources(route, request, progress);
            case "Developer": return setup.developer();
            default: return browse.home(request);
        }
    }
    /** Tabs are hidden during onboarding; the active tab follows the bottom of the stack. */
    private void chrome(String screen) {
        boolean tabs = !screen.equals("Starting");
        boolean expanded = tabs && searchExpanded;
        topBarControls.setVisibility(tabs && !expanded ? View.VISIBLE : View.INVISIBLE);
        searchExpandedView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        String rootScreen = stack.isEmpty() ? "Home" : primaryTab(stack.get(0).screen);
        for (int i = 0; i < navigation.getChildCount(); i++) navigation.getChildAt(i).setActivated(TABS[i].equals(rootScreen));
        boolean canGoBack = stack.size() > 1;
        pageBack.setVisibility(canGoBack ? View.VISIBLE : View.GONE);
        View lastTab = navigation.getChildAt(TABS.length - 1);
        if (shownRoute != route()) {
            lastTab.setNextFocusRightId(lastTab.getId());
            pageTools.setVisibility(View.GONE);
        }
        pageBack.setNextFocusRightId(currentTab().getId());
        searchButton.setNextFocusLeftId(canGoBack ? R.id.page_back : R.id.search_button);
        pageLayout.navigationTarget(canGoBack ? pageBack : currentTab());
        syncSearchQuery();
    }
    /** Hides the tab bar once content has scrolled under it, unless it has focus; shows it again at the top. */
    private void updateTopBar() {
        int offset = list.computeVerticalScrollOffset();
        scrollFade.setAlpha(Math.min(1f, offset / (float) Tv.dp(this, 32)));
        boolean hide = !searchExpanded && !topBar.hasFocus() && !pageBack.hasFocus() && offset > Tv.dp(this, 24);
        if (hide == topBarHidden) return;
        topBarHidden = hide;
        topBar.animate().cancel();
        topBar.animate().translationY(hide ? -topBar.getHeight() : 0).alpha(hide ? 0f : 1f).setDuration(hide ? 220 : 260).start();
    }
    private void showPartial(Page page) { show(page, true); }
    private void show(Page page) { show(page, false); }
    /** Page actions share Home's fixed toolbar. Refresh always occupies the rightmost slot. */
    private void bindTools(Page page) {
        List<View> visible = new ArrayList<>();
        int[] ids = {R.id.home_browse, R.id.home_refresh};
        int[] icons = {R.drawable.ic_tv_list, R.drawable.ic_tv_refresh};
        for (int i = 0; i < ids.length; i++) {
            View tool = findViewById(ids[i]); Item.Action action = null;
            for (Item.Action candidate : page.tools) if (candidate.icon == icons[i]) { action = candidate; break; }
            tool.setVisibility(action == null ? View.GONE : View.VISIBLE);
            if (action == null) { tool.setTag(null); continue; }
            tool.setContentDescription(action.label); tool.setTooltipText(action.label);
            Tv.control(tool, action.key, action.action, key -> {}, Tv.LIFT_ICON, false);
            tool.setOnKeyListener((view, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    View target = list.findViewWithTag(route().focus);
                    if (target != null) target.requestFocus(); else focusFirst();
                }
                return true;
            });
            visible.add(tool);
        }
        pageTools.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        View lastTab = navigation.getChildAt(TABS.length - 1);
        lastTab.setNextFocusRightId(visible.isEmpty() ? lastTab.getId() : visible.get(0).getId());
        for (int i = 0; i < visible.size(); i++) {
            View tool = visible.get(i);
            tool.setNextFocusLeftId(i == 0 ? lastTab.getId() : visible.get(i - 1).getId());
            tool.setNextFocusRightId(i + 1 < visible.size() ? visible.get(i + 1).getId() : tool.getId());
        }
    }
    private void show(Page page, boolean partial) {
        boolean updating = shownRoute == route() && shownPage != null;
        Page previousPage = shownPage;
        shownRoute = route(); shownPage = page;
        if (!partial) retainedPages.put(shownRoute, page);
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
        boolean preserveNavigation = !list.hasFocus()
                && (pageBack.hasFocus() || topBar.hasFocus() || pageTools.hasFocus());
        // Restore the route's focus; otherwise start on the page's first choice, unless the user is on the tabs.
        String wanted = !route.focus.isEmpty() ? route.focus : page.initialFocus != null ? page.initialFocus : "";
        boolean tabs = stayOnTabs && navigation.hasFocus();
        stayOnTabs = false;
        if (wanted.isEmpty() && !tabs && page.firstTile() != null) wanted = page.firstTile();
        if (adapter.set(items, preserveNavigation ? "" : wanted)) {
            if (!partial) finishContentFocusWait();
            contentReady = true; return;
        }
        if (partial && updating) { contentReady = true; return; }
        if (!updating || !preserveNavigation) {
            if (route.scroll != null) list.getLayoutManager().onRestoreInstanceState(route.scroll);
            else list.scrollToPosition(0);
        }
        String target = wanted;
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
                });
            } else if (!tabs) focusFirst();
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
        else if (!navigation.hasFocus()) currentTab().requestFocus();
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
        String rootScreen = stack.isEmpty() ? "Home" : primaryTab(stack.get(0).screen);
        for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(rootScreen)) return navigation.getChildAt(i);
        return navigation.getChildAt(0);
    }
    private void loading() {
        contentReady = false;
        if (!stack.isEmpty()) {
            Route current = route();
            if (current.screen.equals("Sources") && current.title != null) {
                Page shell = titles.sourcesPage(current);
                showPartial(shell.add(Item.skeleton("Source results"))); return;
            }
            if (current.screen.equals("Details") && current.title != null) {
                Page shell = titles.detailsPage(current.title, "");
                showPartial(shell.add(Item.skeleton("Detail controls"))); return;
            }
            if (current.screen.equals("Search") && browse != null) {
                Item search = browse.searchLoading(current);
                String focus = current.focus.isEmpty() ? "keyboard:a" : current.focus;
                // Typing updates the results beside the keyboard; the focused key stays focused and keeps being remembered.
                if (adapter.set(java.util.Collections.singletonList(search), focus)) contentReady = true;
                return;
            }
            if (hasPageSkeleton(current.screen)) {
                // The skeleton takes the place its page will have, so nothing jumps when the page arrives.
                gutters(isNarrow(current.screen));
                contentTop(current.screen.equals("Home"));
                adapter.set(java.util.Collections.singletonList(Item.skeleton(current.screen)), "");
                return;
            }
        }
        adapter.set(java.util.Collections.singletonList(Item.loading()), "");
    }
    /** Screens built as lists and forms (Page.narrow): their skeletons use the same centred column. */
    private static boolean isNarrow(String screen) {
        switch (screen) {
            case "Imports": case "Add-on": case "Developer": case "Add-ons": case "Add-on review": case "Sources": return true;
            default: return false;
        }
    }
    /** Pages start below the tab bar; Home's spotlight runs under it. */
    private void contentTop(boolean spotlight) {
        int headerHeight = topBar.getHeight();
        if (headerHeight == 0) headerHeight = Tv.dp(this, 76);
        list.setPadding(list.getPaddingLeft(), spotlight ? 0 : headerHeight + Tv.dp(this, 2), list.getPaddingRight(), list.getPaddingBottom());
    }
    private void gutters(boolean narrow) {
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        int side = narrow ? Math.max(48, Math.round((widthDp - COLUMN) / 2)) : 48;
        adapter.gutter(side, side);
    }
    private static boolean hasPageSkeleton(String screen) {
        switch (screen) {
            case "Home": case "Library": case "History": case "Settings": case "Imports":
            case "Add-on": case "Catalogues": case "Catalogue": case "Details": case "Sources": case "Developer":
            case "Add-ons": case "Add-on review":
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
            show(retained); return;
        }
        Item.Action retry = new Item.Action("retry", Item.Action.Style.PRIMARY, "Retry", () -> { if (services == null) { loading(); initialize(); } else render(); }).icon(R.drawable.ic_tv_refresh);
        Page page = new Page().add(Item.message(e instanceof AddonException && ((AddonException) e).kind == AddonException.Kind.CERTIFICATE ? R.drawable.ic_tv_info : R.drawable.ic_tv_offline,
                "Unable to load", Failures.message(e),
                reportAction == null ? new Item.Action[]{retry} : new Item.Action[]{retry, reportAction}));
        page.initialFocus = "retry";
        show(page);
    }

    // Page listener.
    @Override public void focused(String key) { if (contentReady && !stack.isEmpty()) route().focus = key; }
    @Override public void featured(@Nullable Title title) { backdrop.show(title, false, false); }

    // Workers.
    private <T> void work(Callable<T> task, Consumer<T> done) {
        int mine = generation;
        pending = app.workers.submit(() -> {
            try { T value = task.call(); main.post(() -> { if (mine == generation && !stopped && !isFinishing()) done.accept(value); }); }
            catch (Exception e) { main.post(() -> { if (mine == generation && !stopped && !isFinishing()) failed(e); }); }
        });
    }
    /** Changes the data behind the current page and rebuilds it in place, keeping its scroll position. */
    @Override public void mutate(Callable<?> task) { then(task, () -> { rememberFocus(); render(); }); }
    @Override public void then(Callable<?> task, Runnable after) { work(() -> { task.call(); return true; }, ignored -> after.run()); }

    // Navigation used by screens.
    @Override public void openTitle(Title title, String video) { push(Route.of("Details", title, video)); }
    @Override public void sources(Title title, String video) { push(Route.of("Sources", title, video)); }
    @Override public void play(Title title, String video, String sourceKey) {
        // A catalogue Play/Resume has not chosen a source. Selection is explicit; imports already identify a torrent.
        if (!title.provider.equals("local") && (sourceKey == null || sourceKey.isEmpty())) {
            sources(title, video); return;
        }
        then(() -> { services.store.remember(title); return null; }, () -> startActivity(WatchActivity.intent(this, title, video, sourceKey)));
    }

    // Dialogs: tvOS alerts (Tv.Alert), dismissed by Back or their Cancel/Close button.
    private void show(Tv.Alert alert) {
        Dialog dialog = alert.dialog;
        dialogs.add(dialog); dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
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
        alert.cancelButton("popup:close", "Close", alert.dialog::cancel);
        alert.dialog.setOnCancelListener(ignored -> { if (onClose != null) onClose.run(); });
        show(alert);
    }
    private void showGlassConfirmation(String title, CharSequence message, String action, Runnable onAccept, Runnable onCancel) {
        Tv.Alert alert = Tv.alert(this, title, message);
        alert.button("popup:confirm", action, () -> { alert.dialog.dismiss(); onAccept.run(); });
        alert.cancelButton("popup:cancel", "Cancel", alert.dialog::cancel);
        alert.dialog.setOnCancelListener(ignored -> { if (onCancel != null) onCancel.run(); });
        show(alert);
    }
    @Override public void edit(String title, String hint, String initial, Consumer<String> accept) {
        String description;
        if (title.equals("Add add-on")) description = "Connect a catalogue or source using its manifest address.";
        else if (title.equals("Import torrent")) description = "Paste a magnet link or a torrent URL.";
        else if (title.toLowerCase(java.util.Locale.ROOT).contains("name") || title.startsWith("Rename")) description = "Choose the name shown in your library.";
        else description = "";
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
        error.setVisibility(View.GONE); error.setGravity(Gravity.CENTER); error.setPadding(0, Tv.dp(this, 8), 0, 0);
        alert.surface.addView(error, alert.surface.indexOfChild(alert.buttons));
        View proceed = alert.button("popup:continue", "Continue", () -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) { error.setText("Enter a value"); error.setVisibility(View.VISIBLE); input.requestFocus(); return; }
            error.setVisibility(View.GONE); alert.dialog.dismiss(); accept.accept(text);
        });
        alert.cancelButton("popup:cancel", "Cancel", alert.dialog::dismiss);
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            proceed.performClick(); return true;
        });
        show(alert);
        input.requestFocus();
    }
    @Override public void choose(String title, String[] options, Consumer<Integer> accept) { show(Tv.choices(this, title, options, accept, null)); }
    @Override public void chooseWithPreview(String title, @Nullable View header, String[] options, int selected,
                                           Consumer<Integer> accept, @Nullable Consumer<Integer> onPreview) {
        show(Tv.choices(this, title, header, options, selected, accept, onPreview, null));
    }
    @Override public void alert(String title, String message) { showGlassMessage(title, message, false, null); }
    @Override public void errorReport() {
        work(() -> services != null && SetupScreens.developerMode(services) ? app.lastErrorReport : null,
                report -> { if (report != null) show(ErrorQrPanel.alert(this, report)); });
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
            showPartial(partial.add(Item.skeleton("Install action")));
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
    @Override public void addAddon() { edit("Add add-on", "https://… or stremio://…/manifest.json", "", this::previewAddon); }
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
            boolean torrents = manifest.p2p || tv.daniil.stream.addon.TorrentAddons.find(address) != null;
            String details = (manifest.description.isEmpty() ? "" : manifest.description + "\n\n") + supplies
                    + (torrents ? "\nTorrent sources play directly on this TV and share data with peers." : "")
                    + "\n\n" + manifest.catalogs.size() + " catalogues. The provider receives your searches and the titles you open.";
            showGlassConfirmation("Install " + manifest.name + "?", details, "Install", () -> then(() -> {
                        services.addons.install(address, manifest); return null;
                    }, () -> { Sounds.play(Sounds.Sound.INSTALLED); rememberFocus(); render(); }), () -> { if (mine == generation) render(); });
        });
    }
    @Override public void importAddress() {
        edit("Import torrent", "magnet:?xt=… or https://…torrent", "", address ->
                edit("Name this import", "Title", "Imported torrent", name -> mutate(() -> services.imports.addAddress(name, address))));
    }
    @Override public void importFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, PICK_TORRENT); }
        catch (android.content.ActivityNotFoundException unavailable) {
            alert("Open a torrent file", "No file picker on this TV. Share a .torrent to " + getString(R.string.app_name) + " from a file manager, or add a magnet link.");
        }
    }
    private void importFile(Uri uri) {
        edit("Name this import", "Title", "Imported torrent", name -> then(() -> services.imports.addFile(name, uri, scope), () -> {
            if (!stack.isEmpty() && route().screen.equals("Imports")) render(); else openImports();
        }));
    }
    @Override public void notices() {
        String[] files = {"androidx.txt", "libtorrent.txt", "boost.txt", "openssl.txt", "ed25519.txt", "try-signal.txt", "inter.txt", "zxing.txt"};
        choose("Open source notices", new String[]{"AndroidX / Media3", "libtorrent", "Boost", "OpenSSL", "Ed25519", "try_signal", "Inter typeface", "ZXing"}, which -> work(() -> {
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
    /** tvOS order: Back leaves a pushed screen, then moves to the tab bar, then Home, then exits. */
    @Override public void onBackPressed() {
        if (intro != null) { finish(); return; }
        if (stack.size() > 1) { Sounds.play(Sounds.Sound.BACK); cancelWork(); stack.remove(stack.size() - 1); enterDirection = -1; render(); return; }
        if (searchExpanded && !stack.isEmpty() && stack.get(0).screen.equals("Search")) { Sounds.play(Sounds.Sound.BACK); closeSearch(); return; }
        boolean tabs = topBarControls.getVisibility() == View.VISIBLE && services != null;
        if (tabs && !navigation.hasFocus()) { Sounds.play(Sounds.Sound.BACK); currentTab().requestFocus(); return; }
        if (tabs && !route().screen.equals("Home")) { Sounds.play(Sounds.Sound.BACK); root("Home"); return; }
        finish();
    }
    /**
     * Arrows are the viewer's navigation, for the focus ticks. One that moves nothing (a row's end, the page's side)
     * bumps, unless focus arrives a moment later (a list that lays out the next row first).
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // Nothing beneath the start screen is visible yet, so only Back reaches the app.
        if (intro != null && event.getKeyCode() != KeyEvent.KEYCODE_BACK) return true;
        boolean move = event.getAction() == KeyEvent.ACTION_DOWN && Sounds.arrow(event.getKeyCode());
        if (move) Sounds.input();
        View before = getCurrentFocus();
        if (move && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && waitingForContentFocus && before == list) {
            View entry = pageBack.getVisibility() == View.VISIBLE ? pageBack : currentTab();
            if (entry.isShown()) { entry.requestFocus(); return true; }
        }
        boolean handled = super.dispatchKeyEvent(event);
        View after = getCurrentFocus();
        if (move && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && insidePage(before) && !insidePage(after)) {
            View entry = pageBack.getVisibility() == View.VISIBLE ? pageBack : currentTab();
            if (entry.isShown()) { entry.requestFocus(); handled = true; }
        }
        if (move && event.getRepeatCount() == 0 && before != null && getCurrentFocus() == before)
            main.postDelayed(() -> { if (!stopped && getCurrentFocus() == before) Sounds.play(Sounds.Sound.EDGE); }, 90);
        return handled;
    }

    private boolean insidePage(@Nullable View view) {
        while (view != null) {
            if (view == list) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    @Override protected void onStop() { super.onStop(); stopped = true; cancelWork(); artwork.cancel(); }
    @Override protected void onStart() {
        super.onStart();
        Sounds.get(this).refresh(this);
        boolean returning = stopped; stopped = false;
        if (returning) { if (services == null) initialize(); else render(); }
    }
    @Override protected void onDestroy() {
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
