package tv.stream.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * The composition boundary for a page block: Compose owns its layout, drawing and keyed controls. RecyclerView still
 * owns page scroll and block identity. The subtitle preview hosts the player's renderer inside its composable.
 */
internal class ComposePageBlock(
    context: Context,
    initialItem: Item,
    private val declarative: (Item) -> @Composable () -> Unit,
) : AbstractComposeView(context) {
    private data class Model(val revision: Long, val item: Item)
    /** The keyed declarative controls this block draws. */
    val focus = PageFocus.Registry(this)
    private val model = mutableStateOf(Model(0, initialItem))
    /** The revision the composition last drew. */
    private var drawnRevision = 0L

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        // In RecyclerView a detached, cached holder keeps its composition; released holders dispose it.
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** RecyclerView publishes a model; the composition owns rendering and rebinding it. */
    fun bind(item: Item): Boolean {
        if (item.kind != model.value.item.kind || item.key != model.value.item.key) return false
        model.value = Model(model.value.revision + 1, item)
        return true
    }

    val hasPendingBinding: Boolean
        get() = model.value.revision != drawnRevision

    // A block whose controls take focus offers its owner, which moves focus into the composition; text-only blocks
    // offer nothing, so the Compose owner never becomes an extra D-pad stop between a page control and the toolbar/rail.
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (focus.hasControls) super.addFocusables(views, direction, focusableMode)
    }

    @Composable
    override fun Content() {
        val current = model.value
        androidx.compose.runtime.CompositionLocalProvider(PageFocus.Local provides focus) { declarative(current.item)() }
        SideEffect { drawnRevision = current.revision }
    }
}
