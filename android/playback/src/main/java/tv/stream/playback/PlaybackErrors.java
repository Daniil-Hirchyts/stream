package tv.stream.playback;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.cert.CertificateException;
import java.security.cert.CertPathValidatorException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Plain-language explanations of failures that need the viewer. Formats the TV can't decode are not among them:
 * the app converts those or offers another source. The technical detail goes to diagnostics, not the screen.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackErrors {
    public static final class Description {
        /** A short code an app can act on, such as "expired" or "network". */
        public final String code, title, message, detail;
        public final int httpStatus;
        /** Whether trying the same stream again may help. */
        public final boolean retry;

        public Description(String code, String title, String message, String detail, int httpStatus, boolean retry) {
            this.code = code;
            this.title = title;
            this.message = message;
            this.detail = detail;
            this.httpStatus = httpStatus;
            this.retry = retry;
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("code", code).put("title", title).put("message", message)
                    .put("detail", detail).put("httpStatus", httpStatus).put("retry", retry);
        }
    }

    private PlaybackErrors() {}

    public static Description describe(Context context, PlaybackException exception) {
        String detail = exception.getErrorCodeName();
        int http = httpStatus(exception);
        if (http != 0) detail += " · HTTP " + http;
        int code = exception.errorCode;
        if (certificateFailure(exception)) {
            return of(context, "certificate", R.string.error_certificate_title, R.string.error_certificate,
                    detail + " · The server certificate could not be verified", 0, false);
        } else if (http == 401 || http == 403) {
            return of(context, "forbidden", R.string.error_forbidden_title, R.string.error_forbidden, detail, http, false);
        } else if (http == 404 || http == 410) {
            return of(context, "expired", R.string.error_expired_title, R.string.error_expired, detail, http, false);
        } else if (http != 0) {
            return of(context, "server", R.string.error_server_title, R.string.error_server, detail, http, true);
        } else if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return of(context, "network", R.string.error_network_title, R.string.error_network, detail, 0, true);
        } else if (code >= 6000 && code < 7000) {
            return of(context, "drm", R.string.error_drm_title, R.string.error_drm, detail, 0, false);
        } else if (code >= 4000 && code < 5000) {
            return of(context, "decoder", R.string.error_decoder_title, R.string.error_decoder, detail, 0, true);
        } else if ((code >= 3000 && code < 4000) || code == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE) {
            return of(context, "format", R.string.error_format_title, R.string.error_format, detail, 0, false);
        }
        return of(context, "generic", R.string.error_generic_title, R.string.error_generic, detail, 0, true);
    }

    public static Description of(Context context, String code, int title, int message, String detail, int http,
            boolean retry) {
        return new Description(code, context.getString(title), context.getString(message), detail, http, retry);
    }

    /** Media3 can wrap the HTTP failure several causes deep. */
    private static int httpStatus(PlaybackException exception) {
        Throwable cause = exception.getCause();
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            }
        }
        return 0;
    }

    private static boolean certificateFailure(Throwable error) {
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof CertificateException || error instanceof CertPathValidatorException
                    || error instanceof SSLPeerUnverifiedException) return true;
        }
        return false;
    }
}
