import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import 'boarding_candidates_view.dart';
import 'boarding_session.dart';
import 'boarding_session_controller.dart';

class BoardingSessionView extends StatelessWidget {
  const BoardingSessionView({
    super.key,
    required this.controller,
    required this.onBack,
    required this.onReconfirm,
    required this.onNew,
  });
  final BoardingSessionController controller;
  final VoidCallback onBack, onReconfirm, onNew;
  Future<void> _cancel(BuildContext context) async {
    final expected = controller.session;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('현재 차량 추적을 취소할까요?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('계속 추적'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
              foregroundColor: Theme.of(context).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('추적 취소 확인'),
          ),
        ],
      ),
    );
    // A dialog is bound to this screen/session. Revalidate after its async gap.
    if (context.mounted &&
        controller.current &&
        confirmed == true &&
        controller.session?.id == expected?.id) {
      await controller.cancel();
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = controller;
    final s = c.session!;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        TextButton(
          onPressed: c.busy ? null : onBack,
          child: const Text('버스 도착 알림으로'),
        ),
        Text('하차 알림 · 세션 상세', style: Theme.of(context).textTheme.titleLarge),
        if (c.inaccessible) ...[
          const Text('세션이 없거나 접근할 수 없어요.'),
          OutlinedButton(
            onPressed: c.busy ? null : c.refresh,
            child: const Text('상태 새로고침'),
          ),
        ] else ...[
          BoardingSessionDetails(session: s, stateUncertain: c.stateUncertain),
          OutlinedButton(
            onPressed: c.busy ? null : c.refresh,
            child: const Text('상태 새로고침'),
          ),
          if (s.isOpen)
            OutlinedButton(
              onPressed: c.busy || c.stateUncertain
                  ? null
                  : () => _cancel(context),
              child: const Text('추적 취소'),
            ),
          if (s.canReconfirm)
            FilledButton(
              onPressed: c.busy || c.stateUncertain ? null : onReconfirm,
              child: const Text('동일 차량 다시 확인'),
            ),
          if (s.isTerminal)
            OutlinedButton(
              onPressed: c.canCreate ? onNew : null,
              child: const Text('새 차량 후보 찾기'),
            ),
        ],
        if (c.message != null) Text(c.message!),
        if (c.pending != null)
          OutlinedButton(
            onPressed: c.busy ? null : c.recover,
            child: const Text('저장된 생성 요청 다시 확인'),
          ),
        if (c.busy) const Text('서버 응답 확인 중...'),
      ],
    );
  }
}

