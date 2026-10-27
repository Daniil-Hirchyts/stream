package tv.stream.playback;

import android.view.View;
import android.view.PointerIcon;
import android.widget.Button;

import java.util.Locale;

/** View helpers shared by player screens and the apps around them. */
public final class Ui {
    private static final int FADE_MS = 180;

    private Ui() {}

    /** Give every action the same pointer, accessibility and keyboard semantics. */
    public static void action(View view, Runnable action) {
        view.setClickable(true);
        view.setFocusable(true);
        view.setPointerIcon(PointerIcon.getSystemIcon(view.getContext(), PointerIcon.TYPE_HAND));
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        view.setOnClickListener(ignored -> action.run());
    }

    /** Cheap to call repeatedly: a view already in the requested state is left alone. */
    public static void fade(View view, boolean visible) {
        // Cancelling also drops a pending fade-out's "gone" step.
        view.animate().cancel();
        if (visible) {
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setVisibility(View.VISIBLE);
            }
            if (view.getAlpha() < 1f) view.animate().alpha(1f).setDuration(FADE_MS).start();
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> view.setVisibility(View.GONE)).start();
        }
    }

    /** 0:42, 12:05 or 1:02:09. */
    public static String time(long ms) {
        long total = Math.max(0, ms / 1000);
        long hours = total / 3600, minutes = total / 60 % 60, seconds = total % 60;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
