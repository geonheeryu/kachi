# 029. 전체 사이클 검증과 e2e-test 모듈

## 배경

키워드 구독부터 알림 발송까지의 파이프라인은 ADR 025가 정한 순서대로 이어졌다.

- ai-service가 요약 이벤트를 발행한다 (ADR 023).
- user-service가 구독과 채널 바인딩을 갖는다 (ADR 026).
- notification-routing이 구독자별로 fan-out해 접수 이벤트를 발행한다 (ADR 027).
- notification-worker가 발송 직전에 주소를 조회해 보낸다 (ADR 028).

그런데 이 흐름을 검증한 테스트는 모두 모듈 안에서 fake로 닫혀 있다.

- Kafka broker는 어느 모듈의 테스트에도 없다.
  notification-service·worker·routing은 `testcontainers-kafka`를 선언만 했고, ai-service는 발행 어댑터를 fake `KafkaTemplate`으로만 검증한다.
- 모듈 사이는 각자의 단위 테스트가 서로의 계약을 믿는 것으로만 이어져 있다.
  routing이 `ai.summary.created`를 실제로 소비해 `notification.requested`를 만들어내는지, notification-service가 그것을 받아 dispatch로 넘기는지, worker가 user-service에서 주소를 받아 발송을 끝내는지를 도는 테스트가 없다.
- 서비스 다섯 개를 함께 띄운 적도 없다.

정해야 할 것은 다음이다.

1. 모듈 경계의 broker 계약을 어디서 어떻게 검증하는가.
2. 전체 사이클을 한 테스트로 도는 자리를 어디에 두는가.
   테스트전략은 "테스트 편의를 위한 모듈 간 공유 모듈을 만들지 않는다"고 정해 뒀다.
3. 서비스 다섯 개를 테스트 안에서 어떻게 띄우는가.
4. 뉴스 수집·LLM·vendor처럼 바깥과 닿는 지점을 무엇으로 대신하는가.
5. 무거운 테스트를 평소의 `./gradlew test`에 넣을 것인가.
6. 실제 LLM과 실제 채널로 사람이 확인하는 절차를 어떻게 남기는가.

## 검토한 선택지

전체 사이클을 도는 자리에 대해 셋을 봤다.

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| notification-worker 같은 기존 모듈의 통합 테스트를 확장한다 | 새 모듈이 없다 | 그 모듈이 다른 서비스 네 개의 jar에 의존하게 된다. 모듈 경계가 테스트 때문에 무너진다 |
| 테스트 JVM 하나에 Spring 컨텍스트 다섯 개를 띄운다 | 컨테이너가 인프라 네 개뿐이라 빠르다 | 다섯 모듈의 자동설정(Security, JPA, Flyway, Kafka listener)이 한 classpath에 섞인다. 어느 컨텍스트가 어느 빈을 만들었는지 갈라내는 비용이 검증 가치보다 크다 |
| 별도 `e2e-test` 모듈이 서비스 다섯 개를 각각 컨테이너로 띄운다 | 실제 배포 단위와 같은 경계로 검증한다. 어느 모듈도 이 모듈에 의존하지 않는다 | 한 번 도는 데 몇 분이 걸린다. 서비스 jar를 컨테이너에 넣는 방식이 필요하다 |

세 번째를 택했다.

테스트전략이 금지한 것은 여러 모듈이 **의존하는** 테스트 공유 모듈이다.
`e2e-test`는 반대 방향이다.
모듈들의 산출물(bootJar)과 계약 모듈(`ai-contract`·`notification-contract`)만 소비하고, 다른 모듈은 이 모듈을 모른다.

## 결정

### 경계 통합 테스트는 각 모듈이 Kafka Testcontainers로 자기 계약을 검증한다

