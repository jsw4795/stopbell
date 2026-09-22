package com.stopbell.transit.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.stopbell.transit.domain.TransitProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BusMetadataBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(BusMetadataBootstrapService.class);

    private final Map<TransitProvider, BusMetadataSource> sources;
    private final Map<TransitProvider, AtomicBoolean> running = new EnumMap<>(TransitProvider.class);
    private final BusMetadataSyncService syncService;

    public BusMetadataBootstrapService(List<BusMetadataSource> sources, BusMetadataSyncService syncService) {
        this.sources = new EnumMap<>(TransitProvider.class);
        for (BusMetadataSource source : sources) {
            if (this.sources.put(source.provider(), source) != null) {
                throw new IllegalStateException("Duplicate metadata source for " + source.provider());
            }
            running.put(source.provider(), new AtomicBoolean());
        }
        this.syncService = syncService;
    }

    public void bootstrap(TransitProvider provider) {
        BusMetadataSource source = sources.get(provider);
        if (source == null) {
            throw new IllegalArgumentException("No metadata source configured for " + provider);
        }
        AtomicBoolean providerRunning = running.get(provider);
        if (!providerRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("Metadata sync is already running for " + provider);
        }
        try {
            log.info("Metadata bootstrap started: provider={}", provider);
            syncService.syncCompleteProviderSnapshot(source.fetchCompleteSnapshot());
            log.info("Metadata bootstrap completed: provider={}", provider);
        } finally {
            providerRunning.set(false);
        }
    }
}
