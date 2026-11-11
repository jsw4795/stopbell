package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.*;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

@Tag("db")
@Tag("isolated-db")
@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"transit.monitoring.enabled=false", "notification.dispatch.enabled=false", "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransitMetadataArchiveIntegrationTest {
    static final Path ROOT;
    static {
        try { ROOT = Files.createTempDirectory("stopbell-archive-test").toRealPath(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @AfterAll static void cleanup() throws Exception {
        try (var files = Files.list(ROOT)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
        Files.deleteIfExists(ROOT);
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "metadata_archive");
        registry.add("transit.metadata.archive.directory", ROOT::toString);
    }
    @Autowired TransitMetadataArchiveService archives;
    @Autowired TransitMetadataCollectionHistoryService runs;
    @Autowired JdbcTemplate jdbc;

    @Test void stores_verifies_retries_and_detects_damage() throws Exception {
        long run = runs.start(TransitProvider.TAGO).getId();
        byte[] bytes = {0, 1, -1, 10};
        var a = archives.store(run, TransitSourceSystem.GBIS, "route-1", bytes);
        assertThat(Files.readAllBytes(ROOT.resolve(a.fileName()))).isEqualTo(bytes);
        assertThat(a.sha256()).isEqualTo("a95803d91f2a2eb57d42e2a799bb0d42e2acc37c22aa9d433a4173e5db794f28");
        assertThat(a.sizeBytes()).isEqualTo(4);
        assertThat(archives.find(a.id())).isEqualTo(a);
        archives.verify(a.id());
        assertThat(archives.store(run, TransitSourceSystem.GBIS, "route-1", bytes).id()).isEqualTo(a.id());
        assertThatThrownBy(() -> archives.store(run, TransitSourceSystem.GBIS, "route-1", new byte[]{1})).isInstanceOf(IllegalStateException.class);
        assertThat(archives.store(run, TransitSourceSystem.TAGO, "route-1", bytes).id()).isNotEqualTo(a.id());
        assertThatThrownBy(() -> archives.store(run, TransitSourceSystem.SEOUL_TDATA, "x", bytes)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> archives.store(run, TransitSourceSystem.GBIS, "../escape", bytes)).isInstanceOf(IllegalArgumentException.class);
        Files.write(ROOT.resolve(a.fileName()), new byte[]{1});
        assertThatThrownBy(() -> archives.verify(a.id())).isInstanceOf(IllegalStateException.class);
        Files.delete(ROOT.resolve(a.fileName()));
        assertThatThrownBy(() -> archives.verify(a.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void database_failure_leaves_recoverable_file() {
        long run = runs.start(TransitProvider.SEOUL_BUS).getId();
        jdbc.execute("CREATE TRIGGER archive_test_failure BEFORE INSERT ON transit_metadata_archives FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'failure'");
        try {
            assertThatThrownBy(() -> archives.store(run, TransitSourceSystem.SEOUL_TDATA, "routes", new byte[]{5})).isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("DROP TRIGGER archive_test_failure"); }
        assertThatThrownBy(() -> archives.store(run, TransitSourceSystem.SEOUL_TDATA, "routes", new byte[]{6})).isInstanceOf(IllegalStateException.class);
        var archive = archives.store(run, TransitSourceSystem.SEOUL_TDATA, "routes", new byte[]{5});
        archives.verify(archive.id());
    }

    @Test void refuses_existing_symlink_without_touching_target(@TempDir Path temp) throws Exception {
        long run = runs.start(TransitProvider.TAGO).getId();
        String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("route".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String name = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((run + ":TAGO:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path outside = temp.resolve("outside");
        Files.write(outside, new byte[]{9});
        Path link = ROOT.resolve(name);
        Files.createSymbolicLink(link, outside);
        assertThatThrownBy(() -> archives.store(run, TransitSourceSystem.TAGO, "route", new byte[]{1}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(outside)).containsExactly(9);
        Files.delete(link);
    }

    @Test void unavailable_storage_fails(@TempDir Path temp) throws Exception {
        var service = new TransitMetadataArchiveService(jdbc, java.time.Clock.systemUTC(), temp.resolve("missing").toString(),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        long run = runs.start(TransitProvider.TAGO).getId();
        assertThatThrownBy(() -> service.store(run, TransitSourceSystem.TAGO, "route", new byte[]{1})).isInstanceOf(IllegalStateException.class);
        var relative = new TransitMetadataArchiveService(jdbc, java.time.Clock.systemUTC(), "../escape",
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatThrownBy(() -> relative.store(run, TransitSourceSystem.TAGO, "route", new byte[]{1})).isInstanceOf(IllegalStateException.class);
    }
}
