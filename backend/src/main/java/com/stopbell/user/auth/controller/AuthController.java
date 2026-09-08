package com.stopbell.user.auth.controller;

import com.stopbell.user.auth.dto.GoogleLoginRequest;
import com.stopbell.user.auth.dto.RefreshTokenRequest;
import com.stopbell.user.auth.dto.TokenResponse;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import com.stopbell.user.auth.service.LoginService;
import com.stopbell.user.auth.service.RefreshTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final GoogleIdentityVerifier googleIdentityVerifier;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
            GoogleIdentityVerifier googleIdentityVerifier,
            LoginService loginService,
            RefreshTokenService refreshTokenService
    ) {
        this.googleIdentityVerifier = googleIdentityVerifier;
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/auth/google")
    public TokenResponse loginWithGoogle(@RequestBody GoogleLoginRequest request) {
        return loginService.login(googleIdentityVerifier.verify(request.idToken()));
    }

    @PostMapping("/auth/refresh")
    public TokenResponse refresh(@RequestBody RefreshTokenRequest request) {
        return refreshTokenService.refresh(request == null ? null : request.refreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody RefreshTokenRequest request) {
        refreshTokenService.invalidate(request == null ? null : request.refreshToken());
    }
}
