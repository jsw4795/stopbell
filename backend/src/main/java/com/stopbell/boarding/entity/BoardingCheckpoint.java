package com.stopbell.boarding.entity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public class BoardingCheckpoint {
    public enum Evidence { ID_ORDER, VERIFIED_ORDER_ONLY }

    @Column(name = "checkpoint_stop_external_id")
    private String stopExternalId;
    @Column(name = "checkpoint_stop_order", nullable = false)
    private int stopOrder;
    @Column(name = "checkpoint_index", nullable = false)
    private int index;
    @Enumerated(EnumType.STRING)
    @Column(name = "checkpoint_evidence", nullable = false, length = 32)
    private Evidence evidence;
    @Column(name = "checkpoint_direction_context")
    private String directionContext;
    @Column(name = "checkpoint_observed_at", nullable = false)
    private LocalDateTime observedAt;
    @Column(name = "checkpoint_provider_data_time")
    private LocalDateTime providerDataTime;

    protected BoardingCheckpoint() {
    }

    public BoardingCheckpoint(String stopExternalId, int stopOrder, int index, Evidence evidence,
            String directionContext, LocalDateTime observedAt, LocalDateTime providerDataTime) {
        this.stopExternalId = stopExternalId;
        this.stopOrder = stopOrder;
        this.index = index;
        this.evidence = evidence;
        this.directionContext = directionContext;
        this.observedAt = observedAt;
        this.providerDataTime = providerDataTime;
        validate();
        this.observedAt = observedAt.truncatedTo(ChronoUnit.MICROS);
        this.providerDataTime = providerDataTime == null ? null : providerDataTime.truncatedTo(ChronoUnit.MICROS);
    }

    private void validate() {
        if (stopOrder <= 0 || index < 0 || evidence == null || observedAt == null
                || observedAt.getYear() < 1000 || observedAt.getYear() > 9999
                || (evidence == Evidence.ID_ORDER ? stopExternalId == null || stopExternalId.isBlank()
                    || stopExternalId.length() > 255 : stopExternalId != null)
                || (directionContext != null && (directionContext.isBlank() || directionContext.length() > 255))
                || (providerDataTime != null && (providerDataTime.getYear() < 1000 || providerDataTime.getYear() > 9999
                    || providerDataTime.isAfter(observedAt)))) {
            throw new IllegalArgumentException("Invalid checkpoint evidence or time");
        }
    }

    public void validateAgainst(BoardingTraversalSnapshot snapshot) {
        validate();
        if (index >= snapshot.occurrences().size()) {
            throw new IllegalArgumentException("Checkpoint is outside traversal");
        }
        var occurrence = snapshot.occurrences().get(index);
        if (stopOrder != occurrence.order() || (evidence == Evidence.ID_ORDER
                && !occurrence.externalStopId().equals(stopExternalId))) {
            throw new IllegalArgumentException("Checkpoint contradicts traversal");
        }
    }

    public String getStopExternalId() {
        return stopExternalId;
    }

    public int getStopOrder() {
        return stopOrder;
    }

    public int getIndex() {
        return index;
    }

    public Evidence getEvidence() {
        return evidence;
    }

    public String getDirectionContext() {
        return directionContext;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public LocalDateTime getProviderDataTime() {
        return providerDataTime;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BoardingCheckpoint value && stopOrder == value.stopOrder && index == value.index
                && evidence == value.evidence && java.util.Objects.equals(stopExternalId, value.stopExternalId)
                && java.util.Objects.equals(directionContext, value.directionContext)
                && java.util.Objects.equals(observedAt, value.observedAt)
                && java.util.Objects.equals(providerDataTime, value.providerDataTime);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(stopExternalId, stopOrder, index, evidence, directionContext, observedAt, providerDataTime);
    }
}
