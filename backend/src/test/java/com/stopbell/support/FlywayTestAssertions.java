package com.stopbell.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.CoreMigrationType;

public final class FlywayTestAssertions {
    private FlywayTestAssertions() {
    }

    // Use an unrestricted, latest-target Flyway instance, not a historical target.
    public static void migrateToLatest(Flyway flyway) {
        MigrationInfo[] pending = flyway.info().pending();
        int executed = flyway.migrate().migrationsExecuted;
        // The count is unambiguous for the project's versioned SQL migrations.
        if (Arrays.stream(pending).allMatch(info -> info.getVersion() != null && info.getType() == CoreMigrationType.SQL)) {
            assertThat(executed).isEqualTo(pending.length);
        }
        assertLatest(flyway);
    }

    public static void assertLatest(Flyway flyway) {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        var info = flyway.info();
        assertThat(info.pending()).isEmpty();
        assertThat(info.all()).isNotEmpty();
        assertThat(info.all()).allSatisfy(migration -> assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS));
        var latest = Arrays.stream(info.all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version != null)
                .max(Comparable::compareTo).orElseThrow();
        assertThat(info.current()).isNotNull();
        assertThat(info.current().getVersion()).isEqualTo(latest);
    }
}
