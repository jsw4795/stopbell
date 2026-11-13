import 'package:flutter/material.dart';

class StopBellHomeScope extends InheritedWidget {
  const StopBellHomeScope({
    super.key,
    required this.onHome,
    required super.child,
  });

  final VoidCallback onHome;

  static VoidCallback? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<StopBellHomeScope>()?.onHome;

  @override
  bool updateShouldNotify(StopBellHomeScope oldWidget) =>
      onHome != oldWidget.onHome;
}

class StopBellHomeButton extends StatelessWidget {
  const StopBellHomeButton({super.key});

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: '홈으로',
    constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
    onPressed: () {
      final home = StopBellHomeScope.maybeOf(context);
      Navigator.of(context).popUntil((route) => route.isFirst);
      home?.call();
    },
    icon: const Icon(Icons.home_outlined),
  );
}
