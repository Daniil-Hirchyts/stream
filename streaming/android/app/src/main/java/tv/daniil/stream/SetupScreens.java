package tv.daniil.stream;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import tv.daniil.stream.addon.AddonManifest;
import tv.daniil.stream.addon.AddonUrl;
import tv.daniil.stream.addon.Title;
import tv.daniil.stream.data.InstalledAddon;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Item.Action;
import tv.daniil.stream.ui.Page;

/** Onboarding, Imports, Settings, add-on management and Developer diagnostics. */
final class SetupScreens {
    static final String[] LANGUAGE_NAMES = {"Automatic", "English", "Russian", "French", "German", "Spanish", "Italian", "Ukrainian", "Japanese", "Off"};
    static final String[] LANGUAGES = {"", "eng", "rus", "fra", "deu", "spa", "ita", "ukr", "jpn", "off"};
    private static final String[][] SUBTITLE_STYLES = {
            {"size", "small", "medium", "large", "xlarge"}, {"color", "white", "yellow", "cyan"},
            {"edge", "outline", "shadow", "box"}, {"position", "low", "standard", "raised"}};
    private final Context context;
    private final Navigator nav;
    private final TvApplication.Services services;

    SetupScreens(Context context, Navigator nav, TvApplication.Services services) { this.context = context; this.nav = nav; this.services = services; }

    /** One screen and one choice: Start watching adds the listed starter add-ons and opens Home. */
    Page welcome() {
        Page page = new Page().narrow();
        page.add(Item.intro("", "Daniil TV", "Films and series, ready to watch."));
        List<String> starters = RuntimeOptions.starterAddons(context);
        if (!starters.isEmpty()) {
            page.add(Item.header("Included"));
            for (String url : starters) {
                String[] about = StarterAddons.describe(url);
                page.add(Item.row("starter:" + url, R.drawable.ic_tv_check, about[0], about[1], null));
            }
        }
        page.add(Item.actions("welcome-start", new Action("continue", Action.Style.PRIMARY, "Start watching", () -> nav.startWatching(starters))
                .icon(R.drawable.ic_tv_play)).centered());
        page.initialFocus = "continue";
        return page;
    }

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

    /** tvOS Settings: plain rows in a centred column, grouped under small headings. Installed add-ons come first. */
    Page settings() {
        Page page = new Page().narrow();
        page.add(Item.header("Add-ons"));
        for (InstalledAddon a : services.store.addons()) {
            page.add(Item.row("addon:" + a.id, 0, a.manifest.name, a.enabled ? "" : "Off", () -> {
                Route r = new Route("Add-on"); r.value = a.id; nav.push(r);
            }));
        }
        page.add(Item.row("addon-store", 0, "Add-on store", "", () -> nav.push(StoreScreens.route("All"))));
        page.add(Item.row("add-addon", 0, "Add add-on", "By address", nav::addAddon));
        int importCount = services.store.importIds().size();
        page.add(Item.header("Library"));
        page.add(Item.row("manage-imports", 0, "Imports", importCount == 0 ? "" : Integer.toString(importCount), nav::openImports));
        page.add(Item.header("Languages"));
        page.add(Item.row("audio-language", 0, "Audio language", languageName(services.store.preference("audioLanguage", "")),
                () -> language("audioLanguage", "Audio language")));
        page.add(Item.row("subtitle-language", 0, "Subtitle language", languageName(services.store.preference("subtitleLanguage", "")),
                () -> language("subtitleLanguage", "Subtitle language")));
        page.add(Item.header("Subtitles"));
        for (String[] style : SUBTITLE_STYLES) {
            String setting = style[0];
            String[] values = Arrays.copyOfRange(style, 1, style.length);
            String current = services.playerPreferences.getString("subtitle_" + setting, "");
            page.add(Item.row("subtitle-" + setting, 0, "Subtitle " + setting, current.isEmpty() ? "Default" : label(current), () -> {
                String[] labels = new String[values.length];
                for (int i = 0; i < values.length; i++) labels[i] = label(values[i]);
                nav.choose("Subtitle " + setting, labels, i -> nav.mutate(() -> {
                    services.playerPreferences.edit().putString("subtitle_" + setting, values[i]).apply();
                    services.store.setPreference("subtitle_" + setting, values[i]);
                    return null;
                }));
            }));
        }
        page.add(Item.header("Playback"));
        page.add(Item.row("cache-size", 0, "Playback cache", services.store.preference("cacheMiB", "256") + " MiB",
                () -> nav.choose("Playback cache", new String[]{"128 MiB", "256 MiB", "512 MiB"},
                        i -> nav.mutate(() -> { services.store.setPreference("cacheMiB", new String[]{"128", "256", "512"}[i]); return null; }))));
        boolean autoplay = services.store.preference("autoplay", "true").equals("true");
        page.add(Item.row("autoplay", 0, "Play next episode", autoplay ? "On" : "Off",
                () -> nav.mutate(() -> { services.store.setPreference("autoplay", autoplay ? "false" : "true"); return null; })));
        // Clearing keeps imports, favourites and watch history.
        page.add(Item.row("clear-cache", 0, "Clear app playback and metadata cache", "",
                () -> nav.mutate(() -> { clearCaches(context, services); return null; })));
        page.add(Item.header("About"));
        page.add(Item.row("developer", 0, "Developer", "", () -> nav.push(new Route("Developer"))));
        return page;
    }
    /** Removes only this app's disposable payload/subtitle files and cached metadata. */
    static void clearCaches(Context context, TvApplication.Services services) {
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
        page.add(Item.text("Version", "Daniil TV " + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName
                + "\n" + tv.daniil.torrent.TorrentEngine.version()));
        page.add(Item.text("Device", android.os.Build.MODEL + " · Android API " + android.os.Build.VERSION.SDK_INT + " · " + String.join(", ", android.os.Build.SUPPORTED_ABIS)));
        page.add(Item.text("Storage", context.getFilesDir().getUsableSpace() / (1 << 20) + " MiB free · " + services.cacheBytes() / (1 << 20)
                + " MiB playback cache · 512 MiB kept free"));
        for (InstalledAddon a : services.store.addons()) page.add(Item.text(a.manifest.name, a.address.toString()));
        page.add(Item.text("Third-party software", "Media3 (Apache-2.0), libtorrent and Boost (BSD/Boost), OpenSSL (Apache-2.0)."));
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
    private static String label(String value) { return value.equals("xlarge") ? "Extra large" : BrowseScreens.capitalise(value); }
    static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
