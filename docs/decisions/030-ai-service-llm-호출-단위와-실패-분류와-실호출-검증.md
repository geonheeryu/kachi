# 030. ai-service LLM 호출 단위, 실패 분류, 실호출 검증

## 배경

ai-service는 story 요약과 키워드 확장에 외부 LLM을 쓴다. 이전 구조는 다음과 같았다.

- provider 이름만 enum이고 모델은 yaml 문자열이었다.
- provider마다 `enabled`로 켜고 무작위로 하나를 골랐다.
- 실패 분류는 ADR 021을 따랐다.

2026-09-04 전체 사이클 스모크(ADR 029)에서 이 구조의 결함이 드러났다.

- 제공 종료된 모델의 404와 결제 문제의 402가 키워드 탓 실패로 분류됐다.
  - failover가 일어나지 않고 서킷에도 기록되지 않아 다음 tick에 같은 provider가 다시 뽑혔다.
  - 키워드 격리 카운트가 올라갔다. 죽은 모델 하나가 멀쩡한 키워드를 영구 격리시키는 경로였다.
- 로컬 Ollama는 호출당 수십 초에서 2분인데 서킷의 slow call 판정이 전역 8초라, 실행 스크립트(`scripts/cycle.sh`)가 그 값을 env로 덮었다.
  - 모델의 시간 특성을 설정 땜질로 흡수하고 있었다.
- 사고(thinking) 토큰을 끄는 `reasoning_effort`는 공용 `String?` 필드였고 Ollama만 값을 가졌다.
  - 실호출로 확인하니 그 철자와 필드 이름은 모델마다 다르다.
- 어느 테스트도 yaml의 모델이 실제로 있는지 확인하지 않았고, 실 webhook 테스트는 secret만 있으면 `./gradlew test`에서 실제 발송됐다.

정해야 할 것은 다음이다.

1. LLM을 부르는 단위는 무엇이고 무엇을 enum으로, 무엇을 설정으로 두는가.
2. 모델과 호스팅에 따라 다른 시간 특성을 어디서 흡수하는가.
3. 실패마다 누구 책임인지, 얼마나 가는지를 어떻게 가르고, failover·서킷·격리·조기 중단이 각각 무엇을 근거로 하는가.
4. 모델 교체와 프롬프트 버전, 그리고 "어떤 모델이 만들었나"의 기록을 어떻게 다루는가.
5. 실제 provider를 부르는 검증을 어디에 두고, 그것이 평소 테스트에서 새어 나가지 않게 하는 스위치는 무엇인가.

## 검토한 선택지

호출 단위를 어디에 정의할지 셋을 봤다.

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| 전부 yaml. provider·모델·용도를 map 키로 두고 코드는 API 규격만 안다 | 모델 추가가 설정 변경이다. 참조 구조(LiteLLM 등)가 이렇다 | 몇 개 안 되고 바뀔 때마다 실호출 검증이 필수인 것을 데이터로 뺀 소프트코딩이다. 오타를 컴파일이 못 잡고, 요청 옵션이 `Map<String, Any>`가 되며, env로 map 키를 덮지 못한다 |
| 모델 카탈로그 enum에 상태·검증일까지 | 한 곳에서 다 보인다 | 운영 기록을 소스에 두면 갱신하는 사람도 소비하는 코드도 없다 |
| enum은 정체(API 규격·제공자·모델·용도), yaml은 환경 의존 값(주소·키·과금·시간)과 선택(후보 순서) | 정체는 컴파일이 지키고 환경 차이는 설정이 흡수한다. 요청 옵션이 전용 타입이 된다 | 새 모델은 코드 변경이다. 다만 새 모델은 어차피 실호출 검증을 거쳐 커밋해야 하므로 배포 비용은 논점이 아니다 |

세 번째를 택했다. 기준은 "코드가 분기하거나 컴파일이 지켜야 하는 것은 enum, 머신과 계정에 따라 달라지는 것은 yaml"이다.

## 결정

### API 규격, 제공자, 모델, 용도

