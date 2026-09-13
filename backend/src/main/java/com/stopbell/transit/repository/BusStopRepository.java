package com.stopbell.transit.repository;

import java.util.Optional;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusStopRepository extends JpaRepository<BusStop, Long> {

    Optional<BusStop> findByProviderAndExternalStopId(TransitProvider provider, String externalStopId);

    @Query("""
            select stop from BusStop stop
            where stop.provider = :provider
              and not exists (
                  select occurrence.id from BusRouteStopOccurrence occurrence where occurrence.stop = stop
              )
            """)
    java.util.List<BusStop> findOrphanedByProvider(@Param("provider") TransitProvider provider);
}
