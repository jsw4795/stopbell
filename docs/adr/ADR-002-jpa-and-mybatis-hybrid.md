# ADR-002: JPA and MyBatis Hybrid Persistence Strategy

- 상태: 채택됨
- 날짜: 2026-08-24

## Context

StopBell은 단순 CRUD 데이터와 복잡한 Transit 관련 Query를 모두 처리해야 한다.

`User`, `Alarm`, `NotificationHistory`는 Entity 상태 관리와 일반적인 Domain CRUD가 필요하다. 반면 Transit 데이터 조회, Alarm 그룹 조회, 복잡한 검색, 집계 Query는 SQL을 명시적으로 제어할 필요가 있을 수 있다.

## Options Considered

### Option A: JPA Only

장점:

- 개발 생산성 향상
- Entity 중심 개발 가능

단점:

- 복잡한 Query 작성 및 최적화가 어려울 수 있음

### Option B: MyBatis Only

장점:

- SQL 제어 용이

단점:

- 단순 CRUD 코드 증가

### Option C: JPA + MyBatis Hybrid

장점:

- Domain CRUD는 JPA 활용
- 복잡한 Query는 MyBatis 활용
- 각 기술을 책임에 맞게 분리할 수 있음

단점:

- 두 Persistence 기술의 역할 경계를 일관되게 유지해야 함
- 구현과 테스트에서 두 접근 방식을 함께 관리해야 함

## Decision

JPA와 MyBatis를 함께 사용한다.

JPA는 `User`, `Alarm`, `NotificationHistory`처럼 단순한 Domain CRUD와 Entity 상태 관리가 필요한 영역에서 Repository 기반으로 사용한다.

MyBatis는 Transit 관련 Query, 복잡한 검색, 집계 Query, 성능 최적화가 필요한 조회에서 사용한다.

## Rationale

각 기술의 장점을 활용하고, Domain 로직과 복잡한 Query 처리를 분리하기 위함이다.

JPA는 Domain Entity의 생성, 변경, 상태 전이를 간결하게 관리하게 한다. MyBatis는 동일한 Bus Route / Bus Stop을 감시하는 Alarm 그룹 조회, Transit 상태 조회, 통계 데이터 조회처럼 SQL 제어가 중요한 Query를 명확하게 작성하고 최적화할 수 있게 한다.

## Consequences

- 단순한 Domain CRUD는 JPA Repository로 구현한다.
- 복잡한 조회와 Transit 관련 Query는 MyBatis Mapper로 구현한다.
- JPA Entity와 MyBatis Query Model은 각 책임에 맞게 분리한다.
- 단순 CRUD에 MyBatis를 사용하거나, 복잡한 Query를 위해 JPA Query를 과도하게 복잡하게 만들지 않는다.

## Revisit When

다음 중 하나 이상이 사실이 되면 이 결정을 재검토한다.

- JPA 또는 MyBatis 중 하나의 사용 범위가 실질적으로 없어짐
- Query 성능이나 유지보수 비용이 현재 역할 분담으로 해결되지 않음
- Domain 또는 Transit 데이터의 성격이 크게 변경됨
