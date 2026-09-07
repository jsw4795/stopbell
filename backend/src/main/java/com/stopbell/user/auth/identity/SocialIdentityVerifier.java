package com.stopbell.user.auth.identity;

public interface SocialIdentityVerifier {

    ExternalIdentity verify(String credential);
}
