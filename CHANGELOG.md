# Changelog

## 0.1.0 (unreleased)

- Initial WS realtime client for turn-provider-gateway `/sdk`, ported from
  `tello-python` (behaviour 1:1 with the gateway).
- `TelloClient`: connect (Bearer auth), `createCall` / `answer` / `cancel`,
  pub/sub event handlers (`on`), `waitClosed`.
- Event parsing for `user.turn` / `agent.turn` / `call.status_changed` /
  `call.completed` / `call.no_answer` / `call.failed` / `error`.
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
