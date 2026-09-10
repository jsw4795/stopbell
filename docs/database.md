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

JPA는 단순한 Domain CRUD와 Entity 상태 관리에 사용한다. `users`, `refresh_tokens`, `devices`, `alarms`, `bus_alarm_targets`, `notification_history`는 Repository 기반으로 관리한다. `BusAlarmTarget`은 Alarm aggregate를 통해 persist/remove한다.

MyBatis는 Transit 관련 Query, 복잡한 검색, 집계 Query, 성능 최적화가 필요한 조회에 사용할 수 있다. Provider API가 Route 검색과 Route별 Stop 조회를 제공하고 Local DB 저장의 명확한 이유가 없으면 Transit 검색을 위해 MyBatis를 도입하지 않는다. Static metadata 저장, 검색 성능, rate limit 절감, grouping query 등 실제 필요가 확인되면 적용을 결정한다.

JPA Entity와 MyBatis Query Model은 각 책임에 맞게 분리한다. 복잡한 조회를 위해 Domain Entity의 상태 관리 책임을 MyBatis로 옮기지 않는다.

## 6. 핵심 테이블

`users`, `refresh_tokens`, `alarms`, `bus_alarm_targets`, `notification_history`의 현재 Schema는 아래 정의와 Flyway Migration으로 관리한다. `devices`는 Push 연동이 확정될 때 별도 Migration으로 추가한다.

### users

내부 사용자 식별과 Alarm 소유자 기준 테이블이다.

현재 Schema:

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY
auth_provider VARCHAR(20) NOT NULL
provider_user_id VARCHAR(255) NOT NULL
created_at DATETIME(6) NOT NULL
updated_at DATETIME(6) NOT NULL
UNIQUE(auth_provider, provider_user_id)
```

`auth_provider`는 외부 Social Provider를, `provider_user_id`는 해당 Provider의 사용자 식별자를 저장한다. `auth_provider`는 `GOOGLE`, `APPLE`, `KAKAO`, `NAVER` 문자열 중 하나를 저장한다. 최초 Google Login에서는 OpenID Connect `sub`를 `provider_user_id`로 사용한다. 같은 `auth_provider`와 `provider_user_id` 조합은 반드시 하나의 User만 식별하도록 Unique Constraint를 둔다.

외부 Identity는 `users`에 직접 저장한다. `auth_identities` 같은 별도 identity 테이블, `email`, `display_name`은 현재 추가하지 않는다.

이 Schema는 `TASK-201`의 V4 Flyway Migration으로 적용한다. Hibernate `ddl-auto`로 Schema를 자동 생성하지 않는다.

### refresh_tokens

Authentication Session을 User와 분리해 저장하는 테이블이다.

현재 Schema:

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY
user_id BIGINT NOT NULL REFERENCES users(id)
token_hash CHAR(64) NOT NULL
expires_at DATETIME(6) NOT NULL
created_at DATETIME(6) NOT NULL
UNIQUE(token_hash)
```

`token_hash`에는 서버가 SecureRandom으로 생성한 256-bit URL-safe Base64 opaque Refresh Token 원문의 SHA-256 Hash를 64-char lowercase hex로 저장한다. Refresh Token 원문은 저장하지 않으며, Password용 BCryptPasswordEncoder를 Refresh Token Hash에 사용하지 않는다. 서로 다른 RefreshToken row가 같은 `token_hash`를 가지는 것은 정상 상태가 아니므로 Database Unique Constraint로 강제한다.

한 User가 여러 Refresh Token을 가질 수 있어 여러 Login Session을 허용한다. Rotation 또는 Logout으로 Token을 무효화할 때는 `token_hash`로 해당 행 하나만 삭제한다. Logout은 만료 여부를 검증하거나 User를 먼저 조회하지 않으며, 삭제 대상이 없어도 성공으로 처리한다. `updated_at`, `revoked_at`, `device_id`, `last_used_at`, `token_family`는 현재 추가하지 않는다.

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

사용자가 설정한 Alarm의 공통 정보와 lifecycle을 저장한다. `transit_type`은 `BUS`, `SUBWAY`, `status`는 `INACTIVE`, `ACTIVE`, `FOLLOW_UP` 문자열로 저장한다. 새 Alarm의 기본 상태는 `INACTIVE`다. ARRIVED는 상태가 아닌 Event이고, FOLLOW_UP은 ARRIVED 뒤 ONE_STOP_AFTER만 처리하는 짧은 lifecycle 상태다.

