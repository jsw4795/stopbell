/// Presentation only: the original provider name remains unchanged.
({String name, bool isNonstop}) stopDisplayName(String original) {
  final trimmed = original.trim();
  const suffix = '(미정차)';
  if (!trimmed.endsWith(suffix)) {
    return (name: trimmed, isNonstop: false);
  }
  return (
    name: trimmed.substring(0, trimmed.length - suffix.length).trimRight(),
    isNonstop: true,
  );
}
