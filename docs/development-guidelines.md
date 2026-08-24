# 개발 가이드라인

## 1. 목적

이 규칙은 StopBell의 사람 개발과 AI 보조 개발 모두에 적용한다.

## 2. Codex 핵심 규칙

중요한 기능을 구현하기 전:

1. `docs/`의 관련 문서를 읽는다.
2. 이미 문서화되지 않은 가정을 밝힌다.
3. 설계 결정에 의미 있는 대안이 있다면 코딩 전에 대안과 트레이드오프를 제시한다.
4. 프레임워크, 인프라, 아키텍처 패턴을 알리지 않고 도입하지 않는다.
5. 현재 버전의 범위를 기억한다.

## 3. 백엔드 가이드라인

기본 스택:

- Java 21
- Spring Boot 4.1.1
- Spring Framework 7.0.9
- Gradle Wrapper 8.14.3
- Spring Data JPA / Hibernate ORM 7.4.5.Final
- MyBatis Spring Boot Starter 4.1.0
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
  ├── Repository (JPA)
  └── Mapper (MyBatis)
  ↓
Database
```

단, 패턴을 따르기 위해 의미 없는 계층이나 클래스를 만들지 않는다.

### JPA

- 단순한 Domain CRUD와 Entity 상태 관리는 JPA Repository를 사용한다.
- `User`, `Alarm`, `NotificationHistory`는 JPA 기반으로 관리한다.
- Entity 관계와 상태 전이는 Domain 규칙에 맞게 명확히 유지한다.
- 복잡한 조회를 위해 불필요하게 JPA Query를 복잡하게 만들지 않는다.

### MyBatis / SQL

- SQL은 읽기 쉽고 명시적이어야 한다.
- Transit 관련 Query, 복잡한 검색, 집계 Query, 성능 최적화가 필요한 조회에는 MyBatis를 사용한다.
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

- 제공자별 클라이언트는 StopBell 소유 인터페이스/서비스 경계 뒤에 둔다.
- 타임아웃을 정의한다.
- 성공하지 않은 응답을 처리한다.
- API 시크릿을 로그에 남기지 않는다.
- 제공자 지연/실패를 디버깅할 충분한 메타데이터를 보존한다.

### 스케줄러

- 스케줄러 코드는 작업을 조율하고 모든 비즈니스 로직을 담지 않는다.
- 중복 부작용을 낼 수 있는 실행이 겹치지 않게 한다.
- 폴링 주기는 제공자 제한과 실제 제품 요구를 반영해야 한다.

## 4. Flutter 가이드라인

V1에서 앱은 비교적 얇은 클라이언트로 유지한다.

책임:

- 사용자 상호작용
- API 요청
- 로컬 화면 상태
- 푸시 토큰 획득/갱신
- 푸시 알림 처리

특별한 이유 없이 교통 모니터링 비즈니스 로직을 모바일 앱으로 옮기지 않는다.

## 5. 테스트

사용자에게 실패를 일으킬 수 있는 동작을 우선 테스트한다.

- 알림 조건 평가
- 중복 알림 방지
- 알림 상태 전이
- 외부 제공자 응답 매핑
- API 검증/오류 처리

프레임워크 동작만 반복 검증하는 테스트는 피한다.

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
NotificationHistory 기반 중복 체크 로직을 추가했다.
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
