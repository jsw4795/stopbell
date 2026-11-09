# StopBell — AI Chat Handoff

## 프로젝트와 문서 진입점

[StopBell](https://github.com/jsw4795/stopbell)은 선택한 버스의 운행을 추적해 실제 내리고 싶은 목표 정류장 전에 하차 준비 알림을 제공하며, 기존 버스 도착·통과 Alarm도 유지한다. Backend는 Java 21, Spring Boot 4, Spring Data JPA, MySQL, Flyway, Gradle, Testcontainers, FCM을 사용한다. Client는 Flutter/Dart, iOS 우선, Firebase Messaging 및 Apple 로그인을 사용한다. 교통 메타데이터 개선은 Flutter UI/UX 전면 리뉴얼의 선행 작업이며 Backend 완성이 최종 목표가 아니다.

이 문서는 안내서이지 설계 계약의 소유자가 아니다. [개발 가이드라인](development-guidelines.md#3-문서-책임)의 책임 경계를 따르고, 구현과 문서가 충돌하면 임의로 선택하지 말고 근거와 판단이 필요한 점을 개발자에게 보고한다. 기존 ADR의 과거 결정과 최신 변경, 제안과 확정을 구분한다.

## 새 채팅 시작 절차

1. 저장소와 이 문서를 확인한다.
2. [로드맵](roadmap.md), [Task List](task-list.md), [개발 가이드라인](development-guidelines.md)을 순서대로 확인한다.
3. 전체 위치를 파악한 뒤 현재 작업에 필요한 [요구사항](requirements.md), [Architecture](architecture.md), [API](api.md), 관련 [ADR](adr/README.md)과 실제 코드만 선택해 읽는다. 모든 문서의 전문을 출력할 필요는 없다.
4. 최신 Git 커밋, HEAD, 로컬 미커밋 변경과 문서를 비교하고 현재·다음 작업을 정리한다. GitHub만 접근 가능하다면 원격에 Push되지 않은 작업을 확인할 수 없음을 알리고 사용자가 전달하는 Codex 결과를 별도로 검토한다.
5. Flutter UI-0~UI-8 전체 목표를 유지하고 이후 전달되는 최신 작업 결과를 반영한다.

## 현재 위치 — 2026-11-10 기준

- 공식 노선 유형 Backend는 `d9300c2`에서 구현됐으며 기존 개발 DB 유형 보강도 완료됐다. HEAD와 작업 트리는 매번 다시 확인한다.
- 아래 TAGO·GBIS 수집과 최초 DB 적용 결과는 개발자가 전달한 기록이며, 당시 인수인계에서는 DB와 원본·백업 복원을 재검증하지 않았다. 이후 공식 유형 보강의 백업·DB 검증은 아래에 별도로 기록한다. [로드맵](roadmap.md)과 [Task List](task-list.md)의 이전 진행 기록은 당시 상태로 읽는다.
- TASK-710 및 TASK-908은 미완료다. 서울·TAGO의 일부 실제 iPhone 결과가 있으나 전체 현장 검증은 남아 있다. TAGO는 기본 OFF 실험이며 원본 최신성·동일 운행 보장과 운영 적용은 별개다.

### 완료된 TAGO·GBIS 수집과 개발 DB 적용

2026-11-09 수집한 두 Bundle의 원본·Manifest 검증과 오프라인 재생이 완료됐다. TAGO는 경기 31개 cityCode, Route 2,168개, Stop 33,564개, Route-Stop Occurrence 148,817개다. GBIS는 공식 데이터 버전 `20261109`, Raw Route 3,159개, Raw Occurrence 198,263개다. 확인된 로컬 경로는 다음과 같다.

- TAGO: `~/GPT_Codex_Project/stopbell-collections/2026-11-09-live/02cf0436-9b59-4afb-a796-7c41737a1218`
- GBIS: `~/GPT_Codex_Project/stopbell-collections/2026-11-09-live/90f6ce9d-c9d7-4a2d-a3ec-238961d8f1e7`

2026-11-10 개발 DB 적용에서 사전 검토 Run ID `1`, 승인 ID `1`, 실제 적용 Run ID `2`를 사용했다. 승인 최종 상태는 `APPLIED`, 적용 실행 상태는 `COMPLETED`다. 위험 분석은 Route 제거 후보 16개, Occurrence 제거 후보 6,229개, Stop 정리 후보 36개였고 최신 Snapshot 기준으로 적용했다. 후보가 모두 공식 폐지됐다는 검증은 아니다. 적용 전 TAGO 알람과 활성 추적 세션은 각 0개, 과거 완료 세션은 3개였으며 그중 제거 후보 노선 참조는 0개였다.

적용 후 검증값은 TAGO 정규화 Route 2,168·Stop 33,564·Occurrence 148,817, TAGO Raw Route 2,168·Occurrence 148,817, GBIS Raw Route 3,159·Occurrence 198,263이며 고아 TAGO Stop과 연결되지 않은 TAGO Raw는 각각 0이다. GBIS `source_version`은 `20261109`, TAGO의 `NULL`은 현재 구현상 정상이다. 이는 Snapshot의 개발 DB 반영 검증이며 전체 노선의 실제 운행 정확성이나 운영 적용 검증은 아니다.

적용 전 백업 `~/GPT_Codex_Project/stopbell-db-backups/stopbell-20261110-001404.sql.gz`는 보존한다. gzip 무결성 검사는 통과했으나 실제 복원 테스트는 하지 않았다.

2026-11-10 개발 DB에 공식 유형만 보강했다. 사용자 승인 후 동일 트랜잭션에서 Preview와 후보별 검증을 다시 수행하고 `bus_routes.route_type` 2,809건(서울 705, TAGO 2,104)을 갱신·commit했다. 전체 Route 2,888개 중 서울 원본 공란 15개와 GBIS 미지원 코드 `50` 64개는 NULL로 유지했다. Route의 다른 필드와 비대상 테이블을 트랜잭션 전후 지문 및 commit 후 주요 테이블 체크섬으로 확인했고 Raw·Stop·Occurrence·Alarm·BoardingSession·동기화 완료 시각은 변경하지 않았다. 적용 전 백업 `~/GPT_Codex_Project/stopbell-db-backups/stopbell-route-type-preflight-20261110-014037.sql.gz`는 SHA-256 `ad6e45a3362c677ff69d6b825b4b719370c8e2b3cfbb82e899a7a6f0b94185fc`이며 gzip과 격리 MySQL 복원을 검증했다.

**서울버스는 이번에 최신화하지 않았다.** `bus_metadata_sync_states`에서 TAGO만 최신 동기화 시각으로 바뀌었고 `SEOUL_BUS`는 기존 날짜를 유지했다. 서울 기존 데이터가 있어도 2026년 11월 최신 CSV 적용을 뜻하지 않는다.

### 현재 우선순위와 남은 작업

1. 공식 노선 유형 저장과 검색 API 구현 및 기존 개발 DB 유형 보강은 완료됐다. 서울 원본 공란과 GBIS 코드 `50`은 미분류로 유지하며, 미확인 유형을 노선번호로 추정하지 않는다.
2. 서울버스 CSV 최신성을 확인하고 DB 비교·안전한 전체 최신화를 별도로 수행한다. 서울 유형만 보강한 것은 서울 전체 최신화가 아니다. Phase 3-B는 진행 중이다.
3. 후속으로 수집 폴더 구조와 필요한 수집기 편의 기능을 개선한다. 목표 구조는 `stopbell-collections/YYYY-MM-DD/{TAGO,GBIS}/HHmmss/<Bundle UUID>/`, 날짜별 `diagnostics/`, 최상위 `README.md`, `incomplete/`, `metadata-admin-archive/`다. UUID·Manifest 무결성과 적용 이력·아카이브를 보호하고 기존 Bundle 이동 전후 검증한다.
4. 서울버스 최신화 이후 Flutter UI-2~UI-8을 이어서 진행하고 통합 검증한다. 공식 유형 색상 UI는 아직 미적용이며 기존 Phase 번호와 순서는 유지한다.

### Flutter UI/UX 전면 리뉴얼 유지

UI-0 공통 라이트·다크 디자인 시스템과 UI-1 세로 노선 타임라인·정류장 선택의 기존 구현을 재사용하고 남은 부분을 보완한다. 전체 리뉴얼은 미완료다. 순서와 세부 계약은 [UI 리뉴얼 로드맵](roadmap.md#flutter-uiux-전면-리뉴얼-로드맵)을 따른다.

- UI-0: 공통 라이트·다크 디자인 시스템
- UI-1: 세로형 노선 타임라인 및 정류장 선택
- UI-2: 공식 노선 유형 및 검색 화면
- UI-3: 차량 후보 선택 및 탑승 확정
- UI-4: 하차 추적 화면
- UI-5: 홈 및 알람 관리
- UI-6: 로그인·설정·계정
- UI-7: 세로 노선도에서 실시간 다중 차량 위치 표시; 별도 Backend API가 필요한 필수 후속 목표
- UI-8: 전체 통합 검증

## ChatGPT와 Codex 역할

ChatGPT는 개발 계획과 현재·다음 Phase, 범위·아키텍처, 기존 기능 보호, UI/UX 방향과 인수인계를 관리하고 Codex 프롬프트를 작성하며 GitHub 커밋을 검토하는 기술 파트너다. Codex는 실제 구현·테스트 작성과 관련 검증, 문서 동기화, 로컬 커밋 및 결과 보고를 수행한다.

## 응답 형식

사용자가 다음 구현 프롬프트를 요청할 때마다 다음 표를 제공한다. 모델과 Reasoning Effort는 난이도와 사용량을 고려하고 근거 없는 성능·사용량 우열을 단정하지 않는다. 기존/새 Codex 채팅방의 추천 이유를 매번 설명한다.

| 항목 | 추천 |
| --- | --- |
| 모델 | 작업별 추천 모델 |
| Reasoning Effort | 작업 난이도에 맞는 수준 |
| Codex 채팅방 | 기존 채팅방 유지 / 새 채팅방 및 이유 |
| 작업 범위 | 해당 Phase |
| 로컬 테스트 | 직접 관련 및 필요한 인접 회귀 |
| 전체 Backend 테스트 | 기본적으로 실행 금지 |
| Git | 로컬 커밋 |
| Push | 사용자가 직접 진행 |
| CI 확인 | 실패 보고나 명시적 요청이 있을 때만 |

작업 프롬프트나 주요 진행 보고에는 아래와 같은 전체 개발 진행 상황표를 제공한다. 완료 작업을 누락하지 않고 현재·다음을 구분하며 UI 리뉴얼을 유지한다. 기존 Phase 번호를 임의로 바꾸지 않고 새 하위 Phase는 기존 계획과 충돌 여부를 확인한 제안으로만 표시한다.

| 단계 | 작업 | 상태 |
| --- | --- | --- |
| 이전 Phase | 완료 작업 | ✅ 완료 |
| 현재 Phase | 진행 작업 | 🔄 진행 중 |
| 다음 Phase | 다음 작업 | ⏳ 다음 |
| 후속 Phase | UI/UX 리뉴얼을 포함한 예정 작업 | ⏳ 예정 |

## 검토·검증·Git 운영

ChatGPT와 Codex는 위험도에 비례해 검증한다. 인증정보·개인정보, 실제 다운로드 URL과 DB 데이터 무결성 등 핵심 안전 검증은 유지하면서 선택적·미사용 데이터의 과도한 검증과 불필요한 방어 코드·테스트·추가 Phase를 지양한다. 정상 동작과 개발 속도를 중시하며 구체적인 기준과 GBIS 사례의 현재 구현·향후 원칙 구분은 [개발 가이드라인](development-guidelines.md#위험도-기반-검증과-개발-우선순위)을 따른다.

사용자가 Push를 알리면 해당 커밋과 원격 main 최신 HEAD, 변경 파일과 주요 구현을 실제로 확인한 뒤 완료 여부와 다음 Phase 진행 가능 여부를 판단한다. 확인하지 않은 Push나 검토를 완료로 말하지 않는다. CI는 실패 보고 또는 명시적 요청이 있을 때만 확인하고 CI 완료를 기다리느라 다음 작업을 중단하지 않는다.

직접 관련 테스트와 필요한 인접 회귀를 우선하며 전체 Backend·Flutter 테스트는 기본적으로 실행하지 않는다. 예외와 보고 기준은 [개발 가이드라인](development-guidelines.md#테스트-실행-범위)을 따른다. 문서 전용 작업의 불필요한 코드 테스트는 생략하고 실행·미실행을 구분한다. 작업 전 Git 상태를 확인하고 사용자 변경을 보존하며 관련 파일만 수정·로컬 커밋한다. Push는 사용자가 직접 수행한다.

**Flyway Migration 추가 시:** 최신 버전과 최신까지의 적용 개수는 숫자로 고정하지 않고 Flyway가 인식한 Migration과 DB 상태를 비교한다. 과거 버전 호환성 검증은 보존한다. [상세 규칙](development-guidelines.md#flyway-migration-추가-시-필수-회귀-검증)을 따른다.

## 유지관리

주요 Phase 완료 시 `roadmap.md`와 함께, 현재 또는 다음 Phase가 바뀌면 진행 상황을 갱신한다. 전체 로드맵·중요한 계약·Flutter UI 리뉴얼 계획의 변경, 필수 미검증 항목 발견 또는 ChatGPT/Codex 운영 규칙 변경도 인수인계 내용에 영향을 줄 때 반영한다. 필요한 갱신은 관련 작업의 로컬 Commit에 함께 포함한다.

일반 Task마다 또는 단순히 ChatGPT 채팅방을 옮긴다는 이유로 갱신하지 않고 과거 로그를 누적하지 않는다. 일반 Task와 단순 버그 수정의 문서 갱신 의무는 [개발 가이드라인](development-guidelines.md#문서-갱신-시점), 상세 구현과 계약은 각 책임 문서, 이력은 Git 및 Task List를 따른다. 이 문서는 항상 자동으로 최신 상태를 보장하지 않는다.

## 새 ChatGPT 채팅방 시작 메시지

```text
StopBell 프로젝트 개발을 이어서 진행해 주세요. GitHub 최신 문서에서 docs/CHAT_HANDOFF.md를 먼저 읽고 로드맵, Task 목록과 개발 규칙을 확인해 현재 위치와 다음 작업을 정리해 주세요. Flutter UI/UX 전면 리뉴얼 전체 계획을 유지하고, 구현 프롬프트에는 Codex 추천 설정표와 개발 진행 상황표를 사용해 주세요. 최신 Codex 작업 결과는 제가 별도로 전달하겠습니다.
```