- **ai-service**
  - `AiSummaryCycleIntegrationTest`가 Mongo replica set과 Kafka 컨테이너 위에서 요약 -> outbox -> relay -> broker까지 돈다.
  - broker에서 읽은 레코드는 `ai-contract`의 `AiSummaryCreatedEvent`·`AiKeywordQuarantinedEvent`로 역직렬화한다.
    소비자가 쓰는 타입으로 읽어야 계약 검증이다.
  - 시나리오: 정상 발행, rate limit 조기 중단, 부분 실패 시 watermark 유지, 격리 이벤트 발행, stale PUBLISHING 회수, 같은 뉴스 재실행 시 이벤트 없음.
- **notification-routing**
  - `RoutingKafkaContractIntegrationTest`가 `ai.summary.created`·`ai.keyword.quarantined`를 소비해 `notification.requested`를 발행하는 계약을 검증한다.
  - 모르는 `schemaVersion`이 DLT로 가는 것을 검증한다.
- **notification-service와 worker의 소비 계약은 따로 만들지 않는다.**
  - 아래 e2e 시나리오가 덮는다.

LLM·collector·user-service 호출은 테스트 JVM 안의 HTTP stub이 받는다.

- stub은 모듈마다 둔다 (테스트전략의 fixture 규칙).
- 응답은 path별 큐라 "429 다음 200" 같은 순서를 표현한다.

### 전체 사이클은 `e2e-test` 모듈이 서비스 컨테이너 다섯 개로 돈다

```
[e2e-test JVM] LLM stub · collector stub · JWT 발급 · 단언용 Kafka consumer
        │ host.testcontainers.internal
────────┼──────────── Docker network ────────────
 mysql · mongo(rs0) · redis · kafka
 user-service · ai-service · notification-routing · notification-service · notification-worker
```

- **서비스는 각 모듈의 `bootJar`를 JDK 베이스 컨테이너에 복사해 `java -jar`로 띄운다.**
  - 이미지는 `amazoncorretto:21-al2023-headless`이고, 이미지를 빌드하지 않는다.
  - 배포판은 로컬 개발 JDK(`.sdkmanrc`의 Corretto 21)와 같게 두어 로컬 실행과 컨테이너 런타임이 같은 JDK다.
  - Dockerfile이 생기면 바꾸는 지점은 컨테이너 클래스 하나다.
- **프로파일은 `local`이고 인프라 주소와 토글은 환경변수로 덮는다.**
  - 모듈에 `application-e2e.yaml`을 심지 않는다.
    테스트 설정은 테스트 쪽에 있어야 한다.
- **collector-service는 띄우지 않는다.**
  - 실제 뉴스 provider 없이는 뉴스가 생기지 않고 시드 주입 API도 없다.
  - ai-service는 뉴스를 HTTP로 읽으므로 그 API를 stub한다.
- **vendor는 worker의 `MockNotificationSender`다.**
  - Slack·Discord 주소는 user-service가 `https://hooks.slack.com/`·`https://discord.com/api/webhooks/` 접두사를 강제하므로 stub URL을 바인딩에 넣을 수 없다.
  - 발송 결과는 세 곳으로 단언한다.
    Mongo의 알림 상태(`SENT`·`SUPPRESSED`), Redis의 `notification:sent:{requestId}`·`notification:recipient:{recipientId}:{channel}` 키, worker의 `kachi_notification_recipient_resolve_total` 지표.
  - 주소가 sender까지 전달되는 것은 worker 통합 테스트가 이미 검증한다.
- **사용자 등록·바인딩·구독은 user-service의 실제 API로 한다.**
  - 공개 API는 JWT가 필요하고 비밀번호 로그인이 없으므로, 테스트가 user-service에 넘긴 secret으로 access token을 직접 만든다.
  - DB에 직접 넣는 방식은 도메인 검증을 거치지 않으므로 쓰지 않는다.
- **단언의 앵커는 `requestId = "sum:{summaryId}:u:{userId}:c:{channel}"`이다 (ADR 025).**
  - `summaryId`는 broker의 `ai.summary.created` 레코드에서 읽는다.

