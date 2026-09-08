package com.stopbell.user.auth.service;

import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.dto.TokenResponse;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class LoginService {

    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;

    public LoginService(
            UserRepository userRepository,
            JwtTokenService jwtTokenService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public TokenResponse login(ExternalIdentity identity) {
        User user = userRepository.findByAuthProviderAndProviderUserId(
                        identity.provider(),
                        identity.providerUserId()
                )
                .orElseGet(() -> userRepository.save(new User(
                        identity.provider(),
                        identity.providerUserId()
                )));

        return new TokenResponse(
                jwtTokenService.createAccessToken(user.getId()),
                refreshTokenService.issue(user)
        );
    }
}
