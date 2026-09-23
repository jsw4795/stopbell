# 아키텍처

## 1. 아키텍처 목표

향후 개선을 위한 명확한 확장 지점을 남기면서, 현재 StopBell 버전을 신뢰성 있게 지원할 수 있는 가장 단순한 아키텍처를 구축한다.

측정으로 정당화되기 전까지 가상의 대규모 트래픽을 위한 설계를 하지 않는다.

## 2. 초기 시스템 컨텍스트

```text
┌──────────────────────┐
│ Flutter 모바일 앱     │
└──────────┬───────────┘
           │ HTTPS / JSON
           ▼
┌──────────────────────┐
│ Spring Boot 백엔드    │
│                      │
│ - REST API           │
│ - 알림 서비스         │
│ - 교통 모니터링       │
│ - 알림 전송 로직      │
│                      │
│ Persistence Layer    │
│ - JPA                │
└───────┬────────┬─────┘
        │        │
        │        ├──────────────► 교통 데이터 API
        │
        ├───────────────────────► 푸시 제공자(FCM 후보)
        │
        ▼
      MySQL
```

## 3. Development Environment

Development Environment는 Runtime Architecture와 구분한다. 이 절은 Local Development에서 MySQL을 실행하고 데이터를 유지하는 방식을 설명하며, Production deployment 환경을 결정하지 않는다.

```text
Flutter App
      ↓
Spring Boot 4.1.1
      ↓
MySQL 8.4 LTS (Docker Container)
      ↓
Docker Named Volume
```

Docker Container는 MySQL Process의 실행 환경이다. Database 데이터는 Docker Named Volume에 저장하므로 Container lifecycle과 Database lifecycle은 분리한다.

Docker Compose configuration은 저장소 루트의 `docker-compose.yml`에서 관리한다.

## 4. Persistence Strategy

현재 StopBell V1 persistence는 JPA를 사용한다.

```text
Spring Boot
│
├── Domain Layer
│
├── JPA
│   ├── User
│   ├── RefreshToken
│   ├── Alarm
│   ├── BusAlarmTarget
│   ├── BusRoute / BusStop / BusRouteStopOccurrence
│   ├── Device
│   ├── NotificationEvent
│   └── NotificationDelivery
│
        ↓
      MySQL
```

JPA는 단순한 Domain CRUD와 Entity 상태 관리가 필요한 영역에서 사용한다. `User`, `RefreshToken`, `Alarm`, `BusAlarmTarget`, `Device`, `NotificationEvent`, `NotificationDelivery`, Bus static metadata는 Repository 기반으로 관리한다. Bus-specific Target은 공통 Alarm table의 nullable column으로 펼치지 않고 Alarm과 공유 PK를 갖는 별도 Entity/table로 관리하며 Alarm aggregate의 persist/remove lifecycle을 따른다. Bus metadata는 source-neutral route snapshot을 한 Route씩 diff sync한다.

현재 구현된 Query는 JPA로 충분하며 MyBatis Mapper, Mapper XML, 전용 production 설정과 starter를 유지하지 않는다. Route/Stop 검색, Alarm grouping, 집계 또는 성능 최적화에서 명시적 SQL 제어의 실제 필요가 확인되면 MyBatis 도입을 다시 결정한다.

## 5. Authentication Architecture

현재 로그인 및 Application API 인증 흐름은 다음과 같다.

```text
Flutter
  ↓ Google Login
Google
  ↓ ID Token
Flutter
  ↓ Google ID Token
Spring Boot Backend
  ↓ Google Token 검증 및 `sub` 확인
User 조회 또는 생성
  ↓
StopBell Access Token (JWT) + Refresh Token 발급
```

Google은 외부 Identity 확인만 담당한다. Google ID Token을 StopBell API의 장기 인증 Token으로 재사용하지 않으며, 이후 Application API는 StopBell이 발급한 JWT Access Token으로 인증한다.

```text
HTTP Request
  ↓ Authorization: Bearer <Access Token>
Spring Security
  ↓ JWT 검증
인증된 StopBell User
  ↓
Controller / Service
```