시나리오는 넷이다.

1. 요약 알림이 3채널로 `SENT`.
2. 접수 뒤 바인딩이 없는 수신자는 `SUPPRESSED`.
3. 같은 요약 이벤트를 다시 넣어도 접수가 늘지 않음 (routing 멱등).
4. 키워드 격리 알림이 관리자에게 감.

스킵 시나리오(2)는 접수 이벤트를 외부 producer처럼 직접 넣는다.
user-service의 구독 조회가 ACTIVE 바인딩이 있는 채널만 돌려주므로 routing은 이 경우를 만들지 않는다.
접수와 발송 사이에 바인딩이 해지된 상황은 그렇게만 재현된다.

### `e2eTest`는 별도 task이고 기본 `test`에서 뺀다

`./gradlew test`는 그대로 서비스·계약 모듈만 돈다.
e2e는 `./gradlew :e2e-test:e2eTest`로 따로 돈다.

- 컨테이너 아홉 개를 띄우는 데 몇 분이 걸려 평소의 `./gradlew test`에 넣기에는 무겁다.
- JUnit 태그로 거르는 안은 아홉 모듈 전부에 필터를 심어야 하고 선례가 없다.
  task 분리는 모듈 하나의 빌드 파일로 닫힌다.
- CI가 생기면 별도 job으로 붙인다.

Docker가 없으면 e2e도 다른 통합 테스트처럼 실패한다.
`assumeTrue`로 skip하지 않는다.

### 인프라 부재는 실패, ~~secret 부재는 skip~~

테스트전략은 "Docker가 없으면 skip하지 않고 실패"라고 정했다.
인프라는 테스트가 직접 띄우는 것이라, 없으면 환경이 잘못된 것이다.

~~worker의 `*RealIntegrationTest`는 webhook secret이 없으면 `assumeTrue`로 skip한다.~~ ADR 030에서 폐지.

- ~~외부 secret은 테스트가 만들 수 없는 것이라, 없으면 그 테스트가 검증 대상이 아닌 것이다.~~
- ~~실 vendor 테스트를 태그로 분리하는 안은 접었다. 셋뿐이고 secret 유무가 이미 스위치다.~~

실제 provider나 vendor를 부르는 테스트는 별도 소스셋 `src/realTest`에 두고, 일부러 실행한 테스트가 secret이 없으면 skip이 아니라 실패한다.
ai-service는 옮겼고 worker의 `*RealIntegrationTest`는 같은 규칙으로 옮길 대상이다.

### 실제 LLM·실제 채널 확인은 사람이 하는 스모크다

테스트전략대로 `src/test`는 실제 provider와 vendor를 부르지 않는다. 모델이 실제로 있는지는 `src/realTest`가 본다(ADR 030).
사람이 끝까지 확인하는 절차로 두 가지를 둔다.

- `scripts/cycle.sh`가 인프라 -> Mongo index -> user-service -> 나머지 순으로 띄운다.
- `docs/전체-사이클-스모크.md`가 실제 LLM과 3채널 webhook으로 수집 -> 요약 -> 수신을 확인하는 체크리스트다.

요약 알림을 실주소로 받으려면 실주소 바인딩을 가진 사용자가 키워드를 구독해야 한다.
그래서 local seed가 관리자 계정에도 seed 키워드를 3채널로 구독시킨다.

### e2e가 찾은 결함

모듈 안에서는 fake가 대신하던 경계를 실제로 잇자 두 가지가 드러났다.
둘 다 이 결정과 같은 변경에서 고쳤고, 단위 테스트를 남겼다.

- **worker의 dispatch listener 컨테이너가 MANUAL ack가 아니었다.**
  - listener는 처리 결과를 보고 직접 `acknowledge()`하는데, 전용 container factory를 직접 만들면서 `spring.kafka.listener.ack-mode`가 적용되지 않았다.
    Boot는 그 설정을 자기가 만드는 기본 factory에만 적용한다.
  - 모든 dispatch 레코드가 `No Acknowledgment available`로 실패해 DLT로 갔다.
  - factory가 ack 모드를 직접 선언하게 했다.
