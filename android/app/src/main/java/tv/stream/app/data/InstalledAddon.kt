package tv.stream.app.data

import tv.stream.app.addon.AddonManifest
import tv.stream.app.addon.AddonUrl

data class InstalledAddon(val id: String, val address: AddonUrl, val manifest: AddonManifest, val enabled: Boolean, val order: Int) {
    override fun toString(): String { return "Add-on $id ($address)" }
}
