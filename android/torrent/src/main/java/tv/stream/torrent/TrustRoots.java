package tv.stream.torrent;

import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * The trust roots this app accepts, for the native TLS of HTTPS trackers and web seeds. OpenSSL can't read Android's
 * certificate store, and must never fall back to unverified connections, so the roots the platform trust manager
 * accepts (honouring the app's network security configuration) are written as a PEM file the engine loads.
 */
public final class TrustRoots {
    private TrustRoots() {}

    public static void writePem(File file) throws IOException {
        X509Certificate[] roots;
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            X509TrustManager manager = null;
            for (TrustManager tm : factory.getTrustManagers()) {
                if (tm instanceof X509TrustManager) manager = (X509TrustManager) tm;
            }
            if (manager == null) throw new IOException("No X.509 trust manager");
            roots = manager.getAcceptedIssuers();
        } catch (GeneralSecurityException e) {
            throw new IOException("Trust roots unavailable", e);
        }
        if (roots.length == 0) throw new IOException("The platform trusts no roots");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File partial = new File(file.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            for (X509Certificate root : roots) {
                String body = Base64.encodeToString(root.getEncoded(), Base64.DEFAULT);
                out.write(("-----BEGIN CERTIFICATE-----\n" + body + "-----END CERTIFICATE-----\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Unreadable trust root", e);
        }
        if (!partial.renameTo(file)) throw new IOException("Cannot write " + file);
    }
}
