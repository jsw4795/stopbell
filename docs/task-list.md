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

상태: 완료

- [x] TASK-301 대상 지역 및 Transit API 후보 조사
- [x] TASK-302 API 약관, rate limit, identifier 안정성, 검색 기능, update frequency 확인
- [x] TASK-303 실제 Transit API 호출 PoC 및 Bus Route / Bus Stop / 실시간 데이터 응답 구조 확인
- [x] TASK-304 V1 Transit provider 및 identifier 전략 결정
- [x] TASK-305 도착/통과 판단에 필요한 Transit 관측 모델과 Alarm Transit Target 계약 결정

Transit Foundation은 다음 순서로 진행한다.

```text
TASK-301 서울 + 경기 지원 범위와 API 후보 선정
        ↓
TASK-302 약관 / rate limit / identifier / 검색 / update frequency 조사
        ↓
TASK-303 실제 TAGO 등 API 호출 PoC 및 서울·경기 응답 구조 확인
        ↓
TASK-304 V1 Provider 및 identifier 전략 최종 결정
        ↓
TASK-305 Transit 관측 모델 및 Alarm Transit Target 계약 결정
```

TASK-301은 서울특별시와 경기도를 V1 초기 지원 범위로 정하고, 국토교통부 TAGO를 1순위 후보로 기록한다. TAGO는 이 Task에서 최종 Provider로 채택하지 않는다. 경기도 버스정보 API는 경기 데이터의 비교 또는 fallback 후보이며, 서울시 버스 API는 기존 API가 존재하지만 신규 프로젝트의 인증키 발급 제약으로 우선순위가 낮은 기능적 후보이다.

TASK-303은 Production Transit Client를 구현하는 Task가 아니다. 실제 Provider 응답에서 route identifier, stop identifier, direction, stop sequence, vehicle identifier, arrival information, vehicle location, provider namespace와 검색 가능 여부를 확인한다. 필요한 값은 조사 결과를 보고 판단하며, TASK-304와 TASK-305 전에는 `routeId + stopId` 또는 다른 식별자 구조를 확정하지 않는다.

TASK-305는 Provider raw DTO와 provider-neutral `TransitObservation`을 분리하고, Alarm Transit Target의 external reference/occurrence metadata/options, ARRIVED·PASSED·UNKNOWN, before/after Event, baseline 및 Vehicle tracking lifecycle을 계약으로 확정했다. 실제 Schema/DTO/Evaluation/Scheduler는 각각 TASK-401/402/504/509/510에서 구현한다.

------------------------------------------------------------------------

# Phase 4 - Alarm Backend

목표:

Phase 3에서 확정된 Transit provider 및 identifier 전략을 기준으로 인증된 User가 실제 V1 Alarm을 생성하고 관리할 수 있는 Backend API를 구현한다.

- [x] TASK-401 Alarm Transit Target Domain/Schema 설계 및 반영
- [x] TASK-402 Alarm API Contract 및 request/response DTO 정의
- [x] TASK-403 Alarm 생성 API 구현
- [x] TASK-404 Alarm 목록 조회 API 구현
- [x] TASK-405 Alarm 상세 조회 API 구현
- [x] TASK-406 Alarm 삭제 API 구현
- [x] TASK-407 Alarm 활성화 API 구현
- [x] TASK-408 Alarm 비활성화 API 구현
- [x] TASK-409 API validation 및 Error response 처리
- [ ] TASK-410 Alarm API Test 작성

TASK-401은 Alarm lifecycle을 `INACTIVE`/`ACTIVE`/`FOLLOW_UP` 상태로 전환하고 ARRIVED 후 ONE_STOP_AFTER 전용 runtime을 영속했다. BUS 전용 장기 설정은 공유 PK `BusAlarmTarget`으로 분리하고 Route/Stop identity, target occurrence order, display/GPS/cityCode 및 필요한 인접 occurrence snapshot을 V6 Migration과 JPA Domain에 반영했다. Route/Stop external reference만으로 uniqueness를 강제하지 않으며 ACTIVE 중 차량별 tracking과 API/Evaluation/Scheduler는 후속 Task에 남겼다.

