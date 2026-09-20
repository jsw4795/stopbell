package com.stopbell.transit.client;

import com.stopbell.transit.domain.TransitProvider;

/**
 * Fetches a provider's current vehicle-location response for one route.
 *
 * @param <R> provider raw response type
 * @param <C> provider-specific request context type
 */
public interface TransitProviderClient<R, C extends VehicleLocationRequestContext> {

    TransitProvider provider();

    R fetchVehicleLocations(VehicleLocationRequest<C> request);
}
