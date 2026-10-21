package tv.daniil.cast;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test data never leaves emulator loopback; no LAN discovery or paired TV is used. */
final class SubtitleFixtureServer implements AutoCloseable {
    private final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Map<String, byte[]> files = new HashMap<>();

    SubtitleFixtureServer() throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("subtitle-video.mp4")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int length; (length = input.read(buffer)) >= 0;) bytes.write(buffer, 0, length);
            files.put("/video.mp4", bytes.toByteArray());
        }
        put("long.vtt", "WEBVTT\n\n00:00:00.000 --> 00:02:10.000\nLong vtt\n");
        put("alternate.vtt", "WEBVTT\n\n00:00:00.000 --> 00:02:10.000\nAlternate long cue\n");
        put("long.srt", "1\n00:00:00,000 --> 00:02:10,000\nLong srt\n");
        put("long.ass", "[Script Info]\nScriptType: v4.00+\nPlayResX: 160\nPlayResY: 90\n"
                + "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
                + "Style: Default,Arial,12,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,5,5,5,1\n"
                + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:00.00,0:02:10.00,Default,,0,0,0,,Long ass\n");
        workers.execute(() -> {
            while (!server.isClosed()) {
                try { Socket client = server.accept(); workers.execute(() -> serve(client)); }
                catch (Exception exception) { if (!server.isClosed()) throw new RuntimeException(exception); }
            }
        });
    }

    private void put(String path, String text) { files.put("/" + path, text.getBytes(StandardCharsets.UTF_8)); }
    String url(String path) { return "http://127.0.0.1:" + server.getLocalPort() + "/" + path; }

    private void serve(Socket client) {
        try (Socket connection = client) {
            connection.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            String[] request = reader.readLine().split(" ");
            byte[] bytes = files.get(request[1]);
            int start = 0, end = bytes == null ? 0 : bytes.length - 1;
            boolean partial = false;
            for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                if (line.toLowerCase().startsWith("range: bytes=")) {
                    String[] range = line.substring(13).trim().split("-", -1);
                    start = Integer.parseInt(range[0]);
                    if (!range[1].isEmpty()) end = Math.min(end, Integer.parseInt(range[1]));
                    partial = true;
                }
            }
            if (bytes == null || start > end || start < 0) {
                connection.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            String header = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK")
                    + "\r\nContent-Length: " + (end - start + 1) + "\r\nAccept-Ranges: bytes\r\n"
                    + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + bytes.length + "\r\n" : "")
                    + "Connection: close\r\n\r\n";
            connection.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
            if (!request[0].equals("HEAD")) connection.getOutputStream().write(bytes, start, end - start + 1);
        } catch (Exception ignored) { /* Player closed or canceled a fixture request. */ }
    }

    @Override public void close() throws Exception { server.close(); workers.shutdownNow(); }
}
