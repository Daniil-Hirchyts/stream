package tv.stream.app.data

import tv.stream.app.addon.AddonManifest
import tv.stream.app.addon.AddonUrl

class InstalledAddon(@JvmField val id: String, @JvmField val address: AddonUrl, @JvmField val manifest: AddonManifest, @JvmField val enabled: Boolean, @JvmField val order: Int) {
    override fun toString(): String { return "Add-on $id ($address)" }
}
