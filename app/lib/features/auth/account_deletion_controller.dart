import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../alarm/alarm_creation_intent.dart';
import '../boarding/boarding_creation_intent.dart';
import 'account_deletion_client.dart';
import 'auth_session.dart';

class DeletionRecord {
  const DeletionRecord({
    required this.receipt,
    required this.session,
    this.confirmed = false,
    this.manual = false,
    this.appleUncertain = false,
    this.retryAt,
  });
  final String receipt, session;
  final bool confirmed, manual, appleUncertain;
  final DateTime? retryAt;
  Map<String, Object?> toJson() => {
    'receipt': receipt,
    'session': session,
    'confirmed': confirmed,
    'manual': manual,
    'appleUncertain': appleUncertain,
    'retryAt': retryAt?.toUtc().toIso8601String(),
  };
  factory DeletionRecord.fromJson(Object? value) {
    if (value is! Map ||
        value['receipt'] is! String ||
        !RegExp(r'^[A-Za-z0-9_-]{43}$').hasMatch(value['receipt']) ||
        value['session'] is! String ||
        (value['session'] as String).isEmpty ||
        value['confirmed'] is! bool ||
        value['manual'] is! bool ||
        (value['appleUncertain'] != null && value['appleUncertain'] is! bool) ||
        (value['manual'] == true && value['confirmed'] != true)) {
      throw const FormatException();
    }
    final raw = value['retryAt'];
    final retryAt = raw is String ? DateTime.tryParse(raw) : null;
    if (raw != null && retryAt == null) throw const FormatException();
    return DeletionRecord(
      receipt: value['receipt'],
      session: value['session'],
      confirmed: value['confirmed'],
      manual: value['manual'],
      appleUncertain: value['appleUncertain'] == true,
      retryAt: retryAt,
    );
  }
}

abstract interface class DeletionStorage {
  Future<DeletionRecord?> read(String account, String session);
  Future<(String, DeletionRecord)?> readNotice();
  Future<DeletionRecord> saveIfAbsent(String account, DeletionRecord value);
  Future<void> save(String account, DeletionRecord value);
  Future<void> delete(String account, String receipt);
}

class SecureDeletionStorage implements DeletionStorage {
  const SecureDeletionStorage({
    required this.origin,
    this.storage = const FlutterSecureStorage(),
  });
  final String origin;
  final FlutterSecureStorage storage;
  static const _legacyKey = 'account_deletion_v1';
  String get _noticeKey =>
      'account_deletion_notice_v2_${base64UrlEncode(utf8.encode(origin))}';
  static Future<void> _tail = Future<void>.value();

  // Serialize read/modify/write sequences even across storage instances.
  Future<T> _locked<T>(Future<T> Function() work) {
    final result = _tail.then((_) => work());
    _tail = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }

  String _key(String account) =>
      'account_deletion_v2_${base64UrlEncode(utf8.encode(jsonEncode([origin, account])))}';

  Future<DeletionRecord?> _read(String key) async {
    final raw = await storage.read(key: key);
    return raw == null ? null : DeletionRecord.fromJson(jsonDecode(raw));
  }

  @override
  Future<DeletionRecord?> read(String account, String session) =>
      _locked(() async {
        final key = _key(account);
        final current = await _read(key);
        DeletionRecord? legacy;
        try {
          legacy = await _read(_legacyKey);
        } on FormatException {
          // Preserve an unreadable legacy value without assigning it to anyone.
          return current;
        }
        if (legacy == null || legacy.session != session) return current;
        if (current == null) {
          await storage.write(key: key, value: jsonEncode(legacy.toJson()));
          // The original remains intact if either write or delete fails.
          await storage.delete(key: _legacyKey);
          return legacy;
        }
        if (current.receipt == legacy.receipt) {
          await storage.delete(key: _legacyKey);
        }
        return current;
      });

  Future<List<String>> _notices() async {
    final raw = await storage.read(key: _noticeKey);
    if (raw == null) return [];
    final decoded = jsonDecode(raw);
    if (decoded is! List || decoded.any((item) => item is! String)) {
      throw const FormatException();
    }
    return decoded.cast<String>();
  }

  @override
  Future<(String, DeletionRecord)?> readNotice() => _locked(() async {
    for (final account in await _notices()) {
      final record = await _read(_key(account));
      if (record != null && record.confirmed) {
        return (account, record);
      }
    }
    return null;
  });

