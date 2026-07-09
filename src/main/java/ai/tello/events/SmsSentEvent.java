package ai.tello.events;

import com.google.gson.JsonObject;

/** {@code sms.sent}, emitted in response to {@code sendSms}. */
public class SmsSentEvent extends Event {

    public final String requestId;
    public final String smsId;
    public final String status;
    public final String to;
    public final String messagePreview;

    public SmsSentEvent(String type, String version, String requestId, String smsId,
                        String status, String to, String messagePreview, String callId,
                        JsonObject raw) {
        super(type, version, "", callId == null ? "" : callId, "", raw);
        this.requestId = requestId;
        this.smsId = smsId;
        this.status = status;
        this.to = to;
        this.messagePreview = messagePreview;
    }
}
