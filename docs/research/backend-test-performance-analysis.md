# Backend 테스트 실행 비용 분석

측정일: 2026-11-11 (Asia/Seoul). 분석 기준 Commit: `105de10`.

§1~7은 최초 분석과 당시 제안 기록이다. 이번 후속 작업은 초기화 비용 제거를 우선하며 테스트를 삭제하지 않는다. 최종 구조·검증·전후 비교는 §8에 기록한다.

## 1. 실측 결과와 측정 한계

| 항목 | 결과 |
| --- | --- |
| 전체 실행 | 130개 클래스 / 1,896개 / 실패·오류·Skip 0 |
| 전체 wall time | 272.87초 (4분 32.87초) |
| DB 사용 테스트 | 50개 클래스 / 867개 |
| DB 비의존 선택 실행 | 80개 클래스 / 1,029개 / 실패·오류·Skip 0 |
| DB 비의존 wall time | 23.14초 |
| 전체 JUnit XML 클래스 시간 합계 | 151.56초 |
| DB 사용 클래스의 XML 시간 합계 | 137.86초 |
| Spring Boot 기동 로그 | 44회 / 기동 시간 합계 80.77초 |
| Flyway 성공 적용 로그 | 99회 / 적용 Migration 수 합계 2,205개 (기존 75회·2,181개 집계 정정) |
| MySQL 서버 시작 | 공유 1회 + 백업 복원 대상 1회 |

환경은 macOS arm64, Java와 Gradle JVM 모두 21.0.12.1, Gradle Wrapper 8.14.3이다. 기존 로컬 dependency/image 캐시를 사용했다. 실제 Transit Provider·FCM·개발 DB는 사용하지 않았다.

전체 측정 명령:

```sh
cd backend
/usr/bin/time -p ./gradlew test --rerun-tasks --console=plain
```

UP-TO-DATE에 의한 실행 생략을 막기 위해 `--rerun-tasks`를 사용했다. compile/resource 처리와 Gradle daemon 기동 비용도 wall time에 포함한다. 따라서 이번 환경에서는 사용자가 관측한 5분 이상을 재현하지 못했으며, 5분을 실측 baseline으로 사용하지 않는다.

DB 비의존 측정은 `SharedMySqlExtension`을 사용하는 50개 클래스를 소스에서 식별하고, 나머지 80개 클래스를 Gradle `includeTestsMatching`으로 명시 선택했다. 임시 init script로 XML/HTML 출력도 별도 디렉터리로 분리했다. 컴파일은 UP-TO-DATE였으며 `test`는 실제 실행됐다. 두 실행의 컴파일 조건이 다르므로 272.87초와 23.14초의 차이를 동일 검증 범위에서 얻은 최적화 효과로 해석하지 않는다. 두 실행 모두 1회 측정이므로 CI 수치·중앙값·분산은 미측정이다.

## 2. 느린 클래스 TOP 20

정렬 기준은 Gradle JUnit XML의 클래스 `time`이다. Spring Context 기동 및 `BeforeAll`의 컨테이너 시작 비용 등이 빠질 수 있어 별도 기동 로그 열을 제공한다. 기동 시간은 정확한 클래스 전체 wall time이 아니며 두 열을 일반적인 프로파일러의 독립 구간처럼 합산하지 않는다. TOP 20의 XML 시간 합계는 130.63초로 전체 XML 시간의 약 86%다.

| 순위 | 클래스 | 테스트 수 | XML 시간(초) | Spring 기동 로그(초) |
| --- | --- | ---: | ---: | ---: |
| 1 | `BusAlarmDecisionIntegrationTest` | 18 | 36.479 | 2.002 |
| 2 | `BoardingTrackingIntegrationTest` | 137 | 16.659 | 2.131 |
| 3 | `UserIdentityMigrationIntegrationTest` | 14 | 15.562 | 0.000 |
| 4 | `BoardingSessionApiIntegrationTest` | 136 | 15.185 | 2.201 |
| 5 | `BoardingDispatchIntegrationTest` | 71 | 8.444 | 1.990 |
| 6 | `TransitMetadataCollectionTest` | 41 | 5.503 | 0.000 |
| 7 | `AccountWithdrawalIntegrationTest` | 48 | 4.265 | 2.245 |
| 8 | `OfflineBundleApprovalIntegrationTest` | 7 | 3.437 | 1.716 |
| 9 | `AlarmMigrationIntegrationTest` | 1 | 2.554 | 0.000 |
| 10 | `NotificationSourceMigrationIntegrationTest` | 1 | 2.540 | 0.000 |
| 11 | `AlarmCreationIntegrationTest` | 17 | 2.487 | 4.338 |
| 12 | `BackupRestoreIntegrationTest` | 1 | 2.362 | 0.000 |
| 13 | `SeoulCsvMetadataSyncIntegrationTest` | 18 | 2.326 | 1.566 |
| 14 | `BusMetadataSafetyIntegrationTest` | 11 | 2.273 | 1.747 |
| 15 | `TransitMetadataSnapshotPreviewIntegrationTest` | 12 | 1.948 | 1.652 |
| 16 | `TransitMetadataDiffMigrationTest` | 1 | 1.801 | 0.000 |
| 17 | `NotificationMigrationIntegrationTest` | 1 | 1.775 | 0.000 |
| 18 | `NotificationAttemptIntegrationTest` | 45 | 1.766 | 1.943 |
| 19 | `TransitMetadataDiffAnalysisIntegrationTest` | 34 | 1.668 | 1.695 |
| 20 | `TransitMetadataCollectionMigrationTest` | 1 | 1.591 | 0.000 |

