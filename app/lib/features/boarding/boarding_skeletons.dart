import 'package:flutter/material.dart';

import '../../shared/stopbell_skeleton.dart';

class BoardingCandidateSkeleton extends StatelessWidget {
  const BoardingCandidateSkeleton({super.key});
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Row(
        children: [
          Expanded(child: StopBellSkeleton(height: 42)),
          SizedBox(width: 12),
          Expanded(child: StopBellSkeleton(height: 42)),
        ],
      ),
      const SizedBox(height: 16),
      const StopBellSkeleton(width: 120, height: 20),
      const SizedBox(height: 12),
      for (var i = 0; i < 3; i++)
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              child: Row(
                children: [
                  const StopBellSkeleton(width: 20, height: 20, radius: 10),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        StopBellSkeleton(width: 120, height: 20),
                        SizedBox(height: 4),
                        StopBellSkeleton(width: 150, height: 16),
                        SizedBox(height: 4),
                        StopBellSkeleton(width: 90, height: 14),
                      ],
                    ),
                  ),
                  const StopBellSkeleton(width: 50, height: 24),
                ],
              ),
            ),
          ),
        ),
    ],
  );
}

class BoardingDetailSkeleton extends StatelessWidget {
  const BoardingDetailSkeleton({super.key});
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(
            children: [
              StopBellSkeleton(width: 22, height: 22),
              SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    StopBellSkeleton(width: 80, height: 20),
                    SizedBox(height: 4),
                    StopBellSkeleton(width: 100, height: 14),
                  ],
                ),
              ),
              StopBellSkeleton(width: 55, height: 24),
            ],
          ),
        ),
      ),
      SizedBox(height: 12),
      Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                StopBellSkeleton(width: 40, height: 14),
                SizedBox(height: 4),
                StopBellSkeleton(height: 20),
              ],
            ),
          ),
          SizedBox(width: 12),
          StopBellSkeleton(width: 24, height: 24),
          SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                StopBellSkeleton(width: 40, height: 14),
                SizedBox(height: 4),
                StopBellSkeleton(height: 20),
              ],
            ),
          ),
        ],
      ),
      SizedBox(height: 16),
      Center(child: StopBellSkeleton(width: 120, height: 32, radius: 16)),
      SizedBox(height: 24),
      StopBellSkeleton(width: 110, height: 20),
      SizedBox(height: 12),
      for (var i = 0; i < 4; i++)
        Padding(
          padding: EdgeInsets.only(bottom: 8),
          child: StopBellSkeleton(height: 52),
        ),
    ],
  );
}
