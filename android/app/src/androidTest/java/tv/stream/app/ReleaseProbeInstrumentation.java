package tv.stream.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.UUID;
import tv.stream.torrent.*;

/** Runs against the shrunk release APK. No provider configuration, application DB or public discovery is used. */
public final class ReleaseProbeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try { probe(); result.putString("stream", "\nOK (1 test)\n"); finish(Activity.RESULT_OK, result); }
        catch (Throwable failure) {
            result.putString("stream", "\nFAILURES!!!\n" + Log.getStackTraceString(failure));
            result.putString("shortMsg", failure.getClass().getSimpleName()); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("Release probe assertion failed"); }
    private static byte[] fetch(String address) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
        connection.setConnectTimeout(10_000); connection.setReadTimeout(10_000);
        try (java.io.InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) { check(bytes.size() + count <= 2 << 20); bytes.write(buffer, 0, count); }
            return bytes.toByteArray();
        } finally { connection.disconnect(); }
    }
    /** Invoke the fixed JNI boundary in the real shrunk app; Java-only Config/Source wrappers may be merged by R8. */
    private static Object call(String name, Class<?>[] signature, Object... arguments) throws Exception {
        java.lang.reflect.Method method = TorrentEngine.class.getDeclaredMethod(name, signature); method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause(); if (cause instanceof Exception) throw (Exception) cause; throw (Error) cause;
        }
    }
    private void probe() throws Exception {
        Context context = getTargetContext();
        for (String mime : new String[]{"audio/eac3", "audio/true-hd", "video/mp4v-es", "video/mp43", "video/wvc1"})
            check(io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary.supportsFormat(mime));
        String origin = arguments.getString("fixtures", "http://127.0.0.1:58133");
        String peer = arguments.getString("seeder", "127.0.0.1:58134");
        File directory = new File(context.getCacheDir(), "release-probe-" + UUID.randomUUID());
        try {
            byte[] index = fetch(origin + "/index.json");
            JSONArray fixtures = new JSONArray(new String(index, java.nio.charset.StandardCharsets.UTF_8)); JSONObject fixture = null;
            for (int i = 0; i < fixtures.length(); i++) if (fixtures.getJSONObject(i).getString("name").equals("unicode")) fixture = fixtures.getJSONObject(i);
            check(fixture != null);
            JSONObject file = fixture.getJSONArray("files").getJSONObject(0);
            byte[] expected = fetch(origin + "/media/unicode/" + Uri.encode(file.getString("path"), "/"));
            byte[] metainfo = fetch(fixture.getString("torrent"));
            check(((String) call("nativeVersion", new Class<?>[]{})).contains("2.1.2"));
            long engine = (Long) call("nativeCreate", new Class<?>[]{String.class, long.class, long.class, String.class,
                    boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, String.class, int.class},
                    directory.getPath(), 16L << 20, 0L, "127.0.0.1:0", false, false, false, false, false, true, false, "", 45);
            try {
                long torrent = (Long) call("nativeAdd", new Class<?>[]{long.class, byte[].class, String.class, String.class, String[].class, String[].class, String[].class},
                        engine, metainfo, null, null, new String[0], new String[]{peer}, new String[0]);
                String metadata = (String) call("nativeAwaitMetadata", new Class<?>[]{long.class, long.class, long.class}, engine, torrent, 10_000L);
                JSONObject selected = new JSONArray(metadata).getJSONObject(0); int indexInTorrent = selected.getInt("index"); long size = selected.getLong("size");
                try { call("nativeSelectFile", new Class<?>[]{long.class, long.class, int.class}, engine, torrent, 99); throw new AssertionError("Invalid index accepted"); }
                catch (TorrentException e) { check(TorrentException.INVALID_FILE == e.kind); }
                call("nativeSelectFile", new Class<?>[]{long.class, long.class, int.class}, engine, torrent, indexInTorrent);
                long reader = (Long) call("nativeOpenReader", new Class<?>[]{long.class, long.class, int.class}, engine, torrent, indexInTorrent);
                ByteArrayOutputStream actual = new ByteArrayOutputStream(); byte[] buffer = new byte[32768];
                try {
                    long deadline = android.os.SystemClock.elapsedRealtime() + 30_000;
                    for (long offset = 0; offset < size;) {
                        int n = (Integer) call("nativeRead", new Class<?>[]{long.class, long.class, long.class, byte[].class, int.class, int.class, long.class},
                                engine, reader, offset, buffer, 0, (int) Math.min(buffer.length, size - offset), 250L);
                        check(android.os.SystemClock.elapsedRealtime() < deadline); if (n < 0) continue;
                        check(n > 0); actual.write(buffer, 0, n); offset += n;
                    }
                } finally { call("nativeCloseReader", new Class<?>[]{long.class, long.class}, engine, reader); }
                check(java.util.Arrays.equals(expected, actual.toByteArray()));
            } finally { call("nativeDestroy", new Class<?>[]{long.class}, engine); }
            File[] remaining = directory.listFiles(); check(remaining == null || remaining.length == 0);
        } finally { directory.delete(); }
    }
}
