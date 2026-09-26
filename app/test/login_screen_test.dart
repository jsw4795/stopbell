import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';
import 'package:stopbell/main.dart';

const pair = TokenPair(
  accessToken: 'fixture-access',
  refreshToken: 'fixture-refresh',
);

class FakeAuthenticator implements Authenticator {
  int calls = 0;
  final Completer<TokenPair> result = Completer<TokenPair>();

  @override
  Future<TokenPair> login() {
    calls++;
    return result.future;
  }
}

class FakeStorage implements TokenPairStorage {
  FakeStorage({this.pair, this.readGate});
  TokenPair? pair;
  final Completer<void>? readGate;
  @override
  Future<TokenPair?> read() async {
    await readGate?.future;
    return pair;
  }

  @override
  Future<void> save(TokenPair pair) async => this.pair = pair;
  @override
  Future<void> delete() async => pair = null;
}

AuthSession session(FakeAuthenticator authenticator, FakeStorage storage) =>
    AuthSession(
      authenticator: authenticator,
      backend: BackendAuthClient(
        apiBaseUrl: Uri.parse('http://localhost:8080'),
      ),
      tokenStorage: storage,
    );

void main() {
  testWidgets('startup 복구 중 표시하고 저장된 Pair로 로그인 상태를 복구한다', (tester) async {
    final auth = FakeAuthenticator();
    final gate = Completer<void>();
    final appSession = session(auth, FakeStorage(pair: pair, readGate: gate));
    await tester.pumpWidget(StopBellApplication(authSession: appSession));
    expect(find.text('인증 상태 확인 중...'), findsOneWidget);
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('로그인 성공'), findsOneWidget);
    expect(auth.calls, 0);
  });

  testWidgets('진행 중 중복 로그인을 막고 Backend 실패 후 재시도할 수 있다', (tester) async {
    final auth = FakeAuthenticator();
    await tester.pumpWidget(
      StopBellApplication(authSession: session(auth, FakeStorage())),
    );
    await tester.pumpAndSettle();
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

  testWidgets('Google과 Backend 로그인 및 저장 뒤에만 성공을 표시한다', (tester) async {
    final auth = FakeAuthenticator();
    final storage = FakeStorage();
    await tester.pumpWidget(
      StopBellApplication(authSession: session(auth, storage)),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Google로 계속하기'));
    await tester.pump();
    expect(find.text('로그인 성공'), findsNothing);
    auth.result.complete(pair);
    await tester.pumpAndSettle();
    expect(storage.pair, same(pair));
    expect(find.text('로그인 성공'), findsOneWidget);
  });
}
