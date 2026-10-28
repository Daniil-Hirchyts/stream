package tv.stream.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import tv.stream.app.ui.Tv;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class PopupRowsTest {
    @Test public void singleLineSpeedAndMultilineDetailsUseAppropriateShapes() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Tv.Alert popup = Tv.alert(activity, "Settings", (View) null);
        View speed = popup.submenuInline("speed", "Speed", "1×", ignored -> {});
        View style = popup.submenu("style", "Background", "Dark box", ignored -> {});
        assertEquals("Speed. 1×", speed.getContentDescription());
        LinearLayout speedRow = (LinearLayout) speed;
        assertEquals("Speed and its value are siblings on one line", 4, speedRow.getChildCount());
        float capsule = ((GradientDrawable) speed.getBackground().getCurrent()).getCornerRadius();
        float multiline = ((GradientDrawable) style.getBackground().getCurrent()).getCornerRadius();
        assertTrue("Multiline corners are smaller than a capsule", multiline < capsule);
        assertEquals(Tv.dp(activity, 14), multiline, .01f);
    }
}
