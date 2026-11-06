# 033. story-service 조립

## 배경

ADR 031은 요약 단위를 키워드에서 story로 바꾸고, 기사를 사건으로 묶는 조립을 새 bounded context에 두기로 했다.
이 ADR은 그 조립을 맡는 story-service의 결정을 담는다.
`collector.news.collected`(ADR 032)를 소비해 기사를 story에 붙이고, `story.article.attached`·`story.merged`를 발행한다.

판정의 큰 틀(임베딩 후보 검색 + 판정기 2단계, 정밀도 우선)은 ADR 031이 정했다.
여기서 정하는 것은 그 틀의 구체값과 도메인·저장·주기 작업·발행 경로다.

## 검토한 선택지

임베딩·판정기 실행기와 벡터 저장소 접속 방식이다. 판정 구조 자체의 대안은 ADR 031 대안 절에 있다.

| 선택 | 장점                                        | 단점 |
| --- |-------------------------------------------| --- |
| DJL로 모델을 프로세스 안에서 실행 | 컨테이너가 없다                                  | 실행기가 환경마다 갈라져 토크나이저·정규화 차이로 유사도 값이 어긋난다. ADR 031의 "실행기는 하나"를 어긴다 |
| TEI 컨테이너 둘(임베딩·판정기) | 로컬과 운영이 같은 실행기다. HTTP `/embed`·`/rerank`만 부른다 | 컨테이너 둘에 메모리 6.4GB가 든다. arm64는 release 태그가 없어 커밋 SHA 태그로 고정한다 |
| Qdrant REST(공식 클라이언트 없음) | HTTP라 의존성이 가볍다                            | DTO를 직접 쓰고 API 변경을 우리가 따라간다. 1024차원 벡터가 JSON으로 protobuf의 3배다 |
| Qdrant gRPC(`io.qdrant:client`) | 공식 클라이언트. upsert가 REST의 3배 처리량이다(아래 측정)   | grpc-netty-shaded 의존이 는다 |

실행기는 TEI, 접속은 gRPC를 택했다.

gRPC 채택의 근거는 같은 Qdrant 컨테이너에 같은 부하(upsert 20,000점 batch 100, search 2,000회 top-10)를 준 측정이다. 반복 3회의 중간값이다.

| 작업 | 전송 | p50(ms) | p95(ms) | 초당 | 요청 본문(MB) |
| --- | --- | --- | --- | --- | --- |
| upsert | gRPC | 11.37 | 26.00 | 72.0 | 81.0 |
| upsert | REST | 32.42 | 60.44 | 26.4 | 245.3 |
| search | gRPC | 8.73 | 17.30 | 102.7 | 8.0 |
| search | REST | 8.84 | 21.35 | 94.7 | 24.5 |

search 지연은 차이가 없고 upsert에서 직렬화 크기(protobuf 4KB 대 JSON 약 10KB)가 그대로 처리량 차이가 된다.
같은 호스트 측정이라 네트워크 지연 차이는 담기지 않았다.
`collectedAt` payload index가 search p95를 26.16ms에서 17.30ms로 줄여, 운영 컬렉션은 payload index(storyId·collectedAt·language)를 만든다.

## 결정

### 도메인: story는 상태, 기사는 사본

- `Story`는 상태(OPEN·CLOSED), centroid 임베딩, 기사 수, keywords 합집합, 시각들, 계보(`parentStoryId`·`mergedInto`), version을 갖는 불변 aggregate다. 전이마다 version이 1 오르고 저장은 version CAS다(ADR 024의 방식).
- `StoryArticle`은 기사 값의 사본과 소속(storyId), 판정 기록(`LinkDecision`)이다. 기사 사실의 정본은 collector이므로 제목·발췌문에 non-blank 외의 규칙을 되풀이하지 않는다(ADR 031의 데이터 소유).
- `LinkDecision`은 자동 병합·판정기 판정·새 story 세 경우가 sealed 타입이다. 새 story에도 최고 후보와 점수를 남겨 미탐 분석에 쓴다.
- centroid는 정규화하지 않은 산술 평균이다. 평균은 `(mean·n + e)/(n+1)`로 정확히 갱신되고 코사인은 크기를 무시한다.
  - 정규화 평균을 저장하면 합의 크기를 잃어 증분 갱신이 근사가 된다.