| 개념 | 정체 | 코드 | 값 |
| --- | --- | --- | --- |
| API 규격 | 요청·응답 JSON의 모양. 코드가 분기하는 유일한 축 | `LlmApi` | `OPENAI_CHAT_COMPLETIONS` |
| 제공자 | 한 계정으로 부르는 한 서비스. 회사와 계정 | `LlmProvider` | `GROQ`, `MISTRAL`, `OLLAMA` |
| 모델 | 그 제공자에서 부르는 모델 하나 | `LlmModel` | `GROQ_QWEN3_27B`, `OLLAMA_QWEN3_27B` |
| 용도 | 앱이 LLM을 부르는 이유. `LlmProviderPort`의 메서드와 1:1 | `LlmUse` | `STORY_SUMMARY`, `KEYWORD_EXPANSION` |

API 규격과 제공자를 나눈 이유는 둘이 다른 축이기 때문이다.

- Groq·Mistral·Ollama는 회사가 셋이지만 규격은 OpenAI Chat Completions 하나다. 반대로 Google은 회사 하나가 규격 둘을 낸다.
- 회사 하나에 규격 하나라고 전제한 enum은 이 사실을 표현하지 못해, 회사가 늘 때마다 같은 규격의 adapter를 복제하게 된다.

이름은 정체를 따른다.

- 제공자 상수는 회사명, 모델 상수는 `<제공자>_<모델>`이다.
- 영속 문서·이벤트 계약·서킷 이름에는 상수명이 아니라 `provider.code`와 `model.code`(wire id)를 쓴다. 저장된 문자열의 뜻은 이전과 같다.

### 요청 옵션은 전용 타입이고 모든 모델이 모든 값을 명시한다

```kotlin
enum class ReasoningEffort { OMIT, NONE, LOW, MEDIUM, HIGH, MAX }
enum class Thinking { OMIT, ENABLED, DISABLED }

data class LlmRequestOptions(val reasoningEffort: ReasoningEffort, val thinking: Thinking)
```

- `OMIT`은 요청에 그 필드를 싣지 않는다는 뜻이다. 한 제공자만 값을 갖고 나머지는 null인 공용 필드를 두지 않는다.
- 새 철자가 오면 필드 하나와 adapter 매핑 한 줄이 늘고, 모든 모델 상수가 그 값을 명시해야 하므로 컴파일이 누락을 잡는다.

### yaml은 환경 의존 값과 선택만 갖는다

```yaml
kachi.ai.llm:
  providers:
    GROQ:    { base-url: https://api.groq.com/openai/v1, api-key: ${GROQ_API_KEY:}, billing: free-tier, connect-timeout: 2s }
    OLLAMA:  { base-url: http://localhost:11434/v1, billing: self-hosted, connect-timeout: 2s }
  models:
    GROQ_QWEN3_27B:   { timeout: 10s,  slow-after: 8s }
    OLLAMA_QWEN3_27B: { timeout: 150s, slow-after: 120s }
  uses:
    STORY_SUMMARY:     { candidates: [GROQ_QWEN3_27B, MISTRAL_SMALL_2603] }
    KEYWORD_EXPANSION: { candidates: [GROQ_QWEN3_27B, MISTRAL_SMALL_2603] }
  guard:
    circuit-breaker: { sliding-window-size: 6, minimum-number-of-calls: 3, failure-rate-threshold: 50, slow-call-rate-threshold: 80, wait-duration-in-open-state: 60s, permitted-number-of-calls-in-half-open-state: 2 }
    cooldown: { default: 60s, max: 10m }
    hold: { reprobe-after: 1h }
```

- `enabled`와 `mode`는 없다. 모델이 쓰인다는 것은 어느 용도의 `candidates`에 있다는 뜻이고, 참조되지 않은 제공자와 모델은 WebClient도 서킷도 만들지 않는다.
- **시간은 모델의 속성이고 비율은 전역 정책이다.**
  - `timeout`과 `slow-after`는 모델마다 선언한다. 같은 모델도 호스팅에 따라 수십 배 다르므로 코드가 아니라 yaml이다.
  - failure rate·slow call rate·sliding window·half-open 허용 수는 어느 모델에나 같은 정책이라 `guard`에 한 번 둔다.
- 기동 검증은 `LlmProperties`가 바인딩 직후 한다.
  - 용도마다 후보 1개 이상, 후보 모델과 그 제공자의 항목이 있음, `0 < slow-after < timeout`, 후보 제공자가 `self-hosted`가 아니면 `api-key`가 있음.
  - 후보가 아닌 제공자의 키는 요구하지 않는다. 정의만 있는 클라우드 제공자 때문에 로컬이 뜨지 못하면 안 된다.

