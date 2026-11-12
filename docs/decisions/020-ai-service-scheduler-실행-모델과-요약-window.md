# 020. ai-service scheduler 실행 모델과 요약 window

## 배경

`ai-service`는 키워드 확장과 뉴스 요약 유스케이스, 중복 실행을 막는 `AiNewsSummaryExecutor`/`AiKeywordExpansionExecutor`, internal API 진입점까지 구현되어 있었다.

하지만 주기 실행 진입점은 없었다.

- `application.yaml`에 `kachi.ai.scheduler.news-summary` 설정이 있었다.
- `AiServiceApplication`에 `@EnableScheduling`이 있었다.
- 정작 `@Scheduled`를 선언한 scheduler 클래스가 없었다.

즉 설정과 스케줄링 활성화만 있고 실행 주체가 없는 상태였다. `ai-service`는 수동 internal API로만 동작했고, kachi 전체 파이프라인은 사람이 매번 API를 호출해야 이어졌다.

전체 파이프라인을 자동으로 돌리려면 다음이 필요하다.

- 주기 뉴스 요약 실행 진입점
- 주기 키워드 확장 실행 진입점
- 매 실행이 "어느 기간의 뉴스를 요약할 것인가"를 정하는 규칙

앞의 두 개는 `collector-service`에 선례가 있지만, 세 번째는 `ai-service`에만 있는 문제다. `collector-service`는 외부 provider에서 "지금 시점의 최신 뉴스"를 가져오므로 기간을 정할 필요가 없다. 반면 `ai-service`는 이미 저장된 뉴스 중 **어디부터 어디까지**를 하나의 요약 입력으로 묶을지 정해야 한다.

## 결정

### 실행 모델

`collector-service`의 실행 모델(ADR 008)을 그대로 따른다.

```text
AiStorySummaryScheduler
  -> AiStorySummaryExecutor
      -> SummarizeDueStoriesUseCase

AiKeywordExpansionScheduler
  -> AiKeywordExpansionExecutor
      -> ExpandKeywordsUseCase
```

scheduler는 `enabled` 확인, 실행 command 구성, 결과 로깅만 담당한다. 중복 실행 방지는 `Executor`에 위임하고, scheduler를 위한 별도 lock을 두지 않는다.

중복 요청 처리도 ADR 008과 같다.

- scheduler 실행은 skip하고 로그를 남긴다.
- internal API 실행은 `409 CONFLICT`를 반환한다.

scheduler는 실행 결과 예외를 삼킨다. `@Scheduled` 메서드에서 예외가 전파되면 이후 tick 동작이 불안정해지므로, 실패는 로그로 남기고 다음 tick을 기다린다.

### 중복 실행 방지

중복 실행 방지는 `adapter.in`의 `AiStorySummaryExecutor`와 `AiKeywordExpansionExecutor`가 담당한다.
scheduler와 internal API 모두 이 `Executor`를 거치므로, 두 진입점이 같은 규칙을 공유한다.

`Executor`는 lock을 직접 구현하지 않고 `ExecutionLockPort`에 맡긴다(ADR 036).

```kotlin
suspend fun execute(command: SummarizeDueStoriesCommand): AiStorySummaryExecutionResult {
    val outcome = executionLock.withLock(AiExecutionLock.STORY_SUMMARY) {
        summarizeDueStoriesUseCase.summarizeDue(command)
    }

    return when (outcome) {
        is Executed -> AiStorySummaryStarted(outcome.value)
        is AlreadyHeld -> AiStorySummaryAlreadyRunning(RunningAiStorySummary(outcome.holder.acquiredAt))
        is Unavailable -> AiStorySummaryLockUnavailable(outcome.cause)
    }
}
```

`Executor`에는 실행 조정만 남는다. 무엇을 보호할지 고르고, lock의 결과를 이 진입점의 언어로 옮긴다.
획득과 해제를 원자 연산으로 하는 것도, 자기가 얻은 lock만 푸는 것도, 작업이 예외로 끝나도 반드시 푸는 것도 포트의 약속이다.
`withLock`이 작업을 감싸는 모양이므로 해제를 잊을 수가 없다.

