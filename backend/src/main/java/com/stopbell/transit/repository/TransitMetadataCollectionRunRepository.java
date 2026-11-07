package com.stopbell.transit.repository;

import com.stopbell.transit.domain.TransitCollectionStatus;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.TransitMetadataCollectionRun;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransitMetadataCollectionRunRepository extends JpaRepository<TransitMetadataCollectionRun, Long> {
    List<TransitMetadataCollectionRun> findByProviderOrderByStartedAtDesc(TransitProvider provider);
    List<TransitMetadataCollectionRun> findByStatusOrderByStartedAtDesc(TransitCollectionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TransitMetadataCollectionRun r where r.id = :id")
    Optional<TransitMetadataCollectionRun> findForUpdate(@Param("id") Long id);
}