### 조립은 전략 패턴, 데코레이터 패턴, 컴포지트 패턴 세 층이다

```text
LlmProperties(yaml, 바인딩 직후 검증)
   ├ 제공자마다  provider.api ──▶ adapter                         전략 패턴 (규격마다 LlmProviderPort 구현 하나)
   ├ 후보 모델마다 adapter + model + timeout ──▶ GuardedLlmModel     데코레이터 패턴 (같은 포트로 감쌈: 서킷 · cooldown · hold)
   └ 용도마다    candidates 순서 ──▶ RoutingLlmProvider            컴포지트 패턴 (같은 포트로 후보를 묶음) + 순차 failover
SummarizeStoryService ──▶ LlmProviderPort.prepareStorySummary() STORY_SUMMARY 후보를 순서대로
ExpandKeywordsService ──▶ LlmProviderPort.expandKeyword()  KEYWORD_EXPANSION 후보를 순서대로
```

세 층은 모두 같은 포트 `LlmProviderPort`를 구현하고, 위 층은 아래 층이 어느 쪽인지 모른다.

- 전략 패턴은 그 포트의 구현을 규격마다 하나씩 두는 것이다.
- 데코레이터 패턴은 구현 하나를 같은 포트로 감싸는 것이다.
- 컴포지트 패턴은 구현 여럿을 같은 포트로 묶는 것이다.

**전략 패턴.** API 규격별 호출을 전략 패턴으로 구현한다.

- 공통 계약은 `LlmProviderPort`, 전략은 규격 하나를 담당하는 adapter(`OpenAiChatAdapter`)다.
- 선택 키는 `LlmApi`이고 선택은 조립 시점에 `LlmConfig` 한 곳에서 한다.

- 느슨한 결합: 가드·라우터·application 서비스는 adapter 구현을 모르고 포트 계약만 본다. adapter를 바꿔도 위 층은 바뀌지 않는다.
- 확장성: 새 회사가 OpenAI 규격이면 `LlmProvider` 상수와 yaml 항목이 전부다. 새 규격이면 `LlmApi` 상수와 adapter 하나를 더하고, 선택 분기의 `when`이 빠진 규격을 컴파일에서 잡는다.
- 다형성: 같은 `summarizeStory` 호출이 규격에 따라 다른 adapter로 간다. 후보 모델마다 인스턴스를 따로 만들지만 규격이 같으면 클래스는 하나다.

**데코레이터 패턴.** 차단을 데코레이터 패턴으로 구현한다. `GuardedLlmModel`이 adapter를 같은 포트로 감싸 모델 하나의 서킷·cooldown·hold를 관리한다. 401·402·403은 계정 문제라 제공자 단위 hold를 가드들이 공유한다(`ProviderHoldRegistry`).

- 느슨한 결합: adapter는 자기가 차단될 수 있다는 사실을 모르고 실패를 분류해 던질 뿐이다.
- 확장성: 차단 장치를 더하거나 상태 저장소를 Redis로 옮겨도 이 층 안에서 끝난다.
- 다형성: 라우터는 후보가 가드로 감싸였는지 모르고 `LlmProviderCandidate` 계약으로만 다룬다.

**컴포지트 패턴.** 후보 묶기를 컴포지트 패턴으로 구현한다.

- `RoutingLlmProvider`가 `LlmProviderPort`를 구현하면서 같은 포트의 후보 여럿을 안에 품는다.
- 묶은 후보를 쓰는 방식은 패턴이 아니라 정책이다. 설정 순서대로 한 번씩 시도하고 처음 성공한 결과를 돌려주는 순차 failover다.
- 무작위 섞기와 가용성 정렬은 없다. 순서가 고정이어야 요약 선조회 plan과 실제 첫 호출이 일치한다(ADR 011).

- 느슨한 결합: application 서비스는 후보가 무엇인지, 몇 개인지, 어떤 순서인지 모른다.
- 확장성: 요약에는 클라우드를, 확장에는 로컬을 쓰는 것이 yaml 한 줄이다.
- 다형성: 같은 포트 뒤에 후보가 하나든 넷이든 호출하는 쪽은 같다.

### 실패는 두 축으로 가른다. 책임과 지속

이전에는 `retryable` 하나에서 "다음 tick 재시도", "다른 provider로 failover", "이 provider를 제외"가 파생됐다. 셋은 다른 질문이라 근거를 따로 둔다.

