package com.stopbell.transit.repository;

import java.util.List;

import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BusRouteStopOccurrenceRepository extends JpaRepository<BusRouteStopOccurrence, Long> {

    @Query("""
            select occurrence from BusRouteStopOccurrence occurrence
            join fetch occurrence.stop
            where occurrence.route = :route
            order by occurrence.stopOrder asc
            """)
    List<BusRouteStopOccurrence> findAllByRouteOrderByStopOrderAsc(BusRoute route);
}
