# ADR-012: 탑승 세션과 하차 준비 알림 계약

- 상태: 채택됨
- 날짜: 2026-10-19
- 대상: TASK-903 설계 완료. Backend/Flutter, Migration, 실제 Provider/FCM 호출은 포함하지 않음

## 배경

[TASK-901 조사](../research/TASK-901-bus-vehicle-identification.md)와 [ADR-011](ADR-011-bus-vehicle-recommendation-and-user-confirmation.md)은 번호 대조 중심의 후보 확인과 GPS 비의존, 자동 차량 전환 금지, Provider별 검증 한계를 확정했다. 기존 Alarm은 다음 차량을 기다리는 다중 차량 감시이며, 사용자가 탄 차량 하나의 하차 목적과 lifecycle이 다르다.

개발자는 설계 검토를 거쳐 차량 확정과 즉시 추적, occurrence 간격 2/1 준비 알림, checkpoint 기반 조건부 자동 복구, 최소 세션 persistence를 승인했다. 이어 Notification source 구분, 복구 대기 Delivery의 batch 점유 방지, 최초 저장 전 만료된 준비 후보의 기회 미소비, 전송 시 Session 재검증을 확정했다. 이는 후속 구현 계약이며 기존 코드의 동작을 변경한 결과가 아니다.

## 검토한 선택지

| 선택지 | 장점 | 비용 / 판단 |
| --- | --- | --- |
| 확정 후 별도 Start와 CONFIRMED | 단계별 조작 가능 | 중간 상태와 버튼이 불필요하여 제외 |
| 번호 확정 검증과 추적 시작 통합 | 성공 의미가 명확하고 상태가 적음 | 검증 실패 시 시작을 안전하게 거부. 채택 |
| 정차한 정류장만 간격 계산 | 실제 정차 수 표현에 유리 | 경유 진행을 잃고 사용자 근접 목적과 다름. 제외 |
| 검증된 occurrence 배열 간격 | 미정차·경유 포함, GPS 불필요 | 물리 거리·정차 횟수·남은 시간과 다름. 채택 |
| 재시작마다 사용자 재확인 | 보수적인 복구 | 정상 짧은 재시작에도 조작 요구. 제외 |
| 같은 ID면 무조건 복구 | 간단함 | 회차·ID 재사용·노선 변경 오연결. 제외 |
| 마지막 checkpoint와 새 관측 검증 | 짧은 공백 자동 복구와 보류 구분 | 동일 운행의 절대 증명은 아님. 채택 |
| Metadata Sync 공유 Route Lock / snapshot 별도 table | commit 경계 조율 / 관계형 탐색 | 기존 sync 결합·락·테이블 증가. 현재 제외 |
| 세션 내 불변 snapshot과 fingerprint | 한 table, 일관된 계산·재시작 복원 | 전역 최신 metadata를 commit 순간까지 잠그지는 않음. 채택 |
| 전체 Worker 시작 지연 / DB recovery epoch | 전역 또는 durable 복구 장벽 | 기존 Alarm 지연 / 불필요한 저장 상태. 제외 |
| 세션별 기본 차단 메모리 gate | 기존 Alarm 독립, 새 table 불필요 | 단일 Backend에만 유효. 채택 |

## 결정

### 1. Domain과 사용자 흐름

독립 `BoardingSession`을 사용하고 User당 미종료 세션은 하나다. 후보 조회·화면 선택은 세션 생성이 아니다. 사용자가 전체 번호를 대조한 확정 요청에서 최신 차량·운행·목적 occurrence를 검증하고 성공하면 즉시 TRACKING으로 저장한다. CONFIRMED, 별도 Start API와 버튼은 없다. 재확인 성공도 같은 차량의 추적 재개를 포함한다.