- 책임 `LlmFailureAttribution`: 이 실패의 책임이 어디 있는가. `INPUT`(이 요약 대상의 요청·응답), `MODEL`, `PROVIDER`(계정과 endpoint), `NONE`(우리 가드가 호출 전에 막음).
- 지속 `transient`: 다음 tick이나 다음 후보에서 저절로 풀리는가.

| 상황 | 코드 | 책임 | transient |
| --- | --- | --- | --- |
| 404 | `LLM_MODEL_NOT_FOUND` | MODEL | false |
| 400·413·422 | `LLM_REQUEST_REJECTED` | INPUT | false |
| 401 | `LLM_UNAUTHORIZED` | PROVIDER | false |
| 402 | `LLM_PAYMENT_REQUIRED` | PROVIDER | false |
| 403 | `LLM_FORBIDDEN` | PROVIDER | false |
| 429 | `LLM_RATE_LIMITED` | PROVIDER | true |
| 5xx | `LLM_SERVER_ERROR` | PROVIDER | true |
| timeout | `LLM_TIMEOUT` | PROVIDER | true |
| 연결·IO | `LLM_NETWORK_ERROR` | PROVIDER | true |
| JSON 계약 위반, 빈 content | `LLM_INVALID_RESPONSE` | INPUT | false |
| 가드 차단 | `LLM_NOT_PERMITTED` | NONE | true |
| 그 외 | `LLM_UNKNOWN_ERROR` | PROVIDER | true |

| 판단 | 규칙 |
| --- | --- |
| 다음 후보로 failover | 항상 |
| 서킷 기록 | 책임이 MODEL 또는 PROVIDER이고 transient이며 실제 호출일 때 |
| 모델 hold | 책임이 MODEL이고 transient가 아닐 때. `reprobe-after` 뒤 한 번 다시 시도 |
| 제공자 hold | 책임이 PROVIDER이고 transient가 아닐 때. 그 제공자의 모든 모델을 뺀다. `reprobe-after` 뒤 다시 시도 |
| cooldown | 429. Retry-After를 따르고 없으면 기본값, 상한 있음 |
| story 격리 카운트 | 시도한 후보 전부가 INPUT으로 끝났을 때만 |
| tick 조기 중단 | 실제 호출이 한 건도 나갈 수 없을 때. 후보 전부가 차단·hold·cooldown |

hold는 서킷과 다른 상태다.

- 서킷은 표본(비율)으로 열리고 대기 시간 뒤 탐색하지만, 404와 402는 한 건으로 확정이고 60초 뒤 풀릴 것도 아니다.
- 재탐색 간격을 둔 이유는 403 일일 한도처럼 시간이 풀어 주는 경우가 있어서다.
- 운영자 reset은 서킷·cooldown·hold를 함께 푼다.

story 격리를 "전 후보 합의"로 좁힌 이유는 격리의 오판 비용이 호출 비용보다 크기 때문이다.

- 400과 JSON 계약 위반은 story 탓일 수도 모델 탓일 수도 있다.
- 격리는 자동 해제가 없어 한 번 잘못 세면 운영자가 손댈 때까지 그 story는 요약되지 않는다.
- 나쁜 story 하나에 시도당 후보 수만큼 호출이 드는 것이 그 대가다.

### 프롬프트와 버전은 코드에 함께 두고, 모델 교체는 재요약을 일으키지 않는다

- 프롬프트 본문과 버전을 한 파일에 둔다(`LlmPrompt`와 용도별 객체). 이전에는 본문이 adapter 상수, 버전이 yaml에 따로 있어 어긋날 수 있었다.
- ADR 011의 "model 교체 시 promptVersion을 올린다" 규칙은 폐지한다.
  - 요약 버전은 새 기사가 있을 때만 생긴다. 모델을 바꿨다고 이미 요약한 기사를 다시 요약하면 새 버전이 생겨 같은 사건 알림이 다시 나간다.
  - promptVersion은 프롬프트만 뜻한다.
- 요청한 모델(`requestedModel`)과 응답이 보고한 모델(`model`)을 따로 기록한다.
  - 응답이 모델을 보고하지 않으면 요청한 모델을 그대로 둔다. 둘 다 항상 값이 있다.
  - Mongo 문서에는 `requestedModel`을 더하고 기존 `model`은 유지한다. 운영 데이터가 없어 옛 문서 호환 코드는 두지 않는다.

