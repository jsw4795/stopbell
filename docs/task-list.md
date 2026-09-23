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
- [x] TASK-503 Transit API Client 구현
- [x] TASK-504 StopBell Transit DTO 및 `TransitObservation` 변환 구현
- [x] TASK-505 Bus Route 검색 구현
- [x] TASK-506 Bus Stop 조회 구현
- [x] TASK-507 Transit metadata persistence / MyBatis 필요성 결정 및 구현
- [x] TASK-513 Transit metadata source adapter / bootstrap 구현
- [x] TASK-508 Alarm grouping 조회 전략 결정 및 구현
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

TASK-503은 기존 `TransitProviderClient<R, C>` contract를 유지해 external call 하나마다 정확한 성공/실패를 표현한다. Timeout/network, HTTP non-success, Provider logical error, decode/protocol error를 normal empty와 구분하고 failure를 빈 차량 목록으로 바꾸지 않는다. 서울 Route roster operation은 raw response 또는 typed provider failure, vehicle detail operation은 차량 하나의 raw detail response 또는 typed provider failure를 반환한다. 여러 detail 호출의 성공/실패 조합과 대상 선택은 TASK-508~510 orchestration 책임이며 TASK-511은 실패 vehicle을 Event 없는 UNKNOWN으로 처리한다. Route roster의 `sectOrd`/`sectionId`/`nextStId`에서 target Stop occurrence를 추론하지 않고 client 내부 무제한 retry, generic `PartialResult`/batch result/provider operation hierarchy를 만들지 않는다. 실제 관찰 response 기반 fixture로 success, empty, error, malformed contract를 검증한다.

TASK-503의 typed failure는 `TransitProviderClientException`과 동등한 하나의 exception type에 `TRANSPORT`, `HTTP`, `PROVIDER`, `PROTOCOL` reason을 둔다. normal success에는 items가 있는 경우와 정상 empty가 모두 포함된다. exception에는 필요할 때 provider, operation, HTTP status, provider result/header code, cause를 보존할 수 있지만 API key, secret, raw sensitive response는 포함하거나 log하지 않는다. timeout/network와 모든 failure reason별 subclass는 만들지 않는다.

TASK-504는 request Route context, raw Provider response, StopBell successful response receive time을 provider-neutral `TransitObservation`으로 변환하고 필요하면 `TransitEvent`의 표현을 정의한다. `observedAt`은 polling 시작 시각이 아니라 successful response를 받은 직후의 시각이며 같은 response의 차량은 같은 receive-time context를 공유한다. TAGO와 서울 Route realtime item에 externalRouteId가 없을 수 있으므로 request context를 mapper까지 전달한다. ONE_STOP_BEFORE, ARRIVED, PASSED, ONE_STOP_AFTER 판정, baseline, tracking lifecycle, GPS/freshness/missing grace는 TASK-509 책임이다.

TASK-503과 TASK-504는 Provider HTTP client·raw operation DTO·failure contract 뒤 바로 raw success response를 `TransitObservation`으로 mapping하는 연속된 vertical slice로 구현할 수 있다. 다만 Task 번호와 완료 기준은 병합하지 않으며 각 Task는 실제 완료와 검증 뒤 독립적으로 완료 표시한다.

TASK-507은 서울 T Data CSV full import와 경기 TAGO throttled full sync를 위한 local metadata persistence 필요성을 확인해 `BusRoute`/`BusStop`/`BusRouteStopOccurrence` Schema, JPA Repository, source-neutral route diff sync를 구현했다. 같은 Route/Stop identity의 metadata는 내부 ID를 유지하며, 의미가 바뀐 occurrence는 새 ID를 받는다. complete provider snapshot 성공 시에만 없는 Route와 orphan Stop을 cleanup하는 reconciliation 방향을 구현했다. metadata CRUD/reconciliation은 JPA로 충분하므로 MyBatis를 도입하지 않았고, Route/Stop 검색·Alarm grouping·대량 조회 성능에서 실제 SQL 제어 필요성이 확인될 때 재검토한다.

