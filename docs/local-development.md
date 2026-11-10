# Local Development

## TAGO 하차 추적 실험

TAGO 실험은 기본 활성화되며 Flutter 별도 설정은 없다. monitoring, notification dispatch 및 FCM도 기본 활성화된다. Cloudflare Tunnel과 iPhone Profile을 연결한다. 남양주 165번의 목적지와 실제 차량을 선택한 후 최초 위치 확인 중 상태에서 진행을 관찰한다. 2정거장 전과 1정거장 전은 각각 별도 세션으로 확인한다. 필요하면 `TAGO_BOARDING_EXPERIMENT_ENABLED=false`로 끄고 재시작한다.

이 실험은 TAGO 응답이 최신이라는 가정을 사용한다. 원본 생성 시각 및 운행 식별자가 없어 실제 지연·동일 운행을 보장하지 않으며 실기기 성공을 자동 테스트로 대신하지 않는다. 20초 간격으로 한 노선을 하루 종일 조회하면 약 4,320회로 알려진 일일 10,000회 한도의 상당 부분을 사용하며, 다른 조회와 여러 노선도 한도를 공유한다. 같은 주기·노선 응답은 공유되지만 실제 이용량과 기관별 한도를 확인해야 한다.

## Purpose

이 문서는 StopBell의 Local Development 환경을 가능한 한 일관되게 유지하기 위한 기준을 정의한다.

확정된 tool Version은 이 문서에 기록한다. 실행 명령과 아직 결정되지 않은 설정은 구현 전에 확정하며, 문서화되지 않은 Version 또는 Infrastructure를 임의로 추가하지 않는다.

------------------------------------------------------------------------

# Project Requirements

다음 항목은 특정 개발자 machine이 아니라 StopBell 프로젝트를 개발하기 위해 필요한 공통 요구사항이다.

- Java Version: 21
- Spring Boot Version: 4.1.1
- Spring Framework Version: 7.0.9
- Gradle Version: Gradle Wrapper 8.14.3
- JPA: Spring Data JPA / Hibernate ORM 7.4.5.Final
- MySQL Connector/J Version: 9.7.0
- Google API Client for Java Version: 2.9.0
- MySQL Version: 8.4 LTS
- Database execution: Docker Compose
- Database data persistence: Docker Named Volume
- Flutter SDK Version: Flutter 3.47.1 Stable
- Dart Version: 3.13.1

추가 도구가 필요해지면 목적과 Version을 문서화한다.

------------------------------------------------------------------------

# Personal Development Environment

다음 항목은 현재 Primary development environment를 기록한 것이며, 프로젝트의 필수 실행 환경을 의미하지 않는다.

- Operating System: macOS
- Architecture: Apple Silicon (ARM64)

다른 Operating System 또는 Architecture에서도 프로젝트를 개발할 수 있어야 한다. 특정 환경에서만 필요한 설정은 프로젝트 공통 요구사항으로 취급하지 않는다.

------------------------------------------------------------------------

# macOS-specific Setup

macOS-specific 설정은 필요할 때만 이 절에 기록한다.

Apple Silicon (ARM64) 환경에서 확인할 사항:

- 설치하는 Java, MySQL, Flutter SDK, Docker image가 ARM64를 지원하는지 확인
- Docker MySQL을 사용할 경우 ARM64 호환 image를 확인
- iOS 실행 또는 실제 iOS Device 검증이 필요한 경우 필요한 Apple platform toolchain을 확인

정확한 package manager, IDE, Xcode Version, Android toolchain Version은 To be decided이다. 이 항목들은 필요성이 확인된 후 프로젝트 공통 요구사항과 개인 환경 설정을 구분해 기록한다.

------------------------------------------------------------------------

# Backend Setup

## Project Run

Backend 디렉터리에서 Gradle Wrapper 8.14.3을 사용해 실행한다. Java toolchain은 21이며 `java -version`, `./gradlew --version`으로 실제 JVM을 확인한다. 기본 profile은 `local`이고 명시하려면 `--args='--spring.profiles.active=local'`을 사용한다.

```text
cd backend
./gradlew bootRun
```

### One-shot Transit Metadata Bootstrap

metadata bootstrap은 일반 Backend startup과 분리된 명시적 one-shot 실행이다. Servlet endpoint와 SecurityFilterChain을 사용하지 않으므로 `WebApplicationType.NONE`으로 실행한다.

서울 버스 metadata는 필요한 CSV file URI를 함께 전달한다.

```text
./gradlew bootRun --args='
--spring.main.web-application-type=none
--transit.metadata.bootstrap.provider=SEOUL_BUS
--transit.metadata.archive.directory=/absolute/path/to/archive
--transit.metadata.seoul.route-master=file:/path/to/route-master.csv
--transit.metadata.seoul.route-stop-master=file:/path/to/route-stop-master.csv
--transit.metadata.seoul.stop-master=file:/path/to/stop-master.csv
'
```

TAGO metadata bootstrap도 같은 방식으로 provider만 지정해 실행한다.
경기도 full sync는 city/route/API collection 및 DB reconciliation 진행률을 INFO log로 출력한다.
이 실행은 먼저 같은 `PUBLIC_DATA_SERVICE_KEY`로 GBIS 기반정보의 route/routeStation bulk와 version을 검증한 뒤 TAGO traversal을 수집한다. 전체 GBIS bulk 검증에 실패하면 complete sync와 cleanup을 진행하지 않는다. 개별 Route가 TAGO traversal과 일치하지 않으면 해당 Route의 destination만 비워 둔다.

```text
./gradlew bootRun --args='
--spring.main.web-application-type=none
--transit.metadata.bootstrap.provider=TAGO
--transit.metadata.archive.directory=/absolute/path/to/archive
'
```

실행 전 확인 사항:

- MySQL이 실행 중인지 확인
- 아카이브 경로는 미리 생성한 쓰기 가능 절대 디렉터리여야 하며 하드 링크를 지원해야 한다. 설정이 없거나 사용 불가하면 Bootstrap은 수집 전에 실패한다. 일반 Backend 기동에는 설정이 필수가 아니다.
- 저장소 루트의 `.env`에 필요한 Local Development용 설정값이 준비되었는지 확인
- 외부 Transit API 또는 FCM 연동이 필요한 경우 Local Development용 credential이 준비되었는지 확인

## Database Connection

Backend는 Docker Compose로 실행한 MySQL 8.4 LTS instance에 연결한다.

연결에 필요한 값의 예:

```text
DB_HOST
DB_PORT
DB_NAME
DB_USERNAME
DB_PASSWORD
```

Local profile은 `backend/src/main/resources/application-local.yml`에서 위 환경 변수를 사용하며, 연결 URL은 `jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}` 형식이다.

## Environment Variable

시크릿과 환경별 값은 source code 또는 Git에 저장하지 않는다.

예:

```text
DB_PASSWORD
JWT_SECRET
GOOGLE_SERVER_CLIENT_ID
PUBLIC_DATA_SERVICE_KEY
FCM_PROJECT_ID
GOOGLE_APPLICATION_CREDENTIALS
```

`.env.example` 또는 동등한 placeholder file에는 값이 아닌 필요한 key만 기록한다.

`GOOGLE_SERVER_CLIENT_ID`에는 iOS Client ID가 아니라 Backend Authentication audience 검증에 사용할 Google Cloud Web application(Server) Client ID를 설정한다.

Local profile은 Spring Boot Config Data import로 Backend 디렉터리의 상위 경로(저장소 루트)에 있는 `.env`를 optional properties file로 자동 로드한다. 따라서 일반적인 실행 방식인 `cd backend && ./gradlew bootRun`과 Backend 프로젝트를 working directory로 사용하는 IDE/STS 실행에서 별도 Environment Variable 등록 없이 `.env`의 `KEY=value` 설정을 사용할 수 있다. `.env`가 없는 경우에도 Local profile의 설정 로딩은 실패하지 않는다.

`.env`는 Git에 커밋하지 않으며, `.env.example`에는 필요한 key만 유지한다. 운영·배포 환경에서는 OS Environment Variable 또는 배포 환경의 Secret 관리 기능을 사용한다. Spring Boot 기본 property precedence에 따라 OS Environment Variable과 command-line property는 `.env`에서 import한 Config Data보다 우선한다.

`PUBLIC_DATA_SERVICE_KEY`는 TAGO와 서울 Transit 설정에 공통으로 주입된다. `GOOGLE_SERVER_CLIENT_ID`와 `FCM_PROJECT_ID`는 식별 설정이며 비밀 인증키가 아니다. FCM은 기본 활성화되며 `FCM_PROJECT_ID`와 저장소 밖 Service Account JSON의 `GOOGLE_APPLICATION_CREDENTIALS` 경로를 사용한다. 실제 private key 파일을 `.env`나 예제 파일에 복사하지 않는다. Test profile의 설정은 격리된 합성 값이며 운영에서 재사용하지 않는다. JWT 서명 키는 UTF-8 기준 최소 32 bytes이고 충분한 무작위성이 필요하다. 키 교체는 기존 Access Token 검증에 영향을 주므로 운영 시점과 재인증 영향을 계획한다. 운영 Secret 주입·권한·교체·회수와 사고 대응의 실제 적용 검증은 TASK-813에서 수행한다.

------------------------------------------------------------------------

# Database Setup

## Database Environment

Development Database는 MySQL 8.4 LTS를 사용한다.

- Local execution: Docker Compose
- Data persistence: Docker Named Volume

Docker는 Database 저장소가 아니라 Database 실행 환경으로 사용한다.

```text
Docker Container
        ↓
MySQL Process
        ↓
Docker Named Volume
        ↓
Persistent Database Data
```

Container lifecycle과 Database lifecycle은 분리한다. Container를 삭제하거나 재생성해도 Docker Named Volume이 유지되는 한 Database 데이터는 유지되어야 한다.

Container 내부에만 Database 데이터를 저장하지 않는다. 이 방식은 Container 삭제 시 데이터가 손실될 수 있다.

Docker Compose configuration은 저장소 루트의 `docker-compose.yml`에 있으며, 서비스 이름은 `mysql`이다.

## Database Management Notes

- Container lifecycle과 Database lifecycle을 분리한다.
- Database 데이터는 Docker Named Volume에 저장한다.
- Container 재생성 시에도 Database 데이터가 유지되어야 한다.
- Volume 삭제는 명시적인 데이터 삭제 작업으로 취급한다.

## Docker MySQL 개발 DB 백업 및 복원

DB 최신화, Migration 또는 대량 데이터 변경 전에 프로젝트 루트의 `docker-compose.yml`을 사용하고 `mysql` 서비스가 실행 중인지 확인한다. 이 절차는 Docker Named Volume을 사용하는 로컬 개발 MySQL 8.4 DB를 대상으로 한다. 백업 파일은 Git 저장소 외부에 저장한다.

```bash
cd ~/GPT_Codex_Project/stopbell

mkdir -p "$HOME/GPT_Codex_Project/stopbell-db-backups"

BACKUP="$HOME/GPT_Codex_Project/stopbell-db-backups/stopbell-$(date +%Y%m%d-%H%M%S).sql.gz"

(
  set -o pipefail

  docker compose exec -T mysql sh -c '
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    exec mysqldump \
      -h 127.0.0.1 \
      -u root \
      --single-transaction \
      --quick \
      --routines \
      --triggers \
      --events \
      --hex-blob \
      --no-tablespaces \
      --set-gtid-purged=OFF \
      "$MYSQL_DATABASE"
  ' | gzip -c > "$BACKUP"
) && \
gzip -t "$BACKUP" && \
shasum -a 256 "$BACKUP" && \
ls -lh "$BACKUP" && \
echo "백업 생성 및 gzip 무결성 검사 완료"
```

컨테이너의 `MYSQL_DATABASE`를 대상으로 하며, root 비밀번호는 컨테이너의 `MYSQL_ROOT_PASSWORD`에서 `MYSQL_PWD`로 전달하므로 별도 입력할 필요가 없다. 비밀번호를 명령어에 직접 기재하거나 로그에 출력하지 않는다. 호스트의 `stopbell` 계정으로 `mysqldump`를 실행했을 때 `1045 Access denied for user 'stopbell'@'localhost'`가 발생한 적이 있으며, 위 컨테이너 내부 root 방식은 실제 백업에 성공했다. root 사용은 로컬 개발 DB 백업에 한정하며 운영 환경의 일반적인 권한 정책으로 확대하지 않는다.

- `set -o pipefail`은 `mysqldump` 실패를 파이프라인 실패로 감지한다. 실패했거나 불완전한 파일은 정상 백업으로 사용하지 않는다.
- `gzip -t`로 압축 파일의 무결성을 검사하고, `shasum -a 256` 출력으로 SHA-256을 기록하며, `ls -lh`로 실제 크기를 확인한다. 파일명에는 실행 시각이 포함된다.
- 백업 파일과 비밀번호는 Git에 커밋하지 않는다.
- gzip 무결성 검사는 실제 MySQL 복원 성공을 보장하지 않는다. 중요한 DB 변경 전에는 가능하면 별도 격리 MySQL에 복원하여 검증하고, 기존 개발 DB에 복원 테스트를 직접 실행하지 않는다.
- 다른 쓰기 작업이 진행 중이라면 백업 시점의 일관성과 작업 영향을 검토한다. `--single-transaction`은 InnoDB의 일관된 Snapshot 백업에 유용하지만 모든 동시 변경과 DDL을 완전히 보호하지는 않는다.

### 실제 백업 검증 기록 (2026-11-10)

서울버스 전체 CSV Raw Metadata 적용 전에 `~/GPT_Codex_Project/stopbell-db-backups/stopbell-20261110-025545.sql.gz`를 생성했다. 크기는 약 14 MB이고 SHA-256은 `25cabf44039131e4fd3cec9ec36e71c3e5cdca48ba037b50735a3c49ee5e0d83`이다. Docker MySQL 내부 `mysqldump`와 gzip 무결성 검사가 성공했고 SHA-256을 기록했다. 실제 격리 MySQL 복원 테스트는 수행하지 않았다.

## Schema

Database Schema 변경은 Flyway Migration으로 관리한다. Hibernate `ddl-auto`를 통한 자동 Schema 변경은 사용하지 않는다.

Entity 변경만으로 Database Schema를 변경하지 않으며, Schema 변경 시 Migration 파일을 반드시 추가한다.

Migration 파일은 `backend/src/main/resources/db/migration/`에 `V{version}__{description}.sql` 형식으로 둔다. 애플리케이션과 MySQL Testcontainer 통합 테스트가 이를 적용한다.

------------------------------------------------------------------------

### V4 미적용 DB의 Read-only Preflight (TASK-816 / AUD-11)

애플리케이션 시작으로 Flyway가 실행되기 **전**, 변경 대상 DB를 식별하고 쓰기 작업과 Backend를 중지한 점검 구간에서 수행한다. 다음은 수동 확인용 read-only SQL이며 AUD-11 작업에서 실제 개발 DB에 실행한 명령이 아니다. Credential은 기존 비공개 접속 방식을 사용하고 출력에 노출하지 않는다.