막힌 요청에 실행 중인 작업의 시작 시각을 함께 주는 규칙은 그대로다.
`ExecutionLockHolder.acquiredAt`이 그 값이며, skip 로그와 internal API의 `409 CONFLICT` 응답에 담긴다.
tick이 계속 skip될 때 이 시각이 유일한 단서가 된다.

lock을 확인할 수 없어 막히는 경우가 하나 더 있다.
이미 실행 중이라 건너뛴 것과 달리 장애이므로, scheduler는 원인과 함께 경고를 남기고 internal API는 `503 SERVICE UNAVAILABLE`을 반환한다.

story 요약 tick과 키워드 확장은 서로 다른 lock 대상이라 서로를 막지 않는다.
두 작업은 입력과 저장 대상이 겹치지 않으므로 동시에 실행되어도 문제가 없고, 24시간 주기의 키워드 확장이 5분 주기의 story 요약 tick을 막아서도 안 된다.

두 대상 모두 `CLUSTER` 범위다. LLM 호출 비용이 걸려 있어 어느 인스턴스에서든 한 번만 돌아야 한다.
지금 그 범위를 맡은 구현은 이 인스턴스 안에서만 유효한 lock이므로, `ai-service`를 여러 개 띄우면 인스턴스마다 별도 lock이 생긴다.
그때 바꾸는 것은 `CLUSTER` 범위의 연결뿐이고 `Executor`와 유스케이스와 scheduler는 그대로 둔다.

### 요약 window와 watermark는 없다

~~요약 window, watermark, window를 정하는 주체, 실행 이력의 구간 기록~~ ADR 035에서 폐지.
요약은 story 이벤트가 즉시 트리거하고, tick은 대기 상한을 넘긴 story만 훑는다(ADR 034). 기간으로 기사를 다시 조회하는 일이 없어 구간과 진행 지점을 둘 이유가 없다.

이 절들은 "겹쳐 읽어 생기는 중복 요약은 ADR 011의 `newsHash` 선조회가 막는다"를 전제로 했고, 그 전제가 틀렸다.
`newsHash`는 기사 묶음이 완전히 같을 때만 같은 값이라, overlap 안에 새 기사가 하나라도 들어오면 묶음이 달라져 같은 기사를 다시 요약했다.
2026-09-07 실데이터에서 같은 키워드의 요약 두 개에 들어간 기사가 153건이었다(ADR 031).

### 반복 실패 대상 격리

요약 대상별 연속 실패 횟수를 세고, 임계치에 도달하면 그 대상을 격리한다. 대상은 story다(ADR 034).

```text
성공 -> consecutiveFailures = 0
실패 -> consecutiveFailures + 1
        임계치(3회) 도달 -> 격리, 이후 요약 대상에서 제외
```

격리 상태는 별도 collection에 남긴다.

```text
ai_story_quarantines
  storyId, consecutiveFailures, lastFailureReason,
  quarantinedAt, releasedAt, status(TRACKING | QUARANTINED | RELEASED)
  unique index: storyId
```

상태를 셋으로 둔 이유는 연속 실패 횟수가 격리 **이전부터** 실행을 넘겨가며 누적되어야 하기 때문이다.
아직 임계치에 닿지 않은 대상도 카운터를 들고 있어야 하므로, 격리되지 않은 추적 상태(`TRACKING`)가 격리 상태(`QUARANTINED`)와 별도로 필요하다.
`quarantinedAt`/`releasedAt`은 현재 상태가 아니라 마지막 격리/해제 시각이고, 현재 상태의 판단 기준은 `status` 하나다.

실패한 적 없는 대상은 문서를 만들지 않는다. 실행마다 전 대상을 기록하면 정상 동작이 쓰기 부하가 된다.

임계치는 scheduler 실행뿐 아니라 소비 경로와 internal API 실행에도 함께 적용되므로 scheduler 설정과 분리한다.

```yaml
kachi.ai.story-quarantine:
  failure-threshold: 3
```

격리된 대상은 요약하지 않는다.
해제는 운영자가 결정하고 자동 해제는 두지 않는다.

