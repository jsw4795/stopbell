import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import '../auth/auth_session.dart';
import '../transit/bus_route.dart';
import '../transit/bus_route_presentation.dart';
import '../transit/bus_route_stop_occurrence.dart';
import '../transit/bus_route_timeline.dart';
import '../transit/bus_route_vehicle.dart';
import '../transit/bus_route_vehicle_bar.dart';
import '../transit/bus_route_vehicle_client.dart';
import '../transit/bus_route_vehicle_controller.dart';
import '../transit/bus_stop_selection_layout.dart';
import '../transit/stop_display_name.dart';
import 'boarding_bus_timeline.dart';
import 'boarding_candidate_client.dart';
import 'boarding_candidate_progress_label.dart';
import 'boarding_candidates.dart';
import 'boarding_candidates_view.dart';
import 'boarding_creation_intent.dart';

enum _StopsState { loading, success, empty, error }

enum _Picker { target, boarding, review }

class BoardingStopScreen extends StatefulWidget {
  const BoardingStopScreen({
    super.key,
    required this.route,
    required this.authSession,
    required this.findStops,
    required this.findCandidates,
    required this.onBack,
    this.onSelectCandidate,
    this.reconfirm = false,
    this.creationBusy = false,
    this.notificationOffsetStops = 2,
    this.onNotificationOffsetChanged,
    this.findVehicles,
  });
  final BusRoute route;
  final AuthSession authSession;
  final Future<List<BusRouteStopOccurrence>> Function(int) findStops;
  final FindBoardingCandidates findCandidates;
  final VoidCallback onBack;
  final bool reconfirm, creationBusy;
  final int notificationOffsetStops;
  final ValueChanged<int>? onNotificationOffsetChanged;

  /// 없으면 앱 루트의 [BusRouteVehiclesScope]를 쓰고, 그것도 없으면 차량 표시를 끈다.
  final FindBusRouteVehicles? findVehicles;
  final void Function(
    BoardingCandidate,
    BoardingSelectionContext, {
    required String boarding,
    required String target,
  })?
  onSelectCandidate;

  @override
  State<BoardingStopScreen> createState() => _BoardingStopScreenState();
}

class _BoardingStopScreenState extends State<BoardingStopScreen> {
  late final int _generation;
  int _stopsEpoch = 0;
  int _candidateEpoch = 0;
  _StopsState _state = _StopsState.loading;
  List<BusRouteStopOccurrence> _stops = const [];
  BusRouteStopOccurrence? _target;
  BusRouteStopOccurrence? _boarding;
  _Picker _picker = _Picker.boarding;
  BoardingCandidates? _result;
  BoardingCandidate? _selectedCandidate;
  DateTime? _receivedAt;
  bool _loadingCandidates = false;
  String? _error;
  bool _staleStops = false;
  BusRouteVehicleController? _vehicles;

  BusRouteVehicleController? _newVehicles() {
    final find =
        widget.findVehicles ?? BusRouteVehiclesScope.maybeFind(context);
    return find == null
        ? null
        : BusRouteVehicleController(routeId: widget.route.id, find: find);
  }

  bool get _current =>
      mounted &&
      widget.authSession.isCurrentGeneration(_generation) &&
      !widget.authSession.isLoggingOut;

  @override
  void initState() {
    super.initState();
    _generation = widget.authSession.generation;
    widget.authSession.addListener(_authChanged);
    _vehicles = _newVehicles();
    _loadStops();
  }

