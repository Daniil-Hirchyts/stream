package tv.daniil.stream;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
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
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Page;
import tv.daniil.stream.ui.PageAdapter;
import tv.daniil.stream.ui.Tv;

/**
 * TV navigation shell: tabs, back stack, artwork and dialogs. Screens build pages on application workers; each
 * route render owns a cancellable provider scope and a generation, so late results never reach a newer screen.
 */
public final class MainActivity extends Activity implements Navigator, PageAdapter.Listener {
    private static final String[] TABS = {"Home", "Library", "Add-ons", "Settings"};
    private static final int PICK_TORRENT = 21;
    private TvApplication app;
    private TvApplication.Services services;
    private RecyclerView list;
    private PageAdapter adapter;
    private Artwork artwork;
    private Backdrop backdrop;
    private LinearLayout navigation;
    private View topBarControls, searchButton, searchExpandedView, searchClose;
    private TextView searchQuery;
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
    @Nullable private Uri incomingFile;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = (TvApplication) getApplication();
        incomingHandled = saved != null && saved.getBoolean("incomingHandled");
        incomingFile = incomingHandled ? null : incoming(getIntent());
        setContentView(R.layout.activity_browse);
        artwork = new Artwork();
        backdrop = new Backdrop(artwork, findViewById(R.id.backdrop), findViewById(R.id.backdrop_back), findViewById(R.id.backdrop_scrim));
        list = findViewById(R.id.page_rows);
        list.setLayoutManager(new PageAdapter.Layout(this, RecyclerView.VERTICAL, 40));
        list.setItemAnimator(null);
        adapter = new PageAdapter(this, artwork, this);
        list.setAdapter(adapter);
        navigation = findViewById(R.id.navigation);
        topBarControls = findViewById(R.id.top_bar_controls);
        searchButton = findViewById(R.id.search_button);
        searchExpandedView = findViewById(R.id.search_expanded);
        searchQuery = findViewById(R.id.search_query);
        searchClose = findViewById(R.id.search_close);
        searchButton.setDefaultFocusHighlightEnabled(false);
        searchClose.setDefaultFocusHighlightEnabled(false);
        for (String screen : TABS) navigation.addView(tab(screen));
        Tv.control(searchButton, "nav:search", this::search, key -> {}, 1.06f, false);
        Tv.control(searchClose, "search:close", this::closeSearch, key -> {}, 1.04f, false);
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
        TextView tab = Tv.heading(this, screen, 17, R.color.tv_tab_text);
        tab.setGravity(Gravity.CENTER); tab.setBackgroundResource(R.drawable.tv_tab); tab.setContentDescription(screen);
        tab.setDefaultFocusHighlightEnabled(false);
        tab.setPadding(Tv.dp(this, 14), 0, Tv.dp(this, 14), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 44));
        params.setMarginStart(Tv.dp(this, 3)); params.setMarginEnd(Tv.dp(this, 3));
        tab.setLayoutParams(params);
        Tv.control(tab, "nav:" + screen, () -> { stayOnTabs = true; root(screen); }, key -> {}, 1.06f, false);
        return tab;
    }
    private void initialize() {
        work(() -> app.services(), value -> {
            services = value;
            browse = new BrowseScreens(this, services); titles = new TitleScreens(this, services); setup = new SetupScreens(this, this, services); store = new StoreScreens(this, services);
            if (route().screen.equals("Starting")) {
                // Someone who already added a provider or an import has finished onboarding in practice.
                work(() -> services.store.preference("onboardingDone", "false").equals("true") || !services.store.addons().isEmpty() || !services.store.importIds().isEmpty(),
                        done -> { root(done ? "Home" : "Welcome"); offerIncomingFile(); });
            } else { render(); offerIncomingFile(); }
        });
    }

    // Routes and rendering.
    private Route route() { return stack.get(stack.size() - 1); }
    private void rememberFocus() { if (!stack.isEmpty()) route().scroll = list.getLayoutManager().onSaveInstanceState(); }
    @Override public void root(String screen) {
        if (screen.equals("Search")) { search(); return; }
        if (services == null) return;
        searchExpanded = false;
        if (isPrimaryTab(screen)) searchReturnTab = screen;
        rememberFocus(); stack.clear(); stack.add(new Route(screen)); render();
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
            saved.value = value; saved.searchSubmitted = false;
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
        if (screen.equals("Imports") || screen.equals("Add-on") || screen.equals("Developer")) return "Settings";
        if (screen.equals("Add-on review")) return "Add-ons";
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
    @Override public void push(Route next) { rememberFocus(); stack.add(next); render(); }
    @Override public void back() { onBackPressed(); }
    @Override public void refresh() { render(); }
    private void cancelWork() { generation++; if (scope != null) scope.close(); if (pending != null) pending.cancel(true); }
    private void render() {
        if (searchDebounce != null) { main.removeCallbacks(searchDebounce); searchDebounce = null; }
        cancelWork();
        if (stopped || services == null) return;
        scope = services.addons.newRequest();
        Route route = route(); Cancellation request = scope;
        chrome(route.screen);
        loading();
        work(() -> build(route, request), this::show);
    }
    private Page build(Route route, Cancellation request) throws Exception {
        switch (route.screen) {
            case "Welcome": return setup.welcome();
            case "Home": return browse.home(request);
            case "Search": return browse.search(route, request);
            case "Library": return browse.library();
            case "History": return browse.library();
            case "Imports": return setup.imports();
            case "Settings": return setup.settings();
            case "Add-on": return setup.addon(route);
            case "Add-ons": return store.store(route, request);
            case "Add-on review": return store.review(route, request);
            case "Catalogues": return browse.catalogues();
            case "Catalogue": return browse.catalogue(route, request);
            case "Details": return titles.details(route, request);
            case "Sources": return titles.sources(route, request);
            case "Developer": return setup.developer();
            default: return browse.home(request);
        }
    }
    /** Tabs are hidden during onboarding; the active tab follows the bottom of the stack. */
    private void chrome(String screen) {
        boolean tabs = !screen.equals("Welcome") && !screen.equals("Starting");
        boolean expanded = tabs && searchExpanded;
        topBarControls.setVisibility(tabs && !expanded ? View.VISIBLE : View.INVISIBLE);
        searchExpandedView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        String rootScreen = stack.isEmpty() ? "Home" : primaryTab(stack.get(0).screen);
        for (int i = 0; i < navigation.getChildCount(); i++) navigation.getChildAt(i).setActivated(TABS[i].equals(rootScreen));
        syncSearchQuery();
    }
    private void show(Page page) {
        contentReady = false;
        Route route = route();
        TextView title = findViewById(R.id.page_title);
        title.setText(page.title == null ? "" : page.title);
        title.setVisibility(page.title == null ? View.GONE : View.VISIBLE);
        int headerHeight = findViewById(R.id.top_bar).getHeight();
        if (headerHeight == 0) headerHeight = Tv.dp(this, 84);
        FrameLayout.LayoutParams titlePosition = (FrameLayout.LayoutParams) title.getLayoutParams();
        titlePosition.topMargin = headerHeight + Tv.dp(this, 8);
        title.setLayoutParams(titlePosition);
        View panel = findViewById(R.id.side_panel);
        panel.setVisibility(page.hasPanel() ? View.VISIBLE : View.GONE);
        if (page.hasPanel()) {
            ((ImageView) findViewById(R.id.side_icon)).setImageResource(page.panelIcon);
            ((TextView) findViewById(R.id.side_title)).setText(page.panelTitle);
            ((TextView) findViewById(R.id.side_text)).setText(page.panelText);
        }
        adapter.gutter(page.hasPanel() ? 8 : 48, 48);
        // Home's spotlight sets the artwork itself; clearing it first would flash the plain canvas.
        boolean spotlight = !page.items.isEmpty() && page.items.get(0).kind == Item.Kind.SPOTLIGHT;
        int contentTop = spotlight ? Tv.dp(this, 8)
                : headerHeight + Tv.dp(this, page.title == null ? 8 : 66);
        list.setPadding(list.getPaddingLeft(), contentTop, list.getPaddingRight(), list.getPaddingBottom());
        if (!spotlight) backdrop.show(page.backdrop, true);
        // Restore the route's focus; otherwise start on the page's first choice, unless the user is on the tabs.
        String wanted = !route.focus.isEmpty() ? route.focus : page.initialFocus != null ? page.initialFocus : "";
        boolean tabs = stayOnTabs && navigation.hasFocus();
        stayOnTabs = false;
        if (wanted.isEmpty() && !tabs && page.firstTile() != null) wanted = page.firstTile();
        adapter.set(page.items, wanted);
        if (route.scroll != null) list.getLayoutManager().onRestoreInstanceState(route.scroll);
        else list.scrollToPosition(0);
        String target = wanted;
        int mine = generation;
        list.post(() -> {
            if (mine != generation || stopped) return;
            View found = target.isEmpty() ? null : list.findViewWithTag(target);
            if (found != null) found.requestFocus();
            else if (!target.isEmpty() && adapter.position(target) >= 0) {
                list.scrollToPosition(adapter.position(target));
                list.post(() -> {
                    if (mine != generation || stopped) return;
                    View restored = list.findViewWithTag(target);
                    if (restored != null) restored.requestFocus(); else focusFirst();
                });
            } else if (!tabs) focusFirst();
            contentReady = true;
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
            if (current.screen.equals("Search") && browse != null) {
                Item search = browse.searchLoading(current);
                String focus = current.focus.isEmpty() ? "keyboard:A" : current.focus;
                adapter.set(java.util.Collections.singletonList(search), focus);
                return;
            }
            if (hasPageSkeleton(current.screen)) {
                adapter.set(java.util.Collections.singletonList(Item.skeleton(current.screen)), "");
                return;
            }
        }
        adapter.set(java.util.Collections.singletonList(Item.loading()), "");
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
        Page page = new Page().add(Item.message(e instanceof AddonException && ((AddonException) e).kind == AddonException.Kind.CERTIFICATE ? R.drawable.ic_tv_info : R.drawable.ic_tv_offline,
                "Unable to load", Failures.message(e),
                new Item.Action("retry", Item.Action.Style.PRIMARY, "Retry", () -> { if (services == null) { loading(); initialize(); } else render(); }).icon(R.drawable.ic_tv_refresh),
                new Item.Action("back", Item.Action.Style.SECONDARY, "Back", this::onBackPressed)));
        page.initialFocus = "retry";
        show(page);
    }

    // Page listener.
    @Override public void focused(String key) { if (contentReady && !stack.isEmpty()) route().focus = key; }
    @Override public void featured(@Nullable Title title) { backdrop.show(title, false); }

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
        then(() -> { services.store.remember(title); return null; }, () -> startActivity(WatchActivity.intent(this, title, video, sourceKey)));
    }

    // Dialogs.
    private void showDialog(AlertDialog dialog) {
        dialogs.add(dialog); dialog.setOnDismissListener(ignored -> dialogs.remove(dialog)); dialog.show(); Tv.styleDialog(dialog);
        for (int id : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            View button = dialog.getButton(id);
            if (button != null) button.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_DOWN) v.requestFocus(); return false; });
        }
    }
    private void showGlassDialog(Dialog dialog) {
        dialogs.add(dialog); dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show(); Tv.styleDialog(dialog);
    }
    private LinearLayout glassSurface(Dialog dialog, String title) {
        LinearLayout surface = new LinearLayout(this);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackgroundResource(R.drawable.tv_dialog);
        int horizontal = Tv.dp(this, 32);
        surface.setPadding(horizontal, Tv.dp(this, 26), horizontal, Tv.dp(this, 24));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = Tv.heading(this, title, 25, R.color.tv_text);
        heading.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(heading);
        ImageView close = Tv.icon(this, R.drawable.ic_tv_close, 22, R.color.tv_text_secondary);
        close.setBackgroundResource(R.drawable.tv_button_round);
        close.setContentDescription("Close");
        close.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(Tv.dp(this, 48), Tv.dp(this, 48));
        closeParams.setMarginStart(Tv.dp(this, 18));
        header.addView(close, closeParams);
        surface.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 48)));
        Tv.control(close, "popup:close", dialog::cancel, ignored -> {}, 1.04f, false);
        return surface;
    }
    private void showGlassMessage(String title, CharSequence message, boolean scroll, Runnable onClose) {
        Dialog dialog = new Dialog(this);
        LinearLayout surface = glassSurface(dialog, title);
        if (scroll) {
            ScrollView content = new ScrollView(this);
            content.setVerticalScrollBarEnabled(false);
            TextView body = Tv.text(this, message.toString(), 15, R.color.tv_text_secondary);
            body.setTextIsSelectable(true);
            body.setLineSpacing(Tv.dp(this, 3), 1f);
            int pad = Tv.dp(this, 18);
            body.setPadding(pad, pad, pad, pad);
            content.addView(body);
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 380));
            contentParams.topMargin = Tv.dp(this, 18);
            surface.addView(content, contentParams);
        } else {
            TextView body = Tv.text(this, message.toString(), 17, R.color.tv_text_secondary);
            body.setLineSpacing(Tv.dp(this, 4), 1f);
            body.setPadding(0, Tv.dp(this, 18), 0, Tv.dp(this, 8));
            surface.addView(body);
        }
        dialog.setOnCancelListener(ignored -> { if (onClose != null) onClose.run(); });
        dialog.setContentView(surface);
        dialog.setCanceledOnTouchOutside(true);
        showGlassDialog(dialog);
    }
    private void showGlassConfirmation(String title, CharSequence message, String action, Runnable onAccept, Runnable onCancel) {
        Dialog dialog = new Dialog(this);
        LinearLayout surface = glassSurface(dialog, title);
        ScrollView content = new ScrollView(this);
        content.setVerticalScrollBarEnabled(false);
        TextView body = Tv.text(this, message.toString(), 17, R.color.tv_text_secondary);
        body.setLineSpacing(Tv.dp(this, 4), 1f);
        content.addView(body);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.topMargin = Tv.dp(this, 18);
        contentParams.bottomMargin = Tv.dp(this, 8);
        surface.addView(content, contentParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 56));
        actionsParams.topMargin = Tv.dp(this, 12);
        surface.addView(actions, actionsParams);
        FrameLayout primary = new FrameLayout(this);
        primary.setBackgroundResource(R.drawable.tv_dialog_primary);
        primary.setContentDescription(action);
        primary.setMinimumWidth(Tv.dp(this, 176));
        LinearLayout primaryContent = new LinearLayout(this);
        primaryContent.setGravity(Gravity.CENTER);
        primaryContent.addView(Tv.heading(this, action, 17, R.color.tv_on_focus));
        ImageView arrow = Tv.icon(this, R.drawable.ic_tv_chevron, 20, R.color.tv_on_focus);
        LinearLayout.LayoutParams arrowParams = (LinearLayout.LayoutParams) arrow.getLayoutParams();
        arrowParams.setMarginStart(Tv.dp(this, 10));
        primaryContent.addView(arrow, arrowParams);
        primary.addView(primaryContent, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        actions.addView(primary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Tv.control(primary, "popup:confirm", () -> { dialog.dismiss(); onAccept.run(); }, ignored -> {}, 1.04f, false);
        dialog.setOnCancelListener(ignored -> { if (onCancel != null) onCancel.run(); });
        dialog.setContentView(surface);
        dialog.setCanceledOnTouchOutside(true);
        showGlassDialog(dialog);
        primary.requestFocus();
    }
    @Override public void edit(String title, String hint, String initial, Consumer<String> accept) {
        Dialog dialog = new Dialog(this);
        LinearLayout surface = new LinearLayout(this);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackgroundResource(R.drawable.tv_dialog);
        int horizontal = Tv.dp(this, 32);
        surface.setPadding(horizontal, Tv.dp(this, 28), horizontal, Tv.dp(this, 28));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = Tv.heading(this, title, 26, R.color.tv_text);
        heading.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(heading);
        ImageView close = Tv.icon(this, R.drawable.ic_tv_close, 22, R.color.tv_text_secondary);
        close.setBackgroundResource(R.drawable.tv_button_round);
        close.setContentDescription("Cancel");
        close.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(Tv.dp(this, 48), Tv.dp(this, 48));
        closeParams.setMarginStart(Tv.dp(this, 18));
        header.addView(close, closeParams);
        surface.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 48)));
        Tv.control(close, "popup:cancel", dialog::dismiss, ignored -> {}, 1.04f, false);

        String description;
        if (title.equals("Add add-on")) description = "Connect a catalogue or source using its manifest URL.";
        else if (title.equals("Import torrent")) description = "Paste a magnet link or a torrent URL.";
        else if (title.toLowerCase(java.util.Locale.ROOT).contains("name") || title.startsWith("Rename")) description = "Choose the name shown in your library.";
        else description = "Enter a value to continue.";
        TextView detail = Tv.text(this, description, 16, R.color.tv_text_secondary);
        detail.setPadding(0, Tv.dp(this, 6), 0, Tv.dp(this, 22));
        surface.addView(detail);

        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint(hint); input.setText(initial); input.setTextSize(19);
        input.setTextColor(getColor(R.color.tv_text)); input.setHintTextColor(getColor(R.color.tv_text_secondary));
        input.setSelectAllOnFocus(true); input.setContentDescription(title); input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackgroundResource(android.R.color.transparent); input.setPadding(0, 0, 0, 0);
        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackgroundResource(R.drawable.tv_dialog_field);
        field.setPadding(Tv.dp(this, 20), 0, Tv.dp(this, 20), 0);
        int inputIcon = hint.equalsIgnoreCase("Title") || hint.equalsIgnoreCase("Value") ? R.drawable.ic_tv_edit : R.drawable.ic_tv_link;
        ImageView leading = Tv.icon(this, inputIcon, 23, R.color.tv_text_secondary);
        LinearLayout.LayoutParams leadingParams = (LinearLayout.LayoutParams) leading.getLayoutParams();
        leadingParams.setMarginEnd(Tv.dp(this, 16));
        field.addView(leading, leadingParams);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        field.addView(input, inputParams);
        surface.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 62)));

        TextView error = Tv.text(this, "", 14, R.color.tv_warning);
        error.setVisibility(View.GONE);
        error.setPadding(Tv.dp(this, 4), Tv.dp(this, 8), 0, 0);
        surface.addView(error);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(this, 56));
        actionsParams.topMargin = Tv.dp(this, 22);
        surface.addView(actions, actionsParams);
        FrameLayout proceed = new FrameLayout(this);
        proceed.setBackgroundResource(R.drawable.tv_dialog_primary);
        proceed.setContentDescription("Continue");
        proceed.setMinimumWidth(Tv.dp(this, 176));
        LinearLayout proceedContent = new LinearLayout(this);
        proceedContent.setGravity(Gravity.CENTER);
        TextView proceedLabel = Tv.heading(this, "Continue", 17, R.color.tv_on_focus);
        proceedContent.addView(proceedLabel);
        ImageView arrow = Tv.icon(this, R.drawable.ic_tv_chevron, 20, R.color.tv_on_focus);
        LinearLayout.LayoutParams arrowParams = (LinearLayout.LayoutParams) arrow.getLayoutParams();
        arrowParams.setMarginStart(Tv.dp(this, 10));
        proceedContent.addView(arrow, arrowParams);
        proceed.addView(proceedContent, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        actions.addView(proceed, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Tv.control(proceed, "popup:continue", () -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                error.setText("Enter a value"); error.setVisibility(View.VISIBLE); input.requestFocus(); return;
            }
            error.setVisibility(View.GONE); dialog.dismiss(); accept.accept(text);
        }, ignored -> {}, 1.04f, false);
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            proceed.performClick(); return true;
        });
        dialog.setContentView(surface);
        showGlassDialog(dialog);
        input.requestFocus();
    }
    @Override public void choose(String title, String[] options, Consumer<Integer> accept) {
        Dialog dialog = Tv.glassChoices(this, title, options, accept, null);
        showGlassDialog(dialog);
        View first = dialog.getWindow().getDecorView().findViewWithTag("choice:0");
        if (first != null) first.requestFocus();
    }
    @Override public void alert(String title, String message) {
        showGlassMessage(title, message, false, null);
    }
    @Override public void addAddon() { edit("Add add-on", "https://… or stremio://…/manifest.json", "", this::previewAddon); }
    @Override public void previewAddon(String input) {
        String address = AddonUrl.normalize(input);
        loading();
        int mine = generation;
        Cancellation request = scope;
        work(() -> services.addons.preview(address, request), manifest -> {
            boolean streams = false;
            for (tv.daniil.stream.addon.AddonManifest.Resource r : manifest.resources) if (r.name.equals("stream")) streams = true;
            String supplies = streams ? "It supplies streams for playback." : "It supplies catalogues and details, not streams.";
            String details = (manifest.description.isEmpty() ? "" : manifest.description + "\n\n") + supplies
                    + (manifest.configurationRequired ? "\nThis add-on needs a configured manifest address." : "")
                    + "\n\n" + manifest.catalogs.size() + " catalogues. The provider receives your searches and the titles you open.";
            showGlassConfirmation("Install " + manifest.name + "?", details, "Install", () -> mutate(() -> {
                        if (manifest.configurationRequired) throw new AddonException(AddonException.Kind.INVALID_URL, "Configuration required");
                        services.addons.install(address, manifest); return null;
                    }), () -> { if (mine == generation) render(); });
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
            alert("Open a torrent file", "This TV has no system file picker. Open or share a .torrent file from a TV file manager and choose Daniil TV. "
                    + "You can also add a magnet link or torrent URL here.");
        }
    }
    private void importFile(Uri uri) {
        edit("Name this import", "Title", "Imported torrent", name -> then(() -> services.imports.addFile(name, uri, scope), () -> {
            if (!stack.isEmpty() && route().screen.equals("Imports")) render(); else openImports();
        }));
    }
    @Override public void notices() {
        String[] files = {"androidx.txt", "libtorrent.txt", "boost.txt", "openssl.txt", "ed25519.txt", "try-signal.txt"};
        choose("Open source notices", new String[]{"AndroidX / Media3", "libtorrent", "Boost", "OpenSSL", "Ed25519", "try_signal"}, which -> work(() -> {
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
        if (stack.size() > 1) { cancelWork(); stack.remove(stack.size() - 1); render(); return; }
        if (searchExpanded && !stack.isEmpty() && stack.get(0).screen.equals("Search")) { closeSearch(); return; }
        boolean tabs = topBarControls.getVisibility() == View.VISIBLE && services != null;
        if (tabs && !navigation.hasFocus()) { currentTab().requestFocus(); return; }
        if (tabs && !route().screen.equals("Home")) { root("Home"); return; }
        finish();
    }
    @Override protected void onStop() { super.onStop(); stopped = true; cancelWork(); artwork.cancel(); }
    @Override protected void onStart() {
        super.onStart();
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
