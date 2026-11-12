package io.telloai.errors;

import com.google.gson.JsonObject;

/**
 * The REST API answered with an error, or with a response this SDK cannot use.
 *
 * <p>Branch on {@link #code} and {@link #status}, not on the message. {@code code} is the
 * server's {@code error} field as sent (snake_case, e.g. {@code tool_not_found}), so it
 * matches the error table of the public docs. A response that is not the API's error
 * envelope (e.g. a proxy page) has {@code code = "http_<status>"}; a success status the
 * API does not document for the request, or a success body that is not a JSON object,
 * has {@code code = "invalid_response"}.
 */
public class TelloApiException extends TelloException {

    /** The HTTP status of the response. */
    public final int status;

    /** The envelope's {@code data} (e.g. {@code missingVariables}), or null. */
    public final JsonObject data;

    /**
     * The {@code Idempotency-Key} the request carried, or null. Retry with the same key so
     * the server replays the stored result instead of acting twice.
     */
    public final String idempotencyKey;

    public TelloApiException(int status, String code, String message, JsonObject data, String idempotencyKey) {
        super(message, code);
        this.status = status;
        this.data = data;
        this.idempotencyKey = idempotencyKey;
    }
}
