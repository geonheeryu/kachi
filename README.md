# Kachi

> 관심 키워드 기반 뉴스 수집, AI 요약, 알림 발송 서비스

---

## 1. 프로젝트 개요

Kachi는 사용자가 등록한 관심 키워드를 기준으로 뉴스와 시장 데이터를 수집하고, AI로 키워드를 확장하거나 뉴스를 요약한 뒤, Slack/Discord/Telegram 같은 외부 채널로 알림을 발송하는 서비스다.

### 구현 체크리스트

- [x] 멀티모듈 프로젝트
- [x] 도메인 문서 초안
- [x] 헥사고날 아키텍처 패키지 규칙 정리
- [x] `user-service` 기본 기능
- [x] `collector-service` 뉴스 수집 기본 기능
- [x] `story-service` 기사 story 조립 기본 기능
- [ ] `ai-service`
- [x] `notification-service` 알림 요청/outbox/worker dispatch 기본 흐름
- [ ] `history-service`

---

## 2. 기술 스택

| 영역 | 기술 | 버전 |
| --- | --- | --- |
| Language | Kotlin | 2.2.21 |
| Language | Java | 21 |
| Framework | Spring Boot | 4.0.6 |
| Build | Gradle Kotlin DSL | - |
| Database | MySQL, MongoDB | - |
| Cache / Messaging | Redis, Kafka | - |
| Architecture | Hexagonal Architecture, Domain Model Pattern | - |

세부 기술 선택과 트레이드오프는 [문서](./docs)를 기준으로 관리한다.

---

## 3. 서비스 구조

| 서비스 | 언어 | 프레임워크 | DB | 역할 |
| --- | --- | --- | --- | --- |
| `user-service` | Kotlin | Spring MVC | MySQL | 사용자 인증, 사용자 상태, 키워드 구독, 채널 바인딩 관리 |
| `collector-service` | Kotlin | WebFlux | MongoDB | 뉴스/시장 데이터 수집 |
| `story-service` | Kotlin | WebFlux | MongoDB, Qdrant | 기사를 같은 사건 단위 story로 조립, story 이벤트 발행 |
| `ai-service` | Kotlin | WebFlux | MongoDB | 키워드 확장, 뉴스 요약 |
| `notification-service` | Kotlin | WebFlux | MongoDB, Redis, Kafka | 알림 요청 접수, outbox 발행, Slack/Discord/Telegram 발송 |
| `history-service` | Java | Spring Batch | MySQL | 사용자 활동/알림/요약 이력 적재 및 통계 집계 |

---

## 4. 데이터 흐름

```text
user-service
  └─ 키워드 구독·채널 바인딩 등록/관리
      -> collector-service: 활성 키워드 조회(HTTP internal) 후 뉴스 수집, `collector.news.collected` 발행
          -> story-service: 기사를 같은 사건 단위 story로 조립, `story.article.attached`·`story.merged` 발행
              -> ai-service: story 이벤트를 받아 story 단위로 요약 버전 생성
          -> ai-service: 수집 뉴스를 키워드별로 LLM 요약 (newsHash 중복 방지), `ai.summary.created`·`ai.keyword.quarantined` 발행
              -> notification-routing: ai 이벤트를 구독자·관리자 x 채널로 fan-out해 `notification.requested` 발행
              -> notification-service: 알림 접수, outbox 발행
                  -> notification-worker: Slack/Discord/Telegram 발송
                      -> history-service: 이력 적재 / 통계 집계 (미구현)
```

서비스 간 경계와 전체 데이터 흐름은 [도메인모델.md의 컨텍스트 맵](./docs/도메인모델.md)을
기준으로 관리한다. 키워드 확장(ai)은 설계만 존재하고 비활성 상태다.

---

## 5. 현재 진행 상태

현재는 `user-service`, `collector-service`, `story-service`, `ai-service`, `notification-service`의 기본 기능을 구현 중이다.

도메인 세부 규칙은 [도메인 모델](./docs/도메인모델.md)을 기준으로 관리한다.  
설계 결정의 배경과 trade-off는 [decisions](./docs/decisions)에 기록한다.

