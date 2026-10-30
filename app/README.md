# StopBell Flutter 앱

노선·정류장 선택, 기존 도착·통과 Alarm 관리와 사용자가 고른 차량의 BoardingSession 하차 알림을 제공하는 iOS Client입니다. Google 로그인·Auth Session 복원/갱신, Device 등록과 Push 수신/tap을 Backend API에 연결합니다. 교통 관측과 알림 판단은 Backend가 담당합니다.

## 개발 환경

프로젝트 기준은 Flutter 3.47.1 Stable / Dart 3.13.1입니다. 설치된 버전은 `flutter --version`, iOS toolchain 상태는 `flutter doctor`로 확인합니다. Dart SDK 제약은 [pubspec.yaml](pubspec.yaml), 공통 환경 기준은 [Local Development](../docs/local-development.md#project-requirements)를 따릅니다.

현재 iOS deployment target은 15.0입니다. 기존 실기기 기록은 iPhone 13 Pro Max / iOS 27.0.1이며, 모든 iOS 버전·기기 검증을 뜻하지 않습니다. iOS 개발에는 macOS와 Xcode, 실제 iPhone 실행에는 기존 signing/provisioning이 필요합니다. Android Push 연동·실기기 지원 검증은 완료되지 않았습니다.

## 설정과 실행

저장소의 `app` 디렉터리에서 실행합니다. [config/local.example.json](config/local.example.json)을 참고해 Git 제외 `config/local.json`에 다음 값을 설정합니다.

| 설정 | 의미 |
| --- | --- |
| `API_BASE_URL` | StopBell Backend origin. API 경로를 붙이지 않음 |
| `GOOGLE_IOS_CLIENT_ID` | Google iOS OAuth Client ID |
| `GOOGLE_SERVER_CLIENT_ID` | Backend audience와 동일한 Web/Server OAuth Client ID |

Client Secret·Service Account·Token은 앱 설정에 넣지 않습니다. Firebase iOS 앱 설정과 APNs/signing은 [기존 설정 절차](../docs/local-development.md#firebase-console--apple-developer-설정-순서)를 따르며 Credential 내용은 출력하지 않습니다. 정식 진입점은 `lib/main.dart`이고 `lib/firebase_smoke.dart`는 초기 smoke 전용입니다.

```sh
cd app
flutter pub get
flutter run --dart-define-from-file=config/local.json
```

실제 기기는 `flutter devices`로 확인한 뒤 `-d <실제-iPhone-ID>`를 추가합니다. Simulator에서 Mac Backend에 연결할 때는 `http://localhost:8080`을 쓸 수 있지만, 실제 iPhone에는 Mac의 접근 가능한 주소나 개발 서버 주소가 필요합니다. 기존 임시 HTTPS 터널을 사용하는 경우 주소 변경 뒤 설정을 반영해 다시 빌드합니다. Backend 실행·DB 사전 점검과 터널 절차는 [Local Development](../docs/local-development.md#backend-connection)를 따릅니다.

## 검증

`app`에서 정적 분석과 자동 테스트를 실행합니다.

```sh
flutter analyze
flutter test
```

Mock/Fake 기반 테스트는 실제 Push 표시 검증을 대신하지 않습니다. 권한 최초 요청/거부 전환, APNs/FID·Device 등록/회전·로그아웃, foreground/background/terminated 수신·tap, 재시작 복구와 실제 버스 하차 준비 알림은 실제 iPhone 검증 범위를 따릅니다. 일부 smoke는 완료됐지만 TASK-710/711/908의 최종 E2E는 미완료입니다. 확인된 결과와 미검증 항목은 [실기기 기록](../docs/local-development.md#task-710-실제-iphone-수신-및-tap-검증)에 있습니다.

## 관련 문서

- [프로젝트 소개](../README.md) · [요구사항](../docs/requirements.md)
- [API](../docs/api.md) · [Architecture](../docs/architecture.md) · [Package 책임](../docs/package-structure.md#flutter-application)
- [Local Development](../docs/local-development.md) · [Task List](../docs/task-list.md)
