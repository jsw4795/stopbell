import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import 'boarding_session.dart';
import 'boarding_session_controller.dart';

class BoardingSessionView extends StatelessWidget {
  const BoardingSessionView({
    super.key,
    required this.controller,
    required this.onBack,
    required this.onReconfirm,
    required this.onNew,
    this.boardingName,
  });
  final BoardingSessionController controller;
  final String? boardingName;
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
    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: IconButton(
                  onPressed: c.busy ? null : onBack,
                  tooltip: '버스 도착 알림으로',
                  icon: const Icon(Icons.arrow_back),
                ),
              ),
              if (c.inaccessible) ...[
                const Text('세션이 없거나 접근할 수 없어요.'),
                OutlinedButton(
                  onPressed: c.busy ? null : c.refresh,
                  child: const Text('상태 새로고침'),
                ),
              ] else ...[
                BoardingSessionDetails(
                  session: s,
                  stateUncertain: c.stateUncertain,
                  boardingName: boardingName,
                  onRefresh: c.busy ? null : c.refresh,
                  showRefresh: true,
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
          ),
        ),
        if (!c.inaccessible && s.isOpen)
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: SizedBox(
                width: double.infinity,
                child: TextButton(
                  onPressed: c.busy || c.stateUncertain
                      ? null
                      : () => _cancel(context),
                  child: const Text('추적 취소'),
                ),
              ),
            ),
          ),
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
    this.boardingName,
    this.onRefresh,
    this.showRefresh = false,
  });
  final VoidCallback? onRefresh;
  final bool showRefresh;
  final BoardingSession session;
  final bool stateUncertain;
  final String? boardingName;

  @override
  Widget build(BuildContext context) {
    final s = session;
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final semantic = StopBellColors.of(context);
    final label = stateUncertain
        ? '확인 필요'
        : switch (s.status) {
            BoardingSessionStatus.tracking => '추적 중',
            BoardingSessionStatus.suspended => '보류',
            BoardingSessionStatus.completed => '종료',
            BoardingSessionStatus.cancelled => '취소',
            BoardingSessionStatus.expired => '만료',
            BoardingSessionStatus.unknown => '확인 필요',
          };
    final badgeColor =
        stateUncertain ||
            s.status == BoardingSessionStatus.suspended ||
            s.status == BoardingSessionStatus.unknown
        ? semantic.heldTracking
        : s.status == BoardingSessionStatus.tracking
        ? semantic.activeTracking
        : scheme.onSurfaceVariant;
    final progress =
        !stateUncertain &&
        s.status == BoardingSessionStatus.tracking &&
        s.evaluationStatus == BoardingEvaluation.ready &&
        s.remainingOccurrenceGap != null;
    final initial =
        !stateUncertain &&
        s.status == BoardingSessionStatus.tracking &&
        s.evaluationStatus == BoardingEvaluation.verificationPending &&
        s.initialRemainingGap != null;
    final notice = stateUncertain
        ? '변경 결과를 확인하지 못했어요. 상태를 다시 조회해 주세요.'
        : switch (s.status) {
            BoardingSessionStatus.suspended =>
              s.canReconfirm ? '동일 차량을 다시 확인해 주세요.' : '차량 정보 확인이 보류됐어요.',
            BoardingSessionStatus.completed => '추적이 종료됐어요. 실제 하차 확인을 뜻하지 않아요.',
            BoardingSessionStatus.cancelled => '사용자 취소로 종료됐어요.',
            BoardingSessionStatus.expired => '세션 수명 만료로 종료됐어요.',
            BoardingSessionStatus.unknown => '세션 상태를 확인해 주세요.',
            BoardingSessionStatus.tracking => switch (s.evaluationStatus) {
              BoardingEvaluation.verificationPending =>
                initial ? null : '차량 위치 확인 중',
              BoardingEvaluation.recoveryPending => '현재 차량 진행 정보를 다시 확인하고 있어요.',
              BoardingEvaluation.unknown => '진행 상태를 확인해 주세요.',
              _ => null,
            },
          };
    final alertTiming = s.notificationOffsetStops != null
        ? '${s.notificationOffsetStops}개 지점 전'
        : s.prepareNotificationEnabled
        ? '기존 알림 설정'
        : '준비 알림 꺼짐';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                '하차 알림 ($label)',
                style: theme.textTheme.headlineSmall!.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            if (showRefresh)
              IconButton(
                onPressed: onRefresh,
                tooltip: '상태 새로고침',
                icon: const Icon(Icons.refresh),
              ),
          ],
        ),
        const SizedBox(height: 16),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
          decoration: BoxDecoration(
            color: scheme.primaryContainer.withValues(alpha: .55),
            borderRadius: BorderRadius.circular(20),
          ),
          child: Row(
            children: [
              Icon(
                Icons.directions_bus_outlined,
                color: scheme.primary,
                size: 30,
              ),
              const SizedBox(width: 20),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      s.routeNumber,
                      style: theme.textTheme.titleLarge!.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Text(s.vehicleNumber, style: theme.textTheme.bodySmall),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              _Badge(label: label, color: badgeColor),
            ],
          ),
        ),
        if (notice != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(notice, style: theme.textTheme.bodySmall),
          ),
        const SizedBox(height: 20),
        if (!s.isTerminal)
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(20),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  initial ? '등록 시 확인된 차량 위치' : '최근 확인된 버스 위치',
                  style: theme.textTheme.labelLarge,
                ),
                const SizedBox(height: 6),
                Text(
                  initial && s.initialStopName != null
                      ? '${s.initialStopName} 부근'
                      : progress && s.currentStopName != null
                      ? '${s.currentStopName} 부근 지나는 중'
                      : '차량 위치 확인 중',
                  style: theme.textTheme.titleMedium,
                ),
                if (initial)
                  Text('등록 당시 관측 기준', style: theme.textTheme.bodySmall)
                else if (progress && s.currentStopName != null)
                  Text('최근 관측 기준', style: theme.textTheme.bodySmall),
                if (progress || initial) ...[
                  const Divider(height: 32),
                  Text('${s.targetName}까지', style: theme.textTheme.bodyMedium),
                  Text(
                    '${initial ? s.initialRemainingGap : s.remainingOccurrenceGap}개 지점 남음',
                    style: theme.textTheme.headlineSmall!.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  Text(
                    '노선 순서 기준 · 미정차 지점 포함',
                    style: theme.textTheme.bodySmall,
                  ),
                ],
              ],
            ),
          ),
        const SizedBox(height: 28),
        _TimelineRow(
          icon: Icons.check_circle,
          color: semantic.boardingStop,
          lineColor: semantic.boardingStop,
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _Badge(label: '승차', color: semantic.boardingStop),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  boardingName ?? '승차 정류장 정보 없음',
                  style: theme.textTheme.titleMedium,
                ),
              ),
            ],
          ),
        ),
        _TimelineRow(
          icon: Icons.notifications,
          color: semantic.boardingStop,
          lineColor: scheme.outline,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('하차 알림', style: theme.textTheme.titleMedium),
              Text(
                alertTiming,
                style: theme.textTheme.titleMedium!.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
        _TimelineRow(
          icon: Icons.circle,
          color: scheme.outline,
          last: true,
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _Badge(label: '목표', color: scheme.onSurfaceVariant),
              const SizedBox(width: 12),
              Expanded(
                child: Text(s.targetName, style: theme.textTheme.titleMedium),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.label, required this.color});
  final String label;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
    decoration: BoxDecoration(
      color: color,
      borderRadius: BorderRadius.circular(8),
    ),
    child: Text(
      label,
      style: Theme.of(context).textTheme.labelSmall!.copyWith(
        color: Theme.of(context).brightness == Brightness.dark
            ? Colors.black
            : Colors.white,
      ),
    ),
  );
}

class _TimelineRow extends StatelessWidget {
  const _TimelineRow({
    required this.icon,
    required this.color,
    required this.child,
    this.lineColor,
    this.last = false,
  });
  final IconData icon;
  final Color color;
  final Color? lineColor;
  final Widget child;
  final bool last;
  @override
  Widget build(BuildContext context) => IntrinsicHeight(
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          width: 32,
          child: Column(
            children: [
              Icon(icon, size: 24, color: color),
              if (!last) Expanded(child: Container(width: 2, color: lineColor)),
            ],
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Padding(
            padding: EdgeInsets.only(bottom: last ? 0 : 36),
            child: child,
          ),
        ),
      ],
    ),
  );
}
