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
이 실행은 먼저 같은 `PUBLIC_DATA_SERVICE_KEY`로 GBIS 기반정보의 route/routeStation bulk와 version을 검증한 뒤 TAGO traversal을 수집한다. 전체 GBIS bulk 검증에 실패하면 complete sync와 cleanup을 진행하지 않는다. 개별 Route가 TAGO traversal과 일치하지 않으면 해당 Route의 destination만 비워 둔다.

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

## Firebase iOS early smoke (TASK-701)

SDK/target/lifecycle 계약의 Owner는 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#task-701-firebase-ios-기술-계약-2026-10-06)이다. 현재 Firebase config/APNs key 및 실제 iPhone 수신 확인은 준비되지 않았다. TASK-701은 `[ ]`를 유지하며 사용자가 실제 수신 확인 후 별도로 완료 처리한다.

### Firebase Console / Apple Developer 설정 순서

1. 사용할 실제 Firebase project를 선택한다. 기존 Google OAuth project를 Firebase에 연결할 수 있으나 프로젝트를 임의로 선택·생성하거나 OAuth 값을 바꾸지 않는다. 이 작업은 Google OAuth + Backend Auth를 Firebase Auth로 대체하지 않는다. Analytics 등 다른 Firebase 제품은 이번 smoke에 추가하지 않는다.
2. Firebase Console → Project settings → General에서 iOS App을 등록/확인한다. Bundle ID는 정확히 `com.stopbell.stopbell`이다. 해당 앱의 실제 `GoogleService-Info.plist`를 내려받는다.
3. 파일을 `app/ios/Runner/GoogleService-Info.plist`에 둔 뒤 `app/ios/Runner.xcworkspace`를 Xcode로 연다. Add Files to Runner에서 해당 파일을 Runner target에 포함하고 Build Phases → Copy Bundle Resources에 실제 파일이 있는지 확인한다. 파일 이름에 `(2)` 등의 suffix를 붙이지 않는다. 가짜 plist/placeholder 값은 사용하지 않는다. Dart는 native default config로 `Firebase.initializeApp()`를 호출하므로 이번 경로에는 임의의 `firebase_options.dart`가 필요하지 않다.
4. Apple Developer Portal에서 해당 Team의 명시적 App ID `com.stopbell.stopbell`에 Push Notifications가 활성화되어 있는지 확인한다. APNs 사용 가능한 Authentication Key를 준비하고 `.p8`, Key ID, Team ID를 확인한다. Firebase Console → Project settings → Cloud Messaging → 해당 iOS App의 APNs authentication key에 실제 key를 업로드한다. Debug development 환경을 지원하는 key인지 확인한다.
5. Xcode Runner → Signing & Capabilities에서 올바른 Apple Developer Team과 실제 iPhone을 선택하고 Push Notifications, Background Modes의 Background fetch/Remote notifications를 확인한다. 저장소에는 capability 및 `aps-environment`가 구성되어 있지만 Portal App ID와 provisioning profile의 권한까지 대신 설정하지는 않는다. Push entitlement를 포함하는 개발 profile을 갱신하고 실제 signed debug install로 확인한다. Xcode가 unrelated upgrade metadata를 바꾸면 해당 변경은 제외한다.
6. Firebase method swizzling은 기본 enabled 상태를 유지한다. `FirebaseAppDelegateProxyEnabled = NO`를 추가하지 않는다. Firebase SDK는 Flutter의 생성 SPM graph가 공급한다. Xcode Add Packages에서 Firebase를 중복 추가하거나 Podfile/pod install을 도입하지 않는다.
7. Google Cloud Console에서 같은 project의 Firebase Cloud Messaging API (`fcm.googleapis.com`) 활성화를 확인한다. 전송할 계정에 해당 project의 `cloudmessaging.messages.create` 권한이 필요하며 Firebase Cloud Messaging API Admin 역할이 이를 제공한다. Smoke에는 기존 gcloud 로그인 계정을 사용하고 Service Account private key를 생성/저장하지 않는다.

`GoogleService-Info.plist`는 공식적으로 non-secret app/project config다. 기존 저장소에 별도 Firebase config ignore 정책이 없으므로 실제 파일과 Xcode resource reference는 리뷰 가능한 tracked config로 관리한다. 현재 실제 파일이 없어 이번 변경에는 포함하지 않았다. `.p8`, Service Account private key 및 access token은 repository에 넣지 않는다. Firebase 설정을 위해 기존 Google Sign-In URL scheme/client ID를 자동 교체하지 않는다.

### 실제 iPhone 실행과 FID 전송

1. iPhone을 Mac에 연결하고 Trust / Developer Mode / signing 준비를 마친다. `app` 디렉터리에서 `flutter devices`로 실제 iPhone ID를 확인한다.
2. 제품 앱과 분리된 smoke entrypoint를 실행한다. Backend 실행이나 Google 로그인은 필요 없다.

   ```text
   flutter run --debug --no-pub -t lib/firebase_smoke.dart -d <실제-iPhone-ID>
   ```

