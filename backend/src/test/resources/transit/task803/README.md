# TASK-803 fixture provenance

All files are DOCUMENTED_OBSERVATION_RECONSTRUCTION, not captured HTTP responses. Source: `docs/transit-provider-research.md`, TASK-303 PoC, 2026-09-09 KST, 경기 실제 Flow and 서울 운행 차량 조회/정류소 도착 tables. Envelopes and reduced item counts are constructed for offline parsing; no claim is made that these were complete responses. Actual TAGO roster contained 25 vehicles; Seoul roster contained 18. The TAGO sample has no routeid. Seoul roster and detail are distinct operations; sectOrd 21 and stOrd 22 are not interchangeable. The detail pair preserves the observed same-vehicle/stop and 1→0 sequence and source times.

Vehicle and stop identifiers are consistent test aliases; route context is a test alias. TAGO GPS is synthetic, deliberately rounded replacement rather than observed coordinates; Seoul GPS and vehicle numbers are omitted. No credential or request URL is included. DTO and Observation expectations are explicit in `ProviderObservedFixtureTest`.

Synthetic malformed, empty, security and boundary cases live in the client and mapper test source, not in these reconstructed fixtures. They do not assert observed provider behavior.

No OBSERVED_SANITIZED fixture is available here: the observation documents record fields and values but do not preserve the complete HTTP responses. TASK-803 remains open until a preserved actual response can be sanitized and verified as a golden fixture. No new provider call was authorized for this documentation correction.
