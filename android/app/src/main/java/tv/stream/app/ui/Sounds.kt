package tv.stream.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import tv.stream.app.R

/**
 * The app's Soft interface sounds (made by sounds/sounds.py and palettes.py). Played as sonification beside
 * whatever else sounds, never taking audio focus from a film or the viewer's music.
 *
 * Three sizes: quiet touches as the viewer navigates (focus, a row's end, typing), controls (OK, Back, tabs,
 * pages, dialogs, the player's own) and moments (favourite, watched, installed, a failure, a title's end, and the
 * start screen's short piece of music).
 *
 * Never two at once, decided before anything plays:
 * - one voice: a new sound cuts whatever smaller or equal is still sounding, and is dropped under a larger one;
 * - only the viewer's own keys and touches make touches: focus restored by a page, or moved by the app, is silent;
 * - one press makes one sound: OK's generic select waits a frame, and a more particular sound in that frame (a
 *   page opening, a favourite, a typed letter) is heard instead;
 * - a held direction key ticks at most every 45 ms; controls in quick succession at most every 90 ms, and the
 *   same one at most every 150 ms.
 *
 * Touches lean up to a semitone lower or higher with their place across the screen: the same tap is never heard
 * quite the same twice, and never becomes a tune.
 */
class Sounds private constructor(context: Context) {
    enum class Sound(val resource: Int, val size: Size, seconds: Double,
                     /** Plays over whatever is sounding rather than cutting it. */
                     val layers: Boolean) {
        // Full Soft clip durations; the one-voice policy must not outlive a finished cue.
        FOCUS(R.raw.sfx_focus, Size.TOUCH, 0.058), EDGE(R.raw.sfx_edge, Size.TOUCH, 0.094),
        KEY(R.raw.sfx_key, Size.TOUCH, 0.045), DELETE(R.raw.sfx_delete, Size.TOUCH, 0.075),
        SELECT(R.raw.sfx_select, Size.CONTROL, 0.130), BACK(R.raw.sfx_back, Size.CONTROL, 0.112),
        TAB(R.raw.sfx_tab, Size.CONTROL, 0.082), OPEN(R.raw.sfx_open, Size.CONTROL, 0.198),
        CHIP(R.raw.sfx_chip, Size.CONTROL, 0.078), RESULTS(R.raw.sfx_results, Size.CONTROL, 0.192),
        ALERT(R.raw.sfx_alert, Size.CONTROL, 0.193), CANCEL(R.raw.sfx_cancel, Size.CONTROL, 0.100),
        PAUSE(R.raw.sfx_pause, Size.CONTROL, 0.118), RESUME(R.raw.sfx_resume, Size.CONTROL, 0.118),
        SEEK_FORWARD(R.raw.sfx_seek_forward, Size.CONTROL, 0.093), SEEK_BACK(R.raw.sfx_seek_back, Size.CONTROL, 0.093),
        MENU_OPEN(R.raw.sfx_menu_open, Size.CONTROL, 0.143), MENU_CLOSE(R.raw.sfx_menu_close, Size.CONTROL, 0.116),
        FAVOURITE_ON(R.raw.sfx_favourite_on, Size.MOMENT, 0.272), FAVOURITE_OFF(R.raw.sfx_favourite_off, Size.MOMENT, 0.150),
        WATCHED_ON(R.raw.sfx_watched_on, Size.MOMENT, 0.298), WATCHED_OFF(R.raw.sfx_watched_off, Size.MOMENT, 0.182),
        INSTALLED(R.raw.sfx_installed, Size.MOMENT, 0.380), WARNING(R.raw.sfx_warning, Size.MOMENT, 0.263),
        UP_NEXT(R.raw.sfx_up_next, Size.MOMENT, 0.395), FINISHED(R.raw.sfx_finished, Size.MOMENT, 0.545),
        // The start screen's music, and the swoosh it leaves with, laid over the opening's fading tail.
        INTRO(R.raw.sfx_intro, Size.MOMENT, 2.050), INTRO_EXIT(R.raw.sfx_intro_exit, Size.MOMENT, 0.320, true);

        val ms: Long = Math.round(seconds * 1000)

        constructor(resource: Int, size: Size, seconds: Double) : this(resource, size, seconds, false)
    }
    enum class Size { TOUCH, CONTROL, MOMENT }

    private val pool: SoundPool
    private val ids = IntArray(Sound.values().size)
    private val main = Handler(Looper.getMainLooper())
    private val screenWidth: Int
    @Volatile private var enabled = true
    private var systemEnabled = true
    private var inputAt = -10_000L
    private var soundingUntil = 0L
    private var touchAt = -10_000L
    private var controlAt = -10_000L
    private var sounding = Size.TOUCH
    private var lastControl: Sound? = null
    private var stream = 0
    private val loaded = BooleanArray(Sound.values().size)
    /** A sound asked for before its file had loaded (the start screen opens with the app), and when. */
    private var pending: Sound? = null
    private var pendingAt = 0L
    private val remembered: android.content.SharedPreferences
    /** Counts every sound played or declined, so a deferred select can tell whether its press already spoke. */
    private var spoken = 0

