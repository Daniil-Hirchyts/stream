package tv.stream.app.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import java.util.function.Consumer
import java.util.function.DoubleSupplier
import tv.stream.app.R

/**
 * Shared view building after tvOS: type, focus motion, platters and alerts, and the click/touch/remote wiring every
 * control uses. Sizes are in dp on the 960×540 dp TV canvas, half of tvOS's 1920×1080 points.
 */
object Tv {
    private val EASE = DecelerateInterpolator(2.2f)
    /**
     * Focus growth. Artwork grows most; buttons a little. List rows and tabs don't grow at all: they are wide, so any
     * scale visibly shifts their text sideways. They show focus by turning white instead.
     */
    const val LIFT_TILE = 1.1f
    const val LIFT_BUTTON = 1.05f
    const val LIFT_ROW = 1f
    /** Small round controls (back, search, page tools and arrows) grow more, so the change reads at their size. */
    const val LIFT_ICON = 1.12f
    private val ACTIVATING = ThreadLocal<View>()
    fun activatingControl(): View? { return ACTIVATING.get() }
    /**
     * Part of a view that a popup grows from and returns to: a declarative control inside its composition owner.
     * `bounds` reads the control's current rectangle in its window (null once it has gone); `radius` is its corner in px; `artwork` opens popups beside it, as from a poster.
     */
    class Region(val bounds: () -> android.graphics.Rect?, val radius: Float, val artwork: Boolean = false)
    private val ACTIVATING_REGION = ThreadLocal<Region>()
    fun activatingRegion(): Region? { return ACTIVATING_REGION.get() }
    /** Runs a control's action with `control` as the [activatingControl], where popups grow from. */
    fun activate(control: View, action: Runnable) { activate(control, null, action) }
    fun activate(control: View, region: Region?, action: Runnable) {
        val previous = ACTIVATING.get(); val previousRegion = ACTIVATING_REGION.get()
        ACTIVATING.set(control); if (region == null) ACTIVATING_REGION.remove() else ACTIVATING_REGION.set(region)
        try { action.run() } finally {
            if (previous == null) ACTIVATING.remove() else ACTIVATING.set(previous)
            if (previousRegion == null) ACTIVATING_REGION.remove() else ACTIVATING_REGION.set(previousRegion)
        }
    }

    fun dp(context: Context, value: Float): Int { return Math.round(value * context.resources.displayMetrics.density) }

    /**
     * Geist Sans: regular for body text, medium for headings and controls, semibold for emphasis and titles.
     */
    enum class Font(val resource: Int) {
        REGULAR(R.font.geist_regular), MEDIUM(R.font.geist_medium), SEMIBOLD(R.font.geist_semibold);
        private var typeface: Typeface? = null
        fun get(context: Context): Typeface {
            var current = typeface
            if (current == null) { current = context.resources.getFont(resource); typeface = current }
            return current
        }
    }
    /** Repeated interface roles: size and weight stay consistent across content, lists and menus. */
    enum class Type(val size: Float, val font: Font, val tracking: Float) {
        HERO(32f, Font.SEMIBOLD, -.015f), TITLE(28f, Font.SEMIBOLD, -.015f), SECTION(18f, Font.SEMIBOLD, -.005f),
        CONTROL(15f, Font.MEDIUM, 0f), BODY(15f, Font.REGULAR, 0f), META(13f, Font.MEDIUM, 0f),
        CAPTION(13.5f, Font.MEDIUM, 0f), EYEBROW(11.5f, Font.MEDIUM, .12f),
        MENU_TITLE(14f, Font.MEDIUM, 0f), VALUE(14f, Font.REGULAR, 0f);
    }
    fun type(context: Context, value: String?, role: Type, @ColorRes color: Int): TextView {
        val view = text(context, value, role.size, color)
        view.typeface = role.font.get(context); view.letterSpacing = role.tracking
        return view
    }
    /** Large titles use tighter tracking; smaller emphasized text stays closer to its natural spacing. */
    private const val TITLE_SP = 24f

