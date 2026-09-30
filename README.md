**English** | [한국어](README.ko.md)

# tello-java

Tello SDK for Java — a thin **WebSocket** realtime client for the
turn-provider-gateway `/sdk` endpoint. The SDK is the "conversation brain": the
gateway streams each caller turn from a live phone call, and your handler's reply
is forwarded back into the call.

> repo: `tello-java` · Maven artifact: `io.telloai:tello-sdk` · package: `io.telloai`
>
> Transport is WebSocket only. There is no REST or webhook surface. The protocol
> contract lives in [`docs/protocol/sdk-ws.v1.md`](docs/protocol/sdk-ws.v1.md).
> Ported from `tello-python`; behaviour is 1:1 with the gateway.

## 1. Install (Gradle)

```kotlin
dependencies {
    implementation("io.telloai:tello-sdk:0.1.1")
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
`new TelloClient()` reads them). Without a URL argument or `TELLO_URL`, the client
connects to `wss://api.telloai.io/sdk`.

A rejected key (`unauthenticated` error frame or close 4401) or an `auth.ok` wait
timeout (the connect timeout, 10 s by default) makes `connect()` /
`connectBlocking()` fail with an `AuthenticationException`. A transport failure
makes it fail with a `ConnectionClosedException`: the socket cannot be opened
(refused, unreachable, TLS or upgrade failure, connect timeout), the `auth` frame
cannot be sent, or the connection closes before `auth.ok` for another reason.
If opening the socket or sending the frame failed, `getCause()` returns the
underlying error. `connectBlocking()` throws these exceptions directly;
`connect()` completes exceptionally with them.

## 3. Connect + start a call

```java
try (TelloClient client = new TelloClient("tello_live_xxx", "wss://api.telloai.io/sdk").connectBlocking()) {
    client.on(EventType.USER_TURN, e -> {
        TurnEvent turn = (TurnEvent) e;
        client.answer("확인했습니다. 계속 말씀해주세요.");
    });
    client.createCall("+821012345678", "예약 확인").join();
    client.waitClosed();
}
```

`connect()` returns a `CompletableFuture<TelloClient>`; `connectBlocking()` is the
blocking convenience. Commands (`createCall` / `answer` / `sendDtmf` / `cancel` /
`getSummary`) return `CompletableFuture<Void>`.

## 4. Realtime turn events (pub/sub)

Register handlers per event type with `client.on(type, Consumer<TelloEvent>)` and
cast to the concrete type:

| `EventType` | value | concrete type / fields |
| --- | --- | --- |
| `CALL_CREATED` | `call.created` | `Event` (`callId`, `sessionId`) |
| `USER_TURN` | `user.turn` | `TurnEvent` (`turnIndex`, `text`) |
| `AGENT_TURN` | `agent.turn` | `TurnEvent` (`turnIndex`, `text`) |
| `ANSWER_ACCEPTED` | `answer.accepted` | `AnswerAcceptedEvent` (`requestId`, `messageId`) |
| `DTMF_ACCEPTED` | `dtmf.accepted` | `DtmfAcceptedEvent` (`requestId`, `messageId`, `digits`) |
| `CALL_SUMMARY` | `call.summary` | `CallSummaryEvent` (`requestId`, `status`, `durationSeconds`, `transcript`, `summary`, `creditCharged`) |
| `CALL_STATUS_CHANGED` | `call.statusChanged` | `StatusChangedEvent` (`status`, `previousStatus`) |
| `CALL_COMPLETED` | `call.completed` | `TerminalEvent` (`status`) |
| `CALL_NO_ANSWER` | `call.noAnswer` | `TerminalEvent` (`status`, `failureReason`) |
| `CALL_FAILED` | `call.failed` | `TerminalEvent` (`status`, `failureReason`) |
| `ERROR` | `error` | `ErrorEvent` (`code`, `message`, `requestId`, `question`) |
| `DISCONNECTED` | `disconnected` | `Event` (SDK-local; emitted when the WS closes) |

`AUTH_OK` is consumed internally by `connect()` and never re-emitted.

`waitClosed()` blocks until the current call ends: a terminal event
(`call.completed`, `call.noAnswer`, `call.failed`, or `call.statusChanged` with
status `cancelled`), an error that ends the call (see §5), or the connection
closing. A wait in progress returns when its own call ends, even if a handler
starts a follow-up call from the terminal event; call `waitClosed()` again to
wait for the follow-up. After `cancel()`, the gateway sends `call.statusChanged`
with status `cancelled` as the call's terminal event, with `previousStatus` set
to the status the call had before it.

## 5. Error handling

