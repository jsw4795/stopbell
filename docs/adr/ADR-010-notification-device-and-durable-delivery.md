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

- worker claim, retry, expiry 상태와 polling이 필요함
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

`installationId`는 StopBell이 한 앱 installation을 구분하는 identity다. Firebase targeting identifier는 rotation/re-registration 가능한 delivery reference이며 Device identity가 아니다. APNs device token을 StopBell Device identity로 사용하지 않는다.

실제 targeting identifier는 TASK-701에서 사용할 FlutterFire/firebase_messaging, Firebase iOS SDK, Java Firebase Admin SDK 버전과 iOS 동작을 확인한 뒤 확정한다. 그전 문서는 `pushRegistrationId`, `pushTargetId` 같은 provider-neutral 용어를 사용한다. 구체 field 이름, column 길이와 uniqueness는 TASK-702에서 정한다.

한 User는 여러 Device를 가질 수 있다. 단일 Device 제한을 두지 않고 RefreshToken Session과 Device를 FK로 직접 연결하지 않는다. 동일 installation의 update에는 monotonic revision 또는 동등한 stale-write 보호가 필요하다. 오래된 update는 최신 target을 덮어쓸 수 없고 같은 revision과 같은 registration의 재요청은 idempotent하게 처리할 수 있어야 한다.

현재 installation logout은 해당 Push subscription만 disable/unregister하고 Alarm lifecycle과 다른 Device는 변경하지 않는다. `/auth/logout`은 Refresh Session 종료 책임을 유지하며 Device field를 받지 않는다. Device disable은 별도 authenticated API 또는 동등한 명시적 lifecycle로 처리한다. Flutter는 Phase 6 logout hook에서 Device disable을 시도한 뒤 Auth logout과 local session 종료를 수행하며, offline에서는 Backend disable을 즉시 보장하지 않는다.

### Alarm activation과 tracking identity

Alarm은 새 monitoring activation cycle마다 증가하는 persisted semantic activation generation을 가진다. 이는 deactivate 후 reactivate, FOLLOW_UP 중 reactivate, stale scheduler result와 이전 activation Notification candidate를 현재 activation과 구분한다. 구체 이름 후보는 `activationSequence`이며 정확한 increment 조건과 CAS/query, JPA `@Version` 병행 여부는 TASK-510에서 확정한다.

ACTIVE vehicle tracking은 기존 Phase 5 결정대로 V1에서 memory 기반일 수 있다. Runtime-unique `trackingCycleId` 또는 동등한 identity를 사용하고 TransitEvent 발생 시 durable NotificationEvent에 복사할 수 있다. Restart 뒤에는 이전 tracking을 새 cycle과 임의로 연결하지 않고 safe recovery baseline을 사용한다. Notification correctness를 이유로 raw TransitObservation 또는 ACTIVE tracking 전체를 영속하지 않는다.

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
  → commit
  → in-process Notification delivery worker
  → FCM I/O
  → NotificationDelivery result update