- story의 닫힘 기준 시각 `lastArticleAt`은 기사의 `publishedAt`이다.
  - 48시간 닫힘은 사건의 시간이고, 처리 시각으로 두면 재처리에서 옛 story가 전부 다시 열린 채 남는다. 후보 창은 색인 비용의 문제라 `collectedAt`이다.
- 임베딩 모델과 판정기는 ADR 030의 LLM 모델처럼 enum(`EmbeddingModel`, `StoryJudge`)이 정체를 갖고 yaml은 주소·시간만 갖는다. `THRESHOLD_ONLY` 판정기는 검색 점수를 그대로 돌려줘, "코사인만" 구성이 설정 한 줄로 재현된다.

### 판정 규칙과 임계값

기사 하나의 조립은 후보 검색 -> 점수 -> 판정 -> 저장 순이다.

- 후보는 72시간 창에서 top-10이고, 후보 story의 점수는 `max(centroid 유사도, 그 story의 최근 기사 5건과의 최대 유사도)`다. centroid만 쓰면 큰 story의 평균에 묻혀 최신 전개와의 근접이 사라진다.
- 점수 ≥ θ_high면 자동 병합, θ_low 미만이면 새 story, 사이면 회색 후보 전부를 판정기에 넣고 최고 점수가 θ_judge 이상일 때만 병합한다. 판정기 입력은 최대 유사도를 낸 그 기사다.
- 병합 대상은 OPEN이고 기사 수 상한(500) 아래여야 한다. 닫힘 직후의 후보가 θ_high 이상이면 병합 대신 새 story의 `parentStoryId`로 계보만 잇는다.

임계값은 골드셋으로 정했다. 2026-09-07 실기동 기사에서 뽑은 250쌍(같은 사건 38쌍)을 사람이 라벨링하고, bge-m3 유사도와 bge-reranker-v2-m3 점수를 전 쌍에 계산해 조합별 정밀도·재현율을 쟀다.

| θ_high | θ_low | θ_judge | 정밀도 | 재현율 | 회색 구간 |
| --- | --- | --- | --- | --- | --- |
| 0.70 | 0.60 | 0.30 | 0.969 | 0.816 | 5% |

오탐(다른 사건 병합)이 미탐(같은 사건 분리)보다 사용자에게 나쁘므로 정밀도 우선으로 골랐다(ADR 031).
미탐 7건 중 6건은 발췌문 없이 제목만 있는 쌍이었고 발췌문 있는 쌍의 재현율은 1.0이다. 발췌문 수집(ADR 032)이 이 재현율의 전제다.
골드셋은 `story-service/src/realTest/resources/goldset/`의 회귀 기준으로 남아, 모델·임계값 변경은 이 측정과 함께 기록한다.

### 이중 쓰기 순서: Mongo 먼저, 색인 다음

- 조립의 저장은 Mongo 트랜잭션(기사 insert, story version CAS update, outbox insert) 하나가 먼저고, 커밋 뒤 Qdrant upsert다. 색인 쓰기가 실패하면 offset을 커밋하지 않고 재전달에서 색인만 다시 쓴다(ADR 031의 순서 결정).
- 재전달·재발행은 `newsId` unique가 거른다. 이미 있는 기사는 색인만 upsert하고 끝난다.
- version CAS가 실패하면 후보 검색부터 다시 하고, 임베딩은 다시 계산하지 않는다. 상한을 넘기면 실패로 두고 재전달에 맡긴다.
- 소비자는 기사 결함(payload 불량, 모르는 schemaVersion, 입력 귀속 추론 실패)만 DLT로 보내고 나머지는 같은 offset을 backoff로 다시 처리한다.
  - 기사를 DLT로 보내면 그 기사는 story에서 영영 빠진다. 알림 경로의 "3회 뒤 DLT"(ADR 027)는 requestId 멱등이 재발송을 막아주기에 성립하는 규칙이라 여기 옮기지 않는다.

### 주기 작업: 닫기·병합·정리

