package tv.stream.app.ui

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent as PlatformKeyEvent
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import tv.stream.app.R

/**
 * What every declarative control on a page shares with [Tv.control]: focus by key, sounds, click by OK (press on the
 * first down, click on the up), pointer and touch, focus after a touch, hover, and exact accessibility labels. The
 * look comes from the same selector resources the native controls drew, so colours and platters have one source.
 */
internal object PageControls {
    /** Optional observer of caption layers after drawing, used to check the visible crossfade. */
    @Volatile var captionDrawn: ((String, Float) -> Unit)? = null
    /** [android.view.animation.DecelerateInterpolator] (2.2), the native controls' focus easing. */
    val Decelerate = Easing { 1f - Math.pow((1f - it).toDouble(), 4.4).toFloat() }

    /** A control's input state, and the modifiers that make it one. [placed] goes inside its growth layer. */
    class Control(val focused: Boolean, val pressed: Boolean, val hovered: Boolean, val placed: Modifier, val input: Modifier)

    /**
     * @param radius the control's corner, for the popup that grows from it
     * @param artwork popups open beside it, as from a poster, rather than below
     * @param onFocus hears every focus change, before the page does
     * @param onKey may consume a D-pad key on its way down
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun control(key: String, label: String, action: Runnable?, page: PageRows.Page, radius: Dp, artwork: Boolean = false,
                onFocus: ((Boolean) -> Unit)? = null, onKey: ((Int) -> Boolean)? = null, click: (() -> Unit)? = null, pointer: Boolean = true): Control {
        val owner = LocalView.current
        val registry = PageFocus.Local.current
        val interaction = remember { MutableInteractionSource() }
        val requester = remember { FocusRequester() }
        val focused by interaction.collectIsFocusedAsState()
        val pressed by interaction.collectIsPressedAsState()
        val hovered by interaction.collectIsHoveredAsState()
        val latestPage by rememberUpdatedState(page)
        val latestFocus by rememberUpdatedState(onFocus)
        val latestClick by rememberUpdatedState(click)
        val latestAction by rememberUpdatedState(action)
        var bounds by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val density = LocalDensity.current
        val radiusPx = with(density) { radius.toPx() }
        val region = remember(radiusPx, artwork) {
            Tv.Region({ bounds?.takeIf { it.isAttached }?.boundsInWindow()?.let { r ->
                android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()) } }, radiusPx, artwork)
        }
        var keyPress by remember { mutableStateOf<PressInteraction.Press?>(null) }
        // Pointer input outlives recomposition: it reads the current item's action through this state.
        val activate by rememberUpdatedState {
            latestPage.focused(key); Sounds.click()
            latestClick?.invoke()
            val run = latestAction
            if (run != null) Tv.activate(owner, region, run)
        }
        val isFocused by rememberUpdatedState(focused)
        if (action != null && registry != null) DisposableEffect(registry, key) {
            registry.register(key, requester, region)
            onDispose { registry.unregister(key, requester) }
        }
        if (action != null && key == page.restore) LaunchedEffect(key) {
            awaitFrame()
            // A remote move during layout owns focus; this delayed restoration must not move it back.
            if (latestPage.restore == key && !(owner.hasFocus() && owner.tag is String && owner.tag != key))
                runCatching { requester.requestFocus() }
        }
        val placed = Modifier.onGloballyPositioned { bounds = it }
        var input = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag(key)
        if (action != null) {
            input = input
                .focusRequester(requester)
                .onFocusChanged { state ->
                    registry?.focused(owner, key, state.isFocused)
                    latestFocus?.invoke(state.isFocused)
                    if (state.isFocused && registry?.announces(key) != false) {
                        latestPage.focused(key)
                        val at = bounds?.takeIf { it.isAttached }?.boundsInWindow()
                        val window = IntArray(2).also { owner.getLocationOnScreen(it) }
                        if (at != null) Sounds.focusAt(window[0] + at.center.x) else Sounds.focus(owner)
                    }
                }
                .onPreviewKeyEvent { event ->
                    onKey != null && event.type == KeyEventType.KeyDown && onKey(event.key.nativeKeyCode)
                }
                .onKeyEvent { event ->
                    // As a View: confirm keys press on the first down and click on the up that follows.
                    val native = event.nativeKeyEvent
                    if (!confirm(native.keyCode)) return@onKeyEvent false
                    if (native.action == PlatformKeyEvent.ACTION_DOWN) {
                        if (native.repeatCount == 0 && keyPress == null) {
                            keyPress = PressInteraction.Press(Offset.Zero).also { interaction.tryEmit(it) }
                        }
                    } else if (native.action == PlatformKeyEvent.ACTION_UP) {
                        val press = keyPress ?: return@onKeyEvent false
                        keyPress = null; interaction.tryEmit(PressInteraction.Release(press)); activate()
                    }
                    true
                }
                .then(if (pointer) Modifier.pointerInput(key) {
                    detectTapGestures(onPress = { at ->
                        Sounds.input()
                        if (!isFocused) runCatching { requester.requestFocus() }
                        val press = PressInteraction.Press(at); interaction.emit(press)
                        interaction.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    }, onTap = { activate() })
                } else Modifier)
                .hoverable(interaction)
                // Focusable after a touch too, as native controls are; `clickable` would only focus in key mode.
                .focusable(interactionSource = interaction)
                .semantics { role = Role.Button; onClick { activate(); true } }
        }
        return Control(focused, pressed, hovered, placed, input.semantics { contentDescription = label })
    }

    private fun confirm(code: Int): Boolean = code == PlatformKeyEvent.KEYCODE_DPAD_CENTER || code == PlatformKeyEvent.KEYCODE_ENTER
            || code == PlatformKeyEvent.KEYCODE_NUMPAD_ENTER || code == PlatformKeyEvent.KEYCODE_SPACE

    /** The focus growth of [Tv.animate]: `scale` while `grown`, optionally floating above the neighbours. */
    @Composable
    fun Modifier.lift(grown: Boolean, scale: Float, raise: Boolean, shape: Shape): Modifier {
        val duration = if (grown) 200 else 160
        val factor by animateFloatAsState(if (grown) scale else 1f, tween(duration, easing = Decelerate), label = "lift")
        val height by animateDpAsState(if (grown && raise) 10.dp else 0.dp, tween(duration, easing = Decelerate), label = "raise")
        return graphicsLayer {
            scaleX = factor; scaleY = factor
            shadowElevation = height.toPx(); this.shape = shape; clip = false
        }
    }

