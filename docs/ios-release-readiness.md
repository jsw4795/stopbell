# iOS App Store 제출 준비 (TASK-815)

2026-11-02 기준 코드·정적 점검이다. 서명, App Store Connect 등록·업로드, 심사 및 실제 기기 검증은 수행하지 않았다. 제출 직전에 Apple 정책과 SDK 요구사항을 다시 확인한다.

## 현재 구현

- iOS Bundle ID는 `com.stopbell.stopbell`, deployment target은 iOS 15.0, 표시 이름은 Stopbell, Flutter 버전은 `0.1.0+1`이다. 실제 등록 식별자·표시 이름·출시 버전과 일치하는지는 사용자 확인이 필요하다.
- Release entitlement는 `aps-environment=production`이고 Debug/Profile은 development다. 세 구성에 Sign in with Apple entitlement `Default`를 추가했다. 실제 Apple Developer App ID capability·provisioning profile, 배포 인증서, Push capability와 APNs/Firebase 연결은 미검증이다. Firebase 설정 파일은 프로젝트에 포함되어 있으며 출시 대상 Firebase 앱과 Bundle ID 일치를 확인한다. Google URL scheme과 iOS/Web client ID도 출시 대상과 일치해야 한다. Client secret이나 서버 credential을 앱에 넣지 않는다.
- `API_BASE_URL`, `GOOGLE_IOS_CLIENT_ID`, `GOOGLE_SERVER_CLIENT_ID`는 빌드 시 `--dart-define`으로 주입한다. Release는 인증 복원 전에 설정을 검증하며 HTTPS가 아니면 시작을 중단한다. 값은 앱 바이너리에서 추출 가능하므로 secret을 넣지 않는다. 실제 운영 HTTPS 주소와 인증서, 외부 접근성은 별도 검증한다.
- Google 로그인과 로그아웃·secure storage 기반 세션 복원, 설치본 정보 및 notification lifecycle이 구현되어 있다. 로그아웃은 계정 삭제가 아니다. 실제 앱 종료·재실행, 기존 설치본·Keychain, 기기 변경, Push 수신 및 tap은 출시 빌드에서 재검증해야 한다.
- Apple 로그인 Backend API와 Flutter 연동은 기본 비활성화 상태로 구현했다. iOS에서 `--dart-define=APPLE_LOGIN_ENABLED=true`일 때 버튼이 보인다. 빌드 설정과 Backend의 `apple.authentication.enabled` 및 실제 설정을 일치시킨 뒤 활성화한다. 서버 nonce 저장 범위와 설정·키 교체, 운영 조건은 [Architecture](architecture.md#apple-인증-기반-task-815-a)를 따른다. 실제 Apple 인증과 실기기 검증, 회원 탈퇴와 Apple token revocation은 미수행이다.
- TASK-815-A Flutter 연동은 `sign_in_with_apple` 8.2.0과 Mock 인증·공통 세션·Device/Boarding 집중 테스트, entitlement 정적 검사 및 서명 없는 iOS Release 빌드로 검증했다. 이는 실제 Apple Developer 설정이나 실기기 인증 검증을 대신하지 않는다.
- 개발용 Firebase smoke는 별도 entrypoint이고 Debug 보호가 있다. 일반 entrypoint의 push 진단 출력은 `kDebugMode`로 제한된다. Release 설정에 개발 API 주소를 넣지 않는다.

## 출시 차단 및 사용자 결정

1. Sign in with Apple의 App ID capability·provisioning profile과 Backend secret 및 feature gate를 준비하고 실제 기기·출시 설정과 [Apple Guideline 4.8](https://developer.apple.com/app-store/review/guidelines/) 준수를 검증한다.
2. 앱에서 계정 삭제를 시작하는 기능은 없다. [Apple 계정 삭제 지침](https://developer.apple.com/help/app-review/guideline-reference/5-1-1-account-deletion/)을 충족하도록 계정과 관련 데이터 삭제 및 Apple token revocation을 구현·검증하고 실제 법적 보존 의무와 백업 경계를 확인해야 한다.
3. 실제 수집·전송·보존 데이터와 Firebase/Google 및 기타 SDK를 조사해 개인정보 처리방침과 App Store privacy disclosure를 작성한다. [Apple privacy 안내](https://developer.apple.com/app-store/user-privacy-and-data-use/)와 [SDK privacy manifest/signature 요구](https://developer.apple.com/support/third-party-SDK-requirements/)를 기준으로 최종 archive의 privacy report 및 의존 SDK를 확인한다. 이 문서는 disclosure를 대신하지 않는다.
4. Apple Developer 계정의 Bundle ID, signing, distribution profile, Push capability, APNs credential 및 Firebase/Google OAuth 설정을 사용자가 확인한다. 실제 credential을 저장소·로그에 넣지 않는다. 알림 권한 거부와 설명, 로그인, 재설치·업데이트, foreground/background/terminated 수신 및 tap을 실제 iPhone에서 검증한다.
5. 제출 시점의 [Apple SDK 요구사항](https://developer.apple.com/news/upcoming-requirements/?id=04282026a)을 확인한다. 2026-04-28 이후 업로드는 Xcode 26 이상과 iOS 26 이상 SDK가 필요하며, [Apple 제출 안내](https://developer.apple.com/app-store/submitting/)는 2027년 4월부터 iOS 27 SDK 및 iOS 15 이상 target을 예고한다. 출시 메타데이터, 연령 등급, 심사 계정, Privacy Policy URL, archive 서명·업로드와 심사를 사용자가 완료해야 한다.

프로젝트의 알림·기기 실측 및 운영 배포 잔여 조건은 TASK-710/810/813/908을 따른다. 위 정적 검토는 Apple 심사 통과를 보장하지 않는다.
