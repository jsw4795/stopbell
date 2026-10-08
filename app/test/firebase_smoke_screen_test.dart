import 'dart:async';

// ignore: depend_on_referenced_packages
import 'package:firebase_app_installations_platform_interface/firebase_app_installations_platform_interface.dart';
import 'package:firebase_core/firebase_core.dart';

// FlutterFire's existing Pigeon test helpers; no new test dependency.
// ignore: depend_on_referenced_packages
import 'package:firebase_core_platform_interface/firebase_core_platform_interface.dart';
// ignore: depend_on_referenced_packages
import 'package:firebase_core_platform_interface/test.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/firebase_smoke.dart';

class _CoreFixture extends MockFirebaseApp {
  @override
  Future<List<CoreInitializeResponse>> initializeCore() async => [
    CoreInitializeResponse(
      name: '[DEFAULT]',
      options: CoreFirebaseOptions(
        apiKey: 'fixture',
        appId: 'fixture',
        messagingSenderId: 'fixture',
        projectId: 'fixture-project',
        iosBundleId: 'com.stopbell.stopbell',
      ),
      pluginConstants: {},
    ),
  ];
}

class _InstallationsFixture extends FirebaseAppInstallationsPlatform {
  _InstallationsFixture() : super(null);

  final idChanges = StreamController<String>.broadcast();
  late Future<String> Function() readId;

  @override
  FirebaseAppInstallationsPlatform delegateFor({required FirebaseApp app}) =>
      this;

  @override
  Future<String> getId() => readId();

  @override
  Stream<String> get onIdChange => idChanges.stream;
}

