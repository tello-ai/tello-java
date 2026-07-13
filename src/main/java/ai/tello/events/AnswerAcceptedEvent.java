package ai.tello.events;

import com.google.gson.JsonObject;

/** {@code answer.accepted}, correlated to an {@code answer} command by request ID. */
public class AnswerAcceptedEvent extends Event {

    /** Nullable when the command did not include a request ID. */
    public final String requestId;
    public final String messageId;

    public AnswerAcceptedEvent(String type, String version, String requestId, String sessionId,
                               String callId, String messageId, String timestamp, JsonObject raw) {
        super(type, version, sessionId, callId, timestamp, raw);
        this.requestId = requestId;
        this.messageId = messageId;
    }
}
