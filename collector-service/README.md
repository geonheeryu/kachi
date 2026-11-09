# collector-service

뉴스와 시장 데이터를 외부 provider에서 수집하고, 수집 실행 기록과 신규 뉴스를 MongoDB에 저장하는 서비스다.

구현 범위는 뉴스 수집이다. 시장 데이터 수집은 미구현이다.

## 구현 상태

- `News`, `CollectionRun`, `ProviderCollectionResult` 도메인 모델
- 정규화 URL hash 기반 중복 방어
- provider 발췌문·언어 보존
- 수집 키워드 기준 provider 호출
- 같은 수집 batch 안의 동일 URL 병합
- 저장 전 기존 URL hash 조회
- 저장 중 unique 충돌을 중복으로 집계
- provider별 수집 결과를 `CollectionRun`에 기록
- Google News RSS provider
- Naver Search API provider
- Finnhub Company News provider
- `user-service` 내부 API 기반 활성 키워드 조회
- scheduler 기반 자동 수집 진입점
- internal API 기반 수동 수집 진입점
- 단일 인스턴스 기준 중복 실행 방지
- MongoDB reactive persistence adapter
- 기사 저장과 한 트랜잭션인 outbox 기록, relay의 Kafka 발행

## 수집 흐름

```text
Scheduler 또는 Internal API
  -> NewsCollectionExecutor
      -> CollectNewsUseCase
          -> KeywordReaderPort
              -> user-service internal active keywords API
          -> NewsProviderPort
              -> Google News RSS
              -> Naver Search API
              -> Finnhub Company News
          -> NewsPersistencePort
              -> MongoDB news + collector_outbox (한 트랜잭션)
          -> CollectionRunPersistencePort
              -> MongoDB collection_runs

CollectorOutboxRelayScheduler
  -> CollectorOutboxRelayExecutor
      -> RelayCollectorOutboxUseCase
          -> CollectorOutboxPublisherPort
              -> Kafka collector.news.collected
```

`CollectNewsCommand.keywords`가 비어 있으면 `user-service`에서 활성 키워드를 조회한다.
수동 실행 요청에서 키워드를 직접 넘기면 해당 키워드만 수집한다.

## API

뉴스 provider 연결 확인:

```http
GET /api/v1/internal/providers/news/{source}/health?keyword=NVIDIA
```

지원 source:

- `GOOGLE`
- `NAVER` (Naver API key 필요)
- `FINNHUB` (Finnhub API key 필요)

이 API는 외부 provider 호출과 응답 파싱까지만 확인한다.
뉴스 저장과 `CollectionRun` 기록은 하지 않는다.

응답 예시:

```json
{
  "success": true,
  "data": {
    "source": "GOOGLE",
    "keyword": "NVIDIA",
    "fetchedCount": 10,
    "samples": [
      {
        "title": "NVIDIA ...",
        "url": "https://news.google.com/...",
        "publishedAt": "2026-05-30T00:00:00Z"
      }
    ]
  },
  "error": null
}
```

수동 뉴스 수집:

```http
POST /api/v1/internal/collections/news
```

요청 body는 생략할 수 있다. 생략하면 활성 키워드를 자동 조회한다.
키워드를 직접 넘기면 user-service를 조회하지 않고 그 키워드로 수집한다.

```json
{
  "keywords": ["NVIDIA", "AI 반도체"],
  "sources": ["GOOGLE"]
}
```

정상 응답:

```json
{
  "success": true,
  "data": {
    "id": "018f...",
    "targetType": "NEWS",
    "status": "SUCCEEDED",
    "startedAt": "2026-05-30T00:00:00Z",
    "finishedAt": "2026-05-30T00:00:03Z",
    "requestedKeywords": 2,
    "collectedCount": 10,
    "duplicateCount": 3,
    "failureCount": 0,
    "failureReason": null
  },
  "error": null
}
```

이미 수집이 실행 중이면 `409 CONFLICT`, 실행 lock을 확인할 수 없으면 `503 SERVICE_UNAVAILABLE`을 반환한다.

저장 뉴스 조회:

```http
GET /api/v1/internal/news?keyword=NVIDIA&from=2026-06-01T00:00:00Z&to=2026-06-02T00:00:00Z&limit=20
```

이 API는 `ai-service`가 요약 대상 뉴스를 읽기 위한 내부 계약이다.
`keyword`는 필수이고, `from`, `to`, `limit`은 선택값이다. `limit`은 1 이상 100 이하이고 생략하면 20이다.
`excerpt`는 provider가 준 발췌문이고 `language`는 기사 언어다. Google은 설정 `language-code`의 값이고 Naver는 `ko`, Finnhub은 `en`으로 고정이다.
모든 필드가 항상 있다. 제목·발췌문·URL·발행 시각 중 하나라도 없는 provider item은 수집 시점에 제외된다.

