# 아키텍처

## 1. 아키텍처 목표

향후 개선을 위한 명확한 확장 지점을 남기면서, 현재 StopBell 버전을 신뢰성 있게 지원할 수 있는 가장 단순한 아키텍처를 구축한다.

측정으로 정당화되기 전까지 가상의 대규모 트래픽을 위한 설계를 하지 않는다.

## 2. 초기 시스템 컨텍스트

```text
┌──────────────────────┐
│ Flutter 모바일 앱     │
└──────────┬───────────┘
           │ HTTPS / JSON
           ▼
┌──────────────────────┐
│ Spring Boot 백엔드    │
│                      │
│ - REST API           │
│ - 알림 서비스         │
│ - 교통 모니터링       │
│ - 알림 전송 로직      │
│                      │
│ Persistence Layer    │
│ - JPA                │
│ - MyBatis            │
└───────┬────────┬─────┘
        │        │
        │        ├──────────────► 교통 데이터 API
        │
        ├───────────────────────► 푸시 제공자(FCM 후보)
        │
        ▼
      MySQL
```

## 3. Development Environment

Development Environment는 Runtime Architecture와 구분한다. 이 절은 Local Development에서 MySQL을 실행하고 데이터를 유지하는 방식을 설명하며, Production deployment 환경을 결정하지 않는다.

```text
Flutter App
      ↓
Spring Boot 4.1.1
      ↓
MySQL 8.4 LTS (Docker Container)
      ↓
Docker Named Volume
```

Docker Container는 MySQL Process의 실행 환경이다. Database 데이터는 Docker Named Volume에 저장하므로 Container lifecycle과 Database lifecycle은 분리한다.

Docker Compose configuration은 저장소 루트의 `docker-compose.yml`에서 관리한다.

## 4. Persistence Strategy

StopBell은 JPA와 MyBatis를 함께 사용한다.

```text
Spring Boot
│
├── Domain Layer
│
├── JPA
│   ├── User
│   ├── RefreshToken
│   ├── Alarm
│   └── NotificationHistory
│
└── MyBatis
    ├── Transit Query
    ├── Complex Query
    └── Statistics Query
        ↓
      MySQL
```

JPA는 단순한 Domain CRUD와 Entity 상태 관리가 필요한 영역에서 사용한다. `User`, `RefreshToken`, `Alarm`, `NotificationHistory`는 Repository 기반으로 관리한다.

MyBatis는 복잡한 Query, 집계, 외부 Transit 데이터 처리 등 SQL 제어가 중요한 영역에서 사용할 수 있다. Transit Provider가 검색과 Route별 Stop 조회를 제공하면 이를 우선 사용하며, Local metadata, grouping query, 성능 최적화 등 SQL 제어가 필요한 근거가 확인된 경우에만 MyBatis를 적용한다. 동일한 Bus Route / Bus Stop을 감시하는 Alarm 그룹 조회, Transit 상태 조회, 통계 데이터 조회는 그 대상 예시이다.

## 5. Authentication Architecture

현재 로그인 및 Application API 인증 흐름은 다음과 같다.

```text
Flutter
  ↓ Google Login
Google
  ↓ ID Token
Flutter
  ↓ Google ID Token
Spring Boot Backend
  ↓ Google Token 검증 및 `sub` 확인
User 조회 또는 생성
  ↓
StopBell Access Token (JWT) + Refresh Token 발급
```

Google은 외부 Identity 확인만 담당한다. Google ID Token을 StopBell API의 장기 인증 Token으로 재사용하지 않으며, 이후 Application API는 StopBell이 발급한 JWT Access Token으로 인증한다.

```text
HTTP Request
  ↓ Authorization: Bearer <Access Token>
Spring Security
  ↓ JWT 검증
인증된 StopBell User
  ↓
Controller / Service
```

Controller가 JWT를 직접 parsing하거나 Client가 전달한 `userId`를 신뢰하지 않는다. Alarm을 포함한 사용자 소유 리소스는 인증된 StopBell User를 기준으로 처리한다.

