# 023. ai-service 도메인 이벤트 발행과 ai-contract

## 배경

ADR 022로 ai-service는 뉴스 요약 생성과 키워드 격리를 outbox에 기록하고, relay가 그 행을 발행 포트(`AiOutboxPublisherPort`)로 내보낸다.
발행 포트의 구현이 없어 relay는 꺼져 있고, outbox는 PENDING으로 쌓이기만 한다. 이 ADR은 그 구현을 붙인다.

알림을 누가 어떤 채널로 받는지는 구독의 문제이고(ADR 025) ai-service의 관심사가 아니다. 
ai-service는 "요약이 생겼다", "키워드가 격리됐다"는 사실만 발행하고, 그것을 알림으로 fan-out하는 일은 notification-service의 routing이 한다.

여기서 정하는 것은 세 가지다.

1. 어느 topic에 무엇을 어떤 형식으로 발행하는가. 계약을 소비자와 어떻게 공유하는가.
2. Kafka 발행 실패를 ADR 022의 retryable / non-retryable 중 어느 쪽으로 보는가.
3. producer 설정과 timeout. relay의 `publishingVisibilityTimeout`(60s)과 어긋나면 발행 중인 행이 회수되어 이중 발행이 난다.

## 결정

### topic과 계약

outbox 행 하나는 Kafka 레코드 하나다. 행의 `eventType`이 topic을 고르고, `partitionKey`(keyword)가 레코드 key, `payload`가 값이다. 
payload는 outbox에 기록할 때 이미 계약 JSON으로 직렬화되어 있으므로(ADR 022) 발행 시점에는 손대지 않는다.

| eventType | topic | 계약 |
| --- | --- | --- |
| `SUMMARY_CREATED` | `ai.summary.created` | `AiSummaryCreatedEvent` |
| `KEYWORD_QUARANTINED` | `ai.keyword.quarantined` | `AiKeywordQuarantinedEvent` |

계약 클래스는 `ai-contract` 모듈로 옮긴다. 지금까지 `ai-service` 안 `me.rgunny.kachi.ai.contract` 패키지였는데, 소비자(notification-service routing)가 생기므로 notification-contract와 같은 형태의 독립 모듈이 된다. 
모듈에는 data class와 enum만 있고 로직·의존성이 없다(ADR 012). 
bounded context 간 코드 의존 금지(ADR 021)는 계약 모듈에 적용되지 않는다.

`schemaVersion`은 payload 필드로 유지한다. 필드 추가는 같은 버전, 의미 변경은 버전을 올리고 소비자가 거부하게 한다.

같은 키워드의 이벤트는 같은 파티션으로 가므로 요약 순서가 지켜진다. 소비자 쪽 순서(사용자별)는 소비자가 정한다.

### 발행 실패 분류

ADR 022의 기준은 하나다. **같은 payload를 다시 보내도 결과가 달라지지 않는 실패만 non-retryable이고, 나머지는 전부 retryable이다.**
`KafkaTemplate`은 실패를 `KafkaProducerException`으로 감싸 돌려주므로 cause 체인을 끝까지 풀어서 본다.

| 분류 | 예외 | 이유 |
| --- | --- | --- |
| non-retryable | `RecordTooLargeException` | broker의 `max.message.bytes`를 넘겼다. 같은 payload는 계속 넘긴다 |
| non-retryable | `SerializationException` | 레코드 직렬화 실패. String serializer라 실제로는 나기 어렵지만 계약상 여기다 |
| non-retryable | `InvalidTopicException` | topic 이름이 규칙에 어긋난다. 설정 오류이며 재시도로 풀리지 않는다 |
| retryable | `TimeoutException`, `NetworkException`, `NotLeaderOrFollowerException`, 그 밖의 `RetriableException` | broker 일시 장애 |
| retryable | 위에 없는 모든 예외 | 원인을 모르는 실패를 DEAD로 보내면 운영자가 매번 복구해야 한다 (ADR 022) |

`TopicAuthorizationException`과 `UnknownTopicOrPartitionException`은 retryable에 둔다. 
설정을 고쳐야 풀리지만, 재시도 5회(약 1~2분) 안에 고쳐지면 스스로 회복하고 아니면 DEAD로 가서 운영 복구 대상이 된다. 즉시 DEAD로 보내서 얻는 것이 없다.

분류 결과는 `AiOutboxPublishException(retryable)`에 담아 던진다. 코드는 `OUTBOX_PUBLISH_FAILED`다. 
ADR 022가 잡아 둔 `OUTBOX_PAYLOAD_INVALID`는 이 어댑터가 payload를 읽지 않으므로 여기서는 쓰지 않는다. 
payload가 계약에 맞는지는 기록 시점의 직렬화가 보장하고, 읽는 쪽의 검증은 소비자 몫이다.

### producer 설정과 timeout

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      properties:
        acks: all
        enable.idempotence: true
        max.block.ms: 5000
        request.timeout.ms: 5000
        delivery.timeout.ms: 10000
