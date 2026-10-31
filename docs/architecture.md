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
└───────┬────────┬─────┘
        │        │
        │        ├──────────────► 교통 데이터 API
        │
        ├───────────────────────► 푸시 제공자(FCM HTTP v1)
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

현재 StopBell V1 persistence는 JPA를 사용한다.

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
│   ├── BoardingSession
│   ├── BusRoute / BusStop / BusRouteStopOccurrence
│   ├── Device
│   ├── NotificationEvent
│   └── NotificationDelivery
│
        ↓
      MySQL
```

JPA는 단순한 Domain CRUD와 Entity 상태 관리가 필요한 영역에서 사용한다. `User`, `RefreshToken`, `Alarm`, `BusAlarmTarget`, `BoardingSession`, `Device`, `NotificationEvent`, `NotificationDelivery`, Bus static metadata는 Repository 기반으로 관리한다. Bus-specific Target은 공통 Alarm table의 nullable column으로 펼치지 않고 Alarm과 공유 PK를 갖는 별도 Entity/table로 관리하며 Alarm aggregate의 persist/remove lifecycle을 따른다. Bus metadata는 source-neutral route snapshot을 한 Route씩 diff sync한다.

현재 구현된 Query는 JPA로 충분하며 MyBatis Mapper, Mapper XML, 전용 production 설정과 starter를 유지하지 않는다. Route/Stop 검색, Alarm grouping, 집계 또는 성능 최적화에서 명시적 SQL 제어의 실제 필요가 확인되면 MyBatis 도입을 다시 결정한다.

### UTC 계약 감사와 구현 책임 (TASK-816 / AUD-12 → TASK-814)

AUD-12 당시에는 기존 User·RefreshToken·Alarm callback의 Host Timezone 의존성과 실제 Java↔Hibernate/JDBC↔MySQL 저장 경계를 확인하고 제품 수정을 TASK-814로 이관했다. 당시 Characterization Test 통과는 결함 재현의 성공이었다. TASK-816은 감사 처리·후속 책임 연결과 회귀 검증을 마쳐 `[x]`이며, 당시 기록은 보존한다.

TASK-814는 A안인 기존 UTC `LocalDateTime`/`DATETIME(6)`을 유지하고 callback 5곳에 `ZoneOffset.UTC`를 명시했다. 기존 UTC Service/Provider/Scheduler/Notification/BoardingSession/Device/metadata 경로와 API·Query·Lifecycle은 유지한다. 공통 Clock Framework·타입 전환·운영 timezone 설정·Migration은 도입하지 않았다. scoped Clock factory 고정으로 실제 callback을 통과시키는 UTC 회귀와 독립 JVM timezone/DST·MySQL 왕복 검증은 [TASK-814 구현 결과](task-list.md#task-814-utc-구현-및-검증-결과-2026-10-30)를 따른다. 현재 저장 의미·기존 값 원문 보존·운영 적용 조건은 [Database](database.md#utc-목표와-현재-저장-경계-task-816--aud-12), 수정 전 재현 근거는 [AUD-12 기록](task-list.md#aud-12-처리-2026-10-30)이 소유한다. AUD-10 로그인 TransactionTemplate/Session 원자성과 AUD-11 Migration 호환성 정책은 유지한다.

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

Flutter에서는 login, bootstrap, authenticated HTTP interceptor, logout이 Token을 각각 독립적으로 변경하지 않는다. 하나의 Auth Session 책임이 current Access/Refresh Token Pair, `initializing`/`authenticated`/`unauthenticated` 상태, refresh single-flight, Token Pair 교체와 Secure Storage 반영을 소유한다. Token Pair와 durable login identity는 `SecureTokenPairStorage`가 OS Secure Storage의 단일 JSON 레코드로 함께 저장한다.

iOS startup은 TASK-704 설치본 marker/secure 레코드를 먼저 확인해 재설치 시 잔존 Keychain Token Pair를 지운 다음 Auth Session을 복구한다. Token Pair에는 재로그인마다 새로 생성하고 rotation/복원 동안 유지하는 durable login identity를 함께 저장한다. startup은 Auth Session을 통해 저장된 Token Pair를 읽고 인증 상태를 복구한다. Access Token 만료 또는 보호 요청의 인증 실패는 같은 session 안에서 refresh를 한 번만 실행하며 concurrent `401`은 single-flight 결과를 공유한다. refresh 성공 뒤 각 보호 요청은 최대 한 번만 재시도한다. `/auth/google`, `/auth/refresh`, `/auth/logout`는 refresh 대상 또는 재시도 대상으로 취급하지 않아 refresh loop에 들어가지 않는다. refresh `401`은 Token Pair를 제거하고 unauthenticated로 전환하지만 network/offline/5xx는 저장된 장기 session을 즉시 제거하지 않는다.

logout과 refresh는 같은 Auth Session coordination으로 직렬화한다. logout은 local session을 종료한 뒤 late refresh 또는 늦은 API 응답이 인증 상태를 되살리지 못하도록 session generation 또는 동등한 보호를 사용한다. 이는 서버에 이미 도착한 요청을 취소한다는 의미가 아니며, Access Token blacklist를 추가하지 않는 기존 Backend 계약도 바꾸지 않는다.

세부 결정과 재검토 조건은 `adr/ADR-005-authentication-and-user-identity-strategy.md`를 따른다.

### Google 최초 로그인 경쟁 복구 (TASK-816 / AUD-10)

`LoginService.login()`은 Spring `PlatformTransactionManager`의 `REQUIRES_NEW` `TransactionTemplate`으로 User 조회/생성·Access Token 생성·Refresh Token 저장을 함께 실행한다. 반환값은 commit 성공 뒤에만 호출자에게 전달한다. Session 저장이나 commit 전 실패 시 신규 User와 Session을 함께 rollback하며 기존 Session은 유지한다. 외부 Transaction이 있으면 중단·복원하고 로그인은 독립적으로 commit/rollback한다. 현재 Controller는 외부 Transaction 없이 호출한다. 별도 transactional component는 추가하지 않으며 annotation self-invocation에 Transaction 경계를 의존하지 않는다.

신규 User의 `saveAndFlush()`에서만 Hibernate의 UNIQUE 종류와 `uk_users_auth_provider_provider_user_id` 제약 메타데이터를 확인한다. MySQL의 `users.` 한정 이름도 허용하며 원문 오류 메시지를 파싱하거나 기록하지 않는다. 제약 식별 실패·다른 제약·연결/Timeout 오류와 조회·Token 발급·commit 단계의 오류는 복구하지 않고 전파한다. Schema/Identity 계약은 [Database](database.md#users)와 ADR-005를 유지한다.

식별된 Identity INSERT 충돌은 내부 예외로 Transaction 밖까지 전파한다. `execute()`의 rollback과 실패 EntityManager 정리가 끝난 뒤 새 `REQUIRES_NEW` Transaction에서 검증된 원래 `(authProvider, providerUserId)`로 한 번만 재조회한다. 반환 User의 두 Identity 값과 양의 내부 ID까지 일치해야 Token을 발급하며, 없거나 불일치하면 원래 DB 오류로 실패한다. 복구는 기존 User 조회만 허용하고 INSERT를 재시도하지 않는다. 복구 중 DB/Token 오류도 그대로 실패하므로 최대 두 Transaction이며 무한 재시도는 없다. 이메일이나 다른 Provider의 동일 문자열 ID로 연결하지 않는다.

동시 로그인 각각은 동일 User를 참조하는 별도 Refresh Session을 저장하며 기존 Rotation·재사용 거부·Session별 Logout 정책을 유지한다. User INSERT를 먼저 별도 commit하는 방식 대신 로그인 전체를 rollback 후 재조회하는 구조로 기존 User/Session 원자성을 보존한다. 실제 MySQL 경쟁·실패 Transaction 종료 검증과 Mock 분기 검증의 구분 및 실환경 미검증 사항은 [TASK-816 AUD-10 처리 기록](task-list.md#task-816-감사-항목과-확인-수준)을 따른다.

### Flutter 인증 HTTP Timeout (TASK-816 / AUD-06)

`BackendAuthClient`의 공통 `_post()`는 `POST /auth/google`·`POST /auth/refresh`·`POST /auth/logout` 각각에 기본 **15초**의 `Future.timeout()`을 적용한다. 생성자의 `timeout`으로 테스트용 제한 시간을 주입할 수 있으며 기존 생성 코드는 기본값을 사용한다. Alarm HTTP와 같은 15초를 사용해 응답 지연 허용 수준을 맞추되, Google SDK의 계정 선택·네이티브 인증 시간은 이 Backend HTTP 예산에 포함하지 않는다. Timeout은 `TimeoutException` 그대로 전달해 HTTP 401·5xx 및 network 오류와 구분하며, 기존 로그인 화면은 민감한 오류 원문 대신 재시도 가능한 로그인 실패 안내로 대기를 끝낸다.

제한 시간은 Token Pair 파싱 이전의 HTTP Future 경계에 적용한다. Timeout 뒤 늦은 성공·HTTP 오류·transport 오류는 이 경계를 통과하지 못하므로 실패한 Login이 새 Pair를 저장하거나 새 login identity를 만들지 않고, 만료된 Refresh가 Pair 교체·저장·보호 API Replay를 실행하지 않는다. 요청별 자동 POST 재전송은 없으며 공유 `http.Client`를 닫지 않는다. 이는 논리적 요청 종료이며 실제 TCP 연결 종료·서버 처리 중단·Rollback을 보장하지 않는다.

Auth Session의 기존 상태 전이는 유지한다. Refresh Timeout은 서버가 Token을 확실히 거부했다는 의미가 아니므로 기존 Pair·Secure Storage·generation·login identity를 유지하고 `_refreshInFlight`를 `finally`로 정리한다. concurrent 401은 HTTP Refresh 한 번을 공유하며, 그 HTTP가 Timeout이면 모든 대기자에게 같은 오류가 전달되고 Replay하지 않는다. 제한 시간 내 정상 Rotation은 새 Pair를 저장하고 generation·identity를 보존해 유효한 요청만 최대 한 번 Replay한다. Refresh 401의 인증 제거, 5xx/network의 일시적 오류 및 Token 저장·삭제 실패 정책은 변경하지 않는다. Alarm 요청 A만 만료되고 공유 Refresh HTTP는 아직 유효하다면 B는 계속 결과를 사용할 수 있는 [AUD-05 요청별 수명](#flutter-alarm-http-요청-수명과-timeout-task-816--aud-05)을 유지한다.

Logout은 기존 `beforeLogout` → generation 무효화 → 진행 중 Refresh 대기 → 확인 가능한 Refresh Token 선택 → Secure Storage 삭제 → 메모리 인증 제거 → Backend Logout 순서를 유지한다. Refresh HTTP 무응답은 그 요청의 남은 15초 예산 뒤 대기를 해제한다. 그 전에 Rotation이 성공하면 기존 `_rotatedRefreshTokenForLogout`으로 새 Token을 revoke하고 Pair를 저장하지 않는다. Backend Logout 무응답은 별도 15초 뒤 `BackendLogoutCleanupException`으로 전달하며 로컬 비인증 상태를 유지하고 `_logoutInFlight`·`_loggingOut`을 정리해 새 Login이 가능하다. 기존 Device API의 8초·`PushRegistrationService.beforeLogout`의 queue 대기 포함 10초는 별도 예산이다. Hook 10초 + Refresh HTTP 잔여 최대 15초 + Logout HTTP 15초의 대기가 누적될 수 있으며, Hook 일반 구현·Secure Storage·Google SDK 자체의 무응답은 이 변경의 보장 밖이므로 전체 Logout의 15초 또는 40초 종료를 보장하지 않는다.

서버가 Refresh Token을 이미 회전시킨 뒤 응답이 유실되면 기존 Token이 무효화됐을 수 있다. Timeout 뒤 Pair 보존은 Token 유효성 보장이 아니며, 별도 복구 계약이 없으므로 Token 합성·추측·자동 재전송을 하지 않는다. 이후 Logout은 확인 가능한 기존 Token만 제시하며, Backend는 제시된 Hash의 Session 하나만 삭제하고 없는 Token도 204로 처리하므로 알 수 없는 새 Token의 서버 측 revocation을 보장할 수 없다. Logout 자체의 Timeout도 서버 정리 성공 또는 확실한 실패로 단정하지 않는다. 자동 재현·회귀 결과와 실환경 미검증 사항은 [TASK-816 처리 기록](task-list.md#task-816-감사-항목과-확인-수준)에 남긴다.

### Flutter 도착 Alarm 최초 생성과 Push 준비 (TASK-816 / AUD-01)

`main.dart`의 기존 `prepareAlarmPush()` 콜백을 `AlarmHome`이 상세 화면과 `BusRouteStopScreen`에 전달한다. 최초 `알람 시작`은 중복 입력을 막은 뒤 Push 준비·사용자 선택 → Create → Activate 순서로 실행하며, 같은 동작의 Activate 직전에는 준비를 반복하지 않는다. 미결정 권한은 도착 알림 안내 뒤 사용자가 선택할 때만 요청하고, 취소·Dialog 닫기는 Create 전에 종료한다. 권한 거부·기기 등록 실패는 기존 설정 안내와 명시적 계속 진행/취소를 유지하며 Alarm 사용 자체를 강제 차단하지 않는다. 콜백이 없는 환경의 기존 생성 계약과 BoardingSession 준비 UX는 유지한다.

준비 완료 뒤와 각 API 응답 뒤에는 화면 수명·요청 generation·선택 Route/occurrence·Client 및 `AlarmHome`의 Auth generation·User/login session identity·navigation epoch를 재검증한다. Logout/계정 전환·화면 이동·Route 교체로 무효화된 요청은 후속 Create/Activate나 상세 이동을 실행하지 않는다. 이미 서버에 전달된 요청을 취소한다는 의미는 아니다. 준비 단계 예외는 재시도 가능한 준비 오류이며 Create 결과 불명확과 구분한다. Create 성공 뒤 Activate 실패는 원래 ID를 보존하고 아래 TASK-804 3단계의 미확인 상태·GET 복구를 사용한다. 수동 활성화는 현재 상태 확인 후 다시 Push를 준비한다. Create 결과 불명확은 자동 POST 재전송 없이 목록 확인으로 복구하는 TASK-608B 계약을 유지한다.

### Flutter Alarm HTTP 요청 수명과 Timeout (TASK-816 / AUD-05)

`AlarmApiClient`의 Create·목록·상세·Activate·Deactivate·Delete는 공통 `_request()` 경계를 사용한다. 각 논리적 요청은 독립적인 `active`와 시작 시 Auth generation을 보존하고, 기존 `AuthenticatedApiClient.request()`에 `expectedGeneration`과 `isRequestCurrent`를 전달한다. 유효성은 해당 요청의 active·현재 Auth generation·Logout 진행 여부로 검사한다. 최초 HTTP 요청·공유 Refresh 대기·최대 1회 Replay 전체에 기존 **15초 Timeout을 한 번만** 적용하며 단계마다 예산을 초기화하지 않는다. 성공·실패·Timeout으로 경계를 벗어날 때 `finally`에서 해당 요청만 무효화한다.

기존 `AuthenticatedApiClient`는 최초 응답 수신 직후 Refresh 시작 전, Refresh 완료 후 Replay 전, Replay 응답 수신 후에 generation과 요청 유효성을 검사한다. 따라서 Timeout 뒤 늦은 401은 새 Refresh/Replay로 이어지지 않고, 이미 Refresh가 시작됐어도 만료된 요청의 Replay는 차단한다. 한 요청의 Timeout은 Auth Session이나 공유 Refresh를 취소하지 않으며, 다른 유효한 요청은 동일 Refresh 결과를 계속 사용한다. Token rotation·Secure Storage·login identity·Refresh 401의 인증 실패 정책과 제한 시간 내 정상 401의 최대 1회 Replay는 기존 Auth Session 계약을 유지한다.

TimeoutException은 기존대로 호출자에게 전달한다. 늦은 성공을 새 UI 성공이나 후속 Activate로 반영하지 않으며, Create Unknown Outcome의 목록 확인·자동 Create 금지와 Create 성공/Activate 실패 시 원래 ID로 복구하는 UX를 유지한다. 이미 서버에 전달된 Mutation의 실행 취소·Rollback이나 물리적 HTTP 연결 종료를 보장하지 않는다. BoardingSession·Device의 기존 요청 유효성 패턴은 변경하지 않으며 인증 HTTP 자체의 예산은 [AUD-06](#flutter-인증-http-timeout-task-816--aud-06)을 따른다. 재현·선택 테스트와 실환경 미검증 사항은 [TASK-816 처리 기록](task-list.md#task-816-감사-항목과-확인-수준)을 따른다.

### Flutter Alarm 응답 유실 복구 (TASK-804 3단계)

`BusRouteStopScreen`은 생성 전 Push 준비 → Create → Activate 순서를 유지한다. 생성 결과 불확실은 기존 `_outcomeUnknown`으로 생성·선택·옵션 변경과 자동 재전송/활성화를 차단한다. HTTP 오류의 확정 거부 조합과 생성 요청 identity 부재의 한계는 [API](api.md#알림-생성)가 소유한다. 목록에서 유사한 Alarm을 골라 성공으로 간주하지 않는다.

생성 ID를 확보한 뒤 Activate 실패는 `onCreated`의 `activationUnverified`를 `AlarmHome`을 통해 상세에 전달한다. 상세는 생성 당시 INACTIVE를 최신 상태로 표시하지 않고 같은 ID로 GET한다. 상세 Activate/Deactivate/Delete 결과가 불확실하면 추가 Mutation을 잠그고 같은 ID를 한 번 GET한다. GET 성공은 조회 시점 상태만 반영하고, 실패는 상태 확인 필요·명시적 재조회로 복구한다. FOLLOW_UP 재활성화도 확인 전 반복하지 않는다. 삭제 뒤 GET 404는 부재/접근 불가 안내와 사용자 목록 선택이며 DELETE 성공으로 해석하지 않는다. HTTP 성공 상태와 오류 의미는 [API](api.md#flutter-alarm-mutation-결과-확인-task-804-3단계)를 따른다.

`AlarmHome`의 기존 Auth generation·User/login identity·navigation epoch를 상세의 `isRequestCurrent`에도 전달해 logout/계정 전환이나 알림 선택이 화면 dispose보다 먼저 일어나도 후속 Mutation/GET/UI/Navigation을 막는다. 상세는 자체 request generation·Alarm ID·Client identity·mounted를 각 await 뒤에 확인하고, ID/Client 교체와 dispose에서 이전 요청을 무효화한다. GET 중 중복 조회와 Mutation을 직렬로 차단해 오래된 조회가 새 Mutation을 덮지 못하게 한다. Push 준비 실패는 HTTP 미전송 상태이므로 재시도 가능하며, 삭제 확인 Dialog 뒤에도 같은 수명을 확인한다. 기존 `AuthenticatedApiClient`/`AuthSession`과 Timeout 이후 늦은 401의 Refresh/Replay 차단 정책은 변경하지 않는다. 이미 전송한 서버 작업의 취소나 서버 수준의 생성 멱등성을 보장하는 경계는 아니다.

### Alarm 생성 멱등성 실행 설계 (TASK-802, 미구현)

후속 구현에서 인증과 형식/정규화 검증 후 `(userId, clientRequestId)`를 먼저 조회하고 fingerprint를 비교한다. 기존 기록이면 현재 metadata 생성 검증을 건너뛰어 [API 결과](api.md#alarm-생성-멱등성-확정-계약-task-802-2단계-미구현)를 반환한다. 없으면 별도 짧은 쓰기 transaction에서 기존 metadata validation, Alarm과 요청 기록 저장 및 flush/commit을 원자적으로 수행한다. 생성된 Alarm만 또는 기록만 commit되지 않는다. DB UNIQUE가 최종 경계다. 동일 ID 경쟁에서 패자는 자신의 Alarm을 포함한 transaction 전체를 rollback한다. constraint 이름과 MySQL duplicate-key 원인으로 **해당 UNIQUE 위반만** 식별하고 transaction 종료 뒤 새 transaction과 persistence context에서 정확한 User/ID를 조회·비교한다. 다른 무결성 오류는 그대로 오류 처리하며 무한 재시도하지 않는다. 승자의 rollback, lock timeout/deadlock, commit 불명확 또는 새 조회에 기록이 없는 경우 성공으로 꾸미지 않고 오류/불확실로 남긴다. 이후 Client가 원래 ID와 body로 명시적 재시도할 수 있다. 다른 User의 같은 ID는 독립이다. Blind upsert, INSERT IGNORE, JVM lock이나 실패 transaction 재조회에 의존하지 않는다.

기록 재조회와 cleanup은 해당 요청 row의 lock을 사용해 직렬화한다. 결과 조회는 해당 User의 Alarm을 읽고 삭제와 경합하면 조회 시점의 상태 또는 Gone을 반환한다. 삭제는 기존 Alarm lock/lifecycle 및 Notification 정책을 유지한다. 기록은 Alarm과 FK 없이 남는다. Commit 성공 후 응답 유실은 GET 또는 같은 POST로 복구한다. rollback에는 둘 다 없으므로 같은 ID로 현재 metadata를 다시 검증해 생성할 수 있다. GET 404는 진행 중인 최초 POST를 배제하지 못한다. UNIQUE 경합, 예외 식별, 격리 수준 및 cleanup과 삭제 경합은 실제 MySQL 테스트로 검증한다.

Flutter는 사용자 확정 → Push 준비 → `Random.secure` UUID v4 한 번 생성 → 원본 body와 User/login Session identity를 Secure Storage에 저장하고 성공 확인 → POST 순서다. `newBoardingRequestId`의 난수/비트 설정과 `SecureBoardingIntentStorage`의 session별 key 및 비동기 직렬화 패턴을 재사용할 수 있으나 Boarding 도메인 payload는 공유하지 않는다. 현재 `AuthSession`은 token 저장의 session identity를 재시작·refresh에 유지하고 재로그인 때 새로 발급한다. 각 await 뒤 기존 auth generation·User/session identity·navigation epoch를 확인하며 다른 session 기록을 복구하지 않는다. 같은 session 내 이전 정리가 새 intent를 삭제하지 않도록 저장·삭제를 직렬화하고 ID 비교 후 삭제한다.

정상 생성 또는 GET 확인 시 원래 Alarm ID를 사용하고 생성 intent 정리를 확인한다. 정리 실패 시 결과를 보존하고 정리 재시도를 제공하며 새 생성을 막는다. 불확실 응답, 5xx, timeout, 잘못된 응답, GET 404/실패는 intent를 유지하고 자동 POST·새 UUID·자동 활성화를 금지한다. 명시적 재조회 또는 원본 ID/body 재시도를 제공한다. 410은 삭제된 결과로 표시하고 정리 뒤 사용자가 명시적으로 새 생성할 때만 새 ID를 발급한다. 명백한 생성 전 거부도 안전하게 정리한 후 재선택한다. 로컬 저장에는 token, credential, FID, provider 응답을 넣지 않는다.

장기 미사용 intent는 보존 만료 후 재전송으로 중복될 수 있다. 로컬 생성 시각을 저장해 30일 이상이거나 시계 신뢰성이 불명확하면 재전송을 차단하고 사용자에게 결과 불확실과 중복 가능성을 안내한다. 로컬 시계는 서버 보존의 증명이 아니므로 30일 미만이어도 절대 보장을 주장하지 않는다. 생성 결과 확인은 활성화 성공의 증거가 아니다. 기존 Push 준비 → 생성 → 활성화 순서를 유지하고, 복구 후 활성화 여부를 자동 추정하지 않으며 TASK-804의 원래 Alarm ID 조회/명시적 복구를 따른다. 생성 ID를 activate/deactivate/delete의 멱등성 ID로 사용하지 않는다.

### Flutter 하차 알림 후보 조회 (TASK-907 1단계)

로그인 뒤 기존 `AlarmHome` 목록에서 버스 도착 알림과 별도의 하차 알림 진입점을 제공한다. 같은 home의 화면 전환을 사용해 기존 Alarm detail/notification tap 진입점을 유지한다. `BoardingHome`은 기존 Route 검색 화면과 Client를 재사용하고, `BoardingStopScreen`은 기존 Stop Client의 occurrence 목록에서 필수 목적·선택 승차 지점을 선택한다. 도착 Alarm 전용 전후 알림 옵션은 공유하지 않는다.

`BoardingCandidateClient`는 기존 `AuthenticatedApiClient`/Auth Session refresh와 generation을 사용한다. 화면의 Route ID·목적/승차 occurrence 변경, 재조회, 화면 폐기에는 요청 epoch를 무효화하며 logout 시작/계정 세대 변경에는 후보·선택 문맥을 제거한다. 미전송 후보 reference는 화면 메모리에만 두고 서버 추천 순서와 관측 시각을 보존한다. 2단계 생성 요청의 원래 reference 보존은 아래 pending intent 경계를 따른다. 후보 상태·불확실성의 의미는 [후보 API](api.md#후보-조회)를 따르며 unknown enum은 미확인 상태로 표시한다.

1단계의 후보 조회·비교와 2단계의 확정·세션 관리가 구현됐다. 차량 선택 접수와 진행 검증·추적 지원을 구분하고 실제 `BOARDING_TRACKING_UNSUPPORTED` 지원 제한을 안내한다. 3단계의 세션 FCM 수신/tap 구현과 검증 경계는 아래 별도 절을 따른다. 새 Dependency, GPS/위치 권한, polling이나 후보 목록의 영속 저장은 추가하지 않는다.

### Flutter 탑승 확정과 세션 관리 (TASK-907 2단계)

`BoardingHome`의 화면별 `BoardingSessionController`가 기존 Auth Session과 `BoardingSessionApiClient`를 사용해 생성·복구·현재 세션·상세·취소·재확인을 조율한다. 후보 선택은 POST가 아니며 번호 입력 없는 차량 선택·명시적 등록 요청 뒤 성공 응답의 실제 status/evaluationStatus를 표시한다. Push 준비 중 후보가 만료되면 selectionKey가 같은 유일 후보만 재조회해 새 생성 의도를 준비한다. 선택 키가 없거나 ID/번호가 달라지면 재선택을 요구하고 다른 차량을 대체하지 않는다. 후보 문맥 변경·재조회·화면 이탈에는 미전송 초안을 폐기하고 불변 1/2정거장 전 알림 시점을 생성 의도에 함께 보존하고 최초 전송 전에는 저장 대기까지 포함해 원래 관측의 60초 freshness를 재확인한다. 최신 Provider/목적/운행 검증과 지원 여부의 최종 책임은 Backend에 있다.

생성 의도마다 `Random.secure` 기반 UUID v4를 한 번 발급한다. `SecureBoardingIntentStorage`는 기존 flutter_secure_storage에 원래 UUID·candidateRef·응답 식별 검사용 선택 차량번호·알림 시점(이전 intent는 기존 ON/OFF canonical 값 유지)·User/login session identity·정확한 Route/목적/선택 승차 ID·관측 시각만 저장한다. Token·FID·Provider raw response는 복제하지 않는다. login session별 key를 사용해 이전 비동기 정리가 다른 로그인 레코드를 삭제하지 않게 한다. 같은 key의 저장·조회·삭제는 화면 instance 간에도 직렬화해 저장 도중 이탈한 화면의 요청을 재진입 화면이 놓치지 않게 한다. write 완료 전에는 POST하지 않으며 저장 실패는 미전송 오류다. 이는 생성 응답 유실 복구용 최소 레코드이며 범용 offline queue가 아니다.

생성 상태는 미시작/전송 중/결과 불확실/해결됨을 구분한다. timeout·통신·파싱·5xx 또는 해석할 수 없는 응답은 원래 identity를 보존한다. 새 UUID/body로 대체하지 않고 `clientRequestId` GET으로 먼저 복구한다. 재진입·프로세스 재시작도 같은 User/login session인 레코드만 조회하며 자동 POST는 하지 않는다. 동시 생성의 영속 결과는 같아도 생성 commit의 runtime 공개 경계에 따라 순간적인 READY/RECOVERY_PENDING과 gap은 다를 수 있다. 조회 0개는 처리 중일 수 있으므로 불확실 상태를 유지하고, 사용자의 명시적 재시도만 동일 UUID/body로 허용한다. 불확실했던 재시도의 4xx도 이전 POST 실패의 증거로 사용하지 않는다. 정상 응답 또는 UUID 조회로 terminal을 포함한 세션을 확인한 뒤 레코드를 정리하며 정리 실패 중에는 새 생성 의도를 막는다. 이전 로그인 레코드가 Keychain에 남아도 새 로그인에서는 복구·재전송·표시하지 않는다.

진입 시 pending 복구를 우선하고, 없으면 `scope=current`로 미종료 세션을 복원한다. 조회 오류는 세션 없음이 아니며 재조회 전 생성을 차단한다. 상세는 명시적 새로고침만 제공하고 주기 polling을 추가하지 않는다. READY인 TRACKING에서만 검증 간격을 표시하며 나머지 상태나 변경 결과 불확실 중에는 과거 gap을 숨긴다. 취소는 사용자 확인 뒤 현재 revision으로 요청하고, 실제 반환된 terminal 상태를 보존한다. mutation 응답 유실/충돌은 상세 GET으로 확인하며 재조회도 실패하면 추가 mutation을 막는다. 조회 epoch와 mutation 전후 무효화, revision·Session ID·전체 차량번호·terminal 검증으로 늦은 GET이 새 상태를 되돌리지 않게 한다.

재확인은 같은 Session에서 새 후보 조회와 목록 선택으로 시작한다. 전체 번호를 입력하지 않는다. 같은 Auth Session 안에 정확한 선택 ID가 남아 있으면 그 문맥으로 조회하고, 없거나 다시 선택이 필요하면 사용자가 Route/occurrence를 재선택한다. response의 routeNumber/targetName/order로 내부 ID를 추측하지 않는다. 다른 차량 번호는 Client에서도 거부하며 같은 차량/경로/운행의 최종 검증은 Backend가 수행한다. 모든 비동기 반영과 늦은 401 refresh/replay는 Auth generation·login/User identity·화면 수명으로 보호한다. 성공 경로는 Fake/Mock으로 검증하며 실제 Provider 지원 보류를 해제하지 않는다.

### Flutter 세션 알림 수신과 상세 이동 (TASK-907 3단계)

앱에 하나인 `NotificationLifecycle`이 기존 Firebase `onMessage`/`onMessageOpenedApp`/`getInitialMessage`를 소유한다. `AlarmNotification`과 `BoardingSessionNotification`은 [기존 payload](api.md#탑승-세션-push와-tap)를 source별로 검증하며 다른 source ID 필드가 함께 있으면 거부한다. source·대상 ID·eventType·notificationEventId와 foreground/tap 목적을 조합해 최근 64개 키만 메모리에 보존한다. messageId만으로 중복을 판단하지 않으며 foreground 안내 뒤 명시적 상세 선택은 허용한다. User/login Session 변경에는 pending hint와 dedupe를 지운다. 영속 inbox는 추가하지 않는다.

Foreground는 source에 맞는 작은 안내와 상세 보기 선택만 제공한다. 수신만으로 이동하지 않는다. Background tap과 terminated initial은 현재 인증 문맥에서 상세로 연결한다. 초기 메시지 Future는 한 번 조회해 초기화 timeout 재시도에도 재사용하며 listener를 중복 등록하지 않는다. Secure 설치본 bootstrap → Auth 복원 → Firebase 초기화 → 수신 연결 순서를 유지한다. initial이 먼저 준비됐으면 진행 중인 Auth 복원이 끝나기를 기다리되 최초 복원된 User/login Session에만 연결한다. 미인증 시작이나 이후 로그인·로그아웃·A→B→A에는 이전 initial/tap을 이전하지 않는다. 기존 Alarm의 로그인 전 tap 보류도 이 fail-closed 경계로 강화했다. 화면이 늦게 구성되면 같은 인증 문맥의 pending tap을 첫 frame 뒤 소비한다.

`AlarmHome`이 두 source의 navigation epoch와 인증 generation·User/login identity를 확인한다. `BoardingNotificationScreen`은 `BoardingSessionApiClient`/`AuthenticatedApiClient`로 정확한 `GET /api/v1/boarding-sessions/{id}`만 호출한다. `scope=current`나 생성 복구를 호출하지 않아 다른 open Session을 대신 표시하지 않으며 Secure pending intent를 읽거나 삭제하지 않는다. 응답 ID를 재검증하고 `BoardingSessionDetails`의 서버 상태 표현을 관리 화면과 공유한다. 종료·취소·만료도 표시하고 READY가 아닌 과거 gap은 숨긴다. 이 진입점은 읽기 전용이며 create/cancel/reconfirm을 실행하지 않는다. 404는 부재/다른 owner를 구분하지 않고, 통신·파싱 오류에는 같은 ID 재조회를 제공한다. 다른 알림 선택·홈 복귀·dispose·로그아웃에는 늦은 응답을 폐기하고 이전 로그인 Snackbar도 정리한다. Backend가 실제 owner를 검증하며 payload 자체는 인증/추적 근거가 아니다.

차량 등록은 명시적 목록 선택 뒤 기존 `PushRegistrationService`/permission/Device 등록을 준비하고 UUID 생성·Secure intent 저장·POST보다 먼저 사용자 선택을 마친다. 미결정 권한은 설명 후 요청하며 거부·등록 실패·미지원 환경은 수신 불가 가능성과 과거 Event recipient가 나중에 늘어나지 않는다는 점을 안내하고 명시적인 `알림 없이 계속`만 허용한다. 준비 성공도 실제 표시나 추적 지원 성공은 아니다. 준비 중에는 중복 확정을 막고 종료 뒤 후보 freshness/Auth/화면 수명을 재검증한다. 준비 실패·이탈은 새 UUID/POST를 만들지 않으며, 이미 불확실한 POST 복구·재시도는 Push 준비와 분리해 원래 UUID/body를 보존한다. 기존 Alarm 활성화 정책은 유지한다.

Fake 수신 callback과 Mock/Fake API로 자동 검증한다. 실제 OS terminated 실행·Firebase/iPhone 표시·실제 버스 E2E는 이 검증의 보장 범위가 아니며 TASK-908과 기존 TASK-710/711에 남는다. Provider별 조건부 지원 범위는 API 계약을 따르며 OS 표시 중복/지연·접수 후 회수 불가 한계를 유지한다.

## 6. 초기 백엔드 경계

권장하는 논리적 모듈/패키지:

```text
user
alarm
boarding
transit
notification
common
```

이는 별도 서비스가 아닌 논리적 경계이다.

### user

애플리케이션 사용자 식별과 Authentication Session을 담당한다. RefreshToken과 Push Device는 직접 연결하지 않는다.

### alarm

알림 설정과 알림 생명주기를 담당한다. Alarm Evaluation은 provider-neutral `TransitObservation`을 입력으로 받아 target과 차량 진행을 판단한다. Provider raw field나 외부 호출 실패를 scheduler에 직접 섞지 않는다.

### boarding

독립 BoardingSession의 snapshot·checkpoint·수명·revision과 생성·조회·취소, 특정 차량 진행 판정을 담당한다. Transit 후보 검증을 재사용하고 기존 Alarm 생명주기는 유지한다. 현재 구현된 추적·판정·복구·Notification 연결의 경계는 [탑승 세션 실행](#탑승-세션-실행과-notification-연계-task-903)을 따른다.

### transit

외부 교통 데이터 제공자와의 통신을 담당하고, 필요할 때 제공자별 데이터를 정규화한다.

V1은 하나의 전국 Provider를 강제하지 않는다. 경기는 TAGO가 Route metadata, Stop metadata, realtime Location, Arrival 보조 정보를 맡는다. 서울 static Route/Stop metadata는 서울 T Data CSV full import를 사용하고, realtime Location은 서울특별시 버스위치정보조회 서비스를 사용한다. 두 지역의 raw external ID는 `TAGO`, `SEOUL_BUS` provider namespace 안의 opaque String으로 처리하고, Route number·Stop name·Stop order를 identity로 사용하지 않는다. Provider client/DTO를 구현할 때 이 역할 구분을 따르되 범용 plugin 또는 dynamic provider registry를 만들지 않는다. 서울 노선정보조회 서비스가 metadata source였던 ADR-006의 초기 결정은 ADR-009에서 T Data CSV로 대체됐다.

realtime Vehicle Location 조회는 `TransitProviderClient<R, C>` contract로 구분한다. Client는 자신이 담당하는 `TransitProvider`를 제공하고 `VehicleLocationRequest<C>`의 opaque `externalRouteId`와 provider별 typed context를 받아 raw response `R`을 반환한다. TAGO context의 `cityCode`는 API request 재현용이며 identity가 아니고, 서울 context에는 TAGO 값을 넣지 않는다. 서울은 Route 전체 조회로 차량 roster와 coarse 상태를 확인한 뒤, polling/orchestration이 필요하다고 고른 차량만 vehicle detail 조회로 `stId`/`stOrd`/`stopFlag`를 확인하는 2단계 방향을 사용한다. Route 전체 응답의 `sectOrd`, `sectionId`, `nextStId`만으로 target Stop occurrence를 판정하거나 다른 operation field와 동치 관계를 만들지 않는다. 상세 조회 대상 선택과 여러 vehicle detail 결과 조합은 Client가 아니라 TASK-508~510 polling/orchestration 책임이고, 실패 vehicle은 TASK-511에서 Event 없는 `UNKNOWN`으로 처리한다. TASK-503은 route roster 호출과 개별 vehicle detail 호출 각각의 raw success/normal empty와 timeout/network, HTTP, Provider logical, decode/protocol failure를 정확히 구분하며 client 내부 무제한 retry나 generic partial/batch result framework를 만들지 않는다. 기존 `TransitProviderClient<R, C>` contract는 유지한다. Provider raw DTO는 TASK-502, 실제 Client 호출 구현과 failure 분류는 TASK-503, raw DTO의 `TransitObservation` 변환은 TASK-504에서 각각 맡는다.

TASK-503은 Spring `RestClient`와 JDK HTTP client로 TAGO `getRouteAcctoBusLcList`, 서울 `getBusPosByRtidList` roster, 서울 `getBusPosByVehIdItem` detail을 각각 호출한다. `transit.client.connect-timeout`과 `transit.client.response-timeout`은 기본 `PT2S`와 `PT5S`로 제한하며, Provider별 base URL/service key는 `transit.client.tago` 및 `transit.client.seoul` 설정에서 주입한다. TAGO metadata bootstrap은 realtime보다 응답이 느릴 수 있어 별도 `transit.client.metadata-response-timeout` 기본 `PT30S`만 사용하며 realtime의 `PT5S` 제한을 변경하지 않는다. TAGO `resultCode=00`의 빈 item과 서울 `headerCd=4` 결과 없음은 정상 empty이고, 그 밖의 Provider code는 `PROVIDER`, HTTP non-success는 `HTTP`, network/timeout은 `TRANSPORT`, decode·필수 envelope 누락은 `PROTOCOL`인 하나의 typed client exception으로 전달한다. 서울 detail의 raw DTO는 roster DTO와 분리하며 `vehId`, `plainNo`, `stId`, `stOrd`, `stopFlag`, `dataTm`, `tmX`, `tmY`만 보존한다.

서울 `ws.bus.go.kr` API의 공통 origin은 `http://ws.bus.go.kr`이며 개발·운영·테스트에 같은 설정을 적용한다. 서울 Provider 설정은 HTTP scheme, 공식 host, 기본 port 또는 80, query/userinfo/fragment 없는 origin만 허용하며 HTTPS override도 거부한다. 테스트 profile에는 별도 Base URL을 중복 정의하지 않는다. 실제 path는 roster `/api/rest/buspos/getBusPosByRtid`, detail `/api/rest/buspos/getBusPosByVehId`로 고정하며 논리 operation 명칭과 구분한다. HTTPS 실패 후 HTTP fallback이나 임의 host HTTP 허용은 없다. 서울용 JDK HTTP client는 redirect를 명시적으로 금지하고 기존 connect/response timeout을 유지한다. TAGO 및 다른 외부 Client 설정과 앱→Backend HTTPS 정책은 변경하지 않는다.

