package io.telloai.events;

import com.google.gson.JsonObject;

/** {@code call.summary}, emitted in response to {@code getSummary}. */
public class CallSummaryEvent extends Event {

    public final String requestId;
    public final String status;
    public final Integer durationSeconds;
    public final String transcript;
    public final String summary;
    public final Integer creditCharged;

    public CallSummaryEvent(String type, String version, String requestId, String callId,
                            String status, Integer durationSeconds, String transcript,
                            String summary, Integer creditCharged, JsonObject raw) {
        super(type, version, "", callId, "", raw);
        this.requestId = requestId;
        this.status = status;
        this.durationSeconds = durationSeconds;
        this.transcript = transcript;
        this.summary = summary;
        this.creditCharged = creditCharged;
    }
}
