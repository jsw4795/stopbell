# 로드맵

## Phase 0 — 조사 및 PoC

목표: 핵심 아이디어가 기술적으로 실현 가능한지 증명한다.

- [x] 서울특별시·경기도 초기 지원 범위 및 Transit API 후보 조사 (TASK-301)
- [ ] API 약관, 요청 제한, 식별자, 검색 기능, 갱신 주기 확인 (TASK-302)
- [ ] 실제 Transit API 호출 PoC 및 서울·경기 응답 구조 확인 (TASK-303)
- [ ] V1 Transit Provider 및 identifier 전략 최종 결정 (TASK-304)
- [ ] 신뢰할 수 있는 V1 알림 실행 규칙과 Transit 관측 모델 정의 (TASK-305)
- [ ] 실제 기기에 테스트 FCM 푸시 알림 전송

완료 기준:

개발자가 실제 교통 데이터를 관찰하고 테스트 휴대폰에 독립적으로 푸시 알림을 보낼 수 있다.

## Backend Authentication 기반

Alarm 기능보다 먼저 Google Social Login과 StopBell 자체 Token 기반 인증을 구현한다.

- [x] Google Login 및 Backend Google ID Token 검증
- [x] StopBell JWT Access Token과 Refresh Token 발급·회전·무효화
- [x] Spring Security 기반 인증된 User 식별
- [ ] 인증된 User 기준 Alarm 소유권 처리

Transit API 조사와 실제 응답 관찰은 Transit Foundation에서 먼저 수행한다. 이후 V1 구현은 Alarm Backend, Transit Integration, Flutter Client, Notification 순으로 진행한다. Flutter Google Login, Secure Storage 기반 로그인 상태 유지, Token 갱신 및 Logout 연동은 이 흐름에서 Flutter Client 단계에 포함한다.

Transit Foundation의 결정 흐름은 다음과 같다.

```text
TASK-301 서울 + 경기 지원 범위와 API 후보 선정
        ↓
TASK-302 약관 / rate limit / identifier / 검색 / update frequency 조사
        ↓
TASK-303 실제 TAGO 등 API 호출 PoC 및 서울·경기 응답 구조 확인
        ↓
TASK-304 V1 Provider 및 identifier 전략 최종 결정
        ↓
TASK-305 Transit 관측 모델 및 Alarm Transit Target 계약 결정
```

TASK-301의 1순위 후보는 국토교통부 TAGO이지만, 최종 Provider 결정은 TASK-304까지 유보한다. 경기도 버스정보 API는 경기 데이터의 비교 또는 fallback 후보이며, 서울시 버스 API는 기존 API가 존재하나 신규 프로젝트의 인증키 발급 제약을 고려해 우선순위가 낮다.

## Phase 1 — 버스 알림 MVP

목표: 실제 종단 간 버스 알림.

- [x] Flutter 기본 애플리케이션
- [x] Spring Boot 백엔드 골격
- [x] MySQL 8.4 LTS Docker Compose 개발 환경
- [x] JPA / MyBatis 설정
- [ ] 버스 노선 검색
- [ ] 정류장 선택
- [ ] 알림 생성/목록/삭제
- [ ] 활성 알림 모니터링
- [ ] 실행 조건 평가
- [ ] 기기 등록
- [ ] FCM 전달
- [ ] 중복 방지
- [ ] 기본 로그와 오류 처리

완료 기준:

사용자가 실제 휴대폰에서 알림을 만들고 앱을 종료하거나 백그라운드로 전환한 뒤, 실제 버스 이동 이벤트에 따라 예상한 알림을 받을 수 있다.

## Phase 1.1 — 신뢰성

- [ ] 실제 환경 테스트에서 알림 지연 측정
- [ ] 외부 API 갱신 동작 기록
- [ ] 일시적인 제공자 장애 처리
- [ ] 서버 재시작을 안전하게 처리
- [ ] 중복 방지 개선
- [ ] 유용하다면 알림 이력 추가
- [ ] 알림 평가 자동화 테스트 추가

## Phase 1.2 — 사용자 경험

- [ ] 타당한 경우 즐겨찾는 노선/정류장
- [ ] 더 나은 활성 알림 UX
- [ ] 푸시 권한 UX
- [ ] 비어 있음/로딩/오류 상태
- [ ] 기본 접근성 검토

## Phase 1.3 — 배포 / 운영

- [ ] 백엔드 Docker 컨테이너화
- [ ] 영속적인 서버 환경에 백엔드 배포
- [ ] HTTPS 설정
- [ ] 운영 데이터베이스 설정
- [ ] 저장소 밖에서 시크릿 관리
- [ ] 상태 확인 추가
- [ ] CI 빌드/테스트 추가
- [ ] 수동 배포를 이해한 뒤에만 CD 추가

## Phase 2 — 지하철 기상 알림

V1 버스 알림이 신뢰성 있게 동작한 뒤에만 시작한다.

조사 주제:

- 실시간 지하철 데이터 가용성
- 사용자의 열차 식별 또는 추정
- 목적지 근접 로직
- 지하 환경의 GPS 제한
- 백그라운드 위치 권한 제한
- iOS/Android 차이
- 알림 시점 허용 오차

## 향후 — 제품에 필요할 때만

가능한 주제이며, 약속된 항목은 아니다.

- 그룹화된 교통 폴링
- 공유 캐시
- Redis
- 알림 큐/워커
- 여러 백엔드 인스턴스
- 추가 교통 지역/제공자
- 반복 통근 프리셋