Controller가 JWT를 직접 parsing하거나 Client가 전달한 `userId`를 신뢰하지 않는다. Alarm을 포함한 사용자 소유 리소스는 인증된 StopBell User를 기준으로 처리한다.

Refresh Token은 SecureRandom으로 생성한 256-bit opaque token이며, 서버가 저장한 SHA-256 Hash와 비교해 Access Token 재발급과 현재 Session Logout에 사용한다. Rotation 시 기존 Token을 삭제하고 새 Access Token과 새 Refresh Token을 함께 발급하며, 새 Refresh Token은 다시 30일 동안 유효하다. Logout은 Access Token 인증 없이 제시된 Token Hash의 Session 하나만 삭제하고 `204 No Content`를 반환한다. Access Token blacklist, Redis 등 추가 인프라는 현재 도입하지 않으므로 이미 발급된 Access Token은 만료 시점까지 유효할 수 있다.

Flutter에서는 login, bootstrap, authenticated HTTP interceptor, logout이 Token을 각각 독립적으로 변경하지 않는다. 하나의 Auth Session 책임이 current Access/Refresh Token Pair, `initializing`/`authenticated`/`unauthenticated` 상태, refresh single-flight, Token Pair 교체와 Secure Storage 반영을 소유한다. Token Pair는 V1에서 pair 단위로 함께 저장하는 것을 기본으로 하며, 구체적인 storage serialization API는 구현 시 결정한다.

startup은 Auth Session을 통해 저장된 Token Pair를 읽고 인증 상태를 복구한다. Access Token 만료 또는 보호 요청의 인증 실패는 같은 session 안에서 refresh를 한 번만 실행하며 concurrent `401`은 single-flight 결과를 공유한다. refresh 성공 뒤 각 보호 요청은 최대 한 번만 재시도한다. `/auth/google`, `/auth/refresh`, `/auth/logout`는 refresh 대상 또는 재시도 대상으로 취급하지 않아 refresh loop에 들어가지 않는다. refresh `401`은 Token Pair를 제거하고 unauthenticated로 전환하지만 network/offline/5xx는 저장된 장기 session을 즉시 제거하지 않는다.

logout과 refresh는 같은 Auth Session coordination으로 직렬화한다. logout은 local session을 종료한 뒤 late refresh 또는 늦은 API 응답이 인증 상태를 되살리지 못하도록 session generation 또는 동등한 보호를 사용한다. 이는 서버에 이미 도착한 요청을 취소한다는 의미가 아니며, Access Token blacklist를 추가하지 않는 기존 Backend 계약도 바꾸지 않는다.

세부 결정과 재검토 조건은 `adr/ADR-005-authentication-and-user-identity-strategy.md`를 따른다.

## 6. 초기 백엔드 경계

권장하는 논리적 모듈/패키지:

```text
user
alarm
transit
notification
common
```

이는 별도 서비스가 아닌 논리적 경계이다.

### user

애플리케이션 사용자 식별과 Authentication Session을 담당한다. RefreshToken과 Push Device는 직접 연결하지 않는다.

### alarm

알림 설정과 알림 생명주기를 담당한다. Alarm Evaluation은 provider-neutral `TransitObservation`을 입력으로 받아 target과 차량 진행을 판단한다. Provider raw field나 외부 호출 실패를 scheduler에 직접 섞지 않는다.

### transit

외부 교통 데이터 제공자와의 통신을 담당하고, 필요할 때 제공자별 데이터를 정규화한다.

V1은 하나의 전국 Provider를 강제하지 않는다. 경기는 TAGO가 Route metadata, Stop metadata, realtime Location, Arrival 보조 정보를 맡는다. 서울 static Route/Stop metadata는 서울 T Data CSV full import를 사용하고, realtime Location은 서울특별시 버스위치정보조회 서비스를 사용한다. 두 지역의 raw external ID는 `TAGO`, `SEOUL_BUS` provider namespace 안의 opaque String으로 처리하고, Route number·Stop name·Stop order를 identity로 사용하지 않는다. Provider client/DTO를 구현할 때 이 역할 구분을 따르되 범용 plugin 또는 dynamic provider registry를 만들지 않는다. 서울 노선정보조회 서비스가 metadata source였던 ADR-006의 초기 결정은 ADR-009에서 T Data CSV로 대체됐다.

