# 요구사항

## 1. 제품 목표

StopBell은 사용자가 교통 정보를 반복해서 확인해야 하는 필요를 줄여야 한다.

제품은 실제 교통 이동 정보를 바탕으로 의미 있는 시점에 사용자에게 알려야 한다.

## 2. 버전별 범위

### V1 — 버스 알림

사용자는 버스와 목표 정류장을 선택하고 알림을 활성화한 뒤 앱을 종료하거나 백그라운드로 전환할 수 있으며, 사용 가능한 교통 데이터에 따라 설정한 버스가 목표 정류장에 도착하거나 통과했을 때 푸시 알림을 받아야 한다.

### V2 — 지하철 기상 알림

지하철을 이용하는 사용자는 경로를 계속 확인하지 않아도 목적지 역을 선택하고 목적지 도착 전 또는 도착 시점에 알림을 받을 수 있어야 한다.

명시적인 프로젝트 결정으로 순서가 변경되지 않는 한, V1이 신뢰성 있게 동작하기 전에는 V2를 구현하지 않는다.

## 3. V1 기능 요구사항

### FR-001 버스 검색

사용자는 설정된 교통 데이터 제공자가 지원하는 버스 노선을 찾고 선택할 수 있다.

### FR-002 정류장 선택

사용자는 선택한 버스 노선에 연결된 정류장을 선택할 수 있다.

### FR-003 알림 생성

사용자는 최소한 다음을 포함하는 알림을 만들 수 있다.

- Provider namespace 안의 버스 노선 식별자
- 같은 Provider namespace 안의 목표 정류장 식별자
- 선택한 Route traversal에서 목표 정류장의 진행 순서
- 순환·재방문·분기·회차의 occurrence 모호성을 해소하는 데 필요한 traversal/direction context
- 알림 상태
- 서로 독립적인 한 정거장 전 / 한 정거장 후 추가 알림 옵션

추가 필드는 필요할 때만 도입한다.

### FR-004 알림 활성화 / 비활성화

사용자는 알림을 활성화하고 비활성화할 수 있다.

### FR-005 교통 모니터링

백엔드는 외부 교통 데이터 소스를 사용해 활성 알림 조건이 충족되었는지 판단한다.

### FR-006 푸시 알림

알림 조건이 충족되면 백엔드는 사용자가 등록한 기기로 푸시 알림을 시작한다.

### FR-007 중복 방지

동일한 알림 발생 건은 의도적으로 중복 알림을 생성하지 않아야 한다.

### FR-008 활성 알림 조회

사용자는 현재 활성화된 알림을 볼 수 있다.

### FR-009 알림 제거

사용자는 더 이상 필요 없는 알림을 제거할 수 있다.

### V1 초기 Transit 지원 범위

V1의 초기 버스 지원 대상 지역은 다음과 같다.

- 서울특별시
- 경기도

사용자의 실제 이동 범위가 서울과 경기에 걸쳐 있으므로 두 지역을 V1부터 함께 지원한다. 이 결정은 전국 지원을 V1 제품 요구사항으로 확장하는 것이 아니다.

### V1 Transit Provider

V1은 지역별 공식 Provider를 사용한다. 경기도는 국토교통부 TAGO가 Route/Stop metadata, realtime Location, Arrival 보조 정보를 제공한다. 서울특별시는 서울특별시 노선정보조회 서비스가 Route/Stop metadata를, 서울특별시 버스위치정보조회 서비스가 realtime Location을 제공한다.

Provider external Route/Stop ID는 provider namespace 안의 opaque String이다. 노선번호와 정류소명은 검색·표시 metadata이고, Stop order는 Route 진행 metadata이며 identity가 아니다. 세부 근거와 제한은 `adr/ADR-006-v1-transit-provider-and-external-identifier-strategy.md`를 따른다.

### V1 Bus Alarm 실행 규칙

