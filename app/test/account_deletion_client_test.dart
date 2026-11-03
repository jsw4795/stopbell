import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/features/auth/account_deletion_client.dart';

void main() {
  final baseUrl = Uri.parse('https://example.test');
  for (final (state, expected) in [
    ('PENDING', AccountDeletionStatus.pending),
    ('DELETED', AccountDeletionStatus.deleted),
    ('MANUAL_REQUIRED', AccountDeletionStatus.manualAppleRevocationRequired),
    ('UNKNOWN', AccountDeletionStatus.uncertain),
  ]) {
    test('status $state is queried without an access token', () async {
      final transport = MockClient((request) async {
        expect(request.method, 'POST');
        expect(request.url.path, '/api/v1/account-deletion/status');
        expect(request.headers['x-account-deletion-receipt'], 'receipt');
        expect(request.headers.containsKey('authorization'), isFalse);
        return http.Response('{"status":"$state"}', 200);
      });
      addTearDown(transport.close);
      final result = await AccountDeletionClient(
        apiBaseUrl: baseUrl,
        client: transport,
      ).status('receipt');
      expect(result.status, expected);
    });
  }
  final cases = <(int, String, AccountDeletionStatus)>[
    (204, '', AccountDeletionStatus.deleted),
    (
      200,
      '{"status":"DELETED","appleRevocation":"MANUAL_REVOCATION_REQUIRED"}',
      AccountDeletionStatus.manualAppleRevocationRequired,
    ),
    (200, '{}', AccountDeletionStatus.uncertain),
    (
      202,
      '{"status":"PENDING","appleRevocation":"UNCONFIRMED"}',
      AccountDeletionStatus.pending,
    ),
    (202, '{}', AccountDeletionStatus.uncertain),
    (401, '', AccountDeletionStatus.unauthenticated),
    (403, '', AccountDeletionStatus.uncertain),
    (409, '', AccountDeletionStatus.uncertain),
    (502, '', AccountDeletionStatus.rejected),
    (503, '', AccountDeletionStatus.configurationError),
    (500, '', AccountDeletionStatus.uncertain),
  ];
  for (final (code, body, expected) in cases) {
    test('maps HTTP $code and sends a single authenticated DELETE', () async {
      var requests = 0;
      final transport = MockClient((request) async {
        requests++;
        expect(request.method, 'DELETE');
        expect(request.url, baseUrl.resolve('/api/v1/users/me'));
        expect(request.headers['authorization'], 'Bearer token');
        expect(request.headers['x-account-deletion-receipt'], 'receipt');
        expect(request.body, isEmpty);
        return http.Response(body, code, headers: {'retry-after': '60'});
      });
      addTearDown(transport.close);
      final result = await AccountDeletionClient(
        apiBaseUrl: baseUrl,
        client: transport,
      ).delete('token', 'receipt');
      expect(result.status, expected);
      if (expected == AccountDeletionStatus.pending) {
        expect(result.retryAfter, const Duration(seconds: 60));
      }
      expect(requests, 1);
    });
  }
  test('timeout leaves the outcome uncertain', () async {
    final transport = MockClient((_) => Completer<http.Response>().future);
    addTearDown(transport.close);
    final result = await AccountDeletionClient(
      apiBaseUrl: baseUrl,
      client: transport,
      timeout: Duration.zero,
    ).delete('token', 'receipt');
    expect(result.status, AccountDeletionStatus.uncertain);
  });
}