realtime Vehicle Location 조회는 `TransitProviderClient<R, C>` contract로 구분한다. Client는 자신이 담당하는 `TransitProvider`를 제공하고 `VehicleLocationRequest<C>`의 opaque `externalRouteId`와 provider별 typed context를 받아 raw response `R`을 반환한다. TAGO context의 `cityCode`는 API request 재현용이며 identity가 아니고, 서울 context에는 TAGO 값을 넣지 않는다. 서울은 Route 전체 조회로 차량 roster와 coarse 상태를 확인한 뒤, polling/orchestration이 필요하다고 고른 차량만 vehicle detail 조회로 `stId`/`stOrd`/`stopFlag`를 확인하는 2단계 방향을 사용한다. Route 전체 응답의 `sectOrd`, `sectionId`, `nextStId`만으로 target Stop occurrence를 판정하거나 다른 operation field와 동치 관계를 만들지 않는다. 상세 조회 대상 선택과 여러 vehicle detail 결과 조합은 Client가 아니라 TASK-508~510 polling/orchestration 책임이고, 실패 vehicle은 TASK-511에서 Event 없는 `UNKNOWN`으로 처리한다. TASK-503은 route roster 호출과 개별 vehicle detail 호출 각각의 raw success/normal empty와 timeout/network, HTTP, Provider logical, decode/protocol failure를 정확히 구분하며 client 내부 무제한 retry나 generic partial/batch result framework를 만들지 않는다. 기존 `TransitProviderClient<R, C>` contract는 유지한다. Provider raw DTO는 TASK-502, 실제 Client 호출 구현과 failure 분류는 TASK-503, raw DTO의 `TransitObservation` 변환은 TASK-504에서 각각 맡는다.

TASK-503은 Spring `RestClient`와 JDK HTTP client로 TAGO `getRouteAcctoBusLcList`, 서울 `getBusPosByRtidList` roster, 서울 `getBusPosByVehIdItem` detail을 각각 호출한다. `transit.client.connect-timeout`과 `transit.client.response-timeout`은 기본 `PT2S`와 `PT5S`로 제한하며, Provider별 base URL/service key는 `transit.client.tago` 및 `transit.client.seoul` 설정에서 주입한다. TAGO metadata bootstrap은 realtime보다 응답이 느릴 수 있어 별도 `transit.client.metadata-response-timeout` 기본 `PT30S`만 사용하며 realtime의 `PT5S` 제한을 변경하지 않는다. TAGO `resultCode=00`의 빈 item과 서울 `headerCd=4` 결과 없음은 정상 empty이고, 그 밖의 Provider code는 `PROVIDER`, HTTP non-success는 `HTTP`, network/timeout은 `TRANSPORT`, decode·필수 envelope 누락은 `PROTOCOL`인 하나의 typed client exception으로 전달한다. 서울 detail의 raw DTO는 roster DTO와 분리하며 `vehId`, `plainNo`, `stId`, `stOrd`, `stopFlag`, `dataTm`, `tmX`, `tmY`만 보존한다.

Bus static metadata는 서울 T Data CSV full import와 경기 TAGO throttled full sync에서 받아 StopBell DB의 현재 상태로 보관한다. 사용자 Route/Stop 조회와 Alarm 생성은 DB metadata를 사용하고, Alarm 생성 시 필요한 값은 `BusAlarmTarget` snapshot으로 복사한다. Alarm target은 metadata Entity를 FK로 장기 참조하지 않으므로 subsequent sync가 기존 Alarm을 변경하지 않는다.

TASK-513의 source adapter는 서울의 노선마스터·정류장마스터·노선-정류장마스터 CSV와 경기의 TAGO city별 Route·Route Stop pagination을 검증해 normalized metadata snapshot으로 만든 뒤 기존 reconciliation service에 전달한다. 실제 CSV header, encoding, GPS column은 fixture 또는 source 파일을 확인한 구현 시점에 확정한다. source 일부 fetch 실패, parser failure, pagination 미완료, required source 누락, provider request 일부 실패와 검증되지 않은 empty result는 complete provider snapshot이 아니므로 absence/deletion으로 해석하지 않으며 provider-level cleanup을 실행하지 않는다. Typed boundary, completeness token/result, destructive method visibility 제한 또는 동등한 구조적 보호로 검증된 complete snapshot만 cleanup 경로에 들어가게 하며 구체 type 이름은 TASK-513에서 정한다.

