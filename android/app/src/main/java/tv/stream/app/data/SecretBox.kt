package tv.stream.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a non-exportable Android Keystore key. No plaintext fallback on key loss. */
class SecretBox {
    private val key: SecretKey?

    init {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            if (!store.containsAlias(ALIAS)) {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build())
                generator.generateKey()
            }
            key = store.getKey(ALIAS, null) as SecretKey?
        } catch (e: Exception) { throw IllegalStateException("Private storage encryption is unavailable", e) }
    }

    @Synchronized
    fun encrypt(scope: String, text: String): String {
        return Base64.encodeToString(seal(scope, text.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }
    @Synchronized
    fun decrypt(scope: String, text: String): String {
        return String(open(scope, Base64.decode(text, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }
    @Synchronized
    fun seal(scope: String, bytes: ByteArray): ByteArray {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            c.updateAAD(scope.toByteArray(StandardCharsets.UTF_8))
            val encrypted = c.doFinal(bytes)
            val iv = c.iv
            val out = ByteArray(1 + iv.size + encrypted.size)
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(encrypted, 0, out, 1 + iv.size, encrypted.size)
            return out
        } catch (e: GeneralSecurityException) { throw IllegalStateException("Could not encrypt private data", e) }
    }
    @Synchronized
    fun open(scope: String, bytes: ByteArray): ByteArray {
        try {
            if (bytes.size < 29 || bytes[0].toInt() != 12) throw GeneralSecurityException("Invalid encrypted data")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 1, 12))
            c.updateAAD(scope.toByteArray(StandardCharsets.UTF_8))
            return c.doFinal(bytes, 13, bytes.size - 13)
        } catch (e: GeneralSecurityException) { throw IllegalStateException("Private data could not be unlocked; reinstall the affected add-on or import", e) }
    }

    companion object {
        private const val ALIAS = "stream.local.v1"
    }
}
