package com.stopbell.common.health;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.stopbell.StopBellApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class FlywayReadinessFailureIntegrationTest {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

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
