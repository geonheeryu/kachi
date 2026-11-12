# 022. ai-service outbox와 이벤트 발행 보장

이 outbox가 실제 broker까지 발행하는 계약은 ai-service의 `AiStorySummaryCycleIntegrationTest`가 검증한다(ADR 029).
이 설계는 collector `CollectorOutbox`(ADR 032)와 story-service `StoryOutbox`(ADR 033)에서도 같은 형태로 쓴다.

## 배경

ai-service는 story 요약 버전을 만들고, 반복해서 실패하는 story를 격리한다. 
이 두 결과를 notification(ADR 013)으로 보내려면 MongoDB 저장과 Kafka 발행 사이의 유실 윈도우를 닫아야 한다. 
요약은 저장됐는데 발행 직전에 프로세스가 죽으면 그 요약의 알림은 영원히 나가지 않고, 
반대로 발행은 됐는데 저장이 실패하면 없는 요약의 알림이 나간다.

이 문제와 해법은 ADR 015가 notification에서 이미 정했다. 
결과를 저장하는 트랜잭션 안에서 outbox 문서를 함께 쓰고, 별도 relay가 outbox를 읽어 발행하며, 발행 결과를 outbox 상태에 반영한다. 

1. 발행 결과 반영은 저장소 CAS(`_id + PUBLISHING + claim`)로만 한다. ADR 015의 "늦은 callback은 PUBLISHING일
   때만 반영" 규칙을 코드 분기가 아니라 저장 조건으로 강제한다.
2. publish 실패를 retryable / non-retryable로 나누고 non-retryable은 즉시 DEAD로 보낸다.

## 결정

### 이벤트

| 이벤트 | 시점 | eventKey | partitionKey |
| --- | --- | --- | --- |
| `SUMMARY_CREATED` | `DEVELOPMENT` 요약 버전 저장 | `storyId:version` | storyId |
| `STORY_SPLIT_REQUESTED` | `NEW_STORY` 요약 버전 저장 | `split:storyId:version` | storyId |
| `STORY_QUARANTINED` | `TRACKING -> QUARANTINED` 전이 | `quarantineId:quarantinedAtEpochMillis` | storyId |

`NO_CHANGE` 버전과 이미 격리된 story의 추가 실패는 이벤트를 만들지 않는다(ADR 034). outbox 1행은 도메인 이벤트 1건이다.
알림 채널 fan-out은 이 서비스의 관심사가 아니다(ADR 023).

eventKey는 내용 해시가 아니라 aggregate 식별자다. 요약 버전 1건은 `(storyId, version)` 1건이므로 그 조합이 곧 핵심 키다(ADR 034).
내용 해시는 LLM 응답에 따라 값이 달라져 같은 버전을 가리키는 키가 되지 못한다. 소비자의 멱등 키(ADR 035)도 이 조합이다.

### payload

payload는 생성 시점의 JSON으로 고정한다. 요약 본문을 그대로 넣어 발행 시점에 원본을 다시 읽지 않는다.
직렬화는 트랜잭션 전에 한다. 직렬화가 실패하면 요약 저장까지 막는다. 
즉, 요약은 저장됐는데 알림은 영원히 없는 상태보다 요약 실패로 다음 시도에 재시도되는 쪽이 낫다.

payload의 형태는 application 모델(`AiOutboxEvent`)이 아니라 별도 계약 타입이 정한다. 
`ai-contract` 모듈의 `AiSummaryCreatedEvent`·`AiStorySplitRequestedEvent`·`AiStoryQuarantinedEvent`가 그것이고, `schemaVersion`은 이 계약 타입의 필드다.
application 모델과 계약을 나눈 이유는 도메인 enum이나 필드가 바뀔 때 발행 형식이 조용히 따라 바뀌지 않게 하기 위해서다. 
어댑터가 두 모델을 값마다 짝지어 옮기므로 도메인이 바뀌면 컴파일이 멈춘다. 
notification이 `NotificationDispatchMessage`와 `notification-contract`의 `NotificationDispatchEvent`를 나눈 것과 같은 구조다.

