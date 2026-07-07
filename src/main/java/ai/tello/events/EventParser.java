package ai.tello.events;

import ai.tello.EventType;
import com.google.gson.JsonObject;
import java.util.Set;

/**
 * Parses inbound flat frames (no {@code {event,data}} envelope) into typed events,
 * dispatched on the {@code type} field. See the WS protocol contract.
 */
public final class EventParser {

    private static final Set<String> TERMINAL_TYPES =
            Set.of(EventType.CALL_COMPLETED, EventType.CALL_NO_ANSWER, EventType.CALL_FAILED);

    private EventParser() {}

    public static TelloEvent parse(JsonObject frame) {
        String type = str(frame, "type", "");

        if (EventType.ERROR.equals(type)) {
            return new ErrorEvent(
                    type,
                    str(frame, "version", ""),
                    str(frame, "code", ""),
                    str(frame, "message", ""),
                    strOrNull(frame, "request_id"),
                    strOrNull(frame, "question"),
                    frame);
        }

        String version = str(frame, "version", "");
        String callId = str(frame, "call_id", "");
        String timestamp = str(frame, "timestamp", "");

        if (EventType.USER_TURN.equals(type) || EventType.AGENT_TURN.equals(type)) {
            return new TurnEvent(type, version, callId, timestamp, frame,
                    intVal(frame, "turn_index", 0), str(frame, "text", ""));
        }

        if (EventType.CALL_STATUS_CHANGED.equals(type)) {
            return new StatusChangedEvent(type, version, callId, timestamp, frame,
                    str(frame, "status", ""), str(frame, "previous_status", ""));
        }

        if (TERMINAL_TYPES.contains(type)) {
            return new TerminalEvent(type, version, callId, timestamp, frame,
                    str(frame, "status", ""), strOrNull(frame, "failure_reason"));
        }

        return new Event(type, version, callId, timestamp, frame);
    }

    /**
     * True if {@code event} ends the current call: the three {@code call.*} terminals
     * plus a {@code call.status_changed} with status {@code cancelled}.
     */
    public static boolean isTerminal(TelloEvent event) {
        if (TERMINAL_TYPES.contains(event.type())) {
            return true;
        }
        return event instanceof StatusChangedEvent sc && "cancelled".equals(sc.status);
    }

    // Tolerant accessors: a field of an unexpected JSON type falls back rather than
    // throwing, mirroring Python's dict.get(...) forward-compatibility.

    private static String str(JsonObject o, String key, String fallback) {
        String v = strOrNull(o, key);
        return v != null ? v : fallback;
    }

    private static String strOrNull(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static int intVal(JsonObject o, String key, int fallback) {
        if (o.has(key) && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isNumber()) {
            return o.get(key).getAsInt();
        }
        return fallback;
    }
}
