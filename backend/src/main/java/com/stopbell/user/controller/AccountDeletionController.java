package com.stopbell.user.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ApiErrorResponse;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.service.AccountWithdrawalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountDeletionController {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionController.class);
    private final AccountWithdrawalService withdrawal;
    private final JwtTokenService tokens;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccountDeletionController(AccountWithdrawalService withdrawal, JwtTokenService tokens, JdbcTemplate jdbc, Clock jwtClock) {
        this.withdrawal = withdrawal;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.clock = jwtClock;
    }

    @DeleteMapping("/api/v1/users/me")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Account-Deletion-Receipt", required = false) String receipt) {
        var result = withdrawal.withdraw(tokens.extractUserId(jwt), receipt == null ? null : hash(receipt));
        return switch (result) {
            case DELETED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            case DELETED_MANUAL_APPLE_REVOCATION_REQUIRED -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new DeletionResponse("DELETED", "MANUAL_REVOCATION_REQUIRED"));
            case PENDING -> ResponseEntity.accepted().cacheControl(CacheControl.noStore()).header("Retry-After", "60")
                    .body(new DeletionResponse("PENDING", "UNCONFIRMED"));
        };
    }

    @PostMapping("/api/v1/account-deletion/status")
    public ResponseEntity<StatusResponse> status(
            @RequestHeader(value = "X-Account-Deletion-Receipt", required = false) String receipt) {
        String digest = hash(receipt);
        var states = jdbc.queryForList("""
                select state from account_deletion_receipts where receipt_hash=?
                and (completed_at is null or completed_at > ?)
                """, String.class, digest, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(30));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new StatusResponse(states.isEmpty() ? "UNKNOWN" : states.getFirst()));
    }

    private String hash(String receipt) {
        if (receipt == null || !receipt.matches("[A-Za-z0-9_-]{43}")) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(receipt.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record StatusResponse(String status) { }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiErrorResponse> databaseFailure(RuntimeException failure) {
        log.warn("Account deletion database operation failed: {}", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR.name(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }

    public record DeletionResponse(String status, String appleRevocation) { }
}