  @override
  Future<DeletionRecord> saveIfAbsent(String account, DeletionRecord value) =>
      _locked(() async {
        final key = _key(account);
        final existing = await _read(key);
        if (existing != null) return existing;
        await storage.write(key: key, value: jsonEncode(value.toJson()));
        return value;
      });

  @override
  Future<void> save(String account, DeletionRecord value) => _locked(() async {
    final key = _key(account);
    final existing = await _read(key);
    if (existing?.receipt != value.receipt) throw StateError('receipt changed');
    await storage.write(key: key, value: jsonEncode(value.toJson()));
    if (value.confirmed) {
      final notices = await _notices();
      if (!notices.contains(account)) {
        notices.add(account);
        await storage.write(key: _noticeKey, value: jsonEncode(notices));
      }
    }
  });

  @override
  Future<void> delete(String account, String receipt) => _locked(() async {
    final key = _key(account);
    final existing = await _read(key);
    if (existing == null) return;
    if (existing.receipt != receipt) throw StateError('receipt changed');
    await storage.delete(key: key);
    final notices = await _notices();
    if (notices.remove(account)) {
      if (notices.isEmpty) {
        await storage.delete(key: _noticeKey);
      } else {
        await storage.write(key: _noticeKey, value: jsonEncode(notices));
      }
    }
  });
}

class AccountDeletionController extends ChangeNotifier {
  AccountDeletionController({
    required this.auth,
    required this.client,
    required this.storage,
    required this.alarms,
    required this.boarding,
  }) {
    auth.addListener(_authChanged);
  }
  final AuthSession auth;
  final AccountDeletionClient client;
  final DeletionStorage storage;
  final AlarmIntentStorage alarms;
  final BoardingIntentStorage boarding;
  DeletionRecord? _record;
  String? _account;
  String? _session;
  bool busy = false;
  bool loading = false;
  bool _ready = false;
  String? message;
  AccountDeletionStatus? status;
  Timer? _retryTimer;
  bool _cleanupComplete = false;
  bool _disposed = false;
  bool _completing = false;
  bool _notice = false;
  int _epoch = 0;
  Future<void>? _loading;

  void _emit() {
    if (!_disposed) notifyListeners();
  }

  bool _current(int epoch) => !_disposed && epoch == _epoch;

  void _authChanged() {
    if (_disposed || _completing || auth.state == AuthState.initializing) {
      return;
    }
    final account = auth.state == AuthState.authenticated
        ? auth.userIdentity
        : null;
    final session = auth.state == AuthState.authenticated
        ? auth.sessionIdentity
        : null;
    if (account == _account && session == _session) return;
    // A locally confirmed manual notice survives the transition to logged out.
    if (_notice && auth.state == AuthState.unauthenticated) return;
    _epoch++;
    _account = account;
    _session = session;
    _record = null;
    _notice = false;
    _cleanupComplete = false;
    _ready = false;
    busy = false;
    message = null;
    status = null;
    _retryTimer?.cancel();
    _loading = null;
    _emit();
    if (auth.state == AuthState.authenticated) {
      initialize().catchError((Object _) {});
    }
  }

  void _scheduleRetry() {
    _retryTimer?.cancel();
    final deadline = _record?.retryAt;
    if (deadline == null) return;
    final delay = deadline.difference(DateTime.now());
    if (delay > Duration.zero) _retryTimer = Timer(delay, _emit);
  }

  @override
  void dispose() {
    _disposed = true;
    _epoch++;
    auth.removeListener(_authChanged);
    _retryTimer?.cancel();
    super.dispose();
  }

  bool get active =>
      _record != null &&
      ((_account != null &&
              auth.state == AuthState.authenticated &&
              auth.userIdentity == _account &&
              auth.sessionIdentity == _session) ||
          (_notice && auth.state == AuthState.unauthenticated));
  bool get manualNotice =>
      active &&
      _cleanupComplete &&
      _record!.confirmed &&
      _record!.manual &&
      auth.state == AuthState.unauthenticated;
  bool get confirmed => active && _record!.confirmed;
  bool get appleUncertain => active && _record!.appleUncertain;
  bool get canRetry =>
      active &&
      !busy &&
      !_record!.confirmed &&
      (_record!.retryAt == null || !DateTime.now().isBefore(_record!.retryAt!));

  Future<void> initialize() {
    if (_loading != null) return _loading!;
    if (_ready) return Future<void>.value();
    final account = auth.state == AuthState.authenticated
        ? auth.userIdentity
        : null;
    final session = auth.state == AuthState.authenticated
        ? auth.sessionIdentity
        : null;
    _account = account;
    _session = session;
    final epoch = ++_epoch;
    loading = true;
    _emit();
    final future = _load(epoch, account, session);
    _loading = future;
    return future;
  }

