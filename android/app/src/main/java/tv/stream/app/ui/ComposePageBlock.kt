package tv.stream.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The composition boundary for a migrated page block. RecyclerView still owns page/shelf scroll and identity;
 * native controls inside AndroidView keep the app's established drawing, accessibility and remote focus rules.
 * This lets individual blocks move to Compose without replacing the complete TV focus tree in one release.
 */
internal class ComposePageBlock(
    context: Context,
    initialItem: Item,
    initialContent: View,
    private val render: (Item) -> View,
    private val rebind: (View, Item) -> Boolean
) : AbstractComposeView(context) {
    private data class Model(val revision: Long, val item: Item)
    private val model = mutableStateOf(Model(0, initialItem))
    private val slot = NativeSlot(context, initialContent)

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        // In RecyclerView a detached, cached holder keeps its composition; released holders dispose it.
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        layoutParams = initialContent.layoutParams
    }

    /** RecyclerView publishes a model; the composition owns rendering and rebinding it. */
    fun bind(item: Item): Boolean {
        if (item.kind != model.value.item.kind || item.key != model.value.item.key) return false
        model.value = Model(model.value.revision + 1, item)
        return true
    }

    val hasPendingBinding: Boolean
        get() = model.value.revision != slot.shownRevision

    // These compositions contain native controls only. Expose their focus targets directly so the
    // Compose owner never becomes an extra D-pad stop between a page control and the toolbar/rail.
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (slot.isAttachedToWindow) slot.addFocusables(views, direction, focusableMode)
    }

    @Composable
    override fun Content() {
        val current = model.value
        AndroidView(
            factory = { slot },
            modifier = Modifier,
            update = { it.show(current) }
        )
    }

    /** Rebinding/replacing and focus restoration happen atomically in AndroidView's composition update. */
    private inner class NativeSlot(context: Context, content: View) : FrameLayout(context) {
        var shownRevision = 0L
            private set

        init {
            clipChildren = false; clipToPadding = false
            layoutParams = content.layoutParams
            addView(content, contentParams(content))
        }

        override fun focusSearch(focused: View, direction: Int): View? =
            (this@ComposePageBlock.parent as? ViewGroup)?.focusSearch(focused, direction)

        fun show(model: Model) {
            if (shownRevision == model.revision) return
            shownRevision = model.revision
            val item = model.item
            val content = getChildAt(0)
            if (rebind(content, item)) return
            val focused = findFocus()
            val focusedKey = focused?.tag as? String
            // A replacement never lets the framework choose a transient neighbouring control.
            val owner = parent as? ViewGroup
            val policy = owner?.descendantFocusability
            if (focusedKey != null) {
                owner?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                owner?.requestFocus()
            }
            try {
                removeAllViews()
                val next = render(item)
                addView(next, contentParams(next))
            } finally {
                if (policy != null) owner.descendantFocusability = policy
                if (focusedKey != null) findViewWithTag<View>(focusedKey)?.requestFocus()
            }
        }

        private fun contentParams(view: View): LayoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
