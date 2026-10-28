package tv.stream.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tv.stream.app.addon.AddonException;
import tv.stream.app.addon.AddonManifest;
import tv.stream.app.addon.AddonUrl;
import tv.stream.app.addon.Title;

/** Local user data. Call from workers. Migrations preserve library/history; backups are disabled in the manifest. */
public final class TvStore extends SQLiteOpenHelper {
    public static final int VERSION = 2;
    private final SecretBox secrets;

    public TvStore(Context context) { this(context, "stream.db"); }
    public TvStore(Context context, String database) {
        super(context.getApplicationContext(), database, null, VERSION);
        secrets = new SecretBox();
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE addons(id TEXT PRIMARY KEY, data TEXT NOT NULL, enabled INTEGER NOT NULL, ordinal INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE library(id TEXT PRIMARY KEY, data TEXT NOT NULL, favourite INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE progress(title TEXT NOT NULL REFERENCES library(id) ON DELETE CASCADE, video TEXT NOT NULL, position INTEGER NOT NULL, duration INTEGER NOT NULL, watched INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(title, video))");
        db.execSQL("CREATE TABLE imports(id TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE preferences(name TEXT PRIMARY KEY, data TEXT NOT NULL)");
        createV2(db);
    }
    private static void createV2(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE metadata_cache(id TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE catalog_visibility(addon TEXT NOT NULL REFERENCES addons(id) ON DELETE CASCADE, catalog TEXT NOT NULL, visible INTEGER NOT NULL, PRIMARY KEY(addon, catalog))");
        db.execSQL("CREATE INDEX progress_updated ON progress(updated DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) createV2(db);
    }

    public synchronized InstalledAddon install(AddonUrl address, AddonManifest manifest) {
        // Same configured URL updates in place; two configurations of the same manifest remain separate.
        String id = null;
        int ordinal = 0;
        for (InstalledAddon a : addons()) {
            ordinal = Math.max(ordinal, a.order + 1);
            if (a.address.manifest.equals(address.manifest)) id = a.id;
        }
        if (id == null) id = UUID.randomUUID().toString();
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("data", secrets.encrypt("addon:" + id, object("url", address.manifest, "manifest", manifest.json).toString()));
        SQLiteDatabase db = getWritableDatabase();
        if (db.update("addons", v, "id=?", new String[]{id}) == 0) {
            v.put("enabled", 1); v.put("ordinal", ordinal); db.insertOrThrow("addons", null, v);
        }
        // Resource changes make old catalogue/meta cache entries obsolete.
        clearMetadata();
        for (InstalledAddon a : addons()) if (a.id.equals(id)) return a;
        throw new IllegalStateException("Add-on was not saved");
    }

    public synchronized List<InstalledAddon> addons() {
        List<InstalledAddon> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,data,enabled,ordinal FROM addons ORDER BY ordinal,id", null)) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                try {
                    JSONObject data = new JSONObject(secrets.decrypt("addon:" + id, c.getString(1)));
                    out.add(new InstalledAddon(id, new AddonUrl(data.getString("url")),
                            AddonManifest.parse(new JSONObject(data.getString("manifest"))), c.getInt(2) != 0, c.getInt(3)));
                } catch (JSONException | AddonException e) { throw new IllegalStateException("An installed add-on is damaged; remove it and install again"); }
            }
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized void enableAddon(String id, boolean enabled) {
        ContentValues v = new ContentValues(); v.put("enabled", enabled ? 1 : 0);
        getWritableDatabase().update("addons", v, "id=?", new String[]{id});
        clearMetadata();
    }
    public synchronized void removeAddon(String id) {
        getWritableDatabase().delete("addons", "id=?", new String[]{id});
        clearMetadata();
    }
    public synchronized void orderAddons(List<String> ids) {
        List<InstalledAddon> current = addons();
        if (ids.size() != current.size() || new java.util.HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("List every add-on once");
        for (InstalledAddon a : current) if (!ids.contains(a.id)) throw new IllegalArgumentException("Unknown add-on");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues(); v.put("ordinal", i); db.update("addons", v, "id=?", new String[]{ids.get(i)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized void catalogVisible(String addon, String type, String catalog, boolean visible) {
        ContentValues v = new ContentValues(); v.put("addon", addon); v.put("catalog", catalogKey(type, catalog)); v.put("visible", visible ? 1 : 0);
        getWritableDatabase().insertWithOnConflict("catalog_visibility", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized boolean catalogVisible(String addon, String type, String catalog) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT visible FROM catalog_visibility WHERE addon=? AND catalog=?", new String[]{addon, catalogKey(type, catalog)})) {
            return !c.moveToFirst() || c.getInt(0) != 0;
        }
    }

    private static String catalogKey(String type, String id) { return new org.json.JSONArray().put(type).put(id).toString(); }

    public static final class LibraryEntry {
        public final Title title;
        public final boolean favourite;
        /** When the title was last played, favourited or remembered. */
        public final long updatedMs;
        public LibraryEntry(Title title, boolean favourite, long updatedMs) { this.title = title; this.favourite = favourite; this.updatedMs = updatedMs; }
    }
    public synchronized void remember(Title title) {
        ContentValues v = new ContentValues(); v.put("id", title.key());
        v.put("data", secrets.encrypt("title:" + title.key(), title.cacheJson())); v.put("updated", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        if (db.update("library", v, "id=?", new String[]{title.key()}) == 0) db.insertOrThrow("library", null, v);
    }
    /** Replaces a saved title's metadata without moving it in Recently played; a title not in the library stays out. */
    public synchronized void refresh(Title title) {
        ContentValues v = new ContentValues(); v.put("data", secrets.encrypt("title:" + title.key(), title.cacheJson()));
        getWritableDatabase().update("library", v, "id=?", new String[]{title.key()});
    }
    public synchronized void favourite(Title title, boolean favourite) {
        remember(title);
        ContentValues v = new ContentValues(); v.put("favourite", favourite ? 1 : 0);
        getWritableDatabase().update("library", v, "id=?", new String[]{title.key()});
    }
    public synchronized List<LibraryEntry> library(boolean favouritesOnly) {
        List<LibraryEntry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,data,favourite,updated FROM library" + (favouritesOnly ? " WHERE favourite=1" : "") + " ORDER BY updated DESC", null)) {
            while (c.moveToNext()) { LibraryEntry entry = entry(c); if (entry != null) out.add(entry); }
        }
        return out;
    }
    /** One saved title, or null when it isn't in the library. */
    @Nullable public synchronized LibraryEntry entry(String titleKey) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,data,favourite,updated FROM library WHERE id=?", new String[]{titleKey})) {
            return c.moveToFirst() ? entry(c) : null;
        }
    }
    @Nullable private LibraryEntry entry(Cursor c) {
        try {
            org.json.JSONArray key = new org.json.JSONArray(c.getString(0));
            Title title = Title.parse(key.getString(0), new JSONObject(secrets.decrypt("title:" + c.getString(0), c.getString(1))));
            return title == null ? null : new LibraryEntry(title, c.getInt(2) != 0, c.getLong(3));
        } catch (JSONException e) { throw new IllegalStateException("Invalid saved title"); }
    }
    public synchronized void removeLibrary(String titleKey) { getWritableDatabase().delete("library", "id=?", new String[]{titleKey}); }

    public static final class Progress {
        public final String titleKey, video;
        public final long positionMs, durationMs, updatedMs;
        public final boolean watched;
        Progress(Cursor c) { titleKey = c.getString(0); video = c.getString(1); positionMs = c.getLong(2); durationMs = c.getLong(3); watched = c.getInt(4) != 0; updatedMs = c.getLong(5); }
        /** Belongs in Continue Watching: unwatched, at least 30 seconds in and not at the end. */
        public boolean resumable() { return !watched && positionMs >= RESUMABLE_MS && (durationMs == 0 || positionMs < durationMs); }
    }
    public static final long RESUMABLE_MS = 30_000;
    /** Natural completion is supplied explicitly, so a seek near the end alone doesn't mark watched. */
    public synchronized void progress(Title title, String video, long position, long duration, boolean completed) {
        remember(title);
        Progress before = progress(title.key(), video);
        ContentValues v = new ContentValues(); v.put("title", title.key()); v.put("video", video);
        v.put("position", Math.max(0, duration > 0 ? Math.min(position, duration) : position)); v.put("duration", Math.max(0, duration));
        v.put("watched", completed || before != null && before.watched ? 1 : 0); v.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("progress", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized void watched(Title title, String video, boolean watched) {
        Progress p = progress(title.key(), video);
        progress(title, video, p == null ? 0 : p.positionMs, p == null ? 0 : p.durationMs, watched);
        ContentValues v = new ContentValues(); v.put("watched", watched ? 1 : 0);
        getWritableDatabase().update("progress", v, "title=? AND video=?", new String[]{title.key(), video});
    }
    @Nullable public synchronized Progress progress(String titleKey, String video) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT title,video,position,duration,watched,updated FROM progress WHERE title=? AND video=?", new String[]{titleKey, video})) {
            return c.moveToFirst() ? new Progress(c) : null;
        }
    }
    /** Every saved video of one title, most recent first. */
    public synchronized List<Progress> history(String titleKey) {
        List<Progress> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT title,video,position,duration,watched,updated FROM progress WHERE title=? ORDER BY updated DESC", new String[]{titleKey})) {
            while (c.moveToNext()) out.add(new Progress(c));
        }
        return out;
    }
    public synchronized List<Progress> history(boolean continueWatching) {
        List<Progress> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT title,video,position,duration,watched,updated FROM progress ORDER BY updated DESC", null)) {
            while (c.moveToNext()) { Progress p = new Progress(c); if (!continueWatching || p.resumable()) out.add(p); }
        }
        return out;
    }

    /** What happened the last time a source was opened for one video. Keys are {@code SourcePolicy.key}s, never addresses. */
    public static final class SourceMark {
        public static final String PLAYED = "played", FAILED = "failed";
        public final String state;
        public final long updatedMs;
        public SourceMark(String state, long updatedMs) { this.state = state; this.updatedMs = updatedMs; }
    }
    /** Records a source's outcome; a source that played once stays "played" even if a later attempt fails. */
    public synchronized void markSource(String titleKey, String video, String sourceKey, String state) {
        Map<String, SourceMark> marks = sourceMarks(titleKey, video);
        SourceMark before = marks.get(sourceKey);
        String kept = state.equals(SourceMark.FAILED) && before != null && before.state.equals(SourceMark.PLAYED) ? SourceMark.PLAYED : state;
        marks.put(sourceKey, new SourceMark(kept, System.currentTimeMillis()));
        JSONObject out = new JSONObject();
        try { for (Map.Entry<String, SourceMark> e : marks.entrySet()) out.put(e.getKey(), new JSONObject().put("state", e.getValue().state).put("at", e.getValue().updatedMs)); }
        catch (JSONException e) { throw new IllegalStateException(e); }
        save("preferences", "name", sourceMarksKey(titleKey, video), out.toString());
    }
    public synchronized Map<String, SourceMark> sourceMarks(String titleKey, String video) {
        Map<String, SourceMark> marks = new java.util.LinkedHashMap<>();
        String value = read("preferences", "name", sourceMarksKey(titleKey, video));
        if (value == null) return marks;
        try {
            JSONObject data = new JSONObject(value);
            for (java.util.Iterator<String> keys = data.keys(); keys.hasNext(); ) {
                String key = keys.next(); JSONObject mark = data.getJSONObject(key);
                marks.put(key, new SourceMark(mark.getString("state"), mark.optLong("at")));
            }
        } catch (JSONException ignored) { /* an unreadable history is shown as none */ }
        return marks;
    }
    /** Hashed, so the plain preference name never reveals which title was watched. */
    private static String sourceMarksKey(String titleKey, String video) { return "sources:" + hash(titleKey + "\n" + video); }

    public synchronized void setPreference(String key, String value) { save("preferences", "name", key, value); }
    public synchronized String preference(String key, String fallback) { String value = read("preferences", "name", key); return value == null ? fallback : value; }
    public synchronized void saveImport(String id, JSONObject descriptor) { save("imports", "id", id, descriptor.toString()); }
    @Nullable public synchronized JSONObject importDescriptor(String id) {
        String value = read("imports", "id", id);
        try { return value == null ? null : new JSONObject(value); }
        catch (JSONException e) { throw new IllegalStateException("Invalid import descriptor"); }
    }
    public synchronized List<String> importIds() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM imports ORDER BY updated DESC", null)) { while (c.moveToNext()) out.add(c.getString(0)); }
        return out;
    }
    public synchronized void removeImport(String id) { getWritableDatabase().delete("imports", "id=?", new String[]{id}); }

    public synchronized void cache(String requestKey, String metadata) {
        if (metadata.getBytes(StandardCharsets.UTF_8).length > (2 << 20)) return;
        save("metadata_cache", "id", hash(requestKey), metadata);
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM metadata_cache WHERE id NOT IN (SELECT id FROM metadata_cache ORDER BY updated DESC LIMIT 256)");
        for (;;) {
            try (Cursor c = db.rawQuery("SELECT COALESCE(SUM(length(data)),0) FROM metadata_cache", null)) {
                c.moveToFirst();
                if (c.getLong(0) <= (8 << 20)) break;
            }
            db.execSQL("DELETE FROM metadata_cache WHERE id IN (SELECT id FROM metadata_cache ORDER BY updated,id LIMIT 1)");
        }
    }
    @Nullable public synchronized String cached(String requestKey) {
        try { return read("metadata_cache", "id", hash(requestKey)); }
        catch (IllegalArgumentException malformed) { return null; }
        catch (IllegalStateException unreadable) {
            // Cached metadata can be fetched again; an authentication failure here must not block Home.
            // Other private data still reports decryption failures rather than being silently discarded.
            if (unreadable.getCause() instanceof java.security.GeneralSecurityException) return null;
            throw unreadable;
        }
    }
    public synchronized void clearMetadata() { getWritableDatabase().delete("metadata_cache", null, null); }

    private void save(String table, String keyColumn, String key, String text) {
        ContentValues v = new ContentValues(); v.put(keyColumn, key); v.put("data", secrets.encrypt(table + ":" + key, text));
        if (!table.equals("preferences")) v.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    @Nullable private String read(String table, String keyColumn, String key) {
        try (Cursor c = getReadableDatabase().query(table, new String[]{"data"}, keyColumn + "=?", new String[]{key}, null, null, null)) {
            return c.moveToFirst() ? secrets.decrypt(table + ":" + key, c.getString(0)) : null;
        }
    }
    static String hash(String key) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte n : b) out.append(String.format(java.util.Locale.ROOT, "%02x", n & 255)); return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static JSONObject object(String... pairs) {
        JSONObject out = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]); }
        catch (JSONException e) { throw new IllegalStateException(e); }
        return out;
    }
}
