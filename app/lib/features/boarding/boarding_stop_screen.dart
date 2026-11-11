import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import '../auth/auth_session.dart';
import '../transit/bus_route.dart';
import '../transit/bus_route_stop_occurrence.dart';
import '../transit/bus_route_timeline.dart';
import '../transit/bus_stop_selection_layout.dart';
import '../transit/stop_display_name.dart';
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
  });
  final BusRoute route;
  final AuthSession authSession;
  final Future<List<BusRouteStopOccurrence>> Function(int) findStops;
  final FindBoardingCandidates findCandidates;
  final VoidCallback onBack;
  final bool reconfirm, creationBusy;
  final int notificationOffsetStops;
  final ValueChanged<int>? onNotificationOffsetChanged;
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

  bool get _current =>
      mounted &&
      widget.authSession.isCurrentGeneration(_generation) &&
      !widget.authSession.isLoggingOut;

  @override
  void initState() {
    super.initState();
    _generation = widget.authSession.generation;
    widget.authSession.addListener(_authChanged);
    _loadStops();
  }

  @override
  void didUpdateWidget(covariant BoardingStopScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.route.id != widget.route.id) _loadStops();
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
    widget.authSession.removeListener(_authChanged);
    super.dispose();
  }

  Future<void> _loadStops() async {
    if (!_current) return;
    final epoch = ++_stopsEpoch;
    final routeId = widget.route.id;
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
    if (!_current ||
        _staleStops ||
        _result != null ||
        _picker == _Picker.review) {
      return;
    }
    if (_picker == _Picker.target && !_validTarget(stop)) return;
    setState(() {
      if (_picker == _Picker.boarding) {
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

  Future<void> _findCandidates() async {
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
    _invalidateCandidates();
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
    if (_picker == _Picker.target) return '승차 정류장 선택으로';
    return '노선 다시 선택';
  }

  void _back() {
    if (!_current || widget.creationBusy) return;
    if (_showingCandidates) {
      setState(_invalidateCandidates);
    } else if (_picker == _Picker.target) {
      setState(() => _picker = _Picker.boarding);
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
        title: _compactResults ? '차량 후보' : '하차 알림',
        onBack: _back,
        backLabel: _backLabel,
        body: _state == _StopsState.success ? _routeAndCandidates() : _status(),
        summary: _state == _StopsState.success ? _summary() : null,
      ),
    );
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
                  ? '정류장 정보가 없습니다.'
                  : '정류장 정보를 불러오지 못했습니다.',
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

  Widget _routeAndCandidates() => IndexedStack(
    index: _result == null ? 0 : 1,
    sizing: StackFit.expand,
    children: [
      BusRouteTimeline(
        stops: _stops,
        targetId: _target?.id,
        boardingId: _boarding?.id,
        isSelectable: _picker == _Picker.target ? _validTarget : null,
        selectionLocked: _picker == _Picker.review,
        rowKey: (id) => ValueKey('occurrence-$id'),
        onSelect: _staleStops || _result != null || _picker == _Picker.review
            ? null
            : _select,
      ),
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
      _stopSummary(boarding, target: false, compact: true),
      _stopSummary(target, target: true, compact: true),
      Wrap(
        spacing: StopBellSpacing.xs,
        children: [
          TextButton(
            key: const ValueKey('reselect-stops'),
            onPressed: widget.creationBusy ? null : _reselectStops,
            child: const Text('정류장 다시 선택'),
          ),
          TextButton(
            key: const ValueKey('find-candidates'),
            onPressed: widget.creationBusy ? null : _findCandidates,
            child: const Text('차량 후보 다시 조회'),
          ),
        ],
      ),
      const Divider(height: StopBellSpacing.lg),
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
        onSelect: widget.onSelectCandidate == null
            ? null
            : (candidate) {
                if (_canUseCandidate(candidate)) {
                  setState(() => _selectedCandidate = candidate);
                }
              },
      ),
    ],
  );

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
              widget.creationBusy ? '등록 중…' : '이 차량으로 하차 알림 설정',
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
        '노선도에서 승차 정류장을 선택하세요.',
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
            StopSelectionPair(
              first: OutlinedButton(
                key: const ValueKey('reselect-stops'),
                onPressed: widget.creationBusy ? null : _reselectStops,
                child: const Text('정류장 다시 선택', textAlign: TextAlign.center),
              ),
              second: FilledButton(
                key: const ValueKey('find-candidates'),
                onPressed: !_validTarget(target) || _loadingCandidates
                    ? null
                    : _findCandidates,
                child: Text(
                  _result == null && _error == null
                      ? '차량 후보 조회'
                      : '차량 후보 다시 조회',
                  textAlign: TextAlign.center,
                ),
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
