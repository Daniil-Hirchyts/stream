package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import tv.stream.app.addon.*;
import tv.stream.app.data.TvStore;

/** Actual generated torrent playback, with only loopback discovery and no public providers. */
public final class AutoplayInteractionTest extends AppHarness {
    @Test public void playBalancesAvailabilityHdAndSizeAndOffersNextBeforeTheEnding() throws Exception {
        try (Sources server = new Sources()) {
            try (Cancellation request = services.addons.newRequest()) { services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request)); }
            services.store.remember(server.title); services.store.favourite(server.title, true);
            launch(); waitUntil("Home", 10000, () -> visible(server.title.name));
            tap(server.title.name, false); waitUntil("Play", 10000, () -> visible("Play")); tap("Play", true);
            waitUntil("HD source renders", 40000, () -> state() != null && state().renderedFirstFrame && state().playing);
            waitUntil("chosen source is recorded", 5000, () -> services.store.sourceMarks(server.title.key(), server.first).containsKey(SourcePolicy.key(MediaSource.list(services.store.addons().get(0).id, server.firstStreams).get(2))));
            assertTrue(diagnostics().optBoolean("automatic")); assertEquals("TORRENT", diagnostics().optString("transport"));
            assertEquals("One attempt picks the balanced source", 1, diagnostics().optInt("sourceAttempts"));
            assertFalse("No offer during the story", shown(R.id.next_episode_offer));
            pauseAndSeek(.93f);
            waitUntil("Next episode appears before completion", 5000, () -> shown(R.id.next_episode_offer));
            assertFalse("No early countdown or automatic skip", shown(R.id.up_next));
            assertFalse(state().ended);
            SystemClock.sleep(350);
            WatchActivity offerActivity = watch();
            instrumentation.runOnMainSync(() -> assertEquals("The final offer is fully readable", 1f, offerActivity.findViewById(R.id.next_episode_offer).getAlpha(), .01f));
            screenshot("validation-next-episode.png");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            waitUntil("Remote reaches Next episode", 5000, () -> focused(R.id.next_episode_play));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            waitUntil("offer dismissed", 5000, () -> !shown(R.id.next_episode_offer));
            seek(.98f); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY); waitUntil("episode finishes without autoplay after dismiss", 10000, () -> state().ended);
            SystemClock.sleep(1200); assertFalse("Dismiss also suppresses end countdown", shown(R.id.up_next));
            // Rewind and explicitly use the existing player option to move to episode two.
            seek(.5f); control(R.id.control_settings, true); tap("Next episode", false);
            waitUntil("next episode actually starts", 40000, () -> state() != null && state().renderedFirstFrame && state().playing
                    && services.store.sourceMarks(server.title.key(), server.second).values().stream().anyMatch(mark -> mark.state.equals(TvStore.SourceMark.PLAYED)));
            pauseAndSeek(.93f); SystemClock.sleep(1200);
            assertFalse("Last episode has no nonexistent successor", shown(R.id.next_episode_offer));
        }
    }

    @Test public void manualNextWorksWithAutoplayOffAndUsesTheNextEpisodesSource() throws Exception {
        try (Sources server = new Sources()) {
            services.store.setPreference("autoplay", "false");
            try (Cancellation request = services.addons.newRequest()) { services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request)); }
            services.store.favourite(server.title, true); launch(); waitUntil("series", 10000, () -> visible(server.title.name));
            tap(server.title.name, false); tap("Play", true);
            waitUntil("first episode renders", 40000, () -> state() != null && state().renderedFirstFrame && state().playing);
            pauseAndSeek(.93f); waitUntil("manual next offer even with autoplay disabled", 5000, () -> shown(R.id.next_episode_offer));
            control(R.id.next_episode_play, true);
            waitUntil("second episode plays", 40000, () -> state() != null && state().renderedFirstFrame && state().playing
                    && services.store.sourceMarks(server.title.key(), server.second).values().stream().anyMatch(mark -> mark.state.equals(TvStore.SourceMark.PLAYED)));
        }
    }

    private void pauseAndSeek(float fraction) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
        waitUntil("paused", 5000, () -> state() != null && !state().playWhenReady); seek(fraction);
    }
    private void seek(float fraction) {
        WatchActivity activity = watch(); assertNotNull(activity);
        instrumentation.runOnMainSync(() -> {
            try { java.lang.reflect.Field field = WatchActivity.class.getDeclaredField("session"); field.setAccessible(true);
                ((tv.stream.playback.PlaybackSession) field.get(activity)).seekTo((long) (stateDuration(activity) * fraction));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
    private long stateDuration(WatchActivity activity) { return activity.playbackSnapshot().durationMs; }
    private boolean shown(int id) { AtomicBoolean value = new AtomicBoolean(); WatchActivity activity = watch();
        if (activity != null) instrumentation.runOnMainSync(() -> value.set(activity.findViewById(id).isShown())); return value.get(); }
    private boolean focused(int id) { AtomicBoolean value = new AtomicBoolean(); WatchActivity activity = watch();
        if (activity != null) instrumentation.runOnMainSync(() -> value.set(activity.findViewById(id).isFocused())); return value.get(); }
    private void screenshot(String name) throws Exception {
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } image.recycle();
    }

    private final class Sources implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final String url = "http://127.0.0.1:" + socket.getLocalPort();
        final String first = "fixture:episode/custom?part=1", second = "fixture:episode/custom?part=2";
        final Title title; final JSONObject firstStreams, secondStreams; final Thread worker;
        Sources() throws Exception {
            try (AddonClient client = new AddonClient(); Cancellation scope = new Cancellation()) {
                JSONObject meta = client.json(origin + "/addon/good/ui/meta/series/fixture%3Aseries.json", scope, AddonClient.deadline()).getJSONObject("meta");
                title = Title.parse("fixture.autoplay", meta.put("name", "Autoplay test series"));
                firstStreams = streams(client.json(origin + "/addon/good/ui/stream/series/fixture%3Aepisode%2Fcustom%3Fpart%3D1.json", scope, AddonClient.deadline()));
                secondStreams = streams(client.json(origin + "/addon/good/ui/stream/series/fixture%3Aepisode%2Fcustom%3Fpart%3D2.json", scope, AddonClient.deadline()));
            }
            worker = new Thread(() -> {
                while (!socket.isClosed()) try (Socket client = socket.accept()) {
                    client.setSoTimeout(3000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                    String line = reader.readLine(); if (line == null) continue; String path = line.split(" ")[1];
                    while (true) { String header = reader.readLine(); if (header == null || header.isEmpty()) break; }
                    String body = path.equals("/manifest.json") ? "{\"id\":\"fixture.autoplay\",\"version\":\"1\",\"name\":\"Autoplay fixture\",\"types\":[\"series\"],\"resources\":[\"meta\",\"stream\"],\"catalogs\":[]}"
                            : path.startsWith("/meta/") ? new JSONObject().put("meta", new JSONObject(title.cacheJson())).toString()
                            : (path.toLowerCase(java.util.Locale.ROOT).contains("part%3d2") ? secondStreams : firstStreams).toString();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(bytes);
                } catch (Exception ignored) { if (socket.isClosed()) return; }
            }, "autoplay-loopback"); worker.start();
        }
        JSONObject streams(JSONObject original) throws Exception {
            JSONObject torrent = original.getJSONArray("streams").getJSONObject(0);
            JSONArray list = new JSONArray();
            String[] names = {"Heavy 1080p H264 AAC", "Small 720p H264 AAC", "Balanced 1080p H264 AAC"};
            int[] peers = {1000, 30, 200}; long[] size = {20000L << 20, 800L << 20, 1000L << 20};
            for (int i = 0; i < names.length; i++) list.put(new JSONObject(torrent.toString()).put("name", names[i]).put("seeders", peers[i])
                    .put("behaviorHints", new JSONObject().put("videoSize", size[i])));
            return new JSONObject().put("streams", list);
        }
        @Override public void close() throws Exception { socket.close(); worker.join(2000); }
    }
}
