package com.stopbell.transit.entity;

import com.stopbell.transit.domain.TransitCollectionFailureCode;
import com.stopbell.transit.domain.TransitCollectionFailureStage;
import com.stopbell.transit.domain.TransitCollectionStatus;
import com.stopbell.transit.domain.TransitProvider;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "transit_metadata_collection_runs")
public class TransitMetadataCollectionRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private TransitProvider provider;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private TransitCollectionStatus status;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Enumerated(EnumType.STRING) @Column(name = "failure_stage", length = 20)
    private TransitCollectionFailureStage failureStage;
    @Enumerated(EnumType.STRING) @Column(name = "failure_code", length = 32)
    private TransitCollectionFailureCode failureCode;
    @Column(name = "failure_message", length = 255)
    private String failureMessage;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TransitMetadataCollectionRun() { }

    public TransitMetadataCollectionRun(TransitProvider provider, Instant now) {
        this.provider = Objects.requireNonNull(provider);
        this.startedAt = Objects.requireNonNull(now);
        this.createdAt = now;
        this.updatedAt = now;
        this.status = TransitCollectionStatus.COLLECTING;
    }

    public void collected(Instant now) { advance(TransitCollectionStatus.COLLECTING, TransitCollectionStatus.COLLECTED, now); }
    public void syncing(Instant now) { advance(TransitCollectionStatus.COLLECTED, TransitCollectionStatus.SYNCING, now); }
    /** Only the orchestrator can establish that provider synchronization has actually succeeded. */
    public void complete(Instant now) { advance(TransitCollectionStatus.SYNCING, TransitCollectionStatus.COMPLETED, now); }

    public void fail(TransitCollectionFailureStage stage, TransitCollectionFailureCode code, Instant now) {
        Objects.requireNonNull(stage);
        Objects.requireNonNull(code);
        if (status == TransitCollectionStatus.FAILED) {
            if (failureStage != stage || failureCode != code) throw new IllegalStateException("Conflicting failure retry");
            return;
        }
        requireActive();
        setTime(now);
        status = TransitCollectionStatus.FAILED;
        finishedAt = now;
        failureStage = stage;
        failureCode = code;
        failureMessage = code.message();
    }

    public void interrupt(Instant now) {
        if (status == TransitCollectionStatus.INTERRUPTED) return;
        requireActive();
        setTime(now);
        status = TransitCollectionStatus.INTERRUPTED;
        finishedAt = now;
    }

    private void advance(TransitCollectionStatus expected, TransitCollectionStatus target, Instant now) {
        if (status == target) return;
        if (status != expected) throw new IllegalStateException("Invalid collection state transition");
        setTime(now);
        status = target;
        if (target == TransitCollectionStatus.COMPLETED) finishedAt = now;
    }

    private void requireActive() {
        if (status != TransitCollectionStatus.COLLECTING && status != TransitCollectionStatus.COLLECTED
                && status != TransitCollectionStatus.SYNCING) throw new IllegalStateException("Collection is already terminal");
    }

    private void setTime(Instant now) {
        Objects.requireNonNull(now);
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("Collection time moved backwards");
        updatedAt = now;
    }

    public Long getId() { return id; }
    public TransitProvider getProvider() { return provider; }
    public TransitCollectionStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public TransitCollectionFailureStage getFailureStage() { return failureStage; }
    public TransitCollectionFailureCode getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
