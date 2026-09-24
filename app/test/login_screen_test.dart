import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/main.dart';

class FakeAuthenticator implements Authenticator {
  int calls = 0;
  final Completer<void> result = Completer<void>();

  @override
  Future<void> login() {
    calls++;
    return result.future;
  }
}

void main() {
  testWidgets('진행 중 중복 로그인을 막고 Backend 실패 후 재시도할 수 있다', (tester) async {
    final auth = FakeAuthenticator();
    await tester.pumpWidget(StopBellApplication(authenticator: auth));

    await tester.tap(find.text('Google로 계속하기'));
    await tester.pump();
    expect(auth.calls, 1);
    expect(find.text('로그인 중...'), findsOneWidget);
    expect(
      tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
      isNull,
    );

    auth.result.completeError(const BackendAuthException('서버 로그인이 실패했습니다.'));
    await tester.pump();
    expect(find.text('로그인 성공'), findsNothing);
    expect(find.text('서버 로그인이 실패했습니다.'), findsOneWidget);
    expect(
      tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
      isNotNull,
    );
  });

  testWidgets('Google과 Backend 로그인이 모두 끝난 뒤에만 성공을 표시한다', (tester) async {
    final auth = FakeAuthenticator();
    await tester.pumpWidget(StopBellApplication(authenticator: auth));

    await tester.tap(find.text('Google로 계속하기'));
    await tester.pump();
    expect(find.text('로그인 성공'), findsNothing);

    auth.result.complete();
    await tester.pump();
    expect(find.text('로그인 성공'), findsOneWidget);
  });
}
