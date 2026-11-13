import 'package:flutter/material.dart';

import '../core/theme/stopbell_spacing.dart';

/// 섹션 제목. 기본형은 아이콘·개수 배지를 가진 홈 목록 제목이고,
/// [StopBellSectionTitle.label]은 설정 화면처럼 글자만 쓰는 보조 제목이다.
class StopBellSectionTitle extends StatelessWidget {
  const StopBellSectionTitle(
    this.title, {
    super.key,
    required this.icon,
    this.count,
  }) : _label = false;

  const StopBellSectionTitle.label(this.title, {super.key})
    : icon = null,
      count = null,
      _label = true;

  final String title;
  final IconData? icon;
  final int? count;
  final bool _label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (_label) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(
          StopBellSpacing.xs,
          StopBellSpacing.lg,
          StopBellSpacing.xs,
          StopBellSpacing.sm,
        ),
        child: Text(
          title,
          style: theme.textTheme.labelLarge?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
      );
    }
    return Semantics(
      header: true,
      child: Row(
        children: [
          Icon(icon, size: 22, color: theme.colorScheme.primary),
          const SizedBox(width: StopBellSpacing.sm),
          Flexible(child: Text(title, style: theme.textTheme.titleLarge)),
          if (count != null) ...[
            const SizedBox(width: StopBellSpacing.sm),
            Container(
              padding: const EdgeInsets.symmetric(
                horizontal: StopBellSpacing.sm,
                vertical: 2,
              ),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(StopBellSpacing.sm),
              ),
              child: Text(
                '$count개',
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
