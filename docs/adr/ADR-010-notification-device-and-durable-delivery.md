# ADR-010: Notification Device identity와 durable delivery

- 상태: 채택됨
- 날짜: 2026-09-21

## 배경

초기 StopBell 모델은 User별 Push token 후보와 Alarm별 `NotificationHistory(SUCCESS/FAILURE)`를 두었지만 실제 Notification correctness에 필요한 다음 의미를 분리하지 못한다.

- 앱 installation identity와 회전 가능한 Firebase targeting identifier
- 서로 다른 Alarm activation과 tracking cycle
- logical Notification 결정과 Device별 Provider delivery
- Alarm lifecycle commit 뒤 process crash에도 복구할 pending dispatch
- Provider request acceptance와 실제 Device 표시

Firebase는 legacy registration token에서 Firebase Installation ID(FID) 기반 targeting으로 전환 중이고 SDK별 지원 상태가 다를 수 있다. 지금 특정 identifier를 장기 Domain identity로 고정하면 사용하는 FlutterFire, Firebase iOS SDK, Java Firebase Admin SDK 계약과 어긋날 수 있다.

또한 lifecycle transition commit 뒤 Push를 non-durable callback으로만 수행하면 process crash 때 알림이 영구 누락된다. 반대로 FCM network I/O를 Alarm transaction 안에서 수행하면 외부 지연과 ambiguous timeout을 DB transaction에 결합한다.

## 검토한 선택지

### 선택지 A — Firebase/APNs identifier를 Device identity로 사용

장점:

- 저장 구조가 단순함

단점:

- rotation/re-registration을 Device 교체로 오인함
- SDK targeting 모델 변화가 StopBell Domain identity를 바꿈
- 순서가 역전된 update가 최신 registration을 덮을 수 있음

### 선택지 B — installation identity와 Push targeting identifier 분리

장점:

- StopBell installation lifecycle과 Provider delivery reference를 분리함
- multi-device와 registration rotation을 명확히 표현함
- stale update를 revision으로 거부할 수 있음

단점:

- Client installation ID와 registration ordering 계약이 필요함

### 선택지 C — lifecycle commit 뒤 즉시/non-durable callback으로 Push

장점:

- 구현이 작고 추가 persistence가 없음

단점:

- commit 뒤 process crash에서 Push가 영구 누락됨
- retry와 per-Device 상태를 복구할 근거가 없음

### 선택지 D — MySQL durable pending dispatch와 in-process worker

장점:

- lifecycle transition과 logical Event 결정을 atomic하게 commit함
- process restart 뒤 pending delivery를 복구할 수 있음
- 현재 MySQL과 monolith만으로 구현 가능함

단점:

- bounded retry, expiry 상태와 polling이 필요함
- FCM timeout 뒤 실제 접수 여부가 불명확하여 표시 exactly-once는 보장할 수 없음

### 선택지 E — 기존 NotificationHistory에 최종 SUCCESS/FAILURE만 기록

장점:

- 현재 Entity/table을 그대로 사용할 수 있음

단점:

- logical decision과 Device별 delivery가 섞임
- dedup, pending recovery, bounded retry, multi-device fan-out을 표현하지 못함

### 선택지 F — NotificationEvent와 NotificationDelivery 분리

장점:

- logical uniqueness와 Event×Device uniqueness를 각각 강제할 수 있음
- Provider delivery와 retry state가 Alarm Evaluation에서 분리됨
- 장기 Analytics와 operational correctness의 경계가 명확함

단점:

- 초기 NotificationHistory를 확장 또는 대체하는 Migration이 필요함

## 결정

### Device identity

V1 Device는 개념적으로 다음 세 값을 분리한다.

```text
Device internal PK
+ client-generated installationId
+ current Firebase push targeting identifier
```

