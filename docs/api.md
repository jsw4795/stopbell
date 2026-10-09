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

JWT Access Token이 필요한 Application API다. `query`는 trim한 뒤 노선번호 원문 또는 시작의 연속된 영문자 prefix(`[A-Za-z]+`)를 생략한 값에 대해 대소문자를 무시하는 prefix 검색을 수행한다. 예를 들어 `2352`는 `M2352`에 일치하지만 `352`는 일치하지 않으며, 일반 substring 검색은 수행하지 않는다. 빈 값, whitespace-only 값, 누락된 `query`는 `400 Bad Request`와 다음 오류를 반환한다.

```json
{
  "code": "INVALID_REQUEST",
  "message": "Request is invalid."
}
```

검색 결과는 최대 50건이며 `routeNumber ASC`, 같은 노선번호에서는 `id ASC`로 정렬한다. 결과가 없으면 오류 없이 `200 OK`와 빈 배열을 반환한다.

```json
[
  {
    "id": 754,
    "routeNumber": "7000",
    "regionName": "수원시"
  },
  {
    "id": 1390,
    "routeNumber": "7000",
    "regionName": "김포시"
  }
]
```

`id`는 `BusRoute.id`인 내부 selection reference다. `routeNumber`는 unique하지 않으므로 같은 번호의 복수 후보가 존재할 수 있으며, `regionName`은 이를 표시용으로 구분한다. Client에는 `provider`, `externalRouteId`, `cityCode`를 노출하지 않는다. Client는 선택한 response의 `id`를 다음 Stop 조회 endpoint의 `{routeId}`로 사용한다.

### 버스 노선의 정류장 조회

```http
GET /api/v1/bus-routes/{routeId}/stops
```

JWT Access Token이 필요한 Application API다. `{routeId}`는 Route 검색 response의 `BusRoute.id`인 양의 내부 selection reference다. 응답은 Route traversal 순서인 `stopOrder ASC`로 반환하며 pagination은 사용하지 않는다.

```json
[
  {
    "id": 12345,
    "name": "사색의광장",
    "order": 1,
    "destinationName": "사당역4번출구",
    "previousStopName": null,
    "nextStopName": "다음 정류장",
    "canNotifyOneStopBefore": false,
    "canNotifyOneStopAfter": true
  }
]
```

`id`는 current metadata의 `BusRouteStopOccurrence.id`이며 Alarm 생성 Request의 `targetStopOccurrenceId`와 같은 selection reference다. `name`은 연결된 `BusStop.stopName`이고 `order`는 `BusRouteStopOccurrence.stopOrder`다. `canNotifyOneStopBefore`와 `canNotifyOneStopAfter`는 정렬된 현재 Route traversal에서 실제 predecessor/successor occurrence 존재 여부를 나타내며, `order`의 산술 증감으로 계산하지 않는다. 같은 `BusStop`이 Route에 여러 번 나타나도 occurrence를 dedup하지 않고 각각 반환한다.

`destinationName`은 검증된 경기 GBIS traversal에서 계산한 사용자용 목적지이며 서울 또는 검증 실패 경기 Route에서는 `null`이다. `previousStopName`/`nextStopName`은 저장된 traversal의 실제 앞뒤 occurrence 이름이고 양 끝에서는 각각 `null`이다. Client는 같은 이름 occurrence를 `destinationName`의 `○○ 방면`, 인접 Stop 문맥, 마지막으로 `order`로 구분한다. `order`는 표시용 최종 구분값이며 Alarm 선택 identity는 `id`다.

Route가 current metadata에 없으면 `404 Not Found`와 `BUS_ROUTE_NOT_FOUND` (`Bus route was not found.`)를 반환한다. Long으로 변환할 수 없거나 양수가 아닌 `{routeId}`는 `400 Bad Request`와 `INVALID_REQUEST`를 반환한다. Route에는 하나 이상의 occurrence가 있어야 한다는 metadata invariant를 유지하며, 이 상태가 깨진 Route를 정상적인 빈 선택 결과나 Route 없음으로 처리하지 않는다.

