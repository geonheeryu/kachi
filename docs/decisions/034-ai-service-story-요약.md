# 034. ai-service story 요약

## 배경

ADR 031은 요약 단위를 키워드에서 story로 바꿨고, ADR 033은 story-service가 기사를 story로 조립해 `story.article.attached`·`story.merged`를 발행하게 했다.
이 ADR은 그 이벤트를 받아 story 단위로 요약하는 ai-service 쪽 결정을 담는다.

키워드 요약 경로는 tick마다 기간으로 뉴스를 다시 조회하고 `newsHash`로 중복을 걸렀다(ADR 011, 020).
story 경로에서 정해야 하는 것은 다음 넷이다.

1. 요약에 필요한 story와 기사를 어디서 읽는가.
2. 언제 요약하는가. 기사 한 건마다 LLM을 부를 수는 없다.
3. 같은 기사 묶음이 두 번 요약되지 않게 무엇으로 막는가.
4. 요약 결과를 누구에게 어떤 이벤트로 알리는가.

## 검토한 선택지

| 질문 | 선택지 | 채택 |
| --- | --- | --- |
| story·기사 읽기 | story-service internal API 조회 / 이벤트로 받은 사본 | 이벤트 사본 |
| 미요약 판별 | 마지막 요약 시각과 `attachedAt` 비교 / 기사마다 실린 버전 표시 | 버전 표시 |
| 요약 시점 | 기사마다 / 주기 tick만 / 미요약 수 즉시 트리거 + 대기 상한 tick | 즉시 트리거 + tick |
| 멱등 | 기사 id 집합 해시 / `(storyId, version)` | `(storyId, version)` |
| story 격리 발행 | `ai.keyword.quarantined`에 새 버전 / 새 topic | 새 topic |

## 결정

### 도메인: story와 기사는 사본이다

story의 정본은 story-service이고 기사 사실의 정본은 collector다.
ai-service는 이벤트가 실어 온 값으로 `AiStory`·`AiStoryArticle` 사본을 만들고, 요약은 이 사본만 읽는다.
story-service를 조회하지 않으므로 story-service가 내려가 있어도 이미 받은 기사는 요약된다.

`AiStory`가 갖는 것은 요약 시점을 판정하는 데 필요한 값이다.
미요약 기사 수, 가장 오래된 미요약 기사의 `attachedAt`, 마지막 요약 버전과 그 시각, 흡수된 경우 흡수한 story다.
키워드와 기사 수는 이벤트 스냅샷의 합집합과 최댓값으로만 자란다.

기사가 어느 요약 버전에 실렸는지는 `AiStoryArticle.summarizedInVersion`이 말한다.
null이면 미요약이다. 시각 비교를 쓰지 않는 이유는 재전달과 순서 역전에서 같은 기사가 빠지거나 두 번 실리기 때문이다.

### 트리거: 미요약 수와 대기 상한

트리거 조건은 둘이다.

- 미요약 기사가 `min-new-articles` 이상이면 기사를 기록하거나 병합을 반영한 그 자리에서 요약한다.
- 미요약 기사가 하나라도 있고 기준 시각이 `max-wait`를 넘겼으면 주기 tick이 요약한다. 기준 시각은 마지막 요약 시각이고, 요약이 없으면 가장 오래된 미요약 기사의 `attachedAt`이다.

한 버전에 싣는 새 기사는 `max-articles-per-version`까지이고 오래된 순이다. 남은 미요약은 다음 트리거가 가져간다.
값은 전역 하나다. story 크기나 키워드별 값은 두지 않는다.

즉시 트리거는 소비 스레드에서 동기로 실행한다.
LLM이 느리면 소비가 늦어지는데, 이것은 요약이 밀린 만큼 기사를 덜 받는 의도된 backpressure다.

### 멱등: `(storyId, version)`과 story CAS

요약 버전은 story마다 1부터 연속으로 매긴다.
요약 insert, 실린 기사의 `summarizedInVersion` 표시, `AiStory` 전이, outbox insert를 한 트랜잭션으로 쓴다.
`(storyId, version)` unique index, 기사 표시 건수, `AiStory.version` CAS 가운데 하나라도 어긋나면 트랜잭션을 되돌리고 이번 LLM 결과를 버린다.
어긋났다는 것은 LLM을 부르는 동안 다른 실행이 story 상태를 바꿨다는 뜻이다. 다른 요약, 새 기사 기록, 병합이 모두 해당한다.
남은 미요약은 다음 트리거가 가져간다.

`newsHash`는 쓰지 않는다. 기사 id 집합을 비교할 일이 없고, 버전이 연속이라 직전 버전도 번호로 찾는다.

### `developmentKind`: 버전이 이전 버전에 대해 무엇인가

LLM은 직전 버전 요약과 새 기사를 함께 받아 요약과 `developmentKind`를 돌려준다.

| 값 | 뜻 | 발행 |
| --- | --- | --- |
| `DEVELOPMENT` | 같은 사건의 새 전개 | `ai.summary.created` |
| `NEW_STORY` | 새 기사 전부가 다른 사건을 다룬다 | `ai.story.split-requested` |
| `NO_CHANGE` | 새 정보가 없다 | 없음 |

세 경우 모두 버전은 저장하고 기사는 실린 것으로 표시한다. 같은 기사로 LLM을 다시 부르지 않기 위해서다.
첫 버전은 비교할 이전 버전이 없으므로 LLM 응답과 무관하게 `DEVELOPMENT`로 고정한다.
`NEW_STORY`에서 요약 알림을 내지 않는 이유는 다른 사건으로 판정된 기사의 요약이 그 story의 알림으로 나가면 안 되기 때문이다.
`developmentKind`가 없거나 모르는 값이면 응답 계약 위반(`LLM_INVALID_RESPONSE`)으로 다룬다.