`installationId`는 StopBell이 한 앱 installation을 구분하는 identity이며 User-scoped identity가 아니다. 하나의 installation에는 동시에 current owner가 최대 한 명이어야 한다. 같은 installation에서 다른 User가 로그인하면 atomic ownership takeover 또는 동등한 계약으로 이전·신규 ownership이 함께 enabled 상태로 남지 않게 한다. Firebase targeting identifier는 rotation/re-registration 가능한 delivery reference이며 Device identity가 아니다. APNs device token을 StopBell Device identity로 사용하지 않는다.

실제 targeting identifier는 TASK-701에서 사용할 FlutterFire/firebase_messaging, Firebase iOS SDK, Java Firebase Admin SDK 버전과 iOS 동작을 확인한 뒤 확정한다. 그전 문서는 `pushRegistrationId`, `pushTargetId` 같은 provider-neutral 용어를 사용한다. Targeting identifier 자체의 global uniqueness도 TASK-701 확인 전에는 가정하지 않으며 구체 field 이름, column 길이와 constraint는 TASK-702에서 정한다.

한 User는 여러 Device를 가질 수 있다. 단일 Device 제한을 두지 않고 RefreshToken Session과 Device를 FK로 직접 연결하지 않는다. Installation ownership takeover의 구체 DB constraint와 API transaction은 TASK-702/703에서 정한다. 동일 installation의 update에는 monotonic revision 또는 동등한 stale-write 보호가 필요하다. 오래된 update는 최신 target을 덮어쓸 수 없고 같은 revision과 같은 registration의 재요청은 idempotent하게 처리할 수 있어야 한다.

현재 installation logout은 해당 Push subscription만 disable/unregister하고 Alarm lifecycle과 다른 Device는 변경하지 않는다. `/auth/logout`은 Refresh Session 종료 책임을 유지하며 Device field를 받지 않는다. Device disable은 별도 authenticated API 또는 동등한 명시적 lifecycle로 처리한다. Flutter는 Phase 6 logout hook에서 Device disable을 시도한 뒤 Auth logout과 local session 종료를 수행하며, offline에서는 Backend disable을 즉시 보장하지 않는다.

### Alarm activation과 tracking identity

Alarm은 새 monitoring activation cycle마다 증가하는 `activationGeneration` persisted semantic generation을 가진다. 이는 `BIGINT NOT NULL DEFAULT 0`이며 `INACTIVE → ACTIVE`, `FOLLOW_UP → ACTIVE`에서 증가하고 `ACTIVE → ACTIVE`는 generation 증가와 baseline reset이 없는 idempotent 동작이다. deactivate 후 reactivate, FOLLOW_UP 중 reactivate, stale scheduler result와 이전 activation Notification candidate를 현재 activation과 구분한다. TASK-510은 API lifecycle mutation과 Scheduler result 적용에 `PESSIMISTIC_WRITE` 하나를 사용하며 CAS와 JPA `@Version`을 병행하지 않는다.

ACTIVE vehicle tracking은 기존 Phase 5 결정대로 V1에서 memory 기반일 수 있다. `trackingCycleId` 또는 동등한 값은 DB transaction ID가 아니라 logical vehicle tracking cycle identity로 cycle 시작 시 한 번 생성한다. 같은 logical cycle의 lifecycle transaction이 deadlock/optimistic conflict로 retry되어도 identity를 다시 만들지 않으며 retry로 Notification dedup uniqueness를 우회해서는 안 된다. TransitEvent 발생 시 이 identity를 durable NotificationEvent에 복사할 수 있다. Restart 뒤에는 이전 tracking을 새 cycle과 임의로 연결하지 않고 safe recovery baseline을 사용한다. Notification correctness를 이유로 raw TransitObservation 또는 ACTIVE tracking 전체를 영속하지 않는다.

### Duplicate identity

다음 중복 책임을 분리한다.

```text
반복 TransitObservation      → Phase 5 tracking
중복 TransitEvent candidate → Phase 5 Evaluation/tracking
동일 logical Notification   → Phase 7 persistence
```

