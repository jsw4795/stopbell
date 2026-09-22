package com.stopbell.transit.entity;

import java.time.Instant;

import com.stopbell.transit.domain.TransitProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bus_metadata_sync_states")
public class BusMetadataSyncState {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransitProvider provider;

    @Column(name = "last_complete_sync_at", nullable = false)
    private Instant lastCompleteSyncAt;

    protected BusMetadataSyncState() {
    }

    public BusMetadataSyncState(TransitProvider provider, Instant lastCompleteSyncAt) {
        this.provider = provider;
        this.lastCompleteSyncAt = lastCompleteSyncAt;
    }

    public void markComplete(Instant completedAt) {
        this.lastCompleteSyncAt = completedAt;
    }

    public TransitProvider getProvider() { return provider; }
    public Instant getLastCompleteSyncAt() { return lastCompleteSyncAt; }
}
