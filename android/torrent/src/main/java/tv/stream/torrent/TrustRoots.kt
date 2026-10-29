package tv.stream.torrent

import android.util.Base64

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.X509Certificate

import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The trust roots this app accepts, for the native TLS of HTTPS trackers and web seeds. OpenSSL can't read Android's
 * certificate store, and must never fall back to unverified connections, so the roots the platform trust manager
 * accepts (honouring the app's network security configuration) are written as a PEM file the engine loads.
 */
object TrustRoots {
    @Throws(IOException::class)
    fun writePem(file: File) {
        val roots: Array<X509Certificate>
        try {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            var manager: X509TrustManager? = null
            for (tm: TrustManager in factory.trustManagers) {
                if (tm is X509TrustManager) manager = tm
            }
            if (manager == null) throw IOException("No X.509 trust manager")
            roots = manager.acceptedIssuers
        } catch (e: GeneralSecurityException) {
            throw IOException("Trust roots unavailable", e)
        }
        if (roots.isEmpty()) throw IOException("The platform trusts no roots")
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create $parent")
        val partial = File(file.path + ".part")
        try {
            FileOutputStream(partial).use { out: OutputStream ->
                for (root in roots) {
                    val body = Base64.encodeToString(root.encoded, Base64.DEFAULT)
                    out.write(("-----BEGIN CERTIFICATE-----\n" + body + "-----END CERTIFICATE-----\n")
                            .toByteArray(StandardCharsets.US_ASCII))
                }
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("Unreadable trust root", e)
        }
        if (!partial.renameTo(file)) throw IOException("Cannot write $file")
    }
}
