# AI 컨텍스트 도메인 모델

ai-service의 도메인 모델이다. story-service의 이벤트를 받아 story마다 LLM 요약 버전을 쌓고, 키워드를 확장한다.
반복 실패하는 story는 격리해 다른 story의 요약을 막지 않는다.
공통 관례는 [도메인모델.md](도메인모델.md)를 따른다.

관련 결정: 
  - ADR 010(초기 설계), 020(scheduler 실행 모델과 격리),
  - 021(LLM 실패 분류·서킷·failover·격리), 030(LLM 호출 단위·실패 분류·실호출 검증),
  - 022(outbox와 이벤트 발행 보장), 031(요약 단위를 story로), 034(story 요약), 035(routing의 story 요약 소비).

## 격리

### story 격리(StoryQuarantine)

_Aggregate Root_

story별 연속 실패 누적과 격리 상태다. 격리된 story는 요약 대상에서 빠진다.

#### 속성(Attributes)

- `id`: `StoryQuarantineId` 격리 기록 식별자
- `storyId`: `StoryId` 추적 단위
- `consecutiveFailures`: 연속 실패 횟수
- `lastFailureReason`: `AiFailureReason?` 마지막 실패 사유
- `status`: `StoryQuarantineStatus`
- `quarantinedAt` / `releasedAt`: 마지막 격리/해제 시각 (현재 상태는 `status`가 갖는다)
- `updatedAt`: 갱신 시각

#### 행위(Behaviors)

- `static track(storyId, updatedAt)`: `TRACKING` 상태로 추적을 시작한다
- `static restore(...)`: 저장소 snapshot을 복원한다
- `recordFailure(reason, failureThreshold, updatedAt)`: 실패를 누적하고 임계치 도달 시 격리한다
- `recordSuccess(updatedAt)`: 연속 실패 누적을 0으로 되돌린다
- `release(updatedAt)`: 운영자가 격리를 해제한다
- `needsReset()`: 되돌릴 실패 누적이나 `TRACKING`이 아닌 상태가 남아 있는지 판단한다

#### 규칙(Rules)

- 격리 임계치는 1 이상이어야 하고, 연속 실패가 임계치에 도달하면 `QUARANTINED`로 전이한다.
- 격리 상태에서 들어온 실패/성공 기록은 무시한다.
- 격리된 story만 해제할 수 있다. 자동 해제는 두지 않는다.
- story 탓 실패만 연속 실패로 센다.
  LLM 실패는 시도한 후보 전부가 입력 탓으로 끝났을 때(`LlmProviderException.allInput`), LLM 호출 단계의 불변식 위반은 항상이다.
  요약 버전을 만들고 저장하는 단계의 위반은 세지 않고 예외로 올린다.

### story 격리 기록 식별자(StoryQuarantineId) · 상태(StoryQuarantineStatus)

- `StoryQuarantineId`: _Value Object_. 격리 기록 식별 UUID, `newId()` / `of()`
- `StoryQuarantineStatus`: _Enum_. `TRACKING`(임계치 미만으로 연속 실패 누적 중), `QUARANTINED`(임계치에 도달해 요약 대상에서 제외), `RELEASED`(운영자가 해제)

## story 사본 애그리거트

story의 정본은 story-service이고 기사 사실의 정본은 collector다.
ai-service는 `story.article.attached`·`story.merged`가 실어 온 값으로 사본을 만들고 요약은 이 사본만 읽는다.

### story 사본(AiStory)

_Aggregate Root_

ai-service가 요약 대상으로 추적하는 story의 상태다. 요약 시점을 판정하는 데 필요한 값만 갖는다.

#### 속성(Attributes)

- `storyId`: `StoryId` story-service가 매긴 식별자
- `keywords`: `List<AiKeyword>` story에 붙은 키워드
- `articleCount`: story의 기사 수
- `latestVersion`: 마지막으로 만든 요약 버전 (요약이 없으면 0) / `latestVersionAt`: 그 시각
- `pendingCount`: 미요약 기사 수 / `oldestPendingAt`: 가장 오래된 미요약 기사의 `attachedAt`
- `mergedInto`: `StoryId?` 이 story를 흡수한 story (흡수되지 않았으면 null)
- `version`: 저장 CAS의 기대값. 전이마다 1씩 커진다
- `updatedAt`: 갱신 시각
- 파생: `nextVersion`(`latestVersion + 1`), `merged`, `summaryWaitBaseline`(`latestVersionAt`, 없으면 `oldestPendingAt`)