- 닫기: `lastArticleAt`이 48시간 지난 OPEN story를 CLOSED로 바꾸고 색인에서 그 story의 벡터를 지운다. 닫힌 뒤 온 같은 사건의 기사는 새 story가 되고 `parentStoryId`로 잇는다.
- 병합: 어린 OPEN story끼리 centroid 유사도 ≥ θ_high면 합친다.
  - 동시 생성 경합(같은 사건의 첫 기사 둘이 동시에 와 story가 둘 생김)과 Mongo 커밋·색인 upsert 사이 창을 흡수하는 작업이라, 스캔은 `openedAt`이 창 안인 story만 본다. 틱 비용이 유입 속도에 비례하고 누적 story 수와 무관하다.
  - 생존자는 먼저 연 쪽이다(동률이면 StoryId 문자열이 작은 쪽). `openedAt`은 병합 시점에 변하지 않아 어느 인스턴스가 언제 계산해도 같은 답이고, `parentStoryId` 계보의 방향과 일치한다.
  - 흡수된 쪽은 `mergedInto`를 남기고 CLOSED가 되며, 기사 reassign·두 story CAS·`StoryMergedEvent` outbox insert가 한 트랜잭션이다.
- 정리: 후보 창을 지난 기사 벡터를 색인에서 지운다.
  - 보관 창 키는 조립의 후보 창 설정을 같이 읽는다. 키가 둘이면 정리 창이 후보 창보다 짧아져 후보 검색이 조용히 깨지는 갈라짐이 가능해진다.
- 색인의 낡은 storyId payload는 읽는 쪽이 고친다. 조립이 후보를 읽었는데 CLOSED이고 `mergedInto`가 있으면 살아남은 story로 치환하고 그 자리에서 색인 payload를 고쳐, 병합 커밋과 색인 이전 사이에 무엇이 끼어도 실패 창이 스스로 닫힌다.

실행 모델은 scheduler -> executor(실행 lock) -> use case다(ADR 008·020). lock 범위는 주기 작업 넷이 `CLUSTER`, outbox relay가 `INSTANCE`다(ADR 036).

### 이벤트 발행

- outbox 구조와 상태 전이(PENDING -> PUBLISHING -> PUBLISHED|DEAD, claim CAS, stale 회수, backoff 재시도)는 ADR 015·022를 collector(ADR 032)와 같은 형태로 갖는다. payload는 기록 시점에 계약 JSON으로 고정된다.
- topic은 `story.article.attached`(기사가 붙은 뒤의 story 상태와 기사 내용)와 `story.merged`(생존·흡수 storyId) 둘이고 key는 storyId다. 같은 story의 이벤트가 같은 파티션이라 순서가 지켜지고, 기사 내용을 실어 ai-service가 collector도 story-service도 조회하지 않는다(ADR 031).
- 두 topic은 compaction 없이 `cleanup.policy=delete`(retention 30일)다.
  - key가 storyId라 한 story의 attach 이벤트 여러 건이 같은 key이고, compaction이 걸리면 이전 attach 이벤트가 지워진다. `collector.news.collected`의 compact+delete는 key(newsId)가 레코드당 유일해서 성립한 것이다.
- producer timeout 역산(발행 시도가 visibility timeout 안에 끝나야 stale 회수와 겹치지 않는다)은 ADR 023·032와 같다.

### 운영 API는 OPEN만 만진다

- 조회(목록·상세), 운영자 병합·분리, 색인 재구축, outbox 조회·복구를 internal API로 연다. 오판 병합·분리는 ADR 031이 예고한 2차 방어다.
- 병합·분리는 OPEN story만 받는다. 닫힌 story의 벡터는 색인에 없다는 불변식이 있어, CLOSED를 만지면 색인을 되살리는 경로가 생긴다.
- 재구축은 Mongo의 임베딩으로 색인 전체를 다시 만든다(색인은 파생 캐시, ADR 031). 분 단위 작업이라 요청 밖 실행 lock 아래에서 돌고 API는 시작 여부만 돌려준다.

### 부재는 기동 실패, 장애는 소비 중단

- TEI·Qdrant가 없거나 구성이 다르면 기동에 실패한다. 기동 뒤 장애는 health와 서킷이 맡고 재기동은 오케스트레이터가 한다.
- TEI는 서킷만 둔다(hold·cooldown 없음, ADR 030과 다른 점). 429에 Retry-After가 없고 구성 불일치는 기동 probe가 잡는다. Qdrant는 Mongo와 같은 취급으로 서킷이 없다.
- 실행기 장애 동안 소비는 같은 offset에서 멈추고 lag로 쌓인다. 유실은 없다(ADR 031 운영 제약).

