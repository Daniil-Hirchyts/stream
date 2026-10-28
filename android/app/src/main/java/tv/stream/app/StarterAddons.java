package tv.stream.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import tv.stream.app.addon.AddonUrl;
import tv.stream.app.addon.BuiltinAddons;

/**
 * The add-ons a new install sets itself up with: the store's starter page (catalogues and subtitles)
 * plus torrent sources.
 */
final class StarterAddons {
    private StarterAddons() {}
    static final String OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json";
    /** The store route value for the starter page; not an add-on address. */
    static final String PACK = "starter-pack";
    static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(AddonUrl.CINEMETA, OPENSUBTITLES));
    /**
     * Torrent sources a new install also gets, by the owner's choice: the public, unconfigured addresses the store's
     * Torrent/P2P list offers (see TorrentAddons). Not on the starter page; each can be removed or reconfigured.
     */
    static final List<String> SOURCES = Collections.unmodifiableList(Arrays.asList(
            "https://meteorfortheweebs.midnightignite.me/manifest.json", "https://mediafusionfortheweebs.midnightignite.me/manifest.json",
            "https://peerflix.mov/manifest.json", "https://torrentsdb.com/manifest.json"));

    /** Remembers which defaults were installed once, so one the viewer removed is never added back. */
    private static final String INSTALLED_ONCE = "defaultAddons";

    /**
     * Installs each default add-on the app has not installed before, so a new install is ready to browse, search and
     * play without setup. Runs on a worker; one that can't be reached now is tried again on the next start.
     * @return how many were added
     */
    static int installDefaults(TvApplication.Services services) {
        java.util.Set<String> done = new java.util.LinkedHashSet<>();
        for (String url : services.store.preference(INSTALLED_ONCE, "").split(" ")) if (!url.isEmpty()) done.add(url);
        int added = 0;
        java.util.List<String> defaults = new java.util.ArrayList<>(services.starters);
        defaults.addAll(SOURCES);
        for (String url : defaults) {
            if (done.contains(url)) continue;
            if (StoreScreens.installed(url, "", services.store.addons()) == null) {
                // Installing cancels open requests, so each add-on is read with a request of its own.
                try (tv.stream.app.addon.Cancellation request = services.addons.newRequest()) {
                    tv.stream.app.addon.AddonManifest manifest = services.addons.preview(url, request);
                    // The same add-on installed with the viewer's own configuration counts as installed.
                    if (StoreScreens.installed(url, manifest.id, services.store.addons()) == null) { services.addons.install(url, manifest); added++; }
                } catch (tv.stream.app.addon.AddonException unreachable) { continue; }
            }
            done.add(url);
            services.store.setPreference(INSTALLED_ONCE, String.join(" ", done));
        }
        return added;
    }

    /** A short name and what it adds, for the onboarding list; unknown (fixture) addresses show their host. */
    static String[] describe(String url) {
        if (url.equals(AddonUrl.CINEMETA)) return new String[]{"Cinemeta", "Films and series"};
        if (url.equals(BuiltinAddons.INTERNET_ARCHIVE)) return new String[]{"Internet Archive", "Free classic films"};
        if (url.equals(OPENSUBTITLES)) return new String[]{"OpenSubtitles", "Subtitles"};
        return new String[]{android.net.Uri.parse(url).getHost() == null ? url : android.net.Uri.parse(url).getHost(), "Add-on"};
    }
}
