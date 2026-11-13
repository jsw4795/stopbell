import 'package:flutter/material.dart';

import '../core/theme/stopbell_spacing.dart';

/// 화면의 스켈레톤 블록이 하나의 애니메이션을 공유한다.
class StopBellShimmer extends StatefulWidget {
  const StopBellShimmer({super.key, required this.child});

  final Widget child;

  @override
  State<StopBellShimmer> createState() => _StopBellShimmerState();
}

class _StopBellShimmerState extends State<StopBellShimmer>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1400),
  );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.disableAnimationsOf(context)) {
      _controller.stop();
    } else if (!_controller.isAnimating) {
      _controller.repeat();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) =>
      _ShimmerScope(animation: _controller, child: widget.child);
}

class _ShimmerScope extends InheritedWidget {
  const _ShimmerScope({required this.animation, required super.child});

  final Animation<double> animation;

  @override
  bool updateShouldNotify(_ShimmerScope oldWidget) =>
      animation != oldWidget.animation;
}

/// 디자인 토큰을 사용하는 로딩 자리표시 블록.
class StopBellSkeleton extends StatelessWidget {
  const StopBellSkeleton({
    super.key,
    this.width,
    this.height = 16,
    this.radius = StopBellSpacing.xs,
  });

  final double? width;
  final double height;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final block = Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        color: colors.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(radius),
      ),
    );
    final animation = context
        .dependOnInheritedWidgetOfExactType<_ShimmerScope>()
        ?.animation;
    if (MediaQuery.disableAnimationsOf(context) || animation == null) {
      return block;
    }
    return AnimatedBuilder(
      animation: animation,
      child: block,
      builder: (context, child) => ShaderMask(
        blendMode: BlendMode.srcIn,
        shaderCallback: (bounds) {
          final position = animation.value * 3 - 1;
          return LinearGradient(
            begin: Alignment(position - 1, 0),
            end: Alignment(position + 1, 0),
            colors: [
              colors.surfaceContainerHighest,
              Color.alphaBlend(
                (colors.brightness == Brightness.light
                        ? colors.surface
                        : colors.onSurface)
                    .withValues(alpha: .65),
                colors.surfaceContainerHighest,
              ),
              colors.surfaceContainerHighest,
            ],
            stops: const [0.25, 0.5, 0.75],
          ).createShader(bounds);
        },
        child: child,
      ),
    );
  }
}

/// 카드형 목록 항목의 자리표시. 제목·부제 두 줄과 우측 칩 모양으로 구성한다.
class StopBellSkeletonCard extends StatelessWidget {
  const StopBellSkeletonCard({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.all(StopBellSpacing.lg),
      decoration: BoxDecoration(
        border: Border.all(color: theme.colorScheme.outlineVariant),
        borderRadius: BorderRadius.circular(StopBellRadius.card),
      ),
      child: const Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                StopBellSkeleton(width: 140, height: 18),
                SizedBox(height: StopBellSpacing.sm),
                StopBellSkeleton(height: 14),
              ],
            ),
          ),
          SizedBox(width: StopBellSpacing.lg),
          StopBellSkeleton(width: 56, height: 28, radius: StopBellSpacing.md),
        ],
      ),
    );
  }
}

/// [StopBellSkeletonCard]를 [count]개 세로로 쌓은 목록 자리표시.
class StopBellSkeletonList extends StatelessWidget {
  const StopBellSkeletonList({super.key, this.count = 3});

  final int count;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (var i = 0; i < count; i++)
        const Padding(
          padding: EdgeInsets.only(bottom: StopBellSpacing.sm),
          child: StopBellSkeletonCard(),
        ),
    ],
  );
}