최초 metadata bootstrap은 일반 Backend startup에 강제로 연결하지 않는 명시적 one-shot import/sync 실행을 기본으로 한다. Provider별 최소 persisted sync state는 `provider`, `lastCompleteSyncAt` 의미를 보존해 bootstrap 완료, readiness와 마지막 complete sync 성공 age를 판단한다. 필요하면 in-progress/failure 정보를 확장할 수 있지만 sync/checksum history, staging table, error journal은 요구하지 않는다. 자동 refresh 주기는 이번 결정에 포함하지 않는다. 단일 Backend에서는 같은 Provider full sync의 동시 실행을 막고, Provider 전체를 하나의 장시간 DB transaction으로 묶지 않는다. 기존 Route 단위 transaction과 complete snapshot 성공 뒤 cleanup을 유지하며, ingestion 구현 시 Route reconciliation의 Stop lazy-loading N+1 여부를 확인·개선한다. Redis, distributed lock, queue는 V1 범위가 아니다.

선택된 Provider와 identifier 정책의 근거·제약은 `adr/ADR-006-v1-transit-provider-and-external-identifier-strategy.md`를 따른다.

Provider mapper는 request Route context, raw Provider response, StopBell이 성공 응답을 받은 직후의 시각을 한 차량의 관측 사실인 `TransitObservation`으로 변환한다. TAGO와 서울 Route realtime item에 externalRouteId가 없을 수 있으므로 request context는 mapper까지 전달한다. 같은 응답의 차량은 같은 receive-time context를 공유하며, `observedAt`은 polling 시작 시각이 아니다. 공통 의미에는 Provider/Route reference, transient vehicle tracking reference, 현재 Stop/진행 순서, 선택적인 위치·시간·방향/구간 문맥, 그리고 `ARRIVED`/`MOVING`/`UNAVAILABLE`로 구분한 직접 도착 근거가 포함된다. Provider에 없는 값을 가짜 값으로 채우지 않는다. HTTP/network timeout, HTTP non-success, Provider logical error, decode/protocol error는 정상 empty와 구분하는 Provider failure이며 빈 차량 목록으로 변환하지 않는다.

### notification

Device registration lifecycle, durable logical Notification 결정, Event 시점의 per-Device recipient 확정, Push provider 요청과 결과 처리를 담당한다.

StopBell Device identity는 내부 PK와 Client가 생성한 installation ID로 구성한다. `installationId`는 User-scoped가 아닌 앱 installation 자체의 identity이며 하나의 installation에는 동시에 current owner가 최대 한 명이어야 한다. 같은 installation에서 User가 바뀌면 atomic ownership takeover 또는 동등한 계약으로 이전·신규 ownership이 함께 enabled 상태로 남지 않게 한다. Firebase의 현재 push targeting identifier는 rotation/re-registration 가능한 delivery reference이며 Device identity가 아니다. 한 User는 여러 Device를 가질 수 있다. 동일 installation의 registration update는 monotonic revision 또는 동등한 stale-write 보호를 사용한다. targeting identifier 자체의 global uniqueness와 구체 field 이름·길이는 TASK-701/702에서 SDK 동작을 확인한 뒤 정한다.

Notification persistence는 다음 책임으로 분리한다.

```text
NotificationEvent
- durable logical notification decision
- alarmId + activation generation + trackingCycleId + eventType dedup identity
- Event 시점 recipient Delivery들의 logical source

NotificationDelivery
- NotificationEvent × Device
- provider delivery/retry/expiry state
- current/final provider result
```

