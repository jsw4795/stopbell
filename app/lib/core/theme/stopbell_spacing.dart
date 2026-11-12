abstract final class StopBellSpacing {
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 24;
  static const double screenPadding = lg;
  static const double minimumTouchSize = 44;
}

abstract final class StopBellRadius {
  static const double button = 12;
  static const double field = 12;
  static const double card = 14;
  static const double dialog = 16;
}

/// 비활성·종료 카드 공통 흐림. 카드 전체(테두리·배경·글자·아이콘)에 같은 값을 적용한다.
/// .6은 보조 글자 대비가 라이트·다크 모두 약 3:1 이상으로 유지되는 하한에 가깝다.
abstract final class StopBellInactive {
  static const double opacity = .6;
}
