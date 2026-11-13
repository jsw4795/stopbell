import '../../shared/stopbell_home_button.dart';

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_spacing.dart';

import '../auth/auth_session.dart';
import '../notification/push_activation_prompt.dart';
import '../transit/bus_route.dart';
import '../transit/bus_route_search_screen.dart';
import '../transit/bus_route_stop_occurrence.dart';
import 'boarding_candidate_client.dart';
import 'boarding_candidates.dart';
import 'boarding_confirmation_view.dart';
import 'boarding_creation_intent.dart';
import 'boarding_reconfirmation_screen.dart';
import 'boarding_session_client.dart';
import 'boarding_session_controller.dart';
import 'boarding_session_view.dart';
import 'boarding_session_auto_refresh.dart';
import 'boarding_stop_screen.dart';

class BoardingHome extends StatefulWidget {
  const BoardingHome({
    super.key,
    required this.authSession,
    required this.searchRoutes,
    required this.findStops,
    required this.findCandidates,
    required this.onBack,
    this.sessionClient,
    this.intentStorage,
    this.beforeCreate,
  });
  final AuthSession authSession;
  final Future<List<BusRoute>> Function(String) searchRoutes;
  final Future<List<BusRouteStopOccurrence>> Function(int) findStops;
  final FindBoardingCandidates findCandidates;
  final VoidCallback onBack;
  final BoardingSessionClient? sessionClient;
  final BoardingIntentStorage? intentStorage;
  final Future<bool> Function(BuildContext)? beforeCreate;
  @override
  State<BoardingHome> createState() => _BoardingHomeState();
}

class _BoardingHomeState extends State<BoardingHome> {
  late final int _generation;
  BoardingSessionController? _controller;
  int? _boardingNameSessionId;
  String? _boardingName;
  final _notificationOffsetStops = ValueNotifier<int>(2);

  bool get _current =>
      mounted &&
      widget.authSession.isCurrentGeneration(_generation) &&
      !widget.authSession.isLoggingOut;

  @override
  void initState() {
    super.initState();
    _generation = widget.authSession.generation;
    widget.authSession.addListener(_changed);
    if (widget.sessionClient != null) {
      _controller = BoardingSessionController(
        auth: widget.authSession,
        client: widget.sessionClient!,
        storage: widget.intentStorage ?? const SecureBoardingIntentStorage(),
      )..addListener(_changed);
      _controller!.initialize();
    }
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    widget.authSession.removeListener(_changed);
    _controller?.removeListener(_changed);
    _controller?.dispose();
    _notificationOffsetStops.dispose();
    super.dispose();
  }

  void _back() => Navigator.of(context).maybePop();

  void _showSession() {
    if (!_current) return;
    final route = ModalRoute.of(context);
    Navigator.of(context).popUntil((candidate) => candidate == route);
    setState(() {});
  }

  void _push(WidgetBuilder builder) {
    if (!_current) return;
    Navigator.of(context).push<void>(
      MaterialPageRoute<void>(
        builder: (context) => Scaffold(
          body: SafeArea(
            child: ListenableBuilder(
              listenable: Listenable.merge([
                widget.authSession,
                ?_controller,
                _notificationOffsetStops,
              ]),
              builder: (_, _) =>
                  _current ? builder(context) : const SizedBox.shrink(),
            ),
          ),
        ),
      ),
    );
  }

  void _openSearch({bool reconfirm = false}) =>
      _push((context) => _search(context, reconfirm: reconfirm));

  Widget _search(BuildContext context, {bool reconfirm = false}) => Column(
    children: [
      AppBar(
        primary: false,
        leading: IconButton(
          tooltip: '도착 알람으로',
          onPressed: () => Navigator.of(context).maybePop(),
          icon: const Icon(Icons.arrow_back),
        ),
        title: const Text('하차 알람 · 노선 선택'),
        actions: const [StopBellHomeButton()],
      ),
      if (reconfirm)
        const Padding(
          padding: EdgeInsets.all(StopBellSpacing.lg),
          child: Text(
            '기존 선택 정보를 복원할 수 없어요. 원래 노선과 목적·승차 정류장을 다시 선택해 주세요. 기존 차량만 재확인할 수 있어요.',
          ),
        ),
      Expanded(
        child: BusRouteSearchScreen(
          search: widget.searchRoutes,
          onSelect: (route) => _openStops(route, reconfirm: reconfirm),
        ),
      ),
    ],
  );

