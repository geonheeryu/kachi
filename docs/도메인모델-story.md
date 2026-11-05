# Story 컨텍스트 도메인 모델

story-service의 도메인 모델이다. collector가 발행한 기사를 같은 사건 단위의 story로 조립하고,
기사의 소속과 판정 기록을 정본으로 갖는다(ADR 031·033).
공통 관례는 [도메인모델.md](도메인모델.md)를 따른다.

기사 사실(제목·발췌문·URL 등)의 정본은 collector다. 이 컨텍스트의 기사는 임베딩 입력과 재처리를 위한 사본이다.

## story 애그리거트

### 스토리(Story)

_Aggregate Root_

#### 속성(Attributes)

- `id`: `StoryId` story 식별자. 첫 기사가 배정받은 id를 그대로 쓴다
- `status`: `StoryStatus` OPEN·CLOSED
- `centroid`: `Embedding` 구성 기사 임베딩의 산술 평균. 정규화하지 않는다
- `articleCount`: 구성 기사 수
- `keywords`: `Set<StoryKeyword>` 구성 기사 `matchedKeywords`의 합집합. 라우팅 속성이다
- `openedAt`: 연 시각
- `lastArticleAt`: 구성 기사 중 가장 늦은 발행 시각. 닫힘 판정 기준이다
- `closedAt`: 닫힌 시각. CLOSED에서만 존재한다
- `parentStoryId`: 닫힌 story의 후속으로 열렸을 때 그 계보
- `mergedInto`: 병합으로 흡수됐을 때 살아남은 story
- `version`: 전이마다 1 오르는 CAS 기준

#### 행위(Behaviors)

- `static open(first, now, parentStoryId?)`: 첫 기사로 story를 연다
- `static restore(...)`: 저장소 snapshot을 복원한다
- `attach(article, now)`: 기사 한 건을 붙인다. centroid 증분 갱신, keywords 합집합, `lastArticleAt` 갱신
- `close(now)`: 닫는다
- `mergeInto(target, now)` / `absorb(other, now)`: 병합의 두 쪽. 흡수된 쪽은 CLOSED가 되고 `mergedInto`를 남긴다
- `recompose(articles, now)`: 분리 뒤 구성 기사 전체에서 파생 상태를 다시 계산한다
- `acceptsMore(maxArticles)`: 병합 대상이 될 수 있는지. OPEN이고 상한 아래여야 한다

#### 규칙(Rules)

- 불변이며 전이 메서드는 version을 하나 올린 새 인스턴스를 반환한다. 저장소는 직전 version을 조건으로 쓴다(CAS).
- 전이는 OPEN에서만 가능하다. CLOSED는 종단 상태이고, 닫힌 뒤 온 같은 사건의 기사는 새 story가 되어 `parentStoryId`로 이어진다.
- `attach`·`absorb`는 centroid의 임베딩 모델이 같아야 한다. 다른 모델의 벡터는 평균할 수 없다.
- `mergedInto`는 CLOSED에서만, `closedAt`은 CLOSED와 함께만 존재한다. 자기 자신을 흡수하거나 자기 자신의 후속일 수 없다.

### 스토리 식별자(StoryId)

_Value Object_

- `value`: story 식별 UUID (v7)
- `newId()` / `of()`

### 스토리 상태(StoryStatus)

_Enum_

- `OPEN`: 기사를 받을 수 있다. 후보 색인에 벡터가 있다
- `CLOSED`: 종단. 닫힘(48h 무기사)·병합·분리 원본 소멸로 진입하며 색인에서 빠진다

### 스토리 키워드(StoryKeyword)

_Value Object_

- `value`: canonicalKey. story에 붙는 라우팅 속성이다
- `of()`: trim 정규화. 빈 값 불가

## 기사 사본 애그리거트

### 기사 사본(StoryArticle)

_Aggregate Root_

#### 속성(Attributes)

- `newsId`: `NewsId` collector가 준 기사 식별자. unique
- `title`, `excerpt`, `url`: 기사 값의 사본
- `source`: `ArticleSource`, `language`: `ArticleLanguage`
- `publishedAt`, `collectedAt`: 시각
- `matchedKeywords`: `List<StoryKeyword>` 기사에 매칭된 키워드
- `embedding`: `Embedding` 이 기사의 벡터. 재임베딩 없이 분리·재구축에 쓴다
- `storyId`: `StoryId` 소속. 이 컨텍스트가 정본인 값이다
- `decision`: `LinkDecision` 붙인 시점의 판정 기록
- `attachedAt`: 붙인 시각

#### 행위(Behaviors)

- `static create(...)`: 조립 판정 직후 생성한다
- `static restore(...)`: 저장소 snapshot을 복원한다
- `reassign(storyId)`: 병합·분리로 소속을 옮긴다. 판정 기록은 그대로다

#### 규칙(Rules)

- 사본이므로 제목·발췌문은 non-blank만 요구하고 collector의 정규화 규칙을 되풀이하지 않는다.
- `decision`은 붙인 시점의 기록이다. 생성 시점에는 merged 판정의 후보 story와 소속이 같아야 하지만, 병합·분리의 `reassign` 뒤에는 소속과 갈라지는 것이 정상이라 복원은 이를 검증하지 않는다.

### 기사 식별자(NewsId)

_Value Object_

