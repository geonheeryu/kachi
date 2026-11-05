# story-service

collector가 발행한 기사를 소비해 같은 사건 단위의 story로 조립하고, story 이벤트를 발행하는 서비스다(ADR 031·033).
기사 사실의 정본은 collector이고, 이 서비스는 기사의 소속(어느 story인가)과 story 상태의 정본이다.

## 현재 구현 상태

- `Story`, `StoryArticle`, `StoryOutbox` 도메인 모델과 판정 기록(`LinkDecision`)
- `collector.news.collected` 소비와 newsId 멱등 재전달 흡수
- TEI 임베딩(`bge-m3`)·판정기(`bge-reranker-v2-m3`) adapter와 서킷
- Qdrant gRPC 후보 색인 adapter (72시간 창, payload index)
- 임계값 2단계 판정(자동 병합 / 판정기 / 새 story)과 골드셋 회귀(realTest)
- Mongo 트랜잭션(기사·story CAS·outbox) 후 색인 upsert
- 주기 작업: 닫기(48h), 어린 OPEN story 병합, 색인 정리
- 병합·닫힘과 색인 사이 실패 창의 조립 자가 수리
- internal API: 조회·병합·분리·색인 재구축, outbox 조회·복구
- outbox relay의 Kafka 발행 (`story.article.attached`, `story.merged`)

## 조립 흐름

```text
Kafka collector.news.collected
  -> NewsCollectedKafkaListener
      -> AssembleStoryUseCase
          -> EmbeddingPort              -> TEI /embed (bge-m3)
          -> CandidateIndexPort.search  -> Qdrant gRPC (72h 창, top-10)
          -> StoryLinkJudge             -> TEI /rerank (회색 구간만)
          -> StoryAssemblyPersistencePort
              -> MongoDB story_articles + stories(version CAS) + story_outboxes (한 트랜잭션)
          -> CandidateIndexPort.upsert  -> Qdrant

StoryCloseScheduler · StoryMergeScheduler · IndexCleanupScheduler
  -> 각 Executor (실행 lock, ADR 036)
      -> 닫기 · 병합 · 색인 정리 유스케이스

StoryOutboxRelayScheduler
  -> StoryOutboxRelayExecutor
      -> RelayStoryOutboxUseCase
          -> StoryOutboxPublisherPort   -> Kafka story.article.attached / story.merged
```

판정은 후보 점수 `max(centroid 유사도, 최근 기사 5건과의 최대 유사도)`가 θ_high(0.70) 이상이면 자동 병합,
θ_low(0.60) 미만이면 새 story, 사이면 판정기 점수 θ_judge(0.30)가 정한다. 값의 근거는 ADR 033의 골드셋 측정이다.

소비 실패 중 기사 결함(payload 불량, 모르는 schemaVersion, TEI INPUT 귀속)만 DLT(`story.assembly.dlt`)로 가고,
나머지는 같은 offset을 backoff로 다시 처리한다. TEI·Qdrant 장애 동안 소비는 멈추고 lag로 쌓이며 유실은 없다.

## API

전부 internal이다. 형식(`ApiPaths`·`ApiResponse`·예외 처리)은 collector와 같다.

| 메서드·경로 | 동작 |
| --- | --- |
| `GET /api/v1/internal/stories?status=&from=&limit=` | story 목록. limit 기본 50 |
| `GET /api/v1/internal/stories/{storyId}` | story와 구성 기사·판정 기록 |
| `POST /api/v1/internal/stories/{storyId}/merge/{mergedStoryId}` | 운영자 병합. 앞이 뒤를 흡수한다 |
| `POST /api/v1/internal/stories/{storyId}/split` | body `newsIds[]`를 새 story로 분리. 전체 분리는 거부 |
| `POST /api/v1/internal/story-index/rebuild` | Mongo 임베딩으로 색인 재구축. started 또는 already-running |
| `GET /api/v1/internal/story-outboxes?status=&limit=` | outbox 조회. status 기본 DEAD |
| `POST /api/v1/internal/story-outboxes/{outboxId}/recover` | DEAD 행을 PENDING으로. 발행은 relay가 한다 |

병합·분리는 OPEN story만 받는다. 닫힌 story의 벡터는 색인에 없다는 불변식 때문이다(ADR 033).

## 이벤트 발행

조립·병합이 outbox 행을 같은 트랜잭션으로 기록하고, relay가 그 행을 Kafka로 내보낸다(ADR 015·022·033).
key는 storyId, value는 기록 시점의 계약 JSON이다. 계약 타입은 `story-contract` 모듈에 있다.

| eventType | topic | 계약 |
| --- | --- | --- |
| `ARTICLE_ATTACHED` | `story.article.attached` | `StoryArticleAttachedEvent` |
| `MERGED` | `story.merged` | `StoryMergedEvent` |

