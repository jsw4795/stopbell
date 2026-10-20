import 'package:flutter/material.dart';

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
            ? '이 하차 알림의 세션을 열 수 없어요. 세션이 없거나 현재 계정에서 접근할 수 없어요.'
            : '세션 상태를 확인하지 못했어요. 다시 조회해 주세요.',
      );
    } finally {
      if (current()) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        TextButton(onPressed: widget.onBack, child: const Text('버스 도착 알림으로')),
        Text('하차 알림 · 세션 상세', style: Theme.of(context).textTheme.titleLarge),
        const Text('알림이 가리킨 세션의 서버 조회 결과예요. 알림 도착은 현재 추적·하차 성공을 뜻하지 않아요.'),
        if (_busy) const Text('서버 응답 확인 중...'),
        if (_error != null) Text(_error!),
        if (_session != null) BoardingSessionDetails(session: _session!),
        OutlinedButton(
          onPressed: _busy ? null : _load,
          child: Text(_error == null ? '상태 새로고침' : '다시 조회'),
        ),
      ],
    );
  }
}
