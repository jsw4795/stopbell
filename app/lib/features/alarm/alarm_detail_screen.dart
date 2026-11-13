import '../../shared/stopbell_home_button.dart';

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import 'alarm.dart';
import 'alarm_approach.dart';
import 'alarm_api_client.dart';
import 'home_overview.dart' show alarmRouteTypeColor;

enum _DetailState { loading, success, error, notFound }

class AlarmDetailScreen extends StatefulWidget {
  const AlarmDetailScreen({
    super.key,
    required this.alarmId,
    required this.client,
    required this.onBack,
    this.createdAlarm,
    this.beforeActivate,
    this.activationUnverified = false,
    this.isRequestCurrent,
  });

  final int alarmId;
  final AlarmClient client;
  final VoidCallback onBack;
  final Alarm? createdAlarm;
  final bool activationUnverified;
  final bool Function()? isRequestCurrent;
  final Future<bool> Function(BuildContext context)? beforeActivate;

  @override
  State<AlarmDetailScreen> createState() => _AlarmDetailScreenState();
}

class _AlarmDetailScreenState extends State<AlarmDetailScreen> {
  _DetailState _state = _DetailState.loading;
  Alarm? _alarm;
  AlarmApproachLoader? _approachLoader;
  bool _mutating = false;
  bool _confirmingDelete = false;
  bool _loading = false;
  bool _outcomeUnknown = false;
  int _requestGeneration = 0;
  String? _message;

  bool get _current => mounted && (widget.isRequestCurrent?.call() ?? true);
  bool get _canMutate =>
      _current &&
      !_mutating &&
      !_confirmingDelete &&
      !_loading &&
      !_outcomeUnknown &&
      _state == _DetailState.success;

  bool _isCurrent(int request, int id, AlarmClient client) =>
      _current &&
      request == _requestGeneration &&
      id == widget.alarmId &&
      identical(client, widget.client);

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  void _initialize() {
    final client = widget.client;
    _approachLoader = client is AlarmApiClient
        ? client.createApproachLoader()
        : null;
    _alarm = widget.createdAlarm?.id == widget.alarmId
        ? widget.createdAlarm
        : null;
    _outcomeUnknown = widget.activationUnverified;
    _message = null;
    _state = _DetailState.loading;
    if (_alarm != null && !_outcomeUnknown) {
      _state = _DetailState.success;
    } else {
      _load();
    }
  }

