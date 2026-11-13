import 'dart:async';

import 'package:flutter/material.dart';

import '../core/theme/stopbell_spacing.dart';

/// 로딩 중에만 [skeleton]을 보여 주는 지연 표시 래퍼. 규칙은 다음과 같다.
/// - [delay](0.3초) 안에 끝나면 아무것도 깜빡이지 않는다.
/// - 한 번 표시했으면 최소 [minVisible] 동안 유지한다.
/// - [slowAfter](8초)가 지나면 지연 안내를, [retryAfter](15초)가 지나면
///   [onRetry]가 있을 때 다시 시도 버튼을 보여 준다.
/// 로딩이 끝나면 [child]를 보여 준다. 로딩 전 구간(delay 이전)에는 빈 공간이다.
class StopBellDelayedLoading extends StatefulWidget {
  const StopBellDelayedLoading({
    super.key,
    required this.loading,
    required this.skeleton,
    required this.child,
    this.onRetry,
    this.delay = const Duration(milliseconds: 300),
    this.minVisible = const Duration(milliseconds: 400),
    this.slowAfter = const Duration(seconds: 8),
    this.retryAfter = const Duration(seconds: 15),
  });

  final bool loading;
  final Widget skeleton;
  final Widget child;
  final VoidCallback? onRetry;
  final Duration delay;
  final Duration minVisible;
  final Duration slowAfter;
  final Duration retryAfter;

  @override
  State<StopBellDelayedLoading> createState() => _StopBellDelayedLoadingState();
}

class _StopBellDelayedLoadingState extends State<StopBellDelayedLoading> {
  final _timers = <Timer>[];
  bool _shown = false;
  bool _minElapsed = true;
  bool _slow = false;
  bool _retry = false;

  @override
  void initState() {
    super.initState();
    if (widget.loading) _start();
  }

  @override
  void didUpdateWidget(StopBellDelayedLoading old) {
    super.didUpdateWidget(old);
    if (widget.loading == old.loading) return;
    if (widget.loading) {
      _reset();
      _start();
    } else {
      _cancelNotices();
      // 표시 중이었고 최소 유지 시간이 남았으면 minTimer가 끝날 때 내린다.
      if (_shown && _minElapsed) _shown = false;
    }
  }

  void _start() {
    _timers.add(
      Timer(widget.delay, () {
        if (!mounted || !widget.loading) return;
        setState(() {
          _shown = true;
          _minElapsed = false;
        });
        _timers.add(
          Timer(widget.minVisible, () {
            if (!mounted) return;
            setState(() {
              _minElapsed = true;
              if (!widget.loading) _shown = false;
            });
          }),
        );
      }),
    );
    _timers.add(Timer(widget.slowAfter, () => _flag(() => _slow = true)));
    _timers.add(Timer(widget.retryAfter, () => _flag(() => _retry = true)));
  }

  void _flag(VoidCallback change) {
    if (!mounted || !widget.loading) return;
    setState(change);
  }

  void _cancelNotices() {
    _slow = false;
    _retry = false;
    // delay·minVisible 타이머는 남기고 안내 타이머만 의미를 잃게 한다(_flag가 loading을 확인).
  }

  void _reset() {
    for (final timer in _timers) {
      timer.cancel();
    }
    _timers.clear();
    _shown = false;
    _minElapsed = true;
    _slow = false;
    _retry = false;
  }

  @override
  void dispose() {
    for (final timer in _timers) {
      timer.cancel();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_shown) return _buildLoading(context);
    return widget.loading ? const SizedBox.shrink() : widget.child;
  }

  Widget _buildLoading(BuildContext context) {
    final theme = Theme.of(context);
    final onRetry = widget.onRetry;
    return Semantics(
      label: '불러오는 중',
      liveRegion: true,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ExcludeSemantics(child: widget.skeleton),
          if (_slow && widget.loading) ...[
            const SizedBox(height: StopBellSpacing.md),
            Text(
              '응답이 늦어지고 있어요',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
          if (_retry && widget.loading && onRetry != null) ...[
            const SizedBox(height: StopBellSpacing.sm),
            Center(
              child: OutlinedButton(
                onPressed: onRetry,
                child: const Text('다시 시도'),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
