package tv.daniil.stream;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;

import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import tv.daniil.playback.SubtitleStyle;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.SubtitlePreviewView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Ignore("Locks color/font constants, preview dimensions and row order; setters only prove no crash. PlayerInteractionTest verifies actual subtitle selection and persisted style.")
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SubtitleSettingsTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void setup() {
        FakeKeyStore.install();
        context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences("test-subtitle-settings", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @Test public void subtitleStyleContainsAllExpandedColorsAndFonts() {
        SubtitleStyle style = new SubtitleStyle(prefs);

        // 8 distinct colors
        assertEquals(8, SubtitleStyle.Setting.COLOR.values.length);
        assertEquals("white", SubtitleStyle.Setting.COLOR.values[0]);
        assertEquals("yellow", SubtitleStyle.Setting.COLOR.values[1]);
        assertEquals("cyan", SubtitleStyle.Setting.COLOR.values[2]);
        assertEquals("green", SubtitleStyle.Setting.COLOR.values[3]);
        assertEquals("magenta", SubtitleStyle.Setting.COLOR.values[4]);
        assertEquals("blue", SubtitleStyle.Setting.COLOR.values[5]);
        assertEquals("amber", SubtitleStyle.Setting.COLOR.values[6]);
        assertEquals("red", SubtitleStyle.Setting.COLOR.values[7]);

        assertEquals(Color.WHITE, SubtitleStyle.colorFor(0));
        assertEquals(0xFFFFE15A, SubtitleStyle.colorFor(1));
        assertEquals(0xFF8BE9FF, SubtitleStyle.colorFor(2));
        assertEquals(0xFFA8F387, SubtitleStyle.colorFor(3));
        assertEquals(0xFFFF80DF, SubtitleStyle.colorFor(4));
        assertEquals(0xFF82B1FF, SubtitleStyle.colorFor(5));
        assertEquals(0xFFFFB84D, SubtitleStyle.colorFor(6));
        assertEquals(0xFFFF6E6E, SubtitleStyle.colorFor(7));

        // 8 distinct fonts
        assertEquals(8, SubtitleStyle.Setting.FONT.values.length);
        assertEquals("standard", SubtitleStyle.Setting.FONT.values[0]);
        assertEquals("bold", SubtitleStyle.Setting.FONT.values[1]);
        assertEquals("condensed", SubtitleStyle.Setting.FONT.values[2]);
        assertEquals("serif", SubtitleStyle.Setting.FONT.values[3]);
        assertEquals("monospace", SubtitleStyle.Setting.FONT.values[4]);
        assertEquals("casual", SubtitleStyle.Setting.FONT.values[5]);
        assertEquals("cursive", SubtitleStyle.Setting.FONT.values[6]);
        assertEquals("smallcaps", SubtitleStyle.Setting.FONT.values[7]);

        for (int i = 0; i < 8; i++) {
            Typeface tf = SubtitleStyle.typefaceFor(i);
            assertNotNull("Font " + SubtitleStyle.Setting.FONT.values[i] + " must resolve to a Typeface", tf);
        }
    }

    @Test public void subtitlePreviewViewInitializesAndUpdatesDynamically() {
        SubtitlePreviewView preview = new SubtitlePreviewView(context);
        assertNotNull(preview);
        assertEquals(false, preview.isCompact());

        // Measure standard: 92dp
        preview.measure(android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
        assertEquals(tv.daniil.stream.ui.Tv.dp(context, 92), preview.getMeasuredHeight());

        // Switch to compact: 58dp
        preview.setCompact(true);
        assertEquals(true, preview.isCompact());
        preview.measure(android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
        assertEquals(tv.daniil.stream.ui.Tv.dp(context, 58), preview.getMeasuredHeight());

        // Update with custom preferences
        prefs.edit()
                .putString("subtitle_color", "green")
                .putString("subtitle_font", "monospace")
                .putString("subtitle_size", "large")
                .putString("subtitle_edge", "outline")
                .putString("subtitle_position", "raised")
                .commit();

        preview.updateFrom(prefs);

        // Dynamic individual setters update without crashing
        preview.setColor(4); // magenta
        preview.setFont(5);  // casual
        preview.setSize(3);  // xlarge
        preview.setEdge(1);  // shadow
        preview.setPosition(0); // low
    }

    @Test public void settingsPagePresentsSubtitlePreviewAndAllSettings() throws Exception {
        TvApplication app = ApplicationProvider.getApplicationContext();
        TvApplication.Services services = app.services();
        services.playerPreferences.edit()
                .putString("subtitle_color", "cyan")
                .putString("subtitle_font", "serif")
                .commit();

        NavigatorStub nav = new NavigatorStub();
        SetupScreens screens = new SetupScreens(context, nav, services);

        Route route = new Route("Settings");
        route.extras.put("category", "settings:subtitles");
        tv.daniil.stream.ui.Page page = screens.settings(route);

        assertNotNull(page);
        assertEquals(1, page.items.size());
        Item split = page.items.get(0);
        assertEquals(Item.Kind.SPLIT, split.kind);
        assertNotNull(split.panes);

        List<Item> subtitleItems = split.panes.get("settings:subtitles");
        assertNotNull(subtitleItems);

        // First item in subtitles pane must be the preview!
        assertEquals("subtitle-preview", subtitleItems.get(0).key);
        assertEquals(Item.Kind.SUBTITLE_PREVIEW, subtitleItems.get(0).kind);

        // Followed by the setting rows
        assertEquals("subtitle-size", subtitleItems.get(1).key);
        assertEquals("subtitle-font", subtitleItems.get(2).key);
        assertEquals("subtitle-color", subtitleItems.get(3).key);
        assertEquals("subtitle-edge", subtitleItems.get(4).key);
        assertEquals("subtitle-position", subtitleItems.get(5).key);

        assertEquals("Serif", subtitleItems.get(2).value);
        assertEquals("Cyan", subtitleItems.get(3).value);
    }

    private static final class NavigatorStub implements Navigator {
        @Override public void options(String title, java.util.function.Consumer<tv.daniil.stream.ui.Tv.Alert> contents) {}
        @Override public void push(Route next) {}
        @Override public void root(String screen) {}
        @Override public void search() {}
        @Override public void searchInput(String value) {}
        @Override public void openImports() {}
        @Override public void back() {}
        @Override public void refresh() {}
        @Override public void openTitle(tv.daniil.stream.addon.Title title, String video) {}
        @Override public void sources(tv.daniil.stream.addon.Title title, String video) {}
        @Override public void play(tv.daniil.stream.addon.Title title, String video, String sourceKey) {}
        @Override public void edit(String title, String hint, String initial, java.util.function.Consumer<String> accept) {}
        @Override public void choose(String title, String[] options, java.util.function.Consumer<Integer> accept) {}
        @Override public void alert(String title, String message) {}
        @Override public void openWebsite(String address) {}
        @Override public void mutate(java.util.concurrent.Callable<?> task) {
            try { task.call(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public void then(java.util.concurrent.Callable<?> task, Runnable after) {}
        @Override public void installStarters(List<String> addons) {}
        @Override public void addAddon() {}
        @Override public void previewAddon(String address) {}
        @Override public void importAddress() {}
        @Override public void importFile() {}
        @Override public void notices() {}
    }
}