    fun text(context: Context, value: String?, sp: Float, @ColorRes color: Int): TextView {
        val view = TextView(context)
        view.text = value; view.textSize = sp; view.setTextColor(context.getColorStateList(color))
        view.typeface = Font.REGULAR.get(context)
        view.includeFontPadding = false
        return view
    }
    fun heading(context: Context, value: String?, sp: Float, @ColorRes color: Int): TextView {
        val view = text(context, value, sp, color)
        view.typeface = Font.MEDIUM.get(context)
        return view
    }
    fun bold(context: Context, value: String?, sp: Float): TextView {
        val view = text(context, value, sp, R.color.tv_text)
        view.typeface = Font.SEMIBOLD.get(context)
        view.letterSpacing = if (sp >= TITLE_SP) -0.015f else -0.005f
        return view
    }
    /** Pixels as density-independent pixels, for Compose layouts that share the View gutters. */
    fun px(context: Context, px: Int): androidx.compose.ui.unit.Dp = (px / context.resources.displayMetrics.density).let { androidx.compose.ui.unit.Dp(it) }
    /**
     * Text that can sit over artwork gets a soft dark shadow, so it stays readable on light frames the scrim doesn't
     * fully darken. On the plain canvas the shadow is invisible.
     */
    fun onArtwork(view: TextView): TextView {
        val context = view.context
        view.setShadowLayer(dp(context, 5f).toFloat(), 0f, dp(context, 1f).toFloat(), 0xB3000000.toInt())
        return view
    }
    fun oneLine(view: TextView): TextView { view.setSingleLine(true); view.ellipsize = TextUtils.TruncateAt.END; return view }
    fun lines(view: TextView, lines: Int): TextView {
        view.maxLines = lines; view.ellipsize = TextUtils.TruncateAt.END; view.setLineSpacing(0f, 1.18f); return view
    }

    /**
     * Makes {@code view} a control: focusable by remote and pointer, clicked by OK, mouse or touch, and reporting
     * focus by key. Touch requests focus on press so the first tap both focuses and activates.
     */
    fun control(view: View, key: String, action: Runnable, focused: Consumer<String>, lift: Float) {
        control(view, key, action, focused, lift, true)
    }

    fun control(view: View, key: String, action: Runnable, focused: Consumer<String>, lift: Float, raise: Boolean) {
        control(view, key, action, focused, DoubleSupplier { lift.toDouble() }, raise)
    }

