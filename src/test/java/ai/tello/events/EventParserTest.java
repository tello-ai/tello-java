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
    void parsesAnswerAcceptedWithRequestCorrelation() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"answer.accepted\",\"version\":\"1.0\",\"requestId\":\"answer-1\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"messageId\":\"message-1\",\"timestamp\":\"t\"}"));
        AnswerAcceptedEvent accepted = assertInstanceOf(AnswerAcceptedEvent.class, e);
        assertEquals("answer-1", accepted.requestId);
        assertEquals("message-1", accepted.messageId);
        assertEquals("c1", accepted.callId);
    }

    @Test
    void parsesDtmfAcceptedWithDigitsAndRequestCorrelation() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"dtmf.accepted\",\"version\":\"1.0\",\"requestId\":\"dtmf-1\",\"sessionId\":\"s1\",\"callId\":\"c1\",\"messageId\":\"message-1\",\"digits\":\"1234#\",\"timestamp\":\"t\"}"));
        DtmfAcceptedEvent accepted = assertInstanceOf(DtmfAcceptedEvent.class, e);
        assertEquals("dtmf-1", accepted.requestId);
        assertEquals("message-1", accepted.messageId);
        assertEquals("1234#", accepted.digits);
        assertEquals("c1", accepted.callId);
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
    void parsesAgentsListed() {
        TelloEvent e = EventParser.parse(obj(
                "{\"type\":\"agents.listed\",\"version\":\"1.0\",\"requestId\":\"agents-1\",\"agents\":[{\"agentId\":\"agent-1\",\"name\":\"예약 확인\",\"role\":\"AI 상담원\",\"isDefault\":true,\"status\":\"published\"}]}"));
        AgentsListedEvent listed = assertInstanceOf(AgentsListedEvent.class, e);
        assertEquals("agents-1", listed.requestId);
        assertEquals(1, listed.agents.size());
        AgentInfo agent = listed.agents.get(0);
        assertEquals("agent-1", agent.agentId);
        assertEquals("예약 확인", agent.name);
        assertEquals("AI 상담원", agent.role);
        assertTrue(agent.isDefault);
        assertEquals("published", agent.status);
    }

    @Test
    void parsesCallSummaryAndSmsSent() {
        TelloEvent summaryEvent = EventParser.parse(obj(
                "{\"type\":\"call.summary\",\"version\":\"1.0\",\"requestId\":\"summary-1\",\"callId\":\"call-1\",\"status\":\"completed\",\"durationSeconds\":42,\"transcript\":\"고객: 예약 확인\",\"summary\":\"예약 확인 완료\",\"creditCharged\":15}"));
        CallSummaryEvent summary = assertInstanceOf(CallSummaryEvent.class, summaryEvent);
        assertEquals("summary-1", summary.requestId);
        assertEquals("call-1", summary.callId);
        assertEquals(42, summary.durationSeconds);
        assertEquals(15, summary.creditCharged);

        TelloEvent smsEvent = EventParser.parse(obj(
                "{\"type\":\"sms.sent\",\"version\":\"1.0\",\"requestId\":\"sms-1\",\"smsId\":\"77\",\"status\":\"queued\",\"to\":\"01012345678\",\"messagePreview\":\"예약 확인\",\"callId\":\"call-1\"}"));
        SmsSentEvent sms = assertInstanceOf(SmsSentEvent.class, smsEvent);
        assertEquals("sms-1", sms.requestId);
        assertEquals("77", sms.smsId);
        assertEquals("queued", sms.status);
        assertEquals("call-1", sms.callId);
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
