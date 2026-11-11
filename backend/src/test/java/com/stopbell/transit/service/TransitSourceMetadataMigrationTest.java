package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stopbell.support.SharedMySqlExtension;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("db")
@Tag("migration")
@ExtendWith(SharedMySqlExtension.class)
class TransitSourceMetadataMigrationTest {

    @Test
    void preserves_existing_route_stop_and_occurrence(SharedMySqlExtension.Database database) {
        var dataSource = new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
        Flyway.configure().dataSource(dataSource).target("32").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO bus_routes (id,provider,external_route_id,route_number) VALUES (101,'SEOUL_BUS','r','1')");
        jdbc.update("INSERT INTO bus_stops (id,provider,external_stop_id,stop_name) VALUES (102,'SEOUL_BUS','s','stop')");
        jdbc.update("INSERT INTO bus_route_stop_occurrences (id,route_id,stop_id,stop_order) VALUES (103,101,102,1)");

        assertThat(Flyway.configure().dataSource(dataSource).target("33").load().migrate().migrationsExecuted).isOne();
        assertThat(jdbc.queryForObject("SELECT route_type FROM bus_routes WHERE id=101", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE id=101", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_stops WHERE id=102", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_route_stop_occurrences WHERE id=103 AND route_id=101 AND stop_id=102", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata", Integer.class)).isZero();
    }
}
