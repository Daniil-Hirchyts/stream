package tv.stream.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.function.Consumer
import tv.stream.playback.SubtitleStyle
import tv.stream.app.addon.AddonManifest
import tv.stream.app.addon.Title
import tv.stream.app.data.InstalledAddon
import tv.stream.app.ui.Item
import tv.stream.app.ui.Page
import tv.stream.app.ui.SubtitleFonts

/** Onboarding, Imports, Settings, add-on management and Developer diagnostics. */
class SetupScreens(private val context: Context, private val nav: Navigator, private val services: TvApplication.Services) {

    @Throws(Exception::class)
    fun imports(): Page {
        val page = Page().title("Imports").narrow()
        page.add(Item.row("import-address", 0, "Add magnet or torrent URL", "", Runnable { nav.importAddress() }))
        page.add(Item.row("import-file", 0, "Choose a .torrent file", "", Runnable { nav.importFile() }))
        val ids: List<String> = services.store.importIds()
        if (!ids.isEmpty()) page.add(Item.header("Your imports"))
        for (id in ids) {
            val d: JSONObject = services.store.importDescriptor(id)!!
            val t = Title.parse("local", JSONObject().put("id", id).put("type", "movie").put("name", d.optString("name", "Import")))!!
            page.add(Item.row("import:" + id, 0, t.name, if (d.has("fileIdx")) "File chosen" else "", Runnable { nav.openTitle(t, "") }))
        }
        return page
    }

