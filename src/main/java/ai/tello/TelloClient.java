package ai.tello;

import ai.tello.errors.AuthenticationException;
import ai.tello.errors.ConnectionClosedException;
import ai.tello.errors.Errors;
import ai.tello.errors.SessionReplacedException;
import ai.tello.errors.TelloException;
import ai.tello.events.ErrorEvent;
import ai.tello.events.Event;
import ai.tello.events.EventParser;
import ai.tello.events.TelloEvent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Tello WebSocket realtime client.
 *
 * <p>Opens a single WS connection to the turn-provider-gateway {@code /sdk} endpoint,
 * sends command frames ({@code create_call} / {@code answer} / {@code cancel}) and
 * dispatches inbound turn/status/terminal/error events to pub/sub handlers.
 *
 * <pre>{@code
 * try (TelloClient client = new TelloClient("tello_live_xxx", "ws://localhost:3000/sdk").connectBlocking()) {
 *     client.on(EventType.USER_TURN, e -> client.answer("확인했습니다."));
 *     client.createCall("agent-1", "예약 확인").join();
 *     client.waitClosed();
 * }
 * }</pre>
 *
 * <p>Design notes:
 * <ul>
 *   <li>Auth is on the WS upgrade request ({@code Authorization: Bearer <apiKey>}); a bad
 *       key surfaces as {@link AuthenticationException} from {@link #waitClosed()}.</li>
 *   <li>The gateway keeps the socket open on command errors, so {@link #waitClosed()}
 *       resolves on a rejected {@code create_call} too, rather than hanging.</li>
 *   <li>WS-level ping heartbeat is answered with a pong automatically.</li>
 *   <li>There is no reconnect/resume protocol.</li>
 * </ul>
 */
public class TelloClient extends EventEmitter implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("tello");
    private static final int CLOSE_UNAUTHENTICATED = 4401;
    private static final int CLOSE_SESSION_REPLACED = 4429;
    private static final Set<String> NON_ABORTING = Set.of("no_active_call", "call_already_active");

    private final ClientConfig config;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Object lock = new Object();

    private volatile WebSocket ws;
    private volatile CompletableFuture<Void> done = new CompletableFuture<>();
    private volatile TelloException closeExc;
    private volatile TelloException callError;
    private volatile boolean active;
    private volatile int callGen;

    public TelloClient() {
        this(null, null);
    }

    public TelloClient(String apiKey) {
        this(apiKey, null);
    }

    public TelloClient(String apiKey, String url) {
        this(resolve(apiKey, url));
    }

    public TelloClient(ClientConfig config) {
        this.config = config;
    }

    private static ClientConfig resolve(String apiKey, String url) {
        String key = apiKey != null ? apiKey : System.getenv(ClientConfig.ENV_API_KEY);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException(
                    "api_key is required (pass apiKey or set $" + ClientConfig.ENV_API_KEY + ")");
        }
        String resolvedUrl = url != null ? url : System.getenv(ClientConfig.ENV_URL);
        if (resolvedUrl == null || resolvedUrl.isEmpty()) {
            resolvedUrl = ClientConfig.DEFAULT_URL;
        }
        return new ClientConfig(key, resolvedUrl);
    }

    // -- lifecycle ---------------------------------------------------------

    /** Open the WS connection and start receiving. */
    public CompletableFuture<TelloClient> connect() {
        synchronized (lock) {
            done = new CompletableFuture<>();
            closeExc = null;
            callError = null;
            active = false;
        }
        return http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + config.apiKey())
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMillis()))
                .buildAsync(URI.create(config.url()), new Listener())
                .thenApply(webSocket -> {
                    this.ws = webSocket;
                    return this;
                });
    }

    /** Blocking convenience: connect and return {@code this}. */
    public TelloClient connectBlocking() {
        return connect().join();
    }

    @Override
    public void close() {
        WebSocket webSocket = ws;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        }
    }

    /**
     * Block until the current call ends or the connection closes.
     *
     * <p>Throws the connection error (auth / session-replaced / abnormal mid-call
     * disconnect) or a call-start rejection error if one occurred.
     */
    public void waitClosed() {
        CompletableFuture<Void> current = done;
        current.join();
        if (closeExc != null) {
            throw closeExc;
        }
        TelloException error = callError;
        if (error != null) {
            callError = null;
            throw error;
        }
    }

    // -- commands ----------------------------------------------------------

    public CompletableFuture<Void> createCall(String agentId) {
        return createCall(agentId, "", null, null);
    }

    public CompletableFuture<Void> createCall(String agentId, String prompt) {
        return createCall(agentId, prompt, null, null);
    }

    public CompletableFuture<Void> createCall(String agentId, String prompt,
                                              Map<String, ?> metadata, String requestId) {
        synchronized (lock) {
            callGen++;
            done = new CompletableFuture<>();
            callError = null;
            active = true;
        }
        return send(Commands.createCall(agentId, prompt, metadata, requestId));
    }

    public CompletableFuture<Void> answer(String text) {
        return answer(text, null, null);
    }

    public CompletableFuture<Void> answer(String text, String messageId, String requestId) {
        return send(Commands.answer(text, messageId, requestId));
    }

    public CompletableFuture<Void> cancel() {
        return send(Commands.cancel());
    }

    private CompletableFuture<Void> send(String frame) {
        WebSocket webSocket = ws;
        if (webSocket == null) {
            return failed(connectionError());
        }
        return webSocket.sendText(frame, true)
                .thenApply(w -> (Void) null)
                .exceptionally(ex -> {
                    throw connectionError();
                });
    }

    private static CompletableFuture<Void> failed(Throwable t) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(t);
        return future;
    }

    private TelloException connectionError() {
        TelloException e = closeExc;
        return e != null ? e : new ConnectionClosedException("connection closed");
    }

    // -- receive/dispatch --------------------------------------------------

    private void dispatchRaw(String raw) {
        JsonElement element;
        try {
            element = JsonParser.parseString(raw);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "tello: dropping non-JSON frame");
            return;
        }
        if (!element.isJsonObject()) {
            LOG.log(System.Logger.Level.WARNING, "tello: dropping non-object frame");
            return;
        }
        dispatch(EventParser.parse(element.getAsJsonObject()));
    }

    private void dispatch(TelloEvent event) {
        if (event instanceof ErrorEvent err) {
            TelloException exc = Errors.exceptionFor(err.code, err.message, err.question);
            if ("unauthenticated".equals(err.code)) {
                closeExc = exc;
            } else if (!NON_ABORTING.contains(err.code) && active) {
                // The gateway sends no terminal frame for a rejected command, so
                // unblock waitClosed() with the mapped error instead of hanging.
                synchronized (lock) {
                    callError = exc;
                    active = false;
                }
                done.complete(null);
            }
            emit(EventType.ERROR, err);
            return;
        }

        // Snapshot the call generation: if a terminal handler starts a follow-up
        // call, callGen advances and we must not complete the new call's future.
        int gen = callGen;
        emit(event.type(), event);
        if (EventParser.isTerminal(event) && callGen == gen) {
            active = false;
            done.complete(null);
        }
    }

    private void noteClose(int code, String reason) {
        if (closeExc != null) {
            return;
        }
        if (code == CLOSE_UNAUTHENTICATED) {
            closeExc = new AuthenticationException(isBlank(reason) ? "unauthenticated" : reason);
        } else if (code == CLOSE_SESSION_REPLACED) {
            closeExc = new SessionReplacedException(isBlank(reason) ? "session replaced" : reason);
        }
    }

    private void finish() {
        if (active && closeExc == null && callError == null) {
            closeExc = new ConnectionClosedException("connection closed before call terminated");
        }
        emit(EventType.DISCONNECTED, new Event(EventType.DISCONNECTED, "", "", "", new JsonObject()));
        done.complete(null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            webSocket.request(1);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                dispatchRaw(message);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            noteClose(statusCode, reason);
            finish();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (closeExc == null && active) {
                closeExc = new ConnectionClosedException("connection error: " + error.getMessage());
            }
            finish();
        }
    }
}
