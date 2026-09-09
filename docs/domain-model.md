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
     |
     └── RefreshToken


    Transit API

     |
     ├── BusRoute
     |
     └── BusStop

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

`createdAt`, `updatedAt`은 Java에서 `LocalDateTime`, Database에서 `DATETIME(6)`으로 관리한다. 두 컬럼은 `NOT NULL`을 기본 정책으로 한다.

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

# Alarm

## Purpose

사용자가 원하는 알림 조건의 공통 정보를 표현하는 핵심 Domain이다.

예:

"143번 버스가 서울역 정류장에 도착하면 알려줘"

라는 사용자의 요청 하나가 하나의 Alarm이다.

## Responsibilities

-   Alarm 공통 정보 관리
-   활성 상태 관리

## Main Attributes

    id

    user

    transitType

    active

    createdAt

    updatedAt

## Active Status

`active`는 Alarm이 현재 감시 대상인지만 표현한다. 새 Alarm은 비활성 상태(`false`)로 생성한다.

Transit API 조회 실패, Notification 발송 결과, Alarm trigger는 Alarm의 상태가 아니다. 이 정보는 필요 시 `NotificationHistory`, Application Log 또는 별도 이력으로 분리한다.

`transitType`은 `BUS`, `SUBWAY`를 표현하는 Enum으로 관리하며, Database에는 문자열로 저장한다.

V1 Bus Alarm의 Transit Target 계약은 아래에서 정의하지만 실제 Entity와 Database Schema는 TASK-401에서 결정한다.

## Bus Alarm Transit Target Contract

사용자는 Bus Route와 Target Stop을 직접 선택한다. First Stop은 선택 가능한 일반 Target occurrence의 하나이며 모든 Alarm의 고정 Target이 아니다.

개념적 최소 계약:

```text
external references
    provider
    externalRouteId
    externalStopId

target occurrence operational snapshot
    targetStopOrder
    traversalContext (ambiguity 해소에 필요한 경우)
    directionContext (ambiguity 해소에 필요한 경우)
    targetStopLatitude (optional)
    targetStopLongitude (optional)
    providerRequestContext (provider별 optional; TAGO cityCode 포함)

display snapshot
    routeNumber
    stopName

notification options
    notifyOneStopBefore
    notifyOneStopAfter
```

Route external identity는 `(provider, externalRouteId)`, Stop external identity는 `(provider, externalStopId)`이며 둘 다 ADR-006에서 정한 opaque external reference다. Vehicle identifier는 Alarm Target에 포함하지 않는다.

사용자가 선택하는 Alarm Target은 Stop identity 자체가 아니라 Route traversal 안의 특정 Stop occurrence다. Route/Stop reference에 `targetStopOrder`와 필요한 traversal/direction context를 함께 사용해 그 occurrence를 평가한다. 같은 Route의 sequence에 같은 Stop ID가 여러 번 나타날 수 있으므로 `(provider, externalRouteId, externalStopId)`만으로 Target occurrence의 uniqueness가 보장되지 않는다. TASK-401은 이 세 값만을 근거로 `UNIQUE(provider, externalRouteId, externalStopId)` 같은 제약을 만들지 않고 실제 Schema와 불변 조건을 별도로 결정한다.

`targetStopOrder`는 선택한 Route traversal에서 target occurrence를 연결하고 이전/다음 Stop을 판단하기 위한 필수 operational metadata다. Stop identity가 아니며 Provider metadata 변경 뒤 stale할 수 있다. 순환·재방문·분기·회차로 같은 Stop ID가 여러 번 등장하면 order와 필요한 traversal/direction context로 사용자가 선택한 occurrence를 구분해야 한다.

Target Stop 좌표는 Provider가 metadata로 제공할 때 저장 후보가 되는 optional operational snapshot이며, 특히 경기 first-stop에서 Stop ID·order와 함께 GPS 근접 근거를 평가하는 데 사용한다. 정확한 저장 여부와 GPS distance threshold는 TASK-401/509에서 결정한다.

