package com.stopbell.user.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A bounded restart-safe retry, including when the initiating Access Token has expired. */
@Component
public class AccountDeletionRecovery {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionRecovery.class);
    private final JdbcTemplate jdbc;
    private final AccountWithdrawalService withdrawal;
    private final Clock clock;
    private final boolean enabled;

    public AccountDeletionRecovery(JdbcTemplate jdbc, AccountWithdrawalService withdrawal, Clock jwtClock,
            @Value("${account.deletion.recovery-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.withdrawal = withdrawal;
        this.clock = jwtClock;
        this.enabled = enabled;
    }

    @Scheduled(scheduler = "accountDeletionScheduler", initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void recover() {
        if (!enabled) return;
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (long id : jdbc.queryForList("""
                select user_id from account_deletions where next_attempt_at <= ?
                order by next_attempt_at,user_id limit 10
                """, Long.class, now)) {
            // Schedule before processing so a persistent DB failure on one account cannot starve others.
            try {
                jdbc.update("update account_deletions set next_attempt_at=? where user_id=?", now.plusMinutes(1), id);
                withdrawal.withdraw(id);
            } catch (RuntimeException failure) {
                // No exception message/cause/stack, upstream payload or user identifiers.
                log.warn("Account deletion recovery deferred: {}", failure.getClass().getSimpleName());
            }
        }
    }
}
