import 'dart:async';

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import '../auth/auth_session.dart';

sealed class NotificationHint {
  const NotificationHint(this.resourceId, this.eventType, this.eventId);
  final int resourceId;
  final String eventType, eventId;
  String get source;
  String get logicalKey => '$source:$resourceId:$eventType:$eventId';

  static NotificationHint? parse(Map<String, dynamic> data) =>
      switch (data['type']) {
        'ALARM_EVENT' => AlarmNotification.parse(data),
        'BOARDING_SESSION_EVENT' => BoardingSessionNotification.parse(data),
        _ => null,
      };
}

int? _positiveId(Object? value) {
  if (value is! String || !RegExp(r'^[1-9][0-9]*$').hasMatch(value)) {
    return null;
  }
  final id = int.tryParse(value);
  return id != null && id > 0 ? id : null;
}

final class AlarmNotification extends NotificationHint {
  const AlarmNotification(super.resourceId, super.eventType, super.eventId);
  int get alarmId => resourceId;
  @override
  String get source => 'ALARM_EVENT';

  static AlarmNotification? parse(Map<String, dynamic> data) {
    if (data['type'] != 'ALARM_EVENT' ||
        data.containsKey('boardingSessionId') ||
        !{
          'ONE_STOP_BEFORE',
          'ARRIVED',
          'PASSED',
          'ONE_STOP_AFTER',
        }.contains(data['eventType'])) {
      return null;
    }
    final id = _positiveId(data['alarmId']);
    if (id == null || _positiveId(data['notificationEventId']) == null) {
      return null;
    }
    return AlarmNotification(
      id,
      data['eventType'] as String,
      data['notificationEventId'] as String,
    );
  }
}

final class BoardingSessionNotification extends NotificationHint {
  const BoardingSessionNotification(
    int sessionId,
    String eventId, [
    String eventType = 'PREPARE_ALIGHT',
  ]) : super(sessionId, eventType, eventId);
  int get boardingSessionId => resourceId;
  @override
  String get source => 'BOARDING_SESSION_EVENT';

  static BoardingSessionNotification? parse(Map<String, dynamic> data) {
    if (data['type'] != 'BOARDING_SESSION_EVENT' ||
        !const {
          'PREPARE_ALIGHT',
          'DESTINATION_REACHED',
        }.contains(data['eventType']) ||
        data.containsKey('alarmId')) {
      return null;
    }
    final id = _positiveId(data['boardingSessionId']);
    if (id == null || _positiveId(data['notificationEventId']) == null) {
      return null;
    }
    return BoardingSessionNotification(
      id,
      data['notificationEventId'] as String,
      data['eventType'] as String,
    );
  }
}

/// The same three Firebase entry points, injectable without a Firebase server.
abstract interface class NotificationReceiver {
  Stream<RemoteMessage> get opened;
  Stream<RemoteMessage> get messages;
  Future<RemoteMessage?> initialMessage();
}

class FirebaseNotificationReceiver implements NotificationReceiver {
  @override
  Stream<RemoteMessage> get opened => FirebaseMessaging.onMessageOpenedApp;
  @override
  Stream<RemoteMessage> get messages => FirebaseMessaging.onMessage;
  @override
  Future<RemoteMessage?> initialMessage() =>
      FirebaseMessaging.instance.getInitialMessage();
}

typedef _Identity = ({int generation, String user, String session});

/// Payloads are hints. Screens must perform authenticated detail lookup.
class NotificationLifecycle extends ChangeNotifier {
  NotificationLifecycle(this.auth, {NotificationReceiver? receiver})
    : receiver = receiver ?? FirebaseNotificationReceiver() {
    _identity = _context;
    _captureRestoration();
    auth.addListener(_authChanged);
  }
  final AuthSession auth;
  final NotificationReceiver receiver;
  final Set<String> _seen = {};
  NotificationHint? _tap;
  NotificationHint? foreground;
  _Identity? _identity, _initialIdentity;
  bool _restorationResolved = false;
  final List<StreamSubscription<RemoteMessage>> _subscriptions = [];
  bool _disposed = false, _initialHandled = false;
  Future<void>? _startFuture;
  Future<RemoteMessage?>? _initial;