TASK-513은 source에서 metadata를 가져와 TASK-507 reconciliation에 적용하는 production ingestion 책임이다. 서울에서는 노선마스터·정류장마스터·노선-정류장마스터 T Data CSV를 parsing·validation하여 normalized `BusRouteMetadataSnapshot`으로 만들고, 경기에서는 TAGO city별 Route와 Route Stop을 pagination 완료까지 수집해 normalized snapshot으로 만든다. 서울 source는 UTF-8 BOM과 실제 한글 header 및 X=longitude/Y=latitude 계약을 고정한다. 공식 노선유형 `공항`/`마을`/`간선`/`지선`/`순환`/`광역`/`관광`만 `SEOUL_BUS`에 포함하고 `경기`/`인천`은 제외한다. 노선유형 null은 모든 occurrence가 확인된 일반 버스 정류장유형일 때만 포함하며, 모두 `선착장`이면 제외한다. 혼재·미확인 정류장유형·null Route의 occurrence 없음은 incomplete source로 처리한다. TAGO metadata full sync는 realtime vehicle polling과 별도 책임이다.

TASK-513의 최초 bootstrap 기본 방향은 Backend startup이 아닌 명시적 one-shot metadata import/sync 실행이다. 자동 refresh 주기는 이번 Task에서 결정하지 않는다. Partial source fetch, parser failure, pagination incomplete, required source missing, provider request 일부 실패와 검증되지 않은 empty result는 complete provider snapshot이 아니므로 provider 전체 cleanup을 실행하지 않는다. Typed complete snapshot boundary, explicit completeness token/result, destructive method visibility 제한 또는 동등한 구조적 보호로 검증된 complete snapshot만 cleanup 경로에 들어가게 한다. 구체 Java type 이름은 구현에서 정한다.

TASK-513 ingestion은 단일 Backend 환경에서 동일 Provider full sync single-flight를 보장하고, current Route reconciliation의 Stop lazy-loading N+1 여부를 확인·개선한다. Provider 전체를 하나의 장시간 DB transaction으로 묶지 않으며 Route 단위 transaction과 successful complete snapshot 뒤 cleanup 방향을 유지한다. Provider별 최소 persisted sync state로 `provider`, `lastCompleteSyncAt` 의미를 저장해 fresh DB bootstrap, readiness와 last successful complete sync age를 판단한다. 필요하면 in-progress/failure 정보를 확장할 수 있지만 sync/checksum history, staging table, error journal은 요구하지 않는다. Redis, distributed lock, queue는 V1에 도입하지 않는다.

TASK-508은 기본 polling key를 TAGO의 `(provider, externalRouteId, cityCode)`, 서울의 `(provider, externalRouteId)`로 grouping한다. `cityCode`는 Route identity가 아니라 TAGO request 재현 문맥이지만 동일 request 공유에는 필요하다. `AlarmRepository`는 `EntityGraph`로 BUS `ACTIVE`/`FOLLOW_UP` Alarm과 `BusAlarmTarget`을 함께 조회하고, Java가 조회 순서대로 group을 구성한다. `INACTIVE`는 제외하며 같은 Route를 쓰는 여러 사용자·target Stop·ACTIVE Alarm·FOLLOW_UP Alarm은 가능한 한 한 Route polling response를 공유한다. target 누락 또는 Provider request context가 깨진 monitoring Alarm은 query에서 숨기거나 보정하지 않고 명시적으로 실패시킨다. 현재 JPA + Java grouping으로 충분하며 MyBatis와 실제 Provider 호출은 도입하지 않는다. Observation 평가, Scheduler와 Provider failure의 `UNKNOWN` 처리는 후속 Task에 남긴다.

TASK-509은 ACTIVE Alarm의 필요한 vehicle tracking을 V1에서 memory 기반으로 관리할 수 있다. Backend restart 뒤에는 이전 memory tracking과 새 cycle을 연결하지 않고 안전한 recovery baseline을 만들며, restart 전 Observation과 연결해 PASSED를 추론하거나 predecessor만으로 ONE_STOP_BEFORE를 재발행하지 않는다. 일부 Event 누락보다 false-positive 방지를 우선하며, 모든 raw Provider observation 저장이나 event sourcing은 도입하지 않는다. FOLLOW_UP은 영속된 `status`, `vehicleTrackingId`, `startedAt`, `expiresAt`을 실제 scheduler가 재사용해 유효한 동일 vehicle tracking을 재개해야 한다. restart continuity 충족 여부는 TASK-811에서 검증하고 필요하면 최소 persistence를 재검토한다.

