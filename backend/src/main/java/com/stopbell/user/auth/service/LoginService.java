package com.stopbell.user.auth.service;

import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.dto.TokenResponse;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoginService {

    private static final String IDENTITY_CONSTRAINT = "uk_users_auth_provider_provider_user_id";

    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final TransactionTemplate loginTransaction;

    public LoginService(
            UserRepository userRepository,
            JwtTokenService jwtTokenService,
            RefreshTokenService refreshTokenService,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.loginTransaction = new TransactionTemplate(transactionManager);
        this.loginTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TokenResponse login(ExternalIdentity identity) {
        try {
            return loginTransaction.execute(status -> {
                User user = userRepository.findByAuthProviderAndProviderUserId(
                        identity.provider(),
                        identity.providerUserId()
                ).orElseGet(() -> createUser(identity));
                return issueTokens(user);
            });
        } catch (IdentityConflict conflict) {
            // execute() has completed rollback and discarded the failed persistence context.
            return loginTransaction.execute(status -> {
                User user = userRepository.findByAuthProviderAndProviderUserId(
                        identity.provider(), identity.providerUserId()
                ).filter(existing -> existing.getAuthProvider() == identity.provider()
                        && identity.providerUserId().equals(existing.getProviderUserId())
                        && existing.getId() != null && existing.getId() > 0)
                        .orElseThrow(() -> conflict.failure);
                return issueTokens(user);
            });
        }
    }

    private User createUser(ExternalIdentity identity) {
        try {
            return userRepository.saveAndFlush(new User(identity.provider(), identity.providerUserId()));
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE
                        && (IDENTITY_CONSTRAINT.equals(violation.getConstraintName())
                        || ("users." + IDENTITY_CONSTRAINT).equals(violation.getConstraintName()))) {
                    throw new IdentityConflict(failure);
                }
            }
            throw failure;
        }
    }

    private TokenResponse issueTokens(User user) {
        return new TokenResponse(
                jwtTokenService.createAccessToken(user.getId()),
                refreshTokenService.issue(user)
        );
    }

    private static final class IdentityConflict extends RuntimeException {

        private final DataIntegrityViolationException failure;

        private IdentityConflict(DataIntegrityViolationException failure) {
            super("User identity insert conflicted", failure);
            this.failure = failure;
        }
    }
}
