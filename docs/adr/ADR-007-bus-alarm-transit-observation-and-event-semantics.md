# ADR-007: Bus Alarm Transit Observation 및 Event 의미

- 상태: 채택됨
- 날짜: 2026-09-09

## 배경

TASK-304는 서울과 경기의 V1 Provider 및 External Route/Stop Identifier를 결정했다. 다음 구현 Task가 같은 제품 의미를 공유하려면 Provider raw response를 어떤 `TransitObservation`으로 해석하고, 사용자가 저장하는 Bus Alarm Target과 도착·통과 Event를 어떻게 구분할지 먼저 정해야 한다.

서울 Bus Location은 `stopFlag`라는 직접 도착 신호를 제공하지만 경기 TAGO Location에는 같은 신호가 없다. 두 Provider 모두 갱신 지연으로 target Stop을 건너뛴 관측이 가능하고, 방향·회차·stale data 또는 차량 식별 불연속이 있으면 단순 Stop order 비교가 거짓 알림을 만들 수 있다. 한편 PASSED 뒤에도 사용자는 다음 버스를 기다려야 하며, ARRIVED 뒤 선택적인 한 정거장 후 알림은 Alarm 활성 상태와 다른 수명의 추적이 필요하다.

## 검토한 선택지

### 선택지 A — ARRIVED와 PASSED를 모두 Alarm 성공 및 종료로 처리

장점:

- 하나의 Terminal Event 규칙으로 구현할 수 있음

단점:

- polling 사이 target을 건너뛴 차량 때문에 Alarm이 종료되어 다음 차량을 놓침
- PASSED와 정상 도착의 사용자 의미가 다름

### 선택지 B — ARRIVED만 성공으로 처리하고 PASSED 뒤 다음 차량 계속 추적

장점:

- 정상 도착을 Alarm 성공의 명확한 기준으로 유지
- 놓친 차량의 최근 위치를 알리면서 다음 차량 감시를 계속할 수 있음

단점:

- Alarm과 차량별 tracking lifecycle을 분리해야 함

### 선택지 C — Provider raw field를 평가 규칙에서 직접 사용

장점:

- 초기 mapping이 단순함

단점:

- 서울 `stopFlag`와 경기의 flag 부재를 같은 boolean으로 왜곡할 수 있음
- Provider별 field 이름과 결측 의미가 Alarm Domain에 누출됨

### 선택지 D — Provider-neutral Observation과 명시적인 evidence availability 사용

장점:

- 도착 신호의 `MOVING`과 `UNAVAILABLE`을 구분함
- 공통 평가 규칙이 raw field 이름 대신 관측 의미에 의존함
- 불충분하거나 상충하는 관측을 `UNKNOWN`으로 보존할 수 있음

단점:

- Provider별 mapper가 raw field의 의미를 정확히 변환해야 함

### 선택지 E — ARRIVED 뒤 Alarm을 ACTIVE로 유지해 after 알림까지 처리

장점:

- 별도 follow-up 개념 없이 기존 활성 상태를 재사용할 수 있음

단점:

- 이미 성공한 Alarm이 활성 Alarm 목록과 다음 차량 monitoring에 남음
- 사용자 활성 상태와 성공 차량의 짧은 후속 추적 의미가 섞임

### 선택지 F — ARRIVED에서 Alarm을 종료하고 성공 차량만 short follow-up

장점:

- Alarm 성공/비활성화 규칙이 옵션과 무관하게 일관됨
- 다른 차량 감시를 즉시 끝내면서 동일 차량의 after 알림만 유지할 수 있음

단점:

- 후속 구현에서 active와 분리된 일시적 tracking state가 필요할 수 있음

## 결정

V1은 Provider raw response를 `TransitObservation`이라는 한 차량의 provider-neutral 관측 사실로 변환한다. 필수 envelope는 Provider와 Route external reference, transient `vehicleTrackingId`, StopBell 수신 시각, 직접 도착 근거다. 현재 Stop external reference와 Route 진행 순서, Stop 표시명·GPS, Provider data 시각, 방향·구간 진행 문맥은 Provider가 제공하거나 metadata로 안전하게 보강할 수 있을 때만 포함한다. 없는 값은 만들지 않는다. 필수 envelope가 없는 raw item은 다른 차량에 임의 연결하지 않고 UNKNOWN을 만드는 mapping ambiguity로 취급한다. 이전 위치는 현재 Observation에 복제하지 않고 같은 차량의 이전 Observation과 비교한다.