```json
{
  "success": true,
  "data": [
    {
      "id": "018f...",
      "source": "NAVER",
      "title": "NVIDIA ...",
      "excerpt": "엔비디아가 ...",
      "url": "https://n.news.naver.com/...",
      "language": "ko",
      "publishedAt": "2026-06-01T10:00:00Z",
      "collectedAt": "2026-06-01T10:05:00Z",
      "matchedKeywords": ["NVIDIA"]
    }
  ],
  "error": null
}
```

outbox 조회와 복구:

```http
GET /api/v1/internal/collector/outboxes?status=DEAD&limit=50
POST /api/v1/internal/collector/outboxes/{outboxId}/recover
```

조회 기본값은 `status=DEAD`, `limit=50`이고 `limit`은 1 이상 500 이하다. payload는 응답에 넣지 않는다.
복구는 DEAD 행을 PENDING으로 되돌리기만 하고 발행은 다음 relay tick이 한다. 없는 행은 `404`, DEAD가 아닌 행은 `409`다.

## 이벤트 발행

기사 한 건이 저장되면 같은 트랜잭션으로 outbox에 기록되고, relay가 그 행을 Kafka topic으로 내보낸다.
outbox 행 하나가 레코드 하나다. key는 기사 id, value는 기록 시점의 계약 JSON이다.
계약 타입은 `collector-contract` 모듈에 있다.

| eventType | topic | 계약 |
| --- | --- | --- |
| `NEWS_COLLECTED` | `collector.news.collected` | `CollectorNewsCollectedEvent` |

이벤트는 제목·발췌문·URL·출처·언어·시각·매칭 키워드를 다 싣는다. 소비자는 이 값만으로 처리하고 collector의 저장소나 조회 API를 부르지 않는다.
topic은 기사 id compaction과 `kachi.collector.events.retention`(기본 30일) 삭제를 함께 건다. 기사는 저장 후 바뀌지 않으므로 그 기간 안에서는 처음부터 다시 읽어 재처리할 수 있다.
topic 선언은 `NewTopic` 빈으로 두고, 이미 있는 topic의 설정은 바꾸지 않는다.

스위치는 둘이고 뜻이 다르다.

| 키 | 뜻 | 기본값 |
| --- | --- | --- |
| `kachi.collector.outbox.relay.enabled` | outbox를 읽어 발행 포트로 넘기는 relay | `true` |
| `kachi.collector.events.enabled` | Kafka 발행 어댑터와 topic 선언 | `true` |

relay만 켜고 어댑터가 없으면 기동에 실패한다. local은 둘 다 켜고 test 프로파일은 둘 다 `false`다.

## Scheduler

뉴스 수집 scheduler 설정:

```yaml
kachi:
  collector:
    scheduler:
      news:
        enabled: true
        fixed-delay: 10m
        initial-delay: 30s
```

기본 설정은 `application.yaml`에 두고 test 프로필만 scheduler를 끈다. local은 운영과 같이 켜져 있다.

## 단일 인스턴스 실행 가정

중복 실행 방지는 `ExecutionLockPort` 뒤에 있다.
`CollectorExecutionLock`이 뉴스 수집을 `CLUSTER`, outbox relay를 `INSTANCE` 범위로 선언하고, executor는 이 포트로 실행 겹침 방지를 요청할 뿐 lock 구현을 모른다.

두 범위 모두 `InMemoryExecutionLockAdapter`로 이어져 있어 단일 collector 인스턴스 안에서만 유효하다.
인스턴스를 늘리면 `CLUSTER` 범위 작업이 인스턴스마다 따로 돌므로, 그 전에 `ExecutionLockConfig`의 범위-구현 연결을 distributed lock adapter로 바꾼다. executor와 진입점은 바뀌지 않는다.

## 외부 연동

### user-service

활성 키워드 조회:

```text
GET {KACHI_USER_SERVICE_BASE_URL}/api/v1/internal/keywords/active
```

`user-service` local 프로필은 collector 연동 확인을 위해 기동 시 `TRUMP`, `NVIDIA`, `SPACE-X`, `TESLA`, `이란`
활성 키워드를 앱 기동 시 초기화한다.

설정:

```yaml
kachi:
  collector:
    clients:
      user-service:
        base-url: http://localhost:8080
        active-keywords-path: /api/v1/internal/keywords/active
        timeout: 3s
        max-in-memory-size: 262144
```

