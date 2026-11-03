package com.stopbell.user.controller;

import com.stopbell.common.error.ApiErrorResponse;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.service.AccountWithdrawalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountDeletionController {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionController.class);
    private final AccountWithdrawalService withdrawal;
    private final JwtTokenService tokens;

    public AccountDeletionController(AccountWithdrawalService withdrawal, JwtTokenService tokens) {
        this.withdrawal = withdrawal;
        this.tokens = tokens;
    }

    @DeleteMapping("/api/v1/users/me")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt) {
        var result = withdrawal.withdraw(tokens.extractUserId(jwt));
        return switch (result) {
            case DELETED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            case DELETED_MANUAL_APPLE_REVOCATION_REQUIRED -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new DeletionResponse("DELETED", "MANUAL_REVOCATION_REQUIRED"));
            case PENDING -> ResponseEntity.accepted().cacheControl(CacheControl.noStore()).header("Retry-After", "60")
                    .body(new DeletionResponse("PENDING", "UNCONFIRMED"));
        };
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiErrorResponse> databaseFailure(RuntimeException failure) {
        log.warn("Account deletion database operation failed: {}", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR.name(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }

    public record DeletionResponse(String status, String appleRevocation) { }
}
