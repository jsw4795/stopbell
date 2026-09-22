package com.stopbell.transit.service;

import com.stopbell.transit.domain.TransitProvider;

public interface BusMetadataSource {

    TransitProvider provider();

    CompleteBusMetadataSnapshot fetchCompleteSnapshot();
}