    private fun states(focused: Boolean, pressed: Boolean, hovered: Boolean, activated: Boolean): IntArray {
        val set = ArrayList<Int>(5)
        set.add(android.R.attr.state_enabled)
        if (focused) set.add(android.R.attr.state_focused)
        if (pressed) set.add(android.R.attr.state_pressed)
        if (hovered) set.add(android.R.attr.state_hovered)
        if (activated) set.add(android.R.attr.state_activated)
        return set.toIntArray()
    }

    /** A colour selector's colour for this state. */
    @Composable
    fun stateColor(@ColorRes selector: Int, focused: Boolean, pressed: Boolean = false, hovered: Boolean = false, activated: Boolean = false): Color {
        val context = LocalContext.current
        val list = remember(selector) { context.getColorStateList(selector) }
        return Color(list.getColorForState(states(focused, pressed, hovered, activated), list.defaultColor))
    }

    /**
     * Draws a drawable resource (a shape or state selector, which `painterResource` can't load) at this size, with the
     * selector's own enter and exit fades.
     */
    @Composable
    fun rememberDrawing(@DrawableRes resource: Int, focused: Boolean = false, pressed: Boolean = false, hovered: Boolean = false, activated: Boolean = false): DrawScope.() -> Unit {
        val context = LocalContext.current
        var tick by remember { mutableIntStateOf(0) }
        val drawable = remember(resource) {
            context.getDrawable(resource)!!.mutate().also {
                it.callback = object : Drawable.Callback {
                    private val main = Handler(Looper.getMainLooper())
                    override fun invalidateDrawable(who: Drawable) { tick++ }
                    override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) { main.postAtTime(what, who, time) }
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) { main.removeCallbacks(what, who) }
                }
            }
        }
        val wanted = states(focused, pressed, hovered, activated)
        drawable.state = wanted
        return {
            @Suppress("UNUSED_EXPRESSION") tick // Redraw while the selector fades.
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawIntoCanvas { drawable.draw(it.nativeCanvas) }
        }
    }

    /** A drawable resource behind this content. */
    @Composable
    fun Modifier.platter(@DrawableRes resource: Int, focused: Boolean = false, pressed: Boolean = false, hovered: Boolean = false, activated: Boolean = false): Modifier {
        val drawing = rememberDrawing(resource, focused, pressed, hovered, activated)
        return drawBehind(drawing)
    }

    /** A drawable resource over this content, such as artwork's focus rim. */
    @Composable
    fun Modifier.overlay(@DrawableRes resource: Int, focused: Boolean = false): Modifier {
        val drawing = rememberDrawing(resource, focused)
        return drawWithContent { drawContent(); drawing() }
    }

    @Composable
    fun Icon(@DrawableRes icon: Int, sizeDp: Int, tint: Color, modifier: Modifier = Modifier) {
        Image(painterResource(icon), contentDescription = null, modifier = modifier.size(sizeDp.dp), colorFilter = ColorFilter.tint(tint))
    }

    /** Backdrop blur behind a button or notice; there's no Compose equivalent, so this one stays a platform view. */
    @Composable
    fun Glass(modifier: Modifier, @DrawableRes background: Int) {
        if (!GlassView.LIVE_BLUR) return
        AndroidView(factory = { GlassView(it).apply { setBackgroundResource(background) } }, modifier = modifier)
    }

    /** A platter button in one of the [Item.Action] styles. Its content turns dark on the white focus platter. */
    @Composable
    fun Button(action: Item.Action, page: PageRows.Page, modifier: Modifier = Modifier, onFocus: ((Boolean) -> Unit)? = null, onHover: ((Boolean) -> Unit)? = null,
               onKey: ((Int) -> Boolean)? = null, click: (() -> Unit)? = null, activateInstead: Runnable? = null) {
        val icon = action.style == Item.Action.Style.ICON
        val chip = action.style == Item.Action.Style.CHIP
        val primary = action.style == Item.Action.Style.PRIMARY
        val shape: Shape = if (icon) CircleShape else RoundedCornerShape(100.dp)
        val height = if (chip) 36.dp else 44.dp
        // Chips make their own sound beside the shared select; it gives way to the more particular one.
        var center by remember { mutableStateOf(0f) }
        val view = LocalView.current
        val sounding = if (chip) ({ Sounds.atX(Sounds.Sound.CHIP, center); click?.invoke(); Unit }) else click
        val control = control(action.key, action.label, activateInstead ?: action.action, page, 22.dp, onFocus = onFocus, onKey = onKey, click = sounding)
        if (onHover != null) LaunchedEffect(control.hovered) { onHover(control.hovered) }
        val grown = control.focused || (icon && control.hovered)
        Box(modifier.height(height)
                .then(if (icon) Modifier.width(height) else Modifier)
                .lift(grown, Tv.LIFT_BUTTON, true, shape)
                .then(control.placed)
                .onGloballyPositioned { c ->
                    val at = IntArray(2).also { view.getLocationOnScreen(it) }
                    center = at[0] + c.boundsInWindow().center.x
                }
                .then(control.input)
                .semantics { selected = action.selected }) {
            if (icon) {
                Box(Modifier.matchParentSize().platter(R.drawable.tv_utility_button, control.focused, control.pressed, control.hovered))
                Icon(action.icon, 20, stateColor(R.color.tv_on_glass, control.focused, control.pressed), Modifier.align(Alignment.Center))
                return@Box
            }
            Glass(Modifier.matchParentSize(), R.drawable.tv_glass_capsule)
            Box(Modifier.matchParentSize().platter(if (chip) R.drawable.tv_chip else R.drawable.tv_button,
                    control.focused, control.pressed, activated = action.selected))
            Row(Modifier.align(Alignment.Center).padding(horizontal = (if (chip) 16 else if (primary) 26 else 20).dp)
                    .defaultMinSize(minWidth = (if (primary) 148 - 52 else if (chip) 44 - 32 else 96 - 40).dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                val text = stateColor(if (chip) R.color.tv_chip_text else R.color.tv_on_glass, control.focused, control.pressed, activated = action.selected)
                if (action.icon != 0) { Icon(action.icon, if (chip) 16 else 18, text); Spacer(Modifier.width(8.dp)) }
                BasicText(action.shown(), style = TextBlocks.style(if (primary) Tv.Font.SEMIBOLD else Tv.Font.MEDIUM, if (primary) 16f else if (chip) 14f else 15f, R.color.tv_text, false).copy(color = text),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A short value after the label: a chip's current filter, or the episode a Play button starts.
                if (action.detail.isNotEmpty()) {
                    val value = stateColor(if (chip) R.color.tv_chip_text_secondary else R.color.tv_on_glass_secondary, control.focused, control.pressed, control.hovered, action.selected)
                    BasicText(action.detail, Modifier.padding(start = 6.dp), style = TextBlocks.style(Tv.Font.REGULAR, if (chip) 14f else 15f, R.color.tv_text, false).copy(color = value),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    /** A row of buttons. Icon-only buttons are named beneath them while focused, as on tvOS. */
    @Composable
    fun Actions(actions: List<Item.Action>, page: PageRows.Page, start: Dp, end: Dp, centered: Boolean = false, top: Dp = 10.dp, bottom: Dp = 10.dp, endAligned: Boolean = false) {
        val icons = actions.any { it.style == Item.Action.Style.ICON }
        val scroll = rememberScrollState()
        var named by remember { mutableStateOf<Named?>(null) }
        Column(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val row = Modifier.horizontalScroll(scroll).widthIn(min = if (centered) maxWidth else 0.dp).padding(start = start, top = top, end = end, bottom = bottom)
                Row(row, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, if (centered) Alignment.CenterHorizontally else if (endAligned) Alignment.End else Alignment.Start)) {
                    for (action in actions) key(action.key) {
                        var slot by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        val show: (Boolean) -> Unit = { shown ->
                            if (action.style == Item.Action.Style.ICON) {
                                val center = slot?.takeIf { it.isAttached }?.boundsInParent()?.center?.x
                                if (shown && center != null) named = Named(action.label, center, scroll)
                                else if (!shown && named?.label == action.label) named = named?.copy(shown = false)
                            }
                        }
                        Button(action, page, Modifier.onGloballyPositioned { slot = it }, onFocus = if (icons) show else null, onHover = if (icons) show else null)
                    }
                }
            }
            if (icons) Caption(named)
        }
    }

    private data class Named(val label: String, val center: Float, val scroll: ScrollState, val shown: Boolean = true)

    /** The focused icon button's name: labels crossfade rather than replace visible text in place. */
    @Composable
    private fun Caption(named: Named?) {
        val slots = remember { arrayOf(mutableStateOf<Named?>(null), mutableStateOf<Named?>(null)) }
        var active by remember { mutableIntStateOf(0) }
        val target = named?.takeIf { it.shown }
        LaunchedEffect(target?.label, target?.center) {
            if (target == null) return@LaunchedEffect
            if (slots[active].value?.label != target.label) active = (active + 1) % 2
            slots[active].value = target
        }
        val text = TextBlocks.style(Tv.Font.REGULAR, 12.5f, R.color.tv_text_secondary, false)
        Box(Modifier.fillMaxWidth().height(28.dp).clearAndSetSemantics {}) {
            for (i in slots.indices) key(i) {
                val slot = slots[i].value
                if (slot != null) {
                    val on = i == active && target != null && target.label == slot.label
                    // A slot appears when it is first given a label: it starts hidden and below, then fades and rises in.
                    val fade = remember { Animatable(0f) }
                    val lift = remember { Animatable(4f) }
                    LaunchedEffect(on, i == active) {
                        val motion = tween<Float>(if (on) 180 else 120, if (on) 60 else 0)
                        launch { fade.animateTo(if (on) 1f else 0f, motion) }
                        lift.animateTo(if (on) 0f else if (i == active) 4f else -3f, motion)
                    }
                    BasicText(slot.label, style = text.copy(textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(min = 160.dp).padding(horizontal = 6.dp)
                                    .graphicsLayer { this.alpha = fade.value; translationY = lift.value.dp.toPx() }
                                    .drawWithContent { drawContent(); captionDrawn?.invoke(slot.label, fade.value) }
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(constraints.copy(minWidth = 0))
                                        layout(placeable.width, placeable.height) {
                                            val x = slot.center - slot.scroll.value - placeable.width / 2f
                                            placeable.place(x.coerceIn(0f, (constraints.maxWidth - placeable.width).coerceAtLeast(0).toFloat()).toInt(), 0)
                                        }
                                    })
                }
            }
        }
    }

    /** One compact footer; no paging controls between filters and posters. */
    @Composable
    fun Pagination(item: Item, page: PageRows.Page, start: Dp, end: Dp) {
        var previous: Item.Action? = null
        var next: Item.Action? = null
        for (action in item.actions) if (action.key.startsWith("previous-page:")) previous = action else next = action
        Box(Modifier.fillMaxWidth().padding(start = start, top = 18.dp, end = end, bottom = 20.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.platter(R.drawable.tv_pagination).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                PageButton(previous, true, page)
                Box(Modifier.size(92.dp, 40.dp), contentAlignment = Alignment.Center) {
                    BasicText(item.title, style = TextBlocks.style(Tv.Font.MEDIUM, 14f, R.color.tv_text_secondary, false).copy(textAlign = TextAlign.Center))
                }
                PageButton(next, false, page)
            }
        }
    }

    /** A round page arrow: a chevron in a circle, dimmed and inert where there is no page to go to. */
    @Composable
    private fun PageButton(action: Item.Action?, previous: Boolean, page: PageRows.Page) {
        if (action == null) {
            Box(Modifier.size(40.dp).alpha(.5f).platter(R.drawable.tv_button_round).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Icon(R.drawable.ic_tv_chevron, 18, colorOf(R.color.tv_text_tertiary), if (previous) Modifier.rotate(180f) else Modifier)
            }
            return
        }
        val control = control(action.key, action.label, action.action, page, 20.dp)
        Box(Modifier.size(40.dp).lift(control.focused, Tv.LIFT_ICON, false, CircleShape).then(control.placed).then(control.input)
                .platter(R.drawable.tv_button_round, control.focused, control.pressed), contentAlignment = Alignment.Center) {
            Icon(R.drawable.ic_tv_chevron, 18, stateColor(R.color.tv_on_glass, control.focused, control.pressed), if (previous) Modifier.rotate(180f) else Modifier)
        }
    }

    @Composable
    fun colorOf(@ColorRes color: Int): Color = androidx.compose.ui.res.colorResource(color)

    /** A centred empty, error or loading state with its next steps. */
    @Composable
    fun Message(item: Item, page: PageRows.Page, start: Dp, end: Dp) {
        Column(Modifier.fillMaxWidth().padding(start = start, top = 52.dp, end = end, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (item.icon != 0) Box(Modifier.size(64.dp).platter(R.drawable.tv_icon_disc), contentAlignment = Alignment.Center) {
                Icon(item.icon, 26, colorOf(R.color.tv_text))
            }
            BasicText(item.title, Modifier.padding(top = 18.dp), style = TextBlocks.style(Tv.Font.MEDIUM, 22f, R.color.tv_text, false).copy(textAlign = TextAlign.Center))
            if (item.detail.isNotEmpty()) BasicText(item.detail, Modifier.widthIn(max = 520.dp).padding(vertical = 8.dp),
                    style = TextBlocks.style(Tv.Font.REGULAR, 15f, R.color.tv_text_secondary, false, spacing = 1.22f).copy(textAlign = TextAlign.Center))
            if (item.actions.isNotEmpty()) Actions(item.actions, page, 0.dp, 0.dp, centered = true)
        }
    }

    /** An inline explanation inside a page that still has other content. Its buttons lift outside its glass. */
    @Composable
    fun Notice(item: Item, page: PageRows.Page, start: Dp, end: Dp) {
        Box(Modifier.fillMaxWidth().padding(start = start, top = 14.dp, end = end, bottom = 6.dp)) {
            if (GlassView.LIVE_BLUR) Glass(Modifier.matchParentSize(), R.drawable.tv_notice)
            else Box(Modifier.matchParentSize().platter(R.drawable.tv_notice))
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 12.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (item.icon == 0) R.drawable.ic_tv_info else item.icon, 22, colorOf(R.color.tv_text))
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
                    BasicText(item.title, style = TextBlocks.style(Tv.Font.MEDIUM, 15.5f, R.color.tv_text, false))
                    if (item.detail.isNotEmpty()) BasicText(item.detail, Modifier.padding(top = 4.dp),
                            style = TextBlocks.style(Tv.Font.REGULAR, 13.5f, R.color.tv_text_secondary, false, spacing = 1.18f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (item.actions.isNotEmpty()) Box(Modifier.widthIn(max = 420.dp)) {
                    Actions(item.actions, page, 0.dp, 8.dp, top = 4.dp, bottom = 4.dp, endAligned = true)
                }
            }
        }
    }

    /** Shared layout of a heading over chips: chips are buttons, so the heading lines up with the page edge. */
    @Composable
    fun Chips(item: Item, page: PageRows.Page, layout: TextBlocks.Layout) {
        Column(Modifier.fillMaxWidth()) {
            if (item.title.isNotEmpty()) TextBlocks.Header(item.title, null, false, 14, layout)
            Actions(item.actions, page, layout.start, layout.end)
        }
    }

}
