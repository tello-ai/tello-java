package io.telloai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.telloai.errors.CallRefusedException;
import io.telloai.errors.TelloServerException;
import io.telloai.events.ErrorEvent;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TelloClient#waitClosed()} against a {@link FakeGateway} that answers commands
 * the way the gateway does: an error frame echoes the failed command's
 * {@code requestId}, and carries none when the command had none. Only an error
 * answering a {@code createCall} of the current call ends the wait; an error for any
 * other command leaves the call running and only reaches the error handlers.
 */
class CallWaitTest {

    private static final String AUTH_OK = "{\"type\":\"auth.ok\",\"version\":\"1.0\"}";
    private static final String CALL_CREATED = "{\"type\":\"call.created\",\"version\":\"1.0\","
            + "\"sessionId\":\"s1\",\"callId\":\"c1\",\"timestamp\":\"t\"}";
    private static final String CALL_COMPLETED = "{\"type\":\"call.completed\",\"version\":\"1.0\","
            + "\"sessionId\":\"s1\",\"callId\":\"c1\",\"timestamp\":\"t\",\"status\":\"completed\"}";

    private FakeGateway server;
    private TelloClient client;

    @BeforeEach
    void connect() throws Exception {
        server = FakeGateway.start();
        client = new TelloClient("tello_test_key", server.url());
        CompletableFuture<TelloClient> connected = client.connect();
        nextCommand("auth");
        server.sendText(AUTH_OK);
        connected.get(3, TimeUnit.SECONDS);
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
        CompletableFuture<Void> wait = waitInBackground();

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
        CompletableFuture<Void> wait = waitInBackground();

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
        CompletableFuture<Void> wait = waitInBackground();

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

    private CompletableFuture<Void> waitInBackground() {
        return CompletableFuture.runAsync(client::waitClosed);
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