## 검토한 대안

- **Qdrant 벡터 양자화**: rebuild로 되돌릴 수 있는 결정이고 로컬에서 효과를 못 잰다. float32 무양자화로 시작하고 운영 메모리 측정 뒤 판단한다.
- **판정기 상수에 LLM 유지**: 구현 없는 enum 상수는 config에 미지원 분기를 만든다(ADR 030). 지웠고, 필요 시 adapter 하나로 돌아온다.
- **병합 job의 전수 스캔**: 창 밖에서 뒤늦게 가까워진 쌍도 잡지만 틱 비용이 누적 story 수에 비례한다. 창 밖 쌍은 다음 기사가 들어올 때 조립 후보로 다시 만나므로, 남는 것은 재현율 문제이지 정합성 문제가 아니다.
- **분리·닫힘 이벤트 발행**: 소비자가 없어 계약을 정할 근거가 없다. `NEW_STORY` 분리 이벤트의 소비 시점은 열린 항목이다.

## 트레이드오프

- 장점: 판정의 모든 상수(θ 셋, 창, 상한)가 측정에서 나왔고 골드셋 회귀로 지켜진다. 색인·병합·닫힘의 실패 창이 전부 자가 수리 경로를 갖는다.
- 단점:
  - TEI 둘·Qdrant가 로컬 기동 메모리 7GB대의 대부분을 차지한다.
  - 판정 오판은 남으며(정밀도 0.969) 운영자 병합·분리와 ADR 034의 `NEW_STORY` 판정이 방어선이다.
  - CPU 추론은 기사당 수 초라 소비 속도가 TEI에 묶인다.

## 운영 제약

- 실기동 측정(2026-09-15, arm64 CPU TEI): 기사 1,528건이 story 268개로 조립됐다(자동 병합 1,140, 판정기 병합 120, 판정기 거부 107, 후보 없음·θ_low 미만 161). 기사 간격 p50 0.5초, p95 3.0초. outbox 1,529건 발행에 188초, 재기동 후 재발행 0건.
- poll 한 번의 레코드 수를 10으로 제한한다. CPU 추론 속도에서 기본값 500이면 한 poll이 `max.poll.interval.ms`를 넘겨 consumer가 그룹에서 빠진다(실기동에서 확인).
- 임베딩 모델을 바꾸면 컬렉션을 새로 만들고 로그를 다시 읽는다(ADR 031). centroid의 모델이 다른 story는 후보에서 빠지므로 전환 중에도 다른 모델의 벡터가 섞이지 않는다.

## 후속

- ADR 034: ai-service가 `story.article.attached`·`story.merged`를 소비해 story를 요약한다.
- `NEW_STORY` 분리 이벤트의 소비 시점, Schema Registry 도입 위치는 열린 항목이다.
- 메트릭은 collector·ai와 함께 별도 결정으로 다룬다(ADR 019 이후).

## 출처

- NIST, [Topic Detection and Tracking Evaluation Overview](https://www.nist.gov/publications/topic-detection-and-tracking-evaluation-overview)
  - 판정 틀의 근거는 ADR 031 출처 절에 있다.
- Qdrant, [gRPC Interface](https://qdrant.tech/documentation/interfaces/#grpc-interface)
  - "gRPC methods follow the same principles as REST. For each REST endpoint, there is a corresponding gRPC method." 공식 Java 클라이언트는 gRPC 기반 `io.qdrant:client`다.
- Qdrant, [Indexing](https://qdrant.tech/documentation/concepts/indexing/)
  - payload index가 필터 조건의 전수 조회를 막는다. `collectedAt` index의 효과는 위 측정 표와 같다.
- Apache Kafka, [Topic Configs](https://kafka.apache.org/documentation/#topicconfigs)
  - `cleanup.policy=compact`는 key당 최신 1건만 남긴다. attach 이벤트처럼 같은 key가 반복되는 topic에 걸면 이전 이벤트가 지워진다.
- BAAI, [bge-m3](https://huggingface.co/BAAI/bge-m3) · [bge-reranker-v2-m3](https://huggingface.co/BAAI/bge-reranker-v2-m3) model cards
  - 임베딩 1024차원, 판정기는 쌍 입력 점수 출력. 선택 근거는 ADR 031이고 임계값 측정은 이 두 모델로 했다.
