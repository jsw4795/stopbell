# Package Structure

## Purpose

이 문서는 Spring Boot Backend와 Flutter Application의 코드 구조를 정의한다.

Package는 기술 계층만으로 분리하지 않고 Domain을 기준으로 구성한다. 각 Domain 내부에서 필요한 `controller`, `service`, `repository`, `entity`, `dto` 또는 `mapper`를 둔다.

------------------------------------------------------------------------

# Backend

## Root Structure

```text
backend/
└── src/main/java/com/stopbell/
    ├── common
    ├── user
    ├── alarm
    ├── boarding
    ├── transit
    └── notification
```

`user`, `alarm`, `boarding`, `transit`, `notification`은 `architecture.md`에 정의한 논리적 Backend 경계와 일치한다. 이 경계는 별도 서비스가 아니라 하나의 Spring Boot application 내부의 Package 경계이다.

## Current Structure

```text
backend/
└── src/main/java/com/stopbell/
    ├── common/
    │   ├── config/
    │   └── error/
    │
    ├── user/
    │   ├── auth/
    │   │   ├── config/
    │   │   ├── controller/
    │   │   ├── dto/
    │   │   ├── service/
    │   │   ├── identity/
    │   │   └── exception/
    │   ├── repository/
    │   └── entity/
    │
    ├── alarm/
    │   ├── controller/
    │   ├── service/
    │   ├── repository/
    │   ├── entity/
    │   └── dto/
    │
    ├── boarding/
    │   ├── domain/
    │   ├── controller/
    │   ├── service/
    │   ├── repository/
    │   ├── entity/
    │   └── dto/
    │
    ├── transit/
    │   ├── client/
    │   ├── entity/
    │   ├── repository/
    │   ├── service/
    │   ├── controller/
    │   ├── mapper/
    │   ├── metadata/
    │   ├── dto/
    │   └── domain/
    │
    └── notification/
        ├── controller/
        ├── service/
        ├── client/
        ├── repository/
        ├── entity/
        └── dto/
```

위 구조는 현재 구현된 주요 Package를 나타낸다. Provider별 하위 DTO는 `transit/dto/seoul`, `transit/dto/tago`에 있다. 향후 Package는 실제 책임이 필요할 때만 추가한다.

## Package Responsibilities

### common

여러 Domain에서 실제로 공유해야 하는 횡단 관심사를 둔다.

예:

- Application-wide Spring configuration
- `common.error`의 공통 예외 및 Error response 처리

`common`은 Domain 규칙, Transit provider 구현, 또는 임의의 utility를 모으는 장소가 아니다.

`SecurityConfiguration`은 모든 Application Request의 Security Policy를 담당하므로 `common.config`에 둔다. JWT 및 Google Identity 검증처럼 Authentication에만 종속된 Configuration은 `user.auth.config`에 둔다.

### user

사용자 식별과 인증 책임을 둔다.

- `repository`: User·RefreshToken의 JPA Repository
- `entity`: User·AuthProvider·RefreshToken
- `auth`: Authentication API와 Google Identity 검증, StopBell Token 발급 책임

`user`는 Google Social Identity를 가진 User와 RefreshToken Authentication Session을 함께 소유한다. Authentication API와 Google Token 검증, Refresh Token 발급·회전·무효화도 이 Domain의 책임으로 둔다.

### user.auth

Authentication 기능은 `user.auth` 하위 Feature Package로 구성한다.

- `config`: `JwtConfiguration`, `JwtProperties`, Google Identity 검증 Configuration과 Properties
- `controller`: Authentication API entry point인 `AuthController`
- `dto`: Google Login 및 Refresh 요청과 Access/Refresh Token Pair 응답 DTO
- `service`: Login use case와 JWT Token application logic
- `identity`: 외부 Social Provider에서 검증된 Identity 획득
- `exception`: Authentication use case와 Social Identity 검증 예외

`user.auth`는 User Domain을 소유하지 않는다. `User`, `AuthProvider`, `RefreshToken`은 `user.entity`에, 해당 JPA Repository는 `user.repository`에 유지한다.

