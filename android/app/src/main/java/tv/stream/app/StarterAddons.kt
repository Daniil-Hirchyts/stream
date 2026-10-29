package tv.stream.app

import java.util.Arrays
import java.util.Collections
import tv.stream.app.addon.AddonUrl
import tv.stream.app.addon.BuiltinAddons

/**
 * The add-ons a new install sets itself up with: the store's starter page (catalogues and subtitles)
 * plus torrent sources.
 */
object StarterAddons {
    const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"
    /** The store route value for the starter page; not an add-on address. */
    const val PACK = "starter-pack"
    val ALL: List<String> = Collections.unmodifiableList(Arrays.asList(AddonUrl.CINEMETA, OPENSUBTITLES))
    /**
     * Torrent sources a new install also gets, by the owner's choice: the public, unconfigured addresses the store's
     * Torrent/P2P list offers (see TorrentAddons). Not on the starter page; each can be removed or reconfigured.
     */
    val SOURCES: List<String> = Collections.unmodifiableList(Arrays.asList(
            "https://meteorfortheweebs.midnightignite.me/manifest.json", "https://mediafusionfortheweebs.midnightignite.me/manifest.json",
            "https://peerflix.mov/manifest.json", "https://torrentsdb.com/manifest.json"))

    /** Remembers which defaults were installed once, so one the viewer removed is never added back. */
    private const val INSTALLED_ONCE = "defaultAddons"

    /**
     * Installs each default add-on the app has not installed before, so a new install is ready to browse, search and
     * play without setup. Runs on a worker; one that can't be reached now is tried again on the next start.
     * @return how many were added
     */
    fun installDefaults(services: TvApplication.Services): Int {
        val done: MutableSet<String> = java.util.LinkedHashSet()
        for (url in java.util.regex.Pattern.compile(" ").split(services.store.preference(INSTALLED_ONCE, ""))) if (!url.isEmpty()) done.add(url)
        var added = 0
        val defaults: MutableList<String> = java.util.ArrayList(services.starters)
        defaults.addAll(SOURCES)
        for (url in defaults) {
            if (done.contains(url)) continue
            if (StoreScreens.installed(url, "", services.store.addons()) == null) {
                // Installing cancels open requests, so each add-on is read with a request of its own.
                try {
                    services.addons.newRequest().use { request ->
                        val manifest = services.addons.preview(url, request)
                        // The same add-on installed with the viewer's own configuration counts as installed.
                        if (StoreScreens.installed(url, manifest.id, services.store.addons()) == null) { services.addons.install(url, manifest); added++ }
                    }
                } catch (unreachable: tv.stream.app.addon.AddonException) { continue }
            }
            done.add(url)
            services.store.setPreference(INSTALLED_ONCE, java.lang.String.join(" ", done))
        }
        return added
    }

    /** A short name and what it adds, for the onboarding list; unknown (fixture) addresses show their host. */
    fun describe(url: String): Array<String> {
        if (url == AddonUrl.CINEMETA) return arrayOf("Cinemeta", "Films and series")
        if (url == BuiltinAddons.INTERNET_ARCHIVE) return arrayOf("Internet Archive", "Free classic films")
        if (url == OPENSUBTITLES) return arrayOf("OpenSubtitles", "Subtitles")
        return arrayOf(if (android.net.Uri.parse(url).host == null) url else android.net.Uri.parse(url).host!!, "Add-on")
    }
}
