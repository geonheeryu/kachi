# AGENTS.md - kachi

뉴스 수집, story 조립, AI 요약, 알림 라우팅·발송, 사용자·구독을 각각 맡는 서비스와 계약 모듈로 이루어진 Kotlin·Spring 멀티모듈 백엔드다. 모듈 목록은 `settings.gradle.kts`, 문서 지도는 `docs/README.md`에 있다. 이 파일에는 `docs/`에 있는 내용을 적지 않는다.

## 빌드·테스트

- 모듈 하나만 테스트하려면 `./gradlew :<module>:test`를 실행한다.
- 통합 테스트는 Docker Desktop이 켜져 있어야 한다.
- Testcontainers 실행 조건은 `docs/user-intervention.md` 2번에 있다.
- `python3 scripts/harness/preflight.py`가 Java 21, Docker, `DOCKER_HOST`를 확인한다.
- 검증 명령은 `harness.json`의 `verify`에 정의한다.
- `python3 scripts/harness/verify.py fast|arch`가 그 명령을 실행한다.

## 구조 규칙

- 계층과 배치 규칙은 각 서비스 모듈의 `src/test/.../architecture/ArchitectureTest.kt`(ArchUnit)가 검사한다.
- 아직 규칙을 어기고 있는 곳은 `@ArchIgnore(reason)`으로 표시한다.
- 패키지를 새로 만들거나 옮길 때는 `docs/패키지구조.md`를 먼저 읽는다.
- 발송 경로는 수신자, 채널, 주소, 내용만 입력으로 받는다. 관리자 분기 같은 특수 처리를 넣지 않는다.
- 수신자를 나타내는 용어는 `recipientId`, `channel`, `address` 셋이다. 합성어와 리터럴 `admin`을 쓰지 않는다.

## 작업 방식

- 새 기능은 main에서 브랜치를 만들어 작업한다. 별도 worktree를 만들지 않는다.
- `scripts/`와 `.env.example`은 에이전트가 작성한다. `.env`와 `.env.local`의 실제 값은 사용자가 넣는다.
- 사람이 해야 하는 일이 생기면 `docs/user-intervention.md`에 적고 다음 작업으로 넘어간다.
- 작업 문서는 세 가지다. 진행 중인 내용은 `prompt.md`, 끝난 내용은 `HISTORY.md`, 단계별 스펙은 `tasks/<id>-<name>/`(규격은 `prompts/task-create.md`)에 적는다. 셋 다 커밋하지 않는다.
- 기존 `DESIGN-*.md`는 그대로 두고 새 단계부터 `tasks/`를 쓴다.
- 커밋과 push는 사용자가 그 커밋을 명시적으로 허락한 뒤에만 한다.
- 러너 세션은 커밋하지 않고 러너가 대신 커밋한다.

## 하네스

- 이 저장소는 감독 모드(`harness.json`의 `mode: supervised`)라 러너를 쓰지 않는다.
- `plan-and-build`는 tech-critic-lead 판정을 거쳐 `tasks/<id>-<name>/`을 만든 뒤 사용자 지시를 기다리고, 사용자가 phase를 지정하면 같은 세션에서 수행해 AC 결과를 보고한다.
- ADR 초안과 단계 스펙은 사용자에게 올리기 전에 `tech-critic-lead`(판단 기준은 `.agents/tech-critic-beliefs.md`)의 판정문을 붙인다. 판정은 참고 의견이고 결정은 사용자가 한다.

## 스킬

- 프로젝트 고유 문서 규칙은 `WRITING.md`를 따른다.
