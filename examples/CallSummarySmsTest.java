// Standalone regression test for the live example; intentionally outside Gradle's source sets.

import ai.tello.events.SmsSentEvent;
import com.google.gson.JsonObject;

public class CallSummarySmsTest {

    public static void main(String[] args) {
        requiresAnAcceptedAnswerBeforeEachAgentTurn();
        terminalCannotPassUntilEveryAnswerIsAcknowledgedThenObserved();
        acceptsQueuedSmsWhenTheGatewayAssignedAnId();
        System.out.println("CallSummarySmsTest=ok");
    }

    private static void requiresAnAcceptedAnswerBeforeEachAgentTurn() {
        CallSummarySms.AnswerDeliveryTracker tracker = new CallSummarySms.AnswerDeliveryTracker();
        tracker.answerSent("answer-1");

        check(!tracker.agentTurnObserved(), "agent turn must require a prior ack");
        check(tracker.answerAccepted("answer-1"), "first ack should be accepted");
        check(tracker.agentTurnObserved(), "agent turn after first ack should be accepted");
        check(tracker.allAnswersDelivered(), "first answer should be complete");
    }

    private static void terminalCannotPassUntilEveryAnswerIsAcknowledgedThenObserved() {
        CallSummarySms.AnswerDeliveryTracker tracker = new CallSummarySms.AnswerDeliveryTracker();
        tracker.answerSent("answer-1");
        tracker.answerSent("answer-2");

        check(tracker.answerAccepted("answer-1"), "first ack should be accepted");
        check(tracker.agentTurnObserved(), "first agent turn should be accepted");
        check(!tracker.allAnswersDelivered(), "second answer still needs ack then agent turn");
        check(tracker.answerAccepted("answer-2"), "second ack should be accepted");
        check(!tracker.allAnswersDelivered(), "second answer still needs an agent turn");
        check(tracker.agentTurnObserved(), "second agent turn should be accepted");
        check(tracker.allAnswersDelivered(), "all answers should be complete");
    }

    private static void acceptsQueuedSmsWhenTheGatewayAssignedAnId() {
        SmsSentEvent queued = new SmsSentEvent(
                "sms.sent", "1.0", "sms-1", "sms-id-1", "queued", "+821012345678",
                "[Tello live test]", null, new JsonObject());
        SmsSentEvent withoutId = new SmsSentEvent(
                "sms.sent", "1.0", "sms-2", "", "sent", "+821012345678",
                "[Tello live test]", null, new JsonObject());

        check(SendSms.hasSmsId(queued), "queued SMS with an ID should be accepted");
        check(CallSummarySms.hasSmsId(queued), "combined flow should accept queued SMS with an ID");
        check(!SendSms.hasSmsId(withoutId), "SMS without an ID should fail");
        check(!CallSummarySms.hasSmsId(withoutId), "combined SMS without an ID should fail");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
