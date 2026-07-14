package ai.tello.events;

import com.google.gson.JsonObject;

/** {@code dtmf.accepted}, correlated to a {@code sendDtmf} command by request ID. */
public class DtmfAcceptedEvent extends Event {

    /** Nullable when the command did not include a request ID. */
    public final String requestId;
    public final String messageId;
    public final String digits;

    public DtmfAcceptedEvent(String type, String version, String requestId, String sessionId,
                             String callId, String messageId, String digits, String timestamp,
                             JsonObject raw) {
        super(type, version, sessionId, callId, timestamp, raw);
        this.requestId = requestId;
        this.messageId = messageId;
        this.digits = digits;
    }
}