| 서비스 | 진행 상태 | 상세 문서 |
| --- | --- | --- |
| `user-service` | 사용자, canonical 키워드·구독, 채널 바인딩(암호화 주소, Telegram 연결 링크), 활성 키워드·구독·바인딩 internal API, OAuth2/JWT, refresh token, MySQL/Redis 저장소 구현 | [user-service README](./user-service/README.md) |
| `collector-service` | 뉴스 도메인, Google/Naver/Finnhub provider, user-service 키워드 조회, MongoDB 저장, scheduler/internal API 실행 진입점, 기사 outbox 기록과 relay의 `collector.news.collected` 발행(`collector-contract` 모듈) 구현 | [collector-service README](./collector-service/README.md) |
| `story-service` | story·기사 사본·판정 기록 도메인, TEI 임베딩·판정기 adapter와 서킷, Qdrant gRPC 후보 색인, `collector.news.collected` 소비와 2단계 판정 조립, 닫기·병합·색인 정리 주기 작업, 조회·병합·분리·색인 재구축·outbox 운영 internal API, outbox relay의 `story.article.attached`/`story.merged` 발행(`story-contract` 모듈) 구현 | [story-service README](./story-service/README.md) |
| `ai-service` | 뉴스 요약 실행 구현: 키워드별 LLM 요약, newsHash 중복 방지, AiRun 실행 기록, 용도별 후보 모델(enum)과 모델 단위 서킷·cooldown·hold, 순차 failover, 실패의 책임·지속 분류와 전 후보 합의 격리, 코드에 둔 프롬프트 버전, `src/realTest` 실호출 검증, MongoDB 저장, 요약/격리 이벤트 outbox 기록과 relay, `ai.summary.created`/`ai.keyword.quarantined` Kafka 발행(`ai-contract` 모듈), scheduler/internal API 진입점, 격리·watermark·outbox 운영 internal API, LLM 모델 상태·reset·probe internal API. story 요약 구현: `story.article.attached`/`story.merged` 소비, story·기사 사본, `(storyId, version)` 요약 버전, story 격리, story 요약·격리 internal API | [ai-service README](./ai-service/README.md) |
| `notification-service` | notification-routing/core/service/worker/contract 모듈 구성, `ai.summary.created`·`ai.keyword.quarantined` 소비와 구독자 x 채널 fan-out(`notification.requested` 발행, RoutingJob 멱등), 요청 접수, MongoDB outbox, Kafka dispatch 발행, worker dispatch, mock/Slack/Discord/Telegram sender, retry/DLT 영속화와 운영 조회/폐기, stale PUBLISHING/PROCESSING 회수, DEAD 운영 조회/수동 복구 구현 | [notification 종합 문서](./docs/README-notification.md) |
| `history-service` | 미구현 | - |

---

## 6. 실행 / 검증

로컬 인프라 실행:

```sh
docker compose -f infra/docker-compose.yml -f infra/docker-compose.mysql.yml -f infra/docker-compose.redis.yml -f infra/docker-compose.mongo.yml -f infra/docker-compose.kafka.yml up -d
```

notification 운영 모니터링 스택까지 함께 실행:

```sh
docker compose -f infra/docker-compose.yml -f infra/docker-compose.mysql.yml -f infra/docker-compose.redis.yml -f infra/docker-compose.mongo.yml -f infra/docker-compose.kafka.yml -f infra/docker-compose.observability.yml up -d
```

모든 compose 파일은 `infra/docker-compose.yml`의 `kachi` project로 묶인다.
`docker compose -p kachi ps`로 전체 상태를 한 번에 확인하고, 조합한 파일에 `down`을 주면 함께 정리된다.

Grafana는 `http://localhost:3000`, Prometheus는 `http://localhost:9094`에서 확인한다.
notification metric은 `notification-routing`, `notification-service`, `notification-worker`의 `/actuator/prometheus`를 Prometheus가 scrape한다.

설정은 두 층으로 나뉜다. `application.yaml`은 환경과 무관한 동작 정의와 함께
secret·서비스 간 URL·인스턴스 식별자를 `${ENV}` 형태로 요구하고, `application-local.yaml`은
인프라 접속 주소와 로컬 더미 값을 덮어쓴다. 그래서 `local`은 환경변수 없이도 뜨고,
다른 프로파일은 값이 빠지면 기동 시점에 실패한다. API 키·웹훅 URL 같은 secret만 env 파일에 둔다.

```
.env.example    필요한 키 목록. 값은 비어 있고 스크립트가 읽지 않는다.
.env            모든 프로파일 공통 secret (git 제외, 선택)
.env.<profile>  프로파일별 secret (git 제외). local 프로파일은 .env.local
```

```sh
cp .env.example .env.local
```

실행 스크립트는 `SPRING_PROFILES_ACTIVE`(기본 `local`)에 맞는 `.env.<profile>`과 `.env`를 읽는다.
우선순위는 shell에 export한 값, `.env.<profile>`, `.env` 순이다.

```sh
./scripts/app.sh user-service start                        # local, .env.local
SPRING_PROFILES_ACTIVE=dev ./scripts/app.sh user-service start   # dev, .env.dev
```

