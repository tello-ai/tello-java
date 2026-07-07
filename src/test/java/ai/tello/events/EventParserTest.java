package ai.tello.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class EventParserTest {

    private JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void parsesUserTurn() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"user.turn\",\"version\":\"1.0\",\"call_id\":\"c1\",\"turn_index\":2,\"text\":\"hey\",\"timestamp\":\"t\"}"));
        TurnEvent t = assertInstanceOf(TurnEvent.class, e);
        assertEquals(2, t.turnIndex);
        assertEquals("hey", t.text);
        assertEquals("c1", t.callId);
    }

    @Test
    void parsesFlatErrorWithRequestId() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"error\",\"version\":\"1.0\",\"code\":\"no_active_call\",\"message\":\"No active call\",\"request_id\":\"r1\"}"));
        ErrorEvent err = assertInstanceOf(ErrorEvent.class, e);
        assertEquals("no_active_call", err.code);
        assertEquals("r1", err.requestId);
    }

    @Test
    void detectsTerminalEvents() {
        assertTrue(EventParser.isTerminal(EventParser.parse(obj(
                "{\"type\":\"call.completed\",\"version\":\"1.0\",\"call_id\":\"c1\",\"status\":\"completed\",\"timestamp\":\"t\"}"))));
        assertTrue(EventParser.isTerminal(EventParser.parse(obj(
                "{\"type\":\"call.status_changed\",\"version\":\"1.0\",\"call_id\":\"c1\",\"status\":\"cancelled\",\"previous_status\":\"in_progress\",\"timestamp\":\"t\"}"))));
        assertFalse(EventParser.isTerminal(EventParser.parse(obj(
                "{\"type\":\"call.status_changed\",\"version\":\"1.0\",\"call_id\":\"c1\",\"status\":\"in_progress\",\"previous_status\":\"queued\",\"timestamp\":\"t\"}"))));
    }

    @Test
    void tolerantOfUnexpectedFieldTypes() {
        // turn_index as a string and text as an object must not throw.
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"user.turn\",\"version\":\"1.0\",\"call_id\":\"c1\",\"turn_index\":\"oops\",\"text\":{\"nested\":1},\"timestamp\":\"t\"}"));
        TurnEvent t = assertInstanceOf(TurnEvent.class, e);
        assertEquals(0, t.turnIndex);
        assertEquals("", t.text);
    }

    @Test
    void unknownTypeFallsBackToBaseEvent() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"future.thing\",\"version\":\"1.0\",\"call_id\":\"c1\",\"timestamp\":\"t\"}"));
        assertEquals("future.thing", e.type());
        assertInstanceOf(Event.class, e);
    }
}
