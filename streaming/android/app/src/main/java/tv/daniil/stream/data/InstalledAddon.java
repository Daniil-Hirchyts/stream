package tv.daniil.stream.data;

import tv.daniil.stream.addon.AddonManifest;
import tv.daniil.stream.addon.AddonUrl;

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
