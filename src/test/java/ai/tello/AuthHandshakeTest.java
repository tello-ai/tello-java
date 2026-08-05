package ai.tello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tello.errors.AuthenticationException;
import ai.tello.errors.TelloException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the internal WebSocket auth handshake against a local
 * {@link FakeGateway}: the {@code auth} frame is first (raw key in {@code token}),
 * no key leaks onto the upgrade, business commands wait for {@code auth.ok}, and
 * every auth failure mode (error frame, close 4401, timeout) surfaces as a connect
 * failure.
 */
class AuthHandshakeTest {

    private static final String API_KEY = "tello_test_secretKEY_must_never_leak";
    private static final String AUTH_OK = "{\"type\":\"auth.ok\",\"version\":\"1.0\"}";

    private JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void authIsFirstFrameAndCarriesToken() throws Exception {
        try (FakeGateway server = FakeGateway.start()) {
            server.onText(msg -> sendQuietly(server, AUTH_OK));
            try (TelloClient client = new TelloClient(API_KEY, server.url()).connectBlocking()) {
                JsonObject frame = parse(server.take());
                assertEquals("auth", frame.get("event").getAsString());
                assertEquals(API_KEY, frame.getAsJsonObject("data").get("token").getAsString());
            }
        }
    }

    @Test
    void noAuthorizationHeaderOrTokenOnUpgrade() throws Exception {
        try (FakeGateway server = FakeGateway.start()) {
            server.onText(msg -> sendQuietly(server, AUTH_OK));
            try (TelloClient client = new TelloClient(API_KEY, server.url()).connectBlocking()) {
                assertFalse(server.requestHeaders().containsKey("authorization"),
                        "upgrade must not carry an Authorization header");
                assertNotNull(server.requestTarget());
                assertFalse(server.requestTarget().contains("token"),
                        "upgrade target must not carry a token query param");
                assertFalse(server.requestTarget().contains(API_KEY),
                        "upgrade target must not carry the api key");
            }
        }
    }

    @Test
    void businessCommandsAreWithheldUntilAuthOk() throws Exception {
        try (FakeGateway server = FakeGateway.start()) {
            // No auto-responder: drive auth.ok manually to observe frame ordering.
            TelloClient client = new TelloClient(API_KEY, server.url());
            CompletableFuture<TelloClient> connected = client.connect();

            // The auth frame proves the socket is open and the key was sent.
            assertEquals("auth", parse(server.take()).get("event").getAsString());

            CompletableFuture<Void> call = client.createCall("+821012345678");
            assertNull(server.poll(400), "no business command may be sent before auth.ok");

            server.sendText(AUTH_OK);
            connected.get(3, TimeUnit.SECONDS);
            call.get(3, TimeUnit.SECONDS);

            assertEquals("createCall", parse(server.take()).get("event").getAsString());
            client.close();
        }
    }

    @Test
    void unauthenticatedErrorFrameFailsConnect() throws Exception {
        try (FakeGateway server = FakeGateway.start()) {
            server.onText(msg -> {
                sendQuietly(server, "{\"type\":\"error\",\"version\":\"1.0\","
                        + "\"code\":\"unauthenticated\",\"message\":\"Authentication required\"}");
                closeQuietly(server, 4401, "unauthenticated");
            });
            TelloException ex = assertThrows(AuthenticationException.class,
                    () -> new TelloClient(API_KEY, server.url()).connectBlocking());
            assertKeyAbsent(ex);
        }
    }

    @Test
    void close4401FailsConnect() throws Exception {
        try (FakeGateway server = FakeGateway.start()) {
            server.onText(msg -> closeQuietly(server, 4401, "unauthenticated"));
            TelloException ex = assertThrows(AuthenticationException.class,
                    () -> new TelloClient(API_KEY, server.url()).connectBlocking());
            assertKeyAbsent(ex);
        }
    }

    @Test
    void authOkTimeoutFailsConnect() throws Exception {
        try (FakeGateway server = FakeGateway.start()) {
            // Server never acknowledges the auth frame.
            ClientConfig config = new ClientConfig(API_KEY, server.url(), 300L);
            // A missing auth.ok is an authentication failure, not a transport
            // one — the gateway closes with 4401 on its own deadline either way.
            TelloException ex = assertThrows(AuthenticationException.class,
                    () -> new TelloClient(config).connectBlocking());
            assertKeyAbsent(ex);
        }
    }

    private static void sendQuietly(FakeGateway server, String text) {
        try {
            server.sendText(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void closeQuietly(FakeGateway server, int code, String reason) {
        try {
            server.sendClose(code, reason);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertKeyAbsent(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null) {
                assertFalse(c.getMessage().contains(API_KEY), "exception message leaked the api key");
            }
        }
    }
}