TASK-510은 단일 Spring instance 기준으로 polling cycle overlap을 막는 synchronous/fixed-delay 모델을 우선한다. Provider HTTP I/O 중 DB transaction/row lock을 장시간 유지하지 않고, Alarm을 읽은 뒤 deactivate/delete/reactivate될 수 있음을 고려해 stale polling 결과가 최신 lifecycle을 덮어쓰지 않게 한다. ARRIVED와 manual deactivate, FOLLOW_UP completion과 reactivation race를 안전하게 처리한다. Persisted semantic activation generation은 `INACTIVE → ACTIVE`, `FOLLOW_UP → ACTIVE`에서 증가하고 `ACTIVE → ACTIVE`는 generation 증가와 baseline reset이 없는 idempotent 동작이다. `trackingCycleId` 또는 동등한 logical vehicle tracking cycle identity는 cycle 시작 시 한 번 생성하며 같은 logical cycle의 transaction retry에서 재생성하지 않는다. TASK-510 구현 시 current API/scheduler transaction 구조를 보고 `@Version`, CAS, pessimistic row lock 중 하나의 최소 concurrency mechanism만 선택하며 중복 적용하지 않는다.

TASK-511은 TASK-503 Provider failure를 Transit/Alarm orchestration에서 Event 없는 UNKNOWN으로 처리한다. failure 때문에 Alarm lifecycle을 진행하거나 기존 vehicle tracking state를 즉시 삭제하거나 synthetic PASSED/ARRIVED를 만들지 않는다. retry/backoff 정책과 Resilience4j/circuit breaker 도입 여부는 TASK-510/511 구현 시 결정한다.

------------------------------------------------------------------------

# Phase 6 - Flutter Client

목표:

현재 목표는 iOS 개발 및 실제 기기 vertical slice다. Flutter Client를 실제 Backend API와 연결하고 V1 Alarm 생성·관리 흐름을 구현한다. Flutter는 미확정 Route DTO나 identity를 추측하지 않고, Transit monitoring 비즈니스 로직을 구현하지 않는다.

- [ ] TASK-601 Flutter Google Login 및 Backend 인증 연동: 구현 전 bundle ID, Google iOS client ID, Backend server/web client ID, API origin 등 iOS 개발 설정을 검증한다.
- [ ] TASK-602 Access/Refresh Token Pair Secure Storage 구현: pair 단위 저장·읽기·삭제와 손상된 저장값 처리를 구현한다. 구체 storage serialization API는 구현 시 결정한다.
- [ ] TASK-603 인증 API Client 및 Access Token 적용 구현: Auth API와 authenticated API client의 최소 연결을 구현한다.
- [ ] TASK-604 startup 인증 상태 복구 및 Access Token 만료/Refresh Rotation 연동: Auth Session이 current Token Pair, `initializing`/`authenticated`/`unauthenticated` 상태, refresh single-flight, Token Pair 교체와 Secure Storage 반영을 단일 책임으로 소유하게 한다. concurrent `401`은 single-flight로 처리하고 refresh 성공 뒤 원 보호 요청을 최대 한 번 재시도한다. refresh `401`은 Token Pair 제거와 unauthenticated 전환이며 network/offline/5xx는 장기 session을 즉시 삭제하지 않는다. `/auth/google`, `/auth/refresh`, `/auth/logout`는 refresh loop에 넣지 않는다.
- [ ] TASK-605 Flutter Logout 구현: TASK-604와 같은 Auth Session coordination에서 logout과 refresh를 직렬화하고, session generation 또는 동등한 보호로 late refresh/API response가 logout 뒤 local auth state를 되살리지 않게 한다. Phase 7이 현재 Device unsubscribe/disable을 연결할 logout lifecycle/hook을 제공하며 서버에 이미 도착한 요청 취소나 Access Token blacklist는 가정하지 않는다.
- [ ] TASK-606 Flutter Bus Route 검색 연동: TASK-513 → TASK-505 완료 뒤 구현하며 loading, empty, error, retry 상태를 제공한다.
- [ ] TASK-607 Flutter Bus Stop 조회 및 선택 구현: TASK-506 완료 뒤 구현하며 loading, empty, error, retry 상태와 `canNotifyOneStopBefore`/`canNotifyOneStopAfter` 기반 option 비활성화를 제공한다.
- [ ] TASK-608 Flutter Alarm 생성 및 관리 화면/API 연동: TASK-607 뒤 구현하며 `INACTIVE`/`ACTIVE`/`FOLLOW_UP`을 그대로 표현한다. Alarm 목록/조회에는 loading, empty, error, retry 상태를 제공하고 mutation 진행 중 중복 입력을 막는다. stale `targetStopOccurrenceId`의 생성 `404`는 old ID 추측 매칭·자동 POST 재시도 대신 Stop 목록 재선택 흐름으로 복구하며, idempotency contract가 없는 create timeout도 자동 POST 재시도하지 않는다. 실제 ACTIVE monitoring 종단 검증은 Phase 5 monitoring 완료 후 수행한다.
- [ ] TASK-609 Flutter Client Authentication / Transit / Alarm E2E·regression Test 보강: 각 Task 테스트를 몰아서 작성하는 Task가 아니라, 최종 Flutter 인증·Transit·Alarm 흐름의 E2E·regression을 보강한다.

