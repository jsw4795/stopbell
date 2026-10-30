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
- [ ] TASK-816 프로젝트 종합 감사 후속 결함 수정
  - 목적: 종합 감사에서 발견한 AUD-01~AUD-13을 중요도에 따라 검토·수정하고 관련 테스트와 문서를 보강한다. 하나의 상위 Task로 관리하며 실제 수정·필수 검증 완료 전까지 `[ ]`를 유지한다.
  - 등록 범위 (2026-10-29): Task와 작업 계획만 추가한다. 이번 등록은 코드·Migration·테스트·Dependency·DB 변경이나 실제 Provider 호출·FCM 발송을 포함하지 않으며, 후속 지시 없이 수정에 착수하지 않는다. 기존 Task 번호와 완료 상태를 유지한다.

### 기존 Phase 8 Task 상세 및 실행 dependency

TASK-805는 structured log, 최소 operational metric, alerting contract를 함께 다룬다. metric은 Provider request outcome/latency, Observation staleness/UNKNOWN reason, ACTIVE/FOLLOW_UP Alarm 수, scheduler cycle duration·last completion·overlap, Notification pending 수와 oldest pending delivery age, no-recipient Event, delivery accepted/failure/retry/expired, metadata 마지막 complete sync 성공 age를 후보로 한다. `alarmId`, `deviceId`, `routeId`, `trackingCycleId`, `installationId`는 metric label에 넣지 않는다. Actuator/Micrometer 수준을 기본으로 하고 자체 Prometheus/Grafana stack을 V1 요구로 만들지 않으며 export와 alert destination은 deployment platform에서 정한다. log는 문맥에 필요한 correlation(`requestId`, `operationId`, provider, schedulerCycleId, alarmId, activation generation, trackingCycleId, notificationEventId, deliveryId, elapsedMs, outcome)만 기록한다. Access/Refresh Token, Google ID Token, Firebase credential, 원문 push targeting identifier·installationId, API key가 든 URL/query, 필요 이상의 GPS, raw Provider response 전체는 로그에 남기지 않는다.

TASK-807은 liveness(JVM/process 생존), readiness(DB 연결, Flyway 적용 완료, 필요한 component 초기화, scheduler/outbox worker 실행 가능), business/dependency health(Provider 최근 실패, scheduler last completion, outbox backlog, metadata 마지막 sync age)를 구분한다. TAGO/서울/FCM의 일시적 원격 장애만으로 Backend readiness를 DOWN으로 만들지 않으며, 상세 dependency 정보는 public health response에 과도하게 노출하지 않는다. fresh DB 최초 배포에서는 필요한 metadata bootstrap이 끝나기 전 public Route/Alarm 생성 traffic을 받지 않는 절차가 필요하지만, 정상 운영 중 metadata가 일시적으로 오래됐다는 이유만으로 기존 Alarm monitoring을 unready로 만들지 않는다.

TASK-808은 production topology 전체가 아니라 Java 21 기반의 재현 가능한 Backend runtime image를 만든다. Gradle Wrapper build 또는 CI-built bootJar, non-root 실행, SIGTERM 전달, UTC runtime timezone, stdout/stderr logging, runtime environment/secret injection, Firebase credential read-only mount 또는 platform identity, health/readiness integration과 graceful shutdown budget을 검토한다. image size 최적화는 correctness보다 우선하지 않고 development `docker-compose.yml`을 production topology로 자동 채택하지 않는다.

TASK-809의 최소 CI는 지금부터 시작할 수 있다. Backend는 Java 21, Gradle Wrapper, build/test, Testcontainers/MySQL/Flyway 검증을, Flutter는 고정 Flutter version, `flutter analyze`, test가 생긴 뒤 `flutter test`를 포함한다. Phase 5~7 구현이 추가될 때 해당 correctness test를 계속 포함한다. 실제 TAGO/서울 Provider 호출, production Firebase credential, 실제 FCM 전송, signed iOS archive는 일반 PR CI에 넣지 않는다. TASK-808 완료 뒤 Docker image build/smoke를 추가할 수 있으며 GitHub Actions는 후보이나 workflow 세부는 이 Task에서 확정한다.

TASK-810은 `pollStartedAt`, provider data time(제공되는 경우), `observedAt`, `eventDetectedAt`, `notificationEventCreatedAt`, `deliveryAttemptAt`, `providerAcceptedAt`으로 Provider freshness/request, evaluation, outbox queue, FCM request, Backend observable end-to-end latency를 분리해 측정한다. Provider가 실제 버스 Event 시각을 주지 않으면 물리 Event부터 Backend까지 지연은 알 수 없고, FCM accepted도 iPhone 표시 시각이 아니다. foreground/background/terminated별 실제 Device 반복 측정으로 체감 지연을 보완한다.

