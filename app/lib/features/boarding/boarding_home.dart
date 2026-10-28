import 'package:flutter/material.dart';

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
  BusRoute? _route;
  late final int _generation;
  BoardingSessionController? _controller;
  ({
    BoardingCandidate candidate,
    BoardingSelectionContext selection,
    String route,
    String target,
  })?
  _draft;
  bool _reconfirm = false, _newSelection = false;
  BoardingSelectionContext? _reconfirmContext;
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

  bool get _current =>
      mounted &&
      widget.authSession.isCurrentGeneration(_generation) &&
      !widget.authSession.isLoggingOut;
  void _changed() {
    if (mounted) {
      setState(() {
        if (!_current) {
          _route = null;
          _draft = null;
          _reconfirmContext = null;
        }
      });
    }
  }

  @override
  void dispose() {
    widget.authSession.removeListener(_changed);
    _controller?.removeListener(_changed);
    _controller?.dispose();
    super.dispose();
  }

  void _select(
    BoardingCandidate candidate,
    BoardingSelectionContext selection,
    String route,
    String target,
  ) {
    if (!_current || _controller!.busy || _controller!.needsRecovery) return;
    _notificationOffsetStops = 2;
    setState(
      () => _draft = (
        candidate: candidate,
        selection: selection,
        route: route,
        target: target,
      ),
    );
  }

  int _notificationOffsetStops = 2;

  Future<void> _confirm(String number) async {
    final draft = _draft;
    if (!_current || draft == null) return;
    final c = _controller!;
    if (_reconfirm) {
      await c.reconfirm(draft.candidate, number, draft.selection);
    } else {
      await c.create(
        draft.candidate,
        number,
        draft.selection,
        refreshSelected: (selected) async {
          if (selected.selectionKey == null) return null;
          final result = await widget.findCandidates(
            routeId: draft.selection.routeId,
            targetStopOccurrenceId: draft.selection.targetId,
            boardingStopOccurrenceId: draft.selection.boardingId,
            expectedGeneration: _generation,
            isRequestCurrent: () => _current && identical(_draft, draft),
          );
          if (!_current) return null;
          final matches = result.candidates
              .where((item) => item.selectionKey == selected.selectionKey)
              .toList();
          return matches.length == 1 ? matches.single : null;
        },
        notificationOffsetStops: _notificationOffsetStops,
        preparePush: () =>
            widget.beforeCreate?.call(context) ??
            prepareBoardingPush(context, null),
      );
    }
    if (_current) {
      setState(() {
        _draft = null;
        _reconfirm = false;
        _reconfirmContext = null;
        _newSelection = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    final c = _controller;
    if (c != null) {
      if (!c.initialized || c.lookupFailed || c.needsRecovery) {
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            TextButton(
              onPressed: c.busy ? null : widget.onBack,
              child: const Text('버스 도착 알림으로'),
            ),
            Text(c.needsRecovery ? '원래 생성 요청 결과 확인' : '현재 하차 알림 확인'),
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
      final draft = _draft;
      if (draft != null) {
        return Column(
          children: [
            if (c.message != null) Text(c.message!),
            Expanded(
              child: BoardingConfirmationView(
                key: ValueKey(draft.candidate.candidateRef),
                candidate: draft.candidate,
                routeNumber: draft.route,
                targetName: draft.target,
                onConfirm: _confirm,
                onNotificationOffsetChanged: (value) =>
                    _notificationOffsetStops = value,
                onBack: () => setState(() => _draft = null),
                busy: c.busy,
                reconfirm: _reconfirm,
              ),
            ),
          ],
        );
      }
      if (_reconfirmContext != null) {
        return BoardingReconfirmationScreen(
          controller: c,
          selectionContext: _reconfirmContext!,
          findCandidates: widget.findCandidates,
          onBack: () => setState(() {
            _reconfirm = false;
            _reconfirmContext = null;
          }),
          onReselect: () => setState(() {
            _reconfirmContext = null;
            _route = null;
          }),
          onSelect: (candidate) => _select(
            candidate,
            _reconfirmContext!,
            c.session!.routeNumber,
            c.session!.targetName,
          ),
        );
      }
      if (c.session != null && !_reconfirm && !_newSelection) {
        return BoardingSessionView(
          controller: c,
          onBack: widget.onBack,
          onNew: () => setState(() {
            _newSelection = true;
            _route = null;
          }),
          onReconfirm: () => setState(() {
            _reconfirm = true;
            _route = null;
            _reconfirmContext = c.selectionContext;
          }),
        );
      }
    }
    final route = _route;
    final body = route != null
        ? BoardingStopScreen(
            key: ValueKey(route.id),
            route: route,
            authSession: widget.authSession,
            findStops: widget.findStops,
            findCandidates: widget.findCandidates,
            onSelectCandidate: c == null
                ? null
                : (candidate, selection, target) =>
                      _select(candidate, selection, route.routeNumber, target),
            onBack: () {
              if (_current) setState(() => _route = null);
            },
          )
        : Column(
            children: [
              TextButton(
                onPressed: widget.onBack,
                child: const Text('버스 도착 알림으로'),
              ),
              const Text('하차 알림 · 노선 선택'),
              Expanded(
                child: BusRouteSearchScreen(
                  search: widget.searchRoutes,
                  onSelect: (route) {
                    if (_current) setState(() => _route = route);
                  },
                ),
              ),
            ],
          );
    return Column(
      children: [
        if (c?.message != null) Text(c!.message!),
        if (_reconfirm) ...[
          const Text(
            '기존 선택 정보를 복원할 수 없어요. 원래 노선과 목적·승차 정류장을 다시 선택해 주세요. 기존 차량만 재확인할 수 있어요.',
          ),
          TextButton(
            onPressed: () => setState(() {
              _reconfirm = false;
              _route = null;
            }),
            child: const Text('세션 상세로'),
          ),
        ],
        Expanded(child: body),
      ],
    );
  }
}
