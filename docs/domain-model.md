# StopBell Domain Model

## Purpose

Domain Model은 StopBell 서비스에서 다루는 핵심 개념과 관계를 정의한다.

Database Schema와 달리 단순히 컬럼을 정의하는 것이 아니라, 서비스에서
어떤 개념이 존재하고 각 개념이 어떤 책임을 가지는지 표현한다.

------------------------------------------------------------------------

# Core Domain

## Domain Relationship

    User

     ├── Alarm
     ├── RefreshToken
     └── Device


    BusRoute ──< BusRouteStopOccurrence >── BusStop

------------------------------------------------------------------------

# User

## Purpose

서비스 내부에서 사용자를 식별하고 Alarm 소유자의 기준이 되는 최소 Domain이다.

## Responsibilities

-   내부 사용자 식별
-   Alarm 소유자 기준
-   Google Social Identity 연결 기준

## Main Attributes

    id

    authProvider

    providerUserId

    createdAt

    updatedAt

## Identifier Policy

Java에서는 `Long`을 사용한다.

Database에서는 MySQL `BIGINT AUTO_INCREMENT`를 사용한다. JPA 구현 시에는 MySQL `AUTO_INCREMENT`와 호환되는 ID 생성 방식을 사용한다.

현재 프로젝트 규모에서는 UUID 등 별도 식별 전략을 도입하지 않는다.

`User.id`는 StopBell 내부 PK이며 Alarm, Device 등 다른 Domain이 사용자를 참조할 때 사용한다. 외부 Social Identity는 User에 직접 저장하며, 별도 `AuthIdentity` Domain은 만들지 않는다.

`authProvider`는 `GOOGLE`, `APPLE`, `KAKAO`, `NAVER` 값을 갖는 `AuthProvider` Enum이며, JPA와 Database에는 문자열로 저장한다. 최초 Provider는 `GOOGLE`이고, Google OpenID Connect의 `sub`를 `providerUserId`로 사용한다. `authProvider`와 `providerUserId` 조합은 하나의 StopBell User를 유일하게 식별해야 한다.

동일한 실제 사람이 서로 다른 Provider로 로그인하더라도 각각 별도 User로 처리한다. Account Linking과 Account Merge는 현재 범위에 포함하지 않는다.

## Timestamp Policy

현재 `createdAt`, `updatedAt`은 Java에서 `LocalDateTime`, Database에서 `DATETIME(6)`으로 관리한다. 두 컬럼은 `NOT NULL`을 기본 정책으로 한다. 이 표현의 persisted 의미는 UTC여야 하며 host local timezone에 따라 달라져서는 안 된다. TASK-814에서 현재 Schema와 migration 비용을 확인해 모든 operational time을 `Instant`로 전환할지, UTC `LocalDateTime`/`DATETIME(6)` 의미를 유지할지 결정한다.

timestamp는 JPA lifecycle callback으로 관리한다. `@PrePersist`에서 `createdAt`과 `updatedAt`을 초기화하고, `@PreUpdate`에서 `updatedAt`을 갱신한다.

현재 단계에서는 Spring Data Auditing, `@CreatedDate`, `@LastModifiedDate`, `@EnableJpaAuditing`, `BaseEntity` 같은 공통 상속 구조를 도입하지 않는다. 현재 규모에서는 lifecycle callback 방식이 충분하며, 중복 또는 관리 비용이 실제 문제가 되면 별도로 재검토할 수 있다.

## Relationship

    User 1 : N Alarm

한 사용자는 여러 개의 Alarm을 등록할 수 있다.

User Entity에는 `alarms` collection을 추가하지 않는다. Alarm 구현 시 Alarm이 User를 참조하는 방향을 우선하며, JPA 양방향 관계는 실제 필요가 생길 때 검토한다.

## Persistence

    JPA

User는 단순한 Domain CRUD와 Entity 상태 관리를 위해 JPA Repository 기반으로 관리한다.

------------------------------------------------------------------------

# RefreshToken

## Purpose

StopBell의 장기 로그인 Authentication Session을 표현한다. Access Token 재발급과 현재 Session Logout에 사용하며, Push Device와 직접 연결하지 않는다.

## Main Attributes

    id

    user

    tokenHash

    expiresAt

    createdAt

`tokenHash`는 SecureRandom으로 생성한 256-bit URL-safe Base64 Refresh Token 원문을 SHA-256으로 Hash한 64-char lowercase hex 값이다. 원문은 Database에 저장하지 않으며, 서로 다른 RefreshToken은 같은 `tokenHash`를 가질 수 없도록 Database Unique Constraint로 강제한다.

## Relationship

    User 1 : N RefreshToken

한 User는 여러 Device 또는 Login Session에 대응하는 여러 Refresh Token을 가질 수 있다.

## Lifecycle

Refresh Token 기본 수명은 발급 시점부터 30일이다. 재발급이 성공하면 기존 Token은 폐기하고, 새 Access Token 및 새 Refresh Token을 함께 발급한다. 새 Refresh Token의 수명은 새 발급 시점부터 다시 30일이다.

Rotation 또는 Logout으로 무효화할 때는 해당 RefreshToken을 삭제한다. Logout은 제시된 Refresh Token의 Hash와 일치하는 현재 Session만 삭제하며, 만료·존재 여부와 관계없이 성공으로 처리한다. 같은 User의 다른 Refresh Token Session은 유지한다. 별도 `revokedAt` 상태는 관리하지 않는다.

`updatedAt`, `revokedAt`, `deviceId`, `lastUsedAt`, `tokenFamily`는 현재 추가하지 않는다.

## Persistence

    JPA

RefreshToken은 별도 Entity와 Repository로 관리한다. User Entity에 Refresh Token을 저장하지 않는다.

------------------------------------------------------------------------

# Device

## Purpose

앱 installation의 identity, 현재 owner와 회전 가능한 FID delivery reference를 구분한다.

## Attributes

| Field | 의미 |
| --- | --- |
| `id` | Backend 내부 PK |
| `user` | 현재 owner, 필수 User 관계 |
| `installationId` | User-scoped가 아닌 StopBell 앱 설치본 UUID v4 |
| `installationCredentialHash` | 설치본 보유 권한을 확인하는 별도 자격증명의 SHA-256 hash |
| `platform` | `DevicePlatform.IOS` / `ANDROID`, 설치본 동안 불변 |
| `currentPushTargetId` | 현재 FCM 등록 callback의 FID, disabled에서는 null |
| `registrationRevision` | 설치본 전체에서 증가하는 Client 요청 순서, 0 이상 |
| `ownershipGeneration` | 최초 0, owner 이전 시 서버가 정확히 1 증가시키는 세대 |
| `enabled` | 현재 StopBell Push 수신 대상 여부 |
| `lastRegisteredAt` | 마지막으로 적용한 등록의 서버 UTC 시각, disable/멱등 재요청에서 유지 |
| `createdAt`, `updatedAt` | JPA callback의 UTC 생성·수정 시각 |

