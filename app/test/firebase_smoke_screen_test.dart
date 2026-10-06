import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/firebase_smoke.dart';

void main() {
  testWidgets('Firebase 구성이 없으면 target을 표시하지 않고 다시 준비할 수 있다', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    const coreChannel =
        'dev.flutter.pigeon.firebase_core_platform_interface.FirebaseCoreHostApi.initializeCore';
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    // Native firebase_core returns no apps when the actual plist is absent.
    messenger.setMockMessageHandler(
      coreChannel,
      (_) async => const StandardMessageCodec().encodeMessage([[]]),
    );
    try {
      await tester.pumpWidget(const MaterialApp(home: FirebaseSmokeScreen()));
      await tester.tap(find.text('Firebase / APNs / FID 준비'));
      await tester.pumpAndSettle();

      expect(find.textContaining('실패'), findsOneWidget);
      expect(find.byType(SelectableText), findsNothing);
      expect(find.textContaining('등록 성공'), findsNothing);
      expect(
        tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
        isNotNull,
      );
      expect(tester.takeException(), isNull);
    } finally {
      debugDefaultTargetPlatformOverride = null;
      messenger.setMockMessageHandler(coreChannel, null);
    }
  });
}
