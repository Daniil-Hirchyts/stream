package tv.stream.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import tv.stream.app.addon.AddonClient
import tv.stream.app.addon.AddonException
import tv.stream.app.addon.AddonUrl
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.MediaSource
import tv.stream.app.addon.Title
import tv.stream.torrent.TorrentEngine

/** Durable descriptors, never payload downloads. Metadata/file selection happens only after explicit open. */
class ImportRepository(context: Context, store: TvStore) {
    private val context: Context
    private val store: TvStore
    private val secrets = SecretBox()
    private val directory: File

    init {
        this.context = context.applicationContext; this.store = store
        directory = File(File(context.noBackupFilesDir, "imports"), TvStore.hash(store.databaseName))
        if (!directory.isDirectory && !directory.mkdirs()) throw IllegalStateException("Import storage is unavailable")
        // Recover only our interrupted writes/orphaned descriptors. Never touch native payload/user files.
        for (file in files()) {
            val name = file.name
            if (name.matches("[0-9a-f-]{36}\\.tmp".toRegex())) file.delete()
            if (name.matches("[0-9a-f-]{36}\\.torrent.enc".toRegex()) && store.importDescriptor(name.substring(0, 36)) == null) file.delete()
        }
    }
    private fun files(): Array<File> { val files = directory.listFiles(); return files ?: arrayOf() }

    @Throws(AddonException::class)
    fun addAddress(name: String?, address: String?): String {
        if (address == null) throw AddonException(AddonException.Kind.INVALID_URL, "Enter a magnet or torrent address")
        try {
            val candidate = JSONObject().put("streams", org.json.JSONArray().put(JSONObject().put("url", address)))
            val source = MediaSource.list("local", candidate)[0]
            if (address.startsWith("magnet:") && source.kind != MediaSource.Kind.MAGNET) {
                throw AddonException(AddonException.Kind.INVALID_URL, "Enter a magnet link with a valid torrent hash")
            }
            if (source.kind != MediaSource.Kind.MAGNET) AddonUrl.web(address)
            val id = UUID.randomUUID().toString()
            save(id, name, JSONObject().put("kind", if (source.kind == MediaSource.Kind.MAGNET) "magnet" else "url").put("address", address))
            return id
        } catch (e: JSONException) { throw IllegalStateException(e) }
    }
    /** Copies a Storage Access Framework selection, so reopening never depends on an expired content grant. */
    @Throws(IOException::class)
    fun addFile(name: String?, uri: Uri, scope: Cancellation): String {
        val id = UUID.randomUUID().toString()
        val bytes: ByteArray
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("The selected torrent could not be opened")
            bytes = bounded(input, scope)
        }
        val target = path(id)
        val temporary = File(directory, "$id.tmp")
        try {
            Files.write(temporary.toPath(), secrets.seal("torrent:$id", bytes))
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            save(id, name, JSONObject().put("kind", "file"))
        } catch (e: JSONException) { throw IOException("Could not save import", e) }
        catch (e: IOException) { target.delete(); throw e }
        catch (e: RuntimeException) { target.delete(); throw e }
        finally { temporary.delete() }
        return id
    }
    @Throws(JSONException::class)
    private fun save(id: String, name: String?, descriptor: JSONObject) {
        if (name == null || name.trim { it <= ' ' }.isEmpty() || name.length > 512) throw IllegalArgumentException("Enter an import name of at most 512 characters")
        descriptor.put("name", name).put("id", id)
        store.saveImport(id, descriptor)
        val title = Title.parse("local", JSONObject().put("id", id).put("type", "movie").put("name", name))
        store.remember(title!!)
    }
    fun rename(id: String, name: String?) {
        val descriptor = require(id)
        try { save(id, name, descriptor) }
        catch (e: JSONException) { throw IllegalStateException(e) }
    }
    fun selectedFile(id: String, index: Int) {
        if (index < 0) throw IllegalArgumentException("A file index is zero based")
        val descriptor = require(id)
        try { descriptor.put("fileIdx", index); store.saveImport(id, descriptor) }
        catch (e: JSONException) { throw IllegalStateException(e) }
    }
    fun remove(id: String) {
        val descriptor = require(id)
        store.removeImport(id)
        path(id).delete()
        val title = Title.parse("local", titleJson(id, descriptor.optString("name", "Import")))
        store.removeLibrary(title!!.key())
    }
    @Throws(IOException::class)
    fun open(id: String, client: AddonClient, scope: Cancellation): TorrentEngine.Source {
        val d = require(id)
        scope.check()
        when (d.optString("kind")) {
            "magnet" -> return TorrentEngine.Source.magnet(d.optString("address"))
            "url" -> return TorrentEngine.Source.metainfo(client.fetch(d.optString("address"), MAX_METAINFO, scope, AddonClient.deadline()))
            "file" -> {
                val file = path(id)
                if (file.length() > MAX_METAINFO + 64) throw IOException("Invalid saved torrent metadata")
                return TorrentEngine.Source.metainfo(secrets.open("torrent:$id", Files.readAllBytes(file.toPath())))
            }
            else -> throw IOException("Invalid saved import")
        }
    }
    private fun require(id: String): JSONObject {
        path(id) // Validate before any file operation.
        return store.importDescriptor(id) ?: throw IllegalArgumentException("Import no longer exists")
    }
    private fun path(id: String): File {
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}".toRegex())) throw IllegalArgumentException("Invalid import identity")
        return File(directory, "$id.torrent.enc")
    }

    companion object {
        const val MAX_METAINFO = 32 shl 20

        @Throws(IOException::class)
        private fun bounded(input: InputStream, scope: Cancellation): ByteArray {
            ByteArrayOutputStream().use { out ->
                val buffer = ByteArray(8192)
                var n: Int
                while (input.read(buffer).also { n = it } >= 0) {
                    scope.check()
                    if (out.size() + n > MAX_METAINFO) throw IOException("The torrent metadata is larger than 32 MiB")
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            }
        }
        private fun titleJson(id: String, name: String): JSONObject {
            try { return JSONObject().put("id", id).put("type", "movie").put("name", name) }
            catch (e: JSONException) { throw IllegalStateException(e) }
        }
    }
}
