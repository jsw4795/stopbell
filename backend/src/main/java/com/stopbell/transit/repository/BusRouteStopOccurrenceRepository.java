package com.stopbell.transit.repository;

import java.util.List;
import java.util.Optional;

import com.stopbell.transit.domain.TransitProvider;

import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BusRouteStopOccurrenceRepository extends JpaRepository<BusRouteStopOccurrence, Long> {

    @Query("""
            select stop.stopName from BusRouteStopOccurrence occurrence
            join occurrence.stop stop
            join occurrence.route route
            where occurrence.id = :id and occurrence.stopOrder = :stopOrder
              and stop.provider = :provider and stop.externalStopId = :externalStopId
              and route.provider = :provider and route.externalRouteId = :externalRouteId
            """)
    Optional<String> findSnapshotStopName(Long id, int stopOrder, TransitProvider provider,
            String externalStopId, String externalRouteId);

    @Query("""
            select occurrence from BusRouteStopOccurrence occurrence
            join fetch occurrence.stop join fetch occurrence.route
            where occurrence.id in :ids
            """)
    List<BusRouteStopOccurrence> findTimelineMetadata(java.util.Collection<Long> ids);

    @Query("""
            select occurrence from BusRouteStopOccurrence occurrence
            join fetch occurrence.stop
            where occurrence.route = :route
            order by occurrence.stopOrder asc
            """)
    List<BusRouteStopOccurrence> findAllByRouteOrderByStopOrderAsc(BusRoute route);
}
