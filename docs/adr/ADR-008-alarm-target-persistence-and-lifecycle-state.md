# ADR-008: Alarm Target 영속성과 lifecycle 상태

- 상태: 채택됨
- 날짜: 2026-09-10

## 배경

기존 `Alarm.active` boolean은 일반 monitoring이 끝났지만 ARRIVED 차량의 ONE_STOP_AFTER만 남은 상태를 표현할 수 없다. 또한 BUS 전용 Route/Stop target, Provider request context와 metadata snapshot을 공통 `alarms` table에 모두 nullable column으로 추가하면 향후 SUBWAY Target과 책임이 섞인다.

ARRIVED 뒤 after follow-up은 서버 재시작 뒤에도 같은 차량을 이어서 추적해야 한다. 반면 ACTIVE 중 ONE_STOP_BEFORE/PASSED와 차량별 observation history는 Alarm 자체 lifecycle이 아니며, 여러 차량과 activation cycle에 종속된다.

## 검토한 선택지

### 선택지 A — active와 followUp boolean 조합

장점:

- 기존 Schema 변경이 작음

단점:

- `active=true, followUp=true` 같은 모호하거나 불가능한 조합이 생김
- ARRIVED Event와 after 전용 lifecycle을 명시적으로 구분하기 어려움

### 선택지 B — INACTIVE / ACTIVE / FOLLOW_UP Enum

장점:

- 가능한 lifecycle 상태를 한 값으로 제한함
- FOLLOW_UP을 ARRIVED 뒤 ONE_STOP_AFTER 전용으로 명시할 수 있음
- status 조회로 재시작 복구 대상을 찾을 수 있음

단점:

- 기존 boolean 값을 새 상태로 Migration해야 함

### 선택지 C — BUS field를 alarms에 포함

장점:

- 하나의 Entity/table만 사용함

단점:

- 공통 Alarm에 Provider와 BUS snapshot nullable column이 늘어남
- 장기 Target 설정과 짧은 follow-up runtime 책임이 섞임
- SUBWAY Target 확장 시 같은 문제가 반복됨

### 선택지 D — 공유 PK BusAlarmTarget Entity/table

장점:

- 공통 lifecycle과 BUS 전용 장기 설정을 분리함
- 한 Alarm당 최대 한 Bus Target을 PK/FK로 단순하게 강제함
- 기존 Alarm row에 가짜 Target을 만들 필요가 없음
- Alarm aggregate cascade와 Database `ON DELETE CASCADE`로 orphan을 방지함

단점:

- shared-primary-key one-to-one JPA mapping과 별도 table이 필요함

### 선택지 E — Provider metadata를 매번 조회

장점:

- 저장 snapshot이 stale할 가능성이 없음

단점:

- Alarm 목록 표시와 polling 평가가 외부 API 가용성·rate limit에 불필요하게 의존함
- 선택 당시 target occurrence와 인접 traversal evidence를 잃음

### 선택지 F — 생성 시 필요한 metadata snapshot 저장

장점:

- Route/Stop display와 인접 occurrence 일치를 Provider metadata 재조회 없이 수행 가능함
- 선택 당시 operational context를 보존함

단점:

- Provider metadata 변경 시 snapshot이 stale할 수 있음

### 선택지 G — follow-up을 memory에만 저장

장점:

- Schema가 단순함

단점:

- 서버 재시작 시 ARRIVED 차량과 만료 문맥을 잃어 ONE_STOP_AFTER를 복구할 수 없음

### 선택지 H — FOLLOW_UP runtime 영속

장점:

- status, 차량 correlation ID와 유효시간으로 재시작 복구가 가능함
- 일반 Alarm Target identity와 runtime을 분리할 수 있음

단점:

- 상태 전이마다 runtime 정합성을 유지해야 함

## 결정

`AlarmStatus`는 `INACTIVE`, `ACTIVE`, `FOLLOW_UP` 세 값만 가지며 `EnumType.STRING`으로 저장한다. `ARRIVED`는 Event이고 status가 아니다. ONE_STOP_BEFORE와 PASSED 뒤에는 ACTIVE를 유지한다. ARRIVED 뒤 after 옵션이 꺼져 있으면 INACTIVE, 켜져 있으면 FOLLOW_UP으로 전환하며 FOLLOW_UP은 ONE_STOP_AFTER 전용이다.

FOLLOW_UP runtime은 `alarms`의 `follow_up_vehicle_tracking_id`, `follow_up_started_at`, `follow_up_expires_at`에 저장한다. FOLLOW_UP이면 세 값과 after 옵션이 필요하고, 다른 status이면 세 값은 모두 비운다. Domain은 전체 불변 조건을 검사하고 Database CHECK는 같은 table 안의 status/runtime 완전성과 시간 순서를 강제한다. cross-table after option 조건은 Database CHECK로 복잡하게 만들지 않고 Domain에서 강제한다.

