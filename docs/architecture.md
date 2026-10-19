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
│   ├── BoardingSession
│   ├── BusRoute / BusStop / BusRouteStopOccurrence
│   ├── Device
│   ├── NotificationEvent
│   └── NotificationDelivery
│
        ↓
      MySQL
```

JPA는 단순한 Domain CRUD와 Entity 상태 관리가 필요한 영역에서 사용한다. `User`, `RefreshToken`, `Alarm`, `BusAlarmTarget`, `BoardingSession`, `Device`, `NotificationEvent`, `NotificationDelivery`, Bus static metadata는 Repository 기반으로 관리한다. Bus-specific Target은 공통 Alarm table의 nullable column으로 펼치지 않고 Alarm과 공유 PK를 갖는 별도 Entity/table로 관리하며 Alarm aggregate의 persist/remove lifecycle을 따른다. Bus metadata는 source-neutral route snapshot을 한 Route씩 diff sync한다.

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

iOS startup은 TASK-704 설치본 marker/secure 레코드를 먼저 확인해 재설치 시 잔존 Keychain Token Pair를 지운 다음 Auth Session을 복구한다. Token Pair에는 재로그인마다 새로 생성하고 rotation/복원 동안 유지하는 durable login identity를 함께 저장한다. startup은 Auth Session을 통해 저장된 Token Pair를 읽고 인증 상태를 복구한다. Access Token 만료 또는 보호 요청의 인증 실패는 같은 session 안에서 refresh를 한 번만 실행하며 concurrent `401`은 single-flight 결과를 공유한다. refresh 성공 뒤 각 보호 요청은 최대 한 번만 재시도한다. `/auth/google`, `/auth/refresh`, `/auth/logout`는 refresh 대상 또는 재시도 대상으로 취급하지 않아 refresh loop에 들어가지 않는다. refresh `401`은 Token Pair를 제거하고 unauthenticated로 전환하지만 network/offline/5xx는 저장된 장기 session을 즉시 제거하지 않는다.

logout과 refresh는 같은 Auth Session coordination으로 직렬화한다. logout은 local session을 종료한 뒤 late refresh 또는 늦은 API 응답이 인증 상태를 되살리지 못하도록 session generation 또는 동등한 보호를 사용한다. 이는 서버에 이미 도착한 요청을 취소한다는 의미가 아니며, Access Token blacklist를 추가하지 않는 기존 Backend 계약도 바꾸지 않는다.

세부 결정과 재검토 조건은 `adr/ADR-005-authentication-and-user-identity-strategy.md`를 따른다.

## 6. 초기 백엔드 경계

권장하는 논리적 모듈/패키지:

```text
user
alarm
boarding
transit
notification
common
```

이는 별도 서비스가 아닌 논리적 경계이다.

### user

애플리케이션 사용자 식별과 Authentication Session을 담당한다. RefreshToken과 Push Device는 직접 연결하지 않는다.

### alarm

알림 설정과 알림 생명주기를 담당한다. Alarm Evaluation은 provider-neutral `TransitObservation`을 입력으로 받아 target과 차량 진행을 판단한다. Provider raw field나 외부 호출 실패를 scheduler에 직접 섞지 않는다.

### boarding

독립 BoardingSession의 snapshot·checkpoint·수명·revision과 생성·조회·취소, 특정 차량 진행 판정을 담당한다. Transit 후보 검증을 재사용하고 기존 Alarm 생명주기는 유지한다. 현재 구현과 후속 추적·판정·복구의 경계는 [탑승 세션 실행](#탑승-세션-실행과-notification-연계-task-903)을 따른다.

### transit

외부 교통 데이터 제공자와의 통신을 담당하고, 필요할 때 제공자별 데이터를 정규화한다.

V1은 하나의 전국 Provider를 강제하지 않는다. 경기는 TAGO가 Route metadata, Stop metadata, realtime Location, Arrival 보조 정보를 맡는다. 서울 static Route/Stop metadata는 서울 T Data CSV full import를 사용하고, realtime Location은 서울특별시 버스위치정보조회 서비스를 사용한다. 두 지역의 raw external ID는 `TAGO`, `SEOUL_BUS` provider namespace 안의 opaque String으로 처리하고, Route number·Stop name·Stop order를 identity로 사용하지 않는다. Provider client/DTO를 구현할 때 이 역할 구분을 따르되 범용 plugin 또는 dynamic provider registry를 만들지 않는다. 서울 노선정보조회 서비스가 metadata source였던 ADR-006의 초기 결정은 ADR-009에서 T Data CSV로 대체됐다.

realtime Vehicle Location 조회는 `TransitProviderClient<R, C>` contract로 구분한다. Client는 자신이 담당하는 `TransitProvider`를 제공하고 `VehicleLocationRequest<C>`의 opaque `externalRouteId`와 provider별 typed context를 받아 raw response `R`을 반환한다. TAGO context의 `cityCode`는 API request 재현용이며 identity가 아니고, 서울 context에는 TAGO 값을 넣지 않는다. 서울은 Route 전체 조회로 차량 roster와 coarse 상태를 확인한 뒤, polling/orchestration이 필요하다고 고른 차량만 vehicle detail 조회로 `stId`/`stOrd`/`stopFlag`를 확인하는 2단계 방향을 사용한다. Route 전체 응답의 `sectOrd`, `sectionId`, `nextStId`만으로 target Stop occurrence를 판정하거나 다른 operation field와 동치 관계를 만들지 않는다. 상세 조회 대상 선택과 여러 vehicle detail 결과 조합은 Client가 아니라 TASK-508~510 polling/orchestration 책임이고, 실패 vehicle은 TASK-511에서 Event 없는 `UNKNOWN`으로 처리한다. TASK-503은 route roster 호출과 개별 vehicle detail 호출 각각의 raw success/normal empty와 timeout/network, HTTP, Provider logical, decode/protocol failure를 정확히 구분하며 client 내부 무제한 retry나 generic partial/batch result framework를 만들지 않는다. 기존 `TransitProviderClient<R, C>` contract는 유지한다. Provider raw DTO는 TASK-502, 실제 Client 호출 구현과 failure 분류는 TASK-503, raw DTO의 `TransitObservation` 변환은 TASK-504에서 각각 맡는다.

TASK-503은 Spring `RestClient`와 JDK HTTP client로 TAGO `getRouteAcctoBusLcList`, 서울 `getBusPosByRtidList` roster, 서울 `getBusPosByVehIdItem` detail을 각각 호출한다. `transit.client.connect-timeout`과 `transit.client.response-timeout`은 기본 `PT2S`와 `PT5S`로 제한하며, Provider별 base URL/service key는 `transit.client.tago` 및 `transit.client.seoul` 설정에서 주입한다. TAGO metadata bootstrap은 realtime보다 응답이 느릴 수 있어 별도 `transit.client.metadata-response-timeout` 기본 `PT30S`만 사용하며 realtime의 `PT5S` 제한을 변경하지 않는다. TAGO `resultCode=00`의 빈 item과 서울 `headerCd=4` 결과 없음은 정상 empty이고, 그 밖의 Provider code는 `PROVIDER`, HTTP non-success는 `HTTP`, network/timeout은 `TRANSPORT`, decode·필수 envelope 누락은 `PROTOCOL`인 하나의 typed client exception으로 전달한다. 서울 detail의 raw DTO는 roster DTO와 분리하며 `vehId`, `plainNo`, `stId`, `stOrd`, `stopFlag`, `dataTm`, `tmX`, `tmY`만 보존한다.

현재 TAGO realtime Client는 `pageNo=1`, `numOfRows=1000`을 명시하고 `totalCount`가 없거나 음수이거나 실제 item 수와 다르면 `PROTOCOL` 실패로 전달한다. 추가 페이지 수집 구현은 아니며, 성공 코드의 empty도 `totalCount=0`과 실제 0건이 일치해야 한다. 개수 검증을 item별 식별·진행 정보의 유효성 보장으로 확대하지 않는다.

TASK-902의 후보 생성·추천과 사용자 번호 확인 경계는 [ADR-011](adr/ADR-011-bus-vehicle-recommendation-and-user-confirmation.md)을 따른다. TASK-904의 `BoardingCandidateService`는 기존 Transit Client/DTO와 DB metadata를 사용하며, 별도 `BoardingCandidateMapper`에서 서울 vehId/plainNo를 함께 보존하고 요청/응답 ID·roster/detail 번호 대응을 검증한다. 기존 Alarm용 Mapper/Observation/Evaluator는 변경하지 않는다. `BoardingCandidateMetadataService`는 read-only transaction에서 불변 traversal을 읽고 Provider I/O는 transaction 밖에서 수행한다. TASK-905 1단계의 세션 API는 이 검증 경로를 재사용하며 실제 지원 보류 범위는 [세션 API](api.md#탑승-세션-api-task-903)를 따른다. 지속 추적의 현재 구현 경계는 아래 탑승 세션 실행 절을 따르며 Flutter 번호 대조 화면은 후속 단계에 남는다.

후보 조회는 기존 Alarm lifecycle·Evaluation·Notification 결정을 변경하지 않는다. 정상 응답의 최신 가용 데이터 수용과 물리 위치 시각 보장은 구분하며, 원본 시각 부재만으로 TAGO 추천을 차단하지 않는다. 기존 Polling/Freshness·timeout/retry 설정을 유지한다. 세션·후보 reference의 경계는 아래 TASK-903 계약을 따르며 후보 API 구현을 세션/Flutter 구현 완료로 취급하지 않는다.

Bus static metadata는 서울 T Data CSV full import와 경기 TAGO throttled full sync에서 받아 StopBell DB의 현재 상태로 보관한다. 경기 GBIS route/routeStation bulk는 전체 TAGO traversal이 검증된 Route의 destination 표시값만 보강하며 Provider identity와 realtime routing은 TAGO를 유지한다. 사용자 Route/Stop 조회와 Alarm 생성은 DB metadata를 사용하고, Alarm 생성 시 필요한 값은 `BusAlarmTarget` snapshot으로 복사한다. Alarm target은 metadata Entity를 FK로 장기 참조하지 않으므로 subsequent sync가 기존 Alarm을 변경하지 않는다.

TASK-513의 source adapter는 서울의 노선마스터·정류장마스터·노선-정류장마스터 CSV와 경기의 TAGO city별 Route·Route Stop pagination을 검증해 normalized metadata snapshot으로 만든 뒤 기존 reconciliation service에 전달한다. 실제 CSV header, encoding, GPS column은 fixture 또는 source 파일을 확인한 구현 시점에 확정한다. source 일부 fetch 실패, parser failure, pagination 미완료, required source 누락, provider request 일부 실패와 검증되지 않은 empty result는 complete provider snapshot이 아니므로 absence/deletion으로 해석하지 않으며 provider-level cleanup을 실행하지 않는다. Typed boundary, completeness token/result, destructive method visibility 제한 또는 동등한 구조적 보호로 검증된 complete snapshot만 cleanup 경로에 들어가게 하며 구체 type 이름은 TASK-513에서 정한다.

최초 metadata bootstrap은 일반 Backend startup에 강제로 연결하지 않는 명시적 one-shot import/sync 실행을 기본으로 한다. Provider별 최소 persisted sync state는 `provider`, `lastCompleteSyncAt` 의미를 보존해 bootstrap 완료, readiness와 마지막 complete sync 성공 age를 판단한다. 필요하면 in-progress/failure 정보를 확장할 수 있지만 sync/checksum history, staging table, error journal은 요구하지 않는다. 자동 refresh 주기는 이번 결정에 포함하지 않는다. 단일 Backend에서는 같은 Provider full sync의 동시 실행을 막고, Provider 전체를 하나의 장시간 DB transaction으로 묶지 않는다. 기존 Route 단위 transaction과 complete snapshot 성공 뒤 cleanup을 유지하며, ingestion 구현 시 Route reconciliation의 Stop lazy-loading N+1 여부를 확인·개선한다. Redis, distributed lock, queue는 V1 범위가 아니다.

선택된 Provider와 identifier 정책의 근거·제약은 `adr/ADR-006-v1-transit-provider-and-external-identifier-strategy.md`를 따른다.

Provider mapper는 request Route context, raw Provider response, StopBell이 성공 응답을 받은 직후의 시각을 한 차량의 관측 사실인 `TransitObservation`으로 변환한다. TAGO와 서울 Route realtime item에 externalRouteId가 없을 수 있으므로 request context는 mapper까지 전달한다. 같은 응답의 차량은 같은 receive-time context를 공유하며, `observedAt`은 polling 시작 시각이 아니다. 공통 의미에는 Provider/Route reference, transient vehicle tracking reference, 현재 Stop/진행 순서, 선택적인 위치·시간·방향/구간 문맥, 그리고 `ARRIVED`/`MOVING`/`UNAVAILABLE`로 구분한 직접 도착 근거가 포함된다. Provider에 없는 값을 가짜 값으로 채우지 않는다. HTTP/network timeout, HTTP non-success, Provider logical error, decode/protocol error는 정상 empty와 구분하는 Provider failure이며 빈 차량 목록으로 변환하지 않는다.

### notification

Device registration lifecycle, durable logical Notification 결정, Event 시점의 per-Device recipient 확정, Push provider 요청과 결과 처리를 담당한다.

Device는 내부 PK + StopBell installation UUID v4 + 현재 FID를 분리한다. User 1:N Device이며 installation당 current owner는 한 명이다. TASK-702는 Device/Platform JPA와 V13 Schema를 구현했으며 등록·상태 조회·disable API는 TASK-703에서 구현했다. [Domain](domain-model.md#device), [Schema](database.md#devices), [등록/해제 API 계약](api.md#기기-등록해제-계약-task-702)을 따른다. Firebase readiness와 FID targeting smoke 결과는 ADR-010을 유지한다.

Device mutation은 JWT Principal과 별도 installation credential을 검증한다. TASK-703에서 기존 row의 `PESSIMISTIC_WRITE` transaction으로 ownership generation/revision 검증과 owner/FID/enabled 갱신을 함께 처리한다. 계정 변경은 credential과 명시적 이전 intent 및 expected generation이 일치할 때만 서버 generation을 증가시킨다. Client의 높은 revision만으로 이전하지 않으며 다른 User의 지연된 disable은 현재 Device를 변경하지 못한다. RefreshToken과 Device는 FK로 연결하지 않는다. 신규 row의 경쟁은 installation/FID UNIQUE로 차단하고 rollback 뒤 권한을 재검증한다.

`DeviceService`는 `REQUIRES_NEW` TransactionTemplate으로 mutation과 read-only 상태 조회의 경계를 관리한다. 최초 등록은 Entity를 로드하지 않는 existence query 후 기존 row에만 current write lock을 얻어 없는 UUID의 gap lock 경쟁을 피한다. 알려진 Device UNIQUE 충돌만 전체 rollback/실패 persistence context 폐기 후 새 transaction에서 원래 요청을 한 번 재확인한다. 원인을 확인하지 못한 DB 오류는 정상 conflict로 분류하지 않는다. Device API의 예상 밖 persistence 오류는 상세 없는 500과 예외 종류만 남기는 log로 처리한다. MySQL 중복키 메시지의 원문 UUID/FID 노출을 막기 위해 `org.hibernate.orm.jdbc.error` 로그를 끈다. 현재 HTTP access log와 request/SQL parameter 상세 log는 활성화하지 않으며 상태 조회의 원문 URL을 추가 기록하지 않는다.

Flutter는 설치본 counter/credential/generation을 logout에도 보존하고 요청을 session과 함께 snapshot해 계정 변경 뒤 이전 작업이 새 Principal로 재시도되지 않게 한다. Device disable 시도 → Auth logout → local session 종료의 경계를 유지하며 network 실패로 logout을 영구 차단하지 않는다. Offline 즉시 Push 해제와 이미 FCM에 접수된 알림 회수는 보장하지 않는다. 구체 계약/한계는 API 문서가 소유하며 TASK-704는 설치본 전용 serial mutation, durable pending/session snapshot, permission/readiness 및 startup/resume/FID rotation 동기화를 구현했다. Native production bridge는 기존 FlutterFire delegate를 전달·복원하며 smoke와 분리한다. 저장·재설치/복구의 상세 정책은 [ADR-010 Client 구현](adr/ADR-010-notification-device-and-durable-delivery.md#task-704-flutter-설치본등록-lifecycle-구현-2026-10-12)을 따른다.

TASK-705의 `notification.client.FcmProviderClient`는 기존 Google 인증/HTTP dependency로 단일 FID HTTP v1 전송과 정규화 결과만 반환한다. Firebase Admin SDK는 추가하지 않는다. 자동 dispatch가 기본 false일 때 설정·인증은 호출 시 준비하므로 FCM 설정 누락이 Backend startup/readiness를 실패시키지 않는다. Provider I/O는 DB transaction 내부 호출을 거부하며 Event/Delivery/Device/Alarm을 변경하지 않는다. Event 연결·fan-out은 TASK-708, Worker orchestration은 TASK-706, production attempt/result/retry/expiry/cleanup은 TASK-709에서 구현했다. 실제 자동 Firebase/iPhone E2E는 TASK-710/711에 남는다.

전송마다 별도 HTTP client를 소유하고 인증·전송을 포함하는 유한 wall-time budget, 요청 abort 및 종료된 invocation의 후속 HTTP 차단을 적용한다. local opt-in one-shot Smoke는 read-only transaction을 끝낸 뒤 같은 Provider를 한 번 호출하며 공개 API를 만들지 않는다. 구체 인증 지원 범위·재전송 제어·보장 한계는 [ADR-010 TASK-705](adr/ADR-010-notification-device-and-durable-delivery.md#task-705-provider-구현과-제어-gate-2026-10-12), 실행 방법과 실기기 미검증 상태는 [Local Development](local-development.md#task-705-backend-provider-수동-smoke)를 따른다.

Notification persistence는 다음 책임으로 분리한다.

```text
NotificationEvent
- durable logical notification decision
- alarmId + activation generation + trackingCycleId + eventType dedup identity
- BOARDING_SESSION: boardingSessionId + PREPARE_ALIGHT dedup identity (TASK-906 1단계 persistence)
- Event 시점 recipient Delivery들의 logical source