#### 행위(Behaviors)

- `static open(storyId, keywords, articleCount, attachedAt, now)`: 첫 기사로 story를 연다. 미요약 수는 1이다
- `static trackMerged(storyId, mergedInto, now)`: 기사 사본 없이 흡수 사실만 기록하는 자리표시를 만든다
- `static restore(...)`: 저장소 snapshot을 복원한다
- `accept(keywords, articleCount, attachedAt, now)`: 기사 스냅샷 하나를 받아 미요약 수를 올린다
- `summarized(summarizedCount, remainingOldestPendingAt, now)`: 요약 한 버전이 만들어진 뒤의 상태로 옮긴다
- `mergeInto(target, now)`: 이 story가 `target`에 흡수된 것으로 표시한다
- `absorb(movedPendingCount, movedOldestPendingAt, mergedKeywords, now)`: 흡수된 story의 미요약 기사와 키워드를 넘겨받는다

#### 규칙(Rules)

- `open`으로 여는 story는 키워드가 하나 이상이고 기사 수가 1 이상이다.
- 키워드와 기사 수는 이벤트가 실어 온 스냅샷의 합집합과 최댓값으로만 자란다.
- 흡수된 story는 기사를 받지 않고 다른 story를 흡수하지도 않는다.
- story는 자신에게 흡수될 수 없다.
- 요약된 기사 수는 1 이상 미요약 수 이하여야 한다.
- 남은 미요약이 0일 때만 `oldestPendingAt`이 null이다.
- 자리표시는 키워드가 비어 있고 기사 수와 미요약 수가 0이다. `story.merged`가 흡수된 story의 기사보다 먼저 온 경우에 만든다.

### story 기사 사본(AiStoryArticle)

_Entity_

story에 붙은 기사의 사본이다.

#### 속성(Attributes)

- `newsId`: 기사 식별 UUID
- `storyId`: `StoryId` 소속 story
- `source` / `title` / `excerpt` / `url`: 출처, 제목, 발췌문, URL (제목과 발췌문은 trim 저장)
- `publishedAt`: 발행 시각 / `attachedAt`: story에 붙은 시각
- `summarizedInVersion`: 이 기사가 실린 요약 버전. null이면 미요약이다

#### 행위(Behaviors)

- `static create(...)`: 미요약 상태로 만든다 / `static restore(...)`: 저장소 snapshot을 복원한다
- `summarizedIn(version)`: 요약 버전에 실린 것으로 표시한다
- `reassign(storyId)`: 소속 story를 바꾼 사본을 돌려준다. 병합의 기사 이동은 저장소가 한 번의 update로 처리하고 이 메서드를 거치지 않는다

#### 규칙(Rules)

- `source`·`title`·`excerpt`·`url`은 빈 값일 수 없다.
- 이미 요약된 기사는 다시 표시하지 않고 소속도 옮기지 않는다.

### story 식별자(StoryId)

_Value Object_

- `value`: story-service가 발급한 UUID, `of()`

## story 요약 애그리거트

### story 요약(StorySummary)

_Aggregate Root_

story 하나의 요약 버전 하나다. 직전 버전 요약과 새 기사를 LLM에 함께 넣어 만든다.

#### 속성(Attributes)

- `id`: `StorySummaryId` 요약 식별자
- `storyId`: `StoryId` / `version`: story마다 1부터 1씩 커지는 버전
- `keywords`: `List<AiKeyword>` 요약 시점의 story 키워드
- `newNewsIds`: `List<UUID>` 이 버전에서 새로 반영된 기사 (중복 제거)
- `sourceNewsCount`: 이 버전까지 반영된 기사 수 누적
- `title` / `content`: 요약 제목·본문 (trim 저장)
- `sentiment`: `NewsSummarySentiment` 감성
- `developmentKind`: `StoryDevelopmentKind` 직전 버전 대비 전개 종류
- `provider`: `LlmProvider` 응답한 제공자
- `requestedModel`: 요청에 실은 모델 wire id / `model`: 응답이 보고한 모델 (보고가 없으면 요청 모델)
- `promptVersion`: `PromptVersion`
- `tokenUsage`: `TokenUsage` 사용 token 수
- `createdAt`: 생성 시각

#### 행위(Behaviors)

- `static create(...)`: 요약 버전을 생성한다. 키워드와 기사 id 중복을 제거하고 제목·본문을 trim한다
- `static restore(...)`: 저장소 snapshot을 복원한다

#### 규칙(Rules)

