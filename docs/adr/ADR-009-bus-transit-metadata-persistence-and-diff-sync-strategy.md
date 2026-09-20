# ADR-009: Bus Transit Metadata Persistence 및 Diff Sync 전략

- 상태: 채택됨
- 날짜: 2026-09-13

## 배경

V1 Bus Route/Stop 선택과 Alarm 생성이 Provider metadata API를 매번 다시 호출하면 외부 가용성·rate limit에 불필요하게 의존하고, 선택한 Route traversal occurrence를 현재 metadata와 일관되게 다루기 어렵다. 조사 결과 서울 7016의 T Data CSV identifier와 기존 realtime identifier, 경기 7000의 TAGO metadata와 realtime PoC identifier·order·name이 일치했다. 경기 TAGO는 31개 cityCode를 순회해 2,155개 고유 Route와 Route별 Stop occurrence를 확보할 수 있음을 확인했다.

## 검토한 선택지

### 선택지 A — Alarm 생성마다 Provider metadata 재조회

장점:

- 항상 외부 최신 metadata를 직접 사용

단점:

- 사용자 조회와 Alarm 생성이 외부 API 가용성·호출 제한에 의존
- Route traversal과 identifier를 현재 StopBell 기준으로 관리할 수 없음

### 선택지 B — 전체 삭제 후 재삽입

장점:

- importer 구현이 단순함

단점:

- 불필요한 write가 발생하고 내부 ID가 매 sync마다 바뀜
- 향후 opaque occurrence reference 사용 가능성을 훼손

### 선택지 C — local metadata persistence와 identity 기반 diff sync

장점:

- 외부 source와 사용자 요청을 분리하고 current Route/Stop metadata를 DB에서 제공
- 동일 Route/Stop의 내부 ID를 유지하면서 변경분만 반영
- occurrence의 의미 변경에는 새 ID를 부여할 수 있음

단점:

- source snapshot 완전성 및 삭제 시점을 명시적으로 관리해야 함

## 결정

서울 metadata는 T Data CSV full import, 경기 metadata는 TAGO throttled full sync를 source로 한다. `BusRoute`, `BusStop`, `BusRouteStopOccurrence` JPA Entity와 MySQL Schema를 사용하며 identity는 각각 `(provider, externalRouteId)`, `(provider, externalStopId)`다. occurrence는 Route, Stop, positive stopOrder로 특정하고 `(route_id, stop_order)`만 unique하게 둔다. 같은 Route에서 Stop 재방문을 허용하므로 `(route_id, stop_id)` unique는 두지 않는다.

각 source adapter는 raw DTO 대신 normalized route snapshot을 전달하고 service가 한 Route 단위로 reconciliation한다. 같은 Route/Stop identity의 display·operational metadata는 UPDATE해 internal ID를 유지한다. occurrence는 `(route, stop, stopOrder)`가 완전히 같을 때만 ID를 유지하며 Stop 또는 order가 바뀌면 기존 row를 삭제하고 새 row를 만든다. Route snapshot에서 사라진 occurrence는 제거한다.

Provider 전체 fetch가 완전히 성공한 경우에만 source에 없는 Route를 제거하며 Route FK는 occurrence cascade delete를 가진다. Stop은 여러 Route에서 공유될 수 있으므로 모든 occurrence에서 더 이상 참조되지 않는 provider Stop만 cleanup한다. fetch 실패·부분 snapshot에서는 provider-level 삭제를 실행하지 않는다. Complete provider snapshot은 adapter가 required source 존재, parsing/validation 성공, pagination 완료 및 provider request 성공을 확인한 결과여야 하며, 검증되지 않은 empty collection은 complete가 아니다. Typed complete snapshot boundary, explicit completeness token/result, destructive method visibility 제한 또는 동등한 구조적 보호로 검증된 snapshot만 cleanup 경로에 들어가게 하고 구체 type은 TASK-513에서 정한다.

Provider별 최소 persisted metadata sync state는 `provider`, `lastCompleteSyncAt` 의미를 보존한다. Fresh DB bootstrap 완료, readiness와 last successful complete sync age 판단에 사용하며 필요하면 in-progress/failure 정보를 확장할 수 있다. Sync history, checksum history, staging table, error journal은 현재 요구하지 않고 정확한 Schema/type은 TASK-513에서 정한다.

