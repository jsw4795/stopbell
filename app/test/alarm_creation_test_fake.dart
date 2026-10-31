import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/alarm/alarm_creation_controller.dart';

// Exercises the standalone stop screen's pre-existing UI behavior. Durable
// storage and session handling are exercised by controller and home tests.
class ScreenCreationFake extends Fake implements AlarmCreationController {
  ScreenCreationFake(this.api);
  final AlarmClient api;

  @override
  Future<Alarm?> create(
    int target,
    bool before,
    bool after,
    bool Function() current,
  ) => api.create(
    targetStopOccurrenceId: target,
    notifyOneStopBefore: before,
    notifyOneStopAfter: after,
  );
}