`0.000`은 해당 클래스 결과에서 Spring Boot 기동 로그가 없다는 뜻이다. 컨테이너 시작 등 모든 초기화 비용이 0이라는 뜻은 아니다.

## 3. 병목과 초기화 반복

### Spring Context와 Migration 반복이 가장 큰 구조적 절감 대상

`@SpringBootTest` 44개, `@DirtiesContext` 40개, `@DynamicPropertySource` 40개다. DB 통합 클래스 대부분은 클래스별 서로 다른 DynamicPropertySource 메서드와 DB 이름을 사용하고 AFTER_CLASS로 Context를 닫는다. Context 재사용을 어렵게 만드는 MockitoBean/SpyBean 조합도 다르다. 단순히 `@DirtiesContext`만 제거해도 공유되지 않으며, 현재 lifecycle과 정리 책임을 깨뜨릴 수 있다.

44회 기동 로그 합계는 80.77초다. DB 비의존 클래스 기동은 이 중 4.50초이고 나머지 약 76.27초가 DB 사용 클래스에 속한다. Flyway 성공 적용 99회에는 클래스별 fresh schema와 Migration 테스트의 단계별 업그레이드가 모두 포함된다. Flyway 적용 시간은 Context 또는 테스트 본문에 들어 있으므로 80.77초에 별도 합산하지 않는다. 2,205개는 서로 다른 Migration 파일 수가 아니라 반복 적용 횟수의 합계다. 기동·Migration 집계는 부모 테스트 JVM의 XML 로그 기준이며 child JVM의 별도 파일 로그와 ApplicationContextRunner의 작은 Context 기동은 포함하지 않는다.

개선 방향은 계약 검증이 겹치는 DB 테스트를 삭제하고, 설정·Mock 조합과 DB 격리 요구가 같은 남은 클래스만 소수 Context로 묶는 것이다. 트랜잭션 rollback, commit 후 상태, 실패 DDL, 동시성 테스트에는 별도 격리가 필요하다. 모든 DB 테스트를 하나의 거대 Context/상속 기반 클래스로 바꾸는 방식은 권고하지 않는다.

### Testcontainers의 서버 반복 기동은 이미 대부분 해결됨

`support/SharedMySqlExtension.java`는 JUnit root store에서 서버 하나를 공유한다. 이번 실행 로그에서도 공유 MySQL 시작 1회(8.29초)를 확인했다. 클래스·호출별 DB 생성과 스키마 적용은 여전히 반복한다. 여러 클래스·호출의 DB가 동일 서버 안에서 격리된다.

`BackupRestoreIntegrationTest`만 복원 대상 MySQL을 별도로 시작한다(7.16초). 실제 dump/restore와 데이터 보존 검증을 수행하므로 유지한다. 클래스마다 MySQL 컨테이너를 새로 띄운다는 진단이나 영구 reuse 설정 도입은 이번 근거와 맞지 않는다.

### 가장 느린 테스트는 실제 재시작 검증

`BusAlarmDecisionIntegrationTest`의 세 테스트가 35.06초를 사용한다.

- SIGTERM 후 재시작과 Event/Delivery 중복 방지: 17.55초
- SIGTERM 중 미완료 transaction rollback과 재시작: 11.55초
- FOLLOW_UP cycle UUID 복구: 5.96초

`RestartProcessProbe`는 실제 Backend child JVM을 반복 시작한다. 단순한 sleep 낭비가 아니라 실제 프로세스 경계 검증 비용이다. 세 테스트와 복구 계약을 유지한다. 테스트 전용 probe의 실행 구성 축소는 별도 구조 변경 후보이며, 실제 종료·복구 검증을 메모리 객체 재생성으로 대체하지 않는다.

`BoardingDispatchIntegrationTest.backlog_fairness_and_sql_plan`은 5.72초다. User/Session/Device/Event/Delivery를 2,000세트 생성한다. batch 점유 방지와 실제 쿼리 계획을 함께 검증한다. 우선 유지하고, 후속 최적화에서는 실제 DB batch 경계와 실행 계획을 보존하면서 fixture 생성 비용을 줄일 수 있는지 확인한다. 단순히 행 수를 줄여 기존 규모 검증을 없애는 것은 절감 효과로 계산하지 않았다.

