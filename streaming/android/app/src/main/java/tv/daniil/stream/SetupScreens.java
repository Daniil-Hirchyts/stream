package tv.daniil.stream;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tv.daniil.playback.SubtitleStyle;
import tv.daniil.stream.addon.AddonManifest;
import tv.daniil.stream.addon.AddonUrl;
import tv.daniil.stream.addon.Title;
import tv.daniil.stream.data.InstalledAddon;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Item.Action;
import tv.daniil.stream.ui.Page;
import tv.daniil.stream.ui.SubtitleFonts;

/** Onboarding, Imports, Settings, add-on management and Developer diagnostics. */
final class SetupScreens {
    static final String[] LANGUAGE_NAMES = {"Automatic", "English", "Russian", "French", "German", "Spanish", "Italian", "Ukrainian", "Japanese", "Off"};
    static final String[] LANGUAGES = {"", "eng", "rus", "fra", "deu", "spa", "ita", "ukr", "jpn", "off"};
    private final Context context;
    private final Navigator nav;
    private final TvApplication.Services services;

    SetupScreens(Context context, Navigator nav, TvApplication.Services services) { this.context = context; this.nav = nav; this.services = services; }

    Page imports() throws Exception {
        Page page = new Page().title("Imports").narrow();
        page.add(Item.row("import-address", 0, "Add magnet or torrent URL", "", nav::importAddress));
        page.add(Item.row("import-file", 0, "Choose a .torrent file", "", nav::importFile));
        List<String> ids = services.store.importIds();
        if (!ids.isEmpty()) page.add(Item.header("Your imports"));
        for (String id : ids) {
            JSONObject d = services.store.importDescriptor(id);
            Title t = Title.parse("local", new JSONObject().put("id", id).put("type", "movie").put("name", d.optString("name", "Import")));
            page.add(Item.row("import:" + id, 0, t.name, d.has("fileIdx") ? "File chosen" : "", () -> nav.openTitle(t, "")));
        }
        return page;
    }

