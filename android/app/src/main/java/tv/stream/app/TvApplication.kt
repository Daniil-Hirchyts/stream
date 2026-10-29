package tv.stream.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import tv.stream.app.addon.AddonRepository
import tv.stream.app.data.ImportRepository
import tv.stream.app.data.TvStore

/** Process-owned local data. Initialization and all database work happen on application workers. */
class TvApplication : Application() {
    @JvmField val workers: ExecutorService = Executors.newFixedThreadPool(2)
    @JvmField val persistence: ExecutorService = Executors.newSingleThreadExecutor()
    /** Native close from an outgoing activity must finish before a new activity can create its session. */
    @JvmField val playbackLoader: ExecutorService = Executors.newSingleThreadExecutor()
    /** Stats must not queue behind blocking catalogue/subtitle requests. No native lifetime work runs here. */
    @JvmField val playbackTelemetry: ExecutorService = Executors.newSingleThreadExecutor()
    /** Quiet provider checks (new episodes) that must never hold up a page build. */
    @JvmField val background: ExecutorService = Executors.newSingleThreadExecutor()
    private var services: Services? = null
    /** Last developer report is process-local: no history, saved stream URLs or files to clean up. */
    @Volatile @JvmField var lastErrorReport: DiagnosticReport? = null
    @Synchronized
    fun services(): Services {
        val database = RuntimeOptions.databaseName(this)
        if (services != null && services!!.store.databaseName != database) resetServices()
        if (services == null) services = Services(this, database)
        return services!!
    }
    @Synchronized
    fun resetServices() { if (services != null) { services!!.preparation.close(); services!!.addons.close(); services!!.store.close(); services = null } }
    class Services(app: TvApplication, database: String) {
        @JvmField val store: TvStore
        @JvmField val addons: AddonRepository
        @JvmField val preparation: PlaybackPreparation
        @JvmField val imports: ImportRepository
        @JvmField val playerPreferences: SharedPreferences
        /** The starter add-ons the store and an empty Home offer (none under local-only instrumentation). */
        @JvmField val starters: List<String>
        init {
            // A new process owns no playback session yet. Recover only our disposable playback directories.
            clear(java.io.File(app.cacheDir, "payload"))
            clear(java.io.File(app.cacheDir, "subtitles"))
            store = TvStore(app, database)
            addons = AddonRepository(store, RuntimeOptions.addonDirectory(app))
            preparation = PlaybackPreparation(addons)
            imports = ImportRepository(app, store)
            playerPreferences = app.getSharedPreferences(RuntimeOptions.playerPreferences(database), Context.MODE_PRIVATE)
            starters = RuntimeOptions.starterAddons(app)
            tv.stream.playback.Capabilities.load(app)
            DecoderProfile.load(app)
        }
        fun cacheBytes(): Long {
            val value = store.preference("cacheMiB", "256")
            return (if (value == "128") 128L else if (value == "512") 512L else 256L) shl 20
        }
        companion object {
            private fun clear(file: java.io.File) {
                val children = file.listFiles(); if (children != null) for (child in children) clear(child)
                file.delete()
            }
        }
    }
}
