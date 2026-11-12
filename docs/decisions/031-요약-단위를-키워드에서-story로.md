# 031. 요약 단위를 키워드에서 story로

## 배경

kachi의 요약은 "키워드 × 10분 tick"이 단위다.
tick마다 키워드별로 구간 안의 기사를 모아 LLM에 넘기고, 요약 1건이 알림 1건이 된다(ADR 020·023·027).

2026-09-07 밤 기동에서 실데이터를 통해 이 단위의 결함이 드러났다.

- 같은 기사가 같은 키워드의 요약 두 개에 들어갔다(기사 153건, 인용 350건 중).
  - ADR 020은 "겹친 구간의 중복 요약은 ADR 011의 `newsHash`가 막는다"고 전제했는데, `newsHash`는 기사 묶음이 완전히 같을 때만 맞는다. 새 기사가 하나라도 들어오면 묶음이 달라져 다시 요약한다.
- 같은 사건(예: 엔비디아의 허깅페이스 인수)이 다른 기사로 여섯 번 요약되어 여섯 번 알림이 갔다.
- 미-이란 충돌 기사가 "이란"·"trump"·"호르무즈"에 각각 요약되어, 여러 키워드를 구독한 사용자는 같은 사건을 여러 통 받는다.

셋의 원인은 하나다.
작업 단위가 키워드인데 세상의 단위는 사건이고, 키워드 단위에서는 어떤 장치를 더해도 교차 키워드 중복과 사건 반복을 막지 못한다.  
예를들어 아래는 내용이 같다.
- 이란이 미국을 공격했다.
- 미국이 이란에게 공격당했다.
- 이란이 미국을 침공했다.

구글링 및 AI 와 해결을 위한 서칭 중,  
이 문제는 정보검색 분야가 Topic Detection and Tracking(TDT)이라는 이름으로 다뤄 온 것과 같은 틀이라는 것을 발견했다.   
NIST가 1998년부터 2004년까지 평가한 다섯 과제 중 First Story Detection(새 사건의 첫 보도인가), Link Detection(두 기사가 같은 사건인가), Topic Tracking(사건에 후속 기사 붙이기)이 여기 해당한다.
표준 해법은 기사를 벡터로 표현하고 도착 순서대로 기존 묶음과 최근접 비교를 해서 임계값 위면 붙이고 아니면 새 묶음을 만드는 single-pass 증분 클러스터링이다.

TDT가 남긴 결론도 함께 가져온다.

- First Story Detection은 가장 어려운 과제이고 오탐·미탐이 남는다. 오판을 전제로 병합·분리·재처리를 갖춰야 한다.
- 표현·검색·판정은 분리한다. 검색은 재현율을, 판정은 정밀도를 맡는다.
- 시간 창이 필수다. 오래된 묶음은 닫는다.

"후속 기사가 새 정보를 담는가"는 TDT 밖의 문제다.
update summarization(DUC 2008 update task, TREC Temporal Summarization) 계열이고, 요약 시점에 LLM이 답한다.

## 검토한 선택지

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| 키워드 단위를 유지하고 보정한다. 기요약 기사 제외, 최소 기사 수·최대 대기 시간 | 코드 변경이 작다 | 기사 재요약만 줄고 사건 반복과 교차 키워드 중복은 구조적으로 남는다. 키워드 5,000개면 LLM 호출 상한이 하루 720,000회다 |
| 기사를 사건으로 묶는 조립을 collector 안에 둔다 | 모듈이 늘지 않는다 | 저장 후 불변인 기사 사실과 병합·분리로 바뀌는 소속 추정이 한 aggregate에 섞인다. 임베딩 서빙 장애가 수집을 세운다. 재처리 경로를 따로 짜야 한다 |
| 조립을 새 bounded context `story-service`로 두고, 요약과 알림의 단위를 story로 바꾼다 | 사건마다 요약 한 번, 사용자마다 사건 한 버전에 한 통. 조립·요약·수집이 따로 확장되고 따로 죽는다 | 모듈 둘, topic 둘, 모델 서빙과 벡터 색인이 는다 |

세 번째를 택했다.
기준은 "기사 한 건만 보고 결정론적으로 나오는 속성은 collector, 기사들 사이의 관계를 모델과 상태로 추정하는 것은 story, 묶음을 읽고 쓰는 것은 ai"다.

## 결정

### 작업 단위는 story다

story는 같은 사건을 다루는 기사 묶음이다.
요약은 story의 버전이고, 알림의 멱등 키는 `storyId + version`이다.
키워드는 story에 붙는 라우팅 속성이며 구성 기사의 `matchedKeywords` 합집합이다.

