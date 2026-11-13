import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_spacing.dart';
import '../../shared/stopbell_skeleton.dart';
import 'bus_route_timeline.dart';

/// 정류장 타임라인의 기본 두 줄 행을 닮은 자리표시.
class StopTimelineSkeleton extends StatelessWidget {
  const StopTimelineSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final painter = TextPainter(
      text: TextSpan(
        text: '정류장',
        style: theme.textTheme.titleMedium!.copyWith(
          fontWeight: FontWeight.w600,
        ),
        children: [TextSpan(text: '\n방면', style: theme.textTheme.bodySmall)],
      ),
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
      locale: Localizations.maybeLocaleOf(context),
      textHeightBehavior: DefaultTextStyle.of(context).textHeightBehavior,
    )..layout();
    final height = math.max(
      52.0,
      painter.height.ceilToDouble() + 2 * StopBellSpacing.sm,
    );
    painter.dispose();
    return Column(
      children: [
        const Divider(height: 1),
        for (var i = 0; i < 7; i++)
          SizedBox(
            key: ValueKey('skeleton-stop-row-$i'),
            height: height,
            child: Row(
              children: [
                SizedBox(
                  width: busRouteTimelineRailWidth,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      const SizedBox(
                        width: 2,
                        height: double.infinity,
                        child: StopBellSkeleton(
                          width: 2,
                          height: double.infinity,
                          radius: 0,
                        ),
                      ),
                      const StopBellSkeleton(width: 10, height: 10, radius: 5),
                    ],
                  ),
                ),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const StopBellSkeleton(width: 130, height: 16),
                      const SizedBox(height: 4),
                      const StopBellSkeleton(width: 85, height: 12),
                    ],
                  ),
                ),
                const SizedBox(width: StopBellSpacing.screenPadding),
              ],
            ),
          ),
      ],
    );
  }
}

class RouteSearchSkeleton extends StatelessWidget {
  const RouteSearchSkeleton({super.key});

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (var i = 0; i < 4; i++)
        Card(
          margin: const EdgeInsets.only(bottom: StopBellSpacing.xs),
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: StopBellSpacing.md,
              vertical: StopBellSpacing.sm,
            ),
            child: Row(
              children: [
                const StopBellSkeleton(width: 24, height: 24),
                const SizedBox(width: StopBellSpacing.md),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SizedBox(
                        height:
                            Theme.of(context).textTheme.titleLarge!.fontSize! *
                            1.4,
                        child: const Align(
                          alignment: Alignment.centerLeft,
                          child: StopBellSkeleton(width: 80, height: 20),
                        ),
                      ),
                      SizedBox(
                        height:
                            Theme.of(context).textTheme.bodySmall!.fontSize! *
                            1.4,
                        child: const Align(
                          alignment: Alignment.centerLeft,
                          child: StopBellSkeleton(width: 150, height: 12),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: StopBellSpacing.xs),
                const StopBellSkeleton(width: 24, height: 24),
              ],
            ),
          ),
        ),
    ],
  );
}
