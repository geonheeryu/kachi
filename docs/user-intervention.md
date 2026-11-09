# 사람이 손으로 해야 하는 일

에이전트 세션은 이 저장소 안에서 명령을 실행하고 파일을 수정하는 일만 한다. 외부 서비스 콘솔, 비밀 값, 과금 계정, 컨테이너 기동 조건은 CLI만으로 처리할 수 없다. 이런 일이 생기면 세션은 사람을 기다리지 않고 여기에 기록한 뒤 다음 작업으로 넘어간다.

이 문서는 할 일 목록이다. 결정 이유와 절차는 각 항목이 가리키는 문서에 있다.

| 번호 | 일 | 성격 |
|---|---|---|
| 1 | `.env`·`.env.<profile>`의 실제 값 | 값이 바뀔 때마다 |
| 2 | Testcontainers 실행 조건 | 세션마다 |
| 3 | TEI·Qdrant 컨테이너 첫 기동 | 머신마다 한 번 |
| 4 | LLM 실호출 검증 계정 | 과금 계정을 쓸 때 |
| 5 | 전체 사이클 스모크의 수신 확인 | 사이클 구성이 바뀔 때 |
| 6 | 저장 형식이 바뀐 뒤의 로컬 데이터 초기화 | 저장 형식이 바뀔 때 |

## 1. `.env`·`.env.<profile>`의 실제 값

- [ ] `.env.example`의 키 목록대로 실제 값을 넣는다. 키 목록은 `.env.example`이 기준이고, 읽는 순서는 루트 README 6장에 있다.
- [ ] `.env`·`.env.local`에서 `KACHI_COLLECTOR_SERVICE_BASE_URL` 줄을 지운다. ai-service는 collector-service를 호출하지 않아 이 키를 읽는 설정이 없다.

값은 모두 외부 서비스 계정에서 발급받는다. OAuth 클라이언트(Google, Kakao), 뉴스 provider(Naver, Finnhub), LLM provider(OpenRouter, Groq, Together, Cerebras, Mistral), 알림 채널(Slack·Discord 웹훅, Telegram 봇 토큰과 chat id), JWT secret과 바인딩 키가 해당한다. 에이전트는 `.env.example`과 `scripts/`만 작성하고 실제 값은 넣지 않는다(`AGENTS.md` 작업 방식). 전역 훅이 `.env*` 편집을 막는다.

`local` 프로파일은 값이 없어도 기동한다. 다른 프로파일은 값이 없으면 기동에 실패한다(README 6장).

## 2. Testcontainers 실행 조건

- [ ] Docker Desktop을 켠다.
- [ ] `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`을 설정한다.
- [ ] Gradle 데몬이 이미 실행 중이었다면 `./gradlew --stop`으로 재시작한다.

세 조건이 모두 맞아야 통합 테스트가 통과한다. Gradle 데몬은 처음 시작될 때의 환경 변수를 유지하므로, 명령 앞에 붙인 환경 변수는 이미 실행 중인 데몬에 전달되지 않는다(`jvm-conventions` 스킬). `python3 scripts/harness/preflight.py`가 앞의 두 조건을 확인한다. 세 번째는 스크립트로 확인할 수 없으므로 통합 테스트가 실패하면 이것부터 확인한다.

통합 테스트가 실제로 실행됐는지는 빌드 로그가 아니라 `build/test-results/test`의 스위트 목록으로 판단한다. 인프라가 없으면 skip이 아니라 실패다(`docs/테스트전략.md`).

## 3. TEI·Qdrant 컨테이너 첫 기동

- [ ] `./scripts/infra.sh tei start`로 임베딩·판정기 컨테이너를 실행한다. 첫 실행은 가중치(각 2.3GB)를 내려받느라 수 분이 걸린다.

story-service의 `realTest`와 전체 사이클 스모크가 이 컨테이너를 필요로 한다. 다운로드에 시간이 오래 걸리고 디스크를 차지하므로 사람이 실행 여부를 정한다(README 6장, `docs/테스트전략.md` 실행 명령).

## 4. LLM 실호출 검증 계정

- [ ] `./gradlew :ai-service:realTest`를 과금 계정으로 실행할지 정하고, 실행한다면 해당 provider의 API 키를 `.env`에 넣는다.

`realTest`는 `check`에 포함되지 않는 JVM Test Suite이고, 과금 계정은 클래스 단위로 opt-in한다(`docs/테스트전략.md`). 과금 여부는 사람이 결정한다.

## 5. 전체 사이클 스모크의 수신 확인

- [ ] `docs/전체-사이클-스모크.md`의 확인 절차대로 Slack, Discord, Telegram에 알림이 도착했는지 사람이 확인한다.

에이전트는 발송 로그까지만 확인할 수 있고, 실제 채널에 도착했는지는 사람이 화면으로 확인한다.

## 6. 저장 형식이 바뀐 뒤의 로컬 데이터 초기화

- [ ] 저장 형식이 바뀐 코드로 처음 기동하기 전에 로컬 Mongo의 `kachi_ai`, `kachi_notification`, `kachi_notification_routing` 데이터베이스를 지운다.
- [ ] 같은 때에 Kafka의 `ai.*`, `notification.*` topic을 지운다.

ai의 실행 기록, routing job, 알림 출처는 옛 문서를 읽는 이행 코드가 없다. 옛 데이터와 옛 schemaVersion 레코드가 남으면 복원이나 소비에서 실패하며, 근거는 ADR 035의 운영 제약이다.
로컬 데이터를 지우는 일이라 사람이 실행 여부를 정한다. 사용자·구독·바인딩(MySQL)과 collector·story-service의 Mongo는 지우지 않는다.

```sh
docker exec kachi-mongo mongosh --quiet --eval \
  'for (const name of ["kachi_ai", "kachi_notification", "kachi_notification_routing"]) { db.getSiblingDB(name).dropDatabase() }'
docker exec kachi-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --delete --topic 'ai\..*|notification\..*'
```
