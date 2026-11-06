# History 컨텍스트 도메인 모델 — 계획, 미구현

history-service 모듈은 미구현이다. 
이 문서는 다른 컨텍스트가 History와 책임을 나눌 때 참조하는 경계 정의만 담는다. 
모듈이 생기면 이 문서를 실제 모델로 채운다.

## 이력(History)

_Aggregate Root_ (계획)

#### 규칙(Rules)

- 사용자별 뉴스, 요약, 알림 결과를 장기 조회할 수 있어야 한다.
- history-service는 장기 조회와 통계 적재를 담당한다.
- notification-service의 발송 처리 상태와 history-service의 장기 이력은 책임을 분리한다.
- notification 내부의 `NotificationHistory`는 한 알림 aggregate의 상태 전이 감사 로그이며,
  history-service의 장기 사용자 이력으로 대체하지 않는다.
- collector의 `CollectionRun`, ai의 `AiRun`도 각 서비스의 운영 기록이지 사용자 이력이 아니다.
