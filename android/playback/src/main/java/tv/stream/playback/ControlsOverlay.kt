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
        private val onVisibilityChanged: Runnable) {
    private val root: View
    private val seekBubble: View
    private val centerIcon: ImageView
    private val title: TextView
    private val details: TextView
    private val elapsed: TextView
    private val remaining: TextView
    private val seekLabel: TextView
    private val seekIcon: ImageView
    private val progress: SeekBar
    private val playButton: TextView
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
        title = activity.findViewById(R.id.controls_title)
        details = activity.findViewById(R.id.controls_details)
        centerIcon = activity.findViewById(R.id.center_icon)
        playButton = activity.findViewById(R.id.control_play)
        seekBubble = activity.findViewById(R.id.seek_bubble)
        seekIcon = activity.findViewById(R.id.seek_icon)
        seekLabel = activity.findViewById(R.id.seek_label)
        progress = activity.findViewById(R.id.progress)
        elapsed = activity.findViewById(R.id.elapsed)
        remaining = activity.findViewById(R.id.remaining)
        (progress.parent as View).addOnLayoutChangeListener { view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom ->
            if (shown && (top != oldTop || bottom != oldBottom)) onVisibilityChanged.run()
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubbing = true
                dragTarget = C.TIME_UNSET
                handler.removeCallbacks(hide)
            }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                val duration = player.duration
                if (!fromUser || duration <= 0 || duration == C.TIME_UNSET) return
                val target = duration * value / 1000
                // A drag shows where it would land and seeks on release, so a stream skips the positions on the way.
                if (scrubbing) dragTarget = target
                else session.seekTo(target)
                update()
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
                if (dragTarget != C.TIME_UNSET) session.seekTo(dragTarget)
                dragTarget = C.TIME_UNSET
                show()
            }
        })
    }

    fun isShown(): Boolean {
        return shown
    }

    /** Keep captions above the full touch target, including its time labels and buttons. */
    fun bottomClearance(): Int {
        val strip = progress.parent as View
        val fallback = root.resources.getDimensionPixelSize(R.dimen.controls_height)
        val gap = Math.round(16 * root.resources.displayMetrics.density)
        // Apps can supply a shorter toolbar. The shared fallback is only for the first, unmeasured frame.
        return if (strip.height > 0 && root.height > 0)
            root.height - strip.top + gap else fallback
    }

    fun setTitle(text: String?) {
        title.text = text
    }

    /** The voice-over and subtitles under the title, such as "Eng.Original · Subtitles: English". */
    fun setDetails(text: String) {
        details.text = text
        details.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
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
        seekIcon.setImageResource(if (deltaMs < 0) R.drawable.ic_rewind else R.drawable.ic_forward)
        val amount = Math.abs(deltaMs)
        seekLabel.text = (if (deltaMs < 0) "−" else "+") + (if (amount < 60_000) (amount / 1000).toString() + " s" else Ui.time(amount))
        Ui.fade(seekBubble, true)
        handler.removeCallbacks(hideSeek)
        handler.postDelayed(hideSeek, SEEK_LABEL_MS)
        show()
    }

    private fun hideSeek() {
        Ui.fade(seekBubble, false)
        update()
    }

    private fun update() {
        val duration = player.duration
        val position = if (scrubbing && dragTarget != C.TIME_UNSET) dragTarget else session.position()
        val known = duration != C.TIME_UNSET && duration > 0
        progress.isEnabled = known && player.isCurrentMediaItemSeekable
        if (!scrubbing) progress.progress = if (known) (position * 1000 / duration).toInt() else 0
        progress.secondaryProgress = if (known) (player.bufferedPosition * 1000 / duration).toInt() else 0
        elapsed.text = Ui.time(position)
        remaining.text = if (player.isCurrentMediaItemLive) root.context.getString(R.string.live)
                else if (known) "−" + Ui.time(duration - position) else ""
        centerIcon.setImageResource(if (active()) R.drawable.ic_pause else R.drawable.ic_play)
        val label = if (active()) R.string.control_pause else R.string.control_play
        centerIcon.contentDescription = root.context.getString(label)
        playButton.setText(label)
        Ui.fade(centerIcon, seekBubble.visibility != View.VISIBLE)
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
