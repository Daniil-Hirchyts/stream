package tv.daniil.stream;

import android.content.Context;

/** Release configuration contains no fixture/test preferences or peers. */
public final class RuntimeOptions {
    static String playerPreferences(String database) { return "player"; }
    static String databaseName(Context context) { return "daniiltv.db"; }
    static java.util.List<String> addonDirectory(Context context) { return java.util.Collections.singletonList(tv.daniil.stream.addon.AddonRepository.DIRECTORY); }
    static java.util.List<String> starterAddons(Context context) { return StarterAddons.ALL; }
    public final boolean localOnly = false;
    public final String peer = "";
    public final long cacheBytes;
    RuntimeOptions(Context context, long configuredCache) { cacheBytes = configuredCache; }
}