`BusAlarmTarget`은 metadata Entity를 FK로 장기 참조하지 않는다. Alarm 생성 시 metadata에서 필요한 값을 읽어 Target snapshot에 복사하므로 metadata sync가 기존 Alarm target을 자동 변경하지 않는다.

metadata CRUD와 reconciliation은 JPA Entity 상태 관리와 단순 관계 CRUD가 중심이므로 지금 MyBatis를 사용하지 않는다. Route/Stop 검색, Alarm grouping, 대량 조회 성능에서 실제 SQL 제어가 필요할 때 MyBatis를 추가한다. 실제 source adapter, full import command, Scheduler, retry/backoff와 Alarm API는 이 결정과 분리된 후속 Task다.

## 후속 구현 경계: TASK-513

TASK-507은 이 ADR의 Schema와 route-level reconciliation을 구현했고, TASK-513은 source adapter와 production ingestion을 담당한다. 서울은 노선마스터·정류장마스터·노선-정류장마스터 T Data CSV를 parsing·validation해 normalized metadata snapshot을 만들고, 경기는 TAGO city별 Route와 Route Stop을 pagination 완료까지 수집해 snapshot을 만든다. CSV header, encoding, GPS column은 실제 fixture 또는 source 파일을 확인한 TASK-513 구현 시점에 확정한다.

최초 bootstrap은 일반 Backend startup에 묶지 않는 명시적 one-shot import/sync 실행을 기본으로 한다. 자동 refresh 주기는 이 ADR이나 TASK-513 문서 범위에서 확정하지 않는다. partial source fetch, parser failure, pagination incomplete, required source missing, provider request 일부 실패와 검증되지 않은 empty result는 모두 complete snapshot이 아니므로 provider 전체 cleanup을 실행하지 않는다. Incomplete snapshot을 absence/deletion으로 해석하지 않는다.

단일 Backend 환경에서 동일 Provider full sync는 single-flight로 실행한다. Provider 전체를 하나의 장시간 DB transaction으로 묶지 않고 기존 Route 단위 transaction과 successful complete snapshot 이후 cleanup 방향을 유지한다. ingestion 구현은 Route reconciliation의 Stop lazy-loading N+1 여부를 확인·개선한다. Redis, distributed lock, queue는 V1에 도입하지 않는다.

## 근거

확인된 서울·경기 identifier compatibility와 full sync 가능성은 local current metadata를 유지할 충분한 근거다. diff sync는 불필요한 write를 줄이고 stable internal ID 가능성을 보존한다. Route/Stop identity와 Route traversal occurrence를 분리하면 순환·재방문 노선에서 Stop 자체와 occurrence를 혼동하지 않는다. Alarm Target snapshot을 분리하면 current metadata의 정상 변경이 사용자가 이미 생성한 Alarm의 의미를 바꾸지 않는다.

## 결과

- 사용자 Route/Stop 조회와 Alarm 생성은 후속 API에서 DB metadata를 사용한다
- `BusRoute`와 `BusStop` metadata 변경은 내부 ID를 유지한다
- 의미가 바뀐 occurrence는 기존 ID를 재사용하지 않는다
- partial source failure가 Route 삭제로 오판되지 않도록 provider cleanup 호출을 검증된 complete snapshot 성공 뒤로 제한한다
- Provider별 마지막 complete sync 성공 시각을 최소 영속 상태로 관리한다
- MyBatis는 현재 추가하지 않으며 hybrid persistence 결정은 유지한다
- Source downloader/parser와 one-shot bootstrap은 TASK-513, realtime production client·Scheduler와 Alarm API는 각각 후속 Task로 남는다

## 재검토 시점

다음 중 하나 이상이 확인되면 이 결정을 재검토한다.

- 서울 CSV 또는 경기 TAGO source가 identifier compatibility나 full snapshot completeness를 지속적으로 보장하지 못함
- Route/Stop 검색 또는 Alarm grouping에서 JPA query만으로 성능·표현 요구를 충족하지 못함
- Provider가 stable metadata version 또는 deletion contract를 제공해 더 안전한 incremental strategy가 가능해짐
- metadata 변경이 Alarm snapshot reconciliation을 실제 제품 요구로 만듦
