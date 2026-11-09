# 024. 상태 전이 aggregate는 불변으로 표현하고 결과 확정은 CAS로 한다

## 배경

kachi 프로젝트에는 상태 전이 그래프를 가진 aggregate가 두 모듈에 있다.  
notification의 `Notification`·`NotificationOutbox`·`NotificationDltMessage`와 ai-service의 `AiOutbox`다.  
형태는 같다. `class ... private constructor`, 정적 팩터리, 전이 가드.  
그런데 구현하고 보니 전이를 표현하는 모양을 모듈마다 다르게 잡았다.  
notification은 `var ... private set` 프로퍼티를 전이 메서드가 직접 바꾸고, ai-service는 전이 메서드가 새 인스턴스를 반환한다.  
어느 쪽도 setter는 아니다. notification 쪽은 Evans식 가변 Entity이고, 처음에는 "상태 전이가 도메인의 본질이니 전이 메서드가 자기 상태를 바꾸는 모양이 자연스럽다"는 이유로 이 차이를 의도된 것으로 두었다.

이 차이가 취향 문제로 끝나지 않는다는 것이 드러난 지점은 저장 방식이다.  
외부 side effect(Kafka publish, vendor API 호출) 뒤에 결과를 DB에 확정할 때는 "내가 claim한 그 행이 아직 그대로일 때만" 저장하는 CAS를 쓴다(ADR 015, 017).  
CAS의 기대값은 전이 **전**의 `claimedAt`·`claimedBy`인데, 가변 전이 메서드는 전이하면서 그 값을 지운다.  
그래서 전이하기 전에 값을 따로 떠 놓아야 하고, notification-worker에 그런 스냅샷 코드가 두 벌 생겼다(`DispatchNotificationService`의 `ProcessingClaim`, `RecoverStaleProcessingDispatchService`의 `requireNotNull` 두 줄).  
notification-service의 outbox publish 확정은 아직 CAS가 아니라 `mongoTemplate.save` 덮어쓰기라서, ADR 015에 규칙으로만 적어 둔 "늦은 publish 결과는 outbox가 아직 `PUBLISHING`일 때만 반영한다"를 저장소가 강제하지 못한다.  
여기에 CAS를 넣으면 스냅샷이 세 벌이 된다.

가변 전이에는 또 하나의 약점이 있다. 같은 인스턴스를 전이 -> 읽기 -> 전달 순으로 쓰는 코드의 순서를 컴파일러가 지켜 주지 않는다.  
`NotificationAdminService`에서 DEAD 복구 전이와 dispatch payload 직렬화의 순서가 바뀌면 DEAD 상태 payload가 Kafka로 나가는데,  
그 실수를 잡는 것은 테스트뿐이다.

같은 전이 그래프를 두 모양으로 유지하면서 저장 방식과 마찰을 감수할 것인지, 한 모양으로 통일할 것인지를 정해야 한다.

## 결정

1. **상태 전이 aggregate는 불변이다.**
   - 모든 필드는 `val`이고 전이 메서드는 `fun markX(...): T`로 새 인스턴스를 반환한다.
   - 전이 전 인스턴스는 그대로 남는다.
2. **외부 side effect 뒤의 결과 확정(finalize)은 CAS로 한다.**
   - 저장소 포트는 전이 후 인스턴스와 함께 전이 전 인스턴스의 claim 값을 기대값으로 받고, `_id + 상태 + claim` 조건이 맞을 때만 갱신한다.
   - 조건이 어긋나면 아무것도 바꾸지 않고 그 사실만 돌려준다 (worker는 `null`, service는 `false`).
   - 호출부는 이를 늦은 결과로 보고 warn만 남기며 집계에서 뺀다.
   - 전이 전에 값을 따로 떠 놓는 코드는 두지 않는다.