현재 Schema:

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY
user_id BIGINT NOT NULL REFERENCES users(id)
transit_type VARCHAR(20) NOT NULL
status VARCHAR(20) NOT NULL
follow_up_vehicle_tracking_id VARCHAR(255) NULL
follow_up_started_at DATETIME(6) NULL
follow_up_expires_at DATETIME(6) NULL
created_at DATETIME(6) NOT NULL
updated_at DATETIME(6) NOT NULL
INDEX(status)
```

`status=FOLLOW_UP`이면 follow-up runtime 세 값이 모두 존재하고 `expires_at > started_at`이어야 한다. 다른 상태이면 세 값은 모두 `NULL`이어야 한다. 이 조합은 `ck_alarms_lifecycle` CHECK로 강제한다. `follow_up_vehicle_tracking_id`는 Target identity가 아니라 ARRIVED 차량의 관측을 재시작 뒤 연결하기 위한 short-lived correlation 값이다. `status` index는 TASK-510에서 FOLLOW_UP 복구 대상과 ACTIVE monitoring 대상을 조회할 수 있게 한다.

V6 Migration은 nullable `status`를 먼저 추가하고 기존 `active=true`를 `ACTIVE`, `false`를 `INACTIVE`로 backfill한 뒤 `NOT NULL`을 적용하고 `active`를 제거한다. 기존 BUS Alarm row에는 가짜 Target을 생성하지 않으므로 Target 없는 legacy row도 Migration을 통과한다.

Transit API 조회 실패, Notification 발송 결과, ARRIVED/PASSED Event는 Alarm status로 저장하지 않는다. ACTIVE 중 차량별 tracking 및 Event consumption field도 이 table에 추가하지 않으며 TASK-509/708에서 별도 책임을 결정한다.

### bus_alarm_targets

Bus-specific 장기 Alarm 설정을 공통 `alarms`의 nullable column으로 펼치지 않고 공유 PK Entity/table로 저장한다. 한 Alarm은 최대 하나의 BusAlarmTarget을 가지며 `alarm_id`는 PK이자 `alarms.id` FK다. FK는 `ON DELETE CASCADE`이므로 Alarm 삭제 시 orphan Target이 남지 않는다.

```text
alarm_id BIGINT PRIMARY KEY REFERENCES alarms(id) ON DELETE CASCADE
provider VARCHAR(20) NOT NULL
external_route_id VARCHAR(255) NOT NULL
external_stop_id VARCHAR(255) NOT NULL
target_stop_order INT NOT NULL
route_number VARCHAR(100) NOT NULL
stop_name VARCHAR(255) NOT NULL
target_stop_latitude DECIMAL(10,7) NULL
target_stop_longitude DECIMAL(10,7) NULL
city_code VARCHAR(50) NULL
notify_one_stop_before BOOLEAN NOT NULL DEFAULT FALSE
predecessor_external_stop_id VARCHAR(255) NULL
predecessor_stop_order INT NULL
notify_one_stop_after BOOLEAN NOT NULL DEFAULT FALSE
successor_external_stop_id VARCHAR(255) NULL
successor_stop_order INT NULL
```

`provider`는 `TAGO`, `SEOUL_BUS` 문자열이다. TAGO에는 non-null `city_code`가 필요하고 서울에는 `NULL`이어야 하며 CHECK로 강제한다. 범용 JSON provider context는 저장하지 않는다. external ID와 vehicle tracking ID는 opaque String의 향후 여유를 위해 `VARCHAR(255)`, 표시용 노선번호는 `VARCHAR(100)`, 정류소명은 `VARCHAR(255)`, cityCode는 `VARCHAR(50)`을 사용한다.

Target GPS는 provider precision을 손실 없이 다루고 부동소수 오차를 피하기 위해 Java `BigDecimal`, MySQL `DECIMAL(10,7)`을 사용한다. 두 좌표는 함께 존재하거나 함께 `NULL`이어야 하고 유효 범위를 CHECK로 제한한다.

before 옵션이 켜지면 predecessor external Stop ID/order, after 옵션이 켜지면 successor external Stop ID/order가 반드시 존재하도록 CHECK를 둔다. 옵션이 꺼지면 대응 snapshot은 `NULL`이다. 인접 Stop의 display name과 GPS는 realtime occurrence 판정이나 현재 Notification 계약에 필요하지 않아 저장하지 않는다. predecessor/successor는 metadata traversal에서 확인한 occurrence snapshot이며 단순 `target_stop_order ± 1`을 가정하지 않는다.

Route identity는 `(provider, external_route_id)`, Stop identity는 `(provider, external_stop_id)`다. Target은 Route traversal 안의 occurrence이므로 `target_stop_order`를 별도로 저장한다. `(provider, external_route_id, external_stop_id)` Unique Constraint는 두지 않으며 이 세 값만으로 같은 Stop 재방문 occurrence를 합치지 않는다.

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