### Google News RSS

Google News RSS는 API key 없이 수집할 수 있다. 공식 안정 JSON API가 아니므로 응답 구조나 접근 제한이 바뀔 수 있다.

상세 설정 근거는 [collector-service WebClient 설정](../docs/collector-webclient-설정.md)을 따른다.

### Naver News Search

Naver Search API의 뉴스 검색 endpoint를 provider로 사용할 수 있다.

```yaml
kachi:
  collector:
    providers:
      naver:
        enabled: true
        client-id: ${NAVER_CLIENT_ID}
        client-secret: ${NAVER_CLIENT_SECRET}
        display: 100
        sort: date
```
기본으로 켜져 있고 credential이 없으면 기동에 실패한다. 쓰지 않을 환경은 `KACHI_COLLECTOR_PROVIDERS_NAVER_ENABLED=false`로 끈다.

### Finnhub Company News

Finnhub Company News endpoint를 provider로 사용할 수 있다.
collector 키워드를 Finnhub의 company symbol로 보고 최근 N일 뉴스를 조회한다.

```yaml
kachi:
  collector:
    providers:
      finnhub:
        enabled: true
        api-key: ${FINNHUB_API_KEY}
        lookback-days: 7
```

기본으로 켜져 있고 credential이 없으면 기동에 실패한다. 쓰지 않을 환경은 `KACHI_COLLECTOR_PROVIDERS_FINNHUB_ENABLED=false`로 끈다.

## 저장소

`collector-service`는 MongoDB를 사용한다.

저장 대상:

- `news`: 수집된 뉴스. `urlHash`는 정규화 URL의 SHA-256이고 `(source, urlHash)`가 unique다
- `collection_runs`: 수집 실행 기록
- `collector_outbox`: 발행 대기 이벤트. `eventKey`(기사 id)가 unique다

MongoDB는 replica set이어야 한다. 기사와 outbox를 한 트랜잭션으로 쓴다.

로컬 MongoDB는 `infra/docker-compose.mongo.yml`로 실행한다.

```sh
docker compose -f infra/docker-compose.yml -f infra/docker-compose.mongo.yml up -d
```

## 실행

로컬 프로필 기본값:

```text
server.port=8082
spring.mongodb.uri=mongodb://localhost:27017/kachi_collector?replicaSet=rs0
spring.kafka.bootstrap-servers=localhost:9092
KACHI_USER_SERVICE_BASE_URL=http://localhost:8080
kachi.collector.scheduler.news.enabled=true
```

실행:

저장소 루트에서 실행한다.

```sh
set -a
source .env.local
set +a
./gradlew :collector-service:bootRun
```

Google RSS 연결 확인:

```sh
curl "http://localhost:8082/api/v1/internal/providers/news/GOOGLE/health?keyword=NVIDIA"
```

Naver News Search 연결 확인:

```sh
curl "http://localhost:8082/api/v1/internal/providers/news/NAVER/health?keyword=NVIDIA"
```

Finnhub Company News 연결 확인:

```sh
curl "http://localhost:8082/api/v1/internal/providers/news/FINNHUB/health?keyword=NVDA"
```

collector 단독 뉴스 수집 smoke:

```sh
curl -X POST "http://localhost:8082/api/v1/internal/collections/news" \
  -H "Content-Type: application/json" \
  -d '{"keywords":["NVIDIA"],"sources":["GOOGLE"]}'
```

테스트:

```sh
./gradlew :collector-service:test
```

## 다음 작업

- `CollectionRun` 조회 API 추가
- provider 실패 사유 세분화
- Google RSS 리다이렉트 URL을 원문 URL로 해석
- Google RSS 제목/언론사명 정규화
- 분산 실행이 필요해지는 시점에 distributed lock 도입

## 관련 문서

- [collector-service 뉴스 수집 실행 모델](../docs/decisions/008-collector-service-뉴스-수집-실행-모델.md)
- [외부 뉴스 provider 연동 기준](../docs/decisions/009-외부-뉴스-provider-연동-기준.md)
- [MongoDB replica set 전환과 트랜잭션 전제](../docs/decisions/017-mongodb-replica-set-전환과-트랜잭션-전제.md)
- [ai-service outbox와 이벤트 발행 보장](../docs/decisions/022-ai-service-outbox와-이벤트-발행-보장.md)
- [collector 기사 이벤트 발행과 collector-contract](../docs/decisions/032-collector-기사-이벤트-발행과-collector-contract.md)
- [실행 lock 포트 분리와 lock 범위](../docs/decisions/036-실행-lock-포트-분리와-lock-범위.md)
