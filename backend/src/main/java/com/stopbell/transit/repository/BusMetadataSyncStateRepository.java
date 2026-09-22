package com.stopbell.transit.repository;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusMetadataSyncState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusMetadataSyncStateRepository extends JpaRepository<BusMetadataSyncState, TransitProvider> {
}
