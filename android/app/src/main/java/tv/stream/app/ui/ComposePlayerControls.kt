package tv.stream.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.StateFlow
import tv.stream.app.PlayerControlsState
import tv.stream.app.R

/**
 * Final composition boundary: app control state is rendered by Compose while the timeline and controls retain
 * their native view identities, touch handlers and remote focus. The outer controls layer stays under player_root
 * so the shared player's focus return, fades and complete subtitle clearance keep the same coordinate system.
 */
internal class ComposePlayerControls private constructor(
    context: Context,
    private val content: FrameLayout,
    private val state: StateFlow<PlayerControlsState>,
    private val onReady: () -> Unit,
) : AbstractComposeView(context) {
    private var readinessPosted = false
    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (content.isAttachedToWindow) content.addFocusables(views, direction, focusableMode)
    }

    @Composable
    override fun Content() {
        val controls by state.collectAsState()
        AndroidView(factory = { content }, update = { native ->
            native.findViewById<TextView>(R.id.controls_title).text = controls.title
            native.findViewById<TextView>(R.id.watching_info).apply {
                text = controls.detail
                visibility = if (controls.detail.isEmpty()) View.GONE else View.VISIBLE
            }
            native.findViewById<ImageView>(R.id.control_watched).apply {
                isActivated = controls.watched
                setImageResource(if (controls.watched) R.drawable.ic_tv_unwatched else R.drawable.ic_tv_check)
                contentDescription = if (controls.watched) "Mark unwatched" else "Mark watched"
                tooltipText = contentDescription
            }
        })
        SideEffect {
            if (!readinessPosted) {
                readinessPosted = true
                // Let AndroidView finish attaching its native subtree before media callbacks can look up IDs.
                post { if (isAttachedToWindow) onReady() }
            }
        }
    }

    companion object {
        /** Called on an attached controls layer, after native listeners bind and before opening a source. */
        fun install(controls: FrameLayout, state: StateFlow<PlayerControlsState>, onReady: () -> Unit = {}): ComposePlayerControls {
            val native = object : FrameLayout(controls.context) {
                override fun focusSearch(focused: View, direction: Int): View? =
                    controls.focusSearch(focused, direction)
            }.apply {
                clipChildren = controls.clipChildren
                clipToPadding = controls.clipToPadding
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val host = ComposePlayerControls(controls.context, native, state, onReady)
            // The parent can itself be in dispatchAttachedToWindow. Finish that traversal before
            // replacing children; the new composition then receives the complete view-tree owners.
            controls.post {
                if (!controls.isAttachedToWindow) return@post
                while (controls.childCount > 0) {
                    val child = controls.getChildAt(0)
                    controls.removeViewAt(0)
                    native.addView(child)
                }
                controls.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            return host
        }
    }
}
