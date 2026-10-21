package tv.daniil.cast;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Player;

/**
 * The playback overlay: title, progress, time and remote hints. It stays up while paused and fades a few
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

    ControlsOverlay(Activity activity, Player player, Handler handler, Runnable onVisibilityChanged) {
        this.player = player;
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
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
                handler.removeCallbacks(hide);
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                long duration = player.getDuration();
                if (fromUser && duration > 0 && duration != C.TIME_UNSET) {
                    player.seekTo(duration * value / 1000);
                    elapsed.setText(Ui.time(duration * value / 1000));
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                show();
            }
        });
    }

    boolean isShown() {
        return shown;
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
        long duration = player.getDuration(), position = player.getCurrentPosition();
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
