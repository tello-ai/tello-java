package io.telloai.events;

import com.google.gson.JsonObject;

/** {@code user.turn} / {@code agent.turn}. */
public class TurnEvent extends Event {

    public final int turnIndex;
    public final String text;

    public TurnEvent(String type, String version, String sessionId, String callId, String timestamp,
                     JsonObject raw, int turnIndex, String text) {
        super(type, version, sessionId, callId, timestamp, raw);
        this.turnIndex = turnIndex;
        this.text = text;
    }
}
