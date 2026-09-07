package com.stopbell.user.service;

import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class LoginService {

    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;

    public LoginService(UserRepository userRepository, JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public String login(ExternalIdentity identity) {
        User user = userRepository.findByAuthProviderAndProviderUserId(
                        identity.provider(),
                        identity.providerUserId()
                )
                .orElseGet(() -> userRepository.save(new User(
                        identity.provider(),
                        identity.providerUserId()
                )));

        return jwtTokenService.createAccessToken(user.getId());
    }
}