Refresh Token은 SecureRandom으로 생성한 256-bit opaque token이며, 서버가 저장한 SHA-256 Hash와 비교해 Access Token 재발급과 현재 Session Logout에 사용한다. Rotation 시 기존 Token을 삭제하고 새 Access Token과 새 Refresh Token을 함께 발급하며, 새 Refresh Token은 다시 30일 동안 유효하다. Logout은 Access Token 인증 없이 제시된 Token Hash의 Session 하나만 삭제하고 `204 No Content`를 반환한다. Access Token blacklist, Redis 등 추가 인프라는 현재 도입하지 않으므로 이미 발급된 Access Token은 만료 시점까지 유효할 수 있다.

세부 결정과 재검토 조건은 `adr/ADR-005-authentication-and-user-identity-strategy.md`를 따른다.

## 6. 초기 백엔드 경계

권장하는 논리적 모듈/패키지:

```text
user
alarm
transit
notification
common
```

이는 별도 서비스가 아닌 논리적 경계이다.

### user

인증이 도입되면 애플리케이션 사용자 식별과 기기 연결을 담당한다.

### alarm

알림 설정과 알림 생명주기를 담당한다. Alarm Evaluation은 provider-neutral `TransitObservation`을 입력으로 받아 target과 차량 진행을 판단한다. Provider raw field나 외부 호출 실패를 scheduler에 직접 섞지 않는다.

### transit

외부 교통 데이터 제공자와의 통신을 담당하고, 필요할 때 제공자별 데이터를 정규화한다.

V1은 하나의 전국 Provider를 강제하지 않는다. 경기는 TAGO가 Route metadata, Stop metadata, realtime Location, Arrival 보조 정보를 맡고, 서울은 서울특별시 노선정보조회 서비스가 Route/Stop metadata를, 서울특별시 버스위치정보조회 서비스가 realtime Location을 맡는다. 두 Provider의 raw external ID는 provider namespace와 opaque String으로 처리하고, Route number·Stop name·Stop order를 identity로 사용하지 않는다. Provider client/DTO를 구현할 때 이 역할 구분을 따르되 범용 plugin 또는 dynamic provider registry를 만들지 않는다.

선택된 Provider와 identifier 정책의 근거·제약은 `adr/ADR-006-v1-transit-provider-and-external-identifier-strategy.md`를 따른다.

Provider mapper는 raw response를 한 차량의 관측 사실인 `TransitObservation`으로 변환한다. 공통 의미에는 Provider/Route reference, transient vehicle tracking reference, 현재 Stop/진행 순서, 선택적인 위치·시간·방향/구간 문맥, 그리고 `ARRIVED`/`MOVING`/`UNAVAILABLE`로 구분한 직접 도착 근거가 포함된다. Provider에 없는 값을 가짜 값으로 채우지 않는다.

### notification

푸시 알림 요청과 알림 결과 처리를 담당한다.

### common

실제로 공유가 필요한 횡단 관심사를 둔다. `common`을 잡동사니 저장소로 만들지 않는다.

## 7. 모니터링 모델

첫 구현에서는 Spring 스케줄 작업으로 활성 알림을 주기적으로 평가할 수 있다.

다만 다음과 같은 단순한 모델은 피해야 한다.

```text
활성 사용자 알림 하나 = 외부 API 요청 하나
```

여러 알림이 같은 교통 조회에 의존한다면, 요청은 궁극적으로 중복 제거하거나 그룹화해야 한다.

정확한 그룹화 키는 선택한 교통 API에 따라 달라지며 **아직 결정되지 않았다**.

가능한 그룹화 예시:

- 노선 + 정류장
- 정류장만
- 노선 + 방향
- 제공자별 차량/노선 식별자

외부 API의 의미를 이해하기 전에는 하나를 선택하지 않는다.

## 8. 알림 평가

알림 평가는 스케줄러 코드에 묻지 말고, 명시적인 Domain/비즈니스 로직으로 표현해야 한다.

위치 관계와 사용자에게 보낼 Event 후보를 분리한다.

```text
Provider raw response
        ↓ provider별 mapping
TransitObservation
        ↓ 이전 동일 차량 Observation + Alarm Transit Target
위치 관계: BEFORE_TARGET / AT_TARGET / AFTER_TARGET / UNKNOWN
        ↓ tracking lifecycle과 option 적용
Event 후보: ONE_STOP_BEFORE / ARRIVED / PASSED / ONE_STOP_AFTER / 없음
        ↓ 한 transition에서 하나 선택
Notification 전송 결정 또는 UNKNOWN 대기
```

