# Changelog

## Unreleased

### Changed (in-band auth frame handshake)

- Authentication moved off the WS upgrade and into the connection itself. The
  upgrade no longer sends an `Authorization: Bearer` header (or any `?token=`
  query), so the API key never appears in the URL, headers, logs, or exception
  messages.
- After the socket opens, `connect()` now sends an `auth` frame
  (`{"event":"auth","data":{"token":"<rawApiKey>","requestId":"…?"}}`) as its first
  application frame and withholds every other command until the server replies
  `{"type":"auth.ok",…}`. This is internal: the public API is unchanged — build a
  `TelloClient` and call `connect()` / `connectBlocking()` exactly as before.
- `connect()` fails (throws a `TelloException` from `connectBlocking()`) on an
  `unauthenticated` error frame, a close 4401, or an `auth.ok` wait timeout (bounded
  by the existing connect timeout). `auth.ok` is consumed internally and not
  re-emitted to subscribers.
- `Commands.auth(apiKey, requestId)` added (emits `event:"auth"`, field `token`);
  `EventType.AUTH_OK` added; optional `ClientConfig` auth `requestId` constructor added.

### Changed (command surface)

- `sendSms` no longer takes a `callId`: `sendSms(to, message)` /
  `sendSms(to, message, requestId)` (the frame carries only `to` / `message` /
  optional `requestId`).
- Added `EventType.DTMF_ACCEPTED` (`dtmf.accepted`) with a typed
  `DtmfAcceptedEvent` (`requestId`, `messageId`, `digits`); `answer.accepted` /
  `call.created` already handled.

### Changed (camelCase wire contract)

- Inbound frames now use camelCase wire keys (`sessionId`, `callId`, `turnIndex`,
  `previousStatus`, `failureReason`, `requestId`); event types renamed:
  `call.status_changed` → `call.statusChanged`, `call.no_answer` → `call.noAnswer`.
- Status vocabulary now camelCase: `in_progress` → `inProgress`,
  `no_answer` → `noAnswer`.
- Error codes now camelCase: `toRequired`, `agentIdRequired`, `callAlreadyActive`,
  `noActiveCall`, `callRejected`, `internalError` (exception class names unchanged).
- Events now carry `sessionId` (exposed on `Event.sessionId`).
- Outbound commands unchanged (already camelCase).

## 0.1.0 (unreleased)

- Initial WS realtime client for turn-provider-gateway `/sdk`, ported from
  `tello-python` (behaviour 1:1 with the gateway).
- `TelloClient`: connect (in-band `auth` handshake), `createCall` /
  `answer` / `cancel`, pub/sub event handlers (`on`), `waitClosed`.
- Event parsing for `user.turn` / `agent.turn` / `call.status_changed` (now
  `call.statusChanged`) / `call.completed` / `call.no_answer` (now
  `call.noAnswer`) / `call.failed` / `error`.
- Outbound `{event,data}` envelope vs inbound flat `{type,...}` isolated at the
  module boundary (`Commands` / `EventParser`).
- Error-code → exception mapping; close 4401 → `AuthenticationException`,
  4429 → `SessionReplacedException`.
- Lifecycle correctness (matches the python code-review fixes): `waitClosed()`
  raises on a rejected `createCall` instead of hanging; abnormal mid-call
  disconnect → `ConnectionClosedException`; non-object frames dropped;
  done-flag re-set race guarded; `DISCONNECTED` delivered as a typed `Event`.
- Built-in `java.net.http.WebSocket` transport (auto pong); Gson for JSON.
- `TELLO_API_KEY` / `TELLO_URL` environment-variable config.

### Fixes (code review)

- Replace the reassignable `done` future with a single monitor + `callFinished`
  flag (matches Python's in-place latch): a re-entrant follow-up call no longer
  orphans a `waitClosed()` waiter.
- Guard all call state (`active` / `callGen` / `callError` / `callFinished`)
  under one lock; `waitClosed()` reads/clears `callError` under the lock.
- Serialize outbound sends so concurrent `answer`/`createCall`/`cancel` cannot
  trigger `java.net.http.WebSocket`'s overlapping-Text-send `IllegalStateException`.
- `EventParser` tolerates unexpected field types (no throw); `dispatchRaw`
  guards parsing so a malformed frame is dropped, not fatal.
- `close()` waits (bounded) for the receive loop to finish so `DISCONNECTED`
  has fired; `finish()` is run-once.
- `onError` message uses `error.toString()` (avoids "connection error: null").
