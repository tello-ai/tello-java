**English** | [한국어](README.ko.md)

# tello-java

Tello SDK for Java — a thin **WebSocket** realtime client for the API gateway
(api-gateway) `/sdk` endpoint, plus a **REST** client for the public resource API
(tools, agents, crews, crew generation, call drafts). On the WebSocket side the SDK
is the "conversation brain": the gateway streams each caller turn from a live phone
call, and your handler's reply is forwarded back into the call.

> repo: `tello-java` · Maven artifact: `io.telloai:tello-sdk` · package: `io.telloai`
>
> The realtime client (`TelloClient`) is WebSocket only; its protocol contract lives
> in [`docs/protocol/sdk-ws.v1.md`](docs/protocol/sdk-ws.v1.md). The REST resource
> API is a separate client, `TelloApi` (§6). There is no webhook surface.
> Ported from `tello-python`; behaviour is 1:1 with the gateway.

## 1. Install

Gradle:

```kotlin
dependencies {
    implementation("io.telloai:tello-sdk:0.1.3")
}
```

Maven:

```xml
<dependency>
    <groupId>io.telloai</groupId>
    <artifactId>tello-sdk</artifactId>
    <version>0.1.3</version>
</dependency>
```

Requires Java 17+. The only runtime dependency is Gson; the WebSocket transport is
the built-in `java.net.http.WebSocket` and the REST client uses the built-in
`java.net.http.HttpClient`.

## 2. API key

Sent in-band: after the socket opens the client sends an `auth` frame (the raw key
in its `token` field) as its first application frame, and `connect()` completes only
once the server returns `auth.ok`. The key is never placed on the WS upgrade request (no
`Authorization` header, no `?token=` query), so it stays out of URLs, logs, and
exception messages. This is internal — you do not call it. Pass the key explicitly
or via `TELLO_API_KEY` / `TELLO_URL` environment variables (a no-arg
`new TelloClient()` reads them). Without a URL argument or `TELLO_URL`, the client
connects to `wss://api.telloai.io/sdk`.

The upgrade URL also carries `sdk=java`, `version=<this package's version>` and
`protocol=<PROTOCOL_VERSION>` (for example
`wss://api.telloai.io/sdk?sdk=java&version=0.1.3&protocol=1.0`) so the gateway can log
which client connected. Your URL's path and other query parameters are kept; those
three keys replace any it already has. The server never rejects a connection over them.

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
| `ipNotAllowed` | `AuthenticationException` (with `.ip`; also close code 4401) |
| `insufficientScope` | `PermissionDeniedException` (with `.requiredScope`; only that command is refused, the connection stays open) |
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

An unrecognised code falls back to `TelloServerException`. An `internalError`
answering `auth` (close 1011: the gateway could not check the key) fails `connect()`
with a `TelloServerException` rather than an `AuthenticationException`; the key was
not refused, so reconnecting later may work.

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

An `ErrorEvent` carries the raw `code`, `message`, `question`, `requiredScope` and
`ip`. To handle it as the typed exception `waitClosed()` would throw, map it with
`Errors.exceptionFor(err)` from `io.telloai.errors`:

```java
client.on(EventType.ERROR, e -> {
    ErrorEvent err = (ErrorEvent) e;
    TelloException error = Errors.exceptionFor(err);
    if (error instanceof ValidationException) {
        // e.g. dtmfDigitsInvalid for a sendDtmf: fix the input; the call goes on
    }
});
```

The gateway drives a WS-level ping heartbeat; pongs are sent automatically. There
is no reconnect/resume — treat an abnormal close as reconnect-worthy and restart
the call.

## 6. REST resource API

`TelloApi` manages the account's resources over the public REST API
(`https://api.telloai.io/v1`). It is separate from `TelloClient` and needs no
WebSocket connection.

> **Writes go live at once.** Creating, changing or deleting a tool, agent or crew
> is deployed to live calls immediately. **`callDrafts().confirm(...)` places a real,
> billed phone call.**

```java
TelloApi api = new TelloApi();          // $TELLO_API_KEY, $TELLO_API_URL
JsonObject agents = api.agents().list().join();

JsonObject tool = new JsonObject();
tool.addProperty("type", "endCall");
tool.addProperty("name", "통화 종료");
tool.addProperty("functionName", "end_call");
JsonObject created = api.tools().create(tool, "create-end-call-1").join();
```

- **Key and URL**: `new TelloApi(apiKey)` / `new TelloApi(apiKey, apiUrl)`, or the
  `TELLO_API_KEY` / `TELLO_API_URL` environment variables (the arguments win). Without
  either URL the client uses `https://api.telloai.io/v1`. The URL must be `https`;
  plain `http` is accepted only for `localhost`, `127.0.0.1` and `::1`. The key is
  sent as `Authorization: Bearer <key>` and never appears in `toString()` or error
  messages. Every request carries `User-Agent: tello-java-sdk/<version>`.
- **Timeouts**: 30 seconds for reads (get, list); 120 seconds for every write (tool,
  agent and crew create/update/delete, crew generation, call draft
  create/revise/confirm). Change them with
  `TelloApi.builder().apiKey(k).timeout(d).longTimeout(d).build()`.
