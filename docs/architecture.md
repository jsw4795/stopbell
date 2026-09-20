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
│   ├── BusAlarmTarget
│   ├── BusRoute / BusStop / BusRouteStopOccurrence
│   └── NotificationHistory
│
└── MyBatis
    ├── Transit Query
    ├── Complex Query
    └── Statistics Query
        ↓
      MySQL
```

JPA는 단순한 Domain CRUD와 Entity 상태 관리가 필요한 영역에서 사용한다. `User`, `RefreshToken`, `Alarm`, `BusAlarmTarget`, `NotificationHistory`, Bus static metadata는 Repository 기반으로 관리한다. Bus-specific Target은 공통 Alarm table의 nullable column으로 펼치지 않고 Alarm과 공유 PK를 갖는 별도 Entity/table로 관리하며 Alarm aggregate의 persist/remove lifecycle을 따른다. Bus metadata는 source-neutral route snapshot을 한 Route씩 diff sync한다.

MyBatis는 복잡한 Query, 집계, 외부 Transit 데이터 처리 등 SQL 제어가 중요한 영역에서 사용할 수 있다. metadata CRUD와 diff sync는 JPA Entity 상태 관리로 충분하므로 MyBatis를 사용하지 않는다. Route/Stop 검색, Alarm grouping, 성능 최적화에서 실제 SQL 제어 필요성이 확인되면 적용한다.

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

Flutter에서는 login, bootstrap, authenticated HTTP interceptor, logout이 Token을 각각 독립적으로 변경하지 않는다. 하나의 Auth Session 책임이 current Access/Refresh Token Pair, `initializing`/`authenticated`/`unauthenticated` 상태, refresh single-flight, Token Pair 교체와 Secure Storage 반영을 소유한다. Token Pair는 V1에서 pair 단위로 함께 저장하는 것을 기본으로 하며, 구체적인 storage serialization API는 구현 시 결정한다.

startup은 Auth Session을 통해 저장된 Token Pair를 읽고 인증 상태를 복구한다. Access Token 만료 또는 보호 요청의 인증 실패는 같은 session 안에서 refresh를 한 번만 실행하며 concurrent `401`은 single-flight 결과를 공유한다. refresh 성공 뒤 각 보호 요청은 최대 한 번만 재시도한다. `/auth/google`, `/auth/refresh`, `/auth/logout`는 refresh 대상 또는 재시도 대상으로 취급하지 않아 refresh loop에 들어가지 않는다. refresh `401`은 Token Pair를 제거하고 unauthenticated로 전환하지만 network/offline/5xx는 저장된 장기 session을 즉시 제거하지 않는다.

logout과 refresh는 같은 Auth Session coordination으로 직렬화한다. logout은 local session을 종료한 뒤 late refresh 또는 늦은 API 응답이 인증 상태를 되살리지 못하도록 session generation 또는 동등한 보호를 사용한다. 이는 서버에 이미 도착한 요청을 취소한다는 의미가 아니며, Access Token blacklist를 추가하지 않는 기존 Backend 계약도 바꾸지 않는다.

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

V1은 하나의 전국 Provider를 강제하지 않는다. 경기는 TAGO가 Route metadata, Stop metadata, realtime Location, Arrival 보조 정보를 맡는다. 서울 static Route/Stop metadata는 서울 T Data CSV full import를 사용하고, realtime Location은 서울특별시 버스위치정보조회 서비스를 사용한다. 두 지역의 raw external ID는 `TAGO`, `SEOUL_BUS` provider namespace 안의 opaque String으로 처리하고, Route number·Stop name·Stop order를 identity로 사용하지 않는다. Provider client/DTO를 구현할 때 이 역할 구분을 따르되 범용 plugin 또는 dynamic provider registry를 만들지 않는다. 서울 노선정보조회 서비스가 metadata source였던 ADR-006의 초기 결정은 ADR-009에서 T Data CSV로 대체됐다.

realtime Vehicle Location 조회는 `TransitProviderClient<R, C>` contract로 구분한다. Client는 자신이 담당하는 `TransitProvider`를 제공하고 `VehicleLocationRequest<C>`의 opaque `externalRouteId`와 provider별 typed context를 받아 raw response `R`을 반환한다. TAGO context의 `cityCode`는 API request 재현용이며 identity가 아니고, 서울 context에는 TAGO 값을 넣지 않는다. 서울은 Route 전체 조회로 차량 roster와 coarse 상태를 확인한 뒤, polling/orchestration이 필요하다고 고른 차량만 vehicle detail 조회로 `stId`/`stOrd`/`stopFlag`를 확인하는 2단계 방향을 사용한다. Route 전체 응답의 `sectOrd`, `sectionId`, `nextStId`만으로 target Stop occurrence를 판정하거나 다른 operation field와 동치 관계를 만들지 않는다. 상세 조회 대상 선택은 Client나 Flutter가 아니라 후속 polling/orchestration 책임이다. Provider raw DTO는 TASK-502, 실제 Client 호출 구현과 failure 분류는 TASK-503, raw DTO의 `TransitObservation` 변환은 TASK-504에서 각각 맡는다.

Bus static metadata는 서울 T Data CSV full import와 경기 TAGO throttled full sync에서 받아 StopBell DB의 현재 상태로 보관한다. 사용자 Route/Stop 조회와 Alarm 생성은 DB metadata를 사용하고, Alarm 생성 시 필요한 값은 `BusAlarmTarget` snapshot으로 복사한다. Alarm target은 metadata Entity를 FK로 장기 참조하지 않으므로 subsequent sync가 기존 Alarm을 변경하지 않는다.

TASK-513의 source adapter는 서울의 노선마스터·정류장마스터·노선-정류장마스터 CSV와 경기의 TAGO city별 Route·Route Stop pagination을 검증해 normalized metadata snapshot으로 만든 뒤 기존 reconciliation service에 전달한다. 실제 CSV header, encoding, GPS column은 fixture 또는 source 파일을 확인한 구현 시점에 확정한다. source 일부 fetch 실패, parser failure, pagination 미완료, required source 누락은 complete provider snapshot이 아니므로 absence/deletion으로 해석하지 않으며 provider-level cleanup을 실행하지 않는다. 단순 empty list도 complete snapshot으로 자동 간주하지 않는다.

최초 metadata bootstrap은 일반 Backend startup에 강제로 연결하지 않는 명시적 one-shot import/sync 실행을 기본으로 한다. 자동 refresh 주기는 이번 결정에 포함하지 않는다. 단일 Backend에서는 같은 Provider full sync의 동시 실행을 막고, Provider 전체를 하나의 장시간 DB transaction으로 묶지 않는다. 기존 Route 단위 transaction과 complete snapshot 성공 뒤 cleanup을 유지하며, ingestion 구현 시 Route reconciliation의 Stop lazy-loading N+1 여부를 확인·개선한다. Redis, distributed lock, queue는 V1 범위가 아니다.

선택된 Provider와 identifier 정책의 근거·제약은 `adr/ADR-006-v1-transit-provider-and-external-identifier-strategy.md`를 따른다.

Provider mapper는 request Route context, raw Provider response, StopBell이 성공 응답을 받은 직후의 시각을 한 차량의 관측 사실인 `TransitObservation`으로 변환한다. TAGO와 서울 Route realtime item에 externalRouteId가 없을 수 있으므로 request context는 mapper까지 전달한다. 같은 응답의 차량은 같은 receive-time context를 공유하며, `observedAt`은 polling 시작 시각이 아니다. 공통 의미에는 Provider/Route reference, transient vehicle tracking reference, 현재 Stop/진행 순서, 선택적인 위치·시간·방향/구간 문맥, 그리고 `ARRIVED`/`MOVING`/`UNAVAILABLE`로 구분한 직접 도착 근거가 포함된다. Provider에 없는 값을 가짜 값으로 채우지 않는다. HTTP/network timeout, HTTP non-success, Provider logical error, decode/protocol error는 정상 empty와 구분하는 Provider failure이며 빈 차량 목록으로 변환하지 않는다.

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

V1의 기본 Provider polling key는 TAGO의 `(provider, externalRouteId, cityCode)`와 서울의 `(provider, externalRouteId)`다. `cityCode`는 Route identity가 아니라 TAGO request context이지만 동일 polling request 재현에는 필요하다. 같은 Route를 사용하는 여러 사용자·target Stop·ACTIVE Alarm·FOLLOW_UP Alarm은 가능한 한 하나의 Route polling response를 공유한다. Alarm별 Provider 호출이나 MyBatis 도입은 기본 구조로 삼지 않는다.

TASK-510 Scheduler는 단일 Spring instance에서 polling cycle overlap을 막는 단순 synchronous/fixed-delay 방식을 우선한다. Provider HTTP I/O 동안 DB transaction 또는 row lock을 오래 유지하지 않으며, polling 뒤 lifecycle이 바뀐 Alarm을 stale 결과가 덮어쓰지 않게 한다. ARRIVED와 manual deactivate, FOLLOW_UP completion과 reactivation의 race를 안전하게 다뤄야 한다. 구체적인 conditional update, CAS, lifecycle generation token 또는 JPA `@Version` 선택은 구현 전에 비교하며 지금 확정하지 않는다.

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

`UNKNOWN`은 Notification Event가 아니라 판단 불가 결과다. 정상 응답 안의 애매한 관측과 TASK-503에서 구분한 Provider failure는 원인이 다르지만 둘 다 거짓 ARRIVED/PASSED Event를 만들지 않는다. TASK-511은 Provider failure를 Event 없는 UNKNOWN으로 처리하며 Alarm lifecycle을 진행하거나 기존 vehicle tracking state를 즉시 삭제하거나 synthetic PASSED/ARRIVED를 만들지 않는다. retry/backoff와 circuit breaker 도입 여부는 TASK-510/511 구현에서 결정한다.

일반 tracking의 Event precedence는 target에서 도착을 충분히 관찰한 `ARRIVED`, target 이전에서 이후로 건너뛴 `PASSED`, `ONE_STOP_BEFORE` 순이다. ARRIVED 후 동일 차량 follow-up에서는 `ONE_STOP_AFTER`만 평가하며 PASSED로 재분류하지 않는다. Stop order는 같은 방향·Route traversal 문맥에서 비교할 수 있을 때만 사용한다.

### Alarm과 Vehicle Tracking lifecycle

Alarm 활성화 시 현재 Route 차량을 baseline으로 분류한다. Target 이전 차량은 추적 후보이고, before 옵션이 켜진 상태에서 정확히 predecessor인 차량은 즉시 ONE_STOP_BEFORE 후보가 된다. Target 차량은 충분한 근거가 있으면 즉시 ARRIVED이며, 이미 Target 이후인 차량은 기존 passed vehicle로 무시한다.

PASSED는 해당 Vehicle tracking만 종료하고 Alarm은 ACTIVE로 유지한다. ARRIVED는 Alarm 성공 Event이며 after 옵션이 꺼져 있으면 Alarm을 INACTIVE로 전환하고 다른 Vehicle tracking을 종료한다. after 옵션이 켜져 있으면 Alarm을 ONE_STOP_AFTER 전용 FOLLOW_UP으로 전환하고 ARRIVED를 발생시킨 동일 차량만 다음 Stop 도달·통과까지 추적한다. FOLLOW_UP의 차량 tracking ID와 시작·만료 시각은 Alarm에 영속하여 재시작 뒤 복구할 수 있게 한다.

FOLLOW_UP 중 같은 Alarm의 새 activation은 이전 cycle을 supersede한다. 기존 follow-up runtime을 지우고 ACTIVE 상태의 새 baseline과 monitoring cycle을 시작한다. 비활성화·follow-up 완료도 runtime을 지우며 Alarm 삭제는 runtime과 BusAlarmTarget을 함께 제거한다. FOLLOW_UP runtime은 서버 restart 뒤에도 저장된 vehicle tracking ID와 유효 기간으로 재사용한다. 반면 ACTIVE의 차량별 observation/event state는 V1에서 memory 기반일 수 있다. restart 뒤에는 이전 memory tracking을 새 cycle과 연결하지 않고 안전한 recovery baseline을 만들며, restart 전 observation으로 PASSED를 추론하거나 predecessor만으로 ONE_STOP_BEFORE를 재발행하지 않는다. 일부 Event 누락보다 false-positive 방지를 우선하고 모든 raw Provider observation 저장이나 event sourcing은 도입하지 않으며, restart continuity 충족 여부는 TASK-811에서 검증한다.

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
