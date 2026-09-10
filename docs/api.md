# API 설계

## 1. 상태

이 문서는 초기 API 형태만 정의한다. 교통 데이터 제공자가 확정된 후 관련 엔드포인트는 변경될 수 있다.

StopBell Application API의 기본 접두사 후보:

```text
/api/v1
```

운영·관리 endpoint는 Application API와 분리하여 `/actuator/*`에 둔다.

## 2. 설계 규칙

- 요청/응답 본문에는 JSON을 사용한다.
- 가능한 경우 리소스에는 명사를 사용한다.
- HTTP 상태 코드를 일관되게 사용한다.
- 외부 교통 데이터 제공자의 원본 응답 객체를 앱에 직접 노출하지 않는다.
- 백엔드 경계에는 안정적인 StopBell DTO를 정의한다.
- 오류 응답은 구조화하고 기계가 읽을 수 있어야 한다.
- `/actuator/*`는 운영·관리 endpoint에 사용한다.
- `/api/v1/*`는 StopBell Application API에 사용한다.

## 3. 운영 endpoint

### 상태 확인

```http
GET /actuator/health
```

Spring Boot Actuator가 제공하는 공식 Application health endpoint이며, 배포·가용성·모니터링 확인에 사용한다.

별도의 `GET /api/v1/health` Application API는 제공하지 않는다.

## 4. 후보 Application API

### 버스 노선 검색

```http
GET /api/v1/bus-routes?query={query}
```

응답 형태는 아직 확정되지 않았다.

### 버스 노선의 정류장 조회

```http
GET /api/v1/bus-routes/{routeId}/stops
```

Route identifier는 Provider namespace 안의 opaque external reference다. 이를 path/query/response에 표현하는 정확한 형태는 Transit API 구현 Task에서 결정한다.

### 알림 생성

```http
POST /api/v1/alarms
Content-Type: application/json
```

Alarm 생성 Request의 구체적인 JSON은 TASK-402에서 확정한다. 의미상 Provider namespace 안의 Route/Stop external reference와 target Stop order 및 필요한 traversal/direction context를 통해 사용자가 선택한 Target occurrence를 표현하고, 표시 metadata, 필요한 Provider request context와 서로 독립적인 before/after option을 포함해야 한다. first Stop의 before option 및 last Stop의 after option은 Backend에서도 invalid request로 처리할 수 있어야 하지만 구체적인 field naming과 HTTP error는 아직 확정하지 않는다. 미래 확장을 이유로 필드를 추가하지 않는다.

### 알림 목록 조회

```http
GET /api/v1/alarms
```

### 알림 조회

```http
GET /api/v1/alarms/{alarmId}
```

### 알림 활성화

후보:

```http
POST /api/v1/alarms/{alarmId}/activate
```

구현 전에는 다른 REST 형태도 검토할 수 있다.

활성화 시 `FOLLOW_UP`인 Alarm은 persisted follow-up runtime을 지워 이전 ARRIVED short follow-up을 취소하고 `ACTIVE`의 새 baseline과 monitoring cycle을 시작한다. before 옵션이 켜져 있고 baseline 차량이 Target predecessor에 있으면 즉시 ONE_STOP_BEFORE 후보가 될 수 있다. 구체적인 response와 동시성 처리는 TASK-402 및 후속 구현 Task에서 결정한다.

### 알림 비활성화

```http
POST /api/v1/alarms/{alarmId}/deactivate
```

### 알림 삭제

```http
DELETE /api/v1/alarms/{alarmId}
```

Alarm 삭제는 active monitoring뿐 아니라 Alarm row에 저장된 진행 중 follow-up runtime과 공유 PK BusAlarmTarget도 함께 제거한다. 구체적인 scheduler coordination은 후속 Task에서 결정한다.

### 기기 등록

푸시 연동을 도입할 때 필요하다.

후보:

```http
POST /api/v1/devices
```

후보 본문:

```json
{
  "platform": "IOS",
  "pushToken": "..."
}
```

정확한 기기 생명주기 및 토큰 갱신 전략은 아직 결정되지 않았다.

