# Collector 컨텍스트 도메인 모델

collector-service의 도메인 모델이다. 외부 뉴스 provider에서 뉴스를 수집·저장하고,
수집 실행 한 번의 결과를 운영 기록으로 남긴다.
공통 관례는 [도메인모델.md](도메인모델.md)를 따른다.

## 뉴스 애그리거트

### 뉴스(News)

_Aggregate Root_

#### 속성(Attributes)

- `id`: `NewsId` 뉴스 식별자
- `source`: `NewsSource` 뉴스 출처 provider
- `title`: `NewsTitle` 제목
- `excerpt`: `NewsExcerpt` provider가 준 발췌문
- `url`: `NewsUrl` 원문 URL
- `urlHash`: 정규화 URL의 hash (생성 시 `url.hash`에서 고정)
- `language`: `NewsLanguage` 기사 언어. provider 설정이 정한다
- `publishedAt`: 발행 시각
- `collectedAt`: 수집 시각
- `matchedKeywords`: `List<CollectedKeyword>` 이 뉴스와 매칭된 수집 키워드 목록

#### 행위(Behaviors)

- `static create(source, title, excerpt, url, language, publishedAt, collectedAt, matchedKeywords)`: 뉴스를 생성한다. 매칭 키워드 중복을 제거한다
- `static restore(...)`: 저장소 snapshot을 복원한다

#### 규칙(Rules)

- 매칭 키워드는 하나 이상이어야 한다. 같은 뉴스가 여러 키워드에 매칭될 수 있고, 중복 키워드는 제거한다.
- 모든 속성이 있어야 기사다. 도메인에 null 속성은 없다.
  - 제목·발췌문·URL·언어·발행 시각 중 하나라도 없는 item은 provider adapter가 기사로 만들지 않는다.
- URL hash는 같은 페이지의 재수집을 막는 값이다. 실제 저장 중복 방어는 persistence 계층의 `(source, urlHash)` unique index에서 처리한다.
- 기사들 사이의 관계(같은 사건인가)는 collector가 판단하지 않는다. 그 일은 story 컨텍스트의 몫이다(ADR 031).
- 저장 후 수정하는 행위는 없다. 수집된 뉴스는 불변 기록이다.

### 뉴스 식별자(NewsId)

_Value Object_

- `value`: 뉴스 식별 UUID
- `newId()` / `of()`

### 뉴스 제목(NewsTitle)

_Value Object_

- `value`: 뉴스 제목
- `of()`: trim 정규화. 빈 값 불가. 길이·금칙어·의미 정합성은 강하게 검증하지 않는다.

### 발췌문(NewsExcerpt)

_Value Object_

- `value`: provider가 기사와 함께 준 짧은 설명. 본문이 아니다
- `of()`: 연속 공백을 하나로 접고 앞뒤 공백을 지운다. 공백뿐이면 거부한다. 1,000자를 넘는 부분은 잘라 낸다
- HTML 제거는 형식을 아는 provider adapter가 끝낸 뒤 넘긴다

### 언어(NewsLanguage)

_Value Object_

- `value`: ISO 639 기본 부호(`ko`, `en`). `en-US`처럼 지역이 붙은 값은 앞부분만 남긴다
- `of()`: 영문 2~3자가 아니면 거부한다. provider 설정이 정한 값을 기사에 옮긴 것이다

### 뉴스 URL(NewsUrl)

_Value Object_

- `value`: 뉴스 원문 URL. provider가 준 그대로다
- `canonical`: 같은 페이지의 URL 변형을 하나로 모은 값. scheme·host 소문자화, 기본 포트·fragment 제거, 추적 파라미터(`utm_*`, `fbclid`, `gclid` 등) 제거, 남은 파라미터 정렬, 끝 슬래시 제거, AMP 경로·`amp.` 서브도메인 원본화
- `hash`: `canonical`의 SHA-256 파생 프로퍼티
- `of()`: trim 정규화. 빈 값 불가. 파싱할 수 없는 URL은 원문을 그대로 `canonical`로 쓴다

