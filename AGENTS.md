# StopBell Codex 작업 지침

## Source of Truth

`/docs`는 StopBell 프로젝트의 Source of Truth이다. 작업 전에는 현재 Task와 변경 영역에 관련된 문서를 읽고, 필요에 따라 `docs/task-list.md`와 `docs/adr/`도 확인한다.

문서와 구현이 충돌하면 코드를 기준으로 문서를 자동 변경하지 않는다. 명시된 설계를 먼저 확인하고, 어느 쪽이 맞는지 판단이 필요하면 임의로 결정하지 말고 개발자에게 보고한다. 문서에 없는 기술, 정책, 설계도 임의로 추가하지 않는다.

상세 규칙은 다음 공식 문서가 소유한다.

- 요구사항: `docs/requirements.md`
- Architecture와 기술 구성: `docs/architecture.md`
- Domain Model: `docs/domain-model.md`
- Database/Persistence: `docs/database.md`
- Package 구조: `docs/package-structure.md`
- Coding/Test Convention: `docs/development-guidelines.md`
- Local 실행 환경: `docs/local-development.md`
- API 계약: `docs/api.md`
- 진행 계획과 Task workflow: `docs/roadmap.md`, `docs/task-list.md`
- 주요 결정의 근거와 Trade-off: `docs/adr/`

## Task 범위

현재 요청된 Task에 필요한 최소 변경만 한다. 미래 Task 선행 구현, 관련 없는 Refactoring, 불필요한 Dependency/Architecture 변경, Task와 무관한 대규모 문서 정리는 하지 않는다.

구현은 기존 Architecture와 Code Style을 따른다. 세부 Convention은 `docs/development-guidelines.md`를 따르며, 이 파일에 중복 작성하지 않는다.

## 문서 일관성과 devlog

현재 Task에서 사용자가 명시적으로 새 설계 결정을 확정했고 그로 인해 기존 문서가 달라지면, 구현과 함께 해당 Owner 문서를 최소 범위로 동기화한다. 코드와 문서 변경은 같은 Task의 결과물이며, 이미 정확한 문서는 수정하지 않는다. 같은 상세 규칙을 여러 문서에 복사하지 않는다.

현재 Task의 새 결정이 아닌 기존 문서·구현 충돌을 발견하면 코드를 기준으로 문서를 자동 변경하지 않는다. 설계 판단이 필요 없는 명백한 노후화만 현재 Task와 관련 있을 때 수정할 수 있다. 문서가 명확한 설계를 정의하거나 어느 쪽이 맞는지 불분명하면 충돌과 필요한 판단을 개발자에게 보고한다. 기존 구현만으로 이를 공식 설계로 승격시키지 않는다.

사용자 지시가 기존 문서와 다를 때, 명시적인 정책 변경이면 새 결정으로 취급해 코드와 Owner 문서를 같은 Task에서 동기화한다. 의도가 불분명하면 `/docs`를 우선하고 판단을 요청한다.

Task 완료 전에는 이번 구현으로 관련 문서가 오래되지 않았는지, 새 결정이 반영됐는지, 새 문서·구현 충돌이 없는지 확인한다. 필요한 동기화가 끝나지 않으면 완료 처리하지 않는다. 각 Phase 종료 시에는 전체 `/docs`와 구현을 감사해 문서 간/구현 간 충돌, 오래된 상태, 누락된 완료 사항, 중복 규칙, 깨진 참조, Dependency·Version·Infrastructure 차이를 확인한다. 설계 판단이 필요한 항목은 감사에서도 임의로 해결하지 않는다.

`docs/devlog`은 개발자의 개인 기록이다. 명시적 요청이 없는 한 작성·수정·정리하지 않으며 Source of Truth로 사용하지 않는다.

## Dependency와 검증

기존 Dependency로 충분하면 새 Dependency를 추가하지 않는다. 추가가 필요하면 필요성, Spring Boot Dependency Management 호환성, 적절한 Scope를 확인하고 resolution 및 관련 Test를 검증한다. 편의를 위한 Library 추가나 Version 하드코딩은 피한다.

Task 완료 전에는 변경에 필요한 Test와 검증을 수행한다. 전체 Test Suite, compile, Application Context, Integration Test 등은 Task 위험도와 관련 문서에 맞게 선택한다. 검증 실패 상태에서 완료 처리하거나, Test skip·검증 약화·의미 없는 mock으로 통과시키지 않는다.

## Task와 Git workflow

Task 완료 절차는 `docs/task-list.md`를 따른다. 구현과 필요한 검증이 성공한 경우에만 해당 Task를 `[ ]`에서 `[x]`로 변경하고, 그 변경은 해당 Task의 동일 Commit에 포함한다. 미완료·부분 완료 Task를 완료 처리하지 않으며, maintenance 작업을 위해 새 Task를 임의로 추가하지 않는다.

Commit 전에는 다음을 확인한다.

```text
git status
git diff
git diff --check
```

현재 Task 관련 변경만 있고, Secret·관련 없는 파일·후속 Task 범위 침범이 없으며 필요한 `task-list.md` 변경이 포함됐는지 확인한다. 검증 실패 시 Commit하지 않는다.

