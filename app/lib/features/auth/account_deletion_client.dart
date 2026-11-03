import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

enum AccountDeletionStatus {
  deleted,
  manualAppleRevocationRequired,
  pending,
  rejected,
  configurationError,
  unauthenticated,
  uncertain,
}

class AccountDeletionResult {
  const AccountDeletionResult(this.status, {this.retryAfter});

  final AccountDeletionStatus status;
  final Duration? retryAfter;
}

/// Sends exactly one DELETE with the current access token; it never refreshes.
class AccountDeletionClient {
  AccountDeletionClient({
    required this.apiBaseUrl,
    http.Client? client,
    this.timeout = const Duration(seconds: 15),
  }) : _client = client ?? http.Client();

  final Uri apiBaseUrl;
  final http.Client _client;
  final Duration timeout;

  Future<AccountDeletionResult> status(String receipt) async {
    try {
      final response = await _client
          .post(
            apiBaseUrl.resolve('/api/v1/account-deletion/status'),
            headers: {'X-Account-Deletion-Receipt': receipt},
          )
          .timeout(timeout);
      if (response.statusCode != 200) {
        return const AccountDeletionResult(AccountDeletionStatus.uncertain);
      }
      final body = jsonDecode(response.body);
      final state = body is Map ? body['status'] : null;
      return AccountDeletionResult(switch (state) {
        'DELETED' => AccountDeletionStatus.deleted,
        'MANUAL_REQUIRED' =>
          AccountDeletionStatus.manualAppleRevocationRequired,
        'PENDING' => AccountDeletionStatus.pending,
        _ => AccountDeletionStatus.uncertain,
      });
    } on FormatException {
      return const AccountDeletionResult(AccountDeletionStatus.uncertain);
    } on TimeoutException {
      return const AccountDeletionResult(AccountDeletionStatus.uncertain);
    } on http.ClientException {
      return const AccountDeletionResult(AccountDeletionStatus.uncertain);
    }
  }

  Future<AccountDeletionResult> delete(
    String accessToken,
    String receipt,
  ) async {
    try {
      final response = await _client
          .delete(
            apiBaseUrl.resolve('/api/v1/users/me'),
            headers: {
              'Authorization': 'Bearer $accessToken',
              'X-Account-Deletion-Receipt': receipt,
            },
          )
          .timeout(timeout);
      switch (response.statusCode) {
        case 204:
          return const AccountDeletionResult(AccountDeletionStatus.deleted);
        case 200:
          try {
            final body = jsonDecode(response.body);
            if (body is Map &&
                body['status'] == 'DELETED' &&
                body['appleRevocation'] == 'MANUAL_REVOCATION_REQUIRED') {
              return const AccountDeletionResult(
                AccountDeletionStatus.manualAppleRevocationRequired,
              );
            }
          } on FormatException {
            // An unrecognized response cannot prove completion.
          }
          return const AccountDeletionResult(AccountDeletionStatus.uncertain);
        case 202:
          try {
            final body = jsonDecode(response.body);
            if (body is! Map ||
                body['status'] != 'PENDING' ||
                body['appleRevocation'] != 'UNCONFIRMED') {
              return const AccountDeletionResult(
                AccountDeletionStatus.uncertain,
              );
            }
          } on FormatException {
            return const AccountDeletionResult(AccountDeletionStatus.uncertain);
          }
          final seconds = int.tryParse(response.headers['retry-after'] ?? '');
          return AccountDeletionResult(
            AccountDeletionStatus.pending,
            retryAfter: seconds != null && seconds >= 0
                ? Duration(seconds: seconds)
                : null,
          );
        case 401:
          return const AccountDeletionResult(
            AccountDeletionStatus.unauthenticated,
          );
        case 502:
          return const AccountDeletionResult(AccountDeletionStatus.rejected);
        case 503:
          return const AccountDeletionResult(
            AccountDeletionStatus.configurationError,
          );
        default:
          return const AccountDeletionResult(AccountDeletionStatus.uncertain);
      }
    } on TimeoutException {
      return const AccountDeletionResult(AccountDeletionStatus.uncertain);
    } on http.ClientException {
      return const AccountDeletionResult(AccountDeletionStatus.uncertain);
    }
  }
}