### 실제 provider를 부르는 검증은 별도 소스셋 `src/realTest`에 둔다

바깥에 닿는 테스트의 스위치는 플래그가 아니라 어느 테스트를 실행했는가다.

- `./gradlew test`는 `src/test`만 돌므로 실호출이 0이다.
  - 실호출은 `realTest`를 실행할 때만 나가고 `check`에 넣지 않는다.
- secret이 없으면 skip이 아니라 실패다. 일부러 실행한 테스트가 조용히 skip되면 결과를 오해한다.
  - ADR 029의 "secret 부재는 skip"은 이 결정으로 폐지한다.
- 유료 계정(`billing: metered`)은 플래그가 아니라 클래스 분리로 opt-in한다.
- 검증은 모델마다 `GET /models`로 존재를, 후보로 오른 (용도, 모델) 쌍마다 실제 생성 1회로 계약을 본다.
  - `/models`만으로는 402·403이 드러나지 않는다. 요약과 확장은 JSON 계약이 달라 용도마다 본다.
- 규칙은 `docs/테스트전략.md`에, 실행 방법과 보고 형식은 `ai-service/README.md`에 있다. notification-worker의 실 webhook 테스트도 같은 규칙으로 옮긴다.

이 구조는 아래 공식문서들을 참고하여 구성했다.

