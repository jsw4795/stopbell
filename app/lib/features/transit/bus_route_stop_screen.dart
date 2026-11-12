import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import '../alarm/alarm.dart';
import '../alarm/alarm_api_client.dart';
import '../alarm/alarm_creation_controller.dart';
import 'bus_route.dart';
import 'bus_route_stop_occurrence.dart';
import 'bus_route_presentation.dart';
import 'bus_route_timeline.dart';
import 'bus_route_vehicle_bar.dart';
import 'bus_route_vehicle_client.dart';
import 'bus_route_vehicle_controller.dart';
import 'bus_stop_selection_layout.dart';

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
    required this.creationController,
    this.beforeActivate,
    this.isRequestCurrent,
    this.findVehicles,
  });

  final BusRoute route;
  final Future<List<BusRouteStopOccurrence>> Function(int routeId) findStops;
  final VoidCallback onBack;
  final AlarmClient alarmClient;
  final void Function(Alarm alarm, {bool activationUnverified}) onCreated;
  final VoidCallback onShowAlarms;
  final Future<bool> Function(BuildContext context)? beforeActivate;
  final bool Function()? isRequestCurrent;

  /// 없으면 앱 루트의 [BusRouteVehiclesScope]를 쓰고, 그것도 없으면 차량 표시를 끈다.
  final FindBusRouteVehicles? findVehicles;
  final AlarmCreationController creationController;

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
  BusRouteVehicleController? _vehicles;

  BusRouteVehicleController? _newVehicles() {
    final find =
        widget.findVehicles ?? BusRouteVehiclesScope.maybeFind(context);
    return find == null
        ? null
        : BusRouteVehicleController(routeId: widget.route.id, find: find);
  }

  @override
  void initState() {
    super.initState();
    _vehicles = _newVehicles();
    _load();
  }

  @override
  void didUpdateWidget(covariant BusRouteStopScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.route.id != widget.route.id) {
      _creating = false;
      _outcomeUnknown = false;
      _createMessage = null;
      _vehicles?.dispose();
      _vehicles = _newVehicles();
      _load();
    }
  }

  @override
  void dispose() {
    _vehicles?.dispose();
    _requestGeneration++;
    super.dispose();
  }

  Future<void> _load() async {
    if (_creating || _outcomeUnknown) return;
    final request = ++_requestGeneration;
    final routeId = widget.route.id;
    _vehicles?.refresh();
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
      _selectedOccurrenceId = _selectedOccurrenceId == occurrence.id
          ? null
          : occurrence.id;
      _notifyOneStopBefore = false;
      _notifyOneStopAfter = false;
      _createMessage = null;
    });
  }

  Future<void> _create() async {
    final id = _selectedOccurrenceId;
    if (_creating ||
        _outcomeUnknown ||
        id == null ||
        !(widget.isRequestCurrent?.call() ?? true)) {
      return;
    }
    final request = ++_requestGeneration;
    final routeId = widget.route.id;
    final client = widget.alarmClient;
    bool current() =>
        mounted &&
        request == _requestGeneration &&
        widget.route.id == routeId &&
        _selectedOccurrenceId == id &&
        identical(widget.alarmClient, client) &&
        (widget.isRequestCurrent?.call() ?? true);
    final before = _notifyOneStopBefore;
    final after = _notifyOneStopAfter;
    setState(() {
      _creating = true;
      _createMessage = null;
    });
    try {
      try {
        if (widget.beforeActivate != null &&
            !await widget.beforeActivate!(context)) {
          return;
        }
      } catch (_) {
        if (current()) {
          setState(() => _createMessage = '알림 수신 준비를 확인하지 못했습니다. 다시 시도해 주세요.');
        }
        return;
      }
      if (!current()) return;
      final controller = widget.creationController;
      final createdAlarm = await controller.create(id, before, after, current);
      if (createdAlarm == null) {
        if (current()) {
          setState(() {
            _outcomeUnknown = controller.pending != null;
            _createMessage = controller.message ?? '요청을 저장하거나 전송하지 못했습니다.';
          });
        }
        return;
      }
      if (!current()) return;
      var alarm = createdAlarm;
      if (createdAlarm.status != AlarmStatus.inactive) {
        widget.onCreated(createdAlarm);
        return;
      }
      try {
        alarm = await client.activate(createdAlarm.id);
        if (alarm.id != createdAlarm.id) throw const AlarmApiException();
      } catch (_) {
        if (current()) {
          widget.onCreated(createdAlarm, activationUnverified: true);
        }
        return;
      }
      if (current()) widget.onCreated(alarm);
    } on AlarmApiException catch (error) {
      if (!current()) return;
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
      } else if (!error.isCreationRejected) {
        setState(() {
          _outcomeUnknown = true;
          _createMessage =
              '알람 생성 요청이 서버에 반영됐을 수 있습니다. 중복 생성을 피하려면 알람 목록에서 먼저 확인해 주세요.';
        });
      } else {
        setState(() => _createMessage = '알람을 생성하지 못했습니다.');
      }
    } catch (_) {
      if (current()) {
        setState(() {
          _outcomeUnknown = true;
          _createMessage =
              '알람 생성 요청이 서버에 반영됐을 수 있습니다. 중복 생성을 피하려면 알람 목록에서 먼저 확인해 주세요.';
        });
      }
    } finally {
      if (mounted && request == _requestGeneration) {
        setState(() => _creating = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final target = _stops
        .where((stop) => stop.id == _selectedOccurrenceId)
        .firstOrNull;
    final details = target == null ? null : selectedStopContext(target, _stops);
    final canChange = !_creating && !_outcomeUnknown;
    final theme = Theme.of(context);
    return BusStopSelectionLayout(
      route: widget.route,
      title: '도착 알람',
      iconColor: routeTypeColor(context, widget.route),
      onBack: _creating ? null : widget.onBack,
      body: _buildStops(),
      summary: target == null && _createMessage == null
          ? _state == _StopState.success
                ? const StopSelectionNotice(
                    '알람을 받을 정류장을 선택하세요.',
                    icon: Icons.flag_outlined,
                  )
                : null
          : Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              spacing: StopBellSpacing.xs,
              children: [
                if (target != null) ...[
                  Text.rich(
                    TextSpan(
                      text: '선택한 정류장: ',
                      children: [
                        TextSpan(
                          text: target.name,
                          style: theme.textTheme.titleMedium?.copyWith(
                            color: StopBellColors.of(context).targetStop,
                          ),
                        ),
                        if (details != null) TextSpan(text: '\n$details'),
                      ],
                    ),
                    style: theme.textTheme.bodySmall,
                  ),
                  StopSelectionPair(
                    first: SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      title: const Text('한 정거장 전 알림'),
                      value: _notifyOneStopBefore,
                      onChanged: canChange && target.canNotifyOneStopBefore
                          ? (value) =>
                                setState(() => _notifyOneStopBefore = value)
                          : null,
                    ),
                    second: SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      title: const Text('한 정거장 후 알림'),
                      value: _notifyOneStopAfter,
                      onChanged: canChange && target.canNotifyOneStopAfter
                          ? (value) =>
                                setState(() => _notifyOneStopAfter = value)
                          : null,
                    ),
                  ),
                  if (!_outcomeUnknown)
                    FilledButton(
                      onPressed: _creating ? null : _create,
                      child: const Text('알람 시작'),
                    ),
                ],
                if (_creating) const Center(child: CircularProgressIndicator()),
                if (_createMessage != null)
                  Text(_createMessage!, textAlign: TextAlign.center),
                if (_outcomeUnknown)
                  OutlinedButton(
                    onPressed: widget.onShowAlarms,
                    child: const Text('알람 목록 보기'),
                  ),
                if (canChange &&
                    _createMessage != null &&
                    _state == _StopState.success)
                  OutlinedButton(
                    onPressed: _load,
                    child: const Text('정류장 목록 새로고침'),
                  ),
              ],
            ),
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
        final vehicles = _vehicles;
        BusRouteTimeline timeline() => BusRouteTimeline(
          stops: _stops,
          targetId: _selectedOccurrenceId,
          onSelect: _creating || _outcomeUnknown ? null : _select,
          vehicles: vehicles?.data?.vehicles,
          vehicleColor: routeTypeColor(context, widget.route),
        );
        if (vehicles == null) return timeline();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            BusRouteVehicleBar(controller: vehicles),
            Expanded(
              child: ListenableBuilder(
                listenable: vehicles,
                builder: (_, _) => timeline(),
              ),
            ),
          ],
        );
    }
  }
}
