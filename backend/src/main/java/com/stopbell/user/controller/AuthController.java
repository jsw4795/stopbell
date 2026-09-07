package com.stopbell.user.controller;

import com.stopbell.user.dto.AccessTokenResponse;
import com.stopbell.user.dto.GoogleLoginRequest;
import com.stopbell.user.service.GoogleIdentityVerifier;
import com.stopbell.user.service.LoginService;
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
