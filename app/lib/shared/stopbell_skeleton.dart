import 'package:flutter/material.dart';

import '../core/theme/stopbell_spacing.dart';

/// 로딩 중 자리표시 블록. 디자인 토큰(surfaceContainerHighest)을 쓰므로 라이트·다크에 모두 맞는다.
/// '동작 줄이기'(MediaQuery.disableAnimations)가 켜져 있으면 반짝임 없이 정적 블록만 그린다.
class StopBellSkeleton extends StatefulWidget {
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
  State<StopBellSkeleton> createState() => _StopBellSkeletonState();
}

class _StopBellSkeletonState extends State<StopBellSkeleton>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  );
  late final Animation<double> _opacity = Tween<double>(
    begin: .45,
    end: 1,
  ).animate(CurvedAnimation(parent: _controller, curve: Curves.easeInOut));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.disableAnimationsOf(context)) {
      _controller.stop();
    } else if (!_controller.isAnimating) {
      _controller.repeat(reverse: true);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final block = Container(
      width: widget.width,
      height: widget.height,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(widget.radius),
      ),
    );
    if (MediaQuery.disableAnimationsOf(context)) return block;
    return FadeTransition(opacity: _opacity, child: block);
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
