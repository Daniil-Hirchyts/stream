package tv.daniil.stream;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.addon.Title;

/** Cold, missing and late artwork through the actual details page, using emulator-loopback HTTP only. */
public final class TitleArtworkTest extends AppHarness {
    private static final String NAME = "Logo fixture film";

    @Test public void metadataLogoIsTheFirstVisibleTitleAndCachedReopenDoesNotFlashText() throws Exception {
        try (LogoServer server = new LogoServer(false)) {
            launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            Title title = Title.parse(services.store.addons().get(0).id, server.title(false));
            AtomicBoolean flashed = new AtomicBoolean();
            browse.onActivity(activity -> {
                activity.findViewById(R.id.page_rows).getViewTreeObserver().addOnDrawListener(() -> {
                    TextView text = titleText(activity.findViewById(R.id.page_rows));
                    if (text != null && text.isShown()) flashed.set(true);
                });
                activity.push(Route.of("Details", title, ""));
            });
            assertTrue(server.metaRequested.await(5, TimeUnit.SECONDS));
            SystemClock.sleep(200); assertFalse("No text before metadata", textShown());
            server.metaReady.countDown();
            assertTrue(server.logoRequested.await(5, TimeUnit.SECONDS));
            SystemClock.sleep(200); assertFalse("No text during logo download", textShown());
            server.logoReady.countDown();
            waitUntil("finished logo header", 5000, this::logoShown);
            assertFalse("No frame displayed the text placeholder", flashed.get());
            tap("Back", false);
            browse.onActivity(activity -> activity.push(Route.of("Details", title, "")));
            waitUntil("cached logo", 5000, this::logoShown);
            assertFalse("Reopening also keeps the image title", flashed.get());
        }
    }

    @Test public void invalidLogoAndAbsentLogoUseReadableText() throws Exception {
        try (LogoServer server = new LogoServer(true)) {
            launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
            server.logoReady.countDown();
            Title broken = Title.parse("local", server.title(true));
            browse.onActivity(activity -> activity.push(Route.of("Details", broken, "")));
            waitUntil("failed image falls back to text", 5000, this::textShown);
            assertFalse(logoShown());
            tap("Back", false);
            Title absent = Title.parse("local", server.title(false));
            browse.onActivity(activity -> activity.push(Route.of("Details", absent, "")));
            waitUntil("title without artwork", 5000, this::textShown);
        }
    }

    @Test public void timedOutLogoCannotReplaceTheTextLater() throws Exception {
        try (LogoServer server = new LogoServer(false)) {
            launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
            Title title = Title.parse("local", server.title(true));
            browse.onActivity(activity -> activity.push(Route.of("Details", title, "")));
            assertTrue(server.logoRequested.await(5, TimeUnit.SECONDS));
            waitUntil("bounded text fallback", 5000, this::textShown);
            server.logoReady.countDown();
            assertTrue(server.logoSent.await(5, TimeUnit.SECONDS));
            SystemClock.sleep(500);
            assertTrue("Late artwork cannot flash over the fallback", textShown());
            assertFalse(logoShown());
        }
    }

    private boolean textShown() {
        AtomicBoolean shown = new AtomicBoolean();
        browse.onActivity(activity -> { TextView text = titleText(activity.findViewById(R.id.page_rows)); shown.set(text != null && text.isShown()); });
        return shown.get();
    }
    private boolean logoShown() {
        AtomicBoolean shown = new AtomicBoolean();
        browse.onActivity(activity -> {
            TextView text = titleText(activity.findViewById(R.id.page_rows));
            if (text == null) return;
            ViewGroup header = (ViewGroup) text.getParent();
            for (int i = 0; i < header.getChildCount(); i++) {
                View child = header.getChildAt(i);
                if (child instanceof ImageView && child.isShown() && ((ImageView) child).getDrawable() != null) shown.set(true);
            }
        });
        return shown.get();
    }
    private static TextView titleText(View view) {
        if (view instanceof TextView && NAME.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = titleText(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private static final class LogoServer implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final CountDownLatch metaRequested = new CountDownLatch(1), metaReady = new CountDownLatch(1);
        final CountDownLatch logoRequested = new CountDownLatch(1), logoReady = new CountDownLatch(1), logoSent = new CountDownLatch(1);
        final String url;
        final byte[] image;
        LogoServer(boolean invalid) throws Exception {
            Bitmap bitmap = Bitmap.createBitmap(240, 60, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xFFFFFFFF);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle();
            image = invalid ? new byte[]{1, 2, 3} : bytes.toByteArray();
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")); url = "http://127.0.0.1:" + server.getLocalPort();
            workers.execute(() -> { while (!server.isClosed()) try { Socket socket = server.accept(); workers.execute(() -> answer(socket)); } catch (Exception ignored) { break; } });
        }
        JSONObject title(boolean logo) throws Exception {
            JSONObject result = new JSONObject().put("id", "logo-film").put("type", "movie").put("name", NAME);
            if (logo) result.put("logo", url + "/logo.png"); return result;
        }
        void answer(Socket socket) {
            try (Socket connection = socket; BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String first = reader.readLine(); if (first == null) return;
                String line; while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                String path = first.split(" ")[1]; byte[] body;
                if (path.equals("/logo.png")) {
                    logoRequested.countDown(); if (!logoReady.await(10, TimeUnit.SECONDS)) return; body = image;
                } else if (path.equals("/manifest.json")) {
                    body = ("{\"id\":\"tv.daniil.logo-fixture\",\"name\":\"Logo fixture\",\"version\":\"1.0.0\",\"types\":[\"movie\"],\"resources\":[\"meta\"],\"catalogs\":[]}").getBytes(StandardCharsets.UTF_8);
                } else {
                    metaRequested.countDown(); if (!metaReady.await(10, TimeUnit.SECONDS)) return;
                    body = new JSONObject().put("meta", title(true)).toString().getBytes(StandardCharsets.UTF_8);
                }
                connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().write(body); connection.getOutputStream().flush();
                if (path.equals("/logo.png")) logoSent.countDown();
            } catch (Exception ignored) { /* Test shutdown closes in-flight requests. */ }
        }
        @Override public void close() throws Exception { metaReady.countDown(); logoReady.countDown(); server.close(); workers.shutdownNow(); }
    }
}