## 5. 오류 형식

후보 형식:

```json
{
  "code": "ALARM_NOT_FOUND",
  "message": "Alarm was not found."
}
```

관측성이 필요해질 때 선택적으로 trace/request ID를 추가할 수 있다.

## 6. 인증

StopBell Application API는 다음 형식의 StopBell 자체 JWT Access Token으로 인증한다.

```http
Authorization: Bearer <Access Token>
```

Google ID Token은 로그인 시 외부 Identity를 확인하기 위해 Backend에 전달할 뿐, Application API의 인증 헤더에 사용하지 않는다. Access Token 기본 수명은 1시간이며, Refresh Token은 Access Token 재발급과 현재 Session Logout에 사용한다.

### Google 로그인

```http
POST /auth/google
Content-Type: application/json
```

요청 본문:

```json
{
  "idToken": "<Google ID Token>"
}
```

Backend는 `GOOGLE_SERVER_CLIENT_ID`에 설정한 Backend용 Google Server Client ID를 audience로 하여 Google ID Token을 검증한다. 검증된 Google OpenID Connect `sub`를 `providerUserId`로 사용해 StopBell User를 조회하거나 생성한 뒤, StopBell 내부 `User.id`를 `sub`로 하는 Access Token과 Refresh Token을 반환한다.

응답 본문:

```json
{
  "accessToken": "<StopBell Access Token>",
  "refreshToken": "<StopBell Refresh Token>"
}
```

이 Endpoint의 `POST` 요청만 Access Token 없이 호출할 수 있다. 잘못된 Google Credential, 검증 실패, 또는 누락된 `sub`에는 `401 Unauthorized`를 반환한다. Google 공개키 조회 등 Google Identity 검증 Infrastructure의 I/O 실패에는 Credential 오류로 처리하지 않고 `503 Service Unavailable`을 반환한다.

### Refresh Token 재발급

```http
POST /auth/refresh
Content-Type: application/json
```

요청 본문:

```json
{
  "refreshToken": "<current Refresh Token>"
}
```

응답 본문:

```json
{
  "accessToken": "<new StopBell Access Token>",
  "refreshToken": "<rotated StopBell Refresh Token>"
}
```

이 Endpoint의 `POST` 요청만 Access Token 없이 호출할 수 있다. Backend는 Refresh Token 원문을 SHA-256 Hash로 변환해 저장된 Session을 찾고, 유효한 Token이면 기존 row를 삭제한 뒤 새 30일 Refresh Token과 새 Access Token을 발급한다. 유효하지 않거나 만료된 Refresh Token은 `401 Unauthorized`를 반환하며 Backend는 Flutter Login 화면으로 redirect하지 않는다. 한 User의 다른 Refresh Token Session은 Rotation으로 삭제하지 않는다.

### Logout

```http
POST /auth/logout
Content-Type: application/json
```

요청 본문은 Refresh Token 재발급과 동일하다.

```json
{
  "refreshToken": "<current Refresh Token>"
}
```

응답은 항상 `204 No Content`이다. 이 Endpoint의 `POST` 요청만 Access Token 없이 호출할 수 있다. Backend는 Refresh Token 원문을 SHA-256 Hash로 변환한 뒤 해당 현재 Session만 삭제하며, User 또는 다른 Refresh Token Session을 조회·삭제하지 않는다. 존재하지 않거나 이미 삭제된 Token, 만료된 Token, `null` 또는 blank Token도 동일하게 `204 No Content`를 반환하므로 Logout은 idempotent하다. Logout된 Refresh Token은 재발급에 사용할 수 없고 `POST /auth/refresh`는 `401 Unauthorized`를 반환한다.

Access Token blacklist는 사용하지 않으므로 Logout 뒤에도 이미 발급된 Access Token은 만료 시점까지 유효할 수 있다. Backend는 Flutter Login 화면으로 redirect하지 않는다.

Alarm을 포함한 사용자 소유 Application API는 Client Request Body 또는 Query Parameter의 `userId`를 받지 않는다. Spring Security가 검증한 Access Token의 Principal에서 StopBell User를 식별한다.
