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

## TASK-303 실제 API PoC

### 실행 시각 및 환경

- 실행일: 2026-09-09 KST. 반복 관찰은 17:36:31~17:37:45 KST에 수행했다.
- Repository root의 local-only `.env`에서 `TAGO_SERVICE_KEY`를 process environment로만 로드했다. Key의 원문·요청 URL은 출력하거나 저장하지 않았다.
- Production Java code, Dependency, Spring Bean/DTO/Controller는 만들지 않았고, 조사에 사용한 script는 repository 밖의 임시 경로에만 두었다.

### Service Key 전달 방식

`.env`의 값을 수정하거나 decode해서 다시 저장하지 않았다. `%`를 이미 포함할 수 있는 원문 Key는 query string의 `serviceKey`에 그대로 붙이고, 나머지 query parameter만 URL encoding했다. 이 방식으로 도시코드·Route·Stop·Location·Arrival 요청이 `resultCode=00`으로 성공했다. 따라서 이 PoC에서는 Key의 double encoding이 발생하지 않았다.

### 도시코드 확인

Route와 Stop 도시코드 목록 API는 같은 결과를 반환했다.

- 경기도는 하나의 코드가 아니라 시·군별 코드였다. 예를 들어 `31010`은 수원시, `31100`은 고양시였고, 목록에는 성남시(`31020`), 의정부시(`31030`) 등도 별도 항목으로 있었다.
- 수원시(`31010`) Route 목록은 `totalCount=104`, 고양시(`31100`) Route 목록은 `totalCount=53`으로 각각 정상 응답했다. 이는 두 서로 다른 경기 지역의 기본 Route 데이터 제공 표본일 뿐, 31개 시·군 coverage 증명은 아니다.
- 서울특별시 항목은 두 도시코드 목록 응답에 없었다. 서울 코드 후보 `11`로 Route 목록 및 `routeNo=100`, `routeNo=7016` 검색을 각각 시도했으나 모두 HTTP 200 / `resultCode=00` / `totalCount=0`이었다. 따라서 이 Service Key와 2026-09-09 현재 TAGO 공통 API에서는 서울 cityCode와 서울 실제 Flow를 확보하지 못했다.

### 경기 실제 Flow

수원시(`31010`)에서 당시 운행 중인 Route를 응답에서 선택했다.

| 단계 | 실제 응답의 대표 field 및 값 | 확인 결과 |
| --- | --- | --- |
| Route 검색 | `routeid=GGB200000006`, `routeno=300`, `routetp=일반버스`, `startnodenm=수월암리공단`, `endnodenm=롯데백화점.범계역` | Route 검색 성공 |
| Route → Stop | 163개 item. 첫 item은 `nodeid=GGB214001355`, `nodenm=수월암리공단`, `nodeord=1`, `gpslati=37.1185833`, `gpslong=127.0248`, `routeid=GGB200000006` | 하나의 Route에 순서가 있는 여러 Stop 연결 확인 |
| Route → Location | 25개 vehicle item. `vehicleno`, `nodeid`, `nodeord`, `gpslati`, `gpslong`, `routenm=300`, `routetp=일반버스` 존재 | 현재 차량 위치 조회 성공 |
| Stop → Arrival | Route의 `nodeid=GGB200000080` (`nodeord=55`)로 조회. Arrival item에 `nodeid=GGB200000080`, `routeid=GGB200000006`, `routeno=300`, `arrprevstationcnt`, `arrtime` 존재 | Stop에서 원 Route로 역연결 성공 |

Location item의 예시는 `vehicleno=경기70바3985`, `nodeid=GGB214000513`, `nodeord=7`, `gpslati=37.10925`, `gpslong=127.06366`이었다. 이 `nodeid`/`nodeord`는 Route → Stop 목록의 동일 값과 일치했다. Location item에는 `routeid`가 없었고, 요청한 `routeId=GGB200000006` 문맥 및 `routenm=300`으로만 Route와 연결됐다.

Arrival 응답에는 동일 Route의 복수 item이 가능했다. 당시 선택 Stop의 Route 300 item 두 개에서 가장 빠른 도착예정 값은 `arrprevstationcnt=1`, `arrtime=49`초였다. Arrival item에는 `vehicleno`가 없으므로 Arrival 값만으로 특정 Location 차량 하나와 일대일 대응시키지는 못했다.

### Route → Stop → Location / Stop → Arrival → Route 연결 결과

