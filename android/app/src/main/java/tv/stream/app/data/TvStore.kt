package tv.stream.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.UUID
import tv.stream.app.addon.AddonException
import tv.stream.app.addon.AddonManifest
import tv.stream.app.addon.AddonUrl
import tv.stream.app.addon.Title

/** Local user data. Call from workers. Migrations preserve library/history; backups are disabled in the manifest. */
class TvStore(context: Context, database: String?) : SQLiteOpenHelper(context.applicationContext, database, null, VERSION) {
    private val secrets: SecretBox

    constructor(context: Context) : this(context, "stream.db")
    init {
        secrets = SecretBox()
        setWriteAheadLoggingEnabled(true)
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE addons(id TEXT PRIMARY KEY, data TEXT NOT NULL, enabled INTEGER NOT NULL, ordinal INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE library(id TEXT PRIMARY KEY, data TEXT NOT NULL, favourite INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE progress(title TEXT NOT NULL REFERENCES library(id) ON DELETE CASCADE, video TEXT NOT NULL, position INTEGER NOT NULL, duration INTEGER NOT NULL, watched INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(title, video))")
        db.execSQL("CREATE TABLE imports(id TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE preferences(name TEXT PRIMARY KEY, data TEXT NOT NULL)")
        createV2(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) {
        if (from < 2) createV2(db)
    }

    @Synchronized
    fun install(address: AddonUrl, manifest: AddonManifest): InstalledAddon {
        // Same configured URL updates in place; two configurations of the same manifest remain separate.
        var id: String? = null
        var ordinal = 0
        for (a in addons()) {
            ordinal = Math.max(ordinal, a.order + 1)
            if (a.address.manifest == address.manifest) id = a.id
        }
        if (id == null) id = UUID.randomUUID().toString()
        val v = ContentValues()
        v.put("id", id)
        v.put("data", secrets.encrypt("addon:$id", `object`("url", address.manifest, "manifest", manifest.json).toString()))
        val db = writableDatabase
        if (db.update("addons", v, "id=?", arrayOf(id)) == 0) {
            v.put("enabled", 1); v.put("ordinal", ordinal); db.insertOrThrow("addons", null, v)
        }
        // Resource changes make old catalogue/meta cache entries obsolete.
        clearMetadata()
        for (a in addons()) if (a.id == id) return a
        throw IllegalStateException("Add-on was not saved")
    }

    @Synchronized
    fun addons(): List<InstalledAddon> {
        val out: MutableList<InstalledAddon> = ArrayList()
        readableDatabase.rawQuery("SELECT id,data,enabled,ordinal FROM addons ORDER BY ordinal,id", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                try {
                    val data = JSONObject(secrets.decrypt("addon:$id", c.getString(1)))
                    out.add(InstalledAddon(id, AddonUrl(data.getString("url")),
                            AddonManifest.parse(JSONObject(data.getString("manifest"))), c.getInt(2) != 0, c.getInt(3)))
                } catch (e: JSONException) { throw IllegalStateException("An installed add-on is damaged; remove it and install again") }
                catch (e: AddonException) { throw IllegalStateException("An installed add-on is damaged; remove it and install again") }
            }
        }
        return Collections.unmodifiableList(out)
    }

    @Synchronized
    fun enableAddon(id: String, enabled: Boolean) {
        val v = ContentValues(); v.put("enabled", if (enabled) 1 else 0)
        writableDatabase.update("addons", v, "id=?", arrayOf(id))
        clearMetadata()
    }
    @Synchronized
    fun removeAddon(id: String) {
        writableDatabase.delete("addons", "id=?", arrayOf(id))
        clearMetadata()
    }
    @Synchronized
    fun orderAddons(ids: List<String>) {
        val current = addons()
        if (ids.size != current.size || java.util.HashSet(ids).size != ids.size) throw IllegalArgumentException("List every add-on once")
        for (a in current) if (!ids.contains(a.id)) throw IllegalArgumentException("Unknown add-on")
        val db = writableDatabase; db.beginTransaction()
        try {
            for (i in 0 until ids.size) {
                val v = ContentValues(); v.put("ordinal", i); db.update("addons", v, "id=?", arrayOf(ids[i]))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized
    fun catalogVisible(addon: String, type: String, catalog: String, visible: Boolean) {
        val v = ContentValues(); v.put("addon", addon); v.put("catalog", catalogKey(type, catalog)); v.put("visible", if (visible) 1 else 0)
        writableDatabase.insertWithOnConflict("catalog_visibility", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized
    fun catalogVisible(addon: String, type: String, catalog: String): Boolean {
        readableDatabase.rawQuery("SELECT visible FROM catalog_visibility WHERE addon=? AND catalog=?", arrayOf(addon, catalogKey(type, catalog))).use { c ->
            return !c.moveToFirst() || c.getInt(0) != 0
        }
    }

    data class LibraryEntry(val title: Title, val favourite: Boolean,
                       /** When the title was last played, favourited or remembered. */
                       val updatedMs: Long)
    @Synchronized
    fun remember(title: Title) {
        val v = ContentValues(); v.put("id", title.key())
        v.put("data", secrets.encrypt("title:" + title.key(), title.cacheJson())); v.put("updated", System.currentTimeMillis())
        val db = writableDatabase
        if (db.update("library", v, "id=?", arrayOf(title.key())) == 0) db.insertOrThrow("library", null, v)
    }
    /** Replaces a saved title's metadata without moving it in Recently played; a title not in the library stays out. */
    @Synchronized
    fun refresh(title: Title) {
        val v = ContentValues(); v.put("data", secrets.encrypt("title:" + title.key(), title.cacheJson()))
        writableDatabase.update("library", v, "id=?", arrayOf(title.key()))
    }
    @Synchronized
    fun favourite(title: Title, favourite: Boolean) {
        remember(title)
        val v = ContentValues(); v.put("favourite", if (favourite) 1 else 0)
        writableDatabase.update("library", v, "id=?", arrayOf(title.key()))
    }
    @Synchronized
    fun library(favouritesOnly: Boolean): List<LibraryEntry> {
        val out: MutableList<LibraryEntry> = ArrayList()
        readableDatabase.rawQuery("SELECT id,data,favourite,updated FROM library" + (if (favouritesOnly) " WHERE favourite=1" else "") + " ORDER BY updated DESC", null).use { c ->
            while (c.moveToNext()) { val entry = entry(c); if (entry != null) out.add(entry) }
        }
        return Collections.unmodifiableList(out)
    }
    /** One saved title, or null when it isn't in the library. */
    @Synchronized
    fun entry(titleKey: String): LibraryEntry? {
        readableDatabase.rawQuery("SELECT id,data,favourite,updated FROM library WHERE id=?", arrayOf(titleKey)).use { c ->
            return if (c.moveToFirst()) entry(c) else null
        }
    }
    private fun entry(c: Cursor): LibraryEntry? {
        try {
            val key = org.json.JSONArray(c.getString(0))
            val title = Title.parse(key.getString(0), JSONObject(secrets.decrypt("title:" + c.getString(0), c.getString(1))))
            return if (title == null) null else LibraryEntry(title, c.getInt(2) != 0, c.getLong(3))
        } catch (e: JSONException) { throw IllegalStateException("Invalid saved title") }
    }
    @Synchronized
    fun removeLibrary(titleKey: String) { writableDatabase.delete("library", "id=?", arrayOf(titleKey)) }

    data class Progress(
        val titleKey: String,
        val video: String,
        val positionMs: Long,
        val durationMs: Long,
        val watched: Boolean,
        val updatedMs: Long,
    ) {
        constructor(c: Cursor) : this(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4) != 0, c.getLong(5))

        /** Belongs in Continue Watching: unwatched, at least 30 seconds in and not at the end. */
        fun resumable(): Boolean = !watched && positionMs >= RESUMABLE_MS && (durationMs == 0L || positionMs < durationMs)
    }
    /** Natural completion is supplied explicitly, so a seek near the end alone doesn't mark watched. */
    @Synchronized
    fun progress(title: Title, video: String, position: Long, duration: Long, completed: Boolean) {
        remember(title)
        val before = progress(title.key(), video)
        val v = ContentValues(); v.put("title", title.key()); v.put("video", video)
        v.put("position", Math.max(0L, if (duration > 0) Math.min(position, duration) else position)); v.put("duration", Math.max(0L, duration))
        v.put("watched", if (completed || before != null && before.watched) 1 else 0); v.put("updated", System.currentTimeMillis())
        writableDatabase.insertWithOnConflict("progress", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized
    fun watched(title: Title, video: String, watched: Boolean) {
        val p = progress(title.key(), video)
        progress(title, video, if (p == null) 0L else p.positionMs, if (p == null) 0L else p.durationMs, watched)
        val v = ContentValues(); v.put("watched", if (watched) 1 else 0)
        writableDatabase.update("progress", v, "title=? AND video=?", arrayOf(title.key(), video))
    }
    @Synchronized
    fun progress(titleKey: String, video: String): Progress? {
        readableDatabase.rawQuery("SELECT title,video,position,duration,watched,updated FROM progress WHERE title=? AND video=?", arrayOf(titleKey, video)).use { c ->
            return if (c.moveToFirst()) Progress(c) else null
        }
    }
    /** Every saved video of one title, most recent first. */
    @Synchronized
    fun history(titleKey: String): List<Progress> {
        val out: MutableList<Progress> = ArrayList()
        readableDatabase.rawQuery("SELECT title,video,position,duration,watched,updated FROM progress WHERE title=? ORDER BY updated DESC", arrayOf(titleKey)).use { c ->
            while (c.moveToNext()) out.add(Progress(c))
        }
        return Collections.unmodifiableList(out)
    }
    @Synchronized
    fun history(continueWatching: Boolean): List<Progress> {
        val out: MutableList<Progress> = ArrayList()
        readableDatabase.rawQuery("SELECT title,video,position,duration,watched,updated FROM progress ORDER BY updated DESC", null).use { c ->
            while (c.moveToNext()) { val p = Progress(c); if (!continueWatching || p.resumable()) out.add(p) }
        }
        return Collections.unmodifiableList(out)
    }

    /** What happened the last time a source was opened for one video. Keys are `SourcePolicy.key`s, never addresses. */
    data class SourceMark(val state: String, val updatedMs: Long) {
        companion object {
            const val PLAYED = "played"
            const val FAILED = "failed"
        }
    }
    /** Records a source's outcome; a source that played once stays "played" even if a later attempt fails. */
    @Synchronized
    fun markSource(titleKey: String, video: String, sourceKey: String, state: String) {
        val marks = sourceMarks(titleKey, video).toMutableMap()
        val before = marks[sourceKey]
        val kept = if (state == SourceMark.FAILED && before != null && before.state == SourceMark.PLAYED) SourceMark.PLAYED else state
        marks[sourceKey] = SourceMark(kept, System.currentTimeMillis())
        val out = JSONObject()
        try { for (e in marks.entries) out.put(e.key, JSONObject().put("state", e.value.state).put("at", e.value.updatedMs)) }
        catch (e: JSONException) { throw IllegalStateException(e) }
        save("preferences", "name", sourceMarksKey(titleKey, video), out.toString())
    }
    @Synchronized
    fun sourceMarks(titleKey: String, video: String): Map<String, SourceMark> {
        val marks: MutableMap<String, SourceMark> = java.util.LinkedHashMap()
        val value = read("preferences", "name", sourceMarksKey(titleKey, video)) ?: return emptyMap()
        try {
            val data = JSONObject(value)
            val keys: Iterator<String> = data.keys()
            while (keys.hasNext()) {
                val key = keys.next(); val mark = data.getJSONObject(key)
                marks[key] = SourceMark(mark.getString("state"), mark.optLong("at"))
            }
        } catch (ignored: JSONException) { /* an unreadable history is shown as none */ }
        return Collections.unmodifiableMap(marks)
    }

    @Synchronized
    fun setPreference(key: String, value: String) { save("preferences", "name", key, value) }
    @Synchronized
    fun preference(key: String, fallback: String): String { val value = read("preferences", "name", key); return value ?: fallback }
    @Synchronized
    fun saveImport(id: String, descriptor: JSONObject) { save("imports", "id", id, descriptor.toString()) }
    @Synchronized
    fun importDescriptor(id: String): JSONObject? {
        val value = read("imports", "id", id)
        try { return if (value == null) null else JSONObject(value) }
        catch (e: JSONException) { throw IllegalStateException("Invalid import descriptor") }
    }
    @Synchronized
    fun importIds(): List<String> {
        val out: MutableList<String> = ArrayList()
        readableDatabase.rawQuery("SELECT id FROM imports ORDER BY updated DESC", null).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return Collections.unmodifiableList(out)
    }
    @Synchronized
    fun removeImport(id: String) { writableDatabase.delete("imports", "id=?", arrayOf(id)) }

    /**
     * @param reusable false when the saved copy isn't the whole answer: it remains a fallback for a failed request, but
     * [cachedWithin] never counts it as fresh, and it is the first to go when the cache is over its bounds.
     */
    @Synchronized
    fun cache(requestKey: String, metadata: String, reusable: Boolean = true) {
        if (metadata.toByteArray(StandardCharsets.UTF_8).size > (2 shl 20)) return
        val id = hash(requestKey)
        save("metadata_cache", "id", id, metadata)
        val db = writableDatabase
        if (!reusable) db.execSQL("UPDATE metadata_cache SET updated=0 WHERE id=?", arrayOf(id))
        db.execSQL("DELETE FROM metadata_cache WHERE id NOT IN (SELECT id FROM metadata_cache ORDER BY updated DESC LIMIT 256)")
        while (true) {
            val small = db.rawQuery("SELECT COALESCE(SUM(length(data)),0) FROM metadata_cache", null).use { c ->
                c.moveToFirst()
                c.getLong(0) <= (8 shl 20)
            }
            if (small) break
            db.execSQL("DELETE FROM metadata_cache WHERE id IN (SELECT id FROM metadata_cache ORDER BY updated,id LIMIT 1)")
        }
    }
    @Synchronized
    fun cached(requestKey: String): String? {
        try { return read("metadata_cache", "id", hash(requestKey)) }
        catch (malformed: IllegalArgumentException) { return null }
        catch (unreadable: IllegalStateException) {
            // Cached metadata can be fetched again; an authentication failure here must not block Home.
            // Other private data still reports decryption failures rather than being silently discarded.
            if (unreadable.cause is java.security.GeneralSecurityException) return null
            throw unreadable
        }
    }
    /** Cached metadata no older than [maxAgeMs], by when it was saved; null when missing, older or unreadable. */
    fun cachedWithin(requestKey: String, maxAgeMs: Long): String? {
        if (maxAgeMs <= 0) return null
        val key = hash(requestKey)
        val updated = readableDatabase.query("metadata_cache", arrayOf("updated"), "id=?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else return null
        }
        if (updated <= 0) return null // A descriptive fallback stripped of inline streams is never a fresh answer.
        val age = System.currentTimeMillis() - updated
        if (age < 0 || age > maxAgeMs) return null
        return cached(requestKey)
    }
    @Synchronized
    fun clearMetadata() { writableDatabase.delete("metadata_cache", null, null) }

    private fun save(table: String, keyColumn: String, key: String, text: String) {
        val v = ContentValues(); v.put(keyColumn, key); v.put("data", secrets.encrypt("$table:$key", text))
        if (table != "preferences") v.put("updated", System.currentTimeMillis())
        writableDatabase.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun read(table: String, keyColumn: String, key: String): String? {
        readableDatabase.query(table, arrayOf("data"), "$keyColumn=?", arrayOf(key), null, null, null).use { c ->
            return if (c.moveToFirst()) secrets.decrypt("$table:$key", c.getString(0)) else null
        }
    }

    companion object {
        const val VERSION = 2
        const val RESUMABLE_MS = 30_000L

        private fun createV2(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE metadata_cache(id TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE catalog_visibility(addon TEXT NOT NULL REFERENCES addons(id) ON DELETE CASCADE, catalog TEXT NOT NULL, visible INTEGER NOT NULL, PRIMARY KEY(addon, catalog))")
            db.execSQL("CREATE INDEX progress_updated ON progress(updated DESC)")
        }

        private fun catalogKey(type: String, id: String): String { return org.json.JSONArray().put(type).put(id).toString() }

        /** Hashed, so the plain preference name never reveals which title was watched. */
        private fun sourceMarksKey(titleKey: String, video: String): String { return "sources:" + hash(titleKey + "\n" + video) }

        fun hash(key: String): String {
            try {
                val b = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
                val out = StringBuilder(); for (n in b) out.append(String.format(java.util.Locale.ROOT, "%02x", n.toInt() and 255)); return out.toString()
            } catch (e: java.security.NoSuchAlgorithmException) { throw AssertionError(e) }
        }
        private fun `object`(vararg pairs: String): JSONObject {
            val out = JSONObject()
            try { var i = 0; while (i < pairs.size) { out.put(pairs[i], pairs[i + 1]); i += 2 } }
            catch (e: JSONException) { throw IllegalStateException(e) }
            return out
        }
    }
}
