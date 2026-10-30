package tv.stream.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester

/**
 * Page focus by key, whichever toolkit draws the control. Native controls carry their key as the view tag; declarative
 * controls register a [FocusRequester] with the [Registry] of the [ComposePageBlock] that hosts them. While one of them
 * has focus, its key is mirrored onto the platform focus owner's tag, so `currentFocus.tag` keeps naming the control.
 */
internal object PageFocus {
    /** The declarative controls of one composition. */
    class Registry internal constructor(private val host: View) {
        private val controls = LinkedHashMap<String, FocusRequester>()
        private val regions = HashMap<String, Tv.Region>()
        val hasControls: Boolean get() = controls.isNotEmpty()
        fun owns(key: String): Boolean = controls.containsKey(key)
        fun keys(): Set<String> = controls.keys

        // Native controls stay focusable after a touch (Tv.control). The composition owner matches only while it draws
        // controls: a text-only block must never take the platform's default focus.
        fun register(key: String, requester: FocusRequester, region: Tv.Region? = null) {
            controls[key] = requester; if (region != null) regions[key] = region; ownerFocusable()
        }
        fun unregister(key: String, requester: FocusRequester) {
            if (controls[key] === requester) { controls.remove(key); regions.remove(key) }; ownerFocusable()
        }
        /** Where a popup opened while `key` has focus grows from. */
        fun region(key: String): Tv.Region? = regions[key]
        private fun ownerFocusable() { (host as? ViewGroup)?.getChildAt(0)?.isFocusableInTouchMode = controls.isNotEmpty() }

        /** Set while [request] moves platform focus into the composition: a control passed through on the way stays quiet. */
        var entering: String? = null
            private set

        fun request(key: String): Boolean {
            val requester = controls[key] ?: return false
            val owner = (host as? ViewGroup)?.getChildAt(0) ?: return false
            try {
                requester.requestFocus()
                if (owner.hasFocus() && owner.tag == key) return true
                // A native view (the rail, a toolbar control) holds focus: the platform must give the owner focus first.
                entering = key
                try { owner.requestFocus(); requester.requestFocus() } finally { entering = null }
                return owner.hasFocus() && owner.tag == key
            } catch (detached: IllegalStateException) { return false }
        }

        /**
         * The platform view holding the composition's focus (the AndroidComposeView) is tagged with the focused key.
         * The tag stays when focus leaves: the page records the key it is leaving from the platform focus change,
         * which arrives after the composition's own.
         */
        fun focused(owner: View, key: String, focused: Boolean) { if (focused) owner.tag = key }
        /** Whether a control gaining focus should tell its page (not while [request] passes through it). */
        fun announces(key: String): Boolean = entering == null || entering == key

        val view: View get() = host
    }

    val Local = compositionLocalOf<Registry?> { null }

    /** The registry that owns `key` under `root`, if a declarative control has it. */
    fun owner(root: View, key: String): Registry? {
        if (root is ComposePageBlock) return root.focus.takeIf { it.owns(key) }
        if (root is ViewGroup) for (i in 0 until root.childCount) { val found = owner(root.getChildAt(i), key); if (found != null) return found }
        return null
    }

    /** The focused declarative control's region, when `focus` is a composition owner (a popup's contextual opener). */
    fun focusedRegion(focus: View?): Tv.Region? {
        val key = focus?.tag as? String ?: return null
        val block = focus.parent as? ComposePageBlock ?: return null
        return block.focus.region(key)
    }

    /** Whether a visible control under `root` is keyed `key`. */
    fun has(root: View, key: String): Boolean = owner(root, key) != null || root.findViewWithTag<View>(key) != null

    /** Focuses the control keyed `key` under `root`; false when none is shown or it can't take focus. */
    fun request(root: View, key: String): Boolean {
        if (key.isEmpty()) return false
        val owner = owner(root, key)
        if (owner != null) return owner.request(key)
        val view = root.findViewWithTag<View>(key) ?: return false
        return view.isShown && view.isFocusable && view.requestFocus()
    }

    /** The first focus target under `root`: a tagged native control, or a block with declarative controls. */
    fun first(view: View): View? {
        if (view.visibility != View.VISIBLE) return null
        if (view is ComposePageBlock && view.focus.hasControls) return view
        if (view.isFocusable && view.tag is String) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) { val found = first(view.getChildAt(i)); if (found != null) return found }
        return null
    }
}
