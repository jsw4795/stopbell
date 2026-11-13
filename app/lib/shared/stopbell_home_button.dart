import 'package:flutter/material.dart';

class StopBellHomeButton extends StatelessWidget {
  const StopBellHomeButton({super.key});

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: '홈으로',
    constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
    onPressed: () {
      Navigator.of(context).popUntil((route) => route.isFirst);
    },
    icon: const Icon(Icons.home_outlined),
  );
}
