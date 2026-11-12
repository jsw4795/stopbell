# 개발 가이드라인

## 1. 목적

이 규칙은 StopBell의 사람 개발과 AI 보조 개발 모두에 적용한다.

## 2. Codex 핵심 규칙

중요한 기능을 구현하기 전:

1. 관련 코드와 테스트를 먼저 확인하고 필요한 `docs/` 계약 섹션을 읽는다.
2. 이미 문서화되지 않은 가정을 밝힌다.
3. 설계 결정에 의미 있는 대안이 있다면 코딩 전에 대안과 트레이드오프를 제시한다.
4. 프레임워크, 인프라, 아키텍처 패턴을 알리지 않고 도입하지 않는다.
5. 현재 버전의 범위를 기억한다.

## 3. 문서 책임

사용자 보장 동작은 `requirements.md`, runtime/component/transaction boundary는 `architecture.md`, Domain 의미와 invariant는 `domain-model.md`, Schema/constraint/index는 `database.md`, HTTP contract는 `api.md`, Task 상태·dependency·acceptance criteria는 `task-list.md`, phase 목표와 release gate는 `roadmap.md`, 결정·대안·근거는 ADR이 상세 Source가 된다. 같은 계약의 상세 규칙을 여러 문서에 복제하지 않고, 다른 문서는 필요한 수준으로 해당 owner 문서를 참조한다.

AI 채팅 인수인계의 진입점과 유지관리 시점은 [CHAT_HANDOFF](CHAT_HANDOFF.md)를 따른다. 이 문서는 책임 문서를 안내하며 설계 계약을 소유하지 않는다.

### 문서 조회 기준

전체 문서 읽기를 기본값으로 삼지 않는다. 관련 코드와 테스트를 먼저 확인하고, 문서가 필요하면 제목·키워드 검색으로 해당 섹션을 읽는다. 같은 채팅에서 이미 읽었고 변경되지 않은 문서는 반복해서 읽지 않으며, 긴 문서의 전문을 응답이나 터미널에 출력하지 않는다. 검색 결과가 충분하면 추가 열람을 중단하고, 확정된 계약이 불명확하거나 문서와 코드가 충돌하면 조회 범위를 넓힌다. 파일 전체 확인에 실질적인 이유가 있으면 예외로 한다.

| 작업 | 기본 조회 범위 |
| --- | --- |
| 단순 UI 수정 | 대상 Flutter 코드와 관련 테스트 |
| 일반 기능 수정 | 해당 기능 코드와 필요한 계약 섹션 |
| CI 실패 수정 | 실패 로그와 관련 코드·테스트 |
| 새 UI 단계 | CHAT_HANDOFF, roadmap 해당 단계와 관련 코드 |
| API 변경 | 해당 API 코드와 api.md 관련 섹션 |
| DB 변경 | 관련 DB 코드·Migration과 database.md 해당 섹션 |
| 인증·추적 상태 머신 변경 | 관련 코드와 requirements/ADR 해당 계약 |
| 과거 Task 후속 작업 | task-list.md의 해당 Task 부분 |
| 새 Codex 채팅 | CHAT_HANDOFF의 현재 상태와 작업 관련 파일 |

이는 최소 시작점이며 근거가 부족하면 관련 문서를 추가 확인한다. `task-list.md`, `architecture.md`, `local-development.md`, `database.md`, `api.md`는 명시적인 근거 없이 작업 시작 시 전체를 읽지 않는다. 특히 완료 이력이 많은 `task-list.md`는 직접 관련된 Task만 조회한다. 이는 문서나 이력 삭제를 뜻하지 않는다. 조회 최소화는 검증 생략이나 설계 계약 무시를 뜻하지 않으며, 새로운 설계나 위험 변경에서는 비용보다 정확성과 안전을 우선한다.

### 모델과 추론 비용 관리

