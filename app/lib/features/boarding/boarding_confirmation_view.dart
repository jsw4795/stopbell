import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import '../transit/bus_route.dart';
import '../transit/bus_route_presentation.dart';
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
    this.route,
    this.boardingName,
    this.busy = false,
    this.reconfirm = false,
    this.onNotificationOffsetChanged,
  });

  final BoardingCandidate candidate;
  final String routeNumber, targetName;
  final BusRoute? route;
  final String? boardingName;
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

  bool get _busy => widget.busy || _sending;

  Future<void> _confirm() async {
    if (_busy || !canConfirmBoardingCandidate(widget.candidate)) return;
    setState(() => _sending = true);
    try {
      await widget.onConfirm(widget.candidate.vehicleNumber!);
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = StopBellColors.of(context);
    final route = widget.route;
    final presentation = route == null
        ? null
        : BusRoutePresentation.forRoute(route, theme.brightness);
    final boardingName = widget.boardingName;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(StopBellSpacing.screenPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: TextButton.icon(
              onPressed: _busy ? null : widget.onBack,
              icon: const Icon(Icons.arrow_back),
              label: const Text('다른 후보 선택 · 다시 조회'),
            ),
          ),
          const SizedBox(height: StopBellSpacing.sm),
          Text(
            widget.reconfirm ? '동일 차량 재확인' : '하차 알림 등록 확인',
            style: theme.textTheme.titleLarge,
          ),
          const SizedBox(height: StopBellSpacing.md),
          Card(
            margin: EdgeInsets.zero,
            color: theme.colorScheme.primaryContainer,
            child: Padding(
              padding: const EdgeInsets.all(StopBellSpacing.lg),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: StopBellSpacing.xs,
                children: [
                  Text('선택한 차량', style: theme.textTheme.labelMedium),
                  Text(
                    widget.candidate.vehicleNumber ?? '차량번호 확인 불가',
                    style: theme.textTheme.headlineSmall,
                  ),
                  Wrap(
                    spacing: StopBellSpacing.sm,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      Text(
                        '${widget.routeNumber}번 노선',
                        style: theme.textTheme.titleSmall,
                      ),
                      if (presentation case BusRoutePresentation(
                        label: final label?,
                        :final color,
                      ))
                        Text(
                          label,
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: color,
                          ),
                        ),
                    ],
                  ),
                  Text(
                    '원래 관측 시각 · ${boardingTime(widget.candidate.observedAt)}',
                    style: theme.textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: StopBellSpacing.md),
          Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.all(StopBellSpacing.lg),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                spacing: StopBellSpacing.md,
                children: [
                  if (boardingName != null) ...[
                    _TripStop(
                      label: '승차 정류장',
                      name: boardingName,
                      icon: Icons.directions_bus_outlined,
                      color: colors.boardingStop,
                    ),
                    const Divider(height: 1),
                  ],
                  _TripStop(
                    label: '하차 목표 정류장',
                    name: widget.targetName,
                    icon: Icons.flag_outlined,
                    color: colors.targetStop,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: StopBellSpacing.lg),
          if (!widget.reconfirm) ...[
            Text('알림 시점', style: theme.textTheme.titleMedium),
            const SizedBox(height: StopBellSpacing.xs),
            Text(
              '미정차·경유를 포함한 노선 방문 지점 순서를 기준으로 하차 준비 알림을 한 번 보내요.',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: StopBellSpacing.sm),
            Card(
              margin: EdgeInsets.zero,
              child: RadioGroup<int>(
                key: const ValueKey('notification-offset-stops'),
                groupValue: _notificationOffsetStops,
                onChanged: (offset) {
                  if (_busy || offset == null) return;
                  setState(() => _notificationOffsetStops = offset);
                  widget.onNotificationOffsetChanged?.call(offset);
                },
                child: Column(
                  children: [
                    RadioListTile<int>(
                      value: 2,
                      title: const Text('노선 방문 지점 2개 전 (기본)'),
                      enabled: !_busy,
                    ),
                    const Divider(height: 1),
                    RadioListTile<int>(
                      value: 1,
                      title: const Text('노선 방문 지점 1개 전'),
                      enabled: !_busy,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: StopBellSpacing.lg),
          ],
          Text(
            widget.reconfirm ? '같은 차량의 추적을 재개해요' : '등록하면 바로 추적을 시작해요',
            style: theme.textTheme.titleMedium,
          ),
          const SizedBox(height: StopBellSpacing.xs),
          Text(
            widget.reconfirm
                ? '같은 차량의 진행을 다시 확인해요. 저장된 목적지와 알림 설정을 사용해요.'
                : '버스 도착 전에도 등록할 수 있어요. 진행 위치가 확인될 때까지 차량 위치 확인 중으로 표시해요.',
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: StopBellSpacing.lg),
          FilledButton(
            onPressed: _busy || !canConfirmBoardingCandidate(widget.candidate)
                ? null
                : _confirm,
            child: Text(
              _busy
                  ? '확인 중…'
                  : widget.reconfirm
                  ? '동일 차량 재확인 요청'
                  : '하차 알림 등록',
            ),
          ),
        ],
      ),
    );
  }
}

class _TripStop extends StatelessWidget {
  const _TripStop({
    required this.label,
    required this.name,
    required this.icon,
    required this.color,
  });

  final String label, name;
  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: color, size: 20),
        const SizedBox(width: StopBellSpacing.sm),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: StopBellSpacing.xs,
            children: [
              Text(label, style: textTheme.labelSmall?.copyWith(color: color)),
              Text(name, style: textTheme.titleSmall),
            ],
          ),
        ),
      ],
    );
  }
}
