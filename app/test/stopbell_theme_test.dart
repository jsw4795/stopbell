import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_colors.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/transit/bus_route_search_screen.dart';

double contrast(Color first, Color second) {
  final a = first.computeLuminance();
  final b = second.computeLuminance();
  return a > b ? (a + .05) / (b + .05) : (b + .05) / (a + .05);
}

void main() {
  for (final brightness in Brightness.values) {
    final isDark = brightness == Brightness.dark;
    final theme = isDark ? StopBellTheme.dark : StopBellTheme.light;

    test('theme, controls and contrast in ${brightness.name}', () {
      final colors = theme.colorScheme;
      final semantic = theme.extension<StopBellColors>()!;
      expect(theme.brightness, brightness);
      expect(
        theme.scaffoldBackgroundColor,
        isDark ? const Color(0xff101722) : const Color(0xfff5f7fb),
      );
      expect(semantic.boardingStop, semantic.success);
      expect(semantic.targetStop, colors.primary);
      expect(semantic.heldTracking, semantic.warning);
      expect(semantic.busLocation, isNot(semantic.targetStop));
      expect(theme.textTheme.headlineLarge!.fontSize, 28);
      expect(theme.textTheme.titleMedium!.fontSize, 17);

      final primary = theme.filledButtonTheme.style!;
      final secondary = theme.outlinedButtonTheme.style!;
      final text = theme.textButtonTheme.style!;
      expect(primary.minimumSize!.resolve({})!.height, 50);
      expect(secondary.minimumSize!.resolve({})!.height, 46);
      expect(text.minimumSize!.resolve({})!.height, 44);
      expect(primary.backgroundColor!.resolve({}), colors.primary);
      expect(secondary.foregroundColor!.resolve({}), colors.secondary);
      expect(text.foregroundColor!.resolve({}), colors.primary);
      final disabled = {WidgetState.disabled};
      expect(
        primary.backgroundColor!.resolve(disabled),
        isNot(primary.backgroundColor!.resolve({})),
      );
      expect(
        contrast(
          primary.foregroundColor!.resolve(disabled)!,
          primary.backgroundColor!.resolve(disabled)!,
        ),
        greaterThanOrEqualTo(4.5),
      );

      final input = theme.inputDecorationTheme;
      expect(input.constraints!.minHeight, 48);
      expect(input.fillColor, colors.surface);
      expect(input.focusedBorder!.borderSide.color, colors.primary);
      expect(input.errorBorder!.borderSide.color, colors.error);
      expect(input.disabledBorder, isNot(input.enabledBorder));

      for (final (foreground, background) in [
        (colors.onPrimary, colors.primary),
        (colors.onError, colors.error),
        (colors.onSurface, colors.surface),
        (colors.onSurfaceVariant, theme.scaffoldBackgroundColor),
        (colors.outline, theme.scaffoldBackgroundColor),
        (semantic.nonstopStop, colors.surface),
        (semantic.activeTracking, colors.surface),
        (semantic.heldTracking, colors.surface),
      ]) {
        expect(contrast(foreground, background), greaterThanOrEqualTo(4.5));
      }
      for (final accent in [
        semantic.boardingStop,
        semantic.targetStop,
        semantic.busLocation,
        Color.alphaBlend(colors.primary.withValues(alpha: .75), colors.surface),
      ]) {
        expect(contrast(accent, colors.surface), greaterThanOrEqualTo(3));
      }
    });

    testWidgets(
      'search with a keyboard and enlarged text in ${brightness.name}',
      (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = const Size(320, 568);
        tester.view.viewPadding = const FakeViewPadding(top: 24, bottom: 20);
        tester.view.padding = const FakeViewPadding(top: 24, bottom: 20);
        addTearDown(tester.view.reset);
        String? searched;
        await tester.pumpWidget(
          MaterialApp(
            theme: theme,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context)
                  .copyWith(textScaler: const TextScaler.linear(2)),
              child: child!,
            ),
            home: Scaffold(
              body: SafeArea(
                child: BusRouteSearchScreen(
                  search: (query) async {
                    searched = query;
                    return [];
                  },
                  onSelect: (_) {},
                ),
              ),
            ),
          ),
        );
        final field = find.byType(TextField);
        await tester.enterText(field, '143');
        tester.view.padding = const FakeViewPadding(top: 24);
        tester.view.viewInsets = const FakeViewPadding(bottom: 280);
        await tester.pumpAndSettle();
        expect(tester.getTopLeft(field).dy, greaterThanOrEqualTo(24));
        expect(tester.getBottomRight(field).dy, lessThan(288));
        await tester.testTextInput.receiveAction(TextInputAction.search);
        await tester.pumpAndSettle();
        expect(searched, '143');
        expect(find.text('검색 결과가 없어요.'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
