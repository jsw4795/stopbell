package com.stopbell.user.auth.apple;

import com.stopbell.user.auth.dto.TokenResponse;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.service.LoginService;
import com.stopbell.user.entity.AuthProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "apple.authentication", name = "enabled", havingValue = "true")
public class AppleLoginController {
    private final AppleAuthentication authentication;
    private final AppleCredentialStore credentials;
    private final LoginService loginService;

    public AppleLoginController(AppleAuthentication authentication, AppleCredentialStore credentials,
                                LoginService loginService) {
        this.authentication = authentication;
        this.credentials = credentials;
        this.loginService = loginService;
    }

    @PostMapping("/auth/apple/challenge")
    public ResponseEntity<Challenge> challenge() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Challenge(authentication.issueNonce()));
    }

    @PostMapping("/auth/apple")
    public ResponseEntity<TokenResponse> login(@RequestBody AppleLoginRequest request) {
        if (request == null) throw new InvalidSocialCredentialException();
        AppleAuthentication.Result result = authentication.authenticate(
                request.identityToken(), request.authorizationCode(), request.nonce());
        TokenResponse tokens = loginService.loginApple(new ExternalIdentity(AuthProvider.APPLE, result.subject()),
                user -> credentials.store(user, result.refreshToken()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens);
    }

    public record Challenge(String nonce) { }

    public record AppleLoginRequest(String identityToken, String authorizationCode, String nonce) {
        @Override public String toString() { return "Apple login request [redacted]"; }
    }
}
