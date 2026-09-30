**English** | [한국어](README.ko.md)

# Live SDK examples

These examples make real provider requests. They are deliberately outside the
Gradle build and must never be run by CI. Use controlled test phone numbers only.

Every live-effect command refuses to connect unless this exact gate is present:

```sh
export ALLOW_LIVE_SIDE_EFFECTS=true
```

The example also validates every required environment variable before opening a
WebSocket connection.

| Variable | Used by | Description |
| --- | --- | --- |
| `TELLO_API_KEY` | `CallSummary` | Gateway API key. |
| `TELLO_URL` | `CallSummary` | Absolute `ws://` or `wss://` gateway `/sdk` URL. Use `wss://` outside a controlled local environment. |
| `LIVE_CALL_TO` | `CallSummary` | Controlled recipient who will answer the real call. |
| `LIVE_CALL_TIMEOUT_SECONDS` | `CallSummary` | Positive timeout; on expiry the example attempts one `cancel` and fails. |
| `LIVE_CALL_PROMPT` | `CallSummary`, optional | Prompt supplied when the live call is created. |
| `LIVE_CALL_REPLY` | `CallSummary`, optional | Deterministic reply for each `user.turn`. |

No example retries a call or answer command. A `requestId` matches the
response to the command; it is not an idempotency key.

## Compile and run

Build the SDK first so its classes and Gson are available, then compile the
chosen standalone example with the same classpath.

```sh
./gradlew classes
javac -cp "build/classes/java/main:/path/to/gson-2.11.0.jar" -d build/live-examples examples/CallSummary.java
java -cp "build/live-examples:build/classes/java/main:/path/to/gson-2.11.0.jar" CallSummary
```

`CallSummary` waits for `call.created`, answers only matching `user.turn`
events, and records request-correlated `answer.accepted` plus matching
`agent.turn` before accepting `call.completed`. It then gets the summary for
that same call ID. `call.noAnswer`, `call.failed`, cancelled status, gateway
errors, disconnects, and timeouts fail the scenario.

## Standalone order regression test

This test is deliberately under `examples/`, so Gradle's normal test source set
does not compile or run it. With a Java 17 `JAVA_HOME` and the SDK classes built,
run:

```sh
"$JAVA_HOME/bin/javac" --release 17 \
  -cp "build/classes/java/main:/path/to/gson-2.11.0.jar" \
  -d build/live-examples \
  examples/CallSummary.java examples/CallSummaryTest.java
"$JAVA_HOME/bin/java" \
  -cp "build/live-examples:build/classes/java/main:/path/to/gson-2.11.0.jar" \
  CallSummaryTest
```
