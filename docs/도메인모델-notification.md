# Notification 컨텍스트 도메인 모델

notification bounded context의 도메인 모델이다. 도메인과 유스케이스는 notification-core에 있고, 
notification-service(접수·outbox 발행)와 notification-worker(dispatch·vendor 발송)는 어댑터 전용 모듈이다. 
notification-routing(fan-out)은 자기 도메인과 유스케이스를 갖는 별도 모듈이다.
공통 관례는 [도메인모델.md](도메인모델.md)를 따른다.

관련 결정: ADR 012(모듈 설계), 013(outbox dispatch flow), 014(재시도 분류),
015(outbox 발행 runtime), 016(vendor sender 구조), 017(Mongo 트랜잭션 전제),
018(재시도 폭주 방지), 024(aggregate 불변화와 finalize CAS), 027(알림 라우팅), 028(발송 직전 주소 조회와 스킵), 035(story 요약 소비와 storyId 멱등).

이 컨텍스트의 상태 전이 aggregate는 불변이다. 전이 메서드는 전이 결과를 새 인스턴스로 반환하고 전이 전 인스턴스는 그대로 남는다.
전이 가드는 최종 방어선이며, 외부 side effect(Kafka 발행, vendor 호출) 뒤의 결과 확정은 전이 전 인스턴스가 들고 있던 claim을
기대값으로 하는 저장소 CAS로만 한다.

## 알림 애그리거트

### 알림(Notification)

_Aggregate Root_

알림 요청 한 건의 접수, dispatch 발행, worker 발송 상태를 표현한다.

#### 속성(Attributes)

- `id`: `NotificationId` 알림 식별자
- `requestId`: upstream 요청 멱등 키
- `requester`: 요청 주체
- `channel`: `NotificationChannel` 발송 채널
- `recipientId`: 수신자 식별자(user-service 사용자 id). 주소(`address`)가 아니며 알림에는 주소가 남지 않는다
- `message`: 발송 메시지 (null 허용, 빈 문자열 불가)
- `origin`: `NotificationOrigin` 출처(summaryId, storyId, version, keywords, userId). 요청 계약에 실려 온 알림만 값을 가진다
  - keywords는 story의 키워드 중 그 수신자가 구독한 canonicalKey다
- `requestedAt`: 최초 접수 시각
- `status`: `NotificationStatus` 알림 상태
- `failureReason`: 마지막 실패 사유
- `updatedAt`: 마지막 변경 시각 / `lastTransitionAt`: 마지막 상태 전이 시각
- `dispatchAttempts`: vendor 발송 시도 횟수 (sender 결과 확정 시 증가)
- `claimedAt` / `claimedBy`: worker claim 정보 (`PROCESSING`일 때만 존재)
- `uncommittedHistories`: 아직 저장되지 않은 상태 전이 이력

#### 행위(Behaviors)

- `static request(...)`: 알림을 `REQUESTED` 상태로 접수한다
- `static restore(...)`: 저장소 snapshot을 복원한다. claim 정보와 상태의 정합을 검증한다
- `markPublished(now)`: dispatch message 발행 성공. `REQUESTED | PUBLISH_FAILED -> PUBLISHED` (멱등)
- `markPublishFailed(now, reason)`: 발행 실패. `REQUESTED -> PUBLISH_FAILED` (이미 발행됐으면 무시)
- `markProcessing(now, claimedBy)`: worker claim. `PUBLISHED | RETRY_WAIT -> PROCESSING` (멱등)
- `markSent(now)`: 발송 성공. `PROCESSING -> SENT`, 시도 횟수 증가, claim 해제
- `markFailed(now, reason)`: 발송 실패. `PROCESSING -> FAILED`, 시도 횟수 증가, claim 해제
- `markRetryWait(now, reason)`: 자동 재시도 대기. `FAILED -> RETRY_WAIT`
- `markDead(now, reason)`: 자동 재시도 종료. `FAILED -> DEAD` (멱등)
- `markSuppressed(now, reason)`: 발송하지 않고 종료(스킵). `PROCESSING -> SUPPRESSED`, 시도 횟수는 그대로, claim 해제. 사유는 `recipient unavailable: {reason}`(바인딩 없음·PENDING·REVOKED·주소 없음·채널 불일치)과 `already sent`(발송 직전 중복 가드) 두 종류
- `recoverDeadToRequested(now, reason)`: 운영자 수동 복구. `DEAD -> REQUESTED`
- `canRetry(maxAttempts)`: 시도 횟수가 한도 미만인지 판단한다

