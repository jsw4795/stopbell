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

TASK-701의 공식 SDK/API 조사 기준 V1 delivery target은 FID를 선택했으며, 사용자 확인에 근거해 HTTP v1 `message.fid` 전송과 실제 iPhone notification 수신의 hardware smoke gate를 통과했다. TASK-701은 완료이며 TASK-702는 이 계약으로 Device Domain/Schema를 구체화했다. FID를 Firebase project/app 문맥과 무관한 영구적·전역 Device identity로 가정하지 않는다. 확정된 field/constraint는 [Device Domain](../domain-model.md#device)과 [devices Schema](../database.md#devices)를 따른다.

한 User는 여러 Device를 가질 수 있다. 단일 Device 제한을 두지 않고 RefreshToken Session과 Device를 FK로 직접 연결하지 않는다. Installation ownership 이전의 계약은 TASK-702에서 정의했으며 실제 API transaction은 TASK-703에서 구현한다. 동일 installation의 update에는 monotonic revision 또는 동등한 stale-write 보호가 필요하다. 오래된 update는 최신 target을 덮어쓸 수 없고 같은 revision과 같은 registration의 재요청은 idempotent하게 처리할 수 있어야 한다.

현재 installation logout은 해당 Push subscription만 disable/unregister하고 Alarm lifecycle과 다른 Device는 변경하지 않는다. `/auth/logout`은 Refresh Session 종료 책임을 유지하며 Device field를 받지 않는다. Device disable은 별도 authenticated API 또는 동등한 명시적 lifecycle로 처리한다. Flutter는 Phase 6 logout hook에서 Device disable을 시도한 뒤 Auth logout과 local session 종료를 수행하며, offline에서는 Backend disable을 즉시 보장하지 않는다.

### TASK-702 설치본 권한과 소유권 이전 결정 (2026-10-09)

UUID v4는 식별자이고 Client revision은 순서값이므로 둘만으로 다른 User의 설치본 takeover를 허용할 수 없다. 현재 owner의 JWT만으로 이전을 허용하면 새 User 로그인이나 offline logout 뒤 이전에 사용할 권한이 없어지고, FID/APNs를 비밀키처럼 사용하면 identity/delivery 분리 원칙을 깨뜨린다.

최소 대안은 설치 시 별도의 CSPRNG 256-bit installation credential을 생성·Secure Storage에 보관하고 Backend에는 hash만 저장하는 것이다. JWT는 User를, 자격증명은 설치본 보유 권한을 확인한다. Credential은 installation 동안 불변이며 logout/계정 전환에도 유지한다. UUID/FID만으로 재발급하거나 다른 자격증명의 기존 row를 덮어쓰지 않는다. 자격증명 유실 시 자동 복구를 제공하지 않는 fail-closed trade-off를 선택한다. 응답 유실 뒤 계정 변경과 counter/generation 복구에는 JWT와 자격증명으로 보호한 최소 상태 조회를 사용한다. UUID만으로는 조회·복구하지 않는다. 이 자격증명은 Firebase registration 소유권 attestation이 아니며 V1에서 Firebase proof/별도 기기 attestation 체계를 추가하지 않는다.

추가로 서버가 owner 이전 시만 증가시키는 `ownershipGeneration`을 둔다. 새 owner의 인증, 설치본 자격증명, 명시적 이전 의도와 current generation 일치 후 높은 revision의 이전을 원자적으로 적용한다. generation은 Client가 임의로 큰 값을 보내 설정할 수 없다. A→B→A 뒤 이전 A 요청도 old generation으로 거부하며, 큰 revision이 권한/generation을 대체하지 못한다. 이전 성공 응답 유실의 동일 상태 재요청만 무변경 멱등 성공으로 처리한다. 세부 순서와 validation/error/response는 [Device API 계약](../api.md#기기-등록해제-계약-task-702)이 소유한다.

단일 Firebase Project V1에서는 현재 FID의 중복 활성 등록을 허용하지 않는다. disable 시 FID를 NULL로 해제하고 case-sensitive nullable UNIQUE를 적용해 비활성 row의 과거 target 점유를 없앤다. 충돌 FID 제출로 기존 Device를 자동 탈취·disable하지 않는다. Schema/index는 [database.md](../database.md#devices)가 소유한다.

TASK-702는 Device/Platform과 V13만 구현한다. 기존 row의 write lock과 최초 생성 UNIQUE 충돌 처리, revision·generation 비교, ownership/disable Service/API 및 correctness test는 TASK-703 책임이다. Event 시점 recipient 확정, worker의 전송 직전 owner/enabled/target/revision 재검증과 TASK-707 persistence 책임은 유지한다. 이미 접수된 전송의 회수나 offline 즉시 해제는 보장하지 않는다.

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

위 native `unregister()` 의미는 공식 SDK 계약 확인이며 실기기 동작 검증은 아직 수행하지 않았다. Foreground/terminated 수신과 tap navigation, Backend Firebase Admin SDK 연동도 미검증이다. StopBell `installationId`와 Firebase FID의 분리 및 rotation 계약을 유지한다. TASK-702의 Domain/Schema와 lifecycle 계약은 정의됐으며 실제 Device API는 TASK-703 책임이다. 재현 절차는 [Local early smoke 절차](../local-development.md#firebase-ios-early-smoke-task-701)를 따른다. Provider accepted 응답, unsigned build 성공, 화면의 등록 성공만으로 실제 수신 성공을 대신하지 않는다.

공식 근거:

- [FlutterFire Core](https://pub.dev/packages/firebase_core/changelog), [Messaging](https://pub.dev/packages/firebase_messaging/changelog), [Installations](https://pub.dev/packages/firebase_app_installations/changelog)의 stable 버전과 native pin
- [Firebase Apple setup](https://firebase.google.com/docs/ios/setup), [Apple release notes](https://firebase.google.com/support/release-notes/ios)의 최소 환경과 standalone patch
- [선택 Apple SDK의 Messaging header](https://github.com/firebase/firebase-ios-sdk/blob/12.19.0/FirebaseMessaging/Sources/Public/FirebaseMessaging/FIRMessaging.h)의 FID mode, register/unregister, auto-init 계약
- [선택 Flutter Messaging API source](https://github.com/firebase/flutterfire/blob/firebase_messaging-v16.7.0/packages/firebase_messaging/firebase_messaging/lib/src/messaging.dart)의 Dart API 지원 범위
- [FCM registration management](https://firebase.google.com/docs/cloud-messaging/manage-tokens), [Firebase Installations lifecycle](https://firebase.google.com/docs/projects/manage-installations)의 FID targeting, rotation 및 deletion
- [Admin release notes](https://firebase.google.com/support/release-notes/admin/java), [Message.Builder](https://firebase.google.com/docs/reference/admin/java/reference/com/google/firebase/messaging/Message.Builder), [선택 release POM](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/pom.xml)의 Java/API 계약

### Alarm activation과 tracking identity

Alarm은 새 monitoring activation cycle마다 증가하는 `activationGeneration` persisted semantic generation을 가진다. 이는 `BIGINT NOT NULL DEFAULT 0`이며 `INACTIVE → ACTIVE`, `FOLLOW_UP → ACTIVE`에서 증가하고 `ACTIVE → ACTIVE`는 generation 증가와 baseline reset이 없는 idempotent 동작이다. deactivate 후 reactivate, FOLLOW_UP 중 reactivate, stale scheduler result와 이전 activation Notification candidate를 현재 activation과 구분한다. TASK-510은 API lifecycle mutation과 Scheduler result 적용에 `PESSIMISTIC_WRITE` 하나를 사용하며 CAS와 JPA `@Version`을 병행하지 않는다.

ACTIVE vehicle tracking은 기존 Phase 5 결정대로 V1에서 memory 기반일 수 있다. `trackingCycleId`는 DB transaction ID가 아니라 logical vehicle tracking cycle identity로 cycle 시작 시 한 번 생성한다. 같은 logical cycle의 lifecycle transaction이 deadlock 등으로 retry되어도 identity를 다시 만들지 않으며 retry로 Notification dedup uniqueness를 우회해서는 안 된다. TransitEvent 발생 시 이 identity를 durable NotificationEvent에 복사한다. ACTIVE restart 뒤에는 이전 tracking을 새 cycle과 임의로 연결하지 않고 safe recovery baseline을 사용한다. FOLLOW_UP은 아래의 원래 cycle identity 복원 계약을 따른다. Notification correctness를 이유로 raw TransitObservation 또는 ACTIVE tracking 전체를 영속하지 않는다.

### Duplicate identity

다음 중복 책임을 분리한다.

```text
반복 TransitObservation      → Phase 5 tracking
중복 TransitEvent candidate → Phase 5 Evaluation/tracking
동일 logical Notification   → Phase 7 persistence
```

Logical Notification의 identity는 `(alarmId, activationGeneration, trackingCycleId, eventType)`이다. `alarmId + eventType`만 사용하지 않는다. 확정된 [Schema/UNIQUE 계약](../database.md#phase-7-notification-persistence-contract)은 TASK-707에서 생성하고, 실제 적용은 TASK-708 implementation에서 수행한다.

### TASK-708 중복 방지 설계 (2026-10-09)

설계 완료, 구현 미완료다. 결정·대안·구현 분담은 이 절, Domain invariant는 [Domain Model](../domain-model.md#notificationevent), column/constraint/index는 [Database](../database.md#phase-7-notification-persistence-contract), transaction 실행 절차는 [Architecture](../architecture.md#notification-decision-transaction-task-708)가 소유한다.

#### 현재 구현 조사와 보장 한계

- `Alarm`/V11은 activation generation을 영속하고 기존 증가 규칙을 구현했다. `VehicleTrackingState.begin()`/`beginBeforeTarget()`은 UUID를 생성하며 `observe()`/`emit()`은 같은 ID를 유지한다. `TransitEvent`는 UUID와 event type을 전달한다.
- `BusAlarmEvaluationState.forFollowUp()`은 정상 process 안에서 ARRIVED 차량의 cycle을 유지한다. 반면 `Alarm`/V6에는 차량 ID와 시작·만료 시각만 있고 cycle ID는 없다. `BusAlarmEvaluator.evaluateFollowUp()`은 memory tracking이 없으면 `begin()`으로 새 UUID를 만든다. 따라서 현재 FOLLOW_UP 복구는 차량 correlation은 가능하지만 ARRIVED와 ONE_STOP_AFTER의 cycle identity continuity는 보장하지 못한다.
- `BusAlarmLifecycleService.applyIfCurrent()`는 row lock과 status/generation 검증 및 lifecycle 전이만 수행한다. Scheduler는 성공 반환 후 memory next state를 반영한다. NotificationEvent/Delivery, atomic fan-out, DB dedup과 dispatch worker는 아직 없다. 기존 `NotificationHistory`/V3/V9는 최종 SUCCESS/FAILURE와 Alarm 삭제 cascade만 표현한다.
- ACTIVE restart는 memory를 잃고 새 baseline/cycle을 만든다. 현재 `evaluateBaseline()`은 predecessor에서 ONE_STOP_BEFORE 후보를 만들 수 있어 [Architecture의 restart 재발행 억제 방향](../architecture.md#alarm과-vehicle-tracking-lifecycle)과 구현 차이가 있다. 이번 설계는 이 기존 Phase 5 문제를 해결하거나 정책을 변경하지 않는다. UUID가 다른 ACTIVE cycle 사이의 동일한 물리 차량/Event 중복까지 DB UNIQUE로 막는다고 주장하지 않으며, 기존 recovery 방향의 정합성 검증은 TASK-811에서 다룬다.

#### 상황별 정책

| 상황 | identity와 처리 |
| --- | --- |
| 같은 차량·같은 cycle·같은 Event 반복 | Phase 5가 후보를 억제하고, 같은 네 값이 다시 제출돼도 DB에는 Event 하나와 최초 Delivery set만 존재 |
| 같은 Alarm에서 다른 차량의 같은 Event | 다른 logical cycle UUID이므로 별도 identity. 단, current lifecycle 검증을 통과한 후보만 저장하며 ARRIVED 뒤 다른 차량의 ARRIVED를 추가 허용하지 않음 |
| 비활성화 후 재활성화 | generation 증가, 새 baseline/cycle. 이전 generation 후보는 stale |
| ACTIVE → ACTIVE 중복 활성화 | generation과 baseline/cycle 유지, 기존 identity를 바꾸지 않음 |
| FOLLOW_UP 중 새 활성화 | generation 증가 및 이전 runtime 제거. old ONE_STOP_AFTER는 stale이며 새 activation과 합치지 않음 |
| 같은 cycle의 서로 다른 Event Type | 다른 identity. before→arrival→after는 가능하고 lifecycle/precedence 규칙은 그대로 적용 |
| 동일 logical Event의 DB transaction 재시도 | 원래 candidate와 UUID를 그대로 재사용. rollback이면 전체 결정 재시도, 이미 commit됐으면 무변경 duplicate 처리 |
| 서버 재시작 | commit된 Event/Delivery는 DB에 남음. FOLLOW_UP은 persisted 원래 UUID로 복구. ACTIVE는 이전 history를 복원하지 않아 cycle 간 dedup 보장 범위 밖 |
| stale Scheduler 결과 | 삭제·generation 불일치·새 후보의 expected status 불일치·FOLLOW_UP vehicle/cycle 불일치는 lifecycle/Event/Delivery/memory 모두 변경하지 않음 |
| Alarm 삭제 | monitoring/follow-up 종료. 아래 삭제 불변조건을 충족하고, 삭제된 Alarm 후보로 Event/Delivery를 재생성하지 않음 |

#### FOLLOW_UP cycle continuity 결정

`alarms.follow_up_tracking_cycle_id` 하나에 ARRIVED candidate의 원래 UUID를 보존하는 최소안을 선택한다. 차량 ID는 관측 correlation, cycle UUID는 logical Notification identity이며 서로 대체하지 않는다. generation은 activation 경계를, UUID는 그 activation 안의 차량 cycle을 구분한다. ARRIVED → FOLLOW_UP → ONE_STOP_AFTER에서는 두 값 모두 유지한다.

차량 ID/시간으로 UUID를 다시 계산하는 대안은 기존 random UUID와 일치하지 않고 같은 차량의 다른 운행을 합칠 위험이 있다. NotificationEvent에서 ARRIVED를 역조회하는 대안은 runtime 복구를 Event 조회·보존 정책과 결합한다. UUID column 하나는 ACTIVE history를 저장하지 않고 기존 short-lived runtime의 수명과 함께 정리할 수 있으므로 선택한다.

TASK-707은 새 Migration과 Alarm mapping/invariant를 추가하고 기존 `startFollowUp(...)`/`clearFollowUpRuntime()` 및 `BusAlarmLifecycleService.applyArrival()`에서 원래 UUID를 저장·제거하도록 연결한다. TASK-708 implementation은 `VehicleTrackingState`의 기존 UUID를 받는 복원 경로와 `evaluateFollowUp()`을 연결한다. memory가 없으면 저장된 UUID로 state를 복원하고 새 UUID를 생성하지 않는다. memory가 있으면 그 UUID가 persisted 값과 같아야 한다. Observation freshness, successor 도달 판단, 5분 만료와 Phase 5 tracking 정책은 유지한다.

복원은 과거 Observation을 만들거나 ARRIVED를 다시 내는 작업이 아니다. fresh한 현재 Observation으로 after 후보를 판단하고, 적용 직전 current Alarm의 generation·FOLLOW_UP status·vehicle ID·cycle UUID·미만료 runtime을 재검증한다. 값이 없거나 서로 다르면 새 UUID로 보정하지 않고 적용을 거부해 불변조건 오류로 관찰한다. TASK-707/708 구현·검증 전에는 restart-safe continuity가 완성된 것으로 취급하지 않는다.

#### Atomic uniqueness와 중복 처리 선택

Alarm row lock으로 같은 Alarm의 결정 transaction을 직렬화하고, exact logical identity의 current read로 이미 commit된 결정을 무변경 처리한다. INSERT의 DB UNIQUE를 최종 방어선으로 유지한다. 사전 SELECT만으로 보장하지 않고 Event/Delivery의 중복 insert를 허용하는 memory cache도 사용하지 않는다.

JPA insert/flush의 UNIQUE 실패는 전체 lifecycle/Event/Delivery transaction을 rollback한 뒤 transaction 밖에서 분류한다. 실패한 persistence context를 재사용하지 않고 새 transaction에서 동일 identity와 current Alarm을 다시 확인한다. 기존 Event가 확인된 정상 duplicate는 Event, payload, recipient set, Delivery state, Alarm lifecycle을 변경하지 않는다. Delivery UNIQUE/FK/CHECK 등 다른 오류를 정상 Event duplicate로 숨기지 않는다. 구체 순서와 Scheduler memory 반영 조건은 Architecture가 소유한다.

Blind upsert/REPLACE는 기존 decision을 변경·대체할 수 있고 INSERT IGNORE는 다른 데이터 오류도 숨길 수 있어 사용하지 않는다. Event만 독립 transaction으로 저장하는 방식도 lifecycle과 fan-out 원자성을 깨뜨려 제외한다. 이 선택은 기존 JPA와 짧은 transaction으로 구현하며 dependency나 generic retry framework를 추가하지 않는다.

#### Alarm 삭제에 대한 후속 결정 제약

현재 NotificationHistory의 cascade는 Phase 7 Event/Delivery 정책으로 자동 승계하지 않는다. 보존·명시적 취소·삭제의 최종 정책과 FK delete action은 TASK-707이 결정하며 이번에는 다음 불변조건만 확정한다.

- 살아 있는 Alarm/current generation에서 재제출 가능한 identity의 기록을 삭제해 dedup을 재허용하지 않는다. Event를 보존하면 `alarmId` 등 네 identity 값과 Event owner를 그대로 보존하며, nullable Alarm association을 identity column으로 대신하지 않는다.
- 삭제 transaction은 기존 Alarm row lock과 조율해 monitoring/follow-up 및 이미 생성된 pending Delivery의 처리 방침을 원자적으로 확정한다. Event/Delivery를 유지할 경우 worker가 삭제된 Alarm의 pending Delivery를 계속 보낼지 취소할지 명시해야 하고, 취소는 Provider 성공/실패로 꾸미지 않는다. worker와 deletion 사이의 진행 중 FCM request 회수는 보장하지 않는다.
- pending retry/recovery가 의존하는 Event/Delivery와 identity는 해당 작업이 끝나거나 명시적으로 취소되기 전에 우연히 cascade로 사라지면 안 된다. 삭제안을 선택하면 pending 처리를 먼저 원자적으로 종료하고 deleted Alarm 후보를 영구 거부해야 한다. Alarm ID를 다른 Alarm에 재사용하거나 남은 candidate로 기록을 재생성하지 않는다.
- Alarm 삭제·재활성화와 Provider delivery 결과가 Alarm lifecycle을 되살리지 않는다. 기존 Event의 재제출은 누락 Delivery 복구나 새 Device fan-out의 계기가 아니다. 보존기간·장기 Analytics 정책은 여기서 정하지 않는다.

#### 후속 최소 구현과 검증 분담

| Task | 구현 책임과 최소 correctness 검증 |
| --- | --- |
| TASK-707 | NotificationEvent/Delivery Entity·Schema·Repository와 두 UNIQUE, durable persistence; FOLLOW_UP UUID column·CHECK·Alarm operation·ARRIVED 저장 연결; 기존 History 전환 및 삭제 정책/FK 확정. NULL/invalid identity·동일 key 거부·다른 type/cycle 허용·Event×Device UNIQUE·runtime 완전성·선택한 삭제 정책의 persistence 검증 |
| TASK-708 implementation | 선택한 TransitEvent + AlarmEvaluationKey를 durable Event로 연결; lifecycle/Event/최초 recipient Delivery transaction·무변경 duplicate·전체 rollback/retry; FOLLOW_UP UUID 복원과 Scheduler commit 이후 memory 반영. 동시 동일 candidate·중복 ARRIVED/after·generation 증가/유지·stale/deleted 결과·실패 rollback·동일 UUID retry·FOLLOW_UP restart continuity·multi-device/0-device/늦은 등록 검증 |
| TASK-706 | 이미 commit된 pending Delivery의 단일 durable dispatch worker와 전송 직전 recipient 재검증. Event 결정/fan-out transaction을 다시 구현하지 않음 |
| TASK-709 | 아래 failure/retry/expiry 설계 계약 적용, 이후 result 처리·bounded retry·freshness·conditional cleanup 구현. 최종 횟수·간격·TTL은 smoke/latency 검증 후 확정 |

실행 순서는 [Phase 7 dependency](../task-list.md#phase-7---notification)를 유지한다. TASK-708 설계만 완료했고 전체 Task는 미완료다. 이번 단계에는 code/Entity/Repository/Migration/test를 추가하지 않으며 테스트 실행 없이 문서 논리·코드 대조·링크·diff만 검증한다.

### Durable dispatch와 persistence 역할

Phase 7은 MySQL 기반 durable pending Notification dispatch/outbox를 사용한다.

```text
Provider I/O / Evaluation
  → DB transaction
     - current Alarm lifecycle/generation 검증
     - lifecycle transition
     - durable logical NotificationEvent insert
     - 현재 eligible Device별 Delivery 생성 (유효 PENDING / 이미 만료 EXPIRED)
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

Provider acceptance는 실제 사용자 표시 성공이 아니다. Delivery lifecycle status는 `PENDING`, `ACCEPTED`, `FAILED`, `EXPIRED`를 기본 방향으로 하고 retry는 `PENDING + attemptCount + nextAttemptAt + expiresAt`으로 표현한다. Provider result는 `ACCEPTED`, `INVALID_TARGET`, `RETRYABLE`, `CONFIGURATION`, `PERMANENT_REQUEST`, `AMBIGUOUS_TIMEOUT`으로 lifecycle status와 분리한다. `EXPIRED`는 Provider result가 아니라 local freshness 종료 의미다.

Invalid/unregistered 결과는 attempt의 identity/owner/generation/revision/FID가 current registration과 모두 일치할 때만 조건부 disable한다. Old attempt 실패가 새 registration을 disable해서는 안 된다. `AMBIGUOUS_TIMEOUT`은 Provider가 실제 접수했는지 알 수 없는 상태다. FCM accepted 뒤 DB update 전 crash하면 Delivery가 PENDING으로 남아 restart 뒤 retry될 수 있으며, 실제 Device duplicate는 exactly-once 비보장 계약으로 허용한다. TASK-709의 구체 계약은 다음 절이 소유하며 최종 retry 수치는 smoke/latency 검증까지 보류한다. Generic retry framework는 도입하지 않는다.

### TASK-709 failure/retry/expiry 설계 (2026-10-09)

**설계 완료, 구현 미완료**다. TASK-701/702 완료 및 TASK-708 설계를 유지하고 두 UNIQUE, 실행 dependency와 V1 single fixed-delay/non-overlapping worker를 변경하지 않는다. 이번 단계는 문서만 변경하며 Backend/Flutter test·build, Docker, Firebase 실전송, SDK 설치와 Migration 실행을 하지 않는다. [Domain](../domain-model.md#notificationdelivery)은 invariant, [Database](../database.md#notificationdelivery-operational-schema-task-709)는 column/NULL/CHECK/query/index, [Architecture](../architecture.md#notification-dispatch와-result-transaction-task-709)는 실행 경계를 소유한다.

#### 조사한 실제 구현과 SDK 경계

현재 `notification.entity.Device`와 V13에는 owner, nullable current FID, revision, ownership generation, enabled가 있다. 등록/disable mutation과 API는 아직 없다. `NotificationHistory`/Repository/V3/V9는 Alarm별 SUCCESS/FAILURE와 삭제 cascade만 제공한다. Event/Delivery Entity·Migration·Worker·Firebase Admin dependency와 production 전송은 없다. `BusAlarmLifecycleService`는 Alarm row lock/generation/status와 lifecycle만 갱신하고 Event/Delivery 원자 저장은 TASK-708 implementation의 후속 작업이다. TASK-708에서 발견한 FOLLOW_UP UUID/ACTIVE restart의 기존 한계도 그대로 남으며 이번 설계가 이를 해결했다고 주장하지 않는다.

조사 기준은 기존 선택 후보 **Firebase Admin Java 9.11.0**, FCM HTTP v1 `message.fid`다. 성공한 단일 `FirebaseMessaging.send(Message)`는 message ID를 반환한다. 실패는 `FirebaseMessagingException.getMessagingErrorCode()`(nullable), inherited `getErrorCode()`, `getHttpResponse()`(nullable), `getCause()`로 조사한다. HTTP response가 있으면 `getStatusCode()`, `getHeaders()`, `getContent()`가 있지만 structured `getFieldViolations()`, `isRetryable()`, 요청 전송 완료 여부 field는 없다. Node의 `messaging/*` 문자열 오류를 Java enum으로 만들지 않는다. FcmError 외 BadRequest/QuotaFailure detail은 SDK typed accessor가 아니며 필요할 때 TASK-705에서 bounded parsing으로 검사하고 원문을 저장/출력하지 않는다.

#### FCM failure mapping 계약

아래는 StopBell의 보수적 분류 결정이다. HTTP code와 FCM-specific code가 모순되거나 응답이 불완전하면 INVALID_TARGET cleanup 근거로 사용하지 않는다. 우선 정상 성공 반환, 신뢰 가능한 FCM error detail, generic SDK code/HTTP status, 실제 cause/context 순서로 평가하되 성공 응답 decoding 실패를 명확한 rejection으로 꾸미지 않는다.

| HTTP v1 / 응답 근거 | Admin Java 9.11.0에서 실제 얻는 정보 | StopBell result / 자동 retry |
| --- | --- | --- |
| 성공 response의 message name | `send()`의 정상 message ID 반환 | ACCEPTED / 없음 |
| 404 + FcmError `UNREGISTERED` | MessagingErrorCode.UNREGISTERED, generic NOT_FOUND 및 HTTP response | INVALID_TARGET / 없음. 해당 전송 target의 등록 해제 근거 |
| 400 + INVALID_ARGUMENT, BadRequest payload/field 위반; local Message validation 오류 | MessagingErrorCode.INVALID_ARGUMENT 또는 generic INVALID_ARGUMENT; local validation은 IllegalArgumentException일 수 있음 | PERMANENT_REQUEST / 없음 |
| 400 + FcmError INVALID_ARGUMENT만 있거나 target/payload 원인이 불명확 | INVALID_ARGUMENT만으로 target 오류 위치는 알 수 없음 | PERMANENT_REQUEST / 없음, `INVALID_ARGUMENT_UNCLASSIFIED`. Device disable 금지 |
| 429 + QUOTA_EXCEEDED, rate limit | MessagingErrorCode.QUOTA_EXCEEDED 또는 generic RESOURCE_EXHAUSTED + HTTP 429 | RETRYABLE / budget·expiry·Provider delay 만족 시만 |
| 500 INTERNAL, 503 UNAVAILABLE 또는 명확한 일시 service failure | MessagingErrorCode.INTERNAL/UNAVAILABLE, generic INTERNAL/UNAVAILABLE, HTTP response | RETRYABLE / 동일 제한 |
| 401 UNAUTHENTICATED, 403 PERMISSION_DENIED/SENDER_ID_MISMATCH, APNs auth 오류 | generic UNAUTHENTICATED/PERMISSION_DENIED 또는 MessagingErrorCode.SENDER_ID_MISMATCH/THIRD_PARTY_AUTH_ERROR | CONFIGURATION / 없음. 프로젝트·권한·APNs 설정 조사, Device disable 금지 |
| APNS_AUTH_ERROR detail | SDK parser는 THIRD_PARTY_AUTH_ERROR로 정규화 | CONFIGURATION / 없음 |
| bare 404 NOT_FOUND, project/endpoint를 찾을 수 없음 | generic NOT_FOUND, MessagingErrorCode가 null일 수 있음 | CONFIGURATION / 없음, `NOT_FOUND_UNCLASSIFIED`. UNREGISTERED로 추정하지 않음 |
| credential/project 초기화 실패로 send 불가 | 초기화 exception이며 반드시 FirebaseMessagingException인 것은 아님 | CONFIGURATION / 없음. global 초기화 불가일 때 worker는 FCM을 호출하지 않고 미만료 PENDING을 보존하되 expiry sweep은 계속 가능해야 함 |
| DNS/NoRouteToHost 등 **요청 미전송이 확인된** 연결 실패 | cause와 generic UNAVAILABLE. code만으로 phase는 확정 불가 | RETRYABLE / 제한 적용 |
| request 시작 후 socket timeout, 응답 전 connection reset/EOF, 성공 응답 parsing 실패 또는 전송 여부 불명인 I/O | SocketTimeout cause는 generic DEADLINE_EXCEEDED; 다른 IOException은 UNKNOWN일 수 있음. cause/nullable response 조사 | AMBIGUOUS_TIMEOUT / duplicate 가능성을 인정한 제한적 retry |
| 그 외 거절 응답/미분류 protocol 오류 | generic UNKNOWN 또는 null FCM code, 제한된 HTTP 정보 | 명확한 rejection은 PERMANENT_REQUEST(`UNCLASSIFIED_REJECTION`), 접수 여부 불명은 AMBIGUOUS_TIMEOUT(`UNKNOWN_ACCEPTANCE`). 임의 INVALID_TARGET 금지 |

특히 generic DEADLINE_EXCEEDED가 connect/read timeout을 구분한다고 가정하지 않는다. 검증 가능한 미전송 근거가 없으면 ambiguous다. 400의 `FcmError.INVALID_ARGUMENT` 자체도 payload와 target을 안전하게 구분하는 충분조건이 아니다. V1 cleanup allowlist는 현재 공식 근거로 확인 가능한 UNREGISTERED로 제한한다. 추후 FID-specific detail로 확대하려면 공식 근거와 redacted correctness fixture가 먼저 필요하다. legacy token troubleshooting 설명을 FID의 모든 400/404에 일반화하지 않는다.

공식 조사 근거(2026-10-09, runtime 미검증):

- [FCM HTTP v1 error 계약](https://firebase.google.com/docs/cloud-messaging/error-codes): HTTP와 details의 차이 및 FCM error 의미
- [선택 SDK Message](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/messaging/Message.java), [Client](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/messaging/FirebaseMessagingClientImpl.java), [Exception](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/messaging/FirebaseMessagingException.java): FID/send/exception 계약
- [MessagingErrorCode](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/messaging/MessagingErrorCode.java), [error DTO parser](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/messaging/internal/MessagingServiceErrorResponse.java): 7개 SDK enum, nullable FcmError mapping과 APNS alias
- [HTTP/I/O handler](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/internal/AbstractHttpErrorHandler.java), [IncomingHttpResponse](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/IncomingHttpResponse.java): 실제 generic code/cause/headers, typed error detail의 한계

#### Provider result → Delivery 결정표

결과는 원래 Event×Device row에만 적용한다. terminal은 무변경이고 ACCEPTED 응답을 받았으면 기한이 지났더라도 확인한 접수를 ACCEPTED로 기록한다. 비접수 결과 및 새 호출 전에는 **실제 expiry → attempt limit → 비재시도 실패/recipient 조건 → retry 예약** 순서로 판단한다. `FAILED`는 확인된 미접수만 의미하지 않으며 crash/ambiguous 뒤 budget 소진도 포함한다.

| Provider result / local decision | Retryable | next status | nextAttemptAt | Device cleanup | terminal |
| --- | --- | --- | --- | --- | --- |
| ACCEPTED | 아니오 | ACCEPTED | null | 없음 | 예 |
| INVALID_TARGET | 아니오 | 미만료 FAILED; 이미 기한 종료면 EXPIRED | null | 아래 current-registration 조건부 disable만 | 예 |
| RETRYABLE | 조건부 | 기한 종료 EXPIRED; budget 소진 FAILED; 그 외 PENDING | 아래 계산값 또는 expiresAt | 없음 | FAILED/EXPIRED일 때 |
| CONFIGURATION | 아니오 | 미만료 FAILED; 기한 종료 EXPIRED | null | 없음, 설정 진단만 | 예 |
| PERMANENT_REQUEST | 아니오 | 미만료 FAILED; 기한 종료 EXPIRED | null | 없음 | 예 |
| AMBIGUOUS_TIMEOUT | 조건부 | 기한 종료 EXPIRED; budget 소진 FAILED; 그 외 PENDING | 중복 위험을 수용한 지연값 또는 expiresAt | 없음 | FAILED/EXPIRED일 때 |
| local EXPIRED | 아니오 | EXPIRED | null | 없음 | 예 |
| local recipient/dispatch 불가 | 아니오 | 미만료 FAILED; 기한 종료 EXPIRED | null | 없음 | 예 |
| local ATTEMPT_LIMIT_REACHED | 아니오 | 미만료 FAILED; 기한 종료 EXPIRED | null | 없음 | 예 |

local 종료 시 Provider 결과가 없으면 null을 유지한다. 이전 attempt의 결과가 있으면 보존하면서 안전한 local code(`FRESHNESS_EXPIRED`, `ATTEMPT_LIMIT_REACHED`, `RECIPIENT_OWNER_CHANGED`, `RECIPIENT_GENERATION_CHANGED`, `RECIPIENT_DISABLED`, `RECIPIENT_TARGET_MISSING`, `DISPATCH_NOT_ALLOWED`)로 종료 이유를 구분한다. result가 실제로 없는 recovery에 가짜 Provider 응답을 만들지 않는다. INVALID_TARGET response가 기한 뒤 도착해 EXPIRED로 종료돼도 명확한 등록 해제 근거의 조건부 cleanup은 가능하다.

CONFIGURATION으로 실제 attempt가 실패한 개별 Delivery는 terminal이며 운영자가 설정을 고쳐도 자동 PENDING 복귀하지 않는다. 운영자는 redacted 설정 오류 log/metric으로 Firebase credential/project/권한/APNs를 점검한다. Provider 초기화 자체가 불가능하면 아직 시작하지 않은 row들을 가짜 attempt/실패로 대량 갱신하지 않는다. 새로운 미만료 Delivery는 설정 복구 뒤 처리할 수 있고 오래된 row는 expiry로 종료한다. global circuit breaker나 별도 운영 상태 subsystem은 만들지 않는다.

#### Bounded retry와 attempt accounting

- `maxAttempts`는 **최초 포함 총 durable attempt 시작 한도**이며 양수·유한 값이다. 추가 retry 최대치는 maxAttempts-1이다. 최초 외부 호출 직전의 짧은 transaction에서 count를 1 증가시키고 lastAttemptAt/revision 및 recovery 예약을 commit한다. commit을 확인하지 못하면 FCM을 호출하지 않는다. 호출 완료/timeout 후에는 다시 count를 증가시키지 않는다. commit 전 crash는 증가 없음, commit 후 호출 전 crash는 호출하지 않았어도 한 번 소비된다. 이 보수적 손실을 감수해 process crash 반복으로 budget을 우회하지 못하게 한다.
- 결과 미저장 crash에 대비해 시작 commit 시 `nextAttemptAt=min(expiresAt, attemptStartedAt + requestBudget + recoveryDelay)`를 저장한다. requestBudget은 한 SDK 호출 전체의 유한 wall-time budget, recoveryDelay는 최소 retry delay 이상이다. 이는 lease나 실행 중 상태가 아니라 같은 PENDING row의 재조회 예약이다. 네트워크 시각과 DB 시각 사이 작은 간격은 관측 한계로 남고 결과가 정상 도착하면 확정 retry 시각으로 교체한다.
- 반환된 retryable 결과의 `candidateNextAt = resultReceivedAt + max(localBoundedDelay(attemptCount), providerMinimumDelay, retryAfterDelay)`로 계산한다. local delay는 작은 유한 설정으로 capped exponential 증가와 음수가 아닌 bounded jitter를 적용하고 범용 framework를 만들지 않는다. jitter가 Provider 최소 지연을 줄이지 않아야 한다. count/기한을 먼저 확인하며 candidateNextAt>=expiresAt이면 지금 EXPIRED라고 꾸미지 않고 PENDING + nextAttemptAt=expiresAt으로 예약해 실제 만료 시 local 종료한다. 기한을 연장하거나 Retry-After를 잘라 더 일찍 보내지 않는다.
- `Retry-After`는 존재하는 HTTP response headers에서만 읽는다. delta seconds와 HTTP-date를 UTC로 해석하고 result receive time 기준 non-negative delay로 정규화한다. 과거 날짜/0도 local minimum을 우회하지 못하며 malformed/누락은 Provider 최소와 local delay를 사용한다. overflow/지나치게 먼 값은 기한 안 retry 불가로 처리하고 즉시 retry하지 않는다. raw header/body는 persistence하지 않고 계산된 nextAttemptAt만 commit한다.
- RETRYABLE은 일시적 거절/확인된 미전송이고 AMBIGUOUS_TIMEOUT은 접수 불명이다. 둘 다 유한 budget/expiry를 쓰지만 ambiguous를 즉시 resend하지 않으며 설정 delay는 일반 retry 이상이다. 전송 dedup key가 있다고 exactly-once로 간주하지 않는다. 마지막 attempt가 ambiguous이고 budget을 소진하면 FAILED로 종료하되 불명확한 접수 결과를 보존한다.
- 정상 지연 재시도는 worker를 sleep시키지 않고 DB nextAttemptAt으로 예약한다. 앱 재시작은 Backend budget/기한을 reset하지 않는다. Backend restart도 같은 row의 count/next/expiry를 읽으며 terminal은 복구 대상이 아니다. 각 Device는 독립 budget/result를 사용하고 성공 Device를 실패 Device와 함께 재전송하지 않는다.

최종 maxAttempts, local base/cap/jitter, recoveryDelay, requestBudget/timeout, Type별 freshness TTL 및 polling/batch 값은 **TASK-705 Backend→iPhone smoke, TASK-709 implementation 및 latency 검증까지 보류**한다. Schema는 수치와 독립적으로 구현 가능하다. smoke 없이 개발 후보값을 운영 기본값으로 기재하지 않는다.

FCM 공식 [retry 권고](https://firebase.google.com/docs/cloud-messaging/scale-fcm#handling_retries)는 timeout/재시도 최소 대기를 제시하고, [error 문서](https://firebase.google.com/docs/cloud-messaging/error-codes)는 quota의 최소 초기 1분 및 UNAVAILABLE의 Retry-After 존중을 설명한다. V1 설계는 일반 실패/ambiguous에 적어도 10초 대기, quota에 적어도 60초 대기를 하한으로 삼고 더 큰 header를 존중한다. 이는 StopBell 운영 최종 interval/TTL을 정한 것이 아니다. 버스 freshness 안에 그 지연을 수용할 수 없으면 retry를 포기하고 기한에 종료한다. latency를 낮추려고 Provider 제한을 어기지 않는다.

#### SDK 내부 retry와 후속 검증 gate

선택 SDK의 [ApiClientUtils](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/internal/ApiClientUtils.java)는 기본 503 retry 최대 4회·최대 간격 60초를 구성한다. [RetryConfig](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/internal/RetryConfig.java), [RetryInitializer](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/internal/RetryInitializer.java), [Retry-After handler](https://github.com/firebase/firebase-admin-java/blob/v9.11.0/src/main/java/com/google/firebase/internal/RetryUnsuccessfulResponseHandler.java)는 SDK 내부 대기/재전송을 수행한다. 따라서 `attemptCount` 한 번이 HTTP 한 번이라고 말할 수 없다. 인증 계층의 재전송도 별도 확인 대상이다.

기본 내부 retry를 그대로 겹치면 freshness 이후 **새 HTTP 요청**이나 이중 backoff가 발생할 수 있어 위 single-attempt 지연 계약을 충족한다고 주장하지 않는다. TASK-705는 내부 재전송을 억제하거나 각 실제 FCM 요청 직전 deadline/budget을 적용할 수 있는 지원된 방법을 확인해야 한다. FirebaseOptions에 존재하지 않는 retry setter나 exception field를 가정하지 않고 internal class/reflection 의존을 미리 결정하지 않는다. 안전한 방법이 확인되기 전 production dispatch를 활성화하지 않는다. 별도 retry layer를 덧붙여 성공 처리하는 것은 대안이 아니다.

지원된 제어가 불가능하면 안전한 최소 대안은 현재 google-api-client/credential 경계를 이용한 단일 HTTP v1 전송 adapter이며, **Admin SDK client 선택 변경은 TASK-705에서 개발자 판단 후** 문서 동기화한다. 이번에 adapter를 교체·구현하지 않는다. TASK-707의 Schema와 retry 결정표는 이 전송 제어 검증과 독립적으로 진행할 수 있다. 기본 SDK와 엄격한 expiry의 충돌은 명시된 구현 gate이지 이미 해결된 기능이 아니다.

#### Freshness와 local expiry

네 Type 모두 freshness가 필요하다. ONE_STOP_BEFORE는 도착 전 안내, ARRIVED는 목표 도착, PASSED는 최근 통과 위치, ONE_STOP_AFTER는 후속 위치 안내이므로 늦게 보내면 사용자를 오도한다. Type→양수 TTL의 작은 policy를 사용하되 값이 서로 달라야 한다고 미리 확정하지 않는다. FOLLOW_UP의 기존 5분 tracking timeout과 Notification TTL은 다른 책임이며 그대로 재사용하지 않는다.

계산 기준은 `TransitEvent.observedAt`(StopBell response receive time)이다. providerDataTime은 optional upstream freshness 근거, eventDetectedAt은 실제 evaluation candidate 선택 시각, notificationEventCreatedAt은 DB row 생성 시각, deliveryAttemptAt은 attempt 시작 시각이다. DB insert/retry/restart 때 observedAt을 now로 바꾸지 않고 `expiresAt = originalObservedAt + TTL(eventType)`를 최초 결정에서 고정한다. DB 생성 시각 기준이면 queue/evaluation 지연을 숨기고 늦은 알림의 유효기간을 늘리므로 제외한다. 물리 Event 시각이 제공되지 않으면 추정해서 저장하지 않는다. Phase 5 stale/UNKNOWN 판단도 변경하지 않는다.

fresh candidate가 늦게 durable commit되어 만료됐다면 lifecycle/logical Event와 원래 recipient set은 보존하고 각 Delivery를 count=0 EXPIRED로 생성한다. 만료를 이유로 ARRIVED lifecycle을 되돌리거나 Event를 없애 dedup을 재허용하지 않는다. 기존 PENDING은 조회 시와 실제 Provider 호출 직전에 UTC Clock으로 `now >= expiresAt`을 재확인한다. 재시작 후 오래된 pending도 동일하게 종료한다.

전송 시작 뒤 기한이 지나도 이미 FCM에 접수/처리 중인 요청을 local expiry로 취소하지 못한다. 명확한 성공 응답은 ACCEPTED와 실제 response receive timestamp를 기록한다. 실패/ambiguous 후 기한이 끝났다면 EXPIRED이며 추가 호출은 금지한다. 단일 worker는 자기 진행 중 요청과 별도 expiry mutation을 경쟁시키지 않는다. TASK-707의 명시적 삭제가 먼저 종료한 row에는 late result로 상태를 되살리지 않는다. 여러 Device가 ACCEPTED/FAILED/EXPIRED로 갈리는 것은 정상이다.

Local expiry는 FCM/APNs queue나 OS 표시 deadline을 보장하지 않는다. 기본 Provider 보존기간에 의존하면 오래된 표시가 가능하므로 TASK-705에서 [APNs expiration/FCM lifespan](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan)을 expiresAt에 맞추는 전송 옵션도 확인해야 한다. 실제 iPhone의 지연/표시는 TASK-710/latency에서 검증하며 Provider 옵션이 취소 API인 것처럼 취급하지 않는다.

#### Recipient 재검증과 invalid-target cleanup

Event owner와 Event 시점 `recipientOwnershipGeneration`을 저장한다. revision/FID를 Event 시점에 고정하지 않는 대안은 같은 installation의 정상 rotation에서 불필요한 실패를 줄인다. 대신 owner/generation을 고정해 재로그인·소유권 회귀가 과거 Event를 다른 ownership 세대에 보내는 것을 막는다.

| 전송 직전 상황 | 처리 |
| --- | --- |
| current owner != Event owner | local FAILED, OWNER_CHANGED, 호출/Device 변경 없음 |
| owner가 다시 같아도 generation != recipient generation | local FAILED, GENERATION_CHANGED. A→B→A의 과거 Event도 전송 금지 |
| disabled | local FAILED, DISABLED. 재활성화를 기다리며 기한을 늘리거나 terminal을 복귀시키지 않음 |
| current FID null/invalid row | local FAILED, TARGET_MISSING 및 invariant 진단. fake Provider result 없음 |
| 같은 owner/generation의 FID rotation·높은 revision 재등록 | 현재 enabled 등록의 current FID/revision으로 attempt snapshot. 원래 Device identity/Delivery 그대로 유지 |
| 이전 등록이 INVALID_TARGET 처리됨 | 해당 Delivery terminal 유지. 아직 PENDING인 다른 Event row는 현재 Device 조건으로 재검증 |
| Event 후 새 Device 등록 | 새 Delivery/fan-out 없음 |
| Event/Delivery 삭제·명시적 dispatch 종료 | 호출 중단/무변경. terminal 또는 삭제 row를 재생성하지 않음 |
| Alarm/User 삭제 관련 결정 대기 | TASK-707이 확정할 lifecycle 정책으로 dispatch 허용 여부 판단. 우연한 FK cascade나 현재 Alarm INACTIVE만으로 판단하지 않음 |

모든 local 탈락에는 먼저 실제 expiry를 적용한다. ARRIVED의 정상 Alarm 비활성화는 그 Event 전달 실패 근거가 아니며 deletion 정책과 혼동하지 않는다. 보존·취소·삭제와 FK 최종 선택은 TASK-707에 남긴다. 보존해서 계속 보내는 정책이면 불변 Event owner/evidence를 쓰고, dispatch 종료 정책이면 pending을 원자적으로 종료/삭제하며 `DISPATCH_NOT_ALLOWED` local 진단으로 Provider 실패와 구분한다. explicit 취소를 시간 EXPIRED로 꾸미거나 CANCELLED lifecycle을 이번에 추가하지 않는다. 삭제 정책이 결정되지 않은 상태로 TASK-707 구현을 완료 처리하지 않는다.

UNREGISTERED 결과의 cleanup은 **짧은 Device PESSIMISTIC_WRITE transaction 안의 current locking read + 검증 + disable**로 수행한다. `{deviceId, eventOwnerId, attemptOwnershipGeneration, attemptRegistrationRevision, attemptCurrentPushTargetId}`와 현재 row의 identity/owner/generation/revision/FID 및 enabled=true를 모두 비교한다. FID 비교는 case-sensitive exact 값이다. lock을 유지한 상태에서만 enabled=false, currentPushTargetId=null 및 updatedAt을 갱신한다. TASK-703 등록/ownership transaction도 같은 Device write lock을 사용하므로 사전 SELECT 뒤 별도 UPDATE와 다르다. CAS/@Version framework나 새 DB lock scheme은 추가하지 않는다.

AAA/r5 요청 중 BBB/r6가 등록됐으면 불일치로 cleanup no-op이고 원래 Delivery는 INVALID_TARGET terminal 처리된다. ownership 이전도 불일치로 no-op이며 다른 Device/FID row를 찾아 disable하지 않는다. revision/generation/lastRegisteredAt/owner/credential은 Provider가 변경하지 않는다. 결과 transaction rollback이면 Delivery 종료와 cleanup도 함께 rollback한다. 삭제로 Delivery가 이미 없어지거나 terminal이면 late result cleanup까지 생략한다.

Provider cleanup은 Client revision을 증가시키지 않으므로 같은 revision의 이전 enabled 등록 재제출은 [기존 API](../api.md#기기-등록해제-계약-task-702)의 동일 결과 상태를 충족하지 못해 conflict다. TASK-703/704는 authenticated state 조회와 local counter max 동기화 뒤 **더 높은 revision**으로 현재 readiness/FID를 재등록한다. generation/credential은 유지하고 stale 요청을 자동 재활성화로 해석하지 않는다. 높은 revision으로 동일 FID 재등록도 허용하지만 원래 terminal Delivery는 재전송하지 않는다.

사전 검증 직후 logout/rotation/takeover와 실제 I/O 사이 경쟁은 남는다. 새 호출의 snapshot은 검증 시 current owner의 값이지만 전송 중 owner가 바뀔 수 있으며 네트워크 내내 DB lock을 잡지 않는다. 이전 owner의 전송이 이미 시작/접수됐다면 회수할 수 없다. 최소 payload와 tap의 현재 Auth/ownership 재검증으로 노출 범위를 줄이며 절대적인 전송 순간 ownership 동기화를 보장했다고 주장하지 않는다.

#### Crash/restart와 safety 경계

아래에서 E는 불변 expiresAt, S는 마지막 preflight의 메모리 registration snapshot이다. 시작 commit 후에는 status=PENDING, count 증가, lastAttemptAt/revision 기록, nextAttemptAt은 recovery 예약이고 마지막 결과는 null이다. restart에는 S가 없으므로 과거 결과 cleanup을 추정 수행하지 않는다.

| 장애 | 저장 상태 / 다음 처리 | cleanup / 한계 |
| --- | --- | --- |
| A. 호출 전 crash | 시작 commit 전이면 count/예약 그대로; 직후면 count 1회 소비, PENDING과 recovery 예약/E 유지. due 때 expiry→budget→새 preflight | 호출하지 않았어도 budget 손실 가능, cleanup 없음 |
| B. 호출 중 crash | 결과 미저장 PENDING/count/예약/E/revision 유지, S 유실. due recovery에서 새 snapshot | 이전 접수 여부 불명, 기한/budget 내 duplicate 가능 |
| C. accepted 뒤 DB 반영 전 crash | B와 같은 DB 상태. 성공했다는 사실을 추정해 ACCEPTED로 만들지 않음 | duplicate 가능; DB update rollback도 동일. count 한도로 무한 반복 차단 |
| D. retry commit 뒤 restart | PENDING/count/last result와 확정 nextAttemptAt/E 유지. due 전 호출 없음 | terminal reset 없음, 기존 S 재사용 없음 |
| E. 요청 중 FID rotation | 원래 attempt의 count/S로 결과 기록; retry 가능하면 다음 attempt는 새 current 등록 | old INVALID_TARGET은 revision/FID 불일치로 cleanup no-op |
| F. 요청 중 ownership 이전 | 원래 Event Delivery 결과만 처리; 다음 preflight는 generation/owner 불일치로 종료 | old cleanup no-op. 이미 시작한 전송 회수 불가 |
| G. expired 후보 재조회 | PENDING이면 EXPIRED + null next, count/E 유지; terminal EXPIRED는 query 제외 | Provider 호출/cleanup 없음 |
| H. retry 중 Event/Alarm 삭제 | TASK-707의 원자 dispatch 종료/보존 정책 적용. 삭제/terminal row는 no-op, 보존·허용 row만 기한 내 재검증 | late 결과로 lifecycle/row 재생성 금지, FK 정책 미결정 유지 |
| I. 오래 중단 뒤 restart | next<=E invariant로 오래된 pending도 due sweep 대상. expired 우선 종료, 미만료 row만 남은 budget 사용 | now/TTL/count를 reset하지 않음 |
| J. 일부 Device만 성공 | ACCEPTED는 유지, 나머지 row만 독립 retry/FAILED/EXPIRED | recipient set 재선정/성공 Device 재전송 없음 |

Result commit 응답 유실 때는 FCM을 다시 보내지 않고 새 DB transaction에서 동일 row/count/status를 읽어 같은 메모리 결과만 재적용한다. 이미 기록됐으면 no-op이고 Device cleanup/timestamp를 반복 갱신하지 않는다. DB 장애 동안 worker는 다음 FCM 호출을 시작하지 않는다. 제한된 DB 재반영도 실패하면 dispatch cycle을 종료하고 durable PENDING recovery에 맡긴다. 결과를 영속하기 전 process가 죽으면 response/Retry-After/S가 유실되므로 받은 header의 완전한 crash-safe 복원이나 정확한 외부 attempt 이력은 보장하지 못한다. 알려진 header는 정상 결과 commit에서 예약으로 보존하고 유실 결과는 recovery 지연과 expiry/budget으로 제한한다.

SENDING/claim/lease 없이 보장하는 것은 DB identity uniqueness, terminal 자동 복귀 금지, durable 시작 count와 expiry로 제한된 SDK 호출, 단일 worker 안의 non-overlap, current-registration 조건부 cleanup이다. process crash의 외부 접수 확인, exactly-once 요청/표시, 여러 Backend 동시 dispatch, 검증 이후 변경의 완전 차단은 보장하지 않는다. SDK 내부 재전송 제어 gate를 통과하기 전 실제 HTTP 요청별 freshness/budget 보장도 미검증이다.

#### Logging, metric과 구현 분담

운영 로그는 eventId/deliveryId, 정규화 result/code, attemptCount, elapsedMs, retryScheduled, expired 정도의 필요한 문맥만 기록한다. 원문 exception message/stack을 무조건 출력하지 않는다. SDK message가 raw response를 포함할 수 있어 allowlist code로 변환한다. Firebase credential, Access/Refresh Token, raw FID/APNs token/installationId, installation credential/hash, raw HTTP error body/민감 response, 불필요한 GPS는 출력·저장하지 않는다. 미분류 detail도 bounded in-memory 진단 후 안전한 code만 남긴다.

최소 metric 후보는 accepted 수, result category별 실패 수, retry 예약 수, final FAILED 수, EXPIRED 수, pending backlog, oldest pending age 및 FCM invocation latency다. backlog는 모든 PENDING을 포함하고 oldest age는 Delivery createdAt 기준 queue age이며 Event observedAt 기반 end-to-end age와 구분한다. category/status/eventType 정도만 bounded label로 쓰고 eventId/deliveryId/deviceId/userId/FID 등의 high-cardinality identifier는 label에 넣지 않는다. Micrometer/Actuator 설정이나 수집 구현을 이번 단계에 추가하지 않는다.

| Task | 후속 책임 / 필요한 검증 |
| --- | --- |
| TASK-705 | Admin/FID Provider Client와 위 mapping, 안전한 error/header 추출, timeout/내부 retry 제어 gate, APNs expiration 확인, credential/target redaction 및 Backend→실제 iPhone smoke |
| TASK-707 | Event/Delivery Entity·Migration·Repository, 기존 두 UNIQUE와 이번 operational fields/CHECK/due index, immutable Event owner/시각·recipient generation, History 전환 및 Alarm/User/Device 삭제 정책/FK. 실제 MySQL 제약과 query/EXPLAIN 검증 |
| TASK-708 implementation | 기존 atomic lifecycle/Event/최초 recipient transaction에서 원래 observed/detected 시각·generation·기한 연결, 0/N recipient 및 이미 만료된 row 생성, logical dedup/UUID continuity. 재시도해도 payload/recipient/TTL 변경 금지 |
| TASK-706 | 단일 due worker lifecycle/제한 query, preflight owner/generation/enabled/target/revision과 호출 직전 expiry 확인, attempt 시작 transaction 및 transaction 밖 I/O 연결. result policy를 새로 정의하지 않고 TASK-709 handler에 위임 |
| TASK-709 implementation | Provider/local 결과 전이와 attempt/retry/recovery policy, conditional cleanup, 시작/결과 snapshot 일치 및 DB 재반영 안전성. expiry-vs-limit, crash 소비, unknown acceptance, stale FID, A→B→A, partial-device 실패의 핵심 correctness test |
| TASK-703/704 | 기존 Device locking/API 및 높은 Client revision 재동기화 적용, 이미 terminal인 Delivery 복귀 없음 |
| TASK-710/711 | 실제 iPhone 수신/tap 및 최종 E2E/regression. 선행 correctness 검증을 대체하지 않음 |

Schema 적용과 삭제 정책은 TASK-707, Provider 제어 gate/smoke는 TASK-705에서 검증하고, 최종 운영 수치는 TASK-709 구현/latency에서 확정한다. Phase 8 latency/restart 검증은 이 failure 계약의 최초 correctness 구현을 미루는 이유가 아니다. TASK-708/709 checkbox와 모든 후속 Task 완료 상태·dependency는 변경하지 않는다.

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
