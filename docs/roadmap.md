# 로드맵

## Phase 0 — 조사 및 PoC

목표: 핵심 아이디어가 기술적으로 실현 가능한지 증명한다.

- [x] 서울특별시·경기도 초기 지원 범위 및 Transit API 후보 조사 (TASK-301)
- [x] API 약관, 요청 제한, 식별자, 검색 기능, 갱신 주기 확인 (TASK-302)
- [x] 실제 Transit API 호출 PoC 및 서울·경기 응답 구조 확인 (TASK-303)
- [x] V1 Transit Provider 및 identifier 전략 최종 결정 (TASK-304)
- [x] 신뢰할 수 있는 V1 알림 실행 규칙과 Transit 관측 모델 정의 (TASK-305)
- [ ] 실제 기기에 테스트 FCM 푸시 알림 전송

완료 기준:

개발자가 실제 교통 데이터를 관찰하고 테스트 휴대폰에 독립적으로 푸시 알림을 보낼 수 있다.

## Backend Authentication 기반

Alarm 기능보다 먼저 Google Social Login과 StopBell 자체 Token 기반 인증을 구현한다.

- [x] Google Login 및 Backend Google ID Token 검증
- [x] StopBell JWT Access Token과 Refresh Token 발급·회전·무효화
- [x] Spring Security 기반 인증된 User 식별
- [x] 인증된 User 기준 Alarm 소유권 처리

Transit API 조사와 실제 응답 관찰은 Transit Foundation에서 먼저 수행한다. 이후 V1 구현은 Alarm Backend, Transit Integration, Flutter Client, Notification 순으로 진행한다. Flutter Google Login, Secure Storage 기반 로그인 상태 유지, Token 갱신 및 Logout 연동은 이 흐름에서 Flutter Client 단계에 포함한다.

Transit Foundation의 결정 흐름은 다음과 같다.

```text
TASK-301 서울 + 경기 지원 범위와 API 후보 선정
        ↓
TASK-302 약관 / rate limit / identifier / 검색 / update frequency 조사
        ↓
TASK-303 실제 TAGO 등 API 호출 PoC 및 서울·경기 응답 구조 확인
        ↓
TASK-304 V1 Provider 및 identifier 전략 최종 결정
        ↓
TASK-305 Transit 관측 모델 및 Alarm Transit Target 계약 결정
```

Transit Foundation Phase 3의 TASK-301~305는 모두 완료됐다.

TASK-304는 경기 TAGO를 Route/Stop metadata·realtime Location·Arrival 보조 Provider로, 서울특별시 버스위치정보조회 서비스를 realtime Location Provider로 결정했다. 서울 static Route/Stop metadata source의 초기 노선정보조회 서비스 결정은 PoC와 ADR-009에서 서울 T Data CSV full import로 대체됐다. Provider external identifier는 `TAGO`, `SEOUL_BUS` namespace 안의 opaque String으로 처리한다. TASK-305는 provider-neutral Observation, Alarm Transit Target, ARRIVED·PASSED·UNKNOWN과 before/after 및 Vehicle tracking lifecycle 계약을 확정했다. TASK-401은 BusAlarmTarget snapshot Schema와 INACTIVE/ACTIVE/FOLLOW_UP lifecycle 및 재시작 가능한 after follow-up runtime을 구현했다. API, mapping, Evaluation, Scheduler는 Phase 4~5의 후속 Task에서 구현한다.

## Phase 1 — 버스 알림 MVP

목표: 실제 종단 간 버스 알림.

- [x] Flutter 기본 애플리케이션
- [x] Spring Boot 백엔드 골격
- [x] MySQL 8.4 LTS Docker Compose 개발 환경
- [x] JPA / MyBatis 설정
- [ ] 버스 노선 검색
- [ ] 정류장 선택
- [ ] 알림 생성/목록/삭제
- [ ] 활성 알림 모니터링
- [ ] 실행 조건 평가
- [ ] 기기 등록
- [ ] FCM 전달
- [ ] 중복 방지
- [ ] 기본 로그와 오류 처리

완료 기준:

사용자가 실제 휴대폰에서 알림을 만들고 앱을 종료하거나 백그라운드로 전환한 뒤, 실제 버스 이동 이벤트에 따라 예상한 알림을 받을 수 있다.

## Phase 1.1 — 신뢰성

- [ ] 실제 환경 테스트에서 알림 지연 측정
- [ ] 외부 API 갱신 동작 기록
- [ ] 일시적인 제공자 장애 처리
- [ ] 서버 재시작을 안전하게 처리
- [ ] 중복 방지 개선
- [ ] Notification delivery 운영 데이터와 장기 Analytics 경계 검증
- [ ] 알림 평가 자동화 테스트 추가

## Phase 1.2 — 사용자 경험

- [ ] 타당한 경우 즐겨찾는 노선/정류장
- [ ] 더 나은 활성 알림 UX
- [ ] 푸시 권한 UX
- [ ] 비어 있음/로딩/오류 상태
- [ ] 기본 접근성 검토