- `(storyId, version)`이 멱등 키다. 저장소 unique index가 버전 하나당 문서 하나를 보장한다.
- 버전은 1 이상이고 첫 버전은 `DEVELOPMENT`여야 한다.
- 키워드와 새 기사는 하나 이상이어야 하고, 누적 기사 수는 새 기사 수 이상이어야 한다.
- 제목·본문·모델·요청 모델은 빈 값일 수 없다.

### story 요약 식별자(StorySummaryId)

_Value Object_

- `value`: 요약 식별 UUID, `newId()` / `of()`

### 전개 종류(StoryDevelopmentKind)

_Enum_

- `NEW_STORY`: 새 기사 전부가 이 story와 다른 사건이다. 분리 요청을 낸다
- `DEVELOPMENT`: 직전 요약에 없던 정보가 더해졌다. 요약 생성 이벤트를 낸다
- `NO_CHANGE`: 직전 요약 대비 새 정보가 없다. 이벤트를 내지 않는다
- 세 경우 모두 버전을 저장하고 기사를 실린 것으로 표시한다.

### 감성(NewsSummarySentiment)

_Enum_

- `POSITIVE`, `NEUTRAL`, `NEGATIVE`, `UNKNOWN`

## 실행 기록

### AI 실행(AiRun)

_Aggregate Root_

키워드 확장 한 번의 실행 이력이다. 몇 키워드를 성공·실패했고 어느 모델이 응답했는지의 판단 근거를 로그가 아니라 DB에 둔다.

#### 속성(Attributes)

- `id`: `AiRunId` 실행 식별자
- `targetType`: `AiRunTargetType` 실행 대상 유형
- `status`: `AiRunStatus` 실행 상태
- `startedAt` / `finishedAt`: 시작·종료 시각
- `requestedKeywords`: 요청 키워드 수
- `succeededCount` / `failureCount`: 성공/실패 처리 건수
- `failureReason`: `AiFailureReason?` 실패 사유 요약
- `provider` / `model` / `promptVersion`: 실행에 사용한 LLM 정보 (실행 전 실패 시 null)

#### 행위(Behaviors)

- `static start(targetType, requestedKeywords, startedAt)`: `RUNNING`으로 실행을 시작한다
- `static restore(...)`: 저장소 snapshot을 복원한다
- `complete(succeededCount, failureCount, failureReason, provider, model, promptVersion, finishedAt)`: 실행을 완료한다

#### 규칙(Rules)

- `RUNNING` 상태의 실행만 완료할 수 있고, 완료 시각은 시작 시각보다 이전일 수 없다.
- 처리 결과는 성공/실패 둘로 나누며 `requestedKeywords = succeededCount + failureCount`다.
- 완료 상태 결정:
  - 실패와 성공이 섞이면 `PARTIALLY_FAILED`
  - 실패만 있으면 `FAILED`
  - 성공만 있으면 `SUCCEEDED`
  - 전부 0이면 `FAILED` (요청 키워드 0건)
- `SUCCEEDED`가 아니면 실패 사유를 남긴다 (없으면 `UNKNOWN`).
- `AiRun`은 notification/history 장기 이력이 아니라 AI 처리 운영 기록이다.

### 실행 식별자(AiRunId)

_Value Object_

- `value`: 실행 식별 UUID, `newId()` / `of()`

### 실행 상태(AiRunStatus)

_Enum_

- `RUNNING`, `SUCCEEDED`, `PARTIALLY_FAILED`, `FAILED`

### 실행 대상 유형(AiRunTargetType)

_Enum_

- `KEYWORD_EXPANSION`

### 실패 사유(AiFailureReason)

_Enum_

AI 처리 실패 사유 분류다. 실행 기록과 격리 기록에 남는다.

- `TIMEOUT`, `RATE_LIMITED`, `CLIENT_ERROR`, `SERVER_ERROR`, `NETWORK_ERROR`,
  `INVALID_RESPONSE`, `UNKNOWN`
- `PROVIDER_UNAVAILABLE`: 호출할 수 있는 LLM 모델이 없어 요청을 보내지 못했다.
- `MODEL_NOT_FOUND`: 제공이 끝났거나 이름이 틀린 모델을 불렀다.
- `ACCOUNT_ERROR`: 제공자 계정 문제. 인증 실패, 결제 필요, 권한이나 한도.

## outbox 애그리거트

### AI outbox(AiOutbox)

_Aggregate Root_

발행해야 할 도메인 이벤트 1건과 그 발행 상태다. 도메인 상태 변경과 한 트랜잭션으로 기록한다.

