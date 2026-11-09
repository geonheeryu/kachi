# 032. collector 기사 이벤트 발행과 collector-contract

## 배경

ADR 031은 요약 단위를 키워드에서 story로 바꾸고, 기사를 사건으로 묶는 조립을 새 컨텍스트에 두기로 했다.
조립이 기사를 받는 길은 collector의 조회 API가 아니라 이벤트다.
소비자가 발행자의 DB나 API를 부르지 않아야 조립 장애가 수집을 세우지 않고, 재처리가 로그를 다시 읽는 것 하나로 끝난다.

collector에는 이벤트를 내보내는 장치가 없었다.
기사는 저장만 되고, ai-service가 10분마다 조회 API로 읽어 갔다.

기사 모델에도 빠진 것이 있었다.

- Naver `description`, Finnhub `summary`를 adapter가 받고 버렸다. 제목만으로는 임베딩 정밀도가 떨어진다(ADR 031).
- `urlHash`가 원문 URL 그대로의 해시라 추적 파라미터나 끝 슬래시만 달라도 다른 기사로 저장됐다.
- `titleFingerprint`는 정규화 제목의 해시인데 읽는 곳이 없었다.

## 검토한 선택지

기사를 소비자에게 넘기는 방식이다.

| 선택 | 장점 | 단점 |
| --- | --- | --- |
| 조회 API를 유지하고 소비자가 주기적으로 읽는다 | 변경이 없다 | 소비자마다 구간 관리가 생기고, collector 장애가 소비자 tick을 비운다. ADR 020의 watermark 문제가 소비자마다 반복된다 |
| 기사 id만 싣는 얇은 이벤트 | 레코드가 작다 | 소비자가 매 건 collector API를 부른다. 다시 읽을 때 collector에 대량 조회가 몰린다 |
| 기사 내용을 다 싣는 이벤트 + outbox | 소비자가 collector를 모른다. 다시 읽는 일이 소비자 안에서 끝난다 | 레코드가 커진다(약 1KB). 기사 내용이 소비자 저장소에도 남는다 |

세 번째를 택했다.
Fowler가 Event-Carried State Transfer라고 부른 방식이고, ADR 031이 정한 "이벤트는 상태를 싣는다"의 첫 적용이다.

## 결정

### 기사 모델

- `excerpt`(발췌문)를 더한다. provider가 기사와 함께 주는 짧은 설명이며 본문이 아니다.
  - HTML 제거는 형식을 아는 adapter가 하고, 값 객체는 공백 정리와 길이 상한(1,000자)만 맡는다.
  - Google RSS는 `description`의 평문이다. 기사 하나면 제목과 매체명, 묶인 기사면 관련 제목 목록이 들어 있어 임베딩 입력이 된다.
- `language`를 더한다. Naver는 `ko`, Finnhub는 `en`, Google RSS는 요청에 보낸 `hl` 값이다.
  - provider 설정이 정하는 사실이므로 adapter가 채우고 story-service는 판별하지 않는다.
- 도메인 `News`에 null 속성은 없다. 제목·발췌문·URL·언어·발행 시각 중 하나라도 없는 provider item은 adapter가 기사로 만들지 않는다.
  - 값이 없는 기사를 nullable로 받아 두면 소비자마다 "없으면 어떻게"가 생기고, 그 분기는 운영 데이터 없이 정할 근거가 없다. 수집 시점에 제외하고 제외 수는 수집 결과의 중복·실패와 함께 센다.
- `titleFingerprint`를 지운다. 제목이 같은 기사를 묶는 일은 story-service의 임베딩 경로가 더 정확하게 한다(ADR 031).

### URL 정규화

`urlHash`는 원문이 아니라 정규화한 URL의 SHA-256이다.
원문 URL은 그대로 저장하고 해시 입력만 바꾼다.

