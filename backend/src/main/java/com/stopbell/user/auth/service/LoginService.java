package com.stopbell.user.auth.service;

import java.util.function.BiConsumer;
import java.time.Instant;

import com.stopbell.user.auth.apple.AppleIdentityBoundary;

import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.dto.TokenResponse;
import com.stopbell.user.entity.AuthProvider;
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
    private final AppleIdentityBoundary appleBoundary;

    public LoginService(
            UserRepository userRepository,
            JwtTokenService jwtTokenService,
            RefreshTokenService refreshTokenService,
            PlatformTransactionManager transactionManager,
            AppleIdentityBoundary appleBoundary
    ) {
        this.userRepository = userRepository;
        this.appleBoundary = appleBoundary;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.loginTransaction = new TransactionTemplate(transactionManager);
        this.loginTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TokenResponse login(ExternalIdentity identity) {
        if (identity != null && identity.provider() == AuthProvider.APPLE) throw new IllegalArgumentException("Apple login requires verified authentication");
        return login(identity, null, null);
    }

    public TokenResponse loginApple(ExternalIdentity identity, Instant issuedAt, BiConsumer<User, Instant> storeCredential) {
        if (identity == null || identity.provider() != AuthProvider.APPLE || issuedAt == null || storeCredential == null) {
            throw new IllegalArgumentException("Apple identity is required");
        }
        return login(identity, issuedAt, storeCredential);
    }

    private TokenResponse login(ExternalIdentity identity, Instant issuedAt, BiConsumer<User, Instant> storeCredential) {
        try {
            return loginTransaction.execute(status -> {
                if (storeCredential != null) appleBoundary.acceptLogin(identity.providerUserId(), issuedAt);
                User user = userRepository.findByAuthProviderAndProviderUserId(
                        identity.provider(),
                        identity.providerUserId()
                ).orElseGet(() -> createUser(identity));
                if (storeCredential != null && !identity.providerUserId().equals(user.getProviderUserId())) throw new InvalidSocialCredentialException();
                return issueTokens(user, issuedAt, storeCredential);
            });
        } catch (IdentityConflict conflict) {
            // execute() has completed rollback and discarded the failed persistence context.
            return loginTransaction.execute(status -> {
                if (storeCredential != null) appleBoundary.acceptLogin(identity.providerUserId(), issuedAt);
                User user = userRepository.findByAuthProviderAndProviderUserId(
                        identity.provider(), identity.providerUserId()
                ).filter(existing -> existing.getAuthProvider() == identity.provider()
                        && identity.providerUserId().equals(existing.getProviderUserId())
                        && existing.getId() != null && existing.getId() > 0)
                        .orElseThrow(() -> conflict.failure);
                if (storeCredential != null && !identity.providerUserId().equals(user.getProviderUserId())) throw new InvalidSocialCredentialException();
                return issueTokens(user, issuedAt, storeCredential);
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

    private TokenResponse issueTokens(User user, Instant issuedAt, BiConsumer<User, Instant> storeCredential) {
        String providerUserId = user.getProviderUserId();
        AuthProvider provider = user.getAuthProvider();
        User locked = userRepository.findByIdForUpdate(user.getId())
                .filter(existing -> existing.getAuthProvider() == provider
                        && existing.getProviderUserId().equals(providerUserId))
                .orElseThrow(() -> new IllegalStateException("User identity is unavailable"));
        userRepository.requireActive(locked.getId());
        if (storeCredential != null) {
            storeCredential.accept(locked, issuedAt);
        }
        return new TokenResponse(
                jwtTokenService.createAccessToken(locked.getId()),
                refreshTokenService.issue(locked)
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