하나의 MySQL Alarm lifecycle transaction에서 current Alarm lifecycle/generation 검증, lifecycle transition, `NotificationEvent` insert와 그 시점에 eligible한 Device별 `NotificationDelivery(PENDING)` 생성을 처리해 recipient set을 확정한다. eligible Device가 0개여도 이미 발생한 logical NotificationEvent는 저장하고 Delivery는 0개로 두며 no-recipient log/metric을 남긴다. 이후 등록된 Device에 과거 Event의 Delivery를 생성하지 않는다. 단일 Spring Backend의 fixed-delay, non-overlapping worker는 commit 뒤 due PENDING Delivery만 제한 조회해 처리하며 recipient를 다시 선정하지 않는다. Worker는 전송 직전에 Device의 current owner/enabled/current target/revision을 재검증하고 실제 attempt revision을 기록하며, invalid/unregistered 결과는 attempt revision이 current registration과 같을 때만 disable한다. FCM I/O는 transaction 밖에서 수행한다. claim/lease, `claimedAt`, `SENDING`, stale-claim recovery와 multi-worker coordination은 현재 V1에 도입하지 않는다. 외부 Kafka/RabbitMQ/Redis queue, Notification microservice, non-durable after-commit callback만으로 구성한 전달 경로는 사용하지 않는다.

Delivery lifecycle status는 `PENDING`, `ACCEPTED`, `FAILED`, `EXPIRED`를 기본 방향으로 하며 retry는 `PENDING + attemptCount + nextAttemptAt + freshness`로 표현한다. Push provider result는 `ACCEPTED`, `INVALID_TARGET`, `RETRYABLE`, `CONFIGURATION`, `PERMANENT_REQUEST`, `AMBIGUOUS_TIMEOUT`으로 Delivery lifecycle과 분리한다. `EXPIRED`는 local freshness 종료 의미다. Invalid target 응답은 attempt revision이 해당 Device의 current registration일 때만 조건부로 disable한다. Provider acceptance는 실제 Device 표시 성공이 아니며 ambiguous timeout 뒤 retry는 duplicate 표시 가능성이 있다.

### common

실제로 공유가 필요한 횡단 관심사를 둔다. `common`을 잡동사니 저장소로 만들지 않는다.

## 7. 모니터링 모델

첫 구현에서는 Spring 스케줄 작업으로 활성 알림을 주기적으로 평가할 수 있다.

다만 다음과 같은 단순한 모델은 피해야 한다.

```text
활성 사용자 알림 하나 = 외부 API 요청 하나
```

여러 알림이 같은 교통 조회에 의존한다면, 요청은 궁극적으로 중복 제거하거나 그룹화해야 한다.

V1의 기본 Provider polling key는 TAGO의 `(provider, externalRouteId, cityCode)`와 서울의 `(provider, externalRouteId)`다. `cityCode`는 Route identity가 아니라 TAGO request context이지만 동일 polling request 재현에는 필요하다. 같은 Route를 사용하는 여러 사용자·target Stop·ACTIVE Alarm·FOLLOW_UP Alarm은 가능한 한 하나의 Route polling response를 공유한다. Alarm별 Provider 호출이나 MyBatis 도입은 기본 구조로 삼지 않는다.

TASK-508은 JPA `EntityGraph`로 BUS `ACTIVE`/`FOLLOW_UP` Alarm과 `BusAlarmTarget`을 함께 조회한 뒤 Java에서 polling key별로 그룹화한다. `INACTIVE`는 조회 대상이 아니며, target 누락이나 provider request context가 깨진 monitoring Alarm을 query에서 누락·보정하지 않고 명시적으로 실패시킨다. 실제 Provider 호출, Observation 평가, Scheduler와 Provider failure의 `UNKNOWN` 처리는 후속 Task의 책임이다.