  @override
  void didUpdateWidget(covariant BoardingStopScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.route.id != widget.route.id) {
      _vehicles?.dispose();
      _vehicles = _newVehicles();
      _loadStops();
    }
  }

  void _invalidateCandidates() {
    _candidateEpoch++;
    _result = null;
    _selectedCandidate = null;
    _receivedAt = null;
    _loadingCandidates = false;
    _error = null;
    _staleStops = false;
  }

  void _authChanged() {
    if (_current || !mounted) return;
    setState(() {
      _stopsEpoch++;
      _stops = const [];
      _target = null;
      _boarding = null;
      _invalidateCandidates();
    });
  }

  @override
  void dispose() {
    _stopsEpoch++;
    _candidateEpoch++;
    _vehicles?.dispose();
    widget.authSession.removeListener(_authChanged);
    super.dispose();
  }

  Future<void> _loadStops() async {
    if (!_current) return;
    final epoch = ++_stopsEpoch;
    final routeId = widget.route.id;
    _vehicles?.refresh();
    setState(() {
      _invalidateCandidates();
      _target = null;
      _boarding = null;
      _picker = _Picker.boarding;
      _stops = const [];
      _state = _StopsState.loading;
    });
    try {
      final stops = await widget.findStops(routeId);
      if (!_current || epoch != _stopsEpoch) return;
      setState(() {
        _stops = stops;
        _state = stops.isEmpty ? _StopsState.empty : _StopsState.success;
      });
    } catch (_) {
      if (_current && epoch == _stopsEpoch) {
        setState(() => _state = _StopsState.error);
      }
    }
  }

  bool _validTarget(BusRouteStopOccurrence stop) =>
      _boarding != null && stop.order > _boarding!.order;

  void _select(BusRouteStopOccurrence stop) {
    if (!_current || _staleStops || _result != null) {
      return;
    }
    final earlier =
        _picker == _Picker.target &&
        _boarding != null &&
        stop.order < _boarding!.order;
    if (_picker == _Picker.target && !earlier && !_validTarget(stop)) return;
    setState(() {
      if (_picker == _Picker.boarding || _picker == _Picker.review || earlier) {
        _boarding = stop;
        _target = null;
        _picker = _Picker.target;
      } else {
        _target = stop;
        _picker = _Picker.review;
      }
      _invalidateCandidates();
    });
  }

  /// [keepResult]가 true면 기존 후보 화면을 그대로 두고 응답이 오면 내용만 바꾼다.
  /// 실패하면 기존 내용을 유지한 채 오류만 안내한다.
  Future<void> _findCandidates({bool keepResult = false}) async {
    final target = _target;
    if (!_current ||
        widget.creationBusy ||
        target == null ||
        _boarding == null ||
        !_validTarget(target) ||
        _loadingCandidates ||
        _staleStops) {
      return;
    }
    if (keepResult && _result != null) {
      _candidateEpoch++;
      _error = null;
    } else {
      _invalidateCandidates();
    }
    _vehicles?.refresh();
    final epoch = _candidateEpoch;
    final routeId = widget.route.id;
    final boardingId = _boarding?.id;
    bool current() =>
        _current &&
        epoch == _candidateEpoch &&
        routeId == widget.route.id &&
        target.id == _target?.id &&
        boardingId == _boarding?.id;
    setState(() {
      _picker = _Picker.review;
      _loadingCandidates = true;
    });
    try {
      final result = await widget.findCandidates(
        routeId: routeId,
        targetStopOccurrenceId: target.id,
        boardingStopOccurrenceId: boardingId,
        expectedGeneration: _generation,
        isRequestCurrent: current,
      );
      if (!current()) return;
      setState(() {
        _result = result;
        _receivedAt = DateTime.now().toUtc();
        // 새 응답이 오면 선택은 해제한다.
        _selectedCandidate = null;
      });
    } on BoardingCandidateException catch (error) {
      if (!current()) return;
      setState(() {
        _staleStops = error.statusCode == 400 || error.statusCode == 404;
        _error = _staleStops
            ? '정류장 정보가 변경되었을 수 있어요. 목록을 다시 조회해 정류장을 선택해 주세요.'
            : switch (error.failure) {
                CandidateFailure.parsing =>
                  '차량 후보 응답 형식을 확인할 수 없어요. 다시 조회해 주세요.',
                CandidateFailure.timeout => '차량 후보 조회 시간이 초과됐어요. 다시 조회해 주세요.',
                CandidateFailure.network =>
                  '통신 오류로 차량 후보를 불러오지 못했어요. 연결을 확인하고 다시 조회해 주세요.',
                CandidateFailure.http =>
                  error.statusCode == 503
                      ? '버스 정보를 일시적으로 불러올 수 없어요. 잠시 후 다시 조회해 주세요.'
                      : '차량 후보를 불러오지 못했어요. 다시 조회해 주세요.',
              };
      });
    } catch (_) {
      if (current()) setState(() => _error = '차량 후보를 불러오지 못했어요. 다시 조회해 주세요.');
    } finally {
      if (current()) setState(() => _loadingCandidates = false);
    }
  }

  String _stopLabel(BusRouteStopOccurrence stop) {
    final details = selectedStopContext(stop, _stops);
    return details == null ? stop.name : '${stop.name}\n$details';
  }

  void _reselectStops() {
    if (!_current || widget.creationBusy || _staleStops) return;
    setState(() {
      _boarding = null;
      _target = null;
      _picker = _Picker.boarding;
      _invalidateCandidates();
    });
  }

  bool get _showingCandidates => _result != null || _loadingCandidates;

  String get _backLabel {
    if (_showingCandidates) return '정류장 선택으로';
    if (_picker == _Picker.target || _picker == _Picker.review) {
      return '승차 정류장 선택으로';
    }
    return '노선 다시 선택';
  }

  void _back() {
    if (!_current || widget.creationBusy) return;
    if (_showingCandidates) {
      setState(_invalidateCandidates);
    } else if (_picker == _Picker.target || _picker == _Picker.review) {
      _reselectStops();
    } else {
      widget.onBack();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    return PopScope<Object?>(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _back();
      },
      child: BusStopSelectionLayout(
        route: widget.route,
        title: _compactResults ? '차량 후보' : '하차 알람',
        onBack: _back,
        backLabel: _backLabel,
        actions: _compactResults
            ? [_candidateRefreshAction()]
            : [
                if (_vehicles != null)
                  ListenableBuilder(
                    listenable: _vehicles!,
                    builder: (_, _) => IconButton(
                      tooltip: '차량 위치 새로고침',
                      onPressed: _vehicles!.loading ? null : _vehicles!.refresh,
                      icon: const Icon(Icons.refresh),
                    ),
                  ),
              ],
        iconColor: routeTypeColor(context, widget.route),
        body: _state == _StopsState.success ? _routeAndCandidates() : _status(),
        summary:
            _state == _StopsState.success &&
                !(_compactResults && _selectedCandidate == null)
            ? _summary()
            : null,
      ),
    );
  }

  Widget _candidateRefreshAction() {
    Widget button() {
      final busy = _loadingCandidates || (_vehicles?.loading ?? false);
      return IconButton(
        key: const ValueKey('find-candidates'),
        tooltip: '차량 위치 새로고침',
        onPressed: busy || widget.creationBusy
            ? null
            : () => _findCandidates(keepResult: true),
        icon: busy
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.refresh, size: 20),
      );
    }

    final vehicles = _vehicles;
    if (vehicles == null) return button();
    return ListenableBuilder(listenable: vehicles, builder: (_, _) => button());
  }

  Widget _status() => Center(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_state == _StopsState.loading) ...[
            const CircularProgressIndicator(),
            const SizedBox(height: 12),
            const Text('정류장 조회 중...'),
          ] else ...[
            Text(
              _state == _StopsState.empty
                  ? '정류장 정보가 없어요.'
                  : '정류장 정보를 불러오지 못했어요.',
            ),
            OutlinedButton(
              onPressed: _loadStops,
              child: const Text('정류장 다시 조회'),
            ),
          ],
        ],
      ),
    ),
  );

  bool get _compactResults => _result != null && !widget.reconfirm;

  bool _canUseCandidate(BoardingCandidate candidate) =>
      _current &&
      _result?.candidateSetStatus != CandidateSetStatus.unknown &&
      (_result?.candidates.contains(candidate) ?? false) &&
      !widget.creationBusy &&
      (widget.reconfirm
          ? canConfirmBoardingCandidate(candidate)
          : candidate.canCreateWithNumber &&
                _result!.candidates
                        .where(
                          (item) =>
                              item.vehicleNumber != null &&
                              item.vehicleNumber == candidate.vehicleNumber,
                        )
                        .length ==
                    1);

  void _openConfirmation(BoardingCandidate candidate) {
    final target = _target;
    final boarding = _boarding;
    if (target == null || boarding == null || !_canUseCandidate(candidate)) {
      return;
    }
    widget.onSelectCandidate?.call(
      candidate,
      BoardingSelectionContext(
        routeId: widget.route.id,
        targetId: target.id,
        boardingId: boarding.id,
      ),
      boarding: _stopLabel(boarding),
      target: _stopLabel(target),
    );
  }

  Widget _timelineWithVehicles() {
    final vehicles = _vehicles;
    BusRouteTimeline timeline() => BusRouteTimeline(
      stops: _stops,
      targetId: _target?.id,
      boardingId: _boarding?.id,
      isSelectable: _picker == _Picker.target
          ? (stop) => _validTarget(stop) || stop.order < _boarding!.order
          : null,
      rowKey: (id) => ValueKey('occurrence-$id'),
      vehicles: vehicles?.data?.vehicles,
      vehicleColor:
          routeTypeColor(context, widget.route) ??
          StopBellColors.of(context).busLocation,
      onSelect: _staleStops || _result != null ? null : _select,
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

  Widget _routeAndCandidates() => IndexedStack(
    index: _result == null ? 0 : 1,
    sizing: StackFit.expand,
    children: [
      _timelineWithVehicles(),
      if (_result case final result?)
        SingleChildScrollView(
          key: const ValueKey('boarding-candidate-results'),
          primary: false,
          padding: const EdgeInsets.all(StopBellSpacing.screenPadding),
          child: _compactResults
              ? _compactCandidateList(result, _boarding!, _target!)
              : BoardingCandidatesView(
                  result: result,
                  receivedAt: _receivedAt!,
                  onSelect: widget.onSelectCandidate == null
                      ? null
                      : _openConfirmation,
                ),
        )
      else
        const SizedBox.shrink(),
    ],
  );

  Widget _compactCandidateList(
    BoardingCandidates result,
    BusRouteStopOccurrence boarding,
    BusRouteStopOccurrence target,
  ) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _miniRoute(boarding, target),
      if (_error case final error?)
        Padding(
          padding: const EdgeInsets.only(top: StopBellSpacing.xs),
          child: StopSelectionNotice(error, icon: Icons.error_outline),
        ),
      const SizedBox(height: StopBellSpacing.lg),
      _candidateBuses(result, boarding, target),
    ],
  );

  /// 차량 위치 API가 있으면 후보와 차량번호로 연결해 노선도에 그리고, 못 그린 후보는
  /// 아래 목록에 둔다. 없으면 기존 후보 목록만 보여 준다.
  Widget _candidateBuses(
    BoardingCandidates result,
    BusRouteStopOccurrence boarding,
    BusRouteStopOccurrence target,
  ) {
    final vehicles = _vehicles;
    String? holdReason(BoardingCandidate candidate) => compactHoldReason(
      candidate,
      canChoose: result.candidateSetStatus != CandidateSetStatus.unknown,
      duplicate:
          candidate.vehicleNumber != null &&
          result.candidates
                  .where((o) => o.vehicleNumber == candidate.vehicleNumber)
                  .length >
              1,
    );
    void select(BoardingCandidate candidate) {
      if (_canUseCandidate(candidate)) {
        setState(
          () => _selectedCandidate = identical(_selectedCandidate, candidate)
              ? null
              : candidate,
        );
      }
    }

    BoardingCandidatesView list({List<BoardingCandidate>? visible}) =>
        BoardingCandidatesView.compact(
          result: result,
          receivedAt: _receivedAt!,
          progressLabel: (candidate) => boardingCandidateProgressLabel(
            candidate,
            _stops,
            boardingId: boarding.id,
            targetId: target.id,
          ),
          selected: _selectedCandidate,
          visible: visible,
          showIntro: false,
          onSelect: widget.onSelectCandidate == null ? null : select,
        );

    Widget build(List<BusRouteVehicle> located) {
      final split = splitBoardingBuses(
        candidates: result.candidates,
        vehicles: located,
        stops: _stops,
        targetId: target.id,
      );
      final theme = Theme.of(context);
      // 선택한 후보가 화면에서 빠지면 선택을 해제한다.
      final sel = _selectedCandidate;
      if (sel != null &&
          !split.drawn.any((b) => identical(b.candidate, sel)) &&
          !split.others.contains(sel)) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted && identical(_selectedCandidate, sel)) {
            setState(() => _selectedCandidate = null);
          }
        });
      }
      // 목표를 지난 후보는 표시하지 않는다. 전부 빠지면 후보 없음 안내를 보인다.
      final noneLeft =
          result.candidates.isNotEmpty &&
          split.drawn.isEmpty &&
          split.others.isEmpty;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: StopBellSpacing.md,
        children: [
          _CandidateSectionHeader(result: result, controller: vehicles),
          list(visible: vehicles == null ? split.others : const []),
          if (split.drawn.isNotEmpty)
            BoardingBusTimeline(
              stops: _stops,
              boardingId: boarding.id,
              targetId: target.id,
              buses: split.drawn,
              selected: _selectedCandidate,
              canSelect: (candidate) =>
                  widget.onSelectCandidate != null &&
                  _canUseCandidate(candidate),
              holdReason: holdReason,
              onSelect: select,
              vehicleColor:
                  routeTypeColor(context, widget.route) ??
                  StopBellColors.of(context).busLocation,
            ),
          if (noneLeft)
            Text('현재 확인 가능한 차량 후보가 없어요.', style: theme.textTheme.bodyMedium),
          if (vehicles != null && split.others.isNotEmpty) ...[
            Text('위치 확인 불가', style: theme.textTheme.titleSmall),
            list(visible: split.others),
          ],
        ],
      );
    }

    if (vehicles == null) return build(const []);
    return ListenableBuilder(
      listenable: vehicles,
      builder: (_, _) => build(vehicles.data?.vehicles ?? const []),
    );
  }

  Widget _summary() {
    if (_compactResults) {
      final selected = _selectedCandidate;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (selected != null) ...[
            Row(
              children: [
                const Text('알림 시점'),
                const SizedBox(width: 12),
                Expanded(
                  child: SegmentedButton<int>(
                    segments: const [
                      ButtonSegment(value: 2, label: Text('2개 전')),
                      ButtonSegment(value: 1, label: Text('1개 전')),
                    ],
                    selected: {widget.notificationOffsetStops},
                    onSelectionChanged: widget.creationBusy
                        ? null
                        : (values) => widget.onNotificationOffsetChanged?.call(
                            values.single,
                          ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: StopBellSpacing.sm),
          ],
          FilledButton(
            key: const ValueKey('continue-boarding-candidate'),
            onPressed:
                selected != null &&
                    widget.onSelectCandidate != null &&
                    _canUseCandidate(selected)
                ? () => _openConfirmation(selected)
                : null,
            child: Text(
              widget.creationBusy ? '등록 중…' : '이 차량으로 하차 알람 설정',
              textAlign: TextAlign.center,
            ),
          ),
        ],
      );
    }
    final boarding = _boarding;
    final target = _target;
    if (_picker == _Picker.boarding || boarding == null) {
      return const StopSelectionNotice(
        '노선도에서 승차 정류장을 선택해 주세요.',
        icon: Icons.directions_bus_outlined,
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: StopBellSpacing.sm,
      children: [
        if (_picker == _Picker.target || target == null) ...[
          const StopSelectionNotice(
            '승차 정류장 이후의 정류장을 선택해 주세요',
            icon: Icons.flag_outlined,
          ),
          _summaryCard(_stopSummary(boarding, target: false)),
        ] else ...[
          _summaryCard(
            Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _stopSummary(boarding, target: false),
                const Divider(
                  height: 1,
                  indent: StopBellSpacing.sm,
                  endIndent: StopBellSpacing.sm,
                ),
                _stopSummary(target, target: true),
              ],
            ),
          ),
          if (!_staleStops)
            FilledButton(
              key: const ValueKey('find-candidates'),
              onPressed: !_validTarget(target) || _loadingCandidates
                  ? null
                  : _findCandidates,
              child: Text(
                _result == null && _error == null ? '차량 후보 조회' : '차량 후보 다시 조회',
                textAlign: TextAlign.center,
              ),
            ),
          if (_loadingCandidates) ...[
            const LinearProgressIndicator(),
            const Text('차량 후보를 조회하고 있어요.'),
          ],
          if (_error case final error?)
            StopSelectionNotice(error, icon: Icons.error_outline),
          if (_staleStops)
            OutlinedButton(
              onPressed: _loadStops,
              child: const Text('정류장 목록 다시 선택'),
            ),
        ],
      ],
    );
  }

  Widget _miniRoute(
    BusRouteStopOccurrence boarding,
    BusRouteStopOccurrence target,
  ) {
    final theme = Theme.of(context);
    final color =
        routeTypeColor(context, widget.route) ??
        StopBellColors.of(context).busLocation;
    Widget row(BusRouteStopOccurrence stop, String label, IconData icon) => Row(
      children: [
        SizedBox(
          width: 18,
          child: Icon(icon, size: label == '승차' ? 10 : 18, color: color),
        ),
        const SizedBox(width: StopBellSpacing.sm),
        Text(
          label,
          style: theme.textTheme.labelSmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(width: StopBellSpacing.sm),
        Expanded(
          child: Text(
            stop.name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.titleSmall,
          ),
        ),
      ],
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        row(boarding, '승차', Icons.circle),
        Padding(
          padding: const EdgeInsets.only(left: 8),
          child: Container(width: 1, height: 8, color: color),
        ),
        row(target, '목표', Icons.flag_outlined),
      ],
    );
  }

  Widget _summaryCard(Widget child) {
    final colors = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: colors.surface,
        border: Border.all(color: colors.outlineVariant),
        borderRadius: BorderRadius.circular(StopBellRadius.card),
      ),
      child: child,
    );
  }

  Widget _stopSummary(
    BusRouteStopOccurrence stop, {
    required bool target,
    bool compact = false,
  }) {
    final role = target
        ? (compact ? '목표' : '목표 정류장')
        : (compact ? '승차' : '승차 정류장');
    final name = stopDisplayName(stop.name).name;
    final repeated = _stops.any(
      (other) =>
          other.id != stop.id && stopDisplayName(other.name).name == name,
    );
    final details = !compact || repeated
        ? selectedStopContext(stop, _stops)
        : null;
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final stopColors = StopBellColors.of(context);
    final accent = target ? stopColors.targetStop : stopColors.boardingStop;
    return Padding(
      key: ValueKey(target ? 'summary-target' : 'summary-boarding'),
      padding: compact
          ? const EdgeInsets.symmetric(vertical: StopBellSpacing.xs)
          : const EdgeInsets.all(StopBellSpacing.sm),
      child: Row(
        children: [
          Icon(
            target ? Icons.flag_outlined : Icons.directions_bus_outlined,
            size: 18,
            color: accent,
          ),
          const SizedBox(width: StopBellSpacing.sm),
          Expanded(
            child: Text.rich(
              TextSpan(
                text: '$role · ',
                style: theme.textTheme.labelSmall?.copyWith(color: accent),
                children: [
                  TextSpan(
                    text: stop.name,
                    style: theme.textTheme.titleSmall?.copyWith(
                      color: colors.onSurface,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (details != null)
                    TextSpan(
                      text: '\n$details',
                      style: theme.textTheme.labelMedium,
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 후보 화면 전용 헤더. 위치 안내의 시간 경과만 갱신하며 자동 조회하지 않는다.
class _CandidateSectionHeader extends StatefulWidget {
  const _CandidateSectionHeader({
    required this.result,
    required this.controller,
  });

  final BoardingCandidates result;
  final BusRouteVehicleController? controller;

  @override
  State<_CandidateSectionHeader> createState() =>
      _CandidateSectionHeaderState();
}

class _CandidateSectionHeaderState extends State<_CandidateSectionHeader> {
  final _helpKey = GlobalKey<TooltipState>();
  bool _tooltipDismissedOnPointerDown = false;
  Timer? _timer;
  bool _outdated = false;

  @override
  void initState() {
    super.initState();
    widget.controller?.addListener(_changed);
    _syncTimer();
  }

  @override
  void didUpdateWidget(covariant _CandidateSectionHeader oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller?.removeListener(_changed);
      widget.controller?.addListener(_changed);
    }
    _syncTimer();
  }

  void _syncTimer() {
    _timer?.cancel();
    _outdated = widget.controller?.isOutdated(DateTime.now()) ?? false;
    final fetched = widget.controller?.fetchedAt;
    if (fetched == null) return;
    final left =
        BusRouteVehicleController.staleAfter -
        DateTime.now().difference(fetched);
    if (left > Duration.zero) {
      _timer = Timer(left, () {
        if (mounted) setState(() => _outdated = true);
      });
    }
  }

  void _changed() {
    _syncTimer();
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _timer?.cancel();
    widget.controller?.removeListener(_changed);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final caption = theme.textTheme.labelMedium?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    final controller = widget.controller;
    final data = controller?.data;
    final time = MaterialLocalizations.of(context).formatTimeOfDay(
      TimeOfDay.fromDateTime(
        (data?.observedAt ?? widget.result.observedAt).toLocal(),
      ),
      alwaysUse24HourFormat: true,
    );
    final unplaced =
        data?.vehicles.where((v) => v.occurrenceId == null).length ?? 0;
    final notes = <String>[
      if (widget.result.candidateSetStatus == CandidateSetStatus.unknown)
        '후보 목록의 상태를 확인할 수 없어요. 다시 조회해 주세요.',
      if (controller?.failed ?? false)
        '차량 정보를 불러오지 못했어요'
      else if (_outdated)
        '위치가 오래됐어요. 새로고침해 주세요',
      if (data?.status == BusRouteVehiclesStatus.stale)
        '최신 위치를 받지 못해 이전 위치를 보여드려요',
      if (data?.status == BusRouteVehiclesStatus.partial)
        '일부 차량의 위치·좌석을 확인하지 못했어요',
      if (data?.status == BusRouteVehiclesStatus.empty &&
          data!.vehicles.isEmpty)
        '지금 위치를 확인할 수 있는 차량이 없어요',
      if (unplaced > 0) '위치 확인 불가 $unplaced대',
      if (widget.result.candidateSetStatus == CandidateSetStatus.empty)
        '현재 확인 가능한 차량 후보가 없어요.',
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: StopBellSpacing.xs,
      children: [
        Row(
          key: const ValueKey('candidate-section-heading'),
          children: [
            Flexible(
              child: Text(
                '차량 후보 선택',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleLarge,
              ),
            ),
            Tooltip(
              key: _helpKey,
              ignorePointer: true,
              message: '차량번호를 확인하고 선택해 주세요. 전체 차량번호를 확인해 주세요. 시각은 서버 조회 기준이에요. 최근 위치는 진행 참고 정류장이며 간격에는 미정차·경유가 포함될 수 있어요.',
              triggerMode: TooltipTriggerMode.manual,
              exitDuration: Duration.zero,
              child: Semantics(
                label: '차량 선택 도움말',
                button: true,
                child: ExcludeSemantics(
                  child: Listener(
                    onPointerDown: (_) {
                      _tooltipDismissedOnPointerDown =
                          Tooltip.dismissAllToolTips();
                    },
                    child: IconButton(
                      onPressed: () {
                        if (!_tooltipDismissedOnPointerDown) {
                          _helpKey.currentState?.ensureTooltipVisible();
                        }
                        _tooltipDismissedOnPointerDown = false;
                      },
                      icon: const Icon(Icons.info_outline),
                      constraints: const BoxConstraints(
                        minWidth: 44,
                        minHeight: 44,
                      ),
                      padding: EdgeInsets.zero,
                      iconSize: 16,
                    ),
                  ),
                ),
              ),
            ),
            const Spacer(),
            Flexible(
              child: Text(
                controller != null && data == null ? '차량 위치 확인 중' : '$time 기준',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.end,
              ),
            ),
          ],
        ),
        for (final note in notes) Text(note, style: caption),
      ],
    );
  }
}