TDT 논문은 기사 한 건을 story, 묶음을 topic이라 부른다.
이 프로젝트는 Google News·Apple News의 제품 관용을 따라 묶음을 story라 부르고, 용어사전에 그 뜻을 둔다.

### 중복은 세 층에서 다른 도구로 막는다

| 층 | 중복의 종류 | 도구 | 위치 |
| --- | --- | --- | --- |
| 같은 기사의 재수집 | URL만 다른 같은 페이지 | URL 정규화 + 해시 | collector (ADR 032) |
| 같은 사건의 다른 기사 | 전재, 다른 문장, 한/영 교차 | 임베딩 + 근접 후보 검색 + 판정기 | story-service (ADR 033) |
| 같은 사건의 반복 알림 | 전개마다, 키워드마다 한 통 | story 버전 + `developmentKind` + `storyId` 멱등 키 | ai-service, routing (ADR 034·035) |

### 모델은 네 종류이고 역할이 다르다

| 종류 | 입력 -> 출력 | 역할 | 선택 |
| --- | --- | --- | --- |
| 임베딩 모델 (bi-encoder) | 텍스트 1개 -> 벡터 1개 | 기사를 벡터로 바꿔 색인에 넣고 후보를 찾는다. 기사마다 한 번 계산하므로 수십만 건과 비교할 수 있다 | bge-m3 |
| 판정기 (cross-encoder, reranker) | 텍스트 2개 -> 점수 1개 | 후보 쌍이 같은 사건인지 채점한다. 두 텍스트를 함께 보므로 정밀하지만 쌍마다 계산한다 | bge-reranker-v2-m3 |
| 생성형 LLM | 프롬프트 -> 문장 | 요약문을 쓰고 새 전개인지 판단한다 | ADR 030의 후보 모델 |
| 벡터 저장소 | 벡터 -> 가까운 벡터 목록 | 모델이 만든 벡터를 저장하고 최근접을 찾는다. 모델이 아니다 | Qdrant |

임베딩 모델과 판정기는 TEI(Text Embeddings Inference)가 서빙하고 생성형 LLM은 ADR 030의 provider 층이 부른다.
로컬과 운영의 실행기가 다르면 토크나이저·정규화 차이로 유사도 값이 어긋나 임계값이 흔들리므로 실행기는 환경과 무관하게 TEI 하나다.

### 판정은 검색과 판정기 두 단계다

- 임베딩으로 최근 72시간 창에서 후보 top-k를 찾는다. 재현율을 맡는다.
- 최고 유사도가 θ_high 이상이면 자동 병합, θ_low 미만이면 새 story, 사이면 판정기가 정한다. 정밀도를 맡는다.
- 임계값은 감으로 정하지 않는다. 2026-09-07 기사로 만든 골드셋(사람이 "같은 사건인가"를 라벨링한 기사 쌍)에서 정밀도·재현율을 재고, 오탐(다른 사건 병합)이 미탐보다 나쁘므로 정밀도 우선으로 고른다.

임베딩 모델과 판정기의 교체를 전략 패턴으로 구현한다.

- 공통 계약은 `EmbeddingPort`와 `StoryLinkJudge` 포트, 전략은 모델 하나를 담당하는 adapter다.
- 선택 키는 ADR 030과 같은 방식의 enum(`EmbeddingModel`, `StoryJudge`)이고 yaml은 주소·시간만 갖는다.
- 어느 모델·판정기의 어느 버전이 어떤 점수로 판정했는지를 기사 소속 기록에 남긴다.

- 느슨한 결합: 조립 유스케이스는 모델이 무엇인지 모르고 포트 계약만 본다.
- 확장성: 새 임베딩 모델이나 LLM 판정기는 enum 상수와 adapter 하나다.
- 다형성: 같은 판정 호출이 설정에 따라 cross-encoder로도 LLM으로도 간다.

### 서비스 경계와 데이터 소유

| 서비스 | 정본 | 성격 |
| --- | --- | --- |
| collector | 기사 사실(제목·발췌문·URL·출처·시각·매칭 키워드) | 저장 후 불변, URL로 멱등 |
| story-service | 기사의 소속(어느 story), story의 상태 | 병합·분리·재처리로 바뀌는 추정 |
| ai-service | story의 요약 버전 | LLM 출력 |

story-service는 임베딩 입력과 재처리에 필요한 기사 값을 자기 저장소에 사본으로 둔다.
같은 기사가 두 컨텍스트에 있는 것은 bounded context의 정상 상태이고, 기사 사실에 대해 story-service는 정본이 되지 않는다.
기사에 새로 필요한 것이 생기면 collector 모델과 이벤트에 넣는다.

