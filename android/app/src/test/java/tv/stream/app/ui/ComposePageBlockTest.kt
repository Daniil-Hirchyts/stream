package tv.stream.app.ui

import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import tv.stream.app.R
import tv.stream.app.addon.Title

/** The first migrated control keeps the native row's geometry, labels, click/remote focus and view identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ComposePageBlockTest {
    @get:Rule val composition = createEmptyComposeRule()
    @Test fun interopPreservesNativeSettingsRowGeometryAndInteraction() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            var clicks = 0
            fun adapter() = PageAdapter(activity, Artwork(activity.cacheDir), object : PageAdapter.Listener {
                override fun focused(key: String) {}
                override fun featured(title: Title?) {}
            })
            val row = Item.row("cache-size", 0, "Playback cache", "256 MiB", Runnable { clicks++ })
            val build = PageAdapter::class.java.getDeclaredMethod("build", Item::class.java).apply { isAccessible = true }
            val baseline = build.invoke(adapter(), row) as View
            val native = build.invoke(adapter(), row) as View
            val composed = ComposePageBlock(activity, row, native, { build.invoke(adapter(), it) as View }) { view, item ->
                val binding = view.getTag(R.id.page_binding)
                if (binding == null) false else binding.javaClass.getDeclaredMethod("update", Item::class.java)
                    .apply { isAccessible = true }.invoke(binding, item) as Boolean
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(baseline, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(composed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            activity.setContentView(root)
            settle(root)
            val old = baseline.findViewWithTag<View>("cache-size")
            val control = composed.findViewWithTag<View>("cache-size")
            assertEquals(bounds(baseline, old), bounds(composed, control))
            assertEquals(texts(baseline), texts(composed))
            assertEquals(old.contentDescription, control.contentDescription)
            assertFalse("Composition boundary must not take remote focus", composed.isFocusable)

            assertTrue(old.requestFocus())
            assertSame("Baseline native focus reaches the page", old, root.findFocus())
            assertTrue(control.requestFocus())
            assertSame(control, root.findFocus())
            assertSame("D-pad focus leaves the composition for the adjacent native control",
                old, control.focusSearch(View.FOCUS_LEFT))
            control.performClick()
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            assertEquals(2, clicks)
            composed.bind(Item.row("cache-size", 0, "Playback cache", "512 MiB", Runnable { clicks += 10 }))
            settle(root)
            assertSame("Recomposition keeps the focused native control", control, composed.findViewWithTag("cache-size"))
            assertSame(control, root.findFocus())
            assertEquals("Playback cache. 512 MiB", control.contentDescription)
            control.performClick()
            assertEquals("Recomposition binds the new action", 12, clicks)
            assertEquals(bounds(baseline, old), bounds(composed, control))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun structuralCompositionUpdateRestoresTheRememberedActionAfterBinding() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            var chosen = ""
            val adapter = PageAdapter(activity, Artwork(activity.cacheDir), object : PageAdapter.Listener {
                override fun focused(key: String) {}
                override fun featured(title: Title?) {}
            })
            val build = PageAdapter::class.java.getDeclaredMethod("build", Item::class.java).apply { isAccessible = true }
            fun action(key: String) = Item.Action(key, Item.Action.Style.SECONDARY, key, Runnable { chosen = key })
            val initial = Item.actions("title-actions", action("Sources"))
            val host = ComposePageBlock(activity, initial, build.invoke(adapter, initial) as View,
                { build.invoke(adapter, it) as View }) { view, item ->
                val binding = view.getTag(R.id.page_binding)
                if (binding == null) false else binding.javaClass.getDeclaredMethod("update", Item::class.java)
                    .apply { isAccessible = true }.invoke(binding, item) as Boolean
            }
            val root = FrameLayout(activity).apply { addView(host) }
            activity.setContentView(root)
            settle(root)
            val before = host.findViewWithTag<View>("Sources")
            assertTrue(before.requestFocus())
            assertFalse(host.hasPendingBinding)

            host.bind(Item.actions("title-actions", action("Play"), action("Sources")))
            assertTrue("Focus restoration waits while the structural update is queued", host.hasPendingBinding)
            settle(root)
            assertFalse(host.hasPendingBinding)
            val after = host.findViewWithTag<View>("Sources")
            assertNotSame("A changed action structure creates new native controls", before, after)
            assertSame("The remembered action receives focus after the update", after, root.findFocus())
            after.performClick()
            assertEquals("Sources", chosen)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun settle(root: View) {
        composition.waitForIdle()
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        composition.waitForIdle()
    }

    private fun bounds(root: View, view: View): Rect {
        val at = IntArray(2); val origin = IntArray(2)
        view.getLocationInWindow(at); root.getLocationInWindow(origin)
        return Rect(at[0] - origin[0], at[1] - origin[1], at[0] - origin[0] + view.width, at[1] - origin[1] + view.height)
    }

    private fun texts(root: View): List<String> = buildList {
        if (root is TextView) add(root.text.toString())
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(texts(root.getChildAt(i)))
    }
}
