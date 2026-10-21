# TASK-908 사전 검증 1단계: Provider 차량·진행·하차 가능성 증거

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

추가 파일: [probe](../../tools/provider_evidence_probe.py), [synthetic 테스트](../../tools/test_provider_evidence_probe.py). Python 표준 라이브러리와 기존 `/usr/bin/curl`만 사용하며 Backend import/DB/FCM 연동이 없다. **기본 dry-run은 Credential/승인 파일도 읽지 않고 HTTP 0건**이다.

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
