package tv.daniil.stream;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import tv.daniil.stream.addon.*;
import tv.daniil.stream.data.*;
import tv.daniil.stream.ui.Artwork;
import tv.daniil.stream.ui.Failures;
import tv.daniil.stream.ui.Rows;
import tv.daniil.stream.ui.Rows.Row;

/** TV navigation. Provider/database work runs outside the main thread; routes own cancellable generations. */
public final class MainActivity extends Activity {
    private TvApplication app;
    private TvApplication.Services services;
    private RecyclerView list;
    private Rows rows;
    private Artwork artwork;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Route> stack = new ArrayList<>();
    private Cancellation scope;
    private Future<?> pending;
    private int generation;
    private boolean stopped;
    private boolean contentReady;
    private android.net.Uri incomingFile;
    private boolean incomingHandled;
    private final java.util.Set<android.app.Dialog> dialogs = new java.util.HashSet<>();
    private static final int PICK_TORRENT = 21;
    private static final String[] LANGUAGE_NAMES = {"Automatic", "English", "Russian", "French", "German", "Spanish", "Italian", "Ukrainian", "Japanese", "Off"};
    private static final String[] LANGUAGES = {"", "eng", "rus", "fra", "deu", "spa", "ita", "ukr", "jpn", "off"};

    private static final class Route {
        String screen, focus = "", value = "", video = "";
        Title title;
        int season = -1, page;
        Parcelable scroll;
        final Map<String, String> extras = new LinkedHashMap<>();
        Route(String screen) { this.screen = screen; }
    }
    private Route route() { return stack.get(stack.size() - 1); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = (TvApplication) getApplication();
        incomingHandled = saved != null && saved.getBoolean("incomingHandled");
        incomingFile = incomingHandled ? null : incoming(getIntent());
        setContentView(R.layout.activity_browse);
        artwork = new Artwork(); list = findViewById(R.id.page_rows);
        list.setLayoutManager(new LinearLayoutManager(this));
        rows = new Rows(this, artwork, key -> { if (contentReady && !stack.isEmpty()) route().focus = key; }); list.setAdapter(rows);
        LinearLayout nav = findViewById(R.id.navigation);
        nav.addView(Rows.text(this, "DANIIL TV", 18, 0xff8ae6cb));
        for (String screen : new String[]{"Home", "Search", "Library", "History", "Imports", "Settings"}) {
            TextView button = Rows.button(this, screen, () -> root(screen)); button.setTag("nav:" + screen);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 8, 0, 0); nav.addView(button, p);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        if (saved != null) restore(saved.getString("routes", ""));
        if (stack.isEmpty()) stack.add(new Route("Starting"));
        loading();
        initialize();
    }
    private void initialize() {
        work(() -> app.services(), value -> {
            services = value;
            if (route().screen.equals("Starting")) {
                work(() -> services.store.preference("onboardingDone", "false"), done -> { root(done.equals("true") ? "Home" : "Welcome"); offerIncomingFile(); });
            } else { render(); offerIncomingFile(); }
        });
    }
    private static android.net.Uri incoming(Intent intent) {
        try {
            Object item = Intent.ACTION_VIEW.equals(intent.getAction()) ? intent.getData()
                    : Intent.ACTION_SEND.equals(intent.getAction()) ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : null;
            android.net.Uri uri = item instanceof android.net.Uri ? (android.net.Uri) item : null;
            return uri != null && "content".equals(uri.getScheme()) ? uri : null;
        } catch (RuntimeException malformed) { return null; }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); incomingHandled = false; incomingFile = incoming(intent); if (services != null) offerIncomingFile();
    }
    private void offerIncomingFile() {
        if (incomingFile == null) return;
        android.net.Uri uri = incomingFile; incomingFile = null; incomingHandled = true;
        importFile(uri);
    }
    private void importFile(android.net.Uri uri) {
        edit("Name this import", "Title", "Imported torrent", name -> work(() -> services.imports.addFile(name, uri, scope), ignored -> root("Imports")));
    }
    private void showDialog(AlertDialog dialog) {
        dialogs.add(dialog); dialog.setOnDismissListener(ignored -> dialogs.remove(dialog)); dialog.show();
        for (int id : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            View button = dialog.getButton(id);
            if (button != null) button.setOnTouchListener((v, event) -> { if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) v.requestFocus(); return false; });
        }
    }

    private void rememberFocus() { if (!stack.isEmpty()) route().scroll = list.getLayoutManager().onSaveInstanceState(); }
    private void root(String screen) {
        if (services == null) return;
        rememberFocus(); stack.clear(); stack.add(new Route(screen)); render();
    }
    private void push(Route next) { rememberFocus(); stack.add(next); render(); }
    private void cancelWork() { generation++; if (scope != null) scope.close(); if (pending != null) pending.cancel(true); }
    private void render() {
        cancelWork(); if (stopped || services == null) return;
        scope = services.addons.newRequest(); ((TextView) findViewById(R.id.page_title)).setText(route().title == null ? route().screen : route().title.name);
        loading();
        switch (route().screen) {
            case "Welcome": welcome(); break;
            case "Home": home(); break;
            case "Search": search(); break;
            case "Library": library(false); break;
            case "History": library(true); break;
            case "Imports": imports(); break;
            case "Settings": settings(); break;
            case "Add-on": addon(); break;
            case "Catalogues": catalogues(); break;
            case "Catalogue": catalogue(); break;
            case "Details": details(); break;
            case "Sources": sources(); break;
            case "Developer": developer(); break;
            default: root("Home"); break;
        }
    }
    private <T> void work(Callable<T> task, Consumer<T> done) {
        int mine = generation;
        pending = app.workers.submit(() -> {
            try { T value = task.call(); main.post(() -> { if (mine == generation && !stopped && !isFinishing()) done.accept(value); }); }
            catch (Exception e) { main.post(() -> { if (mine == generation && !stopped && !isFinishing()) failed(e); }); }
        });
    }
    private void mutate(Callable<?> task) { work(() -> { task.call(); return true; }, ignored -> render()); }
    private void show(List<Row> value) {
        contentReady = false; Route route = route(); String wantedFocus = route.focus; rows.restoreFocus(wantedFocus); rows.set(value);
        int mine = generation;
        if (route.scroll != null) list.getLayoutManager().onRestoreInstanceState(route.scroll);
        list.post(() -> {
            if (mine != generation || stopped) return;
            View target = list.findViewWithTag(wantedFocus);
            if (target != null) target.requestFocus();
            else if (rows.position(wantedFocus) >= 0) {
                list.scrollToPosition(rows.position(wantedFocus));
                list.post(() -> { if (mine == generation && !stopped) { View restored = list.findViewWithTag(wantedFocus); if (restored != null) restored.requestFocus(); } });
            }
            else if (!list.hasFocus() && !findViewById(R.id.navigation).hasFocus()) {
                for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) { list.getChildAt(i).requestFocus(); break; }
            }
            contentReady = true;
        });
    }
    private void loading() { contentReady = false; rows.set(Arrays.asList(Row.text("Loading…", ""), Row.action("cancel", "Cancel", "Return to the previous screen", this::onBackPressed))); }
    private void failed(Throwable e) { show(Arrays.asList(Row.text("Unable to load", Failures.message(e)), Row.action("retry", "Retry", "", () -> { if (services == null) { loading(); initialize(); } else render(); }), Row.action("back", "Back", "", this::onBackPressed))); }
    private void edit(String title, String hint, String initial, Consumer<String> accept) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint(hint); input.setText(initial);
        input.setSelectAllOnFocus(true); input.setContentDescription(title);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString().trim(); if (text.isEmpty()) { input.setError("Enter a value"); return; } dialog.dismiss(); accept.accept(text);
        }));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((v, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return true; } return false; });
        showDialog(dialog);
        // TV keyboards occupy the lower screen and can float over centered dialog buttons.
        dialog.getWindow().setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        android.view.WindowManager.LayoutParams window = dialog.getWindow().getAttributes(); window.y = rows.dp(24); dialog.getWindow().setAttributes(window);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        input.requestFocus();
    }
    private void choose(String title, String[] options, Consumer<Integer> accept) {
        showDialog(new AlertDialog.Builder(this).setTitle(title).setItems(options, (d, which) -> accept.accept(which)).setNegativeButton("Cancel", null).create());
    }
    private void language(String key, String label) {
        String[] names = key.equals("audioLanguage") ? Arrays.copyOf(LANGUAGE_NAMES, LANGUAGE_NAMES.length - 1) : LANGUAGE_NAMES;
        choose(label, names, which -> mutate(() -> { services.store.setPreference(key, LANGUAGES[which]); return null; }));
    }
    private static String languageName(String code) {
        for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i].equals(code)) return LANGUAGE_NAMES[i];
        try { String normalized = java.util.Locale.forLanguageTag(code).getISO3Language(); for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i].equals(normalized)) return LANGUAGE_NAMES[i]; }
        catch (Exception ignored) { /* unknown provider language */ }
        return code;
    }
    private void previewAddon(String address) {
        loading(); work(() -> services.addons.preview(address, scope), manifest -> {
            showDialog(new AlertDialog.Builder(this).setTitle("Install " + manifest.name + "?")
                    .setMessage(manifest.description + (manifest.configurationRequired ? "\nThis add-on requires a configured manifest URL." : "")
                            + "\n\n" + manifest.catalogs.size() + " catalogues. The provider will receive searches and media requests.")
                    .setNegativeButton("Cancel", (d, w) -> render()).setPositiveButton("Install", (d, w) -> mutate(() -> {
                        if (manifest.configurationRequired) throw new AddonException(AddonException.Kind.INVALID_URL, "Configuration required");
                        services.addons.install(address, manifest); return null;
                    })).setOnCancelListener(d -> render()).create());
        });
    }
    private void addAddon() { edit("Add add-on", "https://provider/manifest.json", "", this::previewAddon); }
    private void welcome() {
        show(Arrays.asList(Row.text("Your TV. Your library.", "Browse configured providers or import your own torrents. This TV downloads and plays directly; no desktop helper or debrid account is required."),
                Row.action("cinemeta", "Offer Cinemeta", "Optional film and series metadata. Install only after reviewing the provider.", () -> previewAddon(AddonUrl.CINEMETA)),
                Row.action("source-addon", "Add a source add-on", "Enter your configured manifest address", this::addAddon),
                Row.action("import", "Import a torrent", "Magnet, torrent URL or file", () -> push(new Route("Imports"))),
                Row.action("audio", "Preferred audio language", "", () -> language("audioLanguage", "Audio language")),
                Row.action("subtitles", "Preferred subtitle language", "", () -> language("subtitleLanguage", "Subtitle language")),
                Row.action("continue", "Start watching", "Continue with the services you chose, or use manual imports", () -> work(() -> {
                    services.store.setPreference("onboardingDone", "true"); return true;
                }, ignored -> root("Home")))));
    }
    private List<Row> titleCards(String section, List<Title> titles) {
        List<Row> cards = new ArrayList<>(); for (Title t : titles) cards.add(Row.card(section + ":" + t.key(), t, t.releaseInfo, () -> openTitle(t))); return cards;
    }
    private void openTitle(Title title) { Route r = new Route("Details"); r.title = title; push(r); }
    private void home() {
        Cancellation request = scope;
        work(() -> {
            List<Row> result = new ArrayList<>(); List<TvStore.LibraryEntry> library = services.store.library(false);
            Map<String, Title> known = new LinkedHashMap<>(); for (TvStore.LibraryEntry e : library) known.put(e.title.key(), e.title);
            List<Title> continuing = new ArrayList<>(); for (TvStore.Progress p : services.store.history(true)) if (known.containsKey(p.titleKey) && !continuing.contains(known.get(p.titleKey))) continuing.add(known.get(p.titleKey));
            if (!continuing.isEmpty()) result.add(Row.carousel("continue-watching", "Continue Watching", titleCards("continue", continuing)));
            List<Title> favourites = new ArrayList<>(); for (TvStore.LibraryEntry e : library) if (e.favourite) favourites.add(e.title);
            if (!favourites.isEmpty()) result.add(Row.carousel("favourites", "Favourites", titleCards("favourite", favourites)));
            List<AddonRepository.CatalogQuery> catalogues = services.addons.homeCatalogs();
            if (!catalogues.isEmpty()) {
                AddonRepository.Result<Title> found = services.addons.catalogs(catalogues, request);
                for (AddonRepository.CatalogQuery query : catalogues) {
                    String key = query.addon.id + ":" + query.catalog.type + ":" + query.catalog.id;
                    List<Title> titles = found.byResource.getOrDefault(key, Collections.emptyList());
                    if (!titles.isEmpty()) result.add(Row.carousel("catalogue:" + key, query.catalog.name + " · " + query.addon.manifest.name, titleCards(key, titles)));
                }
                if (!found.failures.isEmpty()) result.add(Row.text(found.stale ? "Showing saved catalogues" : "Some providers are unavailable", "Other results are still available. Refresh to retry."));
            }
            if (result.isEmpty()) result.add(Row.text("Make yourself at home", "Add a catalogue provider in Settings or import a torrent to begin."));
            result.add(Row.action("browse", "Browse catalogues", "Filters and pagination", () -> push(new Route("Catalogues"))));
            result.add(Row.action("refresh", "Refresh", "", this::render)); return result;
        }, this::show);
    }
    private void search() {
        Route current = route(); Cancellation request = scope;
        work(() -> {
            List<Row> result = new ArrayList<>(); result.add(Row.action("query", "Search", current.value.isEmpty() ? "Films, series and local imports" : current.value,
                    () -> edit("Search", "Title", current.value, text -> { current.value = text; current.scroll = null; render(); })));
            if (!current.value.isEmpty()) {
                AddonRepository.Result<Title> found = services.addons.search(current.value, request);
                result.addAll(titleCards("search", found.items));
                if (found.items.isEmpty()) result.add(Row.text("No results", "Try another search or add a searchable catalogue."));
                if (!found.failures.isEmpty()) result.add(Row.text("Some providers could not answer", "Results from the other providers are shown."));
                result.add(Row.action("retry-search", "Refresh search", "", this::render));
            }
            return result;
        }, this::show);
    }
    private void library(boolean history) {
        work(() -> {
            List<Row> result = new ArrayList<>(); List<TvStore.LibraryEntry> entries = services.store.library(false);
            if (history) {
                for (TvStore.Progress p : services.store.history(false)) for (TvStore.LibraryEntry e : entries) if (e.title.key().equals(p.titleKey)) {
                    result.add(Row.action("history:" + e.title.key() + ":" + p.video, e.title.name,
                            (p.watched ? "Watched" : "Resume at " + p.positionMs / 60_000 + " min") + " · " + episodeName(e.title, p.video), () -> { Route next = new Route("Details"); next.title = e.title; next.video = p.video; push(next); }));
                }
            } else for (TvStore.LibraryEntry e : entries) result.add(Row.card("library:" + e.title.key(), e.title, e.favourite ? "Favourite" : "In your library", () -> openTitle(e.title)));
            if (result.isEmpty()) result.add(Row.text(history ? "No watch history yet" : "Your library is empty", "Play a title, add a favourite, or import a torrent."));
            return result;
        }, this::show);
    }
    private static String episodeName(Title title, String video) { for (Title.Episode e : title.episodes) if (e.id.equals(video)) return "S" + e.season + " E" + e.number + " · " + e.name; return title.type.equals("series") ? "Episode" : "Film"; }
    private void imports() {
        work(() -> {
            List<Row> result = new ArrayList<>();
            result.add(Row.action("import-address", "Add magnet or torrent URL", "", () -> edit("Import torrent", "magnet:?xt=… or https://…torrent", "", address -> edit("Name this import", "Title", "Imported torrent", name -> mutate(() -> services.imports.addAddress(name, address))))));
            result.add(Row.action("import-file", "Choose a .torrent file", "Copy metadata into private app storage", () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(intent, PICK_TORRENT); }
                catch (android.content.ActivityNotFoundException unavailable) {
                    showDialog(new AlertDialog.Builder(this).setTitle("Open a torrent file")
                            .setMessage("This TV has no system file picker. Open or share a .torrent file from a TV file manager and choose Daniil TV. You can also add a magnet or torrent URL here.")
                            .setPositiveButton("Close", null).create());
                }
            }));
            for (String id : services.store.importIds()) {
                JSONObject d = services.store.importDescriptor(id);
                Title t = Title.parse("local", new JSONObject().put("id", id).put("type", "movie").put("name", d.optString("name", "Import")));
                result.add(Row.action("import:" + id, t.name, "Open to load metadata and choose a video file", () -> openTitle(t)));
            }
            if (services.store.importIds().isEmpty()) result.add(Row.text("No imports yet", "Importing saves a descriptor. Payload downloads only after you choose Play."));
            return result;
        }, this::show);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_TORRENT && result == RESULT_OK && data != null && data.getData() != null) {
            importFile(data.getData());
        }
    }
    private void details() {
        Route current = route(); Cancellation request = scope; Title original = current.title;
        work(() -> {
            Title title = original;
            String note = "";
            if (!original.provider.equals("local")) {
                AddonRepository.Result<Title> found = services.addons.metadata(original.provider, original.type, original.id, request);
                if (!found.items.isEmpty()) title = found.items.get(0);
                if (!found.failures.isEmpty()) note = "Some metadata is unavailable. Saved information is shown where available.";
            }
            current.title = title; Title chosen = title;
            List<Row> result = new ArrayList<>(); result.add(Row.hero(title));
            if (!title.releaseInfo.isEmpty() || !title.genres.isEmpty()) result.add(Row.text(title.releaseInfo, String.join(" · ", title.genres) + (title.rating.isEmpty() ? "" : " · Rating " + title.rating)));
            if (!note.isEmpty()) result.add(Row.text("Metadata unavailable", note));
            boolean favourite = false; for (TvStore.LibraryEntry e : services.store.library(true)) if (e.title.key().equals(title.key())) favourite = true;
            boolean isFavourite = favourite;
            String video = current.video.isEmpty() ? title.id : current.video;
            if (current.video.isEmpty()) for (TvStore.Progress p : services.store.history(true)) if (p.titleKey.equals(title.key())) { video = p.video; break; }
            if (title.type.equals("series") && video.equals(title.id) && !title.episodes.isEmpty()) video = title.episodes.get(0).id;
            current.video = video; String selectedVideo = video;
            TvStore.Progress progress = services.store.progress(title.key(), video);
            result.add(Row.action("play", progress != null && !progress.watched && progress.positionMs > 0 ? "Resume" : "Play", episodeName(title, video), () -> play(chosen, selectedVideo, "")));
            if (!title.provider.equals("local")) result.add(Row.action("sources", "Sources", "Choose a release or provider", () -> sourcePage(chosen, selectedVideo)));
            result.add(Row.action("favourite", isFavourite ? "Remove favourite" : "Add favourite", "", () -> mutate(() -> { services.store.favourite(chosen, !isFavourite); return null; })));
            result.add(Row.action("watched", progress != null && progress.watched ? "Mark unwatched" : "Mark watched", episodeName(title, video), () -> mutate(() -> { services.store.watched(chosen, selectedVideo, progress == null || !progress.watched); return null; })));
            if (!title.cast.isEmpty()) result.add(Row.text("Cast", String.join(", ", title.cast)));
            if (title.type.equals("series")) {
                java.util.SortedSet<Integer> seasons = new java.util.TreeSet<>(); for (Title.Episode e : title.episodes) seasons.add(e.season);
                if (current.season == -1 && !seasons.isEmpty()) current.season = seasons.first();
                for (int season : seasons) result.add(Row.action("season:" + season, "Season " + season, season == current.season ? "Selected" : "", () -> { current.season = season; current.scroll = null; render(); }));
                for (Title.Episode e : title.episodes) if (e.season == current.season) result.add(Row.action("episode:" + e.id, "Episode " + e.number + " · " + e.name, e.overview,
                        () -> choose(e.name, new String[]{"Play / Resume", "Choose source", "Mark watched", "Mark unwatched"}, which -> {
                            current.video = e.id;
                            if (which == 0) play(chosen, e.id, ""); else if (which == 1) sourcePage(chosen, e.id);
                            else mutate(() -> { services.store.watched(chosen, e.id, which == 2); return null; });
                        })));
                if (title.episodes.isEmpty()) result.add(Row.text("No episodes supplied", "Refresh metadata or choose another provider."));
            }
            if (title.provider.equals("local")) {
                result.add(Row.action("rename", "Rename import", "", () -> edit("Rename import", "Title", chosen.name, name -> mutate(() -> { services.imports.rename(chosen.id, name); current.title = Title.parse("local", new JSONObject().put("id", chosen.id).put("type", chosen.type).put("name", name)); return null; }))));
                result.add(Row.action("remove-import", "Remove import", "Remove its descriptor and history", () -> work(() -> { services.imports.remove(chosen.id); return true; }, ignored -> onBackPressed())));
            } else result.add(Row.action("remove-library", "Remove from library and history", "", () -> work(() -> { services.store.removeLibrary(chosen.key()); return true; }, ignored -> root("Library"))));
            result.add(Row.action("refresh-details", "Refresh details", "", this::render)); return result;
        }, this::show);
    }
    private void sourcePage(Title title, String video) { Route r = new Route("Sources"); r.title = title; r.video = video; push(r); }
    private void sources() {
        Route current = route(); Cancellation request = scope;
        work(() -> {
            AddonRepository.Result<MediaSource> found = services.addons.streams(current.title.type, current.video, request);
            List<Row> result = new ArrayList<>();
            Map<String, String> names = new LinkedHashMap<>(); for (InstalledAddon a : services.store.addons()) names.put(a.id, a.manifest.name);
            for (MediaSource source : found.items) {
                SourcePolicy.Assessment assessment = SourcePolicy.assess(source);
                String detail = names.getOrDefault(source.provider, "Provider") + " · " + source.kind + " · " + assessment.label
                        + (source.videoSize > 0 ? " · " + source.videoSize / (1 << 20) + " MiB" : "") + "\n" + source.description;
                result.add(Row.action("source:" + SourcePolicy.key(source), source.name, detail, () -> {
                    if (!assessment.usable) showDialog(new AlertDialog.Builder(this).setTitle("Source unavailable").setMessage(assessment.label).setPositiveButton("OK", null).create());
                    else play(current.title, current.video, SourcePolicy.key(source));
                }));
            }
            if (found.items.isEmpty()) result.add(Row.text("No sources found", "Add or enable a compatible source add-on, then refresh."));
            if (!found.failures.isEmpty()) result.add(Row.text("Some providers are unavailable", "Other sources are still available."));
            result.add(Row.action("refresh-sources", "Refresh sources", "", this::render)); return result;
        }, this::show);
    }
    private void play(Title title, String video, String source) {
        work(() -> { services.store.remember(title); return true; }, ignored -> startActivity(WatchActivity.intent(this, title, video, source)));
    }
    private void catalogues() {
        work(() -> {
            List<Row> result = new ArrayList<>();
            for (InstalledAddon a : services.store.addons()) if (a.enabled) for (AddonManifest.Catalog c : a.manifest.catalogs) result.add(Row.action("catalogue:" + a.id + c.type + c.id, c.name, a.manifest.name + " · " + c.type, () -> {
                Route next = new Route("Catalogue"); next.value = new JSONArray().put(a.id).put(c.type).put(c.id).toString(); push(next);
            }));
            if (result.isEmpty()) result.add(Row.text("No catalogues", "Install a catalogue add-on in Settings.")); return result;
        }, this::show);
    }
    private void catalogue() {
        Route current = route(); Cancellation request = scope;
        work(() -> {
            JSONArray identity = new JSONArray(current.value); InstalledAddon addon = null; AddonManifest.Catalog catalog = null;
            for (InstalledAddon a : services.store.addons()) if (a.id.equals(identity.getString(0))) { addon = a; for (AddonManifest.Catalog c : a.manifest.catalogs) if (c.type.equals(identity.getString(1)) && c.id.equals(identity.getString(2))) catalog = c; }
            if (addon == null || catalog == null || !addon.enabled) return Arrays.asList(Row.text("Catalogue unavailable", "The add-on was disabled or removed."));
            List<Row> result = new ArrayList<>(); boolean complete = true;
            for (AddonManifest.Extra e : catalog.extras) if (!e.name.equals("skip")) {
                if (e.required && !current.extras.containsKey(e.name)) complete = false;
                result.add(Row.action("filter:" + e.name, e.name + (e.required ? " (required)" : ""), current.extras.getOrDefault(e.name, "Any"), () -> {
                    Consumer<String> set = value -> { if (value.isEmpty()) current.extras.remove(e.name); else current.extras.put(e.name, value); current.page = 0; render(); };
                    if (e.options.isEmpty()) edit(e.name, "Value", current.extras.getOrDefault(e.name, ""), set);
                    else { List<String> values = new ArrayList<>(e.options); if (!e.required) values.add(0, "Any"); choose(e.name, values.toArray(new String[0]), i -> set.accept(!e.required && i == 0 ? "" : values.get(i))); }
                }));
            }
            if (complete) {
                Map<String, String> extras = new LinkedHashMap<>(current.extras); if (catalog.supports("skip")) extras.put("skip", Integer.toString(current.page * 20));
                AddonRepository.Result<Title> found = services.addons.catalogs(Collections.singletonList(new AddonRepository.CatalogQuery(addon, catalog, extras)), request);
                result.addAll(titleCards("catalogue", found.items));
                if (found.items.isEmpty()) result.add(Row.text("No titles on this page", "Change filters or return to the previous page."));
                if (!found.failures.isEmpty()) result.add(Row.text("Provider unavailable", found.stale ? "Showing saved entries." : "Try refreshing this catalogue."));
                if (catalog.supports("skip")) {
                    if (current.page > 0) result.add(Row.action("previous-page", "Previous page", "", () -> { current.page--; current.scroll = null; render(); }));
                    if (!found.items.isEmpty()) result.add(Row.action("next-page", "Next page", "", () -> { current.page++; current.scroll = null; render(); }));
                }
            } else result.add(Row.text("Choose the required filters", "Then this catalogue will load."));
            result.add(Row.action("refresh-catalogue", "Refresh", "", this::render)); return result;
        }, this::show);
    }
    private void settings() {
        work(() -> {
            List<Row> result = new ArrayList<>(); result.add(Row.action("add-addon", "Add add-on", "Review a manifest before installing", this::addAddon));
            for (InstalledAddon a : services.store.addons()) result.add(Row.action("addon:" + a.id, a.manifest.name, a.enabled ? "Enabled" : "Disabled", () -> { Route r = new Route("Add-on"); r.value = a.id; push(r); }));
            result.add(Row.action("audio-language", "Audio language", languageName(services.store.preference("audioLanguage", "")), () -> language("audioLanguage", "Audio language")));
            result.add(Row.action("subtitle-language", "Subtitle language", languageName(services.store.preference("subtitleLanguage", "")), () -> language("subtitleLanguage", "Subtitle language")));
            for (String setting : new String[]{"size", "color", "edge", "position"}) result.add(Row.action("subtitle-" + setting, "Subtitle " + setting, services.playerPreferences.getString("subtitle_" + setting, "Default"), () -> {
                String[] values = setting.equals("size") ? new String[]{"small", "medium", "large", "xlarge"} : setting.equals("color") ? new String[]{"white", "yellow", "cyan"} : setting.equals("edge") ? new String[]{"outline", "shadow", "box"} : new String[]{"low", "standard", "raised"};
                choose("Subtitle " + setting, values, i -> mutate(() -> { services.playerPreferences.edit().putString("subtitle_" + setting, values[i]).apply(); services.store.setPreference("subtitle_" + setting, values[i]); return null; }));
            }));
            result.add(Row.action("cache-size", "Playback cache", services.store.preference("cacheMiB", "256") + " MiB", () -> choose("Playback cache", new String[]{"128 MiB", "256 MiB", "512 MiB"}, i -> mutate(() -> { services.store.setPreference("cacheMiB", new String[]{"128", "256", "512"}[i]); return null; }))));
            result.add(Row.action("autoplay", "Play next episode", services.store.preference("autoplay", "true").equals("true") ? "On" : "Off", () -> mutate(() -> { services.store.setPreference("autoplay", services.store.preference("autoplay", "true").equals("true") ? "false" : "true"); return null; })));
            result.add(Row.action("clear-cache", "Clear app playback and metadata cache", "Preserves imports, favourites and watch history", () -> mutate(() -> { services.store.clearMetadata(); delete(new File(getCacheDir(), "payload")); delete(new File(getCacheDir(), "subtitles")); return null; })));
            result.add(Row.action("developer", "Developer", "Diagnostics, version and storage", () -> push(new Route("Developer")))); return result;
        }, this::show);
    }
    private void addon() {
        work(() -> {
            List<InstalledAddon> addons = services.store.addons(); InstalledAddon addon = null; for (InstalledAddon a : addons) if (a.id.equals(route().value)) addon = a;
            if (addon == null) return Arrays.asList(Row.text("Add-on removed", "Return to Settings."));
            InstalledAddon a = addon; List<Row> result = new ArrayList<>();
            result.add(Row.text(a.manifest.name, a.manifest.description));
            result.add(Row.action("enable", a.enabled ? "Disable add-on" : "Enable add-on", "", () -> mutate(() -> { services.addons.enable(a.id, !a.enabled); return null; })));
            for (int delta : new int[]{-1, 1}) result.add(Row.action("move:" + delta, delta < 0 ? "Move earlier" : "Move later", "", () -> mutate(() -> {
                List<String> order = new ArrayList<>(); for (InstalledAddon item : services.store.addons()) order.add(item.id); int position = order.indexOf(a.id), next = position + delta;
                if (next >= 0 && next < order.size()) { Collections.swap(order, position, next); services.addons.reorder(order); } return null;
            })));
            for (AddonManifest.Catalog c : a.manifest.catalogs) {
                boolean visible = services.store.catalogVisible(a.id, c.type, c.id);
                result.add(Row.action("visibility:" + c.type + c.id, c.name, visible ? "Shown on Home" : "Hidden from Home", () -> mutate(() -> { services.addons.visible(a.id, c.type, c.id, !visible); return null; })));
            }
            result.add(Row.action("remove", "Remove add-on", "Library and watch history are preserved", () -> work(() -> { services.addons.remove(a.id); return true; }, ignored -> onBackPressed()))); return result;
        }, this::show);
    }
    private void developer() {
        work(() -> {
            List<Row> result = new ArrayList<>();
            result.add(Row.text("Daniil TV " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName, tv.daniil.torrent.TorrentEngine.version()));
            result.add(Row.text("Device", android.os.Build.MODEL + " · API " + android.os.Build.VERSION.SDK_INT + " · " + Arrays.toString(android.os.Build.SUPPORTED_ABIS)));
            result.add(Row.text("Storage", getFilesDir().getUsableSpace() / (1 << 20) + " MiB free · payload cache " + services.cacheBytes() / (1 << 20) + " MiB · 512 MiB reserve"));
            for (InstalledAddon a : services.store.addons()) result.add(Row.text(a.manifest.name, a.address.toString()));
            result.add(Row.text("Third-party software", "Media3 (Apache-2.0), libtorrent and Boost (BSD/Boost), OpenSSL (Apache-2.0). Full notices are included with the application."));
            result.add(Row.action("notices", "Open source notices", "Licenses for the included dependencies", this::notices));
            return result;
        }, this::show);
    }
    private void notices() {
        String[] files = {"androidx.txt", "libtorrent.txt", "boost.txt", "openssl.txt", "ed25519.txt", "try-signal.txt"};
        choose("Open source notices", new String[]{"AndroidX / Media3", "libtorrent", "Boost", "OpenSSL", "Ed25519", "try_signal"}, which -> work(() -> {
            try (java.io.InputStream input = getAssets().open("notices/" + files[which]); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return bytes.toString("UTF-8");
            }
        }, text -> {
            android.widget.ScrollView scroll = new android.widget.ScrollView(this); TextView content = Rows.text(this, text, 14, 0xffd8e4eb);
            content.setTextIsSelectable(true); scroll.addView(content);
            showDialog(new AlertDialog.Builder(this).setTitle("Open source notices").setView(scroll).setPositiveButton("Close", (d, w) -> render()).setOnCancelListener(d -> render()).create());
        }));
    }
    private static void delete(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); file.delete(); }
    @Override public void onBackPressed() {
        if (stack.size() > 1) { cancelWork(); stack.remove(stack.size() - 1); render(); } else finish();
    }
    @Override protected void onStop() { super.onStop(); stopped = true; cancelWork(); artwork.cancel(); }
    @Override protected void onStart() { super.onStart(); boolean returning = stopped; stopped = false; if (returning) { if (services == null) initialize(); else render(); } }
    @Override protected void onDestroy() { cancelWork(); for (android.app.Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); artwork.close(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); rememberFocus(); JSONArray routes = new JSONArray();
        out.putBoolean("incomingHandled", incomingHandled);
        try { for (Route r : stack) {
            JSONObject data = new JSONObject().put("screen", r.screen).put("value", r.value).put("video", r.video).put("season", r.season).put("page", r.page).put("focus", r.focus).put("extras", new JSONObject(r.extras));
            if (r.title != null) data.put("title", new JSONObject().put("provider", r.title.provider).put("id", r.title.id).put("type", r.title.type).put("name", r.title.name)); routes.put(data);
        } out.putString("routes", routes.toString()); } catch (Exception ignored) { /* keep normal activity state */ }
    }
    private void restore(String text) {
        try { JSONArray saved = new JSONArray(text); for (int i = 0; i < saved.length(); i++) {
            JSONObject data = saved.getJSONObject(i); Route r = new Route(data.getString("screen")); r.value = data.optString("value"); r.video = data.optString("video"); r.focus = data.optString("focus"); r.season = data.optInt("season", -1); r.page = data.optInt("page");
            JSONObject extras = data.optJSONObject("extras"); if (extras != null) { java.util.Iterator<String> keys = extras.keys(); while (keys.hasNext()) { String key = keys.next(); r.extras.put(key, extras.getString(key)); } }
            JSONObject title = data.optJSONObject("title"); if (title != null) r.title = Title.parse(title.getString("provider"), title); stack.add(r);
        } } catch (Exception ignored) { stack.clear(); }
    }
}