#### 속성(Attributes)

- `id`: `AiOutboxId` outbox 식별자
- `eventType`: `AiOutboxEventType` 발행할 topic을 고른다
- `eventKey`: 이벤트 하나를 가리키는 키. 저장소 unique index가 이벤트 하나당 행 하나를 보장한다
- `partitionKey`: Kafka record key. storyId다
- `payload`: 기록 시점에 고정한 계약 JSON
- `status`: `AiOutboxStatus`
- `retryCount`: 재시도 가능한 실패로 끝난 발행 시도 수
- `nextRetryAt`: 다음에 집어갈 수 있는 시각. 생성 시에는 now다
- `lastError`: 마지막 실패 사유
- `publishedAt`: 발행 완료 시각
- `claim`: `AiOutboxClaim?` 발행 소유권
- `createdAt` / `updatedAt`: 생성·갱신 시각

#### 행위(Behaviors)

- `static create(eventType, eventKey, partitionKey, payload, now)`: `PENDING`으로 기록한다
- `static restore(...)`: 저장소 snapshot을 복원한다
- `markPublishing(now, claimedBy)`: 발행을 시작하며 소유권을 잡는다
- `markPublished(now)`: 발행 완료로 옮긴다
- `recordFailure(reason, retryPolicy, now)`: 재시도 가능한 실패를 기록한다. 한도 미만이면 backoff 뒤 `PENDING`, 한도에 도달하면 `DEAD`다
- `markDead(reason, now)`: 재시도해도 결과가 달라지지 않는 실패를 한도와 무관하게 `DEAD`로 보낸다
- `recoverToPending(now)`: 운영자가 `DEAD`를 다시 발행 대상으로 되돌린다. `retryCount`는 0이 된다

#### 규칙(Rules)

- 전이: PENDING -> PUBLISHING(claim) -> PUBLISHED | DEAD, PUBLISHING -> PENDING(재시도), DEAD -> PENDING(운영자 복구). 자동 복구는 두지 않는다.
- 불변이며 전이 메서드는 새 인스턴스를 반환한다.
- 상태에 맞지 않는 전이 요청은 예외를 던진다.
- 늦은 결과는 도메인이 아니라 저장소의 CAS가 거른다. finalize는 `claim`을 기대값으로 쓴다.
- `claim`은 PUBLISHING에서만 존재하고, PUBLISHED는 `publishedAt`을 갖는다.
- `eventKey`·`partitionKey`·`payload`는 빈 값일 수 없다.

### 발행 소유권(AiOutboxClaim)

_Value Object_

- `claimedBy`, `claimedAt`. PUBLISHING 상태에서만 존재한다
- `claimedBy`는 빈 값일 수 없다

### outbox 식별자(AiOutboxId) · 상태(AiOutboxStatus) · 이벤트 종류(AiOutboxEventType)

- `AiOutboxId`: _Value Object_. outbox 식별 UUID
- `AiOutboxStatus`: _Enum_. `PENDING`, `PUBLISHING`, `PUBLISHED`, `DEAD`. PUBLISHED와 DEAD가 종료 상태다
- `AiOutboxEventType`: _Enum_. `SUMMARY_CREATED`(story 요약 버전), `STORY_SPLIT_REQUESTED`, `STORY_QUARANTINED`

### 재시도 정책(AiOutboxRetryPolicy)

_Value Object_

- `maxAttempts`, `baseDelay`, `maxDelay`, `multiplier`, `jitterRatio`
- `backoff(attempts)`: 지수 backoff에 ±jitter를 더한 대기 시간이다. `exhausted(attempts)`가 DEAD 판정 기준이다

## LLM 호출 단위

`domain/llm`의 enum이 어느 규격의 어느 제공자·모델을 어느 용도로 부르는지 정한다. 주소·키·시간·후보 순서만 yaml이다.

### API 규격(LlmApi)

_Enum_

- `OPENAI_CHAT_COMPLETIONS`
- 요청·응답 JSON의 모양. 코드가 분기하는 유일한 축이며 규격마다 adapter 하나가 있다.

### 제공자(LlmProvider)

_Enum_

- `GROQ`, `MISTRAL`, `OLLAMA`, `OPENROUTER`, `TOGETHER`, `CEREBRAS`, `MOONSHOT`
- 속성: `code`(영속 문서·이벤트·서킷 이름에 쓰는 고정 문자열), `api`
- 한 계정으로 부르는 서비스 하나.
- 상수명은 회사명이고 과금 상태는 이름에 넣지 않는다.