Stop selection response에는 `provider`, `externalRouteId`, `externalStopId`, `cityCode`, GPS, `BusStop.id`, predecessor/successor external ID를 노출하지 않는다. 이 값들은 Provider 또는 Backend metadata implementation detail이며, GPS는 현재 Flutter 기능에 필요하지 않다.

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

`targetStopOccurrenceId`는 Route Stop 조회 응답의 `id`인 `BusRouteStopOccurrence.id`이며 필수인 양의 정수다. `notifyOneStopBefore`와 `notifyOneStopAfter`는 생략할 수 있고, 생략하면 각각 `false`다. Backend는 해당 current metadata occurrence와 Route/Stop metadata를 조회해 `BusAlarmTarget` snapshot을 생성한다. Client는 `provider`, `externalRouteId`, `externalStopId`, `targetStopOrder`, `routeNumber`, `stopName`, target GPS, `cityCode`, predecessor/successor external ID 또는 order를 직접 전달하지 않는다.

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

Flutter가 stale `targetStopOccurrenceId`로 `404 Not Found`를 받으면 old ID를 현재 Stop에 추측 매칭하거나 POST를 자동 재시도하지 않는다. 사용자가 Stop 목록에서 다시 선택하는 흐름으로 복구한다. Alarm create는 idempotency contract가 없으므로 timeout만으로 자동 POST 재시도하지 않는다.

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

현재 인증된 User가 소유한 Alarm을 삭제한다. 성공은 response body 없는 `204 No Content`다. Alarm이 없거나 현재 User의 소유가 아니면 `404 Not Found`다. 삭제는 active monitoring뿐 아니라 Alarm row에 저장된 진행 중 follow-up runtime과 공유 PK BusAlarmTarget을 함께 제거한다. 현재 초기 physical model에서는 종속 NotificationHistory도 함께 제거되며, Phase 7 NotificationEvent/Delivery의 정확한 lifecycle은 TASK-707에서 결정한다. 구체적인 scheduler coordination은 후속 Task에서 결정한다.

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

### 기기 등록/해제 계약 (TASK-702)

아래 계약은 확정됐지만 Endpoint/DTO/ErrorCode/Service/Repository와 동시성 처리는 **TASK-703에서 구현한다**. TASK-702는 Domain/JPA/Schema만 구현한다.

등록·조회·해제 Endpoint 모두 StopBell JWT Access Token이 필수다. User는 Spring Security Principal에서 가져오며 body의 `userId`를 받지 않는다. 별도로 `X-Installation-Credential` header가 필수다. 이 값은 Flutter가 최초 요청 전에 CSPRNG로 생성·안전하게 저장한 32-byte 비밀값의 canonical unpadded Base64URL(43자)이다. Backend는 정확히 32 bytes로 decode하고 SHA-256 lowercase hex hash를 저장/constant-time 비교한다. UUID·FID·revision은 자격증명을 대체하지 않는다. 자격증명, hash, FID는 로그·오류·응답에 노출하지 않는다. installationId는 로그·오류에서 제외하고 응답에서는 해당 요청의 정규화한 값만 반환한다. 상태 조회의 access log도 원문 경로 대신 route template을 사용해 installationId를 노출하지 않는다. 운영 API는 HTTPS를 사용한다.

#### 등록

```http
POST /api/v1/devices
Authorization: Bearer <Access Token>
X-Installation-Credential: <installation credential>
Content-Type: application/json
```

```json
{
  "installationId": "a1234567-1234-4123-8123-123456789abc",
  "platform": "IOS",
  "pushRegistrationId": "<current registered FID>",
  "registrationRevision": 1,
  "expectedOwnershipGeneration": 0,
  "transferOwnership": false
}
```

