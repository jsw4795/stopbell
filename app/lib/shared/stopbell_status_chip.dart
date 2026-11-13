import 'package:flutter/material.dart';

import '../core/theme/stopbell_spacing.dart';

/// 상태 칩. [color]의 옅은 배경에 같은 색 글자·점·아이콘을 쓴다.
class StopBellStatusChip extends StatelessWidget {
  const StopBellStatusChip(
    this.label,
    this.color, {
    super.key,
    this.icon,
    this.dot = false,
  });

  final String label;
  final Color color;
  final IconData? icon;
  final bool dot;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(
      horizontal: StopBellSpacing.sm,
      vertical: 3,
    ),
    decoration: BoxDecoration(
      color: color.withValues(alpha: .14),
      borderRadius: BorderRadius.circular(StopBellRadius.button),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (dot)
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          )
        else if (icon != null)
          Icon(icon, size: 14, color: color),
        if (dot || icon != null) const SizedBox(width: StopBellSpacing.sm),
        Flexible(
          child: Text(
            label,
            style: Theme.of(context).textTheme.labelSmall
                ?.copyWith(color: color),
          ),
        ),
      ],
    ),
  );
}
