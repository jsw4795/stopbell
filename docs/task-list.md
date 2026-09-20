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
- [x] TASK-410 Alarm API Test 작성

TASK-401은 Alarm lifecycle을 `INACTIVE`/`ACTIVE`/`FOLLOW_UP` 상태로 전환하고 ARRIVED 후 ONE_STOP_AFTER 전용 runtime을 영속했다. BUS 전용 장기 설정은 공유 PK `BusAlarmTarget`으로 분리하고 Route/Stop identity, target occurrence order, display/GPS/cityCode 및 필요한 인접 occurrence snapshot을 V6 Migration과 JPA Domain에 반영했다. Route/Stop external reference만으로 uniqueness를 강제하지 않으며 ACTIVE 중 차량별 tracking과 API/Evaluation/Scheduler는 후속 Task에 남겼다.

TASK-507은 client 최소 입력과 metadata DB lookup 기반 Alarm Create를 설계하기 위해 TASK-402/403보다 먼저 완료했다. Bus static metadata는 current `BusRoute`/`BusStop`/`BusRouteStopOccurrence`로 관리하고 Alarm 생성 시에는 기존 `BusAlarmTarget` snapshot으로 복사한다. TASK-402는 Route Stop 조회 응답의 `id`인 `BusRouteStopOccurrence.id`를 Alarm 생성의 `targetStopOccurrenceId`로 확정했다.

모든 Alarm API는 Phase 2 Authentication의 인증된 StopBell User를 기준으로 소유권을 처리한다. Client Request Body 또는 Query Parameter의 `userId`를 받지 않으며, 생성·조회·수정·삭제 모두 해당 User 소유 Alarm만 처리한다.

------------------------------------------------------------------------

# Phase 5 - Transit Integration

목표:

Phase 3에서 결정한 실제 Provider를 Backend에 연결하고, Phase 4의 Alarm을 실제 Transit 데이터로 평가할 수 있게 한다.

- [x] TASK-501 Transit provider client interface 정의
- [x] TASK-502 provider response DTO 정의
- [ ] TASK-503 Transit API Client 구현
- [ ] TASK-504 StopBell Transit DTO 및 `TransitEvent` 변환 구현
- [ ] TASK-505 Bus Route 검색 구현
- [ ] TASK-506 Bus Stop 조회 구현
- [x] TASK-507 Transit metadata persistence / MyBatis 필요성 결정 및 구현
- [ ] TASK-513 Transit metadata source adapter / bootstrap 구현
- [ ] TASK-508 Alarm grouping 조회 전략 결정 및 구현
- [ ] TASK-509 Alarm Evaluation Logic 구현
- [ ] TASK-510 Scheduler 실행 모델 결정 및 구현
- [ ] TASK-511 Transit API failure를 `UNKNOWN` 상태로 처리
- [ ] TASK-512 Transit Integration Test 작성

Phase 5의 dependency는 다음과 같다. TASK-513 때문에 TASK-503을 선행 차단하지 않으며 기존 Task 번호와 완료 이력도 유지한다.

```text
Realtime:                  TASK-501 → TASK-502 → TASK-503 → TASK-504
Metadata / User selection: TASK-507 → TASK-513 → TASK-505 → TASK-506
                                        두 branch 준비 후
                         TASK-508 → TASK-509 → TASK-510 → TASK-511 → TASK-512
```

TASK-503은 `TransitProviderClient<R, C>` contract를 유지해 실제 Provider 호출을 구현한다. timeout/network, HTTP non-success, Provider logical error, decode/protocol error를 정상 empty와 구분 가능한 failure로 전달하며, failure를 빈 차량 목록으로 바꾸지 않는다. 서울은 Route 전체 조회로 roster/coarse 상태를 확인한 뒤 필요 차량만 `getBusPosByVehIdItem` 등 vehicle detail operation으로 조회한다. Route 전체 item의 `sectOrd`/`sectionId`/`nextStId`에서 target Stop occurrence를 추론하지 않으며, detail 대상 선택 규칙은 TASK-508~510에서 결정한다.

TASK-504는 request Route context, raw Provider response, StopBell successful response receive time을 provider-neutral `TransitObservation`으로 변환하고 필요하면 `TransitEvent`의 표현을 정의한다. `observedAt`은 polling 시작 시각이 아니라 successful response를 받은 직후의 시각이며 같은 response의 차량은 같은 receive-time context를 공유한다. TAGO와 서울 Route realtime item에 externalRouteId가 없을 수 있으므로 request context를 mapper까지 전달한다. ONE_STOP_BEFORE, ARRIVED, PASSED, ONE_STOP_AFTER 판정, baseline, tracking lifecycle, GPS/freshness/missing grace는 TASK-509 책임이다.