  _Identity? get _context {
    if (auth.state != AuthState.authenticated ||
        auth.isLoggingOut ||
        auth.userIdentity == null ||
        auth.sessionIdentity == null) {
      return null;
    }
    return (
      generation: auth.generation,
      user: auth.userIdentity!,
      session: auth.sessionIdentity!,
    );
  }

  void _captureRestoration() {
    if (_restorationResolved || auth.state == AuthState.initializing) return;
    _restorationResolved = true;
    _initialIdentity = _context;
  }

  Future<void> start() {
    if (_disposed) return Future<void>.value();
    final pending = _startFuture;
    if (pending != null) return pending;
    final work = _start();
    _startFuture = work;
    work.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {
        if (identical(_startFuture, work)) _startFuture = null;
      },
    );
    return work;
  }

  Future<void> _start() async {
    if (_subscriptions.isEmpty) {
      _subscriptions.add(
        receiver.opened.listen((message) {
          receive(message.data, messageId: message.messageId);
        }, onError: (Object _) {}),
      );
      _subscriptions.add(
        receiver.messages.listen((message) {
          receive(
            message.data,
            messageId: message.messageId,
            inForeground: true,
          );
        }, onError: (Object _) {}),
      );
    }
    // Cache even a slow initial query: retrying setup must not query it twice.
    RemoteMessage? initial;
    try {
      initial = await (_initial ??= receiver.initialMessage()).timeout(
        const Duration(seconds: 8),
      );
    } on TimeoutException {
      rethrow; // A later setup attempt can consume this same pending query.
    } catch (_) {
      // Fail closed for this initial hint. Live listeners and Device sync remain
      // usable even when the platform cannot recover its launch message.
      _initialHandled = true;
      return;
    }
    if (auth.state == AuthState.initializing) await auth.initialize();
    if (_disposed || _initialHandled) return;
    _captureRestoration();
    _initialHandled = true;
    if (_initialIdentity != null &&
        _initialIdentity == _context &&
        initial != null) {
      receive(initial.data, messageId: initial.messageId);
    }
  }

  bool _mark(String channel, NotificationHint hint) {
    if (!_seen.add('$channel:${hint.logicalKey}')) return false;
    if (_seen.length > 64) _seen.remove(_seen.first);
    return true;
  }

  void receive(
    Map<String, dynamic> data, {
    String? messageId,
    bool inForeground = false,
  }) {
    if (_disposed || _context == null) return;
    final hint = NotificationHint.parse(data);
    if (hint == null || !_mark(inForeground ? 'foreground' : 'tap', hint)) {
      return;
    }
    if (inForeground) {
      foreground = hint;
    } else {
      _tap = hint;
    }
    notifyListeners();
  }

  void openForeground(NotificationHint hint, int generation) {
    if (_disposed ||
        _context == null ||
        _identity != _context ||
        !auth.isCurrentGeneration(generation) ||
        !_mark('tap', hint)) {
      return;
    }
    _tap = hint;
    notifyListeners();
  }

  NotificationHint? takeTap() {
    final tap = !_disposed && _context != null && _context == _identity
        ? _tap
        : null;
    _tap = null;
    return tap;
  }

  void _authChanged() {
    _captureRestoration();
    if (_identity != _context) {
      _tap = null;
      foreground = null;
      _seen.clear();
      _identity = _context;
    }
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _tap = null;
    foreground = null;
    _seen.clear();
    auth.removeListener(_authChanged);
    for (final subscription in _subscriptions) {
      subscription.cancel();
    }
    super.dispose();
  }
}