TASK-811은 ACTIVE tracking, ONE_STOP_BEFORE 뒤, target 통과 직전, FOLLOW_UP, lifecycle과 NotificationEvent commit 직후, pending delivery 전, FCM accepted와 DB result update 사이, retry wait, metadata sync 경계에서 restart를 검증한다. ACTIVE tracking의 memory loss는 허용하고 safe rebaseline을 사용하며 restart 전후 Observation을 연결해 PASSED를 추론하지 않는다. FOLLOW_UP은 persisted runtime으로, NotificationEvent는 durable outbox로 복구한다. ambiguous FCM acceptance에는 duplicate 가능성을 인정한다. SIGTERM/deployment 때 새 polling·dispatch cycle을 시작하지 않고 진행 중 transaction은 commit/rollback하며 Provider/FCM timeout은 shutdown budget보다 짧게 제한한다. PENDING Delivery는 restart 뒤 단일 worker가 다시 처리하고 incomplete metadata sync는 provider cleanup 근거가 될 수 없다. 이 검증에서 실제 품질 문제가 입증되기 전에는 ACTIVE tracking persistence나 event sourcing을 추가하지 않는다.

TASK-812는 debugging을 위한 structured log, notification quality를 위한 operational metrics/NotificationDelivery, product usage를 위한 analytics, 장기 business metric을 구분한다. 실제 제품 질문과 보존 근거가 있을 때만 Analytics를 최소 구현하며 별도 persistence가 필요 없다는 결론도 정상 완료다. Phase 7의 NotificationEvent/NotificationDelivery는 correctness와 delivery operation 데이터이며 장기 Analytics Source of Truth가 아니다. TASK-812는 public V1 release blocker가 아니다.

TASK-813은 단일 persistent Backend runtime, durable MySQL, HTTPS/domain 또는 동등한 secure public endpoint, runtime secret injection, restart policy, deployment smoke, rollback procedure, DB backup/restore, Flyway migration policy를 준비한다. 특정 vendor/product는 정하지 않는다. 첫 production 적용 뒤에는 적용된 Flyway migration file을 수정하지 않고 새 migration을 추가한다. single-instance V1은 application startup migration을 유지할 수 있으나, 배포 전 migration 영향과 backup/restore point를 확인하고 migration 실패 instance는 ready가 되어서는 안 된다. DB를 임의 downgrade하지 않으며 application rollback은 새 Schema와의 compatibility를 확인한 경우만 한다. 공개 사용자 데이터를 받기 전 자동 DB backup 또는 platform snapshot, 가능하면 PITR, deploy 전 restore point, 실제 restore drill 최소 1회를 검증한다. Restore 뒤에는 outbound polling/notification dispatch를 quarantine하고 Flyway/schema 검증 → RefreshToken 복구 정책 → Device ownership/registration 정책 → metadata 재동기화/검증 → Alarm recovery baseline → stale pending Notification expiry/cutoff → worker/scheduler 재개 → public readiness 순서로 복구한다. Restore epoch나 별도 recovery subsystem은 추가하지 않는다. Alarm/User/BusAlarmTarget은 복구 중요도가 높고 Transit metadata는 source에서 재생성할 수 있으며 pending notification은 freshness policy를 고려한다. 오래된 backup 복원 뒤 RefreshToken session revoke/re-login 정책은 이 Task에서 결정한다.

TASK-814는 persisted operational time을 UTC 의미로 통일한다. `createdAt`/`updatedAt`, RefreshToken expiry, FOLLOW_UP start/expiry, Provider data time normalization, scheduler time, Notification retry/expiry, latency instrumentation 및 Analytics event time(도입 시)이 대상이다. TASK-504/510/707/709 등 그 전에 추가되는 timestamp도 처음부터 UTC 의미, test 가능한 `Clock` 또는 동등한 time source, 명시적인 Provider timezone parsing을 적용한다. TASK-814는 기존 timestamp consistency의 최종 정리이며 그 전까지 host-local `now()` 추가를 허용하지 않는다. 모든 type을 `Instant`로 바꿀지 UTC `LocalDateTime`/MySQL `DATETIME` 의미를 유지할지는 implementation 시 Schema와 migration 비용을 확인해 정하되 host local timezone에 따라 persisted time 의미가 달라져서는 안 된다.

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
| AUD-13 | P2 | 기존 Alarm의 PASSED 위치 보조 정보가 Push 안내까지 전달되지 않는 문제. 검증된 optional 위치·지난 정거장 정보의 표시 연결 검토 | 코드·요구사항 대조 확인. Event의 stopsPastTarget 보존과 전송 입력·일반 PASSED 문구 사이의 누락 |