#### 규칙(Rules)

- requestId/requester/recipientId는 빈 값일 수 없다.
- 허용되지 않은 상태에서의 전이는 예외로 차단한다. 
  멱등 마커와 저장소 unique 제약을 통과해도 같은 알림 row의 잘못된 상태 변경은 이 가드가 막는다 (최종 방어선).
- 같은 상태로의 재전이는 멱등으로 무시한다 (중복 메시지 대비).
- `SENT`·`DEAD`·`SUPPRESSED`는 자동 처리의 종착 상태다.
- 모든 상태 전이는 `uncommittedHistories`에 `NotificationHistory`로 쌓이고, 
  현재 상태 변경과 같은 MongoDB 트랜잭션에서 원자적으로 저장된다.
- claim 정보는 `PROCESSING`일 때만 존재한다. `claimedAt`/`claimedBy`는 함께 있거나 함께 없다.
- 수동 복구는 이전 실패 사유를 history에 남기고 현재 snapshot의 `failureReason`,
  `dispatchAttempts`, claim 정보를 초기화하며 새 dispatch outbox를 생성한다.
- adapter는 상태를 직접 조작하지 않고 core use case를 통해 변경한다.

### 알림 상태(NotificationStatus)

_Enum_

```text
발송 등록 측 (service):
  REQUESTED ─┬─▶ PUBLISHED
             └─▶ PUBLISH_FAILED (─▶ PUBLISHED, outbox recovery)

발송 처리 측 (worker):
  PUBLISHED ─▶ PROCESSING ─▶ SENT
                         ├─▶ SUPPRESSED
                         └─▶ FAILED ─┬─▶ RETRY_WAIT ─▶ PROCESSING ...
                                     └─▶ DEAD ─▶ (운영자 수동 복구) REQUESTED
```

- `REQUESTED`: 요청 수신, 멱등 통과, 영속 완료
- `PUBLISHED`: `notification.dispatch` 발행 성공, worker 진입 가능
- `PUBLISH_FAILED`: 발행 실패, outbox recovery 대상
- `PROCESSING`: worker가 발송 claim 후 sender 호출 진행 중
- `FAILED`: sender 호출 실패, `RETRY_WAIT` 또는 `DEAD` 분기 직전
- `RETRY_WAIT`: 재시도 가능한 실패 뒤 다음 시도를 기다리는 상태
- `SENT`: 외부 채널 발송 성공
- `SUPPRESSED`: 스킵. 수신 주소가 없거나 이미 보낸 알림이라 vendor를 부르지 않고 종료
- `DEAD`: 자동 재시도 종료, 운영자 수동 재처리 대상

### 알림 채널(NotificationChannel)

_Enum_

- `SLACK`, `DISCORD`, `TELEGRAM`: 실발송 채널
- `SMS`, `KAKAO`, `EMAIL`: 실발송 없이 더미 서버와 연동하는 채널

### 알림 식별자(NotificationId)

_Value Object_

- `value`: 알림 식별 UUID, `newId()` / `of()`

## 아웃박스 애그리거트

### 알림 아웃박스(NotificationOutbox)

_Aggregate Root_

요청 접수와 worker용 dispatch message 발행 사이의 유실을 막는 발행 보장 대기열이다.
같은 트랜잭션에서 notification과 outbox를 함께 commit한 뒤, 폴링이 broker로 발행한다.

