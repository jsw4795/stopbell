import 'package:flutter/material.dart';

import 'alarm.dart';

enum _ListState { loading, success, empty, error }

class AlarmListScreen extends StatefulWidget {
  const AlarmListScreen({
    super.key,
    required this.findAll,
    required this.onSelect,
    required this.onCreate,
  });

  final Future<List<Alarm>> Function() findAll;
  final ValueChanged<int> onSelect;
  final VoidCallback onCreate;

  @override
  State<AlarmListScreen> createState() => _AlarmListScreenState();
}

class _AlarmListScreenState extends State<AlarmListScreen> {
  _ListState _state = _ListState.loading;
  List<Alarm> _alarms = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _state = _ListState.loading);
    try {
      final alarms = await widget.findAll();
      if (!mounted) return;
      setState(() {
        _alarms = alarms;
        _state = alarms.isEmpty ? _ListState.empty : _ListState.success;
      });
    } catch (_) {
      if (mounted) setState(() => _state = _ListState.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: FilledButton(
            onPressed: widget.onCreate,
            child: const Text('새 알림 만들기'),
          ),
        ),
        Expanded(
          child: switch (_state) {
            _ListState.loading => const Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [CircularProgressIndicator(), Text('알람 목록 조회 중...')],
              ),
            ),
            _ListState.empty => const Center(child: Text('아직 만든 알람이 없습니다.')),
            _ListState.error => Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('알람 목록을 불러오지 못했습니다.'),
                  OutlinedButton(onPressed: _load, child: const Text('다시 시도')),
                ],
              ),
            ),
            _ListState.success => ListView.builder(
              itemCount: _alarms.length,
              itemBuilder: (context, index) {
                final alarm = _alarms[index];
                return ListTile(
                  key: ValueKey(alarm.id),
                  title: Text('${alarm.routeNumber} · ${alarm.stopName}'),
                  subtitle: Text(alarm.status.label),
                  onTap: () => widget.onSelect(alarm.id),
                );
              },
            ),
          },
        ),
      ],
    );
  }
}
