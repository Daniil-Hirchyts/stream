package tv.stream.app.data;

import tv.stream.app.addon.AddonManifest;
import tv.stream.app.addon.AddonUrl;

public final class InstalledAddon {
    public final String id;
    public final AddonUrl address;
    public final AddonManifest manifest;
    public final boolean enabled;
    public final int order;
    InstalledAddon(String id, AddonUrl address, AddonManifest manifest, boolean enabled, int order) {
        this.id = id; this.address = address; this.manifest = manifest; this.enabled = enabled; this.order = order;
    }
    @Override public String toString() { return "Add-on " + id + " (" + address + ")"; }
}