    /**
     * Settings as a split view: seven categories on the left, the focused one's rows on the right. Each row keeps its
     * label; the categories only group them. The category shown is remembered on the route.
     */
    fun settings(route: Route): Page {
        val panes: MutableMap<String, List<Item>> = LinkedHashMap()
        val categories: MutableList<Item> = ArrayList()

        val addons: MutableList<Item> = ArrayList()
        for (a in services.store.addons()) {
            addons.add(Item.row("addon:" + a.id, 0, a.manifest.name, if (a.enabled) "" else "Off", Runnable {
                val r = Route("Add-on"); r.value = a.id; nav.push(r)
            }))
        }
        addons.add(Item.row("addon-store", 0, "Add-on store", "", Runnable { nav.push(StoreScreens.route("All")) }))
        addons.add(Item.row("add-addon", 0, "Add add-on", "By address", Runnable { nav.addAddon() }))
        category(categories, panes, "settings:addons", R.drawable.ic_tv_extension, "Add-ons", addons)

        val importCount = services.store.importIds().size
        category(categories, panes, "settings:torrents", R.drawable.ic_tv_download, "Torrents", Collections.singletonList(
                Item.row("manage-imports", 0, "Imports", if (importCount == 0) "" else Integer.toString(importCount), Runnable { nav.openImports() })))

        category(categories, panes, "settings:languages", R.drawable.ic_tv_audio, "Languages", Arrays.asList(
                Item.row("audio-language", 0, "Audio language", languageName(services.store.preference("audioLanguage", "")),
                        Runnable { language("audioLanguage", "Audio language") }),
                Item.row("subtitle-language", 0, "Subtitle language", languageName(services.store.preference("subtitleLanguage", "")),
                        Runnable { language("subtitleLanguage", "Subtitle language") }),
                Item.row("keyboard-languages", 0, "Keyboard languages", keyboardLanguageNames(),
                        Runnable { nav.push(Route("Keyboard languages")) })))

        val subtitles: MutableList<Item> = ArrayList()
        subtitles.add(Item.subtitlePreview("subtitle-preview", services.playerPreferences))
        val subtitleStyle = SubtitleStyle(services.playerPreferences)
        for (s in SubtitleStyle.Setting.values()) {
            val setting = s.key
            val values: Array<String> = if (s == SubtitleStyle.Setting.FONT) SubtitleFonts.VALUES else s.values
            val currentIndex = if (s == SubtitleStyle.Setting.FONT) SubtitleFonts.selected(services.playerPreferences) else subtitleStyle.get(s)
            val labels: Array<String> = if (s == SubtitleStyle.Setting.FONT) SubtitleFonts.labels(context) else Array(s.labels.size) { "" }
            if (s != SubtitleStyle.Setting.FONT) for (i in labels.indices) labels[i] = context.getString(s.labels[i])
            val title = "Subtitle " + context.getString(s.title).lowercase(java.util.Locale.ROOT)
            subtitles.add(Item.row("subtitle-" + setting, 0, title, labels[currentIndex], Runnable {
                nav.chooseWithPreview(title, null, labels, currentIndex, Consumer { i -> nav.mutate(Callable {
                    services.playerPreferences.edit().putString("subtitle_" + setting, values[i]).apply()
                    services.store.setPreference("subtitle_" + setting, values[i])
                    null
                }) }, Consumer { i -> nav.previewSubtitle(s, i) })
            }))
        }
        category(categories, panes, "settings:subtitles", R.drawable.ic_tv_subtitles, "Subtitles", subtitles)

        val autoplay = services.store.preference("autoplay", "true") == "true"
        val sounds = soundsOn(services)
        category(categories, panes, "settings:playback", R.drawable.ic_tv_play, "Playback", Arrays.asList(
                Item.row("cache-size", 0, "Playback cache", services.store.preference("cacheMiB", "256") + " MiB",
                        Runnable { nav.choose("Playback cache", arrayOf("128 MiB", "256 MiB", "512 MiB"),
                                Consumer { i -> nav.mutate(Callable { services.store.setPreference("cacheMiB", arrayOf("128", "256", "512")[i]); null }) }) }),
                Item.row("autoplay", 0, "Play next episode", if (autoplay) "On" else "Off",
                        Runnable { nav.mutate(Callable { services.store.setPreference("autoplay", if (autoplay) "false" else "true"); null }) }),
                Item.row("interface-sounds", 0, "Interface sounds", if (sounds) "On" else "Off", Runnable { nav.mutate(Callable {
                    services.store.setPreference("interfaceSounds", if (sounds) "false" else "true")
                    tv.stream.app.ui.Sounds.get(context).setEnabled(!sounds); null
                }) }),
                // Clearing keeps imports, favourites and watch history.
                Item.row("clear-cache", 0, "Clear app playback and metadata cache", "",
                        Runnable { nav.mutate(Callable { clearCaches(context, services); null }) })))

        val clock = clockFormat(services)
        val playerClock = playerClock(services)
        category(categories, panes, "settings:clock", R.drawable.ic_tv_clock, "Clock", Arrays.asList(
                Item.row("clock", 0, "Clock", tv.stream.app.ui.Clock.name(clock),
                        Runnable { nav.choose("Clock", tv.stream.app.ui.Clock.NAMES,
                                Consumer { i -> nav.mutate(Callable { services.store.setPreference("clock", tv.stream.app.ui.Clock.VALUES[i]); null }) }) }),
                Item.row("player-clock", 0, "Clock in player", if (playerClock) "On" else "Off",
                        Runnable { nav.mutate(Callable { services.store.setPreference("playerClock", if (playerClock) "false" else "true"); null }) })))

        val developerMode = developerMode(services)
        val about: MutableList<Item> = ArrayList()
        about.add(Item.row("developer-mode", 0, "Developer mode", if (developerMode) "On" else "Off", Runnable { nav.mutate(Callable {
            services.store.setPreference("developerMode", if (developerMode) "false" else "true")
            if (developerMode) (context.applicationContext as TvApplication).lastErrorReport = null
            null
        }) }))
        if (developerMode) about.add(Item.row("developer", 0, "Developer", "", Runnable { nav.push(Route("Developer")) }))
        category(categories, panes, "settings:about", R.drawable.ic_tv_info, "About", about)

        return Page().add(Item.split("settings", categories, panes, route.extras.getOrDefault("category", ""),
                Consumer { key -> route.extras["category"] = key }))
    }
    private fun keyboardLanguageNames(): String {
        val names: MutableList<String> = ArrayList()
        for (code in tv.stream.app.ui.KeyboardLanguages.parse(services.store.preference("keyboardLanguages", "en")))
            names.add(tv.stream.app.ui.KeyboardLanguages.name(code))
        return names.joinToString(", ")
    }
    fun keyboardLanguages(): Page {
        val enabled: List<String> = tv.stream.app.ui.KeyboardLanguages.parse(services.store.preference("keyboardLanguages", "en"))
        val page = Page().title("Keyboard languages").narrow()
        page.add(Item.text("", "Choose one or more languages. Use the language key to switch while searching."))
        for (code in tv.stream.app.ui.KeyboardLanguages.CODES) {
            val selected = enabled.contains(code)
            page.add(Item.row("keyboard-language:" + code, if (selected) R.drawable.ic_tv_check else 0,
                    tv.stream.app.ui.KeyboardLanguages.name(code), if (selected) "On" else "", Runnable {
                if (selected && enabled.size == 1) return@Runnable
                val next: MutableList<String> = ArrayList(enabled)
                if (selected) next.remove(code) else next.add(code)
                nav.mutate(Callable { services.store.setPreference("keyboardLanguages", next.joinToString(",")); null })
            }))
        }
        return page
    }

