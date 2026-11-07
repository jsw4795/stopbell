package com.stopbell.transit.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.entity.TransitSourceMetadata;
import com.stopbell.transit.repository.TransitSourceMetadataRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TransitSourceMetadataService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TransitSourceMetadataRepository repository;
    private final Clock clock;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public TransitSourceMetadataService(TransitSourceMetadataRepository repository,
            @Qualifier("transitMetadataClock") Clock clock, EntityManager entityManager, JdbcTemplate jdbc) {
        this.repository = repository;
        this.clock = clock;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Transactional
    public void saveAll(List<SourceRecord> records) {
        Objects.requireNonNull(records, "Records must not be null");
        Instant now = clock.instant();
        for (SourceRecord record : records) {
            Objects.requireNonNull(record, "Record must not be null");
            repository.upsert(record.sourceSystem().name(), record.dataKind().name(), record.sourceKey(),
                    record.dataKind() == TransitSourceDataKind.ROUTE ? record.entityId() : null,
                    record.dataKind() == TransitSourceDataKind.STOP ? record.entityId() : null,
                    record.dataKind() == TransitSourceDataKind.OCCURRENCE ? record.entityId() : null,
                    record.rawPayload(), record.sourceVersion(), record.collectedAt(), now);
        }
    }

    /** Called inside the route transaction after the normalized entities have been flushed. */
    @Transactional
    public void saveTagoLinkedBatch(List<SourceRecord> records) {
        if (records.isEmpty()) throw new IllegalArgumentException("TAGO source batch must not be empty");
        for (SourceRecord record : records) {
            if (record.sourceSystem() != TransitSourceSystem.TAGO || record.entityId() == null
                    || (record.dataKind() != TransitSourceDataKind.ROUTE && record.dataKind() != TransitSourceDataKind.OCCURRENCE)) {
                throw new IllegalArgumentException("Expected linked TAGO source records");
            }
        }
        Instant now = clock.instant();
        jdbc.batchUpdate("""
                INSERT INTO transit_source_metadata
                    (source_system, data_kind, source_key, route_id, stop_id, occurrence_id,
                     raw_payload, source_version, collected_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, ?, CAST(? AS JSON), ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE
                    updated_at = IF(transit_source_metadata.raw_payload <=> incoming.raw_payload
                        AND transit_source_metadata.source_version <=> incoming.source_version
                        AND transit_source_metadata.collected_at <=> incoming.collected_at
                        AND transit_source_metadata.route_id <=> incoming.route_id
                        AND transit_source_metadata.stop_id <=> incoming.stop_id
                        AND transit_source_metadata.occurrence_id <=> incoming.occurrence_id,
                        transit_source_metadata.updated_at, incoming.updated_at),
                    route_id = incoming.route_id, stop_id = incoming.stop_id,
                    occurrence_id = incoming.occurrence_id, raw_payload = incoming.raw_payload,
                    source_version = incoming.source_version, collected_at = incoming.collected_at
                """, records, records.size(), (statement, record) -> {
                    statement.setString(1, record.sourceSystem().name());
                    statement.setString(2, record.dataKind().name());
                    statement.setString(3, record.sourceKey());
                    if (record.dataKind() == TransitSourceDataKind.ROUTE) statement.setLong(4, record.entityId());
                    else statement.setNull(4, java.sql.Types.BIGINT);
                    if (record.dataKind() == TransitSourceDataKind.OCCURRENCE) statement.setLong(5, record.entityId());
                    else statement.setNull(5, java.sql.Types.BIGINT);
                    statement.setString(6, record.rawPayload());
                    statement.setString(7, record.sourceVersion());
                    statement.setTimestamp(8, Timestamp.from(record.collectedAt()));
                    statement.setTimestamp(9, Timestamp.from(now));
                });
    }

    /** Each GBIS batch is committed independently and replaces stale associations. */
    @Transactional
    public void saveGbisBatch(List<SourceRecord> records) {
        if (records.isEmpty()) throw new IllegalArgumentException("GBIS source batch must not be empty");
        for (SourceRecord record : records) {
            if (record.sourceSystem() != TransitSourceSystem.GBIS
                    || (record.dataKind() != TransitSourceDataKind.ROUTE && record.dataKind() != TransitSourceDataKind.OCCURRENCE)) {
                throw new IllegalArgumentException("Expected GBIS source records");
            }
        }
        Instant now = clock.instant();
        jdbc.batchUpdate("""
                INSERT INTO transit_source_metadata
                    (source_system, data_kind, source_key, route_id, stop_id, occurrence_id,
                     raw_payload, source_version, collected_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, ?, CAST(? AS JSON), ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE
                    updated_at = IF(transit_source_metadata.raw_payload <=> incoming.raw_payload
                        AND transit_source_metadata.source_version <=> incoming.source_version
                        AND transit_source_metadata.collected_at <=> incoming.collected_at
                        AND transit_source_metadata.route_id <=> incoming.route_id
                        AND transit_source_metadata.stop_id <=> incoming.stop_id
                        AND transit_source_metadata.occurrence_id <=> incoming.occurrence_id,
                        transit_source_metadata.updated_at, incoming.updated_at),
                    route_id = incoming.route_id, stop_id = incoming.stop_id,
                    occurrence_id = incoming.occurrence_id, raw_payload = incoming.raw_payload,
                    source_version = incoming.source_version, collected_at = incoming.collected_at
                """, records, records.size(), (statement, record) -> {
                    statement.setString(1, record.sourceSystem().name());
                    statement.setString(2, record.dataKind().name());
                    statement.setString(3, record.sourceKey());
                    if (record.dataKind() == TransitSourceDataKind.ROUTE && record.entityId() != null) statement.setLong(4, record.entityId());
                    else statement.setNull(4, java.sql.Types.BIGINT);
                    if (record.dataKind() == TransitSourceDataKind.OCCURRENCE && record.entityId() != null) statement.setLong(5, record.entityId());
                    else statement.setNull(5, java.sql.Types.BIGINT);
                    statement.setString(6, record.rawPayload());
                    statement.setString(7, record.sourceVersion());
                    statement.setTimestamp(8, Timestamp.from(record.collectedAt()));
                    statement.setTimestamp(9, Timestamp.from(now));
                });
    }

    /** Unlinked Seoul rows retain any existing association for the same source identity. */
    @Transactional
    public void saveUnlinkedBatch(List<SourceRecord> records) {
        Instant now = clock.instant();
        for (SourceRecord record : records) {
            if (record.sourceSystem() != TransitSourceSystem.SEOUL_TDATA || record.entityId() != null) {
                throw new IllegalArgumentException("Expected unlinked Seoul source records");
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO transit_source_metadata
                    (source_system, data_kind, source_key, raw_payload, source_version, collected_at, updated_at)
                VALUES (?, ?, ?, CAST(? AS JSON), ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE
                    updated_at = IF(transit_source_metadata.raw_payload <=> incoming.raw_payload
                        AND transit_source_metadata.source_version <=> incoming.source_version
                        AND transit_source_metadata.collected_at <=> incoming.collected_at,
                        transit_source_metadata.updated_at, incoming.updated_at),
                    raw_payload = incoming.raw_payload, source_version = incoming.source_version,
                    collected_at = incoming.collected_at
                """, records, records.size(), (statement, record) -> {
                    statement.setString(1, record.sourceSystem().name());
                    statement.setString(2, record.dataKind().name());
                    statement.setString(3, record.sourceKey());
                    statement.setString(4, record.rawPayload());
                    statement.setString(5, record.sourceVersion());
                    statement.setTimestamp(6, Timestamp.from(record.collectedAt()));
                    statement.setTimestamp(7, Timestamp.from(now));
                });
    }

    /** Reads IDs only after the corresponding TAGO route transaction has committed. */
    @Transactional(readOnly = true)
    public TagoEntityIds findTagoEntityIds(String externalRouteId) {
        List<Long> routeIds = jdbc.queryForList("SELECT id FROM bus_routes WHERE provider = 'TAGO' AND external_route_id = ?",
                Long.class, externalRouteId);
        if (routeIds.size() != 1) throw new IllegalStateException("Missing reconciled TAGO route");
        Map<Integer, Long> occurrences = new HashMap<>();
        jdbc.query("SELECT stop_order, id FROM bus_route_stop_occurrences WHERE route_id = ?", rs -> {
            if (occurrences.put(rs.getInt(1), rs.getLong(2)) != null) {
                throw new IllegalStateException("Duplicate reconciled TAGO occurrence order");
            }
        }, routeIds.getFirst());
        return new TagoEntityIds(routeIds.getFirst(), occurrences);
    }

    public record TagoEntityIds(Long routeId, Map<Integer, Long> occurrences) { }

    @Transactional(readOnly = true)
    public Optional<TransitSourceMetadata> find(TransitSourceSystem source, TransitSourceDataKind kind, String key) {
        Optional<TransitSourceMetadata> result = repository.findBySourceSystemAndDataKindAndSourceKey(source, kind, key);
        result.ifPresent(entityManager::refresh);
        return result;
    }

    public record SourceRecord(TransitSourceSystem sourceSystem, TransitSourceDataKind dataKind, String sourceKey,
            Long entityId, String rawPayload, String sourceVersion, Instant collectedAt) {
        public SourceRecord {
            Objects.requireNonNull(sourceSystem, "Source must not be null");
            Objects.requireNonNull(dataKind, "Data kind must not be null");
            Objects.requireNonNull(collectedAt, "Collection time must not be null");
            if (sourceKey == null || sourceKey.isBlank() || !sourceKey.equals(sourceKey.strip()) || sourceKey.length() > 255) {
                throw new IllegalArgumentException("Source key must be between 1 and 255 characters");
            }
            if (entityId != null && entityId <= 0) {
                throw new IllegalArgumentException("Entity ID must be positive");
            }
            if (sourceVersion != null && sourceVersion.length() > 100) {
                throw new IllegalArgumentException("Source version exceeds 100 characters");
            }
            if (rawPayload == null || !JSON.readTree(rawPayload).isObject()) {
                throw new IllegalArgumentException("Source payload must be a JSON object");
            }
        }
    }
}
