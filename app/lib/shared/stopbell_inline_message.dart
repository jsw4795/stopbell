import 'package:flutter/material.dart';

import '../core/theme/stopbell_spacing.dart';

/// 화면 안에 표시하는 오류 안내. [compact]는 좁은 안내(폼 상단 등),
/// [action]은 재시도 같은 우측 동작이다.
class StopBellInlineMessage extends StatelessWidget {
  const StopBellInlineMessage(
    this.message, {
    super.key,
    this.action,
    this.compact = false,
  });

  final String message;
  final Widget? action;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = compact
        ? Text(message, style: TextStyle(color: colors.onErrorContainer))
        : Text(message);
    final body = Container(
      padding: EdgeInsets.all(
        compact ? StopBellSpacing.md : StopBellSpacing.lg,
      ),
      decoration: BoxDecoration(
        color: colors.errorContainer,
        borderRadius: BorderRadius.circular(StopBellRadius.card),
      ),
      child: Row(
        crossAxisAlignment: compact
            ? CrossAxisAlignment.start
            : CrossAxisAlignment.center,
        children: [
          Icon(
            Icons.error_outline,
            color: colors.onErrorContainer,
            size: compact ? 20 : null,
          ),
          SizedBox(width: compact ? StopBellSpacing.sm : StopBellSpacing.md),
          Expanded(child: text),
          ?action,
        ],
      ),
    );
    return compact ? Semantics(liveRegion: true, child: body) : body;
  }
}
