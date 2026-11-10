import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
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
    this.onSelect,
  });
  final BoardingCandidates result;
  final DateTime receivedAt;
  final ValueChanged<BoardingCandidate>? onSelect;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = result.candidateSetStatus;
    final canChoose = status != CandidateSetStatus.unknown;
    final statusMessage = switch (status) {
      CandidateSetStatus.available => null,
      CandidateSetStatus.empty => '현재 확인 가능한 차량 후보가 없어요.',
      CandidateSetStatus.partial => '일부 차량 정보는 확인되지 않았어요. 전체 목록이 아닐 수 있어요.',
      CandidateSetStatus.unknown => '후보 목록의 상태를 확인할 수 없어요. 다시 조회해 주세요.',
    };
    final providerTime = result.providerDataTime;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: StopBellSpacing.md,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: StopBellSpacing.xs,
          children: [
            Text('차량 후보 선택', style: theme.textTheme.headlineSmall),
            if (statusMessage != null)
              Semantics(liveRegion: true, child: Text(statusMessage)),
            if (canChoose && result.candidates.isNotEmpty)
              Text(
                result.candidates.any(canConfirmBoardingCandidate)
                    ? '전체 차량번호를 보고 원하는 차량을 직접 선택하세요.'
                    : '현재 선택 가능한 차량이 없어요. 차량별 보류 사유를 확인해 주세요.',
                style: theme.textTheme.bodySmall,
              ),
          ],
        ),
        for (final (index, candidate) in result.candidates.indexed)
          _CandidateCard(
            candidate: candidate,
            index: index,
            canChoose: canChoose,
            onSelect: onSelect,
          ),
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: StopBellSpacing.xs,
          children: [
            Text(
              '목록 서버 관측 시각 · ${boardingTime(result.observedAt)}',
              style: theme.textTheme.bodySmall,
            ),
            if (providerTime != null)
              Text(
                '목록 공통 제공 시각 · ${boardingTime(providerTime)}',
                style: theme.textTheme.bodySmall,
              ),
            Text(
              '화면 수신 시각 · ${boardingTime(receivedAt)}',
              style: theme.textTheme.bodySmall,
            ),
          ],
        ),
      ],
    );
  }
}

class _CandidateCard extends StatelessWidget {
  const _CandidateCard({
    required this.candidate,
    required this.index,
    required this.canChoose,
    this.onSelect,
  });

  final ValueChanged<BoardingCandidate>? onSelect;
  final BoardingCandidate candidate;
  final int index;
  final bool canChoose;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final semantic = StopBellColors.of(context);
    final item = candidate;
    final canSelect = canChoose && canConfirmBoardingCandidate(item);
    final accent = canSelect ? semantic.success : semantic.warning;
    final canIdentify =
        item.confirmationStatus == ConfirmationStatus.available &&
        item.vehicleNumber != null &&
        item.candidateRef?.trim().isNotEmpty == true;
    final progress = item.progress;
    final gap = item.boardingOccurrenceGap;
    final providerTime = item.providerDataTime;
    final reason = item.reasonCode;
    final trackingReason = item.trackingReasonCode;
    final notes = {
      if (reason != null)
        _reasonMessage(reason)
      else if (item.vehicleNumber == null)
        _reasonMessage('VEHICLE_NUMBER_MISSING')
      else if (!canIdentify)
        '차량을 확인할 수 없어요. 후보를 다시 조회해 주세요.',
      if (trackingReason != null) _reasonMessage(trackingReason),
      for (final detail in item.limitations)
        if (detail != 'PROVIDER_TIME_MISSING' || providerTime != null)
          _reasonMessage(detail),
    };