    fun addon(route: Route): Page {
        var addon: InstalledAddon? = null
        for (a in services.store.addons()) if (a.id == route.value) addon = a
        if (addon == null) return Page().title("Add-on").narrow().add(Item.message(R.drawable.ic_tv_extension, "Add-on removed", ""))
        val a: InstalledAddon = addon
        val page = Page().title(a.manifest.name).narrow()
        page.add(Item.text("", StoreScreens.kinds(a.manifest)))
        page.add(Item.row("enable", 0, if (a.enabled) "Disable add-on" else "Enable add-on", if (a.enabled) "On" else "Off",
                Runnable { nav.mutate(Callable { services.addons.enable(a.id, !a.enabled); null }) }))
        page.add(Item.row("move:-1", 0, "Move earlier", "", Runnable { move(a.id, -1) }))
        page.add(Item.row("move:1", 0, "Move later", "", Runnable { move(a.id, 1) }))
        if (!a.manifest.catalogs.isEmpty()) page.add(Item.header("Home rows"))
        for (c in a.manifest.catalogs) {
            val visible = services.store.catalogVisible(a.id, c.type, c.id)
            page.add(Item.row("visibility:" + c.type + c.id, 0, BrowseScreens.shelfTitle(c), if (visible) "Shown" else "Hidden",
                    Runnable { nav.mutate(Callable { services.addons.visible(a.id, c.type, c.id, !visible); null }) }))
        }
        page.add(Item.header(""))
        page.add(Item.row("remove", 0, "Remove add-on", "",
                Runnable { nav.then(Callable { services.addons.remove(a.id); null }, Runnable { nav.back() }) }))
        return page
    }
    private fun move(id: String, delta: Int) {
        nav.mutate(Callable {
            val order: MutableList<String> = java.util.ArrayList()
            for (item in services.store.addons()) order.add(item.id)
            val position = order.indexOf(id)
            val next = position + delta
            if (next >= 0 && next < order.size) { Collections.swap(order, position, next); services.addons.reorder(order) }
            null
        })
    }

