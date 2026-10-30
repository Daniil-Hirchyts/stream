package tv.stream.app

import android.net.Uri
import java.util.concurrent.Callable
import java.util.function.Consumer
import tv.stream.app.addon.AddonException
import tv.stream.app.addon.AddonUrl
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.AddonManifest
import tv.stream.app.ui.Item
import tv.stream.app.ui.Page
import tv.stream.app.ui.Sounds

/**
 * The add-on and import journeys: installing the starter add-ons, previewing and installing an add-on by address,
 * importing magnets, torrent URLs and files, and reading the open source notices. Provider work runs through
 * [Host.work] on the application's workers; this class keeps no activity, view or provider response of its own.
 */
class AddonFlows(private val host: Host) {
    /** What the journeys need from the screen that runs them. */
    interface Host {
        val services: TvApplication.Services
        /** The current navigation generation and its provider scope; a later generation makes results stale. */
        val generation: Int
        val scope: Cancellation?
        val navigator: Navigator
        fun <T> work(task: Callable<T>, done: Consumer<T>)
        /** The page on screen, to show a progress notice in place; null before the first page. */
        fun shownPage(): Page?
        fun showPartial(page: Page)
        fun confirm(title: String, message: CharSequence, action: String, accept: Runnable, cancel: Runnable?)
        fun message(title: String, message: CharSequence, scroll: Boolean, close: Runnable?)
        fun render()
        fun rememberFocus()
        fun readAsset(path: String): String
        fun isShowing(screen: String): Boolean
    }

    fun installStarters(addons: List<String>) {
        host.shownPage()?.let { shown ->
            val partial = shown.copy(); partial.items.removeIf { item -> item.key == "starter-actions" }
            host.showPartial(partial.add(Item.notice(R.drawable.ic_tv_refresh, "Installing add-ons", "")))
        }
        val services = host.services
        host.work(Callable {
            val missed: MutableList<String> = ArrayList()
            for (url in addons) {
                if (StoreScreens.installed(url, "", services.store.addons()) != null) continue
                // Installing cancels open requests, so each add-on is read with a request of its own.
                try { services.addons.newRequest().use { request -> services.addons.install(url, services.addons.preview(url, request)) } }
                catch (e: AddonException) { missed.add(StarterAddons.describe(url)[0]) }
            }
            missed
        }, Consumer { missed ->
            Sounds.play(if (missed.isEmpty()) Sounds.Sound.INSTALLED else Sounds.Sound.WARNING)
            host.navigator.root("Home")
            if (missed.isNotEmpty()) host.navigator.alert("Some add-ons weren't added", missed.joinToString(", ") + " didn't respond. Try again from the add-on store.")
        })
    }

    fun addAddon() {
        host.navigator.edit("Add add-on", "Connect a catalogue or source using its manifest address.", "https://… or stremio://…/manifest.json", "",
                Consumer { address -> previewAddon(address) })
    }

    fun previewAddon(address: String) {
        val normalized = AddonUrl.normalize(address)
        host.shownPage()?.let { host.showPartial(it.copy().add(Item.notice(R.drawable.ic_tv_info, "Loading add-on details", ""))) }
        val mine = host.generation
        val request = host.scope
        val services = host.services
        host.work(Callable { services.addons.preview(normalized, request!!) }, Consumer { manifest: AddonManifest ->
            host.shownPage()?.let { shown ->
                val restored = shown.copy(); restored.items.removeIf { item -> item.key == "notice:Loading add-on details" }
                host.showPartial(restored)
            }
            if (manifest.configurationRequired) {
                val review = Route("Add-on review"); review.value = normalized; host.navigator.push(review); return@Consumer
            }
            val supplies = StoreScreens.supplies(manifest)
            val torrents = manifest.p2p || tv.stream.app.addon.TorrentAddons.find(normalized) != null
            val details = ((if (manifest.description.isEmpty()) "" else manifest.description + "\n\n") + supplies
                    + (if (torrents) "\nTorrent sources play directly on this TV and share data with peers." else "")
                    + "\n\n" + manifest.catalogs.size + " catalogues. The provider receives your searches and the titles you open.")
            host.confirm("Install " + manifest.name + "?", details, "Install", Runnable {
                host.navigator.then(Callable { services.addons.install(normalized, manifest); null },
                        Runnable { Sounds.play(Sounds.Sound.INSTALLED); host.rememberFocus(); host.render() })
            }, Runnable { if (mine == host.generation) host.render() })
        })
    }

    fun importAddress() {
        val services = host.services
        host.navigator.edit("Import torrent", "Paste a magnet link or a torrent URL.", "magnet:?xt=… or https://…torrent", "", Consumer { address ->
            host.navigator.edit("Name this import", MainActivity.NAME_HINT, "Title", "Imported torrent",
                    Consumer { name -> host.navigator.mutate(Callable { services.imports.addAddress(name, address) }) })
        })
    }

    fun importFile(uri: Uri) {
        val services = host.services
        host.navigator.edit("Name this import", MainActivity.NAME_HINT, "Title", "Imported torrent", Consumer { name ->
            host.navigator.then(Callable { services.imports.addFile(name, uri, host.scope!!) }, Runnable {
                if (host.isShowing("Imports")) host.render() else host.navigator.openImports()
            })
        })
    }

    fun notices() {
        val files = arrayOf("androidx.txt", "streaming-engine.txt", "geist.txt", "geist-icons.txt", "zxing.txt", "material-icons.txt", "noto-sans.txt", "atkinson-hyperlegible.txt")
        val names = arrayOf("AndroidX / Media3", "Streaming engine (Rust crates)", "Geist Sans", "Geist UI icons", "ZXing", "Material Icons", "Noto Sans", "Atkinson Hyperlegible Next")
        host.navigator.choose("Open source notices", names, Consumer { which ->
            host.work(Callable { host.readAsset("notices/" + files[which]) }, Consumer { text ->
                host.message("Open source notices", text, true, Runnable { host.render() })
            })
        })
    }
}
