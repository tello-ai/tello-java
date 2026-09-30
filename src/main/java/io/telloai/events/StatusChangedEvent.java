package io.telloai.events;

import com.google.gson.JsonObject;

/** {@code call.statusChanged} (also carries the {@code cancelled} terminal status). */
public class StatusChangedEvent extends Event {

    public final String status;
    public final String previousStatus;

    public StatusChangedEvent(String type, String version, String sessionId, String callId, String timestamp,
                              JsonObject raw, String status, String previousStatus) {
        super(type, version, sessionId, callId, timestamp, raw);
        this.status = status;
        this.previousStatus = previousStatus;
    }
}
