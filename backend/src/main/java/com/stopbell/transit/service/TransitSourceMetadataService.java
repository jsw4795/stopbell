package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.entity.TransitSourceMetadata;
import com.stopbell.transit.repository.TransitSourceMetadataRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TransitSourceMetadataService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TransitSourceMetadataRepository repository;
    private final Clock clock;
    private final EntityManager entityManager;

    public TransitSourceMetadataService(TransitSourceMetadataRepository repository,
            @Qualifier("transitMetadataClock") Clock clock, EntityManager entityManager) {
        this.repository = repository;
        this.clock = clock;
        this.entityManager = entityManager;
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