`story.article.attached`는 기사 내용과 붙은 뒤의 story 상태(keywords, articleCount)를 다 싣는다.
같은 story의 이벤트가 같은 파티션이라 순서가 지켜진다.
두 topic은 compaction 없이 delete 정책(`kachi.story.events.retention`, 기본 30일)이다. key가 storyId라 compaction이 걸리면 이전 attach 이벤트가 지워진다.
topic 선언은 `NewTopic` 빈으로 두고, 이미 있는 topic의 설정은 바꾸지 않는다.

스위치는 둘이고 뜻이 다르다.

| 키 | 뜻 | 기본값 |
| --- | --- | --- |
| `kachi.story.outbox.relay.enabled` | outbox를 읽어 발행 포트로 넘기는 relay | `true` |
| `kachi.story.events.enabled` | Kafka 발행 어댑터와 topic 선언 | `true` |

relay만 켜고 어댑터가 없으면 기동에 실패한다. test 프로파일은 둘 다 `false`다.

## Scheduler

주기 작업 셋과 relay가 각자 설정을 갖는다. scheduler 빈은 `enabled`로 막는다.

```yaml
kachi:
  story:
    jobs:
      close:
        interval: 10m
        close-after: 48h
      merge:
        interval: 10m
        scan-window: 24h
      cleanup:
        interval: 1h
    outbox:
      relay:
        fixed-delay: 5s
        batch-size: 50
        publishing-visibility-timeout: 60s
```

- 닫기: `lastArticleAt`이 `close-after` 지난 OPEN story를 닫고 색인에서 그 story의 벡터를 지운다.
- 병합: `openedAt`이 `scan-window` 안인 OPEN story끼리 centroid 유사도 ≥ θ_high면 합친다. 생존자는 먼저 연 쪽이다.
- 정리: 후보 창(`assembly.candidate-window`)을 지난 기사 벡터를 지운다. 보관 창 키는 조립의 것을 같이 읽는다.
- relay: PENDING claim -> 발행 -> finalize CAS. visibility timeout이 지난 PUBLISHING 행을 회수한다.

lock 범위는 주기 작업이 `CLUSTER`, relay가 `INSTANCE`다(ADR 036). 현재 구현은 in-memory라 단일 인스턴스 전제다.

## 저장소

자기 Mongo 데이터베이스 `kachi_story`다. 트랜잭션을 쓰므로 replica set 전제다(ADR 017).

| 컬렉션 | 인덱스 | 비고 |
| --- | --- | --- |
| `stories` | `{status, lastArticleAt}`, `{mergedInto}` | centroid는 double 배열 |
| `story_articles` | `{newsId}` unique, `{storyId, attachedAt desc}`, `{collectedAt}` | 임베딩은 float32 Binary |
| `story_outboxes` | collector와 같음 | |

Qdrant 컬렉션(`story-articles-bge-m3`)은 파생 캐시다. 점 id는 newsId, payload는 storyId·collectedAt·language이고
손상되면 `story-index/rebuild`로 Mongo의 임베딩에서 재구축한다. 임베딩 모델을 바꾸면 모델 code가 붙은 새 컬렉션을 만든다.

## 외부 연동

- **TEI 둘** (`infra/docker-compose.tei.yml`): 임베딩 `bge-m3`(8090), 판정기 `bge-reranker-v2-m3`(8091). HTTP `/embed`·`/rerank`만 부른다.
  기동 시 `/info`로 모델 일치를 검사해 부재·불일치는 기동 실패다. 기동 뒤 장애는 서킷이 소비를 멈춘다.
- **Qdrant** (`infra/docker-compose.qdrant.yml`): gRPC 6334. 공식 클라이언트 `io.qdrant:client`를 쓴다. 채택 근거 측정은 ADR 033.
- **Kafka**: `collector.news.collected` 소비, `story.*` 발행. poll당 레코드 10건 제한은 CPU 추론 속도에 맞춘 값이다.

user-service·다른 서비스의 API는 부르지 않는다.

## 실행

```sh
./scripts/infra.sh story start     # Mongo, Kafka, TEI 둘, Qdrant
./scripts/app.sh story-service start
```

TEI·Qdrant 주소는 `.env`의 `KACHI_STORY_TEI_EMBEDDING_BASE_URL`·`KACHI_STORY_TEI_RERANKER_BASE_URL`·`KACHI_STORY_QDRANT_HOST`·`KACHI_STORY_QDRANT_GRPC_PORT`로 바꾼다.
포트 배치는 `docs/포트-구성.md`, 통합 테스트 실행 조건은 `docs/테스트전략.md`를 따른다.

## 다음 작업

- ai-service가 `story.article.attached`를 소비해 story 단위로 요약한다(ADR 034 예정). 그때까지 발행된 이벤트는 소비자 없이 보존 기간 안에 쌓인다.
- 분리(`NEW_STORY`) 이벤트 발행과 Schema Registry 도입은 열린 결정이다(ADR 033).
