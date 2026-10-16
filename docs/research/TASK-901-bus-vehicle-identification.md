# TASK-901: TAGO·서울 버스 탑승 차량 식별 가능성 조사

- 조사일: 2026-10-16 (Asia/Seoul)
- 기준: `main`, `5e1d126074ef906b2c98bd901a1718097ff12a20` (`docs: Phase 9 버스 하차 알림 개발 계획 추가`). `git fetch origin main` 후 HEAD와 origin/main 일치, 시작 작업 트리 clean 확인
- 성격: 조사 결과이며 새로운 Domain/API/DB/알림 정책의 확정 문서가 아니다
- 최초 조사 당시 실제 Provider API 신규 호출: **0회**. 공개 명세 페이지·첨부 활용가이드만 열람했고, 기존 Phase 3 실응답 관측 기록을 재검토했다. 이후 승인된 추가 제한 실측은 **TAGO 2회·서울 0회**, 응답 불완전으로 조기 종료했으며 §17에 별도 기록한다
- 결론: **서울은 제한된 조건의 GPS 없는 추적·도착 판단 근거가 있고, TAGO는 차량별 진행 관측 근거가 있다. 어느 쪽도 실제 탑승 사실과 모든 상황의 동일 차량 연속성을 자동 보장하지 않는다.** TAGO의 GPS 없는 정확한 정류장 도착 판정과 양 Provider의 회차·소실 후 재연결은 지원 보류다

§1~16은 최초 조사와 당시 판단을 보존한다. §17은 후속 사용자 승인, 이번에만 적용한 Quota 확인 예외, 신규 실측 및 후속 설계 영향을 구분한 기록이다. 최초 조사의 신규 호출 0회·미실행 계획·완료 처리 서술을 추가 실측의 실행 상태로 해석하지 않는다.

## 1. 목적, 범위와 정확성 원칙

질문은 “선택 노선의 다음 버스가 정류장에 오는가”가 아니라 “사용자가 실제 탄 버스를 확인하고 그 차량만 목적 occurrence까지 따라갈 수 있는가”다. 기존 도착 알림 성공, 테스트 통과, iPhone 알림 수신은 탑승 차량 식별의 증거가 아니다.