## 4. 삭제·축소 후보

다음은 구현 전 검토한 구체 후보다. 실제 삭제 단계에서는 남는 테스트가 동일한 실패를 잡는지 assertion과 실행 경로를 대조한다. 테스트 수를 유지하기 위한 대체 테스트를 자동 추가하지 않는다.

| 대상 | 현재 중복 또는 과잉 조합 | 권고 | 예상 감소 |
| --- | --- | --- | ---: |
| `StopBellApplicationTests.application_context_loads` | 본문이 비어 있고 같은 클래스의 health 테스트가 동일 Context 기동을 이미 요구 | 메서드 삭제 | 1 |
| `BoardingTrackingIntegrationTest.destination_is_independent_of_passenger_access` | access 3종 × preparation 2종 × 종료 위치 2종 = 12회, 독립적인 축의 곱집합 | arrival/passed와 설정 영향을 대표 3개로 축소, access별 순수 판정과 중복 Event 검증 유지 | 9 |
| 같은 클래스 `pre_departure_policy_polling` | 10개 조합마다 fixture 재구축과 반복 polling; offset/stopFlag/종료 판정이 순수 evaluator와 겹침 | offset 1/2, fallback, 종료의 대표 통합 흐름 4개 유지 | 6 |
| `BoardingSessionApiIntegrationTest.aud08_conflicting_evidence_does_not_notify` | 9종 중 과거 수신·Provider 시각·동일 시각·이전 위치 5종이 현재 허용 결과를 반복 | 허용 대표 1개와 unverified/stale/future 거부 3개 유지 | 5 |
| `BoardingDispatchIntegrationTest.same_durable_result_policy` | 공통 Handler/RetryPolicy와 같은 Provider 결과 6종을 Session DB에서 재검증 | Session 연결·기한 cap을 확인하는 대표 성공/재시도 2개 유지 | 4 |
| `UserIdentityMigrationIntegrationTest.observe_legacy_upgrade` | User 수 1/2/10 × strict true/false = 6회; 다수 User 2와 10의 Unique 실패 경계 중복 | 실제 지원 SQL mode에서 단일 성공/복수 실패 대표 2개 유지 | 4 |
| `MvcErrorHttpIntegrationTest` 및 Boarding API 입력 테스트 | MVC body/type 오류와 모든 Domain ErrorCode를 실제 HTTP 또는 DB fixture에서 반복 | 바인딩 실패 종류와 보안 경계별 대표 사례만 유지, Endpoint 고유 계약은 유지 | 추가 산정 |

구체 표의 앞 6항목만으로 29개 감소 후보가 있다. 빈 Context 테스트 삭제는 같은 클래스가 남으므로 기동 시간을 줄이지 않는다. 순수 evaluator 79개는 XML 합계 0.025초여서 정리 시 개수·유지보수 효과가 주효하고 실행 시간 절감은 거의 없다. HTTP 오류 클래스 82개도 XML 0.392초이므로 개수 감소를 시간 단축으로 과장하지 않는다.

추가 API 입력 조합·중복 판정·단순 설정/프레임워크 검증까지 포함해 우선 60~120개 감소를 검토할 수 있다. 이 범위는 삭제 완료 수나 검증된 최소 수가 아니다. 명확히 불필요한 항목은 추가로 발견하면 더 삭제하며 기존 개수를 방어하지 않는다.

### 폐기 정책과 과거 호환성의 구분

최근 단순화로 과거 Provider 시각·이전 위치·access 조건을 거부하던 정책이 바뀌었다. 일부 테스트는 이미 기대값을 허용으로 바꿨지만 과거 시나리오 이름과 많은 조합을 유지한다. 위 AUD-08 조합이 대표적인 정리 대상이다. 현재 거부돼야 한다고 주장하는 폐기 정책 전용 테스트가 남아 있다는 결론은 이번 조사에서 확정하지 않았다.

`VERIFYING`, ON/OFF, FOLLOW_UP 또는 과거 Migration 버전을 언급한다는 이유만으로 삭제하지 않는다. 기존 저장 데이터와 요청의 호환성, Unique/FK/원자성, 실제 업그레이드 실패·데이터 보존을 검증하는 테스트는 현재 기능 계약이다. 특히 과거 Migration Guard를 폐기 정책으로 취급해 제거하지 않는다. 본문이 실제로 현재 계약과 겹치는 경우에만 통합하거나 삭제한다.

## 5. 실행 경로 제안 — 구조 변경 단계

현재도 다음 명령으로 변경 기능을 선택 실행할 수 있다.

