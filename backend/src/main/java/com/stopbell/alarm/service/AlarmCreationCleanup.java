package com.stopbell.alarm.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AlarmCreationCleanup {
    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AlarmCreationCleanup(JdbcTemplate jdbc, @Qualifier("transitMetadataClock") Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(scheduler = "alarmCreationCleanupScheduler", initialDelayString = "PT1H", fixedDelayString = "PT1H")
    @Transactional
    public void cleanup() {
        jdbc.update("delete from alarm_creation_requests where expires_at <= ? order by expires_at, id limit " + BATCH_SIZE,
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