payload에는 `type`·`eventKey`·`partitionKey`가 없다. 이 값들은 outbox 행의 필드이고 발행자가 행에서 읽는다.
어느 계약 타입으로 읽을지도 outbox 행의 `eventType`으로 고르므로 payload 안에 타입 표시가 필요 없다.

### 상태 전이 규칙

ADR 015와 동일하다.

```text
PENDING ──claim(CAS)──> PUBLISHING ──publish 성공──> PUBLISHED
                            │
                            ├──retryable 실패, 한도 미달──> PENDING (retryCount++, nextRetryAt)
                            ├──retryable 실패, 한도 도달──> DEAD
                            └──non-retryable 실패──────> DEAD

PUBLISHING 이 visibilityTimeout 을 넘기면 `publishing-timeout` 실패로 회수 (위 retryable 실패 경로)
DEAD ──운영자 복구──> PENDING
```

### 저장 경계 (ADR 017)

| 지점 | 경계 |
| --- | --- |
| 요약 버전 insert + 기사 표시 + story CAS + outbox insert | Mongo transaction. 하나라도 어긋나면 abort하고 이번 결과를 버린다(ADR 034). outbox는 저장에 이긴 쪽만 만든다 |
| 격리 전이 + outbox insert | Mongo transaction |
| claim | `findAndModify(_id + PENDING + nextRetryAt <= now)` |
| 발행 결과 반영 | `findAndModify(_id + PUBLISHING + claimedBy + claimedAt)`. 불일치는 소유권 상실이므로 무시하고 로그만 남긴다 |

notification은 발행 결과 반영이 outbox + notification 두 문서라 transaction이 필요했다. 
ai-service는 outbox 한 문서라 CAS 한 번으로 원자적이다. 
`AiRun`은 outbox와 함께 확정될 이유가 없어 경계 밖이다.

발행 결과 반영 CAS의 `claimedAt`은 claim이 돌려준 값을 그대로 써야 한다. 
MongoDB는 시각을 밀리초로 잘라 저장하고 CAS는 동등 비교이므로, relay가 자기 시계로 값을 다시 만들면 조건이 영원히 맞지 않는다.

격리 전이 저장은 story 요약 실패를 처리하는 자리에서 일어난다.
이 저장이 실패하면 예외가 올라가 소비 경로는 같은 offset을 다시 처리하고 tick 경로는 그 story를 다음 tick으로 미룬다. 격리 기록과 알림이 함께 빠진 채 지나가지 않는다.

이 결정으로 ai-service도 MongoDB replica set이 전제가 된다. Testcontainers는 `MongoDBContainer.withReplicaSet()`을 쓴다.

### 실패 분류와 재시도 (ADR 018 §구현 지침)

| 질문 | 답 |
| --- | --- |
| retryable | broker 일시 장애, timeout, network (ADR 023의 매핑표). 분류되지 않은 예외도 retryable |
| 즉시 give up | payload 직렬화·크기·토픽 계약 오류 -> `AiOutboxPublishException(retryable=false)` -> DEAD |
| 최대 자동 재시도 | 5회 (실패 5회 누적 시 DEAD) |
| backoff | 1s x 2^(n-1), 상한 1m, jitter ±20% |
| Retry-After | 해당 없음 (broker) |
| retry budget | 두지 않음. batchSize 50 x relay 5s가 상한 |
| 서킷 브레이커 | 두지 않음. broker 장기 장애는 DEAD 운영 큐로 (ADR 015와 같은 입장) |
| DEAD 조회·복구 | 운영 internal API. `GET /internal/ai/outboxes`(상태 필터, 기본 DEAD)와 `POST /internal/ai/outboxes/{outboxId}/recover`. 복구는 DEAD 행에만 쓰는 조건부 갱신이라 동시 복구는 하나만 성공한다 |
| 관측 | 로그(상태 전이 전부). metric은 후속 |
| 멱등 | 같은 outbox 재발행은 같은 eventKey·같은 payload다. 발행 쪽은 중복을 막지 않고 소비자가 `storyId:version` 기준으로 거른다(ADR 035 RoutingJob) |

