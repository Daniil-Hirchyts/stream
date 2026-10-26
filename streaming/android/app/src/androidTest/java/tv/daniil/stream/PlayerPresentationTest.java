package tv.daniil.stream;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import tv.daniil.stream.addon.AddonClient;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.addon.Title;

/** Real loading, metadata, artwork, playback tracks and durable watched changes, using only loopback fixtures. */
public final class PlayerPresentationTest extends AppHarness {
    @Test public void logosReplaceTitlesAndTheFirstActionTogglesWatchedBothWays() throws Exception {
        try (PresentationServer server = new PresentationServer(false, false)) {
            start(server, false);
            waitUntil("logo shown during source loading", 10000, () -> shown(R.id.loading_title_logo));
            WatchActivity activity = watch();
            instrumentation.runOnMainSync(() -> {
                assertTrue(activity.findViewById(R.id.loading).isShown());
                assertFalse(activity.findViewById(R.id.loading_title).isShown());
                assertFalse("Normal loading has no step names", activity.findViewById(R.id.loading_step).isShown());
                assertFalse(activity.findViewById(R.id.loading_message).isShown());
            });
            screenshot("validation-player-logo-loading.png");
            server.streamGate.countDown();
            waitUntil("video renders", 30000, () -> state() != null && state().renderedFirstFrame && state().positionMs > 1000);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
            waitUntil("paused controls", 5000, () -> !state().playWhenReady && shown(R.id.controls_title_logo));
            instrumentation.runOnMainSync(() -> {
                assertFalse(activity.findViewById(R.id.controls_title).isShown());
                assertFalse("Film facts do not crowd the player", activity.findViewById(R.id.watching_info).isShown());
                View watched = activity.findViewById(R.id.control_watched), audio = activity.findViewById(R.id.control_options), settings = activity.findViewById(R.id.control_settings);
                assertTrue("Watched comes first", watched.getLeft() < audio.getLeft());
                assertTrue(audio.getLeft() < settings.getLeft());
                assertEquals("Mark watched", watched.getContentDescription());
            });
            waitUntil("embedded subtitles available", 5000, () -> !state().textTracks.isEmpty());
            control(R.id.control_options, true); tap(state().textTracks.get(0).label, false);
            waitUntil("subtitle selected", 5000, () -> state().subtitles != null && !shown(R.id.menu));
            waitUntil("controls visible after selecting subtitles", 5000, () -> shown(R.id.controls_title_logo));
            instrumentation.runOnMainSync(() -> assertFalse("Track updates cannot show the subtitle summary", activity.findViewById(R.id.controls_details).isShown()));
            control(R.id.control_watched, true);
            waitUntil("marked watched", 5000, () -> services.store.progress(server.title.key(), server.video).watched);
            instrumentation.runOnMainSync(() -> assertEquals("Mark unwatched", activity.findViewById(R.id.control_watched).getContentDescription()));
            control(R.id.control_watched, false);
            waitUntil("marked unwatched", 5000, () -> !services.store.progress(server.title.key(), server.video).watched);
            SystemClock.sleep(1200);
            assertFalse("Later progress preserves the viewer's choice", services.store.progress(server.title.key(), server.video).watched);
            instrumentation.runOnMainSync(() -> assertEquals("Mark watched", activity.findViewById(R.id.control_watched).getContentDescription()));
            assertEquals("Loading and controls share one artwork request", 1, server.logoRequests.get());
            screenshot("validation-player-logo-controls.png");
            control(R.id.control_stop, true); waitUntil("player exits", 5000, () -> watch() == null);
            app.persistence.submit(() -> {}).get();
            assertFalse("Unwatched survives exit", services.store.progress(server.title.key(), server.video).watched);
        }
    }

    @Test public void developerLoadingKeepsStepsAndFailedArtworkKeepsAReadableTitle() throws Exception {
        try (PresentationServer server = new PresentationServer(true, true)) {
            start(server, true);
            waitUntil("developer sees the loading stage", 5000, () -> shown(R.id.loading_step));
            WatchActivity activity = watch();
            instrumentation.runOnMainSync(() -> {
                assertFalse(((TextView) activity.findViewById(R.id.loading_step)).getText().toString().isEmpty());
                assertTrue(activity.findViewById(R.id.loading_title).isShown());
                assertFalse(activity.findViewById(R.id.loading_title_logo).isShown());
            });
            server.streamGate.countDown();
            waitUntil("failed optional image does not block playback", 30000, () -> state() != null && state().renderedFirstFrame);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
            waitUntil("readable fallback title", 5000, () -> shown(R.id.controls_title));
            instrumentation.runOnMainSync(() -> {
                assertEquals("Presentation series", ((TextView) activity.findViewById(R.id.controls_title)).getText().toString());
                assertEquals("S1 E2 · Pilot", ((TextView) activity.findViewById(R.id.watching_info)).getText().toString());
                assertFalse(activity.findViewById(R.id.controls_details).isShown());
            });
        }
    }

