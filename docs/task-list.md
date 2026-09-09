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

# Phase 2 - Backend Authentication

목표:

StopBell의 사용자 식별과 장기 로그인 유지에 필요한 Backend Authentication 기반을 구현하고, 이후 Alarm API가 Client 제공 userId가 아닌 인증된 User를 기준으로 동작할 수 있게 한다.

- [x] TASK-200 Authentication 및 User Identity 전략 결정
- [x] TASK-201 User Identity 모델 구현
- [x] TASK-202 RefreshToken Entity 및 Repository 구현
- [x] TASK-203 JWT Access Token 발급 및 검증 구현
- [x] TASK-204 Spring Security Authentication 구조 구현
- [x] TASK-205 Google Social Login Backend 연동
- [x] TASK-206 Refresh Token 발급, Rotation 및 재발급 구현
- [x] TASK-207 Logout 및 Refresh Token 무효화 구현
- [x] TASK-208 Authentication Test 작성

------------------------------------------------------------------------

# Phase 3 - Transit Foundation

목표:

실제 Alarm Schema와 API를 설계하기 전에 V1 대상 Transit API를 직접 조사하고 실제 응답을 확인하여, StopBell이 사용할 provider, identifier, 관측 데이터의 의미를 결정한다.

- [ ] TASK-301 대상 지역 및 Transit API 후보 조사
- [ ] TASK-302 API 약관, rate limit, identifier 안정성, 검색 기능, update frequency 확인
- [ ] TASK-303 실제 Transit API 호출 PoC 및 Bus Route / Bus Stop / 실시간 데이터 응답 구조 확인
- [ ] TASK-304 V1 Transit provider 및 identifier 전략 결정
- [ ] TASK-305 도착/통과 판단에 필요한 Transit 관측 모델과 Alarm Transit Target 계약 결정

TASK-303은 Production Transit Client를 구현하는 Task가 아니다. 실제 Provider 응답에서 route identifier, stop identifier, direction, stop sequence, vehicle identifier, arrival information, vehicle location, provider namespace와 검색 가능 여부를 확인한다. 필요한 값은 조사 결과를 보고 판단하며, TASK-304와 TASK-305 전에는 `routeId + stopId` 또는 다른 식별자 구조를 확정하지 않는다.

------------------------------------------------------------------------

# Phase 4 - Alarm Backend

목표:

Phase 3에서 확정된 Transit provider 및 identifier 전략을 기준으로 인증된 User가 실제 V1 Alarm을 생성하고 관리할 수 있는 Backend API를 구현한다.

- [ ] TASK-401 Alarm Transit Target Domain/Schema 설계 및 반영
- [ ] TASK-402 Alarm API Contract 및 request/response DTO 정의
- [ ] TASK-403 Alarm 생성 API 구현
- [ ] TASK-404 Alarm 목록 조회 API 구현
- [ ] TASK-405 Alarm 상세 조회 API 구현
- [ ] TASK-406 Alarm 삭제 API 구현
- [ ] TASK-407 Alarm 활성화 API 구현
- [ ] TASK-408 Alarm 비활성화 API 구현
- [ ] TASK-409 API validation 및 Error response 처리
- [ ] TASK-410 Alarm API Test 작성

TASK-401은 현재 Alarm의 공통 정보에 Phase 3에서 실제로 필요하다고 확인된 Transit Target 정보만 추가한다. Schema 변경이 필요하면 Flyway Migration도 이 Task에 포함한다. 미래 확장만을 이유로 Transit-specific 필드나 provider abstraction을 추가하지 않는다.

모든 Alarm API는 Phase 2 Authentication의 인증된 StopBell User를 기준으로 소유권을 처리한다. Client Request Body 또는 Query Parameter의 `userId`를 받지 않으며, 생성·조회·수정·삭제 모두 해당 User 소유 Alarm만 처리한다.

------------------------------------------------------------------------

# Phase 5 - Transit Integration

목표:

Phase 3에서 결정한 실제 Provider를 Backend에 연결하고, Phase 4의 Alarm을 실제 Transit 데이터로 평가할 수 있게 한다.