TASK-510 Scheduler는 단일 Spring instance에서 Spring Scheduler의 synchronous/fixed-delay 방식으로 실행한다. `transit.monitoring.enabled=false`가 기본이며, 명시적으로 enabled일 때만 `PT20S` 기본 delay가 이전 polling cycle 완료 뒤 적용되어 invocation이 겹치지 않는다. Provider HTTP I/O는 DB transaction 또는 row lock 밖에서 수행한다. 결과 반영 직전에만 Alarm을 `PESSIMISTIC_WRITE`로 다시 읽어 polling 시작 snapshot의 status와 activation generation을 검증하고, 삭제·deactivate·reactivate 등으로 달라졌으면 lifecycle과 memory state를 모두 버린다. API activate/deactivate/delete도 같은 짧은 row-lock transaction을 사용한다. `@Version`, CAS, optimistic locking은 함께 사용하지 않는다. Scheduler memory state key는 `(alarmId, activationGeneration)`이고, 매 cycle의 ACTIVE/FOLLOW_UP 집합 밖 key는 정리한다. TAGO는 Route Location 응답 하나를 group에 공유하며 그 Observation의 vehicle ID 전체를 현재 존재 집합으로 사용한다. 서울은 roster 하나 뒤 baseline/tracked/new/FOLLOW_UP vehicle만 detail 조회하고, roster `vehId` 전체를 detail Observation과 별도로 Evaluator에 전달한다. roster에 존재하지만 detail이 normal empty인 차량은 존재만 갱신하며 위치·Event를 추측하지 않는다. Alarm별 Event 후보는 ACTIVE에서 `ARRIVED`·`PASSED`·`ONE_STOP_BEFORE` 우선순위, 같은 type이면 `vehicleTrackingId` 오름차순으로 하나만 채택하고 FOLLOW_UP에서는 `ONE_STOP_AFTER`만 사용한다. current ARRIVED가 after option으로 FOLLOW_UP을 시작하면 해당 차량의 memory tracking state와 `trackingCycleId`만 이어가며, `followUpStartedAt`/`followUpExpiresAt`은 Event `observedAt`의 UTC와 5분 timeout으로 정한다.

## 8. 알림 평가

알림 평가는 스케줄러 코드에 묻지 말고, 명시적인 Domain/비즈니스 로직으로 표현해야 한다.

위치 관계와 사용자에게 보낼 Event 후보를 분리한다.

```text
Provider raw response
        ↓ provider별 mapping
TransitObservation
        ↓ 이전 동일 차량 Observation + Alarm Transit Target
위치 관계: BEFORE_TARGET / AT_TARGET / AFTER_TARGET / UNKNOWN
        ↓ tracking lifecycle과 option 적용
Event 후보: ONE_STOP_BEFORE / ARRIVED / PASSED / ONE_STOP_AFTER / 없음
        ↓ 한 transition에서 하나 선택
Notification 전송 결정 또는 UNKNOWN 대기
```

`UNKNOWN`은 Notification Event가 아니라 판단 불가 결과다. 정상 응답 안의 애매한 관측과 TASK-503에서 구분한 Provider failure는 원인이 다르지만 둘 다 거짓 ARRIVED/PASSED Event를 만들지 않는다. TASK-511은 Provider failure를 Event 없는 UNKNOWN으로 처리하며 Alarm lifecycle을 진행하거나 기존 vehicle tracking state를 즉시 삭제하거나 synthetic PASSED/ARRIVED를 만들지 않는다. retry/backoff와 circuit breaker 도입 여부는 TASK-510/511 구현에서 결정한다.

일반 tracking의 Event precedence는 target에서 도착을 충분히 관찰한 `ARRIVED`, target 이전에서 이후로 건너뛴 `PASSED`, `ONE_STOP_BEFORE` 순이다. ARRIVED 후 동일 차량 follow-up에서는 `ONE_STOP_AFTER`만 평가하며 PASSED로 재분류하지 않는다. Stop order는 같은 방향·Route traversal 문맥에서 비교할 수 있을 때만 사용한다.

### Alarm과 Vehicle Tracking lifecycle

Alarm 활성화 시 현재 Route 차량을 baseline으로 분류한다. Target 이전 차량은 추적 후보이고, before 옵션이 켜진 상태에서 정확히 predecessor인 차량은 즉시 ONE_STOP_BEFORE 후보가 된다. Target 차량은 충분한 근거가 있으면 즉시 ARRIVED이며, 이미 Target 이후인 차량은 기존 passed vehicle로 무시한다.