[Phase 9 계획](../task-list.md#phase-9---탑승-차량-식별-및-하차-알림), [Architecture](../architecture.md), [Domain Model](../domain-model.md), [Database](../database.md), [API](../api.md), [Local Development](../local-development.md), [요구사항](../requirements.md), [개발 가이드라인](../development-guidelines.md), [로드맵](../roadmap.md), ADR-006/007/009를 대조했다. TASK-902의 추천 가중치·점수·threshold, TASK-903의 세션/상태/이벤트/스키마는 정하지 않는다. TASK-710/711의 미완료 상태와 검증 책임을 보존한다.

- 정확성 > 편의성. 후보가 한 대여도 탑승 사실을 확정하지 않는다.
- 단말 GPS·위치 권한·Provider GPS를 식별 또는 하차 판단의 필수 근거로 사용하지 않는다.
- 불확실성, 정상 empty, 부분 실패, Provider 장애를 구분하고 누락을 운행 종료로 단정하지 않는다.
- 차량 ID 변경·재사용·소실을 다른 차량이나 같은 차량으로 자동 연결하지 않는다. 차량번호만으로 ID를 복구하지 않는다.
- 사용자 확인도 실제 차량 표시와 API 표시의 대응이 검증된 범위에서만 의미가 있다.
- 코드·테스트·Dependency·DB·기존 Alarm lifecycle·Notification 계약·devlog는 변경하지 않는다.

## 2. 증거 분류와 출처

`[공식]`은 명세가 기술하는 내용, `[관측]`은 기존 실제 API 관측 기록, `[코드]`는 현재 구현/테스트의 동작, `[추론]`은 조건부 기술 판단이다. `미확정`은 근거 없음이며 정상 동작 또는 미지원의 증명이 아니다. 공식 예제와 mock fixture는 실제 응답 관측 횟수에 포함하지 않는다.

### 2.1 공식 자료 — 2026-10-16 원문 확인

| ID | 자료와 확인 범위 |
| --- | --- |
| O1 | [국토교통부 TAGO 버스위치정보](https://www.data.go.kr/data/15098533/openapi.do): 현재 페이지의 노선별 위치 출력 필드, 인증, 트래픽, 오류 코드 |
| O2 | O1 첨부 [오픈API활용가이드_국토교통부(TAGO)_버스위치정보v1.0.docx](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000002761705&fileDetailSn=1): 서비스 개요, 상세기능 1~3, 요청/출력 표. SHA-256 `e506c89e48f4f2bc88baaf7accead5e0a6bee1107494630449095c5b7cc0a796` |
| O3 | [서울특별시 버스위치정보조회 서비스](https://www.data.go.kr/data/15000332/openapi.do): 현재 페이지의 구간 조회, 트래픽, 데이터 이용 출처표시 조건 |
| O4 | O3 첨부 [서울특별시_버스위치정보조회_서비스_활용가이드_20230530.docx](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000003552007&fileDetailSn=1): 상세기능 2 `getBusPosByRtidList`, 3 `getBusPosByVehIdItem` 각각의 요청/출력 표. SHA-256 `257e0778fb5036ae859fe67805a55361a5db1836a17083e5684517c713987f90` |

공개 가이드는 저장소 밖 임시 경로에서 읽었으며 원문을 저장소에 복제하지 않았다. Provider/API 문서에 차량 ID 유지기간·재발급·번호판 교체·운행 회차별 재사용을 보장하는 조항은 확인하지 못했다. 공식 도메인 검색에서도 추가 보장을 찾지 못했으나, 검색 결과 부재가 그러한 정책의 부재를 증명하지는 않는다.

### 2.2 실제 응답 근거와 테스트의 경계

| ID | 저장된 근거 | 이번 조사에서 인정하는 범위 |
| --- | --- | --- |
| E1 | [기존 조사: TASK-303 실제 API PoC](../transit-provider-research.md#task-303-실제-api-poc), 2026-09-09 | TAGO 300의 위치/도착 12쌍, 서울 roster와 detail 20회 등 **실제 응답을 정리한 기록**. 원본 전체 응답은 이번에 확보하지 않음 |
| E2 | [First Stop 보충 PoC](../transit-provider-research.md#서울경기-first-stop-arrival-보충-poc), 2026-09-09 | TAGO 7000의 동일 번호 진행과 서울 별도 구간 operation 관측. 각 필드의 존재와 관측 한계만 재사용 |
| E3 | [서울 Metadata Preflight](../transit-provider-research.md#task-305-서울-metadata-preflight-및-contract-decision), 2026-09-09 및 같은 문서의 T Data CSV fallback 실측 | 7016의 한 occurrence에서 metadata와 detail ID/order 일치, T Data의 같은 occurrence 일치. 전체 노선 검증 아님 |
| E4 | [경기 Static Metadata Full Sync PoC](../transit-provider-research.md#경기-tago-static-metadata-full-sync-poc), 2026-09-13 | 7000/300 등 metadata 순서, 같은 이름·다른 ID 구분. 차량 운행 연속성 실측 아님 |
| T1 | [VehicleLocationResponseDtoTest](../../backend/src/test/java/com/stopbell/transit/dto/VehicleLocationResponseDtoTest.java), [TagoVehicleLocationClientTest](../../backend/src/test/java/com/stopbell/transit/client/TagoVehicleLocationClientTest.java), [SeoulBusVehicleLocationClientTest](../../backend/src/test/java/com/stopbell/transit/client/SeoulBusVehicleLocationClientTest.java), [SeoulBusVehicleDetailClientTest](../../backend/src/test/java/com/stopbell/transit/client/SeoulBusVehicleDetailClientTest.java) | Java text block JSON과 MockRestServiceServer. 필드 수용·정상 empty·실패 분류의 코드 근거이며 live 통합 테스트가 아님 |
| T2 | [TagoTransitObservationMapperTest](../../backend/src/test/java/com/stopbell/transit/mapper/TagoTransitObservationMapperTest.java), [SeoulBusTransitObservationMapperTest](../../backend/src/test/java/com/stopbell/transit/mapper/SeoulBusTransitObservationMapperTest.java) | 인위적으로 생성한 DTO 및 fixed Clock. mapper 의미 확인용 |
| T3 | [BusAlarmEvaluatorTest](../../backend/src/test/java/com/stopbell/alarm/service/BusAlarmEvaluatorTest.java), [BusAlarmMonitoringSchedulerTest](../../backend/src/test/java/com/stopbell/alarm/service/BusAlarmMonitoringSchedulerTest.java), [BusAlarmDecisionIntegrationTest](../../backend/src/test/java/com/stopbell/alarm/service/BusAlarmDecisionIntegrationTest.java) | 통제된 관측/실패, lifecycle·DB 원자성의 검증 코드. 물리 차량 ID 안정성의 증거가 아님 |

`backend/src/test/resources` 디렉터리는 없으며 관련 응답 fixture는 테스트 소스 안에 있다. E1과 비슷한 값이 T1/T2에 등장하지만 서울 fixture 날짜는 2026-09-20으로 구성되어 있어 E1의 2026-09-09 원본 캡처로 간주하지 않는다. 실제 API 응답이 없는 null/중복 ID/재사용 사례를 fixture로 검증 완료했다고 주장하지 않는다.

## 3. 기존 StopBell 구현 분석

### 3.1 Provider → 관측 → 추적 경로

| 책임 | 현재 구현에서 확인한 사실 | Phase 9에 대한 의미 |
| --- | --- | --- |
| TAGO Client | [TagoVehicleLocationClient.fetchVehicleLocations/buildUri](../../backend/src/main/java/com/stopbell/transit/client/TagoVehicleLocationClient.java): 요청 `cityCode/routeId`, `getRouteAcctoBusLcList`, `_type=json`; 정상 `00`과 실패 구분 | 차량 키를 만들지 않는다. 현재 `pageNo/numOfRows` 미지정, 응답 `totalCount`에 따른 추가 페이지 조회·완전성 확인 없음 |
| 서울 roster Client | [SeoulBusVehicleLocationClient](../../backend/src/main/java/com/stopbell/transit/client/SeoulBusVehicleLocationClient.java): `busRouteId` 조회, 정상 `0`/결과 없음 `4` 구분 | 노선별 후보 목록 근거. 개별 detail의 직접 도착 근거를 대체하지 않음 |
| 서울 detail Client | [SeoulBusVehicleDetailClient.fetchVehicleDetail](../../backend/src/main/java/com/stopbell/transit/client/SeoulBusVehicleDetailClient.java): `vehId`만 요청 | 원본 응답 ID와 요청 ID의 일치 검사 및 현재 노선 소속 검증을 Client에서 수행하지 않음 |
| TAGO mapper | [TagoTransitObservationMapper.mapItem](../../backend/src/main/java/com/stopbell/transit/mapper/TagoTransitObservationMapper.java): 원본 `vehicleno` → tracking ID, `nodeid/nodeord` → Stop ID/order | 차량번호 문자열은 tracking ID 안에 남지만 별도 표시값은 없음. 직접 도착 `UNAVAILABLE`, Provider 시각/방향/구간/이름 null |
| 서울 mapper | [SeoulBusTransitObservationMapper.mapItem](../../backend/src/main/java/com/stopbell/transit/mapper/SeoulBusTransitObservationMapper.java): 원본 `vehId`, detail `stId/stOrd`, `stopFlag` 1/0/기타 → ARRIVED/MOVING/UNAVAILABLE | `plainNo` 유실. `dataTm`은 `yyyyMMddHHmmss`, Asia/Seoul → Instant. 방향/구간/이름 null, roster는 mapping 입력 아님 |
| 공통 Domain | [TransitObservation](../../backend/src/main/java/com/stopbell/transit/domain/TransitObservation.java), [TransitProvider](../../backend/src/main/java/com/stopbell/transit/domain/TransitProvider.java), [ArrivalEvidence](../../backend/src/main/java/com/stopbell/transit/domain/ArrivalEvidence.java) | provider/route/vehicle/observedAt/evidence 필수. 공백 차량 ID는 생성 예외. 사용자 차량 확인, 차량번호↔ID 관계, 운행 회차/세션은 표현하지 않음 |
| polling 공유 | [BusAlarmPollingService.findMonitoringGroups](../../backend/src/main/java/com/stopbell/alarm/service/BusAlarmPollingService.java) | TAGO `(provider, externalRouteId, cityCode)`, 서울 `(provider, externalRouteId)`로 기존 Alarm 요청 공유 |
| scheduler | [BusAlarmMonitoringScheduler.pollTago/pollSeoul/selectSeoulDetailVehicleIds](../../backend/src/main/java/com/stopbell/alarm/service/BusAlarmMonitoringScheduler.java) | 서울 baseline은 roster 전체, 이후 tracked/new/FOLLOW_UP의 합집합 detail 조회. 그룹 안 같은 차량 중복 호출 억제. detail 실패·empty와 roster 존재를 분리 |
| tracking | [BusAlarmEvaluationState](../../backend/src/main/java/com/stopbell/alarm/service/BusAlarmEvaluationState.java), [VehicleTrackingState](../../backend/src/main/java/com/stopbell/alarm/service/VehicleTrackingState.java) | Alarm activation별 memory map의 키는 원본 차량 문자열. UUID trackingCycleId는 StopBell이 만든 논리 cycle이며 물리 차량 ID가 아님 |

두 mapper는 Provider 원본 차량 값을 그대로 사용하며 `provider:route:vehicle` 문자열을 생성하지 않는다. 대신 Observation에 provider/route를 따로 보존하고 [BusAlarmEvaluator.validateObservationRoute](../../backend/src/main/java/com/stopbell/alarm/service/BusAlarmEvaluator.java)에서 Alarm target과 일치시킨다. 따라서 현재 map은 Alarm/Route 문맥 안에 격리되어 있다. 외부 차량 ID의 Provider 전역 또는 노선 간 유일성을 입증한 것은 아니다. `cityCode`는 요청 문맥이며 공식 Route identity를 변경하지 않는다.

`observedAt`은 성공 응답을 mapper에 전달한 뒤 한 번 읽은 Clock 값이다. 수신 시각 근사이며 Provider가 그 순간 데이터를 새로 생성했다는 뜻이 아니다. TAGO 원본 데이터 시각이 없으면 upstream stale을 판별할 수 없고, 서울의 `dataTm`도 차량 단말 생성시각인지 중계 제공시각인지 세부 시간 의미는 미확정이다.

### 3.2 차량 누락·재등장·재사용

[BusAlarmEvaluator](../../backend/src/main/java/com/stopbell/alarm/service/BusAlarmEvaluator.java)의 `evaluateActive`, `touchPresentVehicles`, `expireMissingVehicles`, `uniqueSnapshot`, `evaluateActiveVehicle`와 [기존 policy](../../backend/src/main/java/com/stopbell/alarm/service/BusAlarmEvaluationPolicy.java)를 확인했다.

- 한 정상 snapshot 누락으로 종료하지 않는다. 완전한 presence 평가에서 마지막 확인 후 기존 60초 grace 초과 시 memory cycle을 제거한다. 제거는 **실제 운행 종료 판정이 아니다**.
- 원래 cycle이 남아 있고 같은 ID가 재등장하면 기존 state와 이어서 평가한다. 삭제된 뒤 나타나면 새 UUID cycle을 만들 수 있다. 재등장한 snapshot에서 presence를 먼저 갱신하므로 중간 정상 absence 평가 없이 60초가 지났다는 사실만으로 항상 새 cycle이 되는 것도 아니다.
- Provider/roster 실패는 해당 Route 평가를 건너뛰어 기존 state를 보존한다. 서울 detail 실패는 성공 차량만 평가하고 roster 존재를 유지한다. 실패 요청은 첫 조회들 후 한 차례 재시도하며 실패를 empty로 바꾸지 않는다.
- 중복 차량 Observation, 역행, stale 또는 충돌은 이벤트 없이 state를 보존한다. 그러나 같은 ID가 다른 물리 차량에 재사용되어 그럴듯한 순서로 나타나는 경우를 판별할 번호판 연결·trip identity가 없다.
- ACTIVE restart는 새 baseline이다. FOLLOW_UP은 선택 ID/UUID를 복원하지만 물리 차량의 동일성까지 증명하지 않는다.
- 기존 ACTIVE는 여러 차량을 감시하고 PASSED 뒤 다른 차량을 기다린다. 이것을 한 사용자의 탑승 차량 추적에 그대로 적용할 수 없다.

### 3.3 Metadata와 현재 판정의 한계

[BusRoute](../../backend/src/main/java/com/stopbell/transit/entity/BusRoute.java)는 `(provider, externalRouteId)`, [BusStop](../../backend/src/main/java/com/stopbell/transit/entity/BusStop.java)은 `(provider, externalStopId)` identity다. [BusRouteStopOccurrence](../../backend/src/main/java/com/stopbell/transit/entity/BusRouteStopOccurrence.java)는 route/stop/positive order이며 `(route, order)` unique다. 같은 Stop 재방문을 저장할 수 있지만 실시간 데이터에서 어느 lap인지까지 식별하는 모델은 아니다.

[BusRouteStopService.findAll](../../backend/src/main/java/com/stopbell/transit/service/BusRouteStopService.java)은 정렬된 occurrence와 인접 이름을 제공한다. `destinationName`은 ADR-009에 따라 검증된 경기 GBIS 표시 보강에만 있으며 서울은 null이다. 이를 실시간 방향 센서로 사용해서는 안 된다. [BusRouteTraversalService.stopsPastTarget](../../backend/src/main/java/com/stopbell/alarm/service/BusRouteTraversalService.java)은 두 ID/order를 metadata에서 찾아 index 차이를 구하며 현재는 **PASSED 표시 보조**다. 일반적인 운행 방향·회차 검증기 또는 2정거장 전 판정기는 아니다.

기존 `canArrive`는 서울 exact Stop ID/order + ARRIVED를 사용한다. TAGO baseline은 GPS corroboration을 요구하며 이후에는 이전 BEFORE와 단조 진행을 사용한다. GPS 충돌 검사도 있다. 기존 `ONE_STOP_BEFORE`는 predecessor ID/order 일치를 사용하며 별도 direct flag를 요구하지 않는다. 따라서 GPS 없는 하차 이벤트의 정확한 물리 시점을 기존 ARRIVED/before 구현으로 검증 완료했다고 볼 수 없다.

### 3.4 발견한 계약 공백과 판단 필요 사항 — 수정하지 않음

1. **TAGO 목록 완전성:** O1/O2는 pagination을 명시하고 DTO는 `totalCount/pageNo/numOfRows`를 보존하지만 Client는 검사하지 않는다. 현재 응답 한 번의 ID 집합을 전체 존재 집합으로 사용하는 구조에서 일부 페이지 누락이 차량 소실로 보일 위험이 있다. 실제 기본 반환 건수나 잘림 발생은 이번에 실측하지 않았다.
2. **서울 detail 결합:** 호출 요청의 vehId와 반환 vehId 일치, roster/detail의 plainNo 일치, detail 조회 사이의 Route 변경을 검증하는 코드가 없다. mapper가 부여한 요청 Route는 Provider가 현재 소속을 다시 증명한 값이 아니다.
3. **방향·재방문:** Domain/ADR-007은 모호한 traversal을 UNKNOWN으로 요구한다. 현재 mapper의 directionContext는 항상 null이며 evaluator는 주로 order 비교와 target/인접 Stop 충돌 검사에 의존한다. 특히 같은 Stop ID·다른 order를 `conflicts`가 충돌로 처리하므로 저장 가능한 재방문 occurrence를 판정기가 모두 지원한다고 볼 수 없다. 공식 요구와 구현의 보장 범위 차이는 후속 설계 판단 대상이며 문서를 코드에 맞춰 완화하지 않는다.
4. **mapping ambiguity:** 필수 차량 ID가 없으면 `TransitObservation` 생성이 실패하고, 잘못된 `dataTm`은 parsing 예외다. scheduler의 일반 예외 경로는 해당 group 평가를 생략한다. 이는 차량별 명시적인 불확실 응답을 사용자에게 제공하는 구현과 다르다.

위 항목은 기존 기능의 범위 밖 리팩터링·수정으로 해결하지 않았다. TASK-902/903에서 재사용 가능성을 검토할 때의 제한이며, Phase 9 구현에서 필요한 최소 변경과 기존 계약 충돌 해소는 개발자 판단을 거쳐야 한다.

## 4. TAGO 공식 필드와 차량 식별

O1/O2의 `getRouteAcctoBusLcList`는 노선별 버스위치 목록 조회다. 필수 요청 `serviceKey/cityCode/routeId`, 선택 `pageNo/numOfRows/_type`를 사용한다. Route 연결은 요청 문맥으로 유지한다. DTO는 [TagoVehicleLocationItem](../../backend/src/main/java/com/stopbell/transit/dto/tago/TagoVehicleLocationItem.java)이다.

| 필드 | 공식 명칭·설명 / 구분·크기 | 실제 포함 근거 | 추적·표시 활용 및 한계 |
| --- | --- | --- | --- |
| `vehicleno` | 차량번호 / 필수·10 | E1/E2 문자열 존재, 동일 값의 진행 관측 | 표시용 번호와 현재 임시 correlation key의 두 역할. 별도 안정적 opaque 차량 ID라는 보장은 없음 |
| `nodeid` | 정류소ID / 필수·30 | E1에서 metadata ID와 일치 | metadata 이름 조회 가능. 현재 도착/최근 통과/다음 정류소 중 어느 기준으로 전환되는지 명세가 설명하지 않음 |
| `nodeord` | 정류소 순서 / 필수·4 | E1 7→8, E2 1→3 | 노선 occurrence 연결 후보. 허용 최소·최대, 회차 reset/순환 규칙은 명세 미기재. 내부 positive order 조건을 공식 범위로 오인하지 않음 |
| `gpslati` | 맵매칭 Y좌표, WGS84 위도 / 선택·20 | E1/E2 포함 | 좌표값 존재만 확인. 이번 판단에 사용하지 않으며 신규 문서에는 원값 복제하지 않음 |
| `gpslong` | 맵매칭 X좌표, WGS84 경도 / 선택·20 | E1/E2 포함 | 위와 동일. 명세의 item별 유효 범위·정확도 보장은 확인하지 못함 |

크기는 명세 표기이며 유일성·안정성·입력 validation 정책이 아니다. WGS84 위경도의 일반 범위와 StopBell metadata 좌표 검증은 별개다. 공식 필수여도 현장 데이터에서 항상 non-null/non-blank라는 보장은 없다. 위 5개 필드의 누락·공백·비정상 범위 발생 빈도는 E1/E2로 확인되지 않았다.

`vehicleno`는 공식적으로 차량번호이고 표본 형식도 번호판 형태다. 하지만 실제 차체와 대조한 기록은 없다. 같은 노선 동시 중복, 노선 간 중복, 차량 교체·번호판 교체·다른 노선 재배치·운행 중 변경, 소실 뒤 같은 번호 재등장의 물리적 동일성은 **모두 미확정**이다. 다른 노선에 같은 값이 나와도 동일 운행이라고 합치지 않는다. Provider 문의와 별도 승인된 전후 관측이 필요하다.

`nodeid/nodeord`는 E1에서 metadata와 연결되지만 **정류장 도착 flag는 아니다**. 회차·반복 Stop·순환 lap을 구분하는 원본 direction/trip 필드는 현재 DTO와 확인 명세에 없다. 현재/최근/다음 중 정확한 의미를 정류장 전후 육안 시각과 대조하지 않고 “현재 정차 중”이라고 표시하면 안 된다.

O2의 기능 목록에는 `getRouteAcctoBusLcList`, `getRouteAcctoSpcifySttnAccesBusLcInfo`, `getCtyCodeList`가 있다. 두 번째는 노선·정류소 접근 조회이며 **차량 ID를 지정하는 개별 차량 API가 아니다**. 조사한 TAGO 위치 서비스 범위에서는 특정 vehicleno 직접 조회는 지원 불가다. 전국의 모든 별도 API에 그러한 기능이 없다고 확대하지 않는다.

## 5. 서울 공식 필드 — roster와 detail 분리

O4 상세기능 2는 `busRouteId`로 차량 목록을, 상세기능 3은 `vehId`로 한 차량의 위치를 조회한다. [SeoulBusVehicleLocationItem](../../backend/src/main/java/com/stopbell/transit/dto/seoul/SeoulBusVehicleLocationItem.java)과 [SeoulBusVehicleDetailItem](../../backend/src/main/java/com/stopbell/transit/dto/seoul/SeoulBusVehicleDetailItem.java)을 대조했다.

| 필드 | O4 공식 의미 / 구분·크기 | roster / detail | 실제 응답 근거와 활용 한계 |
| --- | --- | --- | --- |
| `vehId` | 버스 ID / 필수·9 | 둘 다 | E1에서 동일 ID 유지. 물리 차량 장기 불변·재사용 방지·노선 간 namespace 보장은 없음 |
| `plainNo` | 차량번호 / 필수·128 | 둘 다 | E1의 동일 item에서 ID↔번호 대응 유지. 전역·영구 1:1 보장은 없음; 실제 외부·차내 표시 일치 미실측 |
| `sectOrd` | 구간순번 / 필수·10 | roster | E1 roster 21, E2 별도 구간 조회 1→2. 정류소순번과 동치 보장 없음 |
| `sectionId` | 구간 ID / 필수·9 | roster | E2 구간 조회에서 존재 확인, roster의 값·연속 변화는 E1 요약으로 독립 재검증 불가. T1만으로 실제 확인 처리하지 않음 |
| `stopFlag` | 정류소도착여부 / 필수·1, 0=운행중·1=도착 | 둘 다 | E1 detail 1→0→1. roster flag를 detail Stop ID/order 없이 특정 occurrence 도착으로 바꾸지 않음 |
| `dataTm` | 제공시간 / 필수·14 | 둘 다 | E1 detail 갱신 관측. 원본 생성 시각/중계 시각 의미와 시계 오차 보장 미확정. 코드 해석은 한국 시각 |
| `nextStId` | 다음정류소아이디 / 필수·9 | roster | O4와 T1에 있음. E1 서술에 필드가 열거되지만 구체 값·detail 대응 실측 표가 없어 의미 검증 미확정 |
| `stId` | 정류소 고유 ID / 필수·9 | detail | E1 도착 때 target ID, 출발 뒤 같은 값 유지. E3 metadata 연결. 모든 이동 중 상태에서 최근/다음 어느 쪽인지 일반화하지 않음 |
| `stOrd` | 정류소순번 / 필수·10 | detail | E1 22→23, E3 seq=3 대응. 숫자 범위·회차 reset·lap 의미 미기재 |

ID는 숫자 예제가 있어도 opaque String이다. `stopFlag` 이외 enum/range·ID 수명·누락률의 공식 보장은 확인되지 않았다. 표의 필수는 null/공백·이상값을 무시하라는 뜻이 아니다. `sectionId/nextStId`는 coarse 위치 설명 후보지만 metadata와 의미 대응이 검증되기 전에는 정류장명이나 방향을 단정하는 근거로 사용할 수 없다.

**공식 자료 간 표현 차이:** O3 웹페이지는 `getBusPosByRouteSt` 구간 조회를 보여 주며, 필드 크기도 O4와 일부 다르다(예: plainNo 12 대 128, dataTm 22 대 14). O4 상세기능명은 `getBusPosByRtidList/getBusPosByVehIdItem`인데 Call Back URL/예제는 각각 `/getBusPosByRtid`, `/getBusPosByVehId`다. 현재 코드는 전자의 기능명 경로를 사용하며 E1에는 성공 기록이 있다. 이를 모든 경로가 현재 동등하게 동작한다는 보장으로 바꾸지 않는다. O3의 구간 응답 표를 roster/detail 표로 복사하지 않았고, 최신 runtime 경로 호환성은 다음 승인 실측에서 확인한다.

## 6. 실제 응답 재검토와 익명 증거 발췌

아래는 E1/E2의 **기존 실응답 요약 재인용**이며 신규 관측이나 새 JSON fixture가 아니다. 차량별 별칭은 표본 안에서 일관되게 유지했고 표본 간 차량을 합치지 않았다. 키·token·인증 query·좌표·원본 번호·전체 raw response는 옮기지 않았다. 공식 확인일은 모두 2026-10-16, 실제 관측일은 아래와 구분한다.

| 표본 | Provider / operation / 노선 | 실제 관측 시각(KST)·횟수 | 최소 필드·확인된 의미 | 한계 |
| --- | --- | --- | --- | --- |
| A | TAGO / `getRouteAcctoBusLcList` / 수원 300, `GGB200000006`, cityCode 31010 | 2026-09-09 17:36:31~17:37:45, Location 12회; Arrival 12회는 별도 보조 | `TAGO_A`의 vehicleno 동일, nodeid/order 7→8; 목록 25건, GPS 필드 존재. 6번째(17:37:02) 위치 변경 | 한 차량·74초. 정확한 정차/출발 시각, 회차·ID 재사용·전체 pagination 검증 없음 |
| B | TAGO / 같은 operation / 수원 7000, `GGB200000112` | 2026-09-09 첫 snapshot의 정확한 시각 미기록, 후속 19:41:05~19:41:31; 5초 간격 서술만 있고 정확한 횟수 미기록 | `TAGO_B` 동일 번호에서 nodeid/order 1→3, 최초 목록 13건 | 1→2와 첫 정류장 체류 누락. 표본 A와 다른 차량 |
| C | SEOUL_BUS / `getBusPosByRtidList` → `getBusPosByVehIdItem` / Route `100100118` | 2026-09-09 roster 19:04:34, 초기 detail 19:05:56, 후속 detail 20회(발췌표 19:06:46~19:08:22, 전체 종료 시각 미기록) | `SEOUL_A`와 `PLATE_A` 대응 유지, roster 18건; detail stId/order 22에서 stopFlag 1→0, 다음 23에서 1; dataTm 존재 | 20회 중 7개 row만 표로 남음. 원본 전체 응답·물리 차량 육안 확인 없음 |
| D | SEOUL_BUS / `getBusPosByRouteSt` / Route `100100118` | 2026-09-09 19:43:26~19:43:51 정상 empty 6회; 19:45:13~19:46:49 차량 관측 총횟수 미기록 | `SEOUL_B`/별도 번호가 sectOrd=1, flag=0 → 2, flag=1; sectionId 존재 | roster/detail operation 검증으로 합산하지 않음. 첫 정류장 direct flag 없음 |
| E | SEOUL_BUS / metadata `getStaionByRoute` 및 차량 위치 / 7016 `100100447` | 2026-09-09, 정확한 시각·총 호출 수 미기록, 비교 occurrence 1개 | metadata station=detail stId, seq=stOrd=3; 이후 T Data 동일 occurrence 확인 | 당시 metadata source와 현재 CSV source를 구분. 모든 row·방향·회차 보장 없음 |

표본 C의 시간 증거를 좌표 없이 재검토하면 다음과 같다. `dataTm` 원문 날짜를 새로 만들어 쓰지 않고 E1이 보존한 시각만 사용했다.

| 관측 시각 | 별칭 | detail order / flag | Provider 시각 | 수신-제공 시각 차 |
| --- | --- | --- | --- | --- |
| 19:06:46 | SEOUL_A / PLATE_A | 22 / 1 | 19:06:34 | 12초 |
| 19:06:51 | SEOUL_A / PLATE_A | 22 / 0 | 19:06:48 | 3초 |
| 19:07:01 | SEOUL_A / PLATE_A | 22 / 0 | 19:06:55 | 6초 |
| 19:07:25 | SEOUL_A / PLATE_A | 22 / 0 | 19:07:13 | 12초 |
| 19:07:44 | SEOUL_A / PLATE_A | 22 / 0 | 19:07:34 | 10초 |
| 19:08:04 | SEOUL_A / PLATE_A | 22 / 0 | 19:07:55 | 9초 |
| 19:08:22 | SEOUL_A / PLATE_A | 23 / 1 | 19:08:15 | 7초 |

위 3~12초는 남아 있는 row의 단순 시각 차이지 HTTP latency 또는 실제 정류장 도착부터의 지연이 아니다. E1의 dataTm 변경 간격은 7~21초였다. TAGO A는 첫 위치 변화가 약 31초 뒤였고 자체 Provider timestamp가 없어 원본 age를 계산할 수 없다. 둘 다 문서상 갱신주기를 실제 freshness SLA로 해석할 수 없다.

## 7. 차량 식별자 연속성 A~J

이 표의 `확인됨`도 명시한 표본·계약 범위 안에서만 의미가 있다. 현실의 물리 차량 확인과 API ID 연속성은 구분한다.

| 사례 | TAGO | 서울 | 해석·추가 근거 |
| --- | --- | --- | --- |
| A 정상 운행 | 제한적으로 확인됨 | 제한적으로 확인됨 | A/B/C의 같은 값 진행. 장시간·전 노선 불변은 미확정 |
| B 정류장 도착 | 미확정 | 제한적으로 확인됨 | TAGO flag 없음. 서울 C의 exact ID/order+flag=1 전후 ID 유지, 육안 도착 시각은 미확인 |
| C 정류장 출발 | 미확정 | 제한적으로 확인됨 | 서울 C의 같은 Stop에서 1→0과 다음 Stop 진행. 모든 출발 의미 보장은 아님 |
| D 회차·방향 전환 | 미확정 | 미확정 | 왕복 전후 동일 ID, 번호, order, 방향을 비교한 자료 필요 |
| E 소실 원인 구분 | 지원 불가 | 지원 불가 | 현재 선택 API/필드만으로 운행 종료·누락·통신 장애를 모두 구별할 수 없음. HTTP 실패는 요청 실패로만 분류 가능 |
| F 재등장 동일성 | 미확정 | 미확정 | 같은 문자열 재등장만으로 같은 물리 차량·같은 운행을 확정할 수 없음 |
| G 차량 밀집 | 제한적으로 확인됨 | 제한적으로 확인됨 | 한 목록에 복수 차량은 관측됨. 동시 정류장 통과·같은 order의 여러 차량과 실제 탑승 매칭은 미실측 |
| H 번호↔ID 불일치 | 미확정 | 미확정 | TAGO는 독립 ID가 없어 비교 불가. 서울 대응 불일치 표본 없음; 어느 한쪽을 자동 신뢰할 근거 없음 |
| I 왕복·순환 occurrence | 제한적으로 확인됨 | 제한적으로 확인됨 | metadata ID+order 표현·일부 실측 연결만 확인. 동일 Stop 재방문/다음 lap 판정은 미확정 |
| J 오래된 데이터 | 미확정 | 제한적으로 확인됨 | TAGO 원본 age 없음. 서울 dataTm으로 오래된 제공값을 배제할 입력은 있지만 시계·실제 지연 보장은 없음 |

## 8. 사용자 확인 정보와 자동 추천 입력

| 정보/입력 | 확보 가능 분류 | 제공 가능한 범위와 필요한 확인 |
| --- | --- | --- |
| 선택 노선 | 실제 확보 가능 | 기존 BusRoute 내부 ID 및 표시 번호. 번호만 같다고 같은 Route로 합치지 않음 |
| 하차 정류장 | 실제 확보 가능 | 기존 occurrence 내부 ID, 이름, 인접 정류장. metadata 최신성과 운행 traversal 일치 필요 |
| “탑승했어요” 시각 | 사용자가 추가 입력해야 함 | 동작에서 새로 수집할 수 있으나 현재 기능/필드로 구현되지 않음. 사용자가 실제로 승차한 시각과 다를 수 있음 |
| 승차 정류장 | 사용자가 추가 입력해야 함 | GPS 없이 후보의 공간 문맥을 좁히는 중요한 입력. Stop 이름이 아닌 occurrence 확인 필요. 필수화 여부는 TASK-902/903 결정 |
| 차량별 진행 | 실제 확보 가능 | TAGO nodeid/order, 서울 detail stId/order/flag. 목록 완전성·direction·미실측 의미 제한을 함께 사용 |
| 차량별 원본 시각 | 특정 Provider에서만 가능 | 서울 dataTm. TAGO 현재 위치 계약에는 없음 |
| 응답 수신 시각 | 실제 확보 가능 | 두 mapper observedAt. 차량 데이터 생성시각 또는 승차시각으로 대체 불가 |
| 추적 ID·표시 번호 | 실제 확보 가능, 안정성 확인 필요 | TAGO vehicleno; 서울 vehId/plainNo. 후자는 DTO에는 있지만 Domain/Client 응답에는 없음 |
| 현재/최근 정류장명 | 확인 필요 | metadata에 정확한 ID/order가 대응할 때 “최근 확인 위치”로 보강 가능. 지금 정차 중이라고 단정 불가 |
| 실시간 운행 방향 | 현재 확보 불가 | mapper/Domain 값이 null. order나 노선번호에서 임의 생성 금지 |
| 행선지 | 특정 Provider에서만 가능 | 검증된 경기 occurrence의 destinationName. 서울 null. 표시 metadata이며 실시간 운행 방향의 보장 아님 |
| 신뢰 상태 | 확인 필요 | 기존 failure/empty/stale/ambiguity 근거는 있으나 후보 API 상태 계약 없음. 임의 신뢰도 수치 생성 금지 |

`[추론]` 노선+하차 Stop+버튼 시각만으로는 노선 위 여러 차량 중 사용자가 있는 위치를 알 수 없다. 단일 후보도 API 누락·지연·pagination·아직 출발하지 않은 버스·사용자의 다른 차량 탑승 가능성을 배제하지 못한다. 따라서 이 정보만으로 탑승 차량을 충분히 특정할 수 없다.

승차 occurrence와 실제 승차 시각을 함께 확인하면 후보를 제한할 근거가 늘어나지만, 배차가 밀집하거나 원본 시각이 없으면 여전히 복수 후보가 가능하다. 승차 정류장 입력 없이 사용자가 전체 번호를 직접 대조하는 경로도 이론상 가능하므로 이번 조사에서 승차 입력을 모든 흐름의 필수 계약으로 확정하지 않는다.

차량번호 UX는 **조건부 가능**이다. 전체 번호가 실제 차체/차내 어느 위치에 표시되는지, 탑승 후에도 안전하게 읽을 수 있는지, API와 일치하는지 별도 확인해야 한다. 끝 네 자리만으로 유일성을 가정하거나 기억이 불확실한 사용자의 확인을 강요해서는 안 된다. 확인할 수 없으면 미확정으로 남는다. 현재 Backend에는 탑승 차량 후보 API가 없고, 화면에 번호를 보여 주는 것은 후속 변경 사항이다.

## 9. GPS 없는 하차 시점 판단

`[추론]` 최소 전제는 사용자 확인으로 연결한 동일 차량, 유효한 Route 소속, 정확한 목적 occurrence와 비교 가능한 traversal, 신선하고 연속적인 진행이다. 이는 현재 API 필드가 모두 보장한다는 뜻이 아니다.

| 시점 | TAGO | 서울 |
| --- | --- | --- |
| 2정거장 전 | 추가 검증 필요. metadata상 target의 두 번째 predecessor와 nodeid/order를 연결할 수 있으나 그 값이 “그 정류장 도착”인지 미확정 | 조건부 가능성. 두 번째 predecessor의 정확한 stId/order + detail direct flag 등을 관측할 입력은 있음. 해당 이벤트/허용 지연은 미설계·미실측 |
| 1정거장 전 | 추가 검증 필요. predecessor 진행 상태는 후보 근거지만 출발 전/후 의미 미확정 | 조건부 가능성. predecessor의 stId/order와 flag=1에 대응 가능. 기존 before는 flag를 요구하지 않으므로 그대로 재사용 검증 완료 아님 |
| 목적 정류장 도착 | **지원 보류**. nodeid/order만으로 정확한 도착을 확인 못함; Arrival의 남은 Stop 수를 선택 차량에 연결할 ID도 없음 | 조건부 가능. 목적 stId/order와 flag=1 직접 근거가 C에 존재. 사용자 탑승 확인·현재 metadata·신선성·연속성 검증이 전제 |

두 predecessor는 raw `order-2/order-1`가 아니라 검증된 occurrence 배열의 실제 인접 edge로 찾아야 한다. 순서가 10, 20, 30이라면 30의 직전은 20이라는 예시는 설명용이며 실측 fixture가 아니다. predecessor가 없거나 회차/lap 문맥이 모호하면 해당 시점은 지원할 수 없다.

정류장 생략·polling jump로 목적지 이후에 처음 관측됐으면 “도착” 또는 놓친 before를 뒤늦게 만들어서는 안 된다. 역행·회차·동일 Stop 재방문·ID 변경·누락·stale·큰 순서 jump는 신뢰할 수 있는 연속성의 손실일 수 있다. jump가 실제 정상 진행인지 Provider 오류인지 구분할 추가 근거가 필요하다. 이 조사에서는 허용 jump 폭/시간이나 새 Event 타입을 정하지 않는다.

기존 TAGO ARRIVED가 일부 연속 관측에서 GPS 없이 실행될 수 있다는 **코드 사실**과, GPS 없이 실제 하차에 충분한 시점을 보장한다는 **제품 주장**은 다르다. 서울도 flag 수집과 push 전달 사이 지연 때문에 내려야 할 때보다 늦게 표시될 수 있다. 모든 before/도착 이벤트의 포착·정시 전달은 현재 근거로 보장 불가다.

## 10. API 호출량·Quota·장애 제약

| 항목 | TAGO | 서울 |
| --- | --- | --- |
| 기본 조회 | Route별 목록, pagination 있음 | Route roster + 선택 차량별 detail |
| 개별 차량 | 조사한 위치 서비스에서 미지원 | vehId 상세 조회 지원 |
| 공식 갱신 표기 | O2: 10~20초 | O4: 매 5초 |
| 실제 기존 표본 | A의 핵심 위치 변화 약 31초 뒤 1회 | C의 dataTm 변경 간격 7~21초 |
| 공식 평균 응답 표기 | O2: 500ms | O4: 100ms |
| 실제 HTTP 지연 | 미측정 | 미측정. 제공시각 age와 구분 |
| 개발계정 신청 가능 트래픽 | O1: 10,000 | O3: 1,000 |
| 초당 명세 표기 | O2: 30 TPS | O4: 30 TPS |
| 비용·인증 | 무료, serviceKey query | 무료, serviceKey query; 출처표시 조건 O3 확인 |
| 운영 한도 | 활용사례 등록 후 증가 신청 가능 표기 | 동일 |

트래픽 표시는 현재 계정의 승인 잔량이나 Service별/Key 공유 적용 단위의 확인 결과가 아니다. 포털에 일일/초당 초과 오류는 있지만 실제 quota 집계 범위·reset 시각·재시도 허용량은 계정 화면/제공기관 확인이 필요하다. 가이드 평균 응답과 갱신주기는 SLA가 아니다.

`[추론]` 한 cycle에서 TAGO 요청량은 각 Route의 필요한 페이지 수 `Σ P_r`, 서울은 roster Route 수 `R` + 해당 Route에서 조회할 고유 차량 수 `Σ V_r`, 여기에 제한된 실패 retry와 사용자 후보 조회가 더해진다. 한 Route·한 페이지/차량, retry 없음이라는 **계산 예시**에서 20초마다 24시간 조회하면 TAGO 4,320회, 서울 roster+detail 8,640회다. 현재 fixed-delay는 I/O 완료 뒤 20초여서 실제 건수와 같지 않으며 이 예시는 운용 주기 제안이 아니다. 특히 서울 1,000 표기만으로 상시 추적 규모를 약속할 수 없다.

현재 그룹화 구조는 같은 Route 사용자 간 요청 공유의 기반이다. 서울 첫 baseline은 전체 roster 차량을 detail 조회하므로 `1+V` 비용이 들고, “selected detail”이 항상 한 대라는 뜻은 아니다. 특정 탑승 차량들만 추적한다면 합집합 단위 상세 조회를 검토할 수 있지만 후보 추천이 모든 detail을 요구하면 비용은 다시 늘어난다. 후보 API와 기존 Scheduler 사이 공유·동시성·freshness 계약은 아직 없다. Redis나 별도 캐시 구조를 이번에 결정하지 않는다.

현재 Client 기본 connect 2초/response 5초와 scheduler 1회 retry는 **기존 구현값**이며 Phase 9 안전 threshold로 승인하지 않는다. 실패는 요청 실패/부분 실패로 표현해야 하며 quota·timeout을 차량 운행 종료로 해석할 수 없다.

## 11. 실패·불확실성 15개 시나리오

아래는 잘못된 확정을 피하기 위한 가능 범위 분석이며 최종 상태 enum/전이 계약은 TASK-902/903에 남긴다. 모든 경우 다른 차량 자동 전환은 제외한다.

| 상황 | 가능한 대응 분류 | 이유·필요 근거 |
| --- | --- | --- |
| 1 후보 0대 | 추천 보류 | 정상 empty/부분 목록/실패를 구분; 운행 없음 단정 불가 |
| 2 후보 1대·탑승 근거 부족 | 사용자 추가 확인 필요 | 후보 수는 탑승 증거가 아님 |
| 3 비슷한 시각 2대 이상 통과 | 사용자 추가 확인 필요 | 승차 occurrence·전체 번호 대조. 불가능하면 추천 보류 |
| 4 반대 방향 동시 존재 | 추천 보류 | 검증된 traversal/방향이 없으면 순서·이름으로 추측 불가 |
| 5 같은 이름 여러 occurrence | 사용자 추가 확인 필요 | ID/order/인접 이름으로 구분 가능할 때만 추천 가능 |
| 6 차량번호 없음 | 사용자 추가 확인 필요 / 추천 보류 | 서울 ID만으로 사람이 버스를 대조하기 어려움; TAGO는 tracking key도 상실 |
| 7 추적 ID 없음 | 지원 불가 | 해당 관측으로 동일 차량 추적 불가. 번호로 새 ID를 만들어 대체 금지 |
| 8 노선 metadata 오래됨 | 추천 보류 / 추적 보류 | 현재 운행과 target occurrence 연결 재검증 필요 |
| 9 실시간 API 실패 | 추천 보류 / 추적 보류 | 실패 차량의 위치나 종료를 생성하지 않음 |
| 10 추적 차량 소실 | 추적 보류 | 이유 불명, 타 차량 전환 금지 |
| 11 차량 재등장 | 재확인 필요 | 동일 ID만으로 동일 운행·탑승 관계 복구 불가 |
| 12 회차 진입 | 추적 보류 / 재확인 필요 | 다음 lap·운행 방향·목적 occurrence 모호성 |
| 13 동일 ID의 다른 차량 의심 | 추적 보류 / 재확인 필요 | 번호·Route·진행 불일치 해결 전 양쪽 모두 신뢰 보류 |
| 14 번호와 위치 불일치 | 추적 보류 / 재확인 필요 | 번호 또는 위치 한쪽을 임의 우선하지 않음 |
| 15 사용자가 다른 차량에 탑승 | 재확인 필요 | GPS 없는 API는 사용자 존재를 직접 감지하지 못함; 잘못 확인한 사실 자동 교정 불가 |

## 12. Provider별 지원 가능 범위

| 항목 | TAGO | 서울 |
| --- | --- | --- |
| 1 노선별 차량 조회 | 확인됨 — O1/O2, A/B; 완전성은 별도 | 확인됨 — O4, C |
| 2 안정적 추적 식별자 | 미확정 — 번호 장기 유지/재사용 정책 필요 | 미확정 — vehId 수명/재할당 정책 필요 |
| 3 사용자 표시 차량번호 | 확인됨 — vehicleno 필드 | 확인됨 — plainNo 필드 |
| 4 추적 ID↔표시 연결 | 확인됨 — 현재는 같은 필드; 물리 대조 별도 | 조건부 가능 — C 동일 item 대응; 실제 대조 및 충돌 검증 필요 |
| 5 현재 정류장 | 미확정 — nodeid의 전후 전환 시점 실측 필요 | 조건부 가능 — detail exact ID/order+flag=1; 이동 중 의미 제한 |
| 6 정류장 진행 순서 | 조건부 가능 — 최신 metadata와 ID/order 연결 | 조건부 가능 — detail과 최신 metadata 연결 |
| 7 운행 방향 | 미확정 — 회차/방향 실측 필요 | 미확정 — 회차/방향 실측 필요 |
| 8 차량별 상세 조회 | 지원 불가 — 조사한 TAGO 위치 서비스 범위 | 확인됨 — O4 detail, C |
| 9 Provider 데이터 시각 | 지원 불가 — 현재 위치 계약에 없음 | 확인됨 — dataTm; 원본 생성시각 의미는 미확정 |
| 10 실시간 갱신 신뢰성 | 미확정 — 지연/누락/원본 age 검증 필요 | 미확정 — dataTm과 실제 운행/지연 반복 대조 필요 |
| 11 회차 처리 | 미확정 — 전후 관측과 운행 문맥 필요 | 미확정 — 동일 |
| 12 누락 원인 해석 | 지원 불가 — 현재 필드만으로 원인 구별 불가 | 지원 불가 — 동일 |
| 13 탑승 후보 추천 | 조건부 가능 — 번호·승차 occurrence 등 검증 입력, 불확실 유지 | 조건부 가능 — ID/번호·detail·시각, 실제 확인 전제 |
| 14 사용자 차량 확인 | 조건부 가능 — 실제 표시와 번호 대조를 먼저 검증 | 조건부 가능 — 번호/ID 연결 보존과 실제 표시 대조 |
| 15 동일 차량 지속 추적 | 조건부 가능 — 짧은 정상 연속 관측만; 소실/회차 지원 보류 | 조건부 가능 — C 범위; Route/번호/ID 일관성 추가 검증 |
| 16 GPS 없는 하차 판단 | 미확정 — 정확한 정차·before 의미와 freshness 근거 부족 | 조건부 가능 — 동일 차량·traversal·fresh detail direct evidence; 정시 전달 미보장 |

`확인됨`인 표시 필드의 존재를 “현재 앱에서 제공됨” 또는 “사용자가 버스 안에서 확인 가능함”으로 해석하지 않는다. 조건이 충족되지 않는 노선/Provider는 지원 보류다.

## 13. 후속 TASK 영향과 미결정 사항

| TASK | 이번 조사로 전달하는 입력/제약 |
| --- | --- |
| 902 추천 설계 | 검증된 입력 목록, 시각만으로 식별 불가, 승차 occurrence 및 실제 번호 대조 후보, 단일 후보도 미확정. 점수·가중치·threshold는 여기서 정하지 않음 |
| 903 세션/Domain 설계 | provider/Route 문맥, 원본 추적 ID와 별도 표시 번호의 연결, 사용자 확인 근거, occurrence/traversal, 수신/제공 시각, 불확실·소실·재확인·재시작 의미가 필요함을 전달. 구체 field/상태/Schema는 미확정 |
| 904 후보 API | raw DTO에서 검증된 표시·시각을 안전하게 전달할 필요. 서울 plainNo 보존, 요청/응답 ID 검증, TAGO pagination 완전성, empty/실패/부분 응답 분리 검토 |
| 905 특정 차량 추적 | 기존 ACTIVE의 다음 차량 감시/재baseline을 그대로 적용 불가. GPS 없는 판정은 Provider별 검증 범위로 제한; 다른 차량 연결 금지 |
| 906 Notification | Device/Delivery/durable dispatch/FCM/retry·expiry 구조 재사용 후보. 기존 Event identity/Alarm lifecycle/tap은 그대로 재사용 확정 아님. TASK-903 최소 확장 및 TASK-710/711 완료가 선행 |
| 907 Flutter | 번호·최근 확인 위치·시각·미확정 이유·occurrence 구분, 추천과 실제 차량 확인 분리. 탑승 버튼/하차 선택의 화면 순서는 후속 계약에서 정함 |
| 908 E2E | 실제 번호 대응, 같은 노선 밀집·반대 방향, 회차/재방문, 소실·재등장, 지연·jump·목적지 이후 관측, 사용자 오확인, 취소·restart, 알림 전달/기기 표시 시각 분리 |

순서는 `901 → 902 → 903 → 904 → 905 → 906 → 907 → 908`이며 906은 기존 701~711 완료도 필요하다. 710/711 및 Phase 8은 901 조사 착수 조건이 아니고 이번 조사로 완료되지 않는다.

기존 공식 설계를 바꾸는 결정은 내리지 않았다. [ADR-006](../adr/ADR-006-v1-transit-provider-and-external-identifier-strategy.md)의 transient 식별과 namespace, [ADR-007](../adr/ADR-007-bus-alarm-transit-observation-and-event-semantics.md)의 UNKNOWN/도착 알림 의미, [ADR-009](../adr/ADR-009-bus-transit-metadata-persistence-and-diff-sync-strategy.md)의 metadata/표시 경계를 보존했다. §3.4의 구현 보장 공백을 문서 변경으로 정당화하지 않았다.

## 14. 추가 검증과 최소 실측 계획 — 실행 전 별도 승인 필요

최초 조사에서는 live 호출 승인 요청 없이 기존 근거로 조사를 마쳤다. 다음은 **당시 미실행 계획**이며 명시적 승인 없이는 실행하지 않는다. 이후 사용자 승인으로 축소·변경한 실행 조건과 결과는 §17을 따른다.

1. **Provider 정책 확인:** vehicleno/vehId의 유일성 범위, 유지기간, 재할당/번호 변경/차량 교체/노선 이동 규칙, dataTm 생성 기준·시계, nodeid/nodeord 전환 의미, quota 적용 단위를 공식 지원 경로로 확인한다. 제공기관에 연락하는 행위는 별도 사용자 지시가 필요하다.
2. **정상 운행 제한 실측:** Provider별 1개 운행 노선, 1개 추적 차량, 최대 2분·최대 6라운드·라운드 간 20초 이상. TAGO 위치는 라운드당 최대 2페이지(총 12회), 서울은 roster 1+detail 1(총 12회), metadata 부족 시 Provider별 최대 1회(총 2회), **전체 상한 26회**. 실패/timeout/불완전 pagination 또는 시간 상한에서 즉시 종료하고 retry하지 않는다. 2페이지 초과면 전체 목록 검증 미완료로 남긴다.
3. **기록 항목:** operation, 관측 시각, Route/상황, item 수와 pagination 완전성, 별칭 ID/번호의 대응, Stop ID/order/flag, Provider 시각, HTTP 경과시간, 필드 누락·중복·충돌만 저장한다. 원본 번호는 메모리에서 비교하고 일관된 별칭만 기록한다. 동일 별칭을 다른 차량에 재사용하지 않는다. 전체 raw·GPS·키·인증 URL은 저장하지 않는다.
4. **물리 표시·시점 검증:** 운행을 방해하지 않는 수동 확인으로 실제 차체/차내 표시와 응답 번호, 실제 정차/출발 시각을 대조한다. 이번 TASK에서는 탑승/iPhone/Xcode 검증을 실행하지 않는다. 2분 표본이 해당 이벤트를 놓치면 성공으로 기록하지 않는다.
5. **별도 회차·소실 조사:** 위 짧은 계획으로 회차/ID 변경/재사용을 재현할 수 있다고 약속하지 않는다. Provider 정책 답변과 별도로 상황·노선·시간·호출 상한을 다시 정해 승인받아야 한다. 통제된 fixture로 실패 처리를 검증하는 것과 실제 차량 동일성을 입증하는 것은 구분한다.

실측은 read-only로 독립 수행하며 Scheduler/application/DB 저장·수정/FCM/운영 상태 변경을 수반하지 않는다. `.env` 또는 Credential을 이번 조사에서 읽거나 새 파일에 추가하지 않았다. quota 잔량을 확인하지 못하면 실측 계획을 그대로 실행할 수 없으며 해당 한도를 먼저 확인해야 한다.

## 15. 최종 질문 Q1~Q10

| 질문 | 답 | 근거와 한계 |
| --- | --- | --- |
| Q1 TAGO 특정 차량 안정 추적? | **CONDITIONAL** | A/B의 짧은 정상 진행 correlation은 가능. 영구 ID·소실/재등장·회차 연속성 보장은 UNKNOWN |
| Q2 서울 특정 차량 안정 추적? | **CONDITIONAL** | C에서 ID/번호 유지와 detail 진행 확인. 실제 차량/노선 소속·회차·재사용 검증 전 무조건 보장 불가 |
| Q3 사용자 차량번호로 실제 탑승 확인? | **CONDITIONAL** | 번호 필드는 존재. 실제 차체/차내 대응 및 확인 가능성을 검증해야 하며 현재 UX 미구현 |
| Q4 버튼 시각만으로 충분한 자동 추천? | **NO** | 사용자의 승차 위치와 차량 탑승 사실을 제공하지 않아 충분한 식별·좁힘 근거가 없음 |
| Q5 승차 정류장 추가 입력 필요? | **CONDITIONAL** | 시각 기반 후보 제한에는 중요한 공간 문맥. 전체 번호 직접 대조 경로까지 일률 필수화할 근거는 없음 |
| Q6 단일 후보면 확정 가능? | **NO** | 후보 목록의 크기와 사용자 탑승 사실은 별개 |
| Q7 ID 소실/변경 후 동일성 보장? | **NO** | 현 근거로 보장 불가. 같은 번호 또는 근접 순서로 자동 재연결하지 않음 |
| Q8 GPS 없이 2전/1전/도착 가능? | **CONDITIONAL** | 서울은 확인한 차량·occurrence·fresh detail 전제의 기술적 가능성. TAGO 정확한 도착은 UNKNOWN/지원 보류. 모든 시점 포착·정시 전달은 보장 불가 |
| Q9 현재 Backend 재사용 가능? | **YES** | Provider Client/DTO, metadata/occurrence, 요청 공유 개념, failure 구분, Notification 전송 기반. 현재 evaluator/lifecycle 전체를 그대로 재사용한다는 뜻은 아님 |
| Q10 TASK-902 바로 시작 가능? | **CONDITIONAL** | 조사 완료 후 지원 보류 범위를 명시한 보수적 후보/확인 설계는 가능. TAGO 정확한 하차·양쪽 회차/소실 복구를 지원 확정하려면 추가 근거와 개발자 판단 필요 |

## 16. 완료 기준 점검과 기술적 결론

| TASK-901 완료 기준 | 충족 근거 |
| --- | --- |
| 공식 명세와 민감정보 제거 실제 응답으로 ID·번호·연결·진행·시각·지연 정리 | O1~O4 원문, E1~E4의 기존 실제 응답 기록, §4~6 익명 발췌. fixture와 신규 실측을 명확히 구분 |
| 유효 범위·연속성·변경/누락·조회·quota·GPS 없는 판단 조사, 미확정과 표본 한계 명시 | §3~7, §9~10, §14. 장기 ID·물리 확인·회차 등의 미검증을 지원 보류로 남김 |
| 확인·추천·지속 추적의 가능/불가/미확정 및 후속 조정안 | §8, §11~15. 후속 계약/구현을 선행 확정하지 않음 |

**TASK-901 조사 완료 기준은 충족한다.** 사용자가 허용한 기존 실제 응답 관측 기록을 사용했으며 새 실측으로 포장하지 않았다. 조사 완료는 제품의 정확성 검증 완료나 두 Provider 전체 지원 승인이 아니다. 미확정 항목을 명시하고 근거 부족 범위를 지원 보류로 남기는 것이 현 Task의 완료 범위다. 코드 변경이 없어 Backend/Flutter 전체 테스트는 실행하지 않으며 문서 출처·링크·diff·기존 Task 보존을 검증한다. 요청한 별도 커밋 절차에 따라 조사 문서 커밋 뒤 TASK-901 한 줄만 완료 기록한다.

“StopBell은 GPS 없이 사용자가 실제 탑승한 버스를 정확하게 식별하고, 동일한 차량만 지속 추적하여 하차 알림을 제공할 수 있는가?”에 대한 답은 **조건부 가능성은 있으나, 현재 근거만으로 전 과정을 정확하게 보장할 수 없다**이다.

- **TAGO:** 번호와 occurrence 진행으로 후보를 보여 주고 제한된 정상 관측을 연결할 수 있다. 사용자 실제 번호 대응, nodeid/order의 정차 전후 의미, 원본 freshness가 미검증이므로 GPS 없는 정확한 하차 알림은 지원 보류다. ID 소실·변경·재사용·회차 뒤 자동 연결은 지원하지 않는다.
- **서울:** 번호↔vehId 연결, 상세 Stop ID/order·direct flag·제공시각으로 GPS 없는 진행/도착 판단의 표본 근거가 있다. 실제 표시 대조, Route 소속과 traversal, 시각 및 연속성 검증이 충족된 범위에서 후보 확인·추적·하차 판단 설계를 진행할 수 있다. 원본 데이터 지연, polling 누락, 잘못된 사용자 확인, 회차·소실 후 동일성까지 보장할 수는 없다.
- **양쪽 공통:** GPS 없는 Provider API는 사용자 본인이 어느 차량 안에 있는지 직접 관측하지 않는다. 추천은 확인할 후보를 제공할 뿐이며 한 대라는 이유로 확정하거나 다른 차량으로 연결해서는 안 된다. 다음 개발자 판단은 이 보류 범위를 유지한 TASK-902 설계를 진행할지, 제한 실측·공식 정책 확인을 먼저 추가할지다.

## 17. 추가 제한 실측 결과 — 2026-10-16

### 17.1 승인·Quota와 실행 경계

Phase A에서 지정 문서와 현재 Client/DTO/Mapper/Monitoring을 확인했다. 기준은 `main`, `f345fa7c711dd48b486c24267062fc8c7985352e` (`docs: TASK-901 완료 기록`)이며 작업 트리는 clean이었다. Phase A의 `git fetch origin main` 뒤 HEAD와 origin/main이 일치했다.

O1/O3 공개 페이지의 개발계정 신청 가능 트래픽은 각각 10,000/1,000이었다. O2/O4 공개 활용가이드를 다시 읽어 30 TPS 표기를 확인했고 파일 SHA-256은 §2.1과 일치했다. **현재 계정의 승인 일일 한도·사용량·잔여량, 서비스/오퍼레이션별 합산 및 같은 Key의 공유 집계 범위, 초기화 시각은 확인하지 못했다.** 공개 기본값을 잔여량으로 간주하지 않았다.

사용자는 Phase A 이후 **이번 최대 24회 실측에 한해 정확한 잔여 Quota 확인을 생략하고 미확인 위험을 수용한다는 명시적 승인**을 제공했다. 따라서 §14의 잔여량 확인 선행 조건에 대한 이번 실행 한정 예외로 수행했다. 이는 향후 호출이나 운영 Quota 정책을 변경하는 결정이 아니다.

| 승인 항목 | 이번 실행 조건 |
| --- | --- |
| 범위 | Provider별 1개 노선·최대 1개 추적 차량 |
| 요청 상한 | TAGO 12회, 서울 12회, 전체 24회. 실패 시도도 포함 |
| 시간·라운드 | 전체 최대 120초, Provider별 최대 6라운드, 같은 Provider의 라운드 시작 간 최소 20초 |
| 추가 호출 | Metadata 0회, 다른 노선 탐색·대체 endpoint 호출 금지 |
| 중단 | HTTP/Provider 오류·Quota 초과·timeout·불완전 응답·pagination 불완전·식별/진행 충돌에서 전체 실행 중단. 시간/횟수 상한 우선 |
| 재시도 | 자동 재시도 및 HTTP redirect 추적 없음 |
| 실행 격리 | 저장소 밖 임시 Python 표준 라이브러리/curl 실행. Backend/Scheduler/Application, DB, Alarm, Notification/FCM, Flutter, Device 및 운영 설정 변경 없음 |
| 기록 | 인증값·전체 URL·원본 차량 ID/번호·전체 응답·GPS 저장 없음. 비교용 원본은 메모리에만 유지하고 허용한 집계·시각·오류 종류만 기록 |

macOS/Apple Silicon 로컬 환경에서 기존 `.env`의 `PUBLIC_DATA_SERVICE_KEY`를 메모리에서 읽었다. Credential 파일은 수정하지 않았고 인증값을 argv·문서·로그에 남기지 않았다. 각 HTTP 시도 전 카운터를 증가시키고 curl connect timeout 2초, 요청 전체 timeout 5초를 적용했다. 기존 Scheduler의 retry/전체 차량 detail 조회 경로는 사용하지 않았다.

### 17.2 대상과 실제 실행량

| Provider | 선정 노선·context | 근거 | 실제 HTTP 시도 | 추적 차량 |
| --- | --- | --- | ---: | --- |
| TAGO | 수원 7000, `GGB200000112`, `cityCode=31010`, `_type=json` | 기존 metadata 79개 occurrence와 realtime ID 연결, 과거 목록 13건 | **2회** | 필수 필드 검증 중 종료하여 선정하지 못함 |
| SEOUL_BUS | 7016, `busRouteId=100100447` | 기존 metadata 106개 occurrence 및 detail Stop ID/order 연결 | **0회** | TAGO에서 전체 실행 중단되어 선정하지 않음 |
| Metadata | 추가 조회 금지 | 기존 문서의 metadata 근거만 사용 | **0회** | 해당 없음 |

- 실행 시각: **2026-10-16 18:00:13.312~18:00:19.057 KST**, 시작부터 종료까지 **5.745초**
- 실제 전체 요청: **2회**. TAGO 1라운드의 2페이지를 받은 뒤 필수 필드 검증에서 종료했으며, 유효한 차량별 반복 관측 라운드는 0회
- TAGO에서 서로 다른 비어 있지 않은 `vehicleno` **12개**를 확인했다. 이는 12개의 응답 식별자이며 물리 차량 12대의 현장 검증이 아니다
- 서울은 현재 운행 여부, 차량 수 및 API 응답 상태를 이번 실행에서 확인하지 않았다
- 사용 중인 노선/metadata가 지금도 완전히 일치하는지 DB 또는 새 metadata 요청으로 검증하지 않았다. 과거 metadata를 현재 운행의 증명으로 사용하지 않았다

첫 TAGO 요청은 현재 Client와 동일하게 `pageNo/numOfRows`를 생략했다. 반환된 pagination을 보고 두 번째 요청에만 `pageNo=2`, `numOfRows=10`을 지정했다. 페이지당 필요 개수·전체 개수·ID 중복/누락 검증 후 차량별 `nodeid/nodeord` 검증으로 진행했다.

### 17.3 실제 요청 원장 — 민감정보 제거

아래 시각은 모두 2026-10-16 KST이며 operation은 두 요청 모두 `getRouteAcctoBusLcList`다. HTTP 소요 시간은 클라이언트 실행 기준 측정값이며 Provider 원본 데이터의 age가 아니다.

| 시도 | 요청 시각 | 응답 수신 시각 | 소요 시간 | HTTP / resultCode / 형식 | pageNo / numOfRows / totalCount | 반환 item 수 |
| ---: | --- | --- | ---: | --- | --- | ---: |
| 1 | 18:00:13.312627 | 18:00:16.338899 | 3,026.4ms | 200 / `00` / JSON | 1 / 10 / 12 | 10 |
| 2 | 18:00:16.341038 | 18:00:19.056089 | 2,715.1ms | 200 / `00` / JSON | 2 / 10 / 12 | 2 |

첫 페이지와 두 페이지 합산 검사 모두 `vehicleno` 누락 0건·중복 0건이었다. 합산 12건은 두 응답의 동일한 `totalCount=12`와 일치했다. 다만 페이지 사이에는 시간차가 있고 snapshot token이 없으므로 동일 시점 전체 운행 차량 집합의 원자적 완전성을 증명한 것은 아니다.

합산 목록의 필수 필드 검사 중 **적어도 한 item의 `nodeid`가 없거나 null·공백인 조건**이 관측되어 `missing_field:nodeid`로 전체 실행을 종료했다. 검증기는 이 세 경우를 하나로 분류했으므로 구체적인 원인, 해당 item의 페이지, 정확한 누락 개수 및 해당 차량 별칭은 기록으로 복원할 수 없다. 중단 사유가 두 번째 요청 원장에 붙은 것은 검증 시점 때문이며, 문제 item이 반드시 2페이지에 있었다는 뜻이 아니다. 남은 item의 필드 검사를 끝내지 않았으므로 다른 필드가 모두 정상이라고 판단하지 않는다.

추적 차량 선정 전에 종료하여 차량별 별칭·번호 대응·Stop ID/order·flag·Provider 시각·age·진행 변화 기록은 생성하지 않았다. 원본 전체 응답을 저장하지 않았으며 누락 원인을 다시 확인하기 위한 추가 요청도 하지 않았다. HTTP 오류·Quota 초과는 이 2회에서 관측되지 않았지만 잔여 Quota 확보나 전체 응답 정상의 증거는 아니다.

### 17.4 확인된 문제와 기존 결론의 변화

1. **[실측 확인] 기본 pagination 생략 요청의 부분 목록:** 첫 요청은 `totalCount=12`인데 item은 10개만 반환했다. 현재 `TagoVehicleLocationClient`는 이 요청 형식을 사용하면서 추가 페이지 조회나 완전성 검사를 하지 않는다. 따라서 §3.4의 잠재 위험에 대해 **현재 요청 형식 한 번으로 전체 목록을 얻지 못하는 실제 사례**를 확보했다. 기존 Backend를 실행하지 않았으므로 실제 Alarm의 차량 소실 오판까지 재현한 것은 아니다.
2. **[문제 관측] 성공 코드와 별개인 정류장 식별 정보 불완전:** HTTP 200 / `resultCode=00`만으로 모든 item의 `nodeid` 유효성을 보장할 수 없었다. O1/O2의 필수 필드 표기와 별개로 이번 응답의 사용 가능한 정류장 식별 정보가 부족했다. 생략/null/공백의 정확한 구분 및 발생 원인은 미확정이다.
3. **[일부 실측 확인] 제한된 목록 개수 정합성:** 2페이지 합산 개수와 `totalCount`가 맞고 차량번호 식별자 중복·누락이 없었다. 이를 차량별 진행 관측의 완전성이나 페이지 간 원자성으로 확대하지 않는다.
4. **[미확정 유지] 서울과 장기 추적:** 서울은 미호출이므로 요청/응답 ID 대조, roster/detail 일치, freshness 등에 새 근거가 없다. 두 Provider의 실제 탑승 사실·ID 장기 안정성·회차·소실 후 동일성도 검증하지 못했다.

기존의 TAGO GPS 없는 정확한 도착 지원 보류와 서울의 조건부 가능성 결론을 완화하지 않는다. 새로 확보한 것은 TAGO 부분 목록과 필수 진행 정보 불완전의 실패 근거이며, 차량 추적 지원 확정의 근거가 아니다. Production 코드 수정은 후속 Task에서 판단한다.

### 17.5 항목별 분석

| 항목 | TAGO | 서울 |
| --- | --- | --- |
| A 식별자 연속성 | **미확정** — 한 목록의 두 페이지뿐이며 동일 식별자 반복 관측 없음 | **미확정** — 미호출 |
| B 차량번호 대응 | **미확정** — `vehicleno` 존재만 확인, 별도 추적 ID·물리 표시·시간상 대응 검증 없음 | **미확정** — vehId/plainNo 미관측 |
| C 정류장 진행 | **문제 관측** — `nodeid` 사용 불가 item으로 중단, 진행 변화 미검증 | **미확정** — stId/stOrd/stopFlag 미관측 |
| D 데이터 신선도 | **미확정** — 현재 계약에 차량별 Provider 시각이 없고 이번에는 반복 관측도 없음. HTTP 소요 시간으로 대체 불가 | **미확정** — dataTm 및 지연 미관측 |
| E 응답 완전성 | **문제 관측** — 첫 요청 10/12건. **일부 실측 확인** — 두 페이지 합산 12건·ID 중복/누락 0건. 정류장 필드 완전성과 원자적 snapshot은 미확보 | **미확정** — 미호출 |
| F 요청 대상 일치 | **미확정** — 노선별 목록 조회이며 개별 차량 ID 요청 없음 | **미확정** — 요청 vehId/응답 vehId 대조 미실행 |
| G 복수 차량 구분 | **일부 실측 확인** — 목록에서 서로 다른 식별자 12개 구분. 사용자 탑승 차량과의 연결은 미확정 | **미확정** — 미호출 |
| H 이상 상황 | **문제 관측** — 기본 요청 부분 목록과 정류장 ID 불완전. 역행·ID 재사용·회차·장기 누락은 검사하지 못함 | **미확정** — 미호출이며 정상으로 간주하지 않음 |
| I GPS 없는 하차 판단 | **미확정 / 지원 보류** — 정류장 진행 정보가 불완전하고 정확한 물리 도착·freshness 근거 부족 | **미확정** — 이번 신규 검증 없음. 기존 §9의 조건부 가능성만 유지 |

### 17.6 TASK-902 영향 — 지원 확정과 분리

아래는 실측과 기존 근거를 함께 해석한 후속 설계 입력이다. 점수·가중치·새 threshold·Domain/API/DB 계약을 결정하지 않는다.

| 질문 | 판단과 이번 결과의 영향 |
| --- | --- |
| Q1 차량번호를 사용자 확인용으로 제공할 수 있는가? | 조건부 가능성 유지. TAGO 번호 필드의 존재는 확인했으나 실제 표시와 대응은 미검증. 서울 plainNo는 기존 근거만 있음 |
| Q2 노선과 하차 정류장만으로 후보를 충분히 좁힐 수 있는가? | 충분하다고 볼 수 없음. 이번 TAGO 목록에 서로 다른 식별자 12개가 있었고 정류장 정보 불완전도 관측됨 |
| Q3 승차 정류장 선택이 필요한가? | 공간 문맥을 좁히는 유용한 입력 후보. 필수화 여부는 실제 번호 직접 확인 경로 등을 고려해 TASK-902에서 판단해야 함 |
| Q4 “탑승했어요” 시각을 추천 근거로 활용할 수 있는가? | 조건부 보조 입력일 뿐 단독 식별 근거가 아님. TAGO 원본 시각 부재와 이번 진행 관측 실패를 시각 입력으로 보완했다고 볼 수 없음 |
| Q5 후보 한 대일 때도 사용자 확인이 필요한가? | 필요. 목록 누락 가능성과 사용자 탑승 사실은 별개이며 이번 기본 pagination 부분 목록이 그 위험을 보강함 |
| Q6 Provider별 추천 근거가 다른가? | 다름. TAGO는 번호/진행 목록, 서울은 vehId/plainNo 및 detail/시각 입력이 있으나 서울은 이번에 재검증하지 않음 |
| Q7 어떤 상황에서 추천을 보류해야 하는가? | 목록 완전성 미확보, 필요한 ID/order 누락, 중복·번호 충돌, freshness 판단 불가, 방향/occurrence 모호성, Provider 오류. 이번에는 부분 목록과 정류장 ID 불완전이 실제 근거 |
| Q8 특정 차량 확정 후 언제 추적을 중단해야 하는가? | ID/번호 불일치, 대상 소실, 진행 모순·회차/재방문 모호성, stale/실패·불완전 응답으로 연속성을 신뢰할 수 없을 때 보류/중단해야 함. 다른 차량으로 자동 대체하거나 동일성을 추측해 재연결하지 않음 |
| Q9 TAGO 하차 알림을 현재 데이터만으로 지원할 수 있는가? | 지원 확정 불가. 이번에는 필수 정류장 ID도 불완전했으며 정확한 정차 의미와 freshness 근거가 부족함 |
| Q10 서울 하차 알림을 현재 데이터만으로 지원할 수 있는가? | 기존 조건부 가능성만 유지. 이번 미호출로 요청 ID 대조·fresh detail·사용자 차량 대응 조건을 검증했다고 볼 수 없음 |

TASK-902는 이 보류 조건을 명시하는 보수적인 추천/확인 설계의 입력을 얻었지만 이번 실측으로 지원 범위가 확대되거나 설계 Task가 완료된 것은 아니다. **사용자가 확인한 특정 버스를 다른 차량과 혼동하지 않고 목적지까지 추적할 수 있다는 보장은 여전히 확보하지 못했다.** 차량별 연속 관측을 재개하거나 서울을 별도로 실측하려면 새 실행 승인이 필요하다.

### 17.7 문서·Task·검증 상태

- 이번 변경은 이 조사 문서의 최초/추가 조사 구분과 §17 보강뿐이다. 코드·Dependency·테스트·DB·devlog 및 설계 Owner 문서는 변경하지 않았다
- TASK-901 `[x]`, TASK-902 `[ ]`, TASK-710/711 및 Phase 8의 기존 상태를 유지한다
- 요청 원장의 실제 횟수·시각·pagination·중단 사유를 대조하고 공식/기존 관측/이번 관측/추론을 구분했다. 추적·서울 미관측 항목을 성공으로 승격하지 않았다
- 문서 링크·민감정보 미포함·Task 상태 보존·`git diff --check` 검증을 통과했다. 코드 변경이 없으므로 Backend/Flutter 테스트는 실행하지 않았다
