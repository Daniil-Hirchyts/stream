package tv.stream.app.ui

import android.view.View
import android.widget.TextClock

/** The small clock on the browse screens and the player overlay, in the format chosen in Settings. */
object Clock {
    val NAMES = arrayOf("Automatic", "24-hour", "12-hour", "Off")
    val VALUES = arrayOf("", "24", "12", "off")

    fun name(value: String?): String {
        for (i in VALUES.indices) if (VALUES[i].equals(value)) return NAMES[i]
        return NAMES[0]
    }

    /** Automatic follows the device's 12/24-hour setting; `shown` false hides it whatever the format. */
    fun apply(clock: TextClock?, format: String?, shown: Boolean) {
        if (clock == null) return
        val off = !shown || format!!.equals("off")
        clock.visibility = if (off) View.GONE else View.VISIBLE
        if (off) return
        val pattern: String? = if (format.equals("24")) "HH:mm" else if (format.equals("12")) "h:mm a" else null
        val twelve = pattern ?: "h:mm a"
        val twentyFour = pattern ?: "HH:mm"
        // TextClock resets its ticker and text layout when a format is assigned, even if unchanged.
        // Global layout observers also update visibility; avoid scheduling another layout from them.
        if (clock.format12Hour?.toString() != twelve) clock.format12Hour = twelve
        if (clock.format24Hour?.toString() != twentyFour) clock.format24Hour = twentyFour
    }
}