    init {
        pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool.setOnLoadCompleteListener { _, id, status ->
            for (s in Sound.values()) if (ids[s.ordinal] == id) loaded[s.ordinal] = status == 0
            // Late by more than a moment, it would no longer match what it belongs to.
            if (pending != null && loaded[pending!!.ordinal] && SystemClock.uptimeMillis() - pendingAt < 1500) { val s = pending!!; pending = null; sound(s, 0f) }
        }
        for (s in Sound.values()) ids[s.ordinal] = pool.load(context, s.resource, 1)
        // The Settings switch, mirrored outside the encrypted store so the first sound of a launch already knows it.
        remembered = context.getSharedPreferences("sounds", Context.MODE_PRIVATE)
        enabled = remembered.getBoolean("on", true)
        screenWidth = context.resources.displayMetrics.widthPixels
    }

    /** The viewer's choice in Settings. */
    fun setEnabled(value: Boolean) { enabled = value; remembered.edit().putBoolean("on", value).apply() }
    /** The TV's own "System sounds" switch, read again whenever an activity resumes. */
    fun refresh(context: Context) {
        systemEnabled = android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
    }

    /** From a semitone lower at the screen's left edge to a semitone higher at its right. */
    private fun across(view: View): Float {
        val at = IntArray(2); view.getLocationOnScreen(at)
        return across(at[0] + view.width / 2f)
    }
    private fun across(screenX: Float): Float {
        val centre = screenX / Math.max(1, screenWidth)
        return Math.max(-1f, Math.min(1f, centre * 2 - 1))
    }
    private fun sound(sound: Sound, semitones: Float) {
        spoken++
        if (!enabled || !systemEnabled) return
        val now = SystemClock.uptimeMillis()
        val busy = now < soundingUntil
        if (busy && sound.size.compareTo(sounding) < 0) return
        if (sound.size == Size.TOUCH) {
            if (now - touchAt < TICK_MS) return
            touchAt = now
        } else if (sound.size == Size.CONTROL) {
            if (now - controlAt < (if (sound == lastControl) REPEAT_MS else GAP_MS)) return
            controlAt = now; lastControl = sound
        }
        if (!loaded[sound.ordinal]) { pending = sound; pendingAt = now; return }
        // One voice: whatever is still sounding gives way, unless this one is laid over it.
        if (busy && stream != 0 && !sound.layers) pool.stop(stream)
        sounding = sound.size; soundingUntil = now + sound.ms
        val rate = Math.pow(2.0, semitones / 12.0).toFloat()
        val played = pool.play(ids[sound.ordinal], LEVEL, LEVEL, sound.size.ordinal, 0, rate)
        if (!sound.layers) stream = played
    }

    companion object {
        /** Below full scale, so the sounds sit under a film's dialogue at the same volume. */
        private const val LEVEL = 0.7f
        private const val TICK_MS = 45L
        private const val GAP_MS = 90L
        private const val REPEAT_MS = 150L
        private const val INPUT_MS = 300L
        private var instance: Sounds? = null

        /** Loads the sounds once per process; call from each activity's onCreate. */
        @Synchronized fun get(context: Context): Sounds {
            if (instance == null) instance = Sounds(context.applicationContext)
            return instance!!
        }

        /** An arrow key or touch from the viewer: the focus ticks that follow are theirs. */
        fun input() { val s = instance; if (s != null) s.inputAt = SystemClock.uptimeMillis() }
        /** Whether {@code keyCode} moves focus. OK and Back don't: the focus a page restores after them stays quiet. */
        fun arrow(keyCode: Int): Boolean {
            return keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
        }

        fun play(sound: Sound) { val s = instance; if (s != null) s.sound(sound, 0f) }
        /** Focus reached {@code view}: a tick leaning with its place across the screen, if the viewer moved it there. */
        fun focus(view: View) {
            val s = instance
            if (s != null && SystemClock.uptimeMillis() - s.inputAt <= INPUT_MS) s.sound(Sound.FOCUS, s.across(view))
        }
        /** A declarative control's focus tick, at the control's own centre (`screenX`) rather than its host view's. */
        fun focusAt(screenX: Float) {
            val s = instance
            if (s != null && SystemClock.uptimeMillis() - s.inputAt <= INPUT_MS) s.sound(Sound.FOCUS, s.across(screenX))
        }
        /** {@code sound} leaning with {@code view}'s place across the screen, like a focus tick. */
        fun at(sound: Sound, view: View) { val s = instance; if (s != null) s.sound(sound, s.across(view)) }
        fun atX(sound: Sound, screenX: Float) { val s = instance; if (s != null) s.sound(sound, s.across(screenX)) }
        /** OK on a control. Waits a frame: whatever the control does may have a sound of its own, heard instead. */
        fun click() {
            val s = instance ?: return
            val before = s.spoken
            s.main.post { if (s.spoken == before) s.sound(Sound.SELECT, 0f) }
        }
    }
}