    /**
     * Settings as a split view: seven categories on the left, the focused one's rows on the right. Each row keeps its
     * label; the categories only group them. The category shown is remembered on the route.
     */
    Page settings(Route route) {
        Map<String, List<Item>> panes = new LinkedHashMap<>();
        List<Item> categories = new ArrayList<>();

        List<Item> addons = new ArrayList<>();
        for (InstalledAddon a : services.store.addons()) {
            addons.add(Item.row("addon:" + a.id, 0, a.manifest.name, a.enabled ? "" : "Off", () -> {
                Route r = new Route("Add-on"); r.value = a.id; nav.push(r);
            }));
        }
        addons.add(Item.row("addon-store", 0, "Add-on store", "", () -> nav.push(StoreScreens.route("All"))));
        addons.add(Item.row("add-addon", 0, "Add add-on", "By address", nav::addAddon));
        category(categories, panes, "settings:addons", R.drawable.ic_tv_extension, "Add-ons", addons);

        int importCount = services.store.importIds().size();
        category(categories, panes, "settings:torrents", R.drawable.ic_tv_download, "Torrents", Collections.singletonList(
                Item.row("manage-imports", 0, "Imports", importCount == 0 ? "" : Integer.toString(importCount), nav::openImports)));

        category(categories, panes, "settings:languages", R.drawable.ic_tv_audio, "Languages", Arrays.asList(
                Item.row("audio-language", 0, "Audio language", languageName(services.store.preference("audioLanguage", "")),
                        () -> language("audioLanguage", "Audio language")),
                Item.row("subtitle-language", 0, "Subtitle language", languageName(services.store.preference("subtitleLanguage", "")),
                        () -> language("subtitleLanguage", "Subtitle language")),
                Item.row("keyboard-languages", 0, "Keyboard languages", keyboardLanguageNames(),
                        () -> nav.push(new Route("Keyboard languages")))));

        List<Item> subtitles = new ArrayList<>();
        subtitles.add(Item.subtitlePreview("subtitle-preview", services.playerPreferences));
        SubtitleStyle subtitleStyle = new SubtitleStyle(services.playerPreferences);
        for (SubtitleStyle.Setting s : SubtitleStyle.Setting.values()) {
            String setting = s.key;
            String[] values = s == SubtitleStyle.Setting.FONT ? SubtitleFonts.VALUES : s.values;
            int currentIndex = s == SubtitleStyle.Setting.FONT ? SubtitleFonts.selected(services.playerPreferences) : subtitleStyle.get(s);
            String[] labels = s == SubtitleStyle.Setting.FONT ? SubtitleFonts.labels(context) : new String[s.labels.length];
            if (s != SubtitleStyle.Setting.FONT) for (int i = 0; i < labels.length; i++) labels[i] = context.getString(s.labels[i]);
            String title = "Subtitle " + context.getString(s.title).toLowerCase(java.util.Locale.ROOT);
            subtitles.add(Item.row("subtitle-" + setting, 0, title, labels[currentIndex], () -> {
                nav.chooseWithPreview(title, null, labels, currentIndex, i -> nav.mutate(() -> {
                    services.playerPreferences.edit().putString("subtitle_" + setting, values[i]).apply();
                    services.store.setPreference("subtitle_" + setting, values[i]);
                    return null;
                }), i -> nav.previewSubtitle(s, i));
            }));
        }
        category(categories, panes, "settings:subtitles", R.drawable.ic_tv_subtitles, "Subtitles", subtitles);

        boolean autoplay = services.store.preference("autoplay", "true").equals("true"), sounds = soundsOn(services);
        category(categories, panes, "settings:playback", R.drawable.ic_tv_play, "Playback", Arrays.asList(
                Item.row("cache-size", 0, "Playback cache", services.store.preference("cacheMiB", "256") + " MiB",
                        () -> nav.choose("Playback cache", new String[]{"128 MiB", "256 MiB", "512 MiB"},
                                i -> nav.mutate(() -> { services.store.setPreference("cacheMiB", new String[]{"128", "256", "512"}[i]); return null; }))),
                Item.row("autoplay", 0, "Play next episode", autoplay ? "On" : "Off",
                        () -> nav.mutate(() -> { services.store.setPreference("autoplay", autoplay ? "false" : "true"); return null; })),
                Item.row("interface-sounds", 0, "Interface sounds", sounds ? "On" : "Off", () -> nav.mutate(() -> {
                    services.store.setPreference("interfaceSounds", sounds ? "false" : "true");
                    tv.daniil.stream.ui.Sounds.get(context).setEnabled(!sounds); return null;
                })),
                // Clearing keeps imports, favourites and watch history.
                Item.row("clear-cache", 0, "Clear app playback and metadata cache", "",
                        () -> nav.mutate(() -> { clearCaches(context, services); return null; }))));

        String clock = clockFormat(services);
        boolean playerClock = playerClock(services);
        category(categories, panes, "settings:clock", R.drawable.ic_tv_settings, "Clock", Arrays.asList(
                Item.row("clock", 0, "Clock", tv.daniil.stream.ui.Clock.name(clock),
                        () -> nav.choose("Clock", tv.daniil.stream.ui.Clock.NAMES,
                                i -> nav.mutate(() -> { services.store.setPreference("clock", tv.daniil.stream.ui.Clock.VALUES[i]); return null; }))),
                Item.row("player-clock", 0, "Clock in player", playerClock ? "On" : "Off",
                        () -> nav.mutate(() -> { services.store.setPreference("playerClock", playerClock ? "false" : "true"); return null; }))));

        boolean developerMode = developerMode(services);
        List<Item> about = new ArrayList<>();
        about.add(Item.row("developer-mode", 0, "Developer mode", developerMode ? "On" : "Off", () -> nav.mutate(() -> {
            services.store.setPreference("developerMode", developerMode ? "false" : "true");
            if (developerMode) ((TvApplication) context.getApplicationContext()).lastErrorReport = null;
            return null;
        })));
        if (developerMode) about.add(Item.row("developer", 0, "Developer", "", () -> nav.push(new Route("Developer"))));
        category(categories, panes, "settings:about", R.drawable.ic_tv_info, "About", about);

        return new Page().add(Item.split("settings", categories, panes, route.extras.getOrDefault("category", ""),
                key -> route.extras.put("category", key)));
    }
    private static void category(List<Item> categories, Map<String, List<Item>> panes, String key, int icon, String title, List<Item> rows) {
        categories.add(Item.row(key, icon, title, "", null));
        panes.put(key, rows);
    }
    private String keyboardLanguageNames() {
        List<String> names = new ArrayList<>();
        for (String code : tv.daniil.stream.ui.KeyboardLanguages.parse(services.store.preference("keyboardLanguages", "en")))
            names.add(tv.daniil.stream.ui.KeyboardLanguages.name(code));
        return String.join(", ", names);
    }
    Page keyboardLanguages() {
        List<String> enabled = tv.daniil.stream.ui.KeyboardLanguages.parse(services.store.preference("keyboardLanguages", "en"));
        Page page = new Page().title("Keyboard languages").narrow();
        page.add(Item.text("", "Choose one or more languages. Use the language key to switch while searching."));
        for (String code : tv.daniil.stream.ui.KeyboardLanguages.CODES) {
            boolean selected = enabled.contains(code);
            page.add(Item.row("keyboard-language:" + code, selected ? R.drawable.ic_tv_check : 0,
                    tv.daniil.stream.ui.KeyboardLanguages.name(code), selected ? "On" : "", () -> {
                if (selected && enabled.size() == 1) return;
                List<String> next = new ArrayList<>(enabled);
                if (selected) next.remove(code); else next.add(code);
                nav.mutate(() -> { services.store.setPreference("keyboardLanguages", String.join(",", next)); return null; });
            }));
        }
        return page;
    }
    /** The viewer's interface sounds switch (on unless turned off). Reads the store: call on a worker. */
    static boolean soundsOn(TvApplication.Services services) { return !services.store.preference("interfaceSounds", "true").equals("false"); }
    /** The clock's format ("", "24", "12" or "off"); reads the store, so call on a worker. */
    static String clockFormat(TvApplication.Services services) { return services.store.preference("clock", ""); }
    /** Whether the player overlay shows the clock too (on unless turned off). Reads the store: call on a worker. */
    static boolean playerClock(TvApplication.Services services) { return !services.store.preference("playerClock", "true").equals("false"); }
    /** Diagnostics are opt-in. Read the encrypted store on an application worker. */
    static boolean developerMode(TvApplication.Services services) { return services.store.preference("developerMode", "false").equals("true"); }
    static void clearCaches(Context context, TvApplication.Services services) {
        services.addons.cancelRequests();
        services.store.clearMetadata();
        delete(new File(context.getCacheDir(), "payload"));
        delete(new File(context.getCacheDir(), "subtitles"));
        delete(new File(context.getCacheDir(), "artwork"));
    }