### 모델(LlmModel)

_Enum_

- `GROQ_QWEN3_27B`, `MISTRAL_SMALL_2603`, `OLLAMA_QWEN3_27B`
- 속성: `provider`, `code`(요청에 싣는 wire id), `options`(`LlmRequestOptions`), `qualifiedCode`(`provider.code/code`, 파생)
- 실호출 검증을 통과한 것만 둔다.
- 제공이 끝난 모델은 상수를 지운다.
- `-latest` 같은 이동 alias는 쓰지 않는다.

### 용도(LlmUse)

_Enum_

- `STORY_SUMMARY`, `KEYWORD_EXPANSION`
- `LlmProviderPort`의 메서드와 1:1이다.
- 용도마다 후보 모델의 순서가 따로 있다.

### 과금 방식(LlmBilling)

_Enum_

- `FREE_TIER`, `METERED`, `SUBSCRIPTION`, `SELF_HOSTED`
- 제공자 계정의 속성이라 yaml이 정한다. `SELF_HOSTED`만 인증 키가 없어도 된다.
- `METERED`는 실호출 검증에서 별도 클래스로 opt-in한다.

### 요청 옵션(LlmRequestOptions / ReasoningEffort / Thinking)

_Value Object_

- `reasoningEffort`: `OMIT`, `NONE`, `LOW`, `MEDIUM`, `HIGH`, `MAX`
- `thinking`: `OMIT`, `ENABLED`, `DISABLED`
- `OMIT`은 요청에 그 필드를 싣지 않는다.
- 모든 모델 상수가 모든 값을 명시한다. 새 값이 생기면 컴파일이 누락을 잡는다.

### 프롬프트(LlmPrompt / StorySummaryPrompt / KeywordExpansionPrompt)

_Value Object_

- `use`, `version`(`PromptVersion`), `system`, `maxTokens`
- 용도마다 object 하나가 본문과 버전을 한 파일에 둔다.
- 본문·입력 모양·출력 형식이 바뀔 때만 버전을 올리고 모델 교체는 대상이 아니다.
- user 메시지는 자연어가 아니라 입력 객체의 JSON이다.
- system이 입력 값을 인용 데이터로 선언해 기사 제목·키워드 속 지시문이 명령으로 읽히지 않게 한다.
- story 요약 입력은 story 키워드, 직전 버전 요약(`StorySummaryPrompt.PreviousSummary`, 첫 버전은 null), 새 기사(`StorySummaryPrompt.Article`: source·title·excerpt·publishedAt)다.
- story 요약 응답에는 `developmentKind`가 더해진다.
- story 요약 입력은 키워드 20개, 기사 50건까지 싣고 기사의 제목·발췌문·출처를 문자 수 상한에서 자른다.

### 프롬프트 버전(PromptVersion)

_Value Object_

- `of()`에서 trim, 빈 값 불가.
- 키워드 확장 저장 키의 일부다. 올리면 같은 키워드도 새 프롬프트로 다시 확장하고, 올리지 않으면 옛 결과를 재사용한다.
- story 요약 버전에는 기록 필드로 남는다.

## LLM 실패 모델

### LLM 실패(LlmFailure)

_Value Object_

LLM 호출 실패 하나를 표준 코드와 함께 보존하는 값이다.
소비처가 내리는 판단마다 파생 프로퍼티가 하나 있고, 판단 축은 모두 `code`에서 파생한다.

#### 속성(Attributes)

- `code`: `LlmFailureCode` 표준 실패 코드
- `provider`: `LlmProvider?` 실패를 낸 제공자. 호출 전에 차단된 실패는 null
- `message`: 실패 메시지 (기본값 `code.defaultMessage`, 빈 값 불가)
- `statusCode`: 외부 HTTP status code
- `retryAfterMillis`: 429의 Retry-After (음수 불가)
- `attribution`: `LlmFailureAttribution` — `code`에서 파생
- `transient`: 다음 tick이나 다음 후보에서 저절로 풀리는가 — `code`에서 파생

#### 행위(Behaviors)

- `fromActualCall`: 실제로 제공자를 호출해서 얻은 실패인가. `attribution != NONE`
- `recordsInCircuit`: 서킷 브레이커에 실패로 기록할 것인가. 실제 호출이고 transient
- `holdsModel`: 이 모델을 한동안 후보에서 뺄 것인가. `MODEL`이고 transient가 아님
- `holdsProvider`: 이 제공자의 모든 모델을 한동안 뺄 것인가. `PROVIDER`이고 transient가 아님

