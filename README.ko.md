[English](README.md) | **한국어**

# tello-java

Java용 Tello SDK. API 게이트웨이(api-gateway) `/sdk` 엔드포인트에 붙는 얇은
**WebSocket** 실시간 클라이언트와, 공개 자원 API(도구·에이전트·크루·크루 자동
생성·통화 초안)를 다루는 **REST** 클라이언트를 담고 있습니다. WebSocket 쪽에서는
SDK가 대화의 두뇌를 맡습니다. 게이트웨이는 진행 중인 통화에서 상대방이 말한 턴을
실시간으로 넘겨주고, 핸들러가 만든 답변은 다시 통화로 전달됩니다.

> 저장소: `tello-java` · Maven 아티팩트: `io.telloai:tello-sdk` · 패키지: `io.telloai`
>
> 실시간 클라이언트(`TelloClient`)는 WebSocket만 씁니다. 프로토콜 계약은
> [`docs/protocol/sdk-ws.v1.md`](docs/protocol/sdk-ws.v1.md)에 있습니다. REST 자원
> API는 별도 클라이언트 `TelloApi`입니다(§6). webhook은 제공하지 않습니다.
> `tello-python`에서 이식했고, 게이트웨이 기준 동작이 1:1로 같습니다.

## 1. 설치

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

Java 17 이상이 필요합니다. 런타임 의존성은 Gson 하나뿐이고, WebSocket 전송은
내장 `java.net.http.WebSocket`을, REST 클라이언트는 내장
`java.net.http.HttpClient`를 씁니다.

## 2. API 키

키는 in-band로 전달됩니다. 소켓이 열리면 첫 애플리케이션 프레임으로 `auth`
프레임(`token` 필드에 원본 키)을 보내고, 서버가 `auth.ok`를 반환한 뒤에야
`connect()`가 완료됩니다. 키는 WS 업그레이드 요청에 실리지 않으므로
(`Authorization` 헤더도, `?token=` query도 없음) URL·로그·예외 메시지에 남지
않습니다. 전부 내부 처리라 직접 호출할 일은 없습니다. 키는 인자로 직접 넘기거나
`TELLO_API_KEY` / `TELLO_URL` 환경 변수로 지정하면 됩니다. 인자 없는
`new TelloClient()`가 이 값들을 읽습니다. URL을 인자로도 `TELLO_URL`로도 주지
않으면 `wss://api.telloai.io/sdk`에 연결합니다.

키가 거부되거나(`unauthenticated` 오류 프레임 또는 4401 종료) `auth.ok` 대기가
타임아웃되면(연결 타임아웃, 기본 10초) `connect()` / `connectBlocking()`이
`AuthenticationException`으로 실패합니다. 전송 계층이 실패하면
`ConnectionClosedException`으로 실패합니다. 소켓을 열지 못한 경우(연결 거부,
도달 불가, TLS·업그레이드 실패, 연결 타임아웃), `auth` 프레임을 보내지 못한
경우, `auth.ok` 전에 다른 이유로 연결이 닫힌 경우가 여기에 해당합니다.
소켓을 열거나 프레임을 보내다 실패했다면 `getCause()`가 원래 오류를 돌려줍니다.
`connectBlocking()`은 이 예외들을 그대로 던지고, `connect()`는 이 예외들로 예외
완료됩니다.

## 3. 연결 + 통화 시작

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

`waitClosed()`는 현재 통화가 끝날 때까지 블로킹합니다. 종단 이벤트
(`call.completed`, `call.noAnswer`, `call.failed`, 또는 status가 `cancelled`인
`call.statusChanged`), 통화를 끝내는 오류(§5 참고), 연결 종료 중 하나가 오면
끝납니다. 이미 기다리고 있던 `waitClosed()`는 핸들러가 종단 이벤트에서 후속
통화를 시작해도 자기 통화가 끝나면 반환합니다. 후속 통화를 기다리려면
`waitClosed()`를 다시 호출하세요. `cancel()` 뒤에는 게이트웨이가 status가
`cancelled`인 `call.statusChanged`를 그 통화의 종단 이벤트로 보내고,
`previousStatus`에는 취소 직전 상태가 담깁니다.

## 5. 오류 처리

게이트웨이 오류 프레임은 예외와 1:1로 대응됩니다. 전부 `TelloException`을
상속하며 unchecked입니다:

| 게이트웨이 `code` | 예외 |
| --- | --- |
| `unauthenticated` | `AuthenticationException` (4401 종료 포함) |
| `toRequired` | `ValidationException` |
| `callIdRequired` | `ValidationException` |
| `dtmfDigitsRequired` | `ValidationException` |
| `dtmfDigitsInvalid` | `ValidationException` |
| `callNotFound` | `ValidationException` |
| `callNotCompleted` | `ValidationException` |
| `callAlreadyActive` | `CallAlreadyActiveException` |
| `noActiveCall` | `NoActiveCallException` |
| `callRejected` | `CallRejectedException` (`.question` 포함) |
| `internalError` | `TelloServerException` |

표에 없는 코드는 `TelloServerException`으로 떨어집니다.

모든 오류는 게이트웨이 코드를 `.code`에 담고 있습니다. **분기는 `.code`로 하고
`.getMessage()`로는 하지 마세요.** 메시지는 게이트웨이가 다시 쓸 수 있는 표시용
문자열입니다.

`createCall`은 통화가 만들어지기 전에 거부될 수도 있습니다. 이 경우
`call.created`도 `callId`도 과금도 없습니다. 게이트웨이는 재시도하지 않으므로
재시도 정책은 호출자 몫입니다.

| 게이트웨이 `code` | 예외 | 대응 |
| --- | --- | --- |
| `insufficientCredit` | `CallRefusedException` | 충전을 안내합니다. 재전송해도 소용없습니다 |
| `concurrentLimitExceeded` | `CallRefusedException` | 자기 통화가 하나 끝나기를 기다렸다가 재시도합니다 |
| `callerNotVerified` | `CallRefusedException` | 번호 인증을 안내합니다. 재전송해도 소용없습니다 |
| `noRepresentativeNumber` | `CallRefusedException` | 발신 번호 설정을 안내합니다. 재전송해도 소용없습니다 |
| `callProviderUnauthorized` | `CallProviderException` | 서비스 장애로 보고합니다. 재전송은 도움이 안 됩니다 |
| `callProviderDraining` | `CallProviderException` | 나중에 재시도합니다 |
| `callProviderUnavailable` | `CallProviderException` | 나중에 재시도합니다 |
| `callSetupFailed` | `CallProviderException` | 실패로 보고합니다 |

명령 오류는 소켓을 닫지 않고 `EventType.ERROR` 구독자에게 전달됩니다.
`waitClosed()`를 끝내는 오류는 현재 통화의 `createCall`들에 대한 오류뿐이며,
`waitClosed()`가 그 오류를 다시 던집니다. `call.created` 전의 거부와 그 뒤에
통화 자체가 실패한 경우가 여기에 해당합니다. 게이트웨이는 명령마다 `requestId`를
오류에 그대로 돌려주므로 `createCall`은 항상 `requestId`를 싣습니다. 비어 있지
않은 `requestId`를 넘기면 그 값을, 아니면 SDK가 만든 UUID를 씁니다. 명령마다
`requestId`를 따로 쓰고, `createCall`의 `requestId`를 다른 명령에 다시 쓰지
마세요. 그 값을 에코한 오류는 대기를 끝냅니다.

두 코드는 따로 다룹니다:

- `callAlreadyActive`는 통화를 연 `createCall`에 대한 응답일 때만 대기를
  끝냅니다. 이때 게이트웨이는 직전 통화를 아직 정리하는 중이라(종단 이벤트 뒤에도
  잠시 붙잡고 있음) 이 통화는 시작되지 않았습니다. 잠시 뒤 `createCall`을 다시
  보내세요. 통화 도중 보낸 `createCall`에 대한 `callAlreadyActive`는
  `EventType.ERROR` 이벤트로만 전달되고, 진행 중인 통화는 계속됩니다.
- `noActiveCall`은 대기를 끝내지 않습니다.

그 밖의 명령(`answer`, `sendDtmf`, `cancel`, `getSummary`) 오류는
`EventType.ERROR` 이벤트로만 전달됩니다. 통화는 계속되고 `waitClosed()`도 계속
기다립니다. 통화 도중 연결이 끊기면 `ConnectionClosedException`, 다른 연결에
세션을 빼앗기면(4429 종료) `SessionReplacedException`입니다.

`ErrorEvent`에는 원래의 `code`, `message`, `question`이 담겨 있습니다.
`waitClosed()`가 던지는 것과 같은 타입의 예외로 다루려면 `io.telloai.errors`의
`Errors.exceptionFor(code, message, question)`으로 바꾸세요:

```java
client.on(EventType.ERROR, e -> {
    ErrorEvent err = (ErrorEvent) e;
    TelloException error = Errors.exceptionFor(err.code, err.message, err.question);
    if (error instanceof ValidationException) {
        // 예: sendDtmf의 dtmfDigitsInvalid. 입력을 고치면 되고 통화는 계속됩니다
    }
});
```