TASK-507은 서울 T Data CSV full import와 경기 TAGO throttled full sync를 위한 local metadata persistence 필요성을 확인해 `BusRoute`/`BusStop`/`BusRouteStopOccurrence` Schema, JPA Repository, source-neutral route diff sync를 구현했다. 같은 Route/Stop identity의 metadata는 내부 ID를 유지하며, 의미가 바뀐 occurrence는 새 ID를 받는다. complete provider snapshot 성공 시에만 없는 Route와 orphan Stop을 cleanup하는 reconciliation 방향을 구현했다. metadata CRUD/reconciliation은 JPA로 충분하므로 MyBatis를 도입하지 않았고, Route/Stop 검색·Alarm grouping·대량 조회 성능에서 실제 SQL 제어 필요성이 확인될 때 재검토한다.

TASK-513은 source에서 metadata를 가져와 TASK-507 reconciliation에 적용하는 production ingestion 책임이다. 서울에서는 노선마스터·정류장마스터·노선-정류장마스터 T Data CSV를 parsing·validation하여 normalized `BusRouteMetadataSnapshot`으로 만들고, 경기에서는 TAGO city별 Route와 Route Stop을 pagination 완료까지 수집해 normalized snapshot으로 만든다. 실제 CSV header, encoding, GPS column은 fixture 또는 source 파일을 확인한 구현 시점에 확정한다. TAGO metadata full sync는 realtime vehicle polling과 별도 책임이다.

TASK-513의 최초 bootstrap 기본 방향은 Backend startup이 아닌 명시적 one-shot metadata import/sync 실행이다. 자동 refresh 주기는 이번 Task에서 결정하지 않는다. partial source fetch, parser failure, pagination incomplete, required source missing, provider request 일부 실패 중 하나라도 있으면 complete provider snapshot이 아니므로 provider 전체 cleanup을 실행하지 않는다. empty `List`만으로 complete snapshot을 자동 판정하지 않는다. 구체 Java type 또는 Schema는 강제하지 않는다.

TASK-513 ingestion은 단일 Backend 환경에서 동일 Provider full sync single-flight를 보장하고, current Route reconciliation의 Stop lazy-loading N+1 여부를 확인·개선한다. Provider 전체를 하나의 장시간 DB transaction으로 묶지 않으며 Route 단위 transaction과 successful complete snapshot 뒤 cleanup 방향을 유지한다. Redis, distributed lock, queue는 V1에 도입하지 않는다.

TASK-508은 기본 polling key를 TAGO의 `(provider, externalRouteId, cityCode)`, 서울의 `(provider, externalRouteId)`로 grouping한다. `cityCode`는 Route identity가 아니라 TAGO request 재현 문맥이지만 동일 request 공유에는 필요하다. 같은 Route를 쓰는 여러 사용자·target Stop·ACTIVE Alarm·FOLLOW_UP Alarm은 가능한 한 한 Route polling response를 공유하며 Alarm별 Provider 호출은 만들지 않는다. 현재 JPA + Java grouping으로 충분하며 MyBatis는 도입하지 않는다.

TASK-509은 ACTIVE Alarm의 필요한 vehicle tracking을 V1에서 memory 기반으로 관리할 수 있다. Backend restart 뒤에는 이전 memory tracking과 새 cycle을 연결하지 않고 안전한 recovery baseline을 만들며, restart 전 Observation과 연결해 PASSED를 추론하거나 predecessor만으로 ONE_STOP_BEFORE를 재발행하지 않는다. 일부 Event 누락보다 false-positive 방지를 우선하며, 모든 raw Provider observation 저장이나 event sourcing은 도입하지 않는다. FOLLOW_UP은 영속된 `status`, `vehicleTrackingId`, `startedAt`, `expiresAt`을 실제 scheduler가 재사용해 유효한 동일 vehicle tracking을 재개해야 한다. restart continuity 충족 여부는 TASK-811에서 검증하고 필요하면 최소 persistence를 재검토한다.

TASK-510은 단일 Spring instance 기준으로 polling cycle overlap을 막는 synchronous/fixed-delay 모델을 우선한다. Provider HTTP I/O 중 DB transaction/row lock을 장시간 유지하지 않고, Alarm을 읽은 뒤 deactivate/delete/reactivate될 수 있음을 고려해 stale polling 결과가 최신 lifecycle을 덮어쓰지 않게 한다. ARRIVED와 manual deactivate, FOLLOW_UP completion과 reactivation race를 안전하게 처리한다. JPA `@Version`, conditional update/CAS, lifecycle generation token 중 무엇을 쓸지는 구현 전에 비교하며 지금 확정하지 않는다.

TASK-511은 TASK-503 Provider failure를 Transit/Alarm orchestration에서 Event 없는 UNKNOWN으로 처리한다. failure 때문에 Alarm lifecycle을 진행하거나 기존 vehicle tracking state를 즉시 삭제하거나 synthetic PASSED/ARRIVED를 만들지 않는다. retry/backoff 정책과 Resilience4j/circuit breaker 도입 여부는 TASK-510/511 구현 시 결정한다.