TASK-601~605 Authentication lane은 Backend Authentication이 준비되어 있으므로 Phase 5와 병행할 수 있다. TASK-606은 TASK-513 → TASK-505 뒤, TASK-607은 TASK-506 뒤, TASK-608은 TASK-607 뒤 진행한다. offline mutation queue/cache, generic `Repository`/`BaseRepository`, 모든 API별 `UseCase` class, global event bus, 복잡한 refresh queue framework, notification stub은 이번 Phase에 도입하지 않는다. state management library도 특정 제품으로 확정하지 않는다.

Phase 6은 Phase 7에 안정적인 Auth Session state, 자동 refresh를 포함한 authenticated API client, logout lifecycle/hook, Alarm ID 기반 navigation 진입점, app resume 시 Auth Session 재평가 가능 지점을 제공한다. Device/FCM 구현은 TASK-701~704에서 맡는다. Flutter logout은 이 hook에서 현재 Device disable을 시도한 뒤 Auth logout과 local session 종료를 수행하는 방향이며 offline에서 Backend disable을 즉시 보장하지 않는다. Apple Login과 App Store 계정 삭제 요건은 별도 release readiness에서 재검토한다.

------------------------------------------------------------------------

# Phase 7 - Notification

목표:

Alarm 조건 충족 시 logical Event와 Event×Device Delivery의 DB uniqueness를 보장하고 실제 기기로 Push notification 전달을 시도한다. FCM request는 bounded retry로 여러 번 발생할 수 있으며 실제 Device 표시 exactly-once는 보장하지 않는다.

