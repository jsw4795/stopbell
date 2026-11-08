package com.stopbell.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class FlywayLatestVersionGuardTest {
    // Deliberately narrow: direct current-version or history MAX comparisons in one statement.
    private static final Pattern DIRECT_CURRENT = Pattern.compile(
            "(?s)\\.info\\s*\\(\\s*\\)\\s*\\.current\\s*\\(\\s*\\).*?\\.getVersion\\s*\\(\\s*\\)[^;]*?\\.isEqualTo\\s*\\(\\s*\"?\\d+\"?\\s*\\)");
    private static final Pattern HISTORY_MAX = Pattern.compile(
            "(?is)max\\s*\\([^;]*?version[^;]*?flyway_schema_history[^;]*?\\.isEqualTo\\s*\\(\\s*\"?\\d+\"?\\s*\\)");

    @Test
    void testSourcesDoNotCompareLatestVersionToLiteral() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src/test/java"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(hasForbiddenComparison(Files.readString(path))).as(path.toString()).isFalse();
            }
        }
    }

    @Test
    void detectsDirectComparisonsWithoutBlockingHistoricalTargets() {
        assertThat(hasForbiddenComparison("assertThat(f.info().current().getVersion().getVersion()" + ").isEqualTo(\"38\");")).isTrue();
        assertThat(hasForbiddenComparison("assertThat(jdbc.queryForObject(\"select max(cast(version as unsigned)) from flyway_schema_" + "history\", Integer.class)).isEqualTo(38);")).isTrue();
        assertThat(hasForbiddenComparison("assertThat(Flyway.configure().target(\"38\").load().migrate().migrationsExecuted).isEqualTo(1);")).isFalse();
        assertThat(hasForbiddenComparison("assertThat(count).isEqualTo(38);")).isFalse();
        assertThat(hasForbiddenComparison("// flyway-historical-target\nassertThat(f.info().current().getVersion()" + ").isEqualTo(\"17\");")).isFalse();
    }

    private static boolean hasForbiddenComparison(String source) {
        for (String statement : source.split(";")) {
            if (!statement.contains("// flyway-historical-target")
                    && (DIRECT_CURRENT.matcher(statement).find() || HISTORY_MAX.matcher(statement).find())) {
                return true;
            }
        }
        return false;
    }
}