해제 진입점은 운영 internal API다.

- `POST /api/v1/internal/ai/story-quarantines/{storyId}/release`가 QUARANTINED를 RELEASED로 옮기고 연속 실패 횟수를 0으로 되돌린다.
- 기록이 없으면 404, 격리 상태가 아니면 409로 응답한다.
- 현황은 `GET /api/v1/internal/ai/story-quarantines`가 보여준다.

격리가 발생하면 `ai.story.quarantined`를 발행하고 notification-routing이 관리자에게 알린다(ADR 035).

### 실행 주기

| 대상 | fixed-delay | 기본 enabled |
| --- | --- | --- |
| story 요약 tick | 5m | true |
| 키워드 확장 | 24h | false |

## 이유

### 강제 진행 대신 격리를 택한 이유

반복 실패하는 대상을 다루는 방식은 둘이다. 일정 시간 실패하면 그 대상을 건너뛰고 진행하거나, 그 대상만 걷어내 격리한다.

원인을 모른 채 건너뛰면 같은 입력이 다시 걸려 같은 실패가 반복되고 그때마다 조금씩 일을 버린다. **자동화가 문제를 조용히 숨기는 방향으로 작동한다.**

실패가 반복되는 대상만 격리하면 나머지는 정상 진행하고, 격리된 대상은 더 이상 시도하지 않으므로 같은 실패가 반복되지도 않는다.

이는 notification-service가 이미 쓰는 DEAD/DLT 처리(ADR 015)와 같은 사고방식이다. 처리할 수 없는 대상을 파이프라인에서 걷어내 격리하고, 나머지 흐름은 계속 진행시키며, 격리된 것은 운영자가 판단한다.

격리 해제를 자동으로 하지 않는 이유도 같다. 3회 연속 실패했다는 것은 일시적 오류가 아닐 가능성이 높고, 원인을 모른 채 자동으로 되돌리면 격리가 무의미해진다.

### 키워드 확장 주기를 길게 둔 이유

키워드 확장은 story 요약과 성격이 다르다.

- 입력이 사용자 관심 키워드 자체이고, 시간에 따라 변하지 않는다.
- 결과는 `keyword + promptVersion + model` 기준으로 중복 저장이 차단된다(ADR 010).
- 반면 비용은 키워드 수만큼의 LLM 호출로 매번 동일하게 발생한다.

story 요약 tick처럼 짧은 주기로 실행하면 매번 같은 결과를 얻기 위해 LLM을 호출하게 된다. 24시간 주기는 신규 키워드 등록을 하루 안에 반영하면서 불필요한 호출을 막는 선이다.

기본 `enabled: false`인 이유는 알림 파이프라인이 story 요약 결과만 사용하고, 확장 키워드를 소비하는 주체가 아직 없기 때문이다. 소비자가 생기면 켠다.

## 결과

- `ai-service`가 수동 API 없이 대기 상한을 넘긴 story를 주기적으로 요약한다.
- scheduler와 internal API가 executor의 같은 실행 lock(`ExecutionLockPort`, ADR 036)을 공유한다. 중복 실행 방지 규칙이 진입점마다 갈리지 않는다.
- 실행 중인 작업의 시작 시각이 skip 로그와 `409 CONFLICT` 응답에 함께 드러난다.
- 반복 실패하는 story가 다른 story의 요약을 막지 않는다.
- 어느 story가 언제 왜 격리됐는지가 격리 기록으로 DB에 남는다.

## 제외한 것

현재는 격리 자동 해제를 두지 않는다.

TTL 기반 자동 재시도는 원인이 해소되지 않은 상태에서 같은 실패를 반복시킨다. 해제는 운영자가 원인을 확인한 뒤 결정하고, 그 판단을 실행하는 수단이 격리 해제 API다.

현재는 scheduler 단위의 distributed lock을 도입하지 않는다.

ADR 008과 같은 이유로, 단일 인스턴스 배포 모델에서는 in-memory lock으로 충분하다. 범위 선언과 교체 지점은 `ExecutionLockPort`와 ADR 036이 정한다.