상태·진행·복구 불변 조건은 [BoardingSession Domain](../domain-model.md#boardingsession), 사용자 보장은 [요구사항](../requirements.md#phase-9-탑승-세션과-하차-준비-알림)이 소유한다. Session ID는 같은 논리적 이용 동안 유지하며 차량·목적지를 자동 교체하지 않는다. 기존 Alarm ACTIVE/FOLLOW_UP과 restart rebaseline은 그대로다.

### 2. occurrence 기반 준비와 지원 경계

검증된 같은 traversal의 target index와 progress index 차이를 쓴다. 미정차·경유도 배열에 포함한다. 간격 2에서 PREPARE_ALIGHT를 우선 결정하고, 관측하지 못했거나 유효 Event를 저장하지 못했다면 간격 1에서 대체한다. stopFlag=1은 준비 알림 필수 조건이 아니다. 준비 알림은 세션당 최대 한 번의 durable Event이며 실제 기기 표시 exactly-once는 아니다.

진행 기준점은 Provider가 보고한 검증된 문맥이지 정확한 물리 정차 위치가 아니다. 동일 order 숫자만으로 대응을 검증했다고 하지 않는다. nodeid 누락은 후보 제외·미정차 증거가 아니며 ADR-011의 order 검증 조건을 충족한 진행만 활용한다. 이 ADR은 그 검증된 진행을 준비 판단에 사용할 조건을 추가하며 ADR-011의 추천 정렬을 변경하지 않는다.

서울 이동 중 detail의 의미와 ID/번호/roster 결합은 검증된 범위에서만 사용한다. TAGO 후보 제공은 유지하지만 GPS 없는 정확한 하차 시점은 검증 전까지 지원 보류다. 어떤 Provider에서도 식별·진행 검증이 부족한 하차 추적을 정상 시작으로 반환하지 않는다. 실제 하차 불가 지점은 정상 하차 대상으로 표현하지 않으며 접근·통과 목적과 구분한다. 검증되지 않은 접근·통과 Event나 가짜 occurrence를 추가하지 않는다. 기존 도착 Alarm의 미정차 개선은 별도 후속 범위다.

### 3. 복구·만료와 Notification 확장

불변 운행 snapshot과 마지막 검증 checkpoint를 `boarding_sessions`에 저장한다. 복구 공백은 마지막 검증 관측과 새 관측 사이로 계산하며, 시각·ID/번호·Route·진행 일관성과 회차 모호성을 함께 확인한다. 시간 이내라도 알려진 소실·reset·운행 모호성을 무시하지 않는다. 과거 checkpoint만 재평가해 알림을 만들지 않는다.

초기 설정은 세션 수명 PT4H, 복구 공백 PT2M, PREPARE_ALIGHT TTL PT30S다. 정확도·도착 전 표시·회차 불가능성을 실측 보장하는 값이 아니며 조정 가능하다. 설정·transaction·복구 gate·Worker 공정성은 [Architecture](../architecture.md#탑승-세션-실행과-notification-연계-task-903), 저장·UNIQUE는 [Database](../database.md#phase-9-boarding-persistence-contract-task-903), 요청·payload는 [API](../api.md#탑승-세션-api-task-903)가 소유한다.

기존 Notification의 Device·recipient 고정·Delivery·bounded retry·FCM을 재사용한다. source를 ALARM/BOARDING_SESSION으로 구분하고 가짜 alarmId 없이 타입별 필수 필드와 identity를 보존한다. `NotificationDispatchTarget`과 `NotificationSendRequest`의 source 확장도 TASK-906에서 구현한다.

PREPARE_ALIGHT는 최초 Event 저장 시 이미 원래 observedAt 기반 기한이 지났으면 Event·Delivery를 생성하지 않는다. 세션 알림 기회를 소비하지 않고 이후 새 유효 관측에서 다시 판단한다. 이미 저장한 Event가 만료되거나 recipient가 없거나 전송 실패해도 삭제·재생성하지 않는다. 기존 Alarm의 만료된 Event 보존 계약은 바꾸지 않는다.

### 4. 정상·예외 입력의 기대 결과

아래는 설계 예시이며 실제 API/현장 검증 결과가 아니다.

| 입력 / 상황 | 기대 결과 |
| --- | --- |
| 번호 대조·최신 검증 성공, 간격 3 | TRACKING과 최초 checkpoint 저장, 준비 Event 없음 |
| 간격 2 → 1 | 유효 PREPARE_ALIGHT 한 번, 계속 같은 차량 추적 |
| 간격 3 → 1 | 현재 1을 근거로 한 번. 놓친 2의 관측을 만들지 않음 |
| 최초 간격 2 후보가 저장 시 TTL 초과, 이후 새 관측 간격 1 | 최초 Event 없음, 새 관측의 유효 판단 허용 |
| 이미 Event 저장, 0 recipients / retry 실패 / 만료 | Event 보존, 새 Event·recipient 확장 없음 |
| 목적지 도착 / 이전에서 이후 진행 | 충분한 직접 도착 / 같은 운행의 이후 근거로 종료. 도착 미관측을 정차·하차 실패로 추측하지 않음 |
| 목적지 이후에서 시작 요청 | 시작 거부, 다음 lap 자동 가정 없음 |
| 미정차·경유를 사이에 둔 target | occurrence edge에 포함. 실제 남은 정차 수라고 표시하지 않음 |
| nodeid 없음, order 대응 검증됨 / 안 됨 | 검증된 진행 참고 활용 / 해당 판단 보류. 정확한 정차 위치 합성 없음 |
| 후보 순위만 변경 / ID·번호·목적 의미 변경 | 같은 후보 검증 계속 / 확정 거부·재조회 |
| 사용자 오선택 | 자동 검출·교정 보장 없음. 취소 후 새 차량 확인 |
| 정상 목록에서 차량 소실 / 재등장 | 소실 사유 영속·보류 / 같은 문자열만으로 자동 재연결하지 않음 |
| 짧은 restart, checkpoint와 새 관측 일관 | gate 차단 중 검증, commit 확인 후 자동 복구 |
| 오래된 공백·역행·회차·다른 lap 모호성 | gate 유지, 재확인 필요. 시간 이내라는 이유로 우선하지 않음 |
| Provider 실패 / stale / 중복·식별 충돌 | 거짓 Event 없음. 일시 실패는 자동 재조회, 연속성 상실은 재확인 |
| 취소·만료와 늦은 평가 결과 | 상태/revision 검증으로 거부, Event나 gate 복구 없음 |
| 복구 대기 PENDING 다수와 Alarm 공존 | 세션 대기는 조회 LIMIT 전에 제외, Alarm 전용 조회 예산 유지 |
| 복구 후 Session revision 변경 | gate와 current revision 재검증. Event 생성 revision과 단순 동일성 강제는 하지 않음 |
| FCM 실패·지연·접수 후 crash | 기존 attempt/expiry/retry 보존. 새 Event 없음, 표시 중복·회수 불가 한계 유지 |
| 계정 변경·A→B→A Device 이전 | 서버 세션 owner 유지, 다른 계정 접근 차단, 기존 Device generation 재검증 |

## 근거

하차 준비 목적에는 실제 정차 횟수보다 검증된 경로 진행이 적합하다. 확정과 시작을 합치면 불필요한 사용자 조작 없이 검증 실패를 명확히 처리한다. checkpoint 한 개와 불변 snapshot은 전체 관측 이력이나 새 운행 Entity 없이 자동 복구의 근거를 남긴다.

준비 Event를 세션 단위로 dedup하고 최초 저장 전 TTL을 검증하면 오래된 결정이 유일한 알림 기회를 소비하지 않는다. source 분리와 세션별 gate는 기존 Alarm의 만료·identity·dispatch를 바꾸지 않고 새 lifecycle을 연결한다.

## 결과

- TASK-903의 설계 완료와 TASK-904~908의 구현·실측 완료를 구분한다. TASK-710/711 및 Phase 8 책임도 유지한다
- TASK-904는 후보 reference와 불확실 응답, TASK-905는 session/checkpoint/판정/복구, TASK-906은 source/atomic Event/Delivery/Worker gate와 공정성, TASK-907은 확정·상태·tap UI를 구현한다
- Snapshot은 commit 순간 전역 최신 metadata 보장이 아니다. 다음 검증에서 구조 변경을 발견하면 보류하며 기존 snapshot을 자동 덮어쓰지 않는다
- ID 장기 안정성, 서울 이동 중 의미, 회차/공백 복구, TAGO 정확한 하차 시점, 초기 시간값과 실제 표시 지연은 미검증이다. 불확실 범위를 구현·실측 성공으로 취급하지 않는다
- TASK-908은 잘못된 차량/Event·자동 전환·세션당 중복·만료 후 새 전송·복구 전 전송을 허용하지 않는 계약을 검증한다. 누락/지연을 별도 기록하며 정시 표시 SLA를 새로 주장하지 않는다

## TASK-903 완료 기준 대조

TASK-903 완료 기준은 다음 Owner 계약으로 대조한다. 문서 검증은 구현/현장 검증의 대체가 아니다.

| 완료 기준 | 소유 문서 / 검토 범위 |
| --- | --- |
| User 소유권·확정/즉시 추적·취소·중복·동시 변경·복구·최소 저장 | API의 세션 계약, Database의 boarding_sessions, Domain lifecycle |
| Provider/Route/차량번호·occurrence·연속성·보류·자동 전환 금지 | Domain snapshot/progress/recovery와 이 ADR의 예외 입력 |
| GPS 없는 준비 2/1·누락·통과·종료·중복·지원 한계 | Requirements 사용자 보장, Domain 준비 판정, Architecture TTL |
| Alarm 경계·source/identity·recipient·atomic decision·gate/fairness·tap·문서 동기화 | Architecture 세션 연계, Database source 분기, API payload, ADR-010 후속 확장 |

## 재검토 시점

- Provider가 안정적인 trip/direction/lap identity 또는 명확한 진행·정차 전후 의미를 제공함
- 실제 짧은 restart 복구율, false recovery, 4시간 세션 만료 사례, Event→표시 지연·만료율 측정값 확보
- Metadata 변경을 commit 순간까지 동기 차단할 요구 또는 다중 Backend 필요가 확인됨
- 경유 지점의 접근·통과 알림을 실제 지원할 별도 근거와 사용자 요구가 확보됨
