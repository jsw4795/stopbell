package com.stopbell.transit.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.entity.TransitSourceMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransitSourceMetadataRepository extends JpaRepository<TransitSourceMetadata, Long> {

    Optional<TransitSourceMetadata> findBySourceSystemAndDataKindAndSourceKey(
            TransitSourceSystem sourceSystem, TransitSourceDataKind dataKind, String sourceKey);

    List<TransitSourceMetadata> findAllBySourceSystemAndDataKindAndSourceKeyIn(
            TransitSourceSystem sourceSystem, TransitSourceDataKind dataKind, List<String> sourceKeys);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO transit_source_metadata
                (source_system, data_kind, source_key, route_id, stop_id, occurrence_id,
                 raw_payload, source_version, collected_at, updated_at)
            VALUES (:source, :kind, :sourceKey, :routeId, :stopId, :occurrenceId,
                    CAST(:payload AS JSON), :version, :collectedAt, :updatedAt) AS incoming
            ON DUPLICATE KEY UPDATE
                updated_at = IF(transit_source_metadata.raw_payload <=> incoming.raw_payload
                    AND transit_source_metadata.source_version <=> incoming.source_version
                    AND transit_source_metadata.collected_at <=> incoming.collected_at
                    AND transit_source_metadata.route_id <=> incoming.route_id AND transit_source_metadata.stop_id <=> incoming.stop_id
                    AND transit_source_metadata.occurrence_id <=> incoming.occurrence_id, transit_source_metadata.updated_at, incoming.updated_at),
                route_id = incoming.route_id, stop_id = incoming.stop_id,
                occurrence_id = incoming.occurrence_id, raw_payload = incoming.raw_payload,
                source_version = incoming.source_version, collected_at = incoming.collected_at
            """, nativeQuery = true)
    void upsert(@Param("source") String source, @Param("kind") String kind,
            @Param("sourceKey") String sourceKey, @Param("routeId") Long routeId,
            @Param("stopId") Long stopId, @Param("occurrenceId") Long occurrenceId,
            @Param("payload") String payload, @Param("version") String version,
            @Param("collectedAt") Instant collectedAt, @Param("updatedAt") Instant updatedAt);
}
