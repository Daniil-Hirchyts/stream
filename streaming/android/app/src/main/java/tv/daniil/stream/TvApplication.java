package tv.daniil.stream;

import android.app.Application;
import android.content.SharedPreferences;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tv.daniil.stream.addon.AddonRepository;
import tv.daniil.stream.data.ImportRepository;
import tv.daniil.stream.data.TvStore;

/** Process-owned local data. Initialization and all database work happen on application workers. */
public final class TvApplication extends Application {
    public final ExecutorService workers = Executors.newFixedThreadPool(2);
    public final ExecutorService persistence = Executors.newSingleThreadExecutor();
    /** Native close from an outgoing activity must finish before a new activity can create its session. */
    public final ExecutorService playbackLoader = Executors.newSingleThreadExecutor();
    /** Stats must not queue behind blocking catalogue/subtitle requests. No native lifetime work runs here. */
    public final ExecutorService playbackTelemetry = Executors.newSingleThreadExecutor();
    /** Quiet provider checks (new episodes) that must never hold up a page build. */
    public final ExecutorService background = Executors.newSingleThreadExecutor();
    private Services services;
    /** Last developer report is process-local: no history, saved stream URLs or files to clean up. */
    volatile DiagnosticReport lastErrorReport;
    public synchronized Services services() {
        String database = RuntimeOptions.databaseName(this);
        if (services != null && !services.store.getDatabaseName().equals(database)) resetServices();
        if (services == null) services = new Services(this, database);
        return services;
    }
    synchronized void resetServices() { if (services != null) { services.preparation.close(); services.addons.close(); services.store.close(); services = null; } }
    public static final class Services {
        public final TvStore store;
        public final AddonRepository addons;
        final PlaybackPreparation preparation;
        public final ImportRepository imports;
        public final SharedPreferences playerPreferences;
        /** The starter add-ons the store and an empty Home offer (none under local-only instrumentation). */
        public final java.util.List<String> starters;
        Services(TvApplication app, String database) {
            // A new process owns no playback session yet. Recover only our disposable playback directories.
            clear(new java.io.File(app.getCacheDir(), "payload"));
            clear(new java.io.File(app.getCacheDir(), "subtitles"));
            store = new TvStore(app, database);
            addons = new AddonRepository(store, RuntimeOptions.addonDirectory(app));
            preparation = new PlaybackPreparation(addons);
            imports = new ImportRepository(app, store);
            playerPreferences = app.getSharedPreferences(RuntimeOptions.playerPreferences(database), MODE_PRIVATE);
            starters = RuntimeOptions.starterAddons(app);
            tv.daniil.playback.Capabilities.load(app);
            DecoderProfile.load(app);
        }
        private static void clear(java.io.File file) {
            java.io.File[] children = file.listFiles(); if (children != null) for (java.io.File child : children) clear(child);
            file.delete();
        }
        public long cacheBytes() {
            String value = store.preference("cacheMiB", "256");
            return (value.equals("128") ? 128L : value.equals("512") ? 512L : 256L) << 20;
        }
    }
}