| 규칙 | 예 |
| --- | --- |
| scheme·host 소문자화, 기본 포트 제거, fragment 제거 | `HTTPS://Kachi.COM:443/news/1#top` -> `https://kachi.com/news/1` |
| 추적 파라미터 제거 (`utm_*`, `fbclid`, `gclid`, `dclid`, `yclid`, `msclkid`, `igshid`, `mc_cid`, `mc_eid`, `ref`, `ref_src`, `_ga`, `_gl`) | `?utm_source=x&id=1` -> `?id=1` |
| 남은 파라미터를 키·값 순으로 정렬 | `?page=2&id=1` -> `?id=1&page=2` |
| 끝 슬래시 제거 | `/news/1/` -> `/news/1` |
| AMP 경로와 `amp.` 서브도메인 원본화 | `/news/1/amp` -> `/news/1`, `amp.kachi.com` -> `kachi.com` |

파싱할 수 없는 URL은 손대지 않는다. 그런 URL끼리는 원문이 같을 때만 같은 해시다.
`www.` 제거와 모바일 서브도메인 원본화는 하지 않는다. 다른 페이지를 같은 것으로 만들 위험이 이득보다 크다.

이 규칙은 `NewsUrl` 값 객체 안에만 있고 저장소는 결과값만 갖는다.
운영 데이터가 없어 기존 문서의 해시를 다시 계산하는 절차는 두지 않는다.

### outbox와 relay

ai-service의 outbox·relay(ADR 022)와 같은 구조를 collector에 둔다.

- 기사 insert와 outbox insert는 한 Mongo 트랜잭션이다. `(source, urlHash)` unique 충돌은 트랜잭션 전체를 되돌리므로 중복 기사의 outbox 행은 남지 않는다.
- relay는 PENDING 행을 claim -> 발행 -> finalize CAS로 옮기고, visibility timeout이 지난 PUBLISHING 행을 회수한다. 실패 분류(retryable / non-retryable)와 DEAD·복구 API도 ADR 022·023과 같다.
- Mongo 트랜잭션은 replica set 전제다(ADR 017). collector의 로컬·테스트 설정도 replica set으로 맞춘다.

bounded context 간 코드 의존 금지(ADR 021)가 outbox에도 적용된다. 세 컨텍스트(notification, ai, collector)가 각자 갖는다.

### 계약과 topic

| eventType | topic | key | 계약 |
| --- | --- | --- | --- |
| `NEWS_COLLECTED` | `collector.news.collected` | newsId | `CollectorNewsCollectedEvent` |

계약 모듈 `collector-contract`에는 data class와 enum만 있다(ADR 012).

| 필드 | 뜻 |
| --- | --- |
| schemaVersion | 1 |
| newsId | 기사 id |
| source | provider (`GOOGLE`, `NAVER`, `FINNHUB`) |
| title, excerpt | 임베딩 입력 |
| url | 정규화 전 원문 URL |
| language | `ko`, `en` 같은 ISO 639 기본 부호 |
| publishedAt, collectedAt | 시각 |
| matchedKeywords | canonicalKey 목록 |

이벤트 키와 파티션 키가 모두 기사 id다.
기사는 저장 후 바뀌지 않으므로 순서를 지킬 단위가 기사 하나이고, 같은 기사의 재발행(DEAD 복구)은 소비자의 newsId 멱등이 거른다.

topic은 `cleanup.policy=compact,delete`에 `retention.ms` 30일이다.
compaction으로 기사당 최신 1건이 남고, 기사가 불변이라 그것이 곧 전체 이력이다.
30일 안에서는 새 소비자 그룹이 처음부터 다시 읽어 재처리할 수 있다(ADR 031).
topic 선언은 producer가 `NewTopic` 빈으로 갖는다. 로컬은 broker가 이 선언으로 topic을 만들고, 이미 있는 topic의 설정은 바꾸지 않는다.

### 스위치

