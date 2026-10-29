package tv.stream.app

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.TextView
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import tv.stream.app.ui.PlayerTimeline

/** Real layout checks: changing status must not move the timestamp off the playhead or collide with the end time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerTimelineLayoutTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var timeline: PlayerTimeline
    private lateinit var readout: View
    private lateinit var elapsed: View
    private lateinit var end: View
    private lateinit var seek: View
    private lateinit var pause: View

    @Before @Throws(Exception::class) fun start() {
        FakeKeyStore.install()
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get(); activity.setContentView(R.layout.activity_watch)
        activity.findViewById<View>(R.id.controls).setVisibility(View.VISIBLE)
        timeline = activity.findViewById(R.id.progress)
        readout = activity.findViewById(R.id.timeline_info)
        elapsed = activity.findViewById(R.id.elapsed); end = activity.findViewById(R.id.remaining)
        seek = activity.findViewById(R.id.seek_bubble); pause = activity.findViewById(R.id.timeline_paused)
        (elapsed as TextView).setText("1:22:03")
        (end as TextView).setText("−1:14:35")
        activity.findViewById<TextView>(R.id.seek_label).setText("+1:20")
        timeline.setPaused(true); timeline.setProgress(500); settle()
    }
    @After fun stop() { controller.pause().stop().destroy() }

    @Test @Throws(Exception::class) fun transientFeedbackKeepsTheTimeAnchoredAndUsesOneStatus() {
        val before = timeCenter()
        assertEquals(playhead(), before, 1f)
        assertTrue(pause.isShown())
        seek.setVisibility(View.VISIBLE); settle()
        assertEquals("Feedback must not recenter the timestamp", before, timeCenter(), 1f)
        assertFalse("Seek feedback replaces the paused badge", pause.isShown())
        seek.setVisibility(View.GONE); settle()
        assertTrue(pause.isShown())
        assertEquals(before, timeCenter(), 1f)
    }

    @Test @Throws(Exception::class) fun longTimesAndFeedbackStayClearAtBothEdgesAndInRtl() {
        for (direction in intArrayOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            activity.findViewById<View>(R.id.player_root).setLayoutDirection(direction)
            seek.setVisibility(View.VISIBLE)
            for (position in intArrayOf(0, 50, 500, 900, 995, 1000)) {
                timeline.setProgress(position); settle()
                assertTrue("Readout stays inside the timeline", readout.getX() >= 0)
                assertTrue(readout.getX() + readout.getWidth() <= timeline.getWidth())
                if (direction == View.LAYOUT_DIRECTION_LTR)
                    assertTrue("Readout clears remaining time", readout.getX() + readout.getWidth() < end.getLeft())
                else assertTrue("RTL readout clears remaining time", readout.getX() > end.getRight())
                if (position == 500) assertEquals("Midpoint anchor, direction=" + direction,
                        playhead(), timeCenter(), 1f)
            }
        }
    }

    private fun timeCenter(): Float { return readout.getX() + elapsed.getLeft() + elapsed.getWidth() / 2f }
    private fun playhead(): Float {
        var fraction = timeline.getProgress() / timeline.getMax().toFloat()
        if (timeline.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) fraction = 1 - fraction
        return timeline.getPaddingLeft() + (timeline.getWidth() - timeline.getPaddingLeft() - timeline.getPaddingRight()) * fraction
    }
    @Throws(InterruptedException::class)
    private fun settle() {
        for (i in 0 until 5) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            // Native drawable updates do not always schedule Robolectric's window traversal.
            activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw()
            Thread.sleep(25)
        }
    }
}