BUS 전용 장기 설정은 `bus_alarm_targets` 별도 Entity/table로 분리한다. `alarm_id`는 PK이자 `alarms.id` FK이며 `@MapsId` shared-primary-key one-to-one을 사용한다. Alarm이 aggregate lifecycle을 소유해 persist/remove를 cascade하고 FK도 `ON DELETE CASCADE`를 사용한다. Target 없는 legacy Alarm은 load하고 비활성화할 수 있지만 새 BUS Alarm 생성과 재활성화에는 Target을 요구한다. generic inheritance나 polymorphic target framework는 도입하지 않는다.

Provider namespace는 ADR-006의 `TAGO`, `SEOUL_BUS`를 `TransitProvider` Enum으로 저장한다. Route/Stop external ID는 opaque `VARCHAR(255)`이고 `target_stop_order`는 occurrence operational snapshot이다. `(provider, external_route_id, external_stop_id)` Unique Constraint는 두지 않는다.

선택 당시 `route_number`, `stop_name`, optional target GPS를 snapshot으로 저장한다. GPS는 `BigDecimal`/`DECIMAL(10,7)`을 사용한다. Provider request context는 generic JSON/Map이 아니라 nullable `city_code` typed column만 두며 TAGO에는 필요하고 SEOUL_BUS에는 없어야 한다.

before/after 옵션은 기본 OFF다. 생성 API에서 predecessor snapshot의 존재가 before ON, successor snapshot의 존재가 after ON을 의미하게 해 option과 인접 occurrence를 함께 받는다. 저장하는 인접 snapshot은 realtime 차량이 해당 occurrence에 도달했는지 판단하는 데 필요한 external Stop ID와 실제 traversal Stop order다. 인접 Stop name은 Target name으로 현재 Notification 표시 요구를 충족하고, 인접 GPS는 현재 Evaluation 필수 evidence가 아니므로 저장하지 않는다. 자동 metadata reconciliation은 V1에서 구현하지 않는다.

V6 Migration은 새 nullable status를 추가하고 기존 `active=true`를 ACTIVE, `false`를 INACTIVE로 backfill한 뒤 NOT NULL을 적용하고 active column을 제거한다. 기존 row는 FOLLOW_UP일 수 없고 BusAlarmTarget 가짜 row를 만들지 않는다.

## 근거

명시적인 Enum은 boolean 조합 없이 제품 lifecycle을 그대로 표현한다. FOLLOW_UP runtime을 Alarm에 두면 Target의 장기 identity와 섞이지 않으면서 status 조회만으로 복구 대상을 찾을 수 있다. BUS 설정을 별도 shared-PK table로 분리하면 nullable column 확산을 막으면서도 V1 규모에서 한 단계의 JPA association만 추가한다.

선택 시점 snapshot은 목록/Notification과 polling을 Provider metadata 가용성에서 분리한다. identity와 snapshot을 구분하고 자동 reconciliation을 하지 않으므로 stale 가능성도 명시적으로 유지한다.

## 결과

- `activate()`는 INACTIVE/FOLLOW_UP을 ACTIVE로 전환하고 이전 follow-up runtime을 지운다.
- `deactivate()`는 ACTIVE/FOLLOW_UP을 INACTIVE로 전환하고 runtime을 지운다.
- `startFollowUp(...)`은 ACTIVE, after option, 완전한 차량/시간 문맥을 요구한다.
- `completeFollowUp()`은 FOLLOW_UP을 INACTIVE로 전환하고 runtime을 지운다.
- status index로 후속 Scheduler가 ACTIVE/FOLLOW_UP Alarm을 조회할 수 있다.
- ACTIVE 중 vehicle/event state, Observation history, dedup journal은 이번 Schema에 포함하지 않는다.
- API validation과 HTTP error는 TASK-402 이후, Evaluation·만료시간·복구 실행은 TASK-509/510, Event consumption persistence는 TASK-708에서 결정한다.

## 재검토 시점

다음 중 하나 이상이 확인되면 이 결정을 재검토한다.

- SUBWAY Target이 shared-PK table 패턴으로 표현하기 어려운 별도 lifecycle을 요구함
- Provider metadata 변경으로 stored snapshot의 자동 reconciliation이 실제 제품 요구가 됨
- 인접 Stop name/GPS 없이는 Evaluation 또는 Notification 계약을 충족할 수 없음
- 다중 Backend concurrency 때문에 current status/runtime CHECK만으로 follow-up 정합성을 유지할 수 없음
