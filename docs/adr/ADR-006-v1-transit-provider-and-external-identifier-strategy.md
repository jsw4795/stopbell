# ADR-006: V1 Transit Provider 및 External Identifier 전략

- 상태: 채택됨 (서울 static metadata source 부분은 ADR-009로 대체됨)
- 날짜: 2026-09-09

## 배경

StopBell V1은 서울특별시와 경기도에서 사용자가 버스 번호를 검색하고, 노선을 선택해 정류장을 고른 뒤 해당 노선의 다음 차량을 감시해야 한다. TASK-301~303과 First Stop 보충 PoC에서 경기 TAGO는 Route·Stop·Location·Arrival 전체 흐름을, 서울특별시 버스위치정보조회 서비스는 차량 연속 식별과 `stopFlag` 기반 정류소 도착 상태를 실제로 확인했다.

그러나 TAGO 공통 API에서는 서울 실제 Route flow를 확보하지 못했고, 서울 Bus Location 단독으로는 Route 검색과 정류소명 목록을 제공하지 않는다. 서로 다른 upstream Provider의 raw identifier를 전역 key나 숫자로 해석하면 Provider 교체·확장·ID 충돌에 취약하다.

## 검토한 선택지

### 선택지 A — 하나의 전국 Provider만 사용

장점:

- Provider client와 identifier 형태를 하나로 제한할 수 있음

단점:

- 현재 실측에서 TAGO의 서울 Route/Stop/Location flow를 확보하지 못함
- 서울 Bus Location의 직접 `stopFlag`와 차량 연속성이라는 검증된 강점을 포기하거나 별도 보완이 필요함

### 선택지 B — 서울·경기 지역별 공식 Provider 사용

장점:

- 경기는 TAGO의 metadata·Location·Arrival 연결을 그대로 사용 가능
- 서울은 노선정보조회 서비스의 사용자 metadata와 버스위치정보조회 서비스의 realtime 관측을 각각 사용 가능
- 현재 V1 범위에 필요한 최소 Provider 수로 각 역할을 충족

단점:

- Provider별 client·response mapping과 request context를 구분해야 함
- raw field 이름과 관측 특성을 공통 Domain에 직접 노출할 수 없음

### 선택지 C — Provider raw identifier를 Domain identity로 직접 사용

장점:

- 초기 구현에서 mapping code가 적음

단점:

- 서울과 경기 ID의 namespace가 섞일 수 있음
- 숫자처럼 보이는 ID, Route number, Stop name, Stop order를 잘못된 identity로 사용할 위험이 있음
- Provider 변경 또는 stale metadata를 처리하기 어려움

### 선택지 D — Provider namespace와 opaque external identifier 사용

장점:

- Provider별 raw ID 충돌을 피함
- ID 형식의 내부 구조·숫자성에 의존하지 않음
- Provider request context와 display metadata를 identity와 분리할 수 있음

단점:

- 후속 Schema·DTO에서 provider를 함께 다뤄야 함

## 결정

V1은 지역별 공식 Provider를 사용한다.

- 경기도: 국토교통부 TAGO가 Route metadata, Stop metadata, realtime Vehicle Location, Arrival 보조 정보를 제공한다.
- 서울특별시: 당시에는 서울특별시 노선정보조회 서비스가 Route/Stop metadata를, 서울특별시 버스위치정보조회 서비스가 realtime Vehicle Location을 제공하는 것으로 결정했다.
- 서울 V1 monitoring에는 Arrival 전용 Provider를 추가하지 않는다.

External Route/Stop identity는 각각 `(provider, externalRouteId)` 및 `(provider, externalStopId)`로 취급한다. V1 provider namespace는 `TAGO`, `SEOUL_BUS`이며 모든 external ID는 opaque String이다. Route number와 Stop name은 display/search metadata, Stop order는 Route 진행 metadata이고 identity가 아니다.

