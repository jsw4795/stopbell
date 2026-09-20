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
    ├── transit
    └── notification
```

`user`, `alarm`, `transit`, `notification`은 `architecture.md`에 정의한 논리적 Backend 경계와 일치한다. 이 경계는 별도 서비스가 아니라 하나의 Spring Boot application 내부의 Package 경계이다.

## Suggested Structure

```text
backend/
└── src/main/java/com/stopbell/
    ├── common/
    │   ├── config/
    │   ├── exception/
    │   └── web/
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
    ├── transit/
    │   ├── client/
    │   ├── entity/
    │   ├── repository/
    │   ├── service/
    │   ├── mapper/
    │   ├── dto/
    │   └── domain/
    │
    └── notification/
        ├── service/
        ├── client/
        ├── repository/
        ├── entity/
        └── dto/
```

이 구조는 초기 방향이다. 실제 구현에서 Package가 비어 있거나 책임이 없다면 미리 만들지 않는다.

## Package Responsibilities

### common

여러 Domain에서 실제로 공유해야 하는 횡단 관심사를 둔다.

예:

- Application-wide Spring configuration
- 공통 예외 및 Error response 처리
- 공통 Web 설정

`common`은 Domain 규칙, Transit provider 구현, 또는 임의의 utility를 모으는 장소가 아니다.

`SecurityConfiguration`은 모든 Application Request의 Security Policy를 담당하므로 `common.config`에 둔다. JWT 및 Google Identity 검증처럼 Authentication에만 종속된 Configuration은 `user.auth.config`에 둔다.

### user

사용자 식별 및 인증이 도입된 이후의 사용자 관련 책임을 둔다.

- `repository`: User Entity의 JPA Repository
- `entity`: User JPA Entity
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

### transit

외부 Transit provider 통신, provider별 응답 정규화, Transit Query 책임을 둔다.

- `client`: 외부 Transit API client
- `service`: Transit 데이터 조회·정규화와 source-neutral metadata reconciliation
- `entity`: `BusRoute`, `BusStop`, `BusRouteStopOccurrence` JPA Entity
- `repository`: Transit metadata JPA Repository
- `mapper`: MyBatis Mapper 및 SQL
- `dto`: provider 응답 및 내부 Transit DTO
- `domain`: `TransitObservation`, `TransitEvent` 등 Transit 관련 Domain Model

Bus static metadata는 서울 T Data CSV full import와 경기 TAGO throttled full sync의 normalized route snapshot을 JPA로 reconciliation한다. TASK-513 source adapter는 서울 노선마스터·정류장마스터·노선-정류장마스터 CSV와 경기 TAGO city별 Route/Route Stop pagination을 읽어 validation 뒤 snapshot을 만든다. source 일부 실패·불완전 pagination·parser failure·필수 source 누락은 provider-level cleanup을 허용하지 않으며 empty collection만으로 complete snapshot을 뜻하지 않는다. 동일 Route/Stop identity의 metadata는 UPDATE하고, 의미가 바뀐 occurrence는 삭제 후 새 row로 생성한다. Route/Stop 검색, Alarm grouping, Transit 상태 조회, 복잡한 검색은 SQL 제어가 실제로 필요한 경우 MyBatis를 사용한다.

V1 Provider namespace는 `TAGO`, `SEOUL_BUS`다. static metadata source는 서울 T Data CSV와 경기 TAGO sync이며, 서울 realtime은 Route 전체 roster/coarse 조회 뒤 필요 차량의 vehicle detail을 조회하는 2단계 방향을 사용한다. 구현 시 provider별 client와 response DTO를 `transit` 경계 안에서 역할에 맞게 분리할 수 있지만, generic multi-provider framework나 동적 registry를 만들지 않는다. 기본 polling key는 TAGO의 `(provider, externalRouteId, cityCode)`, 서울의 `(provider, externalRouteId)`다.

Provider별 raw DTO를 Alarm Evaluation에 직접 전달하지 않는다. `transit`은 request context와 successful response receive time을 raw field와 함께 `TransitObservation`의 공통 의미로 변환하고, Provider failure는 정상 empty 및 정상 Observation과 구분한다. `transit.domain.TransitProvider`는 `TAGO`, `SEOUL_BUS` namespace의 안정적인 공통 type이다. 차량별 tracking lifecycle과 Alarm lifecycle 전이는 `alarm`이 소유하며 scheduler는 이를 실행만 한다. metadata full sync의 single-flight, Route 단위 transaction, Stop lazy-loading N+1 확인은 ingestion 구현 책임이며 Redis, distributed lock, queue는 V1에 추가하지 않는다.

### notification

푸시 알림 요청, 결과 처리, 알림 발송 기록 책임을 둔다.

- `service`: Notification 전송 결정과 결과 처리
- `client`: FCM 등 Push provider client
- `repository`: NotificationHistory Entity의 JPA Repository
- `entity`: NotificationHistory JPA Entity
- `dto`: Push 요청/응답과 Notification DTO

Device registration API 및 Push token lifecycle은 구현 전에 별도 결정이 필요하다.

## Persistence Location

### JPA Repository

JPA Repository는 Entity를 소유한 Domain 안에 둔다.

```text
user/repository/UserRepository
alarm/repository/AlarmRepository
notification/repository/NotificationHistoryRepository
transit/repository/BusRouteRepository
transit/repository/BusStopRepository
transit/repository/BusRouteStopOccurrenceRepository
```

JPA는 `User`, `Alarm`, `BusAlarmTarget`, `NotificationHistory`, Bus metadata의 단순 CRUD와 Entity 상태 관리에 사용한다. `BusAlarmTarget`은 별도 Repository로 독립 관리하지 않고 Alarm aggregate의 cascade lifecycle을 따른다.

### MyBatis Mapper

MyBatis Mapper는 SQL 책임을 가진 Domain 안에 둔다.

```text
transit/mapper/TransitQueryMapper
transit/mapper/AlarmGroupQueryMapper
transit/mapper/StatisticsQueryMapper
```

MyBatis는 Transit 관련 Query, Complex Query, Statistics Query, 성능 최적화가 필요한 조회에서 SQL 제어의 실제 필요가 확인되면 사용한다. Mapper 이름과 SQL file 위치는 구현 시 Spring/MyBatis 설정에 맞추되, Domain 경계를 넘는 범용 Mapper를 만들지 않는다.

## DTO Location

DTO는 사용하는 Domain Package 안에 둔다.

```text
user/auth/dto/
alarm/dto/
transit/dto/
notification/dto/
```

외부 Transit provider의 응답 DTO는 `transit/dto`에 둔다. provider 응답 객체를 API response로 직접 노출하지 않고, StopBell API의 안정적인 DTO로 변환한다.

## Domain Layer and Infrastructure Layer

Domain Layer는 Alarm 조건 평가, 상태 전이, Notification 전송 결정처럼 제품 규칙을 표현한다.

Infrastructure Layer는 JPA Repository, MyBatis Mapper, 외부 Transit API client, FCM client처럼 Database 또는 외부 시스템과 통신한다.

이 구분은 외부 provider와 Persistence 세부사항이 Alarm 규칙에 직접 섞이지 않도록 하기 위함이다. 다만 현재 V1에 불필요한 추상화 계층을 추가하지 않는다.

## Future Consideration

- 여러 Transit provider를 지원하게 되면 provider별 client/DTO Package 분리를 검토할 수 있다.
- Notification fan-out 또는 통계 기능이 실제 병목이 되면 별도 Query Package 또는 module을 검토할 수 있다.
- Package 구조 변경이 Architecture Decision에 영향을 준다면 ADR로 기록한다.

------------------------------------------------------------------------

# Flutter Application

## Suggested Structure

```text
app/
└── lib/
    ├── core/
    ├── features/
    │   ├── auth/
    │   ├── alarm/
    │   ├── transit/
    │   └── notification/
    ├── shared/
    └── main.dart
