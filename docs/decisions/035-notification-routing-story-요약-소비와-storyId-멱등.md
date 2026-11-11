# 035. notification-routing의 story 요약 소비와 storyId 멱등

## 배경

ADR 031은 요약과 알림의 단위를 story로 정했고, ADR 034는 ai-service가 story 요약 버전을 `ai.summary.created`에 schemaVersion 2 계약으로 발행하게 했다.
notification-routing은 이 계약을 읽어 story의 키워드를 구독한 수신자에게 알림을 보낸다.

story 하나는 키워드 여럿을 가진다.
같은 사용자가 그중 둘을 같은 채널로 구독했으면 그 사용자는 story 한 버전에 한 통만 받아야 한다(ADR 031 트레이드오프).
구독자 규모는 canonical 키워드 5,000개, 구독 200,000건을 전제로 한다(ADR 031).
인기 키워드가 겹친 story는 수신자가 수만 명일 수 있어 한 번의 조회와 한 번의 발행으로 끝내기 어렵다.

정해야 할 것은 다음이다.

1. routing이 읽는 계약과 버전.
2. 알림 한 통을 가리키는 멱등 키.
3. 수신자를 어떻게 읽고, 도중에 죽으면 어디서 다시 시작하는가.
4. 본문에 보일 키워드와 그 본문을 만드는 계층.
5. 알림 출처에 남길 값.
6. story 격리 알림의 수신자와 경로.
7. ai-service의 발행 스위치.
8. story 경로에서 쓰지 않는 키워드 요약 경로와 그 API.

## 결정

### 소비 계약은 schemaVersion 2 하나다

- routing은 `ai.summary.created`를 `ai-contract`의 `AiSummaryCreatedEvent`(schemaVersion 2)로만 읽는다.
  - 필드는 summaryId, storyId, version, keywords, developmentKind, title, content, sentiment, sourceNewsCount, provider, model, promptVersion, createdAt이다.
  - keywords는 story에 붙은 canonicalKey 목록이다.
- schemaVersion이 2가 아닌 레코드는 재시도 없이 `notification.routing.dlt`로 간다. 같은 레코드를 다시 받아도 결과가 같기 때문이다.
- 키워드 요약 계약(schemaVersion 1)과 `ai.keyword.quarantined` topic은 없다.

### 멱등 키는 storyId와 version이다

`RoutingJob.eventKey`와 requestId는 이벤트와 수신자만으로 정해지는 순수 함수다.

| 대상 | eventKey | requestId |
| --- | --- | --- |
| 요약 | `{storyId}:{version}` | `sum:{storyId}:{version}:u:{recipientId}:c:{CHANNEL}` |
| 격리 | `{quarantineId}:{quarantinedAt epoch millis}` | `qrt:{eventKey}:u:{recipientId}:c:{CHANNEL}` |

- requestId에 키워드가 없으므로 한 사용자가 story의 키워드를 몇 개 구독했든 story 한 버전에 채널당 한 통이다.
- 같은 story의 다음 버전은 다른 requestId다. 버전은 새 전개(`DEVELOPMENT`)일 때만 발행되므로(ADR 034) 다음 버전의 알림은 새 소식이다.
- 격리 eventKey에 시각을 넣는 이유는 해제 뒤 같은 story가 다시 격리돼도(같은 quarantineId) 알림이 걸러지지 않게 하기 위해서다.
- 접수 쪽 requestId 멱등(ADR 013)이 같은 요청의 두 번째 접수를 거른다.

### 수신자는 페이지로 읽고, 페이지마다 진행 커서를 저장한다

routing은 user-service `POST /api/v1/internal/subscriptions/recipients/search`(ADR 026)를 페이지 단위로 부른다.
요청에는 story의 keywords 전체를 싣고, 응답의 수신자 항목마다 그 수신자가 구독한 키워드가 온다.

```
ai.summary.created (key = storyId)
  -> listener: 역직렬화, schemaVersion 검사, command로 변환
  -> RouteNotificationService
       1. eventKey로 RoutingJob 조회, 없으면 STARTED로 insert. COMPLETED면 SKIPPED
       2. RoutingJob.cursor부터 recipients/search 한 페이지
       3. 수신자마다 본문 렌더링, requestId 계산
       4. 페이지의 요청을 동시에 발행하고 broker ack를 모두 기다린다
       5. nextCursor 있음 -> advance(nextCursor) 조건부 저장 -> 2
          nextCursor 없음 -> complete 조건부 저장
```

