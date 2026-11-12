package io.telloai.errors;

/**
 * A REST request got no response: the connection failed, broke, or timed out. The
 * transport error is the {@linkplain #getCause() cause}.
 *
 * <p><b>A write may already have been applied.</b> The server may have received and
 * carried out a create, update, delete, crew generation or call draft request before
 * the response was lost. Check with get or list before retrying, and retry with the same
 * {@link #idempotencyKey} where the method takes one. A lost call draft confirm may have
 * placed a real call: retry it only with the same key.
 */
public class TelloApiConnectionException extends TelloException {

    /** The {@code Idempotency-Key} the request carried, or null. */
    public final String idempotencyKey;

    public TelloApiConnectionException(String message, String idempotencyKey, Throwable cause) {
        super(message);
        this.idempotencyKey = idempotencyKey;
        initCause(cause);
    }
}