### alarm

Alarm 설정, 상태 전이, 활성화/비활성화 책임을 둔다.

- `controller`: Alarm 생성, 조회, 활성화, 비활성화, 삭제 API
- `service`: Alarm lifecycle과 Alarm Domain 규칙
- `repository`: Alarm Entity의 JPA Repository
- `entity`: `Alarm`, `BusAlarmTarget`, lifecycle/provider snapshot 관련 JPA Domain type
- `dto`: Alarm API request/response DTO

Alarm은 `AlarmStatus`와 FOLLOW_UP runtime을 소유하고, Bus-specific 장기 설정은 같은 aggregate의 공유 PK `BusAlarmTarget` Entity로 분리한다. `AdjacentStopSnapshot`은 Target 생성 시 option과 필요한 predecessor/successor occurrence를 함께 표현한다. Alarm Evaluation은 scheduler에 묻지 않는다. provider-neutral `TransitObservation`과 Alarm Transit Target을 받아 위치 관계 및 `TransitEvent` 후보를 판단하는 Domain/비즈니스 로직은 `alarm`의 책임으로 둔다. Scheduler/orchestration은 같은 Route polling response를 여러 Alarm에 공유하고, Provider failure를 Event 없는 UNKNOWN으로 전달하며, lifecycle 변경과 stale polling 결과의 race를 조정한다.

`BusAlarmPollingService`는 monitoring 대상 BUS `ACTIVE`/`FOLLOW_UP` Alarm을 `AlarmRepository`에서 `BusAlarmTarget`과 함께 조회해 provider route polling key별로 묶는다. 이 내부 grouping 경로는 target 또는 provider request context 불변 조건이 깨진 Alarm을 조용히 제외하지 않는다. `BusAlarmMonitoringScheduler`는 enabled일 때 synchronous fixed-delay cycle에서 group별 Provider 조회·Observation 변환·Evaluation을 실행하고, `BusAlarmLifecycleService`가 짧은 Alarm `PESSIMISTIC_WRITE` transaction에서 stale result 검증과 lifecycle/Event/최초 recipient Delivery를 원자적으로 결정하고 `BusAlarmDecisionResult`를 반환한다. Provider I/O는 transaction 밖에서 실행하며 memory evaluation state는 `(alarmId, activationGeneration)`별로 보관한다.

### boarding