  Future<void> _load(int epoch, String? account, String? session) async {
    try {
      if (auth.state == AuthState.authenticated) {
        if (account == null || session == null) {
          throw StateError('account unavailable');
        }
        final record = await storage.read(account, session);
        if (!_current(epoch)) return;
        _record = record;
        if (record != null) {
          await auth.beginAccountDeletion();
          if (!_current(epoch)) return;
          _scheduleRetry();
          if (record.confirmed) {
            await storage.save(account, record);
            if (!_current(epoch)) return;
            await _finish(epoch);
          } else {
            status = AccountDeletionStatus.uncertain;
            message = '탈퇴 처리 상태를 확인해 주세요. 아직 완료 여부를 알 수 없어요.';
          }
        }
      } else {
        final notice = await storage.readNotice();
        if (!_current(epoch)) return;
        if (notice != null) {
          _account = notice.$1;
          _record = notice.$2;
          _notice = true;
          await _finish(epoch);
        }
      }
      if (_current(epoch)) _ready = true;
    } catch (_) {
      if (_current(epoch)) {
        message = '저장된 정보를 안전하게 준비하지 못했어요. 앱 저장소를 확인해 주세요.';
      }
      rethrow;
    } finally {
      if (_current(epoch)) {
        loading = false;
        _loading = null;
        _emit();
      }
    }
  }

  Future<void> start() async {
    if (busy || auth.state != AuthState.authenticated) return;
    if (!_ready) {
      try {
        await initialize();
      } catch (_) {
        return;
      }
    }
    if (busy ||
        !_ready ||
        auth.userIdentity == null ||
        auth.sessionIdentity == null) {
      return;
    }
    if (_record != null) return;
    final epoch = _epoch;
    final account = _account!;
    final session = _session!;
    busy = true;
    message = null;
    _emit();
    try {
      await auth.beginAccountDeletion();
      if (!_current(epoch)) return;
      final random = Random.secure();
      final receipt = base64UrlEncode(
        List<int>.generate(32, (_) => random.nextInt(256)),
      ).replaceAll('=', '');
      final proposed = DeletionRecord(receipt: receipt, session: session);
      late final DeletionRecord record;
      try {
        record = await storage.saveIfAbsent(account, proposed);
      } catch (_) {
        if (_current(epoch)) auth.cancelAccountDeletionPreparation();
        rethrow;
      }
      if (!_current(epoch)) return;
      _record = record;
      if (record.receipt != receipt) {
        status = AccountDeletionStatus.uncertain;
        message = '탈퇴 처리 상태를 확인해 주세요. 아직 완료 여부를 알 수 없어요.';
        _scheduleRetry();
        return;
      }
      await _send(epoch, record);
    } catch (_) {
      if (_current(epoch)) message = '탈퇴 요청을 준비하거나 저장하지 못했어요. 저장 공간을 확인해 주세요.';
    } finally {
      if (_current(epoch)) {
        busy = false;
        _emit();
      }
    }
  }

  Future<void> check() async {
    if (!active || busy || _record!.confirmed) return;
    final epoch = _epoch;
    final record = _record!;
    busy = true;
    _emit();
    try {
      final result = await client.status(record.receipt);
      if (_current(epoch)) await _handle(epoch, record, result);
    } catch (_) {
      if (_current(epoch)) message = '상태를 확인하지 못했어요. 나중에 다시 확인해 주세요.';
    } finally {
      if (_current(epoch)) {
        busy = false;
        _emit();
      }
    }
  }

  Future<void> retry() async {
    if (!canRetry) return;
    final epoch = _epoch;
    final record = _record!;
    busy = true;
    _emit();
    try {
      await _send(epoch, record);
    } catch (_) {
      if (_current(epoch)) message = '요청 결과를 확인하지 못했어요. 상태를 다시 확인해 주세요.';
    } finally {
      if (_current(epoch)) {
        busy = false;
        _emit();
      }
    }
  }

  Future<void> _send(int epoch, DeletionRecord record) async {
    final token = auth.deletionAccessToken;
    if (token == null) {
      if (_current(epoch)) {
        message = '인증이 만료되었을 수 있어요. 탈퇴 처리 상태를 확인해 주세요.';
      }
      return;
    }
    final result = await client.delete(token, record.receipt);
    if (!_current(epoch)) return;
    if (result.status == AccountDeletionStatus.deleted) {
      final durable = await client.status(record.receipt);
      if (!_current(epoch)) return;
      if (durable.status ==
          AccountDeletionStatus.manualAppleRevocationRequired) {
        await _handle(epoch, record, durable);
        return;
      }
      if (durable.status != AccountDeletionStatus.deleted) {
        await _handle(
          epoch,
          record,
          const AccountDeletionResult(
            AccountDeletionStatus.manualAppleRevocationRequired,
          ),
          appleUncertain: true,
        );
        return;
      }
    }
    await _handle(epoch, record, result);
  }