void smokeTestWidgets(String description, WidgetTesterCallback callback) {
  testWidgets(description, (tester) async {
    try {
      await callback(tester);
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const smoke = MethodChannel('com.stopbell.stopbell/firebase-smoke');
  const messaging = MethodChannel('plugins.flutter.io/firebase_messaging');
  final installations = _InstallationsFixture();
  FirebaseAppInstallationsPlatform.instance = installations;
  var rawId = 'raw-installation-fixture';
  var getIdCalls = 0;
  var registerCalls = 0;
  var apnsReady = true;
  var smokeCalls = <String>[];
  late Future<String?> Function() register;

  Future<void> emitIdChange(String id) async {
    installations.idChanges.add(id);
    await Future<void>.value();
  }

  setUp(() {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    MethodChannelFirebase.appInstances.clear();
    MethodChannelFirebase.isCoreInitialized = false;
    TestFirebaseCoreHostApi.setUp(_CoreFixture());
    rawId = 'raw-installation-fixture';
    getIdCalls = 0;
    registerCalls = 0;
    apnsReady = true;
    smokeCalls = [];
    register = () async => 'registered-fid-fixture';
    installations.readId = () async {
      getIdCalls++;
      return rawId;
    };
    messenger.setMockMethodCallHandler(messaging, (call) async {
      switch (call.method) {
        case 'Messaging#requestPermission':
          return {'authorizationStatus': 1};
        case 'Messaging#getAPNSToken':
          return {'token': apnsReady ? 'apns-fixture' : null};
        default:
          throw StateError('Unexpected Messaging method');
      }
    });
    messenger.setMockMethodCallHandler(smoke, (call) async {
      smokeCalls.add(call.method);
      if (call.method == 'registerAPNs') return null;
      if (call.method == 'register') {
        registerCalls++;
        return await register();
      }
      throw StateError('Unexpected smoke method');
    });
  });

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    TestFirebaseCoreHostApi.setUp(null);
    for (final channel in [smoke, messaging]) {
      messenger.setMockMethodCallHandler(channel, null);
    }
    MethodChannelFirebase.appInstances.clear();
    MethodChannelFirebase.isCoreInitialized = false;
  });

  Future<void> prepare(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: FirebaseSmokeScreen()));
    await tester.tap(find.text('Firebase / APNs / FID 준비'));
    await tester.pumpAndSettle();
  }

  smokeTestWidgets('Firebase 구성이 없으면 target을 표시하지 않고 다시 준비할 수 있다', (
    tester,
  ) async {
    messenger.setMockMessageHandler(
      'dev.flutter.pigeon.firebase_core_platform_interface.FirebaseCoreHostApi.initializeCore',
      (_) async => const StandardMessageCodec().encodeMessage([[]]),
    );
    await prepare(tester);
    expect(find.textContaining('실패'), findsOneWidget);
    expect(find.byType(SelectableText), findsNothing);
    expect(registerCalls, 0);
    expect(
      tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
      isNotNull,
    );
    expect(tester.takeException(), isNull);
  });

  smokeTestWidgets(
    'Push target은 raw FIS 값이 아닌 native registration callback FID다',
    (tester) async {
      await prepare(tester);
      final target = tester.widget<SelectableText>(find.byType(SelectableText));
      expect(target.data == 'registered-fid-fixture', isTrue);
      expect(find.text(rawId), findsNothing);
      expect(
        find.textContaining('FCM registration callback으로 FID 확보'),
        findsOneWidget,
      );
      expect(smokeCalls, ['registerAPNs', 'register']);
      expect(getIdCalls, 2);
      // A repeated event for the same raw FIS ID must not discard this target.
      await emitIdChange(rawId);
      await tester.pumpAndSettle();
      expect(find.byType(SelectableText), findsOneWidget);
      rawId = 'rotated-installation-fixture';
      await emitIdChange(rawId);
      await tester.pumpAndSettle();
      expect(find.byType(SelectableText), findsNothing);
      expect(find.textContaining('다시 등록하세요'), findsOneWidget);
      await tester.tap(find.text('Firebase / APNs / FID 준비'));
      await tester.pumpAndSettle();
      expect(find.byType(SelectableText), findsOneWidget);
      expect(registerCalls, 2);
    },
  );

  smokeTestWidgets('재시도 중과 등록 실패 후 이전 target을 폐기한다', (tester) async {
    await prepare(tester);
    expect(find.byType(SelectableText), findsOneWidget);

    final pending = Completer<String?>();
    register = () => pending.future;
    await tester.tap(find.text('Firebase / APNs / FID 준비'));
    await tester.pumpAndSettle();
    expect(find.byType(SelectableText), findsNothing);

    pending.completeError(PlatformException(code: 'fcm-registration-failed'));
    await tester.pumpAndSettle();
    expect(find.text('iOS smoke 실패: fcm-registration-failed'), findsOneWidget);
    expect(find.byType(SelectableText), findsNothing);

    rawId = 'new-installation-fixture';
    register = () async => rawId;
    await tester.tap(find.text('Firebase / APNs / FID 준비'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<SelectableText>(find.byType(SelectableText)).data,
      rawId,
    );
  });

  smokeTestWidgets('pending 등록은 target을 숨기고 중복 버튼 요청을 차단한다', (tester) async {
    final pending = Completer<String?>();
    register = () => pending.future;
    await prepare(tester);
    expect(find.byType(SelectableText), findsNothing);
    expect(
      tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
      isNull,
    );
    await tester.tap(find.byType(ElevatedButton));
    expect(registerCalls, 1);
    pending.complete('registered-fid-fixture');
    await tester.pumpAndSettle();
    expect(find.byType(SelectableText), findsOneWidget);
  });

  for (final value in <String?>[null, '']) {
    smokeTestWidgets(
      'callback FID가 ${value == null ? 'null' : '빈 값'}이면 target을 표시하지 않는다',
      (tester) async {
        register = () async => value;
        await prepare(tester);
        expect(find.byType(SelectableText), findsNothing);
        expect(find.textContaining('callback FID가 없습니다'), findsOneWidget);
      },
    );
  }

  for (final code in [
    'fcm-registration-failed',
    'fcm-registration-timeout',
    'fcm-registration-in-progress',
  ]) {
    smokeTestWidgets('$code 시 target을 숨기고 다시 준비할 수 있다', (tester) async {
      register = () async => throw PlatformException(code: code);
      await prepare(tester);
      expect(find.byType(SelectableText), findsNothing);
      expect(find.text('iOS smoke 실패: $code'), findsOneWidget);
      expect(
        tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
        isNotNull,
      );
      register = () async => 'registered-fid-fixture';
      await tester.tap(find.text('Firebase / APNs / FID 준비'));
      await tester.pumpAndSettle();
      expect(find.byType(SelectableText), findsOneWidget);
    });
  }

  smokeTestWidgets('등록 중 raw FIS rotation은 callback target을 무효화한다', (
    tester,
  ) async {
    final pending = Completer<String?>();
    register = () => pending.future;
    await prepare(tester);
    await emitIdChange('rotated-installation-fixture');
    await tester.pumpAndSettle();
    // Even a subsequent getId returning the old value cannot hide a rotation event.
    pending.complete('registered-fid-fixture');
    await tester.pumpAndSettle();
    expect(find.byType(SelectableText), findsNothing);
    expect(find.textContaining('등록 중 FID가 변경'), findsOneWidget);
  });

  smokeTestWidgets('등록 전후 raw FIS 값이 다르면 target을 폐기한다', (tester) async {
    register = () async {
      rawId = 'rotated-installation-fixture';
      return 'registered-fid-fixture';
    };
    await prepare(tester);
    expect(find.byType(SelectableText), findsNothing);
    expect(find.textContaining('등록 중 FID가 변경'), findsOneWidget);
  });

  smokeTestWidgets('APNs 준비 실패 시 FCM register를 호출하지 않는다', (tester) async {
    apnsReady = false;
    await prepare(tester);
    await tester.pump(const Duration(seconds: 11));
    // Polling schedules one delay at a time under the widget-test clock.
    for (var attempt = 0; attempt < 20; attempt++) {
      await tester.pump(const Duration(milliseconds: 500));
    }
    await tester.pumpAndSettle();
    expect(registerCalls, 0);
    expect(find.byType(SelectableText), findsNothing);
    expect(find.textContaining('APNs 미준비'), findsOneWidget);
  });
}
