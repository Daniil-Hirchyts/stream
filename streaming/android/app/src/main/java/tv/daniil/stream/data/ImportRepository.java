package tv.daniil.stream.data;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import tv.daniil.stream.addon.AddonClient;
import tv.daniil.stream.addon.AddonException;
import tv.daniil.stream.addon.AddonUrl;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.addon.MediaSource;
import tv.daniil.stream.addon.Title;
import tv.daniil.torrent.TorrentEngine;

/** Durable descriptors, never payload downloads. Metadata/file selection happens only after explicit open. */
public final class ImportRepository {
    public static final int MAX_METAINFO = 32 << 20;
    private final Context context;
    private final TvStore store;
    private final SecretBox secrets = new SecretBox();
    private final File directory;

    public ImportRepository(Context context, TvStore store) {
        this.context = context.getApplicationContext(); this.store = store;
        directory = new File(new File(context.getNoBackupFilesDir(), "imports"), TvStore.hash(store.getDatabaseName()));
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Import storage is unavailable");
        // Recover only our interrupted writes/orphaned descriptors. Never touch native payload/user files.
        for (File file : files()) {
            String name = file.getName();
            if (name.matches("[0-9a-f-]{36}\\.tmp")) file.delete();
            if (name.matches("[0-9a-f-]{36}\\.torrent.enc") && store.importDescriptor(name.substring(0, 36)) == null) file.delete();
        }
    }
    private File[] files() { File[] files = directory.listFiles(); return files == null ? new File[0] : files; }

    public String addAddress(String name, String address) throws AddonException {
        if (address == null) throw new AddonException(AddonException.Kind.INVALID_URL, "Enter a magnet or torrent address");
        try {
            JSONObject candidate = new JSONObject().put("streams", new org.json.JSONArray().put(new JSONObject().put("url", address)));
            MediaSource source = MediaSource.list("local", candidate).get(0);
            if (address.startsWith("magnet:") && source.kind != MediaSource.Kind.MAGNET) {
                throw new AddonException(AddonException.Kind.INVALID_URL, "Enter a magnet link with a valid torrent hash");
            }
            if (source.kind != MediaSource.Kind.MAGNET) AddonUrl.web(address);
            String id = UUID.randomUUID().toString();
            save(id, name, new JSONObject().put("kind", source.kind == MediaSource.Kind.MAGNET ? "magnet" : "url").put("address", address));
            return id;
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    /** Copies a Storage Access Framework selection, so reopening never depends on an expired content grant. */
    public String addFile(String name, Uri uri, Cancellation scope) throws IOException {
        String id = UUID.randomUUID().toString();
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("The selected torrent could not be opened");
            bytes = bounded(in, scope);
        }
        File target = path(id), temporary = new File(directory, id + ".tmp");
        try {
            Files.write(temporary.toPath(), secrets.seal("torrent:" + id, bytes));
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            save(id, name, new JSONObject().put("kind", "file"));
        } catch (JSONException e) { throw new IOException("Could not save import", e); }
        catch (IOException | RuntimeException e) { target.delete(); throw e; }
        finally { temporary.delete(); }
        return id;
    }
    private static byte[] bounded(InputStream in, Cancellation scope) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) >= 0;) {
                scope.check();
                if (out.size() + n > MAX_METAINFO) throw new IOException("The torrent metadata is larger than 32 MiB");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
    private void save(String id, String name, JSONObject descriptor) throws JSONException {
        if (name == null || name.trim().isEmpty() || name.length() > 512) throw new IllegalArgumentException("Enter an import name of at most 512 characters");
        descriptor.put("name", name).put("id", id);
        store.saveImport(id, descriptor);
        Title title = Title.parse("local", new JSONObject().put("id", id).put("type", "movie").put("name", name));
        store.remember(title);
    }
    public void rename(String id, String name) {
        JSONObject descriptor = require(id);
        try { save(id, name, descriptor); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
    public void selectedFile(String id, int index) {
        if (index < 0) throw new IllegalArgumentException("A file index is zero based");
        JSONObject descriptor = require(id);
        try { descriptor.put("fileIdx", index); store.saveImport(id, descriptor); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
    public void remove(String id) {
        JSONObject descriptor = require(id);
        store.removeImport(id);
        path(id).delete();
        Title title = Title.parse("local", titleJson(id, descriptor.optString("name", "Import")));
        store.removeLibrary(title.key());
    }
    public TorrentEngine.Source open(String id, AddonClient client, Cancellation scope) throws IOException {
        JSONObject d = require(id);
        scope.check();
        switch (d.optString("kind")) {
            case "magnet": return TorrentEngine.Source.magnet(d.optString("address"));
            case "url": return TorrentEngine.Source.metainfo(client.fetch(d.optString("address"), MAX_METAINFO, scope, AddonClient.deadline()));
            case "file":
                File file = path(id);
                if (file.length() > MAX_METAINFO + 64) throw new IOException("Invalid saved torrent metadata");
                return TorrentEngine.Source.metainfo(secrets.open("torrent:" + id, Files.readAllBytes(file.toPath())));
            default: throw new IOException("Invalid saved import");
        }
    }
    private JSONObject require(String id) {
        path(id); // Validate before any file operation.
        JSONObject d = store.importDescriptor(id);
        if (d == null) throw new IllegalArgumentException("Import no longer exists");
        return d;
    }
    private File path(String id) {
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("Invalid import identity");
        return new File(directory, id + ".torrent.enc");
    }
    private static JSONObject titleJson(String id, String name) {
        try { return new JSONObject().put("id", id).put("type", "movie").put("name", name); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
