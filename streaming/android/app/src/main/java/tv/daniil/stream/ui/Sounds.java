package tv.daniil.stream.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import tv.daniil.stream.R;

/**
 * The app's interface sounds (made by streaming/sounds/sounds.py, after Rauta's). Played as sonification beside
 * whatever else sounds, never taking audio focus from a film or the viewer's music.
 *
 * <p>Three sizes: quiet touches as the viewer navigates (focus, a row's end, typing), controls (OK, Back, tabs,
 * pages, dialogs, the player's own) and moments (favourite, watched, installed, a failure, a title's end, and the
 * start screen's short piece of music).
 *
 * <p>Never two at once, decided before anything plays:
 * <ul>
 *   <li>one voice: a new sound cuts whatever smaller or equal is still sounding, and is dropped under a larger one;
 *   <li>only the viewer's own keys and touches make touches: focus restored by a page, or moved by the app, is silent;
 *   <li>one press makes one sound: OK's generic select waits a frame, and a more particular sound in that frame (a
 *       page opening, a favourite, a typed letter) is heard instead;
 *   <li>a held direction key ticks at most every 45 ms; controls in quick succession at most every 90 ms, and the
 *       same one at most every 150 ms.
 * </ul>
 * Touches lean up to a semitone lower or higher with their place across the screen: the same tap is never heard
 * quite the same twice, and never becomes a tune.
 */
public final class Sounds {
    public enum Sound {
        // The seconds are how long each is heard (sounds.py prints the files' full lengths, quiet tails included).
        FOCUS(R.raw.sfx_focus, Size.TOUCH, 0.08), EDGE(R.raw.sfx_edge, Size.TOUCH, 0.15),
        KEY(R.raw.sfx_key, Size.TOUCH, 0.07), DELETE(R.raw.sfx_delete, Size.TOUCH, 0.12),
        SELECT(R.raw.sfx_select, Size.CONTROL, 0.15), BACK(R.raw.sfx_back, Size.CONTROL, 0.15),
        TAB(R.raw.sfx_tab, Size.CONTROL, 0.15), OPEN(R.raw.sfx_open, Size.CONTROL, 0.25),
        CHIP(R.raw.sfx_chip, Size.CONTROL, 0.12), RESULTS(R.raw.sfx_results, Size.CONTROL, 0.2),
        ALERT(R.raw.sfx_alert, Size.CONTROL, 0.35), CANCEL(R.raw.sfx_cancel, Size.CONTROL, 0.15),
        PAUSE(R.raw.sfx_pause, Size.CONTROL, 0.14), RESUME(R.raw.sfx_resume, Size.CONTROL, 0.14),
        SEEK_FORWARD(R.raw.sfx_seek_forward, Size.CONTROL, 0.1), SEEK_BACK(R.raw.sfx_seek_back, Size.CONTROL, 0.1),
        MENU_OPEN(R.raw.sfx_menu_open, Size.CONTROL, 0.17), MENU_CLOSE(R.raw.sfx_menu_close, Size.CONTROL, 0.17),
        FAVOURITE_ON(R.raw.sfx_favourite_on, Size.MOMENT, 0.25), FAVOURITE_OFF(R.raw.sfx_favourite_off, Size.MOMENT, 0.2),
        WATCHED_ON(R.raw.sfx_watched_on, Size.MOMENT, 0.35), WATCHED_OFF(R.raw.sfx_watched_off, Size.MOMENT, 0.2),
        INSTALLED(R.raw.sfx_installed, Size.MOMENT, 0.5), WARNING(R.raw.sfx_warning, Size.MOMENT, 0.35),
        UP_NEXT(R.raw.sfx_up_next, Size.MOMENT, 0.5), FINISHED(R.raw.sfx_finished, Size.MOMENT, 0.8),
        // The start screen's music, and the swoosh it leaves with, laid over the opening's fading tail.
        INTRO(R.raw.sfx_intro, Size.MOMENT, 2.6), INTRO_EXIT(R.raw.sfx_intro_exit, Size.MOMENT, 0.6, true);

        final int resource; final Size size; final long ms;
        /** Plays over whatever is sounding rather than cutting it. */
        final boolean layers;
        Sound(int resource, Size size, double seconds) { this(resource, size, seconds, false); }
        Sound(int resource, Size size, double seconds, boolean layers) {
            this.resource = resource; this.size = size; this.ms = Math.round(seconds * 1000); this.layers = layers;
        }
    }
    enum Size { TOUCH, CONTROL, MOMENT }

    /** Below full scale, so the sounds sit under a film's dialogue at the same volume. */
    private static final float LEVEL = 0.7f;
    private static final long TICK_MS = 45, GAP_MS = 90, REPEAT_MS = 150, INPUT_MS = 300;
    private static Sounds instance;

