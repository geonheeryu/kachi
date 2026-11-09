# 036. 실행 lock 포트 분리와 lock 범위

## 배경

scheduler와 internal API 두 진입점에서 같은 작업이 동시에 시작되지 않도록, 서비스마다 `Executor`를 두고 중복 실행을 막아 왔다.
그런 `Executor`는 collector-service의 뉴스 수집과 outbox relay, ai-service의 키워드 확장과 story 요약 tick과 outbox relay, story-service의 주기 작업과 outbox relay에 있다.

lock이 `Executor` 안에 있으면 `Executor`마다 같은 코드를 각자 갖는다.
`AtomicReference`를 필드로 두고, `compareAndSet`으로 획득하고, `finally`에서 다시 `compareAndSet`으로 해제한다.

ADR 008과 ADR 020은 이 lock이 단일 인스턴스 전제임을 밝히고, 분산 실행이 필요해지면 distributed lock으로 바꾼다고 적었다.
그런데 그 교체를 실제로 하려면 `Executor`를 모두 열어야 한다.
lock 구현이 `Executor` 안에 들어 있어서, 바깥에서 갈아 끼울 수 있는 지점이 없기 때문이다.
application service는 lock을 모르지만, 경계가 거기서 끝나고 `Executor` 아래로는 내려가지 않았다.

같은 코드를 쓰지만 성격은 같지 않다.
뉴스 수집과 story 요약 tick과 키워드 확장은 여러 인스턴스가 동시에 하면 외부 provider를 중복 호출하고 LLM 비용을 여러 배로 쓴다.
반면 outbox relay는 여러 인스턴스가 동시에 돌아도 된다.
같은 행을 두 번 발행하는 것은 저장소의 조건부 쓰기가 이미 막고 있고, relay가 막는 것은 앞선 tick이 끝나기 전에 다음 tick이 같은 행을 다시 조회하는 낭비다.
이 차이를 코드가 말하지 않으면, 분산 lock을 넣을 때 relay까지 함께 끌려가 인스턴스를 늘려도 발행은 한 대만 하게 된다.

## 결정

중복 실행 방지를 `ExecutionLockPort`로 옮긴다.
`Executor`는 이 포트를 통해 "한 번에 하나만"이라는 규칙만 선언하고, 그 규칙을 무엇이 어떻게 지키는지는 adapter가 정한다.

```kotlin
interface ExecutionLockPort {
    suspend fun <T> withLock(
        target: ExecutionLockTarget,
        action: suspend () -> T
    ): ExecutionLockOutcome<T>
}
```

lock이 미치는 범위는 실행 단위가 선언한다.

```kotlin
enum class ExecutionLockScope {
    INSTANCE,
    CLUSTER
}
```

보호 대상은 enum으로 닫는다.
서비스마다 자기 실행 단위를 선언하며, 아래는 ai-service의 것이다.

```kotlin
enum class AiExecutionLock(
    override val key: String,
    override val scope: ExecutionLockScope
) : ExecutionLockTarget {
    KEYWORD_EXPANSION("ai-service:keyword-expansion", CLUSTER),
    STORY_SUMMARY("ai-service:story-summary", CLUSTER),
    OUTBOX_RELAY("ai-service:outbox-relay", INSTANCE)
}
```

범위를 구현으로 잇는 일은 `RoutingExecutionLockAdapter`가 한다.
지금은 두 범위 모두 `InMemoryExecutionLockAdapter`로 간다.
클러스터 lock의 저장소가 생기면 `CLUSTER` 쪽 연결만 바꾼다.

요청의 결과는 셋이다.

```kotlin
sealed interface ExecutionLockOutcome<out T> {
    data class Executed<T>(val value: T) : ExecutionLockOutcome<T>
    data class AlreadyHeld(val holder: ExecutionLockHolder) : ExecutionLockOutcome<Nothing>
    data class Unavailable(val cause: Throwable) : ExecutionLockOutcome<Nothing>
}
```

