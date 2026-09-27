import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';

class EmptyAlarmClient extends Fake implements AlarmClient {
  @override
  Future<List<Alarm>> findAll() async => [];
}
