package tv.daniil.stream.addon;

import java.io.IOException;

/** Why an add-on request failed, so a screen can say so and keep other add-ons' results. */
public final class AddonException extends IOException {
    public enum Kind {
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

    public final Kind kind;
    public final int httpStatus;

    public AddonException(Kind kind, String message) {
        this(kind, message, 0, null);
    }

    public AddonException(Kind kind, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }
}