#### 규칙(Rules)

- 두 축은 `code`에서 파생한다. 코드와 어긋난 축을 가진 실패를 만들 수 없다.
- 응답 계약 위반은 모델이 살아 있다는 증거이고, 404·402는 한 건으로 확정이라 서킷의 표본이 아니다.
- story 격리 판단은 여기 없다. 실패 하나가 아니라 시도한 후보 전부의 책임으로 정하므로 `LlmProviderException.allInput`이 맡는다.
- provider 원문 실패 코드는 우리 분류 체계와 섞지 않는다. 보존이 필요해지면 별도 필드로 추가한다.

### LLM 실패 코드(LlmFailureCode)

_Enum_

ai-service가 정의한 표준 LLM 실패 코드다. 각 상수가 `code`, `defaultMessage`, `attribution`, `transient`를 갖는 실패 분류의 유일한 기준이다.

| 코드 | 상황 | attribution | transient |
| --- | --- | --- | --- |
| `LLM_MODEL_NOT_FOUND` | 404 | MODEL | false |
| `LLM_REQUEST_REJECTED` | 400·413·422 | INPUT | false |
| `LLM_UNAUTHORIZED` | 401 | PROVIDER | false |
| `LLM_PAYMENT_REQUIRED` | 402 | PROVIDER | false |
| `LLM_FORBIDDEN` | 403 | PROVIDER | false |
| `LLM_RATE_LIMITED` | 429 | PROVIDER | true |
| `LLM_SERVER_ERROR` | 5xx | PROVIDER | true |
| `LLM_TIMEOUT` | timeout | PROVIDER | true |
| `LLM_NETWORK_ERROR` | 연결·I/O | PROVIDER | true |
| `LLM_INVALID_RESPONSE` | 200이지만 JSON 계약 위반, 빈 content | INPUT | false |
| `LLM_NOT_PERMITTED` | 서킷·cooldown·hold가 호출 전에 막음 | NONE | true |
| `LLM_UNKNOWN_ERROR` | 그 외 | PROVIDER | true |

### LLM 실패 책임(LlmFailureAttribution)

_Enum_

LLM 실패의 책임이 어디 있는가다. 같은 실패라도 책임에 따라 다음 판단이 갈린다.

- `INPUT`: 이 요약·확장 대상(story나 키워드)의 요청이나 응답. 다른 모델도 같은 결과를 줄 수 있다.
- `MODEL`: 모델 하나. 그 모델만 빼면 된다.
- `PROVIDER`: 계정과 endpoint. 그 제공자의 모든 모델이 같다.
- `NONE`: 우리 가드가 호출 전에 막았다. 어느 쪽 상태도 말해 주지 않는다.

### 토큰 사용량(TokenUsage)

_Value Object_

- `inputTokens` / `outputTokens`: 각 0 이상
- `totalTokens`: 합계 (파생)

## 키워드 확장 애그리거트

### 키워드 확장(KeywordExpansion)

_Aggregate Root_

키워드 하나를 LLM으로 확장한 결과다.

#### 속성(Attributes)

- `id`: `KeywordExpansionId` 확장 식별자
- `keyword`: `AiKeyword` 원본 키워드
- `expandedKeywords`: `List<ExpandedKeyword>` 확장 키워드 목록
- `provider`: `LlmProvider` / `requestedModel` / `model` / `promptVersion`: 사용한 LLM 정보
- `createdAt`: 생성 시각

#### 행위(Behaviors)

- `static create(...)`: 확장 결과를 생성한다. 원본과 같은 키워드(대소문자 무시)를 제거하고, 소문자 기준으로 중복을 제거한다
- `static restore(...)`: 저장소 snapshot을 복원한다

#### 규칙(Rules)

- 정규화 후 확장 키워드는 하나 이상이어야 한다.
- 확장 키워드는 원본 키워드와 함께 추적되어야 한다.
- 같은 원본 키워드, prompt version 조합은 중복 저장하지 않는다 (저장소 unique 제약).
- model은 기록 필드다.
- LLM 응답이 비어 있거나 파싱할 수 없으면 실패로 처리하고 결과를 저장하지 않는다.

### 확장 식별자(KeywordExpansionId)

_Value Object_

- `value`: 확장 식별 UUID, `newId()` / `of()`

### AI 키워드 / 확장 키워드(AiKeyword / ExpandedKeyword)

_Value Object_

- 원본 키워드 / 확장 키워드. `of()`에서 trim, 빈 값 불가.