- `routeid`는 Route 검색 및 Route → Stop 응답에 있고, Route → Location의 **요청** 연결 identifier로 실제 사용 가능했다. 다만 Location item 자체에 `routeid`는 없었다.
- `nodeid`와 `nodeord`는 Route → Stop과 Location에서 모두 일치했다. Location의 `nodeord`는 Route 진행 상태를 판단하는 실측 후보 field다.
- Route → Stop에서 선택한 `nodeid`는 Arrival 요청에 사용 가능했고, Arrival item의 `routeid`가 원 Route와 일치했다.
- 이 연결 가능성은 장기간 절대 변경되지 않는 identifier라는 뜻은 아니며, 안정성·영속 전략은 TASK-304에서 별도 판단한다.

### TAGO 경기 실시간 갱신 관찰

TAGO 공통 API에서는 서울 Location/Arrival 표본을 확보하지 못했으므로, 아래 관찰은 수원 Route 300 및 Stop `GGB200000080`의 경기 표본이다. Location과 Arrival을 순차 요청해 12회 관찰했다. 요청 사이에는 약 5초를 두었으며 응답 대기 때문에 실제 관찰 시각 간격은 5~12초였다. 총 24 API 요청/약 74초로 약 0.32 TPS였으며, 공식 30 TPS 표기보다 충분히 낮았다.

Arrival은 같은 Route의 복수 item 중 `arrtime`이 가장 작은 item을 기록했다. `L/A`는 각각 Location/Arrival item count이며, 모든 표의 요청은 HTTP 200 / `resultCode=00`이었다.

| KST 시각 | # | L/A | 차량 `nodeid` / `nodeord` | GPS | Route 300 `arrprevstationcnt` / `arrtime` | 변화 |
| --- | ---: | --- | --- | --- | --- | --- |
| 17:36:31 | 1 | 25 / 29 | `GGB214000513` / 7 | 37.10925, 127.06366 | 4 / 395 | 기준 |
| 17:36:37 | 2 | 25 / 29 | `GGB214000513` / 7 | 동일 | 4 / 395 | 없음 |
| 17:36:42 | 3 | 25 / 29 | `GGB214000513` / 7 | 동일 | 2 / 216 | Arrival 변경 |
| 17:36:48 | 4 | 25 / 29 | `GGB214000513` / 7 | 동일 | 2 / 216 | 없음 |
| 17:36:57 | 5 | 25 / 29 | `GGB214000513` / 7 | 동일 | 2 / 216 | 없음 |
| 17:37:02 | 6 | 25 / 29 | `GGB214000512` / 8 | 37.11404, 127.06357 | 2 / 216 | Location 변경 |
| 17:37:08 | 7 | 25 / 29 | `GGB214000512` / 8 | 동일 | 2 / 216 | 없음 |
| 17:37:14 | 8 | 25 / 29 | `GGB214000512` / 8 | 동일 | 2 / 216 | 없음 |
| 17:37:26 | 9 | 25 / 29 | `GGB214000512` / 8 | 동일 | 2 / 216 | 없음 |
| 17:37:34 | 10 | 25 / 29 | `GGB214000512` / 8 | 동일 | 2 / 216 | 없음 |
| 17:37:39 | 11 | 25 / 29 | `GGB214000512` / 8 | 동일 | 2 / 216 | 없음 |
| 17:37:45 | 12 | 25 / 29 | `GGB214000512` / 8 | 동일 | 2 / 216 | 없음 |

이 한 표본에서 Arrival 핵심 field는 관찰 시작 약 11초 뒤 변경되어 공식 `10~20초` 표기와 대체로 맞았다. Location 핵심 field는 약 31초 뒤 한 번 변경되어 이 표본에서는 더 느렸다. 이후에는 둘 다 관찰 종료까지 동일했다. 따라서 Provider 전체의 영구 갱신주기를 확정할 수 없고, 이 표본의 패턴은 **불규칙**으로 기록한다.

### 서울/경기 Identifier 비교

TAGO 수원 표본에서 `routeid`와 `nodeid`는 모두 영문 provider prefix를 포함한 문자열(`GGB...`)이었고, `nodeord`는 숫자 문자열, `vehicleno`는 한글 지역명·숫자·한글 문자가 결합된 문자열이었다. 서울 버스위치 API 실응답과의 비교는 이 문서 후반의 `서울 / 경기 실제 결과 비교`에 기록한다. 두 Provider의 field contract가 동일하다고 가정하지 않는다.