TASK-507은 client 최소 입력과 metadata DB lookup 기반 Alarm Create를 설계하기 위해 TASK-402/403보다 먼저 완료했다. Bus static metadata는 current `BusRoute`/`BusStop`/`BusRouteStopOccurrence`로 관리하고 Alarm 생성 시에는 기존 `BusAlarmTarget` snapshot으로 복사한다. TASK-402는 Route Stop 조회 응답의 `id`인 `BusRouteStopOccurrence.id`를 Alarm 생성의 `targetStopOccurrenceId`로 확정했다.

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
- [x] TASK-507 Transit metadata persistence / MyBatis 필요성 결정 및 구현
- [ ] TASK-508 Alarm grouping 조회 전략 결정 및 구현
- [ ] TASK-509 Alarm Evaluation Logic 구현
- [ ] TASK-510 Scheduler 실행 모델 결정 및 구현
- [ ] TASK-511 Transit API failure를 `UNKNOWN` 상태로 처리
- [ ] TASK-512 Transit Integration Test 작성

TASK-507은 서울 T Data CSV full import와 경기 TAGO throttled full sync를 위한 local metadata persistence 필요성을 확인해 `BusRoute`/`BusStop`/`BusRouteStopOccurrence` Schema, JPA Repository, source-neutral route diff sync를 구현했다. 같은 Route/Stop identity의 metadata는 내부 ID를 유지하며, 의미가 바뀐 occurrence는 새 ID를 받는다. Provider 전체 fetch 성공 시에만 없는 Route와 orphan Stop을 cleanup한다. metadata CRUD/reconciliation은 JPA로 충분하므로 MyBatis를 도입하지 않았고, Route/Stop 검색·Alarm grouping·대량 조회 성능에서 실제 SQL 제어 필요성이 확인될 때 재검토한다.

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
- [ ] TASK-812 Analytics Event 및 장기 통계 데이터 보존 전략 결정 및 최소 구현

TASK-812에서는 운영 Domain 데이터 lifecycle과 독립적으로 장기 보존할 통계·분석 데이터를 결정하고, 필요한 최소 구현을 수행한다. Alarm 삭제 시 `BusAlarmTarget`, `NotificationHistory` 같은 종속 운영 데이터는 함께 삭제할 수 있지만, 서비스 사용 패턴과 품질 분석에 필요한 데이터는 Alarm 삭제 여부와 독립적으로 보존할 수 있어야 한다. 장기 보존 데이터는 별도의 Analytics/Event Logging 책임으로 분리하며, `NotificationHistory`의 Alarm lifecycle 종속 정책과 충돌하지 않는다.

후보 Event는 `ALARM_CREATED`, `ALARM_ACTIVATED`, `ALARM_DEACTIVATED`, `ALARM_DELETED`, `NOTIFICATION_SUCCESS`, `NOTIFICATION_FAILURE` 등이지만, 실제 V1 기능이 대부분 완성된 뒤 필요한 통계, Event 범위, 익명화·최소화 수준, 보존 기간, 원본 Event와 집계 데이터의 보존 범위를 결정한다. 이 시점에는 추측성 Analytics Schema를 미리 확정하지 않으며, 외부 Provider 사용자 식별자, Refresh Token, Push Token, 정확한 개인 식별 정보 등 장기 통계에 불필요한 데이터는 복제하지 않는 것을 기본 원칙으로 한다.

TASK-805의 구조화된 Logging은 장애 추적, 서버 동작 관찰, request/error 운영 분석을 위한 것으로 로그 보존 정책에 따라 삭제될 수 있다. TASK-812의 Analytics/Event는 장기 통계, 서비스 사용 패턴, 기능 사용률, Notification 성공·실패 분석을 위한 별도 책임이며, 운영 Domain 데이터 삭제와 독립적인 보존을 검토한다.

Future Consideration:

- CD 도입은 수동 배포 흐름을 이해한 뒤 검토한다.
- Redis, queue/worker, multiple backend instance는 측정된 문제가 있을 때만 검토한다.
