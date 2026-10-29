package com.stopbell.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.stopbell.user.auth.dto.TokenResponse;
import com.stopbell.user.auth.exception.InvalidRefreshTokenException;
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.RefreshToken;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.RefreshTokenRepository;
import com.stopbell.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LoginServiceConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired private LoginService loginService;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private RefreshTokenService tokenService;
    @MockitoSpyBean private UserRepository users;
    @MockitoBean private GoogleIdentityVerifier googleIdentityVerifier;

    @Test
    @DisplayName("동일 신규 Identity의 실제 INSERT 경쟁을 rollback 뒤 새 Transaction에서 복구하고 두 Session을 저장한다")
    void recover_concurrent_first_login() throws Exception {
        ExternalIdentity identity = new ExternalIdentity(AuthProvider.GOOGLE, "concurrent-first-login");
        long userCount = users.count();
        long sessionCount = refreshTokens.count();
        assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
        CountDownLatch bothAbsent = new CountDownLatch(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger uniqueConflicts = new AtomicInteger();
        AtomicReference<Object> failedContext = new AtomicReference<>();
        AtomicReference<EntityManager> failedEntityManager = new AtomicReference<>();
        Set<Object> initialContexts = ConcurrentHashMap.newKeySet();

        doAnswer(call -> {
            @SuppressWarnings("unchecked")
            Optional<User> result = (Optional<User>) mockingDetails(users).getMockCreationSettings()
                    .getDefaultAnswer().answer(call);
            Object context = TransactionSynchronizationManager.getResource(entityManagerFactory);
            assertThat(context).isNotNull();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (reads.incrementAndGet() <= 2) {
                assertThat(result).isEmpty();
                initialContexts.add(context);
                EntityManager currentEntityManager = ((EntityManagerHolder) context).getEntityManager();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            failedContext.set(context);
                            failedEntityManager.set(currentEntityManager);
                            rollbacks.incrementAndGet();
                        } else if (status == STATUS_COMMITTED) {
                            commits.incrementAndGet();
                            winnerCommitted.countDown();
                        }
                    }
                });
                bothAbsent.countDown();
                assertThat(bothAbsent.await(10, TimeUnit.SECONDS)).isTrue();
            } else {
                assertThat(winnerCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(rollbacks.get()).isEqualTo(1);
                assertThat(commits.get()).isEqualTo(1);
                assertThat(context).isNotSameAs(failedContext.get());
                assertThat(initialContexts).doesNotContain(context);
                assertThat(failedEntityManager.get().isOpen()).isFalse();
                assertThat(result).isPresent();
            }
            return result;
        }).when(users).findByAuthProviderAndProviderUserId(identity.provider(), identity.providerUserId());
        doAnswer(call -> {
            try {
                return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);
            } catch (DataIntegrityViolationException failure) {
                ConstraintViolationException violation = constraintViolation(failure);
                assertThat(violation.getConstraintName()).isEqualTo("users.uk_users_auth_provider_provider_user_id");
                assertThat(violation.getKind()).isEqualTo(ConstraintViolationException.ConstraintKind.UNIQUE);
                assertThat(violation.getSQLState()).isEqualTo("23000");
                assertThat(violation.getErrorCode()).isEqualTo(1062);
                uniqueConflicts.incrementAndGet();
                throw failure;
            }
        }).when(users).saveAndFlush(any(User.class));

        TokenResponse first;
        TokenResponse second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstLogin = executor.submit(() -> loginService.login(identity));
            var secondLogin = executor.submit(() -> loginService.login(identity));
            first = firstLogin.get(20, TimeUnit.SECONDS);
            second = secondLogin.get(20, TimeUnit.SECONDS);
        }

        assertThat(reads.get()).isEqualTo(3);
        assertThat(initialContexts).hasSize(2);
        assertThat(rollbacks.get()).isEqualTo(1);
        assertThat(uniqueConflicts.get()).isEqualTo(1);
        assertThat(users.count()).isEqualTo(userCount + 1);
        assertThat(refreshTokens.count()).isEqualTo(sessionCount + 2);
        Long userId = jwtTokenService.extractUserId(first.accessToken());
        assertThat(jwtTokenService.extractUserId(second.accessToken())).isEqualTo(userId);
        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        assertSession(first, userId);
        assertSession(second, userId);

        TokenResponse rotatedFirst = tokenService.refresh(first.refreshToken());
        assertThatThrownBy(() -> tokenService.refresh(first.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertSession(second, userId);
        TokenResponse rotatedSecond = tokenService.refresh(second.refreshToken());
        assertThatThrownBy(() -> tokenService.refresh(second.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        tokenService.invalidate(rotatedFirst.refreshToken());
        assertThat(refreshTokens.findByTokenHash(hash(rotatedFirst.refreshToken()))).isEmpty();
        assertSession(rotatedSecond, userId);
        assertThat(jwtTokenService.extractUserId(tokenService.refresh(rotatedSecond.refreshToken()).accessToken()))
                .isEqualTo(userId);
    }

    @Test
    @DisplayName("기존 User의 동시 로그인은 INSERT 없이 기존 Session과 독립적인 두 Session을 유지한다")
    void concurrent_existing_login_keeps_sessions() throws Exception {
        ExternalIdentity identity = new ExternalIdentity(AuthProvider.GOOGLE, "concurrent-existing-login");
        TokenResponse original = loginService.login(identity);
        clearInvocations(users);
        long userCount = users.count();
        long sessionCount = refreshTokens.count();
        synchronizeReads(identity, new CountDownLatch(2), false);

        TokenResponse[] responses = concurrentLogin(identity, identity);

        Long userId = jwtTokenService.extractUserId(original.accessToken());
        assertThat(users.count()).isEqualTo(userCount);
        assertThat(refreshTokens.count()).isEqualTo(sessionCount + 2);
        assertThat(responses[0].refreshToken()).isNotEqualTo(responses[1].refreshToken());
        assertSession(original, userId);
        for (TokenResponse response : responses) {
            assertThat(jwtTokenService.extractUserId(response.accessToken())).isEqualTo(userId);
            assertSession(response, userId);
        }
        verify(users, never()).saveAndFlush(any(User.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("동시 신규 로그인은 다른 외부 ID 또는 같은 외부 ID의 다른 Provider를 각각 별도 User로 저장한다")
    void isolate_concurrent_identities(boolean differentProvider) throws Exception {
        String suffix = differentProvider ? "provider" : "external-id";
        ExternalIdentity first = new ExternalIdentity(AuthProvider.GOOGLE, "isolated-first-" + suffix);
        ExternalIdentity second = new ExternalIdentity(differentProvider ? AuthProvider.APPLE : AuthProvider.GOOGLE,
                differentProvider ? first.providerUserId() : "isolated-second-" + suffix);
        long userCount = users.count();
        long sessionCount = refreshTokens.count();
        CountDownLatch bothAbsent = new CountDownLatch(2);
        synchronizeReads(first, bothAbsent, true);
        synchronizeReads(second, bothAbsent, true);

        TokenResponse[] responses = concurrentLogin(first, second);

        Long firstId = jwtTokenService.extractUserId(responses[0].accessToken());
        Long secondId = jwtTokenService.extractUserId(responses[1].accessToken());
        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(users.count()).isEqualTo(userCount + 2);
        assertThat(refreshTokens.count()).isEqualTo(sessionCount + 2);
        assertThat(users.findById(firstId).orElseThrow().getAuthProvider()).isEqualTo(first.provider());
        assertThat(users.findById(firstId).orElseThrow().getProviderUserId()).isEqualTo(first.providerUserId());
        assertThat(users.findById(secondId).orElseThrow().getAuthProvider()).isEqualTo(second.provider());
        assertThat(users.findById(secondId).orElseThrow().getProviderUserId()).isEqualTo(second.providerUserId());
        assertSession(responses[0], firstId);
        assertSession(responses[1], secondId);
    }

    @Test
    @DisplayName("선행 INSERT의 Session 저장이 실패해 rollback되면 경쟁 요청만 자신의 User와 Session을 commit한다")
    void competing_insert_can_succeed_after_rollback() throws Exception {
        ExternalIdentity identity = new ExternalIdentity(AuthProvider.GOOGLE, "rollback-insert-race");
        long userCount = users.count();
        long sessionCount = refreshTokens.count();
        synchronizeReads(identity, new CountDownLatch(2), true);
        CountDownLatch insertsStarted = new CountDownLatch(2);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger issues = new AtomicInteger();
        AtomicReference<Long> rolledBackUserId = new AtomicReference<>();
        doAnswer(call -> {
            insertsStarted.countDown();
            try {
                return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);
            } catch (DataIntegrityViolationException failure) {
                conflicts.incrementAndGet();
                throw failure;
            }
        }).when(users).saveAndFlush(any(User.class));
        var failure = new DataIntegrityViolationException("Synthetic session persistence failure");
        doAnswer(call -> {
            String token = (String) call.callRealMethod();
            if (issues.incrementAndGet() == 1) {
                rolledBackUserId.set(((User) call.getArgument(0)).getId());
                assertThat(insertsStarted.await(10, TimeUnit.SECONDS)).isTrue();
                throw failure;
            }
            return token;
        }).when(tokenService).issue(any(User.class));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attemptLogin(identity));
            var second = executor.submit(() -> attemptLogin(identity));
            var results = java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result.response() != null).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result.failure() == failure).count()).isEqualTo(1);
            TokenResponse success = results.stream().filter(result -> result.response() != null)
                    .findFirst().orElseThrow().response();
            Long committedUserId = jwtTokenService.extractUserId(success.accessToken());
            assertThat(committedUserId).isNotEqualTo(rolledBackUserId.get());
            assertSession(success, committedUserId);
        }
        assertThat(users.count()).isEqualTo(userCount + 1);
        assertThat(refreshTokens.count()).isEqualTo(sessionCount + 1);
        assertThat(users.findById(rolledBackUserId.get())).isEmpty();
        assertThat(conflicts.get()).isZero();
    }

    @Test
    @DisplayName("Refresh Token의 실제 UNIQUE 충돌은 Identity 복구 없이 신규 User와 Session을 함께 rollback한다")
    void refresh_unique_failure_rolls_back_new_user() throws Exception {
        TokenResponse original = loginService.login(new ExternalIdentity(AuthProvider.GOOGLE, "hash-conflict-owner"));
        clearInvocations(tokenService);
        String existingHash = hash(original.refreshToken());
        ExternalIdentity identity = new ExternalIdentity(AuthProvider.GOOGLE, "hash-conflict-new-user");
        long userCount = users.count();
        long sessionCount = refreshTokens.count();
        doAnswer(call -> {
            refreshTokens.saveAndFlush(new RefreshToken(call.getArgument(0), existingHash,
                    LocalDateTime.now(ZoneOffset.UTC).plusDays(1)));
            throw new AssertionError("Expected real refresh hash UNIQUE violation");
        }).when(tokenService).issue(any(User.class));

        assertThatThrownBy(() -> loginService.login(identity)).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertThat(constraintViolation(failure).getConstraintName())
                        .isEqualTo("refresh_tokens.uk_refresh_tokens_token_hash"));

        assertThat(users.count()).isEqualTo(userCount);
        assertThat(users.findByAuthProviderAndProviderUserId(identity.provider(), identity.providerUserId())).isEmpty();
        assertThat(refreshTokens.count()).isEqualTo(sessionCount);
        assertSession(original, jwtTokenService.extractUserId(original.accessToken()));
        verify(tokenService).issue(any(User.class));
    }

    @Test
    @DisplayName("Token 생성 뒤 commit 전 오류가 나면 User와 Session을 rollback하고 성공 응답을 반환하지 않는다")
    void commit_failure_rolls_back_login() {
        ExternalIdentity identity = new ExternalIdentity(AuthProvider.GOOGLE, "commit-failure-login");
        long userCount = users.count();
        long sessionCount = refreshTokens.count();
        var failure = new IllegalStateException("Synthetic before-commit failure");
        doAnswer(call -> {
            String token = (String) call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw failure;
                }
            });
            return token;
        }).when(tokenService).issue(any(User.class));

        assertThatThrownBy(() -> loginService.login(identity)).isSameAs(failure);

        assertThat(users.count()).isEqualTo(userCount);
        assertThat(refreshTokens.count()).isEqualTo(sessionCount);
        assertThat(users.findByAuthProviderAndProviderUserId(identity.provider(), identity.providerUserId())).isEmpty();
    }

    @Test
    @DisplayName("rollback-only인 외부 Transaction은 중단하고 독립 로그인 Transaction을 commit한 뒤 원래 문맥을 복원한다")
    void suspend_rollback_only_outer_transaction() throws Exception {
        ExternalIdentity identity = new ExternalIdentity(AuthProvider.GOOGLE, "outer-rollback-only-login");
        AtomicReference<Object> outerContext = new AtomicReference<>();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory))
                    .isNotSameAs(outerContext.get());
            return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);
        }).when(users).findByAuthProviderAndProviderUserId(identity.provider(), identity.providerUserId());

        TokenResponse response = new TransactionTemplate(transactionManager).execute(status -> {
            outerContext.set(TransactionSynchronizationManager.getResource(entityManagerFactory));
            status.setRollbackOnly();
            TokenResponse tokens = loginService.login(identity);
            assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory)).isSameAs(outerContext.get());
            assertThat(status.isRollbackOnly()).isTrue();
            return tokens;
        });

        Long userId = jwtTokenService.extractUserId(response.accessToken());
        assertThat(users.findById(userId)).isPresent();
        assertSession(response, userId);
    }

    private void synchronizeReads(ExternalIdentity identity, CountDownLatch barrier, boolean absent) {
        doAnswer(call -> {
            @SuppressWarnings("unchecked")
            Optional<User> result = (Optional<User>) mockingDetails(users).getMockCreationSettings()
                    .getDefaultAnswer().answer(call);
            assertThat(result.isEmpty()).isEqualTo(absent);
            barrier.countDown();
            assertThat(barrier.await(10, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(users).findByAuthProviderAndProviderUserId(identity.provider(), identity.providerUserId());
    }

    private TokenResponse[] concurrentLogin(ExternalIdentity first, ExternalIdentity second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstLogin = executor.submit(() -> loginService.login(first));
            var secondLogin = executor.submit(() -> loginService.login(second));
            return new TokenResponse[]{firstLogin.get(20, TimeUnit.SECONDS), secondLogin.get(20, TimeUnit.SECONDS)};
        }
    }

    private LoginAttempt attemptLogin(ExternalIdentity identity) {
        try {
            return new LoginAttempt(loginService.login(identity), null);
        } catch (RuntimeException failure) {
            return new LoginAttempt(null, failure);
        }
    }

    private ConstraintViolationException constraintViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation;
            }
        }
        throw new AssertionError("Expected Hibernate constraint metadata");
    }

    private void assertSession(TokenResponse response, Long userId) throws Exception {
        assertThat(refreshTokens.findByTokenHash(hash(response.refreshToken())).orElseThrow().getUser().getId())
                .isEqualTo(userId);
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private record LoginAttempt(TokenResponse response, RuntimeException failure) {
    }
}