### Empty / Null / Error 사례

- Empty: 서울 코드 후보 `11`의 Route 검색은 정상 응답(`00`)이면서 `totalCount=0`, 빈 `items`였다. 이는 HTTP/API 실패와 현재 결과 없음이 구분되는 사례다.
- Field 누락: Route → Stop 163개 item 중 `nodeno`가 없는 item이 있었다. 이 표본에서 `nodeid`, `nodenm`, `nodeord`, `gpslati`, `gpslong`, `routeid`는 존재했다. 명시적 `null` literal은 확인하지 못했다.
- Error: 조사 중 잘못된 Arrival service path는 HTTP 400, `NO_OPENAPI_SERVICE_ERROR`, `returnReasonCode=12`를 반환했다. 공공데이터포털 현재 페이지에서 확인한 `ArvlInfoInqireService` path로 정정한 뒤 Arrival 조회는 성공했다.
- 반복 관찰 전에 중단된 탐색 실행에서 Location/Arrival 모두 `resultCode=99`, 빈 item이 한 번 나타났으나 메시지를 보관하지 못했다. 이후의 12회 표본은 모두 `00`이었다. 이를 rate-limit 또는 Provider 장애로 단정하지 않는다.
- 인증 오류는 없었다. Service Key 자체는 어떤 출력·문서·임시 script에도 기록하지 않았다.

### StopBell 관점의 관찰과 남은 불확실성

경기 표본에서는 `routeid`, 목표 `nodeid`/`nodeord`, Location의 차량 `nodeid`/`nodeord`/GPS/`vehicleno`, Arrival의 `routeid`/`arrprevstationcnt`/`arrtime`를 조합할 수 있었다. 이는 TASK-305에서 도착 또는 통과 판단 근거를 검토할 실측 field를 제공한다. 특히 Location의 `nodeord`와 GPS는 차량의 진행을, Arrival의 남은 정류장 수와 예상시간은 목표 Stop 접근을 보조할 수 있다.

다만 Arrival에는 차량 식별자가 없어 Location 차량과 Arrival item의 직접 대응은 이 표본만으로 확인되지 않았고, 복수 Arrival item의 의미와 방향 구분도 미확정이다. TAGO 공통 API의 서울 data 부재는 별도 서울 버스위치 API PoC로 보완했지만, 서울·경기 동시 V1의 Provider 적합성 및 routing 전략은 TASK-304에서 판단한다.

### 서울 버스위치정보조회 서비스 PoC

#### 공식 API Contract