`routeNumber`와 `stopName`은 검색·표시 및 Notification 위치 안내를 위한 snapshot이지 identity가 아니다. TAGO `cityCode`는 API request를 재현하기 위한 필수 provider request context이지 identity가 아니다. 서울에는 가짜 `cityCode`를 채우지 않는다. `providerRequestContext`는 임의 속성을 쌓는 범용 JSON bag을 뜻하지 않으며 TASK-401/402에서 현재 Provider에 필요한 최소 typed 구조로 구체화한다.

두 Notification option은 독립적인 선택값이다. Route metadata가 확인한 traversal에서 predecessor가 없으면 `notifyOneStopBefore=true`, successor가 없으면 `notifyOneStopAfter=true`인 Alarm 생성 요청은 유효하지 않다. 이 검증은 Client에만 의존하지 않고 Backend 계약에서도 수행할 수 있어야 한다. 구체적인 request field와 HTTP error는 TASK-402에서 결정한다.

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

    routeNumber

    region

    provider 결과에 따른 식별자

Route identity는 `(provider, externalRouteId)`다. `routeNumber`는 display/search metadata이고, Route Stop 목록의 순서는 하나의 traversal 안에서 predecessor/successor 및 진행 방향을 해석하는 operational metadata다.

## Relationship

    BusRoute 1 : N BusStop

## Persistence

    Provider 조사 결과에 따라 결정

BusRoute는 Transit 관련 조회 Model로 사용한다. Provider API가 검색을 제공하면 이를 그대로 사용할 수 있으며, Static metadata 저장이나 검색 성능처럼 SQL 조회가 필요한 근거가 확인되면 MyBatis 사용을 검토한다.

------------------------------------------------------------------------

# BusStop

## Purpose

버스 정류장 정보를 표현한다.

예:

    서울역
    강남역
    잠실역

## Main Attributes

    name

    latitude

    longitude

    provider 결과에 따른 식별자

Stop identity는 `(provider, externalStopId)`다. Route 안의 Stop order, name, 좌표는 operational/display metadata이며 identity가 아니다.

## Relationship

실제로 하나의 정류장은 여러 노선에 포함될 수 있다.

따라서 Database에서는 중간 테이블이 필요할 가능성이 있다.

예:

    bus_routes

    bus_stops

    route_stops

## Persistence

    Provider 조사 결과에 따라 결정

BusStop은 Transit 관련 조회 Model로 사용한다. Provider API가 Route별 Stop 조회를 제공하면 이를 사용할 수 있으며, Local metadata 또는 SQL 조회가 필요한 근거가 확인되면 MyBatis 사용을 검토한다.

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

`observedAt`은 StopBell이 응답을 받은 시각이고 항상 존재한다. `providerDataTime`은 Provider가 제공한 원본 data 시각이며 optional이다. 둘을 구분해야 반복·stale data를 판단할 수 있다. Provider가 data 시각을 주지 않으면 현재 수신 시각만으로 upstream freshness가 보장된다고 가정하지 않는다.

`directionContext`와 `sectionContext`는 Provider가 제공할 때 같은 Route traversal에서 Stop order를 비교할 수 있는지 판단하는 operational evidence다. Provider raw field 이름을 Domain contract로 노출하지 않고, 없는 방향·회차 정보를 추측하지 않는다. Stop order가 역행하거나 순환·회차·분기로 traversal이 모호하면 위치 관계는 `UNKNOWN`이다.

현재 Observation에 `previousStopId`/`previousStopOrder`를 중복 저장하지 않는다. Evaluation은 같은 Alarm·Route·Vehicle tracking cycle의 이전 `TransitObservation`과 현재 값을 비교한다.

## Provider Mapping

