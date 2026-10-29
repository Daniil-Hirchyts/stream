package tv.stream.app

import android.content.Context

/** Instrumentation-only configuration, absent from the release source set. */
class RuntimeOptions(context: Context, configuredCache: Long) {
    @JvmField val localOnly: Boolean
    @JvmField val publicNetworkOnly: Boolean
    @JvmField val peer: String
    @JvmField val cacheBytes: Long

    init {
        val test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE)
        localOnly = test.getBoolean("localOnly", false)
        publicNetworkOnly = !localOnly && databaseName(context).startsWith("test-") && test.getBoolean("publicNetworkOnly", false)
        peer = if (localOnly) test.getString("peer", "127.0.0.1:58134")!! else ""
        cacheBytes = if (localOnly) test.getLong("cacheBytes", configuredCache) else configuredCache
    }

    companion object {
        @JvmStatic fun playerPreferences(database: String): String { return if (database.matches("test-[a-z0-9-]+\\.db".toRegex())) "player-$database" else "player" }
        @JvmStatic fun databaseName(context: Context): String {
            val test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).getString("database", "")!!
            return if (test.matches("test-[a-z0-9-]+\\.db".toRegex())) test else "stream.db"
        }
        /** Stremio's directory, except under local-only instrumentation, which may name a fixture directory instead. */
        @JvmStatic fun addonDirectory(context: Context): List<String> {
            val test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE)
            if (!test.getBoolean("localOnly", false)) return java.util.Collections.singletonList(tv.stream.app.addon.AddonRepository.DIRECTORY)
            val fixture = test.getString("addonDirectory", "")!!
            return if (fixture.isEmpty()) java.util.Collections.emptyList() else java.util.Collections.singletonList(fixture)
        }
        /** The owner's debug app sets itself up as a release does; test databases and local-only runs never do. */
        @JvmStatic fun installDefaults(context: Context): Boolean {
            return !context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).getBoolean("localOnly", false)
                    && !databaseName(context).startsWith("test-")
        }
        /** Background new-episode checks, like the defaults, belong to the owner's app; tests call the check themselves. */
        @JvmStatic fun checkNewEpisodes(context: Context): Boolean { return installDefaults(context) }
        /**
         * What onboarding offers to add. Local-only instrumentation must not reach public providers, so it offers only
         * the fixture add-ons it names (none by default).
         */
        @JvmStatic fun starterAddons(context: Context): List<String> {
            val test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE)
            if (!test.getBoolean("localOnly", false)) return StarterAddons.ALL
            val fixtures = test.getString("starterAddons", "")!!
            return if (fixtures.isEmpty()) java.util.Collections.emptyList() else java.util.Arrays.asList(*java.util.regex.Pattern.compile(" ").split(fixtures))
        }
    }
}