```

Flutter 구조는 현재 확정된 Architecture가 아니다. V1의 얇은 client 원칙을 지키며 확장 가능한 방향으로만 제안한다.

V1은 필요한 수준의 Auth API, Transit API, Alarm API, Auth Session 관리, screen-local state로 시작한다. state management library는 이번 범위에서 특정 제품으로 확정하지 않는다. generic `Repository`/`BaseRepository`, 모든 API별 `UseCase` class, global event bus, 복잡한 refresh queue framework, offline mutation queue, notification stub은 미리 만들지 않는다.

### core

앱 전체에서 사용하는 설정, API client, routing, error handling 등 실제 공통 기반 기능을 둔다. authenticated API client는 Auth Session이 제공한 현재 Access Token을 사용하고 refresh lifecycle은 Auth Session에 위임한다.

### features

사용자 기능을 기준으로 화면, 상태, API 연동 코드를 둔다.

- `auth`: Google Login, Token Pair Secure Storage, startup 상태 복구, refresh 및 logout을 포함한 Auth Session
- `alarm`: `INACTIVE`/`ACTIVE`/`FOLLOW_UP` 상태를 보존하는 Alarm 생성·조회·활성화/비활성화 화면과 Alarm ID 기반 navigation 진입점
- `transit`: Bus Route 검색과 Bus Stop 선택 화면. Stop response의 `canNotifyOneStopBefore`/`canNotifyOneStopAfter`로 option을 제어하며 stale occurrence 생성 `404`에서는 Stop 재선택 흐름으로 복구
- `notification`: Phase 7에서 Push permission, Push token 처리, 알림 진입 흐름을 구현한다. Phase 6에서는 notification stub을 만들지 않는다.

### shared

여러 Feature에서 재사용되는 UI component, presentation model, utility를 둔다. `shared` 역시 Feature 고유 규칙을 모으는 장소가 아니다.

### main.dart

애플리케이션 진입점과 최소한의 bootstrap 책임을 둔다.

## Future Consideration

- Flutter state management 방식은 Undecided이다.
- routing, dependency injection, API client library 선택은 구현 전에 필요성과 트레이드오프를 검토한다.
- Transit monitoring 비즈니스 로직은 특별한 이유 없이 Flutter Application으로 옮기지 않는다.
- Phase 6은 Phase 7에 안정적인 Auth Session state, 자동 refresh를 포함한 authenticated API client, logout lifecycle/hook, Alarm ID navigation 진입점, app resume 시 Auth Session 재평가 지점을 제공한다. Device/FCM/logout registration 정책은 TASK-701/702에서 결정하며 Phase 6에서 선행 구현하지 않는다.
