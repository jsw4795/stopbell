import 'package:flutter/material.dart';

import '../../core/theme/stopbell_spacing.dart';
import '../../shared/stopbell_skeleton.dart';

class AlarmListSkeleton extends StatelessWidget {
  const AlarmListSkeleton({super.key});

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (var i = 0; i < 3; i++)
        Padding(
          padding: const EdgeInsets.only(bottom: StopBellSpacing.sm),
          child: Container(
            decoration: BoxDecoration(
              border: Border.all(
                color: Theme.of(context).colorScheme.outlineVariant,
              ),
              borderRadius: BorderRadius.circular(StopBellRadius.card),
            ),
            child: const IntrinsicHeight(
              child: Row(
                children: [
                  StopBellSkeleton(
                    width: 4,
                    height: double.infinity,
                    radius: 0,
                  ),
                  Expanded(
                    child: Padding(
                      padding: EdgeInsets.fromLTRB(16, 12, 8, 12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              StopBellSkeleton(width: 65, height: 22),
                              Spacer(),
                              StopBellSkeleton(width: 55, height: 24),
                            ],
                          ),
                          SizedBox(height: 4),
                          StopBellSkeleton(width: 150, height: 20),
                          SizedBox(height: 4),
                          StopBellSkeleton(width: 180, height: 16),
                        ],
                      ),
                    ),
                  ),
                  Padding(
                    padding: EdgeInsets.only(right: 12),
                    child: Center(
                      child: StopBellSkeleton(width: 24, height: 24),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
    ],
  );
}

class AlarmDetailSkeleton extends StatelessWidget {
  const AlarmDetailSkeleton({super.key});

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  StopBellSkeleton(width: 24, height: 24),
                  SizedBox(width: 8),
                  StopBellSkeleton(width: 70, height: 28),
                  SizedBox(width: 8),
                  StopBellSkeleton(width: 55, height: 24),
                ],
              ),
              SizedBox(height: 4),
              StopBellSkeleton(width: 160, height: 24),
              SizedBox(height: 4),
              StopBellSkeleton(width: 200, height: 16),
            ],
          ),
        ),
      ),
      SizedBox(height: 12),
      Card(
        margin: EdgeInsets.zero,
        child: Column(
          children: [
            for (var i = 0; i < 3; i++)
              SizedBox(
                height: 56,
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 16),
                  child: Row(
                    children: [
                      StopBellSkeleton(width: 130, height: 16),
                      Spacer(),
                      StopBellSkeleton(width: 52, height: 28),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    ],
  );
}
