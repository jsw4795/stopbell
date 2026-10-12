class TokenPair {
  const TokenPair({
    required this.accessToken,
    required this.refreshToken,
    this.sessionIdentity,
  });

  final String accessToken;
  final String refreshToken;
  final String? sessionIdentity;

  TokenPair withSessionIdentity(String identity) => TokenPair(
    accessToken: accessToken,
    refreshToken: refreshToken,
    sessionIdentity: identity,
  );
}