- 커서는 ack를 모두 받은 뒤에 저장한다. 하나라도 실패하면 예외가 올라가고 커서는 그 페이지 앞에 남는다.
- 재전달되면 저장된 커서부터 잇는다. 끝난 페이지는 다시 읽지 않는다.
- 커서 뒤의 페이지 하나는 다시 발행될 수 있다. 이미 접수된 요청은 requestId 멱등이 거른다.
- 수신자가 0명이고 `nextCursor`가 있는 페이지도 커서를 저장하고 다음 페이지로 간다. 그 페이지의 사용자가 모두 ACTIVE 바인딩이 없는 경우다(ADR 026).
- 마지막 페이지는 `complete` 한 번으로 저장한다. COMPLETED의 cursor는 null이다.
- 한 페이지의 크기는 routing 설정이고 user-service의 상한은 100명이다.

`RoutingJob`의 진행(`advance`)과 완료(`complete`) 저장은 조건부 갱신이다.

- 조건은 `_id`, `status = STARTED`, `cursor = 전이 전 값`(null 포함)이다. 기대값은 전이 전 인스턴스에서 읽는다(ADR 024).
- 조건이 어긋나면 예외를 올린다. 서비스 안에서 다시 시도하지 않고, 재전달 때 저장된 상태에서 다시 시작한다.
- lease나 claim은 두지 않는다. 같은 레코드를 두 인스턴스가 동시에 처리해도 알림은 requestId 멱등이 거른다.
- 조건부 갱신이 막는 것은 저장된 진행 상태의 후퇴다. 커서가 뒤로 가거나 COMPLETED가 STARTED로 되돌아가지 않는다.

격리 알림은 관리자 수가 작아 페이지를 쓰지 않는다. `GET /internal/users?role=ADMIN`의 결과 전체를 한 번에 발행하고 `complete`한다.

### 본문은 수신자별로 application 계층이 만든다

요약 본문은 수신자마다 다르다.

```
[{그 수신자가 구독한 키워드의 displayName, canonicalKey 순, ", " 연결}] {title}

{content}

감성 {sentiment} · 기사 {sourceNewsCount}건 · {createdAt}
```

- 머리의 키워드는 story의 키워드 전체가 아니라 그 수신자가 구독한 것이다. 다른 키워드로 이 story에 닿은 수신자에게 자기가 구독하지 않은 키워드를 보이지 않는다.
- 표기는 user-service 응답의 `displayName`이다. 순서는 canonicalKey 오름차순이라 같은 구독이면 본문 머리가 같다.
- 격리 본문은 storyId, 연속 실패 횟수, 사유, 격리 시각을 담는다.
- 2000자를 넘으면 잘라내고 경고 로그를 남긴다. Discord의 한도가 가장 작다.
- 자르기는 요약과 격리가 같은 구현을 쓴다.

본문은 수신자별 구독 키워드가 정해지는 fan-out 시점에 만든다.
그 시점은 application 계층의 `RouteNotificationService` 안이므로 렌더링도 application에 둔다.
application은 `ai.contract` 타입을 import하지 않는다. inbound 어댑터가 계약을 routing의 command(storyId, version, keywords, title, content, sentiment, sourceNewsCount, createdAt)로 옮긴다.

### 알림 출처는 story와 수신자의 구독 키워드다

`NotificationRequestedOrigin`은 summaryId, storyId, version, keywords, userId를 싣는다.

- keywords는 story의 키워드 전체가 아니라 그 수신자가 구독한 canonicalKey다.
- 격리 알림은 storyId와 userId만 싣는다.
- 출처는 접수 쪽이 저장만 하는 선택 정보이고 그 값으로 분기하지 않는다. 그래서 `NotificationRequestedEvent.schemaVersion`은 1을 유지한다(ADR 025·027).
- notification-service는 이 값을 `NotificationOrigin`에 옮긴다. 발송 이력을 story·사용자 축으로 조회하는 read model의 원천이다.

### story 격리 알림

- routing이 `ai.story.quarantined`(`AiStoryQuarantinedEvent`)를 소비해 ADMIN 사용자 x ACTIVE 바인딩 채널로 보낸다.
- 수신자는 `GET /internal/users?role=ADMIN`이 돌려준 관리자와 그 ACTIVE 바인딩 채널이다. 발행 뒤의 경로는 요약 알림과 같고 job 종류는 `QUARANTINE`이다.
- `ai.story.split-requested`를 소비하는 모듈은 없다(ADR 033 열린 항목).

### ai-service의 발행 스위치

story 요약 저장과 outbox 행 기록은 항상 한 트랜잭션이다.
발행할 이벤트가 있는 버전(`DEVELOPMENT`, `NEW_STORY`)과 격리 전이는 언제나 outbox 행을 남긴다. 저장 쪽에 발행을 끄는 설정은 없다.

발행을 멈추는 설정은 relay와 Kafka 발행 어댑터의 둘이다.