### 수집 키워드(CollectedKeyword)

_Value Object_

- `value`: 수집 기준 키워드
- `of()`: trim 정규화. 빈 값 불가.

### 키워드 참조(KeywordReference)

_Value Object_

- `value`: user-service에서 조회한 활성 키워드 참조값
- `of()`: trim 정규화. 빈 값 불가.
- user 컨텍스트의 `Keyword`를 collector가 자기 어휘로 받아들이는 경계 값이다.
  user-service의 도메인 타입을 직접 import하지 않는다.

### 뉴스 출처(NewsSource)

_Enum_

뉴스를 가져온 provider를 구분한다.

- `GOOGLE`: Google News RSS
- `NAVER`: Naver Search API 뉴스 검색
- `FINNHUB`: Finnhub Company News

provider별 응답 구조, 인증 방식, URL 보정은 adapter 계층에서 처리하고,
application/domain 계층은 `NewsSource`와 수집 결과만 다룬다.

## 수집 실행 애그리거트

### 수집 실행(CollectionRun)

_Aggregate Root_

수집 작업 한 번의 실행 기록이다. 장기 조회용 이력이 아니라, 
scheduler·수동 API로 시작된 수집 한 번의 실행 중 상태와 완료 결과를 표현하는 운영 기록이다. 
실패한 provider 때문에 수집 흐름이 끊겼는지 확인하고 재처리 여부를 판단하는 기준이 된다.

#### 속성(Attributes)

- `id`: `CollectionRunId` 수집 실행 식별자
- `targetType`: `CollectionTargetType` 수집 대상 유형
- `status`: `CollectionRunStatus` 실행 상태
- `startedAt` / `finishedAt`: 시작·종료 시각
- `requestedKeywords`: 수집 대상 키워드 수
- `collectedCount`: 신규 저장 건수
- `duplicateCount`: 중복 제외 건수
- `failureCount`: 실패 provider 수
- `failureReason`: 실패 사유 요약 (실패 provider들의 메시지를 `"; "`로 연결)
- `providerResults`: `List<ProviderCollectionResult>` provider별 수집 결과

#### 행위(Behaviors)

- `static start(targetType, requestedKeywords, startedAt)`: `RUNNING` 상태로 실행을 시작한다
- `static restore(...)`: 저장소 snapshot을 복원한다
- `complete(providerResults, finishedAt)`: provider별 결과를 집계해 실행을 완료한다

#### 규칙(Rules)

- 요청 키워드 수는 0 이상이어야 한다.
- `RUNNING` 상태의 수집 실행만 완료할 수 있다.
- 완료 시각은 시작 시각보다 이전일 수 없다.
- 완료 상태 결정: provider 결과가 없으면 `FAILED`, 전부 성공이면 `SUCCEEDED`, 전부 실패면 `FAILED`, 일부 실패면 `PARTIALLY_FAILED`.
- 신규 저장·중복 제외 건수는 provider별 결과의 합으로, 실패 provider 수는 실패 결과 개수로 계산한다.

### 수집 실행 식별자(CollectionRunId)

_Value Object_

- `value`: 수집 실행 식별 UUID
- `newId()` / `of()`

### 수집 실행 상태(CollectionRunStatus)

_Enum_

- `RUNNING`, `SUCCEEDED`, `PARTIALLY_FAILED`, `FAILED`

### 수집 대상 유형(CollectionTargetType)

_Enum_

- `NEWS`: 뉴스 수집
- `MARKET_DATA`: 시장 데이터 수집 (대상 도메인은 미구현)

### Provider 수집 결과(ProviderCollectionResult)

_Value Object_

provider 하나의 수집 결과다.

#### 속성(Attributes)

- `source`: `NewsSource` 뉴스 provider
- `status`: `ProviderCollectionStatus` provider 수집 상태
- `fetchedCount`: provider에서 가져온 원본 건수
- `savedCount`: 신규 저장 건수
- `duplicateCount`: 중복 제외 건수
- `failureReason`: `ProviderFailureReason?` 실패 사유 분류
- `failureMessage`: 실패 메시지