  void _openStops(BusRoute route, {required bool reconfirm}) {
    _push(
      (context) => BoardingStopScreen(
        key: ValueKey(route.id),
        route: route,
        authSession: widget.authSession,
        findStops: widget.findStops,
        findCandidates: widget.findCandidates,
        reconfirm: reconfirm,
        creationBusy: _controller?.busy ?? false,
        creationMessage: _controller?.message,
        notificationOffsetStops: _notificationOffsetStops.value,
        onNotificationOffsetChanged: (value) =>
            _notificationOffsetStops.value = value,
        onSelectCandidate: _controller == null
            ? null
            : (candidate, selection, {required boarding, required target}) {
                if (reconfirm) {
                  _openConfirmation(
                    candidate,
                    selection,
                    route.routeNumber,
                    target,
                    route: route,
                    boarding: boarding,
                  );
                } else {
                  _create(candidate, selection, boarding, context);
                }
              },
        onBack: () => Navigator.of(context).maybePop(),
      ),
    );
  }

  Future<void> _create(
    BoardingCandidate candidate,
    BoardingSelectionContext selection,
    String? boarding,
    BuildContext pageContext,
  ) async {
    final c = _controller!;
    final page = ModalRoute.of(pageContext);
    if (!c.canCreate || !_current || page?.isActive != true) return;
    final previousSessionId = c.session?.id;
    await c.create(
      candidate,
      candidate.vehicleNumber!,
      selection,
      notificationOffsetStops: _notificationOffsetStops.value,
      preparePush: () async {
        final allowed =
            await (widget.beforeCreate?.call(pageContext) ??
                prepareBoardingPush(pageContext, null));
        return _current && page?.isActive == true && allowed;
      },
    );
    if (!_current || page?.isActive != true) return;
    if (c.needsRecovery) {
      _showSession();
      return;
    }
    if (c.creationState == BoardingCreationState.resolved) {
      if (c.session != null && c.session!.id != previousSessionId) {
        _boardingNameSessionId = c.session!.id;
        _boardingName = boarding;
      }
      _showSession();
    }
  }

  void _openConfirmation(
    BoardingCandidate candidate,
    BoardingSelectionContext selection,
    String routeNumber,
    String target, {
    BusRoute? route,
    String? boarding,
  }) {
    final c = _controller!;
    if (!_current || c.busy || c.needsRecovery) return;
    _push(
      (context) => Column(
        children: [
          if (c.message != null) Text(c.message!),
          Expanded(
            child: BoardingConfirmationView(
              candidate: candidate,
              routeNumber: routeNumber,
              route: route,
              boardingName: boarding,
              targetName: target,
              onConfirm: (number) async {
                final page = ModalRoute.of(context);
                await c.reconfirm(candidate, number, selection);
                if (_current && page?.isActive == true) _showSession();
              },
              initialNotificationOffsetStops: _notificationOffsetStops.value,
              onNotificationOffsetChanged: (value) =>
                  _notificationOffsetStops.value = value,
              onBack: () => Navigator.of(context).maybePop(),
              busy: c.busy,
              reconfirm: true,
            ),
          ),
        ],
      ),
    );
  }

  void _openReconfirmation() {
    final c = _controller!;
    final selection = c.selectionContext;
    if (selection == null) {
      _openSearch(reconfirm: true);
      return;
    }
    _push(
      (context) => BoardingReconfirmationScreen(
        controller: c,
        selectionContext: selection,
        findCandidates: widget.findCandidates,
        onBack: () => Navigator.of(context).maybePop(),
        onReselect: () => _openSearch(reconfirm: true),
        onSelect: (candidate) => _openConfirmation(
          candidate,
          selection,
          c.session!.routeNumber,
          c.session!.targetName,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    final c = _controller;
    if (c != null) {
      if (!c.initialized ||
          c.lookupFailed ||
          (c.needsRecovery &&
              c.creationState != BoardingCreationState.submitting)) {
        return ListView(
          padding: const EdgeInsets.all(StopBellSpacing.lg),
          children: [
            TextButton(
              onPressed: c.busy ? null : _back,
              child: const Text('도착 알람으로'),
            ),
            Text(c.needsRecovery ? '원래 생성 요청 결과 확인' : '현재 하차 알람 확인'),
            if (c.message != null) Text(c.message!),
            if (c.busy) const Text('서버 응답 확인 중...'),
            if (!c.busy)
              OutlinedButton(
                onPressed: c.needsRecovery ? c.recover : c.initialize,
                child: const Text('결과 다시 조회'),
              ),
            if (c.needsRecovery)
              FilledButton(
                onPressed: c.busy ? null : c.retryCreation,
                child: const Text('원래 생성 요청 그대로 재시도'),
              ),
          ],
        );
      }
      if (c.session != null) {
        return BoardingSessionAutoRefresh(
          key: ValueKey(c.session!.id),
          controller: c,
          child: BoardingSessionView(
            controller: c,
            boardingName: _boardingNameSessionId == c.session!.id
                ? _boardingName
                : null,
            onBack: _back,
            onNew: _openSearch,
            onReconfirm: _openReconfirmation,
          ),
        );
      }
    }
    return _search(context);
  }
}