    private final SoundPool pool;
    private final int[] ids = new int[Sound.values().length];
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int screenWidth;
    private volatile boolean enabled = true;
    private boolean systemEnabled = true;
    private long inputAt = -10_000, soundingUntil, touchAt = -10_000, controlAt = -10_000;
    private Size sounding = Size.TOUCH;
    private Sound lastControl;
    private int stream;
    private final boolean[] loaded = new boolean[Sound.values().length];
    /** A sound asked for before its file had loaded (the start screen opens with the app), and when. */
    private Sound pending;
    private long pendingAt;
    private final android.content.SharedPreferences remembered;
    /** Counts every sound played or declined, so a deferred select can tell whether its press already spoke. */
    private int spoken;

    private Sounds(Context context) {
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p, id, status) -> {
            for (Sound s : Sound.values()) if (ids[s.ordinal()] == id) loaded[s.ordinal()] = status == 0;
            // Late by more than a moment, it would no longer match what it belongs to.
            if (pending != null && loaded[pending.ordinal()] && SystemClock.uptimeMillis() - pendingAt < 1500) { Sound s = pending; pending = null; sound(s, 0); }
        });
        for (Sound s : Sound.values()) ids[s.ordinal()] = pool.load(context, s.resource, 1);
        // The Settings switch, mirrored outside the encrypted store so the first sound of a launch already knows it.
        remembered = context.getSharedPreferences("sounds", Context.MODE_PRIVATE);
        enabled = remembered.getBoolean("on", true);
        screenWidth = context.getResources().getDisplayMetrics().widthPixels;
    }
    /** Loads the sounds once per process; call from each activity's onCreate. */
    public static synchronized Sounds get(Context context) {
        if (instance == null) instance = new Sounds(context.getApplicationContext());
        return instance;
    }

    /** The viewer's choice in Settings. */
    public void setEnabled(boolean value) { enabled = value; remembered.edit().putBoolean("on", value).apply(); }
    /** The TV's own "System sounds" switch, read again whenever an activity resumes. */
    public void refresh(Context context) {
        systemEnabled = android.provider.Settings.System.getInt(context.getContentResolver(), android.provider.Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
    }
    /** An arrow key or touch from the viewer: the focus ticks that follow are theirs. */
    public static void input() { Sounds s = instance; if (s != null) s.inputAt = SystemClock.uptimeMillis(); }
    /** Whether {@code keyCode} moves focus. OK and Back don't: the focus a page restores after them stays quiet. */
    public static boolean arrow(int keyCode) {
        return keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN;
    }

    public static void play(Sound sound) { Sounds s = instance; if (s != null) s.sound(sound, 0); }
    /** Focus reached {@code view}: a tick leaning with its place across the screen, if the viewer moved it there. */
    public static void focus(View view) {
        Sounds s = instance;
        if (s != null && SystemClock.uptimeMillis() - s.inputAt <= INPUT_MS) s.sound(Sound.FOCUS, s.across(view));
    }
    /** {@code sound} leaning with {@code view}'s place across the screen, like a focus tick. */
    public static void at(Sound sound, View view) { Sounds s = instance; if (s != null) s.sound(sound, s.across(view)); }
    /** OK on a control. Waits a frame: whatever the control does may have a sound of its own, heard instead. */
    public static void click() {
        Sounds s = instance;
        if (s == null) return;
        int before = s.spoken;
        s.main.post(() -> { if (s.spoken == before) s.sound(Sound.SELECT, 0); });
    }

    /** From a semitone lower at the screen's left edge to a semitone higher at its right. */
    private float across(View view) {
        int[] at = new int[2]; view.getLocationOnScreen(at);
        float centre = (at[0] + view.getWidth() / 2f) / Math.max(1, screenWidth);
        return Math.max(-1f, Math.min(1f, centre * 2 - 1));
    }
    private void sound(Sound sound, float semitones) {
        spoken++;
        if (!enabled || !systemEnabled) return;
        long now = SystemClock.uptimeMillis();
        boolean busy = now < soundingUntil;
        if (busy && sound.size.compareTo(sounding) < 0) return;
        if (sound.size == Size.TOUCH) {
            if (now - touchAt < TICK_MS) return;
            touchAt = now;
        } else if (sound.size == Size.CONTROL) {
            if (now - controlAt < (sound == lastControl ? REPEAT_MS : GAP_MS)) return;
            controlAt = now; lastControl = sound;
        }
        if (!loaded[sound.ordinal()]) { pending = sound; pendingAt = now; return; }
        // One voice: whatever is still sounding gives way, unless this one is laid over it.
        if (busy && stream != 0 && !sound.layers) pool.stop(stream);
        sounding = sound.size; soundingUntil = now + sound.ms;
        float rate = (float) Math.pow(2, semitones / 12.0);
        int played = pool.play(ids[sound.ordinal()], LEVEL, LEVEL, sound.size.ordinal(), 0, rate);
        if (!sound.layers) stream = played;
    }
}