- 사용자는 Bus Route와 자신에게 필요한 Target Stop을 직접 선택한다. First Stop은 `targetStopOrder`가 해당 Route traversal의 첫 순서인 일반 Target 사례이며 hard-coded target이 아니다.
- 추적 차량이 Target Stop에 도착했다고 충분히 판단되면 `ARRIVED` Notification을 보내고 Alarm을 성공 처리해 자동 비활성화한다.
- Alarm 활성화 순간 이미 Target Stop에 있는 차량도 충분한 도착 근거가 있으면 즉시 `ARRIVED`로 처리한다.
- before 옵션이 켜진 Alarm 활성화 순간 차량이 이미 Target predecessor에 있다고 충분히 판단되면 즉시 `ONE_STOP_BEFORE` Notification을 보낸다. Alarm은 ACTIVE이고 같은 차량을 ARRIVED까지 계속 추적한다.
- 활성화 당시 이미 Target을 지난 차량은 baseline existing vehicle로 보고 PASSED 알림을 만들지 않는다.
- 활성화 뒤 Target 이전부터 추적한 동일 차량이 직접 도착 관측 없이 Target을 건너뛰었다는 충분한 진행 근거가 있으면 `PASSED` Notification을 보낸다. 가능한 경우 정류장명 등 최근 확인된 위치와 Target에서 지난 정거장 수를 함께 안내한다. 지난 정거장 수는 raw Stop order 차가 아니라 확인된 동일 Route traversal의 successor edge 수이며, 계산할 수 없으면 생략한다.
- `PASSED`는 Alarm 성공 또는 종료가 아니다. 해당 차량 추적만 끝내고 Alarm은 ACTIVE로 유지해 다음 차량을 계속 감시한다.
- 사용자는 `notifyOneStopBefore`와 `notifyOneStopAfter` 의미의 추가 알림을 서로 독립적으로 선택할 수 있다. 구체적인 API field naming은 TASK-402에서 확정한다.
- Target이 Route traversal의 첫 Stop이면 before 옵션을, 마지막 Stop이면 after 옵션을 사용할 수 없다. Client UX와 별개로 Backend 생성 계약도 이를 검증할 수 있어야 한다.
- before/after의 인접 Stop은 단순 숫자 증감이 아니라 Provider의 방향·Route sequence metadata로 확인한 predecessor/successor다.
- after 옵션이 꺼져 있으면 ARRIVED 뒤 모든 추적을 끝낸다. 켜져 있으면 Alarm은 그대로 비활성화하고 ARRIVED 차량만 다음 Stop 도달·통과까지 짧게 추적해 after Notification을 한 번 보낸다.
- ARRIVED short follow-up 중 동일 Alarm을 다시 활성화하면 이전 follow-up을 취소하고 새 baseline과 monitoring cycle을 시작한다. Alarm을 삭제하면 active monitoring과 연결된 short follow-up을 모두 종료한다.
- 한 Observation transition에서는 가장 의미 있는 Event 하나만 알린다. 직접 ARRIVED를 관찰하지 못한 채 Target 이전에서 이후로 점프하면 여러 알림 대신 PASSED를 선택한다. 이미 ARRIVED를 알린 차량의 follow-up 진행은 PASSED가 아니라 ONE_STOP_AFTER 후보로 처리한다.
- Provider data가 stale하거나 방향·차량 연속성·필수 identifier가 불명확하거나 신호가 충돌하면 `UNKNOWN`으로 두고 ARRIVED/PASSED Notification을 만들지 않는다. 외부 Provider 요청 실패도 거짓 Transit Event를 만들지 않는다.

세부 Observation, Event 및 lifecycle 계약은 `adr/ADR-007-bus-alarm-transit-observation-and-event-semantics.md`를 따른다.

## 4. 인증

StopBell은 자체 ID/Password 회원가입을 제공하지 않고 Social Login만 지원한다. 최초 Provider는 Google이며, 추가 Provider는 실제 필요가 확인된 후 별도 범위로 검토한다.

Flutter는 Google ID Token으로 외부 Identity를 증명하고, Backend는 이를 검증한 뒤 StopBell 자체 Access Token과 Refresh Token을 발급한다. Google Token은 StopBell Application API의 장기 인증 Token으로 사용하지 않는다.

Application API는 JWT Access Token 기반으로 인증하며, Access Token 기본 수명은 1시간이다. Refresh Token은 Access Token 재발급과 현재 Session Logout에 사용하고, 30일 수명 및 Rotation 정책으로 장기 로그인 유지를 지원한다. Logout은 해당 Refresh Token Session만 삭제하며 이미 발급된 Access Token은 만료 시점까지 유효할 수 있다. Refresh Token이 만료되거나 유효하지 않으면 다시 Google Login이 필요하다.

공개 배포 전뿐 아니라 Alarm API 구현부터 인증된 StopBell User를 기준으로 Alarm 소유권을 처리한다. Client가 제공한 `userId`를 신뢰하지 않는다.

인증 전략의 근거와 제약은 `adr/ADR-005-authentication-and-user-identity-strategy.md`를 따른다.

## 5. 비기능 요구사항

### NFR-001 신뢰성

시스템은 불필요한 기능 폭보다 누락 알림과 중복 알림 방지를 우선해야 한다.

### NFR-002 관측성

다음의 중요한 단계는 로그를 통해 추적할 수 있어야 한다.

- 교통 API 요청/결과
- 알림 조건 평가
- 푸시 요청/결과
- 관련 실패

로그에 시크릿이나 민감한 토큰을 노출해서는 안 된다.

### NFR-003 외부 API 효율성

여러 활성 알림이 같은 교통 조회를 공유할 수 있다면 시스템은 사용자마다 동일한 외부 API 요청을 하나씩 보내서는 안 된다.

초기 구현은 단순할 수 있지만, 한계는 측정하고 문서화해야 한다.

### NFR-004 장애 처리

교통 API 또는 푸시 제공자의 일시적 장애가 알림 상태를 손상해서는 안 된다.

재시도 동작은 의도적이고 제한되어야 한다.

### NFR-005 보안

- 시크릿은 Git에 커밋하지 않는다.
- API 키는 환경/설정 메커니즘을 통해 저장한다.
- 기기 토큰과 인증 데이터는 민감한 데이터로 취급한다.

### NFR-006 유지보수성

알림 조건을 판단하는 비즈니스 규칙은 가능한 경우 Controller/네트워크 연결 코드와 분리해야 한다.

## 6. V1에서 명시적으로 제외하는 범위

별도 승인이 없다면 V1에는 다음을 포함하지 않는다.

- 지하철 추적
- 결제
- 소셜/친구 기능
- 채팅
- 경로 추천
- 분석 대시보드
- 시연만을 위한 Kafka/RabbitMQ
- 시연만을 위한 Redis
- 마이크로서비스
- 멀티 리전 배포
- 복잡한 관리자 콘솔

## 7. 미해결 질문

운영 구현 전에 다음을 조사하거나 결정해야 한다.

- Transit metadata persistence와 MyBatis가 실제로 필요한가?
- 어떤 폴링 주기가 허용되며 유용한가?
- 어떤 요청 제한이 적용되는가?
- FCM은 Android와 iOS 요구사항 모두에 충분한가?