애플리케이션을 백그라운드에서 실행할 때는 루트의 실행 스크립트를 사용한다.
스크립트는 env 파일을 읽은 뒤 Gradle `bootRun`을 백그라운드에서 실행한다.

```sh
./scripts/infra.sh core start

./scripts/app.sh user-service start
./scripts/app.sh user-service status
./scripts/app.sh user-service logs
./scripts/app.sh user-service stop
```

전체 애플리케이션도 한 번에 관리할 수 있다.

```sh
./scripts/app.sh all start
./scripts/app.sh all status
./scripts/app.sh all stop
```

전체 사이클을 한 번에 띄워 실제 LLM·채널로 확인할 때는 `cycle.sh`를 쓴다.

- 인프라 -> Mongo index -> user-service -> 나머지 순으로 기동한다. ai-service의 LLM 후보는 local 프로파일의 Ollama 하나라 먼저 준비됐는지 본다.
- 확인 절차는 [docs/전체-사이클-스모크.md](./docs/전체-사이클-스모크.md)에 있다.

```sh
./scripts/cycle.sh start
./scripts/cycle.sh status
./scripts/cycle.sh stop
```

인프라는 컴포넌트 하나 또는 애플리케이션별 그룹으로 관리한다.

```sh
./scripts/infra.sh mysql start
./scripts/infra.sh user start
./scripts/infra.sh notification status
./scripts/infra.sh all start
./scripts/infra.sh all stop
```

`user`는 MySQL·Redis, `collector`는 MongoDB, `ai`는 MongoDB·Kafka,
`notification`은 MongoDB·Redis·Kafka, `story`는 MongoDB·Kafka·TEI 둘·Qdrant를 선택한다.
`tei`는 임베딩·판정기 컨테이너 둘이며 첫 기동에 가중치(각 2.3GB)를 받아 수 분이 걸린다.
`core`는 MySQL·Redis·MongoDB·Kafka, `all`은 core에 TEI·Qdrant·Prometheus·Grafana까지 포함한다.
여러 애플리케이션이 같은 인프라를 공유하므로 그룹 `stop`은 해당 그룹에 속한 다른 애플리케이션에도 영향을 줄 수 있다.

프로세스 ID는 `.run/`, 출력은 `logs/`에 저장하며 두 디렉터리는 Git에서 제외한다.
`stop`은 Spring의 graceful shutdown을 위해 `SIGTERM`을 보내고 최대 30초 기다린 뒤 강제 종료한다.
`infra.sh start`는 healthcheck가 정의된 컨테이너가 준비될 때까지 기다린다.

테스트:

```sh
./gradlew :user-service:test
./gradlew :collector-service:test
./gradlew :story-service:test
./gradlew :ai-service:test
./gradlew :notification-core:test
./gradlew :notification-routing:test
./gradlew :notification-service:test
./gradlew :notification-worker:test
./gradlew :e2e-test:e2eTest
```

테스트 분류와 인프라 연동 테스트 기준은 [테스트 전략](./docs/테스트전략.md)을 따른다.

---

## 7. 문서

