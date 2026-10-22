package tv.daniil.cast;

import androidx.media3.common.PlaybackException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.daniil.playback.PlaybackErrors;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PlaybackErrorsTest {
    @Test public void nestedTrustFailureExplainsCertificateInsteadOfInternet() {
        SSLHandshakeException tls = new SSLHandshakeException("Handshake failed");
        tls.initCause(new CertPathValidatorException("Trust anchor not found"));
        PlaybackErrors.Description description = describe(tls);
        assertEquals("certificate", description.code);
        assertFalse(description.retry);
        assertTrue(description.message.contains("certificate"));
    }

    @Test public void dnsFailureRemainsRetryableNetworkProblem() {
        PlaybackErrors.Description description = describe(new UnknownHostException("test.invalid"));
        assertEquals("network", description.code);
        assertTrue(description.retry);
    }

    private PlaybackErrors.Description describe(Throwable cause) {
        return PlaybackErrors.describe(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                new PlaybackException("Load failed", cause, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED));
    }
}
