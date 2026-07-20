// Standalone regression test for the live example; intentionally outside Gradle's source sets.

public class CallSummaryTest {

    public static void main(String[] args) {
        requiresAnAcceptedAnswerBeforeEachAgentTurn();
        terminalCannotPassUntilEveryAnswerIsAcknowledgedThenObserved();
        System.out.println("CallSummaryTest=ok");
    }

    private static void requiresAnAcceptedAnswerBeforeEachAgentTurn() {
        CallSummary.AnswerDeliveryTracker tracker = new CallSummary.AnswerDeliveryTracker();
        tracker.answerSent("answer-1");

        check(!tracker.agentTurnObserved(), "agent turn must require a prior ack");
        check(tracker.answerAccepted("answer-1"), "first ack should be accepted");
        check(tracker.agentTurnObserved(), "agent turn after first ack should be accepted");
        check(tracker.allAnswersDelivered(), "first answer should be complete");
    }

    private static void terminalCannotPassUntilEveryAnswerIsAcknowledgedThenObserved() {
        CallSummary.AnswerDeliveryTracker tracker = new CallSummary.AnswerDeliveryTracker();
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
