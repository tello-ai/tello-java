package ai.tello.events;

import com.google.gson.JsonObject;

/** {@code call.completed} / {@code call.no_answer} / {@code call.failed}. */
public class TerminalEvent extends Event {

    public final String status;
    /** Nullable; present on {@code call.no_answer} / {@code call.failed}. */
    public final String failureReason;

    public TerminalEvent(String type, String version, String callId, String timestamp, JsonObject raw,
                         String status, String failureReason) {
        super(type, version, callId, timestamp, raw);
        this.status = status;
        this.failureReason = failureReason;
    }
}
