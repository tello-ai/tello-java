# Live SDK examples

These examples make real provider requests. They are deliberately outside the
Gradle build and must never be run by CI. Use controlled test phone numbers only.

Every live-effect command refuses to connect unless this exact gate is present:

```sh
export ALLOW_LIVE_SIDE_EFFECTS=true
```

Both examples also validate every required environment variable before opening a
WebSocket connection.

| Variable | Used by | Description |
| --- | --- | --- |
| `TELLO_API_KEY` | both | Gateway API key. |
| `TELLO_URL` | both | Absolute `ws://` or `wss://` gateway `/sdk` URL. Use `wss://` outside a controlled local environment. |
| `LIVE_SMS_TO` | both | Controlled recipient for the real SMS. |
| `TELLO_AGENT_ID` | `CallSummarySms` | Agent to use for the real call. |
| `LIVE_CALL_TO` | `CallSummarySms` | Controlled recipient who will answer the real call. |
| `LIVE_CALL_TIMEOUT_SECONDS` | `CallSummarySms` | Positive timeout; on expiry the example attempts one `cancel` and fails. |
| `LIVE_CALL_PROMPT` | `CallSummarySms`, optional | Prompt supplied when the live call is created. |
| `LIVE_SMS_MESSAGE` | both, optional | SMS body. Defaults to a clear live-test marker. |
| `LIVE_CALL_REPLY` | `CallSummarySms`, optional | Deterministic reply for each `user.turn`. |

No example retries a call, answer, or SMS command. A `requestId` matches the
response to the command; it is not an idempotency key.

## Compile and run

Build the SDK first so its classes and Gson are available, then compile the
chosen standalone example with the same classpath. The repository currently has
no Gradle wrapper, so use a local Gradle installation where available.

```sh
gradle classes
javac -cp "build/classes/java/main:/path/to/gson-2.11.0.jar" -d build/live-examples examples/SendSms.java
java -cp "build/live-examples:build/classes/java/main:/path/to/gson-2.11.0.jar" SendSms
```

`SendSms` sends one SMS and succeeds only after a matching `sms.sent` response
with a non-empty SMS ID. Its provider status (including `queued`) is logged and
does not change this acceptance criterion.

```sh
javac -cp "build/classes/java/main:/path/to/gson-2.11.0.jar" -d build/live-examples examples/CallSummarySms.java
java -cp "build/live-examples:build/classes/java/main:/path/to/gson-2.11.0.jar" CallSummarySms
```

`CallSummarySms` waits for `call.created`, answers only matching `user.turn`
events, and records request-correlated `answer.accepted` plus matching
`agent.turn` before accepting `call.completed`. It then gets the summary for
that same call ID and sends one SMS. `call.noAnswer`, `call.failed`, cancelled
status, gateway errors, disconnects, and timeouts fail the scenario. It never
sends the follow-up SMS unless the call completed and its summary was received.

## Standalone order regression test

This test is deliberately under `examples/`, so Gradle's normal test source set
does not compile or run it. With a Java 17 `JAVA_HOME` and the SDK classes built,
run:

```sh
"$JAVA_HOME/bin/javac" --release 17 \
  -cp "build/classes/java/main:/path/to/gson-2.11.0.jar" \
  -d build/live-examples \
  examples/SendSms.java examples/CallSummarySms.java examples/CallSummarySmsTest.java
"$JAVA_HOME/bin/java" \
  -cp "build/live-examples:build/classes/java/main:/path/to/gson-2.11.0.jar" \
  CallSummarySmsTest
```
