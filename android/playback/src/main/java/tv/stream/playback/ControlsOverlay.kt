package tv.stream.playback

import android.app.Activity
import android.os.Handler
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * The playback overlay: title, draggable progress, time and pointer controls. It stays up while paused and fades a few
 * seconds after playback resumes.
 */
class ControlsOverlay(activity: Activity, private val session: PlaybackSession, private val handler: Handler,
        private val onVisibilityChanged: Runnable, private val presentation: Presentation? = null) {
    private val root: View
    /** Toolkit-independent values: no player or activity references leave the playback owner. */
    data class State(val title: String = "", val details: String = "", val elapsed: String = "0:00",
        val remaining: String = "", val progress: Int = 0, val buffered: Int = 0,
        val seekable: Boolean = false, val playing: Boolean = false, val seekLabel: String = "", val seekBack: Boolean = false)

    /** An app may render controls declaratively while sharing transport, drag and timeout behavior. */
    interface Presentation {
        fun bind(input: Input, layoutChanged: Runnable)
        fun render(state: State)
        /** Bottom of video to top of the complete toolbar, including the timeline's touch target and caption gap. */
        fun bottomClearance(): Int
    }
    interface Input {
        fun startScrub()
        fun scrub(value: Int)
        fun stopScrub()
        fun togglePlay()
    }
    private var state = State()
    private val native: NativePresentation?
    private var scrubbing = false
    /** Where a drag on the timeline would seek; the seek waits for the release. */
    private var dragTarget = C.TIME_UNSET
    private val player: Player
    private var shown = false
    /** A menu drawn above the overlay keeps it from fading on the inactivity timer. */
    private var held = false

    private val hide = Runnable { hide() }
    private val hideSeek = Runnable { hideSeek() }
    private val tick: Runnable = object : Runnable {
        override fun run() {
            update()
            if (shown) handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        this.player = session.player()
        root = activity.findViewById(R.id.controls)
        native = if (presentation == null) NativePresentation(activity) else null
        (presentation ?: native!!).bind(object : Input {
            override fun startScrub() {
                scrubbing = true; dragTarget = C.TIME_UNSET; handler.removeCallbacks(hide)
            }
            override fun scrub(value: Int) {
                val duration = player.duration
                if (duration <= 0 || duration == C.TIME_UNSET) return
                val target = duration * value.coerceIn(0, 1000) / 1000
                if (scrubbing) dragTarget = target else session.seekTo(target)
                update()
            }
            override fun stopScrub() {
                scrubbing = false
                if (dragTarget != C.TIME_UNSET) session.seekTo(dragTarget)
                dragTarget = C.TIME_UNSET; show()
            }
            override fun togglePlay() { session.togglePlay(); show() }
        }, onVisibilityChanged)
    }

    fun isShown(): Boolean {
        return shown
    }

    /** Keep captions above the full touch target, including its time labels and buttons. */
    fun bottomClearance(): Int {
        return (presentation ?: native!!).bottomClearance()
    }

    fun setTitle(text: String?) {
        state = state.copy(title = text.orEmpty()); publish()
    }

    /** The voice-over and subtitles under the title, such as "Eng.Original · Subtitles: English". */
    fun setDetails(text: String) {
        state = state.copy(details = text); publish()
    }

    fun show() {
        if (!shown) {
            shown = true
            Ui.fade(root, true)
            handler.post(tick)
            onVisibilityChanged.run()
        }
        update()
        handler.removeCallbacks(hide)
        if (active() && !held) handler.postDelayed(hide, HIDE_DELAY_MS)
    }

    /** While held, the overlay keeps its visibility; releasing it restarts the inactivity timer. */
    fun hold(hold: Boolean) {
        held = hold
        handler.removeCallbacks(hide)
        if (shown && active() && !held) handler.postDelayed(hide, HIDE_DELAY_MS)
    }

    fun hide() {
        handler.removeCallbacks(hide)
        handler.removeCallbacks(tick)
        if (!shown) return
        shown = false
        // A hidden Options button must not keep receiving OK after the inactivity timeout.
        if (root.hasFocus() && root.parent is View) {
            val surface = root.parent as View
            if (surface.isFocusable) surface.requestFocus()
        }
        Ui.fade(root, false)
        onVisibilityChanged.run()
    }

    /** Keeps the overlay up while paused and restarts its timer once playback resumes. */
    fun onPlaybackChanged() {
        if (shown || !active()) show()
    }

    /** Shows the accumulated jump, such as +30 s, while the viewer holds or repeats a seek button. */
    fun showSeek(deltaMs: Long) {
        val amount = Math.abs(deltaMs)
        val label = (if (deltaMs < 0) "−" else "+") + (if (amount < 60_000) (amount / 1000).toString() + " s" else Ui.time(amount))
        state = state.copy(seekLabel = label, seekBack = deltaMs < 0)
        handler.removeCallbacks(hideSeek)
        handler.postDelayed(hideSeek, SEEK_LABEL_MS)
        show()
    }

    private fun hideSeek() {
        state = state.copy(seekLabel = ""); update()
    }

    private fun update() {
        val duration = player.duration
        val position = if (scrubbing && dragTarget != C.TIME_UNSET) dragTarget else session.position()
        val known = duration != C.TIME_UNSET && duration > 0
        state = state.copy(seekable = known && player.isCurrentMediaItemSeekable,
            progress = if (known) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0,
            buffered = if (known) (player.bufferedPosition * 1000 / duration).toInt().coerceIn(0, 1000) else 0,
            elapsed = Ui.time(position),
            remaining = if (player.isCurrentMediaItemLive) root.context.getString(R.string.live)
                else if (known) "−" + Ui.time(duration - position) else "",
            playing = active())
        publish()
    }

    private fun publish() { (presentation ?: native!!).render(state) }

    /** Default View renderer for applications that use the reusable player's resources. */
    private class NativePresentation(activity: Activity) : Presentation {
        private val root = activity.findViewById<View>(R.id.controls)
        private val seekBubble = activity.findViewById<View>(R.id.seek_bubble)
        private val centerIcon = activity.findViewById<ImageView>(R.id.center_icon)
        private val title = activity.findViewById<TextView>(R.id.controls_title)
        private val details = activity.findViewById<TextView>(R.id.controls_details)
        private val elapsed = activity.findViewById<TextView>(R.id.elapsed)
        private val remaining = activity.findViewById<TextView>(R.id.remaining)
        private val seekLabel = activity.findViewById<TextView>(R.id.seek_label)
        private val seekIcon = activity.findViewById<ImageView>(R.id.seek_icon)
        private val progress = activity.findViewById<SeekBar>(R.id.progress)
        private val playButton = activity.findViewById<TextView>(R.id.control_play)
        private var scrubbing = false
        override fun bind(input: Input, layoutChanged: Runnable) {
            (progress.parent as View).addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (top != oldTop || bottom != oldBottom) layoutChanged.run()
            }
            progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { scrubbing = true; input.startScrub() }
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) input.scrub(value) }
                override fun onStopTrackingTouch(bar: SeekBar) { scrubbing = false; input.stopScrub() }
            })
        }
        override fun bottomClearance(): Int {
            val strip = progress.parent as View
            val gap = Math.round(16 * root.resources.displayMetrics.density)
            return if (strip.height > 0 && root.height > 0) root.height - strip.top + gap
                else root.resources.getDimensionPixelSize(R.dimen.controls_height)
        }
        override fun render(state: State) {
            title.text = state.title
            details.text = state.details; details.visibility = if (state.details.isEmpty()) View.GONE else View.VISIBLE
            progress.isEnabled = state.seekable
            if (!scrubbing) progress.progress = state.progress
            progress.secondaryProgress = state.buffered
            elapsed.text = state.elapsed; remaining.text = state.remaining
            centerIcon.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
            val label = if (state.playing) R.string.control_pause else R.string.control_play
            centerIcon.contentDescription = root.context.getString(label); playButton.setText(label)
            seekIcon.setImageResource(if (state.seekBack) R.drawable.ic_rewind else R.drawable.ic_forward)
            seekLabel.text = state.seekLabel
            Ui.fade(seekBubble, state.seekLabel.isNotEmpty())
            Ui.fade(centerIcon, seekBubble.visibility != View.VISIBLE)
        }
    }

    private fun active(): Boolean {
        return player.playWhenReady && player.playbackState != Player.STATE_ENDED
    }

    companion object {
        private const val HIDE_DELAY_MS: Long = 4000
        private const val SEEK_LABEL_MS: Long = 900
        private const val TICK_MS: Long = 500
    }
}
