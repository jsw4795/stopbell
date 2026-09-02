# Task List

## Purpose

이 문서는 Codex와 개발자가 StopBell을 단계적으로 구현하기 위한 Task 순서를 정의한다.

Task 상태는 실제 완료 여부를 기준으로 관리한다. 실제 구현 전에 `requirements.md`, `architecture.md`, `database.md`, `domain-model.md`, 관련 ADR을 확인한다.

------------------------------------------------------------------------

# Task Completion Rule

모든 Task 완료 시 다음 절차를 따른다.

1. Task 구현 완료 여부를 검증한다.
2. 관련 Test 또는 실행 확인이 필요한 경우 검증한다.
3. 실제 완료된 경우 `task-list.md`의 해당 Task 상태를 변경한다.

변경 형식:

Before:

```text
- [ ] TASK-XXX
```

After:

```text
- [x] TASK-XXX
```

4. Task 완료 표시와 함께 필요하면 관련 문서 업데이트 여부를 확인한다.

원칙:

- 실제 완료되지 않은 Task는 체크하지 않는다.
- 일부 구현만 완료된 경우 체크하지 않는다.
- 문서 결정 Task와 구현 Task를 구분한다.
- Task 완료 여부는 Git Commit 여부가 아니라 실제 작업 완료 여부 기준으로 판단한다.

------------------------------------------------------------------------

# Task List Maintenance Rule

모든 Task 완료 시 `task-list.md`의 상태를 반드시 업데이트한다.

Workflow:

1. Task 작업 시작
2. 구현 또는 문서 작업 진행
3. 검증 완료
4. `task-list.md`의 해당 Task 상태 변경

Before:

```text
- [ ] TASK-XXX
```

After:

```text
- [x] TASK-XXX
```

5. 변경된 `task-list.md`를 해당 Task Commit에 포함한다.

규칙:

- 완료된 Task는 반드시 `task-list.md`에 완료 표시한다.
- 완료 표시 없는 Task 완료 Commit을 만들지 않는다.
- `task-list.md` 업데이트는 별도의 나중 작업으로 미루지 않는다.
- 실제 완료되지 않은 Task는 체크하지 않는다.

목적:

- Git History와 Task 진행 상태 일치
- 프로젝트 진행 상황 추적 가능
- Codex와 개발자가 동일한 진행 상태 공유

------------------------------------------------------------------------

# Phase 0 - Foundation

목표:

프로젝트 기본 환경과 최소 실행 구조를 준비한다.

- [x] TASK-001 Repository Structure 생성
- [x] TASK-002 Spring Boot 프로젝트 생성
- [x] TASK-003 Flutter 프로젝트 생성
- [x] TASK-004 MySQL 8.4 LTS Docker Compose Development 환경 구성
- [x] TASK-005 JPA / MyBatis 설정
- [x] TASK-006 기본 Application configuration 분리
- [x] TASK-007 Health endpoint 구현
- [x] TASK-008 `.env.example` 또는 동등한 설정 placeholder 작성

------------------------------------------------------------------------

# Phase 1 - Domain Foundation

목표:

JPA를 사용하는 핵심 Domain과 Repository 기반 상태 관리를 준비한다.

- [x] TASK-101 User Entity 구현
- [x] TASK-102 Alarm Entity 구현
- [x] TASK-103 NotificationHistory Entity 구현
- [x] TASK-104 User JPA Repository 구현
- [x] TASK-105 Alarm JPA Repository 구현
- [x] TASK-106 NotificationHistory JPA Repository 구현
- [ ] TASK-107 Alarm status와 상태 전이 규칙 구현
- [x] TASK-108 Database migration 또는 schema 관리 방식 결정
- [ ] TASK-109 Entity 및 Repository Test 작성

Undecided:

- Authentication provider
- User identity의 상세 모델

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