| 문서 | 내용 |
| --- | --- |
| [문서 지도](./docs/README.md) | docs/ 각 문서의 책임, 축 분담, 갱신 규칙 |
| [용어사전](./docs/용어사전.md) | Kachi 도메인 용어 정의 |
| [도메인 모델](./docs/도메인모델.md) | 도메인 이야기, 컨텍스트 맵, 컨텍스트별 상세 문서 index |
| [아키텍처](./docs/아키텍처.md) | 헥사고날 층 책임, 의존 규칙, API 버전 정책, ArchUnit 검증 방침 |
| [패키지 구조](./docs/패키지구조.md) | 층·포트·어댑터·config·테스트 소스셋의 패키지 배치 규칙 |
| [개발가이드](./docs/개발가이드.md) | 도메인/예외/어댑터/테스트 코드 관례와 네이밍 |
| [Notification](./docs/README-notification.md) | notification 파이프라인 종합: 모듈, 핵심 설계 요약, API, 운영 확장 설계 |
| [포트 구성](./docs/포트-구성.md) | 로컬 호스트 공개 포트, 컨테이너 인바운드 포트, 서비스 간 연결 계약 |
| [테스트 전략](./docs/테스트전략.md) | unit, slice, integration, e2e 테스트 분류와 인프라 테스트 기준 |
| [collector-service WebClient 설정](./docs/collector-webclient-설정.md) | 외부 뉴스 provider WebClient 설정값과 근거 |
| [001. user-service에 Keyword 포함](./docs/decisions/001-user-service에-keyword-포함.md) | Keyword 경계 결정 |
| [002. ArchUnit으로 아키텍처 검증](./docs/decisions/002-archunit으로-아키텍처-검증.md) | 아키텍처 규칙 자동 검증 결정 |
| [003. UUID v7과 ID Value Object 사용](./docs/decisions/003-uuid-v7과-id-value-object-사용.md) | 식별자 생성 전략과 타입 분리 결정 |
| [004. JWT Access/Refresh Token 정책](./docs/decisions/004-jwt-access-refresh-token-정책.md) | 토큰 분리, TTL, secret 관리, claim 범위 결정 |
| [005. 로컬 인프라 Docker Compose 구성](./docs/decisions/005-로컬-인프라-docker-compose-구성.md) | 로컬 MySQL/Redis/MongoDB 실행 구성과 Docker Compose 분리 기준 |
| [006. OAuth2 로그인 흐름](./docs/decisions/006-oauth2-로그인-흐름.md) | OAuth2 provider 응답 정규화, 사용자 식별, token 발급 흐름 |
| [007. user-service 운영성 기본 설정](./docs/decisions/007-user-service-운영성-기본설정.md) | health endpoint, graceful shutdown 기본 설정 |
| [008. collector-service 뉴스 수집 실행 모델](./docs/decisions/008-collector-service-뉴스-수집-실행-모델.md) | scheduler/internal API 진입점과 단일 인스턴스 lock 결정 |
| [009. 외부 뉴스 provider 연동 기준](./docs/decisions/009-외부-뉴스-provider-연동-기준.md) | Google RSS, Naver, Finnhub provider 설정과 credential 기본 정책 |
| [010. ai-service 초기 설계](./docs/decisions/010-ai-service-초기-설계.md) | 키워드 단위 AI 처리, 실행 모델, 저장 정책, LLM provider 연동 기준 |
| [011. ai-service newsHash 요약 중복 방지](./docs/decisions/011-ai-service-newsHash-요약-중복-방지.md) | 요약 입력 묶음 식별 hash와 중복 저장 방지 결정 |
| [012. notification-service 초기 모듈 설계](./docs/decisions/012-notification-service-초기-모듈-설계.md) | notification contract/core/service/worker 모듈 경계와 런타임 분리 기준 |
| [013. notification 요청 접수와 dispatch 발행 흐름](./docs/decisions/013-notification-request-service-outbox-dispatch-flow.md) | notification.requested 접수, outbox 저장, notification.dispatch 발행 흐름 |
| [014. notification dispatch 실패 분류와 Kafka retry 연결](./docs/decisions/014-notification-dispatch-retry-classification.md) | vendor 실패 분류, Kafka retry/DLT 연결, dispatch finalize CAS 기준 |
| [015. notification outbox 발행 보장과 recovery 정책](./docs/decisions/015-notification-outbox-publish-runtime.md) | outbox publish claim, stale PUBLISHING 회수, DEAD 복구 정책 |
| [016. notification-worker vendor sender 구조와 설정 구성](./docs/decisions/016-notification-worker-vendor-sender-구조.md) | Slack/Discord/Telegram sender 구조와 non-secret/secret 설정 분리 |
| [017. MongoDB replica set 전환과 트랜잭션 전제](./docs/decisions/017-mongodb-replica-set-전환과-트랜잭션-전제.md) | MongoDB multi-document transaction을 위한 로컬 replica set 전환과 transaction boundary 원칙 |
| [018. 재시도 폭주 방지와 복구 트래픽 제어](./docs/decisions/018-재시도-폭주-방지와-복구-트래픽-제어.md) | retry storm, retry budget, 서킷 브레이커, slow start, bulkhead 공통 설계 원칙 |
| [019. notification 운영 모니터링 및 관측성 설계](./docs/decisions/019-notification-운영-모니터링-및-observability-설계.md) | notification metric contract, Prometheus/Grafana, 후속 trace/log 설계 |
| [020. ai-service scheduler 실행 모델과 요약 window](./docs/decisions/020-ai-service-scheduler-실행-모델과-요약-window.md) | scheduler/internal API 실행 모델, 중복 실행 방지, watermark 기반 요약 window, 실패 키워드 격리와 해제 |
| [021. ai-service LLM 실패 분류와 서킷 브레이커, failover, 키워드 격리](./docs/decisions/021-ai-service-llm-실패-분류와-provider-circuit-breaker.md) | 실패의 책임·지속 두 축을 소비처가 쓰는 규칙: 서킷 기록, hold, cooldown, 전 후보 합의 격리, skip 분리, tick 조기 중단, 순차 failover |
| [022. ai-service outbox와 이벤트 발행 보장](./docs/decisions/022-ai-service-outbox와-이벤트-발행-보장.md) | 요약/격리 이벤트 outbox, claim/finalize CAS, publish 실패 분류와 재시도 |
| [023. ai-service 도메인 이벤트 발행과 ai-contract](./docs/decisions/023-ai-service-도메인-이벤트-발행과-ai-contract.md) | `ai.*` topic과 계약 모듈, Kafka 발행 실패 분류, producer timeout, relay와 발행 어댑터 스위치 분리 |
| [024. 상태 전이 aggregate 불변화와 finalize CAS](./docs/decisions/024-상태-전이-aggregate-불변화와-finalize-cas.md) | notification aggregate 불변 전환, outbox finalize의 claim CAS, ai-service와 남기는 차이 |
| [025. 키워드 구독과 알림 라우팅](./docs/decisions/025-키워드-구독과-알림-라우팅.md) | 구독·채널 바인딩 소유권, 키워드 identity 정규화, notification routing fan-out, 홉별 멱등 키, 주소 참조 |
| [026. 키워드 identity와 구독, 채널 바인딩](./docs/decisions/026-키워드-identity와-구독-채널-바인딩.md) | canonical 키워드와 구독 분리, 정규화 규칙, 채널 바인딩 생명주기와 주소 암호화, internal API 계약 |
| [027. 알림 라우팅 서비스](./docs/decisions/027-알림-라우팅.md) | routing을 독립 모듈·프로세스로, `notification.requested` 계약 연결, RoutingJob 멱등, 결정적 requestId, 키워드 격리 알림 수신자 |
| [028. 발송 직전 주소 조회와 스킵](./docs/decisions/028-발송-직전-주소-조회와-스킵.md) | worker의 `(recipientId, channel)` 주소 조회와 Redis 캐시, 없는 수신자의 스킵(SUPPRESSED), `sent:{requestId}` 발송 직전 가드, 전역 webhook 설정 삭제 |
| [029. 전체 사이클 검증과 e2e-test 모듈](./docs/decisions/029-전체-사이클-검증과-e2e-test-모듈.md) | 모듈별 Kafka 계약 통합 테스트, 서비스 다섯 개를 컨테이너로 띄우는 `e2e-test` 모듈과 별도 task, skip/실패 규칙, 스모크 절차 |
| [030. ai-service LLM 호출 단위, 실패 분류, 실호출 검증](./docs/decisions/030-ai-service-llm-호출-단위와-실패-분류와-실호출-검증.md) | API 규격·제공자·모델·용도 enum과 yaml의 경계, 전략·데코레이터·컴포지트 세 층 조립, 실패의 책임·지속 두 축, 프롬프트 버전을 코드에, `src/realTest` 소스셋과 운영 API |
| [031. 요약 단위를 키워드에서 story로](./docs/decisions/031-요약-단위를-키워드에서-story로.md) | TDT 틀에서 같은 사건의 기사를 story로 묶는 방향, 세 층의 중복 제거, 모델 네 종류(임베딩·판정기·생성형·벡터 저장소)의 역할, 서비스 경계와 데이터 소유, 벡터 색인은 파생 캐시 |
| [032. collector 기사 이벤트 발행과 collector-contract](./docs/decisions/032-collector-기사-이벤트-발행과-collector-contract.md) | 발췌문·URL 정규화 기사 모델, collector outbox와 relay, `collector.news.collected` 계약과 compact+delete topic, 발행·relay 스위치 분리 |
| [033. story-service 조립](./docs/decisions/033-story-service-조립.md) | story·기사 사본 도메인, 골드셋으로 정한 2단계 판정 임계값, Mongo 먼저 색인 다음의 이중 쓰기, 닫기·병합·정리와 색인 자가 수리, Qdrant gRPC 채택 측정, delete 정책 topic 둘 |
| [034. ai-service story 요약](./docs/decisions/034-ai-service-story-요약.md) | story·기사 사본으로 요약, 미요약 수 즉시 트리거와 대기 상한 tick, `(storyId, version)` 멱등과 story CAS, `developmentKind`별 발행, story 격리, `ai.summary.created` schemaVersion 2와 발행 스위치 |
| [036. 실행 lock 포트 분리와 lock 범위](./docs/decisions/036-실행-lock-포트-분리와-lock-범위.md) | 중복 실행 방지를 `ExecutionLockPort`로 분리, 실행 단위마다 `INSTANCE`·`CLUSTER` 범위 선언, outbox relay가 `INSTANCE`인 이유 |