서울 공식 HTTP 이용은 Backend→Provider 구간의 service key와 응답이 네트워크에서 노출·변조될 위험을 수용하는 정책이다(2026-10-28 사용자 확정). 이 위험을 이유로 운영 profile에서 서울 Provider를 차단하지 않는다. Key는 Backend secret 설정으로만 관리하고 Flutter/payload/API 응답에 포함하지 않으며, 전체 요청 URI/query·원문 Key·응답 원문·민감한 exception cause를 로그하지 않는다. 서울은 XML 응답을 우선 요청하고 기존 JSON 응답도 수용한다. JDK XML parser는 DTD·외부 entity·외부 schema 및 XInclude를 차단하고 기존 typed DTO/envelope·Provider code 검증을 유지한다. HTTP 설정은 차량 추적 지원 여부와 별개이며 진행 검증 실패는 API 계약대로 거부한다.

현재 TAGO realtime Client는 `pageNo=1`, `numOfRows=1000`을 명시하고 `totalCount`가 없거나 음수이거나 실제 item 수와 다르면 `PROTOCOL` 실패로 전달한다. 추가 페이지 수집 구현은 아니며, 성공 코드의 empty도 `totalCount=0`과 실제 0건이 일치해야 한다. 개수 검증을 item별 식별·진행 정보의 유효성 보장으로 확대하지 않는다.