PASSED는 해당 Vehicle tracking만 종료하고 Alarm은 ACTIVE로 유지한다. ARRIVED는 Alarm 성공 Event이며 after 옵션이 꺼져 있으면 Alarm을 INACTIVE로 전환하고 다른 Vehicle tracking을 종료한다. after 옵션이 켜져 있으면 Alarm을 ONE_STOP_AFTER 전용 FOLLOW_UP으로 전환하고 ARRIVED를 발생시킨 동일 차량만 다음 Stop 도달·통과까지 추적한다. FOLLOW_UP의 차량 tracking ID와 시작·만료 시각은 Alarm에 영속하여 재시작 뒤 복구할 수 있게 한다.

FOLLOW_UP 중 같은 Alarm의 새 activation은 persisted activation generation을 증가시켜 이전 cycle을 supersede한다. 기존 follow-up runtime을 지우고 ACTIVE 상태의 새 baseline과 monitoring cycle을 시작한다. 비활성화·follow-up 완료도 runtime을 지우며 Alarm 삭제는 runtime과 BusAlarmTarget을 함께 제거한다. FOLLOW_UP runtime은 서버 restart 뒤에도 저장된 vehicle tracking ID와 유효 기간으로 재사용한다. 반면 ACTIVE의 차량별 observation/event state는 V1에서 memory 기반일 수 있다. `trackingCycleId` 또는 동등한 값은 transaction ID가 아니라 logical vehicle tracking cycle identity로 cycle 시작 시 한 번 생성한다. 같은 logical cycle의 lifecycle transaction이 deadlock/optimistic conflict로 retry되어도 identity를 다시 만들지 않으며 retry로 Notification dedup uniqueness를 우회해서는 안 된다. TransitEvent가 생기면 이를 durable NotificationEvent에 복사할 수 있다. restart 뒤에는 이전 memory tracking을 새 cycle과 연결하지 않고 안전한 recovery baseline을 만들며, restart 전 observation으로 PASSED를 추론하거나 predecessor만으로 ONE_STOP_BEFORE를 재발행하지 않는다. 일부 Event 누락보다 false-positive 방지를 우선하고 모든 raw Provider observation 저장이나 event sourcing은 도입하지 않으며, restart continuity 충족 여부는 TASK-811에서 검증한다.

반복 `TransitObservation`과 중복 `TransitEvent` candidate의 억제는 Phase 5 tracking/Evaluation 책임이다. 동일 logical Notification의 중복은 Phase 7 persistence 책임이며 기본 identity는 `(alarmId, activation generation, trackingCycleId, eventType)`이다. DB Unique Constraint 또는 동등한 atomic uniqueness의 구체 Schema는 TASK-707/708에서 결정한다.

구체적인 Observation과 Event 의미는 `adr/ADR-007-bus-alarm-transit-observation-and-event-semantics.md`를 따른다.

## 9. 전달 의미론

V1의 보장 경계는 다음과 같다.

```text
logical NotificationEvent         → DB uniqueness 기준 한 번
NotificationEvent × Device record → DB 기준 하나
FCM request                       → bounded retry로 여러 번 가능
실제 Device 표시                  → exactly once 보장하지 않음
```

Provider `accepted`는 요청 접수를 뜻하며 실제 표시 성공을 뜻하지 않는다. Timeout은 Provider가 접수했는지 알 수 없는 ambiguous 결과다. retry 가능한 Provider result는 `PENDING` Delivery의 attemptCount와 nextAttemptAt으로 재시도하며, 최대 횟수·간격·freshness TTL은 TASK-709에서 실제 smoke 결과와 함께 정한다. 모든 attempt를 append-only row로 저장하거나 generic retry framework를 도입하지 않는다.

Push payload는 navigation hint에 필요한 최소 정보만 포함하고 권한 근거로 사용하지 않는다. Flutter는 Auth Session 초기화 뒤 Alarm ID navigation entry를 사용해 Backend에서 ownership/current state를 재확인한다. Permission이 없어도 다른 Device가 수신할 수 있으므로 Alarm 생성·활성화를 금지하지 않는다. 현재 installation logout은 Device unsubscribe/disable을 시도한 뒤 기존 Auth logout과 local session 종료를 수행하되, offline에서는 Backend disable을 즉시 보장하지 않는다.

세부 결정은 `adr/ADR-010-notification-device-and-durable-delivery.md`를 따른다.

## 10. 확장 경로 — 필요한 경우에만

