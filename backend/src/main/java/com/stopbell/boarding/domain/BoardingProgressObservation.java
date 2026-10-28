package com.stopbell.boarding.domain;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle;

/** Verified semantics must come from evidence, never from a matching numeric order alone. */
public record BoardingProgressObservation(
        TransitProvider provider, String routeId, String cityCode, Vehicle vehicle,
        Meaning meaning, boolean orderOnlyVerified, String directionContext, boolean traversalAmbiguous
) {
    public enum Meaning { UNVERIFIED, VERIFIED_REFERENCE, VERIFIED_REACHED, DIRECT_ARRIVAL }
}