- [ ] TASK-501 Transit provider client interface 정의
- [ ] TASK-502 provider response DTO 정의
- [ ] TASK-503 Transit API Client 구현
- [ ] TASK-504 StopBell Transit DTO 및 `TransitEvent` 변환 구현
- [ ] TASK-505 Bus Route 검색 구현
- [ ] TASK-506 Bus Stop 조회 구현
- [ ] TASK-507 Transit metadata persistence / MyBatis 필요성 결정 및 필요 시 구현
- [ ] TASK-508 Alarm grouping 조회 전략 결정 및 구현
- [ ] TASK-509 Alarm Evaluation Logic 구현
- [ ] TASK-510 Scheduler 실행 모델 결정 및 구현
- [ ] TASK-511 Transit API failure를 `UNKNOWN` 상태로 처리
- [ ] TASK-512 Transit Integration Test 작성

Provider API가 Route 검색과 Route별 Stop 조회를 제공하고 Local DB 저장의 명확한 이유가 없다면 Transit 검색을 위해 MyBatis를 도입하지 않는다. Static metadata 저장, 검색 성능, rate limit 절감, grouping query 등 실제 필요가 확인되면 TASK-507 또는 TASK-508에서 적절한 persistence 및 query 방식을 결정한다. JPA/MyBatis 사용 자체를 포트폴리오 목적으로 강제하지 않는다.

------------------------------------------------------------------------

# Phase 6 - Flutter Client

목표:

Backend Authentication, Transit, Alarm 기능이 준비된 상태에서 Flutter Client를 실제 Backend API와 연결하고 V1 Alarm 생성·관리 흐름을 구현한다.

- [ ] TASK-601 Flutter Google Login 및 Backend 인증 연동
- [ ] TASK-602 Access/Refresh Token Secure Storage 및 인증 상태 복구 구현
- [ ] TASK-603 인증 API Client 및 Access Token 적용 구현
- [ ] TASK-604 Access Token 만료 시 Refresh Token Rotation 연동
- [ ] TASK-605 Flutter Logout 구현
- [ ] TASK-606 Flutter Bus Route 검색 연동
- [ ] TASK-607 Flutter Bus Stop 조회 및 선택 구현
- [ ] TASK-608 Flutter Alarm 생성 및 관리 화면/API 연동
- [ ] TASK-609 Flutter Client Authentication / Transit / Alarm 연동 Test 보강

Flutter는 Backend API를 통해 Transit을 선택하고 Alarm을 관리한다. Transit monitoring 비즈니스 로직은 Flutter에 구현하지 않는다.

------------------------------------------------------------------------

# Phase 7 - Notification

목표:

Alarm 조건 충족 시 실제 기기에 중복 없이 Push notification을 전송한다.

- [ ] TASK-701 FCM iOS 지원 적합성 및 Device / Push Token lifecycle 최종 결정
- [ ] TASK-702 Device Domain/Schema 및 Device registration contract 정의
- [ ] TASK-703 Device registration API 구현
- [ ] TASK-704 Flutter Push permission, FCM Token 획득, Backend registration 및 Token 갱신 연동
- [ ] TASK-705 Push provider client 구현
- [ ] TASK-706 Notification Service 구현
- [ ] TASK-707 NotificationHistory 저장 연동
- [ ] TASK-708 Duplicate Prevention 전략 결정 및 구현
- [ ] TASK-709 Notification failure 처리 구현
- [ ] TASK-710 실제 기기 Push notification 검증
- [ ] TASK-711 Notification Test 작성

현재 Device Schema는 후보일 뿐이다. TASK-701에서 실제 FCM lifecycle을 확인한 뒤 TASK-702에서 필요한 최소 Schema를 결정하며, 추측성 field를 미리 추가하지 않는다.

------------------------------------------------------------------------

# Phase 8 - Quality and Operations

목표:

사용자에게 실패를 일으킬 수 있는 동작을 검증하고, 운영에 필요한 최소 품질을 갖춘다.

- [ ] TASK-801 Alarm Evaluation Test 보강
- [ ] TASK-802 Duplicate Prevention Test 보강
- [ ] TASK-803 외부 provider response mapping Test 보강
- [ ] TASK-804 API validation/error handling Test 보강
- [ ] TASK-805 구조화된 Logging 추가
- [ ] TASK-806 Secret 관리 검토
- [ ] TASK-807 Health check 검증
- [ ] TASK-808 Dockerize Backend
- [ ] TASK-809 CI build/test 구성
- [ ] TASK-810 실제 환경에서 notification delay 측정
- [ ] TASK-811 server restart 안전성 검증

Future Consideration:

- CD 도입은 수동 배포 흐름을 이해한 뒤 검토한다.
- Redis, queue/worker, multiple backend instance는 측정된 문제가 있을 때만 검토한다.