### 이벤트는 상태를 싣고, 재처리는 로그를 다시 읽는 것이다

- `collector.news.collected`는 기사 내용을 다 싣는다. 소비자는 발행자의 DB도 API도 부르지 않는다(Event-Carried State Transfer).
- topic은 newsId 키 compaction으로 기사당 최신 1건을 보존한다. 기사는 불변이라 compaction이 곧 전체 이력이다.
- 모델·임계값을 바꿀 때는 새 소비자 그룹이 처음부터 다시 읽어 새 컬렉션과 새 색인에 쓰고, 따라잡으면 전환한다. 실시간 코드와 재처리 코드가 하나다.
- 두 서비스가 서로의 DB를 읽거나 raw 컬렉션을 CDC로 복제하지 않는다. 계약 없는 DB 스키마 결합이 된다.

### 벡터 색인은 파생 캐시다

- 원본은 story-service의 Mongo다. 임베딩도 Mongo에 둔다.
- 쓰기는 Mongo 트랜잭션(소속·story·outbox) 먼저, 색인 upsert 다음이다. 색인 쓰기가 실패하면 offset을 커밋하지 않고 재전달에서 색인만 다시 쓴다.
- 반대 순서면 소속 없는 벡터가 후보로 잡혀 판정 경로마다 예외가 생긴다.
- 색인이 손상되면 Mongo의 임베딩으로 재구축한다.

### 규모 전제

| 항목 | 가정 |
| --- | --- |
| 사용자 · canonical 키워드 · 구독 | 50,000 · 5,000 · 200,000 |
| 수집 기사 | 300,000건/일, 피크 10배 |
| 벡터 보관 창 · story 닫힘 · Kafka 보존 | 72시간 · 48시간 · 30일 |

| 항목 | 키워드 × tick | story |
| --- | --- | --- |
| LLM 호출 | 상한 720,000회/일 | story 버전 수, 약 40,000~80,000회/일 |
| 교차 키워드 중복 | 못 막는다 | 멱등 키에 storyId가 있어 0 |
| 임베딩 · 후보 검색 | 없음 | 각 300,000회/일. GPU 1장, Qdrant 여유 |
| 벡터 보관 | 없음 | 900,000 × 1024차원 × 4B ≈ 3.7GB, int8 양자화 시 약 1GB |

## 검토한 대안

- **SimHash로 어휘 근접 중복을 collector에서 먼저 걷어내기**: 
  - Manku(2007)의 64비트·Hamming 3은 웹 페이지 전문이 대상이다.
  - 제목과 발췌문 20~40 단어에서는 "2분기"와 "1분기" 한 단어 차이가 지문에 반영되지 않아 다른 사건이 사본으로 묶이고, 사본 경로는 판정기를 건너뛰어 2차 방어가 없다.
  - 아끼는 것은 하루 GPU 10여 분이다.
- **TF-IDF 희소 벡터**: 
  - TDT 시기의 표현이다. 한/영 교차와 바꿔 쓴 문장을 못 묶는다.
- **개체명·시간 조합(GDELT 방식)**: 
  - NER 모델이 따로 필요하고 개체명이 같은 다른 사건에 취약하다.
- **LLM을 기본 판정기로**: 
  - 회색 구간이 20%만 돼도 하루 60,000회 호출이다. 판정기 포트의 구현 하나로 남긴다.
- **프로세스 안 HNSW**: 
  - 72시간 창은 한 프로세스에 들어가지만 인스턴스마다 색인이 달라 다른 후보를 본다. 단일 인스턴스 전제에서만 맞다.
- **Redis 8 벡터 검색**: 
  - 이미 스택에 있지만 벡터가 전부 메모리라 창이 커지면 비용이 그대로 는다.
- **OpenSearch 하이브리드 검색**: 
  - 개체명 어휘 일치가 후보 재현율을 올리지만 over-merge도 키운다. 판정기가 있으면 이점이 줄고 클러스터 운영이 무겁다.
- **Schema Registry**: 
  - 계약 호환성 검사를 자동화하지만 기존 계약 모듈 둘까지 옮겨야 효과가 있다. 별도 결정이다.
- **Debezium으로 relay 대체**: 
  - 지연과 폴링 부하의 운영 최적화이지 구조 결정이 아니다. 별도 결정이다.

## 트레이드오프

- 장점:
  - 사건마다 요약 한 번, 수신자마다 사건 한 버전에 한 통이다.
  - 조립·요약·수집이 따로 확장되고 따로 죽는다. 임베딩 서빙이 죽어도 수집은 계속되고 기사는 Kafka에 쌓인다.
  - 모델과 임계값을 바꿔도 로그를 처음부터 다시 읽는 것 하나로 재처리한다.
