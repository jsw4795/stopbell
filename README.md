# StopBell

StopBell은 사용자가 교통 정보를 계속 확인하지 않아도, 행동이 필요한 시점에 알림을 보내는 모바일 교통 알림 서비스입니다.

## 해결하려는 문제

- 특정 버스가 선택한 정류장에 도착하거나 지나갈 때 출발하고 싶지만, 교통 앱을 계속 확인하고 싶지 않은 상황
- 지하철 승객이 목적지 역을 지나칠 걱정 없이 이동하고 싶은 상황

## 제품 방향

- **V1 — 버스 도착·통과 알림:** 버스와 목표 정류장을 선택하고, 조건이 충족되면 푸시 알림을 받는 흐름에 집중합니다.
- **V2 — 지하철 목적지 기상 알림:** V1이 신뢰성 있게 동작한 뒤 검토합니다.

V1의 첫 성공 기준은 사용자가 알림을 설정하고, 조건이 충족되었을 때 실제 휴대폰에서 신뢰할 수 있게 푸시 알림을 받는 것입니다.

## 현재 개발 상태

**구현 완료**

- Google 로그인과 서비스 자체 Access/Refresh Token 인증, 사용자별 Alarm 생성·조회·활성화·비활성화·삭제 API
- 서울·경기 버스 노선·정류장 메타데이터 수집·저장 및 검색 API, 외부 교통 API HTTP 연동과 내부 관측 모델 변환
- 도착·통과 및 전후 정류장 조건 판단, 노선별 요청을 공유하는 Scheduler 기반 관측과 Alarm 상태 반영. 외부 API 실패는 판단 불가로 처리해 도착·통과 이벤트를 만들지 않습니다.
- Flutter의 Google 로그인, 토큰 보안 저장·자동 갱신, 노선·정류장 검색과 Alarm 생성·관리 화면. 주요 인증·검색·Alarm 흐름은 실제 iPhone에서 검증했습니다.
- FCM iOS 기본 연동·실제 기기 수신 가능성 검증, Device 등록·비활성화 API, NotificationEvent / NotificationDelivery 영속화와 Durable Outbox용 DB 기반. 저장 구조와 전달 흐름의 분리 근거는 [ADR-010](docs/adr/ADR-010-notification-device-and-durable-delivery.md)에 기록했습니다.

**진행 중 / 남은 작업**

- Flutter 앱의 Push 권한·기기 등록·로그아웃·알림 탭 처리 흐름과 Backend의 실제 FCM 전송 Client·Notification Dispatch Worker 구현
- Alarm 상태 변경과 Event·기기별 Delivery 생성을 하나의 트랜잭션으로 연결하고, 중복 알림 방지·전송 실패 재시도를 구현하는 작업. 설계와 DB 제약은 준비됐지만 실제 처리 흐름은 미완료입니다.
- 실제 iPhone의 foreground/background/terminated 수신·탭 동작을 포함한 최종 알림 E2E 검증과 Production 배포 준비. FCM 전송 요청의 접수 성공만으로 기기 표시 성공을 판단하지 않습니다.

**다음 목표**

Alarm 조건 판단부터 실제 기기 알림 수신까지 V1 전달 흐름을 완성하고 검증합니다. 세부 진행 상태는 [Task List](docs/task-list.md)에서 관리합니다.

## 대표 의사결정

### Hybrid 방향을 검토한 뒤, 현재 V1은 JPA로 유지

초기에는 도메인 CRUD와 상태 전이는 JPA로, 교통 데이터·복잡한 조회·집계처럼 SQL 제어가 중요한 영역은 MyBatis로 분리하는 hybrid 방향을 검토했습니다. 그러나 현재 구현된 조회는 JPA로 충분하고 MyBatis Mapper나 전용 production 설정도 없다는 점을 확인했습니다.

따라서 현재 V1 persistence는 JPA로 유지합니다. 복잡한 Query가 JPA로 지나치게 어려워지거나 실제 execution plan·성능 문제가 확인될 때에만 MyBatis를 다시 도입합니다. 기술을 미리 병행하는 복잡성 대신, 실제 필요가 확인된 후 도입하는 trade-off이며 [ADR-002](docs/adr/ADR-002-jpa-and-mybatis-hybrid.md)에 초기 선택과 재검토 결과를 함께 기록했습니다.

### Google Identity와 StopBell 인증 토큰의 분리

Flutter는 Google ID Token으로 외부 Identity를 증명하고, Backend는 이를 검증한 뒤 StopBell API용 Access Token과 Refresh Token을 발급합니다. Google Token을 애플리케이션 API의 장기 인증 수단으로 직접 사용하지 않아, 외부 Identity 확인과 서비스 세션 정책을 분리했습니다.

Access Token은 기본 1시간, Refresh Token은 기본 30일로 두고 재발급 때 이전 Refresh Token을 폐기합니다. 장기 로그인과 노출 영향을 분리하는 대신 Refresh Token 저장·회전·무효화와 Google Token 검증을 구현해야 합니다. 범위와 한계는 [ADR-005](docs/adr/ADR-005-authentication-and-user-identity-strategy.md)에 기록했습니다.

### V1 범위: 버스 알림 흐름 우선

V1은 버스 노선·목표 정류장 선택부터 도착 또는 통과 판단, 실제 기기 푸시 알림까지의 흐름을 신뢰성 있게 검증하는 범위입니다. 이 검증이 끝나기 전에는 V2 지하철 기능을 추가하지 않습니다. 현재의 주요 위험은 기능 수를 늘리는 일이 아니라 실제 교통 이벤트를 감지해 알림을 전달할 수 있는지에 있으므로, 하나의 Spring Boot 백엔드 안에서 논리적 모듈을 나눈 구조로 먼저 검증합니다. 관련 근거는 [요구사항](docs/requirements.md)과 [ADR-001](docs/adr/ADR-001-monolith-first.md)에서 확인할 수 있습니다.

## 기술 스택

- 모바일: Flutter / Dart
- 백엔드: Java / Spring Boot, Spring Security
- 저장소: MySQL (로컬 Docker), Spring Data JPA, Flyway
- 인증: Google ID Token 검증, JWT Access Token, Refresh Token Rotation

MyBatis는 현재 V1 production persistence에 사용하지 않으며, 도입 조건은 ADR-002에 기록했습니다.

## 시스템 및 저장소 구조

```text
Flutter 앱
  └─ HTTPS / JSON ─> Spring Boot 백엔드 ─> MySQL
                         ├─ JPA: 현재 V1의 도메인 CRUD와 상태 관리
                         └─ 서울 버스 API / TAGO: 주기적 교통 상태 관측
```

```text
app/        Flutter 애플리케이션
backend/    Spring Boot 애플리케이션
docs/       요구사항, 아키텍처, API, 데이터 모델, ADR
```

## 주요 문서

- [요구사항](docs/requirements.md) · [아키텍처](docs/architecture.md) · [도메인 모델](docs/domain-model.md)
- [API](docs/api.md) · [데이터베이스](docs/database.md) · [로컬 개발](docs/local-development.md)
- [ADR 목록](docs/adr/README.md) · [로드맵](docs/roadmap.md) · [Task List](docs/task-list.md)

## 개발 규칙

구현 전 요구사항과 관련 ADR을 확인하고, 문서화되지 않은 제품 요구사항이나 아키텍처 결정을 임의로 추가하지 않습니다. 세부 규칙은 [개발 가이드](docs/development-guidelines.md)와 [Task List](docs/task-list.md)를 따릅니다.
