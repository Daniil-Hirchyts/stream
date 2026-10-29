package tv.stream.app

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.stream.app.addon.Cancellation
import tv.stream.app.ui.Page

/** A page's worker result. Rendering and focus stay with the current activity. */
sealed interface PageScreenState {
    data object Idle : PageScreenState
    data class Loading(val generation: Int) : PageScreenState
    data class Content(val generation: Int, val content: PageContent, val partial: Boolean) : PageScreenState
    data class Failed(val generation: Int, val cause: Exception) : PageScreenState
}

/** Immutable publication boundary around the mutable page-building/rendering APIs. */
class PageContent(page: Page) {
    private val snapshot = page.copy()
    fun render(): Page = snapshot.copy()
}

/**
 * Owns foreground work for browse, details and management screens. Each navigation or search invalidates
 * every older delivery and closes its provider sockets, including work that ignores thread interruption.
 * Executors belong to the application; clearing this model never shuts them down.
 */
class BrowseViewModel : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<PageScreenState>(PageScreenState.Idle)
    val state = mutableState.asStateFlow()
    var generation = 0
        private set
    var request: Cancellation? = null
        private set
    private val tasks = mutableListOf<Future<*>>()

    fun begin(request: Cancellation) {
        this.request = request
    }

    fun load(executor: ExecutorService, build: (Cancellation, (Page) -> Unit) -> Page) {
        val scope = checkNotNull(request)
        val mine = generation
        mutableState.value = PageScreenState.Loading(mine)
        work(executor, Callable {
            build(scope) { page ->
                val snapshot = PageContent(page)
                deliver(mine) { mutableState.value = PageScreenState.Content(mine, snapshot, true) }
            }
        }) { page -> mutableState.value = PageScreenState.Content(mine, PageContent(page), false) }
    }

    fun <T> work(executor: ExecutorService, task: Callable<T>, done: (T) -> Unit) {
        val mine = generation
        tasks.removeAll { it.isDone }
        tasks += executor.submit {
            try {
                val value = task.call()
                deliver(mine) { done(value) }
            } catch (failure: Exception) {
                deliver(mine) { mutableState.value = PageScreenState.Failed(mine, failure) }
            }
        }
    }

    private fun deliver(mine: Int, action: () -> Unit) {
        main.post { if (mine == generation) action() }
    }

    fun cancel() {
        generation++
        request?.close()
        request = null
        tasks.forEach { it.cancel(true) }
        tasks.clear()
        // Pages contain actions bound to the rendering activity; never retain them across detachment.
        mutableState.value = PageScreenState.Idle
        main.removeCallbacksAndMessages(null)
    }

    override fun onCleared() = cancel()
}