- **claim 경합을 중복으로 보고 ack했다.**
  - notification-service는 dispatch 레코드를 broker에 보낸 뒤 알림을 `PUBLISHED`로 바꾸는 transaction을 커밋한다.
    worker가 그 사이에 레코드를 받으면 claim(`PUBLISHED` 조건 CAS)은 실패하고, 재조회는 커밋된 `PUBLISHED`를 본다.
  - 이것을 "다른 worker가 처리 중"과 같은 중복으로 보고 ack하면 알림이 `PUBLISHED`에 영구히 남는다.
    stale 회수는 `PROCESSING`만 본다.
  - claim에 실패했어도 재조회한 상태가 아직 claim 가능한 상태(`REQUESTED`·`PUBLISHED`·`RETRY_WAIT`)면 재시도 신호를 내도록 바꿨다.
    다음 시도의 CAS가 다시 가른다.

## 검토한 대안

- **WireMock 컨테이너로 LLM·collector를 stub한다.**
  - 서비스 컨테이너가 host를 거치지 않아도 되지만 새 의존성이 생긴다.
  - 기존 `TestVendorServer` 패턴(reactor-netty, 의존성 없음)으로 충분하다.
    host port는 `Testcontainers.exposeHostPorts`로 연다.
- **Dockerfile 또는 buildpack으로 이미지를 만든다.**
  - 프로덕션 이미지 결정을 테스트가 선점하게 된다.
  - bootJar 복사로 같은 경계를 얻는다.
- **user-service DB에 구독·바인딩을 직접 넣는다.**
  - JWT가 필요 없지만 주소 암호화·정규화·바인딩 생명주기를 거치지 않는다.
- **스모크의 요약 알림 수신자를 seed user 주소 설정으로 푼다.**
  - 관리자 주소 설정이 이미 있으므로 설정을 하나 더 늘리는 대신 관리자를 구독시켰다.
  - OAuth 로그인 후 curl로 구독하는 절차도 가능하지만 매번 브라우저를 거친다.

## 트레이드오프

- 장점:
  - 계약 topic과 API만으로 이어진 다섯 프로세스가 실제로 한 사이클을 완주하는지 코드로 확인한다.
  - 어느 서비스 모듈도 테스트 모듈을 모른다.
  - 홉별 멱등(요약 재사용, routing 중복 fan-out 방지)이 fake가 아니라 실제 저장소와 broker 위에서 증명된다.
- 단점:
  - e2e 한 번이 몇 분이라 매 커밋 검증 루프에는 들어가지 않는다.
    계약이 깨졌을 때 알아채는 시점이 늦다.
  - 서비스마다 환경변수 이름이 이 모듈에 박힌다.
    yaml 키가 바뀌면 e2e가 기동 단계에서 깨지는데, 그것이 이 테스트의 역할이기도 하다.
  - vendor를 mock으로 보내므로 실제 webhook 형식은 스모크에서만 확인된다.

## 운영 제약

- e2e와 통합 테스트는 Docker Desktop과 `DOCKER_HOST` 지정이 필요하다 (`docs/테스트전략.md`).
- 스모크를 같은 뉴스로 다시 돌리면 `summaryId`가 같아 이벤트가 나지 않고, 24시간 안이면 `notification:sent:{requestId}` 가드에 걸린다.
  재실행 절차는 스모크 문서에 있다.

## 후속

- CI가 생기면 `e2eTest`를 별도 job으로 붙인다.
- Q1(requestId 조회 API)이 열리면 e2e의 Mongo 직접 조회를 그 API로 바꾼다.
- Dockerfile이 생기면 서비스 컨테이너를 `ImageFromDockerfile`로 바꾼다.
