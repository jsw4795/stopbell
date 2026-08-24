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
    │   ├── controller/
    │   ├── service/
    │   ├── repository/
    │   ├── entity/
    │   └── dto/
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

- Spring configuration
- 공통 예외 및 Error response 처리
- 공통 Web 설정

`common`은 Domain 규칙, Transit provider 구현, 또는 임의의 utility를 모으는 장소가 아니다.

### user

사용자 식별 및 인증이 도입된 이후의 사용자 관련 책임을 둔다.

- `controller`: User API 요청과 응답
- `service`: User Domain의 application logic
- `repository`: User Entity의 JPA Repository
- `entity`: User JPA Entity
- `dto`: User API request/response DTO

Authentication provider와 token/session 전략은 Undecided이다.

### alarm

Alarm 설정, 상태 전이, 활성화/비활성화 책임을 둔다.

- `controller`: Alarm 생성, 조회, 활성화, 비활성화, 삭제 API
- `service`: Alarm lifecycle과 Alarm Domain 규칙
- `repository`: Alarm Entity의 JPA Repository
- `entity`: Alarm JPA Entity
- `dto`: Alarm API request/response DTO

Alarm Evaluation은 scheduler에 묻지 않는다. Transit 관측값을 받아 Alarm 조건을 판단하는 Domain/비즈니스 로직은 `alarm`의 책임으로 둔다.

### transit

외부 Transit provider 통신, provider별 응답 정규화, Transit Query 책임을 둔다.

- `client`: 외부 Transit API client
- `service`: Transit 데이터 조회와 정규화
- `mapper`: MyBatis Mapper 및 SQL
- `dto`: provider 응답 및 내부 Transit DTO
- `domain`: `TransitEvent` 등 Transit 관련 Domain Model

동일한 Bus Route / Bus Stop을 감시하는 Alarm 그룹 조회, Transit 상태 조회, 복잡한 Transit 검색은 MyBatis를 사용한다.

선택할 Transit provider, grouping key, Transit metadata의 영속화 여부는 Undecided이다.

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
```

JPA는 `User`, `Alarm`, `NotificationHistory`의 단순 CRUD와 Entity 상태 관리에 사용한다.

### MyBatis Mapper

MyBatis Mapper는 SQL 책임을 가진 Domain 안에 둔다.

```text
transit/mapper/TransitQueryMapper
transit/mapper/AlarmGroupQueryMapper
transit/mapper/StatisticsQueryMapper
```

MyBatis는 Transit 관련 Query, Complex Query, Statistics Query, 성능 최적화가 필요한 조회에 사용한다. Mapper 이름과 SQL file 위치는 구현 시 Spring/MyBatis 설정에 맞추되, Domain 경계를 넘는 범용 Mapper를 만들지 않는다.

## DTO Location

DTO는 사용하는 Domain Package 안에 둔다.

```text
user/dto/
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

- 인증 전략이 결정되면 `user` Package에 identity 또는 authentication 관련 구조가 필요할 수 있다.
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

### core

앱 전체에서 사용하는 설정, API client, routing, error handling 등 실제 공통 기반 기능을 둔다.

### features

사용자 기능을 기준으로 화면, 상태, API 연동 코드를 둔다.

- `auth`: 인증이 도입될 경우의 로그인 및 사용자 상태
- `alarm`: Alarm 생성, 조회, 활성화/비활성화 화면
- `transit`: Bus Route 검색과 Bus Stop 선택 화면
- `notification`: Push permission, Push token 처리, 알림 진입 흐름

### shared

여러 Feature에서 재사용되는 UI component, presentation model, utility를 둔다. `shared` 역시 Feature 고유 규칙을 모으는 장소가 아니다.

### main.dart

애플리케이션 진입점과 최소한의 bootstrap 책임을 둔다.

## Future Consideration

- Flutter state management 방식은 Undecided이다.
- routing, dependency injection, API client library 선택은 구현 전에 필요성과 트레이드오프를 검토한다.
- Transit monitoring 비즈니스 로직은 특별한 이유 없이 Flutter Application으로 옮기지 않는다.
