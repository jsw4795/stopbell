# Task List

## Purpose

이 문서는 Codex와 개발자가 StopBell을 단계적으로 구현하기 위한 Task 순서를 정의한다.

모든 Task는 현재 미완료 상태이다. 실제 구현 전에 `requirements.md`, `architecture.md`, `database.md`, `domain-model.md`, 관련 ADR을 확인한다.

------------------------------------------------------------------------

# Phase 0 - Foundation

목표:

프로젝트 기본 환경과 최소 실행 구조를 준비한다.

- [ ] TASK-001 Repository Structure 생성
- [ ] TASK-002 Spring Boot 프로젝트 생성
- [ ] TASK-003 Flutter 프로젝트 생성
- [ ] TASK-004 MySQL 8.4 LTS Docker Compose Development 환경 구성
- [ ] TASK-005 JPA / MyBatis 설정
- [ ] TASK-006 기본 Application configuration 분리
- [ ] TASK-007 Health endpoint 구현
- [ ] TASK-008 `.env.example` 또는 동등한 설정 placeholder 작성

Undecided:

- Java, Spring Boot, Gradle, Flutter, Dart의 정확한 Version

------------------------------------------------------------------------

# Phase 1 - Domain Foundation

목표:

JPA를 사용하는 핵심 Domain과 Repository 기반 상태 관리를 준비한다.

- [ ] TASK-101 User Entity 구현
- [ ] TASK-102 Alarm Entity 구현
- [ ] TASK-103 NotificationHistory Entity 구현
- [ ] TASK-104 User JPA Repository 구현
- [ ] TASK-105 Alarm JPA Repository 구현
- [ ] TASK-106 NotificationHistory JPA Repository 구현
- [ ] TASK-107 Alarm status와 상태 전이 규칙 구현
- [ ] TASK-108 Database migration 또는 schema 관리 방식 결정
- [ ] TASK-109 Entity 및 Repository Test 작성

Undecided:

- Authentication provider
- User identity의 상세 모델
- schema migration tool

------------------------------------------------------------------------

# Phase 2 - Alarm Feature

목표:

사용자가 Alarm을 생성하고 관리할 수 있는 최소 API와 화면 흐름을 만든다.

- [ ] TASK-201 Alarm 생성 API 정의 및 구현
- [ ] TASK-202 Alarm 목록 조회 API 정의 및 구현
- [ ] TASK-203 Alarm 상세 조회 API 정의 및 구현
- [ ] TASK-204 Alarm 삭제 API 정의 및 구현
- [ ] TASK-205 Alarm 활성화 API 정의 및 구현
- [ ] TASK-206 Alarm 비활성화 API 정의 및 구현
- [ ] TASK-207 Alarm request/response DTO 정의
- [ ] TASK-208 API validation 및 Error response 처리
- [ ] TASK-209 Alarm API Test 작성
- [ ] TASK-210 Flutter Alarm 생성 및 활성 알림 화면 구현

Transit provider가 확정되기 전에는 실제 Bus Route / Bus Stop 선택 흐름을 임의로 구현하지 않는다.

------------------------------------------------------------------------

# Phase 3 - Transit Integration

목표:

실제 Transit 데이터를 조회하고 Alarm 조건을 평가할 수 있게 한다.

- [ ] TASK-301 대상 지역과 Transit API 조사
- [ ] TASK-302 API 약관, rate limit, identifier, update frequency 확인
- [ ] TASK-303 Transit provider client interface 정의
- [ ] TASK-304 Transit API Client 구현
- [ ] TASK-305 provider response DTO 정의
- [ ] TASK-306 StopBell Transit DTO와 `TransitEvent` 변환 구현
- [ ] TASK-307 Bus Route 검색 Query 구현
- [ ] TASK-308 Bus Stop 조회 Query 구현
- [ ] TASK-309 MyBatis Transit Query Mapper 구현
- [ ] TASK-310 Alarm 그룹 조회 Query 구현
- [ ] TASK-311 Alarm Evaluation Logic 구현
- [ ] TASK-312 Scheduler 실행 모델 결정 및 구현
- [ ] TASK-313 Transit API failure를 `UNKNOWN` 상태로 처리
- [ ] TASK-314 Transit Integration Test 작성

Undecided:

- Transit provider
- grouping key
- polling frequency
- arrived 또는 passed 판단 기준
- Transit metadata persistence

------------------------------------------------------------------------

# Phase 4 - Notification

목표:

Alarm 조건 충족 시 실제 기기에 중복 없이 Push notification을 전송한다.

- [ ] TASK-401 Device registration contract 정의
- [ ] TASK-402 Device registration API 구현
- [ ] TASK-403 Push token lifecycle 정의
- [ ] TASK-404 FCM Integration 조사 및 설정
- [ ] TASK-405 Push provider client 구현
- [ ] TASK-406 Notification Service 구현
- [ ] TASK-407 NotificationHistory 저장 구현
- [ ] TASK-408 Duplicate Prevention 전략 결정 및 구현
- [ ] TASK-409 Notification failure 처리 구현
- [ ] TASK-410 실제 기기 Push notification 검증
- [ ] TASK-411 Notification Test 작성

Undecided:

- FCM의 iOS 지원이 제품 요구사항에 충분한지 여부
- Device와 User의 최종 연결 방식
- 정확한 transaction 및 retry 전략

------------------------------------------------------------------------

# Phase 5 - Quality and Operations

목표:

사용자에게 실패를 일으킬 수 있는 동작을 검증하고, 운영에 필요한 최소 품질을 갖춘다.

- [ ] TASK-501 Alarm Evaluation Test 보강
- [ ] TASK-502 Duplicate Prevention Test 보강
- [ ] TASK-503 외부 provider response mapping Test 보강
- [ ] TASK-504 API validation/error handling Test 보강
- [ ] TASK-505 구조화된 Logging 추가
- [ ] TASK-506 Secret 관리 검토
- [ ] TASK-507 Health check 검증
- [ ] TASK-508 Dockerize Backend
- [ ] TASK-509 CI build/test 구성
- [ ] TASK-510 실제 환경에서 notification delay 측정
- [ ] TASK-511 server restart 안전성 검증

Future Consideration:

- CD 도입은 수동 배포 흐름을 이해한 뒤 검토한다.
- Redis, queue/worker, multiple backend instance는 측정된 문제가 있을 때만 검토한다.