| `kachi.ai.outbox.relay.enabled` | `kachi.ai.events.enabled` | 기동 | 동작 |
| --- | --- | --- | --- |
| true | true | 성공 | relay가 PENDING 행을 Kafka로 발행한다 |
| false | true | 성공 | 발행 어댑터 빈은 있으나 relay 빈이 없다. 행은 PENDING으로 쌓인다 |
| false | false | 성공 | 발행 어댑터와 relay 빈이 모두 없다. 행은 PENDING으로 쌓인다 |
| true | false | 실패 | relay 조립이 발행 어댑터 빈을 찾지 못해 기동을 멈춘다 |

- `kachi.ai.events.enabled`는 `AiEventsConfig`의 조건이라 false면 `AiOutboxPublisherPort` 구현이 없다.
- `kachi.ai.outbox.relay.enabled`는 `AiOutboxRelayConfig`, executor, scheduler의 조건이라 false면 relay 빈이 하나도 없다.
- relay만 켠 조합은 `AiOutboxRelayConfig`가 발행 포트를 `ObjectProvider`로 받아 없으면 기동에서 실패시킨다(ADR 023). 켜져 있다고 믿는데 아무것도 나가지 않는 상태를 막는다.
- 꺼 둔 동안 쌓인 행은 relay를 켜면 발행된다. 유실은 없다.

### 키워드 요약 경로는 없다

- ai-service에 키워드 요약 경로가 없다. 요약 window, watermark, `newsHash`, 키워드 격리와 그 운영 API, `news_summaries`가 해당한다.
- collector-service에 기사 기간 조회 `GET /api/v1/internal/news`가 없다. 기사는 `collector.news.collected` 이벤트로만 나간다(ADR 032).
- user-service에 키워드 하나를 쿼리 파라미터로 받는 구독자 조회 API가 없다. 구독 수신자는 `recipients/search`로만 읽는다.
- 키워드 확장은 그대로다. ai-service는 활성 키워드 API로 키워드를 읽어 확장한다.

## 검토한 대안

- **schemaVersion 1과 2를 함께 소비**
  - 같은 기사가 키워드 요약과 story 요약으로 두 번 알림된다. 두 경로를 함께 끝내야 중복이 사라지고, v1 분기는 쓸 데 없는 코드로 남는다.
- **routing이 구독 read model을 직접 갖는다**
  - 구독 변경 이벤트를 user-service에 새로 만들어야 하고, 정규화·ACTIVE 바인딩 필터가 두 컨텍스트에 생긴다. 구독의 정본은 user-service다(ADR 025).
- **페이징 없이 수신자 전체를 한 번에 조회**
  - 응답 크기가 수신자 수에 비례해 WebClient 메모리 상한(262,144바이트)을 넘는다. 도중에 죽으면 진행 위치가 없어 전체를 다시 발행한다.

## 트레이드오프

- 장점:
  - 수신자는 story 한 버전에 채널당 한 통을 받는다. 키워드를 몇 개 구독했든 같다.
  - 재전달은 저장된 커서부터 잇는다. 다시 발행하는 범위가 페이지 하나다.
  - 한 페이지의 조회 비용이 남은 구독자 수가 아니라 `키워드 수 x limit`에 비례한다(ADR 026).
  - 본문 머리가 수신자의 구독과 맞는다.
- 단점:
  - 조건부 저장이 재시도 횟수만큼 연속으로 어긋나면 그 레코드는 DLT로 간다. 그 사이 다른 인스턴스가 완료했다면 완료된 이벤트가 DLT에 남는다.
  - 페이지 사이에 구독이 바뀌면 커서 앞쪽 userId에 새로 생긴 구독은 그 버전의 알림에서 빠진다(ADR 026).
  - 페이지 안의 발행 하나가 실패하면 그 페이지 전체를 다시 발행한다. 접수 쪽이 중복을 거르지만 `notification.requested`에 레코드가 그만큼 늘어난다.
  - 본문을 수신자마다 렌더링하므로 같은 story의 요청 payload가 수신자 수만큼 따로 만들어진다.

## 운영 제약

- DLT 메시지의 재처리는 원 topic 재발행이다. job이 STARTED면 저장된 커서부터, COMPLETED면 SKIPPED다.
- story의 keywords가 100개를 넘는 요약은 재시도 없이 DLT로 간다. 수신자 조회의 요청 한도가 100개이고(ADR 026), 나눠 조회하면 수신자별 본문과 한 통 규칙이 깨진다.
- schemaVersion 1 레코드와 `ai.keyword.quarantined`의 레코드는 이행하지 않는다. 옛 데이터는 `./scripts/cycle.sh reset`으로 지우고 기동 시 다시 만든다.
