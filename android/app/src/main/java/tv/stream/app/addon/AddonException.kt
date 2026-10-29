package tv.stream.app.addon

import java.io.IOException

/** Why an add-on request failed, so a screen can say so and keep other add-ons' results. */
class AddonException(@JvmField val kind: Kind, message: String?, @JvmField val httpStatus: Int, cause: Throwable?) : IOException(message, cause) {
    enum class Kind {
        /** No connection, or the connection broke. */
        NETWORK,
        /** The server's certificate could not be verified; never retried without verification. */
        CERTIFICATE,
        TIMEOUT,
        /** The add-on answered with an HTTP error. */
        HTTP,
        /** Not the JSON the protocol describes, or larger than allowed. */
        MALFORMED,
        CANCELLED,
        /** The address isn't an http(s) add-on URL. */
        INVALID_URL,
    }

    constructor(kind: Kind, message: String?) : this(kind, message, 0, null)
}
