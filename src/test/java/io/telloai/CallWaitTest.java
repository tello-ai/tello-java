package io.telloai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.telloai.errors.CallAlreadyActiveException;
import io.telloai.errors.CallRefusedException;
import io.telloai.errors.ConnectionClosedException;
import io.telloai.errors.TelloServerException;
import io.telloai.events.ErrorEvent;
import io.telloai.events.TelloEvent;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TelloClient#waitClosed()} against a {@link FakeGateway} that answers commands
 * the way the gateway does: an error frame echoes the failed command's
 * {@code requestId}, and carries none when the command had none. A wait ends with
 * the call it began in: at that call's terminal event, at the connection's close, or
 * at an error answering one of that call's {@code createCall}s, except
 * {@code noActiveCall} and a {@code callAlreadyActive} for a {@code createCall} sent
 * during the call. An error for any other command leaves the call running and only
 * reaches the error handlers.
 */
class CallWaitTest {

    private static final String AUTH_OK = "{\"type\":\"auth.ok\",\"version\":\"1.0\"}";
    private static final String CALL_CREATED = "{\"type\":\"call.created\",\"version\":\"1.0\","
            + "\"sessionId\":\"s1\",\"callId\":\"c1\",\"timestamp\":\"t\"}";
    private static final String CALL_COMPLETED = "{\"type\":\"call.completed\",\"version\":\"1.0\","
            + "\"sessionId\":\"s1\",\"callId\":\"c1\",\"timestamp\":\"t\",\"status\":\"completed\"}";
    /** The gateway's terminal event for a cancelled call (contract §4.4). */
    private static final String CALL_CANCELLED = "{\"type\":\"call.statusChanged\",\"version\":\"1.0\","
            + "\"sessionId\":\"s1\",\"callId\":\"c1\",\"timestamp\":\"t\","
            + "\"status\":\"cancelled\",\"previousStatus\":\"inProgress\"}";

    private FakeGateway server;
    private TelloClient client;

    @BeforeEach
    void connect() throws Exception {
        server = FakeGateway.start();
        client = new TelloClient("tello_test_key", server.url());
        connectClient();
    }

    @AfterEach
    void disconnect() {
        // Drop the socket first: the fake never answers a close frame, so closing the
        // client first would sit out the client's whole close wait.
        server.close();
        client.close();
    }

    @Test
    void otherCommandErrorsDoNotEndTheWait() throws Exception {
        BlockingQueue<ErrorEvent> errors = new LinkedBlockingQueue<>();
        client.on(EventType.ERROR, e -> errors.add((ErrorEvent) e));
        client.createCall("+821012345678").join();
        nextCommand("createCall");
        server.sendText(CALL_CREATED);
        CompletableFuture<Void> wait = startWait();

        client.sendDtmf("12a", null, "dtmf-1").join();
        server.sendText(error("dtmfDigitsInvalid", requestIdOf(nextCommand("sendDtmf"))));
        ErrorEvent invalidDigits = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(invalidDigits, "dtmfDigitsInvalid never reached the error handler");
        assertEquals("dtmfDigitsInvalid", invalidDigits.code);
        assertEquals("dtmf-1", invalidDigits.requestId);
        assertStillWaiting(wait);

        // The answer carries no requestId, so its error carries none either.
        client.answer("hello").join();
        server.sendText(error("internalError", requestIdOf(nextCommand("answer"))));
        ErrorEvent answerFailed = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(answerFailed, "internalError never reached the error handler");
        assertEquals("internalError", answerFailed.code);
        assertNull(answerFailed.requestId);
        assertStillWaiting(wait);

        server.sendText(CALL_COMPLETED);
        wait.get(3, TimeUnit.SECONDS);
    }

    @Test
    void createCallRefusalEndsTheWait() throws Exception {
        client.createCall("+821012345678", "", null, "create-1").join();
        JsonObject createCall = nextCommand("createCall");
        CompletableFuture<Void> wait = startWait();

        // Refused before any call exists: no call.created, only this error.
        server.sendText(error("insufficientCredit", requestIdOf(createCall)));

        assertInstanceOf(CallRefusedException.class, waitFailure(wait));
    }