경기 TAGO `cityCode`는 identity 일부가 아니라 API 호출을 재현하기 위한 request context다. 차량 identifier는 monitoring 중 같은 차량 관측을 연결하는 transient reference이며 Alarm의 영구 target identity가 아니다.

## 근거

경기 TAGO는 실제로 Route → Stop → Location과 Stop → Arrival을 연결했고, `nodeId`/`nodeOrd`/GPS 및 first Stop 차량 표본을 제공했다. 서울 Bus Location은 `vehId`, `plainNo`, `stId`, `stOrd`, `sectOrd`, `sectionId`, `stopFlag`, GPS, `dataTm`을 제공하고 target Stop의 도착·출발·다음 Stop 진행을 직접 관찰했다.

서울특별시 노선정보조회 서비스는 당시 공공데이터포털에서 자동승인으로 활용신청 가능하며, `busRouteId` 기반 Route별 Stop 목록에 순번·정류소 ID·명칭·WGS84 좌표를 계약했다. `busRouteId`는 Bus Location 요청 identifier와 같다. 이 내용은 당시 선택의 근거를 보존한다.

## 후속 확인

TASK-305 시작 시 활성화된 서울 노선정보조회 서비스를 live preflight했다. `routeNo=7016` 검색 결과의 `busRouteId=100100447`로 Stop 106건을 조회했고, 동일 Route의 realtime 차량 표본에서 metadata `station=113000022`/`seq=3`과 Bus Location `stId=113000022`/`stOrd=3`이 일치했다. 따라서 TASK-304에서 공식 contract로 채택한 서울 metadata ↔ realtime identifier 연결을 실제 row에서도 확인했다. 이 확인은 Provider ID의 장기 불변성을 새로 보장하지 않는다.

## 후속 결정 및 대체 범위

2026-09-13의 PoC와 ADR-009는 서울 static Route/Stop metadata source를 서울 T Data CSV full import로 변경했다. 이 ADR의 지역별 Provider routing, `SEOUL_BUS` namespace, opaque external identifier 정책과 서울 realtime Vehicle Location provider 선택은 계속 유효하다. 다만 이 ADR에서의 서울 노선정보조회 서비스 static metadata source 결정은 더 이상 현재 architecture의 적용 계약이 아니다. 조사·live preflight 기록은 identifier compatibility의 역사적 근거로 보존한다.

## 결과

- 후속 Transit 구현은 두 Provider의 역할을 명시적으로 routing하며, 하나의 전국 Provider 또는 fallback orchestration을 가정하지 않는다.
- 서울 static metadata는 ADR-009에 따라 T Data CSV full import를 사용하고, 서울 버스위치정보조회 서비스는 realtime Location 역할을 유지한다.
- Provider raw DTO는 Transit 경계 안에서 변환하고 StopBell Domain/API에 그대로 노출하지 않는다.
- external ID는 String으로 보존하며 parsing·numeric arithmetic·prefix 의존을 하지 않는다.
- 노선 개편·정류소 변경 뒤 Provider ID가 계속 불변한다는 보장은 없으므로 stale target 대응은 향후 실제 필요가 확인될 때 결정한다.
- Alarm Target Schema와 lifecycle persistence는 TASK-401 및 ADR-008에서 결정했다. Observation DTO, polling·grouping·duplicate state는 후속 Task 범위다.

## 재검토 시점

다음 중 하나 이상이 확인되면 이 결정을 재검토한다.

- 서울 T Data CSV가 `SEOUL_BUS` identifier compatibility 또는 complete source contract를 더 이상 충족하지 않음
- 후속 실측에서 `station`과 Bus Location `stId`의 연결이 반복적으로 성립하지 않음
- V1 지원 지역이 늘어 TAGO의 cityCode/request context만으로 Provider routing이 불명확해짐
- Provider ID 변경·재사용으로 stale target이 실제 제품 문제를 일으킴
- 호출 제한 또는 data quality가 선택된 Provider 역할을 충족하지 못함
