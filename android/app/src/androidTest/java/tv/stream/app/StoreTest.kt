package tv.stream.app

import org.junit.Assert.*
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Arrays
import java.util.Collections
import java.util.UUID
import tv.stream.app.addon.*
import tv.stream.app.data.*
import tv.stream.torrent.*

@RunWith(AndroidJUnit4::class)
class StoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: String
    private lateinit var store: TvStore
    @Before fun open() { database = "store-test-" + UUID.randomUUID() + ".db"; store = TvStore(context, database) }
    @After fun close() { store.close(); context.deleteDatabase(database) }
    @Throws(Exception::class)
    private fun title(id: String): Title { return Title.parse("provider", JSONObject().put("id", id).put("type", "series").put("name", "Generated Series"))!! }
    @Throws(Exception::class)
    private fun manifest(): AddonManifest { return AddonManifest.parse(JSONObject("{\"id\":\"configured\",\"name\":\"Configured\",\"version\":\"1\",\"types\":[\"movie\"],\"resources\":[\"catalog\"]}")) }

    @Test @Throws(Exception::class) fun credentialsAreEncryptedAuthenticatedAndBackupIsDisabled() {
        val secret = "fixture-secret-" + UUID.randomUUID()
        val installed = store.install(AddonUrl("https://example.com/$secret/manifest.json?token=$secret"), manifest())
        store.readableDatabase.rawQuery("SELECT data FROM addons", null).use { c ->
            assertTrue(c.moveToFirst()); assertFalse(c.getString(0).contains(secret))
        }
        store.close(); store = TvStore(context, database)
        assertTrue(store.addons()[0].address.manifest.contains(secret))
        assertFalse(installed.toString().contains(secret))
        val box = SecretBox(); val encrypted = box.encrypt("one", secret)
        try { box.decrypt("two", encrypted); fail("Data was moved between encryption scopes") } catch (expected: IllegalStateException) {}
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        // SQLite/WAL contain only ciphertext for configured URLs, not the token.
        for (suffix in arrayOf("", "-wal")) {
            val file = File(context.getDatabasePath(database).path + suffix)
            if (file.exists()) assertFalse(String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1).contains(secret))
        }
    }

    @Test @Throws(Exception::class) fun addOnConfigurationOrderVisibilityAndRemovalSurviveRestart() {
        assertTrue(store.addons().isEmpty())
        val a = store.install(AddonUrl("https://example.com/one/manifest.json"), manifest())
        val b = store.install(AddonUrl("https://example.com/two/manifest.json"), manifest())
        assertNotEquals(a.id, b.id); store.enableAddon(a.id, false)
        assertEquals(a.id, store.install(a.address, manifest()).id); assertFalse(store.addons()[0].enabled)
        store.orderAddons(Arrays.asList(b.id, a.id)); store.catalogVisible(b.id, "movie", "all", false)
        store.close(); store = TvStore(context, database)
        assertEquals(b.id, store.addons()[0].id); assertFalse(store.catalogVisible(b.id, "movie", "all"))
        store.removeAddon(b.id); assertEquals(1, store.addons().size.toLong())
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM catalog_visibility", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0).toLong()) }
        try { store.orderAddons(Collections.singletonList("wrong")); fail("Unknown order accepted") } catch (expected: IllegalArgumentException) {}
    }

    @Test @Throws(Exception::class) fun historyResumeWatchedAndLanguagePreferencesSurviveReopen() {
        val t = title("custom:series")
        store.favourite(t, true); store.progress(t, "episode/custom", 29_999, 60_000, false)
        assertTrue(store.history(true).isEmpty())
        store.progress(t, "episode/custom", 30_000, 60_000, false); assertEquals(1, store.history(true).size.toLong())
        store.progress(t, "episode/other", 31_000, 90_000, false)
        store.setPreference("audioLanguage", "eng"); store.setPreference("subtitleLanguage", "rus")
        store.close(); store = TvStore(context, database)
        assertEquals(2, store.history(true).size.toLong()); assertEquals(30_000L, store.progress(t.key(), "episode/custom")!!.positionMs)
        assertEquals(1, store.library(true).size.toLong()); assertEquals("eng", store.preference("audioLanguage", ""))
        store.progress(t, "episode/custom", 60_000, 60_000, true); assertEquals(1, store.history(true).size.toLong())
        store.watched(t, "episode/custom", false); assertFalse(store.progress(t.key(), "episode/custom")!!.watched)
        store.removeLibrary(t.key()); assertTrue(store.history(false).isEmpty())
    }

    @Test @Throws(Exception::class) fun migrationKeepsHistoryAndMetadataNeverPersistsPlayableAddresses() {
        val t = title("custom:old"); store.progress(t, "original/episode", 42_000, 90_000, false)
        val db: SQLiteDatabase = store.writableDatabase
        db.execSQL("DROP TABLE metadata_cache"); db.execSQL("DROP TABLE catalog_visibility"); db.execSQL("DROP INDEX progress_updated"); db.version = 1
        store.close(); store = TvStore(context, database)
        assertEquals(TvStore.VERSION.toLong(), store.readableDatabase.version.toLong()); assertEquals(42_000L, store.history(true)[0].positionMs)
        val raw = Title.parse("p", JSONObject("{\"id\":\"a\",\"type\":\"series\",\"name\":\"A\",\"videos\":[{\"id\":\"custom/1\",\"title\":\"One\",\"streams\":[{\"url\":\"https://example.com/expired-token\"}]}]}"))!!
        assertEquals(1, raw.episodes[0].streams.size.toLong()); assertFalse(raw.cacheJson().contains("expired-token"))
        store.cache("secret-request-address", raw.cacheJson())
        assertNotNull(store.cached("secret-request-address"))
        store.clearMetadata(); assertNull(store.cached("secret-request-address")); assertEquals(1, store.history(true).size.toLong())
    }

    @Test @Throws(Exception::class) fun unreadableCachedMetadataIsRefetchedWithoutHidingPrivateDataErrors() {
        val title = title("fixture"); store.favourite(title, true); store.setPreference("audioLanguage", "eng")
        store.cache("broken", "old")
        store.writableDatabase.execSQL("UPDATE metadata_cache SET data=?", arrayOf<Any>(SecretBox().encrypt("wrong-scope", "old")))
        store.cache("healthy", "good")
        assertNull("An unauthenticated cache entry is a miss", store.cached("broken"))
        assertEquals("Other cache entries remain readable", "good", store.cached("healthy"))
        assertEquals("Saved library remains readable", 1, store.library(true).size.toLong())
        assertEquals("Preferences remain readable", "eng", store.preference("audioLanguage", ""))
        store.cache("broken", "fresh"); assertEquals("A fresh response replaces the unreadable entry", "fresh", store.cached("broken"))
        store.writableDatabase.execSQL("UPDATE preferences SET data=? WHERE name=?",
                arrayOf<Any>(SecretBox().encrypt("wrong-scope", "eng"), "audioLanguage"))
        try { store.preference("audioLanguage", ""); fail("Private preference corruption must be reported") }
        catch (expected: IllegalStateException) { /* Only disposable cache failures are recoverable. */ }
    }

    @Test fun metadataCacheStaysBoundedAcrossManyLargeEntries() {
        val chars = CharArray(1 shl 20); Arrays.fill(chars, 'x'); val value = String(chars)
        for (i in 0 until 9) store.cache("large-$i", value)
        store.readableDatabase.rawQuery("SELECT SUM(length(data)) FROM metadata_cache", null).use { c ->
            assertTrue(c.moveToFirst()); assertTrue(c.getLong(0) <= (8 shl 20))
        }
        assertEquals(value, store.cached("large-8")); assertNull(store.cached("large-0"))
    }

    @Test @Throws(Exception::class) fun importedDescriptorsReopenWithoutPayloadAndFileTraversalIsRefused() {
        var imports = ImportRepository(context, store)
        val id = imports.addAddress("A local magnet", "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        imports.selectedFile(id, 0); imports.rename(id, "Renamed")
        store.close(); store = TvStore(context, database); imports = ImportRepository(context, store)
        assertEquals("Renamed", store.importDescriptor(id)!!.getString("name")); assertEquals(0, store.importDescriptor(id)!!.getInt("fileIdx").toLong())
        assertEquals("magnet", store.importDescriptor(id)!!.getString("kind"))
        try { imports.remove("../outside"); fail("Traversal accepted") } catch (expected: IllegalArgumentException) {}
        val selected = File(context.cacheDir, "test-import-" + UUID.randomUUID() + ".torrent")
        val origin = InstrumentationRegistry.getArguments().getString("fixtures", "http://127.0.0.1:58133")
        var copied: String? = null
        try {
            AddonClient().use { client -> Cancellation().use { scope ->
                val index = JSONArray(String(client.fetch("$origin/index.json", 2 shl 20, scope, AddonClient.deadline()), StandardCharsets.UTF_8))
                var address: String? = null
                for (i in 0 until index.length()) if (index.getJSONObject(i).getString("name") == "film-hybrid") address = index.getJSONObject(i).getString("torrent")
                Files.write(selected.toPath(), client.fetch(address!!, ImportRepository.MAX_METAINFO, scope, AddonClient.deadline()))
                copied = imports.addFile("Copied torrent", Uri.fromFile(selected), scope); selected.delete()
                val config = TorrentEngine.Config.localOnly(File(context.cacheDir, "import-test-payload").path)
                config.cacheBytes = 16L shl 20; config.reserveBytes = 0
                TorrentEngine.create(config).use { engine ->
                    val torrent = engine.add(imports.open(copied, client, scope))
                    assertFalse(engine.awaitMetadata(torrent, 5000).isEmpty())
                    // Metadata alone never starts downloading payload.
                    assertEquals(0L, engine.stats(torrent).getLong("totalDownload"))
                }
                assertTrue(store.importIds().contains(copied))
            } }
        } finally { selected.delete(); if (copied != null) imports.remove(copied); imports.remove(id) }
    }
}