- [ ] TASK-701 FCM iOS 지원 적합성 및 targeting contract 확정: 실제 사용할 FlutterFire/firebase_messaging, Firebase iOS SDK, Java Firebase Admin SDK 버전을 확인하고 현재 지원되는 targeting identifier와 local unregister 동작을 확정한다. Firebase→실제 iPhone early smoke를 수행하며 APNs token을 StopBell Device identity로 사용하지 않는다.
- [ ] TASK-702 Device Domain/Schema 및 registration/logout contract 정의: 내부 PK + client-generated installationId + current push targeting identifier를 분리한다. `installationId`는 User-scoped가 아닌 앱 installation identity로 두고 동시에 current owner가 최대 한 명이 되도록 하며, User 전환 시 atomic ownership takeover 또는 동등한 계약으로 두 ownership이 함께 enabled 상태로 남지 않게 한다. Multi-device, registration rotation, monotonic revision 또는 동등한 stale-write 방지, 별도 authenticated disable/unregister lifecycle을 정의한다. RefreshToken FK와 단일 Device 제한은 두지 않고 push targeting identifier의 global uniqueness는 TASK-701 확인 뒤 결정한다.
- [ ] TASK-703 Device registration/disable API 구현: stale registration update가 최신 target을 덮어쓰지 못하게 하고 같은 revision/registration 재요청을 idempotent하게 처리하는 correctness test를 함께 작성한다.
- [ ] TASK-704 Flutter Push permission, registration 및 tap lifecycle 구현: 첫 Alarm activation 직전 맥락 기반 permission UX, denied 경고/Settings 안내, app resume 재동기화, registration 갱신, logout hook의 현재 Device disable 시도, Auth Session 초기화 뒤 Alarm ID navigation을 구현한다. Permission denied로 Alarm 생성/활성화를 금지하지 않는다.
- [ ] TASK-705 Push provider client 구현: TASK-709의 result/failure taxonomy를 구현하고 Backend→실제 iPhone smoke를 수행한다. accepted를 실제 표시 성공으로 해석하지 않고 credential/target redaction을 검증한다.
- [ ] TASK-706 Notification orchestration 및 durable pending dispatch worker 구현: lifecycle transaction에서 Event 시점의 eligible Device별 pending Delivery까지 생성해 recipient set을 확정하고, commit 뒤 단일 Spring Backend의 fixed-delay, non-overlapping worker가 due PENDING Delivery를 제한 조회해 처리한다. Eligible Device가 0개여도 이미 발생한 logical NotificationEvent는 저장하고 Delivery는 0개로 두며 no-recipient log/metric을 기록한다. 이후 등록된 Device에 과거 Event의 Delivery를 생성하지 않는다. Worker는 전송 직전 Device owner/enabled/current target/revision을 재검증하고 실제 attempt revision을 기록한다. FCM I/O를 Alarm transaction 안에서 수행하거나 non-durable callback만을 전달 보장으로 사용하지 않는다. claim/lease, `claimedAt`, `SENDING`, stale-claim recovery, multi-worker coordination은 구현하지 않는다.
- [ ] TASK-707 NotificationEvent / NotificationDelivery persistence 및 durable outbox 구현: 초기 NotificationHistory의 확장·대체·migration 방식을 결정하고 logical Event, Event 결정 시점에 확정한 Event×Device delivery, `PENDING`/`ACCEPTED`/`FAILED`/`EXPIRED` lifecycle과 retry를 위한 attemptCount·nextAttemptAt·freshness를 분리한다. retry 가능한 실패를 `RETRYING` lifecycle로 만들지 않으며 raw push target persistence는 필수가 아니고 attempt revision을 기록한다. Alarm 삭제 시 이미 생성된 Event/Delivery를 유지·취소·cascade 삭제할지 lifecycle과 outbox recovery 의미를 기준으로 명시적으로 결정하고 FK cascade에 우연히 맡기지 않는다. Production legacy compatibility와 append-only attempt history를 과도하게 만들지 않는다.
- [ ] TASK-708 Duplicate Prevention 설계 및 구현: 먼저 `(alarmId, activation generation, trackingCycleId, eventType)` logical identity와 atomic DB uniqueness를 설계한 뒤 구현한다. 반복 Observation과 Event candidate 억제는 Phase 5 책임으로 유지하고 `alarmId + eventType`만으로 dedup하지 않는다.
- [ ] TASK-709 Notification failure/retry 설계 및 구현: Provider result `ACCEPTED`, `INVALID_TARGET`, `RETRYABLE`, `CONFIGURATION`, `PERMANENT_REQUEST`, `AMBIGUOUS_TIMEOUT`을 Delivery lifecycle과 분리해 정의한다. `EXPIRED`는 local freshness 종료다. 이후 bounded retry/expiry와 attempt revision이 current registration일 때만 Device를 disable하는 조건부 cleanup을 구현하고, smoke 결과로 최대 횟수·간격·freshness TTL을 확정한다. No-recipient은 Provider failure나 delivery success로 해석하지 않고 별도 enum도 강제하지 않으며 운영 log/metric으로 관찰한다.
- [ ] TASK-710 실제 iPhone foreground/background/terminated 수신 및 tap 검증: payload가 최소 navigation hint이고 tap 뒤 ownership/current state를 Backend에서 재검증하며 stale notification을 정상 처리하는지 확인한다.
- [ ] TASK-711 Notification 최종 E2E/regression Test 보강: 각 선행 Task의 correctness test를 대체하지 않고 durable recovery, multi-device fan-out과 전체 Notification 흐름을 최종 검증한다.

실행 dependency는 다음과 같다. TASK-708/709는 선행 설계와 후행 구현 단계를 가지며 Task 번호와 완료 상태는 변경하지 않는다.

```text
TASK-701
  → TASK-702
  → TASK-708 design
  → TASK-709 design
  → TASK-707
  → TASK-703 / TASK-704
  → TASK-705
  → TASK-708 implementation
  → TASK-706
  → TASK-709 implementation
  → TASK-710
  → TASK-711
```

FCM targeting identifier와 Device field/column 길이는 TASK-701/702, activation generation increment/CAS는 TASK-510, Notification Schema/constraint 이름은 TASK-707/708, retry 수치는 TASK-709에서 확정한다. MySQL durable pending dispatch와 단일 non-overlapping in-process worker를 사용하며 Kafka, RabbitMQ, Redis queue, Notification microservice, event sourcing, generic multi-provider/retry framework, Device subtype hierarchy, APNs direct client, multi-instance distributed lock, claim/lease, `claimedAt`, `SENDING`, stale-claim recovery는 V1에 도입하지 않는다.

