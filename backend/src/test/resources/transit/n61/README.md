# N61 실측 대조 — 2026-11-14

개발자 승인 범위의 서울 Provider 호출은 roster 1건과 차량 4601의 detail 1건이다.
두 XML은 응답에서 진단에 필요한 필드만 허용 목록으로 추출했다.
요청 URL·인증키·좌표는 저장하지 않았다. XML 원문 전체를 저장한 파일이 아니다.
`occurrences.tsv`는 같은 조사에서 개발 DB를 SELECT한 N61 187개 occurrence다.
DB 내부 ID는 제외했으며 개발 DB 변경은 없다.

| 항목 | roster | detail | DB 및 기존 T Data CSV |
| --- | --- | --- | --- |
| 차량 | 서울74사4601 / 120003281 | 동일 | 해당 없음 |
| 시각 | 20261114000925 | 동일 | 실시간 자료 아님 |
| 순번 | sectOrd=9 | stOrd=9 | order 8: 116000147, order 9: 116000636 |
| 정류장 ID | nextStId=0 | stId=116000147 | detail ID는 order 8에 유일하게 존재 |
| 상태 | stopFlag=0 | stopFlag=0 | 해당 없음 |

보류의 직접 원인은 detail ID/order 쌍과 DB occurrence의 불일치다.
ID 문자열 형식은 같으며 기존 CSV의 8·9번 쌍도 DB와 같다.
왕복 순번 체계 차이, 이동 중 필드 갱신 시차, API와 CSV의 노선 버전 차이,
일정한 offset 중 어느 것이 근본 원인인지는 이 한 표본으로 확정할 수 없다.
roster sectOrd와 detail stOrd가 같다는 관측도 두 필드의 의미가 같음을 입증하지 않는다.
4636·4633의 detail은 호출하지 않았으므로 같은 원인으로 확정하지 않는다.

회귀 테스트는 실제 XML 파서와 후보 판정에서 이 불일치를 재현한다. 단일 표본에 대한 -1 보정이나 ID-only fallback을 적용하지 않는다.
정확한 ID/order 쌍을 요구하는 현행 Domain 계약의 변경 및 보류 해소는 추가 근거와
개발자 판단 전까지 미완료다. TASK-908 완료 상태를 변경하지 않는다.
