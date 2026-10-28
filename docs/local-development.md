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
./gradlew bootRun --args='--spring.profiles.active=local --spring.main.web-application-type=none --transit.monitoring.enabled=false --notification.fcm.enabled=true --notification.fcm.smoke=true --notification.fcm.smoke-device-id=10 --notification.fcm.smoke-alarm-id=20'
```

`local` + 명시적 command-line Smoke opt-in + non-web + monitoring disabled 조건에서만 실행한다. Device/FID는 read-only transaction 안에서 메모리로 읽고 같은 owner/IOS/enabled를 확인한 뒤 transaction 종료 후 **한 번만** 전송한다. 완료/실패 뒤 context를 닫으며 자동 startup 전송·공개 테스트 endpoint·worker·scheduler를 추가하지 않는다. Event/Delivery/Alarm/Device row를 INSERT/UPDATE하지 않고 Delivery를 ACCEPTED로 꾸미지 않는다. 일반 startup은 `notification.fcm.enabled=false`이고 설정 없이도 정상 초기화된다.

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

TASK-706 Worker 구조와 집중 Fake Provider/MySQL Testcontainers 검증을 추가했다. `notification.dispatch.enabled=false`가 기본이며 TASK-705 FCM enabled/local Smoke와 독립적이다. TASK-706 당시 handler 부재 gate는 TASK-709에서 production Handler와 FCM 정적 설정 gate로 대체했다. 실제 자동 Firebase/iPhone 검증의 현재 진행 상태는 아래 TASK-710 기록을 따르며, 전체 E2E 및 운영 latency tuning은 미완료다. **TASK-710 검증 전 운영 자동 활성화하지 않는다.** 설정과 구현/후속 recovery 경계는 [Architecture](architecture.md#notification-dispatch-worker-구현-task-706)를 따른다. Codex는 실제 Firebase 전송, local DB dispatch/bootRun, iPhone/Flutter/Xcode 작업을 수행하지 않았다.

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

위 결과는 확인된 수신·화면 이동·DB 기록 범위에 한정하며 전체 수신/tap E2E 완료를 뜻하지 않는다. TASK-710과 최종 E2E/regression 보강 TASK-711은 모두 미완료로 유지한다.

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

### TASK-709 retry/expiry 실행 경계

기본 `notification.dispatch.enabled=false`를 유지한다. 자동 dispatch를 직접 검증할 때만 별도 true 설정과 기존 FCM enabled/project/신뢰하는 credential path/timeout 설정을 함께 제공한다. 정적 설정 누락은 startup을 차단하지만 Backend 기본 실행 및 TASK-705 명시적 one-shot Smoke는 영향을 받지 않는다. 실제 credential 내용은 로그하거나 저장소에 추가하지 않는다.

런타임 인증/프로젝트/APNs 등 CONFIGURATION 결과가 나오면 Worker는 미시작 Delivery를 보존하고 expiry만 처리한다. 설정·인증을 고친 뒤 Backend 재시작으로 차단을 해제한다. 이미 FAILED인 Delivery는 자동 복구하지 않는다. retry/recovery/count/TTL을 운영자가 초기화하거나 오래된 알림의 기한을 연장하는 흐름은 없다. 개발 후보값·안전 상한은 [ADR-010](adr/ADR-010-notification-device-and-durable-delivery.md#task-709-구현-설정과-검증-2026-10-13), transaction/Worker 경계는 [Architecture](architecture.md#notification-dispatch와-result-transaction-task-709)를 따른다.

2026-10-13 Codex 검증은 집중 unit/configuration 및 격리 MySQL Testcontainers·Mock Provider에 한정했다. 실제 FCM/iPhone, Service Account 파일, 개발 DB, Flutter/Xcode 및 전체 Backend suite는 사용하지 않았다. TASK-710/711에서 자동 Event 전송의 foreground/background/terminated 수신·tap, 오래된 notification 처리, 실제 crash/restart와 multi-device 및 재등록 경쟁, latency 분포와 timeout/TTL/retry 후보값 tuning을 검증한다.

## Future Consideration

- Local Development profile의 정확한 이름과 구성
- Database seed data 및 fixture 제공 방식
- API mock 또는 Transit provider sandbox 사용 여부
- Local Push notification credential 관리 방식
