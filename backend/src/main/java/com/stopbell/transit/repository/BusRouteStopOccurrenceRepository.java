package com.stopbell.transit.repository;

import java.util.List;

import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusRouteStopOccurrenceRepository extends JpaRepository<BusRouteStopOccurrence, Long> {

    List<BusRouteStopOccurrence> findAllByRouteOrderByStopOrderAsc(BusRoute route);
}