Flutter는 설치 시 표준 hyphenated UUID v4를 생성하고 소문자로 정규화한다. Backend도 정확한 36자 UUID v4 형식과 variant를 검증한 뒤 `Locale.ROOT` 소문자로 저장한다. 공백 trim, 축약 UUID 또는 다른 UUID version은 허용하지 않는다. 재시작·업데이트·logout·계정 변경에는 유지하고 삭제 후 재설치에는 새로 생성한다. iOS Keychain/backup에 남은 값만으로 새 설치를 이전 설치로 복원하지 않도록 설치 범위 marker와 함께 관리한다. 실제 Flutter 저장 구현은 TASK-704 책임이다.

`installationId`는 공개 식별자이며 인증 수단이 아니다. 설치본 자격증명은 독립적인 CSPRNG 256-bit 비밀값으로 생성해 OS Secure Storage에 보관하고 logout에도 유지한다. Backend에는 hash만 보관하며 JWT와 함께 검증한다. UUID/FID/revision을 안다는 이유로 자격증명을 재발급·교체하거나 owner를 이전하지 않는다. 자격증명 유실은 fail closed이며 UUID만으로 복구하지 않는다. counter/generation metadata는 JWT와 해당 자격증명으로 보호하는 상태 조회로 복구할 수 있다.

FID는 대소문자를 구분하는 opaque delivery reference다. 22자 관측값에 길이를 고정하지 않고 Firebase rotation에 따라 갱신한다. APNs token, legacy FCM token, raw FIS `getId()`만의 결과를 Device identity 또는 등록 완료 target으로 사용하지 않는다. FCM HTTP v1 `message.fid`와 readiness 계약은 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md)을 따른다.

## Relationship and Lifecycle

    User 1 : N Device

`installationId`마다 row 하나와 current owner 한 명만 존재한다. 계정 변경은 기존 row의 owner·generation·revision·FID·enabled를 한 transaction에서 이전한다. 설치본 자격증명, 명시적 이전 의도, current generation 일치가 모두 필요하다. RefreshToken과 Device FK는 없다.