#### 속성(Attributes)

- `id`: `NotificationOutboxId` outbox 식별자
- `notificationId`: 발행 대상 알림 식별자
- `topic` / `partitionKey` / `eventPayload`: 발행할 Kafka topic, partition key, 직렬화된 dispatch message
- `createdAt`: 생성 시각
- `outboxStatus`: `NotificationOutboxStatus` 발행 상태
- `retryCount`: 발행 실패 재시도 횟수
- `nextRetryAt`: 다음 재시도 가능 시각
- `lastError`: 마지막 발행 실패 사유
- `publishedAt`: 발행 성공 시각
- `claimedAt` / `claimedBy`: 발행 claim 정보 (`PUBLISHING`일 때만 존재)

#### 행위(Behaviors)

- `static create(...)`: `PENDING` outbox를 생성한다
- `static restore(...)`: 저장소 snapshot을 복원한다. claim 정보와 상태의 정합을 검증한다
- `markPublishing(now, claimedBy)`: 발행 처리 권한 획득. `PENDING -> PUBLISHING` (CAS claim)
- `markPublished(now)`: Kafka 발행 성공. `PUBLISHING -> PUBLISHED` (멱등), lastError 해제
- `recordFailure(reason, retryPolicy, now)`: 발행 실패 기록. retry 한도 안이면
  `PUBLISHING -> PENDING` + `nextRetryAt` 계산, 한도 도달이면 `PUBLISHING -> DEAD`
- `markDead(reason)`: 즉시 자동 재시도 종료. `PUBLISHING -> DEAD`
- `recoverToPending(now)`: 운영자 복구. `DEAD -> PENDING`, 카운터·실패 정보 초기화

#### 규칙(Rules)

- 알림 요청 접수와 dispatch outbox 저장은 같은 저장 경계에서 확정한다.
- `PENDING`만 `PUBLISHING`으로 claim할 수 있고, `PUBLISHING`만 `PUBLISHED`/`PENDING`/`DEAD`로 전이할 수 있다.
- claim에 성공한 인스턴스만 publish를 수행하며, 결과는 callback에서 별도 트랜잭션으로 반영한다.
  늦은 callback은 `PUBLISHING`일 때만 반영하며, 이 조건은 저장소가 `_id + PUBLISHING + claim` CAS로 강제한다.
- 오래된 `PUBLISHING`은 publisher 중단으로 보고 stale 회수 대상에 포함한다
  (`publishingVisibilityTimeout`).
- claim은 동시 발행을 막는 1차 방어선이다. publish 성공 후 DB 반영 전 timeout 회수가
  겹치면 같은 이벤트가 재발행될 수 있으므로, consumer의 멱등 처리를 2차 방어선으로 둔다.
- outbox retry는 dispatch message 발행 보장이고, worker dispatch retry는 vendor 발송 실패 복구다 
  — 두 재시도는 층이 다르다.

### 아웃박스 상태(NotificationOutboxStatus)

_Enum_

```text
PENDING ─▶ PUBLISHING ─┬─▶ PUBLISHED   (broker ack, terminal)
                       ├─▶ PENDING     (실패, retry 가능)
                       └─▶ DEAD        (한도 초과 또는 즉시 DEAD)
DEAD ─▶ PENDING (운영자 수동 복구)
```

- `PENDING`: 발행 대기 또는 재시도 대기
- `PUBLISHING`: CAS claim 후 발행 진행 중
- `PUBLISHED`: Kafka 발행 성공
- `DEAD`: 자동 발행 재시도 종료

### 아웃박스 식별자(NotificationOutboxId)

_Value Object_

- `value`: outbox 식별 UUID, `newId()` / `of()`

## 이력

### 알림 이력(NotificationHistory)

_Entity_

알림 상태 전이에 따른 append-only 감사 로그다.

#### 속성(Attributes)

