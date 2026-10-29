package tv.stream.playback

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView

import java.util.function.Supplier

/**
 * The options drawer, opened by clicking Options or using the remote's Menu button. Pages stack: OK or right
 * opens a page or picks a value, left or Back goes up a level. It keeps its own row focus, so it never competes
 * with the player for view focus.
 */
class MenuPanel(private val activity: Activity, private val listener: Listener) {
    interface Listener {
        fun onMenuToggled(open: Boolean)
        fun onMenuPageChanged()
    }

    /** A page is rebuilt each time it's shown or refreshed, so its values are always current. */
    interface Page {
        fun title(): String
        fun items(): List<Item>
        /** Style pages show a sample subtitle line while open. */
        fun previewsSubtitles(): Boolean {
            return false
        }

        companion object {
            fun of(title: String, previewsSubtitles: Boolean, items: Supplier<List<Item>>): Page {
                return object : Page {
                    override fun title(): String { return title }
                    override fun items(): List<Item> { return items.get() }
                    override fun previewsSubtitles(): Boolean { return previewsSubtitles }
                }
            }
        }
    }

    class Item private constructor(
        // Read by MenuPanel; Kotlin gives an outer class no access to a nested class's private members.
        val kind: Int,
        val label: String,
        val value: String?,
        val checked: Boolean,
        val action: Runnable,
    ) {
        var focusAction: Runnable? = null

        /** Optional immediate feedback while moving through appearance choices, without pressing OK. */
        fun onFocus(run: Runnable?): Item {
            focusAction = run
            return this
        }

        companion object {
            const val PAGE = 0
            const val CHOICE = 1
            const val ACTION = 2

            /** A row that opens another page, showing its current value; a current row gets the focus first. */
            fun page(label: String, value: String?, current: Boolean, open: Runnable): Item {
                return Item(PAGE, label, value, current, open)
            }

            /** One value of a list; the current one is ticked. */
            fun choice(label: String, value: String?, checked: Boolean, choose: Runnable): Item {
                return Item(CHOICE, label, value, checked, choose)
            }

            fun action(label: String, value: String?, run: Runnable): Item {
                return Item(ACTION, label, value, false, run)
            }
        }
    }

    private class Entry(val page: Page) {
        var items: List<Item>
        var focus: Int

        init {
            items = page.items()
            focus = 0
            for (i in items.indices) if (items[i].checked) focus = i
        }
    }

    private val panel: View
    private val back: View
    private val title: TextView
    private val scroll: ScrollView
    private val rows: ViewGroup
    private val inflater: LayoutInflater
    private val stack: MutableList<Entry> = ArrayList()
    private var open = false

    init {
        panel = activity.findViewById(R.id.menu)
        back = activity.findViewById(R.id.menu_back)
        title = activity.findViewById(R.id.menu_title)
        scroll = activity.findViewById(R.id.menu_scroll)
        rows = activity.findViewById(R.id.menu_rows)
        inflater = activity.layoutInflater
        // Keep a valid focus target in touch mode too. Otherwise Android consumes
        // the first D-pad OK after a pointer click just to restore keyboard focus.
        panel.isFocusableInTouchMode = true
        Ui.action(back, Runnable { pop() })
        Ui.action(activity.findViewById(R.id.menu_go_back), Runnable { pop() })
        Ui.action(activity.findViewById(R.id.menu_close), Runnable { close() })
    }

    fun isOpen(): Boolean {
        return open
    }

    fun previewsSubtitles(): Boolean {
        return open && !stack.isEmpty() && top().page.previewsSubtitles()
    }

    fun open(root: Page) {
        stack.clear()
        stack.add(Entry(root))
        render(true)
        if (open) return
        open = true
        panel.animate().cancel()
        panel.translationX = activity.resources.getDimension(R.dimen.panel_width)
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.requestFocus()
        panel.animate().translationX(0f).alpha(1f).setDuration(SLIDE_MS.toLong())
                .setInterpolator(DecelerateInterpolator()).start()
        listener.onMenuToggled(true)
    }

    fun push(page: Page) {
        stack.add(Entry(page))
        render(true)
        listener.onMenuPageChanged()
    }

    /** Goes up a level, or closes the menu from its first page. */
    fun pop() {
        if (stack.size <= 1) {
            close()
            return
        }
        stack.removeAt(stack.size - 1)
        refresh()
        listener.onMenuPageChanged()
    }