/// Server-returned state shared by management and notification read-only detail.
class BoardingSessionDetails extends StatelessWidget {
  const BoardingSessionDetails({
    super.key,
    required this.session,
    this.stateUncertain = false,
  });
  final BoardingSession session;
  final bool stateUncertain;
  @override
  Widget build(BuildContext context) {
    final s = session;
    final theme = Theme.of(context);
    final colors = StopBellColors.of(context);
    final status = switch (s.status) {
      BoardingSessionStatus.tracking => '선택한 차량 추적 중',
      BoardingSessionStatus.suspended => '추적 보류',
      BoardingSessionStatus.completed => '추적 종료',
      BoardingSessionStatus.cancelled => '사용자 취소로 종료',
      BoardingSessionStatus.expired => '세션 수명 만료',
      BoardingSessionStatus.unknown => '알 수 없는 세션 상태 · 확인 필요',
    };
    final statusColor =
        stateUncertain || s.status == BoardingSessionStatus.suspended
        ? colors.heldTracking
        : s.status == BoardingSessionStatus.tracking
        ? colors.activeTracking
        : theme.colorScheme.onSurfaceVariant;
    final progress =
        !stateUncertain &&
        s.status == BoardingSessionStatus.tracking &&
        s.evaluationStatus == BoardingEvaluation.ready &&
        s.remainingOccurrenceGap != null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _DetailCard(
          children: [
            Text('하차 추적', style: theme.textTheme.labelLarge),
            const SizedBox(height: 8),
            Text('노선 ${s.routeNumber}', style: theme.textTheme.headlineSmall),
            Text('차량 ${s.vehicleNumber}', style: theme.textTheme.titleMedium),
            const SizedBox(height: 16),
            Text(
              stateUncertain ? '상태 확인 필요' : status,
              style: theme.textTheme.titleMedium!.copyWith(color: statusColor),
            ),
            const SizedBox(height: 4),
            if (stateUncertain)
              const Text('변경 결과 미확인 · 아래 정보는 마지막 확인 응답이에요. 상태를 다시 조회해 주세요.')
            else
              Text(switch (s.evaluationStatus) {
                BoardingEvaluation.verificationPending =>
                  '차량 위치 확인 중 · 위치 근거가 확보되면 알림을 판단해요.',
                BoardingEvaluation.ready => '서버에서 관측 정보를 검증했어요.',
                BoardingEvaluation.recoveryPending =>
                  '현재 차량 진행 정보를 다시 확인하고 있어요.',
                BoardingEvaluation.held => '차량 진행 검증을 보류했어요.',
                BoardingEvaluation.ended => '실제 승객 하차 확인을 뜻하지 않아요.',
                BoardingEvaluation.unknown => '현재 진행 검증 상태를 확인할 수 없어요.',
              }),
            if (s.status == BoardingSessionStatus.suspended) ...[
              const SizedBox(height: 8),
              Text(
                s.reasonCode?.needsReconfirmation == true
                    ? '기존 차량의 연속성을 확인할 수 없어요. 목록에서 동일 차량을 다시 선택해 주세요.'
                    : s.reasonCode == BoardingReason.unknown ||
                          s.reasonCode == null
                    ? '보류 사유를 확인할 수 없어요. 상태를 다시 확인해 주세요.'
                    : '차량 정보 확인이 일시적으로 중단됐어요. 새로운 정보를 기다리고 있어요.',
              ),
            ],
            if (s.reasonCode != null) Text('사유: ${_reason(s.reasonCode!)}'),
          ],
        ),
        _DetailCard(
          children: [
            Text(
              '목표 정류장',
              style: theme.textTheme.labelLarge!.copyWith(
                color: colors.targetStop,
              ),
            ),
            const SizedBox(height: 8),
            Text(s.targetName, style: theme.textTheme.titleLarge),
            Text('노선 순서 ${s.targetOrder}', style: theme.textTheme.bodySmall),
            if (progress) ...[
              const Divider(height: 32),
              Text(
                '검증된 노선 방문 지점 간격: ${s.remainingOccurrenceGap}',
                style: theme.textTheme.titleMedium,
              ),
              const SizedBox(height: 4),
              const Text('실제 남은 정차 수·거리·정확한 위치·도착 시간이 아니에요.'),
            ],
          ],
        ),
        _DetailCard(
          children: [
            Text('하차 준비 알림', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(
              s.notificationOffsetStops == null
                  ? '기존 정책 · 사전 알림: ${s.prepareNotificationEnabled ? 'ON' : 'OFF'} · 목적지 알림: ON'
                  : '알림 시점: ${s.notificationOffsetStops}정거장 전 · 세션당 한 번',
            ),
            const SizedBox(height: 4),
            Text(
              '준비 알림: ${switch (s.preparationNotificationStatus) {
                'NOT_CREATED' => '아직 생성되지 않음',
                'PENDING' => '발송 대기 중',
                'ACCEPTED' => '전송 서비스 접수 완료 · 기기 표시 미확인',
                'NO_RECIPIENT' => '알림 생성 · 수신 가능한 기기 없음',
                'FAILED' => '발송 종료 · 실패',
                'EXPIRED' => '발송 기한 만료',
                _ => '발송 여부 확인 중',
              }}',
            ),
          ],
        ),
        _DetailCard(
          children: [
            Text('마지막 검증 관측', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(
              s.checkpointObservedAt == null
                  ? '검증된 관측 시각 없음'
                  : '마지막 검증 관측 시각: ${boardingTime(s.checkpointObservedAt!)}',
            ),
            const Text('과거 검증 근거 시각이며 현재 버스 위치 시각이 아니에요.'),
            const SizedBox(height: 8),
            Text(
              'Provider 제공 시각: ${s.providerDataTime == null ? '알 수 없음' : boardingTime(s.providerDataTime!)}',
              style: theme.textTheme.bodySmall,
            ),
            Text(
              '세션 종료 예정 시각: ${boardingTime(s.expiresAt)}',
              style: theme.textTheme.bodySmall,
            ),
            if (s.endedAt != null)
              Text(
                '세션 종료 시각: ${boardingTime(s.endedAt!)}',
                style: theme.textTheme.bodySmall,
              ),
          ],
        ),
      ],
    );
  }
}

class _DetailCard extends StatelessWidget {
  const _DetailCard({required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 12),
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: children,
      ),
    ),
  );
}

String _reason(BoardingReason reason) => switch (reason) {
  BoardingReason.baselineUnverified => '차량 위치 확인 중',
  BoardingReason.targetNotAhead => '검증 전 목적지 도달 또는 통과 · 알림 판단 불가',
  BoardingReason.temporaryProviderFailure => '차량 정보 제공 일시 장애',
  BoardingReason.staleProgress => '진행 정보 지연',
  BoardingReason.vehicleMissing => '기존 차량 정보 소실',
  BoardingReason.identityConflict => '차량 식별 정보 충돌',
  BoardingReason.progressRegression => '차량 진행 역행',
  BoardingReason.traversalAmbiguous => '운행 경로·회차 불확실',
  BoardingReason.continuityGapExceeded => '진행 확인 공백 초과',
  BoardingReason.metadataChanged => '노선·정류장 정보 변경',
  BoardingReason.arrivalObserved => '목적지 도달 근거 관측',
  BoardingReason.passedObserved => '목적지 이후 진행 근거 관측',
  BoardingReason.userCancelled => '사용자 취소',
  BoardingReason.sessionLifetimeExpired => '세션 수명 만료',
  BoardingReason.unknown => '알 수 없는 사유',
};
