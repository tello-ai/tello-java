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
 * <p>Thread-safety: inbound events are dispatched sequentially on the WS thread;
 * command methods may be called from any thread. All call state is guarded by a
 * single monitor ({@code lock}); {@code waitClosed()} blocks on it. Outbound sends
 * are serialized on a chain so no two {@code sendText} calls overlap (which
 * {@code java.net.http.WebSocket} forbids).
 *
 * <p>Design notes:
 * <ul>
 *   <li>Auth is on the WS upgrade request; a bad key surfaces as
 *       {@link AuthenticationException} from {@link #waitClosed()}.</li>
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
    private static final long CLOSE_WAIT_MILLIS = 5_000L;
    private static final Set<String> NON_ABORTING = Set.of("no_active_call", "call_already_active");

    private final ClientConfig config;
    private final HttpClient http = HttpClient.newHttpClient();

    /** Guards all call state below and backs {@link #waitClosed()}'s wait/notify. */
    private final Object lock = new Object();
    private boolean active;
    private int callGen;
    private boolean callFinished; // current call reached terminal OR connection closed
    private boolean connectionClosed; // finish() has run
    private boolean finished; // run-once guard for finish()
    private TelloException callError; // call-level error to raise once from waitClosed()

    /** Set from either the WS thread or a sender; read from many — kept volatile. */
    private volatile TelloException closeExc;
    private volatile WebSocket ws;

    /** Serializes outbound sends (java.net.http forbids overlapping Text sends). */
    private final Object sendLock = new Object();
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);

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
            active = false;
            callGen = 0;
            callFinished = false;
            connectionClosed = false;
            finished = false;
            callError = null;
        }
        closeExc = null;
        synchronized (sendLock) {
            sendChain = CompletableFuture.completedFuture(null);
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

    /** Close the connection and wait (bounded) for the receive loop to finish. */
    @Override
    public void close() {
        WebSocket webSocket = ws;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
            } catch (RuntimeException ignored) {
                // already closing/closed
            }
        }
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + CLOSE_WAIT_MILLIS;
            while (!connectionClosed) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Block until the current call ends or the connection closes.
     *
     * <p>Throws the connection error (auth / session-replaced / abnormal mid-call
     * disconnect) or a call-start rejection error if one occurred.
     */
    public void waitClosed() {
        synchronized (lock) {
            while (!callFinished) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (closeExc != null) {
                throw closeExc;
            }
            if (callError != null) {
                TelloException error = callError;
                callError = null;
                throw error;
            }
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
            callFinished = false;
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
        synchronized (sendLock) {
            // Chain after any in-flight send so no two Text sends overlap.
            CompletableFuture<Void> attempt = sendChain
                    .handle((v, ex) -> (Void) null)
                    .thenCompose(ignored -> webSocket.sendText(frame, true).thenApply(w -> (Void) null));
            sendChain = attempt.handle((v, ex) -> (Void) null);
            return attempt.exceptionally(ex -> {
                throw connectionError();
            });
        }
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
        try {
            dispatch(EventParser.parse(element.getAsJsonObject()));
        } catch (RuntimeException e) {
            // A malformed field must not tear down the connection.
            LOG.log(System.Logger.Level.WARNING, "tello: dropping unparseable frame", e);
        }
    }

    private void dispatch(TelloEvent event) {
        if (event instanceof ErrorEvent err) {
            TelloException exc = Errors.exceptionFor(err.code, err.message, err.question);
            synchronized (lock) {
                if ("unauthenticated".equals(err.code)) {
                    closeExc = exc;
                } else if (!NON_ABORTING.contains(err.code) && active) {
                    // The gateway sends no terminal frame for a rejected command, so
                    // unblock waitClosed() with the mapped error instead of hanging.
                    callError = exc;
                    active = false;
                    callFinished = true;
                    lock.notifyAll();
                }
            }
            emit(EventType.ERROR, err);
            return;
        }

        // Snapshot the call generation: if a terminal handler starts a follow-up
        // call, callGen advances and we must not finish the wrong call.
        int gen;
        synchronized (lock) {
            gen = callGen;
        }
        emit(event.type(), event);
        if (EventParser.isTerminal(event)) {
            synchronized (lock) {
                if (callGen == gen) {
                    active = false;
                    callFinished = true;
                    lock.notifyAll();
                }
            }
        }
    }

    private void noteClose(int code, String reason) {
        if (code == CLOSE_UNAUTHENTICATED) {
            if (closeExc == null) {
                closeExc = new AuthenticationException(isBlank(reason) ? "unauthenticated" : reason);
            }
        } else if (code == CLOSE_SESSION_REPLACED) {
            if (closeExc == null) {
                closeExc = new SessionReplacedException(isBlank(reason) ? "session replaced" : reason);
            }
        }
    }

    private void finish() {
        synchronized (lock) {
            if (finished) {
                return;
            }
            finished = true;
            if (active && closeExc == null && callError == null) {
                closeExc = new ConnectionClosedException("connection closed before call terminated");
            }
        }
        emit(EventType.DISCONNECTED, new Event(EventType.DISCONNECTED, "", "", "", new JsonObject()));
        synchronized (lock) {
            callFinished = true;
            connectionClosed = true;
            lock.notifyAll();
        }
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
                closeExc = new ConnectionClosedException("connection error: " + error);
            }
            finish();
        }
    }
}
