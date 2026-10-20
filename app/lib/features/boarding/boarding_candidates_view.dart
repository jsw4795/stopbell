import 'package:flutter/material.dart';

import 'boarding_candidates.dart';

String boardingTime(DateTime time) {
  final local = time.toLocal();
  String two(int value) => value.toString().padLeft(2, '0');
  return '${local.year}-${two(local.month)}-${two(local.day)} '
      '${two(local.hour)}:${two(local.minute)}:${two(local.second)} (기기 시간)';
}

class BoardingCandidatesView extends StatelessWidget {
  const BoardingCandidatesView({
    super.key,
    required this.result,
    required this.receivedAt,
  });
  final BoardingCandidates result;
  final DateTime receivedAt;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text(switch (result.candidateSetStatus) {
        CandidateSetStatus.available => '차량 후보 비교',
        CandidateSetStatus.empty => '현재 확인 가능한 차량 후보가 없어요.',
        CandidateSetStatus.partial => '일부 차량 정보는 확인되지 않았어요. 전체 목록이 아닐 수 있어요.',
        CandidateSetStatus.unknown => '후보 목록의 상태를 확인할 수 없어요. 다시 조회해 주세요.',
      }, style: Theme.of(context).textTheme.titleMedium),
      Text('서버 관측 시각: ${boardingTime(result.observedAt)}'),
      Text(
        'Provider 제공 시각: ${result.providerDataTime == null ? '알 수 없음' : boardingTime(result.providerDataTime!)}',
      ),
      Text('화면 수신 시각: ${boardingTime(receivedAt)}'),
      const Text(
        '목록 순서는 탑승 가능성이나 신뢰도를 뜻하지 않아요. 실제 차량에 표시된 전체 차량번호를 직접 대조해야 해요.',
      ),
      const Text(
        '진행 정보는 정확한 현재 위치·실제 정차·탑승 사실을 보장하지 않아요. 원본 갱신 시각이 없거나 정보가 지연될 수 있어요.',
      ),
      for (var index = 0; index < result.candidates.length; index++)
        _CandidateCard(candidate: result.candidates[index], index: index),
    ],
  );
}

class _CandidateCard extends StatelessWidget {
  const _CandidateCard({required this.candidate, required this.index});
  final BoardingCandidate candidate;
  final int index;

  @override
  Widget build(BuildContext context) {
    final item = candidate;
    final canCompare =
        item.confirmationStatus == ConfirmationStatus.available &&
        item.vehicleNumber != null &&
        item.candidateRef != null;
    final progress = item.progress;
    final reasons = <String>{
      if (item.reasonCode != null) item.reasonCode!,
      if (item.trackingReasonCode != null) item.trackingReasonCode!,
      ...item.limitations,
    };
    return Card(
      key: ValueKey('candidate-$index'),
      color: canCompare
          ? null
          : Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              item.vehicleNumber ?? '차량번호 없음 · 식별 불가',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            Text(
              '차량번호 확인: ${canCompare
                  ? '대조 가능 (탑승 미확정)'
                  : item.confirmationStatus == ConfirmationStatus.unknown
                  ? '상태 미확인 · 확인 보류'
                  : '확인 보류'}',
            ),
            Text(
              '하차 추적 지원: ${switch (item.trackingSupport) {
                TrackingSupport.conditional => '추가 검증 필요 · 시작 보장 없음',
                TrackingSupport.held => '보류',
                TrackingSupport.unknown => '알 수 없음 · 보류',
              }}',
            ),
            Text(
              progress == null
                  ? '진행 참고: 위치 정보 없음'
                  : '진행 참고: ${progress.stopName} · 노선 순서 ${progress.order}\n${switch (progress.kind) {
                      ProgressKind.routeProgress => '노선 진행 참고 정보',
                      ProgressKind.arrivalObserved => '제공자가 도착 상태를 관측한 참고 정보',
                      ProgressKind.movingReference => '이동 중 제공된 진행 기준 정보',
                      ProgressKind.unknown => '진행 정보의 의미를 확인할 수 없음',
                    }}',
            ),
            if (item.boardingOccurrenceGap != null) ...[
              Text(
                '선택한 승차 정류장과 관측된 진행 지점의 노선상 간격: ${item.boardingOccurrenceGap}',
              ),
              const Text('노선 방문 지점 배열의 간격이며 실제 정차 수·거리·남은 시간이 아니에요.'),
            ],
            Text('서버 관측 시각: ${boardingTime(item.observedAt)}'),
            Text(
              'Provider 제공 시각: ${item.providerDataTime == null ? '알 수 없음' : boardingTime(item.providerDataTime!)}',
            ),
            for (final reason in reasons) Text('• ${_reasonMessage(reason)}'),
          ],
        ),
      ),
    );
  }
}

String _reasonMessage(String code) => switch (code) {
  'VEHICLE_ID_MISSING' => '차량 식별 정보가 없어 확인을 보류해요.',
  'VEHICLE_NUMBER_MISSING' => '차량번호를 확인할 수 없어요.',
  'IDENTITY_CONFLICT' => '차량 식별 정보가 서로 일치하지 않아요.',
  'POSITION_UNAVAILABLE' => '현재 진행 위치를 확인할 수 없어요.',
  'METADATA_MISMATCH' => '진행 정보가 노선·정류장 정보와 일치하지 않아요.',
  'PROVIDER_TIME_MISSING' => '원본 정보의 갱신 시각을 알 수 없어요.',
  'PROVIDER_TIME_INVALID' => '제공된 시각을 검증할 수 없어요.',
  'STALE_OBSERVATION' => '차량 정보가 오래되어 다시 확인해야 해요.',
  'FUTURE_OBSERVATION' => '관측 시각이 미래여서 최신 정보로 사용할 수 없어요.',
  'DETAIL_NOT_REQUESTED' => '상세 진행 정보는 조회하지 않았어요.',
  'DETAIL_EMPTY' => '추가 진행 정보가 제공되지 않았어요.',
  'DETAIL_HTTP_FAILURE' => '상세 정보 서버 요청에 실패했어요.',
  'DETAIL_TRANSPORT_FAILURE' => '상세 정보 통신에 실패했어요.',
  'DETAIL_PROVIDER_FAILURE' => '정보 제공자가 상세 조회를 처리하지 못했어요.',
  'DETAIL_PROTOCOL_FAILURE' => '상세 정보 형식을 확인할 수 없어요.',
  'DETAIL_ID_MISMATCH' => '조회한 차량과 상세 차량 식별 정보가 일치하지 않아요.',
  'DETAIL_NUMBER_MISMATCH' => '목록과 상세 정보의 차량번호가 일치하지 않아요.',
  'TAGO_ALIGHT_UNVERIFIED' => '이 차량의 하차 알림 추적은 아직 검증되지 않았어요.',
  'TARGET_NOT_AHEAD' => '목적지가 현재 진행 방향의 앞쪽인지 확인되지 않았어요.',
  'REVALIDATION_REQUIRED' => '차량 확인 시 최신 정보를 다시 검증해야 해요.',
  _ => '해석할 수 없는 제한 사유가 있어요. 지원 여부를 확인할 수 없어요.',
};