모든 필드는 필수다. `installationId`는 정확한 hyphenated UUID v4/variant 형식(36자)만 허용하고 소문자로 정규화하며 trim하지 않는다. `platform`은 정확한 `IOS` / `ANDROID`이고 기존 row의 platform을 변경하지 않는다. Android enum은 허용하지만 이번 Task에서 Android Firebase 연동은 구현하지 않는다. `pushRegistrationId`는 FCM 등록 callback에서 얻은 현재 FID로 1~255자이며 blank/공백 포함 값은 거부한다. 대소문자와 내용을 그대로 보존하며 22자나 특정 FID prefix에 고정하지 않는다. `registrationRevision`과 `expectedOwnershipGeneration`은 0~`Long.MAX_VALUE`의 JSON 정수이며 boolean/string/소수 coercion을 허용하지 않는다. `transferOwnership`은 JSON boolean이다.

신규 설치는 `expectedOwnershipGeneration=0`, `transferOwnership=false`로 최초 등록한다. row가 없을 때 자격증명을 처음 바인딩하고 generation=0, enabled=true로 생성한다. 기존 row가 있다면 같은 자격증명만 허용하며 다른 자격증명의 UUID 충돌을 신규 설치나 takeover로 처리하지 않는다.

기존 owner의 재등록/FID rotation/재활성화는 `transferOwnership=false`와 current generation을 제출한다. 다른 User로의 이전은 **새 User의 JWT + 동일 설치본 자격증명 + transferOwnership=true + current generation**이 모두 필요하다. 이전 owner의 disable 성공 여부와 무관하게 안전한 이전을 허용하며 DB의 동일 row에 새 owner/FID/revision/enabled를 적용하고 서버가 generation을 정확히 1 증가시킨다. owner가 같다면 true는 이전 요청으로 적용하지 않는다. revision/generation overflow는 거부한다.

처리 순서와 멱등 규칙:

1. JWT·요청 validation 후 installation row를 찾고 자격증명 및 불변 platform을 검증한다. 기존 row는 write lock 안에서 다음 검증을 수행한다.
2. **같은 revision + 동일 결과 상태**이면 멱등 성공한다. 등록의 동일 상태는 Principal=current owner, platform/FID 일치, enabled=true다. 일반 요청은 expected generation=current가 필요하다. 이전 요청의 응답 유실 재전송만 true와 expected generation=current-1을 허용한다. 이 예외도 동일 owner/revision/target/enabled를 모두 만족해야 하며 아무 mutation이나 timestamp 갱신을 하지 않는다.
3. 그 외에는 expected generation=current가 필수다. 다른 owner의 요청은 명시적 이전 true인 등록만 허용한다. UUID/FID나 큰 revision만으로 이 조건을 우회하지 않는다.
4. 권한 있는 요청에서 request revision > stored면 적용, 같고 상태가 다르면 revision conflict, 작으면 stale로 거부한다. 모든 검증과 UNIQUE 충돌 확인이 끝난 뒤 전체 상태를 한 transaction에서 commit한다.

다른 installation이 같은 FID를 점유하면 충돌로 전체 rollback한다. 기존 Device의 owner/FID/상태를 바꾸거나 자동 disable하지 않는다. 등록 결과는 enabled=true다. 더 높은 revision으로 동일 FID를 재등록하면 `lastRegisteredAt`/`updatedAt`을 서버 UTC 시각으로 갱신한다. 멱등 요청은 갱신하지 않는다.

최초 생성은 `201 Created`, 기존 row 적용·멱등 재요청은 `200 OK`다. 응답 형식은 다음과 같으며 owner나 credential/FID는 반환하지 않는다. `installationId`는 정규화한 요청 식별자를 반환한다.

```json
{
  "id": 1,
  "installationId": "a1234567-1234-4123-8123-123456789abc",
  "enabled": true,
  "registrationRevision": 1,
  "ownershipGeneration": 0
}
```

#### 설치본 상태 조회

```http
GET /api/v1/devices/{installationId}
Authorization: Bearer <Access Token>
X-Installation-Credential: <installation credential>
```

UUID/header validation은 등록과 같다. JWT와 **해당 설치본 자격증명**을 모두 검증한다. 현재 owner가 다른 User여도 설치본 보유자는 계정 이전에 필요한 서버 상태를 조회할 수 있다. `200 OK`로 등록 응답의 id/정규화 installationId/enabled/revision/generation과 `ownedByCurrentUser` boolean을 반환한다. owner ID/FID/credential은 반환하지 않는다. row가 없으면 `404 DEVICE_NOT_FOUND`, 자격증명 불일치는 `403 DEVICE_CREDENTIAL_INVALID`다. 상태나 시각을 변경하지 않는다.