각 항목의 처리 기록에는 재현 근거, 수정 또는 미수정 판단, 변경 범위, 방지 테스트와 결과, 남은 실측·실기기 검증 및 담당 Task를 남긴다. 미재현 추론·위험을 확인된 운영 장애로 승격하지 않는다. API·Domain·Authentication·DB 정책이나 공식 문서 간 설계 판단이 필요한 경우 개발자 결정 후 반영한다.

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
- TASK-710: 실제 iPhone Notification 수신·tap 검증 책임과 현재 상태를 유지한다.
- TASK-711: Notification 최종 E2E·regression 검증 책임과 현재 상태를 유지한다. 그룹별 방지 테스트는 이 최종 검증을 대체하지 않는다.
- TASK-908: 실제 버스 탑승·하차 알림 E2E 검증 책임과 현재 상태를 유지한다.

TASK-816은 감사 후속 결함 수정이며 기존 Task의 검증 책임을 대체하지 않는다. TASK-816 자동 테스트가 통과해도 실제 iPhone E2E 완료로 간주하지 않는다. 기존 Phase 8 실행 dependency는 유지하며, 위 A→B→C→D는 TASK-816 내부의 권장 순서다.

---

# Phase 9 - 탑승 차량 식별 및 하차 알림

목표:

사용자가 실제로 탑승한 버스를 확인하고, 확정된 동일 차량을 목적 정류장까지 추적해 하차에 필요한 시점에 알림을 제공한다. 기존의 정류장 도착 알림과 별도의 사용자 목적을 지원하며 기존 기능과 lifecycle 의미를 보존한다.

범위와 설계 원칙:

- 정확성 > 편의성: 잘못된 차량 확정이나 거짓 하차 알림을 줄이는 것을 우선한다.
- GPS에 의존하지 않는다. 사용자 단말 위치 권한·백그라운드 GPS 추적을 요구하지 않으며, Provider GPS 좌표도 차량 식별·하차 판단의 필수 근거나 GPS 거리 기반 대체 판정으로 사용하지 않는다. GPS 없이 확보 가능한 근거와 한계를 TASK-901에서 확인한다.
- 사용자 차량 확인을 우선 지원한다. 실제 응답에서 확인된 차량 표시 정보만 제공하며, 사용자에게 보이는 정보와 Provider 추적 식별자의 연결 가능성을 먼저 검증한다.
- 차량 자동 추천은 후보와 추천 근거를 제시하는 기능이며 탑승 차량 자동 확정과 구분한다. TASK-902에서 실제 전체 차량번호 대조와 사용자 명시적 확정을 채택했으며, 추천 순위나 단일 후보를 이유로 자동 선택·확정하지 않는다. 상세 사용자 보장은 `requirements.md`, 추천 규칙은 ADR-011을 따른다.
- 식별 불확실 시 임의 확정하지 않는다. 복수 후보는 사용자가 번호를 대조하며, 차량 식별자 누락·변경, stale data, 방향·회차 모호성은 필요한 판단의 불확실성을 표현한다. TAGO 원본 생성시각 부재 자체를 stale로 취급하지 않는다.
- 추적 중 다른 차량으로 자동 전환하지 않는다. 대상 차량 소실이나 식별 연속성 상실 시 알림 판단을 보류하고 사용자 재확인 또는 종료로 복구하는 계약을 설계한다.
- 기존 도착 알림의 기능·판정·상태 전이를 보존하고, 기존 NotificationEvent/NotificationDelivery, Device, durable pending dispatch, FCM 및 failure/retry 인프라를 재사용한다. 하차 알림에 필요한 최소 확장 범위는 설계 Task에서 결정한다.
- TASK-901 결과에 따라 지원 범위와 후속 설계를 조정할 수 있다. 필요한 근거를 확보하지 못하면 제약·미지원 범위·추가 판단을 기록하고, GPS나 추측으로 보완하여 구현 완료로 처리하지 않는다.
- 지하철 하차 알림은 향후 별도 Phase로 계획하며 이번 Phase 9에는 조사·설계·구현을 포함하지 않는다.

이번 Phase 등록 자체는 계획 추가이며 구현 완료를 뜻하지 않는다. TASK-902/903에서 승인한 설계는 ADR-011/012와 Owner 문서가 정의하며 TASK-904~908 구현·검증 완료와 구분한다. 기존 TASK-710/711과 Phase 8의 상태·검증 책임은 유지하며 TASK-908이 이를 대체하지 않는다.

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
