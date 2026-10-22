package tv.daniil.stream;

import android.content.Context;

/** Instrumentation-only configuration, absent from the release source set. */
public final class RuntimeOptions {
    static String playerPreferences(String database) { return database.matches("test-[a-z0-9-]+\\.db") ? "player-" + database : "player"; }
    static String databaseName(Context context) {
        String test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).getString("database", "");
        return test.matches("test-[a-z0-9-]+\\.db") ? test : "daniiltv.db";
    }
    public final boolean localOnly;
    public final String peer;
    public final long cacheBytes;
    RuntimeOptions(Context context, long configuredCache) {
        android.content.SharedPreferences test = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE);
        localOnly = test.getBoolean("localOnly", false);
        peer = localOnly ? test.getString("peer", "127.0.0.1:58134") : "";
        cacheBytes = localOnly ? test.getLong("cacheBytes", configuredCache) : configuredCache;
    }
}
