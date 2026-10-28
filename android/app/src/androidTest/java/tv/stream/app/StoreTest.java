package tv.stream.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import tv.stream.app.addon.*;
import tv.stream.app.data.*;
import tv.stream.torrent.*;

@RunWith(AndroidJUnit4.class)
public final class StoreTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String database;
    private TvStore store;
    @Before public void open() { database = "store-test-" + UUID.randomUUID() + ".db"; store = new TvStore(context, database); }
    @After public void close() { store.close(); context.deleteDatabase(database); }
    private Title title(String id) throws Exception { return Title.parse("provider", new JSONObject().put("id", id).put("type", "series").put("name", "Generated Series")); }
    private AddonManifest manifest() throws Exception { return AddonManifest.parse(new JSONObject("{\"id\":\"configured\",\"name\":\"Configured\",\"version\":\"1\",\"types\":[\"movie\"],\"resources\":[\"catalog\"]}")); }

    @Test public void credentialsAreEncryptedAuthenticatedAndBackupIsDisabled() throws Exception {
        String secret = "fixture-secret-" + UUID.randomUUID();
        InstalledAddon installed = store.install(new AddonUrl("https://example.com/" + secret + "/manifest.json?token=" + secret), manifest());
        try (Cursor c = store.getReadableDatabase().rawQuery("SELECT data FROM addons", null)) {
            assertTrue(c.moveToFirst()); assertFalse(c.getString(0).contains(secret));
        }
        store.close(); store = new TvStore(context, database);
        assertTrue(store.addons().get(0).address.manifest.contains(secret));
        assertFalse(installed.toString().contains(secret));
        SecretBox box = new SecretBox(); String encrypted = box.encrypt("one", secret);
        try { box.decrypt("two", encrypted); fail("Data was moved between encryption scopes"); } catch (IllegalStateException expected) {}
        assertEquals(0, context.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
        // SQLite/WAL contain only ciphertext for configured URLs, not the token.
        for (String suffix : new String[]{"", "-wal"}) {
            File file = new File(context.getDatabasePath(database).getPath() + suffix);
            if (file.exists()) assertFalse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1).contains(secret));
        }
    }

    @Test public void addOnConfigurationOrderVisibilityAndRemovalSurviveRestart() throws Exception {
        assertTrue(store.addons().isEmpty());
        InstalledAddon a = store.install(new AddonUrl("https://example.com/one/manifest.json"), manifest());
        InstalledAddon b = store.install(new AddonUrl("https://example.com/two/manifest.json"), manifest());
        assertNotEquals(a.id, b.id); store.enableAddon(a.id, false);
        assertEquals(a.id, store.install(a.address, manifest()).id); assertFalse(store.addons().get(0).enabled);
        store.orderAddons(Arrays.asList(b.id, a.id)); store.catalogVisible(b.id, "movie", "all", false);
        store.close(); store = new TvStore(context, database);
        assertEquals(b.id, store.addons().get(0).id); assertFalse(store.catalogVisible(b.id, "movie", "all"));
        store.removeAddon(b.id); assertEquals(1, store.addons().size());
        try (Cursor c = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM catalog_visibility", null)) { c.moveToFirst(); assertEquals(0, c.getInt(0)); }
        try { store.orderAddons(Collections.singletonList("wrong")); fail("Unknown order accepted"); } catch (IllegalArgumentException expected) {}
    }

    @Test public void historyResumeWatchedAndLanguagePreferencesSurviveReopen() throws Exception {
        Title t = title("custom:series");
        store.favourite(t, true); store.progress(t, "episode/custom", 29_999, 60_000, false);
        assertTrue(store.history(true).isEmpty());
        store.progress(t, "episode/custom", 30_000, 60_000, false); assertEquals(1, store.history(true).size());
        store.progress(t, "episode/other", 31_000, 90_000, false);
        store.setPreference("audioLanguage", "eng"); store.setPreference("subtitleLanguage", "rus");
        store.close(); store = new TvStore(context, database);
        assertEquals(2, store.history(true).size()); assertEquals(30_000, store.progress(t.key(), "episode/custom").positionMs);
        assertEquals(1, store.library(true).size()); assertEquals("eng", store.preference("audioLanguage", ""));
        store.progress(t, "episode/custom", 60_000, 60_000, true); assertEquals(1, store.history(true).size());
        store.watched(t, "episode/custom", false); assertFalse(store.progress(t.key(), "episode/custom").watched);
        store.removeLibrary(t.key()); assertTrue(store.history(false).isEmpty());
    }

    @Test public void migrationKeepsHistoryAndMetadataNeverPersistsPlayableAddresses() throws Exception {
        Title t = title("custom:old"); store.progress(t, "original/episode", 42_000, 90_000, false);
        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("DROP TABLE metadata_cache"); db.execSQL("DROP TABLE catalog_visibility"); db.execSQL("DROP INDEX progress_updated"); db.setVersion(1);
        store.close(); store = new TvStore(context, database);
        assertEquals(TvStore.VERSION, store.getReadableDatabase().getVersion()); assertEquals(42_000, store.history(true).get(0).positionMs);
        Title raw = Title.parse("p", new JSONObject("{\"id\":\"a\",\"type\":\"series\",\"name\":\"A\",\"videos\":[{\"id\":\"custom/1\",\"title\":\"One\",\"streams\":[{\"url\":\"https://example.com/expired-token\"}]}]}"));
        assertEquals(1, raw.episodes.get(0).streams.size()); assertFalse(raw.cacheJson().contains("expired-token"));
        store.cache("secret-request-address", raw.cacheJson());
        assertNotNull(store.cached("secret-request-address"));
        store.clearMetadata(); assertNull(store.cached("secret-request-address")); assertEquals(1, store.history(true).size());
    }

    @Test public void unreadableCachedMetadataIsRefetchedWithoutHidingPrivateDataErrors() throws Exception {
        Title title = title("fixture"); store.favourite(title, true); store.setPreference("audioLanguage", "eng");
        store.cache("broken", "old");
        store.getWritableDatabase().execSQL("UPDATE metadata_cache SET data=?", new Object[]{new SecretBox().encrypt("wrong-scope", "old")});
        store.cache("healthy", "good");
        assertNull("An unauthenticated cache entry is a miss", store.cached("broken"));
        assertEquals("Other cache entries remain readable", "good", store.cached("healthy"));
        assertEquals("Saved library remains readable", 1, store.library(true).size());
        assertEquals("Preferences remain readable", "eng", store.preference("audioLanguage", ""));
        store.cache("broken", "fresh"); assertEquals("A fresh response replaces the unreadable entry", "fresh", store.cached("broken"));
        store.getWritableDatabase().execSQL("UPDATE preferences SET data=? WHERE name=?",
                new Object[]{new SecretBox().encrypt("wrong-scope", "eng"), "audioLanguage"});
        try { store.preference("audioLanguage", ""); fail("Private preference corruption must be reported"); }
        catch (IllegalStateException expected) { /* Only disposable cache failures are recoverable. */ }
    }

    @Test public void metadataCacheStaysBoundedAcrossManyLargeEntries() {
        char[] chars = new char[1 << 20]; Arrays.fill(chars, 'x'); String value = new String(chars);
        for (int i = 0; i < 9; i++) store.cache("large-" + i, value);
        try (Cursor c = store.getReadableDatabase().rawQuery("SELECT SUM(length(data)) FROM metadata_cache", null)) {
            assertTrue(c.moveToFirst()); assertTrue(c.getLong(0) <= (8 << 20));
        }
        assertEquals(value, store.cached("large-8")); assertNull(store.cached("large-0"));
    }

    @Test public void importedDescriptorsReopenWithoutPayloadAndFileTraversalIsRefused() throws Exception {
        ImportRepository imports = new ImportRepository(context, store);
        String id = imports.addAddress("A local magnet", "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        imports.selectedFile(id, 0); imports.rename(id, "Renamed");
        store.close(); store = new TvStore(context, database); imports = new ImportRepository(context, store);
        assertEquals("Renamed", store.importDescriptor(id).getString("name")); assertEquals(0, store.importDescriptor(id).getInt("fileIdx"));
        assertEquals("magnet", store.importDescriptor(id).getString("kind"));
        try { imports.remove("../outside"); fail("Traversal accepted"); } catch (IllegalArgumentException expected) {}
        File selected = new File(context.getCacheDir(), "test-import-" + UUID.randomUUID() + ".torrent");
        String origin = InstrumentationRegistry.getArguments().getString("fixtures", "http://127.0.0.1:58133");
        String copied = null;
        try (AddonClient client = new AddonClient(); Cancellation scope = new Cancellation()) {
            JSONArray index = new JSONArray(new String(client.fetch(origin + "/index.json", 2 << 20, scope, AddonClient.deadline()), StandardCharsets.UTF_8));
            String address = null;
            for (int i = 0; i < index.length(); i++) if (index.getJSONObject(i).getString("name").equals("film-hybrid")) address = index.getJSONObject(i).getString("torrent");
            Files.write(selected.toPath(), client.fetch(address, ImportRepository.MAX_METAINFO, scope, AddonClient.deadline()));
            copied = imports.addFile("Copied torrent", Uri.fromFile(selected), scope); selected.delete();
            TorrentEngine.Config config = TorrentEngine.Config.localOnly(new File(context.getCacheDir(), "import-test-payload").getPath());
            config.cacheBytes = 16L << 20; config.reserveBytes = 0;
            try (TorrentEngine engine = TorrentEngine.create(config)) {
                long torrent = engine.add(imports.open(copied, client, scope));
                assertFalse(engine.awaitMetadata(torrent, 5000).isEmpty());
                // Metadata alone never starts downloading payload.
                assertEquals(0, engine.stats(torrent).getLong("totalDownload"));
            }
            assertTrue(store.importIds().contains(copied));
        } finally { selected.delete(); if (copied != null) imports.remove(copied); imports.remove(id); }
    }
}