- `id`: `NotificationHistoryId` / `notificationId`: 대상 알림
- `fromStatus` / `toStatus`: 전이 전후 상태
- `reason`: 전이 사유 / `createdAt`: 전이 시각

#### 행위(Behaviors)

- `static record(...)`: 상태 전이 이력을 생성한다 (Notification의 전이 메서드가 호출)
- `static restore(...)`: 저장소 snapshot을 복원한다

#### 규칙(Rules)

- 알림 상태 전이마다 from/to/시각/사유를 기록한다. 수정·삭제 행위는 없다.
- 저장 구조는 `notification_histories` 별도 collection이며, 
  Notification 현재 상태 변경과 history insert는 같은 MongoDB 트랜잭션에서 원자적으로 확정한다.
- history-service(계획)의 장기 사용자 이력과 다르다. 
  이 history는 알림 한 건의 상태 전이 감사 로그이며, 별도 consumer 비동기 적재로 대체하지 않는다 (원자성 유지).

### 이력 식별자(NotificationHistoryId)

_Value Object_

- `value`: 이력 식별 UUID, `newId()` / `of()`

## DLT

### DLT 메시지(NotificationDltMessage)

_Entity_

Kafka retry가 끝나 DLT에 도달한 `notification.dispatch` record의 운영용 보관 기록이다.

#### 속성(Attributes)

- `id`: `NotificationDltMessageId` — 원본 record의 topic/partition/offset에서 결정적으로 생성 (멱등 키)
- `originalTopic` / `originalPartition` / `originalOffset` / `originalTimestamp`: 원본 record 위치와 시각
- `dltTopic` / `dltPartition` / `dltOffset` / `deadLetteredAt`: DLT record 위치와 시각
- `consumerGroup` / `messageKey` / `payload`: 소비 맥락과 원본 payload
- `exceptionFqcn` / `exceptionMessage`: 실패 예외 메타데이터
- `storedAt`: worker 저장 시각
- `status`: `NotificationDltMessageStatus`
- `discardedAt` / `discardReason`: DISCARDED 종료 정보
- `reprocessedAt` / `reprocessReason`: REPROCESSED 종료 정보

#### 행위(Behaviors)

- `static record(...)`: DLT record를 `PENDING` 메시지로 기록한다
- `static restore(...)`: 저장소 snapshot을 복원한다. 상태와 종료 필드의 정합을 검증한다
- `discard(now, reason)`: 운영자가 재처리하지 않기로 판단해 `PENDING -> DISCARDED`로 종료한다
- `canDiscard()`: 폐기 가능 여부 (`PENDING`인가)

#### 규칙(Rules)

- 멱등 기준은 원본 Kafka record의 topic/partition/offset이다.
- payload, messageKey, exception metadata는 runtime에서 관측한 원본 값을 보존해야 하므로
  빈 문자열까지 도메인에서 강하게 차단하지 않는다.
- `PENDING`은 종료 필드를 갖지 않고, `DISCARDED`와 `REPROCESSED`는 서로의 종료 필드를 함께 갖지 않는다. 
  종료 시각과 사유는 함께 있거나 함께 없다.
- `REPROCESSED`는 재처리 완료 상태다. 이 상태로 가는 전이 행위는 미구현이다.
- 운영 목록 조회는 payload를 제외하고, 상세 조회에서 재처리 판단을 위해 payload를 노출한다.

### DLT 메시지 상태(NotificationDltMessageStatus)

_Enum_

- `PENDING`: 운영 확인 대기
- `DISCARDED`: 재처리하지 않기로 종료
- `REPROCESSED`: 재처리 완료

### DLT 메시지 식별자(NotificationDltMessageId)

_Value Object_

- `value`: DLT 메시지 식별자
- `fromOriginalRecord(topic, partition, offset)`: 원본 record 좌표에서 결정적으로 생성한다

## 라우팅

notification-routing 모듈의 도메인이다. `ai.summary.created`·`ai.story.quarantined` 한 건을 수신자 x 채널만큼의 `notification.requested`로 fan-out한 기록을 갖는다.

