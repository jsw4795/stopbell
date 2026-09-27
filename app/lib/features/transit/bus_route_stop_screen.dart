import 'package:flutter/material.dart';

import '../alarm/alarm.dart';
import '../alarm/alarm_api_client.dart';
import 'bus_route.dart';
import 'bus_route_stop_occurrence.dart';

enum _StopState { loading, success, empty, error }

class BusRouteStopScreen extends StatefulWidget {
  const BusRouteStopScreen({
    super.key,
    required this.route,
    required this.findStops,
    required this.onBack,
    required this.alarmClient,
    required this.onCreated,
    required this.onShowAlarms,
  });

  final BusRoute route;
  final Future<List<BusRouteStopOccurrence>> Function(int routeId) findStops;
  final VoidCallback onBack;
  final AlarmClient alarmClient;
  final ValueChanged<Alarm> onCreated;
  final VoidCallback onShowAlarms;

  @override
  State<BusRouteStopScreen> createState() => _BusRouteStopScreenState();
}

class _BusRouteStopScreenState extends State<BusRouteStopScreen> {
  _StopState _state = _StopState.loading;
  List<BusRouteStopOccurrence> _stops = const [];
  int? _selectedOccurrenceId;
  bool _notifyOneStopBefore = false;
  bool _notifyOneStopAfter = false;
  int _requestGeneration = 0;
  bool _creating = false;
  bool _outcomeUnknown = false;
  String? _createMessage;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant BusRouteStopScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.route.id != widget.route.id) _load();
  }

  @override
  void dispose() {
    _requestGeneration++;
    super.dispose();
  }

  Future<void> _load() async {
    if (_creating) return;
    final request = ++_requestGeneration;
    final routeId = widget.route.id;
    setState(() {
      _state = _StopState.loading;
      _stops = const [];
      _selectedOccurrenceId = null;
      _notifyOneStopBefore = false;
      _notifyOneStopAfter = false;
    });
    try {
      final stops = await widget.findStops(routeId);
      if (!mounted || request != _requestGeneration) return;
      setState(() {
        _stops = stops;
        _state = stops.isEmpty ? _StopState.empty : _StopState.success;
      });
    } catch (_) {
      if (mounted && request == _requestGeneration) {
        setState(() => _state = _StopState.error);
      }
    }
  }

  void _select(BusRouteStopOccurrence occurrence) {
    if (_creating || _outcomeUnknown) return;
    setState(() {
      _selectedOccurrenceId = occurrence.id;
      _notifyOneStopBefore = false;
      _notifyOneStopAfter = false;
      _createMessage = null;
    });
  }

  Future<void> _create() async {
    final id = _selectedOccurrenceId;
    if (_creating || _outcomeUnknown || id == null) return;
    final before = _notifyOneStopBefore;
    final after = _notifyOneStopAfter;
    setState(() {
      _creating = true;
      _createMessage = null;
    });
    try {
      final alarm = await widget.alarmClient.create(
        targetStopOccurrenceId: id,
        notifyOneStopBefore: before,
        notifyOneStopAfter: after,
      );
      if (mounted) widget.onCreated(alarm);
    } on AlarmApiException catch (error) {
      if (!mounted) return;
      if (error.isStaleTarget) {
        setState(() {
          _creating = false;
          _createMessage = '정류장 정보가 변경됐습니다. 새 목록에서 정류장을 다시 선택해 주세요.';
        });
        _load();
      } else if (error.statusCode == 400 &&
          error.code == 'INVALID_ALARM_REQUEST') {
        setState(
          () => _createMessage =
              '현재 정류장 정보로 선택한 알림 조건을 생성할 수 없습니다. 목록을 새로 조회해 주세요.',
        );
      } else if (error.statusCode == null) {
        setState(() {
          _outcomeUnknown = true;
          _createMessage =
              '알람 생성 결과를 확인할 수 없습니다. 중복 생성을 피하려면 알람 목록에서 생성 여부를 확인해 주세요.';
        });
      } else {
        setState(() => _createMessage = '알람을 생성하지 못했습니다.');
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _outcomeUnknown = true;
          _createMessage =
              '알람 생성 결과를 확인할 수 없습니다. 중복 생성을 피하려면 알람 목록에서 생성 여부를 확인해 주세요.';
        });
      }
    } finally {
      if (mounted) setState(() => _creating = false);
    }
  }

  String? _context(BusRouteStopOccurrence stop) {
    final sameName = _stops.where((item) => item.name == stop.name).toList();
    final details = <String>[];
    if (stop.destinationName != null) {
      details.add('${stop.destinationName} 방면');
    }
    final sameDestination = sameName
        .where((item) => item.destinationName == stop.destinationName)
        .toList();
    if (sameName.length > 1 &&
        (stop.destinationName == null || sameDestination.length > 1)) {
      details.add(
        '이전: ${stop.previousStopName ?? '없음'} · 다음: ${stop.nextStopName ?? '없음'}',
      );
    }
    if (sameDestination.length > 1 &&
        sameDestination
                .where(
                  (item) =>
                      item.previousStopName == stop.previousStopName &&
                      item.nextStopName == stop.nextStopName,
                )
                .length >
            1) {
      details.add('노선 순서 ${stop.order}');
    }
    return details.isEmpty ? null : details.join('\n');
  }

  @override
  Widget build(BuildContext context) {
    final selected = _stops.where((stop) => stop.id == _selectedOccurrenceId);
    final target = selected.isEmpty ? null : selected.first;
    final selectedContext = target == null ? null : _context(target);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ListTile(
          leading: IconButton(
            tooltip: '노선 다시 선택',
            icon: const Icon(Icons.arrow_back),
            onPressed: _creating ? null : widget.onBack,
          ),
          title: Text(widget.route.routeNumber),
          subtitle: Text(widget.route.regionName),
        ),
        Expanded(child: _buildStops()),
        if (target != null) ...[
          const Divider(),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Text(
              '선택한 정류장: ${target.name}${selectedContext == null ? '' : '\n$selectedContext'}',
            ),
          ),
          SwitchListTile(
            title: const Text('한 정거장 전 알림'),
            value: _notifyOneStopBefore,
            onChanged:
                !_creating && !_outcomeUnknown && target.canNotifyOneStopBefore
                ? (value) => setState(() => _notifyOneStopBefore = value)
                : null,
          ),
          SwitchListTile(
            title: const Text('한 정거장 후 알림'),
            value: _notifyOneStopAfter,
            onChanged:
                !_creating && !_outcomeUnknown && target.canNotifyOneStopAfter
                ? (value) => setState(() => _notifyOneStopAfter = value)
                : null,
          ),
        ],
        if (target != null && !_outcomeUnknown)
          Padding(
            padding: const EdgeInsets.all(16),
            child: FilledButton(
              onPressed: _creating ? null : _create,
              child: const Text('알람 만들기'),
            ),
          ),
        if (_creating) const Center(child: CircularProgressIndicator()),
        if (_createMessage != null)
          Padding(
            padding: const EdgeInsets.all(16),
            child: Text(_createMessage!, textAlign: TextAlign.center),
          ),
        if (_outcomeUnknown)
          OutlinedButton(
            onPressed: widget.onShowAlarms,
            child: const Text('알람 목록 보기'),
          ),
        if (!_creating &&
            !_outcomeUnknown &&
            _createMessage != null &&
            _state == _StopState.success)
          OutlinedButton(onPressed: _load, child: const Text('정류장 목록 새로고침')),
      ],
    );
  }

  Widget _buildStops() {
    switch (_state) {
      case _StopState.loading:
        return const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 12),
              Text('정류장 조회 중...'),
            ],
          ),
        );
      case _StopState.empty:
        return const Center(child: Text('정류장 정보가 없습니다.'));
      case _StopState.error:
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('정류장 정보를 불러오지 못했습니다.'),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: _creating ? null : _load,
                child: const Text('다시 시도'),
              ),
            ],
          ),
        );
      case _StopState.success:
        return ListView.builder(
          itemCount: _stops.length,
          itemBuilder: (context, index) {
            final stop = _stops[index];
            final isSelected = stop.id == _selectedOccurrenceId;
            final context = _context(stop);
            return ListTile(
              key: ValueKey(stop.id),
              title: Text(stop.name),
              subtitle: context == null ? null : Text(context),
              selected: isSelected,
              trailing: isSelected ? const Icon(Icons.check) : null,
              onTap: _creating || _outcomeUnknown ? null : () => _select(stop),
            );
          },
        );
    }
  }
}