`UNKNOWN`은 Notification Event가 아니라 판단 불가 결과다. 정상 응답 안의 애매한 관측과 timeout·HTTP/provider error 같은 Provider failure는 원인이 다르지만 둘 다 거짓 ARRIVED/PASSED Event를 만들지 않는다.

일반 tracking의 Event precedence는 target에서 도착을 충분히 관찰한 `ARRIVED`, target 이전에서 이후로 건너뛴 `PASSED`, `ONE_STOP_BEFORE` 순이다. ARRIVED 후 동일 차량 follow-up에서는 `ONE_STOP_AFTER`만 평가하며 PASSED로 재분류하지 않는다. Stop order는 같은 방향·Route traversal 문맥에서 비교할 수 있을 때만 사용한다.

### Alarm과 Vehicle Tracking lifecycle

Alarm 활성화 시 현재 Route 차량을 baseline으로 분류한다. Target 이전 차량은 추적 후보이고, before 옵션이 켜진 상태에서 정확히 predecessor인 차량은 즉시 ONE_STOP_BEFORE 후보가 된다. Target 차량은 충분한 근거가 있으면 즉시 ARRIVED이며, 이미 Target 이후인 차량은 기존 passed vehicle로 무시한다.

PASSED는 해당 Vehicle tracking만 종료하고 Alarm은 ACTIVE로 유지한다. ARRIVED는 Alarm 성공 Event이며 Notification 뒤 Alarm을 비활성화하고 다른 Vehicle tracking을 종료한다. after 옵션이 켜진 경우에도 Alarm은 비활성화하되, ARRIVED를 발생시킨 동일 차량만 다음 Stop 도달·통과까지 short follow-up 한다. 따라서 Alarm의 `active`와 follow-up tracking state는 같은 의미가 아니며, 별도 persisted state가 필요한지는 TASK-401/509에서 결정한다.

short follow-up 중 같은 Alarm의 새 activation은 이전 cycle을 supersede한다. 기존 follow-up을 취소하고 새 baseline과 monitoring cycle을 시작한다. Alarm 삭제는 active monitoring과 연결된 short follow-up을 모두 종료한다. 이 lifecycle의 runtime/persistence 구조는 TASK-401/509/510에서 결정하며 Architecture에서 미리 고정하지 않는다.

동일 Alarm·Vehicle·Event Type은 같은 tracking cycle에서 한 번만 의미가 있다. 저장소와 동시성 기반 중복 방지는 TASK-708에서 결정한다.

구체적인 Observation과 Event 의미는 `adr/ADR-007-bus-alarm-transit-observation-and-event-semantics.md`를 따른다.

## 9. 전달 의미론

V1은 분산 푸시 전달을 완벽히 제어할 수 없다는 점을 인정하면서, 알림 발생 건당 사용자에게 보이는 알림을 실용적인 최대 한 번으로 전달하는 것을 목표로 한다.

백엔드는 이미 실행된 일회성 알림에 대해 중복 푸시 요청을 의도적으로 보내지 않도록 충분한 상태를 유지해야 한다.

정확한 트랜잭션 전략은 아직 결정되지 않았다.

## 10. 확장 경로 — 필요한 경우에만

```text
단일 Spring 인스턴스
      ↓
측정된 병목
      ↓
가능한 개선
- 그룹 폴링
- 단기 캐시
- 공유 상태용 Redis
- 알림 팬아웃용 큐/워커
- 여러 백엔드 인스턴스
```

Redis, Kafka, RabbitMQ, Kubernetes, 마이크로서비스는 **기본 요구사항이 아니다**.

## 11. 배포 방향

초기 목표:

- 컨테이너화된 Spring Boot 백엔드
- 영속적인 MySQL 인스턴스
- HTTPS 엔드포인트
- 알림이 활성 상태인 동안 서버가 계속 사용 가능해야 함

정확한 제공자는 아직 정해지지 않았다.

초기 개발에서는 AWS 또는 더 단순한 관리형 플랫폼을 후보로 고려할 수 있다.

## 12. 아키텍처 원칙

1. 규모 과시보다 정확성을 우선한다.
2. 최적화 전에 측정한다.
3. 합리적인 범위에서 외부 제공자 세부 사항을 핵심 알림 규칙과 분리한다.
4. 알림을 유발하는 작업에는 멱등성을 고려한다.
5. 비즈니스 결정은 코드뿐 아니라 문서에도 남긴다.