2026-09-09에 [서울특별시_버스위치정보조회 서비스 공식 페이지](https://www.data.go.kr/data/15000332/openapi.do) 및 첨부 `서울특별시_버스위치정보조회_서비스_활용가이드_20230530.docx`를 확인했다. 이 페이지의 개발계정 신청 가능 트래픽 표시는 1,000이며, 활용가이드는 데이터 갱신주기를 매 5초, 각 상세기능 최대 트랜잭션을 30 TPS로 기재한다. 이 Task의 실제 요청률은 그보다 충분히 낮았다.

| 공식 상세기능 | 요청값 | 핵심 응답 field | PoC 용도 |
| --- | --- | --- | --- |
| `getBusPosByRtidList` | `busRouteId` | `vehId`, `plainNo`, `sectOrd`, `sectionId`, `stopFlag`, `dataTm`, `gpsX`/`gpsY`, `nextStId`, `isrunyn`, `congetion` 등 | 노선의 전체 운행 차량 조회 |
| `getBusPosByRouteStList` | `busRouteId`, `startOrd`, `endOrd` | `routeId`, `sectOrd`, `sectionId`, `stopFlag`, `vehId`, `plainNo`, `dataTm`, `tmX`/`tmY` 등 | 특정 정류소 순번 구간의 차량 조회 |
| `getBusPosByVehIdItem` | `vehId` | `stId`, `stOrd`, `stopFlag`, `vehId`, `plainNo`, `dataTm`, `tmX`/`tmY` 등 | 한 차량의 현재 정류소 및 도착 상태 추적 |

공식 가이드에서 `stopFlag`는 `0=운행중`, `1=정류소 도착`이다. `sectOrd`는 구간순번, `stOrd`는 정류소순번이고 `sectionId`는 구간 ID다. `vehId`는 버스 ID, `plainNo`는 차량번호다. `gpsX`/`gpsY` 및 `tmX`/`tmY`는 WGS84 맵매칭 좌표이며, `posX`/`posY`는 GRS80 좌표다. 운영 code나 DTO는 만들지 않았다.

#### Service Key / 호출 확인

기존 `.env`의 일반 인증키를 원문 그대로 `serviceKey` query parameter에 전달하고, 나머지 parameter만 URL encoding했다. `.env`를 수정·decode·재저장하지 않았으며, 이 방식으로 서울 API가 HTTP 200 / `headerCd=0` / `headerMsg=정상적으로 처리되었습니다.`를 반환했다. Service Key 원문과 요청 URL은 출력·저장하지 않았다.

#### 테스트 Route / Stop 및 metadata 한계

공식 버스위치 활용가이드의 요청 예시에서 제공한 `busRouteId=100100118`을 manual Route identifier로 사용했다. 실제 현재 위치 응답에서 운행 차량 18대를 반환해 테스트 대상으로 적합했다.

테스트 target은 반복 추적한 차량이 실제 도착한 `stId=112000001`, `stOrd=22`다. 이 API는 정류소 이름을 반환하지 않으므로 target의 **이름은 확보하지 못했다**. 또한 현재 활용 권한으로는 별도 공식 서울 노선정보조회 서비스의 `getStaionByRoute` 호출이 HTTP 401(해당 서비스에 등록되지 않은 Service Key)으로 실패했다. 버스위치 서비스 자체 인증은 성공했으므로 일반 인증키가 잘못된 사례가 아니라 서비스별 이용 권한/metadata 접근이 별도인 사례다.

따라서 이 PoC의 manual Route/Stop identifier는 실시간 monitoring 검증용일 뿐, 사용자 Route 검색 → Stop 이름 목록 → 선택 UI를 해결하지 않는다. 서울 Route/Stop metadata source와 해당 서비스 이용 권한은 별도 후속 확인이 필요하다.

#### 운행 차량 조회 및 차량 식별

`getBusPosByRtidList(busRouteId=100100118)`는 18개 item을 반환했다. 같은 응답에 서로 다른 `vehId`와 `plainNo`가 있어 노선의 여러 차량을 구분할 수 있었다. 예를 들어 추적 대상은 `vehId=111033668`, `plainNo=서울75사2646`으로 모든 polling에서 동일하게 유지됐다.

전체 노선 조회에서는 `routeId` field가 실제 item에 없는 경우가 있었으므로, 이 응답의 Route 연결은 요청 `busRouteId` 문맥에 의존한다. 반면 `getBusPosByRouteStList`의 같은 차량 item에는 `routeId=100100118`이 존재했다. 두 operation의 item field가 완전히 같다고 가정할 수 없다.

#### 정류소 도착 여부와 실제 접근 → 도착 → 통과 관찰

`getBusPosByRtidList` 초기 관찰(19:04:34 KST)에서 같은 차량은 `sectOrd=21`, `stopFlag=0`이었다. 이어 `getBusPosByVehIdItem`에서 19:05:56 KST에는 `stId=112000001`, `stOrd=22`, `stopFlag=1`로 확인됐다. 이는 target 정류소 도착을 직접 나타내는 실제 표본이다.

그 뒤 5초 간격으로 `getBusPosByVehIdItem`을 20회 관찰했다. `dataTm`은 7~21초 간격으로 갱신됐고, 동일 `vehId`/`plainNo`가 유지됐다.

| KST 시각 | 요청 | `stId` / `stOrd` | `stopFlag` | WGS84 `tmX`, `tmY` | `dataTm` | target 관계 |
| --- | ---: | --- | ---: | --- | --- | --- |
| 19:06:46 | 1 | `112000001` / 22 | 1 | 126.904572, 37.575465 | 19:06:34 | target 도착 |
| 19:06:51 | 2 | `112000001` / 22 | 0 | 동일 | 19:06:48 | target 출발/운행 |
| 19:07:01 | 4 | `112000001` / 22 | 0 | 126.905315, 37.574787 | 19:06:55 | target 이후 GPS 이동 |
| 19:07:25 | 8 | `112000001` / 22 | 0 | 126.905569, 37.574556 | 19:07:13 | target 이후 운행 |
| 19:07:44 | 11 | `112000001` / 22 | 0 | 126.906212, 37.573967 | 19:07:34 | target 이후 운행 |
| 19:08:04 | 14 | `112000001` / 22 | 0 | 126.907603, 37.572715 | 19:07:55 | target 이후 운행 |
| 19:08:22 | 17 | `112000003` / 23 | 1 | 126.909610, 37.571678 | 19:08:15 | 다음 정류소 도착, target 통과 확인 |

관찰 중 `stopFlag`는 실제로 `1 → 0 → 1` 값을 보였다. 이 API는 접근만을 별도 상태로 구분하지 않는다. 그러나 target의 `stId`와 `stOrd`가 일치하고 `stopFlag=1`인 직접 도착 상태, 이어서 GPS 이동과 `stOrd=23`이 관찰됐으므로 target의 도착·출발·통과를 실제 데이터로 확인했다.

직접 도착 event를 polling 사이에 놓치더라도, 같은 `vehId`에 대해 이전 `stOrd < target stOrd`와 현재 `stOrd > target stOrd`가 관찰되면 통과 후보로 해석할 실측 근거는 있다. 다만 회차, 방향 전환, 순환 Route, 정류소 재방문을 단순 순번 비교만으로 해결할 수 있는지는 검증하지 않았으므로 최종 Rule은 TASK-305에서 결정한다.

#### Polling 특성 및 Bus Location 중심 monitoring 가능성

공식 문서는 매 5초 갱신을 표기하지만, 5초 polling 실측에서 `dataTm` 변경은 7~21초 간격으로 나타났고 중간에는 같은 data timestamp가 반복됐다. 이 한 Route/차량 표본만으로 provider 전체의 영구 갱신주기를 확정하지 않는다.

서울 Bus Location API만으로도 다음 실측 field를 이용해 Route의 여러 차량을 감시하고 특정 target Stop 도착/통과 후보를 평가할 가능성이 있다.

- 요청 Route 문맥과 `vehId`/`plainNo`로 차량 연속성 식별
- `stId`, `stOrd`, `stopFlag`로 직접적인 정류소 도착 상태 식별
- `sectOrd`, `sectionId`, WGS84 좌표 및 `dataTm`으로 구간 진행과 갱신 상태 관찰
- `isrunyn`, `islastyn`, `trnstnid`, `nextStId` 등으로 운행 상태·방향 판단을 보조할 가능성

이는 서울 monitoring 가능성의 실측 근거일 뿐, Provider 선택, 차량 추적 identifier 영속화, polling frequency, grouping key, Alarm Schema나 도착/통과 Algorithm을 확정하지 않는다.

### 서울 / 경기 실제 결과 비교

| 관점 | 경기 TAGO 수원 표본 | 서울 버스위치정보조회 표본 |
| --- | --- | --- |
| 차량 식별 | `vehicleno` | `vehId`와 `plainNo` |
| Route 위치 연결 | 요청 `routeId` 문맥, item의 `routenm` | 요청 `busRouteId` 문맥; 구간 조회 item에는 `routeId`가 있으나 전체 노선 조회 item에는 누락 가능 |
| 노선 진행 field | `nodeid`, `nodeord` | `stId`, `stOrd`, `sectOrd`, `sectionId` |
| 직접 도착 field | Location에는 없음; Arrival의 `arrprevstationcnt`/`arrtime`을 별도 조회 | `stopFlag` (`1=도착`, `0=운행중`)를 실제 관찰 |
| 갱신 실측 | Arrival 약 11초, Location 약 31초의 한 표본 | `dataTm` 7~21초 간격의 한 차량 표본 |
| Route/Stop metadata | Route → Stop 조회 성공 | 현재 이용 권한으로 별도 노선 metadata 조회 실패; Bus Location 단독으로 Stop 이름 검색 불가 |

### TASK-303 결론

경기 TAGO는 복수 경기 cityCode에서 Route / Stop / Location / Arrival 연결과 핵심 field 변화를 제공했고, 서울 버스위치정보조회 서비스는 실제 서울 Route의 여러 운행 차량, 차량 연속 identifier, 정류소순번·구간 진행, `stopFlag` 기반 직접 도착 상태 및 target Stop 통과를 제공했다. 따라서 서울·경기의 실시간 관측 특성을 TASK-304와 TASK-305의 판단 근거로 사용할 수준으로 확인했다.

서울 Route/Stop 검색 metadata의 별도 서비스 권한·source, `stOrd` 통과 판단의 회차/방향 예외, identifier 장기 안정성 및 provider routing은 여전히 미확정이다. TAGO 또는 서울 API를 V1 Provider로 확정하지 않으며, 해당 결정과 Algorithm/Schema는 TASK-304~305의 범위다.
