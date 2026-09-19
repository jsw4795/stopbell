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

## 4. Application API

### 버스 노선 검색

```http
GET /api/v1/bus-routes?query={query}
```

응답 형태는 Transit API 구현 Task에서 확정한다.

### 버스 노선의 정류장 조회

```http
GET /api/v1/bus-routes/{routeId}/stops
```

Route identifier의 구체적인 표현은 Transit API 구현 Task에서 결정한다. Alarm 생성에 사용하는 Stop selection response는 최소 다음 형태다.

```json
{
  "id": 12345,
  "name": "사색의광장",
  "order": 1,
  "canNotifyOneStopBefore": false,
  "canNotifyOneStopAfter": true
}
```

`id`는 current metadata의 `BusRouteStopOccurrence.id`이며 Alarm 생성 Request의 `targetStopOccurrenceId`와 같은 selection reference다. `name`과 `order`는 Stop 선택 UI용 metadata다. `canNotifyOneStopBefore`와 `canNotifyOneStopAfter`는 현재 Route traversal에서 실제 predecessor/successor occurrence 존재 여부를 나타내며, `order`의 산술 증감으로 계산하지 않는다.

Stop selection response에는 `provider`, `externalRouteId`, `externalStopId`, `cityCode`, GPS를 노출하지 않는다. 이 값들은 Provider 또는 Backend metadata implementation detail이며, GPS는 현재 Flutter 기능에 필요하지 않다.

### 알림 생성

```http
POST /api/v1/alarms
Content-Type: application/json
```

Alarm 생성은 인증된 User의 Alarm만 생성하며 Request body에 `userId` 또는 Provider metadata를 받지 않는다.

Request body:

```json
{
  "targetStopOccurrenceId": 12345,
  "notifyOneStopBefore": true,
  "notifyOneStopAfter": false
}
```

`targetStopOccurrenceId`는 Route Stop 조회 응답의 `id`인 `BusRouteStopOccurrence.id`이며 필수다. Backend는 해당 current metadata occurrence와 Route/Stop metadata를 조회해 `BusAlarmTarget` snapshot을 생성한다. Client는 `provider`, `externalRouteId`, `externalStopId`, `targetStopOrder`, `routeNumber`, `stopName`, target GPS, `cityCode`, predecessor/successor external ID 또는 order를 직접 전달하지 않는다.

새 Alarm의 초기 `status`는 `INACTIVE`다. 생성 뒤 사용자가 활성화 endpoint를 호출하면 `ACTIVE`가 된다.

성공 응답은 `201 Created`와 다음 Alarm response다.

```json
{
  "id": 15,
  "transitType": "BUS",
  "status": "INACTIVE",
  "routeNumber": "7000",
  "stopName": "사색의광장",
  "notifyOneStopBefore": true,
  "notifyOneStopAfter": false
}
```

`targetStopOccurrenceId`가 존재하지 않으면 `404 Not Found`다. 첫 occurrence에 `notifyOneStopBefore: true` 또는 마지막 occurrence에 `notifyOneStopAfter: true`를 요청하면 `400 Bad Request`다. Backend는 해당 option을 `false`로 변경해 생성하지 않는다. first/last 판정은 `stopOrder`가 1 또는 최대값인지가 아니라 predecessor/successor occurrence의 실제 존재 여부를 사용한다.

### 알림 목록 조회

```http
GET /api/v1/alarms
```

현재 인증된 User가 소유한 Alarm 목록을 `createdAt DESC`(최근 생성순)로 정렬해 단순 JSON array로 반환한다. `INACTIVE`, `ACTIVE`, `FOLLOW_UP` 상태를 모두 포함하며, Alarm이 없으면 빈 array를 반환한다. 성공은 `200 OK`이고 V1에는 pagination을 사용하지 않는다. `createdAt`은 정렬에만 사용하며 response에는 포함하지 않는다.

```json
[
  {
    "id": 15,
    "transitType": "BUS",
    "status": "ACTIVE",
    "routeNumber": "7000",
    "stopName": "사색의광장",
    "notifyOneStopBefore": false,
    "notifyOneStopAfter": true
  }
]
```

### 알림 조회

```http
GET /api/v1/alarms/{alarmId}
```

현재 인증된 User가 소유한 Alarm을 Alarm response로 반환한다. 성공은 `200 OK`다. Alarm이 없거나 현재 User의 소유가 아니면 모두 `404 Not Found`로 처리한다.

### 알림 활성화

```http
POST /api/v1/alarms/{alarmId}/activate
```

현재 인증된 User가 소유한 Alarm을 활성화하고 변경된 Alarm response를 반환한다. 성공은 `200 OK`이며 status는 `ACTIVE`다. `FOLLOW_UP` Alarm 활성화는 persisted follow-up runtime을 지워 이전 ARRIVED short follow-up을 취소하고 새 monitoring cycle을 시작한다. Alarm이 없거나 현재 User의 소유가 아니면 `404 Not Found`다.

### 알림 비활성화

```http
POST /api/v1/alarms/{alarmId}/deactivate
```

현재 인증된 User가 소유한 Alarm을 비활성화하고 변경된 Alarm response를 반환한다. 성공은 `200 OK`이며 status는 `INACTIVE`다. Alarm이 없거나 현재 User의 소유가 아니면 `404 Not Found`다.

### 알림 삭제

```http
DELETE /api/v1/alarms/{alarmId}
```

현재 인증된 User가 소유한 Alarm을 삭제한다. 성공은 response body 없는 `204 No Content`다. Alarm이 없거나 현재 User의 소유가 아니면 `404 Not Found`다. 삭제는 active monitoring뿐 아니라 Alarm row에 저장된 진행 중 follow-up runtime, 공유 PK BusAlarmTarget 및 종속 NotificationHistory를 함께 제거한다. 구체적인 scheduler coordination은 후속 Task에서 결정한다.

### Alarm response

Alarm 생성, 목록, 상세, 활성화, 비활성화 response는 다음 필드를 사용한다.

```json
{
  "id": 15,
  "transitType": "BUS",
  "status": "FOLLOW_UP",
  "routeNumber": "7000",
  "stopName": "사색의광장",
  "notifyOneStopBefore": true,
  "notifyOneStopAfter": false
}
```

`transitType`은 현재 Domain enum의 `BUS`, `SUBWAY`를, `status`는 `INACTIVE`, `ACTIVE`, `FOLLOW_UP`를 사용한다. V1 Alarm response에는 Subway 전용 field를 미리 추가하지 않는다.

Alarm response에는 `provider`, `externalRouteId`, `externalStopId`, `targetStopOrder`, target GPS, `cityCode`, predecessor/successor external ID 또는 order, `followUpVehicleTrackingId`, `followUpStartedAt`, `followUpExpiresAt`을 포함하지 않는다. 이 값들은 Backend의 provider metadata, target snapshot, evaluation 또는 follow-up runtime implementation detail이다.

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

오류 response는 다음 구조를 사용한다.

```json
{
  "code": "ALARM_NOT_FOUND",
  "message": "Alarm was not found."
}
```

관측성이 필요해질 때 선택적으로 trace/request ID를 추가할 수 있다. Alarm API의 구체적인 validation, error code, exception handling 구현은 TASK-409에서 담당한다. 이 계약에서 `404 Not Found`는 target occurrence가 존재하지 않거나, Alarm이 존재하지 않거나 현재 User가 소유하지 않음을 의미한다. `400 Bad Request`는 predecessor/successor occurrence 없이 before/after option을 요청한 경우를 포함한 유효하지 않은 Alarm 생성 요청을 의미한다.

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