    return Card(
      key: ValueKey('candidate-$index'),
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(StopBellRadius.card),
        side: BorderSide(color: colors.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(StopBellSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          spacing: StopBellSpacing.md,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: StopBellSpacing.xs,
              children: [
                Text(
                  item.vehicleNumber ?? '차량번호 확인 불가',
                  style: theme.textTheme.headlineSmall,
                ),
                Text('후보 ${index + 1}', style: theme.textTheme.labelMedium),
                Row(
                  children: [
                    Icon(
                      canSelect
                          ? Icons.check_circle_outline
                          : Icons.info_outline,
                      color: accent,
                      size: 18,
                    ),
                    const SizedBox(width: StopBellSpacing.sm),
                    Expanded(
                      child: Text(
                        canSelect ? '선택 가능' : '선택 보류',
                        style: theme.textTheme.labelLarge?.copyWith(
                          color: accent,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
            DecoratedBox(
              decoration: BoxDecoration(
                color: colors.surfaceContainer,
                borderRadius: BorderRadius.circular(StopBellRadius.field),
              ),
              child: Padding(
                padding: const EdgeInsets.all(StopBellSpacing.md),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: StopBellSpacing.xs,
                  children: [
                    Text('최근 진행 참고', style: theme.textTheme.labelSmall),
                    if (progress == null)
                      Text('진행 위치 미확인', style: theme.textTheme.bodySmall)
                    else ...[
                      Text(
                        '${progress.stopName} · 노선 순서 ${progress.order}',
                        style: theme.textTheme.titleSmall,
                      ),
                      Text(switch (progress.kind) {
                        ProgressKind.routeProgress => '노선 진행 참고 정보',
                        ProgressKind.arrivalObserved => '제공기관의 도착 관측 기록',
                        ProgressKind.movingReference => '이동 중 제공된 진행 기준 정보',
                        ProgressKind.unknown => '진행 정보의 의미 미확인',
                      }, style: theme.textTheme.bodySmall),
                    ],
                    if (gap != null) ...[
                      Text(switch (gap) {
                        > 0 => '승차 지점 이전의 관측 기준',
                        < 0 => '승차 지점 이후의 관측 기준',
                        _ => '승차 지점과 같은 관측 기준',
                      }, style: theme.textTheme.bodySmall),
                      Text(
                        '노선상 간격 ${gap.abs()} · 노선 방문 지점 배열 기준',
                        style: theme.textTheme.bodySmall,
                      ),
                    ],
                    if (progress != null || gap != null)
                      Text(
                        '관측 기준이며 실제 위치·정차 여부나 도착 예정 시간을 알 수 없어요.',
                        style: theme.textTheme.bodySmall,
                      ),
                  ],
                ),
              ),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: StopBellSpacing.xs,
              children: [
                Text(
                  '서버 관측 시각 · ${boardingTime(item.observedAt)}',
                  style: theme.textTheme.bodySmall,
                ),
                Text(
                  '제공기관 원본 시각 · ${providerTime == null ? '미확인' : boardingTime(providerTime)}',
                  style: theme.textTheme.bodySmall,
                ),
              ],
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: StopBellSpacing.xs,
              children: [
                if (!canIdentify ||
                    item.trackingSupport != TrackingSupport.conditional)
                  Text(
                    '차량 식별 · ${canIdentify ? '확인 가능' : '확인 보류'}',
                    style: theme.textTheme.bodySmall,
                  ),
                Text(
                  '하차 추적 지원 · ${switch (item.trackingSupport) {
                    TrackingSupport.conditional => '조건부 지원',
                    TrackingSupport.held => '보류',
                    TrackingSupport.unknown => '미확인',
                  }}',
                  style: theme.textTheme.bodySmall,
                ),
                for (final note in notes)
                  Text(note, style: theme.textTheme.bodySmall),
              ],
            ),
            if (onSelect != null)
              OutlinedButton(
                key: ValueKey('select-${item.candidateRef ?? index}'),
                onPressed: canSelect ? () => onSelect!(item) : null,
                child: Text(canSelect ? '이 차량 선택' : '선택 불가'),
              ),
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
  'DETAIL_DEADLINE_NOT_STARTED' =>
    '조회 시간 안에 상세 조회를 시작하지 못했어요. 필요하면 다시 조회해 주세요.',
  'DETAIL_DEADLINE_INCOMPLETE' => '조회 시간 안에 상세 정보를 확인하지 못했어요. 필요하면 다시 조회해 주세요.',
  'DETAIL_EMPTY' => '추가 진행 정보가 제공되지 않았어요.',
  'DETAIL_HTTP_FAILURE' => '상세 정보 서버 요청에 실패했어요.',
  'DETAIL_TRANSPORT_FAILURE' => '상세 정보 통신에 실패했어요.',
  'DETAIL_PROVIDER_FAILURE' => '정보 제공자가 상세 조회를 처리하지 못했어요.',
  'DETAIL_PROTOCOL_FAILURE' => '상세 정보 형식을 확인할 수 없어요.',
  'DETAIL_ID_MISMATCH' => '조회한 차량과 상세 차량 식별 정보가 일치하지 않아요.',
  'DETAIL_NUMBER_MISMATCH' => '목록과 상세 정보의 차량번호가 일치하지 않아요.',
  'TAGO_ALIGHT_UNVERIFIED' => '이 노선의 하차 알림 추적은 아직 검증되지 않아 사용할 수 없어요.',
  'TARGET_NOT_AHEAD' => '목적지가 현재 진행 방향의 앞쪽인지 확인되지 않았어요.',
  'REVALIDATION_REQUIRED' => '차량 확인 시 최신 정보를 다시 검증해야 해요.',
  _ => '해석할 수 없는 제한 사유가 있어요. 지원 여부를 확인할 수 없어요.',
};

bool canConfirmBoardingCandidate(BoardingCandidate item) =>
    item.canRequestConfirmation;
