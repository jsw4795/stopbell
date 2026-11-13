import '../../shared/stopbell_skeleton.dart';
import '../../shared/stopbell_delayed_loading.dart';
import '../../shared/stopbell_home_button.dart';

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_spacing.dart';

import '../auth/auth_session.dart';
import 'boarding_session.dart';
import 'boarding_session_client.dart';
import 'boarding_session_view.dart';

/// Exact-ID, read-only entry. Never reads/writes creation intents or current scope.
class BoardingNotificationScreen extends StatefulWidget {
  const BoardingNotificationScreen({
    super.key,
    required this.auth,
    required this.client,
    required this.sessionId,
    required this.onBack,
    required this.isNavigationCurrent,
  });
  final AuthSession auth;
  final BoardingSessionClient? client;
  final int sessionId;
  final VoidCallback onBack;
  final bool Function() isNavigationCurrent;
  @override
  State<BoardingNotificationScreen> createState() =>
      _BoardingNotificationScreenState();
}

class _BoardingNotificationScreenState
    extends State<BoardingNotificationScreen> {
  late final int _generation;
  late final String? _user, _login;
  BoardingSession? _session;
  String? _error;
  bool _busy = true;
  int _epoch = 0;
  bool get _current =>
      mounted &&
      widget.isNavigationCurrent() &&
      widget.auth.isCurrentGeneration(_generation) &&
      !widget.auth.isLoggingOut &&
      widget.auth.userIdentity == _user &&
      widget.auth.sessionIdentity == _login;
  @override
  void initState() {
    super.initState();
    _generation = widget.auth.generation;
    _user = widget.auth.userIdentity;
    _login = widget.auth.sessionIdentity;
    widget.auth.addListener(_authChanged);
    _load();
  }

  void _authChanged() {
    if (mounted && !_current) {
      setState(() {
        _epoch++;
        _session = null;
        _error = null;
      });
    }
  }

  @override
  void dispose() {
    _epoch++;
    widget.auth.removeListener(_authChanged);
    super.dispose();
  }

  Future<void> _load() async {
    if (!_current) return;
    final epoch = ++_epoch;
    bool current() => _current && epoch == _epoch;
    setState(() {
      _busy = true;
      _session = null;
      _error = null;
    });
    try {
      final session = await widget.client!.find(
        widget.sessionId,
        generation: _generation,
        current: current,
      );
      if (!current()) return;
      if (session.id != widget.sessionId) {
        throw const BoardingSessionException(SessionFailure.parsing);
      }
      setState(() => _session = session);
    } catch (error) {
      if (!current()) return;
      setState(
        () => _error =
            error is BoardingSessionException && error.statusCode == 404
            ? '이 하차 알람의 세션을 열 수 없어요. 세션이 없거나 현재 계정에서 접근할 수 없어요.'
            : '세션 상태를 확인하지 못했어요. 다시 조회해 주세요.',
      );
    } finally {
      if (current()) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    return Column(
      children: [
        if (_session == null)
          AppBar(
            primary: false,
            leading: IconButton(
              onPressed: widget.onBack,
              tooltip: '도착 알람으로',
              icon: const Icon(Icons.arrow_back),
            ),
            title: const Text('하차 알람'),
            actions: [const StopBellHomeButton()],
          ),
        Expanded(
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(StopBellSpacing.lg),
            children: [
              if (_session == null)
                StopBellDelayedLoading(
                  loading: _busy,
                  skeleton: const StopBellSkeletonList(count: 2),
                  onRetry: _load,
                  child: const SizedBox.shrink(),
                )
              else if (_busy)
                const Text('서버 응답 확인 중...'),
              if (_error != null) ...[
                Text(_error!),
                OutlinedButton(
                  onPressed: _busy ? null : _load,
                  child: const Text('다시 조회'),
                ),
              ],
              if (_session != null)
                BoardingSessionDetails(
                  session: _session!,
                  onBack: widget.onBack,
                  onRefresh: _busy ? null : _load,
                  showRefresh:
                      _session!.status != BoardingSessionStatus.cancelled,
                ),
            ],
          ),
        ),
      ],
    );
  }
}