    @Throws(Exception::class)
    fun developer(): Page {
        val page = Page().title("Developer").narrow()
        if (!developerMode(services)) return page.add(Item.text("Developer mode is off", "Enable it in Settings → About."))
        if ((context.applicationContext as TvApplication).lastErrorReport != null)
            page.add(Item.row("last-error-report", 0, "Last error report", "Scan QR", Runnable { nav.errorReport() }))
        else page.add(Item.text("Error reports", "A QR report appears when a problem occurs with Developer mode on. Reports are kept only until the app closes or Developer mode is turned off."))
        page.add(Item.text("Version", context.getString(R.string.app_name) + " " + context.packageManager.getPackageInfo(context.packageName, 0).versionName
                + "\n" + tv.stream.torrent.TorrentEngine.version()))
        page.add(Item.text("Device", android.os.Build.MODEL + " · Android API " + android.os.Build.VERSION.SDK_INT + " · " + android.os.Build.SUPPORTED_ABIS.joinToString(", ")))
        page.add(Item.text("Storage", (context.filesDir.usableSpace / (1 shl 20)).toString() + " MiB free · " + services.cacheBytes() / (1 shl 20)
                + " MiB playback cache · 512 MiB kept free"))
        for (a in services.store.addons()) page.add(Item.text(a.manifest.name, a.address.toString()))
        page.add(Item.text("Third-party software", "Media3 and ZXing (Apache-2.0), libtorrent and Boost (BSD/Boost), OpenSSL (Apache-2.0)."))
        page.add(Item.row("notices", 0, "Open source notices", "", Runnable { nav.notices() }))
        return page
    }

    fun language(key: String, title: String) {
        val names: Array<String> = if (key == "audioLanguage") Arrays.copyOf(LANGUAGE_NAMES, LANGUAGE_NAMES.size - 1) else LANGUAGE_NAMES
        nav.choose(title, names, Consumer { which -> nav.mutate(Callable { services.store.setPreference(key, LANGUAGES[which]); null }) })
    }

    companion object {
        val LANGUAGE_NAMES = arrayOf("Automatic", "English", "Russian", "French", "German", "Spanish", "Italian", "Ukrainian", "Japanese", "Off")
        val LANGUAGES = arrayOf("", "eng", "rus", "fra", "deu", "spa", "ita", "ukr", "jpn", "off")

        private fun category(categories: MutableList<Item>, panes: MutableMap<String, List<Item>>, key: String, icon: Int, title: String, rows: List<Item>) {
            categories.add(Item.row(key, icon, title, "", null))
            panes[key] = rows
        }
        /** The viewer's interface sounds switch (on unless turned off). Reads the store: call on a worker. */
        fun soundsOn(services: TvApplication.Services): Boolean { return services.store.preference("interfaceSounds", "true") != "false" }
        /** The clock's format ("", "24", "12" or "off"); reads the store, so call on a worker. */
        fun clockFormat(services: TvApplication.Services): String { return services.store.preference("clock", "") }
        /** Whether the player overlay shows the clock too (on unless turned off). Reads the store: call on a worker. */
        fun playerClock(services: TvApplication.Services): Boolean { return services.store.preference("playerClock", "true") != "false" }
        /** Diagnostics are opt-in. Read the encrypted store on an application worker. */
        fun developerMode(services: TvApplication.Services): Boolean { return services.store.preference("developerMode", "false") == "true" }
        fun clearCaches(context: Context, services: TvApplication.Services) {
            services.addons.cancelRequests()
            services.store.clearMetadata()
            delete(File(context.cacheDir, "payload"))
            delete(File(context.cacheDir, "subtitles"))
            delete(File(context.cacheDir, "artwork"))
        }
        fun languageName(code: String): String {
            for (i in LANGUAGES.indices) if (LANGUAGES[i] == code) return LANGUAGE_NAMES[i]
            try {
                val normalized = java.util.Locale.forLanguageTag(code).getISO3Language()
                for (i in LANGUAGES.indices) if (LANGUAGES[i] == normalized) return LANGUAGE_NAMES[i]
            } catch (ignored: Exception) { /* unknown provider language */ }
            return code
        }
        fun delete(file: File) {
            val children = file.listFiles()
            if (children != null) for (child in children) delete(child)
            file.delete()
        }
    }
}
