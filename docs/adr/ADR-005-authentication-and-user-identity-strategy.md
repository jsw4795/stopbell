# ADR-005: Authentication 및 User Identity 전략

- 상태: 채택됨
- 날짜: 2026-09-04

## 배경

StopBell은 Flutter Mobile Application과 분리된 Spring Boot REST API로 구성된다. Alarm과 향후 Push Device를 올바른 사용자에게 연결하고, 앱 재실행 뒤에도 안전하게 로그인 상태를 유지해야 한다.

초기에는 Authentication provider, User Identity 모델, Session/Token 전략이 결정되지 않았다. Alarm API보다 먼저 최소 Authentication 기반을 마련하지 않으면 Client가 제공한 `userId`를 신뢰하는 임시 계약이 필요해진다.

## 검토한 선택지

### 선택지 A — 서버 Session 기반 인증

장점:

- 서버가 Session 상태를 직접 무효화할 수 있음
- Browser 중심 애플리케이션에서 단순한 Cookie 흐름을 제공할 수 있음

단점:

- Flutter Mobile Client와 분리된 REST API에서 Session Cookie 및 장기 로그인 상태를 별도로 관리해야 함
- API 인증 상태를 확장하거나 여러 Client를 지원할 때 서버 상태 관리 부담이 커짐

### 선택지 B — 외부 Provider Token을 StopBell API 인증에 직접 사용

장점:

- StopBell 자체 Token 발급·갱신 흐름이 없음

단점:

- 외부 Identity 증명과 StopBell Application API 인증 책임이 섞임
- Provider Token 수명과 StopBell의 Logout·장기 로그인 정책을 독립적으로 관리하기 어려움

### 선택지 C — Google Social Login 후 StopBell Access/Refresh Token 발급

장점:

- Mobile REST API에 적합한 Bearer Token 인증
- 짧은 JWT Access Token과 Rotation되는 Refresh Token으로 장기 로그인 UX와 Token 노출 영향을 분리
- Google은 Identity 확인, StopBell은 Application API 인증을 각각 담당
- User 내부 PK를 Alarm과 Device의 안정적인 참조로 유지

단점:

- Refresh Token 저장, Rotation, 무효화 구현이 필요함
- Google ID Token의 유효성과 대상 Application 검증이 필요함

## 결정

StopBell은 자체 ID/Password 계정 없이 Social Login만 지원하며, 최초 Provider로 Google을 구현한다.

Flutter는 Google Login으로 ID Token을 받고 이를 Backend에 전달한다. Backend는 Google ID Token의 유효성과 대상 Application을 검증하고 `sub`를 `providerUserId`로 사용해 User를 조회 또는 생성한다. 그 뒤 StopBell 자체 Access Token과 Refresh Token을 발급한다. Google Token은 StopBell Application API의 장기 인증 Token으로 사용하지 않는다.

Access Token은 JWT이며 기본 수명은 1시간이다. 서버 Database에 저장하지 않고 Spring Security가 검증해 StopBell User를 Principal로 식별한다. Logout 때 Access Token blacklist는 만들지 않으며, 이미 발급된 Access Token은 만료 시점까지 유효할 수 있다.

Refresh Token은 충분히 높은 Entropy의 Secure Random opaque token으로 발급한다. 기본 수명은 발급 시점부터 30일이며, 재발급에 성공할 때 새 Access Token과 새 Refresh Token을 함께 발급하고 기존 Token을 폐기한다. 새 Refresh Token의 수명은 새 발급 시점부터 30일이다. 만료되거나 유효하지 않으면 다시 Google Login이 필요하다.

User는 내부 `id`를 유지하고 `provider`, `providerUserId`를 직접 가진다. `(provider, providerUserId)`에는 Database Unique Constraint를 둔다. 별도 AuthIdentity Entity, Account Linking, Account Merge는 현재 만들지 않는다. 서로 다른 Provider 계정은 동일한 실제 사용자가 사용하더라도 별도 User로 취급한다.

Refresh Token은 User와 분리된 Entity에 원문의 SHA-256 Hash만 저장한다. User별 여러 Refresh Token을 허용해 여러 Login Session을 지원하고, RefreshToken과 Push Device는 직접 연결하지 않는다.

Flutter는 Access Token과 Refresh Token을 OS Secure Storage에 저장한다. 마지막 Login Provider는 민감한 인증 정보가 아닌 UX 정보이므로 일반 Local Storage에 저장할 수 있다.

## 근거

이 방식은 Flutter Mobile Client와 Backend REST API의 분리된 구조에 맞고, 1시간 Access Token으로 노출 영향을 제한하면서 30일 Refresh Token Rotation으로 장기 로그인 UX를 제공한다. User에 외부 Identity를 직접 저장하는 모델은 최초 Google Provider와 현재 규모에 필요한 최소 구조이며, 별도 Identity Domain을 만들지 않아도 외부 계정과 내부 User를 안정적으로 연결한다.

## 결과

- Alarm API는 처음부터 인증된 StopBell User를 기준으로 소유권을 처리하며 Client 제공 `userId`를 받지 않는다.
- Spring Security는 Bearer JWT 검증, SecurityContext/Principal 설정, 보호 Endpoint 및 인증 실패 처리를 담당한다.
- Access Token blacklist, Redis, 다중 Provider 동시 구현, Account Linking/Merge, RefreshToken-Device 연결은 현재 범위에서 제외한다.
- User Identity와 RefreshToken Schema 변경은 Flyway Migration과 함께 구현한다.

## 재검토 시점

다음 조건이 확인되면 결정을 재검토한다.

- Google 이후 Provider 지원이 실제 제품 요구사항이 됨
- 동일 사용자의 Provider 계정 연결 또는 병합이 필요함
- 보안 사고 또는 운영 요구로 Access Token 즉시 무효화가 필요함
- Refresh Token 정리, Device 연결, 세션 관리에 현재 최소 모델보다 많은 상태가 필요함