------------------------------------------------------------------------

# Phase 8 - Quality and Operations

목표:

Phase 5/7에서 구현한 correctness를 더 넓은 race, restart, 실제 환경 조건에서 검증하고, V1 운영에 필요한 최소 품질을 갖춘다. Phase 8은 activation generation, stale scheduler result 보호, Alarm Evaluation/tracking의 중복 억제, lifecycle concurrency, NotificationEvent atomic uniqueness, lifecycle·logical NotificationEvent·Event 시점 recipient Delivery의 atomic commit, durable pending dispatch recovery, Device별 delivery uniqueness, failure/retry state를 처음 구현하는 단계가 아니다.

- [ ] TASK-801 Alarm Evaluation Test 보강
- [ ] TASK-802 Duplicate Prevention Test 보강
- [ ] TASK-803 외부 provider response mapping Test 보강: 실제 관찰 response를 민감 정보를 제거한 golden fixture로 만들고 synthetic edge fixture를 함께 사용한다. fixture에는 provider, operation, 관찰 시점 또는 provenance를 남기되 credential은 제거한다. 실제 Provider 호출이나 scheduled live canary는 일반 PR CI 또는 V1 필수 infrastructure로 두지 않는다.
- [ ] TASK-804 API validation/error handling Test 보강: 오류 body shape를 안정적으로 통일하되 모든 오류 의미를 합치지 않는다. 최소 `INVALID_REQUEST`, `UNAUTHENTICATED`, resource-specific `NOT_FOUND`, `TARGET_STOP_OCCURRENCE_NOT_FOUND`, 명시적 concurrency/stale conflict, `METADATA_UNAVAILABLE`, `INTERNAL_SERVER_ERROR`를 검증하며 예상치 못한 Exception을 400으로 바꾸지 않는다.
- [ ] TASK-805 구조화된 Logging, 최소 Operational Metrics 및 Alerting contract 추가
- [ ] TASK-806 Secret 관리 검토
- [ ] TASK-807 Health / Readiness 계약 검증
- [ ] TASK-808 재현 가능한 Backend runtime image 구성
- [ ] TASK-809 CI build/test 구성
- [ ] TASK-810 실제 iPhone 환경에서 notification latency 측정
- [ ] TASK-811 restart / graceful shutdown / recovery 안전성 검증
- [ ] TASK-812 Analytics / 장기 통계 필요성 결정 및 필요한 경우에만 최소 구현
- [ ] TASK-813 Production deployment / migration / backup & recovery readiness
- [ ] TASK-814 Production UTC time contract 및 timestamp consistency
- [ ] TASK-815 iOS public App Store release readiness

TASK-805는 structured log, 최소 operational metric, alerting contract를 함께 다룬다. metric은 Provider request outcome/latency, Observation staleness/UNKNOWN reason, ACTIVE/FOLLOW_UP Alarm 수, scheduler cycle duration·last completion·overlap, Notification pending 수와 oldest pending delivery age, no-recipient Event, delivery accepted/failure/retry/expired, metadata 마지막 complete sync 성공 age를 후보로 한다. `alarmId`, `deviceId`, `routeId`, `trackingCycleId`, `installationId`는 metric label에 넣지 않는다. Actuator/Micrometer 수준을 기본으로 하고 자체 Prometheus/Grafana stack을 V1 요구로 만들지 않으며 export와 alert destination은 deployment platform에서 정한다. log는 문맥에 필요한 correlation(`requestId`, `operationId`, provider, schedulerCycleId, alarmId, activation generation, trackingCycleId, notificationEventId, deliveryId, elapsedMs, outcome)만 기록한다. Access/Refresh Token, Google ID Token, Firebase credential, 원문 push targeting identifier·installationId, API key가 든 URL/query, 필요 이상의 GPS, raw Provider response 전체는 로그에 남기지 않는다.

TASK-807은 liveness(JVM/process 생존), readiness(DB 연결, Flyway 적용 완료, 필요한 component 초기화, scheduler/outbox worker 실행 가능), business/dependency health(Provider 최근 실패, scheduler last completion, outbox backlog, metadata 마지막 sync age)를 구분한다. TAGO/서울/FCM의 일시적 원격 장애만으로 Backend readiness를 DOWN으로 만들지 않으며, 상세 dependency 정보는 public health response에 과도하게 노출하지 않는다. fresh DB 최초 배포에서는 필요한 metadata bootstrap이 끝나기 전 public Route/Alarm 생성 traffic을 받지 않는 절차가 필요하지만, 정상 운영 중 metadata가 일시적으로 오래됐다는 이유만으로 기존 Alarm monitoring을 unready로 만들지 않는다.