------------------------------------------------------------------------

# Phase 6 - Flutter Client

목표:

현재 목표는 iOS 개발 및 실제 기기 vertical slice다. Flutter Client를 실제 Backend API와 연결하고 V1 Alarm 생성·관리 흐름을 구현한다. Flutter는 미확정 Route DTO나 identity를 추측하지 않고, Transit monitoring 비즈니스 로직을 구현하지 않는다.

- [ ] TASK-601 Flutter Google Login 및 Backend 인증 연동: 구현 전 bundle ID, Google iOS client ID, Backend server/web client ID, API origin 등 iOS 개발 설정을 검증한다.
- [ ] TASK-602 Access/Refresh Token Pair Secure Storage 구현: pair 단위 저장·읽기·삭제와 손상된 저장값 처리를 구현한다. 구체 storage serialization API는 구현 시 결정한다.
- [ ] TASK-603 인증 API Client 및 Access Token 적용 구현: Auth API와 authenticated API client의 최소 연결을 구현한다.
- [ ] TASK-604 startup 인증 상태 복구 및 Access Token 만료/Refresh Rotation 연동: Auth Session이 current Token Pair, `initializing`/`authenticated`/`unauthenticated` 상태, refresh single-flight, Token Pair 교체와 Secure Storage 반영을 단일 책임으로 소유하게 한다. concurrent `401`은 single-flight로 처리하고 refresh 성공 뒤 원 보호 요청을 최대 한 번 재시도한다. refresh `401`은 Token Pair 제거와 unauthenticated 전환이며 network/offline/5xx는 장기 session을 즉시 삭제하지 않는다. `/auth/google`, `/auth/refresh`, `/auth/logout`는 refresh loop에 넣지 않는다.
- [ ] TASK-605 Flutter Logout 구현: TASK-604와 같은 Auth Session coordination에서 logout과 refresh를 직렬화하고, session generation 또는 동등한 보호로 late refresh/API response가 logout 뒤 local auth state를 되살리지 않게 한다. 서버에 이미 도착한 요청 취소나 Access Token blacklist는 가정하지 않는다.
- [ ] TASK-606 Flutter Bus Route 검색 연동: TASK-513 → TASK-505 완료 뒤 구현하며 loading, empty, error, retry 상태를 제공한다.
- [ ] TASK-607 Flutter Bus Stop 조회 및 선택 구현: TASK-506 완료 뒤 구현하며 loading, empty, error, retry 상태와 `canNotifyOneStopBefore`/`canNotifyOneStopAfter` 기반 option 비활성화를 제공한다.
- [ ] TASK-608 Flutter Alarm 생성 및 관리 화면/API 연동: TASK-607 뒤 구현하며 `INACTIVE`/`ACTIVE`/`FOLLOW_UP`을 그대로 표현한다. Alarm 목록/조회에는 loading, empty, error, retry 상태를 제공하고 mutation 진행 중 중복 입력을 막는다. stale `targetStopOccurrenceId`의 생성 `404`는 old ID 추측 매칭·자동 POST 재시도 대신 Stop 목록 재선택 흐름으로 복구하며, idempotency contract가 없는 create timeout도 자동 POST 재시도하지 않는다. 실제 ACTIVE monitoring 종단 검증은 Phase 5 monitoring 완료 후 수행한다.
- [ ] TASK-609 Flutter Client Authentication / Transit / Alarm E2E·regression Test 보강: 각 Task 테스트를 몰아서 작성하는 Task가 아니라, 최종 Flutter 인증·Transit·Alarm 흐름의 E2E·regression을 보강한다.

TASK-601~605 Authentication lane은 Backend Authentication이 준비되어 있으므로 Phase 5와 병행할 수 있다. TASK-606은 TASK-513 → TASK-505 뒤, TASK-607은 TASK-506 뒤, TASK-608은 TASK-607 뒤 진행한다. offline mutation queue/cache, generic `Repository`/`BaseRepository`, 모든 API별 `UseCase` class, global event bus, 복잡한 refresh queue framework, notification stub은 이번 Phase에 도입하지 않는다. state management library도 특정 제품으로 확정하지 않는다.

Phase 6은 Phase 7에 안정적인 Auth Session state, 자동 refresh를 포함한 authenticated API client, logout lifecycle/hook, Alarm ID 기반 navigation 진입점, app resume 시 Auth Session 재평가 가능 지점을 제공한다. Device/FCM/logout registration 정책은 TASK-701/702에서 결정하며 Phase 6에서 미리 구현하지 않는다. Apple Login과 App Store 계정 삭제 요건은 별도 release readiness에서 재검토한다.

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