    Page addon(Route route) {
        InstalledAddon addon = null;
        for (InstalledAddon a : services.store.addons()) if (a.id.equals(route.value)) addon = a;
        if (addon == null) return new Page().title("Add-on").narrow().add(Item.message(R.drawable.ic_tv_extension, "Add-on removed", ""));
        InstalledAddon a = addon;
        Page page = new Page().title(a.manifest.name).narrow();
        page.add(Item.text("", StoreScreens.kinds(a.manifest)));
        page.add(Item.row("enable", 0, a.enabled ? "Disable add-on" : "Enable add-on", a.enabled ? "On" : "Off",
                () -> nav.mutate(() -> { services.addons.enable(a.id, !a.enabled); return null; })));
        page.add(Item.row("move:-1", 0, "Move earlier", "", () -> move(a.id, -1)));
        page.add(Item.row("move:1", 0, "Move later", "", () -> move(a.id, 1)));
        if (!a.manifest.catalogs.isEmpty()) page.add(Item.header("Home rows"));
        for (AddonManifest.Catalog c : a.manifest.catalogs) {
            boolean visible = services.store.catalogVisible(a.id, c.type, c.id);
            page.add(Item.row("visibility:" + c.type + c.id, 0, BrowseScreens.shelfTitle(c), visible ? "Shown" : "Hidden",
                    () -> nav.mutate(() -> { services.addons.visible(a.id, c.type, c.id, !visible); return null; })));
        }
        page.add(Item.header(""));
        page.add(Item.row("remove", 0, "Remove add-on", "",
                () -> nav.then(() -> { services.addons.remove(a.id); return null; }, nav::back)));
        return page;
    }
    private void move(String id, int delta) {
        nav.mutate(() -> {
            List<String> order = new java.util.ArrayList<>();
            for (InstalledAddon item : services.store.addons()) order.add(item.id);
            int position = order.indexOf(id), next = position + delta;
            if (next >= 0 && next < order.size()) { Collections.swap(order, position, next); services.addons.reorder(order); }
            return null;
        });
    }

    Page developer() throws Exception {
        Page page = new Page().title("Developer").narrow();
        if (!developerMode(services)) return page.add(Item.text("Developer mode is off", "Enable it in Settings → About."));
        if (((TvApplication) context.getApplicationContext()).lastErrorReport != null)
            page.add(Item.row("last-error-report", 0, "Last error report", "Scan QR", nav::errorReport));
        else page.add(Item.text("Error reports", "A QR report appears when a problem occurs with Developer mode on. Reports are kept only until the app closes or Developer mode is turned off."));
        page.add(Item.text("Version", context.getString(R.string.app_name) + " " + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName
                + "\n" + tv.daniil.torrent.TorrentEngine.version()));
        page.add(Item.text("Device", android.os.Build.MODEL + " · Android API " + android.os.Build.VERSION.SDK_INT + " · " + String.join(", ", android.os.Build.SUPPORTED_ABIS)));
        page.add(Item.text("Storage", context.getFilesDir().getUsableSpace() / (1 << 20) + " MiB free · " + services.cacheBytes() / (1 << 20)
                + " MiB playback cache · 512 MiB kept free"));
        for (InstalledAddon a : services.store.addons()) page.add(Item.text(a.manifest.name, a.address.toString()));
        page.add(Item.text("Third-party software", "Media3 and ZXing (Apache-2.0), libtorrent and Boost (BSD/Boost), OpenSSL (Apache-2.0)."));
        page.add(Item.row("notices", 0, "Open source notices", "", nav::notices));
        return page;
    }

    void language(String key, String title) {
        String[] names = key.equals("audioLanguage") ? Arrays.copyOf(LANGUAGE_NAMES, LANGUAGE_NAMES.length - 1) : LANGUAGE_NAMES;
        nav.choose(title, names, which -> nav.mutate(() -> { services.store.setPreference(key, LANGUAGES[which]); return null; }));
    }
    static String languageName(String code) {
        for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i].equals(code)) return LANGUAGE_NAMES[i];
        try {
            String normalized = java.util.Locale.forLanguageTag(code).getISO3Language();
            for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i].equals(normalized)) return LANGUAGE_NAMES[i];
        } catch (Exception ignored) { /* unknown provider language */ }
        return code;
    }
    static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
