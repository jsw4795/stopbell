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

### 알림 생성

```http
POST /api/v1/alerts
Content-Type: application/json
```

후보 본문:

```json
{
  "routeId": "provider-route-id",
  "stopId": "provider-stop-id"
}
```

언젠가 유용할 수 있다는 이유만으로 필드를 추가하지 않는다.

### 알림 목록 조회

```http
GET /api/v1/alerts
```

### 알림 조회

```http
GET /api/v1/alerts/{alertId}
```

### 알림 활성화

후보:

```http
POST /api/v1/alerts/{alertId}/activate
```

구현 전에는 다른 REST 형태도 검토할 수 있다.

### 알림 비활성화

```http
POST /api/v1/alerts/{alertId}/deactivate
```

### 알림 삭제

```http
DELETE /api/v1/alerts/{alertId}
```

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
  "code": "ALERT_NOT_FOUND",
  "message": "Alert was not found."
}
```

관측성이 필요해질 때 선택적으로 trace/request ID를 추가할 수 있다.

## 6. 인증

StopBell Application API는 다음 형식의 StopBell 자체 JWT Access Token으로 인증한다.

```http
Authorization: Bearer <Access Token>
```

Google ID Token은 로그인 시 외부 Identity를 확인하기 위해 Backend에 전달할 뿐, Application API의 인증 헤더에 사용하지 않는다. Access Token 기본 수명은 1시간이며, Refresh Token은 Access Token 재발급에만 사용한다.

로그인, Refresh, Logout API의 정확한 endpoint 및 request/response DTO는 각각의 Authentication 구현 Task에서 이 문서에 정의한다.

Alarm을 포함한 사용자 소유 Application API는 Client Request Body 또는 Query Parameter의 `userId`를 받지 않는다. Spring Security가 검증한 Access Token의 Principal에서 StopBell User를 식별한다.