직접 도착 근거는 `ARRIVED`, `MOVING`, `UNAVAILABLE`을 구분한다. 서울 `stopFlag=1`은 `ARRIVED`, `stopFlag=0`은 `MOVING`으로 변환한다. 경기 TAGO Location은 직접 flag가 없으므로 `UNAVAILABLE`이며, 차량 ID가 없는 Arrival API는 vehicle-specific truth로 승격하지 않는다.

Observation-derived 위치 관계와 사용자 Event를 분리한다. 위치 관계는 `BEFORE_TARGET`, `AT_TARGET`, `AFTER_TARGET`, `UNKNOWN`이고, 알림 후보 Event는 `ONE_STOP_BEFORE`, `ARRIVED`, `PASSED`, `ONE_STOP_AFTER` 또는 없음이다. `UNKNOWN`은 알림 Event가 아니라 판단 불가 결과다. `APPROACHING`은 `BEFORE_TARGET`의 표시용 해석일 수 있지만 별도 V1 Event로 두지 않는다.

Route external identity는 `(provider, externalRouteId)`, Stop external identity는 `(provider, externalStopId)`다. Bus Alarm이 가리키는 Target은 Stop identity 자체가 아니라 선택한 Route traversal 안의 특정 Stop occurrence다. 이 occurrence는 Route/Stop reference와 `targetStopOrder`, 그리고 모호성을 해소하는 데 필요한 traversal·direction context로 평가한다. `targetStopOrder`는 occurrence를 구분하는 필수 operational snapshot이지 Stop identity가 아니다. 같은 Route가 같은 Stop을 재방문할 수 있으므로 `(provider, externalRouteId, externalStopId)`만으로 Target occurrence의 uniqueness가 보장된다고 가정하거나 이 조합만을 근거로 Unique Constraint를 만들지 않는다.

`routeNumber`와 `stopName`은 display snapshot이고, target Stop 좌표는 Provider가 제공할 때 판단을 보조하는 operational snapshot이다. TAGO `cityCode` 같은 Provider request context는 호출 재현에 필요한 조건부 metadata다. `notifyOneStopBefore`와 `notifyOneStopAfter`는 서로 독립적인 선택 옵션이다.

Route metadata traversal에서 predecessor가 없으면 before 옵션, successor가 없으면 after 옵션을 생성 시 거부할 수 있어야 한다. predecessor/successor는 단순 `order ± 1`이 아니라 같은 방향·Route traversal의 인접 Stop으로 해석한다. 구체적인 HTTP 오류와 DB 구조는 후속 Task에서 정한다.

before 옵션이 켜진 Alarm을 활성화하는 순간 차량이 Target occurrence의 predecessor에 있다고 충분히 판단되면 즉시 `ONE_STOP_BEFORE` 후보를 만들 수 있다. 이 차량은 baseline이라는 이유로 알림에서 제외하지 않으며, Event를 한 번 소비한 뒤에도 Alarm을 ACTIVE로 유지하고 같은 차량을 ARRIVED까지 계속 추적한다.

`ARRIVED`는 같은 Route/차량이 target에 도착했다는 충분하고 일관된 근거가 있을 때 발생한다. 활성화 순간 이미 target에 있는 차량도 같은 기준을 만족하면 즉시 ARRIVED다. ARRIVED Notification 뒤 Alarm은 성공 처리되어 비활성화되고, after 옵션이 꺼져 있으면 모든 tracking을 종료한다.

`PASSED`는 Alarm 활성화 뒤 target 이전부터 같은 tracking cycle에서 관찰한 차량이, 직접 ARRIVED를 관찰하지 못한 채 target 이후로 진행했다는 충분한 근거가 있을 때 발생한다. 방향·sequence가 비교 가능하고 data가 fresh하며 신호가 일관되어야 한다. PASSED는 해당 차량 tracking만 끝내고 Alarm은 ACTIVE로 유지해 다음 차량을 감시한다. 활성화 당시 이미 target 이후인 차량은 baseline existing vehicle로 무시하며 PASSED를 만들지 않는다.

PASSED 위치의 optional `stopsPastTarget`은 raw Stop order의 숫자 차이가 아니다. 같은 Route traversal에서 Target occurrence부터 현재 확인된 Stop occurrence까지 metadata sequence로 확인한 successor edge 수다. 두 occurrence와 그 사이 traversal을 확정할 수 있을 때만 계산하며, 불확실하면 값을 제공하지 않는다. `stopsPastTarget`이 없어도 PASSED와 최근 확인 위치 Notification은 발생할 수 있다.