| 공통 의미 | 경기 TAGO | 서울 버스위치 |
| --- | --- | --- |
| Provider / Route | `TAGO` + 요청 `routeId` | `SEOUL_BUS` + 요청 `busRouteId` 또는 응답 `routeId` |
| Vehicle tracking | `vehicleno` | `vehId`; `plainNo`는 보조값 |
| Current Stop | `nodeId`, `nodeOrd` | `stId`, `stOrd`; `sectOrd`/`sectionId`는 section context |
| GPS | `gpslati`, `gpslong` | WGS84 `tmY`/`tmX` 또는 operation별 WGS84 좌표 |
| Provider data time | 제공되지 않으면 없음 | `dataTm` |
| Direct arrival evidence | `UNAVAILABLE` | `stopFlag=1 → ARRIVED`, `stopFlag=0 → MOVING`, 누락/해석 불가 → `UNAVAILABLE` |

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

Timeout, HTTP error, Provider error는 정상 응답 안의 ambiguity와 구분되는 Provider failure다. 실패 자체는 `TransitObservation`이 아니지만 Alarm Evaluation에는 Event 없는 UNKNOWN으로 전달되어 Alarm 상태를 손상하지 않는다.

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

ARRIVED 뒤 after 옵션이 꺼져 있으면 Alarm 비활성화와 함께 모든 차량 tracking을 끝낸다. 옵션이 켜져 있으면 Alarm은 비활성화하고 다른 차량 tracking을 끝내며, 성공 차량만 ONE_STOP_AFTER까지 short follow-up 한다. follow-up tracking은 Alarm `active`와 다른 runtime 의미다. 별도 persisted state 필요성과 timeout은 TASK-401/509에서 결정한다.

short follow-up이 진행 중인 동일 Alarm을 사용자가 다시 활성화하면 이전 activation cycle의 follow-up을 취소하고 새 baseline으로 새 monitoring cycle을 시작한다. Alarm 삭제 시에는 active monitoring과 해당 Alarm의 short follow-up을 모두 종료한다. 취소 상태의 runtime/persistence 표현은 TASK-401/509/510에서 결정한다.

동일 Alarm + 동일 Vehicle + 동일 Event Type은 같은 tracking cycle에서 한 번만 의미가 있다. 구체적인 persistence와 concurrency 기반 duplicate prevention은 TASK-708의 범위다.

## Persistence

`TransitObservation`, `TransitEvent`와 Vehicle tracking state의 실제 영속 여부는 아직 결정하지 않는다. Schema, Java DTO/record, scheduler, GPS/freshness 수치와 평가 구현은 후속 Task 범위다.

------------------------------------------------------------------------

# NotificationHistory

## Purpose

알림 발송 기록.

## Responsibilities

-   어떤 Alarm인지
-   언제 발송했는지
-   발송 결과가 무엇인지

기록한다.

## Main Attributes

    id

    alarm

    status

    failureReason

    createdAt

`status`는 `SUCCESS`, `FAILURE`만 가지는 Enum으로 표현하고 Database에는 문자열로 저장한다.

`failureReason`은 실패 시 한 줄 수준의 간단한 원인을 기록할 수 있으며 `null`을 허용한다. Provider별 응답 구조나 FCM message ID는 현재 저장하지 않는다.

`createdAt`은 발송 결과 History가 생성된 시각이다. History는 현재 생성 후 일반적으로 수정하지 않는 방향이므로 `updatedAt`은 두지 않는다.

## Relationship

    Alarm 1 : N NotificationHistory

NotificationHistory가 `Alarm`을 참조하는 단방향 관계를 사용한다. Alarm Entity에는 NotificationHistory collection을 추가하지 않는다.

## Persistence

    JPA

NotificationHistory는 알림 발송 기록의 저장과 상태 관리를 위해 JPA Repository 기반으로 관리한다.

------------------------------------------------------------------------

# Domain Relationship Overview

                     User

                 ┌────┴────┐

               Alarm   RefreshToken


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
