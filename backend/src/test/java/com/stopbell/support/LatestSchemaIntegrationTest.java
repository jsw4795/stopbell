package com.stopbell.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

/** Only stateless, compatible tests; schema mutation and failure injection keep private databases. */
@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = LatestSchemaIntegrationTest.ContextOwner.class)
@Tag("db")
@Tag("shared-db")
@Sql(scripts = "/reset-latest-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS,
        config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
@Sql(scripts = "/reset-latest-schema.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS,
        config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
public abstract class LatestSchemaIntegrationTest {
    @DynamicPropertySource
    static void latestDatabase(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "shared_latest");
    }

    @BeforeAll
    static void verifyLatestSchemaAndCleanup(@Autowired Flyway flyway, @Autowired JdbcTemplate jdbc)
            throws IOException {
        FlywayTestAssertions.assertLatest(flyway);
        var script = new ClassPathResource("reset-latest-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        var clearedTables = Pattern.compile("DELETE FROM (\\w+)").matcher(script).results()
                .map(match -> match.group(1)).toList();
        var tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema=database() and table_type='BASE TABLE'
                  and table_name <> 'flyway_schema_history'
                """, String.class);
        assertThat(clearedTables).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(tables);
    }

    public static class ContextOwner implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            // Register before refresh so failed setup also releases pools at the run boundary.
            SharedMySqlExtension.retainContext(context);
        }
    }
}
