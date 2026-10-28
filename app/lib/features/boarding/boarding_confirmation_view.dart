import 'package:flutter/material.dart';

import 'boarding_candidates.dart';
import 'boarding_candidates_view.dart';

class BoardingConfirmationView extends StatefulWidget {
  const BoardingConfirmationView({
    super.key,
    required this.candidate,
    required this.routeNumber,
    required this.targetName,
    required this.onConfirm,
    required this.onBack,
    this.busy = false,
    this.reconfirm = false,
    this.onNotificationOffsetChanged,
  });
  final BoardingCandidate candidate;
  final String routeNumber, targetName;
  final Future<void> Function(String) onConfirm;
  final VoidCallback onBack;
  final ValueChanged<int>? onNotificationOffsetChanged;
  final bool busy, reconfirm;
  @override
  State<BoardingConfirmationView> createState() =>
      _BoardingConfirmationViewState();
}

class _BoardingConfirmationViewState extends State<BoardingConfirmationView> {
  bool _sending = false;
  int _notificationOffsetStops = 2;
  Future<void> _confirm() async {
    if (_sending || widget.busy) return;
    if (!canConfirmBoardingCandidate(widget.candidate)) return;
    setState(() => _sending = true);
    try {
      await widget.onConfirm(widget.candidate.vehicleNumber!);
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      TextButton(
        onPressed: widget.busy || _sending ? null : widget.onBack,
        child: const Text('다른 후보 선택 · 다시 조회'),
      ),
      Text(
        widget.reconfirm ? '동일 차량 재확인' : '하차 알림 설정',
        style: Theme.of(context).textTheme.titleLarge,
      ),
      Text('노선: ${widget.routeNumber}'),
      Text('목적지: ${widget.targetName}'),
      Text('전체 차량번호: ${widget.candidate.vehicleNumber}'),
      Text('원래 관측 시각: ${boardingTime(widget.candidate.observedAt)}'),
      const Text('선택한 차량으로 버스 도착 전에도 알림을 등록할 수 있어요. 차량번호 입력은 필요 없어요.'),
      Text(
        widget.reconfirm ? '같은 차량의 진행을 다시 확인해 추적을 재개해요. 저장된 목적지와 알림 설정은 유지돼요.' : '등록하면 선택한 차량을 바로 추적해요. 위치 정보가 부족하면 차량 위치 확인 중으로 표시하며 다음 관측을 기다려요. 선택한 실제 목적지의 1정거장 또는 2정거장 전에 하차 준비 알림을 한 번 보내요. 목적지 도달 시에는 추적을 완료해요.',
      ),
      if (!widget.reconfirm) ...[
        const Text('알림 시점'),
        SegmentedButton<int>(
          key: const ValueKey('notification-offset-stops'),
          segments: const [
            ButtonSegment(value: 2, label: Text('2정거장 전 (기본)')),
            ButtonSegment(value: 1, label: Text('1정거장 전')),
          ],
          selected: {_notificationOffsetStops},
          onSelectionChanged: widget.busy || _sending
              ? null
              : (values) {
                  final offset = values.single;
                  setState(() => _notificationOffsetStops = offset);
                  widget.onNotificationOffsetChanged?.call(offset);
                },
        ),
      ],
      FilledButton(
        onPressed: widget.busy || _sending ? null : _confirm,
        child: Text(widget.reconfirm ? '동일 차량 재확인 요청' : '하차 알림 등록'),
      ),
    ],
  );
}
