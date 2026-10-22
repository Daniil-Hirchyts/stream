package tv.daniil.stream;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Final harness cleanup only. Does not open, delete or change the owner's database or player preferences. */
@RunWith(AndroidJUnit4.class)
public final class EndTestMode {
    @Test public void removeHarnessOverrides() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getSharedPreferences("instrumentation", 0).edit().remove("database").remove("localOnly").remove("cacheBytes").remove("peer").commit());
        assertEquals("daniiltv.db", RuntimeOptions.databaseName(context));
        assertFalse(new RuntimeOptions(context, 256L << 20).localOnly);
    }
}