WS 수준 ping heartbeat는 게이트웨이가 주도하고, pong은 자동으로 나갑니다.
재연결이나 세션 재개 프로토콜은 없습니다. 비정상 종료가 나면 재연결이 필요한
상황으로 보고 통화를 처음부터 다시 시작하세요.

## 6. REST 자원 API

`TelloApi`는 공개 REST API(`https://api.telloai.io/v1`)로 계정의 자원을 다룹니다.
`TelloClient`와 별개이며 WebSocket 연결이 필요 없습니다.

> **쓰기는 즉시 운영에 반영됩니다.** 도구·에이전트·크루를 만들거나 고치거나 지우면
> 진행 중인 통화에도 바로 배포됩니다. **`callDrafts().confirm(...)`은 실제로 과금되는
> 전화를 겁니다.**

```java
TelloApi api = new TelloApi();          // $TELLO_API_KEY, $TELLO_API_URL
JsonObject agents = api.agents().list().join();

JsonObject tool = new JsonObject();
tool.addProperty("type", "endCall");
tool.addProperty("name", "통화 종료");
tool.addProperty("functionName", "end_call");
JsonObject created = api.tools().create(tool, "create-end-call-1").join();
```

- **키와 URL**: `new TelloApi(apiKey)` / `new TelloApi(apiKey, apiUrl)`로 넘기거나
  `TELLO_API_KEY` / `TELLO_API_URL` 환경 변수를 씁니다(인자가 우선). URL이 둘 다
  없으면 `https://api.telloai.io/v1`을 씁니다. URL은 `https`여야 하고, `http`는
  `localhost`·`127.0.0.1`·`::1`에만 허용합니다. 키는 `Authorization: Bearer <key>`로만
  보내고 `toString()`이나 오류 메시지에 나오지 않습니다. 모든 요청에
  `User-Agent: tello-java-sdk/<버전>`이 붙습니다.
- **타임아웃**: 30초. 통화 초안 만들기·고치기·확정과 크루 자동 생성은 120초.
  `TelloApi.builder().apiKey(k).timeout(d).longTimeout(d).build()`로 바꿀 수 있습니다.
- **자동 재시도는 하지 않습니다.** 만들기를 안전하게 다시 보내려면 멱등 키를 주고
  같은 키로 다시 보내세요.
- **본문과 결과**는 공개 REST 문서 모양 그대로인 Gson `JsonObject`이고, SDK가 모르는
  칸도 잃지 않습니다. 모든 메서드는 `CompletableFuture`를 돌려줍니다. 빈 id나 잘못된
  멱등 키(출력 가능한 ASCII 1~255자, 쉼표 없음)는 요청 없이 `IllegalArgumentException`을
  던집니다.

| 메서드 | HTTP | 결과 |
| --- | --- | --- |
| `tools().list()` / `list(limit, cursor)` | `GET /tools` | `{data, nextCursor}` |
| `tools().get(id)` | `GET /tools/{id}` | 도구 |
| `tools().create(body[, idempotencyKey])` | `POST /tools` (201) | 도구 |
| `tools().update(id, patch)` | `PATCH /tools/{id}` | 도구 |
| `tools().delete(id)` | `DELETE /tools/{id}` (204) | `null` |
| `agents().list()` | `GET /agents` | `{data}` |
| `agents().get/create/update/delete` | `/agents[/{id}]` | 에이전트 / `null` |
| `crews().list()` | `GET /crews` | `{data}` |
| `crews().get/create/update/delete` | `/crews[/{id}]` | 크루 / `null` |
| `crews().generate(input[, idempotencyKey])` | `POST /crews/generate` (202, 기존 작업이면 200) | 작업 |
| `crews().generateAndWait(input[, pollInterval, timeout, idempotencyKey])` | 생성 후 폴링 | 끝난 작업 |
| `crewGenerations().get(id)` | `GET /crew-generations/{id}` | 작업 |
| `callDrafts().create(body[, idempotencyKey])` | `POST /call-drafts` (201) | 초안 |
| `callDrafts().revise(draftId, request[, idempotencyKey])` | `POST /call-drafts/{id}/revise` | 새 초안(새 id) |
| `callDrafts().confirm(draftId[, idempotencyKey])` | `POST /call-drafts/{id}/confirm` (201) | 통화 |