```sh
cd backend
# 빠른 탑승 진행 판정과 차량 추적
./gradlew test --tests '*BoardingProgressEvaluatorTest' --tests '*BoardingVehicleTrackerTest'
# 변경 기능의 실제 DB 회귀만 추가
./gradlew test --tests '*BoardingTrackingIntegrationTest'
# 전체 검증
./gradlew test
```

후속 구현에서는 DB를 사용하는 50개 클래스에 명시적인 JUnit `db` tag를 부여하고 `fastTest`(db 제외), `dbTest`(db 포함)를 마련하는 방식을 권고한다. DB를 쓰면서 `IntegrationTest`로 끝나지 않는 `TransitSourceMetadataMigrationTest` 등도 있으므로 파일명만으로 분리하지 않는다. 빠른 경로에는 DB 없는 Spring/HTTP 테스트도 포함되므로 이를 순수 unit test라고 부르지 않는다.

`test`는 tag 필터 없이 전체를 실행하도록 유지한다. `build`/`check`에 선택 경로 두 개를 추가 연결해 전체를 중복 실행하지 않는다. 현재 `test`에만 있는 HTTP retry 설정과 child JVM용 `task811.test.classpath`는 필요한 Test task에 동일 적용해야 한다. 일반 기능 변경은 `fastTest --tests ...`와 관련 `dbTest --tests ...`를 사용하고 CI는 기존 `./gradlew --no-daemon build`로 전체를 실행한다. 이 task 이름들은 제안이며 아직 사용할 수 없다.

## 6. 예상 절감 효과와 변경 단계

| 단계 | 변경 | 시간 효과 | 개수 효과 |
| --- | --- | --- | --- |
| 분석 완료 | DB 비의존 선택 실행 | 실측 23.14초, 컴파일 캐시 사용 | 1,029개 선택, 삭제 없음 |
| 1차 정리 | 중복 DB/HTTP/판정 조합과 빈 테스트 삭제 | 전체 기준 약 5~15초 절감 예상 | 구체 후보 29개, 추가 검토 포함 약 60~120개 |
| 실행 경로 분리 | fastTest/dbTest와 실행 문서 | 개발 중 전체 대기 대신 약 20~30초의 빠른 경로 예상 | 전체 CI 검증 범위 유지 |
| 별도 구조 변경 | 동일 설정의 DB Context 병합, 중복 fresh/upgrade 경로 축소 | Context 관련 약 20~40초 추가 절감 가설 | 필요 없는 클래스·중복 경로 추가 삭제 |

전체 실행은 1차 정리 후 약 4분 18초~4분 28초, Context 변경까지 성공하면 약 3분 38초~4분 8초를 계획 범위로 본다. 이는 272.87초 baseline에서 계산한 미검증 추정이며 보장값이 아니다. 컨테이너/child JVM/DB 규모 검증 비용은 대부분 남는다. 모든 DB Context를 제거한 수치나 전체 1분 이내 완료를 목표값으로 제시할 근거는 없다.

대규모 삭제와 Context 구조 변경은 이 분석 보고 이후 별도 변경 단계로 구분한다. 먼저 중복 삭제와 실행 경로 분리를 적용하고 전체 테스트로 누락/중복 실행, 실패·Skip 여부를 확인한다. Context 변경은 그 결과에서 남은 병목에 한해 진행한다. 비교 재측정은 같은 JVM/캐시/compile 조건에서 수행해야 한다.

유지해야 할 검증은 API 고유 계약, 알림 Event/Delivery Unique와 중복 방지, 원자 commit/rollback과 데이터 무결성, 소유권·인증 경계, 세션 복구/실제 재시작, 경쟁·잠금·늦은 결과 거부다. 프로덕션 코드 변경이나 신규 방어 로직 추가는 범위에 포함하지 않는다.

## 7. 최초 분석 단계 상태

최초 분석 단계에서는 분석 보고서만 추가했다. 프로덕션·테스트·Gradle·CI·Dependency·Migration과 Task 체크 상태는 변경하지 않았다. 새 maintenance Task를 만들지 않았고 devlog도 수정하지 않았다. Commit/Push는 수행하지 않았다. 최적화 구현 완료를 선언하지 않는다.

재현 자료는 로컬 임시 파일에 남겼다. 이 파일들은 저장소에 포함하지 않으며 `/private/tmp` 정리 시 사라질 수 있다.

- 전체 실행 로그: `/private/tmp/stopbell-test-baseline.log`
- 전체 XML 보존본: `/private/tmp/stopbell-full-test-results/`
- 클래스별 추출 수치: `/private/tmp/stopbell-test-metrics.json`
- DB 비의존 선택용 init script: `/private/tmp/stopbell-fast-baseline.init.gradle`
- DB 비의존 실행 로그와 XML: `/private/tmp/stopbell-fast-baseline.log`, `/private/tmp/stopbell-fast-test-results/`


