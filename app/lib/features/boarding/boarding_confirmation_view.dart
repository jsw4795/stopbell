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
  final _number = TextEditingController();
  String? _error;
  bool _sending = false;
  bool _prepareNotificationEnabled = true;
  @override
  void dispose() {
    _number.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant BoardingConfirmationView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.candidate, widget.candidate)) {
      _number.clear();
      _error = null;
    }
  }

  Future<void> _confirm() async {
    if (_sending || widget.busy) return;
    if (_number.text != widget.candidate.vehicleNumber ||
        !canConfirmBoardingCandidate(widget.candidate)) {
      setState(
        () => _error = '전체 차량번호가 일치하지 않아요. 공백을 포함해 표시된 전체 번호와 정확히 대조해 주세요. 끝 네 자리만으로는 확정할 수 없어요.',
      );
      return;
    }
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      await widget.onConfirm(_number.text);
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
        widget.reconfirm ? '동일 차량 재확인' : '탑승 차량 직접 확인',
        style: Theme.of(context).textTheme.titleLarge,
      ),
      Text('노선: ${widget.routeNumber}'),
      Text('목적지: ${widget.targetName}'),
      Text('전체 차량번호: ${widget.candidate.vehicleNumber}'),
      Text('원래 관측 시각: ${boardingTime(widget.candidate.observedAt)}'),
      const Text(
        '실제 탑승한 버스의 전체 차량번호를 직접 확인해 주세요. 공백도 원문 그대로 입력하며, 끝 네 자리만 대조하지 않아요.',
      ),
      const Text(
        '차량·경로·진행 검증에 성공하면 추적을 시작해요. 선택한 정류장에 도달하거나 통과하면 알리며, 실제 정차나 하차 가능 여부를 보장하지 않아요.',
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
      TextField(
        key: const ValueKey('confirmed-vehicle-number'),
        controller: _number,
        enabled: !widget.busy && !_sending,
        decoration: InputDecoration(
          labelText: '직접 확인한 전체 차량번호',
          errorText: _error,
          errorMaxLines: 5,
        ),
      ),
      FilledButton(
        onPressed: widget.busy || _sending ? null : _confirm,
        child: Text(widget.reconfirm ? '동일 차량 재확인 요청' : '탑승 차량 확정'),
      ),
    ],
  );
}