3. iPhone에서 `Firebase / APNs / FID 준비`를 누르고 알림을 허용한다. Firebase 초기화, APNs 준비와 native FCM `register()`가 성공하면 Firebase project ID와 **FID**가 화면에 표시된다. APNs 원문과 legacy FCM token은 표시/저장하지 않는다. APNs를 10초 이내 얻지 못하거나 FID가 바뀌면 준비 버튼으로 다시 확인한다. 권한을 거부했다면 iPhone 설정에서 smoke 앱의 알림을 허용한 뒤 재시도한다. 이는 최종 제품 permission UX가 아니다.
4. 표시된 FID를 Mac 전송 도구에 입력할 수 있도록 복사한 뒤 iPhone 앱을 background로 보낸다. Notification Center/배너 수신을 직접 볼 수 있게 Focus/알림 표시 설정을 확인한다. Foreground 표시 및 tap lifecycle 전체 검증은 이번 Task에 포함하지 않는다.
5. Google Cloud CLI가 설치된 Mac에서 사용할 계정으로 `gcloud auth login`을 수행한다. 이미 올바른 계정으로 로그인했다면 생략한다. Repository root에서 다음을 실행하고 hidden prompt에 iPhone 화면의 FID를 입력한다. `<실제-Firebase-project-ID>`는 display name/project number가 아닌 화면/config의 project ID다.

   ```text
   python3 tools/fcm_ios_smoke_send.py --project-id <실제-Firebase-project-ID> --target-field fid
   ```

   도구는 한 번만 HTTP v1 `message.fid` 대상으로 alert notification을 전송한다. Target/access token을 command argument나 로그에 남기지 않으며 retry, credential file, production Provider Client를 만들지 않는다. Firebase Console의 legacy registration-token 입력 화면만으로 FID 지원을 추정하지 않고 명시적 `fid` 요청을 사용한다. CLI 설치가 안 된 환경은 Cloud Shell에 이 단일 script를 업로드해 같은 명령을 실행할 수 있다.

   `--target-field`를 생략해도 기본값은 `fid`이며 정식 TASK-701/production 계약은 FID를 유지한다. Legacy registration compatibility 비교가 필요할 때만 같은 registration value로 아래 명령을 별도 실행한다. `token`은 diagnostic-only이며 실행마다 선택한 field 하나로 한 번만 전송한다. Retry나 `fid` → `token` 자동 fallback은 없다.

   ```text
   python3 tools/fcm_ios_smoke_send.py --project-id <실제-Firebase-project-ID> --target-field token
   ```

6. `FCM accepted: projects/.../messages/...`는 Provider 접수만 의미한다. 실제 iPhone에서 `StopBell TASK-701 smoke` 알림을 **한 번 이상 직접 수신 확인**한다. 준비/전송 상태만으로 smoke 성공을 기록하지 않는다.
7. SDK/OS 버전, 수신 확인 시각, background 여부, Provider 접수 여부 및 실제 수신 여부를 결과로 알려준다. 원문 FID/APNs token/credential은 보고나 로그에 붙이지 않는다. 수신 전에는 TASK-701 `[ ]`이며 TASK-702 계약 확정의 hardware gate가 남아 있다.

실패 시 `401/403`은 로그인·IAM·API/project 설정, `404`는 올바른 project의 현재 FCM 등록 FID인지, APNs 미준비는 entitlement/profile/signing/network, 접수 후 미수신은 APNs key 환경·permission·알림 표시 설정을 확인한다. Smoke 도구는 raw error body를 출력하지 않고 알려진 top-level status와 FCM errorCode만 출력한다(예: `FCM HTTP 404: status=NOT_FOUND, fcmErrorCode=UNREGISTERED`). JSON 파싱 실패나 예상 밖의 schema에서는 확인 가능한 항목만 출력하며, 확인할 항목이 없으면 HTTP status만 출력한다.

Smoke 앱은 명시적 FCM 등록을 남기지만 auto-init을 켜지 않으며 StopBell Backend Device에는 등록하지 않는다. 반복하려면 현재 FID를 다시 준비한다. 일반 product entrypoint로 돌아오려면 기존 `flutter run --dart-define-from-file=config/local.json`을 사용한다. 이번 smoke에서 Firebase installation deletion을 logout/정리 수단으로 호출하지 않는다.

공식 setup 근거: [Flutter FCM Apple capability/APNs/swizzling](https://firebase.google.com/docs/cloud-messaging/flutter/get-started), [Firebase Apple app/config setup](https://firebase.google.com/docs/ios/setup), [HTTP v1 fid targeting](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages), [FCM authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

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
