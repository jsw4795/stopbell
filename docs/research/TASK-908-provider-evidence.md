# TASK-908 사전 검증 1단계: Provider 차량·진행·하차 가능성 증거

§1~17은 Phase A 기록을 보존한다. **Phase B의 사전검증은 §18, 최초 점검·보류는 §19, 실제 실행은 §20, 후속 인증 없는 연결 진단과 로컬 경로 수정은 §21**에 구분한다. 각 절의 승인·환경·호출 횟수는 해당 작업 시점의 기록이다. 누적 인증 데이터 요청 시도는 **서울1회/TAGO0회, 실제 데이터 확보0건**이며, §21의 **인증 없는 서울 HTTP 진단4회**는 별도다.

## 1. 목적과 실행 범위

- 조사일: **2026-10-21, Asia/Seoul**
- branch: `main`, 기준 HEAD: `02f8666dbeed52305e0cb512d9bb50f710e53dc4` (`feat: TASK-907 하차 알림 FCM 수신 및 화면 이동 구현`)
- 시작 시 branch/status/log 확인: main, clean. 작업 중 별도로 나타난 `task-list.md` 문장부호 변경은 보존하고 이 작업의 진행 기록만 추가했다
- **Phase A 완료·Phase B 미실행. 이번 실제 TAGO 0회 / SEOUL_BUS 0회 / metadata API 0회 / FCM 0회**
- 공개 설명 페이지·첨부 활용가이드 열람은 수행했다. 이는 인증된 Provider 데이터 조회/실측이 아니다
- Backend/Application/Scheduler/DB/Flutter/iPhone을 실행하지 않았다. `.env`·Credential을 읽거나 변경하지 않았다
- Production 코드·설정·기능 토글·Dependency·API·Migration·기존 ADR·devlog 변경 없음. `BOARDING_TRACKING_UNSUPPORTED` 유지
- TASK-901~907 `[x]`, TASK-908 `[ ]`, TASK-710/711 기존 상태 유지. 실제 탑승 E2E나 Phase 9 종료 감사의 완료 보고가 아니다

이번 결과는 지원 판정의 입력이다. Provider 의미·하차 가능성을 새로 확정하는 설계 결정이나 기능 활성화 승인이 아니다. 신규 실측 승인이 없으므로 Phase B는 실행하지 않았다. 과거 TASK-901 승인/Quota 예외를 재사용하지 않는다.

## 2. 근거 종류와 과거 TASK-901 실측

| 구분 | 인정 범위 |
| --- | --- |
| **공식** | §3의 공개 명세를 이번에 확인한 내용. 예제 응답은 실측이 아님 |
| **과거 관측** | [TASK-901 §6·§17](TASK-901-bus-vehicle-identification.md), [기존 조사](../transit-provider-research.md)의 기록. 원본 전체 응답을 이번에 재확보한 것이 아님 |
| **이번 관측** | **없음: 실제 데이터 API 호출 0회** |
| **코드** | 기준 HEAD의 구현과 기존 테스트 소스 읽기. 기존 테스트를 이번에 재실행했다고 주장하지 않음 |
| **synthetic fixture** | 독립 Python 도구의 인공 데이터 테스트. Provider의 실제 의미/신뢰도를 입증하지 않음 |
| **추론** | 위 근거로부터 남는 조건과 위험. VERIFIED로 승격하지 않음 |

증거 등급은 `VERIFIED`(직접 관측과 적절한 독립 근거), `PARTIALLY_VERIFIED`(명시한 일부 조건), `UNVERIFIED`(부족), `CONTRADICTED`(필요 가정과 관측 충돌), `UNAVAILABLE`(이번 승인·환경 등의 부재)로 구분한다. 공식 정의를 읽었다는 사실은 문서 확인이며 runtime 검증과 다르다.

제품 지원 등급은 별도로 `SUPPORTED_FOR_DEFINED_SCOPE`, `CONDITIONAL_NOT_ENABLED`, `UNSUPPORTED`, `UNDETERMINED`를 사용한다. 일부 정상 표본을 전체 Provider·노선·lap으로 확대하지 않는다.

과거 2026-10-16 18:00:13.312~18:00:19.057 KST, TAGO 수원 7000 (`GGB200000112`, `cityCode=31010`)의 승인 실측은 **2 HTTP 시도**였다. pagination 생략 첫 요청은 10건/totalCount 12, 추가 page 2는 2건이었다. 합산 12건·번호 식별자 중복 없음은 기록됐지만, 적어도 한 item의 nodeid가 누락/null/공백인 조건으로 종료했다. 정확한 누락 수·차량·페이지는 복원 불가다. 차량 선정/연속 관측 전 종료했으며 서울 0회였다. 두 페이지의 동일 시점 원자적 snapshot도 입증되지 않았다.

2026-09-09의 과거 표본은 TAGO 동일 번호의 order 7→8, 다른 표본의 1→3, 서울 같은 ID/번호의 detail order 22에서 flag 1→0 및 다음 23에서 flag 1을 기록했다. 서울 발췌 7개 row의 수신-제공 시각 차는 3~12초였다. 이것은 HTTP 지연·물리 도착 지연·SLA가 아니며 현재 상태를 증명하지 않는다. 과거 서울 표본 Route는 이번 계획의 7016과도 다르다.

## 3. 공식 자료 재확인과 필드 계약

아래 공개 자료를 **2026-10-21** 확인했다. T Data 일부 페이지의 브라우징 timeout은 인증 없는 공개 HTML 읽기로 보완했다. 활용가이드는 메모리에서 ZIP/XML로 읽었고 저장소에 복제하지 않았다. 인증 query·실제 차량번호·GPS는 보고서에 복제하지 않는다.