권한과 generation 검증을 통과한 요청에서 revision이 더 높으면 적용, 같고 요청의 결과 상태가 같으면 멱등 성공, 같고 다르면 conflict, 낮으면 stale 거부다. revision은 logout/owner 이전에도 reset하지 않는다. generation은 revision과 독립적이며 이전 owner의 지연된 요청은 큰 revision이라도 현재 상태를 변경하지 못한다. 상세 비교 순서와 이전 성공 재요청은 [Device API 계약](api.md#기기-등록해제-계약-task-702)을 따른다.

등록은 enabled 상태로 생성/갱신/재활성화한다. disable은 row와 owner를 보존하고 enabled=false 및 FID=null로 해제한다. 다른 Device와 Alarm은 바꾸지 않는다. Provider invalid/unregistered cleanup은 실패 attempt의 owner/generation/target/revision이 current registration과 일치할 때만 조건부 disable하며 Client revision을 임의 증가시키지 않는다. 실제 cleanup은 TASK-709 책임이다.

TASK-702는 `notification.entity.Device`, `DevicePlatform`과 V13 매핑만 구현한다. 상태 변경 method, Repository, Service, Controller, DTO와 동시성/멱등 처리 실행은 TASK-703 책임이다.

------------------------------------------------------------------------

# Alarm

## Purpose

사용자가 원하는 알림 조건의 공통 정보를 표현하는 핵심 Domain이다.

예:

"143번 버스가 서울역 정류장에 도착하면 알려줘"

라는 사용자의 요청 하나가 하나의 Alarm이다.

## Responsibilities

-   Alarm 공통 정보 관리
-   Alarm lifecycle 상태 관리
-   ARRIVED 뒤 ONE_STOP_AFTER 전용 follow-up runtime 관리
-   Bus Alarm Target aggregate lifecycle 관리

## Main Attributes

    id

    user

    transitType

    status

    activationGeneration

    followUpVehicleTrackingId (FOLLOW_UP only)

    followUpTrackingCycleId (FOLLOW_UP only)

    followUpStartedAt (FOLLOW_UP only)

    followUpExpiresAt (FOLLOW_UP only)

    createdAt

    updatedAt

## Alarm Lifecycle

`status`는 `INACTIVE`, `ACTIVE`, `FOLLOW_UP` 값을 갖는 `AlarmStatus` Enum이며 문자열로 영속화한다. 새 Alarm의 기본 상태는 `INACTIVE`다.

- `INACTIVE`: 일반 monitoring과 ARRIVED 후 follow-up이 모두 없음
- `ACTIVE`: 다음 차량의 Target 도착을 일반 monitoring 중임
- `FOLLOW_UP`: ARRIVED가 이미 성공했고 다른 차량 monitoring은 종료됐으며, `notifyOneStopAfter` 때문에 ARRIVED 차량의 successor 진행만 추적 중임

`ARRIVED`, `PASSED`, `ONE_STOP_BEFORE`, `ONE_STOP_AFTER`는 lifecycle 상태가 아니라 Event다. 특히 `ONE_STOP_BEFORE`와 `PASSED` 뒤에는 `ACTIVE`를 유지하고, `ARRIVED` 뒤 after 옵션이 꺼져 있으면 `INACTIVE`, 켜져 있으면 `FOLLOW_UP`으로 전환한다.

`activate()`는 `INACTIVE` 또는 `FOLLOW_UP`을 `ACTIVE`로 전환해 새 monitoring cycle을 시작한다. FOLLOW_UP에서 호출되면 이전 follow-up runtime을 지워 old follow-up을 취소한다. 이미 ACTIVE이면 generation 증가와 baseline reset 없이 idempotent하게 상태를 유지한다. `deactivate()`는 ACTIVE/FOLLOW_UP을 `INACTIVE`로 전환하고 follow-up runtime을 지운다.

`activationGeneration`은 서로 다른 monitoring activation cycle을 구분하는 persisted semantic generation이며 `BIGINT NOT NULL DEFAULT 0`으로 저장한다. `INACTIVE → ACTIVE`와 `FOLLOW_UP → ACTIVE`에서 증가하여 deactivate 후 reactivate, FOLLOW_UP 중 reactivate, stale scheduler result와 이전 activation의 Notification candidate를 현재 activation과 구분한다. `ACTIVE → ACTIVE`에서는 증가하지 않는다. lifecycle mutation과 Scheduler 결과 반영은 Alarm row `PESSIMISTIC_WRITE` 하나로 보호하며 `@Version`, CAS, optimistic locking을 함께 추가하지 않는다.

`startFollowUp(vehicleTrackingId, trackingCycleId, startedAt, expiresAt)`은 ACTIVE이며 `notifyOneStopAfter`가 설정된 Bus Alarm에서만 FOLLOW_UP을 시작한다. 만료시간 숫자는 이 Domain이 정하지 않고 호출자가 명시적으로 전달한다. `completeFollowUp()`은 FOLLOW_UP을 INACTIVE로 전환하고 runtime을 지운다.

FOLLOW_UP이면 non-blank `followUpVehicleTrackingId`, 원래 UUID `followUpTrackingCycleId`, `followUpStartedAt`, `followUpExpiresAt`이 모두 존재하고 expiry가 start보다 뒤여야 한다. FOLLOW_UP이 아니면 네 runtime field는 모두 비어 있어야 한다. after 옵션과 runtime의 교차-table 불변 조건은 Domain이, runtime field의 완전성과 status 조합은 Domain과 Database CHECK가 함께 강제한다.

TASK-707은 TASK-708 설계의 `followUpTrackingCycleId`(UUID)를 네 번째 runtime field로 영속했다. `startFollowUp(vehicleTrackingId, trackingCycleId, startedAt, expiresAt)`은 ARRIVED candidate의 원래 UUID를 필수로 받아 보존하고, activate/deactivate/completeFollowUp 시 네 값을 함께 비운다. FOLLOW_UP이면 네 값 모두 존재하고 그 외 상태이면 모두 없어야 한다. ARRIVED와 ONE_STOP_AFTER는 동일 `activationGeneration`/`trackingCycleId`를 공유하며 vehicle correlation ID만으로 cycle을 대체하지 않는다. 결정 근거는 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#follow_up-cycle-continuity-결정), Schema/적용 전제는 [alarms](database.md#alarms)가 소유한다.

Transit API 조회 실패, Notification delivery 결과, Alarm trigger는 Alarm의 상태가 아니다. Logical notification 결정은 `NotificationEvent`, Device별 전달 상태는 `NotificationDelivery`로 분리한다. ACTIVE의 차량별 tracking state는 V1에서 memory 기반일 수 있으므로 Backend restart 뒤에는 이전 state와 새 Observation을 연결하지 않고 안전한 baseline부터 시작한다. 이는 restart 직후 false PASSED 또는 ONE_STOP_BEFORE 재발행을 피하기 위한 방향이다. 반면 FOLLOW_UP runtime은 이 Entity에 영속된 값으로 유효 기간 안에 재개한다.

`transitType`은 `BUS`, `SUBWAY`를 표현하는 Enum으로 관리하며, Database에는 문자열로 저장한다.

Bus Alarm의 장기 설정은 `Alarm`의 lifecycle runtime과 섞지 않고 공유 PK `BusAlarmTarget` Entity로 분리한다. Alarm이 aggregate lifecycle을 소유하며 persist/remove를 cascade한다. V1에서 지원하는 모든 BUS Alarm은 `BusAlarmTarget`을 반드시 가진다. Pre-production migration 과정의 targetless legacy BUS row는 public V1 지원 상태가 아니며 production 전 개발 DB reset/cleanup 또는 migration 검증으로 존재하지 않음을 보장한다. 이를 위한 compatibility code는 추가하지 않는다.

## Bus Alarm Transit Target Contract

사용자는 Bus Route와 Target Stop을 직접 선택한다. First Stop은 선택 가능한 일반 Target occurrence의 하나이며 모든 Alarm의 고정 Target이 아니다.

영속 계약:

```text
external references
    provider
    externalRouteId
    externalStopId

target occurrence operational snapshot
    targetStopOrder
    targetStopLatitude (optional)
    targetStopLongitude (optional)
    cityCode (TAGO only)

display snapshot
    routeNumber
    stopName

notification options
    notifyOneStopBefore
    predecessorExternalStopId / predecessorStopOrder (option ON only)
    notifyOneStopAfter
    successorExternalStopId / successorStopOrder (option ON only)
```

Route external identity는 `(provider, externalRouteId)`, Stop external identity는 `(provider, externalStopId)`이며 둘 다 ADR-006에서 정한 opaque external reference다. Vehicle identifier는 Alarm Target에 포함하지 않는다.

사용자가 선택하는 Alarm Target은 Stop identity 자체가 아니라 Route traversal 안의 특정 Stop occurrence다. Route/Stop reference와 `targetStopOrder`로 선택 당시 occurrence를 보존한다. 현재 Provider 계약에서 별도 direction/traversal 값을 안전하게 영속할 구체적 field가 확인되지 않아 추측성 column은 추가하지 않았다. 같은 Route의 sequence에 같은 Stop ID가 여러 번 나타날 수 있으므로 `(provider, externalRouteId, externalStopId)`만으로 Target occurrence의 uniqueness가 보장되지 않으며 이 조합의 Unique Constraint를 두지 않는다.

`targetStopOrder`는 선택한 Route traversal에서 target occurrence를 연결하고 이전/다음 Stop을 판단하기 위한 필수 operational metadata다. Stop identity가 아니며 Provider metadata 변경 뒤 stale할 수 있다. 순환·재방문·분기·회차로 같은 Stop ID가 여러 번 등장하면 order와 필요한 traversal/direction context로 사용자가 선택한 occurrence를 구분해야 한다.

Target Stop 좌표는 Provider가 metadata로 제공할 때 `BigDecimal`/`DECIMAL(10,7)`로 함께 저장하는 optional operational snapshot이며, 특히 경기 first-stop에서 Stop ID·order와 함께 GPS 근접 근거를 평가하는 데 사용한다. GPS distance threshold는 TASK-509에서 결정한다.

`routeNumber`와 `stopName`은 검색·표시 및 Notification 위치 안내를 위한 snapshot이지 identity가 아니다. TAGO `cityCode`는 API request를 재현하기 위한 필수 typed context column이지 identity가 아니다. TAGO Target에는 non-blank cityCode가 필요하고 서울 Target에는 가짜 cityCode를 저장하지 않는다. 범용 JSON/Map provider context는 사용하지 않는다.

두 Notification option은 독립적인 선택값이며 기본값은 모두 OFF다. Alarm 생성 API는 Client가 Provider metadata를 전달하지 않고 선택한 `BusRouteStopOccurrence.id`를 `targetStopOccurrenceId`로 전달한다. Backend는 current metadata에서 target과 predecessor/successor occurrence를 조회해 option을 검증하고 필요한 snapshot을 생성한다. predecessor snapshot 존재가 before ON, successor snapshot 존재가 after ON을 의미하게 해 option만 켜지고 필요한 occurrence가 없는 조합을 만들지 않는다. 인접 snapshot은 realtime Observation을 metadata 재조회 없이 일치시키는 데 필요한 external Stop ID와 실제 traversal Stop order만 저장한다. 표시에는 Target `stopName`이면 충분하고 인접 GPS는 현재 평가 계약의 필수 근거가 아니므로 인접 Stop name/GPS는 저장하지 않는다. HTTP error 처리 구현은 TASK-409에서 담당한다.

## Persistence

    JPA

Alarm은 생성, 수정, 삭제와 상태 관리를 위해 JPA Repository 기반으로 관리한다.

------------------------------------------------------------------------

# BusRoute

## Purpose

버스 노선 정보를 표현한다.

예:

    143번
    273번

## Main Attributes

    id
    provider
    externalRouteId
    routeNumber
    cityCode (TAGO only)

Route identity는 `(provider, externalRouteId)`다. `id`는 StopBell 내부 PK이고, `routeNumber`는 display metadata다. `cityCode`는 TAGO metadata/realtime request를 재현하는 typed context이며 identity가 아니다. TAGO에는 non-blank cityCode가 필요하고 SEOUL_BUS에는 `null`이어야 한다.

## Relationship

    BusRoute 1 : N BusRouteStopOccurrence

## Persistence

    JPA

서울 T Data CSV full import와 경기 TAGO throttled full sync가 제공하는 current metadata를 JPA Repository로 저장한다. 같은 external identity의 routeNumber/cityCode가 변경되면 row를 UPDATE해 `id`를 유지한다. Source adapter는 complete provider snapshot을 검증한 경우에만 provider-level absence cleanup을 허용하며, partial fetch, parser failure, pagination 미완료, required source 누락, 일부 provider request failure와 검증되지 않은 empty result는 deletion 근거가 아니다. Typed complete snapshot boundary, completeness token/result, destructive method visibility 제한 또는 동등한 구조적 보호를 사용하며 구체 type은 TASK-513에서 정한다. Provider별 최소 persisted sync state는 `provider`, `lastCompleteSyncAt` 의미를 보존해 bootstrap/readiness/마지막 complete sync age 판단에 사용한다. Sync history, checksum history, staging table, error journal은 요구하지 않는다.

------------------------------------------------------------------------

# BusStop

## Purpose

버스 정류장 정보를 표현한다.

예:

    서울역
    강남역
    잠실역

## Main Attributes

    id
    provider
    externalStopId
    stopName
    latitude
    longitude

Stop identity는 `(provider, externalStopId)`다. Route 안의 Stop order, name, 좌표는 operational/display metadata이며 identity가 아니다.

## Relationship

한 Stop은 여러 Route occurrence에 포함될 수 있다. 좌표는 둘 다 null이거나 둘 다 존재하며 latitude `-90~90`, longitude `-180~180` 범위를 만족한다.

## Persistence

    JPA

같은 external identity의 stopName/GPS가 변경되면 row를 UPDATE해 `id`를 유지한다.

------------------------------------------------------------------------

# BusRouteStopOccurrence

## Purpose

특정 Route traversal에서 Stop이 몇 번째로 나타나는지 표현하는 current metadata다. 같은 Route가 같은 Stop을 재방문할 수 있으므로 Stop identity와 occurrence를 분리한다.

## Main Attributes

    id
    route
    stop
    stopOrder
    destinationName (optional)

`route`와 `stop`은 같은 provider여야 하고 `stopOrder`는 양수다. Route 안에서 `(route, stopOrder)`는 유일하지만 `(route, stop)`은 유일하지 않다.

`destinationName`은 경기 TAGO traversal 전체가 GBIS route/routeStation과 검증되어 일치할 때만 붙는 표시 metadata다. occurrence identity가 아니며 서울 또는 검증 실패 Route에서는 `null`이다. GBIS raw ID, `upDown`, `turnSeq`는 이 Entity의 영속 identity가 아니다.

## Persistence

    JPA

Route snapshot reconciliation은 `(route, stop, stopOrder)`가 완전히 같은 occurrence만 같은 내부 ID를 유지한다. Stop 또는 order가 바뀌면 기존 occurrence를 삭제하고 새 row를 생성해 의미가 바뀐 occurrence ID를 재사용하지 않는다.
동일 occurrence의 destination 변경 또는 제거는 기존 ID를 유지한 채 nullable metadata를 갱신한다.

------------------------------------------------------------------------

# TransitObservation

## Purpose

Provider raw response를 StopBell이 해석한 한 차량의 현재 관측 사실이다. Database Entity나 Notification Event가 아니며, Provider DTO와 Alarm Evaluation 사이의 provider-neutral 계약이다.

## Conceptual Contract

```text
source and correlation
    provider
    externalRouteId
    vehicleTrackingId

current route progress
    currentStopExternalId (optional)
    currentStopOrder (optional)
    currentStopName (optional, metadata로 보강 가능)
    directionContext (optional)
    sectionContext (optional)

position and time
    latitude (optional)
    longitude (optional)
    observedAt
    providerDataTime (optional)

direct arrival evidence
    ARRIVED | MOVING | UNAVAILABLE
```

`provider`와 `externalRouteId`는 요청 Route 문맥을 보존한다. `vehicleTrackingId`는 한 monitoring run에서 Observation을 연결하기 위한 transient reference이며 Alarm identity가 아니다. 경기 TAGO는 `vehicleno`, 서울은 `vehId`를 primary tracking reference로 사용한다. 서울 `plainNo`는 보조 확인·표시값일 수 있지만 `vehId` 누락 또는 변경을 자동으로 같은 차량이라고 단정하는 대체 identity가 아니다.

정상적인 trackable Observation의 필수 envelope는 `provider`, `externalRouteId`, `vehicleTrackingId`, `observedAt`, `arrivalEvidence`다. `arrivalEvidence`는 direct flag가 없을 때도 거짓 `MOVING` 대신 `UNAVAILABLE`을 명시한다. 필수 envelope를 만들 수 없는 raw item은 다른 차량과 연결하지 않고 Provider 정상 응답 안의 mapping ambiguity로 보존해 Evaluation을 UNKNOWN으로 만든다.

현재 Stop ID/order가 Provider 응답에서 없으면 억지로 채우지 않는다. `currentStopName`은 PASSED 위치 안내를 위해 Route metadata로 보강할 수 있다. GPS도 optional이며 사용자에게 raw 숫자를 기본 표시하지 않고 Stop name 또는 “최근 확인된 위치” 표현을 보조하는 내부 근거로 사용한다.

`observedAt`은 StopBell이 성공한 Provider response를 받은 직후의 시각이고 항상 존재한다. polling 시작 시각이 아니며, 같은 response에서 mapping한 차량은 같은 receive-time context를 공유한다. Java 표현은 UTC `Instant`이며 mapper의 test 가능한 `Clock`에서 한 response당 한 번 얻는다. `providerDataTime`은 Provider가 제공한 원본 data 시각이며 optional이다. 서울 `dataTm`의 `yyyyMMddHHmmss` 값은 조사 시각과 같은 `Asia/Seoul`로 명시 해석해 UTC `Instant`로 변환한다. 둘을 구분해야 반복·stale data를 판단할 수 있다. Provider가 data 시각을 주지 않으면 현재 수신 시각만으로 upstream freshness가 보장된다고 가정하지 않는다.

`directionContext`와 `sectionContext`는 Provider가 제공할 때 같은 Route traversal에서 Stop order를 비교할 수 있는지 판단하는 operational evidence다. Provider raw field 이름을 Domain contract로 노출하지 않고, 없는 방향·회차 정보를 추측하지 않는다. Stop order가 역행하거나 순환·회차·분기로 traversal이 모호하면 위치 관계는 `UNKNOWN`이다.

현재 Observation에 `previousStopId`/`previousStopOrder`를 중복 저장하지 않는다. Evaluation은 같은 Alarm·Route·Vehicle tracking cycle의 이전 `TransitObservation`과 현재 값을 비교한다.

## Provider Mapping

| 공통 의미 | 경기 TAGO | 서울 버스위치 |
| --- | --- | --- |
| Provider / Route | `TAGO` + 요청 `routeId` | `SEOUL_BUS` + 요청 `busRouteId` 또는 응답 `routeId` |
| Vehicle tracking | `vehicleno` | `vehId`; `plainNo`는 보조값 |
| Current Stop | `nodeId`, `nodeOrd` | vehicle detail의 `stId`, `stOrd`; Route roster의 `sectOrd`/`sectionId`는 coarse section context |
| GPS | `gpslati`, `gpslong` | WGS84 `tmY`/`tmX` 또는 operation별 WGS84 좌표 |
| Provider data time | 제공되지 않으면 없음 | `dataTm` |
| Direct arrival evidence | `UNAVAILABLE` | `stopFlag=1 → ARRIVED`, `stopFlag=0 → MOVING`, 누락/해석 불가 → `UNAVAILABLE` |

서울 Route 전체 조회는 차량 roster와 coarse 상태를 확인하는 용도이며 `sectOrd`, `sectionId`, `nextStId`만으로 target Stop occurrence를 확정하지 않는다. 필요한 서울 차량의 정확한 Stop observation은 vehicle detail의 `stId`/`stOrd`/`stopFlag`에서 만든다. 어떤 차량을 detail 조회할지는 Client가 아니라 polling/orchestration이 결정하며, `sectOrd == stOrd` 같은 관계를 만들지 않는다. TASK-503은 timeout/network, HTTP non-success, Provider logical error, decode/protocol error를 정상 empty와 구분해 전달하고, TASK-504는 정상 raw response만 이 Domain 계약으로 mapping한다.

TAGO Arrival의 `arrprevstationcnt`와 `arrtime`은 Route/Stop 수준의 auxiliary evidence다. Vehicle identifier가 없으므로 특정 Location 차량의 direct arrival evidence로 채우지 않는다. `MOVING`은 “직접 도착 상태가 아님”이라는 뜻이며 target을 이미 통과했다는 뜻이 아니다. `UNAVAILABLE`은 `false`가 아니며 direct flag가 없거나 사용할 수 없음을 뜻한다.

# Transit Evaluation 및 TransitEvent

## Position Relation

Observation에서 Target과의 위치 관계를 먼저 구분한다.

```text
BEFORE_TARGET
AT_TARGET
AFTER_TARGET
UNKNOWN
```

`APPROACHING`은 `BEFORE_TARGET`을 사용자에게 설명하는 표현으로 사용할 수 있지만, V1의 별도 Event가 아니다. 위치 관계는 같은 방향·Route traversal에서 Stop ID/order, GPS, section context와 시간 연속성이 서로 일관될 때만 확정한다.

## Event Candidate

위치 관계와 이전 tracking state 및 Alarm option을 조합해 다음 Event 후보 하나 또는 없음을 만든다.

```text
ONE_STOP_BEFORE
ARRIVED
PASSED
ONE_STOP_AFTER
```

`UNKNOWN`은 Event가 아니라 평가 불가 결과다. `TransitEvent`는 위 Event 후보와 Alarm/Vehicle/tracking cycle 문맥 및 Notification에 필요한 최신 위치 근거를 묶는 Domain 개념으로 사용한다. 실제 Java type과 field는 TASK-504에서 결정한다.

## Event Rules

### ARRIVED

같은 Route의 같은 차량이 Target Stop에 도착했다는 충분하고 일관된 근거가 있을 때 발생한다. 서울의 target Stop ID/order와 direct `ARRIVED` evidence는 강한 근거다. 경기처럼 direct flag가 없으면 target `nodeId`/`nodeOrd`, target GPS 근접, fresh observation, 같은 차량의 시간에 따른 진행처럼 서로 일관된 신호를 조합할 수 있다. 완벽한 direct flag만 기다리지는 않지만 GPS threshold 같은 숫자는 TASK-509와 추가 실측에서 정한다.

Alarm 활성화 순간 이미 Target에 있는 차량도 같은 충분성 기준을 만족하면 즉시 ARRIVED다. ARRIVED Notification 뒤 Alarm은 성공 처리되어 비활성화된다.

### PASSED

Alarm 활성화 뒤 Target 이전부터 같은 tracking cycle에서 관찰한 차량이, ARRIVED를 직접 관찰하지 못한 채 Target 이후로 진행했다는 충분한 근거가 있을 때 발생한다. 단순히 `currentStopOrder > targetStopOrder` 하나만으로 판정하지 않고, 같은 Vehicle·Route traversal, 이전 BEFORE_TARGET, fresh하고 단조로운 진행, Stop/section/GPS 신호의 일관성을 요구한다.

PASSED Notification은 가능한 경우 다음 최신 위치 근거를 갖는다.

```text
currentStopExternalId
currentStopName
currentStopOrder
latitude / longitude
stopsPastTarget (optional derived value)
observedAt / providerDataTime
```

사용자에게는 raw GPS보다 Stop name과 “최근 확인된 위치” 의미를 우선한다. `stopsPastTarget`은 같은 Route traversal에서 Target occurrence부터 현재 확인된 Stop occurrence까지 metadata sequence로 센 successor edge 수다. raw `currentStopOrder - targetStopOrder`가 아니며 Stop order가 연속 정수라고 가정하지 않는다. 동일 Route/traversal/direction, 양쪽 occurrence와 그 사이 sequence를 모두 확인할 수 있을 때만 계산하고, 불확실하면 unavailable로 둔다. 이 값이 없어도 PASSED Notification은 발생할 수 있다.

PASSED는 해당 Vehicle tracking을 끝내지만 Alarm을 성공 처리하지 않는다. Alarm은 ACTIVE로 유지하고 다음 차량을 계속 감시한다.

### ONE_STOP_BEFORE

before 옵션이 켜져 있고 같은 차량이 선택한 Route traversal에서 Target의 직전 Stop에 도달했을 때 한 번 발생한다. 직전 Stop은 단순 `targetStopOrder - 1`이 아니라 metadata가 확인한 predecessor다. Target이 첫 Stop이면 옵션 자체가 유효하지 않다.

Alarm 활성화 순간 baseline 차량이 이미 predecessor에 있다고 충분히 판단되면 즉시 ONE_STOP_BEFORE 후보가 된다. baseline이라는 이유로 무시하지 않는다. Event를 보낸 뒤 Alarm은 ACTIVE이고 같은 차량 tracking은 계속되며, 이후 ARRIVED가 발생하면 정상 성공 lifecycle로 전환한다.

### ONE_STOP_AFTER

after 옵션이 켜진 Alarm에서 ARRIVED Notification을 이미 발생시킨 동일 차량이 같은 Route traversal의 successor Stop에 도달했거나, polling jump로 successor 이상 진행했다는 충분한 근거가 있을 때 한 번 발생한다. Target이 마지막 Stop이면 옵션 자체가 유효하지 않다. 이 Event는 ARRIVED 뒤 follow-up 전용이며 PASSED로 재분류하지 않는다.

## Event Precedence

한 Observation transition에서 Notification 후보는 하나만 선택한다.

```text
일반 tracking: ARRIVED > PASSED > ONE_STOP_BEFORE > 없음
ARRIVED follow-up: ONE_STOP_AFTER > 없음
```

`previous < target`, `current > target`이고 ARRIVED를 직접 관찰하지 못했다면 PASSED를 선택하며 before/arrival/after를 함께 만들지 않는다. 현재 Observation이 target 도착을 충분히 직접 보여 주면 ARRIVED가 우선한다. 이미 ARRIVED를 보낸 차량이 target 다음 Stop을 건너뛰어 더 진행했다면 ONE_STOP_AFTER를 한 번 만들 수 있다.

## UNKNOWN

다음 조건에서는 Event를 억지로 만들지 않고 다음 Observation을 기다린다.

- Vehicle tracking reference 변경·누락 또는 차량 일시 소실
- Stop order 역행, 방향·회차·순환·분기 문맥 불명
- stale Provider data 또는 서로 충돌하는 Stop/GPS/arrival evidence
- 평가에 필요한 Route/Stop reference 누락
- 정상 응답이지만 현재 차량 진행을 확정할 근거 부족

Timeout, HTTP error, Provider error, protocol error는 정상 empty 및 정상 응답 안의 ambiguity와 구분되는 Provider failure다. 실패 자체는 `TransitObservation`이나 `TransitEvent`가 아니며 Event 없는 UNKNOWN으로 취급한다. TAGO Route 또는 서울 roster 실패는 해당 Route의 평가를 생략해 기존 tracking과 Alarm lifecycle을 보존한다. 서울 detail 일부 실패는 실패 차량의 위치·Event 판단만 생략하고, 성공 차량은 즉시 평가하며 roster에서 확인한 실패 차량의 presence는 유지한다. 첫 실패 요청만 모든 첫 조회 후 약 5초 뒤 한 번 재시도하고, 다시 실패하면 상태를 보존한 채 다음 일반 cycle을 기다린다.

## Tracking Lifecycle

Alarm 활성화 시 현재 Route 차량을 baseline으로 관찰한다.

```text
Target 이전 → tracking 후보
정확히 predecessor + before option ON → tracking 후보 + 즉시 ONE_STOP_BEFORE 후보
Target 위치 → 충분한 근거가 있으면 즉시 ARRIVED
Target 이후 → baseline existing vehicle로 무시
UNKNOWN → Notification 없이 다음 Observation 대기
```

새 차량이 이후 나타나 Target 이전에서 관찰되면 새 tracking 후보가 될 수 있다. PASSED 뒤 해당 차량 cycle은 종료하고 같은 Event를 다시 만들지 않는다. 같은 tracking reference가 순환해 다시 Target 이전에 나타나는 경우에는 차량 소실·새 운행 시작 등 새 cycle 근거가 있어야 하며 단순 order 역행만으로 재사용하지 않는다.

ARRIVED 뒤 after 옵션이 꺼져 있으면 Alarm을 INACTIVE로 전환하고 모든 차량 tracking을 끝낸다. 옵션이 켜져 있으면 Alarm을 FOLLOW_UP으로 전환하고 다른 차량 tracking을 끝내며, 성공 차량만 ONE_STOP_AFTER까지 short follow-up 한다. FOLLOW_UP runtime은 Alarm에 영속되어 재시작 뒤 복구할 수 있다. V1 follow-up timeout은 ARRIVED 시점부터 5분이며, timeout은 Transit Event 없이 후속 orchestration이 `completeFollowUp()`할 수 있는 평가 결과다.

FOLLOW_UP 상태의 동일 Alarm을 사용자가 다시 활성화하면 이전 activation cycle의 follow-up runtime을 지우고 새 baseline으로 새 monitoring cycle을 시작한다. 비활성화와 follow-up 완료도 runtime을 지운다. Alarm 삭제 시에는 Alarm column인 runtime과 공유 PK BusAlarmTarget이 함께 삭제된다.

반복 Observation 억제와 중복 Event candidate 억제는 tracking/Evaluation 책임이다. ACTIVE tracking은 V1에서 memory 기반이며, `BusAlarmEvaluationState`는 차량별 최신 Observation, 마지막 관찰 시각, 해당 cycle에서 target 이전을 관찰했는지와 이미 낸 Event type만 보존한다. raw Observation history 전체는 저장하지 않는다. `trackingCycleId`는 `UUID`이고 transaction ID가 아니라 logical vehicle tracking cycle identity이며 cycle 시작 시 한 번 생성한다. 같은 logical cycle의 lifecycle transaction retry에서는 identity를 유지해 Notification dedup uniqueness를 우회하지 않는다. TransitEvent가 Notification candidate가 되면 cycle identity를 durable `NotificationEvent`에 복사한다. ACTIVE state는 재시작 뒤 복원하거나 이전 Observation과 연결하지 않고 새 baseline으로 시작한다.

TASK-509의 V1 evidence policy는 다음과 같다. `observedAt`과 서울의 `providerDataTime`은 평가 시각보다 60초를 초과해 오래되었거나 미래이면 `UNKNOWN`이다. TAGO target ARRIVED는 exact Stop ID/order와 target GPS 100m 이내 corroboration을 baseline에서 요구하며, 이미 같은 cycle에서 target 이전을 관찰한 경우에는 단조 진행도 근거가 된다. Target GPS와 Observation GPS가 모두 있고 거리가 100m를 넘으면 도착 신호와 충돌하므로 `UNKNOWN`이다. 차량은 한 snapshot에 없다는 이유만으로 종료하지 않고 마지막 관찰 후 60초가 지난 뒤에만 tracking cycle을 종료한다. baseline에서 이미 target 이후였던 차량은 이 grace 동안 별도로 기억해 order regression만으로 새 cycle을 만들지 않는다. Stop ID/order conflict, order regression, direction conflict, stale data, duplicate vehicle Observation 또는 근거 부족은 기존 state를 즉시 파괴하지 않는 `UNKNOWN`이다.

## Persistence

`TransitObservation`과 ACTIVE Vehicle tracking state 전체는 V1에서 영속하지 않는다. Scheduler는 `(alarmId, activationGeneration)` key로 ACTIVE/FOLLOW_UP evaluation state를 memory에 보관하고 restart 뒤에는 `initial()` baseline으로 시작한다. Provider I/O 뒤 lifecycle 반영 직전에 `PESSIMISTIC_WRITE`로 current generation/status를 다시 검증하므로 stale result는 lifecycle과 memory state 모두 바꾸지 않는다. Durable Notification dedup에 필요한 activation generation, tracking cycle identity, event type은 TransitEvent에서 `NotificationEvent`로 전달한다. `TransitEvent`는 type, vehicle tracking ID, UUID cycle ID, latest observed time/position, optional provider data time 및 PASSED의 optional metadata-derived `stopsPastTarget`을 provider-neutral하게 전달한다.

TASK-708 implementation에서는 FOLLOW_UP memory가 없을 때 `followUpTrackingCycleId`로 원래 cycle identity를 복원한다. 새 UUID나 과거 Observation을 만들지 않고 현재의 fresh Observation으로 기존 after 판정만 수행한다. DB retry는 선택한 `TransitEvent`와 원래 evaluation result를 재사용하며 evaluator 재실행으로 UUID를 바꾸지 않는다. ACTIVE restart의 새 cycle과 이전 cycle은 자동 연결하지 않는다. 현재 FOLLOW_UP UUID 재생성 및 ACTIVE baseline 재발행의 구현상 한계는 [ADR-010의 조사 결과](adr/ADR-010-notification-device-and-durable-delivery.md#현재-구현-조사와-보장-한계)를 따른다.

------------------------------------------------------------------------

# NotificationEvent

## Purpose

하나의 logical Notification 결정을 durable하게 표현하고 Event 시점 recipient Delivery들의 source 및 dedup 기준이 된다.

## Responsibilities

-   current Alarm lifecycle과 activation generation 검증 결과 보존
-   logical Notification dedup identity 보존
-   Event 결정 시점에 확정한 recipient Device별 pending Delivery 제공

## Conceptual Identity

```text
alarmId
+ activationGeneration
+ trackingCycleId
+ eventType
```

네 identity 값은 필수이며 생성 후 불변이다. `alarmId + eventType`만으로 dedup하지 않는다. 확정한 [Database UNIQUE](database.md#notification_events-identity)는 TASK-707의 V14에서 구현했다. candidate는 평가 당시 `AlarmEvaluationKey`의 alarmId/generation과 선택한 `TransitEvent`의 cycle ID/type을 사용하며 적용 시점 current generation으로 바꿔 이전 후보를 새 activation에 연결하지 않는다. Event owner도 결정 당시 Alarm owner로 고정한다. TASK-707 Entity는 원래 observed/detected 시각과 실제 optional 위치 근거를 불변으로 보존하며 전달받은 UTC 시각을 microsecond로 맞춘다. 역행하는 observed→detected→created 시각은 거부한다.

이미 존재하는 동일 logical Event의 재제출은 무변경 duplicate다. 기존 Event, payload, recipient set, Delivery 상태와 Alarm lifecycle을 변경하지 않으며 새 Device Delivery를 추가하지 않는다. 서로 다른 cycle/type은 별도 identity지만 current lifecycle/evidence를 통과한 candidate만 새 결정이 될 수 있다. 상황별 정책은 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#상황별-정책), transaction/retry는 [Architecture](architecture.md#notification-decision-transaction-task-708)가 소유한다.

Current Alarm lifecycle/activation generation 검증, lifecycle transition, NotificationEvent insert와 그 시점에 eligible한 Device별 Delivery 생성(유효 기한이면 PENDING, 이미 만료면 EXPIRED)은 같은 Database transaction에서 수행한다. eligible Device가 0개여도 이미 발생한 logical NotificationEvent는 저장하고 Delivery는 0개로 두며 no-recipient operational log/metric으로 관찰한다. 이후 등록된 Device에 과거 Event의 Delivery를 생성하지 않는다. Commit 뒤 단일 Backend의 fixed-delay, non-overlapping worker는 due PENDING Delivery만 전달하며 recipient를 새로 결정하지 않는다. FCM network I/O는 이 transaction 안에서 실행하지 않는다.

`observedAt`은 candidate의 근거가 된 Provider 성공 응답의 StopBell 수신 시각이고, `eventDetectedAt`은 Evaluation에서 candidate를 처음 선택한 실제 서버 시각, `createdAt`은 durable Event row 생성 시각이다. 어느 것도 물리적인 버스 도착 시각이나 DB commit 완료 시각을 뜻하지 않는다. 같은 candidate의 DB retry에서는 observed/detected 시각을 유지한다. optional `providerDataTime`은 별도 upstream 시각이며 없는 값을 추정해 채우지 않는다. TASK-709 freshness는 observedAt을 기준으로 하며 [시각 컬럼](database.md#notificationevent-freshness-time-task-709)과 [TTL 결정 근거](adr/ADR-010-notification-device-and-durable-delivery.md#freshness와-local-expiry)를 따른다.

------------------------------------------------------------------------

# NotificationDelivery

## Purpose

하나의 NotificationEvent를 한 Device에 전달하는 현재 operational state를 표현한다.

## Relationship

    NotificationEvent 1 : N NotificationDelivery
    Device 1 : N NotificationDelivery

각 `NotificationEvent × Device` 조합은 Database 기준 하나다. Provider request는 bounded retry로 여러 번 발생할 수 있지만 모든 attempt를 append-only row로 영속할 필요는 없다.

Event와 recipient Device identity는 필수·불변이며 [Delivery UNIQUE](database.md#notification_deliveries-identity)를 따른다. 최초 decision transaction에서 선정한 recipient set만 Delivery를 가지며, 나중 Device 등록이나 동일 Event 재제출로 set을 확대하지 않는다. 0개 recipient는 Event만 존재하는 정상 결정으로 Provider failure나 성공 Delivery가 아니다. Alarm 삭제 후 기록/Delivery 처리의 후속 제약은 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#alarm-삭제에-대한-후속-결정-제약)을 따른다.

Delivery recipient는 Device identity다. Event 시점의 `recipientOwnershipGeneration`도 불변으로 보존한다. Worker는 current owner=Event owner, current generation=recipient generation, enabled 및 non-null current FID를 전송 직전에 확인한다. 같은 owner/generation의 FID rotation·높은 revision 재등록은 current FID로 전송할 수 있으나 A→B→A를 포함한 다른 ownership 세대에는 과거 Delivery를 보내지 않는다. attempt의 owner/generation/revision/FID snapshot과 현재 등록이 모두 같을 때만 invalid-target cleanup을 허용한다. raw FID와 owner/generation의 attempt snapshot은 메모리 값이며 raw target을 Delivery에 복제하지 않는다. [Operational Schema](database.md#notificationdelivery-operational-schema-task-709)는 확정된 후속 계약이고 TASK-707에서 매핑했으며 실제 attempt/result/retry 처리는 후속 구현이다.

## Delivery Result Semantics

Delivery lifecycle status와 Provider result는 서로 다른 enum이다. `RETRYING`을 추가하지 않는다.

| Lifecycle | 의미 / invariant |
| --- | --- |
| `PENDING` | 최초 미전송 또는 bounded retry/recovery 대기. `nextAttemptAt`은 필수이며 시각이 도래해도 freshness·budget·recipient 검증을 통과해야 호출 가능 |
| `ACCEPTED` | FCM이 요청을 접수한 terminal 상태. `providerAcceptedAt` 필수, 실제 iPhone 표시 성공을 뜻하지 않음 |
| `FAILED` | 더 이상 진행할 수 없는 Provider/local 실패 또는 attempt budget 소진. terminal이며 자동 재시도 없음 |
| `EXPIRED` | `now >= expiresAt`인 접수 확인이 없는 Delivery의 local freshness 종료. terminal이며 Provider 오류나 이미 접수된 전송 취소를 뜻하지 않음 |

최초 유효 Delivery는 `PENDING`, `attemptCount=0`이고 attempt/result/accepted 값이 없다. Event 생성 때 이미 만료됐다면 동일 recipient row를 `EXPIRED`, count=0으로 생성한다. 모든 terminal 상태는 `nextAttemptAt=null`이며 restart·Device 재등록·Event 재제출로 PENDING으로 되돌리지 않는다. Retry는 동일 row만 갱신하고 한 Device의 실패/만료가 다른 Delivery나 Alarm lifecycle을 바꾸지 않는다.

Provider result는 다음 여섯 가지다.

```text
ACCEPTED
INVALID_TARGET
RETRYABLE
CONFIGURATION
PERMANENT_REQUEST
AMBIGUOUS_TIMEOUT
```

`lastProviderResult`는 마지막으로 시작한 attempt에서 확정·정규화한 결과이며 아직 결과가 없으면 null이다. local 종료만으로 Provider result를 만들지 않는다. `AMBIGUOUS_TIMEOUT`은 접수 여부가 불명확하여 retry가 duplicate 표시를 만들 수 있다. count는 최초 호출을 포함한 durable attempt 시작 횟수로 호출 전 commit하므로 crash 때 실제 호출보다 크게 셀 수 있다. expiry는 identity가 아니라 불변 전달 기한이며 네 Event Type 모두 원래 Observation의 `observedAt`을 기준으로 계산한다. 최종 횟수·간격·Type별 TTL은 smoke/latency 검증까지 보류한다. [ADR-010 결정표와 안전성 계약](adr/ADR-010-notification-device-and-durable-delivery.md#task-709-failureretryexpiry-설계-2026-10-09)을 따른다.

TASK-707의 NotificationDelivery 생성자는 전달받은 expiresAt/createdAt으로 PENDING 또는 이미 만료된 EXPIRED(count=0, FRESHNESS_EXPIRED)를 만든다. TTL 수치는 정하지 않으며 expiresAt은 observedAt보다 뒤이고 createdAt은 Event createdAt보다 이르지 않아야 한다. `terminateDispatch(terminatedAt)`는 PENDING만 FAILED/DISPATCH_NOT_ALLOWED로 종료하고 nextAttemptAt을 지우며 실제 attempt/result는 보존한다. terminal은 무변경이며 시각 역행은 거부한다.

Alarm hard delete는 NotificationEvent/Delivery와 원본 alarmId/owner를 보존하고 PENDING만 명시적으로 종료한다. ACCEPTED/FAILED/EXPIRED는 보존한다. 진행/접수 중 FCM 회수는 보장하지 않는다. Event의 User, Delivery의 Event/Device는 non-cascade FK이며 참조 중 hard delete를 제한한다. 계정 삭제 정책/기능은 후속 release readiness에 남긴다. 기존 NotificationHistory 코드는 제거했고 legacy 데이터의 조건부 보존은 [database.md](database.md#notification_history-legacy-보존-정책)가 소유한다. Phase 8 Analytics와 operational delivery data는 별도 책임으로 유지한다.

------------------------------------------------------------------------

# Domain Relationship Overview

                     User

              ┌──────┼──────┐

           Alarm  RefreshToken  Device

             |                    |

      NotificationEvent ──< NotificationDelivery


    Transit API

          |

    BusRoute / BusStop



    Transit API

          |

    TransitObservation

          |

    Alarm Evaluation

          |

    TransitEvent candidate

------------------------------------------------------------------------