이 조회는 응답 유실 후 계정 변경, 앱 재시작의 pending 요청 결과 불명확, counter/generation metadata 복구에 필요한 최소 동기화다. 현재 Auth Session의 **새 사용자 의도**에 대해서만 조회 상태를 반영해 local revision을 max(local, server)로 맞춘 뒤 증가시키고, current generation과 필요한 이전 intent를 새 요청에 snapshot한다. 조회와 mutation 사이 경쟁은 mutation transaction의 재검증으로 거부한다. 과거 pending 요청의 JWT/generation/revision을 교체하거나 자동 takeover하기 위한 조회로 사용하지 않는다. credential을 잃은 경우 조회·복구를 허용하지 않는다. 실제 구현은 TASK-703 책임이다.

#### 비활성화

```http
POST /api/v1/devices/disable
Authorization: Bearer <Access Token>
X-Installation-Credential: <installation credential>
Content-Type: application/json
```

```json
{
  "installationId": "a1234567-1234-4123-8123-123456789abc",
  "registrationRevision": 2,
  "expectedOwnershipGeneration": 0
}
```

모든 필드와 header는 필수이며 등록과 같은 validation을 사용한다. 기존 row의 자격증명과 **Principal=current owner** 및 current generation이 필요하다. 이전 권한은 없으며 row가 없거나 다른 owner이면 `DEVICE_NOT_FOUND`다. 같은 revision에 이미 enabled=false/FID=null이면 멱등 성공, 그 외 revision 비교는 등록과 같다. 적용 시 enabled=false, FID=null, revision 갱신이며 row/owner/generation/lastRegisteredAt을 보존한다. 다른 Device나 Alarm을 삭제·비활성화하지 않는다. `200 OK`로 등록과 같은 응답 형식(enabled=false)을 반환한다.

#### 오류와 Client 순서 계약

API use-case 오류는 기존 `code`, `message` 두 필드 형식을 따른다. 다음 코드는 TASK-703에서 추가한다. JWT 누락/실패는 기존 Spring Security의 `401 Unauthorized` 처리를 유지한다.

| Code | HTTP | message / 의미 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | `Request is invalid.` / 필수값·UUID·범위·header 형식 오류, counter overflow |
| `DEVICE_CREDENTIAL_INVALID` | 403 | `Installation credential is invalid.` / 기존 설치본 자격증명 불일치, 상세 상태 미노출 |
| `DEVICE_NOT_FOUND` | 404 | `Device was not found.` / 조회·disable 대상 없음 또는 disable의 다른 owner |
| `DEVICE_OWNERSHIP_CONFLICT` | 409 | `Device ownership has changed.` / generation 불일치 또는 허용되지 않은 이전 |
| `DEVICE_PLATFORM_CONFLICT` | 409 | `Device platform cannot change.` / 불변 platform 변경 |
| `DEVICE_REGISTRATION_STALE` | 409 | `Device registration revision is stale.` / 낮은 revision |
| `DEVICE_REVISION_CONFLICT` | 409 | `Device registration revision conflicts.` / 동일 revision의 다른 결과 상태 |
| `DEVICE_PUSH_TARGET_CONFLICT` | 409 | `Push target is already registered.` / 다른 installation의 현재 FID 점유 |

오류에 current owner, credential, FID 또는 다른 설치본 상태를 넣지 않는다. 권한/generation 오류는 revision보다 우선하며 표의 세부 상태는 해당 권한을 통과한 요청에만 적용된다.