```

`acks=all` + `enable.idempotence=true`는 notification-service와 같다. 
발행 성공을 outbox PUBLISHED로 확정하는 근거가 broker ack이므로 leader 하나의 ack로는 부족하고, 멱등 producer는 client 내부 재시도로 인한 broker 쪽 중복을 막는다.

timeout 세 개는 relay의 `publishingVisibilityTimeout`(60s)에서 역산한 값이다.

- `max.block.ms`(5s): `send()`가 metadata를 기다리며 호출 스레드를 막는 시간의 상한. 
  - broker가 전부 죽어 있으면 기본값 60s 동안 relay tick이 행 하나에 멈춘다.
- `delivery.timeout.ms`(10s): `send()`가 돌려준 future가 결과를 내기까지의 상한. 
  - client 내부 재시도를 포함한다.
- `request.timeout.ms`(5s): broker 응답 1회의 상한. 
  - `delivery.timeout.ms`보다 작아야 client가 한 번은 재시도한다.

한 행의 발행이 걸릴 수 있는 최대 시간은 `max.block.ms + delivery.timeout.ms` = 15s이고, 60s보다 작으므로 발행 중에 stale로 회수되는 일은 없다.
ADR 022 후속에 적어 둔 "`visibilityTimeout > batchSize x publish timeout`" 검토는 이 조건으로 대신한다. 
relay는 행마다 claim 직전에 소유권을 잡고 발행이 끝나면 확정하므로, visibilityTimeout이 재는 것은 행 하나의 발행 시간이다.

어댑터 자체의 timeout은 두지 않는다. 
`delivery.timeout.ms`가 이미 future를 닫아 주고, 두 겹으로 두면 어느 쪽이 먼저 끊었는지 로그에서 구분해야 한다.

### 켜고 끄는 스위치

| 키 | 뜻 | 끄면 |
| --- | --- | --- |
| `kachi.ai.outbox.relay.enabled` | outbox 행을 읽어 발행 포트로 넘기는 relay (ADR 022) | outbox가 PENDING으로 쌓인다. stale 회수도 멈춘다 |
| `kachi.ai.events.enabled` | Kafka 발행 어댑터 | 발행 포트 구현이 없다. Kafka producer 관련 빈이 만들어지지 않는다 |

둘 다 기본값은 `true`다. 
ADR 022가 relay를 false로 둔 이유("publisher 어댑터가 없는 동안")가 사라졌다. 
로컬 프로파일과 테스트 프로파일은 둘 다 false다. broker 없이 기동하는 환경이다.

relay는 켜져 있는데 어댑터가 꺼진 조합은 모순된 설정이다. 
명시적으로 기동 시 실패시켜야 런타임 상황에 알 수 없는 예외가 발생하지 않는다. 
메시지는 "outbox relay가 켜져 있으나 발행 어댑터가 없습니다. `kachi.ai.events.enabled`를 켜거나 `kachi.ai.outbox.relay.enabled`를 끄십시오"다.
relay 설정은 발행 포트를 `ObjectProvider`로 받아 이 메시지를 만든다. `@ConditionalOnBean`은 auto-configuration 밖에서 빈 정의 순서가 보장되지 않아 쓰지 않는다.

어댑터가 없어도 `KafkaTemplate`은 auto-configuration이 만들지만, producer는 첫 `send()`에서야 broker에 붙으므로 어댑터를 끈 인스턴스는 broker 없이 기동한다.

### 설정

```yaml
kachi:
  ai:
    events:
      enabled: true
      topics:
        summary-created: ai.summary.created
        keyword-quarantined: ai.keyword.quarantined
```

### 운영 규칙

- **relay를 켜기 전 PENDING backlog.** 꺼 둔 동안 쌓인 PENDING은 켜는 즉시 batch 50건씩 발행된다. 
  - 오래된 요약이 한꺼번에 알림으로 나가는 것이 원치 않는 결과라면 켜기 전에 `GET /internal/ai/outboxes?status=PENDING`으로 양을 보고, 필요하면 오래된 행을 정리한다.
- **DEAD 복구.** DEAD가 된 outbox를 운영 API로 복구하면 같은 eventKey로 다시 발행된다. 
  - 소비자는 `summaryId` 기준으로 중복을 걸러야 한다(ADR 025 RoutingJob). 발행 쪽은 중복을 막지 않는다.
- **topic 생성.** `ai.*` topic은 ai-service가 소유한다. 
  - 로컬은 `docker-compose.kafka.yml`의 auto-create에 기댄다.

## 반려안

- **`notification.requested`로 관리자 수신처에 직접 fan-out**
  - ai가 채널·수신처를 알게 되고 사용자별 알림으로 확장할 수 없다. ADR 025.
- **payload를 읽어 사람이 읽을 본문으로 렌더링**
  - 본문은 알림의 관심사다. routing이 한다(ADR 025).
- **권한·topic 없음 오류를 non-retryable로**
  - 즉시 DEAD가 자기 회복 가능성만 없앤다.
- **어댑터 자체 timeout**
  - `delivery.timeout.ms`와 이중이다.
- **스위치 하나로 relay와 어댑터를 묶기**
  - 지금은 동작이 같지만 발행 대상이 늘면 나눠야 하고 그때 설정 키가 바뀐다.
- **relay가 켜져 있고 어댑터가 없으면 조용히 보류**
  - 켜져 있다고 믿는데 나가지 않는 상태를 만든다.
- **immediate publish**
  - ADR 022 반려안. latency 요구가 없다.

## 트레이드오프

- 장점: 
  - 어댑터가 "행 하나를 레코드 하나로" 이상을 하지 않는다.   
  - 계약이 모듈로 분리되어 소비자가 같은 타입으로 읽는다.
  - broker 장애가 relay tick을 길게 붙잡지 않는다.

- 단점: 
  - 이 ADR만으로는 알림이 나가지 않는다. 
  - routing(ADR 025 R1)이 붙기 전까지 `ai.*` topic에는 소비자가 없다.
  - 모듈이 하나 늘어난다.

## 후속

- Kafka Testcontainers로 relay -> 발행 -> 계약대로 읽히는지 검증하는 통합 테스트 (ADR 029에서 추가했다)
- 소비자: notification-service routing (ADR 025 R1)
