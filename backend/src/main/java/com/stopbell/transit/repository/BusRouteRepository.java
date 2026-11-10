package com.stopbell.transit.repository;

import java.util.List;
import java.util.Optional;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusRouteRepository extends JpaRepository<BusRoute, Long> {

    Optional<BusRoute> findByProviderAndExternalRouteId(TransitProvider provider, String externalRouteId);

    List<BusRoute> findAllByProvider(TransitProvider provider);

    @Query(value = """
            SELECT br.* FROM bus_routes br
            WHERE LOWER(LEFT(br.route_number, CHAR_LENGTH(:query))) = LOWER(:query)
               OR LOWER(LEFT(REGEXP_REPLACE(br.route_number, '^[A-Za-z]+', ''), CHAR_LENGTH(:query))) = LOWER(:query)
            ORDER BY br.route_number ASC, br.id ASC
            LIMIT 50
            """, nativeQuery = true)
    List<BusRoute> searchByRouteNumberPrefix(@Param("query") String query);

    @Query(value = """
            SELECT e.route_id AS routeId, e.start_stop_name AS startStopName, e.end_stop_name AS endStopName
            FROM seoul_route_endpoints e JOIN bus_routes br ON br.id = e.route_id
            WHERE br.id IN (:routeIds) AND br.provider = 'SEOUL_BUS'
              AND BINARY br.external_route_id = BINARY e.external_route_id
              AND BINARY br.route_number = BINARY e.route_number
            """, nativeQuery = true)
    List<SeoulEndpoints> findSeoulEndpoints(@Param("routeIds") List<Long> routeIds);

    interface SeoulEndpoints {
        Long getRouteId();
        String getStartStopName();
        String getEndStopName();
    }
}
