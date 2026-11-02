import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/app_config.dart';

void main() {
  test('필수 설정 누락을 각 key 이름으로 알린다', () {
    const config = AppConfig(
      apiBaseUrl: 'http://localhost:8080',
      googleIosClientId: '',
      googleServerClientId: ' ',
    );

    expect(
      config.validate,
      throwsA(
        isA<AppConfigException>().having(
          (error) => error.message,
          'message',
          contains('GOOGLE_IOS_CLIENT_ID, GOOGLE_SERVER_CLIENT_ID'),
        ),
      ),
    );
  });
  test('release 설정은 HTTP 주소를 거부하고 HTTPS를 허용한다', () {
    const insecure = AppConfig(
      apiBaseUrl: 'http://localhost:8080',
      googleIosClientId: 'ios-client',
      googleServerClientId: 'server-client',
      requireHttps: true,
    );
    const secure = AppConfig(
      apiBaseUrl: 'https://api.example.com',
      googleIosClientId: 'ios-client',
      googleServerClientId: 'server-client',
      requireHttps: true,
    );
    expect(insecure.validate, throwsA(isA<AppConfigException>()));
    expect(secure.validate, returnsNormally);
  });
}