```

FCM I/O를 Alarm lifecycle transaction 안에서 수행하지 않고 non-durable after-commit callback만을 유일한 전달 보장으로 사용하지 않는다. 외부 Kafka, RabbitMQ, Redis queue와 Notification microservice는 도입하지 않는다. Worker claim 방식과 polling interval은 TASK-706~709에서 정한다.

`NotificationEvent`는 durable logical decision, dedup identity와 pending dispatch 근거를 소유한다. `NotificationDelivery`는 Event×Device, Provider delivery/retry/expiry state와 current/final result를 소유한다. 기존 `NotificationHistory` Entity/table을 확장·대체·migration하는 방식은 TASK-707에서 정하며 production legacy compatibility를 과도하게 만들지 않는다. 모든 retry attempt를 append-only row로 저장하지 않고 Phase 8 Analytics는 별도 책임으로 유지한다.

### Delivery semantics와 failure taxonomy

보장 경계는 다음과 같다.

```text
logical NotificationEvent         → DB uniqueness 기준 한 번
NotificationEvent × Device record → DB 기준 하나
FCM request                       → retry로 여러 번 가능
실제 Device 표시                  → exactly once 보장하지 않음
```

Provider acceptance는 실제 사용자 표시 성공이 아니다. Provider client와 Delivery state는 accepted, invalid/unregistered target, transient failure, rate/quota failure, provider authentication/configuration failure, invalid payload/permanent request failure, timeout/unknown acceptance state, expired notification을 구분한다.

Invalid/unregistered 결과는 실패한 targeting identifier와 revision이 Device의 current registration일 때만 조건부 disable한다. Timeout은 Provider가 실제 접수했는지 알 수 없는 ambiguous 상태다. 최대 retry 횟수·간격·freshness TTL과 최종 status/type 이름은 TASK-709에서 smoke 결과와 함께 정한다. Generic retry framework는 미리 도입하지 않는다.

### Permission, payload와 tap

V1은 첫 Alarm activation 직전에 기능 맥락을 설명한 뒤 Notification permission을 요청하는 방향을 사용한다. Permission denied여도 Alarm 생성·활성화를 Backend에서 금지하지 않는다. 현재 Device가 수신할 수 없다는 경고와 Settings 안내를 제공하고 app resume에서 permission/registration 상태를 재동기화한다.

Payload는 `type`, `alarmId`, `eventType`, `notificationEventId` 수준의 navigation hint만 담는다. `userId`, Auth Token, push targeting identifier, Provider Route/Stop external ID, GPS, Alarm 상세 전체는 포함하지 않는다. Tap payload를 권한 근거로 사용하지 않고 Auth Session 초기화 뒤 Backend Alarm detail API로 ownership과 current state를 확인한다.

## 근거

Installation identity를 Provider targeting에서 분리하면 Firebase 계약 변화와 registration rotation이 StopBell Device lifecycle을 오염시키지 않는다. Activation generation과 cycle identity는 memory-based tracking을 유지하면서도 durable Notification dedup에 필요한 최소 문맥을 제공한다.

MySQL outbox는 이미 사용하는 infrastructure 안에서 lifecycle commit과 logical Notification 생성을 atomic하게 만들고 process crash 뒤 복구를 가능하게 한다. Event와 Delivery 분리는 multi-device fan-out, retry와 Provider 결과를 logical Alarm Event에서 분리하면서 exactly-once라고 과장하지 않는 경계를 제공한다.

## 결과

- TASK-701은 SDK 버전과 실제 iOS targeting/local unregister 동작 및 early device smoke를 먼저 확인한다.
- TASK-702/703은 installation identity, multi-device, stale registration ordering과 별도 disable API를 정의·구현한다.
- TASK-510은 persisted activation generation의 정확한 증가/CAS 계약을 구현한다.
- TASK-707/708은 NotificationEvent/Delivery persistence와 logical DB uniqueness를 구현한다.
- TASK-705/709는 명시적인 Provider result/failure와 bounded retry를 구현한다.
- TASK-704/710은 permission, registration, foreground/background/terminated/tap을 실제 iPhone에서 검증한다.
- Kafka, RabbitMQ, Redis queue, event sourcing, generic multi-provider/retry framework, Device subtype hierarchy, APNs direct client, multi-instance distributed lock은 V1에서 제외한다.
- ADR-002의 JPA/MyBatis 역할 분담과 ADR-005의 RefreshToken-Device 비연결, ADR-007/008의 Transit Event와 Alarm lifecycle 결정은 유지한다. 초기 NotificationHistory의 최종 결과 기록 역할은 이 ADR의 Event/Delivery 분리 결정으로 대체한다.

## 재검토 시점

다음 중 하나 이상이 확인되면 이 결정을 재검토한다.

- 실제 SDK가 installationId와 별개인 targeting identifier를 안정적으로 제공하지 않음
- 다중 Backend instance 운영 때문에 현재 MySQL worker claim만으로 안전한 처리가 어려움
- Delivery 처리량이 MySQL polling으로 감당할 수 없는 측정된 병목이 됨
- Product가 Provider delivery receipt 또는 별도 APNs direct delivery를 요구함
- 운영·규제 요구로 모든 Provider attempt의 append-only audit가 필요해짐
