package tv.stream.app.ui

import android.view.View
import android.widget.TextClock

/** The small clock on the browse screens and the player overlay, in the format chosen in Settings. */
object Clock {
    @JvmField val NAMES = arrayOf("Automatic", "24-hour", "12-hour", "Off")
    @JvmField val VALUES = arrayOf("", "24", "12", "off")

    @JvmStatic fun name(value: String?): String {
        for (i in VALUES.indices) if (VALUES[i].equals(value)) return NAMES[i]
        return NAMES[0]
    }

    /** Automatic follows the device's 12/24-hour setting; `shown` false hides it whatever the format. */
    @JvmStatic fun apply(clock: TextClock?, format: String?, shown: Boolean) {
        if (clock == null) return
        val off = !shown || format!!.equals("off")
        clock.visibility = if (off) View.GONE else View.VISIBLE
        if (off) return
        val pattern: String? = if (format.equals("24")) "HH:mm" else if (format.equals("12")) "h:mm a" else null
        clock.format12Hour = if (pattern == null) "h:mm a" else pattern
        clock.format24Hour = if (pattern == null) "HH:mm" else pattern
    }
}