독립 BoardingSession aggregate의 불변 snapshot·checkpoint·수명·revision과 생성·조회·취소·동일 차량 재확인 API를 소유한다. `service`의 확정 검증은 기존 `transit` 후보 reference·metadata·Provider 재검증을 재사용하고, 검증 입력과 저장 transaction을 분리한다. 기존 Alarm lifecycle은 유지하며 `BoardingNotificationService`는 기존 Notification persistence/dispatch를 세션 결정에 재사용한다. `domain`은 detached 추적 입력과 관측·판정 결과를, `service`는 polling·Provider 검증·순수 진행 판정·짧은 저장 transaction·프로세스 runtime readiness를 소유한다. `BoardingSessionPollingService`는 기존 Scheduler의 cycle 한정 원본 응답 공유를 사용한다. 실제 지원 경계는 [API](api.md#탑승-세션-api-task-903), 현재 polling·복구와 Notification 연결은 [Architecture](architecture.md#탑승-세션-실행과-notification-연계-task-903)를 따른다.

### transit

외부 Transit provider 통신, provider별 응답 정규화, Transit Query 책임을 둔다.

- `client`: 외부 Transit API client
- `service`: Route/Stop 검색·조회, 후보 조회/reference·최신 선택 차량 재검증, source-neutral metadata reconciliation
- `controller`: Route/Stop·BoardingCandidate 조회 API
- `mapper`: 기존 Alarm용 Observation 변환과 별도 `BoardingCandidateMapper`
- `metadata`: 서울 CSV·TAGO 수집과 GBIS 목적지 표시 보강
- `entity`: `BusRoute`, `BusStop`, `BusRouteStopOccurrence`, `BusMetadataSyncState` JPA Entity
- `repository`: Transit metadata JPA Repository
- `dto`: `seoul`/`tago`별 raw 응답과 StopBell 조회 API DTO
- `domain`: `TransitObservation`, `TransitEvent` 등 Transit 관련 Domain Model

Bus static metadata는 서울 T Data CSV full import와 경기 TAGO throttled full sync의 normalized route snapshot을 JPA로 reconciliation한다. 경기 GBIS route/routeStation bulk는 `transit/metadata` 안에서 검증된 TAGO snapshot의 destination 표시만 보강한다. source adapter는 서울 노선마스터·정류장마스터·노선-정류장마스터 CSV와 경기 TAGO city별 Route/Route Stop pagination을 읽어 validation 뒤 snapshot을 만든다. source 일부 실패·불완전 pagination·parser failure·필수 source 누락은 provider-level cleanup을 허용하지 않으며 empty collection만으로 complete snapshot을 뜻하지 않는다. 동일 Route/Stop identity의 metadata는 UPDATE하고, 의미가 바뀐 occurrence는 삭제 후 새 row로 생성한다. Route/Stop 검색, Alarm grouping, Transit 상태 조회, 복잡한 검색에서 명시적 SQL 제어의 실제 필요가 확인되면 그때 SQL Mapper 추가와 MyBatis 도입을 결정한다.

V1 Provider namespace는 `TAGO`, `SEOUL_BUS`다. static metadata source는 서울 T Data CSV와 경기 TAGO sync이며, 서울 realtime은 Route 전체 roster/coarse 조회 뒤 필요 차량의 vehicle detail을 조회하는 2단계 방향을 사용한다. provider별 client와 response DTO는 `transit` 경계 안에 분리되어 있으며, generic multi-provider framework나 동적 registry를 만들지 않는다. 기본 polling key는 TAGO의 `(provider, externalRouteId, cityCode)`, 서울의 `(provider, externalRouteId)`다.

Provider별 raw DTO를 Alarm Evaluation에 직접 전달하지 않는다. `transit`은 request context와 successful response receive time을 raw field와 함께 `TransitObservation`의 공통 의미로 변환하고, Provider failure는 정상 empty 및 정상 Observation과 구분한다. `transit.domain.TransitProvider`는 `TAGO`, `SEOUL_BUS` namespace의 안정적인 공통 type이다. 차량별 tracking lifecycle과 Alarm lifecycle 전이는 `alarm`이 소유하며 scheduler는 이를 실행만 한다. metadata full sync의 single-flight, Route 단위 transaction, Stop lazy-loading N+1 확인은 ingestion 구현 책임이며 Redis, distributed lock, queue는 V1에 추가하지 않는다.

### notification

Device registration lifecycle, durable logical Notification, per-Device delivery, Push provider 요청과 결과 처리 책임을 둔다.

- `controller`: authenticated Device registration/state lookup/disable API
- `service`: Device 권한·revision·ownership/transaction 처리, NotificationEvent와 Event 시점 recipient Delivery 생성 orchestration, 기존 pending Delivery worker와 전달 결과 처리
- `client`: TASK-705의 `FcmProviderClient`, 전송 입력/정규화 결과, `FcmFailureMapper`, invocation transport/budget, FCM 설정 및 local CLI `FcmSmokeRunner`. HTTP v1 선택·인증/제어 gate는 ADR-010, 실행은 local-development.md를 참조한다
- `repository`: Device, NotificationEvent, NotificationDelivery JPA Repository
- `entity`: Device, NotificationEvent, NotificationDelivery JPA Entity와 `NotificationSourceType`, `BoardingNotificationEventType`
- `dto`: Device 등록·상태 조회·비활성화 요청/응답. 내부 전송 입력/결과와 source별 최소 payload 구성은 `client`가 담당

`NotificationDecisionTtlPolicy`는 notification.service에 두고 최초 Delivery freshness 설정을 소유한다. Lifecycle/Event/fan-out의 단일 결정 경계는 기존 alarm.service의 `BusAlarmLifecycleService`가 조율한다. 현재 구현은 아래 Class에 책임을 두며 generic multi-provider framework, generic retry framework, Device subtype hierarchy, 별도 Notification microservice를 만들지 않는다. 기존 `NotificationHistory` 코드는 TASK-707에서 Event/Delivery로 대체했고 legacy table의 조건부 보존은 database.md를 따른다.

TASK-706의 `NotificationDispatchConfiguration`/`NotificationDispatchProperties`, `NotificationDispatchWorker`, `NotificationDispatchAttemptService`, `NotificationRecipientPreflight`, `NotificationDispatchTarget`/`NotificationAttemptSnapshot`과 `NotificationAttemptHandler` 계약은 notification.service에 둔다. 실행 경계와 기본 비활성화/FCM 정적 설정 gate는 [Architecture](architecture.md#notification-dispatch-worker-구현-task-706)를 따른다. `DurableNotificationAttemptHandler`는 실제 caller-owned attempt/result/expiry/cleanup 정책을, `NotificationRetryPolicy`/`NotificationRetryProperties`는 bounded DB 예약과 설정 검증을 같은 package에서 소유한다. 새 retry framework/package는 없다.

TASK-906은 기존 `NotificationEvent`에 Session factory와 source별 identity를 추가하고, `boarding.service.BoardingNotificationService`가 Session transaction의 Event/최초 recipient 저장·PENDING 종료를 소유한다. `BoardingTrackingStore`는 checkpoint/상태와 원자 저장·commit 재확인을, `BoardingSessionService`는 API 만료/취소 경계를 조율한다. `notification.client.NotificationSourceHint`는 두 source의 전송 identity를 구분하고 기존 DispatchTarget/SendRequest·FCM이 공유한다. 기존 AttemptService는 Session 선행 lock/current gate 검증·전송 직전 검증을, 기존 Worker는 독립 source 예산의 due/expiry 처리를 소유한다. `BoardingTrackingRuntime`의 revision watermark와 검증 snapshot 조회 인덱스가 같은 프로세스 gate를 제공하며 별도 저장소·Event framework·Worker/package는 추가하지 않는다.

## Persistence Location

### JPA Repository

JPA Repository는 Entity를 소유한 Domain 안에 둔다.

```text
user/repository/UserRepository
user/repository/RefreshTokenRepository
alarm/repository/AlarmRepository
boarding/repository/BoardingSessionRepository
notification/repository/NotificationEventRepository
notification/repository/NotificationDeliveryRepository
notification/repository/DeviceRepository
transit/repository/BusRouteRepository
transit/repository/BusStopRepository
transit/repository/BusRouteStopOccurrenceRepository
transit/repository/BusMetadataSyncStateRepository
```

TASK-707은 `notification.entity.NotificationEvent`/`NotificationDelivery`와 각각의 JPA Repository를 추가하고 구형 NotificationHistory 관련 미사용 코드를 제거했다. Device Entity/Platform은 TASK-702 구현을 유지하며 TASK-703은 DeviceController/DeviceService/DeviceRepository와 Device 요청·응답 DTO를 추가했다.

JPA는 `User`, `RefreshToken`, `Alarm`, `BusAlarmTarget`, `BoardingSession`, Device/Notification persistence, Bus metadata의 단순 CRUD와 Entity 상태 관리에 사용한다. `BusAlarmTarget`은 별도 Repository로 독립 관리하지 않고 Alarm aggregate의 cascade lifecycle을 따른다.

### SQL Mapper (실제 필요 시)

MyBatis를 실제 도입할 필요가 확인되면 SQL 책임을 가진 Domain 안에 Mapper를 둔다. 아래 SQL Mapper Class는 현재 구현에 없다. 현재 `transit/mapper`에는 Provider Observation·후보 변환 코드가 있으며 MyBatis SQL Mapper와 구분한다.

```text
transit/mapper/TransitQueryMapper
transit/mapper/AlarmGroupQueryMapper
transit/mapper/StatisticsQueryMapper
```

Transit 관련 Query, Complex Query, Statistics Query, 성능 최적화가 필요한 조회에서 SQL 제어의 실제 필요가 확인되면 MyBatis 도입을 결정한다. Mapper 이름과 SQL file 위치는 그 구현 시 Spring/MyBatis 설정에 맞추되, Domain 경계를 넘는 범용 Mapper를 만들지 않는다.

## DTO Location

DTO는 사용하는 Domain Package 안에 둔다.

```text
user/auth/dto/
alarm/dto/
boarding/dto/
transit/dto/
notification/dto/
```

외부 Transit provider의 응답 DTO는 `transit/dto/seoul`, `transit/dto/tago`에 둔다. Metadata source 수집용 DTO는 `transit/metadata` 내부에 둔다. provider 응답 객체를 API response로 직접 노출하지 않고, StopBell API의 안정적인 DTO로 변환한다.

## Domain Layer and Infrastructure Layer

Domain Layer는 Alarm 조건 평가, 상태 전이, Notification 전송 결정처럼 제품 규칙을 표현한다.

Infrastructure Layer는 JPA Repository, 실제 도입된 SQL Mapper, 외부 Transit API client, FCM client처럼 Database 또는 외부 시스템과 통신한다.

이 구분은 외부 provider와 Persistence 세부사항이 Alarm 규칙에 직접 섞이지 않도록 하기 위함이다. 다만 현재 V1에 불필요한 추상화 계층을 추가하지 않는다.

## Future Consideration

- 추가 Transit provider가 필요해지면 현재의 provider별 client/DTO 구성을 확장할 범위를 검토한다.
- Notification fan-out 또는 통계 기능이 실제 병목이 되면 별도 Query Package 또는 module을 검토할 수 있다.
- Package 구조 변경이 Architecture Decision에 영향을 준다면 ADR로 기록한다.

------------------------------------------------------------------------

# Flutter Application

## Current Structure

```text
app/
└── lib/
    ├── core/
    ├── features/
    │   ├── auth/
    │   ├── alarm/
    │   ├── boarding/
    │   ├── transit/
    │   └── notification/
    ├── firebase_smoke.dart
    └── main.dart
```

위 구조는 현재 구현을 나타낸다. `core`는 공통 설정/인증 HTTP, `features`는 기능별 화면·상태·API 연동을 소유한다. 아직 없는 `shared` Package를 미리 만들지 않는다.

현재 V1은 Auth·Transit·Alarm·Boarding·Device API와 Auth Session, 화면별 상태 및 필요한 `ChangeNotifier`를 사용한다. 별도 state management library는 도입하지 않았다. generic `Repository`/`BaseRepository`, 모든 API별 `UseCase` class, global event bus, 복잡한 refresh queue framework, offline mutation queue, notification stub은 미리 만들지 않는다.

### core

앱 전체에서 사용하는 설정, API client, routing, error handling 등 실제 공통 기반 기능을 둔다. authenticated API client는 Auth Session이 제공한 현재 Access Token을 사용하고 refresh lifecycle은 Auth Session에 위임한다.

### features

사용자 기능을 기준으로 화면, 상태, API 연동 코드를 둔다.

- `auth`: Google Login, Token Pair Secure Storage, startup 상태 복구, refresh 및 logout을 포함한 Auth Session
- `alarm`: `INACTIVE`/`ACTIVE`/`FOLLOW_UP` 상태를 보존하는 Alarm 생성·조회·활성화/비활성화 화면과 Alarm ID 기반 navigation 진입점
- `boarding`: `BoardingHome` 노선/세션 화면 전환, `BoardingStopScreen` occurrence 선택, `BoardingCandidateClient`/`BoardingCandidates`/`BoardingCandidatesView` 후보 조회·비교를 소유한다. `BoardingConfirmationView`는 선택 차량 표시·신규 세션의 2정거장 전(기본)/1정거장 전 설정·명시적 등록 또는 동일 차량 재확인 요청을 담당하며 번호 입력은 요구하지 않는다. `BoardingSession`은 typed 응답, `BoardingSessionApiClient`는 인증 API, `BoardingCreationIntent`/`SecureBoardingIntentStorage`는 생성 identity의 최소 영속 복구, `BoardingSessionController`는 복구·세션 상태·경쟁 제어, `BoardingSessionView`/`BoardingReconfirmationScreen`은 상세·취소·새 후보 재확인 UI를 담당한다. 기존 transit 검색 화면/Client를 재사용하며 `BoardingNotificationScreen`은 정확한 ID의 읽기 전용 Push 상세를, `BoardingSessionDetails`는 관리/Push 상세의 서버 상태 표현을 공유한다. 상세 실행·저장 경계는 [Architecture](architecture.md#flutter-탑승-확정과-세션-관리-task-907-2단계)를 따른다.
- `transit`: Bus Route 검색과 Bus Stop 선택 화면. Stop response의 `canNotifyOneStopBefore`/`canNotifyOneStopAfter`로 option을 제어하며 stale occurrence 생성 `404`에서는 Stop 재선택 흐름으로 복구
- `notification`: TASK-704의 `installation_storage.dart`가 secure 설치본/intent와 native marker를, `device_api_client.dart`가 세 Device API를, `push_permission_service.dart`가 Firebase/native readiness를, `push_registration_service.dart`가 serial session/ownership 동기화를 소유한다. `push_activation_prompt.dart`는 Alarm 활성화/차량 등록 전 permission·Device 준비 안내를, `notification_lifecycle.dart`는 source별 최소 tap/foreground hint의 수신·인증 문맥·중복 처리를 맡아 기존 AlarmHome에 연결한다. `NotificationReceiver`는 같은 세 Firebase callback의 작은 주입 경계이며 별도 Firebase listener나 Event Bus를 추가하지 않는다. 상세 저장·복구 계약은 ADR-010을 따른다.

Flutter logout은 Phase 6 Auth Session의 logout lifecycle/hook에서 현재 Device unsubscribe/disable을 먼저 시도하고 Auth logout과 local session 종료를 이어간다. Device 호출은 별도 authenticated API를 사용하며 `/auth/logout` request에 Device field를 추가하지 않는다. Offline logout에서는 Backend disable을 즉시 보장하지 않고, late auth response가 session을 되살리지 못하게 하는 기존 session generation 보호를 유지한다.

Notification tap payload는 navigation hint일 뿐 권한 근거가 아니다. Auth Session 복원 뒤 source별 Alarm/BoardingSession ID navigation entry를 사용하고 Backend의 정확한 ID 상세 API에서 ownership/current state를 다시 확인한다. 수신·중복·미인증 시작과 읽기 전용 Session 진입은 [TASK-907 3단계](architecture.md#flutter-세션-알림-수신과-상세-이동-task-907-3단계)를 따른다. App resume에서는 permission/registration 상태를 재동기화할 수 있어야 한다.

### 향후 공유 UI

여러 Feature에서 재사용할 UI가 실제로 필요해지면 `shared` 분리를 검토한다. 현재 존재하는 Package로 취급하거나 Feature 고유 규칙을 미리 옮기지 않는다.

### main.dart

애플리케이션 진입점과 설치본 bootstrap → Auth Session 복원 → Firebase/Notification Lifecycle 초기화, 앱 resume 동기화 및 API Client 연결을 담당한다. `firebase_smoke.dart`는 제품 흐름과 분리된 초기 iOS smoke 진입점이다.

## Future Consideration

- 현재의 화면 전환·생성자 주입·`http` 기반 Client로 부족한 문제가 확인되면 별도 routing/DI/state management library의 필요성과 trade-off를 검토한다.
- Transit monitoring 비즈니스 로직은 특별한 이유 없이 Flutter Application으로 옮기지 않는다.
- Auth Session·`AuthenticatedApiClient`·logout hook·resume과 source별 ID navigation은 현재 Device/Notification 흐름에 연결돼 있다. 실제 기기 최종 검증과 운영 보강은 기존 TASK-710/711 및 Phase 8 책임을 따른다.