### 병합과 순서

`story.merged`를 받으면 흡수된 story의 미요약 기사와 키워드를 흡수한 story로 옮기고, 흡수된 story에는 `mergedInto`를 남긴다.
`story.article.attached`와 `story.merged`는 topic이 달라 서로 순서가 보장되지 않는다. 순서 보장은 한 topic의 같은 파티션 안에서만이다.
늦게 온 기사는 `mergedInto`를 따라가 흡수한 story에 기록한다.
병합이 흡수한 story의 사본보다 먼저 오면 예외로 재전달시켜 순서를 되찾는다.
모르는 story가 흡수된 경우는 흡수 사실만 기록하는 자리표시를 남긴다.

### 실패와 격리

격리 판정은 키워드 요약과 같다(ADR 021).
시도한 후보 전부가 입력 탓으로 끝난 실패만 story 탓으로 세고, 연속 실패가 `kachi.ai.quarantine.failure-threshold`에 닿으면 `StoryQuarantine`이 격리 상태가 된다.
임계값을 키워드 격리와 같이 쓰는 이유는 같은 정책 값이고 설정 키가 대상을 가리지 않기 때문이다.

해제는 운영자가 internal API로 한다. 격리 중에도 기사는 기록되고, 해제 뒤 다음 트리거가 쌓인 미요약을 가져간다.

story 탓 실패는 소비를 정상 종료한다. 같은 입력은 다시 실패하므로 재전달하지 않는다.
그 밖의 실패(LLM 제공자, 저장소, 기사 기록·병합의 CAS 경합)는 소비 경로에서는 예외로 올려 같은 offset을 상한 없는 backoff로 다시 처리한다.
tick 경로에서는 그 story만 실패로 두고 다음 tick을 기다리며, 호출할 수 있는 후보 모델이 없으면 남은 story를 건너뛴다.
DLT(`ai.story.dlt`)로 보내는 것은 읽을 수 없는 메시지뿐이다. 요약할 수 있는 기사를 DLT에 넣으면 그 기사는 다시 요약되지 않는다.

story 격리는 새 계약 `AiStoryQuarantinedEvent`와 새 topic `ai.story.quarantined`로 발행한다.
`ai.keyword.quarantined`에 실으면 topic 이름이 내용과 어긋나고, 계약 하나가 두 대상의 필드를 nullable로 갖게 된다.

주기 tick은 `AiRun`을 남기지 않는다.
`AiRun`의 필드는 window와 watermark 중심이고, 대상 종류를 늘리면 키워드 격리 계약의 매핑까지 바뀐다. tick 결과는 scheduler 로그에 남는다.

### 발행 계약과 스위치

story 요약은 `ai.summary.created`에 schemaVersion 2 계약(`AiSummaryCreatedEventV2`)으로 나간다. key는 storyId다.
키워드 요약의 schemaVersion 1 계약은 키워드 경로가 폐지될 때 함께 지우고, 그때 v2 계약이 `AiSummaryCreatedEvent` 이름을 이어받는다.
v2 계약의 필드와 발행은 이 ADR이, notification-routing의 v2 소비와 storyId 멱등은 ADR 035가 정한다.

`kachi.ai.story-summary.events-enabled`가 꺼져 있으면 story 경로의 세 이벤트는 outbox 행 자체를 남기지 않는다. 기본값은 false다.
v2를 읽지 못하는 소비자가 남아 있는 동안 v2가 topic에 실리면 그 소비자의 DLT가 오염되고, 키워드 요약 알림과 겹쳐 같은 기사가 두 번 알림된다.
스위치가 꺼진 동안 만든 요약 버전은 나중에 켜도 발행되지 않는다.

## 검토한 대안

- 누적 기사 id 목록을 버전마다 저장: 상한에 닿은 story에서 버전마다 같은 목록이 반복 저장되고, 표시 방식에서는 비교할 일이 없다.

## 트레이드오프

- 사본은 이벤트가 실어 온 값만 안다. story-service가 기사를 떼거나 story를 닫아도 ai-service의 사본에는 반영되지 않는다.
- 즉시 트리거가 소비 스레드를 잡으므로 LLM 지연이 소비 lag로 나타난다.
- 재전달에 상한이 없으므로 LLM 제공자나 저장소가 오래 멈추면 그 파티션의 소비가 같은 offset에서 멈춘다. 유실은 없고 lag로 드러난다.
- tick이 LLM을 부르는 동안 같은 story에 기사가 붙으면 그 결과는 버려진다. LLM 호출 한 번이 낭비되고 요약은 다음 트리거로 밀린다.
- `NO_CHANGE`와 `NEW_STORY` 버전도 번호를 쓰므로 발행된 버전 번호는 연속이 아닐 수 있다.
- 미요약이 `min-new-articles`보다 적게 쌓인 story는 `max-wait`까지 기다린 뒤 요약된다.

## 운영 제약

- 한 poll의 LLM 호출 시간이 `max.poll.interval.ms`를 넘으면 consumer가 그룹에서 빠진다. 그래서 `max-poll-records`를 작게 둔다.

## 후속

- ADR 035: notification-routing의 v2 소비와 storyId 멱등. 발행 스위치를 켜는 시점과 키워드 요약 경로 폐지를 함께 정한다.
- `ai.story.split-requested`의 소비자는 열린 항목이다(ADR 033).