Gateway error frames map 1:1 to exceptions (all extend `TelloException`, unchecked):

| gateway `code` | exception |
| --- | --- |
| `unauthenticated` | `AuthenticationException` (also close code 4401) |
| `toRequired` | `ValidationException` |
| `callIdRequired` | `ValidationException` |
| `dtmfDigitsRequired` | `ValidationException` |
| `dtmfDigitsInvalid` | `ValidationException` |
| `callNotFound` | `ValidationException` |
| `callNotCompleted` | `ValidationException` |
| `callAlreadyActive` | `CallAlreadyActiveException` |
| `noActiveCall` | `NoActiveCallException` |
| `callRejected` | `CallRejectedException` (with `.question`) |
| `internalError` | `TelloServerException` |

An unrecognised code falls back to `TelloServerException`.

Every error carries the gateway code on `.code` — branch on that, never on
`.getMessage()`, which is display text the gateway may reword.

`createCall` can also be refused before any call exists — no `call.created`, no
`callId`, no charge. The gateway never retries these; any retry policy is yours.

| gateway `code` | exception | what to do |
| --- | --- | --- |
| `insufficientCredit` | `CallRefusedException` | tell the user to top up; do not resend |
| `concurrentLimitExceeded` | `CallRefusedException` | wait for one of your own calls to end, then retry |
| `callerNotVerified` | `CallRefusedException` | tell the user to verify the number; do not resend |
| `noRepresentativeNumber` | `CallRefusedException` | tell the user to configure a caller number; do not resend |
| `callProviderUnauthorized` | `CallProviderException` | service fault; report it, resending never helps |
| `callProviderDraining` | `CallProviderException` | retry later at your own pace |
| `callProviderUnavailable` | `CallProviderException` | retry later at your own pace |
| `callSetupFailed` | `CallProviderException` | surface as a failure and report it |

Command errors are delivered to `EventType.ERROR` subscribers without closing the
socket. Only an error answering one of the current call's `createCall`s ends
`waitClosed()`, which re-raises it: a refusal before `call.created`, or a failure
of the call after it. The gateway echoes each command's `requestId` on its error,
so `createCall` always carries one: the `requestId` you pass if it is non-empty,
otherwise a generated UUID. Give every command its own `requestId`, and never
reuse the `createCall`'s on another command: an error echoing it ends the wait.

Two codes are special:

- `callAlreadyActive` ends the wait only when it answers the `createCall` that
  opened the call. The gateway is then still finishing the previous call (it
  holds it briefly after the terminal event), so this call never started; send
  the `createCall` again shortly. A `callAlreadyActive` for a `createCall` sent
  during a live call is only an `EventType.ERROR` event, and the live call goes
  on.
- `noActiveCall` never ends the wait.

An error from any other command (`answer`, `sendDtmf`, `cancel`, `getSummary`)
is delivered only as an `EventType.ERROR` event; the call keeps running and
`waitClosed()` keeps waiting. Connection drop mid-call →
`ConnectionClosedException`; session displaced (close 4429) →
`SessionReplacedException`.

An `ErrorEvent` carries the raw `code`, `message`, and `question`. To handle it as
the typed exception `waitClosed()` would throw, map it with
`Errors.exceptionFor(code, message, question)` from `io.telloai.errors`:

```java
client.on(EventType.ERROR, e -> {
    ErrorEvent err = (ErrorEvent) e;
    TelloException error = Errors.exceptionFor(err.code, err.message, err.question);
    if (error instanceof ValidationException) {
        // e.g. dtmfDigitsInvalid for a sendDtmf: fix the input; the call goes on
    }
});
```

The gateway drives a WS-level ping heartbeat; pongs are sent automatically. There
is no reconnect/resume — treat an abnormal close as reconnect-worthy and restart
the call.

## 6. Examples

Standalone programs live in [`examples/`](examples/README.md): `BasicCall`
(connect, one call, answer each turn) and `CallSummary` (gated live scenario
ending in `call.summary`). They sit outside the Gradle build and are compiled
with `javac` against the built SDK classes.

They place real calls. Read [`examples/README.md`](examples/README.md) first.

## 7. Version compatibility

`io.telloai:tello-sdk 0.1.x` implements Tello WS protocol `1.0`.

The full frame contract is in [`docs/protocol/sdk-ws.v1.md`](docs/protocol/sdk-ws.v1.md),
with [`docs/events/sdk-events.v1.schema.json`](docs/events/sdk-events.v1.schema.json)
and [`docs/errors/errors.v1.json`](docs/errors/errors.v1.json). Those three files
are generated copies of the canonical contract that lives beside the gateway
implementation — read them here, edit them there.