TASK-808은 production topology 전체가 아니라 Java 21 기반의 재현 가능한 Backend runtime image를 만든다. Gradle Wrapper build 또는 CI-built bootJar, non-root 실행, SIGTERM 전달, UTC runtime timezone, stdout/stderr logging, runtime environment/secret injection, Firebase credential read-only mount 또는 platform identity, health/readiness integration과 graceful shutdown budget을 검토한다. image size 최적화는 correctness보다 우선하지 않고 development `docker-compose.yml`을 production topology로 자동 채택하지 않는다.

TASK-809의 최소 CI는 지금부터 시작할 수 있다. Backend는 Java 21, Gradle Wrapper, build/test, Testcontainers/MySQL/Flyway 검증을, Flutter는 고정 Flutter version, `flutter analyze`, test가 생긴 뒤 `flutter test`를 포함한다. Phase 5~7 구현이 추가될 때 해당 correctness test를 계속 포함한다. 실제 TAGO/서울 Provider 호출, production Firebase credential, 실제 FCM 전송, signed iOS archive는 일반 PR CI에 넣지 않는다. TASK-808 완료 뒤 Docker image build/smoke를 추가할 수 있으며 GitHub Actions는 후보이나 workflow 세부는 이 Task에서 확정한다.

TASK-810은 `pollStartedAt`, provider data time(제공되는 경우), `observedAt`, `eventDetectedAt`, `notificationEventCreatedAt`, `deliveryAttemptAt`, `providerAcceptedAt`으로 Provider freshness/request, evaluation, outbox queue, FCM request, Backend observable end-to-end latency를 분리해 측정한다. Provider가 실제 버스 Event 시각을 주지 않으면 물리 Event부터 Backend까지 지연은 알 수 없고, FCM accepted도 iPhone 표시 시각이 아니다. foreground/background/terminated별 실제 Device 반복 측정으로 체감 지연을 보완한다.

TASK-811은 ACTIVE tracking, ONE_STOP_BEFORE 뒤, target 통과 직전, FOLLOW_UP, lifecycle과 NotificationEvent commit 직후, pending delivery 전, FCM accepted와 DB result update 사이, retry wait, metadata sync 경계에서 restart를 검증한다. ACTIVE tracking의 memory loss는 허용하고 safe rebaseline을 사용하며 restart 전후 Observation을 연결해 PASSED를 추론하지 않는다. FOLLOW_UP은 persisted runtime으로, NotificationEvent는 durable outbox로 복구한다. ambiguous FCM acceptance에는 duplicate 가능성을 인정한다. SIGTERM/deployment 때 새 polling·dispatch cycle을 시작하지 않고 진행 중 transaction은 commit/rollback하며 Provider/FCM timeout은 shutdown budget보다 짧게 제한한다. PENDING Delivery는 restart 뒤 단일 worker가 다시 처리하고 incomplete metadata sync는 provider cleanup 근거가 될 수 없다. 이 검증에서 실제 품질 문제가 입증되기 전에는 ACTIVE tracking persistence나 event sourcing을 추가하지 않는다.

TASK-812는 debugging을 위한 structured log, notification quality를 위한 operational metrics/NotificationDelivery, product usage를 위한 analytics, 장기 business metric을 구분한다. 실제 제품 질문과 보존 근거가 있을 때만 Analytics를 최소 구현하며 별도 persistence가 필요 없다는 결론도 정상 완료다. Phase 7의 NotificationEvent/NotificationDelivery는 correctness와 delivery operation 데이터이며 장기 Analytics Source of Truth가 아니다. TASK-812는 public V1 release blocker가 아니다.