Flutter는 설치본 전체 revision을 logout/계정 변경에도 유지한다. 요청을 보내기 **전에** 증가한 counter와 pending 요청 payload, 당시 Auth Session/User 및 expected generation을 내구적으로 저장하고 설치본 mutation을 직렬화한다. response의 generation은 해당 요청/session이 현재인 경우에만 반영한다. timeout은 저장한 동일 payload로 재전송하며 새 revision/현재 JWT/generation을 붙여 과거 작업을 새 요청으로 바꾸지 않는다. Auth interceptor도 계정 변경 뒤 이전 요청을 새 User JWT로 재시도하지 않는다. ownership conflict에는 generation을 추측 증가하거나 자동 takeover 재시도를 하지 않는다. 필요한 경우 현재 session의 명시적 사용자 동작에서 상태를 조회해 새 의도로 처리한다.

앱 재시작은 저장한 counter/generation/pending 요청을 복원하며 counter는 응답 revision보다 낮아지지 않는다. 정상 응답 유실은 동일 요청 재전송으로 복구한다. counter/generation metadata가 유실되거나 backup과 불일치하면 위 인증된 상태 조회로 동기화한 후 새 의도를 처리한다. credential 자체의 유실에는 등록을 중단하며 UUID만으로 reset/복구하지 않는다. 재설치 때는 새로운 설치본 identity/credential/counter를 생성한다. 범용 동시성 framework를 선행 추가하지 않는다.

| 검토 상황 | 계약 결과 (실행 검증은 TASK-703) |
| --- | --- |
| AAA r1 → BBB r2 | 동일 owner/generation에서 BBB 적용 |
| BBB r2 뒤 AAA r1 지연 | stale 거부, BBB 유지 |
| 같은 r2/BBB 재전송 | 동일 상태 멱등 성공 |
| 같은 r2/다른 FID | revision conflict |
| disable r3 뒤 등록 r2 지연 | stale 거부, disabled 유지 |
| logout 뒤 같은 User 재로그인 | identity/credential/generation 유지, 높은 r로 재활성화 |
| A → B | credential + 명시적 이전 + expected generation 일치, generation g→g+1 |
| B 등록 뒤 A 지연 register/disable | 이전 intent/generation 또는 owner 불일치로 거부, 큰 revision도 변경 불가 |
| A → B → A 뒤 첫 A의 지연 요청 | User가 다시 같아도 old generation 거부 |
| 이전 성공 응답 유실 후 동일 B 요청 | 같은 owner/r/FID/enabled와 이전 g의 재전송은 mutation 없이 성공 |
| 앱 재시작 | durable counter/generation 복원, 필요 시 credential로 상태 조회, reset 금지 |
| 신규 설치 UUID 충돌 | 다른 credential이면 403, 기존 row 유지 |

#### Logout과 offline 한계

Flutter는 현재 session으로 bounded Device disable 시도 → 기존 `POST /auth/logout` → 로컬 session 정리 순서로 처리한다. disable의 network/timeout 실패로 Auth logout을 영구 차단하지 않는다. installation identity/credential/counter는 유지하며 Firebase FID deletion과 native unregister를 일반 logout 필수 단계로 사용하지 않는다. Backend recipient disable과 Firebase registration lifecycle은 분리한다.

Offline disable 실패 시 Backend에 enabled Device가 남아 이전 User의 notification이 계속 도착할 수 있다. Auth logout은 Access Token을 즉시 revoke하지 않으며 Push 권한 해제의 대체 수단도 아니다. 재연결 때 원래 User/session과 generation이 유효할 경우에만 pending disable을 처리한다. 다른 User 로그인은 안전한 ownership 이전을 완료해 이전 owner의 향후 recipient eligibility를 제거한다. 이전 요청을 새 User의 자격으로 재작성하지 않는다. Flutter는 logout 때 로컬 민감 상태/navigation을 지우고 Push는 최소 hint만 사용하며 tap 시 현재 인증/ownership을 다시 확인한다. 이미 Provider에 접수되거나 OS에 표시된 알림의 회수와 offline 즉시 해제는 보장하지 않는다.

### Push payload와 Notification tap

Push payload는 navigation hint 수준의 최소 정보만 포함한다.

```json
{
  "type": "ALARM_EVENT",
  "alarmId": "123",
  "eventType": "ARRIVED",
  "notificationEventId": "456"
}
```