    private void start(PresentationServer server, boolean developer) throws Exception {
        services.store.setPreference("developerMode", Boolean.toString(developer));
        try (Cancellation scope = services.addons.newRequest()) { services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope)); }
        services.store.remember(server.title);
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        browse.onActivity(activity -> activity.startActivity(WatchActivity.intent(activity, server.title, server.video, "")));
    }
    private boolean shown(int id) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        WatchActivity activity = watch(); if (activity != null) instrumentation.runOnMainSync(() -> result.set(activity.findViewById(id).isShown()));
        return result.get();
    }
    private void screenshot(String name) throws Exception {
        SystemClock.sleep(400); // Let the activity/window entrance and the new artwork reach the compositor.
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        image.recycle();
    }

    private final class PresentationServer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.ExecutorService clients = Executors.newFixedThreadPool(4);
        final CountDownLatch streamGate = new CountDownLatch(1);
        final AtomicInteger logoRequests = new AtomicInteger();
        final String url = "http://127.0.0.1:" + server.getLocalPort();
        final Title title;
        final String video;
        final byte[] png;
        final String streams;
        final Thread accept;
        PresentationServer(boolean series, boolean brokenLogo) throws Exception {
            Bitmap image = Bitmap.createBitmap(500, 100, Bitmap.Config.ARGB_8888);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.WHITE); paint.setTextSize(68);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC));
            new Canvas(image).drawText("FRAME", 8, 76, paint);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.PNG, 100, bytes); image.recycle(); png = bytes.toByteArray();
            JSONObject meta = new JSONObject().put("id", "fixture:presentation").put("type", series ? "series" : "movie")
                    .put("name", series ? "Presentation series" : "Presentation film").put("logo", url + (brokenLogo ? "/missing.png" : "/logo.png"))
                    .put("releaseInfo", "1999").put("genres", new JSONArray().put("Drama").put("Mystery")).put("imdbRating", "8.7");
            video = series ? "fixture:pilot" : "fixture:presentation";
            if (series) meta.put("videos", new JSONArray().put(new JSONObject().put("id", video).put("title", "Pilot").put("season", 1).put("episode", 2)));
            title = Title.parse("fixture.presentation", meta);
            try (AddonClient client = new AddonClient(); Cancellation scope = new Cancellation()) {
                streams = client.json(origin + "/addon/good/http-mkv/stream/movie/fixture%3Afilm.json", scope, AddonClient.deadline()).toString();
            }
            accept = new Thread(() -> {
                while (!server.isClosed()) try { Socket socket = server.accept(); clients.execute(() -> reply(socket)); }
                catch (Exception e) { if (server.isClosed()) return; }
            }, "presentation-loopback"); accept.start();
        }
        private void reply(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(15000);
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                String request = reader.readLine(), path = request.split(" ")[1];
                while (true) { String line = reader.readLine(); if (line == null || line.isEmpty()) break; }
                byte[] body; String content = "application/json", status = "200 OK";
                if (path.equals("/manifest.json")) body = ("{\"id\":\"fixture.presentation\",\"version\":\"1\",\"name\":\"Presentation fixture\",\"types\":[\"movie\",\"series\"],\"idPrefixes\":[\"fixture:\"],\"resources\":[\"meta\",\"stream\"],\"catalogs\":[]}").getBytes(StandardCharsets.UTF_8);
                else if (path.startsWith("/meta/")) body = ("{\"meta\":" + title.cacheJson() + "}").getBytes(StandardCharsets.UTF_8);
                else if (path.startsWith("/stream/")) { streamGate.await(12, TimeUnit.SECONDS); body = streams.getBytes(StandardCharsets.UTF_8); }
                else if (path.equals("/logo.png")) { logoRequests.incrementAndGet(); body = png; content = "image/png"; }
                else { status = "404 Not Found"; body = new byte[0]; }
                connection.getOutputStream().write(("HTTP/1.1 " + status + "\r\nContent-Type: " + content + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().write(body);
            } catch (Exception ignored) { /* cancelled navigation closes outstanding test requests */ }
        }
        @Override public void close() throws Exception { streamGate.countDown(); server.close(); clients.shutdownNow(); accept.join(2000); }
    }
}
