package tv.stream.app

import android.view.View
import java.util.concurrent.Callable
import java.util.function.Consumer
import tv.stream.app.addon.Title

/** What screen actions may do. Called on the main thread; implemented by [MainActivity]. */
interface Navigator {
    fun push(next: Route)
    fun root(screen: String)
    fun search()
    fun searchInput(value: String)
    fun openImports()
    fun back()
    /** Rebuilds the current route. */
    fun refresh()
    /** Route parameters changed; reuse fresh provider data rather than forcing a network refresh. */
    fun changed() { refresh() }
    fun openTitle(title: Title, video: String?)
    fun sources(title: Title, video: String?)
    fun play(title: Title, video: String, sourceKey: String)
    /** A one-line text entry; `description` explains it under the title (empty for none). */
    fun edit(title: String, description: String, hint: String, initial: String?, accept: Consumer<String>)
    fun edit(title: String, hint: String, initial: String?, accept: Consumer<String>) { edit(title, "", hint, initial, accept) }
    fun choose(title: String, options: Array<String>, accept: Consumer<Int>)
    fun options(title: String, contents: Consumer<tv.stream.app.ui.Tv.Alert>)
    fun chooseWithPreview(title: String, header: View?, options: Array<String>, selected: Int,
                          accept: Consumer<Int>, onPreview: Consumer<Int>?) {
        choose(title, options, accept)
    }
    fun previewSubtitle(setting: tv.stream.playback.SubtitleStyle.Setting, index: Int) {}
    fun alert(title: String, message: String)
    fun openWebsite(address: String)
    /** Runs `task` on a worker, then rebuilds the current route. */
    fun mutate(task: Callable<*>)
    fun mutateEpisode(task: Callable<*>, route: Route, season: Int, episode: String)
    /** Runs `task` on a worker, then `after` on the main thread. */
    fun then(task: Callable<*>, after: Runnable)
    /** Installs the starter add-ons the store's starter page listed (the viewer's acceptance), then opens Home. */
    fun installStarters(addons: List<String>)
    fun addAddon()
    fun previewAddon(address: String)
    fun importAddress()
    fun importFile()
    fun notices()
    fun errorReport() {}
    fun keyboardLanguage(language: String) {}
}
