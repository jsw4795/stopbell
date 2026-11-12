import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import '../notification/push_permission_service.dart';

/// 계정·알림·정책 설정 화면. 로그아웃과 탈퇴는 기존 흐름을 콜백으로 재사용한다.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    required this.onLogout,
    this.onDeleteAccount,
    this.pushProvider,
  });

  final VoidCallback onLogout;
  final Future<void> Function()? onDeleteAccount;
  final PushProvider? pushProvider;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  PushPermission? _permission;
  bool _permissionFailed = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadPermission();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _loadPermission();
  }

  Future<void> _loadPermission() async {
    final provider = widget.pushProvider;
    if (provider == null) return;
    try {
      final permission = await provider.permission();
      if (mounted) {
        setState(() {
          _permission = permission;
          _permissionFailed = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _permissionFailed = true);
    }
  }

  Future<void> _openSystemSettings() async {
    try {
      await widget.pushProvider?.openSettings();
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('설정 앱 > StopBell > 알림에서 직접 변경해 주세요.')),
      );
    }
  }

  (String, IconData, Color) _permissionLabel(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final semantic = StopBellColors.of(context);
    if (_permissionFailed) {
      return ('알림 권한을 확인하지 못했어요', Icons.help_outline, colors.onSurfaceVariant);
    }
    return switch (_permission) {
      PushPermission.authorized => (
        '알림 허용됨',
        Icons.notifications_active_outlined,
        semantic.success,
      ),
      PushPermission.provisional => (
        '무음으로 알림 허용됨',
        Icons.notifications_outlined,
        semantic.warning,
      ),
      PushPermission.denied => (
        '알림 꺼짐 · 하차 준비 알림을 받을 수 없어요',
        Icons.notifications_off_outlined,
        colors.error,
      ),
      PushPermission.notDetermined => (
        '아직 알림 권한을 요청하지 않았어요',
        Icons.notifications_none,
        colors.onSurfaceVariant,
      ),
      null => (
        '알림 권한 확인 중...',
        Icons.notifications_none,
        colors.onSurfaceVariant,
      ),
    };
  }

  Future<void> _confirmLogout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('로그아웃할까요?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('로그아웃'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    Navigator.of(context).pop();
    widget.onLogout();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final (label, icon, color) = _permissionLabel(context);
    return Scaffold(
      appBar: AppBar(title: const Text('설정')),
      body: ListView(
        padding: const EdgeInsets.all(StopBellSpacing.screenPadding),
        children: [
          const _SectionTitle('알림'),
          _SettingsCard(
            children: [
              if (widget.pushProvider == null)
                const ListTile(
                  leading: Icon(Icons.notifications_none),
                  title: Text('이 기기에서는 알림 설정을 지원하지 않아요'),
                )
              else ...[
                ListTile(
                  leading: Icon(icon, color: color),
                  title: Text(label),
                ),
                ListTile(
                  leading: const Icon(Icons.settings_outlined),
                  title: const Text('시스템 알림 설정 열기'),
                  trailing: const Icon(Icons.open_in_new, size: 18),
                  onTap: _openSystemSettings,
                ),
              ],
            ],
          ),
          const _SectionTitle('정보'),
          const _SettingsCard(
            children: [
              ListTile(
                enabled: false,
                leading: Icon(Icons.privacy_tip_outlined),
                title: Text('개인정보처리방침'),
                subtitle: Text('준비 중'),
              ),
              ListTile(
                enabled: false,
                leading: Icon(Icons.description_outlined),
                title: Text('서비스 이용약관'),
                subtitle: Text('준비 중'),
              ),
            ],
          ),
          const _SectionTitle('계정'),
          _SettingsCard(
            children: [
              ListTile(
                leading: const Icon(Icons.logout),
                title: const Text('로그아웃'),
                onTap: _confirmLogout,
              ),
            ],
          ),
          if (widget.onDeleteAccount != null) ...[
            const SizedBox(height: StopBellSpacing.xl * 2),
            Center(
              child: TextButton(
                key: const ValueKey('delete-account'),
                style: TextButton.styleFrom(
                  foregroundColor: colors.onSurfaceVariant,
                  textStyle: theme.textTheme.bodySmall,
                ),
                onPressed: widget.onDeleteAccount,
                child: const Text('회원 탈퇴'),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(
      StopBellSpacing.xs,
      StopBellSpacing.lg,
      StopBellSpacing.xs,
      StopBellSpacing.sm,
    ),
    child: Text(
      text,
      style: Theme.of(context).textTheme.labelLarge
          ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
    ),
  );
}

class _SettingsCard extends StatelessWidget {
  const _SettingsCard({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
    margin: EdgeInsets.zero,
    clipBehavior: Clip.antiAlias,
    child: Column(children: children),
  );
}
