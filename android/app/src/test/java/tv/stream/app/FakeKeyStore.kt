package tv.stream.app

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Robolectric has no Android Keystore; this in-memory stand-in lets the app's real SecretBox run in JVM UI tests. */
@Suppress("DEPRECATION")
class FakeKeyStore : Provider("AndroidKeyStore", 1.0, "In-memory test keystore") {
    init {
        put("KeyStore.AndroidKeyStore", Store::class.java.name)
        put("KeyGenerator.AES", Generator::class.java.name)
    }

    class Generator : KeyGeneratorSpi() {
        private var alias: String? = "default"
        override fun engineInit(random: SecureRandom?) {}
        override fun engineInit(size: Int, random: SecureRandom?) {}
        override fun engineInit(spec: AlgorithmParameterSpec?, random: SecureRandom?) {
            if (spec is KeyGenParameterSpec) alias = spec.keystoreAlias
        }
        override fun engineGenerateKey(): SecretKey {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val key: SecretKey = SecretKeySpec(bytes, "AES")
            KEYS.put(alias!!, key)
            return key
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? { return KEYS.get(alias) }
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? { return null }
        override fun engineGetCertificate(alias: String?): Certificate? { return null }
        override fun engineGetCreationDate(alias: String): Date? { return if (KEYS.containsKey(alias)) Date() else null }
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) { KEYS.put(alias, key) }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<Certificate>?) { throw UnsupportedOperationException() }
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) { throw UnsupportedOperationException() }
        override fun engineDeleteEntry(alias: String) { KEYS.remove(alias) }
        override fun engineAliases(): Enumeration<String> { return Collections.enumeration(KEYS.keys) }
        override fun engineContainsAlias(alias: String): Boolean { return KEYS.containsKey(alias) }
        override fun engineSize(): Int { return KEYS.size }
        override fun engineIsKeyEntry(alias: String): Boolean { return KEYS.containsKey(alias) }
        override fun engineIsCertificateEntry(alias: String?): Boolean { return false }
        override fun engineGetCertificateAlias(cert: Certificate?): String? { return null }
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
        override fun engineLoad(parameter: KeyStore.LoadStoreParameter?) {}
    }

    companion object {
        private val KEYS: MutableMap<String, Key> = ConcurrentHashMap()

        @JvmStatic fun install() { if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(FakeKeyStore()) }
    }
}
