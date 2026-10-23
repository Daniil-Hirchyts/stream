package tv.daniil.stream;

import android.content.Context;

/** Instrumentation-only configuration, absent from the release source set. */
public final class RuntimeOptions {
    static String playerPreferences(String database) { return database.matches("test-[a-z0-9-]+\\.db") ? "player-" + database : "player"; }
    static String databaseName(Context context) {
        String test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).getString("database", "");
        return test.matches("test-[a-z0-9-]+\\.db") ? test : "daniiltv.db";
    }
    /** Stremio's directory, except under local-only instrumentation, which may name a fixture directory instead. */
    static java.util.List<String> addonDirectory(Context context) {
        android.content.SharedPreferences test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE);
        if (!test.getBoolean("localOnly", false)) return java.util.Collections.singletonList(tv.daniil.stream.addon.AddonRepository.DIRECTORY);
        String fixture = test.getString("addonDirectory", "");
        return fixture.isEmpty() ? java.util.Collections.emptyList() : java.util.Collections.singletonList(fixture);
    }
    /** The owner's debug app sets itself up as a release does; test databases and local-only runs never do. */
    static boolean installDefaults(Context context) {
        return !context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).getBoolean("localOnly", false)
                && !databaseName(context).startsWith("test-");
    }
    /**
     * What onboarding offers to add. Local-only instrumentation must not reach public providers, so it offers only
     * the fixture add-ons it names (none by default).
     */
    static java.util.List<String> starterAddons(Context context) {
        android.content.SharedPreferences test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE);
        if (!test.getBoolean("localOnly", false)) return StarterAddons.ALL;
        String fixtures = test.getString("starterAddons", "");
        return fixtures.isEmpty() ? java.util.Collections.emptyList() : java.util.Arrays.asList(fixtures.split(" "));
    }
    public final boolean localOnly;
    public final boolean publicNetworkOnly;
    public final String peer;
    public final long cacheBytes;
    RuntimeOptions(Context context, long configuredCache) {
        android.content.SharedPreferences test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE);
        localOnly = test.getBoolean("localOnly", false);
        publicNetworkOnly = !localOnly && databaseName(context).startsWith("test-") && test.getBoolean("publicNetworkOnly", false);
        peer = localOnly ? test.getString("peer", "127.0.0.1:58134") : "";
        cacheBytes = localOnly ? test.getLong("cacheBytes", configuredCache) : configuredCache;
    }
}
