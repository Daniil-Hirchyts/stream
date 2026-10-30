package tv.stream.app

import android.graphics.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.*
import kotlin.math.roundToInt

/** Inspect actual Compose layout and semantics rather than requiring one platform View per key. */
abstract class SearchHarness : AppHarness() {
    protected fun searchNode(tag: String): SemanticsNode =
            semanticNode(tag) ?: throw AssertionError("No declarative node tagged $tag")

    protected fun searchBounds(tag: String): Rect {
        val bounds = searchNode(tag).boundsInWindow
        return Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
    }

    protected fun searchQuery(): String = searchNode("search-query").config[SemanticsProperties.Text].joinToString("") { it.text }

    protected fun focusSearch(key: String) {
        browse!!.onActivity { activity -> assertNotNull("Focusable $key", focusKey(activity, key)) }
        waitUntil("$key receives focus", 5000) {
            val value = java.util.concurrent.atomic.AtomicBoolean()
            browse!!.onActivity { value.set(it.currentFocus?.tag == key) }
            value.get()
        }
    }

    protected fun keyboardKeys(): List<SemanticsNode> = semanticNodes { node ->
        val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
        tag.startsWith("keyboard:") && SemanticsProperties.Focused in node.config
    }

    protected fun keyboardRows(): List<List<SemanticsNode>> = keyboardKeys().groupBy { it.boundsInWindow.top.roundToInt() }
            .toSortedMap().values.map { row -> row.sortedBy { it.boundsInWindow.left } }

    protected fun assertCaptionFits(node: SemanticsNode) {
        val layouts = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
        fun collect(current: SemanticsNode) {
            if (SemanticsActions.GetTextLayoutResult in current.config) current.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            current.children.forEach(::collect)
        }
        instrumentation.runOnMainSync { collect(node) }
        assertTrue("Key caption has a measurable text layout", layouts.isNotEmpty())
        for (layout in layouts) assertFalse("Key captions fit", layout.hasVisualOverflow)
    }
}
