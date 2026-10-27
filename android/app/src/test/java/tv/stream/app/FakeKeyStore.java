package tv.stream.app;

import android.security.keystore.KeyGenParameterSpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Robolectric has no Android Keystore; this in-memory stand-in lets the app's real SecretBox run in JVM UI tests. */
public final class FakeKeyStore extends Provider {
    private static final Map<String, Key> KEYS = new ConcurrentHashMap<>();

    public FakeKeyStore() {
        super("AndroidKeyStore", 1.0, "In-memory test keystore");
        put("KeyStore.AndroidKeyStore", Store.class.getName());
        put("KeyGenerator.AES", Generator.class.getName());
    }

    public static void install() { if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(new FakeKeyStore()); }

    public static final class Generator extends KeyGeneratorSpi {
        private String alias = "default";
        @Override protected void engineInit(SecureRandom random) {}
        @Override protected void engineInit(int size, SecureRandom random) {}
        @Override protected void engineInit(AlgorithmParameterSpec spec, SecureRandom random) {
            if (spec instanceof KeyGenParameterSpec) alias = ((KeyGenParameterSpec) spec).getKeystoreAlias();
        }
        @Override protected SecretKey engineGenerateKey() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            SecretKey key = new SecretKeySpec(bytes, "AES");
            KEYS.put(alias, key);
            return key;
        }
    }

    public static final class Store extends KeyStoreSpi {
        @Override public Key engineGetKey(String alias, char[] password) { return KEYS.get(alias); }
        @Override public Certificate[] engineGetCertificateChain(String alias) { return null; }
        @Override public Certificate engineGetCertificate(String alias) { return null; }
        @Override public Date engineGetCreationDate(String alias) { return KEYS.containsKey(alias) ? new Date() : null; }
        @Override public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) { KEYS.put(alias, key); }
        @Override public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) { throw new UnsupportedOperationException(); }
        @Override public void engineSetCertificateEntry(String alias, Certificate cert) { throw new UnsupportedOperationException(); }
        @Override public void engineDeleteEntry(String alias) { KEYS.remove(alias); }
        @Override public Enumeration<String> engineAliases() { return Collections.enumeration(KEYS.keySet()); }
        @Override public boolean engineContainsAlias(String alias) { return KEYS.containsKey(alias); }
        @Override public int engineSize() { return KEYS.size(); }
        @Override public boolean engineIsKeyEntry(String alias) { return KEYS.containsKey(alias); }
        @Override public boolean engineIsCertificateEntry(String alias) { return false; }
        @Override public String engineGetCertificateAlias(Certificate cert) { return null; }
        @Override public void engineStore(OutputStream stream, char[] password) {}
        @Override public void engineLoad(InputStream stream, char[] password) {}
        @Override public void engineLoad(KeyStore.LoadStoreParameter parameter) {}
    }
}
