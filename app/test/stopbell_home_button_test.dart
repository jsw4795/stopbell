import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/shared/stopbell_home_button.dart';

void main() {
  testWidgets('홈 버튼은 첫 경로까지 모든 하위 route를 정리한다', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: Column(
              children: [
                const StopBellHomeButton(),
                TextButton(
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => const Scaffold(
                        body: Column(
                          children: [Text('다음 화면'), StopBellHomeButton()],
                        ),
                      ),
                    ),
                  ),
                  child: const Text('열기'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    final button = find.byType(StopBellHomeButton);
    expect(tester.getSize(button).width, greaterThanOrEqualTo(44));
    expect(tester.getSize(button).height, greaterThanOrEqualTo(44));
    expect(find.byTooltip('홈으로'), findsOneWidget);
    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(find.text('열기'), findsOneWidget);
    await tester.tap(find.text('열기'));
    await tester.pumpAndSettle();
    expect(find.text('다음 화면'), findsOneWidget);
    await tester.tap(find.byTooltip('홈으로').last);
    await tester.pumpAndSettle();
    expect(find.text('다음 화면'), findsNothing);
  });
}
