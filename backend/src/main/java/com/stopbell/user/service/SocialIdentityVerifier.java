package com.stopbell.user.service;

public interface SocialIdentityVerifier {

    ExternalIdentity verify(String credential);
}