```sql
SELECT DATABASE(), VERSION(), @@SESSION.sql_mode;
SELECT table_name FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('flyway_schema_history', 'users', 'alarms', 'notification_history');
```

먼저 테이블 존재 여부를 확인한 뒤 **존재하는 테이블에만** 다음 SQL을 실행한다. History가 없는데 기존 테이블/데이터가 있으면 Fresh DB로 간주하거나 임의 Baseline하지 않고 중단한다.

```sql
SELECT installed_rank, version, description, script, checksum, success
FROM flyway_schema_history ORDER BY installed_rank;
SELECT version, success FROM flyway_schema_history WHERE version = '4';
SELECT COUNT(*) AS user_count FROM users;
SELECT COUNT(*) AS alarm_count, COUNT(DISTINCT user_id) AS alarm_owner_count FROM alarms;
SELECT COUNT(*) AS notification_history_count FROM notification_history;
SELECT COUNT(*) AS orphan_alarm_count FROM alarms a
LEFT JOIN users u ON u.id = a.user_id WHERE u.id IS NULL;
SELECT COUNT(*) AS orphan_history_count FROM notification_history n
LEFT JOIN alarms a ON a.id = n.alarm_id WHERE a.id IS NULL;
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'users';
SHOW CREATE TABLE users;
SHOW CREATE TABLE alarms;
SHOW CREATE TABLE notification_history;
```

