package com.stopbell.transit.service;

import com.stopbell.transit.domain.TransitCollectionFailureCode;
import com.stopbell.transit.domain.TransitCollectionFailureStage;
import com.stopbell.transit.domain.TransitCollectionStatus;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.TransitMetadataCollectionRun;
import com.stopbell.transit.repository.TransitMetadataCollectionRunRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransitMetadataCollectionHistoryService {
    private final TransitMetadataCollectionRunRepository repository;
    private final Clock clock;

    public TransitMetadataCollectionHistoryService(TransitMetadataCollectionRunRepository repository,
            @Qualifier("transitMetadataClock") Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public TransitMetadataCollectionRun start(TransitProvider provider) {
        return repository.save(new TransitMetadataCollectionRun(provider, now()));
    }

    @Transactional(readOnly = true)
    public TransitMetadataCollectionRun find(Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Collection run not found"));
    }

    @Transactional(readOnly = true)
    public List<TransitMetadataCollectionRun> findByProvider(TransitProvider provider) {
        return repository.findByProviderOrderByStartedAtDesc(provider);
    }

    @Transactional(readOnly = true)
    public List<TransitMetadataCollectionRun> findByStatus(TransitCollectionStatus status) {
        return repository.findByStatusOrderByStartedAtDesc(status);
    }

    @Transactional public TransitMetadataCollectionRun collected(Long id) {
        TransitMetadataCollectionRun run = locked(id); run.collected(now()); return run;
    }
    @Transactional public TransitMetadataCollectionRun syncing(Long id) {
        TransitMetadataCollectionRun run = locked(id); run.syncing(now()); return run;
    }
    @Transactional public TransitMetadataCollectionRun complete(Long id) {
        TransitMetadataCollectionRun run = locked(id); run.complete(now()); return run;
    }
    @Transactional public TransitMetadataCollectionRun fail(Long id, TransitCollectionFailureStage stage, TransitCollectionFailureCode code) {
        TransitMetadataCollectionRun run = locked(id); run.fail(stage, code, now()); return run;
    }
    @Transactional public TransitMetadataCollectionRun interrupt(Long id) {
        TransitMetadataCollectionRun run = locked(id); run.interrupt(now()); return run;
    }

    private java.time.Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private TransitMetadataCollectionRun locked(Long id) {
        return repository.findForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Collection run not found"));
    }
}
