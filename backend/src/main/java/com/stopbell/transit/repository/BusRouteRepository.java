package com.stopbell.transit.repository;

import java.util.List;
import java.util.Optional;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusRouteRepository extends JpaRepository<BusRoute, Long> {

    Optional<BusRoute> findByProviderAndExternalRouteId(TransitProvider provider, String externalRouteId);

    List<BusRoute> findAllByProvider(TransitProvider provider);

    List<BusRoute> findTop50ByRouteNumberStartingWithIgnoreCaseOrderByRouteNumberAscIdAsc(String routeNumber);
}