- Fresh DB는 기존 데이터/Schema가 없는 별도 DB임을 확인하고 V1→최신 정상 Flyway 경로를 사용한다. V3에서 User 0명이면 정상 FK 아래 Alarm·History도 0명이어야 한다. FK 비활성화 등으로 고아 데이터가 있으면 중단한다. 이후 Migration의 다른 사전 조건도 확인한다.
- V4 미적용이고 User가 1명 이상이면 **자동 업그레이드 지원 범위 밖**이므로 startup을 실행하지 않는다. [Database 정책](database.md#v3v4-legacy-user-업그레이드-지원-범위-task-816--aud-11)에 따라 외부 Identity 매핑 근거·데이터 보존 필요성과 Backup/Restore 가능 여부를 확인하고 별도 이관 계획을 수립한다. User가 한 명이어도 허용하지 않는다.
- V4 성공 기록이 있으면 두 Identity 컬럼과 복합 UNIQUE의 실제 상태를 확인한다. 컬럼이 있을 때만 `SELECT COUNT(*) FROM users WHERE auth_provider IS NULL OR auth_provider = '' OR provider_user_id IS NULL OR provider_user_id = '';`로 부적합 행을 점검한다. 0건이어도 실제 `sub`의 소유자 매핑 정확성을 증명하지 않으므로 이관 근거도 확인한다. 실패 History·Schema 불일치·잘못된 Identity가 있으면 시작을 중단한다.
- Backup 위치·생성 시각·접근 가능 여부와 실제 Restore 검증 결과를 점검 기록으로 남긴다. 개발 데이터 초기화는 Backup 여부 확인과 명시적 사용자 승인 뒤 별도 작업으로만 수행하며 이 절차에는 삭제 명령을 포함하지 않는다.

실패 뒤에는 재시작을 반복하기 전에 Backup과 위 History·`SHOW CREATE TABLE`·컬럼/UNIQUE/FK·원본 행/시각/소유권을 확인한다. MySQL DDL을 일반 DML Transaction처럼 되돌릴 수 있다고 가정하지 않는다. **Flyway Repair는 데이터 복구가 아니며**, 부분 Schema나 Identity 부재를 고치지 않는다. Repair/Clean/Baseline·History 편집·User 삭제·UNIQUE 제거를 자동 실행하지 않고 관측 상태에 맞는 별도 복구/이관 결정을 받는다. 원본을 보존한 채 검증된 Backup 복원 또는 별도 수동 이관을 계획하며, 이 문서는 범용 복구 SQL을 제공하지 않는다.

# Test Strategy

Alarm 상태 전이 같은 순수 Domain Business Rule은 Spring Context, Database, Docker 없이 JUnit Unit Test로 검증한다.

JPA Entity Mapping과 Repository 동작은 H2 또는 Local Development MySQL이 아닌 MySQL Testcontainers 기반 Integration Test로 검증한다. 기본 MySQL `8.4.11` 서버는 `SharedMySqlExtension`이 JUnit 실행의 root store에서 한 번 시작하고 관리한다. Spring 통합 테스트는 `@ExtendWith(SharedMySqlExtension.class)`와 클래스별 `@DynamicPropertySource`로 독립 DB의 DataSource를 연결한다. 각 클래스의 `@DirtiesContext(AFTER_CLASS)`가 Spring Context와 HikariCP를 먼저 닫으며, root store는 모든 사용자가 끝난 뒤 잔여 테스트 연결이 0개인지 확인하고 DB와 서버를 정리한다. 일부 클래스만 선택해도 같은 생명주기를 사용한다.

Migration·시작 실패 테스트는 `@BeforeEach`에서 `SharedMySqlExtension.Database`를 주입받아 호출마다 새 DB를 사용한다. JUnit method store가 호출 종료 시 DB와 권한을 제거하므로 parameterized invocation의 실패 DDL·Flyway 이력도 격리된다. 공통 서버는 실패 주입용 트리거 생성을 위해 `--log-bin-trust-function-creators=1`을 시작 시 고정하며, 테스트가 전역 설정을 변경하지 않는다. SQL mode·timezone 변경은 기존 session 범위를 유지한다. 자식 JVM 재시작 검증도 해당 클래스의 DB 접속 정보를 그대로 전달한다.

`BackupRestoreIntegrationTest`는 공통 서버의 독립 DB에서 실제 `mysqldump`를 실행하고, 빈 별도 MySQL `8.4.11` 서버에서 실제 `mysql` 클라이언트로 복원한다. 별도 서버로의 백업 이식성 검증을 위해 복원 대상 컨테이너 1개만 예외로 유지하며, 전체 테스트의 MySQL 시작은 기본 서버 1회와 복원 대상 1회다.

Test Schema는 Hibernate가 자동 생성하지 않는다. 기본 `ddl-auto: none` 또는 개별 통합 테스트의 `validate`를 사용하며, Testcontainer의 빈 격리 DB에 Flyway Migration을 적용한다. Integration Test 실행에는 Docker Runtime이 필요하다. Backend 테스트는 `backend`에서 `./gradlew test`로 실행하며 실제 개발 DB를 테스트 대상으로 사용하지 않는다.

------------------------------------------------------------------------

# Flutter Setup

## Project Run

Flutter SDK와 iOS platform toolchain을 준비하고, `app/config/local.example.json`을 참고해 Git에서 제외되는 `app/config/local.json`을 만든다. 설정에는 `API_BASE_URL`, `GOOGLE_IOS_CLIENT_ID`, `GOOGLE_SERVER_CLIENT_ID`를 넣는다. iOS Client ID는 Google Cloud iOS OAuth Client의 값이며, Server Client ID는 Backend의 `GOOGLE_SERVER_CLIENT_ID`와 동일한 Web/Server OAuth Client 값이다. Client Secret은 Flutter 설정에 넣지 않는다.

Flutter 기본 Dart define 파일로 설정을 주입한다. `app` 디렉터리에서 실행한다.

```text
flutter pub get
flutter run --dart-define-from-file=config/local.json
```

## Device Connection

실제 Device 또는 emulator/simulator를 연결한다.

현재 iOS deployment target은 15.0이고 Push registration과 Notification Lifecycle은 iOS 경로에 연결돼 있다. Push 수신·표시/tap 검증은 실제 iPhone에서 수행한다. Simulator의 API/UI 확인을 실기기 Push 검증으로 대체하지 않으며 Android Push 연동·실기기 검증은 완료되지 않았다.

Flutter/Dart 설치 버전과 toolchain은 `flutter --version`, `flutter doctor`로 확인한다. `app`에서 `flutter analyze`, `flutter test`를 실행하며 앱별 안내는 [app/README](../app/README.md)를 따른다.

## Firebase iOS early smoke (TASK-701)

SDK/target/lifecycle 계약의 Owner는 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#task-701-firebase-ios-기술-계약-2026-10-06)이다. 사용자의 실기기 검증 보고로 실제 Firebase config/APNs 준비, HTTP v1 `message.fid` 전송·서버 접수와 실제 iPhone notification 수신 성공을 확인했다. TASK-701의 hardware smoke gate와 TASK-702의 Domain/Schema 정의는 완료됐으며 Backend Device API는 TASK-703에서 구현했다. 완료 결과와 미검증 항목은 ADR-010에 기록한다.

### Firebase Console / Apple Developer 설정 순서

1. 사용할 실제 Firebase project를 선택한다. 기존 Google OAuth project를 Firebase에 연결할 수 있으나 프로젝트를 임의로 선택·생성하거나 OAuth 값을 바꾸지 않는다. 이 작업은 Google OAuth + Backend Auth를 Firebase Auth로 대체하지 않는다. Analytics 등 다른 Firebase 제품은 이번 smoke에 추가하지 않는다.
2. Firebase Console → Project settings → General에서 iOS App을 등록/확인한다. Bundle ID는 정확히 `com.stopbell.stopbell`이다. 해당 앱의 실제 `GoogleService-Info.plist`를 내려받는다.
3. 파일을 `app/ios/Runner/GoogleService-Info.plist`에 둔 뒤 `app/ios/Runner.xcworkspace`를 Xcode로 연다. Add Files to Runner에서 해당 파일을 Runner target에 포함하고 Build Phases → Copy Bundle Resources에 실제 파일이 있는지 확인한다. 파일 이름에 `(2)` 등의 suffix를 붙이지 않는다. 가짜 plist/placeholder 값은 사용하지 않는다. Dart는 native default config로 `Firebase.initializeApp()`를 호출하므로 이번 경로에는 임의의 `firebase_options.dart`가 필요하지 않다.
4. Apple Developer Portal에서 해당 Team의 명시적 App ID `com.stopbell.stopbell`에 Push Notifications가 활성화되어 있는지 확인한다. APNs 사용 가능한 Authentication Key를 준비하고 `.p8`, Key ID, Team ID를 확인한다. Firebase Console → Project settings → Cloud Messaging → 해당 iOS App의 APNs authentication key에 실제 key를 업로드한다. Debug development 환경을 지원하는 key인지 확인한다.
5. Xcode Runner → Signing & Capabilities에서 올바른 Apple Developer Team과 실제 iPhone을 선택하고 Push Notifications, Background Modes의 Background fetch/Remote notifications를 확인한다. 저장소에는 capability 및 `aps-environment`가 구성되어 있지만 Portal App ID와 provisioning profile의 권한까지 대신 설정하지는 않는다. Push entitlement를 포함하는 개발 profile을 갱신하고 실제 signed debug install로 확인한다. Xcode가 unrelated upgrade metadata를 바꾸면 해당 변경은 제외한다.
6. Firebase method swizzling은 기본 enabled 상태를 유지한다. `FirebaseAppDelegateProxyEnabled = NO`를 추가하지 않는다. Firebase SDK는 Flutter의 생성 SPM graph가 공급한다. Xcode Add Packages에서 Firebase를 중복 추가하거나 Podfile/pod install을 도입하지 않는다.
7. Google Cloud Console에서 같은 project의 Firebase Cloud Messaging API (`fcm.googleapis.com`) 활성화를 확인한다. 전송할 계정에 해당 project의 `cloudmessaging.messages.create` 권한이 필요하며 Firebase Cloud Messaging API Admin 역할이 이를 제공한다. Smoke에는 기존 gcloud 로그인 계정을 사용하고 Service Account private key를 생성/저장하지 않는다.

`GoogleService-Info.plist`는 공식적으로 non-secret app/project config다. 기존 저장소에 별도 Firebase config ignore 정책이 없으므로 실제 파일과 Xcode resource reference는 리뷰 가능한 tracked config로 관리한다. 현재 실제 설정은 준비됐으며 이번 정리에서 config 값을 변경하지 않는다. `.p8`, Service Account private key 및 access token은 repository에 넣지 않는다. Firebase 설정을 위해 기존 Google Sign-In URL scheme/client ID를 자동 교체하지 않는다.

### 실제 iPhone 실행과 FID 전송

1. iPhone을 Mac에 연결하고 Trust / Developer Mode / signing 준비를 마친다. `app` 디렉터리에서 `flutter devices`로 실제 iPhone ID를 확인한다.
2. 제품 앱과 분리된 smoke entrypoint를 실행한다. Backend 실행이나 Google 로그인은 필요 없다.

   ```text
   flutter run --debug --no-pub -t lib/firebase_smoke.dart -d <실제-iPhone-ID>
   ```

3. iPhone에서 `Firebase / APNs / FID 준비`를 누르고 알림을 허용한다. Firebase 초기화, APNs 준비와 native FCM `register()`가 성공하면 Firebase project ID와 **FID**가 화면에 표시된다. APNs 원문과 legacy FCM token은 표시/저장하지 않는다. APNs를 10초 이내 얻지 못하거나 FID가 바뀌면 준비 버튼으로 다시 확인한다. 권한을 거부했다면 iPhone 설정에서 smoke 앱의 알림을 허용한 뒤 재시도한다. 이는 최종 제품 permission UX가 아니다.
4. FID는 `SelectableText`로 표시되므로 길게 눌러 선택·복사할 수 있다. FID는 대소문자를 구분한다. 비슷하게 보이는 `I`, `l`, `1` 등의 문자에 주의하고 수동 입력보다 복사·붙여넣기를 권장한다. iPhone과 Mac이 같은 Apple Account를 사용하고 Handoff 조건을 충족하면 Universal Clipboard로 붙여넣을 수 있다. 표시된 FID를 복사한 뒤 iPhone 앱을 background로 보낸다. Notification Center/배너 수신을 직접 볼 수 있게 Focus/알림 표시 설정을 확인한다. Foreground 표시 및 tap lifecycle 전체 검증은 이번 Task에 포함하지 않는다.
5. Google Cloud CLI가 설치된 Mac에서 사용할 계정으로 `gcloud auth login`을 수행한다. 이미 올바른 계정으로 로그인했다면 생략한다. Repository root에서 다음을 실행하고 hidden prompt에 iPhone 화면의 FID를 입력한다. 아래 `stopbell`은 현재 Firebase project ID이며 다른 환경에서는 display name/project number가 아닌 화면/config의 project ID를 사용한다.

   ```text
   python3 tools/fcm_ios_smoke_send.py --project-id stopbell
   ```

   도구는 한 번만 HTTP v1 `message.fid` 대상으로 alert notification을 전송한다. Target/access token을 command argument나 로그에 남기지 않으며 retry, credential file, production Provider Client를 만들지 않는다. Firebase Console의 legacy registration-token 입력 화면만으로 FID 지원을 추정하지 않고 명시적 `fid` 요청을 사용한다. CLI 설치가 안 된 환경은 Cloud Shell에 이 단일 script를 업로드해 같은 명령을 실행할 수 있다.

6. `FCM accepted: projects/.../messages/...`는 Provider 접수만 의미한다. 실제 iPhone에서 `StopBell TASK-701 smoke` 알림을 **한 번 이상 직접 수신 확인**한다. 준비/전송 상태만으로 smoke 성공을 기록하지 않는다.
7. 재실행 결과는 직접 확인한 Provider 접수 여부 및 실제 수신 여부를 기록한다. SDK/OS 버전, 수신 시각, 기기 모델이나 앱 상태는 확인한 경우에만 기록하며 추정하지 않는다. 원문 FID/APNs token/credential은 보고나 로그에 붙이지 않는다.

실패 시 `401/403`은 로그인·IAM·API/project 설정, `404`는 FID 복사·입력의 정확성과 올바른 project의 현재 FCM 등록 FID인지, APNs 미준비는 entitlement/profile/signing/network, 접수 후 미수신은 APNs key 환경·permission·알림 표시 설정을 확인한다. Smoke 도구는 raw error body를 출력하지 않고 알려진 top-level status와 FCM errorCode만 출력한다(예: `FCM HTTP 404: status=NOT_FOUND, fcmErrorCode=UNREGISTERED`). JSON 파싱 실패나 예상 밖의 schema에서는 확인 가능한 항목만 출력하며, 확인할 항목이 없으면 HTTP status만 출력한다.

사용자가 확인한 이전 404의 최종 원인은 수동 FID 입력 오류(`I`/`l` 혼동)이며 SDK/FCM 결함으로 확정된 문제는 없다. Native unregister, foreground/terminated 수신과 tap navigation, Backend Admin SDK 연동은 아직 미검증이다. Backend Device API와 Flutter 제품의 Device 등록은 각각 TASK-703/704에서 구현했으며 Backend Provider Client는 후속 작업이다.

Smoke 앱은 명시적 FCM 등록을 남기지만 auto-init을 켜지 않으며 StopBell Backend Device에는 등록하지 않는다. 반복하려면 현재 FID를 다시 준비한다. 일반 product entrypoint로 돌아오려면 기존 `flutter run --dart-define-from-file=config/local.json`을 사용한다. 이번 smoke에서 Firebase installation deletion을 logout/정리 수단으로 호출하지 않는다.

공식 setup 근거: [Flutter FCM Apple capability/APNs/swizzling](https://firebase.google.com/docs/cloud-messaging/flutter/get-started), [Firebase Apple app/config setup](https://firebase.google.com/docs/ios/setup), [HTTP v1 fid targeting](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages), [FCM authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

## TASK-704 정식 iOS Client 검증

정식 진입점은 `lib/main.dart`이며 기존 `config/local.json`을 사용한다. Firebase/설치본 저장·pending/계정 전환 정책은 [ADR-010 Client 구현](adr/ADR-010-notification-device-and-durable-delivery.md#task-704-flutter-설치본등록-lifecycle-구현-2026-10-12), Endpoint는 [Device API](api.md#기기-등록해제-계약-task-702)가 소유한다. SDK dependency, 실제 Firebase config, Info.plist의 FID mode/auto-init, SPM과 기존 signing 설정은 유지한다.

```text
flutter run --debug --no-pub --dart-define-from-file=config/local.json -d <실제-iPhone-ID>
```

1. Google 로그인 뒤 비활성 또는 FOLLOW_UP 알람에서 활성화를 선택한다. permission이 notDetermined이면 이유 안내 → 계속 → OS permission 요청이 나온다. 이미 허용했다면 반복 permission popup 없이 준비한다.
2. 허용된 permission, APNs readiness, native FCM completion/callback FID 및 Backend Device 등록/조회 확인이 끝나면 `이 기기의 알림 수신 준비를 완료했습니다.`를 표시한다. 이 안내는 실제 Push 수신 성공을 뜻하지 않으며 Alarm activate 성공/실패도 별도로 확인한다.
3. denied/미준비/Backend 실패 때 안내의 `알람 활성화`로 계속할 수 있다. denied에서는 `설정 열기`를 제공한다. 무한 APNs polling과 자동 ownership conflict retry는 없다.
4. background → foreground, 앱 initialization 재실행에서 불필요한 Device POST 없이 준비 상태가 복원되는지 확인한다. 정상 logout에서는 Device disable을 확인하고 같은 계정 재로그인은 높은 revision으로 재등록한다.
5. 다른 계정 로그인 및 A→B→A를 확인한다. 이전 계정의 Alarm 상세/foreground action/dialog가 남지 않아야 한다. 서버 generation은 ownership 이전에만 증가한다. 원문 UUID/FID/credential/JWT를 출력하거나 캡처하지 않는다.
6. 재설치/강제 FID rotation은 별도 검증 환경에서 수행한다. 일반 logout을 위해 앱 삭제, FIS delete, FCM deleteToken/unregister를 호출하지 않는다. 실제 수신과 foreground/background/terminated tap E2E는 TASK-710에서 검증한다.

DEBUG 상태 로그는 `StopBell push stage`의 APNs/FID readiness·Device register/disable 확인 및 `StopBell push outcome`의 안전한 enum만 출력한다. 원문 식별자, JWT, credential, request/response/error body는 출력하지 않는다. 정식 bridge는 release에도 존재하며 TASK-701 smoke channel은 DEBUG에만 남는다.

### 2026-10-12 확인 결과

- production `main.dart` 경로를 연결된 **iPhone 13 Pro Max**에 기존 개발 signing/config로 설치·실행했다. 기존 Backend와 HTTPS 개발 주소의 `/actuator/health` 200을 확인했다. 계정/credential은 사용자가 직접 사용했으며 생성·대체하지 않았다.
- 안전한 상태 로그로 APNs readiness → FCM 등록 callback FID → Backend Device register → ready를 확인했고 사용자가 activation의 준비 완료 안내를 확인했다. 이 기기의 현재 허용 상태로 검증했으며 최초 notDetermined popup/denied 설정 변경의 실기기 결과를 별도로 주장하지 않는다.
- 반복 동기화와 foreground 복귀는 Device mutation 없이 ready를 반환했다. logout은 Backend disable-confirmed, 같은 계정 재로그인은 register-confirmed/ready를 반환했고 사용자가 목록과 무오류 동작을 확인했다.
- Dart hot restart로 initialization/Auth/Secure Storage를 다시 복원한 뒤 Device mutation 없이 ready를 확인했다. 전체 OS process 재시작/재설치 검증과 구분한다.
- 실제 A→B→A에서 각각 disable → register-confirmed/ready를 확인했고, 사용자가 계정별 목록/이전 상세 정리/무오류 동작을 확인했다. 원문 계정/설치본/FID 정보는 기록하지 않았다.
- 집중 Flutter 테스트 4개 파일 35개는 저장·응답 유실/pending·FID rotation·계정 전환·permission/tap을 검증한다. 변경된 Auth/API/bootstrap의 직접 관련 회귀 테스트까지 총 98개가 통과했고 변경 Flutter 파일 analyze는 No issues다. Backend suite/Testcontainers는 재실행하지 않았다. Apple SDK 12.19.0과 unsigned **release** 빌드 및 signed debug 실기기 빌드가 성공했고 native request의 callback/completion 양쪽 순서·중복 completion·nil FID·실패 처리를 좁은 Swift 검사로 확인했다.
- **미검증:** 실기기 재설치, 강제 FID rotation, 실제 offline logout, permission 최초/거부 전환, full OS process restart, 실제 Provider 전송 및 foreground/background/terminated notification tap/수신 E2E. 앞선 correctness는 집중 테스트로, 실제 Provider smoke는 TASK-705, 최종 수신/tap E2E는 TASK-710으로 구분한다.

## TASK-705 Backend Provider 수동 Smoke

상태: **코드·집중 자동 테스트 및 사용자 실제 Firebase 접수·iPhone 수신 검증 완료** (2026-10-12). Codex는 이 절의 실전송을 실행하지 않았다. TASK-701의 Python 도구와 달리 아래 실행은 구현한 Java Provider와 같은 경로를 사용한다.

선행조건:

- 기존 Firebase project `stopbell`의 FCM HTTP v1 API와 APNs key/environment가 준비돼 있어야 한다. 선택 Service Account에는 해당 project의 FCM 전송 권한이 필요하다. 설정/IAM/APNs를 이 Task에서 자동 변경하지 않는다.
- **저장소 밖의 기존 Service Account JSON 파일**이 필요하다. credential이 아직 없다면 사용자가 안전하게 준비한 후 실행한다. `gcloud auth login`만으로는 이 Provider 인증이 준비되지 않는다. 현재 구현은 사용자 ADC/metadata/WIF discovery가 아닌 명시적 Service Account 파일 경로를 지원한다. 파일 내용/private key/token을 명령·로그·보고에 붙이지 않는다.
- 기본 local DB/JWT/Google/Transit 환경 설정을 갖추고 기존 MySQL을 사용한다. 정상 제품 앱에서 Push 준비/Device 등록을 마친 enabled IOS Device ID와 **같은 owner의 기존 Alarm ID**를 선택한다. 등록 API 응답의 Device `id`를 사용하거나 DB에서 `id/user_id/platform/enabled`만 조회하며 FID/설치본 credential을 출력하지 않는다. DB 초기화/새 seed는 필요 없다.

Backend 디렉터리에서 사용자가 다음을 실행한다. `10`/`20`은 선택한 Device/Alarm ID로 바꾸고 credential 경로는 기존 파일의 절대 경로로 바꾼다.

```sh
export GOOGLE_APPLICATION_CREDENTIALS='/absolute/path/outside-repository/service-account.json'
export FCM_PROJECT_ID='stopbell'
./gradlew bootRun --args='--spring.profiles.active=local --spring.main.web-application-type=none --transit.monitoring.enabled=false --notification.dispatch.enabled=false --apple.notifications.enabled=false --account.deletion.recovery-enabled=false --notification.fcm.enabled=true --notification.fcm.smoke=true --notification.fcm.smoke-device-id=10 --notification.fcm.smoke-alarm-id=20'
```

`local` + 명시적 command-line Smoke opt-in + non-web + monitoring disabled 조건에서만 실행한다. Device/FID는 read-only transaction 안에서 메모리로 읽고 같은 owner/IOS/enabled를 확인한 뒤 transaction 종료 후 **한 번만** 전송한다. 완료/실패 뒤 context를 닫으며 자동 startup 전송·공개 테스트 endpoint·worker·scheduler를 추가하지 않는다. Event/Delivery/Alarm/Device row를 INSERT/UPDATE하지 않고 Delivery를 ACCEPTED로 꾸미지 않는다. 일반 startup은 FCM과 dispatch가 기본 활성화되므로 필수 정적 설정을 요구한다.

Smoke payload는 `ALARM_EVENT`, 선택 `alarmId`, `ARRIVED`, **합성 navigation hint `notificationEventId=1`**이며 Event row를 생성하거나 존재를 주장하지 않는다. notification은 `StopBell 알림 / 버스가 목표 정류장에 도착했어요.`다. 이 테스트에서만 APNs expiration을 현재 UTC + 60초로 주고, 후속 운영 Event Type TTL을 확정하지 않는다. 실제 Alarm ownership/current detail 확인은 기존 Flutter tap contract를 따른다.

확인할 결과:

1. 안전한 `FCM smoke result=ACCEPTED code=null` 로그는 Firebase 접수만 뜻한다. 메시지 ID/FID/token/raw response는 출력하지 않는다. 재시도·sleep은 없고 실패 시 사용자가 원인 확인 뒤 새 실행을 선택한다.
2. iPhone을 background로 두고 허용된 알림/Focus 설정에서 **실제 alert 수신**을 별도로 확인한다. Foreground/terminated/tap 전체 E2E는 TASK-710에 남는다.
3. `CONFIGURATION`이면 파일 형식/접근·project/IAM/APNs, `INVALID_TARGET`이면 현재 FCM 등록 상태를 확인한다. 실패로 Device를 자동 disable하지 않는다. timeout/AMBIGUOUS_TIMEOUT은 이미 접수됐을 수 있어 재실행에 중복 표시 위험이 있다.
4. 사용자 확인 후 Firebase 접수와 iPhone 실제 수신 여부, 확인한 앱 상태만 민감정보 없이 기록하고 TASK-705를 최종 완료 처리한다. 2026-10-12 사용자 검증으로 TASK-705 checkbox를 완료 처리했다.

사용자 검증 기록 (2026-10-12): Firebase Project `stopbell`, 기존 Backend Device ID `1`과 동일 owner의 Alarm ID `7`, 실제 iPhone 13 Pro Max에서 로컬 one-shot Backend FCM Smoke를 실행했다. Firebase `ACCEPTED` 로그와 실제 iPhone의 `StopBell 알림 / 버스가 목표 정류장에 도착했어요.` 수신을 사용자가 직접 확인했다. Backend → FCM → 실제 iPhone Smoke가 성공했다.

실제 NotificationEvent 기반 자동 발송, Worker dispatch, foreground/background/terminated 전체 수신 E2E, notification tap navigation 전체 E2E 및 retry/expiry 운영 정책은 검증하지 않았다. TASK-706/709/710/711의 후속 책임이며 Codex는 실기기 검증이나 실전송을 반복하지 않았다.

개발 후보 timeout은 connect `PT2S`, read `PT5S`, 인증 포함 전체 budget `PT15S`다. 이 값은 운영 retry/TTL 확정이 아니며 실제 latency 측정은 아직 없다. 세부 단일 요청·abort 보장과 ADC 지원 범위는 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#task-705-provider-구현과-제어-gate-2026-10-12)을 따른다.

### TASK-706 자동 dispatch 구성 경계

TASK-706 Worker 구조와 집중 Fake Provider/MySQL Testcontainers 검증을 추가했다. 현재 `notification.dispatch.enabled=true`가 기본이며 TASK-705 FCM enabled/local Smoke와 독립적이다. TASK-706 당시 handler 부재 gate는 TASK-709에서 production Handler와 FCM 정적 설정 gate로 대체했다. 실제 자동 Firebase/iPhone 검증의 현재 진행 상태는 아래 TASK-710 기록을 따르며, 전체 E2E 및 운영 latency tuning은 미완료다. **TASK-710 검증 전 운영 자동 활성화하지 않는다.** 설정과 구현/후속 recovery 경계는 [Architecture](architecture.md#notification-dispatch-worker-구현-task-706)를 따른다. Codex는 실제 Firebase 전송, local DB dispatch/bootRun, iPhone/Flutter/Xcode 작업을 수행하지 않았다.

## TASK-710 실제 iPhone 수신 및 tap 검증

### 2026-10-16 사용자 확인 결과

사용자가 직접 확인한 실제 iPhone 검증 결과다. Codex는 이번 기록 작업에서 코드 수정, 테스트 실행, 실제 FCM 전송을 수행하지 않았다.

- 실제 버스 이벤트 기반 자동 FCM 알림 수신 성공
- iPhone 포그라운드 수신 성공
- iPhone 백그라운드 수신 성공
- iPhone 앱 완전 종료 상태 수신 성공
- 백그라운드 알림 탭 후 알람 상세 화면 이동 성공
- MySQL `NotificationDelivery`의 `ACCEPTED` 기록 확인

2026-10-16 당시 미검증 항목:

- 완전 종료 상태에서 알림 탭 후 상세 화면 이동
- 삭제되거나 유효하지 않은 알람의 알림 탭 처리
- 계정 변경 및 소유권 불일치 시 접근 차단

위 당시 결과는 확인된 수신·화면 이동·DB 기록 범위에 한정하며 전체 수신/tap E2E 완료를 뜻하지 않는다. 이후 TASK-711의 격리 자동 E2E/regression은 완료했으며 실제 기기 TASK-710은 미완료다.

### 2026-10-28 USB 실기기 검증 결과

- 환경 **PASS**: Xcode 27.0 (27A266a), Flutter 3.47.1 / Dart 3.13.1, iPhone 13 Pro Max / iOS 27.0.1. CoreDevice에서 `wired`, `paired`, Developer Mode `Enabled`를 확인했다. 기기명·UDID·credential은 기록하지 않는다.
- 기존 Apple Team/Bundle ID `com.stopbell.stopbell`, Firebase Project `stopbell` 및 OAuth 설정을 유지했다. 기존 signing으로 debug 빌드가 성공했으며 embedded provisioning의 유효기간·해당 기기 포함·APNs entitlement를 확인했다. cold-start 검증용 release 빌드와 실제 iPhone 설치도 성공했다. Flutter run은 `build/ios/iphoneos/Runner.app` 경로를 찾지 못했으나 실제 산출물은 `app/build/ios/Release-iphoneos/Runner.app`에 있었다. 해당 bundle의 `codesign --verify --deep --strict` 통과 후 `xcrun devicectl device install app`으로 설치했다.
- 연결 **PASS**: 기존 Cloudflare 임시 개발 주소는 DNS 해석 불가였다. 사용자 승인으로 같은 방식의 임시 HTTPS 터널을 시작하고 Git 제외 `app/config/local.json`의 API 주소만 갱신했다. 로컬/HTTPS `/actuator/health` 모두 200을 확인했다. 실제 release 앱 실행 뒤 사용자가 기존 A 계정의 알람 목록 정상 표시를 확인했다. 검증 종료 후 이번에 시작한 Backend·임시 터널을 종료하고 Git 제외 local API 주소를 테스트 전 값으로 원복했다. 설치된 release 앱에는 이번 임시 주소가 포함되므로 다음 연결 검증에는 유효한 개발 주소로 다시 빌드해야 한다.
- 기존 Docker MySQL을 시작하고 Java 21 / Gradle Wrapper 8.14.3으로 Backend를 실행했다. 기존 Flyway V15·V16이 적용됐고 BoardingSession은 0건이다. `notification.fcm.enabled=false`, `notification.dispatch.enabled=false`, `transit.monitoring.enabled=false`로 자동 전송·polling을 차단했다. 실제 FCM은 매 전송 직전 별도 사용자 승인 후 실행한다.

| 테스트 | 결과 | 실제 확인 범위 / 잔여 작업 |
| --- | --- | --- |
| A: terminated 알림 tap | PASS | 사용자 완전 종료·단일 전송 승인 후 Backend one-shot 1회 `ACCEPTED code=null`. 사용자가 실제 수신·tap 후 M2352(Alarm #7) 상세 정상 표시를 확인했다. 합성 navigation hint이며 Event/Delivery row 생성 없음 |
| B: 삭제·유효하지 않은 Alarm | PASS | A 소유 임시 INACTIVE Alarm #12/Target을 로컬 DB에 복제하고 사용자 승인 one-shot 1회 `ACCEPTED` 후 임시 row만 삭제했고 이 시점에 잔여 0건·기존 #7/#8/#10 보존을 확인했다. 사용자가 알림 tap 후 접근 불가 안내 표시·상세 미노출을 확인했다. 삭제 API 자체 검증은 아님 |
| C: 계정 변경·소유권 불일치 | PASS | 사용자 승인 one-shot 1회 `ACCEPTED` 및 실제 수신 후 B 로그인. DB에서 Device #1의 owner=B/enabled, Alarm #10의 owner=A를 확인했다. 사용자가 이전 A 알림 tap 후 접근 불가 안내·A 상세 미노출을 확인했다 |
| D: 신규 Boarding UI | BLOCKED (일부 PASS) | 사용자 확인: 하차 알림 진입·TAGO 165 노선/목적 정류장 선택·실제 후보/차량번호 표시·하차 추적 지원 보류 안내·확정 불가 버튼 비활성은 PASS. 전체 차량번호 입력/Push 준비 UI는 진입하지 못했다. 서울 143 조회는 사용자가 “버스 정보를 일시적으로 불러올 수 없어요” 안내를 확인했다 |

D 원인 분석: 현재 `SeoulBusVehicleLocationClient`와 동일 설정/timeout을 사용하는 임시 진단으로 서울 143 roster를 **1회** 추가 조회했고 `failureKind=HTTP`, `httpStatus=401` / `Unauthorized`를 확인했다. 제품 코드는 변경하지 않았고 URI·Key·응답 원문을 출력하지 않았다. 후보 service는 Provider 예외를 Backend 503으로 변환하므로 실기기 안내와 일치한다. 직접 Java 진단의 상류 HTTP 응답 401까지만 확인했으며 오류 body/header를 보존하지 않았다. 이를 실기기 요청 자체의 401이나 API/Endpoint 무효·Key 불량의 증거로 해석하지 않는다. 아래 후속 진단에서 응답 계층과 공식 Call Back 경로를 추가 확인했다. 기존 credential·Provider URL·지원 정책을 임의로 변경하지 않았다. 전체 차량번호 입력·Push 준비·생성 요청 뒤 `BOARDING_TRACKING_UNSUPPORTED` 응답 안내는 **NOT_TESTED**다. TAGO 후보의 지원 보류 안내 확인을 이 후속 경로의 성공으로 대체하지 않는다.

이번 실제 FCM은 A/B/C 각각 사용자의 전송 직전 승인을 받은 기존 Backend one-shot **총 3회**이며 모두 Provider `ACCEPTED` 및 사용자 실수신 확인이다. 자동 dispatch·새 Event/Delivery 생성·실제 BoardingSession 생성/추적 성공을 주장하지 않는다. 자동 검증 suite는 코드 변경이 없어 재실행하지 않았으며 signed release build/install/launch, 실제 사용자 확인, DB 및 문서 diff 검증을 수행했다.

최종 DB 점검: 임시 Alarm #12는 0건, BoardingSession은 0건, 기존 NotificationDelivery는 `ACCEPTED` 8건이었다. Alarm #7/#10은 INACTIVE로 남아 있고, B 계정 Alarm #8의 부재는 사용자가 이번 테스트 중 직접 삭제한 결과임을 확인했다. 사용자 삭제를 임의로 복원하지 않았다. 기기의 마지막 로그인/owner는 B다.

실기기 미확인 결과를 성공으로 기록하지 않는다. `BOARDING_TRACKING_UNSUPPORTED` 및 TASK-710/TASK-908 `[ ]`를 유지하며, 기존 로컬 Provider 실측 도구 2개는 Untracked로 보존한다.

### 2026-10-28 서울 HTTP 401 발생 지점 후속 진단

사용자는 공공데이터포털에서 `getBusPosByRouteStList`와 `getBusPosByRtidList`의 실제 차량 XML 정상 반환을 확인했다. 따라서 API 자체나 기존 Endpoint의 무효를 전제하지 않는다. 사용자가 확인한 roster 성공 URL의 path는 `/api/rest/buspos/getBusPosByRtid`다. 인증 query는 기록하지 않으며 포털 Key와 로컬 `.env` Key가 동일한지는 미확인이다.

| 경계 | 확인 결과와 증거 범위 |
| --- | --- |
| Flutter → StopBell | `GET /api/v1/bus-routes/{routeId}/boarding-candidates?targetStopOccurrenceId={id}[&boardingStopOccurrenceId={id}]`. `AuthenticatedApiClient`가 현재 세션의 `Authorization: Bearer`를 설정한다. 실제 이전 요청의 숫자 ID·JWT·access log는 보존하지 않았으므로 해당 원문을 복원했다고 주장하지 않는다 |
| Backend Security | 이 API는 JWT 인증 필수다. 기존 미인증/잘못된 JWT 통합 테스트에서 401·Provider 호출 0회를 확인했다. 실기기에서 확인한 “버스 정보를 일시적으로 불러올 수 없어요”는 현재 Flutter 코드의 **Backend 503 분기**다. 실기기에서 StopBell 401을 직접 관측한 기록은 없다 |
| Controller → Provider | metadata read 뒤 roster Client를 호출한다. Provider 예외는 `TRANSIT_TEMPORARILY_UNAVAILABLE` 503으로 변환한다. 유효한 실제 서명 fixture JWT + 서울 상류 401을 조합한 격리 통합 테스트에서 Provider 1회·Client 503을 확인했다. 이 테스트는 이전 실기기 요청의 네트워크 캡처를 대체하지 않는다 |
| 앞선 직접 Java 진단 | Backend Security/Flutter를 거치지 않고 서울 Client가 받은 HTTP 401 / `Unauthorized`다. 따라서 이 **확인된 401은 StopBell Security가 만든 응답이 아니다**. 다만 raw response body/header가 없어 서울 origin/Gateway/중간 계층 중 정확한 응답자와 세부 거절 이유는 미확정이다 |
| 안전한 오류 기록 | 후보 roster 실패에 `stage=provider-roster`, Provider, 논리 operation, failure kind, upstream HTTP status, Client response status만 WARN 기록하도록 보강했다. URI/query·Key·JWT·오류 원문·Throwable cause는 기록하지 않는다. 상류 401과 하류 503을 혼동하지 않도록 회귀 테스트에서 secret 비노출도 검증했다 |

[공식 20230530 활용가이드](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000003552007&fileDetailSn=1)에서 상세기능명과 실제 Call Back URL이 다름을 재확인했다(SHA-256 `257e0778fb5036ae859fe67805a55361a5db1836a17083e5684517c713987f90`). [현재 공개 서비스 페이지](https://www.data.go.kr/data/15000332/openapi.do?recommendDataYn=Y)도 구간 기능의 실제 URL을 `getBusPosByRouteSt`로 안내한다.

| 상세기능명 | 공식 Call Back path | 수정 전 Client path | 이번 최소 수정 |
| --- | --- | --- | --- |
| `getBusPosByRtidList` | `/api/rest/buspos/getBusPosByRtid` | `/api/rest/buspos/getBusPosByRtidList` | roster `REQUEST_PATH`를 공식 path와 사용자 성공 path에 맞춤 |
| `getBusPosByVehIdItem` | `/api/rest/buspos/getBusPosByVehId` | `/api/rest/buspos/getBusPosByVehIdItem` | 같은 서울 adapter의 detail path도 공식 명세에 맞춤 |

논리 `OPERATION`은 기존 값을 유지하고 실제 HTTP path만 분리했다. Host/base URL, Key, Key 인코딩, 요청 parameter·DTO·Authentication 정책·지원 gate는 바꾸지 않았다. **확인된 결함은 기능명을 Call Back URL로 사용한 구성 차이**이며, 이전 401의 유일한 원인이었다거나 기존 path alias가 무효라고까지 확정하지 않는다. 포털에서 XML을 받았다는 사실만으로 현재 JSON 역직렬화 경로가 실응답에서 통과한다고 주장하지도 않는다.

현재 local 설정은 `http://ws.bus.go.kr/api/rest/buspos`이며, 수정된 Client에 실제 설정을 넣되 transport 실행 전에 차단하는 임시 capture로 roster/detail의 공식 path, `serviceKey` 존재·설정의 encoded 값 유지 및 Provider로 StopBell Authorization header가 전달되지 않음을 확인했다. 외부 HTTP 실행은 **0회**다. 알려진 Mac→`ws.bus.go.kr:443` TCP timeout은 별도 transport 문제이며 이번에는 재측정하지 않았다. HTTP 평문 인증키 전송으로 우회하지 않았고 TLS 도달성/안전한 호출 경로를 확보하기 전 실제 Key를 사용한 재호출은 보류한다.

검증: Java 21 / Gradle 8.14.3, 집중 Backend **62개**(서울 roster/detail Client, 후보 API/Security·service·revalidation / 격리 MySQL Testcontainers 포함), Flutter **43개**(Bearer 전달·401 refresh·503 무재시도·보류 UI)가 모두 통과했다. 변경 Flutter 테스트 analyze와 `git diff --check`도 수행했다. 테스트는 Mock Provider/fixture JWT를 사용하며 실제 Provider 데이터 요청·FCM·iPhone 재검증은 **0회**다. 새 Dependency/Migration·Git add/commit/push 없이 기존 사용자 변경사항·Untracked Provider 도구 2개를 보존했다. 수정 뒤 실응답 401 해소와 Boarding 후속 UI는 **NOT_TESTED**이며 `BOARDING_TRACKING_UNSUPPORTED`와 TASK-710/TASK-908 `[ ]`를 유지한다.

### 2026-10-28 서울 공식 HTTP 개발·운영 공통 지원

사용자 확정으로 서울 realtime은 개발·운영 모두 공식 `http://ws.bus.go.kr` origin을 직접 사용한다. 기존 HTTP 실호출 보류는 앞선 진단 당시의 실행 제한이며, 이번에는 HTTP service key 노출·변조 위험을 인정한 공통 지원 정책으로 변경했다. 영구 정책·보호 경계는 [Architecture의 transit 절](architecture.md#transit)이 소유한다. 앱→Backend HTTPS 정책·다른 Provider 설정·하차 추적 gate는 유지한다.

- 공통 `application.yml`의 `transit.client.seoul.base-url` 기본값과 `.env.example`의 `SEOUL_BUS_BASE_URL`은 `http://ws.bus.go.kr`다. override도 공식 origin 형식이어야 하므로 예전 `/api/rest/buspos` 접미사를 붙인 값은 origin 값으로 교체한다. 개발 전용 HTTP opt-in/profile 제한·운영 차단·HTTPS→HTTP fallback은 없다.
- roster `/api/rest/buspos/getBusPosByRtid`, detail `/api/rest/buspos/getBusPosByVehId`를 고정 적용한다. 다른 host, 임의 port, userinfo/query/fragment/path가 섞인 base URL은 거부하고 redirect도 따르지 않는다.
- XML 우선 요청·기존 JSON 호환을 지원한다. XML 선언의 encoding을 byte input에서 읽고 반복/단일 `itemList`, 빈 optional 값과 기존 DTO 필드를 처리한다. JDK parser의 DTD/외부 entity/schema/XInclude를 차단했다. 정상 empty/Provider logical/HTTP/transport/protocol 분류와 기존 timeout은 유지한다. 새 Dependency는 없다.
- 집중 오프라인 94개(개발·운영 context, 공식 host 제한, redirect, XML/JSON/오류, TAGO 및 후보/Mapper 회귀)가 통과했다. Backend 전체 suite 884개 중 883개 통과, 기존 `FcmProviderClientTest.budget_aborts_slow_body`의 서버 연결 종료 2초 대기 1개가 실패해 조건 변경 없이 해당 클래스 전체와 local/prod/production 설정 테스트를 분리 재검증하여 모두 통과했다. 최초 전체 실행을 모두 성공한 것으로 기록하지 않는다.
- 사용자가 HTTP 평문 service key의 노출·변조 위험을 확인하고 최대 2회를 명시 승인한 뒤, 서울 143 `busRouteId=100100022` roster 직접 1회 + 로컬 Boarding Candidates API 경유 1회, **총 2회**를 실행했다. 승차 occurrence를 생략해 detail 0회이며 retry/대체 요청·FCM·자동 polling·BoardingSession 생성은 없다. 임시 진단은 저장소 밖에서 기존 production Client와 local Backend를 사용하고 종료 시 Backend를 닫았다. JWT는 메모리에서만 사용했으며 Key/전체 요청 URL/실제 차량 식별자·번호/원문 XML·API body는 출력·기록하지 않았다.

| 실제 검증 | 결과 |
| --- | --- |
| 공식 roster HTTP | **PASS**: HTTP 200, `headerCd=0`, XML 차량 목록 44건 |
| 핵심 field 파싱 | **PASS**: `vehId`/`plainNo` 존재 44/44, `sectOrd` 숫자 44/44, `stopFlag` 0/1 값 44/44, `dataTm` 14자리 형식 44/44 |
| Boarding Candidates | **PASS (조회 계약)**: 실제 JWT가 통과하는 loopback HTTP API 요청에서 200, `candidateCount=44`, `candidateSetStatus=PARTIAL` |
| 지원 gate·부작용 | BoardingSession 0건, FCM/dispatch/monitoring=false. 정상 조회를 실제 추적 지원/세션 생성/알림 성공으로 기록하지 않음 |

Candidates의 `PARTIAL`은 실제 응답 상태다. 개별 reason code별 수량은 이번 요약에 저장하지 않았고, detail 생략/신뢰도·지원 보류 경계의 구체 항목을 모두 검증한 것으로 확대하지 않는다. 실제 조회 성공은 Mac의 local Backend에서 확인했으며 운영 서버 배포/실제 운영 망 검증 및 iPhone Boarding 후속 UI 재검증은 이번 범위에서 실행하지 않았다. 운영 지원 자체는 profile gate 없이 공통 설정에 적용하고 local/prod/production context로 검증했다. TASK-710/TASK-908 `[ ]`와 `BOARDING_TRACKING_UNSUPPORTED`를 유지한다.

### 2026-10-28 서울 ws.bus.go.kr HTTP 설정 전수 정리

Backend의 외부 요청 Client·설정·주입 경로와 서울 metadata source를 점검했다. 사용 중인 서울 REST Client는 아래 2개이며 공통 `transit.client.seoul` 설정과 redirect 차단 HTTP transport를 사용한다.

| 기능 | 실제 구현 / 요청 path | 적용 상태 |
| --- | --- | --- |
| 노선 전체 차량 위치(roster) | `SeoulBusVehicleLocationClient` / `/api/rest/buspos/getBusPosByRtid` | 공통 HTTP origin 사용, 공식 Endpoint 유지 |
| 특정 차량 위치(detail) | `SeoulBusVehicleDetailClient` / `/api/rest/buspos/getBusPosByVehId` | 같은 공통 HTTP origin 사용, 공식 Endpoint 유지 |
| 노선·정류장 metadata | `SeoulCsvMetadataSource`의 T Data CSV 3종 | ws.bus.go.kr REST Client 없음. 다른 서울 도메인·Resource 입력은 변경하지 않음 |
| 도착정보 및 기타 서울 REST API | 현재 Backend에 별도 Client/호출 없음 | 신규 Client/Endpoint를 추가하지 않음 |

최종 정책에 맞춰 HTTPS를 허용하던 서울 설정 검증을 **공식 HTTP origin만 허용**하도록 바꾸고, `application-test.yml`의 중복 HTTPS Base URL을 제거했다. 테스트 profile도 공통 `application.yml`의 HTTP 기본값을 상속하며 운영 profile 차단은 없다. local/test/prod/production context와 두 서울 Client의 scheme·host·path·query URI 계약을 확인한다. TAGO의 test용 HTTPS override와 운영 기본 HTTPS URL은 그대로 유지한다. 보안 정책과 평문 전송 위험은 [Architecture](architecture.md#transit)를 따른다.

현재 Backend의 실행 가능한 서울 HTTPS 설정은 남아 있지 않다. `https://ws.bus.go.kr` 문자열은 HTTPS 거부 테스트와 과거 문서 기록에 남는다. 별도로 사용자 요청에 따라 보존한 Untracked `tools/provider_evidence_probe.py`에는 기존 연구용 HTTPS 주소가 남아 있으며 Backend 실행 설정과 무관하다. 두 로컬 Provider 도구는 수정·실행·Git 포함하지 않았다. 로컬 `.env`와 process environment의 서울 URL override는 없음을 값 노출 없이 확인했다.

이번 실제 Provider/FCM 호출은 **0회**이며 앞선 HTTP 200·44건·Candidates 200 결과를 반복하지 않았다. 새 Dependency·Migration·API 계약·FCM/Alarm 동작 변경은 없다. `BOARDING_TRACKING_UNSUPPORTED`, TASK-710/TASK-908 `[ ]` 및 main·기존 변경사항을 유지하고 Git add/commit/push는 수행하지 않는다. Java/Gradle JVM 21 및 Wrapper 8.14.3을 확인하고 관련 회귀 **105개**(Client URI·HTTP origin/profile·redirect/XML/오류·TAGO·후보·서울 Mapper·CSV source)를 실행하여 실패/오류/skip 없이 모두 통과했다. `git diff --check`도 통과했다. 첫 실행의 test profile TAGO 기대값을 기존 mock HTTPS override에 맞게 보정한 뒤 동일 suite 전체를 재통과했으며 제품 TAGO 설정은 변경하지 않았다.

## TASK-908 iPhone Boarding E2E 준비 (2026-10-28)

이 절의 준비 상태·검증 결과는 당시 기록이며 아래 사용자 실행 순서는 TASK-908 하차 전 단일 알림 정책으로 갱신했다. 실제 143번 Provider 생성/추적 결과는 [증거 보고서 §24](research/TASK-908-provider-evidence.md#24-실제-서울-143번-생성진행notification-검증-2026-10-28)를 따른다. TASK-908은 번호 입력 없이 즉시 TRACKING 등록을 구현했으며 지원 경계는 [API](api.md#탑승-세션-api-task-903)를 따른다. **Backend·터널·Flutter 앱은 중지 상태를 유지하며 아래 절차는 다음 사용자 재실행 지시 후에만 수행한다.**

2026-10-28 준비 당시 확인한 상태:

- 기존 개발 MySQL 8.4.11에 V17 적용 및 17개 Migration validate 성공. 기존 INACTIVE Alarm 2개, ACCEPTED Delivery 8개, IOS Device 1개와 143번 metadata 113개 occurrence 보존. BoardingSession/PENDING Delivery는 0개
- Backend를 `127.0.0.1:8080`에 실행하고 기존 Cloudflare 임시 HTTPS 방식을 재사용. 로컬/HTTPS health `200 UP` 확인 후 Git 제외 `app/config/local.json`의 API_BASE_URL만 갱신. 실제 iPhone 네트워크에서의 연결 확인은 사용자가 수행
- **당시 실행은 monitoring=false / dispatch=false / FCM=false**이며 별도 실전송 승인 전까지 유지. 기존 `.env`의 FCM_ENABLED=true는 변경하지 않았고 실행 인자로 false를 우선 적용. Scheduler는 Boarding 전용 별도 토글이 아니라 기존 `transit.monitoring.enabled`를 공유
- 기존 Firebase project/Bundle/OAuth 일치, FID mode와 명시적 native 등록·auto-init=false, background fetch/remote-notification·기본 swizzling 보존. Service Account 파일은 SDK에서 네트워크 없이 parsing 성공, project 일치 확인. OAuth token 발급·Firebase 전송·Console APNs key 유효성 재검증은 미실행
- 유선 iPhone 13 Pro Max/iOS 27.0.1, paired/Developer Mode enabled 확인. 새 HTTPS 주소로 signed release 빌드 성공, `codesign --verify --deep --strict` 통과. 실제 서명의 APNs entitlement=development, 기존 provisioning에 연결 기기 포함·만료일 2027-10-07 확인. 기기 설치/실행/권한 변경은 수행하지 않음
- DB의 기존 Device는 enabled/FID 있음까지만 확인. 현재 설치본의 APNs/FID·로그인 owner·알림 권한·OS 수신은 앱을 실행해 Push 준비를 완료해야 다시 확인 가능. 원문 FID/JWT/credential을 출력하지 않음

기존 NULL offset 세션의 완료 목적지 Worker 경로는 TRACKING 준비 gate와 분리되어 있다. 신규 세션은 목적지 Event/Push를 만들지 않는다. 아래 목적지 전달 검증은 당시 기존 정책의 기록이다. 완료 transaction은 준비 PENDING만 종료하고 목적지 PENDING을 보존한다. Worker는 COMPLETED 목적지를 선정하고 불변 완료 checkpoint·Event 대응·owner·freshness·원래 기한을 검증한 후 전달한다. 취소/만료는 허용하지 않는다. 격리 MySQL과 loopback HTTP v1에서 준비/목적지 payload·정확한 Session/Event ID·FID·APNs expiration·두 번 Worker 실행의 단일 요청·ACCEPTED 저장을 확인했다. 실제 Firebase 접수/기기 수신 결과는 아니다.

### 사용자 실행 순서

1. 기존 MySQL의 healthy 상태와 8080 listener 부재를 확인하고 `backend`에서 아래 안전 모드 Backend를 **하나만** 실행한다. 이어 별도 터미널에서 `cloudflared tunnel --url http://127.0.0.1:8080`을 실행한다. 로컬 및 새 HTTPS 주소의 `/actuator/health`를 확인하고 `app/config/local.json`의 API_BASE_URL을 해당 HTTPS 주소로 맞춘다. 현재 작업에서는 재실행하거나 local.json을 변경하지 않았다.

   ```sh
   env -u JAVA_HOME ./gradlew bootRun --args='--server.address=127.0.0.1 --transit.monitoring.enabled=false --notification.dispatch.enabled=false --notification.fcm.enabled=false --notification.fcm.smoke=false'
   ```

   안전 모드에서도 후보/확정 요청은 실제 Provider를 호출하므로 다음 실측 승인 범위 안에서만 수행한다. 자동 polling·알림은 OFF다. 터널 주소가 바뀌면 앱 재빌드가 필요하다.
2. `app`에서 `flutter devices`로 실제 기기를 선택하고 아래 release 명령을 실행한다. 과거 signed 산출물을 현행 정책 검증에 재사용하지 않고 현재 소스·유효한 API 주소로 빌드한다. 앱 삭제·Firebase installation 초기화는 필요 없다.

   ```sh
   flutter run --release --no-pub --dart-define-from-file=config/local.json -d <실제-iPhone-ID>
   ```

3. 사용할 계정으로 로그인하고 iOS 알림을 허용한다. Focus/알림 표시 상태와 하차 확정 전 Push 준비 완료를 확인한다. Backend에 같은 owner의 enabled IOS Device가 등록돼야 한다. 기존 다른 계정의 Device를 직접 DB에서 이전하지 않는다. 이 단계에서는 세션을 생성하지 않으며, 실제 확정과 그 과정의 Push 준비 확인은 아래 활성화 후 수행한다.
4. **실제 Provider 자동 조회·FCM 전송 별도 승인 후에만** 안전 모드 Backend를 종료하고 8080 listener 부재를 확인한 뒤 `backend`에서 다음 설정으로 하나만 재실행한다. credential/project는 기존 `.env`를 사용하고 smoke는 끈다. Worker를 켜면 허용된 기존 Alarm/Session PENDING도 처리하므로 그 시점의 대상 상태를 다시 확인한다. 이번 작업에서는 이 명령을 실행하지 않았다.

   ```sh
   env -u JAVA_HOME ./gradlew bootRun --args='--server.address=127.0.0.1 --transit.monitoring.enabled=true --notification.dispatch.enabled=true --notification.fcm.enabled=true --notification.fcm.smoke=false'
   ```

5. 서울 143번 → 목적/선택적 승차 occurrence → 차량 후보 조회 → 원하는 차량 명시적 선택 → 2정거장 전(기본)/1정거장 전 설정 → 하차 알림 등록 순서다. 차량번호 입력은 없으며 도착 전·이동 중 등록할 수 있다. 등록 성공 즉시 TRACKING 화면으로 이동한다. 최초 위치 근거가 부족하면 TRACKING/VERIFICATION_PENDING으로 차량 위치 확인 중을 표시한다. 후속 신뢰할 관측에서 TRACKING/READY와 진행 갱신·준비 알림을 확인한다. 위치·진행 근거가 불확실하면 판단을 보류한다. Push 준비 중 후보 만료는 같은 selectionKey의 차량만 재조회하며 실패 시 재선택한다. 실제 Session ID와 상태·비식별 reason을 확인한다. 새 정책 검증에는 V20까지의 Schema가 필요하며 기존 개발 DB 적용 여부는 아래 실행 경계와 실행 전 점검을 따른다.
6. 새 세션은 선택한 시점의 목적지 이전 준비 알림 한 번을 기대하며 별도 목적지 Push는 없어야 한다. 2정거장 전 관측을 놓친 경우 1정거장 전 대체와 목적지 현재/이후 소급 준비 알림 없음도 구분한다. 매 시험은 새 세션으로 foreground/background/terminated 상태를 나누고 수신·tap을 확인한다. foreground는 앱 안내의 사용자 선택으로, background/terminated는 알림 tap으로 **정확한 Session ID의 상세**가 열려야 한다. 이후 목적지 도달·통과로 COMPLETED/ENDED가 된 상세도 정상이다. 기존 NULL offset 세션은 이전 정책이므로 별도 호환 검증으로 구분한다.
7. 서버에서 같은 Session의 Event 종류·Delivery 상태·attempt/result와 실제 기기 표시를 대조한다. ACCEPTED는 Firebase 접수일 뿐 실수신과 별도로 기록한다. 통상 반복 polling/Worker에서는 Event 중복·추가 발송이 없어야 하나 접수 후 응답 유실/crash의 OS exactly-once는 보장하지 않는다. 취소 시험은 목적지 이전에 별도 세션으로 수행한다.
8. 시험 종료 뒤 Flutter 실행·터널·Backend를 정상 종료하고 8080 listener와 관련 실행 프로세스 부재를 확인한다. 안전 모드 Backend도 상시 실행해 두지 않는다. Docker/MySQL·Volume·DB·설정 파일은 보존한다. TASK-908/710/711은 각 실제 검증 완료 전까지 미완료로 유지한다.

2026-10-28 당시 검증: Backend 발송 관련 **109개**와 Flutter 전체 **437개** 실패/오류/skip 없이 통과, `flutter analyze` 통과 및 signed release 빌드/서명 검증 성공. production 발송 코드는 이미 완료 목적지를 지원하므로 변경하지 않았으며 테스트만 보강했다. 실제 서울 API 추가 호출·FCM 전송·기기 설치/조작은 0회다.

## Backend Connection

Mac에서 Backend를 실행하는 iPhone Simulator의 Local `API_BASE_URL`은 `http://localhost:8080`이다. 실제 iPhone의 `localhost`는 Mac을 가리키지 않으므로 Mac의 접근 가능한 주소 또는 개발 서버 주소를 사용한다. 기존 실기기 검증은 Mac Backend를 가리키는 임시 HTTPS 터널을 사용했다. 주소가 만료·변경되면 `app/config/local.json`의 `API_BASE_URL`을 갱신해 재빌드하며, 과거 기록의 터널 주소를 유효한 주소로 가정하지 않는다. Google Server Client ID는 Backend와 일치해야 한다. 실행 순서와 승인 경계는 [TASK-908 사용자 실행 순서](#사용자-실행-순서)를 따른다.

------------------------------------------------------------------------

# Development Workflow

## Database Startup

1. Docker Desktop을 실행한다.
2. `docker compose up -d`를 실행한다.
3. Spring Boot를 실행한다.
4. 개발을 진행한다.

Compose file은 저장소 루트에 있으며, 서비스 이름은 `mysql`이다.

## Feature Workflow

1. 문서와 ADR을 확인하고, 필요한 가정을 먼저 기록한다.
2. 하나의 `TASK-XXX` 단위를 선택한다.
3. Feature Branch를 생성한다.
4. 해당 Task에 필요한 구현과 Test를 작성한다.
5. 관련 Backend 또는 Flutter Application을 Local에서 실행해 확인한다.
6. Test를 실행한다.
7. 동작, 계약, Architecture Decision이 변경되었다면 관련 문서를 업데이트한다.
8. 하나의 목적에 집중한 Commit을 만든다.

권장 Commit 형식은 `development-guidelines.md`를 따른다.

### TASK-709 retry/expiry 실행 경계

기본 `notification.dispatch.enabled=true`에는 유효한 FCM enabled/project/신뢰하는 credential path/timeout 설정이 필요하며 정적 설정 누락은 startup을 차단한다. one-shot Smoke에서 자동 dispatch를 차단하려면 명시적으로 false를 지정한다. 실제 credential 내용은 로그하거나 저장소에 추가하지 않는다.

런타임 인증/프로젝트/APNs 등 CONFIGURATION 결과가 나오면 Worker는 미시작 Delivery를 보존하고 expiry만 처리한다. 설정·인증을 고친 뒤 Backend 재시작으로 차단을 해제한다. 이미 FAILED인 Delivery는 자동 복구하지 않는다. retry/recovery/count/TTL을 운영자가 초기화하거나 오래된 알림의 기한을 연장하는 흐름은 없다. 개발 후보값·안전 상한은 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#task-709-구현-설정과-검증-2026-10-13), transaction/Worker 경계는 [Architecture](architecture.md#notification-dispatch와-result-transaction-task-709)를 따른다.

2026-10-13 Codex 검증은 집중 unit/configuration 및 격리 MySQL Testcontainers·Mock Provider에 한정했다. 실제 FCM/iPhone, Service Account 파일, 개발 DB, Flutter/Xcode 및 전체 Backend suite는 사용하지 않았다. TASK-710/711에서 자동 Event 전송의 foreground/background/terminated 수신·tap, 오래된 notification 처리, 실제 crash/restart와 multi-device 및 재등록 경쟁, latency 분포와 timeout/TTL/retry 후보값 tuning을 검증한다.

## Future Consideration

- Database seed data 및 fixture 제공 방식
- API mock 또는 Transit provider sandbox 사용 여부

Local profile은 위 Backend Setup, 외부 Service Account 경로와 opt-in FCM/dispatch는 TASK-705/709 절에 확정된 실행 계약이 있다. 운영 Secret 주입과 배포 준비는 TASK-806/813에서 검증한다.


### TASK-908 하차 전 단일 알림 변경의 실행 경계 (2026-10-29)

현재 소스의 신규 세션은 실제 목적지를 유지하고 2정거장 전(기본)/1정거장 전 준비 알림만 생성한다. 현재 빌드와 과거 §24~27 E2E 산출물은 구분하며 예전 설치본/Backend로 새 정책을 검증했다고 하지 않는다. 기존 개발 DB에는 V20을 아직 적용하지 않았다. 향후 승인된 실행 시 기존 MySQL/설정/데이터를 보존하고 정상 Flyway로 V20을 적용한 뒤 새 세션에서 확인한다. 기존 NULL offset 세션·대기 알림은 기존 정책이므로 새 정책 E2E와 혼동하지 않는다.

이번 작업은 Testcontainers의 격리 MySQL, Provider mock/loopback FCM HTTP fixture, Flutter 자동 테스트와 서명 없는 iOS build만 사용한다. 실제 Provider·FCM·기기 설치/실행은 별도 승인 범위이며 monitoring/dispatch/FCM 설정과 Credential을 변경하지 않는다. 실제 무정차 stOrd 갱신·회차/반복 occurrence·수신/tap 검증은 [증거 보고서 §28](research/TASK-908-provider-evidence.md#28-하차-전-단일-알림과-이동-진행-평가-2026-10-29)을 따른다. Backend와 터널을 시작해 두지 않으며 TASK-908은 미완료로 유지한다.

### TASK-808 Backend runtime image

Backend 디렉터리에서 Java 21 Gradle Wrapper로 `./gradlew bootJar`를 실행하면 `build/libs/stopbell.jar`가 생성된다. Docker build context는 이 JAR만 포함하며 `.env`와 소스·개발 credential을 포함하지 않는다. Dockerfile은 digest로 고정한 Java 21 JRE, UID/GID 10001, UTC, exec-form Java entrypoint와 readiness healthcheck를 사용한다. 기본 `production` profile에는 로컬 `.env` import가 없으며 DB 설정과 인증 설정은 실행 환경에서 주입한다. 실제 FCM 사용 시 저장소 밖 Service Account 파일을 UID 10001이 읽을 수 있도록 별도로 마운트한다.

같은 플랫폼에서 이미지 ID까지 재현하려면 BuildKit 0.20.2의 timestamp 정규화를 사용한다. 다음은 ARM64 예이며 다른 아키텍처는 `--platform`을 명시해 별도로 빌드한다. builder 생성·제거는 이 예의 전용 builder만 대상으로 한다.

```sh
cd backend
./gradlew bootJar
docker buildx create --name stopbell-repro --driver docker-container --driver-opt image=moby/buildkit:v0.20.2
docker buildx build --builder stopbell-repro --platform linux/arm64 --build-arg SOURCE_DATE_EPOCH=0 --output type=docker,name=stopbell:local,rewrite-timestamp=true .
docker buildx rm stopbell-repro
```

일반 `docker build -t stopbell:local .`도 실행 가능한 이미지를 만들지만 구형 Docker/BuildKit은 생성 시각 차이로 이미지 ID가 매번 달라질 수 있다. 동일한 base digest·플랫폼·Gradle/JDK·dependency와 빌드 도구가 필요하며 플랫폼 간 동일 digest를 주장하지 않는다. Base image의 보안 업데이트는 digest를 검토·교체한 뒤 재검증한다.

`/actuator/health/readiness`가 200이 될 때만 healthcheck가 성공한다. fresh DB에는 별도의 metadata bootstrap이 필요하다. 이미지의 graceful shutdown phase 기본 예산은 60초이며 배포 환경의 종료 유예는 이보다 짧지 않아야 한다. 이 값은 모든 단계의 전체 종료 SLA가 아니고 사용자 설정 timeout을 강제로 제한하지 않는다. 실제 운영 배포·secret injection과 restart/recovery 검증은 TASK-813/811 책임이다.

### TASK-809 CI 검증

GitHub Actions의 `.github/workflows/ci.yml`은 `main` push, pull request 및 수동 실행에서 독립된 Backend와 Flutter job을 실행한다. Backend는 Ubuntu runner의 Docker와 Java 21, Gradle Wrapper로 `./gradlew --no-daemon build`를 실행하며 실제 MySQL/Flyway 검증에는 격리 Testcontainers를 사용한다. Flutter는 3.47.1 stable에서 lockfile을 강제한 dependency resolution 뒤 `flutter analyze --no-pub`와 `flutter test --no-pub`를 실행한다. Action은 commit SHA로 고정하고 읽기 전용 repository permission을 사용한다.

일반 CI에는 실제 Transit Provider 호출, 운영 credential, Firebase 전송 및 서명된 iOS archive를 사용하지 않는다. Testcontainers image와 Flutter/Gradle dependency 다운로드에는 runner 네트워크가 필요하다. 로컬에서 같은 Backend 검증은 `cd backend && ./gradlew --no-daemon build`, Flutter 검증은 `cd app && flutter pub get --enforce-lockfile && flutter analyze --no-pub && flutter test --no-pub`로 수행한다. 운영 배포와 기기 실측을 대체하지 않는다.


### Backend 기본 기능 및 필수 설정

Monitoring, notification dispatch, FCM, TAGO 하차 추적 실험, Apple 인증, Apple 서버 알림 수신·삭제 Worker 및 계정 삭제 복구는 기본 ON이다. 각 Spring 설정에 명시적으로 `false`를 지정해 끌 수 있다. 예를 들어 `--transit.monitoring.enabled=false`를 사용할 수 있다. 테스트 profile은 외부 작업과 Apple 인증을 별도로 끈다.

기본 local profile은 저장소 밖 `.env`를 읽으며 기존 `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `GOOGLE_SERVER_CLIENT_ID`, `PUBLIC_DATA_SERVICE_KEY`, `FCM_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS`를 사용한다. FCM 경로는 저장소 밖 Service Account JSON을 가리켜야 한다. 기본 dispatch는 FCM 정적 설정이 유효하지 않으면 시작을 차단하며 파일 내용과 실제 전송은 실행 시 검증된다.

Apple 인증에는 Spring 설정 `apple.authentication.client-id`, `team-id`, `key-id`, `private-key-pem`, `credential-key-id`, `credential-keys`가 필요하다. 서버 알림은 `apple.notifications.client-id`도 요구하며 누락 시 시작이 실패한다. 비밀은 외부에서 주입한다. Apple Developer에 HTTPS server-to-server notification URL을 등록해야 실제 알림을 수신할 수 있다. Flutter의 Apple 로그인 버튼 설정은 별도다.

필수 설정을 준비한 뒤 `backend`에서 `env -u JAVA_HOME ./gradlew bootRun --args='--server.address=127.0.0.1'`로 실행한다. 기존 `.env`나 환경변수의 명시적 false는 기본값보다 우선한다.


### 교통 메타데이터 사전 분석 결과 조회

명시적 Bootstrap 실행 후 MySQL에서 수집 실행과 예상 변경분 요약을 함께 확인한다. `summary`의 Route 변경 없음은 Route 자체 필드만 뜻하며 occurrence는 별도 집계다. 이 분석은 실제 적용 건수가 아니고, 실행 상태가 `COMPLETED`인지 별도로 확인해야 한다.

```sql
SELECT r.id, r.provider, r.status, r.failure_stage, r.failure_code,
       d.baseline_type, d.previous_complete_sync_at, d.analyzed_at, d.summary
FROM transit_metadata_collection_runs r
LEFT JOIN transit_metadata_diff_analyses d ON d.collection_run_id = r.id
ORDER BY r.id DESC;
```

차단된 실행의 위험 상세는 아래처럼 실행 ID를 지정해 순서대로 조회한다. `:run_id`, `:page_size`, `:offset`은 MySQL 클라이언트에 맞는 숫자 값으로 바꾼다. `STOP_CLEANUP`에는 기존 고아도 포함될 수 있으며 상세는 실제 적용 결과가 아니다.

```sql
SELECT r.id AS collection_run_id, r.provider, a.result_fingerprint,
       d.sequence_number, d.reason, d.route_id, d.stop_id, d.stop_order, d.replacement_stop_id
FROM transit_metadata_collection_runs r
JOIN transit_metadata_diff_analyses a ON a.collection_run_id = r.id
JOIN transit_metadata_unsafe_change_details d ON d.collection_run_id = r.id
WHERE r.id = :run_id
ORDER BY d.sequence_number
LIMIT :page_size OFFSET :offset;

SELECT d.sequence_number, v.route_id, v.stop_order, v.stop_name, v.latitude, v.longitude
FROM transit_metadata_unsafe_change_details d
JOIN transit_metadata_unsafe_change_variants v ON v.detail_id = d.id
WHERE d.collection_run_id = :run_id
ORDER BY d.sequence_number, v.sequence_number;

SELECT COUNT(*) AS total FROM transit_metadata_unsafe_change_details WHERE collection_run_id = :run_id;
```

이전 완전 Snapshot과 현재 DB의 일치는 보장되지 않으며 실패한 동기화의 분석도 예정치로 보존된다.


### 위험 변경 로컬 검토·승인 명령

이 명령은 로컬 운영자용이며 사용자 인증을 제공하지 않는다. DB 접속 권한으로 접근을 제한한다. `metadata-admin` 프로필은 예약 작업과 자동 Flyway·스키마 초기화를 비활성화하며 명시적인 비웹 one-shot 명령에만 사용한다. 실행 전에 별도 절차로 V38까지 적용된 DB가 필요하다. 일반 서버 실행은 기존 프로필을 사용한다. 아래 값은 예시이며 실행 ID와 두 지문 전체를 실제 검토 결과로 교체한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local,metadata-admin --spring.main.web-application-type=none --transit.metadata.admin.enabled=true --notification.dispatch.enabled=false --transit.monitoring.enabled=false --metadata.command=review --run=123 --page=0 --size=50'
./gradlew bootRun --args='--spring.profiles.active=local,metadata-admin --spring.main.web-application-type=none --transit.metadata.admin.enabled=true --notification.dispatch.enabled=false --transit.monitoring.enabled=false --metadata.command=approve --run=123 --size=50 --result-fingerprint=전체분석지문 --review-fingerprint=전체상세지문 --expires-at=2027-01-01T00:00:00Z --note=검토사유 --confirm=APPROVE'
./gradlew bootRun --args='--spring.profiles.active=local,metadata-admin --spring.main.web-application-type=none --transit.metadata.admin.enabled=true --notification.dispatch.enabled=false --transit.monitoring.enabled=false --metadata.command=status --approval=456'
./gradlew bootRun --args='--spring.profiles.active=local,metadata-admin --spring.main.web-application-type=none --transit.metadata.admin.enabled=true --notification.dispatch.enabled=false --transit.monitoring.enabled=false --metadata.command=revoke --approval=456 --confirm=REVOKE'
./gradlew bootRun --args='--spring.profiles.active=local,metadata-admin --spring.main.web-application-type=none --transit.metadata.admin.enabled=true --notification.dispatch.enabled=false --transit.monitoring.enabled=false --metadata.command=apply --approval=456 --provider=SEOUL_BUS --confirm=APPLY'
```

승인 전에 모든 상세 페이지를 검토한다. `apply`는 실제 Provider 수집과 DB 변경을 수행할 수 있으므로 환경을 확인하고 별도 결정 후 실행한다. 이미 선점된 승인은 실패해도 재사용하지 않는다. 실제 최신 Provider 데이터와 운영 적용은 아직 검증되지 않았다. 분석과 실제 쓰기 사이의 다른 writer 제한 및 직접 동기화 우회 경계는 [Architecture](architecture.md#교통-메타데이터-사전-분석-db-연동-phase-3-a-4-2차)를 따른다.

### 교통 메타데이터 최신화 사전 점검

V38까지 적용된 로컬 DB에서 아래 명령을 명시적으로 실행한다. 이 명령은 실제 Provider를 호출하거나 메타데이터·승인을 변경하지 않는다. 아카이브를 확인하려면 기존 아카이브 디렉터리를 지정한다. `TAGO`를 선택하면 TAGO와 GBIS 원본을 각각 표시한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local,metadata-admin --spring.main.web-application-type=none --transit.metadata.admin.enabled=true --notification.dispatch.enabled=false --transit.monitoring.enabled=false --metadata.command=preflight --provider=SEOUL_BUS --transit.metadata.archive.directory=/absolute/path/to/archive'
```

Provider는 `SEOUL_BUS` 또는 `TAGO`다. 미완료 수집 이력은 실행 여부와 상태를 확인하기 전까지 `BLOCKED`이며, 이력만으로 실제 프로세스가 살아 있다고 단정하지 않는다. `READY`는 로컬 조회 결과에만 적용되며 실제 최신성, API 가용성, 메모리 사용량 또는 공식 노선유형 매핑의 검증이 아니다. 파일은 내용 해시가 아니라 접근 여부와 크기를 확인한다. 실패했던 동기화가 일부 Route를 저장했을 수 있어 마지막 성공 시각만으로 현재 DB의 일치를 판단하지 않는다. 검토 대기는 승인 행이 아직 없는 위험 변경을 뜻한다. 다른 writer와 파일의 동시 변경을 막지 않는다.

3-B-2B 수집 전에는 서울 CSV 최신 원본 확보 방법, TAGO 전체 범위, GBIS 기반정보와 버전, Provider별 호출량, 수집 메모리와 아카이브 저장 공간을 확인한다. 적용 전에 백업, 위험 변경 승인 필요성, 실패·부분 적용 복구 방법을 확인한다. 실제 최신성·호출량·메모리 사용량은 아직 측정하지 않았다.

### 교통 메타데이터 Snapshot 비적용 Preview

기존 서울 T-Data 노선·노선-정류장·정류장 CSV 세 파일을 로컬에 확보한 뒤 절대 경로로 지정한다. 기존 CSV 포맷과 서비스 범위 판정을 사용하며 네트워크 수집은 하지 않는다. DB가 현재 Migration까지 적용되어 있어야 하며 Preview는 이를 자동 업그레이드하지 않는다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local,metadata-admin --spring.main.web-application-type=none --transit.metadata.admin.enabled=true --metadata.command=preview --provider=SEOUL_BUS --route-master=/absolute/routes.csv --route-stop-master=/absolute/route-stops.csv --stop-master=/absolute/stops.csv --page=0 --size=50'
```

`--page`는 0부터, `--size`는 1~500이다. 위험 상세와 공유 Stop 변형은 각각 출력 행으로 페이지를 구성하며, 충돌 변형은 입력된 Route·occurrence 처리 순서로 표시한다. 모든 페이지를 확인하려면 같은 입력으로 페이지를 바꾸어 재실행하며, 그 사이 DB나 파일이 바뀌면 결과가 달라질 수 있다. 원본 JSON과 승인 가능한 지문은 출력하지 않는다. Raw는 SEOUL_TDATA/TAGO/GBIS와 데이터 종류별로 비교 여부·추가·변경·동일·미포함·버전 변경을 표시하며 미수집 출처의 미포함은 삭제를 뜻하지 않는다.

관리 프로필은 Flyway 자동 실행, Hibernate DDL, SQL 초기화, Scheduler와 다른 one-shot 실행을 차단한다. Preview는 수집 이력·아카이브·분석·위험 상세·승인·정규화·원본·완료 시각을 쓰지 않는다. 결과는 현재 DB 기준이며 마지막 성공 Snapshot과의 일치를 보장하지 않는다. ALLOW도 이후 적용 시 Guard를 생략할 권한이 아니다. TAGO와 GBIS Snapshot은 서비스 입력으로 지원하지만 Preview CLI는 서울 CSV만 지원한다. 별도의 수집 명령은 아래 실행 경계를 따른다. 3-B-2B-2에서는 서울 최신 파일, TAGO 31개 cityCode 수집 범위와 GBIS 기반정보·버전을 확보하고 최신성, 호출량 및 메모리를 검증한다. 검토된 실제 적용은 3-B-2C에서 별도로 결정한다.

### TAGO·GBIS 완료 Bundle의 위험 검토와 승인 적용

명령은 기존 완료 Bundle의 절대 경로와 별도로 준비된 아카이브 디렉터리를 사용한다. `bundle-review`는 원본 아카이브와 DB 이력·분석·위험 상세를 생성하므로 읽기 전용 Preview가 아니다. BLOCK이면 `UNSAFE_CHANGE`로 종료하며 출력된 실행 ID를 기존 `review`로 모든 페이지를 검토한다. ALLOW이면 승인 대상 위험 근거가 없어 `UNEXPECTED_FAILURE`로 종료하며 동기화하지 않는다. 실패 과정에서 이력과 부분 아카이브는 남을 수 있다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local,metadata-admin --spring.main.web-application-type=none --transit.metadata.admin.enabled=true --notification.dispatch.enabled=false --transit.monitoring.enabled=false --metadata.command=bundle-review --tago-bundle=/absolute/tago-bundle --gbis-bundle=/absolute/gbis-bundle --transit.metadata.archive.directory=/absolute/archive --confirm=ANALYZE'
```

기존 `review`와 `approve` 절차로 모든 위험 상세와 두 지문을 검토해 명시적으로 승인한 뒤에만 아래 적용 명령을 별도로 실행한다. 실행 시 같은 Bundle을 재생하고 현재 DB에서 다시 분석해 승인의 두 지문과 수집 시작 시각을 검증한다. 하나라도 다르면 선점과 동기화를 차단한다. 승인 적용은 정규화·원본 데이터를 변경하고 삭제 후보를 정리할 수 있다. 적용 전 백업과 복구 절차 및 모든 직접 writer 중지를 확인하며 부분 commit과 완료 이력 불확실성은 기존 Bootstrap 정책을 따른다. 같은 입력의 미래 Preview와 승인 적용 결과가 동일하다고 가정하지 않는다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local,metadata-admin --spring.main.web-application-type=none --transit.metadata.admin.enabled=true --notification.dispatch.enabled=false --transit.monitoring.enabled=false --metadata.command=bundle-apply --tago-bundle=/absolute/tago-bundle --gbis-bundle=/absolute/gbis-bundle --transit.metadata.archive.directory=/absolute/archive --approval=123 --confirm=APPLY'
```

두 명령은 Provider API를 호출하지 않는다. TAGO와 GBIS 수집 시작 시각을 각각 Raw 수집 시각으로 사용하며 각 Bundle의 수집 시점 차이를 보존한다. 현재 명령은 위험 변경 승인을 필요로 하는 BLOCK 경로를 제공하고 안전한 ALLOW Bundle의 승인 적용은 제공하지 않는다.

### TAGO 로컬 원본 묶음 (Phase 3-B-2B-2B-1A)

`TagoLocalBundle.create(절대_디렉터리, serviceKey)`는 Git 저장소 밖의 이미 존재하는 실제 경로에서 묶음을 만든다. `cityOrder()` 순서대로 cityCode 목록, 도시별 Route 페이지, 각 Route의 정류장 페이지를 `Writer.add(Request, 원본바이트)`에 전달한다. operation은 각각 `getCtyCodeList`(city·route null, page 0), `getRouteNoList`(city·page), `getRouteAcctoThrghSttnList`(city·route·page)다. 모든 응답은 원본 그대로 보존한다. `complete()`가 기존 수집 검증을 끝내야 `manifest.json`이 생기며, 누락이나 실패 시 묶음은 재생할 수 없다. `TagoLocalBundle.replay(묶음_절대경로, serviceKey)`는 파일 무결성과 범위·순서를 재확인하고 네트워크 없이 `TagoSnapshot`을 반환한다. serviceKey는 내용 검사에만 사용하며 Manifest에 기록하지 않는다. 이 단계에서는 실제 Provider 수집을 실행하지 않았다.

파일 권한과 보존은 실행자가 관리한다. 기존 디렉터리 및 그 조상에 심볼릭 링크가 없어야 하고 하드 링크를 지원해야 한다. 해시는 서명이 아니므로 파일과 Manifest에 대한 무단 수정 방지는 파일시스템 접근 통제에 의존한다. 완료되지 않은 묶음과 임시 파일은 자동 삭제하지 않는다.

### GBIS 로컬 원본 묶음 (Phase 3-B-2B-2B-1B)

`GbisLocalBundle.create(절대_디렉터리, serviceKey)`로 Git 밖의 기존 실제 디렉터리에 묶음을 만든다. `Writer.add(Source, 원본바이트)`에 `BASE_INFO`, `ROUTE`, `ROUTE_STATION`을 각각 한 번 전달한다. `complete()`가 세 파일과 기반정보·버전·Bulk·Raw 안전성을 검증해야 Manifest가 게시된다. `GbisLocalBundle.replay(묶음_절대경로, serviceKey)`는 크기와 SHA-256, 모든 원본과 버전을 재검증해 네트워크 없이 Snapshot을 반환한다. 서비스 키는 검사에만 사용하며 Manifest에 URL과 키를 기록하지 않는다. 실제 다운로드 URL의 인코딩된 형태와 확인할 수 없는 query, `signature` query는 거부한다. 파일 권한, 하드 링크, 해시와 미완료 묶음에 관한 위 TAGO 경계가 동일하게 적용된다.

### TAGO·GBIS 오프라인 Snapshot 통합 (Phase 3-B-2B-2B-1C)

`TagoGbisOfflineSnapshotAssembler.tagoOnly(tago경로, serviceKey)`는 명시적으로 TAGO만 재생한다. `withGbis(tago경로, gbis경로, serviceKey)`는 양쪽 묶음을 검증한 뒤 기존 목적지 보강 규칙을 적용하고 TAGO와 GBIS Raw를 포함한 `TagoSnapshot`을 반환한다. 결합을 요청한 GBIS 묶음이 누락·손상되거나 버전이 맞지 않으면 실패하며 TAGO-only로 대체하지 않는다. 반환값은 기존 `TransitMetadataSnapshotPreviewService.preview(snapshot)`에 전달할 수 있다. TAGO-only Preview는 GBIS 미수집을 삭제로 취급하지 않는다. 두 Manifest의 수집 시각은 별개이며 데이터 시점 일치나 최신성을 뜻하지 않는다. 서비스 수준의 격리 MySQL 테스트로 Preview와 DB 행 불변성을 확인했으며 실제 Provider 수집과 개발 DB 비교는 후속 작업이다.

### TAGO·GBIS 독립 수집 명령 (Phase 3-B-2B-2B-2A)

Backend 디렉터리에서 환경변수 `PUBLIC_DATA_SERVICE_KEY`에 키를 주입하고, Git 밖의 기존 실제 절대 디렉터리를 지정한다. 기존 기본 Provider 주소를 사용하며 `TAGO_METADATA_BASE_URL`과 `GBIS_METADATA_BASE_URL`을 지정할 경우 HTTPS 공식 `apis.data.go.kr` 호스트만 허용한다. 기본은 Dry Run이며 키 값이나 Provider URL을 출력하지 않는다. 아래는 요청을 보내지 않는 예다.

```bash
./gradlew --offline collectTransitMetadata --args='--directory=/absolute/path/to/stopbell-collections --max-requests=5000 --interval-ms=1000'
```

실제 Live 실행은 **사용자의 별도 승인 후에만** 수행한다. 명시적 `--live`, `--confirm=COLLECT`, 양의 `--max-requests`가 모두 필요하다. 다음은 형식 예시이며 이번 단계에서 실제 실행하지 않았다.

```bash
./gradlew --offline collectTransitMetadata --args='--directory=/absolute/path/to/stopbell-collections --live --confirm=COLLECT --max-requests=5000 --interval-ms=1000 --max-response-bytes=50000000 --max-total-bytes=500000000 --max-duration-ms=3600000 --connect-timeout-ms=2000 --response-timeout-ms=30000'
```

`--offline`은 Gradle의 의존성 네트워크 접근을 제한할 뿐 Live Provider 요청을 차단하지 않는다. 기본값은 개별 응답 50,000,000바이트, 전체 응답 500,000,000바이트, 전체 3,600,000ms, 간격 100ms, 연결 2,000ms, 응답 30,000ms다. 위 예시의 `--interval-ms=1000`은 기본 간격을 명시적으로 덮어쓴다. 모든 값은 유한한 양수여야 한다. Dry Run의 기본 최대 요청은 1,000회지만 Live에서는 반드시 별도 지정한다. 최소 요청은 GBIS 3회와 TAGO 도시 목록·31개 도시 Route 각 1회로 35회이며 추가 페이지와 Route별 정류장 때문에 전체 횟수는 수집 전 확정할 수 없다. 제한을 높이는 일은 실행자의 요청량·저장 공간 검토가 필요하다.

별도 JVM은 Java 21 JDK HTTP Client의 숨겨진 재시도 제한 속성을 시작 시 설정하고 Redirect를 따르지 않는다. 하나의 Client를 수집 실행 동안 재사용한 뒤 닫는다. 두 속성이 JVM 시작 인수에 없거나 현재 값이 다르면 요청 예산 선점과 네트워크 전송 전에 실패한다. 연결 실패·HTTP 5xx 및 `resultCode=99`와 확인된 세션 부족 메시지가 함께 있는 경우 요청별 최대 2회 재시도를 공유하며 매 시도는 요청·시간 예산과 간격을 따른다. 세션 부족 재시도 대기는 첫 번째 5초, 두 번째 15초다. 인증 오류, 다른 Provider 오류, 잘못된 응답, 무결성·저장 오류는 재시도하지 않는다. `--gbis-bundle=/절대/완료/경로`를 지정하면 기존 GBIS 묶음을 검증·재생하고 TAGO만 새로 수집한다. 각 수집은 새 디렉터리를 사용하고 저장 성공한 원본은 바꾸지 않는다. 실패 시 Provider·단계·오류·요청/수신량·응답 수와 각 경로/완료 상태를 출력하며 예외 원문과 인증정보는 출력하지 않는다. GBIS 완료 후 TAGO가 실패하면 GBIS 묶음은 독립 완료 상태로 남을 수 있지만 전체 수집은 실패다. 완료 Manifest가 없는 부분 묶음은 재생하지 않으며 자동 재개·삭제하지 않는다. 실행자는 파일 접근 권한과 보존을 관리한다. 파일 무결성 해시는 서명이 아니다.

`--directory`는 이미 존재하는 수집 루트다. Dry Run은 수집 폴더나 Manifest를 만들지 않는다. Live는 Provider별 시작 시각을 UTC로 Manifest에 기록하고, 같은 시각을 `Asia/Seoul`로 변환해 아래 경로를 정한다.

- 수집 중 및 게시 실패 결과는 `incomplete/YYYY-MM-DD/<UUID>/`에 남는다.
- 수집과 기존 검증이 끝나면 Manifest를 기록하고 개별 Replay를 확인한 뒤 `YYYY-MM-DD/{TAGO,GBIS}/HHmmss/<UUID>/`에 게시한다. UUID 목적지를 먼저 확보하고 같은 파일시스템에서 디렉터리를 원자적으로 이동한다. 같은 초의 수집도 UUID로 구분된다. 기존 목적지가 있거나 원자적 이동이 불가능하면 실패하고 원본을 유지한다.
- 실패 요약의 Provider별 `Manifest`, `Complete`, `Published` 필드는 각각 Manifest 기록, 개별 Replay 검증, 최종 경로 게시를 표시한다. Manifest가 있는 게시 실패 결과는 보고된 staging 경로에 보존된다. 프로세스가 목적지 확보 직후 중단되면 빈 목적지가 남을 수 있으며, 완료 판정에는 Manifest와 Replay 검증이 필요하다.
- GBIS는 TAGO 시작 전에 독립 게시된다. 통합 Replay가 실패해도 이미 게시된 Bundle은 보존한다. `--gbis-bundle`은 기존 구조와 새 구조의 완료 Bundle을 현재 경로에서 읽고, 재사용한 GBIS에는 이동·복사·수정을 수행하지 않는다. 기존 수집 데이터와 DB 아카이브는 현재 경로에 유지한다. 로컬 수집 루트의 `README.md`는 완료 Bundle 경로와 보존 절차를 안내하며 Git 저장소 밖에 있다.

TAGO에서 성공 HTTP 본문을 받은 뒤 JSON 파싱 또는 응답 구조 검사가 실패하거나 세션 부족 재시도가 소진되면 실패 시각의 KST 날짜 아래 `YYYY-MM-DD/diagnostics/tago-diagnostic-*/`에 `response.bin` 원본과 `request.txt` 요청·실패 정보를 별도로 저장한다. 디렉터리는 `0700`, 파일은 `0600`이며 기존 Bundle에는 포함하지 않는다. 원본은 인증정보를 포함할 수 있으므로 민감한 로컬 파일로 취급하고 실행자가 접근과 보존을 관리한다. CLI는 본문이나 키 대신 요청 식별자와 저장 경로를 출력한다. 현재 HTTP 전송 경계는 정확한 성공 상태 코드를 전달하지 않아 `2xx`로만 표시한다. 저장이 실패하거나 본문을 받지 못하면 그 상태를 표시하고 원래 실패 코드를 유지한다. 클라이언트 재사용이 Provider 세션 부족을 해결한다는 보장은 없다.

이 명령은 웹 서버, Scheduler, FCM, Flyway, Hibernate, DB 연결 및 Bootstrap을 시작하지 않는다. 정상 수집 후 기존 Bundle 및 통합 Snapshot 재생을 검증하지만 DB Preview나 적용은 하지 않는다. 이 단계에서는 Mock Provider와 로컬 HTTP fixture만 사용했으며 실제 Provider·개발/운영 DB는 사용하지 않았다. 실제 GBIS URL·최신성, API quota와 수집 결과 및 DB Preview는 Live 수집 전에 별도로 검토한다.

### GBIS 공식 기반정보와 Live 수집 시도 (Phase 3-B-2B-2B-2B)

GBIS 기반정보의 `routeDownloadUrl`과 `routeStationDownloadUrl`은 실제 다운로드에 사용하므로 공식 호스트·URI·민감 query 검사를 통과해야 한다. 사용하지 않는 네 필드는 URL 형식을 강제하지 않으며 인증정보 안전성 검사를 통과한 원본을 변형 없이 보존한다. 다른 필드는 기존 안전 정책을 따른다.

사용자가 확인한 공식 다운로드 URL의 query에는 파일명이 단독으로 들어간다. 필수 URL은 이 형식과 기존 key=value 형식을 모두 허용하고 인증정보 검사를 유지한다. 제공된 형식의 Fixture로 파싱과 Bundle 재생을 검증했으며 이 수정 후 실제 수집은 실행하지 않았다.

2026-11-09 별도 Quota 조회 없이 사용자 승인에 따라 제한된 Live 수집을 한 번 시도했다. GBIS 기반정보 요청 1회가 HTTP 성공 응답 869바이트를 받았으나 기반정보 검증에서 `INVALID_RESPONSE`로 중단됐다. CLI가 안전하지 않을 수 있는 원본과 상세 예외를 저장·출력하지 않으므로 구조·Provider 오류, 버전 불일치 또는 URL 안전성 충돌 등 구체적인 원인은 확인되지 않았다. 추가 호출·재시도는 수행하지 않았고 GBIS 다운로드와 TAGO 요청은 시작되지 않았다. 미완료 디렉터리는 `/Users/jsw4795/GPT_Codex_Project/stopbell-collections/2026-11-09-live/1601bbe1-4301-44df-969c-66620cb20597`이며 원본과 완료 Manifest가 없다. GBIS·TAGO 완료 Bundle 및 통합 Snapshot은 확보하지 못했다. 실행 시간은 CLI 실패 보고에 기록되지 않았다.

실제 최신성·데이터 품질·수집 완료를 주장하지 않으며 DB에는 접근하거나 적용하지 않았다. 안전한 진단 방법과 별도 Live 실행 승인 후 원인을 확인해야 한다. 실제 데이터 수집과 DB Preview는 미완료다.

### GBIS 기반정보 검증 실패 진단 (Phase 3-B-2B-2B-2B-D1)

기반정보가 기존 검사에서 거부되면 수집 CLI는 `failure=INVALID_RESPONSE validation=...`과 필요한 경우 고정 `field=...`를 출력한다. 코드에는 `JSON_PARSE_ERROR`, `JSON_DUPLICATE_FIELD`, `ENVELOPE_STRUCTURE`, `PROVIDER_RESULT_ERROR`, `REQUIRED_FIELD_MISSING`, `FIELD_TYPE_INVALID`, `VERSION_MISMATCH`, `URL_MISSING`, `URL_INVALID_FORMAT`, `URL_HOST_MISMATCH`, `URL_FORBIDDEN_QUERY`, `URL_ENCODED_OR_USERINFO`, `URL_FORBIDDEN_CONTENT`, `URL_OUTSIDE_OFFICIAL_FIELD`, `CONTENT_UNSAFE`, `UNKNOWN`이 있다. 필드는 응답 구조·버전·공식 URL의 미리 정의된 식별자만 사용한다. 분류가 확실하지 않으면 `UNKNOWN`이며 실제 필드 값, 원본 JSON, URL, 인증정보 및 원본 예외는 출력하거나 저장하지 않는다. HTTP 오류와 저장 오류에는 GBIS 검증 코드를 붙이지 않는다.

이전 869바이트 응답은 저장되지 않아 원인이 여전히 미확정이다. 이후 별도로 승인된 Live 진단에서는 요청·바이트·Bundle 상태와 이 고정 코드를 확인하고, 필요한 설계 판단은 실제 값을 추측하지 않고 진행한다. 이번 단계는 합성 데이터와 로컬 Mock으로 검증했으며 Provider 호출과 DB 접근을 수행하지 않았다. 실제 수집과 DB Preview는 미완료다.

### GBIS URL 형식 진단 세분화 (Phase 3-B-2B-2B-2B-D3)

이후 별도 승인된 기반정보 Live 진단 1회는 HTTP 2xx, 869바이트, `URL_INVALID_FORMAT` / `AREA_DOWNLOAD_URL`로 중단됐고 원본을 남기지 않았다. 이전 869바이트 응답과 동일한 원인인지는 알 수 없다. Route·RouteStation·TAGO 추가 요청과 완료 Manifest는 없었다.

당시 검증이 거부한 URL 형식은 `URL_URI_SYNTAX`, `URL_SCHEME_MISSING`, `URL_SCHEME_UNSUPPORTED`, `URL_HOST_MISSING`, `URL_PORT_PRESENT`, `URL_FRAGMENT_PRESENT`, `URL_PATH_MISSING`, `URL_PATH_NOT_ABSOLUTE`, `URL_QUERY_STRUCTURE`, `URL_QUERY_PARAMETER_FORMAT`, `URL_BACKSLASH`로 세분화했다. 이후 파일명 단독 query를 허용해 query 구조·매개변수 형식 진단은 사용하지 않는다. 기존 호스트 불일치·민감 query·인코딩/인증정보 분류는 유지한다. 중복 조건은 검사 순서상 먼저 발견한 코드만 보고하며 확인할 수 없는 원인은 `UNKNOWN`이다. 응답 값과 URL, query 이름·값은 출력하지 않는다.

당시 Route와 RouteStation 외 네 필드에도 동일한 공식 URL 검증을 적용했다. 이후 사용자 결정에 따라 미사용 필드의 형식 제약을 제거하고 인증정보 안전성 검사는 유지했다.

이번 코드·테스트 단계에는 Provider와 DB에 접근하지 않았다. 별도 승인 후 `--max-requests=1`로 진단할 수 있으나, 현재 CLI는 기반정보가 정상 검증되면 Route 요청 한도 검사 전에 기반정보 원본을 새 Bundle에 저장한다. 따라서 원본 저장까지 금지하는 후속 Live 진단은 현 CLI 그대로 실행할 수 없고 실행 전에 이 충돌을 해결해야 한다. 실제 GBIS·TAGO 수집과 DB Preview는 미완료다.

이후 사용자 승인으로 미사용 필드 형식을 완화하고 전체 수집을 1회 시도했다. GBIS 기반정보 HTTP 요청 1회, 869바이트 수신 후 필수 `ROUTE_DOWNLOAD_URL`에서 `URL_QUERY_STRUCTURE`로 중단됐다. 당시 query 원문을 저장·출력하지 않아 구체적인 형태는 확인하지 못했다. GBIS Route·RouteStation과 TAGO 요청, 완료 Bundle, 통합 Snapshot은 없다. 미완료 경로는 `/Users/jsw4795/GPT_Codex_Project/stopbell-collections/2026-11-09-live/5e808d65-64a2-419e-9909-c58fccfb84f1`이다. 재시도와 DB 접근은 하지 않았다. 이후 사용자가 제공한 공식 URL 형식을 지원하도록 수정했으며 실제 수집은 별도로 검증해야 한다.

이후 Live 수집에서 GBIS Bundle `/Users/jsw4795/GPT_Codex_Project/stopbell-collections/2026-11-09-live/90f6ce9d-c9d7-4a2d-a3ec-238961d8f1e7`은 완료됐으나 TAGO 정류장 수집은 요청 265회에서 `INVALID_RESPONSE`로 중단됐다. 미완료 TAGO Bundle `/Users/jsw4795/GPT_Codex_Project/stopbell-collections/2026-11-09-live/973ee134-9c7d-43d5-acc4-8392277ad28f`의 261개 응답은 보존했다. 복원한 실패 요청을 별도로 1회 확인했을 때 109개 정류장의 정상 응답이 기존 파서와 안전성 검사를 통과했으나 원래 실패 응답은 없어 원인을 확정할 수 없다.

완료 GBIS Bundle 재생과 제한적 재시도를 추가한 뒤 TAGO만 새로 수집했다. 경기 31개 도시 확인 후 TAGO 정류장 단계에서 `INVALID_RESPONSE detail=TAGO_STRUCTURE`, 요청 53회, 560,827바이트로 중단됐으며 응답 원문은 보관하지 않았다. 새 미완료 TAGO Bundle은 `/Users/jsw4795/GPT_Codex_Project/stopbell-collections/2026-11-09-live/db5433d9-9349-467e-8902-1c8aadb53f86`이다. GBIS 재호출과 수집 명령 재실행, DB 접근은 없었다. TAGO 완료 Bundle과 통합 Snapshot은 확보하지 못했다.

### 기존 노선 유형 보강 준비

`backend/scripts/prepare_route_type_backfill.py`는 기존 서울 노선마스터 CSV에서 두 SQL 파일만 생성하며 DB에 접속하지 않는다. GBIS 후보는 SQL 실행 시 `transit_source_metadata`의 버전 `20261109` ROUTE 중 기존에 검증되어 `route_id`가 연결된 행만 사용한다. 서울은 provider·원본 ID·노선명, 경기는 provider·연결된 내부 ID·`GGB` 원본 ID를 확인한다. 두 출처 모두 현재 유형이 NULL이고 알려진 코드인 행만 후보로 삼는다. 중복 Route 연결은 임시 후보 테이블의 기본키 제약으로 실패한다.

```sh
python3 backend/scripts/prepare_route_type_backfill.py \
  '../stopbell-metadata/rout-data/2026-11-09/SEOUL/TBIS_MS_노선 (노선마스터).csv' \
  /private/tmp/stopbell-route-type-plan
```

출력 디렉터리의 파일이 이미 있으면 덮어쓰지 않는다. 먼저 CSV 출처·해시, DB 백업과 복구 방법을 확인하고 모든 metadata writer를 중지한다. MySQL **같은 세션**에서 `route-type-preview.sql`을 실행하면 임시 테이블과 트랜잭션을 열고 Route/GBIS 원본을 잠근 뒤 후보 목록과 유형별 수를 출력한다. 영구 테이블은 쓰지 않는다. 분석 시점의 예상은 서울 705개, 경기 2,104개, 합계 2,809개이며 현재 DB가 달라졌다면 적용하지 않고 차이를 검토한다. 전체 후보 목록과 기존 `route_type`, 원본 버전·연결, `bus_routes`/occurrence/원본/`bus_metadata_sync_states`의 행 수 및 백업을 적용 전 확인한다. 검토하지 않거나 오류가 나면 같은 세션에서 `ROLLBACK`하고 종료한다.

**실제 DB UPDATE에는 별도의 명시적 승인이 필요하다.** 승인 후에도 위 잠금과 동일 트랜잭션이 유지된 경우에만 같은 세션에서 `route-type-update.sql`을 실행한다. 이 파일은 `bus_routes.route_type`만 갱신하며 자동 commit하지 않는다. 갱신 건수와 후보별 값이 사전 검토 결과와 일치하고 mismatch가 0인지, 나머지 열·테이블과 완료 시각이 보존됐는지 확인한 뒤에만 수동 `COMMIT`; 하나라도 다르면 `ROLLBACK`한다. 세션 단절이나 writer 개입 시 처음부터 미리보기와 승인을 다시 진행한다. 이 작업은 전체 동기화, 원본 갱신, 삭제 또는 동기화 상태 갱신을 실행하지 않는다.
