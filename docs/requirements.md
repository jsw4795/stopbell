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

검색에 성공해 노선 결과가 있을 때만 지역 탭을 표시한다. 전체를 첫 탭과 기본 선택으로 두고, 반환된 결과에 있는 광역 지역만 추가한다. 경기 시·군은 경기로 묶고, 탭 전환은 반환된 결과의 순서와 최대 50건 범위 안에서만 필터링한다.

검색 행에는 노선번호, 공식 유형·색상과 서울 TOPIS 또는 경기 TAGO에서 확인한 운행 구간을 표시한다. 검증된 기점·종점 쌍을 표시할 수 없는 노선은 운행 구간 정보 없음으로 표시한다. 번호와 구간 표시가 같은 결과에만 구별에 필요한 지역명과 추가 식별 정보를 표시하며, 선택 시 해당 행의 내부 노선 ID를 사용한다. [검색 API](api.md#버스-노선-검색)

### FR-002 정류장 선택

사용자는 선택한 버스 노선에 연결된 정류장을 선택할 수 있다.

정류장 선택 UI는 Backend가 전달한 `canNotifyOneStopBefore`와 `canNotifyOneStopAfter`를 사용해 각각 사용할 수 없는 before/after option을 비활성화한다. Stop order를 산술적으로 증감해 인접 정류장을 추측하지 않는다.

사용자는 Route traversal 안에서 선택할 Stop occurrence를 충분히 구분할 수 있어야 하며, 동일 정류장명이나 왕복·순환·회차로 방향 구분이 필요한 경우 UI는 Backend가 제공하는 검증된 metadata/계약을 사용해야 한다. TASK-608A의 표시 계약은 검증된 경기 GBIS traversal의 `destinationName`(서울·검증 실패 경기 Route는 null), 실제 인접 occurrence의 `previousStopName`/`nextStopName`, `order`를 제공한다. 도착 알람 정류장 선택 UI는 모든 occurrence에 `destinationName` 기반 'OO 방면' 한 줄만 표시하고, 이전·다음 정류장과 노선 순서는 표시하지 않는다. `destinationName`이 null이면 보조 표시를 생략한다. 반복 occurrence는 각각의 `id`로 선택한다. 이 표시 정보로 실제 차량의 실시간 운행 방향을 증명하거나 추측하지 않는다. 필드와 nullable 조건은 [Stop API](api.md#버스-노선의-정류장-조회)를 따른다.

도착 알람과 하차 알림의 정류장 선택은 첫 occurrence부터 마지막 occurrence까지 하나의 세로 노선도로 표시한다. 회차 경계를 검증할 수 있는 노선은 고정 헤더 아래 첫 표시 정류장에 따라 방향이 바뀌며, 방향 버튼은 선택을 유지한 채 노선도 안에서 출발 또는 회차 occurrence로 이동한다. 방향을 검증할 수 없어도 전체 노선도에서 정류장을 선택할 수 있다. 선택한 승차와 목표 정류장은 노선도 스크롤과 독립된 하단 요약에서 확인한다. 노선도는 정류장명 끝의 `(미정차)` 표기를 회색 보조 텍스트로 분리해 표시하며, 표기가 없다는 사실은 실제 정차를 보장하지 않는다. 해당 표기는 선택이나 알림 판단을 제한하지 않는다.

하차 알림은 승차 정류장을 먼저 고른 뒤 목표 정류장을 선택하며 둘 다 필수다. 목표는 같은 노선에서 승차 occurrence보다 순서가 뒤여야 한다. 선택 불가능한 목표는 구분해 표시하며 선택을 차단한다. 두 정류장을 선택한 뒤 노선도의 정류장을 누르면 기존 선택을 지우고 누른 정류장을 새 승차 정류장으로 선택해 목표를 다시 고른다. 차량 후보에서 뒤로 가면 노선을 다시 조회하지 않고 목표·승차 선택과 노선도 위치를 복원한다. 차량 정보 확인과 하차 추적 지원 상태를 구분해 보류 사유를 표시한다.

### UI-7 세로 노선도의 실시간 차량 위치와 잔여 좌석

정류장 선택의 세로 노선도는 선택 노선의 여러 차량 위치와 선택 차량을 표시하며, 확인 가능한 경우 차량별 잔여 좌석 수도 표시한다. Provider 위치 순번(경기 GBIS `stationSeq`, 서울 `sectOrd`)을 노선 방문 지점 순서에 대응시켜 표시하고, 대응할 수 없는 개별 차량만 위치 없이 표시한다. 이 표시는 하차 추적·알림 판정과 무관하다.

- TAGO 차량 위치 응답에는 잔여 좌석 수가 없다. [TAGO 버스위치정보](https://www.data.go.kr/data/15098533/openapi.do)
- GBIS `remainSeatCnt`는 차량별 잔여 좌석 수다. 공식 제공 대상은 노선유형 코드 11·12·14·16·17·21·22다. `-1` 또는 미제공은 `좌석 정보 없음`, `0`은 `잔여 좌석 없음`, 양수는 제공된 빈자리 수로 표시한다. `crowded`는 일반버스의 차내 혼잡도이며 좌석 수로 환산하지 않는다. [GBIS 버스 위치정보 공식 매뉴얼](https://gbis.go.kr/gbis2014/publicService.action?cmd=mBusLocation)
- 좌석 미제공 또는 식별·신선도 검증 실패 시 좌석 수를 표시하지 않는다. 독립적으로 검증된 차량 위치는 계속 표시할 수 있다.
- 2026-11-12 확정 정책: 차량 위치는 읽기 전용 신규 API(`GET /api/v1/bus-routes/{routeId}/vehicles`, 상세는 [API](api.md)에서 확정)로 제공하며 후보 API·하차 추적·TRACKING 계약과 분리한다.
- 2026-11-12 결정: 경기 차량 조회는 노선 유형과 무관하게 GBIS를 위치(`stationSeq`→occurrence 순번)·상태(`stateCd`)·좌석·혼잡도의 단일 소스로 1회 호출하며 TAGO 호출과 번호 결합은 UI-7에서 쓰지 않는다. GBIS 실패 시 TAGO로 대체하지 않고 기존 STALE/503으로 처리한다. 하차 추적·후보 API는 TAGO를 그대로 쓴다.
- 위치 상태는 응답의 `positionState`로 구분한다. `AT_STOP`은 해당 정류장 동그라미에, `DEPARTED`는 그 정류장과 다음 정류장 사이 레일 중앙에 아이콘을 그린다. 경기는 GBIS `stateCd` 1을 `AT_STOP`, 0·2를 `DEPARTED`로, 서울은 roster `stopFlag` 1을 `AT_STOP`, 그 외를 `DEPARTED`로 본다. 아이콘은 하차 추적 화면의 버스 아이콘을 노선 유형 표시색으로 그리고(미분류는 기본색) 행에는 번호만 표시한다.
- 서울은 좌석 대신 Provider 혼잡도(`congetion`)를 표시한다. 공식 값은 0 없음, 3 여유, 4 보통, 5 혼잡, 6 매우혼잡이며 0과 문서에 없는 값은 `정보 없음`으로 표시한다. [서울 버스위치정보조회 서비스](https://www.data.go.kr/data/15000332/openapi.do)
- GBIS `crowded`는 노선유형 13·15·23에서만 제공되며 1 여유, 2 보통, 3 혼잡, 4 매우혼잡이다. `stateCd`는 0 교차로통과, 1 정류소 도착, 2 정류소 출발이다. `resultCode`는 0 정상, 1 시스템 에러, 2 필수 Parameter 없음, 4 결과 없음이며 4만 운행 차량 없음으로 해석한다. 엔드포인트는 `https://apis.data.go.kr/6410000/buslocationservice/v2/getBusLocationListv2`다. ([GBIS 공식 매뉴얼](https://gbis.go.kr/gbis2014/publicService.action?cmd=mBusLocation), 2026-11-12 확인) 응답은 서울·GBIS 값을 같은 1~4 단계(1 여유 ~ 4 매우혼잡)로 정규화한다.
- 자동 polling은 하지 않는다. 화면 진입 시 1회 조회하고 새로고침 버튼으로 수동 재조회한다. 서버는 Route 단위 짧은 캐시·동시 요청 합치기와 Provider별 일일 호출 상한을 둔다.
- 노선도에 `HH:mm 기준 위치`를 표시한다. 조회 후 2분이 지나면 차량을 숨기지 않고 `위치가 오래됐어요` 새로고침 안내만 표시한다. Provider 실패를 운행 종료로 해석하지 않는다.
- GBIS 실시간 위치 연동은 UI-7 범위다. 상세 구현은 [UI-7 로드맵](roadmap.md#ui-7--실시간-다중-차량-위치-표시)을 따른다.

### UI-7B 하차 알람 탑승 버스 선택 노선도

하차 알람의 탑승 버스(후보) 선택 화면을 정류장 선택과 같은 세로 노선도 형태로 리뉴얼한다. 정류장이 아닌 버스 마커·라벨을 탭해 선택하며, 기존 후보 API와 `candidateRef` 재검증 계약은 유지한다. 2026-11-12 확정 설계는 다음과 같다.

- 데이터: 후보 API(등록용)와 차량 위치 API(UI-7)를 함께 호출한다. 정규화 차량번호(공백·하이픈 제거)로 연결된 버스는 차량 API의 `occurrenceId`·`positionState`로 노선도에 그리고, 연결되지 않은 후보는 노선도 아래 "위치 확인 불가" 목록에서 기존 방식대로 선택한다. 목표 정류장을 지난 버스(차량 occurrence 또는 후보 `progress` 순번 기준)는 노선도와 목록 어디에도 표시하지 않으며, 전부 빠지면 후보 없음 안내를 보인다. 새로고침은 두 API를 모두 다시 호출한다.
- 범위: 목표 정류장까지 그린다(승차 전·후 버스 모두 선택 가능). 승차 정류장 이전에 버스가 없으면 승차 정류장부터, 있으면 가장 앞 버스에 필요한 첫 정류장부터 그리며 그 이전 구간은 접힘 없이 생략한다.
- 표시: 도착(`AT_STOP`) 버스는 이전·도착 정류장, 출발(`DEPARTED`) 버스는 출발·다음 정류장만 펼치고 승차·목표 정류장은 항상 표시한다. 나머지 구간은 개수와 무관하게 가로 구분선 가운데 "정류장 N개"로 접고 탭으로 펼침·접힘을 왕복한다. 진입 시 승차 정류장 부근으로 스크롤한다. 카드는 버스 마커와 연결선으로 잇고(도착은 위로 비킨 대각선, 출발은 수평선) 번호·좌석·혼잡도와 "승차까지 N정거장"(승차 전) 또는 "목표까지 N정거장"(승차 이후)을 표시한다. 선택은 노선 유형색 카드 강조와 마커 halo로 나타낸다.

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

사용자당 동시에 활성인 도착 Alarm(`ACTIVE`·`FOLLOW_UP`)은 1개, 진행 중인 하차 알람(Boarding Session)도 1개로 제한하며 Alarm 생성 개수는 제한하지 않는다. 이미 활성인 상태에서 다른 Alarm을 활성화하면 `ALARM_ACTIVE_LIMIT_EXCEEDED`(409)로 거절하고 기존 Alarm을 자동으로 끄지 않는다. 앱은 “이미 활성화된 도착 알람이 있어요. 기존 알람을 끈 뒤 다시 시도해 주세요.”로 안내하며, 하차 알람은 `BOARDING_SESSION_CONFLICT`에 대해 하차 알람용 문구로 안내한다.

활성(`ACTIVE`) Alarm은 생성 이후에도 한 정류장 전/후 알림 옵션을 켜고 끌 수 있다. 변경은 이후 평가부터 적용되고, 비활성·후속 추적(`FOLLOW_UP`) 중 Alarm은 수정할 수 없다.

활성(`ACTIVE`) 도착 알람은 홈과 상세에서 대상 정류장에 가장 가까이 접근 중인 직전 버스의 남은 occurrence 간격을 `직전 버스 N정류장 전`으로 표시한다. 접근 차량이 없으면 `다가오는 버스 없음`으로 표시하며, 일부 위치 미확인·오래된 정보·조회 실패는 없음과 구분한다. 화면 진입과 명시적 새로고침에만 기존 차량 위치 API를 조회하고 자동 polling은 하지 않는다. 조회 reference·계산 및 불확실성 처리의 상세는 [Alarm API](api.md#알림-생성)를 따른다.

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

- 사용자는 노선·승차 정류장과 그 이후의 목적 정류장을 선택하고 운행 차량 목록에서 원하는 차량을 직접 선택한 뒤 2정거장 전(기본)/1정거장 전 알림 시점과 함께 등록한다. 전체 차량번호 입력·수동 대조·실제 탑승 확인은 요구하지 않는다. 버스 도착 전·이동 중에도 등록할 수 있으며 단일 후보도 자동 선택하지 않는다.
- 신규 등록은 차량 카드 하나와 알림 시점을 선택한 뒤 하단의 `이 차량으로 하차 알림 설정`을 누르면 Push 준비 후 서버에 요청한다. 별도 확인 화면은 없으며 성공 시 추적 상세를 표시한다. 재조회한 후보에서는 다시 직접 선택한다. 과거 후보의 경과 시간으로 등록을 차단하지 않고 현재 차량 확인은 서버가 수행한다.
- 후보 카드의 조회 시각은 서버 `observedAt`이다. 같은 Route에서 occurrence ID·순서와 관측 근거를 확인한 경우 서버가 제공한 지점명을 최근 진행 참고로 표시한다. 지점명과 간격은 독립적으로 검증한다. 배열 순서와 서버 승차 간격이 일치하고 비교 구간에 방면 충돌 등 모호성이 없으면 승차 이전에는 서버 간격을, 승차 이후부터 목표 이전에는 배열 인덱스 차이를 표시한다. 동일 정류장명 또는 방면 표시 누락만으로 검증된 occurrence를 배제하지 않는다. 간격을 확인할 수 없으면 숫자만 숨기고, 관측 근거가 부족하면 지점명도 숨긴다. 노선 방문 지점에는 미정차·경유가 포함될 수 있으며 실제 정차 수·거리·ETA·현재 위치를 추측하지 않는다. 기존 선택 가능 여부를 유지한다.
- 정상 호출한 Provider 응답은 해당 조회 시점의 최신 가용 데이터로 사용한다. TAGO 원본 생성시각 부재만으로 추천을 보류하지 않는다. 조회 시각과 실제 물리 위치의 일치는 보장하지 않으며, 식별 충돌·진행 모순·불완전 응답 등은 구분해 보류한다.
- 조회 시각·선택적 제공시각, 확인 가능한 진행 정보와 불확실 사유를 구분해 표시한다. 정상 후보 없음과 Provider 실패를 혼동하지 않으며 실제 탑승 사실을 검증했다고 표현하지 않는다.
- 단말 GPS·위치 권한·Provider GPS 좌표에 의존하지 않는다. 재조회로 확정 차량을 바꾸지 않으며, 소실·식별 연속성 상실 때 다른 차량에 자동 연결하지 않는다.

추천 알고리즘·Provider별 지원 범위·정상/예외 사례와 결정 근거는 [ADR-011](adr/ADR-011-bus-vehicle-recommendation-and-user-confirmation.md)을 따른다. Polling과 Observation Freshness는 별개이며 기존 설정과 기존 도착 알림의 기능·판정·상태 전이는 유지한다. 현행 세션 계약은 아래 TASK-903 설계와 TASK-908 후행 변경을 따른다.

### Phase 9 탑승 세션과 하차 준비 알림

TASK-903의 설계에 TASK-908의 현행 정책을 반영한 요구사항이다. Backend·DB·Flutter 및 Notification 연결은 TASK-905~907과 TASK-908의 변경 구현에 반영됐으며, 실제 버스/iPhone 최종 E2E는 미완료다. [ADR-012](adr/ADR-012-boarding-session-and-alight-notification-contract.md)의 당시 결정과 후행 변경, [세션 API의 Provider 지원 경계](api.md#탑승-세션-api-task-903)를 구분한다.

- 차량 선택 요청에서 차량 ID·Provider 전체 번호·노선·목적 occurrence를 확인한다. 후보 reference의 만료와 Provider 관측 시각은 별개다. 정차·이동·위치 근거 유무와 관계없이 즉시 TRACKING으로 등록하고 선택한 같은 차량을 관측한다. TRACKING은 관측 시작을 뜻하며 실제 탑승·정확한 현재 위치 검증을 뜻하지 않는다. 일시적 위치 부족·Provider 장애는 TRACKING을 유지하며 다음 관측을 기다리고, 진행 근거가 없으면 NotificationEvent·Delivery를 생성하지 않는다. 별도 Start API/CONFIRMED 상태는 없다.
- User당 미종료 세션은 하나이며 확정 차량만 추적한다. 다른 차량·노선·목적지로 자동 전환하지 않는다. 후보 조회나 화면 선택 자체는 추적을 시작하지 않는다.
- 사용자는 실제 내리고 싶은 정확한 목적 occurrence를 선택한다. 목적지 도달·통과 시 세션을 완료하고 목적지 Event를 최대 한 번 생성한다. 하차 허용 여부와 무관하게 미정차·경유 occurrence도 선택할 수 있다. 실제 정차·승객 하차를 확인한 것으로 표현하지 않는다.
- 새 세션은 알림 시점을 2정거장 전(기본) 또는 1정거장 전으로 선택하며 ON/OFF는 없다. 불변 traversal의 실제 occurrence 배열에서 목적지의 2개/1개 앞까지 진행했음을 확인하고 아직 목적지 이전일 때 하차 준비 알림을 한 번 생성한다. 2개 앞 관측을 놓치면 1개 앞에서 대체할 수 있다. 목적지 현재·이후에서는 소급 생성하지 않는다. 미정차·경유 지점도 간격에 포함하며 실제 정차 횟수·거리·남은 시간으로 표현하지 않는다. stopFlag=1은 준비 판단의 필수 조건이 아니다.
- 서울은 `stopFlag` 값과 무관하게 정확한 stId/stOrd 대응을 사용한다. Provider 시각의 오래됨·역행·반복, 이전 occurrence 대비 역행 또는 관측 공백만으로 위치를 차단하지 않는다. 이 정보만으로 동일 운행 회차나 응답의 실제 최신성을 보장할 수 없다.
- PREPARE_ALIGHT logical Event는 세션당 최대 한 번이다. 최초 저장 시 원래 관측 기반 TTL이 지났으면 Event·Delivery를 생성하지 않고 이후 새 유효 관측에서 다시 판단할 수 있다. 이미 저장된 Event의 실패·만료·recipient 없음은 새 알림 기회를 만들지 않는다.
- 목표와 현재 occurrence의 index 차이로 준비·도달·통과를 판정한다. 차량·노선·occurrence 식별 충돌과 Provider 오류는 기존대로 처리한다. 정확한 물리 위치나 회차를 확인한 것으로 표현하지 않는다.
- 세션 snapshot과 checkpoint를 보존하고, 재시작 후 관측을 다시 반영해 복구한다. 같은 ID와 occurrence만으로 다른 운행 회차를 구별할 수 없는 한계가 있다.
- 추적 중 준비 알림은 복구 검증 전 전송을 차단한다. 목적지 Event는 완료 근거와 원래 기한을 검증하여 전송한다. 복구 후에도 현재 Session 상태/revision과 Event 기한을 검증한다. 복구 대기 Delivery가 기존 Alarm의 조회 batch를 점유하지 않으며 만료 정리는 gate와 무관하게 수행한다. 메모리 gate는 단일 Backend만 지원한다.
- 초기 세션 수명과 알림 TTL은 각각 PT4H와 PT30S이며 조정 가능한 운영값이다. 새 설정으로 기존 저장 기한을 늘리지 않는다. 정확한 설정·만료 실행은 [Architecture](architecture.md#탑승-세션-실행과-notification-연계-task-903)를 따른다.
- GPS 비의존과 서울 조건부 지원을 유지한다. TAGO는 기본 OFF 실험 기능에서 정상 응답을 최신 가용 위치로 가정하며 원본 최신성과 동일 운행은 보장하지 않는다. 기존 Alarm/Notification 계약을 보존하며 구현과 실제 iPhone 현장 검증을 구분한다. 기존 도착 Alarm의 미정차 개선은 별도 후속 범위다.

TAGO 정상 목록에서 선택 차량이 일시 누락되어도 기존 추적을 유지한다. 미검증 TAGO 참고 관측은 저장된 시각의 참고 정보로 표시하며 검증된 현재 위치로 표현하지 않는다. 회차 지점 또는 전체 노선 마지막 지점의 누락만으로 운행 종료를 확정하지 않는다.

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

StopBell은 자체 ID/Password 회원가입을 제공하지 않고 Social Login만 지원한다. 최초 Provider는 Google이며, Apple 로그인은 Backend와 Flutter iOS에 연결했고 사용자 보고 실기기 인증을 확인했다. 출시 환경 검증은 남았다. Provider가 다르면 계정을 자동 병합하지 않는다.

Flutter는 Google ID Token으로 외부 Identity를 증명하고, Backend는 이를 검증한 뒤 StopBell 자체 Access Token과 Refresh Token을 발급한다. Google Token은 StopBell Application API의 장기 인증 Token으로 사용하지 않는다.

Application API는 JWT Access Token 기반으로 인증하며, Access Token 기본 수명은 1시간이다. Refresh Token은 Access Token 재발급과 현재 Session Logout에 사용하고, 30일 수명 및 Rotation 정책으로 장기 로그인 유지를 지원한다. Logout은 해당 Refresh Token Session만 삭제하며 이미 발급된 Access Token은 만료 시점까지 유효할 수 있다. Refresh Token이 만료되거나 유효하지 않으면 해당 계정의 Provider로 다시 로그인해야 한다.

Flutter는 Access/Refresh Token Pair를 OS Secure Storage에 함께 보관하고, 하나의 Auth Session 책임 안에서 current Token Pair와 인증 상태를 관리한다. Refresh `401`은 Token Pair 제거와 unauthenticated 전환을 뜻하지만, network/offline/5xx는 장기 session을 즉시 삭제하지 않는다. 보호 요청은 성공한 refresh 뒤 최대 한 번만 재시도하며, Alarm 생성처럼 idempotency 계약이 없는 mutation은 timeout만으로 자동 POST 재시도하지 않는다.

공개 배포 전뿐 아니라 Alarm API 구현부터 인증된 StopBell User를 기준으로 Alarm 소유권을 처리한다. Client가 제공한 `userId`를 신뢰하지 않는다.

인증 전략의 근거와 제약은 `adr/ADR-005-authentication-and-user-identity-strategy.md`를 따른다.

### 회원 탈퇴 (TASK-815 Backend)

인증된 사용자는 자신의 계정 삭제를 Backend에 요청할 수 있다. Apple 계정은 저장된 토큰 철회를 시도하며 완료·처리 중·수동 Apple 연결 해제 필요를 구분한다. 삭제 도중 신규 인증/사용자 데이터 생성을 차단하고 외부 장애와 DB 실패·재시작 후 삭제를 복구한다. 기존 사용자 소유 데이터 삭제 범위와 타 사용자 알림 관계 보존 정책을 유지한다. 공개 응답·재시도·Token 무효화는 [API](api.md#7-회원-탈퇴-task-815-backend)를 따른다. Flutter는 탈퇴 확인과 receipt 기반 결과 복구 및 Apple 수동 연결 해제 안내를 제공한다. 사용자 보고 실기기 탈퇴 결과와 미확인 철회 증거는 [출시 준비 점검](ios-release-readiness.md#apple-로그인회원-탈퇴-실기기-검증-2026-11-06)을 따르며 출시 검증은 후속 작업이다. TASK-815는 미완료다.

Apple 서버의 검증된 `consent-revoked`와 `account-deleted`는 해당 기존 Apple 계정과 사용자 데이터를 삭제한다. 이후 재인증은 새 StopBell 계정이며 이전 알람과 탑승 데이터는 복원하지 않는다. 이메일 변경 알림은 사용자 데이터를 변경하지 않고, 불확실한 대상은 삭제하지 않고 검토한다. 처리·복구 조건은 [Architecture](architecture.md#apple-서버-간-알림과-자동-탈퇴-task-815)를 따른다.

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
