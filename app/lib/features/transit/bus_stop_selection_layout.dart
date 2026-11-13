import 'package:flutter/material.dart';

import '../../core/theme/stopbell_spacing.dart';
import 'bus_route.dart';

class BusStopSelectionLayout extends StatelessWidget {
  const BusStopSelectionLayout({
    super.key,
    required this.route,
    required this.title,
    required this.onBack,
    required this.body,
    this.summary,
    this.backLabel = '노선 다시 선택',
    this.iconColor,
  });

  final BusRoute route;
  final String title;
  final VoidCallback? onBack;
  final Widget body;
  final Widget? summary;
  final String backLabel;

  /// 노선 유형색. 없으면 기본색을 쓴다.
  final Color? iconColor;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return SafeArea(
      child: Material(
        color: colors.surface,
        child: LayoutBuilder(
          builder: (context, constraints) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              AppBar(
                primary: false,
                leading: IconButton(
                  tooltip: backLabel,
                  onPressed: onBack,
                  icon: const Icon(Icons.arrow_back),
                ),
                title: Text(title),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: StopBellSpacing.screenPadding,
                ),
                child: Row(
                  children: [
                    ExcludeSemantics(
                      child: Icon(
                        Icons.directions_bus_outlined,
                        color: iconColor ?? colors.primary,
                      ),
                    ),
                    const SizedBox(width: StopBellSpacing.sm),
                    Text(
                      route.routeNumber,
                      style: theme.textTheme.headlineSmall,
                    ),
                    const SizedBox(width: StopBellSpacing.sm),
                    Expanded(
                      child: Text(
                        route.regionName,
                        style: theme.textTheme.labelMedium,
                      ),
                    ),
                  ],
                ),
              ),
              Expanded(child: body),
              if (summary != null) ...[
                const Divider(height: 1),
                Material(
                  color: colors.surfaceContainerLow,
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      maxHeight: constraints.maxHeight * 0.42,
                    ),
                    child: SingleChildScrollView(
                      key: const ValueKey('stop-selection-summary'),
                      primary: false,
                      padding: const EdgeInsets.symmetric(
                        horizontal: StopBellSpacing.screenPadding,
                        vertical: StopBellSpacing.sm,
                      ),
                      child: summary,
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Keep related controls side by side when there is room for their labels.
class StopSelectionPair extends StatelessWidget {
  const StopSelectionPair({
    super.key,
    required this.first,
    required this.second,
  });

  final Widget first;
  final Widget second;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final scale = MediaQuery.textScalerOf(context).scale(16) / 16;
        if (constraints.maxWidth < 320 * scale) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              first,
              const SizedBox(height: StopBellSpacing.xs),
              second,
            ],
          );
        }
        return Row(
          children: [
            Expanded(child: first),
            const SizedBox(width: StopBellSpacing.sm),
            Expanded(child: second),
          ],
        );
      },
    );
  }
}

class StopSelectionNotice extends StatelessWidget {
  const StopSelectionNotice(this.message, {super.key, required this.icon});

  final String message;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: colors.surfaceContainer,
        borderRadius: BorderRadius.circular(StopBellRadius.card),
      ),
      child: Padding(
        padding: const EdgeInsets.all(StopBellSpacing.md),
        child: Row(
          children: [
            Icon(icon, size: 18, color: colors.primary),
            const SizedBox(width: StopBellSpacing.sm),
            Expanded(
              child: Semantics(
                liveRegion: true,
                child: Text(message, style: theme.textTheme.bodySmall),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
