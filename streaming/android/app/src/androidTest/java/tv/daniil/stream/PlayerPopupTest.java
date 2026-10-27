package tv.daniil.stream;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tv.daniil.playback.PlaybackSession;
import tv.daniil.torrent.TorrentEngine;

/** Real app popup actions and cancellation with generated media and loopback peers. */
public final class PlayerPopupTest extends AppHarness {
    @Test public void subtitleFocusOnlyPreviewsAndSelectionCommitsToBothSettingsStores() throws Exception {
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource();
        waitUntil("playing", 30000, () -> state() != null && state().renderedFirstFrame && state().playing);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
        control(R.id.control_settings, true); waitUntil("new settings", 5000, () -> visible("Subtitle style"));
        WatchActivity activity = watch();
        instrumentation.runOnMainSync(() -> assertFalse("The old native menu must never be drawn", activity.findViewById(R.id.menu).isShown()));
        String before = services.playerPreferences.getString("subtitle_font", "standard");
        tap("Subtitle style", false); tap("Font", true);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        SystemClock.sleep(300);
        assertEquals("Focus previews without changing the saved value", before, services.playerPreferences.getString("subtitle_font", "standard"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("returns to style", 5000, () -> visible("Size"));
        assertEquals("Back cancels the preview", before, services.playerPreferences.getString("subtitle_font", "standard"));
        tap("Font", false); tap("Bold", true);
        waitUntil("committed font", 5000, () -> services.playerPreferences.getString("subtitle_font", "").equals("bold"));
        app.persistence.submit(() -> {}).get();
        assertEquals("The Settings page receives the same saved value", "bold", services.store.preference("subtitle_font", ""));
        tap("Font", false); tap("Atkinson Hyperlegible Next", true);
        waitUntil("bundled accessibility font committed", 5000, () -> services.playerPreferences.getString("subtitle_font", "").equals("atkinson"));
        app.persistence.submit(() -> {}).get(); assertEquals("atkinson", services.store.preference("subtitle_font", ""));
        tap("Color", true); tap("Yellow", false);
        waitUntil("another style saved", 5000, () -> services.playerPreferences.getString("subtitle_color", "").equals("yellow"));
        assertEquals("Changing another style preserves the app-only font", "atkinson", services.playerPreferences.getString("subtitle_font", ""));
        instrumentation.runOnMainSync(() -> {
            try {
                android.view.ViewGroup subtitles = activity.findViewById(R.id.subtitles);
                Field font = androidx.media3.ui.DaniilSubtitleOutput.class.getDeclaredField("fontIndex"); font.setAccessible(true);
                assertEquals("The live renderer keeps Atkinson after shared style updates", tv.daniil.stream.ui.SubtitleFonts.ATKINSON, font.getInt(subtitles.getChildAt(0)));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        returned(activity);
        assertFalse("Popup dismissal preserves pause", state().playWhenReady);
    }

    @Test public void sourcePopupsPreserveTheCacheOnCancelAndViewerStateOnSelection() throws Exception {
        playFilm(); WatchActivity activity = watch(); PlaybackSession player = AppFlowTest.session(activity);
        instrumentation.runOnMainSync(player::pause);
        waitUntil("paused", 5000, () -> !state().playWhenReady && !state().buffering);
        Field field = WatchActivity.class.getDeclaredField("coordinator"); field.setAccessible(true);
        PlaybackCoordinator coordinator = (PlaybackCoordinator) field.get(activity);
        TorrentEngine engine = coordinator.engine; long torrent = coordinator.torrent;
        assertNotNull("A real torrent, not an HTTP substitute", engine); assertTrue(torrent != 0);
        long downloaded = app.playbackTelemetry.submit(() -> engine.stats(torrent).getLong("totalDownload")).get();
        assertTrue(downloaded > 0);
        AtomicReference<Integer> generation = new AtomicReference<>(); instrumentation.runOnMainSync(() -> generation.set(player.generation()));
        long position = state().positionMs;
        control(R.id.control_settings, false); tap("Choose source", true);
        waitUntil("source rows", 5000, () -> visible("Choose source") && !visible("Speed"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("back to settings", 5000, () -> visible("Speed"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returned(activity);
        assertRetained(activity, player, coordinator, engine, torrent, generation.get(), position, downloaded);
        // Also exercise the fallback picker used by the error/source route, not just the settings submenu.
        java.lang.reflect.Method choose = WatchActivity.class.getDeclaredMethod("chooseSources"); choose.setAccessible(true);
        instrumentation.runOnMainSync(() -> { try { choose.invoke(activity); } catch (Exception e) { throw new AssertionError(e); } });
        waitUntil("fallback source picker", 5000, () -> visible("Choose source"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returned(activity);
        assertRetained(activity, player, coordinator, engine, torrent, generation.get(), position, downloaded);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY);
        waitUntil("cached film resumes", 10000, () -> state().playing && state().positionMs > position + 500);
        instrumentation.runOnMainSync(() -> { player.pause(); player.seekTo(6000); player.setSpeed(1.25f); });
        waitUntil("paused seek ready", 10000, () -> !state().buffering && state().positionMs >= 6000);
        control(R.id.control_settings, true); tap("Choose source", false); tap("Local magnet", true);
        waitUntil("selected source ready at the same paused position", 30000, () -> state() != null && state().loaded
                && state().renderedFirstFrame && !state().buffering && !state().playWhenReady && Math.abs(state().positionMs - 6000) < 500);
        assertEquals("Source changes also preserve speed", 1.25f, state().speed, .001f);
        instrumentation.runOnMainSync(() -> assertTrue("An actual source change was performed", player.generation() > generation.get()));
    }
    private void assertRetained(WatchActivity activity, PlaybackSession player, PlaybackCoordinator coordinator, TorrentEngine engine,
                                long torrent, int generation, long position, long downloaded) throws Exception {
        assertSame(activity, watch());
        instrumentation.runOnMainSync(() -> {
            assertSame(player, AppFlowTest.session(activity)); assertEquals(generation, player.generation());
            assertSame(engine, coordinator.engine); assertEquals(torrent, coordinator.torrent);
            assertFalse(player.snapshot().playWhenReady); assertEquals(position, player.snapshot().positionMs, 250);
            assertFalse("No reload after cancelling", activity.findViewById(R.id.loading).isShown());
        });
        assertTrue("Download counters retained", app.playbackTelemetry.submit(() -> engine.stats(torrent).getLong("totalDownload")).get() >= downloaded);
    }
    private void returned(WatchActivity activity) {
        waitUntil("player window returns", 5000, () -> {
            AtomicReference<Boolean> focus = new AtomicReference<>(false); instrumentation.runOnMainSync(() -> focus.set(activity.hasWindowFocus())); return focus.get();
        });
    }
}
