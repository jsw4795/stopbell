package com.stopbell.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;
import java.util.stream.Stream;

import com.stopbell.user.auth.dto.TokenResponse;
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

class LoginServiceTest {

    private static final String IDENTITY_CONSTRAINT = "uk_users_auth_provider_provider_user_id";
    private static final ExternalIdentity IDENTITY = new ExternalIdentity(AuthProvider.GOOGLE, "unit-identity");

    private final UserRepository users = mock(UserRepository.class);
    private final JwtTokenService jwt = mock(JwtTokenService.class);
    private final RefreshTokenService refresh = mock(RefreshTokenService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus first = new SimpleTransactionStatus();
    private final SimpleTransactionStatus recovery = new SimpleTransactionStatus();
    private final User user = persistedUser(AuthProvider.GOOGLE, IDENTITY.providerUserId(), 17L);
    private LoginService login;

    @BeforeEach
    void set_up() {
        when(manager.getTransaction(any())).thenReturn(first, recovery);
        login = new LoginService(users, jwt, refresh, manager);
    }

    @Test
    @DisplayName("신규 로그인은 User 생성과 Token 발급을 완료한 뒤 commit한다")
    void create_user_and_commit_tokens() {
        when(find()).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenReturn(user);
        when(jwt.createAccessToken(user.getId())).thenReturn("unit-access");
        when(refresh.issue(user)).thenReturn("unit-refresh");

        assertThat(login.login(IDENTITY)).isEqualTo(new TokenResponse("unit-access", "unit-refresh"));

        var order = inOrder(users, jwt, refresh, manager);
        order.verify(manager).getTransaction(any());
        order.verify(users).findByAuthProviderAndProviderUserId(IDENTITY.provider(), IDENTITY.providerUserId());
        order.verify(users).saveAndFlush(any(User.class));
        order.verify(jwt).createAccessToken(user.getId());
        order.verify(refresh).issue(user);
        order.verify(manager).commit(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {IDENTITY_CONSTRAINT, "users." + IDENTITY_CONSTRAINT})
    @DisplayName("Identity UNIQUE 충돌은 rollback 완료 뒤 새 Transaction에서 정확한 User를 확인하고 한 번 복구한다")
    void recover_only_after_rollback(String constraint) {
        when(find()).thenReturn(Optional.empty(), Optional.of(user));
        when(users.saveAndFlush(any(User.class))).thenThrow(violation(constraint, ConstraintKind.UNIQUE));

        login.login(IDENTITY);

        var order = inOrder(users, jwt, refresh, manager);
        order.verify(manager).getTransaction(any());
        order.verify(users).findByAuthProviderAndProviderUserId(IDENTITY.provider(), IDENTITY.providerUserId());
        order.verify(users).saveAndFlush(any(User.class));
        order.verify(manager).rollback(first);
        order.verify(manager).getTransaction(any());
        order.verify(users).findByAuthProviderAndProviderUserId(IDENTITY.provider(), IDENTITY.providerUserId());
        order.verify(jwt).createAccessToken(user.getId());
        order.verify(refresh).issue(user);
        order.verify(manager).commit(recovery);
        verify(manager, times(2)).getTransaction(org.mockito.ArgumentMatchers.argThat(
                definition -> definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"uk_refresh_tokens_token_hash", "other_unique", "other_table." + IDENTITY_CONSTRAINT})
    @DisplayName("식별되지 않거나 다른 UNIQUE 제약 위반은 재조회나 Token 발급 없이 전파한다")
    void reject_other_constraints(String constraint) {
        assertCreationFailure(violation(constraint, ConstraintKind.UNIQUE));
    }

    @ParameterizedTest
    @MethodSource("creationFailures")
    @DisplayName("연결 실패와 Timeout 및 UNIQUE가 아닌 INSERT 오류는 복구하지 않는다")
    void propagate_other_insert_failures(RuntimeException failure) {
        assertCreationFailure(failure);
    }

    static Stream<RuntimeException> creationFailures() {
        return Stream.of(
                new DataAccessResourceFailureException("Synthetic connection failure"),
                new QueryTimeoutException("Synthetic timeout"),
                new DataIntegrityViolationException("Unidentified integrity failure"),
                violation(IDENTITY_CONSTRAINT, ConstraintKind.OTHER),
                new IllegalStateException("Unexpected persistence failure")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidRecoveryUsers")
    @DisplayName("복구 조회의 User가 없거나 Provider와 외부 ID 및 내부 ID가 유효하지 않으면 원래 오류로 실패한다")
    void reject_missing_or_mismatched_recovery_user(Optional<User> result) {
        var failure = violation(IDENTITY_CONSTRAINT, ConstraintKind.UNIQUE);
        when(find()).thenReturn(Optional.empty(), result);
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThatThrownBy(() -> login.login(IDENTITY)).isSameAs(failure);

        verify(users, times(2)).findByAuthProviderAndProviderUserId(IDENTITY.provider(), IDENTITY.providerUserId());
        verify(users).saveAndFlush(any(User.class));
        verify(manager).rollback(first);
        verify(manager).rollback(recovery);
        verifyNoInteractions(jwt, refresh);
    }

    static Stream<Arguments> invalidRecoveryUsers() {
        return Stream.of(
                Optional.<User>empty(),
                Optional.of(persistedUser(AuthProvider.APPLE, IDENTITY.providerUserId(), 18L)),
                Optional.of(persistedUser(AuthProvider.GOOGLE, "other-identity", 19L)),
                Optional.of(persistedUser(AuthProvider.GOOGLE, IDENTITY.providerUserId(), null)),
                Optional.of(persistedUser(AuthProvider.GOOGLE, IDENTITY.providerUserId(), 0L)),
                Optional.of(persistedUser(AuthProvider.GOOGLE, IDENTITY.providerUserId(), -1L))
        ).map(Arguments::of);
    }

    @Test
    @DisplayName("복구 조회 자체의 DB 오류는 추가 재시도 없이 전파한다")
    void propagate_recovery_read_failure() {
        var failure = new DataAccessResourceFailureException("Synthetic recovery connection failure");
        when(find()).thenReturn(Optional.empty()).thenThrow(failure);
        when(users.saveAndFlush(any(User.class))).thenThrow(violation(IDENTITY_CONSTRAINT, ConstraintKind.UNIQUE));

        assertThatThrownBy(() -> login.login(IDENTITY)).isSameAs(failure);

        verify(manager, times(2)).getTransaction(any());
        verify(users).saveAndFlush(any(User.class));
        verifyNoInteractions(jwt, refresh);
    }

    @Test
    @DisplayName("User 조회 단계의 오류는 Identity INSERT 충돌로 오인하지 않는다")
    void propagate_initial_read_failure() {
        var failure = violation(IDENTITY_CONSTRAINT, ConstraintKind.UNIQUE);
        when(find()).thenThrow(failure);

        assertThatThrownBy(() -> login.login(IDENTITY)).isSameAs(failure);

        verify(users, never()).saveAndFlush(any(User.class));
        verify(manager).rollback(first);
        verify(manager).getTransaction(any());
        verifyNoInteractions(jwt, refresh);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("최초 시도와 경쟁 복구의 Refresh Token 저장 실패는 재시도나 성공 응답 없이 전파한다")
    void propagate_refresh_failure(boolean recovering) {
        var failure = violation(IDENTITY_CONSTRAINT, ConstraintKind.UNIQUE);
        if (recovering) {
            when(find()).thenReturn(Optional.empty(), Optional.of(user));
            when(users.saveAndFlush(any(User.class))).thenThrow(violation(IDENTITY_CONSTRAINT, ConstraintKind.UNIQUE));
        } else {
            when(find()).thenReturn(Optional.of(user));
        }
        when(refresh.issue(user)).thenThrow(failure);

        assertThatThrownBy(() -> login.login(IDENTITY)).isSameAs(failure);

        verify(users, times(recovering ? 2 : 1))
                .findByAuthProviderAndProviderUserId(IDENTITY.provider(), IDENTITY.providerUserId());
        verify(refresh).issue(user);
        verify(manager).rollback(recovering ? recovery : first);
        verify(manager, never()).commit(any());
    }

    @Test
    @DisplayName("commit 실패 시 생성한 TokenResponse를 반환하거나 Identity 복구를 시도하지 않는다")
    void propagate_commit_failure() {
        var failure = new TransactionSystemException("Synthetic commit failure");
        when(find()).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(failure).when(manager).commit(first);

        assertThatThrownBy(() -> login.login(IDENTITY)).isSameAs(failure);

        verify(manager).getTransaction(any());
        verify(users).findByAuthProviderAndProviderUserId(IDENTITY.provider(), IDENTITY.providerUserId());
        verify(refresh).issue(user);
    }

    private void assertCreationFailure(RuntimeException failure) {
        when(find()).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThatThrownBy(() -> login.login(IDENTITY)).isSameAs(failure);

        verify(users).findByAuthProviderAndProviderUserId(IDENTITY.provider(), IDENTITY.providerUserId());
        verify(manager).getTransaction(any());
        verify(manager).rollback(first);
        verifyNoInteractions(jwt, refresh);
    }

    private Optional<User> find() {
        return users.findByAuthProviderAndProviderUserId(IDENTITY.provider(), IDENTITY.providerUserId());
    }

    private static User persistedUser(AuthProvider provider, String providerUserId, Long id) {
        User user = new User(provider, providerUserId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static DataIntegrityViolationException violation(String name, ConstraintKind kind) {
        return new DataIntegrityViolationException("Synthetic integrity failure",
                new ConstraintViolationException("Synthetic constraint failure",
                        new SQLException("Synthetic duplicate", "23000", 1062), kind, name));
    }
}
