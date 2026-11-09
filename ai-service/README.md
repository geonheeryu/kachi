# ai-service

LLM 기반 키워드 확장과 story 요약을 담당하는 서비스다.

구현된 흐름은 둘이다.

- 활성 키워드 또는 요청 키워드 기준 관련 검색어를 생성하는 키워드 확장
- `story-service`가 발행한 story 이벤트를 받아 story 단위로 버전을 쌓는 story 요약([story 요약](#story-요약))

## 설계 기준

`ai-service`는 Hexagonal Architecture를 따른다.

```text
Scheduler 또는 Internal API
  -> AiExecutionExecutor
      -> ExpandKeywordsUseCase
          -> KeywordReaderPort
              -> user-service internal active keywords API
          -> LlmProviderPort
              -> 용도별 후보 모델 (OpenAI chat completions 규격)
          -> KeywordExpansionPersistencePort / AiRunPersistencePort
              -> MongoDB
```

story 이벤트를 소비해 요약하는 경로는 [story 요약](#story-요약) 절에 있다.

WebFlux와 reactive MongoDB를 쓴다.
LLM 호출은 외부 I/O가 많고 timeout, retry, rate limit 대응이 필요하므로 MVC보다 WebFlux 구성이 더 적합하다.

## 구현 범위

- `KeywordExpansion`, `StorySummary`, `AiRun` 도메인 모델
- 키워드 확장 internal API
- 단일 인스턴스 기준 중복 실행 방지
- MongoDB 기반 확장 결과, 요약 결과, 실행 기록 저장
- LLM provider adapter
- user-service 활성 키워드 조회 client
- story 이벤트 소비와 story 요약, story 격리
- outbox 기반 이벤트 발행

## Scheduler

스케줄러 실행 진입점은 두 가지다.

| 대상 | 설정 prefix | 기본 주기 | 기본 활성화 |
| --- | --- | --- | --- |
| 키워드 확장 | `kachi.ai.scheduler.keyword-expansion` | 24h | `false` |
| story 요약 | `kachi.ai.scheduler.story-summary` | 5m | `true` |

story 요약 scheduler는 대기 상한(`max-wait`)을 넘긴 story만 한 tick에 `max-stories-per-tick`(기본 20)개까지 요약한다.

local 프로필은 둘 다 켠다. story 요약은 기본값 그대로이고 키워드 확장은 local에서만 켠다.
test 프로필은 둘 다 끈다.

실행 중인 같은 AI 작업이 있으면 이번 tick은 건너뛰고 로그만 남긴다.

## story 요약

story-service가 발행한 이벤트를 소비해 story와 기사의 사본을 만들고, 사본만 읽어 요약 버전을 쌓는다.

```text
story.article.attached -> StoryArticleAttachedKafkaListener
  -> RecordStoryArticleUseCase: 기사 사본 기록, 미요약 수 증가
      -> SummarizeStoryUseCase: 미요약 수가 min-new-articles 이상이면 그 자리에서 요약
story.merged -> StoryMergedKafkaListener
  -> ApplyStoryMergeUseCase: 흡수된 story의 미요약 기사와 키워드를 흡수한 story로 이동
      -> SummarizeStoryUseCase: 옮긴 뒤 미요약 수가 min-new-articles 이상이면 그 자리에서 요약
AiStorySummaryScheduler
  -> SummarizeDueStoriesUseCase: max-wait를 넘긴 story 요약
```

요약 시점을 정하는 값은 `kachi.ai.story-summary` 아래에 있다.

| 키 | 뜻 | 기본값 |
| --- | --- | --- |
| `min-new-articles` | 미요약 기사가 이 수에 닿으면 소비 스레드에서 바로 요약한다 | `3` |
| `max-wait` | 미요약 기사가 있는 story를 tick이 요약하기까지 기다리는 상한 | `60m` |
| `max-articles-per-version` | 한 버전에 싣는 새 기사 수 상한. 오래된 순으로 싣는다 | `50` |

소비 설정은 `kachi.ai.consumer` 아래에 있다.

| 키 | 기본값 | local |
| --- | --- | --- |
| `group-id` | `ai-service` | 같음 |
| `auto-offset-reset` | `earliest` | 같음 |
| `max-poll-records` | `10` | `1` |
| `max.poll.interval.ms` (`spring.kafka.consumer.properties`) | Kafka 기본값 | `600000` |

읽을 수 없는 메시지는 `ai.story.dlt`로 보낸다.
그 밖의 실패는 같은 offset을 backoff(1s에서 2배씩 30s까지)로 다시 처리한다.

story 탓 실패가 연속으로 `kachi.ai.story-quarantine.failure-threshold`(기본 3)에 닿으면 그 story를 격리한다.
격리된 story는 운영자가 해제할 때까지 요약하지 않는다.

## 이벤트 발행

요약 생성과 격리, 분리 요청은 outbox에 기록되고, relay가 그 행을 Kafka topic으로 내보낸다.
outbox 행 하나가 레코드 하나다. topic은 행의 eventType이 고르고, value는 기록 시점의 계약 JSON이다.
key는 storyId다.
계약 타입은 `ai-contract` 모듈에 있다.

| eventType | topic | 계약 |
| --- | --- | --- |
| `SUMMARY_CREATED` | `ai.summary.created` | `AiSummaryCreatedEventV2`(schemaVersion 2) |
| `STORY_SPLIT_REQUESTED` | `ai.story.split-requested` | `AiStorySplitRequestedEvent` |
| `STORY_QUARANTINED` | `ai.story.quarantined` | `AiStoryQuarantinedEvent` |

세 이벤트는 요약 버전·격리 전이와 같은 트랜잭션에서 항상 outbox 행을 남긴다. `NO_CHANGE` 버전은 행을 남기지 않는다.

발행 스위치는 둘이다.

| 키 | 뜻 | 기본값 |
| --- | --- | --- |
| `kachi.ai.outbox.relay.enabled` | outbox를 읽어 발행 포트로 넘기는 relay | `true` |
| `kachi.ai.events.enabled` | Kafka 발행 어댑터 | `true` |

relay만 켜고 어댑터가 없으면 기동에 실패한다. local은 둘 다 켜고 test 프로파일은 둘 다 `false`다.
relay를 끈 동안 행은 `PENDING`으로 쌓이고, relay를 켜면 발행된다.
누가 어떤 채널로 받는지는 이 서비스가 모른다. 요약을 알림으로 fan-out하는 일은 notification-service의 routing이 한다.

## LLM

LLM 호출의 단위는 모델이다. 어느 회사의 어느 모델을 어느 용도에 쓰는지는 `domain/llm`의 enum이 정하고, 주소·키·시간·후보 순서만 yaml이 정한다.

| enum | 뜻 | 상수 |
| --- | --- | --- |
| `LlmApi` | 요청·응답 규격. adapter 하나가 규격 하나를 맡는다 | `OPENAI_CHAT_COMPLETIONS` |
| `LlmProvider` | 한 계정으로 부르는 회사 | `GROQ`, `MISTRAL`, `OLLAMA`, `OPENROUTER`, `TOGETHER`, `CEREBRAS`, `MOONSHOT` |
| `LlmModel` | 한 제공자의 모델 하나. wire id와 요청 옵션을 함께 갖는다 | `GROQ_QWEN3_27B`, `MISTRAL_SMALL_2603`, `OLLAMA_QWEN3_27B` |
| `LlmUse` | 호출 용도. 용도마다 후보 순서가 따로 있다 | `STORY_SUMMARY`, `KEYWORD_EXPANSION` |

`LlmModel` 상수는 실호출 검증을 통과한 것만 둔다.
`-latest` 같은 이동 alias는 쓰지 않는다. 어느 날 다른 모델이 응답해도 알 길이 없기 때문이다.
Ollama tag는 이동 alias지만 digest 고정 호출이 확인되지 않아 예외로 두고, 실호출 검증이 digest를 보고에 남긴다.

yaml은 `kachi.ai.llm` 아래에 있다.

| 키 | 내용 |
| --- | --- |
| `providers.<PROVIDER>` | `base-url`, `api-key`(`${GROQ_API_KEY:}` 같은 자리표시), `billing`(`free-tier`·`metered`·`subscription`·`self-hosted`), `connect-timeout` |
| `models.<MODEL>` | `timeout`(응답 전체), `slow-after`(서킷의 slow call 판정). 같은 모델도 호스팅에 따라 수십 배 달라 모델의 속성이다 |
| `uses.<USE>.candidates` | 시도할 모델의 순서. 여기 없는 모델은 클라이언트도 서킷도 만들지 않는다 |
| `guard.circuit-breaker` | 모델 전체가 공유하는 서킷 설정 |
| `guard.cooldown` | 429를 받은 모델이 쉬는 시간. Retry-After가 있으면 그 값(상한 `max`) |
| `guard.hold` | 404·401·402·403을 받은 모델이나 제공자를 후보에서 빼는 시간 |

프로파일마다 후보가 다르다.

| 프로파일 | 후보 | 비고 |
| --- | --- | --- |
| 기본(`application.yaml`) | `GROQ_QWEN3_27B` -> `MISTRAL_SMALL_2603` | `GROQ_API_KEY`·`MISTRAL_API_KEY`가 있어야 뜬다 |
| `local` | `OLLAMA_QWEN3_27B` | 같은 머신의 Ollama. 키가 없고 `ollama pull qwen3.8:27b`가 먼저다 |
| `test` | `OLLAMA_QWEN3_27B` | 주소가 테스트 stub이다 |

env로 덮을 때는 Spring relaxed binding 이름을 쓴다. Ollama가 다른 머신에 있으면 `KACHI_AI_LLM_PROVIDERS_OLLAMA_BASEURL`이다.
api-key 검증은 후보에 오른 제공자에만 건다. 정의만 있는 클라우드 제공자 때문에 local이 뜨지 못하면 안 되기 때문이다.

실패는 책임(입력 탓·모델 탓·제공자 탓)과 지속(transient 여부) 두 축으로 가른다. 모델 탓은 그 모델을, 제공자 탓은 그 제공자의 모델 전부를 후보에서 빼고 다음 후보로 간다.
후보 전부가 입력 탓이라고 합의할 때만 story 격리 카운트가 오른다. 상태는 아래 [API](#api)의 모델 상태 조회로 본다.

## 제외 범위

- 사용자별 개인화 프롬프트
- 장기 이력 조회
- 알림 발송
- embedding/vector search
- 분산 lock
- provider별 상세 비용 최적화

`user-service`의 활성 키워드 internal API는 사용자 정보를 주지 않는다.
키워드 확장의 단위는 사용자가 아니라 키워드다.

## API

HTTP API는 `/api/v1` prefix를 사용한다.

### Internal

LLM 모델 상태 조회:

```http
GET /api/v1/internal/llm/models
```

어느 용도에든 후보로 오른 모델 전부의 차단 상태를 돌려준다.
서킷 상태와 failure rate·slow call rate, cooldown 종료 시각, hold 사유와 해제 시각, 제공자 계정의 과금 방식이 들어 있다.
`model`은 `LlmModel` 상수명이고 `provider`는 제공자 code다.
제공자·모델·용도의 정의는 `domain/llm`의 enum에 있고, 어느 모델을 쓰는지는 `application.yaml`의 `kachi.ai.llm.uses.*.candidates`다.

```json
{
  "success": true,
  "data": [
    {
      "model": "OLLAMA_QWEN3_27B",
      "provider": "ollama",
      "billing": "SELF_HOSTED",
      "circuitBreakerState": "CLOSED",
      "cooldownUntil": null,
      "holdReason": null,
      "holdUntil": null,
      "failureRate": -1.0,
      "slowCallRate": -1.0,
      "bufferedCalls": 0,
      "successfulCalls": 0,
      "failedCalls": 0,
      "notPermittedCalls": 0
    }
  ],
  "error": null
}
```

차단 해제:

```http
POST /api/v1/internal/llm/models/{model}/reset
```

그 모델의 서킷·cooldown·모델 hold를 풀고 제공자 hold도 함께 푼다. 운영자가 원인 해소를 확인했다는 뜻이다.
응답은 푼 뒤의 상태다.

실제 호출 확인(probe):

```http
POST /api/v1/internal/llm/models/{model}/probe
```

지정한 모델 하나에 키워드 확장 요청을 한 번 실제로 보내 응답 여부를 확인한다. 후보 순회를 거치지 않지만 차단 장치는 그대로 지난다.
차단 중이면 사유를 실어 502로 실패하고, 실패의 결과는 평소 호출과 같이 상태에 남는다.
`billing`이 `METERED`면 과금된 호출이다.

```json
{
  "success": true,
  "data": {
    "model": "GROQ_QWEN3_27B",
    "provider": "groq",
    "billing": "FREE_TIER",
    "requestedModel": "qwen/qwen3.8-27b",
    "servedModel": "qwen/qwen3.8-27b",
    "promptVersion": "keyword-expansion-v2",
    "latencyMillis": 812,
    "inputTokens": 100,
    "outputTokens": 20,
    "expandedKeywords": ["AI 반도체", "GPU", "데이터센터"]
  },
  "error": null
}
```

`{model}`이 상수명이 아니면 400, 상수지만 어느 용도의 후보도 아니면 404다.

키워드 확장 수동 실행:

```http
POST /api/v1/internal/ai/keyword-expansions
```

요청 body를 생략하거나 `keywords`가 비어 있으면 `user-service`의 활성 키워드를 조회한다.

```json
{
  "keywords": ["NVIDIA", "AI 반도체"],
  "maxExpansionsPerKeyword": 5
}
```

키워드 확장 수동 실행은 같은 작업이 이미 실행 중이면 `409 CONFLICT`, 실행 lock을 확인할 수 없으면 `503 SERVICE_UNAVAILABLE`을 반환한다.

story 요약 버전 조회:

```http
GET /api/v1/internal/ai/story-summaries?storyId={storyId}&limit={limit}
```

`limit`은 1 이상 100 이하이고 생략하면 20이다.
응답 항목에는 `version`, `developmentKind`, `newNewsIds`(이 버전에 새로 실린 기사), `sourceNewsCount`(이 버전까지 누적 기사 수)가 들어 있다.

story 요약 tick 수동 실행:

```http
POST /api/v1/internal/ai/story-summaries/run
```

scheduler와 같은 설정으로 대기 상한을 넘긴 story를 요약하고 `due`, `created`, `skipped`, `failed`, `aborted`를 돌려준다.
tick이 이미 돌고 있으면 `409 CONFLICT`, 실행 lock을 확인할 수 없으면 `503 SERVICE_UNAVAILABLE`이다.

story 격리 조회와 해제:

```http
GET /api/v1/internal/ai/story-quarantines?status={TRACKING|QUARANTINED|RELEASED}
POST /api/v1/internal/ai/story-quarantines/{storyId}/release
```

`status`를 생략하면 전체를 돌려준다.
해제할 기록이 없으면 `404 STORY_QUARANTINE_NOT_FOUND`, 격리 상태가 아니면 `409 STORY_QUARANTINE_NOT_RELEASABLE`이다.

outbox 조회와 복구:

```http
GET /api/v1/internal/ai/outboxes?status={status}&limit={limit}
POST /api/v1/internal/ai/outboxes/{outboxId}/recover
```

조회 기본값은 `status=DEAD`, `limit=50`이고 `limit`은 1 이상 500 이하다.
복구는 `DEAD` 행을 `PENDING`으로 되돌리고 발행은 relay가 한다. 행이 없으면 `404 NOT_FOUND`, `DEAD`가 아니면 `409 CONFLICT`다.

## 저장소

`ai-service`는 MongoDB를 사용한다.

저장 대상:

- `keyword_expansions`: 원본 키워드별 확장 키워드 결과
- `ai_runs`: 키워드 확장 실행 기록
- `stories`, `story_articles`: story-service 이벤트로 만든 story와 기사 사본
- `story_summaries`: story 요약 버전. `(storyId, version)`이 unique다
- `ai_story_quarantines`: story별 연속 실패 누적과 격리 상태
- `ai_outbox`: 발행 대기 이벤트

MongoDB 문서에는 LLM provider, model, prompt version, token usage, 생성 시각을 남긴다.
프롬프트 전문은 운영상 필요해질 때 별도 보관 정책을 정하고 추가한다.

## 외부 연동

### user-service

활성 키워드 조회:

```text
GET {KACHI_USER_SERVICE_BASE_URL}/api/v1/internal/keywords/active
```

### story-service

`story.article.attached`·`story.merged`를 Kafka로 소비한다(계약은 `story-contract` 모듈).

## 실행

local 기본값:

```text
server.port=8083
spring.mongodb.uri=mongodb://localhost:27017/kachi_ai?replicaSet=rs0
KACHI_USER_SERVICE_BASE_URL=http://localhost:8080
kachi.ai.scheduler.keyword-expansion.enabled=true
kachi.ai.scheduler.story-summary.enabled=true
kachi.ai.consumer.max-poll-records=1
kachi.ai.llm.uses.*.candidates=[OLLAMA_QWEN3_27B]
```

실행:

저장소 루트에서 실행한다.

```sh
set -a
source .env.local
set +a
./gradlew :ai-service:bootRun
```

`.env.local`의 `SPRING_PROFILES_ACTIVE`가 어느 프로필로 뜰지 정한다.

scheduler·outbox relay·이벤트 발행은 local에서도 켜져 있다. 끄고 띄우려면 실행 인자로 준다.

```sh
./gradlew :ai-service:bootRun --args='--kachi.ai.scheduler.story-summary.enabled=false'
```

테스트:

```sh
./gradlew :ai-service:test
```

Testcontainers 통합 테스트는 Docker 소켓을 찾지 못하면 실패한다.
Docker Desktop을 쓰면 소켓 경로를 함께 넘긴다.

```sh
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./gradlew :ai-service:test
```

### 실호출 검증(realTest)

`src/test`는 실제 provider를 부르지 않는다. 실제로 부르는 테스트는 `src/realTest` 소스셋에 있고 `test`·`check`에 끼지 않는다.
모델을 더하거나 바꿀 때, 제공자 계정을 바꿀 때 돌려서 통과한 뒤 커밋한다.

```sh
./gradlew :ai-service:realTest                            # local 프로파일. Ollama 후보
./gradlew :ai-service:realTest -Pkachi.llm.profile=default   # 기본 yaml만. Groq·Mistral 후보
```

프로파일의 설정을 Spring 컨텍스트 없이 바인딩해 운영과 같은 조립(adapter·가드·WebClient)으로 후보를 만든다.
`default`는 기본 yaml만 쓰고, 그 밖의 이름은 `application-<profile>.yaml`이 있어야 한다.
secret은 기동 스크립트와 같은 순서로 찾는다. 환경변수 -> `.env.<profile>` -> `.env`이며 없으면 skip이 아니라 실패다.

검사하는 것은 둘이다.

- 모델마다 제공자의 `GET /models`에 wire id가 있는지. 토큰을 쓰지 않는다. Ollama는 `/api/tags`의 digest도 보고한다.
- 후보로 오른 (용도, 모델) 쌍마다 실제 생성 1회가 계약대로 돌아오는지. 요약과 확장의 JSON 계약이 달라 둘 다 본다.

과금 여부로 클래스가 갈린다. `LlmVerificationTest`는 `billing: metered`를 뺀 후보, `LlmMeteredVerificationTest`는 `metered`만 돈다.
과금 후보가 없는 프로파일에서 후자는 0건이며 실패가 아니다.

결과는 stdout에 한 줄씩 나온다. 어느 주소를 검증했는지 보이도록 base-url을 싣는다.

```text
[realTest] profile=local listed model=OLLAMA_QWEN3_27B code=ollama/qwen3.8:27b billing=SELF_HOSTED baseUrl=http://localhost:11434/v1
[realTest] profile=local digest model=OLLAMA_QWEN3_27B code=ollama/qwen3.8:27b digest=22130167c4c2...
[realTest] profile=local generated model=OLLAMA_QWEN3_27B code=ollama/qwen3.8:27b use=STORY_SUMMARY served=qwen3.8:27b latency=41205ms tokens=402/97 developmentKind=DEVELOPMENT sentiment=POSITIVE title="..."
```

IntelliJ에서는 소스셋이 갈려 있어 `src/test` 전체를 어느 러너로 돌려도 realTest가 섞이지 않는다. realTest 클래스를 직접 실행하면 실호출이 나간다.

## 관련 문서

- [아키텍처](../docs/아키텍처.md)
- [도메인 모델](../docs/도메인모델.md)
- [테스트 전략](../docs/테스트전략.md)
- [010. ai-service 초기 설계](../docs/decisions/010-ai-service-초기-설계.md)
- [020. ai-service scheduler 실행 모델과 요약 window](../docs/decisions/020-ai-service-scheduler-실행-모델과-요약-window.md)
- [022. ai-service outbox와 이벤트 발행 보장](../docs/decisions/022-ai-service-outbox와-이벤트-발행-보장.md)
- [023. ai-service 도메인 이벤트 발행과 ai-contract](../docs/decisions/023-ai-service-도메인-이벤트-발행과-ai-contract.md)
- [025. 키워드 구독과 알림 라우팅](../docs/decisions/025-키워드-구독과-알림-라우팅.md)
- [030. ai-service LLM 호출 단위, 실패 분류, 실호출 검증](../docs/decisions/030-ai-service-llm-호출-단위와-실패-분류와-실호출-검증.md)
- [031. 요약 단위를 키워드에서 story로](../docs/decisions/031-요약-단위를-키워드에서-story로.md)
- [034. ai-service story 요약](../docs/decisions/034-ai-service-story-요약.md)
- [035. notification-routing의 story 요약 소비와 storyId 멱등](../docs/decisions/035-notification-routing-story-요약-소비와-storyId-멱등.md)
