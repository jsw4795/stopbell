package com.stopbell.user.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.security.GeneralSecurityException;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GoogleIdentityVerifierTest {

    private final GoogleIdTokenVerifier googleIdTokenVerifier = mock(GoogleIdTokenVerifier.class);
    private final GoogleIdentityVerifier googleIdentityVerifier = new GoogleIdentityVerifier(googleIdTokenVerifier);

    @Test
    @DisplayName("검증된 Google ID Token은 Google ExternalIdentity로 변환한다")
    void verify_valid_google_id_token() throws Exception {
        GoogleIdToken googleIdToken = googleIdToken("google-sub-1");
        when(googleIdTokenVerifier.verify("valid-google-id-token")).thenReturn(googleIdToken);

        ExternalIdentity identity = googleIdentityVerifier.verify("valid-google-id-token");

        assertThat(identity).isEqualTo(new ExternalIdentity(AuthProvider.GOOGLE, "google-sub-1"));
    }

    @Test
    @DisplayName("Google ID Token 검증에 실패하면 인증 실패로 변환한다")
    void reject_google_id_token_when_verification_fails() throws Exception {
        when(googleIdTokenVerifier.verify("invalid-google-id-token"))
                .thenThrow(new GeneralSecurityException("invalid signature"));

        assertThatThrownBy(() -> googleIdentityVerifier.verify("invalid-google-id-token"))
                .isInstanceOf(InvalidSocialCredentialException.class);
    }

    @Test
    @DisplayName("Google ID Token 검증 중 I/O 실패는 외부 검증 실패로 변환한다")
    void convert_io_failure_to_social_identity_verification_exception() throws Exception {
        when(googleIdTokenVerifier.verify("unavailable-google-id-token"))
                .thenThrow(new IOException("Google public keys unavailable"));

        assertThatThrownBy(() -> googleIdentityVerifier.verify("unavailable-google-id-token"))
                .isInstanceOf(SocialIdentityVerificationException.class)
                .isNotInstanceOf(InvalidSocialCredentialException.class);
    }

    @Test
    @DisplayName("Google ID Token에 subject가 없으면 인증 실패로 처리한다")
    void reject_google_id_token_without_subject() throws Exception {
        GoogleIdToken googleIdToken = googleIdToken(null);
        when(googleIdTokenVerifier.verify("missing-subject-google-id-token"))
                .thenReturn(googleIdToken);

        assertThatThrownBy(() -> googleIdentityVerifier.verify("missing-subject-google-id-token"))
                .isInstanceOf(InvalidSocialCredentialException.class);
    }

    private GoogleIdToken googleIdToken(String subject) {
        GoogleIdToken googleIdToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = mock(GoogleIdToken.Payload.class);
        when(googleIdToken.getPayload()).thenReturn(payload);
        when(payload.getSubject()).thenReturn(subject);
        return googleIdToken;
    }
}
