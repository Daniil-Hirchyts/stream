package tv.stream.app.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM with a non-exportable Android Keystore key. No plaintext fallback on key loss. */
public final class SecretBox {
    private static final String ALIAS = "stream.local.v1";
    private final SecretKey key;

    public SecretBox() {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (!store.containsAlias(ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build());
                generator.generateKey();
            }
            key = (SecretKey) store.getKey(ALIAS, null);
        } catch (Exception e) { throw new IllegalStateException("Private storage encryption is unavailable", e); }
    }

    public synchronized String encrypt(String scope, String text) {
        return Base64.encodeToString(seal(scope, text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    public synchronized String decrypt(String scope, String text) {
        return new String(open(scope, Base64.decode(text, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    public synchronized byte[] seal(String scope, byte[] bytes) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            c.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = c.doFinal(bytes), iv = c.getIV();
            byte[] out = new byte[1 + iv.length + encrypted.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(encrypted, 0, out, 1 + iv.length, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Could not encrypt private data", e); }
    }
    public synchronized byte[] open(String scope, byte[] bytes) {
        try {
            if (bytes.length < 29 || bytes[0] != 12) throw new GeneralSecurityException("Invalid encrypted data");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 1, 12));
            c.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(bytes, 13, bytes.length - 13);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Private data could not be unlocked; reinstall the affected add-on or import", e); }
    }
}
