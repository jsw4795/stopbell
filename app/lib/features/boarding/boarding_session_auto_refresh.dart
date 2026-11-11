import 'dart:async';

import 'package:flutter/material.dart';

import 'boarding_session.dart';
import 'boarding_session_controller.dart';

/// The timer belongs to the visible detail, not the longer-lived controller.
class BoardingSessionAutoRefresh extends StatefulWidget {
  const BoardingSessionAutoRefresh({
    super.key,
    required this.controller,
    required this.child,
  });
  final BoardingSessionController controller;
  final Widget child;

  @override
  State<BoardingSessionAutoRefresh> createState() =>
      _BoardingSessionAutoRefreshState();
}

class _BoardingSessionAutoRefreshState extends State<BoardingSessionAutoRefresh>
    with WidgetsBindingObserver {
  Timer? _timer;
  bool _foreground = true, _routeVisible = true, _pending = false;
  int _epoch = 0, _manualToken = 0;

  bool get _visible =>
      mounted &&
      _foreground &&
      _routeVisible &&
      widget.controller.current &&
      widget.controller.session?.status == BoardingSessionStatus.tracking &&
      !widget.controller.inaccessible;

  @override
  void initState() {
    super.initState();
    _foreground =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    _manualToken = widget.controller.manualRefreshToken;
    WidgetsBinding.instance.addObserver(this);
    widget.controller.addListener(_changed);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _activate();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final visible = ModalRoute.of(context)?.isCurrent ?? true;
    if (_routeVisible != visible) {
      _routeVisible = visible;
      if (visible) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _activate();
        });
      } else {
        _deactivate();
      }
    }
  }

  void _activate() {
    if (!_visible) return;
    _pending = true;
    _schedule();
    _tryImmediate();
  }

  void _schedule() {
    _timer?.cancel();
    if (_visible) {
      _timer = Timer.periodic(const Duration(seconds: 20), (_) => _poll());
    }
  }

  void _tryImmediate() {
    if (!_pending ||
        !_visible ||
        widget.controller.autoRefreshing ||
        widget.controller.refreshing ||
        widget.controller.busy) {
      return;
    }
    _pending = false;
    _poll();
  }

  void _poll() {
    if (!_visible ||
        widget.controller.autoRefreshing ||
        widget.controller.refreshing ||
        widget.controller.busy) {
      return;
    }
    final epoch = _epoch;
    final id = widget.controller.session!.id;
    unawaited(
      widget.controller.refreshAutomatically(
        () =>
            _visible && _epoch == epoch && widget.controller.session?.id == id,
      ),
    );
  }

  void _changed() {
    if (!mounted) return;
    if (!_visible) {
      _deactivate();
      return;
    }
    if (_manualToken != widget.controller.manualRefreshToken) {
      _manualToken = widget.controller.manualRefreshToken;
      _schedule();
    }
    _timer ??= Timer.periodic(const Duration(seconds: 20), (_) => _poll());
    if (_pending &&
        !widget.controller.autoRefreshing &&
        !widget.controller.refreshing &&
        !widget.controller.busy) {
      scheduleMicrotask(() {
        if (mounted) _tryImmediate();
      });
    }
  }

  void _deactivate() {
    _epoch++;
    _pending = false;
    _timer?.cancel();
    _timer = null;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (_foreground) {
      _activate();
    } else {
      _deactivate();
    }
  }

  @override
  void dispose() {
    _deactivate();
    widget.controller.removeListener(_changed);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
