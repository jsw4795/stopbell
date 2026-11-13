# GBIS 차량 위치 합성 fixture

실제 API 호출이나 실응답 저장 없이 작성한 `getBusLocationListv2` JSON이다. 파주 17번의 `routeId=229000013`을 사용하며 차량번호는 테스트용이다.

- `single-object`: 1대가 단일 객체로 반환되는 형식
- `array`: 2대가 배열로 반환되는 형식
- `empty-array`, `missing-list`, `null-list`, `missing-body`, `no-data`: 빈 목록 및 목록·본문 누락, 결과 없음 코드
- `missing-optional-fields`: 차량번호·노선 ID만 있고 위치·상태·좌석·혼잡도 등 선택 필드가 누락된 형식

Client 테스트와 DB/API 통합 테스트에서 동일 fixture를 사용한다. Service 테스트는 단일 객체 → 배열 → 빈 배열 순서로 응답하여 15초 TTL 경계에서 차량 수가 갱신되는지 검증한다.