`userId`, Auth Token, push registration identifier, Provider Route/Stop external ID, GPS, Alarm 상세 전체는 포함하지 않는다. Payload는 권한 근거가 아니며 Flutter는 Auth Session initialization 뒤 `alarmId` navigation entry를 사용해 Backend Alarm detail API에서 ownership과 current state를 다시 확인한다. 삭제되었거나 접근할 수 없는 Alarm은 stale notification으로 정상 처리한다. 최종 payload field/type 이름은 TASK-704/706에서 확정한다.

## 5. 오류 형식

오류 response는 다음 구조를 사용한다.

```json
{
  "code": "ALARM_NOT_FOUND",
  "message": "Alarm was not found."
}
```

V1 Error Response는 `code`, `message` 두 필드만 사용한다. `timestamp`, `path`, `status`, trace/request ID, field error 또는 stack trace는 포함하지 않는다.

Alarm API의 error code와 HTTP status는 다음과 같다.

| Code | HTTP status | 의미 |
| --- | --- | --- |
| `ALARM_NOT_FOUND` | `404 Not Found` | Alarm이 없거나 현재 User 소유가 아님 |
| `BUS_ROUTE_NOT_FOUND` | `404 Not Found` | 요청한 BusRoute가 current metadata에 없음 |
| `TARGET_STOP_OCCURRENCE_NOT_FOUND` | `404 Not Found` | Alarm 생성 대상 occurrence가 현재 metadata에 없음 |
| `INVALID_ALARM_REQUEST` | `400 Bad Request` | predecessor/successor 없이 before/after option을 요청하는 등 Alarm Domain 규칙 위반 |
| `INVALID_REQUEST` | `400 Bad Request` | 필수 ID 누락, 0 이하 ID, body 누락 또는 읽을 수 없는 JSON 등 기본 요청 형식 오류 |

`ALARM_NOT_FOUND`의 message는 `Alarm was not found.`이며, `BUS_ROUTE_NOT_FOUND`의 message는 `Bus route was not found.`, `TARGET_STOP_OCCURRENCE_NOT_FOUND`의 message는 `Target stop occurrence was not found.`이다. `INVALID_ALARM_REQUEST`는 `Target stop has no predecessor stop.` 또는 `Target stop has no successor stop.`처럼 구체적 사유를 message로 전달한다. `INVALID_REQUEST`의 message는 `Request is invalid.`이다.

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

Flutter는 refresh `401`을 현재 Token Pair가 더 이상 유효하지 않다는 신호로 처리해 Token을 제거하고 unauthenticated로 전환한다. network/offline/5xx는 이 `401` 의미와 구분하며 저장된 장기 session을 즉시 삭제하지 않는다. `/auth/refresh` 자체는 refresh interceptor나 자동 재시도 대상이 아니다.

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

Flutter logout은 같은 Auth Session 안에서 refresh와 직렬화하며 local 인증 상태가 late refresh/API response로 되살아나지 않게 보호한다. 이는 서버에 이미 도착한 요청을 취소하거나 기존 Access Token을 즉시 무효화한다는 계약이 아니다. `/auth/google`과 `/auth/logout`도 refresh interceptor나 자동 재시도 대상이 아니다.

Phase 7 Flutter logout은 이 기존 lifecycle/hook을 사용해 현재 Device unsubscribe/disable을 시도한 뒤 Auth logout과 local session 종료를 수행한다. Device 처리는 별도 authenticated API를 사용하며 이 request body에 Device field를 추가하지 않는다. Offline logout에서는 Backend Device disable을 즉시 보장할 수 없으므로 local session 종료를 영구 차단하지 않는다. Device 순서와 offline 한계는 [TASK-702 계약](#기기-등록해제-계약-task-702)을 따른다. Flutter 실행은 TASK-704에서 구현·검증한다.

Alarm을 포함한 사용자 소유 Application API는 Client Request Body 또는 Query Parameter의 `userId`를 받지 않는다. Spring Security가 검증한 Access Token의 Principal에서 StopBell User를 식별한다.