| 키 | 뜻 | 기본값 |
| --- | --- | --- |
| `kachi.collector.outbox.relay.enabled` | outbox를 읽어 발행 포트로 넘기는 relay | `true` |
| `kachi.collector.events.enabled` | Kafka 발행 어댑터와 topic 선언 | `true` |
| `kachi.collector.events.retention` | topic 삭제 기간 | `30d` |

relay만 켜고 어댑터가 없으면 기동에 실패한다. 테스트 프로파일은 둘 다 `false`다.

### 조회 API

`GET /api/v1/internal/news`는 그대로 두고 응답에 `excerpt`·`language`를 더한다.
ai-service가 story 경로로 옮겨 가면(ADR 035) 이 API는 지운다.

## 검토한 대안

- **SimHash로 제목 근접 중복을 collector에서 걷어내기**: ADR 031 대안 절. 짧은 텍스트 오탐과 판정기 우회가 근거다.
- **`www.` 제거**: 같은 도메인의 `www`와 비-`www`가 다른 사이트인 경우가 있다. 재수집 중복보다 다른 기사 병합이 나쁘다.
- **발췌문·발행 시각이 없는 item을 null로 저장**: 소비자마다 결손 분기가 생긴다. 도메인은 완전한 기사만 갖고 결손은 수집 시점에 제외한다.
- **outbox 없이 저장 후 즉시 발행**: ADR 022 반려안. 저장과 발행 사이 유실 창이 생긴다.
- **key를 키워드로**: 기사 하나가 여러 키워드에 매칭되므로 키가 정해지지 않고, compaction의 단위도 기사여야 한다.
- **Schema Registry로 계약 관리**: 기존 계약 모듈까지 옮겨야 효과가 있다. 별도 결정이다(ADR 031).

## 트레이드오프

- 장점:
  - 소비자가 collector를 모른다. 조립 장애가 수집을 세우지 않고 기사는 Kafka에 쌓인다.
  - 같은 페이지의 URL 변형이 한 건으로 남는다.
  - 재처리가 로그를 다시 읽는 것 하나다.
- 단점:
  - 모듈 하나(`collector-contract`)와 Kafka 의존, Mongo 트랜잭션 전제가 collector에 는다.
  - 기사 내용이 Kafka와 소비자 저장소에도 남는다.
  - 정규화 규칙이 바뀌면 이미 저장된 해시와 어긋나 같은 페이지가 다시 저장될 수 있다. 규칙 변경은 해시 재계산과 함께 한다.

## 운영 제약

- Mongo가 replica set이 아니면 저장이 트랜잭션 오류로 실패한다.
- relay를 끈 채 쌓인 PENDING은 켜는 즉시 batch 50건씩 발행된다. 오래된 기사가 한꺼번에 나가는 것이 원치 않는 결과면 켜기 전에 outbox 조회 API로 양을 본다.
- ai-service의 조회 API 소비는 story 경로로 옮겨 갈 때까지(ADR 035) 유지된다. 응답 필드 추가는 소비자에게 영향이 없다.

## 후속

- ADR 033: story-service가 이 이벤트를 소비해 조립한다.
- ADR 035: ai-service의 조회 API 소비를 끊고 `GET /api/v1/internal/news`를 지운다.

## 출처

- Martin Fowler, [What do you mean by "Event-Driven"?](https://martinfowler.com/articles/201701-event-driven.html) (2017)
  - "This pattern shows up when you want to update clients of a system in such a way that they don't need to contact the source system in order to do further work."
- Debezium, [Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
  - outbox 행을 계약 형태로 내보내는 방식. relay 대신 CDC로 옮기는 것은 별도 결정이다.
- Apache Kafka, [Topic Configs](https://kafka.apache.org/documentation/#topicconfigs)
  - `cleanup.policy`는 `delete`, `compact`, 또는 둘의 조합이며 조합이면 compaction과 기간 삭제가 함께 적용된다.
