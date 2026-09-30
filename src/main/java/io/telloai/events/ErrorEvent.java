package io.telloai.events;

import com.google.gson.JsonObject;

/** A gateway {@code error} frame (flat, not enveloped). */
public class ErrorEvent implements TelloEvent {

    public final String type;
    public final String version;
    public final String code;
    public final String message;
    /** Nullable; echoes the client's {@code requestId} on the failed command. */
    public final String requestId;
    /** Nullable; present for {@code callRejected}. */
    public final String question;
    public final JsonObject raw;

    public ErrorEvent(String type, String version, String code, String message,
                      String requestId, String question, JsonObject raw) {
        this.type = type;
        this.version = version;
        this.code = code;
        this.message = message;
        this.requestId = requestId;
        this.question = question;
        this.raw = raw;
    }

    @Override
    public String type() {
        return type;
    }
}
