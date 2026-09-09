# Transit Provider 조사

## 목적과 범위

이 문서는 Phase 3 Transit Foundation에서 Transit Provider 후보의 공식 계약과 실제 관찰 결과를 누적한다.

- V1 초기 지원 범위는 서울특별시와 경기도이다.
- 국토교통부 TAGO는 TASK-301 기준 1순위 후보일 뿐, V1 최종 Provider는 TASK-304에서 결정한다.
- TASK-302는 공식 문서 조사만 수행했다. Service Key를 발급하거나 노선·정류소·위치·도착 API를 실제 호출하지 않았다.
- 실제 서울·경기 coverage, 응답 구조, 갱신 동작 및 누락 사례는 TASK-303에서 관찰한다.

## 공식 자료

조사 기준일: 2026-09-09

| 대상 | 공공데이터포털 공식 페이지 | 공식 참고문서 |
| --- | --- | --- |
| 버스노선정보 | [국토교통부_(TAGO)_버스노선정보](https://www.data.go.kr/data/15098529/openapi.do) | 페이지 첨부 `오픈API활용가이드_국토교통부(TAGO)_버스노선정보v1.0.docx` |
| 버스도착정보 | [국토교통부_(TAGO)_버스도착정보](https://www.data.go.kr/data/15098530/openapi.do) | 페이지 첨부 `오픈API활용가이드_국토교통부(TAGO)_버스도착정보v1.0.docx` |
| 버스위치정보 | [국토교통부_(TAGO)_버스위치정보](https://www.data.go.kr/data/15098533/openapi.do) | 페이지 첨부 `오픈API활용가이드_국토교통부(TAGO)_버스위치정보v1.0.docx` |
| 버스정류소정보 | [국토교통부_(TAGO)_버스정류소정보](https://www.data.go.kr/data/15098534/openapi.do) | 페이지 첨부 `오픈API활용가이드_국토교통부(TAGO)_버스정류소정보v1.0.docx` |

이 문서의 API 기능 및 field 이름은 위 공식 페이지의 상세기능 계약과 첨부 활용가이드를 기준으로 한다. 비공식 블로그·예제는 근거로 사용하지 않았다.

## TASK-302 확인 결과

### 이용 조건과 License

네 API의 공공데이터포털 표시 조건은 모두 동일하다.

| API | 비용 | 이용허락범위 | 개발단계 | 운영단계 |
| --- | --- | --- | --- | --- |
| 버스노선정보 | 무료 | 이용허락범위 제한 없음 | 자동승인 | 자동승인 |
| 버스정류소정보 | 무료 | 이용허락범위 제한 없음 | 자동승인 | 자동승인 |
| 버스위치정보 | 무료 | 이용허락범위 제한 없음 | 자동승인 | 자동승인 |
| 버스도착정보 | 무료 | 이용허락범위 제한 없음 | 자동승인 | 자동승인 |

위 표는 포털이 표시하는 비용과 이용허락범위만 기록한다. 이를 상업적·비상업적 이용 가능 여부에 관한 별도 법률 해석으로 확대하지 않는다.

### 호출 제한과 Polling 제약

- 네 API 모두 포털의 신청 가능 트래픽에 `개발계정 : 10,000`, `운영계정 : 활용사례 등록시 신청하면 트래픽 증가 가능`으로 표시된다.
- 포털 오류 계약에는 일일 호출 허용량 초과(`LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR`, code 22)와 초당 호출 허용량 초과(`LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR`, code 23)가 각각 존재한다.
- 포털의 신청 가능 트래픽 표기는 10,000의 적용 단위가 API Service별인지, 하나의 Service Key가 공유하는 양인지 명시하지 않는다. 따라서 10,000을 특정 API별 일일 한도 또는 Service Key 전체 한도로 확정하지 않는다.
- 각 공식 v1.0 활용가이드는 세부기능 정보에 `초당 최대 트랙잭션 [30] tps`를 표시한다. 이는 공식 문서에 명시된 수치이나, 실제 적용 범위·제한 처리와 서울·경기에서의 지속 호출 가능성은 호출하지 않았으므로 TASK-303에서 확인한다.

StopBell은 활성 Alarm을 주기적으로 평가할 예정이므로 개발계정 호출량 제한과 초당 제한을 전제로 해야 한다. 그러나 이번 Task에서는 polling frequency, Alarm grouping key, 동일 Route/Stop 요청 중복 제거 방식을 결정하지 않는다. 실제 Provider의 갱신·누락·지연을 TASK-303에서 관찰한 뒤 TASK-304~305에서 판단한다.

### Route / Stop 식별자 연결 계약

필드의 요청 표기는 camelCase, 응답 표기는 소문자인 경우가 있으므로 구현 시 구분한다.

| 흐름 | 공식 기능 | 요청 식별자 | 확인된 응답 field 및 연결 |
| --- | --- | --- | --- |
| Route 검색 | `getRouteNoList` | `cityCode`, 선택 `routeNo` | `routeid`, `routeno`, `routetp`, 기·종점명 |
| Route 기본정보 | `getRouteInfoIem` | `cityCode`, `routeId` | `routeid`, `routeno`, `routetp` 등 |
| Route → Stop | `getRouteAcctoThrghSttnList` | `cityCode`, `routeId` | `routeid`, `nodeid`, `nodenm`, `nodeord`, `gpslati`, `gpslong` |
| Route → Location | `getRouteAcctoBusLcList` | `cityCode`, `routeId` | `routenm`, `vehicleno`, `nodeid`, `nodeord`, `gpslati`, `gpslong`, `routetp` |
| Stop 검색 | `getSttnNoList` | `cityCode`, 선택 `nodeNm` 또는 `nodeNo` | `nodeid`, `nodenm`, 선택 `nodeno`, `gpslati`, `gpslong` |
| Stop → Arrival | `getSttnAcctoArvlPrearngeInfoList` | `cityCode`, `nodeId` | `nodeid`, `routeid`, `routeno`, `arrprevstationcnt`, `arrtime` |
| Stop → Route | `getSttnThrghRouteList` | `cityCode`, `nodeid` | `routeid`, `routeno`, `routetp`, 기·종점명 |

`routeId`와 `nodeId`는 각각 Route → Location, Stop → Arrival 요청에서 공식적으로 사용하는 연결 identifier이다. `routeNo`는 노선번호 검색의 선택 조건과 응답 field이며, `routeId`를 대신하는 안정 identifier로 확정하지 않는다.

버스위치정보의 노선 연결은 요청 `routeId`의 문맥으로 이뤄진다. 해당 응답 계약에는 `routeid`가 아니라 `routenm`이 명시되어 있으므로, 응답 item만으로 route identifier를 재구성한다고 가정하지 않는다. `vehicleno`는 차량번호, `nodeord`는 정류소 순서, `gpslati`/`gpslong`은 WGS84 위도/경도다.

버스도착정보에서 `arrprevstationcnt`는 도착예정버스 남은 정류장 수이고, `arrtime`은 도착예상시간(초)이다.

공식 계약은 `routeId`/`nodeId`가 API Service 간 연결에 쓰임을 보장하지만, 노선 개편·정류소 변경·Provider 변경 뒤에도 영구적으로 불변한다는 보장이나 변경 정책은 확인하지 못했다. 장기 identifier 안정성은 미확정이며, 이 값을 영속화하는 방식도 TASK-304 전에는 결정하지 않는다.

### 검색과 도시코드 기능

| 구분 | 공식 제공 기능 | TASK-302 판단 |
| --- | --- | --- |
| Bus Route | 노선번호 목록, 노선 기본정보, 노선별 경유정류소 목록, 도시코드 목록 | 제공 확인 |
| Bus Stop | 정류소명/정류소번호 목록, 좌표 기반 근접정류소 목록, 정류소별 경유노선 목록, 도시코드 목록 | 제공 확인 |
| 근접 Stop | `getCrdntPrxmtSttnList` | 공식 설명상 GPS 좌표 기준 반경 500m 검색 |

각 API의 도시코드 목록 조회는 서비스 가능 지역의 도시코드 목록을 조회하는 기능으로 문서화되어 있다. 이는 서울·경기 도시코드 및 기능 존재를 확인하는 수단이지만, 서울 전체 또는 경기 31개 시군의 Route·Location·Arrival 데이터 완전성을 보장하지 않는다. 실제 coverage는 TASK-303에서 검증한다.

공식 API가 Route 검색과 Route별 Stop 조회 기능을 제공하므로, 현재 조사만으로 Transit 검색을 위한 Local persistence 또는 MyBatis 도입 근거는 확인되지 않았다. 검색 성능, 호출량 절감, static metadata 갱신, Alarm grouping query의 실제 필요성은 TASK-303 관찰 뒤 TASK-507~508에서 판단한다.

### 갱신 정보

- 버스도착정보 공식 활용가이드는 실시간 도착예정정보·운행정보를 조회하는 서비스이고 데이터 갱신주기를 `실시간(10~20초)`로 표시한다.
- 버스위치정보 공식 활용가이드는 현재 운행 중인 시내버스 위치정보를 조회하는 서비스이고 데이터 갱신주기를 `실시간(10~20초)`로 표시한다.
- 버스노선정보와 버스정류소정보 공식 활용가이드는 데이터 갱신주기를 `일 1회`로 표시한다.

위 10~20초는 공식 활용가이드의 데이터 갱신주기 표기다. 서울·경기의 실제 source update interval, API 응답 지연, 동일 응답 반복 여부 및 StopBell이 관찰하는 변경 주기를 보장하거나 실측한 값이 아니다. TASK-303에서 반복 호출로 관찰한다.

## Confirmed / Not confirmed yet

### Confirmed

- TAGO 버스노선·정류소·위치·도착 API의 포털 표시 비용은 무료이고 이용허락범위는 제한 없음
- 네 API의 개발·운영 단계 승인은 자동승인
- 개발계정 신청 가능 트래픽 표기는 10,000이고, 운영계정은 활용사례 등록 후 트래픽 증가 신청 가능
- 일일 호출 제한과 초당 호출 제한 오류 계약 존재
- 공식 v1.0 활용가이드의 세부기능별 최대 트랜잭션 표기는 30 TPS
- Route, Stop, Location, Arrival 및 도시코드 조회 기능 제공
- `routeId`가 Route → Location 요청을 연결하고 `nodeId`가 Stop → Arrival 요청을 연결
- Route 검색·경유정류소 조회, Stop 검색·근접정류소 조회·경유노선 조회 제공
- 좌표기반 근접정류소 검색은 공식 설명상 반경 500m
- 위치·도착 가이드는 실시간(10~20초) 데이터 갱신주기를 표시

### Not confirmed yet

- 10,000 개발계정 트래픽의 API Service별/Service Key 공유 적용 단위
- 30 TPS의 현재 runtime enforcement 범위와 초과 시 실제 동작
- `routeId`/`nodeId`의 장기 불변성 및 변경 정책
- 서울·경기 실제 Coverage completeness
- 서울·경기 실제 응답의 update interval, 지연, 동일 응답 반복, 누락 빈도
- Provider가 V1 요구사항에 충분한지 및 V1 최종 Provider 여부
- polling frequency, Alarm grouping key, duplicate-request 제거, identifier 영속화, Transit metadata persistence/MyBatis 필요성

## TASK-303 실측 검증 항목

실제 Service Key로 서울·경기 데이터를 호출할 때 다음을 기록한다.

1. 서울 Bus Route 및 경기 Bus Route 실제 조회와 도시코드 확인
2. Route → 경유 Stop → Bus Location 연결 가능 여부
3. Stop → Arrival → Route 연결 가능 여부
4. `nodeOrd` 변화, `gpslati`/`gpslong` 변화, `arrprevstationcnt` 변화 관찰
5. 위치·도착 요청의 응답 갱신 간격, 동일 요청 반복 시 변경 주기, 응답 지연 관찰
6. 서울·경기 `routeId`/`nodeId` 형태와 provider namespace 필요성 비교
7. null, empty item, 누락 field, 오류 응답 및 제한 응답 사례 기록
8. 공식 문서의 10~20초 갱신 표기와 30 TPS 표기가 실제 관측과 어떻게 대응하는지 확인

TASK-303은 Production Transit Client 구현이나 V1 Provider 확정 Task가 아니다. 관찰 결과는 TASK-304의 Provider·identifier 전략과 TASK-305의 Transit 관측/Alarm Target 계약 결정에 사용한다.