## 8. 초기화 재사용과 CI 분리 적용 (2026-11-11)

이번 후속 요청은 테스트 삭제보다 초기화 반복 제거와 정확성·독립성을 우선한다. 테스트 본문과 프로덕션 코드·알고리즘·Migration SQL은 변경하지 않았고 테스트를 삭제하지 않았다. 기존 §4의 삭제 제안은 이번 구현 범위에 포함하지 않는다.

### 8.1 분류와 적용 범위

| 그룹 | 클래스 범위 | 실행 사례 수 | 격리 방법 |
| --- | --- | ---: | --- |
| DB 비의존 | 80개 | 1,029 | 기존 단위/DB 없는 Spring·HTTP 테스트 유지 |
| 공유 최신 스키마 | 11개 | 117 | 최신 DB 1개, Context 2개, rollback 또는 메서드 전후 정리 |
| 독립 DB의 현재 기능 | 29개 (아래 Migration 메서드 포함 클래스 1개) | 726 | 기존 클래스별 DB와 Context 종료·상태 복원 유지 |
| 과거 Migration·실패·복원 | 10개 클래스 + 혼합 클래스의 1개 메서드 | 24 | 기존 호출별 DB·과거 target·실패 이력·별도 복원 서버 유지 |

합계는 130개 클래스·1,896개다. mixed 클래스의 Migration 메서드를 두 번 세지 않는다. 단순 파일명으로 그룹을 정하지 않았다. `LoginMigrationIntegrationTest`는 과거 target 업그레이드가 아니라 최신 DB의 no-op Migration 뒤 로그인/소유권 보존 테스트이므로 일반 CI에 남긴다.

공유 대상:

- `AlarmServiceIntegrationTest`
- `BoardingSessionPersistenceIntegrationTest`
- `ReadinessIntegrationTest`
- `DevicePersistenceIntegrationTest`
- `NotificationDispatchIntegrationTest`
- `BusMetadataSyncTransactionIntegrationTest`
- `TransitMetadataRepositoryIntegrationTest`
- `BusRouteSearchIntegrationTest`
- `BusRouteStopIntegrationTest`
- `TransitMetadataPreflightIntegrationTest`
- `TransitSourceMetadataIntegrationTest`

이 중 `BusRouteSearchIntegrationTest`는 Hibernate statistics 설정이 달라 별도 Context를 사용하지만 동일 DB를 공유한다. 나머지 10개는 동일 Context key(상속한 DynamicPropertySource·Boot 설정·MockMvc·profile·initializer)를 사용한다. 불필요한 `@DirtiesContext` 11개와 클래스별 DB URL을 제거했다.

공유 DB는 Spring의 정상 Flyway auto migration으로 최신 스키마를 생성한다. 두 번째 Context의 Flyway는 이미 최신인 DB에서 no-op으로 종료한다. `FlywayTestAssertions.assertLatest`로 validation·pending 없음·최신 version 일치를 확인하고 `ddl-auto=validate`로 Entity mapping을 확인한다. Migration 자체를 끄거나 스키마를 수동 생성하지 않는다.

메서드의 기존 트랜잭션과 실제 commit/rollback 검증은 보존했다. rollback형 테스트는 기존 rollback을 유지한다. commit하는 `TransitSourceMetadataIntegrationTest`, `NotificationDispatchIntegrationTest`, `BusMetadataSyncTransactionIntegrationTest`는 동일 정리 SQL을 메서드 전후에도 실행한다. 부분 commit/실패·Notification attempt 결과 검증은 정리 이전의 실제 DB에서 수행한다. DDL이나 병행 작업을 사용하지 않고 호출이 끝난 후 정리할 수 있어 이 세 클래스는 별도 DB가 필수가 아니라고 판단했다.

클래스 전후에는 `@Sql`의 ISOLATED 트랜잭션으로 모든 업무 테이블을 FK 순서에 맞게 DELETE한다. Flyway 이력·FK/CHECK/UNIQUE와 AUTO_INCREMENT는 보존한다. 실제 테이블 목록과 정리 대상이 일치하는지도 검사해 새 테이블이 정리에서 누락되면 setup이 실패한다. 순차 worker를 유지하고 캐시된 Context를 MySQL보다 먼저 닫아 pool 누수를 기존 종료 guard로 검증한다.

독립 DB를 유지한 현재 기능 테스트:

- `AlarmCreationIntegrationTest`
- `BusAlarmDecisionIntegrationTest`
- `TransitMonitoringIntegrationTest`
- `BoardingSessionApiIntegrationTest`
- `BoardingTrackingIntegrationTest`
- `UtcTimestampCharacterizationIntegrationTest`
- `BoardingDispatchIntegrationTest`
- `PassedNotificationDispatchIntegrationTest`
- `DeviceApiIntegrationTest`
- `NotificationPersistenceIntegrationTest`
- `NotificationAttemptIntegrationTest`
- `GbisRawMetadataSyncIntegrationTest`
- `SeoulCsvMetadataSyncIntegrationTest`
- `TagoRawMetadataSyncIntegrationTest`
- `BusMetadataSafetyIntegrationTest`
- `OfflineBundleApprovalIntegrationTest`
- `ProviderArchiveIntegrationTest`
- `BoardingCandidateApiIntegrationTest`
- `TransitMetadataArchiveIntegrationTest`
- `TransitMetadataCollectionHistoryIntegrationTest`
- `TransitMetadataDiffAnalysisIntegrationTest`
- `TransitMetadataSnapshotPreviewIntegrationTest`
- `AppleLoginIntegrationTest`
- `AppleNotificationIntegrationTest`
- `AuthControllerIntegrationTest`
- `LoginMigrationIntegrationTest`
- `LoginServiceConcurrencyIntegrationTest`
- `RepositoryIntegrationTest`
- `AccountWithdrawalIntegrationTest`

DDL/실패 주입·경쟁·실제 프로세스 종료를 사용하는 Notification/Boarding/인증/메타데이터 테스트는 합치지 않았다. 나머지도 clock/mock/spy, 별도 archive 디렉터리, 전용 인증 설정·profile, non-web/timezone 검증 또는 독립 연결 계약이 다르다. 이들을 공유하려고 Mock 구성을 전역으로 합치거나 기존 회귀·실패 검증을 약화하지 않았다. 모든 FK/UNIQUE 테스트가 반드시 별도 DB를 요구하는 것은 아니며, rollback으로 격리 가능한 매핑 테스트는 공유 대상에 포함했다.

과거 Migration 그룹:

- `AlarmMigrationIntegrationTest`
- `FlywayReadinessFailureIntegrationTest`
- `DetachedDeliveryMigrationIntegrationTest`
- `NotificationMigrationIntegrationTest`
- `NotificationSourceMigrationIntegrationTest`
- `BackupRestoreIntegrationTest`
- `TransitMetadataCollectionMigrationTest`
- `TransitMetadataDiffMigrationTest`
- `TransitSourceMetadataMigrationTest`
- `UserIdentityMigrationIntegrationTest`
- `TransitMetadataSnapshotPreviewIntegrationTest.cli_refuses_old_schema_without_migrating` 메서드만 추가 분류 (기존 독립 파라미터 DB 유지)

### 8.2 단계별 검증

- 대표 3개 클래스·37개: 최초 setup에서 최신 스키마에서 제거된 `notification_history`를 정리하려던 오류를 발견했다. SQL을 수정하고 초기화 실패 시에도 Context를 root 자원에 등록해 pool을 닫도록 보완했다. 이후 정상 실행과 무작위 클래스·메서드 순서(seed 716) 모두 통과했다.
- 9개 클래스·101개: seed 927와 1021에서 모두 통과, 실제 Spring 기동 2회·전체 Migration 적용 1회 확인
- 9개 적용 중간 전체 실행: 1,896개 통과, 244.30초 (272.87초 대비 10.5% 감소)
- 최종 11개 공유 클래스·117개: seed 1319 실행에서 인접 PassedNotification 회귀 10개까지 포함한 127개 통과, seed 2027에서는 11개만 정확히 선택한 117개 통과

무작위 실행은 기존 JUnit `ClassOrderer$Random`/`MethodOrderer$Random`과 seed system property를 사용했다. Gradle `-Pjunit.jupiter.testclass.order.default=...`, `-Pjunit.jupiter.testmethod.order.default=...`, `-Pjunit.jupiter.execution.order.random.seed=...`를 Test JVM으로 전달한다. 최종 전체 검증은 기본 순서로도 수행한다.

### 8.3 Flyway 기준 정정

기존 75회는 전체 호출 수가 아니었다. 로그 추출식이 복수형 `migrations`만 세어 단수형 `1 migration` 성공 24회를 빠뜨렸다. 같은 원본 XML을 다시 집계하면 성공 99회(SQL 적용 합계 2,205개), 최신 no-op 17회, 의도한 실패 5회로 총 121회다. 파일/로그를 삭제하거나 실패를 Skip해서 수치를 줄이지 않았다.

원본 99회 성공은 빈 DB→최신 42회, 빈 DB→과거 target 21회, 과거 버전에서 추가 upgrade 36회로 나뉜다. 자동 실행 여부는 별도 축이며 이 중 40회는 일반 DB 테스트의 Spring Context auto migration이다. 빈 DB→최신의 나머지 2회는 User Identity fresh 검증과 백업 원본이다. no-op은 기존 최신 Migration 검증을 그대로 실행한 것이며 실패 5회는 의도된 과거 업그레이드/실패 시나리오다. 따라서 75회와 no-op/실패 수를 단순히 더하면 정확한 호출 수가 되지 않는다.