lock을 확인할 수 없으면 작업을 실행하지 않는다.
scheduler는 로그를 남기고 이번 tick을 건너뛰고, internal API는 `503 SERVICE UNAVAILABLE`을 반환한다.
이미 다른 실행이 쥐고 있어 건너뛰는 경우는 지금처럼 `409 CONFLICT`다.

## 이유

### 갈아 끼울 수 있는 지점은 포트여야 한다

ADR 008은 수집 유스케이스가 lock 구현을 모른다는 점에서 헥사고날의 이점을 설명했다.
그 설명은 맞지만 경계가 한 겹 모자랐다.
`CollectNewsService`는 lock을 모르는데 `NewsCollectionExecutor`는 lock 구현 그 자체를 들고 있었다.

포트가 없으면 구현을 바꾸는 일이 교체가 아니라 수술이 된다.
`Executor`를 모두 열어 같은 수정을 여러 번 하고, 그것들이 서로 어긋나지 않기를 사람이 확인해야 한다.
포트를 두면 바뀌는 것은 adapter 하나와 그것을 고르는 조립 한 줄이다.

이는 DIP다.
실행을 조정하는 쪽이 lock 저장소에 의존하지 않고, 양쪽이 함께 인터페이스에 의존한다.
동시에 SRP이기도 하다.
`Executor`에는 실행 조정만 남고, 획득과 해제라는 별개의 책임은 adapter로 간다.

### 범위는 인프라가 아니라 실행 단위의 성질이다

어디까지 막아야 하는지는 그 작업이 무엇을 보호하려는지에서 나온다.
외부 provider 호출과 LLM 호출은 클러스터 전체에서 한 번이어야 하고, relay의 tick 겹침은 인스턴스 안에서만 막으면 된다.
lock 저장소를 무엇으로 하느냐와는 관계가 없는 성질이다.

그래서 범위를 adapter의 설정이 아니라 실행 단위의 선언에 둔다.
relay가 `INSTANCE`인 것은 규모가 작아서가 아니라 저장소가 이미 행 단위로 보호하기 때문이며, 그 이유는 규모가 커져도 변하지 않는다.
범위를 좁게 잡으면 중복이 나고 넓게 잡으면 나눠 처리할 수 있는 일을 한 대에 묶는데, 어느 쪽인지는 코드를 읽으면 보여야 한다.

선언을 enum에 두면 범위를 바꾸는 일이 눈에 띈다.
relay를 클러스터 lock으로 옮기려면 상수를 고쳐야 하고, 그 변경은 리뷰에 그대로 드러난다.

### 정체는 코드가, 시간은 설정이 갖는다

lock 키를 호출부에 문자열로 적으면 오타를 막을 수 없고 같은 대상이 두 이름으로 불릴 수 있다.
무엇을 보호하는지와 어디까지 보호하는지는 코드가 답할 문제이므로 enum으로 닫는다.
새 실행 단위가 생기면 상수를 더하고, 그때 범위를 정하지 않고는 넘어갈 수 없다.

임대 시간만 설정으로 남긴다.
같은 작업도 배포 환경에 따라 얼마나 오래 걸리는지가 다르고, 그것은 코드가 미리 알 수 없다.
호출자가 임대 시간을 넘기지 않는 것도 같은 이유다. 호출부마다 시간을 적으면 같은 대상의 임대가 부르는 곳마다 달라진다.

설정 항목 자체는 그 값을 읽는 구현이 들어올 때 더한다.
인스턴스 안에서만 유효한 lock은 임대 시간을 쓰지 않으므로, 지금 항목을 두면 아무도 읽지 않는 값이 된다.
포트가 임대를 계약에 담고 있으므로 나중에 항목을 더해도 호출자는 바뀌지 않는다.

이 구분은 ADR 030이 LLM 모델을 다루는 방식과 같다.
모델의 정체는 enum이 갖고 yaml은 주소와 시간만 갖는다.

### 획득과 해제를 하나로 묶는다

`tryAcquire`와 `release`를 따로 두지 않고 `withLock` 하나로 둔다.

