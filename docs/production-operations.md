# Production 배포 및 복구 준비 (TASK-813)

이 문서는 V1 단일 Backend와 영속 MySQL의 운영 절차를 준비하기 위한 체크리스트다. 실제 플랫폼, 운영 적용, 백업 또는 복구 성공을 의미하지 않는다. [운영 계약](architecture.md#11-배포-방향), [V4 사전 점검](local-development.md#v4-미적용-db의-read-only-preflight-task-816--aud-11), [UTC 기존 데이터 영향](task-list.md#task-814-utc-구현-및-검증-결과-2026-10-30)을 함께 확인한다.

## 배포 전

1. 단일 인스턴스만 실행하고 이전 writer를 중지할 수 있는 배포 구간을 확보한다. V22 적용 중과 이후 구 Backend를 새 Backend와 혼합 실행하지 않는다. 적용된 migration 파일을 수정하거나 임의로 DB를 downgrade하지 않는다.
2. 영속 MySQL 8.4, HTTPS 공개 endpoint, 접근 제어, 재시작 정책과 public traffic 차단 수단을 준비한다. 개발용 Compose는 운영 구성이 아니다. 서비스 중지·복구 담당자, 복구 목표, DB 계정 권한과 백업 암호화·보존·접근 정책을 정한다.
3. 기존 schema와 Flyway history 및 pending migration, V4 미적용 DB의 필수 read-only preflight, UTC 이전 데이터 영향을 확인한다. 영향이나 호환성이 불명확하면 중단한다. 사전에 복원 가능한 백업 또는 snapshot을 만들고 별도 격리 환경에서 복구를 검증한다. 자동 백업과 가능하면 PITR을 준비한다.
4. `backend/Dockerfile`의 image를 검증해 사용한다. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`, `GOOGLE_SERVER_CLIENT_ID`, `PUBLIC_DATA_SERVICE_KEY`를 실행 환경에서 주입한다. JWT 키는 충분히 무작위인 UTF-8 32 bytes 이상이어야 한다. Secret은 image, Git, 명령행 인자와 로그에 넣지 않는다. FCM 사용 시 `FCM_ENABLED`, `FCM_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS`와 UID 10001이 읽을 수 있는 외부 credential을 별도로 준비한다. 실제 주입과 권한·교체·회수를 확인한다.
5. 기본 설정의 monitoring과 dispatch는 비활성이다. 최초 배포와 복구에서는 outbound polling 및 dispatch를 차단하고 시작한다. 활성화된 dispatch는 실제 FCM 전송을 발생시킬 수 있다. Provider 설정과 평문 서울 API의 위험은 기존 [Transit 계약](architecture.md#transit)을 확인한다.

## 배포와 점검

1. traffic과 기존 worker를 중지하고 진행 중 작업이 끝나도록 SIGTERM을 전달한다. Docker image의 shutdown phase 예산은 기본 60초이며 플랫폼의 종료 유예는 이를 수용하고 실제 전체 종료 시간을 검증해야 한다.
2. 백업 시점과 대상 DB를 재확인하고 새 Backend 하나만 시작한다. Startup Flyway가 migration을 적용한다. 오류가 나면 새 인스턴스를 격리하고 로그와 history를 조사한다. MySQL DDL은 부분 적용될 수 있으므로 무조건 재시도, repair 또는 역 migration을 하지 않는다.
3. `/actuator/health/liveness`와 `/actuator/health/readiness`를 확인한다. startup 실패 시 endpoint가 없을 수 있다. readiness는 DB와 최초 TAGO·서울 complete metadata sync 및 활성 worker를 검사한다. 최초 bootstrap 전에 public traffic을 열지 않는다. endpoint 자체가 API traffic을 차단하지 않으므로 배포 측에서 차단한다.
4. 승인된 metadata bootstrap 및 검증 후 준비 상태를 확인한다. 운영 측정과 실제 기기 검증을 검토하고 monitoring과 dispatch를 각각 활성화한다. Provider 오류, scheduler 완료, pending backlog와 만료, delivery 결과를 관측한다. FCM ACCEPTED는 단말 표시 보장이 아니다.

## 장애와 복구

- 장애 발생 시 public traffic, polling 및 dispatch를 차단하고 단일 writer를 중지한다. 상태와 로그를 보존하고 실제 Secret을 기록하지 않는다.
- 새 schema에서 구 application의 호환성과 UTC writer 영향을 확인한 경우에만 application rollback을 고려한다. V22 이후 이전 Backend 혼합 실행은 금지한다. DB 복구가 필요한 경우 승인된 복원 지점과 데이터 손실 범위를 확인한다.
- 백업은 일관된 MySQL 백업 또는 플랫폼 snapshot으로 만들고 암호화해 접근을 제한한다. 복원은 먼저 별도 격리 DB에서 수행하며 운영 DB를 덮어쓰기 전에 대상과 복원 지점을 독립적으로 확인한다. 복구 drill에서 schema, Flyway validate/history와 User·Alarm·Device·Notification Event/Delivery 보존을 확인한다. 실제 백업 방식·명령은 선택한 플랫폼과 권한에 맞춰 검증해야 한다.
- 복원 후 outbound를 계속 차단한 채 Flyway/schema → RefreshToken 복구 정책 → Device 소유권·재등록 → metadata 동기화·검증 → Alarm recovery baseline → 오래된 pending Notification expiry/cutoff 순서로 확인한다. 오래된 백업의 RefreshToken revoke/re-login 및 Device 처리 정책은 아직 결정되지 않았으므로 사용자 판단 없이 공개 서비스나 worker를 재개하지 않는다. 이후 worker/scheduler, readiness, public traffic을 순서대로 재개하고 관측한다.

실제 운영 환경의 복원 drill, secret 주입, 종료 유예와 경보·traffic 차단 검증은 수행되지 않았다. 격리 DB 검증은 운영 백업의 복구 가능성을 보증하지 않는다.

V32 적용 시에도 이전 Backend의 인증 요청과 삭제 작업을 종료한 뒤 새 버전을 시작한다. 이전 프로세스는 Challenge 세대 검사를 수행하지 않으므로 새 버전과 혼합 실행하면 삭제 경계가 보장되지 않는다.

## Apple 알림 backlog 조사 (TASK-815)

수신과 Worker는 기본 OFF다. 활성화 전 격리 환경에서 수신·복구 및 아래 조회를 검증하고, DB에는 최소 권한으로 접근한다. 다음 읽기 전용 집계는 UTC 기준이며 식별자와 자격 증명을 반환하지 않는다. 반복 실패의 기준은 운영자가 정한 횟수로 적용한다.

```sql
SELECT state, COUNT(*) AS event_count
FROM apple_notification_events
GROUP BY state;

SELECT state, COUNT(*) AS event_count,
       MAX(TIMESTAMPDIFF(SECOND, received_at, UTC_TIMESTAMP(6))) AS oldest_age_seconds,
       SUM(attempt_count > 1) AS repeated_attempts
FROM apple_notification_events
WHERE state IN ('PENDING', 'REVIEW_REQUIRED')
GROUP BY state;

SELECT last_error, COUNT(*) AS event_count
FROM apple_notification_events
WHERE state = 'PENDING' AND last_error IS NOT NULL
GROUP BY last_error;

SELECT COUNT(*) AS account_count
FROM account_deletions WHERE state = 'APPLE_NOTIFIED';
```

`attempt_count > 1`은 반복 시도 수이며 반드시 반복 실패를 뜻하지 않는다. `last_error`는 마지막 실패의 제한된 분류이고 전체 실패 이력이 아니다. 빈 상태는 결과 행이 없다. backlog 증가나 오래된 PENDING은 Worker 활성화 상태, 실행·DB 오류와 재시도 기한을 조사하고 원인을 복구한다. Worker는 1분 고정 지연으로 최대 10건을 순차 처리하므로 1분 이내 차단을 보장하지 않으며, 장애가 계속되면 차단 지연의 상한이 없다. PENDING 저장만으로 기존 Access Token이나 Alarm이 차단되지는 않는다. Worker가 `APPLE_NOTIFIED`와 source 중단을 commit하면 기존 보호 API와 후속 source 처리가 차단된다. 이미 외부 FCM에 전달 중이거나 접수된 요청은 회수할 수 없다.

REVIEW_REQUIRED는 자동 처리하지 않는다. 권한이 제한된 DB 세션에서 `HEX(jti_hash)`, 이벤트 유형·시각·수신 시각과 상태로 대상을 식별하고, 원문 JWS·Token·Apple subject를 일반 로그나 티켓에 기록하지 않는다. 같은 제한된 세션에서 보존된 subject로 APPLE User만 정확히 연결하고 `users.apple_generation_issued_at`, `apple_credentials.identity_issued_at`, 이벤트 시각을 비교한다. User 부재가 현재 계정 삭제를 뜻할 수 있으나 삭제 원인이나 해당 이벤트 처리 성공의 증거는 아니다. 새 계정 세대가 이벤트 이후인지, 기존 생성 근거가 NULL이거나 동일 초인지 확인한다. 인증 근거가 없거나 순서·계정 세대를 증명할 수 없으면 검토 상태를 유지하고 필요한 추가 검증이나 사용자 조치를 요청한다. 검증되지 않은 이벤트를 DELETED 또는 SKIPPED로 직접 변경하거나 강제 삭제하지 않는다. 실제 운영 환경에서 이 조사·복구 절차와 Apple 알림 수신은 아직 검증하지 않았다.

## Apple 알림 실기기 검증 준비 (TASK-815)

이 절차는 사용자가 직접 수행하는 향후 검증이다. Apple 계정을 영구 삭제하지 않는다. `account-deleted`는 합성 이벤트로 검증하고 실기기에서는 `consent-revoked`부터 확인한다. Apple Developer 설정이나 운영 데이터 변경을 이 문서의 작성·검토만으로 승인한 것으로 간주하지 않는다.

1. 테스트 대상 Apple 계정과 StopBell 환경을 분리하고 삭제 대상과 복구 방법을 확인한다. 테스트용 계정에 Apple 로그인으로 가입해 User ID를 안전하게 기록하고 Alarm을 만든다. 실제 사용자 데이터나 운영 DB를 테스트에 사용하지 않는다.
2. 지속 실행과 재시작 복구가 가능한 Backend와 고정 도메인을 준비한다. 유효한 인증서와 TLS 1.2 이상을 사용하고 외부에서 로그인 페이지 또는 접근 인증 없이 `POST https://<고정 도메인>/auth/apple/notifications`에 도달하는지 검증한다. JWS 검증은 Backend에서 수행한다. 기존 로컬 개발의 임시 `trycloudflare.com` URL은 등록하지 않는다. Named Cloudflare Tunnel은 선택 가능하지만 고정 도메인, 터널·Backend의 지속 가용성과 장애 복구를 별도로 검증해야 한다. 실제 공개 URL은 아직 확정되지 않았다.
3. Backend에 Spring 환경변수 `APPLE_NOTIFICATIONS_ENABLED=true`, `APPLE_NOTIFICATIONS_WORKER_ENABLED=true`, `APPLE_NOTIFICATIONS_CLIENT_ID=<Apple App ID>`를 주입한다. 두 기능은 기본 OFF이며 Worker 활성화는 검증된 알림 대상의 실제 데이터를 삭제할 수 있다. 대상 DB와 환경을 확인한 뒤 순차적으로 활성화하고, 앞 절의 backlog 조회 및 경보를 준비한다.
4. Apple Developer의 Certificates, Identifiers & Profiles → Identifiers에서 해당 Primary App ID와 그룹을 확인한다. Sign in with Apple의 Configure에서 Server-to-Server Notification Endpoint URL에 위의 고정 절대 HTTPS URL을 입력하고 Done 및 Save한다. 그룹당 URL은 하나이므로 기존 App ID 설정 변경이 다른 앱이나 서비스에 영향을 줄 수 있다면 변경 전에 사용자 확인을 받는다. [Apple 등록 안내](https://developer.apple.com/help/account/capabilities/enabling-server-to-server-notifications/)
5. 테스트 iPhone 설정에서 Apple 계정의 Sign in with Apple 목록을 열고 대상 앱의 Apple 로그인 사용을 중단한다. 기기 및 OS 버전에 따라 메뉴 이름이 다를 수 있다. Apple 계정 자체는 삭제하지 않는다.
6. 제한된 DB 세션에서 앞 절의 상태 집계와 대상 이벤트를 확인한다. 200은 저장 완료이지 삭제 완료가 아니다. PENDING에서 DELETED로 처리되고 해당 User와 Alarm 등 관련 데이터가 삭제됐는지, 기존 Access/Refresh Token이 거부되는지 확인한다. 이메일 설정 이벤트는 IGNORED이며 삭제하지 않는다. Apple 전달 시간과 성공은 사전 보장되지 않는다.
7. 새 Apple 인증으로 재가입하고 이전과 다른 User ID를 확인한다. 오래된 이벤트가 새 계정을 삭제하지 않는지 격리 합성 테스트 결과와 함께 확인한다.
8. 수신이 없으면 Primary App ID·그룹·등록 URL, 외부 HTTPS/TLS, 인증 차단, Backend 활성화 및 공개키 접근을 조사한다. 잘못된 서명·claim은 401, 공개키 장애는 503, 저장 장애는 서버 오류다. PENDING이 계속되면 Worker 활성화, DB 및 재시도 기한을 확인한다. REVIEW_REQUIRED는 앞 절의 수동 조사 절차를 따른다. 원문 JWS, 토큰과 식별자를 일반 로그나 티켓에 남기지 않고 상태를 임의 수정하거나 삭제를 강제하지 않는다. Apple의 실제 재전송 동작과 운영 복구는 별도 검증이 필요하다.
