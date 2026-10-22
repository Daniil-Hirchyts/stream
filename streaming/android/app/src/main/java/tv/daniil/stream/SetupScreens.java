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

    Page welcome() {
        Page page = new Page();
        page.add(Item.intro("Welcome to Daniil TV", "Your TV. Your library.",
                "Browse catalogues from add-ons you choose, or bring your own torrents. This TV downloads and plays everything itself: no computer, home server or debrid account."));
        page.add(Item.actions("welcome-options",
                new Action("cinemeta", Action.Style.TILE, "Add Cinemeta", () -> nav.previewAddon(AddonUrl.CINEMETA)).icon(R.drawable.ic_tv_movie)
                        .detail("Free film and series catalogues. You review it before it installs."),
                new Action("source-addon", Action.Style.TILE, "Add a source add-on", nav::addAddon).icon(R.drawable.ic_tv_extension)
                        .detail("Enter the manifest address of a stream provider you use."),
                new Action("import", Action.Style.TILE, "Import a torrent", () -> nav.push(new Route("Imports"))).icon(R.drawable.ic_tv_download)
                        .detail("A magnet link, torrent URL or .torrent file.")).centered());
        page.add(Item.actions("welcome-finish",
                new Action("audio", Action.Style.CHIP, "Audio", () -> language("audioLanguage", "Preferred audio language")).icon(R.drawable.ic_tv_audio)
                        .detail(languageName(services.store.preference("audioLanguage", ""))),
                new Action("subtitles", Action.Style.CHIP, "Subtitles", () -> language("subtitleLanguage", "Preferred subtitle language")).icon(R.drawable.ic_tv_subtitles)
                        .detail(languageName(services.store.preference("subtitleLanguage", ""))),
                new Action("continue", Action.Style.PRIMARY, "Start watching", () -> nav.then(() -> {
                    services.store.setPreference("onboardingDone", "true"); return null;
                }, () -> nav.root("Home"))).icon(R.drawable.ic_tv_play)).centered());
        page.initialFocus = "cinemeta";
        return page;
    }

    Page imports() throws Exception {
        Page page = new Page().panel(R.drawable.ic_tv_download, "Imports",
                "Magnet links, torrent URLs and .torrent files. Nothing downloads until you press Play.");
        page.add(Item.row("import-address", R.drawable.ic_tv_link, "Add magnet or torrent URL", "", nav::importAddress));
        page.add(Item.row("import-file", R.drawable.ic_tv_file, "Choose a .torrent file", "", nav::importFile));
        List<String> ids = services.store.importIds();
        page.add(Item.header("Your imports", ids.isEmpty() ? "" : Integer.toString(ids.size())));
        for (String id : ids) {
            JSONObject d = services.store.importDescriptor(id);
            Title t = Title.parse("local", new JSONObject().put("id", id).put("type", "movie").put("name", d.optString("name", "Import")));
            page.add(Item.row("import:" + id, R.drawable.ic_tv_movie, t.name, d.has("fileIdx") ? "File chosen" : "", () -> nav.openTitle(t, "")));
        }
        if (ids.isEmpty()) page.add(Item.text("", "No imports yet. Add one above; it appears here and in Search."));
        return page;
    }

    Page settings() {
        Page page = new Page().panel(R.drawable.ic_tv_settings, "Settings", "Add-ons, languages, subtitles, playback and storage.");
        page.add(Item.header("Add-ons"));
        page.add(Item.row("add-addon", R.drawable.ic_tv_add, "Add add-on", "", nav::addAddon));
        for (InstalledAddon a : services.store.addons()) {
            page.add(Item.row("addon:" + a.id, R.drawable.ic_tv_extension, a.manifest.name, a.enabled ? "On" : "Off", () -> {
                Route r = new Route("Add-on"); r.value = a.id; nav.push(r);
            }));
        }
        page.add(Item.header("Languages"));
        page.add(Item.row("audio-language", R.drawable.ic_tv_audio, "Audio language", languageName(services.store.preference("audioLanguage", "")),
                () -> language("audioLanguage", "Audio language")));
        page.add(Item.row("subtitle-language", R.drawable.ic_tv_subtitles, "Subtitle language", languageName(services.store.preference("subtitleLanguage", "")),
                () -> language("subtitleLanguage", "Subtitle language")));
        page.add(Item.header("Subtitle style"));
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
        page.add(Item.row("cache-size", R.drawable.ic_tv_storage, "Playback cache", services.store.preference("cacheMiB", "256") + " MiB",
                () -> nav.choose("Playback cache", new String[]{"128 MiB", "256 MiB", "512 MiB"},
                        i -> nav.mutate(() -> { services.store.setPreference("cacheMiB", new String[]{"128", "256", "512"}[i]); return null; }))));
        boolean autoplay = services.store.preference("autoplay", "true").equals("true");
        page.add(Item.row("autoplay", R.drawable.ic_tv_next, "Play next episode", autoplay ? "On" : "Off",
                () -> nav.mutate(() -> { services.store.setPreference("autoplay", autoplay ? "false" : "true"); return null; })));
        page.add(Item.header("Storage"));
        page.add(Item.row("clear-cache", R.drawable.ic_tv_delete, "Clear app playback and metadata cache", "",
                () -> nav.mutate(() -> { clearCaches(context, services); return null; })));
        page.add(Item.text("", "Clearing keeps imports, favourites and watch history."));
        page.add(Item.header("About"));
        page.add(Item.row("developer", R.drawable.ic_tv_code, "Developer", "", () -> nav.push(new Route("Developer"))));
        return page;
    }
    /** Removes only this app's disposable payload/subtitle files and cached metadata. */
    static void clearCaches(Context context, TvApplication.Services services) {
        services.store.clearMetadata();
        delete(new File(context.getCacheDir(), "payload"));
        delete(new File(context.getCacheDir(), "subtitles"));
    }

    Page addon(Route route) {
        InstalledAddon addon = null;
        for (InstalledAddon a : services.store.addons()) if (a.id.equals(route.value)) addon = a;
        if (addon == null) return new Page().title("Add-on").add(Item.message(R.drawable.ic_tv_extension, "Add-on removed", "Return to Settings."));
        InstalledAddon a = addon;
        boolean streams = false;
        for (AddonManifest.Resource r : a.manifest.resources) if (r.name.equals("stream")) streams = true;
        Page page = new Page().panel(R.drawable.ic_tv_extension, a.manifest.name,
                (a.manifest.description.isEmpty() ? "" : a.manifest.description + "\n\n") + (streams ? "Supplies streams for playback." : "Catalogues and details only; it doesn't supply streams."));
        page.add(Item.row("enable", 0, a.enabled ? "Disable add-on" : "Enable add-on", a.enabled ? "On" : "Off",
                () -> nav.mutate(() -> { services.addons.enable(a.id, !a.enabled); return null; })));
        page.add(Item.row("move:-1", 0, "Move earlier", "", () -> move(a.id, -1)));
        page.add(Item.row("move:1", 0, "Move later", "", () -> move(a.id, 1)));
        if (!a.manifest.catalogs.isEmpty()) page.add(Item.header("Home rows"));
        for (AddonManifest.Catalog c : a.manifest.catalogs) {
            boolean visible = services.store.catalogVisible(a.id, c.type, c.id);
            page.add(Item.row("visibility:" + c.type + c.id, R.drawable.ic_tv_movie, c.name, (visible ? "Shown" : "Hidden") + " · " + BrowseScreens.typeLabel(c.type),
                    () -> nav.mutate(() -> { services.addons.visible(a.id, c.type, c.id, !visible); return null; })));
        }
        page.add(Item.header("Remove"));
        page.add(Item.row("remove", R.drawable.ic_tv_delete, "Remove add-on", "Keeps library and history",
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
        Page page = new Page().panel(R.drawable.ic_tv_code, "Developer", "Versions, device, storage and add-on diagnostics. Addresses are shown without their private paths.");
        page.add(Item.text("Version", "Daniil TV " + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName
                + "\n" + tv.daniil.torrent.TorrentEngine.version()));
        page.add(Item.text("Device", android.os.Build.MODEL + " · Android API " + android.os.Build.VERSION.SDK_INT + " · " + String.join(", ", android.os.Build.SUPPORTED_ABIS)));
        page.add(Item.text("Storage", context.getFilesDir().getUsableSpace() / (1 << 20) + " MiB free · " + services.cacheBytes() / (1 << 20)
                + " MiB playback cache · 512 MiB kept free"));
        for (InstalledAddon a : services.store.addons()) page.add(Item.text(a.manifest.name, a.address.toString()));
        page.add(Item.text("Third-party software", "Media3 (Apache-2.0), libtorrent and Boost (BSD/Boost), OpenSSL (Apache-2.0)."));
        page.add(Item.row("notices", R.drawable.ic_tv_info, "Open source notices", "", nav::notices));
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
