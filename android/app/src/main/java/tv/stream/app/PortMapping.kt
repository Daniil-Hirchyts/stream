package tv.stream.app

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import tv.stream.app.data.TvStore

/**
 * Router port mapping (PCP, NAT-PMP, then UPnP IGD) is off unless the viewer turns it on in Settings. The engine then
 * asks the default route's IPv4 gateway, and only that address, to forward the listen port while playback runs; see
 * native/README.md.
 */
internal object PortMapping {
    const val PREFERENCE = "portMapping"

    /** Reads the store: call on a worker. */
    @JvmStatic fun enabled(store: TvStore): Boolean = store.preference(PREFERENCE, "false") == "true"

    /** The active network's default IPv4 gateway, or null when there is none to ask. */
    @JvmStatic fun gateway(context: Context): String? = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val links = connectivity?.getLinkProperties(connectivity.activeNetwork)
        links?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }
            ?.gateway?.hostAddress
    } catch (denied: SecurityException) { null }
}
