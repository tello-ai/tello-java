# tello-java

Tello SDK for Java — a thin **WebSocket** realtime client for the
turn-provider-gateway `/sdk` endpoint. The SDK is the "conversation brain": the
gateway streams each caller turn from a live phone call, and your handler's reply
is forwarded back into the call.

> repo: `tello-java` · Maven artifact: `ai.tello:tello-sdk` · package: `ai.tello`
>
> Transport is WebSocket only. There is no REST or webhook surface. The protocol
> contract lives in [`docs/protocol/sdk-ws.v1.md`](docs/protocol/sdk-ws.v1.md).
> Ported from `tello-python`; behaviour is 1:1 with the gateway.

## 1. Install (Gradle)

```kotlin
dependencies {
    implementation("ai.tello:tello-sdk:0.1.0")
}
```

Requires Java 17+. The only runtime dependency is Gson; the WebSocket transport is
the built-in `java.net.http.WebSocket`.

## 2. API key

Sent in-band: after the socket opens the client sends an `auth` frame (the raw key
in its `token` field) as its first application frame, and `connect()` completes only
once the server returns `auth.ok`. The key is never placed on the WS upgrade request (no
`Authorization` header, no `?token=` query), so it stays out of URLs, logs, and
exception messages. This is internal — you do not call it. Pass the key explicitly
or via `TELLO_API_KEY` / `TELLO_URL` environment variables (a no-arg
`new TelloClient()` reads them).

A rejected key (`unauthenticated` error frame or close 4401) or an `auth.ok` wait
timeout makes `connect()` / `connectBlocking()` fail with an
`AuthenticationException` (or `ConnectionClosedException` on timeout).

## 3. Connect + start a call

```java
try (TelloClient client = new TelloClient("tello_live_xxx", "ws://localhost:3000/sdk").connectBlocking()) {
    client.on(EventType.USER_TURN, e -> {
        TurnEvent turn = (TurnEvent) e;
        client.answer("확인했습니다. 계속 말씀해주세요.");
    });
    client.createCall("+821012345678", "예약 확인").join();
    client.waitClosed();
}
```

`connect()` returns a `CompletableFuture<TelloClient>`; `connectBlocking()` is the
blocking convenience. Commands (`createCall` / `answer` / `cancel`) return
`CompletableFuture<Void>`.

## 4. Realtime turn events (pub/sub)

Register handlers per event type with `client.on(type, Consumer<TelloEvent>)` and
cast to the concrete type:

| `EventType` | value | concrete type / fields |
| --- | --- | --- |
| `USER_TURN` | `user.turn` | `TurnEvent` (`turnIndex`, `text`) |
| `AGENT_TURN` | `agent.turn` | `TurnEvent` (`turnIndex`, `text`) |
| `CALL_STATUS_CHANGED` | `call.statusChanged` | `StatusChangedEvent` (`status`, `previousStatus`) |
| `CALL_COMPLETED` | `call.completed` | `TerminalEvent` (`status`) |
| `CALL_NO_ANSWER` | `call.noAnswer` | `TerminalEvent` (`status`, `failureReason`) |
| `CALL_FAILED` | `call.failed` | `TerminalEvent` (`status`, `failureReason`) |
| `ERROR` | `error` | `ErrorEvent` (`code`, `message`, `requestId`, `question`) |
| `DISCONNECTED` | `disconnected` | `Event` (SDK-local; emitted when the WS closes) |

`waitClosed()` blocks until the call reaches a terminal state (or a cancelled
status) or the connection closes.

## 5. Error handling

Gateway error frames map 1:1 to exceptions (all extend `TelloException`, unchecked):

| gateway `code` | exception |
| --- | --- |
| `unauthenticated` | `AuthenticationException` (also close code 4401) |
| `toRequired` | `ValidationException` |
| `callAlreadyActive` | `CallAlreadyActiveException` |
| `noActiveCall` | `NoActiveCallException` |
| `callRejected` | `CallRejectedException` (with `.question`) |
| `internalError` | `TelloServerException` |

Command errors are also delivered to `EventType.ERROR` subscribers without closing
the socket. `waitClosed()` re-raises the relevant error so a failed `createCall`
does not hang. Connection drop mid-call → `ConnectionClosedException`; session
displaced (close 4429) → `SessionReplacedException`.

The gateway drives a WS-level ping heartbeat; pongs are sent automatically. There
is no reconnect/resume — treat an abnormal close as reconnect-worthy and restart
the call.

## 6. Version compatibility

`ai.tello:tello-sdk 0.1.x` implements Tello WS protocol `1.0`.
