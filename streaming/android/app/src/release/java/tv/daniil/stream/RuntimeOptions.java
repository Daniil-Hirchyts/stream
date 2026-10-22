package tv.daniil.stream;

import android.content.Context;

/** Release configuration contains no fixture/test preferences or peers. */
public final class RuntimeOptions {
    static String playerPreferences(String database) { return "player"; }
    static String databaseName(Context context) { return "daniiltv.db"; }
    public final boolean localOnly = false;
    public final String peer = "";
    public final long cacheBytes;
    RuntimeOptions(Context context, long configuredCache) { cacheBytes = configuredCache; }
}