- Gradle 공식 문서 [The JVM Test Suite Plugin](https://docs.gradle.org/current/userguide/jvm_test_suite_plugin.html).
  - 테스트 종류마다 소스셋과 task를 따로 두는 형식으로 Gradle 7.3에 도입됐다. 옛 `sourceSets` 방식도 문서에 그대로 있다.
  - "only the built-in `test` suite will automatically have a dependency on the production code of the project"가 `implementation(project())`의 근거다.
  - "only the built-in `test` suite will automatically have access to the production source's `implementation` dependencies, all other suites must explicitly declare these"가 `realTestImplementation`이 `testImplementation`을 잇는 근거다. `testImplementation`은 `implementation`을 이미 잇고 있어 main의 의존성과 test 라이브러리(JUnit·Spring test)를 한 번에 받는다.
  - "test suite targets are not associated with the `check` task"가 기본이라 `check`에 넣지 않는 것은 별도 설정이 아니다.
- Gradle 공식 문서 [Testing in Java & JVM projects › Configuring integration tests](https://docs.gradle.org/current/userguide/java_testing.html#sec:configuring_java_integration_tests).
  - 같은 구조를 `sourceSets`와 `configurations.extendsFrom`으로 쓰는 옛 형식이다.
- Spring Boot 저장소의 빌드 플러그인 [IntegrationTestPlugin](https://github.com/spring-projects/spring-boot/blob/main/buildSrc/src/main/java/org/springframework/boot/build/test/IntegrationTestPlugin.java)(`src/intTest`)과 [DockerTestPlugin](https://github.com/spring-projects/spring-boot/blob/main/buildSrc/src/main/java/org/springframework/boot/build/test/DockerTestPlugin.java)(`src/dockerTest`).
  - 바깥 자원이 필요한 테스트를 소스셋으로 가르는 실제 사례다. `dockerTest`는 `test` 소스셋의 output을 classpath에 더하며, `realTest`가 `AiTestFixture`를 가져다 쓰는 것과 같다.
  - 두 플러그인은 `check`에 연결한다. CI에 Docker가 있기 때문이고, 실 provider를 부르는 `realTest`는 그 조건이 없어 연결하지 않는다.
- Maven [Failsafe Plugin](https://maven.apache.org/surefire/maven-failsafe-plugin/).
  - "The Failsafe Plugin is designed to run integration tests while the Surefire Plugin is designed to run unit tests." 이름 규칙(`**/*IT.java`)으로 같은 분리를 하는 Maven 쪽 관행이다.
- JUnit [`@Tag`](https://github.com/junit-team/junit-framework/blob/main/junit-jupiter-api/src/main/java/org/junit/jupiter/api/Tag.java)는 채택하지 않았다.
  - "Tags are used to filter which tests are executed for a given test plan." 태그는 같은 소스셋 안의 필터라, `src/test` 전체 실행에서 빠지려면 Gradle과 IDE에 각각 제외 설정이 있어야 한다.
  - 소스셋은 디렉터리·classpath·task가 갈려 있어 `src/test` 전체를 어느 러너로 돌려도 realTest가 섞이지 않는다. 클래스를 직접 실행하면 나가는 것은 두 방식이 같고, 그것이 이 설계의 의도다.
- JUnit 5.12 [release notes](https://docs.junit.org/5.12.0/release-notes/)의 `@ParameterizedTest(allowZeroInvocations = true)`.
  - 과금 후보가 없는 프로파일에서 과금 검증 클래스가 0건인 것을 실패로 보지 않기 위해 쓴다.

### 운영 API는 모델 단위다

| 경로 | 내용 |
| --- | --- |
| `GET /api/v1/internal/llm/models` | 후보 모델 전부의 상태. 서킷 상태와 비율, cooldown 종료 시각, hold 사유와 해제 시각, 제공자 계정의 과금 방식 |
| `POST /api/v1/internal/llm/models/{model}/reset` | 그 모델의 서킷·cooldown·hold와 제공자 hold를 함께 해제 |
| `POST /api/v1/internal/llm/models/{model}/probe` | 그 모델 하나에 키워드 확장 1회 실호출. 과금 호출임을 응답에 명시 |

라우터를 통과해 어느 모델이 응답할지 모르던 이전의 `GET /internal/providers/llm/health`는 없앤다.

## 검토한 대안

- 요청 옵션을 `Map<String, Any>`로: 어떤 키가 유효한지 아무도 모르고 오타가 실호출까지 간다.
- 회사마다 adapter 클래스: 같은 규격의 코드가 회사 수만큼 복제된다. 규격과 회사를 나눈 이유가 이것이다.
- 실호출 게이트를 시스템 프로퍼티나 JUnit 태그로: 프로퍼티는 IDE에서 매번 VM 옵션이 필요하고, 태그는 러너에 따라 같은 클릭의 결과가 달라진다. 소스셋은 디렉토리가 곧 스위치다.
- 기동 시 자동 probe: 재기동마다 호출이 나가고 테스트 stub이 `/models`까지 흉내 내야 한다.
- INPUT 실패를 첫 후보에서 즉시 확정: 호출은 1회로 끝나지만 모델 탓 실패가 story를 영구 격리한다.
- gateway(LiteLLM 등) 도입: 다른 서비스가 LLM을 부르거나, credential을 앱 밖에서 중앙 통제하거나, 정책 변경이 배포와 분리돼 잦거나, JVM 밖 소비자가 생길 때 다시 본다. 지금은 넷 다 아니다.

## 트레이드오프

- 장점:
  - 죽은 모델이나 결제 문제가 story를 격리시키지 않는다. 그 단위만 빠지고 다음 후보로 넘어간다.
  - 모델·제공자·옵션의 오타와 누락을 컴파일이 잡고, 존재와 계약은 realTest가 잡는다.
  - 새 회사는 상수 하나, 새 규격은 adapter 하나, 후보 변경은 yaml 한 줄이다.
- 단점:
  - 새 모델은 코드 변경과 커밋이다. 다만 검증을 거쳐 커밋하는 것이 어차피 규칙이다.
  - 나쁜 story 하나가 격리되기까지 시도당 후보 수만큼 호출이 든다.
  - 서킷·cooldown·hold는 인스턴스마다 독립이다. 인스턴스가 늘면 Redis로 옮긴다.

## 운영 제약

- 모델을 바꾸거나 더할 때는 `realTest`를 통과한 뒤 커밋한다. 검증 일자와 상태는 소스에 두지 않고, 죽은 모델은 상수를 지운다.
- 유료 계정 검증은 과금된다.
- Ollama가 떠 있지 않으면 realTest와 local 실행이 실패한다.

## 후속

- notification-worker의 `*RealIntegrationTest` 세 개를 `src/realTest`로 옮기고 secret 없으면 실패로 바꾼다. 이 결정의 구현 범위 밖이다.
- Moonshot(Kimi) 유료 계정이 생기면 `MOONSHOT_KIMI_K3` 상수와 키를 더한다.
- OpenRouter·Together·Cerebras는 계정을 정리한 뒤 모델 상수를 더한다.
- 인스턴스가 둘 이상이 되면 서킷·cooldown·hold 상태를 Redis로 옮긴다. 스케줄러 분산 lock과 함께 다룬다.
