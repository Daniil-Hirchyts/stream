package tv.stream.app.ui;

import android.view.View;
import android.widget.TextClock;

/** The small clock on the browse screens and the player overlay, in the format chosen in Settings. */
public final class Clock {
    public static final String[] NAMES = {"Automatic", "24-hour", "12-hour", "Off"};
    public static final String[] VALUES = {"", "24", "12", "off"};

    private Clock() {}

    public static String name(String value) {
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(value)) return NAMES[i];
        return NAMES[0];
    }

    /** Automatic follows the device's 12/24-hour setting; {@code shown} false hides it whatever the format. */
    public static void apply(TextClock clock, String format, boolean shown) {
        if (clock == null) return;
        boolean off = !shown || format.equals("off");
        clock.setVisibility(off ? View.GONE : View.VISIBLE);
        if (off) return;
        String pattern = format.equals("24") ? "HH:mm" : format.equals("12") ? "h:mm a" : null;
        clock.setFormat12Hour(pattern == null ? "h:mm a" : pattern);
        clock.setFormat24Hour(pattern == null ? "HH:mm" : pattern);
    }
}
