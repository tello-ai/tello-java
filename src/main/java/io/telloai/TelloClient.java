package io.telloai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.telloai.errors.AuthenticationException;
import io.telloai.errors.ConnectionClosedException;
import io.telloai.errors.Errors;
import io.telloai.errors.SessionReplacedException;
import io.telloai.errors.TelloException;
import io.telloai.events.ErrorEvent;
import io.telloai.events.Event;
import io.telloai.events.EventParser;
import io.telloai.events.TelloEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Tello WebSocket realtime client.
 *
 * <p>Opens a single WS connection to the turn-provider-gateway {@code /sdk} endpoint,
 * sends command frames ({@code createCall} / {@code answer} / {@code cancel}) and
 * dispatches inbound turn/status/terminal/error events to pub/sub handlers.
 *
 * <pre>{@code
 * try (TelloClient client = new TelloClient("tello_live_xxx", "wss://api.telloai.io/sdk").connectBlocking()) {
 *     client.on(EventType.USER_TURN, e -> client.answer("확인했습니다."));
 *     client.createCall("+821012345678", "예약 확인").join();
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
 *   <li>Authentication is internal to {@link #connect()}: once the socket opens the
 *       client sends an {@code auth} frame (raw key in its {@code token} field) as its
 *       first application frame and withholds every other command until the server
 *       returns {@code auth.ok}. A
 *       rejected key ({@code unauthenticated} error frame or close 4401) or an
 *       {@code auth.ok} wait timeout makes {@code connect()} fail; the API key is
 *       never placed in the URL, a header, a log line, or an exception message.</li>
 *   <li>The gateway keeps the socket open on command errors, and a call ends without a
 *       terminal event only when its {@code createCall} fails. So {@code createCall}
 *       always carries a {@code requestId} (generated when omitted), and
 *       {@link #waitClosed()} ends on an error echoing one of the call's
 *       {@code createCall}s, except {@code noActiveCall} and a {@code callAlreadyActive}
 *       for a {@code createCall} sent during the call; an error from any other command
 *       is only emitted to {@code error} handlers.</li>
 *   <li>A call ends before the event that ends it reaches any handler, so a handler
 *       that starts a follow-up call opens a new call, and a {@link #waitClosed()}
 *       already in progress returns with the call it began in.</li>
 *   <li>WS-level ping heartbeat is answered with a pong automatically.</li>
 *   <li>There is no reconnect/resume protocol.</li>
 * </ul>
 */
public class TelloClient extends EventEmitter implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("tello");
    private static final int CLOSE_UNAUTHENTICATED = 4401;
    private static final int CLOSE_SESSION_REPLACED = 4429;
    private static final long CLOSE_WAIT_MILLIS = 5_000L;

    private final ClientConfig config;
    private final HttpClient http = HttpClient.newHttpClient();

    /** Guards all call state below and backs {@link #waitClosed()}'s wait/notify. */
    private final Object lock = new Object();
    private boolean active; // a call is in progress
    /**
     * The call in progress, else the last call that ended, else (before any call has
     * ended on this connection) the next call to open.
     */
    private Call call = new Call();
    /** requestIds of the createCall commands sent for the current call. */
    private final Set<String> callRequestIds = new HashSet<>();
    /** requestId of the createCall that opened the current call. */
    private String openingRequestId;
    private boolean connectionClosed; // finish() has run
    private boolean finished; // run-once guard for finish()

    /** Set from either the WS thread or a sender; read from many — kept volatile. */
    private volatile TelloException closeExc;
    private volatile WebSocket ws;

    /**
     * Completes when the server acknowledges the {@code auth} handshake with
     * {@code auth.ok}, or completes exceptionally on an auth failure / timeout. Every
     * outbound business command waits on it, so nothing is sent before {@code auth.ok}.
     */
    private volatile CompletableFuture<Void> authFuture = new CompletableFuture<>();

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
        this(resolve(apiKey, url, System::getenv));
    }

    public TelloClient(ClientConfig config) {
        this.config = config;
    }

    /**
     * An argument wins over its environment variable, looked up through {@code env};
     * a URL found in neither falls back to {@link ClientConfig#DEFAULT_URL}.
     */
    static ClientConfig resolve(String apiKey, String url, Function<String, String> env) {
        String key = apiKey != null ? apiKey : env.apply(ClientConfig.ENV_API_KEY);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException(
                    "api_key is required (pass apiKey or set $" + ClientConfig.ENV_API_KEY + ")");
        }
        String resolvedUrl = url != null ? url : env.apply(ClientConfig.ENV_URL);
        if (resolvedUrl == null || resolvedUrl.isEmpty()) {
            resolvedUrl = ClientConfig.DEFAULT_URL;
        }
        return new ClientConfig(key, resolvedUrl);
    }

    // -- lifecycle ---------------------------------------------------------

    /**
     * Open the WS connection, authenticate, and start receiving.
     *
     * <p>The returned future completes only after the server has acknowledged the
     * internal {@code auth} handshake with {@code auth.ok}. It completes
     * exceptionally with an {@link AuthenticationException} if the key is rejected
     * ({@code unauthenticated} error frame or close 4401) or {@code auth.ok} does not
     * arrive within the configured connect timeout, and with a
     * {@link ConnectionClosedException} on a transport failure: the socket cannot be
     * opened, the {@code auth} frame cannot be sent, or the connection otherwise closes
     * before {@code auth.ok}. A failure to open the socket or send the frame is its
     * cause.
     */
    public CompletableFuture<TelloClient> connect() {
        synchronized (lock) {
            // A new connection starts with no call in progress, and no call state of a
            // previous connection carries over into it.
            active = false;
            callRequestIds.clear();
            openingRequestId = null;
            if (call.ended) {
                call = new Call();
            }
            connectionClosed = false;
            finished = false;
        }
        closeExc = null;
        authFuture = new CompletableFuture<>();
        synchronized (sendLock) {
            sendChain = CompletableFuture.completedFuture(null);
        }
        return http.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMillis()))
                .buildAsync(URI.create(config.url()), new Listener())
                .thenCompose(webSocket -> {
                    this.ws = webSocket;
                    return authenticate(webSocket);
                })
                .handle((ignored, ex) -> {
                    if (ex != null) {
                        throw connectFailure(ex);
                    }
                    return this;
                });
    }

    /** Blocking convenience: connect (including auth) and return {@code this}. */
    public TelloClient connectBlocking() {
        try {
            return connect().join();
        } catch (CompletionException e) {
            // Surface the SDK exception directly rather than the join wrapper.
            if (e.getCause() instanceof TelloException te) {
                throw te;
            }
            throw e;
        }
    }

    /**
     * Send the {@code auth} frame as the first application frame, then wait
     * (bounded by the connect timeout) for the server's {@code auth.ok}. Auth failure
     * or timeout aborts the socket and fails the returned future.
     */
    private CompletableFuture<Void> authenticate(WebSocket webSocket) {
        String frame = Commands.auth(config.apiKey(), config.authRequestId());
        CompletableFuture<Void> sent;
        synchronized (sendLock) {
            // First frame on the send chain; business sends chain after it and after auth.
            sent = sendChain
                    .handle((v, ex) -> (Void) null)
                    .thenCompose(ignored -> webSocket.sendText(frame, true).thenApply(w -> (Void) null));
            sendChain = sent.handle((v, ex) -> (Void) null);
        }
        return sent.thenCompose(ignored -> awaitAuthOk());
    }

    private CompletableFuture<Void> awaitAuthOk() {
        return authFuture
                .orTimeout(config.connectTimeoutMillis(), TimeUnit.MILLISECONDS)
                .handle((v, ex) -> {
                    if (ex == null) {
                        return (Void) null;
                    }
                    abortQuietly();
                    Throwable cause = unwrap(ex);
                    if (cause instanceof TelloException te) {
                        throw te;
                    }
                    if (cause instanceof TimeoutException) {
                        // A missing auth.ok is an authentication failure, not a
                        // transport one: the gateway closes with 4401 on its own
                        // 10s deadline either way (docs/protocol/sdk-ws.v1.md §2).
                        throw new AuthenticationException("timed out waiting for auth.ok");
                    }
                    throw new ConnectionClosedException("authentication failed");
                });
    }

    /**
     * What {@code connect()} fails with: an SDK exception as it is (auth rejected or
     * timed out, connection closed before {@code auth.ok}); any other failure, which
     * only opening the socket or sending the {@code auth} frame can raise, as a
     * {@link ConnectionClosedException} caused by it. Commands waiting for
     * {@code auth.ok} fail with it too instead of waiting forever.
     */
    private TelloException connectFailure(Throwable ex) {
        Throwable cause = unwrap(ex);
        TelloException error;
        if (cause instanceof TelloException te) {
            error = te;
        } else {
            error = new ConnectionClosedException("connection failed: " + cause);
            error.initCause(cause);
        }
        failAuth(error);
        return error;
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
     * Block until the current call ends, and return with its outcome.
     *
     * <p>Throws the connection error (auth / session-replaced / abnormal mid-call
     * disconnect), or the error that answered one of this call's {@code createCall}s: a
     * refusal before {@code call.created}, a failure of the call after it, or a
     * {@code callAlreadyActive} for the {@code createCall} that opened the call (the
     * gateway was still finishing its previous call, so this one never started; retry
     * shortly). An error from any other command ({@code answer}, {@code sendDtmf},
     * {@code cancel}, {@code getSummary}), {@code noActiveCall}, and a
     * {@code callAlreadyActive} for a {@code createCall} sent during the call do not end
     * the call: they are only emitted to {@code error} handlers, and this keeps waiting.
     *
     * <p>A wait that begins during a call returns when that call ends, even if a handler
     * has started a follow-up call by then; call this again to wait for the follow-up.
     * With no call in progress, this returns at once with the outcome of the call that
     * ended last (throwing its error only once), or, before any call has ended on this
     * connection, waits for the next call to end or the connection to close.
     */
    public void waitClosed() {
        synchronized (lock) {
            Call target = call;
            if (!active && target.ended) {
                if (closeExc != null) {
                    throw closeExc;
                }
                if (target.error != null && !target.errorThrown) {
                    target.errorThrown = true;
                    throw target.error;
                }
                return;
            }
            // A follow-up call opened meanwhile is another Call, so it cannot extend this.
            while (!target.ended) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (target.error != null) {
                target.errorThrown = true;
                throw target.error;
            }
        }
    }

    // -- commands ----------------------------------------------------------

    public CompletableFuture<Void> createCall(String to) {
        return createCall(to, "", null, null);
    }

    public CompletableFuture<Void> createCall(String to, String prompt) {
        return createCall(to, prompt, null, null);
    }

    /**
     * Start a call. The frame always carries a {@code requestId}: {@code requestId} when
     * non-empty, otherwise a generated UUID. The gateway echoes it on the error that
     * refuses or ends this call, which is how {@link #waitClosed()} tells that error
     * apart from the errors of other commands.
     *
     * <p>With no call in progress this opens a new call. During a call it opens none:
     * the gateway refuses it with {@code callAlreadyActive} and the call goes on. If the
     * frame cannot be sent, the returned future fails and the call this opened ends with
     * that error.
     */
    public CompletableFuture<Void> createCall(String to, String prompt,
                                              Map<String, ?> metadata, String requestId) {
        String id = isBlank(requestId) ? UUID.randomUUID().toString() : requestId;
        Call opened;
        synchronized (lock) {
            if (active) {
                callRequestIds.add(id);
                opened = null;
            } else {
                opened = openCall(id);
            }
        }
        CompletableFuture<Void> sent = send(Commands.createCall(to, prompt, metadata, id));
        if (opened == null) {
            return sent;
        }
        return sent.whenComplete((ignored, ex) -> {
            if (ex != null) {
                failOpenedCall(opened, ex);
            }
        });
    }

    public CompletableFuture<Void> answer(String text) {
        return answer(text, null, null);
    }

    public CompletableFuture<Void> answer(String text, String messageId, String requestId) {
        return send(Commands.answer(text, messageId, requestId));
    }

    public CompletableFuture<Void> sendDtmf(String digits) {
        return sendDtmf(digits, null, null);
    }

    public CompletableFuture<Void> sendDtmf(String digits, String messageId, String requestId) {
        return send(Commands.sendDtmf(digits, messageId, requestId));
    }

    public CompletableFuture<Void> cancel() {
        return send(Commands.cancel());
    }

    public CompletableFuture<Void> getSummary(String callId) {
        return getSummary(callId, null);
    }

    public CompletableFuture<Void> getSummary(String callId, String requestId) {
        return send(Commands.getSummary(callId, requestId));
    }

    private CompletableFuture<Void> send(String frame) {
        WebSocket webSocket = ws;
        synchronized (lock) {
            // Never connected, or this connection has closed: nothing can be sent.
            if (webSocket == null || finished) {
                return failed(connectionError());
            }
        }
        // Withhold every business command until auth.ok. If auth failed, this gate
        // is already completed exceptionally and the send fails without touching the wire.
        CompletableFuture<Void> gate = authFuture;
        synchronized (sendLock) {
            // Chain after any in-flight send so no two Text sends overlap.
            CompletableFuture<Void> attempt = sendChain
                    .handle((v, ex) -> (Void) null)
                    .thenCompose(ignored -> gate)
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
        // The auth.ok acknowledgement is internal to the connect handshake: unblock
        // connect() and do not surface it to subscribers.
        if (EventType.AUTH_OK.equals(event.type())) {
            completeAuth();
            return;
        }

        // A call ends before the event that ends it reaches a handler, so a handler
        // that starts a follow-up call opens a new one.
        if (event instanceof ErrorEvent err) {
            TelloException exc = Errors.exceptionFor(err.code, err.message, err.question);
            synchronized (lock) {
                if ("unauthenticated".equals(err.code)) {
                    closeExc = exc;
                    failAuth(exc);
                } else if (endsCall(err)) {
                    // The gateway sends no terminal frame for this call, only this error.
                    endCall(exc);
                }
            }
            emit(EventType.ERROR, err);
            return;
        }

        if (EventParser.isTerminal(event)) {
            synchronized (lock) {
                if (active) {
                    endCall(null);
                }
            }
        }
        emit(event.type(), event);
    }

    /**
     * Whether {@code err} ends the current call; the caller holds {@code lock}. Only an
     * error answering one of the call's {@code createCall}s does: a refusal, or a
     * failure of the call after {@code call.created}. Any other command's error leaves
     * the call running (docs/protocol/sdk-ws.v1.md §6) and is only emitted.
     */
    private boolean endsCall(ErrorEvent err) {
        if (!active || !callRequestIds.contains(err.requestId) || "noActiveCall".equals(err.code)) {
            return false;
        }
        // callAlreadyActive for the createCall that opened the call: the gateway still
        // holds its previous call (§4.1), so this one never started. For a createCall
        // sent during the call, only that createCall was refused and the call goes on.
        return !"callAlreadyActive".equals(err.code) || err.requestId.equals(openingRequestId);
    }

    /** Open a call for the createCall {@code id}; the caller holds {@code lock}. */
    private Call openCall(String id) {
        if (call.ended) {
            call = new Call();
        }
        callRequestIds.clear();
        callRequestIds.add(id);
        openingRequestId = id;
        active = true;
        return call;
    }

    /**
     * End the current call with {@code error} ({@code null} when it ended normally) and
     * wake every wait on it; the caller holds {@code lock}.
     */
    private void endCall(TelloException error) {
        active = false;
        call.ended = true;
        call.error = error;
        lock.notifyAll();
    }

    /** End {@code opened} with its createCall's send failure, unless it ended already. */
    private void failOpenedCall(Call opened, Throwable ex) {
        Throwable cause = unwrap(ex);
        TelloException error = cause instanceof TelloException te ? te : connectionError();
        synchronized (lock) {
            if (active && call == opened) {
                endCall(error);
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
            if (active) {
                if (closeExc == null) {
                    closeExc = new ConnectionClosedException("connection closed before call terminated");
                }
                endCall(closeExc);
            }
        }
        // A close/error before auth.ok is a connect failure, not a hung future.
        failAuth(closeExc != null ? closeExc
                : new ConnectionClosedException("connection closed before authentication"));
        emit(EventType.DISCONNECTED, new Event(EventType.DISCONNECTED, "", "", "", "", new JsonObject()));
        synchronized (lock) {
            // Nothing ended the current call on this connection: release a wait begun
            // before any call, or end a call opened while the connection was closing.
            if (!call.ended) {
                endCall(active ? connectionError() : closeExc);
            }
            connectionClosed = true;
            lock.notifyAll();
        }
    }

    /** Mark the connect handshake as acknowledged (server sent {@code auth.ok}). */
    private void completeAuth() {
        authFuture.complete(null);
    }

    /** Fail the connect handshake; a no-op once already resolved. */
    private void failAuth(TelloException e) {
        authFuture.completeExceptionally(e);
    }

    /** Abort the socket without surfacing the API key anywhere. */
    private void abortQuietly() {
        WebSocket webSocket = ws;
        if (webSocket != null) {
            try {
                webSocket.abort();
            } catch (RuntimeException ignored) {
                // already closing/closed
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /** The failure inside a {@link CompletionException}, or {@code ex} itself. */
    private static Throwable unwrap(Throwable ex) {
        return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    }

    /**
     * One call's outcome. A wait holds the {@code Call} it began in, so it returns with
     * that call's outcome even when a follow-up call has opened by the time it wakes.
     */
    private static final class Call {
        boolean ended;
        /** The error the call ended with; {@code null} when it ended normally. */
        TelloException error;
        /** Whether a {@code waitClosed()} has thrown {@code error} already. */
        boolean errorThrown;
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
            synchronized (lock) {
                if (closeExc == null && active) {
                    closeExc = new ConnectionClosedException("connection error: " + error);
                }
            }
            finish();
        }
    }
}
