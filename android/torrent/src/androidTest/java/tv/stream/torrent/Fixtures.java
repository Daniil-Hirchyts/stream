package tv.stream.torrent;

import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The host's fixtures (simulator): torrents and their expected contents from the fixture service, and
 * the address of the real seeder. Both reach the emulator's loopback through adb reverse.
 */
final class Fixtures {
    static final class File {
        final int index;
        final String path;
        final long size;
        final String sha256;

        File(JSONObject json) throws Exception {
            index = json.getInt("index");
            path = json.getString("path");
            size = json.getLong("size");
            sha256 = json.getString("sha256");
        }
    }

    static final class Torrent {
        final String name, url, v1, v2;
        final int pieceLength;
        final List<File> files = new ArrayList<>();

        Torrent(JSONObject json) throws Exception {
            name = json.getString("name");
            url = json.getString("torrent");
            v1 = json.getString("v1");
            v2 = json.getString("v2");
            pieceLength = json.getInt("pieceLength");
            JSONArray items = json.getJSONArray("files");
            for (int i = 0; i < items.length(); i++) files.add(new File(items.getJSONObject(i)));
        }

        File file(String nameEnd) {
            for (File f : files) if (f.path.endsWith(nameEnd)) return f;
            throw new AssertionError("No file ending " + nameEnd + " in " + name);
        }
    }

    final String base;
    final String seeder;
    private final JSONArray index;

    private Fixtures(String base, String seeder, JSONArray index) {
        this.base = base;
        this.seeder = seeder;
        this.index = index;
    }

    static Fixtures load() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String base = args.getString("fixtures", "http://127.0.0.1:58133");
        String seeder = args.getString("seeder", "127.0.0.1:58134");
        JSONArray index = new JSONArray(new String(get(base + "/index.json"), StandardCharsets.UTF_8));
        return new Fixtures(base, seeder, index);
    }

    Torrent torrent(String name) throws Exception {
        for (int i = 0; i < index.length(); i++) {
            JSONObject t = index.getJSONObject(i);
            if (t.getString("name").equals(name)) return new Torrent(t);
        }
        throw new AssertionError("No fixture " + name + "; run ./simulator.sh services");
    }

    byte[] metainfo(Torrent t) throws IOException {
        return get(t.url);
    }

    static byte[] get(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) >= 0;) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