```text
단일 Spring 인스턴스
      ↓
측정된 병목
      ↓
가능한 개선
- cross-instance shared polling/cache
- 공유 상태용 Redis
- 외부 message broker 기반 fan-out
- 여러 백엔드 인스턴스
```

MySQL pending dispatch를 처리하는 in-process worker는 Phase 7 기본 구조다. Redis, Kafka, RabbitMQ, Kubernetes, 마이크로서비스와 multi-instance distributed lock은 **기본 요구사항이 아니다**.

## 11. 배포 방향

V1 production은 하나의 persistent Spring Boot runtime, durable MySQL, HTTPS/domain 또는 동등한 secure public endpoint, runtime secret injection, restart policy와 deployment smoke를 필요로 한다. 특정 vendor/product를 지금 정하지 않으며 development `docker-compose.yml`은 production topology가 아니다.

Backend image는 Java 21 runtime, Gradle Wrapper build 또는 CI-built bootJar, non-root 실행, SIGTERM 전달, UTC timezone, stdout/stderr logging, health/readiness integration과 graceful shutdown budget을 갖는 재현 가능한 runtime image를 목표로 한다. image size 최적화는 correctness보다 우선하지 않는다.

liveness는 JVM/process 생존을, readiness는 DB 연결, Flyway migration 적용 완료, 필요한 component 초기화, scheduler/outbox worker 실행 가능을 뜻한다. Provider/FCM의 일시적 장애는 Backend readiness를 DOWN으로 만들지 않는다. Provider 최근 실패, scheduler last completion, outbox backlog, metadata 마지막 complete sync age는 business/dependency health로 관찰하되 public response에 상세 내용을 과도하게 노출하지 않는다.

최초 fresh DB deployment에서는 필요한 metadata bootstrap이 끝나기 전 public Route/Alarm 생성 traffic을 받지 않는다. 반대로 정상 운영 중 metadata stale만으로 기존 Alarm monitoring을 unready로 만들지 않는다.

deployment와 SIGTERM 때는 새 polling/notification dispatch cycle을 시작하지 않고, 진행 중 DB transaction은 정상 commit 또는 rollback한다. Provider/FCM timeout은 shutdown budget보다 짧게 제한하고 pending delivery는 restart 뒤 durable outbox에서 복구한다. FCM accepted 뒤 DB update 전 crash하면 Delivery가 PENDING으로 남아 retry될 수 있으며 actual Device duplicate는 exactly-once 비보장 계약으로 허용한다. incomplete metadata sync는 provider cleanup의 근거가 아니다.

첫 production 적용 뒤 Flyway migration file은 수정하지 않고 변경을 새 migration으로 추가한다. 배포 전 migration 영향과 backup/restore point를 확인하고 migration 실패 instance는 ready가 되어서는 안 된다. DB downgrade는 임의로 하지 않으며 application rollback은 새 Schema와 old application compatibility를 확인한 경우만 한다. 공개 사용자 데이터를 받기 전 자동 DB backup 또는 platform snapshot, 가능하면 PITR, deploy 전 restore point와 실제 restore drill 최소 1회를 검증한다. Alarm/User/BusAlarmTarget은 복구 중요도가 높고 Transit metadata는 source에서 재생성할 수 있으며 pending notification은 freshness policy를 고려한다. Restore 뒤에는 outbound polling/notification dispatch를 격리한 상태에서 Flyway/schema, RefreshToken 복구 정책, Device ownership/registration, metadata sync, Alarm recovery baseline, stale pending Notification expiry/cutoff를 순서대로 확인한 뒤 worker/scheduler와 public readiness를 재개한다. Restore epoch나 별도 recovery subsystem은 요구하지 않는다.

Kubernetes, multi-region, read replica, blue/green deployment framework는 V1 기본 요구가 아니다.

## 12. 아키텍처 원칙

1. 규모 과시보다 정확성을 우선한다.
2. 최적화 전에 측정한다.
3. 합리적인 범위에서 외부 제공자 세부 사항을 핵심 알림 규칙과 분리한다.
4. 알림을 유발하는 작업에는 멱등성을 고려한다.
5. 비즈니스 결정은 코드뿐 아니라 문서에도 남긴다.