`generateAndWait`는 기본으로 2초마다 최대 600초 동안 작업을 조회하고, `running`이
아니게 되면 그 작업으로 완료됩니다. `failed` 작업도 예외가 아니라 결과로 돌려주므로
`status`로 분기하세요. 시간이 다 되면 `CrewGenerationTimeoutException`
(`TimeoutException`, `.generationId`에 작업 id. 작업은 서버에서 계속 돕니다)으로
실패합니다. 초안의 모든 상태(`pendingConfirmation`·`needsClarification`·`refused`·
`confirmed`)는 성공 결과입니다.

`confirm`은 서버가 요구하므로 항상 `Idempotency-Key`를 보냅니다. 준 키가 없으면
UUID를 만들어 보내고, 실패한 오류에 그 키를 싣습니다. 다시 보낼 때는 반드시 그
키로 보내야 응답을 잃어도 통화가 두 번 걸리지 않습니다.

### REST 오류

실패한 요청은 future를 예외로 완료합니다(`join()`은 오류를 `CompletionException`으로
감쌉니다):

- `TelloApiException` — 서버가 오류로 답했습니다. `.status`는 HTTP 상태,
  `.code`는 서버 `error` 원문(snake_case, 공개 오류 표와 같음), `.getMessage()`는
  메시지, `.data`는 `data` 객체 또는 `null`, `.idempotencyKey`는 요청에 실은 키 또는
  `null`입니다. API 오류 봉투가 아닌 응답(프록시 페이지 등)은 `code`가
  `http_<상태>`, 문서에 없는 성공 상태나 JSON 객체가 아닌 성공 본문은
  `invalid_response`입니다.
- `TelloApiConnectionException` — 응답을 받지 못했습니다(연결 실패·타임아웃,
  `getCause()`가 전송 오류). **쓰기라면 이미 반영됐을 수 있습니다.** 다시 보내기
  전에 get이나 list로 확인하고, 같은 멱등 키로 보내세요.

둘 다 `TelloException`을 상속합니다. `code`로 분기하세요:

| 상태 | `code` | 뜻 |
| --- | --- | --- |
| 401 | `unauthenticated` | 키가 없거나 틀렸거나 폐기됐습니다 |
| 403 | `insufficient_scope` | 이 요청에 필요한 권한이 키에 없습니다 |
| 404 | `tool_not_found` / `agent_not_found` / `crew_not_found` | 계정에 그 자원이 없습니다 |
| 409 | `tool_in_use` / `agent_in_use` / `crew_in_use` | 아직 다른 곳에서 쓰고 있습니다. `data`를 보세요 |
| 409 | `crew_generation_in_progress` | 다른 크루를 만드는 중입니다. `data.id`가 그 작업입니다 |
| 404 | `draft_not_found` | 그 통화 초안이 없습니다 |
| 409 | `idempotency_key_reused` | 같은 키를 다른 요청에 썼습니다 |
| 502 | `write_outcome_unknown` | 반영됐는지 알 수 없습니다 |

전체 목록은 공개 REST 오류 문서에 있습니다.

```java
try {
    api.tools().get("tool_123").join();
} catch (CompletionException e) {
    if (e.getCause() instanceof TelloApiException err && err.code.equals("tool_not_found")) {
        // 새로 만들거나 없는 도구로 보고합니다
    } else {
        throw e;
    }
}
```

## 7. 예제

독립 실행형 프로그램이 [`examples/`](examples/README.ko.md)에 있습니다.
`BasicCall`은 연결해서 통화 1건을 걸고 각 턴에 응답하고, `CallSummary`는
게이트로 막아 둔 라이브 시나리오를 `call.summary`까지 진행합니다. 둘 다 Gradle
빌드 바깥에 있어서, 빌드된 SDK 클래스를 classpath로 잡고 `javac`으로 직접
컴파일합니다.

두 예제 모두 실제 통화를 겁니다. 먼저
[`examples/README.ko.md`](examples/README.ko.md)를 읽으세요.

## 8. 버전 호환성

`io.telloai:tello-sdk 0.1.x`는 Tello WS 프로토콜 `1.0`을 구현합니다.

프레임 계약 전문은 [`docs/protocol/sdk-ws.v1.md`](docs/protocol/sdk-ws.v1.md)에
있고, [`docs/events/sdk-events.v1.schema.json`](docs/events/sdk-events.v1.schema.json)과
[`docs/errors/errors.v1.json`](docs/errors/errors.v1.json)이 함께 있습니다. 이
세 파일은 게이트웨이 구현 옆에 있는 정본에서 복사해 온 생성물입니다. 읽는 건
여기서, 고치는 건 정본에서 합니다.
