import 'dart:async';

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import '../auth/auth_session.dart';

class AlarmNotification {
  const AlarmNotification(this.alarmId, this.eventType, this.eventId);
  final int alarmId;
  final String eventType;
  final String eventId;

  static AlarmNotification? parse(Map<String, dynamic> data) {
    if (data['type'] != 'ALARM_EVENT' ||
        !{
          'ONE_STOP_BEFORE',
          'ARRIVED',
          'PASSED',
          'ONE_STOP_AFTER',
        }.contains(data['eventType'])) {
      return null;
    }
    final alarm = data['alarmId'];
    final event = data['notificationEventId'];
    if (alarm is! String ||
        event is! String ||
        !RegExp(r'^[1-9][0-9]*$').hasMatch(alarm) ||
        !RegExp(r'^[1-9][0-9]*$').hasMatch(event)) {
      return null;
    }
    final id = int.tryParse(alarm);
    final eventId = int.tryParse(event);
    if (id == null || eventId == null || id <= 0 || eventId <= 0) return null;
    return AlarmNotification(id, data['eventType'] as String, event);
  }
}

/// Payloads are hints; AlarmHome performs authenticated detail lookup before display.
class NotificationLifecycle extends ChangeNotifier {
  NotificationLifecycle(this.auth) {
    auth.addListener(_authChanged);
  }
  final AuthSession auth;
  final Set<String> _seen = {};
  AlarmNotification? _tap;
  int? _tapGeneration;
  AlarmNotification? foreground;
  final List<StreamSubscription<RemoteMessage>> _subscriptions = [];
  bool _disposed = false;
  bool _started = false;

  Future<void>? _startFuture;
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
    if (!_started) {
      _started = true;
      _subscriptions.add(
        FirebaseMessaging.onMessageOpenedApp.listen((message) {
          receive(message.data, messageId: message.messageId);
        }, onError: (Object _) {}),
      );
      _subscriptions.add(
        FirebaseMessaging.onMessage.listen((message) {
          receive(
            message.data,
            messageId: message.messageId,
            inForeground: true,
          );
        }, onError: (Object _) {}),
      );
    }
    final generation = auth.generation;
    final initial = await FirebaseMessaging.instance
        .getInitialMessage()
        .timeout(const Duration(seconds: 8));
    if (!_disposed &&
        generation == auth.generation &&
        !auth.isLoggingOut &&
        initial != null) {
      receive(initial.data, messageId: initial.messageId);
    }
  }

  void receive(
    Map<String, dynamic> data, {
    String? messageId,
    bool inForeground = false,
  }) {
    if (_disposed || auth.isLoggingOut) return;
    final hint = AlarmNotification.parse(data);
    if (hint == null) return;
    final key =
        '${inForeground ? 'foreground' : 'tap'}:${messageId ?? '${hint.eventId}:${hint.eventType}:${hint.alarmId}'}';
    if (!_seen.add(key)) return;
    if (_seen.length > 64) _seen.remove(_seen.first);
    if (inForeground) {
      if (auth.state != AuthState.authenticated) return;
      foreground = hint;
    } else {
      _tap = hint;
      _tapGeneration = auth.state == AuthState.authenticated
          ? auth.generation
          : null;
    }
    notifyListeners();
  }

  void openForeground(AlarmNotification hint, int generation) {
    if (!auth.isCurrentGeneration(generation) || auth.isLoggingOut) return;
    _tap = hint;
    _tapGeneration = generation;
    notifyListeners();
  }

  AlarmNotification? takeTap() {
    if (auth.state != AuthState.authenticated || auth.isLoggingOut) return null;
    final tap = _tap;
    final allowed = _tapGeneration == null || _tapGeneration == auth.generation;
    _tap = null;
    _tapGeneration = null;
    return allowed ? tap : null;
  }

  void _authChanged() {
    if (auth.isLoggingOut ||
        auth.state == AuthState.unauthenticated && _tapGeneration != null) {
      _tap = null;
      _tapGeneration = null;
      foreground = null;
    }
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    auth.removeListener(_authChanged);
    for (final subscription in _subscriptions) {
      subscription.cancel();
    }
    super.dispose();
  }
}
