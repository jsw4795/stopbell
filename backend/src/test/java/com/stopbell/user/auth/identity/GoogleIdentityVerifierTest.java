package com.stopbell.user.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.json.gson.GsonFactory;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import com.stopbell.user.entity.AuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GoogleIdentityVerifierTest {

    private final GoogleIdTokenVerifier googleIdTokenVerifier = mock(GoogleIdTokenVerifier.class);
    private final GoogleIdentityVerifier googleIdentityVerifier = new GoogleIdentityVerifier(googleIdTokenVerifier);

    @BeforeEach
    void use_real_json_parser() {
        when(googleIdTokenVerifier.getJsonFactory()).thenReturn(GsonFactory.getDefaultInstance());
    }

    @Test
    @DisplayName("검증된 Google ID Token은 Google ExternalIdentity로 변환한다")
    void verify_valid_google_id_token() throws Exception {
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class))).thenReturn(true);
        ExternalIdentity identity = googleIdentityVerifier.verify(token("\"sub\":\"google-sub-1\""));
        assertThat(identity).isEqualTo(new ExternalIdentity(AuthProvider.GOOGLE, "google-sub-1"));
    }

    @Test
    @DisplayName("Google ID Token 검증에 실패하면 인증 실패로 변환한다")
    void reject_google_id_token_when_verification_fails() throws Exception {
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class)))
                .thenThrow(new GeneralSecurityException("private-signature-marker"));
        assertThatThrownBy(() -> googleIdentityVerifier.verify(token("\"sub\":\"fixture-user\"")))
                .isInstanceOf(InvalidSocialCredentialException.class);
    }

    @Test
    @DisplayName("Google ID Token 검증 중 I/O 실패는 외부 검증 실패로 변환한다")
    void convert_io_failure_to_social_identity_verification_exception() throws Exception {
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class)))
                .thenThrow(new IOException("private-key-fetch-marker"));
        assertThatThrownBy(() -> googleIdentityVerifier.verify(token("\"sub\":\"fixture-user\"")))
                .isInstanceOf(SocialIdentityVerificationException.class)
                .isNotInstanceOf(InvalidSocialCredentialException.class);
    }

    @Test
    @DisplayName("Google ID Token에 subject가 없으면 인증 실패로 처리한다")
    void reject_google_id_token_without_subject() throws Exception {
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class))).thenReturn(true);
        assertThatThrownBy(() -> googleIdentityVerifier.verify(token("\"aud\":\"fixture-audience\"")))
                .isInstanceOf(InvalidSocialCredentialException.class);
    }

    @Test
    @DisplayName("Google 검증 false는 인증 실패이며 Provider 장애로 바꾸지 않는다")
    void reject_unverified_credential() {
        assertThatThrownBy(() -> googleIdentityVerifier.verify(token("\"sub\":\"fixture-user\"")))
                .isInstanceOf(InvalidSocialCredentialException.class);
    }

    @ParameterizedTest
    @MethodSource("malformedCredentials")
    @DisplayName("실제 Google parser의 구분자와 JSON 파싱 실패는 인증 실패로 분류한다")
    void reject_malformed_credential_without_network(String credential) {
        GoogleIdentityVerifier verifier = new GoogleIdentityVerifier(localVerifier(new AssertionError("No network allowed")));
        assertThatThrownBy(() -> verifier.verify(credential)).isInstanceOf(InvalidSocialCredentialException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("null과 blank Google Credential은 verifier를 호출하지 않고 인증 실패한다")
    void reject_missing_credential(String credential) {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        assertThatThrownBy(() -> new GoogleIdentityVerifier(verifier).verify(credential))
                .isInstanceOf(InvalidSocialCredentialException.class);
        verifyNoInteractions(verifier);
    }

    @Test
    @DisplayName("실제 Google 공개키 조회 I/O 실패는 로컬 파싱 실패와 구분한다")
    void classify_real_key_fetch_failure() {
        GoogleIdentityVerifier verifier = new GoogleIdentityVerifier(localVerifier(new IOException("private-key-marker")));
        long now = Instant.now().getEpochSecond();
        String credential = token("\"sub\":\"fixture-user\",\"iss\":\"accounts.google.com\","
                + "\"aud\":\"fixture-audience\",\"iat\":" + (now - 60) + ",\"exp\":" + (now + 3600));
        assertThatThrownBy(() -> verifier.verify(credential)).isInstanceOf(SocialIdentityVerificationException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("검증 단계의 내부 RuntimeException은 Credential 실패로 숨기지 않는다")
    void propagate_internal_verification_failure() throws Exception {
        IllegalArgumentException failure = new IllegalArgumentException("private-implementation-marker");
        when(googleIdTokenVerifier.verify(any(GoogleIdToken.class))).thenThrow(failure);
        assertThatThrownBy(() -> googleIdentityVerifier.verify(token("\"sub\":\"fixture-user\""))).isSameAs(failure);
    }

    private static Stream<String> malformedCredentials() {
        return Stream.of("not-a-compact-token", "one.dot", "too.many.token.parts",
                "!!!.e30.c2ln", encode("{broken") + ".e30.c2ln",
                encode("{\"alg\":\"RS256\"}") + "." + encode("{broken") + ".c2ln");
    }

    private static GoogleIdTokenVerifier localVerifier(Throwable networkFailure) {
        HttpTransport transport = new HttpTransport() {
            @Override
            protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
                if (networkFailure instanceof IOException exception) throw exception;
                throw (AssertionError) networkFailure;
            }
        };
        return new GoogleIdTokenVerifier.Builder(transport, GsonFactory.getDefaultInstance())
                .setAudience(List.of("fixture-audience")).build();
    }

    private static String token(String claims) {
        return encode("{\"alg\":\"RS256\"}") + "." + encode("{" + claims + "}") + ".c2ln";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
