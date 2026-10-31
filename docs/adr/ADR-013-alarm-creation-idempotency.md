# ADR-013: Alarm 생성 Request Identity와 유한 보존

- 상태: 채택됨 (구현 예정)
- 날짜: 2026-10-31

## 배경

Alarm create는 현재 요청마다 새 row를 생성하며 응답 유실 후 목록의 유사 Alarm으로 원래 결과를 식별할 수 없다. 삭제 후에도 원래 요청을 재전송해 새 Alarm을 만들면 안 된다. Flutter와 서버를 점진적으로 배포해야 한다.

## 검토한 선택지

- Client에서 입력을 잠그고 목록만 확인: 구현이 작지만 재시작·응답 유실·동시 요청을 안전하게 식별하지 못한다.
- User별 UUID와 별도 durable 요청 기록: DB UNIQUE와 원자적 transaction으로 같은 요청을 식별하며 조회와 삭제 tombstone을 지원한다. 저장·cleanup과 Client 영속화가 필요하다.
- Alarm row에만 UUID 저장: 단순하지만 Alarm 삭제 시 identity가 사라진다. 삭제를 막거나 lifecycle을 바꾸어야 한다.

## 결정

B안을 채택한다. 요청 body의 UUID v4와 인증 User를 identity로 하며 정규화된 요청 fingerprint를 비교한다. 201과 현재 Alarm을 반환하고 삭제 결과는 410이다. 무기한 snapshot은 저장하지 않는다. 기존 ID 없는 Client는 독립 생성으로 유지하고 서버를 먼저 배포한다. 상세 공개 계약은 [API](../api.md#alarm-생성-멱등성-확정-계약-task-802-2단계-미구현), DB와 transaction은 [Database](../database.md#alarm_creation_requests-task-802-설계-미구현), [Architecture](../architecture.md#alarm-생성-멱등성-실행-설계-task-802-미구현)가 소유한다.

보존은 기록 시각부터 30일을 선택한다. 7일은 장기 미사용 뒤 복구 기회가 짧고 저장량은 작다. 90일은 복구 기회가 길지만 저장·개인정보 보유가 3배다. 무기한은 장기 복구에 유리하나 저장량과 개인정보가 계속 증가하고 cleanup이 없다. 30일은 복구 기회와 저장량/보유의 절충이며 수요 및 실제 저장량에 따라 재검토한다. 유한 보존에서는 삭제 tombstone도 cleanup 이후 사라지고 같은 ID가 새 요청으로 처리될 수 있다. 7·30·90일 모두 배치 cleanup 및 경합 처리가 필요하다. 모든 기간 선택은 기기 장기 미사용 또는 실제 cleanup 경과 후 무기한 중복 방지를 제공하지 않는다.

## 결과

같은 User/ID의 성공은 기록 보존 중 중복 생성되지 않으며 다른 ID나 다른 User의 생성 의도는 병합하지 않는다. 응답은 원본 snapshot이 아니라 조회 시점의 상태다. 서버 재시작, rollback과 응답 유실을 DB 기록으로 구분하되 GET 404는 진행 중인 요청을 배제하지 못한다. 30일 이후 재전송의 위험, 레거시의 비멱등성과 cleanup 운영 비용을 수용한다. Alarm creation identity는 [ADR-010](ADR-010-notification-device-and-durable-delivery.md)의 Event/Delivery identity 및 FCM/실제 기기 표시 보장과 독립이다.

## 재검토 시점

Client 지원 종료, 실제 복구 기간·저장량 관측, User 삭제 정책, Alarm ID 재사용 또는 보존/법적 요구가 달라질 때 재검토한다.
