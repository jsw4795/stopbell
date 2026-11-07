package com.stopbell.transit.entity;

import java.time.Instant;

import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "transit_source_metadata")
public class TransitSourceMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 20)
    private TransitSourceSystem sourceSystem;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_kind", nullable = false, length = 24)
    private TransitSourceDataKind dataKind;

    @Column(name = "source_key", nullable = false, length = 255)
    private String sourceKey;

    @Column(name = "route_id")
    private Long routeId;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(name = "occurrence_id")
    private Long occurrenceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(name = "source_version", length = 100)
    private String sourceVersion;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TransitSourceMetadata() {
    }

    public Long getId() { return id; }
    public TransitSourceSystem getSourceSystem() { return sourceSystem; }
    public TransitSourceDataKind getDataKind() { return dataKind; }
    public String getSourceKey() { return sourceKey; }
    public Long getRouteId() { return routeId; }
    public Long getStopId() { return stopId; }
    public Long getOccurrenceId() { return occurrenceId; }
    public String getRawPayload() { return rawPayload; }
    public String getSourceVersion() { return sourceVersion; }
    public Instant getCollectedAt() { return collectedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
