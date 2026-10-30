package tv.stream.app.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import tv.stream.app.PlayerControlsState
import tv.stream.app.R
import tv.stream.playback.ControlsOverlay
import tv.stream.playback.R as PlaybackR

/** App-owned declarative player controls; the shared overlay owns transport, scrubbing and inactivity timers. */
internal class ComposePlayerControls private constructor(
    context: Context,
    private val layer: FrameLayout,
    private val state: StateFlow<PlayerControlsState>,
    private val action: (Int) -> Unit,
    private val onReady: () -> Unit,
) : AbstractComposeView(context), ControlsOverlay.Presentation {
    val focus = PageFocus.Registry(this)
    private var transport by mutableStateOf(ControlsOverlay.State())
    private var input: ControlsOverlay.Input? = null
    private var layoutChanged: Runnable? = null
    private var clearance = 0
    private var endInset by mutableIntStateOf(Tv.dp(context, 48f))
    private var touches = 0
    private var drags = 0
    private var dragStops = 0
    private var previews = 0
    private var lastPreview = -1
    private var readinessPosted = false
    private val bounds = HashMap<Int, Rect>()
    init {
        clipChildren = false; clipToPadding = false; isFocusable = false
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
    override fun bind(input: ControlsOverlay.Input, layoutChanged: Runnable) { this.input = input; this.layoutChanged = layoutChanged }
    override fun render(state: ControlsOverlay.State) { transport = state }
    override fun bottomClearance(): Int = if (clearance > 0) clearance else Tv.dp(context, 90f)
    fun headerInset(pixels: Int) { endInset = pixels }
    fun timelineDiagnostics(): String = "seekable=${transport.seekable}, position=${transport.progress}, input=${input != null}, touches=$touches, drags=$drags, stops=$dragStops, previews=$previews, last=$lastPreview"
    private fun key(id: Int): String = resources.getResourceName(id)
    fun focusedId(): Int = bounds.keys.firstOrNull { key(it) == (getChildAt(0)?.tag as? String) && hasFocus() } ?: View.NO_ID
    fun request(id: Int): Boolean = focus.request(key(id))
    private fun toggle() { action(R.id.progress); input?.togglePlay() }
    fun activate(id: Int) {
        val run = Runnable { if (id == R.id.progress) toggle() else action(id) }
        Tv.activate(getChildAt(0) ?: this, focus.region(key(id)), run)
    }
    fun origin(id: Int): View { request(id); return getChildAt(0) ?: this }
    /** Geometry comes from the composable that renders it, including any current focus growth. */
    fun bounds(id: Int): Rect? = bounds[id]?.takeIf { layer.isShown }?.let { Rect(it) }

    @Composable override fun Content() {
        val controls by state.collectAsState()
        CompositionLocalProvider(PageFocus.Local provides focus) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter).resourceBackground(PlaybackR.drawable.scrim_top))
                Box(Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter).resourceBackground(PlaybackR.drawable.scrim_bottom))
                Header(controls, Modifier.align(Alignment.TopStart))
                Timeline(transport, Modifier.align(Alignment.BottomCenter))
            }
        }
        SideEffect {
            if (!readinessPosted) {
                readinessPosted = true
                post { if (isAttachedToWindow) onReady() }
            }
        }
    }
    @Composable private fun Modifier.resourceBackground(drawable: Int): Modifier = with(PageControls) { this@resourceBackground.platter(drawable) }
    private fun Modifier.located(id: Int): Modifier = onGloballyPositioned { c ->
        val at = IntArray(2).also { getLocationOnScreen(it) }
        val window = IntArray(2).also { getLocationInWindow(it) }
        val r = c.boundsInWindow()
        bounds[id] = Rect((r.left + at[0] - window[0]).toInt(), (r.top + at[1] - window[1]).toInt(),
            (r.right + at[0] - window[0]).toInt(), (r.bottom + at[1] - window[1]).toInt())
    }
    @Composable private fun Modifier.named(id: Int): Modifier {
        DisposableEffect(id) { onDispose { bounds.remove(id) } }
        return semantics { testTagsAsResourceId = true }.testTag(key(id)).located(id)
    }
    @Composable private fun Header(controls: PlayerControlsState, modifier: Modifier) {
        val page = remember { PageRows.Page({}, "") }
        Row(modifier.fillMaxWidth().padding(start = dimensionResource(PlaybackR.dimen.safe_horizontal), top = 22.dp, end = with(LocalDensity.current) { endInset.toDp() }),
            verticalAlignment = Alignment.Top) {
            HeaderButton(R.id.control_stop, context.getString(PlaybackR.string.hint_back), R.drawable.ic_tv_chevron, page, true)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.height(40.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    BasicText(controls.title, Modifier.named(R.id.controls_title), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextBlocks.style(Tv.Font.SEMIBOLD, 20f, R.color.tv_text, false, 1.08f).copy(
                            letterSpacing = (-0.015).em, shadow = Shadow(Color(0xB3000000), Offset(0f, 1f), 8f)))
                }
                if (controls.detail.isNotEmpty()) BasicText(controls.detail, Modifier.padding(top = 4.dp).named(R.id.watching_info),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextBlocks.style(Tv.Font.REGULAR, 13f, R.color.tv_text_on_artwork, false).copy(shadow = Shadow(Color(0xB3000000), Offset(0f, 1f), 6f)))
            }
            Spacer(Modifier.width(24.dp))
            HeaderButton(R.id.control_watched, if (controls.watched) "Mark unwatched" else "Mark watched",
                if (controls.watched) R.drawable.ic_tv_unwatched else R.drawable.ic_tv_check, page)
            Spacer(Modifier.width(12.dp))
            HeaderButton(R.id.control_options, context.getString(R.string.player_audio_subtitles), R.drawable.ic_tv_subtitles, page)
            Spacer(Modifier.width(12.dp))
            HeaderButton(R.id.control_settings, context.getString(R.string.player_settings), R.drawable.ic_tv_settings, page)
        }
    }
    @Composable private fun HeaderButton(id: Int, label: String, icon: Int, page: PageRows.Page, reverse: Boolean = false) {
        val control = PageControls.control(key(id), label, Runnable { action(id) }, page, 20.dp)
        val scale by animateFloatAsState(if (control.focused || control.hovered) Tv.LIFT_ICON else 1f,
            tween(if (control.focused || control.hovered) 200 else 160), label = "player-focus")
        Box(Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale }.then(control.placed).located(id)
            .platterButton(control).then(control.input), contentAlignment = Alignment.Center) {
            val tint = PageControls.stateColor(if (reverse) R.color.tv_on_glass_secondary else R.color.tv_on_glass,
                control.focused, control.pressed, control.hovered)
            PageControls.Icon(icon, 18, tint, if (reverse) Modifier.rotate(180f) else Modifier)
        }
    }
    @Composable private fun Modifier.platterButton(control: PageControls.Control): Modifier = with(PageControls) {
        platter(R.drawable.tv_utility_button, control.focused, control.pressed, control.hovered)
    }
    @Composable private fun Timeline(value: ControlsOverlay.State, modifier: Modifier) {
        val density = LocalDensity.current
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val page = remember { PageRows.Page({}, "") }
        val control = PageControls.control(key(R.id.progress), context.getString(PlaybackR.string.control_seek),
            Runnable { toggle() }, page, 0.dp, pointer = false)
        val latest by rememberUpdatedState(value)
        val foreground = Color.White.copy(alpha = .9f)
        val track = Color.White.copy(alpha = .2f)
        val thumb = colorResource(R.color.tv_focus)
        var touching by remember { mutableStateOf(false) }
        BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = dimensionResource(PlaybackR.dimen.safe_horizontal)).padding(bottom = 22.dp)
            .onGloballyPositioned { c ->
                val origin = IntArray(2).also { getLocationInWindow(it) }
                val next = (height + origin[1] - c.boundsInWindow().top).toInt() + Tv.dp(context, 16f)
                if (clearance != next) { clearance = next; layoutChanged?.run() }
            }.height(52.dp)) {
            val width = with(density) { maxWidth.toPx() }
            fun fraction(x: Float): Int {
                val part = ((x - with(density) { 4.dp.toPx() }) / (width - with(density) { 8.dp.toPx() })).coerceIn(0f, 1f)
                return ((if (rtl) 1f - part else part) * 1000).toInt()
            }
            Canvas(Modifier.fillMaxWidth().height(48.dp).then(control.placed).located(R.id.progress)
                .pointerInput(value.seekable, rtl, width) {
                    if (!latest.seekable) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume(); (layer.parent as? View)?.requestFocus(); Sounds.input(); touching = true; touches++
                        var dragging = false
                        var released = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true; drags++; action(R.id.progress); input?.startScrub()
                                }
                                if (dragging) { lastPreview = fraction(change.position.x); previews++; input?.scrub(lastPreview); change.consume() }
                                if (change.changedToUpIgnoreConsumed()) { released = true; change.consume(); break }
                                if (!change.pressed) break
                            }
                        } finally {
                            touching = false
                            if (dragging) { dragStops++; input?.stopScrub() }
                            else if (released) toggle()
                        }
                    }
                }.then(control.input)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value.progress / 1000f, 0f..1f)
                    setProgress { if (value.seekable) { action(R.id.progress); input?.scrub((it * 1000).toInt()); true } else false }
                }) {
                val inset = 4.dp.toPx(); val span = size.width - 2 * inset
                fun x(part: Float) = inset + span * if (rtl) 1f - part else part
                val y = size.height / 2
                drawLine(track, Offset(inset, y), Offset(size.width - inset, y), 2.dp.toPx(), StrokeCap.Round)
                drawLine(track.copy(alpha = .4f), Offset(x(0f), y), Offset(x(value.buffered / 1000f), y), 2.dp.toPx(), StrokeCap.Round)
                drawLine(foreground, Offset(x(0f), y), Offset(x(value.progress / 1000f), y), 2.dp.toPx(), StrokeCap.Round)
                val grown = control.focused || control.hovered || touching
                val w = (if (grown) 4 else 2).dp.toPx(); val h = (if (grown) 14 else 10).dp.toPx()
                drawRoundRect(thumb, Offset(x(value.progress / 1000f) - w / 2, y - h / 2), Size(w, h), CornerRadius(w / 2, w / 2))
            }
            Readout(value, width, rtl, Modifier.align(Alignment.BottomStart))
        }
    }
    @Composable private fun Readout(value: ControlsOverlay.State, width: Float, rtl: Boolean, modifier: Modifier) {
        var feedback by remember { mutableStateOf(value.seekLabel) }
        var feedbackBack by remember { mutableStateOf(value.seekBack) }
        LaunchedEffect(value.seekLabel, value.seekBack) {
            if (value.seekLabel.isNotEmpty()) { feedback = value.seekLabel; feedbackBack = value.seekBack }
            else { delay(180); feedback = "" }
        }
        val feedbackAlpha by animateFloatAsState(if (value.seekLabel.isNotEmpty()) 1f else 0f, tween(180), label = "seek-feedback")
        val density = LocalDensity.current
        var elapsedWidth by remember { mutableIntStateOf(0) }
        var remainingWidth by remember { mutableIntStateOf(0) }
        var readoutWidth by remember { mutableIntStateOf(0) }
        var statusWidth by remember { mutableIntStateOf(0) }
        val textStyle = TextBlocks.style(Tv.Font.MEDIUM, 13f, R.color.tv_text, false).copy(fontFeatureSettings = "tnum",
            shadow = Shadow(Color(0xB3000000), Offset(0f, 1f), 4f))
        val inset = with(density) { 4.dp.toPx() }; val gap = with(density) { 12.dp.toPx() }; val statusGap = with(density) { 6.dp.toPx() }
        val fraction = value.progress / 1000f
        val head = inset + (width - 2 * inset) * if (rtl) 1 - fraction else fraction
        val left = if (rtl) remainingWidth + gap else 0f
        val right = if (rtl) width else width - remainingWidth - gap
        val statusFirst = if (rtl) head - elapsedWidth / 2f - statusWidth - statusGap < left
            else head + elapsedWidth / 2f + statusWidth + statusGap > right
        val hasStatus = feedback.isNotEmpty() || !value.playing
        val elapsedOffset = if (hasStatus && statusFirst != rtl) statusWidth + statusGap else 0f
        val x = (head - elapsedOffset - elapsedWidth / 2f).coerceIn(left, maxOf(left, right - readoutWidth))
        Box(modifier.fillMaxWidth().height(24.dp)) {
            Row(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(x.toInt(), 0) }.height(24.dp).named(R.id.timeline_info).onSizeChanged { readoutWidth = it.width },
                verticalAlignment = Alignment.CenterVertically) {
                @Composable fun status() {
                    if (feedback.isNotEmpty() || !value.playing) Row(Modifier.onSizeChanged { statusWidth = it.width },
                        verticalAlignment = Alignment.CenterVertically) {
                        if (feedback.isNotEmpty()) {
                            Row(Modifier.graphicsLayer { alpha = feedbackAlpha }, verticalAlignment = Alignment.CenterVertically) {
                                PageControls.Icon(if (feedbackBack) R.drawable.ic_rewind else R.drawable.ic_forward, 12,
                                colorResource(R.color.tv_text_on_artwork))
                                Spacer(Modifier.width(3.dp))
                                BasicText(feedback, Modifier.named(R.id.seek_bubble), style = textStyle.copy(fontSize = 12.sp))
                            }
                        } else Box(Modifier.size(14.dp).border(1.dp, colorResource(R.color.tv_text_on_artwork), CircleShape)
                            .named(R.id.timeline_paused).semantics { contentDescription = "Paused" }, contentAlignment = Alignment.Center) {
                            PageControls.Icon(R.drawable.ic_pause, 8, colorResource(R.color.tv_text_on_artwork))
                        }
                    }
                }
                if (hasStatus && statusFirst) { status(); Spacer(Modifier.width(6.dp)) }
                BasicText(value.elapsed, Modifier.named(R.id.elapsed).onSizeChanged { elapsedWidth = it.width }, style = textStyle)
                if (hasStatus && !statusFirst) { Spacer(Modifier.width(6.dp)); status() }
            }
            BasicText(value.remaining, Modifier.align(Alignment.CenterEnd).named(R.id.remaining).onSizeChanged { remainingWidth = it.width },
                style = textStyle.copy(color = colorResource(R.color.tv_text_on_artwork)))
        }
    }
    companion object {
        /** Install before PlayerScreen binds its optional presentation, retaining the platform fading layer. */
        fun install(controls: FrameLayout, state: StateFlow<PlayerControlsState>, action: (Int) -> Unit,
                    onReady: () -> Unit = {}): ComposePlayerControls {
            val host = ComposePlayerControls(controls.context, controls, state, action, onReady)
            // Attachment callbacks can run during ViewGroup's traversal of its existing children.
            controls.post {
                if (!controls.isAttachedToWindow) return@post
                controls.removeAllViews()
                controls.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            return host
        }
    }
}
