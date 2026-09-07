package com.stopbell.user.service;

import java.io.IOException;
import java.security.GeneralSecurityException;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.stopbell.user.entity.AuthProvider;
import org.springframework.stereotype.Service;

@Service
public class GoogleIdentityVerifier implements SocialIdentityVerifier {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;

    public GoogleIdentityVerifier(GoogleIdTokenVerifier googleIdTokenVerifier) {
        this.googleIdTokenVerifier = googleIdTokenVerifier;
    }

    @Override
    public ExternalIdentity verify(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new InvalidSocialCredentialException();
        }

        try {
            GoogleIdToken googleIdToken = googleIdTokenVerifier.verify(credential);
            if (googleIdToken == null) {
                throw new InvalidSocialCredentialException();
            }

            String providerUserId = googleIdToken.getPayload().getSubject();
            if (providerUserId == null || providerUserId.isBlank()) {
                throw new InvalidSocialCredentialException();
            }

            return new ExternalIdentity(AuthProvider.GOOGLE, providerUserId);
        } catch (IOException | GeneralSecurityException exception) {
            throw new InvalidSocialCredentialException(exception);
        }
    }
}
