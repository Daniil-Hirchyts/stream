package tv.stream.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The composition boundary for a page block. Blocks with a declarative implementation ([declarative] returns one)
 * are drawn by Compose alone; the others still host their native controls inside AndroidView, which keeps the app's
 * drawing, accessibility and remote focus rules while blocks move to Compose one at a time. RecyclerView still owns
 * page/shelf scroll and identity.
 */
internal class ComposePageBlock(
    context: Context,
    initialItem: Item,
    initialContent: View?,
    private val render: (Item) -> View,
    private val declarative: (Item) -> (@Composable () -> Unit)? = { null },
    private val rebind: (View, Item) -> Boolean,
) : AbstractComposeView(context) {
    private data class Model(val revision: Long, val item: Item)
    private val model = mutableStateOf(Model(0, initialItem))
    private var nativeSlot: NativeSlot? = initialContent?.let { NativeSlot(context, it) }
    private val slot: NativeSlot get() = nativeSlot ?: NativeSlot(context, render(model.value.item)).also { nativeSlot = it }
    /** The revision a declarative block last drew. */
    private var drawnRevision = 0L

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        // In RecyclerView a detached, cached holder keeps its composition; released holders dispose it.
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        // Declarative blocks fill the page's width and wrap their height, as the text blocks they replace did.
        layoutParams = initialContent?.layoutParams
                ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** RecyclerView publishes a model; the composition owns rendering and rebinding it. */
    fun bind(item: Item): Boolean {
        if (item.kind != model.value.item.kind || item.key != model.value.item.key) return false
        model.value = Model(model.value.revision + 1, item)
        return true
    }

    val hasPendingBinding: Boolean
        get() = model.value.revision != (nativeSlot?.shownRevision ?: drawnRevision)

    // Native controls are exposed as focus targets directly, so the Compose owner never becomes an extra D-pad stop
    // between a page control and the toolbar/rail. Declarative blocks so far are text, which takes no focus.
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val native = nativeSlot
        if (native != null && native.isAttachedToWindow) native.addFocusables(views, direction, focusableMode)
    }

    @Composable
    override fun Content() {
        val current = model.value
        val block = declarative(current.item)
        if (block != null) {
            block()
            SideEffect { drawnRevision = current.revision }
            return
        }
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
