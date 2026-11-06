# tech-critic-lead 신념 (kachi)

`tech-critic-lead` 에이전트가 판정 전에 읽는 파일이다. 절차와 응답 형식은 에이전트 정의에 있고, 여기에는 이 저장소의 규약과 판단 기준만 적는다. kachi에서 판정은 참고 의견이고 최종 결정은 사용자가 한다.

## 계층 규약

규칙의 설명은 `docs/아키텍처.md`, `docs/패키지구조.md`, `docs/개발가이드.md`에 있고, 검사는 각 모듈의 `ArchitectureTest.kt`가 한다. 판정에 쓰는 기준은 다음과 같다.

- 의존 방향은 `adapter -> application -> domain`이다. 도메인은 Spring, JPA, Mongo, Kafka에 의존하지 않는다.
- 포트 패키지는 기능별로 나누고 adapter 하위 패키지와 1:1로 대응시킨다. outbound adapter 패키지는 `adapter/outbound/<포트 패키지 이름>/`이다. 같은 기술의 어댑터가 포트 둘 이상을 구현할 때만 `adapter/outbound/<기술 이름>/<포트 패키지 이름>/`으로 둔다. 예: `sender/`, `persistence/{notification,outbox,dlt}/`.
- 상태 전이가 있는 aggregate는 불변이고(`val`과 `markX(): T`), 외부 side effect 뒤의 결과 확정은 CAS로 한다. 전이 전 값을 별도 변수에 복사해 두는 코드는 거부한다(ADR 024).
- 어댑터는 실패를 분류만 하고 직접 재시도하지 않는다. 재시도 여부는 저장된 상태를 읽어 결정한다(개발가이드 어댑터 관례).
- 시간은 application이 `Clock`으로 만들어 전달한다. 도메인의 `Instant.now()`는 거부한다.
- 서비스 간 공유는 계약 모듈(`*-contract`)로만 한다.

## 기획 문서와 ADR 위치

- 도메인: `docs/도메인모델*.md`(선행 원장), `docs/용어사전.md`
- ADR: `docs/decisions/NNN-주제.md`. 상태 줄 없이 최종 상태만 적는다. 번호는 재사용하지 않는다(034, 035는 예약).
- 테스트 정책: `docs/테스트전략.md`. 인프라와 secret이 없으면 skip이 아니라 실패다. skip으로 통과시키는 제안은 거부한다.

## 더 적은 비용으로 해결한 사례

ADR 001은 키워드를 별도 서비스로 분리하지 않고 user-service에 두었다. ADR 036은 새 인프라를 들이지 않고 lock 포트를 분리해 범위를 좁혔다. 새 모듈이나 새 인프라를 요구하는 제안은 기존 서비스에 포트를 추가하는 방법으로 안 되는 이유를 먼저 설명해야 한다.

## 이 저장소에서 특히 거부하는 것

- 운영 데이터가 없는 상태의 호환 코드. nullable과 fallback, 옛 형식 문서를 위한 테스트.
- 관리자 분기와 리터럴 `admin`. 수신자 용어는 `recipientId`, `channel`, `address`뿐이다.
- 통합 테스트가 실제로 실행됐는지를 빌드 로그로 판단하는 것. `build/test-results/test`의 스위트 목록으로 판단한다.
- 패키지를 대량으로 옮기고 컴파일만 확인하는 것. 문서의 경로 참조 수정과 ArchUnit 통과까지 마쳐야 완료다.