### 라우팅 job(RoutingJob)

_Aggregate Root_

이벤트 1건을 라우팅한 기록이다.

#### 속성(Attributes)

- `id`: `RoutingJobId`
- `eventKey`: 이벤트 식별 키. 유일하다
- `kind`: `RoutingJobKind`
- `storyId`: 이벤트의 story
- `status`: `RoutingJobStatus`
- `cursor`: 다음에 읽을 수신자 페이지의 커서. `STARTED`에서 null이면 첫 페이지부터다
- `targetCount`: fan-out한 대상 수 누계 / `publishedCount`: 접수 topic으로 발행한 건수 누계
- `createdAt` / `updatedAt` / `completedAt`

eventKey는 요약이면 `{storyId}:{version}`, 격리면 `{quarantineId}:{quarantinedAt epoch millis}`다.

#### 행위(Behaviors)

- `static start(eventKey, kind, storyId, now)`: `STARTED`로 만든다. cursor는 null이고 두 건수는 0이다
- `static restore(...)`: 저장소 snapshot을 복원한다
- `advance(cursor, targetDelta, publishedDelta, now)`: `STARTED -> STARTED`. 한 페이지를 끝내고 cursor와 누계를 옮긴다
- `complete(targetDelta, publishedDelta, now)`: `STARTED -> COMPLETED`. 마지막 페이지의 delta를 더하고 cursor를 null로 한다

#### 규칙(Rules)

- `eventKey`와 `storyId`는 non-blank다.
- `advance`의 cursor는 non-blank이고 delta는 0 이상이다.
- `publishedCount`는 0 이상 `targetCount` 이하다.
- `completedAt`은 `COMPLETED`일 때만 있고, `COMPLETED`의 cursor는 null이다.
- 같은 이벤트를 다시 받으면 `COMPLETED`는 다시 라우팅하지 않고 `STARTED`는 저장된 cursor부터 잇는다.
- 전이 저장은 `_id`, `status = STARTED`, `cursor = 전이 전 값`을 조건으로 한 조건부 갱신이다. 조건이 어긋나면 예외이고, 진행 상태는 뒤로 가지 않는다.

### 라우팅 job 종류(RoutingJobKind)

_Enum_

- `SUMMARY`: story 요약 버전 생성. story의 키워드를 구독한 수신자 x 채널로 fan-out한다
- `QUARANTINE`: story 격리. 관리자 x 채널로 fan-out한다

### 라우팅 job 상태(RoutingJobStatus)

_Enum_

- `STARTED`: 라우팅 중이거나 도중에 중단된 상태
- `COMPLETED`: 라우팅 완료

### 라우팅 job 식별자(RoutingJobId)

_Value Object_

- `id`: 시간순 UUID

## 재시도 모델

`domain/retry/` 패키지에 있다. 기술에 의존하지 않는 순수 모델로, 실패 분류와 재시도 결정이라는
도메인 규칙을 담는다. 재시도 예외(`RetryException`, `RetryableException`,
`NonRetryableException`)는 `exception/`에 있다.

### 재시도 실패(RetryFailure)

_Value Object_

sender, broker, database, network, application 실패를 retry 정책이 판단할 수 있는 표준
형태로 표현한다.

#### 속성(Attributes)

- `code`: 내부 표준 실패 코드 또는 외부(vendor/broker) 실패 코드 문자열
- `message`: 실패 메시지
- `source`: `FailureSource` 실패 원천 / `category`: `FailureCategory` 실패 분류
- `statusCode`: 외부 HTTP status code
- `retryAfterMillis`: 외부 시스템이 알려준 재시도 대기 시간 (음수 불가)

#### 행위(Behaviors)

- `static of(code, ...)`: 표준 코드(`RetryFailureCode`)로 생성한다. source/category가 코드에서 결정된다
- `static external(code, message, source, category, ...)`: vendor/broker가 내려준 동적 실패 코드를 보존해 생성한다

