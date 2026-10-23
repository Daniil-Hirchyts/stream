package tv.daniil.playback;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Player;

/**
 * The playback overlay: title, draggable progress, time and pointer controls. It stays up while paused and fades a few
 * seconds after playback resumes.
 */
final class ControlsOverlay {
    private static final long HIDE_DELAY_MS = 4000;
    private static final long SEEK_LABEL_MS = 900;
    private static final long TICK_MS = 500;

    private final View root, seekBubble;
    private final ImageView centerIcon;
    private final TextView title, details, elapsed, remaining, seekLabel;
    private final ImageView seekIcon;
    private final SeekBar progress;
    private final TextView playButton;
    private boolean scrubbing;
    /** Where a drag on the timeline would seek; the seek waits for the release. */
    private long dragTarget = C.TIME_UNSET;
    private final PlaybackSession session;
    private final Player player;
    private final Handler handler;
    private final Runnable onVisibilityChanged;
    private boolean shown;

    private final Runnable hide = this::hide;
    private final Runnable hideSeek = this::hideSeek;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            if (shown) handler.postDelayed(this, TICK_MS);
        }
    };

    ControlsOverlay(Activity activity, PlaybackSession session, Handler handler, Runnable onVisibilityChanged) {
        this.session = session;
        this.player = session.player();
        this.handler = handler;
        this.onVisibilityChanged = onVisibilityChanged;
        root = activity.findViewById(R.id.controls);
        title = activity.findViewById(R.id.controls_title);
        details = activity.findViewById(R.id.controls_details);
        centerIcon = activity.findViewById(R.id.center_icon);
        playButton = activity.findViewById(R.id.control_play);
        seekBubble = activity.findViewById(R.id.seek_bubble);
        seekIcon = activity.findViewById(R.id.seek_icon);
        seekLabel = activity.findViewById(R.id.seek_label);
        progress = activity.findViewById(R.id.progress);
        elapsed = activity.findViewById(R.id.elapsed);
        remaining = activity.findViewById(R.id.remaining);
        ((View) progress.getParent()).addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (shown && (top != oldTop || bottom != oldBottom)) onVisibilityChanged.run();
        });
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
                dragTarget = C.TIME_UNSET;
                handler.removeCallbacks(hide);
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                long duration = player.getDuration();
                if (!fromUser || duration <= 0 || duration == C.TIME_UNSET) return;
                long target = duration * value / 1000;
                // A drag shows where it would land and seeks on release, so a stream skips the positions on the way.
                if (scrubbing) dragTarget = target;
                else session.seekTo(target);
                update();
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                if (dragTarget != C.TIME_UNSET) session.seekTo(dragTarget);
                dragTarget = C.TIME_UNSET;
                show();
            }
        });
    }

    boolean isShown() {
        return shown;
    }

    /** Keep captions above the full touch target, including its time labels and buttons. */
    int bottomClearance() {
        View strip = (View) progress.getParent();
        int fallback = root.getResources().getDimensionPixelSize(R.dimen.controls_height);
        int gap = Math.round(16 * root.getResources().getDisplayMetrics().density);
        // Apps can supply a shorter toolbar. The shared fallback is only for the first, unmeasured frame.
        return strip.getHeight() > 0 && root.getHeight() > 0
                ? root.getHeight() - strip.getTop() + gap : fallback;
    }

    void setTitle(String text) {
        title.setText(text);
    }

    /** The voice-over and subtitles under the title, such as "Eng.Original · Subtitles: English". */
    void setDetails(String text) {
        details.setText(text);
        details.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void show() {
        if (!shown) {
            shown = true;
            Ui.fade(root, true);
            handler.post(tick);
            onVisibilityChanged.run();
        }
        update();
        handler.removeCallbacks(hide);
        if (active()) handler.postDelayed(hide, HIDE_DELAY_MS);
    }

    void hide() {
        handler.removeCallbacks(hide);
        handler.removeCallbacks(tick);
        if (!shown) return;
        shown = false;
        // A hidden Options button must not keep receiving OK after the inactivity timeout.
        if (root.hasFocus() && root.getParent() instanceof View) {
            View surface = (View) root.getParent();
            if (surface.isFocusable()) surface.requestFocus();
        }
        Ui.fade(root, false);
        onVisibilityChanged.run();
    }

    /** Keeps the overlay up while paused and restarts its timer once playback resumes. */
    void onPlaybackChanged() {
        if (shown || !active()) show();
    }

    /** Shows the accumulated jump, such as +30 s, while the viewer holds or repeats a seek button. */
    void showSeek(long deltaMs) {
        seekIcon.setImageResource(deltaMs < 0 ? R.drawable.ic_rewind : R.drawable.ic_forward);
        long amount = Math.abs(deltaMs);
        seekLabel.setText((deltaMs < 0 ? "−" : "+") + (amount < 60_000 ? amount / 1000 + " s" : Ui.time(amount)));
        Ui.fade(seekBubble, true);
        handler.removeCallbacks(hideSeek);
        handler.postDelayed(hideSeek, SEEK_LABEL_MS);
        show();
    }

    private void hideSeek() {
        Ui.fade(seekBubble, false);
        update();
    }

    private void update() {
        long duration = player.getDuration();
        long position = scrubbing && dragTarget != C.TIME_UNSET ? dragTarget : session.position();
        boolean known = duration != C.TIME_UNSET && duration > 0;
        progress.setEnabled(known && player.isCurrentMediaItemSeekable());
        if (!scrubbing) progress.setProgress(known ? (int) (position * 1000 / duration) : 0);
        progress.setSecondaryProgress(known ? (int) (player.getBufferedPosition() * 1000 / duration) : 0);
        elapsed.setText(Ui.time(position));
        remaining.setText(player.isCurrentMediaItemLive() ? root.getContext().getString(R.string.live)
                : known ? "−" + Ui.time(duration - position) : "");
        centerIcon.setImageResource(active() ? R.drawable.ic_pause : R.drawable.ic_play);
        int label = active() ? R.string.control_pause : R.string.control_play;
        centerIcon.setContentDescription(root.getContext().getString(label));
        playButton.setText(label);
        Ui.fade(centerIcon, seekBubble.getVisibility() != View.VISIBLE);
    }

    private boolean active() {
        return player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
    }
}
