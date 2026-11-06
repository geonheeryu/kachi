# e2e-test

서비스 다섯 개의 bootJar를 컨테이너로 실행해 구독, 수집, 요약, 라우팅, 발송 사이클을 끝까지 확인하는 모듈이다. 다른 모듈은 이 모듈에 의존하지 않고, 이 모듈은 각 서비스의 bootJar만 사용한다.

## 실행

```sh
./gradlew :e2e-test:e2eTest
```

`./gradlew test`와 `check`에 포함되지 않는다. Docker Desktop과 `DOCKER_HOST`가 필요하며 조건은 `docs/user-intervention.md` 2번에 있다. 테스트 분류와 정책은 `docs/테스트전략.md`에 있다.

## 구성

- `KachiCycleE2ETestBase`: 클러스터 실행과 공통 단언. 사이클 테스트는 이 클래스를 상속한다.
- `SummaryNotificationCycleE2ETest`, `KeywordQuarantineNotificationE2ETest`: 요약 알림과 격리 알림의 사이클.
- `support/KachiCluster`, `KachiInfrastructure`, `KachiServiceContainer`: 인프라 컨테이너(MySQL, Redis, MongoDB, Kafka)와 서비스 컨테이너 실행.
- `support/TestLlmServer`, `TestStubServer`: 실제 LLM과 외부 provider 대신 응답하는 stub. 실제 호출 검증은 각 서비스의 `realTest`에서 한다.
- `support/NotificationStore`, `PrometheusMetrics`, `UserServiceClient`, `JwtSupport`, `KafkaSupport`, `JsonHttp`: 단언과 호출 도우미.

## 관련 문서

- [전체 사이클 검증과 e2e-test 모듈](../docs/decisions/029-전체-사이클-검증과-e2e-test-모듈.md)
