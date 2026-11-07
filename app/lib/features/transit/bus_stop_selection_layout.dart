import 'package:flutter/material.dart';

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
  });

  final BusRoute route;
  final String title;
  final VoidCallback? onBack;
  final Widget body;
  final Widget? summary;
  final String backLabel;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 6, 16, 8),
              child: Row(
                children: [
                  IconButton(
                    tooltip: backLabel,
                    onPressed: onBack,
                    icon: const Icon(Icons.arrow_back),
                  ),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          route.routeNumber,
                          style: theme.textTheme.headlineSmall?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        Wrap(
                          spacing: 8,
                          children: [
                            Text(
                              route.regionName,
                              style: theme.textTheme.bodySmall,
                            ),
                            Text(
                              title,
                              style: theme.textTheme.bodySmall?.copyWith(
                                color: colors.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  Icon(Icons.directions_bus_outlined, color: colors.primary),
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
                    maxHeight: constraints.maxHeight * 0.50,
                  ),
                  child: SingleChildScrollView(
                    key: const ValueKey('stop-selection-summary'),
                    primary: false,
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
                    child: summary,
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