분류되지 않은 예외를 retryable로 보는 이유는, 모르는 실패를 DEAD로 보내면 운영자가 매번 복구해야 하기 때문이다.
non-retryable은 발행자가 그렇게 선언한 것만이다.

jitter는 인스턴스가 하나여도 필요하다. 
한 tick의 batch 안에 이미 동시성이 있어서, 같은 broker 장애로 함께 실패한 행들의 `nextRetryAt`이 같은 값으로 몰리면 다음 tick에 같은 부하가 그대로 재현된다. 
형식은 notification의 `ExponentialBackoffPolicy`와 같다.

### 실행 모델 (ADR 020)

polling relay scheduler가 기본이자 복구 경로다. 
같은 인스턴스의 tick 겹침은 실행 lock(`ExecutionLockPort`의 `INSTANCE` 범위, ADR 036), 다중 인스턴스는 claim CAS가 막는다. 즉, relay는 다중 인스턴스에 안전하다. 
story 요약·키워드 확장 tick은 `CLUSTER` 범위로 선언됐지만 현재 구현이 in-memory라 단일 인스턴스 전제이며(ADR 036), distributed lock 구현은 별도 단계에서 다룬다.

`kachi.ai.outbox.relay.enabled`로 relay 전체를 끈다. 
이 값은 scheduler만 막는 것이 아니라 relay 유스케이스·executor·scheduler 빈을 전부 만들지 않는다. 
기본값은 `true`다. 발행 어댑터의 스위치(`kachi.ai.events.enabled`)는 따로 있고, relay만 켜고 어댑터가 없는 조합은 기동에서 실패한다(ADR 023). 
끈 동안 outbox는 PENDING으로 쌓이고 켜면 발행된다. 즉, 유실되지 않는다.

### 반려안

- **내용 해시 eventKey** — 위 "이벤트" 절.
- **notification-core의 `NotificationOutbox`·`RetryPolicy` 재사용**: bounded context 간 코드 의존 금지(ADR 021).
  상태 전이 규칙은 개념이 같아도 도메인 모델은 각자 갖는다. 재시도 정책은 `exhausted`·`backoff` 두 함수라 복제 비용이
  거의 없다.
- **채널당 outbox 1행**: 3채널이 같은 topic·broker라 행을 나눠도 실패가 격리되지 않고, ai 도메인이 알림 채널을
  알게 된다. fan-out의 부분 실패는 `requestId` 멱등으로 충분하다. DEAD를 24h 뒤 복구할 때의 뒷단 DLT 노이즈는
  채널당 1행이어도 같은 행을 복구하면 생기므로 이 안이 푸는 문제가 아니다.
- **relay를 켜 두고 임시 publisher**: 로그만 남기면 PUBLISHED로 유실, 예외를 던지면 전량 DEAD. outbox의 존재 이유를 깬다.
- **immediate publish(저장 직후 발행)**: latency 요구가 없다. ADR 015처럼 polling을 기본으로 둔다.
- **요약 저장 후 outbox를 별도 저장**: transaction 없이 두 번 쓰면 그 사이가 유실 윈도우다. ADR 015의 출발점과 같다.

## 트레이드오프

장점: Mongo 저장과 Kafka 발행 사이 유실이 없다. 발행 실패와 LLM 실패가 다른 상태로 추적된다. 
늦은 결과가 상태를 덮어쓰지 못한다.

단점: replica set 전제, collection과 scheduler 1개씩 추가, DEAD 운영 API 필요, at-least-once라 뒷단 멱등 필수.

## 후속

- PUBLISHED 행 보존 기간·정리(TTL index)
- backlog size/age metric
- ~~visibilityTimeout과 batchSize x publish timeout 관계 재검토~~ — ADR 023에서 정리했다. visibilityTimeout이 재는 것은 행 하나의 발행 시간이라 batch 크기는 들어오지 않고, `max.block.ms + delivery.timeout.ms`(15s) < 60s로 확정했다.
- 스케줄러 tick 분산 lock