  Future<void> _handle(
    int epoch,
    DeletionRecord old,
    AccountDeletionResult result, {
    bool appleUncertain = false,
  }) async {
    if (!_current(epoch)) return;
    status = result.status;
    if (result.status == AccountDeletionStatus.deleted ||
        result.status == AccountDeletionStatus.manualAppleRevocationRequired) {
      final updated = DeletionRecord(
        receipt: old.receipt,
        session: old.session,
        confirmed: true,
        manual:
            result.status ==
            AccountDeletionStatus.manualAppleRevocationRequired,
        appleUncertain: appleUncertain,
      );
      await storage.save(_account!, updated);
      if (!_current(epoch)) return;
      _record = updated;
      await _finish(epoch);
      return;
    }
    if (result.retryAfter != null) {
      final updated = DeletionRecord(
        receipt: old.receipt,
        session: old.session,
        retryAt: DateTime.now().add(result.retryAfter!),
      );
      await storage.save(_account!, updated);
      if (!_current(epoch)) return;
      _record = updated;
      _scheduleRetry();
    }
    message = switch (result.status) {
      AccountDeletionStatus.pending => '탈퇴가 진행 중이에요. 완료까지 시간이 걸릴 수 있어요.',
      AccountDeletionStatus.rejected ||
      AccountDeletionStatus.configurationError =>
        '서버에서 탈퇴를 완료하지 못했어요. 나중에 상태를 확인하거나 다시 시도해 주세요.',
      AccountDeletionStatus.unauthenticated =>
        '인증이 만료되었거나 계정이 삭제되었을 수 있어요. 상태를 확인해 주세요.',
      _ => '요청 결과를 확인하지 못했어요. 탈퇴 완료 여부를 확인해 주세요.',
    };
  }

  Future<void> _finish(int epoch) async {
    final record = _record!;
    final account = _account!;
    try {
      for (final session in {record.session, ?_session}) {
        final intent = await alarms.read(session);
        if (!_current(epoch)) return;
        if (intent != null &&
            !await alarms.deleteIfMatching(session, intent.clientRequestId)) {
          throw StateError('alarm cleanup');
        }
        if (!_current(epoch)) return;
        await boarding.delete(session);
        if (!_current(epoch)) return;
      }
      if (auth.state == AuthState.authenticated &&
          auth.userIdentity == account &&
          auth.sessionIdentity == _session) {
        _completing = true;
        try {
          await auth.completeAccountDeletion();
        } finally {
          _completing = false;
        }
        if (!_current(epoch)) return;
        _session = null;
      }
      _cleanupComplete = true;
      _notice = true;
      if (!record.manual) {
        await storage.delete(account, record.receipt);
        if (!_current(epoch)) return;
        _record = null;
        _notice = false;
      }
      message = null;
    } catch (_) {
      if (_current(epoch)) {
        message = '삭제는 확인되었지만 이 기기의 사용자 정보를 정리하지 못했어요. 다시 시도해 주세요.';
      }
    }
  }

  Future<void> finishCleanup() async {
    if (!active || busy || !_record!.confirmed) return;
    final epoch = _epoch;
    busy = true;
    _emit();
    await _finish(epoch);
    if (_current(epoch)) {
      busy = false;
      _emit();
    }
  }

  Future<void> acknowledgeManual() async {
    if (!manualNotice || busy) return;
    final epoch = _epoch;
    final account = _account!;
    final receipt = _record!.receipt;
    busy = true;
    try {
      await storage.delete(account, receipt);
      if (!_current(epoch)) return;
      _record = null;
      _notice = false;
      message = null;
      final next = await storage.readNotice();
      if (!_current(epoch)) return;
      if (next != null) {
        _account = next.$1;
        _record = next.$2;
        _notice = true;
        _cleanupComplete = false;
        await _finish(epoch);
      }
    } catch (_) {
      if (_current(epoch)) message = '안내 상태를 저장하지 못했어요. 다시 시도해 주세요.';
    } finally {
      if (_current(epoch)) {
        busy = false;
        _emit();
      }
    }
  }
}
