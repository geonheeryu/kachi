# e2e-test

서비스 일곱 개의 bootJar를 컨테이너로 실행해 기사 수집, story 조립, 요약, 라우팅, 발송 사이클을 끝까지 확인하는 모듈이다. 외부 vendor인 Google News RSS, LLM, TEI 추론 서버 둘은 테스트 JVM 안의 stub이 받고, 발송 vendor는 worker의 mock sender다. 다른 모듈은 이 모듈에 의존하지 않고, 이 모듈은 각 서비스의 bootJar와 계약 모듈만 사용한다.

## 실행

```sh
./gradlew :e2e-test:e2eTest
```

`./gradlew test`와 `check`에 포함되지 않는다. Docker Desktop과 `DOCKER_HOST`가 필요하며 조건은 `docs/user-intervention.md` 2번에 있다. 테스트 분류와 정책은 `docs/테스트전략.md`에 있다.

통과 여부는 `build/test-results/e2eTest/`의 XML로 본다. 테스트가 실패하면 서비스 컨테이너마다 로그 끝 120줄이 그 테스트의 `system-out`에 남는다.

## 구성

- `KachiCycleE2ETestBase`: 공유 클러스터 연결, 기사 게시, 수집 API 호출, story와 요약 레코드 대기.
- `StorySummaryNotificationE2ETest`: 3채널 발송, 수신자당 한 통, 요약 이벤트 재전달 멱등, 바인딩 없는 수신자 스킵.
- `StoryQuarantineNotificationE2ETest`: story 격리 알림의 관리자 채널 발송.
- `support/KachiCluster`, `KachiInfrastructure`, `KachiServiceContainer`: 인프라 컨테이너(MySQL, Redis, MongoDB, Kafka, Qdrant)와 서비스 컨테이너. 클러스터는 JVM 하나에 한 번 뜨고 모든 테스트 클래스가 공유한다.
- `support/TestGoogleNewsServer`, `TestLlmServer`, `TestTeiServer`, `TestStubServer`: 외부 vendor stub. 실제 vendor 호출은 각 서비스의 `realTest`에서 검증한다.
- `support/StoryMarker`, `TestKeyword`: 테스트마다 새로 만드는 story 표지와 키워드.
- `support/NotificationStore`, `PrometheusMetrics`, `UserServiceClient`, `JwtSupport`, `KafkaSupport`, `JsonHttp`, `ServiceLogsOnFailure`: 단언과 호출 도우미.

## 테스트 격리

테스트는 고유 키워드와 story 표지(`e2e-story-` 뒤에 16진수 12자)를 만들고, 표지를 기사 제목에 넣는다. stub은 요청에 든 표지로 응답을 정한다.

- TEI 임베딩 stub은 표지에서 정한 단위 벡터에 텍스트마다 다른 성분을 가중치 0.2로 더해 정규화한다.
  - 같은 표지의 기사끼리는 코사인이 약 0.96이라 story-service 기본값 `theta-high` 0.70을 넘어 한 story에 붙는다.
  - 표지가 다른 기사와는 코사인이 0 근처라 `theta-low` 0.60에 닿지 않고 새 story가 된다.
- TEI judge stub의 rerank 점수는 질의와 후보의 표지가 같으면 0.95, 다르면 0.05다.
- LLM stub은 요청 본문의 표지에 정해 둔 응답을 돌려준다. 격리 시나리오의 400 응답은 다른 테스트의 요약 요청에 쓰이지 않는다.

## 덮어쓰는 설정

서비스는 `local` 프로파일로 뜨고, 인프라·stub·user-service 주소와 아래 값을 환경변수로 덮는다.

| 서비스 | 환경변수 | 값 | 이유 |
| --- | --- | --- | --- |
| collector-service | `KACHI_COLLECTOR_PROVIDERS_NAVER_ENABLED`, `..._FINNHUB_ENABLED` | `false` | credential이 없으면 기동에 실패하는 provider다. |
| collector-service | `KACHI_COLLECTOR_SCHEDULER_NEWS_ENABLED` | `false` | 수집은 테스트가 internal API로 일으킨다. 스케줄러 실행과 겹치면 API가 409를 돌려준다. |
| collector, story, ai | `KACHI_*_OUTBOX_RELAY_INITIALDELAY`, `..._FIXEDDELAY` | `1s` | 기본값은 15s와 5s다. |
| ai-service | `KACHI_AI_STORYQUARANTINE_FAILURETHRESHOLD` | `2` | 격리 시나리오가 넣을 기사 수를 이 값으로 정한다. |
| ai-service | `KACHI_AI_SCHEDULER_KEYWORDEXPANSION_ENABLED` | `false` | 시나리오 밖에서 LLM stub을 부르는 주기 작업이다. |
| notification-worker | `KACHI_NOTIFICATION_SENDER_*_ENABLED` | `false` | 실 sender를 모두 끄면 mock sender가 모든 채널을 맡는다. |

story-service의 판정 임계값과 judge 종류, ai-service의 `min-new-articles`는 기본값 그대로다.

## 관련 문서

- [ADR 029(전체 사이클 검증과 e2e-test 모듈)](../docs/decisions/029-전체-사이클-검증과-e2e-test-모듈.md)
- [ADR 035(notification-routing의 story 요약 소비와 storyId 멱등)](../docs/decisions/035-notification-routing-story-요약-소비와-storyId-멱등.md)
