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
    this.onPrepareNotificationChanged,
  });
  final BoardingCandidate candidate;
  final String routeNumber, targetName;
  final Future<void> Function(String) onConfirm;
  final VoidCallback onBack;
  final ValueChanged<bool>? onPrepareNotificationChanged;
  final bool busy, reconfirm;
  @override
  State<BoardingConfirmationView> createState() =>
      _BoardingConfirmationViewState();
}

class _BoardingConfirmationViewState extends State<BoardingConfirmationView> {
  bool _sending = false;
  bool _prepareNotificationEnabled = true;
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
      const Text(
        '등록하면 선택한 차량을 바로 추적해요. 위치 정보가 부족하면 차량 위치 확인 중으로 표시하며 다음 관측을 기다려요. 선택한 정류장에 도달하거나 통과하면 알리며, 실제 정차나 하차 가능 여부를 보장하지 않아요.',
      ),
      if (!widget.reconfirm)
        SwitchListTile(
          key: const ValueKey('prepare-notification-enabled'),
          title: const Text('사전 알림 받기'),
          subtitle: const Text('2정거장 전 우선 · 놓치면 1정거장 전 한 번. 꺼도 목적지 알림은 받아요.'),
          value: _prepareNotificationEnabled,
          onChanged: widget.busy || _sending
              ? null
              : (value) {
                  setState(() => _prepareNotificationEnabled = value);
                  widget.onPrepareNotificationChanged?.call(value);
                },
        ),
      FilledButton(
        onPressed: widget.busy || _sending ? null : _confirm,
        child: Text(widget.reconfirm ? '동일 차량 재확인 요청' : '하차 알림 등록'),
      ),
    ],
  );
}
