package com.stopbell.user.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidSocialCredentialException extends RuntimeException {

    public InvalidSocialCredentialException() {
        super("Social credential is invalid");
    }

    public InvalidSocialCredentialException(Throwable cause) {
        super("Social credential is invalid", cause);
    }
}
