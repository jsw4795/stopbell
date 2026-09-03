# Task List

## Purpose

이 문서는 Codex와 개발자가 StopBell을 단계적으로 구현하기 위한 Task 순서를 정의한다.

Task 상태는 실제 완료 여부를 기준으로 관리한다. 실제 구현 전에 `requirements.md`, `architecture.md`, `database.md`, `domain-model.md`, 관련 ADR을 확인한다.

------------------------------------------------------------------------

# Task Completion Workflow

모든 Task 완료 시 다음 절차를 따른다.

1. 현재 Task에 필요한 구현 또는 문서 작업을 완료한다.
2. 관련 Test, 실행 확인 등 필요한 검증을 수행한다.
3. 검증이 성공하고 Task가 실제로 완료된 경우에만 `task-list.md`의 해당 Task 상태를 변경한다.

   Before:

   ```text
   - [ ] TASK-XXX
   ```

   After:

   ```text
   - [x] TASK-XXX
   ```

4. 필요한 관련 문서 업데이트 여부를 확인하고, 변경된 `task-list.md`를 해당 Task의 동일 Commit에 포함한다.
5. Commit 전에 변경사항과 Task 완료 표시를 확인한다.

원칙:

- Task 완료 여부는 Git Commit 여부가 아니라 실제 작업 완료 여부를 기준으로 판단한다.
- 실제 완료되지 않았거나 일부만 구현된 Task는 체크하지 않는다.
- 문서 결정 Task와 구현 Task를 구분한다.
- 완료된 Task는 반드시 `task-list.md`에 표시하며, 완료 표시 없는 Task 완료 Commit을 만들지 않는다.
- `task-list.md` 업데이트를 별도의 나중 작업으로 미루지 않는다.

목적:

- Git History와 Task 진행 상태 일치
- 프로젝트 진행 상황 추적 가능
- Codex와 개발자가 동일한 진행 상태 공유

------------------------------------------------------------------------

# Phase 0 - Foundation

목표:

프로젝트 기본 환경과 최소 실행 구조를 준비한다.

- [x] TASK-001 Repository Structure 생성
- [x] TASK-002 Spring Boot 프로젝트 생성
- [x] TASK-003 Flutter 프로젝트 생성
- [x] TASK-004 MySQL 8.4 LTS Docker Compose Development 환경 구성
- [x] TASK-005 JPA / MyBatis 설정
- [x] TASK-006 기본 Application configuration 분리
- [x] TASK-007 Health endpoint 구현
- [x] TASK-008 `.env.example` 또는 동등한 설정 placeholder 작성

------------------------------------------------------------------------

# Phase 1 - Domain Foundation

목표:

JPA를 사용하는 핵심 Domain과 Repository 기반 상태 관리를 준비한다.

- [x] TASK-101 User Entity 구현
- [x] TASK-102 Alarm Entity 구현
- [x] TASK-103 NotificationHistory Entity 구현
- [x] TASK-104 User JPA Repository 구현
- [x] TASK-105 Alarm JPA Repository 구현
- [x] TASK-106 NotificationHistory JPA Repository 구현
- [x] TASK-107 Alarm status와 상태 전이 규칙 구현
- [x] TASK-108 Database migration 또는 schema 관리 방식 결정
- [x] TASK-109 Entity 및 Repository Test 작성

------------------------------------------------------------------------

# Phase 2 - Authentication

목표:

StopBell의 사용자 식별과 장기 로그인 유지에 필요한 최소 Authentication 기반을 구현하고, 이후 Alarm API가 Client 제공 userId가 아닌 인증된 User를 기준으로 동작할 수 있게 한다.

- [x] TASK-200 Authentication 및 User Identity 전략 결정
- [ ] TASK-201 User Identity 모델 구현
- [ ] TASK-202 RefreshToken Entity 및 Repository 구현
- [ ] TASK-203 JWT Access Token 발급 및 검증 구현
- [ ] TASK-204 Spring Security Authentication 구조 구현
- [ ] TASK-205 Google Social Login Backend 연동
- [ ] TASK-206 Refresh Token 발급, Rotation 및 재발급 구현
- [ ] TASK-207 Logout 및 Refresh Token 무효화 구현
- [ ] TASK-208 Flutter Google Login 및 Token Secure Storage 구현
- [ ] TASK-209 Authentication Test 작성

------------------------------------------------------------------------

