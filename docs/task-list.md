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
- [ ] TASK-710 실제 iPhone foreground/background/terminated 수신 및 tap 검증: payload가 최소 navigation hint이고 tap 뒤 ownership/current state를 Backend에서 재검증하며 stale notification을 정상 처리하는지 확인한다. **진행 중** (2026-10-16 사용자 확인): 실제 버스 이벤트 기반 자동 FCM 알림의 foreground/background/terminated 수신, background 알림 tap 후 알람 상세 이동 및 MySQL `NotificationDelivery`의 `ACCEPTED` 기록을 확인했다. terminated 알림 tap 후 상세 이동, 삭제·유효하지 않은 알람의 tap 처리, 계정 변경·소유권 불일치 시 접근 차단은 미검증이다. [실기기 검증 기록](local-development.md#task-710-실제-iphone-수신-및-tap-검증)을 따른다.
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

- [ ] TASK-906 기존 Notification 시스템과 하차 알림 연동
  - 1단계 구현: V16의 기존 데이터 보존 source 확장, ALARM/BOARDING_SESSION Event 모델·Session identity Repository·공통 Delivery 기한 검증, LIMIT 이전 ALARM 조회와 직접 attempt 진입 차단을 구현했다. 준비 후보 연결·Event/Delivery 원자 저장·recipient 고정·lifecycle 종료는 아래 2단계에 연결했고, source-aware payload·Session gate·Worker 전송/모든 source 만료 정리는 3단계에 남는다. 하차 FCM과 실제 Provider 지원은 활성화하지 않으며 Task는 미완료로 유지한다.
  - 1단계 검증: MySQL 8.4.11 Testcontainers로 V14/V15 기존 Event·네 Delivery 상태·Session 보존, source별 CHECK/UNIQUE·recipient 없는 저장·Delivery 기한·Alarm batch 보호와 Session 미발송을 확인했다. 기존 733개와 신규 32개를 포함한 전체 Gradle 765개 테스트가 실패·오류·skip 없이 통과했다. API/FCM payload·Dependency 변경과 실제 외부 Provider 호출은 없다.
  - 2단계 구현: Session lock 아래 checkpoint/상태·PREPARE_ALIGHT·최초 Device별 Delivery 원자 저장, 최초 INSERT 전 TTL·기회 미소비, Session identity 중복 억제·0 recipient 고정, API/polling 만료·취소·완료·연속성 상실의 PENDING 종료 및 새 transaction commit/UNIQUE 재확인을 연결했다. Session FCM/Worker 차단과 실제 Provider 지원 보류를 유지하며 3단계 전까지 `[ ]` 상태다. 신규 Migration·Dependency·API/FCM payload 변경은 없다.
  - 2단계 검증: MySQL 8.4.11 Testcontainers로 TTL microsecond 경계·lock 대기·고정 recipient·재시작/재확인 중복 억제·실제 FK/UNIQUE 실패 rollback·commit 응답 유실·취소/만료 경합 및 모든 API 만료 경로를 검증했다. 기존 765개와 신규 48개를 포함한 전체 Gradle 813개 테스트가 실패·오류·skip 없이 통과했으며, 기존 Alarm 및 Worker/payload/retry 회귀와 Session 미발송을 보존했다. 실제 Transit Provider/FCM 호출은 없다.
  - 목적: 하차 알림 후보를 기존 Notification 인프라로 전달하면서 기존 알림의 correctness를 보존한다.
  - 완료 기준:
    - TASK-903에서 확정한 최소 확장으로 logical Event·Event 시점 recipient Delivery·세션 상태 변경의 원자성 및 중복 방지를 연결한다. 기존 Device 소유권/registration, durable dispatch, FCM, retry/expiry 경로를 재사용한다.
    - 하차 알림 payload와 tap 진입에 필요한 최소 hint를 정의된 계약대로 연결한다. 기존 도착 알림과 구분하며 Provider accepted를 실제 기기 표시 성공으로 간주하지 않는다.
    - 중복 후보, recipient 없음, Device/계정 변경, 취소·만료·stale 결과, 전송 실패/retry·restart recovery 및 기존 도착 알림의 회귀를 검증한다. 별도 전송 인프라는 도입하지 않는다.
  - 선행 관계: TASK-905 및 기존 Notification TASK-701~711 완료 후 수행한다. TASK-710/711의 미완료 검증을 이 Task로 대체하거나 완료로 간주하지 않는다.

- [ ] TASK-907 Flutter 하차 알림 UI 구현
  - 목적: 사용자가 탑승 차량을 직접 확인하고 목적 정류장을 선택한 뒤 하차 알림의 상태와 한계를 이해할 수 있게 한다.
  - 완료 기준:
    - 목적 occurrence 문맥의 후보 조회·추천 근거 표시→사용자 차량번호 확인·확정과 즉시 추적→상태 조회/취소 흐름을 연결한다. 별도 Start 버튼은 제공하지 않으며 추천과 확정을 구분하고 기존 버스 도착 알림 UI를 보존한다.
    - loading/empty/error/retry, 복수 후보·식별 불가·stale 후보·차량 소실·재확인/종료 상태를 표시한다. 불확실한 차량을 자동 선택/확정하거나 추적 차량을 자동 교체하지 않으며 위치 권한/GPS를 요구하지 않는다.
    - 하차 알림 수신/tap에서 인증·소유권·현재 세션을 재조회하고 종료·취소·접근 불가 상태를 처리한다. UI/API 연동과 기존 인증·도착 알림 회귀를 검증한다.
  - 선행 관계: Phase 6 및 TASK-904~906 완료 후 수행한다. 화면 구성 검토는 TASK-903 계약 확정 뒤 가능하나 API/Notification 연결 전에는 완료 처리하지 않는다.

- [ ] TASK-908 실제 버스 탑승 E2E 검증
  - 목적: 실제 탑승 차량과 목적 정류장을 기준으로 GPS 없는 하차 알림의 정확성·지연·불확실성 처리를 검증한다.
  - 완료 기준:
    - TASK-901에서 지원 가능하다고 판단한 TAGO/서울 범위에서 실제 탑승으로 후보 조회→추천→사용자 차량 확인→동일 차량 추적→하차 알림→종료 흐름을 검증한다. 미지원/검증 불가 범위는 분리 기록하고 성공으로 처리하지 않는다.
    - 실제 차량/정류장 확인 시각, Provider 관측 시각·지연, 판단/Event/전송 시각과 기기 표시 시각을 구분해 오확정·오알림·누락·중복·지연을 기록한다. 허용 기준은 TASK-903의 계약을 따르며 GPS와 위치 권한 없이 수행한다.
    - 실제 기기 foreground/background/terminated 수신과 tap, 유사 후보, 차량 소실/데이터 지연, 취소·재시작 시 동작 및 기존 도착 알림 병행을 확인한다. 실환경에서 재현할 수 없는 실패는 통제된 검증으로 보완하고 실제 탑승 검증과 구분한다.
    - 발견한 결함을 수정·재검증하고, 남은 설계 판단이나 필수 미검증 항목이 있으면 완료 처리하지 않는다. Phase 종료 시 전체 `/docs`와 구현의 일관성을 감사한다.
  - 선행 관계: TASK-901~907 완료 후 수행한다. 각 구현 Task의 검증과 기존 TASK-710/711·Phase 8의 검증 책임을 대체하지 않는다.

실행 dependency는 다음과 같다.

```text
TASK-901 → TASK-902 → TASK-903 → TASK-904 → TASK-905
                                               ↓
기존 TASK-701~711 완료 ────────────────────→ TASK-906
                                               ↓
Phase 6 완료 ────────────────────────────→ TASK-907 → TASK-908
```

TASK-901에서 필수 식별·진행 근거를 확보하지 못한 범위는 후속 설계/구현 전에 지원 여부를 개발자가 판단한다. Phase 8 전체 완료를 조사·설계 착수 조건으로 추가하지 않으며, 실제 운영/공개 배포에는 기존 Phase 8의 해당 readiness 기준을 그대로 적용한다.
