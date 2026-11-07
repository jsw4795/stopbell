import 'package:flutter/material.dart';

class StopBellColors extends ThemeExtension<StopBellColors> {
  const StopBellColors({
    required this.success,
    required this.warning,
    required this.boardingStop,
    required this.targetStop,
    required this.nonstopStop,
    required this.activeTracking,
    required this.heldTracking,
    required this.busLocation,
  });

  static const light = StopBellColors(
    success: Color(0xff17683f),
    warning: Color(0xff8a4c00),
    boardingStop: Color(0xff17683f),
    targetStop: Color(0xff194bb5),
    nonstopStop: Color(0xff666666),
    activeTracking: Color(0xff17683f),
    heldTracking: Color(0xff8a4c00),
    busLocation: Color(0xff6543a5),
  );

  static const dark = StopBellColors(
    success: Color(0xff82d9a1),
    warning: Color(0xfff4bc70),
    boardingStop: Color(0xff82d9a1),
    targetStop: Color(0xffa6c0ff),
    nonstopStop: Color(0xffb0b0b0),
    activeTracking: Color(0xff82d9a1),
    heldTracking: Color(0xfff4bc70),
    busLocation: Color(0xffcbb6ff),
  );

  static StopBellColors of(BuildContext context) {
    final theme = Theme.of(context);
    return theme.extension<StopBellColors>() ??
        (theme.brightness == Brightness.dark ? dark : light);
  }

  final Color success;
  final Color warning;
  final Color boardingStop;
  final Color targetStop;
  final Color nonstopStop;
  final Color activeTracking;
  final Color heldTracking;
  final Color busLocation;

  @override
  StopBellColors copyWith({
    Color? success,
    Color? warning,
    Color? boardingStop,
    Color? targetStop,
    Color? nonstopStop,
    Color? activeTracking,
    Color? heldTracking,
    Color? busLocation,
  }) => StopBellColors(
    success: success ?? this.success,
    warning: warning ?? this.warning,
    boardingStop: boardingStop ?? this.boardingStop,
    targetStop: targetStop ?? this.targetStop,
    nonstopStop: nonstopStop ?? this.nonstopStop,
    activeTracking: activeTracking ?? this.activeTracking,
    heldTracking: heldTracking ?? this.heldTracking,
    busLocation: busLocation ?? this.busLocation,
  );

  @override
  StopBellColors lerp(StopBellColors? other, double t) {
    if (other == null) return this;
    return StopBellColors(
      success: Color.lerp(success, other.success, t)!,
      warning: Color.lerp(warning, other.warning, t)!,
      boardingStop: Color.lerp(boardingStop, other.boardingStop, t)!,
      targetStop: Color.lerp(targetStop, other.targetStop, t)!,
      nonstopStop: Color.lerp(nonstopStop, other.nonstopStop, t)!,
      activeTracking: Color.lerp(activeTracking, other.activeTracking, t)!,
      heldTracking: Color.lerp(heldTracking, other.heldTracking, t)!,
      busLocation: Color.lerp(busLocation, other.busLocation, t)!,
    );
  }
}
