import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'stopbell_colors.dart';
import 'stopbell_spacing.dart';

abstract final class StopBellTheme {
  static final light = _build(
    brightness: Brightness.light,
    background: const Color(0xfff5f7fb),
    surface: Colors.white,
    primary: const Color(0xff194bb5),
    onPrimary: Colors.white,
    secondary: const Color(0xff37475f),
    text: const Color(0xff18212f),
    secondaryText: const Color(0xff515d70),
    muted: const Color(0xff647186),
    error: const Color(0xffa82535),
    semanticColors: StopBellColors.light,
  );

  static final dark = _build(
    brightness: Brightness.dark,
    background: const Color(0xff101722),
    surface: const Color(0xff192231),
    primary: const Color(0xffa6c0ff),
    onPrimary: const Color(0xff102349),
    secondary: const Color(0xffb9c7dd),
    text: const Color(0xfff2f5fa),
    secondaryText: const Color(0xffafbacb),
    muted: const Color(0xff98a5b8),
    error: const Color(0xffff9faa),
    semanticColors: StopBellColors.dark,
  );

  static SystemUiOverlayStyle _overlayStyle(Brightness brightness) =>
      SystemUiOverlayStyle(
        statusBarBrightness: brightness,
        statusBarIconBrightness: brightness == Brightness.dark
            ? Brightness.light
            : Brightness.dark,
      );

  // Screens without an AppBar still need status bar icons with the right contrast.
  static Widget withSystemOverlay(BuildContext context, Widget? child) =>
      AnnotatedRegion<SystemUiOverlayStyle>(
        value: _overlayStyle(Theme.of(context).brightness),
        child: child!,
      );

