// Live integration example: places one real call, retrieves its summary, then sends one real SMS.
// Not part of the Gradle build.

import ai.tello.EventType;
import ai.tello.TelloClient;
import ai.tello.events.AnswerAcceptedEvent;
import ai.tello.events.CallSummaryEvent;
import ai.tello.events.ErrorEvent;
import ai.tello.events.Event;
import ai.tello.events.SmsSentEvent;
import ai.tello.events.StatusChangedEvent;
import ai.tello.events.TerminalEvent;
import ai.tello.events.TurnEvent;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CallSummarySms {

    private static final long RESPONSE_TIMEOUT_SECONDS = 30;

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnvironment();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> createdCallId = new AtomicReference<>();
        AtomicReference<String> completedCallId = new AtomicReference<>();
        AtomicReference<CallSummaryEvent> callSummary = new AtomicReference<>();
        AnswerDeliveryTracker answers = new AnswerDeliveryTracker();
        CountDownLatch callCreated = new CountDownLatch(1);
        CountDownLatch callTerminal = new CountDownLatch(1);
        CountDownLatch summaryResponse = new CountDownLatch(1);
        CountDownLatch smsResponse = new CountDownLatch(1);
        String summaryRequestId = "live-summary-" + UUID.randomUUID();
        String smsRequestId = "live-summary-sms-" + UUID.randomUUID();

        try (TelloClient client = new TelloClient(config.apiKey, config.url).connectBlocking()) {
            client.on(EventType.USER_TURN, event -> {
                TurnEvent turn = (TurnEvent) event;
                System.out.println("[user #" + turn.turnIndex + "] " + turn.text);
                String callId = createdCallId.get();
                if (callId == null || !callId.equals(turn.callId)) {
                    failAll(failure, "user.turn arrived before matching call.created",
                            callCreated, callTerminal, summaryResponse, smsResponse);
                    return;
                }
                String answerRequestId = "live-answer-" + UUID.randomUUID();
                answers.answerSent(answerRequestId);
                // Do not retry: each answer becomes audible to the caller.
                client.answer(config.reply, null, answerRequestId).exceptionally(error -> {
                    failAll(failure, "could not send answer: " + error.getMessage(),
                            callCreated, callTerminal, summaryResponse, smsResponse);
                    return null;
                });
            });
            client.on(EventType.CALL_CREATED, event -> {
                Event created = (Event) event;
                if (created.callId == null || created.callId.isBlank()) {
                    failAll(failure, "call.created did not include callId",
                            callCreated, callTerminal, summaryResponse, smsResponse);
                    return;
                }
                createdCallId.set(created.callId);
                callCreated.countDown();
                System.out.println("[call.created] " + created.callId);
            });
            client.on(EventType.ANSWER_ACCEPTED, event -> {
                AnswerAcceptedEvent accepted = (AnswerAcceptedEvent) event;
                String callId = createdCallId.get();
                if (callId != null && callId.equals(accepted.callId)
                        && accepted.requestId != null && answers.answerAccepted(accepted.requestId)) {
                    System.out.println("[answer.accepted] requestId=" + accepted.requestId);
                }
            });
            client.on(EventType.AGENT_TURN, event -> {
                TurnEvent turn = (TurnEvent) event;
                if (!turn.callId.equals(createdCallId.get())) {
                    failAll(failure, "agent.turn did not match call.created",
                            callCreated, callTerminal, summaryResponse, smsResponse);
                    return;
                }
                if (!answers.agentTurnObserved()) {
                    failAll(failure, "agent.turn arrived before its corresponding answer.accepted",
                            callCreated, callTerminal, summaryResponse, smsResponse);
                    return;
                }
                System.out.println("[agent #" + turn.turnIndex + "] " + turn.text);
            });
            client.on(EventType.CALL_COMPLETED, event -> {
                Event completed = (Event) event;
                if (completed.callId == null || !completed.callId.equals(createdCallId.get())) {
                    failAll(failure, "call.completed did not match call.created",
                            callCreated, callTerminal, summaryResponse, smsResponse);
                    return;
                }
                if (!answers.hasAcceptedAnswer() || !answers.allAnswersDelivered()) {
                    failAll(failure, "call.completed arrived before all answer.accepted events and agent.turn were observed",
                            callCreated, callTerminal, summaryResponse, smsResponse);
                    return;
                }
                completedCallId.set(completed.callId);
                callTerminal.countDown();
            });
            client.on(EventType.CALL_NO_ANSWER, event -> failTerminal(
                    failure, (TerminalEvent) event, callCreated, callTerminal, summaryResponse, smsResponse));
            client.on(EventType.CALL_FAILED, event -> failTerminal(
                    failure, (TerminalEvent) event, callCreated, callTerminal, summaryResponse, smsResponse));
            client.on(EventType.CALL_STATUS_CHANGED, event -> {
                StatusChangedEvent status = (StatusChangedEvent) event;
                if ("cancelled".equals(status.status)) {
                    failAll(failure, "call ended with cancelled status",
                            callCreated, callTerminal, summaryResponse, smsResponse);
                }
            });
            client.on(EventType.DISCONNECTED, event ->
                    failAll(failure, "WebSocket disconnected before the scenario completed",
                            callCreated, callTerminal, summaryResponse, smsResponse));
            client.on(EventType.ERROR, event -> {
                ErrorEvent error = (ErrorEvent) event;
                failAll(failure, "gateway error " + error.code + ": " + error.message,
                        callCreated, callTerminal, summaryResponse, smsResponse);
            });
            client.on(EventType.CALL_SUMMARY, event -> {
                CallSummaryEvent summary = (CallSummaryEvent) event;
                if (!summaryRequestId.equals(summary.requestId)) {
                    return;
                }
                if (!completedCallId.get().equals(summary.callId)) {
                    failAll(failure, "call.summary callId did not match the completed call",
                            callCreated, callTerminal, summaryResponse, smsResponse);
                } else {
                    callSummary.set(summary);
                }
                summaryResponse.countDown();
            });
            client.on(EventType.SMS_SENT, event -> {
                SmsSentEvent sms = (SmsSentEvent) event;
                if (!smsRequestId.equals(sms.requestId)) {
                    return;
                }
                if (!hasSmsId(sms)) {
                    failAll(failure, "sms.sent did not include smsId",
                            callCreated, callTerminal, summaryResponse, smsResponse);
                } else {
                    System.out.println("[sms.sent] status=" + sms.status + " smsId=" + sms.smsId);
                }
                smsResponse.countDown();
            });

            client.createCall(config.callTo, config.agentId, config.prompt).join();
            if (!callCreated.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                cancelAfterTimeout(client, "call.created");
                throw new IllegalStateException("timed out waiting for call.created");
            }
            throwIfFailed(failure);
            if (!callTerminal.await(config.callTimeoutSeconds, TimeUnit.SECONDS)) {
                cancelAfterTimeout(client, "call.completed");
                throw new IllegalStateException("timed out waiting for call.completed");
            }
            throwIfFailed(failure);

            String callId = completedCallId.get();
            if (callId == null) {
                throw new IllegalStateException("call did not complete successfully");
            }
            client.getSummary(callId, summaryRequestId).join();
            if (!summaryResponse.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for call.summary");
            }
            throwIfFailed(failure);
            if (callSummary.get() == null) {
                throw new IllegalStateException("call.summary was not received");
            }

            // Do not retry: this scenario sends exactly one real SMS after a completed call.
            client.sendSms(config.smsTo, config.message, smsRequestId).join();
            if (!smsResponse.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for sms.sent");
            }
            throwIfFailed(failure);
            System.out.println("Completed call " + callId + ", retrieved summary, and sent SMS to " + config.smsTo);
        }
    }

    private static void failTerminal(AtomicReference<String> failure, TerminalEvent event,
                                     CountDownLatch... latches) {
        failAll(failure, event.type + ": " + event.failureReason, latches);
    }

    private static void failAll(AtomicReference<String> failure, String message, CountDownLatch... latches) {
        failure.compareAndSet(null, message);
        for (CountDownLatch latch : latches) {
            latch.countDown();
        }
    }

    private static void cancelAfterTimeout(TelloClient client, String expectedEvent) {
        try {
            client.cancel().join();
            System.err.println("Timed out waiting for " + expectedEvent + "; cancel was requested.");
        } catch (RuntimeException cancelFailure) {
            System.err.println("Timed out waiting for " + expectedEvent
                    + "; cancel attempt failed: " + cancelFailure.getMessage());
        }
    }

    private static void throwIfFailed(AtomicReference<String> failure) {
        if (failure.get() != null) {
            throw new IllegalStateException(failure.get());
        }
    }

    private record Config(String apiKey, String url, String agentId, String callTo, String smsTo,
                          String message, String reply, String prompt, long callTimeoutSeconds) {
        static Config fromEnvironment() {
            requireLiveSideEffects();
            return new Config(
                    required("TELLO_API_KEY"),
                    requiredWebSocketUrl("TELLO_URL"),
                    required("TELLO_AGENT_ID"),
                    required("LIVE_CALL_TO"),
                    required("LIVE_SMS_TO"),
                    optional("LIVE_SMS_MESSAGE", "[Tello live test] Call summary was retrieved."),
                    optional("LIVE_CALL_REPLY", "확인했습니다. 라이브 테스트 통화를 계속 진행하겠습니다."),
                    optional("LIVE_CALL_PROMPT", "Live test call. Reply to each caller turn."),
                    positiveSeconds("LIVE_CALL_TIMEOUT_SECONDS"));
        }
    }

    private static void requireLiveSideEffects() {
        if (!"true".equals(System.getenv("ALLOW_LIVE_SIDE_EFFECTS"))) {
            throw new IllegalStateException(
                    "Refusing to place a real call or send a real SMS: set ALLOW_LIVE_SIDE_EFFECTS=true explicitly.");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static String requiredWebSocketUrl(String name) {
        String value = required(name);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be an absolute ws:// or wss:// URL", e);
        }
        if (uri.getHost() == null || (!"ws".equals(uri.getScheme()) && !"wss".equals(uri.getScheme()))) {
            throw new IllegalArgumentException(name + " must be an absolute ws:// or wss:// URL");
        }
        return value;
    }

    private static long positiveSeconds(String name) {
        String value = required(name);
        try {
            long seconds = Long.parseLong(value);
            if (seconds <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return seconds;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer", e);
        }
    }

    private static String optional(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static boolean hasSmsId(SmsSentEvent sms) {
        return sms.smsId != null && !sms.smsId.isBlank();
    }

    static final class AnswerDeliveryTracker {

        private final Set<String> pendingRequestIds = ConcurrentHashMap.newKeySet();
        private final AtomicInteger acceptedAwaitingAgentTurns = new AtomicInteger();
        private final AtomicInteger acceptedAnswerCount = new AtomicInteger();

        void answerSent(String requestId) {
            pendingRequestIds.add(requestId);
        }

        boolean answerAccepted(String requestId) {
            if (!pendingRequestIds.remove(requestId)) {
                return false;
            }
            acceptedAnswerCount.incrementAndGet();
            acceptedAwaitingAgentTurns.incrementAndGet();
            return true;
        }

        boolean agentTurnObserved() {
            while (true) {
                int remaining = acceptedAwaitingAgentTurns.get();
                if (remaining == 0) {
                    return false;
                }
                if (acceptedAwaitingAgentTurns.compareAndSet(remaining, remaining - 1)) {
                    return true;
                }
            }
        }

        boolean hasAcceptedAnswer() {
            return acceptedAnswerCount.get() > 0;
        }

        boolean allAnswersDelivered() {
            return pendingRequestIds.isEmpty() && acceptedAwaitingAgentTurns.get() == 0;
        }
    }
}
