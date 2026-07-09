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

Sent as `Authorization: Bearer <apiKey>` on the WS upgrade request. Pass it
explicitly or via `TELLO_API_KEY` / `TELLO_URL` environment variables (a no-arg
`new TelloClient()` reads them).

## 3. Connect + start a call

```java
try (TelloClient client = new TelloClient("tello_live_xxx", "ws://localhost:3000/sdk").connectBlocking()) {
    client.on(EventType.USER_TURN, e -> {
        TurnEvent turn = (TurnEvent) e;
        client.answer("확인했습니다. 계속 말씀해주세요.");
    });
    client.createCall("+821012345678", "agent-1", "예약 확인").join();
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
| `CALL_STATUS_CHANGED` | `call.status_changed` | `StatusChangedEvent` (`status`, `previousStatus`) |
| `CALL_COMPLETED` | `call.completed` | `TerminalEvent` (`status`) |
| `CALL_NO_ANSWER` | `call.no_answer` | `TerminalEvent` (`status`, `failureReason`) |
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
| `to_required` | `ValidationException` |
| `agent_id_required` | `ValidationException` |
| `call_already_active` | `CallAlreadyActiveException` |
| `no_active_call` | `NoActiveCallException` |
| `call_rejected` | `CallRejectedException` (with `.question`) |
| `internal_error` | `TelloServerException` |

Command errors are also delivered to `EventType.ERROR` subscribers without closing
the socket. `waitClosed()` re-raises the relevant error so a failed `createCall`
does not hang. Connection drop mid-call → `ConnectionClosedException`; session
displaced (close 4429) → `SessionReplacedException`.

The gateway drives a WS-level ping heartbeat; pongs are sent automatically. There
is no reconnect/resume — treat an abnormal close as reconnect-worthy and restart
the call.

## 6. Version compatibility

`ai.tello:tello-sdk 0.1.x` implements Tello WS protocol `1.0`.