기본 작업은 Terra + Medium을 권장하고 단순 UI·문서 수정은 선택 가능하면 Terra + Low를 사용할 수 있다. 복잡한 DB·동시성·보안 변경은 필요에 따라 더 높은 모델 또는 추론 강도를 사용한다. Sol + High는 일반 작업의 기본값으로 사용하지 않는다. 과도한 사전 탐색과 불필요한 전체 재분석을 피하고 필요한 상황에서만 조회·분석 범위를 넓힌다.

### 문서 갱신 시점

- 일반 Task 완료 시 관련 설계 문서를 확인해 변경된 내용을 동기화하고, [Task 완료 절차](task-list.md#task-completion-workflow)에 따라 `task-list.md`를 갱신한다. 이미 정확한 문서는 수정하지 않는다.
- 주요 Phase 완료 시 `roadmap.md`와 `CHAT_HANDOFF.md`를 함께 갱신한다. 현재 또는 다음 Phase가 바뀌면 `CHAT_HANDOFF.md`의 진행 상황도 갱신한다.
- 중요한 설계 결정이 변경되면 해당 ADR과 관련 책임 문서를 갱신한다. Flutter UI 리뉴얼 계획이 바뀌면 `roadmap.md`를 갱신하고 필요하면 `CHAT_HANDOFF.md`에도 반영한다.
- 단순 버그 수정은 관련 문서의 정보가 실제로 바뀐 경우에만 해당 문서를 갱신한다. ChatGPT 채팅방 이동만으로 문서를 갱신할 필요는 없다.

`roadmap.md`는 단계별 진행 상황을, `task-list.md`는 작업 이력과 완료 근거를 보존한다. 기능 계약의 원본 문서 책임을 유지하고 관련 없는 문서는 매번 갱신하지 않는다. 단순 UI 변경은 기능·계약에 실제 영향이 없으면 여러 문서를 일괄 갱신하지 않는다. 변경된 사실과 실제 검증 결과만 반영한다.

필요한 문서 동기화는 별도 후속 작업으로 미루지 않고 해당 구현 작업의 로컬 Commit에 함께 포함한다. Task 완료 표시는 구현과 필요한 검증이 성공한 경우에만 같은 Commit에 포함한다. `CHAT_HANDOFF.md`는 매 Task마다 수정하지 않으며 구체적인 갱신 조건은 해당 문서를 따른다.

## 위험도 기반 검증과 개발 우선순위

검증은 실제 위험도에 비례해 수행한다. 안정성은 검증 코드의 양이 아니라 실제 요구사항 충족과 중요한 위험의 통제로 판단한다.

- **높은 위험:** 인증·인가, 개인정보·인증정보, DB 삭제·갱신과 트랜잭션 무결성, 중복 부작용, 실제 다운로드·실행 URL, 데이터 손실이나 복구 불가능한 변경은 엄격히 검증한다. 위험한 DB 변경의 Guard와 사용자 승인 없이 위험한 작업을 실행하지 않는 원칙도 유지한다.
- **중간 위험:** 외부 API 필수 응답 필드, 정상적인 페이지네이션, 실제 사용하는 식별자, 필수 데이터 누락과 기능에 영향을 주는 응답 형식은 필요한 만큼 검증한다.
- **낮은 위험:** 미사용 부가 필드, 선택적 메타데이터, 기능에 직접 영향을 주지 않는 값, 단순 UI 표시 정보와 일반 텍스트·부가 속성은 최소한으로 검증한다. 이 경우에도 인증정보 노출이나 명백히 위험한 입력을 허용하지 않는다.

실제 요구사항에 없는 검증을 임의로 추가하지 않고, 정상적인 Provider의 선택적 데이터 때문에 전체 기능이 중단되지 않도록 한다. 미사용 데이터에 사용 데이터와 동일한 형식 제약을 일괄 적용하지 않으며, 새 방어 코드가 기존 정상 동작을 차단할 가능성도 평가한다. 발생 가능성이 낮은 극단적 상황만을 위한 불필요한 계층·클래스·검증 로직은 피한다.

문제 발생 시 진단 기능을 더하기 전에 기존 검증이 과도한지 확인하고, 원인이 충분히 확인되면 진단을 반복하지 않고 직접 수정한다. 작은 문제를 불필요하게 여러 Phase로 나누지 않는다. 테스트는 핵심 기능과 실제 회귀 위험에 집중하며, 보안·데이터 무결성과 직접 관련 없는 변경은 구현 완료와 실제 동작 확인을 우선한다. 필요한 회귀 테스트와 기존 기능 보호는 유지한다.

GBIS 기반정보의 실제 다운로드 대상은 `routeDownloadUrl`과 `routeStationDownloadUrl`이다. 이 두 URL은 공식 호스트와 인증정보 등 안전성을 검증하고, 사용하지 않는 네 필드는 URL 형식을 강제하지 않되 인증정보 노출을 검사한다. 공식 다운로드의 파일명 단독 query와 기존 key=value query를 모두 지원한다.

## 4. 백엔드 가이드라인

기본 스택:

- Java 21
- Spring Boot 4.1.1
- Spring Framework 7.0.9
- Gradle Wrapper 8.14.3
- Spring Data JPA / Hibernate ORM 7.4.5.Final
- MySQL Connector/J 9.7.0
- MySQL 8.4 LTS

### Java and Gradle Environment Verification

StopBell Backend 개발 환경에서는 Java Version과 Gradle JVM Version이 동일해야 한다.

Required Environment:

- Java 21
- Gradle Wrapper 8.14.3

확인 방법:

Java Version 확인:

```text
java -version
```

Gradle JVM 확인:

```text
./gradlew -version
```

두 결과가 모두 Java 21을 사용하는지 확인한다.

#### JAVA_HOME 관련 규칙

macOS 환경에서는 `JAVA_HOME` 설정으로 인해 Gradle 실행 Java Version이 달라질 수 있다.

예를 들어 `java -version`은 Java 21을 표시하지만, `./gradlew -version`은 Java 17을 표시하는 Version 불일치가 발생할 수 있다.

이 경우 코드 문제로 판단하기 전에 Java 환경을 먼저 확인한다.

잘못된 `JAVA_HOME`이 설정된 경우 다음 명령으로 현재 Shell의 `JAVA_HOME` 영향을 제거하고 Gradle Wrapper를 실행할 수 있다.

```text
env -u JAVA_HOME ./gradlew test
```

또는 `JAVA_HOME`을 Java 21 경로로 수정한다.

추가 규칙:

- Backend 개발 및 Test 실행 전 Java Version을 확인한다.
- Gradle 실행 시 Gradle Wrapper를 사용한다.
- 시스템 Java Version과 Gradle JVM Version 불일치를 방지한다.
- Java Version 관련 오류 발생 시 코드 수정 전에 개발 환경을 먼저 확인한다.

### 계층화

다음과 같은 일반적 구조를 사용할 수 있다.

```text
Controller
  ↓
Service
  └── Repository (JPA)
  ↓
Database
```

단, 패턴을 따르기 위해 의미 없는 계층이나 클래스를 만들지 않는다.

### JPA

- 단순한 Domain CRUD와 Entity 상태 관리는 JPA Repository를 사용한다.
- `User`, `Alarm`, Bus metadata와 Device/Notification operational persistence는 JPA 기반으로 관리한다.
- Entity 관계와 상태 전이는 Domain 규칙에 맞게 명확히 유지한다.
- 복잡한 조회를 위해 불필요하게 JPA Query를 복잡하게 만들지 않는다.

### SQL

- SQL은 읽기 쉽고 명시적이어야 한다.
- Transit 관련 Query, 복잡한 검색, 집계 Query, 성능 최적화가 필요한 조회에서 명시적 SQL 제어의 실제 필요가 확인되면 MyBatis 도입을 별도로 결정한다. 현재 V1은 JPA를 사용하며 MyBatis starter를 선행 유지하지 않는다.
- N+1 쿼리 패턴을 피한다.
- 의도가 명확하지 않은 복잡한 쿼리는 설명한다.
- SQL 동작은 테스트할 수 있어야 한다.

### Database Development

- Development 환경에서는 Docker Compose로 실행하는 MySQL 8.4 LTS를 사용한다.
- Container 내부에만 Database 데이터를 저장하지 않는다.
- Database 데이터는 Docker Named Volume 기반 Persistence를 사용한다.
- Container 재생성 시에도 Database 데이터가 유지되는지 확인한다.
- Volume 삭제는 명시적인 데이터 삭제 작업으로 취급한다.
- Database Schema 변경은 Flyway Migration으로 관리한다.
- Hibernate `ddl-auto`를 통한 자동 Schema 변경은 사용하지 않는다.
- Entity 변경만으로 Database Schema를 변경하지 않으며, Schema 변경 시 Migration 파일을 반드시 추가한다.

### 외부 API

- 응답 검증에는 위 위험도 기반 원칙을 적용한다.
- 제공자별 클라이언트는 StopBell 소유 인터페이스/서비스 경계 뒤에 둔다.
- 타임아웃을 정의한다.
- 성공하지 않은 응답을 처리한다.
- API 시크릿을 로그에 남기지 않는다.
- 제공자 지연/실패를 디버깅할 충분한 메타데이터를 보존한다.

### 운영 관측성과 시간

- structured log에는 필요한 correlation과 outcome/elapsed time만 기록하고, 모든 identifier를 모든 log에 넣지 않는다.
- Access/Refresh Token, Google ID Token, Firebase credential, 원문 push targeting identifier·installationId, API key가 포함된 URL/query, 필요 이상의 GPS, raw Provider response 전체를 기록하지 않는다.
- metric label에는 `alarmId`, `deviceId`, `routeId`, `trackingCycleId`, `installationId`처럼 high-cardinality identifier를 사용하지 않는다.
- persisted operational time은 UTC 의미를 유지하고, Provider local time은 timezone을 명시적으로 해석한다. business time 계산은 test 가능한 `Clock` 또는 동등한 source를 우선한다. TASK-814 이전 구현도 이 원칙을 즉시 적용하며 host-local `now()`를 새로 추가하지 않는다.

### Authentication

- Application API는 Spring Security가 검증한 인증된 StopBell User를 기준으로 처리하며, Controller에서 JWT를 직접 parsing하거나 Client 제공 `userId`를 신뢰하지 않는다.
- Access Token과 Refresh Token 원문, Google ID Token, Authorization Header는 로그에 남기지 않는다.
- Refresh Token 원문은 Database에 저장하지 않고 SHA-256 Hash만 저장한다. Password용 BCryptPasswordEncoder를 Refresh Token Hash에 사용하지 않는다.
- Flutter는 Access Token과 Refresh Token을 OS Secure Storage에 저장한다. 일반 Local Storage 또는 SharedPreferences에는 원문 Token을 저장하지 않는다.
- 마지막 Social Login Provider는 인증 정보가 아닌 Client UX 정보이므로 일반 Local Storage에 저장할 수 있다.

### 스케줄러

- 스케줄러 코드는 작업을 조율하고 모든 비즈니스 로직을 담지 않는다.
- 중복 부작용을 낼 수 있는 실행이 겹치지 않게 한다.
- 폴링 주기는 제공자 제한과 실제 제품 요구를 반영해야 한다.
- graceful shutdown 때는 새 cycle을 시작하지 않고 진행 중 transaction을 정상 commit/rollback하며, PENDING Delivery는 restart 뒤 single worker가 다시 처리할 수 있어야 한다. FCM accepted 뒤 DB update 전 crash의 duplicate 가능성은 exactly-once 비보장 계약으로 허용한다.

## 4. Flutter 가이드라인

V1에서 앱은 비교적 얇은 클라이언트로 유지한다.

책임:

- 사용자 상호작용
- API 요청
- 로컬 화면 상태
- Push permission과 provider targeting identifier 획득/갱신
- 푸시 알림 처리

특별한 이유 없이 교통 모니터링 비즈니스 로직을 모바일 앱으로 옮기지 않는다.

### Flutter 공통 디자인 시스템

제품과 시작 설정 오류의 `MaterialApp`은 `StopBellTheme.light`와 `StopBellTheme.dark`를 `ThemeMode.system`으로 적용한다. 화면 바탕과 AppBar의 상태 표시줄 아이콘도 밝기에 맞춘다.

| 파일 | 책임 |
| --- | --- |
| `app/lib/core/theme/stopbell_theme.dart` | `ThemeData`, Material `ColorScheme`, `TextTheme`, 컴포넌트 및 시스템 UI 스타일 |
| `app/lib/core/theme/stopbell_colors.dart` | 성공·경고와 교통 전용 `ThemeExtension`. 화면에서 `StopBellColors.of(context)`로 참조 |
| `app/lib/core/theme/stopbell_spacing.dart` | 공통 간격, 터치 크기, 모서리 반경 |

`ColorScheme.primary`와 `onPrimary`는 주요 액션, `secondary`는 보조 액션에 사용한다. 페이지 바탕은 `scaffoldBackgroundColor`, 카드·필드는 `surface`, 기본 글자와 보조 글자는 `onSurface`와 `onSurfaceVariant`를 사용한다. `outline`은 입력 경계와 중립 표시, `outlineVariant`는 장식용 구분선에 사용한다. 오류와 삭제·탈퇴 같은 위험 액션은 `error`와 `onError`를 사용한다.

승차 정류장은 `boardingStop`(녹색), 목표 정류장은 `targetStop`(파랑), 미정차는 `nonstopStop`(회색)을 사용한다. 추적 중과 추적 보류는 `activeTracking`과 `heldTracking`, 차량 위치 표시는 향후 `busLocation`을 사용한다. 정류장 마커와 상태 표시는 색과 함께 라벨·아이콘·Semantics로 구분한다. 노선 유형 데이터가 확정되기 전에는 버스번호로 색상을 추정하지 않는다. 향후 유형색은 정류장과 추적의 의미색과 분리한다.

| 텍스트 역할 | `TextTheme` | 크기 / 굵기 / 줄 높이 |
| --- | --- | --- |
| 화면 제목 | `headlineLarge` | 28 / 700 / 34 |
| 섹션 제목 | `titleLarge` | 20 / 600 / 26 |
| 노선번호 등 주요 정보 | `headlineSmall` | 24 / 700 / 30 |
| 정류장 이름·상단 바 제목 | `titleMedium` | 17 / 600 / 23 |
| 본문 | `bodyMedium`, `bodyLarge` | 16 / 400 / 22 |
| 보조 정보 | `bodySmall` | 14 / 400 / 20 |
| 상태 정보 | `labelSmall` | 13 / 600 / 18 |
| 캡션 | `labelMedium` | 12 / 400 / 16 |
| 버튼 | `labelLarge` | 16 / 600 / 22 |

iOS의 플랫폼 기본 폰트와 시스템 글자 배율을 유지한다. 화면 여백은 16, 간격은 4·8·12·16·24를 사용한다. 버튼과 필드 반경은 12, 카드는 14, 다이얼로그는 16이다. 작은 화면, 키보드, 큰 글씨에서 콘텐츠가 줄바꿈되거나 스크롤될 수 있게 구성한다.

주요 액션은 `FilledButton`(최소 높이 50), 보조는 `OutlinedButton`(46), 링크는 `TextButton`(44)을 사용한다. `IconButton`에는 최소 44×44 터치 영역과 접근성 이름을 유지한다. `TextField`는 최소 높이 48과 공통 Surface·포커스·오류·비활성 테마를 상속한다. 카드·리스트·선택 컨트롤·다이얼로그·SnackBar·Divider·진행 표시도 기존 Material 위젯의 테마를 우선 사용한다. 로그인 브랜드 위젯은 제공자의 버튼 규격을 유지한다.

화면별 리디자인에서 색·간격·텍스트 스타일을 추가하기 전에 기존 토큰을 확인한다. 작은 글자는 배경 대비 4.5:1 이상, 의미 있는 그래픽은 3:1 이상을 검증 기준으로 삼는다. 선택과 비활성 표현은 기존 이벤트와 상태 판정에서 파생한다.

### UI 문구 규칙

- 사용자가 만들고 켜고 끄는 기능은 "알람"(도착 알람, 하차 알람), 기기로 오는 푸시와 권한·시점은 "알림"으로 쓴다.
- 문구는 "~어요"체로 통일하고, "알려드려요"처럼 붙여 쓴다.
- 정류장 단위는 "정류장"으로 통일하고(정거장·지점 금지), 승차/목표 라벨은 "승차 정류장"/"목표 정류장" 한 형태를 쓴다. 공간이 부족한 좁은 카드만 예외다.
- 오류 문구는 "무슨 일 + 해결 방법"으로 쓰고, 반복되는 문구는 상수로 모은다. 개발 용어(관측, receipt 등)는 노출하지 않는다.

## 5. 테스트

사용자에게 실패를 일으킬 수 있는 동작을 우선 테스트한다.

- 알림 조건 평가
- 중복 알림 방지
- 알림 상태 전이
- 외부 제공자 응답 매핑
- API 검증/오류 처리

### 테스트 실행 범위

Backend와 Flutter는 변경한 클래스·기능의 직접 관련 테스트를 먼저 선택 실행하고, 영향을 받을 수 있는 인접 모듈의 회귀 테스트만 추가한다. 전체 테스트 실행(`./gradlew test`, `flutter test` 등)은 기본적으로 수행하지 않는다. 동시성·정합성 등 현재 변경의 핵심 검증은 생략하지 않는다.

실패하면 관련 테스트만 재실행하며 전체 테스트로 확대하지 않는다. 변경이나 새 검증 사유 없이 이미 통과한 테스트를 반복하지 않고, compile·정적 분석도 필요한 범위에서만 수행한다. 단순 문서 수정이나 테스트에 영향을 주지 않는 변경은 테스트를 생략할 수 있다.

공통 기반 변경으로 영향 범위를 한정할 수 없거나 핵심 Domain 계약 변경으로 광범위한 회귀 위험이 있거나 집중 테스트로 중요한 정합성을 검증할 수 없는 경우에만 전체 실행 예외를 검토한다. 이때도 관련 테스트 추가 선택을 우선하고, 전체 실행이 필요하면 실행 전에 이유와 예상 검증 범위를 개발자에게 보고한다.

완료 보고에는 실행한 테스트 대상·개수·성공/실패 결과와 실행하지 않은 검증 범위를 명시한다. 전체 테스트를 실행하지 않았다면 그 사실도 명시한다. 이 정책은 TASK-816/AUD-04부터 이후 모든 StopBell Task에 적용한다.

프레임워크 동작만 반복 검증하는 테스트는 피한다.

### Backend 테스트 그룹과 DB 격리

- `./gradlew test`와 `./gradlew build`는 과거 Migration·실패·백업 복원을 포함한 전체 검증이다.
- `./gradlew fastTest`는 DB 비의존 테스트, `./gradlew dbTest`는 현재 스키마 DB 회귀, `./gradlew ciTest`는 둘을 합친 일반 CI 검증이다. 이 세 경로는 `migration` 태그를 제외한다.
- `./gradlew migrationTest`는 과거 스키마·업그레이드·실패 이력·복원 검증만 실행한다. 각 task에 `--tests '*클래스명'`을 추가해 변경 기능을 선택한다. 필터가 일치하지 않으면 실패하며 Skip으로 대체하지 않는다.

DB 테스트는 `db`, 독립 DB가 필요한 일반 테스트는 `isolated-db`, 과거 Migration/복원은 `migration` 태그를 명시한다. Migration과 현재 기능 검증이 섞인 클래스는 해당 메서드만 `migration`으로 지정한다. CI의 제외 사례 수 집계를 위해 `@Tag("migration")`을 literal로 사용하고, 새로운 파라미터 형식은 `tools/backend_test_ci.py`의 집계 지원을 함께 검증한다. 지원하지 않는 형식이나 변경 감지 실패는 전체 검증으로 전환한다.

`LatestSchemaIntegrationTest`는 호환되는 설정·Bean 상태를 사용하는 테스트만 상속한다. 동일 최신 DB와 Context 캐시를 사용하고 Flyway 최신 적용·validation 및 Hibernate mapping 검증을 유지한다. 클래스 경계에서는 FK를 활성화한 `reset-latest-schema.sql`을 별도 트랜잭션으로 실행한다. 일반적인 메서드는 기존 rollback을 유지하며 commit하는 메서드는 전후 정리가 추가로 필요하다. AUTO_INCREMENT를 초기화하지 않고 특정 ID나 순서에 의존하지 않는다. Migration으로 테이블을 추가·삭제하면 정리 SQL도 맞춰야 하며 실제 테이블 목록과 정리 대상 일치를 setup에서 검사한다.

DDL·실패 주입·비동기 경쟁·프로세스 재시작, 다른 설정/Mock 조합을 사용하는 테스트는 격리 필요성을 먼저 검토한다. 공유를 위해 이 검증을 약화하거나 전역 rollback으로 바꾸지 않는다. 공유 DB 실행은 하나의 worker에서 순차 수행하며 Cached Context의 pool을 닫은 뒤 공유 MySQL을 종료한다. 공유 대상을 늘리면 해당 클래스들의 반복·무작위 클래스/메서드 순서를 검증한다.

일반 Push/PR CI는 안전한 변경 감지 후 `assemble ciTest`를 사용한다. DB·설정·매핑·테스트 인프라 변경, 판단 불가 또는 수동 실행은 `build`로 전체 검증한다. 실행 및 의도적 Migration 제외 개수와 실패·Skip은 GitHub Step Summary에 보고한다. 배포 전에는 전체 `build`를 실행한다. 구체적인 변경 감지 범위와 실행 명령은 [Local Development](local-development.md#task-809-ci-검증), 전후 측정은 [분석 보고서](research/backend-test-performance-analysis.md)를 따른다.

### Flyway Migration 추가 시 필수 회귀 검증

Flyway 최신 버전과 최신까지의 적용 개수는 테스트 코드에 숫자로 하드코딩하지 않는다. `FlywayTestAssertions`로 Flyway가 인식한 Migration과 실제 DB 적용 상태, validation 및 pending을 검증한다. 특정 과거 버전 업그레이드와 그 단계의 데이터·제약 검증에 필요한 고정 버전과 개수는 유지한다. 기존 Migration 파일은 수정하지 않는다.

새 Migration 테스트와 직접 영향받는 기존 테스트를 선택 실행하고 결과를 보고한다. 기존 `test`에서 실행되는 소스 검사는 직접적인 Flyway 현재 버전 비교와 schema history 최대 버전의 숫자 비교를 탐지하지만, 변수에 값을 옮기거나 이력의 마지막 행을 조회하는 표현, 적용 개수 비교 등 모든 표현을 분석하지는 못한다. 명시적인 과거 target의 현재 버전 직접 비교가 필요한 경우 해당 문장 앞에 `// flyway-historical-target`을 두며 코드 리뷰에서 과거 검증임을 확인한다. 코드 리뷰에서도 이 원칙을 확인한다. 전체 Backend 테스트는 요구하지 않으며 테스트 실행 범위 정책을 따른다.

### Test Naming Convention

- Test Method는 영어 `snake_case`를 사용한다.
- Production Method는 기존 Java `camelCase`를 유지한다.
- 모든 `@Test` Method에는 한국어 자연어 문장으로 작성한 `@DisplayName`을 추가한다.
- `@DisplayName`은 Test의 행동과 기대 결과를 명확히 표현한다.
- Method 이름은 지나치게 길게 만들지 않고, 세부 설명은 `@DisplayName`에 맡긴다.
- Test Variable은 Java `camelCase`를 유지하고, 문맥이 명확한 범위에서 간결하게 작성한다.

예:

```java
@Test
@DisplayName("비활성 알람을 활성화하면 활성 상태가 된다")
void activate_when_inactive() {
    // ...
}
```

## 6. Git / 커밋 규율

### Git Commit Message Convention

StopBell 프로젝트는 Conventional Commit 형식을 기반으로 작성한다.

Format:

```text
<type>: <description>
```

Description은 한국어로 작성한다.

예:

```text
feat: 사용자 로그인 기능 추가
fix: 알림 중복 발송 문제 수정
docs: API 문서 업데이트
refactor: 알림 처리 구조 개선
chore: 프로젝트 초기 구조 생성
```

### Commit Message Body Convention

Commit Message는 필요에 따라 Subject와 Body로 구성한다.

Format:

```text
<type>: <description>

<body>
```

#### Subject 규칙

Subject는 변경 내용을 간결하게 표현한다.

규칙:

- type prefix는 영어 사용
- description은 한국어 사용
- 한 줄로 작성
- 변경 목적을 이해할 수 있어야 함

예:

```text
feat: 사용자 SNS 로그인 기능 추가
```

#### Body 작성 기준

Body는 모든 Commit에 필수는 아니다.

하지만 다음과 같은 경우 Body 작성을 권장한다.

##### 1. 변경 범위가 큰 경우

예:

```text
chore: StopBell 프로젝트 초기 구조 생성

- Spring Boot Backend Skeleton 추가
- Flutter Application Skeleton 추가
- Docker MySQL 개발 환경 구성
- JPA + MyBatis Hybrid Persistence 설정
- 프로젝트 문서 및 ADR 추가
```

##### 2. 설계 결정이 포함된 경우

예:

```text
chore: JPA와 MyBatis Hybrid Persistence 적용

단순 Domain CRUD는 JPA를 사용하고,
Transit 관련 Complex Query는 MyBatis로 분리했다.

ADR-002 결정 사항을 반영했다.
```

##### 3. 변경 이유가 중요한 경우

예:

```text
fix: 알림 중복 발송 문제 수정

동일한 Transit Event가 여러 번 처리되는 문제가 있어
NotificationEvent atomic uniqueness 검증을 추가했다.
```

#### Body 작성 원칙

- 변경 내용과 변경 이유를 설명한다.
- 제목만으로 이해하기 어려운 내용을 보완한다.
- 구현 세부사항보다 변경 목적을 우선한다.
- 여러 변경사항은 목록 형태로 작성할 수 있다.
- 관련 없는 변경사항은 하나의 Commit Body에 포함하지 않는다.

#### Body 생략 가능 기준

다음과 같은 작은 변경은 Body를 생략할 수 있다.

예:

```text
fix: 오타 수정
docs: 주석 수정
```

단, 아래와 같은 경우 Body 작성을 권장한다.

- 프로젝트 구조 변경
- Architecture 변경
- 주요 Feature 추가
- Database Schema 변경
- 큰 규모의 Refactoring

이번 Task에서는 Git Branch Strategy, Pull Request Rule, Release Process, GitHub Actions Workflow를 추가하지 않는다.

커밋은 적절히 하나의 목적에 집중해야 한다.

## 7. 시크릿과 설정

다음은 절대 커밋하지 않는다.

- 데이터베이스 비밀번호
- 교통 API 키
- OAuth 클라이언트 시크릿
- FCM 서비스 자격 증명
- 개인 키

환경 변수, 시크릿 저장소 또는 무시되는 로컬 설정을 사용한다.

유용하다면 `.env.example` 또는 동등한 플레이스홀더를 제공한다.

## 8. 기능 완료 정의

기능은 컴파일된다고 해서 완료되는 것이 아니다.

해당하는 경우 다음을 포함한다.

- 올바른 동작
- 오류 처리
- 관련 테스트
- 유용한 로그
- 결정 또는 계약이 바뀌었다면 업데이트한 문서
- 현실적인 흐름에서의 수동 검증

## 9. 포트폴리오 중심 과도한 설계 방지

이력서에 기술을 나열하기 위해 Redis, Kafka, Kubernetes, 마이크로서비스, CQRS, 이벤트 소싱 또는 기타 인프라를 추가하지 않는다.

StopBell에 더 단순한 대안보다 잘 해결하는 구체적인 문제가 있을 때 기술을 추가한다.
