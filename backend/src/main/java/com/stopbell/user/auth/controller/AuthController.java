package com.stopbell.user.auth.controller;

import com.stopbell.user.auth.dto.AccessTokenResponse;
import com.stopbell.user.auth.dto.GoogleLoginRequest;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import com.stopbell.user.auth.service.LoginService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final GoogleIdentityVerifier googleIdentityVerifier;
    private final LoginService loginService;

    public AuthController(GoogleIdentityVerifier googleIdentityVerifier, LoginService loginService) {
        this.googleIdentityVerifier = googleIdentityVerifier;
        this.loginService = loginService;
    }

    @PostMapping("/auth/google")
    public AccessTokenResponse loginWithGoogle(@RequestBody GoogleLoginRequest request) {
        return new AccessTokenResponse(loginService.login(googleIdentityVerifier.verify(request.idToken())));
    }
}
