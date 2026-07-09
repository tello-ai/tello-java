package ai.tello.events;

import com.google.gson.JsonObject;

/** {@code call.completed} / {@code call.noAnswer} / {@code call.failed}. */
public class TerminalEvent extends Event {

    public final String status;
    /** Nullable; present on {@code call.noAnswer} / {@code call.failed}. */
    public final String failureReason;

    public TerminalEvent(String type, String version, String sessionId, String callId, String timestamp,
                         JsonObject raw, String status, String failureReason) {
        super(type, version, sessionId, callId, timestamp, raw);
        this.status = status;
        this.failureReason = failureReason;
    }
}
