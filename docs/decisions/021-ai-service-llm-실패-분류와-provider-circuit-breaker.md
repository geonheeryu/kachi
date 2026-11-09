# 021. ai-service LLM 실패 분류와 서킷 브레이커, failover, 요약 대상 격리

## 배경

ai-service는 외부 LLM을 부르는 유일한 서비스다. story 요약(ADR 034)과 키워드 확장(ADR 020)이 LLM을 부른다.

처음에는 이 경로에 실패를 구분하는 장치가 없었다.

- HTTP 오류가 분류되지 않아 429든 401이든 프레임워크 예외 그대로 application 계층까지 올라왔다. `Retry-After`는 읽히지 않았다.
- 실행 기록에는 `UNKNOWN`만 쌓였다.
- 모든 실패가 키워드 탓으로 세어져, provider rate limit이 3 tick 이어지면 멀쩡한 키워드가 전부 격리됐다. 격리는 자동 해제가 없다.
- 장애 중에도 남은 키워드가 각자 한 번씩 더 429를 받았다. 죽은 provider로 나가는 호출을 막는 장치가 없었다.

이후 전체 사이클 스모크(ADR 029)에서 한 축(`retryable`) 분류의 한계가 드러났다.
제공 종료된 모델의 404와 결제 문제의 402가 키워드 탓으로 분류돼, failover도 서킷 기록도 없이 키워드 격리 카운트를 올렸다(ADR 030).

## 결정

### 실패 모델

`domain/llm`의 `LlmFailure`가 실패 하나를 표준 코드와 함께 보존한다.

```text
LlmFailure
  code              LlmFailureCode. 표준 코드 12개
  provider          LlmProvider?. 호출 전에 차단된 실패는 null
  message           운영자와 로그가 보는 메시지
  statusCode?       외부 HTTP status
  retryAfterMillis? 429의 Retry-After
```

판단 축은 둘이고 모두 `code`에서 파생한다. 코드와 어긋난 축을 가진 실패를 만들 수 없게 하기 위해서다.
`LlmFailureCode`는 application 예외의 에러 코드 규약 `AiErrorCode`를 구현하지 않는다. 두 축이 그 규약 밖에 남고, 얻는 것은 `defaultMessage`를 `message`로 바꾸는 것뿐이다.

- 책임 `LlmFailureAttribution`: 이 실패의 책임이 어디 있는가. `INPUT`(이 요약 대상의 요청·응답), `MODEL`(모델 하나), `PROVIDER`(계정과 endpoint), `NONE`(우리 가드가 호출 전에 막음).
- 지속 `transient`: 다음 시도나 다음 후보에서 저절로 풀리는가.

코드마다의 책임과 지속은 ADR 030의 표에 있다. 이 문서는 그 두 축을 소비처가 어떻게 쓰는지 정한다.

`retryable` 하나에서 재시도·failover·제외를 모두 파생하던 이전 모델은 폐지했다. 셋은 다른 질문이라 근거를 따로 둔다.

예외는 `LlmProviderException` 하나다.

- `failure`는 대표 실패(마지막 실제 호출)이고 `attempts`는 실제 호출로 끝난 실패 전부다. 차단은 호출이 아니라 `attempts`에 들어가지 않는다.
- notification처럼 Retryable/NonRetryable 예외 계층을 두지 않는다. in-process 재시도가 없어 예외 타입으로 분기할 주체가 없고, 잡는 쪽은 어차피 `failure`의 축을 본다.

notification-core의 `RetryFailure`(ADR 014)와 어휘를 맞추되 코드 의존은 두지 않는다.
두 모델은 변화 이유가 다르다. notification은 vendor가 늘 때, ai는 provider 계약이 바뀔 때 확장된다.

### 소비처마다 판단 하나

| 판단 | 규칙 | 근거 |
| --- | --- | --- |
| 다음 후보로 failover | 실패 종류를 가리지 않고 항상 | 한 모델의 거부가 그 모델 탓일 수 있다 |
| 서킷 기록 `recordsInCircuit` | 실제 호출이고 transient | 응답 계약 위반은 모델이 살아 있다는 증거이고, 404·402는 한 건으로 확정이라 비율로 다룰 것이 아니다 |
| 모델 hold `holdsModel` | MODEL이고 transient가 아님 | 제공 종료된 모델은 60초 뒤 돌아오지 않는다 |
| 제공자 hold `holdsProvider` | PROVIDER이고 transient가 아님 | 계정 문제는 그 제공자의 모든 모델이 같다 |
| cooldown | 429 | provider가 지시한 대기 시간은 비율로 열리는 서킷이 표현하지 못한다 |
| story 격리 카운트 | 시도한 후보 전부가 INPUT | 격리의 오판 비용이 호출 비용보다 크다 |
| tick 조기 중단 | 실제 호출이 한 건도 나갈 수 없음 | 결과가 확정된 루프는 돌지 않는다 |