TASK-813은 단일 persistent Backend runtime, durable MySQL, HTTPS/domain 또는 동등한 secure public endpoint, runtime secret injection, restart policy, deployment smoke, rollback procedure, DB backup/restore, Flyway migration policy를 준비한다. 특정 vendor/product는 정하지 않는다. 첫 production 적용 뒤에는 적용된 Flyway migration file을 수정하지 않고 새 migration을 추가한다. single-instance V1은 application startup migration을 유지할 수 있으나, 배포 전 migration 영향과 backup/restore point를 확인하고 migration 실패 instance는 ready가 되어서는 안 된다. DB를 임의 downgrade하지 않으며 application rollback은 새 Schema와의 compatibility를 확인한 경우만 한다. 공개 사용자 데이터를 받기 전 자동 DB backup 또는 platform snapshot, 가능하면 PITR, deploy 전 restore point, 실제 restore drill 최소 1회를 검증한다. Restore 뒤에는 outbound polling/notification dispatch를 quarantine하고 Flyway/schema 검증 → RefreshToken 복구 정책 → Device ownership/registration 정책 → metadata 재동기화/검증 → Alarm recovery baseline → stale pending Notification expiry/cutoff → worker/scheduler 재개 → public readiness 순서로 복구한다. Restore epoch나 별도 recovery subsystem은 추가하지 않는다. Alarm/User/BusAlarmTarget은 복구 중요도가 높고 Transit metadata는 source에서 재생성할 수 있으며 pending notification은 freshness policy를 고려한다. 오래된 backup 복원 뒤 RefreshToken session revoke/re-login 정책은 이 Task에서 결정한다.

TASK-814는 persisted operational time을 UTC 의미로 통일한다. `createdAt`/`updatedAt`, RefreshToken expiry, FOLLOW_UP start/expiry, Provider data time normalization, scheduler time, Notification retry/expiry, latency instrumentation 및 Analytics event time(도입 시)이 대상이다. TASK-504/510/707/709 등 그 전에 추가되는 timestamp도 처음부터 UTC 의미, test 가능한 `Clock` 또는 동등한 time source, 명시적인 Provider timezone parsing을 적용한다. TASK-814는 기존 timestamp consistency의 최종 정리이며 그 전까지 host-local `now()` 추가를 허용하지 않는다. 모든 type을 `Instant`로 바꿀지 UTC `LocalDateTime`/MySQL `DATETIME` 의미를 유지할지는 implementation 시 Schema와 migration 비용을 확인해 정하되 host local timezone에 따라 persisted time 의미가 달라져서는 안 된다.

TASK-815는 Phase 6의 iOS development/actual-device vertical slice를 막지 않으며 public App Store 제출 전에만 release blocker다. 제출 시점 Apple 공식 Guideline을 확인해 Google-only primary social login의 Guideline 4.8 준수, 동등한 privacy-preserving login option 필요 여부, in-app account deletion, Privacy Policy, App Store privacy disclosure/data inventory, Firebase/Google 등 third-party SDK disclosure를 검토한다. 지금 Sign in with Apple 구현을 고정하지 않으며 필요할 때 User, Alarm, Device, Refresh Session 등 사용자 연결 데이터의 삭제 범위와 법적 보존 데이터를 정한다.

공개 V1 전에는 기존 Domain Task에서 User별 총/ACTIVE Alarm, User별 Device, Route search query 길이/result limit, request body size의 최소 상한을 Provider quota와 실제 규모를 기준으로 결정한다. 별도 generic rate limiter나 Redis rate limiter는 도입하지 않는다. Provider polling은 Route grouping으로 Alarm별 외부 API 증폭을 막는다.

실행 dependency는 다음과 같다. TASK 번호는 변경하지 않는다.

```text
TASK-809 최소 CI는 지금부터 시작 가능

Phase 5~7 correctness 구현 완료
        ↓
TASK-803 / 801 / 802 / 804 regression 보강
        ↓
TASK-814 UTC time contract
        ↓
TASK-806 Secret 관리
        ↓
TASK-805 Logging / Metrics / Alerts
        ↓
TASK-807 Health / Readiness
        ↓
TASK-808 Backend Docker image
        ↓
TASK-813 Production deployment / migration / backup & recovery
        ↓
TASK-811 Restart / graceful shutdown / recovery 검증
        ↓
TASK-810 실제 iPhone notification latency 측정
```

TASK-815는 public App Store 제출 전에 수행하고 TASK-812는 제품 Analytics 필요성이 확인될 때 수행한다.

Future Consideration:

- CD 도입은 수동 배포 흐름을 이해한 뒤 검토한다.
- Redis, external message broker, multiple backend instance, Kubernetes, Kafka, RabbitMQ, ELK cluster, self-hosted Prometheus/Grafana stack, Vault, distributed tracing platform, multi-region, read replica, CQRS, event sourcing, microservice split, blue/green deployment framework는 측정된 필요가 있을 때만 검토한다. Phase 7의 MySQL pending dispatch worker는 이미 기본 correctness 범위다.
