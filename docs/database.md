# 데이터베이스 설계

## 1. 목표

신뢰할 수 있는 알림 처리를 위한 충분한 상태를 보존하면서 V1 스키마를 최소화한다.

데이터베이스: MySQL

데이터 접근: JPA + MyBatis

## 2. Development Database Environment

Development Database는 MySQL 8.4 LTS를 사용한다.

- Local execution: Docker Compose
- Data persistence: Docker Named Volume

Docker는 Database 실행 환경으로 사용한다. Database 데이터는 Docker Named Volume에 저장하며, Container lifecycle과 Database lifecycle은 분리한다.

Container를 삭제하거나 재생성해도 Docker Named Volume이 유지되는 한 Database 데이터는 유지되어야 한다. Volume 삭제는 명시적인 데이터 삭제 작업으로 취급한다.

Docker Compose configuration file은 별도 Task에서 생성한다.

## 3. Database Schema Migration

Database Schema 변경은 Flyway Migration으로 관리한다.

Hibernate `ddl-auto`를 통한 자동 Schema 변경은 사용하지 않는다. Entity 변경만으로 Database Schema를 변경하지 않으며, Schema 변경 시에는 Migration 파일을 반드시 추가한다.

Migration 파일의 경로, 명명 규칙, 실행 설정은 구현 전에 별도로 정의한다.

## 4. 모델링 원칙

- 명확한 관계형 제약 조건을 우선한다.
- 데이터베이스가 안전하게 강제할 수 있는 불변 조건에는 데이터베이스 제약을 사용한다.
- 캐시/영속화할 측정된 이유가 없다면 대량의 외부 교통 마스터 데이터를 중복 저장하지 않는다.
- 제공자 식별자는 제공자별 네임스페이스가 없으면 전역적으로 유일하다고 가정하지 않는다.
- 타임스탬프는 일관되게 저장한다.

## 5. Persistence Strategy

JPA는 단순한 Domain CRUD와 Entity 상태 관리에 사용한다. `users`, `devices`, `alerts`, `notification_history`는 Repository 기반으로 관리한다.

MyBatis는 Transit 관련 Query, 복잡한 검색, 집계 Query, 성능 최적화가 필요한 조회에 사용한다. 동일한 Bus Route / Bus Stop을 감시하는 Alarm 그룹 조회와 Transit 상태 조회가 대상 예시이다.

JPA Entity와 MyBatis Query Model은 각 책임에 맞게 분리한다. 복잡한 조회를 위해 Domain Entity의 상태 관리 책임을 MyBatis로 옮기지 않는다.

## 6. 후보 핵심 테이블

다음 테이블은 개념적 설계이며 아직 확정되지 않았다.

### users

내부 사용자 식별과 Alarm 소유자 기준을 위한 최소 테이블이다.

후보 필드:

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY
created_at DATETIME(6) NOT NULL
updated_at DATETIME(6) NOT NULL
```

현재 `users` 테이블에는 `email`, `provider`, `provider_user_id`, `display_name`을 추가하지 않는다. PK 외에 Authentication 관련 unique index도 만들지 않는다.

Authentication 설계 시 `UNIQUE(provider, provider_user_id)` 제약과 provider identity를 별도 identity 테이블에 둘지 검토할 수 있다. 단, 현재 schema에는 적용하지 않는다.

실제 `users` 테이블 생성과 Schema 변경은 Flyway Migration으로 관리한다. 이번 문서 작업에서는 Migration SQL을 작성하지 않으며, Hibernate `ddl-auto`로 Schema를 자동 생성하지 않는다.

### devices

모바일 설치/기기를 사용자 및 푸시 토큰에 연결한다.

후보 필드:

```text
id
user_id
platform
push_token
active
created_at
updated_at
```

질문:

- 한 사용자가 여러 기기를 가질 수 있는가? 아마 그렇다.
- 푸시 토큰은 고유해야 하는가? 아마 그렇지만, 제공자의 생명주기를 검토해야 한다.

### alerts

사용자가 설정한 버스 알림을 저장한다.

후보 필드:

```text
id
user_id
provider
route_id
stop_id
status
triggered_at
created_at
updated_at
```

가능한 상태:

```text
ACTIVE
TRIGGERED
INACTIVE
```

상태 이름과 생명주기는 확정되지 않았다.

### notification_history

디버깅과 중복 방지에 선택적으로 유용할 수 있다.

후보 필드:

```text
id
alert_id
device_id
status
provider_message_id
requested_at
completed_at
error_code
```

이 테이블을 V1에 포함할지는 구현 복잡도와 관측성 요구를 바탕으로 결정한다.

## 7. 외부 교통 데이터

`bus_routes`, `bus_stops` 마스터 테이블을 자동으로 만들지 않는다.

먼저 다음을 확인한다.

- 제공자 API 지연 시간
- 요청 제한
- 식별자의 안정성
- 검색 기능
- 로컬 캐시가 UX 또는 API 사용량을 실질적으로 개선하는지 여부

교통 메타데이터를 영속화한다면 이유를 문서화하고 갱신/무효화 규칙을 정의한다.

## 8. 인덱싱

인덱스는 실제 쿼리 패턴을 기준으로 도입한다.

가능한 후보:

- 상태별 활성 알림 조회
- 사용자별 알림
- 사용자별 기기
- 푸시 토큰 고유성/조회

지원하는 쿼리를 특정하지 않은 추측성 인덱스는 추가하지 않는다.

## 9. 트랜잭션 고려 사항

향후 핵심 트랜잭션 질문:

어떻게 두 개의 스케줄러 실행 또는 백엔드 인스턴스가 동일한 일회성 알림을 동시에 전송하기로 결정하는 일을 막을 것인가?

가능한 방법:

- 조건부 업데이트
- 행 잠금
- 고유 이벤트 키
- 트랜잭션 상태 전이

구체적인 선택은 스케줄러/동시성 모델을 정한 뒤 결정한다.
