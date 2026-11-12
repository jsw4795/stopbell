import 'package:flutter/material.dart';

import '../../core/theme/stopbell_spacing.dart';

/// 로그인 화면 본문. 인증 흐름은 호출자가 소유하고 이 위젯은 표시만 담당한다.
class LoginView extends StatelessWidget {
  const LoginView({
    super.key,
    required this.initializing,
    required this.loading,
    required this.loadingMessage,
    required this.onGoogle,
    this.onApple,
    this.messages = const [],
  });

  final bool initializing;
  final bool loading;
  final String loadingMessage;
  final VoidCallback onGoogle;
  final VoidCallback? onApple;
  final List<String> messages;

  static const _buttonHeight = 56.0;
  static const _buttonRadius = 16.0;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final dark = theme.brightness == Brightness.dark;
    final gradient = dark
        ? [const Color(0xff141c2b), const Color(0xff1b2238), colors.surface]
        : [const Color(0xffeef3ff), const Color(0xfff3efff), Colors.white];
    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: gradient,
          ),
        ),
        child: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(
                horizontal: 40,
                vertical: StopBellSpacing.xl,
              ),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 320),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Icon(
                      Icons.directions_bus_rounded,
                      size: 72,
                      color: colors.primary,
                      semanticLabel: '버스',
                    ),
                    const SizedBox(height: StopBellSpacing.lg),
                    Text(
                      'StopBell',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.headlineLarge?.copyWith(
                        fontWeight: FontWeight.w800,
                        color: colors.onSurface,
                      ),
                    ),
                    const SizedBox(height: StopBellSpacing.xl),
                    Text(
                      '내릴 정류장을\n잊지 않도록, StopBell',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.titleMedium?.copyWith(
                        color: dark
                            ? colors.onSurfaceVariant
                            : const Color(0xff3c3f7a),
                        fontWeight: FontWeight.w600,
                        height: 1.5,
                      ),
                    ),
                    const SizedBox(height: StopBellSpacing.xl * 2),
                    if (initializing)
                      _status(context, '인증 상태 확인 중...')
                    else ...[
                      _buttonShadow(
                        dark: dark,
                        child: _AuthButton(
                          key: const ValueKey('google-login'),
                          label: 'Google로 계속하기',
                          semanticsLabel: 'Google로 계속하기',
                          icon: Image.asset(
                            'assets/auth/google_g.png',
                            width: 24,
                            height: 24,
                          ),
                          background: Colors.white,
                          foreground: const Color(0xff1f1f1f),
                          onPressed: loading ? null : onGoogle,
                          height: _buttonHeight,
                          radius: _buttonRadius,
                        ),
                      ),
                      if (onApple != null) ...[
                        const SizedBox(height: StopBellSpacing.md),
                        _buttonShadow(
                          dark: dark,
                          child: _AuthButton(
                            key: const ValueKey('apple-login'),
                            label: 'Apple로 계속하기',
                            semanticsLabel: 'Apple로 계속하기',
                            icon: Icon(
                              Icons.apple,
                              size: 24,
                              color: dark ? Colors.black : Colors.white,
                            ),
                            background: dark ? Colors.white : Colors.black,
                            foreground: dark ? Colors.black : Colors.white,
                            onPressed: loading ? null : onApple!,
                            height: _buttonHeight,
                            radius: _buttonRadius,
                          ),
                        ),
                        const SizedBox(height: StopBellSpacing.md),
                        Text(
                          'Google과 Apple 계정은 각각 별도로 관리됩니다.',
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: colors.onSurfaceVariant,
                          ),
                        ),
                      ],
                      if (loading) ...[
                        const SizedBox(height: StopBellSpacing.xl),
                        _status(context, loadingMessage),
                      ],
                      for (final message in messages) ...[
                        const SizedBox(height: StopBellSpacing.lg),
                        _ErrorNotice(message: message),
                      ],
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// 라이트 배경에서 흰 버튼이 구분되도록 두 버튼에 같은 약한 그림자를 준다.
  Widget _buttonShadow({required bool dark, required Widget child}) =>
      DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(_buttonRadius),
          boxShadow: dark
              ? null
              : const [
                  BoxShadow(
                    color: Color(0x1f000000),
                    blurRadius: 8,
                    offset: Offset(0, 2),
                  ),
                ],
        ),
        child: child,
      );

  Widget _status(BuildContext context, String text) => Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      const CircularProgressIndicator(),
      const SizedBox(height: StopBellSpacing.md),
      Text(text, textAlign: TextAlign.center),
    ],
  );
}

class _ErrorNotice extends StatelessWidget {
  const _ErrorNotice({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      child: Container(
        padding: const EdgeInsets.all(StopBellSpacing.md),
        decoration: BoxDecoration(
          color: colors.errorContainer,
          borderRadius: BorderRadius.circular(StopBellRadius.card),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.error_outline, color: colors.onErrorContainer, size: 20),
            const SizedBox(width: StopBellSpacing.sm),
            Expanded(
              child: Text(
                message,
                style: TextStyle(color: colors.onErrorContainer),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Google·Apple 공용 로그인 버튼. 같은 모양·글자 규칙을 써서 두 버튼을 통일한다.
class _AuthButton extends StatelessWidget {
  const _AuthButton({
    super.key,
    required this.label,
    required this.semanticsLabel,
    required this.icon,
    required this.background,
    required this.foreground,
    required this.onPressed,
    required this.height,
    required this.radius,
  });

  final String label, semanticsLabel;
  final Widget icon;
  final Color background, foreground;
  final VoidCallback? onPressed;
  final double height, radius;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: semanticsLabel,
      excludeSemantics: true,
      child: Material(
        color: background,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radius),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onPressed,
          child: SizedBox(
            height: height,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                SizedBox(
                  width: height * 28 / 44,
                  child: Center(child: icon),
                ),
                Text(
                  label,
                  style: TextStyle(
                    inherit: false,
                    // iOS 기본 버튼 글자 크기. 한글은 시스템 폰트(Apple SD Gothic Neo)로 대체된다.
                    fontSize: 17,
                    fontWeight: FontWeight.w500,
                    fontFamily: '.SF Pro Text',
                    letterSpacing: -0.41,
                    color: foreground,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