3. **작성 규칙.**
   - `class X private constructor(...)`를 유지한다.
   - data class는 쓰지 않는다.
     - 공개 `copy`로 전이 가드를 우회할 수 있고 `equals`가 식별자가 아닌 필드 전체 비교가 된다.
   - private 생성자와 `restore()`에는 default 인자를 두지 않는다. 필드를 추가했을 때 누락을 컴파일이 잡아야 한다.
   - 전이가 둘 이상이면 private `copy(...)` 헬퍼를 두고 named argument만 쓴다. 전이해도 바뀌지 않는 식별자·요청 내용·생성 시각은 헬퍼 인자에서 뺀다.
   - 불허 전이는 `check(...)`로 `IllegalStateException`, 인자 검증은 `require(...)`로 `IllegalArgumentException`.
   - 상태 전이 이력을 누적하는 aggregate(`Notification`)는 이력도 불변 리스트 필드로 들고, 전이마다 `histories + record`로 붙인다.
   - 도메인 테스트는 전이마다 "원본 인스턴스는 그대로다"를 최소 한 번 assert한다.
4. **같은 상태로의 멱등 조기 반환은 입력 경로에 따라 모듈별로 정한다.**
   - notification은 Kafka consumer 재전달과 admin API 재호출이 실제 입력이고 ADR 014·017이 멱등 마커를 전제하므로 둔다(`if (status == PUBLISHED) return this`).
   - ai-service `AiOutbox`는 늦은 결과를 CAS가 거르므로 두지 않는다.

## 근거

- 스냅샷이 필요 없어진다.
  - 전이 전 인스턴스가 살아 있으므로 `val sent = claimed.markSent(now)` 뒤에 `claimed.claimedAt`을 그대로 CAS 기대값으로 쓴다.
  - service outbox finalize에 CAS를 넣어도 스냅샷이 늘지 않는다.
- 순서 의존이 사라진다.
  - 전이 결과를 `recovered`·`sent`·`published` 같은 새 이름으로 받으면, 그 아래 줄에서 원본 이름이 보이는 것 자체가 잘못 읽고 있다는 신호가 된다.
- Mongo 매핑은 가변을 요구하지 않는다.
  - 세 모듈 모두 Document 클래스를 따로 두고 `restore()`로 복원하며 어댑터는 도메인 객체를 읽기만 한다. 전환해도 어댑터 클래스는 바뀌지 않는다.
- 이력 누적도 불변으로 표현된다.
  - `List<NotificationHistory>`를 `copy(uncommittedHistories = uncommittedHistories + record)`로 넘기면 되고, 어댑터가 한 번에 insert하는 구조는 그대로다.
- 비용은 `copy` 헬퍼가 흡수한다.
  - 헬퍼가 있는 `AiOutbox`는 전이당 바뀌는 필드만 적는다.
  - 헬퍼 없이 전이마다 생성자 인자 전부를 다시 적는 `StoryQuarantine`이 반례이고, 그쪽도 헬퍼를 두는 것이 후속이다.
