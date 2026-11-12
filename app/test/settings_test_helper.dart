import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 홈의 설정 톱니바퀴로 설정 화면을 연 뒤 [label] 항목을 누른다.
Future<void> tapInSettings(WidgetTester tester, String label) async {
  await openSettings(tester);
  await tester.tap(find.text(label));
}

Future<void> openSettings(WidgetTester tester) async {
  await tester.tap(find.byTooltip('설정'));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 500));
}

/// 설정 화면이 열려 있는지 확인할 때 사용한다.
Finder get settingsTitle => find.widgetWithText(AppBar, '설정');
