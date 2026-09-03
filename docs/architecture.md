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
│   ├── RefreshToken (Authentication 구현 후)
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

MyBatis는 복잡한 Query, 집계, 외부 Transit 데이터 처리 등 SQL 제어가 중요한 영역에서 사용한다. 동일한 Bus Route / Bus Stop을 감시하는 Alarm 그룹 조회, Transit 상태 조회, 통계 데이터 조회가 대상 예시이다.

## 5. Authentication Architecture

Authentication 구현 후의 로그인 및 Application API 인증 흐름은 다음과 같다.

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

Refresh Token은 서버가 저장한 SHA-256 Hash와 비교하는 opaque token이며, Access Token 재발급에만 사용한다. Rotation 시 새 Access Token과 새 Refresh Token을 함께 발급한다. Access Token blacklist, Redis 등 추가 인프라는 현재 도입하지 않는다.

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

알림 설정과 알림 생명주기를 담당한다.

### transit

외부 교통 데이터 제공자와의 통신을 담당하고, 필요할 때 제공자별 데이터를 정규화한다.

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

개념적으로:

```text
교통 관측값
        ↓
알림 평가
        ↓
NOT_TRIGGERED / TRIGGERED / UNKNOWN
        ↓
알림 전송 결정
```

`UNKNOWN` 또는 동등한 실패 상태는 일시적인 제공자 장애를 유효한 도착 이벤트로 해석하지 않도록 중요하다.

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
