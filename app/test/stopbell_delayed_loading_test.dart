import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/shared/stopbell_button_label.dart';
import 'package:stopbell/shared/stopbell_delayed_loading.dart';
import 'package:stopbell/shared/stopbell_skeleton.dart';

Widget host({
  required bool loading,
  VoidCallback? onRetry,
  bool disableAnimations = false,
}) => MaterialApp(
  home: MediaQuery(
    data: MediaQueryData(disableAnimations: disableAnimations),
    child: Scaffold(
      body: StopBellDelayedLoading(
        loading: loading,
        onRetry: onRetry,
        skeleton: const StopBellSkeleton(key: ValueKey('skeleton')),
        child: const Text('내용'),
      ),
    ),
  ),
);

void main() {
  testWidgets('0.3초 안에 끝나면 스켈레톤을 표시하지 않는다', (tester) async {
    await tester.pumpWidget(host(loading: true));
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.byKey(const ValueKey('skeleton')), findsNothing);
    await tester.pumpWidget(host(loading: false));
    await tester.pump(const Duration(seconds: 1));
    expect(find.byKey(const ValueKey('skeleton')), findsNothing);
    expect(find.text('내용'), findsOneWidget);
  });

  testWidgets('표시했으면 로딩이 끝나도 최소 0.4초 유지한다', (tester) async {
    await tester.pumpWidget(host(loading: true));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byKey(const ValueKey('skeleton')), findsOneWidget);
    await tester.pumpWidget(host(loading: false));
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.byKey(const ValueKey('skeleton')), findsOneWidget);
    expect(find.text('내용'), findsNothing);
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.byKey(const ValueKey('skeleton')), findsOneWidget);
    expect(find.text('내용'), findsOneWidget);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    await tester.pump();
    expect(find.byKey(const ValueKey('skeleton')), findsNothing);
  });

  testWidgets('8초에 지연 안내, 15초에 다시 시도 버튼을 보여 준다', (tester) async {
    var retries = 0;
    await tester.pumpWidget(host(loading: true, onRetry: () => retries++));
    await tester.pump(const Duration(seconds: 7));
    expect(find.text('응답이 늦어지고 있어요'), findsNothing);
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('응답이 늦어지고 있어요'), findsOneWidget);
    expect(find.text('다시 시도'), findsNothing);
    await tester.pump(const Duration(seconds: 7));
    expect(find.text('다시 시도'), findsOneWidget);
    await tester.tap(find.text('다시 시도'));
    expect(retries, 1);
  });

  testWidgets('불러오는 중을 스크린리더에 알린다', (tester) async {
    final handle = tester.ensureSemantics();
    await tester.pumpWidget(host(loading: true));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 150));
    expect(find.bySemanticsLabel('불러오는 중'), findsOneWidget);
    handle.dispose();
  });

  testWidgets('동작 줄이기에서는 정적으로 표시하고 즉시 전환한다', (tester) async {
    await tester.pumpWidget(host(loading: true, disableAnimations: true));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byKey(const ValueKey('skeleton')), findsOneWidget);
    expect(find.byType(ShaderMask), findsNothing);
    expect(find.byType(AnimatedSwitcher), findsNothing);
    await tester.pumpWidget(host(loading: false, disableAnimations: true));
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byKey(const ValueKey('skeleton')), findsNothing);
    expect(find.text('내용'), findsOneWidget);
  });

  testWidgets('스켈레톤과 콘텐츠를 AnimatedSwitcher로 전환한다', (tester) async {
    await tester.pumpWidget(host(loading: true));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(AnimatedSwitcher), findsOneWidget);
    expect(find.byType(ShaderMask), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 150));
    await tester.pump(const Duration(milliseconds: 250));
    await tester.pumpWidget(host(loading: false));
    expect(find.byKey(const ValueKey('skeleton')), findsOneWidget);
    expect(find.text('내용'), findsOneWidget);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    await tester.pump();
    expect(find.byKey(const ValueKey('skeleton')), findsNothing);
    expect(find.byType(StopBellShimmer), findsNothing);
  });

  testWidgets('버튼 라벨은 동작 중에만 작은 스피너를 보여 주고 중복 탭이 막힌다', (tester) async {
    var taps = 0;
    Widget button(bool busy) => MaterialApp(
      home: Scaffold(
        body: FilledButton(
          onPressed: busy ? null : () => taps++,
          child: StopBellButtonLabel('알람 시작', busy: busy),
        ),
      ),
    );
    await tester.pumpWidget(button(false));
    expect(find.byType(CircularProgressIndicator), findsNothing);
    await tester.tap(find.text('알람 시작'));
    expect(taps, 1);
    await tester.pumpWidget(button(true));
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    await tester.tap(find.text('알람 시작'));
    expect(taps, 1);
  });
}