after 옵션이 켜진 ARRIVED에서는 Alarm을 비활성화하고 다른 차량 tracking을 끝낸 뒤 ARRIVED 차량만 short follow-up 한다. 같은 방향·Route traversal의 다음 Stop에 도달하거나 polling jump로 그 Stop 이상 진행했다는 충분한 근거가 있으면 `ONE_STOP_AFTER`를 한 번 발생시키고 follow-up을 끝낸다. 이 진행을 PASSED로 다시 분류하지 않는다. follow-up state의 영속화 필요성과 만료 정책은 TASK-401/509에서 판단한다.

short follow-up이 남아 있는 동일 Alarm을 사용자가 다시 활성화하면 이전 activation cycle의 follow-up을 취소하고 새 baseline과 새 monitoring cycle을 시작한다. 새 activation이 이전 cycle을 supersede하므로 old ONE_STOP_AFTER와 새 cycle Event를 동시에 유지하지 않는다. Alarm 삭제는 active monitoring뿐 아니라 그 Alarm에 연결된 short follow-up도 함께 종료한다. 구체적인 runtime/persistence 방식은 TASK-401/509/510에서 결정한다.

한 Observation transition은 사용자에게 가장 의미 있는 Event 하나만 선택한다. 일반 tracking에서는 직접 관찰한 ARRIVED, target을 건너뛴 PASSED, ONE_STOP_BEFORE 순으로 우선한다. ARRIVED 후 follow-up에서는 ONE_STOP_AFTER만 평가한다. 동일 Alarm·Vehicle·Event Type은 같은 tracking cycle에서 한 번만 의미가 있다.

차량 ID 변경·누락, 일시적 차량 소실, Stop order 역행, stale data, 방향·회차·순환 Route의 모호함, 필수 reference 누락 또는 서로 충돌하는 신호는 `UNKNOWN`으로 두고 알림을 만들지 않는다. 외부 API timeout·HTTP/provider error는 Observation의 애매함과 구분되는 Provider failure지만, 평가 결과는 동일하게 Event 없는 UNKNOWN이다.

## 근거

이 결정은 Provider arrival flag가 완벽할 때만 알림을 허용하지 않으면서도, 약하거나 모순된 데이터로 거짓 알림을 만들지 않는다. 서울의 직접 신호를 보존하고 경기 first-stop의 `nodeId`·`nodeOrd`·GPS·동일 차량 진행을 조합할 수 있어 두 Provider의 실제 강점을 잃지 않는다.

ARRIVED만 Alarm 성공으로 두면 정상 성공 lifecycle이 단순해지고, PASSED 뒤 다음 차량을 계속 기다리는 제품 요구를 만족한다. Alarm 비활성화와 동일 차량 follow-up을 분리하면 after 옵션 때문에 활성 Alarm의 의미가 달라지는 문제도 피한다.

## 결과

- TASK-401은 Target occurrence를 표현할 최소 Domain/Schema와 follow-up runtime state의 영속 필요성을 결정한다. Route/Stop reference만으로 occurrence uniqueness를 가정하지 않는다.
- TASK-402는 생성·조회·활성화·삭제 계약, before/after validation과 오류 응답을 구체화한다.
- TASK-504는 Provider raw DTO를 `TransitObservation`으로 변환하고 `TransitEvent`의 provider-neutral 표현을 정의한다. Event 판정 자체는 Provider mapper가 아니라 TASK-509 Evaluation이 담당한다.
- TASK-509는 evidence 조합, transition precedence, activation 시 predecessor, `stopsPastTarget`, tracking cycle과 UNKNOWN 규칙을 구현하고 GPS threshold·freshness 기준을 실측으로 정한다.
- TASK-510은 Alarm active lifecycle과 short follow-up lifecycle을 구분하고 재활성화·삭제의 취소 의미를 반영해 scheduling한다.
- Provider identifier는 ADR-006의 namespace·opaque String 정책을 그대로 따른다.
- 실제 Entity, Migration, DTO, polling interval, GPS threshold, persistence와 중복 방지 저장소는 이 ADR에서 결정하지 않는다.

## 재검토 시점

다음 중 하나 이상이 확인되면 이 결정을 재검토한다.

- Provider가 vehicle-specific arrival/departure event stream을 안정적으로 제공해 polling transition 추론이 불필요해짐
- 순환·분기 노선에서 현재 Route traversal metadata로 target occurrence를 식별할 수 없음
- after follow-up을 안전하게 완료하려면 Alarm active와 분리된 계약만으로 부족함
- 실제 사용자 관찰에서 현재 ARRIVED/PASSED 성향이 명백한 누락 또는 거짓 알림을 반복해서 만듦
