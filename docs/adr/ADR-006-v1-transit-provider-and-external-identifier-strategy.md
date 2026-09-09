# ADR-006: V1 Transit Provider 및 External Identifier 전략

- 상태: 채택됨
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
- 서울특별시: 서울특별시 노선정보조회 서비스가 Route/Stop metadata를, 서울특별시 버스위치정보조회 서비스가 realtime Vehicle Location을 제공한다.
- 서울 V1 monitoring에는 Arrival 전용 Provider를 추가하지 않는다.

External Route/Stop identity는 각각 `(provider, externalRouteId)` 및 `(provider, externalStopId)`로 취급한다. V1 provider namespace는 `TAGO`, `SEOUL_BUS`이며 모든 external ID는 opaque String이다. Route number와 Stop name은 display/search metadata, Stop order는 Route 진행 metadata이고 identity가 아니다.

경기 TAGO `cityCode`는 identity 일부가 아니라 API 호출을 재현하기 위한 request context다. 차량 identifier는 monitoring 중 같은 차량 관측을 연결하는 transient reference이며 Alarm의 영구 target identity가 아니다.

## 근거

경기 TAGO는 실제로 Route → Stop → Location과 Stop → Arrival을 연결했고, `nodeId`/`nodeOrd`/GPS 및 first Stop 차량 표본을 제공했다. 서울 Bus Location은 `vehId`, `plainNo`, `stId`, `stOrd`, `sectOrd`, `sectionId`, `stopFlag`, GPS, `dataTm`을 제공하고 target Stop의 도착·출발·다음 Stop 진행을 직접 관찰했다.

서울특별시 노선정보조회 서비스는 현재 공공데이터포털에서 자동승인으로 활용신청 가능하며, `busRouteId` 기반 Route별 Stop 목록에 순번·정류소 ID·명칭·WGS84 좌표를 계약한다. `busRouteId`는 Bus Location 요청 identifier와 같다. 현재 local key에는 이 metadata 서비스의 권한이 없어 Stop ID live row 대조는 하지 못했지만, 이는 source 부재가 아니라 service enrollment 문제다.

## 결과

- 후속 Transit 구현은 두 Provider의 역할을 명시적으로 routing하며, 하나의 전국 Provider 또는 fallback orchestration을 가정하지 않는다.
- Provider raw DTO는 Transit 경계 안에서 변환하고 StopBell Domain/API에 그대로 노출하지 않는다.
- external ID는 String으로 보존하며 parsing·numeric arithmetic·prefix 의존을 하지 않는다.
- 노선 개편·정류소 변경 뒤 Provider ID가 계속 불변한다는 보장은 없으므로 stale target 대응은 향후 실제 필요가 확인될 때 결정한다.
- Alarm Target Schema, observation DTO, 도착/통과/first-stop rule, polling·grouping·duplicate state는 이 ADR이 결정하지 않으며 TASK-305로 남긴다.

## 재검토 시점

다음 중 하나 이상이 확인되면 이 결정을 재검토한다.

- 서울 노선정보조회 서비스가 자동승인 또는 필요한 identifier contract를 더 이상 제공하지 않음
- service enrollment 뒤 `station`과 Bus Location `stId`의 연결이 공식 contract와 다르게 확인됨
- V1 지원 지역이 늘어 TAGO의 cityCode/request context만으로 Provider routing이 불명확해짐
- Provider ID 변경·재사용으로 stale target이 실제 제품 문제를 일으킴
- 호출 제한 또는 data quality가 선택된 Provider 역할을 충족하지 못함
