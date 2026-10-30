# 요구사항

## 1. 제품 목표

StopBell의 핵심 목적은 사용자가 선택한 버스를 추적하여 실제 목적지에 도착하기 전에 하차를 준비할 수 있도록 알리는 것이다.

사용자는 실제 내리고 싶은 목적지를 선택한다. 알림을 일찍 받기 위해 목적지보다 앞선 정류장을 대신 선택하도록 유도하지 않는다. 기존 버스 도착·통과 Alarm 기능은 별도로 유지한다.

## 2. 버전별 범위

### V1 — 버스 알림

V1은 기존 버스 도착·통과 Alarm과 선택한 특정 차량의 하차 준비 BoardingSession을 제공한다. 사용자는 설정 뒤 앱을 종료하거나 백그라운드로 전환할 수 있으며, 각 기능의 알림 시점과 안전 조건은 아래 계약을 따른다.

### V2 — 지하철 기상 알림

지하철을 이용하는 사용자는 경로를 계속 확인하지 않아도 목적지 역을 선택하고 목적지 도착 전 또는 도착 시점에 알림을 받을 수 있어야 한다.

명시적인 프로젝트 결정으로 순서가 변경되지 않는 한, V1이 신뢰성 있게 동작하기 전에는 V2를 구현하지 않는다.

## 3. V1 기능 요구사항

### FR-001 버스 검색

사용자는 설정된 교통 데이터 제공자가 지원하는 버스 노선을 찾고 선택할 수 있다.

### FR-002 정류장 선택

사용자는 선택한 버스 노선에 연결된 정류장을 선택할 수 있다.

정류장 선택 UI는 Backend가 전달한 `canNotifyOneStopBefore`와 `canNotifyOneStopAfter`를 사용해 각각 사용할 수 없는 before/after option을 비활성화한다. Stop order를 산술적으로 증감해 인접 정류장을 추측하지 않는다.

