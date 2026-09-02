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
- MyBatis: MyBatis Spring Boot Starter 4.1.0
- MySQL Connector/J Version: 9.7.0
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

실행 전 확인 사항:

- MySQL이 실행 중인지 확인
- 필요한 Environment Variable이 설정되었는지 확인
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
TRANSIT_API_KEY
FCM_SERVICE_CREDENTIAL
OAUTH_CLIENT_SECRET
```

`.env.example` 또는 동등한 placeholder file에는 값이 아닌 필요한 key만 기록한다.

Environment Variable을 Spring Boot에 전달하는 방법은 To be decided이다. Local file, IDE run configuration, shell Environment Variable 중 하나를 선택할 때는 시크릿 노출 위험과 팀 사용성을 함께 검토한다.

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

Flutter project가 생성된 후 Flutter SDK가 설치되어 있고, 필요한 platform toolchain이 준비되었는지 확인한다.

정확한 Flutter 실행 command와 flavor/environment 분리 방식은 To be decided이다.

## Device Connection

실제 Device 또는 emulator/simulator를 연결한다.

Push notification 검증은 실제 Device에서 수행해야 한다. emulator/simulator 지원 범위와 iOS/Android 차이는 Future Consideration이다.

## Backend Connection

Flutter Application은 Local Backend의 HTTPS/HTTP endpoint에 연결한다.

Local Device, emulator/simulator, Backend 실행 위치에 따라 host 주소가 달라질 수 있다. 정확한 Local API base URL 설정 방식은 구현 전에 결정한다.

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