Logical Notification의 기본 identity는 `(alarmId, activation generation, trackingCycleId, eventType)`이다. `alarmId + eventType`만 사용하지 않으며 TASK-707/708에서 DB Unique Constraint 또는 동등한 atomic uniqueness로 구현한다.

### Durable dispatch와 persistence 역할

Phase 7은 MySQL 기반 durable pending Notification dispatch/outbox를 사용한다.

```text
Provider I/O / Evaluation
  → DB transaction
     - current Alarm lifecycle/generation 검증
     - lifecycle transition
     - durable logical NotificationEvent insert
     - 현재 eligible Device별 NotificationDelivery(PENDING) 생성
  → commit
  → single fixed-delay worker가 기존 due PENDING Delivery 처리
  → FCM I/O
  → NotificationDelivery result update
```

Recipient set은 Event 결정 transaction에서 Device identity로 확정한다. Event 뒤 등록된 Device가 과거 Event를 받지 않으며 worker는 recipient를 새로 결정하지 않는다. Eligible Device가 0개여도 이미 발생한 logical NotificationEvent는 저장하고 Delivery는 0개로 두며 no-recipient operational log/metric으로 관찰한다. 이를 Provider failure나 success로 해석하거나 별도 enum을 강제하지 않는다.

V1은 하나의 Spring Backend와 하나의 fixed-delay, non-overlapping worker를 기본으로 한다. Worker는 전송 직전에 recipient Device가 여전히 Event owner의 올바른 installation인지, enabled인지와 current push target/revision을 재검증하고 실제 attempt revision을 기록한다. raw push target persistence는 필수가 아니다. FCM I/O를 Alarm lifecycle transaction 안에서 수행하지 않고 non-durable after-commit callback만을 유일한 전달 보장으로 사용하지 않는다. 외부 Kafka, RabbitMQ, Redis queue와 Notification microservice는 도입하지 않는다. claim/lease, `claimedAt`, `SENDING`, stale-claim recovery, multi-worker coordination은 V1에서 구현하지 않는다.

`NotificationEvent`는 durable logical decision과 dedup identity를 소유하고 Event 시점에 생성된 recipient Delivery들의 logical source가 된다. `NotificationDelivery`는 Event×Device, Provider delivery/retry/expiry state와 current/final result를 소유한다. 기존 `NotificationHistory` Entity/table을 확장·대체·migration하는 방식은 TASK-707에서 정하며 production legacy compatibility를 과도하게 만들지 않는다. 모든 retry attempt를 append-only row로 저장하지 않는다. Analytics는 실제 제품 질문과 보존 근거가 있을 때만 별도 책임으로 최소 구현하며, Event/Delivery를 장기 Analytics Source of Truth로 사용하지 않는다.

### Delivery semantics와 failure taxonomy

보장 경계는 다음과 같다.

```text
logical NotificationEvent         → DB uniqueness 기준 한 번
NotificationEvent × Device record → DB 기준 하나
FCM request                       → retry로 여러 번 가능
실제 Device 표시                  → exactly once 보장하지 않음
```

Provider acceptance는 실제 사용자 표시 성공이 아니다. Delivery lifecycle status는 `PENDING`, `ACCEPTED`, `FAILED`, `EXPIRED`를 기본 방향으로 하고 retry는 `PENDING + attemptCount + nextAttemptAt + freshness`로 표현한다. Provider result는 `ACCEPTED`, `INVALID_TARGET`, `RETRYABLE`, `CONFIGURATION`, `PERMANENT_REQUEST`, `AMBIGUOUS_TIMEOUT`으로 lifecycle status와 분리한다. `EXPIRED`는 Provider result가 아니라 local freshness 종료 의미다.

Invalid/unregistered 결과는 attempt revision이 Device의 current registration과 일치할 때만 조건부 disable한다. Old attempt 실패가 새 registration을 disable해서는 안 된다. `AMBIGUOUS_TIMEOUT`은 Provider가 실제 접수했는지 알 수 없는 상태다. FCM accepted 뒤 DB update 전 crash하면 Delivery가 PENDING으로 남아 restart 뒤 retry될 수 있으며, 실제 Device duplicate는 exactly-once 비보장 계약으로 허용한다. 최대 retry 횟수·간격·freshness TTL과 구체 field 이름은 TASK-709에서 smoke 결과와 함께 정한다. Generic retry framework는 미리 도입하지 않는다.

