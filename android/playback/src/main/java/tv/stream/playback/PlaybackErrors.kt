package tv.stream.playback

import android.content.Context

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

import org.json.JSONException
import org.json.JSONObject

import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Plain-language explanations of failures that need the viewer. Formats the TV can't decode are not among them:
 * the app converts those or offers another source. The technical detail goes to diagnostics, not the screen.
 */
@OptIn(markerClass = [UnstableApi::class])
object PlaybackErrors {
    data class Description(
        /** A short code an app can act on, such as "expired" or "network". */
        val code: String,
        val title: String,
        val message: String,
        val detail: String,
        val httpStatus: Int,
        /** Whether trying the same stream again may help. */
        val retry: Boolean,
    ) {
        @Throws(JSONException::class)
        fun toJson(): JSONObject {
            return JSONObject().put("code", code).put("title", title).put("message", message)
                    .put("detail", detail).put("httpStatus", httpStatus).put("retry", retry)
        }
    }

    fun describe(context: Context, exception: PlaybackException): Description {
        var detail = exception.errorCodeName
        val http = httpStatus(exception)
        if (http != 0) detail += " · HTTP $http"
        val code = exception.errorCode
        if (certificateFailure(exception)) {
            return of(context, "certificate", R.string.error_certificate_title, R.string.error_certificate,
                    "$detail · The server certificate could not be verified", 0, false)
        } else if (http == 401 || http == 403) {
            return of(context, "forbidden", R.string.error_forbidden_title, R.string.error_forbidden, detail, http, false)
        } else if (http == 404 || http == 410) {
            return of(context, "expired", R.string.error_expired_title, R.string.error_expired, detail, http, false)
        } else if (http != 0) {
            return of(context, "server", R.string.error_server_title, R.string.error_server, detail, http, true)
        } else if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return of(context, "network", R.string.error_network_title, R.string.error_network, detail, 0, true)
        } else if (code >= 6000 && code < 7000) {
            return of(context, "drm", R.string.error_drm_title, R.string.error_drm, detail, 0, false)
        } else if (code >= 4000 && code < 5000) {
            return of(context, "decoder", R.string.error_decoder_title, R.string.error_decoder, detail, 0, true)
        } else if ((code >= 3000 && code < 4000) || code == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE) {
            return of(context, "format", R.string.error_format_title, R.string.error_format, detail, 0, false)
        }
        return of(context, "generic", R.string.error_generic_title, R.string.error_generic, detail, 0, true)
    }

    fun of(context: Context, code: String, title: Int, message: Int, detail: String, http: Int,
            retry: Boolean): Description {
        return Description(code, context.getString(title), context.getString(message), detail, http, retry)
    }

    /** Media3 can wrap the HTTP failure several causes deep. */
    private fun httpStatus(exception: PlaybackException): Int {
        var cause: Throwable? = exception.cause
        var depth = 0
        while (cause != null && depth < 12) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            depth++
            cause = cause.cause
        }
        return 0
    }

    private fun certificateFailure(error: Throwable?): Boolean {
        var error = error
        var depth = 0
        while (error != null && depth < 12) {
            if (error is CertificateException || error is CertPathValidatorException
                    || error is SSLPeerUnverifiedException) return true
            depth++
            error = error.cause
        }
        return false
    }
}
