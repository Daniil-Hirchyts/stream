package tv.stream.app.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayList
import java.util.function.Consumer
import tv.stream.app.R

/**
 * A compact menu attached to its opener. Morph snapshots contain only the control or popup, never the page behind it.
 * A control's popup grows out from behind a copy of the control and shrinks back behind it, so the focused (white)
 * platter never blends into the menu's gray: it stays exactly as the real control looks underneath.
 */
class MorphDialog(context: Context, contextual: Boolean) : Dialog(context) {
    private inner class Page(val alert: Tv.Alert) {
        val content: ScrollView
        var focus: View? = null
        init {
            content = ScrollView(getContext()); content.isVerticalScrollBarEnabled = false; content.isFocusable = false
            content.addView(alert.surface, FrameLayout.LayoutParams(-1, -2))
            alert.surface.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (oldB > oldT && b - t != oldB - oldT && !running()) root.post { resize(this@Page, true) }
            }
        }
    }
    private val pages: MutableList<Page> = ArrayList()
    private val root: FrameLayout
    private val panel: FrameLayout
    private val shape = GradientDrawable()
    private val opener: View?
    private val anchor: View?
    /** The declarative control within [anchor] that opened this, when the anchor is its composition owner. */
    private val region: Tv.Region?
    private val owner: Activity?
    private val artwork: Boolean
    private val original = Rect()
    private val visual = Rect()
    private var openingImage: Bitmap? = null
    private var origin: ImageView? = null
    private var glide: Glide? = null
    private var motion: ValueAnimator? = null
    private var motionCleanup: Runnable? = null
    private var displayed: Page? = null
    private var attachment = -1
    private var closing = false
    private var closed = false
    private var cancelAfterClose = false
    private var leftPressed = false
    private var refreshPending = false
    private var backCallback: Any? = null
    var beforeClose: Runnable? = null

    constructor(context: Context) : this(context, true)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        var base: Context? = context
        while (base is ContextWrapper && base !is Activity) base = base.baseContext
        owner = if (base is Activity) base else null
        val activated = Tv.activatingControl()
        region = if (activated != null) Tv.activatingRegion()
                else if (owner != null && contextual) PageFocus.focusedRegion(owner.currentFocus) else null
        opener = if (activated != null) activated else if (owner == null || !contextual) null else owner.currentFocus
        var surface = opener
        val tagged: Any? = if (surface == null) null else surface.getTag(R.id.popup_anchor)
        artwork = tagged is View || region?.artwork == true
        if (tagged is View) surface = tagged
        else if (surface is ViewGroup) {
            val selected = selectedControl(surface); if (selected != null) surface = selected
        }
        anchor = surface
        if (anchor != null && anchor.isAttachedToWindow && anchorBounds(original)
                && (original.width() < context.resources.displayMetrics.widthPixels * .8f
                || original.height() < context.resources.displayMetrics.heightPixels * .4f)) openingImage = copyControl()
        else original.setEmpty()

        root = FrameLayout(context); root.tag = "popup-root"; root.clipChildren = false
        root.setOnClickListener { cancel() }; root.isFocusable = false
        panel = FrameLayout(context); panel.tag = "popup-panel"
        shape.setColor(context.getColor(R.color.tv_material_dialog)); shape.cornerRadius = dp(22f).toFloat()
        panel.background = shape; panel.clipToOutline = true; panel.elevation = dp(12f).toFloat()
        panel.isClickable = true; panel.isFocusable = false; panel.visibility = View.INVISIBLE
        root.addView(panel, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (oldR > oldL && oldB > oldT && (r - l != oldR - oldL || b - t != oldB - oldT) && !pages.isEmpty()) resize(top(), false)
        }
        setContentView(root)
        val window: Window? = window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); window.setWindowAnimations(0)
        }
        setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && Sounds.arrow(code)) Sounds.input()
            if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_CAPTIONS) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) cancel()
                return@setOnKeyListener true
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { leftPressed = true; back() }
                if (event.action == KeyEvent.ACTION_UP) {
                    // Android can consume the first arrow-down while leaving pointer mode. Its up must still work.
                    if (!leftPressed && !event.isCanceled) back()
                    leftPressed = false
                }
                return@setOnKeyListener true
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && currentFocus != null
                    && "submenu" == currentFocus!!.getTag(R.id.popup_navigation)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) currentFocus!!.performClick()
                return@setOnKeyListener true
            }
            false
        }
    }

    fun present(alert: Tv.Alert) {
        if (closing || closed) return
        if (!pages.isEmpty()) top().focus = currentFocus
        val page = Page(alert); pages.add(page)
        if (!isShowing) {
            super.show()
            if (window != null) window!!.setLayout(-1, -1)
            Sounds.play(Sounds.Sound.ALERT)
            root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    display(page, true, false)
                    return false // Lay out the snapshot before the dialog's first painted frame.
                }
            })
        } else display(page, false, false)
    }
    /** Both remote exit keys pop one level, or cancel at the root. */
    fun back() {
        if (closing || closed) return
        Sounds.play(Sounds.Sound.BACK)
        if (pages.size <= 1) { cancel(); return }
        pages.removeAt(pages.size - 1); val parent = top()
        display(parent, false, true)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { back() }
    override fun onStart() {
        super.onStart(); if (Build.VERSION.SDK_INT >= 33) backCallback = BackApi.register(this)
    }
    override fun onStop() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) BackApi.unregister(this, backCallback!!)
        super.onStop()
    }
    /** Callers check {@code Build.VERSION.SDK_INT >= 33}; the annotation lets lint see that through this class. */
    @androidx.annotation.RequiresApi(33)
    private object BackApi {
        fun register(dialog: MorphDialog): Any {
            val callback = android.window.OnBackInvokedCallback { dialog.back() }
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            return callback
        }
        fun unregister(dialog: MorphDialog, callback: Any) {
            dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback as android.window.OnBackInvokedCallback)
        }
    }

    private fun measure(page: Page): Rect {
        val limit = Math.min(dp(520f), Math.max(dp(160f), root.width - dp(48f)))
        val width = Math.min(limit, Math.max(dp(200f), preferredWidth(page.alert.surface, limit)))
        val available = Math.max(dp(92f), root.height - dp(96f))
        page.content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        return target(width, Math.min(available, page.content.measuredHeight))
    }
    /** Weighted rows and MATCH_PARENT fields must not turn an intrinsic measurement into the screen width. */
    private fun preferredWidth(view: View, limit: Int): Int {
        if (view.visibility == View.GONE) return 0
        var content = 0
        if (view is TextView) {
            val text: TextView = view
            val value: CharSequence? = if (text.length() == 0 && text is EditText) text.hint else text.text
            content = if (value == null) 0 else Math.ceil(android.text.Layout.getDesiredWidth(value, text.paint).toDouble()).toInt()
            content += text.compoundPaddingLeft + text.compoundPaddingRight
            if (text is EditText) content = Math.max(content, dp(300f))
        } else if (view is ViewGroup) {
            val group: ViewGroup = view
            val horizontal = group is LinearLayout && group.orientation == LinearLayout.HORIZONTAL
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i); if (child.visibility == View.GONE) continue
                var width = preferredWidth(child, limit)
                if (child.layoutParams is ViewGroup.MarginLayoutParams) {
                    val margins = child.layoutParams as ViewGroup.MarginLayoutParams
                    width += margins.leftMargin + margins.rightMargin
                }
                content = if (horizontal) content + width else Math.max(content, width)
            }
            content += view.paddingLeft + view.paddingRight
        } else content = view.minimumWidth
        val params: ViewGroup.LayoutParams? = view.layoutParams
        if (params != null && params.width > 0) content = Math.max(content, params.width)
        return Math.min(limit, Math.max(view.minimumWidth, content))
    }
    private fun source(): Rect {
        if (original.isEmpty) return Rect()
        val rect = Rect(original); val position = IntArray(2); root.getLocationOnScreen(position)
        rect.offset(-position[0], -position[1]); return rect
    }
    /** Keep the same attachment edge through nested pages; changing height must never flip the menu across its opener. */
    private fun target(width: Int, height: Int): Rect {
        val inset = dp(24f)
        val topInset = dp(72f)
        var leftInset = inset
        val gap = dp(10f)
        val source = source()
        // Menus follow the page's inset, including the wider Search/Details/Sources layouts.
        if (owner is tv.stream.app.MainActivity) leftInset += owner.contentStart()
        if (source.isEmpty) return rect(leftInset + (root.width - leftInset - inset - width) / 2,
                topInset + (root.height - topInset - inset - height) / 2, width, height)
        if (attachment < 0) {
            if (artwork && root.width - inset - source.right >= width + gap) attachment = RIGHT
            else if (artwork && source.left - leftInset >= width + gap) attachment = LEFT
            else if (root.height - inset - source.bottom >= height + gap) attachment = BELOW
            else if (source.top - topInset >= height + gap) attachment = ABOVE
            else if (root.width - inset - source.right >= width + gap) attachment = RIGHT
            else if (source.left - leftInset >= width + gap) attachment = LEFT
            else attachment = OVER
        }
        var x = source.left
        var y = source.top
        if (attachment == BELOW) y = source.bottom + gap
        else if (attachment == ABOVE) y = source.top - height - gap
        else if (attachment == RIGHT) x = source.right + gap
        else if (attachment == LEFT) x = source.left - width - gap
        else { x = source.right - width; y = source.centerY() - height / 2 }
        x = Math.max(leftInset, Math.min(root.width - inset - width, x))
        y = Math.max(topInset, Math.min(root.height - inset - height, y))
        return rect(x, y, width, height)
    }

    private fun display(page: Page, opening: Boolean, returning: Boolean) {
        if (!isShowing || closing || closed) return
        val changing = displayed != null && displayed !== page
        var animate = ValueAnimator.areAnimatorsEnabled()
        val focused: View? = if (returning) page.focus else if (displayed === page) currentFocus else null
        val focusedKey: Any? = if (focused == null) null else focused.tag
        val start = if (opening && !original.isEmpty) source() else currentBounds()
        // Capture the actual composite before cancelling or rebuilding anything. An
        // interrupted transition must continue from the pixels currently on screen.
        // The panel's own surface stays live: only its content is handed off. The focused row's white
        // platter isn't part of it either; one platter glides to the new page's focused row instead.
        val oldRow = if (changing && animate) focusedRow() else null
        val gliding = glide
        val glideFrom: Rect? = if (!changing || !animate) null else if (gliding != null) Rect(gliding.now) else if (oldRow != null) rowBounds(oldRow) else null
        val glideLook: Drawable? = if (glideFrom == null) null else if (gliding != null) gliding.surface.background else platterOf(oldRow!!)
        val previous = if (changing && animate) copyContent(panel, 2048, oldRow) else null
        val fromRadius = if (opening && !original.isEmpty) cornerRadius(start) else shape.cornerRadius
        stopMotion()
        if (page.alert.prepare != null) page.alert.prepare!!.run()
        val end = measure(page)
        if (start.isEmpty) start.set(end)
        animate = animate && (opening || changing || start != end)
        val incoming: View = page.content
        if (incoming.parent !== panel) {
            panel.removeAllViews(); detach(incoming)
            panel.addView(incoming, FrameLayout.LayoutParams(end.width(), end.height()))
        } else {
            incoming.layoutParams = FrameLayout.LayoutParams(end.width(), end.height())
        }
        displayed = page
        (incoming as ViewGroup).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        incoming.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val reveal = changing || opening && !original.isEmpty
        // A control's copy stays above the panel while the menu grows out from behind it; only where the menu
        // ends up over its opener does the copy fade. Artwork still dissolves into the panel.
        val lifted = if (opening && !artwork) lift(openingImage, start) else null
        if (lifted != null) origin = lifted
        val covers = lifted != null && Rect.intersects(start, end)
        val ghost = if (lifted != null) null else ghost(if (opening) openingImage else previous, start.width(), start.height())
        // Content-only layers over the panel's live surface: the old content leaves before the new arrives, so
        // a focused (white) row is never crossfaded against the gray surface or another white row.
        val handoff = ghost != null && changing
        if (ghost != null && !handoff) {
            // Artwork: cover the union of both surfaces, but keep the captured pixels at
            // their original size. Fade this one opaque layer over the new page:
            // fading both layers would darken the content twice at the midpoint.
            ghost.setBackgroundColor(context.getColor(R.color.tv_material_dialog))
            ghost.layoutParams = FrameLayout.LayoutParams(Math.max(start.width(), end.width()), Math.max(start.height(), end.height()))
        }
        incoming.alpha = if (animate && reveal && (ghost == null || handoff)) 0f else 1f
        incoming.translationX = (if (changing && animate) dp((if (returning) -8 else 8).toFloat()) else 0).toFloat()
        panel.alpha = 1f; setBounds(if (animate) start else end); panel.visibility = View.VISIBLE
        val moving = if (handoff && glideLook != null) Glide(glideLook, glideFrom!!) else null
        glide = moving
        if (animate) {
            val animation = ValueAnimator.ofFloat(0f, 1f)
            animation.duration = (if (opening) 280 else 240).toLong(); animation.interpolator = null
            val from = start
            animation.addUpdateListener { value ->
                val progress = value.animatedValue as Float
                val f = EASE.getInterpolation(progress)
                val blend = smooth(progress)
                setBounds(interpolate(from, end, f))
                shape.cornerRadius = fromRadius + (dp(22f) - fromRadius) * f
                if (lifted != null) {
                    if (covers) lifted.alpha = 1 - smooth(progress / .5f)
                    // Brief, so the focused row's white platter doesn't linger as a dim gray.
                    incoming.alpha = smooth((progress - .1f) / .4f)
                } else if (handoff) {
                    ghost!!.alpha = 1 - smooth(progress / .35f)
                    incoming.alpha = smooth((progress - .25f) / .4f)
                } else {
                    // Geometry eases quickly into place; the single covering layer
                    // dissolves over the full duration without a blank midpoint.
                    incoming.alpha = if (reveal && ghost == null) blend else 1f
                    if (ghost != null) ghost.alpha = 1 - blend
                }
                panel.alpha = if (opening && original.isEmpty) blend else 1f
                incoming.translationX = (if (changing) dp((if (returning) -8 else 8) * (1 - blend)) else 0).toFloat()
                if (moving != null) moving.step(f, progress, incoming.translationX)
            }
            startMotion(animation, Runnable {
                removeGhost(ghost)
                if (moving != null) { moving.finish(); if (glide === moving) glide = null }
            })
        } else { removeGhost(ghost); if (covers) lifted!!.alpha = 0f; shape.cornerRadius = dp(22f).toFloat() }
        val restored: View? = if (focusedKey == null) null else page.alert.surface.findViewWithTag(focusedKey)
        if (restored != null) restored.requestFocus()
        else if (page.alert.initialFocus != null) page.alert.initialFocus!!.requestFocus()
        if (incoming.findFocus() == null) {
            val first = firstFocusable(page.alert.buttons)
            if (first != null) first.requestFocus()
            else { page.content.isFocusableInTouchMode = true; page.content.requestFocus() }
        }
        if (moving != null && running()) moving.land(focusedRow())
        if (!animate && refreshPending) { refreshPending = false; root.post { refreshCurrent() } }
    }
    fun refreshCurrent() {
        if (!closed && !closing && !pages.isEmpty()) {
            if (running()) { refreshPending = true; return }
            display(top(), false, false)
        }
    }
    private fun resize(page: Page, animate: Boolean) {
        if (closed || closing || pages.isEmpty() || top() !== page || !isShowing || !page.content.isAttachedToWindow) return
        val end = measure(page); if (end == visual) return
        stopMotion(); page.content.layoutParams = FrameLayout.LayoutParams(end.width(), end.height())
        page.content.alpha = 1f; page.content.translationX = 0f; shape.cornerRadius = dp(22f).toFloat()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) { setBounds(end); return }
        val start = currentBounds(); val animation = ValueAnimator.ofFloat(0f, 1f); animation.duration = 180; animation.interpolator = EASE
        animation.addUpdateListener { value -> setBounds(interpolate(start, end, value.animatedValue as Float)) }
        startMotion(animation, null)
    }
    /**
     * Live-blur buttons (GlassView) draw nothing into a bitmap canvas, so a control is copied as its platter plus
     * its content. Artwork and plain views draw themselves.
     */
    /** The opener's on-screen rectangle: its declarative control's, or the whole view's. */
    private fun anchorBounds(out: Rect): Boolean {
        if (anchor == null) return false
        if (region == null) return anchor.getGlobalVisibleRect(out)
        out.set(region.bounds() ?: return false)
        return !out.isEmpty
    }
    private fun copyControl(): Bitmap? {
        if (anchor == null) return null
        if (region != null) {
            // Only the control's part of its composition, at its on-screen size.
            val image = Bitmap.createBitmap(Math.max(1, original.width()), Math.max(1, original.height()), Bitmap.Config.ARGB_8888)
            val at = IntArray(2); anchor.getLocationInWindow(at)
            try { val canvas = Canvas(image); canvas.translate((at[0] - original.left).toFloat(), (at[1] - original.top).toFloat()); anchor.draw(canvas); return image }
            catch (unsupportedSurface: RuntimeException) { image.recycle(); return null }
        }
        if (artwork) return copyView(anchor, 512)
        // At its on-screen pixel size, focus growth included: the copy sits over the real control while the
        // menu opens, and a smaller copy stretched to that size shows its text soft until the swap.
        val scale = if (anchor.width <= 0 || original.isEmpty) 1f else Math.max(1f, original.width().toFloat() / anchor.width)
        if (anchor !is ViewGroup) return copy(anchor, scale, Consumer { canvas -> anchor.draw(canvas) })
        return copy(anchor, scale, Consumer { canvas ->
            val background: Drawable? = anchor.background; if (background != null) background.draw(canvas)
            drawChildren(anchor, canvas)
        })
    }
    /** The panel's content; with {@code focusedRow}, without that row's platter or a gliding one. */
    private fun copyContent(group: ViewGroup, maxEdge: Int, focusedRow: View?): Bitmap? {
        val platter: Drawable? = if (focusedRow == null) null else focusedRow.background
        val alpha = if (platter == null) 255 else platter.alpha
        if (platter != null) platter.alpha = 0
        val skipped: View? = if (focusedRow == null || glide == null) null else glide!!.surface
        try { return copy(group, fit(group, maxEdge), Consumer { canvas -> drawChildren(group, canvas, skipped) }) }
        finally { if (platter != null) platter.alpha = alpha }
    }
    /** The focused row of the shown page, when it paints a focus platter. */
    private fun focusedRow(): View? {
        val focus: View? = currentFocus
        if (focus == null || !focus.isFocused || focus.background == null) return null
        var parent: android.view.ViewParent? = focus.parent
        while (parent != null) { if (parent === panel) return focus; parent = parent.parent }
        return null
    }
    private fun rowBounds(row: View): Rect {
        val rect = Rect(0, 0, row.width, row.height); panel.offsetDescendantRectToMyCoords(row, rect)
        val page: View? = if (panel.childCount > 0) panel.getChildAt(0) else null
        if (page != null) rect.offset(Math.round(page.translationX), 0)
        return rect
    }
    private fun platterOf(row: View): Drawable? {
        val state: Drawable.ConstantState? = row.background.current.constantState
        return if (state == null) null else state.newDrawable(context.resources).mutate()
    }
    /**
     * During a page change both pages have a white focused row. Crossfading them would dim each through gray, so
     * their platters are hidden and this one surface glides from the old row to the new while only text fades.
     */
    private inner class Glide(look: Drawable, val from: Rect) {
        val surface = View(context)
        val now = Rect()
        val to = Rect()
        var row: View? = null
        var hidden: Drawable? = null
        var hiddenAlpha = 0
        init {
            surface.background = look; surface.tag = "popup-focus"
            surface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            panel.addView(surface, 0, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT)); place(from)
        }
        /** The new page's focused row; without one the platter just fades. */
        fun land(target: View?) {
            if (target == null) return
            row = target; val background = target.background; hidden = background; hiddenAlpha = background.alpha; background.alpha = 0
        }
        fun step(f: Float, progress: Float, shift: Float) {
            val row = row
            // A returning page keeps its layout but isn't re-laid out, so isLaidOut() stays false: test its size.
            if (row != null && row.width > 0 && row.isFocused) {
                to.set(0, 0, row.width, row.height); panel.offsetDescendantRectToMyCoords(row, to)
                val rect = interpolate(from, to, f); rect.offset(Math.round(shift), 0); place(rect)
            } else if (row == null) surface.alpha = 1 - smooth(progress / .35f)
            // Focus moved on mid-glide: the new row shows its own platter, so this one steps aside.
            else if (!row.isFocused) surface.alpha = 0f
        }
        fun place(rect: Rect) {
            now.set(rect); val p = surface.layoutParams as FrameLayout.LayoutParams
            p.width = Math.max(1, rect.width()); p.height = Math.max(1, rect.height()); p.leftMargin = rect.left; p.topMargin = rect.top
            surface.layoutParams = p
        }
        fun finish() { if (hidden != null) hidden!!.alpha = hiddenAlpha; surface.background = null; detach(surface) }
    }
    private fun copyView(view: View, maxEdge: Int): Bitmap? { return copy(view, fit(view, maxEdge), Consumer { canvas -> view.draw(canvas) }) }
    /** The opener's copy over the real control, above the panel (same elevation, added later), casting no shadow. */
    private fun lift(image: Bitmap?, bounds: Rect): ImageView? {
        if (image == null || bounds.isEmpty) return null
        val lift = ImageView(context); lift.tag = "popup-origin"; lift.setImageBitmap(image)
        lift.scaleType = ImageView.ScaleType.FIT_XY; lift.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        lift.outlineProvider = null; lift.elevation = panel.elevation
        val params = FrameLayout.LayoutParams(bounds.width(), bounds.height(), Gravity.TOP or Gravity.LEFT)
        params.leftMargin = bounds.left; params.topMargin = bounds.top
        root.addView(lift, params); return lift
    }
    /** The anchor's corner radius at its on-screen (focus-grown) size. */
    private fun cornerRadius(bounds: Rect): Float {
        if (artwork) return dp(10f).toFloat()
        val half = Math.min(bounds.width(), bounds.height()) / 2f
        if (region != null) return Math.min(half, region.radius)
        val current: Drawable? = if (anchor == null || anchor.background == null) null else anchor.background.current
        if (current !is GradientDrawable) return Math.min(dp(22f).toFloat(), half)
        val platter: GradientDrawable = current
        if (platter.shape == GradientDrawable.OVAL) return half
        return Math.min(half, platter.cornerRadius * bounds.width() / Math.max(1, anchor!!.width))
    }
    private fun ghost(image: Bitmap?, width: Int, height: Int): ImageView? {
        if (image == null) return null
        val ghost = ImageView(context); ghost.tag = "popup-origin"; ghost.setImageBitmap(image)
        ghost.scaleType = ImageView.ScaleType.MATRIX
        val matrix = android.graphics.Matrix(); matrix.setScale(width.toFloat() / image.width, height.toFloat() / image.height)
        ghost.imageMatrix = matrix; ghost.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Keep captured text/icon pixels at their original size while only the
        // enclosing surface changes shape. MATCH_PARENT would stretch them each frame.
        panel.addView(ghost, FrameLayout.LayoutParams(width, height)); return ghost
    }
    private fun removeGhost(ghost: ImageView?) { if (ghost != null) { ghost.setImageDrawable(null); detach(ghost) } }
    private fun recycleOpening() { openingImage = null }
    private fun top(): Page { return pages[pages.size - 1] }
    private fun running(): Boolean { return motion != null && motion!!.isRunning }
    private fun startMotion(animation: ValueAnimator, cleanup: Runnable?) {
        motion = animation; motionCleanup = cleanup
        animation.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: android.animation.Animator) {
                if (motion !== animation) return
                motion = null; cleanupMotion()
                if (refreshPending) { refreshPending = false; root.post { this@MorphDialog.refreshCurrent() } }
            }
        }); animation.start()
    }
    private fun cleanupMotion() { val cleanup = motionCleanup; motionCleanup = null; if (cleanup != null) cleanup.run() }
    private fun stopMotion() {
        val previous = motion
        if (previous != null) {
            motion = null
            previous.removeAllUpdateListeners(); previous.removeAllListeners(); previous.cancel()
        }
        cleanupMotion()
    }
    private fun dp(value: Float): Int { return Tv.dp(context, value) }
    private fun currentBounds(): Rect {
        return if (panel.isLaidOut && panel.width > 1 && panel.height > 1)
            Rect(panel.left, panel.top, panel.right, panel.bottom) else Rect(visual)
    }
    private fun setBounds(rect: Rect) {
        if (visual == rect) return
        visual.set(rect); val p = panel.layoutParams as FrameLayout.LayoutParams
        p.width = Math.max(1, rect.width()); p.height = Math.max(1, rect.height()); p.leftMargin = rect.left; p.topMargin = rect.top; panel.layoutParams = p
    }
    override fun cancel() { if (!closed) { cancelAfterClose = true; dismiss() } }
    override fun dismiss() {
        if (closed) return
        val finishing = owner != null && (owner.isFinishing || owner.isDestroyed)
        if (closing) { if (finishing) closeNow(); return }
        val start = currentBounds()
        val animate = !finishing && isShowing && panel.isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()
        val returning = !original.isEmpty && anchor != null && anchor.isAttachedToWindow
        // Back into a control: the gray panel shrinks behind the control's copy, which matches the real control
        // underneath, so the white platter is never tinted gray and nothing changes when the window goes.
        val tucking = animate && returning && !artwork
        val visibleContent = if (!animate) null else if (tucking) copyContent(panel, 2048, null) else copyView(panel, 2048)
        val initialAlpha = panel.alpha
        val fromRadius = shape.cornerRadius
        closing = true; refreshPending = false; stopMotion()
        if (beforeClose != null) beforeClose!!.run()
        if (!animate) { closeNow(); return }
        val end = Rect()
        if (anchor != null && anchor.isAttachedToWindow && anchorBounds(end)) {
            val position = IntArray(2); root.getLocationOnScreen(position); end.offset(-position[0], -position[1])
        } else end.set(start)
        val image = if (returning) openingImage else null
        panel.removeAllViews()
        val lifted = if (tucking) (if (origin != null) origin else lift(image, end)) else null
        val ghost = if (tucking) null else ghost(image, end.width(), end.height())
        val outgoing = this.ghost(visibleContent, start.width(), start.height())
        val destination = end
        val toRadius = cornerRadius(end)
        val covered = Rect.intersects(start, end)
        if (lifted != null && covered) lifted.alpha = 0f
        val animation = ValueAnimator.ofFloat(0f, 1f); animation.duration = 200; animation.interpolator = null
        animation.addUpdateListener { value ->
            val progress = value.animatedValue as Float
            val blend = smooth(progress)
            val f = EASE.getInterpolation(progress)
            setBounds(interpolate(start, destination, f))
            if (tucking) {
                shape.cornerRadius = fromRadius + (toRadius - fromRadius) * f
                if (outgoing != null) outgoing.alpha = 1 - smooth(progress / .45f)
                if (lifted != null && covered) lifted.alpha = smooth(progress / .6f)
                // Hidden behind the copy by now: only its shadow is left to fade.
                panel.alpha = 1 - smooth((progress - .7f) / .3f)
                return@addUpdateListener
            }
            if (outgoing != null) outgoing.alpha = 1 - blend
            panel.alpha = initialAlpha * (if (original.isEmpty) 1 - blend else 1 - smooth((progress - .65f) / .35f))
        }
        startMotion(animation, Runnable { removeGhost(outgoing); removeGhost(ghost); closeNow() })
    }
    private fun closeNow() {
        if (closed) return; closed = true
        refreshPending = false; stopMotion()
        removeGhost(origin); origin = null
        recycleOpening()
        if (cancelAfterClose) { cancelAfterClose = false; super.cancel() }
        super.dismiss(); pages.clear()
        if (opener != null && opener.isAttachedToWindow && owner != null && !owner.isFinishing) opener.requestFocus()
    }

    companion object {
        private const val BELOW = 0
        private const val ABOVE = 1
        private const val RIGHT = 2
        private const val LEFT = 3
        private const val OVER = 4
        private val EASE = PathInterpolator(.2f, .7f, .15f, 1f)

        private fun fit(view: View, maxEdge: Int): Float { return Math.min(1f, maxEdge.toFloat() / Math.max(1, Math.max(view.width, view.height))) }
        private fun copy(view: View?, scale: Float, painter: Consumer<Canvas>): Bitmap? {
            if (view == null || view.width <= 0 || view.height <= 0) return null
            val image = Bitmap.createBitmap(Math.max(1, Math.round(view.width * scale)), Math.max(1, Math.round(view.height * scale)), Bitmap.Config.ARGB_8888)
            try { val canvas = Canvas(image); canvas.scale(scale, scale); painter.accept(canvas); return image }
            catch (unsupportedSurface: RuntimeException) { image.recycle(); return null }
        }
        private fun drawChildren(group: ViewGroup, canvas: Canvas) { drawChildren(group, canvas, null) }
        /** Children at their positions and opacity, without the parent's own surface. */
        private fun drawChildren(group: ViewGroup, canvas: Canvas, skipped: View?) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child === skipped || child.visibility != View.VISIBLE || child.alpha <= 0) continue
                val saved = canvas.save(); canvas.translate(child.x, child.y)
                if (child.alpha < 1) canvas.saveLayerAlpha(0f, 0f, child.width.toFloat(), child.height.toFloat(), Math.round(child.alpha * 255))
                child.draw(canvas); canvas.restoreToCount(saved)
            }
        }
        private fun clamp(value: Float): Float { return Math.max(0f, Math.min(1f, value)) }
        private fun smooth(value: Float): Float { val t = clamp(value); return t * t * (3 - 2 * t) }
        private fun rect(x: Int, y: Int, width: Int, height: Int): Rect { return Rect(x, y, x + width, y + height) }
        private fun interpolate(a: Rect, b: Rect, f: Float): Rect {
            return Rect(Math.round(a.left + (b.left - a.left) * f), Math.round(a.top + (b.top - a.top) * f),
                    Math.round(a.right + (b.right - a.right) * f), Math.round(a.bottom + (b.bottom - a.bottom) * f))
        }
        private fun detach(view: View) { if (view.parent is ViewGroup) (view.parent as ViewGroup).removeView(view) }
        private fun selectedControl(group: ViewGroup): View? {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.isSelected && child.isClickable) return child
                if (child is ViewGroup) { val selected = selectedControl(child); if (selected != null) return selected }
            }
            return null
        }
        private fun firstFocusable(view: View): View? {
            if (view.visibility != View.VISIBLE) return null
            if (view.isFocusable) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                val child = firstFocusable(view.getChildAt(i)); if (child != null) return child
            }
            return null
        }
    }
}