    @Test
    void createCallFailureAfterCallCreatedEndsTheWait() throws Exception {
        client.createCall("+821012345678").join();
        String createCallId = requestIdOf(nextCommand("createCall"));
        assertNotNull(createCallId, "createCall carried no requestId for the gateway to echo");
        server.sendText(CALL_CREATED);
        CompletableFuture<Void> wait = startWait();

        // A call stream failure after call.created: the gateway cancels the call and
        // sends only this error, echoing the createCall's requestId, with no terminal event.
        server.sendText(error("internalError", createCallId));

        assertInstanceOf(TelloServerException.class, waitFailure(wait));
    }

    @Test
    void createCallAlwaysCarriesRequestId() throws Exception {
        client.createCall("+821012345678").join();
        String generated = requestIdOf(nextCommand("createCall"));
        assertNotNull(generated, "createCall without a requestId sent none");
        assertFalse(generated.isEmpty());

        client.createCall("+821012345678", "", null, "").join();
        String generatedForEmpty = requestIdOf(nextCommand("createCall"));
        assertNotNull(generatedForEmpty, "createCall with an empty requestId sent none");
        assertFalse(generatedForEmpty.isEmpty());

        client.createCall("+821012345678", "", null, "caller-1").join();
        assertEquals("caller-1", requestIdOf(nextCommand("createCall")));
    }

    @Test
    void callAlreadyActiveForTheOpeningCreateCallEndsTheWait() throws Exception {
        client.createCall("+821012345678", "", null, "create-a").join();
        String opening = requestIdOf(nextCommand("createCall"));
        CompletableFuture<Void> wait = startWait();

        // The gateway still holds its previous call (contract §4.1), so this call
        // never starts: no call.created, only this refusal.
        server.sendText(error("callAlreadyActive", opening));
        assertInstanceOf(CallAlreadyActiveException.class, waitFailure(wait));

        // The retry opens a new call, and its terminal event ends a new wait.
        client.createCall("+821012345678", "", null, "create-b").join();
        nextCommand("createCall");
        CompletableFuture<Void> retryWait = startWait();
        server.sendText(CALL_COMPLETED);
        retryWait.get(3, TimeUnit.SECONDS);
    }

    @Test
    void callAlreadyActiveForACreateCallDuringTheCallLeavesTheWaitRunning() throws Exception {
        client.createCall("+821012345678", "", null, "create-a").join();
        String opening = requestIdOf(nextCommand("createCall"));
        server.sendText(CALL_CREATED);
        CompletableFuture<Void> wait = startWait();

        // A createCall sent during the live call is refused, and the call goes on.
        client.createCall("+821012345678", "", null, "create-b").join();
        server.sendText(error("callAlreadyActive", requestIdOf(nextCommand("createCall"))));

        // So the wait still belongs to the first call and ends with its failure.
        server.sendText(error("internalError", opening));
        assertInstanceOf(TelloServerException.class, waitFailure(wait));
    }

    @Test
    void aFollowUpCallFromATerminalHandlerDoesNotExtendTheWait() throws Exception {
        AtomicBoolean followedUp = new AtomicBoolean();
        client.on(EventType.CALL_COMPLETED, e -> {
            if (followedUp.compareAndSet(false, true)) {
                client.createCall("+821012345678", "", null, "create-b");
            }
        });
        client.createCall("+821012345678", "", null, "create-a").join();
        String first = requestIdOf(nextCommand("createCall"));
        server.sendText(CALL_CREATED);
        CompletableFuture<Void> firstWait = startWait();

        server.sendText(CALL_COMPLETED);
        firstWait.get(3, TimeUnit.SECONDS);

        // The handler's call is the current call now. A new wait follows it, and a
        // late error echoing the first call's createCall no longer ends anything.
        assertEquals("create-b", requestIdOf(nextCommand("createCall")));
        CompletableFuture<Void> followUpWait = startWait();
        server.sendText(error("internalError", first));
        client.cancel().join();
        nextCommand("cancel");
        server.sendText(CALL_CANCELLED);
        followUpWait.get(3, TimeUnit.SECONDS);
    }

