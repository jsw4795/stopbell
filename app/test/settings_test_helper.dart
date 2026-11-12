import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/main.dart';

import 'dart:async';

/// 홈의 설정 톱니바퀴로 설정 화면을 연 뒤 [label] 항목을 누른다.
Future<void> tapInSettings(WidgetTester tester, String label) async {
  if (label == '로그아웃' && find.byTooltip('설정').evaluate().isEmpty) {
    unawaited(
      tester
          .widget<StopBellApplication>(find.byType(StopBellApplication))
          .authSession
          .logout(),
    );
    return;
  }
  await openSettings(tester);
  final target = find.text(label);
  await tester.ensureVisible(target);
  await tester.tap(target);
  if (label == '로그아웃') {
    // 로그아웃은 확인 다이얼로그를 거친다.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(
      find.descendant(
        of: find.byType(AlertDialog),
        matching: find.text('로그아웃'),
      ),
    );
  }
}

Future<void> openSettings(WidgetTester tester) async {
  await tester.tap(find.byTooltip('설정'));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 500));
}

/// 설정 화면이 열려 있는지 확인할 때 사용한다.
Finder get settingsTitle => find.widgetWithText(AppBar, '설정');
