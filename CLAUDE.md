# StopBell — Claude Code 지침

공통 작업 규칙은 Codex와 공유하는 `AGENTS.md`를 그대로 따른다. 이 파일은 Claude Code 전용 보충만 담으며 규칙을 중복 작성하지 않는다.

@AGENTS.md

## 프로젝트 요약

선택한 버스를 추적해 목표 정류장 전에 하차 준비 알림을 주는 앱이다. 기존 버스 도착·통과 Alarm도 유지한다.

- Backend: `backend/` — Java 21, Spring Boot 4, JPA, MySQL 8.4(Docker), Flyway, Gradle Wrapper, Testcontainers, FCM
- Client: `app/` — Flutter/Dart, iOS 우선, Firebase Messaging, Google·Apple 로그인
- 문서: `docs/`가 Source of Truth. 진입점은 `docs/CHAT_HANDOFF.md`(현재 위치·다음 작업), 문서 책임 경계는 `docs/development-guidelines.md#3-문서-책임`

## 세션 시작 시

1. `git status`, `git log --oneline -5`로 HEAD와 작업 트리를 확인한다. Codex가 마지막 작업자일 수 있다.
2. `docs/CHAT_HANDOFF.md`의 "현재 위치"와 "현재 우선순위"를 확인한다.
3. 이후 문서는 `development-guidelines.md#문서-조회-기준`에 따라 필요한 섹션만 읽는다. `task-list.md`(2,000줄+), `local-development.md`, `api.md`, `database.md`, `architecture.md`는 전체를 읽지 않고 grep으로 관련 섹션만 찾는다.

## Codex와 교대 사용

- Claude Code와 Codex는 동시에 작업하지 않고 사용량에 따라 번갈아 사용한다. 같은 문서·규칙(`AGENTS.md`, `docs/`)을 공유한다.
- 작업 시작 시 내가 만들지 않은 미커밋 변경이 있으면 수정·되돌리기·커밋하지 않고 개발자에게 먼저 알린다.
- 진행 상태는 Git 커밋과 `docs/` 문서로만 넘긴다. Claude 전용 메모에 프로젝트 상태를 별도로 보관하지 않는다.
- `docs/CHAT_HANDOFF.md`의 ChatGPT/Codex 역할과 응답 형식은 ChatGPT용 안내다. Claude는 이 세션에서 구현·검증·문서 동기화·로컬 커밋을 수행하는 Codex 역할을 맡는다.

## 작업 규칙 요약 (상세는 원문)

- 확정된 설계·기능 계약(API, Domain, DB, 인증, 추적 상태 머신, ADR)을 임의로 바꾸지 않는다. 판단이 필요하면 선택지와 Trade-off를 보고한다.
- 테스트는 직접 관련 + 인접 회귀만 선택 실행한다. 전체 Backend·Flutter 테스트는 기본 금지이며 예외는 `development-guidelines.md#테스트-실행-범위`를 따른다. 보고 시 실행/미실행 범위를 구분한다.
- 커밋은 로컬까지만 한다. **Push는 개발자가 직접 한다.** 커밋 형식은 `AGENTS.md`를 따르고 커밋 후 `git log -1 --pretty=full`로 형식을 확인한다.
- 실제 Provider API 호출, FCM 전송, 개발 DB 데이터 변경·Bootstrap·동기화 적용, Docker volume 삭제는 개발자 승인 없이 실행하지 않는다.
- `.env`, 인증정보, API Key를 출력·커밋·로그에 남기지 않는다. `docs/devlog/`는 요청 없이 건드리지 않는다.

## 자주 쓰는 명령

```bash
# DB
docker compose up -d

# Backend (JAVA_HOME 불일치 방지)
cd backend && env -u JAVA_HOME ./gradlew fastTest --tests '*클래스명'
cd backend && env -u JAVA_HOME ./gradlew dbTest --tests '*클래스명'
cd backend && env -u JAVA_HOME ./gradlew bootRun --args='--server.address=127.0.0.1'

# Flutter
cd app && flutter analyze
cd app && flutter test test/<관련_테스트>.dart
```

Test 그룹(`fastTest`/`dbTest`/`ciTest`/`migrationTest`)과 태그 규칙은 `development-guidelines.md#backend-테스트-그룹과-db-격리`, Flyway Migration 추가 규칙은 같은 문서의 해당 절을 따른다.

## 보고

모든 설명과 보고는 한국어로 한다. 완료 보고 형식은 `AGENTS.md`의 "완료 보고"를 따른다.