후보 생성·선택과 서버 차량 식별 경계는 [ADR-011](adr/ADR-011-bus-vehicle-recommendation-and-user-confirmation.md)을 따른다. TASK-904의 `BoardingCandidateService`는 기존 Transit Client/DTO와 DB metadata를 사용하며, 별도 `BoardingCandidateMapper`에서 서울 vehId/plainNo를 함께 보존하고 요청/응답 ID·roster/detail 번호 대응을 검증한다. 기존 Alarm용 Mapper/Observation/Evaluator는 변경하지 않는다. `BoardingCandidateMetadataService`는 read-only transaction에서 불변 traversal을 읽고 Provider I/O는 transaction 밖에서 수행한다. TASK-905 1단계의 세션 API는 이 검증 경로를 재사용하며 실제 지원 보류 범위는 [세션 API](api.md#탑승-세션-api-task-903)를 따른다. 지속 추적의 현재 구현 경계는 아래 탑승 세션 실행 절을 따르며 Flutter는 번호 입력 없는 목록 선택·등록 화면을 사용한다.

후보 조회는 기존 Alarm lifecycle·Evaluation·Notification 결정을 변경하지 않는다. 정상 응답의 최신 가용 데이터 수용과 물리 위치 시각 보장은 구분하며, 원본 시각 부재만으로 TAGO 추천을 차단하지 않는다. 기존 Polling/Freshness·timeout/retry 설정을 유지한다. 세션·후보 reference의 경계는 아래 TASK-903 계약을 따르며 후보 API 구현을 세션/Flutter 구현 완료로 취급하지 않는다.

#### 서울 후보 조회의 응답 예산 확인 (TASK-816 / AUD-07)

2026-10-30 AUD-07 1차 분석에서 순차 detail의 누적 지연과 Flutter 15초 예산 불일치를 Mock으로 재현했다. 기존 수치와 당시 보류 판단은 [TASK-816 감사 기록](task-list.md#task-816-감사-항목과-확인-수준)에 보존한다. 후속 승인 정책에 따라 `BoardingCandidateService.find()`는 metadata 조회 **직전**에 `System.nanoTime()` 기반 **12초** 예산을 한 번 생성한다. metadata transaction → roster 1회 → 중복/식별/시각 검사 → 제한된 detail 수집 → detail 상호 충돌·최종 freshness 검증 → reference 발급·정렬 순서다. 단계마다 예산을 초기화하지 않는다. 승차 occurrence가 없으면 기존대로 detail 0회이며 TAGO 후보 처리와 세션 생성·재확인의 최신 선택 차량 재검증은 변경하지 않는다.

`BoardingCandidateDetailExecutor`는 애플리케이션 singleton 후보 전용 자원이다. 요청마다 미완료 작업 **최대 2개**, 이 프로세스의 후보 조회 요청 전체에서 admission **최대 4개**(실행 전 대기 포함)를 허용한다. 고정 플랫폼 스레드 4개와 용량 4의 bounded queue를 사용하되 admission 상한 때문에 실행·대기 합계도 4를 넘지 않는다. queue는 worker 종료와 다음 작업 수령 사이의 짧은 경합을 수용하며 미조회 차량마다 Runnable/Future를 미리 생성하지 않는다. 호출 스레드는 완료/자원 반환 신호를 남은 deadline 안에서만 기다리고 원래 차량 index에 결과를 수집한다. 완료 순서와 무관하게 기존 후보 정렬을 유지하며 개별 Provider 실패 분류도 유지한다.

worker는 시작 직전 deadline/요청 종료를 검사하고 Provider 조회·불변 mapping 근거만 계산한다. deadline 경계 이상에서 도착한 결과는 버리고, 호출자는 진행 중 I/O 종료를 기다리지 않고 수집을 마감한다. 결과 수집을 닫은 뒤 늦은 작업은 저장된 결과를 덮어쓸 수 없다. 실행 자리는 **실제 worker의 finally**에서만 반환하므로 HTTP가 남아 있는데 취소된 Future의 자리를 재사용하지 않는다. 실제 네트워크/Provider 처리를 즉시 취소한다고 가정하지 않으며 `Future.cancel()`/interrupt에 의존하지 않는다. `@PreDestroy`에서 신규 admission을 막고 collector를 깨우며 pool을 shutdown한다. 아직 시작하지 않은 wrapper는 I/O 없이 종료하고, 진행 중 호출은 기존 HTTP timeout 아래 실제 종료 후 정리한다. shutdown이 네트워크 종료나 process 전체 graceful shutdown SLA를 보장하지는 않는다.

기존 Detail Client는 호출별 request/URI/응답만 사용하며 변경 가능한 per-call 필드를 공유하지 않는다. 설정은 record이고 구성 후 변경하지 않는 RestClient/JDK HttpClient를 사용한다. `SeoulBusResponseReader`의 JSON mapper는 구성 후 재사용하며 XML factory/builder/DOM은 호출마다 생성한다. `BoardingCandidateMapper`도 불변 결과만 반환한다. `BoardingCandidateReferenceStore`는 synchronized 정책을 유지하고 worker에서는 접근하지 않는다. 최종 request thread에서만 기존 `references.replace(context)`와 검증 통과 후보의 reference 발급을 수행한다. HELD·늦은 결과에는 reference/selectionKey를 발급하지 않으며 사용자·Route·목적·선택 승차 문맥과 restart/eviction/60초 freshness를 유지한다. 응답별 PARTIAL/HELD 및 신규 사유의 상세 계약은 [후보 API](api.md#후보-조회)가 소유한다.

**강제 가능한 범위:** metadata·roster가 소비한 시간도 같은 예산에서 차감하고, detail의 실행 시작 검사·자원 대기·결과 수집/반영을 deadline으로 제한한다. 동기 metadata DB transaction과 roster I/O 자체는 deadline으로 직접 중단하지 않는다. roster 자체 실패는 기존 503이며, 정상 empty는 EMPTY다. 정상 roster 확보 시 예산이 이미 소진되면 detail 대상은 미시작 HELD다. 최종 충돌 검사·freshness 재평가·reference 발급·정렬과 HTTP 직렬화는 동기 로컬 작업으로 강제 중단하지 않는다. 따라서 **12초는 조회 예산이며 절대 HTTP 응답 SLA가 아니다.** 원본 `Clock`/Provider 시각과 60초 freshness를 그대로 사용하며 단조 시간을 관측 시각으로 변환하거나 새 시각으로 연장하지 않는다.

기본 Provider connect **2초**·response **5초**는 각 HTTP 호출의 기존 설정이다. Spring Web 7.0.9의 `JdkClientHttpRequest` response timer는 응답/body 대기를 감싸며 connect를 별도 2초로 중복 가산하지 않는다. XML/JSON decoding·metadata DB·후보 mapping/정렬·직렬화는 이 HTTP timer 밖이다. 장기 정체 시 후보 전용 최대 4개 실행 자리가 남을 수 있으나 새 요청의 대기는 유한하며 새 pool을 만들지 않는다.

이 한도는 **프로세스별 후보 Detail 동시성**이다. roster, `revalidateSelected()`, `VehiclePollingCycle`의 Alarm/Boarding Monitoring, 다른 서버 인스턴스는 포함하지 않으며 Provider 전체 한도나 단위 시간당 Rate Limit이 아니다. 빠른 응답이면 예산 내 다수 차량을 조회할 수 있고 retry/cache/고정 호출 수 상한은 추가하지 않았다. 기존 문서/Client 설정에 공식 동시성·초당 quota 수치는 없으며 계정별 실제 제한·전체 호출량은 운영 미검증이다. 후보 pool은 AUD-04의 `transitMonitoringScheduler`·`notificationDispatchScheduler`와 실행 스레드를 공유하지 않는다. 공통 Client·네트워크/CPU는 여전히 공유하므로 운영 부하 간섭이 전혀 없다고 주장하지 않는다.

Flutter `BoardingCandidateClient.find()`의 최초 HTTP·공유 Refresh·최대 1회 Replay 합산 **15초**와 Auth generation/요청 epoch 무효화는 유지한다. Timeout은 실제 서버 취소를 보장하지 않는다. ReasonCode는 DTO에서 문자열로 보존하므로 신규 두 값도 전체 응답 거부 없이 수용하며 HELD는 선택 불가, 정상 후보는 선택 가능하다. 기존 PARTIAL 안내와 수동 재조회 흐름을 유지하고 신규 사유 설명만 추가했다. 구현 전후 가상 모델·선택 검증과 운영 미검증 항목은 [TASK-816 AUD-07 기록](task-list.md#task-816-감사-항목과-확인-수준)을 따른다.

Bus static metadata는 서울 T Data CSV full import와 경기 TAGO throttled full sync에서 받아 StopBell DB의 현재 상태로 보관한다. 경기 GBIS route/routeStation bulk는 전체 TAGO traversal이 검증된 Route의 destination 표시값만 보강하며 Provider identity와 realtime routing은 TAGO를 유지한다. 사용자 Route/Stop 조회와 Alarm 생성은 DB metadata를 사용하고, Alarm 생성 시 필요한 값은 `BusAlarmTarget` snapshot으로 복사한다. Alarm target은 metadata Entity를 FK로 장기 참조하지 않으므로 subsequent sync가 기존 Alarm을 변경하지 않는다.

TASK-513의 source adapter는 서울의 노선마스터·정류장마스터·노선-정류장마스터 CSV와 경기의 TAGO city별 Route·Route Stop pagination을 검증해 normalized metadata snapshot으로 만든 뒤 기존 reconciliation service에 전달한다. 서울 CSV header·UTF-8 BOM·좌표 column은 source와 fixture를 확인해 parser에 반영했으며 [ADR-009의 TASK-513 구현 기록](adr/ADR-009-bus-transit-metadata-persistence-and-diff-sync-strategy.md#후속-구현-경계-task-513)을 따른다. source 일부 fetch 실패, parser failure, pagination 미완료, required source 누락, provider request 일부 실패와 검증되지 않은 empty result는 complete provider snapshot이 아니므로 absence/deletion으로 해석하지 않으며 provider-level cleanup을 실행하지 않는다. `BusMetadataSource`가 검증된 `CompleteBusMetadataSnapshot`을 반환하고 `BusMetadataBootstrapService`가 이를 `BusMetadataSyncService.syncCompleteProviderSnapshot()`에 전달한다. 검증되지 않은 부분 수집 결과는 cleanup 경로에 들어가지 않는다.

서울 `SeoulCsvMetadataSource.fetchCompleteSnapshot()`은 CSV 전체 파싱·join 검증과 기존 노선유형 scope 판정 뒤, 포함 대상으로 확정된 모든 Route에 occurrence가 최소 1개 있는지 확인한다(TASK-816 / AUD-03). 하나라도 0개이면 complete snapshot 생성을 거부하고 예외를 전파하므로 첫 Route DB 반영·provider cleanup·`lastCompleteSyncAt` 갱신을 모두 시작하지 않는다. 정상/빈 Route가 섞여도 전체 입력을 거부하며 빈 Route만 제외해 성공시키지 않는다. 기존 경기·인천 및 null 유형의 선착장 Route 제외 정책, null 유형의 빈 occurrence 거부 정책과 TAGO 정책은 유지한다. occurrence의 정류장 연결·식별자·양수/중복 순번 등은 기존 parser와 snapshot 검증을 재사용한다. 0개 occurrence 실패는 검증 단계·complete 미승인·DB 반영 미시작을 나타내는 로그에 전체 건수와 최대 10개 Route ID 예시만 남긴다.

최초 metadata bootstrap은 일반 Backend startup에 강제로 연결하지 않는 명시적 one-shot import/sync 실행을 기본으로 한다. Provider별 `BusMetadataSyncState`는 `provider`, `lastCompleteSyncAt`을 저장해 bootstrap 완료와 마지막 complete sync 성공 age의 근거를 보존한다. readiness 연계의 최종 검증은 TASK-807에 남는다. 필요하면 in-progress/failure 정보를 확장할 수 있지만 sync/checksum history, staging table, error journal은 요구하지 않는다. 자동 refresh 주기는 이번 결정에 포함하지 않는다. 단일 Backend에서는 같은 Provider full sync의 동시 실행을 막고, Provider 전체를 하나의 장시간 DB transaction으로 묶지 않는다. 기존 Route 단위 transaction과 complete snapshot 성공 뒤 cleanup을 유지하며, ingestion 구현 시 Route reconciliation의 Stop lazy-loading N+1 여부를 확인·개선한다. DB 반영 중 오류는 실패한 Route의 metadata 변경·occurrence 교체를 함께 rollback하고 후속 cleanup·완료 시각 갱신을 차단하지만, 앞서 commit된 다른 Route까지 rollback하는 계약은 아니다. source 검증 실패와 DB 반영 실패 모두 bootstrap의 single-flight guard를 해제하므로 정상 입력으로 재시도할 수 있다. Redis, distributed lock, queue는 V1 범위가 아니다.

선택된 Provider와 identifier 정책의 근거·제약은 `adr/ADR-006-v1-transit-provider-and-external-identifier-strategy.md`를 따른다.

Provider mapper는 request Route context, raw Provider response, StopBell이 성공 응답을 받은 직후의 시각을 한 차량의 관측 사실인 `TransitObservation`으로 변환한다. TAGO와 서울 Route realtime item에 externalRouteId가 없을 수 있으므로 request context는 mapper까지 전달한다. 같은 응답의 차량은 같은 receive-time context를 공유하며, `observedAt`은 polling 시작 시각이 아니다. 공통 의미에는 Provider/Route reference, transient vehicle tracking reference, 현재 Stop/진행 순서, 선택적인 위치·시간·방향/구간 문맥, 그리고 `ARRIVED`/`MOVING`/`UNAVAILABLE`로 구분한 직접 도착 근거가 포함된다. Provider에 없는 값을 가짜 값으로 채우지 않는다. HTTP/network timeout, HTTP non-success, Provider logical error, decode/protocol error는 정상 empty와 구분하는 Provider failure이며 빈 차량 목록으로 변환하지 않는다.

### notification

Device registration lifecycle, durable logical Notification 결정, Event 시점의 per-Device recipient 확정, Push provider 요청과 결과 처리를 담당한다.

Device는 내부 PK + StopBell installation UUID v4 + 현재 FID를 분리한다. User 1:N Device이며 installation당 current owner는 한 명이다. TASK-702는 Device/Platform JPA와 V13 Schema를 구현했으며 등록·상태 조회·disable API는 TASK-703에서 구현했다. [Domain](domain-model.md#device), [Schema](database.md#devices), [등록/해제 API 계약](api.md#기기-등록해제-계약-task-702)을 따른다. Firebase readiness와 FID targeting smoke 결과는 ADR-010을 유지한다.

Device mutation은 JWT Principal과 별도 installation credential을 검증한다. TASK-703에서 기존 row의 `PESSIMISTIC_WRITE` transaction으로 ownership generation/revision 검증과 owner/FID/enabled 갱신을 함께 처리한다. 계정 변경은 credential과 명시적 이전 intent 및 expected generation이 일치할 때만 서버 generation을 증가시킨다. Client의 높은 revision만으로 이전하지 않으며 다른 User의 지연된 disable은 현재 Device를 변경하지 못한다. RefreshToken과 Device는 FK로 연결하지 않는다. 신규 row의 경쟁은 installation/FID UNIQUE로 차단하고 rollback 뒤 권한을 재검증한다.

`DeviceService`는 `REQUIRES_NEW` TransactionTemplate으로 mutation과 read-only 상태 조회의 경계를 관리한다. 최초 등록은 Entity를 로드하지 않는 existence query 후 기존 row에만 current write lock을 얻어 없는 UUID의 gap lock 경쟁을 피한다. 알려진 Device UNIQUE 충돌만 전체 rollback/실패 persistence context 폐기 후 새 transaction에서 원래 요청을 한 번 재확인한다. 원인을 확인하지 못한 DB 오류는 정상 conflict로 분류하지 않는다. Device API의 예상 밖 persistence 오류는 상세 없는 500과 예외 종류만 남기는 log로 처리한다. MySQL 중복키 메시지의 원문 UUID/FID 노출을 막기 위해 `org.hibernate.orm.jdbc.error` 로그를 끈다. 현재 HTTP access log와 request/SQL parameter 상세 log는 활성화하지 않으며 상태 조회의 원문 URL을 추가 기록하지 않는다.

Flutter는 설치본 counter/credential/generation을 logout에도 보존하고 요청을 session과 함께 snapshot해 계정 변경 뒤 이전 작업이 새 Principal로 재시도되지 않게 한다. Device disable 시도 → Auth logout → local session 종료의 경계를 유지하며 network 실패로 logout을 영구 차단하지 않는다. Offline 즉시 Push 해제와 이미 FCM에 접수된 알림 회수는 보장하지 않는다. 구체 계약/한계는 API 문서가 소유하며 TASK-704는 설치본 전용 serial mutation, durable pending/session snapshot, permission/readiness 및 startup/resume/FID rotation 동기화를 구현했다. Native production bridge는 기존 FlutterFire delegate를 전달·복원하며 smoke와 분리한다. 저장·재설치/복구의 상세 정책은 [ADR-010 Client 구현](adr/ADR-010-notification-device-and-durable-delivery.md#task-704-flutter-설치본등록-lifecycle-구현-2026-10-12)을 따른다.

TASK-705의 `notification.client.FcmProviderClient`는 기존 Google 인증/HTTP dependency로 단일 FID HTTP v1 전송과 정규화 결과만 반환한다. Firebase Admin SDK는 추가하지 않는다. 자동 dispatch가 기본 false일 때 설정·인증은 호출 시 준비하므로 FCM 설정 누락이 Backend startup/readiness를 실패시키지 않는다. Provider I/O는 DB transaction 내부 호출을 거부하며 Event/Delivery/Device/Alarm을 변경하지 않는다. Event 연결·fan-out은 TASK-708, Worker orchestration은 TASK-706, production attempt/result/retry/expiry/cleanup은 TASK-709에서 구현했다. 실제 자동 Firebase/iPhone E2E는 TASK-710/711에 남는다.

전송마다 별도 HTTP client를 소유하고 인증·전송을 포함하는 유한 wall-time budget, 요청 abort 및 종료된 invocation의 후속 HTTP 차단을 적용한다. local opt-in one-shot Smoke는 read-only transaction을 끝낸 뒤 같은 Provider를 한 번 호출하며 공개 API를 만들지 않는다. 구체 인증 지원 범위·재전송 제어·보장 한계는 [ADR-010 TASK-705](adr/ADR-010-notification-device-and-durable-delivery.md#task-705-provider-구현과-제어-gate-2026-10-12), 실행 방법과 실기기 미검증 상태는 [Local Development](local-development.md#task-705-backend-provider-수동-smoke)를 따른다.

Notification persistence는 다음 책임으로 분리한다.

```text
NotificationEvent
- durable logical notification decision
- alarmId + activation generation + trackingCycleId + eventType dedup identity
- BOARDING_SESSION: boardingSessionId + boardingEventType(PREPARE_ALIGHT/DESTINATION_REACHED) dedup identity (TASK-906 1단계 persistence)
- Event 시점 recipient Delivery들의 logical source

NotificationDelivery
- NotificationEvent × Device
- provider delivery/retry/expiry state
- current/final provider result
```

하나의 MySQL Alarm lifecycle transaction에서 current Alarm lifecycle/generation 검증, lifecycle transition, `NotificationEvent` insert와 그 시점에 eligible한 Device별 Delivery 생성을 처리해 recipient set을 확정한다. 유효 기한인 최초 row는 PENDING이며 이미 만료된 row는 EXPIRED로 생성한다. eligible Device가 0개여도 이미 발생한 logical NotificationEvent는 저장하고 Delivery는 0개로 두며 no-recipient log/metric을 남긴다. 이후 등록된 Device에 과거 Event의 Delivery를 생성하지 않는다. 단일 Spring Backend의 fixed-delay, non-overlapping worker는 commit된 Delivery를 제한 조회하고 전송 직전 Device와 freshness를 재검증한다. FCM I/O는 transaction 밖에서 수행한다. claim/lease, `claimedAt`, `SENDING`, stale-claim recovery와 multi-worker coordination은 현재 V1에 도입하지 않는다. 외부 Kafka/RabbitMQ/Redis queue, Notification microservice, non-durable after-commit callback만으로 구성한 전달 경로는 사용하지 않는다.

Delivery의 네 lifecycle과 Provider의 여섯 result는 [Domain](domain-model.md#delivery-result-semantics)을 따른다. Retry는 PENDING + attemptCount + nextAttemptAt + expiresAt이며 EXPIRED는 local 결정이다. Provider acceptance는 실제 Device 표시 성공이 아니며 ambiguous timeout 뒤 retry는 duplicate 표시 가능성이 있다. 오류 mapping/전이와 cleanup 근거는 [TASK-709 설계](adr/ADR-010-notification-device-and-durable-delivery.md#task-709-failureretryexpiry-설계-2026-10-09)가 소유한다.

#### Notification decision transaction (TASK-708)

TASK-708에서 `BusAlarmLifecycleService`에 lifecycle/Event/최초 recipient Delivery의 atomic decision을 구현했다. 선택한 `TransitEvent`와 평가 당시 `AlarmEvaluationKey`, expected status, 원래 evaluation result를 transaction 밖에서 고정한다. Provider HTTP I/O와 Evaluation을 끝낸 후 다음 짧은 DB transaction을 실행한다.

1. 새 persistence context에서 current Alarm을 `PESSIMISTIC_WRITE`로 조회한다. 삭제됐거나 generation이 다르면 stale로 종료하며 변경하지 않는다.
2. candidate가 있으면 동일한 네 identity 값의 Event를 current locking read로 조회한다. 이미 존재하면 무변경 duplicate로 종료한다. 이 확인은 expected status 비교보다 먼저 수행해 commit된 ARRIVED/ONE_STOP_AFTER의 재요청을 이미 바뀐 status 때문에 새 결정으로 처리하지 않는다. Event 없는 결과는 identity 조회를 생략한다.
3. 새 Event 또는 Event 없는 결과이면 expected status와 current status를 비교하고 기존 precedence/option/evidence를 검증한다. ACTIVE는 ONE_STOP_BEFORE/ARRIVED/PASSED, FOLLOW_UP은 ONE_STOP_AFTER만 허용한다. FOLLOW_UP after는 current vehicle ID·persisted cycle UUID가 candidate와 같고 적용 시점에도 미만료여야 한다. 그 밖의 stale/무효 결과는 반영하지 않는다.
4. 필요한 Alarm lifecycle 전이와 FOLLOW_UP UUID 저장/제거를 수행하고 불변 identity의 NotificationEvent를 INSERT한다. ONE_STOP_BEFORE/PASSED는 ACTIVE 유지, ARRIVED는 기존 after option에 따른 전이, ONE_STOP_AFTER는 FOLLOW_UP 완료다. Event 없는 follow-up expiry는 기존 lifecycle 완료만 수행한다.
5. 이 결정 시점에 Alarm owner의 enabled Device를 current locking read로 한 번 조회해 recipient set을 고정한다. 해당 Device row를 ID 순서로 잠가 owner/enabled/target 변경과 조율하며, set의 각 Device에 recipientOwnershipGeneration과 원래 observedAt 기반 expiresAt을 갖는 Delivery 하나를 INSERT한다. 유효 기한이면 PENDING/count=0/nextAttemptAt=createdAt, 이미 만료면 EXPIRED/count=0/null next다. 0개이면 Event만 저장하고 no-recipient 관찰을 남긴다. 원래 candidate의 실제 eventDetectedAt도 함께 보존하며 DB retry로 시각/TTL을 새로 정하지 않는다.
6. flush와 commit을 마친 뒤 결과를 반환한다. Event 또는 필요한 Delivery 하나라도 저장 실패하면 lifecycle/UUID/Event/모든 Delivery를 함께 rollback한다. Provider/FCM I/O는 포함하지 않는다.

기존 Event 조회는 [MySQL current locking read](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)로 수행해 오래된 REPEATABLE READ snapshot을 사용하지 않는다. Alarm → Event → Device ID 순서로 lock을 얻으며 Device 등록 transaction은 Alarm lock을 추가하지 않는다. 조회만을 dedup 보장으로 삼지 않고 [두 DB UNIQUE](database.md#phase-7-notification-persistence-contract)를 최종 경계로 유지한다.

INSERT/flush/commit의 UNIQUE 실패는 transactional method 밖의 호출자가 전체 rollback 완료 후 처리한다. 실패 transaction 안에서 예외를 삼키고 SELECT/commit하지 않는다. [Hibernate Session 오류 계약](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/Session.html)과 [Spring rollback-only 계약](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)을 따른다. 기존 Service transaction 경계 또는 명시적 transaction callback을 사용하며 self-invocation으로 transaction이 누락되지 않게 한다. Event만 별도 `REQUIRES_NEW`로 commit하지 않는다.

Event logical UNIQUE 충돌일 때만 새 transaction/persistence context에서 같은 Alarm lock과 exact identity 조회로 기존 Event를 확인해 duplicate로 분류한다. 동일 Event가 확인되지 않거나 Delivery UNIQUE/FK/CHECK 오류면 일반 duplicate로 숨기지 않는다. deadlock/DB 일시 실패 또는 commit 응답 유실의 재확인은 원래 candidate/UUID/result로 전체 절차를 다시 실행한다. 이미 commit됐으면 duplicate, rollback됐고 여전히 유효하면 새 결정, Alarm/generation/status/evidence가 달라졌으면 stale다. 재시도 횟수는 제한하고 무한 retry/framework를 도입하지 않는다. FCM retry/TTL 수치는 TASK-709 책임이다.

TASK-708의 `BusAlarmDecisionResult`는 COMMITTED/DUPLICATE/STALE/RETRY_REQUIRED와 current generation/status/FOLLOW_UP vehicle·cycle 문맥을 반환한다. Scheduler는 commit 확인 전 nextState를 publish하지 않고, DB 실패/응답 유실 중에는 원래 candidate/result를 유지해 재평가로 UUID를 새로 만들지 않는다. duplicate 확인 후에는 current generation/status와 일치할 때만 원래 result의 consumed state를 반영한다. 이미 ARRIVED commit으로 FOLLOW_UP이면 persisted vehicle/cycle이 같은 원래 tracking을 유지·복원하고, 이미 after commit으로 INACTIVE이면 해당 memory를 종료한다. stale 결과나 새 activation에는 old nextState를 적용하지 않는다. 이 memory 반영은 Event/Delivery/lifecycle을 다시 변경하는 작업이 아니다.

`TransactionTemplate(REQUIRES_NEW)`는 Event만 분리하지 않고 결정 전체에 새 persistence context를 제공하며 execute 반환 전에 flush/commit을 마친다. 일시적 DB/transaction 실패는 호출당 최대 두 번 시도하고, 알려진 Event logical UNIQUE만 rollback 후 새 transaction에서 exact identity를 재확인한다. 그 밖의 integrity 오류는 전파하며 Scheduler는 exception 종류만 안전하게 기록한다. No-recipient log는 commit 뒤 `outcome=NO_RECIPIENT`와 eventType만 기록하며 새로운 metric label/framework를 추가하지 않는다.

Scheduler는 미확인 결정을 `(alarmId, activationGeneration)`별 immutable pending result/expectedStatus/detectedAt으로 메모리에 보관한다. 다음 cycle에서 monitoring group 조회보다 먼저 재확인하므로 응답 유실 뒤 Alarm이 INACTIVE가 되어도 원래 candidate를 잃지 않는다. 재확인 실패 중에는 새 평가를 생략하고 memory nextState를 publish하지 않는다. 이 pending buffer는 process restart 내구성을 주장하지 않으며 ACTIVE의 Phase 5 restart 한계는 그대로다.

`notification.service.NotificationDecisionTtlPolicy`는 `notification.decision.ttl.one-stop-before`, `.arrived`, `.passed`, `.one-stop-after`의 독립 Duration 설정을 제공한다. 네 기본값은 모두 **provisional 개발값 `PT60S`**이며 TASK-709 구현 설정으로 유지한다. 운영값 검증·조정은 TASK-710/711/810에 남는다. 양수·유한하고 DATETIME(6)에서 사라지지 않는 최소 microsecond 정밀도를 요구하며 overflow 값은 거부한다. FOLLOW_UP의 5분 tracking timeout과 별개다. `expiresAt = original observedAt + TTL(type)`이고 DB retry 시 observed/detected 시각과 기한을 변경하지 않는다. 이미 만료된 유효 결정도 lifecycle/Event/recipient identity를 보존하며 Delivery만 최초 EXPIRED로 생성한다. 이 결정 정책은 dispatch/retry scheduling을 수행하지 않는다.

TASK-707은 persistence primitive와 제약을, TASK-708은 위 결정 transaction·dedup/retry 연결·UUID 복원을 구현했다. TASK-706은 commit된 pending Delivery worker를 소유한다. 책임 분담·삭제 제약·현재 recovery 한계는 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#task-708-중복-방지-설계-2026-10-09)을 따른다.

#### Alarm 삭제 transaction (TASK-707)

AlarmService.delete는 기존 인증/소유권 검증과 Alarm PESSIMISTIC_WRITE 조회를 유지한다. 같은 transaction에서 해당 원본 alarmId의 PENDING Delivery를 ID 순서로 PESSIMISTIC_WRITE 조회하고 FAILED/DISPATCH_NOT_ALLOWED, nextAttemptAt=null로 종료한 뒤 Alarm/BusAlarmTarget을 hard delete한다. Event/Delivery는 보존하고 기존 attempt/result 및 terminal row는 변경하지 않는다. flush/commit 실패는 모두 rollback한다.

삭제는 Alarm → Event join/Delivery ID 순서로 lock을 얻고 Device lock을 추가하지 않는다. 신규 결정은 같은 Alarm lock 뒤 Event → Device 순서이므로 삭제와 직렬화하며, 삭제 후 Scheduler/Event candidate는 TASK-708의 Alarm 부재 검증으로 거부한다. Worker는 Delivery → Device만 잠그고 뒤에 Alarm/Event write lock을 추가하지 않으므로 역순 lock cycle을 만들지 않는다. Worker preflight가 먼저 commit됐다면 I/O 직전 삭제 경쟁과 진행 중 FCM 요청의 회수는 보장하지 않는다. 삭제가 종료한 terminal row에는 후속 Worker가 새 호출을 시작하거나 late result로 PENDING을 복구하지 않는다. TASK-706은 이 Worker 경계를, TASK-709는 실제 result 정책을 구현했다.

#### PASSED 위치 보조 정보 전달 (TASK-816 / AUD-13)

기존 ALARM의 PASSED 위치 보조 정보는 `TransitEvent → NotificationEvent → NotificationRecipientPreflight → NotificationDispatchTarget(NotificationAttemptSnapshot) → NotificationSendRequest → FCM Payload`로 전달한다. `PassedNotificationContext`는 기존 attempt 준비 transaction에서 Delivery가 참조하는 원래 Event의 `currentStopName`·`stopsPastTarget`만 복사하는 불변 값이다. Entity/Lazy proxy는 전송 경계를 넘기지 않으며 기존 commit 뒤 transaction 밖 FCM 호출과 Delivery → Device lock 순서를 유지한다. 위치용 Provider 재조회·외부 cache·Event 사후 갱신은 없다. retry나 전송 서비스 재생성 뒤에도 해당 Delivery의 원래 Event를 사용하며 다른 차량/cycle의 최근 정보와 결합하지 않는다.

두 값은 Event 생성 시점에 확인된 선택적 근거이며 실제 현재 위치가 아니다. 정거장 수는 기존 Evaluator/Traversal이 확인한 동일 traversal의 successor edge 수만 사용하고 dispatch에서 order 차/GPS로 재계산하지 않는다. null·0·음수는 생략한다. 이름은 앞뒤 공백을 제거하고 null·공백뿐인 값·제어 문자·Unicode format/line/paragraph separator·고립 surrogate가 있으면 생략한다. 긴 이름은 surrogate를 분리하지 않고 말줄임표 포함 80 Unicode code point로 제한한다. 선택적 정보가 없어도 일반 PASSED 알림을 보내며 실제 DB 오류를 정보 부재로 숨기지 않는다. 현재 서울·TAGO Observation Mapper는 이름을 null로 두므로 이름을 새로 보강하지 않고 정거장 수만으로도 안내한다.

기본 문장과 선택적 보조 문구·두 alert body의 일치 및 기존 data 계약은 [API](api.md#push-payload와-notification-tap)를 따른다. PASSED 외 Alarm Event와 BOARDING_SESSION은 이 Context를 사용하지 않는다. 판정 우선순위·PASSED 뒤 ACTIVE/다음 차량 감시·identity/UNIQUE·recipient generation/revision·retry/backoff/expiry/cleanup 정책은 변경하지 않는다. 격리 MySQL의 commit·영속성 컨텍스트 분리·전송 경계 재생성 검증은 실제 프로세스 restart E2E나 iPhone 표시 검증을 대체하지 않는다. 실기기 줄바꿈·표시 범위와 수신/tap은 TASK-710/711에 남는다.

#### Notification dispatch worker 구현 (TASK-706)

`notification.service.NotificationDispatchWorker`는 기존 `findDuePendingIds(now, PageRequest.of(0, batchSize))`를 사용해 `nextAttemptAt ASC, id ASC`의 ALARM source PENDING ID만 제한 조회하고 순차 처리한다. TASK-906 1단계의 source 제한은 LIMIT 전에 적용한다. Alarm의 expired/capped PENDING을 조회에서 제외하지 않으며 recipient fan-out, 새 Event/Delivery 생성, Alarm lifecycle 변경을 수행하지 않는다. MySQL이 pending source이며 [전용 Scheduler](#scheduler-실행-자원-분리-task-816--aud-04)에서 동기 실행하고, memory queue, 추가 비동기 executor, worker sleep/retry loop는 없다.

`notification.dispatch.enabled=false`가 기본이다. FCM provider의 `notification.fcm.enabled` 및 명시적 local Smoke와 독립된 설정이다. `batch-size=50`, `fixed-delay=PT1S`는 provisional 개발값이며 운영 확정값이 아니다. TASK-709에서는 batch 1~1,000, delay 1ms~1분으로 유한 범위를 검증한다. 기존 Spring scheduling의 fixed-delay와 Worker의 `AtomicBoolean.compareAndSet` guard로 동일 인스턴스의 실행 중첩을 막는다. context close 뒤 새 cycle/후속 후보는 시작하지 않는다. 다중 Backend 안전성을 제공하지 않는다.

`NotificationDispatchConfiguration`은 enabled=true일 때 `DurableNotificationAttemptHandler`와 `NotificationRetryPolicy`를 구성한다. TASK-706 당시 handler 부재 gate는 TASK-709에서 FCM enabled/project/credential path/timeout의 필수 정적 설정 검증으로 대체했다. 누락·잘못된 설정이면 startup을 차단하고 attempt를 소비하지 않는다. Credential 파일을 startup에서 열거나 인증 요청을 미리 수행하지 않는다. 기본 disabled Backend startup과 TASK-705 별도 one-shot Smoke 경로는 유지한다.

`NotificationDispatchAttemptService`는 전체 cycle과 HTTP를 transaction으로 감싸지 않고 시작/결과/local expiry마다 `TransactionTemplate(REQUIRES_NEW)`를 사용한다. Delivery `PESSIMISTIC_WRITE` current read 후 PENDING/due를 재확인하고, handler의 `checkEligibility`에 실제 expiry 우선·attempt budget 판단과 필요한 local 종료를 맡긴다. 살아 있는 후보만 `NotificationRecipientPreflight`가 Device ID의 `PESSIMISTIC_WRITE` current read로 Event owner, recipientOwnershipGeneration, enabled, 유효 current FID 및 현재 revision을 확인한다. A→B→A는 generation으로 차단하고 같은 ownership의 정상 rotation은 current FID/revision을 사용한다. Event는 불변 source이며 Alarm/Event write lock과 현재 Alarm status 검사는 추가하지 않는다. recipient 탈락은 `rejectRecipient`에 위임하며 Provider result로 꾸미지 않는다.

전송 target은 불변 `NotificationDispatchTarget`의 deliveryId/eventId/alarmId/eventType/deviceId/eventOwnerId/ownershipGeneration/registrationRevision/FID/expiresAt이다. `NotificationAttemptSnapshot`은 이 target과 durable attemptCount/UTC microsecond lastAttemptAt을 묶는다. raw FID는 메모리에만 존재하고 두 타입의 toString은 redacted이며 Delivery Schema에 복제하지 않는다. handler의 `beginAttempt`는 count/start/revision/결과 초기화/recovery 예약을 실제로 저장해야 한다. Service는 반환 snapshot과 저장된 attempt identity/count 증가/유효 예약을 확인하고 flush하며, execute가 commit을 확인한 뒤에만 snapshot을 반환한다. 시작 실패 또는 commit 응답 유실이면 Provider를 호출하지 않는다.

Worker는 enclosing transaction/synchronization을 거부하고 시작 commit 뒤 UTC Clock으로 `now >= expiresAt`을 다시 확인한다. 만료 시 같은 snapshot의 `expireBeforeSend`에 위임하고 호출하지 않는다. 그 외에는 실제 Event ID와 Alarm ID/type, 원래 expiry 및 current FID로 기존 `FcmProviderClient.send`를 한 번 호출한다. 여섯 `NotificationSendResult`를 재분류하지 않고 별도 transaction의 `completeAttempt`에 전달한다. Service는 terminal/삭제 종료/다른 count·start time·revision/이미 반영된 결과를 handler 호출 전에 무변경 제외한다. Device cleanup과 상태 전이 정책은 handler 책임이다.

후보별 일반 오류는 격리하며 DB/transaction 장애로 안전한 진행이 불가능하면 cycle을 종료한다. 결과 저장 실패 때문에 FCM을 즉시 재실행하지 않는다. cycle 개수/elapsedMs, skip reason, 정규화 result, 필요한 deliveryId와 exception 종류만 기록하고 원문 exception/body/FID/credential은 기록하지 않는다. TASK-706의 최초 검증에는 실제 accounting/retry/result/cleanup이 없었다. TASK-709에서 production 정책과 동일 결과의 제한 DB 재반영을 추가했으며 아래 구현 경계를 따른다. 자동 dispatch는 계속 기본 false이고 실제 E2E 및 운영 tuning은 미검증이다.

집중 unit 10개, Spring configuration 4개, 격리 MySQL Testcontainers 통합 8개를 검증했다. 통합 테스트의 명시적 SQL fixture는 valid durable start를 제공해 실제 commit/rollback·응답 유실 차단·recipient current read·transaction 밖 Provider 연결을 확인하며 TASK-709의 retry/result mutation correctness를 대신하지 않는다. 기존 Backend 전체 suite, 실제 Firebase/iPhone 전송, local DB dispatch와 process restart/E2E는 실행하지 않았다. Dependency·Migration 변경은 없다.

#### Notification dispatch와 result transaction (TASK-709)

TASK-709는 `DurableNotificationAttemptHandler`로 아래 계약을 구현했다. Handler는 caller-owned transaction 안에서 Entity mutation만 수행하며 Provider 호출이나 독립 transaction을 시작하지 않는다. `NotificationRetryPolicy`는 기존 FCM requestBudget을 재사용해 retry/recovery 시각만 계산한다. TASK-706의 worker/preflight/I/O 경계와 V14 Schema는 유지한다. worker는 [due candidate query](database.md#worker-due-query와-index-task-709)로 bounded ID 목록을 읽고 한 row씩 동기 처리한다. expired/capped 후보도 종료해야 하므로 처음 query에서 숨기지 않는다. restart는 MySQL의 row 상태만 사용하고 memory recipient 목록/attempt를 복원하지 않는다.

1. **시작 transaction:** Delivery를 PESSIMISTIC_WRITE로 다시 읽고 없거나 terminal/future-due면 no-op한다. 현재 UTC Clock에서 expiry를 먼저, 그 다음 남은 attempt budget을 확인하고 local 종료가 필요하면 그 상태만 commit한다. 살아 있는 row는 원래 Event owner와 recipient generation을 사용해 Device를 PESSIMISTIC_WRITE current read로 검증한다. enabled/current FID와 owner/generation이 맞으면 메모리 snapshot을 고정한다. Event/삭제 dispatch 허용은 TASK-707 정책을 따르고 현재 Alarm INACTIVE만으로 정상 ARRIVED를 거부하지 않는다.
2. **Durable attempt 시작:** 같은 짧은 transaction에서 count 증가, lastAttemptAt/revision, null lastProviderResult/lastFailureCode, recovery nextAttemptAt을 저장한다. status는 PENDING이다. commit이 확인된 경우만 다음으로 진행한다. commit 실패/응답 유실이면 FCM을 호출하지 않고 저장 상태를 재확인하며 보수적으로 budget을 소비한다.
3. **외부 호출:** 모든 DB lock/transaction을 해제한 뒤 UTC freshness를 한 번 더 확인한다. 이미 기한이면 새 외부 호출 없이 local EXPIRED를 반영한다(이미 commit한 count는 되돌리지 않음). 그 밖에는 검증한 current FID snapshot으로 단일 Provider invocation을 실행한다. FCM 요청 전체에 유한 timeout/budget을 요구하며 [SDK 내부 retry 제어 gate](adr/ADR-010-notification-device-and-durable-delivery.md#sdk-내부-retry와-후속-검증-gate)가 먼저 충족돼야 한다. row lock을 유지한 채 네트워크를 호출하지 않는다.
4. **결과 transaction:** Delivery PESSIMISTIC_WRITE current read로 동일 row가 PENDING이고 attemptCount/last revision/lastAttemptAt이 해당 snapshot과 같은지 확인한다. 삭제/terminal/다른 attempt면 late result를 무변경 종료한다. 같은 attempt의 결과가 이미 저장됐으면 no-op한다. Provider 결과를 [결정표](adr/ADR-010-notification-device-and-durable-delivery.md#provider-result--delivery-결정표)에 따라 기록하고 nextAttemptAt/accepted time/local code를 갱신한다. INVALID_TARGET만 Device write lock 안에서 identity/owner/generation/revision/FID/enabled를 모두 재검증한 뒤 조건부 disable한다. Delivery result와 cleanup은 함께 commit/rollback하며 다른 Device/Alarm은 변경하지 않는다.
5. **다음 cycle / restart:** terminal은 재조회하지 않고 PENDING은 확정 retry 또는 recovery 예약이 due일 때 다시 현재 상태를 검증한다. result DB commit 실패/응답 유실 시 같은 memory snapshot/result를 새 transaction에서 한 번만 재반영하며(최초 포함 최대 2회) 새로운 FCM 요청을 시작하지 않는다. 상태 확인에서도 DB 실패가 계속되면 현재 dispatch cycle을 종료한다. process가 죽으면 결과/snapshot은 잃지만 precommitted count/기한/예약은 유지된다.

실제 invocation이 CONFIGURATION을 반환하면 해당 Delivery는 기존 결정표로 종료하되 Worker는 추가 attempt 시작을 차단한다. 같은 cycle의 남은 후보 및 후속 cycle은 local expiry만 처리하고 미만료 row의 count/예약을 보존한다. 후속 조회는 `findExpiredPendingIds`로 만료된 PENDING만 제한 조회해 미만료 backlog 뒤의 expiry starvation을 막는다. 새 queue/lease/circuit breaker는 없으며 이 최소 in-process 차단은 설정·인증 복구 후 Backend 재시작으로 해제한다. 이미 FAILED인 row는 복구해도 재전송하지 않는다.

개발 후보 설정·안전 상한은 [ADR-010 구현 설정](adr/ADR-010-notification-device-and-durable-delivery.md#task-709-구현-설정과-검증-2026-10-13)을 따른다. Type별 TTL은 기존 `NotificationDecisionTtlPolicy`의 60초를 유지하며 Handler는 원래 expiresAt을 재계산/연장하지 않는다. 일반/ambiguous 최소 10초, quota 최소 60초와 normalized minimumRetryDelay를 존중하므로 기한 내 retry가 불가능하면 PENDING + nextAttemptAt=expiresAt으로 기다렸다 실제 만료 때 EXPIRED로 종료한다.

Worker transaction의 lock 순서는 Delivery → Device다. worker가 Alarm/Event write lock을 나중에 추가해 TASK-708의 Alarm → Event → Device와 반대 순서를 만들지 않는다. Event는 불변 owner/evidence source로 읽으며 TASK-707의 dispatch 종료/삭제는 Delivery lock과 조율하고 일관된 lock 순서를 검증해야 한다. 신규 fan-out의 아직 commit되지 않은 Delivery는 worker에 보이지 않는다. Device mutation은 Device lock만 사용한다. 삭제 transaction/FK는 위 TASK-707 계약을 따르며 범용 cancellation system은 추가하지 않는다.

단일 worker는 진행 중 request와 별도 expiry sweep을 겹치게 하지 않는다. 후보 조회가 exclusion/lease는 아니므로 multiple worker 안전성을 보장하지 않는다. 전송 직전 검증 이후 registration 변경 경쟁, accepted 뒤 result commit 전 crash의 duplicate, 내부 retry의 wire count와 외부 접수 복원 한계는 [ADR crash/restart 계약](adr/ADR-010-notification-device-and-durable-delivery.md#crashrestart와-safety-경계)을 따른다. 다음 cycle/restart도 terminal을 PENDING으로 reset하거나 과거 recipient를 확장하지 않는다.

### 탑승 세션 실행과 Notification 연계 (TASK-903)

이 절은 [ADR-012](adr/ADR-012-boarding-session-and-alight-notification-contract.md)의 승인된 계약이다. TASK-905 1단계는 Session Persistence·확정 검증/저장 경계·조회·취소를, 2단계는 특정 차량 polling·checkpoint/준비 후보·종료 판정을 구현했다. 3단계는 checkpoint 기반 재시작 자동 복구와 동일 차량 재확인 API를 구현했다. TASK-906 2단계는 준비 Event·최초 recipient Delivery와 Session checkpoint/상태의 원자 저장 및 lifecycle PENDING 종료를 연결했다. TASK-906 3단계는 source-aware 전송과 Session current gate를 연결했다. Provider별 조건부 생성·재확인은 API 계약을 따르며 실제 기기 표시 검증과 구분한다. Domain·복구 판정은 [BoardingSession](domain-model.md#boardingsession), 필드·제약·조회는 [Database](database.md#phase-9-boarding-persistence-contract-task-903), HTTP는 [API](api.md#탑승-세션-api-task-903)가 소유한다.

#### 확정과 평가 transaction

후보 생성은 기존 Transit Client/raw DTO와 DB metadata를 사용하며 Alarm Evaluator로 후보를 완성하지 않는다. opaque candidateRef는 인증 User·Route·목적/선택적 승차 occurrence·ID/전체 번호·관측 시각·구조 fingerprint의 임시 snapshot에 연결한다. 단일 프로세스의 제한된 메모리 reference이며 새 cache 인프라/table은 없다. 원래 관측의 기존 freshness를 벗어나거나 eviction/restart로 잃으면 재조회한다. 캐시 재표시로 observedAt을 갱신하지 않는다. 후보 목록 전체 detail의 무조건 선조회·별도 polling 주기·무제한 cache를 도입하지 않는다. TASK-904의 `BoardingCandidateReferenceStore`는 발급 순서 기준 최대 10,000개를 유지하며 동일 사용자/화면 문맥 재조회는 이전 reference를 교체한다. 구조 fingerprint에는 Provider/Route/typed cityCode와 ordered occurrence ID·external Stop ID·order를 포함하고 표시 이름은 제외한다. 서버 내부 resolve는 인증 User를 요구해 원래 문맥을 복원하며, 이미 가진 전체 문맥과의 일치 검증도 제공한다. 최신 차량·metadata 재검증을 대체하지 않는다. 서울 detail 선택 조건과 호출량·응답 필드는 [후보 API](api.md#후보-조회)를 따른다.

확정/재확인은 Provider I/O 전에 일관된 metadata snapshot을 읽고 I/O는 DB transaction·lock 밖에서 수행한다. 결과 반영의 짧은 transaction은 새 current metadata 구조를 일관되게 다시 읽어 fingerprint를 비교한다. 계산은 고정 session snapshot만 사용하고 버전을 섞지 않는다. 공유 Route Lock이나 기존 Sync 변경은 없으므로 비교 뒤 sync commit까지 차단하는 전역 최신성 보장은 아니다. 이후 구조 변경을 발견하면 보류하고 목적지·snapshot을 자동 교체하지 않는다.

생성은 User PESSIMISTIC_WRITE 뒤 중복 요청·미종료 세션을 current read로 확인하고 Session을 저장한다. 기존 세션의 API mutation/평가/복구는 Session PESSIMISTIC_WRITE로 원래 status/revision·차량·수명·checkpoint를 다시 검증한다. checkpoint·상태·필요한 Event·최초 recipient Delivery를 한 transaction으로 commit한다. 알려진 UNIQUE 충돌·commit 응답 유실은 전체 rollback/새 persistence context에서 원래 요청/후보로 제한 재확인하며 blind upsert·Event만 별도 commit은 금지한다. commit 확인 전 memory next-state나 전송 gate를 열지 않는다.

2단계는 별도 Scheduler 없이 기존 `BusAlarmMonitoringScheduler`의 synchronous fixed-delay cycle을 사용한다. cycle 한정 `VehiclePollingCycle`이 TAGO `(externalRouteId, cityCode)` 원본 목록, 서울 Route roster, 고유 vehId detail과 실패 결과를 공유한다. Alarm의 기존 detail 선택과 세션 selected 차량 조회의 합집합을 사용하고 원래 수신시각을 보존한다. 세션이 없거나 재확인이 필요한 보류·만료이면 세션 때문에 외부 요청을 추가하지 않는다. 미검증 TRACKING과 자동 재검증 가능한 SUSPENDED도 같은 cycle의 새 관측을 사용해 복구하며 동일 Route/차량의 조회·실패를 공유한다. 기존 Alarm의 한 번 재시도는 별도의 공유 cycle에서 새 요청을 수행하며, 재시도 detail도 고유 차량 ID로 중복 제거한다. 세션은 다음 일반 cycle에서 재조회한다. cycle 종료 뒤 공유 응답을 유지하지 않는다. Scheduler 중복 invocation은 직렬화하고 기존 Alarm 평가·Notification 결정 경로는 유지한다.

`BoardingSessionPollingService`는 TRACKING/SUSPENDED를 조회한다. 등록은 즉시 TRACKING이며 미검증 관측과 검증된 checkpoint를 evidence로 구분한다. 일시적 위치 부족·Provider 장애는 TRACKING을 유지하고 runtime gate를 닫는다. 새로운 신뢰할 기준점 확보 시 현재 유효한 알림은 checkpoint와 같은 transaction에 저장한다. 미검증 관측의 NO_CANDIDATE 반영은 성공으로 반환하지만 runtime readiness·Event 생성을 허용하지 않는다. 후보 만료와 분리된 최초 세션 수명 동안 새 관측을 처리하고 재시작도 같은 검증을 거친다. 짧은 조회 transaction에서 detached 입력을 읽고, Provider I/O·`BoardingProgressEvaluator` 계산을 transaction 밖에서 수행한다. `BoardingTrackingStore`는 Session PESSIMISTIC_WRITE 아래 terminal/만료·원래 status/revision·차량·snapshot/checkpoint를 다시 확인하고 현재 metadata 구조를 비교한 뒤 checkpoint/상태/revision을 원자적으로 반영한다. Metadata Sync에 전역 lock을 추가하지 않는다. commit 확인 뒤에만 runtime readiness와 `BoardingTrackingDecision`을 공개하고, DB commit 응답 유실은 새 transaction에서 원래 결과의 status/revision·identity·snapshot/checkpoint와 현재 metadata를 한 번 재조회한다. 정확히 일치하는 commit만 공개하고 rollback·최신 변경은 재적용하지 않는다. DB 실패 뒤 즉시 Provider 재호출이나 오래된 결정 자동 replay는 하지 않는다. `Applied`는 sessionId와 current revision을 명시하며 후보가 durable 소비됐다는 뜻은 아니다.

`BoardingTrackingRuntime`은 polling 대상 목록과 독립된 프로세스별 readiness gate다. 생성 commit 확인 뒤 등록하며, 새 정책의 신선한 검증 baseline이 있는 생성은 원자 저장한 현재 준비 Event와 함께 commit 확인 뒤 READY를 허용한다. 미검증 생성과 레거시 복구는 기본 차단을 유지하며, 복구·재확인·polling에서 새 관측 checkpoint의 commit을 확인한 경우에만 해당 revision/checkpoint를 허용한다. 새 프로세스는 기본 차단이며 DB TRACKING이나 과거 checkpoint의 반복 평가만으로 gate를 열지 않는다. `isReady`는 현재 DB snapshot의 status/revision/checkpoint·수명·관측 freshness를 요구하므로 TASK-906도 현재 상태를 읽어 같은 검증 경계를 사용할 수 있다.

보류·취소·만료·완료는 commit 확인 뒤 허용을 닫고 revision watermark를 남긴다. `committed(..., false)`는 이전 허용 revision을 유지하지 않으며 더 낮거나 이미 닫힌 같은 revision의 늦은 callback은 열지 못한다. freshness가 끝난 checkpoint도 닫으며 새 검증 revision이 필요하다. 목록 조회 정리는 조회 중 새로 생성된 세션을 제거하지 않는다. 미복구 세션의 만료도 외부 호출 없이 반영한다. 재확인이 필요한 알려진 연속성 상실은 DB 사유와 마지막 checkpoint를 보존한다. 자동 재검증 실패가 이어져 checkpoint 이후 공백 상한을 넘으면 CONTINUITY_GAP_EXCEEDED로 보류한다.

세션은 Alarm의 다중 차량 선택·baseline·lifecycle을 재사용하지 않는다. snapshot/checkpoint는 전체 raw history나 event sourcing이 아니며 준비 후보는 Event 저장 전 여러 cycle에서 다시 계산될 수 있다. TASK-906 2단계는 Session identity UNIQUE와 atomic Notification 저장으로 durable 중복을 억제하며 FCM 성공을 뜻하지 않는다.

1단계의 `BoardingSessionStartValidator`는 ephemeral reference를 복원하고 기존 `BoardingCandidateService`의 최신 선택 차량 검증을 재사용한다. 검증된 입력만 `VerifiedBoardingStart`로 `BoardingSessionService`의 짧은 저장 transaction 경계에 전달한다. 현재 Provider 지원 차단은 [API](api.md#탑승-세션-api-task-903)를 따른다. 생성의 중복 확인은 reference 조회 전에 수행하고, 실패한 저장은 원래 요청/검증 입력으로 새 transaction에서 최대 한 번 재확인한다. 지원하지 않는 임의 제약 실패를 UNIQUE 재시도로 숨기지 않는다. 조회·취소는 Session lock 아래 수명을 확인하며, Provider I/O나 후속 Worker를 실행하지 않는다. 재확인은 먼저 owner·상태/revision을 읽고 같은 validator로 transaction 밖에서 최신 후보를 재검증한다. 저장 transaction은 원래 불변 차량/traversal과 최신 metadata·revision·기한을 확인해 같은 세션의 새 baseline을 저장한다. TASK-816/AUD-08은 새 정책의 재확인 baseline도 생성과 같은 `BoardingNotificationService.recordInitialPreparation()` 경로로 즉시 평가한다. 이전 checkpoint와 수신/제공시각·진행·방향이 충돌하지 않고 목적지 이전의 설정 구간이며 Event가 미소비·미만료인 경우에만 checkpoint·TRACKING/revision·Event·최초 Delivery를 같은 transaction에 저장한다. 이 비교는 불확실 공백의 물리적 진행이나 연속성을 주장하지 않으며, 충돌 근거·미검증 baseline·NULL offset은 즉시 알림 대상에서 제외한다. 재확인 mutation 전에 이전 runtime 허용을 닫고, commit 응답 유실은 정확한 결과 snapshot과 해당 Event ID의 존재까지 새 transaction에서 확인한 뒤에만 허용한다. 최초 수명은 연장하지 않으며 상세 충돌/응답 유실 계약은 API 문서를 따른다.

#### Boarding 초기 설정과 만료

| 설정 | 초기값 | 계약 |
| --- | --- | --- |
| `boarding.session.max-lifetime` | `PT4H` | 생성 시 createdAt + 수명으로 expiresAt 고정. polling/복구/재확인으로 연장 없음 |
| `boarding.recovery.max-observation-gap` | `PT2M` | 마지막 검증 observedAt→새 observedAt의 공백 상한. 초과하면 재확인 필요 |
| `notification.decision.ttl.prepare-alight` | `PT30S` | 최초 후보의 originalObservedAt 기준. 기존 Alarm TTL과 독립 |

양수·유한·UTC microsecond 표현과 overflow를 검증한다. 정확도·회차 불가능성·도착 전 표시의 실측 기준이 아닌 조정 가능한 운영 초기값이다. 세션 수명은 방치 추적/점유를 제한하고, 복구값은 여러 기존 polling 기회를 포함한 짧은 중단을 허용하며, 준비 TTL은 늦은 queue/retry 안내를 제한한다. 기존 20초 polling·60초 Observation freshness·Alarm TTL·FOLLOW_UP 5분을 변경하지 않는다. 설정 변경으로 이미 저장한 Session/Delivery 기한을 늘리지 않는다. 기존 세션의 복구 판단은 현재 승인 설정으로 수행하며 시간값만으로 운행 연속성을 보장하지 않는다.

PREPARE_ALIGHT의 기한 E는 `min(originalObservedAt + TTL, session.expiresAt)`이다. 신규 결정은 Session lock 아래 current 유효성·Event 부재 확인 후 INSERT 직전 `now < E`를 요구한다. `now >= E`면 Event·Delivery·소비 표식 없이 반환하고 checkpoint/필요 상태만 반영할 수 있다. 같은 오래된 후보를 now로 바꾸지 않으며 이후 새 유효 관측으로 다시 판단한다. Event INSERT 시점 검증이며 미래 commit 완료 시각을 예측하는 보장은 아니다. 유효하게 INSERT한 뒤 commit 지연으로 기한이 지나면 이미 정해진 Event는 보존하고 worker가 만료 정리한다.

Event 저장이 가능한 경우 eligible Device를 ID 순서 current lock으로 고정하고 기존 ownership generation을 복사해 Delivery를 만든다. 0 recipients도 유효 Event를 소비한다. 저장 후 만료·실패로 Event를 삭제하거나 recipient를 추가하지 않는다. Session 만료는 EXPIRED 전환과 PENDING 종료를 같은 transaction으로 처리한다. Delivery 기한도 Session 기한으로 cap되어 만료 session의 새 FCM 호출을 허용하지 않는다. Delivery의 실제 시간 만료는 EXPIRED, 기한 전 취소·연속성 상실·목적지 도달에 따른 준비 Delivery의 명시 종료는 FAILED/DISPATCH_NOT_ALLOWED이며 terminal과 실제 attempt/result는 보존한다.

#### Notification source와 전송 모델 확장

TASK-906 1단계는 V16, 두 source의 Event Entity와 identity Repository, 공통 Delivery의 Session 기한 일치 검증까지 구현했다. `NotificationEvent.forBoardingSession`은 검증된 `BoardingCheckpoint`에서 Stop ID/order·observedAt·실제 providerDataTime만 복사하는 영속성 입력 경로다. 2단계의 `boarding.service.BoardingNotificationService`는 `Propagation.MANDATORY`로 기존 Session transaction에 참여하며 `BoardingTrackingStore.apply()`에서 검증된 새 진행 결정을 저장하고, 새 정책의 생성·재확인은 `BoardingSessionService`의 기존 transaction에서 검증된 현재 baseline의 준비 결정을 같은 저장 경로로 전달한다. TASK-908 새 정책은 1/2 occurrence 전 PREPARE_ALIGHT만 원자 저장하고 목적지에서는 세션만 완료한다. V20 이전 NULL offset 세션만 기존 사전 ON/OFF와 완료 시 DESTINATION_REACHED를 보존한다. 기존 두 종류 모두 기존 관측 기반 PT30S 설정과 Session expiresAt의 작은 값을 기한으로 사용한다. Polling의 `BoardingTrackingDecision.eventDetectedAt`은 transaction 밖 평가 시각이며 lock 대기로 바꾸지 않는다. 생성·재확인의 즉시 판단은 해당 transaction의 판단 시각을 사용하되 checkpoint의 원래 observedAt·providerDataTime과 그에 따른 TTL을 유지한다. 원래 status/revision·차량·snapshot·현재 metadata·checkpoint 검증과 Session flush 뒤 Event identity를 current lock으로 확인하고, 기존 Event가 없고 최초 INSERT 직전 기한이 유효할 때만 Event를 INSERT한다. 이어 기존 `DeviceRepository.findEligibleByUserIdForUpdate`로 현재 eligible recipient를 ID 순서로 고정해 generation과 부모 기한을 복사한다. Device lock 대기로 기한이 지나도 이미 INSERT한 Event는 보존하며 그 뒤 생성하는 Delivery는 현재 시각에 따라 EXPIRED일 수 있다. 0 recipients log는 commit 뒤 안전한 outcome/type만 남긴다. 준비 알림 상태 조회도 Session → Event 뒤 Delivery의 current read를 사용해, 이전 REPEATABLE READ snapshot 때문에 새 PENDING을 수신자 없음으로 표시하지 않는다. flush/commit 실패는 Session/Event/모든 Delivery를 rollback한다.

알려진 `uk_notification_events_boarding_identity`의 MySQL 1062만 전체 rollback 후 새 persistence context에서 Session과 원래 Event identity를 한 번 확인하며 checkpoint나 recipient를 재적용하지 않는다. 다른 UNIQUE/FK/CHECK는 전파한다. commit 응답 유실은 원래 반영 결과의 정확한 Session snapshot과 Event ID를 새 transaction에서 확인한 경우만 runtime에 공개한다. rollback·최신 상태 변경·재확인 실패에서는 gate를 닫고 새 Event나 recipient를 생성하지 않는다. Device 변경 경합 등 MySQL deadlock도 결정 전체 rollback 경계를 유지하고 즉시 Provider 재호출·blind replay 없이 다음 정상 cycle에서 새 snapshot/관측으로 판단한다.

`BoardingSessionService`의 공통 expire 경계는 상세/current/clientRequestId 조회·기존 생성 요청/현재 세션 확인·취소·재확인을 모두 포함한다. 취소와 `BoardingTrackingStore`의 만료·재확인이 필요한 연속성 상실은 같은 transaction에서 Session source PENDING을 종료한다. 완료는 준비 PENDING만 종료하며, 기존 NULL offset 세션에서 함께 저장한 목적지 PENDING을 보존한다. TEMPORARY_PROVIDER_FAILURE/STALE_PROGRESS 및 기존 자동 재검증 사유 METADATA_CHANGED는 PENDING을 유지하며 후속 전송 gate가 판단한다. terminal/attempt/result를 보존하고 실제 기한 만료를 명시 종료보다 우선한다. lifecycle은 Session → exact Session Event current lock → Event ID별 PENDING Delivery current lock 순서다. Event와 Delivery를 분리 조회해 API 인증이 먼저 연 REPEATABLE READ snapshot 때문에 새로 commit된 Event를 join에서 놓치지 않으며, 기존 Alarm lock/query는 변경하지 않는다. Provider 지원 제한은 유지한다.

TASK-906 3단계는 임시 ALARM-only 차단을 source-aware dispatch로 교체했다. `NotificationSourceHint`는 ALARM/BOARDING_SESSION 두 분기만 검증하며 `NotificationDispatchTarget`·`NotificationSendRequest`가 공유한다. 기존 Alarm 생성자·네 Event Type·payload는 유지하고, 공통 FID/Event ID/expiresAt와 Target의 Delivery/Device/owner/generation/registration revision을 보존한다.

| source hint | 필수값 | 금지값 |
| --- | --- | --- |
| ALARM | 양수 alarmId, 기존 TransitEventType | boardingSessionId/boardingEventType |
| BOARDING_SESSION | 양수 boardingSessionId, PREPARE_ALIGHT / DESTINATION_REACHED | alarmId/TransitEventType |

공통 constructor/validation·`request()`·payload builder는 이 두 분기만 지원하며 가짜 alarmId, 0 sentinel, 범용 plugin/provider framework를 추가하지 않는다. DispatchTarget의 세션 attempt에는 전송 검증 당시 current Session snapshot(revision·checkpoint·원래 세션 기한)을 담고 gate 재검증에 사용한다. 이는 Event의 불변 identity나 SendRequest의 공개 payload가 아니다. SendRequest에는 준비 문구용 실제 목적지 이름만 별도로 전달하며 data navigation hint는 그대로 유지한다. 기존 Alarm constructor 의미·네 type·ALARM_EVENT payload를 보존한다. Notification DB source 분기는 [Schema](database.md#notification-source-확장-task-906-1단계), payload는 [API](api.md#탑승-세션-push와-tap)가 소유한다.

#### 복구 gate와 Worker 공정성

단일 Backend·단일 non-overlapping Worker가 전제다. 메모리 gate는 세션별 `{sessionId, validatedRevision, validatedObservedAt}`의 현재 프로세스 허용 근거이며 기본은 차단이다. Session 생성/복구의 DB commit 확인 후만 열고, 재시작 시 이전 허용 근거를 복원하지 않는다. 오래된 revision의 after-commit 결과가 새 취소/보류를 덮어 열지 못하게 한다. gate는 영속 Source of Truth·claim/lease가 아니며 여러 Backend의 dispatch 안전성을 제공하지 않는다.

Worker cycle은 기존 batch-size를 각 bounded 조회의 상한으로 사용하고 다음 순서로 실행한다. 세션 전용 worker/thread/queue는 없다.

1. **만료 정리:** ALARM, BOARDING_SESSION 각각의 expired PENDING을 독립 제한 조회해 EXPIRED로 종료한다. gate·nextAttemptAt과 무관하며 CONFIGURATION 차단 중에도 두 source의 예산을 유지한다.
2. **Alarm 조회/전달:** ALARM due 조회는 기존 순서·자격·bounded 예산을 유지한다. 세션 대기/세션 만료 row가 이 LIMIT을 점유하지 않는다.
3. **세션 조회/전달:** 준비는 current 프로세스의 허용 session ID 집합과 current DB 상태, 목적지는 불변 COMPLETED 상태를 predicate에 적용한 due 조회다. 미복구/보류 세션을 LIMIT **이전**에 제외하고 nextAttemptAt/id 순서로 제한한다. 조회 후 메모리 filtering만으로 batch 공정성을 구현하지 않는다.

각 cycle은 세 조회에 실행 기회를 주며 Alarm을 우선 처리한다. ready set이 비었어도 만료·Alarm·완료 목적지 조회는 수행한다. 세션 대기 때문에 PENDING 예약/attempt/TTL을 변경하거나 루프에서 기다리지 않는다. runtime은 revision watermark와 별도로 같은 컴포넌트 내부에 검증 snapshot의 정렬 조회 인덱스를 유지한다. 미복구/보류 항목은 이 인덱스에 없으며 새로운 허가 저장소가 아니다. cycle당 최대 batch-size개의 항목을 ID keyset window로 방문하고 cursor를 순환해 무제한 IN/전체 DB Session 스캔을 피한다. 준비 분기는 이 window의 READY ID를 SQL IN predicate에 전달하고 current DB TRACKING/사전 알림 ON/수명/owner 조건을 LIMIT 전에 적용한다. 목적지 분기는 기존 NULL offset 정책에만 READY ID와 무관하게 COMPLETED/수명/owner 조건을 LIMIT 전에 적용한다. due 정렬은 window 안에서 nextAttemptAt/id 순서다. 여러 window의 전역 FIFO나 전체 Session을 한 cycle에 처리한다고 주장하지 않는다.

cycle의 최대 조회/후속 처리 예산은 만료 source별 batch-size(합계 2B), ALARM due B, Session due B다. 실제 FCM 호출은 두 due batch 합계 최대 2B이며 Alarm을 먼저 처리한다. Session I/O로 다음 cycle까지의 wall time은 늘 수 있으므로 이전과 같은 지연 시간을 보장하지 않는다. 기존 Device·Event·due index를 재사용하며 SQL/EXPLAIN의 검증 범위와 비용은 Database를 따른다.

#### 복구 후에도 적용하는 Session dispatch 검증

세션 source의 `beginAttempt`는 immutable Event source로 Session ID를 식별한 뒤 **Session → Delivery → Device** 순서로 current write lock을 얻는다. 기존 Alarm 경로는 **Delivery → Device**를 유지하며 뒤에 Session/Alarm lock을 추가하지 않는다. 세션 결정/취소도 Session을 먼저 잠그며 result transaction은 기존 Delivery → Device만 사용한다.

준비 Session source는 expiry를 우선 처리한 뒤 current status=TRACKING, 사전 알림 ON, 미만료, Event owner/Session owner 일치, 현재 검증 가능한 목적지 이전 진행, gate와 current Session revision 일치, checkpoint freshness, 원래 Event/Delivery 기한을 검증한다. 서울 checkpoint는 실제 providerDataTime을 요구하고 미래 시각/관측 시각 이후의 Provider 시각을 거부한다. Device lock 대기 후에도 시각·gate를 재확인한 뒤 attempt를 저장한다. gate 미준비·일시 보류는 attempt 증가 없이 대기한다. 취소/회복 불가 사유는 lifecycle transaction에서 pending을 명시 종료하며, 완료는 준비 pending만 종료하고 만료는 gate 없이 처리한다. 정상 polling으로 revision이 증가한 경우 새 검증 commit의 gate를 사용한다. **Event 생성 당시 revision과 현재 revision이 같아야 한다는 조건은 두지 않는다.** Event 기한·identity를 새 revision으로 갱신하지 않는다.

목적지 source는 기존 NULL offset 정책의 미만료 COMPLETED와 정확한 도달/통과 reason·target index 관계를 확인하고, Event의 Stop ID/order·observedAt·providerDataTime이 불변 완료 checkpoint와 모두 일치해야 한다. 완료 checkpoint의 수신/제공시각 freshness와 서울 providerDataTime 필수 조건도 전송 직전까지 유지한다. 목적지 도달을 확인한 뒤 완료를 저장했으므로 새 프로세스에서 live-progress gate 복구를 요구하지 않으며 과거 checkpoint로 새 Event를 생성하지 않는다. Worker는 준비 READY ID 또는 완료 목적지 Event를 같은 bounded Session 예산에서 조회하고, 별도 Alarm 예산을 보존한다.

그 뒤 기존 recipient preflight와 durable attempt 시작을 commit하고 모든 DB lock을 해제한 뒤 FCM을 호출한다. 실제 호출 직전에도 준비는 gate의 attempt revision, 목적지는 불변 완료 근거를 확인하고 두 종류 모두 관측 유효성·기한을 확인한다. 시작 commit 후 gate가 닫혀 호출을 생략하면 이미 소비한 count를 되돌리거나 가짜 Provider 결과를 만들지 않고 기존 bounded recovery/종료 경로로 처리한다. 복구 검증은 전송 권한의 영구 허가가 아니다.

취소 및 polling checkpoint/보류/종료 변경은 Session lock 아래 mutation 전에 이전 revision의 gate를 닫는다. 새 검증 checkpoint는 commit 확인 후만 공개한다. 세션 취소·만료·연속성 상실은 pending을, 목적지 도달은 준비 pending을 원자 종료하며 늦은 결과로 되살리지 않는다. gate 사전 차단 뒤 DB rollback이면 보수적으로 다음 재검증까지 닫힌 상태를 유지할 수 있다. 마지막 메모리 검증과 `FcmProviderClient.send`/실제 HTTP 시작 사이에는 원자성이 없다. 그 사이 취소 commit·Device 변경이 발생하거나 이미 요청이 시작/접수됐다면 회수 또는 새 호출의 완전 차단을 보장하지 않는다. 네트워크 동안 DB lock을 유지하지 않는 기존 한계다. DB 장애/commit 불확실 중에는 gate를 열거나 새 외부 요청을 시작하지 않는다.

TASK-905는 checkpoint/복구와 lifecycle 판단을, TASK-906은 위 atomic Notification 연결·source·gate·fair query·전송 검증을 구현한다. TASK-906의 Backend dispatch 구현과 실제 Provider 지원/기기 표시 검증은 구분한다. 실제 세션 생성·재확인은 무조건 차단하지 않으며 [API의 조건부 지원 범위](api.md#탑승-세션-api-task-903)를 따른다. Flutter 수신·상세는 위 TASK-907 3단계를 따르며 실차/기기 실측은 TASK-908 및 기존 TASK-710/711에 남는다.

### common

실제로 공유가 필요한 횡단 관심사를 둔다. `common`을 잡동사니 저장소로 만들지 않는다.

## 7. 모니터링 모델

현재 V1은 Spring 스케줄 작업으로 ACTIVE/FOLLOW_UP Alarm과 미종료 BoardingSession을 주기적으로 관측·평가한다.

다만 다음과 같은 단순한 모델은 피해야 한다.

```text
활성 사용자 알림 하나 = 외부 API 요청 하나
```

같은 Route의 Alarm은 polling key로 그룹화하며, BoardingSession도 같은 Scheduler cycle의 `VehiclePollingCycle` 원본 응답을 공유한다.

V1의 기본 Provider polling key는 TAGO의 `(provider, externalRouteId, cityCode)`와 서울의 `(provider, externalRouteId)`다. `cityCode`는 Route identity가 아니라 TAGO request context이지만 동일 polling request 재현에는 필요하다. 같은 Route를 사용하는 여러 사용자·target Stop·ACTIVE Alarm·FOLLOW_UP Alarm은 가능한 한 하나의 Route polling response를 공유한다. Alarm별 Provider 호출이나 MyBatis 도입은 기본 구조로 삼지 않는다.

TASK-508은 JPA `EntityGraph`로 BUS `ACTIVE`/`FOLLOW_UP` Alarm과 `BusAlarmTarget`을 함께 조회한 뒤 Java에서 polling key별로 그룹화한다. `INACTIVE`는 조회 대상이 아니며, target 누락이나 provider request context가 깨진 monitoring Alarm을 query에서 누락·보정하지 않고 명시적으로 실패시킨다. 실제 Provider 호출·Observation 평가·Scheduler·실패 시 Event 없는 `UNKNOWN` 처리는 TASK-509~511에서 구현했다.

TASK-510 Scheduler는 단일 Spring instance에서 Spring Scheduler의 synchronous/fixed-delay 방식으로 실행한다. `transit.monitoring.enabled=false`가 기본이며, 명시적으로 enabled일 때만 `PT20S` 기본 delay가 이전 polling cycle 완료 뒤 적용되어 invocation이 겹치지 않는다. Provider HTTP I/O는 DB transaction 또는 row lock 밖에서 수행한다. 결과 반영 직전에만 Alarm을 `PESSIMISTIC_WRITE`로 다시 읽어 polling 시작 snapshot의 status와 activation generation을 검증하고, 삭제·deactivate·reactivate 등으로 달라졌으면 lifecycle과 memory state를 모두 버린다. API activate/deactivate/delete도 같은 짧은 row-lock transaction을 사용한다. `@Version`, CAS, optimistic locking은 함께 사용하지 않는다. Scheduler memory state key는 `(alarmId, activationGeneration)`이고, 매 cycle의 ACTIVE/FOLLOW_UP 집합 밖 key는 정리한다. TAGO는 Route Location 응답 하나를 group에 공유하며 그 Observation의 vehicle ID 전체를 현재 존재 집합으로 사용한다. 서울은 roster 하나 뒤 baseline/tracked/new/FOLLOW_UP vehicle만 detail 조회하고, roster `vehId` 전체를 detail Observation과 별도로 Evaluator에 전달한다. roster에 존재하지만 detail이 normal empty인 차량은 존재만 갱신하며 위치·Event를 추측하지 않는다. Alarm별 Event 후보는 ACTIVE에서 `ARRIVED`·`PASSED`·`ONE_STOP_BEFORE` 우선순위, 같은 type이면 `vehicleTrackingId` 오름차순으로 하나만 채택하고 FOLLOW_UP에서는 `ONE_STOP_AFTER`만 사용한다. current ARRIVED가 after option으로 FOLLOW_UP을 시작하면 해당 차량의 memory tracking state와 `trackingCycleId`만 이어가며, `followUpStartedAt`/`followUpExpiresAt`은 Event `observedAt`의 UTC와 5분 timeout으로 정한다.

### Scheduler 실행 자원 분리 (TASK-816 / AUD-04)

`SchedulingConfiguration`은 pool size가 각각 1인 두 `ThreadPoolTaskScheduler`를 제공한다. `BusAlarmMonitoringScheduler.pollMonitoringAlarms`는 `@Scheduled(scheduler="transitMonitoringScheduler")`로 Alarm·BoardingSession polling 전체를 `transit-monitoring-` 스레드에 연결하고, `NotificationDispatchWorker.dispatchPending`는 `notificationDispatchScheduler`의 `notification-dispatch-` 스레드에서 실행한다. Provider 대기와 FCM 대기가 서로의 Scheduler를 점유하지 않는다. 추가 queue/비동기 executor나 전역 lock은 도입하지 않는다.

기존 enabled 기본값 false와 fixed-delay(각각 PT20S/PT1S)를 유지한다. 각 작업은 단일 스레드에서 이전 invocation 완료 뒤 delay를 적용하며, Monitoring 메서드의 `synchronized`와 Dispatch의 `AtomicBoolean` guard도 유지한다. 실행 자원 분리는 여러 Backend나 여러 Dispatch Worker를 허용하는 변경이 아니다. DB commit/current locking read, Session mutation 전 gate 차단·commit 확인 후 공개 및 전송 직전 검증은 [기존 Session dispatch 경계](#복구-후에도-적용하는-session-dispatch-검증)를 그대로 따른다.

Context close 시 Scheduler는 새 제출·반복·지연 작업을 차단하고 취소된 task를 queue에서 제거한다. Spring의 coordinated lifecycle stop으로 실행 중 작업이 끝나기를 기다린 뒤 의존 Bean을 종료한다. `waitForTasksToCompleteOnShutdown=true`에 의한 늦은 종료 대신 기본 lifecycle 대기 경로를 사용한다. Monitoring은 종료 신호 뒤 새 cycle/후속 polling group·Boarding polling·retry를 시작하지 않고, Dispatch는 기존대로 후속 후보를 시작하지 않으며 진행 중 FCM의 결과 transaction을 마친다. Provider/FCM timeout·retry는 변경하지 않는다. lifecycle shutdown budget 초과나 강제 종료의 recovery/중복 한계와 운영 budget 검증은 기존 TASK-811 범위에 남는다.

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

`UNKNOWN`은 Notification Event가 아니라 판단 불가 결과다. 정상 응답 안의 애매한 관측과 TASK-503에서 구분한 Provider failure는 원인이 다르지만 둘 다 거짓 ARRIVED/PASSED Event를 만들지 않는다. TASK-511은 Provider failure를 정상 empty와 분리해 Event 없는 UNKNOWN으로 처리한다. TAGO Route 또는 서울 roster 실패는 해당 Route 전체의 평가를 건너뛰고, 서울 detail 일부 실패는 성공 차량과 roster 전체 presence를 즉시 평가하면서 실패 차량의 위치만 판단하지 않는다. 첫 단계의 모든 Route를 처리한 뒤 실패한 Route 요청과 vehicle detail 요청만 모아 `transit.monitoring.failure-retry-delay=PT5S`로 한 번 기다리고 각각 1회 재시도한다. detail 재시도는 roster를 다시 조회하거나 이전 roster를 새 presence로 재사용하지 않고 성공 Observation만 최신 memory state에 적용한다. 재시도 중 새 실패는 다시 재시도하지 않으며 최종 실패는 기존 tracking/lifecycle을 보존하고 다음 일반 cycle을 기다린다. 지연과 Provider HTTP 동안 transaction 또는 row lock을 유지하지 않고, 재시도 결과도 적용 직전에 status/generation을 검증한다. 별도 retry framework, circuit breaker, 비동기 worker는 도입하지 않는다.

TASK-816/AUD-09는 서울 detail Mapper에 조회한 vehicle ID를 전달해 모든 item의 `vehId`가 해당 ID와 일치하는지 확인한다. NULL item·누락/공백/다른 차량 ID, 형식이 잘못되거나 존재하지 않는 `dataTm`은 `SEOUL_BUS` / `getBusPosByVehIdItem`의 `PROTOCOL` 실패로 표현한다. 시각은 Asia/Seoul의 `uuuuMMddHHmmss`를 STRICT로 해석해 잘못된 날짜의 자동 보정을 막고, 누락/공백 시각과 선택 필드의 기존 NULL 의미는 유지한다. Mapper는 모든 item 변환이 끝나야 목록을 반환하므로 잘못된 item이 하나라도 있으면 해당 detail의 일부 Observation도 집계하지 않는다. 동일 차량의 중복·충돌 item은 임의 선택하거나 합치지 않고 기존 Evaluator의 ambiguity 정책으로 판단한다. 내부 호출 인자 오류나 임의의 RuntimeException은 Provider 실패로 변환하지 않는다.

이 Mapping 실패도 위의 기존 차량별 catch·로그·1회 재시도를 재사용한다. 다른 정상 차량의 Observation과 전체 roster presence를 먼저 평가하고, 실패 차량의 이전 검증 위치·시각·cycle은 보존하며 presence만 기존 경로로 갱신한다. 정상 empty는 실패나 재시도 대상으로 바꾸지 않는다. `VehiclePollingCycle`의 raw response/실패 공유와 재시도 cycle의 고유 vehicle ID 중복 제거는 유지한다. detail 재시도 성공은 `evaluatePartial()`을 거쳐 AUD-02의 시각 검증과 기존 lifecycle/generation·Event 중복 방지 정책을 그대로 적용한다. 기존 로그에는 Provider·operation·failureKind·Route·vehicle·최초/최종 시도만 남기며 원문 응답이나 파싱 입력을 추가하지 않는다.

일반 tracking의 Event precedence는 target에서 도착을 충분히 관찰한 `ARRIVED`, target 이전에서 이후로 건너뛴 `PASSED`, `ONE_STOP_BEFORE` 순이다. ARRIVED 후 동일 차량 follow-up에서는 `ONE_STOP_AFTER`만 평가하며 PASSED로 재분류하지 않는다. Stop order는 같은 방향·Route traversal 문맥에서 비교할 수 있을 때만 사용한다.

TASK-816/AUD-02는 기존 차량 state가 있는 ACTIVE/FOLLOW_UP에서 Event 평가와 위치 갱신 전에 시간·순서 단조성을 함께 검사한다. Stop order와 `observedAt`이 역행하면 거부하고, 현재와 마지막 유효 Provider 시각이 모두 있으면 현재 시각이 엄격히 증가해야 한다. 같은 위치에서 동일 Provider 시각의 arrival evidence만 달라진 경우도 새 도착 근거로 사용하지 않는다. `hasNewProgressEvidence()`는 이 검증과 엄격한 Stop order 증가를 함께 요구한다. 기존 freshness 60초·방향·Stop ID/order·GPS 검증 및 Provider별 Event 정책은 유지한다.

TAGO Mapper의 Provider 시각은 NULL이고 서울 Mapper도 `dataTm` 누락/공백을 NULL로 보존한다. 비교할 Provider 시각이 없으면 기존의 시각 없는 관측을 허용하되 `observedAt`이 증가한 응답만 반영한다. 이는 수신 순서 검증이며 upstream freshness 보장이 아니다. `VehicleTrackingState.lastKnownProviderDataTime`은 마지막 검증 Provider 시각을 메모리에만 보존해 중간 NULL 관측 뒤 동일·역행 시각이 다시 들어오는 우회를 차단한다. 원본 Observation/Event의 NULL 시각은 보완하거나 합성하지 않는다.

거부된 관측은 마지막 위치·유효 Provider 시각·before 이력·발행 Event 집합·cycle UUID를 유지하고, 기존 presence/missing grace 경로로 `lastSeenAt`만 갱신한다. FOLLOW_UP의 Stop ID/order 충돌도 위치와 시각 기준을 덮어쓰지 않는다. 이후 정상 관측은 보존된 기준으로 다시 평가하며 같은 cycle의 중복 방지를 유지한다. `baselineAfterVehicles`의 기존 억제·실제 부재 만료 정책도 유지한다. 이전 state가 없는 최초/신규 차량의 Baseline은 비교할 과거 시각을 만들지 않고 기존 서울 직접 ARRIVED·TAGO GPS 근거를 사용한다. FOLLOW_UP 메모리가 없는 복구도 기존 fresh successor 관측과 영속 UUID·만료 시각을 사용하며, restart 전 Provider 시각과의 비교까지 보장하지 않는다. BoardingSession, Scheduler 실행 자원, Lifecycle의 generation/transaction/재시도 및 Notification identity는 변경하지 않는다.

### Alarm과 Vehicle Tracking lifecycle

Alarm 활성화 시 현재 Route 차량을 baseline으로 분류한다. Target 이전 차량은 추적 후보이고, before 옵션이 켜진 상태에서 정확히 predecessor인 차량은 즉시 ONE_STOP_BEFORE 후보가 된다. Target 차량은 충분한 근거가 있으면 즉시 ARRIVED이며, 이미 Target 이후인 차량은 기존 passed vehicle로 무시한다.

PASSED는 해당 Vehicle tracking만 종료하고 Alarm은 ACTIVE로 유지한다. ARRIVED는 Alarm 성공 Event이며 after 옵션이 꺼져 있으면 Alarm을 INACTIVE로 전환하고 다른 Vehicle tracking을 종료한다. after 옵션이 켜져 있으면 Alarm을 ONE_STOP_AFTER 전용 FOLLOW_UP으로 전환하고 ARRIVED를 발생시킨 동일 차량만 다음 Stop 도달·통과까지 추적한다. FOLLOW_UP의 차량 tracking ID와 시작·만료 시각은 Alarm에 영속하여 재시작 뒤 복구할 수 있게 한다.

FOLLOW_UP 중 같은 Alarm의 새 activation은 persisted activation generation을 증가시켜 이전 cycle을 supersede한다. 기존 follow-up runtime을 지우고 ACTIVE 상태의 새 baseline과 monitoring cycle을 시작한다. 비활성화·follow-up 완료도 runtime을 지우며 Alarm 삭제는 runtime과 BusAlarmTarget을 함께 제거한다. FOLLOW_UP runtime은 서버 restart 뒤에도 저장된 vehicle tracking ID와 유효 기간으로 재사용한다. 반면 ACTIVE의 차량별 observation/event state는 V1에서 memory 기반일 수 있다. `trackingCycleId` 또는 동등한 값은 transaction ID가 아니라 logical vehicle tracking cycle identity로 cycle 시작 시 한 번 생성한다. 같은 logical cycle의 lifecycle transaction이 deadlock/optimistic conflict로 retry되어도 identity를 다시 만들지 않으며 retry로 Notification dedup uniqueness를 우회해서는 안 된다. TransitEvent가 생기면 이를 durable NotificationEvent에 복사할 수 있다. restart 뒤에는 이전 memory tracking을 새 cycle과 연결하지 않고 안전한 recovery baseline을 만들며, restart 전 observation으로 PASSED를 추론하거나 predecessor만으로 ONE_STOP_BEFORE를 재발행하지 않는다. 일부 Event 누락보다 false-positive 방지를 우선하고 모든 raw Provider observation 저장이나 event sourcing은 도입하지 않으며, restart continuity 충족 여부는 TASK-811에서 검증한다.

반복 `TransitObservation`과 중복 `TransitEvent` candidate의 억제는 Phase 5 tracking/Evaluation 책임이다. 동일 logical Notification의 중복은 Phase 7 persistence 책임이며 identity는 `(alarmId, activationGeneration, trackingCycleId, eventType)`이다. TASK-708 설계에서 확정한 [DB UNIQUE Schema](database.md#phase-7-notification-persistence-contract)는 TASK-707에서 적용했다.

TASK-707은 FOLLOW_UP에 원래 cycle UUID 저장/제거와 ARRIVED 연결을 구현했고 TASK-708은 원래 UUID를 사용하는 평가기 재시작 복원을 구현했다. 현재 FOLLOW_UP은 이 영속 UUID로 복원하며 [Alarm Domain](domain-model.md#alarm-lifecycle)과 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#follow_up-cycle-continuity-결정)을 따른다. ACTIVE baseline 정책은 변경하지 않으며 기존 restart 재발행 억제 방향과 현재 evaluator의 차이는 ADR-010 조사 결과에 기록한다. logical DB uniqueness가 ACTIVE restart 전후의 서로 다른 UUID cycle까지 합치지는 않는다.

구체적인 Observation과 Event 의미는 `adr/ADR-007-bus-alarm-transit-observation-and-event-semantics.md`를 따른다.

## 9. 전달 의미론

V1의 보장 경계는 다음과 같다.

```text
logical NotificationEvent         → DB uniqueness 기준 한 번
NotificationEvent × Device record → DB 기준 하나
FCM request                       → bounded retry로 여러 번 가능
실제 Device 표시                  → exactly once 보장하지 않음
```

Provider `accepted`는 요청 접수를 뜻하며 실제 표시 성공을 뜻하지 않는다. Timeout은 Provider가 접수했는지 알 수 없는 ambiguous 결과다. retry 가능한 Provider result는 `PENDING` Delivery의 attemptCount와 nextAttemptAt으로 재시도한다. 최대 횟수·간격·freshness TTL의 개발 기본값과 상한은 TASK-709에서 구현했으며 [설정 계약](adr/ADR-010-notification-device-and-durable-delivery.md#task-709-구현-설정과-검증-2026-10-13)을 따른다. 실제 지연·운영 적합성 검증은 TASK-710/711/810에 남는다. 모든 attempt를 append-only row로 저장하거나 generic retry framework를 도입하지 않는다.

Push payload는 navigation hint에 필요한 최소 정보만 포함하고 권한 근거로 사용하지 않는다. Flutter는 Auth Session 초기화 뒤 source별 Alarm/BoardingSession ID navigation entry를 사용해 Backend의 정확한 ID 상세 API에서 ownership/current state를 재확인한다. Permission이 없어도 다른 Device가 수신할 수 있으므로 Alarm 생성·활성화를 금지하지 않는다. 현재 installation logout은 Device unsubscribe/disable을 시도한 뒤 기존 Auth logout과 local session 종료를 수행하되, offline에서는 Backend disable을 즉시 보장하지 않는다.

세부 결정은 `adr/ADR-010-notification-device-and-durable-delivery.md`를 따른다.

## 10. 확장 경로 — 필요한 경우에만

```text
단일 Spring 인스턴스
      ↓
측정된 병목
      ↓
가능한 개선
- cross-instance shared polling/cache
- 공유 상태용 Redis
- 외부 message broker 기반 fan-out
- 여러 백엔드 인스턴스
```

MySQL pending dispatch를 처리하는 in-process worker는 Phase 7 기본 구조다. Redis, Kafka, RabbitMQ, Kubernetes, 마이크로서비스와 multi-instance distributed lock은 **기본 요구사항이 아니다**.

## 11. 배포 방향

V1 production은 하나의 persistent Spring Boot runtime, durable MySQL, HTTPS/domain 또는 동등한 secure public endpoint, runtime secret injection, restart policy와 deployment smoke를 필요로 한다. 특정 vendor/product를 지금 정하지 않으며 development `docker-compose.yml`은 production topology가 아니다.

Backend image는 Java 21 runtime, Gradle Wrapper build 또는 CI-built bootJar, non-root 실행, SIGTERM 전달, UTC timezone, stdout/stderr logging, health/readiness integration과 graceful shutdown budget을 갖는 재현 가능한 runtime image를 목표로 한다. image size 최적화는 correctness보다 우선하지 않는다.

liveness는 JVM/process 생존을, readiness는 DB 연결, Flyway migration 적용 완료, 필요한 component 초기화, scheduler/outbox worker 실행 가능을 뜻한다. Provider/FCM의 일시적 장애는 Backend readiness를 DOWN으로 만들지 않는다. Provider 최근 실패, scheduler last completion, outbox backlog, metadata 마지막 complete sync age는 business/dependency health로 관찰하되 public response에 상세 내용을 과도하게 노출하지 않는다.

최초 fresh DB deployment에서는 필요한 metadata bootstrap이 끝나기 전 public Route/Alarm 생성 traffic을 받지 않는다. 반대로 정상 운영 중 metadata stale만으로 기존 Alarm monitoring을 unready로 만들지 않는다.

deployment와 SIGTERM 때는 새 polling/notification dispatch cycle을 시작하지 않고, 진행 중 DB transaction은 정상 commit 또는 rollback한다. Provider/FCM timeout은 shutdown budget보다 짧게 제한하고 pending delivery는 restart 뒤 durable outbox에서 복구한다. FCM accepted 뒤 DB update 전 crash하면 Delivery가 PENDING으로 남아 retry될 수 있으며 actual Device duplicate는 exactly-once 비보장 계약으로 허용한다. incomplete metadata sync는 provider cleanup의 근거가 아니다.

첫 production 적용 뒤 Flyway migration file은 수정하지 않고 변경을 새 migration으로 추가한다. 배포 전 migration 영향과 backup/restore point를 확인하고 migration 실패 instance는 ready가 되어서는 안 된다. DB downgrade는 임의로 하지 않으며 application rollback은 새 Schema와 old application compatibility를 확인한 경우만 한다. 공개 사용자 데이터를 받기 전 자동 DB backup 또는 platform snapshot, 가능하면 PITR, deploy 전 restore point와 실제 restore drill 최소 1회를 검증한다. Alarm/User/BusAlarmTarget은 복구 중요도가 높고 Transit metadata는 source에서 재생성할 수 있으며 pending notification은 freshness policy를 고려한다. Restore 뒤에는 outbound polling/notification dispatch를 격리한 상태에서 Flyway/schema, RefreshToken 복구 정책, Device ownership/registration, metadata sync, Alarm recovery baseline, stale pending Notification expiry/cutoff를 순서대로 확인한 뒤 worker/scheduler와 public readiness를 재개한다. Restore epoch나 별도 recovery subsystem은 요구하지 않는다.

V3→V4 Legacy 업그레이드는 [Database 지원 범위](database.md#v3v4-legacy-user-업그레이드-지원-범위-task-816--aud-11)와 [실행 전 점검](local-development.md#v4-미적용-db의-read-only-preflight-task-816--aud-11)을 따른다. V3에는 외부 Identity 근거가 없으므로 기존 User를 자동 변환하거나 다른 Google 계정에 연결하지 않는다. V4 미적용 DB는 User 0명 조건을 확인한 뒤 기존 startup Flyway 경로로 적용하며, User가 있으면 실행 전에 중단하고 별도 이관 판단을 받는다. 사용자 한 명에서는 SQL 자체가 성공할 수 있어 Flyway 실패를 사전 차단 장치로 의존하지 않는다. 이 작업에서는 startup 순서 변경·자동 차단 코드·DB 수정 callback·새 Dependency를 추가하지 않으며, 적용 전 수동 Preflight를 필수 절차로 둔다. 이미 적용된 V4와 History는 수정하지 않는다.

Kubernetes, multi-region, read replica, blue/green deployment framework는 V1 기본 요구가 아니다.

## 12. 아키텍처 원칙

1. 규모 과시보다 정확성을 우선한다.
2. 최적화 전에 측정한다.
3. 합리적인 범위에서 외부 제공자 세부 사항을 핵심 알림 규칙과 분리한다.
4. 알림을 유발하는 작업에는 멱등성을 고려한다.
5. 비즈니스 결정은 코드뿐 아니라 문서에도 남긴다.
