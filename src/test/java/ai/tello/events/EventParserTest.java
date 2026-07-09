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
                "{\"type\":\"user.turn\",\"version\":\"1.0\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"turnIndex\":2,\"text\":\"hey\",\"timestamp\":\"t\"}"));
        TurnEvent t = assertInstanceOf(TurnEvent.class, e);
        assertEquals(2, t.turnIndex);
        assertEquals("hey", t.text);
        assertEquals("c1", t.callId);
        assertEquals("s1", t.sessionId);
    }

    @Test
    void parsesFlatErrorWithRequestId() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"error\",\"version\":\"1.0\",\"code\":\"noActiveCall\",\"message\":\"No active call\",\"requestId\":\"r1\"}"));
        ErrorEvent err = assertInstanceOf(ErrorEvent.class, e);
        assertEquals("noActiveCall", err.code);
        assertEquals("r1", err.requestId);
    }

    @Test
    void parsesStatusChangedWithPreviousStatus() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"call.statusChanged\",\"version\":\"1.0\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"status\":\"inProgress\",\"previousStatus\":\"ringing\",\"timestamp\":\"t\"}"));
        StatusChangedEvent sc = assertInstanceOf(StatusChangedEvent.class, e);
        assertEquals("inProgress", sc.status);
        assertEquals("ringing", sc.previousStatus);
    }

    @Test
    void parsesNoAnswerTerminalWithFailureReason() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"call.noAnswer\",\"version\":\"1.0\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"status\":\"noAnswer\",\"failureReason\":\"timeout\",\"timestamp\":\"t\"}"));
        TerminalEvent term = assertInstanceOf(TerminalEvent.class, e);
        assertEquals("noAnswer", term.status);
        assertEquals("timeout", term.failureReason);
        assertTrue(EventParser.isTerminal(term));
    }

    @Test
    void detectsTerminalEvents() {
        assertTrue(EventParser.isTerminal(EventParser.parse(obj(
                "{\"type\":\"call.completed\",\"version\":\"1.0\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"status\":\"completed\",\"timestamp\":\"t\"}"))));
        assertTrue(EventParser.isTerminal(EventParser.parse(obj(
                "{\"type\":\"call.statusChanged\",\"version\":\"1.0\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"status\":\"cancelled\",\"previousStatus\":\"inProgress\",\"timestamp\":\"t\"}"))));
        assertFalse(EventParser.isTerminal(EventParser.parse(obj(
                "{\"type\":\"call.statusChanged\",\"version\":\"1.0\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"status\":\"inProgress\",\"previousStatus\":\"queued\",\"timestamp\":\"t\"}"))));
    }

    @Test
    void tolerantOfUnexpectedFieldTypes() {
        // turnIndex as a string and text as an object must not throw.
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"user.turn\",\"version\":\"1.0\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"turnIndex\":\"oops\",\"text\":{\"nested\":1},\"timestamp\":\"t\"}"));
        TurnEvent t = assertInstanceOf(TurnEvent.class, e);
        assertEquals(0, t.turnIndex);
        assertEquals("", t.text);
    }

    @Test
    void unknownTypeFallsBackToBaseEvent() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"future.thing\",\"version\":\"1.0\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"timestamp\":\"t\"}"));
        assertEquals("future.thing", e.type());
        assertInstanceOf(Event.class, e);
    }
}