사용자는 Route traversal 안에서 선택할 Stop occurrence를 충분히 구분할 수 있어야 하며, 동일 정류장명이나 왕복·순환·회차로 방향 구분이 필요한 경우 UI는 Backend가 제공하는 검증된 metadata/계약을 사용해야 한다. TASK-608A의 표시 계약은 검증된 경기 GBIS traversal의 `destinationName`(서울·검증 실패 경기 Route는 null), 실제 인접 occurrence의 `previousStopName`/`nextStopName`, 마지막 구분값인 `order`를 사용한다. 반복 occurrence는 각각의 `id`로 선택한다. 이 표시 정보로 실제 차량의 실시간 운행 방향을 증명하거나 추측하지 않는다. 필드와 nullable 조건은 [Stop API](api.md#버스-노선의-정류장-조회)를 따른다.

### FR-003 알림 생성

사용자는 최소한 다음을 포함하는 알림을 만들 수 있다.

- Provider namespace 안의 버스 노선 식별자
- 같은 Provider namespace 안의 목표 정류장 식별자
- 선택한 Route traversal에서 목표 정류장의 진행 순서
- 순환·재방문·분기·회차의 occurrence 모호성을 해소하는 데 필요한 traversal/direction context
- 알림 상태
- 서로 독립적인 한 정거장 전 / 한 정거장 후 추가 알림 옵션

추가 필드는 필요할 때만 도입한다.

Alarm 상태는 `INACTIVE`, `ACTIVE`, `FOLLOW_UP`을 그대로 표시하며 boolean active로 축소하지 않는다. 현재 metadata에서 삭제·교체된 stale `targetStopOccurrenceId`로 생성 요청이 `404 Not Found`가 되면 Client는 이전 ID를 추측해 매칭하거나 자동 POST 재시도를 하지 않고, 사용자가 Stop 목록에서 다시 선택하도록 복구한다.

### FR-004 알림 활성화 / 비활성화

사용자는 알림을 활성화하고 비활성화할 수 있다.

새 monitoring cycle을 시작하는 `INACTIVE → ACTIVE`, `FOLLOW_UP → ACTIVE`에서만 activation generation을 증가시킨다. 이미 `ACTIVE`인 Alarm의 중복 활성화는 idempotent하며 generation을 증가시키거나 baseline을 초기화하지 않는다.

### FR-005 교통 모니터링

백엔드는 외부 교통 데이터 소스를 사용해 활성 알림 조건이 충족되었는지 판단한다.

### FR-006 푸시 알림

알림 조건이 충족되면 백엔드는 그 logical Event 결정 시점에 eligible한 Device들을 recipient로 확정하고 푸시 알림 전달을 시작한다. 한 User는 여러 Device를 가질 수 있으며 현재 installation의 logout이나 Push 해제 때문에 다른 Device 또는 Alarm lifecycle이 변경되어서는 안 된다.

### FR-007 중복 방지

동일한 logical Notification 발생 건은 Database uniqueness 기준으로 한 번만 생성되어야 한다. 각 Event와 Device 조합의 delivery record도 하나여야 하지만, retry 때문에 Provider request는 여러 번 발생할 수 있고 실제 Device 표시 exactly-once는 보장하지 않는다.

### FR-008 활성 알림 조회

사용자는 현재 활성화된 알림을 볼 수 있다.

### FR-009 알림 제거

사용자는 더 이상 필요 없는 알림을 제거할 수 있다.

Alarm 삭제 후에도 기존 NotificationEvent/Delivery와 원본 Alarm ID를 보존한다. PENDING 전달만 FAILED/DISPATCH_NOT_ALLOWED로 종료하고 기존 terminal 결과는 유지한다. 향후 Provider 호출을 중단하는 의미이며 이미 진행 중이거나 접수된 알림 회수는 보장하지 않는다.

### V1 초기 Transit 지원 범위

V1의 초기 버스 지원 대상 지역은 다음과 같다.

- 서울특별시
- 경기도

사용자의 실제 이동 범위가 서울과 경기에 걸쳐 있으므로 두 지역을 V1부터 함께 지원한다. 이 결정은 전국 지원을 V1 제품 요구사항으로 확장하는 것이 아니다.

### V1 Transit Provider

V1은 지역별 공식 Provider와 source를 사용한다. 경기도는 국토교통부 TAGO가 Route/Stop identity와 traversal metadata, realtime Location, Arrival 보조 정보를 제공한다. 경기 GBIS 기반정보는 검증된 Route의 목적지 표시 metadata만 보강하며 GBIS-only Route를 추가하지 않는다. 서울특별시의 static Route/Stop metadata는 서울 T Data CSV full import를 사용하고, realtime Location은 서울특별시 버스위치정보조회 서비스를 사용한다. static metadata의 후행 결정은 `adr/ADR-009-bus-transit-metadata-persistence-and-diff-sync-strategy.md`를 따른다.

Provider external Route/Stop ID는 provider namespace 안의 opaque String이다. 노선번호와 정류소명은 검색·표시 metadata이고, Stop order는 Route 진행 metadata이며 identity가 아니다. 세부 근거와 제한은 `adr/ADR-006-v1-transit-provider-and-external-identifier-strategy.md`를 따른다.

### V1 Bus Alarm 실행 규칙

- 사용자는 Bus Route와 자신에게 필요한 Target Stop occurrence를 직접 선택한다. First Stop은 Route traversal의 첫 occurrence인 일반 Target 사례이며 hard-coded target이 아니다.
- 추적 차량이 Target Stop에 도착했다고 충분히 판단되면 `ARRIVED` Notification을 보내고 Alarm을 성공 처리해 자동 비활성화한다.
- Alarm 활성화 순간 이미 Target Stop에 있는 차량도 충분한 도착 근거가 있으면 즉시 `ARRIVED`로 처리한다.
- before 옵션이 켜진 Alarm 활성화 순간 차량이 이미 Target predecessor에 있다고 충분히 판단되면 즉시 `ONE_STOP_BEFORE` Notification을 보낸다. Alarm은 ACTIVE이고 같은 차량을 ARRIVED까지 계속 추적한다.
- 활성화 당시 이미 Target을 지난 차량은 baseline existing vehicle로 보고 PASSED 알림을 만들지 않는다.
- 활성화 뒤 Target 이전부터 추적한 동일 차량이 직접 도착 관측 없이 Target을 건너뛰었다는 충분한 진행 근거가 있으면 `PASSED` Notification을 보낸다. 가능한 경우 정류장명 등 최근 확인된 위치와 Target에서 지난 정거장 수를 함께 안내한다. 지난 정거장 수는 raw Stop order 차가 아니라 확인된 동일 Route traversal의 successor edge 수이며, 계산할 수 없으면 생략한다.
- `PASSED`는 Alarm 성공 또는 종료가 아니다. 해당 차량 추적만 끝내고 Alarm은 ACTIVE로 유지해 다음 차량을 계속 감시한다.
- 사용자는 `notifyOneStopBefore`와 `notifyOneStopAfter` 추가 알림 옵션을 서로 독립적으로 선택할 수 있다.
- Target이 Route traversal의 첫 Stop이면 before 옵션을, 마지막 Stop이면 after 옵션을 사용할 수 없다. Client UX와 별개로 Backend 생성 계약도 이를 검증할 수 있어야 한다.
- before/after의 인접 Stop은 단순 숫자 증감이 아니라 Provider의 방향·Route sequence metadata로 확인한 predecessor/successor다.
- after 옵션이 꺼져 있으면 ARRIVED 뒤 Alarm을 `INACTIVE`로 전환하고 모든 추적을 끝낸다. 켜져 있으면 일반 monitoring을 끝내고 Alarm을 `FOLLOW_UP`으로 전환하여 ARRIVED 차량만 다음 Stop 도달·통과까지 짧게 추적한 뒤 after Notification을 한 번 보내고 `INACTIVE`로 전환한다. `FOLLOW_UP`은 ONE_STOP_AFTER 전용 lifecycle 상태이며 ARRIVED 자체는 Event다.
- ARRIVED short follow-up 중 동일 Alarm을 다시 활성화하면 이전 follow-up을 취소하고 새 baseline과 monitoring cycle을 시작한다. Alarm을 삭제하면 active monitoring과 연결된 short follow-up을 모두 종료한다.
- 한 Observation transition에서는 가장 의미 있는 Event 하나만 알린다. 직접 ARRIVED를 관찰하지 못한 채 Target 이전에서 이후로 점프하면 여러 알림 대신 PASSED를 선택한다. 이미 ARRIVED를 알린 차량의 follow-up 진행은 PASSED가 아니라 ONE_STOP_AFTER 후보로 처리한다.
- Provider data가 stale하거나 방향·차량 연속성·필수 identifier가 불명확하거나 신호가 충돌하면 `UNKNOWN`으로 두고 ARRIVED/PASSED Notification을 만들지 않는다. 외부 Provider 요청 실패도 거짓 Transit Event를 만들지 않는다.

세부 Observation, Event 및 lifecycle 계약은 `adr/ADR-007-bus-alarm-transit-observation-and-event-semantics.md`를 따른다.

### Phase 9 탑승 차량 추천과 확인

TASK-908의 사전 등록과 하차 전 단일 알림 요구사항을 반영한다. 기존 정류장 도착 Alarm과 별도로 사용자가 선택한 차량 하나의 목적지 이전 하차 준비 알림을 제공하며, 목적 occurrence 도달·통과는 세션 완료에 사용한다. 실제 탑승 여부를 검증하지 않는다.

- 사용자는 노선·목적 정류장과 선택적 승차 정류장을 선택하고 운행 차량 목록에서 원하는 차량을 직접 선택한 뒤 2정거장 전(기본)/1정거장 전 알림 시점과 함께 등록한다. 전체 차량번호 입력·수동 대조·실제 탑승 확인은 요구하지 않는다. 버스 도착 전·이동 중에도 등록할 수 있으며 단일 후보도 자동 선택하지 않는다.
- 승차 정류장과 관측 진행 지점의 노선상 간격과 앞/같음/이후 관계는 선택 보조 정보다. 실제 남은 정차 수·거리·ETA·실제 운행 방향으로 과장하지 않으며 위치 불확실성과 관측 시각을 표시한다.
- 정상 호출한 Provider 응답은 해당 조회 시점의 최신 가용 데이터로 사용한다. TAGO 원본 생성시각 부재만으로 추천을 보류하지 않는다. 조회 시각과 실제 물리 위치의 일치는 보장하지 않으며, 식별 충돌·진행 모순·불완전 응답 등은 구분해 보류한다.
- 조회 시각·선택적 제공시각, 확인 가능한 진행 정보와 불확실 사유를 구분해 표시한다. 정상 후보 없음과 Provider 실패를 혼동하지 않으며 실제 탑승 사실을 검증했다고 표현하지 않는다.
- 단말 GPS·위치 권한·Provider GPS 좌표에 의존하지 않는다. 재조회로 확정 차량을 바꾸지 않으며, 소실·식별 연속성 상실 때 다른 차량에 자동 연결하지 않는다.

추천 알고리즘·Provider별 지원 범위·정상/예외 사례와 결정 근거는 [ADR-011](adr/ADR-011-bus-vehicle-recommendation-and-user-confirmation.md)을 따른다. Polling과 Observation Freshness는 별개이며 기존 설정과 기존 도착 알림의 기능·판정·상태 전이는 유지한다. 현행 세션 계약은 아래 TASK-903 설계와 TASK-908 후행 변경을 따른다.

### Phase 9 탑승 세션과 하차 준비 알림

TASK-903의 설계에 TASK-908의 현행 정책을 반영한 요구사항이다. Backend·DB·Flutter 및 Notification 연결은 TASK-905~907과 TASK-908의 변경 구현에 반영됐으며, 실제 버스/iPhone 최종 E2E는 미완료다. [ADR-012](adr/ADR-012-boarding-session-and-alight-notification-contract.md)의 당시 결정과 후행 변경, [세션 API의 Provider 지원 경계](api.md#탑승-세션-api-task-903)를 구분한다.

- 차량 선택 요청에서 최신 차량 ID·Provider 전체 번호·노선·후보 freshness와 목적 occurrence를 검증한다. 정차·이동·위치 근거 유무와 관계없이 즉시 TRACKING으로 등록하고 선택한 같은 차량을 관측한다. TRACKING은 관측 시작을 뜻하며 실제 탑승·정확한 현재 위치 검증을 뜻하지 않는다. 일시적 위치 부족·Provider 장애는 TRACKING을 유지하며 다음 관측을 기다리고, 신뢰할 진행 근거가 없으면 NotificationEvent·Delivery를 생성하지 않는다. 별도 Start API/CONFIRMED 상태는 없다.
- User당 미종료 세션은 하나이며 확정 차량만 추적한다. 다른 차량·노선·목적지로 자동 전환하지 않는다. 후보 조회나 화면 선택 자체는 추적을 시작하지 않는다.
- 사용자는 실제 내리고 싶은 정확한 목적 occurrence를 선택한다. 목적지 도달·통과 근거는 세션 완료에만 사용하며 새 세션은 목적지 Push를 별도로 발송하지 않는다. 하차 허용 여부와 무관하게 미정차·경유 occurrence도 선택할 수 있다. 실제 정차·승객 하차를 확인한 것으로 표현하지 않는다.
- 새 세션은 알림 시점을 2정거장 전(기본) 또는 1정거장 전으로 선택하며 ON/OFF는 없다. 불변 traversal의 실제 occurrence 배열에서 목적지의 2개/1개 앞까지 진행했음을 확인하고 아직 목적지 이전일 때 하차 준비 알림을 한 번 생성한다. 2개 앞 관측을 놓치면 1개 앞에서 대체할 수 있다. 목적지 현재·이후에서는 소급 생성하지 않는다. 미정차·경유 지점도 간격에 포함하며 실제 정차 횟수·거리·남은 시간으로 표현하지 않는다. stopFlag=1은 준비 판단의 필수 조건이 아니다.
- 서울의 이동 중 진행은 N51·143번 실측에 기반한 `stopFlag=0`의 마지막 보고 도달/통과 occurrence와 `stopFlag=1`의 현재 도착 occurrence 가정을 사용한다. 정확한 stId/stOrd 대응·freshness·단조성·운행 연속성 검증은 필수다. 공식 API 보장이나 실제 무정차 갱신 검증 완료를 뜻하지 않는다.
- PREPARE_ALIGHT logical Event는 세션당 최대 한 번이다. 최초 저장 시 원래 관측 기반 TTL이 지났으면 Event·Delivery를 생성하지 않고 이후 새 유효 관측에서 다시 판단할 수 있다. 이미 저장된 Event의 실패·만료·recipient 없음은 새 알림 기회를 만들지 않는다.
- 도착·이후 진행·회차·관측 누락을 구분한다. 근거가 부족하면 판단을 보류하고 놓친 준비 알림이나 실제 정차·사용자 하차 사실을 추측하지 않는다. 하차 가능 여부는 추적 시작·알림 판정 조건이 아니다. 차량 식별·목적 occurrence·freshness·진행 방향·운행 연속성 검증은 유지한다.
- 세션 snapshot과 마지막 검증 checkpoint를 보존하고, 새 관측과 조건부 자동 복구한다. 재시작이나 같은 ID만으로 동일 운행을 확정하지 않으며 소실·식별 변경·회차·공백으로 연속성이 불명확하면 보류·재확인한다.
- 추적 중 준비 알림은 복구 검증 전 전송을 차단한다. V20 이전 세션의 기존 목적지 알림에 한해 불변 완료 근거·원래 기한을 재검증하여 전송한다. 기존 ON/OFF·대기 Event/Delivery는 기존 정책으로 보존한다. 복구 후에도 현재 Session 상태/revision과 Event 기한을 검증한다. 복구 대기 Delivery가 기존 Alarm의 조회 batch를 점유하지 않으며 만료 정리는 gate와 무관하게 수행한다. 메모리 gate는 단일 Backend만 지원한다.
- 초기 세션 수명·복구 공백·준비 알림 TTL은 각각 PT4H/PT2M/PT30S이며 검증된 정확도 기준이 아닌 조정 가능한 운영값이다. 새 설정으로 기존 저장 기한을 늘리지 않는다. 정확한 설정·만료 실행은 [Architecture](architecture.md#탑승-세션-실행과-notification-연계-task-903)를 따른다.
- GPS 비의존, TAGO 정확한 하차 시점 지원 보류와 서울 조건부 지원을 유지한다. 기존 Alarm/Notification 계약을 보존하며 TASK-908의 변경 구현과 실제 iPhone 현장 검증을 구분한다. 기존 도착 Alarm의 미정차 개선은 별도 후속 범위다.

### V1 Push Notification 계약

- StopBell Device identity는 Client가 앱 installation마다 생성한 `installationId`를 기준으로 하며 User-scoped identity가 아니다. 하나의 `installationId`에는 동시에 current owner가 최대 한 명이어야 하고, 같은 installation에서 User가 바뀌면 atomic ownership takeover 또는 동등한 계약으로 이전·신규 ownership이 함께 enabled 상태로 남지 않게 한다. Firebase의 현재 push targeting identifier는 rotation/re-registration 가능한 delivery reference로 분리하고 APNs device token은 StopBell Device identity로 사용하지 않는다. TASK-702에서 확정한 단일 Firebase Project의 현재 FID uniqueness 정책은 `database.md`의 devices Schema를 따른다.
- TASK-701의 SDK/API 조사에서 delivery target으로 Firebase Installation ID(FID)를 선택했다. 사용자 확인에 근거한 실제 iPhone FID targeting 수신 smoke는 통과했으며 상세 SDK/readiness/lifecycle 계약은 ADR-010을 따른다.
- 동일 installation의 registration update는 monotonic revision 또는 동등한 계약으로 순서 역전된 stale update가 최신 push target을 덮어쓰지 못하게 한다. 같은 revision과 같은 registration의 재요청은 idempotent하게 처리할 수 있어야 한다.
- 현재 installation logout은 해당 Device의 Push subscription disable/unregister를 시도하지만 Alarm lifecycle과 다른 Device는 변경하지 않는다. Auth logout은 Refresh Session 종료 책임을 유지하며 Device lifecycle은 별도 authenticated API 또는 동등한 명시적 흐름으로 처리한다. Offline logout에서 Backend Device disable을 즉시 보장하지 않는다.
- 첫 Alarm activation 직전에 기능 맥락을 설명한 뒤 Notification permission을 요청하는 것을 기본 방향으로 한다. Permission이 거부되어도 Alarm 생성·활성화를 Backend에서 금지하지 않으며, 현재 Device가 Push를 받을 수 없다는 안내와 Settings 진입을 제공하고 app resume에서 상태를 재동기화할 수 있어야 한다.
- 기존 Alarm Push payload는 navigation hint에 필요한 `type`, `alarmId`, `eventType`, `notificationEventId` 수준으로 제한한다. `userId`, Auth Token, push targeting identifier, Provider Route/Stop external ID, GPS, Alarm 상세 전체를 포함하지 않는다. Tap 뒤에는 Auth Session 초기화 후 Backend Alarm detail API로 소유권과 현재 상태를 다시 확인하며, 삭제되었거나 접근할 수 없는 Alarm은 정상적인 stale-notification UX로 처리한다. 별도 세션 source의 hint와 tap은 [세션 API 계약](api.md#탑승-세션-push와-tap)을 따른다.
- Alarm lifecycle transaction은 current lifecycle/activation generation 검증, lifecycle transition, durable logical `NotificationEvent` 생성과 그 시점에 eligible한 Device별 Delivery 생성을 함께 처리한다. 유효 기한이면 PENDING이며 이미 만료된 경우 EXPIRED 기록을 남기고 늦은 알림을 전송하지 않는다. freshness는 원래 StopBell observedAt 기준이며 [TASK-709 계약](adr/ADR-010-notification-device-and-durable-delivery.md#freshness와-local-expiry)을 따른다. commit 뒤 단일 Spring Backend의 fixed-delay, non-overlapping worker는 due PENDING Delivery만 처리한다. 전송 직전 recipient Device의 current owner, ownership generation, enabled 상태, current push target/revision을 다시 확인하고 실제 attempt revision을 기록한다. FCM I/O는 이 transaction 안에서 수행하지 않으며 non-durable after-commit callback만을 유일한 전달 보장으로 사용하지 않는다. claim/lease, `claimedAt`, `SENDING`, stale-claim recovery, multi-worker coordination은 V1 요구가 아니다.
- eligible Device가 0개여도 `NotificationEvent`는 생성하고 Delivery는 0개로 둔다. 이를 Provider failure나 delivery success로 해석하거나 별도 status enum을 강제하지 않고 no-recipient operational log/metric으로 관찰한다.
- Delivery lifecycle은 `PENDING`, `ACCEPTED`, `FAILED`, `EXPIRED`를 기본 방향으로 하며 retry는 `PENDING + attemptCount + nextAttemptAt + freshness`로 표현할 수 있다. Provider result는 `ACCEPTED`, `INVALID_TARGET`, `RETRYABLE`, `CONFIGURATION`, `PERMANENT_REQUEST`, `AMBIGUOUS_TIMEOUT`을 구분한다. `EXPIRED`는 local freshness 종료 의미이며 Provider accepted는 실제 사용자 표시 성공을 뜻하지 않는다.

세부 Device 및 전달 결정의 근거와 보류 범위는 `adr/ADR-010-notification-device-and-durable-delivery.md`를 따른다.

## 4. 인증

StopBell은 자체 ID/Password 회원가입을 제공하지 않고 Social Login만 지원한다. 최초 Provider는 Google이며, 추가 Provider는 실제 필요가 확인된 후 별도 범위로 검토한다.

Flutter는 Google ID Token으로 외부 Identity를 증명하고, Backend는 이를 검증한 뒤 StopBell 자체 Access Token과 Refresh Token을 발급한다. Google Token은 StopBell Application API의 장기 인증 Token으로 사용하지 않는다.

Application API는 JWT Access Token 기반으로 인증하며, Access Token 기본 수명은 1시간이다. Refresh Token은 Access Token 재발급과 현재 Session Logout에 사용하고, 30일 수명 및 Rotation 정책으로 장기 로그인 유지를 지원한다. Logout은 해당 Refresh Token Session만 삭제하며 이미 발급된 Access Token은 만료 시점까지 유효할 수 있다. Refresh Token이 만료되거나 유효하지 않으면 다시 Google Login이 필요하다.

Flutter는 Access/Refresh Token Pair를 OS Secure Storage에 함께 보관하고, 하나의 Auth Session 책임 안에서 current Token Pair와 인증 상태를 관리한다. Refresh `401`은 Token Pair 제거와 unauthenticated 전환을 뜻하지만, network/offline/5xx는 장기 session을 즉시 삭제하지 않는다. 보호 요청은 성공한 refresh 뒤 최대 한 번만 재시도하며, Alarm 생성처럼 idempotency 계약이 없는 mutation은 timeout만으로 자동 POST 재시도하지 않는다.

공개 배포 전뿐 아니라 Alarm API 구현부터 인증된 StopBell User를 기준으로 Alarm 소유권을 처리한다. Client가 제공한 `userId`를 신뢰하지 않는다.

인증 전략의 근거와 제약은 `adr/ADR-005-authentication-and-user-identity-strategy.md`를 따른다.

## 5. 비기능 요구사항

### NFR-001 신뢰성

시스템은 불필요한 기능 폭보다 누락 알림과 중복 알림 방지를 우선해야 한다.

Phase 7은 stale activation/lifecycle 보호, logical Notification DB uniqueness, Event 시점의 multi-device recipient Delivery 생성, durable pending dispatch recovery, invalid registration의 조건부 정리, bounded retry state를 포함해야 한다. Phase 8 검증은 이 correctness를 대체하지 않는다.

기존 Alarm의 ACTIVE tracking은 V1에서 memory 기반 상태와 restart-safe rebaseline을 유지한다. restart 전후 Observation을 연결해 PASSED를 추론하지 않으며, TASK-811이 실제 품질 문제를 입증하기 전에는 Alarm tracking persistence나 event sourcing을 추가하지 않는다. 별도 BoardingSession의 최소 checkpoint 영속·조건부 복구는 TASK-903 계약이며 이 Alarm 정책을 변경하지 않는다.

### NFR-002 관측성

다음의 중요한 단계는 structured log와 최소 operational metric으로 추적할 수 있어야 한다.

- 교통 API 요청/결과
- 알림 조건 평가
- 푸시 요청/결과
- 관련 실패

최소 metric 후보는 Provider request outcome/latency, Observation staleness/UNKNOWN reason, ACTIVE/FOLLOW_UP Alarm 수, scheduler cycle duration·last completion·overlap, Notification pending 수와 oldest pending delivery age, delivery accepted/failure/retry/expired, metadata 마지막 complete sync 성공 age다. `alarmId`, `deviceId`, `routeId`, `trackingCycleId`, `installationId`는 metric label에 넣지 않는다. Actuator/Micrometer 수준을 기본으로 하며 export와 alert destination은 deployment platform에서 정한다.

로그에 시크릿이나 민감한 토큰을 노출해서는 안 된다. Access/Refresh Token, Google ID Token, Firebase credential, 원문 push targeting identifier·installationId, Transit API key가 포함된 URL/query, 필요 이상의 정확한 GPS, raw Provider response 전체를 기록하지 않는다.

### NFR-003 외부 API 효율성

여러 활성 알림이 같은 교통 조회를 공유할 수 있다면 시스템은 사용자마다 동일한 외부 API 요청을 하나씩 보내서는 안 된다.

초기 구현은 단순할 수 있지만, 한계는 측정하고 문서화해야 한다.

### NFR-004 장애 처리

교통 API 또는 푸시 제공자의 일시적 장애가 알림 상태를 손상해서는 안 된다.

재시도 동작은 의도적이고 제한되어야 한다.

### NFR-005 보안

- 시크릿은 Git에 커밋하지 않는다.
- API 키는 환경/설정 메커니즘을 통해 저장한다.
- push targeting identifier와 인증 데이터는 민감한 데이터로 취급하고 로그에서 redaction한다.

### NFR-006 유지보수성

알림 조건을 판단하는 비즈니스 규칙은 가능한 경우 Controller/네트워크 연결 코드와 분리해야 한다.

### NFR-007 운영 준비성

Backend liveness와 readiness를 구분한다. readiness는 DB 연결, Flyway migration 적용, 필요한 application component 초기화, scheduler/outbox worker 실행 가능을 확인하며 일시적인 TAGO/서울/FCM 원격 장애만으로 DOWN이 되어서는 안 된다. fresh DB 최초 배포에서는 필요한 metadata bootstrap 전 public Route/Alarm 생성 traffic을 받지 않는 절차가 필요하다.

Persisted operational time은 host local timezone과 무관하게 UTC 의미로 일관되어야 한다. Provider local time은 timezone을 명시적으로 해석하며, business time 계산은 test 가능한 `Clock` 또는 동등한 source를 우선한다. 이 원칙은 TASK-814 이전에 추가되는 timestamp에도 즉시 적용하며 TASK-814는 기존 timestamp의 최종 consistency를 정리한다.

## 6. V1에서 명시적으로 제외하는 범위

별도 승인이 없다면 V1에는 다음을 포함하지 않는다.

- 지하철 추적
- 결제
- 소셜/친구 기능
- 채팅
- 경로 추천
- 분석 대시보드
- 시연만을 위한 Kafka/RabbitMQ
- 시연만을 위한 Redis
- 마이크로서비스
- 멀티 리전 배포
- 복잡한 관리자 콘솔
- Kubernetes, self-hosted Prometheus/Grafana stack, Vault, distributed tracing platform
- CQRS, event sourcing, blue/green deployment framework

## 7. 미해결 질문

운영 구현 전에 다음을 조사하거나 결정해야 한다.

- Transit metadata persistence는 서울 T Data CSV full import와 경기 TAGO throttled full sync를 위해 필요하며 JPA diff sync로 구현한다. Route/Stop 검색·Alarm grouping의 MyBatis 필요성은 실제 SQL 제어 요구가 확인될 때 결정한다.
- 어떤 폴링 주기가 허용되며 유용한가?
- 어떤 요청 제한이 적용되는가?
- TASK-701에서 선택한 FID targeting은 사용자 확인에 근거한 실제 iPhone 수신 smoke를 통과했다. SDK/local unregister 계약과 아직 실기기 검증하지 않은 항목은 ADR-010을 따른다.
- 공개 App Store 제출 시점 Apple Guideline 4.8의 적용 여부와 가장 단순한 login/account deletion 준수 방식은 무엇인가?
