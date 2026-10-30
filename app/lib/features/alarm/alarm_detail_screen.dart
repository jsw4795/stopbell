import 'package:flutter/material.dart';

import 'alarm.dart';
import 'alarm_api_client.dart';

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
          _message = '요청이 서버에 반영됐을 수 있습니다. 현재 상태를 다시 조회해 주세요.';
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
      _message = '요청 결과를 확인하고 있습니다.';
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
            setState(() => _message = '알림 수신 준비를 확인하지 못했습니다. 다시 시도해 주세요.');
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
          _rejected(error, '알람 상태를 변경하지 못했습니다.');
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
          content: const Text('이 알람을 삭제할까요?'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('취소'),
            ),
            TextButton(
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
          _rejected(error, '알람을 삭제하지 못했습니다.');
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
        ListTile(
          leading: IconButton(
            tooltip: '알람 목록으로',
            icon: const Icon(Icons.arrow_back),
            onPressed: _mutating ? null : widget.onBack,
          ),
          title: const Text('알람 상세'),
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
                        ? '요청 결과와 현재 알람 상태를 확인할 수 없습니다.'
                        : '알람을 불러오지 못했습니다.',
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
                  const Text('알람을 찾을 수 없습니다.'),
                  const Text('삭제되었거나 현재 계정에서 접근할 수 없는 알람입니다.'),
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

  Widget _details(Alarm alarm) => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      Text(
        '${alarm.routeNumber} · ${alarm.stopName}',
        style: Theme.of(context).textTheme.titleLarge,
      ),
      const SizedBox(height: 12),
      Text('교통수단: ${alarm.transitType.code}'),
      Text(_outcomeUnknown ? '상태: 확인 필요' : '상태: ${alarm.status.label}'),
      Text('한 정거장 전 알림: ${alarm.notifyOneStopBefore ? '켜짐' : '꺼짐'}'),
      Text('한 정거장 후 알림: ${alarm.notifyOneStopAfter ? '켜짐' : '꺼짐'}'),
      if (!_outcomeUnknown && alarm.status == AlarmStatus.followUp) ...[
        const SizedBox(height: 12),
        const Text('목표 정류장 도착 후 같은 차량을 한 정거장 뒤 알림까지 추적 중입니다.'),
        const Text('다시 활성화하면 이전 후속 추적을 끝내고 새 감시를 시작합니다.'),
      ],
      const SizedBox(height: 24),
      if (alarm.status == AlarmStatus.inactive)
        FilledButton(
          onPressed: _canMutate ? () => _change(activating: true) : null,
          child: const Text('활성화'),
        )
      else ...[
        if (alarm.status == AlarmStatus.followUp)
          FilledButton(
            onPressed: _canMutate ? () => _change(activating: true) : null,
            child: const Text('다시 활성화'),
          ),
        OutlinedButton(
          onPressed: _canMutate ? () => _change(activating: false) : null,
          child: const Text('비활성화'),
        ),
      ],
      TextButton(
        onPressed: _canMutate ? _confirmDelete : null,
        child: const Text('삭제'),
      ),
      if (_mutating || _loading)
        const Center(child: CircularProgressIndicator()),
      if (_outcomeUnknown)
        OutlinedButton(
          onPressed: _mutating || _loading ? null : _load,
          child: const Text('현재 상태 다시 조회'),
        ),
      if (_message != null) Text(_message!),
    ],
  );
}
