# Local Development

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

Backend 디렉터리에서 Gradle Wrapper를 사용해 실행한다. 기본 profile은 `local`이다.

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
--transit.metadata.seoul.route-master=file:/path/to/route-master.csv
--transit.metadata.seoul.route-stop-master=file:/path/to/route-stop-master.csv
--transit.metadata.seoul.stop-master=file:/path/to/stop-master.csv
'
```

TAGO metadata bootstrap도 같은 방식으로 provider만 지정해 실행한다.
경기도 full sync는 city/route/API collection 및 DB reconciliation 진행률을 INFO log로 출력한다.

```text
./gradlew bootRun --args='
--spring.main.web-application-type=none
--transit.metadata.bootstrap.provider=TAGO
'
```

실행 전 확인 사항:

- MySQL이 실행 중인지 확인
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
TRANSIT_API_KEY
FCM_SERVICE_CREDENTIAL
OAUTH_CLIENT_SECRET
```

`.env.example` 또는 동등한 placeholder file에는 값이 아닌 필요한 key만 기록한다.

`GOOGLE_SERVER_CLIENT_ID`에는 iOS Client ID가 아니라 Backend Authentication audience 검증에 사용할 Google Cloud Web application(Server) Client ID를 설정한다.

Local profile은 Spring Boot Config Data import로 Backend 디렉터리의 상위 경로(저장소 루트)에 있는 `.env`를 optional properties file로 자동 로드한다. 따라서 일반적인 실행 방식인 `cd backend && ./gradlew bootRun`과 Backend 프로젝트를 working directory로 사용하는 IDE/STS 실행에서 별도 Environment Variable 등록 없이 `.env`의 `KEY=value` 설정을 사용할 수 있다. `.env`가 없는 경우에도 Local profile의 설정 로딩은 실패하지 않는다.

`.env`는 Git에 커밋하지 않으며, `.env.example`에는 필요한 key만 유지한다. 운영·배포 환경에서는 OS Environment Variable 또는 배포 환경의 Secret 관리 기능을 사용한다. Spring Boot 기본 property precedence에 따라 OS Environment Variable과 command-line property는 `.env`에서 import한 Config Data보다 우선한다.

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

## Schema

Database Schema 변경은 Flyway Migration으로 관리한다. Hibernate `ddl-auto`를 통한 자동 Schema 변경은 사용하지 않는다.

Entity 변경만으로 Database Schema를 변경하지 않으며, Schema 변경 시 Migration 파일을 반드시 추가한다.

Migration 파일은 `backend/src/main/resources/db/migration/`에 `V{version}__{description}.sql` 형식으로 둔다. 애플리케이션과 MySQL Testcontainer 통합 테스트가 이를 적용한다.

------------------------------------------------------------------------

# Test Strategy

Alarm 상태 전이 같은 순수 Domain Business Rule은 Spring Context, Database, Docker 없이 JUnit Unit Test로 검증한다.

JPA Entity Mapping과 Repository 동작은 H2 또는 Local Development MySQL이 아닌 MySQL Testcontainers 기반 Integration Test로 검증한다. Testcontainer는 MySQL `8.4.11` image를 사용하고, Spring Boot의 `@ServiceConnection`으로 Test DataSource에 연결한다.

Test Schema는 Hibernate가 자동 생성하지 않는다. Test에서도 `ddl-auto: none`을 유지하며, 빈 MySQL Testcontainer에 Flyway Migration을 적용한다. Integration Test 실행에는 Docker Runtime이 필요하다.

------------------------------------------------------------------------

# Flutter Setup

## Project Run

Flutter SDK와 iOS platform toolchain을 준비하고, `app/config/local.example.json`을 참고해 Git에서 제외되는 `app/config/local.json`을 만든다. 설정에는 `API_BASE_URL`, `GOOGLE_IOS_CLIENT_ID`, `GOOGLE_SERVER_CLIENT_ID`를 넣는다. iOS Client ID는 Google Cloud iOS OAuth Client의 값이며, Server Client ID는 Backend의 `GOOGLE_SERVER_CLIENT_ID`와 동일한 Web/Server OAuth Client 값이다. Client Secret은 Flutter 설정에 넣지 않는다.

Flutter 기본 Dart define 파일로 설정을 주입한다. `app` 디렉터리에서 실행한다.

```text
flutter run --dart-define-from-file=config/local.json
```

## Device Connection

실제 Device 또는 emulator/simulator를 연결한다.

Push notification 검증은 실제 Device에서 수행해야 한다. emulator/simulator 지원 범위와 iOS/Android 차이는 Future Consideration이다.

## Backend Connection

Mac에서 Backend를 실행하는 iPhone Simulator의 Local `API_BASE_URL`은 `http://localhost:8080`이다. 실제 iPhone의 `localhost`는 Mac을 가리키지 않으므로, 실제 기기 검증 시에는 Mac의 접근 가능한 Local 주소 또는 개발 서버 주소를 사용한다.

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

## Future Consideration

- Local Development profile의 정확한 이름과 구성
- Database seed data 및 fixture 제공 방식
- API mock 또는 Transit provider sandbox 사용 여부
- Local Push notification credential 관리 방식
