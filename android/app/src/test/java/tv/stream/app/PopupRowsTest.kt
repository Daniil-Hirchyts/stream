package tv.stream.app

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.stream.app.ui.Tv

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PopupRowsTest {
    @Test fun singleLineSpeedAndMultilineDetailsUseAppropriateShapes() {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val popup: Tv.Alert = Tv.alert(activity, "Settings", null as View?)
        val speed: View = popup.submenuInline("speed", "Speed", "1×") { ignored -> }
        val style: View = popup.submenu("style", "Background", "Dark box") { ignored -> }
        assertEquals("Speed. 1×", speed.contentDescription)
        val speedRow = speed as LinearLayout
        assertEquals("Speed and its value are siblings on one line", 4, speedRow.childCount)
        val capsule = (speed.background.current as GradientDrawable).cornerRadius
        val multiline = (style.background.current as GradientDrawable).cornerRadius
        assertTrue("Multiline corners are smaller than a capsule", multiline < capsule)
        assertEquals(Tv.dp(activity, 14f).toFloat(), multiline, .01f)
    }
}