- DDD 원전은 근거가 아니다.
  - Evans의 Entity는 원래 가변이고, 불변 선호는 Kotlin과 함수형 관용이다.
  - 식별자로 연속성을 유지하며 상태가 바뀌는 객체가 Entity의 정의이고, 불변은 Value Object의 성질이다.
  - (Evans, *Domain-Driven Design*, 2003, ch.5 "Entities" / "Value Objects" — "Treat the VALUE OBJECT as immutable";  
    저자 요약본 [DDD Reference](https://www.domainlanguage.com/ddd/reference/)).
  - Kotlin 쪽은 [Coding Conventions › Immutability](https://kotlinlang.org/docs/coding-conventions.html#immutability)  
    — "Prefer using immutable data to mutable."
  - 판정 기준은 저장 방식(CAS)과의 마찰이지 교과서가 아니다. 애초에 notification을 가변으로 둔 이유("자연스럽다")도 같은 종류의 취향 논거였다.

## 검토한 대안

- **ai-service를 가변으로 통일한다.**
  - `RelayAiOutboxService`의 finalize CAS가 스냅샷을 요구하게 된다. 마찰을 없애려는 방향과 반대라 기각한다.
- **모듈별로 다르게 둔다.**
  - 수정 전 상태다. notification-service outbox finalize에 CAS를 넣는 순간 세 번째 스냅샷이 생기고, 같은 전이 그래프가 두 모양으로 계속 남는다.
- **`data class` + 공개 `copy`.**
  - 가장 짧다. 그러나 결정 3의 첫 항목 이유로 기각한다.
- **outbox finalize를 CAS 없이 둔다.**
  - ADR 015의 규칙이 문서에만 있는 상태가 계속된다.
  - publish가 visibility timeout보다 오래 걸려 다른 tick이 회수한 뒤 늦은 결과가 도착하면 회수 이후의 상태가 덮인다.

## 결과

notification 세 aggregate가 불변이 되고, `ProcessingClaim`과 `requireNotNull` 스냅샷이 없어진다.  
어댑터 안에서 도메인을 전이하는 `NotificationMongoPublishPersistenceAdapter`는 그 구조를 유지한 채(트랜잭션 경계가 어댑터다) 전이 결과로 document와 이력을 만든다.

동작이 바뀌는 곳은 하나다.  
`NotificationMongoPublishPersistenceAdapter.savePublished/savePublishFailed`가 `mongoTemplate.save` 덮어쓰기에서  
`findAndModify(_id + PUBLISHING + claimedAt + claimedBy)`가 된다.  
ADR 015 "늦은 publish 결과 처리"가 저장소 조건으로 강제되며, 불일치 시 outbox도 `Notification`도 건드리지 않는다.  
따라서 `PublishNotificationDispatchService`의 `processed` 집계는 조회된 행 수가 아니라 실제로 claim한 행 수가 된다.

CAS 불일치는 저장소 장애가 아니라 늦은 결과이므로 예외가 아닌 warn이고, 그 판단은 서비스가 한다.  
그래서 `notification-core`에 `org.slf4j:slf4j-api`를 둔다. core 모듈에는 지금까지 로깅이 없었고 로그는 전부 어댑터에 두었다.  
core에 로그가 더 늘어야 할 이유는 지금 없으며, 늘어난다면 그 판단을 어댑터로 옮겨야 한다는 신호로 본다.

[도메인모델.md](../도메인모델.md)의 불변성 관례, [도메인모델-notification.md](../도메인모델-notification.md)의 서두,  
[개발가이드.md](../개발가이드.md)의 도메인 모델 관례를 이 결정으로 고친다. ADR 015 본문에 finalize 구현 방식을 반영한다.

## 두 모듈에 남기는 차이

같은 규칙 아래에서 notification과 ai-service가 다르게 남는 것은 셋이고, 전부 의도한 것이다.

| 항목 | notification | ai-service | 이유 |
| --- | --- | --- | --- |
| 멱등 조기 반환 | 둔다 | 없다 | 결정 4. 입력 경로가 다르다 |
| claim 표현 | `claimedAt`·`claimedBy` 두 필드 | `AiOutboxClaim` VO | notification은 CAS 기대값이 두 값뿐이라 VO 도입은 필요해질 때로 미룬다 |
| 헬퍼 이름 | 기계적 필드 교체 `copy`, 이력 붙이는 전이 `transition` | `transition` 하나 | `Notification`은 이력을 붙이는 단계가 따로 있어 한 이름이 두 뜻을 갖지 않게 나눈다 |

## 트레이드오프

- 전이 결과를 받지 않는 호출(`notification.markSent(now)` 한 줄)이 컴파일되고 조용히 무동작이 된다. 가변에서는 없던 실수 유형이다.  
  Kotlin에 `@CheckReturnValue`에 해당하는 표준 강제 수단이 없으므로, 반환값을 버리는 전이 호출이 없는지를 리뷰 기준으로 둔다.  
  전이 결과를 항상 새 이름으로 받는 습관이 이 실수도 같이 막는다.
- 전이마다 인스턴스를 새로 만든다. aggregate는 필드 20개 안쪽의 작은 객체이고 전이는 요청당 수 회라 측정할 만한 비용이 아니다.
- 이력 누적이 `List + record`라 전이 횟수만큼 리스트를 복사한다. 한 요청에서 쌓이는 이력은 최대 4건이다.
- `notification-core`가 로깅 API에 의존하게 된다. 기술 의존이 아니라 API 하나이고 binding은 실행 모듈이 제공하지만, 이 모듈이  
  "로그 없는 순수 core"였다는 성질은 잃는다.