- 단점:
  - 모듈 둘(`story-service`, `story-contract`)과 계약 모듈 하나(`collector-contract`), topic 둘, 컴포넌트 둘(TEI, Qdrant)이 는다.
  - 오판이 남는다. 다른 사건이 한 story에 묶이면 요약 입력이 섞이고 keywords 합집합이 오염되어 엉뚱한 구독자에게 간다.
    - 병합·분리 운영 API와 LLM의 `NEW_STORY` 판정이 2차 방어다.
  - 기사 내용이 collector와 story-service 두 곳에 있다.

## 운영 제약

- TEI와 Qdrant가 없으면 story-service는 소비를 멈추고 lag가 쌓인다. 유실은 없다.
- 임베딩 모델을 바꾸면 벡터 컬렉션 이름에 모델 코드를 붙여 새로 만든다. 옛 컬렉션은 전환 뒤 지운다.
- 골드셋은 회귀 기준이다. 모델·임계값 변경은 골드셋 측정 결과와 함께 기록한다.

## 후속

- ADR 032: collector 기사 이벤트 발행과 `collector-contract`. 발췌문, URL 정규화, compaction.
- ADR 033: story-service 조립. 도메인, 포트, 판정 규칙, 주기 작업, 골드셋 결과.
- ADR 034: story 요약. 트리거 정책, `developmentKind`, `StorySummary` 멱등.
- ADR 035: notification-routing의 story 요약 소비와 storyId 멱등. 구독 수신자 페이지 조회와 진행 커서, 수신자별 본문, story 격리 알림, 키워드 요약 경로와 그 조회 API 폐지.
- 개정: ADR 011(newsHash 폐지), 020(window·watermark 절 폐지, 실행 모델과 격리는 유지), 023·025·027(계약과 멱등 키).
- 수신자별 발송 빈도(즉시·시간·일 단위 묶음)는 별도 결정이다.

## 출처

- NIST, [Topic Detection and Tracking Evaluation Overview](https://www.nist.gov/publications/topic-detection-and-tracking-evaluation-overview)
  - "five TDT research tasks: Topic Tracking, Link Detection, Topic Detection, First Story Detection, and Story Segmentation"
- Jay Kreps, [Questioning the Lambda Architecture](https://www.oreilly.com/radar/questioning-the-lambda-architecture/) (O'Reilly Radar, 2014)
  - "When you want to do the reprocessing, start a second instance of your stream processing job that starts processing from the beginning of the retained data, but direct this output data to a new output table."
  - "When the second job has caught up, switch the application to read from the new table."
- Martin Fowler, [What do you mean by "Event-Driven"?](https://martinfowler.com/articles/201701-event-driven.html) (2017)
  - Event-Carried State Transfer: "This pattern shows up when you want to update clients of a system in such a way that they don't need to contact the source system in order to do further work."
  - "An obvious down-side of this pattern is that there's lots of data schlepped around and lots of copies."
- Debezium, [Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
  - 서비스 간 연동에는 raw 테이블 변경이 아니라 outbox 행을 내보내는 것을 안내한다. outbox 행은 계약 JSON이라 DB 스키마가 소비자에게 새지 않는다.
- BAAI, [bge-m3 model card](https://huggingface.co/BAAI/bge-m3)
  - License MIT. "It can support more than 100 working languages." 입력 8192 토큰, dense 1024차원.
- BAAI, [bge-reranker-v2-m3 model card](https://huggingface.co/BAAI/bge-reranker-v2-m3)
  - query·passage 쌍을 입력받아 관련도 점수 하나를 내는 cross-encoder. TEI의 `/rerank`로 서빙한다.
- Hugging Face, [Text Embeddings Inference](https://huggingface.co/docs/text-embeddings-inference/index)
  - "Text Embeddings Inference (TEI) is a comprehensive toolkit designed for efficient deployment and serving of open source text embeddings models."
- Qdrant, [Scalar Quantization](https://qdrant.tech/articles/scalar-quantization/), [Quantization](https://qdrant.tech/documentation/manage-data/quantization/)
  - float32를 int8로 바꿔 벡터 메모리를 75% 줄인다. payload 필드에 인덱스를 두면 시간·언어 필터가 전수 조회로 떨어지지 않는다.
- Manku, Jain, Das Sarma, [Detecting Near-Duplicates for Web Crawling](https://research.google.com/pubs/archive/33026.pdf) (WWW 2007)
  - 80억 웹 페이지 저장소에서 64비트 simhash와 k=3이 적절함을 실험으로 보였다. 대상이 전문이라 짧은 제목에는 이 결과가 옮겨지지 않는다.