# Phase 3 - Alarm Feature

목표:

사용자가 Alarm을 생성하고 관리할 수 있는 최소 API와 화면 흐름을 만든다.

- [ ] TASK-301 Alarm 생성 API 정의 및 구현
- [ ] TASK-302 Alarm 목록 조회 API 정의 및 구현
- [ ] TASK-303 Alarm 상세 조회 API 정의 및 구현
- [ ] TASK-304 Alarm 삭제 API 정의 및 구현
- [ ] TASK-305 Alarm 활성화 API 정의 및 구현
- [ ] TASK-306 Alarm 비활성화 API 정의 및 구현
- [ ] TASK-307 Alarm request/response DTO 정의
- [ ] TASK-308 API validation 및 Error response 처리
- [ ] TASK-309 Alarm API Test 작성
- [ ] TASK-310 Flutter Alarm 생성 및 활성 알림 화면 구현

Transit provider가 확정되기 전에는 실제 Bus Route / Bus Stop 선택 흐름을 임의로 구현하지 않는다.

------------------------------------------------------------------------

# Phase 4 - Transit Integration

목표:

실제 Transit 데이터를 조회하고 Alarm 조건을 평가할 수 있게 한다.

- [ ] TASK-401 대상 지역과 Transit API 조사
- [ ] TASK-402 API 약관, rate limit, identifier, update frequency 확인
- [ ] TASK-403 Transit provider client interface 정의
- [ ] TASK-404 Transit API Client 구현
- [ ] TASK-405 provider response DTO 정의
- [ ] TASK-406 StopBell Transit DTO와 `TransitEvent` 변환 구현
- [ ] TASK-407 Bus Route 검색 Query 구현
- [ ] TASK-408 Bus Stop 조회 Query 구현
- [ ] TASK-409 MyBatis Transit Query Mapper 구현
- [ ] TASK-410 Alarm 그룹 조회 Query 구현
- [ ] TASK-411 Alarm Evaluation Logic 구현
- [ ] TASK-412 Scheduler 실행 모델 결정 및 구현
- [ ] TASK-413 Transit API failure를 `UNKNOWN` 상태로 처리
- [ ] TASK-414 Transit Integration Test 작성

Undecided:

- Transit provider
- grouping key
- polling frequency
- arrived 또는 passed 판단 기준
- Transit metadata persistence

------------------------------------------------------------------------

# Phase 5 - Notification

목표:

Alarm 조건 충족 시 실제 기기에 중복 없이 Push notification을 전송한다.

- [ ] TASK-501 Device registration contract 정의
- [ ] TASK-502 Device registration API 구현
- [ ] TASK-503 Push token lifecycle 정의
- [ ] TASK-504 FCM Integration 조사 및 설정
- [ ] TASK-505 Push provider client 구현
- [ ] TASK-506 Notification Service 구현
- [ ] TASK-507 NotificationHistory 저장 구현
- [ ] TASK-508 Duplicate Prevention 전략 결정 및 구현
- [ ] TASK-509 Notification failure 처리 구현
- [ ] TASK-510 실제 기기 Push notification 검증
- [ ] TASK-511 Notification Test 작성

Undecided:

- FCM의 iOS 지원이 제품 요구사항에 충분한지 여부
- Device와 User의 최종 연결 방식
- 정확한 transaction 및 retry 전략

------------------------------------------------------------------------

# Phase 6 - Quality and Operations

목표:

사용자에게 실패를 일으킬 수 있는 동작을 검증하고, 운영에 필요한 최소 품질을 갖춘다.

- [ ] TASK-601 Alarm Evaluation Test 보강
- [ ] TASK-602 Duplicate Prevention Test 보강
- [ ] TASK-603 외부 provider response mapping Test 보강
- [ ] TASK-604 API validation/error handling Test 보강
- [ ] TASK-605 구조화된 Logging 추가
- [ ] TASK-606 Secret 관리 검토
- [ ] TASK-607 Health check 검증
- [ ] TASK-608 Dockerize Backend
- [ ] TASK-609 CI build/test 구성
- [ ] TASK-610 실제 환경에서 notification delay 측정
- [ ] TASK-611 server restart 안전성 검증

Future Consideration:

- CD 도입은 수동 배포 흐름을 이해한 뒤 검토한다.
- Redis, queue/worker, multiple backend instance는 측정된 문제가 있을 때만 검토한다.
