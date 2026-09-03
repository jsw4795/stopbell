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

Docker Compose configuration은 저장소 루트의 `docker-compose.yml`에서 관리한다.

## 3. Database Schema Migration

Database Schema 변경은 Flyway Migration으로 관리한다.

Hibernate `ddl-auto`를 통한 자동 Schema 변경은 사용하지 않는다. Entity 변경만으로 Database Schema를 변경하지 않으며, Schema 변경 시에는 Migration 파일을 반드시 추가한다.

Migration 파일은 `backend/src/main/resources/db/migration/`에 `V{version}__{description}.sql` 형식으로 둔다. Flyway는 애플리케이션과 MySQL Testcontainer 통합 테스트에서 이 Migration을 적용한다.

## 4. 모델링 원칙

- 명확한 관계형 제약 조건을 우선한다.
- 데이터베이스가 안전하게 강제할 수 있는 불변 조건에는 데이터베이스 제약을 사용한다.
- 캐시/영속화할 측정된 이유가 없다면 대량의 외부 교통 마스터 데이터를 중복 저장하지 않는다.
- 제공자 식별자는 제공자별 네임스페이스가 없으면 전역적으로 유일하다고 가정하지 않는다.
- 타임스탬프는 일관되게 저장한다.

## 5. Persistence Strategy

JPA는 단순한 Domain CRUD와 Entity 상태 관리에 사용한다. `users`, `refresh_tokens`, `devices`, `alarms`, `notification_history`는 Repository 기반으로 관리한다.

MyBatis는 Transit 관련 Query, 복잡한 검색, 집계 Query, 성능 최적화가 필요한 조회에 사용한다. 동일한 Bus Route / Bus Stop을 감시하는 Alarm 그룹 조회와 Transit 상태 조회가 대상 예시이다.

JPA Entity와 MyBatis Query Model은 각 책임에 맞게 분리한다. 복잡한 조회를 위해 Domain Entity의 상태 관리 책임을 MyBatis로 옮기지 않는다.

## 6. 핵심 테이블

`users`, `alarms`, `notification_history`의 현재 Schema는 아래 정의와 Flyway Migration으로 관리한다. Authentication Task에서 추가할 `refresh_tokens`와 `devices`는 별도 Migration으로 적용한다.

### users

내부 사용자 식별과 Alarm 소유자 기준 테이블이다.

Authentication 구현 후 Schema:

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY
provider VARCHAR(20) NOT NULL
provider_user_id VARCHAR(255) NOT NULL
created_at DATETIME(6) NOT NULL
updated_at DATETIME(6) NOT NULL
UNIQUE(provider, provider_user_id)
```

`provider`는 외부 Social Provider를, `provider_user_id`는 해당 Provider의 사용자 식별자를 저장한다. 최초 Google Login에서는 OpenID Connect `sub`를 `provider_user_id`로 사용한다. 같은 `provider`와 `provider_user_id` 조합은 반드시 하나의 User만 식별하도록 Unique Constraint를 둔다.

외부 Identity는 `users`에 직접 저장한다. `auth_identities` 같은 별도 identity 테이블, `email`, `display_name`은 현재 추가하지 않는다.

현재 구현에는 이 변경이 아직 적용되지 않았다. `TASK-201`에서 User Entity 변경과 해당 Flyway Migration을 함께 추가한다. Hibernate `ddl-auto`로 Schema를 자동 생성하지 않는다.

### refresh_tokens

Authentication Session을 User와 분리해 저장하는 테이블이다.

Authentication 구현 후 Schema:

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY
user_id BIGINT NOT NULL REFERENCES users(id)
token_hash CHAR(64) NOT NULL
expires_at DATETIME(6) NOT NULL
created_at DATETIME(6) NOT NULL
```

`token_hash`에는 서버가 발급한 opaque Refresh Token 원문의 SHA-256 Hash를 저장한다. Refresh Token 원문은 저장하지 않으며, Password용 BCryptPasswordEncoder를 Refresh Token Hash에 사용하지 않는다.

한 User가 여러 Refresh Token을 가질 수 있어 여러 Login Session을 허용한다. Rotation 또는 Logout으로 Token을 무효화할 때는 해당 행을 삭제한다. `updated_at`, `revoked_at`, `device_id`, `last_used_at`, `token_family`는 현재 추가하지 않는다.

현재 구현에는 이 테이블이 아직 없다. `TASK-202`에서 Entity, Repository, Flyway Migration을 함께 추가한다.

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

### alarms

사용자가 설정한 Alarm의 공통 정보를 저장한다. Transit provider와 식별자 체계가 확정되기 전에는 Bus/Subway별 상세 대상 정보는 저장하지 않는다.

후보 필드:

```text
id
user_id
transit_type
active
created_at
updated_at
```

`transit_type`은 `BUS`, `SUBWAY` 문자열로 저장한다. `active`는 Alarm이 현재 감시 대상인지 여부만 표현하며, 새 Alarm의 기본값은 `false`이다.

Transit API 조회 실패, Notification 발송 결과, Alarm trigger는 Alarm의 상태로 저장하지 않는다. 이력과 결과는 필요 시 `notification_history` 또는 별도 logging으로 분리한다.

현재 확정 Schema:

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY
user_id BIGINT NOT NULL REFERENCES users(id)
transit_type VARCHAR(20) NOT NULL
active BOOLEAN NOT NULL
created_at DATETIME(6) NOT NULL
updated_at DATETIME(6) NOT NULL
```

### notification_history

특정 Alarm에서 발생한 Notification 발송 결과를 기록한다. Alarm의 활성 상태나 Transit API 조회 실패 상태를 표현하지 않는다.

현재 확정 Schema:

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY
alarm_id BIGINT NOT NULL REFERENCES alarms(id)
status VARCHAR(20) NOT NULL
failure_reason VARCHAR(255) NULL
created_at DATETIME(6) NOT NULL
```

`status`는 `SUCCESS`, `FAILURE` 문자열만 저장한다. `failure_reason`은 실패 시 간단한 원인을 기록할 수 있고 `null`을 허용한다. `created_at`은 생성 후 변경하지 않으며 `updated_at`은 추가하지 않는다.

FCM message ID, device token, provider 응답, retry count, 전송 단계별 timestamp는 실제 Notification 전송 흐름이 확정될 때 필요성을 검토한다. NotificationHistory 저장 시점, retry 및 duplicate prevention 전략도 현재 결정하지 않는다.

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