ADR 020은 해제를 `finally`에 두는 이유를 이렇게 적었다.
유스케이스가 예외로 끝나도 lock이 반드시 풀려야 하며, 그렇지 않으면 호출 한 번의 실패로 이후 모든 tick이 영구히 skip된다.
이것을 규칙으로만 두면 호출부마다 지켜야 하지만, 인터페이스를 이 모양으로 두면 지키지 않을 수 없다.

소유 토큰이 밖으로 나가지 않는다는 이점도 있다.
남의 lock을 실수로 푸는 일이 애초에 표현되지 않으며, 임대 연장 같은 저장소별 사정을 adapter 안에 감출 수 있다.

### 막힌 이유를 둘로 나눈다

다른 실행이 쥐고 있어 막힌 것과 lock 자체를 판단할 수 없어 막힌 것은 다르다.
앞은 정상이고 뒤는 장애다.
전자는 다음 tick에 자연히 풀리지만 후자는 사람이 봐야 한다.

인메모리 lock은 판단에 실패할 수 없어 `Unavailable`을 내지 않는다.
그럼에도 지금 이 결과를 둔다.
결과 타입은 sealed이고 진입점들이 `when`으로 모든 경우를 처리하므로, 나중에 경우를 더하면 scheduler와 controller가 함께 컴파일되지 않는다.
그 강제는 규칙이 갈라지지 않게 하는 장치이지만, 동시에 결과를 늘리는 일이 호출부 전체를 건드리는 일이라는 뜻이기도 하다.
자리를 지금 만들어 두면 저장소가 바뀔 때 바뀌는 것이 adapter 하나로 남는다.

### 판단할 수 없으면 실행하지 않는다

lock을 읽지 못했을 때 그냥 실행하는 선택도 가능하지만 그렇게 하지 않는다.
그 순간은 lock이 필요한 이유가 사라진 것이 아니라 확인할 수단이 사라진 것이고, 실행하면 인스턴스 수만큼 외부 provider를 호출하거나 LLM을 부른다.
한 tick을 쉬는 손해가 훨씬 작으며, 수집과 요약은 다음 실행이 남은 일을 가져간다.

### 임대는 소유자가 사라진 뒤의 상한이다

임대 시간은 소유자가 사라졌을 때 lock이 회수되기까지의 상한이며, 소유자가 살아 있는 동안에는 유지된다.

이렇게 정의해야 두 구현이 같은 약속을 지킨다.
인메모리에서 소유자가 사라진다는 것은 프로세스가 끝난다는 뜻이고 그때는 lock을 담은 자료구조도 함께 사라지므로, 별도의 만료 처리 없이 약속이 지켜진다.
저장소를 쓰는 구현에서는 만료 시간이 그 상한이 되고, 작업이 임대보다 길어질 때 연장하는 책임이 adapter에 생긴다.

정의를 적어 두지 않으면 클러스터 lock을 만들 때 작업 도중 임대가 끝나는 경우가 미결로 남는다.
임대가 작업보다 짧아 다른 인스턴스가 끼어드는 것은 lock을 걸지 않은 것보다 나쁘다.

## 결과

`Executor`마다 흩어져 있던 같은 코드가 서비스마다 adapter 한 곳으로 모인다.
`Executor`는 lock을 거쳐 유스케이스를 부르고 결과를 자기 타입으로 옮기는 일만 한다.
시각을 다루는 책임도 함께 adapter로 옮겨간다.

클러스터 lock을 도입할 때 `ExecutionLockPort` 구현 하나를 더하고 `CLUSTER` 범위의 연결을 바꾼다.
`Executor`와 결과 타입과 scheduler와 controller와 application service는 그대로 둔다.
그 저장소가 무엇이 될지와 임대 연장을 어떻게 할지는 도입 시점에 정한다.

새 실행 단위를 보호하려면 enum에 상수를 더한다.
범위를 정하지 않고는 상수를 만들 수 없으므로, 무엇까지 막을지 결정하는 일을 건너뛸 수 없다.

`Unavailable`은 당분간 어떤 경로로도 반환되지 않는다.
지금은 쓰이지 않는 분기를 진입점마다 갖고 있게 되며, 그 비용을 감수한다.