  static ThemeData _build({
    required Brightness brightness,
    required Color background,
    required Color surface,
    required Color primary,
    required Color onPrimary,
    required Color secondary,
    required Color text,
    required Color secondaryText,
    required Color muted,
    required Color error,
    required StopBellColors semanticColors,
  }) {
    final isDark = brightness == Brightness.dark;
    final onAccent = isDark ? background : Colors.white;
    Color tint(Color accent, double opacity) =>
        Color.alphaBlend(accent.withValues(alpha: opacity), surface);

    final colors = ColorScheme(
      brightness: brightness,
      primary: primary,
      onPrimary: onPrimary,
      primaryContainer: tint(primary, .12),
      onPrimaryContainer: primary,
      secondary: secondary,
      onSecondary: onAccent,
      secondaryContainer: tint(secondary, .14),
      onSecondaryContainer: secondary,
      surface: surface,
      onSurface: text,
      onSurfaceVariant: secondaryText,
      surfaceContainerLowest: surface,
      surfaceContainerLow: background,
      surfaceContainer: tint(text, .04),
      surfaceContainerHigh: tint(text, .06),
      surfaceContainerHighest: tint(text, .09),
      outline: muted,
      outlineVariant: tint(muted, .28),
      error: error,
      onError: onAccent,
      errorContainer: tint(error, .10),
      onErrorContainer: error,
      inverseSurface: text,
      onInverseSurface: surface,
      inversePrimary: isDark
          ? const Color(0xff194bb5)
          : const Color(0xffa6c0ff),
    );

    final base = ThemeData(
      useMaterial3: true,
      visualDensity: VisualDensity.standard,
      colorScheme: colors,
      scaffoldBackgroundColor: background,
      disabledColor: muted,
      extensions: [semanticColors],
    );
    final typography = _typography(base.textTheme, colors);
    final button = typography.labelLarge!;
    final disabledFill = colors.surfaceContainer;
    final outline = BorderSide(color: muted);
    final choiceColor = WidgetStateProperty.resolveWith<Color>((states) {
      if (states.contains(WidgetState.disabled)) return muted;
      return states.contains(WidgetState.selected) ? primary : secondaryText;
    });

    OutlineInputBorder fieldBorder(Color color, {double width = 1}) =>
        OutlineInputBorder(
          borderRadius: BorderRadius.circular(StopBellRadius.field),
          borderSide: BorderSide(color: color, width: width),
        );

    return base.copyWith(
      textTheme: typography,
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(StopBellSpacing.minimumTouchSize, 50),
          padding: const EdgeInsets.symmetric(
            horizontal: StopBellSpacing.lg,
            vertical: StopBellSpacing.sm,
          ),
          shape: _rounded(StopBellRadius.button),
          textStyle: button,
          backgroundColor: primary,
          foregroundColor: onPrimary,
          disabledBackgroundColor: disabledFill,
          disabledForegroundColor: muted,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(StopBellSpacing.minimumTouchSize, 46),
          padding: const EdgeInsets.symmetric(
            horizontal: StopBellSpacing.lg,
            vertical: StopBellSpacing.sm,
          ),
          shape: _rounded(StopBellRadius.button),
          textStyle: button,
          foregroundColor: secondary,
          disabledForegroundColor: muted,
          side: outline,
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          minimumSize: const Size.square(StopBellSpacing.minimumTouchSize),
          padding: const EdgeInsets.symmetric(horizontal: StopBellSpacing.sm),
          shape: _rounded(StopBellSpacing.sm),
          textStyle: button,
          foregroundColor: primary,
          disabledForegroundColor: muted,
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          minimumSize: const Size.square(StopBellSpacing.minimumTouchSize),
          iconSize: 22,
          foregroundColor: secondary,
          disabledForegroundColor: muted,
          shape: _rounded(10),
        ),
      ),
      inputDecorationTheme: InputDecorationThemeData(
        constraints: const BoxConstraints(minHeight: 48),
        filled: true,
        fillColor: surface,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: StopBellSpacing.md,
          vertical: StopBellSpacing.md,
        ),
        border: fieldBorder(muted),
        enabledBorder: fieldBorder(muted),
        focusedBorder: fieldBorder(primary, width: 2),
        disabledBorder: fieldBorder(colors.outlineVariant),
        errorBorder: fieldBorder(error),
        focusedErrorBorder: fieldBorder(error, width: 2),
        errorStyle: typography.labelMedium!.copyWith(color: error),
      ),
      cardTheme: CardThemeData(
        color: surface,
        elevation: 0,
        surfaceTintColor: Colors.transparent,
        shape: _rounded(StopBellRadius.card),
      ),
      listTileTheme: ListTileThemeData(
        minVerticalPadding: StopBellSpacing.sm,
        titleTextStyle: typography.bodyLarge,
        subtitleTextStyle: typography.bodySmall,
        iconColor: secondary,
        selectedColor: primary,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) return muted;
          return states.contains(WidgetState.selected)
              ? onPrimary
              : secondaryText;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) return disabledFill;
          return states.contains(WidgetState.selected) ? primary : disabledFill;
        }),
        trackOutlineColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) return muted;
          return states.contains(WidgetState.selected)
              ? Colors.transparent
              : muted;
        }),
      ),
      checkboxTheme: CheckboxThemeData(
        fillColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected)
              ? choiceColor.resolve(states)
              : null,
        ),
        checkColor: WidgetStateProperty.resolveWith(
          (states) =>
              states.contains(WidgetState.disabled) ? surface : onPrimary,
        ),
      ),
      radioTheme: RadioThemeData(fillColor: choiceColor),
      dialogTheme: DialogThemeData(
        backgroundColor: surface,
        elevation: 0,
        surfaceTintColor: Colors.transparent,
        shape: _rounded(StopBellRadius.dialog),
        titleTextStyle: typography.titleLarge,
        contentTextStyle: typography.bodyLarge,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: colors.inverseSurface,
        contentTextStyle: typography.bodyMedium!.copyWith(color: surface),
        actionTextColor: colors.inversePrimary,
      ),
      appBarTheme: AppBarThemeData(
        backgroundColor: background,
        foregroundColor: text,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        titleTextStyle: typography.titleMedium,
        systemOverlayStyle: _overlayStyle(brightness),
      ),
      dividerTheme: DividerThemeData(
        color: colors.outlineVariant,
        thickness: 1,
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(color: primary),
    );
  }

  static RoundedRectangleBorder _rounded(double radius) =>
      RoundedRectangleBorder(borderRadius: BorderRadius.circular(radius));

  static TextTheme _typography(TextTheme base, ColorScheme colors) {
    TextStyle role(
      TextStyle? style,
      double size,
      double lineHeight,
      FontWeight weight, {
      Color? color,
    }) => style!.copyWith(
      fontSize: size,
      height: lineHeight / size,
      fontWeight: weight,
      color: color ?? colors.onSurface,
    );

    const regular = FontWeight.w400;
    const semibold = FontWeight.w600;
    const bold = FontWeight.w700;
    return base.copyWith(
      headlineLarge: role(base.headlineLarge, 28, 34, bold),
      headlineSmall: role(base.headlineSmall, 24, 30, bold),
      titleLarge: role(base.titleLarge, 20, 26, semibold),
      titleMedium: role(base.titleMedium, 17, 23, semibold),
      bodyLarge: role(base.bodyLarge, 16, 22, regular),
      bodyMedium: role(base.bodyMedium, 16, 22, regular),
      bodySmall: role(
        base.bodySmall,
        14,
        20,
        regular,
        color: colors.onSurfaceVariant,
      ),
      labelLarge: role(base.labelLarge, 16, 22, semibold),
      labelMedium: role(
        base.labelMedium,
        12,
        16,
        regular,
        color: colors.onSurfaceVariant,
      ),
      labelSmall: role(base.labelSmall, 13, 18, semibold),
    );
  }
}