- `value`: 기사 식별 UUID. collector의 것을 그대로 받는다
- `of()`

### 기사 출처(ArticleSource)

_Enum_

- `GOOGLE`, `NAVER`, `FINNHUB`. collector-contract의 enum을 경계에서 `when`으로 짝지어 받는다

### 기사 언어(ArticleLanguage)

_Value Object_

- `value`: ISO 639 기본 부호(`ko`, `en`). 지역이 붙은 값은 앞부분만 남긴다
- `of()`: 영문 2~3자가 아니면 거부한다

### 임베딩 텍스트(EmbeddingText)

_Value Object_

- `value`: `"title\nexcerpt"`. 임베딩과 judge에 넣는 텍스트 규칙을 한 곳에 둔다
- `of(title, excerpt)`: 둘 다 non-blank, trim 후 결합

### 판정 기록(LinkDecision)

_Sealed_

기사 한 건을 어느 story에 붙일지 정한 판정의 기록이다. `merged`·`candidateStoryId`·`similarity`가 공통 계약이다.
경우마다 데이터가 달라 enum이 아니라 sealed 타입이다.

- `AutoMergedLinkDecision(storyId, similarity)`: 유사도가 θ_high 이상이라 judge 없이 붙였다
- `JudgedLinkDecision(candidateStoryId, similarity, judge, judgeScore, merged)`: 회색 구간에서 judge가 정했다. 거부(merged=false)면 새 story에 남는다
- `NewStoryLinkDecision(candidateStoryId?, similarity?)`: 새 story를 열었다. 최고 후보와 점수를 남겨 미탐 분석에 쓴다

코사인 유사도는 -1과 1 사이, 판정 점수는 0과 1 사이다.

## 임베딩과 judge

### 임베딩(Embedding)

_Value Object_

- `model`: `EmbeddingModel`, `values`: 벡터. 차원은 모델이 정한다
- `cosine(other)`: 코사인 유사도. 같은 모델끼리만. 부동소수 오차로 범위를 벗어난 값은 -1과 1로 자른다
- `meanWith(other, weight, otherWeight)`: 가중 평균. centroid 증분 갱신과 병합에 쓴다
- 벡터와 모델이 한 타입에 있어, 다른 모델의 벡터를 평균하는 실수를 타입이 막는다

### 임베딩 모델(EmbeddingModel)

_Enum_

- `BGE_M3`: code `bge-m3`, 1024차원
- 정체(code·modelId·차원)는 enum, 주소·시간은 yaml이다(ADR 030 방식). `ofCode`는 모르는 코드면 실패한다

### 판정기(StoryJudge)

_Enum_

- `BGE_RERANKER_V2_M3`: cross-encoder
- `THRESHOLD_ONLY`: judge 없이 검색 점수를 그대로 판정 점수로 쓴다. "코사인만" 구성이 설정 한 줄로 재현된다

## outbox 애그리거트

collector·ai의 outbox와 같은 구조다(ADR 015·022·032·033). 컨텍스트 간 코드 공유 없이 각자 갖는다.

### 스토리 outbox(StoryOutbox)

_Aggregate Root_

- 발행해야 할 story 이벤트 1건과 그 발행 상태. `eventType`·`eventKey`·`partitionKey`·`payload`(생성 시점에 계약 JSON으로 고정)·`status`·`retryCount`·`nextRetryAt`·`claim` 등
- 전이: PENDING -> PUBLISHING(claim) -> PUBLISHED | DEAD, DEAD -> PENDING(운영자 복구). 늦은 결과는 저장소의 claim CAS가 거른다

### 발행 소유권(StoryOutboxClaim)

_Value Object_

- `claimedBy`, `claimedAt`. PUBLISHING 상태에서만 존재한다

### outbox 식별자(StoryOutboxId) · 상태(StoryOutboxStatus) · 이벤트 종류(StoryOutboxEventType)

- `StoryOutboxId`: UUID v7
- `StoryOutboxStatus`: `PENDING`, `PUBLISHING`, `PUBLISHED`, `DEAD`
- `StoryOutboxEventType`: `ARTICLE_ATTACHED`, `MERGED`

### 재시도 정책(StoryOutboxRetryPolicy)

_Value Object_

- `maxAttempts`, `baseDelay`, `maxDelay`, `multiplier`, `jitterRatio`
- `backoff(attempts)`: 지수 backoff에 ±jitter를 더한 대기 시간. `exhausted(attempts)`가 DEAD 판정 기준이다

## 실패 모델

ADR 030의 두 축(귀속 attribution, 지속 transient)을 따른다. TEI와 Qdrant는 프로토콜이 달라 코드 표를 따로 둔다.

### 추론 실패(InferenceFailure)

- `code`: `InferenceFailureCode`(귀속·transient 내장), `target`: `InferenceTarget`(`EMBEDDING`·`JUDGE`), `message`, `statusCode?`
- INPUT 귀속(400·413·422)은 기사 결함으로 DLT, 나머지는 재시도 대상이다(ADR 033)

### 후보 색인 실패(CandidateIndexFailure)

- `code`: `CandidateIndexFailureCode`(gRPC status code에서 매핑), attribution·transient 두 축은 추론 실패와 같다
- 색인 실패는 전부 재시도 대상이다. 색인은 파생 캐시라 유실돼도 재구축으로 돌아온다
