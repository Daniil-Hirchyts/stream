package tv.stream.app.ui

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.android.awaitFrame
import tv.stream.app.DiagnosticReport
import tv.stream.app.ErrorQrPanel
import tv.stream.app.R
import tv.stream.playback.PlayerScreen
import tv.stream.playback.R as PlaybackR

/** Declarative loading, buffering, failure and next-episode layers; platform roots retain the shared player's fades. */
internal class ComposePlayerLayers(private val activity: Activity, private val developer: Boolean, private val nextEpisode: () -> Unit) : PlayerScreen.Layers {
    private var state by mutableStateOf(PlayerScreen.LayerState())
    private var input: PlayerScreen.LayerInput? = null
    private var showQr by mutableStateOf(false)
    private val qr = FrameLayout(activity).apply { id = R.id.error_diagnostics }
    private val hosts = ArrayList<Layer>()
    private val locations = HashMap<Int, Pair<Layer, Rect>>()
    override fun bind(input: PlayerScreen.LayerInput) { this.input = input }
    override fun render(state: PlayerScreen.LayerState) { this.state = state }
    fun diagnostics(report: DiagnosticReport?) {
        ErrorQrPanel.attach(qr, developer, report)
        showQr = developer && report != null
    }
    private fun key(id: Int) = activity.resources.getResourceName(id)
    fun focusedId(): Int = locations.keys.firstOrNull { id -> locations[id]?.first?.let { it.hasFocus() && it.getChildAt(0)?.tag == key(id) } == true } ?: View.NO_ID
    fun bounds(id: Int): Rect? = locations[id]?.takeIf { it.first.isShown }?.let { Rect(it.second) }
    fun request(id: Int): Boolean = hosts.any { it.focus.request(key(id)) }
    fun activate(id: Int) { when (id) {
        R.id.error_retry -> input?.retry()
        R.id.error_sources -> input?.sources()
        R.id.error_close -> input?.exit()
        R.id.up_next_play -> input?.playNext()
        R.id.up_next_cancel -> input?.cancelNext()
        R.id.next_episode_play -> nextEpisode()
    } }
    fun focusError() {
        val root = activity.findViewById<View>(R.id.error)
        if (root.hasFocus()) return
        root.post { if (root.isShown && !root.hasFocus()) request(if (state.retry) R.id.error_retry else R.id.error_sources) }
    }
    private inner class Layer(val drawing: @Composable Layer.() -> Unit) : AbstractComposeView(activity) {
        val focus = PageFocus.Registry(this)
        init { clipChildren = false; clipToPadding = false; isFocusable = false; setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }
        @Composable override fun Content() { CompositionLocalProvider(PageFocus.Local provides focus) { drawing() } }
        @Composable fun Modifier.named(id: Int): Modifier {
            DisposableEffect(id) { onDispose { locations.remove(id) } }
            return semantics { testTagsAsResourceId = true }.testTag(key(id)).onGloballyPositioned { c ->
                val screen = IntArray(2).also { getLocationOnScreen(it) }; val window = IntArray(2).also { getLocationInWindow(it) }
                val r = c.boundsInWindow()
                locations[id] = this@Layer to Rect((r.left + screen[0] - window[0]).toInt(), (r.top + screen[1] - window[1]).toInt(),
                    (r.right + screen[0] - window[0]).toInt(), (r.bottom + screen[1] - window[1]).toInt())
            }
        }
        @Composable fun Action(id: Int, label: String, icon: Int, modifier: Modifier = Modifier, offer: Boolean = false) {
            val page = remember { PageRows.Page({}, "") }
            val control = PageControls.control(key(id), label, Runnable { activate(id) }, page, 22.dp)
            val background = if (offer) R.drawable.tv_button else R.drawable.tv_dialog_button
            val fill = with(PageControls) { Modifier.platter(background, control.focused, control.pressed, control.hovered) }
            Row(modifier.height(if (offer) 48.dp else 44.dp).then(control.placed).named(id).then(fill).then(control.input)
                .padding(horizontal = if (offer) 22.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val color = PageControls.stateColor(R.color.tv_on_glass, control.focused, control.pressed, control.hovered)
                PageControls.Icon(icon, 18, color); Spacer(Modifier.width(if (offer) 8.dp else 12.dp))
                BasicText(label, style = TextBlocks.style(Tv.Font.MEDIUM, 15f, R.color.tv_text, false).copy(color = color), maxLines = 1)
            }
        }
    }
    private fun install(id: Int, drawing: @Composable Layer.() -> Unit) {
        val old = activity.findViewById<View>(id)
        val root = if (old is ViewGroup) old else {
            val parent = old.parent as ViewGroup; val index = parent.indexOfChild(old)
            FrameLayout(activity).apply {
                this.id = id; layoutParams = old.layoutParams; visibility = old.visibility
                parent.removeViewAt(index); parent.addView(this, index)
            }
        }
        root.setPadding(0, 0, 0, 0); root.background = null
        root.clipChildren = false; root.clipToPadding = false
        val host = Layer(drawing); hosts.add(host)
        root.removeAllViews()
        root.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (id == R.id.loading || id == R.id.error) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    fun install() {
        install(R.id.loading) {
            Column(Modifier.fillMaxSize().background(colorResource(PlaybackR.color.ink)).padding(horizontal = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                BasicText(state.title, Modifier.width(560.dp).named(R.id.loading_title), style = TextBlocks.style(Tv.Font.SEMIBOLD, 28f, R.color.tv_text, false, 1.12f).copy(textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (developer) Column(Modifier.width(560.dp).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(state.step.uppercase(), Modifier.named(R.id.loading_step), style = TextBlocks.style(Tv.Font.MEDIUM, 11.5f, R.color.accent, false).copy(textAlign = TextAlign.Center))
                    if (state.message.isNotEmpty()) BasicText(state.message, Modifier.padding(top = 10.dp).named(R.id.loading_message), style = TextBlocks.style(Tv.Font.REGULAR, 15f, R.color.tv_text, false).copy(textAlign = TextAlign.Center))
                }
                Spacer(Modifier.height(32.dp)); if (state.loading) Spinner(48) else Spacer(Modifier.size(48.dp))
            }
        }
        install(R.id.buffering) { if (state.buffering) Spinner(56) }
        install(R.id.error) {
            LaunchedEffect(state.errorTitle, state.retry, state.sources) { awaitFrame(); focusError() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(with(PageControls) { Modifier.width(400.dp).platter(R.drawable.tv_dialog) }.padding(start = 8.dp, top = 14.dp, end = 8.dp, bottom = 8.dp)) {
                        BasicText(state.errorTitle, Modifier.fillMaxWidth().padding(horizontal = 12.dp).named(R.id.error_title), style = TextBlocks.style(Tv.Font.MEDIUM, 16f, R.color.tv_text_secondary, false))
                        BasicText(state.errorMessage, Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp).named(R.id.error_message), style = TextBlocks.style(Tv.Font.REGULAR, 14f, R.color.tv_text_secondary, false, 1.2f))
                        Spacer(Modifier.height(16.dp))
                        if (state.retry) Action(R.id.error_retry, activity.getString(PlaybackR.string.hint_retry), R.drawable.ic_tv_refresh, Modifier.fillMaxWidth().padding(top = 2.dp))
                        if (state.sources) Action(R.id.error_sources, activity.getString(PlaybackR.string.hint_sources), R.drawable.ic_tv_list, Modifier.fillMaxWidth().padding(top = 2.dp))
                    }
                    if (showQr) { Spacer(Modifier.width(20.dp)); AndroidView(factory = { qr }, modifier = Modifier.width(332.dp)) }
                }
            }
        }
        install(R.id.up_next) {
            Column(with(PageControls) { Modifier.width(300.dp).platter(R.drawable.tv_dialog) }.padding(start = 8.dp, top = 20.dp, end = 8.dp, bottom = 8.dp)) {
                BasicText(activity.getString(PlaybackR.string.up_next), Modifier.padding(horizontal = 12.dp), style = TextBlocks.style(Tv.Font.MEDIUM, 16f, R.color.tv_text_secondary, false))
                BasicText(state.nextTitle, Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, end = 12.dp).named(R.id.up_next_title), style = TextBlocks.style(Tv.Font.MEDIUM, 15f, R.color.tv_text, false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(state.nextDetail, Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 12.dp).named(R.id.up_next_detail), style = TextBlocks.style(Tv.Font.REGULAR, 13f, R.color.tv_text_secondary, false))
                Spacer(Modifier.height(12.dp))
                Action(R.id.up_next_play, activity.getString(PlaybackR.string.hint_play_now), R.drawable.ic_tv_play, Modifier.fillMaxWidth().padding(top = 2.dp))
                Action(R.id.up_next_cancel, activity.getString(PlaybackR.string.hint_cancel), R.drawable.ic_tv_close, Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
        install(R.id.next_episode_offer) { Action(R.id.next_episode_play, "Next episode", R.drawable.ic_tv_play, offer = true) }
    }
    @Composable private fun Spinner(side: Int) {
        val turn by rememberInfiniteTransition(label = "buffering").animateFloat(0f, 8f, infiniteRepeatable(tween(1024, easing = LinearEasing)), label = "spokes")
        val color = colorResource(R.color.tv_text)
        val alphas = floatArrayOf(1f, .28f, .32f, .38f, .46f, .57f, .70f, .85f)
        Canvas(Modifier.size(side.dp)) {
            val scale = size.minDimension / 48f
            for (i in 0 until 8) rotate(i * 45f) {
                drawLine(color.copy(alpha = alphas[(i - turn.toInt() + 8) % 8]),
                    Offset(size.width / 2, 5 * scale), Offset(size.width / 2, 13 * scale), 4.2f * scale, StrokeCap.Round)
            }
        }
    }
}