- **No automatic retries.** To retry a create safely, pass an idempotency key and
  reuse it.
- **Bodies and results** are Gson `JsonObject`s shaped as in the public REST docs;
  fields this SDK does not know are kept. Every method returns a
  `CompletableFuture`. An empty id or an invalid idempotency key (1–255 printable
  ASCII characters, no comma) throws `IllegalArgumentException` before any request.

| method | HTTP | result |
| --- | --- | --- |
| `tools().list()` / `list(limit, cursor)` | `GET /tools` | `{data, nextCursor}` |
| `tools().get(id)` | `GET /tools/{id}` | tool |
| `tools().create(body[, idempotencyKey])` | `POST /tools` (201) | tool |
| `tools().update(id, patch)` | `PATCH /tools/{id}` | tool |
| `tools().delete(id)` | `DELETE /tools/{id}` (204) | `null` |
| `agents().list()` | `GET /agents` | `{data}` |
| `agents().get/create/update/delete` | `/agents[/{id}]` | agent / `null` |
| `crews().list()` | `GET /crews` | `{data}` |
| `crews().get/create/update/delete` | `/crews[/{id}]` | crew / `null` |
| `crews().generate(input[, idempotencyKey])` | `POST /crews/generate` (202, or 200 for the existing job) | job |
| `crews().generateAndWait(input[, pollInterval, timeout, idempotencyKey])` | generate, then poll | finished job |
| `crewGenerations().get(id)` | `GET /crew-generations/{id}` | job |
| `callDrafts().create(body[, idempotencyKey])` | `POST /call-drafts` (201) | draft |
| `callDrafts().revise(draftId, request[, idempotencyKey])` | `POST /call-drafts/{id}/revise` | new draft (new id) |
| `callDrafts().confirm(draftId[, idempotencyKey])` | `POST /call-drafts/{id}/confirm` (201) | call |

`generateAndWait` polls every 2 seconds for up to 600 seconds by default and
completes with the job once it is no longer `running`. A `failed` job is returned,
not thrown: branch on `status`. If the time runs out it fails with
`CrewGenerationTimeoutException` (a `TimeoutException`; `.generationId` names the job,
which keeps running). Every draft status (`pendingConfirmation`,
`needsClarification`, `refused`, `confirmed`) is a successful result.

`confirm` always sends an `Idempotency-Key` (the API requires one): yours, or a
generated UUID. A failed confirm carries that key; retry only with it so a lost
response never becomes a second call.

### REST errors

A failed request completes the future exceptionally (`join()` wraps the error in a
`CompletionException`):

- `TelloApiException` — the server answered with an error. `.status` is the HTTP
  status, `.code` the server's `error` exactly as sent (snake_case, as in the public
  error table), `.getMessage()` its message, `.data` its `data` object or `null`,
  `.idempotencyKey` the key the request carried or `null`. A response that is not
  the API's error envelope (e.g. a proxy page) has `code` `http_<status>`; an
  undocumented success status or a success body that is not a JSON object has
  `code` `invalid_response`.
- `TelloApiConnectionException` — no response (connection failure or timeout;
  `getCause()` is the transport error). **A write may already have been applied**:
  check with get or list before retrying, and retry with the same idempotency key.

Both extend `TelloException`. Branch on `code`:

| status | `code` | meaning |
| --- | --- | --- |
| 401 | `unauthenticated` | the key is missing, wrong or revoked |
| 403 | `insufficient_scope` | the key lacks the scope this request needs |
| 404 | `tool_not_found` / `agent_not_found` / `crew_not_found` | no such resource in the account |
| 409 | `tool_in_use` / `agent_in_use` / `crew_in_use` | still referenced; see `data` |
| 409 | `crew_generation_in_progress` | another crew is being generated; `data.id` is that job |
| 404 | `draft_not_found` | no such call draft |
| 409 | `idempotency_key_reused` | the key was used with a different request |
| 502 | `write_outcome_unknown` | the write may or may not have been applied |

The full list is in the public REST error docs.

```java
try {
    api.tools().get("tool_123").join();
} catch (CompletionException e) {
    if (e.getCause() instanceof TelloApiException err && err.code.equals("tool_not_found")) {
        // create it, or report the missing tool
    } else {
        throw e;
    }
}
```

## 7. Examples

Standalone programs live in [`examples/`](examples/README.md): `BasicCall`
(connect, one call, answer each turn) and `CallSummary` (gated live scenario
ending in `call.summary`). They sit outside the Gradle build and are compiled
with `javac` against the built SDK classes.

They place real calls. Read [`examples/README.md`](examples/README.md) first.

## 8. Version compatibility

`io.telloai:tello-sdk 0.1.x` implements Tello WS protocol `1.0`.

The full frame contract is in [`docs/protocol/sdk-ws.v1.md`](docs/protocol/sdk-ws.v1.md),
with [`docs/events/sdk-events.v1.schema.json`](docs/events/sdk-events.v1.schema.json)
and [`docs/errors/errors.v1.json`](docs/errors/errors.v1.json). Those three files
are generated copies of the canonical contract that lives beside the gateway
implementation — read them here, edit them there.