### provider 예외 매핑

`OpenAiChatAdapter`가 HTTP status와 응답을 실패 코드로 옮긴다.

- HTTP 오류는 Mono 체인 안에서 `LlmProviderException`으로 바꾼다(`exchangeToMono`). 서킷은 체인을 지나는 예외만 보므로 체인 밖 catch에서 바꾸면 늦다.
- timeout은 예외 cause 체인을 훑어 판정한다(`LlmHttpExceptionClassifier`).
- 표에 없는 4xx는 `LLM_UNKNOWN_ERROR`(PROVIDER·transient)다. Retry-After는 429에만 보존한다.

### 실패 원인 기록

`SummarizeStoryService`가 `LlmFailure`를 `AiFailureReason`으로 옮겨 story 격리 기록의 `lastFailureReason`에 남긴다.

| LlmFailureCode | AiFailureReason |
| --- | --- |
| `LLM_TIMEOUT` | `TIMEOUT` |
| `LLM_RATE_LIMITED` | `RATE_LIMITED` |
| `LLM_REQUEST_REJECTED` | `CLIENT_ERROR` |
| `LLM_SERVER_ERROR` | `SERVER_ERROR` |
| `LLM_NETWORK_ERROR` | `NETWORK_ERROR` |
| `LLM_INVALID_RESPONSE` | `INVALID_RESPONSE` |
| `LLM_NOT_PERMITTED` | `PROVIDER_UNAVAILABLE` |
| `LLM_MODEL_NOT_FOUND` | `MODEL_NOT_FOUND` |
| `LLM_UNAUTHORIZED`·`LLM_PAYMENT_REQUIRED`·`LLM_FORBIDDEN` | `ACCOUNT_ERROR` |
| `LLM_UNKNOWN_ERROR` | `UNKNOWN` |

LLM 경로 밖의 실패도 함께 정한다.

| 예외 | AiFailureReason | story 탓 |
| --- | --- | --- |
| `IllegalArgumentException` | `INVALID_RESPONSE` | 예 |
| 그 외 | 기록하지 않는다 | 아니오 |

- `IllegalArgumentException`은 도메인 불변식 위반이다. `StorySummary.create`가 거부하는 값은 이 story의 입력이나 응답에서 온다.
- 판별할 수 없는 실패는 인프라 쪽으로 본다. 잘못 세면 정상 story가 영구 격리되고, 놓치면 다음 시도가 다시 부를 뿐이다.
- `MODEL_NOT_FOUND`·`ACCOUNT_ERROR`는 `ai-contract`에 없어 격리 이벤트에는 `CLIENT_ERROR`로 나간다. 격리 카운트는 INPUT 합의에서만 오르므로 이 두 값이 격리 이벤트에 실릴 경로는 없다.

### 격리 카운트 규칙

story 격리 카운트(`StoryQuarantine.recordFailure()`)는 story 탓 실패에서만 올린다.

- LLM 실패는 `LlmProviderException.allInput`, 즉 시도한 후보 전부가 INPUT으로 끝났을 때만 story 탓이다.
- 400과 JSON 계약 위반은 story 탓일 수도 모델 탓일 수도 있다. 후보 하나의 판정으로 세지 않는다.
- 그 밖의 실패는 카운트를 갱신하지 않고 quarantine 문서도 만들지 않는다. 예외로 올라간다.
- 소비 경로의 예외는 같은 offset을 다시 처리하게 하고, tick 경로의 예외는 그 story를 다음 tick으로 미룬다(ADR 034). 미요약 기사는 그대로 남는다.

격리는 재시도로 해결되지 않는 실패를 걷어내는 장치다(ADR 020).
rate limit과 timeout은 다음 시도가 해결하므로, 세면 격리가 해결책이 아닌 문제에 개입하고 그 개입은 영구적이다.

### tick 조기 중단

story 요약 tick에서 story 하나의 실패가 실제 호출 없이 끝났으면 남은 story를 호출하지 않고 건너뛴다.

