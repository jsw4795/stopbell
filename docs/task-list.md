# Task List

## Purpose

이 문서는 Codex와 개발자가 StopBell을 단계적으로 구현하기 위한 Task 순서를 정의한다.

Task 상태는 실제 완료 여부를 기준으로 관리한다. 실제 구현 전에 `requirements.md`, `architecture.md`, `database.md`, `domain-model.md`, 관련 ADR을 확인한다.

---

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

---

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

---

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

---

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

---

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

---

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

---

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
- [x] TASK-509 Alarm Evaluation Logic 구현
- [x] TASK-510 Scheduler 실행 모델 결정 및 구현
- [x] TASK-511 Transit API failure를 `UNKNOWN` 상태로 처리
- [x] TASK-512 Transit Integration Test 작성

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

TASK-509은 `BusAlarmEvaluator`와 memory 기반 `BusAlarmEvaluationState`로 ACTIVE 차량별 최신 Observation, 마지막 관찰 시각, cycle 내 target 이전 관찰 history, cycle별 emitted Event만 보존한다. logical `trackingCycleId`는 cycle 시작 시 한 번 생성한 UUID이며 raw Observation history, tracking DB table, event sourcing은 도입하지 않는다. baseline target-after 차량은 60초 missing grace 동안 별도로 보존하며, ACTIVE memory state는 restart 뒤 복원하거나 이전 Observation과 연결하지 않고 새 baseline으로 시작한다. V1 evidence policy는 TAGO target GPS 100m, Observation/서울 provider data freshness 60초, vehicle missing grace 60초, FOLLOW_UP timeout 5분이다. `TransitEvent`는 provider-neutral candidate로 type, vehicle/cycle ID, 최신 위치와 optional metadata edge-count `stopsPastTarget`을 전달한다. FOLLOW_UP은 영속된 `status`, `vehicleTrackingId`, `startedAt`, `expiresAt`을 Evaluation이 사용해 동일 차량의 ONE_STOP_AFTER 또는 event 없는 expiry를 판단하며, 실제 scheduler/lifecycle 적용은 TASK-510이 맡는다.

TASK-510은 단일 Spring instance에서 Spring Scheduler의 synchronous fixed-delay 방식으로 구현한다. `transit.monitoring.enabled=false`가 기본이고, 명시적으로 켰을 때만 `PT20S` 기본 fixed-delay cycle이 이전 cycle 완료 뒤 시작된다. Provider HTTP I/O는 monitoring Alarm snapshot을 읽은 뒤 transaction/row lock 밖에서 수행하고, 결과 반영 직전에만 `PESSIMISTIC_WRITE`로 Alarm을 다시 조회한다. API activate/deactivate/delete도 같은 row-lock 원칙을 사용한다. `activation_generation BIGINT NOT NULL DEFAULT 0`은 `INACTIVE → ACTIVE`, `FOLLOW_UP → ACTIVE`에서 증가하고 `ACTIVE → ACTIVE`는 generation 증가와 baseline reset이 없는 idempotent 동작이다. Scheduler는 `(alarmId, activationGeneration)` key로 memory evaluation state를 분리하며, 잠금 재조회 결과의 generation/status가 request snapshot과 달라졌거나 row가 삭제됐으면 lifecycle과 memory state 모두 적용하지 않는다. TAGO group은 Route Location 호출 하나를 공유하고 Observation vehicle ID 전체를 현재 존재 집합으로 평가에 전달한다. 서울 group은 roster를 한 번 공유한 뒤 baseline, tracked/new vehicle, FOLLOW_UP vehicle에 필요한 detail만 조회해 `TransitObservation`으로 변환하며 roster `vehId` 전체를 detail Observation과 분리해 전달한다. detail normal empty는 차량 존재만 보존하고 위치나 Event를 만들지 않는다. ACTIVE 후보는 `ARRIVED`·`PASSED`·`ONE_STOP_BEFORE` 우선순위와 `vehicleTrackingId` 오름차순으로 하나만 채택하고 FOLLOW_UP은 `ONE_STOP_AFTER`만 사용한다. current ARRIVED가 after option으로 FOLLOW_UP이 되면 선택 차량의 `trackingCycleId`를 가진 state만 이어가며 시작·만료 시각은 Event `observedAt`의 UTC와 5분 timeout으로 정한다. `trackingCycleId`는 evaluation이 관리하는 logical vehicle tracking cycle identity이며 같은 logical cycle의 transaction retry에서 재생성하지 않는다.

TASK-511은 TASK-503의 `TRANSPORT`/`HTTP`/`PROVIDER`/`PROTOCOL` failure를 정상 empty와 분리해 Event 없는 UNKNOWN으로 처리한다. TAGO Route·서울 roster failure는 해당 Route만 평가하지 않고, 서울 detail 일부 failure는 roster presence와 성공 차량을 즉시 평가하되 실패 차량의 위치는 보존한다. 모든 첫 요청 뒤 실패 요청만 `PT5S` 지연 한 번으로 1회 재시도하며 재시도 단계의 새 실패는 반복하지 않는다. 최종 실패는 Event·Alarm lifecycle·기존 tracking state를 변경하지 않고 다음 fixed-delay cycle을 기다린다. Provider HTTP와 지연 중 DB lock을 유지하지 않으며 결과 적용 시 current status/generation 검증을 유지한다. Resilience4j, circuit breaker, 별도 비동기 retry framework는 도입하지 않는다.

---

# Phase 6 - Flutter Client

목표:

현재 목표는 iOS 개발 및 실제 기기 vertical slice다. Flutter Client를 실제 Backend API와 연결하고 V1 Alarm 생성·관리 흐름을 구현한다. Flutter는 미확정 Route DTO나 identity를 추측하지 않고, Transit monitoring 비즈니스 로직을 구현하지 않는다.

- [x] TASK-601 Flutter Google Login 및 Backend 인증 연동: 구현 전 bundle ID, Google iOS client ID, Backend server/web client ID, API origin 등 iOS 개발 설정을 검증한다.
- [x] TASK-602 Access/Refresh Token Pair Secure Storage 구현: pair 단위 저장·읽기·삭제와 손상된 저장값 처리를 구현한다. 구체 storage serialization API는 구현 시 결정한다.
- [x] TASK-603 인증 API Client 및 Access Token 적용 구현: Auth API와 authenticated API client의 최소 연결을 구현한다.
- [x] TASK-604 startup 인증 상태 복구 및 Access Token 만료/Refresh Rotation 연동: Auth Session이 current Token Pair, `initializing`/`authenticated`/`unauthenticated` 상태, refresh single-flight, Token Pair 교체와 Secure Storage 반영을 단일 책임으로 소유하게 한다. concurrent `401`은 single-flight로 처리하고 refresh 성공 뒤 원 보호 요청을 최대 한 번 재시도한다. refresh `401`은 Token Pair 제거와 unauthenticated 전환이며 network/offline/5xx는 장기 session을 즉시 삭제하지 않는다. `/auth/google`, `/auth/refresh`, `/auth/logout`는 refresh loop에 넣지 않는다.
- [x] TASK-605 Flutter Logout 구현: TASK-604와 같은 Auth Session coordination에서 logout과 refresh를 직렬화하고, session generation 또는 동등한 보호로 late refresh/API response가 logout 뒤 local auth state를 되살리지 않게 한다. Phase 7이 현재 Device unsubscribe/disable을 연결할 logout lifecycle/hook을 제공하며 서버에 이미 도착한 요청 취소나 Access Token blacklist는 가정하지 않는다.
- [x] TASK-606 Flutter Bus Route 검색 연동: TASK-513 → TASK-505 완료 뒤 구현하며 loading, empty, error, retry 상태를 제공한다.
- [x] TASK-607 Flutter Bus Stop 조회 및 선택 구현: TASK-506 완료 뒤 구현하며 loading, empty, error, retry 상태와 `canNotifyOneStopBefore`/`canNotifyOneStopAfter` 기반 option 비활성화를 제공한다.
- [x] TASK-608 Flutter Alarm 생성 및 관리 화면/API 연동: TASK-607 뒤 구현하며 `INACTIVE`/`ACTIVE`/`FOLLOW_UP`을 그대로 표현한다. Alarm 목록/조회에는 loading, empty, error, retry 상태를 제공하고 mutation 진행 중 중복 입력을 막는다. stale `targetStopOccurrenceId`의 생성 `404`는 old ID 추측 매칭·자동 POST 재시도 대신 Stop 목록 재선택 흐름으로 복구하며, idempotency contract가 없는 create timeout도 자동 POST 재시도하지 않는다. 실제 ACTIVE monitoring 종단 검증은 Phase 5 monitoring 완료 후 수행한다.
- [x] TASK-608A Bus Stop 방향 식별 metadata/API/UI 보완: TASK-607 실제 iPhone smoke에서 Stop 목록만으로 어느 진행 방향의 occurrence인지 구분하기 어렵다는 문제가 확인됐다. 동일 정류장명이 Route에 여러 번 등장하거나 왕복·순환·회차 Route에서는 잘못된 occurrence를 선택할 UX 위험이 있다. 현재 Stop API에는 신뢰 가능한 direction display field가 없다. Flutter는 Stop order, Stop name, Route number, `regionName`, 기점/종점 이름의 임의 조합, 의미가 검증되지 않은 Provider raw field로 방향을 추측하지 않는다. 먼저 Backend metadata 기준으로 신뢰 가능한 방향/행선지/traversal 표시 계약을 확정하고, 필요할 경우 이를 표현하는 최소 범위만 metadata/API에 추가한 뒤 Flutter Stop 선택 UI에서 occurrence를 충분히 구분할 수 있게 표시한다. Provider별 데이터가 실제 제공할 수 없는 정보는 만들어내지 않는다. 실제 iPhone에서 왕복·순환·동일 정류장명 등 대표 사례를 확인한다. 구체 field 이름, DB column, response JSON 구조, Provider별 direction mapping은 이 Task의 설계/구현에서 결정한다.

TASK-608A의 metadata/API/UI 구현과 자동 검증 뒤에도 실제 iPhone smoke가 끝나기 전에는 위 checkbox를 완료 처리하지 않는다.

- [x] TASK-608B Alarm 생성 후 즉시 활성화 UX 보완: TASK-608 초기 구현은 correctness 확인을 위해 사용자 `알람 만들기` 동작에서 Backend create를 호출하고 `INACTIVE` Alarm을 생성한 뒤, 사용자가 상세에서 별도로 `활성화`한다. 최종 Flutter UX는 사용자의 한 번의 `알람 시작` 동작으로 create 성공 후 activate를 이어서 수행한다. Backend `POST /api/v1/alarms`의 새 Alarm `INACTIVE` 생성 계약은 유지하고 Flutter orchestration으로 create → activate를 연결한다. create 성공 후 activate 성공 시 ACTIVE Alarm으로 완료하며, activate 실패 시 이미 생성된 Alarm을 잃거나 create를 다시 호출하지 않고 생성된 `INACTIVE` Alarm 상세로 복구해 재활성화를 시도할 수 있게 한다. create timeout/network처럼 결과가 불명확하면 TASK-608 계약대로 같은 create POST를 자동 재시도하지 않고 Alarm 목록에서 생성 여부를 확인하게 한다. activate 실패를 이유로 create를 다시 호출해 duplicate Alarm을 만들지 않으며 mutation 중 중복 사용자 입력도 막는다. 실제 iPhone에서 Stop 선택 → `알람 시작` → ACTIVE 흐름과 partial-success 복구를 확인한다.
- [x] TASK-609 Flutter Client Authentication / Transit / Alarm E2E·regression Test 보강: 각 Task 테스트를 몰아서 작성하는 Task가 아니라, TASK-608A/B 완료 뒤 최종 Flutter 인증·Transit·Alarm 흐름의 E2E·regression을 보강한다. 실제 iPhone에서 session 복구, Alarm 생성·Route/Stop 선택·활성화, 목록 복귀, 비활성화·재활성화 및 logout 흐름을 최종 smoke 검증한다.

TASK-601~605 Authentication lane은 Backend Authentication이 준비되어 있으므로 Phase 5와 병행할 수 있다. TASK-606은 TASK-513 → TASK-505 뒤, TASK-607은 TASK-506 뒤, TASK-608은 TASK-607 뒤 진행한다. TASK-608A와 TASK-608B는 TASK-608의 후속 보완 작업이며, TASK-609는 두 Task 완료 뒤 최종 E2E·regression을 수행한다. offline mutation queue/cache, generic `Repository`/`BaseRepository`, 모든 API별 `UseCase` class, global event bus, 복잡한 refresh queue framework, notification stub은 이번 Phase에 도입하지 않는다. state management library도 특정 제품으로 확정하지 않는다.

Phase 6은 Phase 7에 안정적인 Auth Session state, 자동 refresh를 포함한 authenticated API client, logout lifecycle/hook, Alarm ID 기반 navigation 진입점, app resume 시 Auth Session 재평가 가능 지점을 제공한다. Device/FCM 구현은 TASK-701~704에서 맡는다. Flutter logout은 이 hook에서 현재 Device disable을 시도한 뒤 Auth logout과 local session 종료를 수행하는 방향이며 offline에서 Backend disable을 즉시 보장하지 않는다. Apple Login과 App Store 계정 삭제 요건은 별도 release readiness에서 재검토한다.

---

# Phase 7 - Notification

목표:

Alarm 조건 충족 시 logical Event와 Event×Device Delivery의 DB uniqueness를 보장하고 실제 기기로 Push notification 전달을 시도한다. FCM request는 bounded retry로 여러 번 발생할 수 있으며 실제 Device 표시 exactly-once는 보장하지 않는다.

현재 상태 (2026-10-30): TASK-701~709 구현 완료, TASK-710/711 최종 검증 미완료다. 아래 TASK-706의 handler 부재·자동 활성화 불가와 TASK-707/708의 후속 구현 표기는 각 완료 당시 기록이다. 이후 TASK-709에서 production Handler·retry/expiry/cleanup을 연결했고 FCM 정적 설정 gate 아래 명시적 dispatch 활성화를 지원한다. 기본 false와 최종 실기기 검증 책임은 유지한다.

- [x] TASK-701 FCM iOS 지원 적합성 및 targeting contract 확정: 실제 사용할 FlutterFire/firebase_messaging, Firebase iOS SDK, Java Firebase Admin SDK 버전을 확인하고 현재 지원되는 targeting identifier와 local unregister 동작을 확정한다. Firebase→실제 iPhone early smoke를 수행하며 APNs token을 StopBell Device identity로 사용하지 않는다.
- [x] TASK-702 Device Domain/Schema 및 registration/logout contract 정의: 내부 PK + client-generated installationId + current push targeting identifier를 분리한다. `installationId`는 User-scoped가 아닌 앱 installation identity로 두고 동시에 current owner가 최대 한 명이 되도록 하며, User 전환 시 atomic ownership takeover 또는 동등한 계약으로 두 ownership이 함께 enabled 상태로 남지 않게 한다. Multi-device, registration rotation, monotonic revision 또는 동등한 stale-write 방지, 별도 authenticated disable/unregister lifecycle을 정의한다. V13 Device/Platform 매핑과 단일 Firebase Project의 case-sensitive nullable FID UNIQUE를 추가했다. 별도 installation credential hash와 서버 ownership generation으로 권한 없는 이전·old-owner 요청을 차단하는 계약을 확정했다. RefreshToken FK와 단일 Device 제한은 두지 않는다. 실제 API/동시성 구현은 TASK-703 책임이다.
- [x] TASK-703 Device registration/disable API 구현: TASK-702의 JWT + 설치본 자격증명, 등록·상태 조회·disable 계약과 엄격한 입력 검증을 구현했다. write lock과 UNIQUE 충돌 rollback/새 transaction 재확인으로 최초 등록 race 및 FID 충돌을 처리한다. revision·ownership generation, A→B→A 지연 요청 차단, 이전 응답 유실 재전송과 무변경 멱등성, 민감정보 비노출을 집중 MySQL API 테스트 12개로 검증했다. V13/Dependency/Auth logout/Alarm/Flutter는 변경하지 않았다.
- [x] TASK-704 Flutter Push permission, registration 및 tap lifecycle 구현: secure 설치본 identity/credential/counter·pending과 재설치 marker, production native FID bridge, 첫 INACTIVE/FOLLOW_UP activation permission UX와 denied/실패 시 계속 진행, startup/resume/FID rotation 및 session/ownership 동기화, bounded logout disable, 인증된 Alarm detail 기반 tap/foreground를 구현했다. 집중 correctness 및 직접 관련 Auth/API/bootstrap 회귀 테스트, release/native 컴파일과 실제 iPhone 등록·동기화·logout·같은 계정 재등록·A→B→A를 검증했다. 실기기 재설치/강제 rotation/permission 거부 전환 및 전체 수신/tap E2E의 미검증 범위는 [Local Development](local-development.md#task-704-정식-ios-client-검증)에 기록한다. TASK-705/706/708/709/710/711 구현과 Backend 계약은 변경하지 않았다.
- [x] TASK-705 Push provider client 구현: **코드 구현·집중 자동 검증 및 사용자 Backend→FCM→실제 iPhone Smoke 완료** (2026-10-12). 기존 Google dependency의 단일 HTTP v1 `message.fid` adapter, 여섯 result/failure 분류, 한국어 alert/navigation hint, 외부 Service Account 인증, 인증/전송 재시도 차단과 전체 budget/abort, 민감정보 비노출 및 local opt-in Smoke를 구현했다. 사용자가 Firebase ACCEPTED와 iPhone 13 Pro Max의 실제 알림 수신을 확인했다. Codex는 실전송을 수행하지 않았다. 검증 범위와 후속 미검증 항목은 [수동 Smoke 기록](local-development.md#task-705-backend-provider-수동-smoke)을 따른다. accepted 자체는 실제 표시 성공을 의미하지 않는다.
- [x] TASK-706 Notification orchestration 및 durable pending dispatch worker 구현: **Worker 구조·집중 자동 검증 완료, TASK-709 연동 대기** (2026-10-12). 단일 fixed-delay/non-overlapping Worker, 기존 bounded due PENDING ID 조회, current Device owner/generation/enabled/FID/revision preflight, immutable attempt snapshot과 시작 commit 확인·별도 결과 transaction, transaction 밖 기존 FcmProviderClient 호출 경로 및 직전 expiry 재확인을 구현했다. unit 10개·Spring configuration 4개·격리 MySQL 통합 8개, Java compile과 diff 검증을 통과했다. `notification.dispatch.enabled=false`가 기본이며 **TASK-709 실제 handler 미구현으로 true 설정도 startup 구성 오류로 차단한다. 현재 자동 FCM 전송은 활성화할 수 없다.** [구현·설정·검증 경계](architecture.md#notification-dispatch-worker-구현-task-706)를 따른다. 실제 durable accounting/retry/result 전이/expiry/cleanup/응답 유실 복구는 TASK-709 책임이고 운영 dispatch 완성을 뜻하지 않는다. Event/fan-out·Alarm lifecycle·Dependency·Migration·Flutter는 변경하지 않았으며 실제 Firebase/iPhone 전송과 전체 Backend suite는 실행하지 않았다.
- [x] TASK-707 NotificationEvent / NotificationDelivery persistence 및 durable outbox DB 기반 구현: V14, 불변 Event identity/owner/UTC 시각/실제 optional 위치 근거, Event×Device Delivery와 TASK-709 operational fields·CHECK·due 조회/index를 적용했다. FOLLOW_UP 원래 UUID 저장/제거와 ARRIVED 연결, Alarm 삭제 시 기록 보존 및 PENDING의 원자 FAILED/DISPATCH_NOT_ALLOWED 종료를 구현했다. 빈 legacy History table만 제거하고 데이터가 있으면 table/row와 원본 Alarm ID를 보존하며 cascade FK만 제거한다. JPA Schema validation과 핵심 MySQL 제약·삭제 rollback·Migration guard를 검증했다. Worker/FCM 전송, atomic decision/dedup/fan-out/UUID 복원, retry/result/cleanup은 후속 Task 책임이다.
- [x] TASK-708 Duplicate Prevention 설계 및 구현: **설계·구현·집중 검증 완료** (2026-10-12). `(alarmId, activationGeneration, trackingCycleId, eventType)` exact current locking read와 기존 두 UNIQUE, lifecycle/Event/최초 0/N recipient Delivery의 atomic commit·전체 rollback, 무변경 duplicate 및 원래 candidate의 제한 DB 재시도, persisted FOLLOW_UP UUID 복원과 Scheduler commit 확인 후 memory 반영을 구현했다. MySQL decision 12개·unit 52개·기존 monitoring 통합 6개가 모두 통과했고 Java compile 및 diff 검증을 완료했다. [구현 경계와 provisional Type별 TTL](architecture.md#notification-decision-transaction-task-708)을 따른다. FCM 전송·Worker·운영 retry/expiry/cleanup·실기기 E2E는 후속 범위이고 ACTIVE restart의 Phase 5 한계는 유지한다.
- [x] TASK-709 Notification failure/retry 설계 및 구현: **코드 구현·집중 자동 검증 완료** (2026-10-13, 구현 커밋 `e8c447c`). Production `DurableNotificationAttemptHandler`, 최초 포함 precommitted attempt budget, 여섯 Provider result 전이, bounded exponential retry/jitter와 Provider 최소 지연, 불변 expiresAt 기반 expiry/recovery, current owner/generation/revision/FID 조건부 UNREGISTERED cleanup을 구현했다. 시작 commit 응답 유실 전송 차단과 동일 memory result의 제한 DB 재반영, terminal/stale result no-op 및 cleanup 원자 rollback을 검증했다. 집중 테스트 95개(Handler 30·Retry/TTL 6·Worker 10·configuration 6·격리 MySQL 39·Mock Smoke 4), Java compile 및 diff 검증을 통과했으며 실패 fixture 4건은 수정 후 관련 클래스만 재검증했다. [실행 경계](architecture.md#notification-dispatch와-result-transaction-task-709)와 [provisional 설정/안전 상한](adr/ADR-010-notification-device-and-durable-delivery.md#task-709-구현-설정과-검증-2026-10-13)을 따른다. 기본 dispatch=false를 유지하고 명시적 활성화는 FCM 정적 설정 gate를 요구하며 런타임 CONFIGURATION은 미시작 row를 보존하고 expiry만 처리한다(설정 복구 후 Backend 재시작). 새 Dependency/Migration/Flutter 변경은 없다. 실제 자동 Firebase/iPhone 수신·tap·process restart·전체 regression과 latency tuning은 TASK-710/711에 남으며 실제 전송·Service Account·개발 DB 및 전체 Backend suite는 사용하지 않았다.
- [ ] TASK-710 실제 iPhone foreground/background/terminated 수신 및 tap 검증: payload가 최소 navigation hint이고 tap 뒤 ownership/current state를 Backend에서 재검증하며 stale notification을 정상 처리하는지 확인한다. **진행 중**: 2026-10-16 실제 버스 이벤트 기반 자동 FCM의 foreground/background/terminated 수신, background tap 상세 이동 및 MySQL `NotificationDelivery`의 `ACCEPTED`를 확인했다. 2026-10-28 USB release 실기기에서 A terminated tap의 정확한 상세 이동, B 삭제된 임시 Alarm tap의 접근 불가 안내·상세 미노출, C B 계정에서 이전 A 알림 tap의 접근 차단을 사용자 확인하여 **PASS**로 기록했다. 신규 Boarding은 TAGO 후보·차량번호 표시·지원 보류 안내·버튼 차단까지 PASS이나, 실기기 서울 후보 조회의 Backend 503으로 후속 전체 차량번호 입력·Push 준비·생성 거부 안내는 **BLOCKED / NOT_TESTED**다. 후속 진단에서 별도 Java 호출의 상류 401과 StopBell Security 401을 구분하고, 공식 가이드·사용자 성공 URL로 확인한 서울 roster/detail Call Back path 구성 차이를 최소 수정했다. Backend 62개·Flutter 43개 회귀 테스트가 통과했으나 수정 후 실제 Provider 재호출은 0회로 401 해소는 미확인이다. 후속 공식 HTTP 공통 지원 작업에서 개발·운영 설정과 XML 처리를 보강하고, 위험 고지·최대 2회 사용자 승인 뒤 서울143 roster HTTP 200/headerCd=0/44건·Candidates API 200/PARTIAL/44건을 확인했다. 실제 BoardingSession은 0건이며 iPhone 후속 UI는 재검증하지 않았다. 현재 요청의 필수 실기기 범위가 남아 `[ ]`를 유지한다. [실기기 검증 기록](local-development.md#task-710-실제-iphone-수신-및-tap-검증)을 따른다.
- [ ] TASK-711 Notification 최종 E2E/regression Test 보강: 각 선행 Task의 correctness test를 대체하지 않고 durable recovery, multi-device fan-out과 전체 Notification 흐름을 최종 검증한다.

실행 dependency는 다음과 같다. TASK-708/709는 선행 설계와 후행 구현 단계를 가지며 Task 번호와 완료 상태는 변경하지 않는다.

TASK-707은 [확정된 identity Schema/UNIQUE](database.md#phase-7-notification-persistence-contract), [TASK-709 operational fields/CHECK/due index](database.md#notificationdelivery-operational-schema-task-709)와 FOLLOW_UP UUID runtime의 Migration·Alarm mapping·저장 연결을 구현했으며, History 조건부 보존과 Alarm 삭제 시 기록 보존/PENDING 종료를 확정했다. TASK-708은 Event candidate 연결·atomic lifecycle/Event/최초 recipient Delivery 결정·dedup/DB 재시도·FOLLOW_UP UUID 복원을 구현했다. TASK-706은 commit된 Delivery dispatch만 구현하며 TASK-709는 failure/retry/expiry를 소유한다. correctness test도 [TASK-708 분담](adr/ADR-010-notification-device-and-durable-delivery.md#후속-최소-구현과-검증-분담)과 [TASK-709 분담](adr/ADR-010-notification-device-and-durable-delivery.md#logging-metric과-구현-분담)에 맞춰 작성한다.

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

FCM targeting identifier와 Device field/column 길이는 TASK-701/702, activation generation 증가와 row-lock 검증은 TASK-510의 계약을 유지한다. Notification identity Schema/constraint 이름은 TASK-708 설계에서 확정했으며 적용은 TASK-707, retry 수치는 TASK-709 책임이다. MySQL durable pending dispatch와 단일 non-overlapping in-process worker를 사용하며 Kafka, RabbitMQ, Redis queue, Notification microservice, event sourcing, generic multi-provider/retry framework, Device subtype hierarchy, APNs direct client, multi-instance distributed lock, claim/lease, `claimedAt`, `SENDING`, stale-claim recovery는 V1에 도입하지 않는다.

---

# Phase 8 - Quality and Operations

목표:

Phase 5/7에서 구현한 correctness를 더 넓은 race, restart, 실제 환경 조건에서 검증하고, V1 운영에 필요한 최소 품질을 갖춘다. Phase 8은 activation generation, stale scheduler result 보호, Alarm Evaluation/tracking의 중복 억제, lifecycle concurrency, NotificationEvent atomic uniqueness, lifecycle·logical NotificationEvent·Event 시점 recipient Delivery의 atomic commit, durable pending dispatch recovery, Device별 delivery uniqueness, failure/retry state를 처음 구현하는 단계가 아니다.

- [x] TASK-801 Alarm Evaluation Test 보강
- [x] TASK-802 Duplicate Prevention Test 보강
- [x] TASK-803 외부 provider response mapping Test 보강: 실제 관찰 response를 민감 정보를 제거한 golden fixture로 만들고 synthetic edge fixture를 함께 사용한다. fixture에는 provider, operation, 관찰 시점 또는 provenance를 남기되 credential은 제거한다. 실제 Provider 호출이나 scheduled live canary는 일반 PR CI 또는 V1 필수 infrastructure로 두지 않는다.
- [x] TASK-804 API validation/error handling Test 보강: 오류 body shape를 안정적으로 통일하되 모든 오류 의미를 합치지 않는다. `INVALID_REQUEST`, `UNAUTHENTICATED`·`ACCESS_DENIED`, resource-specific `NOT_FOUND`, `TARGET_STOP_OCCURRENCE_NOT_FOUND`, 명시적 concurrency/stale conflict, Google·Transit·일반 서비스 장애의 구분된 503, `INTERNAL_SERVER_ERROR`와 MVC 상태 보존을 검증하며 예상치 못한 Exception을 400으로 바꾸지 않는다.
- [ ] TASK-805 구조화된 Logging, 최소 Operational Metrics 및 Alerting contract 추가
- [ ] TASK-806 Secret 관리 검토
- [ ] TASK-807 Health / Readiness 계약 검증
- [ ] TASK-808 재현 가능한 Backend runtime image 구성
- [ ] TASK-809 CI build/test 구성
- [ ] TASK-810 실제 iPhone 환경에서 notification latency 측정
- [ ] TASK-811 restart / graceful shutdown / recovery 안전성 검증
- [ ] TASK-812 Analytics / 장기 통계 필요성 결정 및 필요한 경우에만 최소 구현
- [ ] TASK-813 Production deployment / migration / backup & recovery readiness
- [x] TASK-814 Production UTC time contract 및 timestamp consistency
- [ ] TASK-815 iOS public App Store release readiness
- [x] TASK-816 프로젝트 종합 감사 후속 결함 수정
  - 목적: 종합 감사에서 발견한 AUD-01~AUD-13을 중요도에 따라 검토·수정하고 관련 테스트와 문서를 보강한다. 하나의 상위 Task로 관리하며 실제 수정·필수 검증 완료 전까지 `[ ]`를 유지한다.
  - 등록 범위 (2026-10-29): Task와 작업 계획만 추가한다. 이번 등록은 코드·Migration·테스트·Dependency·DB 변경이나 실제 Provider 호출·FCM 발송을 포함하지 않으며, 후속 지시 없이 수정에 착수하지 않는다. 기존 Task 번호와 완료 상태를 유지한다.

### TASK-803 — Provider 응답 매핑 검증 (2026-10-31)

기준 `19aa981d4317a6b6b566842d8cc48713468debb2`. 기존 Client·Mapper 테스트가 정상 empty와 Provider/Protocol/HTTP 오류, TAGO count 불일치 및 차량 ID 누락, 서울 JSON/XML·XXE·시각 및 차량 ID 오류를 검증한다. Scheduler·평가기 테스트는 부분 실패, 오래된·동일·역행 시각 및 충돌 관측을 확인한다.

`backend/src/test/resources/transit/task803/README.md`에 출처와 한계를 남겼다. 2026-09-09 실측 문서의 TAGO 300번 Location과 서울 roster·detail 도착→운행 관찰을 `DOCUMENTED_OBSERVATION_RECONSTRUCTION`으로 재구성했다. 원본 전체 응답이 아니며 item 수와 envelope는 축약·구성했다. 차량·정류장과 요청 노선은 가명, TAGO GPS는 인공 대체값이고 서울 GPS·차량번호는 생략했다. 각 클라이언트 DTO부터 Observation까지 요청 노선, 위치 순서, 좌표, 서울 flag와 KST→UTC 변환을 검증한다. 기존 및 신규 테스트의 정상 empty·잘못된 JSON/숫자·불완전 응답·XXE 등은 `SYNTHETIC`이다. 실제 보존 원본을 비식별한 `OBSERVED_SANITIZED` fixture는 없다.

Java/Gradle JVM 21과 offline Gradle을 사용했다. Provider 집중 6개 클래스 52회, Scheduler·평가기 2개 클래스 107회, Backend 전체 83개 클래스 1,435회 모두 실패·오류·skip 0이다. JUnit XML 집계이며 전체 종료 중 종료된 격리 DB에 대한 연결 풀 경고가 있었으나 Gradle과 XML은 성공이다.

추가 테스트는 재구성 흐름 2개와 TAGO 합성 오류 1개이며 제품 결함이나 제품 코드·Dependency 변경은 없다. 서울 `sectOrd`를 detail `stOrd`와 같은 occurrence로 가정하지 않는다. 원본 전체 응답, 실제 전체 차량 목록 및 GPS 정밀도, Provider 현행 동작·실시간 지연·물리적 정차와 기기 수신은 검증하지 않았다. 실제 Provider·Credential·FCM·운영 DB는 사용하지 않았다.

### TASK-801 — Alarm Evaluation 검증 (2026-10-31)

기준 `a4b6181d3413970e8b594446c05bf30927f111c9`, 기존 작업 트리 변경 없음. [평가 정책](architecture.md#8-알림-평가)과 ADR-007/010을 기준으로 기존 구현과 테스트를 확인했다. 제품 코드, 정책 임계값, Dependency, Request Identity 및 Notification 중복 방지 계약은 변경하지 않았다.

| 영역 | 기존 검증과 중복 테스트를 추가하지 않은 근거 |
|---|---|
| Baseline·판정 | `BusAlarmEvaluatorTest`가 predecessor의 BEFORE, 서울 직접 ARRIVED와 TAGO GPS 근거, 추적 차량 PASSED 및 우선순위를 확인한다. ID/order 충돌은 UNKNOWN으로 보존한다. |
| 시각·Observation | 같은 차량 중복 관측 격리, stale/future, Provider 시각 동일·역행·누락, 수신시각과 order 역행 및 정상 관측 복구를 확인한다. 같은 정류장 ID의 다른 order 충돌도 기존 테스트가 차단한다. |
| 소실·FOLLOW_UP | roster presence와 상세 부재를 분리하고 실제 부재 뒤 새 cycle을 확인한다. FOLLOW_UP 복구 UUID, 다른 차량 무시, 중복 및 시각 충돌과 복구를 검증한다. |
| Scheduler·Lifecycle | scheduler 테스트는 실제 evaluator의 오래된 재시도 차단과 정상 도착 복구 및 stale 결과의 memory 미반영을 확인한다. lifecycle과 MySQL decision 테스트는 generation, ARRIVED→FOLLOW_UP→INACTIVE, 만료, 원자적 Event/Delivery와 stale 결과를 확인한다. |

신규 테스트는 4개 메서드, parameter 사례를 포함해 7회 실행이다. TAGO GPS 100m 양쪽, 방향 충돌로 인한 잘못된 ARRIVED/PASSED 차단과 동일 cycle 복구, FOLLOW_UP 만료 직전·정각·이후, ACTIVE memory 초기화 후 baseline-after의 거짓 PASSED 방지를 확인한다. 필수 제품 결함은 발견되지 않았다.

Java/Gradle JVM 21 및 offline Gradle, 기존 Testcontainers MySQL을 사용했다. 관련 5개 클래스는 132회, Backend 전체 82개 클래스는 1,432회 실행했으며 각각 실패 0·오류 0·skip 0이다. 수치는 JUnit XML 집계다. 전체 실행 종료 중 종료된 테스트 DB에 대한 연결 풀 경고가 있었으나 Gradle과 XML은 성공이다. 실제 Transit Provider·FCM·기기 호출은 수행하지 않았다. ACTIVE 메모리 초기화는 실제 프로세스 재시작이 아니며 초기화 전 Event와 새 cycle의 중복 억제는 보장하지 않는다. 실제 restart/recovery는 TASK-811, 실제 Provider fixture와 기기 검증은 TASK-803·710·711·908의 책임을 유지한다. 순환 노선의 동일 Stop ID가 다른 order로 관측되는 경우 기존 보수적 충돌 처리이며 실제 운행에서의 occurrence 판별은 이번 synthetic 테스트로 입증하지 않는다.

### TASK-802 1단계 — 기존 중복 방지 검증 보강 및 Alarm 생성 멱등성 설계 판단 (2026-10-31)

시작 기준은 `main`의 `41d3211cefb945a735b54c7e7cce2e24384fe659`, 기존 변경 없음이다. 이 단계는 TASK-802 완료나 새 API 정책의 확정이 아니다. TASK-802와 다른 모든 체크박스를 유지한다.

#### 구현과 기존 Coverage Matrix

Evaluation은 차량 추적 상태와 cycle UUID로 소비된 후보를 억제한다. Lifecycle Service는 Alarm row write lock 후 generation과 현재 상태를 재검증하고 Event exact locking read를 수행한다. 동일 logical identity `(alarm_id, activation_generation, tracking_cycle_id, event_type)`는 `uk_notification_events_logical_identity`, 동일 Event·Device는 `uk_notification_deliveries_event_device`가 강제한다. Lifecycle 변경, Event와 최초 eligible Device별 Delivery는 같은 REQUIRES_NEW transaction에서 commit 또는 rollback된다. 이후 dispatch는 별도 transaction과 외부 전송이다. 상세 계약은 [Architecture](architecture.md#notification-decision-transaction-task-708)와 [Database](database.md#phase-7-notification-persistence-contract)를 따른다.

| 영역 | 시나리오 | 기존 assertion 근거 | 수준 | 공백과 조치 |
|---|---|---|---|---|
| Evaluation | 같은 관측·cycle 반복 | `BusAlarmEvaluatorTest.uses_predecessor_snapshot_and_deduplicates_before`는 후보가 비어 있음을 확인 | Unit | 중복 추가 불필요 |
| Tracking | 차량 부재 뒤 새 cycle | `BusAlarmEvaluatorTest`의 missing grace 및 FOLLOW_UP UUID 불일치 테스트 | Unit | 실제 restart는 TASK-811 |
| Generation | 중복 활성화·재활성화·늦은 결과 | `BusAlarmDecisionIntegrationTest.distinguishes_all_identity_components`와 `preserves_follow_up_and_rejects_stale_lifecycle`는 generation과 stale 결과 확인 | MySQL | 중복 추가 불필요 |
| Event | 동일 identity·payload·시각·늦은 Device | `commits_fan_out_and_preserves_duplicate`는 전체 Event/Delivery/Alarm row를 비교 | MySQL | 중복 추가 불필요 |
| Event | 다른 type·cycle·generation | `distinguishes_all_identity_components`와 `NotificationPersistenceIntegrationTest.persist_identity_and_enforce_uniqueness` | MySQL | 다른 Alarm 독립 identity는 schema 및 코드 정적 확인 |
| Delivery | 동일 Event·Device와 다른 Device | persistence UNIQUE 위반 및 decision의 다중 recipient assertion | MySQL | 중복 추가 불필요 |
| Delivery | ownership A→B→A·revision·terminal | decision의 `current_read_coordinates_ownership_transfer`, `NotificationDispatchIntegrationTest`의 owner/generation 및 FID rotation, persistence의 terminal 보존 | MySQL, 외부 Provider 대체 | 실제 FCM 표시 검증 아님 |
| Transaction | Event 뒤 Delivery UNIQUE/FK/CHECK 실패 | `rolls_back_everything_on_delivery_failure`, `rejects_delivery_unique_as_event_duplicate`는 lifecycle·Event·Delivery rollback 및 오류 전파 확인 | MySQL | Event INSERT의 독립 FK/CHECK 오류 주입은 미검증 |
| Transaction | commit 응답 유실 | `reconciles_lost_commit_response`는 실제 commit 뒤 예외와 원본 Event/Delivery 유지 확인 | MySQL, 응답 유실 주입 | 실제 네트워크 단절은 미검증 |
| Transaction | commit 전 rollback | 기존 Delivery 오류 rollback과 unit 재시도는 결합된 commit 경계 재시도를 직접 확인하지 않음 | MySQL | `retries_original_candidate_after_pre_commit_rollback` 추가 |
| Concurrency | 동일 candidate | `serializes_concurrent_same_candidate`는 두 작업의 COMMITTED/DUPLICATE 및 한 Event/Delivery 확인 | MySQL, 동시 시작 latch | 실제 deadlock은 미주입. unit `bounds_transient_database_retries`의 transient 예외와 두 번 제한은 별도 확인 |
| Lifecycle | deactivate/reactivate/delete 경쟁 | `coordinates_alarm_mutation_with_decision`은 mutation이 잠금을 먼저 얻은 순서의 stale 및 무생성 확인 | MySQL, latch | 반대 순서의 명시적 경쟁은 미검증 |
| Restart | FOLLOW_UP UUID 복구 | `preserves_follow_up_and_rejects_stale_lifecycle`는 빈 memory로 재평가한 원래 UUID와 after·만료 확인 | MySQL 및 evaluator, process 재시작 아님 | 실제 restart/ACTIVE 연속성은 TASK-811 |
| Alarm Create | 동일 POST | `api.md`의 Request ID 부재; 생성 멱등성 계약 없음 | 정적 분석 | 아래 정책 결정 필요 |

Scheduler mock 테스트 `replays_original_decision_until_commit_is_confirmed`는 원래 candidate·시간을 재사용하고 duplicate 확인 뒤 memory에 반영하며 `refuses_memory_for_incompatible_duplicate`는 generation/cycle 불일치 시 반영하지 않음을 확인한다. 신규 MySQL 테스트는 첫 commit 전에 실제 rollback해 ACTIVE와 빈 runtime/Event/Delivery를 확인하고, 다음 transaction이 같은 generation·UUID·type으로 한 Event와 한 Delivery를 commit함을 확인한다. 이는 실제 DB 장애나 process crash 재현은 아니다. FCM 접수 뒤 응답 유실·종료 시 기기 중복 표시 및 OS exactly-once는 보장하지 않는다. TASK-709의 retry 정책과 TASK-811의 restart 책임을 유지한다.

#### Alarm 생성 멱등성: 결정 전 비교 및 권고

| 선택 | 장점 | 한계 |
|---|---|---|
| A 현 API 유지 | DB/API 변경과 배포 부담이 없고 기존 Client 호환 | 응답 유실 시 생성 결과를 정확히 식별할 수 없고 수동 재생성·복수 기기에서 중복 가능; 자동 재전송 금지와 현재 안내 유지 |
| B 서버 요청 identity | 동일 원본 요청의 commit 불확실성을 안전하게 복구 가능 | 저장·보존·삭제·충돌 계약, migration 및 durable Client intent가 필요하고 배포 순서와 운영 복잡도가 증가 |

**권고: V1의 안정적인 생성 복구를 위해 B를 도입하는 것이 필요하다.** 사용자 경험상 화면 잠금과 목록 확인은 특정 요청 결과를 입증하지 못한다. 단, 이는 구현 제안이며 승인된 계약이 아니다. 서로 다른 기기에서 각자 새 ID를 발급한 독립 요청까지 자동으로 같은 의도로 합치지는 못한다.

최소 후보는 인증 User와 Client Request ID의 복합 UNIQUE를 갖는 별도 durable 요청 기록, canonical 원본 요청 fingerprint와 결과 참조를 Alarm 생성과 같은 transaction에 저장하는 것이다. 같은 User/ID·같은 body는 최초 결과를 재조회하고 다른 body는 conflict, 다른 User의 같은 ID는 독립 범위로 처리한다. 동시 요청은 DB UNIQUE를 최종 방어로 사용하고 실패 transaction을 종료한 뒤 새 transaction으로 exact 조회하며 rollback에는 기록이 없어 lifecycle/입력을 재검증한다. 서버 재시작과 commit 후 응답 유실도 동일 ID로 복구한다. 원본 metadata snapshot과 요청 fingerprint를 현재 metadata 변경과 분리해야 한다. 기존 Event/Delivery identity는 이 생성 요청 identity와 독립이다.

삭제 후 재전송에 새 Alarm을 만들지 않도록 별도 tombstone/결과 기록을 보존하는 방식을 권고한다. 삭제 후 응답을 404/410/별도 결과 중 무엇으로 표현할지, 보존 기간·만료 후 ID 재사용과 보장 범위, canonicalization, 최초 응답과 현재 상태 중 반환 방식, 조회 endpoint, 개인정보와 저장 상한은 개발자 결정이 필요하다. 무기한 보존 또는 임의 TTL을 이번에 확정하지 않는다. Alarm row에 UUID만 두면 삭제 뒤 중복 방지와 결과 복구를 보장하지 못한다.

Backend는 계약 확정 후 API/Service, 원자적 요청 기록 및 migration, conflict/조회·보존 처리와 동시성 테스트가 필요하다. Flutter는 전송 전 User/session에 묶인 원본 ID와 body의 durable pending intent, 재시작 후 복구 및 계정 전환 보호가 필요하다. 기존 Alarm row는 별도 신규 요청 기록 없이 호환 가능하나 과거 요청을 소급 식별할 수 없다. 이전 Client에 필드를 optional로 허용하면 하위 호환되지만 그 요청은 멱등성이 없다. 필수 전환 여부·배포 순서와 혼합 버전 운영 정책은 별도 결정한다. DB migration의 lock/rollback·보존량, 중간 버전 배포와 응답 변경 위험을 검토해야 한다. 신규 테스트는 User 격리, body conflict, 순차/동시 중복, commit 응답 유실, rollback, 삭제·만료·재시작, metadata 변경 및 legacy Client와 기존 Notification 불변을 포함한다. 실제 HTTP와 process restart를 각각 구분한다.

검증 결과: Java/Gradle JVM 21, offline Gradle 및 Testcontainers MySQL 사용. 변경 클래스 집중 13개, Alarm·Notification·Boarding 관련 회귀 885개, Backend 전체 1,408개가 각각 실패 0·오류 0·skip 0으로 통과했다. 수치는 각 실행의 JUnit XML 집계다. 최초 집중 실행은 테스트의 UUID와 문자열 비교 오류 1건으로 실패했으며 assertion 타입을 수정한 뒤 재실행했다. 관련 및 전체 실행 종료 중 이미 종료된 테스트 DB 연결에 대한 pool 경고가 있었으나 Gradle과 XML은 성공이다. 실제 FCM/Transit 호출과 Flutter 전체 회귀는 실행하지 않았다.

TASK-802의 남은 조건은 위 미검증 경계의 필요성 판단, 생성 멱등성 정책과 범위 결정, 승인 시 Backend·Flutter·migration 및 관련 검증, 최종 회귀와 완료 판정이다. 제품 코드·API·Migration·Dependency 변경은 없다.

### TASK-802 2단계 — Alarm 생성 서버 멱등성 계약 확정 (2026-10-31)

시작 기준 `main` / `b2c8bf4adaac9d4aa23a18da382780e6f19cadf9`, 작업 트리 변경 없음. 이 단계는 **설계 확정, 미구현**이며 TASK-802는 `[ ]`다. 기존 TASK-802 1단계와 TASK-804 완료 기록 및 다른 Task 상태를 유지한다. B안인 서버 Request Identity를 채택한다. 최종 계약과 근거는 [API](api.md#alarm-생성-멱등성-확정-계약-task-802-2단계-미구현), [DB](database.md#alarm_creation_requests-task-802-설계-미구현), [실행 흐름](architecture.md#alarm-생성-멱등성-실행-설계-task-802-미구현), [ADR-013](adr/ADR-013-alarm-creation-idempotency.md)을 따른다.

현재 `AlarmController`는 201을 고정하고 `AlarmService.create`는 User·current metadata 검증 후 매번 Alarm과 BusAlarmTarget을 저장한다. 조회는 User별이며 삭제는 Alarm lock 뒤 pending Delivery를 종료하고 Alarm을 삭제한다. Request ID 기록은 없다. Flutter `AlarmApiClient`는 201만 성공으로 인정하고 화면은 Push 준비 → create → activate를 수행한다. `AuthSession` identity는 재시작/refresh에 유지되고 새 로그인에 바뀐다. Boarding UUID 생성 및 session별 Secure Storage 직렬화는 참고 가능하다. 기존 Migration의 User FK, UTC DATETIME(6), ascii_bin UUID 및 Notification 보존을 유지한다.

- **Identity와 API:** body의 canonical UUID v4와 인증 User 조합. 신규 Client는 필수, 필드 미포함 legacy는 기존 비멱등 생성이다. 같은 요청은 현재 Alarm을 201, 다른 canonical body는 409 `ALARM_CREATION_REQUEST_CONFLICT` (`Alarm creation request conflicts with the original request.`). GET `/api/v1/alarm-creation-requests/{clientRequestId}`은 현재 Alarm 200, 삭제 410 `ALARM_CREATION_RESULT_GONE` (`Alarm created by this request is no longer available.`), 기록 부재/타 User 404 `ALARM_CREATION_REQUEST_NOT_FOUND` (`Alarm creation request was not found.`). 삭제된 동일 POST도 410이며 body 충돌은 우선 409다. 잘못된 UUID는 400, 인증 실패는 401, 장애는 기존 5xx다. 오류는 기존 code/message 두 필드다.
- **Fingerprint/DB:** 검증·정규화된 occurrence ID와 두 Boolean의 버전 있는 canonical 문자열을 SHA-256으로 저장한다. 별도 요청 테이블의 `(user_id, client_request_id)` UNIQUE가 최종 방어선이고 `alarm_id`는 FK 없는 불변 참조다. Alarm과 기록은 단일 transaction으로 commit한다. 30일 보존 이후 batch cleanup이 실제 삭제하면 같은 ID도 새 요청으로 처리될 수 있다. 유한 tombstone의 보장 한계를 숨기지 않는다.
- **경합과 복구:** identity 확인을 metadata validation보다 먼저 한다. 동시 요청은 UNIQUE에서 패자의 전체 transaction을 rollback하고 해당 제약 위반만 새 transaction에서 정확히 조회·비교한다. 그 외 오류를 duplicate로 숨기거나 무한 재시도하지 않는다. commit 성공·응답 유실은 같은 ID GET/POST로 복구한다. rollback은 양쪽 기록이 없어 동일 요청을 현재 metadata로 재검증한다. GET 404는 처리 중 또는 cleanup 이후일 수도 있어 rollback 증거가 아니다. 다른 User는 격리한다.
- **Flutter:** Push 준비 후 원본 ID/body, User/session 및 최소 문맥을 Secure Storage에 성공적으로 기록한 뒤 POST한다. 불확실하면 보존하고 명시적 GET/같은 요청 재시도만 허용한다. session 전환, 앱 재시작, 삭제 410, 정리 실패와 오래된 비동기 정리를 보호한다. 만료 가능성이 있거나 시계를 신뢰할 수 없는 오래된 intent는 재전송하지 않고 중복 위험을 안내한다. 원래 Alarm ID 확보와 활성화 성공은 별개이며 TASK-804 복구 및 Auth generation/navigation epoch를 유지한다. Token·credential·FID·Provider 원본을 저장하지 않는다.
- **범위:** 서로 다른 ID/기기의 의도는 합치지 않는다. NotificationEvent/Delivery identity, FCM과 기기 표시의 exactly-once 보장은 이 결정과 독립이다.

후속 3단계에서 새 Flyway migration, DTO/검증, repository, transaction 및 해당 UNIQUE만 식별하는 복구, GET/삭제 tombstone, 제한 batch cleanup과 실제 MySQL 동시성·HTTP 테스트를 함께 구현한다. 기존 Alarm은 backfill하지 않는다. DDL lock·배포/rollback, 보존량, cleanup 실패 관측 및 서버 선배포/legacy 혼합 운영을 검증한다. 4단계는 Flutter durable intent와 session/navigation 보호, 응답 유실·명시적 재시도 및 client/widget 검증이다. 5단계는 순차/동시 중복, 다른 body/User, commit 응답 유실·rollback·metadata 변경·서버 재시작·삭제·만료와 cleanup 경합, legacy, Notification 회귀와 Backend/Flutter 전체 회귀를 확인한 뒤 TASK-802 완료를 판단한다. 기존 구현에서 이 단계에 별도 설계 결정을 요구하는 미해결 정책은 없다. MySQL 제약 식별·격리/lock·cleanup 성능과 실제 배포 호환성은 구현 단계의 필수 검증 사항이다.

### TASK-802 3단계 — Backend Alarm 생성 멱등성 구현 및 MySQL 검증 (2026-10-31)

`main` / `868d0d30d4b916d036292fc93a691bfbc690d98e`의 미커밋 V21과 migration 기대값 변경을 보존해 작업했다. Backend 구현이며 TASK-802는 `[ ]`, TASK-804·814·816은 `[x]`를 유지한다. Flutter 제품 코드와 dependency는 변경하지 않았다.

- **Persistence:** `V21__create_alarm_creation_requests.sql`, `AlarmCreationRequest`, `AlarmCreationRequestRepository`를 추가했다. User FK, User/UUID UNIQUE, ASCII binary UUID v4·variant CHECK, SHA-256 raw `BINARY(32)`, UTC `DATETIME(6)`과 만료 index를 사용한다. Alarm FK와 기존 row backfill은 없다.
- **API와 비교:** `CreateAlarmRequest`는 ID 생략과 명시적 null을 구분하고 기존 Boolean의 생략·null은 false로 정규화한다. `AlarmController`와 `AlarmCreationService`는 검증된 User/ID와 `v1:<occurrence>:<before>:<after>`의 SHA-256을 사용한다. 동일 요청은 현재 Alarm을 201, 다른 body는 409, 조회는 200/404, 삭제된 결과는 410, 잘못된 UUID는 400으로 처리한다. ID 미포함 legacy는 기존 비멱등 생성이다.
- **원자성과 경합:** READ COMMITTED transaction에서 User 행을 먼저 잠가 metadata 검증 전 같은 User의 생성을 직렬화하고, Alarm과 기록을 함께 저장한다. 요청 기록의 UNIQUE가 최종 경계이며 해당 MySQL 중복키만 실패 transaction 종료 후 새 transaction에서 조회·비교한다. 다른 DB 오류는 정상 중복으로 변환하지 않는다. 요청 기록을 먼저 확인하므로 metadata 변경 후에도 같은 요청을 복구한다. 현재 상태 조회는 lifecycle이나 Notification을 변경하지 않는다.
- **Tombstone과 보존:** Alarm 삭제 시 기존 pending Delivery 종료와 Event 보존을 유지하고 요청 기록은 남긴다. `AlarmCreationCleanup`은 매 시간 만료 기록을 최대 500건 삭제하며 삭제 전까지 멱등성 계약을 유지한다. 기록은 UTC 생성 시각부터 30일을 보존하고 실제 삭제 후에는 같은 ID가 새 요청이 될 수 있다. 실패는 스케줄러 로그에 남으며 이후 실행에서 재시도한다.
- **MySQL·HTTP 집중 검증:** `AlarmCreationIntegrationTest` 17개는 UUID/CHECK·UNIQUE, legacy/null, fingerprint와 409/404/410, 사용자 격리, ACTIVE/FOLLOW_UP 및 기존 Event/Delivery 보존, 삭제, 만료·batch, GET/POST/삭제와 cleanup 경합을 검증했다. `concurrent_same_request`, `concurrent_different_request`, `concurrent_same_id_for_different_users`, `concurrent_replay_after_metadata_changes`는 실제 DB와 barrier/latch를 사용한다. `unique_collision_reloads_after_rollback`은 테스트 전용 외부 writer 주입으로 실제 UNIQUE 충돌과 새 transaction 복구를 확인했다. `request_insert_failure_rolls_back_alarm`은 각 INSERT 실패와 재시도를, `recover_after_committed_response_is_discarded`는 commit된 응답 폐기 후 복구를 검증했다. 실제 네트워크 단절이나 서버 프로세스 재시작을 주입한 검증은 아니다.
- **회귀:** 수정한 Device API와 Alarm·Notification·Boarding·인증 관련 선택 검증이 통과했다. 전체 Backend `env -u JAVA_HOME ./gradlew test --offline --max-workers=1`은 **82개 클래스, 1,425개 실행·1,425개 통과, 실패 0·오류 0·Skip 0, BUILD SUCCESSFUL**이다. 종료 시 이미 중지된 테스트 컨테이너에 연결 풀이 재접속을 시도하는 경고가 출력됐다. Java 21과 격리된 MySQL 8.4.11 Testcontainers를 사용했다.
- **남은 작업:** 4단계에서 Flutter durable pending intent, 원본 요청 ID/body와 session 보호, 결과 조회·명시적 재시도·410 안내와 Flutter 회귀를 구현한다. 5단계에서 Backend·Flutter 통합, 재시작 및 실제 배포 경계 등을 검증하고 TASK-802 전체 완료 조건을 평가한다. GET 404는 최초 요청의 rollback 증거가 아니다.

### TASK-802 4단계 — Flutter Alarm 생성 복구 (2026-10-31)

`e7ed692a5829367bb9d4b91fd7f40d7eb4156688`에서 시작했다. TASK-802 전체는 `[ ]`를 유지하며 TASK-804·814·816 상태는 변경하지 않는다.

- Flutter는 Push 준비 뒤 UUID v4와 원본 body, User/login session 및 최초 시각을 Secure Storage에 기록하고 저장 성공 뒤에만 POST한다. Session별 직렬화와 request ID 비교 삭제로 늦은 정리가 새 요청을 지우지 않는다.
- 불확실한 결과는 원본 intent를 유지하고 명시적 조회 또는 원본 재시도를 제공한다. 조회 404는 불확실로 남고 409는 충돌을 안내하며 410은 삭제 결과로 처리한다. 복구한 Alarm은 자동 활성화하지 않는다. 정리 실패 시 새 생성을 막는다.
- 같은 실행 내 재시도는 단조 경과 시간과 로컬 시각을 확인하고 30일 이상 또는 시계 불일치를 차단한다. 재시작 시 단조 시간 연속성을 검증할 수 없어 재전송은 차단하고 GET 및 중복 가능성을 안내한 명시적 새 생성 경로를 제공한다. 30일 미만 자체는 서버 기록의 존재를 보증하지 않는다.
- 집중 검증과 Alarm·Auth·Boarding 회귀를 실행했다. 첫 전체 Flutter 실행에서 세션 identity를 읽을 수 없는 기존 테스트 token 및 secure storage fixture로 8개가 실패해 수정하고 해당 두 파일 15개를 재검증했다. 이후 생성 화면의 영속 경계를 강화해 전체 회귀를 다시 실행했으며 648개가 모두 통과했다. `flutter analyze`도 통과했다. 실제 iPhone·FCM 및 운영 네트워크 장애는 검증하지 않았다.
- 5단계에서 Backend·Flutter 통합, 실제 restart·배포 경계와 전체 완료 조건을 검증한다. Dependency와 Backend 제품 코드·Migration 변경은 없다.

### TASK-802 5단계 — 최종 통합 검증 및 완료 판정 (2026-10-31)

`main`의 `b45ffd9ed865c21c3d5076f6c2f84208c72dc806`, 변경 없는 작업 트리에서 시작했다. 제품 코드·Dependency·Migration은 변경하지 않았다.

- **Backend:** 기존 `AlarmCreationIntegrationTest` 17개를 MySQL 8.4.11 Testcontainers와 MockMvc로 재실행해 모두 통과했다. 동일 UUID 순차·동시 요청, 다른 내용 409, User 격리, UNIQUE 충돌 후 별도 transaction 복구, INSERT 실패 시 Alarm/요청 기록 rollback과 동일 ID 재시도, 삭제 tombstone 410, 30일 이후 cleanup 및 재생성 가능성, cleanup 경합을 확인했다. ACTIVE/FOLLOW_UP 상태·generation·기존 NotificationEvent/Delivery 보존도 확인했다. 응답 유실은 commit 뒤 응답 폐기 시뮬레이션이며 실제 네트워크 단절은 아니다. GET 404는 rollback의 증거가 아니다.
- **실제 HTTP와 재시작:** 별도 MySQL 8.4.11 컨테이너에서 V1~V21을 적용하고 실제 Spring Boot 서버를 실행했다. HTTP 생성 201과 활성화 200 뒤 서버 프로세스를 정상 종료·재시작해 GET 200과 동일 POST 201이 기존 ACTIVE ID를 반환했고 DB에서 Alarm 1개, 요청 기록 1개, generation 1을 확인했다. 실제 Flutter `AlarmApiClient`도 같은 서버에 HTTP GET과 POST를 보내 같은 ID와 ACTIVE를 확인했다. 이 일회성 연결 검증은 앱 전체 E2E나 실제 네트워크 단절 시험이 아니다.
- **Flutter:** 생성 controller·API·화면·Phase 6 회귀 88개, timeout·Push 준비·Session 전환 회귀 79개, 실제 HTTP 연결 1개가 통과했다. Push 준비 후 UUID v4와 Secure Storage 저장 뒤 POST, 재생성된 controller의 원본 복구, 같은 실행 내 명시적 동일 요청 재시도, 404 불확실성 보존, 409/410, 400/401/5xx·timeout·잘못된 응답, 다른 User/Session과 늦은 응답 격리, 정리 실패 시 신규 생성 차단을 기존 테스트와 구현으로 확인했다. Secure Storage는 테스트 mock이며 실제 OS 저장소나 앱 프로세스 재시작은 아니다. 재시작 후에는 동일 POST를 차단하고 GET을 제공하며 명시적 새 생성 전 화면과 확인 대화상자에서 중복 위험을 안내한다.
- **호환성과 범위:** 서버 선배포 후 신규 Flutter 순서다. 기존 Client의 ID 없는 생성은 계속 허용되나 멱등성은 없다. 신규 Client를 구형 서버에 먼저 배포하는 조합은 지원하지 않는다. V21은 독립 table을 추가하고 기존 Alarm을 backfill하지 않는다. 새 DB migration과 기존 `UserIdentityMigrationIntegrationTest` 8개 통과를 확인했으며 운영 데이터 규모의 DDL lock·배포·복구 검증은 수행하지 않았다.
- **실행과 판정:** 기존 3단계 Backend 전체 1,425개, 4단계 Flutter 전체 648개 통과 기록을 재사용했다. 이번에는 제품 코드 변경이 없어 전체 suite를 반복하지 않았다. Flutter 추가 실행에서 존재하지 않는 파일 지정으로 한 번 로딩 오류가 났고 유효한 두 파일만 재실행해 79개가 통과했다. 실제 iPhone·FCM, 운영 배포, 장애 중 강제 종료와 실제 네트워크 단절은 미검증이며 각각 별도 운영 검증 범위다. 핵심 중복 방지, 영속성·원자성, 복구·Session 격리, lifecycle 및 알림 기록 보호, legacy·만료 정책의 필수 조건을 충족해 TASK-802를 `[x]`로 완료한다.

### TASK-804 API 오류 계약 분석 및 구현 계획 (2026-10-30)

**분석 전용 결과: A안(기존 GlobalExceptionHandler·Security 경계의 최소 보강, 두 Controller-local Handler 유지)을 추천한다.** 기준은 `main`, HEAD `47493ea3ef3f70d37d258d81d90521f7bb6e6f3d`이며 시작 시 tracked/untracked 변경은 없었다. 이 절만 추가하며 TASK-804 `[ ]`, TASK-814 `[x]`, TASK-816 `[x]`와 다른 Task 상태·기존 감사/완료 기록을 유지한다. 아래 권고는 구현·새 정책 확정·HTTP 실행 검증을 뜻하지 않는다.

#### 조사 범위와 확인 수준

Owner 문서 `api.md` §4~6, `architecture.md`의 인증·Client 복구·Device/Boarding transaction 경계, `requirements.md`의 인증/장애/보안, 관련 Domain/Package/개발·로컬 실행 규칙, ADR-005·010·012 및 기존 TASK-804/814/816 기록을 대조했다. Java 제품의 모든 HTTP Controller와 공통 오류/보안 설정, 인증·Domain Service/DTO, Flutter의 아래 Client와 호출 측 복구 정책, 기존 테스트 소스를 조사했다. devlog는 근거로 사용하지 않았다.

**이 절의 HTTP 상태·Content-Type·본문은 모두 ‘정적 분석상 예상’이다. 이번 HTTP 요청·테스트·build/compile 실행은 0개다.** 기존 테스트의 assertion 존재와 과거 실행 기록은 이번 실행 결과와 구분한다. TASK-816의 두 local 500 집중 검증 및 Flutter 회귀, TASK-814 완료 결과를 재실행하거나 다시 완료 처리하지 않는다. Servlet error dispatch, 실제 JSON coercion/직렬화와 content negotiation은 후속 실행 acceptance다.

프레임워크 판단에는 로컬 캐시의 Spring WebMVC 7.0.9, Spring Security 7.1.1, Spring Boot 4.1.1 및 Google API Client 2.9.0/HTTP Client 2.0.0 바이트코드를 읽기 전용으로 확인했다. Dependency resolution/다운로드는 하지 않았다. `BearerTokenAuthenticationEntryPoint`·`BearerTokenAccessDeniedHandler`는 header/status만 설정하며 JSON을 쓰지 않는다. `ResponseStatusExceptionResolver`는 `sendError`를 사용하고 `BasicErrorController`/`DefaultErrorAttributes`는 별도 기본 오류 경로다. 이 근거도 실제 Servlet HTTP 응답을 관측한 것은 아니다.

#### 전체 Endpoint 목록

제품 Controller는 6개, 명시적 HTTP mapping은 20개다. 모두 아래 목록에 포함하며 동일 GET의 query 모드는 별도 mapping으로 세지 않는다. 운영 health 1개와 프레임워크 `/error`는 별도 경계다. GET의 암시적 HEAD/OPTIONS 처리는 MVC 검증에 포함한다.

| Controller | 실제 Endpoint | 주요 실패 경계 |
| --- | --- | --- |
| AuthController | `POST /auth/google` | body/Google credential 400·401, 검증 Infrastructure 503, 로그인 DB/Token 발급 500 후보 |
| AuthController | `POST /auth/refresh` | body 400, 유효하지 않음/만료/재사용 401, DB/발급 500 후보 |
| AuthController | `POST /auth/logout` | body 400, DB 500 후보; 읽을 수 있는 body의 없음/null/blank Token은 204 |
| AlarmController | `POST /api/v1/alarms` | validation 400, User 소실 401, occurrence 404, Domain 400, 미처리 500 |
| AlarmController | `GET /api/v1/alarms` | JWT/subject, DB·response mapping 실패 |
| AlarmController | `GET /api/v1/alarms/{alarmId}` | 타입 400, 부재/다른 owner 404, DB·mapping 실패 |
| AlarmController | `POST /api/v1/alarms/{alarmId}/activate` | 타입 400, 부재/다른 owner 404, DB·상태 변경 실패 |
| AlarmController | `POST /api/v1/alarms/{alarmId}/deactivate` | 타입 400, 부재/다른 owner 404, DB·상태 변경 실패 |
| AlarmController | `DELETE /api/v1/alarms/{alarmId}` | 타입 400, 부재/다른 owner 404, DB·transaction 실패 |
| BusRouteController | `GET /api/v1/bus-routes?query=...` | 누락/blank 400, DB·mapping 실패; 검색 결과 없음은 200 빈 배열 |
| BusRouteController | `GET /api/v1/bus-routes/{routeId}/stops` | 타입/0 이하 400, Route 404, 빈 traversal invariant 실패 |
| BoardingCandidateController | `GET /api/v1/bus-routes/{routeId}/boarding-candidates?targetStopOccurrenceId=...&boardingStopOccurrenceId=...` | 필수 query/ID 400, metadata 404, 전체 Provider 실패 503 |
| DeviceController | `POST /api/v1/devices` | body/header 400, credential 403, ownership/platform/revision/target 409, local 500 |
| DeviceController | `GET /api/v1/devices/{installationId}` | UUID/header 400, credential 403, 부재 404, local 500 |
| DeviceController | `POST /api/v1/devices/disable` | body/header 400, credential 403, 부재/다른 owner 404, generation/revision 409, local 500 |
| BoardingSessionController | `POST /api/v1/boarding-sessions` | body 400, User 401, metadata 404, candidate/request/open-session 409, Provider 503, local 500 |
| BoardingSessionController | `GET /api/v1/boarding-sessions/{sessionId}` | 타입/0 이하 400, User 401, 부재/다른 owner 404, local 500 |
| BoardingSessionController | `GET /api/v1/boarding-sessions?scope=current` 또는 `?clientRequestId={uuid}` | query 조합/중복/UUID 400, User 401, local 500; 조회 없음은 200 빈 배열 |
| BoardingSessionController | `POST /api/v1/boarding-sessions/{sessionId}/reconfirm` | body/ID 400, User 401, owner/metadata 404, 상태/revision/candidate 409, Provider 503, local 500 |
| BoardingSessionController | `POST /api/v1/boarding-sessions/{sessionId}/cancel` | body/ID 400, User 401, 부재/다른 owner 404, revision 409, local 500 |
| Actuator (제품 Controller 아님) | `GET /actuator/health` | 공개 health; DOWN의 503 health body는 Application Domain 오류와 별개, TASK-807 소유 |

`/auth/*`는 위 POST와 health만 permitAll이고 Application API는 모두 JWT 필수다. permitAll POST라도 잘못된 Bearer header를 함께 보내면 Resource Server filter가 Controller보다 먼저 거부할 수 있다. Notification/FCM·metadata bootstrap·scheduler 자체의 HTTP Controller는 없다. `/error`는 Boot 자동 오류 처리 경로이며 공개 Application Endpoint로 새로 추가된 것이 아니다.

#### 현재 오류 처리 구조와 우선순위

1. Security filter가 Bearer parsing·JWT 검증·인가를 수행한다. JWT 실패는 filter의 EntryPoint, 미인증 보호 요청은 ExceptionTranslationFilter의 EntryPoint, 인증된 인가 거부는 AccessDeniedHandler 경로다. Controller Advice가 이 filter 경계의 오류를 자동 처리하지 않는다.
2. MVC가 Controller를 선택하고 argument binding/body parsing/`@Valid`를 수행한다. Controller-local `@ExceptionHandler`가 우선하며 Device·Boarding의 `DataAccessException`/`TransactionException`만 안전한 고정 JSON 500으로 처리한다. local Handler는 `ApiException`이나 임의 RuntimeException 전체를 처리하지 않는다.
3. `GlobalExceptionHandler`는 `ApiException`의 enum HTTP/code와 exception message를 반환한다. 현재 custom message를 주는 제품 호출은 Alarm의 predecessor/successor 고정 문구다. 나머지 모든 예외 message를 그대로 노출하는 구조는 아니다. 단, 향후 `ApiException(code, rawMessage)` 사용은 안전성을 별도로 확인해야 한다.
4. Global validation Handler는 `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException` 네 종류만 400 `INVALID_REQUEST`로 변환한다.
5. 세 인증 예외의 `@ResponseStatus`와 Service의 `ResponseStatusException(401, "User not found")`는 위 Handler 대상이 아니다. 기본 Resolver/sendError로 넘어간다. 미처리 Runtime/DB/transaction/serialization 실패는 현재 공통 JSON 500이 보장되지 않는다. Handler 자체 실패도 다른 Handler가 반드시 다시 처리한다고 보장할 수 없다.

근거: [공통 Handler](../backend/src/main/java/com/stopbell/common/error/GlobalExceptionHandler.java), [응답 DTO](../backend/src/main/java/com/stopbell/common/error/ApiErrorResponse.java), [Security 설정](../backend/src/main/java/com/stopbell/common/config/SecurityConfiguration.java), [Device local Handler](../backend/src/main/java/com/stopbell/notification/controller/DeviceController.java), [Boarding local Handler](../backend/src/main/java/com/stopbell/boarding/controller/BoardingSessionController.java). `ApiErrorResponse`는 이미 `code/message` 두 필드이므로 DTO 확장은 필요 없다.

#### 구현된 ErrorCode Matrix

아래 22개가 현재 enum의 전부다. 각 message의 원문은 [ErrorCode](../backend/src/main/java/com/stopbell/common/error/ErrorCode.java)가 소유하며 아래 표는 의미/도달 경로를 비교한다. ‘유지’는 정적 구조가 계약과 맞는다는 뜻이며 모든 HTTP 조합의 이번 실행 통과를 뜻하지 않는다.

| 구현된 Code | HTTP | 실제 사용 조건/영역 | 판단 |
| --- | ---: | --- | --- |
| `INVALID_REQUEST` | 400 | 네 binding/validation 예외, Route query/양의 ID, Device strict DTO/header/UUID/counter, Boarding DTO/ID/query | 유지·경계별 shape 검증 보강 |
| `INVALID_ALARM_REQUEST` | 400 | 첫/마지막 occurrence에 불가능한 before/after 옵션 | 구체적 안전 message 유지 |
| `ALARM_NOT_FOUND` | 404 | Alarm 상세/활성/비활성/삭제의 부재 또는 다른 owner | 정보 비노출 404 유지 |
| `BUS_ROUTE_NOT_FOUND` | 404 | Stop/후보 조회·세션 검증의 Route 부재 | 유지 |
| `TARGET_STOP_OCCURRENCE_NOT_FOUND` | 404 | Alarm/후보/세션 검증의 occurrence 부재 | stale 선택 복구 의미 유지 |
| `DEVICE_CREDENTIAL_INVALID` | 403 | 형식은 유효하나 저장 hash와 다른 credential | Security 403과 구분, 유지 |
| `DEVICE_NOT_FOUND` | 404 | 상태/disable 부재 또는 disable 다른 owner | 유지 |
| `DEVICE_OWNERSHIP_CONFLICT` | 409 | generation/명시적 이전 intent 불일치 | revision 검사 전 권한 의미 유지 |
| `DEVICE_PLATFORM_CONFLICT` | 409 | 기존 installation platform 변경 | 유지 |
| `DEVICE_REGISTRATION_STALE` | 409 | 저장 revision보다 낮은 mutation | 유지 |
| `DEVICE_REVISION_CONFLICT` | 409 | 같은 revision에 다른 결과 상태 | 동일 결과의 idempotent 성공과 구분 |
| `DEVICE_PUSH_TARGET_CONFLICT` | 409 | 다른 installation의 현재 FID 점유·식별된 UNIQUE 경합 | 미분류 DB 오류로 확대 금지 |
| `BOARDING_SESSION_NOT_FOUND` | 404 | 상세/변경 대상 부재 또는 다른 owner | 유지 |
| `BOARDING_CANDIDATE_CHANGED` | 409 | reference 만료/소실, identity·시각·metadata 불일치 | 후보 재조회 의미 유지 |
| `BOARDING_SESSION_CONFLICT` | 409 | 다른 미종료 세션 존재 | 자동 대체 금지 유지 |
| `BOARDING_REQUEST_CONFLICT` | 409 | 같은 User/UUID의 다른 fingerprint | 원래 UUID/body 보존 |
| `BOARDING_REVISION_CONFLICT` | 409 | mutation/reconfirm snapshot revision 불일치 | 상세 조회 복구 유지 |
| `BOARDING_TARGET_NOT_AHEAD` | 409 | enum 존재, 현재 제품 throw 지점 없음 | 이전 Backend 호환 코드; 새 사용/삭제 불필요 |
| `BOARDING_RECONFIRM_REQUIRED` | 409 | 같은 차량·목적/상태 재확인 조건 불충족 | terminal/상태 검증 유지 |
| `BOARDING_TRACKING_UNSUPPORTED` | 409 | 검증되지 않은 TAGO 지원·운행 종료 등 추적 근거 미지원 | Provider 장애 503과 구분 |
| `TRANSIT_TEMPORARILY_UNAVAILABLE` | 503 | 후보 roster 실패 및 생성/재확인 Provider 재검증 실패 | upstream 401도 Client 인증 401로 전파하지 않음 |
| `INTERNAL_SERVER_ERROR` | 500 | Device·Boarding local persistence Handler만 현재 사용 | 고정 message·두 필드 JSON 보존, 공통 fallback 후보로 재사용 |

**미구현:** `UNAUTHENTICATED`, `METADATA_UNAVAILABLE`, Security 일반 권한 거부용 code, Google 인증 Infrastructure 503용 code는 현재 enum에 없다. TASK-804 bullet에 적힌 앞의 두 이름은 구현 사실이 아니다. Route/Stop API는 local metadata DB를 조회하며 요청마다 metadata Provider를 호출하지 않는다. metadata 부재는 확정된 404, 빈 traversal은 `IllegalStateException`인 invariant 실패다. 아직 별도 metadata 일시 장애 판정/HTTP 정책이 없으므로 `METADATA_UNAVAILABLE`을 선행 추가하지 않는다. Transit Provider 장애를 이 이름으로 중복 매핑하거나 모든 DB 오류를 metadata 503으로 바꾸지 않는다.

#### HTTP 오류 Matrix: 목표와 현재의 차이

이 표의 모든 현재 결과는 **정적 분석상 예상**이다. `JSON 2필드`는 일반 JSON 요청/응답 협상에서의 `ApiErrorResponse`이며 Content-Type의 실제 값과 정확한 key 집합은 후속 테스트로 확인한다. `기본 오류 경로`는 MockMvc의 sendError 후 빈 본문과 실제 Servlet의 error dispatch 응답을 구분해야 한다.

| 영역 | 상황 | 현재 HTTP | 현재 Body / Content-Type | 목표 계약 | 수정 필요 |
| --- | --- | --- | --- | --- | --- |
| 공통 MVC | body 없음/깨진 JSON/bean validation/필수 query/Long 변환 실패 | 400 | JSON 2필드 `INVALID_REQUEST` | 동일 400·두 필드 | 주로 테스트 보강 |
| Alarm | Domain 옵션 위반/부재·다른 owner | 400 / 404 | JSON 2필드·기존 Domain code | 현재 의미 유지 | 구현 불필요 |
| Alarm | `{alarmId}`가 0/음수지만 Long 변환 성공 | 404 | 조회 결과 `ALARM_NOT_FOUND` | generic 0 이하 ID=400 문구의 path 적용 범위 판단 필요 | 아래 미확정 결정 |
| Device | credential/리소스/ownership·revision 실패 | 403 / 404 / 409 | JSON 2필드·기존 Device code | 동일 | 구현 불필요 |
| Boarding | 리소스/상태·UUID·revision 충돌 | 404 / 409 (형식 오류 400) | JSON 2필드·기존 Boarding code | 동일 | 구현 불필요 |
| Transit | roster·선택 차량 재검증 transport/protocol/upstream 실패 | 503 | JSON 2필드 `TRANSIT_TEMPORARILY_UNAVAILABLE` | 동일·Client 401과 구분 | 구현 불필요 |
| Security | JWT 없음, compact JWT 오류, 서명 오류, 만료, issuer/필수 exp 검증 실패 | 401 | 기본 EntryPoint는 body/Content-Type 미설정, Bearer challenge | 401 고정 JSON·안전한 challenge | 필요 |
| Security | Bearer header 형식 오류 | 기본 resolver는 malformed header를 401 `invalid_token` 처리 | 기본 EntryPoint body 미설정 | 기존 상태·header 보존 | 필요·세부 실행 확인 |
| Security | 인증 성공 후 filter 인가 거부 | 도달하면 403 | 기본 Handler는 body/Content-Type 미설정 | 403·안전한 JSON·challenge | 필요; 현재 실제 권한 rule은 없음 |
| JWT claim | 서명/issuer/exp는 유효하나 sub 누락·0·문자·Long overflow | filter 통과 가능; extract 시 미처리 실패(500 경로), Route search/stops는 extract 안 함 | 공통 JSON 보장 없음 | 기존 양의 User ID 규칙을 JWT 검증 경계에서 거부하는 401 | 필요 |
| Auth | Google null/blank/검증 false·서명 실패·subject 없음 | Resolver 단계 401 | `@ResponseStatus` 기본 오류 경로, JSON 계약 없음 | 401 고정 JSON·개인정보 비노출 | 필요 |
| Auth | Refresh null/blank/unknown/만료/재사용 | Resolver 단계 401 | 위와 동일 | 401 고정 JSON·Rotation 정책 유지 | 필요 |
| Auth | Google key 조회 등의 원격 IOException | Resolver 단계 503, ERROR 재인가 시 최종 401 위험 | 기본 오류 경로 | 503 고정 JSON·인증 실패와 분리 | 필요·실제 Servlet 확인 |
| Auth | Google compact token 파싱 실패 | IllegalArgumentException 미처리 500 경로 또는 parse IOException의 503 오분류 가능 | 기본 오류 경로 | credential 실패 401 | 필요·로컬 라이브러리 parser 경계 검증 |
| Service | JWT User가 DB에서 사라짐(Alarm 생성·Device·Boarding) | Resolver 단계 401 | `ResponseStatusException`의 sendError, JSON 계약 없음 | 401·고정 message, User 정보 비노출 | 필요 |
| Device/Boarding | 지정된 DataAccess/Transaction 계열 | 500 | 고정 `INTERNAL_SERVER_ERROR` JSON 2필드 | 현재 계약 | 보존·기존 회귀 재사용 |
| 나머지 Controller/공통 | 예상 밖 Runtime/DB/transaction·metadata invariant 실패 | 미처리 500 경로; public Auth는 ERROR 재인가 위험 | 기본 오류 경로, JSON 2필드 보장 없음 | 고정 안전 JSON 500 | 필요 |
| MVC 자체 | 미지원 method/media/Accept·알 수 없는 URL | 인증 통과 후 405/415/406/404 후보 | 기본 Resolver/Boot JSON 또는 HTML 등 | 상태·Allow 등 header 보존, 적용할 오류 code는 미확정 | 필요·후속 결정/테스트 |
| 직렬화/Handler | message write 실패, Handler 재실패, 이미 commit된 응답 | commit 전 500 후보; commit 후 원래 상태/부분 body 유지 가능 | 일관된 body 보장 불가 | commit 전 안전 500, commit 후 재작성 금지 | fallback의 한계 명시 |

유효하지만 다른 owner인 User는 JWT 인증 자체는 성공한다. Alarm·Boarding·Device disable은 확정된 404, installation credential 불일치는 403, Device generation/이전 충돌은 409다. 이들을 Security 일반 403이나 401로 통합하지 않는다. 현재 `anyRequest().authenticated()` 외 role/scope/method-security 인가 rule은 없으므로 실제 제품 Endpoint에 존재하지 않는 Security 권한 거부 경로를 구현된 403 사례로 기록하지 않는다.

#### 발견된 문제와 추천 방향

| ID / 심각도 | 코드 위치·재현 가능한 조건 | 현재 동작과 기대 동작 | 영향 API / Flutter | 최소 수정 방향 |
| --- | --- | --- | --- | --- |
| E01 / P2 | `SecurityConfiguration.java:18~35`; 보호 Endpoint의 JWT 누락/실패 | filter가 JSON을 쓰지 않음 → status를 보존한 두 필드 JSON 필요 | 모든 Application API / AuthenticatedApiClient 및 모든 보호 Client | 누락 인증의 exceptionHandling EntryPoint와 Bearer filter EntryPoint를 모두 연결, AccessDeniedHandler도 별도 보강 |
| E02 / P1 | `JwtConfiguration.java:49~55`, `JwtTokenService.java:60~70`; 올바르게 서명한 sub 누락/비양수/문자/overflow token | issuer/time 검증만 통과, 추출 시 JwtException 또는 추출 없는 API 통과 → 동일 User ID 규칙으로 filter에서 401 | Alarm/Device/Boarding/후보 및 Route 조회 / 보호 Client 전체 | 기존 sub 규칙을 재사용/일치시키는 작은 JWT validator. 모든 JwtException을 global 401로 묶어 발급 장애를 숨기지 않음 |
| E03 / P1 | 인증 예외 3종, `AlarmService.java:57`, `DeviceService.java:190`, `BoardingSessionService.java:116,349`, Security 설정; public Auth sendError/미처리 실패 | body 미통일, `/error` 재인가가 원래 503/500을 401로 바꿀 위험 → 인증 401·장애 5xx 보존 | Auth·User 검증 API / BackendAuthClient·AuthSession(Refresh 401이면 Pair 삭제) | 인증 예외/기존 Service 401을 명시적으로 ResponseEntity 처리. 제한된 ERROR dispatch 경로도 실제 서버 테스트로 확인 |
| E04 / P1 | `GoogleIdentityVerifier.java:28~43`; compact token 점 구분 오류 또는 payload 파싱 실패 | `verify(String)`의 IllegalArgumentException은 누락, parse IOException도 원격 I/O와 같은 catch → invalid credential 401·원격 I/O 503 분리 | `/auth/google` / GoogleAuthService·BackendAuthClient | 라이브러리의 로컬 parse 단계와 검증/공개키 I/O 단계를 구분. parse 단계에서만 알려진 parse 예외를 401 처리; 임의 Runtime/원격 장애를 credential 오류로 숨기지 않음 |
| E05 / P2 | `GlobalExceptionHandler.java:12~32`; Alarm/Route/Auth DB 실패 또는 `BusRouteStopService.java:38~39` 빈 traversal | 공통 고정 JSON 500 없음 → 안전 500 fallback 필요 | local 지정 예외 이외 모든 API / 모든 Client | known HTTP/auth/domain 분류 후 마지막 MVC fallback. local Handler·transaction·서비스 재시도 정책 보존 |
| E06 / P2 | 같은 Global Handler; 미지원 method/Content-Type/Accept, 잘못된 URL·새 validation 경로 | 기본 오류 body가 섞이고 무조건 catch-all 추가 시 4xx→500 위험 | 전체 MVC / 보호 Client 전체 | 구체적 MVC 예외와 ErrorResponse의 status/header 분류를 먼저 보존. 현재 사용되지 않는 validation 예외를 무차별 400으로 등록하지 않음 |
| E07 / P2·정책 확인 | `AlarmController.java:43~60`, `AlarmService.java:120~148`; path ID 0/음수 | Long 변환은 성공해 404, Stop/Boarding은 명시적으로 400 | Alarm 상세/활성/비활성/삭제 / AlarmApiClient는 보통 전송 전 양수 검사 | API §5의 ID 문구 범위를 확인한 뒤 최소 guard/테스트. 양의 부재 ID·다른 owner 404는 유지 |
| E08 / P1 | `app/lib/features/transit/bus_route_stop_screen.dart:154~181`의 Alarm create catch 및 `alarm_detail_screen.dart`의 `_change`/`_confirmDelete`; mutation HTTP 500/503 또는 응답 유실 | create는 status null/transport 실패만 outcomeUnknown, HTTP 5xx는 ‘생성하지 못했습니다’와 재생성 허용. 상세 mutation도 실패 문구와 기존 상태를 유지 → 서버 반영 결과를 단정하지 않고 조회 복구 필요 | Alarm 생성/활성/비활성/삭제 / AlarmApiClient의 호출 화면 | 생성 5xx를 기존 목록 확인·중복 POST 차단 흐름에 연결, 상세는 원래 ID 조회로 결과 확인. 새로운 Alarm UUID/idempotency API는 도입하지 않음 |

E03은 확정된 실서비스 장애 보고가 아니라 우선 실행 검증할 P1 위험이다. Boot의 Security filter registration 기본 dispatcher에는 ERROR가 포함되고 AuthorizationFilter도 error dispatch를 검사한다. 현재 `/error`·ERROR 허용 rule이 없어 public `/auth/*`의 anonymous error dispatch는 `anyRequest().authenticated()`에 걸릴 수 있다. MockMvc는 실제 Container의 error 재진입을 자동 재현하지 않으므로 기존 Auth 503 status assertion만으로 최종 HTTP를 보장할 수 없다. 단순 `/error` URL 전체 permitAll이나 Security filter 전체 비활성화를 권하지 않는다. 필요하면 `DispatcherType.ERROR`와 오류 경로를 제한한 예외만 검토하고 정상 REQUEST 보호·public 오류의 비노출을 함께 검증한다.

E04의 근거는 Google verifier의 `verify(String) → GoogleIdToken.parse → JsonWebSignature.Parser.parse`가 key I/O보다 먼저 실행되고 구분자/형식 검사에서 `checkArgument`를 호출한다는 점이다. 기존 `GoogleIdentityVerifierTest`는 verifier mock의 GeneralSecurityException/IOException/subject 없음만 다루어 실제 parser의 이 구분을 검증하지 않는다. 새 테스트는 가짜 token·로컬 key fixture로 원격 호출 없이 분류를 확인해야 한다.

Bearer resolver의 query/form token 허용은 현재 기본 false다. 따라서 여러 전달 채널의 중복 token에서 생기는 OAuth `invalid_request` 400을 현재 활성 제품 경로로 주장하지 않는다. 향후 writer가 이 오류를 받는 경우에도 401로 강제하지 않는 호환 분기를 검증할 수 있지만, 이를 위해 제품의 query/form token 허용 설정을 켜지 않는다. 헤더 중복/잘못된 scheme의 실제 Container 해석은 후속 HTTP 검증 대상이다.

E08은 Backend body 통일로 새로 생기는 문제가 아니라 이번 분석에서 발견한 기존 복구 경계의 차이다. 사용자가 지정한 ‘500을 확정적 작업 실패로 단정하지 않음’ 조건과 현재 Alarm 화면이 다르므로 자동으로 문서를 현재 코드에 맞추지 않는다. 기존 UUID 없는 Alarm 생성 정책은 유지하면서 생성 5xx의 목록 확인과 원래 ID의 mutation 결과 조회를 최소 보완하는 방향을 제안한다. 이번에는 Flutter 제품/테스트를 수정하지 않는다.

#### Validation 400의 보존/누락 구분

| 입력 축 | 현재 처리 | 다음 구현의 검증/판단 |
| --- | --- | --- |
| 전체 `@RequestBody` 누락·빈 본문·JSON syntax/불가능한 구조 | `HttpMessageNotReadableException` → 400 | Auth를 포함한 JSON Content-Type 요청별 두 필드/고정 message 확인. body literal null과 token 필드 null은 구분 |
| Alarm target ID 누락/null/0/음수 | `@Valid`의 NotNull/Positive 및 Service guard → 400 | 기존 HTTP 테스트 재사용, Long overflow/타입 변환 경계 보강 |
| Alarm option 누락/null | JsonCreator가 false로 정규화 | 기존 허용 정책 유지; NotNull을 추가하지 않음 |
| Device 필수 필드·enum·UUID v4·counter·credential header | JsonNode strict creator·Service 검증 → 400 | 문자열/boolean/실수 counter·Long overflow·음수 거부, 0/Long.MAX_VALUE의 허용 및 ownership counter overflow 규칙 유지 |
| Boarding UUID/reference·offset 1/2·revision | strict JsonNode creator·Service → 400 | offset 생략=2, 명시적 null/타입 오류 거부, negative/overflow revision과 stale nonnegative revision 409 구분 |
| Path/query의 Long 변환 실패·필수 query 누락 | type mismatch/missing parameter → 400 | blank/비숫자/overflow·Route/Boarding 양수 guard 확인; Alarm path 비양수는 E07 |
| Boarding 조회 query | MultiValueMap으로 하나의 key/하나의 value와 scope/current 또는 canonical UUID 검사 | 무query·동시 query·unknown key·반복 value 400, 조회 없음 200 보존 |
| Google/Refresh token 필드 누락/null/blank | verifier/Service의 인증 실패 → 401 | 무조건 Bean Validation 400으로 바꾸지 않음; 구조가 읽히지 않는 요청은 400 |
| Logout token 필드 누락/null/blank·만료/없는 token | 정상 DTO가 읽히면 무변경/해당 hash 삭제 후 204 | idempotency 보존; body 자체 누락·깨진 JSON까지 204로 바꾸지 않음 |
| typed Long/Boolean/String coercion | Alarm/Auth는 typed DTO, Device/Boarding과 달리 전역 strict 정책 없음 | 실제 configured Jackson mapper를 통과한 HTTP로 numeric string/float/boolean 경계 확인. 임의 전역 coercion/unknown-field 정책 도입 금지 |
| Method validation/필수 header/ConstraintViolation | 현재 Handler 네 종류에 없음; direct parameter constraint/필수 RequestHeader 사용은 현 Controller에 없음 | `HandlerMethodValidationException`은 입력 400과 반환값 검증 500 구분. Jakarta constraint를 DB/Hibernate constraint와 혼동하지 않음. 도달하지 않는 예외를 현재 제품 결함으로 과장하지 않음 |

따라서 실제 사용 중인 body/query/type/Bean Validation 400은 이미 상당 부분 처리한다. 새 `@Validated`·전역 제약 추가나 모든 IllegalArgumentException/ConstraintViolationException의 400 변환은 최소 수정안이 아니다. Generic catch-all만 추가해 기존 MVC 400/404/405/406/415·인증 401/503을 500으로 바꾸는 것이 가장 큰 회귀 위험이다.

#### 500 fallback·보안·Provider 503 권고

- 공통 500 fallback은 필요하다. 두 local persistence Handler는 그대로 두고, global에서 기존 `ApiException`·네 validation 예외·인증 예외·`ResponseStatusException` 및 MVC의 알려진 HTTP 예외를 먼저 분류한다. `ErrorResponse` 또는 `@ResponseStatus`의 HTTP 의미/필요 header를 확인한 뒤에만 미분류 Exception을 `INTERNAL_SERVER_ERROR` 고정 응답으로 처리한다. 모든 Exception을 400으로 바꾸거나 Exception.class 하나로 바로 500을 반환하지 않는다. 원인에 알려진 HTTP 예외가 감싸진 경우도 기존 Resolver와 달라지지 않는지 확인한다.
- MVC `HttpMessageNotWritableException`/직렬화 실패는 서버 500 영역이다. 오류 응답을 쓰기 전 `response.isCommitted()`를 확인하고 commit 뒤에는 status/body를 재작성하거나 성공/rollback을 단정하지 않는다. Handler 자체 직렬화 실패·filter 외부 실패·Container 오류는 Advice의 완전한 처리 범위가 아니므로 Boot 오류 경로의 최소 보완 여부를 실제 Servlet 검증 뒤 결정한다. catch-all로 `Error`/OOM까지 복구하는 설계를 만들지 않는다.
- 응답에는 고정 code/message만 사용한다. SQL/table/constraint/stack, Token·Google/Firebase credential·FCM target·내부 User ID·installationId/credential/hash·Provider raw URL/query/body를 넣지 않는다. 새 내부 오류 log도 고정 operation/오류 종류 등 필요한 안전 메타데이터만 사용하고 exception message/cause/Throwable 전체·원문 request/path를 기록하지 않는다. Device/Boarding local 로그와 Hibernate 원문 억제를 유지한다. Bearer challenge의 기본 error_description도 무조건 복사하지 말고 안전한 정적 값과 기존 OAuth 상태 의미로 제한한다. 전체 관측 시스템 설계는 TASK-805 범위다.
- Provider 전체 실패는 기존 503 `TRANSIT_TEMPORARILY_UNAVAILABLE` 그대로다. upstream 401/403·timeout·HTTP/provider logical/protocol 오류는 Client 인증 실패가 아니다. 정상 EMPTY는 200, detail 일부 실패/deadline의 PARTIAL/HELD도 200이며 이를 모두 503으로 바꾸지 않는다. 선택 차량 생성/재확인의 detail 실패와 identity/state conflict의 기존 503/409 구분도 유지한다.
- Google 검증 원격 I/O 실패는 503을 유지하되 Transit code를 재사용하지 않는다. 일반 DB DataAccess/Transaction 실패를 자동 503/409로 분류하지 않는다. FCM은 durable worker의 결과/retry 정책이며 현재 HTTP 오류 code 추가 대상이 아니다. Health 503과 metadata 최초 bootstrap/readiness 정책은 TASK-807 책임을 유지한다.

#### Flutter 오류 소비와 호환성

| Client/호출 책임 | 오류 JSON·빈 본문·unknown code와 HTTP 처리 | Timeout/응답 유실·보존 사항 | 제품 수정 판단 |
| --- | --- | --- | --- |
| `core/authenticated_api_client.dart` | body를 해석하지 않고 status 401에만 refresh single-flight·최대 1회 동일 요청 replay. 400/403/404/409/500/503은 그대로 반환 | 응답 후/refresh 후/replay 후 generation·current 검사. wrapper timeout 뒤 늦은 401로 refresh/replay하지 않는 기존 수명 유지 | Backend shape 변경만으로 수정 불필요 |
| `features/auth/backend_auth_client.dart` + `auth_session.dart` | 오류 본문/code를 읽지 않고 status 보존. refresh 401만 현재 Pair 삭제/unauthenticated; 5xx·network/timeout은 Pair 즉시 삭제 안 함 | Auth HTTP 15초, 자동 auth POST 재시도 없음. 늦은 결과·logout/refresh 직렬화·Pair 원자 저장 유지. logout의 명시적 local 종료는 별도 정책 | E03의 status 오분류가 가장 큰 위험. JSON 401/503 조합 회귀 필요 |
| `features/alarm/alarm_api_client.dart` + 생성/상세 화면 | code/message가 문자열이면 보존, 빈/깨진 body는 nullable fallback. 특정 404 code만 부재/stale target 구분; 다른 4xx/5xx는 일반 실패 | 최초 요청+refresh+replay 전체 15초·finally 무효화. 생성 UUID 계약 없음. 생성 화면은 timeout/파싱 등 status null에서는 목록 확인·자동 create/activate 차단하지만 HTTP 5xx는 그 경로를 타지 않음. 상세 mutation 실패도 자동 결과 조회 없음 | Client body parser 수정 불필요, 호출 화면의 5xx/불확실 mutation 복구 보완 필요(E08). raw message 임의 출력 금지 |
| `features/notification/device_api_client.dart` + `push_registration_service.dart` | code allowlist만 보존, message는 버림. 정확한 404 `DEVICE_NOT_FOUND`만 null. unknown/empty에도 HTTP status 유지. 4xx definitive, 500/503은 false | 8초·늦은 401 차단. 5xx/timeout/응답 파싱 실패면 pending revision/generation/payload 보존, 다음 상태 조회/동일 intent 복구. 새 revision/body로 blind retry 금지 | `INTERNAL_SERVER_ERROR`를 allowlist에 넣을 필요 없음 |
| `features/boarding/boarding_session_client.dart` + `boarding_session_controller.dart` | code 문자열 선택 보존, message 버림. 빈/unknown/malformed 오류 body도 HTTP/status 보존. 5xx는 `uncertain=true`, 그 외 HTTP 4xx와 구분 | 15초·늦은 응답 폐기. create UUID/body/login identity를 보존하고 request GET 우선, 조회 0개도 실패 확정 아님. 명시적 동일 요청 재시도, mutation은 상세 GET; 이전 불확실 POST 뒤 4xx도 원래 identity를 지우지 않음 | 수정 불필요 |
| `features/boarding/boarding_candidate_client.dart` | code/status 선택 보존, 400/404/503을 UI가 구분. unknown/empty는 안전 일반 오류, 서버 raw message 미사용 | 15초·network/timeout/parsing 구분·generation/screen epoch, timeout 뒤 늦은 401 차단 | 수정 불필요 |
| `features/transit/bus_route_search_client.dart`, `bus_route_stop_client.dart` | error body/code를 읽지 않고 모든 non-200의 status 보존, 일반 안내 | GET 15초, 네트워크/timeout은 상위 UI catch. 두 GET wrapper 자체에는 request-current timeout guard가 없어 같은 로그인에서 늦은 401의 refresh/read replay 가능; mutation은 없음 | body 통일의 필수 변경 아님. 인증 전체 수명 정책 확대 시 별도 판단, 이번에 선행 수정하지 않음 |

Backend의 HTTP status·Domain code·성공 계약을 보존하는 body 통일 자체는 Flutter Client parser 변경을 요구하지 않는다. 다만 E08의 기존 Alarm 화면 불일치는 별도 최소 보완 대상이다. unknown code가 항상 작업 결과 확정 실패를 뜻하는 것은 아니다. 특히 500 JSON을 받았다는 이유로 Device pending이나 Boarding UUID를 지우거나 Alarm 생성 POST를 자동 재전송해서는 안 된다. 기존 TASK-816에 이미 추가된 빈/JSON/unknown 500 회귀를 재사용한다.

#### A/B안 비교 및 추천 구현 순서

| 비교 기준 | A안: 기존 경계 최소 보강 (추천) | B안: 오류 공통 구조 확대 |
| --- | --- | --- |
| 계약 누락 해소 | Security writer + Global 명시 매핑/fallback + 기존 JWT/Google 검증 보완으로 주요 누락 해결 | 중앙 resolver/base handler/공통 writer로 더 넓게 통합 가능 |
| 변경 파일 | 주요 제품 5곳 후보: GlobalExceptionHandler·SecurityConfiguration·ErrorCode·JwtConfiguration·GoogleIdentityVerifier. 작은 Security writer/validator는 필요 시 한정 추가; `/error` 보완은 실행 근거에 따라 조건부 | 앞의 보안/검증 변경에 더해 local Handler 2곳·공통 추상화/기본 오류 경로 등 확대. 정확한 파일 수는 구조 선택 전 확정 불가 |
| 기존 의미·인증 보안 | local 우선순위·Service/transaction을 유지하기 쉬움. 단, 명시 HTTP 분류를 catch-all보다 먼저 완성해야 함 | 중앙화 과정에서 인증/Domain/local handler 우선순위와 안전 로그가 바뀔 위험 |
| Flutter·테스트 가능성 | status/code 보존 검증을 작은 단위로 나눌 수 있음 | Client 회귀 범위와 handler 선택 회귀가 넓어짐 |
| 유지보수 | 기존 6개 Controller 규모에 적합, 약간의 500 응답 생성 중복 허용 | 단일 관리 장점은 있으나 현재 입증된 추상화 필요보다 변경 폭이 큼 |

권장 순서는 다음과 같다. 각 단계는 승인 후 구현할 독립 작업 단위이며 이번에는 실행하지 않는다.

1. **계약의 빈칸만 확정:** 아래 미확정 code/message와 generic ID 범위를 결정하고 `api.md`에 최소 동기화할 내용을 정한다. Domain 오류 의미·Refresh Rotation·Logout은 재설계하지 않는다. `METADATA_UNAVAILABLE`은 별도 의미가 확인되기 전 추가하지 않는 권고를 TASK-804 계획에 반영한다.
2. **인증 분류와 JSON:** JWT sub validator, Google 로컬 parse/원격 I/O 분리, 세 인증 예외와 현재 ResponseStatusException 401 매핑, Security 401/403 writer를 보강한다. 전달받은 OAuth 오류의 고유 status도 보존한다. Security filter 자체 실패·미인증 보호 접근 두 경로를 각각 검증하고 인증 서비스 장애가 최종 HTTP에서 401로 바뀌지 않는지 확인한다.
3. **MVC validation/known HTTP 매핑:** 현재 네 validation Handler를 보존하고 실제 도달하는 추가 MVC 예외의 status/header를 먼저 매핑한다. Alarm path ID는 결정된 범위만 보강한다. typed DTO coercion 정책 변경은 별도 근거 없이 포함하지 않는다.
4. **안전한 마지막 500:** 기존 Domain/auth/MVC 예외 보존 테스트 후 미분류 Runtime/DB/transaction/직렬화에 고정 fallback과 비민감 log를 추가한다. local Handler를 이동/제거하지 않는다. 실제 error dispatch의 남은 누락이 재현될 때만 최소 Boot 오류 경로 보완을 추가한다.
5. **Alarm 복구·회귀·문서:** E08을 기존 생성 목록 확인/원래 ID 조회 흐름의 작은 작업으로 보완하고 아래 선택 테스트/Client 회귀로 status·shape·비노출·복구 의미를 확인한다. Owner 문서는 승인된 변경 계약만 동기화한다. TASK-804 완료는 구현/관련 검증 성공 뒤 별도로 판단한다. 이번 분석으로 체크하지 않는다.

예상 변경 파일은 위 Backend 제품 후보와 기존 HTTP 테스트들, E08의 `app/lib/features/transit/bus_route_stop_screen.dart`·`app/lib/features/alarm/alarm_detail_screen.dart` 및 대응 Flutter 테스트, `docs/api.md`·`docs/task-list.md`다. JWT validator를 별도 파일로 분리하거나 기존 `JwtTokenService` 검증을 공유할지는 최소 변경 여부로 정한다. Auth 예외 class의 `@ResponseStatus` 제거, `ApiException`/응답 DTO 변경, local Controller 수정, Flutter Client parser 수정은 현재 필수가 아니다. DB Schema/Migration, Dependency, 신규 인증/Identity/권한 모델, 재시도/Rate Limit infrastructure 변경은 필요 없다. 관측된 `/error` 누락을 해결할 작은 클래스/설정과 대응 테스트는 조건부 후보이며 B안의 전면 중앙화를 미리 선택하지 않는다.

#### 다음 구현 단계의 테스트 계획

공통 acceptance는 실제 request binding/filter/handler/serialization을 통과한 HTTP status, JSON Content-Type, 정확한 key 집합 `{code,message}`·고정 안전 값, 금지 필드와 민감 marker 비노출이다. 성공 DTO·204 무본문·Bearer challenge·Allow 등 필요한 header도 보존한다. 서비스 mock은 경계별 예외 주입에만 제한하고 DB commit/rollback 보장의 증거로 사용하지 않는다.

| 대상 클래스/파일 | 재사용할 근거와 추가/선택 Case |
| --- | --- |
| `SecurityConfigurationIntegrationTest` | 기존 실제 JWT Encoder/Decoder·MockMvc와 5개 인증 테스트 재사용. JWT 없음/잘못된 compact·서명·issuer·만료·exp 없음/미래 nbf·잘못된 sub(null/0/음수/문자/overflow)의 401 shape/challenge·비노출. Route GET과 User 추출 API 모두 동일 거부. Bearer header syntax/중복 header·비허용 query/form 전달에서 기존 status 보존. writer의 OAuth invalid_request 400 분기는 필요 시 단위 테스트로 별도 확인. 테스트 전용 deny rule로 authenticated 403과 anonymous 401 분기 검증; 제품에 새 role rule 추가 금지 |
| `JwtConfigurationTest`, `JwtTokenServiceTest` | 기존 시간·issuer/exp 검증, sub 양수/Long 범위와 validator 일치. Token 생성/encode 오류는 401로 위장하지 않음. JWT 값·key 원문 비노출 |
| `AuthControllerIntegrationTest` | 기존 Google 로그인/Refresh Rotation·다른 Session 보존·재사용/만료/Logout 사례 재사용, 오류 두 필드/Content-Type 보강. `{}`/null/blank token의 기존 Google·Refresh 401, Logout 204; body 누락/깨진 JSON은 400. Google Infrastructure 503·로그인/Refresh DB/발급 500과 User 소실 401의 안전 shape·민감 message 비노출 |
| `GoogleIdentityVerifierTest` | 실제 로컬 parser를 통과하는 compact 구분자/잘못된 Base64·header/payload 형식의 credential 401과 원격 key fetch IOException 503 분리. key/network fixture는 in-memory 또는 loopback stub만 사용. 기존 일반 verifier mock만으로 parse 분리를 검증했다고 주장하지 않음 |
| `AlarmServiceIntegrationTest` | 기존 `reject_invalid_target_stop_occurrence_id`, `reject_missing_or_malformed_request_body`, Domain before/after·404/ownership HTTP 재사용. path/query 타입·overflow, 결정된 비양수 path ID 정책, 삭제된 User 401, Runtime/DB/transaction 주입 500과 정확한 두 필드·안전 log. 양의 없는 ID·다른 owner는 404 유지 |
| `BusRouteSearchIntegrationTest`, `BusRouteStopIntegrationTest` | missing/blank query·invalid/비양수/overflow routeId 400, missing Route 404, 검색 없음 200. 빈 traversal invariant와 DB 장애를 400/404/metadata 503으로 바꾸지 않고 안전 500 처리 |
| `BoardingCandidateApiIntegrationTest` | 기존 `provider_error_response`, `upstream_unauthorized_is_not_client_unauthorized`, `invalid_ids`, `missing_metadata`, `reject_other_route_occurrences` 재사용. 필수 query/타입/blank·전체 roster 503 정확한 shape, 정상 EMPTY/PARTIAL/HELD 200·sensitive Provider 원문 비노출 |
| `DeviceApiIntegrationTest` | 기존 strict body/header/UUID/counter 검증·credential 403·부재/ownership 404·모든 revision/generation/platform/target 409, `report_unclassified_database_failure_safely` 보존. register/state/disable의 대표 Runtime/Transaction 매핑과 local Handler 우선순위 확인. 실제 UNIQUE/권한·원자성 회귀 재사용 |
| `BoardingSessionApiIntegrationTest` | 기존 invalid body/query/UUID/revision·404/상태 409·Provider 503과 두 HTTP persistence 500 재사용. local Handler 우선순위, stale revision≠형식 400, terminal cancel 성공, 같은 UUID 원래 body 성공·다른 fingerprint 409 보존. 기존 `lost_commit_response_and_rollback`/`reconfirm_commit_reconciliation`/`aud08_atomic_failure`로 응답 형식과 commit 결과 불확실성을 별도 검증 |
| 공통 MVC 오류 경계 테스트 (신규 후보 `ApiErrorHandlingIntegrationTest`) | 기존 Testcontainers/MockMvc 설정을 재사용해 405/415/406/unknown URL 404·400 input validation 대 500 return validation·ResponseStatusException·annotated 예외·Runtime·DataAccess·Transaction·message write 실패의 분류 검증. product Endpoint로 주요 사례 확인, 도달 불가능한 framework 경계만 test Controller 사용. catch-all이 403/404/409/503을 덮지 않음. mock setter로 response를 직접 만든 결과를 HTTP 검증이라고 하지 않음 |
| 실제 Servlet 경계 테스트 (신규 후보 `ApiErrorDispatchIntegrationTest`) | `@SpringBootTest(webEnvironment=RANDOM_PORT)` + 기존 test profile/격리 MySQL, loopback HTTP만 사용. anonymous `/auth/google` Infrastructure 503, `/auth/refresh` DB/Runtime 500·invalid 401, authenticated MVC 404/405/415 및 정상 REQUEST 보호 확인. 실제 ERROR 재진입·Content-Type/본문·민감 path/message 비노출 검증. MockMvc 결과와 구분하고 이미 commit된 응답에 새 JSON을 보장하지 않음 |
| Flutter `backend_auth_client_test.dart`, `auth_session_test.dart`, `authenticated_api_client_test.dart` | 빈/정상 JSON/unknown/malformed 오류 본문의 401 및 500/503. refresh 401만 Pair 제거, 5xx/timeout은 Pair 보존, auth POST 자동 retry 0회·보호 API replay 최대 1회, 403은 refresh 0회. 기존 shared refresh·logout/late generation·timeout 후 replay 차단 유지 |
| Flutter `alarm_api_client_test.dart`, `alarm_api_client_timeout_test.dart`, `bus_route_stop_screen_test.dart`, `alarm_screen_test.dart`, `alarm_creation_push_test.dart`, `device_api_client_test.dart`, `push_registration_service_test.dart` | 기존 500 형태별 사례 재사용. Alarm 400/정확한 404와 unknown fallback, create 500/503·timeout/파싱 실패에서 목록 확인/재생성·activate 차단, 성공 create 뒤 activate 실패는 원래 ID 유지. 상세 mutation 5xx/timeout은 같은 ID GET으로 확인하고 조회 실패 중 추가 mutation 차단, 삭제 뒤 404는 정상 부재 처리. Device 400/403/404/409·unknown code/null·500/503 pending 유지·동일 revision/body와 늦은 401 차단 |
| Flutter `boarding_session_client_test.dart`, `boarding_session_controller_test.dart`, `boarding_candidate_client_test.dart`, `bus_route_search_client_test.dart`, `bus_route_stop_client_test.dart` | 400/401/403/404/409/500/503 중 해당 Client가 실제 소비하는 조합·빈/unknown/깨진 body fallback. Session 5xx/timeout/파싱 결과 불확실·원래 UUID/body·GET 복구·명시적 동일 재시도, conflict 뒤 재조회, late response 폐기. 후보 Provider 503≠인증 실패, Route status 기반 일반 오류 보존 |

구현 시 직접 관련 메서드/클래스를 단계별 선택 실행하고 인접 회귀만 추가한다. 전체 Backend/Flutter suite는 기본 실행하지 않으며 필요한 영향 근거가 생기면 먼저 범위를 보고한다. 격리 Testcontainers 외 실제 개발/운영 DB 접근, 실제 Google/Transit/FCM·Firebase credential 사용, 실제 iPhone·Docker 기존 Container/Volume 변경은 필요 없다. 실제 commit 네트워크 장애·프로세스 restart·실 Provider/iPhone 검증은 이 자동 테스트의 보장 범위가 아니다.

#### 미확정 결정과 이번 종료 상태

다음은 구현 전에 개발자 판단이 필요한 계약의 빈칸이며 이번 분석에서 확정하지 않는다.

1. **새 공개 code/message 최소 집합:** 인증 실패의 `UNAUTHENTICATED`와 일반 Security 권한 거부 code, Google 인증 Infrastructure 503 code 및 MVC 404/405/406/415의 code/message를 정해야 한다. 추천은 인증 401만 공통 `UNAUTHENTICATED` 후보로 두되 Domain 오류를 재사용해 의미를 흐리지 않고 Google 장애를 별도 503으로 구분하는 것이다. 이 이름들은 모두 미구현 후보이며 보안상 고정 message만 허용한다. `METADATA_UNAVAILABLE`은 독립 의미/트리거가 없어 추가하지 않는 방향을 추천한다. TASK-804 bullet의 기존 검증 후보 문구도 승인된 최종 범위와 맞출 필요가 있다.
2. **Alarm path 비양수 ID:** API §5의 generic ‘0 이하 ID’ 400 규칙을 `{alarmId}`에도 적용할지 확인해야 한다. 현재 구현은 404이고 Client는 보통 전송 전 거부한다. 추천은 형식/범위 오류만 400으로 명시하고 양의 부재 ID/다른 owner 404를 보존하는 것이다. 이번에는 API Owner 문서나 제품을 임의로 바꾸지 않는다.

인증 401과 장애 5xx 분리, Refresh Rotation·다른 Session 보존·Token Pair 원자성·User Identity, Logout idempotency, ownership 403/404/409·Provider 503, Client의 불확실 결과 복구는 이미 확정된 계약이므로 재질문/재설계 대상이 아니다. ERROR dispatch 제한 보완·fallback 내부 구현 방식은 위 계약을 지키는 최소 구현으로 판단하되, 공개 접근 정책을 넓히는 변경은 자동 채택하지 않는다.

이번 변경은 `docs/task-list.md`의 이 분석 절뿐이다. 제품·테스트·설정·Dependency·Migration·다른 Owner 문서·devlog와 기존 TASK-814/816 기록을 수정하지 않았다. 새 code/응답을 구현하거나 Task 완료 체크를 하지 않았고, 테스트/build/compile·HTTP·외부 Provider·DB·Docker 조작·Git add/commit/push·branch 전환/생성·reset/stash/clean/pull/fetch는 수행하지 않았다. 최종 `git status --short`, `git diff HEAD --stat`, `git diff --check`로 변경 범위/공백을 확인하며 결과는 미커밋 상태로 보고 후 중단한다.

### TASK-804 1단계 — 인증·Security 오류 계약 구현 및 검증 (2026-10-30)

이번 단계는 위 226행 분석을 보존하고 인증·Security 경계만 구현한다. 시작은 `main`, HEAD `47493ea3ef3f70d37d258d81d90521f7bb6e6f3d`, 기존 변경은 위 분석을 추가한 `docs/task-list.md`뿐이었다. 분석 당시 ‘미구현/미확정’은 당시 상태이며 아래 사용자 확정과 구현 결과를 현재 인증 계약으로 따른다. TASK-804는 `[ ]`, TASK-814/816은 `[x]`와 기존 완료 기록을 유지한다.

#### 확정한 공개 계약과 제품 변경

새 ErrorCode는 아래 세 개뿐이며 `Content-Type: application/json`, 정확한 `{code,message}` 두 필드와 고정 message를 사용한다. 기존 Domain code/status는 변경하지 않았다. `METADATA_UNAVAILABLE`은 추가하지 않는다. 상세 HTTP Owner는 [API 오류 형식](api.md#5-오류-형식)과 [인증](api.md#6-인증)이다.

| Code | HTTP | Message |
| --- | ---: | --- |
| `UNAUTHENTICATED` | 401 | `Authentication is required or invalid.` |
| `ACCESS_DENIED` | 403 | `Access is denied.` |
| `SOCIAL_IDENTITY_UNAVAILABLE` | 503 | `Social identity verification is temporarily unavailable.` |

- `JwtConfiguration`: 기존 HS256/서명·issuer·Clock·timestamp/필수 exp validator에 subject validator를 추가했다. `[1-9]`로 시작하는 십진 정수 문자열과 `Long.parseLong`으로 존재/빈 값/0/음수/문자/소수/선행 0/overflow를 검증한다. Nimbus가 JSON 숫자형 sub를 문자열로 정규화하는 것을 로컬 바이트코드에서 추가 확인해, 서명 검증을 마친 JWT의 원래 payload에서 claim 타입도 검사한다. 서명 검증을 대체하거나 Controller에 JWT parsing을 추가하지 않는다. `Authentication.name`만 사용하는 Endpoint에서도 인증 성립 전에 거부하며 `JwtTokenService.extractUserId()`의 방어 검증과 발급 코드는 변경하지 않았다.
- `SecurityErrorHandler`와 `SecurityConfiguration`: 누락 인증의 exceptionHandling EntryPoint 및 Bearer filter EntryPoint 양쪽, 인증된 AccessDeniedHandler에 같은 작은 writer를 연결했다. JWT/원인 예외 message를 직렬화하지 않고 기존 DTO/Mapper로 고정 JSON을 쓴다. `WWW-Authenticate: Bearer`와 invalid_token/insufficient_scope challenge, 기본 Security header를 유지하되 decoder error_description 원문은 내보내지 않는다. 비활성 query/form token 전달을 새로 켜지 않으며 OAuth invalid_request 400을 받는 경우 기존 `INVALID_REQUEST`로 보존하는 분기를 둔다. Role/권한 모델은 추가하지 않았다.
- `GoogleIdentityVerifier`: 도입된 Google 2.9.0/HTTP Client 2.0.0의 `verify(String) → parse → verify(GoogleIdToken)` 경계를 확인하고 로컬 parse와 검증을 분리했다. parse의 `IOException`/`IllegalArgumentException`만 `InvalidSocialCredentialException`으로 분류한다. 검증 false/subject 누락/기존 GeneralSecurityException은 인증 실패, 공개키 조회 등 검증 I/O는 기존 Infrastructure 실패를 유지한다. 검증 단계의 임의 RuntimeException은 그대로 전파하며 catch-all 인증 실패로 숨기지 않는다.
- `GlobalExceptionHandler`: `InvalidSocialCredentialException`/`InvalidRefreshTokenException`을 401, `SocialIdentityVerificationException`을 503의 고정 JSON으로 명시 처리한다. 실제 HTTP 테스트로 기존 `@ResponseStatus`보다 이 Handler가 먼저 선택됨을 확인했다. 기존 `ApiException`·validation Handler와 두 Controller-local persistence Handler는 수정하지 않았다.

Java 제품 변경은 위 5개 기존 파일과 신규 `common/error/SecurityErrorHandler.java`로 한정했다. Auth Controller/Service·Identity 모델·Refresh Rotation/Hash/원자성·Session별 Logout·요청/정상 응답 DTO, Alarm/Boarding/Device/Transit/Notification 제품, Schema/Migration/Dependency는 변경하지 않았다.

#### 실제 Servlet ERROR dispatch 재현과 수정

신규 `AuthenticationErrorDispatchIntegrationTest`는 RANDOM_PORT Embedded Servlet을 loopback HTTP로 호출한다. DB 검증이 아닌 dispatch 경계 테스트이므로 기존 Security 테스트의 Repository/Transaction mock 설정을 재사용하고 DataSource/JPA auto-config를 제외했다. Auth Controller와 Security는 실제 제품 경로이며 Google verifier만 제한적으로 stub한다. 실제 DB 정책은 별도의 기존 Auth/Domain MySQL Testcontainers 테스트가 검증한다.

**수정 전 실제 재현:** 공개 `POST /auth/google`에서 verifier가 `ResponseStatusException(404/500)`을 던지는 두 사례 모두 최종 HTTP가 **401**이었다(예상 404/500의 2개 assertion 실패). 실제 Servlet의 sendError→ERROR 재진입에서 인가가 원래 상태를 덮는 결함을 확인했다. MockMvc 결과만으로 재현했다고 주장하지 않는다.

수정은 `dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` 한 규칙이다. `/api/**`, `/error` URL 또는 모든 Dispatcher를 공개하지 않는다. 수정 후 원래 404/500, 미처리 Runtime 500, 인증 예외의 401·Infrastructure 503, Refresh 401·Logout 204를 실제 HTTP에서 확인했다. 직접 GET `/error`·`/auth/google`·`/auth/refresh`·`/auth/logout`·`/api/v1/alarms`는 여전히 JSON 401이며 익명 health는 200, 유효 JWT의 미등록 Application 경로는 404다. 일반 MVC/Boot 오류 body는 이번에 통일하지 않았으며 그 한계를 테스트/Owner 문서에 명시했다.

#### HTTP/비노출 및 기존 의미 검증

| 상황 | 실행 경계 | 결과 |
| --- | --- | --- |
| JWT 없음·malformed Bearer/JWT·변조·만료·issuer 불일치·exp 누락 | 실제 Filter Chain을 통과한 MockMvc | 401 UNAUTHENTICATED, application/json, 정확한 두 필드 |
| 서명은 유효하나 sub 누락/빈 값/0/음수/선행 0/+기호/소수/지수/공백/문자/Long overflow 및 JSON 숫자 42/Long.MAX_VALUE | User ID를 추출하지 않는 테스트 Endpoint의 실제 JWT 인증 | 모두 같은 JSON 401; 정상 문자열 User ID와 Long.MAX_VALUE는 통과 |
| 인증된 Security 거부 / 동일 조건의 미인증 요청 | 테스트 전용 denyAll 경계 + 실제 제품 error writer/Resource Server | 각각 403 ACCESS_DENIED / 401 UNAUTHENTICATED. 제품 role 정책 추가 없음 |
| Google 무효 Credential·실제 parser malformed | Auth MockMvc 및 실제 parser + network 금지 transport | 401 UNAUTHENTICATED |
| Google 검증 I/O·실제 공개키 fetch 단계 I/O | Auth HTTP와 실제 Google verifier/실패 transport | HTTP 503 SOCIAL_IDENTITY_UNAVAILABLE, 내부 분류 보존 |
| Refresh 만료/unknown/blank/null/Rotation 뒤 재사용 | 기존 실제 MySQL Auth HTTP | 401 UNAUTHENTICATED, 다른 Session·Rotation 보존 |
| Login·정상 Refresh·Logout/반복 Logout | 기존 실제 MySQL Auth HTTP | 기존 Token Pair·Hash 저장·현재 Session 삭제, Logout 204 무본문 보존 |
| Device credential 불일치 | 기존 Device HTTP/DB | 기존 403 DEVICE_CREDENTIAL_INVALID 보존 |
| Domain 부재·다른 owner·request/revision 충돌·Provider 장애 | 기존 Alarm/Device/Boarding/Transit 선택 HTTP 회귀 | 기존 404/409 및 TRANSIT_TEMPORARILY_UNAVAILABLE 503 보존 |

정확한 key 집합/고정 message로 원문 Credential·JWT·decoder message·SQL/내부 예외가 JSON에 섞이지 않음을 검증했다. Security challenge에 원문 error_description이 없고 `X-Content-Type-Options: nosniff`, Set-Cookie 없음도 확인했다. 실제 Servlet의 기본 404/500 응답도 synthetic 내부 marker/stack 정보를 본문에 내보내지 않는지 확인했다. 새로운 오류 writer/Advice는 Token/예외 cause 로그를 남기지 않는다. 일반 Runtime의 전체 로깅/공통 500 정책을 이번 단계에서 구현했다고 확대하지 않는다.

#### 단계별 실행 결과

환경은 Java/Gradle JVM 21.0.12.1, Wrapper 8.14.3이다. Backend 명령은 `env -u JAVA_HOME ./gradlew test --offline --max-workers=1`에 아래 집중/선택 클래스·메서드의 `--tests`를 지정했다. DB 테스트는 기존 격리 `mysql:8.4.11` Testcontainers/Flyway만 사용했다. 실제 Google/Transit/FCM 요청은 금지하고 Mock/실제 로컬 parser/실패 transport만 사용했다.

| 단계 | 실행 | 통과 | 실패 / 오류 / Skip | 범위 |
| --- | ---: | ---: | --- | --- |
| 수정 전 Servlet 재현 | 2 | 0 | 2 / 0 / 0 | 404/500이 401로 바뀌는 기존 결함 재현, 수정 후 아래 재검증 |
| JWT/Security/Google 집중 | 50 | 50 | 0 / 0 / 0 | SecurityConfigurationIntegrationTest 23, NonWeb 1, JwtConfigurationTest 2, JwtTokenServiceTest 8, GoogleIdentityVerifierTest 16 |
| Auth + 실제 Servlet | 36 | 36 | 0 / 0 / 0 | AuthControllerIntegrationTest 26, AuthenticationErrorDispatchIntegrationTest 10 |
| 인접 Backend 선택 회귀 | 11 | 11 | 0 / 0 / 0 | Alarm 2, Device 2, Boarding 4, BoardingCandidate 3 |
| Flutter 인증 관련 | 74 | 74 | 0 / 0 / 0 | backend_auth_client/auth_session/authenticated_api_client/google_auth_service 4개 파일 |
| Backend 전체 (원래 claim 타입 보강 전) | 1,323 | 1,323 | 0 / 0 / 0 | 80개 클래스, BUILD SUCCESSFUL(6분 44초); 아래 최종 보강 뒤 결과와 구분 |
| 마지막 JWT claim 타입 보강 | 35 | 35 | 0 / 0 / 0 | SecurityConfigurationIntegrationTest 25, JwtConfigurationTest 2, JwtTokenServiceTest 8; 숫자형 sub 2개 추가 |
| Backend 최종 전체 | 1,325 | 1,325 | 0 / 0 / 0 | 최종 코드 80개 클래스, BUILD SUCCESSFUL(6분 34초); 실제 JUnit XML suite/testcase 합계 일치 |

Auth 단계 최초 실행은 새 assertion helper의 checked exception 선언 누락으로 compileTestJava에서 실패했으며 JUnit 실행은 0개였다. helper 선언만 수정한 뒤 위 36개가 통과했다. 기존 테스트 삭제·Skip·assertion 약화나 제품 예외 분류 변경으로 통과시키지 않았다. 수정 전 재현 실패와 검증 과정 compile 실패를 최종 성공 수에 숨기거나 통과로 합산하지 않는다.

첫 전체 실행 중 최종 점검에서 Nimbus 10.9.1의 숫자형 subject 정규화를 확인했다. 이 실행은 컴파일된 기존 subject validator의 결과로 구분해 보존했다. 이후 JWT payload의 원래 String 타입 검사와 실제 HS256으로 서명한 숫자형 42/Long.MAX_VALUE HTTP 사례를 추가하고, 정상/최대 문자열 ID와 기존 JWT 오류를 포함한 35개를 통과했다. 최종 제품 코드가 달라졌으므로 전체 회귀를 다시 실행해 최종 1,325개 전부 통과했으며, 성공한 동일 코드를 근거 없이 반복한 것이 아니다. 기존 1,280개에 Backend 신규 45개(Security 20, Google 12, Auth 3, Servlet 10)를 추가한 수와 실제 XML이 일치한다. 선택 실행·첫 전체의 중복 횟수를 최종 서로 다른 1,325개에 합산하지 않는다. 종료된 격리 DB의 cached context shutdown 중 Hikari 연결 종료 경고가 있었지만 JUnit 실패/오류/Skip은 모두 0이며 테스트 검증을 약화하거나 timeout을 변경하지 않았다.

Flutter 제품은 변경하지 않았다. 추가한 인증 테스트 2개 파일은 `flutter analyze --no-pub` No issues, Dart format 적용 후 관련 74개가 통과했다. 신규 6개는 Login/Refresh 각각 JSON 401/503의 status·자동 재시도 없음 4개와, 실제 BackendAuthClient를 사용하는 AuthSession의 JSON Refresh 401 Pair 삭제/503 Pair 보존 2개다. 기존 single-flight·늦은 응답·timeout/replay/Logout 회귀도 함께 통과했다. Flutter 전체는 실행하지 않았으며 과거 전체 결과를 이번 수치에 합산하지 않는다. 초기 Gradle/Flutter SDK 캐시 쓰기 제한은 허용된 권한 확장으로 해결했고 SDK/Dependency upgrade는 하지 않았다.

실행 로그는 `/private/tmp/stopbell-task804-step1-{dispatch-before,focused,auth,adjacent,flutter,full-before-claim-type,claim-type,full}.log`, Backend 단계별 JUnit XML/summary는 같은 접두사의 `*-results` 디렉터리에 보존한다. 실제 개발/운영 DB, 기존 Docker Container/Volume, 실제 Google/Provider/FCM/Firebase Credential·iPhone은 사용/변경하지 않았다.

#### 문서·변경 범위 및 남은 책임

기존 분석 226행과 TASK-814/816 기록은 그대로 두고 이 절만 추가했다. `docs/api.md` §5/6에 확정된 공개 code/message·JWT sub 검증·ERROR 경계·미완료 body 범위를 동기화했다. 다른 Owner 문서와 직접 충돌하는 새 계약은 발견하지 않아 수정하지 않았다. 새 Dependency·Migration·API DTO·Flutter 제품·devlog 변경은 없다. 기존 분석 문서 변경도 이번 구현 Commit에 포함하며 모든 체크박스를 유지한다.

Java 테스트 변경은 SecurityConfigurationIntegrationTest, AuthControllerIntegrationTest, GoogleIdentityVerifierTest와 신규 AuthenticationErrorDispatchIntegrationTest다. 기존 Auth 테스트에 실제 parser를 연결한 malformed HTTP 3개, Google 단위 테스트에 실제 parser/network 차단·실제 key I/O·내부 Runtime 보존을 보강했다. Flutter 테스트 변경은 backend_auth_client_test.dart와 auth_session_test.dart뿐이다.

최종 변경은 허용된 14개 파일(Backend 제품 6, Java 테스트 4, Flutter 테스트 2, 문서 2)뿐이다. 기존 ErrorCode 22개의 HTTP/message 불변, 새 code 3개만 추가, 기존 분석/감사/Task 상태 불변과 문서 링크를 정적으로 확인했다. 집중·Auth/Servlet·인접 Backend·Flutter·최종 Backend 전체 검증과 `git diff --check`가 모두 통과했으며 사용자 지시에 따라 기존 분석을 포함한 허용 파일만 명시적으로 Stage해 `main`에서 Commit·Push한다. Commit subject는 사용자 지정 `fix: TASK-804 harden authentication error contracts`를 사용하며 body는 실제 개행 bullet로 작성하고 Commit 후 형식을 확인한다.

남은 TASK-804 범위는 (1) MVC validation 400과 기타 기본 오류 경로, (2) 안전한 공통 500 fallback, (3) 전체 Endpoint 오류 Matrix 회귀, (4) Flutter Alarm 생성 실패·응답 유실 복구, (5) TASK-802의 서버 중복 방지 책임과 구분, (6) TASK-804 최종 회귀·완료 판단이다. 일반 Service `ResponseStatusException(401)`의 body, MVC 404/405/406/415 등의 code, commit/직렬화/Handler 재실패의 공통 처리도 이번에 완료하지 않았다. 분석 E08의 Alarm 화면은 이번 범위에서 수정하지 않는다. 1단계 Commit·Push 성공도 TASK-804 전체 완료를 의미하지 않는다.

### TASK-804 2단계 — MVC Validation 및 공통 500 오류 계약 구현 (2026-10-31)

이번 단계는 `main`, HEAD `4c1b9bef764c4f44f0dba9c5ee1566eb90ed4d01`, tracked/untracked 변경 없는 상태에서 시작했다. 위 분석 226행과 1단계 결과는 당시 조사/구현 범위로 보존한다. 아래 내용이 현재 MVC/공통 500 구현 결과이며 TASK-804는 `[ ]`, TASK-814/816은 `[x]`와 다른 모든 Task 상태를 유지한다. TASK-804 전체 완료나 Phase 종료 감사로 확대하지 않는다.

#### 확인한 경로와 구현 선택

제품 Controller 6개(Alarm, Device, BoardingSession, Auth, BusRoute, BoardingCandidate), DTO/Domain 오류와 기존 HTTP/DB 테스트, [API 오류 형식](api.md#5-오류-형식)·관련 Architecture/Requirements/Domain/Package/개발·로컬 실행 규칙·ADR을 대조했다. Security/인증 1단계는 재설계하지 않았다. 구현 전 설치된 Spring MVC **7.0.9**의 `DefaultHandlerExceptionResolver` 바이트코드와 [공식 지원 예외/상태](https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/web/servlet/mvc/support/DefaultHandlerExceptionResolver.html), [Resolver 확장 API](https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/web/servlet/config/annotation/WebMvcConfigurer.html)를 확인했다. Boot/Dependency는 기존 **4.1.1** 그대로다.

- 기존 `GlobalExceptionHandler`는 body Bean Validation, 읽기 실패, Query 누락, Path/Query 타입 실패의 네 400 예외와 `ApiException`·인증 예외를 처리한다. 이 Handler들을 유지하고 `HandlerMethodValidationException`만 추가했다. `getStatusCode()`를 사용해 입력 validation의 400과 반환값 validation의 500을 구분한다.
- 미등록 경로는 기본 static resource 처리의 `NoResourceFoundException` 404, 지원하지 않는 method/media type/Accept는 각각 405/415/406이다. 기본 Resolver는 상태·`Allow`/지원 media type header를 설정하고 `sendError`한다. `ResponseStatusException`과 원인 예외까지의 `@ResponseStatus`도 상태 Resolver에서 `sendError`로 처리한다. 이 경로는 기존 generic 400 Handler의 대상이 아니며 원래 상태를 보존해야 한다.
- `ResponseEntityExceptionHandler` 확장도 검토했으나 기존 Advice와 기본 Resolver를 보존하는 더 작은 변경을 선택했다. Advice의 catch-all은 상태/기본 Resolver보다 먼저 실행하므로 `@ExceptionHandler(Exception.class)`를 추가하지 않았다. `MvcErrorConfiguration.extendHandlerExceptionResolvers()`로 **기존 Resolver 목록의 마지막**에만 예상 밖 Exception fallback을 추가했다.
- 실행 순서는 Controller-local Handler → 기존 Advice → `ResponseStatusExceptionResolver` → `DefaultHandlerExceptionResolver` → 신규 fallback이다. Domain/인증 예외와 정상 MVC 상태가 앞에서 해결되면 fallback은 호출되지 않는다. Security의 Authentication/AccessDenied 예외는 cause chain까지 확인해 fallback에서 처리하지 않고 바깥 Filter에 맡긴다. 실제 MVC→Security 401/403/challenge 회귀로 확인했다.
- 신규 `ApiErrorController`는 Boot 기본 `/error` Controller를 대체해 Servlet의 `ERROR_STATUS_CODE`만 읽고 안전한 JSON을 만든다. `ERROR_MESSAGE`·Exception·URI·Boot error attributes를 직렬화하지 않는다. 상태가 없거나 유효한 4xx/5xx가 아니면 500을 사용한다. 상태에 따른 공개 body는 `ApiErrorResponse.forStatus()`에서 공유한다. Security Filter/정책·운영 설정은 변경하지 않았다.
- 새 enum code는 `NOT_FOUND`(404), `METHOD_NOT_ALLOWED`(405), `NOT_ACCEPTABLE`(406), `UNSUPPORTED_MEDIA_TYPE`(415), `SERVICE_UNAVAILABLE`(503) 다섯 개다. 일반 401/403/400/500은 기존 공통 code를 사용하고, 나머지 상태는 `HTTP_<status>`와 고정 `Request could not be completed.`를 사용한다. 예컨대 generic `HTTP_409`와 Domain의 revision/ownership 409를 구분한다. 기존 **25개 ErrorCode의 HTTP/message는 모두 불변**이다. 정확한 공개 message의 Owner는 API §5다.
- Alarm ID는 이번 요청과 API §5의 ‘0 이하 ID는 INVALID_REQUEST’ 계약을 확인해 조회·활성화·비활성화·삭제의 Path 인자에만 `@Positive`를 추가했다. 양수 부재/다른 owner의 `ALARM_NOT_FOUND` 404는 유지한다. Create DTO나 다른 DTO의 제약을 새로 만들지 않고 기존 필수값·범위·Enum·UUID 검증을 사용했다.

#### 안전한 500 및 기존 Handler 우선순위

fallback은 아직 commit되지 않은 응답 buffer와 기존 Content-Length만 비우고 HTTP 500, `application/json`, 정확히 `INTERNAL_SERVER_ERROR`/`An internal server error occurred.` 두 필드를 기록한다. 이미 commit됐거나 쓰기 I/O가 실패하면 교체를 보장하지 않는다. 연결 단절·streaming 이후 오류나 Container 자체 장애까지 안전한 새 body를 전달한다고 주장하지 않는다.

기존 Device/Boarding의 `DataAccessException`·`TransactionException` local Handler를 그대로 두었다. 실제 Servlet에서 각 local 로그와 고정 JSON 500이 나오고 마지막 fallback 로그가 없음을 확인했다. Alarm/Auth처럼 local Handler가 없는 경로의 Runtime·DataAccessException은 마지막 fallback을 사용한다. 신규 fallback 로그는 `exceptionType`만 기록하며 원문 message/cause·JWT/Refresh/Google/Firebase credential·FCM target·SQL/constraint·서버 경로·Provider 원문을 기록하지 않는다. 별도 Logging Framework나 TASK-805의 구조화 Logging 시스템은 추가하지 않았다.

500은 서버 미저장/rollback의 증거가 아니다. 기존 Device/Boarding의 원래 요청 identity와 결과 재확인, Alarm 생성 결과 불확실성은 그대로다. JWT/Google 검증·Auth Service·Token Rotation/Pair/Logout, Alarm/Boarding Lifecycle, Transit Mapping, Notification, DB/Flyway/Dependency/운영 설정 및 Flutter 제품은 변경하지 않았다.

#### 실제 Servlet HTTP 검증

신규 `MvcErrorHttpIntegrationTest`는 RANDOM_PORT Embedded Tomcat을 `java.net.http.HttpClient`로 호출한다. 기존 인증 dispatch 테스트의 Repository/Transaction mock 구성과 DataSource/JPA 제외를 재사용하고, 제품 Controller/DTO/Security를 실제로 실행한다. 예외 주입은 test의 Service/Google 경계 mock으로 한정했다. 기본 `sendError`, annotation/원인 annotation, 반환값 validation, MVC Security 예외를 위한 probe Controller와 ERROR dispatch 확인 Filter는 **test source에만** 둔다. 실제 DB/Domain 정책은 아래 기존 MySQL 통합 회귀가 별도로 검증한다.

| 검증 상황 | 실제 결과 |
| --- | --- |
| 누락 body, 잘못된 JSON, 필수값/null/빈 문자열, 타입/범위/overflow, Enum/UUID 오류, Bean Validation | 400 INVALID_REQUEST, 정확한 두 필드; 해당 Service 호출 없음 |
| Path/Query 타입 오류와 필수 Query 누락 | 같은 고정 JSON 400 |
| Alarm Path ID 0/-1, 조회/activate/deactivate/delete | Service 호출 전 400; 양수 Domain 404 유지 |
| 기본 미등록 URL / 지원하지 않는 method / 요청 media type / Accept 협상 | 404 NOT_FOUND / 405 METHOD_NOT_ALLOWED / 415 UNSUPPORTED_MEDIA_TYPE / 406 NOT_ACCEPTABLE; ERROR dispatch와 Allow/지원 media type header 보존 |
| 공개 Auth의 ResponseStatusException | 400/401/403/404/405/406/409/415/418/422/429/500/502/503/504 그대로 보존, reason/cause 비노출 |
| Servlet sendError 404/500/503 및 annotation/원인 annotation 404 | 실제 ERROR 재진입을 test Filter header로 확인, 원래 상태와 고정 JSON 보존 |
| 필수 Header 누락 / 반환값 validation 실패 | 각각 400 INVALID_REQUEST / 500 INTERNAL_SERVER_ERROR |
| Alarm/Auth 예상 밖 Runtime·DataAccessException | 고정 JSON 500; 내부 marker·SQL/Token/경로/예외 class 비노출 |
| Device/Boarding local 영속성 Handler | 기존 JSON 500 및 local 우선순위 보존 |
| Domain 403/404/409, Transit Provider 503 | 기존 resource/conflict code 및 TRANSIT_TEMPORARILY_UNAVAILABLE 보존 |
| Security 401/403, Google/Auth 401/503 | 기존 UNAUTHENTICATED/ACCESS_DENIED/SOCIAL_IDENTITY_UNAVAILABLE 및 Security challenge 보존 |

모든 오류 assertion은 HTTP status·Content-Type·정확한 `code/message` key 집합·고정 message와 가짜 민감 marker 비노출을 검사한다. 기존 `AuthenticationErrorDispatchIntegrationTest`의 404/500 assertion도 고정 JSON까지 강화했다. 기존 테스트 삭제·Skip·assertion 약화는 없다. 이번 실제 HTTP 검증이 모든 Endpoint 오류 조합의 최종 Matrix를 대체하지는 않는다.

#### 실행 결과와 검증 범위

환경은 Java/Gradle JVM **21.0.12.1**, Wrapper **8.14.3**, DB는 기존 격리 **mysql:8.4.11 Testcontainers/Flyway**다. Backend 명령은 `env -u JAVA_HOME ./gradlew test --offline --max-workers=1`에 집중/인접 단계의 `--tests`를 지정했다. 초기 Gradle wrapper cache 접근 제한은 허용된 권한 확장으로 해결했으며 제품/설정/Dependency를 우회 변경하지 않았다.

| 단계 | 실행 / 통과 | 실패 / 오류 / Skip | 범위 |
| --- | ---: | --- | --- |
| MVC 집중 + 실제 Servlet | 83 / 83 | 0 / 0 / 0 | 신규 MvcErrorHttpIntegrationTest 73 + 기존 AuthenticationErrorDispatchIntegrationTest 10 |
| 인접 Backend 회귀 | 256 / 256 | 0 / 0 / 0 | Alarm 38, Device 12, Boarding 130, BoardingCandidate 9, RouteSearch 10, RouteStop 5, Security 25, NonWeb 1, Auth 26 |
| Backend 전체 | 1,398 / 1,398 | 0 / 0 / 0 | 81개 클래스, 실제 JUnit XML suite/testcase 합계 일치, BUILD SUCCESSFUL(6분 33초) |
| Flutter Client 선택 회귀 | 131 / 131 | 0 / 0 / 0 | Alarm/Timeout, Device, BoardingSession/Candidate, RouteSearch/Stop, AuthenticatedApi, BackendAuth 9개 파일 |

Flutter는 제품을 수정하지 않았다. `alarm_api_client_test.dart`에 신규 고정 JSON 400/404/405/406/415/500/503의 status/code/message 보존·Domain 404와의 구분·Create 자동 replay 없음 7개를 추가했다. 기존 Session의 500 uncertainty/원래 UUID 보존과 Device/Transit/Auth 호환성도 함께 통과했다. `flutter analyze --no-pub`는 No issues, 변경 Dart의 format 검사는 통과했다. Flutter 전체는 실행하지 않았으며 과거 전체 결과를 이번 수치에 합산하지 않는다. 응답 key와 기존 Domain/status·인증 경계가 유지되고 관련 Client 9개 파일이 통과하므로 전체 Flutter 회귀로 확대하지 않았다. Alarm 화면의 생성 실패·응답 유실 복구 자체는 다음 단계다.

기존 Backend 1,325개와 신규 Servlet 73개를 합친 최종 1,398개 전부 통과했으며 테스트 실패로 인한 재실행은 없다. 종료된 격리 DB의 cached context shutdown 중 Hikari 연결 종료 경고가 있었지만 JUnit 실패/오류/Skip은 모두 0이다. 종료 후 Docker에는 시작 전과 같은 개발 MySQL Container `978144708abb`만 남았으며 기존 Container/Volume을 변경하지 않았다. 단계별 테스트 결과는 서로 겹치므로 전체 테스트 수에 중복 합산하지 않는다. 실행 로그는 `/private/tmp/stopbell-task804-step2-{focused,adjacent,full,flutter,flutter-analyze}.log`, Backend JUnit XML은 같은 접두사의 단계별 `*-results` 디렉터리에 보존한다. 실제 Google/Transit Provider/FCM·Firebase credential·iPhone, 개발/운영 DB는 사용하지 않았다.

#### 문서 일관성과 남은 완료 조건

[API §5](api.md#5-오류-형식)에 범용 오류 code/message·Servlet 상태 보존·fallback 우선순위·보안/commit 한계와 Alarm Path ID 범위를 최소 동기화했다. 위 분석/1단계 기록은 수정하지 않고 이번 절만 추가하며, 이미 정확한 Owner 문서와 devlog는 변경하지 않는다. 기존 분석의 ‘미구현/미확정’과 1단계의 ‘후속 범위’는 각 시점의 기록이다. 현재 상태는 이번 절과 API Owner를 따른다.

최종 변경은 허용된 11개 파일(제품 6, Java 테스트 2, Flutter 테스트 1, 문서 2)뿐이다. 기존 25개 ErrorCode의 status/message, Task 체크박스 102개와 새 절을 제외한 task-list 전체 본문의 불변을 비교했고 `git diff --check`를 통과했다. 필요한 문서 동기화와 집중·인접·전체 Backend 및 관련 Flutter 검증이 성공했으므로 사용자 지정 subject `fix: TASK-804 normalize MVC error responses`와 실제 개행 bullet body로 명시적 Stage·Commit·Push를 진행한다.

TASK-804는 `[ ]`이며 남은 작업은 (1) 전체 Endpoint 오류 Matrix 최종 검증, (2) Flutter Alarm 생성 실패·응답 유실 복구, (3) TASK-802 중복 방지 책임/경계 확정, (4) 최종 Backend·Flutter 회귀, (5) TASK-804 완료 판단이다. `METADATA_UNAVAILABLE`은 독립 의미/정책이 확정되지 않아 추가하지 않았다. 본 단계 성공 및 Commit·Push도 TASK-804 전체 완료를 뜻하지 않는다.

### TASK-804 3단계 — Flutter Alarm 응답 유실 복구 및 중복 생성 책임 구분 (2026-10-31)

`main`, HEAD `bed17dfa8846d996a96cfe9a7359372ca1e21b83`, tracked/untracked 변경 없는 상태에서 시작했다. 위 분석과 1·2단계 및 기존 감사 기록은 당시 결과 그대로 보존한다. 이번 결과는 TASK-804 3단계에 한정하며 TASK-804/TASK-802는 `[ ]`, TASK-814/TASK-816은 `[x]`와 다른 모든 Task 상태를 유지한다.

#### 변경과 복구 경계

- 제품 변경은 Flutter `BusRouteStopScreen`, `AlarmDetailScreen`, `AlarmHome`, `AlarmApiClient` 4개 파일이다. 기존 5개 테스트 파일을 보강/호환 수정하고 `api.md`·`architecture.md`를 필요한 범위만 동기화했다. Backend Controller/Service/Repository/DTO·DB/Migration·Dependency·AuthSession/AuthenticatedApiClient·Boarding/Notification 제품과 Push 준비 정책은 변경하지 않았다.
- 생성은 올바른 HTTP 201 Alarm response만 성공이다. 500/502/503/504와 모든 5xx, 408, Timeout·Transport/연결 단절·응답 유실, 성공 JSON 파싱 실패 및 판단 불가 오류를 결과 불확실로 분류한다. 문서에 정의된 생성 전 거부 조합만 확정 거부로 처리하며 알 수 없는 4xx나 생성 API에 정의되지 않은 ALARM_NOT_FOUND도 재생성 허용 근거로 쓰지 않는다. API Client는 성공 JSON 파싱 실패의 HTTP status도 보존한다.
- 기존 `_outcomeUnknown`으로 같은 화면의 Create·정류장 선택·옵션 변경·새로고침을 차단하고 서버에 반영됐을 가능성과 목록 확인을 안내한다. 중복 탭/보관된 callback도 추가 POST를 실행하지 않으며 자동 Create 재시도·Activate는 없다. 기존 stale target의 목록 재조회/선택 초기화와 INVALID_ALARM_REQUEST의 수동 새로고침, Push 준비 실패/취소 시 HTTP 미전송 재시도는 유지한다.
- 생성 성공 뒤 Activate 실패는 원래 Alarm ID와 `activationUnverified`를 상세로 전달한다. 상세는 즉시 원래 ID를 GET하며 생성 당시 INACTIVE를 최신 서버 상태로 확정하지 않는다. 조회 실패 시 ‘상태: 확인 필요’와 명시적 재조회를 제공하고 새 Alarm을 만들지 않는다.
- 상세 Activate/Deactivate의 불확실 오류는 같은 ID를 한 번 GET한다. 조회 중과 조회 실패 뒤에는 모든 Mutation을 막는다. 성공 GET만 조회 시점 실제 상태로 반영하며 직전 Mutation이 원인이라고 주장하지 않는다. 명시적 재조회 성공 후 UI를 다시 허용하고 POST를 자동 반복하지 않는다. FOLLOW_UP 추적과 다시 활성화/비활성화 Lifecycle은 유지하며 미확인 재활성화로 추적을 반복 초기화하지 않는다.
- Delete 정상 204는 기존 목록 Navigation이다. 불확실한 Delete는 같은 ID GET으로 확인하며 200은 조회 시점 잔존 상태, 404 ALARM_NOT_FOUND는 삭제/현재 계정 접근 불가를 구분하지 않는 안내다. 404로 삭제 성공을 확정하거나 자동 이동하지 않고 사용자가 목록 이동을 선택한다. GET 실패에는 미확인 잠금과 재조회를 제공하며 DELETE 자동 재전송은 없다.
- 상세에도 기존 Home의 Auth generation·User/login identity·navigation epoch callback을 전달한다. request generation·원래 Alarm ID·Client identity·mounted를 함께 검사해 logout, 계정 변경/같은 계정 재로그인, 화면 이탈/다른 알림 선택 시 늦은 성공·실패·GET을 폐기한다. ID/Client 교체·dispose는 이전 요청을 무효화하고 GET 중 Mutation/중복 GET을 막는다. 무효화 직후 rebuild 전에도 후속 GET/POST/Navigation을 막는다. 제한 시간 안의 기존 401 Refresh/최대 1회 Replay, Timeout 뒤 늦은 401의 Refresh/Replay 차단은 유지한다.

상세 HTTP/복구 정책은 [API](api.md#flutter-alarm-mutation-결과-확인-task-804-3단계), 실행/수명 경계는 [Architecture](architecture.md#flutter-alarm-응답-유실-복구-task-804-3단계)가 소유한다. 이번 단계는 Phase 종료 감사나 실기기/서버 배포 검증으로 확대하지 않는다.

#### TASK-802 책임과 현재 API 한계

현재 `POST /api/v1/alarms`에는 clientRequestId가 없고 AlarmResponse에는 원래 targetStopOccurrenceId가 없다. 목록 조회 성공이나 노선 번호·정류장 이름·옵션의 일치는 이전 특정 생성 요청 성공의 증거가 아니다. Flutter는 목록의 유사 Alarm을 자동 연결하지 않으며 임의 ID/Flutter 전용 Request ID를 추가하지 않는다. 화면 메모리 잠금은 해당 화면의 중복 전송 억제일 뿐 앱 재진입·재시작·복수 기기까지의 서버 멱등성 보장이 아니다.

TASK-804는 실패/불확실성을 성공으로 위장하지 않는 UX, 중복 자동 Mutation 억제, 원래 ID GET 복구와 Session 보호를 담당한다. TASK-802의 기존 Duplicate Prevention Test 범위는 Phase 5/7의 Evaluation·activation generation·tracking cycle·Lifecycle 동시성 및 TASK-708의 logical NotificationEvent/recipient Delivery 중복 억제 검증을 보존한다. Alarm 생성 요청의 정확한 중복 방지·결과 식별은 이와 별개의 서버 책임으로 TASK-802에 연결한다.

향후 설계 후보는 User별 Client Request ID, 원본 요청 Fingerprint 비교, DB 수준 identity/unique 식별, 같은 요청에 기존 Alarm 반환, 다른 내용의 명시적 Conflict, 응답 유실 뒤 Request ID 조회다. 이는 이번에 채택/구현한 서버 계약이 아니다. TASK-802에서는 먼저 계약을 결정한 뒤 동일 요청 순차/동시 재전송·응답 유실·transaction commit/rollback·서로 다른 내용 conflict·User별 격리·기존 Alarm 반환/조회 및 기존 Event/Delivery dedup 불변을 검증해야 한다. 현재 API를 BoardingSession UUID 멱등성 API처럼 취급하지 않는다. TASK-802 `[ ]`를 유지한다.

#### 실제 검증 결과

Mock/Fake transport·Push fixture·가상 Timeout만 사용했다. 실제 Google/FCM/Transit 호출, 개발/운영 DB, 실제 iPhone은 사용하지 않았다. Backend 제품/계약을 변경하지 않아 Backend 테스트는 실행하지 않았으며 이전 1,398개 결과를 이번 결과로 합산하지 않는다.

| 단계 | 실행 | 통과 | 실패 | 범위 |
| --- | ---: | ---: | ---: | --- |
| Alarm 집중 | 154 | 154 | 0 | alarm_screen / alarm_creation_push / alarm_api_client / alarm_api_client_timeout |
| 인접 Flutter 회귀 | 203 | 203 | 0 | auth_session / authenticated_api_client / notification_lifecycle / push_registration_service / boarding_session_client / boarding_notification / bus_route_stop_screen / phase6_regression |
| Flutter 전체, 1회 | 637 | 637 | 0 | flutter test --no-pub --reporter expanded |
| 테스트 lint 수정 후 관련 재검증 | 131 | 131 | 0 | alarm_screen / alarm_creation_push / alarm_api_client_timeout |
| 안내 문구 assertion 최종 확인 | 106 | 106 | 0 | alarm_screen / alarm_creation_push |
| Flutter Analyze 최종 | 1 | 1 | 0 | flutter analyze --no-pub, No issues found |
| 변경 Dart Format | 9 files | 9 files | 0 | --output=none --set-exit-if-changed |

집중 → 인접 → 전체 → 정적 분석 순서로 실행했다. 전체 회귀 뒤 Analyze가 추가 테스트의 if 중괄호 4곳을 지적해 동작 변경 없이 수정하고 관련 131개와 Analyze를 재검증했다. 기존 부정 assertion도 새 불확실 안내 문구를 검사하도록 맞춘 뒤 Widget 106개를 최종 확인했다. 전체 테스트는 추가 실행하지 않았다. 작성 중 삭제 확인 Dialog의 로딩 표시 문제와 테스트 fixture의 cascade/비동기 assertion 문제를 수정했으며 최종 검증 실패/Skip은 0이다. 단계별 숫자는 중복되므로 합산하지 않는다. 로그는 `/private/tmp/stopbell-alarm-focused-final.log`, `stopbell-alarm-adjacent.log`, `stopbell-flutter-full.log`, `stopbell-alarm-lint-retest.log`, `stopbell-alarm-copy-retest.log`, `stopbell-flutter-analyze-final.log`다.

생성 5xx 후 재생성 허용, 생성 후 활성화 유실을 INACTIVE로 단정, 상세 Mutation 유실 뒤 stale 상태/일반 실패만 표시하던 문제를 해결했다. 정상 성공·Domain 거부·Push 준비 순서와 Session 보호를 보존했다. 기존 task-list 본문/체크박스와 이번 절을 제외한 감사·완료 기록 불변 및 git diff --check를 확인한 후 사용자 지정 subject `fix: TASK-804 recover uncertain alarm mutations`와 실제 개행 bullet body로 명시적 Stage·Commit·Push를 진행한다.

TASK-804 `[ ]`의 남은 조건은 (1) 전체 Endpoint 오류 Matrix 최종 검증, (2) TASK-804 전체 완료 조건 평가, (3) TASK-802로 분리한 서버 중복 방지 책임의 후속 확인이다. 이번 Commit·Push는 3단계 완료만 의미하며 TASK-804 전체 완료를 뜻하지 않는다.

### TASK-804 4단계 — 전체 API 오류 계약 최종 검증 및 완료 판정 (2026-10-31)

`main`, HEAD `4715af8172e2be582a26ca3c988b3930008996b8`, tracked/untracked 변경 없는 상태에서 시작했다. 위 분석과 1·2·3단계 및 기존 감사 기록은 당시 결과 그대로 보존한다. 이번 절은 현재 Controller·ErrorCode·Client·실행 결과를 대조한 **최종 검증**이다. 과거 분석의 22개 ErrorCode·미구현 후보·후속 완료 조건과 구분한다. TASK-804만 `[x]`로 변경하고 TASK-802 `[ ]`, TASK-814/816 `[x]` 및 다른 Task 상태는 유지한다. Phase 8 종료 감사나 다음 Task 착수는 아니다.

#### 현재 Endpoint와 ErrorCode 전체 목록

제품 Controller는 Auth·Alarm·BusRoute·BoardingCandidate·Device·BoardingSession **6개**, 명시적 Method/Path 조합은 **20개**다. 아래 Endpoint Matrix가 현재 전체 목록이며, 위 분석의 Endpoint 목록을 소스와 다시 대조해 추가·삭제가 없음을 확인했다. BoardingSession collection GET의 `scope=current`와 `clientRequestId`는 하나의 mapping이다. 별도로 운영 `GET /actuator/health` 1개와 framework 오류 처리 `/error`가 있다. 암시적 HEAD/OPTIONS는 새 제품 API로 세지 않는다. Notification/FCM·metadata bootstrap·scheduler의 별도 HTTP Controller는 없다.

현재 enum은 **30개**이며 아래가 전부다. `HTTP_<status>`는 enum 추가가 아닌 generic fallback이다. 고정 message는 [API 오류 형식](api.md#5-오류-형식), Device/Boarding 계약과 [ErrorCode](../backend/src/main/java/com/stopbell/common/error/ErrorCode.java)를 대조했다. 기존 30개의 status/message는 이번 작업에서 변경하지 않았다.

| HTTP | 현재 ErrorCode 전체 목록 |
| ---: | --- |
| 400 | `INVALID_REQUEST`, `INVALID_ALARM_REQUEST` |
| 401 | `UNAUTHENTICATED` |
| 403 | `ACCESS_DENIED`, `DEVICE_CREDENTIAL_INVALID` |
| 404 | `NOT_FOUND`, `ALARM_NOT_FOUND`, `BUS_ROUTE_NOT_FOUND`, `TARGET_STOP_OCCURRENCE_NOT_FOUND`, `DEVICE_NOT_FOUND`, `BOARDING_SESSION_NOT_FOUND` |
| 405 | `METHOD_NOT_ALLOWED` |
| 406 | `NOT_ACCEPTABLE` |
| 409 | `DEVICE_OWNERSHIP_CONFLICT`, `DEVICE_PLATFORM_CONFLICT`, `DEVICE_REGISTRATION_STALE`, `DEVICE_REVISION_CONFLICT`, `DEVICE_PUSH_TARGET_CONFLICT`, `BOARDING_CANDIDATE_CHANGED`, `BOARDING_SESSION_CONFLICT`, `BOARDING_REQUEST_CONFLICT`, `BOARDING_REVISION_CONFLICT`, `BOARDING_TARGET_NOT_AHEAD`, `BOARDING_RECONFIRM_REQUIRED`, `BOARDING_TRACKING_UNSUPPORTED` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 500 | `INTERNAL_SERVER_ERROR` |
| 503 | `SOCIAL_IDENTITY_UNAVAILABLE`, `TRANSIT_TEMPORARILY_UNAVAILABLE`, `SERVICE_UNAVAILABLE` |

`BOARDING_TARGET_NOT_AHEAD`는 이전 Backend 호환 enum으로 현행 제품 throw 지점이 없다. 목록·status·Owner 문서 보존을 정적으로 확인했으며, 도달할 수 없는 HTTP 오류를 만들어 PASS라고 하지 않는다. `METADATA_UNAVAILABLE`은 독립 트리거/의미가 없어 추가하지 않기로 한 기존 결정을 유지하고 TASK-804 본문의 폐기 후보만 현재 503 계약으로 정리했다. Alarm Create의 현행 metadata 부재는 `TARGET_STOP_OCCURRENCE_NOT_FOUND`이며 `BUS_ROUTE_NOT_FOUND`를 던지지 않는다. Route 404는 Stops/후보 경로에서 검증한다.

#### 전체 Endpoint 오류 Matrix

표의 테스트 약어는 아래 실제 클래스다. **H**는 RANDOM_PORT Embedded Tomcat/loopback HTTP(제품 Controller·Security·Resolver, Service 경계 예외 주입), **M**은 실제 Filter/Controller/Service와 격리 MySQL Testcontainers를 사용하는 MockMvc다. 따라서 H의 예외 주입은 Domain/DB commit 검증이 아니며 M과 구분한다. PASS는 이번 최종 suite에서 실행한 해당 테스트의 결과다. 모든 Endpoint에 존재하지 않는 Domain 오류를 조합하지 않는다.

| 약어 | 테스트 클래스 | 실행 경계 |
| --- | --- | --- |
| MH | `MvcErrorHttpIntegrationTest` | H |
| ED | `AuthenticationErrorDispatchIntegrationTest` | H |
| AU | `AuthControllerIntegrationTest` | M |
| AL | `AlarmServiceIntegrationTest` | M + 명시한 Service 직접 검증 |
| DE | `DeviceApiIntegrationTest` | M |
| BS | `BoardingSessionApiIntegrationTest` | M + 명시한 Service/Transaction 검증 |
| BC | `BoardingCandidateApiIntegrationTest` | M |
| RS | `BusRouteSearchIntegrationTest` | M |
| ST | `BusRouteStopIntegrationTest` | M + Service 직접 검증 |

보호된 17개 mapping은 같은 Security rule을 사용한다. 누락/무효 JWT의 HTTP 401은 영역별 기존 테스트(AL.reject_unauthenticated_alarm_api_request, DE.require_jwt_for_all_endpoints, BS.require_jwt/reconfirm_authentication_and_ownership, BC.require_valid_jwt, RS.require_authentication, ST.require_authentication)와 아래 공통 Security Matrix로 함께 검증했다. 모든 mapping에 동일 JWT case를 복제하지 않았다. `/auth/*`의 지정 POST만 공개이고 잘못된 Bearer를 함께 보내면 Controller 전에 인증 실패할 수 있다.

| Endpoint | HTTP Method | 오류 상황 | 기대 HTTP | 기대 Code | 검증 테스트 | 결과 |
| --- | --- | --- | ---: | --- | --- | --- |
| `/auth/google` | POST | body 누락/깨진 JSON | 400 | INVALID_REQUEST | MH.reject_invalid_request | PASS |
| `/auth/google` | POST | 잘못된 Credential/실제 Token parser 실패 | 401 | UNAUTHENTICATED | AU.reject_invalid_google_credential/reject_real_malformed_google_credential | PASS |
| `/auth/google` | POST | Google 검증 I/O 장애 | 503 | SOCIAL_IDENTITY_UNAVAILABLE | AU.return_service_unavailable_when_google_identity_verification_fails, MH.preserve_authentication_contracts | PASS |
| `/auth/google` | POST | 예상 밖 Runtime/DB 실패 | 500 | INTERNAL_SERVER_ERROR | MH.normalize_public_unexpected_failure | PASS |
| `/auth/refresh` | POST | body 누락/깨진 JSON | 400 | INVALID_REQUEST | MH.reject_invalid_request | PASS |
| `/auth/refresh` | POST | unknown/만료/Rotation 뒤 재사용/null/blank | 401 | UNAUTHENTICATED | AU.reject_unknown_refresh_token/reject_expired_refresh_token/reject_reused_refresh_token/reject_blank_or_null_refresh_token | PASS |
| `/auth/logout` | POST | body 누락/깨진 JSON | 400 | INVALID_REQUEST | MH.reject_invalid_request | PASS |
| `/api/v1/alarms` | POST | 필수값/null/타입/범위/JSON/Bean Validation | 400 | INVALID_REQUEST | AL.reject_invalid_target_stop_occurrence_id/reject_missing_or_malformed_request_body, MH.reject_invalid_request | PASS |
| `/api/v1/alarms` | POST | 첫/마지막 occurrence의 불가능한 옵션 | 400 | INVALID_ALARM_REQUEST | AL.reject_boundary_alarm_request | PASS |
| `/api/v1/alarms` | POST | 인증 후 User 소실 | 401 | UNAUTHENTICATED | MH.preserve_missing_authenticated_user + AL.reject_missing_authenticated_user(Service 직접) | PASS |
| `/api/v1/alarms` | POST | stale/없는 target occurrence | 404 | TARGET_STOP_OCCURRENCE_NOT_FOUND | AL.return_target_stop_occurrence_not_found | PASS |
| `/api/v1/alarms` | GET | JWT 없음 | 401 | UNAUTHENTICATED | MH.preserve_authentication_contracts, AL.reject_unauthenticated_alarm_api_request | PASS |
| `/api/v1/alarms` | GET | Runtime/DataAccess/Transaction 실패 | 500 | INTERNAL_SERVER_ERROR | MH.normalize_unexpected_failure | PASS |
| `/api/v1/alarms/{alarmId}` | GET | Path 타입/0/음수 | 400 | INVALID_REQUEST | MH.reject_path_and_query_errors/reject_nonpositive_alarm_id | PASS |
| `/api/v1/alarms/{alarmId}` | GET | 양수 부재/다른 owner | 404 | ALARM_NOT_FOUND | AL.return_alarm_not_found_for_missing_or_other_user_alarm/authenticated_get_returns_not_found_for_missing_alarm | PASS |
| `/api/v1/alarms/{alarmId}/activate` | POST | 0/음수 Path | 400 | INVALID_REQUEST | MH.reject_nonpositive_alarm_id | PASS |
| `/api/v1/alarms/{alarmId}/activate` | POST | 양수 부재/다른 owner | 404 | ALARM_NOT_FOUND | AL.return_alarm_not_found_for_missing_or_other_user_alarm/authenticated_post_activate_returns_not_found_for_other_users_alarm | PASS |
| `/api/v1/alarms/{alarmId}/deactivate` | POST | 0/음수 Path | 400 | INVALID_REQUEST | MH.reject_nonpositive_alarm_id | PASS |
| `/api/v1/alarms/{alarmId}/deactivate` | POST | 양수 부재/다른 owner | 404 | ALARM_NOT_FOUND | AL.return_alarm_not_found_for_missing_or_other_user_alarm/authenticated_post_deactivate_returns_not_found_for_other_users_active_alarm | PASS |
| `/api/v1/alarms/{alarmId}` | DELETE | 0/음수 Path | 400 | INVALID_REQUEST | MH.reject_nonpositive_alarm_id | PASS |
| `/api/v1/alarms/{alarmId}` | DELETE | 양수 부재/다른 owner | 404 | ALARM_NOT_FOUND | AL.return_alarm_not_found_for_missing_or_other_user_alarm/authenticated_delete_rejects_other_users_alarm | PASS |
| `/api/v1/bus-routes` | GET | 필수 query 누락/blank | 400 | INVALID_REQUEST | RS.reject_missing_or_blank_query, MH.reject_path_and_query_errors | PASS |
| `/api/v1/bus-routes` | GET | JWT 없음 | 401 | UNAUTHENTICATED | RS.require_authentication + 공통 Security 검증 | PASS |
| `/api/v1/bus-routes/{routeId}/stops` | GET | Path 타입/비양수 | 400 | INVALID_REQUEST | ST.reject_invalid_route_id, MH.reject_path_and_query_errors | PASS |
| `/api/v1/bus-routes/{routeId}/stops` | GET | Route 부재 | 404 | BUS_ROUTE_NOT_FOUND | ST.return_bus_route_not_found | PASS |
| `/api/v1/bus-routes/{routeId}/stops` | GET | Route는 있으나 occurrence 0개(invariant) | 500 | INTERNAL_SERVER_ERROR | ST.reject_route_without_occurrences(실제 DB/HTTP 추가) | PASS |
| `/api/v1/bus-routes/{routeId}/boarding-candidates` | GET | 필수 query/Path·Query 타입/비양수/다른 Route occurrence | 400 | INVALID_REQUEST | BC.invalid_ids/reject_other_route_occurrences, MH.reject_path_and_query_errors | PASS |
| `/api/v1/bus-routes/{routeId}/boarding-candidates` | GET | Route 부재 | 404 | BUS_ROUTE_NOT_FOUND | BC.missing_metadata | PASS |
| `/api/v1/bus-routes/{routeId}/boarding-candidates` | GET | 목적/승차 occurrence 부재 | 404 | TARGET_STOP_OCCURRENCE_NOT_FOUND | BC.missing_metadata | PASS |
| `/api/v1/bus-routes/{routeId}/boarding-candidates` | GET | Provider 장애/upstream 401 | 503 | TRANSIT_TEMPORARILY_UNAVAILABLE | BC.provider_error_response/upstream_unauthorized_is_not_client_unauthorized | PASS |
| `/api/v1/devices` | POST | strict DTO/UUID/header/형식/범위 | 400 | INVALID_REQUEST | DE.reject_invalid_input_without_coercion | PASS |
| `/api/v1/devices` | POST | credential 불일치 | 403 | DEVICE_CREDENTIAL_INVALID | DE.enforce_credentials_platform_authority_and_limits | PASS |
| `/api/v1/devices` | POST | ownership/generation 불일치 | 409 | DEVICE_OWNERSHIP_CONFLICT | DE.transfer_ownership_and_reject_old_generations | PASS |
| `/api/v1/devices` | POST | platform 변경 | 409 | DEVICE_PLATFORM_CONFLICT | DE.enforce_credentials_platform_authority_and_limits | PASS |
| `/api/v1/devices` | POST | 낮은 revision | 409 | DEVICE_REGISTRATION_STALE | DE.rotate_target_and_enforce_revision | PASS |
| `/api/v1/devices` | POST | 같은 revision의 다른 상태 | 409 | DEVICE_REVISION_CONFLICT | DE.rotate_target_and_enforce_revision | PASS |
| `/api/v1/devices` | POST | 다른 설치본의 FID 점유/식별된 UNIQUE 경합 | 409 | DEVICE_PUSH_TARGET_CONFLICT | DE.enforce_credentials_platform_authority_and_limits/roll_back_target_uniqueness_races | PASS |
| `/api/v1/devices` | POST | 미분류 실제 FK 오류 | 500 | INTERNAL_SERVER_ERROR | DE.report_unclassified_database_failure_safely | PASS |
| `/api/v1/devices/{installationId}` | GET | UUID/header/credential 형식 오류 | 400 | INVALID_REQUEST | DE.reject_invalid_input_without_coercion | PASS |
| `/api/v1/devices/{installationId}` | GET | credential 불일치 | 403 | DEVICE_CREDENTIAL_INVALID | DE.look_up_state_without_mutation_or_owner_disclosure | PASS |
| `/api/v1/devices/{installationId}` | GET | 설치본 부재 | 404 | DEVICE_NOT_FOUND | DE.look_up_state_without_mutation_or_owner_disclosure | PASS |
| `/api/v1/devices/{installationId}` | GET | local DataAccess 실패 | 500 | INTERNAL_SERVER_ERROR | MH.preserve_local_persistence_handlers | PASS |
| `/api/v1/devices/disable` | POST | 필수값/strict 타입/header 오류 | 400 | INVALID_REQUEST | DE.reject_invalid_input_without_coercion | PASS |
| `/api/v1/devices/disable` | POST | credential 불일치 | 403 | DEVICE_CREDENTIAL_INVALID | DE.enforce_credentials_platform_authority_and_limits | PASS |
| `/api/v1/devices/disable` | POST | 부재/다른 owner | 404 | DEVICE_NOT_FOUND | DE.look_up_state_without_mutation_or_owner_disclosure/transfer_ownership_and_reject_old_generations | PASS |
| `/api/v1/devices/disable` | POST | generation 불일치 | 409 | DEVICE_OWNERSHIP_CONFLICT | DE.transfer_ownership_and_reject_old_generations | PASS |
| `/api/v1/devices/disable` | POST | 낮은 revision/동일 revision의 다른 상태 | 409 | DEVICE_REGISTRATION_STALE / DEVICE_REVISION_CONFLICT | DE.disable_and_reactivate_only_current_device | PASS |
| `/api/v1/boarding-sessions` | POST | strict body/UUID/필수값/옵션 오류 | 400 | INVALID_REQUEST | BS.invalid_create_body/reject_invalid_preparation_choice | PASS |
| `/api/v1/boarding-sessions` | POST | 만료/타 User/변경된 candidate·metadata | 409 | BOARDING_CANDIDATE_CHANGED | BS.reject_invalid_reference_and_number/provider_metadata_and_target_failures | PASS |
| `/api/v1/boarding-sessions` | POST | 동일 UUID의 다른 원본 요청 | 409 | BOARDING_REQUEST_CONFLICT | BS.request_and_session_conflicts | PASS |
| `/api/v1/boarding-sessions` | POST | 다른 미종료 Session | 409 | BOARDING_SESSION_CONFLICT | BS.request_and_session_conflicts | PASS |
| `/api/v1/boarding-sessions` | POST | 검증되지 않은 차량/운행 근거 | 409 | BOARDING_TRACKING_UNSUPPORTED | BS.reject_unsupported_moving_creation/known_run_end_does_not_create_session | PASS |
| `/api/v1/boarding-sessions` | POST | Provider 재검증 장애 | 503 | TRANSIT_TEMPORARILY_UNAVAILABLE | BS.provider_metadata_and_target_failures | PASS |
| `/api/v1/boarding-sessions/{sessionId}` | GET | Path 타입 | 400 | INVALID_REQUEST | MH.reject_path_and_query_errors | PASS |
| `/api/v1/boarding-sessions/{sessionId}` | GET | 인증 후 User 소실 | 401 | UNAUTHENTICATED | MH.preserve_missing_authenticated_user | PASS |
| `/api/v1/boarding-sessions/{sessionId}` | GET | 부재/다른 owner | 404 | BOARDING_SESSION_NOT_FOUND | BS.query_and_cancel_with_ownership | PASS |
| `/api/v1/boarding-sessions/{sessionId}` | GET | local Transaction 실패 | 500 | INTERNAL_SERVER_ERROR | BS.report_transaction_failure_over_http_safely, MH.preserve_local_persistence_handlers | PASS |
| `/api/v1/boarding-sessions` | GET | query 조합/중복/UUID 오류 | 400 | INVALID_REQUEST | BS.invalid_query | PASS |
| `/api/v1/boarding-sessions` | GET | JWT 없음/무효 | 401 | UNAUTHENTICATED | BS.require_jwt + 공통 Security 검증 | PASS |
| `/api/v1/boarding-sessions/{sessionId}/reconfirm` | POST | strict revision/body 오류 | 400 | INVALID_REQUEST | BS.reconfirm_invalid_body | PASS |
| `/api/v1/boarding-sessions/{sessionId}/reconfirm` | POST | JWT 없음 | 401 | UNAUTHENTICATED | BS.reconfirm_authentication_and_ownership + 공통 Security 검증 | PASS |
| `/api/v1/boarding-sessions/{sessionId}/reconfirm` | POST | 부재/다른 owner | 404 | BOARDING_SESSION_NOT_FOUND | BS.reconfirm_authentication_and_ownership | PASS |
| `/api/v1/boarding-sessions/{sessionId}/reconfirm` | POST | reference 소실/identity·target·metadata 변경 | 409 | BOARDING_CANDIDATE_CHANGED | BS.reconfirm_expired_reference/reconfirm_identity_and_target_change/reconfirm_metadata_race | PASS |
| `/api/v1/boarding-sessions/{sessionId}/reconfirm` | POST | stale revision/경합 | 409 | BOARDING_REVISION_CONFLICT | BS.reconfirm_success_and_lost_response_retry | PASS |
| `/api/v1/boarding-sessions/{sessionId}/reconfirm` | POST | 보류 외 상태/terminal | 409 | BOARDING_RECONFIRM_REQUIRED | BS.reconfirm_requires_suspension | PASS |
| `/api/v1/boarding-sessions/{sessionId}/reconfirm` | POST | 실제 Event FK 실패 | 500 | INTERNAL_SERVER_ERROR | BS.report_database_failure_over_http_safely | PASS |
| `/api/v1/boarding-sessions/{sessionId}/cancel` | POST | strict revision/body 오류 | 400 | INVALID_REQUEST | BS.invalid_revision_body | PASS |
| `/api/v1/boarding-sessions/{sessionId}/cancel` | POST | 다른 owner | 404 | BOARDING_SESSION_NOT_FOUND | BS.query_and_cancel_with_ownership | PASS |
| `/api/v1/boarding-sessions/{sessionId}/cancel` | POST | stale revision | 409 | BOARDING_REVISION_CONFLICT | BS.cancel_requires_current_revision | PASS |

예상 밖 500은 모든 Endpoint에 같은 예외를 주입하는 방식 대신 실제 DB 실패와 공개 Auth·Alarm·Device·Boarding의 공통/local Handler 경로를 검증했다. 아래 공통 Matrix를 포함해 판단하며, 실행하지 않은 Endpoint별 500 조합을 개별 PASS로 주장하지 않는다. 각 Endpoint에 적용되는 같은 Servlet binding·Security·Resolver 정책은 기존 테스트를 재사용했다. Boarding 생성/재확인에서 metadata.read()가 전달하는 BUS_ROUTE_NOT_FOUND/TARGET_STOP_OCCURRENCE_NOT_FOUND 404와 Provider 재검증의 503도 현재 소스 경로를 확인했으며, metadata 실증은 BC.missing_metadata, 503 실증은 BS.provider_metadata_and_target_failures, 공개 code 직렬화는 MH의 공통 Advice 검증을 재사용한다. 이 공통 경계 검증을 별도 Boarding HTTP 예외 주입 실행으로 세지 않는다.

#### 공통 HTTP·Security·Auth·MVC와 우선순위

| Endpoint/경계 | HTTP Method | 오류 상황 | 기대 HTTP | 기대 Code | 검증 테스트 | 결과 |
| --- | --- | --- | ---: | --- | --- | --- |
| 테스트 인증 경계와 제품 보호 API | GET 등 | JWT 없음/변조/만료/issuer/exp 누락/잘못된 Bearer | 401 | UNAUTHENTICATED | SecurityConfigurationIntegrationTest의 reject_request_without_bearer_token/reject_tampered_access_token/reject_expired_access_token/reject_access_token_with_invalid_issuer/reject_missing_expiry/reject_malformed_bearer | PASS |
| 테스트 인증 경계 | GET | sub 누락·빈 값·0·음수·비숫자·선행 0·overflow·JSON 숫자형 | 401 | UNAUTHENTICATED | SecurityConfigurationIntegrationTest.reject_invalid_subject_before_authentication/reject_numeric_subject_claim | PASS |
| 테스트 deny 경계/MVC Security 예외 | GET | 인증된 권한 거부 / 미인증 | 403 / 401 | ACCESS_DENIED / UNAUTHENTICATED | SecurityConfigurationIntegrationTest.distinguish_access_denied_from_unauthenticated, MH.preserve_security_exception_boundary | PASS |
| `/api/v1/missing-path` | GET | 미등록 URL | 404 | NOT_FOUND | MH.preserve_default_mvc_status | PASS |
| `/api/v1/alarms` | PUT | 미지원 Method | 405 | METHOD_NOT_ALLOWED | MH.preserve_default_mvc_status(Allow 보존) | PASS |
| `/api/v1/alarms` | GET | Accept 협상 실패 | 406 | NOT_ACCEPTABLE | MH.preserve_not_acceptable(지원 media type header 보존) | PASS |
| `/api/v1/alarms` | POST | text/plain 요청 body | 415 | UNSUPPORTED_MEDIA_TYPE | MH.preserve_unsupported_media_type | PASS |
| `/auth/google`의 예외 주입 | POST | 명시적 일반 서비스 이용 불가 | 503 | SERVICE_UNAVAILABLE | MH.preserve_response_status_exception | PASS |
| `/auth/google`의 예외 주입 | POST | 기타 명시적 HTTP 상태 | 408/418/422/429/502/504 | HTTP_408 / HTTP_418 / HTTP_422 / HTTP_429 / HTTP_502 / HTTP_504 | MH.preserve_response_status_exception | PASS |
| Servlet test probe → `/error` | GET | sendError 404/500/503 | 원래 상태 | NOT_FOUND / INTERNAL_SERVER_ERROR / SERVICE_UNAVAILABLE | MH.preserve_servlet_error_dispatch | PASS |
| annotation/원인 annotation test probe | GET | @ResponseStatus | 404 | NOT_FOUND | MH.preserve_annotated_exception | PASS |
| header/return validation test probe | GET | 필수 Header 누락 / 반환값 Validation 실패 | 400 / 500 | INVALID_REQUEST / INTERNAL_SERVER_ERROR | MH.distinguish_binding_from_return_validation | PASS |
| Device/Boarding 실제 Controller | GET | local persistence Handler 우선 | 500 | INTERNAL_SERVER_ERROR | MH.preserve_local_persistence_handlers(local 로그, fallback 로그 부재) | PASS |
| Alarm/Auth 및 Domain Controller | GET/POST | Global Advice가 Domain/Auth/입력 Validation 처리 | 기존 400/401/403/404/409/503 | 기존 Domain/Auth code | MH.preserve_alarm_domain_error/preserve_device_domain_error/preserve_boarding_domain_error/preserve_transit_unavailable/preserve_authentication_contracts | PASS |
| 공개 Auth/보호 API → `/error` | POST/GET | ERROR 재진입에서 Security가 원래 상태를 덮지 않음 | 원래 4xx/5xx | 상태별 고정 code | ED.preserve_public_error_dispatch_status/keep_original_request_protected, MH.preserve_response_status_exception | PASS |

실행 순서는 **Controller-local Handler → Global Advice → Spring 상태/기본 Resolver → 마지막 500 fallback**으로 유지된다. Security 예외는 MVC fallback이 삼키지 않는다. 400 입력 검증과 500 반환값 검증, Domain 403/404/409와 generic HTTP_409, Google/Transit/일반 503을 구분했다. HTTP 오류 공통 helper는 `application/json`, 정확한 `{code,message}` key 집합, 고정 message 및 synthetic Token/SQL/경로/원인 marker 비노출을 검사한다. Allow·Accept·Bearer challenge와 기본 Security header도 기존 테스트로 확인했다. timestamp/path/status/traceId/requestId/stackTrace나 예외 정보를 추가하지 않았다. Alarm Domain 두 구체적 predecessor/successor message는 기존 계약대로 보존한다.

정상 계약도 함께 회귀했다. AU의 실제 Google fixture Login·Refresh Rotation/재사용 거부·다른 Session 보존·Logout/반복 Logout 204 무본문, AL의 Create 201·목록/상세 200·삭제 204·ACTIVE 멱등 및 FOLLOW_UP 전이, DE의 최초 등록 201/동일 intent 200·상태/disable 200, BS의 최초 생성 201/동일 UUID 200·조회/재확인/취소 200, RS/ST의 검색/정류장 200·검색 없음 빈 배열과 BC의 AVAILABLE/EMPTY/PARTIAL 200이 통과했다. JWT 정상 양수 Long 문자열/Long.MAX_VALUE 인증도 통과했다. 실제 Google parser와 공개키 I/O 분리는 `GoogleIdentityVerifierTest` 16개, JWT 발급/검증 방어는 `JwtConfigurationTest`·`JwtTokenServiceTest`로 함께 확인했다. Token Pair 원자성/로그인 경쟁·Refresh Session 책임을 재설계하지 않았다.

#### Flutter 오류·Mutation 복구와 Session/Race

아래는 전체 Flutter suite에서 실행한 기존 테스트다. 이번 단계에서 Flutter 제품·테스트는 변경하지 않고 3단계 최종 커밋 뒤 수정된 assertion/lint까지 포함한 전체 상태를 다시 실행했다.

| 경계 | 실제 검증 | 테스트 파일/근거 | 결과 |
| --- | --- | --- | --- |
| Create 정상/확정 거부 | 201만 성공, 400/401/403 및 stale target 404의 문서상 status/code 조합, unknown code/잘못된 status 보수 분류 | alarm_api_client_test, alarm_screen_test, bus_route_stop_screen_test | PASS |
| Create 불확실 결과 | 500/502/503/504/기타 5xx/408, Timeout·Transport·응답 유실, malformed 201; 성공·실패 확정 금지, 중복 탭/보관 callback·선택/옵션 변경·자동 POST/Activate 차단, 목록 안내 | alarm_api_client_timeout_test의 real HTTP failure/late create success, alarm_screen_test의 uncertainty 사례 | PASS |
| Create 뒤 Activate 실패 | 생성 ID·activationUnverified 전달, 원래 ID GET, 생성 당시 INACTIVE를 현재 상태로 단정하지 않음 | alarm_creation_push_test의 partial success, alarm_screen_test | PASS |
| Activate/Deactivate/FOLLOW_UP | 정상 전이, timeout/500/잘못된 성공 응답 후 원래 ID 1회 GET, GET 중/실패 뒤 Mutation 잠금, 명시 재조회, GET은 조회 시점 상태만 표시 | alarm_screen_test의 activate/deactivate/follow-up 및 wrong-ID recovery | PASS |
| Delete | 정상 204, timeout/5xx 뒤 원래 ID GET 200/404/실패, 자동 DELETE 없음, 404를 삭제 성공으로 단정/자동 이동하지 않음 | alarm_screen_test의 failed delete/delete recovery/GET failure | PASS |
| Session/Navigation/Dispose | logout·다른 User·같은 User 재로그인·Auth generation/login identity·navigation epoch, 다른 Alarm ID·Client 교체·dispose, 늦은 Mutation/GET 폐기와 후속 GET/POST/Navigation 없음 | alarm_creation_push_test의 detail phase별 stale 응답, alarm_screen_test의 replacing Alarm ID/disposed detail, notification_lifecycle_test | PASS |
| Timeout 이후 늦은 401 | Alarm 모든 operation의 뒤늦은 Refresh/Replay 차단, 하나의 15초 예산, 공유 Refresh의 다른 유효 요청 보존 | alarm_api_client_timeout_test, authenticated_api_client_test | PASS |
| Auth 상태/오류 | JSON 401과 503 구분, Refresh 401만 Pair 삭제, 5xx/timeout Pair 보존, single-flight·원자 저장·Session identity·Logout 직렬화, Auth POST 자동 재전송 없음 | backend_auth_client_test, auth_session_test, authenticated_api_client_test, token_pair_storage_test | PASS |
| Device | 정확한 credential/resource/revision code, unknown/빈/깨진 500도 pending 보존, 원래 payload/revision, 계정 전환/늦은 401 보호 | device_api_client_test, push_registration_service_test | PASS |
| BoardingSession | 원래 UUID/body/login identity 보존, GET 복구 우선, 5xx/timeout/파싱 불확실과 409 분리, 명시적 동일 요청 재시도·계정/epoch 보호 | boarding_session_client_test, boarding_session_controller_test 및 flow/screen 회귀 | PASS |
| Candidate/Route | Provider 503≠인증 401, code/HTTP 보존과 안전 fallback, Route GET의 status 기반 일반 오류·정상 응답 | boarding_candidate_client_test, bus_route_search_client_test, bus_route_stop_client_test | PASS |

#### 발견한 검증 공백과 최소 보강

TASK-804 제품 결함이나 새 설계 판단이 필요한 문서·구현 충돌은 발견하지 않았다. 제품 코드 수정은 **0개**다. 다음 기존 테스트 **4개 파일만** 보강했다.

- `MvcErrorHttpIntegrationTest`: HTTP_408 1개, local Handler 없는 TransactionException 500 1개, 공개 Auth 세 POST의 body 누락/깨진 JSON 6개, 유효 JWT 뒤 Alarm/Boarding User 소실 401 및 ERROR dispatch 1개를 추가했다. 총 73→82개, 신규 실행 case 9개다. 예외 주입은 HTTP 분류/우선순위 검증이며 실제 DB 장애라고 주장하지 않는다.
- `BusRouteStopIntegrationTest`: 기존 빈 traversal Service invariant 사례에 실제 Controller/DB 요청의 고정 두 필드 JSON 500을 추가했다. Unit/Service 예외 확인만으로 HTTP 계약이 증명된 것으로 보던 공백을 해소했다.
- `BoardingSessionApiIntegrationTest`, `BoardingCandidateApiIntegrationTest`: 기존 Domain 400/404/409/503 사례의 code assertion을 status·정확한 두 필드·Content-Type·고정 message까지 강화했다. 기존 시나리오/DB assertion·동시성/rollback 검증은 보존했다.

새 Dependency·DB Schema/Flyway·운영 설정·인증/Domain/API 정책·Provider mapping·Notification·Boarding Lifecycle 변경은 없다. 테스트 삭제/Skip/약화, 새 Task나 devlog 변경도 없다.

#### 최종 회귀 실행 결과

Java/Gradle JVM **21.0.12.1**, Wrapper **8.14.3**, 격리 DB **mysql:8.4.11 Testcontainers/Flyway**를 사용했다. 초기 Gradle 캐시·Docker socket의 sandbox 접근 제한은 허용된 권한 확장으로 해결했고 Dependency·운영 설정을 변경하지 않았다. 실제 개발/운영 DB·Google/Transit Provider/FCM/Firebase credential·iPhone은 사용하지 않았다. 외부 SDK/HTTP 검증은 Mock/실제 로컬 parser/실패 transport/loopback 서버로 제한했다.

전체 회귀 명령은 `backend`에서 `env -u JAVA_HOME ./gradlew test --offline --max-workers=1`, `app`에서 `flutter test --no-pub --reporter expanded`와 `flutter analyze --no-pub`를 각각 실행했다. 집중 테스트는 같은 Gradle 명령에 변경한 네 클래스의 `--tests`만 지정했다.

| 실행 | 클래스/파일 | 실행 / 통과 | 실패 / 오류 / Skip | 결과 |
| --- | ---: | ---: | --- | --- |
| 1차 Backend 집중 | 4 classes | 219 / 219 | 0 / 0 / 0 | BUILD SUCCESSFUL(58초) |
| 최종 Backend 집중 | 4 classes | 226 / 226 | 0 / 0 / 0 | BUILD SUCCESSFUL(50초) |
| 최종 Backend 전체 | 81 classes | 1,407 / 1,407 | 0 / 0 / 0 | BUILD SUCCESSFUL(6분 45초) |
| 최종 Flutter 전체 | 34 test files | 637 / 637 | 0 / 0 / 0 | All tests passed! |
| Flutter Analyze | app | 1 / 1 | 0 / 0 / 0 | No issues found! |
| 3단계 변경 Dart Format 재확인 | 9 files | 9 / 9 | 0 / 0 / 0 | 0 changed |

집중 테스트 후 전체 실행을 시작했다가 Matrix 대조에서 Auth body/인증 후 User 소실 경계를 추가로 확인해 그 실행을 중단했다(exit 130). 해당 부분 실행은 최종 성공 수로 합산하지 않았다. 보강 뒤 최종 집중→최종 전체를 다시 실행했다. 최종 Backend JUnit XML의 suite 집계와 testcase 수가 일치하며 **기존 1,398 + 신규 9 = 1,407개**다. 실행 단계 간 중복은 합산하지 않는다. Flutter는 직전 3단계 최종 커밋과 이번 최종 상태가 동일하며 이번에는 전체 637개와 Analyze를 모두 실행했다. 이번 Dart 변경은 없지만 직전 단계 9개 파일도 `dart format --output=none --set-exit-if-changed`로 확인했다.

최종 실행 완료 시각(KST)은 Backend **2026-10-31 12:25:22 KST**, Flutter **2026-10-31 12:13:31 KST**, Analyze **2026-10-31 12:14:00 KST**다. 최종 전체 회귀 뒤 제품 동작/테스트 의미에 영향을 주는 코드를 변경하지 않았으며 이후 변경은 이 기록·완료 표시 및 API 문서의 최소 동기화뿐이다. 전체 suite가 최종 코드 상태와 일치한다. 검증 중 실제 제품/test 실패를 숨기거나 assertion을 약화하지 않았다.

실행 로그는 `/private/tmp/stopbell-task804-step4-{focused,focused-final,backend-full,flutter-full,flutter-analyze}.log`, Backend JUnit XML/집계는 `/private/tmp/stopbell-task804-step4-{focused,focused-final,full}-results/`에 보존한다. 종료 시 기존 개발 MySQL Container `978144708abb`만 그대로 남고 이번 격리 Testcontainers는 정리됐음을 확인했다. 기존 Container/Volume은 변경하지 않았다.

#### TASK-802 책임과 남은 한계

TASK-804에서 오류 HTTP/code/shape·기존 정책 보존, 화면 내 중복 자동 Mutation 차단, 결과 불확실 표시, 원래 Alarm ID GET 복구, Session/늦은 응답 보호를 검증했다. `POST /api/v1/alarms`에는 Request Identity 기반 서버 멱등성이 없고 목록의 유사 Alarm을 이전 요청 결과로 연결하지 않는다. 목록 조회 성공이나 GET 404는 특정 Mutation의 commit/rollback 증거가 아니다. 화면 메모리 잠금은 앱 재진입/재시작/복수 기기·동시 생성의 정확한 중복 방지 보장이 아니다.

TASK-802 `[ ]`에는 기존 Evaluation·activation generation/tracking cycle·Lifecycle 경쟁·logical Event/recipient Delivery dedup 검증을 유지한다. Alarm 생성의 User별 Client Request ID·원본 fingerprint·동일 요청 식별/기존 Alarm 반환·다른 내용 conflict·응답 유실 조회·순차/동시 요청·commit/rollback·User 격리 설계 채택 여부와 검증도 후속 서버 책임으로 남긴다. 이번에 멱등성 계약을 채택/구현하거나 TASK-802를 완료하지 않았다. 서버 멱등성 부재는 TASK-804 완료 차단 사유가 아니다.

이미 commit된 HTTP 응답/끊긴 연결에 새 JSON 전달, 실제 DB 네트워크 단절·프로세스 restart, 실제 Google/Transit/FCM·iPhone/OS 동작은 보장·검증 범위 밖이다. Actuator DOWN 503은 health body 계약이며 Application Domain 오류와 합치지 않고 TASK-807에 남긴다. RouteSearch/Stop의 읽기 전용 wrapper는 같은 login에서 Timeout 뒤 늦은 401의 Refresh/GET replay를 별도 취소하지 않는 기존 분석 한계를 유지한다. Auth generation이 바뀐 이전 Session 응답은 공통 Client가 폐기하며, 이 한계를 Alarm Mutation의 Timeout/중복 전송 보호까지 일반화하지 않는다. 인증 전체 read 요청 수명 확대는 이번에 선행 설계하지 않았다.

#### 완료 조건 평가와 문서/Git 범위

실제 20개 mapping 및 30개 enum을 식별하고 Endpoint별 도달 경로와 공통 경계를 대조했다. 400 Validation, Security/Auth 401·403, Domain 404·409, Google/Transit/일반 503, 안전한 MVC 500, 상태/Header/ERROR dispatch와 비노출, 정상 응답, Flutter Create 불확실성/원래 ID 조회/중복 Mutation 차단, Session/Race·기존 인증/Domain 정책과 TASK-802 책임을 확인했다. Backend 전체·Flutter 전체·Analyze·Format이 통과했으며 TASK-804 범위의 미해결 차단 결함은 없다.

**TASK-804: `[x]` — 최종 완료 조건 전부 충족.** Owner 문서/API·Architecture·관련 요구사항/Domain/ADR과 현재 구현의 정합성을 확인했다. `api.md`의 후속 단계 문장을 이 최종 결과 링크로 갱신하고, BUS_ROUTE_NOT_FOUND가 Alarm 설정의 Route 정류장 조회 단계에 해당함을 명확히 했다. occurrence ID 기반 생성의 TARGET_STOP_OCCURRENCE_NOT_FOUND와 기존 status/code는 그대로이며 설계 변경은 없다. 이미 정확한 Architecture와 다른 Owner는 수정하지 않았다. task-list의 폐기된 METADATA_UNAVAILABLE 후보와 TASK-804 체크만 최소 동기화했다. 기존 분석/1·2·3단계·다른 완료 기록과 TASK-802 등 다른 체크박스는 유지한다.

Commit은 보강한 Java 테스트 4개와 문서 2개만 명시적으로 Stage한다. 사용자 지정 subject `test: TASK-804 complete API error contract verification` 및 실제 개행 한국어 bullet 3줄로 작성하고, Commit 전 status/diff/diff --check·staged diff와 Commit 후 `git log -1 --pretty=full` 형식을 확인한다. `main`에서 정상 Push하고 원격 main과 HEAD 일치를 확인해 최종 보고하며 다른 Task는 시작하지 않는다.

### 기존 Phase 8 Task 상세 및 실행 dependency

TASK-805는 structured log, 최소 operational metric, alerting contract를 함께 다룬다. metric은 Provider request outcome/latency, Observation staleness/UNKNOWN reason, ACTIVE/FOLLOW_UP Alarm 수, scheduler cycle duration·last completion·overlap, Notification pending 수와 oldest pending delivery age, no-recipient Event, delivery accepted/failure/retry/expired, metadata 마지막 complete sync 성공 age를 후보로 한다. `alarmId`, `deviceId`, `routeId`, `trackingCycleId`, `installationId`는 metric label에 넣지 않는다. Actuator/Micrometer 수준을 기본으로 하고 자체 Prometheus/Grafana stack을 V1 요구로 만들지 않으며 export와 alert destination은 deployment platform에서 정한다. log는 문맥에 필요한 correlation(`requestId`, `operationId`, provider, schedulerCycleId, alarmId, activation generation, trackingCycleId, notificationEventId, deliveryId, elapsedMs, outcome)만 기록한다. Access/Refresh Token, Google ID Token, Firebase credential, 원문 push targeting identifier·installationId, API key가 든 URL/query, 필요 이상의 GPS, raw Provider response 전체는 로그에 남기지 않는다.

TASK-807은 liveness(JVM/process 생존), readiness(DB 연결, Flyway 적용 완료, 필요한 component 초기화, scheduler/outbox worker 실행 가능), business/dependency health(Provider 최근 실패, scheduler last completion, outbox backlog, metadata 마지막 sync age)를 구분한다. TAGO/서울/FCM의 일시적 원격 장애만으로 Backend readiness를 DOWN으로 만들지 않으며, 상세 dependency 정보는 public health response에 과도하게 노출하지 않는다. fresh DB 최초 배포에서는 필요한 metadata bootstrap이 끝나기 전 public Route/Alarm 생성 traffic을 받지 않는 절차가 필요하지만, 정상 운영 중 metadata가 일시적으로 오래됐다는 이유만으로 기존 Alarm monitoring을 unready로 만들지 않는다.

TASK-808은 production topology 전체가 아니라 Java 21 기반의 재현 가능한 Backend runtime image를 만든다. Gradle Wrapper build 또는 CI-built bootJar, non-root 실행, SIGTERM 전달, UTC runtime timezone, stdout/stderr logging, runtime environment/secret injection, Firebase credential read-only mount 또는 platform identity, health/readiness integration과 graceful shutdown budget을 검토한다. image size 최적화는 correctness보다 우선하지 않고 development `docker-compose.yml`을 production topology로 자동 채택하지 않는다.

TASK-809의 최소 CI는 지금부터 시작할 수 있다. Backend는 Java 21, Gradle Wrapper, build/test, Testcontainers/MySQL/Flyway 검증을, Flutter는 고정 Flutter version, `flutter analyze`, test가 생긴 뒤 `flutter test`를 포함한다. Phase 5~7 구현이 추가될 때 해당 correctness test를 계속 포함한다. 실제 TAGO/서울 Provider 호출, production Firebase credential, 실제 FCM 전송, signed iOS archive는 일반 PR CI에 넣지 않는다. TASK-808 완료 뒤 Docker image build/smoke를 추가할 수 있으며 GitHub Actions는 후보이나 workflow 세부는 이 Task에서 확정한다.

TASK-810은 `pollStartedAt`, provider data time(제공되는 경우), `observedAt`, `eventDetectedAt`, `notificationEventCreatedAt`, `deliveryAttemptAt`, `providerAcceptedAt`으로 Provider freshness/request, evaluation, outbox queue, FCM request, Backend observable end-to-end latency를 분리해 측정한다. Provider가 실제 버스 Event 시각을 주지 않으면 물리 Event부터 Backend까지 지연은 알 수 없고, FCM accepted도 iPhone 표시 시각이 아니다. foreground/background/terminated별 실제 Device 반복 측정으로 체감 지연을 보완한다.

TASK-811은 ACTIVE tracking, ONE_STOP_BEFORE 뒤, target 통과 직전, FOLLOW_UP, lifecycle과 NotificationEvent commit 직후, pending delivery 전, FCM accepted와 DB result update 사이, retry wait, metadata sync 경계에서 restart를 검증한다. ACTIVE tracking의 memory loss는 허용하고 safe rebaseline을 사용하며 restart 전후 Observation을 연결해 PASSED를 추론하지 않는다. FOLLOW_UP은 persisted runtime으로, NotificationEvent는 durable outbox로 복구한다. ambiguous FCM acceptance에는 duplicate 가능성을 인정한다. SIGTERM/deployment 때 새 polling·dispatch cycle을 시작하지 않고 진행 중 transaction은 commit/rollback하며 Provider/FCM timeout은 shutdown budget보다 짧게 제한한다. PENDING Delivery는 restart 뒤 단일 worker가 다시 처리하고 incomplete metadata sync는 provider cleanup 근거가 될 수 없다. 이 검증에서 실제 품질 문제가 입증되기 전에는 ACTIVE tracking persistence나 event sourcing을 추가하지 않는다.

TASK-812는 debugging을 위한 structured log, notification quality를 위한 operational metrics/NotificationDelivery, product usage를 위한 analytics, 장기 business metric을 구분한다. 실제 제품 질문과 보존 근거가 있을 때만 Analytics를 최소 구현하며 별도 persistence가 필요 없다는 결론도 정상 완료다. Phase 7의 NotificationEvent/NotificationDelivery는 correctness와 delivery operation 데이터이며 장기 Analytics Source of Truth가 아니다. TASK-812는 public V1 release blocker가 아니다.

TASK-813은 단일 persistent Backend runtime, durable MySQL, HTTPS/domain 또는 동등한 secure public endpoint, runtime secret injection, restart policy, deployment smoke, rollback procedure, DB backup/restore, Flyway migration policy를 준비한다. 특정 vendor/product는 정하지 않는다. 첫 production 적용 뒤에는 적용된 Flyway migration file을 수정하지 않고 새 migration을 추가한다. single-instance V1은 application startup migration을 유지할 수 있으나, 배포 전 migration 영향과 backup/restore point를 확인하고 migration 실패 instance는 ready가 되어서는 안 된다. DB를 임의 downgrade하지 않으며 application rollback은 새 Schema와의 compatibility를 확인한 경우만 한다. 공개 사용자 데이터를 받기 전 자동 DB backup 또는 platform snapshot, 가능하면 PITR, deploy 전 restore point, 실제 restore drill 최소 1회를 검증한다. Restore 뒤에는 outbound polling/notification dispatch를 quarantine하고 Flyway/schema 검증 → RefreshToken 복구 정책 → Device ownership/registration 정책 → metadata 재동기화/검증 → Alarm recovery baseline → stale pending Notification expiry/cutoff → worker/scheduler 재개 → public readiness 순서로 복구한다. Restore epoch나 별도 recovery subsystem은 추가하지 않는다. Alarm/User/BusAlarmTarget은 복구 중요도가 높고 Transit metadata는 source에서 재생성할 수 있으며 pending notification은 freshness policy를 고려한다. 오래된 backup 복원 뒤 RefreshToken session revoke/re-login 정책은 이 Task에서 결정한다.

TASK-814는 persisted operational time을 UTC 의미로 통일한다. `createdAt`/`updatedAt`, RefreshToken expiry, FOLLOW_UP start/expiry, Provider data time normalization, scheduler time, Notification retry/expiry, latency instrumentation 및 Analytics event time(도입 시)이 대상이다. TASK-504/510/707/709 등 그 전에 추가되는 timestamp도 처음부터 UTC 의미, test 가능한 `Clock` 또는 동등한 time source, 명시적인 Provider timezone parsing을 적용한다. TASK-814는 기존 UTC `LocalDateTime`/MySQL `DATETIME(6)`을 유지하는 A안으로 callback의 Host 의존성을 제거하고 기존 UTC 경로를 보존했다. 이후에도 host-local `now()`를 추가하지 않으며 [구현·검증 결과와 기존 데이터 정책](#task-814-utc-구현-및-검증-결과-2026-10-30)을 따른다.

TASK-816 / [AUD-12 처리 기록](#aud-12-처리-2026-10-30)은 기존 callback 5개 컬럼의 Host 의존성, 별도 UTC 경로, MySQL 실측 및 기존 데이터 위험을 TASK-814의 근거로 연결한다. 감사 재현 완료는 UTC 결함 수정이나 TASK-814 완료를 의미하지 않는다.

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

### TASK-814 UTC 계약 및 기존 데이터 영향 분석 (2026-10-30)

**분석 전용 결과: A안(기존 UTC LocalDateTime / DATETIME(6) 유지, Host callback 5곳만 명시적 UTC 생성으로 변경)을 추천한다.** 이번 기록은 구현 승인이나 제품 수정 완료가 아니다. TASK-814는 `[ ]`, TASK-816은 현재 `[x]` 및 기존 감사 기록 그대로 유지한다. 이번 변경은 이 분석 절 추가뿐이며 제품·테스트·Migration·Dependency·Application/Docker 설정·devlog는 수정하지 않았다. 테스트 실행, 실제 개발 DB 조회/변경, Git add/commit/push도 수행하지 않았다.

#### 원인과 Timestamp 경계별 분석

근거는 현재 코드·테스트·Flyway V1~V20, [Database의 UTC 저장 경계](database.md#utc-목표와-현재-저장-경계-task-816--aud-12), Architecture, Local Development, Development Guidelines와 [AUD-12의 기존 MySQL 실측](#aud-12-처리-2026-10-30)이다. AUD-12의 세 독립 JVM·총 12회 통과는 **Host 의존 결함 재현 성공**이며 이번에 실행한 결과나 수정 후 UTC 보장의 증거가 아니다. 현재 저장소 기준 Java 21 / Boot 4.1.1 / Hibernate 7.4.5.Final / Connector/J 9.7.0이며, 기존 실측은 MySQL 8.4.11이다. Compose는 `mysql:8.4` 태그이므로 실제 개발 DB의 patch version까지 새로 확인한 것은 아니다.

아래 코드 위치는 `backend/src/main/java/com/stopbell/` 기준이다. YES는 다음 구현의 수정 대상, NO는 현재 제품 생성·소비 경로를 보존할 대상이다. NO는 과거 데이터나 임의 생성자 입력까지 UTC임을 입증한다는 뜻이 아니다. 영속 시각의 physical type은 모두 `DATETIME(6)`이며, 비영속 경계는 별도 표시한다.

| Entity/Service·코드 위치 | Timestamp / DB 컬럼 | Java Type | DB Type | 시간 생성 원천 | 현재 UTC 의미 | 사용 목적 | 수정 필요 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `user/entity/User.java:46` onPrePersist / `:53` onPreUpdate | createdAt, updatedAt / users.created_at, updated_at | LocalDateTime | DATETIME(6) | 인자 없는 now(), JVM Host | 미보장 | 생성·수정 감사. 공개 User 수정 경로는 현재 없음 | YES: 2곳 |
| `user/entity/RefreshToken.java:47` onPrePersist | createdAt / refresh_tokens.created_at | LocalDateTime | DATETIME(6) | 인자 없는 now(), JVM Host | 미보장 | 발급 감사. 만료 비교에 사용하지 않음 | YES: 1곳 |
| `alarm/entity/Alarm.java:89` onPrePersist / `:97` onPreUpdate | createdAt, updatedAt / alarms.created_at, updated_at | LocalDateTime | DATETIME(6) | 인자 없는 now(), JVM Host | 미보장 | 생성·상태 변경 감사, Repository 목록 createdAt DESC | YES: 2곳 |
| `user/auth/service/RefreshTokenService.java` issue / refresh | expiresAt / refresh_tokens.expires_at | LocalDateTime | DATETIME(6) | jwtClock.instant() → ofInstant(UTC) +30일 | 보장 | expiresAt > UTC now일 때만 Rotation, 경계에서 삭제·거부 | NO |
| `alarm/service/BusAlarmLifecycleService.java` applyArrival / `BusAlarmEvaluator.java` | followUpStartedAt, followUpExpiresAt / alarms.follow_up_started_at, follow_up_expires_at | LocalDateTime ↔ Instant | DATETIME(6) | Event observedAt → UTC, +5분, MICROS 절삭; 읽을 때 toInstant(UTC) | 보장 | FOLLOW_UP 시작·만료·재조회 복구 | NO |
| `notification/entity/Device.java` callback / `notification/service/DeviceService.java` utcNow | createdAt, updatedAt, lastRegisteredAt / devices.created_at, updated_at, last_registered_at | LocalDateTime | DATETIME(6) | callback now(UTC); 등록은 jwtClock.instant() → UTC | 보장 | 등록·소유권·상태 감사 | NO: Clock 주입 차이는 아래 검토 |
| `boarding/entity/BoardingSession.java`, `BoardingCheckpoint.java`; `boarding/service/BoardingSessionService.java`, `BoardingTrackingStore.java`, `BoardingSessionStartValidator.java`, `BoardingProgressEvaluator.java` | createdAt, updatedAt, expiresAt, endedAt, checkpoint observedAt/providerDataTime / boarding_sessions의 대응 6컬럼 | LocalDateTime | DATETIME(6) | transitMetadataClock.instant() 또는 차량 관측 Instant → UTC, Entity MICROS 절삭 | 보장 | 최초 수명 고정, checkpoint 순서·freshness·재확인·종료 | NO |
| `notification/entity/NotificationEvent.java`; BusAlarmLifecycleService / `boarding/service/BoardingNotificationService.java` | observedAt, providerDataTime, eventDetectedAt, createdAt, expiresAt / notification_events의 대응 5컬럼 | LocalDateTime | DATETIME(6) | Event Instant → UTC 또는 UTC checkpoint, Service Clock → UTC; MICROS 절삭 | 보장 | 관측≤검출≤생성, Session 불변 기한. ALARM expires_at은 NULL | NO |
| `notification/entity/NotificationDelivery.java`; `notification/service/NotificationDispatchAttemptService.java`, `NotificationDispatchWorker.java`, `DurableNotificationAttemptHandler.java`, `NotificationRetryPolicy.java` | createdAt, updatedAt, lastAttemptAt, providerAcceptedAt, nextAttemptAt, expiresAt / notification_deliveries의 대응 6컬럼 | LocalDateTime | DATETIME(6) | Service Clock → UTC, FCM result Instant → UTC, Event TTL; Entity MICROS 절삭, retry는 하한 보존을 위해 올림 | 보장 | due·expiry·attempt/recovery/retry·accepted, nextAttemptAt/id 정렬 | NO |
| `alarm/service/AlarmService.java:138` delete | Delivery updatedAt | LocalDateTime | DATETIME(6) | now(ZoneOffset.UTC) → terminateDispatch | 보장 | Alarm 삭제 시 PENDING 종료 | NO: 직접 system time은 UTC이며 기한 정책 변경 불필요 |
| `transit/entity/BusMetadataSyncState.java`; `transit/service/BusMetadataSyncService.java` | lastCompleteSyncAt / bus_metadata_sync_states.last_complete_sync_at | Instant | DATETIME(6) | Instant.now(transitMetadataClock), complete sync 성공 후 기록 | 보장, 기존 JPA/MySQL 실측 | 마지막 완전 동기화 시각 | NO |
| `transit/mapper/SeoulBusTransitObservationMapper.java`, `TagoTransitObservationMapper.java`; `transit/service/VehiclePollingCycle.java`, `BoardingCandidateService.java` | observedAt / 서울 providerDataTime(dataTm) | Instant | 비영속, Event/checkpoint에 저장 시 UTC 변환 | 수신 Clock.instant(); 서울 STRICT parse + Asia/Seoul → Instant; TAGO 원본 시각 없음 | 보장, TAGO NULL 유지 | freshness·미래/동일/역행 시각 차단, 수신 시각과 Provider 시각 분리 | NO |
| `alarm/service/BusAlarmMonitoringScheduler.java`; `transit/service/BoardingCandidateReferenceStore.java` | scheduler now, 실패 재시도 기한, 후보 reference 기한 | Instant | 비영속 | transitMetadataClock.instant(), Instant 산술 | 보장 | polling·실패 재시도·후보 TTL, FOLLOW_UP UTC 복원 | NO |
| `user/auth/service/JwtTokenService.java`, `user/auth/config/JwtConfiguration.java` | JWT issuedAt / expiresAt 및 validator now | Instant | JWT claim, DB 컬럼 아님 | jwtClock.instant(), Clock.systemUTC() bean | 보장 | Access Token 발급·검증 | NO |
| `notification/client/FcmProviderClient.java`, `FcmFailureMapper.java`, `FcmSmokeRunner.java` | receivedAt, Retry-After, smoke expiry | Instant / Duration | 직접 DB 컬럼 없음, 결과는 Delivery에 UTC 변환 | Clock.instant(), RFC 1123의 명시 offset, smoke Instant.now() +60초 | 보장 | FCM 결과 수신·최소 retry delay·smoke 기한 | NO |
| FcmProviderClient / FcmAttempt / NotificationDispatchWorker / `transit/service/BoardingCandidateDetailExecutor.java` | elapsed/request budget/deadline | long / Duration | 비영속 | System.nanoTime(), 주입 가능한 LongSupplier | timezone과 무관한 경과 시간 | latency·request budget. UTC wall-clock으로 바꾸지 않음 | NO |
| BusAlarmTarget / BusRoute / BusStop / BusRouteStopOccurrence | 없음 | 해당 없음 | Timestamp 컬럼 없음 | 자체 now/callback 없음 | 대상 아님 | target/metadata snapshot | NO |
| legacy notification_history (V3, V14) | created_at | 현재 Entity 없음 | DATETIME(6) | 과거 writer/Host 이력 불명 | 확인 필요 | 데이터가 남은 경우만 보존, 현 Event/Delivery에서 사용하지 않음 | 확인 필요: 임의 변환 금지 |

Host 결함은 **5개 호출·5개 컬럼**이다. 같은 기준 `2026-10-30T00:00:00Z`에서 기존 callback은 UTC JVM `00:00`, Seoul `09:00`, LA `전날 17:00`을 저장했다(AUD-12). LA offset은 이 날짜에 -07:00이며 겨울까지 고정되는 값이 아니다. User/Alarm 감사 시각과 RefreshToken 생성 시각의 의미가 달라지고, 서로 다른 Host 또는 DST 전환·수정 전후 데이터 혼합 시 Alarm 목록 순서가 실제 생성 순서와 어긋날 수 있다. 수정 후 과거 Seoul updatedAt보다 신규 UTC updatedAt이 작아지는 경우도 가능하다. 현재 만료·활성화·FOLLOW_UP·Notification·BoardingSession은 이 감사 컬럼을 기한 근거로 사용하지 않아, 그 업무 정책을 함께 변경할 이유는 확인되지 않았다. 실제 운영 장애나 목록 역전은 실측하지 않았다.

#### JDBC/JPA·SQL 생성 및 정밀도 경계

- Entity의 LocalDateTime은 offset을 갖지 않으며 기본 JPA 매핑을 사용한다. 제품의 별도 시간 AttributeConverter / 직접 setTimestamp·getTimestamp 경로는 발견하지 않았다(BoardingSession의 JdbcTypeCode는 JSON snapshot용). 현재 설정에는 hibernate.jdbc.time_zone / connectionTimeZone / forceConnectionTimeZoneToSession 고정이 없다. 이번 A안에서 이 옵션을 추가하거나 JVM/DB UTC 설정만으로 callback 결함을 대신 해결하지 않는다.
- AUD-12에서 JPA persist→flush→clear→find와 JDBC getObject(..., LocalDateTime.class)는 DATETIME 날짜·시각을 그대로 유지했다. 동일 literal의 별도 JVM 읽기와 Session offset +00:00/+09:00/-07:00에서도 같은 값이었다. metadata Instant는 현재 Hibernate UTC binding/extraction으로 동일 Instant를 복원했다. 잘못 생성된 Host-local LocalDateTime을 이 경계가 자동 UTC 보정해 주지는 않았다.
- 기본 Connector의 getTimestamp(DATETIME).toInstant()는 JVM zone에 따른 다른 해석을 보였다. 이는 typed LocalDateTime 읽기와 다른 경로다. TIMESTAMP 및 FROM_UNIXTIME() 비교 실험도 Session offset에 영향을 받았다. 현재 operational schema를 TIMESTAMP로 전환하지 않으며, 이 비교용 테이블을 제품 테이블로 해석하지 않는다.
- V1~V20에는 operational timestamp를 생성하는 NOW()/CURRENT_TIMESTAMP/UTC_TIMESTAMP 등의 함수, timestamp DEFAULT/ON UPDATE가 없다. 현재 Repository의 시각 조건은 Service에서 받은 :now와 비교하며 DB 현재 시각으로 교체하지 않는다. notification_history 보존 분기, V19 상태/revision 전환과 V20 옵션 추가도 기존 시각을 변환하지 않는다. Flyway 자체 history는 제품 operational time 계약과 별개다.
- DATETIME(6)은 microsecond, Java 시간 값은 nanosecond를 표현할 수 있다. FOLLOW_UP/Event/Delivery/Session은 기존 절삭 또는 retry 하한 올림을 보존한다. User/RefreshToken/Alarm callback·Device·Refresh expiry에는 같은 명시적 절삭이 일괄 적용돼 있지 않다. A안의 최소 제품 변경은 UTC 인자 추가이며 전역 precision 정책 변경은 포함하지 않는다. sub-microsecond binding의 실제 반올림/절삭·초/날짜 carry는 기존 AUD-12 정수초 실측만으로 확정할 수 없으므로 아래 후속 검증으로 남긴다.

#### A/B/C 비교와 추천

| 방안 | 변경 범위 | 장점 | 위험·비용 | 추천 |
| --- | --- | --- | --- | --- |
| A. UTC LocalDateTime 유지 | User·RefreshToken·Alarm의 now() 5곳을 now(ZoneOffset.UTC)로 변경, import 및 직접 관련 테스트 | Java type·DATETIME(6)·생성자·API·Query 유지, 기존 Device UTC callback과 같은 방식, Schema Migration 불필요 | callback은 실시간 system source이고 주입 business Clock과 동일한 인스턴스는 아님. 테스트의 factory Clock 고정 필요. 과거 값 의미는 복구하지 않음 | **추천: 현재 결함에 대한 최소 위험안** |
| B. Clock 기반 UTC 생성 | 위 필드를 유지하되 callback에 Clock을 전달할 EntityListener/주입 경계 또는 Service 시각 전달 설계·테스트 추가 | Clock.fixed로 결정적 테스트, 업무 시각 원천과 연결 가능 | Entity는 일반 Spring bean이 아니므로 단순 @Autowired만으로 해결됐다고 할 수 없음. Listener DI 및 모든 JPA 생성/갱신 경로 검증 필요; Service 전달은 dirty checking 경로 누락 위험. static mutable Clock/Service locator/BaseEntity는 불필요한 구조 증가 | 이번에는 미추천 |
| C. Instant 중심 변경 | 적어도 대상 Entity 필드/생성자/getter·Service 경계·Repository 비교 인자·테스트 변경; 전면 전환은 Notification/Session/DTO까지 확대 | 특정 순간을 타입으로 표현, UTC offset 해석 누락 감소 | Instant↔DATETIME mapping·정밀도·API 호환성 검증 필요. metadata 사례만으로 모든 mapping을 일반화할 수 없음. physical Schema 변경이 반드시 필요한 것은 아니지만 데이터 의미 이관 검토는 남고, 과거 timezone은 타입만 바꿔도 복원되지 않음 | 이번에는 미추천 |

A안은 callback의 **UTC 의미와 생성 방식을 통일**하며 모든 도메인이 하나의 주입 Clock 인스턴스를 공유하게 만드는 안은 아니다. 기존 jwtClock/transitMetadataClock은 systemUTC bean과 fixed Clock 테스트 구조를 그대로 유지한다. Device callback과 Alarm 삭제는 이미 명시 UTC이고 test 가능한 business time 우선 규칙에 반하는 기한 계산 결함도 확인되지 않아 변경하지 않는다. callback 테스트에는 기존 Mockito scoped static Clock 고정을 사용하면 새 제품 추상화 없이 결정적으로 검증할 수 있다. 공통 Clock 주입이 별도 필수 요구가 되면 B안을 명시적으로 다시 결정하며, 이번 분석으로 EntityListener/새 Framework를 도입하지 않는다.

#### 기존 데이터 호환성·Migration 판단

1. **자동 변환 불가:** DATETIME 자체에는 원래 zone/offset/Instant가 없다. 현재 저장소 설정은 과거 배포 JVM/JDBC/Session zone의 증거가 아니며, 같은 컬럼 안에 UTC·여러 Host-local 값이 섞였을 수 있다. 특히 LA DST 중첩의 같은 local time은 zone을 알아도 offset 근거 없이 하나의 Instant로 정할 수 없다. 생성·수정 각각의 writer 시점도 다를 수 있다. 기존 개발/운영 DB와 배포 이력은 이번에 조회하지 않았다.
2. **수정 코드에 필수인 Schema/데이터 Migration은 없음:** A안은 타입·컬럼·읽기 해석·업무 기한을 유지하므로 신규 UTC 생성부터 바로 적용할 수 있다. 이는 기존 감사 값까지 정상 UTC라는 결론이 아니다. 이미 UTC인 expires_at/FOLLOW_UP/Session/Event/Delivery/metadata는 그대로 보존해야 한다. RefreshToken 만료값·기존 Token의 유효성/Rotation/Logout·Session 최초 수명을 임의 변경하거나 Token/User/Alarm을 재생성할 근거는 없다.
3. **과거 감사 값 보존 시 잔여 위험:** Alarm createdAt DESC 정렬과 감사 시각의 시계열 비교는 수정 전후 혼합으로 왜곡될 수 있다. 기존 createdAt은 보존하고 새 updatedAt만 UTC로 갱신되면 updatedAt < createdAt도 가능하다. 현재 대상 Entity/Schema에는 이 순서를 강제하는 제약이 없고 업무 만료도 여기에 의존하지 않지만, 그 사실만으로 사용자 목록/감사 의미 문제가 해소되지는 않는다. A안은 신규 값의 Host 의존성만 제거한다. 실제 과거 데이터가 없거나 UTC로만 생성됐다는 근거가 있으면 데이터 변환은 불필요하다. 근거가 없으면 원문을 보존하며 과거 시각의 불확실성과 정렬 영향 수용 여부를 운영 적용 전에 개발자가 판단해야 한다. 임시 ID 정렬이나 표시/API 변경을 이번에 끼워 넣지 않는다.
4. **조건부 보정 가능 범위:** 별도 승인된 조사에서 컬럼·row 집합·writer 배포 기간별 원천, 당시 JVM/JDBC/DB 설정 및 신뢰 가능한 대응 Instant를 확보하고 DST까지 유일하게 복원되는 row에만 고려한다. ID 범위나 현재 UTC와의 차이만으로 원천을 추측하지 않는다. 이미 UTC인 컬럼·불명확한 row·legacy History는 대상에서 제외한다. 전체 ±9시간, 기존 적용 Flyway 수정, Refresh expiry 변환은 금지한다. 필요성이 입증된 경우에만 후속 새 Migration 또는 승인된 데이터 이관 절차를 별도 설계한다.
5. **안전한 적용 조건 제안:** 과거 시각 불확실성/정렬 영향을 확인·수용하거나 증거 있는 보정 범위를 먼저 승인하고, 구 writer와 UTC writer가 동시에 기록하지 않는 단일 instance 교체 구간을 잡는다. 데이터 보정이 필요하면 원본 값·대상 PK·배포 cutoff를 보존한 백업/restore point와 격리 Restore 검증을 먼저 준비하고, 중복 보정 방지·정확한 대상 수·전후 원본/기한 비교·복구 절차를 명시한다. 실패 시 승인된 원본 복원으로 복구하며 무조건 역으로 9시간 가감하지 않는다. 보정이 없는 코드 배포도 구 버전 rollback은 Host-local writer를 다시 도입할 수 있고 이미 저장한 UTC row를 되돌리지 않으므로 rollback 가능 조건을 사전에 확인해야 한다. 이 절은 운영 절차 제안이며 실제 배포·Backup/Restore 실행이나 TASK-813 구현이 아니다.

#### Characterization 변경과 후속 테스트 계획

`backend/src/test/java/com/stopbell/common/UtcTimestampCharacterizationIntegrationTest.java`의 현재 4개 테스트는 다음처럼 다룬다. 이번에는 수정·실행하지 않았다.

1. `characterize_host_local_callbacks`: Host zone 기대값을 기준 Instant의 **UTC LocalDateTime**으로 바꾸고 세 JVM 모두 User/Alarm 생성·최초 updatedAt·RefreshToken createdAt 및 +60초 dirty update의 JPA/raw 값을 검증한다. createdAt은 갱신되지 않고 생성 시각과 최초 수정 시각은 동일해야 한다. User의 공개 수정 API는 만들지 않고 기존 dirty-field 테스트 방식을 유지한다.
2. `characterize_refresh_expiry_and_rotation`: createdAt의 Host 기대값만 UTC로 변경한다. +30일 만료, 만료 1초 전 Rotation·새 30일 기한, 원래 Hash 재사용 거부, 정확한 경계에서 거부·삭제, 만료 Token Logout 삭제를 보존한다. microsecond 경계 직전/동일/직후와 non-UTC zone의 fixed business Clock도 추가해 callback 수정이 기존 만료 정책을 건드리지 않음을 확인한다.
3. `characterize_follow_up_utc_and_reload`: Alarm createdAt 기대값을 UTC로 변경하고 applyArrival의 observedAt 기준 UTC start/+5분, raw/JPA 재조회와 +299초 유지/+300초 만료를 보존한다. scheduler/검출 시각과 관측 시각이 다른 fixture, 기한 1 microsecond 전/동일/후를 포함한다. private applyArrival 호출 테스트의 범위를 전체 lifecycle commit/restart E2E로 과장하지 않고 기존 Lifecycle/Decision 통합 테스트로 보완한다.
4. `characterize_mysql_datetime_and_instant_boundary`: literal LocalDateTime, metadata Instant, Session offset별 typed DATETIME 불변성과 비교용 TIMESTAMP/FROM_UNIXTIME의 차이를 유지한다. getTimestamp(...).toInstant()의 Host별 차이는 비교 API의 특성을 설명하는 assertion이며 이를 UTC로 바꾸지 않는다. 새 callback 값은 typed LocalDateTime과 명시적 toInstant(UTC)로 해석한다.

현재 `atHostTime()`은 Clock.systemDefaultZone()만 고정하므로 **assertion만 바꾸면 A안의 now(UTC)를 제어하지 못한다.** 다음 구현에서는 UTC Clock을 static mock 진입 전에 만들고 scoped mock에서 Clock.system(ZoneOffset.UTC)도 고정한다. 회귀 결함을 숨기지 않도록 systemDefaultZone()은 해당 JVM의 Host zone을 가진 같은 fixed Instant로 계속 고정하고 실제 LocalDateTime.now(UTC)·JPA callback·MySQL은 그대로 통과시킨다. 반환 값 now 자체나 JPA/JDBC를 Mock하지 않는다. 고정 Instant를 벗어난 실시간을 쓰거나 허용 범위를 넓혀 통과시키지 않는다. 테스트 이름·DisplayName·Javadoc/helper 설명은 수정 후 UTC 계약 검증에 맞춘다.

| 검증 축 | 다음 구현의 실행/기대값 |
| --- | --- |
| UTC / Asia/Seoul / America/Los_Angeles | user.timezone을 JVM 시작 시 지정한 독립 Test worker 3개로 동일 선택 테스트를 실행. Gradle daemon 설정만으로 worker에 전달됐다고 가정하지 않고 worker의 기본 zone 확인, 결과 디렉터리 분리. TimeZone.setDefault() 사용 금지. 같은 Instant → 같은 UTC DATETIME/JPA 값 |
| LA DST spring gap | `2026-03-08T09:59:59Z` → `10:00:00Z` 전후: local 01:59:59 → 03:00:00 변화와 무관하게 UTC는 1초 전진 |
| LA DST fall overlap | `2026-11-01T08:59:59Z` → `09:00:00Z`, 및 `08:30Z`/`09:30Z`처럼 local 01:30이 중복되는 서로 다른 Instant: UTC 저장은 역행/충돌하지 않고 실제 간격 유지. 같은 fixture를 Seoul/UTC에서도 실행 |
| 실제 MySQL DATETIME(6) | mysql:8.4.11 격리 Testcontainer + V1~V20 + ddl-auto=validate. 모든 대상 생성/갱신 컬럼 raw typed read와 flush/clear/find, literal 읽기 및 metadata Instant 왕복. 기존 Session offset 비교 유지. 추가로 같은 격리 DB에 한 JVM이 쓴 UTC row를 다른 JVM이 읽는 순차 교차 읽기는 AUD-12에서 미실시이므로 별도 후속 검증으로 구분 |
| nanosecond/microsecond | `.123456000` 정확 왕복, `.123456789`와 `.999999999` 등 비정렬 값의 실제 binding/extraction 및 초/날짜 carry 확인. 절삭을 가정해 assertion을 만들지 않고 현재 Hibernate/Connector/SQL mode의 동작을 확인. 기존 Session/Event/Delivery 절삭과 retry 최소시각 올림 보존. precision 추가 수정이 필요하면 근거와 범위를 먼저 결정 |
| RefreshToken / User / Alarm | RepositoryIntegrationTest, RefreshTokenServiceTest 및 위 UTC 계약 테스트로 생성·dirty update·createdAt 불변·idempotent activate의 updatedAt 불변, Expiry/Rotation/Logout 확인. 과거 Host-local 감사 literal + 기존 UTC expiry/FOLLOW_UP의 혼합 fixture를 남겨 읽기 자동 보정/기한 이동이 없음을 확인 |
| FOLLOW_UP / 정상 경로 회귀 | BusAlarmLifecycleServiceTest, BusAlarmDecisionIntegrationTest, BusAlarmEvaluatorTest의 관측 기준·5분·복구 경계, DevicePersistenceIntegrationTest, NotificationPersistenceIntegrationTest, NotificationRetryPolicyTest, NotificationAttemptIntegrationTest, NotificationDispatchIntegrationTest, BoardingSessionPersistenceIntegrationTest, BoardingTrackingIntegrationTest, 서울/TAGO Mapper 테스트의 직접 관련 항목 선택 실행. Notification due/retry/expiry, Session 수명·checkpoint와 Provider timezone/NULL 의미 보존; 실제 Provider/FCM·실기기 호출 없음 |

독립 JVM 실행은 기존 AUD-12의 임시 Gradle init script 방식으로 user.timezone과 report 경로를 분리할 수 있다. 이번에는 script 생성·Gradle 실행·Docker 조작을 하지 않았다. 구현 후 위 필수 검증이 성공하고 필요한 Owner 문서 동기화가 끝나야 TASK-814 완료 여부를 판단한다.

#### 다음 구현의 예상 변경 파일과 미확정 사항

| 분류 | 예상 파일·변경 |
| --- | --- |
| 필수 제품 변경 | `backend/src/main/java/com/stopbell/user/entity/User.java`, `user/entity/RefreshToken.java`, `alarm/entity/Alarm.java`(뒤 두 경로도 같은 com/stopbell 하위): ZoneOffset import, callback 5곳 now(ZoneOffset.UTC). 생성 callback의 단일 now 공유·Alarm validateLifecycleState 호출 순서 유지 |
| 필수 테스트 변경 | `backend/src/test/java/com/stopbell/common/UtcTimestampCharacterizationIntegrationTest.java`: UTC 기대값·Clock 고정·설명 변경, 세 zone/DST/정밀도/혼합 legacy fixture 및 raw/JPA 검증 보강. 필요한 경우 같은 common 테스트 패키지에 계약 테스트 분리 |
| 조건부 직접 테스트 변경 | `backend/src/test/java/com/stopbell/user/entity/RepositoryIntegrationTest.java` 등 위 표의 직접 관련 테스트에 부족한 assertion만 보강. 기존 검증이 충분한 테스트는 실행 대상으로만 유지, 정상 제품 Service/Entity는 수정하지 않음 |
| 구현 후 문서 | `docs/task-list.md`에 구현/검증 결과와 완료 조건 기록; `docs/database.md`·`docs/architecture.md`의 현재 callback 미보장 설명을 검증된 결과에 한해 최소 동기화. ADR은 새 구조 결정이 없는 A안에서 신설하지 않음. 이번 분석에서는 이 절 외 문서를 바꾸지 않음 |
| 변경 불필요 | SQL Migration V1~V20, application*.yml, build.gradle, Docker/인프라, 정상 UTC Service/Provider/Notification/BoardingSession/Device/metadata 및 API 계약 |

미확정·위험은 과거 컬럼/기간별 원천과 기존 감사 데이터 보존·정렬 영향 수용 여부, DST·sub-microsecond 실제 binding, 수정 후 factory Clock 고정의 실행 검증, 동일 DB의 교차 JVM 읽기다. 과거 값 정규화 필요성이 입증되기 전에는 Migration을 추가하지 않는다. 기존 Architecture UTC 절·Roadmap 등에는 TASK-816을 미완료로 적은 문구가 남아 현재 `[x]`와 다르지만, 이는 이번 UTC 구현 결정과 무관한 기존 상태 서술 불일치로 보고만 하고 기존 TASK-816 기록/다른 문서는 수정하지 않는다. TASK-804·710/711·908, 인증/Alarm/Session/Retry 정책, 운영 인프라로 범위를 확대하지 않는다.

### TASK-814 UTC 구현 및 검증 결과 (2026-10-30)

위 94행 분석은 착수 전 기록으로 원문 보존한다. 아래 결과가 현재 구현·검증 상태이며, 과거 AUD-12와 TASK-816 당시의 미수정/미완료 서술을 소급 변경하지 않는다.

#### 구현 및 기존 계약 보존

- 사용자 확정 A안 적용: `User.onPrePersist()`/`onPreUpdate()`, `RefreshToken.onPrePersist()`, `Alarm.onPrePersist()`/`onPreUpdate()` 총 5곳을 `LocalDateTime.now(ZoneOffset.UTC)`로 변경했다. 제품 변경은 3개 Entity의 UTC 인자와 import뿐이다. 생성 callback의 단일 now 공유와 Alarm lifecycle 검증 순서는 유지했다.
- Java LocalDateTime/기존 metadata Instant, JPA Mapping·API DTO/직렬화·DATETIME(6)·Refresh expiry/Rotation/Logout·FOLLOW_UP 관측 기준/+5분·Device/Notification/BoardingSession/Provider/metadata 경로는 유지했다. Schema/Flyway V1~V20·Dependency·Gradle/Profile·JDBC/Hibernate timezone·Docker 설정은 변경하지 않았다. 보호 파일 25개(기존 Migration 20개, Gradle 및 Compose와 application 설정)의 착수 시 SHA-256과 작업 후 값을 대조했다.
- `UtcTimestampCharacterizationIntegrationTest`는 Host 결함 기대값을 UTC 회귀 계약으로 바꿨다. Java 21 LocalDateTime 바이트코드의 `now(ZoneId) → Clock.system(ZoneId)` 경로를 확인하고 `atFixedTime()`이 호출 스레드의 scoped static mock에서 Clock.system(UTC)와 systemDefaultZone()을 같은 Instant/각각 UTC·Host zone으로 고정한다. 실제 LocalDateTime.now·JPA callback·MySQL은 Mock하지 않으며 JVM 기본 시간대는 실행 중 변경하지 않는다. Entity의 생성/수정값을 정확히 확인하므로 고정 시각의 callback 적용 여부도 검증한다.
- 기존 4개 실행 사례를 12개로 보강했다: Worker zone 확인 1, 정수초/정확 microsecond callback 2, sub-microsecond binding 2, Refresh 1, FOLLOW_UP 1, JDBC/metadata 1, legacy 보존 1, DST 3. 허용 시간 오차 없이 생성 Entity → raw DATETIME typed read → EntityManager clear/재조회, PrePersist/PreUpdate, createdAt 불변과 멱등 activate의 updatedAt 불변을 검증한다.

#### 실행 결과와 증거 범위

Java/Gradle JVM 21.0.12.1, Wrapper 8.14.3, Hibernate 7.4.5.Final, Connector/J 9.7.0, 격리 mysql:8.4.11, Flyway V1~V20과 UTC 테스트의 ddl-auto=validate를 사용했다. 초기 샌드박스의 Gradle cache lock 접근 제한은 허용된 테스트 실행 권한으로 해결했으며, 테스트 실패를 우회하거나 검증을 약화한 것은 아니다.

| 실행 | 실제 사례 수 | 통과 | 실패 | 오류 / Skip | 확인 내용 |
| --- | --- | --- | --- | --- | --- |
| UTC 독립 Worker | 12 | 12 | 0 | 0 / 0 | Worker actual=expected=UTC |
| Asia/Seoul 독립 Worker | 12 | 12 | 0 | 0 / 0 | Worker actual=expected=Asia/Seoul |
| America/Los_Angeles 독립 Worker | 12 | 12 | 0 | 0 / 0 | Worker actual=expected=America/Los_Angeles |
| DST subset | 각 JVM 3 | 각 3 | 0 | 0 / 0 | 위 각 12개에 포함, 별도 신규 사례로 합산하지 않음 |
| 직접 관련 회귀 11개 클래스 | 222 | 222 | 0 | 0 / 0 | User/Persistence, Login/Refresh, Alarm/FOLLOW_UP |
| Backend 전체 79개 클래스 | 1,280 | 1,280 | 0 | 0 / 0 | BUILD SUCCESSFUL, 6분 31초; 이전 1,272개 대비 UTC 보강 8개 증가 |

독립 Worker 3회는 같은 12개 사례의 반복 검증이다. 관련 회귀와 전체 실행에도 겹치는 테스트가 있으므로 이 실행 수들을 고유 테스트 수로 합산하지 않는다. 직접 관련 클래스는 RepositoryIntegrationTest, RefreshTokenServiceTest, LoginServiceTest, LoginServiceConcurrencyIntegrationTest, AuthControllerIntegrationTest, AlarmTest, AlarmServiceIntegrationTest, BusAlarmLifecycleServiceTest, BusAlarmDecisionIntegrationTest, BusAlarmEvaluatorTest, TransitMonitoringIntegrationTest다.

집중 실행은 `env -u JAVA_HOME ./gradlew test --offline --max-workers=1 --tests com.stopbell.common.UtcTimestampCharacterizationIntegrationTest -I /private/tmp/stopbell-task814/timezone.init.gradle -Ptask814Zone=<zone> -Ptask814Run=<run>`으로 수행했다. 일회성 init script에서 Test worker의 `user.timezone`과 독립 검증 property `task814.expectedTimezone`을 모두 `<zone>`으로 지정하고 XML/HTML report 경로를 `task814-<run>`으로 분리했다. 저장소 Gradle 설정은 변경하지 않았다. XML의 Worker assertion 및 stdout에서 실제 zone과 기대값 일치를 확인했다. 집중 XML은 `backend/build/test-results/task814-{UTC,Asia-Seoul,America-Los_Angeles}`, 관련 회귀는 `task814-related`, 전체는 기본 `backend/build/test-results/test`다. 전체 실행 명령은 요청한 `env -u JAVA_HOME ./gradlew test --offline --max-workers=1`이다.

- **DST:** LA ZoneRules가 실제로 offset을 바꾸는 `2026-03-08T09:59:59Z → 10:00:00Z`의 local 01:59:59→03:00:00, `2026-11-01T08:59:59Z → 09:00:00Z`의 local 01:59:59→01:00:00을 검증했다. `11-01T08:30Z`/`09:30Z`가 같은 local 01:30이어도 별개 UTC로 저장되고 실제 간격이 유지된다. 각 사례는 User/Alarm PreUpdate도 검증한다.
- **MySQL/정밀도:** 5개 감사 컬럼의 raw typed DATETIME 및 JPA 재조회가 UTC 기대값과 일치했다. `.123456000` 정확 왕복과 `.123456789 → .123457`, `23:59:59.999999999 → 다음 날 00:00:00`의 기존 반올림을 확인했다. 제품에 절삭을 추가하지 않았고 시간 오차 assertion도 없다. Session offset +00:00/+09:00/-07:00별 동일 literal/JPA·metadata Instant 왕복과 TIMESTAMP/FROM_UNIXTIME/getTimestamp 비교 API 차이도 보존했다. fresh Testcontainer의 20개 Migration validate/apply와 Schema validation은 통과했다.
- **업무 기한:** Refresh 만료 1 microsecond 전 Rotation과 새 30일 기한, 원 Hash 재사용 거부, 정확한 만료 경계 삭제/거부, 만료 Token Logout 삭제를 보존했다. FOLLOW_UP은 기존 applyArrival 관측 UTC 기준 시작/+5분을 저장·재조회하고 기한 1 microsecond 전 유지, 동일/이후 만료를 확인했다. private applyArrival 테스트만으로 lifecycle commit/restart 전체를 검증했다고 주장하지 않으며 실제 Lifecycle/Decision/Monitoring 통합 회귀를 함께 실행했다.
- **Flutter:** 제품 DTO/직렬화/API 계약과 Flutter 파일은 변경하지 않아 재실행하지 않았다. 기존 TASK-816의 Flutter 549/549 통과는 이전 결과이며 이번 실행 결과가 아니다. 실제 Provider/Google/FCM/iPhone, 개발/운영 DB 조회·변경, 운영 서버/터널·Backup/Restore·배포는 수행하지 않았다.

#### 기존 데이터 정책과 완료 판정

호환성 정책은 [Database의 현재 UTC 계약](database.md#utc-목표와-현재-저장-경계-task-816--aud-12)이 소유한다. 기존 값의 원문 보존·읽기 자동 보정 없음·신규 생성/갱신부터 UTC를 적용한다. 격리 legacy fixture에서 기존 User/Alarm/RefreshToken의 감사 값과 UTC expiry를 그대로 읽고, read-only flush가 이를 변환하지 않으며 dirty update 후에도 createdAt/Token expiry는 보존되고 updatedAt만 UTC로 바뀜을 확인했다. Schema/Mapping/읽기 해석과 업무 기한이 같아 신규 UTC 계약 적용에 데이터 변환 Migration은 필요하지 않다. 전체 ±9시간/추측 기반 변환·기존 Token expiry 변경·기존 User/Alarm 재생성은 하지 않는다.

과거 값이 UTC였다는 보장은 없고 DATETIME만으로 원래 zone/Instant를 복원할 수 없다. 실제 과거 writer/JVM/JDBC/DB timezone과 데이터 분포·배포 이력은 미확인이다. 수정 전후 감사 값 혼합에 따른 Alarm createdAt 정렬과 updatedAt 역행 가능성은 남는다. 운영 적용 전 배포 이력·원천 시각·기존 데이터 영향·백업/복구 가능성과 정렬 영향 수용 여부를 확인하고, 필요성이 입증될 때만 범위를 특정한 별도 이관 계획을 마련한다. 이는 기존 TASK-813의 운영 배포 전 확인 조건이며 이번에 실제 운영 호환성을 실증한 것은 아니다. 같은 격리 DB를 세 JVM이 순차 공유하는 읽기 실험이나 임의 JDBC/SQL mode 조합은 수행하지 않았으며, 각 JVM의 별도 DB에 같은 fixture를 저장·조회한 범위다.

**완료 판정: TASK-814 `[x]`.** callback 5곳의 UTC 생성, 기존 타입/Schema/업무 기한 보존, 실제 Worker zone이 확인된 UTC/Seoul/LA 독립 JVM, DST·MySQL/정밀도·legacy fixture, 직접 관련 222개와 Backend 전체 1,280개 회귀, 기존 데이터 무변환 호환성 정책 및 주요 문서 동기화가 완료됐다. 원문 보존 정책으로 이번 제품 수정에 필요한 미결 Schema/데이터 변환 결정은 없다. 이 완료는 과거 운영 값의 시간대 복원·정렬 영향 수용·실데이터 이관 또는 운영 배포 승인/실증을 뜻하지 않으며 위 운영 적용 전 확인 조건은 유지한다. TASK-816 `[x]`와 다른 모든 Task 상태는 유지한다. Database/Architecture의 현재 UTC 계약과 Roadmap의 TASK-814/816 현재 완료 상태를 최소 동기화하고 착수 전 94행 분석·AUD-12·TASK-816 당시 기록은 보존했다.

Git 검토 범위는 제품 3개 Entity, UTC 테스트 1개, Task List/Database/Architecture/Roadmap 문서 4개로 제한한다. 착수 HEAD는 `82001b7d0602b81302949af1eb85c9eda140a3fa`이며 기존 미커밋 분석을 같은 Task 결과에 포함한다. 완료된 테스트를 문서 변경 때문에 다시 실행하지 않는다. 실제 개발/운영 DB와 기존 데이터를 건드리지 않았으며 다른 Task를 시작하지 않는다.

### TASK-816 감사 항목과 확인 수준

2026-10-29 종합 감사 보고서의 식별자·우선순위·확인 수준을 유지한다. 아래 재현은 감사 당시의 임시 자동 재현이며 실제 서비스·실기기 재현과 구분한다. 이번 등록에서 새 재현이나 검증을 수행한 것은 아니다.

| 식별자 | 우선순위 | 결함 및 후속 검토 범위 | 감사의 확인 수준 |
|---|---|---|---|
| AUD-01 | P1 | 최초 Alarm 생성·활성화 과정에서 Push 준비 절차 누락. create→activate에도 기존 준비 절차와 준비 실패 시 사용자 선택 연결 | 임시 Widget에서 활성화 1회·준비 hook 0회 재현. 실제 iPhone 검증과 구분 |
| AUD-02 | P1 | 기존 Alarm의 동일·역행 Provider 시각에 대한 도착 판단 문제. ACTIVE/FOLLOW_UP의 시각 단조성·동일 시각 위치 충돌 검증 | 실제 평가기에 freshness 내 동일 시각·역행 시각 입력 시 ARRIVED 후보 생성 재현. 실운행 검증 아님 |
| AUD-03 | P1 | 정류장 정보가 없는 서울 노선의 불완전한 metadata snapshot 승인. complete 승인 전 포함 노선의 occurrence 최소 조건 검증 | 임시 CSV로 알려진 포함 유형 노선의 occurrence 0개 complete 승인 재현. 기존 occurrence 삭제 연결은 sync 코드 확인이며 개발 DB 적용 없음 |
| AUD-04 | P1 | Polling과 Notification Dispatch의 Scheduler 실행 자원 공유로 인한 지연 위험. 각 작업의 non-overlap을 유지하며 실행 자원 분리 | 기본 scheduling pool size 1과 구성·실행 경로 확인. 실제 부하의 발생 빈도·지연 분포는 미측정 |
| AUD-05 | P2 | Alarm timeout 이후 늦은 401 응답에 의한 mutation 재전송. timeout 이후 늦은 refresh/replay 차단 | 임시 Flutter 재현에서 timeout 후 401 반환 시 요청 2회·refresh 1회 확인 |
| AUD-06 | P2 | 인증 HTTP 요청의 timeout 부재. login·refresh·logout의 유한 종료 시간과 늦은 결과 반영 차단 검토 | 코드 경로 확인. 실제 네트워크 장기 정지는 미실측 |
| AUD-07 | P2 | 서울 버스 후보 조회 시 순차 detail 호출로 인한 응답 지연 위험. 전체 budget·호출 수와 Client deadline 정합성 검토 | 1차 Mock 재현 후 12초 예산·후보 전용 요청당 2/프로세스 전체 4 병렬 처리·deadline HELD 구현 및 선택 검증 완료. 실제 지연·Provider quota/부하는 미검증 |
| AUD-08 | P2 | BoardingSession 재확인 직후 준비 알림 판단 누락 가능성. 미소비 상태의 알림 구간 재확인과 다음 관측 전이 검토 | 정적 경로 확인에 따른 누락 시나리오. 실운행 재현 없음 |
| AUD-09 | P2 | 서울 개별 차량 mapping 오류가 노선 전체 평가를 중단하는 문제. 정상 차량 관측·roster presence를 유지하며 차량별 실패 격리 | 잘못된 dataTm 등의 예외가 차량별 처리 경계를 벗어나는 코드 경로 확인 |
| AUD-10 | P2 | 동일 신규 Google 사용자의 동시 최초 로그인 경쟁. identity UNIQUE 충돌 후 안전한 기존 사용자 재확인 검토 | 동시 실행 경로에 대한 추론. 실제 LoginService 경쟁 재현은 미실행 |
| AUD-11 | P2 | 기존 사용자 데이터가 존재하는 V3→V4 Migration 업그레이드 문제. 지원할 과거 데이터 범위·적용 전제와 호환성 검토 | 격리 MySQL의 V3 사용자 2명 상태에서 V4 identity UNIQUE 중복키 실패 재현. 과거 업그레이드 호환성 문제이며 현재 V20 적용 실패가 확인된 것은 아님 |
| AUD-12 | P2 | 일부 기존 timestamp의 Host Timezone 의존성. User·RefreshToken·Alarm callback과 UTC 저장 계약 차이 | 코드 확인. 기존 TASK-814 잔여 범위이며 구현·검증 책임은 TASK-814에 연결 |
| AUD-13 | P2 | 기존 Alarm의 PASSED 위치 보조 정보가 Push 안내까지 전달되지 않는 문제. 검증된 optional 위치·지난 정거장 정보의 표시 연결 검토 | 불변 PASSED Context로 영속 Event→Dispatch→FCM Body 전달 수정·선택 검증 완료. 실제 Provider/FCM·iPhone 표시·process restart는 미검증이며 아래 처리 기록 참조 |

각 항목의 처리 기록에는 재현 근거, 수정 또는 미수정 판단, 변경 범위, 방지 테스트와 결과, 남은 실측·실기기 검증 및 담당 Task를 남긴다. 미재현 추론·위험을 확인된 운영 장애로 승격하지 않는다. API·Domain·Authentication·DB 정책이나 공식 문서 간 설계 판단이 필요한 경우 개발자 결정 후 반영한다.

#### AUD-13 처리 (2026-10-30)

- 원인·수정 경계: `BusAlarmEvaluator`는 동일 차량의 검증된 Observation 이름과 `BusRouteTraversalService`의 successor edge 수를 TransitEvent에 넣고, Lifecycle은 NotificationEvent/Delivery와 원자 저장했으나 `NotificationRecipientPreflight`가 전송 Target에 이를 복사하지 않아 FCM이 고정 PASSED 문구만 사용했다. 이제 기존 attempt transaction의 원래 Event에서 두 값만 `PassedNotificationContext`로 복사해 DispatchTarget → AttemptSnapshot → SendRequest → FCM Body로 전달한다. 기존 생성자 호환성을 유지하며 Entity/Lazy proxy·추가 조회/lock·Provider 재조회·cache·Event 사후 갱신은 추가하지 않았다. 상세 경계는 [Architecture](architecture.md#passed-위치-보조-정보-전달-task-816--aud-13)를 따른다.
- 출처·불확실성: 정거장 수는 기존 동일 traversal의 검증된 successor edge 수이며 dispatch에서 raw order 차/GPS로 재계산하지 않는다. null/비양수는 생략한다. 이름은 원래 Event 값만 사용하며 null/공백·제어/format/줄 구분 문자·고립 surrogate는 생략하고 앞뒤 공백 제거·말줄임표 포함 80 code point 제한을 적용한다. 현재 서울·TAGO Mapper는 이름을 null로 남기므로 임의 이름 보강은 없다. 위치 부재는 정상 기본 문구로 처리하되 실제 DB 예외를 부재로 숨기지 않는다.
- 문구·호환성: 기본 `버스가 목표 정류장을 지나갔어요.`를 앞에 두고 이름/정거장 수/둘 다/모두 없음의 네 경우를 독립 처리한다. 최근 확인된 위치로 표현하고 실시간 위치/도착을 단정하지 않는다. [API의 최종 문구](api.md#push-payload와-notification-tap)를 일반 notification과 APS alert에 동일하게 사용한다. `type`·`alarmId`·`eventType`·`notificationEventId`와 BoardingSession의 기존 data key, 제목·FCM HTTP/auth/timeout은 유지하며 위치 data key는 추가하지 않았다. 다른 Alarm Event/두 BoardingSession Event는 Context를 사용하지 않는다. Flutter의 foreground 일반 안내·수신/parser·Event ID dedupe·tap/navigation은 코드 확인 후 변경하지 않았다.
- 실제 MySQL·Dispatch 검증: 신규 통합 9개는 네 선택 조합의 Lifecycle 원자 저장·commit 뒤 별도 영속성 컨텍스트/clear와 재조회, 이름/정거장 수/Stop ID/order/providerDataTime/type/cycle/generation·Delivery 연결, transaction 밖 Worker→실제 payload builder의 두 alert body/identity를 검증한다. production AttemptHandler/RetryPolicy로 retry 예약 후 전송 서비스를 재생성하고 Device FID/revision 갱신·다른 cycle Event 추가 뒤에도 원래 Event 위치/identity/기한을 사용하는지 검증했다. expiry·disabled·owner 변경·A→B→A generation 변경은 attempt 0·Provider 호출 0이며 Alarm ACTIVE 유지도 확인했다. 이는 실제 프로세스 restart E2E가 아니다.
- 신규 회귀: `FcmProviderClientTest`에 32개 사례(네 조합·null/비양수·공백/제어/Unicode·한글/JSON escape·길이·다른 Alarm/BoardingSession 격리·data/APS 일치), `BusRouteTraversalServiceTest`에 order 차가 3일 때 실제 edge 1/3을 구분하는 2개, `PassedNotificationDispatchIntegrationTest`에 9개를 추가했다. 신규 총 **43개 사례/10개 테스트 메서드**이며 기존 판정 우선순위·stale/충돌·baseline·PASSED 뒤 다음 차량·정상 Lifecycle 및 recipient/retry/expiry/cleanup 회귀를 선택 검증했다.
- 실행 결과: Java/Gradle JVM 21.0.12.1·Wrapper 8.14.3·MySQL 8.4.11 Testcontainers에서 아래 **13개 클래스의 최종 341개**가 실패·오류·skip 없이 통과했다. 첫 실행 254개 중 신규 통합 4개의 UUID/문자열 기대 타입 오류를 수정했다. 후속 98개 실행에서는 새 Traversal 2개의 중첩 Mockito stubbing 오류만 발생해 fixture 생성과 stubbing을 분리했고, 해당 클래스 4개만 재실행하여 통과했다. 제품 코드 변경이나 검증 약화로 실패를 우회하지 않았으며 이미 통과한 무변경 클래스는 반복하지 않았다.

| 선택 테스트 클래스 | 최종 통과 사례 수 |
| --- | ---: |
| BusAlarmEvaluatorTest | 65 |
| BusRouteTraversalServiceTest | 4 |
| BusAlarmLifecycleServiceTest | 6 |
| BusAlarmDecisionIntegrationTest | 12 |
| NotificationEventTest | 22 |
| NotificationPersistenceIntegrationTest | 12 |
| NotificationDispatchWorkerTest | 10 |
| NotificationDispatchIntegrationTest | 10 |
| NotificationAttemptIntegrationTest | 31 |
| DurableNotificationAttemptHandlerTest | 30 |
| FcmProviderClientTest | 57 |
| PassedNotificationDispatchIntegrationTest | 9 |
| BoardingDispatchIntegrationTest | 73 |
| 합계 | **341** |

- 문서·보호 범위: Architecture와 API의 변경된 전달/표시 계약 및 이 처리 기록만 동기화했다. Requirements의 기존 검증된 traversal/생략 계약과 Database/V14/V16의 optional snapshot Schema는 이미 정확해 수정하지 않았다. 판정/Evaluator/Lifecycle 구현·Tracking Cycle/activation generation·Event/Delivery UNIQUE·recipient lock 순서/owner/generation/revision·retry/expiry·Device 정책·BoardingSession·AUD-12 Characterization Test·TASK-814 UTC 후속 범위는 보존한다. Dependency·Migration·Credential·환경 설정·devlog 변경은 없으며 `git diff --check`를 통과했다.
- 미검증·상태: 전체 Backend/Flutter suite·실제 Provider/FCM/Google 호출·개발 MySQL 접근·Backend 상시 실행·터널·실제 iPhone·process restart는 수행하지 않았다. 실제 iPhone의 긴 Body/줄바꿈/표시 범위·foreground/background/terminated 수신/tap은 TASK-710/711 책임으로 남는다. AUD-13 구현/선택 검증 완료는 TASK-816 전체 완료가 아니다. 주요 문서 불일치 정리, Backend·Flutter 주요 회귀, AUD-12/TASK-814 연결 최종 확인, 실기기/Provider 미검증 항목과 기존 Task 책임·완료 조건 정리를 별도 후속 작업으로 남기며 TASK-816/TASK-814는 `[ ]`를 유지한다. 기존 AUD-01~12 기록과 다른 Task 상태를 보존하고 `main`에서 Git add/commit/push·reset/stash/clean/checkout·branch 생성 없이 AUD-13 보고 후 중단한다.

AUD-07 분석 (2026-10-30, 제품 수정 보류·설계 판단 필요): 서울 후보 조회의 순차 detail 지연과 Flutter 15초 예산 불일치를 실제 `BoardingCandidateService`·Mock Client·제어 가능한 Clock으로 재현했다. 운영 지연의 발생 빈도/실제 차량 수 분포를 측정한 것은 아니다. **Case A의 조건부 성능 위험은 확인됐으며 보호 장치가 충분한 Case B로 종결하지 않는다.** deadline 중단 시 미조회 차량의 응답 정책이 없어 제품 구현을 임의 변경하지 않았다.

- 실제 코드 경로: Backend `transit/controller/BoardingCandidateController.java` → `service/BoardingCandidateMetadataService.java` → `service/BoardingCandidateService.java`의 `find()`/`fetchRoster()`/`fetchDetail()` → `client/SeoulBusVehicleLocationClient.java`·`SeoulBusVehicleDetailClient.java` → `SeoulBusResponseReader.java` → `mapper/BoardingCandidateMapper.java` → `dto/BoardingCandidatesResponse.java`·`service/BoardingCandidateReferenceStore.java`. Flutter는 `features/boarding/boarding_candidate_client.dart` → `boarding_stop_screen.dart`/`boarding_reconfirmation_screen.dart` → `boarding_candidates_view.dart`, 생성 전 freshness는 `boarding_session_controller.dart`다. 파일은 각각 `backend/src/main/java/com/stopbell/`, `app/lib/` 아래에 있다.
- 호출 구조: `GET /api/v1/bus-routes/{routeId}/boarding-candidates`는 서울 roster `getBusPosByRtidList`(`/api/rest/buspos/getBusPosByRtid`, `busRouteId`) **1회**, 승차 occurrence가 있으면 식별·시각 검증을 통과한 고유 차량마다 detail `getBusPosByVehIdItem`(`/api/rest/buspos/getBusPosByVehId`, `vehId`) **최대 1회씩 순차 호출**한다. 대상은 전체 번호→ID 순서이며 결과는 기존 occurrence 간격 그룹/절댓값·번호 순서다. 승차 occurrence 없음·정상 empty는 detail 0회다. 동일 item 중복은 병합하고 누락/상충 ID·번호와 stale 차량은 호출하지 않는다. 차량 수 고정 상한·공유 cache·후보 조회 자체 retry/재호출/지연은 없다. Monitoring의 5초 후 1회 retry와 `VehiclePollingCycle` 공유는 이 API에 적용되지 않는다.
- 시간 예산: 기본 Provider connect **2초**, response **5초**, metadata HTTP **30초**(이 후보 경로에서는 미사용), Backend 전체 deadline 없음, Flutter 인증 Refresh/최대 1회 Replay를 포함한 HTTP Future **15초**다. Spring Web 7.0.9의 실제 cached class에서 response timer가 응답/body를 감싸는 경로를 확인했으며 connect를 별도 2초로 중복 가산하지 않았다. 계산은 `로컬 처리 + roster + Σ detail`이다. DB·XML/JSON decoding·후보 mapping/정렬·reference 발급·직렬화 시간은 별도이며 실측/상한은 없다. 상세 runtime과 freshness 조건부 상한은 [Architecture](architecture.md#서울-후보-조회의-응답-예산-확인-task-816--aud-07)가 소유한다.

| 분석 차량 수 | 정상 Mock: roster 1초 + detail 각 1초, detail 수 / 총 가상 시간 | timeout Mock: roster 5초 + detail 각 5초 실패, detail 수 / 총 가상 시간 |
| --- | --- | --- |
| 1 | 1회 / 2초 | 1회 / 10초 |
| 5 | 5회 / 6초 | 5회 / 30초 |
| 10 | 10회 / 11초 | 10회 / 55초 |
| 20 | 20회 / 21초 | 13회 / 70초 |
| 40 | 40회 / 41초 | 13회 / 70초 |

- 표의 차량 수는 분석 입력이며 실제 분포가 아니다. 전체 HTTP 수는 각 detail 수에 roster 1회를 더한다. 각 Mock의 roster 제공시각은 roster 수신 시각, 정상 detail 제공시각은 해당 수신 시각으로 만들어 지연과 데이터 자체의 노후화를 구분했다. timeout은 가상 시계를 5초 전진한 뒤 `HttpTimeoutException` 원인의 `TRANSPORT` 실패를 전달하며 실제 TCP/HTTP timer를 기다리는 테스트는 아니다. 60초 freshness는 경계에서 여전히 유효해 timeout Mock이 roster 후 0·5·…·60초에 총 13개 detail을 시작하고 65초에 끝낸다. 뒤 차량은 stale로 생략되고 최종 평가에서는 모두 HELD다. 단순 `5 × (N+1)` 계산의 20대 105초/40대 205초를 실제 실행 결과로 보고하지 않는다. 반대로 빠른 80대 Mock(roster 1초·detail 10ms)은 **80회 / 1.8초**, 승차 occurrence 없는 80대는 **0회 / 1초**로 고정 호출 수 상한이 없음을 확인했다.
- 추가 재현: 5대·roster 1초·평소 detail 100ms에서 중간 1대만 4.9초이면 **5회 / 6.3초**이고 뒤 차량의 시작도 지연된다(모두 정상이라면 계산상 1.5초). 중간 1대만 5초 timeout이면 **5회 / 6.4초**, 정상 4대 보존·실패 1대 HELD·PARTIAL이다. 4.9초 지연 1대·5초 timeout 1대·100ms protocol 실패 1대 혼합은 **5회 / 11.2초**, 정상 3대 보존·PARTIAL이다. 20대·각 성공 4.9초·13번째만 5초 timeout이면 **13회 / 64.8초**로 앞서 성공한 12대도 최종 roster freshness 만료로 참조를 발급하지 않는다. 20/40대 정상 Mock은 15초 이후에도 후속 detail을 시작한다. 이는 Client timeout 이후 서버가 계속 작업할 수 있는 코드 근거이며 실제 연결 단절·서버 취소 실험은 아니다.

| 검토한 개선 | 장점 | 현재 판단/Trade-off |
| --- | --- | --- |
| 전체 deadline | 차량 수·느린 응답에 대한 응답 예산 통제 | Flutter 15초보다 작은 서버 예산·진행 중 호출의 잔여 timeout·미조회 차량 상태/사유 결정 필요. 호출 사이 시간 검사만으로는 진행 중 detail을 제한하지 못함 |
| detail 대상 수 제한 | 요청당 비용 상한 | 전체 유효 고유 차량 detail 조회 계약 변경. 조용히 자르면 잘못된 완전 목록/유일성 표시 위험 |
| 중복 제거 | 불필요한 호출 절감 | 동일 item 병합과 ID/번호 충돌 제외가 이미 있어 추가 결함 없음 |
| 필요한 차량만 조회 | 승차 미선택 시 비용 최소화 | 이미 detail 0회. 승차 선택 시 간격을 미리 알 수 없어 coarse 위치로 선별하거나 임의 후보를 생략할 수 없음 |
| 제한된 병렬화 | 순차 누적/느린 한 차량의 후속 시작 지연 완화 | 큰 N의 15초 보장은 별도 deadline 필요. 요청별 제한만으로는 다중 요청의 Provider 전체 동시성을 통제하지 못함. 공식 quota/허용 burst와 프로세스 전체 한도 근거 미확인 |
| 응답 공유·cache | 반복 요청 비용 절감 | 현재 후보 API의 무cache 계약 변경. 원래 관측 시각·독립 reference·최종 최신 재검증을 보존하는 공유 범위/수명 결정 필요 |

- 권장·보류 사유: 먼저 **15초 미만의 서버 전체 예산과 deadline에서 미조회/진행 중 차량을 어떻게 보류하며 정상 성공을 보존할지** 결정하고, 그 예산에 맞춘 제한된 동시성을 Provider quota·프로세스 전체 한도와 함께 검토한다. 단순 503 종료는 성공 후보까지 잃고, 미조회 차량을 DETAIL_EMPTY/DETAIL_NOT_REQUESTED/TRANSPORT 실패로 바꾸면 실제 원인을 왜곡한다. 기존 API에는 요청한 detail 실패/empty의 PARTIAL은 있지만 deadline 때문에 요청하지 않은 차량의 사유는 없다. 새 응답 정책이나 공식 호출 제한을 임의 확정하지 않는 요청에 따라 Executor·budget·reason code·Flutter 변경을 추가하지 않았다. 개선 전후 제품 호출 수/소요는 **동일**하며 개선 완료로 표시하지 않는다.
- 정확성 보호: 신규 16개 재현은 유효 후보의 reference가 정확한 User/Route/목적·승차 occurrence 및 roster/detail ID·전체 번호에 연결되는지, selectionKey·진행 occurrence/gap·Provider 시각이 보존되는지 검증한다. 실패는 HELD·참조/선택 키 없음·PARTIAL, stale는 원래 시각을 유지하며 HELD다. 기존 31개 Service·9개 Reference Store 테스트의 정상 EMPTY·detail EMPTY와 실패 분류·중복/상호 충돌·정렬·freshness도 통과했다. 제품 Mapper·AUD-09 차량별 실패 격리·AUD-02 시각 단조성·AUD-05/06 HTTP 정책·세션 생성/재확인의 최신 선택 차량 검증은 변경하지 않았다. 세션 인접 테스트는 제품 코드에 직접 영향이 없어 추가 실행하지 않았다.
- 선택 검증: Java/Gradle JVM **21.0.12.1**, Wrapper **8.14.3** 확인. `./gradlew test --offline`에 개별 `--tests`를 지정해 `BoardingCandidateLatencyTest` **16개(신규)**, `BoardingCandidateServiceTest` **31개**, `BoardingCandidateReferenceStoreTest` **9개**, `SeoulBusVehicleLocationClientTest` **11개**, `SeoulBusVehicleDetailClientTest` **6개**, `TransitClientPropertiesConfigurationTest` **3개**, `SeoulBusTransitObservationMapperTest` **19개**, 합계 **95개 모두 성공, 실패/오류/skip 0개**다. `git diff --check` 통과. 제품 변경 없는 분석/재현이므로 수정 전후 테스트 재실행은 하지 않았다.
- 변경·미검증 범위: 신규 `backend/src/test/java/com/stopbell/transit/service/BoardingCandidateLatencyTest.java`와 `docs/architecture.md`·이 기록만 변경했다. 실 Provider의 지연/차량 수/생성시각 분포, XML/JSON mapping CPU·메모리, 동시 사용자 부하·quota/Rate Limit, 네트워크 timeout/body 종료·Flutter 15초 이후 실제 서버 작업 취소, iPhone 조회/재시도 UI는 미검증이다. Backend/Flutter 전체 suite·DB 통합 테스트·실제 서울/TAGO/FCM 호출·개발 MySQL 변경·서버/터널 실행은 하지 않았다. Dependency·설정·Schema·Migration·Credential·devlog와 기존 AUD 기록/다른 Task 상태를 보존한다. main 및 TASK-816 `[ ]` 유지, Git add/commit/push·reset/stash/clean/checkout·새 branch 생성 없이 AUD-07 보고 후 중단한다.

AUD-07 구현 (2026-10-30, 위 1차 분석 이후 승인 정책 반영): 단순 반복문 시간 검사 대신 후보 Detail 전용 bounded executor와 단일 deadline을 적용했다. 기존 16개 순차 재현을 수정 전 다시 실행해 모두 통과한 뒤, `BoardingCandidateLatencyTest`를 개선 계약 14개로 교체했다. 위 1차 수치·당시 판단은 과거 분석 근거로 보존하며 현재 구현의 기대값으로 사용하지 않는다.

- 안전성·구조: Detail Client의 요청별 지역 상태, immutable 설정/JSON mapper와 호출별 XML parser/DOM, 순수 Candidate Mapper를 확인했다. 새 `BoardingCandidateDetailExecutor` singleton은 고정 4개 스레드·용량 4 queue와 admission 상한 4를 사용하며 요청당 미완료 작업은 최대 2개다. 미조회 차량별 Runnable/Future를 미리 만들지 않는다. 요청 thread는 같은 monitor의 완료/자리 반환 신호를 남은 예산 안에서만 기다린다. 정확한 실행·종료 경계는 [Architecture](architecture.md#서울-후보-조회의-응답-예산-확인-task-816--aud-07)가 소유한다.
- Deadline·한계: `find()`의 metadata 조회 직전 `System.nanoTime()`으로 **12초**를 시작해 모든 단계의 소비 시간을 누적한다. Detail 시작 검사·자리 대기·결과 수집/반영을 제한하고 경계 이상 결과는 버린다. 동기 metadata DB·roster I/O·최종 충돌/freshness 검증·reference/정렬·응답 직렬화는 강제 중단하지 않으므로 절대 12초 HTTP SLA는 아니다. 실제 HTTP 즉시 취소를 주장하지 않고 worker가 실제 끝난 뒤에만 실행 자리를 반환한다. 종료 시 admission 차단·collector 깨우기·executor shutdown을 수행하며 미시작 wrapper는 호출 없이 정리한다.
- 응답·정확성: [후보 API](api.md#후보-조회)에 `DETAIL_DEADLINE_NOT_STARTED`와 `DETAIL_DEADLINE_INCOMPLETE`를 추가했다. 둘 다 HELD·tracking HELD·reference/selectionKey/진행/gap null이며 원래 roster 번호/시각을 보존한다. 기존 정상 미요청·empty·HTTP/transport/provider/protocol 실패를 재사용하지 않는다. 하나 이상 불확실하면 PARTIAL이며 전부 HELD인 정상 비empty roster도 EMPTY로 바꾸지 않는다. 정상 empty/roster 실패 503은 유지한다. Deadline 전 정상 근거는 기존 차량 ID/전체 번호·시각·Stop ID/order·occurrence·상호 충돌·최종 60초 freshness 검증을 통과한 경우 보존하고 원본 시각을 갱신하지 않는다.
- Reference·기존 기능 보호: worker는 Provider/불변 mapping만 계산하고 final request thread에서만 `references.replace(context)`와 검증 통과 후보 발급을 수행한다. 늦은 작업의 결과·reference 변경을 막고 User/Route/목적/승차 occurrence·opaque 형식·eviction/restart/원래 expiry를 유지한다. 후보 선택·자동 확정 금지와 생성/재확인의 최신 roster/detail 검증은 그대로다. 공통 Provider Client·Alarm/Boarding Monitoring·Notification·AuthSession은 변경하지 않는다.
- 동시성과 Rate Limit 구분: 한도는 **이 프로세스의 후보 Detail 전용**으로 roster·세션 생성/재확인·`VehiclePollingCycle`/다른 인스턴스를 포함한 Provider 전체 동시성 및 초당 호출량은 보장하지 않는다. 공식 계정 quota/허용 동시성의 수치는 기존 문서/설정에 없고 실제 운영에서는 미확인이다. 기존 HTTP connect 2초/response 5초를 유지하고 retry/cache/고정 호출 수 한도는 추가하지 않았다. AUD-04 Monitoring/Dispatch Scheduler와 스레드를 공유하지 않으며 네트워크·CPU·Provider는 공유한다.
- Flutter: DTO는 ReasonCode를 String으로 보존해 신규 값도 전체 응답 거부 없이 수용한다. unknown 처리·정상 후보 선택·HELD 선택 금지·PARTIAL 표시·수동 재조회·기존 15초 Timeout·Auth generation/session identity를 유지하고 신규 두 사유의 화면 설명만 추가했다.

| 항목 | 기존 구현 | 개선 구현 |
| --- | --- | --- |
| Detail 호출 / 요청당 동시성 | 순차 / 1 | 제한된 병렬 / 최대 2 |
| 후보 요청 전체 동시성 | 별도 한도 없음 | 프로세스별 admission 최대 4, 실행·대기 합계 포함 |
| 조회 예산 | 없음 | metadata 전 시작한 단일 12초, Detail 시작·대기·결과 반영 강제 |
| Deadline 이후 Detail | 시작/대기 가능 | 신규 시작/늦은 결과 반영 차단, 진행 중 I/O는 실제 종료까지 자리 유지 |
| 부분 성공 | 기존 detail 실패/불확실성 | 기존 사유 + deadline 미시작/미완료 별도 HELD |
| 정상 후보 | 다른 차량 누적 지연에 따른 만료 위험 | Deadline 전 확보한 근거를 최종 60초 freshness가 유효하면 보존 |

| 차량 수 | 정상 Mock 기존 → 개선 총 가상 시간 | 연속 timeout Mock 기존 → 개선 총 가상 시간 | 개선 정상 모델: AVAILABLE / 미완료 / 미시작 | 개선 timeout 모델: 실제 통신 실패 / 미완료 / 미시작 |
| --- | --- | --- | --- | --- |
| 1 | 2 → 2초 | 10 → 10초 | 1 / 0 / 0 | 1 / 0 / 0 |
| 5 | 6 → 4초 | 30 → 12초 | 5 / 0 / 0 | 2 / 2 / 1 |
| 10 | 11 → 6초 | 55 → 12초 | 10 / 0 / 0 | 2 / 2 / 6 |
| 20 | 21 → 11초 | 70 → 12초 | 20 / 0 / 0 | 2 / 2 / 16 |
| 40 | 41 → 12초 | 70 → 12초 | 20 / 2 / 18 | 2 / 2 / 36 |

- 비교 조건: 모두 실제 Provider 측정이 아닌 Mock 모델이다. 정상은 roster 1초/detail 각 1초, timeout은 roster 5초/detail 각 5초 실패다. 개선 테스트는 AtomicLong 단조 시간과 latch로 두 작업씩 wave를 제어하고 observation `Clock`은 별도로 고정해 identity/시각 혼합 없이 순수 스케줄을 비교한다. 12초 **정확한 경계** 결과도 제외한다. 따라서 정상 40대의 22개 시작 중 마지막 2개는 미완료이고, timeout 5대 이상은 4개 시작 중 첫 2개만 실제 실패로 확인한다. Freshness는 별도 Service/Reference 회귀에서 검증하며 가상 시간·고정 observation Clock·실제 wall-clock/네트워크 종료를 혼동하지 않는다.
- 신규 계약 검증: Executor 5개는 요청당 2·다중 요청 전체 4, 응답 없는 작업의 유한 대기, 전체 자리 소진 시 미시작, 실제 종료 전 자리 미반환/이후 재사용, deadline 직전/경계 결과·후속 호출 차단, shutdown/스레드 종료 및 Monitoring/Dispatch 독립 실행을 검증한다. Latency 14개는 1/5/10/20/40대 정상/timeout, 빠른 후속 후보 보존, 늦은 결과의 실제 종료 후에도 재조회 reference 불변, metadata+roster 예산 누적/전체 HELD·JSON 응답, 동시 User/Route identity·selectionKey·reference 문맥 격리, roster 오류 정책을 검증한다. MutableClock fixture는 volatile로 가시성을 보장하고 병렬 worker에서 시각 증가를 경쟁시키지 않는다.
- 선택 테스트 결과: Java/Gradle JVM **21.0.12.1**, Wrapper **8.14.3**. Backend 개별 `--tests`/`--offline` 실행에서 `BoardingCandidateDetailExecutorTest` **5개**, `BoardingCandidateLatencyTest` **14개**, `BoardingCandidateServiceTest` **31개**, `BoardingCandidateReferenceStoreTest` **9개**, `BoardingCandidateRevalidationTest` **36개**, 서로 다른 테스트 합계 **95개 최종 모두 통과, 실패/오류/skip 0개**다. 최초 92개 실행의 신규 테스트 오류 2건(완료 순서 가정·Mock 재설정)을 수정했고 영향받는 클래스만 재검증했다. 최종 Service/Latency 변경 후 3개 클래스 81개가 통과했으며 변경 없는 Executor 5개·Reference 9개는 기존 통과 결과를 유지했다. Flutter `boarding_candidates_test.dart`·`boarding_candidate_client_test.dart`·`boarding_candidates_view_test.dart` **29개 모두 통과**, 신규 DTO/Widget 계약 2개 포함. `git diff --check` 통과.
- 변경·잔여 검증: 제품 Backend는 Service·ReasonCode DTO·신규 전용 Executor, Flutter는 후보 사유 설명만 변경했다. 직접 관련 테스트와 `docs/api.md`·`docs/architecture.md`·이 기록을 동기화했다. 실 Provider/계정별 quota·실제 지연과 차량 수/시각 분포·다중 인스턴스 부하·네트워크 취소/body 종료·XML/JSON CPU/메모리·운영 shutdown budget·iPhone 수동 재조회는 미검증이다. 전체 suite·DB 통합·실제 Provider/FCM/Device·개발 MySQL 변경·Backend/터널 실행은 하지 않았다. Dependency·Schema/Migration·Credential/환경 설정·devlog·다른 AUD 항목을 보존하고 `main` 및 TASK-816 `[ ]`를 유지한다. Git add/commit/push·reset/stash/clean/checkout·branch 생성 없이 AUD-07 결과만 보고한다.

AUD-06 처리 (2026-10-30): `BackendAuthClient._post()`에 Timeout이 없어 Login·Refresh·Logout의 HTTP Future가 끝나지 않고, `AuthSession._logout()`도 `_refreshInFlight`를 무기한 기다릴 수 있었다. 제품 수정 전 MockClient·Completer와 실제 BackendAuthClient/AuthSession을 연결한 **4개 재현이 모두 실패**했다. 가상 시계로 15초를 진행해도 세 인증 요청과 공유 Refresh 대기 중 Logout이 미완료임을 확인했다. 실제 Backend/네트워크 장기 정지 실측은 아니다.

- 수정 범위·제한 시간: 제품 코드는 `backend_auth_client.dart`만 변경해 공통 `_post()`의 HTTP Future에 Login·Refresh·Logout 각각 기본 **15초**를 적용하고 생성자 `timeout` 주입을 허용했다. Alarm과 같은 지연 허용 수준을 선택했으며 Device API 8초·beforeLogout hook 10초와 별개다. Hook → Refresh HTTP 잔여 시간 → Logout HTTP 시간이 누적될 수 있고 Storage·일반 hook·Google SDK 대기는 보장 밖이다. 전체 Logout 시간 상한과 물리적 연결 취소를 보장하지 않는 실행 계약은 [Architecture](architecture.md#flutter-인증-http-timeout-task-816--aud-06)가 소유한다.
- 인증·늦은 결과: `TimeoutException`을 HTTP 401·5xx/network와 구분해 그대로 전달하며 기존 UI의 안전한 로그인 실패 안내를 유지했다. Timeout된 HTTP 결과는 Token Pair 파싱·AuthSession 후속 단계에 전달되지 않는다. Login Timeout은 비인증·Pair 미저장·generation/identity 불변이며, 늦은 성공이 새 로그인 전후에 도착해도 인증을 만들거나 덮어쓰지 않는다. Refresh Timeout은 기존 Pair·Storage·generation·identity를 보존하고 늦은 성공/401/network 오류로 저장·인증 제거·Replay하지 않는다. 기존 401 인증 무효화·5xx/network 일시 실패·Storage 저장/삭제 실패 계약은 유지한다.
- 공유 Refresh·Logout: concurrent 401의 HTTP Refresh는 1회이며 Timeout 때 모든 대기자가 같은 오류로 종료되고 `_refreshInFlight`가 정리된다. 별도 호출이 새 flight를 시작할 수 있음을 확인했으며 자동 재전송은 추가하지 않았다. Refresh 무응답 중 Logout은 HTTP Timeout 뒤 확인 가능한 기존 Token으로 서버 정리를 진행하며 로컬 Pair를 삭제한다. 제한 시간 내 Rotation 중 Logout은 기존 `_rotatedRefreshTokenForLogout`으로 새 Refresh Token을 revoke하고 늦은 Pair를 저장하지 않는다. Backend Logout Timeout은 `BackendLogoutCleanupException`이며 로컬 비인증 상태를 유지하고 `_logoutInFlight`·`_loggingOut` 정리 후 새 Login이 가능하다. 늦은 Logout 204도 새 로그인 상태를 바꾸지 않는다.
- 요청 수·결과 불명확성: 각 무응답/늦은 응답 테스트의 인증 POST는 **1회·자동 재시도 0회**다. 공유 보호 API 2개는 Refresh 1회를 공유하고 Backend Refresh Timeout 뒤 보호 요청 Replay는 **0회**다. HTTP Refresh Timeout 뒤 기존 Token이 서버에서 여전히 유효하다고 보장하지 않는다. 서버 Rotation 성공 후 응답 유실이면 새 Token을 알 수 없고, 제시된 Token Hash만 삭제하는 기존 Logout 계약으로는 알 수 없는 새 Session의 revocation을 보장할 수 없다. Token 합성·추측·새 복구/자동 재전송 계약을 추가하지 않았으며 Logout Timeout도 서버 정리 성공/확정 실패로 단정하지 않는다.
- 선택 검증: 신규 **19개**(BackendAuthClient 9·AuthSession 8·AuthenticatedApiClient 1·Alarm Timeout 1)를 포함해 `backend_auth_client_test.dart` **15개**, `auth_session_test.dart` **28개**, `authenticated_api_client_test.dart` **23개**, 직접 영향받는 `alarm_api_client_timeout_test.dart` **18개**, 합계 **84개**를 검증했다. 최초 선택 실행 83개 중 79개 통과·신규 Widget 4개가 mock callback의 `expect`/`pump` guard 충돌로 실패했다. 해당 callback을 `expectSync`로 수정하고 Login 늦은 응답의 새 로그인 전/후 경합을 분리한 5개만 재실행해 모두 통과했으며 이미 통과한 79개는 반복하지 않았다. 정상 Login/Refresh/Logout·Rotation·single-flight·최대 1회 Replay·기존 Storage 실패 계약을 포함한다. AUD-05의 A만 만료되는 테스트는 최초 401을 1초 지연시켜 Alarm 15초와 Refresh HTTP 16초의 deadline을 구분했고, B 정상 Replay 및 아직 유효한 Refresh 401의 기존 인증 제거 정책을 유지했다. 별도로 Refresh HTTP 자체의 15초 Timeout이면 자신의 Alarm 예산이 남은 B도 실패함을 확인했다. 변경 Dart 5개 format 및 범위 한정 정적 분석(No issues), `git diff --check`를 확인했다.
- 미검증·범위: 실제 지연/단절 네트워크·서버 Rotation/Logout commit 및 응답 유실·TCP 연결 종료·iPhone 로그인/로그아웃 UI·Google SDK/Storage/hook 자체의 장기 대기는 미검증이다. 전체 Flutter/Backend suite·관련 없는 BoardingSession suite·실제 Google/Backend/Provider/FCM/Device 호출·개발 MySQL 변경·서버/터널 실행은 수행하지 않았다. AuthSession·UI·Backend/API·Dependency·Migration/Schema·Credential/환경 설정·devlog 변경과 Git add/commit/push 없이 main을 유지한다. 기존 AUD-01·02·03·04·05·08·09 기록 및 TASK-710·711·814·908 등의 상태를 보존하고 TASK-816은 `[ ]`를 유지한다. AUD-07·10·11·12·13 등 다른 항목으로 진행하지 않는다.

AUD-05 처리 (2026-10-29): `AlarmApiClient`는 바깥 Future에만 Timeout을 적용해 호출자에게 TimeoutException을 반환한 뒤에도 하위 `AuthenticatedApiClient`가 늦은 401을 Refresh/Replay할 수 있었다. 제품 수정 전 실제 `AlarmApiClient`·`AuthenticatedApiClient`·`AuthSession`·`BackendAuthClient`와 Mock HTTP/Completer를 연결한 재현 테스트가 기대 요청 1회·Refresh 0회에 대해 **Create 요청 2회·Refresh 1회**로 실패했다. 실제 Backend/인터넷 없이 기본 15초를 Widget 테스트의 가상 시계로 진행한 코드 재현이며 운영 장애 실측은 아니다. 재현 fixture의 비동기 expectation guard 충돌을 제거한 뒤 정확한 Replay 횟수를 확인했다.

- 수정 범위·요청 수명: `AlarmApiClient` 내부의 작은 공통 요청 함수로 여섯 API를 연결했다. 요청마다 독립적인 active·시작 Auth generation을 기존 `expectedGeneration`/`isRequestCurrent`에 전달하고 종료 시 `finally`로 해당 요청만 무효화한다. 최초 응답 뒤 Refresh 전·Refresh 완료 뒤 Replay 전·Replay 응답 뒤의 기존 유효성 검사를 재사용하므로 `AuthenticatedApiClient`·`AuthSession` 변경은 없다. 실행 경계와 물리적 취소 비보장은 [Architecture](architecture.md#flutter-alarm-http-요청-수명과-timeout-task-816--aud-05)를 따른다.
- 정상 호환·동시성·예산: 정상 401은 원래 Method/URI/Body/Content-Type을 보존해 회전 Token으로 최대 1회 Replay한다. 기본 15초는 최초 요청·공유 Refresh·Replay 전체 예산이며 단계마다 다시 시작하지 않는다. Timeout 뒤 늦은 401은 Refresh/Replay 0회, Refresh 도중 Timeout은 해당 요청의 Replay 0회다. 공유 Refresh·다른 정상 요청·Token 저장/회전·login identity·Refresh 401의 기존 인증 실패 처리는 유지한다. Logout 진행·다른 계정·같은 계정 재로그인 후 이전 Mutation도 Replay되지 않는다.
- UX·인접 보호: 실제 Alarm 생성 화면과 실제 HTTP Client를 연결해 Timeout 뒤 늦은 201이 성공 콜백·Activate·자동 Create를 발생시키지 않고 Unknown Outcome·목록 확인 안내를 유지함을 확인했다. 기존 Widget 회귀로 Create 성공/Activate 실패의 원래 ID 보존·INACTIVE 상세 복구·수동 재활성화·정상 활성화 및 Push 준비 흐름도 확인했다. 서버에 이미 전달된 Mutation이 실패하거나 취소됐다고 확정하지 않는다. BoardingSession·Device·PushRegistrationService와 인증 HTTP 자체 Timeout 정책(AUD-06)은 변경하지 않았다.
- 선택 검증: 신규 `alarm_api_client_timeout_test.dart` **17개**, 기존 `alarm_api_client_test.dart`·`authenticated_api_client_test.dart`·`auth_session_test.dart`·`alarm_screen_test.dart`·`alarm_creation_push_test.dart` **96개**, 합계 **113개**를 선택 실행해 통과했다. 여섯 API의 늦은 401·무응답 15초 종료·정상 401의 원래 요청 Replay·공유 Refresh 도중 요청별 만료·독립 요청·Logout/계정 전환·최초 요청 10초 후 Refresh/Replay를 포함한 15초 총 예산·늦은 Refresh 401·늦은 Create 성공 UI를 검증했다. 신규 파일 최초 실행의 Dart cascade 문법 오류를 수정한 뒤 해당 파일만 재실행했으며 이미 통과한 기존 96개는 반복하지 않았다. 변경 Dart 2개 파일 format과 범위 한정 정적 분석(No issues), `git diff --check`를 확인했다. 새 Dependency나 Timeout 주입 API 없이 가상 시계와 Completer를 사용했다.
- 미검증·보호 범위: 실제 지연 네트워크·iPhone Timeout UI·연결 종료·서버 Mutation의 처리 시점 및 응답 유실은 미검증이며 실제 기기 검증을 대체하지 않는다. 전체 Flutter/Backend suite, 변경 없는 BoardingSession·Device 개별 suite, 실제 Backend/Provider/FCM/Device 호출·개발 DB 변경·서버/터널 실행은 수행하지 않았다. Dependency·Backend API·Schema·Migration·Credential·환경 설정·devlog 변경과 Git add/commit/push 없이 main을 유지한다. AUD-01·02·03·04·08·09 기록 및 TASK-710·711·908 등 기존 상태를 보존하고 TASK-816은 `[ ]`를 유지한다. AUD-06 및 다른 미처리 항목으로 진행하지 않는다.

AUD-01 처리 (2026-10-29): `main.dart`에서 생성한 `beforeActivate`가 `AlarmHome`의 기존 상세 화면에만 전달되고 `BusRouteStopScreen`에는 연결되지 않아 최초 `알람 시작`이 Push 준비 없이 Create → Activate를 실행했다. 제품 수정 전 실제 AlarmHome 생성 경로 Widget 테스트에서 Create 1회·Activate 1회·준비 hook 0회와 호출 순서 `['create', 'activate']`를 확인해 기대 순서 검증이 실패했다. Fake 기반 코드 재현이며 실제 iPhone 장애 실측은 아니다.

- 수정 연결·사용자 선택: 기존 콜백을 신규 생성 화면에도 전달해 중복 입력 차단 → Push 준비 1회 → Create → Activate를 연결했다. 미결정 권한 안내의 취소/닫기는 API 없이 선택 화면으로 돌아가 재시도할 수 있고, 권한 거부·Provider/Device 등록 실패는 기존 안내·설정 열기·명시적 계속 진행/취소를 유지한다. 성공 안내와 Push 서비스 미제공 환경의 null 콜백 계약을 보존했다. Alarm 안내만 버스 도착 기능에 맞게 수정하고 BoardingSession 문구·준비 정책은 유지했다. 상세 실행 경계는 [Architecture](architecture.md#flutter-도착-alarm-최초-생성과-push-준비-task-816--aud-01)를 따른다.
- 비동기 보호·기존 복구: 준비 완료 후와 Create/Activate 응답 뒤에 Auth generation·User/login identity·navigation epoch·mounted·요청 generation·Route/occurrence 문맥을 검증한다. 위젯 dispose 전의 늦은 준비 성공/사용자 동의와 Create 응답도 후속 API·화면 이동으로 이어지지 않는다. 준비 예외는 재시도 가능한 오류로 분리하며 Create Unknown Outcome으로 표시하지 않는다. Create 성공/Activate 실패는 원래 ID의 INACTIVE 상세로 복구하고 수동 활성화 시 준비를 다시 실행한다. Create 결과 불명확은 자동 재전송 없이 목록 확인하는 기존 정책을 유지한다.
- 선택 검증: 신규 `alarm_creation_push_test.dart` **32개**와 직접 관련 기존 회귀 **45개**, 총 **77개**를 분할 선택 실행해 최종 통과했다. 기존 대상은 `alarm_screen_test.dart`(준비 후 Partial Success/Unknown Outcome 검증 보강), `bus_route_stop_screen_test.dart`, `notification_lifecycle_test.dart`(Alarm 안내 기대 문구 수정), `phase6_regression_test.dart`, 인접 `boarding_push_readiness_test.dart`다. 최초 새 테스트의 import·fixture 초기화 위치·Widget 조회·탭 시작 방식 오류는 수정하고 실패/추가 건만 재검증했다. Push 준비 순서·권한 미결정/거부·Provider/등록 실패의 진행/취소·중복 입력·로그아웃/다른 계정/같은 계정 재로그인·navigation epoch 변경/Route 교체/dispose·상세 활성화/재활성화·Boarding 준비 회귀를 확인했다. 변경 Dart 6개 파일의 format과 범위 한정 정적 분석, `git diff --check`를 확인했다.
- 미검증·범위: 실제 iPhone의 OS 권한 요청/설정 복귀, APNs/FID·Device 등록, 최초 생성/상세 재활성화와 foreground/background/terminated 수신·tap은 미검증이며 TASK-710/711/908의 실기기 검증을 대신하지 않는다. 전체 Flutter 테스트/분석·Backend 테스트·실제 Provider/FCM/Device 요청·개발 MySQL 변경·서버/터널 실행은 수행하지 않았다. Backend/API·Dependency·Schema·Migration·Credential·PushRegistrationService 변경과 Git add/commit/push 없이 main을 유지한다. 기존 AUD-04·AUD-08·AUD-03·AUD-02·AUD-09 기록 및 다른 체크박스를 보존하며 TASK-816은 `[ ]`를 유지한다.

AUD-09 처리 (2026-10-29): 서울 detail Mapper의 `LocalDateTime.parse()`에서 발생한 `DateTimeParseException`이 `pollSeoul()`의 차량별 `TransitProviderClientException` catch를 벗어나 group 단위 RuntimeException catch로 전파됐다. 따라서 이미 수집한 정상 차량의 Observation도 `pollGroup()` 평가에 도달하지 못하고 이후 차량 조회와 해당 group의 재시도 등록까지 중단될 수 있었다. 수정 전 정상 A/B/C 중 오류 차량을 첫째·중간·마지막에 배치한 Scheduler 테스트 3건이 모두 Evaluator 미호출로 실패했고, 로그에서 `DateTimeParseException`과 group 실패를 확인했다. 고정 시각·Mock Provider의 코드 재현이며 운영 장애 실측은 아니다.

- 수정 범위·실패 분류: Mapper가 잘못된 날짜 형식·불가능한 날짜를 STRICT 파싱의 명시적 오류로, NULL item·누락/불일치 차량 ID를 명시적 입력 검증 실패로 처리해 기존 `SEOUL_BUS` / `getBusPosByVehIdItem` / `PROTOCOL` 계약으로 전달한다. 광범위한 RuntimeException catch나 새로운 예외 계층은 추가하지 않았다. 내부 호출 인자 오류는 Provider 실패로 숨기지 않는다. 정상 응답·선택 NULL·중복 관측의 기존 판단은 유지한다. 상세 실행 계약은 [Architecture의 모니터링 모델](architecture.md#7-모니터링-모델)을 따른다.
- 집계·presence·재시도: 한 detail의 모든 item 변환이 끝나야 집계하므로 실패 차량의 부분 관측과 다른 차량 ID가 유입되지 않는다. 기존 차량별 catch가 정상 차량 목록을 보존해 전체 roster presence와 즉시 평가하고, 실패 차량의 이전 검증 위치·Provider 시각·cycle·Event 이력은 유지한다. 기존 5초 지연 뒤 실패 차량만 1회 재시도하며 정상 차량·roster를 불필요하게 재조회하지 않는다. 최종 실패는 다음 일반 cycle을 기다리고 정상 empty는 실패/재시도로 바꾸지 않는다. 여러 Alarm/Route의 동일 vehicle raw 응답 공유와 재시도 중복 제거, `evaluatePartial()`·AUD-02의 동일/역행 시각 차단·Lifecycle/generation·Event 중복 방지는 유지한다. 기존 로그의 Route/차량·PROTOCOL·최초/최종 시도 표시도 확인했다.
- 추가 테스트: Mapper 6개 method/17건과 Scheduler 6개 method/13건으로 총 12개 method/30건을 추가했다. 날짜 형식·불가능한 날짜, 누락/불일치 ID·NULL item, 응답 전체 거부, NULL/공백 선택 필드, 정상 empty, 오류 차량의 반복문 위치, 차량별 재시도 성공/반복 실패, 여러 Alarm/Route 공유와 정상 Route 독립성, 실제 Mapper/Evaluator의 정상 ONE_STOP_BEFORE·ARRIVED 후보 및 Lifecycle 적용 경계 전달, 이전 위치/presence/cycle 보존과 다음 정상 관측 복구, 같은 시각·역행 재시도 차단, 충돌 중복 관측의 기존 UNKNOWN 처리를 검증했다. Lifecycle 적용 경계는 Mock이며 실제 DB/Event/Delivery 저장 검증으로 간주하지 않는다.
- 선택 실행 결과: Java/Gradle JVM 21·Wrapper 8.14.3을 확인했다. 수정 전 재현 3건 실패 후 수정된 `SeoulBusTransitObservationMapperTest` 19건·`BusAlarmMonitoringSchedulerTest` 35건과 직접 연결된 `BusAlarmEvaluatorTest` 65건·`BusAlarmLifecycleServiceTest` 6건을 선택 실행해 총 125건이 실패/오류/skip 없이 통과했다. 성공한 테스트의 불필요한 재실행은 하지 않았다.
- 미검증 운영 조건·보호 범위: 실제 서울 malformed detail·dataTm·차량 ID 불일치의 발생 빈도와 운영 retry 지연/응답 공유·실운행 위치/GPS·관측 누락/복구, Notification 저장과 실제 기기 수신은 검증하지 않았다. Backend/Flutter 전체 suite·Application Context/DB 통합 테스트·관련 없는 BoardingSession/TAGO 테스트, 실제 Provider/FCM·개발 MySQL 변경·서버/터널 실행은 수행하지 않았다. Dependency·Migration·Schema·설정·BoardingSession·TAGO·Evaluator/Lifecycle 구현 변경과 Git add/commit/push 없이 main을 유지한다. 기존 AUD-04·AUD-08·AUD-03·AUD-02 기록과 다른 Task 체크박스를 보존하며 TASK-816은 `[ ]`를 유지한다.

AUD-02 처리 (2026-10-29): `isMonotonic()`은 Stop order만 검사하고 `hasNewProgressEvidence()`는 Provider 시각의 불일치만 검사해 역행 시각을 새 진행으로 허용했다. 동일 시각의 진행도 ACTIVE의 `observe()`에서 이전 위치를 덮어썼고, 서울 직접 ARRIVED·ONE_STOP_BEFORE·FOLLOW_UP의 ONE_STOP_AFTER는 공통 시간 검증 없이 후보를 생성할 수 있었다. 수정 전 새 Evaluator 회귀 12개가 모두 실패해 이벤트 오생성과 상태 덮어쓰기를 재현했다. 이는 고정 시각·격리 관측의 코드 재현이며 운영 장애 실측은 아니다.

- 수정 기준·범위: `BusAlarmEvaluator`의 ACTIVE/FOLLOW_UP 공통 단조성 검증에 수신 순서와 엄격한 Provider 시각 증가를 추가하고 `hasNewProgressEvidence()`에 같은 검증과 Stop order 증가를 요구했다. 상세 실행 계약은 [Architecture의 모니터링 모델](architecture.md#7-모니터링-모델)을 따른다. `VehicleTrackingState`의 메모리 전용 `lastKnownProviderDataTime`으로 중간 NULL 관측 뒤의 동일·역행 시각 우회도 차단한다. FOLLOW_UP의 Stop ID/order 충돌이 이 검증 기준을 덮어쓰지 않도록 보존 경로를 사용한다.
- 정상 호환성·복구: TAGO의 Provider 시각 부재와 서울 `dataTm` 누락은 계속 허용하며 원본 시각을 합성하지 않는다. 이전 state가 없는 Baseline/신규 차량의 서울 직접 ARRIVED·TAGO GPS 근거, 기존 FOLLOW_UP 복원 UUID·기한을 유지한다. 거부 시 검증된 위치·시각·before 이력·Event 집합·cycle을 유지하고 presence만 기존 정책으로 갱신한다. 정상 관측으로 같은 cycle에서 복구하며 실제 부재의 grace 만료 후에는 기존처럼 새 cycle을 시작한다. Event 우선순위·identity·generation·Lifecycle/DB 재시도 정책은 변경하지 않았다.
- 추가 검증: Evaluator 39개와 실제 Evaluator/Mapper를 연결한 Scheduler 1개를 추가했다. 시간·정류장 순서 조합, 동일 위치의 신호 변경, NULL 및 NULL 사이의 시각 기준 보존, freshness/future, 잘못된 관측 뒤 정상 복구, 차량 중복 Snapshot/독립성, Baseline 직접 근거, 네 Event의 정상 발생·차단·중복 억제, FOLLOW_UP 복원·이관·충돌 기준 보존, roster presence/실제 부재, 서울 detail 실패→오래된 재시도 차단→정상 재조회 복구를 검증했다. Mock Provider와 고정 시각만 사용했다.
- 선택 실행 결과: Java/Gradle JVM 21·Wrapper 8.14.3을 확인하고, `BusAlarmEvaluatorTest` 64개·`BusAlarmMonitoringSchedulerTest` 22개·`BusAlarmLifecycleServiceTest` 6개·`TagoTransitObservationMapperTest` 4개·`SeoulBusTransitObservationMapperTest` 2개로 98개가 통과했다. 추가한 FOLLOW_UP 충돌 보존 1개만 별도 실행해 서로 다른 총 99개(Evaluator 65개)가 실패/오류/skip 없이 통과했다. 첫 선택 실행은 새 테스트 annotation 표기 오류로 compile 단계에서 중단됐으며 수정 후 위 결과를 확인했다. 이미 통과한 테스트는 반복하지 않았다.
- 미검증 운영 조건·보호 범위: 실응답의 동일 `dataTm`/다른 `stOrd` 발생 빈도, 실제 운행·GPS·지연 분포, TAGO 원본 생성시각 부재의 upstream freshness, restart 전 메모리 Provider 시각과 복구 관측의 비교 및 실제 Notification 수신은 검증하지 않았다. 전체 Backend/Flutter suite·Application Context/DB 통합 테스트, 실제 Provider/FCM·개발 MySQL 변경·서버/터널 실행은 수행하지 않았다. Dependency·Migration·Schema·공통 Observation·BoardingSession·Scheduler 구성 변경과 Git add/commit/push 없이 main을 유지한다. 기존 AUD-04·AUD-08·AUD-03 기록과 다른 Task 체크박스를 보존하며 TASK-816은 `[ ]`를 유지한다.

AUD-03 처리 (2026-10-29): 알려진 서울 포함 노선유형은 노선-정류장 행이 없어도 `scope()`가 INCLUDE를 반환하고, 공통 snapshot은 빈 occurrence를 허용해 기존 occurrence 전체 삭제와 complete 성공 처리로 이어지는 경로를 확인했다. 수정 전 격리 CSV 테스트가 예외 미발생으로 실패해 현재 코드의 결함을 재현했다.

- 수정 위치·기준: `SeoulCsvMetadataSource.fetchCompleteSnapshot()`에서 기존 scope 확정 뒤 포함 Route의 occurrence가 최소 1개인지 검증한다. 전체 실패 처리와 제한된 오류 로그의 실행 계약은 [Architecture의 transit 절](architecture.md#transit)을 따른다. 공통 snapshot·TAGO·Alarm·BoardingSession 로직과 기존 포함/제외 정책은 변경하지 않았다.
- 데이터·상태 보존: 신규/기존 빈 Route와 정상/빈 Route 혼합 입력은 첫 DB 변경 전에 전체 거부해 기존 Route·Stop·occurrence의 모든 row와 `lastCompleteSyncAt`을 보존한다. 잘못된 신규 데이터·complete 상태를 생성하거나 성공 로그를 남기지 않으며, 동일 bootstrap/source에 정상 임시 CSV를 다시 제공하면 재시도에 성공한다. 기존 노선별 transaction 정책은 유지하며 DB 실패 노선은 rollback하고 cleanup·완료 시각 갱신을 차단한다. 이미 commit된 다른 Route까지 되돌리는 provider 전체 원자성을 새로 도입하지 않았다.
- 추가 검증: Parser 2개·격리 MySQL 통합 10개를 추가했다. 7개 포함 유형의 0개 occurrence, 오류 예시 10개 제한, 기존 데이터 보존, 신규 빈 Route, 정상/비정상 혼합, occurrence 없는 경기·인천 제외 Route, 정상 metadata 갱신·삭제 대상 cleanup·ID/반복 방문 보존·1개 occurrence 허용, 실패 후 복구, 정류장 join 누락·CSV 파싱 오류, 실제 DB trigger로 occurrence 삭제 뒤 INSERT 실패 및 Stop UPDATE 실패의 rollback을 검증했다. 로그에서는 검증 실패·complete 미승인·DB 반영 미시작과 성공 로그 부재를 확인했다.
- 선택 실행 결과: `SeoulCsvMetadataSourceTest` 13개, `SeoulCsvMetadataSyncIntegrationTest` 10개, `BusMetadataSyncTransactionIntegrationTest` 3개, `BusMetadataBootstrapServiceTest` 3개, `BusMetadataSyncServiceTest` 2개로 서로 다른 31개가 최종 통과했다. 최초 31개 중 28개 통과/3개 실패 후 신규 테스트의 MySQL trigger 생성 설정과 ID 비교 범위를 보정해 실패한 3개만 재실행했고 실패/오류/skip 없이 통과했다. Java/Gradle JVM 21·Wrapper 8.14.3과 Java compile을 확인했다. 변경 없는 성공 테스트는 반복 실행하지 않았다.
- 잔여 실제 검증·범위: 실제 서울 3종 CSV의 전체 규모 import·처리 시간/메모리, 운영 파일 배포 중 누락·I/O 실패, DB 연결 단절·process 강제 종료/commit 응답 유실은 미검증이다. Backend/Flutter 전체 테스트, 관련 없는 Alarm·BoardingSession·TAGO 전체 테스트, 실제 Provider/FCM·개발 DB 변경·실제 CSV 수정·서버/터널 실행은 수행하지 않았다. Dependency·Migration·Schema 변경과 Git add/commit/push 없이 main을 유지한다. AUD-04·AUD-08 처리 기록과 다른 Task 상태는 유지하며 TASK-816은 `[ ]`를 유지한다.

AUD-04 처리 (2026-10-29): 두 `@Scheduled`가 전용 Scheduler 없이 기본 단일 스레드 실행 자원을 공유하는 구성을 확인하고, [Monitoring/Dispatch 전용 단일 스레드 Scheduler](architecture.md#scheduler-실행-자원-분리-task-816--aud-04)로 분리했다. 기존 직렬 실행 guard·DB lock/commit·Session runtime gate·timeout/retry를 유지하고 Monitoring 종료 신호 처리를 추가했다. 신규 동시성 12개를 포함한 관련 10개 클래스 288개를 선택 검증했다. 최초 285개 통과/3개 실패 후 새 테스트의 transaction fixture를 수정했고, 해당 Dispatch 통합 73개와 Scheduler 5개가 재실행에서 모두 통과했다. 변경 없는 나머지 210개는 재실행하지 않았다. Backend·Flutter 전체 테스트, 실제 Provider/FCM·개발 DB 변경은 수행하지 않았다. 실제 부하 지연 분포와 운영 종료 budget·강제 종료 복구 검증은 TASK-810/811에 남는다. 당시 AUD-08은 미수정이며 TASK-816은 `[ ]`를 유지한다.

AUD-08 처리 (2026-10-29): 재확인은 검증된 checkpoint만 저장하고 준비 판단을 누락하며, 다음 동일 관측은 새 진행이 아니어서 Event 기회를 놓치는 경로를 수정했다.

- 수정 경로: `BoardingSessionService.reconfirm()`의 Session lock transaction에서 checkpoint·TRACKING/revision flush 뒤 `BoardingNotificationService.recordReconfirmedPreparation()`을 호출한다. 생성의 `recordInitialPreparation()`과 Event identity/TTL/최초 recipient 저장을 재사용하며 새 진행을 합성하지 않는다. 이전 checkpoint와 수신/제공시각·위치·방향이 충돌하면 즉시 알림을 생성하지 않는다. mutation 전 runtime 허용을 닫고 commit 응답 유실 시 정확한 결과 snapshot·Event ID를 읽기 전용으로 확인한다. NULL offset의 기존 재확인·준비/목적지 정책과 기존 Delivery는 보존한다.
- 검증 시나리오: offset 2/1·2→1 대체·구간 이전, 실제 Validator의 동일 차량/occurrence/시각/운행 종료 차단, 목적지 현재/이후의 미검증 재개와 소급 알림 차단, 과거 checkpoint 충돌·미검증·stale/future, 동일 baseline의 원래 TTL, 동일/새 Polling, 동시 재확인과 늦은 Polling 반영, 취소/만료 경합, 최초 INSERT 전 TTL/Session 기한, Event 저장 후 FK 실패·두 번째 Delivery UNIQUE 실패의 전체 rollback, commit 응답 유실/rollback·stale revision 재시도, commit 확인 전 gate 차단, 0 recipients 소비·소급 Delivery 금지, 기존 NULL offset 준비 ON/OFF·Event 소비·Delivery 보존·DESTINATION_REACHED 완료를 검증했다.
- 선택 실행 결과: 신규 37개를 포함한 서로 다른 406개 사례가 최종 통과했다. `BoardingSessionApiIntegrationTest` 128개, `BoardingProgressEvaluatorTest` 66개, `BoardingTrackingIntegrationTest` 139개, `BoardingDispatchIntegrationTest` 73개가 범위다. 최초 선택 397개는 393개 통과/4개 실패였고, 새 테스트의 목적지 기대값·TTL 훅과 추가한 운행 종료 fixture를 수정해 실패한 테스트 및 신규 테스트만 재실행했다. 마지막 선택 8개가 실패/오류/skip 없이 통과했으며 변경 없는 성공 테스트는 전체 재실행하지 않았다. compileTestJava의 신규 lambda fixture 오류도 수정했다. 실제 Provider·FCM은 Mock이며 DB는 격리된 MySQL Testcontainers만 사용했다. Backend/Flutter 전체 테스트·개발 DB 변경·서버/터널 실행·Dependency/Migration 변경·Git add/commit/push는 수행하지 않았다.
- 잔여 실제 검증: 실제 버스 SUSPENDED→동일 차량 재확인 직후 2/1 구간·다음 동일 관측/목적지 전이에서의 Event·Delivery와 iPhone 수신/tap, 무정차 stOrd 갱신·실제 지연/관측 누락은 TASK-908 및 TASK-710/711에 남는다. 자동 검증을 실제 E2E 완료로 간주하지 않으며 TASK-816·TASK-908은 `[ ]`를 유지한다. AUD-04 결과와 다른 감사 항목은 유지한다.

AUD-10 처리 (2026-10-30): 기존 `LoginService.login()`의 조회 후 INSERT는 동일 신규 Google Identity의 두 요청이 모두 User 부재를 확인할 수 있었다. 기존 `jakarta.transaction.Transactional`도 Spring TransactionInterceptor에 의해 적용됐지만, UNIQUE 충돌을 처리할 Transaction 밖 복구 경로는 없었다. 수정 전 테스트에서 `CountDownLatch`로 실제 조회 결과 두 개를 동기화한 뒤 격리 MySQL의 INSERT를 경쟁시켰고, 한 요청이 `users.uk_users_auth_provider_provider_user_id` 중복키의 `DataIntegrityViolationException`으로 실패했다. `IDENTITY` INSERT는 `save()` 안에서 실행됐으며 JDBC `SQLIntegrityConstraintViolationException` → Hibernate `ConstraintViolationException` → Spring 예외 변환 경로를 확인했다. 이는 자동 테스트 재현이며 운영 장애 발생 사실을 의미하지 않는다. 수정 전에는 중복 INSERT와 실패 전파를 확인했고 아래 User/Session 개수는 수정 후 검증 결과다.

- Transaction 선택: 별도 transactional service는 경계를 분명히 하지만 component가 추가되고, User 생성만 먼저 commit하는 분리는 Token 저장 실패 후 User가 남는다. 로그인 전체를 새 Transaction에서 실행하는 `TransactionTemplate`을 선택해 제품 변경을 `LoginService.java`에 한정했다. 신규 User와 Session의 원자성을 유지하며 실패한 Transaction을 재사용하지 않는 상세 경계는 [Architecture](architecture.md#google-최초-로그인-경쟁-복구-task-816--aud-10)를 따른다. 외부 rollback-only Transaction도 중단·복원하고 로그인은 독립 commit함을 검증했다. 기존 Controller에는 외부 Transaction이 없으며 self-invocation/proxy annotation에 의존하지 않는다.
- 복구·오류 구분: 신규 User `saveAndFlush()`의 Hibernate UNIQUE 종류와 Identity 제약 이름 메타데이터가 일치할 때만 내부 충돌로 분류한다. 원문 DB 메시지를 파싱하지 않으며 `users.` 한정 이름도 허용한다. rollback·실패 EntityManager 종료 후 새 Transaction에서 원래 Provider와 외부 ID로 한 번만 재조회하고 두 값·양의 내부 ID를 검증한다. 부재/불일치는 원래 오류로 실패하며 INSERT 재시도는 없다. 다른 Provider/외부 ID, 이메일로 계정을 연결하지 않는다. 연결 실패·Timeout·다른 UNIQUE/FK·예상 밖 오류·조회 실패·Refresh Token 저장/commit 실패는 성공으로 위장하지 않는다. 새 로그나 민감정보를 담는 응답은 추가하지 않았다.
- 실제 MySQL 검증: Java/Gradle JVM **21.0.12.1**, Wrapper **8.14.3**, Docker Desktop의 격리 **MySQL 8.4.11 Testcontainers**, 기존 Flyway Schema를 사용하고 **REPEATABLE-READ** 격리 수준을 SQL로 확인했다. Google 검증기는 Mock이며 실제 인증/Provider/FCM 호출과 개발 MySQL 접근은 하지 않았다. 두 초기 조회는 서로 다른 Transaction/영속성 컨텍스트에서 모두 부재를 반환한다. 실제 Identity UNIQUE 충돌 **1회**, SQLState **23000**/MySQL **1062**, 승자 commit·패자 rollback 완료 후 새 Transaction의 복구 조회 **1회**를 확인했다. 최종 User **1개**, 동일 내부 ID의 유효한 Access Token **2개**, 서로 다른 Refresh Token **2개**와 해당 User를 참조하는 Hash 행 **2개**를 확인했다. 각 Token의 Rotation·재사용 거부 및 한 Session Logout 후 다른 Session의 유효성도 검증했다.
- 추가 DB 시나리오: 기존 User 동시 로그인은 INSERT 없이 기존 Session과 새 Session 둘을 유지했다. 서로 다른 외부 ID와 다른 Provider의 동일 문자열 ID는 각각 User 2개/Session 2개로 분리됐다(APPLE은 Identity 저장 격리만 검증하며 Social Login을 추가한 것이 아님). 선행 INSERT와 Session 저장 후 실패를 주입한 경쟁은 선행 User/Session을 rollback하고 다른 요청의 User/Session만 각각 1개 commit했다. 실제 `refresh_tokens.uk_refresh_tokens_token_hash` 충돌은 신규 User/Session 전체 rollback과 기존 Session 보존을 확인했다. Token 생성 후 `beforeCommit` 실패도 성공 응답 없이 전체 rollback했다.
- 신규·선택 테스트: `LoginServiceTest` **23건**은 Mock 기반으로 제약 종류/이름 구분, 연결/Timeout/일반 오류 전파, rollback 이후 복구 순서, Provider/외부 ID/내부 ID 불일치·부재, 조회/Token/commit 실패 및 최대 1회 복구를 검증했다. `LoginServiceConcurrencyIntegrationTest` **8건**은 실제 MySQL UNIQUE·Transaction/영속성 컨텍스트·User/Session 영속성을 검증했다. 직접 관련 기존 `AuthControllerIntegrationTest` **23건**과 `RefreshTokenServiceTest` **4건**을 선택 실행해 Google 401/503·JWT 내부 ID·Rotation·재사용 거부·Logout·다중 Session 회귀를 확인했다. 서로 다른 총 **58건**(신규 **31건**)이 최종 실패/오류/skip 없이 통과했다. 핵심 경쟁 테스트에 격리 수준·초기 컨텍스트 분리·실패 EntityManager 종료 검증을 보강한 뒤 해당 1건만 추가 재검증했다. 초기 테스트 Spy의 추상 메서드 직접 호출 오류는 실제 Repository 위임으로 수정했다. 종료 확인 보강 중에는 정리된 Holder를 다시 읽던 테스트를 원래 EntityManager 사전 캡처로 수정하고, 승자 commit 콜백 관측은 latch로 동기화했다. 이 테스트 관측 오류들은 제품 수정 전 UNIQUE 실패 재현과 구분한다.
- 범위·미검증: Backend/Flutter 전체 테스트, 실제 Google 동시 인증·운영 부하/다수 인스턴스·운영 Connection Pool 고갈/DB 연결 단절·commit 응답 유실·실제 사용자 삭제와 복구 조회의 경합은 미검증이다. 재조회 부재/불일치는 Mock 검증이며 실제 운영 User 삭제 재현으로 간주하지 않는다. Dependency·Entity·Repository·Schema·Migration·JWT Claim·API·Refresh 정책·Credential/환경 설정은 변경하지 않았다. `architecture.md`와 이 처리 기록만 동기화하고 기존 감사 표와 AUD-11·12·13 및 다른 처리 기록을 보존했다. TASK-816은 `[ ]`이며 main을 유지하고 Git add/commit/push, branch 생성·reset/stash/clean/checkout, 서버·터널 실행은 하지 않았다.

### AUD-11 처리 (2026-10-30)

기존 감사의 **V3 User 2명 → V4 UNIQUE 실패**를 독립적으로 재현했다. 현재 V20 DB의 장애가 확인된 것이 아닌 과거 데이터 업그레이드 호환성 문제다. V1 User에는 내부 ID·Timestamp만 있고, V2 Alarm과 V3 Notification History가 이를 FK로 참조한다. V4는 값을 채우는 DML/외부 Identity 근거 없이 두 `NOT NULL` 문자열 컬럼과 복합 UNIQUE를 단일 ALTER로 추가한다. 기존 행에 암묵적으로 들어간 빈 문자열은 NULL이 아니므로 한 명은 UNIQUE를 통과하지만, 여러 명은 같은 빈 Identity 조합에서 충돌한다. SQL 성공과 Google `sub`의 의미적 정확성은 별개다.

실행 환경은 Java/Gradle JVM **21.0.12.1**, Wrapper **8.14.3**, Docker Desktop의 격리 **MySQL 8.4.11 Testcontainers**다. 기존 `db/migration`의 SQL을 그대로 사용했고 각 Migration 테스트 invocation은 새로운 Container로 격리했다. MySQL 버전과 기본 `STRICT_TRANS_TABLES`를 SQL로 확인했다. 아래 1·2·10명은 Flyway 연결에 `initSql`로 설정한 strict 조합(`STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`)과 빈 `sql_mode` 양쪽을 각각 검증했다. 다른 모든 SQL Mode 조합까지 보장하지 않는다.

| 경로 | 실제 MySQL 결과 | 의미 및 보존 확인 |
| --- | --- | --- |
| V3 User 0명 → V4 → V20 | 성공 | Identity NOT NULL/복합 UNIQUE 생성, 최신 적용 후 validation 성공·재적용 0건 |
| V3 User 1명 → V4 (2개 Mode) | 성공 | `auth_provider=''`, `provider_user_id=''`, V4 success=true. 유효한 AuthProvider/Google Identity가 아니므로 지원 대상에서 제외 |
| V3 User 2명 → V4 (2개 Mode) | 실패 | SQLState **23000**, MySQL **1062**, `Duplicate entry '' for key 'users.uk_users_auth_provider_provider_user_id'` |
| V3 User 10명 → V4 (2개 Mode) | 실패 | 2명과 동일한 UNIQUE/SQLState/error code |
| Fresh DB → V1~V20 | 성공 | 20개 Migration, Identity NOT NULL/복합 UNIQUE, 동일 Provider/ID 중복 거부·다른 Provider의 같은 ID 구분·NULL 거부 |
| 정상 Identity의 V20 DB 재적용·로그인 | 성공 | Migration 0건·History/Checksum validation 성공, 기존 User/Identity/시각/Alarm owner/Refresh 행 보존, 기존 Identity 재사용·다른 Google Identity 신규 생성 |

- 실패 후 상태: 2명·10명의 V4 단일 ALTER는 `SHOW CREATE TABLE users`가 원본과 동일했고 두 컬럼 및 복합 UNIQUE가 남지 않았다. 이는 **이 MySQL 버전의 해당 단일 ALTER 관측**이며 일반 DDL/DML rollback 보장이 아니다. V1~V3 History는 원본과 같고 V4 `success=false` 한 행이 남았다. 새로운 Flyway 인스턴스의 최신 Migration 시도는 `failed migration` validation 오류로 중단됐으며 History/Schema는 추가 변경되지 않고 V5 `refresh_tokens`도 생성되지 않았다. 무조치 자동 재시도로 복구되지 않는다.
- 데이터 보존: 1·2·10명 모두 각 User에 Alarm과 Notification History를 연결했다. 성공/실패와 재시도 뒤 내부 User ID·Alarm ID/owner·History ID/내용·생성/수정 시각·행 개수·FK 메타데이터 및 전체 join 행 개수가 원본과 같았다. User 삭제·소유권 이전·Identity 합성은 하지 않았다. 최신 Schema의 Device/NotificationEvent/BoardingSession도 내부 User FK를 사용함을 검토했으며 해당 데이터의 별도 Legacy 이관은 구현하지 않았다.
- Identity 판단·지원 정책: V3 Schema/현재 저장소에 검증된 내부 ID↔Google `sub` 매핑 근거가 없어 자동 복원은 불가능하다. A(User 0명만 자동 업그레이드)를 채택하고, B(검증된 외부 매핑이 있는 별도 수동 이관)는 근거·중복 검사·기존 ID/FK 보존·Backup/실제 Restore·로그인/소유권 검증을 전제로만 검토한다. C(모든 기존 User 자동 이관)는 채택하지 않는다. 매핑이 없는 보존 대상 DB는 자동 업그레이드를 지원하지 않는다. 개발 DB 초기화는 Backup 여부 확인과 명시적 승인 후 별도 작업이다. 상세 정책은 [Database](database.md#v3v4-legacy-user-업그레이드-지원-범위-task-816--aud-11)가 소유한다.
- 변경 판단: 기존 V1~V20 SQL/Checksum과 제품 Schema는 수정하지 않았다. 후속 Migration은 실패한 V4보다 먼저 실행될 수 없어 추가하지 않았다. 새로운 startup guard는 순서/운영 복잡도를 늘리므로 추가하지 않고, User 한 명의 잘못된 성공까지 막도록 **실행 전 수동 Preflight를 필수화**했다. 이는 자동 차단 구현을 의미하지 않는다. [Local Development](local-development.md#v4-미적용-db의-read-only-preflight-task-816--aud-11)에 현재 History/Version/V4·User/Alarm/History 개수·고아 FK·Schema·매핑/Backup/Restore 확인과 실패 후 점검 절차를 추가하고 Architecture에 실행 경계를 연결했다. Repair는 데이터 복구가 아니며 임의 Repair/Clean/Baseline/History 편집/UNIQUE 삭제로 통과시키지 않는다.
- 신규·선택 테스트: `UserIdentityMigrationIntegrationTest` **8건**(2개 일반 테스트 + 6개 parameter invocation), `LoginMigrationIntegrationTest` **1건**, 신규 **9건 전부 실패/오류/skip 0건**으로 첫 선택 실행에 통과했다. `env -u JAVA_HOME ./gradlew test --offline --tests com.stopbell.user.entity.UserIdentityMigrationIntegrationTest --tests com.stopbell.user.auth.service.LoginMigrationIntegrationTest`를 실행했다. 후자는 검증된 ExternalIdentity 경계 fixture와 실제 LoginService/DB를 사용해 Fresh 최초 로그인·기존 User 재로그인·새 Identity, 독립 Session, Refresh Hash 저장/Rotation/재사용 거부·한 Session Logout 후 나머지 Session 유효성을 확인했다. Google 검증기는 Mock이고 실제 인증/Provider/FCM 호출은 없다. AUD-10 제품 코드는 그대로 유지했고 동시성 테스트 전체를 반복하지 않았다.
- 범위·미검증: 실제 개발/운영 DB와 Docker Named Volume은 접근·수정·삭제하지 않았다. 배포 데이터에 외부 매핑이 존재하는지, 실제 수동 이관·Backup Restore drill·Google 인증/실기기, 다른 MySQL 버전/모든 SQL Mode/장애 중 DDL 중단은 미검증이다. 실제 데이터 이관이 필요한 증거가 나오면 별도 설계 결정이 필요하다. Backend/Flutter 전체 suite·서버/터널은 실행하지 않았다. Dependency·Credential/환경 설정·devlog 및 다른 감사 기록은 변경하지 않았다. `git diff --check` 통과, main 유지, Git add/commit/push·branch 생성·reset/stash/clean/checkout 없이 종료한다. TASK-816은 `[ ]`를 유지하며 AUD-12·13으로 진행하지 않는다.

### AUD-12 처리 (2026-10-30)

**판정: UTC 저장 목표와 기존 callback 구현의 불일치를 확인했으며, 수정하지 않고 TASK-814에 이관한다.** TASK-814는 착수 전부터 `[ ]`이고 UTC operational time 전체의 구현·최종 검증 소유자다. TASK-816도 `[ ]`를 유지한다. 기존 감사 표의 당시 코드 확인 수준은 보존하고 이번 실행 근거를 아래에 추가한다. 운영 DB에서 장애가 발생했거나 모든 `LocalDateTime`이 잘못됐다고 판정한 것은 아니다.

#### 원인과 컬럼별 경로

영속 시각 중심으로 `LocalDateTime/LocalDate/ZonedDateTime/OffsetDateTime/Instant.now`, `Clock.systemDefaultZone`, `ZoneId.systemDefault`, `ZoneOffset.UTC`, `ofInstant`, `clock.instant`와 관련 getter·Query·Migration을 확인했다. 직접 Host 의존 호출은 `User.onPrePersist()`/`onPreUpdate()`, `RefreshToken.onPrePersist()`, `Alarm.onPrePersist()`/`onPreUpdate()`의 인자 없는 `LocalDateTime.now()` **5개 지점**이다. `DATETIME(6)`과 callback 사용이라는 사실만으로 UTC가 보장되지 않는다.

아래 LocalDateTime/Instant 컬럼의 physical type은 모두 `DATETIME(6)`이다. UTC 보장은 현재 제품 생성·소비 경로의 정적 확인을 뜻하며 모든 생성자 입력/외부 SQL/과거 데이터를 검증했다는 뜻은 아니다. 실측한 경로는 별도 명시한다.

| 저장 위치 | Java 타입 | 생성 위치·원천 | 현재 UTC 의미 | 실제 용도·영향 | 담당 |
| --- | --- | --- | --- | --- | --- |
| `users.created_at`, `updated_at` | LocalDateTime | User JPA callback / Host | 미보장, MySQL 재현 | 감사 시각. 현재 User 생성 이후 공개 수정 경로는 없으며 테스트에서 dirty field로 PreUpdate 실행 | AUD-12 근거 → TASK-814 수정 |
| `refresh_tokens.created_at` | LocalDateTime | RefreshToken JPA callback / Host | 미보장, MySQL 재현 | 감사 시각. 만료 판단에는 사용하지 않음 | AUD-12 → TASK-814 |
| `refresh_tokens.expires_at` | LocalDateTime | RefreshTokenService.issue / jwtClock Instant를 UTC 변환 +30일 | 보장, 발급·Rotation·만료 경계 실측 | refresh의 `expiresAt > UTC now`, Logout은 Hash 삭제만 수행 | 기존 인증 유지, TASK-814 최종 검증 |
| `alarms.created_at`, `updated_at` | LocalDateTime | Alarm JPA callback / Host | 미보장, MySQL 재현 | 생성·상태 변경 감사 시각, 사용자 목록 `ORDER BY createdAt DESC` | AUD-12 → TASK-814 |
| `alarms.follow_up_started_at`, `follow_up_expires_at` | LocalDateTime | BusAlarmLifecycleService.applyArrival / Event observedAt Instant의 UTC, +5분 | 보장, 저장·재조회·Evaluator 경계 실측 | 복구·FOLLOW_UP 관측/만료 비교에서 명시적 UTC Instant 복원 | 기존 lifecycle 유지, TASK-814 최종 검증 |
| `bus_alarm_targets` | 해당 없음 | Timestamp 컬럼/자체 callback 없음 | 적용 대상 없음 | Alarm runtime과 구분, 수정 대상 아님 | 기존 모델 유지 |
| `devices.created_at`, `updated_at`; `last_registered_at` | LocalDateTime | callback의 `now(ZoneOffset.UTC)`; DeviceService의 Clock→UTC | 보장, 코드 확인 | 등록·소유권/상태 갱신. callback은 UTC지만 주입 Clock을 사용하지 않음 | 기존 TASK-702 유지, TASK-814 source 정리 검토 |
| `boarding_sessions.created_at`, `updated_at`, `expires_at`, `ended_at`, `checkpoint_observed_at`, `checkpoint_provider_data_time` | LocalDateTime | Session/TrackingStore/Notification Service Clock→UTC; Validator의 차량 Instant→UTC | 보장, 코드 확인 | 최초 수명·freshness·checkpoint 단조성·재확인·종료 비교 | 기존 TASK-903~906 유지, TASK-814 최종 검증 |
| `notification_events.observed_at`, `provider_data_time`, `event_detected_at`, `created_at`, `expires_at` | LocalDateTime | TransitEvent Instant→UTC 또는 검증된 Session checkpoint; lifecycle/BoardingNotificationService Clock→UTC | 보장, 코드 확인 | Event 시각 순서·Session 불변 기한. Alarm 감사 시각을 복사하지 않음 | 기존 TASK-707/906 유지, TASK-814 최종 검증 |
| `notification_deliveries.created_at`, `updated_at`, `last_attempt_at`, `provider_accepted_at`, `next_attempt_at`, `expires_at` | LocalDateTime | lifecycle/DispatchAttempt/Worker/DurableHandler Clock→UTC, FCM result Instant→UTC, RetryPolicy | 보장, 코드 확인 | due 조회·expiry·attempt/recovery/retry·accepted. Event/관측 기한 사용 | 기존 TASK-709 유지, TASK-814 최종 검증 |
| `bus_metadata_sync_states.last_complete_sync_at` | Instant | BusMetadataSyncService의 `Instant.now(transitMetadataClock)` | 보장, JPA 저장·읽기 실측 | 마지막 complete sync 성공 시각. 부분 실패는 갱신 근거가 아님 | 기존 TASK-507/513 유지, TASK-814 JDBC 최종 검증 |
| `bus_routes`, `bus_stops`, `bus_route_stop_occurrences` | 해당 없음 | Timestamp 컬럼/자체 now 없음 | 적용 대상 없음 | metadata business snapshot과 complete sync 시각 분리 | 기존 모델 유지 |
| legacy `notification_history.created_at` | 현재 Entity 없음 | V3 SQL만 존재, V14의 기존 데이터가 있는 경우 보존 | 과거 원천 불명확 | 현재 Event/Delivery 경로에서 쓰지 않음. 보존 데이터 해석은 미확정 | TASK-814 기존 데이터 조사 |

Provider `observedAt`은 Mapper Clock의 Instant이고 서울 `dataTm`은 명시적 `Asia/Seoul` parsing 뒤 Instant다. TAGO의 원본 시각 부재는 그대로 유지하며 수신 시각으로 Provider freshness를 합성하지 않는다. Scheduler는 Clock Instant를 사용하고 FOLLOW_UP 저장값을 UTC로 해석한다. FCM result receive time도 Clock Instant이고 elapsed/request budget은 `System.nanoTime()`이다. `FcmSmokeRunner`의 `Instant.now()`는 zone에 독립적인 smoke request 기한이며 DB callback이 아니고 실행하지 않았다. `AlarmService`의 삭제 시 Delivery 종료 시각은 `now(ZoneOffset.UTC)`로 Host 의존 결함은 없지만 Device callback과 함께 Clock 주입 일관성 검토 여지는 남는다. 인자 없는 LocalDate/ZonedDateTime/OffsetDateTime.now나 제품 `Clock.systemDefaultZone`/`ZoneId.systemDefault` 호출은 발견하지 않았다.

#### 고정 Instant와 격리 JVM 재현

기준은 **`2026-10-30T00:00:00Z`**다. JVM 시작 시 `user.timezone`을 각각 지정한 별도 Gradle Test worker/MySQL Testcontainer로 실행했다. `TimeZone.setDefault()`나 시스템 시각 변경은 없다. 테스트 thread의 `Clock.systemDefaultZone()`만 fixed Clock으로 제한해 실제 `LocalDateTime.now()`·JPA callback을 통과시켰고, MySQL 자체는 Mock하지 않았다.

| 대상 | UTC JVM | Asia/Seoul JVM | America/Los_Angeles JVM |
| --- | --- | --- | --- |
| User 생성·최초 수정 / RefreshToken 생성 / Alarm 생성·최초 수정 | 10-30 00:00 | 10-30 09:00 | 10-29 17:00 |
| +60초 시 User·Alarm 수정 | 10-30 00:01 | 10-30 09:01 | 10-29 17:01 |
| RefreshToken 만료 | 11-29 00:00 | 11-29 00:00 | 11-29 00:00 |
| FOLLOW_UP 시작 / 만료 | 10-30 00:00 / 00:05 | 동일 UTC 값 | 동일 UTC 값 |
| metadata complete sync raw DATETIME / 복원 Instant | 10-30 00:00 / `00:00Z` | 동일 UTC 값 / Instant | 동일 UTC 값 / Instant |

LA의 이 날짜 offset은 **-07:00**이다. UTC Host에서는 기존 callback도 우연히 목표값과 일치한다. 서로 다른 Host 설정으로 쌓인 감사 시각은 시간 순서가 뒤집힐 수 있으나 운영 발생 이력은 확인하지 않았다. 기존 RepositoryIntegrationTest는 생성 시각 non-null·갱신 증가 등을 확인하고 RefreshTokenServiceTest는 UTC fixed Clock에서 만료를 검증한다. 명시적으로 Host=UTC를 강제하는 설정은 없고 callback의 UTC 의미/시간대 불변성도 검증하지 않아 UTC Host에서 이 결함을 발견할 수 없는 공백이 있었다.

#### 실제 MySQL·JDBC 관측과 서비스 영향

- Java/Gradle JVM **21.0.12.1**, Wrapper **8.14.3**, Hibernate **7.4.5.Final**, Connector/J **9.7.0**, 격리 image **mysql:8.4.11**, 기존 Flyway V1~V20 및 `ddl-auto=validate`로 실행했다. 실제 개발 MySQL/Named Volume은 접근하지 않았다.
- JPA persist→flush→clear→find 및 JDBC typed raw read로 위 callback/expiry/FOLLOW_UP 값을 확인했다. 동일 SQL literal `2026-10-30 00:00:00`도 세 JVM에서 같은 User `LocalDateTime`으로 읽었다. 실제 연결의 Session offset `+00:00`/`+09:00`/`-07:00` 모두 DATETIME 날짜·시각은 유지됐고 metadata Instant도 원래 `00:00Z`로 복원됐다. DB에 timezone 정보가 추가되거나 잘못 생성된 Host 값이 자동 보정되지는 않았다.
- 비교용 connection-local 임시 테이블의 `TIMESTAMP` 및 `FROM_UNIXTIME(기준 epoch)`는 같은 offset 순서로 `00:00`/`09:00`/전날 `17:00`이 됐다. 반면 `getObject(DATETIME, LocalDateTime.class)`는 항상 `00:00`이다. 기본 Connector 설정의 `getTimestamp(DATETIME).toInstant()`는 UTC JVM `00:00Z`, Seoul JVM **전날 `15:00Z`**, LA JVM **`07:00Z`**로 해석됐다. 이는 JDBC API/설정 차이이며, 모든 JPA LocalDateTime이 자동 Instant 변환된다는 뜻은 아니다. 임시 테이블은 제거하고 Session zone은 원래 값으로 복구했다. 저장 의미와 공식 자료는 [Database](database.md#utc-목표와-현재-저장-경계-task-816--aud-12)를 따른다.
- **RefreshToken:** 생성/만료 컬럼의 의미 혼합은 확인된 결함이다. 현재 서비스는 createdAt을 만료 판단에 사용하지 않아 이 결함에 따른 조기/지연 만료는 재현되지 않았다. UTC 만료 **1초 전** refresh 성공·새 30일 기한, 기존 Hash 재사용 거부, 정확한 만료 경계의 거부/삭제, 만료된 Token의 Logout Hash 삭제를 실제 Repository/Service로 확인했다. JWT 생성기만 Mock이며 Google/인증 네트워크 검증이나 AUD-10 Session 원자성 재검증은 아니다.
- **Alarm/FOLLOW_UP:** 생성/수정 감사 시각 불일치와 목록 생성시각 내림차순 사용을 확인했다. 같은 Host에서 일정 offset만 적용되면 순서는 유지되지만 zone 변경/혼합·DST 전환 시 순서 왜곡 가능성은 추론이며 목록 역전/DST 실측은 하지 않았다. activate/deactivate/FOLLOW_UP 자체의 조건은 감사 시각을 쓰지 않는다. 기존 실제 `applyArrival` 변환을 호출해 UTC start/expiry를 저장하고 재조회한 뒤 메모리 state 없이 Evaluator가 +299초에는 유지, +300초에는 만료함을 확인했다. 이 테스트는 해당 private 변환과 재조회/Evaluator 경계 검증이며 전체 lifecycle commit·restart E2E는 아니다.
- **Notification:** Event/delivery/dispatch/retry/expiry가 별도 UTC 경로이고 User/Alarm createdAt·updatedAt을 기한 계산에 사용하지 않음을 코드로 확인했다. 이 callback 결함으로 발송 지연/조기 expiry가 발생했다는 근거는 없다. 실제 발송/Worker/Retry 테스트를 이번에 실행하지 않았으므로 전체 Notification 동작을 새로 실증했다고 주장하지 않는다.
- **BoardingSession:** 생성/수정/수명·checkpoint/Provider 시각·freshness/재확인은 Clock Instant→UTC 경로이며 대상 callback과 독립적이다. 세션 응답은 UTC offset으로 Instant를 복원한다. 이번 감사로 새 결함은 확인되지 않았으며 실제 Session 생성/재확인·Provider 추적/재시작 테스트는 재실행하지 않았다.
- **기존 데이터:** callback 컬럼은 Host 변경 시 같은 컬럼에도 다른 의미가 섞일 수 있다. 현재 Schema나 timezone 없는 값만으로 원래 Instant를 복원할 수 없다. 실제 운영 Host/DB/JDBC 설정 이력과 신뢰 가능한 대응 시각은 조사하지 않았으므로 기존 값의 timezone은 미확정이다. UTC 경로의 값까지 ±9시간 이동하거나 전체 변환하지 않는다. TASK-814에서 근거와 Backup/Restore를 갖춘 별도 이관/호환성 정책이 필요하다.

#### TASK-814 후속 구현과 검증 조건

| 대안 | 이점 | 실제 영향·비용 및 검증 필요 |
| --- | --- | --- |
| A. UTC LocalDateTime + DATETIME 유지 | 현재 Entity/Schema/대부분 Query 변경 최소화 가능, 기존 UTC 경로 재사용 | 5개 Host 호출의 원천을 UTC Clock 기준으로 통일하고 zone 없는 타입의 UTC 의미를 모든 생성·소비 경계에서 유지. Hibernate Timestamp binding/extraction·JDBC API/옵션 조합 검증 필요. 기존 데이터 해석 문제는 남음 |
| B. Instant 중심 전환 | 특정 순간 의미를 타입으로 명확히 표현, UTC offset 해석 누락 감소 | User/RefreshToken/Alarm뿐 아니라 Notification/Session의 Entity·생성자·DTO·Repository Query/비교·테스트 영향 검토. 현재 metadata의 Instant→DATETIME 왕복은 가능하지만 전체 전환/Schema 유지 가능성의 증거로 일반화하지 않음. MySQL 정밀도·JDBC 설정·직렬화·과거 데이터 이관 비용 검토 필요 |

이번에는 어느 대안도 채택하지 않았다. TASK-814에서는 다음을 수행해야 한다.

1. A/B의 범위·호환성을 결정하고 5개 callback Host 호출을 제거한다. UTC이지만 직접 system time을 쓰는 Device callback/Alarm 삭제 시각도 test 가능한 원천의 일관성 검토에 포함한다. 불필요한 BaseEntity/공통 Framework는 선행 도입하지 않는다.
2. 현재 UTC인 Refresh expiry/Rotation, FOLLOW_UP start/expiry/복구, Provider parsing, Scheduler, Notification retry/expiry, BoardingSession freshness/재확인, metadata Instant를 보존한다. 감사 시각과 업무 기한 사이에 새 의존성을 만들지 않는다.
3. 이번 **Host-local Characterization 기대값을 동일 Instant→동일 UTC 저장 기대값으로 변경**한다. 세 JVM의 raw DATETIME/JPA 왕복, 동일 DB 값의 교차 zone 읽기, 실제 도입 JDBC/Hibernate 설정, microsecond 정밀도·만료 경계와 DST 중첩/누락 시각을 검증한다. UTC runtime 설정만으로 callback/경계 검증을 대체하지 않는다.
4. 컬럼·배포 기간별 과거 시각 원천/설정 근거와 혼합 가능성을 조사하고, 원래 Instant를 알 수 없는 경우의 정책을 별도 결정한다. 근거 없는 일괄 보정/Migration 없이 Backup·실제 Restore 및 해석/소유권/기한 보존 검증을 준비한다.
5. 최종 선택·제품 수정·필수 검증 후 Owner 문서를 동기화한다. 이번 AUD-12 근거 확보만으로 TASK-814 또는 TASK-816을 완료 처리하지 않는다.

#### 선택 테스트·변경 범위·미검증

- 신규 파일 `backend/src/test/java/com/stopbell/common/UtcTimestampCharacterizationIntegrationTest.java`의 **4개 테스트**를 세 별도 JVM에서 실행해 **총 12회, 실패/오류/skip 0건**으로 통과했다. 실행 중 테스트 실패나 제품 코드 수정은 없었다. callback의 현 결함 동작을 UTC 정책으로 승인하는 회귀 테스트가 아닌 분석용 Characterization임을 클래스에 명시했다.
- 동일 클래스만 선택 실행했다. 각 실행의 보고서는 `backend/build/test-results/aud12-UTC`, `aud12-Asia-Seoul`, `aud12-America-Los_Angeles`에 분리했다. 실행 명령은 `env -u JAVA_HOME ./gradlew test --offline --tests com.stopbell.common.UtcTimestampCharacterizationIntegrationTest -I /private/tmp/stopbell-aud12/timezone.init.gradle -PauditTimezone=<zone>`이며 `<zone>`에 위 세 값을 차례로 지정했다. init script는 저장소 밖 임시 테스트 설정이고 아래 내용으로 재현할 수 있다. 제품/개인 Credential·환경 파일이나 Gradle build 설정을 변경하지 않았다.

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'user.timezone', providers.gradleProperty('auditTimezone').get()
        reports.junitXml.outputLocation = layout.buildDirectory.dir('test-results/aud12-' + providers.gradleProperty('auditTimezone').get().replace('/', '-'))
    }
}
```

- 이번 변경은 신규 테스트와 `docs/task-list.md`·`docs/database.md`·`docs/architecture.md` **4개 파일**뿐이다. 기존 AUD-11 변경을 보존했으며 작업 중 별도 주체가 해당 변경을 commit한 뒤에도 문서 본문이 시작 시점과 같음을 확인했다. 제품 Entity/Service·AUD-10 TransactionTemplate·AUD-01~11 정책·기존/신규 Migration·Dependency·Credential/환경 설정·devlog는 변경하지 않았다.
- 미검증: 실제 개발/운영 데이터·과거 Host/DB/JDBC 설정/배포 이력, 실데이터 이관·Backup Restore, DST 경계, 임의 JDBC/Hibernate 옵션 조합·다른 MySQL 버전, 모든 Entity의 교차 Host 전체 업무 흐름·Notification/BoardingSession E2E/재시작·목록 역전, 실제 Provider/Google/FCM/실기기는 검증하지 않았다. 동일 DB를 여러 JVM이 차례로 공유한 실험은 아니며 각 JVM의 새 DB에 동일 literal을 넣어 읽었다. Backend/Flutter 전체 테스트·서버/터널 실행은 없었다.
- `git diff --check`와 신규 미추적 테스트 파일의 `git diff --no-index --check /dev/null <파일>` 모두 통과했다. main 유지, Git add/commit/push·branch 생성·reset/stash/clean/checkout 없이 AUD-12만 보고 후 중단한다. 이 감사의 완료는 결함 수정이 아니라 TASK-814로의 근거 이관이며 체크박스는 모두 유지한다.

### TASK-816 문서 불일치 후속 범위

다음은 감사 당시 확인한 문서 불일치 목록이다. 1단계 정리 결과와 제품 코드에 남은 항목은 아래 후속 조건에서 구분한다.

- `README.md`: Notification Worker·권한·Device 등록·tap·atomic decision·retry 등 이미 구현된 기능을 미구현으로 안내하는 설명 검토
- Flutter 안내 문구: 목적지 이전 하차 준비 알림 정책과 달리 도달·통과 시 알림으로 안내하는 화면 문구 검토
- `requirements.md`와 기존 Alarm Push 구현: PASSED 위치·지난 정거장 보조 안내 차이를 AUD-13과 함께 처리
- UTC 저장 요구사항과 일부 Entity callback 차이: AUD-12로 추적하되 TASK-814의 구현·문서 동기화 결과를 연결하고 중복 구현·완료 처리 금지
- `package-structure.md` 등 현행 책임 설명: 제거된 차량번호 수동 입력·전체 번호 대조 절차 검토
- `requirements.md`와 `api.md`: 미확정으로 남은 정류장 표시 계약과 TASK-608A의 목적지·인접 정류장·순서 표시 계약 차이 검토. 표시 구분을 실제 운행 방향 증명으로 확대하지 않음
- `roadmap.md`와 이 Task List: 구현 완료와 제품 E2E 미완료를 구분하고 TASK-706의 당시 Dispatch 활성화 제한 등 오래된 상태 설명 검토
- `app/README.md`: 기본 생성 문구를 대신할 실행 안내 보강
- `local-development.md`: 이미 정해진 profile·설정을 Future Consideration으로 남긴 설명 검토

현재 정책을 잘못 안내하거나 실제 구현 상태를 오해하게 만드는 설명을 우선 대상으로 한다. 과거 연구 기록과 ADR의 역사적 내용은 현행 정책과 다르다는 이유만으로 변경하지 않는다. 특히 ADR-011·012와 TASK-908 증거 보고서의 번호 대조·VERIFYING·목적지 Push 기록은 명시된 대체 범위와 기록 시점을 보존한다. 문서·코드 충돌을 코드 기준으로 자동 해소하지 않는다.

#### 주요 문서 정합성 1단계 (2026-10-30)

현재 main의 코드·테스트와 기존 검증 기록을 대조해 주요 문서 10개를 최소 범위로 정리했다. Notification 구현 완료/실기기 미검증, 번호 입력 없는 명시적 차량 선택, 신규 1/2정거장 전 단일 준비 알림과 기존 목적지 Push 호환, TASK-608A 표시 계약, 실제 Package 책임·실행 설정을 구분했다. 문서 정확성 정리이며 새 설계·제품 수정·회귀 테스트 통과·TASK-816 최종 완료를 뜻하지 않는다. 아래는 남은 책임이며 새 Task 번호나 체크박스는 추가하지 않는다.

| 남은 항목 | 정적 확인 근거 / 후속 책임 |
| --- | --- |
| Flutter 초기 안내 문구 | 1단계 당시 `app/lib/features/boarding/boarding_stop_screen.dart:210`의 “선택한 정류장에 도달하거나 통과하면 알리는 기능이에요 … 진행 검증 전에는 대기로 표시해요”가 신규 준비 알림과 즉시 TRACKING을 오해하게 해 수정 제안했으며, 당시에는 Dart 수정 금지로 보존했다. 이후 아래 Flutter 안내 문구 후속 처리에서 수정·선택 검증 완료 |
| API 오류 본문 차이 | 아래 [API 500 검토](#api-500-오류-응답-계약-검토-2026-10-30) 뒤 사용자 확정 A안을 구현했다. 두 local Handler의 지정된 영속성 500은 고정 `INTERNAL_SERVER_ERROR`/message 두 필드를 반환한다. [구현·집중 검증 결과](#api-500-a안-구현-결과-2026-10-30)와 [API 계약](api.md#5-오류-형식)을 연결하며, TASK-804의 전체 오류 계약 및 TASK-816 최종 주요 회귀·완료 판정은 남아 있음 |
| AUD-12 UTC 제품 결함 | User onPrePersist/onPreUpdate, RefreshToken onPrePersist, Alarm onPrePersist/onPreUpdate의 Host 의존성은 미수정. 위 AUD-12 근거·Characterization과 TASK-814의 선택·구현·기존 데이터 호환 검증 책임 유지 |
| AUD-01~13 최종 상호 정합성 | 개별 처리 기록과 이번 문서 변경을 함께 대조하고 Backend 주요 회귀 및 Flutter 주요 회귀를 별도로 수행. AUD-13 PASSED의 검증된 정류장명/Successor Edge 수·생략·기존 data key·두 alert body 일치 계약도 회귀 확인 |
| 실제 외부/기기 미검증 | TASK-710 iPhone 전체 수신/tap·권한/등록 전환, TASK-711 Notification 최종 E2E·multi-device·durable recovery/재시작, TASK-908 신규 정책의 실제 버스·iPhone E2E 책임 유지. Provider 실측·FCM 접수·실제 기기 표시는 각각 구분하며 초기 smoke/Mock 성공으로 대체하지 않음 |
| TASK-816 최종 완료 판정 | AUD-12→TASK-814의 미수정 책임·검증 근거, 미검증 범위와 기존 TASK-710/711/814/908의 책임 분리, 필수 결함 잔여 여부를 확인한 뒤 기존 완료 기준 검토. 이번 단계에서는 TASK-814/816과 모든 기존 체크박스 유지 |

ADR-006/007/009/010/011/012, TASK-908 연구 기록과 기존 AUD 처리·테스트 실행 기록은 보존했다. AUD-11의 V4 미적용 DB User 0명 자동 업그레이드 조건과 사전 중단·별도 이관 판단, AUD-13의 이미 정확한 상세 계약은 변경하지 않았다. Backend/Flutter 테스트·빌드·서버·DB·실제 Provider/FCM/실기기 실행 없이 Markdown/경로/anchor·Controller/DTO/Class 및 diff만 검토했다.

#### Flutter 안내 문구 후속 처리 (2026-10-30)

- 기존 문구: “선택한 정류장에 도달하거나 통과하면 알리는 기능이에요. 목록에서 차량을 선택해 미리 등록하세요. 진행 검증 전에는 대기로 표시해요.”는 목적지 도달·통과를 신규 알림 시점으로 오해하게 하고, 기본 2정거장 전/선택 1정거장 전 준비 알림과 즉시 TRACKING·위치 근거 부족을 구분하지 못했다.
- 확인한 현행 계약: Requirements·API·Domain·ADR-012·TASK-908 후행 계약과 Flutter 확인/상세/Controller·관련 테스트를 대조했다. 신규 세션은 차량번호 입력 없이 단일 후보도 사용자가 직접 선택해 등록하고 즉시 TRACKING을 시작한다. 위치 근거 부족은 TRACKING을 유지하는 VERIFICATION_PENDING이며 실제 진행 근거 확보 뒤 알림 시점을 판단한다. 기본 2정거장 전 또는 선택 1정거장 전 준비 알림은 세션당 한 번만 결정하고, 목적지 도달·통과는 완료 조건이며 신규 목적지 Push는 만들지 않는다. NULL offset 기존 세션의 목적지 Push 호환은 유지한다. 실제 정차·탑승·하차나 기기 수신/표시 성공을 보장하지 않는다. 상세 계약의 Owner 문서는 변경하지 않았다.
- 최종 문구: “목적지 2정거장 전(기본) 또는 1정거장 전(선택)에 하차 준비 알림을 받을 수 있어요. 목록에서 차량을 직접 선택해 등록하세요. 등록 후 바로 추적을 시작하며, 위치를 확인하는 동안에는 '차량 위치 확인 중'으로 표시해요.”
- 수정 범위: `app/lib/features/boarding/boarding_stop_screen.dart`의 기존 안내 Text 문자열 하나와 `app/test/boarding_flow_test.dart`의 기존 흐름 테스트 기대값만 수정했다. 기본/선택 시점·준비 알림·직접 차량 선택·즉시 추적·위치 확인 중 표시 및 이전 도달·통과/대기 설명 부재를 검사한다. 신규 테스트는 0개이며 `boarding_stop_screen_test.dart`는 변경하지 않았다. Widget 구조·상태 변수·Controller·API 호출·다른 Flutter 화면·Backend·Notification 정책·Dependency·설정·Migration·devlog는 변경하지 않았다.
- 선택 검증: `app`에서 `flutter test --no-pub test/boarding_stop_screen_test.dart` **16개**, `flutter test --no-pub test/boarding_flow_test.dart` **4개**, 합계 **20개** 실패/오류/skip 없이 통과했다. 작은 화면/큰 글자 스크롤, 목적/승차 occurrence 선택, 후보 조회·오류/재시도·오래된 응답 무효화·로그인 변경·기존 도착 알림 흐름을 검증했다. `flutter analyze --no-pub lib/features/boarding/boarding_stop_screen.dart test/boarding_flow_test.dart`는 No issues, 두 파일의 `dart format --output=none --set-exit-if-changed`는 변경 0개이며 `git diff --check`를 통과했다.
- 미검증·후속 책임: 전체 Flutter/Backend 테스트·빌드·서버 실행·개발 DB 접근·실제 Provider/FCM 호출·iPhone 설치/실행·터널은 수행하지 않았다. 실제 iPhone의 안내 줄바꿈/가독성·foreground/background/terminated 수신/tap, 버스 진행과 신규 준비 알림 E2E는 TASK-710/711/908 책임으로 남는다. DeviceController/BoardingSessionController의 빈 500 응답과 AUD-12/TASK-814 UTC 수정은 이번 범위 밖이다. TASK-816의 최종 Backend/Flutter 주요 회귀·문서/잔여 결함 검토는 별도 후속이며 이번 20개 테스트로 대체하지 않는다. 기존 감사 기록과 모든 체크박스, TASK-814/816 `[ ]` 및 `main`을 유지하며 Git add/commit/push·reset/stash/clean/checkout·branch 생성은 수행하지 않았다.

#### API 500 오류 응답 계약 검토 (2026-10-30)

아래는 **구현 전 검토 당시의 기록**이다. 이후 사용자 확정과 실제 구현·검증 결과는 [A안 구현 결과](#api-500-a안-구현-결과-2026-10-30)를 따른다.

이번 단계는 두 Controller의 빈 500에 대한 정적 분석과 후속 구현 범위 제안이다. 아래 `INTERNAL_SERVER_ERROR`와 message는 **권장 후보이며 아직 구현·확정된 API 계약이 아니다.** Java/Flutter 제품·테스트·설정·Dependency·Migration과 `api.md`는 변경하지 않았다. TASK-804/814/816 및 모든 기존 체크박스는 유지한다.

**현재 Handler와 보안 근거**

- [DeviceController](../backend/src/main/java/com/stopbell/notification/controller/DeviceController.java)의 `handlePersistenceFailure()`는 `DataAccessException`, `TransactionException` 계열을 HTTP 500 + 빈 본문으로 처리한다. 등록·상태 조회·disable에서 Service 밖으로 전파된 영속성 실패가 대상이다. 로그는 `Device persistence failed: <예외 단순 클래스명>`뿐이며 원문 message/cause/stack이나 요청 값을 전달하지 않는다.
- [BoardingSessionController](../backend/src/main/java/com/stopbell/boarding/controller/BoardingSessionController.java)의 `persistenceFailure()`도 같은 예외 계열을 HTTP 500 + 빈 본문으로 처리한다. 생성·상세/current/clientRequestId 조회·취소·재확인에서 최종 전파된 실패가 대상이며 로그는 `Boarding persistence failed: <예외 단순 클래스명>`이다. 모든 RuntimeException을 처리하는 Handler는 아니다. Spring의 cause 탐색으로 일치하는 wrapper도 고려해야 한다.
- `GlobalExceptionHandler`는 `ApiException`을 해당 `ErrorCode`의 status/name + exception message로, validation/body/query/type 오류 4종을 400 `INVALID_REQUEST` + 고정 message로 처리한다. 영속성 예외나 임의 `Exception`의 fallback은 없다. MVC는 Controller-local Handler를 먼저 찾고 불일치할 때 Advice를 찾는다. 따라서 현재 두 영속성 Handler가 응답을 결정하며, Global Handler에 같은 매핑만 추가해도 local Handler는 대체되지 않는다. 로컬 Spring 7.0.9 JAR의 `ExceptionHandlerExceptionResolver.getExceptionHandlerMethod()` 바이트코드에서도 이 순서를 확인했다.
- `ApiErrorResponse`는 `code/message` 두 string의 record이며 500에도 재사용할 수 있다. `ErrorCode`는 status와 기본 message를 보유하지만 현재 `INTERNAL_SERVER_ERROR`는 없다. TASK-804의 `UNAUTHENTICATED`, `METADATA_UNAVAILABLE`도 현재 enum에 없으므로 목표 검증 목록을 이미 구현된 code matrix로 읽지 않는다. 기존 `ApiException`은 custom message도 허용하므로 500에 원본 `exception.getMessage()`를 전달하는 방식은 금지한다.
- Device Handler의 주석은 JDBC 원문에 UUID/FID가 들어갈 수 있어 cause를 로그하지 않는다고 명시한다. 도입 commit `516408e`와 함께 추가된 `report_unclassified_database_failure_safely()`는 User 삭제와 등록의 실제 FK 경쟁에서 미분류 오류를 target conflict로 위장하지 않는 500, 빈 본문, Device 미생성, 안전한 클래스명 로그와 installation/FID/credential/hash/constraint 미노출을 보호한다. `application.yml`의 `org.hibernate.orm.jdbc.error=OFF`도 하위 JDBC 로그의 원문 노출을 막는 기존 방어다.
- Boarding Handler는 commit `c7f78b2`에서 같은 제한적 처리 패턴으로 도입됐다. 확인한 코드·공식 문서·commit에는 빈 본문 자체를 영구 보안 계약으로 정한 별도 근거가 없다. Boarding의 의도는 동일한 비노출 방어로 해석할 수 있으나 Device와 동일한 HTTP 500 보안 테스트가 있다는 뜻은 아니다. 보안상 핵심은 미분류 DB 실패의 500 분류와 원문 비노출이며, 고정된 안전한 JSON도 이 조건을 만족할 수 있다.

**Service·Transaction·분류 경계**

- `DeviceService`의 write/read `TransactionTemplate`은 모두 `REQUIRES_NEW`이며 상태 조회만 read-only다. User/Device 조회, lock, insert/flush, disable 및 commit 실패가 전파될 수 있다. 등록은 MySQL 1062와 알려진 installation/push-target UNIQUE 메타데이터만 식별하고, 실패 transaction 종료 뒤 새 transaction에서 원래 요청을 한 번 재확인한다. 재확인된 자격증명/owner/revision/target conflict는 기존 403/409로 처리하고, 미식별 제약·FK·연결/timeout 및 재확인 실패는 그대로 전파한다. 상태 조회와 disable에는 이 등록 복구를 추가하지 않는다.
- `BoardingSessionService`도 `REQUIRES_NEW` template을 사용한다. 생성의 기존 request 조회는 검증 I/O 전 transaction이며 여기서 실패하면 생성 재확인 catch 밖으로 전파된다. 후보/Provider 검증은 lock transaction 밖이고, 생성 반영은 User lock → 같은 `clientRequestId` → current session → metadata/freshness 확인 → Session/Notification 저장이다. 반영 중 미식별 `DataIntegrityViolationException`은 그대로 전파하고, 알려진 두 Session UNIQUE 또는 그 외 `DataAccessException`/`TransactionException`은 원래 입력으로 `createTransaction()`을 최대 한 번 더 수행한다. 따라서 DB 장애가 항상 즉시 500이 되는 것은 아니며, 재확인 결과 성공·명시적 conflict·최종 500으로 나뉜다. 이 기존 생성 복구를 이번 본문 수정에서 확대하거나 변경하지 않는다.
- 상세/current/clientRequestId GET도 만료 및 PENDING 종료를 반영할 수 있어 단순 read-only가 아니다. 취소는 ownership/revision 검사와 Session 취소·관련 PENDING 종료를 같은 transaction에서 처리하고 별도 재적용 retry가 없다. 재확인은 사전 snapshot 조회 → transaction 밖 후보 재검증 → Session lock 및 revision/metadata/freshness 재검사 → checkpoint/revision/준비 Event·Delivery 저장 순서다. `DataIntegrityViolationException` 또는 반영 결과가 준비되기 전 실패는 전파한다. 그 외 commit 불확실성은 새 transaction에서 원래 결과 snapshot과 반환 Event ID, metadata/freshness를 확인할 뿐 rollback된 재확인을 재적용하지 않는다.
- `BoardingNotificationService`는 `MANDATORY`로 Session transaction에 참여한다. Event `saveAndFlush`, eligible Device lock, Delivery 저장/flush와 취소·만료의 PENDING 종료는 독립 commit되지 않는다. 검증된 Session UNIQUE 및 비즈니스 revision/ownership conflict와 다른 Event/Delivery UNIQUE·FK·실제 DB 실패를 합치지 않는다. Scheduler/Worker에서 발생한 실패는 Controller-local Handler의 적용 대상이 아니다.
- callback 내부 실패는 `TransactionTemplate.execute()`의 rollback 경계를 지난 뒤 Controller로 도달한다. 정상 반환은 commit 호출 뒤에만 가능하다(로컬 Spring 7.0.9 `TransactionTemplate.execute()` 확인). **500을 받았다는 사실만으로 rollback 또는 미저장을 보장하지 않는다.** commit 응답 유실·rollback 실패·commit 뒤 후속 조회 실패는 결과가 불확실하거나 이미 반영됐을 수 있고, Boarding의 사전 만료 transaction과 반영 transaction도 별개다. Body 직렬화는 이 경계 밖이므로 본문만 바꾸는 권고는 DB 원자성을 바꾸지 않으며, 실패 시 기존 상태 보존과 commit 불확실성 복구를 구별해 검증해야 한다.

**계약 적용 범위와 Client 영향**

- [API 오류 형식](api.md#5-오류-형식)은 V1 Error Response를 정확히 `code/message` 두 필드로 규정하며 두 Controller의 영속성 500에 대한 빈 본문 예외를 명시하지 않는다. Device use-case 오류와 세션 오류도 이 형식을 참조한다. 따라서 HTTP 500이라는 분류는 타당하지만 두 mapped 500의 빈 본문은 남은 형식 불일치다. 문서에 500 code/message 값의 명시적 행은 없으므로 그 값은 후속 구현 전 승인할 후보로 구분한다.
- JWT 누락/실패의 401은 API의 Device/세션 절에서 기존 Spring Security 처리 유지로 별도 명시돼 있다. `SecurityConfiguration`은 기본 JWT Resource Server를 사용하며 custom JSON entry point가 없다. Filter 이전/내부 실패는 Controller Advice만으로 통일되지 않는다. 또한 Service의 User 부재는 `ResponseStatusException`, Google/refresh의 credential 오류는 `@ResponseStatus(UNAUTHORIZED)`가 붙은 전용 예외 경로여서 모든 401이 Filter에서 발생하는 것도 아니다. 이번 검토로 Security/401·모든 HTTP 오류·임의 예상 밖 예외에 JSON을 강제하지 않는다. 이 적용 범위와 최종 matrix는 TASK-804에서 검증·결정한다.
- Transit 실패는 use-case에서 정규화된 `TRANSIT_TEMPORARILY_UNAVAILABLE` 503 등을 유지하며 Google Identity 검증의 credential 401/infrastructure 503, FCM의 내부 전송 결과 분류도 별개다. 외부 Provider 원문/status를 이 두 500의 code/message로 복사하지 않는다. Alarm/Transit/Auth 등 다른 Controller에는 같은 local 영속성 Handler가 없으므로 그들의 미처리 DB 오류 body를 공통 JSON이라고 가정하지 않는다. A안은 이 경로를 바꾸지 않으며 전체 fallback은 TASK-804 책임이다.
- `AuthenticatedApiClient`는 status=401일 때만 refresh와 최대 한 번 replay하고 body를 판단하지 않는다. `authenticated_api_client_test.dart`에는 빈 500의 무-refresh 통과 테스트가 있다. JSON 500도 동일 경로이므로 인증 제거/자동 POST replay를 새로 유발하지 않는다.
- `DeviceApiClient`는 오류 JSON 파싱 실패(빈 본문 포함)를 허용하고 allowlist code만 저장한다. 새 `INTERNAL_SERVER_ERROR`는 현 allowlist에 없어 **500/code=null**로 보존되며 `message`는 보존하지 않는다. `definitive`는 4xx에만 true이므로 500은 계속 미확정이다. `PushRegistrationService`도 이 값으로 rejected 여부를 정해 pending을 보존한다. 새 code를 인식시키는 Flutter 제품 수정은 호환성에 필수적이지 않다.
- `BoardingSessionApiClient`는 빈/비정상 오류 JSON에서도 status를 보존하고 문자열 code는 미등록 값도 수용한다. JSON 500은 `SessionFailure.http`, status=500, 새 code로 처리되며 `uncertain=true`를 유지한다. `boardingSessionError()`의 기본 안내와 생성의 원래 UUID/body 보존·조회 복구, 취소/재확인 후 상태 재조회는 유지된다. 기존 Client 테스트의 500/`INTERNAL`은 임의 fixture code이며 서버 계약이 아니다. 통합된 Error DTO/parser는 없고 Device/Boarding이 각각 파싱한다. 8초/15초 timeout, 늦은 401 차단과 session generation 검증도 본문에 의존하지 않는다.

**수정안 비교와 권고**

| 방안 | 후속 수정 파일 | 장점 | 위험 | 추천 여부 |
| --- | --- | --- | --- | --- |
| A — Controller-local 유지 | `ErrorCode.java`, `DeviceController.java`, `BoardingSessionController.java`, 두 API 통합 테스트 | 현재 매핑·우선순위·클래스명 로그와 Service 경계를 유지하며 본문만 정합화 | 두 곳의 짧은 DTO 생성 중복, 전체 500 통일은 아님 | **권고** |
| B — Global Handler 확장 | A의 파일 + `GlobalExceptionHandler.java`, 다른 Controller의 오류 회귀 테스트 | 영속성 오류 응답 생성 집중 | local 제거가 필요하고 Alarm/Transit/Auth의 미처리 DB 오류까지 영향 확대, 로그 문맥/보안·예외 선택 검증 증가, TASK-804와 겹침 | 이번 최소 수정으로 비권고 |
| C — 빈 500 유지 | 제품 변경 없음, 예외 계약 채택 시 `api.md` 설계 변경 필요 | 현 테스트/응답 유지 | 문서 계약에 새 예외 도입, 기계적 오류 처리 일관성 저하, 비노출에 빈 본문이 필수라는 근거 없음 | 비권고; 이번에 문서 계약을 낮추지 않음 |

A안을 후속 구현의 최소 방향으로 제안한다. 후보는 `ErrorCode.INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred.")`이며 두 local Handler의 반환형을 `ResponseEntity<ApiErrorResponse>`로 바꿔 enum의 name/고정 message만 전달한다. 기존 status·매핑·로그 문구/클래스명만 유지하고 catch-all·Service 예외 wrapping·자동 retry는 추가하지 않는다. `ApiErrorResponse`, `ApiException`, `GlobalExceptionHandler` 및 Flutter 제품 수정은 필요하지 않다. 채택 시 API Owner 문서에는 두 mapped 500의 명시적 code/message와 한정 범위를 최소 동기화하며, 이번에는 변경하지 않는다.

응답/로그에는 SQL·SQLState·constraint·Hibernate 원문·stack trace·내부 DB 구조·installationId·FID/push target·Device credential/hash·User ID·JWT/Refresh Token을 포함하지 않는다. 응답은 정확히 두 필드이며 timestamp/path/status/trace ID 등의 필드를 추가하지 않는다. 원문 예외나 cause를 로거의 Throwable 인자로 넘기지 않고 기존 Hibernate 원문 로그 억제도 유지한다.

**후속 테스트 acceptance와 책임 분리**

| 대상 | 필요한 변경·선택 검증 |
| --- | --- |
| `DeviceApiIntegrationTest` | `report_unclassified_database_failure_safely()`의 빈 본문 assertion만 승인된 JSON 계약 검사로 대체: 500, JSON Content-Type, 정확한 code/message와 필드 집합 `{code,message}`. FK 경쟁·Device 미생성·클래스명 로그·기존 민감값/constraint 미노출 assertion은 삭제하지 않고 응답 비노출까지 보강. register/state/disable의 DB·transaction 실패와 기존 row의 owner/generation/revision/target/시각 보존도 검증 |
| `BoardingSessionApiIntegrationTest` | 현재 빈 500 자체를 검증하는 HTTP 테스트는 발견하지 못함. create·상세/current/request GET·cancel·reconfirm의 HTTP 실패 매핑과 두 예외 계열 대표 경로에서 정확한 두 필드/고정 값, 원문/민감값 및 stack 미노출, 안전한 클래스명 로그 검증 추가. 단순 Service mock만으로 rollback을 주장하지 않고 기존 MySQL Testcontainers의 Event/Delivery 저장 중 실패와 Session·revision·checkpoint·PENDING 원자 보존을 HTTP 경로에서도 연결 |
| 기존 Transaction·동시성 회귀 | Device `roll_back_target_uniqueness_races`, `serialize_existing_device_mutations` 및 설치본 UNIQUE 경쟁 테스트를 보존. Boarding `lost_commit_response_and_rollback`, `reconfirm_commit_reconciliation`, `aud08_atomic_failure`, `aud08_commit_reconciliation`, `concurrent_same_request`, `concurrent_distinct_creates`, `concurrent_reconfirmation`과 ownership/cancel revision 검증을 선택 실행. 앞의 rollback/commit 테스트는 Service 직접 호출이므로 HTTP body/log 검증을 대신하지 않음 |
| Flutter 호환 회귀 | 기존 `device_api_client_test.dart`, `boarding_session_client_test.dart`에 빈 500·후보 JSON 500·미등록 code·비정상 JSON과 민감 message 무보존 비교 보강. `authenticated_api_client_test.dart`에서 JSON 500의 refresh/replay 0회·인증 유지, `push_registration_service_test.dart`의 pending 보존, `boarding_session_controller_test.dart`의 원래 UUID/body·조회 복구 및 mutation 재조회 검증. 현재 제품 수정 필요성은 발견하지 못했으며 이 정확한 후보 조합은 미실행 |
| 인접 Backend 회귀 | 변경 Handler가 `ApiException`/validation을 가로채지 않는지 두 API의 기존 400/403/404/409와 JWT 401, Boarding Provider 503을 선택 검증. 다른 Controller와 전역 fallback·Security 전체 matrix는 TASK-804에서 다루며 이번 수정 검증을 전체 API 완료로 확대하지 않음 |

- **TASK-816 후속 구현 책임:** 발견된 두 mapped 500의 형식 정합화, 비노출/클래스명 로그 보존, 직접 관련 HTTP·DB 원자성·Client 호환 회귀와 필요한 API 문서 최소 동기화. 구현 전 A안과 후보 code/message 채택을 확인하고, transaction 내부 실패·commit 불확실·후속 read 실패를 구분해 fixture를 설계한다. 반환형/직렬화 변경이 새 민감 로그나 error fallback을 유발하지 않는지도 확인한다.
- **TASK-804 유지 책임:** 전체 Error Code Matrix, Controller별 400/401/403/404/409/500(및 기존 Provider 503)의 의미/shape, Security entry point·인증 응답, 예상 밖 Exception의 최종 fallback, 신규 Endpoint 일관성, 전체 validation/error regression. 미구현 code와 metadata/Provider 의미 구분도 여기서 확정하며 모든 DB 실패를 409 또는 400으로 바꾸지 않는다. 두 Handler 수정만으로 TASK-804를 완료 처리하지 않는다.
- **이번 확인 수준:** 기존 코드·테스트·공식 문서·도입 Git 이력과 로컬 Spring 바이트코드의 정적 분석만 수행했다. 테스트 실행은 **0개**이며 성공을 주장하지 않는다. 문서 검토만으로 최소 방향을 판단할 수 있어 두 Testcontainers 클래스·전체 Backend/Flutter suite·compile/build를 실행하지 않았다. 실제 DB rollback/commit·HTTP JSON 직렬화·로그 수집·Flutter 후보 JSON 500의 실행 검증은 후속 acceptance다. 개발 DB·Docker/volume 조작·Provider/Google/FCM·서버/터널·iPhone 실행은 없고 `main` 및 기존 감사 기록을 유지한다. Git add/commit/push 및 branch/reset/stash/clean/checkout은 수행하지 않는다.

#### API 500 A안 구현 결과 (2026-10-30)

- 사용자 확정에 따라 A안(Controller-local Handler 유지)을 구현했다. `ErrorCode.INTERNAL_SERVER_ERROR`에 HTTP 500과 `An internal server error occurred.`를 추가하고 `DeviceController.handlePersistenceFailure()`·`BoardingSessionController.persistenceFailure()`가 기존 `ApiErrorResponse`로 정확히 `{"code":"INTERNAL_SERVER_ERROR","message":"An internal server error occurred."}`를 반환하도록 변경했다. 실제 MockMvc 응답의 Content-Type은 `application/json`이다. [API 오류 형식](api.md#5-오류-형식)에 두 Handler의 지정된 예외에 한정한 계약을 반영했다.
- `DataAccessException`·`TransactionException` 매핑과 기존 로그 문구/예외 단순 클래스명만 기록하는 정책은 동일하다. 원문 message/cause/stack·SQL/제약 이름·민감정보를 응답/로그로 전달하지 않는다. GlobalExceptionHandler/ApiException/ApiErrorResponse·Security·세 Service·TransactionTemplate/lock/retry/commit 복구·Flutter 제품·Dependency·Migration·환경 설정은 변경하지 않았다. 기존 AUD-01~13 제품/회귀 테스트도 보존했다.
- Device의 기존 `report_unclassified_database_failure_safely()`에서 빈 본문 검사만 정확한 JSON 두 필드/고정 값/Content-Type 및 응답 비노출 검사로 교체했다. 실제 MySQL User 삭제/FK 경쟁·Device 미생성·예외 클래스명 로그·installation/FID/credential/hash/constraint 미노출 검증과 동기화는 유지했다.
- Boarding에 `report_database_failure_over_http_safely()`를 추가했다. 기존 검증 fixture로 재확인 HTTP를 호출하고 실제 Event INSERT/flush 뒤 MySQL FK 실패를 발생시킨다. HTTP 500/두 필드/고정 값과 안전 로그, `afterCompletion=ROLLED_BACK`, 요청 뒤 transaction 종료, 원래 Session snapshot·revision·checkpoint 보존, Event/Delivery 0개 및 gate 차단을 확인했다. Delivery 중간 저장 실패는 기존 `aud08_atomic_failure` 두 사례가 별도로 검증하며 두 검증을 합쳐 Service mock만으로 원자성을 주장하지 않는다.
- `report_transaction_failure_over_http_safely()`는 실제 조회 HTTP에서 기존 Notification Service spy에 제한적으로 `TransactionSystemException`을 주입해 두 번째 예외 계열의 local Handler 선택·고정 JSON·message/cause/민감 marker 비노출과 Session 보존을 검증한다. 이 사례는 실제 commit 장애 재현이 아니다. Spy는 기존 `@MockitoSpyBean`의 테스트 후 자동 reset을 사용한다. 최초 실행은 MANDATORY AOP proxy를 transaction 밖에서 stubbing한 fixture 오류로 실패했고, 기존 테스트 방식대로 ultimate target에 stubbing한 뒤 실패했던 메서드 1개만 재실행해 통과했다. 제품 transaction 정책은 변경하지 않았다.

| 실행 대상 | 실제 실행·결과 |
| --- | --- |
| HTTP 500 집중 테스트 | Device 기존 FK 경쟁 1개 + Boarding 신규 2개. 최초 3개 중 2개 성공/fixture 1개 실패, 해당 1개 수정 후 재실행 성공. 최종 서로 다른 3개 모두 통과 |
| Device 선택 회귀 | 8개 통과: `rotate_target_and_enforce_revision`, `transfer_ownership_and_reject_old_generations`, `enforce_credentials_platform_authority_and_limits`, `reject_invalid_input_without_coercion`, `require_jwt_for_all_endpoints`, `resolve_first_registration_races`, `roll_back_target_uniqueness_races`, `serialize_existing_device_mutations` |
| Boarding 선택 회귀 | 26개 필터 실행 통과: `require_jwt`, `request_and_session_conflicts`, `query_and_cancel_with_ownership`, `cancel_requires_current_revision`, `invalid_revision_body`(6), `provider_metadata_and_target_failures`, `concurrent_distinct_creates`, `concurrent_same_request`, `lost_commit_response_and_rollback`, `reconfirm_authentication_and_ownership`, `reconfirm_requires_suspension`(4), `reconfirm_commit_reconciliation`(2), `concurrent_reconfirmation`, `aud08_atomic_failure`(2), `aud08_commit_reconciliation`(2). 추가로 `known_run_end_does_not_create_session` 1개를 선택 실행해 `BOARDING_TRACKING_UNSUPPORTED` 409 보존 통과. 합계 **27개** |
| Backend 합계 | 최종 서로 다른 **38개** 통과(Device 500 1 + Boarding 500 2 + Device 회귀 8 + Boarding 회귀 27), 최종 실패/오류/skip 없음. 최초 fixture 실패와 수정 재실행까지 누적 실행은 39회(성공 38회·실패 1회)이며 최종 통과 수에 중복 합산하지 않음 |
| Flutter 선택 회귀 | `device_api_client_test.dart`, `boarding_session_client_test.dart`, `authenticated_api_client_test.dart`, `push_registration_service_test.dart`, `boarding_session_controller_test.dart` 5개 파일 합계 **127개 통과**, 실패/오류/skip 없음. 전체 Flutter suite가 아님 |
| 정적 검사 | Java 21 `compileJava`·`compileTestJava` 성공. 변경 Dart 테스트 5개 파일 `flutter analyze --no-pub` No issues, `dart format --output=none --set-exit-if-changed` 변경 0개. `git diff --check` 및 보호 파일/Handler 매핑·로그 불변 정적 확인 통과 |

- 신규 테스트는 Boarding 메서드 2개와 Dart 5개 선언에서 생성하는 9개 사례(Device 빈/JSON/미등록 code 3개, Boarding 생성 500 동일 3개, JSON 500 무-refresh·무-POST replay/Token Pair 보존 1개, Device pending 보존/동일 payload 재시도 1개, Boarding 원래 UUID/body 보존/조회/명시적 재시도 1개)다. 신규 파일이나 테스트 infrastructure는 추가하지 않았다. Device Client가 allowlist 밖 code를 null로 처리하고 `definitive=false`를 유지하며, Boarding Client는 HTTP/status=500/`uncertain=true`를 유지하는 것을 실행 검증했다. 기존 인증 generation·timeout/늦은 401 차단·mutation 재조회 회귀도 포함한다.
- DB 검증은 기존 MySQL **8.4.11 Testcontainers**만, Provider는 Mock/Fake만 사용했다. 400/403/404/409·JWT 누락/잘못된 JWT 401·Provider 503 분류와 Device UNIQUE/ownership/generation/revision·Boarding idempotency/revision/원자성 및 commit 응답 유실/rollback 재확인을 선택 검증했다. HTTP 500 자체로 미저장/rollback을 단정하지 않으며, 기존 commit 결과 불확실성 복구와 원래 요청 identity를 유지한다.
- 미검증: 전체 Backend/Flutter suite·TASK-816 최종 주요 회귀/상호 정합성 감사, 실제 개발/운영 DB·실제 DB 네트워크 commit 응답 유실·process kill/restart, 실제 Google/Transit/FCM/Firebase·iPhone, 모든 Endpoint/예외 계열 하위 타입·만료 JWT 전체 matrix. 서버 상시 실행·터널·Docker Named Volume 조작은 없었다. Gradle/Flutter 최초 실행의 sandbox 캐시 쓰기 제한은 허용된 권한 확장으로 해결했으며 테스트 skip/검증 약화는 없다.
- TASK-804는 전체 code matrix·Security 401·예상 밖 Exception fallback·신규 Endpoint 및 전체 validation/error regression 책임을 유지한다. TASK-816은 이번 두 mapped 500의 불일치 처리 결과를 최종 주요 Backend/Flutter 회귀 및 AUD-01~13 상호 정합성·문서·잔여 결함 검토에 연결해야 한다. AUD-12→TASK-814의 UTC 수정·기존 데이터 판단과 TASK-710/711/908 실제 E2E 책임도 유지한다. **TASK-804/814/816 및 모든 체크박스는 `[ ]` 등 기존 상태 그대로이며 최종 회귀 단계로 자동 진행하지 않는다.** `main`과 기존 검토/AUD 기록을 보존하고 Git add/commit/push·branch/reset/stash/clean/checkout 없이 보고 후 중단한다.

### TASK-816 최종 종합 회귀 및 완료 조건 감사 (2026-10-30)

**최종 판정: NOT READY.** 전체 Backend 회귀에서 기존 Fixture와 AUD-02·AUD-10의 현재 계약이 충돌하는 실패 2건을 확인했다. 제품 결함 재발로 단정하지 않지만 필수 회귀 통과 조건은 충족되지 않았다. AUD-12의 UTC 제품 결함도 미수정이며 TASK-814의 책임과 완료 조건 해석을 아래에서 구분한다. 이 기록은 TASK-816 완료 처리나 다른 Phase 착수를 뜻하지 않는다.

#### 검증 대상과 실행 안전성

- Branch `main`, Commit `e44d009ff9c65b7fde9ceae94f9a2b84e0b6026f`. 시작 시 `git status --short`와 `git diff HEAD --stat`는 출력이 없는 깨끗한 작업 트리였다. 검증 대상은 이 로컬 코드이며 GitHub와의 대조·fetch/pull/checkout은 수행하지 않았다.
- API 500 A안, Flutter 안내 문구 및 AUD-01~13의 기존 처리 결과를 현재 코드·테스트와 대조했다. 제품·테스트·SQL·Dependency·Gradle/Profile·Firebase/Docker 설정·Credential·ADR·devlog는 변경하지 않았다.
- Java와 Gradle JVM은 21.0.12.1, Wrapper는 8.14.3, Flutter는 3.47.1 Stable / Dart 3.13.1이다. 최초 Gradle 버전 확인은 sandbox의 기존 캐시 lock 쓰기 제한으로 실행 전 실패했고, 허용된 권한 확장 후 실행했다. 이 환경 사전 점검은 테스트 실행/실패 개수에 포함하지 않는다.
- Backend DB 테스트는 `mysql:8.4.11` Testcontainers의 임시 DB와 `@ServiceConnection` 또는 Container JDBC URL만 사용한다. Context smoke의 DB는 제외/Mock이며 실제 개발 MySQL에 연결하지 않는다. 기존 `stopbell-mysql-1`은 보존했다.
- Provider HTTP는 `MockRestServiceServer`/Mock, Google 검증은 Mock, FCM은 Mock 또는 `127.0.0.1` 임시 HTTP 서버와 합성 인증 경계다. 이름에 Smoke가 있는 `FcmSmokeRunnerTest`도 Mock 테스트이며 수동 실전송을 실행하지 않는다. 기본 Monitoring/Dispatch는 비활성이고 이를 켜는 Scheduler/Worker 테스트는 주입된 Mock을 사용한다. Flutter HTTP·Google·Secure Storage·Firebase/Pigeon/MethodChannel은 Mock/Fake로 격리한다.
- 전체 Backend는 `env -u JAVA_HOME ./gradlew test --offline --max-workers=1`, Flutter는 `flutter analyze --no-pub` 및 `flutter test --no-pub --reporter=json`으로 실행했다. 안전성 때문에 제외한 자동 테스트는 없다. 실제 외부 호출·개발/운영 DB·기존 Named Volume 변경·서버/터널·iPhone 설치/실행은 수행하지 않았다.

#### AUD-01~13 현재 상태와 상호 정합성

다음 개수는 이번 전체 실행의 실제 사례 수이며 과거 선택 실행 수를 합산하지 않는다. PASS는 기재한 자동 검증/처리 범위에 한정한다.

| AUD / 최초 위험 | 현재 판정과 제품 코드 근거 | 이번 테스트 근거 | 상호 정합성·미검증 / 후속 책임 |
| --- | --- | --- | --- |
| AUD-01 최초 Alarm Push 준비 누락 | PASS. `main.dart`→`AlarmHome`→`BusRouteStopScreen._create()`의 준비·사용자 선택→Create→Activate, Auth/화면 수명 재검증 유지 | `alarm_creation_push_test.dart` 32개, Alarm API/timeout·화면 회귀 40개 통과 | AUD-05/06과 준비 취소·늦은 응답·중복 POST 차단 계약 유지. 실제 permission/APNs/수신·tap은 TASK-710/711 |
| AUD-02 동일·역행 Provider 시각의 오판 | 제품 수정 유지, 최종 회귀 BLOCKED. `BusAlarmEvaluator.isMonotonic()`과 `VehicleTrackingState.lastKnownProviderDataTime`의 엄격 증가·NULL 사이 기준 보존 유지 | Evaluator 65개·Scheduler 35개·Lifecycle 6개 통과. `TransitMonitoringIntegrationTest`는 6개 중 1개 실패 | AUD-09 혼합 차량/재시도 회귀 통과. 동일 시각에 진행시키는 기존 통합 Fixture 충돌은 아래 실패 1 참조. 실 Provider 시각 분포는 미검증. TASK-816 회귀 보정 책임 |
| AUD-03 불완전 서울 Snapshot 승인 | PASS. `SeoulCsvMetadataSource.fetchCompleteSnapshot()`이 포함 Route의 occurrence 0개를 complete 반환 전에 거부 | CSV Source 13개·서울 Sync 10개·Sync Transaction 3개·Bootstrap 3개·Sync Service 2개 통과 | 정상 complete 갱신/cleanup·기존 ID/데이터 보존과 실패 rollback 유지. 실제 대규모 CSV import·I/O/장애 운영 검증은 별도 |
| AUD-04 Polling/Dispatch 자원 공유 | PASS. `SchedulingConfiguration`의 독립 단일 스레드와 두 `@Scheduled(scheduler=...)`, 기존 직렬 실행 guard 유지 | Scheduling 5개, Monitoring Scheduler 35개, Dispatch Worker 10개와 Dispatch/Boarding 통합 회귀 통과 | 느린 한쪽과 다른쪽의 독립 진행·각 non-overlap·shutdown 보호 유지. 운영 부하/강제 종료는 TASK-810/811·711 |
| AUD-05 timeout 뒤 늦은 401 replay | PASS. `AlarmApiClient._request()`의 요청별 active/generation과 `finally` 무효화, `AuthenticatedApiClient`의 refresh/replay 전후 검사 유지 | Alarm timeout 18개·Authenticated API 24개 통과 | AUD-01 준비·AUD-06 공유 Refresh와 유효 요청/만료 요청 분리 유지. 물리적 HTTP 취소·서버 rollback은 보장 밖 |
| AUD-06 인증 HTTP 무한 대기 | PASS. `BackendAuthClient._post()`의 기본 15초, Token parsing 이전 timeout과 늦은 결과 폐기 유지 | Backend Auth Client 15개·Auth Session 28개와 위 Authenticated API/Alarm timeout 통과 | Login/Refresh/Logout·Rotation/single-flight·500 뒤 Session 보존 유지. Google SDK/Storage/hook 장기 대기·실네트워크 응답 유실은 미검증 |
| AUD-07 후보 detail 누적 지연 | PASS. `BoardingCandidateService`·전용 `BoardingCandidateDetailExecutor`의 단일 12초 예산, 요청당 2/프로세스 4 admission·늦은 결과 폐기 유지 | Executor 11개·Latency 14개·Service 31개·Reference 9개·Revalidation 36개, Flutter 후보 29개 통과 | 정렬/identity/freshness/reference·PARTIAL/HELD와 15초 Client 수명 유지. AUD-04 pool과 독립. 절대 응답 SLA·Provider 전체 rate limit·실부하 검증 아님 |
| AUD-08 재확인 후 준비 판단 누락 | PASS. `BoardingSessionService.reconfirm()`→`recordReconfirmedPreparation()`→`recordInitialPreparation()`의 Session/checkpoint/Event/Delivery 원자 저장·commit 확인 유지 | Boarding API 130개·Tracking 139개·Evaluator 66개·Boarding Dispatch 73개 통과 | 새 1/2 구간의 미소비 판단, 기존 NULL offset·TTL·0 recipient 소비·revision/gate·동시성/응답 유실 보존. 실제 버스/기기는 TASK-908·710/711 |
| AUD-09 차량 mapping 오류의 노선 전파 | PASS. 서울 Mapper의 STRICT 시각/차량 검증→PROTOCOL 변환, Scheduler의 차량별 catch·presence·실패 차량 재시도 유지 | 서울 Mapper 19개·Scheduler 35개 및 Evaluator 65개 통과 | AUD-02 동일/역행 재시도 차단과 정상 차량 평가 보존. 실 malformed 응답 빈도·지연은 미검증 |
| AUD-10 신규 Google 최초 로그인 경쟁 | 제품 수정 유지, 최종 회귀 BLOCKED. `LoginService`의 로그인 전체 REQUIRES_NEW, 알려진 Identity UNIQUE만 rollback 후 새 Transaction에서 1회 조회 유지 | Login Service 23개·동시성 통합 8개·Auth Controller 23개·Refresh 4개·Login Migration 1개 통과. Repository 20개 중 1개 실패 | AUD-11 Identity 지원 정책·Session 원자성 보존. 미커밋 외부 Fixture와의 잠금 충돌은 아래 실패 2 참조. 실제 Google/다중 서버/운영 장애는 미검증. TASK-816 회귀 보정 책임 |
| AUD-11 Legacy V3→V4 | PASS(확정된 지원 제한·감사 처리). 기존 SQL 불변, 신규 Identity 합성 없음. V4 미적용 User 0명만 일반 자동 업그레이드 | User Identity Migration 8개·Login Migration 1개 통과 | Fresh/빈 V3/정상 V20 validation·재적용, User 1명의 잘못된 성공 및 2/10명의 실패·원본 보존 확인. 운영 Preflight·수동 이관/Backup Restore는 미검증·별도 설계 책임 |
| AUD-12 Host 의존 timestamp | PENDING(감사 근거 연결 완료, 제품 미수정). User 2·RefreshToken 1·Alarm 2 callback의 인자 없는 `LocalDateTime.now()` 잔존 | UTC Characterization 4개 통과. 이번에는 기본 테스트 JVM에서 실행했으며 과거 3개 zone의 12회 결과를 이번 수에 합산하지 않음 | 기존 UTC expiry/FOLLOW_UP·Notification·Boarding 경로 회귀 유지. 통과는 현 결함 재현이며 UTC 수정 증거가 아님. 수정·시간 원천 선택·JDBC/다중 zone/DST·기존 데이터 정책은 TASK-814 |
| AUD-13 PASSED 위치 전달 누락 | PASS. Event의 불변 위치→`NotificationRecipientPreflight`→`PassedNotificationContext`→DispatchTarget/SendRequest→FCM body 유지 | Passed Dispatch 9개·FCM Provider 57개·Notification persistence/attempt/retry/worker 회귀 통과 | 네 data key·두 alert body 일치·선택 위치 생략/길이 제한·다른 Event/Session body·최신 recipient/retry/expiry 보존. process restart·실 FCM/iPhone 표시는 TASK-811·710/711 |

API 500 A안도 두 Controller-local Handler가 지정된 `DataAccessException`/`TransactionException`에만 고정 `INTERNAL_SERVER_ERROR`와 `An internal server error occurred.`의 두 필드 JSON을 반환함을 확인했다. Device API 12개·Boarding API 130개 및 Flutter의 빈/JSON/알 수 없는 code 500 테스트가 통과했다. 원문/민감정보 비노출, 기존 400/401/403/404/409/503 분류, Auth Pair/generation·Device pending·Session의 원래 UUID/body·commit 불확실성 조회 복구가 유지된다. 전체 Endpoint/예상 밖 Exception/Security 오류 형식 완성은 TASK-804의 독립 책임이다.

#### Backend 실제 실행 결과와 실패 분석

전체 79개 테스트 클래스가 모두 보고서에 존재하며 누락·제외한 클래스는 없다. JUnit XML의 실제 `<testcase>`와 suite 합계는 일치한다. Parameterized invocation도 각각 한 사례로 집계하고 동일 DisplayName이라는 이유로 서로 다른 인자를 합치지 않는다.

| 영역 | 실행 | 통과 | 실패 | 오류 | Skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| Alarm | 189 | 188 | 1 | 0 | 0 |
| BoardingSession | 410 | 410 | 0 | 0 | 0 |
| Notification(Device 포함) | 307 | 307 | 0 | 0 | 0 |
| Authentication/User/Persistence | 102 | 101 | 1 | 0 | 0 |
| Transit/Metadata | 247 | 247 | 0 | 0 | 0 |
| Common Configuration/UTC/Application Context | 17 | 17 | 0 | 0 | 0 |
| 전체 최초 실행 | 1,272 | 1,270 | 2 | 0 | 0 |

Device API 12개·Persistence 2개는 위 Notification의 부분집합이다. Migration 5개 클래스의 12개 사례와 UTC Characterization 4개도 각 영역에 이미 포함되므로 중복 합산하지 않는다. 전체 Gradle 결과는 `BUILD FAILED`(7분 41초)이며 compileJava/compileTestJava는 UP-TO-DATE였다.

1. **시각 계약과 충돌하는 Alarm Fixture:** `TransitMonitoringIntegrationTest.seoul_arrival_then_same_vehicle_one_stop_after_completes_follow_up()`의 135행에서 기대 INACTIVE / 실제 FOLLOW_UP으로 실패했다. 이 테스트의 `detail()`은 target/successor 모두 같은 `20260923090000`을 만들며 Mapper/Scheduler Clock도 고정이다. AUD-02가 동일 시각의 바뀐 위치를 거부하는 것이 현재 설계이므로 정상 후속 진행 Fixture가 아니다. Evaluator의 `audit_rejects_follow_up_time_conflict_and_recovers`와 나머지 단위/통합 경계는 통과했다. 후속 후보는 **이 통합 테스트의 정상 진행에만** Provider 시각과 Mapper/Scheduler/decision Clock을 일관되게 전진시키는 Fixture 보정이다. 동일/역행 시각 차단 assertion이나 제품 검증을 완화하지 않는다. 정상 시각의 전체 DB/lifecycle 경로 최종 통과는 보정 후 확인해야 한다.
2. **Transaction 경계와 충돌하는 기존 로그인 Fixture:** `RepositoryIntegrationTest.login_existing_google_identity_reuses_user()`의 161행에서 약 50초 후 `CannotAcquireLockException`→`LockTimeoutException`→MySQL lock wait timeout으로 실패했다. 클래스의 테스트 Transaction에서 User를 saveAndFlush하지만 commit하지 않은 채 REQUIRES_NEW 로그인으로 진입한다. 새 Transaction은 그 User를 볼 수 없어 같은 Identity INSERT를 시도하고, 중단된 외부 Transaction의 UNIQUE 잠금을 기다린다. 실제 동시 로그인 8개·정상 이관 후 로그인 1개는 통과했으며 테스트 순서만으로 설명되는 실패가 아니다. 후속 후보는 로그인 관련 Fixture를 **실제 commit된 User**로 준비하고 로그인 결과도 새 읽기 Transaction에서 확인하는 테스트 경계 보정이다. REQUIRES_NEW commit은 바깥 테스트 rollback으로 정리되지 않으므로 기존 신규 로그인 테스트를 포함한 명시적 정리와 절대 `users.count()==1`의 격리 조건도 함께 검토해야 한다. 제품의 Transaction 원자성/충돌 분류 변경이나 잠금 timeout 증가는 후보가 아니다.

실패한 위 두 메서드만 같은 코드에서 `--tests` 두 개로 선택 재실행한 결과 **2개 모두 같은 원인으로 다시 실패**했다(`BUILD FAILED`, 1분 15초). Alarm은 동일한 기대 INACTIVE / 실제 FOLLOW_UP, 로그인은 같은 INSERT의 약 50초 lock wait timeout이다. 따라서 전체 실행 순서나 다른 테스트의 상태 누출만으로 설명되지 않는다. 재실행은 2회(통과 0·실패 2·오류/Skip 0), 누적 실행은 **1,274회(통과 1,270·실패 4·오류/Skip 0)**다. 서로 다른 사례는 최초 전체의 **1,272개**, 최종 통과 1,270·실패 2로 유지하며 재실행을 새 사례로 중복 합산하지 않는다. 이미 통과한 1,270개는 반복 실행하지 않았고 두 Fixture 모두 직접 수정하지 않았다.

최초 전체 JUnit XML은 재실행 전에 `/private/tmp/stopbell-task816-backend-full-results`, HTML은 `/private/tmp/stopbell-task816-backend-full-html`에 보존했다. 재실행 XML은 `/private/tmp/stopbell-task816-backend-rerun-results`에 별도 보존했으며 현재 `backend/build/test-results/test`·HTML은 2개 선택 재실행 결과다. 실행 로그는 `/private/tmp/stopbell-task816-backend.log`·`stopbell-task816-backend-rerun.log`, Flutter runner 원본은 `/private/tmp/stopbell-task816-flutter-test.jsonl`이다. 전체 종료 중 Hikari의 Context 정리 대기를 읽기 전용 스레드 덤프로 확인했으며 강제 종료·설정 변경 없이 정상 종료한 Gradle 실패 결과를 집계했다.

#### MySQL/Flyway 호환성

- `UserIdentityMigrationIntegrationTest`가 실제 MySQL version 8.4.11을 확인하고 Fresh V1~V20, User 0명의 V3→V4→V20, 정상 Identity의 V20 validation/재적용 0건을 검증했다. Login Migration 통합은 기존/신규 Identity와 독립 Session·Rotation·Logout을 검증했다.
- V3 User 1/2/10명과 strict/빈 sql_mode는 기존 AUD-11 지원 제한대로 관측했다. 1명의 빈 Identity SQL 성공은 지원 성공이 아니고, 2/10명 UNIQUE 실패는 알려진 제한을 재현하는 성공 테스트다. 새로운 제품 회귀로 등록하지 않는다. Local Development의 수동 Preflight·지원 중단·별도 이관 판단 문구도 유지된다.
- Notification Migration/Source Migration은 legacy History와 기존 ALARM Event·Device·네 Delivery 상태, Session 및 V16~V20의 데이터·기한·fingerprint·NULL offset 호환과 CHECK/UNIQUE를 검증했다. 기존 FOLLOW_UP의 V14 적용 전제도 보존했다.
- SQL 20개 모두 현재 HEAD 및 각 파일의 최초 추가 Commit과 바이트가 동일하다. 격리 fixture DB의 Flyway validation은 통과했다. 실제 개발/운영 DB의 적용 History/Checksum·실데이터를 조회한 증거로 확대하지 않는다. Entity/Schema 관련 persistence 테스트는 아래 두 Fixture 실패와 별개로 통과했다.

#### Flutter 실제 실행 결과

`flutter analyze --no-pub`는 **No issues found**, 전체 runner는 `done.success=true`다. 실제 테스트 34개 파일·549개 모두 통과했고 실패/오류/Skip/재실행은 0개다. JSON의 hidden 파일 로딩 이벤트 34개는 실제 테스트 수에 포함하지 않았다.

| 영역(중복 없는 파일 기준) | 실행 / 통과 | 실패 / 오류 / Skip |
| --- | ---: | --- |
| Authentication | 89 / 89 | 0 / 0 / 0 |
| Alarm | 72 / 72 | 0 / 0 / 0 |
| BoardingSession(세션 Notification 포함) | 317 / 317 | 0 / 0 / 0 |
| Device/Notification/Firebase smoke fixture | 51 / 51 | 0 / 0 / 0 |
| Transit/Configuration/Phase 6 회귀 | 20 / 20 | 0 / 0 / 0 |
| 합계 | 549 / 549 | 0 / 0 / 0 |

Auth Session·Secure Pair·Refresh single-flight·Logout·timeout/늦은 응답 폐기, 최초 Push 준비, 명시적 차량 선택/후보 refresh·즉시 TRACKING·알림 시점·원래 UUID 생성/복구/재확인·revision, foreground/initial/opened source별 정확한 ID 이동·stale/generation 차단, Device pending/FID/disable, 빈/JSON 500 뒤 인증·pending·미확정 생성 identity 보존이 자동 검증 범위다. 실제 Firebase SDK 초기화/APNs 등록·권한·OS background/terminated 실행/표시·실제 tap·기기 Secure Storage/재설치는 Mock 성공으로 완료 처리하지 않는다. iOS build·설치·실행·실 Provider/Google/FCM은 미실행이다.

#### 문서와 정책 및 남은 책임

README·app README, Requirements/API/Architecture/Database/Domain/Package/Roadmap/Local Development/Task List의 현행 정책·상태·관련 구현을 대조했다. 신규 Boarding의 명시적 선택·2정거장 전 기본/1정거장 전 선택·즉시 TRACKING/readiness 분리·세션당 준비 1회·목적지 완료만, 기존 NULL offset 목적지 Push·ON/OFF, Alarm ACTIVE/FOLLOW_UP·ARRIVED 우선/PASSED 뒤 ACTIVE·새 차량 감시, Notification source identity/atomic decision·recipient/lock 순서·retry/expiry·terminal 비재전송을 유지한다. 정상 시각의 Alarm 전체 FOLLOW_UP 경로는 실패한 Fixture를 보정해 최종 확인해야 한다.

두 local Handler 500의 한정된 계약, PASSED의 Event snapshot 기반 선택 위치와 동일 alert body/data key, AUD-11 지원 제한, AUD-12 미수정/TASK-814, 실제 Notification 구현 완료와 최종 실기기 미검증의 구분은 문서와 일치한다. 주요 문서의 로컬 Markdown 파일/앵커 219개를 검사해 깨진 참조 0개를 확인했다. ADR은 후행 결정/역사 기록으로 읽고 수정하지 않았다. 이번 대조에서 다른 Owner 문서의 새 명백한 오류는 발견하지 않았으며 불필요한 재작성도 하지 않았다.

| 남은 문제 | 심각도 / 실제 영향 | TASK-816 차단 여부 | 후속 책임 |
| --- | --- | --- | --- |
| Alarm 정상 진행 통합 Fixture의 동일 시각 | 회귀 검증 결함. 유효한 정상 후속 DB/lifecycle 검증이 최종 녹색이 아님 | 차단. 회귀 통과 조건 4·6·10 미충족 | TASK-816, AUD-02 인접 테스트 보정 |
| 기존 로그인 Fixture의 미커밋 외부 Transaction | 회귀 검증 결함. 알려진 로그인 경계와 충돌해 lock timeout, 로그인 관련 Fixture 정리/개수 격리 검토 필요 | 차단. 회귀 통과 조건 4·10 미충족 | TASK-816, AUD-10 인접 테스트 보정 |
| AUD-12 Host 의존 callback 5곳 | 알려진 P2 제품 결함 미수정. 감사 시각과 Alarm 목록 정렬의 UTC 의미 미보장, 기존 데이터의 zone 해석 불명확 | 조건 2는 PENDING. 명시적 책임 분리 근거와 필수 결함 포함 여부의 해석 필요. 이번 NOT READY의 독립적인 회귀 차단 2건과 구분 | TASK-814, 시간 원천/타입·기존 데이터 정책·JDBC/다중 zone/DST 검증 |
| 전체 API validation/error matrix·fallback | 기존 독립 검증 범위. 두 Handler 500 성공이 모든 Endpoint의 오류 계약을 보장하지 않음 | 현재 확인된 두 Handler 불일치는 해결. 이 범위 자체로 자동 차단하지 않음 | TASK-804 |
| 실제 iPhone·버스·Provider/FCM 검증 | 실제 freshness/무정차/회차·수신/표시/tap·quota/부하 미검증 | 자동 회귀와 분리된 후속 E2E. 다른 Task 번호만으로 필요한 correctness를 면제하지 않음 | TASK-710 수신/tap, TASK-711 최종 Notification E2E/regression, TASK-908 실제 버스/하차 알림 |
| 실제 process restart/graceful shutdown/recovery·응답 유실 | 자동 fixture는 실제 kill/restart/FCM accepted 뒤 crash·운영 DB 장애를 대체하지 않음 | 기존 운영 후속 책임. 이번 자동 회귀는 운영 안전성 완료가 아님 | TASK-811 및 TASK-711, latency/운영 부하는 TASK-810 |

AUD-12는 새 결함으로 중복 등록하지 않는다. 기존 완료 기준 9와 TASK-814 책임 문구, AUD-12의 명시적 감사·이관 결정은 TASK-816의 감사 처리와 TASK-814 제품 수정을 분리할 근거다. 그러나 기준 2의 “수정이 필요한 결함을 실제 코드에서 해결”과 기준 10의 “모든 필수 결함”이 미수정 UTC 결함까지 포함하는지에 대한 무조건적 면제는 명시되어 있지 않다. 따라서 제품 수정 완료를 PASS로 바꾸지 않았다. 두 회귀 실패를 해결하더라도 AUD-12를 TASK-816 필수 수정에서 제외하는 기존 책임 분리의 적용을 최종 확인해야 한다. 포함한다면 TASK-814 수정·호환성 검증 결과 연결까지 차단되며, 제외한다면 감사/근거/후속 소유는 충족하되 UTC 제품 결함과 TASK-814 `[ ]`는 남는다. 이번 작업에서 정책 선택이나 UTC 구현을 임의 확정하지 않는다.

#### 기존 10개 완료 조건 판정

| 완료 조건 | 상태 | 근거 | 남은 문제 |
| --- | --- | --- | --- |
| 1. AUD-01~13 검토·처리 기록 | PASS | 기존 기록 보존, 현재 코드·이번 결과·미검증·소유자 연결 | AUD-02/10 통합 Fixture 실패를 숨기지 않고 추가 기록 |
| 2. 수정 필요한 필수 제품 결함 해결 | PENDING | AUD-01~10/13 수정 유지, AUD-11 지원 정책 유지 | AUD-12 제품 미수정. TASK-814 책임 분리의 완료 기준 적용 확인 필요 |
| 3. 수정별 재현/회귀 방지 테스트 | PASS | 각 수정의 직접 테스트 존재·이번 전체 실행에 포함, AUD-12 Characterization 명시 | 기존 인접 Fixture 2건의 실패는 조건 4에서 차단 |
| 4. Backend·Flutter 주요 회귀 | BLOCKED | Flutter 549개 성공, Backend 1,270/1,272 성공 | Backend 실패 2건 해결·직접 검증 필요 |
| 5. 주요 문서 정합성 | PASS | 현행 정책/API 500·PASSED·Migration·UTC 미완료/책임 일치, 링크 219개 정상 | UTC 목표의 구현 격차 자체는 조건 2/ TASK-814로 구분 |
| 6. Alarm·Boarding 정책 보존 | BLOCKED | Boarding/Notification·Alarm 핵심 단위 회귀 통과 | 정상 시각의 Alarm 전체 FOLLOW_UP 완료 통합 회귀가 아직 통과하지 않음 |
| 7. 기존 DB/Migration 호환 | PASS(지원 범위) | 실제 격리 MySQL/Flyway·데이터 보존·validation, SQL 20개 최초 버전과 동일 | Legacy User 존재 DB는 명시적 지원 제외. 실데이터 이관 미검증 |
| 8. 실제 Provider/iPhone 미검증 구분 | PASS | Mock/loopback·자동 runner와 실제 E2E 책임 분리 | TASK-710/711/908·실 Provider/운영 검증 유지 |
| 9. 미수정 사유·후속 Task 명시 | PASS | AUD-11 제한·AUD-12→814, 804/710/711/908/811 책임 및 두 회귀 보정 범위 명시 | 다른 Task의 미완료를 감사 통과로 대체하지 않음 |
| 10. 필수 결함·검증 조건 모두 충족 | BLOCKED | 최종 NOT READY, 모든 기존 체크박스 유지 | 두 회귀 실패와 조건 2 PENDING 해소 전 완료 불가 |

#### 변경/Git 및 다음 작업

유일한 추적 파일 변경은 이 `docs/task-list.md`의 새 감사 기록 118행 추가다. `git diff --check`는 통과했고 최종 status는 ` M docs/task-list.md`, staged diff는 없다. 새 절을 제외한 본문이 HEAD와 완전히 같고 기존 체크박스 102개도 동일함을 비교했다. 시작 시 기존 미커밋 변경은 없었으며 TASK-804/814/816 및 TASK-710/711/908/811의 상태를 변경하지 않는다. 종료 시 이번 테스트 JVM·임시 MySQL·Ryuk Container가 남지 않고 기존 개발 MySQL Container ID가 그대로임을 확인했다. Git add/commit/push·branch 생성·reset/stash/clean/checkout·pull/fetch는 수행하지 않는다. Dependency·Migration·제품·테스트 변경과 다음 Phase 착수도 없다.

**다음 권장 작업은 하나다: TASK-816의 위 두 기존 회귀 Fixture만 별도 작업으로 보정하고 실패 경로를 재검증한다.** assertion·timeout·제품 안전 조건을 약화하지 않으며 성공한 전체 Suite를 이번 감사 중 다시 실행하지 않는다. 완료 체크는 이 보고서를 검토한 사용자가 별도로 결정한다.

### TASK-816 회귀 Fixture 보정 후속 검증 (2026-10-30)

**최종 권고: READY FOR FINAL DECISION.** 두 Fixture를 보정한 뒤 개별·클래스·AUD 인접 검증과 Backend 전체 1,272개가 모두 통과했다. 이전 회귀 차단 2건은 해소됐으며 제품 결함 수정 없이 정상 입력/Transaction Fixture의 문제였음을 확인했다. TASK-816 완료 기준 2의 AUD-12/TASK-814 책임 분리 및 최종 완료 판단은 여전히 남고 체크박스는 변경하지 않는다.

검증 대상은 `main`, HEAD `21153d6f1c3fb09408f8a7904493f8739337832b`와 아래 테스트 Fixture 변경이다. 시작 작업 트리는 깨끗했고 이전 감사는 해당 HEAD에 commit되어 있었다. 이전 **Backend 1,272개 중 2개 실패 / NOT READY** 기록과 모든 AUD 처리 기록은 당시 증거로 그대로 보존한다. e44d009 이후 시작 HEAD까지의 변경은 이 감사 문서뿐이므로 제품·기존 테스트 경계는 이전 실패 재현 당시와 같다.

#### 실제 원인과 최소 Fixture 보정

- 서울 `seoul_arrival_then_same_vehicle_one_stop_after_completes_follow_up()`은 같은 차량의 target(20)과 successor(30)에 같은 Provider 시각을 주어 AUD-02의 엄격 증가 계약과 충돌했다. 해당 정상 진행만 `20260923085959`→`20260923090000`으로 1초 증가시키고, 명시적 시각을 받는 `detail()` overload를 추가했다. 기본 helper의 시각과 다른 테스트는 유지했다.
- 서울 Mapper는 Asia/Seoul을 UTC로 해석하므로 위 시각은 `2026-09-22T23:59:59Z`→`2026-09-23T00:00:00Z`다. 기존 Mapper/Scheduler/decision Clock의 NOW(`2026-09-23T00:00:00Z`)보다 미래가 아니며 60초 freshness 안이다. Provider 시각이 존재하면 수신시각 비역행과 Provider 시각 엄격 증가를 검사하므로 고정 observedAt의 변경이 필요하지 않았다. FOLLOW_UP 시작은 NOW, 만료는 NOW+5분으로 기존 기한 안에서 후속 판단한다. MutableClock·전역 Clock 변경·제품 시각 검증 변경은 없다.
- 기존 ACTIVE→FOLLOW_UP→INACTIVE, 차량 ID·generation·호출 횟수 assertion을 보존했다. 실제 DB에 ARRIVED/ONE_STOP_AFTER가 각각 한 개씩이고 같은 Alarm/generation 및 영속 FOLLOW_UP Tracking Cycle을 공유함을 추가 확인한다. Event 생성·Lifecycle Transaction은 실제 제품 경로를 사용한다.
- 기존 `login_existing_google_identity_reuses_user()`는 클래스의 미커밋 User INSERT와 제품 LoginService의 REQUIRES_NEW INSERT가 UNIQUE 잠금에서 충돌했다. 해당 메서드에만 `@Transactional(propagation=NOT_SUPPORTED)`를 적용해 UserRepository의 saveAndFlush 호출 자체 Transaction이 반환 전에 commit되게 했다. 조회 assertion도 외부 테스트 snapshot 없이 commit된 결과를 확인한다. 클래스의 기본 `@Transactional`과 다른 persistence 테스트는 유지한다.
- 같은 클래스의 신규 로그인도 제품 REQUIRES_NEW commit을 외부 Rollback으로 정리할 수 없어, 해당 메서드에도 같은 제한적 경계와 cleanup을 적용했다. 두 테스트는 각각 UUID를 붙인 고유 Google Identity를 사용한다. 기존 User ID 재사용·Identity 개수 유지·Access Token subject·새 RefreshToken의 Hash/FK·원문 미저장을 보존/보강했다. 전체 `users.count()==1`은 해당 `(GOOGLE, providerUserId)`의 1행과 같은 내부 ID 검증으로 대체해 다른 Identity에 의존하지 않는다. 신규 로그인은 생성 전 0→후 1, 기존 로그인은 로그인 전후 1을 확인한다.
- 두 로그인 모두 `try/finally`에서 `cleanLoginIdentity()`를 호출하므로 본문 assertion 실패에도 정리가 실행된다. 별도 유효한 TransactionTemplate 안에서 **해당 고유 Identity의 User ID만** 조회해 RefreshToken→User 순서로 삭제하고 잔존 Token/Identity 0개를 확인한다. 실제 commit 실패는 테스트 실패로 전파한다. 전체 테이블 delete나 다른 Identity 삭제는 없다. 정상 로그인·cleanup은 클래스 전체 실행에서도 통과했다. assertion 실패를 인위적으로 주입한 추가 테스트는 실행하지 않았으며 실패 시 정리 보장은 finally 구조로 확인했다.

#### 단계별 실제 검증

Java/Gradle JVM 21.0.12.1·Wrapper 8.14.3, 기존 격리 `mysql:8.4.11` Testcontainers/Flyway를 사용한다. 공통 명령은 `env -u JAVA_HOME ./gradlew test --offline --max-workers=1`이며 1~3단계만 아래 클래스로 `--tests`를 지정했다. 실제 Provider/Google/FCM은 호출하지 않고 기존 Mock/loopback 경계를 유지했다.

| 단계 | 실제 범위 | 실행 | 통과 | 실패 / 오류 / Skip |
| --- | --- | ---: | ---: | --- |
| 1 | 기존 실패 두 메서드 | 2 | 2 | 0 / 0 / 0 |
| 2 | TransitMonitoringIntegrationTest 6 + RepositoryIntegrationTest 20 | 26 | 26 | 0 / 0 / 0 |
| 3 | BusAlarmEvaluatorTest 65 + LoginServiceConcurrencyIntegrationTest 8 + LoginMigrationIntegrationTest 1 | 74 | 74 | 0 / 0 / 0 |
| 4 | Backend 전체 79개 클래스(최종 1회) | 1,272 | 1,272 | 0 / 0 / 0 |

1단계 27초, 2단계 25초, 3단계 24초로 모두 BUILD SUCCESSFUL이다. 동일·역행 Provider 시각 차단과 정상 후속 관측의 같은 cycle 복구, 최초 로그인 실제 UNIQUE 경쟁/rollback 후 새 Transaction 복구·기존 User 다중 Session·독립 commit 및 기존 Identity/Migration 정책을 검증했다. 1단계의 두 메서드는 이전 FAIL에서 PASS로 바뀌었고, 2단계에서 신규 로그인과 cleanup을 포함한 같은 클래스의 나머지 테스트도 통과했다. 단계 확대는 이번 사용자 요청의 순서를 따르며 임의 반복 실행은 하지 않는다.

Backend 전체 회귀는 위 단계 통과 후 최종 1회 실행했고 **BUILD SUCCESSFUL(6분 39초)**이다. JUnit XML 79개 클래스의 suite 합계와 실제 testcase 개수가 일치하며 모든 테스트 클래스가 포함되고 제외/누락은 없다. 이번 실행 중 실패·오류·Skip과 실패 때문에 추가한 재실행은 모두 0이다. 단계별 확대의 중복 실행 102회를 포함한 이번 누적 실행은 **1,374회 전부 통과**이며, 서로 다른 사례는 전체 Suite의 **1,272개**다. 과거 감사의 실패/재실행 수나 Flutter 549개를 이번 실행 수에 합산하지 않는다. 테스트 메서드/사례를 추가·삭제하지 않아 이전 전체 1,272개와 동일한 수다.

| Backend 영역 | 실행 | 통과 | 실패 | 오류 | Skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| Alarm | 189 | 189 | 0 | 0 | 0 |
| BoardingSession | 410 | 410 | 0 | 0 | 0 |
| Notification/Device | 307 | 307 | 0 | 0 | 0 |
| Authentication/User | 102 | 102 | 0 | 0 | 0 |
| Transit/Metadata | 247 | 247 | 0 | 0 | 0 |
| Common/UTC/Application Context | 17 | 17 | 0 | 0 | 0 |
| 합계 | 1,272 | 1,272 | 0 | 0 | 0 |

수정된 두 클래스는 단독 선택 26개와 전체 Suite 양쪽에서 통과했다. Cleanup의 Identity/Token 0개 확인이 성공했고 신규·기존 로그인 데이터가 다른 테스트에 남는 문제는 관측되지 않았다. 별도 실행 순서 강제나 성공 테스트의 추가 반복은 하지 않았다. 실제 MySQL/Flyway·Notification/Boarding·동시 로그인 및 API 500 회귀도 모두 통과했으며 새로운 제품 결함이나 회귀 실패는 발견하지 않았다.

1~3단계 JUnit XML은 `/private/tmp/stopbell-task816-fixture-step1-results`, `step2-results`, `step3-results`(동일 `stopbell-task816-fixture-` 접두사)에 보존했다. 최종 XML은 `/private/tmp/stopbell-task816-fixture-full-results`와 현재 `backend/build/test-results/test`, HTML은 `backend/build/reports/tests/test/index.html`이다. 각 실행 로그는 `/private/tmp/stopbell-task816-fixture-step1.log`·`step2.log`·`step3.log`·`full.log`(동일 접두사)이며 과거 감사 결과 파일은 덮어쓰지 않았다.

이번 수정은 Backend 테스트 Fixture만이므로 Flutter analyze/test는 재실행하지 않았다. 이전 감사의 analyze No issues·549/549 PASS는 **이전 실행 결과**로 보존한다. 실제 개발/운영 DB·기존 Named Volume·실제 Google/Transit/FCM·Firebase Credential·iPhone·Backend 서버/터널은 사용하지 않았다. 실제 Provider/iPhone·운영 restart 검증은 이번 전체 자동 통과로 대체하지 않는다.

#### 후속 책임과 상태 유지

제품 Java·Flutter 제품/테스트·Migration·Dependency·Gradle/Profile·Docker Compose·Firebase/Credential·Spring Security/Transaction 설정·기존 ADR·devlog는 변경하지 않는다. 변경 파일은 위 테스트 두 개와 이 Task List뿐이다. 기존 assertion의 의미를 보존하고 정상 입력·Transaction/cleanup만 보정했다. AUD-02 Evaluator와 AUD-10 LoginService의 REQUIRES_NEW·Identity UNIQUE·Session 원자성/Rotation·오류 분류는 유지한다. `git diff --check`는 통과했고 staged/미추적 신규 파일은 없다. 종료 시 임시 MySQL/Ryuk Container가 남지 않고 기존 개발 MySQL Container ID가 그대로임을 확인했다.

AUD-12의 Host 의존 callback 5곳은 미수정이며 TASK-814의 시간 원천/타입 선택·JDBC/다중 zone/DST·기존 데이터 호환 정책 책임이 남는다. 실제 Provider/iPhone 및 운영 restart는 자동 테스트로 대체하지 않으며 TASK-710/711/908/811, 전체 API 오류 계약은 TASK-804가 계속 소유한다. TASK-804/814/816을 포함한 모든 체크박스를 그대로 유지하며 Git add/commit/push·branch/reset/stash/clean/checkout/pull/fetch는 수행하지 않는다. Backend 전체 통과 여부와 별개로 AUD-12의 완료 기준 2 책임 분리 및 TASK-816 완료 여부는 사용자 최종 판단으로 남긴다.

이전 완료 기준 중 4(주요 회귀)는 이번 Backend 1,272개 통과와 제품 변경 없는 기존 Flutter 549개 통과 근거로 PASS, 6(Alarm·Boarding 정책 보존)은 정상 FOLLOW_UP DB/Event 경로와 전체 관련 회귀 통과로 PASS로 재평가한다. 조건 2는 AUD-12 제품 미수정/책임 분리 판단으로 PENDING이며 조건 10도 최종 판단 전 PENDING이다. 나머지 기존 PASS 근거는 보존한다. 이 변화는 이전 감사 표의 당시 BLOCKED/NOT READY 기록을 삭제하거나 TASK-816을 완료 처리하는 것이 아니다.

### TASK-816 최종 완료 결정 (2026-10-30)

사용자 승인에 따라 AUD-12의 실제 제품 수정을 TASK-814의 독립 완료 조건으로 분리하고 TASK-816의 감사·수정·검증 범위를 완료한다. AUD-01~13은 각각 검토하고 처리 결과와 후속 책임을 기록했다. TASK-816이 직접 수정하기로 확정한 필수 제품 결함은 수정과 회귀 검증을 마쳤으며, Flutter 안내 문구와 두 Controller의 API 500 오류 응답 계약 수정도 반영했다. AUD-11의 지원 범위와 Migration 제한, 기존 Alarm·BoardingSession 정책을 보존하고 주요 문서 정합성을 검증했다.

이전 최종 감사의 NOT READY 판정은 당시 결과로 유지한다. 이후 `TransitMonitoringIntegrationTest.seoul_arrival_then_same_vehicle_one_stop_after_completes_follow_up()`과 `RepositoryIntegrationTest.login_existing_google_identity_reuses_user()`의 Fixture를 보정하고 직접 검증 및 Backend 전체 회귀를 통과해 두 회귀 차단 조건을 해소했다. 제품 계약은 변경하지 않았다. Backend 전체는 1,272/1,272 통과, 실패·오류·Skip 0이다. Flutter는 이전 최종 감사 실행에서 analyze No issues와 전체 549/549 통과, 실패·오류·Skip 0을 확인했으며 마지막 Backend Fixture 보정 후 재실행하지 않았다. 이번 최종 문서 작업에서는 테스트를 실행하지 않았다.

AUD-12에서 User의 `onPrePersist`·`onPreUpdate`, RefreshToken의 `onPrePersist`, Alarm의 `onPrePersist`·`onPreUpdate`에 남은 인자 없는 `LocalDateTime.now()`의 Host Timezone 의존성을 재현하고 MySQL 저장·Timezone 특성, 기존 UTC 경로와의 차이 및 기존 데이터 위험을 분석했다. **제품 결함은 아직 수정되지 않았으며 TASK-816 완료는 그 해결을 의미하지 않는다.** UTC 시간 원천 결정과 Callback 제품 수정, JDBC/JPA/MySQL 계약 및 다중 Timezone·DST 검증, 기존 저장 데이터의 시간 의미와 호환성·운영 Migration 정책 결정, 최종 UTC 회귀는 미완료 TASK-814의 독립 책임이다.

기존 완료 기준 10개는 유지한다. 기준 2와 10은 TASK-816이 직접 수정하기로 확정한 필수 결함의 수정·검증 완료, AUD-11의 지원 범위 및 제한 확정, AUD-12의 재현·분석과 TASK-814로의 명시적 제품 수정 책임 이관을 함께 고려해 충족으로 판정한다. 이는 AUD-12 결함을 면제하거나 삭제하는 결정이 아니다. 다른 Task가 소유한 실제 Provider·FCM·iPhone E2E는 자동 회귀와 구분하며 미검증 상태를 유지한다.

- TASK-804 `[ ]`: 전체 API validation/error matrix, Security/401, 예상 밖 Exception 및 최종 regression 검증
- TASK-814 `[ ]`: AUD-12 UTC 제품 수정과 위 시간·데이터·Migration 계약의 결정 및 검증
- TASK-710 `[ ]`: 실제 iPhone Notification 수신·표시·tap 검증
- TASK-711 `[ ]`: Notification 최종 E2E·regression 검증
- TASK-908 `[ ]`: 실제 Provider와 버스를 이용한 탑승·하차 알림 및 iPhone E2E 검증
- TASK-811 `[ ]`: 실제 restart·graceful shutdown·recovery 운영 검증

최종 판정:

```text
TASK-816: COMPLETED

Backend: 1272 / 1272 PASS
Flutter: 549 / 549 PASS (prior full regression)
AUD-01~13: 감사 처리 및 후속 책임 정리 완료
AUD-12 UTC 제품 수정: TASK-814로 이관, 미완료
실제 Provider/FCM/iPhone E2E: 별도 Task에서 검증 예정
```

### TASK-816 권장 수정 순서와 독립 검증

| 순서 | 작업 그룹 | 대상 | 검증 초점 |
|---|---|---|---|
| A | 알림 신뢰성 | AUD-04 Scheduler 실행 자원 분리 → AUD-08 재확인 후 준비 알림 판단 보완 | 느린 Polling/Dispatch 상호 지연·각 작업 non-overlap, 미소비 구간 재확인→동일 source/목적지 전이와 Event 소비·TTL 회귀 |
| B | 데이터 및 도착 판단 정확성 | AUD-03 불완전한 metadata snapshot 차단 → AUD-02 Provider 시각 충돌 검증 → AUD-09 차량별 mapping 실패 격리 | 빈 occurrence complete 거부·기존 metadata 보존, freshness 내 동일/역행 시각, 오류 차량과 정상 차량 혼합 관측 |
| C | Flutter 및 인증 안정성 | AUD-01 최초 Alarm Push 준비 연결 → AUD-05 timeout 이후 늦은 mutation 재전송 차단 → AUD-06 인증 요청의 유한 종료 시간 보장 | 최초 화면 create→activate 준비·사용자 선택, timeout→늦은 401, 완료되지 않는 인증 transport·늦은 결과와 기존 Refresh 회전/세션 보존 회귀 |
| D | 기타 결함 및 개선 | AUD-07·10·11·12·13 및 관련 문서 불일치 | 누적 후보 조회 지연, 최초 로그인 경쟁, 지원 과거 DB 데이터 업그레이드, TASK-814 결과 연결, PASSED 정보의 계산→영속→전송 표시 |

그룹은 관리·권장 구현 순서이며 한 번에 전체 코드 수정을 요구하지 않는다. 각 그룹 착수 시 변경 영향과 의존 관계를 확인하고 독립적으로 수정·검증할 수 있는 범위로 나눈다. 그룹 내 항목도 개별 처리·검증할 수 있다. P2라도 실제 서비스에 직접 영향을 미치는 근거가 있으면 우선순위를 재검토하고 사유를 기록한다. 성능 해결 방식·Schema·인증 정책을 등록 단계에서 확정하지 않는다.

### TASK-816 완료 기준과 기존 Task 책임

1. AUD-01~AUD-13을 각각 검토하고 처리 결과를 기록한다.
2. 수정이 필요한 결함을 실제 코드에서 해결한다.
3. 각 수정에 해당 문제를 재현하거나 방지할 수 있는 테스트를 보강한다.
4. Backend·Flutter의 기존 주요 회귀 테스트를 통과한다.
5. 문서와 코드의 주요 불일치를 해결한다.
6. 기존 Alarm과 BoardingSession의 정책 및 동작을 유지한다.
7. 기존 DB 데이터와 적용된 Migration의 호환성을 보존한다. AUD-11의 과거 데이터 지원 전제를 명시하며 사용자 identity를 임의 합성하거나 적용된 Migration을 임의 수정하지 않는다.
8. 실제 Provider 또는 iPhone 검증이 필요한 항목을 자동 테스트와 구분해 기록한다.
9. 수정하지 않기로 결정한 항목은 이유와 후속 관리 TASK를 명시한다.
10. 모든 필수 결함의 처리와 검증이 완료된 경우에만 TASK-816을 `[x]`로 변경한다. 등록·부분 수정·자동 테스트 통과만으로 완료하지 않는다.

- TASK-814: AUD-12 및 UTC 관련 문서 불일치의 구현·검증 책임을 유지한다. TASK-816은 해당 처리 결과·검증 근거를 연결하며 중복 구현하거나 TASK-814를 대신 완료 처리하지 않는다.
- TASK-804: 두 Controller의 500 형식 수정·집중 검증 결과는 위 [A안 구현 결과](#api-500-a안-구현-결과-2026-10-30)에 연결한다. 전체 API 오류 matrix·Security/401·예상 밖 Exception 처리와 최종 regression은 TASK-804 책임을 유지하며, 이번 집중 검증은 TASK-804/816 완료를 뜻하지 않는다.
- TASK-710: 실제 iPhone Notification 수신·tap 검증 책임과 현재 상태를 유지한다.
- TASK-711: Notification 최종 E2E·regression 검증 책임과 현재 상태를 유지한다. 그룹별 방지 테스트는 이 최종 검증을 대체하지 않는다.
- TASK-908: 실제 버스 탑승·하차 알림 E2E 검증 책임과 현재 상태를 유지한다.

TASK-816은 감사 후속 결함 수정이며 기존 Task의 검증 책임을 대체하지 않는다. TASK-816 자동 테스트가 통과해도 실제 iPhone E2E 완료로 간주하지 않는다. 기존 Phase 8 실행 dependency는 유지하며, 위 A→B→C→D는 TASK-816 내부의 권장 순서다.

---

# Phase 9 - 탑승 차량 식별 및 하차 알림

목표:

사용자가 목록에서 선택한 동일 차량을 목적 정류장까지 추적해 목적지 이전 하차 준비 알림을 제공한다. 실제 탑승 여부를 검증하지 않는다. 기존의 정류장 도착 알림과 별도의 사용자 목적을 지원하며 기존 기능과 lifecycle 의미를 보존한다.

범위와 설계 원칙:

- 정확성 > 편의성: 잘못된 차량 확정이나 거짓 하차 알림을 줄이는 것을 우선한다.
- GPS에 의존하지 않는다. 사용자 단말 위치 권한·백그라운드 GPS 추적을 요구하지 않으며, Provider GPS 좌표도 차량 식별·하차 판단의 필수 근거나 GPS 거리 기반 대체 판정으로 사용하지 않는다. GPS 없이 확보 가능한 근거와 한계를 TASK-901에서 확인한다.
- 사용자 차량 확인을 우선 지원한다. 실제 응답에서 확인된 차량 표시 정보만 제공하며, 사용자에게 보이는 정보와 Provider 추적 식별자의 연결 가능성을 먼저 검증한다.
- 차량 자동 추천은 후보와 추천 근거를 제시하는 기능이며 탑승 차량 자동 확정과 구분한다. TASK-902 당시의 전체 차량번호 입력·대조 UX는 TASK-908에서 번호 입력 없는 목록 선택·명시적 등록으로 대체했다. 추천 순위나 단일 후보를 이유로 자동 선택·확정하지 않는다. 상세 사용자 보장은 `requirements.md`, 추천 규칙은 ADR-011을 따른다.
- 식별 불확실 시 임의 확정하지 않는다. 복수 후보도 사용자가 목록에서 직접 선택하며, 차량 식별자 누락·변경, stale data, 방향·회차 모호성은 필요한 판단의 불확실성을 표현한다. TAGO 원본 생성시각 부재 자체를 stale로 취급하지 않는다.
- 추적 중 다른 차량으로 자동 전환하지 않는다. 대상 차량 소실이나 식별 연속성 상실 시 알림 판단을 보류하고 사용자 재확인 또는 종료로 복구한다.
- 기존 도착 알림의 기능·판정·상태 전이를 보존하고, 기존 NotificationEvent/NotificationDelivery, Device, durable pending dispatch, FCM 및 failure/retry 인프라를 재사용한다. 최소 source 확장은 TASK-903에서 정하고 TASK-905/906에서 연결했다.
- TASK-901 결과에 따라 지원 범위와 후속 설계를 조정할 수 있다. 필요한 근거를 확보하지 못하면 제약·미지원 범위·추가 판단을 기록하고, GPS나 추측으로 보완하여 구현 완료로 처리하지 않는다.
- 지하철 하차 알림은 향후 별도 Phase로 계획하며 이번 Phase 9에는 조사·설계·구현을 포함하지 않는다.

Phase 등록 당시 계획과 아래 날짜별 구현·검증 기록을 보존한다. 현재 TASK-901~907은 완료됐고 TASK-908의 번호 입력 제거·즉시 TRACKING·신규 1/2정거장 전 준비 알림 변경은 구현됐지만 실제 버스/iPhone E2E는 미완료다. TASK-902/903 당시 설계와 TASK-908의 후행 변경은 ADR-011/012와 Owner 문서를 함께 따른다. 기존 TASK-710/711과 Phase 8의 상태·검증 책임은 유지하며 TASK-908이 이를 대체하지 않는다.

- [x] TASK-901 TAGO·서울 버스 차량 식별 가능성 조사
  - 목적: 경기 TAGO와 서울 버스 API에서 실제 탑승 차량을 사용자가 확인하고 동일 차량을 지속 추적할 수 있는지 GPS 없이 검증한다.
  - 완료 기준:
    - 공식 명세와 민감정보를 제거한 실제 응답을 근거로 차량 추적 식별자, 차량번호 등 사용자 확인용 표시 정보, 양자의 연결 관계, 노선·방향·정류장 occurrence·진행 정보, 갱신 시각과 지연을 Provider별로 정리한다. 제공되지 않는 값은 추정하지 않는다.
    - 식별자의 유효 범위·연속성·변경/누락 가능성, 차량별 조회 가능 여부, 호출량·quota 제약과 GPS 없는 하차 판단에 필요한 근거를 확인한다. 확인하지 못한 항목과 표본 검증의 한계를 명시한다.
    - 사용자 확인·자동 추천·지속 추적의 가능/불가/미확정 범위와 후속 설계 조정안을 기록한다. 근거가 부족한 Provider/노선은 지원 보류 또는 추가 조사 대상으로 남긴다.
  - 선행 관계: Phase 3의 조사 결과와 Phase 5의 Provider/Observation 계약을 검토한다. TASK-902/903의 입력이며 TASK-710/711이나 Phase 8 완료를 조사 착수 조건으로 삼지 않는다.

- [x] TASK-902 탑승 차량 자동 추천 알고리즘 설계
  - 목적: GPS 없이 사용 가능한 근거로 탑승 차량 후보의 우선순위를 제시하고, 사용자 확인으로 이어지는 추천 규칙을 설계한다.
  - 완료 기준:
    - TASK-901에서 검증한 입력만으로 후보 선정·제외·순위·추천 근거를 정의한다. 단일 후보, 복수 후보, 동률, 후보 없음, stale/식별 불가 사례별 결과와 한계를 명시한다.
    - 추천 상태와 확정 상태를 구분하고, 사용자 확인에 필요한 표시 정보와 재조회 시 후보 변경 처리 원칙을 정의한다. 자동 확정은 기본 흐름에 포함하지 않으며, 도입을 제안한다면 별도의 근거와 개발자 설계 판단을 요구한다.
    - 대표 정상/모호 사례의 입력과 기대 결과를 문서화하고, 식별 불확실 시 미확정 유지 및 추적 차량 자동 전환 금지를 확인한다. 실제 알고리즘 코드는 후속 구현에 남긴다.
  - 선행 관계: TASK-901 완료 및 지원 가능 범위 확인 후 수행한다. TASK-903/904의 추천·확정 경계 입력이 된다.
  - 설계 결과: [ADR-011](adr/ADR-011-bus-vehicle-recommendation-and-user-confirmation.md). 후보 선정·보조 정렬·추천/확정 구분·재조회·Provider별 지원 범위와 정상/예외 사례를 정의한다. 실제 알고리즘·API·화면 구현은 후속 Task에 남긴다.

- [x] TASK-903 탑승 세션 및 하차 알림 도메인 설계
  - 목적: 확인된 탑승 차량, 목적 정류장 occurrence와 하차 알림의 수명을 기존 도착 알림과 혼동하지 않는 Domain/API/Persistence 계약으로 설계한다.
  - 완료 기준:
    - 인증된 User의 소유권, 후보 조회→사용자 차량 확인→추적 시작→알림/종료 흐름, 취소·중복 요청·동시 변경·재확인·만료·재시작 시 동작을 정의한다. 차량 확정/조회/취소 등 필요한 API 계약과 최소 저장 범위를 결정한다.
    - Provider namespace와 차량 식별자의 유효 범위, 노선·방향·목적 occurrence 및 동일 차량 연속성 검증 규칙을 정의한다. 차량 소실·ID 변경·회차·관측 실패는 판단 불가로 보존하고 다른 차량으로 자동 연결하지 않는다.
    - GPS 없이 하차를 알릴 시점과 충분한 근거, 놓친 관측·목적지 통과·불확실 시 처리, 세션 종료 및 중복 방지 기준을 정의한다. 지원 불가능한 시점/노선은 명시하고 임의 threshold나 보장되지 않는 정확도를 약속하지 않는다.
    - 기존 도착 알림 lifecycle과의 경계, Notification 재사용에 필요한 Event identity·수명·recipient·tap 계약의 최소 확장을 결정하고 관련 Owner 문서/ADR을 동기화한다. 구체 Entity/Schema/API field는 이 Task 전에는 확정하지 않는다.
  - 선행 관계: TASK-901/902 완료 후 수행한다. 기존 Alarm/Transit 및 Phase 7 Notification 계약을 검토하며 TASK-904~907의 구현 기준이 된다.
  - 설계 결과: [ADR-012](adr/ADR-012-boarding-session-and-alight-notification-contract.md). 번호 확정과 즉시 추적, occurrence 간격 2/1 및 세션당 PREPARE_ALIGHT 최대 1회, snapshot/checkpoint와 PT2M 조건부 자동 복구, PT4H 수명·PT30S 준비 TTL을 정의한다. Source 분기·복구 gate·LIMIT 이전 filtering과 Alarm 조회 예산·전송 시 current Session/revision 재검증·최초 저장 전 TTL 초과의 기회 미소비를 Owner 문서에 동기화한다. 신규 Schema/API/Worker는 후속 구현이며 이번 작업에서 코드·Migration·외부 호출·커밋은 수행하지 않는다.

- [x] TASK-904 탑승 차량 후보 조회 API 구현
  - 목적: 사용자 확인과 추천에 필요한 차량 후보 및 근거를 인증된 Client에 제공한다.
  - 완료 기준:
    - TASK-902/903 계약에 따라 검증된 노선·운행 문맥의 후보, 확인 가능한 차량 표시 정보, 추천 근거·데이터 시점·식별 한계를 제공한다. Provider raw ID/표시 정보의 관계를 추측하지 않는다.
    - 후보 없음, 복수 후보, stale/부분 실패/Provider 실패를 구분하며 실패를 확정 가능한 정상 후보로 반환하지 않는다. 후보 조회 자체로 탑승 차량이나 세션을 자동 확정하지 않는다.
    - 인증/입력 검증, 추천 순서, 불확실 응답, GPS 비의존성과 기존 조회 기능 보존을 관련 API 검증으로 확인한다. 외부 조회는 기존 Provider client와 공유 가능한 조회 경로를 재사용하고 호출 증폭을 확인한다.
  - 구현·검증 결과: JWT 후보 API, Provider별 번호/ID·시각·진행 검증, index 간격 정렬, bounded memory candidateRef와 AVAILABLE/EMPTY/PARTIAL·503 구분을 구현했다. 후보 관련 48개 및 TAGO count 보강 2개를 포함한 전체 Backend 504개 테스트를 실패·skip 없이 통과했다. 실제 Provider 호출, 세션/추적/Notification·Flutter 변경, Dependency·Migration 추가는 없으며 응답·호출량·reference 계약은 [API](api.md#후보-조회)를 따른다.
  - 선행 관계: TASK-901~903 완료 후 수행한다. TASK-905/907에서 실제 후보 확인 흐름을 연결한다.

- [x] TASK-905 특정 차량 추적 및 하차 시점 판단 구현
  - 구현 완료: Persistence/API·특정 차량 polling·checkpoint·준비 후보/종료 판정과 3단계 재시작 자동 복구·동일 차량 재확인·runtime revision 정합성 구현. 전체 Gradle 733개(기존 676개 포함), Boarding 218개 통과, 실패·오류·skip 없음. 실제 Provider 지원·실차 E2E는 보류하며 Notification durable 중복 억제·Event/Delivery/FCM 연결은 TASK-906 범위다.
  - 목적: 사용자가 확인한 차량만 추적하고, 설계된 근거를 충족할 때 하차 알림 후보를 생성한다.
  - 완료 기준:
    - TASK-903의 차량 확인·세션 시작/조회/취소 계약을 구현하고, 조회 뒤 변한 후보를 확정하는 요청과 중복·동시 요청을 안전하게 처리한다. 소유권과 유효한 차량/목적 occurrence를 검증한다.
    - 확정한 동일 차량과 traversal만 추적한다. 차량 소실·식별자 변경·관측 역행·회차 모호성·stale/Provider 실패·재시작 시 설계된 판단 불가/복구 규칙을 적용하고 다른 차량으로 자동 전환하지 않는다.
    - GPS 없이 설계된 시점의 알림 후보와 종료/중복 억제를 구현한다. 정상 진행, polling 누락, 목적지 통과, 불확실 관측, 취소·재시작 사례와 기존 도착 알림 회귀를 검증한다.
  - 선행 관계: TASK-903/904 완료 후 수행한다. Notification 전송 연결은 TASK-906에서 수행한다.

- [x] TASK-906 기존 Notification 시스템과 하차 알림 연동
  - 1단계 구현: V16의 기존 데이터 보존 source 확장, ALARM/BOARDING_SESSION Event 모델·Session identity Repository·공통 Delivery 기한 검증, LIMIT 이전 ALARM 조회와 직접 attempt 진입 차단을 구현했다. 이 단계에서는 Session 전송을 차단했고 아래 2·3단계에서 원자 저장과 source-aware 전송을 연결한다. 실제 Provider 지원은 계속 보류한다.
  - 1단계 검증: MySQL 8.4.11 Testcontainers로 V14/V15 기존 Event·네 Delivery 상태·Session 보존, source별 CHECK/UNIQUE·recipient 없는 저장·Delivery 기한·Alarm batch 보호와 Session 미발송을 확인했다. 기존 733개와 신규 32개를 포함한 전체 Gradle 765개 테스트가 실패·오류·skip 없이 통과했다. API/FCM payload·Dependency 변경과 실제 외부 Provider 호출은 없다.
  - 2단계 구현: Session lock 아래 checkpoint/상태·PREPARE_ALIGHT·최초 Device별 Delivery 원자 저장, 최초 INSERT 전 TTL·기회 미소비, Session identity 중복 억제·0 recipient 고정, API/polling 만료·취소·완료·연속성 상실의 PENDING 종료 및 새 transaction commit/UNIQUE 재확인을 연결했다. 이 단계에서는 Session FCM/Worker를 차단했으며 실제 Provider 지원 보류를 유지했다. 신규 Migration·Dependency·API/FCM payload 변경은 없다.
  - 2단계 검증: MySQL 8.4.11 Testcontainers로 TTL microsecond 경계·lock 대기·고정 recipient·재시작/재확인 중복 억제·실제 FK/UNIQUE 실패 rollback·commit 응답 유실·취소/만료 경합 및 모든 API 만료 경로를 검증했다. 기존 765개와 신규 48개를 포함한 전체 Gradle 813개 테스트가 실패·오류·skip 없이 통과했으며, 기존 Alarm 및 Worker/payload/retry 회귀와 Session 미발송을 보존했다. 실제 Transit Provider/FCM 호출은 없다.
  - 3단계 구현: 두 source의 명시적 전송 hint와 FCM payload, Session→Delivery→Device current lock, commit 확인 후 snapshot 및 전송 직전 runtime revision/checkpoint/기한 재검증, 기존 Handler의 durable retry/result/cleanup을 연결했다. 단일 Worker는 source별 만료·Alarm 우선 due·bounded READY window SQL 조회를 수행하며, gate 미준비는 attempt를 소비하지 않고 시작 후 호출 생략은 count/recovery를 보존한다. Dependency·Migration·실제 Provider 지원 차단 및 Flutter 범위는 변경하지 않는다.
  - 3단계 검증 완료 (2026-10-20): Java 21·Gradle Wrapper 8.14.3·MySQL 8.4.11 Testcontainers에서 기존 813개와 신규 62개를 포함한 전체 875개 테스트가 실패·오류·skip 없이 통과했다. Session dispatch 통합 54개, 실제 Worker→loopback HTTP v1 FCM, 2,000개 독립 미복구/보류 Session backlog와 EXPLAIN, Session/Device lock 경합·REPEATABLE READ·commit 응답 유실·전송 직전 gate/TTL 변경·stale 결과를 검증했다. 기존 ALARM 네 payload·lifecycle·Device·retry/expiry 회귀와 diff/문서 참조 검사를 통과했다. Backend 연결 완료이며 실제 Provider 생성/재확인 차단과 단일 Backend 전제·최종 검사/외부 요청의 race·실기기 표시 한계는 유지한다. TASK-710/711·907/908을 대체하지 않는다.
  - 목적: 하차 알림 후보를 기존 Notification 인프라로 전달하면서 기존 알림의 correctness를 보존한다.
  - 완료 기준:
    - TASK-903에서 확정한 최소 확장으로 logical Event·Event 시점 recipient Delivery·세션 상태 변경의 원자성 및 중복 방지를 연결한다. 기존 Device 소유권/registration, durable dispatch, FCM, retry/expiry 경로를 재사용한다.
    - 하차 알림 payload와 tap 진입에 필요한 최소 hint를 정의된 계약대로 연결한다. 기존 도착 알림과 구분하며 Provider accepted를 실제 기기 표시 성공으로 간주하지 않는다.
    - 중복 후보, recipient 없음, Device/계정 변경, 취소·만료·stale 결과, 전송 실패/retry·restart recovery 및 기존 도착 알림의 회귀를 검증한다. 별도 전송 인프라는 도입하지 않는다.
  - 선행 관계: TASK-905 및 기존 Notification TASK-701~711 완료 후 수행한다. TASK-710/711의 미완료 검증을 이 Task로 대체하거나 완료로 간주하지 않는다.

- [x] TASK-907 Flutter 하차 알림 UI 구현
  - 목적: 사용자가 탑승 차량을 직접 확인하고 목적 정류장을 선택한 뒤 하차 알림의 상태와 한계를 이해할 수 있게 한다.
  - 완료 (2026-10-21): 1·2·3단계 구현·자동 검증 완료. 기존 324개를 보존하고 신규 106개를 추가한 전체 Flutter 테스트 430개가 실패·오류·skip 없이 통과했다. 변경 Dart 파일 format 검사, `flutter analyze --no-pub`, `flutter test --no-pub` 및 diff 검사를 통과했다. 전체 번호 대조·Session 생성/복구/관리와 함께 source-aware payload, foreground 안내/사용자 선택, background tap·terminated initial callback, 정확한 Session ID 읽기 전용 상세, Auth generation·User/login identity·navigation epoch 보호, bounded logical dedupe 및 확정 전 Push 권한/Device 준비를 연결했다. 통신 실패·404·terminal/보류·JWT refresh·Firebase 초기화 재시도·계정 전환/늦은 응답·후보 만료·원래 UUID/body 보존을 Mock/Fake로 검증했다. 실제 Firebase/iPhone/OS terminated 실행·버스 E2E는 수행하지 않았으며 TASK-908 및 기존 TASK-710/711에 남는다. 실제 `BOARDING_TRACKING_UNSUPPORTED` 지원 차단을 유지하며 새 Dependency·Migration·Backend API 변경은 없다. 상세 경계는 [Architecture](architecture.md#flutter-세션-알림-수신과-상세-이동-task-907-3단계)를 따른다.
  - 단계 범위:
    - 1단계: 별도 하차 알림 진입점, 기존 노선 검색/정류장 조회 재사용, 목적·선택 승차 occurrence 선택, 후보 API/모델과 비교·제한 안내 UI 구현. AVAILABLE/EMPTY/PARTIAL·오류/재조회, Auth generation·늦은 응답 보호와 Flutter 테스트 포함. 실제 Session POST와 추적 지원 활성화는 포함하지 않는다.
    - 2단계 (완료): 사용자 전체 차량번호 대조·확정, Session 상태/취소/재확인 연결. pending 생성 intent를 전송 전에 Secure Storage에 보존하고 같은 User/login Session에서 UUID 조회를 우선하며 자동 재전송·새 UUID 대체를 차단한다. 문맥이 없으면 Route/occurrence를 재선택하고 최신 후보·번호를 다시 대조한다.
    - 3단계 (완료): 세션 FCM 세 수신 경로·사용자 선택/정확한 ID 상세·인증/중복/경쟁 보호와 확정 전 Push 준비를 연결하고 기존 Alarm·Flutter 전체 회귀를 검증했다. 자동화 callback 검증은 실제 Provider/iPhone 수신 성공을 뜻하지 않는다.
  - 완료 기준:
    - 목적 occurrence 문맥의 후보 조회·추천 근거 표시→사용자 차량번호 확인·확정과 즉시 추적→상태 조회/취소 흐름을 연결한다. 별도 Start 버튼은 제공하지 않으며 추천과 확정을 구분하고 기존 버스 도착 알림 UI를 보존한다.
    - loading/empty/error/retry, 복수 후보·식별 불가·stale 후보·차량 소실·재확인/종료 상태를 표시한다. 불확실한 차량을 자동 선택/확정하거나 추적 차량을 자동 교체하지 않으며 위치 권한/GPS를 요구하지 않는다.
    - 하차 알림 수신/tap에서 인증·소유권·현재 세션을 재조회하고 종료·취소·접근 불가 상태를 처리한다. UI/API 연동과 기존 인증·도착 알림 회귀를 검증한다.
  - 선행 관계: Phase 6 및 TASK-904~906 완료 후 수행한다. 화면 구성 검토는 TASK-903 계약 확정 뒤 가능하나 API/Notification 연결 전에는 완료 처리하지 않는다.

- [ ] TASK-908 실제 버스 탑승 E2E 검증
  - 하차 전 단일 알림 정책 변경 (2026-10-29): 신규 세션의 2정거장 전(기본)/1정거장 전 설정, 목적지 이전 한 번의 준비 알림·관측 누락 대체·등록 시 이미 구간인 차량의 즉시 판단, 이동 exact stId/stOrd 기반 진행·반복 occurrence 구분과 목적지 완료 분리, Flutter 시점·전송 상태 UI 및 기존 정책을 보존하는 V20을 반영했다. Backend 전체 1,027개·Flutter 전체 470개 테스트와 analyze·Dart format·JAR/iOS build·diff·문서 링크 검증을 통과했다. 실측 가정·호환 및 자동 검증 결과는 [증거 보고서 §28](research/TASK-908-provider-evidence.md#28-하차-전-단일-알림과-이동-진행-평가-2026-10-29)을 따른다. 실제 무정차 stOrd 갱신·iPhone 수신/tap은 미검증이며 실제 Provider/FCM·개발 DB 적용·Git 변경 명령 없이 `[ ]` 유지
  - 즉시 TRACKING 요구 변경 (2026-10-29): VERIFYING 상태·전이 제거, 정차·이동·위치 필드 누락·정상 empty detail의 즉시 TRACKING 등록, 관측 readiness와 알림 gate 분리, V18 보존·V19 호환 전환, Flutter 즉시 추적 화면과 차량 위치 확인 중 표시를 반영했다. Backend 전체 987개·Flutter 전체 456개가 실패·오류·skip 없이 통과했고 analyze·format·diff·문서 링크 검증을 통과했다. 서울 143번 기존 기록의 오프라인 재생과 회귀 결과, 이동 관측만 계속될 때의 알림 한계 및 실기기 잔여 검증은 [증거 보고서 §27](research/TASK-908-provider-evidence.md#27-차량-선택-즉시-tracking-변경-2026-10-29)을 따른다. 실제 Provider/FCM·기존 개발 DB 적용·Git 변경 명령 없이 실제 iPhone E2E 전까지 `[ ]` 유지
  - 사전 등록 요구 변경 (2026-10-29): 번호 입력·수동 대조 제거, VERIFYING 등록·미검증 관측 보존·안전한 TRACKING 전환, V18, 후보 위치 관계·동일 차량 freshness 재조회와 Flutter 상태 표시 구현. Mock/격리 MySQL로 Backend 전체 978개·Flutter 전체 453개 테스트 실패/오류/skip 0, flutter analyze·diff·문서 링크 검증 통과. V18의 기존 Session/Event/Delivery 보존과 기존 Alarm·FCM 회귀 포함. 실제 Provider/FCM 호출 및 기존 개발 DB 적용은 없으며 실제 iPhone 도착 전/이동 중 등록·수신/tap E2E 전까지 `[ ]` 유지. 세부 계약은 Requirements/Domain/API/Database/Architecture와 ADR-012를 따름
  - 실행 정리·이동 중 확정 최소 개선 (2026-10-29): [증거 보고서 §26](research/TASK-908-provider-evidence.md#26-잔여-실행-정리와-정차-기준점-재사용-2026-10-29)에 잔여 Backend/bootRun·터널·이전 테스트 daemon의 정상 종료와 기존 MySQL/설정/사용자 변경 보존을 기록했다. 최초 정차와 최신 이동의 exact ID/order가 같고 기존 안전 검증을 통과할 때만 원래 기준점·두 시각으로 생성/재확인하는 최소 확장을 반영했다. 신규 14개 사례를 포함한 Backend 전체 966개·관련 Flutter 65개 통과, 실패/오류/skip 0. API·Domain·ADR·실행 절차를 동기화했으며 새 Dependency/Migration은 없다. 최초부터 이동인 차량의 검증 대기 접수는 설계 범위로 남겼다. 실제 Provider/FCM·서버 재가동·Git add/commit/push 없이 실행 프로세스 부재를 확인했고 실제 iPhone E2E 전까지 `[ ]`를 유지한다.
  - iPhone 확정 실패 우선 분석 (2026-10-28): [증거 보고서 §25](research/TASK-908-provider-evidence.md#25-실제-iphone-차량-확정-실패-분석과-진단-보강-2026-10-28)에 최신 정차 필수 제약·이전 201과의 차이·기존 로그의 개별 원인 미확정·두 Backend/터널 대상 차이를 기록했다. 안전 검증을 유지하고 미지원 분기별 비식별 진단 및 신규 11개 사례를 포함한 집중 130개 테스트를 통과했다. 기존 JVM에는 진단 미적용이며 재시작·Provider 추가 호출·FCM·Git add/commit/push는 없다. 기존 정차 기준점 재사용과 별도 검증 대기 접수 설계안을 미확정으로 제시하고 이동 중 일반 사용 차단과 `[ ]`를 유지한다.
  - iPhone E2E 환경 준비 (2026-10-28): [실행 절차](local-development.md#task-908-iphone-boarding-e2e-준비-2026-10-28)에 기존 개발 MySQL V17 적용·데이터 보존, 안전 모드 Backend/새 HTTPS 연결, Flutter local API 주소 갱신과 signed release/서명·APNs entitlement 확인을 기록했다. COMPLETED 목적지는 준비 gate와 별개로 발송 가능하며 loopback HTTP v1/격리 MySQL에서 두 Event payload·중복 억제·취소/만료 차단과 Alarm 회귀 등 Backend 109개, Flutter 전체 437개를 통과했다. 실제 발송 코드는 변경하지 않았다. monitoring/dispatch/FCM은 별도 승인 전 OFF이며 실제 기기 설치·권한·현장 수신/tap과 FCM 전송은 수행하지 않아 `[ ]`를 유지한다.
  - 서울 143번 실제 Provider 검증 (2026-10-28): [증거 보고서 §24](research/TASK-908-provider-evidence.md#24-실제-서울-143번-생성진행notification-검증-2026-10-28)에 목록 41~43대·2대 연속 관측, 실제 후보 GET→POST 201 TRACKING, 정차·이동·목적지 도달과 ON/OFF Event 3개·Delivery 3개·전송 attempt 0을 기록했다. 정상 이동의 영구 보류를 기존 기준점 유지/제한된 자동 재조회로 수정하고 이전 이동 detail→최신 정차의 시작 검증을 보강했다. V17 격리 MySQL 적용·기존 데이터 보존, Backend 전체 937개 및 추가 보강 후 관련 통합 118개를 통과했다. 최신 이동 중 즉시 시작·실제 무정차/회차·기기 표시는 미검증이며 정차에서 시작하는 제한된 iPhone E2E만 가능하다. FCM 전송·기존 DB 변경·Git add/commit/push 없이 `[ ]`를 유지한다.
  - 요구사항 정정 (2026-10-28): 하차 허용 분류를 시작·진행 차단 조건에서 제거하고 검증된 서울 exact detail의 실제 생성·재확인 성공 경로를 연결했다. 선택적인 사전 알림과 필수 목적지 도달·통과 Event, V17, Flutter 선택·저장·수신을 반영했다. 현행 계약은 Requirements/Domain/API/Database/Architecture와 ADR-012 정정을 따른다. 아래 과거 기록의 하차 허용 근거 조사·무조건 지원 차단은 현행 요구가 아니다. Backend 전체 **930개**·Flutter 전체 **437개**가 실패/오류/skip 없이 통과했고 `flutter analyze`와 diff 검증을 통과했다. 새 Dependency는 없으며 서울 HTTP 설정·기존 Alarm 계약을 유지한다. 실제 Provider·FCM·iPhone 검증은 이번에 수행하지 않았으며 `[ ]`를 유지한다.
  - 목적: 실제 탑승 차량과 목적 정류장을 기준으로 GPS 없는 하차 알림의 정확성·지연·불확실성 처리를 검증한다.
  - Backend 안전 검증 보강 (2026-10-28): [Provider 증거 보고서 §22](research/TASK-908-provider-evidence.md#22-http-연결-완료-이후-backend-안전-검증-보강-2026-10-28)에 HTTP 연결 완료 이후의 실제 시작 차단과 잔여 근거를 구분했다. 시작 재검증의 동일 source-time 진행/구간 충돌을 거부하고 명시적 `isrunyn=0` 운행 종료를 시작 차단·영속 보류에 반영했다. Boarding·Notification·Transit·Alarm **825개**가 실패/오류/skip 없이 통과했다. 하차 허용 근거와 운행 연속성 미검증 범위의 안전 차단을 유지하며 실제 Provider/FCM 호출·Git add/commit/push는 없다. 실제 Session 활성화·버스/iPhone E2E는 미완료다.
  - 사전 검증 1단계 Phase A (2026-10-21): [Provider 증거 보고서](research/TASK-908-provider-evidence.md)에 공식 자료·과거 실측·현재 Backend 차단과 미확정 범위를 구분하고 독립 수집 도구·오프라인 테스트를 준비했다. 신규 실측 승인이 없어 실제 TAGO/서울·metadata API·FCM 호출은 모두 0건이다. 목적 occurrence의 하차 가능 근거, 이동 중 의미·동일 차량/운행·회차·freshness 검증과 실제 버스/iPhone E2E가 남아 있으며 `BOARDING_TRACKING_UNSUPPORTED` 및 TASK-908 미완료를 유지한다. 실제 수집은 별도 신규 승인과 호출·시간·Quota 범위 확인 뒤에만 수행한다.
  - Phase B 사전검증 (2026-10-21): 같은 보고서 §18에 실행 계약·안전성 보강·오프라인 테스트 44개 통과를 기록했다. 신규 라이브 승인·실제 계정 Quota·환경 Credential 미확보로 서울/TAGO 요청은 각각 0건이며 실측은 미실행이다. Git 미포함 로컬 도구 링크를 제거했고 `BOARDING_TRACKING_UNSUPPORTED`·TASK-908 `[ ]` 및 실제 버스/iPhone E2E 잔여 범위를 유지한다.
  - Phase B 실행 요청 점검 (2026-10-27): 같은 보고서 §19에 이번 사용자 실측 승인, 기존 도구 오프라인 테스트 44개 통과와 dry-run, 로컬 `.env`의 Key 존재를 기록했다. 공공데이터포털 계정 화면이 로그인·보안문자로 전환되어 서울/TAGO 현재 승인·잔여 Quota 확인이 불가능하므로 승인 파일 작성·live 실행을 보류했다. 실제 서울0/TAGO0회이며 Provider 진행 변화는 미관측이다. 계정 확인 후 제한 실측과 기존 E2E가 남아 있고 `BOARDING_TRACKING_UNSUPPORTED`·TASK-908 `[ ]`를 유지한다.
  - Phase B 실제 실행 (2026-10-27): 같은 보고서 §20에 로그인된 계정의 서울/TAGO 승인·operation별 일일 한도 확인과, 실제 잔여량 미확인 상태에서 사용자가 명시한 이번 실행 한정 Quota 예외를 기록했다. 오프라인 테스트 44개 재통과 후 기존 도구를 live 실행했으나 첫 서울 roster 요청이 약2.036초 후 TIMEOUT으로 실패해 전체 중단했다. 누적 서울1회(roster1/detail0)/TAGO0회, 정상 데이터0건·진행 변화 미관측이며 retry·대체/추가 호출은 없다. Provider 가용성·진행 근거 확보 및 기존 E2E가 남아 있고 `BOARDING_TRACKING_UNSUPPORTED`·TASK-908 `[ ]`를 유지한다.
  - Phase B 연결 진단 (2026-10-27): 같은 보고서 §21에 DNS 정상·TCP80 성공·TCP443 2초 timeout과 인증 없는 네 경로의 HTTP401을 기록했다. 공식 callback path를 로컬 도구에 고정 매핑하고 기존44개+신규2개=46개 오프라인 테스트를 통과했다. 안전한 HTTPS 경로를 확보하지 못해 신규 인증 호출은 서울0/TAGO0회이며, 이전 서울1회와 이번 인증 없는 진단4회를 구분했다. 만료된 window·Quota 예외를 재사용하지 않았고, 기관의 안전한 연결 계약·신규 실측 승인 및 기존 E2E가 남아 있다. Production 변경 없이 지원 제한·TASK-908 `[ ]`를 유지한다.
  - 완료 기준:
    - TASK-901에서 지원 가능하다고 판단한 TAGO/서울 범위에서 실제 탑승으로 후보 조회→추천→사용자 차량 확인→동일 차량 추적→하차 알림→종료 흐름을 검증한다. 미지원/검증 불가 범위는 분리 기록하고 성공으로 처리하지 않는다.
    - 실제 차량/정류장 확인 시각, Provider 관측 시각·지연, 판단/Event/전송 시각과 기기 표시 시각을 구분해 오확정·오알림·누락·중복·지연을 기록한다. 허용 기준은 TASK-903의 계약을 따르며 GPS와 위치 권한 없이 수행한다.
    - 실제 기기 foreground/background/terminated 수신과 tap, 유사 후보, 차량 소실/데이터 지연, 취소·재시작 시 동작 및 기존 도착 알림 병행을 확인한다. 실환경에서 재현할 수 없는 실패는 통제된 검증으로 보완하고 실제 탑승 검증과 구분한다..
    - 발견한 결함을 수정·재검증하고, 남은 설계 판단이나 필수 미검증 항목이 있으면 완료 처리하지 않는다. Phase 종료 시 전체 `/docs`와 구현의 일관성을 감사한다.
  - 선행 관계: TASK-901~907 완료 후 수행한다. 각 구현 Task의 검증과 기존 TASK-710/711·Phase 8의 검증 책임을 대체하지 않는다

실행 dependency는 다음과 같다.

```text
TASK-901 → TASK-902 → TASK-903 → TASK-904 → TASK-905
                                               ↓
기존 TASK-701~711 완료 ────────────────────→ TASK-906
                                               ↓
Phase 6 완료 ────────────────────────────→ TASK-907 → TASK-908
```

TASK-901에서 필수 식별·진행 근거를 확보하지 못한 범위는 후속 설계/구현 전에 지원 여부를 개발자가 판단한다. Phase 8 전체 완료를 조사·설계 착수 조건으로 추가하지 않으며, 실제 운영/공개 배포에는 기존 Phase 8의 해당 readiness 기준을 그대로 적용한다.