| ID | 공식 source | 확인한 범위와 한계 |
| --- | --- | --- |
| O1 | [TAGO 위치](https://www.data.go.kr/data/15098533/openapi.do), [첨부 v1.0](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000002761705&fileDetailSn=1) | 노선 위치 목록, pagination, vehicleno/nodeid/nodeord 및 선택 GPS. 차량 발생시각·도착 flag·trip/lap 보장 없음 |
| O2 | [서울 위치](https://www.data.go.kr/data/15000332/openapi.do), [첨부 20230530](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000003552007&fileDetailSn=1) | roster/detail의 개별 필드, stopFlag 0=운행중·1=도착, dataTm=제공시간, isrunyn 0=운행종료·1=운행 |
| O3 | [T Data 노선 CSV](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=33), [공개 API 설명](https://t-data.seoul.go.kr/category/dataviewopenapi.do?data_id=1033) | 노선 ID/명칭/유형/거리. 노선 정보이며 occurrence별 승객 하차 허용 계약 아님 |
| O4 | [T Data 노선·정류장 CSV](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=34), [공개 API 설명](https://t-data.seoul.go.kr/category/dataviewopenapi.do?data_id=1034) | route_id, node_id=경유노드, link_dstnc_acmtl, sttn_sn=정류장 순서. 하차 허용/실제 정차 보장은 확인 못함 |
| O5 | [T Data 정류장 CSV](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=35), [공개 API 설명](https://t-data.seoul.go.kr/category/dataviewopenapi.do?data_id=1035) | sttn_id/nm/ty/no, 좌표, BIT. 유형 예시는 일반차로. 방향·노선별·유효기간별 하차 허용 의미는 확인 못함 |
| O6 | [TAGO 노선](https://www.data.go.kr/data/15098529/openapi.do), [첨부 v1.0](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000002761691&fileDetailSn=1) | Route 기·종점·유형·시간, Route Stop ID/order 및 **선택 updowncd=상하행 구분(0/1)**. 하차 허용 필드는 확인 못함 |
| O7 | [TAGO 정류소](https://www.data.go.kr/data/15098534/openapi.do), [첨부 v1.0](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000002761693&fileDetailSn=1) | ID/이름/번호/좌표, 경유노선 관계. occurrence별 하차 허용 근거는 확인 못함 |
| O8 | [GBIS 기반정보 공식 명세](https://www.gbis.go.kr/gbis2014/publicService.action?cmd=mBaseInfo) | route.turnSeq=회차 정류소 순번, routeStation.upDown=노선방향, staOrder=노선 운행 정류소 순번. destination 보강을 하차 분류로 승격할 근거 없음 |

첨부 SHA-256:

| 자료 | SHA-256 |
| --- | --- |
| O1 위치 가이드 | `e506c89e48f4f2bc88baaf7accead5e0a6bee1107494630449095c5b7cc0a796` |
| O2 서울 가이드 | `257e0778fb5036ae859fe67805a55361a5db1836a17083e5684517c713987f90` |
| O6 노선 가이드 | `0e80c78364b082f030a82154a521fed2b7af2c6acf35869751620ecb8f0db371` |
| O7 정류소 가이드 | `9fd0427cb161567975fadf25ea3f53cf18ad8d6e6ee2f53afaa7c8890760433b` |

O1/O2는 TASK-901에 기록된 파일 해시와 같다. TAGO `updowncd`는 **이번 공식 가이드에서 확인한 선택 metadata 필드**다. 과거 경기 실측에서 해당 값이 제공됐다는 뜻은 아니다. 현재 `TagoMetadataClient.Stop`/저장 snapshot에는 없고 실시간 차량의 방향/trip을 직접 증명하지도 않는다. 과거 보고의 “실응답에 방향 없음”을 “공식 서비스 전체에 방향 필드가 없음”으로 확대하면 안 된다.

O2 웹페이지는 `getBusPosByRouteSt` 구간 응답 설명이다. roster/detail 가이드의 상세기능명 `getBusPosByRtidList`/`getBusPosByVehIdItem`과 예제 Call Back 경로 `getBusPosByRtid`/`getBusPosByVehId`가 다르다. 현재 Backend는 상세기능명 경로를 쓴다. 이번에 경로 호환성·HTTPS 가용성을 실측하지 않았으며 실패 시 대체 경로를 자동 호출하지 않는다. 구간 응답의 optional routeId를 현재 roster/detail DTO에 있는 것처럼 사용하지 않는다.

## 4. 현재 Backend의 직접 차단 원인

[BoardingSessionStartValidator](../../backend/src/main/java/com/stopbell/boarding/service/BoardingSessionStartValidator.java)는 reference를 User 문맥으로 resolve → 최신 metadata read → `candidates.revalidateSelected` 수행 후 **항상 `BOARDING_TRACKING_UNSUPPORTED`를 throw**한다. 검증 결과를 `VerifiedBoardingStart`로 만드는 실제 성공 분기가 없다. 현재 metadata에 목적 occurrence 하차 가능 분류/근거가 없기 때문이다.

이 마지막 throw 이전에도 TAGO는 `revalidateSelected`에서 즉시 미지원이며, 서울은 이동 detail·이전 detail 부재/이동 상태·반복 Stop 포함 traversal 등에서 미지원이다. 후보 오류/만료/번호·metadata 변경·Provider 실패·target not ahead는 별도 오류가 먼저 발생할 수 있다. 따라서 모든 실패를 alightability 하나로 환원하지 않는다.

실제 enum은 [BoardingTraversalSnapshot.AlightAvailability](../../backend/src/main/java/com/stopbell/boarding/entity/BoardingTraversalSnapshot.java)의 **`ALLOWED`, `PROHIBITED`, `UNKNOWN`**이다. `DISALLOWED`라는 값은 없다. ALLOWED/PROHIBITED는 nonblank `targetClassificationEvidence`를 요구하고, UNKNOWN은 근거 null을 요구한다. 생성자의 문자열 검사 자체가 근거의 진위를 검증해 주지는 않는다.

`BoardingRouteSnapshot`에는 alightability 필드가 없으므로 현재 metadata로부터 ALLOWED를 공급할 수 없다. [VerifiedBoardingStart](../../backend/src/main/java/com/stopbell/boarding/service/VerifiedBoardingStart.java), [BoardingSession](../../backend/src/main/java/com/stopbell/boarding/entity/BoardingSession.java)의 최초 생성, [BoardingProgressEvaluator](../../backend/src/main/java/com/stopbell/boarding/service/BoardingProgressEvaluator.java)는 검증된 ALLOWED를 요구한다. UNKNOWN을 ALLOWED로 바꾸거나 마지막 throw만 제거하는 방식으로 해결할 수 없다.

## 5. 현재 클래스별 책임·nullable·실패 감사

아래 경로는 모두 `backend/src/main/java/com/stopbell/` 아래다. 기존 Alarm mapper와 후보/Boarding 경계를 구분했다.

| 클래스/경로 | 현재 책임과 검증 | 남는 한계 |
| --- | --- | --- |
| `transit/client/TagoVehicleLocationClient` | pageNo=1/numOfRows=1000, 정상 `00`, header/body와 nonnegative totalCount=item 수 검사 | 응답 pageNo/numOfRows 별도 검사·추가 페이지 없음. item ID/order/중복·source age 미검증 |
| `transit/client/SeoulBusVehicleLocationClient` | request busRouteId, 정상 `0`/정상 empty `4`, 성공 body/list 필수 | item identity·시각·실시간 Route 소속은 Client가 증명하지 않음 |
| `transit/client/SeoulBusVehicleDetailClient` | request vehId, `0`/`4` 및 envelope 검사 | 요청 ID=응답 ID·번호는 상위에서 검사. detail 자체 Route ID 없음 |
| `transit/client/TransitClientProperties` | 양수 timeout, nonblank provider URL/key. 운영 기본 connect 2초/response 5초 | 설정 존재는 잔여 Quota·endpoint 성공 증거 아님 |
| `transit/client/TransitProviderClientException` | HTTP/TRANSPORT/PROVIDER/PROTOCOL 분리, top message는 provider/operation/kind만 | cause는 원래 HTTP/transport 예외 보존. stack/cause 전체가 민감정보 비노출이라고 단정 못함 |
| `transit/dto/tago/TagoVehicleLocationResponse`/Item | wrapper/list/count/page boxed nullable, 번호/ID/order/GPS 수용 | DTO 자체 required item 필드/의미 validation 없음, source timestamp/도착 flag 없음 |
| `transit/dto/seoul/SeoulBusVehicleLocationResponse`/Item | nullable vehId/plainNo/sectOrd/sectionId/stopFlag/dataTm/nextStId/isrunyn/GPS | coarse 구간을 Stop order로 바꾸지 않음. isrunyn은 현재 후보/Tracker에서 사용하지 않음 |
| `transit/dto/seoul/SeoulBusVehicleDetailResponse`/Item | nullable vehId/plainNo/stId/stOrd/stopFlag/dataTm/tmX/tmY | Route/trip/direction/lap 필드 없음 |
| `transit/mapper/BoardingCandidateMapper` | TAGO 번호=임시 ID, 서울 ID/전체 번호 별도 보존. blank→null, strict uuuuMMddHHmmss/KST, 누락/잘못된 시각 사유 | nullable Stop/order/flag/source time. roster coarse 문자열은 의미 검증 아님. GPS 사용 없음 |
| `transit/mapper/SeoulBusTransitObservationMapper` | 기존 Alarm용 detail→Observation, flag 1/0→ARRIVED/MOVING, dataTm KST | plainNo 미보존·방향 null, request Route를 붙임. 물리 노선 소속 증명 아님 |
| `transit/mapper/TagoTransitObservationMapper` | 기존 Alarm용 번호→ID, nodeid/order, GPS와 수신 Clock | providerDataTime/direction null, arrival UNAVAILABLE. 기존 Alarm의 GPS 활용을 하차 검증으로 전용하지 않음 |
| `transit/service/BoardingCandidateMetadataService` | read-only transaction, Route/목적/선택 승차 소속 확인, 불변 ordered traversal 읽기 | 하차 분류·실시간 방향 없음 |
| `transit/service/BoardingRouteSnapshot` | ID/Stop ID/order의 ordered fingerprint, 표시 이름 제외 | 구조 정합성과 실제 현장/하차 허용은 별개 |
| `transit/service/BoardingCandidateReferenceStore` | User/화면 문맥, 원래 source/수신 freshness 60초, 최대 10,000 reference, 재조회 교체/eviction | restart 소실, 현재 재검증 대체 불가, TAGO source age는 알 수 없음 |
| `transit/service/BoardingCandidateService` | 목록 failure→503, empty/partial 구분, ID/번호 충돌·detail 결합·시각·exact ID/order 검사, index 간격 정렬 | 후보 가능≠추적 가능. identical record는 distinct 처리. roster/detail 시각의 동시 snapshot 보장 없음 |
| `boarding/service/BoardingSessionStartValidator`/`VerifiedBoardingStart` | §4의 최종 차단과 검증 증거 transaction 경계 | 실제 ALLOWED 공급 경로 없음 |
| `boarding/service/BoardingVehicleTracker` | 선택 ID/전체 번호만 추적, roster 중복/동일 번호 다른 ID/소실, detail ID/번호/시각 검사 | 서울 exact stopped만 DIRECT_ARRIVAL. 그 외 UNVERIFIED, orderOnly=false/direction=null/traversalAmbiguous=false. 이 값들은 방향/lap을 검증했다는 뜻이 아님 |
| `boarding/service/BoardingProgressEvaluator` | 60초 freshness, PT2M 공백, ID/Route/번호, meaning·direction·ambiguity·ALLOWED, metadata ID/order·단조 진행·중복 source 검사 | 의미 검증을 입력에 의존. 새 source time의 같은 occurrence 허용은 물리 이동의 증명이 아님 |
| `boarding/service/BoardingTrackingMetadata` | 현재 Route/city와 전체 occurrence ID/Stop/order를 저장 snapshot과 비교 | 하차 분류나 upstream의 현재 운행/방향을 새로 검증하지 않음 |
| `boarding/service/BoardingSessionService` | 생성/재확인 Provider I/O는 lock 밖, 짧은 transaction의 User/Session lock·fingerprint·freshness·revision·idempotency | 실제 생성/재확인은 validator에 차단. 성공 경로의 fixture 주입은 production 지원 아님 |
| `boarding/service/BoardingSessionPollingService` | detached 읽기→metadata/Provider/평가→store 원자 적용, 예외와 보류/복구 구분 | 자체 Scheduler가 아니며 기존 cycle 공유. 이번에 실행하지 않음 |
| `boarding/service/BoardingTrackingRuntime` | 기본 닫힌 프로세스 gate, commit된 새 revision/checkpoint만 READY, freshness 만료/늦은 callback 차단 | gate 자체가 Provider semantics·물리 lap을 증명하지 않음 |
| `boarding/entity/BoardingTraversalSnapshot` | version1 ordered unique occurrence, target 분류와 근거 | Stop 재방문 표현 가능≠현장 traversal 검증 |
| `boarding/entity/BoardingCheckpoint` | ID_ORDER 또는 VERIFIED_ORDER_ONLY, index/order·snapshot 일치와 UTC 시각 | order-only 검증은 외부 입력 근거가 필요, source time nullable |
| `boarding/domain/BoardingProgressObservation` | UNVERIFIED/VERIFIED_REFERENCE/DIRECT_ARRIVAL, orderOnlyVerified·direction·ambiguity 분리 | VERIFIED_REFERENCE/order-only의 실제 공급 근거 없음 |
| `boarding/domain/BoardingTrackingSnapshot` | 불변 evaluation 입력, revision/state·identity 일치, 재확인 필요한 보류 사유 유지 | DB TRACKING은 현재 프로세스 READY·실제 차량 연속성 보장 아님 |

Provider 예외의 top message와 structured polling 실패 로그는 구분한다. 현재 Client cause 및 Scheduler의 일반 RuntimeException stack 로깅 경로가 있어 **production 전체 로그의 Credential 비노출까지 이번 조사로 인증할 수 없다**. 수정 범위는 아니다. 독립 도구는 외부 예외 메시지·stderr·URL·resultMsg를 출력하지 않고 상수 failure kind만 기록한다.

## 6. StopOccurrence 하차 가능성

**현재 persistence/후보 metadata에 목적 occurrence를 ALLOWED로 분류할 공식 근거가 연결돼 있지 않다.** 이번 공식 자료의 확인 범위에서도 일반 정류장·경유 노드·방향·회차 정보만으로 승객 하차 허용을 판정할 의미 계약을 찾지 못했다. 모든 기관 데이터에 그러한 자료가 없다는 결론은 아니다.

| source | 실제 확보/확인 범위 | 하차 분류로 승격할 수 없는 이유 |
| --- | --- | --- |
| 서울 노선마스터 | 노선 ID/명/유형/거리, CSV parser의 provider scope | 버스 노선 유형은 특정 방향/occurrence의 승객 이용 허용과 다름 |
| 서울 정류장마스터 | 유형·번호·BIT·위치. 현재 parser는 유형을 scope에 활용하나 persistence에는 보존 안 함 | 일반차로·중앙차로 등의 유형만으로 해당 노선의 실제 정차/하차 허용이 보장되지 않음 |
| 서울 노선·정류장마스터 | 경유노드/순서/거리 누계, 과거 7016의 한 realtime occurrence 대응 | 경유노드 존재가 승하차 지점 보장 아님. 별도 관계 의미와 이용 제한 필요 |
| TAGO Route/Stop/정류소 | 노선, 경유 정류소 ID/order 및 선택 상하행 | 정차/미정차·승차전용/하차전용·차고지 등의 이용 정책을 확인 못함 |
| GBIS destination | 전체 TAGO traversal 정합성 확인 후 turnSeq/마지막 occurrence 이름으로 표시 보강 | 회차/행선지 표시는 이용 허용 증거가 아니며 현재 버스의 방향/운행도 증명하지 않음 |

특히 [ADR-009](../adr/ADR-009-bus-transit-metadata-persistence-and-diff-sync-strategy.md)의 `가상정류장` 포함은 **버스 metadata scope** 결정이다. 이를 하차 ALLOWED로 승격하지 않는다. 반대로 이름에 차고지/종점이 있다는 이유로 PROHIBITED를 만들지도 않는다. 같은 물리 Stop이라도 다른 노선·방향·순번의 occurrence별 정책이 다를 수 있다.

일반 승하차 지점, 무정차 경유, 회차·차고지·종점, 방향별 이용 제한을 구분하려면 기관의 **노선×방향×occurrence별 정차/승하차 허용 정의, 코드표, 적용일/예외/운행 구분**이 필요하다. 차고지·회차 관련 필드나 Stop type의 공식 설명이 있어도 그 필드가 승객 하차 허용까지 뜻하는지 기관 계약으로 확인해야 한다. 현장에서는 해당 방향/차량이 실제 승객을 내려 주는 지점·안내·예외와 metadata를 대조해야 한다. 한 번 정차한 사실만으로 정규 이용 허용 정책을 확정하지 않는다.

근거 확보 전 모든 미분류 target은 **UNKNOWN**이다. nodeid 누락, 단순 통과, 이름, ID 존재, 일반 Stop type으로 ALLOWED/PROHIBITED를 추측하지 않는다.

## 7. 서울: roster/detail·이동 중 의미·시간

O2 roster에는 vehId/plainNo/sectOrd/sectionId/stopFlag/dataTm/nextStId/isrunyn, detail에는 vehId/plainNo/stId/stOrd/stopFlag/dataTm이 있다. `sectOrd`는 구간순번이며 `stOrd`와 동치가 아니다. `isrunyn=1`도 같은 lap 또는 다음 target의 하차 가능을 뜻하지 않는다. 현재 Boarding mapper/Tracker는 isrunyn을 판정에 사용하지 않는다.

현재 후보/추적 코드의 ID/전체 번호 일치 검사와 source time 파싱·60초 age 검사는 과거 TASK-901 당시의 일부 결합 공백을 줄였다. 하지만 다음은 남는다.

- mapper의 요청 Route 보존은 Provider의 독립적인 현재 노선 증명이 아니다. roster와 detail 사이 노선 변경/시간차가 가능하고 detail에는 Route/trip이 없다
- 양 응답의 시각은 각각 검사하지만 같은 Provider 시각·같은 snapshot·같은 차량 상태가 되도록 보장하지 않는다. 원래 후보→현재 roster/detail 시각 단조성 검증이 모든 roster/detail pair의 시간 순서 일치와 같은 것은 아니다
- Tracker는 roster source time이 checkpoint보다 역행하는지, evaluator는 detail이 checkpoint보다 역행하는지 검사한다. roster/detail의 직접적인 상호 시각 정합성 전체를 보장하지 않는다
- source timestamp는 제공시간이다. 실제 차량 발생/물리 도착 시각·시계 정확도는 별도 미확정이다

**이동 중 의미의 현재 증거:** 공식 `stopFlag=0`은 운행 중이라는 것까지만 명시한다. 과거 한 차량에서 order 22의 flag 1 뒤 같은 ID/order의 0이 반복되고 다음 order 23에서 1이 관측됐다. 이는 해당 표본에서 출발 뒤 같은 기준점이 유지된 정황이며, 모든 `stOrd=N/flag=0`을 “N에서 출발함” 또는 “N에 곧 도착함”으로 해석할 근거가 아니다. **이번 연속 관측은 0건**이다.

다음 실측은 각 roster/detail 시각·동일 ID/번호를 보존하며 이동→도착→이동 전후 stId/stOrd 전환, nextStId/sectionId 변화, 동일 source time/중복 응답을 대조해야 한다. 육안 정차/출발 시각과 기관 의미 설명을 별도로 확보한다. 20초 cycle은 짧은 정차 flag를 놓칠 수 있으므로 짧은 표본에서 전환 미포착은 미정차의 증거가 아니다. 더 촘촘한 관측은 별도 승인·예산이 필요하다.

제품 등급은 **서울 하차 세션 UNSUPPORTED(현재 실제 시작 불가)**, 기술적 가능성은 **CONDITIONAL_NOT_ENABLED**다. 새롭게 검증된 좁은 실제 하차 지원 구간은 없다.

## 8. TAGO: 완전성·진행·GPS 없는 gap 2/1

현재 Client는 과거 §17의 pagination 생략 방식과 다르다. page 1/1000 요청에서 totalCount와 실제 item 수가 다르면 `PROTOCOL`이므로 부분 목록을 정상 전체 목록으로 사용하거나 소실로 해석하는 위험을 줄인다. 1000건 초과·서버 truncation 시 추가 페이지로 복구하지 않고 실패한다. **응답 pageNo/numOfRows를 검사하지는 않는다.**

count 일치는 모든 item의 유효 nodeid/order·번호 유일성·원자적 snapshot·실제 전체 운행 차량 수·upstream freshness의 보장이 아니다. 과거 nodeid 불완전 문제는 count 보강으로 해결됐다고 할 수 없다. 이번에 그 원인/빈도/최신 발생 여부를 실측하지 않았다. 도구는 일부 페이지를 합쳐 지원을 주장하지 않고 count/page context 불일치에서 중단한다.

vehicleno는 전체 번호와 임시 correlation key를 겸한다. 별도의 안정적인 내부 ID는 확인 명세에 없다. nodeord 증가·ID/order 일치는 노선 기준 진행 참고이며 정차/접근/직접 도착/승객 하차 허용을 서로 대체하지 않는다. Provider 시각이 없어 같은 reference 반복을 새 freshness 증거로 삼을 수 없고, 변경된 reference에도 실제 발생시각은 알 수 없다.

현재 GPS 비의존 설계에서 좌표는 검증 대체 수단이 아니다. TAGO Arrival은 선택 차량과 연결할 검증된 identity가 없으므로 위치의 차량에 임의 결합하지 않는다. **현재 근거로 TAGO GPS 없는 gap 2/1 준비 판단의 실제 지원은 확정할 수 없다. 정확한 하차 tracking은 UNSUPPORTED 유지**다. 향후 index 간격 계산 자체가 가능하더라도 meaning/동일 traversal/target ALLOWED/신선성 한계를 별도로 해결해야 한다.

## 9. 차량 동일성·회차·반복 occurrence·lap

| 상황 | 현재 보호/확인 | 미확정과 지원 경계 |
| --- | --- | --- |
| 전체 번호 직접 대조 | 서울 ID/번호 별도 보존, TAGO 번호 보존, 사용자 명시적 확인 UX | 실제 차체/차내 번호 대응·사용자 탑승은 API로 검출 못함 |
| 한 응답 중복·번호 변경 | 후보 서비스와 Tracker가 충돌 보류, detail ID/번호 검증 | long-lived ID 재사용·그럴듯한 동일 번호/진행의 다른 운행 검출 보장 없음 |
| 소실·재등장 | VEHICLE_MISSING 등의 영속 보류와 사용자 재확인, 자동 대체 금지 | 소실 원인은 알 수 없음. 같은 ID만으로 복구 불가 |
| 단순 전진 구간 | exact ID/order→현재 metadata의 index 대응 | 단조 증가만으로 같은 방향/운행 절대 증명 불가 |
| 왕복·기종점 회차 | 역행·reset이면 보류, 시작 시 반복 Stop traversal은 보수적으로 차단 | 종점 이후 전환·동일 ID 유지·다음 lap 규칙 미실측 |
| 순환·같은 Stop 재방문 | Domain은 occurrence ID/order로 표현 가능 | 동일 Stop 다른 order의 실제 의미/다른 lap 오연결 미검증 |
| 중간 분기·차량 노선 변경 | metadata 불일치·ID/번호 모순 발견 시 보류 | 분기가 현재 필드에 드러나지 않으면 감지 못할 수 있음. trip/branch identity 부재 |

Domain은 “반복 Stop이 있다는 이유만으로 Route 전체를 차단하지 않고 관측 쌍으로 판단”을 규정하지만 **현재 시작 검증은 반복 Stop을 포함한 traversal 전체를 보수적으로 미지원 처리**한다. 구현의 좁은 지원 경계이며 문서를 일괄 차단 정책으로 바꾸지 않았다. 해당 경로의 해제에는 별도 증거와 구현 판단이 필요하다.

현재 adapter의 `directionContext=null`, `traversalAmbiguous=false`는 판단할 입력이 없다는 코드 상태다. null 일치가 검증된 같은 방향을 뜻하거나 false가 회차 부재를 증명하는 것은 아니다. 이 상태에서 미지원만 제거하면 감춰진 lap·분기 위험이 남는다. 이번 지원 차단은 알려진 위험의 오알림을 막지만 모든 숨은 오연결을 검출하는 완성된 알고리즘을 입증하지는 않는다.

## 10. 핵심 질문 A~L: 근거와 제품 판단

공통 코드 근거는 §5, 문서 계약은 [ADR-011](../adr/ADR-011-bus-vehicle-recommendation-and-user-confirmation.md), [ADR-012](../adr/ADR-012-boarding-session-and-alight-notification-contract.md), [Domain](../domain-model.md#boardingsession), [API 지원 경계](../api.md#탑승-세션-api-task-903)다. 아래 실측은 모두 **과거 기록**이며 이번 실측이 아니다.

| 질문 | TAGO 증거 | 서울 증거 | 실제/공식/코드 근거와 남은 불확실성 → 지원 판정 |
| --- | --- | --- | --- |
| A 전체 번호↔실제 버스 | UNVERIFIED | UNVERIFIED | O1 vehicleno/O2 plainNo 존재·과거 수집, 후보/Flutter 표시 구현. 현장 번호 대조 없음 → 번호 확인 후보만 조건부, 물리 탑승 보장 없음 |
| B ID·번호 일관성 | PARTIALLY_VERIFIED | PARTIALLY_VERIFIED | 과거 짧은 동일 번호, 서울 ID/번호 대응. 후보/Tracker 충돌 검사. 영구 ID·재사용 미검증 → 장기 동일성 지원 안 함 |
| C roster/detail 결합 | UNVERIFIED(별도 detail 없음) | PARTIALLY_VERIFIED | O2와 과거 서울 한 차량, 현재 ID/번호 검사. 최신 pair·독립 Route/동시시각 증명 없음 → 조건부 미활성 |
| D 반복 진행 순서 | PARTIALLY_VERIFIED | PARTIALLY_VERIFIED | 과거 TAGO 7→8/1→3, 서울22→23. 이번0건, metadata 현재 일치 미검증 → 좁은 과거 참고 |
| E 이동 reference 의미 | UNVERIFIED | PARTIALLY_VERIFIED | O1 순서/O2 운행중 정의, 서울 과거 같은 Stop의 1→0. 언제 stOrd 전환하는지 일반 보장 없음, adapter UNVERIFIED → 준비 판단 사용 차단 |
| F occurrence exact 대응 | PARTIALLY_VERIFIED | PARTIALLY_VERIFIED | 과거 일부 ID/order metadata 일치, 현재 exact pair 검사. 모든 Route/반복 occurrence/최신 metadata 미확보 → 전체 지원 불가 |
| G 목적 하차 가능 분류 | UNVERIFIED | UNVERIFIED | O3~8에서 필요한 이용 허용 계약 미확보, 현재 metadata에 분류 없음 → UNKNOWN/시작 미지원 |
| H GPS 없는 gap2/1 | UNVERIFIED | UNVERIFIED | ADR-012 index edge와 fixture의 계산 경로. 실측 meaning/ALLOWED·현장 타이밍 미확보 → 실제 하차 지원 불가 |
| I 회차·분기·lap 안전성 | UNVERIFIED | UNVERIFIED | 소실/역행/ambiguity 보류·재확인 구현. 미드러난 lap·분기 검출 불가, 반복 Stop 시작 차단 → 자동 연결 지원 안 함 |
| J 새 진행·중복 구별 | PARTIALLY_VERIFIED | PARTIALLY_VERIFIED | TAGO source time 없음, order 변경만 관측 가능. 서울 제공시각 과거 갱신·evaluator 중복/역행 검사. 실제 발생시각 보장 아님 → 제한된 입력 검증 |
| K 60초/2분/20초 계약 | UNVERIFIED | UNVERIFIED | 설정·코드·기존 synthetic 경로. 지연/짧은 정차 누락·recovery 안전성 현장 검증 없음 → SLA·운행 연속성 보장 없음 |
| L 누락/오류에서 거짓 준비 방지 | PARTIALLY_VERIFIED(코드) | PARTIALLY_VERIFIED(코드) | Client 실패/empty 분리, evaluator 보류·gate·Event UNIQUE, 이번 도구 fixture. 현장 end-to-end 정확성 아님 → production 차단 유지 |

**이번 승인 실측 가용성은 양쪽 UNAVAILABLE**다. 정상 empty·인증 실패·quota·transport 실패는 Provider 기능 자체의 UNSUPPORTED 판정과 구분한다. 기존 TAGO “공식 필수 nodeid가 모든 item에서 사용 가능” 가정은 과거 §17 관측에 의해 **CONTRADICTED**다. 현재 모든 item에서 누락된다는 주장도 하지 않는다.

## 11. 현재 알고리즘과 실제 증거의 간격·최소 변경 후보

아래는 **후속 제안**이며 이번에 Production 코드를 변경하지 않았다. 근거가 부족한 분기는 구현안을 확정하지 않는다.

| 현재 코드 요구 | Provider 근거 / 부족한 검증 | 후속 최소 검토 파일·좁은 적용 범위·필요 테스트 |
| --- | --- | --- |
| target ALLOWED+classification evidence | 둘 다 검증 source 미연결 (§6) | 먼저 기관의 occurrence별 이용 계약 확보. 그 후 `BoardingCandidateMetadataService`/`BoardingRouteSnapshot`, `BoardingSessionStartValidator`에서 검증 분류를 전달하는 가장 작은 경로 검토. UNKNOWN/PROHIBITED/오래된·다른 방향 근거/metadata 변경 테스트. persistence가 필요하면 별도 승인·Schema 검토 |
| 동일 ID/번호·Route 검증 | 서울 pair 존재, detail 독립 Route 부재. TAGO 별도 ID 없음 | `BoardingCandidateService`, `BoardingVehicleTracker`에서 승인된 pair 시각 정합성·현재 roster 문맥 보강 검토. 불일치/소실/다른 Route·오래된 roster 테스트. 이 검사로 trip identity가 생기지는 않음 |
| meaning VERIFIED_REFERENCE 또는 DIRECT_ARRIVAL | 서울 flag1 공식/과거 근거, 이동 일반 의미 미확정. TAGO flag 없음 | 서울의 기관 설명+연속 관측이 일치한 범위만 `BoardingVehicleTracker`의 meaning 공급 검토. `BoardingProgressObservation`/Evaluator는 기존 모델 우선. flag0→1→0, 같은 source time 변화·전환 누락 테스트. 근거 전 moving 지원 보류 |
| metadata exact pair/orderOnlyVerified | 일부 과거 exact 대응. 누락 nodeid item의 실제 order 검증 없음 | `BoardingCandidateService`/Tracker에서 order-only 활성화 보류. 향후 해당 Route order 체계 독립 검증·반복 occurrence/충돌/역행 테스트 후 좁은 source만 검토. ID/Stop 이름 합성 금지 |
| directionContext/traversalAmbiguous·단조성 | trip/lap 직접 필드 없음, 현재 null/false | 검증 가능한 방향/turn/branch 문맥부터 확보. `BoardingVehicleTracker`, `BoardingSessionStartValidator`, 필요 시 `BoardingTrackingMetadata`의 최소 범위 검토. 반복 Stop의 구별 가능한 쌍/불가 쌍, reset·회차·노선 변경·공백 테스트. updowncd/GBIS destination만으로 해제하지 않음 |
| checkpoint freshness 60초, 공백 PT2M | 서울 제공시각 입력 있음, TAGO 원본 age 없음 | `BoardingProgressEvaluator`, `BoardingTrackingRuntime`, `BoardingSessionPollingService` 기존 보류 계약 유지. stale/future/역행/같은 source time/큰 공백·commit 후 gate 테스트. 새 threshold나 가짜 timestamp로 보완하지 않음 |
| gap2 우선/gap1 대체, 최대 Event1회 | index 계산·UNIQUE/atomic 경로 구현, 물리 알림 타이밍 미검증 | 현재 `BoardingProgressEvaluator`/`BoardingTrackingStore`/`BoardingNotificationService` 유지. 새 유효 관측·TTL 만료 기회 미소비·revision/중복 테스트와 현장 시각 대조 필요 |
| 새 검증 commit 후 runtime READY | gate 구현, Provider 의미 입력이 선행 조건 | `BoardingTrackingRuntime`/polling의 제한 해제 제안 없음. 미복구/미검증 입력으로 gate를 열지 않음 |

ALLOWED 하드코딩·일반 정류장 일괄 허용·stopFlag0 직접 도착·nodeord만으로 하차 확정·order reset을 lap으로 연결·같은 번호의 다른 ID 자동 교체·새 flag 우회는 제안하지 않는다. 새 source의 저장/만료/책임이 확정되기 전 Migration이나 ADR을 선행 추가하지 않는다.

## 12. Phase B 제안: 승인 전용 호출·Quota 계획

**미승인·미실행 계획**이다. 작은 단발 표본이며 회차/분기/lap·물리 탑승·하차 허용까지 증명하는 예산이 아니다. 기존 노선의 재사용은 과거 metadata 연결 근거 때문이며 현재 운행/지원의 증명이 아니다.

| 항목 | SEOUL_BUS | TAGO |
| --- | --- | --- |
| 대상 | 서울 7016, `busRouteId=100100447` | 수원 7000, `routeId=GGB200000112`, city31010 |
| 선택 근거 | 과거 T Data 106 occurrence 및 detail 한 점의 exact 연결 | 과거 79 occurrence 연결·nodeid 불완전 재확인 |
| endpoint | `https://ws.bus.go.kr/api/rest/buspos/getBusPosByRtidList`, `…/getBusPosByVehIdItem` | `https://apis.data.go.kr/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList` |
| 최대 HTTP 시도 | **12** (roster6+선택한 같은 차량 detail6) | **6** (목록6, page1/1000) |
| 수집 차량 | 첫 충돌 없는 roster에서 기술 표본 1대 선정, 이후 변경 금지 | 첫 충돌 없는 목록의 기술 표본 1대, 이후 변경 금지 |

전체 최대 **18 HTTP 시도**, 최대 **6라운드**, 전체 최대 **150초**. Provider별 라운드 시작 간 최소 **20초**, 모든 HTTP 시도 시작 간 최소 **2초**, connect timeout **2초**, request timeout **5초**, child cleanup 포함 최대 **6초**를 다음 호출 전 승인 window/전체 deadline 안에 확보한다. 순서는 서울 roster→동일 차량 detail→TAGO이며 어떤 오류든 전체 실행을 중단한다. 서울 표본이 TAGO 오류보다 먼저 확보될 가능성을 두지만 성공을 보장하지 않는다. 한 Provider만 승인하면 해당 범위만 실행한다.

- HTTP 실패/timeout/첫 페이지/roster/detail 모두 **시도 전** count 증가. 추가 페이지·자동 retry·redirect 추적·대체 URL/노선·metadata API·다른 차량 조회 **없음**
- count mismatch, 잘못된 pagination context, missing progress, 중복 identity·번호 변경·차량 소실, 시각/진행 역행·모순, stale/future, HTTP/Provider/quota/protocol 오류에서 즉시 중단. 정상 empty는 기록하고 상한 안에서 다음 라운드를 기다릴 수 있으나 운행 종료로 해석하지 않음
- 서울 roster/detail 시각은 수집 도구에서 별도 delta를 기록하며 detail이 roster보다 오래되면 중단. 이는 production에 없는 연구용 보수 검사이며 같은 snapshot 보장 아님
- 개수 일치에도 missing field/진행 의미 부족이면 분석 실패 또는 미확정. 부족한 표본을 보충하려고 승인량을 넘기지 않음
- 공개 portal 신청 가능 트래픽은 TAGO10,000/서울1,000이나 **현재 계정 잔여량 아님**. live 전 계정 활용승인 화면/기관에서 한도·사용량·동일 key/서비스/operation 공유 집계·reset 시각을 확인하고 병행 사용자/Backend 호출도 고려해야 함. 확인 못하면 live 보류. 도구는 `quotaConfirmed=true` 기록을 요구하지만 계정을 자동 검증하지는 않음
- 현재 Backend의 서울 기본 URL은 HTTP다. 도구는 Credential 보호를 위해 **고정 HTTPS만** 사용한다. HTTPS나 상세기능명 경로가 동작하지 않아도 HTTP/짧은 경로로 fallback하지 않음. 변경 실행은 별도 범위 승인/도구 검토 필요. TLS/권한 실패는 Provider 기능 미지원의 증거가 아님
- raw ID·전체 번호·응답·좌표는 메모리에만 두고, 별칭 secret도 실행 종료 시 폐기. stdout JSONL만 사용하며 stdout을 파일로 보존할지는 승인 시 지정. 저장한다면 저장소 밖 제한 경로에 비식별 원장만 두고 후속 검토/보고 이관 후 삭제 시점을 실행 승인에 명시. 기본은 파일 저장 없음
- 승인에는 Provider/Route/endpoint/최대 시도/UTC 실행 window/시간·간격·Quota 확인/기록 정책을 포함해야 함. **승인 파일을 작성하는 행위는 신규 인간 승인을 대신하지 않음**. 이번에 승인 파일을 만들거나 live를 실행하지 않음

이 소량 관측으로 flag 전환을 못 잡거나 정상 데이터가 안 나오면 `UNVERIFIED`다. 회차·분기·다른 lap·소실 후 재등장·현장 번호 대응은 별도 구간/시각/예산 승인과 독립 공식 근거가 필요하다.

## 13. 독립 수집 도구와 오프라인 검증

로컬 일회성 도구: `tools/provider_evidence_probe.py`, `tools/test_provider_evidence_probe.py`. 두 파일은 Git에 포함하지 않으며 실측·후속 분석 후 사용자 정리 대상이다. 이 문서는 파일 링크에 의존하지 않고 실행 명령과 핵심 정책을 보존한다. Python 표준 라이브러리와 기존 `/usr/bin/curl`만 사용하며 Backend import/DB/FCM 연동이 없다. **기본 dry-run은 Credential/승인 파일도 읽지 않고 HTTP 0건**이다.

```sh
python3 -B tools/provider_evidence_probe.py
python3 -B -m unittest discover -s tools -p 'test_provider_evidence_probe.py' -v
```

Synthetic JSON replay는 `--replay /private/tmp/synthetic-fixture.json`이다. 최상위 `provenance`는 `SYNTHETIC_FIXTURE`, `observations`는 provider/operation/payload/pollStartedAt/responseReceivedAt 배열이며, 선택 `metadata`에는 Provider별 `{stopId, order}` 배열을 둔다. 원본 실제 응답 파일을 만들도록 요구하지 않는다. replay는 network 0건이며 미확정 의미를 VERIFIED로 바꾸지 않는다. 정규화 원장을 raw replay로 재해석하지 않는다.

Live에는 신규 승인 후에만 `--live --approval-file /private/tmp/task908-approval.json`을 사용할 수 있다. 기존 환경변수 `PUBLIC_DATA_SERVICE_KEY`만 메모리에서 사용하고 key argv 옵션은 없다. `.env`를 자동 읽지 않는다. 승인 JSON은 다음 필드를 모두 요구한다(이 목록은 승인 자체가 아님).

| 필드 | 승인 기록 내용 / 도구 상한 |
| --- | --- |
| approvalReference | 신규 인간 승인 참조. stdout에 내용 출력 안 함 |
| notBefore/notAfter | 승인된 timezone 포함 시각. UTC로 해석·실제 wall clock 검사 |
| providers | `SEOUL_BUS`, `TAGO` 중 승인된 집합 |
| scopes | Provider별 고정 `route`와 `operations` 배열(§12의 순서), TAGO는 `city=31010` 포함 |
| quotaConfirmed | 실제 확인 후 true, 미확인 false이면 실행 거부 |
| maxAttempts / maxTotalAttempts | Provider별 상한12/6, 전체18 이하 |
| maxDurationSeconds / rounds | 150초 이하 / 6 이하 |
| minRequestIntervalSeconds / minRoundIntervalSeconds | 각각2초 이상 / 20초 이상 |
| timeoutSeconds | 최대5초 |

curl은 첫 옵션 `--disable`로 개인 `.curlrc`의 trace/redirect/retry 설정을 무시한다. URL은 stdin config로만 전달하고 argv/문서/로그에 인증 query를 두지 않는다. stderr와 외부 예외 문자열을 출력하지 않으며 자동 retry0·redirect 추적 없음·HTTPS만·크기 제한을 적용한다. 실행별 무작위 HMAC 별칭은 원본 번호/ID를 복원할 secret을 저장하지 않는다. 서로 다른 실행의 별칭을 같은 차량의 증거로 합치지 않는다.

원장에는 provider/operation/route·vehicle 별칭, pollStartedAt/responseReceivedAt/elapsedMs, HTTP status/허용된 짧은 result code, item/total/page count, Provider 시각 존재·값·검사 가능한 age, Stop ID/order/flag 존재, HMAC Stop/section 별칭과 order/flag, coarse field 존재/isrunyn, 이전 진행 관계·수신 간격·충돌/오류를 기록한다. Provider 시각이 없으면 null이며 수신시각을 도착시각으로 대체하지 않는다. 최초 표본/동일 source time·응답/변화 없는 진행/역행도 구별한다. 현재 live CLI는 metadata를 자동 조회하지 않으므로 metadataMatch는 미확정이다. replay의 metadata 검증은 인공 대응 검증일 뿐 실제 최신 metadata 검증이 아니다.

도구는 자체 의미 해석으로 PREPARE_ALIGHT를 만들거나 지원을 활성화하지 않는다. 기술 표본 첫 차량 선택은 사용자 탑승 차량 자동 추천이 아니다. 차량 소실 뒤 다른 번호/ID를 고르지 않으며 live는 즉시 중단한다. 재등장 보류 동작은 synthetic replay로만 검사했다.

## 14. Provider별 지원 매트릭스와 이번 실측 원장

| 기능 | TAGO | SEOUL_BUS |
| --- | --- | --- |
| 번호 확인용 후보 | 기존 조건부 제공 유지. 물리 번호 대응 미검증 | 기존 조건부 제공 유지. detail 없이 roster 번호만도 가능 |
| 동일 차량 짧은 진행 참고 | 과거 일부 표본 PARTIALLY_VERIFIED, 최신 연속 관측 UNAVAILABLE | 과거 일부 표본 PARTIALLY_VERIFIED, 최신 결합·연속 관측 UNAVAILABLE |
| GPS 없는 정확한 하차 tracking | **UNSUPPORTED** | **UNSUPPORTED(실제 Session 시작)** / 기술 가능성 CONDITIONAL_NOT_ENABLED |
| target ALLOWED 공급 | UNKNOWN/UNVERIFIED | UNKNOWN/UNVERIFIED |
| gap2/1 실제 준비 알림 | **UNSUPPORTED** | **UNSUPPORTED**, moving 의미·하차 분류 등 추가 근거 필요 |
| 회차·분기·다른 lap 자동 연결 | **UNSUPPORTED** | **UNSUPPORTED** |
| 이번 새로 검증된 좁은 실운영 하차 범위 | **없음** | **없음** |

| 이번 실행 항목 | 결과 |
| --- | --- |
| Phase B 신규 승인 | 없음 |
| TAGO / 서울 / metadata / FCM 시도 | **0 / 0 / 0 / 0** |
| 실제 HTTP 응답 성공/실패 | **각0건**. 인증 실패도 경험하지 않았음 |
| live 관측 기간·실제 진행 변화 | 해당 없음 / **0건** |
| live 미실행 사유 | 신규 승인 부재. quota/환경 상태를 성공·실패로 판단하지 않음 |
| 기록·개인정보 | 실제 차량 식별자/번호·좌표·raw 응답·Credential 수집/저장 없음. 인공 fixture와 공개 문서만 사용 |

## 15. 남은 차단 원인과 다음 순서

1. **Alightability:** 기관의 occurrence별 실제 이용 허용 의미·코드표·유효 범위 확보. 현재 metadata와의 정확한 연결 방식이 정해지기 전 ALLOWED 공급 구현 보류
2. **Moving semantics:** 서울 이동/정차 전후 stId/stOrd/flag와 source time 연속 관측 및 기관 설명. TAGO는 직접 도착 의미와 원본 age 한계 해소 근거가 우선
3. **Identity continuity:** ID/전체 번호·요청/응답/roster pair 검증, 실제 차체 대조, ID 수명·재사용·노선 변경 공식 정책/관측
4. **Traversal ambiguity:** 기종점·분기·순환·반복 Stop의 실제 전환 확인. 구별 못하는 구간은 지원 제외/보류하며 null/false로 검증 완료 처리하지 않음
5. **Source freshness:** 서울 dataTm의 정확한 시간 의미/지연·시계 보장 확인, TAGO 무시각 반복을 새 진행으로 처리하지 않음. 60초/2분/20초 기본값의 현장 적합성 검증

공식 metadata 조사와 신규 승인 범위 확정 → 제한 실측 → 증거별 좁은 지원 범위의 개발자 판단 → §11의 최소 Backend 변경 및 영향 테스트 → 실제 탑승/iPhone E2E 순서다. 이번 Phase A 결과만으로 지원 제한을 풀면 안 된다.

## 16. 실제 버스·iPhone E2E 진입 전제

- 해당 **노선·방향·운행 구간·목적 occurrence**의 하차 허용 근거, 현재 metadata exact 대응, 동일 차량 진행/meaning·회차 경계와 source freshness를 확보해야 한다
- Backend의 검증 증거 공급을 구현·검증하고 지원 범위를 명시적으로 판단해야 한다. fixture·관리 DB 삽입·새 feature flag로 production 차단을 우회하지 않는다
- 사용자 전체 차량번호 직접 확인과 선택한 목표/방향/실제 이용 가능 지점을 현장에서 대조한다. 위치 권한/단말 GPS·Provider GPS 거리 기반 대체 판정 없이 수행한다
- 실제 정차/출발/하차 관찰 시각, Provider data time, 요청 시작/수신, 평가/Event/전송/FCM accepted, iPhone 실제 표시/tap 시각을 분리한다. 미관측 정차·놓친 gap2를 소급 생성하지 않는다
- foreground/background/terminated 수신·정확한 Session 조회/tap, 취소/만료·재시작 gate·차량 소실·유사 후보·기존 Alarm 병행을 검증한다. FCM 전송/현장 실행에는 해당 작업의 승인·설정 경계가 추가로 필요하며 이번에 발송하지 않았다
- TASK-710의 terminated tap/stale·계정 권한과 TASK-711 회귀 책임을 별도로 유지한다. 오류/누락/중복/지연·미지원 구간을 기록하고 필수 미확정이 남으면 TASK-908을 완료하지 않는다

## 17. 검증 결과·문서 정합성

실행 환경은 Python **3.14.8**, 기존 curl **8.7.1**이다. 독립 도구 오프라인 테스트, default dry-run, 문서 local link/Task 상태·범위 검사 및 `git diff --check`를 수행했다. 구체 결과는 아래 실행 기록을 기준으로 한다.

| 검증 | 결과 |
| --- | --- |
| Python probe synthetic unittest | **39개 전부 통과, failure/error/skip 0**. §13의 명령으로 실행했으며 모든 transport는 mock/fake, 실제 Provider 호출0 |
| 기본 dry-run | 실제 transport 0회, Credential/승인 파일 읽기 없음 |
| 민감정보 검사 | 인공 key/번호/ID/Stop/GPS·외부 오류 메시지가 원장/argv에 나오지 않는지 테스트, HMAC 실행 간 분리 검사 |
| Quota·시간·rate 경계 | 인공 clock/transport로 Provider별/전체 상한, 초과 직전 차단·timeout 포함 시도 count·window·간격 검사 |
| Provider 실패·불완전 | TAGO count/일부 페이지·nodeid/order 누락·중복, 서울 pair/empty/실패·flag0/1·시각·metadata·반복 occurrence·회차 모호성 synthetic 검사 |
| no retry/no redirect | curl 호출 인자와 실패 시 추가 호출0을 mock 검사. 실제 endpoint/TLS 성공의 검증 아님 |
| 문서/Task/Git | 보고서·task-list의 local 파일 링크, TASK-901~908/710/711 checkbox 보존, Python AST, 신규 파일 whitespace/실제 번호 패턴 및 `git diff --check` 통과 |
| Backend/Flutter 전체 suite | **미실행**: production 변경 없음. 과거 875/430개 결과를 이번 실행 결과로 재사용하지 않음 |

요구사항/Architecture/Domain/API/Database·ADR-006/009/011/012와 조사 결과를 대조했다. 새 승인 설계 결정이 없어 Owner 계약을 바꾸지 않고, `task-list.md`에 Phase A 진행과 남은 실차량 E2E를 구분하는 최소 기록만 추가한다. 과거 보고의 시점별 구현 설명은 보존하고 최신 코드와 차이는 이 보고서 §4~11에 기록했다. 별도 ADR은 필요하지 않다. Phase 9 전체 감사·TASK-908 완료 처리는 남아 있다. commit/add/push를 수행하지 않는다.

### 완료 질문 Q1~Q14 대조

| 질문 | 답 / 위치 |
| --- | --- |
| Q1 직접 미지원 원인 | 최신 후보 검증 뒤 validator 무조건 throw, 목적 alightability 없음. TAGO/서울 moving 등 선행 차단도 존재 (§4) |
| Q2 현재 공식 하차 분류 근거 | 현재 연결 없음. 확인 자료로 ALLOWED를 만들 수 없으며 UNKNOWN (§6) |
| Q3 서울 이동 의미 | 공식 운행중/도착 정의+과거 한 표본 일부, 이번0건. 일반 stId/stOrd 전환 의미 미확정 (§7) |
| Q4 TAGO GPS 없는 gap2/1 | index 참고 가능성과 제품 지원을 구분. 현재 하차 지원 확정 불가 (§8) |
| Q5 같은 차량/운행 추적 범위 | 짧은 과거 문자열 연속성만 일부. 물리 차량·같은 운행 장기 보장 없음 (§9) |
| Q6 회차/반복/lap 오연결 | 알려진 충돌 보류는 구현, 숨은 lap 검출 보장 없음. 자동 연결 차단 유지 (§9) |
| Q7 실제/fixture 구분 | 실제는 과거 기록뿐, 이번 분석·테스트는 synthetic (§2·13·14) |
| Q8 호출 횟수/기간/중단 | 이번 양쪽0건, live 기간 없음, 신규 승인 없어 미실행 (§14) |
| Q9 좁은 지원 범위 | 기존 번호 확인 후보와 검증된 참고 정보만. 새 실운영 하차 지원 구간 없음 (§14) |
| Q10 지금 제한 해제 | 불가. ALLOWED source·이동 의미·identity/traversal/freshness 근거 부족 (§15) |
| Q11 최소 구현/파일 | 근거 확보 뒤 metadata→validator 증거 전달, pair/meaning/운행 경계의 좁은 보강 검토. 지금 production 변경 없음 (§11) |
| Q12 E2E 전 추가 근거 | occurrence 이용 계약·현재 metadata·같은 차량 전환/시각·운행 경계·현장 번호 대응 (§16) |
| Q13 기존 기능 영향 | Production/Dependency/API/DB/Alarm/Notification/Flutter 변경·실행 없음 (§1·17) |
| Q14 TASK-908 | **`[ ]` 유지**, TASK-901~907·710/711 상태 보존 (§1·17) |

## 18. Phase B: 제한 실측 사전검증 — 신규 라이브 미실행

### 18.1 환경과 이번 범위

- 점검일: **2026-10-21, Asia/Seoul**. branch `main`, HEAD `a9710a0` (`docs: TASK-908 Provider 실측 사전 조사 및 검증 도구 준비`)
- 시작 시 `git branch --show-current`, `git status --short`, `git log -3 --oneline` 확인. 기존 변경은 위 Python 도구 2개의 Untracked 상태뿐이며 보존했다
- **신규 승인 없음 / 실제 계정 잔여 Quota 미확인 / 현재 프로세스의 `PUBLIC_DATA_SERVICE_KEY` 환경변수 없음**. Credential 값·`.env`는 읽지 않았다
- 이번 Phase B의 **서울 0회, TAGO 0회, metadata 0회, FCM 0회**. 실제 호출 시작·종료 시각과 live 소요 기간은 해당 없음이다. 정상·실패 HTTP 응답도 각각 0건이며 인증/TLS/Quota 오류를 경험한 것으로 기록하지 않는다
- Backend/Application/Scheduler/DB/Flutter/Alarm/FCM을 실행하거나 수정하지 않았다. Production·Dependency·API·Schema·ADR·Owner 설계·devlog·`.gitignore` 변경 없음
- TASK-901~907 `[x]`, TASK-908 `[ ]`, TASK-710/711 기존 상태 유지. 이번 사전검증은 Phase B 실측 완료·실제 탑승 E2E·Phase 9 종료 감사가 아니다

### 18.2 도구 실행 계약 대조와 최소 보강

두 Python 소스를 읽고 §12~13과 대조했다. CLI는 기본 dry-run, `--replay`, `--live`, `--approval-file`이며 `--live`와 `--replay`는 함께 사용할 수 없다. 기본 실행은 승인 파일/환경 Credential을 읽거나 transport를 만들지 않는다. 승인 없는 live 거절은 mock 기반 테스트로 검증했고 실제 `--live` 실행은 하지 않았다.

| 안전 경계 | 소스·오프라인 검증 결과 |
| --- | --- |
| 승인 | `--live`와 승인 파일 필수, 신규 인간 승인 참조·timezone 포함 기간·Provider/Route/operation/city 정확 일치·Quota 확인 true·유한한 수치 상한 검사. 파일 내용만으로 인간 승인·실제 Quota를 인증하지는 않음 |
| 요청 예산 | 서울12/TAGO6/전체18 이하, 6라운드·150초 이하. transport 전에 시도 선계수, 실패/timeout도 포함. 다음 요청 timeout+cleanup 예산까지 승인 기간/전체 deadline에 확보하지 못하면 중단 |
| 간격 | 모든 HTTP 시작 간 최소2초, **각 Provider의 실제 라운드 시작 간 최소20초**. 대기 후에도 요청 직전 예산 재검사 |
| transport | connect2초, request 최대5초, subprocess timeout은 request+1초로 최대6초. curl `--disable`, retry0, redirect 추적0, HTTPS만, proxy 우회. TLS 실패 시 HTTP·별칭 endpoint fallback 없음 |
| 범위 | 고정 §12 노선/operation만, 서울 roster→선정 차량 detail→TAGO 순서. TAGO page1/1000 고정. metadata/추가 page/다른 노선/다른 차량 대체 없음 |
| 실패 | HTTP/Provider/Quota/parse/pagination/identity/필수 진행/시각·진행 모순에서 전체 호출 중단. 예상 밖 실행 오류도 상수 `INTERNAL_SAFETY_ERROR`로 중단 |
| 기록 | raw 응답·ID·전체 번호·GPS·인증 URL 저장 없음. 원본은 메모리 내 비교만, stdout 허용 필드와 실행별 HMAC 별칭만. curl URL은 stdin, argv·stderr·외부 예외 원문 출력 없음 |
| freshness | 서울 제공시각/수신시각 분리, age·역행·동일 source time 검사. detail이 roster보다 오래되면 중단하는 연구용 보수 정책 유지. TAGO 원본 시각 합성 없음 |

기존 39개 테스트는 수정 전에 전부 통과했다. 추가 synthetic 회귀검사에서 다음 공백을 재현하고 로컬 도구만 보강했다. 이것은 실제 Provider 장애/관측 결과가 아니다.

1. **Provider별 라운드 간격:** 전체 라운드 기준 대기만으로는 서울의 요청 시간이 변할 때 TAGO 시작 간격이 synthetic 사례에서 14초까지 줄었다. 각 Provider 첫 HTTP의 실제 선계수 시점을 따로 보존하고 대기하도록 수정했다. 대기+다음 요청 예산이 기간 안에 들어가지 않으면 대기/호출 전에 중단한다
2. **TAGO 전체 item 순번 검증:** 선택되지 않은 item의 `nodeord=0`/음수를 놓칠 수 있었다. 목록 전체의 양수가 아닌 순번을 검사해 `MISSING_OR_INVALID_PROGRESS`로 전체 실행을 중단한다. 기존 null/누락 검사는 유지한다
3. **내부 예외 보호:** 예상 밖 transport 예외가 외부 메시지를 포함한 traceback으로 전파될 수 있었다. 요청을 이미 선계수한 상태로 상수 오류 원장·중단 요약을 남기고 추가 호출하지 않도록 보강했다. CLI의 예상 밖 오류도 원문을 출력하지 않는다

회귀 테스트 5개를 추가해 **44개 전부 통과, failure/error/skip 0**. 새 Dependency는 없다. 모든 transport는 mock/fake이며 실제 호출은 0건이다. 원본 Python 파일은 문서에 복제하지 않으며 삭제하지 않았다.

### 18.3 승인·Quota·Credential의 남은 선행 조건

이 사용자 요청은 **사전 점검·준비 승인**이며 새 라이브 승인이 아니다. 과거 TASK-901의 Quota 생략 예외도 재사용하지 않는다. 실제 계정 상태를 읽는 연결 도구나 현재 잔여량 증거를 확보하지 못했으므로 아래를 미확인으로 유지한다. 공개 Portal의 신청 가능 트래픽과 과거 성공 응답은 대체 증거가 아니다.

| 서비스별 계정 확인 항목 | 서울 버스위치 / TAGO 위치 |
| --- | --- |
| 현재 활용승인·서비스 권한 | 양쪽 미확인 |
| 계정 일일 한도·실제 사용량·잔여량 | 양쪽 미확인 |
| 같은 Key의 서비스/operation별 집계 단위 | 미확인 |
| reset 기준·시각 | 미확인 |
| 다른 프로세스/사용자와 공유 여부·병행 소비 | 미확인. 이번 작업에서 Backend를 실행하지 않은 사실만으로 다른 소비자가 없다고 보장 못함 |

라이브를 재개하려면 다음을 함께 확인해야 한다. **승인 파일은 작성하지 않았고 `quotaConfirmed=true`도 설정하지 않았다.**

- 신규 인간 승인: §12의 정확한 Provider·Route·endpoint, 서울 roster6/detail6 이하 총12·TAGO6 이하·전체18 이하와 150초 이하, 6라운드 이하·20초/2초 최소 간격·connect2/request5/cleanup 포함6초·retry0·전체 중단 정책
- 실제 계정 확인 결과: 두 서비스의 승인 상태·한도·사용량·잔여량·공유 집계·reset·병행 소비 위험을 확인한 근거와 확인 시각. Key 문자열은 공유하지 않는다
- timezone을 포함한 실행 시작/종료 허용 window. 최대치만으로 실행 시각을 임의 확정하지 않는다
- 비식별 stdout 요약 정책의 명시적 승인. 파일 보관을 원하면 저장소 밖 정확한 임시 경로와 삭제 시점을 추가 승인해야 한다. 경로/삭제 정책이 없으면 파일로 redirect하지 않는다
- 실행 프로세스에 기존 Key를 안전하게 주입. 승인 파일 생성·Credential 존재만으로 라이브 승인이 생기지 않는다

현재 환경변수가 없으므로 사용자가 실행할 로컬 zsh의 비표시 입력 절차는 아래와 같다. **이번에 이 입력·export를 실행하지 않았다.** Key를 command argument·채팅·보고서에 붙이지 않고, `.env` 자동 로딩을 추가하지 않는다.

```sh
set +x
read -rs 'PUBLIC_DATA_SERVICE_KEY?기존 인증키 입력(표시 안 됨): '
print
export PUBLIC_DATA_SERVICE_KEY
# 승인·Quota·window·기록 정책 확인 및 승인 파일 검토 후에만:
# python3 -B tools/provider_evidence_probe.py --live --approval-file /private/tmp/task908-approval.json
# 실행·분석 후 해당 shell에서 unset PUBLIC_DATA_SERVICE_KEY
```

### 18.4 이번 실제 관측과 한계

| 항목 | SEOUL_BUS 7016 | TAGO 수원7000 |
| --- | --- | --- |
| HTTP 시도 / 정상 / 실패 | **0 / 0 / 0** | **0 / 0 / 0** |
| live 시작·종료·관측 기간 | 해당 없음 | 해당 없음 |
| 미실행 이유 | 신규 승인·계정 Quota 증거·환경 Credential 미확보 | 동일 |
| identity·차량 소실/재등장 | 신규 관측 없음 | 신규 관측 없음 |
| roster/detail ID·번호·시간 대응 | 미관측, 일치/불일치 판단 불가 | 별도 detail 없음 |
| 진행 변화 | stId/stOrd/stopFlag/sectOrd/sectionId 미관측 | nodeid/nodeord·번호 변화 미관측 |
| pagination·필수 필드 | 미관측 | pageNo/numOfRows/totalCount/item 수·nodeid/nodeord 누락 재현 여부 모두 미확인 |
| 제공시각·수신시각 | 실제 dataTm/수신 원장 없음. 두 시각의 구분 로직만 synthetic 검사 | 이번 원장 없음. 기존 명세의 원본 시각 부재 한계는 유지 |

실행 전 보류이므로 실패한 HTTP 시도 번호·추가 호출은 해당 없음/0회다. 실제 응답을 수집하지 않아 신규 관측 원장 파일을 만들지 않았다. “변화 없음”은 같은 차량을 반복 관측한 결과에만 쓰며 이번에는 **미관측**이다. 과거 2026-10-16 TAGO nodeid 문제나 2026-09-09 서울 flag 전환을 이번 재현으로 합치지 않는다.

현재 metadata API/DB 조회가 없으므로 과거 metadata와 현재 실시간 진행의 exact 대응을 주장하지 않는다. 실제 탑승·물리 번호 대조·실제 정차/하차를 수행하지 않았으며, 제한 API 관측을 하더라도 이 현장 검증을 대체하지 않는다.

### 18.5 Backend 요구와 이번 증거 비교

§4~11의 코드 분석과 현재 아래 7개 클래스를 다시 대조했다. **모든 행에서 이번 실제 Provider 증거는 0건**이며, 코드를 읽거나 synthetic 도구 테스트를 통과한 것을 실측 증거로 승격하지 않는다.

| 조건 / 현재 요구·담당 코드 | 이번 확보 근거 | 미검증 입력과 실패 위험 | 추가 검증 |
| --- | --- | --- | --- |
| candidate identity / `BoardingCandidateService` | 원래 reference·최신 roster/detail 검증 코드 재확인 | 실제 ID 충돌·소실·재사용, 다른 운행 오연결 | ID/번호 연속 관측·기관 ID 수명 정책 필요 |
| vehicleNumber / Service·`BoardingVehicleTracker` | 서울 요청 ID/응답 ID·roster/detail 전체 번호 검사 재확인 | 실제 pair/차체 번호 대응, 번호가 같은 다른 ID | pair 실측과 현장 직접 대조 필요 |
| providerDataTime / `BoardingCandidateMapper` | 서울 strict KST 파싱, TAGO null 코드 확인 | 실제 source timestamp 반복·역행·정확한 생성 의미 | 서울 dataTm/기관 설명, TAGO source age 근거 필요 |
| observedAt / Service·Mapper | 성공 응답 수신 직후 시각 전달 확인 | 요청 시작/물리 도착과 혼동, 지연 원인 미상 | 실제 요청 시작·수신·제공·현장 시각 분리 필요 |
| source freshness / `BoardingProgressEvaluator`·Tracker | 60초·future/역행·중복·PT2M 공백 검사 확인 | TAGO 원본 age 부재, 제공시각이 실제 발생시각인지 불명 | freshness·중복·공백 실측 필요 |
| directionContext / Tracker·Evaluator | adapter null, 이전 문맥과 일치 검사 | null 일치가 같은 방향/운행을 입증하지 않음 | 방향·trip·turn 경계 독립 근거 필요 |
| traversalAmbiguous / Tracker·Evaluator | adapter false, 명시 ambiguity는 보류 | false를 lap 검증 완료로 오인할 위험 | 반복 Stop·분기·회차·lap 별도 검증 필요 |
| orderOnlyVerified / Tracker·Evaluator | false, exact ID/order 대응 우선 | TAGO 누락 nodeid의 order만으로 가짜 Stop 합성 위험 | 노선별 order 체계 독립 검증 전 비활성 유지 |
| meaning / Tracker·Evaluator | 서울 exact flag1만 DIRECT_ARRIVAL, 그 외 UNVERIFIED | flag0을 출발/접근으로 해석, TAGO order를 도착으로 오인 | moving 전환 관측과 기관 의미 근거 필요 |
| targetAlightAvailability / `BoardingSessionStartValidator` | 최신 후보 검증 후 항상 미지원 throw, ALLOWED 공급 경로 없음 | 일반 Stop/진행 성공을 목적 하차 허용으로 승격할 위험 | occurrence별 이용 허용 source·유효 범위·분류 근거 먼저 확보 |
| metadata / `BoardingTrackingMetadata` | Route/city·전체 occurrence ID/Stop/order snapshot 비교 | 저장 구조 일치가 upstream 현재 운행·이용 정책 증명은 아님 | 최신 source 대응 및 분류 연결 검증 필요 |
| occurrence gap2/1 / Evaluator | targetIndex-progressIndex, 검증 meaning·ALLOWED·freshness 선행 | 숫자 계산 성공을 실제 적시 준비 알림으로 오인 | 모든 선행 근거 확보 후 현장 gap/표시 지연 검증 필요 |
| runtime / `BoardingTrackingRuntime` | commit된 revision/checkpoint·freshness 일치 때만 READY | gate를 열어도 Provider 의미가 새로 입증되지는 않음 | 공급 근거 검증·commit/gate 영향 테스트 후 판단 |

Provider 연속 관측이 성공하더라도 **ALLOWED는 자동 공급되지 않는다**. 이번 연구용 `detail >= roster` 시각 정책을 기존 운영 계약으로 승격하지 않는다. 기존 반복 Stop 시작 검증의 보수적 차단과 Domain의 관측 쌍 기반 계약 차이는 §9에 기록된 기존 지원 경계이며 이번에 설계를 변경하지 않는다.

### 18.6 증거 등급·지원 판정과 다음 최소 작업

| 판단 대상 | TAGO | SEOUL_BUS |
| --- | --- | --- |
| 이번 신규 실제 응답 가용성 | **UNAVAILABLE** | **UNAVAILABLE** |
| 이번 identity/progress/freshness 의미 검증 | **UNVERIFIED** | **UNVERIFIED** |
| 과거 제한 표본의 ID/진행 일부 | PARTIALLY_VERIFIED, §2·10의 과거 범위만 | PARTIALLY_VERIFIED, 과거 다른 노선 표본을 7016로 확대하지 않음 |
| 모든 item의 nodeid가 항상 유효하다는 가정 | 과거 결과에 의해 CONTRADICTED, 이번 재현 아님 | 해당 없음 |
| 목적 ALLOWED·반복 occurrence·회차/lap·물리 하차 | UNVERIFIED | UNVERIFIED |
| 현재 실제 하차 Session/gap2/1 제품 지원 | **UNSUPPORTED** | **UNSUPPORTED** |
| 향후 기술 가능성 | UNDETERMINED | CONDITIONAL_NOT_ENABLED |

이번에 VERIFIED로 승격한 Provider 항목과 `SUPPORTED_FOR_DEFINED_SCOPE`인 새 하차 지원 범위는 없다. 승인/Quota 부재를 Provider 자체 기능 부재의 증거로 해석하지 않는다. `BOARDING_TRACKING_UNSUPPORTED`를 유지한다.

다음 순서는 §15~16을 유지한다. 우선 기관의 **노선×방향×occurrence별 정차/하차 허용 정의·코드표·유효기간/예외**를 확보하고, 서울 moving 기준점/전환과 dataTm 생성 의미, 양 Provider identity 수명·노선 변경·회차 정책을 확인한다. 기관에 연락하는 작업은 이번에 수행하지 않았다. 승인·계정 Quota·환경이 갖춰지면 §12의 좁은 실측만 수행해 변화/실패/미관측을 분리한다. 회차·lap까지 확인하지 못하면 별도 검증으로 남긴다.

Backend 최소 변경 후보는 **근거 확보 후** metadata snapshot→validator의 검증된 하차 분류 전달, 근거가 확인된 범위의 pair 시각·moving meaning·traversal 입력 공급 검토다. 저장/만료/정책·Schema 필요 여부는 별도 개발자 판단 사항이며 지금 구현하지 않는다. 이후 UNKNOWN/PROHIBITED·근거 만료·방향 불일치·source 반복/역행·소실·lap·commit/gate 테스트를 수행한 뒤 명시적 지원 판단과 실제 버스/iPhone E2E로 진행한다. E2E는 현장 번호/정차/하차, Event/전송/기기 표시·tap 시각, foreground/background/terminated, 취소/재시작·기존 Alarm 병행과 TASK-710/711 검증을 포함한다.

### 18.7 이번 요청의 완료 질문 Q1~Q17

| 질문 | 답 |
| --- | --- |
| Q1 실제 HTTP 수 | 서울0 / TAGO0 |
| Q2 정상·실패 종류 | 양쪽 정상0·실패0. 실행 전 보류이며 HTTP/auth/TLS 실패로 분류하지 않음 |
| Q3 서울 동일 vehId/plainNo 연속 | 미관측 |
| Q4 서울 stId/stOrd/stopFlag 변화 | 미관측. 변화 없음으로 단정 불가 |
| Q5 flag0 이동 의미 | 이번 추가 검증 없음. §3·7의 기존 문서상 운행 중 정의만, 출발/접근 의미 UNVERIFIED |
| Q6 roster/detail ID·번호 일치 | 이번 미관측, 검사 코드·인공 테스트만 확인 |
| Q7 TAGO 누락 재발 | 요청0이므로 미확인. 2026-10-16의 문제는 과거 근거로 보존 |
| Q8 TAGO 동일 번호 진행 변화 | 이번 미관측 |
| Q9 제공/수신 시각 구분 | 구분 로직·synthetic 검사는 수행. 실제 시각쌍 분석은 미실행, TAGO source time 합성 없음 |
| Q10 반복 Stop·회차·lap 입증 | 불가, UNVERIFIED |
| Q11 목적 occurrence 하차 허용 | 입증 불가. 현재 ALLOWED 공급 source 없음 |
| Q12 GPS 없이 gap2/1 활성화 | 안전하다고 판단할 근거 부족, 비활성 유지 |
| Q13 미지원 유지 | `BOARDING_TRACKING_UNSUPPORTED` 유지, Production 변경 없음 |
| Q14 우선 공백 | occurrence별 하차 허용 근거와 공급 경로, moving/운행 연속성·freshness·traversal 증거 |
| Q15 E2E 전 남은 작업 | 신규 승인·계정 Quota·Credential, 제한 실측/공식 의미·최신 metadata 대응, 근거 공급 최소 구현/검증과 지원 판단, 현장/FCM/iPhone 준비 |
| Q16 보호 위반 | 이번 실제 HTTP·raw 수집/저장·Credential 값 읽기/출력0. 확인한 실행 범위에서 위반 없음. 계정 잔여량이나 live 안전성 검증 완료 주장은 아님 |
| Q17 두 Python Git 추가 | 추가하지 않음. 둘 다 Untracked로 보존, 사용자 후속 정리 대상 |

### 18.8 검증·문서·Git 상태

- `python3 -B -m unittest discover -s tools -p 'test_provider_evidence_probe.py' -v`: **44개 통과**, 모두 offline synthetic/mock/fake. 기본 dry-run은 `totalAttempts=0`, `supportEnabled=false`
- 승인 scope/Quota false/기간/시간·횟수·간격/실패 후 추가 호출0, retry0/redirect0/HTTPS, 원장·argv 비식별 및 외부 예외 원문 비노출 회귀 검사 수행. 실제 endpoint·권한·TLS 성공은 검증하지 않음
- 보고서의 Git 미포함 도구 Markdown 링크 제거, 실행 명령·연구 정책·Phase A의 39개 테스트 기록 보존. 이번 Phase B는 44개로 별도 기록
- 관련 요구사항·Architecture·Domain·API·ADR-006/011/012와 코드 대조. 새 설계 결정이 없어 Owner 문서 변경 없음. `task-list.md`에는 이번 준비 범위만 추가하고 TASK-908 `[ ]` 유지
- 문서 상대 파일 참조·Task checkbox 보존, Python AST/별도 공백 검사, `git diff --check`와 최종 `git status --short` 검사 수행. Backend/Flutter 전체 suite는 Production 변경이 없어 미실행
- **git add/commit/push·브랜치/Worktree 생성·checkout/switch/reset/clean/stash 미실행**. 두 도구는 삭제/스테이징하지 않는다
- 향후 문서만 커밋할 때의 제안 제목: `docs: TASK-908 Phase B 실측 사전검증 및 미실행 조건 기록`. 실제 실측 결과를 확보한 것처럼 표현하지 않는다

## 19. Phase B 실행 요청 — 계정 Quota 확인 불가로 live 보류 (2026-10-27)

### 19.1 이번 승인과 실행 전 확인 결과

- 사용자가 이번 요청에서 **실제 Provider 호출을 승인**했다. §18의 신규 승인 부재는 과거 기록이며 이번 보류 사유가 아니다
- branch `main`, 기준 HEAD `f6b2bdd0f6eb64462758d6f766d333505ac13ca9`. 시작 시 변경은 기존 Python 도구 2개의 Untracked 상태뿐이었다. 도구·테스트 소스와 approval-file 계약을 확인했으며 수정하지 않았다
- 승인 범위는 서울 7016 `busRouteId=100100447`의 roster/detail 합계 최대12회, TAGO 수원7000 `routeId=GGB200000112`, `cityCode=31010` 위치 최대6회다. 전체18 HTTP 시도·150초·Provider별6라운드 이하, 같은 Provider 라운드 시작 간20초·전체 HTTP 시작 간2초 이상, 자동 retry·추가 호출 금지를 유지했다
- `python3 -m unittest discover -s tools -p 'test_provider_evidence_probe.py' -v`: **44개 통과, failure/error/skip 0**. 기존 mock/fake/synthetic 검증이며 실제 API 호출은 없다
- `python3 -B tools/provider_evidence_probe.py`: 기존 도구를 기본 모드로 실행해 `DRY_RUN`, `totalAttempts=0`, `supportEnabled=false`를 확인했다. 이를 live 실행 성공으로 세지 않는다
- **17:07:16 KST** Credential 존재 점검: 현재 프로세스의 `PUBLIC_DATA_SERVICE_KEY`는 없고, 로컬 `.env`의 해당 값은 비어 있지 않았다. 값은 메모리에서 존재 여부만 확인하고 출력·파일 복제·Git 기록하지 않았다. `.env` 전체를 shell로 실행하거나 다른 설정을 주입하지 않았다. 실제 인증 유효성·서비스별 권한은 미검증이다
- 서울/TAGO 계정 승인·잔여량을 읽는 전용 연결 도구가 없어 기존 Chrome 공공데이터포털 탭을 확인했다. 기존 상세 화면은 데이터명·서비스·상세기능이 비어 있어 표시된 일반적인 `승인` 문구를 두 서비스의 승인 증거로 인정하지 않았다. 화면에 표시된 마이페이지 주소로 이동하자 **공공데이터포털 로그인·보안문자 화면으로 전환**됐다. 현재 세션에서는 계정 확인을 진행할 수 없었고 로그인·보안문자를 대신 수행하지 않았다
- 위 계정 화면 확인은 **17:07~17:09 KST** 작업 중 수행했다. Portal HTML 열람은 아래 버스 데이터 API 시도 횟수에 포함하지 않는다. 공개 서비스의 신청 가능 트래픽이나 과거 성공 응답으로 실제 계정 잔여량을 대체하지 않았다

| live 선행 조건 | 이번 확인 결과 |
| --- | --- |
| 사용자 실측 의사·호출 상한 | 이번 요청으로 확인 |
| Credential 존재 | 로컬 `.env`에 비어 있지 않은 값 확인. 실행 환경 주입 전이며 실제 유효성 미확인 |
| 서울 버스위치 서비스 현재 활용승인·operation 권한 | 로그인 필요, 확인 불가 |
| TAGO 버스위치 서비스 현재 활용승인·operation 권한 | 로그인 필요, 확인 불가 |
| 각 서비스 오늘 한도·실제 사용량·잔여량 | 확인 불가. 서울12/TAGO6 호출을 감당하는지 판정 불가 |
| Key/서비스/operation별 공유 집계·reset 기준/시각·병행 소비 | 확인 불가 |
| approval-file·live 실행 | **승인 파일 미작성, 실제 실행용 `quotaConfirmed=true` 설정 없음, `--live` 미실행** |

오프라인 테스트의 synthetic 승인 fixture는 실제 계정 확인 증거가 아니다. 도구 계약을 우회하거나 인증/Quota 확인용 버스 API를 별도로 호출하지 않았다. live의 시작·종료 window와 150초 예산은 가동하지 않았고, 실제 실행 시작·종료 시각도 없다. 계정 확인 후 실행 시점에 유효한 window로 계약을 충족해야 한다.

### 19.2 실제 호출과 분석 가능한 근거

| 항목 | SEOUL_BUS 7016 | TAGO 수원7000 |
| --- | --- | --- |
| 실제 HTTP 시도 / 성공 / 실패 | **0 / 0 / 0** | **0 / 0 / 0** |
| 실제 관측 기간·차량 표본 | 없음 | 없음 |
| 동일 차량 식별·진행 변화 | vehId/plainNo 유지, roster/detail 일치, stId/stOrd 진행, stopFlag 0/1 전환 모두 **미관측** | 같은 차량번호의 nodeid/nodeord 진행 모두 **미관측** |
| 필드·pagination | 신규 응답 없음 | nodeid/nodeord 누락 및 pageNo/numOfRows/totalCount/item 수 정합성 **미확인** |
| 시각·신선도 | dataTm 갱신과 실제 수신시각 차 **분석 불가** | 신규 원본 응답 없음. 기존 원본 생성시각 부재 한계를 해소한 근거도 없음 |
| 이번 발견한 Provider 장애 | 없음: 호출 전 보류이므로 정상/인증 실패/Quota 소진/TLS 실패 어느 쪽도 입증 안 됨 | 동일 |

metadata API·FCM 호출도 각각0회다. 비식별 관측 원장·raw 응답 파일을 생성하지 않았다. **미관측을 변화 없음으로 표현하지 않으며**, 과거 TAGO 필드 누락이나 과거 서울 진행 변화를 이번 재현으로 계산하지 않는다. 이번 계정 로그인 필요는 실행 환경의 차단 조건이며 Provider 데이터 품질 문제의 증거가 아니다.

### 19.3 사용자에게 필요한 조치와 후속 최소 범위

1. 브라우저에 열린 공공데이터포털에서 직접 로그인한 뒤 계정 화면 확인을 재개할 수 있게 한다. Key·비밀번호·보안문자를 채팅이나 문서에 전달할 필요는 없다
2. 서울 버스위치와 TAGO 버스위치 각각의 현재 활용승인·상세기능 권한, 오늘 한도·사용량·잔여량과 확인 시각, Key/서비스/operation별 집계 단위·초기화 기준/시각을 확인한다. 병행 소비 여부도 확인해 서울12/TAGO6·전체18회 예산을 확보한다. Portal에서 실제 사용량을 제공하지 않으면 기관/계정 사용량 근거가 필요하며 미확인을 true로 바꾸지 않는다
3. 위 확인 후 기존 Key를 실행 프로세스 환경변수로만 안전하게 주입하고, 이번 승인 상한·유효 실행 window·비식별 stdout 정책을 반영한 approval-file로 기존 도구를 실행한다. 별도 관측 파일 보관 승인 없이 stdout을 파일로 redirect하지 않는 기존 계약을 유지한다

이번에 새로 확보한 Provider 응답이 없으므로 하차 가능 정류장 분류, 서울 이동 reference 의미·TAGO 원본 신선도, 차량 물리 동일성·방향·회차/lap·최신 metadata exact 대응을 검증된 것으로 바꾸지 않는다. `BOARDING_TRACKING_UNSUPPORTED` 유지이며 실제 탑승·Backend/Flutter/FCM E2E도 미실행이다.

**다음 Backend 수정에 이번 실행이 새로 요구하는 변경은 없다.** 기존 최소 후보(§11·18.6)는 근거 확보 후 metadata snapshot→validator에 검증된 occurrence 하차 분류/근거를 전달하고, 검증된 범위에서만 roster/detail pair·시각·진행 meaning을 공급하는 경로다. 현재 증거로 UNKNOWN→ALLOWED, 이동 meaning 승격, order-only 허용, 회차/lap 안전성 또는 지원 제한 해제를 정당화하지 않는다.

### 19.4 문서·Git·검증 범위

- 이번 변경은 이 절과 문서 상단의 시점 안내, `task-list.md`의 진행 기록뿐이다. 기존 Phase A/사전검증 기록은 보존하고 **TASK-908 `[ ]`** 및 다른 Task checkbox를 유지한다
- Production·설정·Dependency·API·DB·Owner 설계·ADR·devlog 변경 없음. Backend/Flutter/FCM 실행 없음. Provider 실측이나 Phase 9 전체 감사·실차 E2E를 완료로 보고하지 않는다
- branch/Worktree 생성·Git add/commit/push 없음. 두 Python 도구는 수정·스테이징하지 않고 Untracked로 유지한다
- `git diff --check`, 문서 diff·status 검토, 전체 Task checkbox 보존, 두 Python 파일 SHA-256 불변·AST 검사, 실제 Key 원문/인코딩 변형의 문서 diff 비포함 검사를 통과했다. 테스트 실행으로 생긴 두 `.pyc`와 빈 `__pycache__`만 제거했다

## 20. Phase B 실제 실행 — 첫 서울 요청 timeout으로 전체 중단 (2026-10-27)

### 20.1 계정 확인과 이번 요청에 한정된 Quota 예외

§19 이후 사용자가 로그인한 포털에서 계정 상세를 확인했다. 이는 공개 서비스의 일반 신청 가능량이 아닌 **현재 계정의 승인 상세 화면** 확인이다. 두 서비스 모두 개발계정 승인, 활용기간 `2026-09-09 ~ 2028-09-09`로 표시됐다.

| 서비스 | 이번 직접 확인한 상세기능·일일 트래픽 | 확인하지 못한 내용 |
| --- | --- | --- |
| 서울특별시_버스위치정보조회 서비스 | `getBusPosByRtidList` 1,000회, `getBusPosByVehIdItem` 1,000회 | 오늘 사용량·실제 잔여량·reset 기준/시각·병행 소비 |
| 국토교통부_(TAGO)_버스위치정보 | `getRouteAcctoBusLcList` 10,000회 | 동일 |

사용자는 API별 별도 집계라고 설명했고, 후속 답변에서 **현재 잔여량은 확인 불가**, 오늘 사용하지 않아 전량 남았을 것이라고 설명했다. 후자는 사용자의 미사용 설명·잔여량 추정이며 실제 계정 잔여량 측정값이 아니다. 서울과 TAGO의 실측 대상 operation이 승인 목록에 있는 것은 확인했지만, quota 잔여량을 검증 완료로 승격하지 않는다.

그 뒤 사용자가 **“`quotaConfirmed=true` 설정하고 할 수 있는거 모두 진행해”**라고 명시했다. 이번 실행에 한해 이전의 잔여량 확인 선행 조건을 생략하는 **사용자 승인 예외**로 반영했다. approval-file의 `quotaConfirmed=true`는 이 예외 승인에 따른 실행 허용 표시이며 실제 잔여량 확인 증거가 아니다. `approvalReference`에 예외 경위, 별도 `quotaEvidence`에 `remainingQuotaStatus=UNVERIFIED`와 명시적 사용자 예외를 함께 기록했다. 향후 실행의 일반 Quota 확인 계약을 완화하거나 과거 확인 기록을 변경한 것이 아니다.

다른 실행 한도·operation·노선·실패 시 전체 중단 계약은 그대로 적용했다. 승인 파일은 저장소 밖 `/private/tmp`에 실행 직전 생성했으며 Key를 포함하지 않았다. UTC 허용 window는 **08:16:28.514851~08:18:58.514851**(KST **17:16:28.514851~17:18:58.514851**)로 150초였다. 실행 종료 시 승인 파일을 제거했다. 기존 `.env`의 해당 Key만 메모리에서 읽어 실행 프로세스 환경변수로 주입했으며, 도구 소스·테스트는 수정하지 않았다. 비식별 stdout만 사용하고 별도 응답/원장 파일은 저장하지 않았다.

### 20.2 실제 실행과 비식별 관측 원장

live 직전 `python3 -B -m unittest discover -s tools -p 'test_provider_evidence_probe.py' -v`를 다시 실행해 **44개 통과, failure/error/skip 0**을 확인했다. 기존 두 Python 소스의 SHA-256 불변과 `main` 유지도 확인했다. 기존 CLI 진입점에 `--live --approval-file`을 전달해 실행했다. HTTP 전송은 도구의 기존 `fetch`/curl 그대로이며 mock/fake나 replay가 아니다.

| 항목 | 실제 기록 |
| --- | --- |
| Provider / operation / routeAlias | `SEOUL_BUS` / `getBusPosByRtidList` / `SEOUL_ROUTE_A` (승인된 7016) |
| 요청 시작 | **2026-10-27T08:16:28.519677Z** / **17:16:28.519677 KST** |
| transport 완료 기록 | **2026-10-27T08:16:30.555193Z** / **17:16:30.555193 KST** |
| `elapsedMs` | **2035.594** |
| `httpStatus` / `providerResultCode` / `itemCount` | 모두 `null` |
| `errorKind` / `relation` / `provenance` | `TIMEOUT` / `UNOBSERVED` / `LIVE_OBSERVATION` |
| 차량·정류장·진행·제공시각 | 별칭과 진행 모두 `null`, 실제 데이터 확보 없음 |
| 실행 요약 | `mode=LIVE`, `totalAttempts=1`, `stopReason=TIMEOUT`, `supportEnabled=false`, `evidence=UNVERIFIED` |

원장의 `responseReceivedAt`은 실패 시에도 transport가 끝난 시각을 담는 도구 필드명이다. **이번에는 정상 응답 수신시각이 아니며**, 제공시각/원본 데이터 age/실제 정차와의 지연 분석에 사용할 수 없다. 요청 시작~실패까지 약2.036초로 실행을 마쳤으며 허용 window 전체를 사용하지 않았다.

| 시도·결과 | SEOUL_BUS | TAGO |
| --- | --- | --- |
| HTTP 시도 선계수 | **1** (roster1/detail0) | **0** |
| HTTP 200·정상 데이터 확보 | **0** | **0** |
| 실패 | **1: TIMEOUT** | **0: 미호출** |
| 추가 라운드 / retry / 대체 endpoint | **0 / 0 / 0** | **0 / 0 / 0** |

connect timeout2초·request timeout5초·retry0·redirect0·고정 HTTPS 계약으로 첫 서울 요청이 실패했다. 도구가 원문 transport 오류를 출력하지 않으므로 DNS/접속/TLS/서버 중 어느 단계가 원인인지 이번 원장만으로 구분할 수 없다. 서울 서버가 요청을 수신했는지, 실제 계정 Quota가 차감됐는지도 알 수 없다. 약2초 실패를 특정 원인의 확정 증거로 쓰지 않는다. HTTP/Provider 인증·Quota 오류 코드를 받은 것도 아니다.

첫 실패에서 전체 실행을 중단하는 계약에 따라 detail·TAGO·추가 라운드를 호출하지 않았다. 남은 횟수는 추가 실행 허가나 실패 후 다른 Provider 계속 실행의 근거로 사용하지 않았다. HTTP·다른 경로 fallback, 추가 진단 호출도 없다. metadata API·Backend·Flutter·FCM·DB는 실행하지 않았다.

### 20.3 분석 결과·지원 경계·다음 최소 작업

- **서울:** vehId/plainNo 연속성, roster/detail 일치, stId/stOrd 변화, stopFlag 0/1 전환, dataTm 갱신·실제 수신시각 차 모두 미관측이다. 한 건의 transport 실패를 차량 소실/정상 empty/Provider 필드 문제로 해석하지 않는다
- **TAGO:** 호출0회이므로 nodeid/nodeord 누락 재발 여부, 같은 번호의 진행, pagination 정합성을 판단할 수 없다. 기존 원본 생성시각 부재 한계를 해소하는 새 근거도 없다
- **이번 새 문제:** 승인된 서울 HTTPS roster 요청의 timeout을 관측했다. 서울 API 전반 장애, HTTPS 미지원, 잘못된 경로, 인증 실패, Quota 소진 중 어느 것도 원인으로 확정하지 않는다. TAGO 장애를 관측한 것도 아니다
- **남는 근거:** 하차 가능 occurrence 분류·이용 허용 source, moving reference 의미·freshness, 최신 metadata exact 대응, 차량 물리 동일성·같은 방향/운행·회차/lap은 모두 여전히 미검증이다. 실제 현장/하차·iPhone E2E를 수행하지 않았으며 `BOARDING_TRACKING_UNSUPPORTED`와 TASK-908 `[ ]`를 유지한다
- **다음 Backend 최소 변경:** 이번 timeout만으로 Production 코드 변경을 정당화할 수 없다. 먼저 별도 실행에서 동일 endpoint의 접속/응답 가용성을 확인하고 제한 실측 데이터를 확보해야 한다. 이후 §11의 검증된 하차 분류 전달·pair/시각·진행 meaning 공급 경로만 근거에 맞춰 검토한다. timeout을 우회하기 위한 HTTP 전환·timeout 증대·자동 retry·지원 제한 해제는 이번 결과에 포함하지 않는다

### 20.4 기록과 검증 범위

- 로그인된 포털 마이페이지를 처음 열 때 **자동 화면 도구 출력에 인증키가 포함되는 실수**가 있었다. 사용자에게 알렸고, 이후 화면 출력은 인증 문자열을 제거한 뒤 표시했다. 키 원문·포털 화면 전체를 보고서나 Git 파일에 복제하지 않았다. 이 실수를 숨기고 이번 작업 전체를 Key 비노출 성공으로 보고하지 않는다
- 실제 API 전송의 Key는 환경변수·curl stdin으로만 사용했고 argv·원문 stderr·응답 본문을 출력하지 않았다. 위 표는 기존 허용 필드의 비식별 stdout 기록만 옮겼다
- 보고서와 `task-list.md` 진행 상태만 갱신했다. Production·Dependency·Owner 설계·ADR·devlog 및 도구 변경 없음. 브랜치/Worktree 생성·Git add/commit/push 없음
- 문서 diff·`git status`·`git diff --check`, 도구 SHA-256 불변·AST, 전체 Task checkbox 보존·staging 비어 있음, 보고서 파일 참조·Key 원문/인코딩 변형의 diff 비포함 검사를 통과했다. 추가 HTTP 검증은 수행하지 않았다

## 21. 서울 연결 원인 분리·공식 경로 반영 (2026-10-27)

### 21.1 설정·공식 명세 대조

| 구분 | base URL / 경로 / timeout |
| --- | --- |
| Backend `application.yml`·두 서울 Client | 기본 `http://ws.bus.go.kr/api/rest/buspos` + `getBusPosByRtidList` 또는 `getBusPosByVehIdItem`. connect `PT2S`, response `PT5S` |
| 로컬 도구 수정 전 | `https://ws.bus.go.kr/api/rest/buspos/` + 같은 긴 이름. connect2초, request 전체 최대5초, subprocess 정리 포함 최대6초 |
| 공식 활용가이드 | 상세기능 이름은 `getBusPosByRtidList`/`getBusPosByVehIdItem`, Call Back 경로는 **`getBusPosByRtid`/`getBusPosByVehId`**, scheme은 **HTTP** |
| 로컬 도구 수정 후 | 논리 operation/승인 scope는 유지하고 실제 path만 공식 Call Back의 짧은 이름으로 고정 매핑. **HTTPS 전용 유지**, HTTP fallback 없음. timeout 변경 없음 |

Backend는 JDK HttpClient connect timeout과 request factory read timeout을 설정하며, 도구의 curl `--max-time`은 연결까지 포함하는 전체 요청 제한이다. 두 response 제한을 완전히 동일한 타이머라고 간주하지 않는다. 현재 process environment·로컬 `.env`의 `SEOUL_BUS_BASE_URL` override는 없었다. Backend를 실행하지 않았으므로 다른 배포 환경의 실효 설정까지 검증한 것은 아니다.

이번에 [공공데이터포털 공개 설명](https://www.data.go.kr/data/15000332/openapi.do)과 [공식 20230530 가이드](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000003552007&fileDetailSn=1)를 확인했다. 공개 설명의 구간 조회 서비스 URL도 HTTP다. 가이드의 상세기능명과 실제 Call Back 경로를 구분해 확인했고, 예제 Key/query를 출력하지 않고 URL의 scheme/host/path만 추출했다. 첨부는 메모리에서만 읽었으며 SHA-256은 기존과 같은 `257e0778fb5036ae859fe67805a55361a5db1836a17083e5684517c713987f90`이다. 공식 자료에 HTTPS 지원을 확인할 근거는 이번에 확보하지 못했다.

### 21.2 인증 없는 DNS·TCP·HTTP 진단

진단 시작은 **17:26:22.327508 KST**다. Key·인증 query·차량 ID를 사용하지 않았다. 직접 socket으로 DNS/TCP를 분리했고, HTTP 진단은 curl 개인 설정 무시·proxy 우회·retry0·redirect0·connect2초·전체5초로 수행했다. 공식 가이드 다운로드의 HTTPS/TLS 검증은 성공했으므로 이 실행 환경의 모든 외부 HTTPS가 차단됐다는 증거는 아니다.

| 단계 | 이번 실제 결과 | 판정 한계 |
| --- | --- | --- |
| DNS `ws.bus.go.kr` | `175.193.202.140`, **44.197ms** | 이번 DNS 실패 아님 |
| TCP 80 | **10.599ms 연결 성공** | HTTP 연결 가능 |
| TCP 443 | **2003.119ms 후 TimeoutError** | 2초 안에 연결되지 않음. listener 미지원/목적지 또는 중간 네트워크 차단/일시 장애를 서로 구분할 수 없음 |
| TLS handshake | TCP443 연결 실패로 **미진입** | 인증서 오류·TLS 버전 오류·전역 HTTPS 미지원으로 확정 불가 |
| 연결 후 응답 지연 | HTTP80에서는 아래401 응답 확보, HTTPS는 연결 전 실패 | 이전 약2.036초 TIMEOUT의 동일 연결 단계 실패를 재현. 이전 단일 요청의 모든 내부 단계를 소급 입증한 것은 아님 |

아래는 **인증 없는 HTTP 진단**이며 실제 Provider 차량 데이터 호출 성공이 아니다. query parameter 없이 명시된 네 경로를 각각1회만 요청했다. 첫 HTTP 시작은 **17:27:13.842307 KST**, 마지막은 **17:27:19.858028 KST**, 시작 간 최소2.005초였다.

| `/api/rest/buspos/` 뒤 경로 | HTTP | TCP 완료(초) | 첫 byte(초) | 전체(초) |
| --- | --- | --- | --- | --- |
| `getBusPosByRtidList` | 401 | 0.011801 | 0.018607 | 0.018625 |
| `getBusPosByRtid` | 401 | 0.011602 | 0.020720 | 0.020767 |
| `getBusPosByVehIdItem` | 401 | 0.011905 | 0.019765 | 0.019791 |
| `getBusPosByVehId` | 401 | 0.013335 | 0.020046 | 0.020083 |

네 응답은 모두93byte·비XML로 원문은 출력/저장하지 않았다. **401은 HTTP 수신 계층에 도달했다는 근거이며, 인증 후 해당 operation이 정상 처리된다는 근거가 아니다.** 인증 검사가 경로 판별보다 앞설 수 있어 긴 이름의 alias 지원/미지원도 이 결과로 판정하지 않는다. 공식 명세의 canonical path는 확인했지만 정상 차량 데이터가 반환되는 path를 실증한 것은 아니다. 경로를 바꿔도 TCP443 연결 문제를 해결하지 못한다.

### 21.3 최소 로컬 수정·검증

- `tools/provider_evidence_probe.py`: 논리 operation과 공식 callback path를 분리하는 고정 매핑2개 추가. `fetch`는 승인된 논리 operation 외 입력을 전송 전에 거절한다. 짧은 경로를 새 승인 operation으로 추가하지 않는다
- `tools/test_provider_evidence_probe.py`: 공식 callback path·파라미터·HTTPS·기존 timeout/retry/redirect 제한 보존, 짧은 경로/임의 operation 직접 입력 시 전송0회 회귀 테스트2개 추가
- `python3 -B -m unittest discover -s tools -p 'test_provider_evidence_probe.py' -v`: **기존44개+신규2개=46개 전부 통과, failure/error/skip 0**. 실제 네트워크가 없는 synthetic/mock 검증이다
- 서울12/TAGO6/전체18·150초·6라운드·Provider 라운드20초·전체 요청2초 이상 제한, 실패 시 전체 중단, retry/redirect0·HTTPS 전용을 유지한다. timeout 증대·HTTP 전환·자동 URL 탐색/전환 기능을 추가하지 않았다

### 21.4 인증 실측 보류와 호출 회계

**안전하게 연결된 서울 HTTPS endpoint를 확보하지 못해 신규 인증 실측은0회**다. 인증키를 HTTP로 전송하면 중간 구간에서 Key와 요청/응답을 열람·변조할 위험이 있다. 기관이 지원하는 HTTPS 주소 또는 기관까지 보호되는 연결 대안이 먼저 필요하다. 앞단만 HTTPS인 임의 proxy는 마지막 HTTP 구간의 노출을 해결하지 못한다. 이번에 proxy·새 서비스·인프라를 도입하거나 인증 HTTP 전송을 승인된 것으로 해석하지 않았다.

§20의 실행 window는 **17:18:58.514851 KST에 만료**됐고 Quota 예외도 해당 실행에 한정됐다. 재사용하거나 새 window/Quota 예외를 자동 생성하지 않았다. 안전한 경로 확인 후에도 신규 실행 window·호출/시간 예산·Quota 확인 또는 해당 실행의 명시적 예외가 필요하다. 이번 요청은 인증 없는 원인 분리와 명세에 따른 로컬 수정 범위로 수행했다.

| 호출 종류 | 이전(§20) | 이번(§21) | 누적 |
| --- | --- | --- | --- |
| 서울 인증 데이터 요청 시도 | 1(timeout) | **0** | **1** |
| TAGO 인증 데이터 요청 시도 | 0 | **0** | **0** |
| 서울 인증 없는 HTTP 경로 진단 | 0 | **4(모두401)** | **4** |
| 차량 데이터 확보 | 0 | **0** | **0** |

실제 서울 호스트 HTTP 전송 시도는 인증 여부를 합쳐 누적5회다. 공개 공식 설명 페이지 조회·첨부 다운로드는 별도 문서 접근이며 위 데이터/경로 요청과 구분한다. DNS/TCP 테스트는 HTTP 요청이 아니다. 실제 Provider 데이터가 없으므로 서울 identity·stId/stOrd/flag/dataTm·roster/detail, TAGO nodeid/nodeord·번호 진행·pagination은 새로 분석할 수 없다.

### 21.5 다음 Backend 작업과 남은 근거

추가 실측 없이 준비 가능한 다음 Backend 수정 범위는 **두 서울 Client의 논리 `OPERATION`과 실제 요청 path 분리 및 공식 callback path를 사용하는 URI 계약 테스트**다. 이는 공식 명세와의 일치 작업이며, 기존 긴 이름이 운영에서 실패한다는 실증이나 timeout 해결이라고 표현하지 않는다. Production은 이번에 변경하지 않았다. Backend 기본 HTTP 사용은 Key 평문 전송 위험이 있으므로, 안전한 전송 경로·운영 사용 정책 판단을 먼저 해결해야 한다. 단순 HTTPS 치환·timeout 증대·경로 변경만으로 연결 가능성을 보장하지 않는다.

기관의 **HTTPS 지원 host/port/path·접근 조건과 실제 callback alias 계약**이 연결 문제에 남는 공식 근거다. 하차 지원에는 기존 occurrence별 하차 허용 source, moving reference·제공시각 의미, ID 수명·같은 운행·회차/lap 근거도 여전히 필요하다. 이번에 이를 VERIFIED로 승격하지 않는다. `BOARDING_TRACKING_UNSUPPORTED`와 TASK-908 `[ ]`를 유지한다.

이번에는 Credential을 전송·출력하지 않았고 브라우저 계정 화면·스크린샷을 사용하지 않았다. 기존 §20의 키 노출 기록을 삭제/수정하거나 없었다고 주장하지 않는다. Credential 재발급은 사용자의 후속 조치로 남기고 기존 값을 변경하지 않았다. 두 로컬 도구는 Untracked로 유지하며, 보고서와 `task-list.md` 진행 상태만 함께 갱신한다. 새 Dependency·Production·Flutter·FCM·devlog 변경이나 실행, 브랜치/Worktree 생성·Git add/commit/push는 없다.

문서 diff·status·`git diff --check`, Python AST·공백 검사, 전체 Task checkbox 보존·staging 비어 있음·도구 Untracked, 관련 파일의 실제 Key 원문/인코딩 변형 비포함, 보고서 파일 참조 및 기존 노출 기록 보존 검사를 통과했다. Backend/Flutter 테스트는 Production 변경이 없어 실행하지 않았다.
