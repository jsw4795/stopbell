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
}
