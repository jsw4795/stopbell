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
+ current Firebase Installation ID (FID)
```

`installationId`는 StopBell이 한 앱 installation을 구분하는 identity이며 User-scoped identity가 아니다. 하나의 installation에는 동시에 current owner가 최대 한 명이어야 한다. 같은 installation에서 다른 User가 로그인하면 atomic ownership takeover 또는 동등한 계약으로 이전·신규 ownership이 함께 enabled 상태로 남지 않게 한다. Firebase targeting identifier는 rotation/re-registration 가능한 delivery reference이며 Device identity가 아니다. APNs device token은 Apple/Firebase bridge와 readiness에만 사용하며 StopBell Device identity, Backend Push target, Domain identifier로 저장·노출하지 않는다.

TASK-701의 공식 SDK/API 조사 기준 V1 delivery target은 FID를 선택했으며, 사용자 확인에 근거해 HTTP v1 `message.fid` 전송과 실제 iPhone notification 수신의 hardware smoke gate를 통과했다. TASK-701은 완료이며 TASK-702에서 이 계약을 사용해 Device Domain/Schema를 구체화한다. FID를 Firebase project/app 문맥과 무관한 영구적·전역 Device identity로 가정하지 않는다. 구체 field 이름, column 길이와 constraint는 TASK-702에서 정한다.

한 User는 여러 Device를 가질 수 있다. 단일 Device 제한을 두지 않고 RefreshToken Session과 Device를 FK로 직접 연결하지 않는다. Installation ownership takeover의 구체 DB constraint와 API transaction은 TASK-702/703에서 정한다. 동일 installation의 update에는 monotonic revision 또는 동등한 stale-write 보호가 필요하다. 오래된 update는 최신 target을 덮어쓸 수 없고 같은 revision과 같은 registration의 재요청은 idempotent하게 처리할 수 있어야 한다.

현재 installation logout은 해당 Push subscription만 disable/unregister하고 Alarm lifecycle과 다른 Device는 변경하지 않는다. `/auth/logout`은 Refresh Session 종료 책임을 유지하며 Device field를 받지 않는다. Device disable은 별도 authenticated API 또는 동등한 명시적 lifecycle로 처리한다. Flutter는 Phase 6 logout hook에서 Device disable을 시도한 뒤 Auth logout과 local session 종료를 수행하며, offline에서는 Backend disable을 즉시 보장하지 않는다.

### TASK-701 Firebase iOS 기술 계약 (2026-10-06)

공식 stable package와 선택 버전의 SDK source를 확인했다.

| 구성 | 선택 버전 / 계약 |
|---|---|
| FlutterFire Core | `firebase_core 4.15.0` |
| FlutterFire Messaging | `firebase_messaging 16.7.0` |
| FlutterFire Installations | `firebase_app_installations 0.4.4` |
| Native Firebase Apple SDK | 위 세 plugin의 SPM manifest가 exact pin하는 `12.19.0` |
| Backend 후보 | `com.google.firebase:firebase-admin:9.11.0`, 실제 dependency 추가는 후속 Provider 구현에서 수행 |

세 Dart package의 최소 요구사항은 Dart `^3.6.0`, Flutter `>=3.27.0`이고 Apple SDK의 공식 최소 요구사항은 Xcode `26.2+`, iOS `15+`다. 현재 Flutter `3.47.1` / Dart `3.13.1` / Xcode `27.0` / iOS deployment target `15.0`은 이를 만족한다. Native standalone stable은 `12.19.2`지만 해당 patch는 Analytics 수정이고 이번 FlutterFire graph는 `12.19.0`을 사용한다. Native SDK를 직접 추가하거나 override하지 않으며 기존 `FlutterGeneratedPluginSwiftPackage` SPM 경로만 사용한다. CocoaPods와 중복 설치하지 않는다. Firebase SPM repository의 전체 package resolution 때문에 `Package.resolved`에 GoogleAppMeasurement `12.19.2` 등 미사용 product의 package도 나타나지만 Firebase SDK exact pin override나 Analytics product 추가를 뜻하지 않는다. 생성 plugin graph는 Core/Installations/Messaging만 연결하며 이번 unsigned build의 Runner binary에서도 FirebaseAnalytics symbol이 없음을 확인했다.

Admin Java `9.11.0`은 Java 8+ SDK이며 선택 release POM도 Java 8 bytecode를 사용하므로 Java 21 기준에 부합한다. Spring Boot 전용 adapter가 필요한 SDK는 아니다. 다만 Spring Boot `4.1.1`의 managed graph와 기존 `google-api-client:2.9.0`, Admin의 Google Cloud BOM/Netty/SLF4J transitive resolution은 실제 dependency를 추가할 Task에서 검증한다. 이번 Task는 Backend dependency/build/runtime 호환성 통합 테스트를 수행했다고 주장하지 않는다. Admin `9.10.0`부터 `Message.Builder.setFid(String)`를 제공하고 `setToken(String)`은 deprecated다. 새로운 V1 send는 `Message.builder().setFid(currentFid)`를 기준으로 하며 token 기반 설계를 추가하지 않는다.

FID 조회와 FCM delivery registration은 서로 다르다.

1. `FirebaseInstallations.instance.getId()`는 FID가 없으면 생성하고 현재 ID를 조회한다. 조회 성공만으로 APNs 준비나 FCM 등록 성공을 뜻하지 않는다.
2. Apple `Info.plist`의 `FirebaseMessagingInstallationIdEnabled = YES`로 FID mode를 선택한다. 기본값은 NO이며 YES에서는 token 조회/삭제 API가 unsupported로 실패한다.
3. Notification permission 요청 뒤 APNs availability를 확인하고 native `Messaging.messaging().register(completion:)` 성공과 `MessagingDelegate.messaging(_:didReceiveRegistration:)`의 FID 수신을 모두 확인한다. Network가 필요하며 기존 등록에서도 등록 callback을 다시 발생시킨다.
4. Targeting reference는 registration delegate callback으로 받은 FID만 사용한다. 등록 전후 `getId()`와 `onIdChange`는 raw FIS ID의 lifecycle/rotation 확인에만 사용하며, 등록 중 또는 Smoke 중 변경이 감지되면 기존 target 표시를 폐기하고 다시 준비한다.

`firebase_messaging 16.7.0`에는 Dart `register()`/`unregister()`나 FID registration callback이 없다. `onTokenRefresh`는 FID 변경 stream이 아니다. 이번 Task는 plugin source 수정 없이 이미 FlutterFire가 연결한 native SDK의 `register()`를 debug smoke 전용 MethodChannel로 호출한다. Firebase 초기화 뒤 명시적 smoke 등록 요청에서 AppDelegate를 MessagingDelegate로 설정하며 callback FID를 Dart에 반환한다. Completion/callback 순서와 무관하게 한 번만 반환하고, 중복 pending 요청은 거부하며 30초 이내 완료되지 않으면 오류로 반환한다. 이 bridge는 production Device registration 구현이 아니며, 후속 Flutter registration 구현 전에 당시 FlutterFire FID API 지원을 다시 확인해야 한다.

FID는 재설치·다른 기기 복원, 앱/device 데이터 초기화, 명시적 installation deletion, Firebase backend의 inactivity deletion 등으로 바뀔 수 있다. 공식 FIS 문서의 현재 inactivity 기준은 270일이며 StopBell 영구 identity/DB 보존 정책으로 고정하지 않는다. `FirebaseInstallations.instance.onIdChange`로 변경을 관찰하고 `getId()`로 현재 값을 다시 조회한다. FIS auth token의 refresh와 FID rotation, APNs token 변경은 같은 사건이 아니다. Startup/resume에서도 현재 FID와 FCM/APNs readiness를 다시 확인해 Backend와 재동기화하는 계약을 사용하되 실제 제품 구현은 이번 Task에 포함하지 않는다.

| 동작 | 의미와 일반 logout 적용 |
|---|---|
| StopBell Backend Device disable | 현재 installation의 StopBell recipient eligibility만 해제. 일반 logout의 책임 경계이며 다른 Device/Alarm은 유지 |
| Legacy `deleteToken()` | 기존 FCM token만 삭제, FID deletion과 다름. 이번 FID mode에서는 unsupported이며 사용하지 않음 |
| Native `unregister()` | 현재 FID의 FCM delivery registration 해제, FID는 보존. 이후 send는 404; auto-init이 켜져 있으면 다음 startup에 재등록될 수 있음 |
| `setAutoInitEnabled(false)` | 향후 자동 FCM registration 방지. 이미 등록된 target을 revoke하거나 FID를 삭제하는 동작은 아님 |
| `FirebaseInstallations.instance.delete()` | FID 및 연결된 Firebase 데이터 deletion lifecycle. 서비스가 계속 ID를 생성하면 새 FID가 생길 수 있으며 일반 logout API로 사용하지 않음 |
| StopBell logout | Device disable 시도 → 기존 Auth logout/local session 종료. FID 보존, Firebase token/installation 삭제 및 auto-init 변경을 일반 logout에 추가하지 않음 |

`/auth/logout`에는 Device 책임을 추가하지 않는다. Offline Device disable은 즉시 보장되지 않으며 Auth session 종료를 영구 차단하지 않는다. Firebase installation deletion은 개인정보/installation lifecycle의 별도 동작이고 관련 데이터 삭제에는 공식 보존·삭제 기간이 적용된다.

이번 smoke는 normal product entrypoint와 분리한 debug iOS 앱에서 실행한다. `FirebaseMessagingAutoInitEnabled = NO`를 유지하고 smoke 버튼에서 APNs와 FCM을 명시적으로 등록하여 auto-init의 persisted opt-in을 남기지 않는다. 실제 config와 APNs key 준비 및 Firebase native core 초기화는 사용자 실기기 확인으로 검증됐지만 일반 앱의 permission/Device registration UX는 아직 구현하지 않는다. Firebase Auth 등 다른 제품과 production Provider Client는 추가하지 않는다.

TASK-701 완료 기록은 사용자의 실제 iPhone 검증 보고에 근거한다.

- Firebase Core 초기화, APNs token 확보, `Messaging.register()` completion 성공 및 `MessagingDelegate.didReceiveRegistration`의 FID 수신 확인
- Firebase Installations FID와 등록 callback FID 일치 및 앱 삭제 후 재설치 시 FID 변경 확인
- FCM HTTP v1 `message.fid` 전송과 서버 접수 및 실제 iPhone의 `StopBell TASK-701 smoke` notification 수신 성공
- 이전 `404 NOT_FOUND / UNREGISTERED`의 최종 원인은 FID를 Mac에 수동 입력할 때 대문자 `I`와 소문자 `l`을 혼동한 오류이며, 정확한 FID로 전송·수신 성공

SDK/FCM 결함이나 APNs 연동·FID registration 실패로 확정된 문제는 없다. 과거 `message.token` 시도는 입력 FID의 정확성이 보장되지 않아 token targeting 실패를 입증한 실험으로 기록하지 않는다. V1 전송 계약은 `message.fid`를 유지한다.

위 native `unregister()` 의미는 공식 SDK 계약 확인이며 실기기 동작 검증은 아직 수행하지 않았다. Foreground/terminated 수신과 tap navigation, Backend Firebase Admin SDK 연동도 미검증이다. StopBell `installationId`와 Firebase FID의 분리 및 rotation 계약을 유지하며 다음 단계는 TASK-702다. 재현 절차는 [Local early smoke 절차](../local-development.md#firebase-ios-early-smoke-task-701)를 따른다. Provider accepted 응답, unsigned build 성공, 화면의 등록 성공만으로 실제 수신 성공을 대신하지 않는다.

공식 근거:

- [FlutterFire Core](https://pub.dev/packages/firebase_core/changelog), [Messaging](https://pub.dev/packages/firebase_messaging/changelog), [Installations](https://pub.dev/packages/firebase_app_installations/changelog)의 stable 버전과 native pin
- [Firebase Apple setup](https://firebase.google.com/docs/ios/setup), [Apple release notes](https://firebase.google.com/support/release-notes/ios)의 최소 환경과 standalone patch
- [선택 Apple SDK의 Messaging header](https://github.com/firebase/firebase-ios-sdk/blob/12.19.0/FirebaseMessaging/Sources/Public/FirebaseMessaging/FIRMessaging.h)의 FID mode, register/unregister, auto-init 계약
- [선택 Flutter Messaging API source](https://github.com/firebase/flutterfire/blob/firebase_messaging-v16.7.0/packages/firebase_messaging/firebase_messaging/lib/src/messaging.dart)의 Dart API 지원 범위
- [FCM registration management](https://firebase.google.com/docs/cloud-messaging/manage-tokens), [Firebase Installations lifecycle](https://firebase.google.com/docs/projects/manage-installations)의 FID targeting, rotation 및 deletion
- [Admin release notes](https://firebase.google.com/support/release-notes/admin/java), [Message.Builder](https://firebase.google.com/docs/reference/admin/java/reference/com/google/firebase/messaging/Message.Builder), [선택 release POM](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/pom.xml)의 Java/API 계약

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
