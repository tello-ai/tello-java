[English](README.md) | **한국어**

# 라이브 SDK 예제

이 예제들은 실제 프로바이더 요청을 발생시킵니다. 일부러 Gradle 빌드 바깥에
두었고, CI에서는 절대 돌리면 안 됩니다. 통제된 테스트 전화번호만 쓰세요.

라이브 부작용이 있는 명령은 아래 게이트가 정확히 켜져 있지 않으면 연결 자체를
거부합니다:

```sh
export ALLOW_LIVE_SIDE_EFFECTS=true
```

WebSocket 연결을 열기 전에 필수 환경 변수를 모두 검증합니다.

| 변수 | 사용 예제 | 설명 |
| --- | --- | --- |
| `TELLO_API_KEY` | `CallSummary` | 게이트웨이 API 키. |
| `TELLO_URL` | `CallSummary` | 절대 경로 `ws://` 또는 `wss://` 게이트웨이 `/sdk` URL. 통제된 로컬 환경 밖에서는 `wss://`를 쓰세요. |
| `LIVE_CALL_TO` | `CallSummary` | 실제 통화를 받을 통제된 수신자. |
| `LIVE_CALL_TIMEOUT_SECONDS` | `CallSummary` | 양수 타임아웃. 만료되면 `cancel`을 한 번만 시도하고 실패 처리. |
| `LIVE_CALL_PROMPT` | `CallSummary`, 선택 | 라이브 통화를 만들 때 넘길 prompt. |
| `LIVE_CALL_REPLY` | `CallSummary`, 선택 | 각 `user.turn`에 보낼 고정 응답. |

어느 예제도 통화나 응답 명령을 재시도하지 않습니다. `requestId`는 명령과 응답을
짝지어 주는 값이며, 멱등성 키가 아닙니다.

## 컴파일 및 실행

먼저 SDK를 빌드해 클래스와 Gson을 준비하고, 같은 classpath로 원하는 독립 예제를
컴파일합니다.

```sh
./gradlew classes
javac -cp "build/classes/java/main:/path/to/gson-2.11.0.jar" -d build/live-examples examples/CallSummary.java
java -cp "build/live-examples:build/classes/java/main:/path/to/gson-2.11.0.jar" CallSummary
```

`CallSummary`는 `call.created`를 기다리고, 짝이 맞는 `user.turn`에만 응답합니다.
request와 짝이 맞는 `answer.accepted`와 그에 대응하는 `agent.turn`을 기록한
뒤에야 `call.completed`를 받아들이고, 그다음 같은 call ID로 요약을 조회합니다.
`call.noAnswer`, `call.failed`, cancelled 상태, 게이트웨이 오류, 연결 끊김,
타임아웃은 모두 시나리오 실패로 처리합니다.

## 독립 실행형 순서 회귀 테스트

이 테스트는 일부러 `examples/` 아래에 두어서 Gradle의 일반 test source set이
컴파일하지도, 실행하지도 않습니다. Java 17 `JAVA_HOME`과 빌드된 SDK 클래스를
준비한 뒤 실행하세요:

```sh
"$JAVA_HOME/bin/javac" --release 17 \
  -cp "build/classes/java/main:/path/to/gson-2.11.0.jar" \
  -d build/live-examples \
  examples/CallSummary.java examples/CallSummaryTest.java
"$JAVA_HOME/bin/java" \
  -cp "build/live-examples:build/classes/java/main:/path/to/gson-2.11.0.jar" \
  CallSummaryTest
```