수치는 부모 테스트 JVM의 XML에 남은 Flyway migrate 결과 기준이다. 별도 프로세스 로그, ResourceDatabasePopulator로 직접 실행한 SQL, 실패 Context의 결과 로그 이전 단계는 별도다. `validate`·`info` 조회를 migrate 호출로 세지 않는다. Flyway 로그의 execution time은 SQL 적용 구간이므로 Context 기동/전체 wall time에 더하지 않는다.

### 8.4 실행 및 CI 동작

- 전체 및 배포 전/수동: `./gradlew --no-daemon build` (`test`는 항상 전체)
- 일반 CI: `./gradlew --no-daemon assemble ciTest` (Migration 24개를 의도적으로 제외)
- 과거 Migration 집중 실행: `./gradlew migrationTest`
- 빠른 기능 선택: `./gradlew fastTest --tests '*BoardingProgressEvaluatorTest'`
- 현재 DB 기능 선택: `./gradlew dbTest --tests '*BoardingTrackingIntegrationTest'`

일반 Push/PR에서 안전하게 일반 변경으로 판별된 경우만 `ciTest`를 사용한다. DB SQL/Schema/매핑/설정/데이터 upgrade, 전체 Backend test 인프라·Gradle, CI 도구/워크플로 변경은 전체 `build`를 실행한다. diff 실패·base SHA 부재/초기 Push·지원하지 않는 Migration 사례 집계도 전체로 전환한다. 삭제/rename된 Mapping을 포함한 git diff와 conventional package 밖 Mapping/SQL 변경을 검사한다. test 변경을 모두 보수적으로 전체 분류하므로 실제 필요보다 자주 전체를 실행할 수 있다.

`workflow_dispatch`는 변경 감지와 무관하게 전체 경로다. `check`에 선택 task들을 추가 연결하지 않아 전체 검증을 중복 실행하지 않는다. HTTP retry property와 child JVM classpath는 모든 Test task에서 유지한다. Gradle은 실행·성공·실패·Skip 수를 출력하고 CI 도구는 XML의 실제 결과와 의도적 Migration 제외 사례 수를 GitHub Step Summary에 기록한다. 제외 수는 현재 literal 태그·CsvSource를 소스에서 집계하며 지원하지 않는 동적 사례는 전체로 전환한다. XML 부재·실행 0개·실패·Skip은 보고 단계에서도 실패한다.

CI 선택/보고 도구는 Python 표준 라이브러리만 사용한다. 실제 Git 저장소 fixture로 일반 변경, DB 변경, Mapping 삭제/rename, base 실패·수동 전체, 동적 Migration 집계 실패와 결과 보고를 검증했다. GitHub 호스팅 runner의 실제 실행은 Push하지 않아 수행하지 않았으며 로컬에서 동일 Gradle 경로를 검증한다.


### 8.5 최종 전후 실측

| 지표 | 최적화 전 전체 | 최적화 후 전체 | 일반 CI |
| --- | ---: | ---: | ---: |
| 전체 wall time | 272.87초 | 236.62초 | 216.69초 |
| 실행 클래스 | 130 | 130 | 120 |
| 실행 사례 | 1,896 | 1,896 | 1,872 |
| Spring Boot 기동 | 44 | 35 | 35 |
| 빈 DB → 최신 전체 Migration | 42 | 32 | 30 |
| 빈 DB → 과거 target | 21 | 21 | 0 |
| 과거 버전 단계 upgrade | 36 | 36 | 0 |
| 적용 성공 합계 | 99 | 89 | 30 |
| 최신 no-op | 17 | 18 | 2 |
| 의도한 Migration 실패 | 5 | 5 | 0 |
| Flyway migrate 호출 합계 | 121 | 112 | 32 |
| 기존 복수형 로그 기준 (75회와 비교) | 75 | 65 | 30 |
| 실제 SQL Migration 적용 합계 | 2,205 | 1,815 | 1,170 |
| MySQL 기동 | 2 | 2 | 1 |
| Spring 기동 시간 합계 | 80.767초 | 60.476초 | 63.687초 |
| Flyway SQL execution time 합계 | 45.365초 | 35.962초 | 25.216초 |
| 실패 / 오류 / Skip | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |
| 의도적 Migration 미실행 | 0 | 0 | 24 |

전체는 같은 `./gradlew test --rerun-tasks --console=plain` 명령으로 비교했다. 36.25초(13.3%) 감소다. 일반 CI는 실제 `./gradlew --no-daemon assemble ciTest --rerun-tasks --console=plain`으로 측정했으며, 단일 사용 daemon·bootJar/assemble·compile 비용도 포함한다. `--rerun-tasks`로 검증 생략을 막았고 모두 dependency/image 캐시를 사용했다. baseline 때 daemon이 새로 시작됐고 최종 전체는 기존 daemon을 사용했으므로 모든 시간 차이를 Context 변경만의 인과 효과로 단정하지 않는다. 각 wall time은 1회 실측이며 Linux GitHub runner 시간이나 분포는 검증하지 않았다.