    /** {@code lift} is read at every change, for a control whose shape, and so its growth, changes. */
    fun control(view: View, key: String, action: Runnable, focused: Consumer<String>, lift: DoubleSupplier, raise: Boolean) {
        view.tag = key
        view.isFocusable = true; view.isFocusableInTouchMode = true; view.isClickable = true
        view.defaultFocusHighlightEnabled = false
        // The app's own sounds (Sounds) replace the platform's click.
        view.isSoundEffectsEnabled = false
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { Sounds.input(); if (!v.isFocused) v.requestFocus() }
            false
        }
        view.setOnClickListener { v -> focused.accept(key); Sounds.click(); activate(v, action) }
        view.setOnFocusChangeListener { v, has -> if (has) { focused.accept(key); Sounds.focus(v) }; animate(v, has, lift.asDouble.toFloat(), raise) }
        animate(view, view.isFocused, lift.asDouble.toFloat(), raise)
    }

    /**
     * A pointer over a [control] grows it exactly as focus does (as the player's buttons do), and its
     * background's hovered state fades in a soft platter. It never moves focus, so the remote keeps its place.
     * Call it after [control], whose focus listener it extends.
     */
    fun hover(control: View, lift: DoubleSupplier) {
        control.setOnHoverListener { view, event ->
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_HOVER_ENTER) animate(view, true, lift.asDouble.toFloat(), false)
            else if (action == MotionEvent.ACTION_HOVER_EXIT) animate(view, view.isFocused, lift.asDouble.toFloat(), false)
            false // The view still records its hovered state for the drawables.
        }
        val focus: View.OnFocusChangeListener? = control.onFocusChangeListener
        control.setOnFocusChangeListener { view, has ->
            focus?.onFocusChange(view, has)
            // Focus leaving a control the pointer is still over keeps its growth.
            if (!has && view.isHovered) animate(view, true, lift.asDouble.toFloat(), false)
        }
    }

    /** tvOS focus: the focused control grows and floats above its neighbours. */
    fun animate(view: View, focused: Boolean, lift: Float) { animate(view, focused, lift, true) }

    /** Some controls have enough layout clearance to grow without an elevation shadow. */
    fun animate(view: View, focused: Boolean, lift: Float, raise: Boolean) {
        val scale = if (focused) lift else 1f
        view.animate().cancel()
        view.animate().scaleX(scale).scaleY(scale).translationZ((if (focused && raise) dp(view.context, 10f) else 0).toFloat())
                .setDuration(if (focused) 200L else 160L).setInterpolator(EASE).start()
    }

    /** Clears motion left on a recycled view. */
    fun settle(view: View) { view.animate().cancel(); view.scaleX = 1f; view.scaleY = 1f; view.translationZ = 0f }

    fun icon(context: Context, drawable: Int, sizeDp: Int, @ColorRes tint: Int): ImageView {
        val icon = ImageView(context)
        icon.setImageResource(drawable); icon.imageTintList = context.getColorStateList(tint)
        icon.isDuplicateParentStateEnabled = true; icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp.toFloat()), dp(context, sizeDp.toFloat()))
        return icon
    }

    /** A platter button in one of the [Item.Action] styles. Its content turns dark on the white focus platter. */
    fun button(context: Context, action: Item.Action, focused: Consumer<String>): View {
        // Icon controls stay transparent at rest; other buttons may blur the artwork behind them.
        val button = if (action.style == Item.Action.Style.ICON) FrameLayout(context) else GlassView.surface(context)
        bindButton(button, action, focused)
        return button
    }

    /** Update a button's content and action without detaching its focusable surface. */
    fun bindButton(button: FrameLayout, action: Item.Action, focused: Consumer<String>) {
        val context = button.context
        val previousLayout: ViewGroup.LayoutParams? = button.layoutParams
        button.removeAllViews()
        button.contentDescription = action.label
        button.tooltipText = if (action.style == Item.Action.Style.ICON) action.label else null
        val content = LinearLayout(context)
        content.gravity = Gravity.CENTER
        content.isDuplicateParentStateEnabled = true
        val height = dp(context, (if (action.style == Item.Action.Style.CHIP) 36 else 44).toFloat())
        when (action.style) {
            Item.Action.Style.ICON -> {
                button.tooltipText = action.label
                button.setBackgroundResource(R.drawable.tv_utility_button)
                content.addView(icon(context, action.icon, 20, R.color.tv_on_glass))
                button.layoutParams = LinearLayout.LayoutParams(height, height)
                button.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            else -> {
                val chip = action.style == Item.Action.Style.CHIP
                content.orientation = LinearLayout.HORIZONTAL
                button.setBackgroundResource(if (chip) R.drawable.tv_chip else R.drawable.tv_button)
                val pad = dp(context, (if (chip) 16 else if (action.style == Item.Action.Style.PRIMARY) 26 else 20).toFloat())
                content.setPadding(pad, 0, pad, 0)
                content.minimumWidth = dp(context, (if (action.style == Item.Action.Style.PRIMARY) 148 else if (chip) 44 else 96).toFloat())
                if (action.icon != 0) {
                    val icon = icon(context, action.icon, if (chip) 16 else 18, R.color.tv_on_glass)
                    (icon.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(context, 8f)
                    content.addView(icon)
                }
                val label = oneLine(heading(context, action.shown(), (if (chip) 14 else 15).toFloat(), if (chip) R.color.tv_chip_text else R.color.tv_on_glass))
                if (action.style == Item.Action.Style.PRIMARY) { label.textSize = 16f; label.typeface = Font.SEMIBOLD.get(context) }
                label.isDuplicateParentStateEnabled = true
                content.addView(label)
                // A short value after the label: a chip's current filter, or the episode a Play button starts.
                if (!action.detail.isEmpty()) {
                    val value = oneLine(text(context, action.detail, (if (chip) 14 else 15).toFloat(), if (chip) R.color.tv_chip_text_secondary else R.color.tv_on_glass_secondary))
                    value.isDuplicateParentStateEnabled = true
                    value.setPadding(dp(context, 6f), 0, 0, 0)
                    content.addView(value)
                }
                button.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height)
                button.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }
        }
        button.isActivated = action.selected
        val press: Runnable = if (action.style == Item.Action.Style.CHIP) Runnable { Sounds.at(Sounds.Sound.CHIP, button); action.action.run() } else action.action
        control(button, action.key, press, focused, LIFT_BUTTON)
        if (action.style == Item.Action.Style.ICON) hover(button, DoubleSupplier { LIFT_BUTTON.toDouble() })
        if (previousLayout != null) button.layoutParams = previousLayout
    }

    // Context menus stay beside their opener. One surface contains the full navigation stack.
    class Alert(val dialog: Dialog, val surface: LinearLayout, val buttons: LinearLayout, val title: String?) {
        var initialFocus: View? = null
        var prepare: Runnable? = null
        var customBody: View? = null
        var buttonHost: View = buttons

        fun onAppear(prepare: Runnable?): Alert { this.prepare = prepare; return this }
        fun onClosing(action: Runnable?): Alert { (dialog as MorphDialog).beforeClose = action; return this }
        fun reset() {
            initialFocus = null
            if (buttonHost !== buttons) {
                (buttons.parent as ViewGroup).removeView(buttons)
                val index = surface.indexOfChild(buttonHost); val params = buttonHost.layoutParams
                surface.removeView(buttonHost); surface.addView(buttons, index, params); buttonHost = buttons
            }
            buttons.removeAllViews(); buttons.visibility = View.GONE
            body(null)
        }
        fun body(body: View?): Alert {
            if (customBody != null) surface.removeView(customBody)
            customBody = body
            if (body != null) {
                val params = LinearLayout.LayoutParams(-1, -2); params.topMargin = Tv.dp(dialog.context, 10f)
                surface.addView(body, surface.indexOfChild(buttonHost), params)
            }
            (buttonHost.layoutParams as LinearLayout.LayoutParams).topMargin = Tv.dp(dialog.context, (if (body == null) 4 else 16).toFloat())
            return this
        }
        fun scrollChoices(maxHeightDp: Int) {
            if (buttonHost !== buttons) return
            val context = dialog.context
            val scroll: ScrollView = object : ScrollView(context) {
                override fun onMeasure(width: Int, height: Int) {
                    super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(Math.min(Tv.dp(context, maxHeightDp.toFloat()), View.MeasureSpec.getSize(height)), View.MeasureSpec.AT_MOST))
                }
            }
            scroll.isVerticalScrollBarEnabled = false; scroll.isFocusable = false
            scroll.isVerticalFadingEdgeEnabled = true; scroll.setFadingEdgeLength(Tv.dp(context, 12f))
            val index = surface.indexOfChild(buttons); val params = buttons.layoutParams
            surface.removeView(buttons); scroll.addView(buttons, FrameLayout.LayoutParams(-1, -2))
            surface.addView(scroll, index, params); buttonHost = scroll
        }
        fun choice(key: String, label: String, detail: String, selected: Boolean, action: Runnable): View {
            return choice(key, label, detail, selected, action, Consumer<String> { })
        }
        fun choice(key: String, label: String, detail: String, selected: Boolean, action: Runnable, focused: Consumer<String>?): View {
            val row = row(key, label, detail, 0, if (selected) R.drawable.ic_tv_check else 0, action, focused)
            row.isSelected = selected; if (selected) initialFocus = row; return row
        }
        fun sourceChoice(key: String, label: String, detail: String, selected: Boolean, action: Runnable): View {
            val row = row(key, label, detail, 0, if (selected) R.drawable.ic_tv_check else 0, action, Consumer<String> { }, false, true)
            row.isSelected = selected; if (selected) initialFocus = row; return row
        }
        fun refresh() { (dialog as MorphDialog).refreshCurrent() }
        fun button(key: String, label: String, action: Runnable): View { return button(key, label, action, Consumer<String> { }) }
        fun button(key: String, label: String, action: Runnable, onFocused: Consumer<String>?): View {
            return row(key, label, "", menuIcon(label), 0, action, onFocused)
        }
        /** Not private: [Tv.choices] builds its rows through it. */
        fun row(key: String, label: String, detail: String, glyph: Int, trailing: Int, action: Runnable, onFocused: Consumer<String>?): View {
            return row(key, label, detail, glyph, trailing, action, onFocused, false, false)
        }
        private fun row(key: String, label: String, detail: String, glyph: Int, trailing: Int, action: Runnable, onFocused: Consumer<String>?, inline: Boolean, singleLabel: Boolean): View {
            val context = dialog.context
            val row = LinearLayout(context); row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = Tv.dp(context, 44f); row.setPadding(Tv.dp(context, 12f), Tv.dp(context, 11f), Tv.dp(context, 12f), Tv.dp(context, 11f))
            row.contentDescription = if (detail.isEmpty()) label else "$label. $detail"
            if (glyph != 0) {
                val icon = Tv.icon(context, glyph, 18, R.color.tv_on_glass)
                (icon.layoutParams as LinearLayout.LayoutParams).rightMargin = Tv.dp(context, 12f); row.addView(icon)
            }
            val text = Tv.lines(Tv.type(context, label, Type.CONTROL, R.color.tv_on_glass), if (singleLabel || inline) 1 else 2)
            text.isDuplicateParentStateEnabled = true
            val column = LinearLayout(context); column.orientation = LinearLayout.VERTICAL; column.isDuplicateParentStateEnabled = true
            column.addView(text)
            if (!detail.isEmpty() && !inline) {
                val value = Tv.oneLine(Tv.text(context, detail, 12f, R.color.tv_on_glass_secondary)); value.isDuplicateParentStateEnabled = true
                val spacing = LinearLayout.LayoutParams(-1, -2); spacing.topMargin = Tv.dp(context, 5f); column.addView(value, spacing)
            }
            row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!detail.isEmpty() && inline) {
                val value = Tv.oneLine(Tv.text(context, detail, 14f, R.color.tv_on_glass_secondary))
                value.isDuplicateParentStateEnabled = true; value.setPadding(Tv.dp(context, 8f), 0, 0, 0); row.addView(value)
            }
            if (trailing != 0) {
                val mark = Tv.icon(context, trailing, 14, R.color.tv_on_glass)
                (mark.layoutParams as LinearLayout.LayoutParams).leftMargin = Tv.dp(context, 10f); row.addView(mark)
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (buttons.childCount > 0) params.topMargin = Tv.dp(context, 2f)
            buttons.addView(row, params); buttons.visibility = View.VISIBLE
            val background = intArrayOf(0)
            val shape = Runnable {
                val wanted = if (!inline && (!detail.isEmpty() || text.lineCount > 1))
                    R.drawable.tv_dialog_button_multiline else R.drawable.tv_dialog_button
                if (background[0] != wanted) { background[0] = wanted; row.setBackgroundResource(wanted) }
            }
            shape.run(); text.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> shape.run() }
            Tv.control(row, key, action, onFocused ?: Consumer<String> { }, 1f, false)
            return row
        }
        fun submenu(key: String, label: String, contents: Consumer<Alert>): View { return submenu(key, label, "", contents) }
        fun submenu(key: String, label: String, detail: String, contents: Consumer<Alert>): View {
            val row = row(key, label, detail, menuIcon(label), R.drawable.ic_tv_chevron, Runnable {
                val child = createAlert(dialog.context, label, null, dialog, title)
                contents.accept(child); child.show()
            }, Consumer<String> { })
            row.setTag(R.id.popup_navigation, "submenu")
            return row
        }
        fun submenuInline(key: String, label: String, detail: String, contents: Consumer<Alert>): View {
            val row = row(key, label, detail, menuIcon(label), R.drawable.ic_tv_chevron, Runnable {
                val child = createAlert(dialog.context, label, null, dialog, title)
                contents.accept(child); child.show()
            }, Consumer<String> { }, true, true)
            row.setTag(R.id.popup_navigation, "submenu"); return row
        }
        fun back() { (dialog as MorphDialog).back() }
        fun show() { (dialog as MorphDialog).present(this) }
    }

    /** Icons only where the label names an action; value lists keep their single selected checkmark. */
    private fun menuIcon(label: String): Int {
        val text = label.lowercase(java.util.Locale.ROOT)
        if (text == "play" || text == "resume" || text == "playback") return R.drawable.ic_tv_play
        if (text == "previous episode") return R.drawable.ic_tv_previous
        if (text == "next episode") return R.drawable.ic_tv_next
        if (text == "sources" || text == "choose source") return R.drawable.ic_tv_list
        if (text == "mark unwatched") return R.drawable.ic_tv_unwatched
        if (text == "mark watched") return R.drawable.ic_tv_check
        if (text == "keep for later") return R.drawable.ic_tv_clock
        if (text.contains("favourite")) return R.drawable.ic_tv_heart_outline
        if (text.startsWith("remove") || text.startsWith("delete")) return R.drawable.ic_tv_delete
        if (text.contains("retry") || text.startsWith("refresh")) return R.drawable.ic_tv_refresh
        if (text.contains("cache") || text.startsWith("download")) return R.drawable.ic_tv_download
        if (text.startsWith("rename")) return R.drawable.ic_tv_edit
        if (text == "quality" || text == "speed" || text == "settings") return R.drawable.ic_tv_settings
        if (text.contains("subtitle")) return R.drawable.ic_tv_subtitles
        if (text == "audio") return R.drawable.ic_tv_audio
        if (text == "continue") return R.drawable.ic_tv_check
        if (text.contains("report") || text.contains("diagnostics")) return R.drawable.ic_tv_info
        return 0
    }

    fun alert(context: Context, title: String?, message: CharSequence?): Alert {
        val alert = createAlert(context, title, null, MorphDialog(context, false), null)
        if (message != null && message.length > 0) {
            (alert.buttons.layoutParams as LinearLayout.LayoutParams).topMargin = dp(context, 12f)
            val body = text(context, message.toString(), 14f, R.color.tv_text_secondary)
            body.setLineSpacing(dp(context, 3f).toFloat(), 1f); body.setPadding(dp(context, 12f), 0, dp(context, 12f), 0)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(context, 10f)
            alert.surface.addView(body, alert.surface.indexOfChild(alert.buttons), params)
        }
        return alert
    }
    fun alert(context: Context, title: String?, bodyView: View?): Alert {
        return createAlert(context, title, bodyView, MorphDialog(context), null)
    }
    private fun createAlert(context: Context, title: String?, body: View?, dialog: Dialog, parent: String?): Alert {
        val surface = LinearLayout(context); surface.orientation = LinearLayout.VERTICAL
        surface.setPadding(dp(context, 8f), dp(context, 20f), dp(context, 8f), dp(context, 8f))
        val header = LinearLayout(context); header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(context, 12f), 0, dp(context, 12f), dp(context, 8f))
        if (parent != null) {
            val breadcrumb = oneLine(text(context, parent, 11.5f, R.color.tv_text_tertiary))
            breadcrumb.setPadding(0, 0, 0, dp(context, 5f)); header.addView(breadcrumb)
        }
        val heading = type(context, title, Type.MENU_TITLE, R.color.tv_text_secondary)
        heading.tag = "popup-title"; heading.maxLines = 2; heading.ellipsize = TextUtils.TruncateAt.END; heading.setLineSpacing(0f, 1.12f)
        header.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        surface.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (body != null) {
            val existing: ViewGroup.LayoutParams? = body.layoutParams
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    if (existing != null && existing.height > 0) existing.height else ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(context, 8f); surface.addView(body, params)
        }
        val buttons = LinearLayout(context); buttons.orientation = LinearLayout.VERTICAL; buttons.visibility = View.GONE
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(context, (if (body == null) 4 else 12).toFloat()); surface.addView(buttons, params)
        return Alert(dialog, surface, buttons, title)
    }

    fun choices(context: Context, title: String, options: Array<String>, accept: Consumer<Int>, cancel: Runnable?): Alert {
        return choices(context, title, null, options, -1, accept, null, cancel)
    }
    fun choices(context: Context, title: String, body: View?, options: Array<String>, initialSelection: Int,
                           accept: Consumer<Int>, onPreview: Consumer<Int>?, cancel: Runnable?): Alert {
        val alert = alert(context, title, body)
        val actionIcons = title != "Choose source" && title != "Choose a video file" && initialSelection < 0
        val optionViews = arrayOfNulls<View>(options.size)
        // Long lists scroll independently so the heading and any preview stay visible.
        for (i in options.indices) {
            val index = i
            optionViews[i] = alert.row("choice:$i", options[i], "", if (actionIcons) menuIcon(options[i]) else 0, if (i == initialSelection) R.drawable.ic_tv_check else 0, Runnable {
                alert.dialog.dismiss(); accept.accept(index)
            }, Consumer<String> { _ ->
                onPreview?.accept(index)
                val option = optionViews[index]
                if (option != null) option.post {
                    option.requestRectangleOnScreen(
                        android.graphics.Rect(0, 0, option.width, option.height), false)
                }
            })
        }
        if (initialSelection >= 0 && initialSelection < options.size) {
            onPreview?.accept(initialSelection)
            alert.initialFocus = optionViews[initialSelection]
        } else if (options.size > 0) alert.initialFocus = optionViews[0]
        if (options.size > (if (body == null) 6 else 4)) alert.scrollChoices((if (body == null) 6 else 4) * 46)
        alert.dialog.setOnCancelListener { cancel?.run() }
        return alert
    }

    /**
     * Clips a scrolling view to its own box. Its parents let focused controls grow past their bounds, which also lets
     * content scrolled out of view draw over its neighbours; a clipping frame stops that. {@code bleed} pixels on each
     * side stay inside the clip for a focused item's growth; give the frame matching negative margins.
     */
    fun clipped(scrolling: ViewGroup, bleed: Int): FrameLayout {
        val frame = FrameLayout(scrolling.context)
        scrolling.clipChildren = false; scrolling.clipToPadding = false
        scrolling.setPadding(scrolling.paddingLeft + bleed, scrolling.paddingTop, scrolling.paddingRight + bleed, scrolling.paddingBottom)
        frame.addView(scrolling, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    /** Alpha fades on the clipped composite, rather than Android's padding-relative fading-edge bands. */
    fun faded(scrolling: ViewGroup, bleed: Int, lengthDp: Int): FrameLayout { return faded(scrolling, bleed, lengthDp, lengthDp) }
    fun faded(scrolling: ViewGroup, bleed: Int, topDp: Int, bottomDp: Int): FrameLayout {
        val frame: FrameLayout = ScrollFade(scrolling, dp(scrolling.context, topDp.toFloat()), dp(scrolling.context, bottomDp.toFloat()))
        scrolling.isVerticalFadingEdgeEnabled = false
        scrolling.clipChildren = false; scrolling.clipToPadding = false
        scrolling.setPadding(scrolling.paddingLeft + bleed, scrolling.paddingTop,
                scrolling.paddingRight + bleed, scrolling.paddingBottom)
        frame.addView(scrolling, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scrolling.setOnScrollChangeListener { _, _, _, _, _ -> frame.invalidate() }
        if (scrolling is androidx.recyclerview.widget.RecyclerView)
            scrolling.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(view: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) { frame.invalidate() }
            })
        return frame
    }

    private class ScrollFade(private val scrolling: ViewGroup, private val topLength: Int, private val bottomLength: Int) : FrameLayout(scrolling.context) {
        private val mask = android.graphics.Paint()
        init {
            mask.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            super.dispatchDraw(canvas)
            var top = if (scrolling.canScrollVertically(-1)) topLength.toFloat() else 0f
            var bottom = if (scrolling.canScrollVertically(1)) bottomLength.toFloat() else 0f
            val focus: View? = findFocus()
            if (focus != null && focus !== scrolling) {
                val rect = android.graphics.Rect(); focus.getDrawingRect(rect)
                offsetDescendantRectToMyCoords(focus, rect)
                // Leave the complete focus lift opaque. Other rows still fade at the viewport boundary.
                val clearance = Tv.dp(context, 10f)
                top = Math.min(top, Math.max(0, rect.top - clearance).toFloat())
                bottom = Math.min(bottom, Math.max(0, height - rect.bottom - clearance).toFloat())
            }
            if (top > 0) {
                mask.shader = android.graphics.LinearGradient(0f, 0f, 0f, top, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), top, mask)
            }
            if (bottom > 0) {
                mask.shader = android.graphics.LinearGradient(0f, height - bottom, 0f, height.toFloat(), 0xFF000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRect(0f, height - bottom, width.toFloat(), height.toFloat(), mask)
            }
            canvas.restoreToCount(layer)
        }
    }

    fun colors(context: Context, @ColorRes color: Int): ColorStateList { return context.getColorStateList(color) }
}