Commit은 English Conventional Commit type과 한국어 description을 사용한다.

Commit message body는 변경 규모와 설명 필요성에 따라 다음 기준으로 작성한다.

- 단순하고 범위가 작은 변경은 제목 한 줄만 사용해도 된다.
- 여러 파일군이나 여러 책임을 함께 변경한 경우에는 Commit body를 작성한다.
- 설계 결정 반영, DB Migration, 테스트 보강, 문서 동기화처럼 변경 이유와 범위를 제목만으로 충분히 설명하기 어려운 경우에도 body를 작성한다.
- Body는 변경 내용을 단순 나열하기보다 주요 변경과 의도를 이해할 수 있도록 2~5개 항목의 간결한 bullet로 작성한다.
- 사소한 변경까지 억지로 body를 붙이지 않는다.
- Commit body는 한국어로 작성해도 된다.
- Commit body의 각 bullet은 간결한 작업 요약 형태로 작성하고, 명사형/작업형 표현(예: `~ 추가`, `~ 구현`, `~ 적용`, `~ 변경`, `~ 수정`, `~ 보강`, `~ 동기화`, `~ 검증`)으로 끝낸다.
- `~한다.`, `~했다.`, `~했습니다.`, `~하도록 한다.` 또는 완전한 문장 형태의 장황한 서술은 사용하지 않으며, 각 bullet 끝에 마침표를 붙이지 않는다.
- 서로 다른 bullet 사이에서도 동일한 문체를 유지하고, 기술 용어·클래스명·파일명은 기존 프로젝트 표기를 유지한다.
- Commit body의 각 bullet은 반드시 실제 개행으로 구분한다. 줄바꿈 목적으로 `\n`, `\\n` 또는 기타 escape sequence를 Commit message 본문에 그대로 사용하지 않는다.
- Git commit 명령을 직접 실행할 때도 body의 줄바꿈은 실제 newline으로 전달한다. 여러 `-m` 옵션 또는 multi-line message를 실제 개행으로 전달하는 안전한 방식을 사용한다.

Commit body formatting:

Commit body를 사용하는 경우 반드시 다음 형태를 따른다.

```text
type: 제목

- 첫 번째 변경 내용
- 두 번째 변경 내용
- 세 번째 변경 내용
```

- Subject 다음에는 정확히 한 줄의 빈 줄을 둔다.
- Commit body의 각 항목은 반드시 `- `로 시작하는 bullet 형식으로 작성한다.
- Bullet 사이에 빈 줄을 넣지 않는다.
- 각 bullet은 실제 newline으로 구분하고 literal `\n` 문자열을 사용하지 않는다.
- 기존 규칙대로 bullet은 `~ 추가`, `~ 구현`, `~ 적용`, `~ 변경`, `~ 수정`, `~ 보강`, `~ 동기화`, `~ 검증` 등의 간결한 형태로 끝내며, `~한다.`, `~했다.` 형태와 마침표를 사용하지 않는다.

잘못된 예:

```text
docs: 개발 Task Phase 구조 재정리

Backend Authentication과 Flutter Client Phase 분리
Alarm·Transit·Flutter·Notification·Quality Task 번호 재구성
Roadmap과 Task Reference 동기화
```

올바른 예:

```text
docs: 개발 Task Phase 구조 재정리

- Backend Authentication과 Flutter Client Phase 분리
- Alarm·Transit·Flutter·Notification·Quality Task 번호 재구성
- Roadmap과 Task Reference 동기화
```

Commit 후에는 반드시 `git log -1 --pretty=full`로 제목과 body 사이의 빈 줄, bullet 앞의 `- `, bullet 사이의 불필요한 빈 줄, literal `\n` 문자열을 확인한다. Subject/body formatting 오류가 하나라도 있으면 Push 전에 Commit message를 amend한다.

예시:

```text
feat: Alarm 활성화 API 구현
```

```text
feat: User Identity 모델 구현

- User에 authProvider와 providerUserId 추가
- Flyway V4로 User Identity 컬럼과 복합 Unique Constraint 추가
- Identity 저장 및 중복 제약 통합 테스트 보강
- 관련 Domain/Database/ADR 문서 동기화
```

## 설계 판단과 단순성

공식 문서 간 충돌, 문서와 구현의 설계 충돌, Architecture/Infrastructure 선택, Domain Model·API Contract·Database Policy·Authentication 변경, ADR과 충돌하는 변경은 임의로 확정하지 않는다. 현재 Task에 결정이 꼭 필요하면 선택지와 Trade-off를 개발자에게 보고한다.

실제 문제가 확인되기 전에는 Microservices, Kafka, Redis, Kubernetes, 복잡한 Event Architecture, 불필요한 추상화·Generic Framework·Base Class를 도입하지 않는다. 현재 문제를 해결하는 가장 단순하고 설명 가능한 구조를 우선한다.

## 완료 보고

완료 보고는 한국어로 작성하고 현재 Task에 중요한 내용만 간결하게 포함한다. 변경 내용과 파일, 중요한 설계 사항, 검증 결과, Dependency/문서/Task 상태 변경 여부, Commit hash와 메시지를 필요에 따라 보고한다.
