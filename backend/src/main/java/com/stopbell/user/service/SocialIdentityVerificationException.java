package com.stopbell.user.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class SocialIdentityVerificationException extends RuntimeException {

    public SocialIdentityVerificationException(Throwable cause) {
        super("Social identity verification is unavailable", cause);
    }
}
