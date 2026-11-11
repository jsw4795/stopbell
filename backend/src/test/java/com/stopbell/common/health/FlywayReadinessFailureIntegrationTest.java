package com.stopbell.common.health;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.stopbell.StopBellApplication;
import com.stopbell.support.SharedMySqlExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

@Tag("db")
@Tag("migration")
@ExtendWith(SharedMySqlExtension.class)
class FlywayReadinessFailureIntegrationTest {
    private SharedMySqlExtension.Database mysql;

    @BeforeEach
    void connect_to_isolated_database(SharedMySqlExtension.Database database) {
        mysql = database;
    }

    @Test
    void failed_migration_prevents_application_startup() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(StopBellApplication.class)
                .web(WebApplicationType.NONE).profiles("test")
                .properties(Map.of(
                        "spring.datasource.url", mysql.getJdbcUrl(),
                        "spring.datasource.username", mysql.getUsername(),
                        "spring.datasource.password", mysql.getPassword(),
                        "spring.flyway.locations", "classpath:health-failed-migration",
                        "transit.monitoring.enabled", "false",
                        "notification.dispatch.enabled", "false"))
                .run()).hasStackTraceContaining("Flyway");
    }
}