NotificationDelivery
- NotificationEvent × Device
- provider delivery/retry/expiry state
- current/final provider result
```

하나의 MySQL Alarm lifecycle transaction에서 current Alarm lifecycle/generation 검증, lifecycle transition, `NotificationEvent` insert와 그 시점에 eligible한 Device별 Delivery 생성을 처리해 recipient set을 확정한다. 유효 기한인 최초 row는 PENDING이며 이미 만료된 row는 EXPIRED로 생성한다. eligible Device가 0개여도 이미 발생한 logical NotificationEvent는 저장하고 Delivery는 0개로 두며 no-recipient log/metric을 남긴다. 이후 등록된 Device에 과거 Event의 Delivery를 생성하지 않는다. 단일 Spring Backend의 fixed-delay, non-overlapping worker는 commit된 Delivery를 제한 조회하고 전송 직전 Device와 freshness를 재검증한다. FCM I/O는 transaction 밖에서 수행한다. claim/lease, `claimedAt`, `SENDING`, stale-claim recovery와 multi-worker coordination은 현재 V1에 도입하지 않는다. 외부 Kafka/RabbitMQ/Redis queue, Notification microservice, non-durable after-commit callback만으로 구성한 전달 경로는 사용하지 않는다.

Delivery의 네 lifecycle과 Provider의 여섯 result는 [Domain](domain-model.md#delivery-result-semantics)을 따른다. Retry는 PENDING + attemptCount + nextAttemptAt + expiresAt이며 EXPIRED는 local 결정이다. Provider acceptance는 실제 Device 표시 성공이 아니며 ambiguous timeout 뒤 retry는 duplicate 표시 가능성이 있다. 오류 mapping/전이와 cleanup 근거는 [TASK-709 설계](adr/ADR-010-notification-device-and-durable-delivery.md#task-709-failureretryexpiry-설계-2026-10-09)가 소유한다.

#### Notification decision transaction (TASK-708)

TASK-708에서 `BusAlarmLifecycleService`에 lifecycle/Event/최초 recipient Delivery의 atomic decision을 구현했다. 선택한 `TransitEvent`와 평가 당시 `AlarmEvaluationKey`, expected status, 원래 evaluation result를 transaction 밖에서 고정한다. Provider HTTP I/O와 Evaluation을 끝낸 후 다음 짧은 DB transaction을 실행한다.

1. 새 persistence context에서 current Alarm을 `PESSIMISTIC_WRITE`로 조회한다. 삭제됐거나 generation이 다르면 stale로 종료하며 변경하지 않는다.
2. candidate가 있으면 동일한 네 identity 값의 Event를 current locking read로 조회한다. 이미 존재하면 무변경 duplicate로 종료한다. 이 확인은 expected status 비교보다 먼저 수행해 commit된 ARRIVED/ONE_STOP_AFTER의 재요청을 이미 바뀐 status 때문에 새 결정으로 처리하지 않는다. Event 없는 결과는 identity 조회를 생략한다.
3. 새 Event 또는 Event 없는 결과이면 expected status와 current status를 비교하고 기존 precedence/option/evidence를 검증한다. ACTIVE는 ONE_STOP_BEFORE/ARRIVED/PASSED, FOLLOW_UP은 ONE_STOP_AFTER만 허용한다. FOLLOW_UP after는 current vehicle ID·persisted cycle UUID가 candidate와 같고 적용 시점에도 미만료여야 한다. 그 밖의 stale/무효 결과는 반영하지 않는다.
4. 필요한 Alarm lifecycle 전이와 FOLLOW_UP UUID 저장/제거를 수행하고 불변 identity의 NotificationEvent를 INSERT한다. ONE_STOP_BEFORE/PASSED는 ACTIVE 유지, ARRIVED는 기존 after option에 따른 전이, ONE_STOP_AFTER는 FOLLOW_UP 완료다. Event 없는 follow-up expiry는 기존 lifecycle 완료만 수행한다.
5. 이 결정 시점에 Alarm owner의 enabled Device를 current locking read로 한 번 조회해 recipient set을 고정한다. 해당 Device row를 ID 순서로 잠가 owner/enabled/target 변경과 조율하며, set의 각 Device에 recipientOwnershipGeneration과 원래 observedAt 기반 expiresAt을 갖는 Delivery 하나를 INSERT한다. 유효 기한이면 PENDING/count=0/nextAttemptAt=createdAt, 이미 만료면 EXPIRED/count=0/null next다. 0개이면 Event만 저장하고 no-recipient 관찰을 남긴다. 원래 candidate의 실제 eventDetectedAt도 함께 보존하며 DB retry로 시각/TTL을 새로 정하지 않는다.
6. flush와 commit을 마친 뒤 결과를 반환한다. Event 또는 필요한 Delivery 하나라도 저장 실패하면 lifecycle/UUID/Event/모든 Delivery를 함께 rollback한다. Provider/FCM I/O는 포함하지 않는다.

기존 Event 조회는 [MySQL current locking read](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)로 수행해 오래된 REPEATABLE READ snapshot을 사용하지 않는다. Alarm → Event → Device ID 순서로 lock을 얻으며 Device 등록 transaction은 Alarm lock을 추가하지 않는다. 조회만을 dedup 보장으로 삼지 않고 [두 DB UNIQUE](database.md#phase-7-notification-persistence-contract)를 최종 경계로 유지한다.

INSERT/flush/commit의 UNIQUE 실패는 transactional method 밖의 호출자가 전체 rollback 완료 후 처리한다. 실패 transaction 안에서 예외를 삼키고 SELECT/commit하지 않는다. [Hibernate Session 오류 계약](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/Session.html)과 [Spring rollback-only 계약](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)을 따른다. 기존 Service transaction 경계 또는 명시적 transaction callback을 사용하며 self-invocation으로 transaction이 누락되지 않게 한다. Event만 별도 `REQUIRES_NEW`로 commit하지 않는다.

Event logical UNIQUE 충돌일 때만 새 transaction/persistence context에서 같은 Alarm lock과 exact identity 조회로 기존 Event를 확인해 duplicate로 분류한다. 동일 Event가 확인되지 않거나 Delivery UNIQUE/FK/CHECK 오류면 일반 duplicate로 숨기지 않는다. deadlock/DB 일시 실패 또는 commit 응답 유실의 재확인은 원래 candidate/UUID/result로 전체 절차를 다시 실행한다. 이미 commit됐으면 duplicate, rollback됐고 여전히 유효하면 새 결정, Alarm/generation/status/evidence가 달라졌으면 stale다. 재시도 횟수는 제한하고 무한 retry/framework를 도입하지 않는다. FCM retry/TTL 수치는 TASK-709 책임이다.

TASK-708의 `BusAlarmDecisionResult`는 COMMITTED/DUPLICATE/STALE/RETRY_REQUIRED와 current generation/status/FOLLOW_UP vehicle·cycle 문맥을 반환한다. Scheduler는 commit 확인 전 nextState를 publish하지 않고, DB 실패/응답 유실 중에는 원래 candidate/result를 유지해 재평가로 UUID를 새로 만들지 않는다. duplicate 확인 후에는 current generation/status와 일치할 때만 원래 result의 consumed state를 반영한다. 이미 ARRIVED commit으로 FOLLOW_UP이면 persisted vehicle/cycle이 같은 원래 tracking을 유지·복원하고, 이미 after commit으로 INACTIVE이면 해당 memory를 종료한다. stale 결과나 새 activation에는 old nextState를 적용하지 않는다. 이 memory 반영은 Event/Delivery/lifecycle을 다시 변경하는 작업이 아니다.

`TransactionTemplate(REQUIRES_NEW)`는 Event만 분리하지 않고 결정 전체에 새 persistence context를 제공하며 execute 반환 전에 flush/commit을 마친다. 일시적 DB/transaction 실패는 호출당 최대 두 번 시도하고, 알려진 Event logical UNIQUE만 rollback 후 새 transaction에서 exact identity를 재확인한다. 그 밖의 integrity 오류는 전파하며 Scheduler는 exception 종류만 안전하게 기록한다. No-recipient log는 commit 뒤 `outcome=NO_RECIPIENT`와 eventType만 기록하며 새로운 metric label/framework를 추가하지 않는다.

Scheduler는 미확인 결정을 `(alarmId, activationGeneration)`별 immutable pending result/expectedStatus/detectedAt으로 메모리에 보관한다. 다음 cycle에서 monitoring group 조회보다 먼저 재확인하므로 응답 유실 뒤 Alarm이 INACTIVE가 되어도 원래 candidate를 잃지 않는다. 재확인 실패 중에는 새 평가를 생략하고 memory nextState를 publish하지 않는다. 이 pending buffer는 process restart 내구성을 주장하지 않으며 ACTIVE의 Phase 5 restart 한계는 그대로다.

`notification.service.NotificationDecisionTtlPolicy`는 `notification.decision.ttl.one-stop-before`, `.arrived`, `.passed`, `.one-stop-after`의 독립 Duration 설정을 제공한다. 네 기본값은 모두 **provisional 개발값 `PT60S`**이며 운영 확정값은 TASK-709/latency 검증 책임이다. 양수·유한하고 DATETIME(6)에서 사라지지 않는 최소 microsecond 정밀도를 요구하며 overflow 값은 거부한다. FOLLOW_UP의 5분 tracking timeout과 별개다. `expiresAt = original observedAt + TTL(type)`이고 DB retry 시 observed/detected 시각과 기한을 변경하지 않는다. 이미 만료된 유효 결정도 lifecycle/Event/recipient identity를 보존하며 Delivery만 최초 EXPIRED로 생성한다. 이 결정 정책은 dispatch/retry scheduling을 수행하지 않는다.

TASK-707은 persistence primitive와 제약을, TASK-708은 위 결정 transaction·dedup/retry 연결·UUID 복원을 구현했다. TASK-706은 commit된 pending Delivery worker를 소유한다. 책임 분담·삭제 제약·현재 recovery 한계는 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#task-708-중복-방지-설계-2026-10-09)을 따른다.

#### Alarm 삭제 transaction (TASK-707)

AlarmService.delete는 기존 인증/소유권 검증과 Alarm PESSIMISTIC_WRITE 조회를 유지한다. 같은 transaction에서 해당 원본 alarmId의 PENDING Delivery를 ID 순서로 PESSIMISTIC_WRITE 조회하고 FAILED/DISPATCH_NOT_ALLOWED, nextAttemptAt=null로 종료한 뒤 Alarm/BusAlarmTarget을 hard delete한다. Event/Delivery는 보존하고 기존 attempt/result 및 terminal row는 변경하지 않는다. flush/commit 실패는 모두 rollback한다.

삭제는 Alarm → Event join/Delivery ID 순서로 lock을 얻고 Device lock을 추가하지 않는다. 신규 결정은 같은 Alarm lock 뒤 Event → Device 순서이므로 삭제와 직렬화하며, 삭제 후 Scheduler/Event candidate는 TASK-708의 Alarm 부재 검증으로 거부한다. Worker는 Delivery → Device만 잠그고 뒤에 Alarm/Event write lock을 추가하지 않으므로 역순 lock cycle을 만들지 않는다. Worker preflight가 먼저 commit됐다면 I/O 직전 삭제 경쟁과 진행 중 FCM 요청의 회수는 보장하지 않는다. 삭제가 종료한 terminal row에는 후속 Worker가 새 호출을 시작하거나 late result로 PENDING을 복구하지 않는다. TASK-706은 이 Worker 경계를 구현했고 실제 result 정책은 TASK-709에 남는다.

#### Notification dispatch worker 구현 (TASK-706)

`notification.service.NotificationDispatchWorker`는 기존 `findDuePendingIds(now, PageRequest.of(0, batchSize))`를 사용해 `nextAttemptAt ASC, id ASC`의 ALARM source PENDING ID만 제한 조회하고 순차 처리한다. TASK-906 1단계의 source 제한은 LIMIT 전에 적용한다. Alarm의 expired/capped PENDING을 조회에서 제외하지 않으며 recipient fan-out, 새 Event/Delivery 생성, Alarm lifecycle 변경을 수행하지 않는다. MySQL이 pending source이고 memory queue, 별도 thread pool, worker sleep/retry loop는 없다.

`notification.dispatch.enabled=false`가 기본이다. FCM provider의 `notification.fcm.enabled` 및 명시적 local Smoke와 독립된 설정이다. `batch-size=50`, `fixed-delay=PT1S`는 provisional 개발값이며 운영 확정값이 아니다. TASK-709에서는 batch 1~1,000, delay 1ms~1분으로 유한 범위를 검증한다. 기존 Spring scheduling의 fixed-delay와 Worker의 `AtomicBoolean.compareAndSet` guard로 동일 인스턴스의 실행 중첩을 막는다. context close 뒤 새 cycle/후속 후보는 시작하지 않는다. 다중 Backend 안전성을 제공하지 않는다.

`NotificationDispatchConfiguration`은 enabled=true일 때 `DurableNotificationAttemptHandler`와 `NotificationRetryPolicy`를 구성한다. TASK-706 당시 handler 부재 gate는 TASK-709에서 FCM enabled/project/credential path/timeout의 필수 정적 설정 검증으로 대체했다. 누락·잘못된 설정이면 startup을 차단하고 attempt를 소비하지 않는다. Credential 파일을 startup에서 열거나 인증 요청을 미리 수행하지 않는다. 기본 disabled Backend startup과 TASK-705 별도 one-shot Smoke 경로는 유지한다.

`NotificationDispatchAttemptService`는 전체 cycle과 HTTP를 transaction으로 감싸지 않고 시작/결과/local expiry마다 `TransactionTemplate(REQUIRES_NEW)`를 사용한다. Delivery `PESSIMISTIC_WRITE` current read 후 PENDING/due를 재확인하고, handler의 `checkEligibility`에 실제 expiry 우선·attempt budget 판단과 필요한 local 종료를 맡긴다. 살아 있는 후보만 `NotificationRecipientPreflight`가 Device ID의 `PESSIMISTIC_WRITE` current read로 Event owner, recipientOwnershipGeneration, enabled, 유효 current FID 및 현재 revision을 확인한다. A→B→A는 generation으로 차단하고 같은 ownership의 정상 rotation은 current FID/revision을 사용한다. Event는 불변 source이며 Alarm/Event write lock과 현재 Alarm status 검사는 추가하지 않는다. recipient 탈락은 `rejectRecipient`에 위임하며 Provider result로 꾸미지 않는다.

전송 target은 불변 `NotificationDispatchTarget`의 deliveryId/eventId/alarmId/eventType/deviceId/eventOwnerId/ownershipGeneration/registrationRevision/FID/expiresAt이다. `NotificationAttemptSnapshot`은 이 target과 durable attemptCount/UTC microsecond lastAttemptAt을 묶는다. raw FID는 메모리에만 존재하고 두 타입의 toString은 redacted이며 Delivery Schema에 복제하지 않는다. handler의 `beginAttempt`는 count/start/revision/결과 초기화/recovery 예약을 실제로 저장해야 한다. Service는 반환 snapshot과 저장된 attempt identity/count 증가/유효 예약을 확인하고 flush하며, execute가 commit을 확인한 뒤에만 snapshot을 반환한다. 시작 실패 또는 commit 응답 유실이면 Provider를 호출하지 않는다.

Worker는 enclosing transaction/synchronization을 거부하고 시작 commit 뒤 UTC Clock으로 `now >= expiresAt`을 다시 확인한다. 만료 시 같은 snapshot의 `expireBeforeSend`에 위임하고 호출하지 않는다. 그 외에는 실제 Event ID와 Alarm ID/type, 원래 expiry 및 current FID로 기존 `FcmProviderClient.send`를 한 번 호출한다. 여섯 `NotificationSendResult`를 재분류하지 않고 별도 transaction의 `completeAttempt`에 전달한다. Service는 terminal/삭제 종료/다른 count·start time·revision/이미 반영된 결과를 handler 호출 전에 무변경 제외한다. Device cleanup과 상태 전이 정책은 handler 책임이다.

후보별 일반 오류는 격리하며 DB/transaction 장애로 안전한 진행이 불가능하면 cycle을 종료한다. 결과 저장 실패 때문에 FCM을 즉시 재실행하지 않는다. cycle 개수/elapsedMs, skip reason, 정규화 result, 필요한 deliveryId와 exception 종류만 기록하고 원문 exception/body/FID/credential은 기록하지 않는다. TASK-706의 최초 검증에는 실제 accounting/retry/result/cleanup이 없었다. TASK-709에서 production 정책과 동일 결과의 제한 DB 재반영을 추가했으며 아래 구현 경계를 따른다. 자동 dispatch는 계속 기본 false이고 실제 E2E 및 운영 tuning은 미검증이다.

집중 unit 10개, Spring configuration 4개, 격리 MySQL Testcontainers 통합 8개를 검증했다. 통합 테스트의 명시적 SQL fixture는 valid durable start를 제공해 실제 commit/rollback·응답 유실 차단·recipient current read·transaction 밖 Provider 연결을 확인하며 TASK-709의 retry/result mutation correctness를 대신하지 않는다. 기존 Backend 전체 suite, 실제 Firebase/iPhone 전송, local DB dispatch와 process restart/E2E는 실행하지 않았다. Dependency·Migration 변경은 없다.

#### Notification dispatch와 result transaction (TASK-709)

TASK-709는 `DurableNotificationAttemptHandler`로 아래 계약을 구현했다. Handler는 caller-owned transaction 안에서 Entity mutation만 수행하며 Provider 호출이나 독립 transaction을 시작하지 않는다. `NotificationRetryPolicy`는 기존 FCM requestBudget을 재사용해 retry/recovery 시각만 계산한다. TASK-706의 worker/preflight/I/O 경계와 V14 Schema는 유지한다. worker는 [due candidate query](database.md#worker-due-query와-index-task-709)로 bounded ID 목록을 읽고 한 row씩 동기 처리한다. expired/capped 후보도 종료해야 하므로 처음 query에서 숨기지 않는다. restart는 MySQL의 row 상태만 사용하고 memory recipient 목록/attempt를 복원하지 않는다.

1. **시작 transaction:** Delivery를 PESSIMISTIC_WRITE로 다시 읽고 없거나 terminal/future-due면 no-op한다. 현재 UTC Clock에서 expiry를 먼저, 그 다음 남은 attempt budget을 확인하고 local 종료가 필요하면 그 상태만 commit한다. 살아 있는 row는 원래 Event owner와 recipient generation을 사용해 Device를 PESSIMISTIC_WRITE current read로 검증한다. enabled/current FID와 owner/generation이 맞으면 메모리 snapshot을 고정한다. Event/삭제 dispatch 허용은 TASK-707 정책을 따르고 현재 Alarm INACTIVE만으로 정상 ARRIVED를 거부하지 않는다.
2. **Durable attempt 시작:** 같은 짧은 transaction에서 count 증가, lastAttemptAt/revision, null lastProviderResult/lastFailureCode, recovery nextAttemptAt을 저장한다. status는 PENDING이다. commit이 확인된 경우만 다음으로 진행한다. commit 실패/응답 유실이면 FCM을 호출하지 않고 저장 상태를 재확인하며 보수적으로 budget을 소비한다.
3. **외부 호출:** 모든 DB lock/transaction을 해제한 뒤 UTC freshness를 한 번 더 확인한다. 이미 기한이면 새 외부 호출 없이 local EXPIRED를 반영한다(이미 commit한 count는 되돌리지 않음). 그 밖에는 검증한 current FID snapshot으로 단일 Provider invocation을 실행한다. FCM 요청 전체에 유한 timeout/budget을 요구하며 [SDK 내부 retry 제어 gate](adr/ADR-010-notification-device-and-durable-delivery.md#sdk-내부-retry와-후속-검증-gate)가 먼저 충족돼야 한다. row lock을 유지한 채 네트워크를 호출하지 않는다.
4. **결과 transaction:** Delivery PESSIMISTIC_WRITE current read로 동일 row가 PENDING이고 attemptCount/last revision/lastAttemptAt이 해당 snapshot과 같은지 확인한다. 삭제/terminal/다른 attempt면 late result를 무변경 종료한다. 같은 attempt의 결과가 이미 저장됐으면 no-op한다. Provider 결과를 [결정표](adr/ADR-010-notification-device-and-durable-delivery.md#provider-result--delivery-결정표)에 따라 기록하고 nextAttemptAt/accepted time/local code를 갱신한다. INVALID_TARGET만 Device write lock 안에서 identity/owner/generation/revision/FID/enabled를 모두 재검증한 뒤 조건부 disable한다. Delivery result와 cleanup은 함께 commit/rollback하며 다른 Device/Alarm은 변경하지 않는다.
5. **다음 cycle / restart:** terminal은 재조회하지 않고 PENDING은 확정 retry 또는 recovery 예약이 due일 때 다시 현재 상태를 검증한다. result DB commit 실패/응답 유실 시 같은 memory snapshot/result를 새 transaction에서 한 번만 재반영하며(최초 포함 최대 2회) 새로운 FCM 요청을 시작하지 않는다. 상태 확인에서도 DB 실패가 계속되면 현재 dispatch cycle을 종료한다. process가 죽으면 결과/snapshot은 잃지만 precommitted count/기한/예약은 유지된다.

실제 invocation이 CONFIGURATION을 반환하면 해당 Delivery는 기존 결정표로 종료하되 Worker는 추가 attempt 시작을 차단한다. 같은 cycle의 남은 후보 및 후속 cycle은 local expiry만 처리하고 미만료 row의 count/예약을 보존한다. 후속 조회는 `findExpiredPendingIds`로 만료된 PENDING만 제한 조회해 미만료 backlog 뒤의 expiry starvation을 막는다. 새 queue/lease/circuit breaker는 없으며 이 최소 in-process 차단은 설정·인증 복구 후 Backend 재시작으로 해제한다. 이미 FAILED인 row는 복구해도 재전송하지 않는다.

개발 후보 설정·안전 상한은 [ADR-010 구현 설정](adr/ADR-010-notification-device-and-durable-delivery.md#task-709-구현-설정과-검증-2026-10-13)을 따른다. Type별 TTL은 기존 `NotificationDecisionTtlPolicy`의 60초를 유지하며 Handler는 원래 expiresAt을 재계산/연장하지 않는다. 일반/ambiguous 최소 10초, quota 최소 60초와 normalized minimumRetryDelay를 존중하므로 기한 내 retry가 불가능하면 PENDING + nextAttemptAt=expiresAt으로 기다렸다 실제 만료 때 EXPIRED로 종료한다.

Worker transaction의 lock 순서는 Delivery → Device다. worker가 Alarm/Event write lock을 나중에 추가해 TASK-708의 Alarm → Event → Device와 반대 순서를 만들지 않는다. Event는 불변 owner/evidence source로 읽으며 TASK-707의 dispatch 종료/삭제는 Delivery lock과 조율하고 일관된 lock 순서를 검증해야 한다. 신규 fan-out의 아직 commit되지 않은 Delivery는 worker에 보이지 않는다. Device mutation은 Device lock만 사용한다. 삭제 transaction/FK는 위 TASK-707 계약을 따르며 범용 cancellation system은 추가하지 않는다.

단일 worker는 진행 중 request와 별도 expiry sweep을 겹치게 하지 않는다. 후보 조회가 exclusion/lease는 아니므로 multiple worker 안전성을 보장하지 않는다. 전송 직전 검증 이후 registration 변경 경쟁, accepted 뒤 result commit 전 crash의 duplicate, 내부 retry의 wire count와 외부 접수 복원 한계는 [ADR crash/restart 계약](adr/ADR-010-notification-device-and-durable-delivery.md#crashrestart와-safety-경계)을 따른다. 다음 cycle/restart도 terminal을 PENDING으로 reset하거나 과거 recipient를 확장하지 않는다.

### 탑승 세션 실행과 Notification 연계 (TASK-903)

이 절은 [ADR-012](adr/ADR-012-boarding-session-and-alight-notification-contract.md)의 승인된 계약이다. TASK-905 1단계는 Session Persistence·확정 검증/저장 경계·조회·취소를, 2단계는 특정 차량 polling·checkpoint/준비 후보·종료 판정을 구현했다. 3단계는 checkpoint 기반 재시작 자동 복구와 동일 차량 재확인 API를 구현했다. 실제 Provider 생성·재확인 지원 보류는 유지하며 Notification 연계는 TASK-906에 남는다. 아래 계약 전체가 실행 중이라는 뜻이 아니다. Domain·복구 판정은 [BoardingSession](domain-model.md#boardingsession), 필드·제약·조회는 [Database](database.md#phase-9-boarding-persistence-contract-task-903), HTTP는 [API](api.md#탑승-세션-api-task-903)가 소유한다.

#### 확정과 평가 transaction

후보 생성은 기존 Transit Client/raw DTO와 DB metadata를 사용하며 Alarm Evaluator로 후보를 완성하지 않는다. opaque candidateRef는 인증 User·Route·목적/선택적 승차 occurrence·ID/전체 번호·관측 시각·구조 fingerprint의 임시 snapshot에 연결한다. 단일 프로세스의 제한된 메모리 reference이며 새 cache 인프라/table은 없다. 원래 관측의 기존 freshness를 벗어나거나 eviction/restart로 잃으면 재조회한다. 캐시 재표시로 observedAt을 갱신하지 않는다. 후보 목록 전체 detail의 무조건 선조회·별도 polling 주기·무제한 cache를 도입하지 않는다. TASK-904의 `BoardingCandidateReferenceStore`는 발급 순서 기준 최대 10,000개를 유지하며 동일 사용자/화면 문맥 재조회는 이전 reference를 교체한다. 구조 fingerprint에는 Provider/Route/typed cityCode와 ordered occurrence ID·external Stop ID·order를 포함하고 표시 이름은 제외한다. 서버 내부 resolve는 인증 User를 요구해 원래 문맥을 복원하며, 이미 가진 전체 문맥과의 일치 검증도 제공한다. 최신 차량·metadata 재검증을 대체하지 않는다. 서울 detail 선택 조건과 호출량·응답 필드는 [후보 API](api.md#후보-조회)를 따른다.

확정/재확인은 Provider I/O 전에 일관된 metadata snapshot을 읽고 I/O는 DB transaction·lock 밖에서 수행한다. 결과 반영의 짧은 transaction은 새 current metadata 구조를 일관되게 다시 읽어 fingerprint를 비교한다. 계산은 고정 session snapshot만 사용하고 버전을 섞지 않는다. 공유 Route Lock이나 기존 Sync 변경은 없으므로 비교 뒤 sync commit까지 차단하는 전역 최신성 보장은 아니다. 이후 구조 변경을 발견하면 보류하고 목적지·snapshot을 자동 교체하지 않는다.

생성은 User PESSIMISTIC_WRITE 뒤 중복 요청·미종료 세션을 current read로 확인하고 Session을 저장한다. 기존 세션의 API mutation/평가/복구는 Session PESSIMISTIC_WRITE로 원래 status/revision·차량·수명·checkpoint를 다시 검증한다. checkpoint·상태·필요한 Event·최초 recipient Delivery를 한 transaction으로 commit한다. 알려진 UNIQUE 충돌·commit 응답 유실은 전체 rollback/새 persistence context에서 원래 요청/후보로 제한 재확인하며 blind upsert·Event만 별도 commit은 금지한다. commit 확인 전 memory next-state나 전송 gate를 열지 않는다.

2단계는 별도 Scheduler 없이 기존 `BusAlarmMonitoringScheduler`의 synchronous fixed-delay cycle을 사용한다. cycle 한정 `VehiclePollingCycle`이 TAGO `(externalRouteId, cityCode)` 원본 목록, 서울 Route roster, 고유 vehId detail과 실패 결과를 공유한다. Alarm의 기존 detail 선택과 세션 selected 차량 조회의 합집합을 사용하고 원래 수신시각을 보존한다. 세션이 없거나 재확인이 필요한 보류·만료이면 세션 때문에 외부 요청을 추가하지 않는다. 미검증 TRACKING과 자동 재검증 가능한 SUSPENDED도 같은 cycle의 새 관측을 사용해 복구하며 동일 Route/차량의 조회·실패를 공유한다. 기존 Alarm의 한 번 재시도는 별도의 공유 cycle에서 새 요청을 수행하며, 재시도 detail도 고유 차량 ID로 중복 제거한다. 세션은 다음 일반 cycle에서 재조회한다. cycle 종료 뒤 공유 응답을 유지하지 않는다. Scheduler 중복 invocation은 직렬화하고 기존 Alarm 평가·Notification 결정 경로는 유지한다.

`BoardingSessionPollingService`는 짧은 조회 transaction에서 detached 입력을 읽고, Provider I/O·`BoardingProgressEvaluator` 계산을 transaction 밖에서 수행한다. `BoardingTrackingStore`는 Session PESSIMISTIC_WRITE 아래 terminal/만료·원래 status/revision·차량·snapshot/checkpoint를 다시 확인하고 현재 metadata 구조를 비교한 뒤 checkpoint/상태/revision을 원자적으로 반영한다. Metadata Sync에 전역 lock을 추가하지 않는다. commit 확인 뒤에만 runtime readiness와 `BoardingTrackingDecision`을 공개하고, DB commit 응답 유실은 새 transaction에서 원래 결과의 status/revision·identity·snapshot/checkpoint와 현재 metadata를 한 번 재조회한다. 정확히 일치하는 commit만 공개하고 rollback·최신 변경은 재적용하지 않는다. DB 실패 뒤 즉시 Provider 재호출이나 오래된 결정 자동 replay는 하지 않는다. `Applied`는 sessionId와 current revision을 명시하며 후보가 durable 소비됐다는 뜻은 아니다.

`BoardingTrackingRuntime`은 polling 대상 목록과 독립된 프로세스별 readiness gate다. 생성 commit 확인 뒤 등록하되 최초 polling 전에는 READY가 아니며, 복구·재확인·polling에서 새 관측 checkpoint의 commit을 확인한 경우에만 해당 revision/checkpoint를 허용한다. 새 프로세스는 기본 차단이며 DB TRACKING이나 과거 checkpoint의 반복 평가만으로 gate를 열지 않는다. `isReady`는 현재 DB snapshot의 status/revision/checkpoint·수명·관측 freshness를 요구하므로 TASK-906도 현재 상태를 읽어 같은 검증 경계를 사용할 수 있다.

보류·취소·만료·완료는 commit 확인 뒤 허용을 닫고 revision watermark를 남긴다. `committed(..., false)`는 이전 허용 revision을 유지하지 않으며 더 낮거나 이미 닫힌 같은 revision의 늦은 callback은 열지 못한다. freshness가 끝난 checkpoint도 닫으며 새 검증 revision이 필요하다. 목록 조회 정리는 조회 중 새로 생성된 세션을 제거하지 않는다. 미복구 세션의 만료도 외부 호출 없이 반영한다. 재확인이 필요한 알려진 연속성 상실은 DB 사유와 마지막 checkpoint를 보존한다. 자동 재검증 실패가 이어져 checkpoint 이후 공백 상한을 넘으면 CONTINUITY_GAP_EXCEEDED로 보류한다.

세션은 Alarm의 다중 차량 선택·baseline·lifecycle을 재사용하지 않는다. snapshot/checkpoint는 전체 raw history나 event sourcing이 아니며 준비 후보는 Event 저장 전 여러 cycle에서 다시 계산될 수 있다. TASK-906의 UNIQUE와 atomic Notification 연결 전에는 durable 중복 억제나 FCM 성공을 주장하지 않는다.

1단계의 `BoardingSessionStartValidator`는 ephemeral reference를 복원하고 기존 `BoardingCandidateService`의 최신 선택 차량 검증을 재사용한다. 검증된 입력만 `VerifiedBoardingStart`로 `BoardingSessionService`의 짧은 저장 transaction 경계에 전달한다. 현재 Provider 지원 차단은 [API](api.md#탑승-세션-api-task-903)를 따른다. 생성의 중복 확인은 reference 조회 전에 수행하고, 실패한 저장은 원래 요청/검증 입력으로 새 transaction에서 최대 한 번 재확인한다. 지원하지 않는 임의 제약 실패를 UNIQUE 재시도로 숨기지 않는다. 조회·취소는 Session lock 아래 수명을 확인하며, Provider I/O나 후속 Worker를 실행하지 않는다. 재확인은 먼저 owner·상태/revision을 읽고 같은 validator로 transaction 밖에서 최신 후보를 재검증한다. 저장 transaction은 원래 불변 차량/traversal과 최신 metadata·revision·기한을 확인해 같은 세션의 새 baseline만 저장한다. 불확실 구간의 연속성을 주장하거나 수명을 연장하지 않으며 상세 충돌/응답 유실 계약은 API 문서를 따른다.

#### Boarding 초기 설정과 만료

| 설정 | 초기값 | 계약 |
| --- | --- | --- |
| `boarding.session.max-lifetime` | `PT4H` | 생성 시 createdAt + 수명으로 expiresAt 고정. polling/복구/재확인으로 연장 없음 |
| `boarding.recovery.max-observation-gap` | `PT2M` | 마지막 검증 observedAt→새 observedAt의 공백 상한. 초과하면 재확인 필요 |
| `notification.decision.ttl.prepare-alight` | `PT30S` | 최초 후보의 originalObservedAt 기준. 기존 Alarm TTL과 독립 |

양수·유한·UTC microsecond 표현과 overflow를 검증한다. 정확도·회차 불가능성·도착 전 표시의 실측 기준이 아닌 조정 가능한 운영 초기값이다. 세션 수명은 방치 추적/점유를 제한하고, 복구값은 여러 기존 polling 기회를 포함한 짧은 중단을 허용하며, 준비 TTL은 늦은 queue/retry 안내를 제한한다. 기존 20초 polling·60초 Observation freshness·Alarm TTL·FOLLOW_UP 5분을 변경하지 않는다. 설정 변경으로 이미 저장한 Session/Delivery 기한을 늘리지 않는다. 기존 세션의 복구 판단은 현재 승인 설정으로 수행하며 시간값만으로 운행 연속성을 보장하지 않는다.

PREPARE_ALIGHT의 기한 E는 `min(originalObservedAt + TTL, session.expiresAt)`이다. 신규 결정은 Session lock 아래 current 유효성·Event 부재 확인 후 INSERT 직전 `now < E`를 요구한다. `now >= E`면 Event·Delivery·소비 표식 없이 반환하고 checkpoint/필요 상태만 반영할 수 있다. 같은 오래된 후보를 now로 바꾸지 않으며 이후 새 유효 관측으로 다시 판단한다. Event INSERT 시점 검증이며 미래 commit 완료 시각을 예측하는 보장은 아니다. 유효하게 INSERT한 뒤 commit 지연으로 기한이 지나면 이미 정해진 Event는 보존하고 worker가 만료 정리한다.

Event 저장이 가능한 경우 eligible Device를 ID 순서 current lock으로 고정하고 기존 ownership generation을 복사해 Delivery를 만든다. 0 recipients도 유효 Event를 소비한다. 저장 후 만료·실패로 Event를 삭제하거나 recipient를 추가하지 않는다. Session 만료는 EXPIRED 전환과 PENDING 종료를 같은 transaction으로 처리한다. Delivery 기한도 Session 기한으로 cap되어 만료 session의 새 FCM 호출을 허용하지 않는다. Delivery의 실제 시간 만료는 EXPIRED, 기한 전 취소·연속성 상실·목적지 도달에 따른 명시 종료는 FAILED/DISPATCH_NOT_ALLOWED이며 terminal과 실제 attempt/result는 보존한다.

#### Notification source와 전송 모델 확장

TASK-906 1단계는 V16, 두 source의 Event Entity와 identity Repository, 공통 Delivery의 Session 기한 일치 검증까지 구현했다. `NotificationEvent.forBoardingSession`은 검증된 `BoardingCheckpoint`에서 Stop ID/order·observedAt·실제 providerDataTime만 복사하는 영속성 입력 경로다. 실제 `BoardingTrackingDecision` 연결·Event/recipient Delivery 원자 생성·lifecycle pending 종료는 2단계에 남는다. Provider 지원 제한은 유지한다.

1단계의 임시 전송 경계는 `NotificationDeliveryRepository.findDuePendingIds`/`findExpiredPendingIds`의 ALARM predicate와 `NotificationDispatchAttemptService.beginAttempt`의 handler/recipient 이전 ALARM 검사다. Session backlog는 두 조회의 LIMIT을 점유하지 않고 직접 Session Delivery ID가 입력돼도 attempt·예약·결과를 변경하지 않는다. `NotificationRecipientPreflight`도 ALARM 이외 입력을 Device 조회 전에 거부해 nullable alarmId의 unboxing을 막는다. 별도 Worker는 추가하지 않는다. 3단계에서 이 query/attempt/preflight 경계를 아래 source-aware 조회·Session 선행 lock/gate·전송 모델로 확장해야 하며 단순 차단 해제만으로 전송을 활성화하면 안 된다. 모든 source 만료 sweep도 3단계에 남는다.

현재 `NotificationDispatchTarget`·`NotificationSendRequest`는 primitive alarmId와 TransitEventType을 필수로 받고 FCM payload도 ALARM_EVENT로 고정한다. 3단계는 두 source만 갖는 작은 typed source hint로 최소 확장한다. 공통 FID/Event ID/expiresAt 및 DispatchTarget의 Delivery/Device/owner/generation/revision 정보는 유지한다.

| source hint | 필수값 | 금지값 |
| --- | --- | --- |
| ALARM | 양수 alarmId, 기존 TransitEventType | boardingSessionId/boardingEventType |
| BOARDING_SESSION | 양수 boardingSessionId, PREPARE_ALIGHT | alarmId/TransitEventType |

후속 공통 constructor/validation·`request()`·payload builder는 이 두 분기만 지원하며 가짜 alarmId, 0 sentinel, 범용 plugin/provider framework를 추가하지 않는다. DispatchTarget의 세션 attempt에는 전송 검증 당시 current Session revision을 담고 gate 재검증에 사용한다. 이는 Event의 불변 identity나 SendRequest의 공개 payload가 아니다. 기존 Alarm constructor 의미·네 type·ALARM_EVENT payload를 보존한다. Notification DB source 분기는 [Schema](database.md#notification-source-확장-task-906-1단계), payload는 [API](api.md#탑승-세션-push와-tap)가 소유한다.

#### 복구 gate와 Worker 공정성

단일 Backend·단일 non-overlapping Worker가 전제다. 메모리 gate는 세션별 `{sessionId, validatedRevision, validatedObservedAt}`의 현재 프로세스 허용 근거이며 기본은 차단이다. Session 생성/복구의 DB commit 확인 후만 열고, 재시작 시 이전 허용 근거를 복원하지 않는다. 오래된 revision의 after-commit 결과가 새 취소/보류를 덮어 열지 못하게 한다. gate는 영속 Source of Truth·claim/lease가 아니며 여러 Backend의 dispatch 안전성을 제공하지 않는다.

후속 Worker cycle은 기존 batch-size를 각 bounded 조회의 상한으로 사용하고 다음 순서로 실행한다. 별도 worker/thread/queue는 없다.

1. **만료 정리:** 모든 source의 expired PENDING을 gate와 무관하게 제한 조회해 EXPIRED로 종료. CONFIGURATION 차단 중에도 실행한다.
2. **Alarm 조회/전달:** ALARM due 조회는 기존 순서·자격·bounded 예산을 유지한다. 세션 대기/세션 만료 row가 이 LIMIT을 점유하지 않는다.
3. **세션 조회/전달:** current 프로세스의 허용 session ID 집합과 current DB 상태를 predicate에 적용한 due 조회다. 미복구/보류 세션을 LIMIT **이전**에 제외하고 nextAttemptAt/id 순서로 제한한다. 조회 후 메모리 filtering만으로 batch 공정성을 구현하지 않는다.

각 cycle은 세 조회에 실행 기회를 주며 Alarm을 우선 처리한다. ready set이 비었어도 만료·Alarm 조회는 수행한다. 세션 대기 때문에 PENDING 예약/attempt/TTL을 변경하거나 루프에서 기다리지 않는다. 추가 실제 FCM I/O의 전체 처리시간이 0이라는 보장은 아니며 예산·SQL/EXPLAIN과 backlog 공정성은 TASK-906에서 검증한다.

#### 복구 후에도 적용하는 Session dispatch 검증

세션 source의 `beginAttempt`는 immutable Event source로 Session ID를 식별한 뒤 **Session → Delivery → Device** 순서로 current write lock을 얻는다. 기존 Alarm 경로는 **Delivery → Device**를 유지하며 뒤에 Session/Alarm lock을 추가하지 않는다. 세션 결정/취소도 Session을 먼저 잠그며 result transaction은 기존 Delivery → Device만 사용한다.

Session source는 expiry를 우선 처리한 뒤 current status=TRACKING, 미만료, Event owner/Session owner 일치, 현재 검증 가능한 목적지 이전 진행, gate와 current Session revision 일치, checkpoint freshness, 원래 Event/Delivery 기한을 검증한다. gate 미준비·일시 보류는 attempt 증가 없이 대기한다. 취소/완료/회복 불가 사유는 lifecycle transaction에서 pending을 명시 종료하며 만료는 gate 없이 처리한다. 정상 polling으로 revision이 증가한 경우 새 검증 commit의 gate를 사용한다. **Event 생성 당시 revision과 현재 revision이 같아야 한다는 조건은 두지 않는다.** Event 기한·identity를 새 revision으로 갱신하지 않는다.

그 뒤 기존 recipient preflight와 durable attempt 시작을 commit하고 모든 DB lock을 해제한 뒤 FCM을 호출한다. 실제 호출 직전에도 gate의 attempt revision·관측 유효성·기한을 확인한다. 시작 commit 후 gate가 닫혀 호출을 생략하면 이미 소비한 count를 되돌리거나 가짜 Provider 결과를 만들지 않고 기존 bounded recovery/종료 경로로 처리한다. 복구 검증은 전송 권한의 영구 허가가 아니다.

세션 취소·만료·목적지 도달·연속성 상실은 gate를 차단하고 pending을 원자 종료하며 늦은 결과로 되살리지 않는다. gate 사전 차단 뒤 DB rollback이면 보수적으로 다음 재검증까지 닫힌 상태를 유지할 수 있다. 마지막 검증 직후 상태 변경과 이미 시작/접수된 외부 요청 사이 경쟁·회수 불가는 기존 한계다. DB 장애/commit 불확실 중에는 gate를 열거나 새 외부 요청을 시작하지 않는다.

TASK-905는 checkpoint/복구와 lifecycle 판단을, TASK-906은 위 atomic Notification 연결·source·gate·fair query·전송 검증을 구현한다. TASK-906 완료 전 하차 Event/FCM을 지원 완료로 취급하지 않는다.

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

`UNKNOWN`은 Notification Event가 아니라 판단 불가 결과다. 정상 응답 안의 애매한 관측과 TASK-503에서 구분한 Provider failure는 원인이 다르지만 둘 다 거짓 ARRIVED/PASSED Event를 만들지 않는다. TASK-511은 Provider failure를 정상 empty와 분리해 Event 없는 UNKNOWN으로 처리한다. TAGO Route 또는 서울 roster 실패는 해당 Route 전체의 평가를 건너뛰고, 서울 detail 일부 실패는 성공 차량과 roster 전체 presence를 즉시 평가하면서 실패 차량의 위치만 판단하지 않는다. 첫 단계의 모든 Route를 처리한 뒤 실패한 Route 요청과 vehicle detail 요청만 모아 `transit.monitoring.failure-retry-delay=PT5S`로 한 번 기다리고 각각 1회 재시도한다. detail 재시도는 roster를 다시 조회하거나 이전 roster를 새 presence로 재사용하지 않고 성공 Observation만 최신 memory state에 적용한다. 재시도 중 새 실패는 다시 재시도하지 않으며 최종 실패는 기존 tracking/lifecycle을 보존하고 다음 일반 cycle을 기다린다. 지연과 Provider HTTP 동안 transaction 또는 row lock을 유지하지 않고, 재시도 결과도 적용 직전에 status/generation을 검증한다. 별도 retry framework, circuit breaker, 비동기 worker는 도입하지 않는다.

일반 tracking의 Event precedence는 target에서 도착을 충분히 관찰한 `ARRIVED`, target 이전에서 이후로 건너뛴 `PASSED`, `ONE_STOP_BEFORE` 순이다. ARRIVED 후 동일 차량 follow-up에서는 `ONE_STOP_AFTER`만 평가하며 PASSED로 재분류하지 않는다. Stop order는 같은 방향·Route traversal 문맥에서 비교할 수 있을 때만 사용한다.

### Alarm과 Vehicle Tracking lifecycle

Alarm 활성화 시 현재 Route 차량을 baseline으로 분류한다. Target 이전 차량은 추적 후보이고, before 옵션이 켜진 상태에서 정확히 predecessor인 차량은 즉시 ONE_STOP_BEFORE 후보가 된다. Target 차량은 충분한 근거가 있으면 즉시 ARRIVED이며, 이미 Target 이후인 차량은 기존 passed vehicle로 무시한다.

PASSED는 해당 Vehicle tracking만 종료하고 Alarm은 ACTIVE로 유지한다. ARRIVED는 Alarm 성공 Event이며 after 옵션이 꺼져 있으면 Alarm을 INACTIVE로 전환하고 다른 Vehicle tracking을 종료한다. after 옵션이 켜져 있으면 Alarm을 ONE_STOP_AFTER 전용 FOLLOW_UP으로 전환하고 ARRIVED를 발생시킨 동일 차량만 다음 Stop 도달·통과까지 추적한다. FOLLOW_UP의 차량 tracking ID와 시작·만료 시각은 Alarm에 영속하여 재시작 뒤 복구할 수 있게 한다.

FOLLOW_UP 중 같은 Alarm의 새 activation은 persisted activation generation을 증가시켜 이전 cycle을 supersede한다. 기존 follow-up runtime을 지우고 ACTIVE 상태의 새 baseline과 monitoring cycle을 시작한다. 비활성화·follow-up 완료도 runtime을 지우며 Alarm 삭제는 runtime과 BusAlarmTarget을 함께 제거한다. FOLLOW_UP runtime은 서버 restart 뒤에도 저장된 vehicle tracking ID와 유효 기간으로 재사용한다. 반면 ACTIVE의 차량별 observation/event state는 V1에서 memory 기반일 수 있다. `trackingCycleId` 또는 동등한 값은 transaction ID가 아니라 logical vehicle tracking cycle identity로 cycle 시작 시 한 번 생성한다. 같은 logical cycle의 lifecycle transaction이 deadlock/optimistic conflict로 retry되어도 identity를 다시 만들지 않으며 retry로 Notification dedup uniqueness를 우회해서는 안 된다. TransitEvent가 생기면 이를 durable NotificationEvent에 복사할 수 있다. restart 뒤에는 이전 memory tracking을 새 cycle과 연결하지 않고 안전한 recovery baseline을 만들며, restart 전 observation으로 PASSED를 추론하거나 predecessor만으로 ONE_STOP_BEFORE를 재발행하지 않는다. 일부 Event 누락보다 false-positive 방지를 우선하고 모든 raw Provider observation 저장이나 event sourcing은 도입하지 않으며, restart continuity 충족 여부는 TASK-811에서 검증한다.

반복 `TransitObservation`과 중복 `TransitEvent` candidate의 억제는 Phase 5 tracking/Evaluation 책임이다. 동일 logical Notification의 중복은 Phase 7 persistence 책임이며 identity는 `(alarmId, activationGeneration, trackingCycleId, eventType)`이다. TASK-708 설계에서 확정한 [DB UNIQUE Schema](database.md#phase-7-notification-persistence-contract)는 TASK-707에서 적용한다.

TASK-707은 FOLLOW_UP에 원래 cycle UUID 저장/제거와 ARRIVED 연결을 구현했다. 원래 UUID를 사용하는 평가기 재시작 복원은 TASK-708 implementation에 남는다. 이는 위 현재 TASK-510의 memory continuity를 보완하는 후속 계약이며 [Alarm Domain](domain-model.md#alarm-lifecycle)과 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#follow_up-cycle-continuity-결정)을 따른다. ACTIVE baseline 정책은 변경하지 않으며 기존 restart 재발행 억제 방향과 현재 evaluator의 차이는 ADR-010 조사 결과에 기록한다. logical DB uniqueness가 ACTIVE restart 전후의 서로 다른 UUID cycle까지 합치지는 않는다.

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
