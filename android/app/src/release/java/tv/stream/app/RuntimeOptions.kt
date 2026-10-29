package tv.stream.app

import android.content.Context

/** Release configuration contains no fixture/test preferences or peers. */
@Suppress("UNUSED_PARAMETER")
class RuntimeOptions(context: Context, configuredCache: Long) {
    @JvmField val localOnly: Boolean = false
    @JvmField val publicNetworkOnly: Boolean = false
    @JvmField val peer: String = ""
    @JvmField val cacheBytes: Long = configuredCache

    companion object {
        @JvmStatic fun playerPreferences(database: String): String { return "player" }
        @JvmStatic fun databaseName(context: Context): String { return "stream.db" }
        @JvmStatic fun addonDirectory(context: Context): List<String> { return java.util.Collections.singletonList(tv.stream.app.addon.AddonRepository.DIRECTORY) }
        @JvmStatic fun starterAddons(context: Context): List<String> { return StarterAddons.ALL }
        /** A new install sets itself up with the starter add-ons. */
        @JvmStatic fun installDefaults(context: Context): Boolean { return true }
        @JvmStatic fun checkNewEpisodes(context: Context): Boolean { return true }
    }
}