Flyway 121→112는 성공 적용 10회 감소와 공유 DB를 사용하는 두 번째 Context의 no-op 1회 증가를 합친 결과다. 기존 집계식 75→65도 함께 제시하되 올바른 전체 migrate 기준으로 사용하지 않는다. 과거 target 21회·단계 upgrade 36회·의도한 실패 5회는 전체에서 동일하다. 일반 DB 테스트의 Spring 자동 migrate 호출은 40→31회이며 이 값은 위 표의 별도 분류 축이므로 다른 행과 더하지 않는다.

독립 DB 11개를 최신 DB 1개로 바꾸면서 빈 DB 전체 적용이 10회, 실제 SQL 적용이 390개 줄었다. Spring Context는 11개를 설정별 2개로 줄여 전체 44→35회가 됐다. 정상 공유 Context가 사용하는 DB와 이력이 최신임을 계속 검증한다. MySQL 서버는 전체에서 계속 2개이며 일반 CI에서는 백업 복원 그룹을 제외하므로 공유 서버 1개다.

### 8.6 남은 비용과 범위

전체에는 실제 child JVM 종료·재시작, 대량 알림 backlog/FK/UNIQUE/트랜잭션·실패 주입과 과거 Upgrade/복원 비용이 남는다. 일반 CI에도 현재 기능의 중요 회귀는 그대로 있어 35개 Context가 필요하다. 현재 35개는 공유 Context 2개, 기존 독립 DB Context 29개, DB 없는 Spring Context 4개다. 서로 다른 Mock/clock/profile과 실패·경쟁 검증을 하나의 거대 Context에 합치지 않았다. 추가 절감 가능성은 있지만 실제 격리 검토 없이 이를 재사용 가능한 비용으로 계산하지 않는다.

새 Dependency나 외부 테스트 프레임워크는 도입하지 않았다. DB·Flyway·Entity·알고리즘을 수정하지 않았고 실제 Provider/FCM·개발 DB·실제 사용자 데이터는 사용하지 않았다. Task 목록의 완료 상태와 devlog는 변경하지 않았다. 공유 자원과 CI 정책에 관한 개발 가이드라인·로컬 실행 문서는 같은 작업에서 동기화했다.

### 8.7 최종 통과 범위와 필터링 확인

| 실행 | 실제 실행 사례 | 결과 |
| --- | ---: | --- |
| `test --rerun-tasks` (최종 전체) | 1,896 | 실패·오류·Skip 0 |
| `--no-daemon assemble ciTest --rerun-tasks` | 1,872 | 실패·오류·Skip 0, 의도적 Migration 제외 24 |
| `migrationTest` | 24 | 실패·오류·Skip 0 |
| `fastTest` | 1,029 | 실패·오류·Skip 0 |
| `dbTest --tests 'com.stopbell.boarding.entity.BoardingSessionPersistenceIntegrationTest'` | 28 | 실패·오류·Skip 0 |
| `--no-daemon build` | 최종 전체 성공 결과 재사용 | `test`와 build가 UP-TO-DATE, 실제 선택 dbTest는 별도 실행 |
| `python3 -m unittest discover -s tools/tests -p 'test_backend_test_ci.py'` | 7 | 통과 |

일반 CI·Migration·fast의 실제 XML을 전체 XML과 대조했다. 파라미터 display name에 포함된 JVM 객체 identity hash만 정규화한 뒤 `(classname, name)` multiset으로 `전체 = 일반 CI + Migration`, 양쪽 교집합 없음, `fast ⊆ 일반 CI`를 확인했다. 표기 hash가 JVM마다 달라 최초 문자 그대로의 비교는 불일치했으며 테스트 누락은 아니었다. 실패·Skip은 정규화하지 않고 모두 0임을 별도로 검사했다. 실제 실행 결과로 현재 Migration 소스 집계 24개도 일치함을 확인했다. Workflow YAML 문법은 Ruby 표준 YAML parser로 확인했다. 실제 GitHub Actions runner 실행은 하지 않았다.

최종 로그/원본 XML은 `/private/tmp/stopbell-optimized-full-final.log`, `/private/tmp/stopbell-full-final-results/`, `/private/tmp/stopbell-optimized-ci.log`와 각 Gradle task의 `backend/build/test-results/{test,ciTest,migrationTest,fastTest,dbTest}`에 남는다. 임시 파일과 build 출력은 Commit에 포함하지 않는다. Commit은 이 문서·공유 지원 코드·태그·Gradle·CI 도구/테스트·실행 문서만 포함하며 Push하지 않는다.