- 실제 호출이 한 건도 나가지 못했다는 것은 후보 전부가 서킷·cooldown·hold라는 뜻이고, 이번 tick 안에서 풀리지 않는다.
- 429 한 건은 중단 사유가 아니다. 한 모델이 rate limit이어도 다른 후보나 다음 story는 시도해 볼 수 있다.
- 건너뛴 story는 미요약 기사를 그대로 가지므로 다음 tick이 다시 고른다. 중단은 작업을 버리는 것이 아니라 미루는 것이다.

### 모델 단위 가드

후보 모델마다 `GuardedLlmModel`이 adapter를 같은 포트로 감싼다(데코레이터 패턴, ADR 030). 차단 장치는 넷이다.

- 서킷 브레이커: 모델마다 하나. `recordsInCircuit`인 실패만 표본이다. 설정은 ADR 030의 `guard.circuit-breaker`이고 slow call duration threshold는 모델의 `slow-after`다.
- cooldown: 429의 Retry-After(없으면 기본값, 상한 있음) 동안 후보에서 뺀다.
- 모델 hold: 404를 받은 모델을 `reprobe-after` 동안 뺀다.
- 제공자 hold: 401·402·403을 받은 제공자의 모든 모델을 `reprobe-after` 동안 뺀다. 같은 제공자의 가드들이 `ProviderHoldRegistry`로 공유한다.

하나라도 걸리면 호출하지 않고 `LLM_NOT_PERMITTED`(NONE·transient)로 실패한다.

- 이 실패는 서킷에 기록하지 않는다. 호출이 없었으니 provider 상태의 증거가 아니고, 기록하면 차단이 차단을 유지하는 근거가 되어 회로가 닫힐 기회를 잃는다.
- hold는 서킷과 다른 상태다. 서킷은 표본으로 열리고 대기 뒤 탐색하지만 hold는 한 건으로 확정이라, 만료되면 호출 한 번이 통과하고 같은 실패면 다시 걸린다.
- 운영자 reset은 서킷·cooldown·hold를 함께 푼다.

`permittedNumberOfCallsInHalfOpenState`는 2다. half-open은 probe이지 정상 트래픽 복귀가 아니다(ADR 018).

### failover

`RoutingLlmProvider`가 용도마다 정해진 후보를 순서대로 호출한다(컴포지트 패턴, ADR 030).

```text
후보 = 그 용도의 candidates 순서 그대로
각 후보 최대 1회 호출
성공 -> 반환
실패 -> 종류를 가리지 않고 다음 후보
후보 소진 -> LlmProviderException(failure = 마지막 실제 호출의 실패, attempts = 실제 호출 전부)
```

- 순서는 설정 그대로다.
- 차단·hold·cooldown 상태는 in-process에 둔다. in-process에 두지 않는 것은 재시도 루프이고, 호출 차단은 in-process가 맞는 자리다.
- in-process 재시도(`RetryOperator`)는 쓰지 않는다.
  - 재전달·tick 재시도와 곱해져 호출 수가 `대상 × 시도 × 재시도`가 되고, 시도 흔적이 영속 상태에 남지 않는다.
  - 재시도 구동은 영속 상태에 둔다.

## 결과

- 죽은 모델이나 결제 문제가 story를 격리시키지 않는다. 그 모델이나 제공자만 빠지고 다음 후보로 간다.
- provider rate limit이 이어져도 정상 story가 영구 격리되는 경로가 없다.
- story 격리 기록이 마지막 실패 원인을 남겨, 격리된 story를 보고 조치가 필요한지 알 수 있다.
- 호출할 수 있는 모델이 없으면 이번 tick의 호출이 즉시 멈춘다.

## 트레이드오프

- 나쁜 story 하나가 격리되기까지 시도당 후보 수만큼 호출이 든다. 격리 오판의 비용이 더 크다.
- 서킷·cooldown·hold는 인스턴스마다 독립이다. 인스턴스가 늘면 Redis로 옮긴다(ADR 030 후속).

## 제외한 것

- retry budget(ADR 018): 재시도가 tick 단위라 분모가 고정이고, 서킷과 조기 중단이 총량을 잡는다. 관측 지표 없이 비율을 정하면 근거 없는 숫자다.
- bulkhead와 slow start: tick 안의 호출은 순차라 동시성이 1이다. story 병렬 요약을 도입하면 함께 본다.
- token/cost budget: 사용량 데이터가 쌓인 뒤 관측과 함께 다룬다.
- provider별 metric 노출: observability(ADR 019) 이후다.
