import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/notification/push_permission_service.dart';
import 'package:stopbell/features/settings/settings_screen.dart';

import 'notification_test_fakes.dart';

void main() {
  Future<void> pump(
    WidgetTester tester, {
    PushProvider? provider,
    VoidCallback? onLogout,
    Future<void> Function()? onDelete,
    ThemeData? theme,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: theme ?? StopBellTheme.light,
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) => SettingsScreen(
                  onLogout: onLogout ?? () {},
                  onDeleteAccount: onDelete,
                  pushProvider: provider,
                ),
              ),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('알림 권한 상태와 시스템 설정 이동을 제공한다', (tester) async {
    final provider = FakePushProvider()..status = PushPermission.denied;
    await pump(tester, provider: provider);
    expect(find.text('알림 꺼짐 · 하차 준비 알림을 받을 수 없어요'), findsOneWidget);
    await tester.tap(find.text('시스템 알림 설정 열기'));
    await tester.pump();
    expect(provider.settings, 1);
    expect(provider.requests, 0);
  });

  testWidgets('정책 링크는 URL 확정 전까지 비활성 자리만 둔다', (tester) async {
    await pump(tester, theme: StopBellTheme.dark);
    expect(find.text('이 기기에서는 알림 설정을 지원하지 않아요'), findsOneWidget);
    for (final title in ['개인정보처리방침', '서비스 이용약관']) {
      final tile = tester.widget<ListTile>(
        find.widgetWithText(ListTile, title),
      );
      expect(tile.enabled, isFalse);
    }
    expect(find.text('준비 중'), findsNWidgets(2));
    expect(find.text('회원 탈퇴'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('회원 탈퇴는 로그아웃 아래 빨간 버튼으로 떨어져 있다', (tester) async {
    await pump(tester, onDelete: () async {});
    final button = find.byKey(const ValueKey('delete-account'));
    final logoutBottom = tester.getBottomLeft(find.text('로그아웃')).dy;
    expect(tester.getTopLeft(button).dy - logoutBottom, greaterThan(24));
    expect(tester.getSize(button).height, greaterThanOrEqualTo(44));
    final style = tester.widget<TextButton>(button).style!;
    expect(
      style.foregroundColor!.resolve({}),
      StopBellTheme.light.colorScheme.error,
    );
  });

  testWidgets('로그아웃은 설정을 닫고 기존 흐름을 호출한다', (tester) async {
    var logout = 0;
    var deletion = 0;
    await pump(
      tester,
      onLogout: () => logout++,
      onDelete: () async => deletion++,
    );
    await tester.ensureVisible(find.text('회원 탈퇴'));
    await tester.tap(find.text('회원 탈퇴'));
    await tester.pump();
    expect(deletion, 1);
    await tester.tap(find.text('로그아웃'));
    await tester.pumpAndSettle();
    expect(find.text('로그아웃할까요?'), findsOneWidget);
    expect(logout, 0);
    await tester.tap(find.widgetWithText(TextButton, '취소'));
    await tester.pumpAndSettle();
    expect(logout, 0);
    await tester.tap(find.text('로그아웃'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '로그아웃'));
    await tester.pumpAndSettle();
    expect(logout, 1);
    expect(find.text('open'), findsOneWidget);
  });
}
