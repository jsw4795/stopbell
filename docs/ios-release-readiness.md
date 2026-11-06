# iOS App Store 제출 준비 (TASK-815)

2026-11-03 기준 코드·정적 점검과 아래 2026-11-06 사용자 실기기 보고를 기록한다. 정식 배포 서명, App Store Connect 등록·업로드 및 심사는 미검증이다. 제출 직전에 Apple 정책과 SDK 요구사항을 다시 확인한다.

## 현재 구현

- iOS Bundle ID는 `com.stopbell.stopbell`, deployment target은 iOS 15.0, 표시 이름은 Stopbell, Flutter 버전은 `0.1.0+1`이다. 실제 등록 식별자·표시 이름·출시 버전과 일치하는지는 사용자 확인이 필요하다.
- Release entitlement는 `aps-environment=production`이고 Debug/Profile은 development다. 세 구성에 Sign in with Apple entitlement `Default`를 추가했다. 실제 Apple Developer App ID capability·provisioning profile, 배포 인증서, Push capability와 APNs/Firebase 연결은 미검증이다. Firebase 설정 파일은 프로젝트에 포함되어 있으며 출시 대상 Firebase 앱과 Bundle ID 일치를 확인한다. Google URL scheme과 iOS/Web client ID도 출시 대상과 일치해야 한다. Client secret이나 서버 credential을 앱에 넣지 않는다.
- `API_BASE_URL`, `GOOGLE_IOS_CLIENT_ID`, `GOOGLE_SERVER_CLIENT_ID`는 빌드 시 `--dart-define`으로 주입한다. Release는 인증 복원 전에 설정을 검증하며 HTTPS가 아니면 시작을 중단한다. 값은 앱 바이너리에서 추출 가능하므로 secret을 넣지 않는다. 실제 운영 HTTPS 주소와 인증서, 외부 접근성은 별도 검증한다.
- Google 로그인과 로그아웃·secure storage 기반 세션 복원, 설치본 정보 및 notification lifecycle이 구현되어 있다. 로그아웃은 계정 삭제가 아니다. 실제 앱 종료·재실행, 기존 설치본·Keychain, 기기 변경, Push 수신 및 tap은 출시 빌드에서 재검증해야 한다.
- Apple 로그인 Backend API와 Flutter 연동은 기본 비활성화 상태로 구현했다. iOS에서 `--dart-define=APPLE_LOGIN_ENABLED=true`일 때 버튼이 보인다. 빌드 설정과 Backend의 `apple.authentication.enabled` 및 실제 설정을 일치시킨 뒤 활성화한다. 서버 nonce 저장 범위와 설정·키 교체, 운영 조건은 [Architecture](architecture.md#apple-인증-기반-task-815-a)를 따른다. 실기기 인증·탈퇴 결과와 미확인 철회 증거는 아래에 기록한다.
- TASK-815-A Flutter 연동은 `sign_in_with_apple` 8.2.0과 Mock 인증·공통 세션·Device/Boarding 집중 테스트, Flutter 전체 테스트 676개, entitlement 정적 검사 및 서명 없는 iOS Release 빌드로 검증했다. 이 자동·정적 검증은 실제 Apple Developer 출시 설정이나 아래 실기기 검증을 대신하지 않는다.
- 개발용 Firebase smoke는 별도 entrypoint이고 Debug 보호가 있다. 일반 entrypoint의 push 진단 출력은 `kDebugMode`로 제한된다. Release 설정에 개발 API 주소를 넣지 않는다.

## Apple 로그인·회원 탈퇴 실기기 검증 (2026-11-06)

사용자가 실제 iPhone에서 직접 수행하고 보고한 결과다. Codex는 실기기 테스트나 DB 조회를 수행하지 않았다.

| 검증 항목 | 결과 |
|---|---|
| 실제 iPhone Apple 인증 | PASS |
| Backend Apple 로그인 | PASS |
| APPLE 사용자 DB 생성 | PASS |
| 동일 계정 로그아웃 후 기존 사용자로 재로그인, 중복 생성 없음 | PASS |
| 앱 완전 종료 후 세션 복원 | PASS |
| 앱 회원 탈퇴 | PASS |
| 사용자 및 관련 데이터 삭제 | PASS |
| Apple Credential 삭제 | PASS |
| 탈퇴 Receipt 완료 기록 보존 | PASS |
| 동일 Apple 계정 재가입 및 새 세션·앱 사용 | PASS |

코드상 Apple 로그인은 사용자를 조회·생성하고 refresh token을 암호화해 저장하며, Flutter는 세션을 저장·복원한다. 탈퇴 경로는 Apple refresh token 철회를 시도하고 결과에 따라 처리한 뒤 사용자·credential 등을 삭제하고 receipt 완료 기록을 보존한다. credential을 사용할 수 없으면 수동 연결 해제가 필요하다. 다만 이번 사용자 보고와 확인된 기존 기록에는 Apple 철회 HTTP 응답 코드나 내부 상태 전이의 직접 증거가 없어 철회 성공을 별도로 확인했다고 기록하지 않는다. 탈퇴 전후 사용자 ID도 확인되지 않았다. 이 결과는 정식 배포 환경·출시 설정 검증을 뜻하지 않는다.

## 출시 차단 및 사용자 결정

1. Sign in with Apple의 App ID capability·provisioning profile과 Backend secret 및 feature gate를 준비하고 정식 배포 환경의 기기·출시 설정과 [Apple Guideline 4.8](https://developer.apple.com/app-store/review/guidelines/) 준수를 검증한다.
2. Backend의 Apple 토큰 철회와 [공개 회원 탈퇴 API](api.md#7-회원-탈퇴-task-815-backend), 영속 상태·재시작 복구를 구현했다. Flutter 탈퇴 UI·receipt 기반 재시작 복구·수동 연결 해제 안내와 로컬 Token/요청 데이터 정리를 구현했다. [Apple 계정 삭제 지침](https://developer.apple.com/help/app-review/guideline-reference/5-1-1-account-deletion/)에 따른 실제 철회 응답 확인과 배포 환경 검증, Apple 계정 연결 해제·상태 변경 서버 간 알림의 실제 삭제 처리·운영 검증 및 Developer 설정, token lifecycle 운영, 인증 키 운영 주입·보관·교체와 암호화 키 관리·백업·복구, 삭제 backlog 감시를 준비해야 한다. 탈퇴 중 네트워크 장애, 철회 응답 불확실성, Backend 재시작, receipt 기반 복구, 키 오류·철회 실패의 실제 환경 검증도 남았다. 기존 자동 테스트 근거는 [Task 검증 기록](task-list.md)을 따른다. 실제 법적 보존 의무와 백업 경계는 미확정이다. TASK-815는 `[ ]`를 유지한다.
3. 실제 수집·전송·보존 데이터와 Firebase/Google 및 기타 SDK를 조사해 개인정보 처리방침과 App Store privacy disclosure를 작성한다. [Apple privacy 안내](https://developer.apple.com/app-store/user-privacy-and-data-use/)와 [SDK privacy manifest/signature 요구](https://developer.apple.com/support/third-party-SDK-requirements/)를 기준으로 최종 archive의 privacy report 및 의존 SDK를 확인한다. 이 문서는 disclosure를 대신하지 않는다.
4. Apple Developer 계정의 Bundle ID, signing, distribution profile, Push capability, APNs credential 및 Firebase/Google OAuth 설정을 사용자가 확인한다. 실제 credential을 저장소·로그에 넣지 않는다. 알림 권한 거부와 설명, 로그인, 재설치·업데이트, foreground/background/terminated 수신 및 tap을 실제 iPhone에서 검증한다.
5. 제출 시점의 [Apple SDK 요구사항](https://developer.apple.com/news/upcoming-requirements/?id=04282026a)을 확인한다. 2026-04-28 이후 업로드는 Xcode 26 이상과 iOS 26 이상 SDK가 필요하며, [Apple 제출 안내](https://developer.apple.com/app-store/submitting/)는 2027년 4월부터 iOS 27 SDK 및 iOS 15 이상 target을 예고한다. 출시 메타데이터, 연령 등급, 심사 계정, Privacy Policy URL, archive 서명·업로드와 심사를 사용자가 완료해야 한다.

프로젝트의 알림·기기 실측 및 운영 배포 잔여 조건은 TASK-710/810/813/908을 따른다. 위 정적 검토는 Apple 심사 통과를 보장하지 않는다.

서버 간 알림 1단계는 기본 OFF로 수신·검증·영속화만 제공한다. 삭제 이벤트와 로그인 간 영속 동시성 경계는 구현했으나 Worker의 실제 삭제 및 운영 검증은 남아 있으며 실제 Apple 이벤트 수신은 검증하지 않았다. 운영 HTTPS 주소와 TLS 1.2 이상을 준비한 후 Apple Developer App ID의 server-to-server notification URL을 설정한다. 임시 Tunnel을 등록하지 않는다.
