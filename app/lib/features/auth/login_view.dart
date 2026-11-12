import 'package:flutter/material.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

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
                horizontal: StopBellSpacing.xl,
                vertical: StopBellSpacing.xl,
              ),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 400),
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
                      _GoogleButton(
                        onPressed: loading ? null : onGoogle,
                        height: _buttonHeight,
                        radius: _buttonRadius,
                      ),
                      if (onApple != null) ...[
                        const SizedBox(height: StopBellSpacing.md),
                        SignInWithAppleButton(
                          text: 'Apple로 계속하기',
                          height: _buttonHeight,
                          borderRadius: const BorderRadius.all(
                            Radius.circular(_buttonRadius),
                          ),
                          onPressed: loading ? null : onApple!,
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

class _GoogleButton extends StatelessWidget {
  const _GoogleButton({
    required this.onPressed,
    required this.height,
    required this.radius,
  });

  final VoidCallback? onPressed;
  final double height;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final fontSize = height * 0.43;
    return Semantics(
      button: true,
      label: 'Google로 계속하기',
      excludeSemantics: true,
      child: Material(
        color: dark ? const Color(0xff131314) : Colors.white,
        elevation: dark ? 0 : 2,
        shadowColor: Colors.black26,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radius),
          side: BorderSide(
            color: dark ? const Color(0xff8e918f) : const Color(0xffe3e3e3),
          ),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: const ValueKey('google-login'),
          onTap: onPressed,
          child: SizedBox(
            height: height,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Apple 버튼(sign_in_with_apple)과 같은 치수 규칙: 글자 height*0.43, 아이콘 칸 height*28/44
                SizedBox(
                  width: height * 28 / 44,
                  child: Center(
                    child: Image.asset(
                      dark
                          ? 'assets/auth/dark/google_g.png'
                          : 'assets/auth/google_g.png',
                      width: fontSize,
                      height: fontSize,
                    ),
                  ),
                ),
                Text(
                  'Google로 계속하기',
                  style: TextStyle(
                    inherit: false,
                    fontSize: fontSize,
                    fontFamily: '.SF Pro Text',
                    letterSpacing: -0.41,
                    color: dark
                        ? const Color(0xffe3e3e3)
                        : const Color(0xff1f1f1f),
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