    fun close() {
        if (!open) return
        open = false
        panel.animate().cancel()
        panel.animate().translationX(panel.width.toFloat()).alpha(0f).setDuration(SLIDE_MS.toLong())
                .withEndAction { panel.visibility = View.GONE }.start()
        listener.onMenuToggled(false)
    }

    /** Rebuilds the visible page, for example after tracks or the episode changed. */
    fun refresh() {
        if (!open || stack.isEmpty()) return
        val entry = top()
        entry.items = entry.page.items()
        entry.focus = Math.max(0, Math.min(entry.items.size - 1, entry.focus))
        render(false)
    }

    fun onKeyDown(keyCode: Int): Boolean {
        val entry = top()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { move(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!entry.items.isEmpty() && entry.items[entry.focus].kind == Item.PAGE) activate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { activate(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK -> { pop(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS -> { close(); return true }
            else -> return false
        }
    }

    private fun top(): Entry {
        return stack[stack.size - 1]
    }

    private fun move(delta: Int) {
        val entry = top()
        val next = Math.max(0, Math.min(entry.items.size - 1, entry.focus + delta))
        if (next == entry.focus) return
        entry.focus = next
        bindRows()
        reveal(false)
        previewFocused()
    }

    private fun previewFocused() {
        val entry = top()
        if (!entry.items.isEmpty() && entry.items[entry.focus].focusAction != null)
            entry.items[entry.focus].focusAction!!.run()
    }

    private fun activate() {
        val entry = top()
        if (entry.items.isEmpty()) return
        entry.items[entry.focus].action.run()
    }

    private fun render(jump: Boolean) {
        val entry = top()
        title.text = entry.page.title()
        back.visibility = View.VISIBLE
        while (rows.childCount < entry.items.size) {
            rows.addView(inflater.inflate(R.layout.row_menu, rows, false))
        }
        while (rows.childCount > entry.items.size) rows.removeViewAt(rows.childCount - 1)
        bindRows()
        if (jump) scroll.scrollTo(0, 0)
        reveal(jump)
    }

    private fun bindRows() {
        val entry = top()
        for (i in entry.items.indices) {
            val item = entry.items[i]
            val row = rows.getChildAt(i)
            val index = i
            Ui.action(row, Runnable {
                // Bind the current entry each render; rows are reused when navigating pages.
                entry.focus = index
                bindRows()
                item.action.run()
            })
            row.setOnHoverListener { view, event ->
                if (item.focusAction != null && event.actionMasked == MotionEvent.ACTION_HOVER_ENTER && entry.focus != index) {
                    entry.focus = index
                    bindRows()
                    previewFocused()
                }
                false
            }
            (row.findViewById<View>(R.id.row_label) as TextView).text = item.label
            val value = row.findViewById<TextView>(R.id.row_value)
            value.text = if (item.value == null) "" else item.value
            value.visibility = if (item.value == null || item.value.isEmpty()) View.GONE else View.VISIBLE
            val mark = row.findViewById<ImageView>(R.id.row_icon)
            mark.setImageResource(if (item.kind == Item.PAGE) R.drawable.ic_chevron_right else R.drawable.ic_check)
            val ticked = item.kind == Item.CHOICE && item.checked
            mark.visibility = if (item.kind == Item.PAGE || ticked) View.VISIBLE else View.INVISIBLE
            mark.isActivated = ticked
            // Selection reaches the label and icon too, which recolour through their selectors.
            row.isSelected = i == entry.focus
        }
    }

    /** Scrolls the focused row into view, at once on a new page and smoothly while moving. */
    private fun reveal(jump: Boolean) {
        scroll.post {
            if (!open || stack.isEmpty()) return@post
            val row = rows.getChildAt(top().focus) ?: return@post
            if (row.height == 0 || row.isLayoutRequested) {
                // New rows aren't measured yet; try again after the next layout pass.
                scroll.post { reveal(jump) }
                return@post
            }
            val margin = row.height
            val y = scroll.scrollY
            val height = scroll.height
            var target = y
            if (row.top - margin < y) target = Math.max(0, row.top - margin)
            else if (row.bottom + margin > y + height) target = row.bottom + margin - height
            if (target == y) return@post
            if (jump) scroll.scrollTo(0, target)
            else scroll.smoothScrollTo(0, target)
        }
    }

    companion object {
        private const val SLIDE_MS = 220
    }
}