## Phase 1.3 — 배포 / 운영

- [ ] 백엔드 Docker 컨테이너화
- [ ] 영속적인 서버 환경에 백엔드 배포
- [ ] HTTPS 설정
- [ ] 운영 데이터베이스 설정
- [ ] 저장소 밖에서 시크릿 관리
- [ ] 상태 확인 추가
- [ ] CI 빌드/테스트 추가
- [ ] 수동 배포를 이해한 뒤에만 CD 추가

## Phase 5 — Transit Integration 의존성

Phase 5는 realtime branch와 metadata/user selection branch를 독립적으로 진행한 뒤 통합한다. TASK-513은 metadata source adapter와 bootstrap 책임이며, 기존 완료 TASK-507을 미완료로 되돌리거나 realtime branch를 선행 차단하지 않는다.

```text
Realtime
TASK-501 → TASK-502 → TASK-503 → TASK-504

Metadata / User selection
TASK-507 → TASK-513 → TASK-505 → TASK-506

두 branch 준비 후
TASK-508 → TASK-509 → TASK-510 → TASK-511 → TASK-512
```

## Phase 6 — Flutter Client 의존성

Phase 6의 현재 목표는 iOS 개발 및 실제 기기 vertical slice다. TASK-601~605 Authentication lane은 Backend Authentication이 준비되어 있으므로 Phase 5와 병행할 수 있다. TASK-601 구현 전에는 bundle ID, Google iOS client ID, Backend server/web client ID, API origin 등 필요한 개발 설정을 검증한다. Apple Login과 App Store 계정 삭제 요건은 이 계약에서 확정하지 않고 별도 release readiness에서 재검토한다.

```text
Authentication lane (Phase 5와 병행 가능)
TASK-601 ~ TASK-605 (공통 Auth Session 계약)

Transit / Alarm lane
TASK-513 → TASK-505 → TASK-606
TASK-506 → TASK-607 → TASK-608
                         ↓
             ACTIVE monitoring 종단 검증은 Phase 5 monitoring 완료 후
```

Flutter는 미확정 Route DTO나 identity를 추측하지 않는다. TASK-606~608은 loading, empty, error, retry와 mutation 진행 중 중복 입력 방지를 포함한다. offline mutation queue/cache는 선행 구축하지 않으며, idempotency 계약이 없는 Alarm create는 timeout으로 자동 POST 재시도하지 않는다. TASK-609는 각 Task의 테스트를 모아 작성하는 항목이 아니라, 이 흐름이 준비된 뒤 Flutter Authentication/Transit/Alarm의 E2E·regression을 보강하는 최종 Task다.

## Phase 7 — Notification 의존성

Phase 7은 SDK와 iOS targeting 동작을 먼저 실제 Device에서 확인하고, Device identity와 registration ordering, logical Notification dedup, provider failure 의미를 설계한 뒤 persistence와 delivery를 구현한다. Alarm lifecycle transition과 durable NotificationEvent는 MySQL transaction으로 묶고 in-process worker가 commit 뒤 전달한다. 외부 message broker나 Notification microservice는 도입하지 않는다.

```text
TASK-701 FCM/iOS contract + early Firebase→device smoke
    ↓
TASK-702 Device identity / multi-device / logout contract
    ↓
TASK-708 design: activation/tracking/event dedup identity
    ↓
TASK-709 design: provider result/failure taxonomy
    ↓
TASK-707 NotificationEvent / NotificationDelivery persistence + durable outbox
    ↓
TASK-703 Device API ─┐
TASK-704 Flutter lifecycle ─┴→ TASK-705 Push provider client + Backend→device smoke
                                  ↓
TASK-708 implementation → TASK-706 orchestration → TASK-709 implementation
                                  ↓
TASK-710 iPhone foreground/background/terminated/tap 검증
                                  ↓
TASK-711 최종 E2E/regression
```

각 Task는 자기 correctness test를 함께 작성한다. TASK-711은 테스트를 몰아서 처음 작성하는 Task가 아니라 최종 E2E/regression 보강이다. Phase 7에서 logical DB uniqueness, stale activation 보호, durable pending recovery, multi-device fan-out, invalid registration conditional cleanup, bounded retry와 실제 iOS 상태별/tap 검증을 갖추며 Phase 8에 미루지 않는다.

## Phase 2 — 지하철 기상 알림

V1 버스 알림이 신뢰성 있게 동작한 뒤에만 시작한다.

조사 주제:

- 실시간 지하철 데이터 가용성
- 사용자의 열차 식별 또는 추정
- 목적지 근접 로직
- 지하 환경의 GPS 제한
- 백그라운드 위치 권한 제한
- iOS/Android 차이
- 알림 시점 허용 오차

## 향후 — 제품에 필요할 때만

가능한 주제이며, 약속된 항목은 아니다.

- 그룹화된 교통 폴링
- 공유 캐시
- Redis
- 외부 message broker 기반 알림 queue
- 여러 백엔드 인스턴스
- 추가 교통 지역/제공자
- 반복 통근 프리셋