    @Test
    void anErrorForACallFromBeforeAReconnectDoesNotEndTheNextCall() throws Exception {
        client.createCall("+821012345678", "", null, "create-a").join();
        String beforeDrop = requestIdOf(nextCommand("createCall"));
        CompletableFuture<Void> droppedWait = startWait();

        // Drop the live call's socket. No frame is in flight: java.net.http can miss an
        // EOF that lands right behind a text frame, which would leave the client
        // unaware of the drop.
        server.dropConnection();
        assertInstanceOf(ConnectionClosedException.class, waitFailure(droppedWait));

        connectClient();
        client.createCall("+821012345678", "", null, "create-c").join();
        nextCommand("createCall");
        server.sendText(CALL_CREATED);
        CompletableFuture<Void> wait = startWait();

        server.sendText(error("internalError", beforeDrop));
        server.sendText(CALL_COMPLETED);
        wait.get(3, TimeUnit.SECONDS);
    }

    @Test
    void aCreateCallThatCannotBeSentDoesNotLeaveTheWaitHanging() throws Exception {
        CompletableFuture<TelloEvent> disconnected = new CompletableFuture<>();
        client.on(EventType.DISCONNECTED, disconnected::complete);
        server.dropConnection();
        disconnected.get(3, TimeUnit.SECONDS);

        CompletableFuture<Void> sent = client.createCall("+821012345678");
        ExecutionException sendFailure = assertThrows(ExecutionException.class,
                () -> sent.get(3, TimeUnit.SECONDS));
        assertInstanceOf(ConnectionClosedException.class, sendFailure.getCause());

        assertInstanceOf(ConnectionClosedException.class, waitFailure(startWait()));
    }

    /** Connect (or reconnect) the client through the fake's auth handshake. */
    private void connectClient() throws Exception {
        CompletableFuture<TelloClient> connected = client.connect();
        nextCommand("auth");
        server.sendText(AUTH_OK);
        connected.get(3, TimeUnit.SECONDS);
    }

    /**
     * Run {@code waitClosed()} on its own thread and return once it is blocked in the
     * wait or has already returned, so the frames a test sends next find the wait in
     * place.
     */
    private CompletableFuture<Void> startWait() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            try {
                client.waitClosed();
                result.complete(null);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }, "wait-closed");
        waiter.setDaemon(true);
        waiter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (waiter.getState() != Thread.State.WAITING && !result.isDone()) {
            if (System.nanoTime() - deadline > 0) {
                fail("waitClosed() neither blocked nor returned");
            }
            Thread.onSpinWait();
        }
        return result;
    }

    private static void assertStillWaiting(CompletableFuture<Void> wait) {
        assertThrows(TimeoutException.class, () -> wait.get(300, TimeUnit.MILLISECONDS),
                "waitClosed() returned while the call was still live");
    }

    /** The exception {@code waitClosed()} ended with. */
    private static Throwable waitFailure(CompletableFuture<Void> wait) {
        return assertThrows(ExecutionException.class, () -> wait.get(3, TimeUnit.SECONDS)).getCause();
    }

    private JsonObject nextCommand(String event) throws InterruptedException {
        String raw = server.poll(3_000);
        assertNotNull(raw, "no " + event + " frame arrived");
        JsonObject frame = JsonParser.parseString(raw).getAsJsonObject();
        assertEquals(event, frame.get("event").getAsString());
        return frame;
    }

    /** What the gateway echoes: the command's own {@code requestId}, or none. */
    private static String requestIdOf(JsonObject command) {
        JsonObject data = command.getAsJsonObject("data");
        return data.has("requestId") ? data.get("requestId").getAsString() : null;
    }

    private static String error(String code, String requestId) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "error");
        frame.addProperty("version", "1.0");
        frame.addProperty("code", code);
        frame.addProperty("message", code);
        if (requestId != null) {
            frame.addProperty("requestId", requestId);
        }
        return frame.toString();
    }
}