#### 행위(Behaviors)

- `static success(source, fetchedCount, savedCount, duplicateCount)`: 성공 결과를 생성한다
- `static failure(source, failureReason, failureMessage)`: 실패 결과를 생성한다

#### 규칙(Rules)

- 성공 결과의 수집/저장/중복 건수는 0 이상이어야 하고, 실패 사유와 실패 메시지를 갖지 않는다.
- 실패 결과는 실패 사유와 빈 값이 아닌 실패 메시지를 가져야 하며, 건수는 모두 0으로 기록한다.

### Provider 수집 상태(ProviderCollectionStatus)

_Enum_

- `SUCCEEDED`, `FAILED`

### Provider 실패 사유(ProviderFailureReason)

_Enum_

provider 수집 실패 사유 분류다.

- `TIMEOUT`: provider 응답 지연 또는 timeout
- `RATE_LIMITED`: provider rate limit 또는 quota 제한
- `CLIENT_ERROR`: 잘못된 요청, 인증 실패 같은 4xx 계열 오류
- `SERVER_ERROR`: provider 5xx 계열 오류
- `NETWORK_ERROR`: 연결 실패, DNS 오류 같은 네트워크 오류
- `INVALID_RESPONSE`: 응답 파싱 실패 또는 필수 필드 누락
- `UNKNOWN`: 아직 분류하지 못한 오류

## outbox 애그리거트

story·ai의 outbox와 같은 구조다(ADR 015·022·032). 컨텍스트 간 코드 공유 없이 각자 갖는다.

### 수집 outbox(CollectorOutbox)

_Aggregate Root_

- 발행해야 할 기사 이벤트 1건과 그 발행 상태. `eventType`·`eventKey`·`partitionKey`·`payload`(생성 시점에 계약 JSON으로 고정)·`status`·`retryCount`·`nextRetryAt`·`lastError`·`publishedAt`·`claim` 등
- 행위: `create`, `markPublishing(now, claimedBy)`, `markPublished(now)`, `recordFailure(reason, retryPolicy, now)`, `markDead(reason, now)`, `recoverToPending(now)`
- 전이: PENDING -> PUBLISHING(claim) -> PUBLISHED | DEAD, PUBLISHING -> PENDING(재시도), DEAD -> PENDING(운영자 복구). 늦은 결과는 저장소의 CAS가 거른다
- 뉴스 저장과 outbox 기록은 한 트랜잭션이다 (ADR 032)

### 발행 소유권(CollectorOutboxClaim)

_Value Object_

- `claimedBy`, `claimedAt`. PUBLISHING 상태에서만 존재한다

### outbox 식별자(CollectorOutboxId) · 상태(CollectorOutboxStatus) · 이벤트 종류(CollectorOutboxEventType)

- `CollectorOutboxId`: outbox 식별 UUID
- `CollectorOutboxStatus`: `PENDING`, `PUBLISHING`, `PUBLISHED`, `DEAD`
- `CollectorOutboxEventType`: `NEWS_COLLECTED`. `collector.news.collected`로 나간다

### 재시도 정책(CollectorOutboxRetryPolicy)

_Value Object_

- `maxAttempts`, `baseDelay`, `maxDelay`, `multiplier`, `jitterRatio`
- `backoff(attempts)`: 지수 backoff에 ±jitter를 더한 대기 시간. `exhausted(attempts)`가 DEAD 판정 기준이다

## 시장 데이터(MarketData) — 계획, 미구현

_Aggregate Root_

`CollectionTargetType.MARKET_DATA`가 가리키는 수집 대상이지만 도메인 모델은 아직 없다.

- 시장 데이터는 뉴스 요약의 보조 정보로 사용한다.
- 외부 API 실패는 전체 수집 흐름을 중단시키지 않고 실패 이력으로 남긴다.
