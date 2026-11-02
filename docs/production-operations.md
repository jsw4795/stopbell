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
