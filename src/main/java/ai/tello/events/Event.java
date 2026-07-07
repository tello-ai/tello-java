package ai.tello.events;

import com.google.gson.JsonObject;

/**
 * Base inbound event. {@link #raw} holds the original decoded frame so
 * forward-compatible fields remain accessible.
 */
public class Event implements TelloEvent {

    public final String type;
    public final String version;
    public final String callId;
    public final String timestamp;
    public final JsonObject raw;

    public Event(String type, String version, String callId, String timestamp, JsonObject raw) {
        this.type = type;
        this.version = version;
        this.callId = callId;
        this.timestamp = timestamp;
        this.raw = raw;
    }

    @Override
    public String type() {
        return type;
    }
}
