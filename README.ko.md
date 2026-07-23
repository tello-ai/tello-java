[English](README.md) | **한국어**

# tello-java

Java용 Tello SDK. turn-provider-gateway `/sdk` 엔드포인트에 붙는 얇은
**WebSocket** 실시간 클라이언트입니다. SDK가 대화의 두뇌를 맡습니다.
게이트웨이는 진행 중인 통화에서 상대방이 말한 턴을 실시간으로 넘겨주고,
핸들러가 만든 답변은 다시 통화로 전달됩니다.

> 저장소: `tello-java` · Maven 아티팩트: `ai.tello:tello-sdk` · 패키지: `ai.tello`
>
> 전송 계층은 WebSocket뿐입니다. REST나 webhook은 제공하지 않습니다. 프로토콜
> 계약은 [`docs/protocol/sdk-ws.v1.md`](docs/protocol/sdk-ws.v1.md)에 있습니다.
> `tello-python`에서 이식했고, 게이트웨이 기준 동작이 1:1로 같습니다.

## 1. 설치 (Gradle)

```kotlin
dependencies {
    implementation("ai.tello:tello-sdk:0.1.0")
}
```

Java 17 이상이 필요합니다. 런타임 의존성은 Gson 하나뿐이고, WebSocket 전송은
내장 `java.net.http.WebSocket`을 씁니다.

## 2. API 키

키는 in-band로 전달됩니다. 소켓이 열리면 첫 애플리케이션 프레임으로 `auth`
프레임(`token` 필드에 원본 키)을 보내고, 서버가 `auth.ok`를 반환한 뒤에야
`connect()`가 완료됩니다. 키는 WS 업그레이드 요청에 실리지 않으므로
(`Authorization` 헤더도, `?token=` query도 없음) URL·로그·예외 메시지에 남지
않습니다. 전부 내부 처리라 직접 호출할 일은 없습니다. 키는 인자로 직접 넘기거나
`TELLO_API_KEY` / `TELLO_URL` 환경 변수로 지정하면 됩니다. 인자 없는
`new TelloClient()`가 이 값들을 읽습니다.

키가 거부되거나(`unauthenticated` 오류 프레임 또는 4401 종료) `auth.ok` 대기가
타임아웃되면 `connect()` / `connectBlocking()`이 `AuthenticationException`
(타임아웃이면 `ConnectionClosedException`)으로 실패합니다.

## 3. 연결 + 통화 시작

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

`connect()`는 `CompletableFuture<TelloClient>`를 반환하고, `connectBlocking()`은
블로킹 편의 메서드입니다. 명령(`createCall` / `answer` / `sendDtmf` / `cancel` /
`getSummary`)은 모두 `CompletableFuture<Void>`를 반환합니다.

## 4. 실시간 턴 이벤트 (pub/sub)

`client.on(type, Consumer<TelloEvent>)`으로 이벤트 타입별 핸들러를 등록하고,
구체 타입으로 캐스팅해서 씁니다:

| `EventType` | 값 | 구체 타입 / 필드 |
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
| `DISCONNECTED` | `disconnected` | `Event` (SDK 자체 이벤트. WS가 닫힐 때 발생) |

`AUTH_OK`는 `connect()`가 내부에서 소비하며 밖으로 다시 emit 하지 않습니다.

`waitClosed()`는 통화가 종료 상태(또는 cancelled 상태)에 이르거나 연결이 닫힐
때까지 블로킹합니다.

## 5. 오류 처리

게이트웨이 오류 프레임은 예외와 1:1로 대응됩니다. 전부 `TelloException`을
상속하며 unchecked입니다:

| 게이트웨이 `code` | 예외 |
| --- | --- |
| `unauthenticated` | `AuthenticationException` (4401 종료 포함) |
| `toRequired` | `ValidationException` |
| `callAlreadyActive` | `CallAlreadyActiveException` |
| `noActiveCall` | `NoActiveCallException` |
| `callRejected` | `CallRejectedException` (`.question` 포함) |
| `internalError` | `TelloServerException` |

그 밖의 코드는 `callNotFound`, `callNotCompleted`를 포함해 모두
`TelloServerException`으로 떨어집니다.

명령 오류는 소켓을 닫지 않고 `EventType.ERROR` 구독자에게도 전달됩니다. 실패한
`createCall`이 멈춘 채 남지 않도록 `waitClosed()`가 그 오류를 다시 던집니다.
통화 도중 연결이 끊기면 `ConnectionClosedException`, 다른 연결에 세션을
빼앗기면(4429 종료) `SessionReplacedException`입니다.

WS 수준 ping heartbeat는 게이트웨이가 주도하고, pong은 자동으로 나갑니다.
재연결이나 세션 재개 프로토콜은 없습니다. 비정상 종료가 나면 재연결이 필요한
상황으로 보고 통화를 처음부터 다시 시작하세요.

## 6. 예제

독립 실행형 프로그램이 [`examples/`](examples/README.ko.md)에 있습니다.
`BasicCall`은 연결해서 통화 1건을 걸고 각 턴에 응답하고, `CallSummary`는
게이트로 막아 둔 라이브 시나리오를 `call.summary`까지 진행합니다. 둘 다 Gradle
빌드 바깥에 있어서, 빌드된 SDK 클래스를 classpath로 잡고 `javac`으로 직접
컴파일합니다.

두 예제 모두 실제 통화를 겁니다. 먼저
[`examples/README.ko.md`](examples/README.ko.md)를 읽으세요.

## 7. 버전 호환성

`ai.tello:tello-sdk 0.1.x`는 Tello WS 프로토콜 `1.0`을 구현합니다.
