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

기반 구현 단계입니다. Phase 0 환경 구성, Phase 1 도메인·Repository, Phase 2 인증의 주요 구현 항목을 완료했습니다. 알림 API, 실제 교통 데이터 연동, 푸시 전송 흐름은 아직 구현 전입니다. 자세한 진행 상태는 [Task List](docs/task-list.md)에서 관리합니다.

## 기술 스택

- 모바일: Flutter / Dart
- 백엔드: Java / Spring Boot, Spring Security
- 저장소: MySQL (로컬 Docker), JPA + MyBatis
- 인증: Google ID Token 검증, JWT Access Token, Refresh Token Rotation

FCM과 교통 데이터 제공자는 V1 구현 전에 실제 요구사항과 제공 조건을 검증할 예정입니다.

## 시스템 및 저장소 구조

```text
Flutter 앱
  └─ HTTPS / JSON ─> Spring Boot 백엔드 ─> MySQL
                         ├─ JPA: 도메인 CRUD와 상태 관리
                         └─ MyBatis: 복잡한 조회·집계와 교통 데이터 처리
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
