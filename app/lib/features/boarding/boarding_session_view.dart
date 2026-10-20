import 'package:flutter/material.dart';

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
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('노선 ${s.routeNumber}'),
        Text('차량 ${s.vehicleNumber}'),
        Text('목적지 ${s.targetName} · 노선 순서 ${s.targetOrder}'),
        Text(
          '상태: ${switch (s.status) {
            BoardingSessionStatus.tracking => 'TRACKING · 차량 진행 확인 중',
            BoardingSessionStatus.suspended => 'SUSPENDED · 추적 보류',
            BoardingSessionStatus.completed => 'COMPLETED · 추적 종료',
            BoardingSessionStatus.cancelled => 'CANCELLED · 사용자 취소로 종료',
            BoardingSessionStatus.expired => 'EXPIRED · 세션 수명 만료',
            BoardingSessionStatus.unknown => '알 수 없는 세션 상태 · 확인 필요',
          }}',
        ),
        Text(switch (s.evaluationStatus) {
          BoardingEvaluation.ready => 'READY · 서버의 새 관측 검증 응답',
          BoardingEvaluation.recoveryPending =>
            'RECOVERY_PENDING · 현재 차량 진행 정보를 다시 확인하고 있어요.',
          BoardingEvaluation.held => 'HELD · 차량 진행 검증을 보류했어요.',
          BoardingEvaluation.ended =>
            'ENDED · 세션이 종료됐어요. 실제 승객 하차 확인을 뜻하지 않아요.',
          BoardingEvaluation.unknown => '현재 진행 검증 상태를 확인할 수 없어요.',
        }),
        if (stateUncertain)
          const Text('변경 결과 미확인 · 아래 정보는 마지막 확인 응답이에요. 상태를 다시 조회해 주세요.'),
        if (!stateUncertain &&
            s.status == BoardingSessionStatus.tracking &&
            s.evaluationStatus == BoardingEvaluation.ready &&
            s.remainingOccurrenceGap != null) ...[
          Text('검증된 노선 방문 지점 간격: ${s.remainingOccurrenceGap}'),
          const Text('실제 남은 정차 수·거리·정확한 위치·도착 시간이 아니에요.'),
        ],
        if (s.status == BoardingSessionStatus.suspended)
          Text(
            s.reasonCode?.needsReconfirmation == true
                ? '기존 차량의 연속성을 확인할 수 없어요. 실제 차량번호를 다시 확인해야 해요.'
                : s.reasonCode == BoardingReason.unknown || s.reasonCode == null
                ? '보류 사유를 확인할 수 없어요. 상태를 다시 확인해 주세요.'
                : '차량 정보 확인이 일시적으로 중단됐어요. 새로운 정보를 기다리고 있어요.',
          ),
        if (s.reasonCode != null) Text('사유: ${_reason(s.reasonCode!)}'),
        Text(
          '마지막 검증 checkpoint 관측 시각: ${boardingTime(s.checkpointObservedAt)}',
        ),
        const Text('과거 검증 근거 시각이며 현재 버스 위치 시각이 아니에요.'),
        Text(
          'Provider 제공 시각: ${s.providerDataTime == null ? '알 수 없음' : boardingTime(s.providerDataTime!)}',
        ),
        Text('세션 종료 예정 시각: ${boardingTime(s.expiresAt)}'),
        if (s.endedAt != null) Text('세션 종료 시각: ${boardingTime(s.endedAt!)}'),
      ],
    );
  }
}

String _reason(BoardingReason reason) => switch (reason) {
  BoardingReason.temporaryProviderFailure => '차량 정보 제공 일시 장애',
  BoardingReason.staleProgress => '진행 정보 지연',
  BoardingReason.vehicleMissing => '기존 차량 정보 소실',
  BoardingReason.identityConflict => '차량 식별 정보 충돌',
  BoardingReason.progressRegression => '차량 진행 역행',
  BoardingReason.traversalAmbiguous => '운행 경로·회차 불확실',
  BoardingReason.continuityGapExceeded => '진행 확인 공백 초과',
  BoardingReason.metadataChanged => '노선·정류장 정보 변경',
  BoardingReason.arrivalObserved => '목적지 도착 근거 관측',
  BoardingReason.passedObserved => '목적지 이후 진행 근거 관측',
  BoardingReason.userCancelled => '사용자 취소',
  BoardingReason.sessionLifetimeExpired => '세션 수명 만료',
  BoardingReason.unknown => '알 수 없는 사유',
};
