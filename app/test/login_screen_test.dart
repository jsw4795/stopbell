import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';
import 'package:stopbell/features/transit/bus_route.dart';
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

class FakeBackend extends BackendAuthClient {
  FakeBackend() : super(apiBaseUrl: Uri.parse('http://localhost:8080'));
  final Completer<void> logoutGate = Completer<void>();
  int logoutCalls = 0;
  @override
  Future<void> logout(String refreshToken) {
    logoutCalls++;
    expect(refreshToken, 'fixture-refresh');
    return logoutGate.future;
  }
}

AuthSession session(
  FakeAuthenticator authenticator,
  FakeStorage storage, {
  BackendAuthClient? backend,
}) => AuthSession(
  authenticator: authenticator,
  backend:
      backend ??
      BackendAuthClient(apiBaseUrl: Uri.parse('http://localhost:8080')),
  tokenStorage: storage,
);

StopBellApplication app(
  AuthSession authSession, {
  Future<List<BusRoute>> Function(String)? searchRoutes,
}) => StopBellApplication(
  authSession: authSession,
  searchRoutes: searchRoutes ?? (String _) async => <BusRoute>[],
);

void main() {
  testWidgets('startup 복구 중 표시하고 저장된 Pair로 로그인 상태를 복구한다', (tester) async {
    final auth = FakeAuthenticator();
    final gate = Completer<void>();
    final appSession = session(auth, FakeStorage(pair: pair, readGate: gate));
    await tester.pumpWidget(app(appSession));
    expect(find.text('인증 상태 확인 중...'), findsOneWidget);
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('노선번호를 입력해 검색하세요.'), findsOneWidget);
    expect(auth.calls, 0);
  });

  testWidgets('진행 중 중복 로그인을 막고 Backend 실패 후 재시도할 수 있다', (tester) async {
    final auth = FakeAuthenticator();
    await tester.pumpWidget(app(session(auth, FakeStorage())));
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
    expect(find.text('노선번호를 입력해 검색하세요.'), findsNothing);
    expect(find.text('서버 로그인이 실패했습니다.'), findsOneWidget);
    expect(
      tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
      isNotNull,
    );
  });

  testWidgets('Google과 Backend 로그인 및 저장 뒤에만 성공을 표시한다', (tester) async {
    final auth = FakeAuthenticator();
    final storage = FakeStorage();
    await tester.pumpWidget(app(session(auth, storage)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Google로 계속하기'));
    await tester.pump();
    expect(find.text('노선번호를 입력해 검색하세요.'), findsNothing);
    auth.result.complete(pair);
    await tester.pumpAndSettle();
    expect(storage.pair, same(pair));
    expect(find.text('노선번호를 입력해 검색하세요.'), findsOneWidget);
  });

  testWidgets('Logout 버튼은 중복 입력을 막고 완료 뒤 Google login을 표시한다', (tester) async {
    final backend = FakeBackend();
    final storage = FakeStorage(pair: pair);
    await tester.pumpWidget(
      app(session(FakeAuthenticator(), storage, backend: backend)),
    );
    await tester.pumpAndSettle();
    expect(find.text('로그아웃'), findsOneWidget);
    await tester.tap(find.text('로그아웃'));
    await tester.pump();
    expect(find.text('로그아웃 중...'), findsOneWidget);
    expect(
      tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
      isNull,
    );
    expect(backend.logoutCalls, 1);
    backend.logoutGate.complete();
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);
    expect(storage.pair, isNull);
  });

  testWidgets('검색 중 AuthSession 로그아웃 뒤에는 Login 화면을 유지한다', (tester) async {
    final backend = FakeBackend();
    final searchResult = Completer<List<BusRoute>>();
    await tester.pumpWidget(
      app(
        session(FakeAuthenticator(), FakeStorage(pair: pair), backend: backend),
        searchRoutes: (_) => searchResult.future,
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '7000');
    await tester.tap(find.text('검색'));
    await tester.pump();
    expect(find.text('노선 검색 중...'), findsOneWidget);

    await tester.tap(find.text('로그아웃'));
    await tester.pump();
    backend.logoutGate.complete();
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);

    searchResult.complete(const [
      BusRoute(id: 1, routeNumber: '7000', regionName: '서울'),
    ]);
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);
    expect(find.text('서울'), findsNothing);
  });
}
