package com.stopbell.user.repository;

import java.util.List;
import java.util.Optional;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(Long id);

    @Query(value = "select user_id from account_deletions where user_id = :id for update", nativeQuery = true)
    List<Long> findDeletionForUpdate(Long id);

    @Query(value = "select exists(select 1 from account_deletions where user_id = :id)", nativeQuery = true)
    long deletionExists(Long id);

    /** Call after acquiring the User lock. A current read also works after a REPEATABLE READ snapshot. */
    default void requireActive(Long id) {
        if (!findDeletionForUpdate(id).isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
        }
    }

    Optional<User> findByAuthProviderAndProviderUserId(AuthProvider authProvider, String providerUserId);
}
