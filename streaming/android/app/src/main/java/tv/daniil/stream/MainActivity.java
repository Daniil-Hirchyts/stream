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
    private static final String[] TABS = {"Home", "Search", "Library", "History", "Imports", "Settings"};
    private static final int PICK_TORRENT = 21;
    private TvApplication app;
    private TvApplication.Services services;
    private RecyclerView list;
    private PageAdapter adapter;
    private Artwork artwork;
    private Backdrop backdrop;
    private LinearLayout navigation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Route> stack = new ArrayList<>();
    private final Set<Dialog> dialogs = new HashSet<>();
    private BrowseScreens browse;
    private TitleScreens titles;
    private SetupScreens setup;
    private Cancellation scope;
    private Future<?> pending;
    private int generation;
    private boolean stopped, contentReady, incomingHandled;
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
        for (String screen : TABS) navigation.addView(tab(screen));
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        if (saved != null) restore(saved.getString("routes", ""));
        if (stack.isEmpty()) stack.add(new Route("Starting"));
        loading();
        initialize();
    }
    private TextView tab(String screen) {
        TextView tab = Tv.heading(this, screen, 17, R.color.tv_tab_text);
        tab.setGravity(Gravity.CENTER); tab.setBackgroundResource(R.drawable.tv_tab); tab.setContentDescription(screen);
        tab.setPadding(Tv.dp(this, 22), 0, Tv.dp(this, 22), 0);
        tab.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Tv.dp(this, 44)));
        Tv.control(tab, "nav:" + screen, () -> { stayOnTabs = true; root(screen); }, key -> {}, 1.06f);
        return tab;
    }
    private void initialize() {
        work(() -> app.services(), value -> {
            services = value;
            browse = new BrowseScreens(this, services); titles = new TitleScreens(this, services); setup = new SetupScreens(this, this, services);
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
        if (services == null) return;
        rememberFocus(); stack.clear(); stack.add(new Route(screen)); render();
    }
    @Override public void push(Route next) { rememberFocus(); stack.add(next); render(); }
    @Override public void back() { onBackPressed(); }
    @Override public void refresh() { render(); }
    private void cancelWork() { generation++; if (scope != null) scope.close(); if (pending != null) pending.cancel(true); }
    private void render() {
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
            case "History": return browse.history();
            case "Imports": return setup.imports();
            case "Settings": return setup.settings();
            case "Add-on": return setup.addon(route);
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
        findViewById(R.id.navigation).setVisibility(tabs ? View.VISIBLE : View.INVISIBLE);
        String rootScreen = stack.get(0).screen;
        for (int i = 0; i < navigation.getChildCount(); i++) navigation.getChildAt(i).setActivated(TABS[i].equals(rootScreen));
    }
    private void show(Page page) {
        contentReady = false;
        Route route = route();
        TextView title = findViewById(R.id.page_title);
        title.setText(page.title == null ? "" : page.title);
        title.setVisibility(page.title == null ? View.GONE : View.VISIBLE);
        int headerHeight = findViewById(R.id.top_bar).getHeight();
        if (headerHeight == 0) headerHeight = Tv.dp(this, 80);
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
        String rootScreen = stack.get(0).screen;
        for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(rootScreen)) return navigation.getChildAt(i);
        return navigation.getChildAt(0);
    }
    private void loading() {
        contentReady = false;
        adapter.set(java.util.Collections.singletonList(Item.loading(new Item.Action("cancel", Item.Action.Style.SECONDARY, "Cancel", this::onBackPressed))), "");
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
        dialogs.add(dialog); dialog.setOnDismissListener(ignored -> dialogs.remove(dialog)); dialog.show();
        for (int id : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            View button = dialog.getButton(id);
            if (button != null) button.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_DOWN) v.requestFocus(); return false; });
        }
    }
    @Override public void edit(String title, String hint, String initial, Consumer<String> accept) {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint(hint); input.setText(initial); input.setTextSize(20);
        input.setSelectAllOnFocus(true); input.setContentDescription(title); input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        LinearLayout box = new LinearLayout(this);
        int pad = Tv.dp(this, 22); box.setPadding(pad, Tv.dp(this, 8), pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) { input.setError("Enter a value"); return; }
            dialog.dismiss(); accept.accept(text);
        }));
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return true;
        });
        showDialog(dialog);
        // TV keyboards occupy the lower screen and can float over centred dialog buttons.
        dialog.getWindow().setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams window = dialog.getWindow().getAttributes(); window.y = Tv.dp(this, 24); dialog.getWindow().setAttributes(window);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        input.requestFocus();
    }
    @Override public void choose(String title, String[] options, Consumer<Integer> accept) {
        showDialog(new AlertDialog.Builder(this).setTitle(title).setItems(options, (d, which) -> accept.accept(which)).setNegativeButton("Cancel", null).create());
    }
    @Override public void alert(String title, String message) {
        showDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).create());
    }
    @Override public void addAddon() { edit("Add add-on", "https://provider/manifest.json", "", this::previewAddon); }
    @Override public void previewAddon(String address) {
        loading();
        int mine = generation;
        Cancellation request = scope;
        work(() -> services.addons.preview(address, request), manifest -> {
            boolean streams = false;
            for (tv.daniil.stream.addon.AddonManifest.Resource r : manifest.resources) if (r.name.equals("stream")) streams = true;
            String supplies = streams ? "It supplies streams for playback." : "It supplies catalogues and details, not streams.";
            showDialog(new AlertDialog.Builder(this).setTitle("Install " + manifest.name + "?")
                    .setMessage((manifest.description.isEmpty() ? "" : manifest.description + "\n\n") + supplies
                            + (manifest.configurationRequired ? "\nThis add-on needs a configured manifest address." : "")
                            + "\n\n" + manifest.catalogs.size() + " catalogues. The provider receives your searches and the titles you open.")
                    .setNegativeButton("Cancel", (d, w) -> render())
                    .setPositiveButton("Install", (d, w) -> mutate(() -> {
                        if (manifest.configurationRequired) throw new AddonException(AddonException.Kind.INVALID_URL, "Configuration required");
                        services.addons.install(address, manifest); return null;
                    }))
                    .setOnCancelListener(d -> { if (mine == generation) render(); }).create());
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
        edit("Name this import", "Title", "Imported torrent", name -> then(() -> services.imports.addFile(name, uri, scope), () -> root("Imports")));
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
            ScrollView scroll = new ScrollView(this);
            TextView content = Tv.text(this, text, 14, R.color.tv_text_secondary);
            int pad = Tv.dp(this, 24); content.setPadding(pad, 0, pad, pad); content.setTextIsSelectable(true);
            scroll.addView(content);
            showDialog(new AlertDialog.Builder(this).setTitle("Open source notices").setView(scroll)
                    .setPositiveButton("Close", (d, w) -> render()).setOnCancelListener(d -> render()).create());
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
        boolean tabs = navigation.getVisibility() == View.VISIBLE && services != null;
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