### Permission, payload와 tap

V1은 첫 Alarm activation 직전에 기능 맥락을 설명한 뒤 Notification permission을 요청하는 방향을 사용한다. Permission denied여도 Alarm 생성·활성화를 Backend에서 금지하지 않는다. 현재 Device가 수신할 수 없다는 경고와 Settings 안내를 제공하고 app resume에서 permission/registration 상태를 재동기화한다.

Payload는 `type`, `alarmId`, `eventType`, `notificationEventId` 수준의 navigation hint만 담는다. `userId`, Auth Token, push targeting identifier, Provider Route/Stop external ID, GPS, Alarm 상세 전체는 포함하지 않는다. Tap payload를 권한 근거로 사용하지 않고 Auth Session 초기화 뒤 Backend Alarm detail API로 ownership과 current state를 확인한다.

## 근거

Installation identity를 Provider targeting에서 분리하면 Firebase 계약 변화와 registration rotation이 StopBell Device lifecycle을 오염시키지 않는다. Activation generation과 cycle identity는 memory-based tracking을 유지하면서도 durable Notification dedup에 필요한 최소 문맥을 제공한다.

MySQL outbox는 이미 사용하는 infrastructure 안에서 lifecycle transition, logical Notification과 Event 시점 recipient Delivery 생성을 atomic하게 만들고 process crash 뒤 복구를 가능하게 한다. Event와 Delivery 분리는 multi-device recipient, retry와 Provider 결과를 logical Alarm Event에서 분리하면서 exactly-once라고 과장하지 않는 경계를 제공한다.

## 결과

- TASK-701은 SDK 버전과 실제 iOS targeting/local unregister 동작 및 early device smoke를 먼저 확인한다.
- TASK-702/703은 installation identity, multi-device, stale registration ordering과 별도 disable API를 정의·구현한다.
- TASK-510은 persisted activation generation의 정확한 증가와 `PESSIMISTIC_WRITE` stale-result 검증을 구현했다.
- TASK-707/708은 NotificationEvent/Delivery persistence와 logical DB uniqueness를 구현한다.
- TASK-707은 Alarm 삭제 시 이미 생성된 Event/Delivery를 유지·취소·cascade 삭제할지 lifecycle과 outbox recovery 의미로 결정하며 FK cascade에 우연히 맡기지 않는다.
- TASK-705/709는 명시적인 Provider result/failure와 bounded retry를 구현한다.
- TASK-704/710은 permission, registration, foreground/background/terminated/tap을 실제 iPhone에서 검증한다.
- Kafka, RabbitMQ, Redis queue, event sourcing, generic multi-provider/retry framework, Device subtype hierarchy, APNs direct client, multi-instance distributed lock은 V1에서 제외한다.
- ADR-002의 초기 hybrid 검토 이력과 현재 JPA persistence 방향, ADR-005의 RefreshToken-Device 비연결, ADR-007/008의 Transit Event와 Alarm lifecycle 결정은 유지한다. 초기 NotificationHistory의 최종 결과 기록 역할은 이 ADR의 Event/Delivery 분리 결정으로 대체한다.

## 재검토 시점

다음 중 하나 이상이 확인되면 이 결정을 재검토한다.

- 실제 SDK가 installationId와 별개인 targeting identifier를 안정적으로 제공하지 않음
- 다중 Backend instance 운영으로 claim/lease 등 worker coordination이 실제로 필요해짐
- Delivery 처리량이 MySQL polling으로 감당할 수 없는 측정된 병목이 됨
- Product가 Provider delivery receipt 또는 별도 APNs direct delivery를 요구함
- 운영·규제 요구로 모든 Provider attempt의 append-only audit가 필요해짐