#### 규칙(Rules)

- retry 가능 여부는 code 문자열이 아니라 `source`와 `category`로 판단한다.

### 재시도 실패 코드(RetryFailureCode)

_Enum_

notification-core가 정의한 표준 실패 코드다. 각 상수가 code/defaultMessage/source/category를 갖는다.

| 코드 | source | category |
| --- | --- | --- |
| `VENDOR_TIMEOUT` | VENDOR | TIMEOUT |
| `VENDOR_RATE_LIMITED` | VENDOR | RATE_LIMITED |
| `VENDOR_TRANSIENT_ERROR` | VENDOR | TRANSIENT_ERROR |
| `INVALID_RECIPIENT` | VENDOR | VALIDATION_ERROR |
| `DISPATCH_NOT_READY` | APPLICATION | TRANSIENT_ERROR |
| `DISPATCH_PROCESSING_TIMEOUT` | APPLICATION | TIMEOUT |
| `RECIPIENT_RESOLVE_FAILED` | APPLICATION | TRANSIENT_ERROR |
| `RECIPIENT_RESOLVE_TIMEOUT` | APPLICATION | TIMEOUT |

### 실패 원천(FailureSource)

_Enum_

- `VENDOR`, `BROKER`, `DATABASE`, `NETWORK`, `APPLICATION`

### 실패 분류(FailureCategory)

_Enum_

- `TIMEOUT`, `RATE_LIMITED`, `TRANSIENT_ERROR`, `PERMANENT_ERROR`, `VALIDATION_ERROR`,
  `AUTHORIZATION_ERROR`, `CONFLICT`, `UNKNOWN`

### 재시도 정책(RetryPolicy)

_Domain Service_

실패를 재시도할지 결정하고 다음 대기 시간을 계산한다.

#### 행위(Behaviors)

- `decide(failure, attempts)`: `RetryDecision`을 반환한다
- `retryable(failure)`: category와 source가 모두 재시도 대상 집합에 속하는지 판단한다
- `exhausted(attempts)`: 재시도 한도 도달 여부
- `backoff(attempts)`: 현재 시도 횟수의 backoff 대기 시간

#### 규칙(Rules)

- retry 불가능한 실패는 한도와 무관하게 즉시 `GiveUp(exhausted=false)`를 반환한다.
- retry 가능해도 한도에 도달하면 `GiveUp(exhausted=true)` — DEAD 처리 후보가 된다.
- 그 외에는 backoff delay와 함께 `Retry`를 반환한다.
- 기본 재시도 대상 category: TIMEOUT, RATE_LIMITED, TRANSIENT_ERROR, CONFLICT, UNKNOWN.
  기본 재시도 대상 source: 전체.

### 재시도 결정(RetryDecision)

_Value Object_ (sealed)

- `Retry(delay, failure)`: backoff 후 재시도
- `GiveUp(failure, exhausted)`: 포기. `exhausted`로 한도 도달과 non-retryable을 구분한다

### 백오프 정책(BackoffPolicy / ExponentialBackoffPolicy)

_Domain Service_

- `delay(attempts)`: 시도 횟수에 해당하는 대기 시간을 반환한다.
- ExponentialBackoffPolicy: `baseDelay * multiplier^(attempts-1)`을 `maxDelay`로 cap한다
  (기본 multiplier 2.0). 동시 재시도 몰림을 줄이기 위해 jitter를 선택적으로 적용한다
  (기본 0). 지수와 delay에 과대값 방어가 있다.

### 재시도 예외(RetryException / RetryableException / NonRetryableException)

_Exception_

`RetryFailure`를 담는 예외 계층이다. 예외 타입이 제어 신호다.
worker의 dispatch 흐름과 spring-kafka 에러 핸들러(`addNotRetryableExceptions`)가 catch 타입만으로 재시도/DEAD를 결정한다. 