  @override
  void didUpdateWidget(covariant AlarmDetailScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.alarmId != widget.alarmId ||
        !identical(oldWidget.client, widget.client)) {
      _requestGeneration++;
      _loading = false;
      _mutating = false;
      _confirmingDelete = false;
      _initialize();
    }
  }

  @override
  void dispose() {
    _requestGeneration++;
    super.dispose();
  }

  Future<void> _load() async {
    if (!_current || _mutating || _loading) return;
    final request = ++_requestGeneration;
    final id = widget.alarmId;
    final client = widget.client;
    setState(() {
      _loading = true;
      _state = _DetailState.loading;
      _message = null;
    });
    await _readCurrent(request, id, client);
  }

  Future<void> _readCurrent(int request, int id, AlarmClient client) async {
    try {
      final alarm = await client.findById(id);
      if (!_isCurrent(request, id, client)) return;
      if (alarm.id != id) throw const AlarmApiException();
      setState(() {
        final recovered = _outcomeUnknown;
        _alarm = alarm;
        _outcomeUnknown = false;
        _state = _DetailState.success;
        _message = recovered ? '조회 시점의 서버 상태입니다.' : null;
      });
    } catch (error) {
      if (!_isCurrent(request, id, client)) return;
      setState(() {
        if (error is AlarmApiException && error.isAlarmNotFound) {
          _alarm = null;
          _state = _DetailState.notFound;
        } else if (_outcomeUnknown && _alarm != null) {
          _state = _DetailState.success;
          _message = '요청이 서버에 반영됐을 수 있어요. 현재 상태를 다시 조회해 주세요.';
        } else {
          _state = _DetailState.error;
        }
      });
    } finally {
      if (_isCurrent(request, id, client)) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _recover(int request, int id, AlarmClient client) async {
    if (!_isCurrent(request, id, client)) return;
    setState(() {
      _outcomeUnknown = true;
      _loading = true;
      _message = '요청 결과를 확인하고 있어요.';
    });
    await _readCurrent(request, id, client);
  }

  void _rejected(AlarmApiException error, String message) {
    setState(() {
      if (error.isAlarmNotFound) {
        _alarm = null;
        _state = _DetailState.notFound;
      } else {
        _message = message;
      }
    });
  }

  Future<void> _change({required bool activating}) async {
    if (!_canMutate) return;
    final request = ++_requestGeneration;
    final id = widget.alarmId;
    final client = widget.client;
    setState(() {
      _mutating = true;
      _message = null;
    });
    try {
      // Preparation failure happens before the mutation; it is safe to retry.
      if (activating && widget.beforeActivate != null) {
        try {
          if (!await widget.beforeActivate!(context)) return;
        } catch (_) {
          if (_isCurrent(request, id, client)) {
            setState(() => _message = '알림 수신 준비를 확인하지 못했어요. 다시 시도해 주세요.');
          }
          return;
        }
      }
      if (!_isCurrent(request, id, client)) return;
      try {
        final alarm = await (activating
            ? client.activate(id)
            : client.deactivate(id));
        if (!_isCurrent(request, id, client)) return;
        if (alarm.id != id) throw const AlarmApiException();
        setState(() => _alarm = alarm);
      } catch (error) {
        if (!_isCurrent(request, id, client)) return;
        if (error is AlarmApiException && error.isMutationRejected) {
          _rejected(error, '알람 상태를 바꾸지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.');
        } else {
          await _recover(request, id, client);
        }
      }
    } finally {
      if (_isCurrent(request, id, client)) {
        setState(() => _mutating = false);
      }
    }
  }

  Future<void> _changeOption({bool? before, bool? after}) async {
    final current = _alarm;
    if (!_canMutate || current == null) return;
    final request = ++_requestGeneration;
    final id = widget.alarmId;
    final client = widget.client;
    setState(() {
      _mutating = true;
      _message = null;
    });
    try {
      final alarm = await client.updateOptions(
        id,
        notifyOneStopBefore: before ?? current.notifyOneStopBefore,
        notifyOneStopAfter: after ?? current.notifyOneStopAfter,
      );
      if (!_isCurrent(request, id, client)) return;
      if (alarm.id != id) throw const AlarmApiException();
      setState(() => _alarm = alarm);
    } catch (error) {
      if (!_isCurrent(request, id, client)) return;
      if (error is AlarmApiException && error.isMutationRejected) {
        _rejected(
          error,
          error.statusCode == 400
              ? '이 정류장에서는 사용할 수 없는 알림이에요.'
              : error.statusCode == 409
              ? '알람이 켜져 있을 때만 바꿀 수 있어요.'
              : '알림 설정을 바꾸지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.',
        );
      } else {
        await _recover(request, id, client);
      }
    } finally {
      if (_isCurrent(request, id, client)) {
        setState(() => _mutating = false);
      }
    }
  }

  Widget _optionRow(String title, bool value, ValueChanged<bool>? onChanged) {
    return ListTile(
      minTileHeight: StopBellSpacing.minimumTouchSize,
      enabled: onChanged != null,
      title: Text(title),
      trailing: Switch(value: value, onChanged: onChanged),
      onTap: onChanged == null ? null : () => onChanged(!value),
    );
  }

  Future<void> _confirmDelete() async {
    if (!_canMutate) return;
    final request = ++_requestGeneration;
    final id = widget.alarmId;
    final client = widget.client;
    setState(() => _confirmingDelete = true);
    try {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('알람 삭제'),
          content: const Text('도착 알람을 삭제할까요? 삭제한 알람은 복구할 수 없어요.'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('취소'),
            ),
            TextButton(
              style: TextButton.styleFrom(
                foregroundColor: Theme.of(context).colorScheme.error,
              ),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('삭제'),
            ),
          ],
        ),
      );
      if (!_isCurrent(request, id, client) || confirmed != true) return;
      setState(() {
        _mutating = true;
        _message = null;
      });
      try {
        await client.delete(id);
        if (_isCurrent(request, id, client)) widget.onBack();
      } catch (error) {
        if (!_isCurrent(request, id, client)) return;
        if (error is AlarmApiException && error.isMutationRejected) {
          _rejected(error, '알람을 삭제하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.');
        } else {
          await _recover(request, id, client);
        }
      }
    } finally {
      if (_isCurrent(request, id, client)) {
        setState(() {
          _mutating = false;
          _confirmingDelete = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        AppBar(
          primary: false,
          leading: IconButton(
            tooltip: '알람 목록으로',
            icon: const Icon(Icons.arrow_back),
            onPressed: _mutating ? null : widget.onBack,
          ),
          title: const Text('알람 상세'),
          actions: const [StopBellHomeButton()],
        ),
        Expanded(
          child: switch (_state) {
            _DetailState.loading => const Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [CircularProgressIndicator(), Text('알람 조회 중...')],
              ),
            ),
            _DetailState.error => Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _outcomeUnknown
                        ? '요청 결과와 현재 알람 상태를 확인할 수 없어요.'
                        : '알람을 불러오지 못했어요.',
                  ),
                  OutlinedButton(
                    onPressed: _load,
                    child: Text(_outcomeUnknown ? '현재 상태 다시 조회' : '다시 시도'),
                  ),
                ],
              ),
            ),
            _DetailState.notFound => Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('알람을 찾을 수 없어요.'),
                  const Text('삭제되었거나 현재 계정에서 접근할 수 없는 알람이에요.'),
                  if (_outcomeUnknown)
                    OutlinedButton(
                      onPressed: _load,
                      child: const Text('현재 상태 다시 조회'),
                    ),
                  OutlinedButton(
                    onPressed: widget.onBack,
                    child: const Text('알람 목록 보기'),
                  ),
                ],
              ),
            ),
            _DetailState.success => _details(_alarm!),
          },
        ),
      ],
    );
  }

  Widget _details(Alarm alarm) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final semantic = StopBellColors.of(context);
    final muted = colors.onSurfaceVariant;
    final routeColor = alarmRouteTypeColor(
      context,
      alarm.routeNumber,
      alarm.provider,
      alarm.routeType,
    );
    final statusColor = alarm.status == AlarmStatus.inactive
        ? muted
        : semantic.activeTracking;
    final statusLabel = _outcomeUnknown ? '확인 필요' : alarm.status.shortLabel;
    final endpoints = alarm.startStopName != null && alarm.endStopName != null
        ? '${alarm.startStopName} → ${alarm.endStopName}'
        : null;
    final active = alarm.status != AlarmStatus.inactive;
    final optionsEditable = _canMutate && alarm.status == AlarmStatus.active;
    return ListView(
      padding: const EdgeInsets.all(StopBellSpacing.screenPadding),
      children: [
        Card(
          margin: EdgeInsets.zero,
          child: Padding(
            padding: const EdgeInsets.all(StopBellSpacing.lg),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: StopBellSpacing.sm,
                  runSpacing: StopBellSpacing.xs,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Icon(
                      Icons.directions_bus_outlined,
                      color: routeColor ?? colors.primary,
                      size: 24,
                    ),
                    Text(
                      alarm.routeNumber,
                      style: theme.textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.w700,
                        color: routeColor,
                      ),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: StopBellSpacing.sm,
                        vertical: 3,
                      ),
                      decoration: BoxDecoration(
                        color: statusColor.withValues(alpha: .14),
                        borderRadius: BorderRadius.circular(
                          StopBellRadius.button,
                        ),
                      ),
                      child: Text(
                        statusLabel,
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: statusColor,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: StopBellSpacing.xs),
                Text.rich(
                  TextSpan(
                    children: [
                      TextSpan(
                        text: alarm.stopName,
                        style: theme.textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      TextSpan(
                        text: ' 도착',
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: muted,
                        ),
                      ),
                    ],
                  ),
                ),
                AlarmApproachText(
                  alarm: alarm,
                  findApproach: _approachLoader?.find,
                ),
                if (endpoints != null)
                  Padding(
                    padding: const EdgeInsets.only(top: StopBellSpacing.xs),
                    child: Row(
                      children: [
                        Icon(Icons.swap_horiz, size: 16, color: muted),
                        const SizedBox(width: StopBellSpacing.sm),
                        Expanded(
                          child: Text(
                            endpoints,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: muted,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: StopBellSpacing.md),
        Card(
          margin: EdgeInsets.zero,
          clipBehavior: Clip.antiAlias,
          child: Column(
            children: [
              SwitchListTile(
                title: const Text('알람 사용'),
                value: active,
                onChanged: _canMutate
                    ? (value) => _change(activating: value)
                    : null,
              ),
              if (alarm.canNotifyOneStopBefore != false)
                _optionRow(
                  '한 정류장 전 알림',
                  alarm.notifyOneStopBefore,
                  optionsEditable
                      ? (value) => _changeOption(before: value)
                      : null,
                ),
              if (alarm.canNotifyOneStopAfter != false)
                _optionRow(
                  '한 정류장 후 알림',
                  alarm.notifyOneStopAfter,
                  optionsEditable
                      ? (value) => _changeOption(after: value)
                      : null,
                ),
            ],
          ),
        ),
        if (!_outcomeUnknown && alarm.status == AlarmStatus.followUp) ...[
          const SizedBox(height: StopBellSpacing.md),
          const Text('목표 정류장 도착 후 같은 차량을 한 정류장 뒤 알림까지 추적 중이에요.'),
          const Text('다시 활성화하면 이전 후속 추적을 끝내고 새 감시를 시작해요.'),
          const SizedBox(height: StopBellSpacing.sm),
          FilledButton(
            onPressed: _canMutate ? () => _change(activating: true) : null,
            child: const Text('다시 활성화'),
          ),
        ],
        if (_mutating || _loading)
          const Center(child: CircularProgressIndicator()),
        if (_outcomeUnknown)
          OutlinedButton(
            onPressed: _mutating || _loading ? null : _load,
            child: const Text('현재 상태 다시 조회'),
          ),
        if (_message != null) ...[
          const SizedBox(height: StopBellSpacing.md),
          Text(_message!),
        ],
        const SizedBox(height: StopBellSpacing.xl),
        TextButton(
          style: TextButton.styleFrom(
            foregroundColor: colors.error,
            minimumSize: const Size.fromHeight(
              StopBellSpacing.minimumTouchSize,
            ),
          ),
          onPressed: _canMutate ? _confirmDelete : null,
          child: const Text('삭제'),
        ),
      ],
    );
  }
}
