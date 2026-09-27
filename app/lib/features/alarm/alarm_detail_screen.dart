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
  });

  final int alarmId;
  final AlarmClient client;
  final VoidCallback onBack;
  final Alarm? createdAlarm;

  @override
  State<AlarmDetailScreen> createState() => _AlarmDetailScreenState();
}

class _AlarmDetailScreenState extends State<AlarmDetailScreen> {
  _DetailState _state = _DetailState.loading;
  Alarm? _alarm;
  bool _mutating = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    if (widget.createdAlarm != null) {
      _alarm = widget.createdAlarm;
      _state = _DetailState.success;
    } else {
      _load();
    }
  }

  Future<void> _load() async {
    if (_mutating) return;
    setState(() {
      _state = _DetailState.loading;
      _message = null;
    });
    try {
      final alarm = await widget.client.findById(widget.alarmId);
      if (!mounted) return;
      setState(() {
        _alarm = alarm;
        _state = _DetailState.success;
      });
    } on AlarmApiException catch (error) {
      if (mounted) {
        setState(
          () => _state = error.isAlarmNotFound
              ? _DetailState.notFound
              : _DetailState.error,
        );
      }
    } catch (_) {
      if (mounted) setState(() => _state = _DetailState.error);
    }
  }

  Future<void> _change(Future<Alarm> Function() action) async {
    if (_mutating) return;
    setState(() {
      _mutating = true;
      _message = null;
    });
    try {
      final alarm = await action();
      if (mounted) setState(() => _alarm = alarm);
    } on AlarmApiException catch (error) {
      if (mounted) {
        setState(() {
          if (error.isAlarmNotFound) {
            _alarm = null;
            _state = _DetailState.notFound;
          } else {
            _message = '알람 상태를 변경하지 못했습니다.';
          }
        });
      }
    } catch (_) {
      if (mounted) setState(() => _message = '알람 상태를 변경하지 못했습니다.');
    } finally {
      if (mounted) setState(() => _mutating = false);
    }
  }

  Future<void> _confirmDelete() async {
    if (_mutating) return;
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
    if (!mounted || confirmed != true || _mutating) return;
    setState(() {
      _mutating = true;
      _message = null;
    });
    try {
      await widget.client.delete(widget.alarmId);
      if (mounted) widget.onBack();
    } on AlarmApiException catch (error) {
      if (mounted) {
        setState(() {
          if (error.isAlarmNotFound) {
            _alarm = null;
            _state = _DetailState.notFound;
          } else {
            _message = '알람을 삭제하지 못했습니다.';
          }
        });
      }
    } catch (_) {
      if (mounted) setState(() => _message = '알람을 삭제하지 못했습니다.');
    } finally {
      if (mounted) setState(() => _mutating = false);
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
                  const Text('알람을 불러오지 못했습니다.'),
                  OutlinedButton(onPressed: _load, child: const Text('다시 시도')),
                ],
              ),
            ),
            _DetailState.notFound => Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('알람을 찾을 수 없습니다.'),
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
      Text('상태: ${alarm.status.label}'),
      Text('한 정거장 전 알림: ${alarm.notifyOneStopBefore ? '켜짐' : '꺼짐'}'),
      Text('한 정거장 후 알림: ${alarm.notifyOneStopAfter ? '켜짐' : '꺼짐'}'),
      if (alarm.status == AlarmStatus.followUp) ...[
        const SizedBox(height: 12),
        const Text('목표 정류장 도착 후 같은 차량을 한 정거장 뒤 알림까지 추적 중입니다.'),
        const Text('다시 활성화하면 이전 후속 추적을 끝내고 새 감시를 시작합니다.'),
      ],
      const SizedBox(height: 24),
      if (alarm.status == AlarmStatus.inactive)
        FilledButton(
          onPressed: _mutating
              ? null
              : () => _change(() => widget.client.activate(alarm.id)),
          child: const Text('활성화'),
        )
      else ...[
        if (alarm.status == AlarmStatus.followUp)
          FilledButton(
            onPressed: _mutating
                ? null
                : () => _change(() => widget.client.activate(alarm.id)),
            child: const Text('다시 활성화'),
          ),
        OutlinedButton(
          onPressed: _mutating
              ? null
              : () => _change(() => widget.client.deactivate(alarm.id)),
          child: const Text('비활성화'),
        ),
      ],
      TextButton(
        onPressed: _mutating ? null : _confirmDelete,
        child: const Text('삭제'),
      ),
      if (_mutating) const Center(child: CircularProgressIndicator()),
      if (_message != null) Text(_message!),
    ],
  );
}
