import 'package:flutter/material.dart';

import '../core/theme/stopbell_spacing.dart';

/// 버튼 라벨. [busy]이면 라벨 앞에 작은 스피너를 둔다. 동작 중 중복 탭은
/// 호출하는 쪽에서 `onPressed: null`로 막는다.
class StopBellButtonLabel extends StatelessWidget {
  const StopBellButtonLabel(this.label, {super.key, this.busy = false});

  final String label;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    if (!busy) return Text(label);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const SizedBox.square(
          dimension: 16,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
        const SizedBox(width: StopBellSpacing.sm),
        Flexible(child: Text(label)),
      ],
    );
  }
}
