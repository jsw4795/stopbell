package com.stopbell.transit.service;

import com.stopbell.transit.domain.TransitProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Enabled only by an explicit --transit.metadata.bootstrap.provider command-line property. */
@Component
@ConditionalOnProperty(name = "transit.metadata.bootstrap.provider")
public class MetadataBootstrapRunner implements ApplicationRunner {
    private final BusMetadataBootstrapService bootstrapService;
    private final String provider;
    public MetadataBootstrapRunner(BusMetadataBootstrapService bootstrapService,
                                   @org.springframework.beans.factory.annotation.Value("${transit.metadata.bootstrap.provider}") String provider) { this.bootstrapService=bootstrapService; this.provider=provider; }
    @Override public void run(ApplicationArguments args) { bootstrapService.bootstrap(TransitProvider.valueOf(provider)); }
}
